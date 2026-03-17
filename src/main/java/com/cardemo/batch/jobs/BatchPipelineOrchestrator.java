package com.cardemo.batch.jobs;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 5-Stage Nightly Batch Pipeline Orchestrator.
 *
 * <p>Migrated from the mainframe JCL pipeline sequence:
 * <pre>
 *   POSTTRAN.jcl → INTCALC.jcl → COMBTRAN.jcl → CREASTMT.JCL / TRANREPT.jcl
 * </pre>
 *
 * <p>Pipeline stages:
 * <ul>
 *   <li>Stage 1 (POSTTRAN): Daily Transaction Posting — validates and posts daily transactions</li>
 *   <li>Stage 2 (INTCALC): Interest Calculation — computes monthly interest on category balances</li>
 *   <li>Stage 3 (COMBTRAN): Combine Transactions — sorts and merges transaction files</li>
 *   <li>Stage 4a (CREASTMT): Statement Generation — generates text + HTML statements (PARALLEL)</li>
 *   <li>Stage 4b (TRANREPT): Transaction Report — generates date-filtered reports (PARALLEL)</li>
 * </ul>
 *
 * <p>Sequential dependency: Stage N+1 starts ONLY after Stage N completes successfully.
 * Stages 4a and 4b execute in PARALLEL after Stage 3 completes — they have no data
 * dependencies on each other (both read from TRANSACT after Stage 3 loads it).
 *
 * <p>JCL COND code logic:
 * <ul>
 *   <li>POSTTRAN RETURN-CODE 0 (COMPLETED) — all records posted → downstream stages proceed</li>
 *   <li>POSTTRAN RETURN-CODE 4 (COMPLETED_WITH_REJECTS) — partial rejections → downstream stages proceed
 *       (CBTRN02C.cbl line 230: MOVE 4 TO RETURN-CODE when WS-REJECT-COUNT &gt; 0)</li>
 *   <li>POSTTRAN RETURN-CODE &gt;= 8 (FAILED) — fatal error → pipeline stops immediately</li>
 * </ul>
 */
