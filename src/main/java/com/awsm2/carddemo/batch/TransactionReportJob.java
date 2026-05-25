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
import com.awsm2.carddemo.service.TransactionReportService;
import com.awsm2.carddemo.service.TransactionReportService.ReportResult;

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
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Spring Batch {@code @Configuration} class for the
 * {@code transactionReportJob}.
 *
 * <p><b>// COBOL: CBTRN03C</b><br>
 * <b>// Replaces: app/jcl/TRANREPT.jcl + app/cbl/CBTRN03C.cbl
 * (report variant)</b> &mdash; the COBOL end-of-day transaction-report
 * program executed by JES2 as Stage 4b of the end-of-day batch pipeline
 * per AAP &sect;0.6.3.</p>
 *
 * <h2>Source Lineage</h2>
 *
 * <p>The original {@code TRANREPT.jcl} JCL job consisted of three steps:</p>
 * <ol>
 *   <li>{@code REPROC} &mdash; copy the live {@code TRANSACT} VSAM cluster
 *       to a generation-data-group (GDG) backup
 *       ({@code AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)}). Replaced operationally
 *       by RDS Multi-AZ continuous replication and S3 versioned object
 *       persistence (AAP &sect;0.6.2); not part of this Spring Batch job.</li>
 *   <li>{@code SORT} &mdash; DFSORT step that filtered the backup by date
 *       window and sorted by card number. Replaced by the indexed JPA
 *       query {@code TransactionRepository.findAll()} plus an in-memory
 *       comparator inside {@link TransactionReportService}.</li>
 *   <li>{@code EXEC PGM=CBTRN03C} &mdash; the COBOL program itself, which
 *       streamed the date-filtered transactions, joined with
 *       {@code CARDXREF} / {@code TRANTYPE} / {@code TRANCATG} lookups,
 *       emitted fixed-width detail lines plus per-card subtotals plus
 *       page totals plus a grand total to the {@code TRANREPT} GDG
 *       (LRECL=133, RECFM=FB). The Java target wraps the entire
 *       {@link TransactionReportService#generateReport(LocalDate, LocalDate)
 *       generateReport} invocation in this Spring Batch tasklet; the
 *       service performs the streaming, sorting, formatting, and S3
 *       upload via {@code S3OutputService}.</li>
 * </ol>
 *
 * <h2>Java/Spring Batch Replacement</h2>
 *
 * <p>The {@code transactionReportJob} delegates the entire report-generation
 * pipeline to {@link TransactionReportService}:</p>
 * <ul>
 *   <li>Date-window filter on {@code TRAN-PROC-TS}.</li>
 *   <li>Sort by card number, then by processing timestamp (CBTRN03C
 *       sort discipline preserved verbatim).</li>
 *   <li>Card-change subtotal pattern (CBTRN03C
 *       {@code WS-CURR-CARD-NUM} tracker at L181-L188).</li>
 *   <li>Page totals every {@code WS-PAGE-SIZE=20} detail lines (COBOL
 *       L131-L132).</li>
 *   <li>Grand total at end of report (CBTRN03C
 *       {@code 1110-WRITE-GRAND-TOTALS} at L318-L322).</li>
 *   <li>Fixed-width LRECL=133 line formatting (preserved from
 *       TRANREPT JCL {@code DCB=(LRECL=133,RECFM=FB)}).</li>
 *   <li>Upload to S3 via {@code S3OutputService.writeReport(...)}
 *       (AAP &sect;0.6.2 &mdash; GDG &rarr; S3 versioned object
 *       replacement).</li>
 * </ul>
 *
 * <p><b>Why a tasklet (not chunk-oriented)?</b> The
 * {@link TransactionReportService} is a
 * {@code @Transactional(readOnly = true)} read-only iteration over the
 * transactions table that aggregates by card and renders a fixed-width
 * report. The COBOL semantic produces ONE report object per run;
 * chunking would force splitting the report across multiple S3 objects,
 * breaking the single-output guarantee and the LRECL=133 page layout.</p>
 *
 * <h2>End-of-Day Pipeline Position</h2>
 *
 * <p>This job is <b>Stage 4b</b> of the end-of-day batch pipeline
 * orchestrated by AWS Step Functions per AAP &sect;0.6.3:</p>
 * <pre>
 *   POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; Parallel { CREASTMT, TRANREPT }
 * </pre>
 *
 * <p>Stage 4a ({@code StatementGenerationJob}) and Stage 4b
 * ({@code TransactionReportJob}, this job) run in parallel downstream
 * of {@code CombineTransactionsJob}.</p>
 *
 * <h2>Job Parameters</h2>
 * <ul>
 *   <li>{@code batchRunId} (OPTIONAL) &mdash; unique batch run identifier
 *       (commonly emitted by Step Functions when launching the job).</li>
 *   <li>{@code startDate} (REQUIRED) &mdash; inclusive lower bound of
 *       the report's date window (ISO-8601 {@code yyyy-MM-dd}).</li>
 *   <li>{@code endDate} (REQUIRED) &mdash; inclusive upper bound of the
 *       report's date window (ISO-8601 {@code yyyy-MM-dd}).</li>
 *   <li>{@code correlationId} (OPTIONAL) &mdash; for distributed-trace
 *       correlation; a random UUID is generated when absent.</li>
 * </ul>
 *
 * <h2>Audit emission (AAP &sect;0.6.6)</h2>
 * <p>Two complementary audit emissions are produced for every run:</p>
 * <ol>
 *   <li>An inline {@link JobExecutionListener} attached to the
 *       {@link Job} emits {@code STARTED} (on {@code beforeJob}) and
 *       the final terminal status (on {@code afterJob}) lifecycle
 *       events with the computed duration. This replaces the JES
 *       SYSPRINT + RETURN-CODE inspection from the original
 *       TRANREPT.jcl.</li>
 *   <li>The {@link Tasklet} body emits a {@code COMPLETED} business
 *       event after the {@link ReportResult} is populated, carrying
 *       the transaction count, page count, grand total, and S3 key as
 *       structured audit metadata so the OpenSearch document is
 *       directly queryable by reconciliation and regulatory tooling.
 *       Per AAP &sect;0.6.6 this preserves the COBOL audit-trail field
 *       semantics verbatim.</li>
 * </ol>
 *
 * @see TransactionReportService
 * @see AuditLogService#logBatchJobLifecycle(String, String, String, Long, Map, String)
 */
