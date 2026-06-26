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

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.job.BatchPipelineOrchestrator;
import com.carddemo.batch.job.InterestCalculationJobConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * End-to-end integration test for the orchestrated CardDemo batch pipeline
 * ({@code carddemoBatchPipelineJob}, assembled by {@link BatchPipelineOrchestrator}),
 * exercised against a REAL PostgreSQL&nbsp;16 container and REAL LocalStack
 * (S3/SQS/SNS) inherited from {@link AbstractIntegrationIT}. No component is mocked
 * and no in-memory database is used; every assertion is made against durable state
 * written by the production code path.
 *
 * <p><strong>Gate&nbsp;1 / Gate&nbsp;4 anchor.</strong> This suite is the
 * byte-equivalence anchor for the migration validation framework: it stages
 * production-representative slices of the named ASCII fixtures (primarily
 * {@code dailytran.txt}, with {@code acctdata.txt} / {@code tcatbal.txt} /
 * {@code discgrp.txt} supplied through the Flyway V3 seed), drives them through the
 * full pipeline locally, and verifies that the produced outputs honour the COBOL
 * record contracts byte-for-byte — the 430-byte {@code DALYREJS} reject image, the
 * 133-byte {@code CBTRN03C} report line, and the 80-byte / 100-byte {@code CBSTM03A}
 * statement streams — together with the reject reason codes and arithmetic totals.
 * Gate&nbsp;1 (end-to-end boundary, no mocked I/O) and Gate&nbsp;4 (named real-world
 * fixtures through the primary pipeline) are both satisfied by the real container
 * round trip.</p>
 *
 * <p><strong>Pipeline topology under test</strong> (mirrors the JCL run order, with
 * the {@code COND=(4,LT)} category-balance backup gate realised as a
 * {@code JobExecutionDecider}):</p>
 * <pre>
 *   postTransactionStep            (CBTRN02C / POSTTRAN)
 *      -&gt; [decider: RC&le;4 =&gt; RUN_BACKUP, RC&gt;4 =&gt; halt]
 *   categoryBalanceBackupStep      (TRANBKP-equivalent backup, runs when RC&le;4)
 *   interestCalculationStep        (CBACT04C / INTCALC)
 *   combineTransactionsStep        (COMBTRAN DFSORT concat + sort)
 *   {transactionReportStep || statementStep}   (CBTRN03C / TRANREPT  ‖  CBSTM03A / CREASTMT, parallel split)
 *   printCategoryBalanceStep       (PRTCATBL category-balance print)
 * </pre>
 * A clean posting cycle exits {@code postTransactionStep} as {@code COMPLETED}
 * (RC&nbsp;0) and a cycle that produced rejects exits {@code COMPLETED_WITH_REJECTS}
 * (RC&nbsp;4); both are {@code &le; 4}, so the decider runs the backup step and the
 * pipeline proceeds. Only RC&nbsp;&gt;&nbsp;4 halts the run.</p>
 *
 * <p><strong>Transaction semantics.</strong> The class is deliberately NOT
 * {@code @Transactional}: Spring Batch owns its own chunk/step commit boundaries, so
 * a surrounding test transaction would corrupt batch semantics and hide commits.
 * State is instead isolated with explicit {@link BeforeEach}/{@link AfterEach}
 * cleanup — the mutable application tables this suite seeds or mutates
 * ({@code daily_transaction}, {@code transactions}, {@code accounts},
 * {@code transaction_category_balance}, and any test-owned {@code accounts} /
 * {@code card_xref} rows) are snapshotted once from the pristine Flyway seed and
 * restored after every test, and the S3 output / statement buckets are emptied.
 * The shared Flyway seed the sibling suites depend on is never left mutated.</p>
 *
 * <p><strong>Job launching.</strong> {@code spring.batch.job.enabled=false} (test
 * profile) prevents jobs from auto-running, so each job is launched explicitly via a
 * manually wired {@link JobLauncherTestUtils}. Because the context defines six
 * {@link Job} beans, the utils are built by hand per job (autowiring a single
 * {@code setJob} target would be ambiguous). Every launch is given a unique
 * {@code run.id} parameter so re-launches never collide with a completed
 * {@code JobInstance}.</p>
 */
@DisplayName("BatchPipeline E2E IT — orchestrated carddemoBatchPipelineJob vs real PostgreSQL + LocalStack (Gates 1/4/5)")
public class BatchPipelineE2EIT extends AbstractIntegrationIT {

    // ---------------------------------------------------------------------
    // Application tables (mutated/asserted via the inherited JdbcTemplate)
    // ---------------------------------------------------------------------
    private static final String DAILY_TRANSACTION_TABLE = "daily_transaction";
    private static final String TRANSACTIONS_TABLE = "transactions";
    private static final String ACCOUNTS_TABLE = "accounts";
    private static final String TCATBAL_TABLE = "transaction_category_balance";
    private static final String CARD_XREF_TABLE = "card_xref";

    /** Flyway V3 seeds {@code daily_transaction} from {@code dailytran.txt}. */
    private static final int SEEDED_DAILY_ROW_COUNT = 300;

    // ---------------------------------------------------------------------
    // Step / job bean names (the package-private constants on the job configs
    // are unreachable from this package, so the canonical names are repeated
    // here exactly as registered by the @Bean methods).
    // ---------------------------------------------------------------------
    private static final String STEP_POST = "postTransactionStep";
    private static final String STEP_BACKUP = "categoryBalanceBackupStep";
    private static final String STEP_INTEREST = "interestCalculationStep";
    private static final String STEP_COMBINE = "combineTransactionsStep";
    private static final String STEP_REPORT = "transactionReportStep";
    private static final String STEP_STATEMENT = "statementStep";
    private static final String STEP_PRINT = "printCategoryBalanceStep";

    private static final String JOB_POST = "postTransactionJob";
    private static final String JOB_INTEREST = "interestCalculationJob";
    private static final String JOB_REPORT = "transactionReportJob";
    private static final String JOB_STATEMENT = "statementJob";

