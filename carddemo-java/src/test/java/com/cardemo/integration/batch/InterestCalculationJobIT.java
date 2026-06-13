/*
 * ============================================================================
 *  CardDemo — Greenfield Java 25 LTS + Spring Boot 3.x Migration
 *  Stage-2 Interest Calculation — Spring Batch JOB integration test
 * ============================================================================
 *
 *  PROVENANCE & TRACEABILITY (AAP §0.7.1 / §0.7.2)
 *  Net-new greenfield parity test with NO COBOL source equivalent. It validates
 *  the production {@code interestCalculationJob}, the Java migration of JCL
 *  app/jcl/INTCALC.jcl (//STEP15 EXEC PGM=CBACT04C,PARM='2022071800') driving
 *  COBOL app/cbl/CBACT04C.cbl (the interest calculator). The COBOL/JCL sources
 *  and the app/data/ASCII/*.txt fixtures (tcatbal.txt, discgrp.txt, acctdata.txt)
 *  are read-only reference and are NEVER copied into this repository; traceability
 *  is by the frozen baseline commit SHA 27d6c6f only. Base package is com.cardemo
 *  (decision D-006 — deliberately NOT com.carddemo).
 * ============================================================================
 */
package com.cardemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.DisclosureGroupRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Integration test for <strong>Stage&nbsp;2</strong> of the CardDemo Spring Batch pipeline — the
 * production {@code interestCalculationJob}, the faithful Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x
 * migration of JCL {@code INTCALC.jcl} driving COBOL {@code CBACT04C} (interest &amp; fee posting).
 *
 * <h2>Why this IT is the decimal-fidelity "crown jewel" of the batch suite</h2>
 * <p>{@code CBACT04C} is the program where money is <em>created</em> from the interest formula, so this
 * test is the primary home of <strong>decimal fidelity</strong> and JPA <strong>{@code @Version}</strong>
 * verification among the batch ITs (the sibling {@code TransactionReportJobIT} owns the report-total
 * decimals). It proves, end-to-end against a real PostgreSQL&nbsp;16 + LocalStack surface, that:</p>
 * <ol>
 *   <li>the interest formula {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} is reproduced with
 *       {@link BigDecimal} at scale&nbsp;2 using {@link RoundingMode#HALF_EVEN} (banker's rounding),
 *       <strong>not</strong> {@code HALF_UP} and with <strong>no</strong> algebraic rearrangement
 *       (AAP §0.7.3 / §0.7.6) — {@code CBACT04C} {@code 1300-COMPUTE-INTEREST};</li>
 *   <li>the {@code DEFAULT}-group fallback resolves the rate when an account's own group is unrated
 *       — {@code 1200-A-GET-DEFAULT-INT-RATE};</li>
 *   <li>a zero rate produces <strong>no</strong> interest transaction and leaves the balance unchanged
 *       — the COBOL {@code IF DIS-INT-RATE NOT = 0} guard;</li>
 *   <li>each interest transaction is staged to S3 ({@code carddemo-batch-output} under {@code systran/};
 *       the {@code SYSTRAN(+1)} GDG &rarr; S3 substitution) and every account — including the
 *       <strong>final</strong> account in sort order, which can only be flushed at end-of-file — is
 *       rolled up ({@code 1050-UPDATE-ACCOUNT}: add interest to balance, zero the cycle credit/debit);</li>
 *   <li>{@code Account} carries a JPA {@code @Version} optimistic lock (the migration of the COACTUPC
 *       read-update before/after image comparison; AAP §0.3.3 L510 / §0.7.5).</li>
 * </ol>
 *
 * <h2>Harness &amp; conventions</h2>
 * <p>Extends {@link AbstractBatchJobIT}, inheriting the singleton PostgreSQL&nbsp;16 + LocalStack
 * containers, the {@code @DynamicPropertySource} wiring, the AWS provisioning/teardown lifecycle, and the
 * {@code launchJob}/{@code uniqueParams}/{@code clearJobRepository}/{@code listKeys}/{@code countObjects}/
 * {@code emptyBucket} helpers — none of which are redeclared here. The {@code *IT} suffix routes this
 * class to the {@code maven-failsafe-plugin} under {@code mvn verify -Pintegration}. The job is autowired
 * <strong>by bean name</strong> ({@code interestCalculationJob}); the late-bound {@code parmDate} job
 * parameter (the {@code PARM='2022071800'} run date) is supplied via the inherited
 * {@code uniqueParams(customizer)} overload.</p>
 *
 * <h2>Assertion strategy (assert via repository final-state + S3 object presence)</h2>
 * <p>The 9 canonical ASCII fixtures are Flyway-seeded inside the throwaway PostgreSQL container: 50
 * accounts (all in group {@code A000000000}), 51 disclosure-group rates (the {@code A000000000},
 * {@code DEFAULT} and an all-zero {@code ZEROAPR} block), and 50 {@code TCATBAL} rows
 * (each {@code (account, '01', 1)} with a seeded balance of {@code 0.00}). Because every seeded balance
 * is zero, meaningful interest scenarios are <strong>arranged</strong> per test (a permitted, FK-safe
 * setup — {@code account.group_id} has no foreign key, and {@code TCATBAL} keys only the account).
 * Expected values are computed <strong>at runtime from the repositories with the exact production
 * formula</strong> (never hard-coded tech-spec numbers), every monetary assertion uses {@code compareTo}
 * (via AssertJ {@code isEqualByComparingTo}, never scale-sensitive {@code equals}), and the shared,
 * non-transactional context is kept clean by snapshotting balances and restoring all arranged state in a
 * {@code finally} block.</p>
 *
 * @see AbstractBatchJobIT
 */
// Create the Spring Batch metadata schema (BATCH_JOB_INSTANCE, BATCH_JOB_EXECUTION, ...) in the
// Testcontainers PostgreSQL: the Flyway migrations provision only business tables, and for a
// non-embedded database `spring.batch.jdbc.initialize-schema` defaults to `embedded` (a no-op). The
// inherited `clearJobRepository()` (@BeforeEach) and every `launchJob(...)` require these tables. This
// IT owns a single cached ApplicationContext, so the PostgreSQL batch DDL runs exactly once.
@TestPropertySource(properties = "spring.batch.jdbc.initialize-schema=always")
@Import(InterestCalculationJobIT.SecurityCorsTestConfig.class)
class InterestCalculationJobIT extends AbstractBatchJobIT {

    // -------------------------------------------------------------------------
    // Parity constants (mirroring INTCALC.jcl / CBACT04C / the Flyway seed).
    // -------------------------------------------------------------------------

    /** The 10-character run date carried by {@code INTCALC.jcl} {@code PARM='2022071800'}. */
    private static final String PARM_DATE = "2022071800";

    /** Seeded {@code TCATBAL} transaction-type code for every fixture row ({@code TRANCAT-TYPE-CD}). */
    private static final String TYPE_01 = "01";

    /** Seeded {@code TCATBAL} transaction-category code for every fixture row ({@code TRANCAT-CD}). */
    private static final int CAT_1 = 1;

    /** The disclosure group every seeded account belongs to (rated for {@code (01,1)} at 15.00). */
    private static final String GROUP_A = "A000000000";

    /** The literal {@code DEFAULT} disclosure group ({@code CBACT04C} {@code 1200-A-GET-DEFAULT-INT-RATE}). */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /** The seeded all-zero-rate disclosure group, used to exercise the zero-rate path. */
    private static final String ZERO_APR_GROUP = "ZEROAPR";

    /** A disclosure group deliberately absent from the seed, used to force the {@code DEFAULT} fallback. */
    private static final String ABSENT_GROUP = "NOGROUP";

    /** S3 key prefix under which interest transactions are staged ({@code systran/<yyyyMMdd>/<tranId>.dat}). */
    private static final String SYSTRAN_KEY_PREFIX = "systran/";

    /** Interest-formula divisor — COBOL literal {@code 1200} held as an exact-precision {@link BigDecimal}. */
    private static final BigDecimal INTEREST_DIVISOR = new BigDecimal("1200");

    /** Scale of {@code WS-MONTHLY-INT}/{@code ACCT-CURR-BAL} ({@code PIC S9(n)V99}) — two fractional digits. */
    private static final int MONETARY_SCALE = 2;

    // -------------------------------------------------------------------------
    // Production beans under test — autowired BY NAME from the full context.
    // -------------------------------------------------------------------------

    /** The Stage-2 job under test, resolved by bean name {@code interestCalculationJob}. */
    @Autowired
    private Job interestCalculationJob;

    /** TCATBAL driver repository — arranged (balances) and read to compute expected interest. */
    @Autowired
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /** Account master — the interest roll-up target whose final balances/version are asserted. */
    @Autowired
    private AccountRepository accountRepository;

    /** Disclosure-group rates — read to resolve the per-row interest rate (with {@code DEFAULT} fallback). */
    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    /** Live transaction table — asserted to remain unchanged (interest is staged to S3, not persisted here). */
    @Autowired
    private TransactionRepository transactionRepository;

    // -------------------------------------------------------------------------
    // Parity helpers (compute expected values exactly as the production code does).
    // -------------------------------------------------------------------------

    /**
     * Resolves the disclosure-group interest rate exactly as {@code CBACT04C}
     * {@code 1200-GET-INTEREST-RATE} (and {@code InterestCalculationProcessor}) do: a primary keyed read
     * on the account's own group, falling back to the literal {@code DEFAULT} group when the primary read
     * finds nothing (COBOL {@code DISCGRP-STATUS '23'} &rarr; {@code 1200-A-GET-DEFAULT-INT-RATE}).
     *
     * @param groupId  the account's group id ({@code ACCT-GROUP-ID})
     * @param typeCode the transaction type code ({@code TRANCAT-TYPE-CD})
     * @param catCode  the transaction category code ({@code TRANCAT-CD})
     * @return the resolved {@code DIS-INT-RATE} (scale 2)
     * @throws IllegalStateException if neither the primary nor the {@code DEFAULT} rate exists (a fixture
     *                               precondition violation, not an expected production path)
     */
    private BigDecimal resolveRate(final String groupId, final String typeCode, final Integer catCode) {
        final Optional<DisclosureGroup> primary =
                disclosureGroupRepository.findById(new DisclosureGroupId(groupId, typeCode, catCode));
        if (primary.isPresent()) {
            return primary.get().getDisIntRate();
        }
        return disclosureGroupRepository.findById(new DisclosureGroupId(DEFAULT_GROUP_ID, typeCode, catCode))
                .map(DisclosureGroup::getDisIntRate)
                .orElseThrow(() -> new IllegalStateException(
                        "Fixture precondition: neither group '" + groupId + "' nor DEFAULT has a rate for "
                                + "type " + typeCode + " / cat " + catCode));
    }

    /**
     * Computes the expected monthly interest with byte-for-byte formula fidelity to
     * {@code CBACT04C} {@code 1300-COMPUTE-INTEREST} and {@code InterestCalculationProcessor}:
     * {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} — multiply first, then divide by the literal 1200 at
     * scale&nbsp;2 with {@link RoundingMode#HALF_EVEN}. No algebraic rearrangement; no {@code float}/{@code double}.
     *
     * @param balance the transaction-category balance ({@code TRAN-CAT-BAL})
     * @param rate    the resolved disclosure-group rate ({@code DIS-INT-RATE})
     * @return the expected monthly interest ({@code WS-MONTHLY-INT}), scale 2
     */
    private static BigDecimal expectedMonthlyInterest(final BigDecimal balance, final BigDecimal rate) {
        return balance.multiply(rate).divide(INTEREST_DIVISOR, MONETARY_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Counts the seeded {@code TCATBAL} rows whose <em>resolved</em> rate is non-zero — i.e. the number of
     * {@code (account, category)} pairs for which {@code CBACT04C} writes an interest transaction
     * ({@code IF DIS-INT-RATE NOT = 0}). Computed from the current repository state so it tracks any
     * arranged group reassignment. Accounts are pre-loaded into a map to avoid per-row account reads.
     *
     * @return the number of category-balance rows that will produce an interest transaction
     */
    private long countNonZeroRateRows() {
        final Map<Long, String> groupByAccount = new HashMap<>();
        for (final Account account : accountRepository.findAll()) {
            groupByAccount.put(account.getAcctId(), account.getAcctGroupId());
        }
        long nonZero = 0L;
        for (final TransactionCategoryBalance row : transactionCategoryBalanceRepository.findAll()) {
            final TransactionCategoryBalanceId key = row.getId();
            final BigDecimal rate = resolveRate(groupByAccount.get(key.getAcctId()),
                    key.getTypeCode(), key.getCatCode());
            if (rate.compareTo(BigDecimal.ZERO) != 0) {
                nonZero++;
            }
        }
        return nonZero;
    }

    /**
     * Launches {@code interestCalculationJob} with a unique {@code JobInstance} plus the late-bound
     * {@code parmDate} parameter ({@code INTCALC.jcl} {@code PARM='2022071800'}).
     *
     * @return the terminal {@link JobExecution}
     * @throws Exception if the launch fails
     */
    private JobExecution launchInterestJob() throws Exception {
        return launchJob(interestCalculationJob, uniqueParams(builder -> builder.addString("parmDate", PARM_DATE)));
    }

    /**
     * Loads a seeded account, failing fast if the fixture row is missing.
     *
     * @param acctId the account id
     * @return the managed-then-detached {@link Account}
     */
    private Account account(final long acctId) {
        return accountRepository.findById(acctId)
                .orElseThrow(() -> new IllegalStateException("Seed precondition: missing account " + acctId));
    }

    /**
     * Returns the current {@code ACCT-CURR-BAL} of an account (for before/after delta snapshots).
     *
     * @param acctId the account id
     * @return the current balance
     */
    private BigDecimal currentBalance(final long acctId) {
        return account(acctId).getAcctCurrBal();
    }

    /**
     * Restores an account's balance to a snapshot value (shared-state hygiene for sibling ITs).
     *
     * @param acctId          the account id
     * @param originalBalance the balance to restore
     */
    private void restoreBalance(final long acctId, final BigDecimal originalBalance) {
        final Account account = account(acctId);
        account.setAcctCurrBal(originalBalance);
        accountRepository.saveAndFlush(account);
    }

    /**
     * Reassigns an account's disclosure group (used to force the {@code DEFAULT} fallback or the
     * zero-rate path). FK-safe: {@code account.group_id} has no foreign key.
     *
     * @param acctId  the account id
     * @param groupId the group id to set
     */
    private void setAccountGroup(final long acctId, final String groupId) {
        final Account account = account(acctId);
        account.setAcctGroupId(groupId);
        accountRepository.saveAndFlush(account);
    }

    /**
     * Arranges a {@code TCATBAL} row's balance, failing fast if the seeded row is missing.
     *
     * @param acctId   the account id ({@code TRANCAT-ACCT-ID})
     * @param typeCode the type code ({@code TRANCAT-TYPE-CD})
     * @param catCode  the category code ({@code TRANCAT-CD})
     * @param balance  the balance to set, as an exact decimal string (no {@code float}/{@code double})
     */
    private void setTcatbalBalance(final long acctId, final String typeCode, final int catCode,
            final String balance) {
        final TransactionCategoryBalance row = transactionCategoryBalanceRepository
                .findById(new TransactionCategoryBalanceId(acctId, typeCode, catCode))
                .orElseThrow(() -> new IllegalStateException(
                        "Seed precondition: missing TCATBAL row " + acctId + "/" + typeCode + "/" + catCode));
        row.setTranCatBal(new BigDecimal(balance));
        transactionCategoryBalanceRepository.saveAndFlush(row);
    }

    // =========================================================================
    // Phase 1 — Interest formula decimal fidelity (the headline assertion).
    // =========================================================================

    /**
     * Proves the interest formula is decimal-faithful: {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
     * computed at scale&nbsp;2 with {@link RoundingMode#HALF_EVEN} banker's rounding — distinct from
     * {@code HALF_UP} — exactly as {@code CBACT04C} {@code 1300-COMPUTE-INTEREST}.
     */
    @Test
    @DisplayName("Interest formula is decimal-faithful with HALF_EVEN banker's rounding (CBACT04C 1300-COMPUTE-INTEREST)")
    void interestFormula_isDecimalFaithful_withBankersRounding() throws Exception {
        // GIVEN — CBACT04C 1300-COMPUTE-INTEREST: WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE)/1200,
        // WS-MONTHLY-INT PIC S9(09)V99 (scale 2), banker's rounding (AAP §0.7.3/§0.7.6). The seeded
        // A000000000/01/1 rate is 15.00 (monthly factor = rate/1200 = 0.0125). We arrange three TCATBAL
        // balances that target the 3rd decimal so HALF_EVEN is provably distinct from HALF_UP:
        //   acct 1:   2.00 -> 0.025  -> HALF_EVEN 0.02 (HALF_UP 0.03)   [round-half-to-even down]
        //   acct 2:  10.00 -> 0.125  -> HALF_EVEN 0.12 (HALF_UP 0.13)   [round-half-to-even down]
        //   acct 3: 160.00 -> 2.000  -> exact 2.00 (no rounding)
        final BigDecimal rate = resolveRate(GROUP_A, TYPE_01, CAT_1);
        assertThat(rate).as("seed precondition: A000000000/01/1 rate is non-zero")
                .isEqualByComparingTo(new BigDecimal("15.00"));

        final BigDecimal bal1 = new BigDecimal("2.00");
        final BigDecimal bal2 = new BigDecimal("10.00");
        final BigDecimal bal3 = new BigDecimal("160.00");

        final BigDecimal before1 = currentBalance(1L);
        final BigDecimal before2 = currentBalance(2L);
        final BigDecimal before3 = currentBalance(3L);
        try {
            setTcatbalBalance(1L, TYPE_01, CAT_1, "2.00");
            setTcatbalBalance(2L, TYPE_01, CAT_1, "10.00");
            setTcatbalBalance(3L, TYPE_01, CAT_1, "160.00");

            // Expected values use the SAME formula + HALF_EVEN + scale 2 as production (no rearrangement).
            final BigDecimal expected1 = expectedMonthlyInterest(bal1, rate); // 0.02
            final BigDecimal expected2 = expectedMonthlyInterest(bal2, rate); // 0.12
            final BigDecimal expected3 = expectedMonthlyInterest(bal3, rate); // 2.00
            final BigDecimal halfUp1 = bal1.multiply(rate).divide(INTEREST_DIVISOR, MONETARY_SCALE, RoundingMode.HALF_UP); // 0.03
            final BigDecimal halfUp2 = bal2.multiply(rate).divide(INTEREST_DIVISOR, MONETARY_SCALE, RoundingMode.HALF_UP); // 0.13

            // Sanity: confirm these cases genuinely discriminate HALF_EVEN from HALF_UP.
            assertThat(expected1).as("acct1 discriminates HALF_EVEN vs HALF_UP").isNotEqualByComparingTo(halfUp1);
            assertThat(expected2).as("acct2 discriminates HALF_EVEN vs HALF_UP").isNotEqualByComparingTo(halfUp2);

            // WHEN — launch interestCalculationJob (INTCALC.jcl //STEP15 EXEC PGM=CBACT04C).
            final JobExecution execution = launchInterestJob();

            // THEN — the job completes and each account balance advanced by the HALF_EVEN interest.
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            assertThat(currentBalance(1L).subtract(before1))
                    .as("acct1 interest is HALF_EVEN 0.02, not HALF_UP 0.03")
                    .isEqualByComparingTo(expected1)
                    .isNotEqualByComparingTo(halfUp1);
            assertThat(currentBalance(2L).subtract(before2))
                    .as("acct2 interest is HALF_EVEN 0.12, not HALF_UP 0.13")
                    .isEqualByComparingTo(expected2)
                    .isNotEqualByComparingTo(halfUp2);
            assertThat(currentBalance(3L).subtract(before3))
                    .as("acct3 interest is exact 2.00 (no rounding)")
                    .isEqualByComparingTo(expected3);

            // CBACT04C 1050-UPDATE-ACCOUNT also zeroes the cycle credit/debit (MOVE 0 TO ...).
            final Account rolledUp = account(1L);
            assertThat(rolledUp.getAcctCurrCycCredit()).as("cycle credit reset to 0")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(rolledUp.getAcctCurrCycDebit()).as("cycle debit reset to 0")
                    .isEqualByComparingTo(BigDecimal.ZERO);
        } finally {
            // Restore the shared, non-transactional state for sibling integration tests.
            setTcatbalBalance(1L, TYPE_01, CAT_1, "0.00");
            setTcatbalBalance(2L, TYPE_01, CAT_1, "0.00");
            setTcatbalBalance(3L, TYPE_01, CAT_1, "0.00");
            restoreBalance(1L, before1);
            restoreBalance(2L, before2);
            restoreBalance(3L, before3);
        }
    }

    // =========================================================================
    // Phase 2 — DEFAULT-group fallback.
    // =========================================================================

    /**
     * Proves that an account whose own disclosure group is unrated falls back to the {@code DEFAULT}
     * group rate — {@code CBACT04C} {@code 1200-A-GET-DEFAULT-INT-RATE}.
     */
    @Test
    @DisplayName("DEFAULT-group fallback applies the DEFAULT rate for an unrated group (CBACT04C 1200-A-GET-DEFAULT-INT-RATE)")
    void defaultGroupFallback_appliesDefaultRate() throws Exception {
        // GIVEN — CBACT04C 1200-GET-INTEREST-RATE: when the account's own group read returns nothing
        // (DISCGRP-STATUS '23'), it MOVEs 'DEFAULT' to FD-DIS-ACCT-GROUP-ID and re-reads
        // (1200-A-GET-DEFAULT-INT-RATE). All seeded accounts use the rated group A000000000, so we move
        // acct 10 onto an absent group ('NOGROUP') to force the fallback. This is FK-safe: account.group_id
        // has no foreign key (AAP §0.4.1 note), so referential integrity is preserved.
        final long acctId = 10L;
        final String originalGroup = account(acctId).getAcctGroupId();
        final BigDecimal before = currentBalance(acctId);
        try {
            setAccountGroup(acctId, ABSENT_GROUP);
            setTcatbalBalance(acctId, TYPE_01, CAT_1, "120.00");

            // Prove the fallback is actually reached: the primary lookup is empty; DEFAULT is present.
            assertThat(disclosureGroupRepository.findById(new DisclosureGroupId(ABSENT_GROUP, TYPE_01, CAT_1)))
                    .as("absent group has no specific rate (forces DEFAULT fallback)").isEmpty();
            assertThat(disclosureGroupRepository.findById(new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_01, CAT_1)))
                    .as("DEFAULT group supplies the fallback rate").isPresent();

            final BigDecimal defaultRate = resolveRate(ABSENT_GROUP, TYPE_01, CAT_1); // resolves via DEFAULT
            final BigDecimal expected = expectedMonthlyInterest(new BigDecimal("120.00"), defaultRate);

            // WHEN
            final JobExecution execution = launchInterestJob();

            // THEN — acct 10 received interest computed with the DEFAULT-group rate.
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(currentBalance(acctId).subtract(before))
                    .as("acct10 interest equals the DEFAULT-rate computation (parity with 1200-A-GET-DEFAULT-INT-RATE)")
                    .isEqualByComparingTo(expected);
        } finally {
            setTcatbalBalance(acctId, TYPE_01, CAT_1, "0.00");
            setAccountGroup(acctId, originalGroup);
            restoreBalance(acctId, before);
        }
    }

    // =========================================================================
    // Phase 3 — Zero rate => no interest transaction.
    // =========================================================================

    /**
     * Proves a zero disclosure rate produces no interest transaction and no balance change — the COBOL
     * {@code IF DIS-INT-RATE NOT = 0} guard in {@code CBACT04C} {@code 1300-COMPUTE-INTEREST}.
     */
    @Test
    @DisplayName("Zero rate produces NO interest transaction and no balance change (CBACT04C 'IF DIS-INT-RATE NOT = 0')")
    void zeroRate_producesNoInterestTransaction() throws Exception {
        // GIVEN — CBACT04C computes and writes interest only when DIS-INT-RATE NOT = 0. We move acct 11
        // onto the seeded ZEROAPR group (every ZEROAPR rate is 0.00) so its TCATBAL row resolves a zero
        // rate even with a large balance.
        final long acctId = 11L;
        final String originalGroup = account(acctId).getAcctGroupId();
        final BigDecimal before = currentBalance(acctId);
        try {
            setAccountGroup(acctId, ZERO_APR_GROUP);
            setTcatbalBalance(acctId, TYPE_01, CAT_1, "500.00");

            assertThat(resolveRate(ZERO_APR_GROUP, TYPE_01, CAT_1))
                    .as("ZEROAPR/01/1 rate is zero").isEqualByComparingTo(BigDecimal.ZERO);

            // Expected number of SYSTRAN objects = rows whose RESOLVED rate is non-zero. The zero-rate
            // acct 11 row must NOT contribute one (computed AFTER arrangement to reflect current state).
            final long expectedSystranObjects = countNonZeroRateRows();
            emptyBucket(BATCH_OUTPUT_BUCKET);

            // WHEN
            final JobExecution execution = launchInterestJob();

            // THEN — no interest applied to the zero-rate account; balance unchanged by interest.
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(currentBalance(acctId).subtract(before))
                    .as("zero-rate account balance unchanged (no 1300-COMPUTE-INTEREST)")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            // And the zero-rate row staged NO SYSTRAN object: the total equals the non-zero-rate row count.
            assertThat(countObjects(BATCH_OUTPUT_BUCKET, SYSTRAN_KEY_PREFIX))
                    .as("zero-rate row stages NO interest transaction to S3")
                    .isEqualTo(expectedSystranObjects);
        } finally {
            setTcatbalBalance(acctId, TYPE_01, CAT_1, "0.00");
            setAccountGroup(acctId, originalGroup);
            restoreBalance(acctId, before);
        }
    }

    // =========================================================================
    // Phase 4 — Interest transactions to S3 (SYSTRAN) + account roll-up + EOF flush.
    // =========================================================================

    /**
     * Proves interest transactions are staged to S3 ({@code carddemo-batch-output} under {@code systran/};
     * {@code CBACT04C} {@code 1300-B-WRITE-TX}) and that the <strong>final</strong> account in sort order is
     * rolled up — which can only happen via the end-of-file flush ({@code 1050-UPDATE-ACCOUNT} at EOF,
     * the writer's {@code afterStep}). Also confirms interest is staged to S3 only, never persisted to the
     * live transaction table.
     */
    @Test
    @DisplayName("Interest transactions staged to S3 SYSTRAN; final account rolled up at EOF (CBACT04C 1300-B-WRITE-TX + 1050 end-of-file)")
    void interestTransactionsStagedToS3_andFinalAccountRolledUpAtEof() throws Exception {
        // GIVEN — the writer stages each interest transaction to carddemo-batch-output under 'systran/'
        // (the SYSTRAN(+1) GDG -> S3 substitution, decision D-003) and performs the account roll-up on
        // each account-id change AND once more at end-of-file (afterStep). acct 50 is the MAXIMUM acctId
        // among the seeded TCATBAL rows and therefore the LAST in id.acctId ASC order, so its roll-up can
        // only fire via the EOF flush — exercising the path the on-change flush never covers.
        final long firstAcct = 1L;
        final long lastAcct = 50L;
        final BigDecimal beforeFirst = currentBalance(firstAcct);
        final BigDecimal beforeLast = currentBalance(lastAcct);
        final long txRowsBefore = transactionRepository.count();
        final BigDecimal rate = resolveRate(GROUP_A, TYPE_01, CAT_1);
        try {
            setTcatbalBalance(firstAcct, TYPE_01, CAT_1, "160.00"); // -> 2.00
            setTcatbalBalance(lastAcct, TYPE_01, CAT_1, "80.00");   // -> 1.00
            final BigDecimal expectedFirst = expectedMonthlyInterest(new BigDecimal("160.00"), rate);
            final BigDecimal expectedLast = expectedMonthlyInterest(new BigDecimal("80.00"), rate);

            final long expectedSystranObjects = countNonZeroRateRows();
            emptyBucket(BATCH_OUTPUT_BUCKET);

            // WHEN
            final JobExecution execution = launchInterestJob();

            // THEN
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            // One SYSTRAN S3 object per non-zero-rate (account,category) pair (1300-B-WRITE-TX WRITE).
            assertThat(countObjects(BATCH_OUTPUT_BUCKET, SYSTRAN_KEY_PREFIX))
                    .as("one SYSTRAN S3 object per non-zero-rate row")
                    .isEqualTo(expectedSystranObjects);
            // The FINAL account in sort order was rolled up => proves the afterStep/EOF flush path.
            assertThat(currentBalance(lastAcct).subtract(beforeLast))
                    .as("final account (acctId 50) rolled up at EOF (afterStep flush)")
                    .isEqualByComparingTo(expectedLast);
            // The first/early account was rolled up via the on-account-change flush path.
            assertThat(currentBalance(firstAcct).subtract(beforeFirst))
                    .as("first account rolled up (on-change flush)")
                    .isEqualByComparingTo(expectedFirst);
            // SYSTRAN staging is S3-only: the interest job adds NO rows to the live transaction table.
            assertThat(transactionRepository.count())
                    .as("interest transactions are staged to S3, not persisted to the transaction table")
                    .isEqualTo(txRowsBefore);
        } finally {
            setTcatbalBalance(firstAcct, TYPE_01, CAT_1, "0.00");
            setTcatbalBalance(lastAcct, TYPE_01, CAT_1, "0.00");
            restoreBalance(firstAcct, beforeFirst);
            restoreBalance(lastAcct, beforeLast);
        }
    }

    // =========================================================================
    // Phase 5 — JPA @Version optimistic locking on Account.
    // =========================================================================

    /**
     * Proves {@code Account} carries a JPA {@code @Version} optimistic lock: a stale update throws
     * {@link ObjectOptimisticLockingFailureException}. This is the JPA migration of the COACTUPC
     * read-update before/after image comparison (AAP §0.3.3 L510 / §0.7.5) and is exercised by the
     * interest roll-up's {@code accountRepository.save(...)}.
     *
     * <p><strong>Scope boundary:</strong> {@code COCRDUPC} (Card) optimistic locking is an ONLINE concern
     * validated in {@code e2e/OnlineTransactionE2ETest}. Here we validate {@code Account} {@code @Version}
     * specifically because the batch interest roll-up is the path that mutates {@code Account}; Card
     * concurrency is intentionally NOT exercised in this batch IT.</p>
     */
    @Test
    @DisplayName("Account @Version rejects a stale update with ObjectOptimisticLockingFailureException (AAP §0.3.3 L510 / §0.7.5)")
    void accountVersion_isOptimisticallyLocked_onStaleUpdate() {
        // GIVEN — two independent detached views of the same account, both at version v. The interest
        // roll-up (1050-UPDATE-ACCOUNT) calls accountRepository.save(account); Hibernate guards that write
        // with the @Version column. This focused, job-independent test loads the account twice (two reads,
        // two transactions => two detached instances at version v).
        final long acctId = 25L;
        final BigDecimal originalBalance = currentBalance(acctId);
        try {
            final Account first = account(acctId);  // version v
            final Account stale = account(acctId);  // independent view, also version v

            // WHEN — the first update succeeds and bumps the @Version (v -> v+1).
            first.setAcctCurrBal(first.getAcctCurrBal().add(BigDecimal.ONE));
            accountRepository.saveAndFlush(first);

            // THEN — saving the stale view (still version v) must fail the optimistic-lock check.
            stale.setAcctCurrBal(stale.getAcctCurrBal().add(new BigDecimal("2.00")));
            assertThrows(ObjectOptimisticLockingFailureException.class,
                    () -> accountRepository.saveAndFlush(stale));
        } finally {
            restoreBalance(acctId, originalBalance);
        }
    }

    // =========================================================================
    // Test-only configuration.
    // =========================================================================

    /**
     * Supplies the {@link CorsConfigurationSource} bean that production {@code SecurityConfig}'s
     * {@code .cors(Customizer.withDefaults())} resolves.
     *
     * <p><strong>Why this is needed (technology-transition note).</strong> The production filter chain
     * enables Security-aware CORS via {@code .cors(Customizer.withDefaults())}, which at runtime (a
     * servlet web context backed by {@code spring-boot-starter-web}) delegates to Spring MVC's
     * {@code HandlerMappingIntrospector}. The batch IT harness ({@link AbstractBatchJobIT}) deliberately
     * boots with {@code @SpringBootTest(webEnvironment = NONE)} — no embedded server, no web MVC — so that
     * delegate does not exist and the security filter chain cannot instantiate. This {@code @TestConfiguration}
     * publishes an explicit (empty) {@link UrlBasedCorsConfigurationSource}, which is behaviorally equivalent
     * to production's effective default (no CORS mappings registered) and merely lets the context refresh in
     * a non-web batch test. It is {@code @TestConfiguration} (excluded from the application component scan)
     * and {@code @Import}ed only by this class, so it touches neither production code nor the shared base
     * class and never leaks into any other test context.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityCorsTestConfig {

        /**
         * @return an empty CORS source (no mappings) satisfying the security filter chain's CORS DSL
         */
        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            return new UrlBasedCorsConfigurationSource();
        }
    }
}