@Configuration("transactionReportJobConfiguration")
public class TransactionReportJob {

    /**
     * SLF4J logger emitting structured JSON via the
     * {@code logstash-logback-encoder} configured in
     * {@code logback-spring.xml} and shipped to CloudWatch Logs per
     * AAP &sect;0.6.6.
     */
    private static final Logger log = LoggerFactory.getLogger(TransactionReportJob.class);

    /**
     * The Spring Batch {@link Job} bean name.
     */
    public static final String JOB_NAME = "transactionReportJob";

    /**
     * The Spring Batch {@link Step} bean name.
     */
    public static final String STEP_NAME = "generateTransactionReportStep";

    /**
     * {@link JobParameters} key for the batch run identifier.
     */
    public static final String PARAM_BATCH_RUN_ID = "batchRunId";

    /**
     * {@link JobParameters} key for the inclusive lower bound of the
     * report's date window. The COBOL source reads the equivalent
     * value from the {@code DATEPARM} DD file (CBTRN03C L466-L482 /
     * L220-L243).
     */
    public static final String PARAM_START_DATE = "startDate";

    /**
     * {@link JobParameters} key for the inclusive upper bound of the
     * report's date window. The COBOL source reads the equivalent
     * value from the {@code DATEPARM} DD file (CBTRN03C L466-L482 /
     * L220-L243).
     */
    public static final String PARAM_END_DATE = "endDate";