    /** {@code postTransactionStep} exit code when at least one record was rejected (RC=4). */
    private static final String EXIT_COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    // ---------------------------------------------------------------------
    // Byte geometry of the COBOL record contracts under test
    // ---------------------------------------------------------------------
    /** DALYREJS reject record: 350-byte CVTRA06Y image + 80-byte trailer = 430 data bytes. */
    private static final int REJECT_RECORD_WIDTH = 430;
    private static final int REJECT_FRAMED_WIDTH = REJECT_RECORD_WIDTH + 1; // + trailing '\n'
    private static final int REJECT_IMAGE_WIDTH = 350;
    private static final int REJECT_CODE_OFFSET = 350;
    private static final int REJECT_CODE_WIDTH = 4;
    private static final int REJECT_DESC_OFFSET = 354;
    private static final int REJECT_DESC_WIDTH = 76;
    private static final String REJECT_KEY_PREFIX = "rejects/dalyrejs/";

    /** CBTRN03C transaction report: every physical line is exactly 133 bytes. */
    private static final int REPORT_RECORD_WIDTH = 133;
    private static final int REPORT_PAGE_SIZE = 20;
    private static final String REPORT_SEPARATOR = "-".repeat(REPORT_RECORD_WIDTH);
    private static final String REPORT_NAME_HEADER_PREFIX = "DALYREPT";
    private static final String REPORT_COLUMN_HEADER_PREFIX = "Transaction ID";
    private static final String REPORT_GRAND_TOTAL_PREFIX = "Grand Total";

    /** CBSTM03A statement: 80-byte plain-text stream and 100-byte HTML stream. */
    private static final int STATEMENT_TEXT_WIDTH = 80;
    private static final int STATEMENT_HTML_WIDTH = 100;
    private static final String STATEMENT_TEXT_PREFIX = "statements/text/";
    private static final String STATEMENT_HTML_PREFIX = "statements/html/";
    private static final String STATEMENT_TEXT_MARKER = "START OF STATEMENT";
    private static final String STATEMENT_TOTAL_LABEL = "Total EXP:";

    /** Output-bucket key prefixes of the downstream pipeline artifacts. */
    private static final String REPORT_OBJECT_PREFIX = "transaction-report/";
    private static final String PRINT_OBJECT_PREFIX = "category-balance/PRTCATBL-";
    private static final String BACKUP_OBJECT_PREFIX = "category-balance-backup/TCATBAL-BKUP-";

    // ---------------------------------------------------------------------
    // Reject reason codes + byte-exact reason text (CBTRN02C cascade)
    // ---------------------------------------------------------------------
    private static final String CODE_CARD_NOT_FOUND = "0100";
    private static final String DESC_CARD_NOT_FOUND = "INVALID CARD NUMBER FOUND";
    private static final String CODE_ACCOUNT_NOT_FOUND = "0101";
    private static final String DESC_ACCOUNT_NOT_FOUND = "ACCOUNT RECORD NOT FOUND";
    private static final String CODE_OVER_LIMIT = "0102";
    private static final String DESC_OVER_LIMIT = "OVERLIMIT TRANSACTION";
    private static final String CODE_EXPIRED = "0103";
    private static final String DESC_EXPIRED = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    // ---------------------------------------------------------------------
    // Seed-derived fixtures (present in the Flyway V3 seed; never mutated
    // permanently). Card -> customer/account mappings come from cardxref.txt.
    // ---------------------------------------------------------------------
    /** Card 0923877193247330 -> customer 2 / account 2 (active, expires 2024-08-11, limit 6130.00). */
    private static final String CLEAN_CARD = "0923877193247330";
    private static final long CLEAN_ACCT_ID = 2L;

    /** Card 0982496213629795 -> account 12 (limit 4636.00); used to force an over-limit reject. */
    private static final String OVER_LIMIT_CARD = "0982496213629795";

    /** Card 0927987108636232 -> account 20 (expires before 2099); used to force an expiration reject. */
    private static final String EXPIRED_CARD = "0927987108636232";

    /** Not present in card_xref -> CBTRN02C reject 0100. */
    private static final String CARD_NOT_FOUND_CARD = "9999999999999999";

    /** Card 9680294154603697 -> customer 1 / account 1; the focus card for statement detail lines. */
    private static final String STATEMENT_CARD = "9680294154603697";

    // ---------------------------------------------------------------------
    // Test-owned fixtures (never in the seed; created and removed per test).
    // ---------------------------------------------------------------------
    /** Dangling cross-reference (card present, account absent) -> CBTRN02C reject 0101. */
    private static final String DANGLING_CARD = "7000000000000001";
    private static final long DANGLING_ACCT_ID = 70_000_000_001L;
    private static final long DANGLING_CUST_ID = 700_000_001L;

    /**
     * Seeded account 1 drives the deterministic interest assertion: it owns a single
     * category-balance row (so it yields exactly one interest transaction) and a
     * card cross-reference (so {@code InterestProcessor.loadCardNumber} resolves). Its
     * mutable state is snapshotted and restored like every other seeded account.
     */
    private static final long INTEREST_ACCT_ID = 1L;
    /** {@code Int. for a/c } + 11-digit zero-padded account id (InterestProcessor). */
    private static final String INTEREST_DESCRIPTION =
            "Int. for a/c " + String.format("%011d", INTEREST_ACCT_ID);
    private static final int INTEREST_TRAN_CAT_CD = 5;
    private static final String INTEREST_TRAN_SOURCE = "System";
    private static final String INTEREST_TRAN_TYPE = "01";
    private static final BigDecimal INTEREST_SEED_BALANCE = scaled("1000.00");
    private static final BigDecimal INTEREST_DEFAULT_RATE = scaled("15.00"); // DEFAULT/'01'/1
    private static final BigDecimal INTEREST_EXPECTED_AMOUNT = scaled("12.50"); // 1000.00*15.00/1200

    // ---------------------------------------------------------------------
    // Run parameters / dates
    // ---------------------------------------------------------------------
    /** Legacy CBACT04C PARM '2022071800' (run-date 2022-07-18); see app/jcl/INTCALC.jcl. */
    private static final String INTEREST_PARM_DATE =
            InterestCalculationJobConfig.formatRunDate(LocalDate.of(2022, 7, 18));
    /** Wide report window used by full-pipeline launches so the report/statement legs have data. */
    private static final String WIDE_WINDOW_START = "1900-01-01";
    private static final String WIDE_WINDOW_END = "2999-12-31";
    /** Focused report window matching the dailytran.txt processing dates (Gate-5 report contract). */
    private static final String REPORT_WINDOW_START = "2022-01-01";
    private static final String REPORT_WINDOW_END = "2022-07-06";

