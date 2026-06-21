package com.carddemo.batch.jobs;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.job.DefaultJobParametersExtractor;
import org.springframework.batch.core.step.job.JobParametersExtractor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;

/**
 * Spring Batch configuration that re-hosts the five-stage AWS CardDemo mainframe batch job stream as
 * one master {@link Job}. It composes the five child batch jobs (each the Java re-host of one COBOL
 * batch program / JCL job) into the predecessor-success pipeline the original job stream implies
 * (lineage: source commit {@code 27d6c6f}; REFERENCE ONLY &mdash; the COBOL/JCL is never copied).
 *
 * <h2>Pipeline ordering (the JCL job stream, AAP &sect;0.8.5)</h2>
 * <pre>
 *   POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; { CREASTMT (4a) &#8214; TRANREPT (4b) }
 * </pre>
 * <ul>
 *   <li>Stage&nbsp;1 {@code POSTTRAN} &rarr; {@link DailyTransactionPostingJob} (daily transaction posting).</li>
 *   <li>Stage&nbsp;2 {@code INTCALC} &rarr; {@link InterestCalculationJob} (interest calculation).</li>
 *   <li>Stage&nbsp;3 {@code COMBTRAN} &rarr; {@link CombineTransactionsJob} (sort + master rebuild).</li>
 *   <li>Stage&nbsp;4a {@code CREASTMT} &rarr; {@link StatementGenerationJob} (statement generation).</li>
 *   <li>Stage&nbsp;4b {@code TRANREPT} &rarr; {@link TransactionReportJob} (transaction report).</li>
 * </ul>
 *
 * <h2>Mapping summary (rationale &rarr; {@code DECISION_LOG.md}, not code comments)</h2>
 * <p>Each child job is wrapped in a Spring Batch {@code JobStep} so it executes inside the master
 * flow; a {@link JobParametersExtractor} forwards the master {@link JobParameters} (notably
 * {@code parmDate} for INTCALC and {@code startDate}/{@code endDate} for TRANREPT) down to every
 * child. The JCL {@code COND} condition-code logic is expressed by a {@link JobExecutionDecider}
 * after POSTTRAN: a partial-failure ({@code RC=4}, the {@value DailyTransactionPostingJob#COMPLETED_WITH_REJECTS}
 * exit status) is a warning that still permits the downstream stages, while a genuine
 * {@code FAILED} POSTTRAN stops the pipeline. Stages&nbsp;4a and&nbsp;4b are run concurrently via
 * {@code FlowBuilder.split(TaskExecutor)} on the bounded {@code batchTaskExecutor} supplied by
 * {@code com.carddemo.config.BatchConfig}.</p>
 *
 * <h2>Launch semantics</h2>
 * <p>No job (including this master) is auto-run at boot ({@code spring.batch.job.enabled=false}); the
 * pipeline is launched only programmatically through {@link #runPipeline(LocalDate, LocalDate, LocalDate)}
 * (or its {@link #runPipeline(String, String, String) string-typed} variant), which builds a fresh
 * {@link JobParameters} set &mdash; including a unique run id &mdash; and calls the auto-configured
 * {@link JobLauncher}. There is intentionally no {@code @Scheduled} method and no
 * {@code CommandLineRunner}. The {@link JobRepository} and {@link JobLauncher} are the beans
 * auto-configured by Spring Boot (no {@code @EnableBatchProcessing}); the launching thread's MDC
 * (notably the {@code correlationId}) and Micrometer observation/tracing context propagate to the
 * launched executions and &mdash; because the bounded {@code batchTaskExecutor} is fitted with a
 * {@code BatchContextPropagatingTaskDecorator} &mdash; onto the {@code carddemo-batch-*} worker
 * threads of the parallel stage-4 split as well, so both terminal branches keep unbroken log
 * correlation and child spans (Observability rule, AAP &sect;0.7.1). This class adds no batch metrics
 * (the child jobs and writers own those).</p>
 */
@Configuration(value = "batchPipelineOrchestratorConfig", proxyBeanMethods = false)
public class BatchPipelineOrchestrator {

