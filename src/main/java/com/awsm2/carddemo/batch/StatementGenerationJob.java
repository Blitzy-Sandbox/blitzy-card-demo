/*
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
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.batch;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.service.StatementGenerationService;
import com.awsm2.carddemo.service.StatementGenerationService.StatementResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Spring Batch {@code @Configuration} class for the
 * {@code statementGenerationJob}.
 *
 * <p><b>// COBOL: CBSTM03A (text format), CBSTM03B (HTML format)</b><br>
 * <b>// Replaces: app/jcl/CREASTMT.JCL</b> &mdash; the COBOL end-of-day
 * statement-generation programs executed by JES2 as Stage 4a of the
 * end-of-day batch pipeline per AAP &sect;0.6.3.</p>
 *
 * <h2>Source Lineage</h2>
 *
 * <p>The original {@code CREASTMT.JCL} JCL job consisted of an
 * {@code EXEC PGM=CBSTM03A} step (with {@code CBSTM03B.CBL} called as a
 * file-service subroutine) that emitted both text and HTML statements
 * via the dual-format template pattern of the COBOL pair (per AAP
 * &sect;0.4.1). Each iteration:</p>
 * <ol>
 *   <li>Read each card from the XREF VSAM cluster, then joined to the
 *       owning {@code accounts}, {@code customer}, and date-ranged
 *       {@code transactions} rows.</li>
 *   <li>Rendered a text statement (FD-STMTFILE-REC PIC X(80) fixed-
 *       width COBOL output) AND an HTML statement (FD-HTMLFILE-REC
 *       PIC X(100) per the CBSTM03B Template Method).</li>
 *   <li>Wrote each statement file to the {@code STMTFILE} (LRECL=80)
 *       and {@code HTMLFILE} (LRECL=100) GDG datasets &mdash; now
 *       replaced by S3 versioned objects per AAP &sect;0.6.2.</li>
 * </ol>
 *
 * <h2>Java/Spring Batch Replacement</h2>
 *
 * <p>The {@code statementGenerationJob} wraps
 * {@link StatementGenerationService#generateStatements(LocalDate)} in
 * a single Spring Batch tasklet. The service performs the customer
 * iteration, the per-customer text+HTML rendering, and the S3 upload
 * via {@code S3OutputService}. Per AAP &sect;0.3.3, the service uses
 * the <b>Template Method pattern</b> internally to share the data-
 * gathering pipeline between text and HTML rendering variants.</p>
 *
 * <p>HTML output uses verbatim COBOL color literals to preserve
 * regulatory output format byte-identity (per AAP &sect;0.7.2
 * "Regulatory reporting output formats must remain identical
 * byte-for-byte"):</p>
 * <ul>
 *   <li>{@code #1d1d96b3} &mdash; header background</li>
 *   <li>{@code #FFAF33} &mdash; alert/warning highlight</li>
 *   <li>{@code #33FF5E} &mdash; success highlight</li>
 *   <li>{@code #f2f2f2} &mdash; alternating row background</li>
 * </ul>
 *
 * <p><b>Why a tasklet (not chunk-oriented)?</b>
 * {@link StatementGenerationService} is a {@code @Transactional(readOnly = true)}
 * iterator over the {@code accounts} table that renders the dual-format
 * output per account. Wrapping it in a Spring Batch chunk would impose
 * additional commit boundaries that would not match the COBOL semantic
 * (the service is read-only and does not write to RDS &mdash; only to
 * S3, which is non-transactional).</p>
 *
 * <h2>End-of-Day Pipeline Position</h2>
 *
 * <p>This job is <b>Stage 4a</b> of the end-of-day batch pipeline
 * orchestrated by AWS Step Functions per AAP &sect;0.6.3:</p>
 * <pre>
 *   POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; Parallel { CREASTMT, TRANREPT }
 * </pre>
 *
 * <p>Stage 4a (CREASTMT) and Stage 4b (TRANREPT) run in parallel
 * downstream of COMBTRAN. Their AWS Batch container exit codes feed
 * back into the parent Step Functions {@code Parallel} state via the
 * {@link ExitStatus} surfaced by the {@link StatementStepExitStatusListener}
 * &mdash; non-zero rendering errors map to
 * {@link CardDemoExitStatus#COMPLETED_WITH_REJECTS} (COBOL
 * {@code RETURN-CODE = 4}) so downstream {@code Choice} states can
 * branch on partial-success runs.</p>
 *
 * <h2>Job Parameters</h2>
 * <ul>
 *   <li>{@code batchRunId} (REQUIRED) &mdash; unique batch run identifier
 *       carried into the audit payload.</li>
 *   <li>{@code statementMonth} (REQUIRED) &mdash; the statement period
 *       end date (ISO-8601 {@code yyyy-MM-dd}); maps to the COBOL
 *       {@code WS-STMT-DATE} working-storage field. The
 *       {@link StatementGenerationService} uses this date to label
 *       statement headers and to scope the transaction date-range
 *       filter.</li>
 *   <li>{@code correlationId} (OPTIONAL) &mdash; distributed-trace
 *       correlation ID surfaced through MDC and the AuditLog
 *       structured documents; auto-generated as a {@link UUID} if
 *       absent.</li>
 * </ul>
 *
 * <h2>AWS Service Wiring</h2>
 * <p>Per AAP &sect;0.7.1 (Adapter Pattern), this class does NOT call
 * the AWS SDK directly &mdash; all AWS interactions flow through the
 * injected {@link AuditLogService} (OpenSearch + CloudWatch +
 * CloudTrail integration) and the {@link StatementGenerationService}
 * which in turn calls {@code S3OutputService}.</p>
 *
 * <h2>Replaces (AAP &sect;0.4.1)</h2>
 * <ul>
 *   <li>{@code app/cbl/CBSTM03A.CBL} &mdash; main statement renderer
 *       (text + HTML output).</li>
 *   <li>{@code app/cbl/CBSTM03B.CBL} &mdash; file-service subroutine
 *       (now subsumed by the JPA repository layer in
 *       {@link StatementGenerationService}).</li>
 *   <li>{@code app/jcl/CREASTMT.JCL} &mdash; full job stream including
 *       the IDCAMS DEFINE CLUSTER (replaced by Flyway), SORT step
 *       (replaced by JPA query order_by), IEFBR14 cleanup step
 *       (replaced by S3 versioning), and EXEC PGM=CBSTM03A.</li>
 * </ul>
 *
 * @see StatementGenerationService
 * @see CardDemoExitStatus#COMPLETED_WITH_REJECTS
 * @see BatchJobConfig
 */
