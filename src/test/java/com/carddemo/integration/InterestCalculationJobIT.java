/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.job.InterestCalculationJobConfig;
import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import com.carddemo.entity.Transaction;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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

/**
 * Testcontainers integration test for the {@code interestCalculationJob} Spring Batch job — the
 * Java migration of the COBOL interest calculator {@code CBACT04C} (driven on the mainframe by
 * {@code INTCALC.jcl} with {@code PARM='2022071800'}).
 *
 * <p><strong>Behavioral parity under test (CBACT04C):</strong> the job reads the
 * {@code transaction_category_balance} store sequentially in key order, control-breaking on the
 * account id. For every category-balance row it resolves the disclosure-group interest rate; when
 * that rate is non-zero it computes the monthly interest and emits a {@code System} interest
 * transaction. At each account break (and again at end-of-file) the accumulated interest is added
 * to the account {@code currBal} and the cycle credit/debit buckets are reset to zero — mirroring
 * the COBOL {@code 1050-UPDATE-ACCOUNT} paragraph. When the disclosure rate is zero the row is
 * skipped and no transaction is produced (COBOL {@code IF DIS-INT-RATE NOT = 0}).</p>
 *
 * <p><strong>Interest formula.</strong> CBACT04C computes
 * {@code WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. The COBOL {@code COMPUTE} carries
 * no {@code ROUNDED} clause, but the migration contract (AAP §0.6.1) mandates
 * {@link RoundingMode#HALF_EVEN} at scale 2, and the production {@code InterestProcessor} implements
 * {@code tranCatBal.multiply(rate).divide(new BigDecimal("1200"), 2, HALF_EVEN)}. This focused job
 * IT therefore asserts consistency with the <em>implemented</em> processor formula (HALF_EVEN,
 * scale 2); strict bit-for-bit COBOL golden-file comparison is the responsibility of the
 * {@code gates/} tests. To avoid any truncation-vs-HALF_EVEN ambiguity the precision-sensitive
 * tests seed balances and rates whose exact product divided by 1200 already has at most two decimal
 * places.</p>
 *
 * <p><strong>Infrastructure.</strong> Exercises the real PostgreSQL container (with the Flyway
 * V1/V2/V3 schema and seed) and the real LocalStack container provided by
 * {@link AbstractIntegrationIT}. There are no mocks, no in-memory database and no live-AWS
 * dependencies. The class is deliberately <em>not</em> {@code @Transactional}: Spring Batch owns its
 * own commit boundaries, so wrapping the test in a rolled-back transaction would hide the very
 * writes being verified. Isolation is achieved instead by snapshot/restore in
 * {@link #snapshotMutableState()} / {@link #restoreMutableState()}.</p>
 *
 * <p><strong>JobLauncherTestUtils wiring.</strong> The application context contains six {@code Job}
 * beans, which makes {@code JobLauncherTestUtils#setJob} autowiring ambiguous. This test follows the
 * robust manual pattern: the {@code interestCalculationJob} bean is injected by name and a
 * {@link JobLauncherTestUtils} instance is assembled by hand in {@link #initJobLauncherTestUtils()}.</p>
 */
@DisplayName("interestCalculationJob (CBACT04C) — Testcontainers integration test")
class InterestCalculationJobIT extends AbstractIntegrationIT {

    /** Transactions table — emptied before each test and after each test (generated output). */
    private static final String TRANSACTIONS_TABLE = "transactions";

    /** Category-balance table — the sequential input the job reads. */
    private static final String CATEGORY_BALANCE_TABLE = "transaction_category_balance";

    /**
     * Description prefix emitted by {@code 1300-B-WRITE-TX}: {@code "Int. for a/c "}. The COBOL
     * description is this 13-character prefix followed by the zero-padded 11-digit account id.
     */
    private static final String INTEREST_DESCRIPTION_PREFIX = "Int. for a/c ";

    /** Disclosure-group fallback id used by CBACT04C when the account's own group lookup misses. */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /** Monthly-interest divisor from the COBOL formula {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. */
    private static final BigDecimal INTEREST_DIVISOR = new BigDecimal("1200");