    /**
     * {@link JobParameters} key for the (optional) distributed-trace
     * correlation identifier. A fresh {@link UUID} is generated when
     * the parameter is absent so the audit document always carries a
     * non-null correlation ID.
     */
    public static final String PARAM_CORRELATION_ID = "correlationId";

    /**
     * Execution-context key for the number of transactions included
     * in the rendered report. Replaces the COBOL
     * {@code WS-LINE-COUNTER} (L129-L130) read-count semantic.
     */
    public static final String CTX_TRANSACTION_COUNT = "transactionCount";

    /**
     * Execution-context key for the report's page count. Replaces
     * the COBOL implicit page count derived from
     * {@code FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE)} (L282).
     */
    public static final String CTX_PAGE_COUNT = "pageCount";

    /**
     * Execution-context key for the report's grand total (stringified
     * to preserve {@link java.math.BigDecimal} precision per AAP
     * &sect;0.6.1). Replaces the COBOL {@code WS-GRAND-TOTAL}
     * {@code PIC S9(09)V99} field (L136).
     */
    public static final String CTX_GRAND_TOTAL = "grandTotal";

    /**
     * Execution-context key for the S3 object key under which the
     * rendered report was persisted. Replaces the original COBOL
     * {@code TRANREPT(+1)} GDG generation identifier.
     */
    public static final String CTX_S3_KEY = "s3Key";

    /**
     * Audit emission status emitted by the tasklet on successful
     * completion. The Spring Batch lifecycle listener separately
     * emits the {@code STARTED} and final {@code COMPLETED}/{@code FAILED}
     * status from the job execution.
     */
    static final String LIFECYCLE_STATUS_STARTED = "STARTED";

    /**
     * Audit emission status emitted by the tasklet body when the
     * report has been written to S3 and the {@link ReportResult}
     * metadata is available.
     */
    static final String LIFECYCLE_STATUS_COMPLETED = "COMPLETED";

    /**
     * Spring Batch {@link JobRepository} bean &mdash; constructor-
     * injected from the auto-configuration declared by
     * {@code com.awsm2.carddemo.config.BatchConfig} (via
     * {@code @EnableBatchProcessing} in {@code BatchJobConfig}) and
     * backed by the {@code BATCH_*} metadata tables persisted in
     * RDS PostgreSQL per AAP &sect;0.4.1.
     */
    private final JobRepository jobRepository;

    /**
     * Spring {@link PlatformTransactionManager} bean &mdash; the JPA
     * {@code JpaTransactionManager} auto-configured by Spring Boot
     * from the {@code @Primary @RefreshScope DataSource} declared by
     * {@code com.awsm2.carddemo.config.JpaConfig}. Wired into
     * {@link StepBuilder#tasklet(Tasklet, PlatformTransactionManager)}
     * so the Spring Batch metadata writes
     * ({@code BATCH_JOB_INSTANCE}, {@code BATCH_STEP_EXECUTION}) share
     * the same RDS PostgreSQL transactional context as the application
     * reads delegated to {@link TransactionReportService}.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * The translated COBOL CBTRN03C report-generation service. The
     * Tasklet body delegates {@em all} report-generation logic
     * (date-window filter, sort, card-change subtotal pattern,
     * PAGE_SIZE=20 pagination, LRECL=133 line rendering, S3 upload)
     * to this collaborator. Per AAP &sect;0.7.1, the Job class itself
     * contains zero formatting or aggregation logic.
     */
    private final TransactionReportService transactionReportService;

    /**
     * Audit-log adapter &mdash; emits structured lifecycle events to
     * OpenSearch and Micrometer/CloudWatch per AAP &sect;0.6.6.
     * Replaces the JES SYSPRINT + RETURN-CODE inspection of the
     * original TRANREPT.jcl.
     */
    private final AuditLogService auditLogService;