@Configuration("statementGenerationJobConfiguration")
public class StatementGenerationJob {

    /**
     * SLF4J logger for the job lifecycle. Manually instantiated rather
     * than Lombok-generated because the project does not include
     * Project Lombok as a Maven dependency &mdash; sibling batch jobs
     * ({@link InterestCalculationJob},
     * {@link DailyTransactionPostingJob},
     * {@link CombineTransactionsJob}, {@link TransactionReportJob})
     * also use manual {@link LoggerFactory} instantiation for the same
     * reason.
     */
    private static final Logger LOG = LoggerFactory.getLogger(StatementGenerationJob.class);

    /**
     * Spring bean name for the {@link Job} produced by
     * {@link #statementGenerationJob()}. Used by:
     * <ul>
     *   <li>The {@link org.springframework.beans.factory.annotation.Qualifier @Qualifier}
     *       lookup in the {@code statementGenerationJobLauncher} of the
     *       Step Functions trigger Lambda.</li>
     *   <li>The {@link AuditLogService#logBatchJobLifecycle} {@code jobName}
     *       parameter so OpenSearch documents are uniformly tagged.</li>
     *   <li>The {@code StatementGenerationJobTest} {@code @Qualifier} based
     *       autowiring of the Job under test.</li>
     * </ul>
     */
    public static final String JOB_NAME = "statementGenerationJob";

    /**
     * Spring bean name for the orchestration {@link Step} produced by
     * {@link #generateStatementsStep()}.
     */
    public static final String STEP_NAME = "generateStatementsStep";

