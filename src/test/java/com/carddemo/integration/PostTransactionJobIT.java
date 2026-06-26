/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.integration;

import com.carddemo.batch.processor.DailyTransactionRecordImage;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.enums.RejectReasonCode;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.DailyTransactionRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

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
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration test for the {@code postTransactionJob} Spring Batch job — the
 * Java realization of the COBOL {@code CBTRN02C} daily-transaction posting engine driven by
 * JCL {@code POSTTRAN.jcl}. This is the highest-risk batch IT in the migration: it verifies the
 * <strong>four-stage validation cascade</strong>, the byte-exact <strong>430-byte reject record</strong>
 * streamed to S3 (the {@code DALYREJS} GDG generation), and <strong>RC=4
 * ({@code COMPLETED_WITH_REJECTS})</strong> return-code parity.
 *
 * <h2>What it exercises end-to-end (Gates 1 / 4 / 5)</h2>
 * <ul>
 *   <li>The real {@code postTransactionStep} reading staged {@code daily_transaction} rows,
 *       validating/posting through {@code TransactionPostingProcessor}, and fanning each
 *       {@code PostingResult} to the composite {@code PostingResultWriter}
 *       (posted &rarr; {@code transactions} master, rejected &rarr; the 430-byte S3 object).</li>
 *   <li>The exact cascade and reject codes mandated by {@code CBTRN02C} lines 385-419:
 *       100 {@code INVALID CARD NUMBER FOUND}, 101 {@code ACCOUNT RECORD NOT FOUND},
 *       102 {@code OVERLIMIT TRANSACTION}, 103 {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}.</li>
 *   <li>The COBOL <strong>last-wins</strong> behavior: when a transaction is simultaneously over the
 *       credit limit (102) <em>and</em> past account expiration (103), the persisted reason is
 *       <strong>103</strong>, because {@code 1500-B-LOOKUP-ACCT} evaluates the two guards as separate
 *       (non-nested) {@code IF} blocks and the 103 {@code MOVE} overwrites the 102 {@code MOVE}.</li>
 * </ul>
 *
 * <h2>Wiring and isolation</h2>
 * <p>The class extends {@link AbstractIntegrationIT}, inheriting its {@code @SpringBootTest}
 * (random web port), {@code @ActiveProfiles("test")} and {@code @Testcontainers} configuration plus
 * the singleton PostgreSQL 16 and LocalStack containers. The schema and seed data come from Flyway
 * V1/V2/V3 inside the container — never H2, Mockito, or live AWS.</p>
 *
 * <p>The class is deliberately <strong>not</strong> annotated {@code @Transactional}: Spring Batch owns
 * its own transactions and commits, so a surrounding test transaction would roll back the committed
 * state the job produced and hide it. Instead each test starts from a known, empty staging state and
 * cleans up explicitly. Because Flyway V3 seeds {@code daily_transaction} with 300 production rows
 * (and a sibling repository IT asserts that exact count), this IT snapshots those rows once and
 * restores them after every test, while every other mutation targets dedicated, test-owned rows
 * (a synthetic account/card that the schema's absence of foreign keys makes safe to insert/delete).</p>
 *
 * @see AbstractIntegrationIT
 */
@DisplayName("PostTransactionJob IT — CBTRN02C posting cascade, 430-byte S3 rejects, RC=4 parity (Gates 1/4/5)")
public class PostTransactionJobIT extends AbstractIntegrationIT {

    // ---------------------------------------------------------------------------------------------
    // Table and exit-code constants
    // ---------------------------------------------------------------------------------------------

    /** Staging table populated from {@code DALYTRAN-RECORD} / {@code CVTRA06Y}. */
    private static final String DAILY_TRANSACTION_TABLE = "daily_transaction";

    /** Posted transaction master table ({@code TRAN-RECORD} / {@code CVTRA05Y}). */
    private static final String TRANSACTIONS_TABLE = "transactions";

    /** Flyway V3 seeds exactly this many rows into {@code daily_transaction} (from {@code dailytran.txt}). */
    private static final int SEEDED_DAILY_ROW_COUNT = 300;

    /** Step/job exit code emitted by {@code RejectCountingStepListener} when COMPLETED with &gt;0 rejects. */
    private static final String EXIT_COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    // ---------------------------------------------------------------------------------------------
    // Reject-object byte geometry — mirrors RejectTransactionWriter / CBTRN02C REJECT-RECORD
    // (PIC X(350) image + PIC 9(04) reason code + PIC X(76) reason description = 430 bytes),
    // each framed with a trailing line feed (the writer's LINE_DELIMITER) => 431 bytes on the wire.
    // ---------------------------------------------------------------------------------------------

    private static final int RECORD_WIDTH = 430;
    private static final int FRAMED_WIDTH = RECORD_WIDTH + 1; // 430 data bytes + trailing '\n'
    private static final int IMAGE_WIDTH = 350;
    private static final int CODE_OFFSET = 350;
    private static final int CODE_WIDTH = 4;
    private static final int DESC_OFFSET = 354;
    private static final int DESC_WIDTH = 76;

    /** Object-key prefix that maps the {@code DALYREJS} GDG dataset onto the output bucket. */
    private static final String REJECT_KEY_PREFIX = "rejects/dalyrejs/";

    // ---------------------------------------------------------------------------------------------
    // Deterministic, test-owned fixtures. Valid because V1__create_schema.sql declares NO foreign
    // keys; a synthetic account/card can be inserted and removed without touching seeded master data.
    // ---------------------------------------------------------------------------------------------

    private static final long TEST_ACCT_ID = 70_000_000_001L;
    private static final String TEST_CARD_NUM = "7000000000000001";
    private static final long TEST_CUST_ID = 700_000_001L;

    private static final long TEST_ACCT_ID_2 = 70_000_000_002L;
    private static final String TEST_CARD_NUM_2 = "7000000000000002";
    private static final long TEST_CUST_ID_2 = 700_000_002L;

    /** Card present in {@code card_xref} but pointing at an account with no row (drives reject 101). */
    private static final String ACCOUNT_NOT_FOUND_CARD_NUM = "8888888888888888";
    private static final long ACCOUNT_NOT_FOUND_ACCT_ID = 99_999_999_999L;

    /** Card absent from {@code card_xref} entirely (drives reject 100). */
    private static final String CARD_NOT_FOUND_CARD_NUM = "9999999999999999";

    private static final String FAR_FUTURE_EXPIRATION = "2099-12-31";
    private static final String PAST_EXPIRATION = "2000-01-01";
    private static final String ORIG_TS_DATE = "2022-06-10";

    private static final BigDecimal DEFAULT_CREDIT_LIMIT = scaled("10000.00");
    private static final BigDecimal WITHIN_LIMIT_AMT = scaled("100.00");
    private static final BigDecimal OVER_LIMIT_AMT = scaled("15000.00");

    /** Monotonic source of unique {@code run.id} job parameters (fresh JobInstance per launch). */
    private static final AtomicLong RUN_ID_SEQUENCE = new AtomicLong(System.nanoTime());

    /**
     * The 300 Flyway-seeded {@code daily_transaction} rows captured once, before any test clears the
     * table, so they can be restored after each test for the sibling repository suite.
     */
    private static List<DailyTransaction> seededDailyTransactions;

    // ---------------------------------------------------------------------------------------------
    // Collaborators
    // ---------------------------------------------------------------------------------------------

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    @Qualifier("postTransactionJob")
    private Job postTransactionJob;

    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * Manually instantiated launcher utility. {@link JobLauncherTestUtils#setJob(Job)} is autowired
     * by type, which would raise {@code NoUniqueBeanDefinitionException} were the utility declared as
     * a bean (six {@link Job} beans exist in the context). Instantiating it here and injecting the
     * specific {@code postTransactionJob} avoids that ambiguity.
     */
    private JobLauncherTestUtils jobLauncherTestUtils;

    // ---------------------------------------------------------------------------------------------
    // Per-test lifecycle
    // ---------------------------------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        jobLauncherTestUtils = new JobLauncherTestUtils();
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJobRepository(jobRepository);
        jobLauncherTestUtils.setJob(postTransactionJob);

        // Capture the Flyway-seeded daily_transaction rows exactly once, before the first test clears
        // them, so @AfterEach can restore the seed (DailyTransactionRepositoryIT asserts count()==300).
        if (seededDailyTransactions == null) {
            List<DailyTransaction> seed = new ArrayList<>(dailyTransactionRepository.findAll());
            assertThat(seed)
                    .as("Flyway V3 must seed daily_transaction with %d rows before this IT clears it",
                            SEEDED_DAILY_ROW_COUNT)
                    .hasSize(SEEDED_DAILY_ROW_COUNT);
            seededDailyTransactions = seed;
        }

        // The singleton LocalStack container starts empty, so create the canonical buckets/queue/topic
        // (idempotently) before the job's reject writer uploads to the output bucket and before this
        // test lists/reads it. Mirrors the sibling S3-backed integration suites.
        provisionCanonicalAwsResources();

        // Every test launches the job against ONLY the rows it stages, so clear the seed (it is
        // restored after the test) and any prior posted transactions, and reset the S3 output bucket.
        deleteFrom(TRANSACTIONS_TABLE);
        deleteFrom(DAILY_TRANSACTION_TABLE);
        emptyBucket(outputBucket());
    }

    @AfterEach
    void tearDown() {
        // Remove everything this test produced or staged.
        deleteFrom(TRANSACTIONS_TABLE);
        deleteFrom(DAILY_TRANSACTION_TABLE);

        // Drop test-owned master rows only. No foreign keys exist, so ordering is irrelevant and
        // seeded accounts (1..50), their cross-references and seeded category balances are untouched.
        jdbcTemplate.update(
                "DELETE FROM transaction_category_balance WHERE acct_id IN (?, ?)",
                TEST_ACCT_ID, TEST_ACCT_ID_2);
        jdbcTemplate.update(
                "DELETE FROM accounts WHERE acct_id IN (?, ?)",
                TEST_ACCT_ID, TEST_ACCT_ID_2);
        jdbcTemplate.update(
                "DELETE FROM card_xref WHERE xref_card_num IN (?, ?, ?, ?)",
                TEST_CARD_NUM, TEST_CARD_NUM_2, ACCOUNT_NOT_FOUND_CARD_NUM, CARD_NOT_FOUND_CARD_NUM);

        // Restore the Flyway daily_transaction seed (DailyTransaction has no @Version, so re-persisting
        // the detached snapshot is a clean re-insert) for sibling suites that depend on the 300 rows.
        if (seededDailyTransactions != null) {
            dailyTransactionRepository.saveAll(seededDailyTransactions);
        }

        emptyBucket(outputBucket());
    }

    // ---------------------------------------------------------------------------------------------
    // Tests — clean posting paths
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("all-valid batch: COMPLETED (not COMPLETED_WITH_REJECTS), every row posted, no reject object")
    void allValidTransactionsPostAndCompleteCleanly() throws Exception {
        seedAccountAndXref(TEST_ACCT_ID, TEST_CARD_NUM, TEST_CUST_ID, FAR_FUTURE_EXPIRATION, DEFAULT_CREDIT_LIMIT);
        dailyTransactionRepository.saveAll(List.of(
                daily(1, TEST_CARD_NUM, WITHIN_LIMIT_AMT),
                daily(2, TEST_CARD_NUM, scaled("250.00")),
                daily(3, TEST_CARD_NUM, scaled("75.50"))));

        JobExecution execution = launchPostingJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("zero rejects => the listener leaves the default COMPLETED exit code")
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(countRows(TRANSACTIONS_TABLE))
                .as("all three valid transactions posted to the master table")
                .isEqualTo(3L);
        assertThat(listRejectObjectKeys())
                .as("no DALYREJS object is written when the run produces no rejects")
                .isEmpty();
    }

    @Test
    @DisplayName("posted transaction preserves every field verbatim and stamps a 26-char processing timestamp")
    void postedTransactionPersistsAllFieldsAndProcTimestamp() throws Exception {
        seedAccountAndXref(TEST_ACCT_ID, TEST_CARD_NUM, TEST_CUST_ID, FAR_FUTURE_EXPIRATION, DEFAULT_CREDIT_LIMIT);
        String id = tranId(10);
        String origTs = ts(ORIG_TS_DATE); // 26-char YYYY-MM-DD HH:MM:SS.mmmmmm
        dailyTransactionRepository.save(new DailyTransaction(
                id, "01", 1, "POS", "TEST PURCHASE", WITHIN_LIMIT_AMT,
                123_456_789L, "TEST MERCHANT", "TEST CITY", "00000",
                TEST_CARD_NUM, origTs, ""));

        JobExecution execution = launchPostingJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Transaction posted = transactionRepository.findById(id)
                .orElseThrow(() -> new AssertionError("valid transaction must be posted: " + id));
        assertThat(posted.getTranId()).as("TRAN-ID copied from DALYTRAN-ID").isEqualTo(id);
        assertThat(posted.getTranAmt().compareTo(WITHIN_LIMIT_AMT)).as("TRAN-AMT preserved").isZero();
        assertThat(posted.getCardNum()).as("TRAN-CARD-NUM preserved").isEqualTo(TEST_CARD_NUM);
        assertThat(posted.getOrigTs()).as("TRAN-ORIG-TS preserved verbatim (26 chars)").isEqualTo(origTs);
        assertThat(posted.getTransactionType())
                .as("raw '01' round-trips through TransactionTypeConverter to PURCHASE")
                .isEqualTo(TransactionTypeCode.PURCHASE);
        assertThat(posted.getProcTs())
                .as("TRAN-PROC-TS stamped by 2000-POST (CHAR(26))")
                .isNotNull()
                .hasSize(26);
        assertThat(posted.getProcTs().trim())
                .as("processing timestamp formatted YYYY-MM-DD HH:MM:SS.mmmmmm")
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}");
    }

    @Test
    @DisplayName("posting upserts the transaction-category balance (2700-UPDATE-TCATBAL: existing row increased by amount)")
    void postingUpsertsTransactionCategoryBalance() throws Exception {
        seedAccountAndXref(TEST_ACCT_ID, TEST_CARD_NUM, TEST_CUST_ID, FAR_FUTURE_EXPIRATION, DEFAULT_CREDIT_LIMIT);

        // Pre-seed an existing category balance so this exercises the UPDATE arm (the status-other path
        // in 2700-UPDATE-TCATBAL); the CREATE arm (status-23) is covered by postingUpdatesAccount...().
        TransactionCategoryBalanceId balanceId = new TransactionCategoryBalanceId(TEST_ACCT_ID, "01", 1);
        transactionCategoryBalanceRepository.save(new TransactionCategoryBalance(balanceId, scaled("100.00")));

        dailyTransactionRepository.save(daily(11, TEST_CARD_NUM, scaled("50.00")));

        JobExecution execution = launchPostingJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        TransactionCategoryBalance updated = transactionCategoryBalanceRepository.findById(balanceId)
                .orElseThrow(() -> new AssertionError("category balance must remain after update"));
        assertThat(updated.getTranCatBal().compareTo(scaled("150.00")))
                .as("running balance increased by the transaction amount (100.00 + 50.00)")
                .isZero();
    }

    @Test
    @DisplayName("posting updates account balance and cycle buckets, and creates the category balance from absent")
    void postingUpdatesAccountBalanceAndCycleBuckets() throws Exception {
        seedAccountAndXref(TEST_ACCT_ID, TEST_CARD_NUM, TEST_CUST_ID, FAR_FUTURE_EXPIRATION, DEFAULT_CREDIT_LIMIT);
        dailyTransactionRepository.save(daily(12, TEST_CARD_NUM, scaled("123.45")));

        JobExecution execution = launchPostingJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Account account = accountRepository.findById(TEST_ACCT_ID)
                .orElseThrow(() -> new AssertionError("test account must exist"));
        // TransactionPostingProcessor 2800: currBal += amount always; a non-negative amount accrues to
        // the cycle-credit bucket and leaves the cycle-debit bucket unchanged.
        assertThat(account.getCurrBal().compareTo(scaled("123.45")))
                .as("ACCT-CURR-BAL increased by the posted amount").isZero();
        assertThat(account.getCurrCycCredit().compareTo(scaled("123.45")))
                .as("a non-negative amount accrues to ACCT-CURR-CYC-CREDIT").isZero();
        assertThat(account.getCurrCycDebit().compareTo(scaled("0.00")))
                .as("ACCT-CURR-CYC-DEBIT unchanged for a non-negative amount").isZero();

        TransactionCategoryBalance created = transactionCategoryBalanceRepository
                .findById(new TransactionCategoryBalanceId(TEST_ACCT_ID, "01", 1))
                .orElseThrow(() -> new AssertionError("category balance must be created from absent"));
        assertThat(created.getTranCatBal().compareTo(scaled("123.45")))
                .as("category balance created starting from the transaction amount").isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // Tests — the four-stage validation cascade and its reject records
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("stage 1 — card not present in card_xref => reject 0100 INVALID CARD NUMBER FOUND")
    void cardNotFoundProducesReject100() throws Exception {
        // No card_xref row for this card => the very first lookup fails; no account is needed.
        dailyTransactionRepository.save(daily(20, CARD_NOT_FOUND_CARD_NUM, WITHIN_LIMIT_AMT));

        JobExecution execution = launchPostingJob();

        assertCompletedWithRejects(execution);
        assertThat(countRows(TRANSACTIONS_TABLE)).as("nothing posts when the card is unknown").isZero();
        assertSingleRejectRecord(tranId(20), RejectReasonCode.CARD_NOT_FOUND);
    }

    @Test
    @DisplayName("stage 2 — card maps to a missing account => reject 0101 ACCOUNT RECORD NOT FOUND")
    void accountNotFoundProducesReject101() throws Exception {
        // Stage 1 passes (the xref exists) but stage 2 fails (the referenced account has no row).
        cardXrefRepository.save(new CardXref(ACCOUNT_NOT_FOUND_CARD_NUM, TEST_CUST_ID, ACCOUNT_NOT_FOUND_ACCT_ID));
        dailyTransactionRepository.save(daily(21, ACCOUNT_NOT_FOUND_CARD_NUM, WITHIN_LIMIT_AMT));

        JobExecution execution = launchPostingJob();

        assertCompletedWithRejects(execution);
        assertThat(countRows(TRANSACTIONS_TABLE)).isZero();
        assertSingleRejectRecord(tranId(21), RejectReasonCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("stage 3 — projected balance exceeds the credit limit => reject 0102 OVERLIMIT TRANSACTION")
    void overCreditLimitProducesReject102() throws Exception {
        // Within date (far-future expiration) but over the limit: (0 - 0 + 15000) > 10000.
        seedAccountAndXref(TEST_ACCT_ID, TEST_CARD_NUM, TEST_CUST_ID, FAR_FUTURE_EXPIRATION, DEFAULT_CREDIT_LIMIT);
        dailyTransactionRepository.save(daily(22, TEST_CARD_NUM, OVER_LIMIT_AMT));

        JobExecution execution = launchPostingJob();

        assertCompletedWithRejects(execution);
        assertThat(countRows(TRANSACTIONS_TABLE)).isZero();
        assertSingleRejectRecord(tranId(22), RejectReasonCode.OVER_CREDIT_LIMIT);
    }

    @Test
    @DisplayName("stage 4 — transaction date after account expiration => reject 0103 RECEIVED AFTER ACCT EXPIRATION")
    void expiredAccountProducesReject103() throws Exception {
        // Within the credit limit but received after expiration: '2000-01-01' < '2022-06-10'.
        seedAccountAndXref(TEST_ACCT_ID, TEST_CARD_NUM, TEST_CUST_ID, PAST_EXPIRATION, DEFAULT_CREDIT_LIMIT);
        dailyTransactionRepository.save(daily(23, TEST_CARD_NUM, WITHIN_LIMIT_AMT));

        JobExecution execution = launchPostingJob();

        assertCompletedWithRejects(execution);
        assertThat(countRows(TRANSACTIONS_TABLE)).isZero();
        assertSingleRejectRecord(tranId(23), RejectReasonCode.ACCOUNT_EXPIRED);
    }

    @Test
    @DisplayName("CRITICAL last-wins — over-limit AND expired => the persisted reason is 0103, never 0102")
    void expiredAndOverlimitYields103NotLastWins() throws Exception {
        // Both guards fail. CBTRN02C 1500-B-LOOKUP-ACCT runs the credit-limit IF then the expiration IF
        // as two sequential (non-nested) blocks, so the 103 MOVE overwrites the 102 MOVE: last wins = 103.
        seedAccountAndXref(TEST_ACCT_ID, TEST_CARD_NUM, TEST_CUST_ID, PAST_EXPIRATION, DEFAULT_CREDIT_LIMIT);
        dailyTransactionRepository.save(daily(24, TEST_CARD_NUM, OVER_LIMIT_AMT));

        JobExecution execution = launchPostingJob();

        assertCompletedWithRejects(execution);
        assertThat(countRows(TRANSACTIONS_TABLE)).isZero();
        assertSingleRejectRecord(tranId(24), RejectReasonCode.ACCOUNT_EXPIRED);

        // Explicit guard on the raw 4-byte reason code so the intent cannot regress silently.
        byte[] content = readSingleRejectObject();
        String code = new String(content, CODE_OFFSET, CODE_WIDTH, StandardCharsets.US_ASCII);
        assertThat(code)
                .as("expiration (0103) overwrites over-limit (0102) — last MOVE wins")
                .isEqualTo("0103")
                .isNotEqualTo("0102");
    }

    // ---------------------------------------------------------------------------------------------
    // Test — mixed batch: counts, ordered reject image, and observability counters
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("mixed batch — 1 valid + 5 rejects: 1 posted, one S3 object of 5×431 bytes, metrics advance")
    void mixedBatchReportsCorrectWriteAndRejectCounts() throws Exception {
        // Account A (not expired) drives the valid post and the over-limit (102) reject.
        seedAccountAndXref(TEST_ACCT_ID, TEST_CARD_NUM, TEST_CUST_ID, FAR_FUTURE_EXPIRATION, DEFAULT_CREDIT_LIMIT);
        // Account B (expired) drives the expiration (103) and last-wins (103) rejects.
        seedAccountAndXref(TEST_ACCT_ID_2, TEST_CARD_NUM_2, TEST_CUST_ID_2, PAST_EXPIRATION, DEFAULT_CREDIT_LIMIT);
        // A card_xref pointing at a non-existent account drives the account-not-found (101) reject.
        cardXrefRepository.save(new CardXref(ACCOUNT_NOT_FOUND_CARD_NUM, TEST_CUST_ID, ACCOUNT_NOT_FOUND_ACCT_ID));

        // Ascending tran-ids fix the sequential reader (and therefore S3 record) order deterministically.
        dailyTransactionRepository.saveAll(List.of(
                daily(30, TEST_CARD_NUM, WITHIN_LIMIT_AMT),               // valid          -> posted
                daily(31, CARD_NOT_FOUND_CARD_NUM, WITHIN_LIMIT_AMT),     // 100 card        -> reject
                daily(32, ACCOUNT_NOT_FOUND_CARD_NUM, WITHIN_LIMIT_AMT),  // 101 account     -> reject
                daily(33, TEST_CARD_NUM, OVER_LIMIT_AMT),                 // 102 over-limit  -> reject
                daily(34, TEST_CARD_NUM_2, WITHIN_LIMIT_AMT),             // 103 expired     -> reject
                daily(35, TEST_CARD_NUM_2, OVER_LIMIT_AMT)));             // 103 last-wins   -> reject

        double processedBefore = counterTotal("carddemo.batch.records.processed");
        double rejectedBefore = counterTotal("carddemo.batch.records.rejected");

        JobExecution execution = launchPostingJob();

        assertCompletedWithRejects(execution);
        assertThat(countRows(TRANSACTIONS_TABLE)).as("exactly one transaction posts").isEqualTo(1L);

        StepExecution step = execution.getStepExecutions().iterator().next();
        // Every input yields a PostingResult, so all six are read (and written by the composite writer);
        // the posted-versus-rejected split is asserted via the DB and S3 below, not via writeCount.
        assertThat(step.getReadCount()).as("all six staged rows are read").isEqualTo(6L);

        // One versioned object holding five 431-byte framed records, in ascending tran-id order.
        byte[] content = readSingleRejectObject();
        assertThat(content).as("five rejects => 5×431 bytes").hasSize(5 * FRAMED_WIDTH);
        assertRejectRecordAt(content, 0, tranId(31), RejectReasonCode.CARD_NOT_FOUND);
        assertRejectRecordAt(content, 1, tranId(32), RejectReasonCode.ACCOUNT_NOT_FOUND);
        assertRejectRecordAt(content, 2, tranId(33), RejectReasonCode.OVER_CREDIT_LIMIT);
        assertRejectRecordAt(content, 3, tranId(34), RejectReasonCode.ACCOUNT_EXPIRED);
        assertRejectRecordAt(content, 4, tranId(35), RejectReasonCode.ACCOUNT_EXPIRED);

        // Observability: the processed/rejected Micrometer counters advance by the run's real split.
        assertThat(counterTotal("carddemo.batch.records.processed") - processedBefore)
                .as("processed counter advanced by the posted count").isEqualTo(1.0d);
        assertThat(counterTotal("carddemo.batch.records.rejected") - rejectedBefore)
                .as("rejected counter advanced by the reject count").isEqualTo(5.0d);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers — launching
    // ---------------------------------------------------------------------------------------------

    /** Launches {@code postTransactionJob} with a fresh, unique {@code run.id} to force a new JobInstance. */
    private JobExecution launchPostingJob() throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addLong("run.id", RUN_ID_SEQUENCE.incrementAndGet())
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    private void assertCompletedWithRejects(JobExecution execution) {
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("COBOL RC=4 parity: a completed run with rejects reports COMPLETED_WITH_REJECTS")
                .isEqualTo(EXIT_COMPLETED_WITH_REJECTS);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers — seeding
    // ---------------------------------------------------------------------------------------------

    /** Inserts a fully populated, test-owned account and its card cross-reference. */
    private void seedAccountAndXref(long acctId, String cardNum, long custId,
                                    String expirationDate, BigDecimal creditLimit) {
        accountRepository.save(new Account(
                acctId, "Y", scaled("0.00"), creditLimit, scaled("5000.00"),
                "2020-01-01", expirationDate, "2020-01-01",
                scaled("0.00"), scaled("0.00"), "00000", "DEFAULT", null));
        cardXrefRepository.save(new CardXref(cardNum, custId, acctId));
    }

    /** Builds a staging {@link DailyTransaction} (type {@code 01}/category {@code 1}) with a 26-char origin timestamp. */
    private DailyTransaction daily(long idSequence, String cardNum, BigDecimal amount) {
        return new DailyTransaction(
                tranId(idSequence), "01", 1, "POS", "TEST PURCHASE", amount,
                123_456_789L, "TEST MERCHANT", "TEST CITY", "00000",
                cardNum, ts(ORIG_TS_DATE), "");
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers — reject-object byte assertions (Gate 1/4 fidelity)
    // ---------------------------------------------------------------------------------------------

    /** Returns the keys of every reject object currently under the {@code rejects/dalyrejs/} prefix. */
    private List<String> listRejectObjectKeys() {
        try (S3Client s3 = newS3Client()) {
            return s3.listObjectsV2(ListObjectsV2Request.builder()
                            .bucket(outputBucket())
                            .prefix(REJECT_KEY_PREFIX)
                            .build())
                    .contents().stream()
                    .map(S3Object::key)
                    .toList();
        }
    }

    /**
     * Asserts that the run produced exactly one reject object whose length is a whole number of
     * 431-byte framed records, and returns its raw bytes.
     */
    private byte[] readSingleRejectObject() {
        try (S3Client s3 = newS3Client()) {
            List<S3Object> objects = s3.listObjectsV2(ListObjectsV2Request.builder()
                            .bucket(outputBucket())
                            .prefix(REJECT_KEY_PREFIX)
                            .build())
                    .contents();
            assertThat(objects).as("exactly one versioned DALYREJS object per run").hasSize(1);
            String key = objects.get(0).key();
            assertThat(key).as("object key maps the DALYREJS GDG dataset").startsWith(REJECT_KEY_PREFIX);

            byte[] content = s3.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(outputBucket())
                            .key(key)
                            .build())
                    .asByteArray();
            assertThat(content.length % FRAMED_WIDTH)
                    .as("reject object must be a whole number of 431-byte framed records")
                    .isZero();
            return content;
        }
    }

    /** Convenience for the single-reject cascade tests: asserts one framed record carrying {@code reason}. */
    private void assertSingleRejectRecord(String rejectedTranId, RejectReasonCode reason) {
        byte[] content = readSingleRejectObject();
        assertThat(content).as("one rejected record => 1×431 bytes").hasSize(FRAMED_WIDTH);
        assertRejectRecordAt(content, 0, rejectedTranId, reason);
    }

    /**
     * Asserts the framed 430-byte reject record at {@code index} against the byte-exact CBTRN02C layout:
     * the 350-byte {@code CVTRA06Y} image, the 4-digit zero-padded reason code, the 76-char space-padded
     * reason description, and the trailing line-feed frame byte. The expected image is reconstructed by
     * re-rendering the rejected row read back from the database, which the posting step never deletes —
     * guaranteeing a byte-identical comparison regardless of fixed-width column padding.
     */
    private void assertRejectRecordAt(byte[] content, int index, String rejectedTranId, RejectReasonCode reason) {
        int offset = index * FRAMED_WIDTH;

        String image = new String(content, offset, IMAGE_WIDTH, StandardCharsets.US_ASCII);
        String code = new String(content, offset + CODE_OFFSET, CODE_WIDTH, StandardCharsets.US_ASCII);
        String description = new String(content, offset + DESC_OFFSET, DESC_WIDTH, StandardCharsets.US_ASCII);

        DailyTransaction rejected = dailyTransactionRepository.findById(rejectedTranId)
                .orElseThrow(() -> new AssertionError(
                        "rejected daily row must remain for image reconstruction: " + rejectedTranId));
        String expectedImage = DailyTransactionRecordImage.render(rejected);

        assertThat(image)
                .as("record %d: 350-byte CVTRA06Y DALYTRAN image", index)
                .isEqualTo(expectedImage);
        assertThat(code)
                .as("record %d: 4-digit zero-padded reason code", index)
                .isEqualTo(reason.getFormattedCode());
        assertThat(description)
                .as("record %d: 76-char space-padded reason description", index)
                .isEqualTo(padRight(reason.getDescription(), DESC_WIDTH));
        assertThat(content[offset + RECORD_WIDTH])
                .as("record %d: trailing line-feed frame byte", index)
                .isEqualTo((byte) '\n');
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers — metrics and small utilities
    // ---------------------------------------------------------------------------------------------

    /** Sums the count of every Micrometer counter registered under {@code name} (tag-agnostic). */
    private double counterTotal(String name) {
        return meterRegistry.find(name).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    /** Formats a 16-character zero-padded transaction id ({@code DALYTRAN-ID} / {@code TRAN-ID}). */
    private static String tranId(long sequence) {
        return String.format("%016d", sequence);
    }

    /** Builds a 26-character {@code YYYY-MM-DD HH:MM:SS.mmmmmm} timestamp from a {@code YYYY-MM-DD} date. */
    private static String ts(String date) {
        return date + " 00:00:00.000000";
    }

    /** Returns {@code value} as a {@link BigDecimal} scaled to 2 places using banker's rounding. */
    private static BigDecimal scaled(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** Left-justifies {@code value} in a field of {@code width}, padding with spaces or truncating. */
    private static String padRight(String value, int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }
}
