package com.cardemo.batch.jobs;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Spring Batch {@code @Configuration} that assembles the <strong>master 5-stage batch pipeline</strong>
 * of the greenfield Java&nbsp;25 LTS + Spring&nbsp;Boot&nbsp;3.5.x CardDemo migration. It is the faithful
 * translation of the legacy JES job-scheduling sequence realized by the five JCL job streams
 * {@code app/jcl/POSTTRAN.jcl}, {@code app/jcl/INTCALC.jcl}, {@code app/jcl/COMBTRAN.jcl},
 * {@code app/jcl/CREASTMT.JCL} and {@code app/jcl/TRANREPT.jcl}, composed into a single executable
 * Spring Batch {@link Job}:
 *
 * <pre>
 *   POSTTRAN  &rarr;  INTCALC  &rarr;  COMBTRAN  &rarr;  ( CREASTMT  &#x2016;  TRANREPT )
 *   (Stage 1)     (Stage 2)     (Stage 3)        (Stage 4a)    (Stage 4b)
 * </pre>
 *
 * <h2>Provenance &amp; governance (AAP &sect;0.7.1 / &sect;0.7.2)</h2>
 * <p>Translated from the frozen AWS CardDemo COBOL/JCL baseline at commit SHA {@code 27d6c6f}. The
 * JCL sources are <strong>read-only</strong> reference material and are <strong>never copied</strong>
 * into this repository; traceability is by commit SHA only. Per the <strong>Minimal Change Clause</strong>
 * this class reproduces the legacy <em>scheduling and condition-code</em> behaviour <em>exactly</em> and
 * documents every technology substitution at its point of change. The application base package is
 * {@code com.cardemo} (decision <strong>D-006</strong>, deliberately <em>not</em> {@code com.carddemo}).</p>
 *
 * <h2>Responsibility &mdash; PURE ORCHESTRATION (no business logic)</h2>
 * <p>This class adds <strong>no</strong> business logic and re-creates <strong>no</strong>
 * reader/processor/writer. Its single responsibility is to reproduce the legacy JES job sequence and
 * its condition-code gating using Spring Batch flow constructs. It <em>reuses verbatim</em> the
 * {@link Step} beans already published by the five stage {@code @Configuration} classes in this package
 * &mdash; injected by name as method parameters &mdash; and merely wires them together:</p>
 * <ul>
 *   <li>{@code dailyTransactionPostingStep} &mdash; Stage&nbsp;1, from {@link DailyTransactionPostingJob}
 *       ({@code POSTTRAN.jcl} / {@code CBTRN02C}).</li>
 *   <li>{@code interestCalculationStep} &mdash; Stage&nbsp;2, from {@link InterestCalculationJob}
 *       ({@code INTCALC.jcl} / {@code CBACT04C}).</li>
 *   <li>{@code combineTransactionsStep} &mdash; Stage&nbsp;3, from {@link CombineTransactionsJob}
 *       ({@code COMBTRAN.jcl} / DFSORT + IDCAMS&nbsp;{@code REPRO}).</li>
 *   <li>{@code prepareStatementsStep} + {@code generateStatementsStep} &mdash; Stage&nbsp;4a, from
 *       {@link StatementGenerationJob} ({@code CREASTMT.JCL} / {@code CBSTM03A}).</li>
 *   <li>{@code transactionReportStep} &mdash; Stage&nbsp;4b, from {@link TransactionReportJob}
 *       ({@code TRANREPT.jcl} / {@code CBTRN03C}).</li>
 * </ul>
 *
 * <h2>JCL &rarr; Spring Batch substitution table (AAP &sect;0.7.6, documented per Minimal Change Clause)</h2>
 * <dl>
 *   <dt>Five independent JES jobs scheduled in sequence &rarr; one composed Spring Batch {@link Job}</dt>
 *   <dd>The legacy operator/scheduler submitted {@code POSTTRAN}, then {@code INTCALC}, then
 *       {@code COMBTRAN}, then {@code CREASTMT}/{@code TRANREPT}. Here the five stages become one
 *       {@code Job} ({@link #cardDemoBatchPipelineJob}) built from the reused {@code Step} beans, so the
 *       cross-job ordering is preserved <em>inside</em> a single restartable job instance.</dd>
 *   <dt>JES condition codes ({@code COND}) &rarr; {@link ExitStatus} + {@link JobExecutionDecider} +
 *       sequential {@code .next(...)}</dt>
 *   <dd>Within the job, a sequential {@code .next(stepB)} already reproduces "run {@code stepB} only if
 *       {@code stepA} {@code COMPLETED}" (a non-{@code COMPLETED} status aborts the flow), which is the
 *       common-case {@code COND} semantics. The single conditional checkpoint that {@code .next(...)}
 *       cannot express &mdash; {@code POSTTRAN}'s {@code RETURN-CODE=4} &mdash; is realized by the
 *       explicit {@link #postingDecider()} (see below).</dd>
 *   <dt>{@code CREASTMT}'s {@code COND=(0,NE)} inter-step gating &rarr; {@code prepareStatementsStep
 *       .next(generateStatementsStep)}</dt>
 *   <dd>In {@code CREASTMT.JCL} the {@code CBSTM03A} statement step ({@code STEP040}) carries
 *       {@code COND=(0,NE)} so it runs only if every prior step returned {@code RC=0}. The two-step
 *       Stage&nbsp;4a flow {@code prepare &rarr; generate} reproduces that gating: {@code generate} runs
 *       only when {@code prepare} {@code COMPLETED}.</dd>
 *   <dt>Independent {@code CREASTMT} and {@code TRANREPT} jobs (both depend only on {@code COMBTRAN})
 *       &rarr; {@link FlowBuilder#split(TaskExecutor)}</dt>
 *   <dd>Because Stage&nbsp;4a and Stage&nbsp;4b share a single upstream dependency ({@code COMBTRAN}) and
 *       neither reads the other's output, they may run concurrently. They are composed as two sibling
 *       {@link Flow}s executed in parallel by {@code split(}{@link #pipelineTaskExecutor()}{@code )}.</dd>
 *   <dt>{@code POSTTRAN} {@code RETURN-CODE=4} ("success-with-rejects") &rarr; decider PROCEED branch</dt>
 *   <dd>{@code CBTRN02C} emits {@code MOVE 4 TO RETURN-CODE} when it posts with partial rejects &mdash;
 *       <em>not</em> a failure. {@link DailyTransactionPostingJob} surfaces this as the custom
 *       {@link ExitStatus} code {@link DailyTransactionPostingJob#EXIT_CODE_COMPLETED_WITH_REJECTS}. The
 *       {@link #postingDecider()} treats both {@code COMPLETED} (RC=0) and {@code COMPLETED_WITH_REJECTS}
 *       (RC=4) as {@link #PROCEED}, so partial rejects do not halt the downstream pipeline.</dd>
 * </dl>
 *
 * <h2>Sequential-chain guarantee (AAP &sect;0.7.6)</h2>
 * <p>The chain {@code POSTTRAN &rarr; INTCALC &rarr; COMBTRAN} is strictly sequential: each stage starts
 * only after its predecessor completes successfully. Only the terminal Stage&nbsp;4 (statement&nbsp;&#x2016;
 * report) is parallelized, and only because both its members depend solely on {@code COMBTRAN}.</p>
 *
 * <h2>Spring Batch 5.x infrastructure (AAP &sect;0.7.8)</h2>
 * <p>Built with the Spring&nbsp;Batch&nbsp;5.x builder API ({@link JobBuilder} / {@link FlowBuilder} with
 * an explicit {@link JobRepository}). The deprecated {@code JobBuilderFactory}/{@code StepBuilderFactory}
 * are <strong>not</strong> used, and {@code @EnableBatchProcessing} is <strong>not</strong> declared here
 * (it is intentionally absent from the whole application &mdash; in Spring&nbsp;Boot&nbsp;3.x it would
 * disable Boot's batch auto-configuration; the {@link JobRepository} is the auto-configured JDBC bean
 * documented by {@code com.cardemo.config.BatchConfig}). The parallel-split {@link TaskExecutor} is owned
 * here, next to its sole consumer, exactly as {@code BatchConfig} prescribes.</p>
 *
 * @see DailyTransactionPostingJob
 * @see InterestCalculationJob
 * @see CombineTransactionsJob
 * @see StatementGenerationJob
 * @see TransactionReportJob
 * @see com.cardemo.config.BatchConfig
 * @see Configuration
 */
@Configuration
public class BatchPipelineOrchestrator {

    /**
     * Flow-transition name signalling that the {@code POSTTRAN} stage finished acceptably (legacy
     * {@code RC=0} <em>or</em> {@code RC=4}) and the pipeline should continue to {@code INTCALC}. Declared
     * as a constant to avoid magic strings and to keep the decider and the job-flow transitions in lockstep
     * (a typo between the two would silently route the flow to an unreachable branch).
     */
    private static final String PROCEED = "PROCEED";

    /**
     * Flow-transition name signalling that the {@code POSTTRAN} stage failed (any exit code other than
     * {@code COMPLETED} / {@code COMPLETED_WITH_REJECTS}) and the pipeline must halt before {@code INTCALC},
     * exactly as a JES {@code COND} abend would stop the job stream.
     */
    private static final String STOP = "STOP";

    /**
     * Core/maximum pool size of the {@link #pipelineTaskExecutor()} &mdash; exactly two, one worker thread
     * for each of the two parallel Stage&nbsp;4 sibling flows ({@code CREASTMT} &#x2016; {@code TRANREPT}).
     * A fixed bound of two threads is sufficient and intentional: there are never more than two concurrent
     * flows in the split, and an unbounded executor is undesirable for a batch worker pool.
     */
    private static final int STAGE4_PARALLELISM = 2;

    /**
     * Number of seconds Spring waits for in-flight Stage&nbsp;4 flows to finish when the
     * {@link #pipelineTaskExecutor()} is shut down, before forcing termination. Bounds container/JVM
     * shutdown while still allowing an in-progress statement or report flow to complete cleanly.
     */
    private static final int EXECUTOR_AWAIT_TERMINATION_SECONDS = 60;

    /**
     * Bounded {@link TaskExecutor} that powers the Stage&nbsp;4 {@link FlowBuilder#split(TaskExecutor)} so
     * statement generation (4a) and the transaction report (4b) execute <strong>genuinely in parallel</strong>
     * after {@code COMBTRAN}.
     *
     * <p><strong>Why this executor lives here and not in {@code BatchConfig}.</strong> The split flow and
     * its executor are an <em>orchestration</em> concern owned by the {@code batch/jobs} layer;
     * {@code com.cardemo.config.BatchConfig} deliberately declares no executor and explicitly delegates its
     * definition to this orchestrator. Defining it adjacent to its only consumer also keeps the
     * pool-sizing decision next to the workload it sizes for.</p>
     *
     * <p><strong>Bounded, not synchronous.</strong> A {@link ThreadPoolTaskExecutor} bounded to
     * {@value #STAGE4_PARALLELISM} threads is used precisely so the two sibling flows run concurrently; a
     * {@code SyncTaskExecutor} would run them on the caller thread and serialize them, defeating the
     * parallel-stage requirement (AAP &sect;0.7.6). The pool never grows beyond the two parallel flows.</p>
     *
     * <p><strong>Lifecycle.</strong> {@link ThreadPoolTaskExecutor} is an {@code InitializingBean} and
     * {@code DisposableBean}; as a {@code @Bean} Spring starts the pool via {@code afterPropertiesSet()}
     * and shuts it down gracefully via {@code destroy()}. {@code initialize()} is therefore intentionally
     * <em>not</em> invoked here (a manual call would orphan a second, unused pool).</p>
     *
     * @return a bounded, two-thread executor used only by the Stage&nbsp;4 parallel split
     */
    @Bean
    public TaskExecutor pipelineTaskExecutor() {
        final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(STAGE4_PARALLELISM);   // one thread per Stage-4 sibling flow
        executor.setMaxPoolSize(STAGE4_PARALLELISM);    // bounded: never more than the two parallel flows
        executor.setQueueCapacity(0);                   // no backlog: both flows must start immediately
        executor.setThreadNamePrefix("carddemo-pipeline-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(EXECUTOR_AWAIT_TERMINATION_SECONDS);
        // No manual initialize(): Spring invokes afterPropertiesSet() (InitializingBean) to start the pool
        // and destroy() (DisposableBean) to stop it. Calling initialize() here would create a second pool.
        return executor;
    }

    /**
     * The JCL condition-code checkpoint that follows {@code POSTTRAN}'s {@code //STEP15 EXEC PGM=CBTRN02C}.
     *
     * <p>This decider is the Spring Batch realization of the legacy {@code RETURN-CODE} gate between the
     * posting job and the rest of the pipeline. It inspects the exit code of the immediately-preceding
     * {@code dailyTransactionPostingStep} and decides whether the downstream stages run:</p>
     * <ul>
     *   <li>{@link ExitStatus#COMPLETED} (legacy {@code RC=0}, clean post) &rarr; {@link #PROCEED}.</li>
     *   <li>{@link DailyTransactionPostingJob#EXIT_CODE_COMPLETED_WITH_REJECTS} (legacy {@code RC=4},
     *       success-with-rejects per {@code CBTRN02C}'s {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO
     *       RETURN-CODE}) &rarr; {@link #PROCEED}. Partial rejects are <em>not</em> a failure, so the
     *       pipeline still advances to {@code INTCALC} &mdash; exactly as the legacy schedule did for
     *       {@code RC=4}.</li>
     *   <li>any other exit code (a genuine failure) &rarr; {@link #STOP}, halting the pipeline before
     *       {@code INTCALC}, exactly as a JES {@code COND} abend would.</li>
     * </ul>
     *
     * <p>The reject tally that drove a {@code RC=4} outcome is published by
     * {@link DailyTransactionPostingJob} under
     * {@link DailyTransactionPostingJob#REJECT_COUNT_KEY} for operational visibility; this decider keys
     * its decision purely off the exit code, mirroring the JES condition-code check.</p>
     *
     * @return the posting condition-code decider (a functional {@link JobExecutionDecider})
     */
    @Bean
    public JobExecutionDecider postingDecider() {
        // jobExecution is unused: the RC=0/RC=4 decision depends only on the preceding step's exit code,
        // mirroring a JES COND check against the prior step's RETURN-CODE.
        return (jobExecution, stepExecution) -> {
            final String exitCode = (stepExecution == null || stepExecution.getExitStatus() == null)
                    ? null
                    : stepExecution.getExitStatus().getExitCode();
            // RC=0 (clean) or RC=4 (success-with-rejects) both continue the pipeline.
            if (ExitStatus.COMPLETED.getExitCode().equals(exitCode)
                    || DailyTransactionPostingJob.EXIT_CODE_COMPLETED_WITH_REJECTS.equals(exitCode)) {
                return new FlowExecutionStatus(PROCEED);
            }
            // Any real failure halts the pipeline before Stage 2 (INTCALC), as a JES COND abend would.
            return new FlowExecutionStatus(STOP);
        };
    }

    /**
     * Assembles the master {@code cardDemoBatchPipelineJob} that reproduces the legacy
     * {@code POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; (CREASTMT &#x2016; TRANREPT)} schedule as one
     * Spring Batch {@link Job}.
     *
     * <p>The reused {@link Step} beans are injected by name (Spring resolves a {@code @Bean} method
     * parameter by type and, when several beans share the type as the six pipeline {@code Step}s do, by
     * the parameter name &mdash; parameter names are retained because the Spring&nbsp;Boot parent enables
     * the {@code -parameters} compiler flag). This orchestrator therefore composes the existing steps
     * <em>without</em> redefining any reader, processor or writer.</p>
     *
     * <p><strong>Flow shape.</strong> Stage&nbsp;4a is the gated two-step flow {@code prepare &rarr;
     * generate} (preserving {@code CREASTMT}'s {@code COND=(0,NE)}); Stage&nbsp;4b is the single
     * {@code transactionReportStep}; the two are run concurrently by a {@link FlowBuilder#split(TaskExecutor)}
     * on {@link #pipelineTaskExecutor()}. The pre-split chain
     * {@code POSTTRAN &rarr; (decider) &rarr; INTCALC &rarr; COMBTRAN} is strictly sequential; only the
     * post-{@code COMBTRAN} Stage&nbsp;4 runs in parallel.</p>
     *
     * @param jobRepository              the auto-configured Spring Batch JDBC job repository
     * @param postingDecider             the {@code POSTTRAN} RC=0/RC=4 condition-code decider
     * @param dailyTransactionPostingStep Stage&nbsp;1 step ({@code POSTTRAN} / {@code CBTRN02C})
     * @param interestCalculationStep    Stage&nbsp;2 step ({@code INTCALC} / {@code CBACT04C})
     * @param combineTransactionsStep    Stage&nbsp;3 step ({@code COMBTRAN} / DFSORT + {@code REPRO})
     * @param prepareStatementsStep      Stage&nbsp;4a step&nbsp;1 ({@code CREASTMT} sort/REPRO into TRXFL)
     * @param generateStatementsStep     Stage&nbsp;4a step&nbsp;2 ({@code CREASTMT} / {@code CBSTM03A})
     * @param transactionReportStep      Stage&nbsp;4b step ({@code TRANREPT} / {@code CBTRN03C})
     * @param pipelineTaskExecutor       the bounded executor powering the Stage&nbsp;4 parallel split
     * @return the fully-composed master batch pipeline job named {@code cardDemoBatchPipelineJob}
     */
    @Bean
    public Job cardDemoBatchPipelineJob(
            final JobRepository jobRepository,
            final JobExecutionDecider postingDecider,
            final Step dailyTransactionPostingStep,
            final Step interestCalculationStep,
            final Step combineTransactionsStep,
            final Step prepareStatementsStep,
            final Step generateStatementsStep,
            final Step transactionReportStep,
            final TaskExecutor pipelineTaskExecutor) {

        // --- Stage 4a (CREASTMT / CBSTM03A): prepare -> generate, preserving COND=(0,NE) gating. ---
        // generateStatementsStep runs only if prepareStatementsStep COMPLETED, exactly as CREASTMT's
        // CBSTM03A step (STEP040 COND=(0,NE)) ran only when the prior sort/REPRO steps returned RC=0.
        final Flow statementFlow = new FlowBuilder<Flow>("statementFlow")
                .start(prepareStatementsStep)
                .next(generateStatementsStep)
                .build();

        // --- Stage 4b (TRANREPT / CBTRN03C): a single report step. ---
        final Flow reportFlow = new FlowBuilder<Flow>("reportFlow")
                .start(transactionReportStep)
                .build();

        // --- Stage 4 parallel split: 4a || 4b run concurrently after COMBTRAN (both depend only on it). ---
        final Flow parallelStage4 = new FlowBuilder<Flow>("parallelStage4")
                .split(pipelineTaskExecutor)
                .add(statementFlow, reportFlow)
                .build();

        // --- Master pipeline assembly: strictly sequential chain, gated by the POSTTRAN decider. ---
        return new JobBuilder("cardDemoBatchPipelineJob", jobRepository)
                .start(dailyTransactionPostingStep)        // Stage 1: POSTTRAN  (CBTRN02C)
                .next(postingDecider)                      // JCL COND checkpoint after STEP15 (RC=4 -> PROCEED)
                .on(PROCEED).to(interestCalculationStep)   // Stage 2: INTCALC   (CBACT04C) -- only on PROCEED
                .next(combineTransactionsStep)             // Stage 3: COMBTRAN  (DFSORT + IDCAMS REPRO)
                .next(parallelStage4)                      // Stage 4a || 4b: CREASTMT (CBSTM03A) + TRANREPT (CBTRN03C)
                .from(postingDecider).on(STOP).fail()      // posting failure (RC != 0 and != 4) halts the pipeline
                .end()
                .build();
    }
}
