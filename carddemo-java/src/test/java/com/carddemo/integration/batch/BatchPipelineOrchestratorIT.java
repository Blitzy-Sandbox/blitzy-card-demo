package com.carddemo.integration.batch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.carddemo.batch.jobs.BatchPipelineOrchestrator;
import com.carddemo.batch.jobs.StatementGenerationJob;
import com.carddemo.batch.jobs.TransactionReportJob;
import com.carddemo.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the master Spring Batch pipeline orchestrator
 * {@link com.carddemo.batch.jobs.BatchPipelineOrchestrator} (master job bean
 * {@code cardDemoBatchPipelineJob}), proving behavioral parity with the mainframe COBOL/JCL
 * <strong>five-stage batch job stream</strong>
 * {@code POSTTRAN -> INTCALC -> COMBTRAN -> { CREASTMT (4a) || TRANREPT (4b) }}
 * (lineage: source commit {@code 27d6c6f}; REFERENCE ONLY &mdash; no COBOL/JCL is copied).
 *
 * <p>This test validates the three things the orchestrator re-platforms from the JCL job stream:</p>
 * <ul>
 *   <li><strong>Predecessor-success ordering</strong> &mdash; the stages run sequentially
 *       ({@code POSTTRAN} then {@code INTCALC} then {@code COMBTRAN}) and a failed predecessor stops
 *       the chain, so the presence of every stage execution proves the success chain advanced.</li>
 *   <li><strong>The {@code JobExecutionDecider} condition-code logic</strong> (JCL {@code COND}) &mdash;
 *       a {@code COMPLETED} or {@code COMPLETED_WITH_REJECTS} ({@code RC=4}) posting outcome lets the
 *       pipeline continue, while a genuine {@code FAILED} posting stops it.</li>
 *   <li><strong>The parallel stages 4a/4b</strong> ({@code CREASTMT} statement generation in parallel
 *       with {@code TRANREPT} transaction reporting) fanned out from the {@code FlowBuilder.split(...)}.</li>
 * </ul>
 *
 * <p>It supports <strong>Gate 1</strong> (end-to-end {@code dailytran.txt} to PostgreSQL + S3) and
 * <strong>Gate 7</strong> (multi-subsystem scope: file I/O, inter-job orchestration, DB, and S3).</p>
 *
 * <p>It {@code extends} {@link AbstractBatchIntegrationTest} and reuses all of its scaffolding
 * (singleton Testcontainers PostgreSQL + LocalStack, the {@code @DynamicPropertySource} wiring and
 * AWS self-provisioning, the fixture locator, the S3 helpers, the {@code JobLauncher}, the per-test
 * {@code @BeforeEach} S3 cleanup, and the shared constants). No container, property,
 * {@code @SpringBootTest}, {@code @ActiveProfiles}, {@code @Testcontainers}, or {@code @Tag}
 * scaffolding is re-declared here.</p>
 *
 * <p><strong>Shared-state robustness.</strong> The containers and the seeded database are shared
 * singletons across all of the batch integration tests, and the master pipeline is idempotent (the
 * interest {@code TRAN-ID} is {@code parmDate}-derived and the combine load upserts on the
 * {@code tranId} primary key), so every assertion here uses count <em>deltas</em> or robust
 * lower-bounds (never a brittle pristine-DB absolute), each launch carries a unique {@code run.id}
 * (a distinct master {@code JobInstance}), and each test stages its own S3 inputs.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BatchPipelineOrchestratorIT extends AbstractBatchIntegrationTest {

    /**
     * The 10-character COBOL {@code PARM-DATE} ({@code yyyyMMddHH}) forwarded to {@code INTCALC}
     * (mirrors {@code INTCALC.jcl} {@code STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}).
     */
    private static final String PARM_DATE = "2022071800";

    /** Inclusive lower bound of the {@code TRANREPT} reporting window ({@code yyyy-MM-dd}). */
    private static final String START_DATE = "2022-07-01";

    /** Inclusive upper bound of the {@code TRANREPT} reporting window ({@code yyyy-MM-dd}). */
    private static final String END_DATE = "2022-07-31";

    /**
     * S3 object key of the master-transaction backup that the combine stage ({@code COMBTRAN}) reads
     * first (the GDG base {@code TRANSACT.BKUP}); pre-staged for robust combine input (see
     * {@link #stagePipelineInputs()}).
     */
    private static final String TRANSACT_BKUP_KEY = "TRANSACT.BKUP";

    /** Best-effort evidence file capturing the per-stage outcome for Gate 1 / Gate 7. */
    private static final String SUMMARY_FILE = "pipeline-summary.txt";

    /** The minimum number of stage executions in a fully advanced pipeline (the five {@code JobStep}s). */
    private static final int PIPELINE_STAGE_COUNT = 5;

    /** The master pipeline job under test, injected by its canonical bean name. */
    @Autowired
    @Qualifier("cardDemoBatchPipelineJob")
    private Job cardDemoBatchPipelineJob;

    /** Transaction master repository (TRANSACT); used for the data-effect count deltas. */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * The orchestrator bean, injected as a required dependency. The programmatic-entry test asserts
     * the bean is present (a missing orchestrator is a wiring regression, not an environmental
     * precondition), so the injection is mandatory rather than optional.
     */
    @Autowired
    private BatchPipelineOrchestrator orchestrator;

    /**
     * Stages the pipeline's S3 inputs: the real {@code dailytran.txt} fixture for the {@code POSTTRAN}
     * reader, plus a small, valid {@code TRANSACT.BKUP} (real 350-byte record slices) for the
     * {@code COMBTRAN} prior-backup source. If the combine reader is non-strict the pre-stage is
     * harmless (the redundant copy is simply never read or is overwritten by {@code POSTTRAN}); if it
     * is strict the pre-stage prevents a spurious downstream failure in a bare environment.
     *
     * @throws IOException if the {@code dailytran.txt} fixture is located but cannot be read
     */
    private void stagePipelineInputs() throws IOException {
        byte[] daily = requireFixtureBytes(DAILY_TRAN_KEY); // skips (assumeTrue) if the fixture is absent
        putS3Object(BUCKET_INPUT, DAILY_TRAN_KEY, daily);
        int slices = Math.min(3, daily.length / DAILY_TRAN_RECORD_LENGTH);
        byte[] bkup = Arrays.copyOfRange(daily, 0, slices * DAILY_TRAN_RECORD_LENGTH);
        putS3Object(BUCKET_OUTPUT, TRANSACT_BKUP_KEY, bkup);
        putS3Object(BUCKET_INPUT, TRANSACT_BKUP_KEY, bkup);
    }

    /**
     * Launches the master pipeline through the inherited {@link JobLauncher} with the master-level
     * job parameters every stage needs: {@code inputLocation} ({@code POSTTRAN} reader),
     * {@code parmDate} ({@code INTCALC}), and {@code startDate}/{@code endDate} ({@code TRANREPT}).
     * The inherited {@code baseParams()} seeds a unique {@code run.id} so each launch is a distinct
     * master {@code JobInstance}.
     *
     * @return the master {@link JobExecution}
     * @throws Exception if the launcher cannot start the pipeline
     */
    private JobExecution launchPipeline() throws Exception {
        return jobLauncher.run(cardDemoBatchPipelineJob, baseParams()
                .addString("inputLocation", "s3://" + BUCKET_INPUT + "/" + DAILY_TRAN_KEY)
                .addString("parmDate", PARM_DATE)
                .addString("startDate", START_DATE)
                .addString("endDate", END_DATE)
                .toJobParameters());
    }

    /**
     * Returns the master job's stage executions ordered by start time. Spring Batch 5
     * {@code StepExecution.getStartTime()} returns a {@link LocalDateTime}, which is {@link Comparable}.
     *
     * @param execution the master job execution
     * @return the stage executions sorted ascending by start time
     */
    private List<StepExecution> stepsByStart(JobExecution execution) {
        return execution.getStepExecutions().stream()
                .sorted(Comparator.comparing(StepExecution::getStartTime))
                .toList();
    }

    /**
     * Returns the first non-{@code null} start time produced by applying {@code startOf} to the given
     * name tokens in order, or {@code null} when no token matches a stage step.
     *
     * @param startOf maps a lowercase step-name token to the first matching step's start time
     * @param tokens  the candidate tokens, tried in order
     * @return the first non-{@code null} start time, or {@code null} if none matched
     */
    private static LocalDateTime firstNonNull(Function<String, LocalDateTime> startOf, String... tokens) {
        for (String token : tokens) {
            LocalDateTime start = startOf.apply(token);
            if (start != null) {
                return start;
            }
        }
        return null;
    }

    /**
     * Soft-writes a per-stage summary of the pipeline run to {@code target/pipeline-summary.txt} as
     * Gate 1 / Gate 7 evidence. Best-effort: any {@link IOException} is swallowed so an evidence-write
     * problem never fails the test.
     *
     * @param execution the completed master job execution
     */
    private void writePipelineSummary(JobExecution execution) {
        try {
            Path targetDir = Path.of("target");
            Files.createDirectories(targetDir);
            StringBuilder summary = new StringBuilder(512);
            summary.append("cardDemoBatchPipelineJob status=").append(execution.getStatus())
                    .append(" exit=").append(execution.getExitStatus().getExitCode())
                    .append(System.lineSeparator());
            for (StepExecution step : stepsByStart(execution)) {
                summary.append("  step=").append(step.getStepName())
                        .append(" status=").append(step.getStatus())
                        .append(" exit=").append(step.getExitStatus().getExitCode())
                        .append(System.lineSeparator());
            }
            Files.writeString(targetDir.resolve(SUMMARY_FILE), summary.toString());
        } catch (IOException ignored) {
            // Best-effort Gate-1/Gate-7 evidence only; never fail the test on an evidence-write IO error.
        }
    }

    /**
     * Test 1 &mdash; the whole pipeline runs to {@code COMPLETED} with no {@code FAILED} stage and all
     * five stage executions present (predecessor-success ordering held: had {@code POSTTRAN} failed and
     * the decider {@code .end()}-ed, the downstream {@code JobStep}s would be absent).
     *
     * @throws Exception if the fixture cannot be read or the launch fails
     */
    @Test
    @Order(1)
    void wholePipelineCompletesWithNoFailedStage() throws Exception {
        stagePipelineInputs();

        JobExecution execution = launchPipeline();

        boolean anyFailed = execution.getStepExecutions().stream()
                .anyMatch(step -> step.getStatus() == BatchStatus.FAILED);
        assertThat(anyFailed).as("no pipeline stage may FAIL").isFalse();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(execution.getStepExecutions().size())
                .as("all %d pipeline stages ran (success chain advanced through the split)", PIPELINE_STAGE_COUNT)
                .isGreaterThanOrEqualTo(PIPELINE_STAGE_COUNT);

        writePipelineSummary(execution);
    }

    /**
     * Test 2 &mdash; the stages run in sequential order {@code POSTTRAN -> INTCALC -> COMBTRAN}. The
     * explicit start-time ordering is asserted best-effort by lowercase step-name token; if the tokens
     * do not match the real step names the ordering assertion is skipped while the stage-count and
     * all-{@code COMPLETED} proof still stands.
     *
     * @throws Exception if the fixture cannot be read or the launch fails
     */
    @Test
    @Order(2)
    void stagesRunInSequentialOrder() throws Exception {
        stagePipelineInputs();
        JobExecution execution = launchPipeline();

        List<StepExecution> steps = stepsByStart(execution);
        assertThat(steps.size()).isGreaterThanOrEqualTo(PIPELINE_STAGE_COUNT);

        boolean anyFailed = steps.stream().anyMatch(step -> step.getStatus() == BatchStatus.FAILED);
        assertThat(anyFailed).as("no pipeline stage may FAIL").isFalse();

        steps.forEach(step -> assertThat(step.getStatus()).isEqualTo(BatchStatus.COMPLETED));

        Function<String, LocalDateTime> startOf = token ->
                steps.stream()
                        .filter(step -> step.getStepName().toLowerCase(Locale.ROOT).contains(token))
                        .map(StepExecution::getStartTime)
                        .findFirst()
                        .orElse(null);

        LocalDateTime posting = firstNonNull(startOf, "post");
        LocalDateTime interest = firstNonNull(startOf, "interest", "intcalc");
        LocalDateTime combine = firstNonNull(startOf, "combine", "combtran");

        // Hard assertion (not an assumption): the orchestrator's stage step names are compile-time
        // constants (pipelinePostingJobStep / pipelineInterestJobStep / pipelineCombineJobStep), so the
        // "post" / "interest" / "combine" tokens always resolve a start time after a COMPLETED run. A
        // soft skip here could mask a step-naming or stage-wiring regression.
        assertThat(posting).as("POSTTRAN stage step start time must be resolvable").isNotNull();
        assertThat(interest).as("INTCALC stage step start time must be resolvable").isNotNull();
        assertThat(combine).as("COMBTRAN stage step start time must be resolvable").isNotNull();

        assertThat(posting).as("POSTTRAN starts no later than INTCALC").isBeforeOrEqualTo(interest);
        assertThat(interest).as("INTCALC starts no later than COMBTRAN").isBeforeOrEqualTo(combine);
    }

    /**
     * Test 3 &mdash; the decider lets the pipeline continue on {@code COMPLETED_WITH_REJECTS}. The real
     * {@code dailytran.txt} produces rejected records, so {@code POSTTRAN} exits {@code RC=4}; the JCL
     * {@code COND} re-platforming requires that partial failure NOT halt the pipeline (the
     * {@code FAILED -> .end()} branch must not be taken).
     *
     * @throws Exception if the fixture cannot be read or the launch fails
     */
    @Test
    @Order(3)
    void deciderContinuesOnCompletedWithRejects() throws Exception {
        stagePipelineInputs();
        JobExecution execution = launchPipeline();

        long rejects = countRejectRecords(); // inherited: reads the exact DALYREJS key, asserts 430-alignment
        boolean rejectsExit = execution.getStepExecutions().stream()
                .anyMatch(step -> step.getExitStatus().getExitCode().contains("COMPLETED_WITH_REJECTS"));

        // Guarded so the test stays correct even if a run yields zero rejects; with the CardDemo
        // dailytran.txt the reject path is the expected, exercised case.
        if (rejects > 0 || rejectsExit) {
            assertThat(execution.getStepExecutions().size())
                    .as("COMPLETED_WITH_REJECTS must NOT stop the pipeline")
                    .isGreaterThanOrEqualTo(PIPELINE_STAGE_COUNT);
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        boolean anyFailed = execution.getStepExecutions().stream()
                .anyMatch(step -> step.getStatus() == BatchStatus.FAILED);
        assertThat(anyFailed).as("the decider FAILED -> .end() branch must not be taken").isFalse();
    }

    /**
     * Test 4 &mdash; data effects plus parallel-stage (4a/4b) evidence. {@code POSTTRAN} posts valid
     * transactions and {@code COMBTRAN} bulk-merges the combined (posted + interest) records into the
     * {@code transaction} master, and the {@code FlowBuilder.split(...)} fans out to both terminal
     * branches.
     *
     * <p>Because the database is a shared singleton and the pipeline is idempotent (the combine load
     * upserts on the {@code tranId} primary key, so a repeated identical run adds zero net rows), the
     * data effect is asserted robustly: the master never loses rows ({@code count >= preCount}) and is
     * populated by the pipeline ({@code count > 0}; the {@code transaction} table is seeded empty and
     * only the pipeline ever writes it). Parallel execution is asserted structurally (both terminal
     * stages present and {@code COMPLETED}); wall-clock overlap is intentionally NOT asserted because a
     * synchronous test executor may serialize the split.</p>
     *
     * @throws Exception if the fixture cannot be read or the launch fails
     */
    @Test
    @Order(4)
    void dataEffectsAndParallelTerminalStages() throws Exception {
        long preCount = transactionRepository.count();

        stagePipelineInputs();
        JobExecution execution = launchPipeline();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        long postCount = transactionRepository.count();
        assertThat(postCount)
                .as("the idempotent bulk-merge (COMBTRAN REPRO-equivalent) never loses master rows")
                .isGreaterThanOrEqualTo(preCount);
        assertThat(postCount)
                .as("the pipeline populated the transaction master (seeded empty; POSTTRAN posts + COMBTRAN merges)")
                .isGreaterThan(0L);

        List<StepExecution> steps = stepsByStart(execution);
        assertThat(steps.size()).isGreaterThanOrEqualTo(PIPELINE_STAGE_COUNT);
        steps.forEach(step -> assertThat(step.getStatus()).isEqualTo(BatchStatus.COMPLETED));

        boolean hasStatement = steps.stream()
                .anyMatch(step -> step.getStepName().toLowerCase(Locale.ROOT).contains("statement"));
        boolean hasReport = steps.stream()
                .anyMatch(step -> step.getStepName().toLowerCase(Locale.ROOT).contains("report"));
        assertThat(hasStatement && hasReport)
                .as("the stage-4 split fanned out to BOTH branches (4a statement || 4b report)")
                .isTrue();
    }

    /**
     * Test 5 &mdash; the programmatic entry point {@code runPipeline(...)} executes the same master
     * pipeline. The orchestrator bean is a required injection and its presence is asserted (a missing
     * bean is a wiring regression, not a skip condition). The programmatic entry builds its own
     * {@code JobParameters} (including a unique run id) and relies on the {@code POSTTRAN} reader's
     * default {@code inputLocation} ({@code s3://carddemo-batch-input/dailytran.txt}), which
     * {@link #stagePipelineInputs()} satisfies.
     *
     * @throws Exception if the fixture cannot be read or the launch fails
     */
    @Test
    @Order(5)
    void programmaticRunPipelineEntryExecutesSamePipeline() throws Exception {
        // Hard assertion (not an assumption): the orchestrator is a required bean; a missing injection
        // is a wiring regression that must FAIL rather than skip the programmatic-entry verification.
        assertThat(orchestrator)
                .as("BatchPipelineOrchestrator bean must be injectable for the programmatic-entry test")
                .isNotNull();

        stagePipelineInputs();
        long preCount = transactionRepository.count();

        JobExecution execution = orchestrator.runPipeline(PARM_DATE, START_DATE, END_DATE);

        assertThat(execution.getStatus())
                .as("the programmatic entry executes the full pipeline to COMPLETED")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(transactionRepository.count())
                .as("the master transaction table is consistent (idempotent re-run) after the programmatic launch")
                .isGreaterThanOrEqualTo(preCount);
    }

    /**
     * Test 6 &mdash; observability context propagation across the parallel stage-4 split. The pipeline
     * is launched with a known {@code correlationId} in the launching thread's MDC; the stage-4 split
     * runs stage&nbsp;4a (CREASTMT statement generation) and stage&nbsp;4b (TRANREPT transaction
     * reporting) on the {@code batchTaskExecutor}'s {@code carddemo-batch-*} worker threads. A Logback
     * {@link ListAppender} proves the context survives the thread boundary: <strong>every</strong> log
     * event emitted on a {@code carddemo-batch-*} thread carries the launching thread's
     * {@code correlationId}, and <strong>both</strong> terminal branch jobs
     * ({@code statementGenerationJob} and {@code transactionReportJob}) logged from those worker
     * threads &mdash; i.e. both branches retained the correlation id.
     *
     * <p>This is the regression guard for the {@code BatchContextPropagatingTaskDecorator} wired onto
     * the split executor by {@code com.carddemo.config.BatchConfig} (Observability rule, AAP
     * &sect;0.7.1). Before that decorator, branch steps logged without a correlation id and began
     * unrelated traces.</p>
     *
     * @throws Exception if the fixture cannot be read or the launch fails
     */
    @Test
    @Order(6)
    void parallelSplitWorkerThreadsRetainCorrelationId() throws Exception {
        final String correlationId = "PIPELINE-IT-CORRID-" + System.nanoTime();

        // Capture org.springframework.batch INFO events (job/step lifecycle) that the split branches
        // emit on the carddemo-batch-* worker threads; each event snapshots the thread's MDC at log time.
        Logger batchLogger = (Logger) LoggerFactory.getLogger("org.springframework.batch");
        Level priorLevel = batchLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        batchLogger.addAppender(appender);
        batchLogger.setLevel(Level.INFO);

        JobExecution execution;
        try {
            stagePipelineInputs();
            MDC.put("correlationId", correlationId); // established on the launching thread
            execution = launchPipeline();
        } finally {
            MDC.remove("correlationId");
            batchLogger.detachAppender(appender);
            batchLogger.setLevel(priorLevel);
            appender.stop();
        }

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Snapshot the captured events after the run (the synchronous master launcher has already
        // joined the split, so no further worker-thread appends occur).
        List<ILoggingEvent> workerEvents = new ArrayList<>(appender.list).stream()
                .filter(event -> event.getThreadName() != null
                        && event.getThreadName().startsWith("carddemo-batch-"))
                .toList();

        assertThat(workerEvents)
                .as("the stage-4 split executed its branches on carddemo-batch-* worker threads")
                .isNotEmpty();

        assertThat(workerEvents)
                .as("every stage-4 worker-thread log event retains the launching thread's correlationId")
                .allSatisfy(event -> assertThat(event.getMDCPropertyMap().get("correlationId"))
                        .isEqualTo(correlationId));

        String workerMessages = workerEvents.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining(System.lineSeparator()));
        assertThat(workerMessages)
                .as("stage 4a (statement) branch ran on a worker thread that retained the context")
                .contains(StatementGenerationJob.JOB_NAME);
        assertThat(workerMessages)
                .as("stage 4b (report) branch ran on a worker thread that retained the context")
                .contains(TransactionReportJob.JOB_NAME);
    }
}