    /** Logger for pipeline launch outcomes; structured JSON + correlation id are added by Logback (AAP &sect;0.7.1). */
    private static final Logger LOGGER = LoggerFactory.getLogger(BatchPipelineOrchestrator.class);

    /** Canonical Spring Batch job name and bean name of the master pipeline job. */
    public static final String PIPELINE_JOB_NAME = "cardDemoBatchPipelineJob";

    /** Bean name of the {@link JobParametersExtractor} that forwards master parameters to every child job. */
    public static final String PARAMETERS_EXTRACTOR_BEAN = "pipelineJobParametersExtractor";

    /** Bean name of the POSTTRAN condition-code {@link JobExecutionDecider}. */
    public static final String DECIDER_BEAN = "postingConditionDecider";

    /** Job-parameter key carrying a unique run id so each pipeline launch is a distinct {@code JobInstance}. */
    public static final String RUN_ID_KEY = "runId";

    /** Bean name of the bounded, multi-threaded split executor declared by {@code com.carddemo.config.BatchConfig}. */
    static final String BATCH_TASK_EXECUTOR_BEAN = "batchTaskExecutor";

    /** Internal flow name of the master pipeline flow. */
    static final String PIPELINE_FLOW_NAME = "cardDemoBatchPipelineFlow";

    /** Step name of the POSTTRAN {@code JobStep} wrapper (stage&nbsp;1). */
    static final String POSTING_STEP_NAME = "pipelinePostingJobStep";

    /** Step name of the INTCALC {@code JobStep} wrapper (stage&nbsp;2). */
    static final String INTEREST_STEP_NAME = "pipelineInterestJobStep";

    /** Step name of the COMBTRAN {@code JobStep} wrapper (stage&nbsp;3). */
    static final String COMBINE_STEP_NAME = "pipelineCombineJobStep";

    /** Step name of the CREASTMT {@code JobStep} wrapper (stage&nbsp;4a). */
    static final String STATEMENT_STEP_NAME = "pipelineStatementJobStep";

    /** Step name of the TRANREPT {@code JobStep} wrapper (stage&nbsp;4b). */
    static final String REPORT_STEP_NAME = "pipelineReportJobStep";

    /** Internal flow name of the concurrent stage-4 split (CREASTMT &#8214; TRANREPT). */
    static final String STAGE4_SPLIT_FLOW_NAME = "stage4Split";

    /** Internal flow name of the stage-4a statement-generation branch. */
    static final String STATEMENT_FLOW_NAME = "statementFlow";

    /** Internal flow name of the stage-4b transaction-report branch. */
    static final String REPORT_FLOW_NAME = "reportFlow";

    /** Decider outcome routing the pipeline onward (POSTTRAN succeeded, with or without rejects). */
    static final String DECISION_CONTINUE = "CONTINUE";

    /** Decider outcome stopping the pipeline (POSTTRAN failed). */
    static final String DECISION_STOP = "STOP";

    /** Wildcard transition pattern matching any exit status (used to route every POSTTRAN outcome to the decider). */
    static final String MATCH_ALL = "*";

    /** The {@link ExitStatus#COMPLETED} exit code, used as the success-only transition for stages&nbsp;2&rarr;3&rarr;4. */
    static final String EXIT_COMPLETED = ExitStatus.COMPLETED.getExitCode();

    /**
     * Formatter producing the 10-character COBOL {@code PARM-DATE PIC X(10)} ({@code yyyyMMddHH}, e.g.
     * {@code 2022071800}) expected by {@link InterestCalculationJob}; a {@link LocalDate} is taken at
     * start of day so the trailing hour component is {@code 00}, matching the {@code INTCALC.jcl} PARM.
     */
    static final DateTimeFormatter PARM_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHH");

    /** Stage&nbsp;1 child job {@code POSTTRAN} ({@link DailyTransactionPostingJob}). */
    private final Job dailyTransactionPostingJob;

    /** Stage&nbsp;2 child job {@code INTCALC} ({@link InterestCalculationJob}). */
    private final Job interestCalculationJob;

    /** Stage&nbsp;3 child job {@code COMBTRAN} ({@link CombineTransactionsJob}). */
    private final Job combineTransactionsJob;

