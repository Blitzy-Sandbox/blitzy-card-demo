/*
 * ******************************************************************
 * Program     : DailyTransactionPostingJob.java
 * Application : CardDemo
 * Type        : Spring Batch Job Configuration
 * Function    : Daily transaction posting - validation cascade, reject
 *               engine and atomic three-write posting.
 * Source      : app/jcl/POSTTRAN.jcl + app/cbl/CBTRN02C.cbl (731 lines,
 *               26 own paragraph labels) + app/cbl/CBTRN01C.cbl (491 lines,
 *               18 own paragraph labels) @ 7756d89
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.support.TransactionTemplate;

import com.cardemo.batch.processors.TransactionPostingProcessor;
import com.cardemo.batch.readers.DailyTransactionReader;
import com.cardemo.batch.writers.RejectWriter;
import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * The daily transaction posting job: the whole of {@code app/jcl/POSTTRAN.jcl}, whose single step
 * {@code //STEP15 EXEC PGM=CBTRN02C} at {@code app/jcl/POSTTRAN.jcl:L23} runs
 * {@code app/cbl/CBTRN02C.cbl}, with {@code app/cbl/CBTRN01C.cbl} folded in ahead of it as an explicitly
 * labelled <strong>read-only pre-flight step</strong>.
 *
 * <h2>What it does</h2>
 *
 * <p>Two steps, gated by a decider that maps every outcome onto the four legacy return codes:
 *
 * <ol>
 *   <li>{@link #dailyTransactionPostingPreFlightStep(DailyTransactionReader)} - the diagnostic pass of
 *       {@code app/cbl/CBTRN01C.cbl}. It opens six datasets, reads, and closes six datasets.
 *       <strong>It writes nothing at all</strong>, and its business work runs in a nested read-only
 *       transaction declared by {@link #readOnlyTransactionAttribute()} so the infrastructure enforces that
 *       rather than the code merely asserting it. The step's own chunk transaction stays writable because
 *       the framework persists the step execution context inside it.</li>
 *   <li>{@link #dailyTransactionPostingStep(DailyTransactionReader, TransactionPostingProcessor,
 *       TransactionWriter, RejectWriter)} - the chunk-oriented realisation of the mainline loop at
 *       {@code app/cbl/CBTRN02C.cbl:L202}-{@code :L219}. Each record is validated and then either posted or
 *       rejected, never both.</li>
 * </ol>
 *
 * <h2>Inputs</h2>
 *
 * <p>The six data DDs of {@code app/jcl/POSTTRAN.jcl:L28}-{@code :L42}, in the member's own order:
 * {@code TRANFILE}, {@code DALYTRAN}, {@code XREFFILE}, {@code DALYREJS}, {@code ACCTFILE} and
 * {@code TCATBALF}. The driving input is {@code DALYTRAN}, read by {@link DailyTransactionReader}; the
 * remaining five are reached through the repositories and the two writers.
 *
 * <p>Job parameters: <strong>none are required.</strong> {@code app/jcl/POSTTRAN.jcl} carries no
 * {@code PARM=} on its {@code EXEC} card at {@code :L23}, so no job parameter has a legacy contract to
 * reproduce. {@link PostTranParametersValidator} therefore <em>hardens</em> whatever a launcher supplies
 * rather than specifying a contract the source does not have.
 *
 * <h2>Outputs and side effects</h2>
 *
 * <ul>
 *   <li>Rows inserted into the transaction table and updated in the account and
 *       transaction-category-balance tables - all three inside one transaction, see the atomicity deviation
 *       below.</li>
 *   <li>One object-storage generation per chunk of posted transactions, written by {@link TransactionWriter}
 *       at exactly {@value TransactionWriter#RECORD_LENGTH} bytes per record.</li>
 *   <li>One {@code DALYREJS} generation per chunk that contains at least one reject, written by
 *       {@link RejectWriter} at exactly {@value RejectCode#REJECT_RECORD_LENGTH} bytes per record.</li>
 *   <li>The concrete object keys both writers created, promoted from the step execution context into the
 *       job execution context by {@link #promoteGenerationKeys(StepExecution)} so that a caller reads the
 *       key that <em>was</em> created instead of re-resolving a "latest" generation.</li>
 *   <li>Two diagnostic context entries, established and restored by {@link PostTranJobListener}.</li>
 *   <li>One increment of the existing {@code records processed} counter per chunk that contains a reject, so
 *       that the meter matches {@code WS-TRANSACTION-COUNT} at {@code app/cbl/CBTRN02C.cbl:L206}, which is
 *       incremented before validation and therefore counts rejected records too. See
 *       {@link #countRejectedRecordsAsProcessed(int)}; <strong>no new meter is defined here.</strong></li>
 *   <li>Log events only - <strong>this job never terminates the virtual machine.</strong> There is no
 *       explicit termination call of any kind, and no shutdown hook, anywhere in this class. It reports
 *       status; the launcher decides what to do with it.</li>
 * </ul>
 *
 * <h2>The exit-status contract - the heart of this class</h2>
 *
 * <p>{@code app/cbl/CBTRN02C.cbl:L229}-{@code :L231} is the entire rule:
 *
 * <pre>
 * IF WS-REJECT-COUNT &gt; 0
 *    MOVE 4 TO RETURN-CODE
 * END-IF
 * </pre>
 *
 * <p>That is the <strong>only</strong> numeric-literal {@code RETURN-CODE} assignment in the corpus; the
 * only other {@code TO RETURN-CODE} site anywhere is the variable move at
 * {@code app/cbl/CSUTLDTC.cbl:L98}. So the mapping is:
 *
 * <table border="1">
 *   <caption>Return-code mapping and its evidence</caption>
 *   <tr><th>RC</th><th>Meaning</th><th>Condition</th><th>Evidence</th></tr>
 *   <tr><td>0</td><td>completed</td><td>reject count is zero</td>
 *       <td>the fall-through of {@code :L229}</td></tr>
 *   <tr><td>4</td><td>completed with rejects</td><td>reject count is greater than zero, and nothing else
 *       </td><td>{@code app/cbl/CBTRN02C.cbl:L229}-{@code :L231}</td></tr>
 *   <tr><td>8</td><td>failed</td><td>an unsuccessful step with no abend recorded</td>
 *       <td>the conventional job-control failure code; no COBOL literal exists</td></tr>
 *   <tr><td>12</td><td>abend</td><td>a {@link FatalProcessingException} was recorded</td>
 *       <td>the conventional language-environment consequence of the {@code CALL 'CEE3ABD'} at
 *       {@code app/cbl/CBTRN02C.cbl:L711}. <strong>There is no {@code MOVE 12 TO RETURN-CODE} anywhere in
 *       the corpus and none is invented here.</strong></td></tr>
 * </table>
 *
 * <p><strong>RC 4 is not a failure</strong>, and RC 4 and RC 12 are independent paths: a run may reject
 * records and still complete, and a run may abend having rejected none.
 * {@link PostTranReturnCodeDecider} covers all four outcomes, which is the named test obligation under
 * Rule 1 clause B.
 *
 * <p>Reject codes <strong>drive</strong> the exit status and are never thrown. There are exactly five, and
 * {@link RejectCode} owns them.
 *
 * <h2>Deviation 1 - one atomic unit instead of three independent commits</h2>
 *
 * <p><strong>This is a genuine behavioural improvement, not parity, and it is labelled as a
 * deviation rather than presented as equivalence.</strong>
 *
 * <p>{@code app/cbl/CBTRN02C.cbl:L440}-{@code :L442} performs, in this exact order:
 *
 * <pre>
 * PERFORM 2700-UPDATE-TCATBAL
 * PERFORM 2800-UPDATE-ACCOUNT-REC
 * PERFORM 2900-WRITE-TRANSACTION-FILE
 * </pre>
 *
 * <p>On the mainframe those are three independent commits, so the rewrite-failure path inside
 * {@code 2800-UPDATE-ACCOUNT-REC} leaves an orphaned category-balance row and an orphaned transaction row.
 * Here the <strong>order is preserved exactly</strong> - {@link TransactionPostingProcessor} performs 2700
 * then 2800, and {@link TransactionWriter} performs 2900 - but the chunk transaction this class establishes
 * through {@code chunk(POSTING_COMMIT_INTERVAL, transactionManager)} makes all three <strong>one atomic
 * unit</strong>, so the orphan hazard cannot occur. The interval is <strong>one record</strong>, which is what
 * keeps the unit of work exactly as wide as the source's own: a failure on one record rolls back that record
 * and no other, and every record already processed is already durable. Held, with the locator above, as
 * {@code DL-DV-01} in {@code DECISION_LOG.md}.
 *
 * <h2>Deviation 2 - the pre-flight adds two datasets POSTTRAN never supplied</h2>
 *
 * <p><strong>Documented rather than silently absorbed, and the two datasets are
 * deliberately not dropped to hide it.</strong>
 *
 * <p>{@code app/cbl/CBTRN01C.cbl:L28}-{@code :L60} declares six DD names: {@code DALYTRAN},
 * {@code CUSTFILE}, {@code XREFFILE}, {@code CARDFILE}, {@code ACCTFILE} and {@code TRANFILE}.
 * {@code app/jcl/POSTTRAN.jcl:L28}-{@code :L42} supplies a different six: {@code TRANFILE},
 * {@code DALYTRAN}, {@code XREFFILE}, {@code DALYREJS}, {@code ACCTFILE} and {@code TCATBALF}. So
 * <strong>{@code CARDFILE} and {@code CUSTFILE} are not supplied by {@code POSTTRAN.jcl}</strong>, and
 * {@code DALYREJS} and {@code TCATBALF} are not consumed by {@code CBTRN01C}.
 *
 * <p>Folding the pre-flight in therefore introduces two dependencies the legacy {@code POSTTRAN} job stream
 * does not have: {@link CardRepository} and {@link CustomerRepository}. They are declared as injected
 * collaborators and justified here rather than removed, because dropping the two datasets would make the
 * pre-flight a partial reproduction of {@code CBTRN01C} while looking like a complete one. The reason the
 * pre-flight is folded in at all is that {@code CBTRN01C} has <strong>no JCL member anywhere in the corpus
 * that runs it</strong> - a standalone job would be an invention - and its verb inventory makes it
 * incapable of writing: {@code WRITE} 0, {@code REWRITE} 0, {@code DELETE} 0, against six {@code OPEN} and
 * six {@code CLOSE} statements. The program is nonetheless part of the migrated scope, which is why its
 * paragraphs are cited on the pre-flight methods below even though no JCL member runs it.
 *
 * <h2>Preserved quirks - parity governs over clause B</h2>
 *
 * <p>Rule 1 clause B forbids dead code. Parity requires that reachable no-ops and unreachable assignments
 * survive so the paragraph map stays provable. <strong>Parity governs</strong>, because clause B forbids
 * <em>untracked</em> dead code and deferred work without an owner or tracking reference, and every artefact below is
 * cited, tracked and marked intentional.
 *
 * <ul>
 *   <li><strong>Reject code 109 is assigned but never consumed.</strong> The
 *       control-flow proof, from disk: it is assigned only inside {@code 2800-UPDATE-ACCOUNT-REC} on the
 *       {@code INVALID KEY} arm of the {@code REWRITE} at {@code app/cbl/CBTRN02C.cbl:L556}-{@code :L558};
 *       that paragraph is performed only from {@code 2000-POST-TRANSACTION} at {@code :L441}; and
 *       {@code 2000-POST-TRANSACTION} is performed only from the {@code IF} arm at {@code :L211}-{@code
 *       :L212}, which is entered only when the reason code is already zero. The reject write and the reject
 *       count live exclusively in the {@code ELSE} arm at {@code :L213}-{@code :L215}, which is entered only
 *       when the reason code is non-zero <em>on entry</em>. The paragraph then falls through its plain
 *       {@code EXIT} at {@code :L560} to {@code 2900-WRITE-TRANSACTION-FILE}, and the value is cleared by
 *       {@code MOVE 0 TO WS-VALIDATION-FAIL-REASON} at {@code :L208} on the next iteration. Therefore
 *       <strong>no reject record bearing 109 is ever written and the reject count is never incremented for
 *       it</strong>, and this job adds no reject path for it. The constant still exists because the
 *       assignment is real code on a reachable path.</li>
 *   <li><strong>{@code CBTRN02C} has no final-flush construct at all.</strong> Neither the
 *       outer {@code IF} at {@code app/cbl/CBTRN02C.cbl:L203} nor the inner {@code IF} at {@code :L205} has
 *       an {@code ELSE}, so there is nothing to flush after the loop - the exact contrast with
 *       {@code app/cbl/CBACT04C.cbl}, which does have one. No end-of-data flush is added here, because
 *       adding one would invent behaviour.</li>
 *   <li><strong>The commented-out {@code DISPLAY DALYTRAN-RECORD} at {@code app/cbl/CBTRN02C.cbl:L207} is
 *       noted and deliberately not reproduced as code.</strong> It is a comment in the
 *       source, so reproducing it as an executable statement would add an emission the source does not
 *       make - and one that would put a full card number into the log.</li>
 *   <li><strong>The scoped {@code FILE STATUS} leniency at {@code app/cbl/CBTRN02C.cbl:L481} is preserved.
 *</strong> {@code IF  TCATBALF-STATUS = '00'  OR '23'} - note the two spaces the
 *       source carries - makes {@code 2700-UPDATE-TCATBAL} an upsert in which a record-not-found status is
 *       <em>success</em>, one of only three such sites tree-wide. It is enforced by
 *       {@link FileStatusMapper#requireCategoryBalanceReadSuccess(String)} and consumed by
 *       {@link TransactionPostingProcessor}; this class must not, and does not, re-implement it.</li>
 *   <li><strong>The unguarded sequential validation checks are preserved.</strong>
 *       {@code app/cbl/CBTRN02C.cbl:L407}-{@code :L420} tests the credit limit and then, with no
 *       intervening guard or early exit, tests the expiry date - so when both fail, code 103 overwrites
 *       code 102 and a single reject bearing 103 is written. Owned by
 *       {@link TransactionPostingProcessor}; this class neither guards it nor emits two rejects.</li>
 * </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <table border="1">
 *   <caption>Properties this class binds</caption>
 *   <tr><th>Property</th><th>Default</th><th>Purpose</th></tr>
 *   <tr><td>{@code carddemo.batch.jobs.posttran.name}</td><td>{@value #DEFAULT_JOB_NAME}</td>
 *       <td>The registered job name</td></tr>
 *   <tr><td>{@code carddemo.batch.posttran.chunk-size}</td><td>{@code carddemo.batch.chunk-size}, then
 *       {@value #DEFAULT_CHUNK_SIZE}</td><td>Commit interval. A tunable, <em>not</em> a parity contract:
 *       the source commits per record because it has no chunk concept at all</td></tr>
 * </table>
 *
 * <p>The nested placeholder is deliberate. {@code src/main/resources/application.yml} declares
 * {@code carddemo.batch.chunk-size} but <em>not</em> {@code carddemo.batch.posttran.chunk-size}, so the
 * job-specific key wins when present and the shared key is the fallback - the job is tunable in isolation
 * without forcing a property the configuration does not currently declare.
 *
 * <p>Bucket names and generation prefixes are <strong>not</strong> bound here. They belong to
 * {@link TransactionWriter} and {@link RejectWriter}, which read {@code carddemo.aws.s3.batch-output-bucket},
 * {@code carddemo.aws.s3.transaction-object-prefix} and {@code carddemo.aws.s3.gdg-prefixes.daly-rejs} from
 * {@code src/main/resources/application.yml}. Binding them again here would duplicate configuration and
 * create a second place for the two to drift apart. <strong>The namespace is
 * {@code carddemo.aws.s3.*}</strong> - verified against {@code src/main/resources/application.yml}, which
 * declares it under {@code carddemo: aws: s3:}; a {@code carddemo.s3.*} spelling without the {@code aws}
 * segment does not exist.
 * No AWS client is constructed here, no bucket or endpoint is hardcoded, and the process environment is never
 * read directly - every value arrives through Spring property binding.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Build and gate: {@code ./mvnw -B -ntp clean verify}. No job runs at startup, because
 * {@code src/main/resources/application.yml} sets {@code spring.batch.job.enabled: false}; launch this one
 * by name through the {@code JobLauncher}, resolving it as {@value #JOB_BEAN_NAME}. The four bean-name
 * constants and the two counter context keys on this class are {@code public} precisely so that
 * {@code src/test/java/com/cardemo/integration/batch} can assert the topology and the return-code outcomes
 * without this folder contributing a test-support file of its own.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <table border="1">
 *   <caption>Failure modes</caption>
 *   <tr><th>Symptom</th><th>Cause</th><th>Remedy</th></tr>
 *   <tr><td>Startup fails naming a duplicate bean</td><td>Another configuration declared one of this
 *       class's four bean names; {@code application.yml} sets
 *       {@code spring.main.allow-bean-definition-overriding: false}, so this is fatal rather than silent
 *       </td><td>Keep the {@code dailyTransactionPosting} prefix unique. This class
 *       declares only {@link Job}, {@link Step} and {@link Flow} beans and redeclares no
 *       infrastructure</td></tr>
 *   <tr><td>Job ends {@code COMPLETED} when records were rejected</td><td>The reject count never reached the
 *       step execution context</td><td>{@link PostTranReturnCodeDecider} reads
 *       {@value #REJECT_COUNT_CONTEXT_ENTRY} as the authoritative signal; confirm the composite writer
 *       incremented it</td></tr>
 *   <tr><td>Exit status is {@code FAILED} where an abend was expected</td><td>The failure was not a
 *       {@link FatalProcessingException}</td><td>Only that type maps to RC 12; every other
 *       failure is RC 8, deliberately</td></tr>
 *   <tr><td>A reject object is not a whole multiple of {@value RejectCode#REJECT_RECORD_LENGTH} bytes</td>
 *       <td>Record geometry changed</td><td>Geometry belongs to {@link RejectWriter}; this
 *       class configures nothing that can change a record width</td></tr>
 *   <tr><td>The pre-flight step abends immediately</td><td>One of its six datasets is unreachable</td>
 *       <td>The abend names the DD it failed on, in the source's own open order</td></tr>
 * </table>
 *
 * <h2>Not available - stated plainly rather than guessed, per clause F</h2>
 *
 * <ul>
 *   <li><strong>The {@code DALYTRAN} record length is not available from the job control.</strong>
 *       {@code app/jcl/POSTTRAN.jcl:L30}-{@code :L31} carries no {@code DCB=}, no {@code LRECL} and no
 *       {@code RECFM} at all, and {@code DALYTRAN.PS} has no catalogued record length. The 350-byte
 *       geometry rests on the record layout in {@code app/cpy/CVTRA06Y.cpy}, not on the JCL. Contrast
 *       {@code DALYREJS}, whose {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} at {@code :L36} does state it
 *       explicitly - and note {@code RECFM=F} is fixed <em>unblocked</em>, unlike the {@code RECFM=FB} of
 *       every sort and copy output in the corpus.</li>
 *   <li><strong>The object-storage record-framing convention is not available.</strong> Nothing in the
 *       corpus says whether a consumer of a migrated generation expects newline-delimited records or a
 *       bare fixed-width stream, because the mainframe carried record boundaries out of band in the
 *       dataset attributes. The two writers own that decision; this class does not restate it.</li>
 *   <li><strong>The generation-prefix mapping for the posted-transaction object is not available.</strong>
 *       {@code app/jcl/POSTTRAN.jcl:L28}-{@code :L29} names {@code TRANFILE} as an existing KSDS with
 *       {@code DISP=SHR}, not a generation data group, so no GDG base corresponds to it. The prefix
 *       {@link TransactionWriter} uses is a target-side choice, not a translated one.</li>
 *   <li><strong>Container-runtime dependent gates cannot be asserted from source inspection.</strong>
 *       Gates 1, 4 and 8 need a reachable container daemon; where one is absent the evidence must record
 *       the prerequisite rather than claim a pass. No fabricated pass appears in this file.</li>
 * </ul>
 *
 * <p>One further asymmetry is noted and deliberately not "fixed": the six generation-data-group definitions
 * in {@code app/jcl/DEFGDGB.jcl:L24}-{@code :L59} are each followed by
 * {@code IF LASTCC=12 THEN SET MAXCC=0}, the idempotent-provisioning idiom, whereas the seventh base -
 * {@code DALYREJS}, this job's reject output, defined at {@code app/jcl/DALYREJS.jcl:L24}-{@code :L28} with
 * the same {@code LIMIT(5) SCRATCH} - carries no such guard. The asymmetry is real in the source and is
 * recorded, not repaired.
 *
 * <h2>Thread safety and state</h2>
 *
 * <p>Every field is {@code final} and set by the sole constructor, so an instance cannot be half-configured.
 * There is <strong>no static mutable state</strong>: the only static members are the logger, immutable
 * constants, and one {@link ThreadLocal} whose value is a thread-confined stack of immutable records - one
 * entry per in-progress execution on that thread, so nested launches nest rather than overwrite each other.
 * Per-execution counters live in the step execution context, reached through
 * {@link StepSynchronizationManager}, which is thread-bound and therefore correct when several executions
 * share a pool.
 *
 * @see TransactionPostingProcessor for the validation cascade and paragraphs 1500 through 2800
 * @see TransactionWriter for paragraph 2900 and the 350-byte record geometry
 * @see RejectWriter for paragraph 2500 and the 430-byte reject geometry
 * @see RejectCode for the five reject outcomes
 */
@Configuration(DailyTransactionPostingJob.CONFIGURATION_BEAN_NAME)
public class DailyTransactionPostingJob {

    /**
     * Bean name of this configuration class itself.
     *
     * <p>Named explicitly rather than left to the container's default so that it cannot collide with
     * {@code com.cardemo.config.BatchConfig} or any other configuration, which matters because
     * {@code src/main/resources/application.yml} sets
     * {@code spring.main.allow-bean-definition-overriding: false} and so turns a collision into a startup
     * failure rather than a silent replacement.
     */
    static final String CONFIGURATION_BEAN_NAME = "dailyTransactionPostingJobConfiguration";

    /**
     * Bean name of the read-only pre-flight step, {@code app/cbl/CBTRN01C.cbl}.
     *
     * <p>{@code public} so the integration tier can resolve the step by name and assert that it performs no
     * write, without this folder contributing a test-support file.
     */
    public static final String PRE_FLIGHT_STEP_BEAN_NAME = "dailyTransactionPostingPreFlightStep";

    /** Bean name of the chunk-oriented posting step, {@code app/cbl/CBTRN02C.cbl}. */
    public static final String POSTING_STEP_BEAN_NAME = "dailyTransactionPostingStep";

    /** Bean name of the gated flow that routes every step outcome through the return-code decider. */
    public static final String FLOW_BEAN_NAME = "dailyTransactionPostingFlow";

    /** Bean name of the job, the whole of {@code app/jcl/POSTTRAN.jcl}. */
    public static final String JOB_BEAN_NAME = "dailyTransactionPostingJob";

