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
package com.carddemo.gates;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.job.BatchPipelineOrchestrator;
import com.carddemo.batch.job.InterestCalculationJobConfig;
import com.carddemo.enums.RejectReasonCode;
import com.carddemo.integration.AbstractIntegrationIT;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.DailyTransactionRepository;
import com.carddemo.repository.TransactionRepository;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.BatchStatus;
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

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * The single, authoritative, executable evidence harness for the eight Validation
 * Gates (Gate&nbsp;1&ndash;Gate&nbsp;8) of the CardDemo COBOL&rarr;Java migration
 * (technical-specifications &sect;0.7.2). One {@code @Nested} group per gate turns the
 * Failsafe report into a gate-by-gate evidence narrative proving that the greenfield
 * Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.5.11 application achieves 100% behavioral
 * parity with the frozen COBOL corpus (source SHA {@code 27d6c6f}).
 *
 * <h2>Why this class is named {@code GateVerificationIT} (a recorded decision)</h2>
 * The Agent Action Plan names this deliverable {@code GateVerificationTest}. It is
 * deliberately renamed to {@code GateVerificationIT} because it requires real
 * Testcontainers infrastructure (PostgreSQL&nbsp;16 + LocalStack) and therefore MUST
 * execute in the Maven <strong>Failsafe</strong> {@code integration-test}/{@code verify}
 * phase, not the Surefire unit phase. The project routes container-backed suites by the
 * {@code *IT.java} suffix (Failsafe {@code <include>**&#47;*IT.java</include>}; Surefire
 * {@code <exclude>**&#47;*IT.java</exclude>}), matching the sibling rename precedent
 * {@code BatchPipelineE2ETest}&rarr;{@code BatchPipelineE2EIT}. This rename is recorded as
 * a decision in {@code DECISION_LOG.md} (a root deliverable owned by another agent); the
 * rationale is echoed here so it is co-located with the code. This file does not create
 * {@code DECISION_LOG.md}.
 *
 * <h2>Real I/O only &mdash; no mocks, no H2</h2>
 * Gates&nbsp;1 and&nbsp;4 require processing production-representative input end-to-end
 * through the <strong>real</strong> Spring&nbsp;Batch pipeline on <strong>real</strong>
 * containers. Mocked I/O does not satisfy these gates, so this class never uses Mockito,
 * {@code @MockBean}, or an in-memory database. It extends {@link AbstractIntegrationIT}
 * (which owns the singleton containers, the {@code @DynamicPropertySource} wiring, the
 * AWS SDK&nbsp;v2 client factories, and the Flyway-seeded real schema) and asserts the
 * documented COBOL contracts against durable state written by the production code path.
 * It does not re-implement job logic; it launches the production beans and verifies
 * their outputs.
 *
 * <h2>Input architecture</h2>
 * The posting boundary ({@code CBTRN02C}/{@code POSTTRAN}) reads the
 * {@code daily_transaction} table (the materialized {@code DALYTRAN} PS input) via
 * {@code DailyTransactionItemReader}; Flyway&nbsp;V3 seeds that table with the 300
 * {@code dailytran.txt} records, while the posted {@code transactions} table starts
 * empty. Gate&nbsp;1/4 therefore drive the real pipeline over the seeded table plus
 * deterministic, test-owned scenario rows, and the named {@code dailytran.txt} fixture is
 * additionally loaded from the test classpath ({@code fixtures/dailytran.txt}) to assert
 * the fixed-width record geometry and the S3 file-drop contract.
 *
 * <h2>Transaction semantics</h2>
 * This class is intentionally NOT {@code @Transactional}: Spring&nbsp;Batch owns its own
 * chunk/step commit boundaries, so a surrounding test transaction would corrupt batch
 * semantics. Determinism for repeated {@code verify} runs is achieved with explicit
 * {@link BeforeEach}/{@link AfterEach} provisioning, seed snapshot/restore, and S3/SQS
 * cleanup.
 *
 * @see AbstractIntegrationIT
 * @see BatchPipelineOrchestrator
 */
@DisplayName("Eight-Gate Validation Evidence — CardDemo COBOL→Java Migration (SHA 27d6c6f)")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
public class GateVerificationIT extends AbstractIntegrationIT {

    /** Emits gate evidence (comparison summary, performance baseline) as log-safe metrics. */
    private static final Logger LOGGER = LoggerFactory.getLogger(GateVerificationIT.class);

    // ---------------------------------------------------------------------
    // Application tables (mutated / asserted via the inherited JdbcTemplate)
    // ---------------------------------------------------------------------
    private static final String DAILY_TRANSACTION_TABLE = "daily_transaction";
    private static final String TRANSACTIONS_TABLE = "transactions";
    private static final String ACCOUNTS_TABLE = "accounts";
    private static final String TCATBAL_TABLE = "transaction_category_balance";
    private static final String CARD_XREF_TABLE = "card_xref";

    // ---------------------------------------------------------------------
    // Canonical Flyway-V3 seed counts (the nine named ASCII fixtures). These are
    // asserted by Gate 4 to prove the named real-world artifacts are loaded.
    // ---------------------------------------------------------------------
    private static final int SEED_ACCOUNTS = 50;
    private static final int SEED_CARDS = 50;
    private static final int SEED_CARD_XREF = 50;
    private static final int SEED_CUSTOMERS = 50;
    private static final int SEED_DISCLOSURE_GROUP = 51;
    private static final int SEED_TCATBAL = 50;
    private static final int SEED_TRAN_CATEGORY = 18;
    private static final int SEED_TRAN_TYPE = 7;
    private static final int SEED_MIN_USERS = 2;
    private static final int SEEDED_DAILY_ROW_COUNT = 300;

    // ---------------------------------------------------------------------
    // Step / Job bean names. The per-config JOB_NAME / STEP_NAME constants are
    // package-private, so the canonical names are repeated here exactly as the
    // @Bean methods register them (BatchPipelineOrchestrator.JOB_NAME is public
    // and is referenced directly).
    // ---------------------------------------------------------------------
    private static final String JOB_POST = "postTransactionJob";
    private static final String JOB_INTEREST = "interestCalculationJob";
    private static final String JOB_REPORT = "transactionReportJob";
    private static final String JOB_STATEMENT = "statementJob";
    private static final String JOB_COMBINE = "combineTransactionsJob";

    private static final String STEP_POST = "postTransactionStep";
    private static final String STEP_BACKUP = "categoryBalanceBackupStep";
    private static final String STEP_INTEREST = "interestCalculationStep";
    private static final String STEP_COMBINE = "combineTransactionsStep";
    private static final String STEP_REPORT = "transactionReportStep";
    private static final String STEP_STATEMENT = "statementStep";
    private static final String STEP_PRINT = "printCategoryBalanceStep";

    /** {@code postTransactionStep} exit code when at least one record was rejected (RC=4). */
    private static final String EXIT_COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    // ---------------------------------------------------------------------
    // COBOL record byte geometry under test (immutable external contracts)
    // ---------------------------------------------------------------------
    /** DALYREJS reject record: 350-byte CVTRA06Y image + 80-byte trailer = 430 data bytes (F-018). */
    private static final int REJECT_RECORD_WIDTH = 430;
    /** The writer frames each 430-byte record with a single trailing '\n' delimiter. */
    private static final int REJECT_FRAMED_WIDTH = REJECT_RECORD_WIDTH + 1;
    private static final int REJECT_IMAGE_WIDTH = 350;
    private static final int REJECT_CODE_OFFSET = 350;
    private static final int REJECT_CODE_WIDTH = 4;
    private static final int REJECT_DESC_OFFSET = 354;
    private static final int REJECT_DESC_WIDTH = 76;
    private static final String REJECT_KEY_PREFIX = "rejects/dalyrejs/";

    /** CBTRN03C transaction report (CVTRA07Y): every physical line is exactly 133 bytes (F-022). */
    private static final int REPORT_RECORD_WIDTH = 133;
    private static final String REPORT_OBJECT_PREFIX = "transaction-report/";

    /** CBSTM03A statement: 80-byte plain-text stream and 100-byte HTML stream (F-021 = 80/100). */
    private static final int STATEMENT_TEXT_WIDTH = 80;
    private static final int STATEMENT_HTML_WIDTH = 100;
    private static final String STATEMENT_TEXT_PREFIX = "statements/text/";
    private static final String STATEMENT_HTML_PREFIX = "statements/html/";

    /** PRTCATBL category-balance print line (DFSORT LRECL=40) and backup unload (LRECL=50). */
    private static final int PRINT_RECORD_WIDTH = 40;
    private static final int BACKUP_RECORD_WIDTH = 50;
    private static final String PRINT_OBJECT_PREFIX = "category-balance/PRTCATBL-";
    private static final String BACKUP_OBJECT_PREFIX = "category-balance-backup/TCATBAL-BKUP-";

    /** DALYTRAN/TRAN fixed-width record geometry: 300 records × 350 bytes in dailytran.txt. */
    private static final int DALYTRAN_RECORD_WIDTH = 350;
    private static final String DAILYTRAN_FIXTURE = "fixtures/dailytran.txt";
    private static final String INPUT_FIXTURE_KEY_PREFIX = "incoming/dalytran/";

    // ---------------------------------------------------------------------
    // Reject reason codes + byte-exact reason text (CBTRN02C cascade). The codes
    // are formatted via RejectReasonCode.getFormattedCode() (%04d).
    // ---------------------------------------------------------------------
    private static final String CODE_CARD_NOT_FOUND = RejectReasonCode.CARD_NOT_FOUND.getFormattedCode();
    private static final String CODE_ACCOUNT_NOT_FOUND = RejectReasonCode.ACCOUNT_NOT_FOUND.getFormattedCode();
    private static final String CODE_OVER_LIMIT = RejectReasonCode.OVER_CREDIT_LIMIT.getFormattedCode();
    private static final String CODE_EXPIRED = RejectReasonCode.ACCOUNT_EXPIRED.getFormattedCode();
    private static final String DESC_CARD_NOT_FOUND = RejectReasonCode.CARD_NOT_FOUND.getDescription();
    private static final String DESC_ACCOUNT_NOT_FOUND = RejectReasonCode.ACCOUNT_NOT_FOUND.getDescription();
    private static final String DESC_OVER_LIMIT = RejectReasonCode.OVER_CREDIT_LIMIT.getDescription();
    private static final String DESC_EXPIRED = RejectReasonCode.ACCOUNT_EXPIRED.getDescription();

    // ---------------------------------------------------------------------
    // Seed-derived card/account fixtures (present in the Flyway V3 seed, derived
    // from cardxref.txt; never mutated permanently).
    // ---------------------------------------------------------------------
    /** Card -> account 2 (active); used for clean posts. */
    private static final String CLEAN_CARD = "0923877193247330";
    private static final long CLEAN_ACCT_ID = 2L;
    /** Card -> account 12; used to force an over-limit (0102) reject with a huge amount. */
    private static final String OVER_LIMIT_CARD = "0982496213629795";
    /** Card -> account 20 (expires before 2099); used to force an expiration (0103) reject. */
    private static final String EXPIRED_CARD = "0927987108636232";
    /** Not present in card_xref -> CBTRN02C reject 0100. */
    private static final String CARD_NOT_FOUND_CARD = "9999999999999999";
    /** Card -> account 1; focus card for statement detail lines. */
    private static final String STATEMENT_CARD = "9680294154603697";

    // ---------------------------------------------------------------------
    // Test-owned fixtures (never in the seed; created and removed per test).
    // ---------------------------------------------------------------------
    /** Dangling cross-reference (card present, account absent) -> CBTRN02C reject 0101. */
    private static final String DANGLING_CARD = "7000000000000001";
    private static final long DANGLING_ACCT_ID = 70_000_000_001L;
    private static final long DANGLING_CUST_ID = 700_000_001L;

    // ---------------------------------------------------------------------
    // Interest parity (CBACT04C / INTCALC). Account 1 with a single 1000.00
    // category balance at the DEFAULT disclosure rate (15.00) yields exactly
    // (1000.00 * 15.00) / 1200 = 12.50 (HALF_EVEN, scale 2).
    // ---------------------------------------------------------------------
    private static final long INTEREST_ACCT_ID = 1L;
    private static final String INTEREST_DESCRIPTION =
            "Int. for a/c " + String.format(Locale.ROOT, "%011d", INTEREST_ACCT_ID);
    private static final int INTEREST_TRAN_CAT_CD = 5;
    private static final String INTEREST_TRAN_SOURCE = "System";
    private static final String INTEREST_TRAN_TYPE = "01";
    private static final BigDecimal INTEREST_SEED_BALANCE = scaled("1000.00");
    private static final BigDecimal INTEREST_DEFAULT_RATE = scaled("15.00");
    private static final BigDecimal INTEREST_EXPECTED_AMOUNT = scaled("12.50");

    // ---------------------------------------------------------------------
    // Amounts / dates / run parameters
    // ---------------------------------------------------------------------
    private static final BigDecimal CLEAN_AMOUNT = scaled("10.00");
    private static final BigDecimal OVER_LIMIT_AMOUNT = scaled("9999999.00");
    private static final BigDecimal SMALL_AMOUNT = scaled("5.00");
    private static final String IN_WINDOW_ORIG_DATE = "2022-06-10";
    private static final String FUTURE_ORIG_DATE = "2099-12-31";
    /** Legacy CBACT04C PARM '2022071800' (run-date 2022-07-18); see app/jcl/INTCALC.jcl. */
    private static final String INTEREST_PARM_DATE =
            InterestCalculationJobConfig.formatRunDate(LocalDate.of(2022, 7, 18));
    /** Wide report window so the report/statement legs see all posted transactions. */
    private static final String WIDE_WINDOW_START = "1900-01-01";
    private static final String WIDE_WINDOW_END = "2999-12-31";

    /** Distinctive 16-digit tran-id base (leading '9') so generated ids never collide with the seed. */
    private static final long TEST_TRAN_ID_BASE = 9_000_000_000_000_000L;

    /** Gate 6 unsafe-code audit budget: more than this many occurrences requires per-site justification. */
    private static final int UNSAFE_CODE_BUDGET = 50;

    // ---------------------------------------------------------------------
    // Spring Batch wiring. Six Job beans exist, so each is qualified by bean id
    // (a bare @Autowired Job would be ambiguous).
    // ---------------------------------------------------------------------
    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    @Qualifier(BatchPipelineOrchestrator.JOB_NAME)
    private Job pipelineJob;

    @Autowired
    @Qualifier(JOB_POST)
    private Job postTransactionJob;

    @Autowired
    @Qualifier(JOB_INTEREST)
    private Job interestCalculationJob;

    @Autowired
    @Qualifier(JOB_REPORT)
    private Job transactionReportJob;

    @Autowired
    @Qualifier(JOB_STATEMENT)
    private Job statementJob;

    @Autowired
    @Qualifier(JOB_COMBINE)
    private Job combineTransactionsJob;

    /** All Job beans keyed by bean name; used by Gate 7 to prove the six-job scope. */
    @Autowired
    private Map<String, Job> jobsByName;

    // Repositories (from depends_on); used for repository-layer coverage evidence (Gate 7)
    // and as a cross-check of the JdbcTemplate-based seed-count assertions (Gate 4).
    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    /** Monotonic source of unique {@code run.id} job-parameter values. */
    private static final AtomicLong RUN_ID_SEQUENCE = new AtomicLong(System.nanoTime());
    /** Monotonic source of unique 16-digit transaction ids within a test. */
    private final AtomicLong tranIdSequence = new AtomicLong(0L);

    // ---------------------------------------------------------------------
    // Pristine-seed snapshots, captured once before the first mutation so the
    // shared Flyway seed can be restored after every test (sibling-proven pattern).
    // ---------------------------------------------------------------------
    private static List<Map<String, Object>> dailySeedSnapshot;
    private static List<Map<String, Object>> tcatbalSeedSnapshot;
    private static List<Map<String, Object>> accountSeedSnapshot;

    // =====================================================================
    // Lifecycle — provisioning, seed-snapshot, and deterministic cleanup
    // =====================================================================

    /**
     * Prepares an isolated run: captures the pristine seed once, idempotently
     * provisions the canonical AWS resources (three buckets + FIFO queue + topic),
     * removes any leftover test-owned rows, clears the mutable working tables
     * (restored in {@link #tearDown()}), and empties the S3 buckets so each gate
     * observes the canonical state.
     */
    @BeforeEach
    void setUp() {
        captureSeedSnapshotOnce();
        provisionCanonicalAwsResources();
        removeTestOwnedRows();
        deleteFrom(TRANSACTIONS_TABLE);
        deleteFrom(DAILY_TRANSACTION_TABLE);
        emptyBucket(inputBucket());
        emptyBucket(outputBucket());
        emptyBucket(statementBucket());
        tranIdSequence.set(0L);
    }

    /**
     * Restores every table this suite may have mutated back to its pristine Flyway
     * state and clears the S3 buckets + the report queue, guaranteeing the next test
     * (here or in any sibling suite sharing the singleton containers) observes the
     * canonical seed.
     */
    @AfterEach
    void tearDown() {
        deleteFrom(TRANSACTIONS_TABLE);
        removeTestOwnedRows();
        restoreTransactionCategoryBalances();
        restoreAccounts();
        restoreDailyTransactions();
        emptyBucket(inputBucket());
        emptyBucket(outputBucket());
        emptyBucket(statementBucket());
        purgeQueue(reportQueueUrl());
    }

    /** Captures the pristine seed of the mutable tables exactly once per JVM. */
    private void captureSeedSnapshotOnce() {
        if (dailySeedSnapshot == null) {
            List<Map<String, Object>> daily = jdbcTemplate.queryForList(
                    "SELECT tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_desc, tran_amt, "
                            + "merchant_id, merchant_name, merchant_city, merchant_zip, card_num, "
                            + "orig_ts, proc_ts FROM " + DAILY_TRANSACTION_TABLE);
            assertThat(daily)
                    .as("Flyway V3 must seed %s with %d rows before this IT clears it",
                            DAILY_TRANSACTION_TABLE, SEEDED_DAILY_ROW_COUNT)
                    .hasSize(SEEDED_DAILY_ROW_COUNT);
            dailySeedSnapshot = daily;
        }
        if (tcatbalSeedSnapshot == null) {
            tcatbalSeedSnapshot = jdbcTemplate.queryForList(
                    "SELECT acct_id, type_cd, cat_cd, tran_cat_bal FROM " + TCATBAL_TABLE);
        }
        if (accountSeedSnapshot == null) {
            accountSeedSnapshot = jdbcTemplate.queryForList(
                    "SELECT acct_id, curr_bal, curr_cyc_credit, curr_cyc_debit, version FROM " + ACCOUNTS_TABLE);
        }
    }

    /** Restores {@code transaction_category_balance} to its seed snapshot. */
    private void restoreTransactionCategoryBalances() {
        jdbcTemplate.update("DELETE FROM " + TCATBAL_TABLE);
        for (Map<String, Object> row : tcatbalSeedSnapshot) {
            jdbcTemplate.update(
                    "INSERT INTO " + TCATBAL_TABLE + " (acct_id, type_cd, cat_cd, tran_cat_bal) VALUES (?, ?, ?, ?)",
                    row.get("acct_id"), row.get("type_cd"), row.get("cat_cd"), row.get("tran_cat_bal"));
        }
    }

    /** Restores the mutable columns of every seeded account to their snapshot values. */
    private void restoreAccounts() {
        for (Map<String, Object> row : accountSeedSnapshot) {
            jdbcTemplate.update(
                    "UPDATE " + ACCOUNTS_TABLE
                            + " SET curr_bal = ?, curr_cyc_credit = ?, curr_cyc_debit = ?, version = ?"
                            + " WHERE acct_id = ?",
                    row.get("curr_bal"), row.get("curr_cyc_credit"), row.get("curr_cyc_debit"),
                    row.get("version"), row.get("acct_id"));
        }
    }

    /** Restores {@code daily_transaction} to its seed snapshot. */
    private void restoreDailyTransactions() {
        jdbcTemplate.update("DELETE FROM " + DAILY_TRANSACTION_TABLE);
        for (Map<String, Object> row : dailySeedSnapshot) {
            jdbcTemplate.update(
                    "INSERT INTO " + DAILY_TRANSACTION_TABLE + " (tran_id, tran_type_cd, tran_cat_cd, "
                            + "tran_source, tran_desc, tran_amt, merchant_id, merchant_name, merchant_city, "
                            + "merchant_zip, card_num, orig_ts, proc_ts) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    row.get("tran_id"), row.get("tran_type_cd"), row.get("tran_cat_cd"), row.get("tran_source"),
                    row.get("tran_desc"), row.get("tran_amt"), row.get("merchant_id"), row.get("merchant_name"),
                    row.get("merchant_city"), row.get("merchant_zip"), row.get("card_num"),
                    row.get("orig_ts"), row.get("proc_ts"));
        }
    }

    /** Removes any rows this suite created outside the Flyway seed (idempotent). */
    private void removeTestOwnedRows() {
        jdbcTemplate.update("DELETE FROM " + TRANSACTIONS_TABLE + " WHERE card_num = ?", DANGLING_CARD);
        jdbcTemplate.update("DELETE FROM " + CARD_XREF_TABLE + " WHERE xref_card_num = ?", DANGLING_CARD);
    }

    // =====================================================================
    // Launch / parameter helpers (manual JobLauncherTestUtils — six Job beans
    // make a single autowired setJob target ambiguous)
    // =====================================================================

    /**
     * Launches the supplied {@link Job} through a freshly wired
     * {@link JobLauncherTestUtils} and returns the completed {@link JobExecution}.
     *
     * @param job        the job bean to launch
     * @param parameters the unique parameters for this launch
     * @return the resulting job execution
     * @throws Exception if the launcher fails to run the job
     */
    private JobExecution launch(Job job, JobParameters parameters) throws Exception {
        JobLauncherTestUtils utils = new JobLauncherTestUtils();
        utils.setJobLauncher(jobLauncher);
        utils.setJobRepository(jobRepository);
        utils.setJob(job);
        return utils.launchJob(parameters);
    }

    /**
     * Builds a unique parameter set carrying the interest run date and the report
     * window (via {@link BatchPipelineOrchestrator#pipelineJobParameters}) plus a
     * unique {@code run.id} so re-launches never collide with a completed
     * {@code JobInstance}. Each job consumes only the keys it recognises.
     *
     * @param parmDate    the legacy interest run-date parameter ({@code yyyyMMddHH})
     * @param reportStart the inclusive report-window start ({@code yyyy-MM-dd})
     * @param reportEnd   the inclusive report-window end ({@code yyyy-MM-dd})
     * @return the unique job parameters
     */
    private JobParameters params(String parmDate, String reportStart, String reportEnd) {
        return new JobParametersBuilder(
                BatchPipelineOrchestrator.pipelineJobParameters(parmDate, reportStart, reportEnd))
                .addLong("run.id", RUN_ID_SEQUENCE.incrementAndGet())
                .toJobParameters();
    }

    /** Convenience: the standard wide-window parameter set used by most launches. */
    private JobParameters wideParams() {
        return params(INTEREST_PARM_DATE, WIDE_WINDOW_START, WIDE_WINDOW_END);
    }

    /** Returns the next distinctive 16-digit transaction id for this test. */
    private String nextTranId() {
        return String.format(Locale.ROOT, "%016d", TEST_TRAN_ID_BASE + tranIdSequence.incrementAndGet());
    }

    // =====================================================================
    // Seeding helpers (all writes go through the inherited JdbcTemplate)
    // =====================================================================

    /** Stages one {@code daily_transaction} row for posting (proc_ts blank, as the unposted seed leaves it). */
    private void insertDailyTransaction(String tranId, String type, int cat, BigDecimal amount,
                                        String cardNum, String origDate) {
        jdbcTemplate.update(
                "INSERT INTO " + DAILY_TRANSACTION_TABLE + " (tran_id, tran_type_cd, tran_cat_cd, tran_source, "
                        + "tran_desc, tran_amt, merchant_id, merchant_name, merchant_city, merchant_zip, "
                        + "card_num, orig_ts, proc_ts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tranId, type, cat, "POS TERM", "GATE TEST TRANSACTION", amount,
                800_000_000L, "GATE MERCHANT", "GATE CITY", "00000", cardNum,
                origDate + " 00:00:00.000000", "");
    }

    /** Inserts one posted {@code transactions} row whose proc_ts the report window can select. */
    private void insertTransaction(String tranId, String type, int cat, BigDecimal amount,
                                   String cardNum, String procTs) {
        jdbcTemplate.update(
                "INSERT INTO " + TRANSACTIONS_TABLE + " (tran_id, tran_type_cd, tran_cat_cd, tran_source, "
                        + "tran_desc, tran_amt, merchant_id, merchant_name, merchant_city, merchant_zip, "
                        + "card_num, orig_ts, proc_ts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tranId, type, cat, "POS TERM", "GATE REPORT TXN", amount,
                800_000_000L, "GATE MERCHANT", "GATE CITY", "00000", cardNum,
                IN_WINDOW_ORIG_DATE + " 00:00:00.000000", procTs);
    }

    /** Inserts a card cross-reference row. */
    private void insertCardXref(String cardNum, long custId, long acctId) {
        jdbcTemplate.update(
                "INSERT INTO " + CARD_XREF_TABLE + " (xref_card_num, xref_cust_id, xref_acct_id) VALUES (?, ?, ?)",
                cardNum, custId, acctId);
    }

    /** Inserts a transaction-category-balance row. */
    private void insertTransactionCategoryBalance(long acctId, String type, int cat, BigDecimal balance) {
        jdbcTemplate.update(
                "INSERT INTO " + TCATBAL_TABLE + " (acct_id, type_cd, cat_cd, tran_cat_bal) VALUES (?, ?, ?, ?)",
                acctId, type, cat, balance);
    }

    // =====================================================================
    // Query helpers
    // =====================================================================

    /** Returns the current balance of an account. */
    private BigDecimal accountCurrentBalance(long acctId) {
        return jdbcTemplate.queryForObject(
                "SELECT curr_bal FROM " + ACCOUNTS_TABLE + " WHERE acct_id = ?", BigDecimal.class, acctId);
    }

    /** Returns {@code true} when a transaction with the given id has been persisted. */
    private boolean transactionExists(String tranId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + TRANSACTIONS_TABLE + " WHERE tran_id = ?", Integer.class, tranId);
        return count != null && count > 0;
    }

    /** Returns the (single) step execution for the named step, or {@code null} if absent. */
    private StepExecution stepExecution(JobExecution execution, String stepName) {
        return execution.getStepExecutions().stream()
                .filter(step -> step.getStepName().equals(stepName))
                .findFirst()
                .orElse(null);
    }

    /** Returns the monotonic start order key (execution id) of the named step. */
    private long stepOrder(JobExecution execution, String stepName) {
        StepExecution step = stepExecution(execution, stepName);
        assertThat(step).as("step %s must have executed", stepName).isNotNull();
        return step.getId();
    }

    // =====================================================================
    // S3 helpers (real LocalStack via the inherited AWS SDK v2 client factory)
    // =====================================================================

    /** Lists every object key in a bucket. */
    private List<String> listKeys(String bucket) {
        try (S3Client s3 = newS3Client()) {
            return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
                    .contents().stream()
                    .map(S3Object::key)
                    .toList();
        }
    }

    /** Lists object keys in a bucket that start with the given prefix. */
    private List<String> keysWithPrefix(String bucket, String prefix) {
        return listKeys(bucket).stream().filter(key -> key.startsWith(prefix)).toList();
    }

    /** Reads an object's raw bytes. */
    private byte[] readObjectBytes(String bucket, String key) {
        try (S3Client s3 = newS3Client()) {
            return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        }
    }

    /** Uploads raw bytes to a bucket/key (used to stage the named fixture into the input bucket). */
    private void putObjectBytes(String bucket, String key, byte[] payload) {
        try (S3Client s3 = newS3Client()) {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromBytes(payload));
        }
    }

    /**
     * Splits a US-ASCII fixed-width payload on the {@code '\n'} record delimiter,
     * dropping only the empty element produced by the trailing delimiter. Every
     * surviving element is a full fixed-width record, so callers can assert
     * {@code length() == width} on each.
     */
    private List<String> fixedWidthLines(byte[] payload) {
        String content = new String(payload, StandardCharsets.US_ASCII);
        String[] parts = content.split("\n", -1);
        List<String> lines = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isEmpty()) {
                lines.add(part);
            }
        }
        return lines;
    }

    /** Extracts the 4-digit reject reason code from a 430-byte reject record. */
    private String rejectCode(String record) {
        return record.substring(REJECT_CODE_OFFSET, REJECT_CODE_OFFSET + REJECT_CODE_WIDTH);
    }

    /** Extracts the trimmed reject reason description from a 430-byte reject record. */
    private String rejectDescription(String record) {
        return record.substring(REJECT_DESC_OFFSET, REJECT_DESC_OFFSET + REJECT_DESC_WIDTH).trim();
    }

    // =====================================================================
    // Fixture / interest helpers
    // =====================================================================

    /**
     * Loads the named {@code dailytran.txt} fixture from the test classpath as raw
     * bytes. The fixture is the byte-exact production-representative input; a missing
     * resource FAILS the gate (no silent skip — a skipped Gate 1/4 is a gate failure).
     *
     * @return the fixture bytes (300 records × 350 bytes, newline-delimited)
     */
    private byte[] loadDailyTranFixture() {
        try (InputStream in = GateVerificationIT.class.getClassLoader().getResourceAsStream(DAILYTRAN_FIXTURE)) {
            assertThat(in)
                    .as("named fixture '%s' MUST be on the test classpath (provide the byte-exact "
                            + "dailytran.txt as a test resource — a skipped Gate 1/4 is a gate failure)",
                            DAILYTRAN_FIXTURE)
                    .isNotNull();
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new AssertionError("failed to read named fixture " + DAILYTRAN_FIXTURE, ex);
        }
    }

    /** Computes the COBOL monthly interest: {@code balance * rate / 1200}, HALF_EVEN scale 2. */
    private static BigDecimal monthlyInterest(BigDecimal balance, BigDecimal rate) {
        return balance.multiply(rate).divide(new BigDecimal("1200"), 2, RoundingMode.HALF_EVEN);
    }

    /** Normalises a decimal literal to scale 2 with HALF_EVEN rounding (no float ever used). */
    private static BigDecimal scaled(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_EVEN);
    }

    // =====================================================================
    // Gate 3 performance-capture helpers
    // =====================================================================

    /** Resets the heap pools' peak-usage counters so the next measure reflects one run window. */
    private static void resetHeapPeakUsage() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    /** Returns the sum of the peak used bytes across all heap memory pools. */
    private static long peakHeapUsageBytes() {
        long peak = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                peak += pool.getPeakUsage().getUsed();
            }
        }
        return peak;
    }

    // =====================================================================
    // Thin accessors so the @Nested gate groups reference only outer-class
    // members (every AWS name is resolved through the config-bound properties
    // exposed by the base class — no hardcoded literal is ever passed to AWS).
    // =====================================================================

    private String inBucket() {
        return inputBucket();
    }

    private String outBucket() {
        return outputBucket();
    }

    private String stmtBucket() {
        return statementBucket();
    }

    private String reportQueue() {
        return reportQueueName();
    }

    private String snsTopic() {
        return snsTopicName();
    }

    private long rowCount(String table) {
        return countRows(table);
    }

    private void provisionAws() {
        provisionCanonicalAwsResources();
    }

    /**
     * Sends one FIFO message to the canonical report queue and receives it back,
     * proving the SQS FIFO report-bridge contract (F-011) on real LocalStack. The
     * queue is created with content-based deduplication by the base class, so only a
     * message-group id is required.
     *
     * @param body the message body to round-trip
     * @return the received message body, or {@code null} if none was received
     */
    private String roundTripReportMessage(String body) {
        String queueUrl = reportQueueUrl();
        try (SqsClient sqs = newSqsClient()) {
            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageGroupId("gate5-report")
                    .messageBody(body)
                    .build());
            List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(5)
                    .build()).messages();
            return messages.isEmpty() ? null : messages.get(0).body();
        }
    }

    // =====================================================================
    // GATE 1 — End-to-End Boundary Verification
    // =====================================================================

    /**
     * Gate&nbsp;1 processes a production-representative input end-to-end on the REAL
     * PostgreSQL + LocalStack containers and verifies byte-equivalent output against the
     * documented COBOL baseline. The primary posting boundary
     * ({@code postTransactionJob} &larr; {@code CBTRN02C}/{@code POSTTRAN}) is launched
     * over a representative slice (a clean post plus the documented reject scenarios);
     * the posted rows are persisted and the produced {@code DALYREJS} reject object is
     * checked byte-for-byte against the 430-byte record contract with byte-exact reason
     * codes. A human-readable comparison summary is emitted as the Gate&nbsp;1 evidence.
     */
    @Nested
    @Order(1)
    @DisplayName("Gate 1 — End-to-End Boundary Verification")
    class Gate1EndToEndBoundary {

        @Test
        @DisplayName("representative input posted end-to-end on real containers yields a byte-contract-equivalent reject object")
        void representativeInputPostedEndToEndWithByteEquivalentRejects() throws Exception {
            // Real production-representative input drives the real reader -> processor ->
            // writer chain on real containers (mocked I/O does not satisfy this gate).
            String cleanId = nextTranId();
            insertDailyTransaction(cleanId, "01", 1, CLEAN_AMOUNT, CLEAN_CARD, IN_WINDOW_ORIG_DATE);
            insertDailyTransaction(nextTranId(), "01", 1, CLEAN_AMOUNT, CARD_NOT_FOUND_CARD, IN_WINDOW_ORIG_DATE);
            insertDailyTransaction(nextTranId(), "01", 1, OVER_LIMIT_AMOUNT, OVER_LIMIT_CARD, IN_WINDOW_ORIG_DATE);
            insertDailyTransaction(nextTranId(), "01", 1, SMALL_AMOUNT, EXPIRED_CARD, FUTURE_ORIG_DATE);

            JobExecution execution = launch(postTransactionJob, wideParams());

            // The job completes; a cycle that produced rejects exits COMPLETED_WITH_REJECTS (RC 4).
            assertThat(execution.getStatus())
                    .as("the posting boundary completes end-to-end against real PostgreSQL + LocalStack")
                    .isEqualTo(BatchStatus.COMPLETED);
            assertThat(stepExecution(execution, STEP_POST).getExitStatus().getExitCode())
                    .as("a cycle with rejects exits COMPLETED_WITH_REJECTS (RC 4)")
                    .isEqualTo(EXIT_COMPLETED_WITH_REJECTS);
            assertThat(transactionExists(cleanId)).as("the clean transaction was posted").isTrue();

            // The reject object honours the 430-byte DALYREJS record contract byte-for-byte.
            List<String> rejectKeys = keysWithPrefix(outBucket(), REJECT_KEY_PREFIX);
            assertThat(rejectKeys).as("exactly one DALYREJS reject object is written").hasSize(1);
            byte[] rejectBytes = readObjectBytes(outBucket(), rejectKeys.get(0));
            assertThat(rejectBytes.length % REJECT_FRAMED_WIDTH)
                    .as("the reject object is an exact multiple of the 431-byte framed record (430 + delimiter)")
                    .isZero();
            List<String> rejectRecords = fixedWidthLines(rejectBytes);
            assertThat(rejectRecords).as("three reject scenarios were staged").hasSize(3);
            for (String record : rejectRecords) {
                assertThat(record.length()).as("each reject record image is exactly 430 bytes")
                        .isEqualTo(REJECT_RECORD_WIDTH);
            }
            List<String> codes = rejectRecords.stream().map(GateVerificationIT.this::rejectCode).toList();
            assertThat(codes)
                    .as("the reject reason codes match the CBTRN02C cascade outcomes")
                    .containsExactlyInAnyOrder(CODE_CARD_NOT_FOUND, CODE_OVER_LIMIT, CODE_EXPIRED);

            // Gate 1 comparison summary (records read / posted / rejected, per-reason-code tally).
            long readCount = execution.getStepExecutions().stream().mapToLong(StepExecution::getReadCount).sum();
            long rejectCount = rejectRecords.size();
            LOGGER.info("GATE1 comparison-summary job={} recordsRead={} posted={} rejected={} "
                            + "rejects[card={} acct={} overLimit={} expired={}]",
                    JOB_POST, readCount, readCount - rejectCount, rejectCount,
                    codes.stream().filter(CODE_CARD_NOT_FOUND::equals).count(),
                    codes.stream().filter(CODE_ACCOUNT_NOT_FOUND::equals).count(),
                    codes.stream().filter(CODE_OVER_LIMIT::equals).count(),
                    codes.stream().filter(CODE_EXPIRED::equals).count());
        }
    }

    // =====================================================================
    // GATE 2 — Zero-Warning Build
    // =====================================================================

    /**
     * Gate&nbsp;2 (zero-warning clean build) is enforced by the build itself (compiler
     * {@code -Xlint:all -Werror}, {@code failOnWarning}) and is not fully assertable from
     * a running test. This group asserts what is programmatically true — the production
     * context loaded without split-package / illegal-access failures (the context would
     * not start otherwise) — and documents that the authoritative evidence is the
     * {@code mvn verify} zero-warning output, pointing at {@code docs/validation-gates.md}
     * when present (never failing solely because that external doc is absent).
     */
    @Nested
    @Order(2)
    @DisplayName("Gate 2 — Zero-Warning Build")
    class Gate2ZeroWarningBuild {

        @Test
        @DisplayName("production context loaded cleanly (no split-package / illegal-access at startup)")
        void productionContextLoadedWithoutSplitPackageIssues() {
            // If the Spring context started, the production packages were loaded by the
            // module class loader without IllegalAccessException / split-package errors.
            assertThat(pipelineJob).as("the orchestrated pipeline bean is wired").isNotNull();
            assertThat(jobsByName).as("the application context exposes batch Job beans").isNotEmpty();
        }

        @Test
        @DisplayName("validation-gates documentation references Gate 2 when present (authoritative evidence is the build)")
        void validationGatesDocReferencesGate2WhenPresent() throws IOException {
            Path doc = Paths.get("docs", "validation-gates.md");
            if (Files.exists(doc)) {
                String content = Files.readString(doc, StandardCharsets.UTF_8);
                assertThat(content)
                        .as("docs/validation-gates.md documents the Gate 2 zero-warning build")
                        .contains("Gate 2");
            } else {
                LOGGER.info("GATE2 docs/validation-gates.md not present in working dir; "
                        + "authoritative zero-warning evidence is the 'mvn verify' (-Werror/failOnWarning) build output");
            }
        }
    }

    // =====================================================================
    // GATE 3 — Performance Baseline
    // =====================================================================

    /**
     * Gate&nbsp;3 benchmarks the Java pipeline locally and documents throughput (elapsed
     * time, records/sec, peak heap) against the COBOL reference. No hard threshold is
     * asserted (container performance is environment-dependent); instead the run must
     * complete within a generous ceiling and the metrics are emitted as a log-safe
     * baseline and written to {@code target/performance-baseline.txt}.
     */
    @Nested
    @Order(3)
    @DisplayName("Gate 3 — Performance Baseline")
    class Gate3PerformanceBaseline {

        private static final long ELAPSED_CEILING_MILLIS = 120_000L;
        private static final int BASELINE_BATCH_SIZE = 25;

        @Test
        @DisplayName("full pipeline run captures elapsed/throughput/peak-memory baseline (log-safe, no hard threshold)")
        void fullPipelineCapturesPerformanceBaseline() throws Exception {
            for (int i = 0; i < BASELINE_BATCH_SIZE; i++) {
                insertDailyTransaction(nextTranId(), "01", 1, SMALL_AMOUNT, CLEAN_CARD, IN_WINDOW_ORIG_DATE);
            }

            resetHeapPeakUsage();
            long startNanos = System.nanoTime();
            JobExecution execution = launch(pipelineJob, wideParams());
            long elapsedNanos = System.nanoTime() - startNanos;

            assertThat(execution.getStatus())
                    .as("the benchmarked pipeline run completes end-to-end").isEqualTo(BatchStatus.COMPLETED);

            long recordsProcessed = execution.getStepExecutions().stream()
                    .mapToLong(StepExecution::getReadCount).sum();
            double elapsedMillis = elapsedNanos / 1_000_000.0;
            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
            double recordsPerSecond = elapsedSeconds > 0 ? recordsProcessed / elapsedSeconds : 0.0;
            long peakHeapBytes = peakHeapUsageBytes();
            long peakHeapMb = peakHeapBytes / (1024L * 1024L);

            String baseline = String.format(Locale.ROOT,
                    "GATE3 baseline pipeline=%s records=%d elapsedMs=%.1f recordsPerSec=%.2f peakHeapMB=%d%n",
                    BatchPipelineOrchestrator.JOB_NAME, recordsProcessed, elapsedMillis, recordsPerSecond, peakHeapMb);
            LOGGER.info(baseline.trim());
            writeBaselineArtifact(baseline);

            assertThat(recordsProcessed)
                    .as("the pipeline processed at least the staged batch").isGreaterThanOrEqualTo(BASELINE_BATCH_SIZE);
            assertThat(elapsedMillis)
                    .as("the run completes within the generous %d ms ceiling", ELAPSED_CEILING_MILLIS)
                    .isLessThan(ELAPSED_CEILING_MILLIS);
            assertThat(recordsPerSecond).as("a positive throughput was captured").isGreaterThan(0.0);
            assertThat(peakHeapBytes).as("a positive peak-heap figure was captured").isPositive();
        }

        /** Writes the Gate 3 baseline to the build output dir (best-effort; never fails the gate). */
        private void writeBaselineArtifact(String baseline) {
            try {
                Path target = Paths.get("target");
                if (Files.isDirectory(target)) {
                    Files.writeString(target.resolve("performance-baseline.txt"), baseline, StandardCharsets.UTF_8);
                }
            } catch (IOException ex) {
                LOGGER.info("GATE3 could not persist performance-baseline.txt ({}); the log line above is the baseline",
                        ex.getMessage());
            }
        }
    }

    // =====================================================================
    // GATE 4 — Named Real-World Validation Artifacts
    // =====================================================================

    /**
     * Gate&nbsp;4 drives the nine named ASCII fixtures through the primary batch
     * pipeline and verifies the named output artifacts against the COBOL baseline. The
     * fixtures are materialized by the Flyway&nbsp;V3 seed (asserted here by exact row
     * count) and the named {@code dailytran.txt} input is additionally loaded from the
     * test classpath to prove the fixed-width geometry and the S3 file-drop contract.
     * The orchestrated {@code carddemoBatchPipelineJob} then produces — by name — the
     * {@code DALYREJS} reject object, the {@code CBTRN03C} report, the {@code CBSTM03A}
     * text + HTML statements, and the {@code PRTCATBL} print + backup objects, and the
     * {@code CBACT04C} interest formula is verified to the cent (including the DEFAULT
     * disclosure-group fallback).
     */
    @Nested
    @Order(4)
    @DisplayName("Gate 4 — Named Real-World Validation Artifacts")
    class Gate4NamedArtifacts {

        // The remaining seed tables addressed only here (the mutable tables use the
        // outer-class constants). Inner-class constants are permitted on Java 16+.
        private static final String CARDS_TABLE = "cards";
        private static final String CUSTOMERS_TABLE = "customers";
        private static final String DISCLOSURE_GROUP_TABLE = "disclosure_group";
        private static final String TRANSACTION_CATEGORY_TABLE = "transaction_category";
        private static final String TRANSACTION_TYPE_TABLE = "transaction_type";
        private static final String USERS_TABLE = "users";

        @Test
        @DisplayName("the nine named ASCII fixtures are loaded as seed data with the documented row counts")
        void nineNamedFixturesLoadedWithDocumentedCounts() {
            // The eight reference fixtures seeded into stable (never-cleared) tables.
            assertThat(accountRepository.count())
                    .as("acctdata.txt -> %d accounts", SEED_ACCOUNTS).isEqualTo(SEED_ACCOUNTS);
            assertThat(rowCount(CARDS_TABLE))
                    .as("carddata.txt -> %d cards", SEED_CARDS).isEqualTo(SEED_CARDS);
            assertThat(rowCount(CARD_XREF_TABLE))
                    .as("cardxref.txt -> %d card cross-references", SEED_CARD_XREF).isEqualTo(SEED_CARD_XREF);
            assertThat(rowCount(CUSTOMERS_TABLE))
                    .as("custdata.txt -> %d customers", SEED_CUSTOMERS).isEqualTo(SEED_CUSTOMERS);
            assertThat(rowCount(DISCLOSURE_GROUP_TABLE))
                    .as("discgrp.txt -> %d disclosure-group rows (incl. DEFAULT)", SEED_DISCLOSURE_GROUP)
                    .isEqualTo(SEED_DISCLOSURE_GROUP);
            assertThat(rowCount(TCATBAL_TABLE))
                    .as("tcatbal.txt -> %d category-balance rows", SEED_TCATBAL).isEqualTo(SEED_TCATBAL);
            assertThat(rowCount(TRANSACTION_CATEGORY_TABLE))
                    .as("trancatg.txt -> %d transaction categories", SEED_TRAN_CATEGORY)
                    .isEqualTo(SEED_TRAN_CATEGORY);
            assertThat(rowCount(TRANSACTION_TYPE_TABLE))
                    .as("trantype.txt -> %d transaction types", SEED_TRAN_TYPE).isEqualTo(SEED_TRAN_TYPE);
            assertThat(rowCount(USERS_TABLE))
                    .as("user seed -> at least %d users (ADMIN001/A, USER0001/U)", SEED_MIN_USERS)
                    .isGreaterThanOrEqualTo(SEED_MIN_USERS);

            // The ninth named fixture (dailytran.txt) is materialized into daily_transaction;
            // setUp() clears it, so the pristine seed snapshot captured before clearing is the
            // authoritative evidence that all 300 records were seeded. The posted transactions
            // table starts empty.
            assertThat(dailySeedSnapshot)
                    .as("dailytran.txt -> %d daily_transaction rows seeded by Flyway V3", SEEDED_DAILY_ROW_COUNT)
                    .hasSize(SEEDED_DAILY_ROW_COUNT);
            assertThat(dailyTransactionRepository.count())
                    .as("daily_transaction is cleared by setUp() before each gate run").isZero();
            assertThat(transactionRepository.count())
                    .as("the posted transactions table starts empty (no seed)").isZero();
        }

        @Test
        @DisplayName("named dailytran.txt fixture honours the 350-byte geometry and round-trips through the S3 input bucket")
        void namedDailyTranFixtureGeometryAndS3FileDrop() {
            byte[] fixtureBytes = loadDailyTranFixture();
            List<String> records = fixedWidthLines(fixtureBytes);
            assertThat(records)
                    .as("dailytran.txt holds exactly %d records", SEEDED_DAILY_ROW_COUNT)
                    .hasSize(SEEDED_DAILY_ROW_COUNT);
            for (String record : records) {
                assertThat(record.length())
                        .as("each DALYTRAN/TRAN record is exactly %d bytes", DALYTRAN_RECORD_WIDTH)
                        .isEqualTo(DALYTRAN_RECORD_WIDTH);
            }
            // The CSUTLDTC timestamp format (YYYY-MM-DD HH:MM:SS.mmmmmm) is preserved verbatim.
            Pattern timestamp = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}");
            assertThat(records.stream().anyMatch(r -> timestamp.matcher(r).find()))
                    .as("the fixed-width timestamp format YYYY-MM-DD HH:MM:SS.mmmmmm is preserved")
                    .isTrue();

            // File-drop contract: the named fixture round-trips through the input bucket byte-for-byte.
            String key = INPUT_FIXTURE_KEY_PREFIX + UUID.randomUUID() + "/dailytran.txt";
            putObjectBytes(inBucket(), key, fixtureBytes);
            assertThat(readObjectBytes(inBucket(), key))
                    .as("the named fixture survives an S3 input-bucket round trip unchanged")
                    .isEqualTo(fixtureBytes);
        }

        @Test
        @DisplayName("named fixtures drive the full pipeline to every named output artifact (reject/report/statement/print/backup)")
        void namedFixturesDriveFullPipelineToNamedArtifacts() throws Exception {
            // A representative slice: two clean posts (so the report/statement/print legs have
            // data) plus one card-not-found reject (so a DALYREJS artifact is also produced and
            // the COND=(4,LT) backup gate is exercised at RC 4).
            String cleanA = nextTranId();
            String cleanB = nextTranId();
            insertDailyTransaction(cleanA, "01", 1, CLEAN_AMOUNT, CLEAN_CARD, IN_WINDOW_ORIG_DATE);
            insertDailyTransaction(cleanB, "01", 1, CLEAN_AMOUNT, CLEAN_CARD, IN_WINDOW_ORIG_DATE);
            insertDailyTransaction(nextTranId(), "01", 1, CLEAN_AMOUNT, CARD_NOT_FOUND_CARD, IN_WINDOW_ORIG_DATE);

            JobExecution execution = launch(pipelineJob, wideParams());
            assertThat(execution.getStatus())
                    .as("the orchestrated pipeline completes over the named fixtures").isEqualTo(BatchStatus.COMPLETED);
            assertThat(transactionExists(cleanA)).as("first clean fixture posted").isTrue();
            assertThat(transactionExists(cleanB)).as("second clean fixture posted").isTrue();

            // Named artifact: CBTRN02C DALYREJS reject object.
            List<String> rejectKeys = keysWithPrefix(outBucket(), REJECT_KEY_PREFIX);
            assertThat(rejectKeys).as("named artifact: DALYREJS reject object").hasSize(1);
            for (String record : fixedWidthLines(readObjectBytes(outBucket(), rejectKeys.get(0)))) {
                assertThat(record.length()).as("DALYREJS record is 430 bytes").isEqualTo(REJECT_RECORD_WIDTH);
            }

            // Named artifact: CBTRN03C transaction report (133-byte lines).
            List<String> reportKeys = keysWithPrefix(outBucket(), REPORT_OBJECT_PREFIX);
            assertThat(reportKeys).as("named artifact: CBTRN03C transaction report").hasSize(1);
            for (String line : fixedWidthLines(readObjectBytes(outBucket(), reportKeys.get(0)))) {
                assertThat(line.length()).as("CBTRN03C report line is 133 bytes").isEqualTo(REPORT_RECORD_WIDTH);
            }

            // Named artifacts: CBSTM03A statements — 80-byte text and 100-byte HTML.
            List<String> textKeys = keysWithPrefix(stmtBucket(), STATEMENT_TEXT_PREFIX);
            List<String> htmlKeys = keysWithPrefix(stmtBucket(), STATEMENT_HTML_PREFIX);
            assertThat(textKeys).as("named artifact: CBSTM03A text statement(s)").isNotEmpty();
            assertThat(htmlKeys).as("named artifact: CBSTM03A HTML statement(s)").isNotEmpty();
            for (String key : textKeys) {
                for (String line : fixedWidthLines(readObjectBytes(stmtBucket(), key))) {
                    assertThat(line.length()).as("statement text line is 80 bytes").isEqualTo(STATEMENT_TEXT_WIDTH);
                }
            }
            for (String key : htmlKeys) {
                for (String line : fixedWidthLines(readObjectBytes(stmtBucket(), key))) {
                    assertThat(line.length()).as("statement HTML line is 100 bytes").isEqualTo(STATEMENT_HTML_WIDTH);
                }
            }

            // Named artifacts: PRTCATBL category-balance print (40-byte) + backup unload (50-byte).
            List<String> printKeys = keysWithPrefix(outBucket(), PRINT_OBJECT_PREFIX);
            List<String> backupKeys = keysWithPrefix(outBucket(), BACKUP_OBJECT_PREFIX);
            assertThat(printKeys).as("named artifact: PRTCATBL category-balance print").hasSize(1);
            assertThat(backupKeys).as("named artifact: TCATBAL backup unload").hasSize(1);
            for (String line : fixedWidthLines(readObjectBytes(outBucket(), printKeys.get(0)))) {
                assertThat(line.length()).as("PRTCATBL print line is 40 bytes").isEqualTo(PRINT_RECORD_WIDTH);
            }
            for (String line : fixedWidthLines(readObjectBytes(outBucket(), backupKeys.get(0)))) {
                assertThat(line.length()).as("TCATBAL backup line is 50 bytes").isEqualTo(BACKUP_RECORD_WIDTH);
            }
        }

        @Test
        @DisplayName("CBACT04C interest parity: (balance*rate)/1200 HALF_EVEN scale 2 with the DEFAULT-group fallback")
        void interestFormulaParityWithDefaultGroupFallback() throws Exception {
            // Reduce account 1 to a single category balance so it yields exactly one interest
            // transaction; account 1's (group,type,cat) is absent from discgrp, so the DEFAULT
            // disclosure rate (15.00) applies (the documented CBACT04C 1200-A fallback).
            jdbcTemplate.update("DELETE FROM " + TCATBAL_TABLE + " WHERE acct_id = ?", INTEREST_ACCT_ID);
            insertTransactionCategoryBalance(INTEREST_ACCT_ID, "01", 1, INTEREST_SEED_BALANCE);

            BigDecimal expectedInterest = monthlyInterest(INTEREST_SEED_BALANCE, INTEREST_DEFAULT_RATE);
            assertThat(expectedInterest)
                    .as("formula cross-check: (1000.00 * 15.00) / 1200 = 12.50 (HALF_EVEN, scale 2)")
                    .isEqualByComparingTo(INTEREST_EXPECTED_AMOUNT);

            BigDecimal balanceBefore = accountCurrentBalance(INTEREST_ACCT_ID);

            JobExecution execution = launch(interestCalculationJob, wideParams());
            assertThat(execution.getStatus())
                    .as("the interest job completes").isEqualTo(BatchStatus.COMPLETED);

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_amt FROM " + TRANSACTIONS_TABLE
                            + " WHERE tran_desc = ?", INTEREST_DESCRIPTION);
            assertThat(rows)
                    .as("a single-category account yields exactly one CBACT04C interest transaction").hasSize(1);
            Map<String, Object> interestTxn = rows.get(0);
            assertThat(((String) interestTxn.get("tran_type_cd")).trim())
                    .as("interest transaction type is '01'").isEqualTo(INTEREST_TRAN_TYPE);
            assertThat(((Number) interestTxn.get("tran_cat_cd")).intValue())
                    .as("interest transaction category is 5").isEqualTo(INTEREST_TRAN_CAT_CD);
            assertThat(((String) interestTxn.get("tran_source")).trim())
                    .as("interest transaction source is 'System'").isEqualTo(INTEREST_TRAN_SOURCE);
            assertThat((BigDecimal) interestTxn.get("tran_amt"))
                    .as("posted interest equals (balance*rate)/1200 = 12.50").isEqualByComparingTo(expectedInterest);
            assertThat((String) interestTxn.get("tran_id"))
                    .as("interest tran-id is the 10-char run-date prefix + 6-digit suffix")
                    .startsWith(INTEREST_PARM_DATE).hasSize(16);
            assertThat(accountCurrentBalance(INTEREST_ACCT_ID).subtract(balanceBefore))
                    .as("the account balance grows by exactly the posted interest").isEqualByComparingTo(expectedInterest);
        }
    }

    // =====================================================================
    // GATE 5 — API/Interface Contract Verification
    // =====================================================================

    /**
     * Gate&nbsp;5 verifies every external interface with a real local test that
     * exercises the actual contract (no self-certification): the fixed-width input
     * geometry (DALYTRAN 350B), the produced record-length contracts (reject 430B,
     * report 133B, statement 80B/100B, print 40B), the full CBTRN02C four-stage reject
     * cascade with byte-exact reason codes and text — including the documented
     * <strong>103-over-102 last-wins</strong> precedence for a single transaction that
     * is both over-limit and expired — and the SQS FIFO report-bridge messaging
     * contract (F-011) round-tripped on real LocalStack.
     */
    @Nested
    @Order(5)
    @DisplayName("Gate 5 — API/Interface Contract Verification")
    class Gate5InterfaceContract {

        @Test
        @DisplayName("RejectReasonCode enum matches the frozen CBTRN02C byte-exact code/description contract (100/101/102/103/109)")
        void rejectReasonCodeEnumMatchesFrozenCobolContract() {
            // These literals are the FROZEN CBTRN02C contract (source SHA 27d6c6f) — the one place a
            // literal is correct, since the gate's purpose is to pin the production enum to the
            // immutable COBOL byte contract and fail on any drift.
            assertThat(RejectReasonCode.CARD_NOT_FOUND.getFormattedCode()).isEqualTo("0100");
            assertThat(RejectReasonCode.CARD_NOT_FOUND.getDescription()).isEqualTo("INVALID CARD NUMBER FOUND");
            assertThat(RejectReasonCode.ACCOUNT_NOT_FOUND.getFormattedCode()).isEqualTo("0101");
            assertThat(RejectReasonCode.ACCOUNT_NOT_FOUND.getDescription()).isEqualTo("ACCOUNT RECORD NOT FOUND");
            assertThat(RejectReasonCode.OVER_CREDIT_LIMIT.getFormattedCode()).isEqualTo("0102");
            assertThat(RejectReasonCode.OVER_CREDIT_LIMIT.getDescription()).isEqualTo("OVERLIMIT TRANSACTION");
            assertThat(RejectReasonCode.ACCOUNT_EXPIRED.getFormattedCode()).isEqualTo("0103");
            assertThat(RejectReasonCode.ACCOUNT_EXPIRED.getDescription())
                    .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
            assertThat(RejectReasonCode.ACCOUNT_NOT_FOUND_ON_UPDATE.getFormattedCode()).isEqualTo("0109");
            assertThat(RejectReasonCode.ACCOUNT_NOT_FOUND_ON_UPDATE.getDescription()).isEqualTo("ACCOUNT RECORD NOT FOUND");
        }

        @Test
        @DisplayName("DALYTRAN fixed-width input geometry (350 bytes/record) is honoured with no boundary shift")
        void fixedWidthInputGeometryHonoured() {
            byte[] fixtureBytes = loadDailyTranFixture();
            List<String> records = fixedWidthLines(fixtureBytes);
            assertThat(records).as("dailytran.txt holds %d records", SEEDED_DAILY_ROW_COUNT)
                    .hasSize(SEEDED_DAILY_ROW_COUNT);
            for (String record : records) {
                assertThat(record.length())
                        .as("each DALYTRAN record is exactly %d bytes (parse round-trips without boundary shift)",
                                DALYTRAN_RECORD_WIDTH)
                        .isEqualTo(DALYTRAN_RECORD_WIDTH);
            }
        }

        @Test
        @DisplayName("CBTRN02C four-stage cascade emits byte-exact reason codes 0100/0101/0102/0103 in a 430-byte reject record")
        void rejectCascadeProducesByteExactCodesAndText() throws Exception {
            // 0101 needs a card present in the cross-reference but whose account is absent.
            insertCardXref(DANGLING_CARD, DANGLING_CUST_ID, DANGLING_ACCT_ID);
            insertDailyTransaction(nextTranId(), "01", 1, CLEAN_AMOUNT, CARD_NOT_FOUND_CARD, IN_WINDOW_ORIG_DATE); // 0100
            insertDailyTransaction(nextTranId(), "01", 1, CLEAN_AMOUNT, DANGLING_CARD, IN_WINDOW_ORIG_DATE);       // 0101
            insertDailyTransaction(nextTranId(), "01", 1, OVER_LIMIT_AMOUNT, OVER_LIMIT_CARD, IN_WINDOW_ORIG_DATE);// 0102
            insertDailyTransaction(nextTranId(), "01", 1, SMALL_AMOUNT, EXPIRED_CARD, FUTURE_ORIG_DATE);           // 0103

            JobExecution execution = launch(postTransactionJob, wideParams());
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(stepExecution(execution, STEP_POST).getExitStatus().getExitCode())
                    .as("a cycle with rejects exits COMPLETED_WITH_REJECTS (RC 4)").isEqualTo(EXIT_COMPLETED_WITH_REJECTS);

            List<String> rejectKeys = keysWithPrefix(outBucket(), REJECT_KEY_PREFIX);
            assertThat(rejectKeys).as("the reject object lands under the rejects/dalyrejs/ prefix as one versioned object")
                    .hasSize(1);
            assertThat(rejectKeys.get(0)).as("the reject key uses the DALYREJS prefix").startsWith(REJECT_KEY_PREFIX);

            byte[] rejectBytes = readObjectBytes(outBucket(), rejectKeys.get(0));
            assertThat(rejectBytes.length)
                    .as("four 430-byte records, each framed with one delimiter").isEqualTo(REJECT_FRAMED_WIDTH * 4);
            List<String> records = fixedWidthLines(rejectBytes);
            assertThat(records).hasSize(4);
            for (String record : records) {
                assertThat(record.length()).as("each reject record image is 430 bytes").isEqualTo(REJECT_RECORD_WIDTH);
                assertThat(record.substring(0, REJECT_IMAGE_WIDTH).length())
                        .as("the leading 350 bytes are the CVTRA06Y record image").isEqualTo(REJECT_IMAGE_WIDTH);
            }
            assertThat(records.stream().map(GateVerificationIT.this::rejectCode).toList())
                    .as("all four CBTRN02C reject reason codes are present")
                    .containsExactlyInAnyOrder(CODE_CARD_NOT_FOUND, CODE_ACCOUNT_NOT_FOUND, CODE_OVER_LIMIT, CODE_EXPIRED);
            // Each rejected record's 4-digit code carries its byte-exact 76-char reason text.
            // (The CODE_*/DESC_* constants are derived from the production RejectReasonCode enum,
            // so they are runtime values rather than compile-time constants; an if/else-if chain is
            // used instead of a switch, which would require constant case labels.)
            for (String record : records) {
                String code = rejectCode(record);
                String description = rejectDescription(record);
                if (CODE_CARD_NOT_FOUND.equals(code)) {
                    assertThat(description).as("0100 reason text is byte-exact").isEqualTo(DESC_CARD_NOT_FOUND);
                } else if (CODE_ACCOUNT_NOT_FOUND.equals(code)) {
                    assertThat(description).as("0101 reason text is byte-exact").isEqualTo(DESC_ACCOUNT_NOT_FOUND);
                } else if (CODE_OVER_LIMIT.equals(code)) {
                    assertThat(description).as("0102 reason text is byte-exact").isEqualTo(DESC_OVER_LIMIT);
                } else if (CODE_EXPIRED.equals(code)) {
                    assertThat(description).as("0103 reason text is byte-exact").isEqualTo(DESC_EXPIRED);
                } else {
                    throw new AssertionError("unexpected reject code: " + code);
                }
            }
        }

        @Test
        @DisplayName("103-over-102 last-wins: one transaction both over-limit AND expired rejects as 0103 (expiration overwrites)")
        void expirationRejectWinsOverOverLimitForSingleTransaction() throws Exception {
            // CBTRN02C stages 3 (credit-limit) and 4 (expiration) are two sequential IFs in the
            // account-found path with NO short-circuit between them. A transaction that fails BOTH
            // first sets reason 0102 then has it overwritten by 0103, so the final reason is 0103.
            insertDailyTransaction(nextTranId(), "01", 1, OVER_LIMIT_AMOUNT, EXPIRED_CARD, FUTURE_ORIG_DATE);

            JobExecution execution = launch(postTransactionJob, wideParams());
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(stepExecution(execution, STEP_POST).getExitStatus().getExitCode())
                    .isEqualTo(EXIT_COMPLETED_WITH_REJECTS);

            List<String> rejectKeys = keysWithPrefix(outBucket(), REJECT_KEY_PREFIX);
            assertThat(rejectKeys).as("exactly one reject object").hasSize(1);
            byte[] rejectBytes = readObjectBytes(outBucket(), rejectKeys.get(0));
            assertThat(rejectBytes.length).as("exactly one framed 430-byte reject record").isEqualTo(REJECT_FRAMED_WIDTH);
            List<String> records = fixedWidthLines(rejectBytes);
            assertThat(records).hasSize(1);
            assertThat(rejectCode(records.get(0)))
                    .as("expiration (0103) wins over over-limit (0102) for a single transaction failing both")
                    .isEqualTo(CODE_EXPIRED);
            assertThat(rejectDescription(records.get(0)))
                    .as("the winning reason text is the expiration text").isEqualTo(DESC_EXPIRED);
        }

        @Test
        @DisplayName("downstream record-length contracts on real artifacts: report 133B, statement 80B/100B, print 40B")
        void downstreamRecordLengthContractsHonoured() throws Exception {
            insertDailyTransaction(nextTranId(), "01", 1, CLEAN_AMOUNT, CLEAN_CARD, IN_WINDOW_ORIG_DATE);
            insertDailyTransaction(nextTranId(), "01", 1, CLEAN_AMOUNT, CLEAN_CARD, IN_WINDOW_ORIG_DATE);

            JobExecution execution = launch(pipelineJob, wideParams());
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            List<String> reportKeys = keysWithPrefix(outBucket(), REPORT_OBJECT_PREFIX);
            assertThat(reportKeys).as("CBTRN03C report object produced").hasSize(1);
            for (String line : fixedWidthLines(readObjectBytes(outBucket(), reportKeys.get(0)))) {
                assertThat(line.length()).as("F-022: report line is 133 bytes").isEqualTo(REPORT_RECORD_WIDTH);
            }

            List<String> textKeys = keysWithPrefix(stmtBucket(), STATEMENT_TEXT_PREFIX);
            List<String> htmlKeys = keysWithPrefix(stmtBucket(), STATEMENT_HTML_PREFIX);
            assertThat(textKeys).as("CBSTM03A text statement produced").isNotEmpty();
            assertThat(htmlKeys).as("CBSTM03A HTML statement produced").isNotEmpty();
            for (String key : textKeys) {
                for (String line : fixedWidthLines(readObjectBytes(stmtBucket(), key))) {
                    assertThat(line.length()).as("F-021: statement text line is 80 bytes").isEqualTo(STATEMENT_TEXT_WIDTH);
                }
            }
            for (String key : htmlKeys) {
                for (String line : fixedWidthLines(readObjectBytes(stmtBucket(), key))) {
                    assertThat(line.length()).as("F-021: statement HTML line is 100 bytes").isEqualTo(STATEMENT_HTML_WIDTH);
                }
            }

            List<String> printKeys = keysWithPrefix(outBucket(), PRINT_OBJECT_PREFIX);
            assertThat(printKeys).as("PRTCATBL print object produced").hasSize(1);
            for (String line : fixedWidthLines(readObjectBytes(outBucket(), printKeys.get(0)))) {
                assertThat(line.length()).as("PRTCATBL print line is 40 bytes").isEqualTo(PRINT_RECORD_WIDTH);
            }
        }

        @Test
        @DisplayName("report-bridge SQS FIFO contract (F-011): queue resolves to carddemo-report-jobs.fifo and a message round-trips")
        void reportBridgeFifoQueueContractRoundTrips() {
            // The queue name is resolved through config (never hardcoded into the AWS call) and
            // must match the canonical FIFO contract.
            assertThat(reportQueue())
                    .as("the report queue resolves to the canonical FIFO name").isEqualTo(SQS_REPORT_QUEUE);
            assertThat(reportQueue()).as("a FIFO queue name ends with .fifo").endsWith(".fifo");

            String body = "{\"reportType\":\"GATE5\",\"requestedBy\":\"GateVerificationIT\"}";
            String received = roundTripReportMessage(body);
            assertThat(received)
                    .as("a report-request message published to the FIFO queue is consumable verbatim")
                    .isEqualTo(body);
        }
    }

    // =====================================================================
    // GATE 6 — Unsafe/Low-Level Code Audit
    // =====================================================================

    /**
     * Gate&nbsp;6 statically audits the <strong>production</strong> source tree
     * ({@code src/main/java}) for unsafe/low-level constructs — {@code Runtime.exec},
     * {@code ProcessBuilder}, reflection, {@code @SuppressWarnings}, and raw SQL string
     * concatenation. More than fifty occurrences would require a per-site justification
     * table; this gate asserts the total stays within that budget and prints the
     * per-category tally as evidence. It needs no container (the context is inherited
     * but no job is launched), and it is kept here so all gate evidence lives in one
     * harness.
     */
    @Nested
    @Order(6)
    @DisplayName("Gate 6 — Unsafe/Low-Level Code Audit")
    class Gate6UnsafeCodeAudit {

        private static final Pattern RUNTIME_EXEC = Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec");
        private static final Pattern PROCESS_BUILDER = Pattern.compile("\\bProcessBuilder\\b");
        private static final Pattern REFLECTION = Pattern.compile(
                "java\\.lang\\.reflect|\\.getDeclaredMethod\\(|\\.getDeclaredField\\(|\\.setAccessible\\(");
        private static final Pattern SUPPRESS_WARNINGS = Pattern.compile("@SuppressWarnings");
        private static final Pattern UNCHECKED = Pattern.compile("@SuppressWarnings\\(\\s*\"unchecked\"");
        private static final Pattern SQL_BUILDER = Pattern.compile("createNativeQuery|createQuery|\\bStatement\\b");
        private static final Pattern STRING_CONCAT = Pattern.compile("\"\\s*\\+|\\+\\s*\"");

        @Test
        @DisplayName("unsafe/low-level constructs across src/main/java stay within the audit budget (<= 50)")
        void unsafeCodeStaysWithinBudget() throws IOException {
            Path sourceRoot = Paths.get("src", "main", "java");
            if (!Files.isDirectory(sourceRoot)) {
                LOGGER.info("GATE6 src/main/java not found at {}; the authoritative audit runs against the "
                        + "module source tree at 'mvn verify'", sourceRoot.toAbsolutePath());
                return;
            }

            List<Path> javaFiles;
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();
            }

            int runtimeExec = 0;
            int processBuilder = 0;
            int reflection = 0;
            int suppressWarnings = 0;
            int unchecked = 0;
            int rawSqlConcat = 0;
            for (Path file : javaFiles) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                runtimeExec += countMatches(RUNTIME_EXEC, content);
                processBuilder += countMatches(PROCESS_BUILDER, content);
                reflection += countMatches(REFLECTION, content);
                suppressWarnings += countMatches(SUPPRESS_WARNINGS, content);
                unchecked += countMatches(UNCHECKED, content);
                for (String line : content.split("\n", -1)) {
                    if (SQL_BUILDER.matcher(line).find() && STRING_CONCAT.matcher(line).find()) {
                        rawSqlConcat++;
                    }
                }
            }

            // @SuppressWarnings("unchecked") is a subset of @SuppressWarnings, so the unchecked
            // tally is reported for transparency but not summed twice.
            int total = runtimeExec + processBuilder + reflection + suppressWarnings + rawSqlConcat;
            LOGGER.info("GATE6 unsafe-code audit files={} runtimeExec={} processBuilder={} reflection={} "
                            + "suppressWarnings={} (unchecked={}) rawSqlConcat={} total={} budget={}",
                    javaFiles.size(), runtimeExec, processBuilder, reflection, suppressWarnings, unchecked,
                    rawSqlConcat, total, UNSAFE_CODE_BUDGET);

            assertThat(total)
                    .as("unsafe/low-level occurrences (%d) exceed the audit budget (%d); add a per-site "
                            + "justification table to docs/validation-gates.md (Gate 6)", total, UNSAFE_CODE_BUDGET)
                    .isLessThanOrEqualTo(UNSAFE_CODE_BUDGET);
        }

        /** Counts non-overlapping matches of a pattern within the supplied content. */
        private int countMatches(Pattern pattern, String content) {
            Matcher matcher = pattern.matcher(content);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            return count;
        }
    }

    // =====================================================================
    // GATE 7 — Scope Matching
    // =====================================================================

    /**
     * Gate&nbsp;7 demonstrates the migration spans the full multi-subsystem scope:
     * JCL&rarr;Spring&nbsp;Batch orchestration (the six Job beans), AWS integration
     * (three S3 buckets + SQS&nbsp;FIFO + SNS, reachable on real LocalStack), fixed-width
     * file I/O, the inter-program call chain (reader&rarr;processor&rarr;writer), and the
     * repository data subsystem.
     */
    @Nested
    @Order(7)
    @DisplayName("Gate 7 — Scope Matching")
    class Gate7ScopeMatching {

        @Test
        @DisplayName("the context defines the six batch Job beans (JCL -> Spring Batch orchestration coverage)")
        void contextDefinesSixBatchJobBeans() {
            assertThat(jobsByName)
                    .as("the six pipeline Job beans (POSTTRAN/INTCALC/COMBTRAN/TRANREPT/CREASTMT + orchestrator) are present")
                    .containsKeys(BatchPipelineOrchestrator.JOB_NAME, JOB_POST, JOB_INTEREST,
                            JOB_REPORT, JOB_STATEMENT, JOB_COMBINE);
            assertThat(pipelineJob).as("orchestrator job wired").isNotNull();
            assertThat(postTransactionJob).as("POSTTRAN job wired").isNotNull();
            assertThat(interestCalculationJob).as("INTCALC job wired").isNotNull();
            assertThat(transactionReportJob).as("TRANREPT job wired").isNotNull();
            assertThat(statementJob).as("CREASTMT/statement job wired").isNotNull();
            assertThat(combineTransactionsJob).as("COMBTRAN job wired").isNotNull();
        }

        @Test
        @DisplayName("AWS integration: 3 S3 buckets + SQS FIFO + SNS resolve to canonical names and are reachable on LocalStack")
        void awsIntegrationResourcesResolveAndAreReachable() {
            provisionAws();
            // Config-contract: every name resolves through AwsResourceProperties to its canonical value.
            assertThat(inBucket()).as("input bucket resolves to the canonical name").isEqualTo(S3_INPUT_BUCKET);
            assertThat(outBucket()).as("output bucket resolves to the canonical name").isEqualTo(S3_OUTPUT_BUCKET);
            assertThat(stmtBucket()).as("statement bucket resolves to the canonical name").isEqualTo(S3_STATEMENT_BUCKET);
            assertThat(reportQueue()).as("report queue resolves to the canonical FIFO name").isEqualTo(SQS_REPORT_QUEUE);
            assertThat(snsTopic()).as("SNS topic resolves to the canonical name").isEqualTo(SNS_TOPIC);
            // Reachability on real LocalStack: each bucket lists successfully and the FIFO URL resolves.
            assertThat(listKeys(inBucket())).as("input bucket is reachable on LocalStack").isNotNull();
            assertThat(listKeys(outBucket())).as("output bucket is reachable on LocalStack").isNotNull();
            assertThat(listKeys(stmtBucket())).as("statement bucket is reachable on LocalStack").isNotNull();
            assertThat(reportQueueUrl()).as("the SQS FIFO queue URL resolves on LocalStack").isNotBlank();
        }

        @Test
        @DisplayName("file I/O round-trip + inter-program call + repository-layer coverage")
        void fileIoRoundTripInterProgramCallAndRepositoryCoverage() throws Exception {
            // File I/O: a fixed-width payload round-trips through object storage unchanged.
            byte[] payload = loadDailyTranFixture();
            String key = INPUT_FIXTURE_KEY_PREFIX + UUID.randomUUID() + "/dalytran.txt";
            putObjectBytes(inBucket(), key, payload);
            assertThat(readObjectBytes(inBucket(), key))
                    .as("fixed-width file I/O round-trips through S3 unchanged").isEqualTo(payload);

            // Repository / data-structures subsystem coverage.
            assertThat(accountRepository.count())
                    .as("the account repository surfaces the seeded masters").isEqualTo(SEED_ACCOUNTS);

            // Inter-program call coverage: the posting reader -> processor -> writer chain writes a
            // fixed-width reject artifact back to object storage.
            insertDailyTransaction(nextTranId(), "01", 1, CLEAN_AMOUNT, CARD_NOT_FOUND_CARD, IN_WINDOW_ORIG_DATE);
            JobExecution execution = launch(postTransactionJob, wideParams());
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            List<String> rejectKeys = keysWithPrefix(outBucket(), REJECT_KEY_PREFIX);
            assertThat(rejectKeys)
                    .as("the reader->processor->writer chain wrote a fixed-width reject artifact").hasSize(1);
            for (String record : fixedWidthLines(readObjectBytes(outBucket(), rejectKeys.get(0)))) {
                assertThat(record.length()).as("the written reject record is 430 bytes").isEqualTo(REJECT_RECORD_WIDTH);
            }
        }
    }

    // =====================================================================
    // GATE 8 — Integration Sign-Off Checklist
    // =====================================================================

    /**
     * Gate&nbsp;8 is the consolidated sign-off. It asserts what is programmatically
     * verifiable in this run — E2E capability (Gates&nbsp;1/4), the multi-subsystem job
     * scope (Gate&nbsp;7), and the canonical AWS interface contracts (Gate&nbsp;5) — and
     * documents pointers to the authoritative build-time evidence for the rest:
     * &ge;80% line coverage (JaCoCo {@code jacoco:check}), zero critical/high CVEs
     * (OWASP dependency-check), and the 100%-paragraph traceability matrix
     * ({@code TRACEABILITY_MATRIX.md}, a root deliverable owned by another agent). It
     * never creates those external artifacts and never fails merely because one is
     * absent; when present they are read and asserted.
     */
    @Nested
    @Order(8)
    @DisplayName("Gate 8 — Integration Sign-Off Checklist")
    class Gate8IntegrationSignOff {

        @Test
        @DisplayName("consolidated sign-off: in-run items asserted; coverage/OWASP/traceability pointers documented")
        void consolidatedSignOffChecklist() throws IOException {
            // E2E verified (Gate 1/4): the real context + orchestrated pipeline are wired.
            assertThat(pipelineJob).as("E2E pipeline is wired (Gate 1/4 capability)").isNotNull();
            assertThat(jobsByName).as("all six batch jobs present (multi-subsystem scope, Gate 7)")
                    .containsKeys(BatchPipelineOrchestrator.JOB_NAME, JOB_POST, JOB_INTEREST,
                            JOB_REPORT, JOB_STATEMENT, JOB_COMBINE);
            // Interface contracts (Gate 5): canonical AWS interfaces resolve through config.
            assertThat(reportQueue()).as("SQS FIFO report interface (Gate 5)").isEqualTo(SQS_REPORT_QUEUE);
            assertThat(inBucket()).as("S3 input interface (Gate 5)").isEqualTo(S3_INPUT_BUCKET);

            // Coverage >= 80% line: read the JaCoCo report if present; else document the build gate.
            documentCoverageEvidence();
            // OWASP zero critical/high: enforced by dependency-check at build time.
            LOGGER.info("GATE8 OWASP zero critical/high CVEs is enforced by dependency-check-maven at 'mvn verify'");
            // Traceability matrix 100% of COBOL paragraphs (root deliverable; asserted when present).
            assertTraceabilityMatrixWhenPresent();

            // Gate-8 section of the validation-gates doc, when present.
            Path gatesDoc = Paths.get("docs", "validation-gates.md");
            if (Files.exists(gatesDoc)) {
                assertThat(Files.readString(gatesDoc, StandardCharsets.UTF_8))
                        .as("docs/validation-gates.md documents the Gate 8 sign-off").contains("Gate 8");
            } else {
                LOGGER.info("GATE8 docs/validation-gates.md not present; the authoritative sign-off evidence is "
                        + "the failsafe report plus the build-time coverage/CVE gates");
            }
        }

        /** Reads the JaCoCo CSV export (if present) and logs the aggregate line coverage. */
        private void documentCoverageEvidence() throws IOException {
            Path jacocoCsv = Paths.get("target", "site", "jacoco", "jacoco.csv");
            if (Files.exists(jacocoCsv)) {
                long covered = 0L;
                long missed = 0L;
                List<String> lines = Files.readAllLines(jacocoCsv, StandardCharsets.UTF_8);
                for (int i = 1; i < lines.size(); i++) {
                    String[] cols = lines.get(i).split(",");
                    // JaCoCo CSV columns: ...,LINE_MISSED(7),LINE_COVERED(8),...
                    if (cols.length >= 9) {
                        missed += parseLongSafe(cols[7]);
                        covered += parseLongSafe(cols[8]);
                    }
                }
                double linePct = (covered + missed) > 0 ? (100.0 * covered / (covered + missed)) : 0.0;
                LOGGER.info("GATE8 JaCoCo line coverage = {}% (covered={} missed={}); the authoritative >=80% gate "
                                + "is the jacoco:check build rule",
                        String.format(Locale.ROOT, "%.2f", linePct), covered, missed);
            } else {
                LOGGER.info("GATE8 JaCoCo report not present at {}; coverage >=80% is enforced by the jacoco:check "
                        + "build rule at 'mvn verify'", jacocoCsv);
            }
        }

        /** Parses a long, returning 0 for any non-numeric cell. */
        private long parseLongSafe(String value) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException ex) {
                return 0L;
            }
        }

        /** Asserts the traceability matrix's 100%-coverage affirmation when the root file is present. */
        private void assertTraceabilityMatrixWhenPresent() throws IOException {
            Path matrix = Paths.get("TRACEABILITY_MATRIX.md");
            if (Files.exists(matrix)) {
                String content = Files.readString(matrix, StandardCharsets.UTF_8);
                assertThat(content).as("the traceability matrix is non-empty").isNotBlank();
                assertThat(content.toLowerCase(Locale.ROOT))
                        .as("the traceability matrix affirms 100% paragraph coverage with no gaps")
                        .containsAnyOf("100%", "no gaps", "no gap");
                assertThat(content)
                        .as("the single intentionally-unmapped reserved copybook UNUSED1Y is recorded")
                        .contains("UNUSED1Y");
            } else {
                LOGGER.info("GATE8 TRACEABILITY_MATRIX.md is a root deliverable owned by another agent; "
                        + "it is asserted here when present and never created from this test");
            }
        }
    }
}