    /**
     * Shared {@link JobParametersValidator} bean (defined in
     * {@link BatchJobConfig#standardJobParametersValidator()}) that
     * enforces the mandatory {@code batchRunId} JobParameter on every
     * launch &mdash; preventing untraceable batch executions per the
     * AAP &sect;0.7.1 audit-traceability rule.
     *
     * <p>The schema's exposed Job factory signature is the no-arg
     * {@code transactionReportJob()}, so the validator is injected
     * here as a constructor field and referenced from the no-arg Job
     * factory method via {@code this.standardJobParametersValidator}.</p>
     */
    private final JobParametersValidator standardJobParametersValidator;

    /**
     * Externalized configuration knob that lets operators tag the
     * audit document with a deployment-specific label without
     * recompiling. Defaults to the COBOL program identifier
     * ({@code CBTRN03C}) per AAP &sect;0.7.3 (refactor discipline
     * &mdash; preserve traceability to the source program).
     *
     * <p>Configured via the {@code carddemo.batch.transaction-report.audit-source}
     * property in {@code application.yml} (or any active profile
     * overlay) and sourced ultimately from AWS Secrets Manager /
     * Parameter Store at runtime per AAP &sect;0.7.1 externalized-
     * configuration directive.</p>
     */
    @Value("${carddemo.batch.transaction-report.audit-source:CBTRN03C}")
    private String auditSourceLabel;