    /** Stage&nbsp;4a child job {@code CREASTMT} ({@link StatementGenerationJob}). */
    private final Job statementGenerationJob;

    /** Stage&nbsp;4b child job {@code TRANREPT} ({@link TransactionReportJob}). */
    private final Job transactionReportJob;

    /** Auto-configured Spring Batch job repository (used by the master {@link JobBuilder} and the {@code JobStep}s). */
    private final JobRepository jobRepository;

    /** Auto-configured Spring Batch job launcher (used by each {@code JobStep} and by {@link #runPipeline}). */
    private final JobLauncher jobLauncher;

    /** Bounded, multi-threaded executor backing the concurrent stage-4 split (from {@code BatchConfig}). */
    private final TaskExecutor batchTaskExecutor;

    /** The master pipeline job bean defined in this class, injected lazily to break the self-reference cycle. */
    private final Job cardDemoBatchPipelineJob;

    /**
     * Creates the batch pipeline orchestrator with its five child jobs and the batch infrastructure
     * beans injected by the Spring container.
     *
     * <p>The {@link #cardDemoBatchPipelineJob master pipeline job} is injected {@link Lazy lazily}
     * because it is a {@code @Bean} produced by this same configuration; the lazy proxy is resolved on
     * the first {@link #runPipeline} call, which avoids the otherwise-circular bean dependency.</p>
     *
     * @param dailyTransactionPostingJob stage&nbsp;1 POSTTRAN job; never {@code null}
     * @param interestCalculationJob     stage&nbsp;2 INTCALC job; never {@code null}
     * @param combineTransactionsJob     stage&nbsp;3 COMBTRAN job; never {@code null}
     * @param statementGenerationJob     stage&nbsp;4a CREASTMT job; never {@code null}
     * @param transactionReportJob       stage&nbsp;4b TRANREPT job; never {@code null}
     * @param jobRepository              the auto-configured Spring Batch job repository; never {@code null}
     * @param jobLauncher                the auto-configured Spring Batch job launcher; never {@code null}
     * @param batchTaskExecutor          the bounded split executor ({@code batchTaskExecutor} bean); never {@code null}
     * @param cardDemoBatchPipelineJob   the master pipeline job (lazy self-reference); never {@code null}
     */
    public BatchPipelineOrchestrator(
            @Qualifier(DailyTransactionPostingJob.JOB_NAME) final Job dailyTransactionPostingJob,
            @Qualifier(InterestCalculationJob.JOB_NAME) final Job interestCalculationJob,
            @Qualifier(CombineTransactionsJob.JOB_NAME) final Job combineTransactionsJob,
            @Qualifier(StatementGenerationJob.JOB_NAME) final Job statementGenerationJob,
            @Qualifier(TransactionReportJob.JOB_NAME) final Job transactionReportJob,
            final JobRepository jobRepository,
            final JobLauncher jobLauncher,
            @Qualifier(BATCH_TASK_EXECUTOR_BEAN) final TaskExecutor batchTaskExecutor,
            @Lazy @Qualifier(PIPELINE_JOB_NAME) final Job cardDemoBatchPipelineJob) {
        this.dailyTransactionPostingJob =
                Objects.requireNonNull(dailyTransactionPostingJob, "dailyTransactionPostingJob must not be null");
        this.interestCalculationJob =
                Objects.requireNonNull(interestCalculationJob, "interestCalculationJob must not be null");
        this.combineTransactionsJob =
                Objects.requireNonNull(combineTransactionsJob, "combineTransactionsJob must not be null");
        this.statementGenerationJob =
                Objects.requireNonNull(statementGenerationJob, "statementGenerationJob must not be null");
        this.transactionReportJob =
                Objects.requireNonNull(transactionReportJob, "transactionReportJob must not be null");
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        this.jobLauncher = Objects.requireNonNull(jobLauncher, "jobLauncher must not be null");
        this.batchTaskExecutor = Objects.requireNonNull(batchTaskExecutor, "batchTaskExecutor must not be null");
        this.cardDemoBatchPipelineJob =
                Objects.requireNonNull(cardDemoBatchPipelineJob, "cardDemoBatchPipelineJob must not be null");
    }

