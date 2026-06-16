package com.carddemo.batch.jobs;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import org.springframework.batch.core.step.job.JobParametersExtractor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;

/**
 * Spring Batch re-host of the mainframe CardDemo batch <em>job stream</em>: the master
 * orchestration that sequences the five batch stages in the exact order the JCL implied
 * (source commit {@code 27d6c6f}; REFERENCE ONLY &mdash; no COBOL/JCL is copied, lineage is
 * preserved by the commit SHA). The reference job stream is:
 *
 * <pre>
 *   POSTTRAN ({@code app/jcl/POSTTRAN.jcl})  &rarr; stage 1  daily transaction posting
 *   INTCALC  ({@code app/jcl/INTCALC.jcl})   &rarr; stage 2  interest calculation
 *   COMBTRAN ({@code app/jcl/COMBTRAN.jcl})  &rarr; stage 3  combine transactions
 *   CREASTMT ({@code app/jcl/CREASTMT.JCL})  &rarr; stage 4a statement generation   } run
 *   TRANREPT ({@code app/jcl/TRANREPT.jcl})  &rarr; stage 4b transaction report     } concurrently
 * </pre>
 *
 * <p>A single master Spring Batch {@link Job} ({@value #PIPELINE_JOB_NAME}) wraps each of the five
 * sibling child {@link Job} beans in a {@link org.springframework.batch.core.step.job.JobStep} so
 * every sub-job executes inside the master flow. The JCL {@code COND} condition codes are realised
 * as a {@link JobExecutionDecider} plus Spring Batch {@link ExitStatus} matching, and the two
 * stage-4 jobs run concurrently through a {@link FlowBuilder#split(TaskExecutor)}. The detailed
 * rationale for these mappings (COND &rarr; decider, stage-4 &rarr; split, RC=4-but-continue) is
 * recorded in {@code DECISION_LOG.md}, not in code comments (Explainability rule, AAP &sect;0.7.3).
 *
 * <p><b>Predecessor-success ordering (AAP &sect;0.8.5).</b> Each stage runs only after its
 * predecessor reaches an acceptable exit status: POSTTRAN feeds a decider that allows INTCALC to
 * proceed when posting finished {@link ExitStatus#COMPLETED} or with the RC=4 warning
 * {@link DailyTransactionPostingJob#COMPLETED_WITH_REJECTS}; INTCALC then COMBTRAN then the stage-4
 * split each gate on the predecessor completing successfully.
 *
 * <p><b>Condition-code honouring of RC=4.</b> The COBOL posting program raised a return code of 4
 * (a warning, not an error) when it rejected one or more transactions; the daily posting job maps
 * that to the {@link DailyTransactionPostingJob#COMPLETED_WITH_REJECTS} exit status. The decider
 * treats that warning exactly like {@code COMPLETED} so downstream stages still run; only a genuine
 * {@code FAILED} posting stops the pipeline.
 *
 * <p><b>Not auto-run (AAP &sect;0.8).</b> No job &mdash; including this master &mdash; is launched at
 * boot ({@code spring.batch.job.enabled=false}); there is no {@code @Scheduled} task and no
 * {@code CommandLineRunner}. The pipeline is launched explicitly through {@link JobLauncher} by the
 * {@link #runPipeline(LocalDate, LocalDate, LocalDate)} method (invoked by tests or an
 * administrative trigger). The transaction-report job is additionally launched on its own by the
 * SQS report-submission listener for ad-hoc reports.
 *
 * <p><b>Observability (AAP &sect;0.7.1).</b> A {@code correlationId} MDC entry (the same key the
 * {@code CorrelationIdFilter} publishes and {@code logback-spring.xml} renders) is ensured for the
 * duration of a launch so every log line emitted by the orchestration and the synchronously
 * launched stages is correlated. Batch throughput/reject metrics are owned by the child
 * jobs/writers; this orchestrator deliberately adds none of its own.
 */
@Configuration(proxyBeanMethods = false)
public class BatchPipelineOrchestrator {

    // ---------------------------------------------------------------------------------------------
    // Canonical names (job, steps, flows) and transition values. Kept as constants so tests and
    // the traceability matrix can reference them without string duplication.
    // ---------------------------------------------------------------------------------------------

