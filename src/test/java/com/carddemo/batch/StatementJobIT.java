package com.carddemo.batch;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.carddemo.observability.CorrelationIdFilter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end <strong>integration test</strong> ({@code *IT}, executed by the Maven Failsafe plugin
 * 3.5.2) for the statement-generation batch job, proving that the migrated pipeline persists both
 * statement renditions to S3 with the exact fixed-record widths mandated by the legacy dataset
 * contract.
 *
 * <h2>COBOL lineage (REFERENCE-only, source commit SHA {@code 27d6c6f}; not copied here)</h2>
 * <p>This test validates the Java realization of the legacy statement batch:</p>
 * <ul>
 *   <li>{@code app/cbl/CBSTM03A.CBL} &mdash; the driver program that, for every card cross-reference,
 *       joins the owning customer and account and emits one statement into <em>two</em> parallel
 *       sequential output files: the plain-text {@code STMT-FILE} ({@code FD-STMTFILE-REC PIC X(80)})
 *       and the {@code HTML-FILE} ({@code FD-HTMLFILE-REC PIC X(100)}).</li>
 *   <li>{@code app/cbl/CBSTM03B.CBL} &mdash; the VSAM I/O subprogram invoked via {@code CALL 'CBSTM03B'},
 *       migrated to a constructor-injected {@code StatementFileService} bean (AAP&nbsp;&sect;0.4.3).</li>
 *   <li>{@code app/jcl/CREASTMT.JCL} &mdash; step {@code STEP040 EXEC PGM=CBSTM03A}, whose
 *       {@code STMTFILE DD DCB=(LRECL=80,...,RECFM=FB)} and {@code HTMLFILE DD DCB=(LRECL=100,...,RECFM=FB)}
 *       fix the two record lengths this test asserts.</li>
 * </ul>
 *
 * <h2>What this test proves (and how it complements the unit test)</h2>
 * <p>{@code StatementItemWriterTest} pins the fixed-width formatting of the writer <em>in isolation</em>
 * with a mocked S3 client. This integration test instead launches the <strong>real</strong>
 * {@code statementJob} &mdash; reader &rarr; processor &rarr; writer &mdash; against a <strong>real
 * PostgreSQL&nbsp;16</strong> instance (seeded by the production Flyway migrations, including
 * {@code V3__seed_data.sql}) and a <strong>real LocalStack S3</strong> endpoint, then reads the two
 * uploaded objects back and verifies the interface contract byte-for-byte. It therefore exercises the
 * S3 write path against LocalStack with zero live-AWS dependencies (AAP&nbsp;&sect;0.7.7) and
 * contributes to Gate&nbsp;5 (external interface contract: the {@code LRECL=80}/{@code LRECL=100}
 * statement-file widths) and Gate&nbsp;8 coverage.</p>
 *
 * <h2>Infrastructure and self-provisioning (AAP&nbsp;&sect;0.7.7)</h2>
 * <p>The shared PostgreSQL and LocalStack containers, the Spring context, and the AWS SDK v2 clients
 * are inherited from {@link AbstractBatchIntegrationTest}. Per the zero-ambient-state rule this test
 * creates <em>only</em> the one bucket the job writes to &mdash; {@link #BUCKET_STATEMENTS} &mdash; in
 * {@link #createStatementsBucket()} and deletes it recursively in {@link #deleteStatementsBucket()},
 * so no S3 state leaks between tests. The report-queue {@code @SqsListener} declared elsewhere in the
 * application auto-creates its own FIFO queue on context refresh
 * ({@code sqs.queue-not-found-strategy=create}); it is unrelated to statement generation and is left
 * to the application, so this test neither provisions nor tears it down.</p>
 *
 * <h2>Why {@link JobLauncherTestUtils#setJob(Job)} is called explicitly</h2>
 * <p>{@link SpringBatchTest @SpringBatchTest} registers a {@link JobLauncherTestUtils} whose
 * {@code JobLauncher} and {@code JobRepository} are auto-wired from the (unique) auto-configured beans,
 * but whose {@code Job} is auto-wired only when the context contains exactly one {@code Job} bean. The
 * CardDemo context defines ten batch jobs, so the utility's job is intentionally left unset by the
 * framework; this test binds it to the specific {@code statementJob} in {@link #bindStatementJob()}.</p>
 *
 * @see StatementJob
 * @see StatementItemWriter
 * @see AbstractBatchIntegrationTest
 */
@SpringBatchTest
@DisplayName("StatementJob IT: CBSTM03A/B + CREASTMT.JCL -> two fixed-width S3 statement renditions")
class StatementJobIT extends AbstractBatchIntegrationTest {

    /**
     * S3 object key of the plain-text ({@code LRECL=80}) statement rendition. Mirrors
     * {@code carddemo.batch.statement.text-object-key} in {@code application.yml} and the
     * {@code StatementItemWriter} default; it is the {@code statements.ps} object the job uploads.
     */
    private static final String STATEMENTS_TEXT_KEY = "statements.ps";

    /**
     * S3 object key of the HTML ({@code LRECL=100}) statement rendition. Mirrors
     * {@code carddemo.batch.statement.html-object-key} in {@code application.yml} and the
     * {@code StatementItemWriter} default; it is the {@code statements.html} object the job uploads.
     */
    private static final String STATEMENTS_HTML_KEY = "statements.html";

    /**
     * Charset the writer uses to serialize both statement files
     * ({@link StatementItemWriter#serialize}); single-byte US-ASCII guarantees one character equals
     * exactly one byte, which is what makes the {@code LRECL} byte contract testable. Mirrored here so
     * the read-back decode matches the write encode exactly.
     */
    private static final Charset OUTPUT_CHARSET = StandardCharsets.US_ASCII;

    /**
     * Record delimiter the writer appends after <em>every</em> fixed-width record (a single line feed,
     * {@code U+000A}). Because the writer terminates each record &mdash; including the last &mdash; with
     * this delimiter, a serialized object ends with a trailing delimiter and splitting on it yields one
     * final empty element that {@link #splitFixedWidthRecords(byte[])} discards.
     */
    private static final String RECORD_DELIMITER = "\n";

    /**
     * Correlation id supplied as a job parameter and propagated to the batch MDC by the job's
     * correlation-id listener (keyed by {@link CorrelationIdFilter#CORRELATION_ID_MDC_KEY}), so this
     * run's log lines are traceable end-to-end (AAP Observability rule).
     */
    private static final String CORRELATION_ID = "it-stmt-001";

    /**
     * Extracts the 11-digit account id printed on each plain-text statement ({@code ST-LINE7}:
     * {@code "Account ID         :" + digits(acctId, 11)}). Exactly one text record per statement
     * matches, so applying this per record in file order yields the emitted account-id sequence.
     */
    private static final Pattern TEXT_ACCOUNT_ID_PATTERN = Pattern.compile("^Account ID\\s*:\\s*(\\d+)");

    /**
     * Extracts the 11-digit account id from each HTML statement's {@code <h3>} header
     * ({@code "<h3>Statement for Account Number: " + digits(acctId, 11) + "</h3>"}). This header appears
     * exactly once per statement, so applying this per record in file order yields the emitted
     * account-id sequence for the HTML rendition.
     */
    private static final Pattern HTML_ACCOUNT_ID_PATTERN =
            Pattern.compile("Statement for Account Number:\\s*(\\d+)");

    /**
     * Deterministic mirror of the reader-plus-processor selection: every card cross-reference whose
     * account and customer keys resolve to existing rows, projected to its account id and ordered by
     * ascending card number &mdash; exactly the order {@code StatementCardXrefItemReader} reads
     * ({@code ORDER BY xref_card_num ASC}) and the order {@code StatementProcessor} keeps (it filters
     * out any row whose {@code account}/{@code customer} parent is missing). The resulting account-id
     * list is the expected emission order the generated statements must match.
     */
    private static final String EXPECTED_ACCOUNT_IDS_SQL = """
            SELECT x.xref_acct_id
              FROM card_xref x
             WHERE x.xref_acct_id IS NOT NULL
               AND x.xref_cust_id IS NOT NULL
               AND EXISTS (SELECT 1 FROM account  a WHERE a.acct_id = x.xref_acct_id)
               AND EXISTS (SELECT 1 FROM customer c WHERE c.cust_id = x.xref_cust_id)
             ORDER BY x.xref_card_num ASC
            """;

    /**
     * Test utility registered by {@link SpringBatchTest @SpringBatchTest}; its launcher and repository
     * are auto-wired, and this test binds its {@code Job} to {@code statementJob} in
     * {@link #bindStatementJob()} before each run.
     */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /**
     * The specific batch job under test, selected by bean name from the ten jobs the context defines.
     */
    @Autowired
    @Qualifier("statementJob")
    private Job statementJob;

    /**
     * Plain-JDBC access to the seeded schema, used to compute the expected account-id emission order
     * ({@link #EXPECTED_ACCOUNT_IDS_SQL}) directly from the same data the job reads. Auto-configured by
     * Spring Boot from the container-backed {@code DataSource}.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Self-provisions the single bucket the statement job writes to and binds the job under test to the
     * shared {@link JobLauncherTestUtils}. Runs before every test method (AAP&nbsp;&sect;0.7.7).
     */
    @BeforeEach
    void createStatementsBucketAndBindJob() {
        createStatementsBucket();
        bindStatementJob();
    }

    /**
     * Creates the {@link #BUCKET_STATEMENTS} bucket in LocalStack so the writer's {@code close()} upload
     * has a destination. No other bucket is touched by {@code statementJob}.
     */
    private void createStatementsBucket() {
        createBucket(BUCKET_STATEMENTS);
    }

    /**
     * Binds the job under test to the {@link JobLauncherTestUtils}. Required because the framework only
     * auto-wires the utility's job when the context holds a single {@code Job} bean, and CardDemo
     * defines ten (see the class Javadoc).
     */
    private void bindStatementJob() {
        jobLauncherTestUtils.setJob(statementJob);
    }

    /**
     * Tears down the statements bucket recursively so no S3 state leaks to the next test
     * (AAP&nbsp;&sect;0.7.7). The base helper is idempotent, so this is safe even if setup failed before
     * the bucket was created.
     */
    @AfterEach
    void deleteStatementsBucket() {
        deleteBucketRecursively(BUCKET_STATEMENTS);
    }

    /**
     * Launches the real {@code statementJob} against real PostgreSQL and real LocalStack S3 and verifies
     * the full interface contract of the two generated statement renditions:
     * <ol>
     *   <li>the job completes successfully;</li>
     *   <li>both S3 objects &mdash; {@code statements.ps} and {@code statements.html} &mdash; exist;</li>
     *   <li>every plain-text record is exactly {@value StatementItemWriter#TEXT_RECORD_LENGTH} characters
     *       and every HTML record is exactly {@value StatementItemWriter#HTML_RECORD_LENGTH} characters
     *       ({@code LRECL=80}/{@code LRECL=100}, AAP Gate&nbsp;5 / Refinement&nbsp;R2);</li>
     *   <li>at least one statement is generated for the resolvable seeded cross-references; and</li>
     *   <li>the statements appear in ascending {@code XREF-CARD-NUM} order.</li>
     * </ol>
     *
     * <p><strong>Ordering proxy.</strong> The card number is never rendered onto a statement, but every
     * statement carries its account id (11-digit, zero-padded) in both renditions. Because the driving
     * reader emits cross-references strictly in ascending card-number order and the processor preserves
     * that order (only filtering rows with a missing parent), the account-id sequence observed in file
     * order is a deterministic function of the card-number order. This test therefore compares the
     * account ids extracted from the output, in file order, against {@link #expectedAccountIdsInCardOrder()}
     * &mdash; the same selection computed directly from the seeded schema ordered by card number. Matching
     * that list exactly simultaneously proves non-emptiness, the per-statement count, and the ascending
     * card-number ordering without hard-coding a brittle statement count.</p>
     *
     * @throws Exception if the job launch itself fails (propagated by
     *                   {@link JobLauncherTestUtils#launchJob(org.springframework.batch.core.JobParameters)})
     */
    @Test
    @DisplayName("uploads statements.ps (80-char) and statements.html (100-char) to S3 in ascending card-number order")
    void statementJobUploadsBothFixedWidthRenditionsInCardOrder() throws Exception {
        // --- Phase 2: launch the real job (unique run.id per RunIdIncrementer; correlation id for MDC).
        final JobExecution execution = jobLauncherTestUtils.launchJob(new JobParametersBuilder()
                .addString(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, CORRELATION_ID)
                .addLong("run.id", System.nanoTime())
                .toJobParameters());

        assertThat(execution.getStatus())
                .as("statementJob must COMPLETE against real PostgreSQL + real LocalStack S3")
                .isEqualTo(BatchStatus.COMPLETED);

        // --- Phase 3: both renditions must have been uploaded to the statements bucket.
        assertThat(objectExists(BUCKET_STATEMENTS, STATEMENTS_TEXT_KEY))
                .as("plain-text statement object s3://%s/%s must exist", BUCKET_STATEMENTS, STATEMENTS_TEXT_KEY)
                .isTrue();
        assertThat(objectExists(BUCKET_STATEMENTS, STATEMENTS_HTML_KEY))
                .as("HTML statement object s3://%s/%s must exist", BUCKET_STATEMENTS, STATEMENTS_HTML_KEY)
                .isTrue();

        // --- Phase 4: fixed-width contract (read back through the writer's charset/delimiter).
        final List<String> textRecords = splitFixedWidthRecords(readObject(BUCKET_STATEMENTS, STATEMENTS_TEXT_KEY));
        final List<String> htmlRecords = splitFixedWidthRecords(readObject(BUCKET_STATEMENTS, STATEMENTS_HTML_KEY));

        assertThat(textRecords)
                .as("at least one plain-text statement must be generated for the resolvable seeded cardxrefs")
                .isNotEmpty();
        assertThat(textRecords)
                .as("every plain-text record must be exactly %d characters (LRECL=80 / PIC X(80))",
                        StatementItemWriter.TEXT_RECORD_LENGTH)
                .allSatisfy(record -> assertThat(record).hasSize(StatementItemWriter.TEXT_RECORD_LENGTH));

        assertThat(htmlRecords)
                .as("the HTML rendition must be non-empty when statements were generated")
                .isNotEmpty();
        assertThat(htmlRecords)
                .as("every HTML record must be exactly %d characters (LRECL=100 / PIC X(100))",
                        StatementItemWriter.HTML_RECORD_LENGTH)
                .allSatisfy(record -> assertThat(record).hasSize(StatementItemWriter.HTML_RECORD_LENGTH));

        // --- Phase 4 (cont.): ascending XREF-CARD-NUM ordering via the rendered account-id sequence.
        final List<Long> expectedAccountIds = expectedAccountIdsInCardOrder();
        assertThat(expectedAccountIds)
                .as("seed data (V3) must resolve at least one cardxref to an account/customer statement")
                .isNotEmpty();

        assertThat(extractAccountIds(textRecords, TEXT_ACCOUNT_ID_PATTERN))
                .as("plain-text statements must appear once per resolvable cardxref, in ascending card-number order")
                .containsExactlyElementsOf(expectedAccountIds);
        assertThat(extractAccountIds(htmlRecords, HTML_ACCOUNT_ID_PATTERN))
                .as("HTML statements must appear once per resolvable cardxref, in ascending card-number order")
                .containsExactlyElementsOf(expectedAccountIds);
    }

    /**
     * Decodes a serialized statement object back into its individual fixed-width records.
     *
     * <p>The bytes are decoded with the writer's {@link #OUTPUT_CHARSET US-ASCII} charset and split on
     * the writer's {@link #RECORD_DELIMITER line-feed delimiter}. Because the writer appends the
     * delimiter after every record (including the last), splitting with a negative limit produces one
     * trailing empty element for the text following the final delimiter; that single artifact is
     * discarded. An empty object (zero statements) therefore yields an empty list. No genuine record is
     * ever empty &mdash; the writer pads every record to its exact width &mdash; so discarding only the
     * trailing empty element is safe.</p>
     *
     * @param objectBytes the raw bytes of an uploaded statement object; must not be {@code null}
     * @return the fixed-width records in file (card) order, without the trailing-delimiter artifact
     */
    private static List<String> splitFixedWidthRecords(final byte[] objectBytes) {
        final String content = new String(objectBytes, OUTPUT_CHARSET);
        final List<String> records = new ArrayList<>(Arrays.asList(content.split(RECORD_DELIMITER, -1)));
        if (!records.isEmpty() && records.get(records.size() - 1).isEmpty()) {
            records.remove(records.size() - 1);
        }
        return records;
    }

    /**
     * Extracts, in file order, the account id printed on each statement by applying {@code pattern} to
     * every record and collecting the first capture group of each match. The rendering guarantees at
     * most one matching record per statement, so the returned list is the emitted account-id sequence
     * for the rendition. {@link Long#valueOf(String)} parses the zero-padded value in base&nbsp;10, so
     * leading zeros are handled correctly.
     *
     * @param records the fixed-width records to scan, in file order; must not be {@code null}
     * @param pattern the extraction pattern whose group&nbsp;1 captures the account-id digits
     * @return the account ids in the order they appear across {@code records}
     */
    private static List<Long> extractAccountIds(final List<String> records, final Pattern pattern) {
        final List<Long> accountIds = new ArrayList<>();
        for (final String record : records) {
            final Matcher matcher = pattern.matcher(record);
            if (matcher.find()) {
                accountIds.add(Long.valueOf(matcher.group(1)));
            }
        }
        return accountIds;
    }

    /**
     * Computes the expected account-id emission order directly from the seeded schema, mirroring the
     * reader's ascending card-number sort and the processor's missing-parent filter
     * ({@link #EXPECTED_ACCOUNT_IDS_SQL}).
     *
     * @return the account ids of the resolvable cross-references, ordered by ascending card number
     */
    private List<Long> expectedAccountIdsInCardOrder() {
        return jdbcTemplate.queryForList(EXPECTED_ACCOUNT_IDS_SQL, Long.class);
    }
}