    /**
     * Step execution context key holding {@code WS-TRANSACTION-COUNT}, declared
     * {@code PIC 9(09) VALUE 0} at {@code app/cbl/CBTRN02C.cbl:L185} and incremented at {@code :L206}.
     *
     * <p>{@code public} so a test can assert the counter without re-deriving it.
     */
    public static final String PROCESSED_COUNT_CONTEXT_ENTRY = "carddemo.posttran.transaction.count";

    /**
     * Step execution context key holding {@code WS-REJECT-COUNT}, declared {@code PIC 9(09) VALUE 0} at
     * {@code app/cbl/CBTRN02C.cbl:L186} and incremented at {@code :L214}.
     *
     * <p>This is the <strong>authoritative</strong> return-code-4 signal: {@link PostTranReturnCodeDecider}
     * reads this entry rather than the step's exit code. The reason is that
     * {@link ExitStatus#and(ExitStatus)} resolves two statuses of equal severity by comparing their exit
     * <em>code strings</em> lexicographically, and {@code "COMPLETED WITH REJECTS"} survives a merge with
     * {@code "COMPLETED"} only because it happens to sort after it - measured, not assumed:
     * {@code ExitStatus.COMPLETED.compareTo(new ExitStatus("COMPLETED WITH REJECTS"))} is {@code -13}. That
     * is an incidental property of the two names rather than a documented contract, so a future rename could
     * silently invert it. Deriving the outcome from the count instead makes return code 4 independent of the
     * tie-break entirely.
     */
    public static final String REJECT_COUNT_CONTEXT_ENTRY = "carddemo.posttran.reject.count";

    /**
     * Structured log sink for this class.
     *
     * <p>Replaces the {@code DISPLAY} statements of {@code app/cbl/CBTRN02C.cbl} and
     * {@code app/cbl/CBTRN01C.cbl}, which together with the four-character status renderer were the only
     * instrumentation either program had.
     *
     * <p>The run boundary markers, the two end-of-run counters and the failure diagnostics travel on this
     * logger, none of them carrying a card number, an account identifier, a transaction identifier, an
     * amount or any personal field. The <em>record-image</em> emissions of the pre-flight, and every
     * emission that names an identifier at all, go to {@link #PARITY_LOG} instead and never to this logger;
     * where an identifier would have located a record, a bounded ordinal does so instead - see
     * {@link #MSG_PRE_FLIGHT_ACCOUNT_NOT_FOUND}.
     */
    private static final Logger LOG = LoggerFactory.getLogger(DailyTransactionPostingJob.class);

    /**
     * Name of the isolated parity-output logger for the pre-flight program.
     *
     * <p>Suffixed with the program name rather than the class name, so parity output can be enabled for one
     * legacy program at a time rather than for the whole {@code com.cardemo.parity} tree. See
     * {@link #PARITY_LOG} for why the tree exists at all.
     */
    private static final String PARITY_LOGGER_NAME = "com.cardemo.parity.CBTRN01C";

    /**
     * Sink for the pre-flight's record-image emissions.
     *
     * <p><strong>Why this exists.</strong> {@code app/cbl/CBTRN01C.cbl:L168} is an
     * <em>uncommented</em> {@code DISPLAY DALYTRAN-RECORD}, {@code :L177}-{@code :L179} displays a full
     * {@code ACCT-ID} and {@code :L181}-{@code :L183} displays a full {@code DALYTRAN-CARD-NUM}. Deleting
     * them would break the paragraph map that the coverage gate reads; emitting them on the application
     * logger would put an eleven-digit account identifier and a sixteen-digit card number into the
     * operational log stream. So they travel here, on {@value #PARITY_LOGGER_NAME}, which
     * {@code src/main/resources/application.yml} and {@code src/main/resources/logback-spring.xml} both pin
     * to {@code OFF} for the whole {@code com.cardemo.parity} tree, in every shipped profile. No deployment
     * emits them, and every emission is additionally guarded by a level check and carries a
     * {@linkplain #maskCardNumber(String) masked} card number or a
     * {@linkplain #maskAccountIdentifier(Long) masked} account identifier rather than the raw value - so
     * each value is protected twice over, by routing and by redaction.
     *
     * <p>The same two-layer rule covers account and customer identifiers, through
     * {@link #maskIdentifier(Long)}. Routing alone would not be enough for them: this tree is pinned
     * {@code OFF} by configuration, and configuration is exactly the kind of thing an operator turns on to
     * diagnose a live incident. Masking is what makes that safe to do.
     *
     * <p>This is deliberately <em>not</em> the same situation as
     * {@code app/cbl/CBTRN02C.cbl:L207}, where the equivalent {@code DISPLAY DALYTRAN-RECORD} is commented
     * out in the source and is therefore not reproduced as code at all.
     */
    private static final Logger PARITY_LOG = LoggerFactory.getLogger(PARITY_LOGGER_NAME);

    /**
     * Number of trailing digits of a card number that may appear in a diagnostic.
     *
     * <p>Four is the conventional last-four projection. The remaining twelve are replaced, so no emission
     * anywhere in this class can reconstruct a primary account number.
     */
    private static final int CARD_NUMBER_VISIBLE_SUFFIX = 4;

    /** Replacement character for every masked digit of a card number. */
    private static final char CARD_NUMBER_MASK = '*';

    /**
     * Number of trailing digits of an account or customer identifier that may appear in a diagnostic.
     *
     * <p>Matches {@link #CARD_NUMBER_VISIBLE_SUFFIX} in value but is a separate constant because it answers
     * a separate question. Four trailing digits of an eleven-digit account number are enough to correlate
     * two log lines and not enough to quote the account, and the two projections must be free to diverge
     * without one silently changing the other.
     */
    private static final int IDENTIFIER_VISIBLE_SUFFIX = 4;

    /**
     * Number of trailing digits of an account identifier that may appear in a diagnostic.
     *
     * <p><strong>Finding, severity Medium, resolved - CWE-532.</strong> {@code ACCT-ID} is
     * {@code PIC 9(11)} and the pre-flight's {@code DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'} at
     * {@code app/cbl/CBTRN01C.cbl:L177}-{@code :L179} published it in full. Reproduced literally on the
     * application logger it put a complete account identifier into the operational log stream at
     * {@code WARN}, which is enabled in every deployment - so the identifier travelled wherever the log
     * travelled. Two controls now apply together, and the reasoning is the same as for a card number: the
     * identifier-bearing reproduction moved to {@link #PARITY_LOG}, which every shipped profile pins to
     * {@code OFF}, <em>and</em> the value it carries is reduced to this suffix. The operational
     * {@code WARN} that an operator actually reads carries no identifier at all - only the logical dataset
     * names and the record's ordinal within the file, which is what locates the record without disclosing
     * whose it is.
     *
     * <p>Four rather than the whole value, and four rather than none: a bounded suffix lets two
     * diagnostics about the same account be recognised as such while leaving the remaining seven digits -
     * ten million possibilities - unstated.
     */
    private static final int ACCOUNT_ID_VISIBLE_SUFFIX = 4;

    /** Default job name, matching {@code carddemo.batch.jobs.posttran.name} and the JCL member name. */
    private static final String DEFAULT_JOB_NAME = "POSTTRAN";

    /**
     * Default bounded read window when neither the job-specific nor the shared chunk-size property is set.
     *
     * <p>A tunable rather than a parity contract: {@code app/cbl/CBTRN02C.cbl} has no chunk concept at all,
     * so no source value is being reproduced or contradicted. It bounds how many staged rows the pre-flight
     * resolves per set-based lookup; it is <strong>not</strong> the commit interval - see
     * {@link #POSTING_COMMIT_INTERVAL}.
     */
    private static final int DEFAULT_CHUNK_SIZE = 100;

    /**
     * The commit interval of the posting step, pinned at one record.
     *
     * <p><strong>BLOCKER, remediated here. This value is a parity contract, not a tunable, which is why it is
     * a constant and not a property.</strong> Spring Batch makes the chunk the JDBC transaction boundary, so
     * a commit interval of {@value #DEFAULT_CHUNK_SIZE} meant that a failure while posting record 100 rolled
     * back records 1 through 99 as well. {@code app/cbl/CBTRN02C.cbl} cannot behave that way: its mainline at
     * {@code :L202}-{@code :L219} handles one record per iteration and its three writes at
     * {@code :L440}-{@code :L442} are three <em>separate</em> commits, so every record that has already been
     * processed is already durable when the next one fails. A batch-wide rollback is therefore not a
     * conservative choice but a different behaviour, and one that would make a single defective record
     * discard up to ninety-nine good ones.
     *
     * <p>One record per transaction reproduces the source's guarantee on both sides. Prior records stay
     * committed, exactly as they do on the mainframe. And the three writes of one record - paragraph
     * {@code 2700-UPDATE-TCATBAL}, {@code 2800-UPDATE-ACCOUNT-REC} and
     * {@code 2900-WRITE-TRANSACTION-FILE} - remain inside one unit of work, which is deviation 1 as
     * documented on this class: the source's own failure path inside {@code 2800} leaves an orphaned
     * category-balance row and an orphaned transaction row, and one transaction per record closes that
     * hazard without widening the blast radius past the record that caused it.
     *
     * <p>The cost is one commit per record rather than one per hundred. That is the price of the atomicity
     * contract and it is paid deliberately; the read path stays bounded and batched independently through
     * {@link #chunkSize}, and the object-storage output stays aggregated because
     * {@link TransactionWriter} buffers committed records and emits one object per block rather than one per
     * transaction.
     */
    private static final int POSTING_COMMIT_INTERVAL = 1;

    /** Return code 0. The normal outcome: every record either posted or the file was empty. */
    private static final String EXIT_CODE_COMPLETED = ExitStatus.COMPLETED.getExitCode();

    /**
     * Return code 4, from {@code MOVE 4 TO RETURN-CODE} at {@code app/cbl/CBTRN02C.cbl:L230}.
     *
     * <p><strong>Not a failure.</strong> The run completed; some records were rejected and written to
     * {@code DALYREJS}. Deliberately prefixed {@code COMPLETED} so that a caller unaware of this code still
     * reads the run as successful.
     */
    private static final String EXIT_CODE_COMPLETED_WITH_REJECTS = "COMPLETED WITH REJECTS";

    /** Return code 8: the step failed for a reason that is not an abend. */
    private static final String EXIT_CODE_FAILED = ExitStatus.FAILED.getExitCode();

    /**
     * Return code 12: the abend path.
     *
     * <p>The conventional language-environment consequence of {@code CALL 'CEE3ABD'} at
     * {@code app/cbl/CBTRN02C.cbl:L711}. No {@code MOVE 12 TO RETURN-CODE} exists in the corpus, so no
     * locator is fabricated for the literal.
     */
    private static final String EXIT_CODE_ABEND = "ABEND";

    /** Wildcard transition pattern, so no step outcome can leave the flow without reaching the decider. */
    private static final String EXIT_CODE_ANY = "*";

    /**
     * Exit status published on the job when an abend was recorded, naming the abend code and the return
     * code so the two appear together in the batch metadata.
     */
    private static final ExitStatus ABEND_EXIT_STATUS = new ExitStatus(EXIT_CODE_ABEND,
            "abend code " + FatalProcessingException.BATCH_ABEND_CODE
                    + ", return code " + FatalProcessingException.BATCH_RETURN_CODE);

    /** Exit status published on the posting step when the reject count exceeded zero. */
    private static final ExitStatus COMPLETED_WITH_REJECTS_EXIT_STATUS =
            new ExitStatus(EXIT_CODE_COMPLETED_WITH_REJECTS,
                    "app/cbl/CBTRN02C.cbl:L229-L231 - reject count greater than zero");

    /**
     * Abend code carried by every {@link FatalProcessingException} this class raises, from
     * {@code MOVE 999 TO ABCODE} at {@code app/cbl/CBTRN02C.cbl:L710}.
     *
     * <p><strong>999, and never the four-digit variant.</strong> That four-digit value is the CICS online
     * code and belongs to a
     * different code path; conflating them would misreport the failure.
     */
    private static final String ABEND_CODE = String.valueOf(FatalProcessingException.BATCH_ABEND_CODE);

    /** Abend culprit for the posting step, {@code ABEND-CULPRIT X(8)} sized and exactly eight characters. */
    private static final String ABEND_CULPRIT_POSTING = "CBTRN02C";

    /** Abend culprit for the read-only pre-flight step. Also exactly eight characters. */
    private static final String ABEND_CULPRIT_PRE_FLIGHT = "CBTRN01C";

    /** {@code DISPLAY 'ABENDING PROGRAM'} at {@code app/cbl/CBTRN02C.cbl:L708}. */
    private static final String MSG_ABENDING_PROGRAM = "ABENDING PROGRAM";

    /** {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN02C'} at {@code app/cbl/CBTRN02C.cbl:L194}. */
    private static final String MSG_START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM CBTRN02C";

    /** {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN02C'} at {@code app/cbl/CBTRN02C.cbl:L232}. */
    private static final String MSG_END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM CBTRN02C";

    /**
     * {@code DISPLAY 'TRANSACTIONS PROCESSED :'} at {@code app/cbl/CBTRN02C.cbl:L227}.
     *
     * <p><strong>Exactly one space before the colon.</strong> Reproduced byte for byte because the parity
     * comparison reads this line.
     */
    private static final String MSG_TRANSACTIONS_PROCESSED = "TRANSACTIONS PROCESSED :";

    /**
     * {@code DISPLAY 'TRANSACTIONS REJECTED  :'} at {@code app/cbl/CBTRN02C.cbl:L228}.
     *
     * <p><strong>Exactly two spaces before the colon</strong>, which aligns the value column with the line
     * above. The asymmetry with {@link #MSG_TRANSACTIONS_PROCESSED} is in the source and is preserved.
     */
    private static final String MSG_TRANSACTIONS_REJECTED = "TRANSACTIONS REJECTED  :";

    /**
     * {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN01C'} at {@code app/cbl/CBTRN01C.cbl:L156}.
     *
     * <p>The pre-flight is a distinct program and announces itself under its own name, so the two markers
     * are not interchangeable.
     */
    private static final String MSG_PRE_FLIGHT_START_OF_EXECUTION =
            "START OF EXECUTION OF PROGRAM CBTRN01C";

    /** {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN01C'} at {@code app/cbl/CBTRN01C.cbl:L195}. */
    private static final String MSG_PRE_FLIGHT_END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM CBTRN01C";

    /**
     * The operational form of {@code DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'},
     * {@code app/cbl/CBTRN01C.cbl:L177}-{@code :L179}.
     *
     * <p>Names the two logical datasets involved - the cross reference resolved a card number to an account
     * identifier, and the account master does not hold it - and locates the record by its one-based ordinal
     * within {@code DALYTRAN}. It carries <strong>no identifier of any kind</strong>: not the account, not
     * the card number, not the transaction identifier. An operator reading this line learns which dataset
     * pair disagrees and which record to look at, which is everything the diagnostic is for; whose account
     * it is is not part of that.
     *
     * <p>The identifier-bearing reproduction of the same {@code DISPLAY} travels on {@link #PARITY_LOG}
     * with a {@linkplain #ACCOUNT_ID_VISIBLE_SUFFIX bounded suffix}, so the paragraph map the coverage gate
     * reads is intact while nothing an enabled logger emits can identify an account.
     */
    private static final String MSG_PRE_FLIGHT_ACCOUNT_NOT_FOUND =
            "DALYTRAN RECORD {} RESOLVED THROUGH XREFFILE TO AN ACCOUNT THAT ACCTFILE DOES NOT HOLD";

    /** {@code DISPLAY 'ERROR OPENING DALYTRAN'} at {@code app/cbl/CBTRN02C.cbl:L248}. */
    private static final String MSG_ERROR_OPENING_DALYTRAN = "ERROR OPENING DALYTRAN";

    /** {@code DISPLAY 'ERROR OPENING TRANSACTION FILE'}, paragraph {@code 0100-TRANFILE-OPEN}. */
    private static final String MSG_ERROR_OPENING_TRANFILE = "ERROR OPENING TRANSACTION FILE";

    /** {@code DISPLAY 'ERROR OPENING CROSS REF FILE'}, paragraph {@code 0200-XREFFILE-OPEN}. */
    private static final String MSG_ERROR_OPENING_XREFFILE = "ERROR OPENING CROSS REF FILE";

    /**
     * {@code DISPLAY 'ERROR OPENING DALY REJECTS FILE'}, paragraph {@code 0300-DALYREJS-OPEN}.
     *
     * <p>Note {@code DALY}, not {@code DAILY}. The close paragraph spells it the other way - see
     * {@link #MSG_ERROR_CLOSING_DALYREJS} - and both spellings are reproduced as the source has them.
     */
    private static final String MSG_ERROR_OPENING_DALYREJS = "ERROR OPENING DALY REJECTS FILE";

    /** {@code DISPLAY 'ERROR OPENING ACCOUNT MASTER FILE'}, paragraph {@code 0400-ACCTFILE-OPEN}. */
    private static final String MSG_ERROR_OPENING_ACCTFILE = "ERROR OPENING ACCOUNT MASTER FILE";

    /** {@code DISPLAY 'ERROR OPENING TRANSACTION BALANCE FILE'}, paragraph {@code 0500-TCATBALF-OPEN}. */
    private static final String MSG_ERROR_OPENING_TCATBALF = "ERROR OPENING TRANSACTION BALANCE FILE";

    /** {@code DISPLAY 'ERROR CLOSING DALYTRAN FILE'}, paragraph {@code 9000-DALYTRAN-CLOSE}. */
    private static final String MSG_ERROR_CLOSING_DALYTRAN = "ERROR CLOSING DALYTRAN FILE";

    /** {@code DISPLAY 'ERROR CLOSING TRANSACTION FILE'}, paragraph {@code 9100-TRANFILE-CLOSE}. */
    private static final String MSG_ERROR_CLOSING_TRANFILE = "ERROR CLOSING TRANSACTION FILE";

    /** {@code DISPLAY 'ERROR CLOSING CROSS REF FILE'}, paragraph {@code 9200-XREFFILE-CLOSE}. */
    private static final String MSG_ERROR_CLOSING_XREFFILE = "ERROR CLOSING CROSS REF FILE";

    /**
     * {@code DISPLAY 'ERROR CLOSING DAILY REJECTS FILE'}, paragraph {@code 9300-DALYREJS-CLOSE}.
     *
     * <p><strong>the source is internally inconsistent here.</strong> The open
     * paragraph spells the word {@code DALY} and this close paragraph spells it {@code DAILY}. Both are
     * reproduced exactly as written; normalising either would put a diff into the parity comparison.
     */
    private static final String MSG_ERROR_CLOSING_DALYREJS = "ERROR CLOSING DAILY REJECTS FILE";

    /**
     * {@code DISPLAY 'ERROR CLOSING ACCOUNT FILE'}, paragraph {@code 9400-ACCTFILE-CLOSE}.
     *
     * <p>The open paragraph says {@code ACCOUNT MASTER FILE} and this one says only
     * {@code ACCOUNT FILE}; the asymmetry is in the source.
     */
    private static final String MSG_ERROR_CLOSING_ACCTFILE = "ERROR CLOSING ACCOUNT FILE";

    /** {@code DISPLAY 'ERROR CLOSING TRANSACTION BALANCE FILE'}, paragraph {@code 9500-TCATBALF-CLOSE}. */
    private static final String MSG_ERROR_CLOSING_TCATBALF = "ERROR CLOSING TRANSACTION BALANCE FILE";

    /** {@code DISPLAY 'ERROR OPENING DAILY TRANSACTION FILE'} at {@code app/cbl/CBTRN01C.cbl:L263}. */
    private static final String MSG_PRE_FLIGHT_ERROR_OPENING_DALYTRAN =
            "ERROR OPENING DAILY TRANSACTION FILE";

    /** {@code DISPLAY 'ERROR OPENING CUSTOMER FILE'} at {@code app/cbl/CBTRN01C.cbl:L282}. */
    private static final String MSG_PRE_FLIGHT_ERROR_OPENING_CUSTFILE = "ERROR OPENING CUSTOMER FILE";

    /** {@code DISPLAY 'ERROR OPENING CROSS REF FILE'} at {@code app/cbl/CBTRN01C.cbl:L300}. */
    private static final String MSG_PRE_FLIGHT_ERROR_OPENING_XREFFILE = "ERROR OPENING CROSS REF FILE";

    /** {@code DISPLAY 'ERROR OPENING CARD FILE'} at {@code app/cbl/CBTRN01C.cbl:L318}. */
    private static final String MSG_PRE_FLIGHT_ERROR_OPENING_CARDFILE = "ERROR OPENING CARD FILE";

    /** {@code DISPLAY 'ERROR OPENING ACCOUNT FILE'} at {@code app/cbl/CBTRN01C.cbl:L336}. */
    private static final String MSG_PRE_FLIGHT_ERROR_OPENING_ACCTFILE = "ERROR OPENING ACCOUNT FILE";

    /** {@code DISPLAY 'ERROR OPENING TRANSACTION FILE'} at {@code app/cbl/CBTRN01C.cbl:L354}. */
    private static final String MSG_PRE_FLIGHT_ERROR_OPENING_TRANFILE = "ERROR OPENING TRANSACTION FILE";

    /**
     * {@code DISPLAY 'ERROR CLOSING CUSTOMER FILE'} at {@code app/cbl/CBTRN01C.cbl:L372}.
     *
     * <p><strong>this is a copy-and-paste defect in the source and it is
     * preserved, not repaired.</strong> The literal sits inside {@code 9000-DALYTRAN-CLOSE} at
     * {@code app/cbl/CBTRN01C.cbl:L361}, so a failure to close the <em>daily transaction</em> file reports
     * the <em>customer</em> file. Correcting it would improve the diagnostic and break the parity
     * comparison, and parity is the contract. Note that {@code 9100-CUSTFILE-CLOSE} at {@code :L390} emits
     * the identical literal, so the two are genuinely indistinguishable in the legacy output - which is the
     * defect, faithfully reproduced.
     */
    private static final String MSG_PRE_FLIGHT_ERROR_CLOSING_DALYTRAN = "ERROR CLOSING CUSTOMER FILE";

    /** {@code DISPLAY 'ERROR CLOSING CUSTOMER FILE'} at {@code app/cbl/CBTRN01C.cbl:L390}. */
    private static final String MSG_PRE_FLIGHT_ERROR_CLOSING_CUSTFILE = "ERROR CLOSING CUSTOMER FILE";

    /** {@code DISPLAY 'ERROR CLOSING CROSS REF FILE'} at {@code app/cbl/CBTRN01C.cbl:L408}. */
    private static final String MSG_PRE_FLIGHT_ERROR_CLOSING_XREFFILE = "ERROR CLOSING CROSS REF FILE";

    /** {@code DISPLAY 'ERROR CLOSING CARD FILE'} at {@code app/cbl/CBTRN01C.cbl:L426}. */
    private static final String MSG_PRE_FLIGHT_ERROR_CLOSING_CARDFILE = "ERROR CLOSING CARD FILE";

