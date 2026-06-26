/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.integration;

import com.carddemo.entity.Account;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration test for the Spring Batch job {@code statementJob}
 * ({@link com.carddemo.batch.job.StatementJobConfig}), the Java realization of the
 * legacy COBOL statement generator {@code CBSTM03A} with its file-service subroutine
 * {@code CBSTM03B}, driven by the JCL job {@code CREASTMT} and shaped by the statement
 * copybook {@code COSTM01} (members {@code app/cbl/CBSTM03A.CBL},
 * {@code app/cbl/CBSTM03B.CBL}, {@code app/jcl/CREASTMT.JCL} and
 * {@code app/cpy/COSTM01.CPY} at source commit {@code 27d6c6f}).
 *
 * <h2>The CBSTM03A parity invariant under test</h2>
 * {@code CREASTMT} "creates a statement for each CARD present in the XREF file": the
 * COBOL mainline loops the card cross-reference, and for every card reads the owning
 * customer and account, iterates the card's transactions, and emits the statement in
 * <strong>two parallel fixed-width presentations</strong> — a plain-text statement
 * ({@code FD-STMTFILE-REC PIC X(80)}, DD {@code STMTFILE} {@code LRECL=80}) and an HTML
 * statement ({@code FD-HTMLFILE-REC PIC X(100)}, DD {@code HTMLFILE} {@code LRECL=100}).
 * Both files are opened once and closed once per run, so a single text object and a
 * single HTML object are produced per execution, each carrying every card's statement in
 * card-number order. The two fixed record widths (80 and 100) are the external interface
 * contract this suite verifies (Validation Gate&nbsp;5).
 *
 * <h2>Real infrastructure (no mocks, no H2, no live AWS)</h2>
 * This class extends {@link AbstractIntegrationIT}, so it runs against a <strong>real
 * PostgreSQL&nbsp;16</strong> container with Flyway applying the {@code V1}/{@code V2}/{@code V3}
 * migrations and Hibernate under {@code ddl-auto=validate}, and a <strong>real
 * LocalStack</strong> container providing S3. The statement objects are written by the
 * production {@code S3Template} path to the canonical {@code carddemo-statements} bucket
 * and read back through the base {@link AbstractIntegrationIT#newS3Client()} factory,
 * which targets LocalStack with path-style addressing and the dummy {@code test}/{@code test}
 * credentials, so there is <strong>zero live-AWS dependency and no real credential</strong>
 * anywhere in this suite. The job is launched against the real {@code BATCH_*} metadata
 * tables (auto-created by {@code spring.batch.jdbc.initialize-schema=always}).
 *
 * <h2>Why this IT is not {@code @Transactional}</h2>
 * Spring Batch commits in its own transactions, so a test-managed rollback transaction
 * would both hide the job's writes and deadlock against the job's chunk commits. This
 * class therefore owns its data and S3 lifecycle explicitly:
 * {@link #initBatchAndResetState()} clears the {@code transactions} master, provisions the
 * canonical buckets, and empties the statement bucket before each test, and
 * {@link #cleanState()} restores that empty state afterwards so sibling suites observe the
 * shared singleton container in its expected seed state.
 *
 * <h2>JobLauncherTestUtils wiring (multi-job context)</h2>
 * The migration defines several {@code Job} beans, so {@link AbstractIntegrationIT}
 * deliberately does not expose a {@link JobLauncherTestUtils} bean (it would require
 * exactly one {@code Job}). This IT instead instantiates it manually and injects the
 * specific job under test by {@link Qualifier qualified} bean name, wiring the real
 * {@link JobLauncher} and {@link JobRepository} in {@link #initBatchAndResetState()}.
 *
 * <h2>Seed data and the chosen statement card</h2>
 * Flyway&nbsp;V3 seeds 50 {@code card_xref} rows (each resolving to an existing customer
 * and account, customer-id equal to account-id), 50 {@code customers}, and 50
 * {@code accounts}; the posted {@code transactions} table starts empty. To make a
 * statement total deterministic, {@link #seedStatementCardTransactions()} inserts three
 * transactions for one specific seeded card ({@link #STATEMENT_CARD_NUM}, which resolves
 * to customer&nbsp;{@value #STATEMENT_CUST_ID} / account&nbsp;{@value #STATEMENT_ACCT_ID})
 * with known amounts whose sum is asserted against the rendered statement total.
 */
@DisplayName("StatementJob IT — CBSTM03A parity (per-XREF-card statements, 80-char text + 100-char HTML, LocalStack S3)")
public class StatementJobIT extends AbstractIntegrationIT {

    /** The posted-transactions master the statement job reads per card; cleared around each test. */
    private static final String TRANSACTIONS_TABLE = "transactions";

    /**
     * Card number of the seeded {@code card_xref} row used for the deterministic-total
     * assertions. It resolves to customer {@value #STATEMENT_CUST_ID} / account
     * {@value #STATEMENT_ACCT_ID}; the exact 16-character width matches the {@code CHAR(16)}
     * {@code TRAN-CARD-NUM} column so the by-card read finds the seeded transactions without
     * a bpchar padding mismatch.
     */
    private static final String STATEMENT_CARD_NUM = "9680294154603697";

    /** Seeded customer id that {@link #STATEMENT_CARD_NUM} cross-references. */
    private static final long STATEMENT_CUST_ID = 1L;

    /** Seeded account id that {@link #STATEMENT_CARD_NUM} cross-references. */
    private static final long STATEMENT_ACCT_ID = 1L;

    /** Banner line that opens every per-card statement ({@code CBSTM03A 5000-CREATE-STATEMENT}). */
    private static final String START_OF_STATEMENT = "START OF STATEMENT";

    /** Label that prefixes the statement total line ({@code ST-TOTAL} trailer). */
    private static final String TOTAL_LABEL = "Total EXP:";

    /** Fixed record width of the plain-text statement ({@code FD-STMTFILE-REC PIC X(80)}). */
    private static final int TEXT_RECORD_WIDTH = 80;

    /** Fixed record width of the HTML statement ({@code FD-HTMLFILE-REC PIC X(100)}). */
    private static final int HTML_RECORD_WIDTH = 100;

    /** Object-key suffix the job assigns to the single combined text statement object. */
    private static final String TEXT_KEY_SUFFIX = ".txt";

    /** Object-key suffix the job assigns to the single combined HTML statement object. */
    private static final String HTML_KEY_SUFFIX = ".html";

    /** Unique transaction description / amount fixtures for the chosen statement card. */
    private static final String DESC_ALPHA = "STMTITALPHA";
    private static final String DESC_BRAVO = "STMTITBRAVO";
    private static final String DESC_CHARLIE = "STMTITCHARLIE";
    private static final BigDecimal AMOUNT_ALPHA = new BigDecimal("100.00");
    private static final BigDecimal AMOUNT_BRAVO = new BigDecimal("200.00");
    private static final BigDecimal AMOUNT_CHARLIE = new BigDecimal("50.50");

    /**
     * Monotonic {@code run.id} source giving every launch a distinct {@code JobInstance}.
     * Seeded with {@link System#nanoTime()} so identifiers never collide with a prior run
     * when the singleton PostgreSQL container is reused ({@code withReuse(true)}) across
     * separate JVM executions.
     */
    private static final AtomicLong RUN_ID_SEQUENCE = new AtomicLong(System.nanoTime());

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    @Qualifier("statementJob")
    private Job statementJob;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    /** Manually wired per-test (the base class exposes no such bean in the multi-job context). */
    private JobLauncherTestUtils jobLauncherTestUtils;

    /**
     * Resets the mutable state this IT owns and wires {@link JobLauncherTestUtils} to the
     * qualified {@code statementJob}. The posted {@code transactions} master is cleared (this
     * IT cannot be {@code @Transactional}); the canonical AWS resources are provisioned
     * idempotently so the {@code carddemo-statements} bucket exists before the job uploads to
     * it (the production {@code S3Template} upload path does not create buckets); and the
     * statement bucket is emptied so each test observes exactly the objects its own launch
     * produced.
     */
    @BeforeEach
    void initBatchAndResetState() {
        deleteFrom(TRANSACTIONS_TABLE);
        provisionCanonicalAwsResources();
        emptyBucket(statementBucket());
        jobLauncherTestUtils = new JobLauncherTestUtils();
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJobRepository(jobRepository);
        jobLauncherTestUtils.setJob(statementJob);
    }

    /**
     * Returns the shared {@code transactions} table and the statement bucket to their
     * empty-seed state for the other suites.
     */
    @AfterEach
    void cleanState() {
        deleteFrom(TRANSACTIONS_TABLE);
        emptyBucket(statementBucket());
    }

    // =====================================================================================
    // Test methods.
    // =====================================================================================

    /**
     * The job runs end-to-end against real PostgreSQL and LocalStack and reports success:
     * every seeded {@code card_xref} row yields a statement and the {@link JobExecution}
     * reports {@link BatchStatus#COMPLETED} with the {@code COMPLETED} exit code.
     *
     * @throws Exception if the job launch fails (propagated from {@link JobLauncherTestUtils})
     */
    @Test
    @DisplayName("statementJob completes with BatchStatus.COMPLETED and ExitStatus COMPLETED")
    void jobCompletesSuccessfully() throws Exception {
        JobExecution execution = launchStatementJob();

        assertThat(execution.getStatus())
                .as("statementJob batch status")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("statementJob exit code")
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * The text statement object is fixed 80-column width: every line is exactly
     * {@value #TEXT_RECORD_WIDTH} characters, reproducing {@code FD-STMTFILE-REC PIC X(80)}
     * (DD {@code STMTFILE} {@code LRECL=80}). This is the text half of the Gate&nbsp;5
     * fixed-width contract.
     *
     * @throws Exception if the job launch or the S3 read fails
     */
    @Test
    @DisplayName("text statement object is fixed 80-char width per line (FD-STMTFILE-REC PIC X(80))")
    void textStatementObjectsAre80CharWide() throws Exception {
        launchStatementJob();

        String bucket = statementBucket();
        String textKey = singleKeyEndingWith(listKeys(bucket), TEXT_KEY_SUFFIX);
        List<String> lines = splitFixedWidthLines(readObject(bucket, textKey));

        assertThat(lines)
                .as("the text statement object must contain at least one record")
                .isNotEmpty();
        assertThat(lines).allSatisfy(line -> assertThat(line.length())
                .as("every text statement line is exactly %d characters", TEXT_RECORD_WIDTH)
                .isEqualTo(TEXT_RECORD_WIDTH));
    }

    /**
     * The HTML statement object is fixed 100-column width: every line is exactly
     * {@value #HTML_RECORD_WIDTH} characters, reproducing {@code FD-HTMLFILE-REC PIC X(100)}
     * (DD {@code HTMLFILE} {@code LRECL=100}). This is the HTML half of the Gate&nbsp;5
     * fixed-width contract.
     *
     * @throws Exception if the job launch or the S3 read fails
     */
    @Test
    @DisplayName("HTML statement object is fixed 100-char width per line (FD-HTMLFILE-REC PIC X(100))")
    void htmlStatementObjectsAre100CharWide() throws Exception {
        launchStatementJob();

        String bucket = statementBucket();
        String htmlKey = singleKeyEndingWith(listKeys(bucket), HTML_KEY_SUFFIX);
        List<String> lines = splitFixedWidthLines(readObject(bucket, htmlKey));

        assertThat(lines)
                .as("the HTML statement object must contain at least one record")
                .isNotEmpty();
        assertThat(lines).allSatisfy(line -> assertThat(line.length())
                .as("every HTML statement line is exactly %d characters", HTML_RECORD_WIDTH)
                .isEqualTo(HTML_RECORD_WIDTH));
    }

    /**
     * For the chosen card with seeded transactions, the text statement contains the owning
     * customer's name and the 11-digit account id, a line for every seeded transaction
     * (carrying its description), and a trailer total equal to the {@link BigDecimal} sum of
     * the card's transaction amounts. The expected customer name and account id are derived
     * from the seeded {@link Customer} / {@link Account} so the assertions track the seed
     * data rather than hardcoded literals.
     *
     * @throws Exception if the job launch or the S3 read fails
     */
    @Test
    @DisplayName("text statement carries the customer name, account id, each transaction line, and the summed total")
    void statementContainsCustomerAccountAndTransactionLines() throws Exception {
        seedStatementCardTransactions();
        BigDecimal expectedTotal = AMOUNT_ALPHA.add(AMOUNT_BRAVO).add(AMOUNT_CHARLIE)
                .setScale(2, RoundingMode.HALF_EVEN);

        launchStatementJob();

        Customer customer = customerRepository.findById(STATEMENT_CUST_ID)
                .orElseThrow(() -> new IllegalStateException("seed customer " + STATEMENT_CUST_ID + " missing"));
        Account account = accountRepository.findById(STATEMENT_ACCT_ID)
                .orElseThrow(() -> new IllegalStateException("seed account " + STATEMENT_ACCT_ID + " missing"));
        String firstNameToken = tokenBeforeSpace(customer.getFirstName());
        String lastNameToken = tokenBeforeSpace(customer.getLastName());
        String accountIdField = String.format("%011d", account.getAcctId());

        String bucket = statementBucket();
        String textKey = singleKeyEndingWith(listKeys(bucket), TEXT_KEY_SUFFIX);
        List<String> lines = splitFixedWidthLines(readObject(bucket, textKey));

        List<String> block = statementBlockContaining(lines, DESC_ALPHA);
        assertThat(block)
                .as("a statement block for the seeded card must be present in the combined object")
                .isNotNull();
        String blockText = String.join("\n", block);

        assertThat(blockText)
                .as("statement shows the owning customer's name")
                .contains(firstNameToken)
                .contains(lastNameToken);
        assertThat(blockText)
                .as("statement shows the 11-digit account id")
                .contains(accountIdField);
        assertThat(blockText)
                .as("statement shows a detail line for every seeded transaction")
                .contains(DESC_ALPHA, DESC_BRAVO, DESC_CHARLIE);

        String totalLine = block.stream()
                .filter(line -> line.startsWith(TOTAL_LABEL))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("statement block has no 'Total EXP:' trailer"));
        BigDecimal renderedTotal = parseEditedAmount(totalLine.substring(totalLine.indexOf('$') + 1));
        assertThat(renderedTotal)
                .as("rendered statement total equals the BigDecimal sum of the card's transaction amounts")
                .isEqualByComparingTo(expectedTotal);
    }

    /**
     * One statement is generated per qualifying {@code card_xref} row. Because the job opens
     * and closes each output file once per run, all card statements are concatenated into a
     * single combined text object; the number of {@code START OF STATEMENT} banners in that
     * object therefore equals the number of cross-reference rows the reader processed. The
     * seeded card's statement is confirmed present among them.
     *
     * @throws Exception if the job launch or the S3 read fails
     */
    @Test
    @DisplayName("one statement is generated per card_xref row (banners in the combined object == card_xref count)")
    void statementGeneratedPerCardXref() throws Exception {
        seedStatementCardTransactions();

        launchStatementJob();

        long expectedStatements = cardXrefRepository.count();
        assertThat(expectedStatements)
                .as("Flyway V3 seeds the full card_xref set")
                .isGreaterThanOrEqualTo(50L);

        String bucket = statementBucket();
        String textKey = singleKeyEndingWith(listKeys(bucket), TEXT_KEY_SUFFIX);
        List<String> lines = splitFixedWidthLines(readObject(bucket, textKey));

        long bannerCount = lines.stream().filter(line -> line.contains(START_OF_STATEMENT)).count();
        assertThat(bannerCount)
                .as("one START OF STATEMENT banner per processed card_xref row")
                .isEqualTo(expectedStatements);
        assertThat(statementBlockContaining(lines, DESC_ALPHA))
                .as("the seeded card has its own statement among the generated set")
                .isNotNull();
    }

    /**
     * The generated statement objects are retrievable from the LocalStack S3 endpoint through
     * the base {@link AbstractIntegrationIT#newS3Client()} factory (which targets the
     * LocalStack container, never a live AWS endpoint), confirming the zero-live-AWS contract.
     * Both objects are removed afterwards by {@link #cleanState()}.
     *
     * @throws Exception if the job launch or the S3 read fails
     */
    @Test
    @DisplayName("statement objects are retrievable from LocalStack S3 (zero live-AWS dependency)")
    void noLiveAwsAllResourcesInLocalStack() throws Exception {
        launchStatementJob();

        String bucket = statementBucket();
        List<String> keys = listKeys(bucket);
        String textKey = singleKeyEndingWith(keys, TEXT_KEY_SUFFIX);
        String htmlKey = singleKeyEndingWith(keys, HTML_KEY_SUFFIX);

        assertThat(readObject(bucket, textKey))
                .as("text statement is retrievable from the LocalStack S3 endpoint")
                .isNotEmpty();
        assertThat(readObject(bucket, htmlKey))
                .as("HTML statement is retrievable from the LocalStack S3 endpoint")
                .isNotEmpty();
    }

    // =====================================================================================
    // Fixtures / helpers.
    // =====================================================================================

    /**
     * Launches {@code statementJob} synchronously (Spring Boot's default launcher) with
     * unique parameters and returns the completed execution.
     *
     * @return the {@link JobExecution} of the finished run
     * @throws Exception if the launch fails
     */
    private JobExecution launchStatementJob() throws Exception {
        return jobLauncherTestUtils.launchJob(uniqueJobParameters());
    }

    /**
     * Builds job parameters carrying a unique {@code run.id} so every launch is a distinct
     * {@code JobInstance} (the run-unique discriminator also flows into the statement object
     * keys).
     *
     * @return unique job parameters for one launch
     */
    private static JobParameters uniqueJobParameters() {
        return new JobParametersBuilder()
                .addLong("run.id", RUN_ID_SEQUENCE.incrementAndGet())
                .toJobParameters();
    }

    /**
     * Seeds three posted transactions for {@link #STATEMENT_CARD_NUM} with known amounts and
     * unique descriptions, so the chosen card's statement carries a predictable detail set and
     * total. The card number is exactly 16 characters to match the {@code CHAR(16)}
     * {@code TRAN-CARD-NUM} column on the by-card read.
     */
    private void seedStatementCardTransactions() {
        persistStatementTransaction("STMTIT0000000001", DESC_ALPHA, AMOUNT_ALPHA);
        persistStatementTransaction("STMTIT0000000002", DESC_BRAVO, AMOUNT_BRAVO);
        persistStatementTransaction("STMTIT0000000003", DESC_CHARLIE, AMOUNT_CHARLIE);
    }

    /**
     * Persists a single posted transaction for the chosen statement card, mirroring the
     * {@code CVTRA05Y TRAN-RECORD} layout and varying only the identifier, description, and
     * amount the statement assertions key off.
     *
     * @param tranId      the 16-character transaction identifier ({@code TRAN-ID})
     * @param description the transaction description ({@code TRAN-DESC})
     * @param amount      the monetary amount ({@code TRAN-AMT}, scale 2)
     */
    private void persistStatementTransaction(String tranId, String description, BigDecimal amount) {
        transactionRepository.save(new Transaction(
                tranId,                              // TRAN-ID            X(16)
                TransactionTypeCode.PURCHASE,        // TRAN-TYPE-CD       X(02) via converter
                1,                                   // TRAN-CAT-CD        9(04)
                "POS",                               // TRAN-SOURCE        X(10)
                description,                          // TRAN-DESC          X(100)
                amount,                              // TRAN-AMT           S9(09)V99 (scale 2)
                800000000L,                          // TRAN-MERCHANT-ID   9(09)
                "Statement IT Merchant",             // TRAN-MERCHANT-NAME X(50)
                "SEATTLE",                           // TRAN-MERCHANT-CITY X(50)
                "98101",                             // TRAN-MERCHANT-ZIP  X(10)
                STATEMENT_CARD_NUM,                  // TRAN-CARD-NUM      X(16)
                "2022-06-10 19:27:53.000000",        // TRAN-ORIG-TS       X(26)
                "2022-06-10 19:27:53.000000"));      // TRAN-PROC-TS       X(26)
    }

    /**
     * Lists every object key in a bucket through the base LocalStack-pointed S3 client.
     *
     * @param bucket the bucket to list
     * @return the object keys present in the bucket, never {@code null}
     */
    private static List<String> listKeys(String bucket) {
        try (S3Client s3 = newS3Client()) {
            ListObjectsV2Response listing =
                    s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build());
            List<String> keys = new ArrayList<>(listing.contents().size());
            for (S3Object object : listing.contents()) {
                keys.add(object.key());
            }
            return keys;
        }
    }

    /**
     * Returns the single key ending with the given suffix, asserting that exactly one such
     * object exists (the job emits one combined text object and one combined HTML object per
     * run, and each test empties the bucket beforehand).
     *
     * @param keys   the candidate object keys
     * @param suffix the required key suffix ({@code .txt} or {@code .html})
     * @return the single matching key
     */
    private static String singleKeyEndingWith(List<String> keys, String suffix) {
        List<String> matches = keys.stream().filter(key -> key.endsWith(suffix)).toList();
        assertThat(matches)
                .as("exactly one statement object ending with '%s'", suffix)
                .hasSize(1);
        return matches.get(0);
    }

    /**
     * Reads an S3 object as a {@code US-ASCII} string through the base LocalStack-pointed S3
     * client. The single-byte charset matches the writer's encoding, so the returned
     * character count equals the stored byte count.
     *
     * @param bucket the bucket holding the object
     * @param key    the object key
     * @return the object content decoded as {@code US-ASCII}
     */
    private static String readObject(String bucket, String key) {
        try (S3Client s3 = newS3Client()) {
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return bytes.asString(StandardCharsets.US_ASCII);
        }
    }

    /**
     * Splits a newline-delimited fixed-width payload into its records. The writer terminates
     * every record with a single {@code '\n'}, so {@link String#split(String)} (which drops
     * the trailing empty segment after the final separator) yields exactly the emitted
     * records; genuinely blank records are space-padded to the record width and are retained.
     *
     * @param content the object content read back from S3
     * @return the fixed-width records in write order
     */
    private static List<String> splitFixedWidthLines(String content) {
        return List.of(content.split("\n"));
    }

    /**
     * Returns the single per-card statement block (the lines from a {@code START OF STATEMENT}
     * banner through to the end of that card's statement) that contains the given marker.
     * Markers used by this suite are unique to the seeded card, so at most one block matches.
     *
     * @param lines  every line of the combined statement object
     * @param marker a substring unique to the target card's statement
     * @return the matching block's lines, or {@code null} when no block contains the marker
     */
    private static List<String> statementBlockContaining(List<String> lines, String marker) {
        List<String> current = null;
        List<String> match = null;
        for (String line : lines) {
            if (line.contains(START_OF_STATEMENT)) {
                current = new ArrayList<>();
            }
            if (current != null) {
                current.add(line);
                if (line.contains(marker)) {
                    match = current;
                }
            }
        }
        return match;
    }

    /**
     * Parses a COBOL {@code PIC Z(9).99-} edited-amount field back into a {@link BigDecimal}.
     * The field is the zero-suppressed integer part, a decimal point, two fraction digits, and
     * a trailing sign position ({@code '-'} for negative, a space otherwise); leading
     * zero-suppression spaces are trimmed before parsing.
     *
     * @param editedField the edited amount field (the text following the {@code '$'} marker)
     * @return the decoded signed amount
     */
    private static BigDecimal parseEditedAmount(String editedField) {
        boolean negative = editedField.endsWith("-");
        String body = editedField.substring(0, editedField.length() - 1).trim();
        BigDecimal magnitude = new BigDecimal(body);
        return negative ? magnitude.negate() : magnitude;
    }

    /**
     * Returns the portion of a value preceding its first embedded space, reproducing the COBOL
     * {@code STRING ... DELIMITED BY ' '} name assembly the statement renderer applies. A
     * {@code null} value yields an empty string.
     *
     * @param value the source value (a customer name component)
     * @return the text before the first space, or the whole value when it has none
     */
    private static String tokenBeforeSpace(String value) {
        if (value == null) {
            return "";
        }
        int index = value.indexOf(' ');
        return index < 0 ? value : value.substring(0, index);
    }
}