    /**
     * Constructs the job configuration with all required collaborators
     * via constructor injection (per AAP &sect;0.7.1 &mdash;
     * "Dependency injection for loose coupling. Constructor injection
     * for all @Service, @Repository, @Component, adapter, and config
     * beans"). The schema-exposed constructor signature matches the
     * four final fields above; the {@link Value @Value}-injected
     * {@code auditSourceLabel} is property-injection (separate from
     * bean DI) and therefore not a constructor parameter.
     *
     * @param jobRepository                  Spring Batch metadata repository
     *                                       (from {@code BatchConfig})
     * @param transactionManager             JPA transaction manager (from
     *                                       {@code JpaConfig} via Spring Boot
     *                                       auto-configuration)
     * @param transactionReportService       the COBOL CBTRN03C report-
     *                                       generation service
     * @param auditLogService                the audit-log adapter
     * @param standardJobParametersValidator the shared JobParameters
     *                                       validator bean from
     *                                       {@link BatchJobConfig} that
     *                                       enforces the mandatory
     *                                       {@code batchRunId} parameter
     */
    public TransactionReportJob(JobRepository jobRepository,
                                PlatformTransactionManager transactionManager,
                                TransactionReportService transactionReportService,
                                AuditLogService auditLogService,
                                JobParametersValidator standardJobParametersValidator) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.transactionReportService = transactionReportService;
        this.auditLogService = auditLogService;
        this.standardJobParametersValidator = standardJobParametersValidator;
    }

    /**
     * Defines the {@code transactionReportJob} Spring Batch
     * {@link Job} bean.
     *
     * <p>// COBOL: CBTRN03C<br>
     * // Replaces: app/jcl/TRANREPT.jcl entire job stream
     * (EXEC PGM=CBTRN03C).</p>
     *
     * <p>An inline {@link JobExecutionListener} emits {@code STARTED}
     * on {@code beforeJob} and the final lifecycle status on
     * {@code afterJob}, with the elapsed duration computed via
     * {@link Duration#between(java.time.temporal.Temporal,
     * java.time.temporal.Temporal)}. The listener is intentionally
     * inlined (rather than reusing the shared lifecycle listener) so
     * the bean's responsibility is self-contained per the schema's
     * exposed {@code transactionReportJob()} signature.</p>
     *
     * @return the configured {@link Job} bean &mdash; registered in
     *         the {@code ApplicationContext} under the name
     *         {@value #JOB_NAME}
     */
    @Bean
    public Job transactionReportJob() {
        // Replaces: app/jcl/TRANREPT.jcl EXEC PGM=CBTRN03C
        // Wire the shared standardJobParametersValidator (injected via
        // the constructor as this.standardJobParametersValidator since
        // the schema-exposed signature is the no-arg
        // transactionReportJob()) so every launch is gated on a
        // non-blank batchRunId (AAP §0.7.1 audit-traceability rule).
        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(standardJobParametersValidator)
                .listener(new JobExecutionListener() {

                    @Override
                    public void beforeJob(JobExecution jobExecution) {
                        // Replaces: JES SYSPRINT job-start banner.
                        final String executionId = safeExecutionId(jobExecution);
                        final String correlationId = extractCorrelationId(
                                jobExecution.getJobParameters());
                        log.info(
                                "// Replaces: app/jcl/TRANREPT.jcl - "
                                        + "TransactionReportJob starting, executionId={}, "
                                        + "auditSource={}",
                                executionId, auditSourceLabel);
                        auditLogService.logBatchJobLifecycle(
                                JOB_NAME,
                                executionId,
                                LIFECYCLE_STATUS_STARTED,
                                0L,
                                null,
                                correlationId);
                    }

                    @Override
                    public void afterJob(JobExecution jobExecution) {
                        // Replaces: JES SYSPRINT job-end banner + RETURN-CODE.
                        final String executionId = safeExecutionId(jobExecution);
                        final long durationMillis = computeDurationMillis(jobExecution);
                        final String status = jobExecution.getStatus() != null
                                ? jobExecution.getStatus().name()
                                : "UNKNOWN";
                        final String exitCode = jobExecution.getExitStatus() != null
                                ? jobExecution.getExitStatus().getExitCode()
                                : "UNKNOWN";
                        log.info(
                                "TransactionReportJob completed: "
                                        + "executionId={}, status={}, exitCode={}, durationMs={}",
                                executionId, status, exitCode, durationMillis);
                        auditLogService.logBatchJobLifecycle(
                                JOB_NAME,
                                executionId,
                                status,
                                durationMillis,
                                null,
                                extractCorrelationId(jobExecution.getJobParameters()));
                    }
                })
                .start(generateTransactionReportStep())
                .build();
    }

    /**
     * Defines the orchestration tasklet step that invokes the
     * {@link TransactionReportService}.
     *
     * <p>// COBOL: CBTRN03C PROCEDURE DIVISION (L159-L210)<br>
     * // Replaces: app/jcl/TRANREPT.jcl STEP10R</p>
     *
     * @return the configured {@link Step} bean &mdash; registered in
     *         the {@code ApplicationContext} under the name
     *         {@value #STEP_NAME}
     */
    @Bean
    public Step generateTransactionReportStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(generateTransactionReportTasklet(), transactionManager)
                .build();
    }

    /**
     * Defines the {@link Tasklet} that drives the
     * {@link TransactionReportService#generateReport(LocalDate, LocalDate)}
     * invocation.
     *
     * <p>// COBOL: CBTRN03C PROCEDURE DIVISION (L159-L210)</p>
     *
     * <p>Behavior:</p>
     * <ol>
     *   <li>Reads {@code startDate} and {@code endDate} from
     *       {@link JobParameters}. Both are REQUIRED ISO-8601
     *       {@code yyyy-MM-dd} strings (a missing or malformed value
     *       throws {@link IllegalArgumentException}, which Spring
     *       Batch surfaces as a {@code BatchStatus.FAILED} job
     *       execution per the AWS Batch / Step Functions failure
     *       contract).</li>
     *   <li>Logs the inputs at INFO with the COBOL provenance comment
     *       for CloudWatch + OpenSearch correlation.</li>
     *   <li>Delegates the entire report-generation pipeline to
     *       {@link TransactionReportService#generateReport(LocalDate,
     *       LocalDate)}. The service:
     *       <ul>
     *         <li>Streams transactions in the date window using
     *             {@code TransactionRepository.findAll()} + in-memory
     *             filter.</li>
     *         <li>Sorts by card number then by processing
     *             timestamp.</li>
     *         <li>Joins each transaction with {@code TransactionType},
     *             {@code TransactionCategory}, and
     *             {@code CardCrossReference} via the corresponding
     *             repositories with local {@link Map} caches.</li>
     *         <li>Emits a subtotal line whenever the card number
     *             changes (CBTRN03C card-change pattern at L181-L188).</li>
     *         <li>Paginates output with PAGE_SIZE=20 (verbatim
     *             COBOL {@code WS-PAGE-SIZE}).</li>
     *         <li>Formats each line at LRECL=133 fixed-width.</li>
     *         <li>Uploads the assembled report to S3 via
     *             {@code S3OutputService.writeReport(...)}.</li>
     *       </ul></li>
     *   <li>Populates the {@link StepExecution} execution context
     *       with the four CTX_* keys so Step Functions and ops
     *       tooling can inspect the report metadata.</li>
     *   <li>Increments {@link StepContribution#incrementReadCount()
     *       readCount} and
     *       {@link StepContribution#incrementWriteCount(long)
     *       writeCount} so Spring Batch metrics reflect the work
     *       performed.</li>
     *   <li>Sets a structured {@link ExitStatus#COMPLETED} exit
     *       description so downstream Step Functions consumers can
     *       parse the run summary without re-reading the report.</li>
     *   <li>Emits a {@code COMPLETED} business audit event via
     *       {@link AuditLogService#logBatchJobLifecycle(String,
     *       String, String, Long, Map, String)} carrying the
     *       structured report fields as the payload.</li>
     * </ol>
     *
     * @return a {@link Tasklet} returning {@link RepeatStatus#FINISHED}
     *         after a single service invocation
     */
    @Bean
    public Tasklet generateTransactionReportTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            // Replaces: app/cbl/CBTRN03C.cbl PROCEDURE DIVISION (L159-L210)
            final StepExecution stepExecution =
                    chunkContext.getStepContext().getStepExecution();
            final JobParameters jobParameters = stepExecution.getJobParameters();

            // -----------------------------------------------------------------
            // Step 1: parameter resolution (replaces COBOL DATEPARM DD read in
            //         0500-DATEPARM-OPEN / 0550-DATEPARM-READ at L466-L482 /
            //         L220-L243). Missing or malformed values throw
            //         IllegalArgumentException; Spring Batch surfaces this as
            //         BatchStatus.FAILED which Step Functions reads as a Task
            //         failure (mapped to the AWS Batch container exit code).
            // -----------------------------------------------------------------
            final String batchRunId = jobParameters.getString(PARAM_BATCH_RUN_ID);
            final LocalDate startDate = resolveStartDate(jobParameters);
            final LocalDate endDate = resolveEndDate(jobParameters);

            log.info(
                    "// COBOL: CBTRN03C - generating transaction report: "
                            + "batchRunId={}, startDate={}, endDate={}",
                    batchRunId, startDate, endDate);

            // -----------------------------------------------------------------
            // Step 2: delegate report generation to the service. Per AAP
            //         §0.7.1, the Job class contains ZERO formatting or
            //         aggregation logic — everything is in the service.
            // -----------------------------------------------------------------
            final ReportResult result =
                    transactionReportService.generateReport(startDate, endDate);

            // Defensive guard: the service contract returns a non-null
            // ReportResult, but tests mock the service and can return null.
            // The Job protects itself rather than NPE'ing on result.* below.
            if (result == null) {
                throw new IllegalStateException(
                        "TransactionReportService.generateReport returned null "
                                + "for startDate=" + startDate + ", endDate=" + endDate);
            }

            // -----------------------------------------------------------------
            // Step 3: publish report metadata via the StepExecution context.
            //         Spring Batch persists this context to
            //         BATCH_STEP_EXECUTION_CONTEXT so the Step Functions
            //         downstream task can inspect the values without
            //         re-reading the report from S3.
            // -----------------------------------------------------------------
            final String grandTotalText = result.grandTotal() != null
                    ? result.grandTotal().toPlainString()
                    : "0.00";
            final String s3Key = result.s3Key() != null ? result.s3Key() : "";

            stepExecution.getExecutionContext()
                    .putInt(CTX_TRANSACTION_COUNT, result.transactionCount());
            stepExecution.getExecutionContext()
                    .putInt(CTX_PAGE_COUNT, result.pageCount());
            stepExecution.getExecutionContext()
                    .putString(CTX_GRAND_TOTAL, grandTotalText);
            stepExecution.getExecutionContext()
                    .putString(CTX_S3_KEY, s3Key);

            // -----------------------------------------------------------------
            // Step 4: Spring Batch metrics. readCount increments by ONE per
            //         tasklet invocation (we read the entire transaction
            //         population in one delegated call). writeCount increments
            //         by the number of transactions actually included in the
            //         report so downstream alarms can compare read vs write
            //         volumes for parity validation.
            // -----------------------------------------------------------------
            contribution.incrementReadCount();
            contribution.incrementWriteCount(result.transactionCount());

            // -----------------------------------------------------------------
            // Step 5: structured ExitStatus description. AWS Batch and Step
            //         Functions consumers parse this string to surface the
            //         report summary in the execution-history UI without
            //         re-reading the report from S3.
            // -----------------------------------------------------------------
            contribution.setExitStatus(ExitStatus.COMPLETED.addExitDescription(
                    "Emitted " + result.transactionCount()
                            + " transactions across " + result.pageCount()
                            + " pages (grandTotal=" + grandTotalText
                            + ", s3Key=" + s3Key + ")"));

            // -----------------------------------------------------------------
            // Step 6: business audit event. The shared lifecycle listener
            //         emits the STARTED / final-status events with the
            //         elapsed-duration timer; this in-Tasklet emission
            //         carries the structured report fields as payload so
            //         the OpenSearch document is directly queryable by
            //         reconciliation and regulatory tooling (AAP §0.6.6).
            // -----------------------------------------------------------------
            final Map<String, Object> auditFields = new LinkedHashMap<>();
            auditFields.put("auditSource", auditSourceLabel);
            auditFields.put("batchRunId", batchRunId);
            auditFields.put("startDate", startDate.toString());
            auditFields.put("endDate", endDate.toString());
            auditFields.put("transactionCount", result.transactionCount());
            auditFields.put("pageCount", result.pageCount());
            auditFields.put("grandTotal", grandTotalText);
            auditFields.put("s3Key", s3Key);

            auditLogService.logBatchJobLifecycle(
                    JOB_NAME,
                    String.valueOf(stepExecution.getJobExecutionId()),
                    LIFECYCLE_STATUS_COMPLETED,
                    null,
                    auditFields,
                    extractCorrelationId(jobParameters));

            log.info(
                    "// COBOL: CBTRN03C - report generation complete: "
                            + "batchRunId={}, transactionCount={}, pageCount={}, "
                            + "grandTotal={}, s3Key={}",
                    batchRunId, result.transactionCount(),
                    result.pageCount(), grandTotalText, s3Key);

            return RepeatStatus.FINISHED;
        };
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Resolves the {@code startDate} job parameter to a {@link LocalDate}.
     *
     * <p>Replaces the COBOL {@code WS-START-DATE PIC X(10)} field
     * (L123) read from the {@code DATEPARM} DD file by
     * {@code 0550-DATEPARM-READ}.</p>
     *
     * @param jobParameters the {@link JobParameters} carrying the
     *                      ISO-8601 {@code yyyy-MM-dd} string
     * @return the parsed {@link LocalDate}; never {@code null}
     * @throws IllegalArgumentException if the parameter is missing,
     *                                  blank, or not ISO-8601
     */
    private static LocalDate resolveStartDate(JobParameters jobParameters) {
        return parseRequiredIsoDate(jobParameters, PARAM_START_DATE);
    }

    /**
     * Resolves the {@code endDate} job parameter to a {@link LocalDate}.
     *
     * <p>Replaces the COBOL {@code WS-END-DATE PIC X(10)} field
     * (L125) read from the {@code DATEPARM} DD file by
     * {@code 0550-DATEPARM-READ}.</p>
     *
     * @param jobParameters the {@link JobParameters} carrying the
     *                      ISO-8601 {@code yyyy-MM-dd} string
     * @return the parsed {@link LocalDate}; never {@code null}
     * @throws IllegalArgumentException if the parameter is missing,
     *                                  blank, or not ISO-8601
     */
    private static LocalDate resolveEndDate(JobParameters jobParameters) {
        return parseRequiredIsoDate(jobParameters, PARAM_END_DATE);
    }

    /**
     * Reads a REQUIRED ISO-8601 date job parameter and parses it via
     * {@link DateTimeFormatter#ISO_DATE}.
     *
     * @param jobParameters the source {@link JobParameters}
     * @param paramName     the parameter name (used in error messages)
     * @return the parsed {@link LocalDate}
     * @throws IllegalArgumentException if the parameter is missing,
     *                                  blank, or not ISO-8601
     */
    private static LocalDate parseRequiredIsoDate(JobParameters jobParameters,
                                                  String paramName) {
        final String value = jobParameters != null
                ? jobParameters.getString(paramName)
                : null;
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Required JobParameter '" + paramName
                            + "' is missing or empty (expected ISO-8601 yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_DATE);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "JobParameter '" + paramName + "' is not a valid ISO-8601 date: '"
                            + value + "' (expected yyyy-MM-dd)",
                    ex);
        }
    }

    /**
     * Extracts the {@code correlationId} {@link JobParameters} entry,
     * generating a fresh {@link UUID#randomUUID()} when the parameter
     * is absent or blank.
     *
     * <p>This guarantees that every
     * {@link AuditLogService#logBatchJobLifecycle(String, String, String,
     * Long, Map, String) lifecycle audit emission} carries a non-null
     * correlation identifier so distributed traces remain linkable
     * across Step Functions execution, AWS Batch job, ECS task,
     * Spring Batch job, and OpenSearch audit documents (AAP &sect;0.6.6).</p>
     *
     * @param jobParameters the source {@link JobParameters}; may be
     *                      {@code null}
     * @return a non-null, non-blank correlation identifier
     */
    private static String extractCorrelationId(JobParameters jobParameters) {
        if (jobParameters == null) {
            return UUID.randomUUID().toString();
        }
        final String correlationId = jobParameters.getString(PARAM_CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return correlationId;
    }

    /**
     * Safely extracts the stringified execution identifier from a
     * {@link JobExecution}, defaulting to {@code "UNKNOWN"} when the
     * identifier is absent. The default prevents the audit document
     * from carrying a literal {@code "null"} string.
     *
     * @param jobExecution the {@link JobExecution}; may be {@code null}
     * @return the stringified execution identifier when available;
     *         otherwise the literal {@code "UNKNOWN"}
     */
    private static String safeExecutionId(JobExecution jobExecution) {
        if (jobExecution == null || jobExecution.getId() == null) {
            return "UNKNOWN";
        }
        return String.valueOf(jobExecution.getId());
    }

    /**
     * Computes the elapsed runtime in milliseconds for a Spring Batch
     * {@link JobExecution} using its {@code startTime} and
     * {@code endTime}. Returns {@code 0L} if either timestamp is
     * absent &mdash; the Spring Batch 5.x API uses
     * {@link java.time.LocalDateTime} for both fields and either may
     * be {@code null} for an in-flight job (before {@code afterJob})
     * or an abended job whose end-time was never populated.
     *
     * @param jobExecution the {@link JobExecution} carrying the start /
     *                     end timestamps; may be {@code null}
     * @return the elapsed runtime in milliseconds, never negative;
     *         {@code 0L} when either timestamp is {@code null} or
     *         when {@code endTime} is before {@code startTime}
     */
    private static long computeDurationMillis(JobExecution jobExecution) {
        if (jobExecution == null
                || jobExecution.getStartTime() == null
                || jobExecution.getEndTime() == null) {
            return 0L;
        }
        final long millis = Duration.between(
                jobExecution.getStartTime(),
                jobExecution.getEndTime()).toMillis();
        return Math.max(0L, millis);
    }
}