@Configuration
public class BatchPipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BatchPipelineOrchestrator.class);

    /**
     * Decider status: pipeline should continue to the next stage.
     * Maps to JCL RETURN-CODE 0 (all records posted) or RETURN-CODE 4 (partial rejections).
     */
    private static final String DECIDER_STATUS_CONTINUE = "CONTINUE";

    /**
     * Decider status: pipeline should stop due to fatal error.
     * Maps to JCL RETURN-CODE &gt;= 8 (fatal error in POSTTRAN step).
     */
    private static final String DECIDER_STATUS_FAILED = "FAILED";

    /**
     * Defines the 5-stage nightly batch pipeline meta-job.
     *
     * <p>Wires all 5 individual batch job steps into a single orchestrated pipeline:
     * <pre>
     *   Stage 1 → Condition Code Decider → CONTINUE → Stage 2 → Stage 3 → [Stage 4a ‖ Stage 4b]
     *                                    → FAILED  → pipeline stops
     * </pre>
     *
     * @param jobRepository  Spring Batch job metadata repository
     * @param postingStep    Stage 1: Daily Transaction Posting (POSTTRAN / CBTRN02C)
     * @param interestStep   Stage 2: Interest Calculation (INTCALC / CBACT04C)
     * @param combineStep    Stage 3: Combine Transactions (COMBTRAN / DFSORT+REPRO)
     * @param statementStep  Stage 4a: Statement Generation (CREASTMT / CBSTM03A+B) — parallel
     * @param reportStep     Stage 4b: Transaction Report (TRANREPT / CBTRN03C) — parallel
     * @return the fully wired 5-stage pipeline Job
     */
    @Bean("batchPipelineJob")
    public Job batchPipelineJob(
            JobRepository jobRepository,
            @Qualifier("dailyTransactionPostingStep") Step postingStep,
            @Qualifier("interestCalculationStep") Step interestStep,
            @Qualifier("combineTransactionsStep") Step combineStep,
            @Qualifier("statementGenerationStep") Step statementStep,
            @Qualifier("transactionReportStep") Step reportStep) {

        // Stage 1 wrapped in Flow — required for JobBuilder.start(Flow) to enable
        // flow-based pipeline with deciders and parallel execution
        Flow stage1Flow = new FlowBuilder<Flow>("stage1-posttran")
                .start(postingStep)
                .build();

        // Stages 4a and 4b run in PARALLEL using FlowBuilder.split()
        // Per AAP §0.8.5: CREASTMT and TRANREPT have no data dependencies on each other.
        // Both read from TRANSACT after Stage 3 loads it — parallel execution is safe.
        Flow stage4aFlow = new FlowBuilder<Flow>("stage4a-creastmt")
                .start(statementStep)
                .build();

        Flow stage4bFlow = new FlowBuilder<Flow>("stage4b-tranrept")
                .start(reportStep)
                .build();

        Flow parallelStage4 = new FlowBuilder<Flow>("stage4-parallel")
                .split(batchPipelineTaskExecutor())
                .add(stage4aFlow, stage4bFlow)
                .build();

        // Wire the 5-stage sequential pipeline with JCL COND code logic
        //
        // Pipeline flow:
        //   Stage1(POSTTRAN) → DecisionPoint → CONTINUE → Stage2(INTCALC)
        //                                    → FAILED   → stop
        //   Stage2(INTCALC) → Stage3(COMBTRAN) → [Stage4a(CREASTMT) ‖ Stage4b(TRANREPT)]
        return new JobBuilder("batchPipelineJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(pipelineListener())
                .start(stage1Flow)
                .next(conditionCodeDecider())
                .on(DECIDER_STATUS_CONTINUE).to(interestStep)
                .from(conditionCodeDecider()).on(DECIDER_STATUS_FAILED).fail()
                .from(interestStep).next(combineStep)
                .on("COMPLETED").to(parallelStage4)
                .end()
                .build();
    }

    /**
     * JCL COND code decision logic for the batch pipeline.
     *
     * <p>Evaluates the POSTTRAN (Stage 1) step exit status to determine whether downstream
     * stages should proceed:
     * <ul>
     *   <li>RETURN-CODE = 0 (ExitStatus.COMPLETED): All daily transactions posted → CONTINUE</li>
     *   <li>RETURN-CODE = 4 (ExitStatus "COMPLETED_WITH_REJECTS"): Some rejections but processing
     *       continues — rejected transactions are excluded from the TRANSACT file, so interest
     *       calculation, transaction combining, and reporting operate on posted transactions only → CONTINUE
     *       (CBTRN02C.cbl line 230: MOVE 4 TO RETURN-CODE when WS-REJECT-COUNT &gt; 0)</li>
     *   <li>RETURN-CODE &gt;= 8 (ExitStatus.FAILED): Fatal error — stop pipeline → FAILED</li>
     * </ul>
     *
     * @return a JobExecutionDecider implementing JCL COND code semantics
     */
    @Bean
    public JobExecutionDecider conditionCodeDecider() {
        return (JobExecution jobExecution, StepExecution stepExecution) -> {
            // The decider is invoked after Stage 1 (POSTTRAN / dailyTransactionPostingStep).
            // stepExecution contains the last executed step's information.
            if (stepExecution == null) {
                log.warn("Pipeline decider: No step execution available — "
                        + "defaulting to CONTINUE for pipeline resilience");
                return new FlowExecutionStatus(DECIDER_STATUS_CONTINUE);
            }

            String exitCode = stepExecution.getExitStatus().getExitCode();

            // JCL COND code mapping:
            // Only FAILED exit status stops the pipeline (maps to RETURN-CODE >= 8).
            // COMPLETED (RC=0) and COMPLETED_WITH_REJECTS (RC=4) both allow downstream stages.
            // Any other non-FAILED status also allows continuation for robustness.
            boolean isFatalFailure = "FAILED".equals(exitCode);
            String decision = isFatalFailure ? DECIDER_STATUS_FAILED : DECIDER_STATUS_CONTINUE;

            log.info("Pipeline decider: POSTTRAN exit status={}, proceeding={}", exitCode, decision);

            if (isFatalFailure) {
                return FlowExecutionStatus.FAILED;
            }
            return new FlowExecutionStatus(DECIDER_STATUS_CONTINUE);
        };
    }

    /**
     * TaskExecutor for parallel execution of Pipeline Stages 4a and 4b.
     *
     * <p>Per AAP §0.8.5, CREASTMT (statement generation) and TRANREPT (transaction report)
     * may execute in parallel after COMBTRAN (Stage 3) completes. They have no data
     * dependencies on each other — both read from the same TRANSACT dataset loaded by Stage 3.
     *
     * <p>Thread prefix "batch-pipeline-" aids observability in structured logs, enabling
     * correlation of log entries across parallel stage threads via logstash-logback-encoder.
     *
     * @return a SimpleAsyncTaskExecutor with the "batch-pipeline-" thread prefix
     */
    @Bean
    public TaskExecutor batchPipelineTaskExecutor() {
        return new SimpleAsyncTaskExecutor("batch-pipeline-");
    }

    /**
     * Pipeline lifecycle listener for structured logging and performance metrics.
     *
     * <p>Logs pipeline start and completion events with timestamps for Gate 3
     * (Performance Baseline) evidence. Captures failure details for diagnostic purposes.
     * All log messages flow through logstash-logback-encoder for structured JSON output
     * with correlation IDs and trace context per AAP §0.7.1 observability requirements.
     *
     * @return a JobExecutionListener for pipeline lifecycle logging
     */
    @Bean
    public JobExecutionListener pipelineListener() {
        return new JobExecutionListener() {

            @Override
            public void beforeJob(JobExecution jobExecution) {
                log.info("Starting CardDemo nightly batch pipeline — 5 stages");
                Long instanceId = jobExecution.getJobInstance() != null
                        ? jobExecution.getJobInstance().getInstanceId() : null;
                log.info("Pipeline job instance ID: {}, execution ID: {}, parameters: {}",
                        instanceId,
                        jobExecution.getId(),
                        jobExecution.getJobParameters());
            }

            @Override
            public void afterJob(JobExecution jobExecution) {
                log.info("CardDemo batch pipeline complete — status={}, startTime={}, endTime={}",
                        jobExecution.getStatus(),
                        jobExecution.getStartTime(),
                        jobExecution.getEndTime());

                // Log any failure exceptions encountered during the pipeline run
                if (jobExecution.getAllFailureExceptions() != null
                        && !jobExecution.getAllFailureExceptions().isEmpty()) {
                    log.error("Pipeline encountered {} failure(s):",
                            jobExecution.getAllFailureExceptions().size());
                    for (Throwable ex : jobExecution.getAllFailureExceptions()) {
                        log.error("Pipeline failure detail: {}", ex.getMessage(), ex);
                    }
                }
            }
        };
    }
}