    /** Monetary scale for all interest arithmetic (two decimal places). */
    private static final int MONETARY_SCALE = 2;

    /** Category code stamped on every interest transaction ({@code TRAN-CAT-CD = 5}). */
    private static final int INTEREST_TRAN_CAT_CD = 5;

    /** Source label stamped on every interest transaction ({@code TransactionSource.SYSTEM}). */
    private static final String INTEREST_TRAN_SOURCE = "System";

    /** Total transaction-id length: a 10-character run-date prefix plus a 6-digit suffix. */
    private static final int TRAN_ID_LENGTH = 16;

    /** Job-parameter key used to make every launch a fresh {@code JobInstance}. */
    private static final String RUN_ID_KEY = "run.id";

    /**
     * Monotonic sequence guaranteeing a unique {@code run.id} per launch within the JVM, so each
     * launch creates a new {@code JobInstance} and never trips
     * {@code JobInstanceAlreadyCompleteException}.
     */
    private static final AtomicLong RUN_ID_SEQUENCE = new AtomicLong(System.nanoTime());

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    @Qualifier("interestCalculationJob")
    private Job interestCalculationJob;

    @Autowired
    private InterestCalculationJobConfig interestCalculationJobConfig;

    @Autowired
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    /** Manually assembled batch test harness (see class javadoc for the multi-job rationale). */
    private JobLauncherTestUtils jobLauncherTestUtils;

    /** Pre-run snapshot of every account row, restored after each test. */
    private List<Map<String, Object>> accountSnapshot;

    /** Pre-run snapshot of every category-balance row, restored after each test. */
    private List<Map<String, Object>> categoryBalanceSnapshot;

    /**
     * Resets generated output, captures the mutable seed state and assembles the
     * {@link JobLauncherTestUtils} harness before every test.
     */
    @BeforeEach
    void setUpInterestCalculationFixture() {
        deleteFrom(TRANSACTIONS_TABLE);
        snapshotMutableState();
        initJobLauncherTestUtils();
    }

    /**
     * Restores the mutable seed state so that this suite — and the sibling {@code gates/} tests —
     * remain deterministic regardless of the per-test mutations applied above.
     */
    @AfterEach
    void tearDownInterestCalculationFixture() {
        restoreMutableState();
    }

    // ---------------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("launches with the documented default run date and completes successfully")
    void jobCompletesSuccessfully() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(defaultRunDateParameters());

        assertThat(execution.getStatus())
                .as("interestCalculationJob must finish with batch status COMPLETED")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("interestCalculationJob must finish with exit code COMPLETED")
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    @Test
    @DisplayName("emits one System interest transaction per non-zero-rate category-balance row")
    void generatesSystemInterestTransactionsForNonZeroRateRows() throws Exception {
        // Number of category-balance rows whose EFFECTIVE disclosure rate (after DEFAULT fallback)
        // is non-zero: this is exactly how many interest transactions the job should produce.
        long expectedTransactionCount = transactionCategoryBalanceRepository.findAll().stream()
                .filter(balance -> {
                    BigDecimal rate = resolveEffectiveRate(
                            balance.getId().getAcctId(),
                            balance.getId().getTypeCd(),
                            balance.getId().getCatCd());
                    return rate != null && rate.signum() != 0;
                })
                .count();
        assertThat(expectedTransactionCount)
                .as("seed precondition: at least one category-balance row resolves to a non-zero rate")
                .isPositive();

        runWithDefaultRunDate();

        List<Transaction> generated = transactionRepository.findAll();
        assertThat(generated)
                .as("the job must generate the interest transactions into the empty transactions table")
                .isNotEmpty();
        assertThat((long) generated.size())
                .as("exactly one System interest transaction per non-zero-rate category-balance row")
                .isEqualTo(expectedTransactionCount);

        for (Transaction tx : generated) {
            assertThat(tx.getTransactionType())
                    .as("interest transactions carry transaction type '01' (PURCHASE) per CBACT04C")
                    .isEqualTo(TransactionTypeCode.PURCHASE);
            assertThat(tx.getTranCatCd())
                    .as("interest transactions carry category code 5")
                    .isEqualTo(INTEREST_TRAN_CAT_CD);
            assertThat(tx.getTranSource())
                    .as("interest transactions carry the System source label")
                    .isEqualTo(INTEREST_TRAN_SOURCE);
            assertThat(tx.getTranDesc())
                    .as("interest transaction description begins with the CBACT04C prefix")
                    .startsWith(INTEREST_DESCRIPTION_PREFIX);
            assertThat(tx.getTranId())
                    .as("interest transaction id is a 10-char run date prefix plus a 6-digit suffix")
                    .hasSize(TRAN_ID_LENGTH);
            assertThat(tx.getMerchantId())
                    .as("interest transactions carry a zero merchant id")
                    .isEqualTo(0L);

            long accountId = accountIdFromDescription(tx.getTranDesc());
            String expectedCardNumber =
                    cardXrefRepository.findByXrefAcctId(accountId).get(0).getXrefCardNum().trim();
            assertThat(tx.getCardNum().trim())
                    .as("interest transaction card number equals the account's card xref entry")
                    .isEqualTo(expectedCardNumber);
        }
    }

