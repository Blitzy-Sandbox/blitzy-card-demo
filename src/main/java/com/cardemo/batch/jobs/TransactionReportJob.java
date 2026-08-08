/*
 * ******************************************************************
 * Program     : TransactionReportJob.java
 * Application : CardDemo
 * Type        : Spring Batch Job Configuration
 * Function    : Transaction report — backup, card-ordered filtered sort and paginated 133-byte report emission.
 * Source      : app/jcl/TRANREPT.jcl + app/proc/TRANREPT.prc + app/proc/REPROC.prc + app/ctl/REPROCT.ctl + app/cbl/CBTRN03C.cbl (649 lines, 26 own paragraph labels) @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.batch.jobs;

import com.cardemo.batch.GenerationPrefixContract;
import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.batch.processors.TransactionReportProcessor.ReportLines;
import com.cardemo.batch.readers.TransactionBackupReader;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRepository;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Spring Batch replacement for the transaction-report stream, with the clean procedure member defining
 * the executable topology and the standalone JCL retained only as defect evidence.
 *
 * <h2>Purpose, inputs, outputs and side effects</h2>
 *
 * <p>The job has exactly three steps in {@code app/proc/TRANREPT.prc} order. STEP01R at {@code :L21}
 * copies the transaction relation to one 350-byte {@code TRANSACT.BKUP(+1)} object. STEP05R at
 * {@code :L35-L53} reads that exact key, applies the inclusive character date predicate at
 * {@code :L45-L46}, sorts on the card-number character image at {@code :L44}, and writes one 350-byte
 * {@code TRANSACT.DALY(+1)} object. STEP10R at {@code :L57-L78} reads that exact daily key, delegates
 * report state and formatting to {@link TransactionReportProcessor}, and writes one undelimited stream of
 * 133-byte {@code TRANREPT(+1)} records.
 *
 * <h2>Collaborator ownership - this class is the single owner</h2>
 * <b>This class constructs {@link TransactionBackupReader} and {@link TransactionReportProcessor} directly,
 * and is the only class in the tree that references either type.</b> Neither carries {@code @Component} or
 * {@code @StepScope}, so the container publishes no definition of either and there is exactly one ownership
 * model per type.
 *
 * <p><b>Finding F-008, severity Medium.</b> Annotating both classes as
 * {@code @Component @StepScope} with {@code @Value} bindings on their constructors <em>while</em> this class
 * builds them with {@code new} publishes bean definitions no production path resolves, so the
 * annotations would describe a container lifecycle that never runs and a property binding that never takes
 * effect - two ownership models for one type, with the unused one the more prominent in the source. Neither
 * annotation nor {@code @Value} expression is declared on either class, and this class is the declared owner,
 * for three reasons that are properties of this job rather than preferences:
 * <ul>
 *   <li><b>One scoped definition could not have served both reader call sites.</b> STEP01R needs the
 *       {@code repository} substrate over the {@code TRANSACT.BKUP} prefix with no promoted key, and STEP10R
 *       needs {@code object-storage} over the {@code TRANSACT.DALY} prefix with the concrete key STEP05R
 *       promoted. That is why this class was passing its own arguments to begin with.</li>
 *   <li><b>Construction validates, and the validation must stay inside the step.</b>
 *       {@link #requireConcreteKey} and the reader's own generation-key checks raise typed
 *       {@code CardDemoException}s that let the failing step discard the generation it created through
 *       {@link #discardOwnGenerationOnFailure} and abend with the operator-facing cause. Behind a scoped
 *       proxy the same failures would arrive as {@code BeanCreationException} at first method call and be
 *       reported as a generic abend.</li>
 *   <li><b>The steps are tasklets, not chunk-oriented steps.</b> {@link #mainlineProcedureDivision} drives
 *       {@code open}, {@code read}, {@code update} and {@code close} by hand to reproduce the six-paragraph
 *       lifecycle of {@code app/cbl/CBTRN03C.cbl:L163-L212} in order, so there is no chunk-oriented
 *       {@code reader}/{@code processor} slot for the framework to fill.</li>
 * </ul>
 *
 * <p>Per-step-execution isolation is preserved without a scope: a fresh instance of each collaborator is
 * created inside each tasklet body, and neither class holds static mutable state.
 * {@code TransactionReportProcessorScopeIsolationTest} asserts both facts - no component or scope annotation,
 * no static mutable field - so the guarantee is enforced rather than asserted in prose. The sibling
 * collaborators that <em>are</em> container-owned keep their annotations and are injected as step-bean
 * parameters, which is the other half of the same rule: {@code TransactionCombineProcessor} and
 * {@code CombinedTransactionReader} in {@link CombineTransactionsJob}, and the four verification readers in
 * {@link BatchPipelineOrchestrator}.
 *
     * <p>The required job parameters are {@code startDate} and {@code endDate}. They replace the 80-byte
     * {@code DATEPARM} DD at {@code app/proc/TRANREPT.prc:L71-L72} and originate from the fixed 80-byte JOBS
     * queue records at {@code app/csd/CARDDEMO.CSD:L499-L505}, transported to the migrated job through its SQS
     * FIFO ingress. Each value must be exactly ten ASCII characters in {@code yyyy-MM-dd} shape, must pass
     * {@link DateValidationService}, and the start must not follow the end. The comparison itself stays lexical:
     * {@code TRAN-PROC-TS (1:10)} at
 * {@code app/cbl/CBTRN03C.cbl:L173-L174} is never converted to a temporal object.
 *
     * <p>The zero-padded 19-digit job-instance generation makes each {@code (+1)} key monotonically increasing
     * and makes legacy {@code (0)} the lexicographically greatest key beneath its dataset prefix. Each created
     * key is published to both the producing step execution context and the job execution context. The next
     * step consumes that concrete value; it never lists the bucket to resolve “latest” while the job is running.
     * This reproduces the same-job {@code (+1)} handoffs at {@code app/proc/TRANREPT.prc:L31-L37} and
     * {@code :L53-L64}, and prevents a concurrently created, lexicographically newer object from changing the
     * input between steps.
 *
 * <h2>The five-member resolution chain</h2>
 *
 * <ol>
 *   <li>{@code app/cbl/CORPT00C.cbl:L94} emits
 *       {@code "//STEP10 EXEC PROC=TRANREPT".}, the sole {@code EXEC PROC=TRANREPT} in the corpus. The
 *       complete {@code EXEC PROC=} census has five sites: TRANBKP.jcl:L23, TRANREPT.jcl:L23,
 *       PRTCATBL.jcl:L29 and TRANREPT.prc:L21 all invoke {@code REPROC}; CORPT00C:L94 invokes
 *       {@code TRANREPT}.</li>
 *   <li>JES resolves {@code PROC=TRANREPT} by member name to {@code app/proc/TRANREPT.prc}.</li>
 *   <li>{@code app/proc/TRANREPT.prc:L1} nevertheless reads {@code //REPROC PROC}. The name field documents
 *       the member but does not select it, so the member resolves as TRANREPT while self-labelling REPROC.
 *       {@code app/proc/REPROC.prc:L1} carries the same declaration. This Low finding is logged and not
 *       repaired.</li>
 *   <li>{@code TRANREPT.prc:L21} nests {@code EXEC PROC=REPROC}; {@code app/proc/REPROC.prc:L21} runs the
 *       copy utility, {@code :L28} selects {@code REPROCT}, and {@code app/ctl/REPROCT.ctl:L15} contains
 *       {@code REPRO INFILE(FILEIN) OUTFILE(FILEOUT)}. The target performs that copy in process.</li>
 *   <li>{@code app/jcl/TRANREPT.jcl:L23} and {@code :L37} both name STEP05R, while the procedure correctly
 *       names STEP01R/STEP05R/STEP10R at {@code app/proc/TRANREPT.prc:L21,L35,L57}. The procedure is the
 *       authority; both source members are logged and neither is edited.</li>
 * </ol>
 *
 * <h2>Findings and preserved source behaviour</h2>
 *
 * <ul>
 *   <li><strong>Blocker — character timestamp contract.</strong> The 26-character processing timestamp
 *       remains a {@link String}; only its first ten characters participate in comparison. The independent
 *       offset evidence is {@code TRAN-PROC-DT,305,10,CH} at
 *       {@code app/proc/TRANREPT.prc:L40}. A temporal conversion would change the accepted domain.</li>
 *   <li><strong>High — duplicate step ambiguity.</strong> The duplicate STEP05R at
 *       {@code app/jcl/TRANREPT.jcl:L23,L37} is functionally ambiguous because
 *       {@code app/cbl/CORPT00C.cbl:L98} emits the qualified override
 *       {@code //STEP05R.SYMNAMES DD *}. Remediation is to execute the clean procedure member, not to edit
 *       the frozen JCL.</li>
 *   <li><strong>High — whole-loop transfer.</strong> {@code NEXT SENTENCE} at
 *       {@code app/cbl/CBTRN03C.cbl:L177} transfers past the period on {@code END-PERFORM.} at {@code :L206}.
 *       Therefore the first out-of-range record terminates report processing; it is not skipped. This class
 *       turns the processor’s first filtered result into loop termination and omits the end-of-data totals,
 *       exactly as that transfer does.</li>
 *   <li><strong>Medium — retention conflict resolved to 10.</strong>
 *       {@code app/jcl/DEFGDGB.jcl:L38-L39} declares LIMIT(5) with SCRATCH, while
 *       {@code app/jcl/REPTFILE.jcl:L27} declares LIMIT(10) without SCRATCH. The larger value is retained by
 *       {@code carddemo.aws.s3.gdg-retention-generations}; this job logs it but performs no deletion. Object
 *       versioning supersedes both declarations. This is the migration’s only resolved legacy
 *       inconsistency.</li>
 *   <li><strong>Low.</strong> In addition to the internal REPROC label, the stray backtick at
 *       {@code app/jcl/TRANREPT.jcl:L20} and the header-only notice at
 *       {@code app/ctl/REPROCT.ctl:L1-L14} are recorded and left unchanged.</li>
 * </ul>
 *
 * <p>The second date test is deliberately retained after the sort test; it is not redundant cleanup. It is
 * the test that interacts with the whole-loop transfer above. The control break remains a card-number change
 * at {@code app/cbl/CBTRN03C.cbl:L181}, while the emitted line remains labelled {@code Account Total} by
 * {@code 1120-WRITE-ACCOUNT-TOTALS} at {@code :L306-L316}. The {@code WS-FIRST-TIME} flag at {@code :L128}
 * suppresses a total before the first card. The reachable end-of-data branch belongs to the inner IF at
 * {@code :L179-L203}; it double-counts the last amount by adding it a second time to page and account totals,
 * then emits page and grand totals without a final account-total line. By contrast,
 * {@code app/cbl/CBACT04C.cbl:L219-L220}
 * attaches its ELSE to the outer IF and cannot reach it, while
 * {@code app/cbl/CBTRN02C.cbl:L202-L219} gives neither IF an ELSE. These three shapes must not be
 * harmonised.
 *
 * <p>Page size is 20 at {@code app/cbl/CBTRN03C.cbl:L131-L132}; {@code WS-BLANK-LINE PIC X(133)} at
 * {@code :L133} independently corroborates the report width. All report totals and transaction amounts use
 * {@link BigDecimal} at scale two with {@link RoundingMode#HALF_EVEN}; comparison uses
 * {@link BigDecimal#compareTo(BigDecimal)}. The card number occupies bytes 263-278 and the processing stamp
 * bytes 305-330 of the 350-byte record; {@code app/proc/TRANREPT.prc:L39-L40} independently corroborates both
 * offsets. Active {@code DISPLAY TRAN-RECORD} at {@code app/cbl/CBTRN03C.cbl:L180} is delegated to the
 * processor’s masked DEBUG event; no full 16-digit card number is emitted by this class.
 *
 * <h2>Configuration and defaults</h2>
 *
 * <ul>
 *   <li>{@code carddemo.batch.jobs.tranrept.name}: {@code TRANREPT}. The {@code jobs.} segment is the
 *       one spelling and is deliberate: {@code DailyTransactionPostingJob},
 *       {@code InterestCalculationJob}, {@code CombineTransactionsJob} and
 *       {@code StatementGenerationJob} all bind {@code carddemo.batch.jobs.<id>.name}, and the profile
 *       declares the registry under that namespace. Binding
 *       {@code carddemo.batch.tranrept.name} instead would leave the declared profile key read by nothing
 *       while the key this class actually reads appears in no profile - both halves of one drift, recorded as
 *       finding CFG-002.</li>
 *   <li>{@code carddemo.batch.tranrept.chunk-size}: falls back to
 *       {@code carddemo.batch.chunk-size}, then 100. It bounds report-writer batches. The chunk-size
 *       namespace is deliberately {@code carddemo.batch.<id>.chunk-size} and not
 *       {@code carddemo.batch.jobs.<id>.chunk-size}, because that is the spelling all five jobs already
 *       share; only the job name was inconsistent.</li>
 *   <li>{@code carddemo.aws.s3.batch-output-bucket}: blank default, which fails construction. A bucket is
 *       never silently invented.</li>
 *   <li>{@code carddemo.aws.s3.gdg-prefixes.transact-bkup}: {@code gdg/transact-bkup}.</li>
 *   <li>{@code carddemo.aws.s3.gdg-prefixes.transact-daly}: {@code gdg/transact-daly}.</li>
 *   <li>{@code carddemo.aws.s3.gdg-prefixes.tranrept}: {@code gdg/tranrept}.</li>
 *   <li>{@code carddemo.aws.s3.gdg-retention-generations}: 10, documentary only.</li>
 * </ul>
 *
 * <h2>Exit status, observability and failures</h2>
 *
 * <p>Return codes map 0 to completed, 4 to completed-with-rejects, 8 to failed and 12 to abend. RC 4 is
 * recognised for composability but is unreachable here: CBTRN03C assigns no return code, and the sole
 * literal {@code MOVE 4 TO RETURN-CODE} is {@code app/cbl/CBTRN02C.cbl:L230}. An abend is represented by
 * {@link FatalProcessingException} with batch code 999 and return code 12. RC 4 and RC 12 remain independent.
 * Every I/O failure passes through {@link FileStatusMapper}; FILE STATUS {@code '10'} ends a read loop and is
 * never thrown. Causes are preserved.
 *
 * <p>The inline job listener establishes the batch diagnostic context through
 * {@link CorrelationIdFilter#enterBatchScope(long, String)} and releases it through
 * {@link CorrelationIdFilter#exitBatchScope()} in a finally block, so the two entries it displaces are put
 * back exactly rather than removed. It publishes {@code jobInstanceId} and, only when the thread carries
 * none, {@code correlationId}. <b>It publishes no {@code traceId} and no {@code spanId}</b>: this job creates
 * no span, and an identifier minted here would name a trace no backend holds - see finding H-02 on
 * {@link TransactionReportJobListener}. <b>This job advances no application metric at all</b>:
 * the four counters {@code com.cardemo.observability.MetricsConfig} owns are the POSTTRAN tallies of
 * {@code app/cbl/CBTRN02C.cbl:L227-L228} and its authentication and amount counters, and a report line belongs
 * to none of those populations. Volumes are published to the job execution context and to the Spring Batch step
 * metrics, which carry a job dimension. No card number becomes a metric tag, because no tag is added here at
 * all. See finding H-01.
 * <p>The {@code MetricsConfig} collaborator was removed along with the increment rather than left
 * injected and unused - finding F-010. AAP section 0.5.1.9 ties
 * {@code carddemo.batch.records.processed} to exactly the {@code DALYTRAN} population and AAP section
 * 0.7.7 fixes the instrument set at four, none of which is this job's to publish. The per-run quantities
 * this job does produce are execution-context entries - {@code recordCount}, {@code lineCount} and the
 * two upstream generation counts - which is where a per-run figure belongs.
 *
 *
 * <h2>Build, test and troubleshooting</h2>
 *
 * <p>Build with {@code ./mvnw -B -ntp clean verify}. The compiler targets
 * Java 25 with {@code -Xlint:all -Werror}. Jobs do not auto-run because
 * {@code spring.batch.job.enabled} is false. A missing date raises {@link ValidationException}; a missing
 * concrete handoff key raises {@link FatalProcessingException}; a record-width mismatch fails before the
 * object is published; and a missing bucket fails configuration rather than selecting an implicit target.
 *
 * <p><strong>Not available:</strong> the source does not state whether an external object consumer expects
 * newline-delimited records, so this implementation preserves fixed blocks with no delimiter; no service-level
 * latency or throughput objective exists, so none is invented; and integration Gates 1, 4 and 8 require a
 * container runtime. The retention decision is held as {@code DL-CR-02} in {@code DECISION_LOG.md} at the
 * repository root, and restated here and in the {@code application.yml} block that cites both declarations,
 * which is where it cannot drift from the value it explains.
 */
@Configuration("transactionReportJobConfiguration")
public class TransactionReportJob {