    /** Name of the master pipeline {@link Job} (also its Spring bean name). */
    public static final String PIPELINE_JOB_NAME = "cardDemoBatchPipelineJob";

    /** Step name wrapping stage 1, the daily transaction posting child job (POSTTRAN). */
    public static final String DAILY_POSTING_STEP_NAME = "dailyPostingJobStep";

    /** Step name wrapping stage 2, the interest calculation child job (INTCALC). */
    public static final String INTEREST_STEP_NAME = "interestJobStep";

    /** Step name wrapping stage 3, the combine transactions child job (COMBTRAN). */
    public static final String COMBINE_STEP_NAME = "combineJobStep";

    /** Step name wrapping stage 4a, the statement generation child job (CREASTMT). */
    public static final String STATEMENT_STEP_NAME = "statementJobStep";

    /** Step name wrapping stage 4b, the transaction report child job (TRANREPT). */
    public static final String REPORT_STEP_NAME = "reportJobStep";

    /** Decider outcome that allows the pipeline to proceed past POSTTRAN (RC=0 or RC=4). */
    public static final String DECISION_CONTINUE = "CONTINUE";

    /** Decider outcome that halts the pipeline after a failed POSTTRAN. */
    public static final String DECISION_STOP = "STOP";

    /** Job-parameter key carrying a per-launch unique id, guaranteeing a fresh {@code JobInstance}. */
    public static final String RUN_ID_KEY = "run.id";

    /** Name of the self-contained master pipeline flow. */
    static final String PIPELINE_FLOW_NAME = "cardDemoBatchPipelineFlow";

    /** Name of the stage-4 parallel split flow (CREASTMT alongside TRANREPT). */
    static final String STAGE4_SPLIT_FLOW_NAME = "stage4SplitFlow";

    /** Name of the stage-4a (statement generation) sub-flow inside the split. */
    static final String STATEMENT_FLOW_NAME = "statementGenerationFlow";

    /** Name of the stage-4b (transaction report) sub-flow inside the split. */
    static final String REPORT_FLOW_NAME = "transactionReportFlow";

    /** Spring Batch transition pattern matching any exit status. */
    private static final String MATCH_ALL = "*";

    /** Transition pattern for an exactly-{@code COMPLETED} predecessor (predecessor-success gate). */
    private static final String COMPLETED_PATTERN = ExitStatus.COMPLETED.getExitCode();

    /** MDC key under which the correlation id is published; matches {@code CorrelationIdFilter}. */
    private static final String MDC_CORRELATION_ID_KEY = "correlationId";