    @Test
    @DisplayName("interest amount equals balance * rate / 1200 (HALF_EVEN, scale 2)")
    void interestAmountMatchesFormula() throws Exception {
        long accountId = 1L;
        // Seed an exact balance whose product with the resolved rate / 1200 has <= 2 decimals:
        // 1000.00 * 15.00 / 1200 = 12.50 exactly.
        BigDecimal balance = new BigDecimal("1000.00");
        setCategoryBalance(accountId, "01", 1, balance);

        BigDecimal rate = resolveEffectiveRate(accountId, "01", 1);
        assertThat(rate)
                .as("seed precondition: account %d resolves to a disclosure rate (DEFAULT fallback)", accountId)
                .isNotNull();
        assertThat(rate.signum())
                .as("seed precondition: the resolved rate is non-zero so interest is produced")
                .isNotZero();
        BigDecimal expectedInterest = computeExpectedInterest(balance, rate);

        runWithDefaultRunDate();

        List<Transaction> accountTransactions = interestTransactionsForAccount(accountId);
        assertThat(accountTransactions)
                .as("account %d has a single category-balance row, hence exactly one interest transaction", accountId)
                .hasSize(1);
        assertThat(accountTransactions.get(0).getTranAmt().compareTo(expectedInterest))
                .as("interest amount must equal balance*rate/1200 (HALF_EVEN, scale 2) = %s", expectedInterest)
                .isZero();
    }

    @Test
    @DisplayName("a zero disclosure rate produces no interest transaction")
    void zeroRateRowsProduceNoTransaction() throws Exception {
        long accountId = 50L;
        // Replace the account's seeded ('01',1) row with a combination whose DEFAULT-group rate is
        // zero (DEFAULT '02'/1 = 0.00), leaving the account with only a zero-rate row.
        jdbcTemplate.update(
                "DELETE FROM transaction_category_balance WHERE acct_id = ?", accountId);
        jdbcTemplate.update(
                "INSERT INTO transaction_category_balance (acct_id, type_cd, cat_cd, tran_cat_bal) "
                        + "VALUES (?, ?, ?, ?)",
                accountId, "02", 1, new BigDecimal("500.00"));

        BigDecimal rate = resolveEffectiveRate(accountId, "02", 1);
        assertThat(rate)
                .as("seed precondition: the DEFAULT('02',1) disclosure row exists")
                .isNotNull();
        assertThat(rate.signum())
                .as("seed precondition: the chosen disclosure rate is zero")
                .isZero();

        runWithDefaultRunDate();

        assertThat(interestTransactionsForAccount(accountId))
                .as("a zero disclosure rate must yield NO interest transaction (CBACT04C: IF DIS-INT-RATE NOT = 0)")
                .isEmpty();
    }

