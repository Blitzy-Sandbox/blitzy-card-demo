/*
 * ******************************************************************
 * Program     : TransactionReportJob.java
 * Application : CardDemo
 * Type        : Spring Batch Job Configuration
 * Function    : Transaction report — backup, card-ordered filtered sort and paginated 133-byte report emission.
 * Source      : app/jcl/TRANREPT.jcl + app/proc/TRANREPT.prc + app/proc/REPROC.prc + app/ctl/REPROCT.ctl + app/cbl/CBTRN03C.cbl (649 lines, 27 paragraphs) @ 7756d89
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

import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.batch.processors.TransactionReportProcessor.ReportLines;
import com.cardemo.batch.readers.TransactionBackupReader;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.CardCrossReferenceRepository;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 *   <li>{@code carddemo.batch.tranrept.name}: {@code TRANREPT}.</li>
 *   <li>{@code carddemo.batch.tranrept.chunk-size}: falls back to
 *       {@code carddemo.batch.chunk-size}, then 100. It bounds report-writer batches.</li>
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
 * <p>The inline job listener supplies {@code jobInstanceId}, {@code correlationId}, {@code traceId} and
 * {@code spanId} to MDC for batch log events, snapshots inherited values in the job execution context, and
 * restores or clears every value in a finally block. Metrics use only the counters owned by
 * {@link MetricsConfig}; no card number becomes a metric tag.
 *
 * <h2>Build, test and troubleshooting</h2>
 *
 * <p>Build with {@code ./mvnw -B -ntp -Ddependency-check.skip=true clean verify}. The compiler targets
 * Java 25 with {@code -Xlint:all -Werror}. Jobs do not auto-run because
 * {@code spring.batch.job.enabled} is false. A missing date raises {@link ValidationException}; a missing
 * concrete handoff key raises {@link FatalProcessingException}; a record-width mismatch fails before the
 * object is published; and a missing bucket fails configuration rather than selecting an implicit target.
 *
 * <p><strong>Not available:</strong> the source does not state whether an external object consumer expects
 * newline-delimited records, so this implementation preserves fixed blocks with no delimiter; no service-level
 * latency or throughput objective exists, so none is invented; and integration Gates 1, 4 and 8 require a
 * container runtime. {@code DECISION_LOG.md} is absent in this clone and cannot be created by this single-file
 * assignment; the retention decision is therefore recorded here and in the existing
 * {@code application.yml} block that cites both declarations.
 */
@Configuration("transactionReportJobConfiguration")
public class TransactionReportJob {

