package com.carddemo.batch;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for QA finding <strong>F4</strong> (MAJOR) &mdash; the <em>systemic</em> half of the
 * finding, exercised against the statement writer ({@code CREASTMT} / {@code CBSTM03A}).
 *
 * <p>The pre-fix {@link StatementItemWriter} performed its terminal {@code statements.ps} /
 * {@code statements.html} uploads inside {@code ItemStream.close()}, whose exception {@code AbstractStep}
 * swallows <em>after</em> it has already persisted the step/job as {@code COMPLETED} &mdash; so a failed
 * statement upload surfaced as a false success and the statements were silently lost. Decision
 * {@code D-027} moved the serialize+upload into
 * {@link org.springframework.batch.core.StepExecutionListener#afterStep(StepExecution)} (which runs
 * <em>before</em> the status is persisted), where an upload failure now sets {@code BatchStatus.FAILED},
 * records the exception, and returns {@link org.springframework.batch.core.ExitStatus#FAILED}.
 *
 * <p>This test drives the <em>real</em> {@code statementJob} against real PostgreSQL&nbsp;16 and real
 * LocalStack S3 (zero live AWS), launching it directly through the {@link JobLauncher} so the terminal
 * {@link JobExecution} / {@link StepExecution} statuses can be asserted deterministically. The failure is
 * injected by removing the writer's target bucket ({@link #BUCKET_STATEMENTS}) before the run.
 *
 * <h2>Why no seeding or read-count discriminator is required</h2>
 * <p>{@code StatementItemWriter.afterStep} uploads <em>both</em> objects <strong>unconditionally</strong>
 * (exactly as {@code CBSTM03A} always produces both files, even with an empty buffer), and the writer's
 * only S3 interaction happens in {@code afterStep} &mdash; the chunk {@code write()} merely buffers in
 * memory. A missing bucket therefore fails deterministically at {@code afterStep}, independent of how
 * many rows the (V3-seeded, database-backed) reader emits. The job is read-only on the database, so this
 * test mutates no shared DB state; it self-provisions and tears down only its bucket (AAP&nbsp;&sect;0.7.7).</p>
 *
 * <h2>What this proves (complements the unit-level {@code StatementItemWriterTest#uploadFailureFailsStep})</h2>
 * <ol>
 *   <li>The {@code @StepScope} writer is genuinely wired as a {@code StepExecutionListener} by the real
 *       framework (its {@code afterStep} runs, driven by {@code CompositeStepExecutionListener}).</li>
 *   <li>A failed statement upload fails both the {@code statementStep} step execution and the job &mdash;
 *       never a false {@code COMPLETED} &mdash; and records the failure exception.</li>
 *   <li>Neither {@code statements.ps} nor {@code statements.html} is left behind: statements are
 *       <em>not</em> silently lost with a success status.</li>
 * </ol>
 *
 * <p>The successful path and the fixed-width byte-parity of both objects are owned by
 * {@link StatementJobIT}; this test is deliberately failure-only to avoid duplicating that proof.</p>
 *
 * @see StatementItemWriter
 * @see StatementJob
 * @see StatementJobIT
 * @see AbstractBatchIntegrationTest
 */
@DisplayName("F4 IT — statementJob fails (not false COMPLETED) when the statement S3 upload fails")
class StatementUploadFailureIT extends AbstractBatchIntegrationTest {

    /** S3 object key of the plain-text statement (writer default {@code carddemo.batch.statement.text-object-key}). */
    private static final String STATEMENTS_TEXT_KEY = "statements.ps";

    /** S3 object key of the HTML statement (writer default {@code carddemo.batch.statement.html-object-key}). */
    private static final String STATEMENTS_HTML_KEY = "statements.html";

    /** Bean name of the single launched step whose status this test asserts. */
    private static final String STATEMENT_STEP_NAME = "statementStep";

    /** The synchronous, auto-configured Spring Batch launcher used to run the statement job directly. */
    @Autowired
    private JobLauncher jobLauncher;

    /** The real statement job under test (by bean name, per {@code StatementJob}). */
    @Autowired
    @Qualifier("statementJob")
    private Job statementJob;

    /**
     * Removes the statement bucket so no state leaks to sibling integration tests sharing the singleton
     * containers (AAP&nbsp;&sect;0.7.7). Idempotent.
     */
    @AfterEach
    void teardown() {
        deleteBucketRecursively(BUCKET_STATEMENTS);
    }

    /**
     * With the statement writer's target bucket absent, launching the statement job must end
     * {@code FAILED} at both the step and job level, record a failure exception, and leave neither
     * statement object &mdash; the exact opposite of the pre-fix behaviour where the upload failure was
     * swallowed in {@code close()} and the job falsely reported {@code COMPLETED}.
     */
    @Test
    @DisplayName("Statement bucket absent → statementStep and job end FAILED; no statements.ps/.html written")
    void statementUploadFailureFailsStepAndJob() throws Exception {
        // Inject the failure: guarantee the writer's target bucket does not exist at afterStep upload.
        deleteBucketRecursively(BUCKET_STATEMENTS);

        final JobExecution execution = jobLauncher.run(statementJob, newRunParameters());

        // (1) The job did NOT falsely complete — it failed (F4 core assertion).
        assertThat(execution.getStatus())
                .as("job must end FAILED when the statement S3 upload fails (F4: no false COMPLETED)")
                .isEqualTo(BatchStatus.FAILED);

        // (2) The statement step itself is FAILED (the failure was surfaced at the step boundary).
        final StepExecution statementStep = statementStepExecution(execution);
        assertThat(statementStep.getStatus())
                .as("statementStep must end FAILED when its afterStep statement upload fails")
                .isEqualTo(BatchStatus.FAILED);

        // (3) A concrete failure exception was recorded (not merely a status flip).
        assertThat(statementStep.getFailureExceptions())
                .as("the statement S3 upload failure must be recorded on the step execution")
                .isNotEmpty();

        // (4) Neither statement artifact was written (a missing bucket yields a 404, i.e. object-absent).
        assertThat(objectExists(BUCKET_STATEMENTS, STATEMENTS_TEXT_KEY))
                .as("no statements.ps may be written when the upload fails")
                .isFalse();
        assertThat(objectExists(BUCKET_STATEMENTS, STATEMENTS_HTML_KEY))
                .as("no statements.html may be written when the upload fails")
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
                .addString(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, "it-stmt-fail-" + UUID.randomUUID())
                .addString("jobId", UUID.randomUUID().toString())
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
    }

    /**
     * Extracts the single {@code statementStep} execution from a job execution.
     *
     * @param execution the finished job execution
     * @return the statement step execution
     * @throws AssertionError if the step execution is not present
     */
    private static StepExecution statementStepExecution(final JobExecution execution) {
        return execution.getStepExecutions().stream()
                .filter(se -> STATEMENT_STEP_NAME.equals(se.getStepName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "job execution did not contain a '" + STATEMENT_STEP_NAME + "' step execution"));
    }
}