    /**
     * {@link JobParameters} key for the unique batch-run identifier
     * supplied by the Step Functions / AWS Batch orchestrator. Maps to
     * the COBOL {@code BATCH-RUN-ID} concept that downstream auditing
     * uses to correlate all output records from a single CREASTMT run.
     */
    public static final String PARAM_BATCH_RUN_ID = "batchRunId";

    /**
     * {@link JobParameters} key for the statement period end date
     * (ISO-8601 {@code yyyy-MM-dd}). Maps to the COBOL
     * {@code WS-STMT-DATE} working-storage field.
     */
    public static final String PARAM_STATEMENT_MONTH = "statementMonth";

    /**
     * {@link JobParameters} key for the optional distributed-trace
     * correlation ID. Carried through into every
     * {@link AuditLogService#logBatchJobLifecycle} emission to support
     * end-to-end trace joins across Step Functions / AWS Batch / ECS
     * Fargate / Spring Batch / OpenSearch.
     */
    public static final String PARAM_CORRELATION_ID = "correlationId";

    /**
     * {@link org.springframework.batch.item.ExecutionContext} key for
     * the count of plain-text statements rendered &amp; uploaded.
     * Surfaced to downstream Step Functions tasks via the
     * {@code ExecutionContextPromotionListener} configured at the
     * {@link BatchJobConfig} level.
     */
    public static final String CTX_TEXT_COUNT = "textCount";

    /**
     * {@link org.springframework.batch.item.ExecutionContext} key for
     * the count of HTML statements rendered &amp; uploaded.
     */
    public static final String CTX_HTML_COUNT = "htmlCount";

    /**
     * {@link org.springframework.batch.item.ExecutionContext} key for
     * the count of accounts whose statement generation threw an
     * exception. A non-zero value here triggers the
     * {@link StatementStepExitStatusListener} to map the step
     * {@link ExitStatus} to {@link CardDemoExitStatus#COMPLETED_WITH_REJECTS}
     * (COBOL {@code RETURN-CODE = 4}).
     */
    public static final String CTX_ERROR_COUNT = "errorCount";

    /**
     * Spring Batch metadata repository. Auto-configured by Spring Boot
     * via {@link BatchJobConfig @EnableBatchProcessing} and injected
     * here to satisfy the {@link JobBuilder} / {@link StepBuilder}
     * factory constructors.
     */
    private final JobRepository jobRepository;

    /**
     * JPA transaction manager &mdash; provided by
     * {@link com.awsm2.carddemo.config.JpaConfig} via Spring Boot's
     * {@code HibernateJpaAutoConfiguration}. Wired into the
     * {@link StepBuilder#tasklet(Tasklet, PlatformTransactionManager)}
     * call so Spring Batch metadata writes (BATCH_JOB_INSTANCE,
     * BATCH_STEP_EXECUTION) share the same RDS PostgreSQL
     * transactional context as the read-side queries delegated to
     * {@link StatementGenerationService} (CICS {@code SYNCPOINT}
     * replacement per AAP &sect;0.4.1).
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * The {@link StatementGenerationService} owning the entire
     * statement-generation pipeline per AAP &sect;0.7.1 (one
     * {@code @Service} per COBOL program: CBSTM03A text + CBSTM03B
     * HTML).
     */
    private final StatementGenerationService statementGenerationService;

    /**
     * Audit-log adapter invoked from the {@link JobExecutionListener}
     * {@code beforeJob}/{@code afterJob} callbacks AND from the tasklet
     * to emit {@code STARTED} and terminal ({@code COMPLETED},
     * {@code FAILED}) lifecycle events.
     *
     * <p>Per AAP &sect;0.6.6 this provides the regulatory-grade audit
     * trail that replaces JES {@code SYSPRINT} + {@code RETURN-CODE}
     * values from the original {@code CREASTMT.JCL} by indexing to
     * OpenSearch and emitting CloudWatch counters via Micrometer
     * &mdash; preserving the COBOL audit-trail field semantics
     * (executionId, status, durationMillis) verbatim per the AAP
     * &sect;0.7.2 "Audit trail content must be preserved exactly"
     * directive.</p>
     */
    private final AuditLogService auditLogService;

