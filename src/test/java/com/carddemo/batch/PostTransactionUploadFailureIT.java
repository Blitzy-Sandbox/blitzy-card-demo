package com.carddemo.batch;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.carddemo.observability.CorrelationIdFilter;
import com.carddemo.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for QA finding <strong>F4</strong> (MAJOR) &mdash; the <em>systemic</em> half of the
 * finding, exercised against the highest-blast-radius writer: the {@code POSTTRAN} reject writer.
 *
 * <p>The QA report calls this scenario out explicitly: <em>"Under an S3 outage, POSTTRAN could silently
 * lose all&nbsp;38 reject records (a Gate&nbsp;1 byte-equivalence artifact) yet report
 * {@code COMPLETED}."</em> The pre-fix {@link PostTransactionItemWriter} performed its terminal
 * {@code DALYREJS} upload inside {@code ItemStream.close()}, whose exception {@code AbstractStep}
 * swallows <em>after</em> it has already persisted the step/job as {@code COMPLETED} &mdash; so a failed
 * reject upload surfaced as a false success and the 38 reject records vanished silently. Decision
 * {@code D-027} moved the flush+upload into
 * {@link org.springframework.batch.core.StepExecutionListener#afterStep(StepExecution)} (which runs
 * <em>before</em> the status is persisted), where an upload failure now sets {@code BatchStatus.FAILED},
 * records the exception, and returns {@link org.springframework.batch.core.ExitStatus#FAILED}.
 *
 * <p>This test drives the <em>real</em> {@code postTransactionJob} against real PostgreSQL&nbsp;16 and
 * real LocalStack S3 (zero live AWS), launching it directly through the {@link JobLauncher} so the
 * terminal {@link JobExecution} / {@link StepExecution} statuses can be asserted deterministically. The
 * failure is injected by removing the reject writer's target bucket ({@link #BUCKET_OUTPUT}) before the
 * run: the reader and processor are database-backed and untouched, so the chunk phase runs to completion
 * (all {@value #EXPECTED_DAILY_COUNT} input rows are read) and only the {@code afterStep} upload fails.
 *
 * <h2>What this proves (complements the unit-level {@code PostTransactionItemWriterTest#uploadFailureFailsStep})</h2>
 * <ol>
 *   <li>The {@code @StepScope} writer is genuinely wired as a {@code StepExecutionListener} by the real
 *       framework (its {@code afterStep} runs, driven by {@code CompositeStepExecutionListener}).</li>
 *   <li>A failed reject upload fails both the {@code postTransactionStep} step execution and the job
 *       &mdash; never a false {@code COMPLETED} &mdash; and records the failure exception.</li>
 *   <li>No {@code dalyrejs.dat} object is left behind: the 38 reject records are <em>not</em> silently
 *       lost with a success status, directly rebutting the QA blast-radius scenario.</li>
 * </ol>
 *
 * <p>The successful path and the 38-record byte-equivalence (Gate&nbsp;1) are owned by
 * {@link PostTransactionJobIT}; this test is deliberately failure-only to avoid duplicating that proof.</p>
 *
 * <h2>COBOL/JCL lineage (reference-only, source commit SHA {@code 27d6c6f}; NOT copied)</h2>
 * <p>In {@code CBTRN02C}/{@code POSTTRAN.jcl} a write/close I/O failure on the {@code DALYREJS} dataset
 * abended the job step (a non-zero JCL condition code) and the pipeline stopped; it never reported
 * success on a failed reject write. Preserving that abend-on-write-failure semantics is exactly what
 * {@code D-027} restores in the Spring Batch translation.</p>
 *
 * @see PostTransactionItemWriter
 * @see PostTransactionJob
 * @see PostTransactionJobIT
 * @see AbstractBatchIntegrationTest
 */
@DisplayName("F4 IT — postTransactionJob fails (not false COMPLETED) when the DALYREJS S3 upload fails")
class PostTransactionUploadFailureIT extends AbstractBatchIntegrationTest {

    /** S3 object key of the reject file (writer default {@code carddemo.batch.reject.object-key}). */
    private static final String REJECT_OBJECT_KEY = "dalyrejs.dat";

    /** Bean name of the single launched step whose status this test asserts. */
    private static final String POST_STEP_NAME = "postTransactionStep";

    /**
     * Exact number of {@code daily_transaction} input rows seeded by {@code V3__seed_data.sql} (the
     * {@code dailytran.txt} fixture). Asserting the step read all of them proves the chunk phase ran to
     * completion, so the observed failure is unambiguously the {@code afterStep} upload rather than a
     * mid-chunk error; it also doubles as an input-integrity guard.
     */
    private static final long EXPECTED_DAILY_COUNT = 300L;

    /** The synchronous, auto-configured Spring Batch launcher used to run the posting job directly. */
    @Autowired
    private JobLauncher jobLauncher;

    /** The real posting job under test (by bean name, per {@code PostTransactionJob}). */
    @Autowired
    @Qualifier("postTransactionJob")
    private Job postTransactionJob;

    /** Used only to establish/restore the empty {@code transaction}-master baseline (V3 seeds none). */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * Establishes the deterministic pre-state: the {@code transaction} master starts EMPTY (V3 seeds no
     * rows into it) so the chunk phase posts cleanly and the only possible terminal failure is the
     * {@code afterStep} reject upload. The {@code daily_transaction} input is left intact (V3-seeded).
     */
    @BeforeEach
    void emptyTransactionMaster() {
        transactionRepository.deleteAll();
    }

    /**
     * Restores the empty {@code transaction}-master baseline (the failing run still commits its posted
     * chunks before {@code afterStep} fails) and removes the output bucket, so no state leaks to sibling
     * integration tests sharing the singleton containers (AAP&nbsp;&sect;0.7.7). Both are idempotent.
     */
    @AfterEach
    void teardown() {
        transactionRepository.deleteAll();
        deleteBucketRecursively(BUCKET_OUTPUT);
    }

    /**
     * With the reject writer's target bucket absent, launching the posting job must end {@code FAILED}
     * at both the step and job level, record a failure exception, and leave no {@code dalyrejs.dat} &mdash;
     * the exact opposite of the pre-fix behaviour where the reject upload failure was swallowed in
     * {@code close()} and the job falsely reported {@code COMPLETED} (silently losing all 38 rejects).
     */
    @Test
    @DisplayName("Output bucket absent → postTransactionStep and job end FAILED; no dalyrejs.dat written")
    void rejectUploadFailureFailsStepAndJob() throws Exception {
        // Inject the failure: guarantee the reject writer's target bucket does not exist at afterStep upload.
        deleteBucketRecursively(BUCKET_OUTPUT);

        final JobExecution execution = jobLauncher.run(postTransactionJob, newRunParameters());

        // (1) The job did NOT falsely complete — it failed (F4 core assertion).
        assertThat(execution.getStatus())
                .as("job must end FAILED when the DALYREJS S3 upload fails (F4: no false COMPLETED)")
                .isEqualTo(BatchStatus.FAILED);

        // The standalone posting job has exactly one step (postTransactionStep).
        final StepExecution postStep = postStepExecution(execution);

        // (2) The chunk phase ran to completion — all input was read — so the failure is unambiguously the
        //     afterStep upload, not a mid-chunk error (a mid-chunk failure would stop short of 300 reads).
        assertThat(postStep.getReadCount())
                .as("the chunk phase must read all %d daily_transaction rows before the afterStep upload fails",
                        EXPECTED_DAILY_COUNT)
                .isEqualTo(EXPECTED_DAILY_COUNT);

        // (3) The step itself is FAILED (the failure was surfaced at the step boundary).
        assertThat(postStep.getStatus())
                .as("postTransactionStep must end FAILED when its afterStep reject upload fails")
                .isEqualTo(BatchStatus.FAILED);

        // (4) A concrete failure exception was recorded (not merely a status flip).
        assertThat(postStep.getFailureExceptions())
                .as("the DALYREJS S3 upload failure must be recorded on the step execution")
                .isNotEmpty();

        // (5) No reject artifact was written: the 38 reject records are NOT silently persisted with a
        //     success status (a missing bucket yields a 404, i.e. object-absent). This is the blast-radius rebuttal.
        assertThat(objectExists(BUCKET_OUTPUT, REJECT_OBJECT_KEY))
                .as("no dalyrejs.dat may be written when the reject upload fails")
                .isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a fresh set of identifying job parameters (unique {@code jobId} + {@code run.id}) so each
     * launch is a distinct {@code JobInstance} — never rejected as an already-complete duplicate — and
     * carries a correlation id for MDC-propagated structured logging.
     *
     * @return the job parameters for one launch
     */
    private static JobParameters newRunParameters() {
        return new JobParametersBuilder()
                .addString(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, "it-post-fail-" + UUID.randomUUID())
                .addString("jobId", UUID.randomUUID().toString())
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
    }

    /**
     * Extracts the single {@code postTransactionStep} execution from a job execution.
     *
     * @param execution the finished job execution
     * @return the posting step execution
     * @throws AssertionError if the step execution is not present
     */
    private static StepExecution postStepExecution(final JobExecution execution) {
        return execution.getStepExecutions().stream()
                .filter(se -> POST_STEP_NAME.equals(se.getStepName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "job execution did not contain a '" + POST_STEP_NAME + "' step execution"));
    }
}