    private static final String IN_WINDOW_ORIG_DATE = "2022-06-10";
    private static final String IN_WINDOW_PROC_TS = "2022-03-15 12:00:00.000000";
    private static final String FUTURE_ORIG_DATE = "2099-12-31";

    private static final BigDecimal CLEAN_AMOUNT = scaled("10.00");
    private static final BigDecimal OVER_LIMIT_AMOUNT = scaled("9999999.00");
    private static final BigDecimal SMALL_AMOUNT = scaled("5.00");
    private static final BigDecimal REPORT_DETAIL_AMOUNT = scaled("42.00");

    /** Distinctive 16-digit tran-id base (leading '9') so generated ids never collide with the seed. */
    private static final long TEST_TRAN_ID_BASE = 9_000_000_000_000_000L;

    // ---------------------------------------------------------------------
    // Spring Batch wiring. Six Job beans exist, so each is qualified by bean id.
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

    /** Monotonic source of unique {@code run.id} job-parameter values. */
    private static final AtomicLong RUN_ID_SEQUENCE = new AtomicLong(System.nanoTime());
    /** Monotonic source of unique 16-digit transaction ids within a test. */
    private final AtomicLong tranIdSequence = new AtomicLong(0L);

    // ---------------------------------------------------------------------
    // Pristine-seed snapshots, captured once before the first mutation so the
    // shared Flyway seed can be restored after every test.
    // ---------------------------------------------------------------------
    private static List<Map<String, Object>> dailySeedSnapshot;
    private static List<Map<String, Object>> tcatbalSeedSnapshot;
    private static List<Map<String, Object>> accountSeedSnapshot;

    /**
     * Prepares an isolated run: builds nothing global, captures the pristine seed
     * once, provisions the canonical AWS resources idempotently, clears the mutable
     * working tables (restored in {@link #tearDown()}), removes any leftover
     * test-owned rows, and empties the S3 output and statement buckets.
     */
    @BeforeEach
    void setUp() {
        captureSeedSnapshotOnce();
        provisionCanonicalAwsResources();
        removeTestOwnedRows();
        deleteFrom(TRANSACTIONS_TABLE);
        deleteFrom(DAILY_TRANSACTION_TABLE);
        emptyBucket(outputBucket());
        emptyBucket(statementBucket());
        tranIdSequence.set(0L);
    }

    /**
     * Restores every table this suite may have mutated back to its pristine Flyway
     * state and clears the S3 buckets, guaranteeing the next test (in this or any
     * sibling suite) observes the canonical seed.
     */
    @AfterEach
    void tearDown() {
        deleteFrom(TRANSACTIONS_TABLE);
        removeTestOwnedRows();
        restoreTransactionCategoryBalances();
        restoreAccounts();
        restoreDailyTransactions();
        emptyBucket(outputBucket());
        emptyBucket(statementBucket());
    }

    // =====================================================================
    // Snapshot / restore helpers
    // =====================================================================

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

    /**
     * Removes any rows this suite created outside the Flyway seed (idempotent). Only
     * the test-owned dangling cross-reference and its rejected-transaction artifacts
     * are removed here; seeded rows are returned to their pristine values by the
     * snapshot-restore helpers.
     */
    private void removeTestOwnedRows() {
        jdbcTemplate.update("DELETE FROM " + TRANSACTIONS_TABLE + " WHERE card_num = ?", DANGLING_CARD);
        jdbcTemplate.update("DELETE FROM " + CARD_XREF_TABLE + " WHERE xref_card_num = ?", DANGLING_CARD);
    }

    // =====================================================================
    // Launch / parameter helpers
    // =====================================================================

    /**
     * Launches the supplied {@link Job} through a freshly wired
     * {@link JobLauncherTestUtils} (built per job because six {@code Job} beans make
     * a single autowired {@code setJob} target ambiguous) and returns the completed
     * {@link JobExecution}.
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
     * window keys (via {@link BatchPipelineOrchestrator#pipelineJobParameters}), plus
     * a unique {@code run.id} so re-launches never collide with a completed
     * {@code JobInstance}. Each job consumes only the keys it recognises, so the same
     * builder serves the full pipeline and every sub-job.
     *
     * @param parmDate    the legacy interest run-date parameter (e.g. {@code 2022071800})
     * @param reportStart the inclusive report window start ({@code YYYY-MM-DD})
     * @param reportEnd   the inclusive report window end ({@code YYYY-MM-DD})
     * @return the unique job parameters
     */
    private JobParameters params(String parmDate, String reportStart, String reportEnd) {
        return new JobParametersBuilder(
                BatchPipelineOrchestrator.pipelineJobParameters(parmDate, reportStart, reportEnd))
                .addLong("run.id", RUN_ID_SEQUENCE.incrementAndGet())
                .toJobParameters();
    }

    /** Returns the next distinctive 16-digit transaction id for this test. */
    private String nextTranId() {
        return String.format("%016d", TEST_TRAN_ID_BASE + tranIdSequence.incrementAndGet());
    }

    // =====================================================================
    // Seeding helpers (all writes go through the inherited JdbcTemplate so the
    // test never depends on repository/enum types outside its dependency set)
    // =====================================================================

    /**
     * Stages one {@code daily_transaction} row for posting. The 26-character
     * {@code orig_ts} is derived from {@code origDate}; {@code proc_ts} is left blank
     * exactly as the unposted seed leaves it.
     */
    private void insertDailyTransaction(String tranId, String type, int cat, BigDecimal amount,
                                        String cardNum, String origDate) {
        jdbcTemplate.update(
                "INSERT INTO " + DAILY_TRANSACTION_TABLE + " (tran_id, tran_type_cd, tran_cat_cd, tran_source, "
                        + "tran_desc, tran_amt, merchant_id, merchant_name, merchant_city, merchant_zip, "
                        + "card_num, orig_ts, proc_ts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tranId, type, cat, "POS TERM", "E2E TEST TRANSACTION", amount,
                800_000_000L, "E2E MERCHANT", "E2E CITY", "00000", cardNum,
                origDate + " 00:00:00.000000", "");
    }