    /**
     * The {@link JobParametersExtractor} used by every child {@code JobStep} to forward the master
     * pipeline's {@link JobParameters} to the wrapped child job. {@code useAllParentParameters} is
     * enabled so {@code parmDate} (INTCALC), {@code startDate}/{@code endDate} (TRANREPT) and the unique
     * {@code runId} all reach the child jobs; each child therefore runs as a fresh {@code JobInstance}
     * per pipeline launch and reads its required parameters via {@code @Value("#{jobParameters[...]}")}.
     *
     * @return the parent-parameter-forwarding extractor bean
     */
    @Bean(PARAMETERS_EXTRACTOR_BEAN)
    public JobParametersExtractor pipelineJobParametersExtractor() {
        final DefaultJobParametersExtractor extractor = new DefaultJobParametersExtractor();
        extractor.setUseAllParentParameters(true);
        return extractor;
    }

    /**
     * The POSTTRAN condition-code decider (JCL {@code COND} &rarr; Spring Batch decision). It inspects
     * the posting {@code JobStep}'s outcome and routes the flow: a successful POSTTRAN &mdash; whether
     * it completed cleanly ({@link ExitStatus#COMPLETED}) or with rejects
     * ({@value DailyTransactionPostingJob#COMPLETED_WITH_REJECTS}, {@code RC=4}) &mdash; yields
     * {@value #DECISION_CONTINUE}; any failure yields {@value #DECISION_STOP}.
     *
     * @return the POSTTRAN condition-code decider bean
     */
    @Bean(DECIDER_BEAN)
    public JobExecutionDecider postingConditionDecider() {
        return new PostingConditionDecider();
    }

    /**
     * Builds the master pipeline {@link Job} ({@value #PIPELINE_JOB_NAME}) that orchestrates the five
     * child jobs in the {@code POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; {CREASTMT &#8214; TRANREPT}}
     * order.
     *
     * <p>Each child job is wrapped in a {@code JobStep} (built by {@link #jobStep}); the master flow is
     * assembled so that:</p>
     * <ul>
     *   <li>POSTTRAN's every outcome routes ({@value #MATCH_ALL}) to the {@code postingConditionDecider},
     *       which yields {@value #DECISION_CONTINUE} on success (clean or {@code RC=4}) and
     *       {@value #DECISION_STOP} on failure;</li>
     *   <li>{@value #DECISION_CONTINUE} proceeds to INTCALC while {@value #DECISION_STOP} fails the job
     *       (a failed POSTTRAN stops the pipeline);</li>
     *   <li>INTCALC and COMBTRAN each transition onward only on the {@link #EXIT_COMPLETED} exit code
     *       (predecessor-success ordering &mdash; a failed predecessor leaves no matching transition and
     *       the job ends failed);</li>
     *   <li>COMBTRAN completion enters the {@link #stage4SplitFlow stage-4 split} that runs CREASTMT and
     *       TRANREPT concurrently.</li>
     * </ul>
     *
     * @param pipelineJobParametersExtractor the parameter-forwarding extractor shared by all child steps
     * @param postingConditionDecider        the POSTTRAN condition-code decider
     * @return the configured master pipeline job
     */
    @Bean(PIPELINE_JOB_NAME)
    public Job cardDemoBatchPipelineJob(
            @Qualifier(PARAMETERS_EXTRACTOR_BEAN) final JobParametersExtractor pipelineJobParametersExtractor,
            @Qualifier(DECIDER_BEAN) final JobExecutionDecider postingConditionDecider) {

        final Step postingJobStep =
                jobStep(POSTING_STEP_NAME, this.dailyTransactionPostingJob, pipelineJobParametersExtractor);
        final Step interestJobStep =
                jobStep(INTEREST_STEP_NAME, this.interestCalculationJob, pipelineJobParametersExtractor);
        final Step combineJobStep =
                jobStep(COMBINE_STEP_NAME, this.combineTransactionsJob, pipelineJobParametersExtractor);
        final Step statementJobStep =
                jobStep(STATEMENT_STEP_NAME, this.statementGenerationJob, pipelineJobParametersExtractor);
        final Step transactionReportJobStep =
                jobStep(REPORT_STEP_NAME, this.transactionReportJob, pipelineJobParametersExtractor);

        final Flow stage4Split = stage4SplitFlow(statementJobStep, transactionReportJobStep);

        final Flow pipelineFlow = new FlowBuilder<Flow>(PIPELINE_FLOW_NAME)
                .start(postingJobStep)
                .on(MATCH_ALL).to(postingConditionDecider)
                .from(postingConditionDecider).on(DECISION_CONTINUE).to(interestJobStep)
                .from(postingConditionDecider).on(DECISION_STOP).fail()
                .from(interestJobStep).on(EXIT_COMPLETED).to(combineJobStep)
                .from(combineJobStep).on(EXIT_COMPLETED).to(stage4Split)
                .end();

        return new JobBuilder(PIPELINE_JOB_NAME, this.jobRepository)
                .start(pipelineFlow)
                .end()
                .build();
    }