    /** Date portion of the ten-character {@code parmDate} (COBOL {@code PARM='2022071800'}). */
    private static final DateTimeFormatter PARM_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Trailing two characters (the run hour) completing the ten-character {@code parmDate}. */
    private static final String PARM_DATE_HOUR_SUFFIX = "00";

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchPipelineOrchestrator.class);

    /** The synchronous launcher used to run the master pipeline and forwarded to every {@code JobStep}. */
    private final JobLauncher jobLauncher;

    /** The fully assembled master pipeline job, built once at construction and exposed as a bean. */
    private final Job pipelineJob;

    /**
     * Assembles the master pipeline at construction time from the five injected child job beans and
     * the batch infrastructure. Building the flow here (rather than across several factory methods)
     * keeps the wiring deterministic and lets {@link #runPipeline(LocalDate, LocalDate, LocalDate)}
     * launch the resulting job without a self-referential bean lookup.
     *
     * @param jobRepository                the Spring Batch job repository (Boot auto-configured)
     * @param jobLauncher                  the synchronous job launcher (Boot auto-configured)
     * @param stage4TaskExecutor           the bounded, multi-threaded executor that drives the
     *                                     stage-4 split so CREASTMT and TRANREPT run concurrently
     *                                     (the {@code batchTaskExecutor} bean from {@code BatchConfig};
     *                                     never a {@code SyncTaskExecutor}, which would serialize them)
     * @param dailyTransactionPostingJob   stage 1 child job (POSTTRAN)
     * @param interestCalculationJob       stage 2 child job (INTCALC)
     * @param combineTransactionsJob       stage 3 child job (COMBTRAN)
     * @param statementGenerationJob       stage 4a child job (CREASTMT)
     * @param transactionReportJob         stage 4b child job (TRANREPT)
     */
    public BatchPipelineOrchestrator(
            JobRepository jobRepository,
            JobLauncher jobLauncher,
            @Qualifier("batchTaskExecutor") TaskExecutor stage4TaskExecutor,
            @Qualifier("dailyTransactionPostingJob") Job dailyTransactionPostingJob,
            @Qualifier("interestCalculationJob") Job interestCalculationJob,
            @Qualifier("combineTransactionsJob") Job combineTransactionsJob,
            @Qualifier("statementGenerationJob") Job statementGenerationJob,
            @Qualifier("transactionReportJob") Job transactionReportJob) {
        this.jobLauncher = jobLauncher;
        JobParametersExtractor parametersExtractor = forwardingParametersExtractor();
        Step dailyPostingJobStep =
                jobStep(DAILY_POSTING_STEP_NAME, dailyTransactionPostingJob, jobRepository, parametersExtractor);
        Step interestJobStep =
                jobStep(INTEREST_STEP_NAME, interestCalculationJob, jobRepository, parametersExtractor);
        Step combineJobStep =
                jobStep(COMBINE_STEP_NAME, combineTransactionsJob, jobRepository, parametersExtractor);
        Step statementJobStep =
                jobStep(STATEMENT_STEP_NAME, statementGenerationJob, jobRepository, parametersExtractor);
        Step reportJobStep =
                jobStep(REPORT_STEP_NAME, transactionReportJob, jobRepository, parametersExtractor);
        this.pipelineJob = buildPipelineJob(
                jobRepository,
                stage4TaskExecutor,
                dailyPostingJobStep,
                interestJobStep,
                combineJobStep,
                statementJobStep,
                reportJobStep);
    }

    /**
     * Exposes the assembled master pipeline as a Spring bean named {@value #PIPELINE_JOB_NAME}. The
     * job is not auto-run ({@code spring.batch.job.enabled=false}); the bean exists so test harnesses
     * and administrative triggers can resolve and launch it.
     *
     * @return the master pipeline job
     */
    @Bean(PIPELINE_JOB_NAME)
    public Job cardDemoBatchPipelineJob() {
        return pipelineJob;
    }

    /**
     * Launches the five-stage pipeline through the {@link JobLauncher} with a fresh
     * {@code JobInstance}. The master {@link JobParameters} are forwarded verbatim to each child job
     * by the {@code JobParametersExtractor}, so {@code parmDate} reaches INTCALC and
     * {@code startDate}/{@code endDate} reach TRANREPT.
     *
     * <p>A unique {@value #RUN_ID_KEY} parameter guarantees a distinct {@code JobInstance} on every
     * call. A {@code correlationId} is ensured in the MDC for the launch (an existing one &mdash; for
     * example, set by the {@code CorrelationIdFilter} on an HTTP-triggered launch &mdash; is reused
     * and never overwritten; one generated here is removed afterward).
     *
     * @param processingDate the interest run date (INTCALC {@code parmDate}); formatted to the
     *                       ten-character {@code yyyyMMdd} + {@value #PARM_DATE_HOUR_SUFFIX} form
     * @param startDate      the inclusive transaction-report window start (TRANREPT {@code startDate})
     * @param endDate        the inclusive transaction-report window end (TRANREPT {@code endDate})
     * @return the completed (synchronous launcher) master {@link JobExecution}
     * @throws JobExecutionException     if the launcher cannot start the job (already running,
     *                                   restart issue, instance already complete, or invalid params)
     * @throws NullPointerException      if any date argument is {@code null}
     */
    public JobExecution runPipeline(LocalDate processingDate, LocalDate startDate, LocalDate endDate)
            throws JobExecutionException {
        Objects.requireNonNull(processingDate, "processingDate must not be null");
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(endDate, "endDate must not be null");
        JobParameters jobParameters = new JobParametersBuilder()
                .addString(InterestCalculationJob.PARM_DATE_KEY,
                        processingDate.format(PARM_DATE_FORMAT) + PARM_DATE_HOUR_SUFFIX)
                .addString(TransactionReportJob.PARAM_START_DATE, startDate.toString())
                .addString(TransactionReportJob.PARAM_END_DATE, endDate.toString())
                .addString(RUN_ID_KEY, UUID.randomUUID().toString())
                .toJobParameters();
        boolean correlationIdGenerated = ensureCorrelationId();
        try {
            LOGGER.info("Launching batch pipeline {} (processingDate={}, reportWindow=[{}, {}])",
                    PIPELINE_JOB_NAME, processingDate, startDate, endDate);
            return jobLauncher.run(pipelineJob, jobParameters);
        } finally {
            if (correlationIdGenerated) {
                MDC.remove(MDC_CORRELATION_ID_KEY);
            }
        }
    }

    /**
     * Builds a {@link JobParametersExtractor} that forwards the master job's parameters unchanged to
     * each child job. Forwarding the whole parameter set is intentional: a child simply ignores keys
     * it does not consume, and the shared unique {@value #RUN_ID_KEY} keeps every child's
     * {@code JobInstance} fresh per master run while remaining stable across a restart of the same
     * master instance.
     *
     * @return the parent-to-child parameter forwarding extractor
     */
    private JobParametersExtractor forwardingParametersExtractor() {
        return (job, stepExecution) -> stepExecution.getJobParameters();
    }

    /**
     * Wraps a child {@link Job} in a {@code JobStep} so it executes as a step inside the master flow,
     * launched through the shared {@link #jobLauncher} with parameters supplied by the extractor.
     *
     * @param stepName            the unique step name
     * @param childJob            the child job to run
     * @param jobRepository       the Spring Batch job repository
     * @param parametersExtractor the master-to-child parameter extractor
     * @return the configured {@code JobStep}
     */
    private Step jobStep(
            String stepName, Job childJob, JobRepository jobRepository, JobParametersExtractor parametersExtractor) {
        return new StepBuilder(stepName, jobRepository)
                .job(childJob)
                .launcher(jobLauncher)
                .parametersExtractor(parametersExtractor)
                .build();
    }

    /**
     * Assembles the master pipeline flow and wraps it in a {@link Job}.
     *
     * <p>The flow encodes the predecessor-success ordering and the JCL condition codes:
     * POSTTRAN routes every outcome to the {@link PostingConditionDecider}; the decider sends
     * {@link #DECISION_CONTINUE} (RC=0 or RC=4) on to INTCALC and {@link #DECISION_STOP} (a failed
     * posting) to a failing end-state; INTCALC then COMBTRAN proceed only on {@link ExitStatus#COMPLETED}
     * and otherwise fail; COMBTRAN's success leads into the stage-4 split, which runs CREASTMT and
     * TRANREPT concurrently on the supplied executor and completes the pipeline only when both
     * succeed.
     *
     * @param jobRepository      the Spring Batch job repository
     * @param stage4TaskExecutor the multi-threaded executor backing the stage-4 split
     * @param dailyPostingJobStep stage 1 step
     * @param interestJobStep     stage 2 step
     * @param combineJobStep      stage 3 step
     * @param statementJobStep    stage 4a step
     * @param reportJobStep       stage 4b step
     * @return the assembled master pipeline job
     */
    private Job buildPipelineJob(
            JobRepository jobRepository,
            TaskExecutor stage4TaskExecutor,
            Step dailyPostingJobStep,
            Step interestJobStep,
            Step combineJobStep,
            Step statementJobStep,
            Step reportJobStep) {
        JobExecutionDecider postingConditionDecider = new PostingConditionDecider();

        Flow statementFlow = new FlowBuilder<Flow>(STATEMENT_FLOW_NAME)
                .start(statementJobStep)
                .build();
        Flow reportFlow = new FlowBuilder<Flow>(REPORT_FLOW_NAME)
                .start(reportJobStep)
                .build();
        Flow stage4Split = new FlowBuilder<Flow>(STAGE4_SPLIT_FLOW_NAME)
                .split(stage4TaskExecutor)
                .add(statementFlow, reportFlow)
                .build();

        Flow pipelineFlow = new FlowBuilder<Flow>(PIPELINE_FLOW_NAME)
                .start(dailyPostingJobStep)
                    .on(MATCH_ALL).to(postingConditionDecider)
                .from(postingConditionDecider)
                    .on(DECISION_CONTINUE).to(interestJobStep)
                .from(postingConditionDecider)
                    .on(DECISION_STOP).fail()
                .from(interestJobStep)
                    .on(COMPLETED_PATTERN).to(combineJobStep)
                .from(interestJobStep)
                    .on(MATCH_ALL).fail()
                .from(combineJobStep)
                    .on(COMPLETED_PATTERN).to(stage4Split)
                .from(combineJobStep)
                    .on(MATCH_ALL).fail()
                .from(stage4Split)
                    .on(COMPLETED_PATTERN).end()
                .from(stage4Split)
                    .on(MATCH_ALL).fail()
                .build();

        return new JobBuilder(PIPELINE_JOB_NAME, jobRepository)
                .start(pipelineFlow)
                .end()
                .build();
    }

    /**
     * Ensures a correlation id is present in the MDC. If one is already set (for example by the
     * {@code CorrelationIdFilter} during an HTTP-triggered launch) it is left untouched; otherwise a
     * new one is generated.
     *
     * @return {@code true} if a correlation id was generated here (and must be removed by the caller),
     *         {@code false} if an existing one was reused
     */
    private static boolean ensureCorrelationId() {
        if (MDC.get(MDC_CORRELATION_ID_KEY) == null) {
            MDC.put(MDC_CORRELATION_ID_KEY, UUID.randomUUID().toString());
            return true;
        }
        return false;
    }

    /**
     * Condition-code decider for POSTTRAN, the Java realisation of the JCL {@code COND} logic. It
     * inspects the posting step's exit status and routes the flow:
     * <ul>
     *   <li>{@link ExitStatus#COMPLETED} (RC=0) &rarr; {@link #DECISION_CONTINUE}</li>
     *   <li>{@link DailyTransactionPostingJob#COMPLETED_WITH_REJECTS} (the RC=4 warning) &rarr;
     *       {@link #DECISION_CONTINUE} &mdash; rejects are a warning, not a stop</li>
     *   <li>any other status (notably {@code FAILED}) &rarr; {@link #DECISION_STOP}</li>
     * </ul>
     *
     * <p>The decider is stateless and therefore safe to share across executions.
     */
    public static final class PostingConditionDecider implements JobExecutionDecider {

        /**
         * Decides whether the pipeline may proceed past POSTTRAN.
         *
         * @param jobExecution  the master job execution (fallback source of the exit status)
         * @param stepExecution the just-finished posting step execution (primary source)
         * @return {@link #DECISION_CONTINUE} when posting completed (with or without rejects),
         *         otherwise {@link #DECISION_STOP}
         */
        @Override
        public FlowExecutionStatus decide(JobExecution jobExecution, StepExecution stepExecution) {
            String exitCode = resolveExitCode(jobExecution, stepExecution);
            if (COMPLETED_PATTERN.equals(exitCode)
                    || DailyTransactionPostingJob.COMPLETED_WITH_REJECTS.equals(exitCode)) {
                return new FlowExecutionStatus(DECISION_CONTINUE);
            }
            return new FlowExecutionStatus(DECISION_STOP);
        }

        /**
         * Resolves the exit code to evaluate, preferring the immediately preceding step's exit status
         * and falling back to the job execution's exit status (and finally {@link ExitStatus#UNKNOWN}).
         *
         * @param jobExecution  the master job execution
         * @param stepExecution the preceding step execution (may be {@code null} in unusual flows)
         * @return the resolved, non-{@code null} exit code
         */
        private static String resolveExitCode(JobExecution jobExecution, StepExecution stepExecution) {
            if (stepExecution != null
                    && stepExecution.getExitStatus() != null
                    && stepExecution.getExitStatus().getExitCode() != null) {
                return stepExecution.getExitStatus().getExitCode();
            }
            if (jobExecution != null
                    && jobExecution.getExitStatus() != null
                    && jobExecution.getExitStatus().getExitCode() != null) {
                return jobExecution.getExitStatus().getExitCode();
            }
            return ExitStatus.UNKNOWN.getExitCode();
        }

        /**
         * @return a stable name for this decider's flow state, aiding flow logging and traceability
         */
        @Override
        public String toString() {
            return "postingConditionDecider";
        }
    }
}