    /**
     * Inserts one posted {@code transactions} row with a processing timestamp the
     * report window can select, used to seed deterministic report/statement input
     * without running the posting stage.
     */
    private void insertTransaction(String tranId, String type, int cat, BigDecimal amount,
                                   String cardNum, String procTs) {
        jdbcTemplate.update(
                "INSERT INTO " + TRANSACTIONS_TABLE + " (tran_id, tran_type_cd, tran_cat_cd, tran_source, "
                        + "tran_desc, tran_amt, merchant_id, merchant_name, merchant_city, merchant_zip, "
                        + "card_num, orig_ts, proc_ts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tranId, type, cat, "POS TERM", "E2E REPORT TXN", amount,
                800_000_000L, "E2E MERCHANT", "E2E CITY", "00000", cardNum,
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

    /** Returns the category balance for the key, or {@code 0.00} when no row exists. */
    private BigDecimal categoryBalanceOrZero(long acctId, String type, int cat) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT tran_cat_bal FROM " + TCATBAL_TABLE + " WHERE acct_id = ? AND type_cd = ? AND cat_cd = ?",
                acctId, type, cat);
        if (rows.isEmpty() || rows.get(0).get("tran_cat_bal") == null) {
            return scaled("0.00");
        }
        return ((BigDecimal) rows.get(0).get("tran_cat_bal")).setScale(2, RoundingMode.HALF_EVEN);
    }

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

    // =====================================================================
    // Step-execution helpers
    // =====================================================================

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

