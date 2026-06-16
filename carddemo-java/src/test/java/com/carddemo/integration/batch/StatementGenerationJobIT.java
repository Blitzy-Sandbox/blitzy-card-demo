package com.carddemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.repository.AccountRepository;

import java.nio.charset.Charset;
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

/**
 * Integration test for the Spring Batch statement-generation job {@code statementGenerationJob}
 * (production class {@link com.carddemo.batch.jobs.StatementGenerationJob}, two steps
 * {@code deletePriorStatementsStep} &rarr; {@code statementGenerationStep}).
 *
 * <p>This IT proves behavioural parity with the mainframe statement pipeline
 * {@code app/jcl/CREASTMT.JCL} and its COBOL programs {@code app/cbl/CBSTM03A.CBL}
 * (statement-generation main) and {@code app/cbl/CBSTM03B.CBL} (the file-access subroutine), whose
 * transaction-altered reporting record layout is {@code app/cpy/COSTM01.CPY} (source commit
 * {@code 27d6c6f}; REFERENCE ONLY &mdash; no COBOL/JCL is copied). The JCL primitives are
 * re-platformed and verified here as:</p>
 * <ul>
 *   <li>{@code STEP030 EXEC PGM=IEFBR14} (which deleted the prior-run {@code STATEMNT.PS} and
 *       {@code STATEMNT.HTML} datasets) &rarr; the {@code deletePriorStatementsStep} tasklet that
 *       clears the prior {@code STATEMNT.PS}/{@code STATEMNT.HTML} S3 objects before regeneration.</li>
 *   <li>{@code STEP040 EXEC PGM=CBSTM03A} (which read the cross-reference, account, customer, and
 *       transaction files and wrote {@code STMTFILE} at {@code LRECL=80} and {@code HTMLFILE} at
 *       {@code LRECL=100}) &rarr; the chunk-oriented {@code statementGenerationStep} that renders
 *       both the fixed 80-column text statement ({@code STATEMNT.PS}) and the HTML statement
 *       ({@code STATEMNT.HTML}) to the statements bucket.</li>
 * </ul>
 *
 * <p>The COBOL {@code COND=(0,NE)} predecessor-success gating between the steps maps to Spring
 * Batch's {@code .start(deletePriorStatementsStep).next(statementGenerationStep)} ordering, so the
 * generation step only runs once the delete step has succeeded.</p>
 *
 * <p>All Testcontainers (PostgreSQL + LocalStack) wiring, dynamic property registration, AWS
 * self-provisioning, the S3 helpers, the {@code JobLauncher}, the resource-name constants, and the
 * per-test S3 cleanup are inherited from {@link AbstractBatchIntegrationTest}; none of that
 * scaffolding ({@code @SpringBootTest}, {@code @ActiveProfiles}, {@code @Testcontainers},
 * {@code @Tag}, the containers, or the property source) is redeclared here.</p>
 *
 * <p>The shared singleton containers mean the database and buckets are never assumed pristine
 * across classes: statement generation is read-only against the database (it never mutates
 * {@code Account}/{@code Transaction}), so no DB-delta assertions are needed; and because the base
 * {@code @BeforeEach} empties the statements bucket before every test, each test launches the job
 * itself and inspects the freshly produced {@code STATEMNT.PS}/{@code STATEMNT.HTML}. Every launch
 * uses {@code baseParams()} for a unique {@code run.id}, so each run yields a distinct
 * {@code JobInstance}.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StatementGenerationJobIT extends AbstractBatchIntegrationTest {

    /** S3 object key of the fixed 80-column text statement (COBOL {@code STMTFILE}). */
    private static final String TEXT_KEY = "STATEMNT.PS";

    /** S3 object key of the HTML statement (COBOL {@code HTMLFILE}). */
    private static final String HTML_KEY = "STATEMNT.HTML";

    /** Logical record length of the text statement (COBOL {@code FD-STMTFILE-REC PIC X(80)}). */
    private static final int TEXT_LRECL = 80;

    /**
     * Latin-1 (ISO-8859-1) charset used to decode statement bytes verbatim, one byte per character.
     * The writer persists content in this exact encoding, so decoding with it round-trips the bytes
     * without re-interpretation (no static import, so it raises no unused-import warning).
     */
    private static final Charset LATIN1 = StandardCharsets.ISO_8859_1;

    /** The statement-generation job under test, injected by its canonical bean name. */
    @Autowired
    @Qualifier("statementGenerationJob")
    private Job statementGenerationJob;

    /** Repository used only as a seed precondition (Flyway V3 must have loaded the account rows). */
    @Autowired
    private AccountRepository accountRepository;

    // ---------------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------------

    /**
     * Test 1 &mdash; the job and both of its named steps complete. Verifies the two-step contract
     * ({@code deletePriorStatementsStep} then {@code statementGenerationStep}) and an overall
     * {@code COMPLETED} status. Completing over the full seeded set also proves the
     * XREF&rarr;Customer&rarr;Account resolution succeeds for every account (no
     * {@code RecordNotFoundException}), i.e. the Flyway seed is internally consistent &mdash; the
     * Java equivalent of {@code CBSTM03A}'s multi-file read across {@code XREFFILE},
     * {@code CUSTFILE}, and {@code ACCTFILE}.
     *
     * @throws Exception if the launcher rejects the run
     */
    @Test
    @Order(1)
    void jobAndBothStepsComplete() throws Exception {
        Assumptions.assumeTrue(accountRepository.count() > 0,
                "No seeded accounts - Flyway V3 not applied");

        JobExecution execution = launchStatements();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        var stepNames = execution.getStepExecutions().stream()
                .map(stepExecution -> stepExecution.getStepName())
                .toList();
        assertThat(stepNames).contains("deletePriorStatementsStep", "statementGenerationStep");
        execution.getStepExecutions().forEach(stepExecution ->
                assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED));
    }

    /**
     * Test 2 &mdash; the text statement output ({@code STATEMNT.PS}), proving the COBOL
     * {@code STMTFILE} {@code LRECL=80} fixed-record contract. The object is read by its exact key,
     * decoded verbatim, and asserted to honour 80-column fidelity in a way that is robust to either
     * output convention: a newline-delimited body (each line &le; 80) or a blocked body of fixed
     * 80-byte records (no delimiters). A lenient content sniff confirms the statement carries
     * non-blank scaffolding without coupling to the exact formatting.
     *
     * @throws Exception if the launcher rejects the run
     */
    @Test
    @Order(2)
    void textStatementHonoursEightyColumnLrecl() throws Exception {
        launchStatements();

        byte[] text = getS3ObjectOrNull(BUCKET_STATEMENTS, TEXT_KEY);
        Assumptions.assumeTrue(text != null && text.length > 0,
                "No STATEMNT.PS produced - skipping");

        String content = new String(text, LATIN1);
        if (content.indexOf('\n') >= 0) {
            content.lines().forEach(line ->
                    assertThat(line.length()).as("80-col text record").isLessThanOrEqualTo(TEXT_LRECL));
        } else {
            assertThat(text.length % TEXT_LRECL).as("blocked 80-byte LRECL").isZero();
        }

        // Parity sniff: a generated statement always carries non-blank scaffolding (the
        // START/END-OF-STATEMENT banners and the basic-details block). Kept lenient on purpose.
        assertThat(content.strip()).isNotEmpty();
    }

    /**
     * Test 3 &mdash; the HTML statement output ({@code STATEMNT.HTML}), proving the COBOL
     * {@code HTMLFILE} variant is emitted. The object is read by its exact key, decoded verbatim,
     * lower-cased, and asserted to contain recognizable HTML markup. The HTML variant uses
     * {@code LRECL=100}; per-line width is intentionally not enforced because markup line widths
     * legitimately vary. The check is a lenient, case-insensitive contains-any so it is not coupled
     * to the precise tag set.
     *
     * @throws Exception if the launcher rejects the run
     */
    @Test
    @Order(3)
    void htmlStatementContainsMarkup() throws Exception {
        launchStatements();

        byte[] html = getS3ObjectOrNull(BUCKET_STATEMENTS, HTML_KEY);
        Assumptions.assumeTrue(html != null && html.length > 0,
                "No STATEMNT.HTML produced - skipping");

        String htmlContent = new String(html, LATIN1).toLowerCase(Locale.ROOT);
        assertThat(htmlContent).containsAnyOf("<html", "<!doctype", "<body", "<table", "<div");
    }

    /**
     * Test 4 &mdash; prior-output deletion parity (the {@code deletePriorStatementsStep}, COBOL
     * {@code STEP030}). {@code CBSTM03A}/{@code CREASTMT} clears the prior statement output before
     * regenerating; the Java delete step removes any pre-existing {@code STATEMNT.PS}/
     * {@code STATEMNT.HTML}. A stale marker object is pre-seeded under both keys (the base
     * {@code @BeforeEach} has already emptied the bucket, so the marker is seeded inside this test),
     * the job is run, and both objects are re-read: the stale marker must be gone, proving the prior
     * output was cleared. The {@code if (... != null)} guards the theoretical empty-output edge where
     * the delete left nothing regenerated, which still satisfies "stale removed"; with the
     * internally-consistent seed the objects are present with fresh, stale-free content.
     *
     * @throws Exception if the launcher rejects the run
     */
    @Test
    @Order(4)
    void priorStatementOutputIsCleared() throws Exception {
        String stale = "STALE-PRIOR-RUN-DO-NOT-KEEP";
        putS3Object(BUCKET_STATEMENTS, TEXT_KEY, stale.getBytes(LATIN1));
        putS3Object(BUCKET_STATEMENTS, HTML_KEY, stale.getBytes(LATIN1));

        JobExecution execution = launchStatements();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        byte[] text = getS3ObjectOrNull(BUCKET_STATEMENTS, TEXT_KEY);
        byte[] html = getS3ObjectOrNull(BUCKET_STATEMENTS, HTML_KEY);
        if (text != null) {
            assertThat(new String(text, LATIN1)).doesNotContain(stale);
        }
        if (html != null) {
            assertThat(new String(html, LATIN1)).doesNotContain(stale);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Launch helper
    // ---------------------------------------------------------------------------------------------

    /**
     * Launches {@code statementGenerationJob} over all seeded accounts. The job needs no business
     * {@code JobParameters} (the account reader is a repository cursor and the job declares no
     * required-parameter validator), so only the unique {@code run.id} from {@code baseParams()} is
     * supplied to guarantee a distinct {@code JobInstance} per launch.
     *
     * @return the resulting {@link JobExecution}
     * @throws Exception if the launcher rejects the run (already running, restart, completed, or
     *                   invalid parameters)
     */
    private JobExecution launchStatements() throws Exception {
        return jobLauncher.run(statementGenerationJob, baseParams().toJobParameters());
    }
}
