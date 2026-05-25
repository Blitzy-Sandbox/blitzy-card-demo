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
import com.awsm2.carddemo.service.TransactionPostingService;
import com.awsm2.carddemo.service.TransactionPostingService.PostingResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Batch {@code @Configuration} class for the
 * {@code dailyTransactionPostingJob}.
 *
 * <p><b>// Replaces: app/jcl/POSTTRAN.jcl + app/cbl/CBTRN01C.cbl +
 * app/cbl/CBTRN02C.cbl + app/cbl/CBTRN03C.cbl</b> &mdash; the COBOL
 * end-of-day transaction-posting program executed by JES2 as part of
 * the JCL job stream documented in {@code README.md} (Running full
 * batch).</p>
 *
 * <h2>Source Lineage</h2>
 *
 * <p>The original {@code POSTTRAN.jcl} JCL job consisted of three
 * cascading {@code EXEC PGM=} steps:</p>
 * <ol>
 *   <li>{@code STEP05 EXEC PGM=CBTRN01C} &mdash; sequential reader /
 *       dump utility that walked the {@code DALYTRAN-RECORD} VSAM
 *       cluster and emitted a tracing trail to {@code SYSPRINT}. In
 *       the Java target this is replaced by structured Logback +
 *       CloudWatch Logs emission from
 *       {@link TransactionPostingService} per AAP &sect;0.6.6.</li>
 *   <li>{@code STEP15 EXEC PGM=CBTRN02C} &mdash; the canonical 4-stage
 *       transaction-posting cascade per {@code CBTRN02C.cbl}
 *       (L194-L234 PROCEDURE DIVISION main loop): XREF lookup,
 *       Account lookup, credit-limit check, expiration check. Records
 *       that fail validation are written to the {@code DALYREJS}
 *       sequential file (replaced by S3 versioned objects per AAP
 *       &sect;0.6.2). Successful records are persisted to the
 *       canonical {@code TRANSACT} VSAM cluster (replaced by the JPA
 *       {@code transactions} journal).</li>
 *   <li>{@code STEP20 EXEC PGM=CBTRN03C} &mdash; the transaction
 *       report writer; that step is owned by the separate
 *       {@link TransactionReportJob} per AAP &sect;0.4.1 to keep
 *       step responsibility one-program-per-job.</li>
 * </ol>
 *
 * <h2>Java/Spring Batch Replacement</h2>
 *
 * <p>The {@code dailyTransactionPostingJob} is composed of a single
 * orchestration step that invokes
 * {@link TransactionPostingService#postDailyTransactions(LocalDate)}.
 * The service contains the 4-stage validation cascade verbatim from
 * the COBOL source and handles per-record commit boundaries (via
 * {@code @Transactional(propagation = REQUIRES_NEW)}) plus reject
 * emission to S3 via {@code S3OutputService}.</p>
 *
 * <p><b>Why a tasklet (not chunk-oriented for this job)?</b>
 * {@link TransactionPostingService} is itself a chunked / per-record
 * loop that performs the COBOL cascade exactly: it reads each row
 * from {@link com.awsm2.carddemo.repository.DailyTransactionRepository},
 * runs the validation pipeline, writes to {@code transactions}
 * (commit per record), and writes rejections to S3. Wrapping this in
 * a Spring Batch chunk step would <em>fight</em> the
 * {@code @Transactional(REQUIRES_NEW)} per-record commit boundaries
 * the service deliberately uses to mirror COBOL semantics. The
 * tasklet pattern wraps the whole service invocation as a single
 * Spring Batch step that surfaces the {@link PostingResult} record-
 * count summary to the step's {@code ExecutionContext} and maps the
 * service's {@code returnCode} (0 or 4) to the step's
 * {@link ExitStatus} per AAP &sect;0.7.1 RETURN-CODE parity
 * (F-CP6-Combine-04).</p>
 *
 * <h2>RETURN-CODE Parity (AAP &sect;0.7.1)</h2>
 *
 * <p>The step's terminal {@link ExitStatus} is mapped per
 * {@link CardDemoExitStatus}:</p>
 * <ul>
 *   <li>{@link PostingResult#returnCode()} = {@code 0} &rArr;
 *       {@link ExitStatus#COMPLETED}</li>
 *   <li>{@link PostingResult#returnCode()} = {@code 4} &rArr;
 *       {@link CardDemoExitStatus#COMPLETED_WITH_REJECTS} (rejects
 *       written to S3; downstream Step Functions Choice state may
 *       branch on the non-zero reject count)</li>
 *   <li>any unhandled {@code Throwable} &rArr; {@link ExitStatus#FAILED}
 *       (Step Functions Catch / Retry policy triggers)</li>
 * </ul>
 *
 * <h2>End-of-Day Pipeline Position</h2>
 *
 * <p>This job is <b>Stage 1</b> of the end-of-day batch pipeline
 * orchestrated by AWS Step Functions per AAP &sect;0.6.3:</p>
 * <pre>
 *   POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; Parallel { CREASTMT, TRANREPT }
 * </pre>
 *
 * <h2>Job Parameters</h2>
 *
 * <p>The job accepts the following {@code JobParameters}:</p>
 * <ul>
 *   <li>{@code batchRunId} (REQUIRED) &mdash; unique identifier for
 *       this batch run; supplied by Step Functions execution input
 *       or by the AWS Batch container's {@code commandArgs}; used
 *       for idempotency, audit-trail correlation, and S3 reject
 *       object-key generation per AAP &sect;0.6.3.</li>
 *   <li>{@code businessDate} (REQUIRED) &mdash; the business date for
 *       this batch run, ISO-8601 format {@code yyyy-MM-dd}; consumed
 *       by {@code TransactionPostingService} as the {@code batchDate}
 *       used in the S3 reject object-key prefix.</li>
 *   <li>{@code correlationId} (OPTIONAL) &mdash; if supplied, threaded
 *       through the {@code AuditLogService} so all audit events for
 *       this batch run share a single MDC trace identifier.</li>
 * </ul>
 *
 * @see TransactionPostingService
 * @see CardDemoExitStatus
 */
@Configuration("dailyTransactionPostingJobConfiguration")
public class DailyTransactionPostingJob {

    /**
     * SLF4J logger for the job lifecycle.
     */
    private static final Logger LOG = LoggerFactory.getLogger(DailyTransactionPostingJob.class);

    /**
     * The Spring Batch {@link Job} bean name. AWS Batch / Step
     * Functions resolve and launch the job by this exact name; do not
     * rename without updating the corresponding AWS Batch job
     * definition's {@code commandArgs}.
     */
    public static final String JOB_NAME = "dailyTransactionPostingJob";

    /**
     * The Spring Batch {@link Step} bean name.
     */
    public static final String STEP_NAME = "postDailyTransactionsStep";

    /**
     * {@link org.springframework.batch.core.JobParameters} key for the
     * required batch run identifier.
     */
    public static final String PARAM_BATCH_RUN_ID = "batchRunId";

    /**
     * {@link org.springframework.batch.core.JobParameters} key for the
     * required business date.
     */
    public static final String PARAM_BUSINESS_DATE = "businessDate";

    /**
     * {@link StepExecution.ExecutionContext} key under which the step
     * publishes the {@link PostingResult#transactionCount()} for
     * inspection by downstream Step Functions states.
     */
    public static final String CTX_TRANSACTION_COUNT = "transactionCount";

    /**
     * {@link StepExecution.ExecutionContext} key under which the step
     * publishes the {@link PostingResult#rejectCount()}.
     */
    public static final String CTX_REJECT_COUNT = "rejectCount";

    /**
     * {@link StepExecution.ExecutionContext} key under which the step
     * publishes the {@link PostingResult#returnCode()}.
     */
    public static final String CTX_RETURN_CODE = "returnCode";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final TransactionPostingService transactionPostingService;
    private final AuditLogService auditLogService;

    /**
     * Constructs the job configuration with all required collaborators
     * via constructor injection (per AAP &sect;0.7.1).
     *
     * @param jobRepository             Spring Batch metadata repository
     *                                  (auto-configured by Spring Boot
     *                                  from the {@code JpaConfig}
     *                                  DataSource)
     * @param transactionManager        JPA transaction manager
     *                                  (auto-configured by
     *                                  {@code HibernateJpaAutoConfiguration})
     * @param transactionPostingService the service implementing the
     *                                  4-stage validation cascade and
     *                                  per-record persistence
     * @param auditLogService           the audit-log adapter for
     *                                  lifecycle events
     */
    public DailyTransactionPostingJob(JobRepository jobRepository,
                                      PlatformTransactionManager transactionManager,
                                      TransactionPostingService transactionPostingService,
                                      AuditLogService auditLogService) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.transactionPostingService = transactionPostingService;
        this.auditLogService = auditLogService;
    }

    /**
     * Defines the {@code dailyTransactionPostingJob} Spring Batch
     * {@link Job} bean.
     *
     * <p>// Replaces: app/jcl/POSTTRAN.jcl STEP15 EXEC PGM=CBTRN02C</p>
     *
     * <p>The job is composed of a single tasklet step
     * ({@link #postDailyTransactionsStep()}) that wraps the
     * {@link TransactionPostingService}. Lifecycle audit listening is
     * delegated to the shared
     * {@code sharedAuditJobExecutionListener} bean (defined in
     * {@link BatchJobConfig}) per F-CP6-Combine-06.</p>
     *
     * <p>Parameter validation is enforced via the shared
     * {@code standardJobParametersValidator} bean defined in
     * {@link BatchJobConfig#standardJobParametersValidator()}. This
     * validator rejects any launch attempt that omits the mandatory
     * {@code batchRunId} JobParameter &mdash; preventing untraceable
     * batch executions (AAP &sect;0.7.1 audit-traceability rule:
     * every batch invocation MUST carry a {@code batchRunId} for
     * AuditLogService correlation across OpenSearch / CloudTrail /
     * CloudWatch).</p>
     *
     * @param sharedAuditJobExecutionListener the shared lifecycle audit
     *                                        listener bean from
     *                                        {@link BatchJobConfig}
     * @param standardJobParametersValidator  the shared JobParameters
     *                                        validator bean from
     *                                        {@link BatchJobConfig}
     *                                        that enforces the mandatory
     *                                        {@code batchRunId} parameter
     * @return the configured {@link Job} bean &mdash; registered in the
     *         {@code ApplicationContext} under the name
     *         {@value #JOB_NAME}
     */
    @Bean
    public Job dailyTransactionPostingJob(JobExecutionListener sharedAuditJobExecutionListener,
                                          JobParametersValidator standardJobParametersValidator) {
        // Replaces: app/jcl/POSTTRAN.jcl STEP15 EXEC PGM=CBTRN02C
        // Wire the standardJobParametersValidator so every launch is
        // validated for the mandatory batchRunId parameter. This is the
        // single audit-traceability gate that ties every batch run to a
        // unique identifier used downstream by AuditLogService and
        // CloudTrail (AAP §0.7.1).
        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(standardJobParametersValidator)
                .listener(sharedAuditJobExecutionListener)
                .start(postDailyTransactionsStep())
                .build();
    }

    /**
     * Defines the orchestration tasklet step that invokes the
     * {@link TransactionPostingService}.
     *
     * <p>// Replaces: app/cbl/CBTRN02C.cbl entire PROCEDURE DIVISION
     * main loop (L194-L234)</p>
     *
     * <p>The tasklet:</p>
     * <ol>
     *   <li>Reads the required {@code businessDate} job parameter and
     *       parses it as an ISO-8601 date.</li>
     *   <li>Invokes
     *       {@link TransactionPostingService#postDailyTransactions(LocalDate)}.</li>
     *   <li>Publishes the returned {@link PostingResult} field values
     *       to the step's {@code ExecutionContext} so downstream Step
     *       Functions states / operational tooling can inspect them.</li>
     *   <li>Returns {@link RepeatStatus#FINISHED} because the tasklet
     *       is a single-pass orchestration over the service.</li>
     * </ol>
     *
     * <p>The {@link CardDemoStepExitStatusListener} listener inspects
     * the {@code returnCode} stored in the execution context and maps
     * it to the corresponding Spring Batch {@link ExitStatus}
     * (RETURN-CODE parity per AAP &sect;0.7.1).</p>
     *
     * @return the configured {@link Step} bean &mdash; registered in the
     *         {@code ApplicationContext} under the name
     *         {@value #STEP_NAME}
     */
    @Bean
    public Step postDailyTransactionsStep() {
        // Replaces: app/cbl/CBTRN02C.cbl entire PROCEDURE DIVISION
        // main loop (L194-L234).
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(postDailyTransactionsTasklet(), transactionManager)
                .listener(new CardDemoStepExitStatusListener())
                .build();
    }

    /**
     * Defines the {@link Tasklet} that performs the actual
     * transaction-posting orchestration over the
     * {@link TransactionPostingService}.
     *
     * <p>The tasklet is a single-method functional implementation —
     * each invocation is a single pass over the staging table; the
     * service performs the per-record loop, the per-record commit
     * boundary, and the S3 reject emission.</p>
     *
     * <p>The tasklet is intentionally <em>not</em> wrapped in a Spring
     * Batch chunk because the {@link TransactionPostingService}
     * already orchestrates per-record commits via
     * {@code @Transactional(propagation = REQUIRES_NEW)} — exactly
     * the COBOL per-record commit semantics. Wrapping it in a chunk
     * would impose chunk-level commit boundaries that would conflict
     * with the per-record semantics the service deliberately
     * preserves.</p>
     *
     * @return a {@link Tasklet} that orchestrates the daily
     *         transaction-posting service invocation
     */
    @Bean
    public Tasklet postDailyTransactionsTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            // Replaces: app/cbl/CBTRN02C.cbl PROCEDURE DIVISION
            // (L194-L234 main loop).
            final StepExecution stepExecution =
                    chunkContext.getStepContext().getStepExecution();
            final Map<String, Object> auditFields = new LinkedHashMap<>();
            final String batchRunId =
                    stepExecution.getJobParameters().getString(PARAM_BATCH_RUN_ID);
            final String businessDateStr =
                    stepExecution.getJobParameters().getString(PARAM_BUSINESS_DATE);

            if (businessDateStr == null || businessDateStr.isBlank()) {
                throw new IllegalArgumentException(
                        "Required job parameter '" + PARAM_BUSINESS_DATE
                                + "' is missing (expected ISO-8601 yyyy-MM-dd)");
            }

            final LocalDate batchDate;
            try {
                batchDate = LocalDate.parse(businessDateStr);
            } catch (RuntimeException pex) {
                throw new IllegalArgumentException(
                        "Required job parameter '" + PARAM_BUSINESS_DATE
                                + "' is not a valid ISO-8601 date: '"
                                + businessDateStr + "'", pex);
            }

            LOG.info(
                    "DailyTransactionPostingJob: starting "
                            + "batchRunId={}, batchDate={}",
                    batchRunId, batchDate);

            // Invoke the service: the service performs the entire
            // per-record validation cascade + per-record commit +
            // S3 reject emission. The PostingResult summarizes the
            // run's record counts and the COBOL-equivalent RETURN-CODE.
            final PostingResult result =
                    transactionPostingService.postDailyTransactions(batchDate);

            // Publish the result fields to the step ExecutionContext so
            // downstream Step Functions / operators can inspect them.
            stepExecution.getExecutionContext()
                    .putInt(CTX_TRANSACTION_COUNT, result.transactionCount());
            stepExecution.getExecutionContext()
                    .putInt(CTX_REJECT_COUNT, result.rejectCount());
            stepExecution.getExecutionContext()
                    .putInt(CTX_RETURN_CODE, result.returnCode());

            // Record the step counters so Spring Batch metadata reflects
            // the work performed: total records read, total records
            // committed to the transactions journal.
            contribution.incrementReadCount();
            contribution.incrementWriteCount(result.transactionCount() - result.rejectCount());

            auditFields.put("batchRunId", batchRunId);
            auditFields.put("transactionCount", result.transactionCount());
            auditFields.put("rejectCount", result.rejectCount());
            auditFields.put("returnCode", result.returnCode());
            // Signature: (jobName, executionId, status, durationMillis, payload, correlationId)
            auditLogService.logBatchJobLifecycle(
                    JOB_NAME,
                    String.valueOf(stepExecution.getJobExecutionId()),
                    "COMPLETED",
                    null,
                    auditFields,
                    stepExecution.getJobParameters().getString("correlationId"));

            LOG.info(
                    "DailyTransactionPostingJob: completed batchRunId={}, "
                            + "transactionCount={}, rejectCount={}, returnCode={}",
                    batchRunId, result.transactionCount(),
                    result.rejectCount(), result.returnCode());

            return RepeatStatus.FINISHED;
        };
    }

    /**
     * {@link StepExecutionListener} that inspects the step's execution
     * context for the {@link #CTX_RETURN_CODE} value placed by the
     * tasklet and maps it to the corresponding Spring Batch
     * {@link ExitStatus} per {@link CardDemoExitStatus} (AAP
     * &sect;0.7.1 RETURN-CODE parity).
     */
    private static final class CardDemoStepExitStatusListener implements StepExecutionListener {

        @Override
        public void beforeStep(StepExecution stepExecution) {
            // No-op — initial exit status is COMPLETED by default;
            // afterStep below remaps based on the tasklet's
            // PostingResult.returnCode().
        }

        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            final ExitStatus existing = stepExecution.getExitStatus();

            // Defensive: if the step already failed, preserve FAILED.
            if (existing != null && !"COMPLETED".equals(existing.getExitCode())) {
                return existing;
            }

            if (!stepExecution.getExecutionContext().containsKey(CTX_RETURN_CODE)) {
                // Tasklet did not run / publish its returnCode; default
                // to COMPLETED so the step status reflects the absence
                // of explicit failure.
                return ExitStatus.COMPLETED;
            }

            final int returnCode =
                    stepExecution.getExecutionContext().getInt(CTX_RETURN_CODE);
            final ExitStatus mapped = CardDemoExitStatus.fromReturnCode(returnCode);

            if (returnCode == CardDemoExitStatus.RETURN_CODE_WITH_REJECTS) {
                LOG.warn(
                        "Daily transaction posting completed with rejects "
                                + "(returnCode={}, mapping to {})",
                        returnCode, mapped.getExitCode());
            } else {
                LOG.info(
                        "Daily transaction posting completed cleanly "
                                + "(returnCode={}, mapping to {})",
                        returnCode, mapped.getExitCode());
            }

            return mapped;
        }
    }
}
