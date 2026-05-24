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
 * {@code statementGenerationJob}.
 *
 * <p><b>// Replaces: app/jcl/CREASTMT.JCL + app/cbl/CBSTM03A.CBL +
 * app/cbl/CBSTM03B.CBL</b> &mdash; the COBOL end-of-day statement-
 * generation programs executed by JES2 as Stage 4a of the end-of-day
 * batch pipeline per AAP &sect;0.6.3.</p>
 *
 * <h2>Source Lineage</h2>
 *
 * <p>The original {@code CREASTMT.JCL} JCL job consisted of an
 * {@code EXEC PGM=CBSTM03A} step that emitted both text and HTML
 * statements via the dual-format template pattern of
 * {@code CBSTM03B.CBL} (per AAP &sect;0.4.1). Each iteration:</p>
 * <ol>
 *   <li>Read each customer and joined to their {@code accounts} +
 *       {@code cards} + {@code card_xref} + {@code transactions}
 *       rows.</li>
 *   <li>Rendered a text statement (PIC X(80) fixed-width COBOL
 *       output) AND an HTML statement (CBSTM03B Template Method).</li>
 *   <li>Wrote each statement file to the {@code STMTFILE} GDG
 *       (now replaced by S3 versioned objects per AAP &sect;0.6.2).</li>
 * </ol>
 *
 * <h2>Java/Spring Batch Replacement</h2>
 *
 * <p>The {@code statementGenerationJob} wraps
 * {@link StatementGenerationService#generateStatements(LocalDate)} in
 * a single Spring Batch tasklet. The service performs the customer
 * iteration, the per-customer text+HTML rendering, and the S3 upload
 * via {@code S3OutputService}.</p>
 *
 * <p><b>Why a tasklet (not chunk-oriented)?</b>
 * {@link StatementGenerationService} is a {@code @Transactional(readOnly = true)}
 * iterator over the customer table that renders the dual-format output
 * per customer. Wrapping it in a Spring Batch chunk would impose
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
 * downstream of COMBTRAN.</p>
 *
 * <h2>Job Parameters</h2>
 * <ul>
 *   <li>{@code batchRunId} (REQUIRED) &mdash; unique batch run identifier.</li>
 *   <li>{@code statementMonth} (REQUIRED) &mdash; the statement period
 *       end date (ISO-8601 {@code yyyy-MM-dd}); maps to the COBOL
 *       {@code WS-STMT-DATE} working-storage field.</li>
 *   <li>{@code correlationId} (OPTIONAL).</li>
 * </ul>
 *
 * @see StatementGenerationService
 */
@Configuration("statementGenerationJobConfiguration")
public class StatementGenerationJob {

    /**
     * SLF4J logger for the job lifecycle.
     */
    private static final Logger LOG = LoggerFactory.getLogger(StatementGenerationJob.class);

    /**
     * The Spring Batch {@link Job} bean name.
     */
    public static final String JOB_NAME = "statementGenerationJob";

    /**
     * The Spring Batch {@link Step} bean name.
     */
    public static final String STEP_NAME = "generateStatementsStep";

    /**
     * {@link org.springframework.batch.core.JobParameters} key for the
     * batch run ID.
     */
    public static final String PARAM_BATCH_RUN_ID = "batchRunId";

    /**
     * {@link org.springframework.batch.core.JobParameters} key for the
     * statement period end date.
     */
    public static final String PARAM_STATEMENT_MONTH = "statementMonth";

    /**
     * Execution-context key for the number of text statements rendered.
     */
    public static final String CTX_TEXT_COUNT = "textCount";

    /**
     * Execution-context key for the number of HTML statements rendered.
     */
    public static final String CTX_HTML_COUNT = "htmlCount";

    /**
     * Execution-context key for the count of customers whose statement
     * generation failed.
     */
    public static final String CTX_ERROR_COUNT = "errorCount";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final StatementGenerationService statementGenerationService;
    private final AuditLogService auditLogService;

    /**
     * Constructs the job configuration with required collaborators
     * via constructor injection (per AAP &sect;0.7.1).
     *
     * @param jobRepository              Spring Batch metadata repository
     * @param transactionManager         JPA transaction manager
     * @param statementGenerationService the service implementing the
     *                                   COBOL CBSTM03A/B Template
     *                                   Method
     * @param auditLogService            the audit-log adapter
     */
    public StatementGenerationJob(JobRepository jobRepository,
                                  PlatformTransactionManager transactionManager,
                                  StatementGenerationService statementGenerationService,
                                  AuditLogService auditLogService) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.statementGenerationService = statementGenerationService;
        this.auditLogService = auditLogService;
    }

    /**
     * Defines the {@code statementGenerationJob} Spring Batch
     * {@link Job} bean.
     *
     * <p>// Replaces: app/jcl/CREASTMT.JCL entire job stream
     * (EXEC PGM=CBSTM03A).</p>
     *
     * @param sharedAuditJobExecutionListener the shared lifecycle audit
     *                                        listener bean from
     *                                        {@link BatchJobConfig}
     * @return the configured {@link Job} bean
     */
    @Bean
    public Job statementGenerationJob(JobExecutionListener sharedAuditJobExecutionListener) {
        // Replaces: app/jcl/CREASTMT.JCL EXEC PGM=CBSTM03A
        return new JobBuilder(JOB_NAME, jobRepository)
                .listener(sharedAuditJobExecutionListener)
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
     * @return a {@link Tasklet} returning {@link RepeatStatus#FINISHED}
     */
    @Bean
    public Tasklet generateStatementsTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            // Replaces: app/cbl/CBSTM03A.CBL + app/cbl/CBSTM03B.CBL
            final StepExecution stepExecution =
                    chunkContext.getStepContext().getStepExecution();
            final Map<String, Object> auditFields = new LinkedHashMap<>();
            final String batchRunId =
                    stepExecution.getJobParameters().getString(PARAM_BATCH_RUN_ID);
            final String statementMonthStr =
                    stepExecution.getJobParameters().getString(PARAM_STATEMENT_MONTH);

            if (statementMonthStr == null || statementMonthStr.isBlank()) {
                throw new IllegalArgumentException(
                        "Required job parameter '" + PARAM_STATEMENT_MONTH
                                + "' is missing (expected ISO-8601 yyyy-MM-dd)");
            }

            final LocalDate statementMonth;
            try {
                statementMonth = LocalDate.parse(statementMonthStr);
            } catch (RuntimeException pex) {
                throw new IllegalArgumentException(
                        "Required job parameter '" + PARAM_STATEMENT_MONTH
                                + "' is not a valid ISO-8601 date: '"
                                + statementMonthStr + "'", pex);
            }

            LOG.info(
                    "StatementGenerationJob: starting batchRunId={}, statementMonth={}",
                    batchRunId, statementMonth);

            final StatementResult result =
                    statementGenerationService.generateStatements(statementMonth);

            stepExecution.getExecutionContext()
                    .putInt(CTX_TEXT_COUNT, result.textCount());
            stepExecution.getExecutionContext()
                    .putInt(CTX_HTML_COUNT, result.htmlCount());
            stepExecution.getExecutionContext()
                    .putInt(CTX_ERROR_COUNT, result.errorCount());

            contribution.incrementReadCount();
            contribution.incrementWriteCount(result.textCount() + result.htmlCount());

            auditFields.put("batchRunId", batchRunId);
            auditFields.put("statementMonth", statementMonth.toString());
            auditFields.put("textCount", result.textCount());
            auditFields.put("htmlCount", result.htmlCount());
            auditFields.put("errorCount", result.errorCount());
            auditLogService.logBatchJobLifecycle(
                    JOB_NAME,
                    String.valueOf(stepExecution.getJobExecutionId()),
                    "COMPLETED",
                    null,
                    auditFields,
                    stepExecution.getJobParameters().getString("correlationId"));

            LOG.info(
                    "StatementGenerationJob: completed batchRunId={}, "
                            + "textCount={}, htmlCount={}, errorCount={}",
                    batchRunId, result.textCount(),
                    result.htmlCount(), result.errorCount());

            return RepeatStatus.FINISHED;
        };
    }

    /**
     * {@link StepExecutionListener} for the statement-generation step.
     *
     * <p>Maps a non-zero {@link StatementResult#errorCount()} to
     * {@link CardDemoExitStatus#COMPLETED_WITH_REJECTS} (COBOL
     * RETURN-CODE = 4) so downstream Step Functions Choice states
     * can branch on partial-success runs. Zero errors map to
     * {@link ExitStatus#COMPLETED}.</p>
     */
    private static final class StatementStepExitStatusListener implements StepExecutionListener {

        @Override
        public void beforeStep(StepExecution stepExecution) {
            // No-op.
        }

        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            final ExitStatus existing = stepExecution.getExitStatus();
            if (existing != null && !"COMPLETED".equals(existing.getExitCode())) {
                LOG.warn(
                        "StatementGenerationJob step exitStatus={} preserved",
                        existing.getExitCode());
                return existing;
            }
            if (!stepExecution.getExecutionContext().containsKey(CTX_ERROR_COUNT)) {
                return ExitStatus.COMPLETED;
            }
            final int errorCount =
                    stepExecution.getExecutionContext().getInt(CTX_ERROR_COUNT);
            if (errorCount > 0) {
                LOG.warn(
                        "StatementGenerationJob completed with {} customer-level errors "
                                + "(mapping to COMPLETED_WITH_REJECTS, RETURN-CODE = 4)",
                        errorCount);
                return CardDemoExitStatus.COMPLETED_WITH_REJECTS;
            }
            return ExitStatus.COMPLETED;
        }
    }
}
