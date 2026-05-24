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
import org.springframework.batch.core.JobExecutionListener;
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
 * {@code transactionReportJob}.
 *
 * <p><b>// Replaces: app/jcl/TRANREPT.jcl + app/cbl/CBTRN03C.cbl
 * (report variant)</b> &mdash; the COBOL end-of-day transaction-report
 * program executed by JES2 as Stage 4b of the end-of-day batch pipeline
 * per AAP &sect;0.6.3.</p>
 *
 * <h2>Source Lineage</h2>
 *
 * <p>The original {@code TRANREPT.jcl} JCL job consisted of an
 * {@code EXEC PGM=CBTRN03C} step that:</p>
 * <ol>
 *   <li>Streamed all {@code TRANSACT} VSAM rows (now JPA
 *       {@code transactions}).</li>
 *   <li>Filtered to those with {@code TRAN-PROC-TS}{@code (1:10)}
 *       within the inclusive {@code [startDate, endDate]} window.</li>
 *   <li>Sorted by {@code TRAN-CARD-NUM} then by
 *       {@code TRAN-PROC-TS} so the {@code WS-CURR-CARD-NUM} card-
 *       boundary tracker (COBOL L181-L188) detected card boundaries
 *       monotonically.</li>
 *   <li>Emitted fixed-width detail lines + per-card subtotals + page
 *       totals + grand total to the {@code TRANREPT} GDG (now
 *       replaced by S3 versioned objects per AAP &sect;0.6.2).</li>
 * </ol>
 *
 * <h2>Java/Spring Batch Replacement</h2>
 *
 * <p>The {@code transactionReportJob} wraps
 * {@link TransactionReportService#generateReport(LocalDate, LocalDate)}
 * in a single Spring Batch tasklet. The service performs the
 * transaction streaming, date-window filtering, in-memory sort, fixed-
 * width line rendering, and S3 upload via {@code S3OutputService}.</p>
 *
 * <p><b>Why a tasklet (not chunk-oriented)?</b>
 * {@link TransactionReportService} is a {@code @Transactional(readOnly = true)}
 * read-only iteration over the transactions table that aggregates by
 * card and renders a fixed-width report. The COBOL semantic produces
 * one report object per run; chunking would force splitting the report
 * across multiple S3 objects, breaking the single-output guarantee.</p>
 *
 * <h2>End-of-Day Pipeline Position</h2>
 *
 * <p>This job is <b>Stage 4b</b> of the end-of-day batch pipeline
 * orchestrated by AWS Step Functions per AAP &sect;0.6.3:</p>
 * <pre>
 *   POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; Parallel { CREASTMT, TRANREPT }
 * </pre>
 *
 * <p>Stage 4a (CREASTMT) and Stage 4b (TRANREPT) run in parallel
 * downstream of COMBTRAN.</p>
 *
 * <h2>Job Parameters</h2>
 * <ul>
 *   <li>{@code batchRunId} (REQUIRED) &mdash; unique batch run identifier.</li>
 *   <li>{@code startDate} (REQUIRED) &mdash; the inclusive lower bound
 *       of the report's date window (ISO-8601 {@code yyyy-MM-dd}).</li>
 *   <li>{@code endDate} (REQUIRED) &mdash; the inclusive upper bound
 *       of the report's date window (ISO-8601 {@code yyyy-MM-dd}).</li>
 *   <li>{@code correlationId} (OPTIONAL).</li>
 * </ul>
 *
 * @see TransactionReportService
 */
@Configuration("transactionReportJobConfiguration")
public class TransactionReportJob {

    /**
     * SLF4J logger for the job lifecycle.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionReportJob.class);

    /**
     * The Spring Batch {@link Job} bean name.
     */
    public static final String JOB_NAME = "transactionReportJob";

    /**
     * The Spring Batch {@link Step} bean name.
     */
    public static final String STEP_NAME = "generateTransactionReportStep";

    /**
     * {@link org.springframework.batch.core.JobParameters} key for the
     * batch run ID.
     */
    public static final String PARAM_BATCH_RUN_ID = "batchRunId";

    /**
     * {@link org.springframework.batch.core.JobParameters} key for the
     * inclusive lower bound of the report's date window.
     */
    public static final String PARAM_START_DATE = "startDate";

    /**
     * {@link org.springframework.batch.core.JobParameters} key for the
     * inclusive upper bound of the report's date window.
     */
    public static final String PARAM_END_DATE = "endDate";

    /**
     * Execution-context key for the number of transactions included
     * in the rendered report.
     */
    public static final String CTX_TRANSACTION_COUNT = "transactionCount";

    /**
     * Execution-context key for the report's page count.
     */
    public static final String CTX_PAGE_COUNT = "pageCount";

    /**
     * Execution-context key for the report's grand total (stringified
     * BigDecimal).
     */
    public static final String CTX_GRAND_TOTAL = "grandTotal";

    /**
     * Execution-context key for the S3 object key under which the
     * rendered report was persisted.
     */
    public static final String CTX_S3_KEY = "s3Key";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final TransactionReportService transactionReportService;
    private final AuditLogService auditLogService;

    /**
     * Constructs the job configuration with required collaborators
     * via constructor injection (per AAP &sect;0.7.1).
     *
     * @param jobRepository            Spring Batch metadata repository
     * @param transactionManager       JPA transaction manager
     * @param transactionReportService the service implementing the
     *                                 COBOL CBTRN03C report variant
     * @param auditLogService          the audit-log adapter
     */
    public TransactionReportJob(JobRepository jobRepository,
                                PlatformTransactionManager transactionManager,
                                TransactionReportService transactionReportService,
                                AuditLogService auditLogService) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.transactionReportService = transactionReportService;
        this.auditLogService = auditLogService;
    }

    /**
     * Defines the {@code transactionReportJob} Spring Batch
     * {@link Job} bean.
     *
     * <p>// Replaces: app/jcl/TRANREPT.jcl entire job stream
     * (EXEC PGM=CBTRN03C).</p>
     *
     * @param sharedAuditJobExecutionListener the shared lifecycle audit
     *                                        listener bean from
     *                                        {@link BatchJobConfig}
     * @return the configured {@link Job} bean
     */
    @Bean
    public Job transactionReportJob(JobExecutionListener sharedAuditJobExecutionListener) {
        // Replaces: app/jcl/TRANREPT.jcl EXEC PGM=CBTRN03C
        return new JobBuilder(JOB_NAME, jobRepository)
                .listener(sharedAuditJobExecutionListener)
                .start(generateTransactionReportStep())
                .build();
    }

    /**
     * Defines the orchestration tasklet step that invokes the
     * {@link TransactionReportService}.
     *
     * <p>// Replaces: app/cbl/CBTRN03C.cbl entire PROCEDURE DIVISION
     * (L159-L210).</p>
     *
     * @return the configured {@link Step} bean
     */
    @Bean
    public Step generateTransactionReportStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(generateTransactionReportTasklet(), transactionManager)
                .listener(new ReportStepExitStatusListener())
                .build();
    }

    /**
     * Defines the {@link Tasklet} that drives the
     * {@link TransactionReportService#generateReport(LocalDate, LocalDate)}
     * invocation.
     *
     * @return a {@link Tasklet} returning {@link RepeatStatus#FINISHED}
     */
    @Bean
    public Tasklet generateTransactionReportTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            // Replaces: app/cbl/CBTRN03C.cbl PROCEDURE DIVISION
            final StepExecution stepExecution =
                    chunkContext.getStepContext().getStepExecution();
            final Map<String, Object> auditFields = new LinkedHashMap<>();
            final String batchRunId =
                    stepExecution.getJobParameters().getString(PARAM_BATCH_RUN_ID);
            final String startDateStr =
                    stepExecution.getJobParameters().getString(PARAM_START_DATE);
            final String endDateStr =
                    stepExecution.getJobParameters().getString(PARAM_END_DATE);

            if (startDateStr == null || startDateStr.isBlank()) {
                throw new IllegalArgumentException(
                        "Required job parameter '" + PARAM_START_DATE
                                + "' is missing (expected ISO-8601 yyyy-MM-dd)");
            }
            if (endDateStr == null || endDateStr.isBlank()) {
                throw new IllegalArgumentException(
                        "Required job parameter '" + PARAM_END_DATE
                                + "' is missing (expected ISO-8601 yyyy-MM-dd)");
            }

            final LocalDate startDate;
            final LocalDate endDate;
            try {
                startDate = LocalDate.parse(startDateStr);
                endDate = LocalDate.parse(endDateStr);
            } catch (RuntimeException pex) {
                throw new IllegalArgumentException(
                        "One or both report date parameters are not valid "
                                + "ISO-8601 dates: startDate='" + startDateStr
                                + "', endDate='" + endDateStr + "'", pex);
            }

            LOG.info(
                    "TransactionReportJob: starting batchRunId={}, "
                            + "startDate={}, endDate={}",
                    batchRunId, startDate, endDate);

            final ReportResult result =
                    transactionReportService.generateReport(startDate, endDate);

            stepExecution.getExecutionContext()
                    .putInt(CTX_TRANSACTION_COUNT, result.transactionCount());
            stepExecution.getExecutionContext()
                    .putInt(CTX_PAGE_COUNT, result.pageCount());
            stepExecution.getExecutionContext()
                    .putString(CTX_GRAND_TOTAL,
                            result.grandTotal() != null
                                    ? result.grandTotal().toPlainString() : "0.00");
            stepExecution.getExecutionContext()
                    .putString(CTX_S3_KEY,
                            result.s3Key() != null ? result.s3Key() : "");

            contribution.incrementReadCount();
            contribution.incrementWriteCount(result.transactionCount());

            auditFields.put("batchRunId", batchRunId);
            auditFields.put("startDate", startDate.toString());
            auditFields.put("endDate", endDate.toString());
            auditFields.put("transactionCount", result.transactionCount());
            auditFields.put("pageCount", result.pageCount());
            auditFields.put("grandTotal",
                    result.grandTotal() != null
                            ? result.grandTotal().toPlainString() : "0.00");
            auditFields.put("s3Key", result.s3Key());
            auditLogService.logBatchJobLifecycle(
                    JOB_NAME,
                    String.valueOf(stepExecution.getJobExecutionId()),
                    "COMPLETED",
                    null,
                    auditFields,
                    stepExecution.getJobParameters().getString("correlationId"));

            LOG.info(
                    "TransactionReportJob: completed batchRunId={}, "
                            + "transactionCount={}, pageCount={}, "
                            + "grandTotal={}, s3Key={}",
                    batchRunId, result.transactionCount(),
                    result.pageCount(), result.grandTotal(), result.s3Key());

            return RepeatStatus.FINISHED;
        };
    }

    /**
     * {@link StepExecutionListener} for the transaction-report step.
     *
     * <p>CBTRN03C does not have a "completed with rejects" concept —
     * every in-window transaction is included in the report; reads
     * are non-destructive. Clean completion is mapped to
     * {@link ExitStatus#COMPLETED}; any thrown exception bubbles up
     * as {@link ExitStatus#FAILED}.</p>
     */
    private static final class ReportStepExitStatusListener implements StepExecutionListener {

        @Override
        public void beforeStep(StepExecution stepExecution) {
            // No-op.
        }

        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            final ExitStatus existing = stepExecution.getExitStatus();
            if (existing != null && !"COMPLETED".equals(existing.getExitCode())) {
                LOG.warn(
                        "TransactionReportJob step exitStatus={} preserved",
                        existing.getExitCode());
                return existing;
            }
            return ExitStatus.COMPLETED;
        }
    }
}
