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
import com.awsm2.carddemo.service.InterestCalculationService;
import com.awsm2.carddemo.service.InterestCalculationService.InterestResult;

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
 * {@code interestCalculationJob}.
 *
 * <p><b>// Replaces: app/jcl/INTCALC.jcl + app/cbl/CBACT04C.cbl</b>
 * &mdash; the COBOL end-of-day interest-calculation program executed
 * by JES2 as Stage 2 of the end-of-day batch pipeline per AAP
 * &sect;0.6.3.</p>
 *
 * <h2>Source Lineage</h2>
 *
 * <p>The original {@code INTCALC.jcl} JCL job consisted of a single
 * {@code EXEC PGM=CBACT04C} step that:</p>
 * <ol>
 *   <li>Iterated every {@code transaction_category_balance} row in
 *       the TCATBALF VSAM cluster (now JPA
 *       {@code TransactionCategoryBalanceRepository}).</li>
 *   <li>Looked up the per-account interest rate from the
 *       {@code DISCGRP} VSAM cluster (now JPA
 *       {@code DisclosureGroupRepository}) with the COBOL DEFAULT
 *       fallback rule.</li>
 *   <li>Computed monthly interest using the COBOL formula
 *       {@code (BAL * RATE) / 1200} preserved verbatim with
 *       {@code BigDecimal.HALF_EVEN} rounding (AAP &sect;0.6.1).</li>
 *   <li>Updated the corresponding {@code accounts} balance.</li>
 *   <li>Emitted a per-account {@code SYSTRAN} interest record (now
 *       written to S3 via {@code S3OutputService} per AAP
 *       &sect;0.6.2).</li>
 * </ol>
 *
 * <h2>Java/Spring Batch Replacement</h2>
 *
 * <p>The {@code interestCalculationJob} is composed of a single
 * orchestration step that invokes
 * {@link InterestCalculationService#calculateInterest(LocalDate)}.
 * The service contains the entire COBOL CBACT04C iteration plus the
 * BigDecimal interest formula plus the SYSTRAN emission to S3. The
 * tasklet wraps this single invocation as a Spring Batch step that
 * surfaces the {@link InterestResult} field values to the step's
 * {@code ExecutionContext} so Step Functions and operational tooling
 * can inspect them.</p>
 *
 * <p><b>Why a tasklet (not chunk-oriented)?</b>
 * {@link InterestCalculationService} is itself a chunked / per-row
 * loop that performs the COBOL cascade exactly: it reads each TCATBAL
 * row, looks up the matching DISCGRP, computes the BigDecimal monthly
 * interest, updates the account balance, and writes the SYSTRAN record
 * to S3. Wrapping this in a Spring Batch chunk step would impose
 * additional commit boundaries that would not match the COBOL
 * semantic (the service's {@code @Transactional(readOnly = true)}
 * envelope already handles transaction discipline correctly).</p>
 *
 * <h2>End-of-Day Pipeline Position</h2>
 *
 * <p>This job is <b>Stage 2</b> of the end-of-day batch pipeline
 * orchestrated by AWS Step Functions per AAP &sect;0.6.3:</p>
 * <pre>
 *   POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; Parallel { CREASTMT, TRANREPT }
 * </pre>
 *
 * <h2>Job Parameters</h2>
 * <ul>
 *   <li>{@code batchRunId} (REQUIRED) &mdash; unique batch run identifier.</li>
 *   <li>{@code parmDate} (REQUIRED) &mdash; the date parameter passed
 *       to {@code CBACT04C} via JCL {@code PARM='YYYYMMDDHH'} (the
 *       original COBOL parameter); the Java target accepts an
 *       ISO-8601 {@code yyyy-MM-dd} date string and ignores the
 *       hour component (which is not used by the service).</li>
 *   <li>{@code correlationId} (OPTIONAL) &mdash; for audit-trail
 *       correlation.</li>
 * </ul>
 *
 * @see InterestCalculationService
 */
@Configuration("interestCalculationJobConfiguration")
public class InterestCalculationJob {

    /**
     * SLF4J logger for the job lifecycle.
     */
    private static final Logger LOG = LoggerFactory.getLogger(InterestCalculationJob.class);

    /**
     * The Spring Batch {@link Job} bean name.
     */
    public static final String JOB_NAME = "interestCalculationJob";

    /**
     * The Spring Batch {@link Step} bean name.
     */
    public static final String STEP_NAME = "calculateInterestStep";

    /**
     * {@link org.springframework.batch.core.JobParameters} key for the
     * batch run ID.
     */
    public static final String PARAM_BATCH_RUN_ID = "batchRunId";

    /**
     * {@link org.springframework.batch.core.JobParameters} key for the
     * {@code parmDate} (CBACT04C JCL PARM).
     */
    public static final String PARAM_PARM_DATE = "parmDate";

    /**
     * Execution-context key for the per-account interest count emitted
     * by the service.
     */
    public static final String CTX_TCAT_COUNT = "tcatCount";

    /**
     * Execution-context key for the per-account update count.
     */
    public static final String CTX_ACCT_COUNT = "acctCount";

    /**
     * Execution-context key for the grand-total interest amount
     * (stringified BigDecimal to preserve precision).
     */
    public static final String CTX_GRAND_TOTAL = "grandTotal";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final InterestCalculationService interestCalculationService;
    private final AuditLogService auditLogService;

    /**
     * Constructs the job configuration with all required collaborators
     * via constructor injection (per AAP &sect;0.7.1).
     *
     * @param jobRepository              Spring Batch metadata repository
     * @param transactionManager         JPA transaction manager
     * @param interestCalculationService the service implementing the
     *                                   COBOL CBACT04C cascade
     * @param auditLogService            the audit-log adapter
     */
    public InterestCalculationJob(JobRepository jobRepository,
                                  PlatformTransactionManager transactionManager,
                                  InterestCalculationService interestCalculationService,
                                  AuditLogService auditLogService) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.interestCalculationService = interestCalculationService;
        this.auditLogService = auditLogService;
    }

    /**
     * Defines the {@code interestCalculationJob} Spring Batch
     * {@link Job} bean.
     *
     * <p>// Replaces: app/jcl/INTCALC.jcl entire job stream
     * (EXEC PGM=CBACT04C with PARM='YYYYMMDDHH').</p>
     *
     * @param sharedAuditJobExecutionListener the shared lifecycle audit
     *                                        listener bean from
     *                                        {@link BatchJobConfig}
     * @return the configured {@link Job} bean &mdash; registered in the
     *         {@code ApplicationContext} under the name
     *         {@value #JOB_NAME}
     */
    @Bean
    public Job interestCalculationJob(JobExecutionListener sharedAuditJobExecutionListener) {
        // Replaces: app/jcl/INTCALC.jcl EXEC PGM=CBACT04C
        return new JobBuilder(JOB_NAME, jobRepository)
                .listener(sharedAuditJobExecutionListener)
                .start(calculateInterestStep())
                .build();
    }

    /**
     * Defines the orchestration tasklet step that invokes the
     * {@link InterestCalculationService}.
     *
     * <p>// Replaces: app/cbl/CBACT04C.cbl PROCEDURE DIVISION</p>
     *
     * @return the configured {@link Step} bean
     */
    @Bean
    public Step calculateInterestStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(calculateInterestTasklet(), transactionManager)
                .listener(new InterestStepExitStatusListener())
                .build();
    }

    /**
     * Defines the {@link Tasklet} that drives the
     * {@link InterestCalculationService#calculateInterest(LocalDate)}
     * invocation.
     *
     * @return a {@link Tasklet} returning {@link RepeatStatus#FINISHED}
     *         after a single service invocation
     */
    @Bean
    public Tasklet calculateInterestTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            // Replaces: app/cbl/CBACT04C.cbl PROCEDURE DIVISION
            final StepExecution stepExecution =
                    chunkContext.getStepContext().getStepExecution();
            final Map<String, Object> auditFields = new LinkedHashMap<>();
            final String batchRunId =
                    stepExecution.getJobParameters().getString(PARAM_BATCH_RUN_ID);
            final String parmDateStr =
                    stepExecution.getJobParameters().getString(PARAM_PARM_DATE);

            if (parmDateStr == null || parmDateStr.isBlank()) {
                throw new IllegalArgumentException(
                        "Required job parameter '" + PARAM_PARM_DATE
                                + "' is missing (expected ISO-8601 yyyy-MM-dd)");
            }

            final LocalDate parmDate;
            try {
                parmDate = LocalDate.parse(parmDateStr);
            } catch (RuntimeException pex) {
                throw new IllegalArgumentException(
                        "Required job parameter '" + PARAM_PARM_DATE
                                + "' is not a valid ISO-8601 date: '"
                                + parmDateStr + "'", pex);
            }

            LOG.info(
                    "InterestCalculationJob: starting batchRunId={}, parmDate={}",
                    batchRunId, parmDate);

            final InterestResult result =
                    interestCalculationService.calculateInterest(parmDate);

            // Publish to execution context
            stepExecution.getExecutionContext()
                    .putInt(CTX_TCAT_COUNT, result.tcatCount());
            stepExecution.getExecutionContext()
                    .putInt(CTX_ACCT_COUNT, result.acctCount());
            stepExecution.getExecutionContext()
                    .putString(CTX_GRAND_TOTAL,
                            result.grandTotal() != null
                                    ? result.grandTotal().toPlainString() : "0.00");

            contribution.incrementReadCount();
            contribution.incrementWriteCount(result.acctCount());

            auditFields.put("batchRunId", batchRunId);
            auditFields.put("parmDate", parmDate.toString());
            auditFields.put("tcatCount", result.tcatCount());
            auditFields.put("acctCount", result.acctCount());
            auditFields.put("grandTotal",
                    result.grandTotal() != null
                            ? result.grandTotal().toPlainString() : "0.00");
            auditLogService.logBatchJobLifecycle(
                    JOB_NAME,
                    String.valueOf(stepExecution.getJobExecutionId()),
                    "COMPLETED",
                    null,
                    auditFields,
                    stepExecution.getJobParameters().getString("correlationId"));

            LOG.info(
                    "InterestCalculationJob: completed batchRunId={}, "
                            + "tcatCount={}, acctCount={}, grandTotal={}",
                    batchRunId, result.tcatCount(), result.acctCount(),
                    result.grandTotal());

            return RepeatStatus.FINISHED;
        };
    }

    /**
     * {@link StepExecutionListener} for the interest-calculation step.
     *
     * <p>Maps any thrown exception to {@link ExitStatus#FAILED}
     * (COBOL RETURN-CODE = 8 per CBACT04C semantics); on clean
     * completion the default {@link ExitStatus#COMPLETED} is preserved
     * because CBACT04C does not have a "completed with rejects"
     * concept (every TCATBAL row is processed; mis-rated rows fall
     * back to the DEFAULT disclosure group).</p>
     */
    private static final class InterestStepExitStatusListener implements StepExecutionListener {

        @Override
        public void beforeStep(StepExecution stepExecution) {
            // No-op.
        }

        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            final ExitStatus existing = stepExecution.getExitStatus();
            // Preserve failure status; CBACT04C has no rejects concept
            // so clean completions remain COMPLETED.
            if (existing != null && !"COMPLETED".equals(existing.getExitCode())) {
                LOG.warn(
                        "InterestCalculationJob step exitStatus={} preserved",
                        existing.getExitCode());
                return existing;
            }
            return ExitStatus.COMPLETED;
        }
    }
}