    /**
     * Wraps a child {@link Job} in a Spring Batch {@code JobStep} so it executes inside the master
     * pipeline flow, launching it through the auto-configured {@link JobLauncher} and forwarding the
     * master {@link JobParameters} via the supplied {@link JobParametersExtractor}.
     *
     * @param stepName    the unique step name for the wrapper (tracked in the job repository)
     * @param delegateJob the child job to run as a step
     * @param extractor   the extractor that forwards the master parameters to the child job
     * @return the {@code JobStep} wrapping {@code delegateJob}
     */
    private Step jobStep(final String stepName, final Job delegateJob, final JobParametersExtractor extractor) {
        return new StepBuilder(stepName, this.jobRepository)
                .job(delegateJob)
                .launcher(this.jobLauncher)
                .parametersExtractor(extractor)
                .build();
    }

    /**
     * Builds the concurrent stage-4 split flow ({@value #STAGE4_SPLIT_FLOW_NAME}) that runs the
     * statement-generation branch (4a) and the transaction-report branch (4b) at the same time on the
     * bounded {@code batchTaskExecutor}. A multi-threaded executor is required: a synchronous executor
     * would serialize the two branches and defeat the split.
     *
     * @param statementJobStep         the CREASTMT {@code JobStep} (stage&nbsp;4a)
     * @param transactionReportJobStep the TRANREPT {@code JobStep} (stage&nbsp;4b)
     * @return the parallel stage-4 split flow
     */
    private Flow stage4SplitFlow(final Step statementJobStep, final Step transactionReportJobStep) {
        final Flow statementFlow =
                new FlowBuilder<Flow>(STATEMENT_FLOW_NAME).start(statementJobStep).build();
        final Flow reportFlow =
                new FlowBuilder<Flow>(REPORT_FLOW_NAME).start(transactionReportJobStep).build();
        return new FlowBuilder<Flow>(STAGE4_SPLIT_FLOW_NAME)
                .split(this.batchTaskExecutor)
                .add(statementFlow, reportFlow)
                .build();
    }

    /**
     * Launches the master pipeline for the given processing and reporting dates. The {@code processingDate}
     * is rendered to the 10-character {@code parmDate} ({@code yyyyMMddHH}, taken at start of day) that
     * INTCALC expects, and {@code startDate}/{@code endDate} are rendered as ISO {@code yyyy-MM-dd}
     * strings (the inclusive reporting window TRANREPT expects).
     *
     * @param processingDate the interest processing date (becomes {@code parmDate}); never {@code null}
     * @param startDate      inclusive report window lower bound (becomes {@code startDate}); never {@code null}
     * @param endDate        inclusive report window upper bound (becomes {@code endDate}); never {@code null}
     * @return the started {@link JobExecution} of the master pipeline
     * @throws JobExecutionException if the launcher cannot start the pipeline
     */
    public JobExecution runPipeline(final LocalDate processingDate, final LocalDate startDate, final LocalDate endDate)
            throws JobExecutionException {
        Objects.requireNonNull(processingDate, "processingDate must not be null");
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(endDate, "endDate must not be null");
        final String parmDate = processingDate.atStartOfDay().format(PARM_DATE_FORMATTER);
        return runPipeline(parmDate, startDate.toString(), endDate.toString());
    }