    /** Structured application logger; report records themselves are never written to it. */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionReportJob.class);

    /** Unique bean name for STEP01R, {@code app/proc/TRANREPT.prc:L21}. */
    private static final String BACKUP_STEP_BEAN_NAME = "transactionReportBackupStep";

    /** Unique bean name for STEP05R, {@code app/proc/TRANREPT.prc:L35}. */
    private static final String SORT_STEP_BEAN_NAME = "transactionReportSortStep";

    /** Unique bean name for STEP10R, {@code app/proc/TRANREPT.prc:L52}. */
    private static final String GENERATE_STEP_BEAN_NAME = "transactionReportGenerateStep";

    /**
     * Unique bean name for the {@code app/jcl/PRTCATBL.jcl} step, {@value}.
     *
     * <p>One step for all three of that member's, because they are one indivisible unit of work: the
     * {@code DELDEF} pre-delete at {@code :L21-L25}, the {@code STEP05R} unload to {@code TCATBALF.BKUP(+1)}
     * at {@code :L29-L39} and the {@code STEP10R} sort-and-print at {@code :L43-L63} that reads back the very
     * generation the unload wrote.
     */
    private static final String CATEGORY_BALANCE_STEP_BEAN_NAME = "transactionReportCategoryBalanceStep";

    /**
     * Job parameter instructing the {@code app/jcl/PRTCATBL.jcl} print, {@value}.
     *
     * <p>{@code PRTCATBL} is a separate member from {@code TRANREPT}, so its work is gated rather than
     * unconditional: a standalone transaction report must not also unload and print a different cluster.
     * {@link BatchPipelineOrchestrator} sets it non-identifying on the report branch, which is where the
     * stream's reporting work belongs, and that is what gives the {@code TCATBALF.BKUP} generation base a
     * real producer and a real consumer (finding M-05).
     */
    public static final String JOB_PARAMETER_PRINT_CATEGORY_BALANCES = "printCategoryBalances";

    /** Logical name of the {@code STEP05R} input, {@code app/jcl/PRTCATBL.jcl:L32-L33}. */
    private static final String DD_CATEGORY_BALANCE_INPUT = "TCATBALF.VSAM.KSDS";

    /** Logical name of the {@code STEP05R} output, {@code app/jcl/PRTCATBL.jcl:L35-L39}. */
    private static final String DD_CATEGORY_BALANCE_BACKUP = "TCATBALF.BKUP";

    /** Logical name of the {@code STEP10R} output, {@code app/jcl/PRTCATBL.jcl:L59-L63}. */
    private static final String DD_CATEGORY_BALANCE_REPORT = "TCATBALF.REPT";

    /** Terminal object name of the unloaded generation. */
    private static final String CATEGORY_BALANCE_BACKUP_OBJECT_NAME = "TCATBALF.BKUP";

    /**
     * Key suffix of the report object, which is deliberately <b>not</b> a generation base.
     *
     * <p>{@code app/jcl/PRTCATBL.jcl:L21-L25} allocates {@code TCATBALF.REPT} with {@code DISP=(MOD,DELETE)}
     * under {@code IEFBR14} and {@code STEP10R} re-creates it, so the member keeps exactly one report and
     * replaces it on every run. A single fixed key reproduces that; a generation would invent retention the
     * member does not ask for.
     */
    private static final String CATEGORY_BALANCE_REPORT_OBJECT_NAME = "reports/TCATBALF.REPT";

    /**
     * Record length of the unloaded cluster, {@code app/jcl/PRTCATBL.jcl:L37} {@code LRECL=50}.
     *
     * <p>Corroborated independently by {@code app/cpy/CVTRA01Y.cpy} and by the cluster's catalogued record
     * size, and consistent with the {@code SYMNAMES} offsets at {@code :L47-L50}.
     */
    private static final int CATEGORY_BALANCE_RECORD_LENGTH = 50;

    /**
     * Line length of the printed report, {@code app/jcl/PRTCATBL.jcl:L53} {@code LRECL=40}.
     *
     * <p><b>A legacy arithmetic defect lives here, and it is resolved in favour of the declared length.</b>
     * The {@code OUTREC} at {@code :L53-L56} lists {@code TRANCAT-ACCT-ID,X, TRANCAT-TYPE-CD,X,
     * TRANCAT-CD,X, TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),9X} - that is 11+1+2+1+4+1+12+9 = <b>41</b> bytes into a
     * {@code SORTOUT} declared {@code LRECL=40}. The two cannot both hold. The declared record length is the
     * contract every consumer of the report reads, so 40 is emitted and the trailing filler is 8 blanks
     * rather than 9. The divergence is logged once per run rather than silently absorbed.
     */
    private static final int CATEGORY_BALANCE_REPORT_LINE_LENGTH = 40;

    /** Trailing filler on the printed line: 8 blanks, one fewer than {@code :L56} asks for. */
    private static final int CATEGORY_BALANCE_REPORT_FILLER_WIDTH = 8;

    /** Context entry carrying the concrete {@code TCATBALF.BKUP} key this run created. */
    public static final String CATEGORY_BALANCE_BACKUP_KEY_CONTEXT =
            "carddemo.prtcatbl.tcatbalf-bkup.objectKey";

    /** Context entry carrying how many records that generation holds. */
    public static final String CATEGORY_BALANCE_BACKUP_COUNT_CONTEXT =
            "carddemo.prtcatbl.tcatbalf-bkup.recordCount";

    /** Context entry carrying the concrete {@code TCATBALF.REPT} key this run wrote. */
    public static final String CATEGORY_BALANCE_REPORT_KEY_CONTEXT =
            "carddemo.prtcatbl.tcatbalf-rept.objectKey";

    /** Context entry carrying how many lines that report holds. */
    public static final String CATEGORY_BALANCE_REPORT_LINE_COUNT_CONTEXT =
            "carddemo.prtcatbl.tcatbalf-rept.lineCount";

    /** Unique flow bean name, kept distinct from any future shared batch configuration. */
    private static final String FLOW_BEAN_NAME = "transactionReportFlow";

    /** Required job bean name. */
    private static final String JOB_BEAN_NAME = "transactionReportJob";

    /** Transaction backup and daily record length, {@code app/proc/TRANREPT.prc:L29,L51}. */
    private static final int TRANSACTION_RECORD_LENGTH = 350;

    /** Report record length, {@code app/proc/TRANREPT.prc:L76}. */
    private static final int REPORT_RECORD_LENGTH = 133;

    /** Zero-based start of card bytes 263-278, corroborated by {@code TRANREPT.prc:L39}. */
    private static final int CARD_NUMBER_OFFSET = 262;

    /** Width of {@code TRAN-CARD-NUM,263,16,ZD}, {@code app/proc/TRANREPT.prc:L39}. */
    private static final int CARD_NUMBER_LENGTH = 16;

    /** Zero-based start of processing bytes 305-314, corroborated by {@code TRANREPT.prc:L40}. */
    private static final int PROCESSING_DATE_OFFSET = 304;

    /** Width of {@code TRAN-PROC-DT,305,10,CH}, {@code app/proc/TRANREPT.prc:L40}. */
    private static final int PROCESSING_DATE_LENGTH = 10;

    /** Width of the date parameter card fields, {@code app/proc/TRANREPT.prc:L41-L42}. */
    private static final int REPORT_DATE_LENGTH = 10;

    /** Financial scale of {@code PIC S9(09)V99}, {@code app/cbl/CBTRN03C.cbl:L134-L136}. */
    private static final int MONEY_SCALE = 2;

    /** Digits in the signed zoned-decimal transaction amount image. */
    private static final int AMOUNT_DIGITS = 11;

    /** Width of the monotonically increasing job-instance segment. */
    private static final int GENERATION_NUMBER_WIDTH = 19;

    /** Resolved documentary retention value. */
    private static final int RESOLVED_REPORT_RETENTION = 10;

    /** The ten-character dashed date the {@code DATEPARM} cards of {@code app/proc/TRANREPT.prc} carry. */
    private static final Pattern PARAMETER_DATE_SHAPE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** Explicit one-byte character mapping at every fixed-width object boundary. */
    private static final Charset FIXED_WIDTH_CHARSET = StandardCharsets.ISO_8859_1;

    /** Object media type for unblocked fixed-width records. */
    private static final String OBJECT_CONTENT_TYPE = "application/octet-stream";

    /** Reader substrate for STEP01R. */
    private static final String READER_SOURCE_REPOSITORY = "repository";

    /** Reader substrate for STEP10R. */
    private static final String READER_SOURCE_OBJECT_STORAGE = "object-storage";

    /** Object name for {@code TRANSACT.BKUP(+1)}, {@code app/proc/TRANREPT.prc:L31}. */
    private static final String BACKUP_OBJECT_NAME = "TRANSACT.BKUP";

    /** Object name for {@code TRANSACT.DALY(+1)}, {@code app/proc/TRANREPT.prc:L53}. */
    private static final String DAILY_OBJECT_NAME = "TRANSACT.DALY";

    /** Object name for {@code TRANREPT(+1)}, {@code app/proc/TRANREPT.prc:L78}. */
    private static final String REPORT_OBJECT_NAME = "TRANREPT";

    /** STEP01R concrete-key handoff. */
    private static final String BACKUP_OBJECT_KEY_CONTEXT =
            "carddemo.tranrept.transact-bkup.objectKey";

    /** STEP05R concrete-key handoff. */
    private static final String DAILY_OBJECT_KEY_CONTEXT =
            "carddemo.tranrept.transact-daly.objectKey";

    /** STEP10R published report key. */
    private static final String REPORT_OBJECT_KEY_CONTEXT =
            "carddemo.tranrept.report.objectKey";

    /** Record count published by STEP01R. */
    private static final String BACKUP_RECORD_COUNT_CONTEXT =
            "carddemo.tranrept.transact-bkup.recordCount";

    /** Record count published by STEP05R. */
    private static final String DAILY_RECORD_COUNT_CONTEXT =
            "carddemo.tranrept.transact-daly.recordCount";

    /** Report-line count published by STEP10R. */
    private static final String REPORT_LINE_COUNT_CONTEXT =
            "carddemo.tranrept.report.lineCount";

    /**
     * Count of detail records STEP10R processed, published per run rather than added to a shared meter.
     *
     * <p><b>Finding F-010, severity High, remediated by this entry.</b> This loop used to call
     * {@code MetricsConfig.countRecordProcessed()} once per report row.
     * {@code carddemo.batch.records.processed} is defined as the {@code DALYTRAN} population that
     * {@code app/cbl/CBTRN02C.cbl:L236} displays as {@code TRANSACTIONS PROCESSED}, and AAP section 0.5.1.9
     * ties the metric to exactly that. The rows this job reads are rows that population <em>already</em>
     * counted when they were posted, so re-counting them here added a second, unrelated quantity to an
     * <b>untagged</b> counter - one with no dimension to subtract along, which is what made the inflation
     * irrecoverable rather than merely wrong.
     *
     * <p>An execution-context entry is the right home for it because the quantity is per run and the
     * consumers are per run: {@code JobExplorer}, the step summary in the log, and an assertion in a test. It
     * is deliberately <b>not</b> a fifth Micrometer instrument, because AAP section 0.7.7 fixes the instrument
     * set at four and this job publishes nothing an operator needs to aggregate across runs.
     *
     * <p>It is distinct from {@value #DAILY_RECORD_COUNT_CONTEXT}, which is what STEP05R wrote and therefore
     * what STEP10R had available to read, and from {@value #REPORT_LINE_COUNT_CONTEXT}, which counts every
     * emitted 133-byte line including headers and totals. The three differ whenever the loop stops early at
     * {@code app/cbl/CBTRN03C.cbl:L177}, and that divergence is the reason all three are published.
     */
    private static final String REPORT_RECORD_COUNT_CONTEXT =
            "carddemo.tranrept.report.recordCount";

    /**
     * {@code LF}, {@code 0x0A}. Named to be <b>refused</b> inside a fixed-block record, never consumed.
     *
     * <p>{@code app/proc/TRANREPT.prc:L29} declares {@code DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)}, so the
     * generation is undelimited: this byte can only appear because something wrote the records as text lines,
     * and every record after the first is then shifted. The same constant, for the same reason, appears in
     * {@code com.cardemo.batch.readers.DailyTransactionReader} and
     * {@code com.cardemo.batch.readers.TransactionBackupReader}; the three are the complete set of fixed-block
     * object read paths in the tree, and none of them tolerates a separator.
     */
    private static final byte LINE_FEED = (byte) '\n';

    /** {@code CR}, {@code 0x0D}. Refused on the same terms as {@link #LINE_FEED}, and covers {@code CRLF}. */
    private static final byte CARRIAGE_RETURN = (byte) '\r';

    /** Sentinel for {@link #publishedCount} when the producing step published nothing. */
    private static final long COUNT_NOT_PUBLISHED = -1L;

    /** JCL DD identity for the STEP01R input override, {@code TRANREPT.prc:L24-L25}. */
    private static final String DD_BACKUP_INPUT = "PRC001.FILEIN";

    /** JCL DD identity for the STEP01R output override, {@code TRANREPT.prc:L27-L31}. */
    private static final String DD_BACKUP_OUTPUT = "PRC001.FILEOUT";

    /** JCL DD identity for STEP05R input, {@code TRANREPT.prc:L36-L37}. */
    private static final String DD_SORT_INPUT = "SORTIN";

    /** JCL DD identity for STEP05R output, {@code TRANREPT.prc:L49-L53}. */
    private static final String DD_SORT_OUTPUT = "SORTOUT";

    /** JCL DD identity for STEP10R input, {@code TRANREPT.prc:L63-L64}. */
    private static final String DD_REPORT_INPUT = "TRANFILE";

    /** JCL DD identity for STEP10R output, {@code TRANREPT.prc:L74-L78}. */
    private static final String DD_REPORT_OUTPUT = "TRANREPT";

    /** Normal file status. */
    private static final String STATUS_SUCCESS = FileStatus.SUCCESS.code().orElseThrow();

    /** Synthetic physical-I/O status in the legacy 9x family. */
    private static final String STATUS_IO_ERROR = FileStatus.IO_ERROR_FIRST_BYTE + "0";

    /** Four-character display form of batch abend 999. */
    private static final String ABEND_CODE = "0999";

    /** Eight-character legacy culprit. */
    private static final String ABEND_CULPRIT = "CBTRN03C";

    /** Successful flow status and legacy RC 0. */
    private static final String EXIT_CODE_COMPLETED = ExitStatus.COMPLETED.getExitCode();

    /** Legacy RC 4 flow status; recognised but not produced by this job. */
    private static final String EXIT_CODE_COMPLETED_WITH_REJECTS = "COMPLETED WITH REJECTS";

    /** Failed flow status and legacy RC 8. */
    private static final String EXIT_CODE_FAILED = ExitStatus.FAILED.getExitCode();

    /** Abend flow status and legacy RC 12. */
    private static final String EXIT_CODE_ABEND = "ABEND";

    /** Wildcard transition. */
    private static final String EXIT_CODE_ANY = "*";

    /** Numeric legacy return code for normal completion. */
    private static final int RETURN_CODE_COMPLETED = 0;

    /** Numeric legacy return code for completed-with-rejects. */
    private static final int RETURN_CODE_COMPLETED_WITH_REJECTS = 4;

    /** Numeric legacy return code for failure. */
    private static final int RETURN_CODE_FAILED = 8;

    /** Numeric legacy return code for abend. */
    private static final int RETURN_CODE_ABEND = FatalProcessingException.BATCH_RETURN_CODE;

    /**
     * Prefix of this job's correlation identifier, {@value}, followed by the job execution identifier.
     *
     * <p>Derived rather than random, so a run's identifier can be recomputed from its execution record when
     * the logs are read back. Composed only of ASCII letters, digits and {@code -}, which is the grammar
     * {@link CorrelationIdFilter#enterBatchScope(long, String)} enforces.
     */
    private static final String CORRELATION_ID_PREFIX = "tranrept-";

    /** Legacy display literal at {@code app/cbl/CBTRN03C.cbl:L198}. */
    private static final String DISPLAY_TRAN_AMOUNT = "TRAN-AMT ";

    /** Legacy display literal and spacing at {@code app/cbl/CBTRN03C.cbl:L199}. */
    private static final String DISPLAY_PAGE_TOTAL = "WS-PAGE-TOTAL  ";

    /**
     * Immutable topology evidence covering every DD and control statement in the five-member chain.
     * Entries are cited where the corresponding private step method consumes them.
     */
    private static final List<String> LEGACY_TOPOLOGY = List.of(
            "app/cbl/CORPT00C.cbl:L94 EXEC PROC=TRANREPT",
            "app/cbl/CORPT00C.cbl:L98 STEP05R.SYMNAMES",
            "app/proc/TRANREPT.prc:L21 STEP01R",
            "app/proc/TRANREPT.prc:L24 PRC001.FILEIN",
            "app/proc/TRANREPT.prc:L27 PRC001.FILEOUT",
            "app/proc/REPROC.prc:L21 PRC001",
            "app/proc/REPROC.prc:L22 SYSPRINT",
            "app/proc/REPROC.prc:L23 FILEIN",
            "app/proc/REPROC.prc:L25 FILEOUT",
            "app/proc/REPROC.prc:L27 SYSIN",
            "app/ctl/REPROCT.ctl:L15 REPRO",
            "app/proc/TRANREPT.prc:L35 STEP05R",
            "app/proc/TRANREPT.prc:L36 SORTIN",
            "app/proc/TRANREPT.prc:L38 SYMNAMES",
            "app/proc/TRANREPT.prc:L43 SYSIN",
            "app/proc/TRANREPT.prc:L44 SORT FIELDS",
            "app/proc/TRANREPT.prc:L45 INCLUDE COND",
            "app/proc/TRANREPT.prc:L48 SYSOUT",
            "app/proc/TRANREPT.prc:L49 SORTOUT",
            "app/proc/TRANREPT.prc:L57 STEP10R",
            "app/proc/TRANREPT.prc:L58 STEPLIB",
            "app/proc/TRANREPT.prc:L60 SYSOUT",
            "app/proc/TRANREPT.prc:L61 SYSPRINT",
            "app/proc/TRANREPT.prc:L63 TRANFILE",
            "app/proc/TRANREPT.prc:L65 CARDXREF",
            "app/proc/TRANREPT.prc:L67 TRANTYPE",
            "app/proc/TRANREPT.prc:L69 TRANCATG",
            "app/proc/TRANREPT.prc:L71 DATEPARM",
            "app/proc/TRANREPT.prc:L74 TRANREPT",
            "app/proc/TRANREPT.prc:L79 PEND");

    /** Spring Batch metadata repository, injected from Boot auto-configuration. */
    private final JobRepository jobRepository;

    /** Transaction manager used by each tasklet step. */
    private final PlatformTransactionManager transactionManager;

    /** Posted-transaction access used by the backup reader and report processor. */
    private final TransactionRepository transactionRepository;

    /** Card-to-account lookup used by the delegated report processor. */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /** Transaction-type lookup used by the delegated report processor. */
    private final TransactionTypeRepository transactionTypeRepository;

    /** Transaction-category lookup used by the delegated report processor. */
    private final TransactionCategoryRepository transactionCategoryRepository;

    /** Shared source-faithful date validator. */
    private final DateValidationService dateValidationService;

    /** Shared FILE STATUS translator and renderer. */
    private final FileStatusMapper fileStatusMapper;


    /** Injected object-storage abstraction; this class never constructs an AWS client. */
    private final S3Operations objectStorage;

    /** Injected paged object client required by {@link TransactionBackupReader}. */
    private final S3Client objectStoreClient;

    /** Configured runtime job name. */
    private final String jobName;

    /** Configured report-line batch size. */
    private final int chunkSize;

    /** Configured output bucket. */
    private final String outputBucket;

    /** Configured backup-generation prefix. */
    private final String backupPrefix;

    /** Configured daily-generation prefix. */
    private final String dailyPrefix;

    /** Configured report-generation prefix. */
    private final String reportPrefix;

    /**
     * Configured {@code TCATBALF.BKUP} generation prefix, {@code app/jcl/PRTCATBL.jcl:L37}.
     *
     * <p>Before finding M-05 this base was declared in configuration with no producer and no consumer. The
     * {@code app/jcl/PRTCATBL.jcl} step now writes it and reads it back.
     */
    private final String categoryBalanceBackupPrefix;

    /** Repository behind the {@code STEP05R} unload, {@code app/jcl/PRTCATBL.jcl:L32-L33}. */
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /** Documentary retention value, logged but not acted upon. */
    private final int reportRetentionGenerations;

    /**
     * Creates the immutable job configuration.
     *
     * @param jobRepository Boot-provided batch metadata repository
     * @param transactionManager Boot-provided transaction manager
     * @param transactionRepository posted-transaction repository
     * @param cardCrossReferenceRepository card/account cross-reference repository
     * @param transactionTypeRepository transaction-type reference repository
     * @param transactionCategoryRepository transaction-category reference repository
     * @param dateValidationService shared date validator
     * @param fileStatusMapper shared file-status translator
     * @param objectStorage injected S3 operations abstraction
     * @param objectStoreClient injected paged S3 client
     * @param configuredJobName {@code carddemo.batch.jobs.tranrept.name}, default {@code TRANREPT}
     * @param configuredChunkSize {@code carddemo.batch.tranrept.chunk-size}, global fallback, then 100
     * @param configuredOutputBucket {@code carddemo.aws.s3.batch-output-bucket}, blank fail-fast default
     * @param configuredBackupPrefix backup generation prefix, default {@code gdg/transact-bkup}
     * @param configuredDailyPrefix daily generation prefix, default {@code gdg/transact-daly}
     * @param configuredReportPrefix report generation prefix, default {@code gdg/tranrept}
     * @param transactionCategoryBalanceRepository source of the {@code app/jcl/PRTCATBL.jcl} unload
     * @param configuredCategoryBalanceBackupPrefix {@code TCATBALF.BKUP} generation prefix, default
     *     {@code gdg/tcatbalf-bkup}
     * @param configuredRetention documentary retention, default 10
     */
    public TransactionReportJob(
            final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager,
            final TransactionRepository transactionRepository,
            final CardCrossReferenceRepository cardCrossReferenceRepository,
            final TransactionTypeRepository transactionTypeRepository,
            final TransactionCategoryRepository transactionCategoryRepository,
            final DateValidationService dateValidationService,
            final FileStatusMapper fileStatusMapper,
            final S3Operations objectStorage,
            final S3Client objectStoreClient,
            @Value("${carddemo.batch.jobs.tranrept.name:TRANREPT}") final String configuredJobName,
            @Value("${carddemo.batch.tranrept.chunk-size:${carddemo.batch.chunk-size:100}}")
                    final int configuredChunkSize,
            @Value("${carddemo.aws.s3.batch-output-bucket:}") final String configuredOutputBucket,
            @Value("${carddemo.aws.s3.gdg-prefixes.transact-bkup:gdg/transact-bkup}")
                    final String configuredBackupPrefix,
            @Value("${carddemo.aws.s3.gdg-prefixes.transact-daly:gdg/transact-daly}")
                    final String configuredDailyPrefix,
            @Value("${carddemo.aws.s3.gdg-prefixes.tranrept:gdg/tranrept}")
                    final String configuredReportPrefix,
            final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            @Value("${carddemo.aws.s3.gdg-prefixes.tcatbalf-bkup:gdg/tcatbalf-bkup}")
                    final String configuredCategoryBalanceBackupPrefix,
            @Value("${carddemo.aws.s3.gdg-retention-generations:10}")
                    final int configuredRetention) {

        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        this.transactionManager =
                Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.transactionRepository =
                Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
        this.cardCrossReferenceRepository = Objects.requireNonNull(
                cardCrossReferenceRepository, "cardCrossReferenceRepository must not be null");
        this.transactionTypeRepository = Objects.requireNonNull(
                transactionTypeRepository, "transactionTypeRepository must not be null");
        this.transactionCategoryRepository = Objects.requireNonNull(
                transactionCategoryRepository, "transactionCategoryRepository must not be null");
        this.dateValidationService =
                Objects.requireNonNull(dateValidationService, "dateValidationService must not be null");
        this.fileStatusMapper =
                Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage must not be null");
        this.objectStoreClient =
                Objects.requireNonNull(objectStoreClient, "objectStoreClient must not be null");
        this.jobName = requireConfiguredText(configuredJobName, "carddemo.batch.jobs.tranrept.name");
        this.chunkSize = requirePositive(configuredChunkSize, "carddemo.batch.tranrept.chunk-size");
        this.outputBucket =
                requireConfiguredText(configuredOutputBucket, "carddemo.aws.s3.batch-output-bucket");
        this.backupPrefix = requireGenerationPrefix(
                configuredBackupPrefix, "carddemo.aws.s3.gdg-prefixes.transact-bkup");
        this.dailyPrefix = requireGenerationPrefix(
                configuredDailyPrefix, "carddemo.aws.s3.gdg-prefixes.transact-daly");
        this.reportPrefix = requireGenerationPrefix(
                configuredReportPrefix, "carddemo.aws.s3.gdg-prefixes.tranrept");
        this.reportRetentionGenerations = requireRetention(configuredRetention);
        this.transactionCategoryBalanceRepository = Objects.requireNonNull(
                transactionCategoryBalanceRepository,
                "transactionCategoryBalanceRepository must not be null");
        this.categoryBalanceBackupPrefix = requireGenerationPrefix(
                configuredCategoryBalanceBackupPrefix, "carddemo.aws.s3.gdg-prefixes.tcatbalf-bkup");
    }

    /**
     * STEP01R: repository-to-fixed-width backup, replacing the nested procedure and control card.
     *
     * @return the backup step
     */
    @Bean(BACKUP_STEP_BEAN_NAME)
    public Step transactionReportBackupStep() {
        return new StepBuilder(BACKUP_STEP_BEAN_NAME, jobRepository)
                .tasklet(transactionReportBackupTasklet(), transactionManager)
                .build();
    }

    /**
     * STEP05R: inclusive date selection and ascending card-number sort.
     *
     * @return the sort step
     */
    @Bean(SORT_STEP_BEAN_NAME)
    public Step transactionReportSortStep() {
        return new StepBuilder(SORT_STEP_BEAN_NAME, jobRepository)
                .tasklet(transactionReportSortTasklet(), transactionManager)
                .build();
    }

    /**
     * STEP10R: CBTRN03C report generation and 133-byte object emission.
     *
     * @return the report-generation step
     */
    @Bean(GENERATE_STEP_BEAN_NAME)
    public Step transactionReportGenerateStep() {
        return new StepBuilder(GENERATE_STEP_BEAN_NAME, jobRepository)
                .tasklet(transactionReportGenerateTasklet(), transactionManager)
                .build();
    }

    /**
     * The whole of {@code app/jcl/PRTCATBL.jcl}: pre-delete, unload {@code TCATBALF.BKUP(+1)}, print it.
     *
     * <p>Gated by {@value #JOB_PARAMETER_PRINT_CATEGORY_BALANCES}; without it the step is a logged
     * no-operation, because {@code PRTCATBL} is a different member from {@code TRANREPT} and a standalone
     * transaction report must not unload and print another cluster.
     *
     * @return the category-balance print step, registered as {@value #CATEGORY_BALANCE_STEP_BEAN_NAME}
     */
    @Bean(CATEGORY_BALANCE_STEP_BEAN_NAME)
    public Step transactionReportCategoryBalanceStep() {
        return new StepBuilder(CATEGORY_BALANCE_STEP_BEAN_NAME, jobRepository)
                .tasklet(transactionReportCategoryBalanceTasklet(), transactionManager)
                .build();
    }

    /**
     * Four-step flow in the clean procedure order, with every outcome routed through the 0/4/8/12 decider.
     *
     * @param backupStep STEP01R
     * @param sortStep STEP05R
     * @param generateStep STEP10R
     * @param categoryBalanceStep the {@code app/jcl/PRTCATBL.jcl} unload-and-print step
     * @return the complete transaction-report flow
     */
    @Bean(FLOW_BEAN_NAME)
    public Flow transactionReportFlow(
            @Qualifier(BACKUP_STEP_BEAN_NAME) final Step backupStep,
            @Qualifier(SORT_STEP_BEAN_NAME) final Step sortStep,
            @Qualifier(GENERATE_STEP_BEAN_NAME) final Step generateStep,
            @Qualifier(CATEGORY_BALANCE_STEP_BEAN_NAME) final Step categoryBalanceStep) {

        final JobExecutionDecider returnCodeDecider = new TransactionReportReturnCodeDecider();
        return new FlowBuilder<SimpleFlow>(FLOW_BEAN_NAME)
                .start(backupStep).on(EXIT_CODE_COMPLETED).to(sortStep)
                .from(backupStep).on(EXIT_CODE_ANY).to(returnCodeDecider)
                .from(sortStep).on(EXIT_CODE_COMPLETED).to(generateStep)
                .from(sortStep).on(EXIT_CODE_ANY).to(returnCodeDecider)
                // app/jcl/PRTCATBL.jcl is its own member and prints a different cluster, so it follows the
                // report rather than gating it: only a completed report goes on to it, and any other outcome
                // of the report goes straight to the decider.
                .from(generateStep).on(EXIT_CODE_COMPLETED).to(categoryBalanceStep)
                .from(generateStep).on(EXIT_CODE_ANY).to(returnCodeDecider)
                .from(categoryBalanceStep).on(EXIT_CODE_ANY).to(returnCodeDecider)
                .from(returnCodeDecider).on(EXIT_CODE_COMPLETED).end(EXIT_CODE_COMPLETED)
                .from(returnCodeDecider).on(EXIT_CODE_COMPLETED_WITH_REJECTS)
                .end(EXIT_CODE_COMPLETED_WITH_REJECTS)
                .from(returnCodeDecider).on(EXIT_CODE_FAILED).fail()
                .from(returnCodeDecider).on(EXIT_CODE_ABEND).fail()
                .from(returnCodeDecider).on(EXIT_CODE_ANY).fail()
                .build();
    }

    /**
     * Complete transaction-report job with pre-run parameter validation and an inline diagnostic-context
     * listener.
     *
     * @param transactionReportFlow the uniquely named report flow
     * @return the registered job
     */
    @Bean(JOB_BEAN_NAME)
    public Job transactionReportJob(
            @Qualifier(FLOW_BEAN_NAME) final Flow transactionReportFlow) {

        return new JobBuilder(jobName, jobRepository)
                .validator(new TransactionReportParametersValidator())
                .listener(new TransactionReportJobListener())
                .start(transactionReportFlow)
                .end()
                .build();
    }

    /**
     * Creates the STEP01R tasklet without registering an additional bean.
     *
     * @return the backup tasklet
     */
    private Tasklet transactionReportBackupTasklet() {
        return (contribution, chunkContext) -> {
            executeStep01r(chunkContext.getStepContext().getStepExecution());
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Creates the STEP05R tasklet without registering an additional bean.
     *
     * @return the sort tasklet
     */
    private Tasklet transactionReportSortTasklet() {
        return (contribution, chunkContext) -> {
            executeStep05r(chunkContext.getStepContext().getStepExecution());
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Creates the STEP10R tasklet without registering an additional bean.
     *
     * @return the report tasklet
     */
    private Tasklet transactionReportGenerateTasklet() {
        return (contribution, chunkContext) -> {
            executeStep10r(chunkContext.getStepContext().getStepExecution());
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Creates the {@code app/jcl/PRTCATBL.jcl} tasklet without registering an additional bean.
     *
     * @return the category-balance print tasklet
     */
    private Tasklet transactionReportCategoryBalanceTasklet() {
        return (contribution, chunkContext) -> {
            executePrtcatbl(chunkContext.getStepContext().getStepExecution());
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * The whole of {@code app/jcl/PRTCATBL.jcl}, in its three source steps and in source order.
     *
     * <p>{@code DELDEF} at {@code :L21-L25} allocates {@code TCATBALF.REPT} {@code DISP=(MOD,DELETE)} under
     * {@code IEFBR14}, whose only effect is to delete it - the member keeps one report and replaces it.
     * {@code STEP05R} at {@code :L29-L39} unloads {@code TCATBALF.VSAM.KSDS} to a {@code TCATBALF.BKUP(+1)}
     * generation at {@code LRECL=50} through the shared {@code REPROC} procedure. {@code STEP10R} at
     * {@code :L43-L63} sorts that generation by the composite key and emits the edited 40-byte line.
     *
     * <p><b>The print reads the generation the unload just wrote, not the relation.</b> That is what
     * {@code SORTIN DSN=...TCATBALF.BKUP(+1)} at {@code :L44-L45} says, and reproducing it is the point: it
     * makes the unload a real producer with a real consumer, which is what finding M-05 recorded as missing.
     *
     * <p>{@code :L52} states the sort explicitly, and the unload emits that order already because it reads
     * the cluster through its composite-key ordering. Neither step is allowed to hold the cluster in the
     * heap, so the print verifies the declared order record by record instead of re-sorting a resident list;
     * see {@link #printCategoryBalances(String)}.
     *
     * @param stepExecution the running step, whose context receives both concrete keys
     */
    private void executePrtcatbl(final StepExecution stepExecution) {
        final JobExecution jobExecution = stepExecution.getJobExecution();
        if (!printCategoryBalancesInstructed(jobExecution)) {
            LOG.info("{} not instructed for this run; app/jcl/PRTCATBL.jcl is a separate member from"
                            + " app/jcl/TRANREPT.jcl and a standalone report does not include it",
                    DD_CATEGORY_BALANCE_REPORT);
            return;
        }

        final long jobInstanceId = requireJobInstanceId(stepExecution);
        final String backupKey = generationObjectKey(
                categoryBalanceBackupPrefix, jobInstanceId, CATEGORY_BALANCE_BACKUP_OBJECT_NAME);

        deldef();
        final int recordCount = unloadCategoryBalances(backupKey);
        final int lineCount = printCategoryBalances(backupKey);

        publishConcreteKey(stepExecution, CATEGORY_BALANCE_BACKUP_KEY_CONTEXT, backupKey);
        publishCount(stepExecution, CATEGORY_BALANCE_BACKUP_COUNT_CONTEXT, recordCount);
        publishConcreteKey(
                stepExecution, CATEGORY_BALANCE_REPORT_KEY_CONTEXT, CATEGORY_BALANCE_REPORT_OBJECT_NAME);
        publishCount(stepExecution, CATEGORY_BALANCE_REPORT_LINE_COUNT_CONTEXT, lineCount);
        LOG.info("{} completed: {} records unloaded as {}-byte blocks to {}, then {} lines printed as"
                        + " {}-byte blocks to {}",
                CATEGORY_BALANCE_STEP_BEAN_NAME, Integer.valueOf(recordCount),
                Integer.valueOf(CATEGORY_BALANCE_RECORD_LENGTH), backupKey, Integer.valueOf(lineCount),
                Integer.valueOf(CATEGORY_BALANCE_REPORT_LINE_LENGTH), CATEGORY_BALANCE_REPORT_OBJECT_NAME);
    }

    /**
     * Reads the per-run print instruction.
     *
     * @param jobExecution the running execution
     * @return {@code true} when the stream asked for the category-balance print
     */
    private static boolean printCategoryBalancesInstructed(final JobExecution jobExecution) {
        return Boolean.parseBoolean(jobExecution.getJobParameters()
                .getString(JOB_PARAMETER_PRINT_CATEGORY_BALANCES, "false"));
    }

    /**
     * {@code DELDEF}, {@code app/jcl/PRTCATBL.jcl:L21-L25}: remove the previous report if there is one.
     *
     * <p>{@code IEFBR14} with {@code DISP=(MOD,DELETE)} creates the dataset if absent and deletes it either
     * way, so an absent object is the expected case on a first run and is not an error.
     */
    private void deldef() {
        try {
            if (objectStorage.objectExists(outputBucket, CATEGORY_BALANCE_REPORT_OBJECT_NAME)) {
                objectStorage.deleteObject(outputBucket, CATEGORY_BALANCE_REPORT_OBJECT_NAME);
                LOG.debug("DELDEF removed the previous {}", CATEGORY_BALANCE_REPORT_OBJECT_NAME);
            }
        } catch (final RuntimeException cause) {
            throw ioAbend(DD_CATEGORY_BALANCE_REPORT, "DELETE",
                    "ERROR DELETING PREVIOUS CATEGORY BALANCE REPORT", cause);
        }
    }

    /**
     * {@code STEP05R}, {@code app/jcl/PRTCATBL.jcl:L29-L39}: unload the cluster at {@code LRECL=50}.
     *
     * <p>Read in bounded slices so the whole cluster is never resident, in the composite-key order the
     * cluster itself is keyed in.
     *
     * @param backupKey the concrete {@code TCATBALF.BKUP(+1)} key this run creates
     * @return how many 50-byte records were written
     */
    private int unloadCategoryBalances(final String backupKey) {
        int recordCount = 0;
        try {
            final S3Resource resource = outputResource(backupKey);
            try (OutputStream output = new BufferedOutputStream(resource.getOutputStream())) {
                int page = 0;
                boolean more = true;
                while (more) {
                    final Slice<TransactionCategoryBalance> slice =
                            transactionCategoryBalanceRepository
                                    .findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc(
                                            PageRequest.of(page, chunkSize));
                    for (final TransactionCategoryBalance balance : slice.getContent()) {
                        final byte[] encoded =
                                categoryBalanceRecord(balance).getBytes(FIXED_WIDTH_CHARSET);
                        requireEncodedLength(
                                encoded, CATEGORY_BALANCE_RECORD_LENGTH, DD_CATEGORY_BALANCE_BACKUP);
                        output.write(encoded);
                        recordCount++;
                    }
                    more = slice.hasNext();
                    page++;
                }
                output.flush();
            }
            fileStatusMapper.requireSuccess(STATUS_SUCCESS, DD_CATEGORY_BALANCE_BACKUP, "WRITE");
            return recordCount;
        } catch (final CardDemoException typed) {
            discardOwnGenerationOnFailure(backupKey, CATEGORY_BALANCE_STEP_BEAN_NAME);
            throw typed;
        } catch (final IOException | RuntimeException cause) {
            discardOwnGenerationOnFailure(backupKey, CATEGORY_BALANCE_STEP_BEAN_NAME);
            throw ioAbend(DD_CATEGORY_BALANCE_BACKUP, "WRITE",
                    "ERROR WRITING CATEGORY BALANCE BACKUP", cause);
        }
    }

    /**
     * {@code STEP10R}, {@code app/jcl/PRTCATBL.jcl:L43-L63}: read the generation back and print the edited
     * line, one record at a time.
     *
     * <p><strong>Finding M-08, severity High, here as well as in the sort step.</strong> Reading the whole
     * {@code TCATBALF.BKUP(+1)} generation into a {@code List<byte[]>}, sorting
     * that list and building the entire report in one {@code StringBuilder} makes peak memory the whole unload
     * twice over, and the category-balance cluster has one row per account, type and category triple and is
     * unbounded. This streams instead: one 50-byte record is read, rendered as its 40-byte line, written and
     * discarded, so peak memory is one record regardless of cluster size.
     *
     * <p><b>The sort becomes an assertion, which is stronger than sorting.</b> {@code :L52} declares
     * {@code SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)} over the {@code SYMNAMES}
     * offsets at {@code :L47-L49}, and {@code STEP05R} produced this generation in exactly that order
     * because {@link #unloadCategoryBalances(String)} reads the cluster through
     * {@code findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc}. Re-sorting a stream is impossible without
     * making it resident again, so the order the producer guarantees is verified instead: each key is
     * compared against its predecessor and a descending pair abends the step. A silent re-sort would have
     * masked a producer regression; refusing an out-of-order source surfaces it. The comparison is on
     * characters, which for these zero-padded zoned-decimal keys is the same order as numeric and is what
     * DFSORT's {@code CH} format does.
     *
     * @param backupKey the generation to read back, which {@code :L44-L45} names as {@code SORTIN}
     * @return how many 40-byte lines were written
     */
    private int printCategoryBalances(final String backupKey) {
        int lineCount = 0;
        try {
            final S3Resource source = objectStorage.download(outputBucket, backupKey);
            final S3Resource resource = outputResource(CATEGORY_BALANCE_REPORT_OBJECT_NAME);
            try (InputStream input = new BufferedInputStream(source.getInputStream());
                    OutputStream output = new BufferedOutputStream(resource.getOutputStream())) {
                String previousKey = null;
                while (true) {
                    final byte[] record = input.readNBytes(CATEGORY_BALANCE_RECORD_LENGTH);
                    if (record.length == 0) {
                        break;
                    }
                    requireRecordLength(
                            record, CATEGORY_BALANCE_RECORD_LENGTH, DD_CATEGORY_BALANCE_BACKUP);
                    final String key = categoryBalanceSortKey(record);
                    if (previousKey != null && key.compareTo(previousKey) < 0) {
                        throw abend("SORT ORDER VIOLATION",
                                "TCATBALF.BKUP RECORD " + (lineCount + 1)
                                        + " BREAKS THE COMPOSITE KEY ORDER SORT FIELDS DECLARES", null);
                    }
                    previousKey = key;
                    final byte[] encoded = categoryBalanceReportLine(
                            new String(record, FIXED_WIDTH_CHARSET)).getBytes(FIXED_WIDTH_CHARSET);
                    requireEncodedLength(
                            encoded, CATEGORY_BALANCE_REPORT_LINE_LENGTH, DD_CATEGORY_BALANCE_REPORT);
                    output.write(encoded);
                    lineCount++;
                }
                output.flush();
            }
            fileStatusMapper.requireSuccess(STATUS_SUCCESS, DD_CATEGORY_BALANCE_BACKUP, "READ");
            fileStatusMapper.requireSuccess(STATUS_SUCCESS, DD_CATEGORY_BALANCE_REPORT, "WRITE");
            return lineCount;
        } catch (final CardDemoException typed) {
            throw typed;
        } catch (final IOException | RuntimeException cause) {
            throw ioAbend(DD_CATEGORY_BALANCE_REPORT, "WRITE",
                    "ERROR WRITING CATEGORY BALANCE REPORT", cause);
        }
    }

    /**
     * The three key fields of one unloaded record, concatenated, per the {@code SYMNAMES} at
     * {@code app/jcl/PRTCATBL.jcl:L47-L49}.
     *
     * <p>Used by {@link #printCategoryBalances(String)} to verify that the generation arrives in the order
     * {@code :L52} declares, rather than to sort a resident list.
     *
     * @param record one 50-byte unloaded record
     * @return the sort key, never {@code null}
     */
    private static String categoryBalanceSortKey(final byte[] record) {
        return new String(record, 0, 17, FIXED_WIDTH_CHARSET);
    }

    /**
     * Renders one 50-byte {@code TCATBALF} record, {@code app/cpy/CVTRA01Y.cpy}.
     *
     * <p>Offsets are the ones {@code app/jcl/PRTCATBL.jcl:L47-L50} states independently of the copybook:
     * the account identifier at 1 for 11, the type code at 12 for 2, the category code at 14 for 4 and the
     * balance at 18 for 11. That accounts for 28 of the 50 bytes; the remaining 22 are the copybook's
     * trailing filler.
     *
     * @param balance the entity to render
     * @return the 50-character image, never {@code null}
     */
    private String categoryBalanceRecord(final TransactionCategoryBalance balance) {
        final TransactionCategoryBalanceId id = balance.getId();
        final StringBuilder record = new StringBuilder(CATEGORY_BALANCE_RECORD_LENGTH);
        record.append(zonedDigits(id.getAccountId(), 11, "TRANCAT-ACCT-ID"));
        record.append(alphanumeric(id.getTypeCd(), 2, "TRANCAT-TYPE-CD"));
        record.append(zonedDigits(id.getCatCd(), 4, "TRANCAT-CD"));
        record.append(signedZonedAmount(balance.getBalance()));
        record.append(" ".repeat(CATEGORY_BALANCE_RECORD_LENGTH - record.length()));
        return record.toString();
    }

    /**
     * Renders an unsigned zoned-decimal key field, zero padded to its declared width.
     *
     * @param value the numeric value, which must be present and non-negative
     * @param width the declared width
     * @param fieldName the field name used in typed failures
     * @return the fixed-width image, never {@code null}
     */
    private String zonedDigits(final Number value, final int width, final String fieldName) {
        if (value == null) {
            throw abend("KEY FIELD MISSING", fieldName + " IS REQUIRED", null);
        }
        final long numeric = value.longValue();
        if (numeric < 0L) {
            throw abend("KEY FIELD INVALID", fieldName + " MUST NOT BE NEGATIVE", null);
        }
        final String digits = Long.toString(numeric);
        if (digits.length() > width) {
            throw abend("KEY FIELD OVERFLOW", fieldName + " EXCEEDS ITS DECLARED WIDTH", null);
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Renders one printed line, {@code app/jcl/PRTCATBL.jcl:L53-L56}.
     *
     * <p>{@code OUTREC FIELDS=(TRANCAT-ACCT-ID,X, TRANCAT-TYPE-CD,X, TRANCAT-CD,X,
     * TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),9X)}: each {@code X} is one blank, and {@code EDIT} with {@code T}
     * digit selectors prints every digit position without zero suppression.
     *
     * <p><b>Two source behaviours are preserved deliberately.</b> First, {@code EDIT} carries no
     * {@code SIGN} operand, so the printed magnitude is unsigned - a negative balance prints without its
     * sign, and that is the member's behaviour rather than a defect to repair here. Second, the declared
     * {@code LRECL=40} wins over the field list's 41 bytes; see
     * {@link #CATEGORY_BALANCE_REPORT_LINE_LENGTH}.
     *
     * @param record one 50-character unloaded record
     * @return the 40-character printed line, never {@code null}
     */
    private String categoryBalanceReportLine(final String record) {
        final String accountId = record.substring(0, 11);
        final String typeCode = record.substring(11, 13);
        final String categoryCode = record.substring(13, 17);
        final String balanceImage = record.substring(17, 28);

        final BigDecimal balance = decodeSignedZoned(balanceImage);
        final String magnitude = balance.abs().setScale(MONEY_SCALE, RoundingMode.HALF_EVEN)
                .movePointRight(MONEY_SCALE).toBigIntegerExact().toString();
        final String padded = "0".repeat(Math.max(0, AMOUNT_DIGITS - magnitude.length())) + magnitude;
        final String edited = padded.substring(0, padded.length() - MONEY_SCALE)
                + "." + padded.substring(padded.length() - MONEY_SCALE);

        final StringBuilder line = new StringBuilder(CATEGORY_BALANCE_REPORT_LINE_LENGTH);
        line.append(accountId).append(' ')
                .append(typeCode).append(' ')
                .append(categoryCode).append(' ')
                .append(edited)
                .append(" ".repeat(CATEGORY_BALANCE_REPORT_FILLER_WIDTH));
        if (line.length() != CATEGORY_BALANCE_REPORT_LINE_LENGTH) {
            throw abend("REPORT LINE LENGTH VIOLATION",
                    "TCATBALF.REPT LINE IS NOT 40 CHARACTERS", null);
        }
        return line.toString();
    }

    /**
     * Decodes an eleven-character zoned-decimal image with a trailing overpunch sign.
     *
     * @param image the eleven-character field
     * @return the signed value at scale 2, never {@code null}
     */
    private BigDecimal decodeSignedZoned(final String image) {
        final String leading = image.substring(0, image.length() - 1);
        final char overpunch = image.charAt(image.length() - 1);
        final int positive = "{ABCDEFGHI".indexOf(overpunch);
        final int negative = "}JKLMNOPQR".indexOf(overpunch);
        if (positive < 0 && negative < 0) {
            throw abend("BALANCE INVALID",
                    "TRAN-CAT-BAL CARRIES NO VALID ZONED-DECIMAL SIGN OVERPUNCH", null);
        }
        for (int index = 0; index < leading.length(); index++) {
            if (leading.charAt(index) < '0' || leading.charAt(index) > '9') {
                throw abend("BALANCE INVALID", "TRAN-CAT-BAL IS NOT ZONED DECIMAL", null);
            }
        }
        final int finalDigit = positive >= 0 ? positive : negative;
        final BigDecimal unscaled = new BigDecimal(leading + finalDigit);
        final BigDecimal signed = negative >= 0 ? unscaled.negate() : unscaled;
        return signed.movePointLeft(MONEY_SCALE);
    }

    /**
     * STEP01R, {@code app/proc/TRANREPT.prc:L21-L31}: FILEIN is the posted transaction cluster and FILEOUT
     * is the 350-byte backup generation. {@code app/proc/REPROC.prc:L21-L28} and
     * {@code app/ctl/REPROCT.ctl:L15} reduce to a record-for-record in-process copy.
     *
     * @param stepExecution current step execution
     */
    private void executeStep01r(final StepExecution stepExecution) {
        reproControlCard();
        final long jobInstanceId = requireJobInstanceId(stepExecution);
        final String backupKey = generationObjectKey(backupPrefix, jobInstanceId, BACKUP_OBJECT_NAME);
        final ExecutionContext stepContext = stepExecution.getExecutionContext();

        // F-008: this class owns the reader outright - TransactionBackupReader carries no @Component and no
        // @StepScope, so this is the only way an instance comes into being on the FILEIN leg. The substrate
        // is a literal rather than a property because app/proc/TRANREPT.prc:L23 declares FILEIN over the
        // transaction cluster, and the promoted key is null because this step is the one that creates the
        // TRANSACT.BKUP generation the later steps consume.
        final TransactionBackupReader reader = new TransactionBackupReader(
                transactionRepository,
                objectStorage,
                objectStoreClient,
                fileStatusMapper,
                READER_SOURCE_REPOSITORY,
                chunkSize,
                outputBucket,
                backupPrefix,
                null);

        boolean readerOpened = false;
        CardDemoException failure = null;
        int recordCount = 0;
        try {
            reader.open(stepContext);
            readerOpened = true;
            recordCount = writeRepositoryBackup(reader, stepContext, backupKey);
            reader.update(stepContext);
        } catch (final CardDemoException typed) {
            failure = typed;
        } catch (final RuntimeException unexpected) {
            failure = abend("STEP01R FAILED", "ERROR COPYING TRANSACTION BACKUP", unexpected);
        } finally {
            failure = closeReader(reader, readerOpened, failure, DD_BACKUP_INPUT);
        }
        if (failure != null) {
            discardOwnGenerationOnFailure(backupKey, BACKUP_STEP_BEAN_NAME);
            throw failure;
        }

        publishConcreteKey(stepExecution, BACKUP_OBJECT_KEY_CONTEXT, backupKey);
        publishCount(stepExecution, BACKUP_RECORD_COUNT_CONTEXT, recordCount);
        LOG.info("{} completed: {} records written as {}-byte fixed blocks",
                BACKUP_STEP_BEAN_NAME, Integer.valueOf(recordCount),
                Integer.valueOf(TRANSACTION_RECORD_LENGTH));
    }

    /**
     * The single control card, {@code app/ctl/REPROCT.ctl:L15}. The target uses the authored repository
     * reader and an S3 fixed-width stream; no utility process is invoked.
     */
    private void reproControlCard() {
        LOG.debug("Resolved app/ctl/REPROCT.ctl:L15 to the in-process {} -> {} copy",
                DD_BACKUP_INPUT, DD_BACKUP_OUTPUT);
    }

    /**
     * Streams the repository reader into the concrete backup object.
     *
     * @param reader repository-backed transaction reader
     * @param stepContext restart context
     * @param backupKey concrete key created by this step
     * @return number of 350-byte records written
     */
    private int writeRepositoryBackup(
            final TransactionBackupReader reader,
            final ExecutionContext stepContext,
            final String backupKey) {

        int recordCount = 0;
        try {
            final S3Resource resource = outputResource(backupKey);
            try (OutputStream output = new BufferedOutputStream(resource.getOutputStream())) {
                while (true) {
                    final var transaction = reader.read();
                    if (transaction == null) {
                        break;
                    }
                    final String record = transactionRecord(
                            transaction.getTransactionId(),
                            transaction.getTypeCode(),
                            transaction.getCategoryCode(),
                            transaction.getTransactionSource(),
                            transaction.getDescription(),
                            transaction.getAmount(),
                            transaction.getMerchantId(),
                            transaction.getMerchantName(),
                            transaction.getMerchantCity(),
                            transaction.getMerchantZip(),
                            transaction.getCardNumber(),
                            transaction.getOrigTs(),
                            transaction.getProcTs());
                    final byte[] encoded = record.getBytes(FIXED_WIDTH_CHARSET);
                    requireEncodedLength(encoded, TRANSACTION_RECORD_LENGTH, DD_BACKUP_OUTPUT);
                    output.write(encoded);
                    recordCount++;
                    if (recordCount % chunkSize == 0) {
                        reader.update(stepContext);
                    }
                }
                output.flush();
            }
            fileStatusMapper.requireSuccess(
                    STATUS_SUCCESS, DD_BACKUP_OUTPUT, "WRITE");
            return recordCount;
        } catch (final CardDemoException typed) {
            throw typed;
        } catch (final IOException | RuntimeException cause) {
            throw ioAbend(
                    DD_BACKUP_OUTPUT, "WRITE", "ERROR WRITING TRANSACTION BACKUP", cause);
        }
    }

    /**
     * STEP05R, {@code app/proc/TRANREPT.prc:L35-L53}. SORTIN consumes the concrete backup key; SYMNAMES at
     * {@code :L38-L42} maps card bytes 263-278 and processing-date bytes 305-314; SYSIN at
     * {@code :L43-L46} orders the first field ascending and includes both date bounds; SORTOUT publishes the
     * concrete daily key.
     *
     * <p><b>Bounded, and it was not.</b> Under finding <b>F-012</b> this step materialised the entire SORTIN
     * generation into a {@code List<byte[]>}, filtered it with {@code removeIf} and sorted it in heap, so peak
     * live memory grew linearly with the transaction population and was reached before any output byte was
     * written. It now verifies SORTIN without retaining it - see
     * {@link #requireIntactSortInput(StepExecution, String)} - and streams the ordered, filtered population one
     * page at a time into SORTOUT, in {@link #writeSortedGeneration(String, String, String)}. No external sort
     * process is spawned, which AAP transformation rule 9 forbids, and the emitted byte sequence is unchanged.
     *
     * @param stepExecution current step execution
     */
    private void executeStep05r(final StepExecution stepExecution) {
        final String startDate = startDateSymbol(stepExecution.getJobExecution().getJobParameters());
        final String endDate = endDateSymbol(stepExecution.getJobExecution().getJobParameters());
        // SORTIN at app/proc/TRANREPT.prc:L36-L37 names TRANSACT.BKUP(0), so the step still requires that
        // generation to exist - STEP01R must have run and published it, and the combine's first leg reads it.
        final String backupKey =
                requireConcreteKey(stepExecution, BACKUP_OBJECT_KEY_CONTEXT, backupPrefix);

        // SORTIN is still read, and it is read for what a sort step actually needs from it: that the
        // generation STEP01R catalogued exists, has intact 350-byte geometry, and holds the same population
        // the ordered scan below will read. Nothing is retained - see requireIntactSortInput.
        requireIntactSortInput(stepExecution, backupKey);

        final long jobInstanceId = requireJobInstanceId(stepExecution);
        final String dailyKey = generationObjectKey(dailyPrefix, jobInstanceId, DAILY_OBJECT_NAME);
        final long recordCount;
        try {
            recordCount = writeSortedGeneration(dailyKey, startDate, endDate);
        } catch (final RuntimeException failure) {
            // CardDemoException is itself a RuntimeException, so this one alternative covers both the typed
            // failures this write raises and any unexpected one. The failure is rethrown unchanged.
            discardOwnGenerationOnFailure(dailyKey, SORT_STEP_BEAN_NAME);
            throw failure;
        }
        publishConcreteKey(stepExecution, DAILY_OBJECT_KEY_CONTEXT, dailyKey);
        publishCount(stepExecution, DAILY_RECORD_COUNT_CONTEXT, recordCount);
        LOG.info("{} completed: {} records passed the inclusive character predicate and card ordering",
                SORT_STEP_BEAN_NAME, Long.valueOf(recordCount));
    }

    /**
     * Verifies {@code SORTIN} without retaining it, and proves it holds the population the ordered scan reads.
     *
     * <p><b>Finding F-012, severity High, addressed by this method and by
     * {@link #writeSortedGeneration(String, String, String)}.</b> Reading the whole generation
     * into a {@code List<byte[]>}, dropping the non-matching entries with {@code removeIf} and sorting the
     * remainder in heap costs, at 350 bytes per element plus per-object overhead, roughly 370 bytes of live
     * heap per transaction with no bound of any kind - and the peak arrives before the first byte of output is
     * written. This retains one page of rows instead.
     *
     * <p><b>Why reading the generation for its bytes was replaceable at all.</b> STEP01R writes the generation
     * by copying the transaction relation record for record: {@code app/ctl/REPROCT.ctl:L15} is
     * {@code REPRO INFILE(FILEIN) OUTFILE(FILEOUT)} with <b>no selection and no reformatting</b>, so the
     * generation's population and the relation's population are the same set of records. That equivalence is
     * not assumed here - it is <b>asserted at run time</b>, twice, and a disagreement fails the step rather
     * than producing a report that looks complete:
     * <ul>
     *   <li>against the count STEP01R published under {@value #BACKUP_RECORD_COUNT_CONTEXT}, which catches a
     *       generation truncated or overwritten between the two steps; and</li>
     *   <li>against {@code count()} on the relation, which catches the case the frozen-snapshot semantics of
     *       {@code DISP=SHR} would otherwise hide - a write that landed on the relation after STEP01R ran.</li>
     * </ul>
     *
     * <p><b>Separator bytes are refused, and the length must be an exact multiple.</b> This is the last of the
     * three fixed-block object readers to be hardened - the two in {@code batch/readers} were done under
     * finding F-013 - and it was the one that failed least usefully. {@code readNBytes} over an object carrying
     * a one-byte terminator per record returns 350 bytes every time, so the old loop's length check passed
     * while every record after the first was <b>misaligned by one byte and read as valid</b>; the run failed,
     * if at all, only on the short final remainder, reporting a length error about the last record when the
     * defect was in the second. {@code app/proc/TRANREPT.prc:L29} declares
     * {@code DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)} - fixed blocked, undelimited - so a separator byte is corrupt
     * geometry and is named as such, at the row and byte where it appears.
     *
     * @param stepExecution current step execution, carrying STEP01R's published count
     * @param backupKey the concrete generation key STEP01R promoted
     * @throws DataIntegrityException if the object carries a separator byte, if its length is not an exact
     *     multiple of {@value #TRANSACTION_RECORD_LENGTH}, or if its record count disagrees with either the
     *     count STEP01R published or the count the relation holds
     */
    private void requireIntactSortInput(final StepExecution stepExecution, final String backupKey) {
        final long objectRecords =
                countFixedWidthGeneration(backupKey, DD_SORT_INPUT, TRANSACTION_RECORD_LENGTH);
        final long publishedRecords = publishedCount(stepExecution, BACKUP_RECORD_COUNT_CONTEXT);
        if (publishedRecords != COUNT_NOT_PUBLISHED && objectRecords != publishedRecords) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s holds %d records of %d bytes but %s published %d when it created it; the generation "
                            + "was truncated or overwritten between the two steps, so %s cannot be sorted "
                            + "into %s",
                    backupKey, Long.valueOf(objectRecords), Integer.valueOf(TRANSACTION_RECORD_LENGTH),
                    BACKUP_STEP_BEAN_NAME, Long.valueOf(publishedRecords), DD_SORT_INPUT, DD_SORT_OUTPUT));
        }
        final long relationRecords = transactionRepository.count();
        if (objectRecords != relationRecords) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s holds %d records but the transaction relation holds %d. app/ctl/REPROCT.ctl:L15 "
                            + "copies the relation into the generation without selection, so the two must "
                            + "agree; a disagreement means the relation changed after %s ran, and ordering "
                            + "the relation would report on a population %s does not contain",
                    backupKey, Long.valueOf(objectRecords), Long.valueOf(relationRecords),
                    BACKUP_STEP_BEAN_NAME, DD_SORT_INPUT));
        }
    }

    /**
     * SYMNAMES {@code PARM-START-DATE,C'2022-01-01'}, {@code app/proc/TRANREPT.prc:L41}.
     *
     * @param parameters validated job parameters
     * @return the exact ten-character start date
     */
    private String startDateSymbol(final JobParameters parameters) {
        return requireDateParameter(parameters, TransactionReportProcessor.START_DATE_JOB_PARAMETER);
    }

    /**
     * SYMNAMES {@code PARM-END-DATE,C'2022-07-06'}, {@code app/proc/TRANREPT.prc:L42}.
     *
     * @param parameters validated job parameters
     * @return the exact ten-character end date
     */
    private String endDateSymbol(final JobParameters parameters) {
        return requireDateParameter(parameters, TransactionReportProcessor.END_DATE_JOB_PARAMETER);
    }

    /**
     * INCLUDE COND at {@code app/proc/TRANREPT.prc:L45-L46}. Both comparisons are inclusive and operate on
     * the ten-character image beginning at byte 305.
     *
     * @param record 350-byte transaction record
     * @param startDate inclusive lower character bound
     * @param endDate inclusive upper character bound
     * @return true when the record passes
     */
    private boolean includeCondition(
            final byte[] record,
            final String startDate,
            final String endDate) {

        requireRecordLength(record, TRANSACTION_RECORD_LENGTH, DD_SORT_INPUT);
        final String processingDate = new String(
                record, PROCESSING_DATE_OFFSET, PROCESSING_DATE_LENGTH, FIXED_WIDTH_CHARSET);
        return processingDate.compareTo(startDate) >= 0
                && processingDate.compareTo(endDate) <= 0;
    }

    // SORT FIELDS=(TRAN-CARD-NUM,A), app/proc/TRANREPT.prc:L44, IS NOT IMPLEMENTED AS A COMPARATOR HERE.
    //
    // No private sortFieldsCardNumberAscending(byte[], byte[]) comparator exists here, and none may be
    // added. Rule 1 Clause B forbids dead code, and such a comparator would have zero callers and zero test
    // references once the ordering is declared on the query. The parity exception of AAP section 0.8.2 does
    // not reach it either: DL-CR-01's register is keyed by identifier and locator and admits an artefact only
    // when the frozen source genuinely reaches a no-op or an unobservable value, which a Java comparator that
    // nothing calls does not. Deleting it is therefore required rather than merely permitted.
    //
    // The ordering itself is NOT lost, and this is the important part: it is declared as
    // "order by t.cardNumber asc, t.transactionId asc" on
    // TransactionRepository.findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc, which
    // writeSortedGeneration pages through. The character-image comparison the migration contract requires is
    // preserved because the column is CHAR and the database is initialised with --locale=C, so SQL ORDER BY
    // is byte ordering - the same relation String.compareTo implemented. The card number is a zoned-decimal
    // field compared on its character representation either way.
    //
    // The traceability anchor for TRANREPT.prc:L44 therefore moves from this method to
    // writeSortedGeneration, whose documentation carries the citation and the proof that the emitted
    // permutation is unchanged.

    /**
     * STEP10R, {@code app/proc/TRANREPT.prc:L57-L79}. TRANFILE reads the concrete daily key; CARDXREF,
     * TRANTYPE and TRANCATG are delegated repositories; DATEPARM is the validated pair; TRANREPT is the
     * nested fixed-width writer.
     *
     * @param stepExecution current step execution
     */
    private void executeStep10r(final StepExecution stepExecution) {
        final JobParameters parameters = stepExecution.getJobExecution().getJobParameters();
        final String startDate = startDateSymbol(parameters);
        final String endDate = endDateSymbol(parameters);
        final String dailyKey =
                requireConcreteKey(stepExecution, DAILY_OBJECT_KEY_CONTEXT, dailyPrefix);
        final long jobInstanceId = requireJobInstanceId(stepExecution);
        final String reportKey = generationObjectKey(reportPrefix, jobInstanceId, REPORT_OBJECT_NAME);

        // F-008: both collaborators are owned by this class and by nothing else. The daily key was resolved
        // above by requireConcreteKey, so an absent or malformed STEP05R handoff has already abended this
        // step with a typed cause; constructing here keeps that failure inside the step rather than inside a
        // container callback. The substrate is object-storage because app/proc/TRANREPT.prc:L59 declares
        // TRANFILE over the sorted TRANSACT.DALY generation, not over the cluster.
        final TransactionBackupReader reader = new TransactionBackupReader(
                transactionRepository,
                objectStorage,
                objectStoreClient,
                fileStatusMapper,
                READER_SOURCE_OBJECT_STORAGE,
                chunkSize,
                outputBucket,
                dailyPrefix,
                dailyKey);
        final TransactionReportProcessor processor = new TransactionReportProcessor(
                transactionRepository,
                cardCrossReferenceRepository,
                transactionTypeRepository,
                transactionCategoryRepository,
                fileStatusMapper,
                startDate,
                endDate);
        final FixedWidthReportItemWriter writer = new FixedWidthReportItemWriter(
                objectStorage, fileStatusMapper, outputBucket, reportKey);

        CardDemoException failure = mainlineProcedureDivision(
                stepExecution, reader, processor, writer);
        if (failure != null) {
            discardOwnGenerationOnFailure(reportKey, GENERATE_STEP_BEAN_NAME);
            throw failure;
        }

        publishConcreteKey(stepExecution, REPORT_OBJECT_KEY_CONTEXT, reportKey);
        publishCount(stepExecution, REPORT_LINE_COUNT_CONTEXT, writer.linesWritten());
        LOG.info("{} completed: {} fixed-width report lines emitted",
                GENERATE_STEP_BEAN_NAME, Long.valueOf(writer.linesWritten()));
    }

    /**
     * Reproduces the abnormal-termination disposition {@code DELETE} for the generation this step created.
     *
     * <p>{@code app/proc/TRANREPT.prc} STEP10R declares {@code //TRANREPT DD DISP=(NEW,CATLG,DELETE)}. The
     * third positional sub-parameter is the <b>abnormal-termination</b> disposition, so when the step abends
     * the new generation is deleted and never catalogued. Without that, a failed generate step left
     * {@code gdg/tranrept/generation=.../TRANREPT} behind at <b>0 bytes</b>, and a consumer resolving
     * {@code TRANREPT(0)} could not tell "no transactions matched the range" from "the job abended" - two
     * situations demanding opposite responses.
     *
     * <p><b>Scoped to the object this step created, and to nothing else.</b> The backup and daily objects of a
     * run whose own steps completed are left alone, because their steps were normal terminations and their
     * {@code CATLG} disposition applies. Likewise a generation written by a step that succeeded is not removed
     * because a <em>later</em> step failed - {@code app/jcl/COMBTRAN.jcl} demonstrates the same rule, where a
     * load-step failure leaves the sort step's {@code SORTOUT} legitimately catalogued.
     *
     * <p>A deletion failure is reported and deliberately does not replace the original failure. The step is
     * already failing and the cause the operator needs is the one that made it fail; losing that to an
     * error about tidying up would be strictly worse. The leftover object is named at error level so it can be
     * removed by hand.
     *
     * @param objectKey the key this step created and is abandoning
     * @param stepName the step bean name, for the diagnostic
     */
    private void discardOwnGenerationOnFailure(final String objectKey, final String stepName) {
        try {
            if (objectStorage.objectExists(outputBucket, objectKey)) {
                objectStorage.deleteObject(outputBucket, objectKey);
                LOG.info("{} failed, so the generation it created was deleted rather than catalogued,"
                                + " reproducing the abnormal-termination disposition DELETE of"
                                + " app/proc/TRANREPT.prc DISP=(NEW,CATLG,DELETE): {}",
                        stepName, objectKey);
            }
        } catch (final RuntimeException deletion) {
            LOG.error("{} failed and the generation it created could not be deleted; {} remains catalogued"
                            + " and must be removed by hand, because a consumer resolving this generation"
                            + " cannot distinguish it from a complete one",
                    stepName, objectKey, deletion);
        }
    }

    /**
     * Unlabelled PROCEDURE DIVISION mainline, {@code app/cbl/CBTRN03C.cbl:L159-L217}. This is the twenty-
     * seventh paragraph-equivalent method: it opens the delegated datasets, drives the read loop, preserves
     * the whole-loop transfer, runs the natural-EOF branch once, and closes in source order.
     *
     * @param stepExecution current step
     * @param reader exact daily-generation reader
     * @param processor delegated report body
     * @param writer nested report emitter
     * @return a typed failure after all closes, or null
     */
    private CardDemoException mainlineProcedureDivision(
            final StepExecution stepExecution,
            final TransactionBackupReader reader,
            final TransactionReportProcessor processor,
            final FixedWidthReportItemWriter writer) {

        traceParagraphCorrespondence();
        boolean processorOpened = false;
        boolean readerOpened = false;
        boolean writerOpened = false;
        CardDemoException failure = null;
        try {
            processor.openDatasets();
            processorOpened = true;
            reader.open(stepExecution.getExecutionContext());
            readerOpened = true;
            writer.open(stepExecution.getExecutionContext());
            writerOpened = true;

            final List<ReportLines> buffered = new ArrayList<>(chunkSize);
            boolean terminatedByNextSentence = false;
            long detailRecords = 0L;
            while (true) {
                final var transaction = reader.read();
                if (transaction == null) {
                    break;
                }
                final ReportLines reportLines = processor.process(transaction);
                if (reportLines == null) {
                    terminatedByNextSentence = true;
                    LOG.warn("app/cbl/CBTRN03C.cbl:L177 transfers past :L206; report loop terminated");
                    break;
                }
                buffered.add(reportLines);
                // F-010: this loop counts REPORT rows, and it deliberately increments no shared meter.
                // carddemo.batch.records.processed is defined as the DALYTRAN population CBTRN02C displays at
                // app/cbl/CBTRN02C.cbl:L236, so incrementing it here added this job's already-posted rows to
                // that population and left the untagged counter irrecoverably inflated. The count is published
                // as a job-local execution-context entry instead - see publishCount below.
                // FINDING H-01, severity HIGH. metricsConfig.countRecordProcessed() stood here and has been
                // removed. carddemo.batch.records.processed mirrors ADD 1 TO WS-TRANSACTION-COUNT at
                // app/cbl/CBTRN02C.cbl:L206, so its population is the daily-transaction records the POSTTRAN
                // job read - and nothing else. A report LINE is not one of those records: it is one line of
                // output derived from a transaction an earlier run already counted, and CBTRN03C assigns no
                // such tally at all. Advancing an UNTAGGED counter from here made the series the sum of two
                // unrelated populations, with no job dimension to group either one back out.
                //
                // This step's volume is not lost: it is published to the execution context as
                // REPORT_LINE_COUNT_CONTEXT and is available continuously as the Spring Batch step metrics
                // spring_batch_step_seconds_count and spring_batch_item_write_seconds_count, both tagged by
                // name.
                detailRecords++;
                if (buffered.size() == chunkSize) {
                    writeReportRecord1111(writer, buffered);
                }
            }
            if (!terminatedByNextSentence) {
                buffered.add(processor.finishReport());
            }
            writeReportRecord1111(writer, buffered);
            publishCount(stepExecution, REPORT_RECORD_COUNT_CONTEXT, detailRecords);
        } catch (final CardDemoException typed) {
            failure = typed;
        } catch (final RuntimeException unexpected) {
            failure = abend("STEP10R FAILED", "ERROR PRODUCING TRANSACTION REPORT", unexpected);
        }

        failure = closeReader(reader, readerOpened, failure, DD_REPORT_INPUT);
        failure = closeReportWriter(writer, writerOpened, failure);
        failure = closeProcessor(processor, processorOpened, failure);
        return failure;
    }

    /**
     * Invokes every source-labelled correspondence method once per report execution. Each method records only
     * delegation evidence; all business state remains in {@link TransactionReportProcessor} or
     * {@link TransactionBackupReader}.
     */
    private void traceParagraphCorrespondence() {
        dateParmRead0550();
        transactionFileGetNext1000();
        writeTransactionReport1100();
        writePageTotals1110();
        writeAccountTotals1120();
        writeGrandTotals1110();
        writeHeaders1120();
        writeReportRecord1111();
        writeDetail1120();
        transactionFileOpen0000();
        reportFileOpen0100();
        cardCrossReferenceOpen0200();
        transactionTypeOpen0300();
        transactionCategoryOpen0400();
        dateParameterOpen0500();
        lookupCrossReference1500a();
        lookupTransactionType1500b();
        lookupTransactionCategory1500c();
        transactionFileClose9000();
        reportFileClose9100();
        cardCrossReferenceClose9200();
        transactionTypeClose9300();
        transactionCategoryClose9400();
        dateParameterClose9500();
        abendProgram9999();
        displayIoStatus9910();
    }

    /** {@code 0550-DATEPARM-READ}, {@code app/cbl/CBTRN03C.cbl:L219-L243}; delegated to validation. */
    private void dateParmRead0550() {
        LOG.trace("0550-DATEPARM-READ delegated to TransactionReportParametersValidator");
    }

    /** {@code 1000-TRANFILE-GET-NEXT}, {@code app/cbl/CBTRN03C.cbl:L248-L272}; delegated to the reader. */
    private void transactionFileGetNext1000() {
        LOG.trace("1000-TRANFILE-GET-NEXT delegated to TransactionBackupReader");
    }

    /** {@code 1100-WRITE-TRANSACTION-REPORT}, {@code app/cbl/CBTRN03C.cbl:L274-L290}. */
    private void writeTransactionReport1100() {
        LOG.trace("1100-WRITE-TRANSACTION-REPORT delegated to TransactionReportProcessor");
    }

    /** {@code 1110-WRITE-PAGE-TOTALS}, {@code app/cbl/CBTRN03C.cbl:L293-L304}. */
    private void writePageTotals1110() {
        LOG.trace("1110-WRITE-PAGE-TOTALS delegated to TransactionReportProcessor");
    }

    /** {@code 1120-WRITE-ACCOUNT-TOTALS}, {@code app/cbl/CBTRN03C.cbl:L306-L316}. */
    private void writeAccountTotals1120() {
        LOG.trace("1120-WRITE-ACCOUNT-TOTALS preserves the card break and Account Total label");
    }

    /** {@code 1110-WRITE-GRAND-TOTALS}, {@code app/cbl/CBTRN03C.cbl:L318-L322}. */
    private void writeGrandTotals1110() {
        LOG.trace("1110-WRITE-GRAND-TOTALS delegated to TransactionReportProcessor");
    }

    /** {@code 1120-WRITE-HEADERS}, {@code app/cbl/CBTRN03C.cbl:L324-L341}. */
    private void writeHeaders1120() {
        LOG.trace("1120-WRITE-HEADERS delegated to TransactionReportProcessor");
    }

    /** {@code 1111-WRITE-REPORT-REC}, {@code app/cbl/CBTRN03C.cbl:L343-L359}. */
    private void writeReportRecord1111() {
        LOG.trace("1111-WRITE-REPORT-REC delegated to FixedWidthReportItemWriter");
    }

    /** {@code 1120-WRITE-DETAIL}, {@code app/cbl/CBTRN03C.cbl:L361-L374}. */
    private void writeDetail1120() {
        LOG.trace("1120-WRITE-DETAIL delegated to TransactionReportProcessor");
    }

    /** {@code 0000-TRANFILE-OPEN}, {@code app/cbl/CBTRN03C.cbl:L376-L392}. */
    private void transactionFileOpen0000() {
        LOG.trace("0000-TRANFILE-OPEN delegated to TransactionReportProcessor and TransactionBackupReader");
    }

    /** {@code 0100-REPTFILE-OPEN}, {@code app/cbl/CBTRN03C.cbl:L394-L410}. */
    private void reportFileOpen0100() {
        LOG.trace("0100-REPTFILE-OPEN delegated to FixedWidthReportItemWriter");
    }

    /** {@code 0200-CARDXREF-OPEN}, {@code app/cbl/CBTRN03C.cbl:L412-L428}. */
    private void cardCrossReferenceOpen0200() {
        LOG.trace("0200-CARDXREF-OPEN delegated to TransactionReportProcessor");
    }

    /** {@code 0300-TRANTYPE-OPEN}, {@code app/cbl/CBTRN03C.cbl:L430-L446}. */
    private void transactionTypeOpen0300() {
        LOG.trace("0300-TRANTYPE-OPEN delegated to TransactionReportProcessor");
    }

    /** {@code 0400-TRANCATG-OPEN}, {@code app/cbl/CBTRN03C.cbl:L448-L464}. */
    private void transactionCategoryOpen0400() {
        LOG.trace("0400-TRANCATG-OPEN delegated to TransactionReportProcessor");
    }

    /** {@code 0500-DATEPARM-OPEN}, {@code app/cbl/CBTRN03C.cbl:L466-L482}. */
    private void dateParameterOpen0500() {
        LOG.trace("0500-DATEPARM-OPEN delegated to TransactionReportParametersValidator");
    }

    /** {@code 1500-A-LOOKUP-XREF}, {@code app/cbl/CBTRN03C.cbl:L484-L492}. */
    private void lookupCrossReference1500a() {
        LOG.trace("1500-A-LOOKUP-XREF delegated to TransactionReportProcessor");
    }

    /** {@code 1500-B-LOOKUP-TRANTYPE}, {@code app/cbl/CBTRN03C.cbl:L494-L502}. */
    private void lookupTransactionType1500b() {
        LOG.trace("1500-B-LOOKUP-TRANTYPE delegated to TransactionReportProcessor");
    }

    /** {@code 1500-C-LOOKUP-TRANCATG}, {@code app/cbl/CBTRN03C.cbl:L504-L512}. */
    private void lookupTransactionCategory1500c() {
        LOG.trace("1500-C-LOOKUP-TRANCATG delegated to TransactionReportProcessor");
    }

    /** {@code 9000-TRANFILE-CLOSE}, {@code app/cbl/CBTRN03C.cbl:L514-L530}. */
    private void transactionFileClose9000() {
        LOG.trace("9000-TRANFILE-CLOSE delegated to TransactionBackupReader and TransactionReportProcessor");
    }

    /** {@code 9100-REPTFILE-CLOSE}, {@code app/cbl/CBTRN03C.cbl:L532-L548}. */
    private void reportFileClose9100() {
        LOG.trace("9100-REPTFILE-CLOSE delegated to FixedWidthReportItemWriter");
    }

    /** {@code 9200-CARDXREF-CLOSE}, {@code app/cbl/CBTRN03C.cbl:L551-L567}. */
    private void cardCrossReferenceClose9200() {
        LOG.trace("9200-CARDXREF-CLOSE delegated to TransactionReportProcessor");
    }

    /** {@code 9300-TRANTYPE-CLOSE}, {@code app/cbl/CBTRN03C.cbl:L569-L585}. */
    private void transactionTypeClose9300() {
        LOG.trace("9300-TRANTYPE-CLOSE delegated to TransactionReportProcessor");
    }

    /** {@code 9400-TRANCATG-CLOSE}, {@code app/cbl/CBTRN03C.cbl:L587-L603}. */
    private void transactionCategoryClose9400() {
        LOG.trace("9400-TRANCATG-CLOSE delegated to TransactionReportProcessor");
    }

    /** {@code 9500-DATEPARM-CLOSE}, {@code app/cbl/CBTRN03C.cbl:L605-L621}. */
    private void dateParameterClose9500() {
        LOG.trace("9500-DATEPARM-CLOSE delegated to TransactionReportProcessor");
    }

    /** {@code 9999-ABEND-PROGRAM}, {@code app/cbl/CBTRN03C.cbl:L626-L630}. */
    private void abendProgram9999() {
        LOG.trace("9999-ABEND-PROGRAM maps to batch abend code 999 and return code 12");
    }

    /** {@code 9910-DISPLAY-IO-STATUS}, {@code app/cbl/CBTRN03C.cbl:L633-L646}. */
    private void displayIoStatus9910() {
        LOG.trace("9910-DISPLAY-IO-STATUS delegates to FileStatusMapper and {}",
                FileStatus.DISPLAY_MESSAGE_PREFIX);
    }

    /**
     * Calls the nested Spring Batch 5 writer with a defensive snapshot and clears the caller’s buffer.
     *
     * @param writer report writer
     * @param buffered report-line groups
     */
    private void writeReportRecord1111(
            final FixedWidthReportItemWriter writer,
            final List<ReportLines> buffered) {

        if (buffered.isEmpty()) {
            return;
        }
        writer.write(new Chunk<>(List.copyOf(buffered)));
        buffered.clear();
    }

    /**
     * Counts the undelimited fixed-width records in one object, <b>retaining none of them</b>.
     *
     * <p>One record-sized buffer is reused for the whole scan, so the live heap this method adds is
     * {@value #TRANSACTION_RECORD_LENGTH} bytes regardless of how large the object is. The buffer is read into
     * only so its bytes can be rejected: no field is decoded, nothing is returned but the count, and no record
     * content reaches a log or an exception message.
     *
     * @param objectKey concrete object key
     * @param logicalName logical DD name, for the {@code FILE STATUS} diagnostics
     * @param recordLength the exact record length the DD declares
     * @return the number of whole records the object holds, zero for an empty object
     * @throws DataIntegrityException if a separator byte appears or the length is not an exact multiple
     */
    private long countFixedWidthGeneration(
            final String objectKey,
            final String logicalName,
            final int recordLength) {

        long records = 0L;
        try {
            final S3Resource resource = objectStorage.download(outputBucket, objectKey);
            try (InputStream input = new BufferedInputStream(resource.getInputStream())) {
                while (true) {
                    final byte[] record = input.readNBytes(recordLength);
                    if (record.length == 0) {
                        break;
                    }
                    if (record.length != recordLength) {
                        throw new DataIntegrityException(String.format(Locale.ROOT,
                                "%s object %s in bucket %s ends with a partial record: row %d carries %d "
                                        + "bytes where app/proc/TRANREPT.prc:L29 declares "
                                        + "DCB=(LRECL=%d,RECFM=FB,BLKSIZE=0), so the object length must be an "
                                        + "exact multiple of %d",
                                logicalName, objectKey, outputBucket, Long.valueOf(records + 1L),
                                Integer.valueOf(record.length), Integer.valueOf(recordLength),
                                Integer.valueOf(recordLength)));
                    }
                    rejectRecordSeparator(record, objectKey, logicalName, records + 1L);
                    records++;
                }
            }
            fileStatusMapper.requireSuccess(STATUS_SUCCESS, logicalName, "READ");
            return records;
        } catch (final CardDemoException typed) {
            throw typed;
        } catch (final IOException | RuntimeException cause) {
            throw ioAbend(logicalName, "READ", "ERROR READING FIXED-WIDTH GENERATION", cause);
        }
    }

    /**
     * Refuses a record separator anywhere in a fixed-block record.
     *
     * <p>{@code RECFM=FB} carries no delimiter, so an {@code LF}, {@code CR} or {@code CRLF} inside a record
     * image is not a terminator to be consumed - it is evidence that the object was written by something that
     * treated the records as text lines, and every record after the first is therefore shifted. Refusing it
     * names the defect where it starts. The scan covers the whole record rather than only its final byte,
     * because a shifted record carries the stray byte in the interior, not at the end.
     *
     * @param record one record image, exactly {@code recordLength} bytes
     * @param objectKey the object being read, for the diagnostic
     * @param logicalName the logical DD name
     * @param row the one-based row number, for the diagnostic
     * @throws DataIntegrityException if any separator byte is present
     */
    private void rejectRecordSeparator(
            final byte[] record,
            final String objectKey,
            final String logicalName,
            final long row) {

        for (int offset = 0; offset < record.length; offset++) {
            final byte candidate = record[offset];
            if (candidate == LINE_FEED || candidate == CARRIAGE_RETURN) {
                throw new DataIntegrityException(String.format(Locale.ROOT,
                        "%s object %s in bucket %s carries a record separator 0x%02X at byte %d of row %d. "
                                + "app/proc/TRANREPT.prc:L29 declares DCB=(LRECL=%d,RECFM=FB,BLKSIZE=0), "
                                + "which is undelimited, so the object geometry is corrupt and every record "
                                + "after the first is shifted",
                        logicalName, objectKey, outputBucket, Byte.valueOf(candidate),
                        Integer.valueOf(offset + 1), Long.valueOf(row),
                        Integer.valueOf(TRANSACTION_RECORD_LENGTH)));
            }
        }
    }

    /**
     * Streams the ordered, filtered transaction population into the concrete {@code SORTOUT} object.
     *
     * <p><b>This is the {@code SORT} of {@code app/proc/TRANREPT.prc:L43-L46}, and it spawns no external
     * process.</b> AAP section 0.4.3 nominates "Comparator plus repository ordering" as the DFSORT
     * replacement, and that is what this is: {@code ORDER BY tran_card_num, tran_id} served by
     * {@code idx_transaction_proc_ts} over the range predicate, paged so that one chunk is live at a time.
     *
     * <p><b>The emitted order is byte-identical to the order the heap sort produced.</b> The old code sorted a
     * generation written in ascending {@code TRAN-ID} order - that is the order
     * {@code TransactionBackupReader} browses the relation in - with {@code List.sort}, which is <b>stable</b>,
     * so equal card numbers stayed in {@code TRAN-ID} order. The query's total order
     * {@code cardNumber asc, transactionId asc} is the same permutation, and it is deterministic where a
     * stable sort over an incidentally-ordered input merely happened to be. {@code app/proc/TRANREPT.prc:L44}
     * declares {@code SORT FIELDS=(TRAN-CARD-NUM,A)} alone, so DFSORT itself leaves ties unordered; naming the
     * tiebreak is therefore a tightening of an unspecified case and not a divergence.
     *
     * <p><b>Ordering and comparison are byte-ordered on both sides.</b> {@code docker-compose.yml} initialises
     * the database with {@code --locale=C} and every Testcontainers definition does the same, so
     * {@code ORDER BY} and {@code >=} in SQL mean exactly what {@code String.compareTo} means in Java. That is
     * what makes it sound to move the predicate and the ordering across the boundary at all; under a
     * locale-dependent collation punctuation is reordered and the two would disagree.
     *
     * <p><b>The inclusive predicate is nevertheless re-applied on the rendered image.</b> The SQL range is
     * derived, and {@link #includeCondition} is the definition; re-checking the 350-byte image against it
     * costs one comparison per surviving row, retains nothing, and means the object this step publishes is
     * governed by the byte-level predicate the source declares rather than by the derivation. A row that
     * reached here without matching is a defect in the derivation, so it fails the step rather than being
     * silently dropped.
     *
     * @param dailyKey the concrete {@code SORTOUT} key this step creates
     * @param startDate the inclusive ten-character lower bound, {@code app/proc/TRANREPT.prc:L45}
     * @param endDate the inclusive ten-character upper bound, {@code app/proc/TRANREPT.prc:L46}
     * @return the number of 350-byte records written
     */
    private long writeSortedGeneration(
            final String dailyKey,
            final String startDate,
            final String endDate) {

        final String endBoundExclusive = exclusiveEndBound(startDate, endDate);
        long recordCount = 0L;
        try {
            final S3Resource resource = outputResource(dailyKey);
            try (OutputStream output = new BufferedOutputStream(resource.getOutputStream())) {
                int pageNumber = 0;
                boolean morePages = true;
                while (morePages) {
                    final Slice<Transaction> page =
                            transactionRepository.findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc(
                                    startDate, endBoundExclusive, PageRequest.of(pageNumber, chunkSize));
                    for (final Transaction transaction : page.getContent()) {
                        final byte[] encoded = encodeTransactionRecord(transaction, DD_SORT_OUTPUT);
                        requireDerivedRangeMatched(encoded, startDate, endDate, endBoundExclusive);
                        output.write(encoded);
                        recordCount++;
                    }
                    morePages = page.hasNext();
                    pageNumber++;
                }
                output.flush();
            }
            fileStatusMapper.requireSuccess(STATUS_SUCCESS, DD_SORT_OUTPUT, "WRITE");
            return recordCount;
        } catch (final CardDemoException typed) {
            throw typed;
        } catch (final IOException | RuntimeException cause) {
            throw ioAbend(DD_SORT_OUTPUT, "WRITE", "ERROR WRITING FIXED-WIDTH GENERATION", cause);
        }
    }

    /**
     * Renders one entity as its 350-byte fixed-width image.
     *
     * @param transaction the row to render
     * @param logicalName the logical DD name the length is checked against
     * @return exactly {@value #TRANSACTION_RECORD_LENGTH} bytes
     */
    private byte[] encodeTransactionRecord(final Transaction transaction, final String logicalName) {
        final String record = transactionRecord(
                transaction.getTransactionId(),
                transaction.getTypeCode(),
                transaction.getCategoryCode(),
                transaction.getTransactionSource(),
                transaction.getDescription(),
                transaction.getAmount(),
                transaction.getMerchantId(),
                transaction.getMerchantName(),
                transaction.getMerchantCity(),
                transaction.getMerchantZip(),
                transaction.getCardNumber(),
                transaction.getOrigTs(),
                transaction.getProcTs());
        final byte[] encoded = record.getBytes(FIXED_WIDTH_CHARSET);
        requireEncodedLength(encoded, TRANSACTION_RECORD_LENGTH, logicalName);
        return encoded;
    }

    /**
     * Confirms a row the derived SQL range admitted also satisfies the source's byte-level predicate.
     *
     * @param encoded the rendered 350-byte image
     * @param startDate the inclusive lower bound
     * @param endDate the inclusive upper bound
     * @param endBoundExclusive the derived exclusive bound, named in the diagnostic
     * @throws DataIntegrityException if the derivation and the declared predicate disagree
     */
    private void requireDerivedRangeMatched(
            final byte[] encoded,
            final String startDate,
            final String endDate,
            final String endBoundExclusive) {

        if (!includeCondition(encoded, startDate, endDate)) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "a row admitted by the derived half-open range [%s, %s) fails the INCLUDE COND of "
                            + "app/proc/TRANREPT.prc:L45-L46, which is inclusive on both bounds [%s, %s]. "
                            + "The derivation and the declared predicate must agree exactly; they do not, so "
                            + "%s is not written rather than being written with a population the source would "
                            + "not have selected",
                    startDate, endBoundExclusive, startDate, endDate, DD_SORT_OUTPUT));
        }
    }

    /**
     * Derives the exclusive upper bound that is equivalent to the source's inclusive ten-character one.
     *
     * <p>{@code app/proc/TRANREPT.prc:L46} includes a record when the ten characters at byte 305 are less than
     * or equal to the end date. {@code TRAN-PROC-TS} is {@code PIC X(26)}, so the SQL predicate compares the
     * whole 26 characters and {@code procTs <= endDate} would be wrong: any real timestamp on the end date is
     * longer than the ten-character bound and therefore compares <b>greater</b> than it. An exclusive bound is
     * required, and {@code TransactionRepository} states that deriving it belongs to the caller.
     *
     * <p><b>The derivation increments the final character of the ten-character date, and nothing else.</b> For
     * {@code 2026-01-31} the bound is {@code 2026-01-32}. Any timestamp whose first ten characters are at most
     * the end date compares less than that bound, because the decision falls at character ten; any timestamp
     * whose first ten characters name a later date compares greater, because the decision falls earlier. It is
     * therefore exactly equivalent, and it is preferred over the two alternatives for specific reasons:
     * <ul>
     *   <li><b>It assumes nothing about character eleven.</b> Appending a separator would: three mutually
     *       incompatible producers write these 26 bytes, the batch generator emitting
     *       {@code yyyy-MM-dd-HH.mm.ss.SS0000} with a hyphen at character eleven and the online path emitting
     *       {@code yyyy-MM-dd HH:mm:ss.000000} with a <b>space</b> there, and a third path passing input
     *       through unchanged. A bound that depended on which producer wrote the row would admit one and
     *       exclude the other.</li>
     *   <li><b>It performs no date arithmetic.</b> Adding one day would reintroduce calendar handling - month
     *       ends, leap years - into a comparison the source performs on characters and never parses, and AAP
     *       section 0.8.3 requires the lexical comparison to stay lexical.</li>
     * </ul>
     * The bound is a comparison operand only. It is never rendered, stored or reported as a date, so the fact
     * that {@code 2026-01-32} is not a calendar date is immaterial - and incrementing {@code 9} yields
     * {@code :}, which orders immediately after {@code 9} and is equally serviceable.
     *
     * <p><b>The parameter's shape is checked here rather than assumed.</b> This method is the first statement
     * of the step, so it is the last point before a query is issued or an object is created; an end date that
     * is not exactly {@code yyyy-MM-dd} would otherwise be incremented anyway - {@code 2022-06-3X} becoming
     * {@code 2022-06-3Y} - and turn a malformed parameter into a silently wrong scan. It abends instead, with
     * reason {@code END DATE INVALID}, before the repository or the object store is touched. The start date is
     * checked on the same terms, because it reaches the predicate as an operand in exactly the same way.
     *
     * @param startDate the inclusive ten-character lower bound, checked for shape and otherwise passed through
     * @param endDate the inclusive ten-character end date whose exclusive equivalent is derived
     * @return the exclusive bound, ten characters, strictly greater than every timestamp on {@code endDate}
     * @throws FatalProcessingException with reason {@code START DATE INVALID} or {@code END DATE INVALID} when
     *     either parameter is not the ten-character dashed form
     */
    private String exclusiveEndBound(final String startDate, final String endDate) {
        requireParameterDate(startDate, "START DATE INVALID", "startDate");
        requireParameterDate(endDate, "END DATE INVALID", "endDate");
        final int lastIndex = endDate.length() - 1;
        final char incremented = (char) (endDate.charAt(lastIndex) + 1);
        return endDate.substring(0, lastIndex) + incremented;
    }

    /**
     * Refuses a period parameter that is not the ten-character dashed date the parameter cards carry.
     *
     * @param value the parameter as supplied, which may be {@code null}
     * @param reason the abend reason to report
     * @param parameterName the parameter's own name, for the diagnostic
     * @throws FatalProcessingException when the value is absent or not {@code yyyy-MM-dd}
     */
    private void requireParameterDate(final String value, final String reason, final String parameterName) {
        if (value == null || !PARAMETER_DATE_SHAPE.matcher(value).matches()) {
            throw abend(reason, String.format(Locale.ROOT,
                    "JOB PARAMETER %s MUST BE THE TEN-CHARACTER DATE app/proc/TRANREPT.prc:L45-L46 SUPPLIES",
                    parameterName), null);
        }
    }

    /**
     * Creates a writable resource and applies deterministic binary metadata before its stream is opened.
     *
     * @param objectKey concrete object key
     * @return writable resource
     */
    private S3Resource outputResource(final String objectKey) {
        final S3Resource resource = objectStorage.createResource(outputBucket, objectKey);
        resource.setObjectMetadata(ObjectMetadata.builder()
                .contentType(OBJECT_CONTENT_TYPE)
                .build());
        return resource;
    }

    /**
     * Renders one 350-character transaction record in the 1-based offset order corroborated by
     * {@code app/proc/TRANREPT.prc:L39-L40}.
     *
     * @param transactionId source transaction identifier
     * @param typeCode source transaction-type code
     * @param categoryCode source transaction-category code
     * @param transactionSource source-system identifier
     * @param description transaction description
     * @param amount transaction amount rendered as {@code PIC S9(09)V99}
     * @param merchantId merchant identifier
     * @param merchantName merchant name
     * @param merchantCity merchant city
     * @param merchantZip merchant postal code
     * @param cardNumber card number occupying source bytes 263-278
     * @param originatingStamp 26-character originating timestamp image
     * @param processingStamp 26-character processing timestamp image
     * @return exactly 350 characters
     */
    private String transactionRecord(
            final String transactionId,
            final String typeCode,
            final Integer categoryCode,
            final String transactionSource,
            final String description,
            final BigDecimal amount,
            final Long merchantId,
            final String merchantName,
            final String merchantCity,
            final String merchantZip,
            final String cardNumber,
            final String originatingStamp,
            final String processingStamp) {

        final StringBuilder image = new StringBuilder(TRANSACTION_RECORD_LENGTH);
        image.append(alphanumeric(transactionId, 16, "TRAN-ID"));
        image.append(alphanumeric(typeCode, 2, "TRAN-TYPE-CD"));
        image.append(unsignedInteger(categoryCode, 4, "TRAN-CAT-CD"));
        image.append(alphanumeric(transactionSource, 10, "TRAN-SOURCE"));
        image.append(alphanumeric(description, 100, "TRAN-DESC"));
        image.append(signedZonedAmount(amount));
        image.append(unsignedLong(merchantId, 9, "TRAN-MERCHANT-ID"));
        image.append(alphanumeric(merchantName, 50, "TRAN-MERCHANT-NAME"));
        image.append(alphanumeric(merchantCity, 50, "TRAN-MERCHANT-CITY"));
        image.append(alphanumeric(merchantZip, 10, "TRAN-MERCHANT-ZIP"));
        image.append(alphanumeric(cardNumber, 16, "TRAN-CARD-NUM"));
        image.append(alphanumeric(originatingStamp, 26, "TRAN-ORIG-TS"));
        image.append(alphanumeric(processingStamp, 26, "TRAN-PROC-TS"));
        image.append(" ".repeat(20));
        final String record = image.toString();
        if (record.length() != TRANSACTION_RECORD_LENGTH) {
            throw abend("RECORD LENGTH VIOLATION",
                    "TRANSACTION BACKUP RECORD IS NOT 350 CHARACTERS", null);
        }
        return record;
    }

    /**
     * Encodes {@code PIC S9(09)V99} as eleven zoned-decimal characters with a trailing overpunch.
     *
     * @param amount monetary amount
     * @return eleven-character image
     */
    private String signedZonedAmount(final BigDecimal amount) {
        if (amount == null) {
            throw abend("AMOUNT MISSING", "TRAN-AMT IS REQUIRED", null);
        }
        final BigDecimal scaled = amount.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
        final BigInteger unscaled;
        try {
            unscaled = scaled.movePointRight(MONEY_SCALE).toBigIntegerExact();
        } catch (final ArithmeticException cause) {
            throw abend("AMOUNT INVALID", "TRAN-AMT CANNOT BE RENDERED AT SCALE 2", cause);
        }
        String digits = unscaled.abs().toString();
        if (digits.length() > AMOUNT_DIGITS) {
            throw abend("AMOUNT OVERFLOW", "TRAN-AMT EXCEEDS PIC S9(09)V99", null);
        }
        digits = "0".repeat(AMOUNT_DIGITS - digits.length()) + digits;
        final int finalDigit = digits.charAt(AMOUNT_DIGITS - 1) - '0';
        final char overpunch;
        if (unscaled.signum() < 0) {
            overpunch = finalDigit == 0 ? '}' : (char) ('J' + finalDigit - 1);
        } else {
            overpunch = finalDigit == 0 ? '{' : (char) ('A' + finalDigit - 1);
        }
        return digits.substring(0, AMOUNT_DIGITS - 1) + overpunch;
    }

    /**
     * Renders an alphanumeric picture clause, refusing truncation and non-printable input.
     *
     * @param value source value; {@code null} renders as an empty value
     * @param width declared fixed width
     * @param fieldName logical field name used in typed failures
     * @return space-padded fixed-width value
     */
    private String alphanumeric(final String value, final int width, final String fieldName) {
        final String actual = value == null ? "" : value;
        if (actual.length() > width) {
            throw abend("FIELD WIDTH VIOLATION",
                    fieldName + " EXCEEDS ITS DECLARED WIDTH", null);
        }
        for (int index = 0; index < actual.length(); index++) {
            final char character = actual.charAt(index);
            final boolean printableAscii = character >= 0x20 && character <= 0x7E;
            final boolean printableLatinOne = character >= 0xA0 && character <= 0xFF;
            if (!printableAscii && !printableLatinOne) {
                throw abend("FIELD CHARACTER VIOLATION",
                        fieldName + " CONTAINS A NON-PRINTABLE CHARACTER", null);
            }
        }
        return actual + " ".repeat(width - actual.length());
    }

    /**
     * Renders an unsigned integer picture clause.
     *
     * @param value source integer
     * @param width declared fixed width
     * @param fieldName logical field name used in typed failures
     * @return zero-padded unsigned value
     */
    private String unsignedInteger(final Integer value, final int width, final String fieldName) {
        if (value == null) {
            throw abend("FIELD MISSING", fieldName + " IS REQUIRED", null);
        }
        return unsignedDigits(Integer.toString(value.intValue()), width, fieldName);
    }

    /**
     * Renders an unsigned long picture clause.
     *
     * @param value source long
     * @param width declared fixed width
     * @param fieldName logical field name used in typed failures
     * @return zero-padded unsigned value
     */
    private String unsignedLong(final Long value, final int width, final String fieldName) {
        if (value == null) {
            throw abend("FIELD MISSING", fieldName + " IS REQUIRED", null);
        }
        return unsignedDigits(Long.toString(value.longValue()), width, fieldName);
    }

    /**
     * Renders ASCII digits with zero padding.
     *
     * @param digits source digit image
     * @param width declared fixed width
     * @param fieldName logical field name used in typed failures
     * @return zero-padded digit image
     */
    private String unsignedDigits(final String digits, final int width, final String fieldName) {
        if (digits.startsWith("-") || digits.length() > width) {
            throw abend("FIELD RANGE VIOLATION",
                    fieldName + " DOES NOT FIT ITS UNSIGNED PICTURE", null);
        }
        for (int index = 0; index < digits.length(); index++) {
            final char character = digits.charAt(index);
            if (character < '0' || character > '9') {
                throw abend("FIELD DIGIT VIOLATION",
                        fieldName + " CONTAINS A NON-DIGIT", null);
            }
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Verifies a byte record before it crosses an object boundary.
     *
     * @param record record to validate
     * @param expected required byte count
     * @param logicalName logical DD name used in typed failures
     */
    private void requireRecordLength(
            final byte[] record,
            final int expected,
            final String logicalName) {

        if (record == null || record.length != expected) {
            final int actual = record == null ? 0 : record.length;
            throw abend("RECORD LENGTH VIOLATION",
                    String.format(Locale.ROOT, "%s RECORD IS %d BYTES; EXPECTED %d",
                            logicalName, Integer.valueOf(actual), Integer.valueOf(expected)),
                    null);
        }
    }

    /**
     * Verifies encoded length independently of character count.
     *
     * @param encoded encoded record to validate
     * @param expected required byte count
     * @param logicalName logical DD name used in typed failures
     */
    private void requireEncodedLength(
            final byte[] encoded,
            final int expected,
            final String logicalName) {

        requireRecordLength(encoded, expected, logicalName);
    }

    /**
     * Creates a monotonically increasing, lexically ordered object key from the job-instance identifier.
     *
     * @param prefix validated generation prefix
     * @param jobInstanceId non-negative Spring Batch job-instance identifier
     * @param objectName terminal object name
     * @return concrete generation key with a zero-padded 19-digit generation
     */
    private String generationObjectKey(
            final String prefix,
            final long jobInstanceId,
            final String objectName) {

        if (jobInstanceId < 0L) {
            throw abend("JOB INSTANCE INVALID", "JOB INSTANCE IDENTIFIER MUST NOT BE NEGATIVE", null);
        }
        final String generation = String.format(
                Locale.ROOT, "%0" + GENERATION_NUMBER_WIDTH + "d", Long.valueOf(jobInstanceId));
        return prefix + "/generation=" + generation + "/" + objectName;
    }

    /**
     * Publishes a concrete key to the step and job contexts.
     *
     * @param stepExecution current producing step
     * @param contextKey execution-context key
     * @param objectKey concrete object key
     */
    private void publishConcreteKey(
            final StepExecution stepExecution,
            final String contextKey,
            final String objectKey) {

        stepExecution.getExecutionContext().putString(contextKey, objectKey);
        stepExecution.getJobExecution().getExecutionContext().putString(contextKey, objectKey);
    }

    /**
     * Publishes a non-negative count to the step and job contexts.
     *
     * @param stepExecution current producing step
     * @param contextKey execution-context key
     * @param count non-negative count to publish
     */
    private void publishCount(
            final StepExecution stepExecution,
            final String contextKey,
            final long count) {

        if (count < 0L) {
            throw abend("COUNT INVALID", "EXECUTION COUNT MUST NOT BE NEGATIVE", null);
        }
        stepExecution.getExecutionContext().putLong(contextKey, count);
        stepExecution.getJobExecution().getExecutionContext().putLong(contextKey, count);
    }

    /**
     * Reads a count a prior step published into the job execution context.
     *
     * <p>Absence is reported as {@value #COUNT_NOT_PUBLISHED} rather than as zero, because the two mean
     * different things: zero is a step that ran and found nothing, and absence is a step that did not publish.
     * A caller that conflated them would compare against zero and pass whenever the producer had been skipped.
     *
     * @param stepExecution the consuming step execution
     * @param contextKey the key the producing step published under
     * @return the published count, or {@value #COUNT_NOT_PUBLISHED} when the key is absent
     */
    private long publishedCount(final StepExecution stepExecution, final String contextKey) {
        final ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();
        if (!jobContext.containsKey(contextKey)) {
            return COUNT_NOT_PUBLISHED;
        }
        return jobContext.getLong(contextKey, COUNT_NOT_PUBLISHED);
    }

    /**
     * Reads and validates an exact prior-step key; no bucket listing fallback exists.
     *
     * @param stepExecution current consuming step
     * @param contextKey execution-context key populated by the producer
     * @param expectedPrefix required generation prefix
     * @return validated concrete prior-step object key
     */
    private String requireConcreteKey(
            final StepExecution stepExecution,
            final String contextKey,
            final String expectedPrefix) {

        final ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();
        if (!jobContext.containsKey(contextKey)) {
            throw abend("MISSING STEP HANDOFF",
                    "REQUIRED EXECUTION-CONTEXT OBJECT KEY IS ABSENT", null);
        }
        final String key = jobContext.getString(contextKey, "");
        if (key.isBlank()
                || key.length() > 1024
                || !key.startsWith(expectedPrefix + "/")
                || key.contains("..")
                || !isPrintableAscii(key)) {
            throw abend("INVALID STEP HANDOFF",
                    "EXECUTION-CONTEXT OBJECT KEY IS OUTSIDE THE EXPECTED GENERATION PREFIX", null);
        }
        return key;
    }

    /**
     * Returns the current job-instance identifier.
     *
     * @param stepExecution current step
     * @return non-negative Spring Batch job-instance identifier
     */
    private long requireJobInstanceId(final StepExecution stepExecution) {
        if (stepExecution.getJobExecution().getJobInstance() == null) {
            throw abend("JOB INSTANCE MISSING", "SPRING BATCH JOB INSTANCE IS REQUIRED", null);
        }
        return stepExecution.getJobExecution().getJobInstance().getInstanceId();
    }

    /**
     * Validates the full untrusted date-parameter pair.
     *
     * @param parameters job parameters containing {@code startDate} and {@code endDate}
     */
    private void validateDateRange(final JobParameters parameters) {
        final String startDate =
                requireDateParameter(parameters, TransactionReportProcessor.START_DATE_JOB_PARAMETER);
        final String endDate =
                requireDateParameter(parameters, TransactionReportProcessor.END_DATE_JOB_PARAMETER);
        if (startDate.compareTo(endDate) > 0) {
            throw ValidationException.invalidField(
                    TransactionReportProcessor.START_DATE_JOB_PARAMETER,
                    "startDate must not be after endDate");
        }
    }

    /**
     * Validates one exact ten-character report date through the shared service.
     *
     * @param parameters job parameters containing the requested value
     * @param parameterName exact job-parameter name
     * @return validated ten-character date image
     */
    private String requireDateParameter(final JobParameters parameters, final String parameterName) {
        if (parameters == null) {
            throw new ValidationException("Job parameters are required");
        }
        final String value = parameters.getString(parameterName);
        if (value == null || value.isBlank()) {
            throw ValidationException.missingField(parameterName, parameterName + " is required");
        }
        if (!hasReportDateShape(value)) {
            throw ValidationException.invalidField(
                    parameterName, parameterName + " must have exact yyyy-MM-dd shape");
        }
        final var result =
                dateValidationService.validate(value, DateValidationService.MASK_YYYY_MM_DD);
        if (!result.valid()) {
            throw ValidationException.invalidField(
                    parameterName, parameterName + " is not a valid calendar date");
        }
        return value;
    }

    /**
     * Tests exact ASCII {@code yyyy-MM-dd} shape without coercion.
     *
     * @param value candidate date image
     * @return {@code true} only for the exact ten-character digit-and-dash shape
     */
    private boolean hasReportDateShape(final String value) {
        if (value.length() != REPORT_DATE_LENGTH
                || value.charAt(4) != '-'
                || value.charAt(7) != '-') {
            return false;
        }
        for (int index = 0; index < REPORT_DATE_LENGTH; index++) {
            if (index == 4 || index == 7) {
                continue;
            }
            final char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates a non-blank configuration value without trimming it.
     *
     * @param value configured value
     * @param property property name used in validation failures
     * @return unchanged validated value
     */
    private String requireConfiguredText(final String value, final String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " must not be blank");
        }
        if (!value.equals(value.strip()) || !isPrintableAscii(value)) {
            throw new IllegalArgumentException(
                    property + " must contain printable ASCII with no surrounding whitespace");
        }
        return value;
    }

    /**
     * Validates an object-key prefix without silently normalising it.
     *
     * <p><strong>Finding m-02, severity Medium, RESOLVED.</strong> This validator was the strict one the review
     * named as the benchmark, and five sibling classes each carried a weaker variant. The grammar it applied is
     * now published once as {@link GenerationPrefixContract#requireRelativePrefix(String, String)} and all six
     * delegate to it, so the rule is defined in one place and cannot diverge again. The shared form adds three
     * rules this one lacked - a backslash, a lone {@code .} segment and an interior space are refused as well -
     * and reports the offending position for a character failure; every prefix this method accepted before is
     * still accepted.
     *
     * @param value configured prefix
     * @param property property name used in validation failures
     * @return unchanged safe relative prefix
     */
    private String requireGenerationPrefix(final String value, final String property) {
        return GenerationPrefixContract.requireRelativePrefix(value, property);
    }

    /**
     * Validates a positive configuration integer.
     *
     * @param value configured integer
     * @param property property name used in validation failures
     * @return unchanged positive value
     */
    private int requirePositive(final int value, final String property) {
        if (value < 1) {
            throw new IllegalArgumentException(property + " must be positive");
        }
        return value;
    }

    /**
     * Validates the sole resolved retention decision while leaving lifecycle enforcement external.
     *
     * @param value configured retention generation count
     * @return the validated value, which must equal 10
     */
    private int requireRetention(final int value) {
        if (value != RESOLVED_REPORT_RETENTION) {
            throw new IllegalArgumentException(
                    "carddemo.aws.s3.gdg-retention-generations must remain 10 for TRANREPT");
        }
        return value;
    }

    /**
     * Tests printable ASCII for object keys and configuration coordinates.
     *
     * @param value candidate text
     * @return {@code true} when every character is in the printable ASCII range
     */
    private boolean isPrintableAscii(final String value) {
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < 0x20 || character > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * Maps an object-store failure through FILE STATUS and then into the source abend path.
     *
     * @param logicalName logical DD name
     * @param operation failed I/O operation
     * @param message source-compatible diagnostic message
     * @param cause original failure
     * @return typed batch abend preserving the mapped or original cause
     */
    private FatalProcessingException ioAbend(
            final String logicalName,
            final String operation,
            final String message,
            final Throwable cause) {

        try {
            fileStatusMapper.requireSuccess(
                    STATUS_IO_ERROR, logicalName, operation, cause);
        } catch (final CardDemoException mapped) {
            LOG.error(message);
            LOG.error(fileStatusMapper.displayIoStatus(STATUS_IO_ERROR));
            return abend(operation + " FAILED", message, mapped);
        }
        return abend(operation + " FAILED", message, cause);
    }

    /**
     * Constructs the complete batch abend payload with cause preservation.
     *
     * @param reason concise abend reason
     * @param message source-compatible diagnostic message
     * @param cause original or mapped failure
     * @return typed batch abend with code 999 and return code 12
     */
    private FatalProcessingException abend(
            final String reason,
            final String message,
            final Throwable cause) {

        return new FatalProcessingException(
                ABEND_CODE, ABEND_CULPRIT, reason, message, cause);
    }

    /**
     * Closes a reader without losing an earlier typed failure.
     *
     * @param reader reader to close
     * @param opened whether the reader completed its open operation
     * @param existing earlier typed failure, or {@code null}
     * @param logicalName logical DD name used in close diagnostics
     * @return the original failure, a close failure, or {@code null}
     */
    private CardDemoException closeReader(
            final TransactionBackupReader reader,
            final boolean opened,
            final CardDemoException existing,
            final String logicalName) {

        if (!opened) {
            return existing;
        }
        try {
            reader.close();
            return existing;
        } catch (final CardDemoException closeFailure) {
            return mergeFailure(existing, closeFailure);
        } catch (final RuntimeException closeFailure) {
            return mergeFailure(existing,
                    abend("CLOSE FAILED", "ERROR CLOSING " + logicalName, closeFailure));
        }
    }

    /**
     * Closes the nested report writer without losing an earlier typed failure.
     *
     * @param writer writer to close
     * @param opened whether the writer completed its open operation
     * @param existing earlier typed failure, or {@code null}
     * @return the original failure, a close failure, or {@code null}
     */
    private CardDemoException closeReportWriter(
            final FixedWidthReportItemWriter writer,
            final boolean opened,
            final CardDemoException existing) {

        if (!opened) {
            return existing;
        }
        try {
            writer.close();
            return existing;
        } catch (final CardDemoException closeFailure) {
            return mergeFailure(existing, closeFailure);
        } catch (final RuntimeException closeFailure) {
            return mergeFailure(existing,
                    abend("CLOSE FAILED", "ERROR CLOSING TRANREPT", closeFailure));
        }
    }

    /**
     * Closes delegated report datasets without losing an earlier typed failure.
     *
     * @param processor processor whose datasets must be closed
     * @param opened whether the datasets completed their open operations
     * @param existing earlier typed failure, or {@code null}
     * @return the original failure, a close failure, or {@code null}
     */
    private CardDemoException closeProcessor(
            final TransactionReportProcessor processor,
            final boolean opened,
            final CardDemoException existing) {

        if (!opened) {
            return existing;
        }
        try {
            processor.closeDatasets();
            return existing;
        } catch (final CardDemoException closeFailure) {
            return mergeFailure(existing, closeFailure);
        } catch (final RuntimeException closeFailure) {
            return mergeFailure(existing,
                    abend("CLOSE FAILED", "ERROR CLOSING REPORT DATASETS", closeFailure));
        }
    }

    /**
     * Preserves the first failure and attaches every cleanup failure as suppressed evidence.
     *
     * @param existing earlier typed failure, or {@code null}
     * @param additional later cleanup failure
     * @return the first failure with the additional failure suppressed, or the additional failure
     */
    private CardDemoException mergeFailure(
            final CardDemoException existing,
            final CardDemoException additional) {

        if (existing == null) {
            return additional;
        }
        existing.addSuppressed(additional);
        return existing;
    }

    /**
     * Job-parameter validator that rejects malformed input before STEP01R creates an object.
     */
    private final class TransactionReportParametersValidator implements JobParametersValidator {

        /** Narrows the implicit constructor: only the enclosing job builds this. */
        private TransactionReportParametersValidator() {
        }

        /** {@inheritDoc} */
        @Override
        public void validate(final JobParameters parameters) {
            validateDateRange(parameters);
        }
    }

    /**
     * Inline batch diagnostic-context lifecycle and source-finding logger.
     *
     * <p><strong>Findings H-02 and M-02, severities High and Medium.</strong> This listener used to park four
     * diagnostic entries in the job execution context under four string literals of its own, and then fill
     * {@code traceId} and {@code spanId} with random UUIDs whenever the thread carried none. Both halves were
     * wrong. The literals were a second spelling of a contract
     * {@link CorrelationIdFilter} already owns, and the two spellings had already drifted in lifecycle
     * behaviour from the other batch listeners. The fabricated identifiers were worse: this job creates no
     * span, so the values named traces that no tracing backend held, and a
     * {@value CorrelationIdFilter#TRACE_PARENT_HEADER} composed from them would have invited the next hop to
     * parent itself onto a trace that does not exist. An untraced run now emits no trace identifier, which is
     * the honest rendering, and {@link CorrelationIdFilter#enterBatchScope(long, String)} is the one
     * implementation of the snapshot-and-restore this listener shares with every other job.
     */
    private final class TransactionReportJobListener implements JobExecutionListener {

        /** Narrows the implicit constructor; the displaced context lives on the thread that raised it. */
        private TransactionReportJobListener() {
        }

        /**
         * Establishes the job instance and correlation entries before the first event this class emits.
         *
         * <p>The instance identifier falls back to zero for an execution carrying no instance, which a
         * partially constructed execution can: a diagnostic aid must label the run rather than fail it.
         *
         * @param jobExecution the starting execution
         */
        @Override
        public void beforeJob(final JobExecution jobExecution) {
            final long instanceId = jobExecution.getJobInstance() == null
                    ? 0L
                    : jobExecution.getJobInstance().getInstanceId();
            CorrelationIdFilter.enterBatchScope(instanceId, mintedCorrelationId(jobExecution));

            LOG.info("START OF EXECUTION OF JOB {}: {} ordered steps; retention={} documented only",
                    jobName, Integer.valueOf(3), Integer.valueOf(reportRetentionGenerations));
            LOG.info("Procedure authority is app/proc/TRANREPT.prc; topology evidence entries={}",
                    Integer.valueOf(LEGACY_TOPOLOGY.size()));
            LOG.warn("High legacy finding: app/jcl/TRANREPT.jcl:L23 and :L37 duplicate STEP05R, while "
                    + "app/cbl/CORPT00C.cbl:L98 supplies a qualified override; using the procedure member");
            LOG.warn("High legacy finding: app/cbl/CBTRN03C.cbl:L177 transfers past the period at :L206");
            LOG.info("Low legacy findings retained: internal REPROC label, JCL backtick, partial control-card "
                    + "banner; display literals '{}' and '{}' remain delegated",
                    DISPLAY_TRAN_AMOUNT, DISPLAY_PAGE_TOTAL);
        }

        /**
         * Logs the final outcome and restores the displaced diagnostic context in a {@code finally} block.
         *
         * @param jobExecution the finishing execution
         */
        @Override
        public void afterJob(final JobExecution jobExecution) {
            try {
                final ExecutionContext context = jobExecution.getExecutionContext();
                LOG.info("END OF EXECUTION OF JOB {}: status={} exit={} backup={} daily={} reportLines={}",
                        jobName,
                        jobExecution.getStatus(),
                        jobExecution.getExitStatus().getExitCode(),
                        Long.valueOf(context.getLong(BACKUP_RECORD_COUNT_CONTEXT, 0L)),
                        Long.valueOf(context.getLong(DAILY_RECORD_COUNT_CONTEXT, 0L)),
                        Long.valueOf(context.getLong(REPORT_LINE_COUNT_CONTEXT, 0L)));
            } finally {
                CorrelationIdFilter.exitBatchScope();
            }
        }

        /**
         * The correlation identifier this listener mints when the thread carries none.
         *
         * @param jobExecution the execution being labelled
         * @return {@value #CORRELATION_ID_PREFIX} followed by the execution identifier, never {@code null}
         */
        private String mintedCorrelationId(final JobExecution jobExecution) {
            return CORRELATION_ID_PREFIX + jobExecution.getId();
        }
    }

    /**
     * Uniform decider covering legacy RC 0, 4, 8 and 12. RC 4 is recognised but never set by CBTRN03C.
     */
    private static final class TransactionReportReturnCodeDecider implements JobExecutionDecider {

        /** Narrows the implicit constructor: only the enclosing job builds this. */
        private TransactionReportReturnCodeDecider() {
        }

        /** {@inheritDoc} */
        @Override
        public FlowExecutionStatus decide(
                final JobExecution jobExecution,
                final StepExecution stepExecution) {

            final int returnCode = legacyReturnCode(jobExecution, stepExecution);
            return switch (returnCode) {
                case RETURN_CODE_COMPLETED ->
                        new FlowExecutionStatus(EXIT_CODE_COMPLETED);
                case RETURN_CODE_COMPLETED_WITH_REJECTS ->
                        new FlowExecutionStatus(EXIT_CODE_COMPLETED_WITH_REJECTS);
                case RETURN_CODE_ABEND ->
                        new FlowExecutionStatus(EXIT_CODE_ABEND);
                default ->
                        new FlowExecutionStatus(EXIT_CODE_FAILED);
            };
        }

        /**
         * Calculates the worst applicable legacy return code.
         *
         * @param jobExecution current job execution
         * @param stepExecution most recently completed step, or {@code null}
         * @return one of the legacy return codes 0, 4, 8 or 12
         */
        private int legacyReturnCode(
                final JobExecution jobExecution,
                final StepExecution stepExecution) {

            if (containsAbend(jobExecution, stepExecution)) {
                return RETURN_CODE_ABEND;
            }
            if (stepExecution == null) {
                return RETURN_CODE_FAILED;
            }
            final String exitCode = stepExecution.getExitStatus().getExitCode();
            if (EXIT_CODE_COMPLETED_WITH_REJECTS.equals(exitCode)) {
                return RETURN_CODE_COMPLETED_WITH_REJECTS;
            }
            if (stepExecution.getStatus().isUnsuccessful()
                    || EXIT_CODE_FAILED.equals(exitCode)) {
                return RETURN_CODE_FAILED;
            }
            return RETURN_CODE_COMPLETED;
        }

        /**
         * Detects a fatal type anywhere in the recorded cause chains.
         *
         * @param jobExecution current job execution
         * @param stepExecution most recently completed step, or {@code null}
         * @return {@code true} when any bounded cause chain contains a batch abend
         */
        private boolean containsAbend(
                final JobExecution jobExecution,
                final StepExecution stepExecution) {

            for (final Throwable failure : jobExecution.getAllFailureExceptions()) {
                if (containsFatalCause(failure)) {
                    return true;
                }
            }
            if (stepExecution != null) {
                for (final Throwable failure : stepExecution.getFailureExceptions()) {
                    if (containsFatalCause(failure)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Walks one bounded cause chain.
         *
         * @param failure first failure in the cause chain
         * @return {@code true} when a {@link FatalProcessingException} occurs within 32 links
         */
        private boolean containsFatalCause(final Throwable failure) {
            Throwable current = failure;
            int depth = 0;
            while (current != null && depth < 32) {
                if (current instanceof FatalProcessingException) {
                    return true;
                }
                final Throwable next = current.getCause();
                current = next == current ? null : next;
                depth++;
            }
            return false;
        }
    }

    /**
     * Fixed-width report emitter kept inside this job because no fourth writer file may exist.
     *
     * <p>Only byte framing lives here. Control breaks, page counters, first-time state and all three totals
     * remain in {@link TransactionReportProcessor}. One instance is created per STEP10R execution, so its
     * stream and line count are never shared.
     */
    private static final class FixedWidthReportItemWriter implements ItemStreamWriter<ReportLines> {

        /** Injected rather than constructed, so the emulator endpoint override reaches this writer too. */
        private final S3Operations objectStorage;

        /** Injected rather than constructed, so open, write and close report through one translation. */
        private final FileStatusMapper fileStatusMapper;

        /** Where {@code REPTFILE} of {@code app/jcl/TRANREPT.jcl} publishes, resolved once per step. */
        private final String outputBucket;

        /** Fixed when the step opened, so a retry rewrites the same generation rather than adding one. */
        private final String objectKey;

        /** Held open across the chunk boundary, and never shared: one stream per STEP10R execution. */
        private OutputStream output;

        /** Republished to the step context on every update, so a restart diagnostic reports progress. */
        private long linesWritten;

        /**
         * Creates one per-step writer.
         *
         * @param objectStorage object-storage abstraction
         * @param fileStatusMapper FILE STATUS translator
         * @param outputBucket destination bucket
         * @param objectKey concrete destination key
         */
        private FixedWidthReportItemWriter(
                final S3Operations objectStorage,
                final FileStatusMapper fileStatusMapper,
                final String outputBucket,
                final String objectKey) {

            this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage must not be null");
            this.fileStatusMapper =
                    Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
            this.outputBucket = Objects.requireNonNull(outputBucket, "outputBucket must not be null");
            this.objectKey = Objects.requireNonNull(objectKey, "objectKey must not be null");
        }

        /**
         * Opens one unblocked report-generation object.
         *
         * @param executionContext the step's context. Nothing is resumed from it: a report generation is
         *         written whole under a fresh key rather than appended to, so a restart re-opens rather
         *         than continues
         */
        @Override
        public void open(final ExecutionContext executionContext) {
            if (output != null) {
                throw fatal("REPORT OPEN REPEATED", "TRANREPT IS ALREADY OPEN", null);
            }
            try {
                final S3Resource resource = objectStorage.createResource(outputBucket, objectKey);
                resource.setObjectMetadata(ObjectMetadata.builder()
                        .contentType(OBJECT_CONTENT_TYPE)
                        .build());
                output = new BufferedOutputStream(resource.getOutputStream());
                linesWritten = 0L;
                fileStatusMapper.requireSuccess(STATUS_SUCCESS, DD_REPORT_OUTPUT, "OPEN");
            } catch (final CardDemoException typed) {
                throw typed;
            } catch (final IOException | RuntimeException cause) {
                throw ioFailure("OPEN", "ERROR OPENING REPTFILE", cause);
            }
        }

        /**
         * Spring Batch 5 writer contract. Every line is padded to exactly 133 characters, encoded with
         * ISO-8859-1, and written without a delimiter.
         *
         * @param chunk report-line groups
         */
        @Override
        public void write(final Chunk<? extends ReportLines> chunk) {
            Objects.requireNonNull(chunk, "chunk must not be null");
            if (chunk.isEmpty()) {
                return;
            }
            final OutputStream destination = output;
            if (destination == null) {
                throw fatal("REPORT NOT OPEN", "TRANREPT WRITE OCCURRED BEFORE OPEN", null);
            }
            try {
                for (final ReportLines group : chunk) {
                    if (group == null) {
                        throw fatal("REPORT GROUP MISSING", "TRANREPT CHUNK CONTAINS A NULL GROUP", null);
                    }
                    for (final String line : group.lines()) {
                        writeLine(destination, line);
                    }
                }
                fileStatusMapper.requireSuccess(STATUS_SUCCESS, DD_REPORT_OUTPUT, "WRITE");
            } catch (final CardDemoException typed) {
                throw typed;
            } catch (final IOException | RuntimeException cause) {
                throw ioFailure("WRITE", "ERROR WRITING REPTFILE", cause);
            }
        }

        /**
         * Publishes the current line count for restart diagnostics.
         *
         * @param executionContext the context to publish into; a {@code null} context is tolerated and
         *         publishes nothing
         */
        @Override
        public void update(final ExecutionContext executionContext) {
            if (executionContext != null) {
                executionContext.putLong(REPORT_LINE_COUNT_CONTEXT, linesWritten);
            }
        }

        /** Closes and commits the object. */
        @Override
        public void close() {
            final OutputStream destination = output;
            if (destination == null) {
                return;
            }
            output = null;
            try {
                destination.close();
                fileStatusMapper.requireSuccess(STATUS_SUCCESS, DD_REPORT_OUTPUT, "CLOSE");
            } catch (final CardDemoException typed) {
                throw typed;
            } catch (final IOException | RuntimeException cause) {
                throw ioFailure("CLOSE", "ERROR CLOSING REPORT FILE", cause);
            }
        }

        /**
         * Returns the committed or staged line count.
         *
         * @return number of 133-byte records successfully written
         */
        private long linesWritten() {
            return linesWritten;
        }

        /**
         * Writes one padded, one-byte-safe line.
         *
         * @param destination open report object stream
         * @param line report line to pad and encode
         * @throws IOException when the object stream rejects the encoded record
         */
        private void writeLine(final OutputStream destination, final String line) throws IOException {
            if (line == null) {
                throw fatal("REPORT LINE MISSING", "TRANREPT LINE IS NULL", null);
            }
            if (line.length() > REPORT_RECORD_LENGTH) {
                throw fatal("REPORT LINE TOO LONG", "TRANREPT LINE EXCEEDS 133 CHARACTERS", null);
            }
            // FINDING M-09, severity High, RESOLVED. This loop used to admit every code point through 0xFF,
            // which let a carriage return, a line feed or any other control byte into a 133-byte record.
            // app/proc/TRANREPT.prc declares DCB=(LRECL=133,RECFM=FB) - fixed blocks with no delimiter - so
            // the object stays well formed and still parses by byte count, which is precisely what makes the
            // injection dangerous: any reader that splits on newlines sees a record boundary the report does
            // not have, with attacker-chosen content after it. The permitted set is now the same strict one
            // alphanumeric() and every other fixed-width writer applies.
            for (int index = 0; index < line.length(); index++) {
                final char character = line.charAt(index);
                final boolean printableAscii = character >= 0x20 && character <= 0x7E;
                final boolean printableLatinOne = character >= 0xA0 && character <= 0xFF;
                if (!printableAscii && !printableLatinOne) {
                    // Position and code point only. The line carries cardholder-bearing fields, so the
                    // diagnostic names where the offending character is and never what the line says.
                    throw fatal("REPORT CHARACTER INVALID", String.format(Locale.ROOT,
                            "TRANREPT LINE CANNOT CARRY THE CHARACTER AT POSITION %d (CODE POINT %d)",
                            Integer.valueOf(index + 1), Integer.valueOf(character)), null);
                }
            }
            final String padded = line + " ".repeat(REPORT_RECORD_LENGTH - line.length());
            final byte[] encoded = padded.getBytes(FIXED_WIDTH_CHARSET);
            if (encoded.length != REPORT_RECORD_LENGTH) {
                throw fatal("REPORT LENGTH INVALID",
                        "TRANREPT LINE IS NOT 133 BYTES AFTER ENCODING", null);
            }
            destination.write(encoded);
            linesWritten++;
        }

        /**
         * Maps an object-store failure through FILE STATUS and preserves the mapped cause.
         *
         * @param operation failed I/O operation
         * @param message source-compatible diagnostic message
         * @param cause original failure
         * @return typed batch abend preserving the mapped or original cause
         */
        private FatalProcessingException ioFailure(
                final String operation,
                final String message,
                final Throwable cause) {

            try {
                fileStatusMapper.requireSuccess(
                        STATUS_IO_ERROR, DD_REPORT_OUTPUT, operation, cause);
            } catch (final CardDemoException mapped) {
                LOG.error(message);
                LOG.error(fileStatusMapper.displayIoStatus(STATUS_IO_ERROR));
                return fatal(operation + " FAILED", message, mapped);
            }
            return fatal(operation + " FAILED", message, cause);
        }

        /**
         * Constructs a batch abend without any mutable global state.
         *
         * @param reason concise abend reason
         * @param message source-compatible diagnostic message
         * @param cause original or mapped failure
         * @return typed batch abend with code 999 and return code 12
         */
        private FatalProcessingException fatal(
                final String reason,
                final String message,
                final Throwable cause) {

            return new FatalProcessingException(
                    ABEND_CODE, ABEND_CULPRIT, reason, message, cause);
        }
    }
}
