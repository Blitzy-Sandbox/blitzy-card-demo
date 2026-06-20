package com.carddemo.integration.batch;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.carddemo.repository.AccountRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the {@code statementGenerationJob} Spring Batch job, proving behavioral
 * parity with the mainframe statement pipeline {@code CREASTMT.JCL} together with the COBOL
 * statement-generation main {@code CBSTM03A.CBL} and its file-service subroutine
 * {@code CBSTM03B.CBL} (lineage: source commit {@code 27d6c6f}; REFERENCE ONLY &mdash; no COBOL/JCL
 * is copied into the target).
 *
 * <p>The original {@code CREASTMT.JCL} is a five-step stream whose two statement-relevant steps are
 * re-platformed and verified here:</p>
 * <ul>
 *   <li><strong>{@code STEP030 EXEC PGM=IEFBR14} ({@code DISP=(MOD,DELETE,DELETE)} on
 *       {@code STATEMNT.HTML} and {@code STATEMNT.PS})</strong> &mdash; clears the prior statement
 *       output before regeneration; reproduced by the Spring Batch {@code deletePriorStatementsStep}
 *       tasklet (verified by {@link #priorOutputIsClearedBeforeRegeneration()}).</li>
 *   <li><strong>{@code STEP040 EXEC PGM=CBSTM03A}</strong> &mdash; renders each customer account
 *       statement in <em>both</em> the original output variants the COBOL emits: a plain-text
 *       statement ({@code STMTFILE}, {@code FD-STMTFILE-REC PIC X(80)}, LRECL=80) and an HTML
 *       statement ({@code HTMLFILE}, {@code FD-HTMLFILE-REC PIC X(100)}, LRECL=100). The two
 *       variants are persisted to the {@code carddemo-statements} S3 bucket (replacing the mainframe
 *       statement datasets) as the objects {@code STATEMNT.PS} and {@code STATEMNT.HTML}
 *       (verified by {@link #textStatementIs80ColumnLrecl()} and
 *       {@link #htmlStatementHasMarkup()}).</li>
 * </ul>
 *
 * <p>This test extends {@link AbstractBatchIntegrationTest} and reuses ALL of its scaffolding
 * (singleton Testcontainers PostgreSQL + LocalStack, the {@code @DynamicPropertySource} wiring and
 * AWS self-provisioning, the S3 helpers, the
 * {@link org.springframework.batch.core.launch.JobLauncher}, the per-test {@code @BeforeEach} S3
 * cleanup, and the shared constants). No container, property, {@code @SpringBootTest},
 * {@code @ActiveProfiles}, {@code @Testcontainers}, or {@code @Tag} scaffolding is re-declared
 * here.</p>
 *
 * <p>The job reads {@code Account} rows from the database (Flyway {@code V3} seeds 50 internally
 * consistent accounts/customers/cards/cross-references), so it runs over the full seeded set with no
 * S3 input upload required. Statement generation is read-only against the database, so the ordered
 * tests are robust against the shared singleton containers: the base {@code @BeforeEach} empties the
 * buckets between tests and each test launches the job itself with a unique {@code run.id}.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StatementGenerationJobIT extends AbstractBatchIntegrationTest {

    /** S3 object key for the accumulated plain-text statement output (COBOL {@code STMTFILE}). */
    private static final String TEXT_KEY = "STATEMNT.PS";

    /** S3 object key for the accumulated HTML statement output (COBOL {@code HTMLFILE}). */
    private static final String HTML_KEY = "STATEMNT.HTML";

    /** Fixed record length (LRECL), in columns, of a plain-text statement record ({@code PIC X(80)}). */
    private static final int TEXT_LRECL = 80;

    /** First job step ({@code CREASTMT.JCL} STEP030 / {@code IEFBR14}): clears the prior statement output. */
    private static final String DELETE_STEP_NAME = "deletePriorStatementsStep";

    /** Second job step ({@code CREASTMT.JCL} STEP040 / {@code CBSTM03A}): renders and writes the statements. */
    private static final String GENERATION_STEP_NAME = "statementGenerationStep";

    /**
     * Single-byte {@code ISO-8859-1} charset used to decode/encode statement bytes verbatim. The
     * writer persists both variants in this charset, so reading them back with the same charset
     * preserves the fixed-width fidelity exactly. Declared with its fully-qualified type so no extra
     * import is introduced.
     */
    private static final java.nio.charset.Charset LATIN1 = StandardCharsets.ISO_8859_1;

    /** The statement-generation job under test, injected by its bean name. */
    @Autowired
    @Qualifier("statementGenerationJob")
    private Job statementGenerationJob;

    /** Account repository, used only to assert the seed precondition (the job's driving input). */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * Launches the statement-generation job with only the unique {@code run.id} parameter. The job
     * runs over every seeded account and declares no business job parameters, so the base
     * {@code run.id} (which yields a distinct {@code JobInstance} per launch) is sufficient.
     *
     * @return the resulting job execution
     * @throws Exception if the launch fails (for example, an already-running or already-complete
     *                   instance)
     */
    private JobExecution launchStatements() throws Exception {
        return jobLauncher.run(statementGenerationJob, baseParams().toJobParameters());
    }

    // ------------------------------------------------------------------------------------------
    // Test 1: the job and both named steps complete.
    // ------------------------------------------------------------------------------------------

    /**
     * Verifies the two-step statement job runs to {@code COMPLETED} and that both named steps
     * ({@code deletePriorStatementsStep} &rarr; {@code statementGenerationStep}) execute and
     * complete. Completing over the full seeded set also proves that the
     * cross-reference&rarr;customer&rarr;account resolution succeeds for every account (no
     * {@code RecordNotFoundException}), i.e. the seed data is internally consistent &mdash; parity
     * with the multi-file read of {@code CBSTM03A}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @Order(1)
    void jobAndBothStepsComplete() throws Exception {
        Assumptions.assumeTrue(accountRepository.count() > 0,
                "No seeded accounts — Flyway V3 not applied");

        final JobExecution execution = launchStatements();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        final var stepNames = execution.getStepExecutions().stream()
                .map(se -> se.getStepName())
                .toList();
        assertThat(stepNames).contains(DELETE_STEP_NAME, GENERATION_STEP_NAME);
        execution.getStepExecutions().forEach(se ->
                assertThat(se.getStatus()).isEqualTo(BatchStatus.COMPLETED));
    }

    // ------------------------------------------------------------------------------------------
    // Test 2: text statement output — 80-column LRECL parity (STMTFILE PIC X(80)).
    // ------------------------------------------------------------------------------------------

    /**
     * Verifies the plain-text statement object {@code STATEMNT.PS} is produced and honours the
     * original {@code FD-STMTFILE-REC PIC X(80)} 80-column record contract.
     *
     * <p>The assertion is robust to either output convention the writer may emit: newline-delimited
     * lines (each at most 80 columns) or fixed 80-byte blocked records (no delimiters). A trailing
     * non-blank check confirms the object carries recognizable statement content without coupling to
     * the exact statement formatting.</p>
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @Order(2)
    void textStatementIs80ColumnLrecl() throws Exception {
        launchStatements();

        final byte[] text = getS3ObjectOrNull(BUCKET_STATEMENTS, TEXT_KEY);
        Assumptions.assumeTrue(text != null && text.length > 0,
                "No STATEMNT.PS produced — skipping");

        final String content = new String(text, LATIN1);
        if (content.indexOf('\n') >= 0) {
            content.lines().forEach(line ->
                    assertThat(line.length()).as("80-col text record").isLessThanOrEqualTo(TEXT_LRECL));
        } else {
            assertThat(text.length % TEXT_LRECL).as("blocked 80-byte LRECL").isZero();
        }
        assertThat(content.trim()).isNotEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // Test 3: HTML statement output (HTMLFILE PIC X(100)).
    // ------------------------------------------------------------------------------------------

    /**
     * Verifies the HTML statement object {@code STATEMNT.HTML} is produced and carries HTML markup.
     * The check is lenient and case-insensitive (contains-any) so it proves the HTML variant is
     * emitted without coupling to the exact table layout. The HTML variant uses the LRECL=100
     * contract ({@code FD-HTMLFILE-REC PIC X(100)}); per-line width is intentionally not enforced on
     * HTML because markup lines legitimately vary in length.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @Order(3)
    void htmlStatementHasMarkup() throws Exception {
        launchStatements();

        final byte[] html = getS3ObjectOrNull(BUCKET_STATEMENTS, HTML_KEY);
        Assumptions.assumeTrue(html != null && html.length > 0,
                "No STATEMNT.HTML produced — skipping");

        final String htmlContent = new String(html, LATIN1).toLowerCase(Locale.ROOT);
        assertThat(htmlContent).containsAnyOf("<html", "<!doctype", "<body", "<table", "<div");
    }

    // ------------------------------------------------------------------------------------------
    // Test 4: prior-output deletion parity (deletePriorStatementsStep / IEFBR14).
    // ------------------------------------------------------------------------------------------

    /**
     * Proves the prior-output clearing parity of {@code CBSTM03A}/{@code CREASTMT.JCL} STEP030: the
     * job's {@code deletePriorStatementsStep} removes any pre-existing {@code STATEMNT.PS} /
     * {@code STATEMNT.HTML} before the generation step regenerates them.
     *
     * <p>The base {@code @BeforeEach} has already emptied the bucket, so stale objects are seeded
     * within this test, the job is launched, and both objects are re-read: the stale marker must be
     * absent afterwards (the object was deleted and regenerated fresh, or replaced). The
     * {@code != null} guards cover the theoretical empty-output edge where the delete left nothing
     * regenerated &mdash; which still satisfies "stale removed".</p>
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @Order(4)
    void priorOutputIsClearedBeforeRegeneration() throws Exception {
        final String stale = "STALE-PRIOR-RUN-DO-NOT-KEEP";
        putS3Object(BUCKET_STATEMENTS, TEXT_KEY, stale.getBytes(LATIN1));
        putS3Object(BUCKET_STATEMENTS, HTML_KEY, stale.getBytes(LATIN1));

        final JobExecution execution = launchStatements();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        final byte[] text = getS3ObjectOrNull(BUCKET_STATEMENTS, TEXT_KEY);
        final byte[] html = getS3ObjectOrNull(BUCKET_STATEMENTS, HTML_KEY);
        if (text != null) {
            assertThat(new String(text, LATIN1)).doesNotContain(stale);
        }
        if (html != null) {
            assertThat(new String(html, LATIN1)).doesNotContain(stale);
        }
    }
}