    /** {@code DISPLAY 'ERROR CLOSING ACCOUNT FILE'} at {@code app/cbl/CBTRN01C.cbl:L444}. */
    private static final String MSG_PRE_FLIGHT_ERROR_CLOSING_ACCTFILE = "ERROR CLOSING ACCOUNT FILE";

    /** {@code DISPLAY 'ERROR CLOSING TRANSACTION FILE'} at {@code app/cbl/CBTRN01C.cbl:L462}. */
    private static final String MSG_PRE_FLIGHT_ERROR_CLOSING_TRANFILE = "ERROR CLOSING TRANSACTION FILE";

    /** {@code DISPLAY 'ERROR READING DAILY TRANSACTION FILE'} at {@code app/cbl/CBTRN01C.cbl:L219}. */
    private static final String MSG_PRE_FLIGHT_ERROR_READING_DALYTRAN =
            "ERROR READING DAILY TRANSACTION FILE";

    /** {@code DISPLAY 'INVALID CARD NUMBER FOR XREF'} at {@code app/cbl/CBTRN01C.cbl:L232}. */
    private static final String MSG_INVALID_CARD_NUMBER_FOR_XREF = "INVALID CARD NUMBER FOR XREF";

    /** {@code DISPLAY 'SUCCESSFUL READ OF XREF'} at {@code app/cbl/CBTRN01C.cbl:L235}. */
    private static final String MSG_SUCCESSFUL_READ_OF_XREF = "SUCCESSFUL READ OF XREF";

    /** {@code DISPLAY 'INVALID ACCOUNT NUMBER FOUND'} at {@code app/cbl/CBTRN01C.cbl:L246}. */
    private static final String MSG_INVALID_ACCOUNT_NUMBER_FOUND = "INVALID ACCOUNT NUMBER FOUND";

    /** {@code DISPLAY 'SUCCESSFUL READ OF ACCOUNT FILE'} at {@code app/cbl/CBTRN01C.cbl:L249}. */
    private static final String MSG_SUCCESSFUL_READ_OF_ACCOUNT_FILE = "SUCCESSFUL READ OF ACCOUNT FILE";

    /** DD name of the driving sequential input, {@code app/jcl/POSTTRAN.jcl:L30}. */
    private static final String DD_DALYTRAN = "DALYTRAN";

    /** DD name of the transaction master, {@code app/jcl/POSTTRAN.jcl:L28}. */
    private static final String DD_TRANFILE = "TRANFILE";

    /** DD name of the card cross-reference, {@code app/jcl/POSTTRAN.jcl:L32}. */
    private static final String DD_XREFFILE = "XREFFILE";

    /** DD name of the reject generation, {@code app/jcl/POSTTRAN.jcl:L34}-{@code :L38}. */
    private static final String DD_DALYREJS = "DALYREJS";

    /** DD name of the account master, {@code app/jcl/POSTTRAN.jcl:L39}. */
    private static final String DD_ACCTFILE = "ACCTFILE";

    /** DD name of the transaction category balance file, {@code app/jcl/POSTTRAN.jcl:L41}. */
    private static final String DD_TCATBALF = "TCATBALF";

    /**
     * DD name of the customer file, {@code app/cbl/CBTRN01C.cbl:L34}.
     *
     * <p>Part of deviation 2: {@code app/jcl/POSTTRAN.jcl} does not supply this DD.
     */
    private static final String DD_CUSTFILE = "CUSTFILE";

    /**
     * DD name of the card file, {@code app/cbl/CBTRN01C.cbl:L46}.
     *
     * <p>Part of deviation 2: {@code app/jcl/POSTTRAN.jcl} does not supply this DD either.
     */
    private static final String DD_CARDFILE = "CARDFILE";

    /** Reason text for an abend raised while opening a dataset. Fits {@code ABEND-REASON X(50)}. */
    private static final String REASON_OPEN_FAILED = "OPEN FAILED";

    /**
     * The suffix every operation-failure reason constant ends in, {@value}.
     *
     * <p>Declared once so {@link #succeededOperation(String)} strips exactly what the reason constants append.
     */
    private static final String FAILED_REASON_SUFFIX = " FAILED";

    /** Reason text for an abend raised while closing a dataset. */
    private static final String REASON_CLOSE_FAILED = "CLOSE FAILED";

    /** Reason text for an abend raised while reading a dataset. */
    private static final String REASON_READ_FAILED = "READ FAILED";

    /** Reason text for an abend raised because the batch runtime supplied no step context. */
    private static final String REASON_NO_STEP_CONTEXT = "NO STEP CONTEXT";

    /** Reason text for an abend raised because a required collaborator or property was absent. */
    private static final String REASON_MISSING_COLLABORATOR = "MISSING COLLABORATOR";

    /** Reason text for an abend raised because a configuration property is unusable. */
    private static final String REASON_INVALID_CONFIGURATION = "INVALID CONFIGURATION";

    /** Reason text for an abend raised because a chunk carried an item the writer cannot classify. */
    private static final String REASON_UNCLASSIFIED_OUTCOME = "UNCLASSIFIED POSTING OUTCOME";

    /** {@code '00'}, the only status either program treats as unconditional success. */
    private static final String SUCCESS_STATUS = FileStatus.SUCCESS.code().orElseThrow();

    /** {@code '35'}, the status an unavailable dataset reports, used for a failed open probe. */
    private static final String OPEN_FAILURE_STATUS = FileStatus.FILE_UNAVAILABLE.code().orElseThrow();

    /**
     * {@code '9'} followed by a digit: the physical or logical I/O error family, used for a failed close or
     * read probe so that the four-character rendering exercises the expanded branch of
     * {@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:L715}-{@code :L721}.
     */
    private static final String IO_ERROR_STATUS = FileStatus.IO_ERROR_FIRST_BYTE + "1";

    /**
     * Page size of an open probe: one row is enough to prove a dataset is reachable, and reading more would
     * be work the source's {@code OPEN} does not do.
     */
    private static final int OPEN_PROBE_PAGE_SIZE = 1;

    /**
     * Identifier property of {@link com.cardemo.model.entity.Customer}, the sort key of the
     * {@code CUSTFILE} probe. Declared at {@code src/main/java/com/cardemo/model/entity/Customer.java:L519}.
     */
    private static final String SORT_CUSTOMER_ID = "customerId";

    /**
     * Identifier property of {@link CardCrossReference}, the sort key of the {@code XREFFILE} probe.
     * Declared at {@code src/main/java/com/cardemo/model/entity/CardCrossReference.java:L325}, and the same
     * 16-character card number the alternate index {@code CXACAIX} is keyed on.
     */
    private static final String SORT_CARD_NUMBER = "cardNumber";

    /**
     * Identifier property of {@link Account}, the sort key of the {@code ACCTFILE} probe. Declared at
     * {@code src/main/java/com/cardemo/model/entity/Account.java:L403}.
     */
    private static final String SORT_ACCOUNT_ID = "accountId";

    /**
     * Identifier property of {@link Transaction}, the sort key of the {@code TRANFILE} probe. Declared at
     * {@code src/main/java/com/cardemo/model/entity/Transaction.java:L582}.
     */
    private static final String SORT_TRANSACTION_ID = "transactionId";

    /**
     * Width of {@code WS-TRANSACTION-COUNT} and {@code WS-REJECT-COUNT}, both {@code PIC 9(09)} at
     * {@code app/cbl/CBTRN02C.cbl:L185}-{@code :L186}.
     *
     * <p>Load bearing for the emitted literal: a {@code PIC 9(09)} {@code DISPLAY} is zero-padded to nine
     * digits, so the counters are rendered the same way rather than as a bare integer.
     */
    private static final int COUNTER_DIGITS = 9;

    /**
     * Format string that renders a counter as nine zero-padded digits, as a {@code PIC 9(09)}
     * {@code DISPLAY} does.
     *
     * <p>Always applied with {@link Locale#ROOT} so that no locale can introduce a grouping separator or a
     * non-ASCII digit.
     */
    private static final String COUNTER_FORMAT = "%0" + COUNTER_DIGITS + "d";

    /**
     * Upper bound on the number of job parameters a launcher may supply.
     *
     * <p>{@code app/jcl/POSTTRAN.jcl:L23} carries no {@code PARM=}, so every parameter is a target-side
     * addition; the bound exists because job parameters are untrusted input and an unbounded map is a
     * denial-of-service surface in the batch metadata tables.
     */
    private static final int MAX_JOB_PARAMETERS = 16;

    /** Upper bound on the length of a job parameter name. */
    private static final int MAX_JOB_PARAMETER_NAME_LENGTH = 100;

    /** Upper bound on the length of a job parameter's rendered value. */
    private static final int MAX_JOB_PARAMETER_VALUE_LENGTH = 250;

    /**
     * Per-thread stack of the diagnostic context entries this job displaced, so they can be restored
     * exactly rather than blanket-removed.
     *
     * <p><strong>Why a stack rather than a single slot.</strong> A single slot holds one displacement per
     * thread, which is correct only while no second execution can begin on a thread that already has one in
     * progress. {@link com.cardemo.batch.jobs.BatchPipelineOrchestrator} launches this job as part of a wider
     * stream, so a nested or re-entrant launch on the same thread is reachable. (An earlier revision of this
     * sentence called that class <em>planned</em> and named it in a code font rather than linking it, on the
     * grounds that it was not authored yet; it is authored, so the qualifier is withdrawn and the link
     * stands.) With a single slot that
     * reachable case is silently destructive in both directions: the inner {@code set} overwrites the
     * outer's displaced values, and the inner {@code remove} then leaves the outer restore with nothing to
     * put back. The outer scope's correlation identifier would be lost for the remainder of the thread's
     * life, which on a pooled thread means it leaks into unrelated work. A stack makes each displacement
     * belong to the invocation that created it, so the two nest instead of colliding.
     *
     * <p>Each entry is an immutable record, so this is shared static state without being <em>mutable</em>
     * static state in the sense that matters: no entry is ever mutated after being pushed. The deque itself
     * is confined to one thread and is discarded the moment it empties, so a pooled thread retains no
     * bookkeeping between jobs.
     *
     * <p>Growth is bounded by construction rather than by a ceiling: every push in
     * {@link #establishDiagnosticContext(long, long)} is matched by a pop in
     * {@link #restoreDiagnosticContext(long)}, which the listener performs in a {@code finally} block, and
     * Spring Batch invokes {@code afterJob} for every execution whose {@code beforeJob} ran.
     */
    private static final ThreadLocal<Deque<DiagnosticContextSnapshot>> DIAGNOSTIC_SNAPSHOTS =
            new ThreadLocal<>();

    /** The batch metadata store, from Spring Boot's batch auto-configuration. Never redeclared here. */
    private final JobRepository jobRepository;

    /**
     * The transaction manager the chunk boundary commits through.
     *
     * <p>This is the mechanism of deviation 1: handing it to
     * {@code chunk(POSTING_COMMIT_INTERVAL, transactionManager)} is what makes paragraphs 2700, 2800 and
     * 2900 one atomic unit instead of the source's three independent commits - and, because that interval is
     * one record, what keeps the unit of work exactly one input record wide.
     */
    private final PlatformTransactionManager transactionManager;

    /** {@code DALYTRAN} staging dataset. Probed by both steps; read for real by the reader. */
    private final DailyTransactionRepository dailyTransactionRepository;

    /** {@code TRANFILE}, the transaction master. Probed by both steps; written by the writer. */
    private final TransactionRepository transactionRepository;

    /** {@code XREFFILE} and {@code CXACAIX}, the card cross-reference. Probed by both steps. */
    private final CardCrossReferenceRepository crossReferenceRepository;

    /** {@code ACCTFILE}, the account master. Probed by both steps; updated by the processor. */
    private final AccountRepository accountRepository;

    /** {@code TCATBALF}, the transaction category balance file. Probed by the posting step only. */
    private final TransactionCategoryBalanceRepository categoryBalanceRepository;

    /**
     * {@code CARDFILE}.
     *
     * <p><strong>Deviation 2.</strong> Required by {@code app/cbl/CBTRN01C.cbl:L46} and <em>not</em>
     * supplied by {@code app/jcl/POSTTRAN.jcl}. Declared rather than dropped, so the pre-flight reproduces
     * all six of its datasets instead of five while appearing complete.
     */
    private final CardRepository cardRepository;

    /**
     * {@code CUSTFILE}.
     *
     * <p><strong>Deviation 2.</strong> Required by {@code app/cbl/CBTRN01C.cbl:L34} and <em>not</em>
     * supplied by {@code app/jcl/POSTTRAN.jcl}. Same justification as {@link #cardRepository}.
     */
    private final CustomerRepository customerRepository;

    /**
     * The single {@code FILE STATUS} translation.
     *
     * <p>Every I/O path in this class routes its status through this collaborator, so the guard idiom that
     * {@code app/cbl/CBTRN02C.cbl} repeats at every {@code OPEN}, {@code READ} and {@code CLOSE} exists in
     * exactly one place.
     */
    private final FileStatusMapper fileStatusMapper;

    /**
     * Owner of the four batch counters.
     *
     * <p>Held so the end-of-run counter emission and the metric stream cannot disagree about which
     * instruments exist. <strong>No fifth instrument is defined here</strong>, and the per-record counting
     * itself belongs to the two writers, which already call
     * {@link MetricsConfig#countRecordsProcessed(int)}, {@link MetricsConfig#countTransactionAmount(
     * java.math.BigDecimal)} and {@link MetricsConfig#countRecordRejected(RejectCode)} - counting again here
     * would count every figure twice.
     */
    private final MetricsConfig metricsConfig;

    /** The registered job name, from {@code carddemo.batch.jobs.posttran.name}. */
    private final String jobName;

    /**
     * The bounded read window, from {@code carddemo.batch.posttran.chunk-size}.
     *
     * <p>It sizes the pre-flight's set-based lookups, so the number of round trips the pre-flight makes is
     * proportional to the record count divided by this value rather than to the record count itself. It is
     * <strong>not</strong> the commit interval: that is {@link #POSTING_COMMIT_INTERVAL} and is pinned by
     * parity rather than configured.
     */
    private final int chunkSize;

    /**
     * Sole constructor: <strong>constructor injection only</strong>, every field {@code final}, so an
     * instance is immutable once built and cannot be half-configured. Field or setter injection would leave
     * a window in which a collaborator is {@code null}, and neither source program has an equivalent of a
     * partially opened state.
     *
     * <p>The chunk-size placeholder is nested on purpose: the job-specific
     * {@code carddemo.batch.posttran.chunk-size} wins when present, the shared
     * {@code carddemo.batch.chunk-size} that {@code src/main/resources/application.yml} does declare is the
     * fallback, and {@value #DEFAULT_CHUNK_SIZE} is the final default. So the job is tunable in isolation
     * without forcing a property the configuration does not currently carry.
     *
     * <p>No bucket, prefix or endpoint is bound here - those belong to the two writers. Nothing is read
     * from the process environment directly.
     *
     * @param jobRepository the batch metadata store, from Spring Boot's batch auto-configuration
     * @param transactionManager the transaction manager the chunk boundary commits through, which is what
     *     makes paragraphs 2700, 2800 and 2900 atomic
     * @param dailyTransactionRepository the {@code DALYTRAN} staging dataset
     * @param transactionRepository the {@code TRANFILE} transaction master
     * @param crossReferenceRepository the {@code XREFFILE} card cross-reference
     * @param accountRepository the {@code ACCTFILE} account master
     * @param categoryBalanceRepository the {@code TCATBALF} transaction category balance file
     * @param cardRepository the {@code CARDFILE} card master - deviation 2, not supplied by
     *     {@code app/jcl/POSTTRAN.jcl}
     * @param customerRepository the {@code CUSTFILE} customer master - deviation 2, likewise not supplied
     * @param metricsConfig the owner of the four batch counters
     * @param fileStatusMapper the single {@code FILE STATUS} translation
     * @param jobName the registered job name, defaulting to {@value #DEFAULT_JOB_NAME}
     * @param chunkSize the bounded read window used by the pre-flight's set-based lookups, defaulting to
     *     {@value #DEFAULT_CHUNK_SIZE}; the commit interval is {@link #POSTING_COMMIT_INTERVAL} and is not
     *     configurable
     * @throws FatalProcessingException if any collaborator is absent, the job name is blank or the chunk
     *     size is not positive
     */
    public DailyTransactionPostingJob(
            final JobRepository jobRepository,
            @Qualifier("transactionManager") final PlatformTransactionManager transactionManager,
            final DailyTransactionRepository dailyTransactionRepository,
            final TransactionRepository transactionRepository,
            final CardCrossReferenceRepository crossReferenceRepository,
            final AccountRepository accountRepository,
            final TransactionCategoryBalanceRepository categoryBalanceRepository,
            final CardRepository cardRepository,
            final CustomerRepository customerRepository,
            final MetricsConfig metricsConfig,
            final FileStatusMapper fileStatusMapper,
            @Value("${carddemo.batch.jobs.posttran.name:" + DEFAULT_JOB_NAME + "}") final String jobName,
            @Value("${carddemo.batch.posttran.chunk-size:${carddemo.batch.chunk-size:"
                    + DEFAULT_CHUNK_SIZE + "}}") final int chunkSize) {

        this.jobRepository = requireCollaborator(jobRepository, "jobRepository");
        this.transactionManager = requireCollaborator(transactionManager, "transactionManager");
        this.dailyTransactionRepository =
                requireCollaborator(dailyTransactionRepository, "dailyTransactionRepository");
        this.transactionRepository = requireCollaborator(transactionRepository, "transactionRepository");
        this.crossReferenceRepository =
                requireCollaborator(crossReferenceRepository, "crossReferenceRepository");
        this.accountRepository = requireCollaborator(accountRepository, "accountRepository");
        this.categoryBalanceRepository =
                requireCollaborator(categoryBalanceRepository, "categoryBalanceRepository");
        this.cardRepository = requireCollaborator(cardRepository, "cardRepository");
        this.customerRepository = requireCollaborator(customerRepository, "customerRepository");
        this.metricsConfig = requireCollaborator(metricsConfig, "metricsConfig");
        this.fileStatusMapper = requireCollaborator(fileStatusMapper, "fileStatusMapper");
        this.jobName = requireText(jobName, "carddemo.batch.jobs.posttran.name");
        this.chunkSize = requirePositive(chunkSize, "carddemo.batch.posttran.chunk-size");
    }