    @Test
    @DisplayName("falls back to the DEFAULT disclosure group when the account's own group is missing")
    void defaultGroupFallbackUsedWhenSpecificGroupMissing() throws Exception {
        long accountId = 4L;
        String ownGroupId = accountRepository.findById(accountId).orElseThrow().getGroupId();

        // The account's own disclosure-group key is absent (COBOL status 23) ...
        assertThat(disclosureGroupRepository.findById(new DisclosureGroupId(ownGroupId, "01", 1)))
                .as("account %d own group '%s' has no ('01',1) disclosure row — forces DEFAULT fallback",
                        accountId, ownGroupId)
                .isEmpty();
        // ... but the DEFAULT-group entry exists and supplies the fallback rate.
        var defaultEntry = disclosureGroupRepository.findById(new DisclosureGroupId(DEFAULT_GROUP_ID, "01", 1));
        assertThat(defaultEntry)
                .as("DEFAULT group must provide the fallback ('01',1) disclosure rate")
                .isPresent();
        BigDecimal defaultRate = defaultEntry.orElseThrow().getDisIntRate();
        assertThat(defaultRate.signum())
                .as("seed precondition: the DEFAULT fallback rate is non-zero")
                .isNotZero();

        BigDecimal balance = new BigDecimal("1200.00");
        setCategoryBalance(accountId, "01", 1, balance);
        BigDecimal expectedInterest = computeExpectedInterest(balance, defaultRate);

        runWithDefaultRunDate();

        List<Transaction> accountTransactions = interestTransactionsForAccount(accountId);
        assertThat(accountTransactions)
                .as("the DEFAULT-fallback rate must still produce an interest transaction for account %d", accountId)
                .hasSize(1);
        assertThat(accountTransactions.get(0).getTranAmt().compareTo(expectedInterest))
                .as("interest must be computed from the DEFAULT-group rate %s: expected %s",
                        defaultRate, expectedInterest)
                .isZero();
    }

    @Test
    @DisplayName("adds accrued interest to currBal and resets cycle buckets at the control break")
    void accountBalanceUpdatedAndCycleBucketsResetAtControlBreak() throws Exception {
        long accountId = 3L;
        // Give the account a non-zero balance (so interest is non-zero) and non-zero cycle buckets
        // (so we can prove they are reset). 2400.00 * 15.00 / 1200 = 30.00 exactly.
        BigDecimal balance = new BigDecimal("2400.00");
        setCategoryBalance(accountId, "01", 1, balance);
        jdbcTemplate.update(
                "UPDATE accounts SET curr_cyc_credit = ?, curr_cyc_debit = ? WHERE acct_id = ?",
                new BigDecimal("123.45"), new BigDecimal("678.90"), accountId);

        BigDecimal rate = resolveEffectiveRate(accountId, "01", 1);
        assertThat(rate)
                .as("seed precondition: account %d resolves to a disclosure rate", accountId)
                .isNotNull();
        BigDecimal expectedInterest = computeExpectedInterest(balance, rate);
        BigDecimal preRunBalance = currentBalanceOf(accountId);
        BigDecimal expectedBalance = preRunBalance.add(expectedInterest);

        runWithDefaultRunDate();

        assertThat(currentBalanceOf(accountId).compareTo(expectedBalance))
                .as("account currBal must increase by the summed monthly interest (1050-UPDATE-ACCOUNT): expected %s",
                        expectedBalance)
                .isZero();
        assertThat(currentCycleCreditOf(accountId).compareTo(BigDecimal.ZERO))
                .as("currCycCredit must be reset to zero at the account control break")
                .isZero();
        assertThat(currentCycleDebitOf(accountId).compareTo(BigDecimal.ZERO))
                .as("currCycDebit must be reset to zero at the account control break")
                .isZero();
    }