    /**
     * Shared {@link JobParametersValidator} bean (defined in
     * {@link BatchJobConfig#standardJobParametersValidator()}) that
     * enforces the mandatory {@code batchRunId} JobParameter on every
     * launch &mdash; preventing untraceable batch executions per the
     * AAP &sect;0.7.1 audit-traceability rule.
     *
     * <p>The schema's {@code members_exposed} constrains the Job
     * factory method to a no-arg signature
     * ({@code statementGenerationJob()}), so the validator is injected
     * here as a constructor field and referenced from the no-arg Job
     * factory method via {@code this.standardJobParametersValidator}.</p>
     */
    private final JobParametersValidator standardJobParametersValidator;

    /**
     * Externalized-configuration tag identifying the audit-source
     * (COBOL program name) embedded into structured log fields and
     * audit-event payloads. Defaults to {@code "CBSTM03A"} to preserve
     * traceability back to the source COBOL program per AAP &sect;0.7.3
     * (Refactor Discipline &mdash; "Document all COBOL-to-Java
     * translations with inline comments referencing the original COBOL
     * paragraph/section name").
     *
     * <p>Sourced from {@code carddemo.batch.statement.audit-source} in
     * {@code application.yml} / AWS Systems Manager Parameter Store
     * (per AAP &sect;0.7.1 externalized-configuration directive).</p>
     */
    @Value("${carddemo.batch.statement.audit-source:CBSTM03A}")
    private String auditSource;

