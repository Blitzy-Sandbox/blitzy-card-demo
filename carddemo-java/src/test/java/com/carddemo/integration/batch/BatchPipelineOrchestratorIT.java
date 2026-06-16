package com.carddemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.jobs.BatchPipelineOrchestrator;
import com.carddemo.repository.TransactionRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * End-to-end integration test for the Spring Batch <em>master pipeline orchestrator</em>
 * (bean {@code cardDemoBatchPipelineJob}, assembled by {@link BatchPipelineOrchestrator}), the Java
 * re-host of the mainframe CardDemo batch <em>job stream</em> (source commit {@code 27d6c6f};
 * REFERENCE ONLY &mdash; no COBOL or JCL is copied into the Java target). The reference job stream is
 * the five-stage chain:
 *
 * <pre>
 *   POSTTRAN ({@code app/jcl/POSTTRAN.jcl}, PGM=CBTRN02C)  &rarr; stage 1  daily transaction posting
 *   INTCALC  ({@code app/jcl/INTCALC.jcl},  PGM=CBACT04C)  &rarr; stage 2  interest calculation
 *   COMBTRAN ({@code app/jcl/COMBTRAN.jcl}, PGM=SORT)      &rarr; stage 3  combine transactions
 *   CREASTMT ({@code app/jcl/CREASTMT.JCL})                &rarr; stage 4a statement generation  } run
 *   TRANREPT ({@code app/jcl/TRANREPT.jcl})                &rarr; stage 4b transaction report    } concurrently
 * </pre>
 *
 * <p>The suite proves behavioral parity with the JCL condition-code control flow and supports two
 * validation gates:</p>
 * <ul>
 *   <li><b>Gate 1 (end-to-end)</b> &mdash; the real {@code app/data/ASCII/dailytran.txt} fixture is
 *       staged to S3 and driven end-to-end through the master pipeline into PostgreSQL and S3.</li>
 *   <li><b>Gate 7 (extended, multi-subsystem scope)</b> &mdash; the run exercises the full
 *       five-stage pipeline (batch + file I/O + S3 + database) as one orchestrated unit.</li>
 * </ul>
 *
 * <p>It validates: sequential <b>predecessor-success ordering</b> (POSTTRAN &rarr; INTCALC &rarr;
 * COMBTRAN); the {@code JobExecutionDecider} <b>condition-code logic</b> ({@code COMPLETED} or
 * {@code COMPLETED_WITH_REJECTS} continue, {@code FAILED} stops &mdash; the JCL {@code COND}
 * re-platforming, where a partial-failure reject from POSTTRAN does not halt the pipeline); and the
 * <b>parallel stages 4a/4b</b> (statement alongside report) produced by the stage-4
 * {@code FlowBuilder.split(...)}.</p>
 *
 * <p>The class {@code extends AbstractBatchIntegrationTest} and reuses that base class's entire
 * scaffolding (singleton Testcontainers PostgreSQL + LocalStack, dynamic property wiring, AWS
 * resource self-provisioning, per-test S3 cleanup, the fixture locator, the S3 helpers, the reject
 * counter, and the {@code JobLauncher}). No container, dynamic-property, or
 * {@code @SpringBootTest}/{@code @ActiveProfiles}/{@code @Testcontainers}/{@code @Tag} annotation is
 * re-declared here; all are inherited. When Docker is unavailable the inherited
 * {@code @Testcontainers(disabledWithoutDocker = true)} condition skips the whole class cleanly, and
 * when the source-tree fixture is unreachable each test self-skips via the inherited
 * {@code requireFixtureBytes(...)} assumption.</p>
 *
 * <p><b>Shared-state robustness.</b> The base class shares one PostgreSQL database, one LocalStack
 * instance, and one cached Spring context across every batch IT, and it empties only S3 (never the
 * database) before each test. Posting persists transactions with {@code save()} keyed on the
 * {@code TRAN-ID} taken verbatim from the input record, and the combine stage bulk-loads by the same
 * id, so re-running the pipeline over the same fixture is idempotent (no net-new rows on a later
 * run). Assertions therefore never depend on an absolute pristine-database count nor on a strictly
 * positive row delta: data-effect checks prove the transaction table is <em>populated</em> and that
 * a run loses no rows, and every launch uses the inherited {@code baseParams()} (a unique
 * {@code run.id}) so each master launch is a distinct {@code JobInstance}.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BatchPipelineOrchestratorIT extends AbstractBatchIntegrationTest {

    /** Ten-character interest run date (COBOL {@code PARM='2022071800'}: {@code yyyyMMdd} + run hour). */
    private static final String PARM_DATE = "2022071800";

    /** Inclusive transaction-report window start forwarded to TRANREPT ({@code startDate}). */
    private static final String START_DATE = "2022-07-01";

    /** Inclusive transaction-report window end forwarded to TRANREPT ({@code endDate}). */
    private static final String END_DATE = "2022-07-31";

    /** Exact S3 key of the prior-day transaction backup that COMBTRAN sorts together with SYSTRAN. */
    private static final String BACKUP_OBJECT_KEY = "TRANSACT.BKUP";

    /** Exact S3 key of the system-generated transactions COMBTRAN sorts together with TRANSACT.BKUP. */
    private static final String SYSTRAN_OBJECT_KEY = "SYSTRAN";

    /** Number of pipeline stages that must have a {@code StepExecution} when the success-chain holds. */
    private static final int EXPECTED_STAGE_COUNT = 5;

    /** The master pipeline job under test, launched explicitly ({@code spring.batch.job.enabled=false}). */
    @Autowired
    @Qualifier("cardDemoBatchPipelineJob")
    private Job cardDemoBatchPipelineJob;

    /** Repository for the transaction master table (re-platform of the {@code TRANSACT} KSDS). */
    @Autowired
    private TransactionRepository transactionRepository;

    /** Orchestrator bean, used by the optional programmatic-entry test; defensively optional. */
    @Autowired(required = false)
    private BatchPipelineOrchestrator orchestrator;

    /**
     * Test 1 (Gate 1, happy path) &mdash; launches the whole master pipeline over the real
     * {@code dailytran.txt} fixture and proves the success-chain advanced through every stage: no
     * stage {@code FAILED}, the master batch status is {@code COMPLETED}, and at least the five
     * pipeline stages produced {@code StepExecution}s. Had POSTTRAN failed and the decider taken its
     * {@code .end()} branch, the downstream {@code JobStep}s would be absent, so the presence of the
     * full stage set is the structural proof that predecessor-success ordering held through the
     * stage-4 split.
     *
     * @throws Exception if the fixture cannot be read or the job launcher rejects the run
     */
    @Test
    @Order(1)
    void wholePipelineCompletesWithNoFailedStage() throws Exception {
        stagePipelineInputs();
        JobExecution execution = launchPipeline();

        boolean anyFailed = execution.getStepExecutions().stream()
                .anyMatch(se -> se.getStatus() == BatchStatus.FAILED);
        assertThat(anyFailed).as("no pipeline stage may FAIL").isFalse();

        assertThat(execution.getStatus())
                .as("the master pipeline completes when every staged input is present")
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(execution.getStepExecutions().size())
                .as("the success-chain advanced through all %d pipeline stages", EXPECTED_STAGE_COUNT)
                .isGreaterThanOrEqualTo(EXPECTED_STAGE_COUNT);

        writePipelineSummary(execution);
    }

    /**
     * Test 2 (sequential ordering) &mdash; proves the predecessor-success ordering of the first three
     * stages, POSTTRAN &rarr; INTCALC &rarr; COMBTRAN. Every stage execution must be {@code COMPLETED}
     * and none {@code FAILED}; the explicit start-time ordering is then asserted by matching the stage
     * steps by a lowercased name token. Because the production step names
     * ({@code dailyPostingJobStep}, {@code interestJobStep}, {@code combineJobStep}) carry those
     * tokens the assertion runs; were the names ever to change so the tokens no longer match, the
     * assumption skips the explicit-ordering check while the all-{@code COMPLETED} and stage-count
     * proofs still stand.
     *
     * @throws Exception if the fixture cannot be read or the job launcher rejects the run
     */
    @Test
    @Order(2)
    void stagesRunInPredecessorSuccessOrder() throws Exception {
        stagePipelineInputs();
        JobExecution execution = launchPipeline();

        List<StepExecution> steps = stepsByStart(execution);
        assertThat(steps.size())
                .as("the success-chain advanced through all %d pipeline stages", EXPECTED_STAGE_COUNT)
                .isGreaterThanOrEqualTo(EXPECTED_STAGE_COUNT);

        boolean anyFailed = steps.stream().anyMatch(se -> se.getStatus() == BatchStatus.FAILED);
        assertThat(anyFailed).as("no pipeline stage may FAIL").isFalse();

        steps.forEach(se -> assertThat(se.getStatus())
                .as("stage %s must complete", se.getStepName())
                .isEqualTo(BatchStatus.COMPLETED));

        Function<String, LocalDateTime> startOf = token ->
                steps.stream()
                        .filter(se -> se.getStepName().toLowerCase(Locale.ROOT).contains(token))
                        .map(StepExecution::getStartTime)
                        .findFirst()
                        .orElse(null);
        LocalDateTime post = firstNonNull(startOf, "post");
        LocalDateTime interest = firstNonNull(startOf, "interest", "intcalc");
        LocalDateTime combine = firstNonNull(startOf, "combine", "combtran");
        Assumptions.assumeTrue(post != null && interest != null && combine != null,
                "Stage step names not recognizable by token - relying on stage-count ordering proof");

        assertThat(post).as("POSTTRAN starts no later than INTCALC").isBeforeOrEqualTo(interest);
        assertThat(interest).as("INTCALC starts no later than COMBTRAN").isBeforeOrEqualTo(combine);
    }

    /**
     * Test 3 (decider continue-on-rejects parity) &mdash; the real {@code dailytran.txt} produces
     * rejected records, so POSTTRAN exits {@code COMPLETED_WITH_REJECTS} (the JCL {@code RC=4}
     * warning). The {@code JobExecutionDecider} must treat that warning like {@code COMPLETED} and let
     * the pipeline continue rather than taking its {@code FAILED} &rarr; {@code .end()} branch. The
     * continue-parity assertion is guarded by whether this run actually produced rejects, so the test
     * stays correct even on a fixture run that yields none; with the CardDemo fixture the reject path
     * is the expected, exercised case.
     *
     * @throws Exception if the fixture cannot be read or the job launcher rejects the run
     */
    @Test
    @Order(3)
    void deciderContinuesOnCompletedWithRejects() throws Exception {
        stagePipelineInputs();
        JobExecution execution = launchPipeline();

        long rejects = countRejectRecords();
        boolean rejectsExit = execution.getStepExecutions().stream()
                .anyMatch(se -> se.getExitStatus().getExitCode().contains("COMPLETED_WITH_REJECTS"));

        if (rejects > 0 || rejectsExit) {
            assertThat(execution.getStepExecutions().size())
                    .as("COMPLETED_WITH_REJECTS must NOT stop the pipeline")
                    .isGreaterThanOrEqualTo(EXPECTED_STAGE_COUNT);
            assertThat(execution.getStatus())
                    .as("the pipeline still completes after a rejects warning from POSTTRAN")
                    .isEqualTo(BatchStatus.COMPLETED);
        }

        boolean anyFailed = execution.getStepExecutions().stream()
                .anyMatch(se -> se.getStatus() == BatchStatus.FAILED);
        assertThat(anyFailed)
                .as("the decider's FAILED -> .end() branch must NOT be taken on a rejects warning")
                .isFalse();
    }

    /**
     * Test 4 (data effects + parallel 4a/4b evidence) &mdash; proves the database side effects and the
     * stage-4 fan-out. POSTTRAN posts valid transactions and COMBTRAN bulk-loads the staged records,
     * so the transaction table is populated; because posting and the combine load are idempotent on
     * the shared singleton database, the robust invariants are that the table is non-empty and that a
     * run removes no rows (rather than a strictly positive delta, which a repeated fixture run cannot
     * guarantee). Parallel execution of the two terminal stages is asserted structurally &mdash; both
     * branches ran and completed &mdash; not by wall-clock overlap, which a synchronous test executor
     * may serialize and which is therefore timing-nondeterministic.
     *
     * @throws Exception if the fixture cannot be read or the job launcher rejects the run
     */
    @Test
    @Order(4)
    void pipelinePopulatesTransactionsAndBothTerminalStagesRun() throws Exception {
        long preCount = transactionRepository.count();

        stagePipelineInputs();
        JobExecution execution = launchPipeline();

        assertThat(execution.getStatus())
                .as("the master pipeline completes when every staged input is present")
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(transactionRepository.count())
                .as("POSTTRAN posts valid transactions and COMBTRAN bulk-loads records into TRANSACT")
                .isGreaterThan(0L);
        assertThat(transactionRepository.count())
                .as("a pipeline run never removes posted transactions from the shared database")
                .isGreaterThanOrEqualTo(preCount);

        List<StepExecution> steps = stepsByStart(execution);
        assertThat(steps.size())
                .as("the stage-4 split fanned out to both terminal branches")
                .isGreaterThanOrEqualTo(EXPECTED_STAGE_COUNT);
        steps.forEach(se -> assertThat(se.getStatus())
                .as("stage %s must complete", se.getStepName())
                .isEqualTo(BatchStatus.COMPLETED));

        boolean statementRan = steps.stream()
                .anyMatch(se -> se.getStepName().toLowerCase(Locale.ROOT).contains("statement"));
        boolean reportRan = steps.stream()
                .anyMatch(se -> se.getStepName().toLowerCase(Locale.ROOT).contains("report"));
        if (statementRan || reportRan) {
            assertThat(statementRan).as("stage 4a (statement) branch present in the split").isTrue();
            assertThat(reportRan).as("stage 4b (report) branch present in the split").isTrue();
        }
    }

    /**
     * Test 5 (programmatic entry, optional) &mdash; exercises {@link BatchPipelineOrchestrator}'s
     * programmatic {@code runPipeline(...)} entry point, which builds its own {@code JobParameters}
     * ({@code parmDate}, {@code startDate}, {@code endDate}, and a unique {@code run.id}) and relies on
     * the POSTTRAN reader's default input location {@code s3://carddemo-batch-input/dailytran.txt}
     * (satisfied by {@link #stagePipelineInputs()}). The verified signature accepts
     * {@link LocalDate} arguments, so the string constants are converted accordingly. The test is
     * guarded by an assumption so it self-skips cleanly if the orchestrator bean is not injectable,
     * and its assertions stay tolerant: the goal is to prove the programmatic entry executes the same
     * pipeline (completing, and leaving the transaction table populated and intact) without throwing.
     *
     * @throws Exception if the fixture cannot be read or the launcher rejects the programmatic run
     */
    @Test
    @Order(5)
    void programmaticRunPipelineEntryExecutesSamePipeline() throws Exception {
        Assumptions.assumeTrue(orchestrator != null,
                "BatchPipelineOrchestrator bean not injectable - skipping programmatic-entry test");

        stagePipelineInputs();
        long preCount = transactionRepository.count();

        // runPipeline(LocalDate, LocalDate, LocalDate) is the verified signature; the parmDate's date
        // portion (yyyyMMdd) is recovered from PARM_DATE so the constant remains the single source.
        LocalDate processingDate =
                LocalDate.parse(PARM_DATE.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
        JobExecution execution = orchestrator.runPipeline(
                processingDate, LocalDate.parse(START_DATE), LocalDate.parse(END_DATE));

        assertThat(execution.getStatus())
                .as("the programmatic entry runs the same pipeline to COMPLETED")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(transactionRepository.count())
                .as("the programmatic run leaves TRANSACT populated and loses no rows")
                .isGreaterThanOrEqualTo(preCount);
    }

    // ------------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------------

    /**
     * Stages the master pipeline's inputs into S3. The real {@code dailytran.txt} fixture is uploaded
     * under the input bucket as the POSTTRAN reader source. COMBTRAN (stage&nbsp;3) then reads two
     * objects from the output bucket and parses both as 350-byte {@code CVTRA05Y} transaction-master
     * records ({@code app/jcl/COMBTRAN.jcl}: SORTIN over {@code TRANSACT.BKUP(0)} and {@code SYSTRAN(0)}):
     *
     * <ul>
     *   <li>{@code SYSTRAN} is produced by the INTCALC stage, which runs <em>before</em> COMBTRAN and
     *       overwrites this object with the real interest transactions whenever it stages any; and</li>
     *   <li>{@code TRANSACT.BKUP} (the prior-day backup) is produced by <em>no</em> pipeline stage.</li>
     * </ul>
     *
     * <p>COMBTRAN's reader treats a <em>missing</em> object as a hard error but an <em>empty</em> object
     * as zero records (parse-safe). Because the base class empties the output bucket before each test
     * and INTCALC writes nothing when there are zero interest rows, this helper pre-stages an
     * <em>empty</em> {@code TRANSACT.BKUP} and an <em>empty</em> {@code SYSTRAN} so COMBTRAN always finds
     * both required inputs in a bare environment. This models a fresh run with no prior-day backup; the
     * empty {@code SYSTRAN} is a safety net that INTCALC overwrites with real records when it has any.
     * The {@code dailytran.txt} fixture is <strong>never</strong> staged as a combine input: it is the
     * {@code CVTRA06Y} daily-staging layout, not {@code CVTRA05Y}, so feeding its bytes to COMBTRAN would
     * misframe the records and raise a parse error. Self-skips the calling test when the fixture is
     * unreachable (via the inherited {@code requireFixtureBytes(...)} assumption).
     *
     * @throws IOException if the located fixture cannot be read
     */
    private void stagePipelineInputs() throws IOException {
        byte[] daily = requireFixtureBytes(DAILY_TRAN_KEY);
        putS3Object(BUCKET_INPUT, DAILY_TRAN_KEY, daily);

        // COMBTRAN requires both objects to EXIST (missing => hard error) but tolerates EMPTY
        // (=> zero records, parse-safe). INTCALC overwrites SYSTRAN with real CVTRA05Y interest
        // records when it stages any; the empty pre-stage keeps COMBTRAN fed otherwise.
        putS3Object(BUCKET_OUTPUT, BACKUP_OBJECT_KEY, new byte[0]);
        putS3Object(BUCKET_OUTPUT, SYSTRAN_OBJECT_KEY, new byte[0]);
    }

    /**
     * Launches the master pipeline through the inherited {@code JobLauncher} with the parameters every
     * stage needs: {@code inputLocation} (POSTTRAN reader), {@code parmDate} (INTCALC), and
     * {@code startDate}/{@code endDate} (TRANREPT). A unique {@code run.id} from {@code baseParams()}
     * makes each launch a distinct {@code JobInstance}. The orchestrator's forwarding
     * {@code JobParametersExtractor} relays these master parameters to each child job.
     *
     * @return the completed master {@link JobExecution} (the launcher is synchronous)
     * @throws Exception if the launcher rejects the run (already running, restart, completed, or
     *                   invalid parameters)
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
     * {@code StepExecution.getStartTime()} returns a {@link LocalDateTime}, which orders the stages
     * along the pipeline timeline.
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
     * Returns the first non-{@code null} start time produced by applying {@code startOf} to the
     * supplied name tokens in order, or {@code null} when none match. Used to locate a stage step by
     * any of its recognised lowercased name tokens.
     *
     * @param startOf resolves a token to the start time of the first matching stage step (or
     *                {@code null})
     * @param tokens  the candidate lowercased name tokens, tried in order
     * @return the first matching start time, or {@code null} if no token matches
     */
    private static LocalDateTime firstNonNull(Function<String, LocalDateTime> startOf, String... tokens) {
        for (String token : tokens) {
            LocalDateTime value = startOf.apply(token);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Writes a best-effort per-stage evidence artifact to {@code target/pipeline-summary.txt} (Gate 1
     * / Gate 7), listing each stage's name, batch status, and exit code. Any {@link IOException} is
     * swallowed so a filesystem hiccup can never fail the test; the artifact is evidence, not an
     * assertion.
     *
     * @param execution the completed master job execution
     */
    private static void writePipelineSummary(JobExecution execution) {
        StringBuilder summary = new StringBuilder();
        summary.append("CardDemo master batch pipeline (cardDemoBatchPipelineJob) per-stage summary\n");
        summary.append("Source baseline (REFERENCE-ONLY, commit 27d6c6f): ")
                .append("POSTTRAN -> INTCALC -> COMBTRAN -> { CREASTMT || TRANREPT }\n");
        summary.append("Master batch status: ").append(execution.getStatus()).append('\n');
        for (StepExecution step : execution.getStepExecutions()) {
            summary.append("  ").append(step.getStepName())
                    .append(" status=").append(step.getStatus())
                    .append(" exit=").append(step.getExitStatus().getExitCode())
                    .append('\n');
        }
        try {
            Path report = Path.of("target", "pipeline-summary.txt");
            Path parent = report.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(report, summary.toString());
        } catch (IOException ioe) {
            // Best-effort evidence artifact only; an IO failure must never fail the test.
        }
    }
}