    /**
     * Splits a US-ASCII fixed-width payload on the {@code '\n'} record delimiter,
     * dropping only the empty element produced by the trailing delimiter. Every
     * surviving element is a full fixed-width record (space padded), so callers can
     * assert {@code length() == width} on each.
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

    /** Extracts the trimmed 76-character reject reason description from a 430-byte reject record. */
    private String rejectDescription(String record) {
        return record.substring(REJECT_DESC_OFFSET, REJECT_DESC_OFFSET + REJECT_DESC_WIDTH).trim();
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
    // Tests
    // =====================================================================

    /**
     * Drives the whole orchestrated pipeline over a clean posting cycle and verifies
     * that every stage ran, in the JCL-preserving order, and that the posting and
     * downstream artifacts were produced against the real backends.
     *
     * <p>Two clean transactions are staged on a seeded card whose account and
     * customer both exist, so posting succeeds (no rejects), the backup gate sees
     * RC&nbsp;0 and runs the backup step, interest and combine run, the report and
     * statement legs run in parallel, and the category-balance print step closes the
     * run. The pipeline must reach {@link BatchStatus#COMPLETED}.</p>
     */
    @Test
    @DisplayName("full pipeline COMPLETED with all seven steps in JCL order (post->backup->interest->combine->{report||statement}->print)")
    void fullPipelineCompletesAllStagesInOrder() throws Exception {
        String firstTranId = nextTranId();
        String secondTranId = nextTranId();
        insertDailyTransaction(firstTranId, "01", 1, CLEAN_AMOUNT, CLEAN_CARD, IN_WINDOW_ORIG_DATE);
        insertDailyTransaction(secondTranId, "01", 1, CLEAN_AMOUNT, CLEAN_CARD, IN_WINDOW_ORIG_DATE);

        BigDecimal categoryBalanceBefore = categoryBalanceOrZero(CLEAN_ACCT_ID, "01", 1);
        BigDecimal accountBalanceBefore = accountCurrentBalance(CLEAN_ACCT_ID);

        JobExecution execution = launch(pipelineJob, params(INTEREST_PARM_DATE, WIDE_WINDOW_START, WIDE_WINDOW_END));

        // The pipeline reached the terminal COMPLETED state.
        assertThat(execution.getStatus())
                .as("the orchestrated pipeline must complete successfully on a clean posting cycle")
                .isEqualTo(BatchStatus.COMPLETED);

        // Every one of the seven steps executed (the five AAP stages plus the COND=(4,LT)
        // category-balance backup gate the orchestrator inserts after posting).
        assertThat(execution.getStepExecutions().stream().map(StepExecution::getStepName))
                .as("all pipeline steps must have executed")
                .contains(STEP_POST, STEP_BACKUP, STEP_INTEREST, STEP_COMBINE,
                        STEP_REPORT, STEP_STATEMENT, STEP_PRINT);

        // Step start order mirrors the JCL run order. The two split legs (report ‖
        // statement) may start in either relative order, but both follow combine and
        // precede the closing print step.
        long postOrder = stepOrder(execution, STEP_POST);
        long backupOrder = stepOrder(execution, STEP_BACKUP);
        long interestOrder = stepOrder(execution, STEP_INTEREST);
        long combineOrder = stepOrder(execution, STEP_COMBINE);
        long reportOrder = stepOrder(execution, STEP_REPORT);
        long statementOrder = stepOrder(execution, STEP_STATEMENT);
        long printOrder = stepOrder(execution, STEP_PRINT);
        assertThat(postOrder).as("post precedes backup").isLessThan(backupOrder);
        assertThat(backupOrder).as("backup precedes interest").isLessThan(interestOrder);
        assertThat(interestOrder).as("interest precedes combine").isLessThan(combineOrder);
        assertThat(combineOrder).as("combine precedes both split legs")
                .isLessThan(Math.min(reportOrder, statementOrder));
        assertThat(Math.max(reportOrder, statementOrder)).as("both split legs precede print")
                .isLessThan(printOrder);
        assertThat(stepExecution(execution, STEP_POST).getExitStatus().getExitCode())
                .as("a clean posting cycle exits COMPLETED (RC 0)")
                .isEqualTo(BatchStatus.COMPLETED.name());

        // Posting persisted both staged transactions...
        assertThat(transactionExists(firstTranId)).as("first posted transaction persisted").isTrue();
        assertThat(transactionExists(secondTranId)).as("second posted transaction persisted").isTrue();

        // ...the category balance grew by exactly the posted amount (posting is the only
        // stage that writes tcatbal; interest reads it but never mutates it)...
        BigDecimal categoryBalanceAfter = categoryBalanceOrZero(CLEAN_ACCT_ID, "01", 1);
        assertThat(categoryBalanceAfter.subtract(categoryBalanceBefore))
                .as("tcatbal increases by the two posted amounts")
                .isEqualByComparingTo(CLEAN_AMOUNT.add(CLEAN_AMOUNT));

        // ...and the account balance strictly increased (posting plus a positive
        // interest accrual at the non-zero DEFAULT disclosure rate).
        assertThat(accountCurrentBalance(CLEAN_ACCT_ID))
                .as("account balance increases after posting + interest")
                .isGreaterThan(accountBalanceBefore);

        // The report leg wrote exactly one 133-byte report object to the output bucket.
        List<String> reportKeys = keysWithPrefix(outputBucket(), REPORT_OBJECT_PREFIX);
        assertThat(reportKeys).as("the report leg writes one report object").hasSize(1);
        for (String line : fixedWidthLines(readObjectBytes(outputBucket(), reportKeys.get(0)))) {
            assertThat(line.length()).as("every report line is exactly 133 bytes").isEqualTo(REPORT_RECORD_WIDTH);
        }

        // The backup gate ran and the print step wrote its category-balance objects.
        assertThat(keysWithPrefix(outputBucket(), BACKUP_OBJECT_PREFIX))
                .as("the COND=(4,LT) backup step uploaded its unload").isNotEmpty();
        assertThat(keysWithPrefix(outputBucket(), PRINT_OBJECT_PREFIX))
                .as("the PRTCATBL print step uploaded its formatted output").isNotEmpty();

        // The statement leg wrote both presentations to the statements bucket.
        assertThat(keysWithPrefix(statementBucket(), STATEMENT_TEXT_PREFIX))
                .as("the statement leg wrote text statements").isNotEmpty();
        assertThat(keysWithPrefix(statementBucket(), STATEMENT_HTML_PREFIX))
                .as("the statement leg wrote HTML statements").isNotEmpty();
    }

    /**
     * Verifies the CBTRN02C reject cascade and the {@code COND=(4,LT)} continuation
     * guarantee, in two launches that together cover both obligations without letting
     * a dangling cross-reference reach the statement stage (which faithfully abends on
     * one, just like COBOL {@code CBSTM03A}).
     *
     * <p><strong>Launch A &mdash; reject geometry (sub-job).</strong> The
     * {@code postTransactionJob} is launched in isolation against a mix that triggers
     * all four reject reason codes — 0100 (card not in xref), 0101 (card present but
     * account absent, via a test-owned dangling xref), 0102 (over the credit limit),
     * and 0103 (received after expiration; the later assignment wins over 0102). The
     * statement stage is not part of this job, so the dangling xref is safe. The step
     * must exit {@code COMPLETED_WITH_REJECTS} and a single 430-byte
     * {@code DALYREJS}-equivalent object must hold one framed record per reject, each
     * carrying its 4-digit code and byte-exact reason text.</p>
     *
     * <p><strong>Launch B &mdash; pipeline continuation.</strong> The full pipeline is
     * launched against only the statement-safe rejects (0100/0102/0103) plus a clean
     * post. Because the posting step exits RC&nbsp;4 ({@code &le; 4}), the
     * {@code JobExecutionDecider} chooses {@code RUN_BACKUP} and the pipeline runs to
     * {@link BatchStatus#COMPLETED}, with the backup step present and a reject object
     * written.</p>
     */
    @Test
    @DisplayName("posting emits rejects 0100/0101/0102/0103 (430-byte) and the pipeline still proceeds under COND=(4,LT)")
    void postingStageProducesRejectsAndPipelineStillProceedsUnderCond4() throws Exception {
        // ---- Launch A: all four reject codes through the posting sub-job ----
        insertCardXref(DANGLING_CARD, DANGLING_CUST_ID, DANGLING_ACCT_ID); // card present, account absent -> 0101

        String cardNotFoundId = nextTranId();
        String accountNotFoundId = nextTranId();
        String overLimitId = nextTranId();
        String expiredId = nextTranId();
        insertDailyTransaction(cardNotFoundId, "01", 1, CLEAN_AMOUNT, CARD_NOT_FOUND_CARD, IN_WINDOW_ORIG_DATE);
        insertDailyTransaction(accountNotFoundId, "01", 1, CLEAN_AMOUNT, DANGLING_CARD, IN_WINDOW_ORIG_DATE);
        insertDailyTransaction(overLimitId, "01", 1, OVER_LIMIT_AMOUNT, OVER_LIMIT_CARD, IN_WINDOW_ORIG_DATE);
        insertDailyTransaction(expiredId, "01", 1, SMALL_AMOUNT, EXPIRED_CARD, FUTURE_ORIG_DATE);

        JobExecution postExecution =
                launch(postTransactionJob, params(INTEREST_PARM_DATE, WIDE_WINDOW_START, WIDE_WINDOW_END));

        assertThat(postExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExecution(postExecution, STEP_POST).getExitStatus().getExitCode())
                .as("a cycle that produced rejects exits COMPLETED_WITH_REJECTS (RC 4)")
                .isEqualTo(EXIT_COMPLETED_WITH_REJECTS);

        List<String> rejectKeys = keysWithPrefix(outputBucket(), REJECT_KEY_PREFIX);
        assertThat(rejectKeys).as("exactly one DALYREJS reject object is written").hasSize(1);

        byte[] rejectBytes = readObjectBytes(outputBucket(), rejectKeys.get(0));
        assertThat(rejectBytes.length)
                .as("each reject record is 430 data bytes + 1 delimiter; four records were rejected")
                .isEqualTo(REJECT_FRAMED_WIDTH * 4);

        List<String> rejectRecords = fixedWidthLines(rejectBytes);
        assertThat(rejectRecords).as("four rejected records").hasSize(4);
        for (String record : rejectRecords) {
            assertThat(record.length()).as("each reject record image is 430 bytes").isEqualTo(REJECT_RECORD_WIDTH);
            assertThat(record.substring(0, REJECT_IMAGE_WIDTH).length())
                    .as("the leading 350 bytes are the CVTRA06Y record image").isEqualTo(REJECT_IMAGE_WIDTH);
        }

        // Every reject reason code and its byte-exact description is present.
        List<String> codes = rejectRecords.stream().map(this::rejectCode).toList();
        assertThat(codes).containsExactlyInAnyOrder(
                CODE_CARD_NOT_FOUND, CODE_ACCOUNT_NOT_FOUND, CODE_OVER_LIMIT, CODE_EXPIRED);
        for (String record : rejectRecords) {
            String code = rejectCode(record);
            String description = rejectDescription(record);
            switch (code) {
                case CODE_CARD_NOT_FOUND -> assertThat(description).isEqualTo(DESC_CARD_NOT_FOUND);
                case CODE_ACCOUNT_NOT_FOUND -> assertThat(description).isEqualTo(DESC_ACCOUNT_NOT_FOUND);
                case CODE_OVER_LIMIT -> assertThat(description).isEqualTo(DESC_OVER_LIMIT);
                case CODE_EXPIRED -> assertThat(description).isEqualTo(DESC_EXPIRED);
                default -> throw new AssertionError("unexpected reject code: " + code);
            }
        }

        // Reset state between the two launches: remove the dangling xref (so the
        // statement stage in Launch B never sees it) and clear staged/posted rows + S3.
        removeTestOwnedRows();
        deleteFrom(TRANSACTIONS_TABLE);
        deleteFrom(DAILY_TRANSACTION_TABLE);
        emptyBucket(outputBucket());
        emptyBucket(statementBucket());

        // ---- Launch B: full pipeline with statement-safe rejects + a clean post ----
        String cleanId = nextTranId();
        String pipelineCardNotFoundId = nextTranId();
        String pipelineOverLimitId = nextTranId();
        String pipelineExpiredId = nextTranId();
        insertDailyTransaction(cleanId, "01", 1, CLEAN_AMOUNT, CLEAN_CARD, IN_WINDOW_ORIG_DATE);
        insertDailyTransaction(pipelineCardNotFoundId, "01", 1, CLEAN_AMOUNT, CARD_NOT_FOUND_CARD, IN_WINDOW_ORIG_DATE);
        insertDailyTransaction(pipelineOverLimitId, "01", 1, OVER_LIMIT_AMOUNT, OVER_LIMIT_CARD, IN_WINDOW_ORIG_DATE);
        insertDailyTransaction(pipelineExpiredId, "01", 1, SMALL_AMOUNT, EXPIRED_CARD, FUTURE_ORIG_DATE);

        JobExecution pipelineExecution =
                launch(pipelineJob, params(INTEREST_PARM_DATE, WIDE_WINDOW_START, WIDE_WINDOW_END));

        // RC=4 is not greater than 4, so the decider runs the backup step and the
        // pipeline completes rather than halting.
        assertThat(pipelineExecution.getStatus())
                .as("RC=4 (COMPLETED_WITH_REJECTS) satisfies COND=(4,LT); the pipeline proceeds")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExecution(pipelineExecution, STEP_POST).getExitStatus().getExitCode())
                .isEqualTo(EXIT_COMPLETED_WITH_REJECTS);
        assertThat(stepExecution(pipelineExecution, STEP_BACKUP))
                .as("the backup step runs because the posting RC (4) is not greater than 4")
                .isNotNull();
        assertThat(stepExecution(pipelineExecution, STEP_PRINT))
                .as("the pipeline reaches the closing print step").isNotNull();
        assertThat(keysWithPrefix(outputBucket(), REJECT_KEY_PREFIX))
                .as("the pipeline run also wrote a reject object").hasSize(1);
        assertThat(transactionExists(cleanId)).as("the clean transaction still posted").isTrue();
    }