    /**
     * Rejects an absent collaborator with a message that names it, so a mis-wired context fails with a
     * diagnosis rather than with a {@link NullPointerException} at first use. Rule 1 clause B requires null
     * cases to be handled explicitly rather than assumed away.
     *
     * @param <T> the collaborator type
     * @param collaborator the injected collaborator
     * @param name the parameter name, for the diagnostic
     * @return {@code collaborator}, never {@code null}
     * @throws FatalProcessingException if {@code collaborator} is {@code null}
     */
    private static <T> T requireCollaborator(final T collaborator, final String name) {
        if (collaborator == null) {
            throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT_POSTING,
                    REASON_MISSING_COLLABORATOR,
                    "DailyTransactionPostingJob requires a non-null " + name + ".");
        }
        return collaborator;
    }

    /**
     * Rejects a blank configuration value, trimming an otherwise usable one.
     *
     * <p>A blank job name would register the job under an unusable identity and the failure would only
     * surface at launch, so it is refused at startup instead.
     *
     * @param value the configured value, possibly {@code null}
     * @param property the property name, for the diagnostic
     * @return the trimmed value, never blank
     * @throws FatalProcessingException if {@code value} is {@code null}, empty or only whitespace
     */
    private static String requireText(final String value, final String property) {
        final String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT_POSTING,
                    REASON_INVALID_CONFIGURATION,
                    "Property " + property + " must be configured with a non-blank value.");
        }
        return trimmed;
    }

    /**
     * Rejects a non-positive chunk size.
     *
     * <p>Zero would make the step read forever without committing and a negative value would be rejected
     * far later, inside the step builder, with a message that does not name the property.
     *
     * @param value the configured value
     * @param property the property name, for the diagnostic
     * @return {@code value}, guaranteed positive
     * @throws FatalProcessingException if {@code value} is zero or negative
     */
    private static int requirePositive(final int value, final String property) {
        if (value <= 0) {
            throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT_POSTING,
                    REASON_INVALID_CONFIGURATION,
                    "Property " + property + " must be greater than zero but was " + value + ".");
        }
        return value;
    }

    // The bean surface. This folder contributes only Job, Step and Flow beans; every piece of
    // infrastructure - the job repository, the transaction manager, the data source, the object-storage
    // gateway - is injected, never redeclared. The batch-processing enabler annotation appears nowhere:
    // under Spring Boot 3 it switches OFF the batch auto-configuration that supplies the JobRepository.

    /**
     * The read-only pre-flight step: {@code app/cbl/CBTRN01C.cbl}, all 491 lines of it.
     *
     * <p><strong>This step cannot write.</strong> The evidence is the program's own verb inventory, counted
     * on disk at commit {@code 7756d89}: {@code WRITE} 0, {@code REWRITE} 0, {@code DELETE} 0, against six
     * {@code OPEN} and six {@code CLOSE} statements. There is accordingly no {@code save}, no
     * {@code delete}, no {@code flush} and no {@code saveAll} anywhere beneath this method. It is realised
     * as a single tasklet rather than a chunk-oriented step precisely because a chunk step exists to write,
     * and this one has nothing to write.
     *
     * <p>It is folded in here rather than given a job of its own because <strong>no JCL member anywhere in
     * the corpus runs {@code CBTRN01C}</strong> - verified by searching {@code app/jcl} and
     * {@code app/proc} for the program name, which returns nothing - so a standalone job would be an
     * invention. Its paragraphs are cited on the pre-flight methods of this class instead.
     *
     * <p>The tasklet returns {@link RepeatStatus#FINISHED} after one pass, matching
     * {@code MAIN-PARA} at {@code app/cbl/CBTRN01C.cbl:L155}, which runs its opens, its read loop and its
     * closes exactly once and then returns.
     *
     * <p>Its business work runs read-only, in a nested transaction of its own rather than in the step's
     * chunk transaction. The reason that distinction is load-bearing, rather than a stylistic preference,
     * is spelled out on {@link #readOnlyTransactionAttribute()}: the step's own chunk transaction must stay
     * writable because the framework persists {@code BATCH_STEP_EXECUTION_CONTEXT} inside it, so declaring
     * <em>that</em> transaction read-only stops the step completing at all. The tasklet is still handed
     * {@link #transactionManager} because {@link StepBuilder#tasklet} requires one.
     *
     * @param dailyTransactionReader the {@code DALYTRAN} reader, resolved so the pre-flight probe uses the
     *     same step-scoped component the posting step reads with rather than a second, divergent path
     * @return the pre-flight step, never {@code null}
     */
    @Bean(PRE_FLIGHT_STEP_BEAN_NAME)
    public Step dailyTransactionPostingPreFlightStep(
            final DailyTransactionReader dailyTransactionReader) {

        final DailyTransactionReader reader =
                requireCollaborator(dailyTransactionReader, "dailyTransactionReader");

        // Built once, here, and captured by the tasklet: a TransactionTemplate copies the definition it is
        // given into itself, so the mutable attribute instance is not retained and this class still holds no
        // mutable state, static or otherwise.
        final TransactionTemplate readOnlyBusinessWork =
                new TransactionTemplate(transactionManager, readOnlyTransactionAttribute());

        return new StepBuilder(PRE_FLIGHT_STEP_BEAN_NAME, jobRepository)
                .tasklet((contribution, chunkContext) -> readOnlyBusinessWork.execute(
                                status -> preFlightMainPara(reader, contribution)),
                        transactionManager)
                .build();
    }

    /**
     * The transaction attribute the pre-flight step's <em>business work</em> runs under: read-only, and
     * nested in a transaction of its own.
     *
     * <p><strong>Why the attribute is declared rather than merely documented.</strong> Documenting the
     * step's read-only nature enforces nothing: a later edit that added a {@code save} would compile and
     * commit. Declaring the transaction read-only, as below, makes the property structural rather than a
     * matter of discipline.
     *
     * <p><strong>Finding, severity High - raised against that first remediation and remediated here.</strong>
     * The read-only attribute was originally applied to the step itself, through
     * {@code StepBuilder.transactionAttribute(...)}, and that made the step unable to complete on any input.
     * {@code TaskletStep} persists the step execution context <em>inside</em> the chunk transaction - see
     * {@code TaskletStep$ChunkTransactionCallback.doInTransaction}, which calls
     * {@code JobRepository.updateExecutionContext} before that transaction commits - so the framework issues
     * {@code UPDATE BATCH_STEP_EXECUTION_CONTEXT} against a connection the attribute has just marked read
     * only. PostgreSQL refuses it with SQL state {@code 25006}, "cannot execute UPDATE in a read-only
     * transaction", the framework wraps that as {@code FatalStepExecutionException: JobRepository failure
     * forcing rollback}, and the step ends {@code FAILED} before the decider is ever consulted. Remediation:
     * leave the step's own transaction writable, which the framework requires for its bookkeeping, and apply
     * this attribute to a nested transaction that wraps only the tasklet body - the part that is genuinely
     * read-only. The enforcement the first remediation asked for is kept; what is dropped is applying it to a
     * transaction that was never the business one.
     *
     * <p>{@link TransactionDefinition#PROPAGATION_REQUIRES_NEW} is therefore not decoration but the whole
     * mechanism. Under {@code PROPAGATION_REQUIRED} the nested attribute would join the step's writable
     * transaction and the read-only flag would be silently discarded, leaving documentation that claims an
     * enforcement that does not exist - worse than no enforcement, because it would be believed. Requiring a
     * new transaction gives the tasklet a connection of its own on which the database itself rejects a write;
     * the pool is sized at ten, so holding two at once is unremarkable.
     *
     * <p>The evidence that read-only is faithful to the source is the program's own verb inventory:
     * {@code app/cbl/CBTRN01C.cbl} contains {@code WRITE} 0, {@code REWRITE} 0 and {@code DELETE} 0 against
     * six {@code OPEN} and six {@code CLOSE} statements. Rule 1 clause A puts explicit behaviour ahead of
     * cleverness and clause D asks for least privilege; both point at declaring the intent rather than
     * relying on the absence of a call.
     *
     * <p>Built fresh on each call rather than held in a constant, because
     * {@link DefaultTransactionAttribute} is mutable and a shared instance would be static mutable state -
     * which this class does not have and will not acquire. Only the pre-flight step uses it: the posting step
     * writes, so a read-only attribute there would be wrong.
     *
     * @return a read-only, requires-new transaction attribute naming the step, never {@code null}
     */
    private static TransactionAttribute readOnlyTransactionAttribute() {
        final DefaultTransactionAttribute attribute = new DefaultTransactionAttribute();
        attribute.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        attribute.setReadOnly(true);
        attribute.setName(PRE_FLIGHT_STEP_BEAN_NAME);
        return attribute;
    }

    /**
     * The posting step: the mainline loop of {@code app/cbl/CBTRN02C.cbl:L202}-{@code :L219}, realised as a
     * chunk-oriented step.
     *
     * <p>The correspondence is exact. {@code PERFORM UNTIL END-OF-FILE = 'Y'} is the step's own loop;
     * {@code PERFORM 1000-DALYTRAN-GET-NEXT} at {@code :L204} is
     * {@link DailyTransactionReader#read()}, whose {@code null} return is the {@code '10'} end-of-file
     * status and therefore <strong>loop termination rather than an exception</strong>; the validation
     * cascade of {@code :L210} is {@link TransactionPostingProcessor#process(DailyTransaction)}; and the
     * {@code IF}/{@code ELSE} at {@code :L211}-{@code :L216} that chooses between posting and rejecting is
     * {@link PostingOutcomeWriter}.
     *
     * <p><strong>Deviation 1 is established here.</strong> Handing {@link #transactionManager} to
     * {@code chunk(...)} places {@code 2700-UPDATE-TCATBAL}, {@code 2800-UPDATE-ACCOUNT-REC} and
     * {@code 2900-WRITE-TRANSACTION-FILE} - the three writes of {@code :L440}-{@code :L442} - inside one
     * transaction. The source commits each separately. The order is preserved; the atomicity is new.
     *
     * <p>The reader's page size and the commit interval are the same value deliberately: a chunk that
     * committed across page boundaries would hold a transaction open over an extra fetch for no benefit.
     *
     * <p><strong>Finding, severity High - found by
     * {@code src/test/java/com/cardemo/integration/batch/DailyTransactionPostingJobTest.java} and fixed
     * here.</strong> {@link TransactionWriter} declares {@link StepExecutionListener} and obtains the
     * {@code StepExecution} it needs for the object key from {@code beforeStep}. {@code SimpleStepBuilder}
     * auto-registers a reader, processor or writer as a step listener - but it inspects <em>the writer it was
     * given</em>, and the writer given here is {@link PostingOutcomeWriter}, a composite that is not itself a
     * listener. The delegate was therefore invisible to that check, {@code beforeStep} was never called, and
     * <strong>the first posted transaction of every run abended the step</strong> with
     * {@code no step execution was captured before the first write}. Fix: register the delegate explicitly,
     * as {@code StatementGenerationJob} already does for {@code StatementWriter}. {@link RejectWriter} needs
     * no such registration - it takes its {@code StepExecution} by step-scoped injection instead.
     *
     * @param dailyTransactionReader the {@code DALYTRAN} reader, paragraph {@code 1000-DALYTRAN-GET-NEXT}
     * @param transactionPostingProcessor the validation cascade and the two updates, paragraphs
     *     {@code 1500} through {@code 2800}
     * @param transactionWriter paragraph {@code 2900-WRITE-TRANSACTION-FILE} and the 350-byte geometry
     * @param rejectWriter paragraph {@code 2500-WRITE-REJECT-REC} and the 430-byte geometry
     * @return the posting step, never {@code null}
     */
    @Bean(POSTING_STEP_BEAN_NAME)
    public Step dailyTransactionPostingStep(
            final DailyTransactionReader dailyTransactionReader,
            final TransactionPostingProcessor transactionPostingProcessor,
            final TransactionWriter transactionWriter,
            final RejectWriter rejectWriter) {

        final DailyTransactionReader reader =
                requireCollaborator(dailyTransactionReader, "dailyTransactionReader");
        final TransactionPostingProcessor processor =
                requireCollaborator(transactionPostingProcessor, "transactionPostingProcessor");
        final TransactionWriter posted = requireCollaborator(transactionWriter, "transactionWriter");
        final RejectWriter rejected = requireCollaborator(rejectWriter, "rejectWriter");

        return new StepBuilder(POSTING_STEP_BEAN_NAME, jobRepository)
                .<DailyTransaction, TransactionPostingProcessor.PostingResult>chunk(
                        POSTING_COMMIT_INTERVAL, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(new PostingOutcomeWriter(posted, rejected))
                .listener((StepExecutionListener) posted)
                .listener(new PostTranStepListener(rejected))
                .build();
    }

    /**
     * Wraps the two steps so that <strong>every</strong> outcome is routed through
     * {@link PostTranReturnCodeDecider} and mapped onto the four legacy return codes.
     *
     * <p>The wildcard transitions are deliberate and load bearing. Without them a failed step would
     * short-circuit the flow and the decider would never run, so return codes 8 and 12 could not be
     * distinguished. From the decider, {@code COMPLETED} and {@code COMPLETED WITH REJECTS} end the flow
     * successfully carrying their own exit code, while {@code FAILED}, {@code ABEND} and anything
     * unrecognised fail it. The unrecognised arm exists so a future decider outcome cannot silently fall
     * through to success.
     *
     * <p>The pre-flight runs first and is also gated, because a pre-flight that cannot reach its datasets
     * must stop the run rather than let the posting step discover the same problem halfway through a file.
     * That ordering is the whole point of a pre-flight and mirrors {@code app/cbl/CBTRN02C.cbl:L195}-{@code
     * :L200}, where a failed open abends before the loop is ever entered.
     *
     * @param dailyTransactionPostingPreFlightStep the read-only pre-flight, injected by bean name so the
     *     flow cannot bind to some other step that happens to be assignable
     * @param dailyTransactionPostingStep the chunk-oriented posting step, likewise injected by bean name
     * @return the gated flow, never {@code null}
     */
    @Bean(FLOW_BEAN_NAME)
    public Flow dailyTransactionPostingFlow(
            @Qualifier(PRE_FLIGHT_STEP_BEAN_NAME) final Step dailyTransactionPostingPreFlightStep,
            @Qualifier(POSTING_STEP_BEAN_NAME) final Step dailyTransactionPostingStep) {

        final JobExecutionDecider returnCodeDecider = new PostTranReturnCodeDecider();

        return new FlowBuilder<SimpleFlow>(FLOW_BEAN_NAME)
                .start(dailyTransactionPostingPreFlightStep)
                .on(EXIT_CODE_FAILED).fail()
                .from(dailyTransactionPostingPreFlightStep)
                .on(EXIT_CODE_ANY).to(dailyTransactionPostingStep)
                .from(dailyTransactionPostingStep).on(EXIT_CODE_ANY).to(returnCodeDecider)
                .from(returnCodeDecider).on(EXIT_CODE_COMPLETED).end(EXIT_CODE_COMPLETED)
                .from(returnCodeDecider).on(EXIT_CODE_COMPLETED_WITH_REJECTS)
                .end(EXIT_CODE_COMPLETED_WITH_REJECTS)
                .from(returnCodeDecider).on(EXIT_CODE_FAILED).fail()
                .from(returnCodeDecider).on(EXIT_CODE_ABEND).fail()
                .from(returnCodeDecider).on(EXIT_CODE_ANY).fail()
                .build();
    }

    /**
     * The job itself: the whole of {@code app/jcl/POSTTRAN.jcl}.
     *
     * <p>Two things are attached here rather than anywhere else. {@link PostTranParametersValidator}
     * refuses unusable job parameters <strong>before either step runs</strong>, which is the closest
     * available analogue of the language environment refusing to pass a malformed {@code PARM}. And
     * {@link PostTranJobListener} owns the diagnostic context, which spans both programs and so belongs to
     * the job rather than to either step.
     *
     * <p>No incrementer is declared. {@code app/jcl/POSTTRAN.jcl} has no run-sequence concept, and adding
     * one would let the same {@code DALYTRAN} content be posted twice under a fresh job instance - exactly
     * the duplicate-key collision that must surface rather than be smoothed over.
     *
     * <p>Both the validator and the listener are plain nested objects rather than beans. Declaring either as
     * a bean would add a container singleton this folder is not permitted to contribute, and would risk a
     * name collision with {@code com.cardemo.config.BatchConfig}; neither needs one.
     *
     * @param dailyTransactionPostingFlow the gated flow, injected by bean name
     * @return the job, registered under {@link #jobName}, never {@code null}
     */
    @Bean(JOB_BEAN_NAME)
    public Job dailyTransactionPostingJob(
            @Qualifier(FLOW_BEAN_NAME) final Flow dailyTransactionPostingFlow) {

        return new JobBuilder(jobName, jobRepository)
                .validator(new PostTranParametersValidator())
                .listener(new PostTranJobListener())
                .start(dailyTransactionPostingFlow)
                .end()
                .build();
    }

    // app/cbl/CBTRN01C.cbl - the read-only pre-flight. Nineteen paragraph labels, mapped one to one below
    // with no consolidation. FILE-CONTROL at :L28 is a declaration rather than an executable paragraph and
    // has no method: its six SELECT statements map onto the six repositories declared as fields, namely
    // DALYTRAN -> dailyTransactionRepository, CUSTFILE -> customerRepository,
    // XREFFILE -> crossReferenceRepository, CARDFILE -> cardRepository, ACCTFILE -> accountRepository and
    // TRANFILE -> transactionRepository. The other eighteen each have a method.

    /**
     * {@code MAIN-PARA} - {@code app/cbl/CBTRN01C.cbl:L155}-{@code :L197}.
     *
     * <p>Runs the six opens in the source's order at {@code :L157}-{@code :L162}, then the read loop at
     * {@code :L164}-{@code :L186}, then the six closes in the source's order at {@code :L188}-{@code :L193},
     * bracketed by the program's own boundary markers at {@code :L156} and {@code :L195}. Ordering is
     * preserved so that a failed open reports the first dataset that is unavailable, exactly as it does on
     * the mainframe.
     *
     * <p><strong>A legacy control-flow quirk, preserved.</strong> The loop reads
     * a record, and only the {@code DISPLAY} at {@code :L168} is guarded by the end-of-file test at
     * {@code :L167}. The cross-reference lookup and the account read at {@code :L170}-{@code :L184} sit
     * <em>outside</em> that inner {@code IF} and inside the outer one at {@code :L165}, so on the iteration
     * that detects end of file <strong>they run one extra time against the previous record's card
     * number</strong>. That extra lookup is reproduced here rather than optimised away, because it is
     * visible in the program's diagnostic output and this program exists only to produce that output.
     *
     * <p>Nothing in this method or anything it calls writes: no {@code save}, no {@code delete}, no
     * {@code flush}. The evidence is the program's verb inventory - {@code WRITE} 0, {@code REWRITE} 0,
     * {@code DELETE} 0.
     *
     * @param reader the {@code DALYTRAN} reader this paragraph opens at {@code :L157}, drives at
     *     {@code :L166} and releases at {@code :L188}, exactly as the source's {@code MAIN-PARA} does
     * @param contribution the step's contribution, credited with one read per record consumed so that
     *     the diagnostic pass's volume is visible in the job repository the way {@code CBTRN01C}'s own
     *     {@code DISPLAY} counters made it visible on the job log. A tasklet contributes nothing unless
     *     it says so, so a pass that read three hundred records would otherwise report none
     * @return {@link RepeatStatus#FINISHED}, because {@code MAIN-PARA} runs once and returns at
     *     {@code :L197}
     * @throws FatalProcessingException if any of the six datasets cannot be opened, read or closed
     */
    private RepeatStatus preFlightMainPara(final DailyTransactionReader reader,
            final StepContribution contribution) {
        LOG.info(MSG_PRE_FLIGHT_START_OF_EXECUTION);

        openPreFlightDailyTransactionFile(reader);
        openPreFlightCustomerFile();
        openPreFlightCrossReferenceFile();
        openPreFlightCardFile();
        openPreFlightAccountFile();
        openPreFlightTransactionFile();

        // :L164-:L186. Spring Batch owns no loop here - this is a tasklet, so the loop is written out, and
        // for the same reason the step registers no ItemStream and opens nothing: the reader was opened by
        // openPreFlightDailyTransactionFile above, which is 0000-DALYTRAN-OPEN, and is released by
        // closePreFlightDailyTransactionFile below, which is 9000-DALYTRAN-CLOSE. The source performs both
        // itself, at :L157 and :L188. The pre-flight consumes the whole file exactly as the source does,
        // because its purpose is to report on every record.
        //
        // The records are consumed in bounded windows rather than one at a time, and the two lookups of
        // :L170-:L184 are resolved for a whole window with one query each. See preFlightResolveWindow():
        // the emission order inside the window is still the file's own, so what an operator sees is
        // unchanged, but the round-trip count falls from two per record to two per window.
        //
        // recordOrdinal is the one-based position of the record within DALYTRAN. Not a parity artefact - the
        // source has no such counter - but the ordinal is what lets the identifier-free diagnostic below
        // locate a record, so it is what replaces the account identifier the source's DISPLAY carried.
        long recordOrdinal = 0L;
        DailyTransaction last = null;
        PreFlightWindow window = preFlightReadWindow(reader);
        while (!window.isEmpty()) {
            final PreFlightLookups resolved = preFlightResolveWindow(window);
            for (final DailyTransaction current : window.records()) {
                recordOrdinal++;
                // :L167-:L169 - the record image is emitted only while not at end of file.
                preFlightDisplayDalytranRecord(current);
                preFlightVerifyRecord(current, recordOrdinal, resolved);
                contribution.incrementReadCount();
                last = current;
            }
            if (window.endOfFile()) {
                // :L170-:L184 once more, on the iteration that set the end-of-file flag, against the record
                // the previous pass left in DALYTRAN-CARD-NUM. It is a genuine re-execution of both lookups
                // and both emissions, not a narrative about one: the source really does perform them, and
                // they are visible in the diagnostic output this program exists to produce. No extra round
                // trip is needed, because the window that carried the record also carried its lookups.
                preFlightVerifyTrailingRecord(last, recordOrdinal, resolved);
                break;
            }
            window = preFlightReadWindow(reader);
        }
        if (last == null) {
            // An empty file from the outset. The source's DALYTRAN-CARD-NUM is still at its initial value, so
            // the cross-reference lookup fails and the unverifiable-card arm is what an observer sees. That
            // branch is taken here too rather than skipped, so the emptiness case emits what the source emits.
            preFlightVerifyTrailingRecord(null, 0L, PreFlightLookups.empty());
        }

        closePreFlightDailyTransactionFile(reader);
        closePreFlightCustomerFile();
        closePreFlightCrossReferenceFile();
        closePreFlightCardFile();
        closePreFlightAccountFile();
        closePreFlightTransactionFile();

        LOG.info(MSG_PRE_FLIGHT_END_OF_EXECUTION);
        return RepeatStatus.FINISHED;
    }

    /**
     * The body of {@code app/cbl/CBTRN01C.cbl:L170}-{@code :L184} for one record.
     *
     * <p>Resets the cross-reference status, moves the card number, performs the lookup, and on success moves
     * the account identifier and performs the account read - reporting a missing account at {@code :L178} and
     * an unverifiable card at {@code :L181}-{@code :L183}. Kept separate from {@link #preFlightMainPara(
     * DailyTransactionReader, StepContribution)} only so that the preserved trailing invocation and the
     * in-loop invocation cannot drift apart; it is not a consolidation of paragraphs, since both
     * {@code 2000-LOOKUP-XREF} and
     * {@code 3000-READ-ACCOUNT} keep their own methods.
     *
     * <p><strong>Finding, severity Medium, resolved - CWE-532, insertion of sensitive information into a
     * log file.</strong> The {@code DISPLAY} at {@code :L177}-{@code :L179} names the account identifier,
     * and reproducing it on the application logger published a complete {@code PIC 9(11)} account
     * identifier at {@code WARN} - a level enabled in every deployment, so the value reached every log
     * aggregator, retention store and replica the log stream reaches. The remedy is the one this class
     * already applies to the card number three lines below, applied to the account identifier as well: the
     * operational emission carries {@link #MSG_PRE_FLIGHT_ACCOUNT_NOT_FOUND}, which names the two logical
     * datasets and the record's ordinal and <em>no identifier at all</em>, while the identifier-bearing
     * reproduction of the source's own wording travels on {@link #PARITY_LOG} - pinned {@code OFF} in every
     * shipped profile, guarded by a level check, and carrying only a
     * {@linkplain #ACCOUNT_ID_VISIBLE_SUFFIX bounded suffix}. Neither the paragraph map nor the operator's
     * ability to locate the offending record is lost.
     *
     * @param record the daily transaction whose card number drives the lookup; must not be {@code null}
     * @param recordOrdinal the one-based position of {@code record} within {@code DALYTRAN}, used to
     *     locate the record in a diagnostic without naming anything that identifies its owner
     * @param resolved the window's pre-resolved cross-reference and account rows, which stand in for the
     *     two keyed reads without changing which rows they find; must not be {@code null}
     */
    private void preFlightVerifyRecord(final DailyTransaction record, final long recordOrdinal,
            final PreFlightLookups resolved) {
        // :L170-:L171 MOVE 0 TO WS-XREF-READ-STATUS / MOVE DALYTRAN-CARD-NUM TO XREF-CARD-NUM
        final Long accountId = preFlightLookupXref(record.getCardNumber(), resolved);
        if (accountId != null) {
            // :L173-:L176 the xref read succeeded, so the account read is attempted
            if (!preFlightReadAccount(accountId, resolved)) {
                // :L177-:L179 DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'. Split in two: an identifier-free
                // operational line, and the source's own wording on the hard-OFF parity channel with the
                // identifier reduced to its last four digits.
                LOG.warn(MSG_PRE_FLIGHT_ACCOUNT_NOT_FOUND, Long.valueOf(recordOrdinal));
                if (PARITY_LOG.isDebugEnabled()) {
                    PARITY_LOG.debug("ACCOUNT {} NOT FOUND", maskAccountIdentifier(accountId));
                }
            }
            return;
        }
        // :L180-:L184. The card number is masked and the emission is routed to the parity logger, because
        // the source displays the value in full and this target must not.
        if (PARITY_LOG.isDebugEnabled()) {
            PARITY_LOG.debug("CARD NUMBER {} COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-{}",
                    maskCardNumber(record.getCardNumber()), record.getTransactionId());
        }
    }

    /**
     * Reads up to {@link #chunkSize} records from the {@code DALYTRAN} reader, reporting whether the read that
     * filled the window also reached end of file.
     *
     * <p>Windowing is what makes the two lookups of {@code app/cbl/CBTRN01C.cbl:L170}-{@code :L184} resolvable
     * as set operations. It changes nothing an observer can see: the records are returned in the order the
     * reader produced them, which is the file's order, and every one of them is emitted individually and in
     * that order by the caller.
     *
     * <p>The window is bounded by construction, so a staged file of any size costs at most
     * {@link #chunkSize} resident records. That matters because the pre-flight reads the <em>whole</em> file -
     * the source does, at {@code :L164}-{@code :L186}, because its purpose is to report on every record.
     *
     * @param reader the {@code DALYTRAN} reader, already opened by the step's stream registration
     * @return the next window, empty only when the reader was exhausted before it produced anything
     * @throws FatalProcessingException if a read fails with any status other than success or end of file
     */
    private PreFlightWindow preFlightReadWindow(final DailyTransactionReader reader) {
        final List<DailyTransaction> records = new ArrayList<>(chunkSize);
        for (int index = 0; index < chunkSize; index++) {
            final DailyTransaction next = preFlightDalytranGetNext(reader);
            if (next == null) {
                // MOVE 'Y' TO END-OF-DAILY-TRANS-FILE (:L217). The window is short, and the caller must run
                // the end-of-file repetition rather than ask for another window.
                return new PreFlightWindow(List.copyOf(records), true);
            }
            records.add(next);
        }
        return new PreFlightWindow(List.copyOf(records), false);
    }

    /**
     * Resolves one window's cross-reference and account rows with one query each.
     *
     * <p><strong>Finding, severity High - remediated here.</strong> This method replaces a keyed read per
     * record on each of two datasets. For the 300-record parity fixture that was about 600 round trips, and it
     * grew linearly with the staged file, which is the "obvious inefficiency" Rule 1 clause A forbids.
     *
     * <p><strong>What is preserved, exactly.</strong> A keyed read either finds a row or does not, and which
     * rows a set of keys finds is the same question asked once as asked one key at a time - so the outcome per
     * record is identical. The <em>order</em> of the diagnostics is preserved by the caller, which iterates the
     * window in file order and emits each record's own messages, and the <em>order of the two lookups</em> is
     * preserved by {@link #preFlightVerifyRecord(DailyTransaction, long, PreFlightLookups)}, which still
     * consults
     * the cross-reference first and only then the account. The account keys are collected from the resolved
     * cross-references rather than guessed, so the second query asks for exactly the accounts the first
     * query's results name - which is what {@code :L173}-{@code :L176} does one record at a time.
     *
     * <p>Duplicate card numbers within a window cost nothing extra: the keys are de-duplicated into a set
     * before the query, which is a property of the query and not of the emission.
     *
     * @param window the records to resolve; must not be {@code null}
     * @return the resolved rows, never {@code null}
     * @throws FatalProcessingException if either dataset cannot be read
     */
    private PreFlightLookups preFlightResolveWindow(final PreFlightWindow window) {
        final Set<String> cardNumbers = new LinkedHashSet<>();
        for (final DailyTransaction record : window.records()) {
            final String cardNumber = record.getCardNumber();
            if (cardNumber != null && !cardNumber.isBlank()) {
                cardNumbers.add(cardNumber);
            }
        }
        if (cardNumbers.isEmpty()) {
            return PreFlightLookups.empty();
        }

        final Map<String, CardCrossReference> crossReferences = new LinkedHashMap<>();
        try {
            for (final CardCrossReference found : crossReferenceRepository.findAllById(cardNumbers)) {
                crossReferences.put(found.getCardNumber(), found);
            }
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final DataAccessException cause) {
            LOG.error(MSG_PRE_FLIGHT_ERROR_OPENING_XREFFILE);
            preFlightDisplayIoStatus(IO_ERROR_STATUS);
            throw preFlightAbendProgram(MSG_PRE_FLIGHT_ERROR_OPENING_XREFFILE, REASON_READ_FAILED, cause);
        }

        final Set<Long> accountIds = new LinkedHashSet<>();
        for (final CardCrossReference crossReference : crossReferences.values()) {
            if (crossReference.getAccountId() != null) {
                accountIds.add(crossReference.getAccountId());
            }
        }
        final Set<Long> presentAccounts = new LinkedHashSet<>();
        if (!accountIds.isEmpty()) {
            try {
                for (final Account found : accountRepository.findAllById(accountIds)) {
                    presentAccounts.add(found.getAccountId());
                }
            } catch (final CardDemoException alreadyTyped) {
                throw alreadyTyped;
            } catch (final DataAccessException cause) {
                LOG.error(MSG_PRE_FLIGHT_ERROR_OPENING_ACCTFILE);
                preFlightDisplayIoStatus(IO_ERROR_STATUS);
                throw preFlightAbendProgram(MSG_PRE_FLIGHT_ERROR_OPENING_ACCTFILE, REASON_READ_FAILED,
                        cause);
            }
        }
        return new PreFlightLookups(Map.copyOf(crossReferences), Set.copyOf(presentAccounts));
    }

    /**
     * The end-of-file repetition of {@code app/cbl/CBTRN01C.cbl:L170}-{@code :L184}.
     *
     * <p><strong>Finding, severity High - remediated here.</strong> The source really does execute both
     * lookups and both emissions once more, on the iteration that sets the end-of-file flag, because they sit
     * outside the inner {@code IF END-OF-FILE = 'N'} at {@code :L167} and inside the outer one at
     * {@code :L165} - with {@code DALYTRAN-CARD-NUM} still holding whatever the previous successful read left
     * in it. Emitting a <em>narrative</em> about that repetition instead of
     * performing it is not the same observable behaviour: the diagnostic line an operator sees is the
     * record's own line, not a note about it, and this program exists only to produce that output. The
     * repetition is genuinely executed here, against the record the loop left behind.
     *
     * <p>It costs no extra dataset access, which is what makes a narrative shortcut tempting. The window that
     * carried the record also carried its resolved cross-reference and account rows, so the repeated lookup is
     * answered from the same resolution the in-loop lookup used - which is exactly what the source's own
     * re-read does, since re-reading the same key returns the same row.
     *
     * <p>On an empty file the source's card number field is still at its initial value and the lookup fails,
     * so the unverifiable-card arm is what an observer sees; that is the branch taken here too, by passing
     * a {@code null} record. Both cases are therefore executed rather than described. It is a preserved
     * quirk whose physical dataset access is deliberately not duplicated.
     *
     * @param lastRecord the record the loop last emitted, or {@code null} when the file was empty from the
     *     outset
     * @param recordOrdinal the one-based position of {@code lastRecord} within {@code DALYTRAN}, or
     *     {@code 0} when the file was empty from the outset
     * @param resolved the window's resolved rows, which answer the repeated lookup without a further round
     *     trip; must not be {@code null}
     */
    private void preFlightVerifyTrailingRecord(final DailyTransaction lastRecord,
            final long recordOrdinal, final PreFlightLookups resolved) {

        if (lastRecord == null) {
            // An empty file: DALYTRAN-CARD-NUM holds its initial value, the xref read fails, and :L180-:L184
            // is the arm the source takes. Reproduced by resolving a blank card number, which the lookup
            // already treats as unverifiable.
            preFlightLookupXref(null, resolved);
            return;
        }
        preFlightVerifyRecord(lastRecord, recordOrdinal, resolved);
    }

    /**
     * One bounded window of staged records, and whether the read that filled it reached end of file.
     *
     * @param records the records in file order, never {@code null} and immutable
     * @param endOfFile {@code true} when the reader reported end of file while filling this window, which is
     *     {@code MOVE 'Y' TO END-OF-DAILY-TRANS-FILE} at {@code app/cbl/CBTRN01C.cbl:L217}
     */
    private record PreFlightWindow(List<DailyTransaction> records, boolean endOfFile) {

        /**
         * Reports whether the window carries no records at all, which happens only when the reader was already
         * exhausted.
         *
         * @return {@code true} when there is nothing to emit
         */
        private boolean isEmpty() {
            return records.isEmpty();
        }
    }

    /**
     * One window's resolved cross-reference and account rows.
     *
     * <p>Immutable, and built once per window by {@link #preFlightResolveWindow(PreFlightWindow)}. It answers
     * the two keyed reads of {@code app/cbl/CBTRN01C.cbl:L170}-{@code :L184} for every record in the window,
     * and it also answers the end-of-file repetition, which asks the same two questions about a record the
     * window already covered.
     *
     * @param crossReferences the cross-reference rows found, keyed by card number; never {@code null}
     * @param presentAccounts the account identifiers that exist; never {@code null}
     */
    private record PreFlightLookups(Map<String, CardCrossReference> crossReferences,
                                    Set<Long> presentAccounts) {

        /**
         * An empty resolution, for a window that carries no usable card number and for the empty-file case.
         *
         * @return a resolution in which every lookup misses, never {@code null}
         */
        private static PreFlightLookups empty() {
            return new PreFlightLookups(Map.of(), Set.of());
        }
    }

    /**
     * {@code 1000-DALYTRAN-GET-NEXT} - {@code app/cbl/CBTRN01C.cbl:L202}-{@code :L225}.
     *
     * <p>The source reads, then maps {@code '00'} to {@code APPL-RESULT 0}, {@code '10'} to
     * {@code APPL-EOF} and {@code MOVE 'Y' TO END-OF-DAILY-TRANS-FILE} at {@code :L217}, and anything else
     * to {@code APPL-RESULT 12} followed by {@code Z-DISPLAY-IO-STATUS} and {@code Z-ABEND-PROGRAM} at
     * {@code :L219}-{@code :L222}.
     *
     * <p>Those three outcomes map onto the reader's contract exactly: a returned item is {@code '00'}, a
     * returned {@code null} is {@code '10'} and is <strong>loop termination rather than an exception</strong>,
     * and a thrown exception is the abend arm. The abend arm preserves the cause rather than swallowing it.
     *
     * @param reader the {@code DALYTRAN} reader
     * @return the next record, or {@code null} at end of file
     * @throws FatalProcessingException if the read fails with any status other than success or end of file
     */
    private DailyTransaction preFlightDalytranGetNext(final DailyTransactionReader reader) {
        try {
            return reader.read();
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final RuntimeException cause) {
            LOG.error(MSG_PRE_FLIGHT_ERROR_READING_DALYTRAN);
            preFlightDisplayIoStatus(IO_ERROR_STATUS);
            throw preFlightAbendProgram(MSG_PRE_FLIGHT_ERROR_READING_DALYTRAN, REASON_READ_FAILED, cause);
        }
    }

    /**
     * {@code 2000-LOOKUP-XREF} - {@code app/cbl/CBTRN01C.cbl:L227}-{@code :L239}.
     *
     * <p>A keyed read of the cross-reference by card number. On {@code INVALID KEY} the source displays
     * {@code 'INVALID CARD NUMBER FOR XREF'} and moves 4 into {@code WS-XREF-READ-STATUS} at
     * {@code :L232}-{@code :L233}; on success it displays the card number, the account identifier and the
     * customer identifier at {@code :L235}-{@code :L238}.
     *
     * <p><strong>This paragraph does not abend on a missing record</strong> - it reports and continues,
     * which is why a record-not-found outcome is returned as {@code null} rather than raised. That is a
     * property of the diagnostic program and is not generalised to the posting step, where a missing
     * cross-reference is reject code 100.
     *
     * <p>The three identifier displays carry a card number, so they are routed to {@link #PARITY_LOG} with
     * the card number masked.
     *
     * @param cardNumber the card number from the current daily transaction; may be {@code null} or blank,
     *     which is treated as an unverifiable card rather than as an error
     * @param resolved the window's resolved cross-reference rows, which stand in for the keyed read; must not
     *     be {@code null}
     * @return the cross-referenced account identifier, or {@code null} when the card is not on file
     */
    private Long preFlightLookupXref(final String cardNumber, final PreFlightLookups resolved) {
        if (cardNumber == null || cardNumber.isBlank()) {
            LOG.warn(MSG_INVALID_CARD_NUMBER_FOR_XREF);
            return null;
        }
        final CardCrossReference located = resolved.crossReferences().get(cardNumber);
        if (located == null) {
            // :L231-:L233 INVALID KEY
            LOG.warn(MSG_INVALID_CARD_NUMBER_FOR_XREF);
            return null;
        }
        // :L234-:L238 NOT INVALID KEY
        final CardCrossReference crossReference = located;
        if (PARITY_LOG.isDebugEnabled()) {
            // All three identifiers are masked, not just the card number. The cross-reference record is
            // the one place where a card number, an account number and a customer number appear on a
            // single line, so an unmasked pair here would re-link exactly what masking the card number
            // is meant to break.
            PARITY_LOG.debug("{} CARD NUMBER: {} ACCOUNT ID : {} CUSTOMER ID: {}",
                    MSG_SUCCESSFUL_READ_OF_XREF, maskCardNumber(crossReference.getCardNumber()),
                    maskIdentifier(crossReference.getAccountId()),
                    maskIdentifier(crossReference.getCustomerId()));
        }
        return crossReference.getAccountId();
    }

    /**
     * {@code 3000-READ-ACCOUNT} - {@code app/cbl/CBTRN01C.cbl:L241}-{@code :L250}.
     *
     * <p>A keyed read of the account master. On {@code INVALID KEY} the source displays
     * {@code 'INVALID ACCOUNT NUMBER FOUND'} and moves 4 into {@code WS-ACCT-READ-STATUS} at
     * {@code :L246}-{@code :L247}; on success it displays {@code 'SUCCESSFUL READ OF ACCOUNT FILE'} at
     * {@code :L249}. Like the cross-reference lookup, <strong>it does not abend on a missing record.</strong>
     *
     * @param accountId the account identifier the cross-reference yielded; never {@code null} at this point
     * @param resolved the window's resolved account identifiers, which stand in for the keyed read; must not
     *     be {@code null}
     * @return {@code true} when the account is on file, mirroring {@code WS-ACCT-READ-STATUS} remaining zero
     */
    private boolean preFlightReadAccount(final Long accountId, final PreFlightLookups resolved) {
        if (!resolved.presentAccounts().contains(accountId)) {
            // :L245-:L247 INVALID KEY
            LOG.warn(MSG_INVALID_ACCOUNT_NUMBER_FOUND);
            return false;
        }
        // :L248-:L249 NOT INVALID KEY
        LOG.debug(MSG_SUCCESSFUL_READ_OF_ACCOUNT_FILE);
        return true;
    }

    /**
     * The record-image emission of {@code app/cbl/CBTRN01C.cbl:L168}, {@code DISPLAY DALYTRAN-RECORD}.
     *
     * <p><strong>Uncommented in the source</strong>, so unlike its counterpart at
     * {@code app/cbl/CBTRN02C.cbl:L207} it must be reproduced. It is routed to {@link #PARITY_LOG}, guarded
     * by a level check, and the card number is masked - the record's other fields carry no personal value.
     *
     * @param record the record just read; never {@code null} here, because the caller has already tested
     *     for end of file
     */
    private static void preFlightDisplayDalytranRecord(final DailyTransaction record) {
        if (!PARITY_LOG.isDebugEnabled()) {
            return;
        }
        PARITY_LOG.debug("DALYTRAN-RECORD id={} type={} category={} source={} card={} origTs={}",
                record.getTransactionId(), record.getTypeCode(), record.getCategoryCode(),
                record.getTransactionSource(), maskCardNumber(record.getCardNumber()), record.getOrigTs());
    }

    /**
     * {@code 0000-DALYTRAN-OPEN} of the pre-flight - {@code app/cbl/CBTRN01C.cbl:L252}-{@code :L270}.
     *
     * <p>The source performs this paragraph itself, from {@code app/cbl/CBTRN01C.cbl:L157}, and it is the
     * paragraph that issues {@code OPEN INPUT DALYTRAN-FILE} at {@code :L254}. <strong>So this method opens
     * the reader.</strong> Nothing else does: the pre-flight is a {@code tasklet}, so the step builder
     * registers no {@code ItemStream} and performs no open around it - unlike the posting step, which
     * registers the same reader through {@code .reader(...)} and is opened for that reason. A cursor of its
     * own is supplied rather than the step's, because this is a complete read of the whole file for
     * reporting and must start at the first record every time; restart positioning belongs to the posting
     * step, whose reader is a different instance because the bean is {@code @StepScope}.
     *
     * <p>The reachability probe is retained alongside the open, because it is what the source's guard
     * actually asserts and because it names the dataset in the failure the same way on both paths.
     *
     * @param reader the {@code DALYTRAN} reader this paragraph opens, and which
     *     {@link #closePreFlightDailyTransactionFile(DailyTransactionReader)} releases
     * @throws FatalProcessingException if the dataset is unreachable or the open fails
     */
    private void openPreFlightDailyTransactionFile(final DailyTransactionReader reader) {
        LOG.debug("{} pre-flight open probe using reader {}", DD_DALYTRAN, reader.getClass().getSimpleName());
        probeDataset(() -> dailyTransactionRepository.findAllByOrderByIngestSequenceAsc(openProbePage()),
                DD_DALYTRAN, MSG_PRE_FLIGHT_ERROR_OPENING_DALYTRAN, REASON_OPEN_FAILED,
                ABEND_CULPRIT_PRE_FLIGHT, OPEN_FAILURE_STATUS);
        // The probe above answers "is the dataset reachable"; it does not open the reader, and the reader
        // refuses a read until it has been opened. 0000-DALYTRAN-OPEN at app/cbl/CBTRN01C.cbl:L245-L262 is one
        // OPEN INPUT covering both questions, so both are performed here.
        //
        // A fresh context rather than the step's own: this step is read-only and CBTRN01C is not restartable -
        // it has no checkpoint of any kind - so the sequential scan below must always begin at the first
        // record. Passing the step context would let a restart of the posting step reposition the verification
        // scan, which would verify a suffix of the file and report the whole of it as verified.
        //
        // The reader is @StepScope, so the instance reached through the proxy here belongs to this step alone
        // and opening it cannot disturb the posting step's own instance.
        probeDataset(() -> reader.open(new ExecutionContext()), DD_DALYTRAN,
                MSG_PRE_FLIGHT_ERROR_OPENING_DALYTRAN, REASON_OPEN_FAILED, ABEND_CULPRIT_PRE_FLIGHT,
                OPEN_FAILURE_STATUS);
    }

    /**
     * {@code 0100-CUSTFILE-OPEN} - {@code app/cbl/CBTRN01C.cbl:L271}-{@code :L288}.
     *
     * <p><strong>Deviation 2.</strong> {@code app/jcl/POSTTRAN.jcl} supplies no {@code CUSTFILE} DD, so this
     * probe exists only because the pre-flight was folded into this job.
     *
     * @throws FatalProcessingException if the dataset is unreachable
     */
    private void openPreFlightCustomerFile() {
        probeDataset(() -> customerRepository.findAll(openProbePage(SORT_CUSTOMER_ID)), DD_CUSTFILE,
                MSG_PRE_FLIGHT_ERROR_OPENING_CUSTFILE, REASON_OPEN_FAILED, ABEND_CULPRIT_PRE_FLIGHT,
                OPEN_FAILURE_STATUS);
    }

    /**
     * {@code 0200-XREFFILE-OPEN} - {@code app/cbl/CBTRN01C.cbl:L289}-{@code :L306}.
     *
     * @throws FatalProcessingException if the dataset is unreachable
     */
    private void openPreFlightCrossReferenceFile() {
        probeDataset(() -> crossReferenceRepository.findAll(openProbePage(SORT_CARD_NUMBER)), DD_XREFFILE,
                MSG_PRE_FLIGHT_ERROR_OPENING_XREFFILE, REASON_OPEN_FAILED, ABEND_CULPRIT_PRE_FLIGHT,
                OPEN_FAILURE_STATUS);
    }

    /**
     * {@code 0300-CARDFILE-OPEN} - {@code app/cbl/CBTRN01C.cbl:L307}-{@code :L324}.
     *
     * <p><strong>Deviation 2.</strong> {@code app/jcl/POSTTRAN.jcl} supplies no {@code CARDFILE} DD either.
     *
     * @throws FatalProcessingException if the dataset is unreachable
     */
    private void openPreFlightCardFile() {
        probeDataset(() -> cardRepository.findAllByOrderByCardNumberAsc(openProbePage()), DD_CARDFILE,
                MSG_PRE_FLIGHT_ERROR_OPENING_CARDFILE, REASON_OPEN_FAILED, ABEND_CULPRIT_PRE_FLIGHT,
                OPEN_FAILURE_STATUS);
    }

    /**
     * {@code 0400-ACCTFILE-OPEN} - {@code app/cbl/CBTRN01C.cbl:L325}-{@code :L342}.
     *
     * @throws FatalProcessingException if the dataset is unreachable
     */
    private void openPreFlightAccountFile() {
        probeDataset(() -> accountRepository.findAll(openProbePage(SORT_ACCOUNT_ID)), DD_ACCTFILE,
                MSG_PRE_FLIGHT_ERROR_OPENING_ACCTFILE, REASON_OPEN_FAILED, ABEND_CULPRIT_PRE_FLIGHT,
                OPEN_FAILURE_STATUS);
    }

    /**
     * {@code 0500-TRANFILE-OPEN} - {@code app/cbl/CBTRN01C.cbl:L343}-{@code :L360}.
     *
     * @throws FatalProcessingException if the dataset is unreachable
     */
    private void openPreFlightTransactionFile() {
        probeDataset(() -> transactionRepository.findAll(openProbePage(SORT_TRANSACTION_ID)), DD_TRANFILE,
                MSG_PRE_FLIGHT_ERROR_OPENING_TRANFILE, REASON_OPEN_FAILED, ABEND_CULPRIT_PRE_FLIGHT,
                OPEN_FAILURE_STATUS);
    }

    /**
     * {@code 9000-DALYTRAN-CLOSE} - {@code app/cbl/CBTRN01C.cbl:L361}-{@code :L378}.
     *
     * <p>The source performs this paragraph itself, from {@code app/cbl/CBTRN01C.cbl:L188}, and it issues
     * {@code CLOSE DALYTRAN-FILE} at {@code :L363}. <strong>So this method closes the reader</strong> that
     * {@link #openPreFlightDailyTransactionFile(DailyTransactionReader)} opened. The pair has to balance:
     * nothing else releases it, because the pre-flight registers no {@code ItemStream}, and a reader left
     * open holds its cursor and its stream for the remainder of the step.
     *
     * <p>Emits {@link #MSG_PRE_FLIGHT_ERROR_CLOSING_DALYTRAN} on failure, which is the source's own
     * copy-and-paste defect naming the customer file - preserved, not repaired. See that constant.
     *
     * @param reader the {@code DALYTRAN} reader opened by
     *     {@link #openPreFlightDailyTransactionFile(DailyTransactionReader)}, closed here so the pair is
     *     symmetrical
     * @throws FatalProcessingException if the dataset cannot be released
     */
    private void closePreFlightDailyTransactionFile(final DailyTransactionReader reader) {
        // Symmetrical with the open above: the reader holds the scan position and, in fixed-width mode, an
        // open stream, so leaving it open would leak that stream for the life of the step scope.
        probeDataset(reader::close, DD_DALYTRAN, MSG_PRE_FLIGHT_ERROR_CLOSING_DALYTRAN,
                REASON_CLOSE_FAILED, ABEND_CULPRIT_PRE_FLIGHT, IO_ERROR_STATUS);
        guardClose(DD_DALYTRAN, MSG_PRE_FLIGHT_ERROR_CLOSING_DALYTRAN, ABEND_CULPRIT_PRE_FLIGHT);
    }

    /**
     * {@code 9100-CUSTFILE-CLOSE} - {@code app/cbl/CBTRN01C.cbl:L379}-{@code :L396}.
     *
     * @throws FatalProcessingException if the dataset cannot be released
     */
    private void closePreFlightCustomerFile() {
        guardClose(DD_CUSTFILE, MSG_PRE_FLIGHT_ERROR_CLOSING_CUSTFILE, ABEND_CULPRIT_PRE_FLIGHT);
    }

    /**
     * {@code 9200-XREFFILE-CLOSE} - {@code app/cbl/CBTRN01C.cbl:L397}-{@code :L414}.
     *
     * @throws FatalProcessingException if the dataset cannot be released
     */
    private void closePreFlightCrossReferenceFile() {
        guardClose(DD_XREFFILE, MSG_PRE_FLIGHT_ERROR_CLOSING_XREFFILE, ABEND_CULPRIT_PRE_FLIGHT);
    }

    /**
     * {@code 9300-CARDFILE-CLOSE} - {@code app/cbl/CBTRN01C.cbl:L415}-{@code :L432}.
     *
     * @throws FatalProcessingException if the dataset cannot be released
     */
    private void closePreFlightCardFile() {
        guardClose(DD_CARDFILE, MSG_PRE_FLIGHT_ERROR_CLOSING_CARDFILE, ABEND_CULPRIT_PRE_FLIGHT);
    }

    /**
     * {@code 9400-ACCTFILE-CLOSE} - {@code app/cbl/CBTRN01C.cbl:L433}-{@code :L450}.
     *
     * @throws FatalProcessingException if the dataset cannot be released
     */
    private void closePreFlightAccountFile() {
        guardClose(DD_ACCTFILE, MSG_PRE_FLIGHT_ERROR_CLOSING_ACCTFILE, ABEND_CULPRIT_PRE_FLIGHT);
    }

    /**
     * {@code 9500-TRANFILE-CLOSE} - {@code app/cbl/CBTRN01C.cbl:L451}-{@code :L468}.
     *
     * @throws FatalProcessingException if the dataset cannot be released
     */
    private void closePreFlightTransactionFile() {
        guardClose(DD_TRANFILE, MSG_PRE_FLIGHT_ERROR_CLOSING_TRANFILE, ABEND_CULPRIT_PRE_FLIGHT);
    }

    /**
     * {@code Z-DISPLAY-IO-STATUS} - {@code app/cbl/CBTRN01C.cbl:L476}-{@code :L489}.
     *
     * <p>The pre-flight's own copy of the four-character status renderer. Identical in behaviour to
     * {@code 9910-DISPLAY-IO-STATUS} of the posting program, and given its own method because it is a
     * separate paragraph in a separate program and the paragraph map must show both.
     *
     * @param ioStatus the two-character file status to render
     */
    private void preFlightDisplayIoStatus(final String ioStatus) {
        LOG.error(fileStatusMapper.displayIoStatus(ioStatus));
    }

    /**
     * {@code Z-ABEND-PROGRAM} - {@code app/cbl/CBTRN01C.cbl:L469}-{@code :L473}.
     *
     * <p>{@code DISPLAY 'ABENDING PROGRAM'} then {@code MOVE 999 TO ABCODE} then {@code CALL 'CEE3ABD'} -
     * the same abend code 999 the posting program uses, under the pre-flight's own culprit name. The
     * exception is returned rather than thrown so the caller's {@code throw} keeps the stack honest.
     *
     * @param abendMessage the failure text already emitted, carried into the exception payload
     * @param reason the abend reason, fitting {@code ABEND-REASON X(50)}
     * @param cause the underlying failure, preserved; may be {@code null} when there is none
     * @return the exception the caller must throw, never {@code null}
     */
    private static FatalProcessingException preFlightAbendProgram(final String abendMessage,
            final String reason, final Throwable cause) {

        return abendProgram(ABEND_CULPRIT_PRE_FLIGHT, abendMessage, reason, cause);
    }

    // app/cbl/CBTRN02C.cbl - the posting program. Twenty-seven paragraph labels. This class owns the open,
    // close, counter and exit-status skeleton; the record-level paragraphs belong to the sibling classes and
    // are delegated to, never duplicated. The full map, with the owner of each:
    //
    //   FILE-CONTROL              :28  - declaration; the six DDs map onto this class's repositories and the
    //                                    two writers, exactly as the pre-flight's FILE-CONTROL does
    //   0000-DALYTRAN-OPEN       :236  - openDailyTransactionFile()
    //   0100-TRANFILE-OPEN       :254  - openTransactionFile()
    //   0200-XREFFILE-OPEN       :273  - openCrossReferenceFile()
    //   0300-DALYREJS-OPEN       :291  - openRejectFile()
    //   0400-ACCTFILE-OPEN       :309  - openAccountFile()
    //   0500-TCATBALF-OPEN       :327  - openCategoryBalanceFile()
    //   1000-DALYTRAN-GET-NEXT   :345  - DELEGATED to com.cardemo.batch.readers.DailyTransactionReader
    //   1500-VALIDATE-TRAN       :370  - DELEGATED to TransactionPostingProcessor
    //   1500-A-LOOKUP-XREF       :380  - DELEGATED to TransactionPostingProcessor (reject code 100)
    //   1500-B-LOOKUP-ACCT       :393  - DELEGATED to TransactionPostingProcessor (codes 101, 102, 103)
    //   2000-POST-TRANSACTION    :424  - the three-write order, preserved across the processor and writer
    //                                    inside the one chunk transaction this class establishes
    //   2500-WRITE-REJECT-REC    :446  - DELEGATED to com.cardemo.batch.writers.RejectWriter
    //   2700-UPDATE-TCATBAL      :467  - DELEGATED to TransactionPostingProcessor (the '00' OR '23' upsert)
    //   2700-A-CREATE-TCATBAL-REC:503  - DELEGATED to TransactionPostingProcessor
    //   2700-B-UPDATE-TCATBAL-REC:526  - DELEGATED to TransactionPostingProcessor
    //   2800-UPDATE-ACCOUNT-REC  :545  - DELEGATED to TransactionPostingProcessor (where 109 is assigned
    //                                    and, as proved in the class documentation, never consumed)
    //   2900-WRITE-TRANSACTION-FILE:562 - DELEGATED to com.cardemo.batch.writers.TransactionWriter
    //   9000-DALYTRAN-CLOSE      :582  - closeDailyTransactionFile()
    //   9100-TRANFILE-CLOSE      :600  - closeTransactionFile()
    //   9200-XREFFILE-CLOSE      :619  - closeCrossReferenceFile()
    //   9300-DALYREJS-CLOSE      :637  - closeRejectFile()
    //   9400-ACCTFILE-CLOSE      :655  - closeAccountFile()
    //   9500-TCATBALF-CLOSE      :674  - closeCategoryBalanceFile()
    //   Z-GET-DB2-FORMAT-TIMESTAMP:692 - DELEGATED to TransactionPostingProcessor, which formats
    //                                    yyyy-MM-dd-HH.mm.ss.SS followed by the four literal zeros of
    //                                    MOVE '0000' TO DB2-REST at :701
    //   9999-ABEND-PROGRAM       :707  - abendProgram(), abend code 999
    //   9910-DISPLAY-IO-STATUS   :714  - displayIoStatus()

    /**
     * {@code 0000-DALYTRAN-OPEN} - {@code app/cbl/CBTRN02C.cbl:L236}-{@code :L253}.
     *
     * <p>{@code MOVE 8 TO APPL-RESULT}, {@code OPEN INPUT DALYTRAN-FILE}, then {@code '00'} yields
     * {@code APPL-RESULT 0} and anything else yields 12 followed by
     * {@code DISPLAY 'ERROR OPENING DALYTRAN'}, {@code 9910-DISPLAY-IO-STATUS} and
     * {@code 9999-ABEND-PROGRAM}. The guard is realised by {@link #probeDataset}, which routes the status
     * through {@link FileStatusMapper#applResultForGuard(String)} so the {@code APPL-AOK} test lives in one
     * place rather than being written out six times.
     *
     * @throws FatalProcessingException if the dataset is unreachable
     */
    private void openDailyTransactionFile() {
        probeDataset(() -> dailyTransactionRepository.findAllByOrderByIngestSequenceAsc(openProbePage()),
                DD_DALYTRAN, MSG_ERROR_OPENING_DALYTRAN, REASON_OPEN_FAILED, ABEND_CULPRIT_POSTING,
                OPEN_FAILURE_STATUS);
    }

    /**
     * {@code 0100-TRANFILE-OPEN} - {@code app/cbl/CBTRN02C.cbl:L254}-{@code :L272}.
     *
     * <p>The source opens {@code TRANSACT-FILE} for {@code OUTPUT}; the write itself belongs to
     * {@link TransactionWriter}, so what is asserted here is reachability.
     *
     * @throws FatalProcessingException if the dataset is unreachable
     */
    private void openTransactionFile() {
        probeDataset(() -> transactionRepository.findAll(openProbePage(SORT_TRANSACTION_ID)), DD_TRANFILE,
                MSG_ERROR_OPENING_TRANFILE, REASON_OPEN_FAILED, ABEND_CULPRIT_POSTING, OPEN_FAILURE_STATUS);
    }

    /**
     * {@code 0200-XREFFILE-OPEN} - {@code app/cbl/CBTRN02C.cbl:L273}-{@code :L290}.
     *
     * @throws FatalProcessingException if the dataset is unreachable
     */
    private void openCrossReferenceFile() {
        probeDataset(() -> crossReferenceRepository.findAll(openProbePage(SORT_CARD_NUMBER)), DD_XREFFILE,
                MSG_ERROR_OPENING_XREFFILE, REASON_OPEN_FAILED, ABEND_CULPRIT_POSTING, OPEN_FAILURE_STATUS);
    }

    /**
     * {@code 0300-DALYREJS-OPEN} - {@code app/cbl/CBTRN02C.cbl:L291}-{@code :L308}.
     *
     * <p><strong>Not available: there is nothing to probe.</strong> {@code app/jcl/POSTTRAN.jcl:L34}
     * declares {@code DISP=(NEW,CATLG,DELETE)} on a {@code (+1)} generation, so the target does not exist
     * until it is written - and its object-storage successor likewise does not exist until
     * {@link RejectWriter} emits the first reject. A probe would therefore have to create the object, which
     * would produce an empty generation on every clean run and change the observable output.
     *
     * <p>What the source's guard actually protects against - an unusable destination - is instead asserted
     * at construction: {@link RejectWriter} resolves {@code carddemo.aws.s3.batch-output-bucket} and
     * {@code carddemo.aws.s3.gdg-prefixes.daly-rejs} in its own constructor and refuses a blank value, so an
     * unconfigured destination fails at startup rather than at the first reject. The check here confirms the
     * collaborator was resolvable, which for a step-scoped proxy means the step context is live.
     *
     * @param rejectWriter the reject writer whose configuration stands in for the {@code OPEN OUTPUT}
     * @throws FatalProcessingException if the reject writer is not resolvable
     */
    private void openRejectFile(final RejectWriter rejectWriter) {
        if (rejectWriter == null) {
            LOG.error(MSG_ERROR_OPENING_DALYREJS);
            displayIoStatus(OPEN_FAILURE_STATUS);
            throw abendProgram(ABEND_CULPRIT_POSTING, MSG_ERROR_OPENING_DALYREJS, REASON_OPEN_FAILED, null);
        }
        LOG.debug("{} destination is configured; the generation is created on the first reject",
                DD_DALYREJS);
    }

    /**
     * {@code 0400-ACCTFILE-OPEN} - {@code app/cbl/CBTRN02C.cbl:L309}-{@code :L326}.
     *
     * @throws FatalProcessingException if the dataset is unreachable
     */
    private void openAccountFile() {
        probeDataset(() -> accountRepository.findAll(openProbePage(SORT_ACCOUNT_ID)), DD_ACCTFILE,
                MSG_ERROR_OPENING_ACCTFILE, REASON_OPEN_FAILED, ABEND_CULPRIT_POSTING, OPEN_FAILURE_STATUS);
    }

    /**
     * {@code 0500-TCATBALF-OPEN} - {@code app/cbl/CBTRN02C.cbl:L327}-{@code :L344}.
     *
     * <p>{@code TCATBALF} has no CICS file definition in {@code app/csd/CARDDEMO.CSD}, which is the evidence
     * that it is a batch-only dataset; it is reached here and nowhere in the online surface.
     *
     * @throws FatalProcessingException if the dataset is unreachable
     */
    private void openCategoryBalanceFile() {
        probeDataset(() -> categoryBalanceRepository
                        .findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc(openProbePage()),
                DD_TCATBALF,
                MSG_ERROR_OPENING_TCATBALF, REASON_OPEN_FAILED, ABEND_CULPRIT_POSTING, OPEN_FAILURE_STATUS);
    }

    /**
     * {@code 9000-DALYTRAN-CLOSE} - {@code app/cbl/CBTRN02C.cbl:L582}-{@code :L599}.
     *
     * @throws FatalProcessingException if the dataset cannot be released
     */
    private void closeDailyTransactionFile() {
        guardClose(DD_DALYTRAN, MSG_ERROR_CLOSING_DALYTRAN, ABEND_CULPRIT_POSTING);
    }

    /**
     * {@code 9100-TRANFILE-CLOSE} - {@code app/cbl/CBTRN02C.cbl:L600}-{@code :L618}.
     *
     * @throws FatalProcessingException if the dataset cannot be released
     */
    private void closeTransactionFile() {
        guardClose(DD_TRANFILE, MSG_ERROR_CLOSING_TRANFILE, ABEND_CULPRIT_POSTING);
    }

    /**
     * {@code 9200-XREFFILE-CLOSE} - {@code app/cbl/CBTRN02C.cbl:L619}-{@code :L636}.
     *
     * @throws FatalProcessingException if the dataset cannot be released
     */
    private void closeCrossReferenceFile() {
        guardClose(DD_XREFFILE, MSG_ERROR_CLOSING_XREFFILE, ABEND_CULPRIT_POSTING);
    }

    /**
     * {@code 9300-DALYREJS-CLOSE} - {@code app/cbl/CBTRN02C.cbl:L637}-{@code :L654}.
     *
     * <p>Closing the reject destination is where the source's buffered records reach the dataset. Here
     * {@link RejectWriter} has already emitted each generation synchronously per chunk, so there is no
     * deferred flush to force - which is why this paragraph asserts rather than acts.
     *
     * @throws FatalProcessingException if the destination reports a failure
     */
    private void closeRejectFile() {
        guardClose(DD_DALYREJS, MSG_ERROR_CLOSING_DALYREJS, ABEND_CULPRIT_POSTING);
    }

    /**
     * {@code 9400-ACCTFILE-CLOSE} - {@code app/cbl/CBTRN02C.cbl:L655}-{@code :L673}.
     *
     * @throws FatalProcessingException if the dataset cannot be released
     */
    private void closeAccountFile() {
        guardClose(DD_ACCTFILE, MSG_ERROR_CLOSING_ACCTFILE, ABEND_CULPRIT_POSTING);
    }

    /**
     * {@code 9500-TCATBALF-CLOSE} - {@code app/cbl/CBTRN02C.cbl:L674}-{@code :L691}.
     *
     * @throws FatalProcessingException if the dataset cannot be released
     */
    private void closeCategoryBalanceFile() {
        guardClose(DD_TCATBALF, MSG_ERROR_CLOSING_TCATBALF, ABEND_CULPRIT_POSTING);
    }

    /**
     * {@code 9910-DISPLAY-IO-STATUS} - {@code app/cbl/CBTRN02C.cbl:L714}-{@code :L727}.
     *
     * <p>Both arms of the source paragraph emit {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04} at
     * {@code :L721} and {@code :L725}, where <strong>{@code NNNN} is a fixed twenty-character literal, not a
     * placeholder</strong>, followed by the four-character rendering of the status. Both the literal and the
     * rendering belong to {@link FileStatus#DISPLAY_MESSAGE_PREFIX} and
     * {@link FileStatusMapper#displayIoStatus(String)}: this method references them and never redeclares,
     * reformats or repeats the prefix on the line, because Gate 1 compares it against the legacy baseline byte for
     * byte.
     *
     * @param ioStatus the two-character file status to render
     */
    private void displayIoStatus(final String ioStatus) {
        LOG.error(fileStatusMapper.displayIoStatus(ioStatus));
    }

    /**
     * {@code 9999-ABEND-PROGRAM} - {@code app/cbl/CBTRN02C.cbl:L707}-{@code :L711}.
     *
     * <p>The source displays {@code 'ABENDING PROGRAM'}, zeroes {@code TIMING}, moves <strong>999</strong>
     * into {@code ABCODE} and calls {@code 'CEE3ABD'}. Here the marker is emitted and a typed exception is
     * returned carrying abend code {@value FatalProcessingException#BATCH_ABEND_CODE}, which the flow maps
     * to return code {@value FatalProcessingException#BATCH_RETURN_CODE}.
     *
     * <p><strong>Nine-nine-nine, never nine-nine-nine-nine.</strong> The four-digit value belongs to the
     * CICS online path and conflating them would misreport which layer failed.
     *
     * <p>The exception is <strong>returned rather than thrown</strong> so that every call site reads
     * {@code throw abendProgram(...)} and the compiler can see the method never falls through. Nothing here
     * terminates the virtual machine: there is no termination call and no shutdown hook.
     * {@code TIMING} has no Java counterpart - it exists only to satisfy the {@code CEE3ABD} calling
     * convention - so it is documented rather than modelled.
     *
     * @param culprit the program name to record, {@value #ABEND_CULPRIT_POSTING} or
     *     {@value #ABEND_CULPRIT_PRE_FLIGHT}
     * @param abendMessage the failure text already emitted, carried into the exception payload
     * @param reason the abend reason, fitting {@code ABEND-REASON X(50)}
     * @param cause the underlying failure, preserved so the root cause is never lost; may be {@code null}
     * @return the exception the caller must throw, never {@code null}
     */
    private static FatalProcessingException abendProgram(final String culprit, final String abendMessage,
            final String reason, final Throwable cause) {

        LOG.error(MSG_ABENDING_PROGRAM);
        if (cause == null) {
            return new FatalProcessingException(ABEND_CODE, culprit, reason, abendMessage);
        }
        return new FatalProcessingException(ABEND_CODE, culprit, reason, abendMessage, cause);
    }

    // The universal I/O guard idiom, written once. Every OPEN, READ and CLOSE in both source programs has
    // the identical shape: MOVE 8 TO APPL-RESULT, do the verb, map '00' to 0 and anything else to 12, then
    // IF APPL-AOK CONTINUE ELSE display, render the status and abend. Recognising it as one idiom rather
    // than as thirty-odd individual checks is what lets a single mapper own the translation.

    /**
     * A dataset access that either succeeds or raises a store failure.
     *
     * <p>Exists so {@link #probeDataset} can take the access as a parameter without any call site needing a
     * checked exception or a shared mutable holder. Deliberately not {@link java.util.function.Supplier},
     * because the result is discarded and naming the type after its role documents the intent.
     */
    @FunctionalInterface
    private interface DatasetProbe {

        /**
         * Performs the access. The result is intentionally unused: reachability is the whole assertion.
         *
         * @throws DataAccessException if the dataset is unreachable
         */
        void access();
    }

    /**
     * The {@code OPEN} guard, shared by all eleven open paragraphs across the two programs.
     *
     * <p>Runs the probe, translates the outcome into a two-character file status, and then applies the
     * source's {@code IF APPL-AOK} test through {@link FileStatusMapper#applResultForGuard(String)}. A
     * failure emits the paragraph's own literal, renders the status through the four-character renderer, and
     * abends - the same three statements, in the same order, that every source paragraph performs.
     *
     * <p>A typed {@link CardDemoException} raised by a collaborator is rethrown untouched, because it already
     * carries a translated status and re-wrapping it would bury the original diagnosis. Every other store
     * failure is wrapped with its cause preserved. <strong>Nothing is swallowed and no catch block is
     * empty.</strong>
     *
     * @param probe the dataset access that proves reachability
     * @param ddName the DD name, for the diagnostic
     * @param failureMessage the paragraph's own {@code DISPLAY} literal
     * @param reason the abend reason
     * @param culprit the program name to record on the abend
     * @param failureStatus the file status a failed access reports
     * @throws FatalProcessingException if the probe fails
     */
    private void probeDataset(final DatasetProbe probe, final String ddName, final String failureMessage,
            final String reason, final String culprit, final String failureStatus) {

        String ioStatus = SUCCESS_STATUS;
        RuntimeException failure = null;
        try {
            probe.access();
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final DataAccessException cause) {
            ioStatus = failureStatus;
            failure = cause;
        }
        guardFileOperation(ioStatus, ddName, failureMessage, reason, culprit, failure);
    }

    /**
     * The {@code CLOSE} guard, shared by all twelve close paragraphs across the two programs.
     *
     * <p>Spring Data and the object-storage gateway have no explicit close: the persistence context is
     * released with the step's transaction and each generation was emitted synchronously. So the source's
     * {@code CLOSE} has no physical counterpart, and what remains of the paragraph is its guard - which
     * always sees success. That is stated openly rather than dressed up as a real close, and the guard is
     * still routed through the mapper so that the one code path exists and is exercised.
     *
     * @param ddName the DD name, for the diagnostic
     * @param failureMessage the paragraph's own {@code DISPLAY} literal, emitted only on failure
     * @param culprit the program name to record on the abend
     * @throws FatalProcessingException if the mapper reports the close status as a failure
     */
    private void guardClose(final String ddName, final String failureMessage, final String culprit) {
        guardFileOperation(SUCCESS_STATUS, ddName, failureMessage, REASON_CLOSE_FAILED, culprit, null);
    }

    /**
     * Names the operation a reason constant describes, for the success branch of the I/O guard.
     *
     * <p>The guard is handed an abend <em>reason</em> because that is what it needs on the failure branch -
     * {@code "OPEN FAILED"}, {@code "CLOSE FAILED"} - and the success branch used to log that same constant.
     * The result read {@code "DALYTRAN OPEN FAILED completed with status 00"}: a line asserting a failure and a
     * success in the same breath, emitted 25 times in a single-record run. Nothing was wrong with the run; the
     * sentence was wrong, and a diagnostic that reports a healthy operation as a failure trains a reader to
     * ignore it.
     *
     * <p>The verb is derived from the reason rather than passed as a second argument so that the two can never
     * disagree at a call site, and so no caller has to be edited to add one. A reason that does not describe a
     * failed operation is returned unchanged, which keeps the guard usable for outcomes that are not verbs.
     *
     * <p>Pure function of its argument.
     *
     * @param reason the abend reason the caller supplies for the failure branch
     * @return the bare operation, or the reason unchanged when it names no operation
     */
    private static String succeededOperation(final String reason) {
        return reason != null && reason.endsWith(FAILED_REASON_SUFFIX)
                ? reason.substring(0, reason.length() - FAILED_REASON_SUFFIX.length())
                : reason;
    }

    /**
     * The shared tail of every guard: {@code IF APPL-AOK CONTINUE ELSE display, render, abend}.
     *
     * @param ioStatus the two-character file status the operation reported
     * @param ddName the DD name, for the diagnostic
     * @param failureMessage the paragraph's own {@code DISPLAY} literal
     * @param reason the abend reason
     * @param culprit the program name to record on the abend
     * @param cause the underlying failure, or {@code null} when there is none
     * @throws FatalProcessingException when the status is not {@code APPL-AOK}
     */
    private void guardFileOperation(final String ioStatus, final String ddName,
            final String failureMessage, final String reason, final String culprit,
            final Throwable cause) {

        if (fileStatusMapper.applResultForGuard(ioStatus) == FileStatusMapper.APPL_AOK) {
            // IF APPL-AOK CONTINUE. The source does nothing here; the trace is a target-side addition that
            // replaces instrumentation neither program has.
            LOG.debug("{} {} completed with status {}", ddName, succeededOperation(reason), ioStatus);
            return;
        }
        LOG.error(failureMessage);
        displayIoStatus(ioStatus);
        throw abendProgram(culprit, failureMessage, reason, cause);
    }

    /**
     * Adds the rejected records of one chunk to the {@code records processed} counter.
     *
     * <p><strong>Why the rejects are added to the existing meter.</strong> Counting only the posted records
     * would make the {@code records processed} meter disagree with the
     * {@value #MSG_TRANSACTIONS_PROCESSED} literal this class emits, under-reporting by exactly the number of
     * rejected records, so a dashboard built on the meter would contradict the log.
     *
     * <p>Closes a real gap rather than duplicating an existing count. {@code WS-TRANSACTION-COUNT} is
     * incremented at {@code app/cbl/CBTRN02C.cbl:L206}, <em>before</em> validation runs at {@code :L210}, so
     * the legacy figure is posted plus rejected. On this side
     * {@code src/main/java/com/cardemo/batch/writers/TransactionWriter.java:L882} calls
     * {@code countRecordsProcessed} with the size of the posted list only, and
     * {@code src/main/java/com/cardemo/batch/writers/RejectWriter.java:L1574} calls
     * {@code countRecordRejected} but <strong>not</strong> {@code countRecordsProcessed} - verified by
     * reading both files. Rejected records were therefore absent from the meter, leaving it disagreeing with
     * the {@value #MSG_TRANSACTIONS_PROCESSED} literal this class emits. Adding exactly the rejects here
     * makes the two agree and <strong>cannot</strong> count a record twice, because no other class on the
     * path counts a rejected record as processed.
     *
     * <p>Uses an existing instrument. No fifth meter, no timer, no gauge and no distribution summary is
     * introduced, and no tag is attached, so cardinality is unchanged - the reject-code tag belongs to
     * {@code countRecordRejected} and is applied by {@link RejectWriter} where the code is known.
     *
     * @param rejectedCount the number of rejected records in the chunk; zero is a no-op rather than a
     *     zero-valued increment, so an all-posted chunk touches no meter here
     */
    private void countRejectedRecordsAsProcessed(final int rejectedCount) {
        if (rejectedCount <= 0) {
            return;
        }
        metricsConfig.countRecordsProcessed(rejectedCount);
    }

    /**
     * The page request used by an open probe whose repository method already names its own ordering.
     *
     * <p>Carries no {@link Sort} because the three call sites that use it invoke a derived finder whose
     * method name ends in {@code OrderBy...Asc} - {@code findAllByOrderByIngestSequenceAsc},
     * {@code findAllByOrderByCardNumberAsc} and
     * {@code findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc}. The ordering is therefore already fixed by
     * the query, and adding a second {@code Sort} here would emit a redundant {@code ORDER BY} term.
     * Probes that go through the unordered {@code findAll(Pageable)} take
     * {@link #openProbePage(String)} instead, so that <strong>every</strong> probe in this class is
     * deterministically ordered.
     *
     * @return a one-row page request, never {@code null}
     */
    private static PageRequest openProbePage() {
        return PageRequest.of(0, OPEN_PROBE_PAGE_SIZE);
    }

    /**
     * The page request used by an open probe that goes through the unordered {@code findAll(Pageable)}.
     *
     * <p><strong>Why the ordering travels in the page request.</strong> An unordered
     * {@code findAll(PageRequest.of(0, 1))} would leave the emitted query dependent on whatever row order the
     * store happened to return, so the ordering is passed through the page request, as below.
     *
     * <p>Sorts ascending on the entity's identifier property so the probe is deterministic rather than
     * dependent on whatever row order the store happens to return. Rule 1 clause A puts determinism ahead of
     * cleverness, and the sibling job in this package establishes the convention - see
     * {@code src/main/java/com/cardemo/batch/jobs/InterestCalculationJob.java:L1408}-{@code :L1411}, whose
     * probe page request likewise carries a {@code Sort} - so clause C's instruction not to fight the
     * existing style points the same way. The four repositories reached this way expose no unconditional
     * ordered {@code findAll} variant, and adding one is out of this file's scope, so the ordering is
     * supplied through the {@link org.springframework.data.domain.Pageable} instead.
     *
     * @param identifierProperty the entity's identifier property, one of {@value #SORT_CUSTOMER_ID},
     *     {@value #SORT_CARD_NUMBER}, {@value #SORT_ACCOUNT_ID} or {@value #SORT_TRANSACTION_ID}
     * @return a one-row, ascending-ordered page request, never {@code null}
     */
    private static PageRequest openProbePage(final String identifierProperty) {
        return PageRequest.of(0, OPEN_PROBE_PAGE_SIZE, Sort.by(Sort.Direction.ASC, identifierProperty));
    }

    /**
     * Masks all but the last {@value #CARD_NUMBER_VISIBLE_SUFFIX} digits of a card number.
     *
     * <p>Applied to every card number this class can emit, so that no log line - not even one on the parity
     * logger, and not even at {@code DEBUG} - can reconstruct a primary account number. A value shorter than
     * the visible suffix is masked in full rather than exposed, and a {@code null} or blank value is reported
     * as such instead of producing the literal {@code "null"}.
     *
     * @param cardNumber the raw card number; may be {@code null} or blank
     * @return the masked projection, never {@code null}
     */
    private static String maskCardNumber(final String cardNumber) {
        if (cardNumber == null || cardNumber.isBlank()) {
            return "(absent)";
        }
        final String trimmed = cardNumber.strip();
        if (trimmed.length() <= CARD_NUMBER_VISIBLE_SUFFIX) {
            return String.valueOf(CARD_NUMBER_MASK).repeat(trimmed.length());
        }
        final int maskedLength = trimmed.length() - CARD_NUMBER_VISIBLE_SUFFIX;
        return String.valueOf(CARD_NUMBER_MASK).repeat(maskedLength) + trimmed.substring(maskedLength);
    }

    /**
     * Masks all but the last {@value #IDENTIFIER_VISIBLE_SUFFIX} digits of an account or customer identifier.
     *
     * <p>{@code ACCT-ID} is {@code PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy} and {@code CUST-ID} is
     * {@code PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy}. Both are durable identifiers for a real
     * cardholder: an account number is what a caller quotes to be recognised, and a nine-digit customer
     * number sits in the same shape as the {@code CUST-SSN} that
     * {@code src/main/resources/logback-spring.xml} deliberately refuses to redact by pattern, because a
     * blanket nine-digit rule would also redact the end-of-run counters. That is precisely why these values
     * are masked <em>here</em>, at the call site, where the field is known: an encoder rule cannot tell a
     * customer identifier from {@code WS-TRANSACTION-COUNT}, and this method can.
     *
     * <p>The last four digits are kept so an operator can still correlate two lines about the same account
     * without being handed the identifier, which is the same trade the card-number projection makes - and
     * {@link #CARD_NUMBER_MASK} is reused as the glyph so every masked value in this job's output looks
     * alike rather than inventing a second convention.
     *
     * @param identifier the raw account or customer identifier; may be {@code null}
     * @return the masked projection, never {@code null} and never the literal {@code "null"}
     */
    private static String maskIdentifier(final Long identifier) {
        if (identifier == null) {
            return "(absent)";
        }
        final String digits = Long.toString(identifier);
        if (digits.length() <= IDENTIFIER_VISIBLE_SUFFIX) {
            return String.valueOf(CARD_NUMBER_MASK).repeat(digits.length());
        }
        final int maskedLength = digits.length() - IDENTIFIER_VISIBLE_SUFFIX;
        return String.valueOf(CARD_NUMBER_MASK).repeat(maskedLength) + digits.substring(maskedLength);
    }

    /**
     * Masks all but the last {@value #ACCOUNT_ID_VISIBLE_SUFFIX} digits of an account identifier.
     *
     * <p>Applied to every account identifier this class can emit, so that no log line - not even one on the
     * parity logger, and not even at {@code DEBUG} - can reconstruct an account number. The identifier is
     * rendered at its declared {@code PIC 9(11)} width first, so that the masked prefix has a constant
     * length and a short identifier is not accidentally disclosed in full by being shorter than the visible
     * suffix. A {@code null} is reported as such rather than producing the literal {@code "null"}.
     *
     * <p>Separate from {@link #maskCardNumber(String)} rather than shared with it, and deliberately so. The
     * two take different types - a {@code Long} keyed on {@code PIC 9(11)} against space-padded
     * {@code PIC X(16)} text - and the card-number rule has to cope with the blank filler rows and trailing
     * padding a fixed-width text field carries, which a numeric identifier never has. Folding them into one
     * routine would mean one of the two callers passing a converted value through a rule written for the
     * other, which is how a masking rule ends up applied to the wrong extent.
     *
     * @param accountId the account identifier; may be {@code null}
     * @return the masked projection, never {@code null}
     */
    private static String maskAccountIdentifier(final Long accountId) {
        if (accountId == null) {
            return "(absent)";
        }
        // PIC 9(11): zero-padded to its declared width, so the mask always covers seven digits.
        final String rendered = String.format(Locale.ROOT, "%011d", accountId);
        final int maskedLength = rendered.length() - ACCOUNT_ID_VISIBLE_SUFFIX;
        return String.valueOf(CARD_NUMBER_MASK).repeat(maskedLength) + rendered.substring(maskedLength);
    }

    /**
     * Renders a counter the way a {@code PIC 9(09)} {@code DISPLAY} renders it: nine zero-padded digits.
     *
     * <p>{@link Locale#ROOT} is mandatory, not decorative - a locale-sensitive format could introduce a
     * grouping separator or a non-ASCII digit and put a diff into the parity comparison. A negative value
     * cannot arise from a counter that only increments, and is rendered as zero rather than with a sign,
     * because {@code PIC 9(09)} is unsigned and would display the absolute value.
     *
     * @param value the counter value
     * @return exactly {@value #COUNTER_DIGITS} digits, never {@code null}
     */
    private static String renderCounter(final long value) {
        return String.format(Locale.ROOT, COUNTER_FORMAT, Math.max(0L, value));
    }

    /**
     * Reads a counter out of the step execution context, treating an absent entry as zero.
     *
     * <p>Absent is the normal state before the first chunk writes, and on a run whose file was empty it
     * stays absent for the whole step - so it is handled explicitly rather than allowed to throw.
     *
     * @param stepExecution the step whose context holds the counter
     * @param key the context key
     * @return the counter value, or zero when the entry is absent
     */
    private static long readCounter(final StepExecution stepExecution, final String key) {
        final ExecutionContext context = stepExecution.getExecutionContext();
        if (!context.containsKey(key)) {
            return 0L;
        }
        return context.getLong(key);
    }

    /**
     * Adds to a counter held in the step execution context.
     *
     * <p>The context rather than a field, because this configuration is a singleton and a field would leak
     * one execution's count into the next. The context is per-execution and is persisted with the step, so
     * the count also survives a restart.
     *
     * @param stepExecution the step whose context holds the counter
     * @param key the context key
     * @param increment how much to add; zero is permitted and is a no-op in effect
     */
    private static void addToCounter(final StepExecution stepExecution, final String key,
            final long increment) {

        stepExecution.getExecutionContext().putLong(key, readCounter(stepExecution, key) + increment);
    }

    /**
     * Resolves the step execution bound to the calling thread.
     *
     * <p>{@link StepSynchronizationManager} is used rather than a captured field because it is thread-bound,
     * so the counters remain correct when several executions share a pool. An absent context means the
     * writer was invoked outside a step, which is a wiring fault rather than a data fault and so abends
     * instead of being tolerated.
     *
     * @return the live step execution, never {@code null}
     * @throws FatalProcessingException if no step context is bound to this thread
     */
    private static StepExecution requireStepExecution() {
        final StepContext context = StepSynchronizationManager.getContext();
        if (context == null) {
            throw abendProgram(ABEND_CULPRIT_POSTING, "NO STEP CONTEXT AVAILABLE", REASON_NO_STEP_CONTEXT,
                    null);
        }
        return context.getStepExecution();
    }

    /**
     * Copies the generation keys the two writers published from the step execution context into the job
     * execution context.
     *
     * <p><strong>This is the "never re-resolve latest" obligation.</strong> {@code app/jcl/POSTTRAN.jcl:L38}
     * writes {@code DSN=AWS.M2.CARDDEMO.DALYREJS(+1)}, a <em>relative</em> generation reference that the
     * catalogue resolves once, at allocation, and that every later step in the job stream then refers to as
     * {@code (0)}. The object-storage equivalent of resolving it again by listing for the greatest prefix
     * would be a different object if another run interleaved. So the concrete key each writer created is
     * promoted here, and a consumer reads the key that <em>was</em> created.
     *
     * <p>{@link RejectWriter} and {@link TransactionWriter} own the key derivation and publish into the step
     * context under their own constants; this method only promotes, so there is exactly one place where a
     * key is composed. An absent entry is normal - a run with no rejects creates no reject generation - and
     * is skipped rather than defaulted.
     *
     * @param stepExecution the finished posting step, whose context holds the published keys
     */
    private static void promoteGenerationKeys(final StepExecution stepExecution) {
        final JobExecution jobExecution = stepExecution.getJobExecution();
        if (jobExecution == null) {
            return;
        }
        final ExecutionContext from = stepExecution.getExecutionContext();
        final ExecutionContext to = jobExecution.getExecutionContext();
        for (final String key : List.of(
                RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY,
                RejectWriter.REJECT_GENERATION_PREFIX_CONTEXT_KEY,
                RejectWriter.REJECT_RECORD_COUNT_CONTEXT_KEY,
                RejectWriter.REJECT_OBJECT_KEYS_COUNT_ENTRY,
                TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY,
                TransactionWriter.OBJECT_KEYS_COUNT_ENTRY,
                PROCESSED_COUNT_CONTEXT_ENTRY,
                REJECT_COUNT_CONTEXT_ENTRY)) {

            if (from.containsKey(key)) {
                to.put(key, from.get(key));
            }
        }
    }

    /**
     * Establishes this run's diagnostic context on the calling thread and records what it displaced.
     *
     * <p>A correlation identifier is generated only when the thread has none, so a job launched from inside a
     * traced request keeps that request's identifier and the whole causal chain shares one.
     * {@link CorrelationIdFilter#currentCorrelationId()} is used for the read rather than a bare MDC lookup
     * because it validates: an inherited value that could inject into the log format is treated as absent and
     * replaced rather than propagated.
     *
     * <p>{@code traceId} and {@code spanId} are deliberately <strong>not</strong> written here. The tracing
     * bridge populates them from real span identity, and setting them by hand would replace that identity
     * with a fabrication - which would be worse than leaving them to the mechanism that owns them.
     *
     * <p>Side effects: mutates two diagnostic context entries and pushes one entry onto
     * {@link #DIAGNOSTIC_SNAPSHOTS} for the calling thread. Always paired with
     * {@link #restoreDiagnosticContext(long)} in a {@code finally} block.
     *
     * @param jobInstanceId the Spring Batch instance identifier of the starting execution
     * @param jobExecutionId the identifier of the starting execution, carried so that the matching restore
     *     can prove it is undoing its own displacement rather than someone else's
     */
    private static void establishDiagnosticContext(final long jobInstanceId, final long jobExecutionId) {
        final String previousJobInstanceId =
                CorrelationIdFilter.propagateJobInstanceId(Long.toString(jobInstanceId));
        final String previousCorrelationId = CorrelationIdFilter.currentCorrelationId();
        if (previousCorrelationId == null) {
            CorrelationIdFilter.propagate(UUID.randomUUID().toString());
        }
        // computeIfAbsent has no ThreadLocal equivalent, so the deque is created on first use for this
        // thread. Confined to one thread throughout, which is why an unsynchronised ArrayDeque is correct.
        Deque<DiagnosticContextSnapshot> displaced = DIAGNOSTIC_SNAPSHOTS.get();
        if (displaced == null) {
            displaced = new ArrayDeque<>();
            DIAGNOSTIC_SNAPSHOTS.set(displaced);
        }
        displaced.push(
                new DiagnosticContextSnapshot(jobExecutionId, previousJobInstanceId, previousCorrelationId));
    }

    /**
     * Puts both diagnostic context entries back exactly as {@link #establishDiagnosticContext(long, long)}
     * found them, undoing this invocation's displacement and no other.
     *
     * <p>Restoring rather than removing is the point. An entry that was absent is removed, so nothing leaks
     * onto the next job to borrow this pooled thread; an entry that existed is put back, so context owned by
     * an outer scope survives. A blanket removal satisfies the first obligation and violates the second.
     *
     * <p>The most recent displacement is the one this invocation owns, because pushes and pops are paired
     * and nest. The execution identifier is compared to confirm that, and a mismatch is reported rather than
     * hidden: it would mean pushes and pops had been interleaved out of order, which no supported launcher
     * does, and silently restoring the wrong values would leave a correlation identifier attributed to the
     * wrong run. The pop still happens, because leaving the entry in place would strand it on the thread.
     *
     * <p>An empty or absent stack is tolerated by doing nothing. That is reachable rather than defensive
     * padding: if the listener abends before the push completes, this thread's context was never modified,
     * so there is nothing to undo.
     *
     * <p>Side effects: mutates two diagnostic context entries, pops one entry, and clears
     * {@link #DIAGNOSTIC_SNAPSHOTS} for the calling thread once its last entry is gone.
     *
     * @param jobExecutionId the identifier of the finishing execution, matched against the entry being popped
     */
    private static void restoreDiagnosticContext(final long jobExecutionId) {
        final Deque<DiagnosticContextSnapshot> displaced = DIAGNOSTIC_SNAPSHOTS.get();
        if (displaced == null || displaced.isEmpty()) {
            return;
        }
        final DiagnosticContextSnapshot snapshot = displaced.pop();
        try {
            if (snapshot.jobExecutionId() != jobExecutionId) {
                LOG.warn("Diagnostic context restored out of order: expected execution {}, found {}",
                        jobExecutionId, snapshot.jobExecutionId());
            }
            CorrelationIdFilter.propagateJobInstanceId(snapshot.jobInstanceId());
            CorrelationIdFilter.propagate(snapshot.correlationId());
        } finally {
            if (displaced.isEmpty()) {
                DIAGNOSTIC_SNAPSHOTS.remove();
            }
        }
    }

    /**
     * Publishes the abend exit status on the job when an abend was recorded among its failures.
     *
     * @param jobExecution the finishing execution
     */
    private static void applyAbendExitStatus(final JobExecution jobExecution) {
        for (final Throwable failure : jobExecution.getAllFailureExceptions()) {
            if (failure instanceof FatalProcessingException) {
                jobExecution.setExitStatus(ABEND_EXIT_STATUS);
                return;
            }
        }
    }

    /**
     * Reports whether an abend was recorded against the job or the step that has just finished.
     *
     * <p>Both are inspected because Spring Batch records a step's failure on the step and, depending on how
     * the failure propagated, may or may not also record it on the job.
     *
     * @param jobExecution the running execution
     * @param stepExecution the step that has just finished; may be {@code null}
     * @return {@code true} when a {@link FatalProcessingException} appears among either set of failures
     */
    private static boolean containsAbend(final JobExecution jobExecution,
            final StepExecution stepExecution) {

        for (final Throwable failure : jobExecution.getAllFailureExceptions()) {
            if (failure instanceof FatalProcessingException) {
                return true;
            }
        }
        if (stepExecution == null) {
            return false;
        }
        for (final Throwable failure : stepExecution.getFailureExceptions()) {
            if (failure instanceof FatalProcessingException) {
                return true;
            }
        }
        return false;
    }

    /**
     * What the diagnostic context held before this job replaced it.
     *
     * <p>A record, so the snapshot is immutable and the stack that holds it is shared static state without
     * being mutable static state. A {@code null} string component means the entry was absent and must be
     * removed rather than restored.
     *
     * @param jobExecutionId the execution that displaced these values, so the matching restore can prove it
     *     is undoing its own displacement even when several are stacked on one thread
     * @param jobInstanceId the displaced job instance identifier, or {@code null} if there was none
     * @param correlationId the displaced correlation identifier, or {@code null} if there was none
     */
    private record DiagnosticContextSnapshot(
            long jobExecutionId, String jobInstanceId, String correlationId) {
    }

    /**
     * The {@code IF}/{@code ELSE} of {@code app/cbl/CBTRN02C.cbl:L211}-{@code :L216}, as a chunk writer.
     *
     * <pre>
     * IF WS-VALIDATION-FAIL-REASON = 0
     *   PERFORM 2000-POST-TRANSACTION
     * ELSE
     *   ADD 1 TO WS-REJECT-COUNT
     *   PERFORM 2500-WRITE-REJECT-REC
     * END-IF
     * </pre>
     *
     * <p>Each outcome goes to exactly one destination and <strong>never to both</strong>, which is the whole
     * content of the source's {@code ELSE}. A {@link TransactionPostingProcessor.PostingResult} that is
     * neither posted nor rejected cannot be constructed - the record's own constructor rejects it - but the
     * case is still handled here rather than assumed away, because a silent fall-through would lose a record.
     *
     * <p><strong>Batching, and what it does and does not change.</strong> The source handles one record at a
     * time, so its posted and rejected writes interleave; a chunk necessarily serialises them into two
     * groups. Order <em>within</em> each group is preserved exactly, which is what matters, because the two
     * destinations are disjoint - the transaction table and its object versus the {@code DALYREJS}
     * generation - and no record's outcome depends on another's. So the relative order of the two groups is
     * not observable in either output. Posted records are written first, matching the source's {@code IF}
     * arm preceding its {@code ELSE} arm.
     *
     * <p>Both delegates are called <strong>once per chunk</strong>, never once per record. That is required
     * rather than merely efficient: {@link TransactionWriter#write(Chunk)} emits one object keyed on the
     * step's write count, so per-record calls would produce one object per record and destroy the generation
     * geometry.
     *
     * <p>Neither counter is incremented here for metrics: both delegates already call
     * {@link MetricsConfig} themselves, and counting again would count every figure twice. What is incremented
     * here are the two <em>legacy</em> counters in the step execution context, which no delegate owns.
     *
     * <p>Declared as an inner class rather than a bean, so this folder contributes no extra singleton, and
     * it holds <strong>no mutable state</strong> - the per-execution counts live in the step execution
     * context.
     */
    private final class PostingOutcomeWriter
            implements ItemStreamWriter<TransactionPostingProcessor.PostingResult>, StepExecutionListener {

        /** Paragraph {@code 2900-WRITE-TRANSACTION-FILE}, and the 350-byte record geometry. */
        private final TransactionWriter postedWriter;

        /** Paragraph {@code 2500-WRITE-REJECT-REC}, and the 430-byte record geometry. */
        private final RejectWriter rejectedWriter;

        /**
         * Creates the writer over the two step-scoped delegates.
         *
         * @param postedWriter the transaction writer, already validated by the caller
         * @param rejectedWriter the reject writer, already validated by the caller
         */
        private PostingOutcomeWriter(final TransactionWriter postedWriter,
                final RejectWriter rejectedWriter) {

            this.postedWriter = postedWriter;
            this.rejectedWriter = rejectedWriter;
        }

        /**
         * {@inheritDoc}
         *
         * <p><strong>Finding, severity High - remediated here.</strong> Forwards the step execution to
         * {@link TransactionWriter}, which implements {@link StepExecutionListener} itself and captures the
         * execution in {@code beforeStep} so that it can scope its object key on the job instance. Spring
         * Batch registers the reader, processor and writer as listeners when they implement a listener
         * interface, but it inspects only the objects handed to {@code reader}, {@code processor} and
         * {@code writer} - and the object handed to {@code writer} here is this wrapper. Wrapping the
         * transaction writer therefore hid it from that registration, its {@code beforeStep} never ran, and
         * the first write of every run abended with "no step execution was captured before the first write",
         * which made the posting step - and so the whole job - unable to complete on any input.
         *
         * <p>The remediation is to give the wrapper the lifecycle responsibility that comes with wrapping:
         * a composite owns its delegates' callbacks, which is the same reason the framework's own
         * {@code CompositeItemWriter} implements {@code ItemStream} and forwards {@code open}, {@code update}
         * and {@code close}. Because this class now implements the interface, the framework registers
         * <em>it</em> automatically, and it passes the execution on. {@link RejectWriter} needs no forwarding:
         * it implements no listener interface, and its per-step configuration is done by
         * {@link PostTranStepListener#beforeStep(StepExecution)} through {@code openRejectFile}.
         *
         * <p>Purpose: wire the delegate's step lifecycle. Inputs: the framework's step execution. Output:
         * none. Side effects: replaces the execution captured by the transaction writer. Error modes: none of
         * its own; a {@code null} execution is passed through and reported by the delegate at its first write,
         * where the diagnostic can name the step.
         *
         * @param stepExecution the step execution supplied by the framework
         */
        @Override
        public void beforeStep(final StepExecution stepExecution) {
            postedWriter.beforeStep(stepExecution);
        }

        /**
         * {@inheritDoc}
         *
         * <p><strong>Signature note.</strong> Spring Batch 5 replaced {@code write(List)} with
         * {@code write(Chunk)}; the pinned version is 5.2.4, so a {@link java.util.List} parameter would not
         * override the interface method and {@code @Override} would fail the build outright.
         *
         * @param chunk the processed outcomes for this commit interval; may be empty but never {@code null}
         * @throws Exception if either delegate fails, which rolls the whole chunk back - the atomicity of
         *     deviation 1
         */
        @Override
        public void write(final Chunk<? extends TransactionPostingProcessor.PostingResult> chunk)
                throws Exception {

            if (chunk == null || chunk.isEmpty()) {
                // A chunk can legitimately be empty on the last commit interval of an empty file. Handled
                // explicitly, per clause B, rather than left to throw inside a delegate.
                return;
            }

            final StepExecution stepExecution = requireStepExecution();
            final List<Transaction> posted = new ArrayList<>(chunk.size());
            final List<RejectWriter.RejectedTransaction> rejected = new ArrayList<>(chunk.size());

            for (final TransactionPostingProcessor.PostingResult outcome : chunk) {
                if (outcome == null) {
                    throw abendProgram(ABEND_CULPRIT_POSTING, "NULL POSTING OUTCOME IN CHUNK",
                            REASON_UNCLASSIFIED_OUTCOME, null);
                }
                if (outcome.isPosted()) {
                    // :L211-:L212 the IF arm
                    posted.add(outcome.postedTransaction());
                } else if (outcome.isRejected()) {
                    // :L213-:L215 the ELSE arm
                    rejected.add(new RejectWriter.RejectedTransaction(
                            outcome.source(), outcome.rejectCode()));
                } else {
                    throw abendProgram(ABEND_CULPRIT_POSTING,
                            "POSTING OUTCOME IS NEITHER POSTED NOR REJECTED",
                            REASON_UNCLASSIFIED_OUTCOME, null);
                }
            }

            // :L206 ADD 1 TO WS-TRANSACTION-COUNT, applied for every record the loop saw, posted or not.
            addToCounter(stepExecution, PROCESSED_COUNT_CONTEXT_ENTRY, chunk.size());
            // :L214 ADD 1 TO WS-REJECT-COUNT, applied only on the ELSE arm.
            addToCounter(stepExecution, REJECT_COUNT_CONTEXT_ENTRY, rejected.size());
            countRejectedRecordsAsProcessed(rejected.size());

            if (!posted.isEmpty()) {
                postedWriter.write(new Chunk<>(posted));
            }
            if (!rejected.isEmpty()) {
                rejectedWriter.write(new Chunk<>(rejected));
            }
        }

        /**
         * {@inheritDoc}
         *
         * <p>Forwards the stream lifecycle to the reject writer, which needs it because
         * {@code AWS.M2.CARDDEMO.DALYREJS(+1)} at {@code app/jcl/POSTTRAN.jcl:L38} is <strong>one</strong>
         * dataset: that writer therefore appends every chunk to one object and completes it on
         * {@link #close()}. Spring Batch registers a step's writer as a stream only when the object handed to
         * {@code writer(...)} implements {@code ItemStream}, and the object handed to it is this decorator - so
         * without these three methods the reject generation would never be closed and the run's rejects would
         * never reach the store.
         *
         * <p>The transaction writer is deliberately not forwarded to: it is not an {@code ItemStream}, because
         * its output is the keyed transaction relation plus one mirror object per commit interval rather than a
         * single generation.
         *
         * @param executionContext the step's context
         */
        @Override
        public void open(final ExecutionContext executionContext) {
            rejectedWriter.open(executionContext);
        }

        /**
         * {@inheritDoc}
         *
         * @param executionContext the step's context, into which the reject writer publishes its running count
         */
        @Override
        public void update(final ExecutionContext executionContext) {
            rejectedWriter.update(executionContext);
        }

        /**
         * {@inheritDoc}
         *
         * <p>This is {@code 9300-DALYREJS-CLOSE} at {@code app/cbl/CBTRN02C.cbl:L654}: the single reject
         * generation is completed here and its concrete key published. A failure is the source's own failed
         * {@code CLOSE} and fails the step.
         */
        @Override
        public void close() {
            rejectedWriter.close();
        }
    }

    /**
     * The parts of the mainline that bracket the loop: {@code app/cbl/CBTRN02C.cbl:L194}-{@code :L200} before
     * it and {@code :L221}-{@code :L232} after it.
     *
     * <p><strong>Why these belong to the step and not to the job.</strong> The two boundary markers name
     * {@code CBTRN02C} explicitly, and the six opens and six closes are that program's paragraphs. The job
     * spans two programs, so attaching them to the job would put {@code CBTRN02C}'s markers around
     * {@code CBTRN01C}'s work as well. The pre-flight emits its own markers from its own tasklet.
     *
     * <p>Ordering is preserved on both sides: the opens run {@code 0000}, {@code 0100}, {@code 0200},
     * {@code 0300}, {@code 0400}, {@code 0500} as at {@code :L195}-{@code :L200}, and the closes run
     * {@code 9000}, {@code 9100}, {@code 9200}, {@code 9300}, {@code 9400}, {@code 9500} as at
     * {@code :L221}-{@code :L226}. An abend in an open therefore reports the first dataset that is
     * unavailable, exactly as on the mainframe, and the source likewise never reaches its loop when an open
     * fails.
     */
    private final class PostTranStepListener implements StepExecutionListener {

        /** The reject destination, whose configuration stands in for {@code 0300-DALYREJS-OPEN}. */
        private final RejectWriter rejectedWriter;

        /**
         * Creates the listener.
         *
         * @param rejectedWriter the reject writer, already validated by the caller
         */
        private PostTranStepListener(final RejectWriter rejectedWriter) {
            this.rejectedWriter = rejectedWriter;
        }

        /**
         * {@inheritDoc}
         *
         * <p>Emits {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN02C'} at
         * {@code app/cbl/CBTRN02C.cbl:L194} and runs the six {@code OPEN} paragraphs in source order.
         *
         * @param stepExecution the starting step, whose context carries the two counters
         * @throws FatalProcessingException if any of the six datasets is unavailable
         */
        @Override
        public void beforeStep(final StepExecution stepExecution) {
            LOG.info(MSG_START_OF_EXECUTION);

            openDailyTransactionFile();
            openTransactionFile();
            openCrossReferenceFile();
            openRejectFile(rejectedWriter);
            openAccountFile();
            openCategoryBalanceFile();
        }

        /**
         * {@inheritDoc}
         *
         * <p>Runs the six {@code CLOSE} paragraphs in source order and then, <em>only if the step completed</em>,
         * emits the two end-of-run counters and the end-of-execution marker, promotes the generation keys, and
         * applies the return-code-4 exit status.
         *
         * <p><strong>Returns {@code null} deliberately.</strong> Spring Batch merges a returned status into
         * the step's own with {@link ExitStatus#and(ExitStatus)}. That merge would in fact preserve
         * {@code "COMPLETED WITH REJECTS"} today - verified by measurement, not assumed - but only because
         * two statuses of equal severity are resolved by comparing their exit code strings, and this one
         * happens to sort after {@code "COMPLETED"}. Depending on that would make return code 4 hostage to a
         * string comparison. Setting the status directly and returning {@code null} sidesteps the merge, and
         * {@link PostTranReturnCodeDecider} re-derives the outcome from
         * {@value DailyTransactionPostingJob#REJECT_COUNT_CONTEXT_ENTRY} rather than from any exit code, so
         * the contract holds however the framework chooses to merge.
         *
         * <p>The one merge property this class <em>does</em> rely on is severity ordering, which is a
         * documented contract: {@code FAILED.and("COMPLETED WITH REJECTS")} stays {@code FAILED}, so a
         * genuine failure can never be downgraded to return code 4.
         *
         * <p>A step that already failed keeps its failure and, beyond that, produces <strong>no
         * end-of-run output at all</strong>: no counter emission, no generation-key promotion and no
         * reject-count exit status. Return code 4 means "completed, with rejects" and must never mask a
         * genuine failure or an abend, and a summary or a promoted key would advertise a completed run to
         * anything reading the job context. The six closes still run, because the language environment
         * releases the datasets on an abend too.
         *
         * @param stepExecution the finished step
         * @return {@code null}, to leave the framework's own status merge untouched
         */
        @Override
        public ExitStatus afterStep(final StepExecution stepExecution) {
            closeDailyTransactionFile();
            closeTransactionFile();
            closeCrossReferenceFile();
            closeRejectFile();
            closeAccountFile();
            closeCategoryBalanceFile();

            final long processed = readCounter(stepExecution, PROCESSED_COUNT_CONTEXT_ENTRY);
            final long rejected = readCounter(stepExecution, REJECT_COUNT_CONTEXT_ENTRY);

            // FINDING, SEVERITY HIGH. Nothing below may run unconditionally: a
            // step that FAILED would then emit the two end-of-run counters as though the run had completed and
            // would promote its generation keys into the job context, where a downstream step would read them
            // as the run's output. The source cannot do that: :L227-:L232 is reached only by falling out of the
            // mainline loop, and every failure path before it goes through 9999-ABEND-PROGRAM, which calls
            // CEE3ABD and never returns. A failed step therefore emits no summary and publishes no output, and
            // that is what the guard below reproduces. The closes above are NOT guarded, because they must run
            // either way - CICS and the language environment release the datasets on abend too.
            if (stepExecution.getStatus().isUnsuccessful()) {
                LOG.error("{} did not complete: status={}; the end-of-run counters of "
                                + "app/cbl/CBTRN02C.cbl:L227-L228 are not emitted and no generation key is "
                                + "promoted, because :L227 is reached only by completing the mainline loop "
                                + "and every failure path abends at 9999-ABEND-PROGRAM instead. Records "
                                + "observed before the failure: processed={} rejected={}",
                        POSTING_STEP_BEAN_NAME, stepExecution.getStatus(), Long.valueOf(processed),
                        Long.valueOf(rejected));
                return null;
            }

            // :L227-:L228. Rendered as nine zero-padded digits because both counters are PIC 9(09), and
            // emitted with the source's own literals - one space before the first colon, two before the
            // second.
            LOG.info("{}{}", MSG_TRANSACTIONS_PROCESSED, renderCounter(processed));
            LOG.info("{}{}", MSG_TRANSACTIONS_REJECTED, renderCounter(rejected));

            promoteGenerationKeys(stepExecution);

            // :L229-:L231 IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE. The only condition, and nothing else
            // participates in it.
            if (rejected > 0L) {
                stepExecution.setExitStatus(COMPLETED_WITH_REJECTS_EXIT_STATUS);
            }

            // :L232, after the counters and the return-code decision, as the source has it.
            LOG.info(MSG_END_OF_EXECUTION);
            return null;
        }
    }

    /**
     * The job-wide diagnostic context, which is the one concern that spans both programs.
     *
     * <p>{@code CorrelationIdFilter} is HTTP-scoped and never runs for batch work, and the observability
     * package is not permitted a job listener of its own, so this listener establishes the context itself -
     * under {@link CorrelationIdFilter#MDC_KEY_JOB_INSTANCE_ID} and
     * {@link CorrelationIdFilter#MDC_KEY_CORRELATION_ID} rather than under literals of its own, so the names
     * cannot drift from the ones {@code src/main/resources/logback-spring.xml} consumes. The context is
     * <strong>restored in a {@code finally} block</strong>, so it cannot leak onto a pooled thread.
     *
     * <p>It deliberately owns no opens, closes or markers: those are {@code CBTRN02C}'s and
     * {@code CBTRN01C}'s respectively, and each step emits its own.
     */
    private final class PostTranJobListener implements JobExecutionListener {

        /** Creates the listener. Stateless: every value it needs comes from the {@link JobExecution}. */
        private PostTranJobListener() {
            // No state: see the class documentation.
        }

        /**
         * {@inheritDoc}
         *
         * @param jobExecution the starting execution
         */
        @Override
        public void beforeJob(final JobExecution jobExecution) {
            establishDiagnosticContext(
                    jobExecution.getJobInstance().getInstanceId(), jobExecution.getId());
        }

        /**
         * {@inheritDoc}
         *
         * <p>Publishes the abend exit status when one is warranted, then restores the diagnostic context in a
         * {@code finally} block so it is released even if that publication throws.
         *
         * @param jobExecution the finishing execution
         */
        @Override
        public void afterJob(final JobExecution jobExecution) {
            try {
                applyAbendExitStatus(jobExecution);
            } finally {
                restoreDiagnosticContext(jobExecution.getId());
            }
        }
    }

    /**
     * Maps the posting step's outcome onto the four legacy return codes, replacing the {@code COND=} gating
     * that JCL would use between steps.
     *
     * <p><strong>{@code app/jcl/POSTTRAN.jcl} carries no {@code COND=} parameter at all</strong>, because it
     * has a single program step and nothing to gate. The decider exists because the orchestrated pipeline
     * gates on the full code set, and because without it a failed step could not be told apart from an
     * abended one.
     *
     * <p>The mapping, and the evidence for each arm:
     *
     * <ul>
     *   <li><strong>12, abend</strong> - any recorded {@link FatalProcessingException}. Tested
     *       <em>first</em>, because an abend is also a failure and would otherwise be reported as 8.</li>
     *   <li><strong>8, failed</strong> - an unsuccessful batch status, or a {@code FAILED} exit code, with no
     *       abend among the failures.</li>
     *   <li><strong>4, completed with rejects</strong> - the reject count exceeded zero. Read from
     *       {@value DailyTransactionPostingJob#REJECT_COUNT_CONTEXT_ENTRY} rather than from the step's exit
     *       code, because {@link ExitStatus#and(ExitStatus)} resolves equal-severity statuses by a
     *       lexicographic comparison of their exit codes, which is incidental rather than contractual.
     *       Reading the count makes the outcome independent of it. This is the direct realisation of
     *       {@code app/cbl/CBTRN02C.cbl:L229}-{@code :L231}.</li>
     *   <li><strong>0, completed</strong> - everything else. The normal outcome.</li>
     * </ul>
     *
     * <p>Declared {@code static}: it closes over nothing, so an inner class would hold a reference to the
     * enclosing configuration for no reason. It is a plain object rather than a bean, so it cannot collide
     * with a decider declared by {@code com.cardemo.config.BatchConfig}.
     */
    private static final class PostTranReturnCodeDecider implements JobExecutionDecider {

        /** Creates the decider. Stateless, so one instance serves every execution. */
        private PostTranReturnCodeDecider() {
            // No state: the decision is a pure function of the two arguments.
        }

        /**
         * {@inheritDoc}
         *
         * @param jobExecution the running execution, whose recorded failures identify an abend
         * @param stepExecution the step that has just finished; the contract permits {@code null}, which is
         *     reported as {@link FlowExecutionStatus#UNKNOWN} rather than assumed to be success
         * @return one of {@code ABEND}, {@code FAILED}, {@code COMPLETED WITH REJECTS}, {@code COMPLETED} or
         *     {@code UNKNOWN}
         */
        @Override
        public FlowExecutionStatus decide(final JobExecution jobExecution,
                final StepExecution stepExecution) {

            if (containsAbend(jobExecution, stepExecution)) {
                return new FlowExecutionStatus(EXIT_CODE_ABEND);
            }
            if (stepExecution == null) {
                return FlowExecutionStatus.UNKNOWN;
            }
            if (stepExecution.getStatus().isUnsuccessful()) {
                return FlowExecutionStatus.FAILED;
            }
            if (EXIT_CODE_FAILED.equals(stepExecution.getExitStatus().getExitCode())) {
                return FlowExecutionStatus.FAILED;
            }
            if (readCounter(stepExecution, REJECT_COUNT_CONTEXT_ENTRY) > 0L) {
                return new FlowExecutionStatus(EXIT_CODE_COMPLETED_WITH_REJECTS);
            }
            return FlowExecutionStatus.COMPLETED;
        }
    }

    /**
     * Treats job parameters as untrusted input.
     *
     * <p><strong>Not available: no job parameter has a legacy contract.</strong>
     * {@code app/jcl/POSTTRAN.jcl:L23} carries no {@code PARM=} on its {@code EXEC} card, so unlike
     * {@code app/jcl/INTCALC.jcl} - which passes a ten-character date - this job receives nothing from the
     * job stream. There is therefore no required parameter to specify, and none is invented.
     *
     * <p>What this validator does instead is <em>harden</em>: it bounds the number of parameters and the
     * length of every name and rendered value, and refuses control characters, which could otherwise forge a
     * line break in the structured log stream. It deliberately does <strong>not</strong> reject unrecognised
     * names, because the standard way to launch the same job twice is to add a uniqueness parameter, and
     * refusing those would make the job launchable exactly once.
     *
     * <p>Numeric parameters are checked for range where they appear: a negative {@link Long} is refused,
     * since no quantity this job could be given has a meaningful negative value.
     *
     * <p>Declared {@code static}: it closes over nothing.
     */
    private static final class PostTranParametersValidator implements JobParametersValidator {

        /** Creates the validator. Stateless, so one instance serves every launch. */
        private PostTranParametersValidator() {
            // No state: the decision is a pure function of the argument.
        }

        /**
         * {@inheritDoc}
         *
         * @param parameters the supplied parameters; {@code null} and empty are both valid, because the
         *     source passes none
         * @throws JobParametersInvalidException if a parameter is unusable
         */
        @Override
        public void validate(final JobParameters parameters) throws JobParametersInvalidException {
            if (parameters == null || parameters.isEmpty()) {
                // The faithful case: app/jcl/POSTTRAN.jcl:L23 supplies no PARM at all.
                return;
            }
            if (parameters.getParameters().size() > MAX_JOB_PARAMETERS) {
                throw new JobParametersInvalidException("at most " + MAX_JOB_PARAMETERS
                        + " job parameters are accepted but " + parameters.getParameters().size()
                        + " were supplied");
            }
            for (final var entry : parameters.getParameters().entrySet()) {
                validateName(entry.getKey());
                validateValue(entry.getKey(), entry.getValue());
            }
        }

        /**
         * Bounds and sanitises a parameter name.
         *
         * @param name the parameter name; {@code null} and blank are both refused
         * @throws JobParametersInvalidException if the name is absent, over-long or carries a control
         *     character
         */
        private static void validateName(final String name) throws JobParametersInvalidException {
            if (name == null || name.isBlank()) {
                throw new JobParametersInvalidException("a job parameter name must not be blank");
            }
            if (name.length() > MAX_JOB_PARAMETER_NAME_LENGTH) {
                throw new JobParametersInvalidException("job parameter name exceeds "
                        + MAX_JOB_PARAMETER_NAME_LENGTH + " characters");
            }
            if (containsControlCharacter(name)) {
                throw new JobParametersInvalidException(
                        "a job parameter name must not contain a control character");
            }
        }

        /**
         * Bounds and sanitises a parameter value, and range-checks a numeric one.
         *
         * @param name the parameter name, for the diagnostic
         * @param parameter the parameter; a {@code null} parameter or a {@code null} value is accepted, since
         *     neither can carry an injection or an out-of-range quantity
         * @throws JobParametersInvalidException if the rendered value is over-long, carries a control
         *     character, or is a negative number
         */
        private static void validateValue(final String name, final JobParameter<?> parameter)
                throws JobParametersInvalidException {

            if (parameter == null || parameter.getValue() == null) {
                return;
            }
            final Object value = parameter.getValue();
            if (value instanceof Number number && number.longValue() < 0L) {
                throw new JobParametersInvalidException(
                        "job parameter " + name + " must not be negative");
            }
            final String rendered = String.valueOf(value);
            if (rendered.length() > MAX_JOB_PARAMETER_VALUE_LENGTH) {
                throw new JobParametersInvalidException("job parameter " + name + " exceeds "
                        + MAX_JOB_PARAMETER_VALUE_LENGTH + " characters");
            }
            if (containsControlCharacter(rendered)) {
                throw new JobParametersInvalidException(
                        "job parameter " + name + " must not contain a control character");
            }
        }

        /**
         * Reports whether a string carries any character that could forge structure in the log stream.
         *
         * @param value the string to inspect; never {@code null} at either call site
         * @return {@code true} when at least one character is a control character
         */
        private static boolean containsControlCharacter(final String value) {
            for (int index = 0; index < value.length(); index++) {
                if (Character.isISOControl(value.charAt(index))) {
                    return true;
                }
            }
            return false;
        }
    }
}