    @Test
    @DisplayName("an explicit run-date parameter prefixes every generated transaction id")
    void explicitRunDateParameterPrefixesTranIds() throws Exception {
        // Use a run date distinct from the documented default to prove the parameter is honored.
        String explicitRunDate = InterestCalculationJobConfig.formatRunDate(LocalDate.of(2023, 1, 15));

        runWithExplicitRunDate(explicitRunDate);

        List<Transaction> generated = transactionRepository.findAll();
        assertThat(generated)
                .as("an explicit run-date launch must still generate interest transactions")
                .isNotEmpty();
        for (Transaction tx : generated) {
            assertThat(tx.getTranId())
                    .as("each transaction id must begin with the explicit run-date prefix and be 16 chars")
                    .startsWith(explicitRunDate)
                    .hasSize(TRAN_ID_LENGTH);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Assembles the {@link JobLauncherTestUtils} harness by hand. A {@code @Bean}-based harness
     * cannot be used because the context holds six {@code Job} beans, making {@code setJob}
     * autowiring ambiguous.
     */
    private void initJobLauncherTestUtils() {
        jobLauncherTestUtils = new JobLauncherTestUtils();
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJobRepository(jobRepository);
        jobLauncherTestUtils.setJob(interestCalculationJob);
    }

    /**
     * Builds job parameters using the documented default run date
     * ({@code carddemo.batch.interest.default-run-date}) plus a unique {@code run.id}.
     */
    private JobParameters defaultRunDateParameters() {
        return new JobParametersBuilder(interestCalculationJobConfig.defaultJobParameters())
                .addLong(RUN_ID_KEY, RUN_ID_SEQUENCE.incrementAndGet())
                .toJobParameters();
    }

    /**
     * Builds job parameters using an explicit run-date string plus a unique {@code run.id}.
     *
     * @param parmDate the 10-character {@code yyyyMMdd00} run-date string the processor prefixes
     *                 onto every generated transaction id
     */
    private JobParameters explicitRunDateParameters(String parmDate) {
        return new JobParametersBuilder()
                .addString(InterestCalculationJobConfig.RUN_DATE_PARAMETER_KEY, parmDate)
                .addLong(RUN_ID_KEY, RUN_ID_SEQUENCE.incrementAndGet())
                .toJobParameters();
    }

    /** Launches the job with the default run date and asserts it completed. */
    private JobExecution runWithDefaultRunDate() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(defaultRunDateParameters());
        assertThat(execution.getStatus())
                .as("interestCalculationJob (default run date) must complete before assertions")
                .isEqualTo(BatchStatus.COMPLETED);
        return execution;
    }

    /** Launches the job with an explicit run date and asserts it completed. */
    private JobExecution runWithExplicitRunDate(String parmDate) throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(explicitRunDateParameters(parmDate));
        assertThat(execution.getStatus())
                .as("interestCalculationJob (explicit run date %s) must complete before assertions", parmDate)
                .isEqualTo(BatchStatus.COMPLETED);
        return execution;
    }

    /**
     * Resolves the effective disclosure interest rate for a category-balance key exactly as
     * {@code InterestProcessor#resolveInterestRate} does: look up the account's own disclosure
     * group first, then fall back to the {@code DEFAULT} group when that key is absent.
     *
     * @return the resolved rate, or {@code null} when neither the specific nor the DEFAULT key exists
     */
    private BigDecimal resolveEffectiveRate(long accountId, String typeCode, int categoryCode) {
        String groupId = accountRepository.findById(accountId)
                .orElseThrow(() -> new AssertionError("seed is missing account " + accountId))
                .getGroupId();
        var disclosure = disclosureGroupRepository.findById(
                new DisclosureGroupId(groupId, typeCode, categoryCode));
        if (disclosure.isEmpty()) {
            disclosure = disclosureGroupRepository.findById(
                    new DisclosureGroupId(DEFAULT_GROUP_ID, typeCode, categoryCode));
        }
        return disclosure.map(DisclosureGroup::getDisIntRate).orElse(null);
    }