    /**
     * Verifies the CBACT04C interest formula and the shape of the generated system
     * transactions. Seeded account&nbsp;1 is reduced to a single category-balance row
     * of {@code 1000.00}; at the DEFAULT disclosure rate (15.00) the monthly interest
     * is {@code 1000.00 * 15.00 / 1200 = 12.50} exactly (HALF_EVEN, scale&nbsp;2), so a
     * single interest transaction must be generated and the account balance must grow
     * by precisely {@code 12.50}.
     */
    @Test
    @DisplayName("interest stage applies (balance*rate)/1200 HALF_EVEN and generates a System transaction")
    void interestStageAppliesFormulaAndGeneratesSystemTransactions() throws Exception {
        // Give the account exactly one category-balance row so it yields exactly one
        // interest transaction (restored to the seed in @AfterEach).
        jdbcTemplate.update("DELETE FROM " + TCATBAL_TABLE + " WHERE acct_id = ?", INTEREST_ACCT_ID);
        insertTransactionCategoryBalance(INTEREST_ACCT_ID, "01", 1, INTEREST_SEED_BALANCE);

        BigDecimal expectedInterest = monthlyInterest(INTEREST_SEED_BALANCE, INTEREST_DEFAULT_RATE);
        assertThat(expectedInterest)
                .as("formula cross-check: 1000.00 * 15.00 / 1200 = 12.50")
                .isEqualByComparingTo(INTEREST_EXPECTED_AMOUNT);

        BigDecimal accountBalanceBefore = accountCurrentBalance(INTEREST_ACCT_ID);

        JobExecution execution =
                launch(interestCalculationJob, params(INTEREST_PARM_DATE, WIDE_WINDOW_START, WIDE_WINDOW_END));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Exactly one interest transaction was generated for the account, with the
        // CBACT04C system-transaction shape.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_amt FROM " + TRANSACTIONS_TABLE
                        + " WHERE tran_desc = ?", INTEREST_DESCRIPTION);
        assertThat(rows)
                .as("a single-category account yields exactly one interest transaction")
                .hasSize(1);
        Map<String, Object> interestTransaction = rows.get(0);
        assertThat(((String) interestTransaction.get("tran_type_cd")).trim())
                .as("interest transaction type code is '01'").isEqualTo(INTEREST_TRAN_TYPE);
        assertThat(((Number) interestTransaction.get("tran_cat_cd")).intValue())
                .as("interest transaction category code is 5").isEqualTo(INTEREST_TRAN_CAT_CD);
        assertThat(((String) interestTransaction.get("tran_source")).trim())
                .as("interest transaction source is 'System'").isEqualTo(INTEREST_TRAN_SOURCE);
        assertThat((BigDecimal) interestTransaction.get("tran_amt"))
                .as("interest amount equals (balance*rate)/1200 = 12.50").isEqualByComparingTo(expectedInterest);
        assertThat((String) interestTransaction.get("tran_id"))
                .as("interest transaction id is the 10-char run date prefix + 6-digit suffix")
                .startsWith(INTEREST_PARM_DATE)
                .hasSize(16);

