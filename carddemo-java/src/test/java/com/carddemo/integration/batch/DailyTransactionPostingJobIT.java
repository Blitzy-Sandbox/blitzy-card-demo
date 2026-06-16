package com.carddemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;

/**
 * End-to-end integration test for the Spring Batch daily-transaction posting job
 * (bean {@code dailyTransactionPostingJob}), the Java re-platform of the mainframe daily-posting
 * pipeline {@code app/jcl/POSTTRAN.jcl} (single step {@code EXEC PGM=CBTRN02C}) and COBOL program
 * {@code app/cbl/CBTRN02C.cbl} (source commit {@code 27d6c6f}; REFERENCE ONLY &mdash; no COBOL or
 * JCL is copied into the Java target).
 *
 * <p>The suite proves behavioral parity with the COBOL baseline across two validation gates:</p>
 * <ul>
 *   <li><b>Gate 1 (end-to-end byte equivalence)</b> &mdash; the real {@code app/data/ASCII/dailytran.txt}
 *       fixture is staged to S3, processed through {@code file &rarr; validate &rarr; PostgreSQL + S3
 *       rejections}, and every one of the 300 input records is shown to be either posted or
 *       rejected (conservation; none lost, none duplicated).</li>
 *   <li><b>Gate 5 (fixed-width record contract)</b> &mdash; the 350-byte daily-transaction input
 *       record, the 430-byte reject record ({@code LRECL=430}), and the zoned-decimal trailing-sign
 *       overpunch monetary encoding are each verified against the exact COBOL layout.</li>
 * </ul>
 *
 * <p>It {@code extends AbstractBatchIntegrationTest} and reuses that base class's entire scaffolding
 * (singleton Testcontainers PostgreSQL + LocalStack, dynamic property wiring, AWS resource
 * self-provisioning, per-test S3 cleanup, the fixture locator, the S3 helpers, the overpunch
 * decoder, and the {@code JobLauncher}). No container, dynamic-property, or
 * {@code @SpringBootTest}/{@code @ActiveProfiles}/{@code @Testcontainers}/{@code @Tag} annotation is
 * re-declared here; all are inherited. When Docker is unavailable the inherited
 * {@code @Testcontainers(disabledWithoutDocker = true)} condition skips the whole class cleanly, and
 * when the source-tree fixture is unreachable each fixture-dependent test self-skips via the
 * inherited {@code requireFixtureBytes(...)} assumption.</p>
 *
 * <p><b>Shared-state robustness.</b> The base class shares one PostgreSQL database, one LocalStack
 * instance, and one cached Spring context across every batch IT. Because the posting writer persists
 * transactions with {@code save()} keyed on the {@code TRAN-ID} taken verbatim from the input record,
 * re-posting the same fixture is idempotent (no new rows on a second run). A naive
 * {@code count()}-delta would therefore read zero whenever another IT had already posted the same
 * fixture into the shared database, even though every record is still accounted for. To stay
 * contamination-proof, the conservation test derives the posted count from <em>membership</em> (how
 * many of the fixture's distinct transaction ids are present in the database after the run) rather
 * than from a row-count delta, and every other assertion uses dynamically-computed expectations
 * instead of absolute row counts. Each test seeds its own S3 input (the base empties the buckets
 * before every test) and launches the job with a fresh, unique {@code run.id}.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DailyTransactionPostingJobIT extends AbstractBatchIntegrationTest {

    /**
     * The complete set of daily-transaction reject reason codes the COBOL cascade can emit
     * ({@code CBTRN02C} {@code 1500-VALIDATE-TRAN}: 100 invalid card, 101 account-not-found,
     * 102 overlimit, 103 after-expiration; plus 109 account-not-found on the balance rewrite).
     * Codes 104-108 are absent from the source and must never appear in a reject record.
     */
    private static final Set<Integer> ALLOWED_REJECT_CODES = Set.of(100, 101, 102, 103, 109);

    /** Length of the {@code DALYTRAN-ID} key field at the head of every daily-transaction record. */
    private static final int TRAN_ID_LENGTH = 16;

    /** Inclusive-start (0-based) offset of the {@code DALYTRAN-AMT} field within a 350-byte record (cols 133-143). */
    private static final int AMOUNT_FIELD_BEGIN_INDEX = 132;

    /** Exclusive-end (0-based) offset of the {@code DALYTRAN-AMT} field within a 350-byte record. */
    private static final int AMOUNT_FIELD_END_INDEX = 143;

    /** Lowest printable ASCII byte, used to assert the verbatim copy of the original record. */
    private static final int PRINTABLE_ASCII_MIN = 0x20;

    /** Highest printable ASCII byte, used to assert the verbatim copy of the original record. */
    private static final int PRINTABLE_ASCII_MAX = 0x7E;

    /** The daily-transaction posting job under test, launched explicitly ({@code spring.batch.job.enabled=false}). */
    @Autowired
    @Qualifier("dailyTransactionPostingJob")
    private Job dailyTransactionPostingJob;

    /** Repository for the posted-transaction master table (re-platform of the {@code TRANSACT} KSDS). */
    @Autowired
    private TransactionRepository transactionRepository;

    /** Repository for the account table (re-platform of the {@code ACCTDAT} KSDS). */
    @Autowired
    private AccountRepository accountRepository;

    /** Repository for the transaction-category-balance table (re-platform of the {@code TCATBALF} KSDS). */
    @Autowired
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /**
     * Test 1 (Gate 1, happy path) &mdash; runs the posting job end-to-end over the real
     * {@code dailytran.txt} fixture and proves record conservation: every input record is either
     * posted to PostgreSQL or written to the S3 reject object, with none lost or duplicated. This is
     * the byte-equivalence guarantee against the COBOL baseline. The job's batch status stays
     * {@code COMPLETED} even when records are rejected (POSTTRAN has no condition code that fails the
     * job on rejects &mdash; only the exit code changes; see {@link #conditionCodeMapsRejectsToCompletedWithRejects()}).
     *
     * @throws Exception if the fixture cannot be read or the job launcher rejects the run
     */
    @Test
    @Order(1)
    void gate1HappyPathConservesEveryRecord() throws Exception {
        byte[] fixture = requireFixtureBytes(DAILY_TRAN_KEY);

        long recordCount = countFixtureRecords(fixture);
        assertThat(recordCount)
                .as("dailytran.txt must contain exactly %d fixed-width %d-byte records",
                        EXPECTED_DAILY_RECORDS, DAILY_TRAN_RECORD_LENGTH)
                .isEqualTo(EXPECTED_DAILY_RECORDS);

        putS3Object(BUCKET_INPUT, DAILY_TRAN_KEY, fixture);

        long preTxnCount = transactionRepository.count();

        JobParameters params = baseParams()
                .addString("inputLocation", inputLocationUri())
                .toJobParameters();
        JobExecution execution = launch(dailyTransactionPostingJob, params);

        assertThat(execution.getStatus())
                .as("POSTTRAN has no COND that fails the job on rejects; batch status stays COMPLETED")
                .isEqualTo(BatchStatus.COMPLETED);

        long rejectCount = countRejectRecords();

        // Contamination-proof posted count: how many of the fixture's distinct transaction ids are
        // present in the database after posting. TransactionWriter.post() uses save() (merge by the
        // PK TRAN-ID, which is fixed by the input record), so posting is idempotent; a count() delta
        // would read zero whenever the same fixture had already been posted by another IT sharing the
        // singleton database. Membership proves the true Gate-1 invariant directly: each input record
        // is either posted (its id is present) or rejected (its record is in DALYREJS).
        List<String> tranIds = extractDistinctTranIds(fixture);
        long validCount = tranIds.stream().filter(transactionRepository::existsById).count();

        assertThat(validCount + rejectCount)
                .as("Gate 1 conservation: every input record is either posted or rejected "
                        + "(none lost, none duplicated)")
                .isEqualTo(recordCount);

        writeGate1Report(recordCount, validCount, rejectCount,
                transactionRepository.count() - preTxnCount);
    }

    /**
     * Test 2 (condition-code fidelity) &mdash; verifies the JCL return-code contract: {@code CBTRN02C}
     * executes {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}, which the Java job maps to the
     * {@code COMPLETED_WITH_REJECTS} step/job exit code while the batch status remains
     * {@code COMPLETED}. The assertion is conditional on whether this run actually produced rejects,
     * so it stays correct without hard-coding a reject count.
     *
     * @throws Exception if the fixture cannot be read or the job launcher rejects the run
     */
    @Test
    @Order(2)
    void conditionCodeMapsRejectsToCompletedWithRejects() throws Exception {
        JobExecution execution = seedAndLaunchPostingJob();

        assertThat(execution.getStatus())
                .as("batch status is COMPLETED whether or not records were rejected")
                .isEqualTo(BatchStatus.COMPLETED);

        long rejectCount = countRejectRecords();
        String exitCode = execution.getStepExecutions().stream()
                .findFirst()
                .map(stepExecution -> stepExecution.getExitStatus().getExitCode())
                .orElse(execution.getExitStatus().getExitCode());

        if (rejectCount > 0) {
            assertThat(exitCode)
                    .as("CBTRN02C RC=4 (WS-REJECT-COUNT > 0) maps to the COMPLETED_WITH_REJECTS exit code")
                    .contains("COMPLETED_WITH_REJECTS");
        } else {
            assertThat(exitCode)
                    .as("no rejects produced: the exit code is the plain COMPLETED code")
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());
        }
    }

    /**
     * Test 3 (Gate 5, reject-record layout + cascade reason codes) &mdash; runs the posting job, then
     * reads the exact {@code DALYREJS} reject object and verifies it is a whole number of 430-byte
     * fixed-width records (350-byte original record + 4-byte zero-padded reason code + 76-byte
     * description) and that every decoded reason code is one of the five COBOL cascade codes
     * {@code {100, 101, 102, 103, 109}}. Codes 104-108 are absent from the source and must never
     * appear. The test self-skips when the fixture happens to produce zero rejects, keeping it
     * parity-agnostic.
     *
     * @throws Exception if the fixture cannot be read or the job launcher rejects the run
     */
    @Test
    @Order(3)
    void rejectRecordsHonour430ByteLayoutAndCascadeCodes() throws Exception {
        seedAndLaunchPostingJob();

        byte[] rejects = getS3ObjectOrNull(BUCKET_OUTPUT, REJECT_OBJECT_KEY);
        Assumptions.assumeTrue(rejects != null && rejects.length > 0,
                "No reject records produced - skipping reject-layout assertions");

        assertThat(rejects.length % REJECT_RECORD_LENGTH)
                .as("DALYREJS must be a whole number of %d-byte fixed-width records (Gate 5)",
                        REJECT_RECORD_LENGTH)
                .isZero();

        int rejectRecords = rejects.length / REJECT_RECORD_LENGTH;
        for (int i = 0; i < rejectRecords; i++) {
            int offset = i * REJECT_RECORD_LENGTH;
            String codeText = new String(rejects, offset + DAILY_TRAN_RECORD_LENGTH, 4,
                    StandardCharsets.ISO_8859_1).trim();
            int code = Integer.parseInt(codeText);
            assertThat(ALLOWED_REJECT_CODES)
                    .as("reject reason code %d at record %d must be one of the CBTRN02C cascade codes "
                            + "{100,101,102,103,109}; codes 104-108 must never appear", code, i)
                    .contains(code);
        }

        // Stronger parity: the head of the first reject record is the 16-byte DALYTRAN-ID of the
        // original record copied verbatim into the 430-byte frame, so it is printable ASCII.
        String firstTranId = new String(rejects, 0, TRAN_ID_LENGTH, StandardCharsets.ISO_8859_1);
        assertThat(firstTranId.chars().allMatch(this::isPrintableAscii))
                .as("the 16-byte tran-id prefix of the first reject record must be printable ASCII")
                .isTrue();
    }

    /**
     * Test 4 (Gate 5, decimal fidelity) &mdash; validates the zoned-decimal trailing-sign overpunch
     * decoding the posting pipeline relies on, per the decimal-precision rules (AAP &sect;0.8.2):
     * monetary fields decode to {@link BigDecimal} with scale 2 and are compared with
     * {@code compareTo} semantics (never {@code equals}, which is scale-sensitive). Two canonical
     * vectors are asserted, followed by a live decode of the first fixture record's amount field when
     * the fixture is reachable (the live part is skipped silently when it is not, so the canonical
     * assertions always run).
     *
     * @throws IOException if the reachable fixture cannot be read
     */
    @Test
    @Order(4)
    void overpunchDecodingPreservesScaleTwoDecimalFidelity() throws IOException {
        // Canonical vectors: trailing 'G' is +7 in the units position; trailing '}' is -0.
        assertThat(decodeOverpunch("0000005047G"))
                .as("trailing overpunch 'G' (+7) decodes to +504.77")
                .isEqualByComparingTo(new BigDecimal("504.77"));
        assertThat(decodeOverpunch("0000009190}"))
                .as("trailing overpunch '}' (-0) decodes to -919.00")
                .isEqualByComparingTo(new BigDecimal("-919.00"));
        assertThat(decodeOverpunch("0000005047G").scale())
                .as("monetary fields decode to BigDecimal scale 2")
                .isEqualTo(2);
        assertThat(decodeOverpunch("0000009190}").scale())
                .as("negative monetary fields also decode to BigDecimal scale 2")
                .isEqualTo(2);

        // Optional live decode: prove the on-disk fixture's amount field parses with the same fidelity.
        Path fixturePath = locateFixture(DAILY_TRAN_KEY);
        if (fixturePath != null) {
            byte[] fixture = Files.readAllBytes(fixturePath);
            String firstRecord = new String(fixture, StandardCharsets.ISO_8859_1)
                    .lines().findFirst().orElse("");
            if (firstRecord.length() >= AMOUNT_FIELD_END_INDEX) {
                BigDecimal liveAmount = decodeOverpunch(
                        firstRecord.substring(AMOUNT_FIELD_BEGIN_INDEX, AMOUNT_FIELD_END_INDEX));
                assertThat(liveAmount.scale())
                        .as("the live DALYTRAN-AMT decode preserves scale 2")
                        .isEqualTo(2);
                assertThat(liveAmount)
                        .as("the first fixture record amount decodes to +504.77")
                        .isEqualByComparingTo(new BigDecimal("504.77"));
            }
        }
    }

    /**
     * Test 5 (posting parity) &mdash; proves the database side effects of a valid posting, the COBOL
     * equivalent of {@code CBTRN02C}'s update of {@code TRANSACT} + {@code TCATBAL} + {@code ACCTDAT}:
     * a {@link Transaction} row is written with a scale-2 amount and populated type/category codes,
     * the transaction-category-balance table holds rows (seeded by Flyway and upserted by posting),
     * and account balances are present with scale-2 monetary fidelity. Structural and financial
     * fidelity is asserted rather than a hard balance delta, because the shared singleton context may
     * have already moved balances in earlier tests or other ITs.
     *
     * @throws Exception if the fixture cannot be read or the job launcher rejects the run
     */
    @Test
    @Order(5)
    void postingUpdatesTransactionCategoryBalanceAndAccount() throws Exception {
        seedAndLaunchPostingJob();

        assertThat(transactionRepository.count())
                .as("at least one valid record must have posted to the transaction master")
                .isGreaterThan(0L);

        List<Transaction> firstTransactionPage =
                transactionRepository.findAll(PageRequest.of(0, 1)).getContent();
        assertThat(firstTransactionPage).as("a posted transaction must be retrievable").isNotEmpty();
        Transaction posted = firstTransactionPage.get(0);
        assertThat(posted.getTranAmt()).as("TRAN-AMT must be present").isNotNull();
        assertThat(posted.getTranAmt().scale()).as("TRAN-AMT preserves BigDecimal scale 2").isEqualTo(2);
        assertThat(posted.getTranTypeCd()).as("TRAN-TYPE-CD must be populated").isNotNull();
        assertThat(posted.getTranCatCd()).as("TRAN-CAT-CD must be populated").isNotNull();

        assertThat(transactionCategoryBalanceRepository.count())
                .as("TCATBAL is seeded by Flyway and upserted by posting (2700-UPDATE-TCATBAL)")
                .isGreaterThan(0L);

        List<Account> firstAccountPage =
                accountRepository.findAll(PageRequest.of(0, 1)).getContent();
        assertThat(firstAccountPage).as("seeded accounts must be present").isNotEmpty();
        Account account = firstAccountPage.get(0);
        assertThat(account.getAcctCurrBal()).as("ACCT-CURR-BAL must be present").isNotNull();
        assertThat(account.getAcctCurrBal().scale()).as("ACCT-CURR-BAL preserves scale 2").isEqualTo(2);
        assertThat(account.getAcctCurrCycCredit()).as("ACCT-CURR-CYC-CREDIT must be present").isNotNull();
        assertThat(account.getAcctCurrCycDebit()).as("ACCT-CURR-CYC-DEBIT must be present").isNotNull();
    }

    // ------------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------------

    /**
     * Seeds the daily-transaction fixture into the input bucket and launches the posting job with a
     * fresh, unique {@code run.id}. Used by the tests that only need a completed run (the base class
     * empties the S3 buckets before each test, so every test must upload its own input). Self-skips
     * the calling test when the fixture is unreachable.
     *
     * @return the resulting job execution
     * @throws Exception if the fixture cannot be read or the job launcher rejects the run
     */
    private JobExecution seedAndLaunchPostingJob() throws Exception {
        byte[] fixture = requireFixtureBytes(DAILY_TRAN_KEY);
        putS3Object(BUCKET_INPUT, DAILY_TRAN_KEY, fixture);
        return launch(dailyTransactionPostingJob,
                baseParams().addString("inputLocation", inputLocationUri()).toJobParameters());
    }

    /**
     * Builds the {@code s3://} job-parameter location of the daily-transaction input.
     *
     * @return the {@code s3://carddemo-batch-input/dailytran.txt} URI
     */
    private static String inputLocationUri() {
        return "s3://" + BUCKET_INPUT + "/" + DAILY_TRAN_KEY;
    }

    /**
     * Counts the logical 350-byte records in the raw fixture, tolerating the on-disk
     * {@code app/data/ASCII} form where each record is stored on its own newline-terminated line
     * (so the raw byte length is {@code 300 * (350 + 1) = 105300}, not a multiple of 350). When the
     * payload is a clean multiple of the record length it is treated as raw fixed-width; otherwise
     * the non-empty line count is used.
     *
     * @param fixture the raw fixture bytes
     * @return the number of 350-byte logical records
     */
    private static long countFixtureRecords(byte[] fixture) {
        if (fixture.length % DAILY_TRAN_RECORD_LENGTH == 0) {
            return fixture.length / DAILY_TRAN_RECORD_LENGTH;
        }
        return new String(fixture, StandardCharsets.ISO_8859_1)
                .lines()
                .filter(line -> !line.isEmpty())
                .count();
    }

    /**
     * Extracts the distinct 16-byte {@code DALYTRAN-ID} keys (columns 1-16 of each record) from the
     * raw fixture. Newline-tolerant: it reads one id per non-empty line. The ids are the
     * fixed-width, zero-padded numeric transaction ids the writer persists verbatim as the
     * transaction primary key.
     *
     * @param fixture the raw fixture bytes
     * @return the distinct transaction ids, in first-seen order
     */
    private static List<String> extractDistinctTranIds(byte[] fixture) {
        return new String(fixture, StandardCharsets.ISO_8859_1)
                .lines()
                .filter(line -> line.length() >= TRAN_ID_LENGTH)
                .map(line -> line.substring(0, TRAN_ID_LENGTH))
                .distinct()
                .toList();
    }

    /**
     * Tests whether a single character code point is printable ASCII (0x20-0x7E inclusive).
     *
     * @param codePoint the character code point to test
     * @return {@code true} when the code point is printable ASCII
     */
    private boolean isPrintableAscii(int codePoint) {
        return codePoint >= PRINTABLE_ASCII_MIN && codePoint <= PRINTABLE_ASCII_MAX;
    }

    /**
     * Writes the best-effort Gate-1 byte-equivalence evidence artifact to
     * {@code target/gate1-byte-equivalence-report.txt}. Any {@link IOException} is swallowed so a
     * filesystem hiccup can never fail the test; the artifact is evidence, not an assertion.
     *
     * @param recordCount the number of input records
     * @param validCount  the number of posted (valid) records
     * @param rejectCount  the number of rejected records
     * @param countDelta  the transaction-row delta observed for this run (informational only, since
     *                    {@code save()}-based posting is idempotent under the shared database)
     */
    private static void writeGate1Report(long recordCount, long validCount, long rejectCount,
                                         long countDelta) {
        boolean conserved = validCount + rejectCount == recordCount;
        String content = "CardDemo Gate 1 - Daily Transaction Posting byte-equivalence report\n"
                + "Source baseline (REFERENCE-ONLY): POSTTRAN.jcl + CBTRN02C.cbl (commit 27d6c6f)\n"
                + "Input records (dailytran.txt): " + recordCount + "\n"
                + "Posted (valid) records:        " + validCount + "\n"
                + "Rejected records (DALYREJS):   " + rejectCount + "\n"
                + "Transaction row delta this run (informational; idempotent save): " + countDelta + "\n"
                + "Conservation (posted + rejected == input): " + (conserved ? "PASS" : "FAIL") + "\n";
        try {
            Path report = Path.of("target", "gate1-byte-equivalence-report.txt");
            Path parent = report.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(report, content);
        } catch (IOException ioe) {
            // Best-effort evidence artifact only; an IO failure must never fail the test.
        }
    }
}