    /**
     * Constructs the job configuration with required collaborators
     * via constructor injection (per AAP &sect;0.7.1 dependency-
     * injection directive).
     *
     * @param jobRepository                   Spring Batch metadata
     *                                        repository auto-configured by
     *                                        {@code @EnableBatchProcessing}
     * @param transactionManager              JPA-backed
     *                                        {@link PlatformTransactionManager}
     *                                        from JpaConfig
     * @param statementGenerationService      the service owning the COBOL
     *                                        CBSTM03A/B Template Method
     * @param auditLogService                 the audit-log adapter
     *                                        (OpenSearch + CloudWatch)
     * @param standardJobParametersValidator  the shared JobParameters
     *                                        validator bean from
     *                                        {@link BatchJobConfig}
     *                                        that enforces the mandatory
     *                                        {@code batchRunId} parameter
     */
    public StatementGenerationJob(JobRepository jobRepository,
                                  PlatformTransactionManager transactionManager,
                                  StatementGenerationService statementGenerationService,
                                  AuditLogService auditLogService,
                                  JobParametersValidator standardJobParametersValidator) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.statementGenerationService = statementGenerationService;
        this.auditLogService = auditLogService;
        this.standardJobParametersValidator = standardJobParametersValidator;
    }

    /**
     * Defines the {@code statementGenerationJob} Spring Batch
     * {@link Job} bean.
     *
     * <p>// Replaces: app/jcl/CREASTMT.JCL entire job stream
     * (EXEC PGM=CBSTM03A).</p>
     *
     * <p>Registers an inline {@link JobExecutionListener} that emits
     * {@code STARTED} and terminal lifecycle audit events via the
     * injected {@link AuditLogService}. Per agent_prompt Task 3.1, the
     * listener is wired inline (not injected separately) so the bean
     * factory signature exposes {@code statementGenerationJob()}
     * exactly as required by the file schema's
     * {@code members_exposed}.</p>
     *
     * <p>The inline listener emits:</p>
     * <ul>
     *   <li><b>{@code beforeJob}:</b>
     *       {@link AuditLogService#logBatchJobLifecycle} with
     *       {@code status="STARTED"} and {@code durationMillis=0L}.</li>
     *   <li><b>{@code afterJob}:</b>
     *       {@link AuditLogService#logBatchJobLifecycle} with the
     *       resolved {@link JobExecution#getStatus() job status} (e.g.
     *       {@code "COMPLETED"}, {@code "FAILED"}, {@code "ABANDONED"})
     *       and the computed {@code durationMillis} between the start
     *       and end times.</li>
     * </ul>
     *
     * <p>Per the schema's {@code members_exposed} the public Job-factory
     * method signature is {@code statementGenerationJob()} (no
     * parameters). The {@link JobExecutionListener} is therefore
     * created inline within this method rather than being injected as a
     * bean-method parameter.</p>
     *
     * @return the configured {@link Job} bean &mdash; registered in the
     *         {@code ApplicationContext} under the bean name
     *         {@value #JOB_NAME}
     */
    @Bean
    public Job statementGenerationJob() {
        // Replaces: app/jcl/CREASTMT.JCL EXEC PGM=CBSTM03A
        // Wire the shared standardJobParametersValidator (injected via
        // the constructor as this.standardJobParametersValidator since
        // the schema constrains this factory method to a no-arg
        // signature) so every launch is gated on a non-blank
        // batchRunId (AAP §0.7.1 audit-traceability rule).
        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(standardJobParametersValidator)
                .listener(new JobExecutionListener() {
                    @Override
                    public void beforeJob(JobExecution jobExecution) {
                        // Replaces: JES SYSPRINT "JOB STARTED" line +
                        // CBSTM03A 0000-START PROCEDURE DIVISION entry trace
                        LOG.info("// Replaces: app/jcl/CREASTMT.JCL - "
                                + "StatementGenerationJob starting, executionId={}, auditSource={}",
                                jobExecution.getId(), auditSource);
                        auditLogService.logBatchJobLifecycle(
                                JOB_NAME,
                                String.valueOf(jobExecution.getId()),
                                "STARTED",
                                0L,
                                null,
                                extractCorrelationId(jobExecution.getJobParameters())
                        );
                    }

                    @Override
                    public void afterJob(JobExecution jobExecution) {
                        // Replaces: JES SYSPRINT "JOB ENDED RC=nn" line +
                        // CBSTM03A 9999-EXIT-PROGRAM final RETURN-CODE inspection
                        final long durationMillis = computeDurationMillis(
                                jobExecution.getStartTime(),
                                jobExecution.getEndTime());
                        final String status = jobExecution.getStatus() != null
                                ? jobExecution.getStatus().name()
                                : "UNKNOWN";
                        final String exitCode = jobExecution.getExitStatus() != null
                                ? jobExecution.getExitStatus().getExitCode()
                                : "UNKNOWN";
                        LOG.info("StatementGenerationJob completed: status={}, exitCode={}, durationMs={}",
                                status, exitCode, durationMillis);
                        auditLogService.logBatchJobLifecycle(
                                JOB_NAME,
                                String.valueOf(jobExecution.getId()),
                                status,
                                durationMillis,
                                null,
                                extractCorrelationId(jobExecution.getJobParameters())
                        );
                    }
                })
                .start(generateStatementsStep())
                .build();
    }

    /**
     * Defines the orchestration tasklet step that invokes the
     * {@link StatementGenerationService}.
     *
     * <p>// Replaces: app/cbl/CBSTM03A.CBL + app/cbl/CBSTM03B.CBL
     * combined PROCEDURE DIVISION.</p>
     *
     * <p>The step is configured with a
     * {@link StatementStepExitStatusListener} that maps the
     * {@code errorCount} accumulated by the
     * {@link StatementGenerationService} to the appropriate Spring
     * Batch {@link ExitStatus}:</p>
     * <ul>
     *   <li>{@code errorCount == 0} &rarr; {@link ExitStatus#COMPLETED}
     *       (COBOL {@code RETURN-CODE = 0}).</li>
     *   <li>{@code errorCount > 0} &rarr;
     *       {@link CardDemoExitStatus#COMPLETED_WITH_REJECTS} (COBOL
     *       {@code RETURN-CODE = 4}).</li>
     * </ul>
     *
     * @return the configured {@link Step} bean
     */
    @Bean
    public Step generateStatementsStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(generateStatementsTasklet(), transactionManager)
                .listener(new StatementStepExitStatusListener())
                .build();
    }

    /**
     * Defines the {@link Tasklet} that drives the
     * {@link StatementGenerationService#generateStatements(LocalDate)}
     * invocation.
     *
     * <p>The tasklet:</p>
     * <ol>
     *   <li>Extracts and validates the {@code statementMonth}
     *       {@link JobParameters} entry (ISO-8601 {@code yyyy-MM-dd});
     *       throws {@link IllegalArgumentException} if missing or
     *       malformed, causing Spring Batch to set
     *       {@link org.springframework.batch.core.BatchStatus#FAILED}
     *       and surface the exception to the orchestrating Step
     *       Functions {@code Catch} block per AAP &sect;0.6.3.</li>
     *   <li>Delegates the entire statement-generation pipeline to
     *       {@link StatementGenerationService#generateStatements(LocalDate)}
     *       which iterates active accounts, joins
     *       {@code customer}/{@code card}/{@code transaction} rows,
     *       renders BOTH text (80-byte LRECL) and HTML (100-byte
     *       LRECL) statements via the Template Method pattern, and
     *       uploads each pair to S3 via {@code S3OutputService}.</li>
     *   <li>Surfaces the resulting {@link StatementResult} counts
     *       (textCount, htmlCount, errorCount) into the step
     *       {@link org.springframework.batch.item.ExecutionContext} so
     *       downstream Step Functions Choice states (and the
     *       {@link StatementStepExitStatusListener}) can branch on
     *       partial-success runs.</li>
     *   <li>Emits a single
     *       {@link AuditLogService#logBatchJobLifecycle} document with
     *       {@code status="COMPLETED"}, a {@code null durationMillis}
     *       (the lifecycle audits emitted by the inline listener
     *       carry the wall-clock duration), and a structured payload
     *       map containing batchRunId, statementMonth, and counts
     *       &mdash; matching the AuditLog field contract used by all
     *       CardDemo Spring Batch jobs per AAP &sect;0.6.6.</li>
     * </ol>
     *
     * @return a {@link Tasklet} returning {@link RepeatStatus#FINISHED}
     *         after a single service invocation
     */
    @Bean
    public Tasklet generateStatementsTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            // Replaces: app/cbl/CBSTM03A.CBL + app/cbl/CBSTM03B.CBL combined mainline
            final StepExecution stepExecution =
                    chunkContext.getStepContext().getStepExecution();
            final JobParameters jobParameters = stepExecution.getJobParameters();
            final String batchRunId = jobParameters.getString(PARAM_BATCH_RUN_ID);
            final String statementMonthStr = jobParameters.getString(PARAM_STATEMENT_MONTH);

            // Strict validation: COBOL CBSTM03A treats a missing
            // PARM='YYYY-MM-DD' as a fatal RETURN-CODE=8 ABEND. The
            // Java target preserves that semantic by throwing
            // IllegalArgumentException, which Spring Batch maps to
            // BatchStatus.FAILED and the Step Functions Catch block
            // intercepts per AAP §0.6.3.
            if (statementMonthStr == null || statementMonthStr.isBlank()) {
                throw new IllegalArgumentException(
                        "Required job parameter '" + PARAM_STATEMENT_MONTH
                                + "' is missing (expected ISO-8601 yyyy-MM-dd)");
            }

            final LocalDate statementMonth;
            try {
                // ISO-8601 yyyy-MM-dd parsing — replaces LE CEEDAYS-
                // based date parsing in CBSTM03A per AAP §0.4.1
                // (LE → java.time package).
                statementMonth = LocalDate.parse(
                        statementMonthStr, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (RuntimeException pex) {
                throw new IllegalArgumentException(
                        "Required job parameter '" + PARAM_STATEMENT_MONTH
                                + "' is not a valid ISO-8601 date: '"
                                + statementMonthStr + "'", pex);
            }

            LOG.info(
                    "// COBOL: CBSTM03A + CBSTM03B - generating statements: "
                            + "batchRunId={}, statementMonth={}, auditSource={}",
                    batchRunId, statementMonth, auditSource);

            // Delegate the entire statement-generation pipeline to the
            // service. The service:
            //   - iterates every active Account (or paginated chunk thereof)
            //   - for each account: joins Customer + Card +
            //     Transaction (in statement period via the CardCrossReference
            //     AIX equivalent)
            //   - generates BOTH text (80-byte LRECL) and HTML (100-byte
            //     LRECL) statements using the Template Method pattern per
            //     AAP §0.3.3
            //   - writes both outputs to S3 via S3OutputService.writeReport
            //   - tracks textCount, htmlCount, errorCount
            final StatementResult result =
                    statementGenerationService.generateStatements(statementMonth);

            // Surface counts into ExecutionContext for downstream
            // listeners and Step Functions Choice states.
            stepExecution.getExecutionContext()
                    .putInt(CTX_TEXT_COUNT, result.textCount());
            stepExecution.getExecutionContext()
                    .putInt(CTX_HTML_COUNT, result.htmlCount());
            stepExecution.getExecutionContext()
                    .putInt(CTX_ERROR_COUNT, result.errorCount());

            // Bump StepContribution counts so the Spring Batch
            // BATCH_STEP_EXECUTION row reflects the work performed.
            contribution.incrementReadCount();
            contribution.incrementWriteCount(
                    result.textCount() + result.htmlCount());

            // Set ExitStatus description summarizing the counts for
            // AWS Batch + Step Functions consumers (the actual exit
            // code is resolved by StatementStepExitStatusListener
            // based on errorCount).
            final String summaryDescription = "Generated "
                    + result.textCount() + " text statements and "
                    + result.htmlCount() + " HTML statements ("
                    + result.errorCount() + " errors)";
            contribution.setExitStatus(
                    ExitStatus.COMPLETED.addExitDescription(summaryDescription));

            // Emit a structured COMPLETED audit document carrying the
            // run-level counts. duration is null here because the
            // wall-clock duration is captured by the JobExecutionListener
            // afterJob callback in statementGenerationJob() above.
            final Map<String, Object> auditFields = new LinkedHashMap<>();
            auditFields.put("batchRunId", batchRunId);
            auditFields.put("statementMonth", statementMonth.toString());
            auditFields.put("textCount", result.textCount());
            auditFields.put("htmlCount", result.htmlCount());
            auditFields.put("errorCount", result.errorCount());
            auditFields.put("auditSource", auditSource);
            auditLogService.logBatchJobLifecycle(
                    JOB_NAME,
                    String.valueOf(stepExecution.getJobExecutionId()),
                    "COMPLETED",
                    null,
                    auditFields,
                    jobParameters.getString(PARAM_CORRELATION_ID));

            LOG.info(
                    "// COBOL: CBSTM03A+B - statement generation complete: "
                            + "batchRunId={}, textCount={}, htmlCount={}, errorCount={}",
                    batchRunId, result.textCount(),
                    result.htmlCount(), result.errorCount());

            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Extracts the {@code correlationId} {@link JobParameters} entry,
     * generating a fresh {@link UUID#randomUUID()} string when the
     * parameter is absent or blank.
     *
     * <p>Guarantees that every
     * {@link AuditLogService#logBatchJobLifecycle} callback emits a
     * non-null correlation identifier so distributed traces in
     * OpenSearch and CloudWatch remain joinable across the Step
     * Functions execution, AWS Batch job, ECS Fargate task, Spring
     * Batch job, and OpenSearch audit documents per AAP &sect;0.6.6
     * (Cross-Cutting Audit + Observability).</p>
     *
     * @param jobParameters the {@link JobParameters} of the current
     *                      execution; may be {@code null}
     * @return the supplied correlation ID, or a freshly-generated UUID
     *         when none was supplied
     */
    private String extractCorrelationId(JobParameters jobParameters) {
        if (jobParameters == null) {
            return UUID.randomUUID().toString();
        }
        final String correlationId = jobParameters.getString(PARAM_CORRELATION_ID);
        return (correlationId != null && !correlationId.isBlank())
                ? correlationId
                : UUID.randomUUID().toString();
    }

    /**
     * Computes the wall-clock duration between Spring Batch
     * {@link JobExecution#getStartTime()} and
     * {@link JobExecution#getEndTime()} in milliseconds.
     *
     * <p>Returns {@code 0L} defensively if either timestamp is
     * {@code null}, which can occur on abended jobs whose end-time was
     * never populated &mdash; preventing a {@link NullPointerException}
     * from breaking the audit emission for an abnormal termination.
     * The Spring Batch 5.x API returns
     * {@link java.time.LocalDateTime} from both methods, and
     * {@link Duration#between} accepts any {@link java.time.temporal.Temporal}
     * type.</p>
     *
     * @param startTime the job execution start time; may be {@code null}
     * @param endTime   the job execution end time; may be {@code null}
     * @return the elapsed time in milliseconds, or {@code 0L} if either
     *         timestamp is {@code null}
     */
    private static long computeDurationMillis(java.time.LocalDateTime startTime,
                                              java.time.LocalDateTime endTime) {
        if (startTime == null || endTime == null) {
            return 0L;
        }
        return Duration.between(startTime, endTime).toMillis();
    }

    /**
     * {@link StepExecutionListener} for the statement-generation step.
     *
     * <p>Maps a non-zero
     * {@link StatementResult#errorCount() errorCount} (surfaced into
     * the step {@link org.springframework.batch.item.ExecutionContext}
     * by {@link #generateStatementsTasklet()}) to
     * {@link CardDemoExitStatus#COMPLETED_WITH_REJECTS} (COBOL
     * {@code RETURN-CODE = 4}) so downstream Step Functions Choice
     * states can branch on partial-success runs. Zero errors map to
     * {@link ExitStatus#COMPLETED}.</p>
     *
     * <p>Preserves any non-{@code COMPLETED} ExitStatus that may have
     * been set by upstream Spring Batch fault-tolerance machinery
     * (e.g., {@code FAILED} when the tasklet threw an exception). This
     * ensures the {@link CardDemoExitStatus#COMPLETED_WITH_REJECTS}
     * mapping only fires when the step has otherwise completed
     * successfully.</p>
     *
     * <p><b>Static nested class:</b> declared static so it neither
     * captures an implicit reference to the enclosing
     * {@code StatementGenerationJob} instance nor depends on any
     * non-static state &mdash; making it trivially thread-safe and
     * suitable as a Spring Batch listener attached at
     * {@code @Configuration} bean-construction time.</p>
     */
    private static final class StatementStepExitStatusListener implements StepExecutionListener {

        /**
         * Step-scoped logger separate from the enclosing
         * {@link StatementGenerationJob#LOG} so the audit log clearly
         * shows whether a message originated from the step listener
         * or from the tasklet.
         */
        private static final Logger STEP_LOG =
                LoggerFactory.getLogger(StatementStepExitStatusListener.class);

        @Override
        public void beforeStep(StepExecution stepExecution) {
            // No-op — every required pre-step setup happens inside
            // the tasklet itself (parameter validation, audit emission).
        }

        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            // Preserve failure status — CBSTM03A's RETURN-CODE = 8
            // ABEND semantics map to Spring Batch BatchStatus.FAILED
            // / ExitStatus.FAILED which must NOT be downgraded to
            // COMPLETED_WITH_REJECTS.
            final ExitStatus existing = stepExecution.getExitStatus();
            if (existing != null && !"COMPLETED".equals(existing.getExitCode())) {
                STEP_LOG.warn(
                        "StatementGenerationJob step exitStatus={} preserved (not downgraded to COMPLETED_WITH_REJECTS)",
                        existing.getExitCode());
                return existing;
            }
            // When no errorCount was published (e.g., the tasklet
            // failed before reaching the count-publication line), the
            // default COMPLETED is the conservative choice — any real
            // failure would have already set existing.getExitCode()
            // away from "COMPLETED" above.
            if (!stepExecution.getExecutionContext().containsKey(CTX_ERROR_COUNT)) {
                return ExitStatus.COMPLETED;
            }
            final int errorCount =
                    stepExecution.getExecutionContext().getInt(CTX_ERROR_COUNT);
            if (errorCount > 0) {
                STEP_LOG.warn(
                        "StatementGenerationJob completed with {} per-account errors "
                                + "(mapping to COMPLETED_WITH_REJECTS, COBOL RETURN-CODE = 4)",
                        errorCount);
                return CardDemoExitStatus.COMPLETED_WITH_REJECTS;
            }
            return ExitStatus.COMPLETED;
        }
    }
}