    /** Structured application logger; report records themselves are never written to it. */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionReportJob.class);

    /** Unique bean name for STEP01R, {@code app/proc/TRANREPT.prc:L21}. */
    private static final String BACKUP_STEP_BEAN_NAME = "transactionReportBackupStep";

    /** Unique bean name for STEP05R, {@code app/proc/TRANREPT.prc:L35}. */
    private static final String SORT_STEP_BEAN_NAME = "transactionReportSortStep";

    /** Unique bean name for STEP10R, {@code app/proc/TRANREPT.prc:L57}. */
    private static final String GENERATE_STEP_BEAN_NAME = "transactionReportGenerateStep";

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

    /** MDC key consumed by {@code logback-spring.xml}. */
    private static final String MDC_JOB_INSTANCE_ID = "jobInstanceId";

    /** MDC key consumed by {@code logback-spring.xml}. */
    private static final String MDC_CORRELATION_ID = "correlationId";

    /** MDC key consumed by {@code logback-spring.xml}. */
    private static final String MDC_TRACE_ID = "traceId";

    /** MDC key consumed by {@code logback-spring.xml}. */
    private static final String MDC_SPAN_ID = "spanId";

    /** Execution-context slot used to restore inherited job-instance context. */
    private static final String SAVED_MDC_JOB_INSTANCE_ID =
            "carddemo.tranrept.mdc.saved.jobInstanceId";

    /** Execution-context slot used to restore inherited correlation context. */
    private static final String SAVED_MDC_CORRELATION_ID =
            "carddemo.tranrept.mdc.saved.correlationId";

    /** Execution-context slot used to restore inherited trace context. */
    private static final String SAVED_MDC_TRACE_ID =
            "carddemo.tranrept.mdc.saved.traceId";

    /** Execution-context slot used to restore inherited span context. */
    private static final String SAVED_MDC_SPAN_ID =
            "carddemo.tranrept.mdc.saved.spanId";

    /** Suffix marking whether a saved MDC value existed. */
    private static final String SAVED_MDC_PRESENT_SUFFIX = ".present";

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

    /** Sole owner facade for the repository’s four metrics. */
    private final MetricsConfig metricsConfig;

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
     * @param metricsConfig owner facade for the four application metrics
     * @param objectStorage injected S3 operations abstraction
     * @param objectStoreClient injected paged S3 client
     * @param configuredJobName {@code carddemo.batch.tranrept.name}, default {@code TRANREPT}
     * @param configuredChunkSize {@code carddemo.batch.tranrept.chunk-size}, global fallback, then 100
     * @param configuredOutputBucket {@code carddemo.aws.s3.batch-output-bucket}, blank fail-fast default
     * @param configuredBackupPrefix backup generation prefix, default {@code gdg/transact-bkup}
     * @param configuredDailyPrefix daily generation prefix, default {@code gdg/transact-daly}
     * @param configuredReportPrefix report generation prefix, default {@code gdg/tranrept}
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
            final MetricsConfig metricsConfig,
            final S3Operations objectStorage,
            final S3Client objectStoreClient,
            @Value("${carddemo.batch.tranrept.name:TRANREPT}") final String configuredJobName,
            @Value("${carddemo.batch.tranrept.chunk-size:${carddemo.batch.chunk-size:100}}")
                    final int configuredChunkSize,
            @Value("${carddemo.aws.s3.batch-output-bucket:}") final String configuredOutputBucket,
            @Value("${carddemo.aws.s3.gdg-prefixes.transact-bkup:gdg/transact-bkup}")
                    final String configuredBackupPrefix,
            @Value("${carddemo.aws.s3.gdg-prefixes.transact-daly:gdg/transact-daly}")
                    final String configuredDailyPrefix,
            @Value("${carddemo.aws.s3.gdg-prefixes.tranrept:gdg/tranrept}")
                    final String configuredReportPrefix,
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
        this.metricsConfig = Objects.requireNonNull(metricsConfig, "metricsConfig must not be null");
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage must not be null");
        this.objectStoreClient =
                Objects.requireNonNull(objectStoreClient, "objectStoreClient must not be null");
        this.jobName = requireConfiguredText(configuredJobName, "carddemo.batch.tranrept.name");
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
     * Three-step flow in the clean procedure order, with every outcome routed through the 0/4/8/12 decider.
     *
     * @param backupStep STEP01R
     * @param sortStep STEP05R
     * @param generateStep STEP10R
     * @return the complete transaction-report flow
     */
    @Bean(FLOW_BEAN_NAME)
    public Flow transactionReportFlow(
            @Qualifier(BACKUP_STEP_BEAN_NAME) final Step backupStep,
            @Qualifier(SORT_STEP_BEAN_NAME) final Step sortStep,
            @Qualifier(GENERATE_STEP_BEAN_NAME) final Step generateStep) {

        final JobExecutionDecider returnCodeDecider = new TransactionReportReturnCodeDecider();
        return new FlowBuilder<SimpleFlow>(FLOW_BEAN_NAME)
                .start(backupStep).on(EXIT_CODE_COMPLETED).to(sortStep)
                .from(backupStep).on(EXIT_CODE_ANY).to(returnCodeDecider)
                .from(sortStep).on(EXIT_CODE_COMPLETED).to(generateStep)
                .from(sortStep).on(EXIT_CODE_ANY).to(returnCodeDecider)
                .from(generateStep).on(EXIT_CODE_ANY).to(returnCodeDecider)
                .from(returnCodeDecider).on(EXIT_CODE_COMPLETED).end(EXIT_CODE_COMPLETED)
                .from(returnCodeDecider).on(EXIT_CODE_COMPLETED_WITH_REJECTS)
                .end(EXIT_CODE_COMPLETED_WITH_REJECTS)
                .from(returnCodeDecider).on(EXIT_CODE_FAILED).fail()
                .from(returnCodeDecider).on(EXIT_CODE_ABEND).fail()
                .from(returnCodeDecider).on(EXIT_CODE_ANY).fail()
                .build();
    }

    /**
     * Complete transaction-report job with pre-run parameter validation and an inline MDC listener.
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
     * @param stepExecution current step execution
     */
    private void executeStep05r(final StepExecution stepExecution) {
        final String startDate = startDateSymbol(stepExecution.getJobExecution().getJobParameters());
        final String endDate = endDateSymbol(stepExecution.getJobExecution().getJobParameters());
        final String backupKey =
                requireConcreteKey(stepExecution, BACKUP_OBJECT_KEY_CONTEXT, backupPrefix);
        final List<byte[]> records =
                readFixedWidthGeneration(backupKey, DD_SORT_INPUT, TRANSACTION_RECORD_LENGTH);

        records.removeIf(record -> !includeCondition(record, startDate, endDate));
        final Comparator<byte[]> cardNumberAscending = this::sortFieldsCardNumberAscending;
        records.sort(cardNumberAscending);

        final long jobInstanceId = requireJobInstanceId(stepExecution);
        final String dailyKey = generationObjectKey(dailyPrefix, jobInstanceId, DAILY_OBJECT_NAME);
        writeFixedWidthGeneration(dailyKey, DD_SORT_OUTPUT, TRANSACTION_RECORD_LENGTH, records);
        publishConcreteKey(stepExecution, DAILY_OBJECT_KEY_CONTEXT, dailyKey);
        publishCount(stepExecution, DAILY_RECORD_COUNT_CONTEXT, records.size());
        LOG.info("{} completed: {} records passed the inclusive character predicate and card ordering",
                SORT_STEP_BEAN_NAME, Integer.valueOf(records.size()));
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

    /**
     * SORT FIELDS=(TRAN-CARD-NUM,A), {@code app/proc/TRANREPT.prc:L44}. The declared ZD field is compared
     * on the character representation, as required by the migration contract.
     *
     * @param left left record
     * @param right right record
     * @return ascending comparison result
     */
    private int sortFieldsCardNumberAscending(final byte[] left, final byte[] right) {
        requireRecordLength(left, TRANSACTION_RECORD_LENGTH, DD_SORT_INPUT);
        requireRecordLength(right, TRANSACTION_RECORD_LENGTH, DD_SORT_INPUT);
        final String leftCard =
                new String(left, CARD_NUMBER_OFFSET, CARD_NUMBER_LENGTH, FIXED_WIDTH_CHARSET);
        final String rightCard =
                new String(right, CARD_NUMBER_OFFSET, CARD_NUMBER_LENGTH, FIXED_WIDTH_CHARSET);
        return leftCard.compareTo(rightCard);
    }

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
            throw failure;
        }

        publishConcreteKey(stepExecution, REPORT_OBJECT_KEY_CONTEXT, reportKey);
        publishCount(stepExecution, REPORT_LINE_COUNT_CONTEXT, writer.linesWritten());
        LOG.info("{} completed: {} fixed-width report lines emitted",
                GENERATE_STEP_BEAN_NAME, Long.valueOf(writer.linesWritten()));
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
                metricsConfig.countRecordProcessed();
                if (buffered.size() == chunkSize) {
                    writeReportRecord1111(writer, buffered);
                }
            }
            if (!terminatedByNextSentence) {
                buffered.add(processor.finishReport());
            }
            writeReportRecord1111(writer, buffered);
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
     * Reads one fixed-width object as undelimited records.
     *
     * @param objectKey concrete object key
     * @param logicalName logical DD name
     * @param recordLength record length
     * @return mutable record list in object order
     */
    private List<byte[]> readFixedWidthGeneration(
            final String objectKey,
            final String logicalName,
            final int recordLength) {

        final List<byte[]> records = new ArrayList<>();
        try {
            final S3Resource resource = objectStorage.download(outputBucket, objectKey);
            try (InputStream input = new BufferedInputStream(resource.getInputStream())) {
                while (true) {
                    final byte[] record = input.readNBytes(recordLength);
                    if (record.length == 0) {
                        break;
                    }
                    requireRecordLength(record, recordLength, logicalName);
                    records.add(record);
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
     * Writes unchanged fixed-width records to one concrete object.
     *
     * @param objectKey concrete output key
     * @param logicalName logical DD name
     * @param recordLength required record length
     * @param records records in final order
     */
    private void writeFixedWidthGeneration(
            final String objectKey,
            final String logicalName,
            final int recordLength,
            final List<byte[]> records) {

        try {
            final S3Resource resource = outputResource(objectKey);
            try (OutputStream output = new BufferedOutputStream(resource.getOutputStream())) {
                for (final byte[] record : records) {
                    requireRecordLength(record, recordLength, logicalName);
                    output.write(record);
                }
                output.flush();
            }
            fileStatusMapper.requireSuccess(STATUS_SUCCESS, logicalName, "WRITE");
        } catch (final CardDemoException typed) {
            throw typed;
        } catch (final IOException | RuntimeException cause) {
            throw ioAbend(logicalName, "WRITE", "ERROR WRITING FIXED-WIDTH GENERATION", cause);
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
     * @param value configured prefix
     * @param property property name used in validation failures
     * @return unchanged safe relative prefix
     */
    private String requireGenerationPrefix(final String value, final String property) {
        final String prefix = requireConfiguredText(value, property);
        if (prefix.startsWith("/")
                || prefix.endsWith("/")
                || prefix.contains("//")
                || prefix.contains("..")) {
            throw new IllegalArgumentException(property + " is not a safe relative object prefix");
        }
        return prefix;
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
     * Parks one inherited MDC value in the job execution context.
     *
     * @param context job execution context holding the snapshot
     * @param savedKey execution-context key for the inherited value
     * @param mdcKey MDC key to snapshot
     */
    private void parkMdc(
            final ExecutionContext context,
            final String savedKey,
            final String mdcKey) {

        final String inherited = MDC.get(mdcKey);
        context.put(savedKey + SAVED_MDC_PRESENT_SUFFIX, Boolean.valueOf(inherited != null));
        if (inherited != null) {
            context.putString(savedKey, inherited);
        }
    }

    /**
     * Restores or clears one MDC value and removes its snapshot.
     *
     * @param context job execution context holding the snapshot
     * @param savedKey execution-context key for the inherited value
     * @param mdcKey MDC key to restore or clear
     */
    private void restoreMdc(
            final ExecutionContext context,
            final String savedKey,
            final String mdcKey) {

        final boolean present = context.get(
                savedKey + SAVED_MDC_PRESENT_SUFFIX, Boolean.class, Boolean.FALSE).booleanValue();
        if (present) {
            MDC.put(mdcKey, context.getString(savedKey, ""));
        } else {
            MDC.remove(mdcKey);
        }
        context.remove(savedKey);
        context.remove(savedKey + SAVED_MDC_PRESENT_SUFFIX);
    }

    /**
     * Job-parameter validator that rejects malformed input before STEP01R creates an object.
     */
    private final class TransactionReportParametersValidator implements JobParametersValidator {

        /** Stateless constructor. */
        private TransactionReportParametersValidator() {
            // Validation delegates to immutable outer collaborators.
        }

        /** {@inheritDoc} */
        @Override
        public void validate(final JobParameters parameters) {
            validateDateRange(parameters);
        }
    }

    /**
     * Inline batch MDC lifecycle and source-finding logger.
     */
    private final class TransactionReportJobListener implements JobExecutionListener {

        /** Stateless constructor; inherited values live in the execution context. */
        private TransactionReportJobListener() {
            // No mutable listener state.
        }

        /** Establishes all four MDC keys before the first job event emitted by this class. */
        @Override
        public void beforeJob(final JobExecution jobExecution) {
            final ExecutionContext context = jobExecution.getExecutionContext();
            parkMdc(context, SAVED_MDC_JOB_INSTANCE_ID, MDC_JOB_INSTANCE_ID);
            parkMdc(context, SAVED_MDC_CORRELATION_ID, MDC_CORRELATION_ID);
            parkMdc(context, SAVED_MDC_TRACE_ID, MDC_TRACE_ID);
            parkMdc(context, SAVED_MDC_SPAN_ID, MDC_SPAN_ID);

            final long instanceId = jobExecution.getJobInstance() == null
                    ? 0L
                    : jobExecution.getJobInstance().getInstanceId();
            MDC.put(MDC_JOB_INSTANCE_ID, Long.toString(instanceId));
            if (MDC.get(MDC_CORRELATION_ID) == null) {
                MDC.put(MDC_CORRELATION_ID, UUID.randomUUID().toString());
            }
            if (MDC.get(MDC_TRACE_ID) == null) {
                MDC.put(MDC_TRACE_ID, UUID.randomUUID().toString().replace("-", ""));
            }
            if (MDC.get(MDC_SPAN_ID) == null) {
                MDC.put(MDC_SPAN_ID, UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            }

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

        /** Logs the final outcome and restores every inherited MDC value in a finally block. */
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
                final ExecutionContext context = jobExecution.getExecutionContext();
                restoreMdc(context, SAVED_MDC_SPAN_ID, MDC_SPAN_ID);
                restoreMdc(context, SAVED_MDC_TRACE_ID, MDC_TRACE_ID);
                restoreMdc(context, SAVED_MDC_CORRELATION_ID, MDC_CORRELATION_ID);
                restoreMdc(context, SAVED_MDC_JOB_INSTANCE_ID, MDC_JOB_INSTANCE_ID);
            }
        }
    }

    /**
     * Uniform decider covering legacy RC 0, 4, 8 and 12. RC 4 is recognised but never set by CBTRN03C.
     */
    private static final class TransactionReportReturnCodeDecider implements JobExecutionDecider {

        /** Stateless constructor. */
        private TransactionReportReturnCodeDecider() {
            // Pure decision object.
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

        /** Object storage supplied by the enclosing configuration. */
        private final S3Operations objectStorage;

        /** FILE STATUS translator supplied by the enclosing configuration. */
        private final FileStatusMapper fileStatusMapper;

        /** Destination bucket. */
        private final String outputBucket;

        /** Concrete report-generation key. */
        private final String objectKey;

        /** Open object stream, scoped to one writer instance. */
        private OutputStream output;

        /** Number of 133-byte records successfully written. */
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

        /** Opens one unblocked report-generation object. */
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

        /** Publishes the current line count for restart diagnostics. */
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
            final String padded = line + " ".repeat(REPORT_RECORD_LENGTH - line.length());
            for (int index = 0; index < padded.length(); index++) {
                if (padded.charAt(index) > 0xFF) {
                    throw fatal("REPORT CHARACTER INVALID",
                            "TRANREPT LINE CANNOT BE ENCODED AS ONE BYTE PER CHARACTER", null);
                }
            }
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