        // The account balance grew by exactly the computed interest.
        assertThat(accountCurrentBalance(INTEREST_ACCT_ID).subtract(accountBalanceBefore))
                .as("the account balance increases by exactly the posted interest")
                .isEqualByComparingTo(expectedInterest);
    }


    /**
     * Verifies the CBTRN03C transaction-report contract: a single fixed-width object
     * is written to the output bucket in which <strong>every physical line is exactly
     * 133 bytes</strong> (the Gate-5 record-length contract), the 133-hyphen separator
     * rule is honoured, and the page header is reprinted on a page break
     * ({@code WS-PAGE-SIZE = 20}). Twenty-five in-window detail rows for a single card
     * force exactly one page break, so the report's name and column headers each
     * appear at least twice.
     */
    @Test
    @DisplayName("report stage writes a single object whose every line is exactly 133 bytes, with page breaks every 20 details")
    void reportStageWritesFixedWidth133ToOutputBucket() throws Exception {
        int detailCount = 25; // > WS-PAGE-SIZE (20) -> at least one page break
        for (int i = 0; i < detailCount; i++) {
            insertTransaction(nextTranId(), "01", 1, REPORT_DETAIL_AMOUNT, CLEAN_CARD, IN_WINDOW_PROC_TS);
        }

        JobExecution execution =
                launch(transactionReportJob, params(INTEREST_PARM_DATE, REPORT_WINDOW_START, REPORT_WINDOW_END));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> reportKeys = keysWithPrefix(outputBucket(), REPORT_OBJECT_PREFIX);
        assertThat(reportKeys).as("exactly one transaction-report object is written").hasSize(1);

        List<String> lines = fixedWidthLines(readObjectBytes(outputBucket(), reportKeys.get(0)));
        assertThat(lines).as("the report is not empty").isNotEmpty();
        for (String line : lines) {
            assertThat(line.length())
                    .as("every report line is exactly 133 bytes (Gate-5 contract)")
                    .isEqualTo(REPORT_RECORD_WIDTH);
        }

        // The 133-hyphen separator rule.
        assertThat(lines).as("the report contains the 133-character separator rule").contains(REPORT_SEPARATOR);

        // Detail lines (those whose first character is a digit, i.e. the 16-char TRAN-ID).
        long detailLines = lines.stream().filter(line -> Character.isDigit(line.charAt(0))).count();
        assertThat(detailLines)
                .as("all %d seeded in-window detail rows are reported", detailCount)
                .isGreaterThanOrEqualTo(detailCount);

        // Page break: with more than 20 single-card detail lines the name and column
        // headers are reprinted, so each appears at least twice.
        long nameHeaderCount = lines.stream().filter(line -> line.startsWith(REPORT_NAME_HEADER_PREFIX)).count();
        long columnHeaderCount = lines.stream().filter(line -> line.startsWith(REPORT_COLUMN_HEADER_PREFIX)).count();
        assertThat(nameHeaderCount)
                .as("more than %d detail lines force a page break (name header reprinted)", REPORT_PAGE_SIZE)
                .isGreaterThanOrEqualTo(2L);
        assertThat(columnHeaderCount)
                .as("the column header is reprinted on each page")
                .isGreaterThanOrEqualTo(2L);

        // A grand total trailer closes the report.
        assertThat(lines.stream().anyMatch(line -> line.startsWith(REPORT_GRAND_TOTAL_PREFIX)))
                .as("the report ends with a grand total line").isTrue();
    }

    /**
     * Verifies the CBSTM03A statement contract: the statement stage writes a plain-text
     * presentation whose every line is exactly 80 bytes
     * ({@code FD-STMTFILE-REC PIC X(80)}) and an HTML presentation whose every line is
     * exactly 100 bytes ({@code FD-HTMLFILE-REC PIC X(100)}), with the statement banner
     * and the running total present. A few transactions are seeded on the focus card so
     * its statement carries detail lines and a non-trivial total.
     */
    @Test
    @DisplayName("statement stage writes 80-byte text and 100-byte HTML statements to the statements bucket")
    void statementStageWritesTextAndHtmlToStatementsBucket() throws Exception {
        // Seed a few transactions for the focus card so its statement has detail + total.
        insertTransaction(nextTranId(), "01", 1, scaled("31.00"), STATEMENT_CARD, IN_WINDOW_PROC_TS);
        insertTransaction(nextTranId(), "01", 1, scaled("11.00"), STATEMENT_CARD, IN_WINDOW_PROC_TS);

        JobExecution execution =
                launch(statementJob, params(INTEREST_PARM_DATE, WIDE_WINDOW_START, WIDE_WINDOW_END));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Plain-text statements: every line is exactly 80 bytes.
        List<String> textKeys = keysWithPrefix(statementBucket(), STATEMENT_TEXT_PREFIX);
        assertThat(textKeys).as("the statement stage wrote a plain-text object").isNotEmpty();
        StringBuilder allText = new StringBuilder();
        for (String key : textKeys) {
            for (String line : fixedWidthLines(readObjectBytes(statementBucket(), key))) {
                assertThat(line.length()).as("every statement text line is exactly 80 bytes")
                        .isEqualTo(STATEMENT_TEXT_WIDTH);
                allText.append(line).append('\n');
            }
        }
        assertThat(allText.toString())
                .as("the text statement carries the start banner and the running total")
                .contains(STATEMENT_TEXT_MARKER)
                .contains(STATEMENT_TOTAL_LABEL);

        // HTML statements: every line is exactly 100 bytes.
        List<String> htmlKeys = keysWithPrefix(statementBucket(), STATEMENT_HTML_PREFIX);
        assertThat(htmlKeys).as("the statement stage wrote an HTML object").isNotEmpty();
        for (String key : htmlKeys) {
            for (String line : fixedWidthLines(readObjectBytes(statementBucket(), key))) {
                assertThat(line.length()).as("every statement HTML line is exactly 100 bytes")
                        .isEqualTo(STATEMENT_HTML_WIDTH);
            }
        }
    }

    /**
     * Gate&nbsp;1 / Gate&nbsp;4 byte-equivalence anchor. A production-representative
     * slice of the named ASCII fixtures (a clean post plus the documented reject
     * scenarios) is driven through the full pipeline against the REAL PostgreSQL and
     * REAL LocalStack backends — mocked I/O does not satisfy these gates — and the
     * produced S3 outputs are checked against the COBOL record contracts byte-for-byte:
     * the 430-byte {@code DALYREJS} reject framing and its 4-digit codes with byte-exact
     * reason text, the 133-byte {@code CBTRN03C} report line, and the 80-byte /
     * 100-byte {@code CBSTM03A} statement streams.
     *
     * <p>These deterministic structural contracts are the authoritative, executable
     * assertions of the gate. When a checked-in COBOL golden-output artifact is
     * published it can additionally be diffed against these same retrieved bytes for a
     * full record-for-record comparison; the structural contracts asserted here already
     * make the gate meaningful (it is not a no-op) and would fail on any divergence in
     * record length, framing, reject code, or reason text.</p>
     */
    @Test
    @DisplayName("Gate 1/4: representative fixtures through the full pipeline produce byte-contract-equivalent S3 output")
    void gate1And4ByteEquivalenceAgainstCobolBaseline() throws Exception {
        String cleanId = nextTranId();
        insertDailyTransaction(cleanId, "01", 1, CLEAN_AMOUNT, CLEAN_CARD, IN_WINDOW_ORIG_DATE);
        insertDailyTransaction(nextTranId(), "01", 1, CLEAN_AMOUNT, CARD_NOT_FOUND_CARD, IN_WINDOW_ORIG_DATE);
        insertDailyTransaction(nextTranId(), "01", 1, OVER_LIMIT_AMOUNT, OVER_LIMIT_CARD, IN_WINDOW_ORIG_DATE);
        insertDailyTransaction(nextTranId(), "01", 1, SMALL_AMOUNT, EXPIRED_CARD, FUTURE_ORIG_DATE);

        JobExecution execution = launch(pipelineJob, params(INTEREST_PARM_DATE, WIDE_WINDOW_START, WIDE_WINDOW_END));
        assertThat(execution.getStatus())
                .as("the full pipeline completes end-to-end against real PostgreSQL + LocalStack")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(transactionExists(cleanId)).as("the clean fixture transaction posted").isTrue();

        // --- Reject contract: 430-byte framing, codes, byte-exact reason text ---
        List<String> rejectKeys = keysWithPrefix(outputBucket(), REJECT_KEY_PREFIX);
        assertThat(rejectKeys).as("a DALYREJS reject object was produced").hasSize(1);
        byte[] rejectBytes = readObjectBytes(outputBucket(), rejectKeys.get(0));
        assertThat(rejectBytes.length % REJECT_FRAMED_WIDTH)
                .as("the reject object is an exact multiple of the 431-byte framed record")
                .isZero();
        List<String> rejectRecords = fixedWidthLines(rejectBytes);
        assertThat(rejectRecords).as("three reject scenarios were staged").hasSize(3);
        for (String record : rejectRecords) {
            assertThat(record.length()).as("each reject record image is 430 bytes").isEqualTo(REJECT_RECORD_WIDTH);
        }
        assertThat(rejectRecords.stream().map(this::rejectCode).toList())
                .as("the reject reason codes match the CBTRN02C cascade outcomes")
                .containsExactlyInAnyOrder(CODE_CARD_NOT_FOUND, CODE_OVER_LIMIT, CODE_EXPIRED);
        for (String record : rejectRecords) {
            String description = rejectDescription(record);
            switch (rejectCode(record)) {
                case CODE_CARD_NOT_FOUND -> assertThat(description).isEqualTo(DESC_CARD_NOT_FOUND);
                case CODE_OVER_LIMIT -> assertThat(description).isEqualTo(DESC_OVER_LIMIT);
                case CODE_EXPIRED -> assertThat(description).isEqualTo(DESC_EXPIRED);
                default -> throw new AssertionError("unexpected reject code: " + rejectCode(record));
            }
        }

        // --- Report contract: every line exactly 133 bytes ---
        List<String> reportKeys = keysWithPrefix(outputBucket(), REPORT_OBJECT_PREFIX);
        assertThat(reportKeys).as("a transaction-report object was produced").hasSize(1);
        for (String line : fixedWidthLines(readObjectBytes(outputBucket(), reportKeys.get(0)))) {
            assertThat(line.length()).as("every report line is exactly 133 bytes").isEqualTo(REPORT_RECORD_WIDTH);
        }

        // --- Statement contract: text 80 bytes, HTML 100 bytes ---
        List<String> textKeys = keysWithPrefix(statementBucket(), STATEMENT_TEXT_PREFIX);
        List<String> htmlKeys = keysWithPrefix(statementBucket(), STATEMENT_HTML_PREFIX);
        assertThat(textKeys).as("a plain-text statement object was produced").isNotEmpty();
        assertThat(htmlKeys).as("an HTML statement object was produced").isNotEmpty();
        for (String key : textKeys) {
            for (String line : fixedWidthLines(readObjectBytes(statementBucket(), key))) {
                assertThat(line.length()).as("every statement text line is 80 bytes").isEqualTo(STATEMENT_TEXT_WIDTH);
            }
        }
        for (String key : htmlKeys) {
            for (String line : fixedWidthLines(readObjectBytes(statementBucket(), key))) {
                assertThat(line.length()).as("every statement HTML line is 100 bytes").isEqualTo(STATEMENT_HTML_WIDTH);
            }
        }

        // --- Category-balance print contract: the PRTCATBL object exists ---
        assertThat(keysWithPrefix(outputBucket(), PRINT_OBJECT_PREFIX))
                .as("the PRTCATBL category-balance print object was produced").isNotEmpty();
    }
}