    /** Computes the expected monthly interest using the implemented processor formula. */
    private static BigDecimal computeExpectedInterest(BigDecimal balance, BigDecimal rate) {
        return balance.multiply(rate).divide(INTEREST_DIVISOR, MONETARY_SCALE, RoundingMode.HALF_EVEN);
    }

    /** Returns the CBACT04C description string for the given account id. */
    private static String descriptionFor(long accountId) {
        return INTEREST_DESCRIPTION_PREFIX + String.format("%011d", accountId);
    }

    /** Parses the account id embedded in a CBACT04C interest description. */
    private static long accountIdFromDescription(String description) {
        return Long.parseLong(description.substring(INTEREST_DESCRIPTION_PREFIX.length()).trim());
    }

    /** Returns the generated interest transactions for a single account (matched by description). */
    private List<Transaction> interestTransactionsForAccount(long accountId) {
        String description = descriptionFor(accountId);
        return transactionRepository.findAll().stream()
                .filter(tx -> description.equals(tx.getTranDesc()))
                .toList();
    }

    /** Sets the balance of a single category-balance row (no row is created/removed). */
    private void setCategoryBalance(long accountId, String typeCode, int categoryCode, BigDecimal balance) {
        int updated = jdbcTemplate.update(
                "UPDATE transaction_category_balance SET tran_cat_bal = ? "
                        + "WHERE acct_id = ? AND type_cd = ? AND cat_cd = ?",
                balance, accountId, typeCode, categoryCode);
        assertThat(updated)
                .as("expected to update exactly one category-balance row for account %d", accountId)
                .isEqualTo(1);
    }

    /** Reads the current balance directly from PostgreSQL (bypassing any JPA first-level cache). */
    private BigDecimal currentBalanceOf(long accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT curr_bal FROM accounts WHERE acct_id = ?", BigDecimal.class, accountId);
    }

    /** Reads the current cycle credit directly from PostgreSQL. */
    private BigDecimal currentCycleCreditOf(long accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT curr_cyc_credit FROM accounts WHERE acct_id = ?", BigDecimal.class, accountId);
    }

    /** Reads the current cycle debit directly from PostgreSQL. */
    private BigDecimal currentCycleDebitOf(long accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT curr_cyc_debit FROM accounts WHERE acct_id = ?", BigDecimal.class, accountId);
    }

    /** Captures the mutable seed rows (accounts and category balances) before a test mutates them. */
    private void snapshotMutableState() {
        accountSnapshot = jdbcTemplate.queryForList(
                "SELECT acct_id, curr_bal, curr_cyc_credit, curr_cyc_debit, version FROM accounts");
        categoryBalanceSnapshot = jdbcTemplate.queryForList(
                "SELECT acct_id, type_cd, cat_cd, tran_cat_bal FROM transaction_category_balance");
    }

    /**
     * Restores the mutable seed rows captured by {@link #snapshotMutableState()} and removes any
     * generated transactions, so the next test (and the sibling {@code gates/} tests) see the
     * pristine Flyway seed.
     */
    private void restoreMutableState() {
        deleteFrom(TRANSACTIONS_TABLE);

        for (Map<String, Object> row : accountSnapshot) {
            jdbcTemplate.update(
                    "UPDATE accounts SET curr_bal = ?, curr_cyc_credit = ?, curr_cyc_debit = ?, version = ? "
                            + "WHERE acct_id = ?",
                    row.get("curr_bal"),
                    row.get("curr_cyc_credit"),
                    row.get("curr_cyc_debit"),
                    row.get("version"),
                    row.get("acct_id"));
        }

        deleteFrom(CATEGORY_BALANCE_TABLE);
        for (Map<String, Object> row : categoryBalanceSnapshot) {
            jdbcTemplate.update(
                    "INSERT INTO transaction_category_balance (acct_id, type_cd, cat_cd, tran_cat_bal) "
                            + "VALUES (?, ?, ?, ?)",
                    row.get("acct_id"),
                    row.get("type_cd"),
                    row.get("cat_cd"),
                    row.get("tran_cat_bal"));
        }
    }
}