    /**
     * Launches the master pipeline with explicit, already-formatted parameter strings, building a fresh
     * {@link JobParameters} set &mdash; {@code parmDate} (INTCALC), {@code startDate}/{@code endDate}
     * (TRANREPT) and a unique {@value #RUN_ID_KEY} &mdash; so every launch is a distinct
     * {@code JobInstance}, and calling the auto-configured {@link JobLauncher}. This is invoked
     * programmatically (tests or an admin trigger); no job runs at boot.
     *
     * @param parmDate  the 10-character COBOL {@code PARM-DATE} for INTCALC ({@code yyyyMMddHH}); never {@code null}
     * @param startDate inclusive report window lower bound for TRANREPT ({@code yyyy-MM-dd}); never {@code null}
     * @param endDate   inclusive report window upper bound for TRANREPT ({@code yyyy-MM-dd}); never {@code null}
     * @return the started {@link JobExecution} of the master pipeline
     * @throws JobExecutionException if the launcher cannot start the pipeline
     */
    public JobExecution runPipeline(final String parmDate, final String startDate, final String endDate)
            throws JobExecutionException {
        Objects.requireNonNull(parmDate, "parmDate must not be null");
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(endDate, "endDate must not be null");
        final JobParameters parameters = new JobParametersBuilder()
                .addString(InterestCalculationJob.PARM_DATE_KEY, parmDate)
                .addString(TransactionReportJob.PARAM_START_DATE, startDate)
                .addString(TransactionReportJob.PARAM_END_DATE, endDate)
                .addString(RUN_ID_KEY, UUID.randomUUID().toString())
                .toJobParameters();
        LOGGER.info("Launching {} (parmDate={}, window=[{}..{}])",
                PIPELINE_JOB_NAME, parmDate, startDate, endDate);
        final JobExecution execution = this.jobLauncher.run(this.cardDemoBatchPipelineJob, parameters);
        LOGGER.info("Launched {}: executionId={}, status={}, exitCode={}",
                PIPELINE_JOB_NAME, execution.getId(), execution.getStatus(),
                execution.getExitStatus().getExitCode());
        return execution;
    }

    /**
     * The POSTTRAN condition-code {@link JobExecutionDecider}. After the posting {@code JobStep} runs,
     * its exit status carries the child job's outcome ({@link ExitStatus#COMPLETED},
     * {@value DailyTransactionPostingJob#COMPLETED_WITH_REJECTS}, or a failure). This decider maps that
     * outcome to a routing decision: a successful posting &mdash; clean or with rejects ({@code RC=4})
     * &mdash; yields {@value #DECISION_CONTINUE}; any failure (or an unexpected exit code) yields
     * {@value #DECISION_STOP}. {@code RC=4} is therefore honored as a warning, never as a stop.
     */
    static final class PostingConditionDecider implements JobExecutionDecider {

        /** POSTTRAN exit codes that permit the pipeline to proceed: clean completion and {@code RC=4} (rejects). */
        private static final Set<String> CONTINUE_EXIT_CODES = Set.of(
                ExitStatus.COMPLETED.getExitCode(),
                DailyTransactionPostingJob.COMPLETED_WITH_REJECTS);

        /**
         * Decides whether the pipeline proceeds past POSTTRAN.
         *
         * @param jobExecution  the master job execution (not inspected; the step outcome is authoritative)
         * @param stepExecution the just-completed posting {@code JobStep} execution
         * @return {@value #DECISION_CONTINUE} when the posting step succeeded (with or without rejects),
         *         otherwise {@value #DECISION_STOP}
         */
        @Override
        public FlowExecutionStatus decide(final JobExecution jobExecution, final StepExecution stepExecution) {
            if (stepExecution == null) {
                return new FlowExecutionStatus(DECISION_STOP);
            }
            final ExitStatus exitStatus = stepExecution.getExitStatus();
            final String exitCode = (exitStatus == null) ? null : exitStatus.getExitCode();
            final boolean proceed =
                    !stepExecution.getStatus().isUnsuccessful() && CONTINUE_EXIT_CODES.contains(exitCode);
            return proceed
                    ? new FlowExecutionStatus(DECISION_CONTINUE)
                    : new FlowExecutionStatus(DECISION_STOP);
        }
    }
}
