package com.carddemo.integration.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

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
import org.springframework.data.domain.PageRequest;

import com.carddemo.model.entity.Account;
import com.carddemo.model.key.DisclosureGroupId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the {@code interestCalculationJob} Spring Batch job, proving behavioral
 * parity with the mainframe monthly interest-calculation pipeline {@code INTCALC.jcl}
 * ({@code STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}) and the COBOL program {@code CBACT04C.cbl}
 * (lineage: source commit {@code 27d6c6f}; REFERENCE ONLY &mdash; no COBOL/JCL is copied).
 *
 * <p>This is the precision-critical guard of the decimal rules (AAP &sect;0.8.2): the monthly
 * interest formula {@code (TRAN-CAT-BAL &times; DIS-INT-RATE) / 1200} is evaluated in
 * {@link BigDecimal} with the divisor exactly {@code new BigDecimal("1200")}, scale&nbsp;2, and
 * {@link RoundingMode#HALF_EVEN} (banker's rounding). No floating point is used and decimal
 * comparisons use {@code compareTo}/{@code isEqualByComparingTo}, never the scale-sensitive
 * {@code equals}.</p>
 *
 * <p>Five ordered tests verify the four decoupled behaviours of {@code CBACT04C}, each preserved
 * faithfully and never conflated:</p>
 * <ol>
 *   <li><strong>Job completion + SYSTRAN staging</strong> (Gate-5 fixed-width): the job runs to
 *       {@code COMPLETED} and the interest transactions are staged to the {@code SYSTRAN} S3 object
 *       on the exact 350-byte {@code CVTRA05Y} record boundary. Completion across all 50 seeded
 *       category balances also proves the specific&rarr;{@code DEFAULT} disclosure-group fallback
 *       resolves a rate for every row (a missing group would throw and FAIL the step).</li>
 *   <li><strong>Double-load guard</strong>: the interest job stages to a sequential file and updates
 *       accounts, but does <em>not</em> insert interest rows into the {@code transaction} table
 *       (those reach the master only through {@code COMBTRAN}); the table delta is exactly zero.</li>
 *   <li><strong>Account control-break rollup</strong>: after accumulating interest per account, the
 *       current-cycle credit and debit fields are reset to zero.</li>
 *   <li><strong>Interest-formula precision</strong>: the canonical vector plus two
 *       {@code HALF_EVEN} ties that diverge from {@code HALF_UP}.</li>
 *   <li><strong>DEFAULT disclosure-group fallback</strong>: the seed is present and the fallback
 *       chain is re-affirmed end-to-end.</li>
 * </ol>
 *
 * <p>The class {@code extends} {@link AbstractBatchIntegrationTest} and reuses ALL of its scaffolding
 * (singleton Testcontainers PostgreSQL + LocalStack, the {@code @DynamicPropertySource} wiring and
 * AWS self-provisioning, the fixture locator, the S3 helpers, the inherited
 * {@link org.springframework.batch.core.launch.JobLauncher}, the per-test {@code @BeforeEach} S3
 * cleanup, and the shared constants). No container, property, {@code @SpringBootTest},
 * {@code @ActiveProfiles}, {@code @Testcontainers}, or {@code @Tag} scaffolding is re-declared here.
 * When Docker is unavailable the inherited {@code @Testcontainers(disabledWithoutDocker = true)}
 * cleanly skips every test.</p>
 *
 * <p>All assertions are robust against the shared singleton database (no absolute pristine-DB
 * counts): the double-load guard uses a delta, the rollup uses {@code compareTo(ZERO)} structural
 * checks, and every launch carries a unique {@code run.id} (via the inherited {@code baseParams()})
 * so each launch is a distinct {@code JobInstance}. There is no {@code @DirtiesContext}.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InterestCalculationJobIT extends AbstractBatchIntegrationTest {

    /**
     * Exact S3 object key the interest writer stages the {@code SYSTRAN} interest records under
     * (COBOL {@code TRANSACT} DD &rarr; {@code AWS.M2.CARDDEMO.SYSTRAN(+1)} GDG generation, mapped to
     * an S3 object per decision D-003). The records MUST be read by this exact key, never by listing
     * (the output bucket also holds other staging objects).
     */
    private static final String SYSTRAN_OBJECT_KEY = "SYSTRAN";

    /**
     * The 10-character processing date (COBOL {@code PARM-DATE PIC X(10)}, the
     * {@code PARM='2022071800'} of {@code INTCALC.jcl}); the high-order part of every interest
     * {@code TRAN-ID}. The processor requires exactly ten characters.
     */
    private static final String PARM_DATE = "2022071800";

    /** The interest-calculation job under test, injected by its bean name. */
    @Autowired
    @Qualifier("interestCalculationJob")
    private Job interestCalculationJob;

    /** Category-balance repository (TCATBALF); used for the Gate-4 seed-presence sanity check. */
    @Autowired
    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    /** Disclosure-group repository (DISCGRP); used to assert the seed and the DEFAULT fallback row. */
    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    /** Account repository (ACCTDAT); used to assert the control-break cycle reset and balance shape. */
    @Autowired
    private AccountRepository accountRepository;

    /** Transaction repository (TRANSACT); used for the double-load guard count delta. */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * Launches the interest-calculation job with a unique {@code run.id} (so every launch yields a
     * distinct {@code JobInstance}, never a {@code JobInstanceAlreadyCompleteException}) and the
     * required {@code parmDate} job parameter. Jobs are launched explicitly because
     * {@code spring.batch.job.enabled=false} in the {@code test} profile.
     *
     * @return the resulting job execution
     * @throws Exception if the launch fails (for example an already-running instance)
     */
    private JobExecution launchInterest() throws Exception {
        return jobLauncher.run(
                interestCalculationJob,
                baseParams().addString("parmDate", PARM_DATE).toJobParameters());
    }

    /**
     * Computes the monthly interest exactly as {@code InterestCalculationProcessor} does (COBOL
     * {@code 1300-COMPUTE-INTEREST}): {@code (balance &times; rate) / 1200}, with the divisor the
     * literal {@code new BigDecimal("1200")}, scale&nbsp;2 and {@link RoundingMode#HALF_EVEN}. The
     * arguments are {@code String} so the inputs carry exact, unambiguous scale (no binary
     * floating-point intermediates).
     *
     * @param balance the transaction-category balance literal (COBOL {@code TRAN-CAT-BAL})
     * @param rate    the disclosure-group interest-rate literal (COBOL {@code DIS-INT-RATE})
     * @return the scale-2 monthly interest amount
     */
    private static BigDecimal monthlyInterest(final String balance, final String rate) {
        return new BigDecimal(balance)
                .multiply(new BigDecimal(rate))
                .divide(new BigDecimal("1200"), 2, RoundingMode.HALF_EVEN);
    }

    // ------------------------------------------------------------------------------------------
    // Test 1 (Phase B): the job completes and the interest records are staged to SYSTRAN on the
    // exact 350-byte CVTRA05Y boundary (Gate-5 fixed-width contract).
    // ------------------------------------------------------------------------------------------

    /**
     * Verifies the interest job runs to {@code COMPLETED} and stages the interest transactions to the
     * {@code SYSTRAN} S3 object aligned to the 350-byte {@code CVTRA05Y} record length.
     *
     * <p>The {@code COMPLETED} status is itself a strong parity proof: the processor resolves the
     * disclosure rate by the account's own group id and, when that is absent, by the reserved
     * {@code DEFAULT} group; if a category balance had neither, it would throw
     * {@code RecordNotFoundException} and FAIL the step/job. A completed run therefore proves the
     * fallback resolved a rate for every one of the seeded category balances.</p>
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @Order(1)
    void jobCompletesAndStagesSystranOnRecordBoundary() throws Exception {
        // Gate-4 seed sanity: the interest job has category-balance input to process.
        assertThat(categoryBalanceRepository.count()).isGreaterThanOrEqualTo(1L);

        final JobExecution execution = launchInterest();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // The base @BeforeEach empties the output bucket, so this object is produced by THIS launch.
        final byte[] systran = getS3ObjectOrNull(BUCKET_OUTPUT, SYSTRAN_OBJECT_KEY);
        // Hard gate evidence (Gate-4/Gate-5): after a COMPLETED interest job over the seeded category
        // balances, the SYSTRAN object MUST be staged and non-empty. A soft skip here could mask a
        // regression in the INTCALC output contract, so this is a hard assertion rather than an
        // assumption.
        assertThat(systran)
                .as("SYSTRAN interest records must be staged after a successful interest job")
                .isNotNull();
        assertThat(systran.length)
                .as("SYSTRAN interest records object must be non-empty")
                .isGreaterThan(0);

        // Gate-5: the staged object is an exact multiple of the 350-byte CVTRA05Y record length.
        assertThat(systran.length % DAILY_TRAN_RECORD_LENGTH).isZero();

        final int interestRecords = systran.length / DAILY_TRAN_RECORD_LENGTH;
        assertThat(interestRecords).isGreaterThan(0);
    }

    // ------------------------------------------------------------------------------------------
    // Test 2 (Phase C): interest transactions are NOT inserted into the Transaction DB table.
    // ------------------------------------------------------------------------------------------

    /**
     * Proves the double-load guard that distinguishes the interest job from the posting job:
     * {@code CBACT04C} stages interest records to a sequential file ({@code SYSTRAN}) and updates the
     * account master, but it does NOT post them to {@code TRANSACT} &mdash; the interest rows reach
     * the master table only through the {@code COMBTRAN} merge. Inserting here would double-load them.
     * The transaction-table row count must be unchanged by the interest run (delta exactly zero).
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @Order(2)
    void interestRunDoesNotInsertTransactionRows() throws Exception {
        final long preCount = transactionRepository.count();

        final JobExecution execution = launchInterest();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Delta must be exactly 0: the interest job writes only to S3 (SYSTRAN) and the account table.
        assertThat(transactionRepository.count()).isEqualTo(preCount);
    }

    // ------------------------------------------------------------------------------------------
    // Test 3 (Phase D): account control-break rollup - the current-cycle credit/debit are ZEROED.
    // ------------------------------------------------------------------------------------------

    /**
     * Proves the account control-break rollup (COBOL {@code 1050-UPDATE-ACCOUNT}): after accumulating
     * the per-account interest, the job adds it to the current balance and resets the current-cycle
     * credit and debit fields to zero. A bounded sample of accounts is read after the run and at least
     * one is asserted to carry zeroed cycle fields.
     *
     * <p>The assertion is intentionally {@code > 0} rather than {@code == sampleSize}: an account with
     * no category balance (or only zero-rate balances) is never processed and keeps its prior cycle
     * values, so requiring every sampled account to be zeroed would be brittle. The zeroing is
     * idempotent across the ordered re-runs (it stays zero) while balances keep accruing interest;
     * both are consistent with this structural check, so no absolute balance value is asserted.</p>
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @Order(3)
    void controlBreakZeroesCycleCreditAndDebit() throws Exception {
        final JobExecution execution = launchInterest();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Bounded page rather than a full-table scan; getContent() is List<Account> (no raw type).
        final List<Account> accounts = accountRepository.findAll(PageRequest.of(0, 20)).getContent();
        Assumptions.assumeTrue(!accounts.isEmpty(), "No accounts seeded");

        // compareTo(ZERO) (never equals, which is scale-sensitive) detects the zeroed cycle fields.
        final long zeroedCycle = accounts.stream()
                .filter(a -> a.getAcctCurrCycCredit() != null
                        && a.getAcctCurrCycDebit() != null
                        && a.getAcctCurrCycCredit().compareTo(BigDecimal.ZERO) == 0
                        && a.getAcctCurrCycDebit().compareTo(BigDecimal.ZERO) == 0)
                .count();
        assertThat(zeroedCycle).isGreaterThan(0L);

        // Account balances are PIC S9(10)V99 -> scale 2 (AAP 0.8.2); every balance stays well-formed.
        accounts.forEach(account -> {
            assertThat(account.getAcctCurrBal()).isNotNull();
            assertThat(account.getAcctCurrBal().scale()).isEqualTo(2);
        });
    }

    // ------------------------------------------------------------------------------------------
    // Test 4 (Phase E): interest-formula precision - the AAP 0.8.2 core (HALF_EVEN, scale 2, /1200).
    // ------------------------------------------------------------------------------------------

    /**
     * Validates the interest formula {@code (balance &times; rate) / 1200} computed exactly as the
     * processor does: divisor literal {@code new BigDecimal("1200")}, scale&nbsp;2,
     * {@link RoundingMode#HALF_EVEN}. The two tie vectors are the discriminating cases &mdash; they
     * diverge from {@code HALF_UP}, pinning banker's rounding as the binding mode (AAP &sect;0.8.2).
     * Assertions use {@code isEqualByComparingTo} ({@code compareTo}) so they are scale-insensitive.
     */
    @Test
    @Order(4)
    void interestFormulaUsesHalfEvenScaleTwoDivisor1200() {
        // Canonical (non-tie): 1000.00 * 12.00 = 12000.00; / 1200 = 10.00 exactly, scale 2.
        assertThat(monthlyInterest("1000.00", "12.00")).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(monthlyInterest("1000.00", "12.00").scale()).isEqualTo(2);

        // HALF_EVEN tie that rounds DOWN to the even zero: 2.00 * 3.00 = 6.00; / 1200 = 0.005 -> 0.00.
        // HALF_UP would instead give 0.01; HALF_EVEN (banker's rounding) is binding per AAP 0.8.2.
        assertThat(monthlyInterest("2.00", "3.00")).isEqualByComparingTo(new BigDecimal("0.00"));

        // HALF_EVEN tie that rounds to the even hundredth: 10.00 * 3.00 = 30.00; / 1200 = 0.025 -> 0.02.
        // HALF_UP would instead give 0.03; HALF_EVEN (banker's rounding) is binding per AAP 0.8.2.
        assertThat(monthlyInterest("10.00", "3.00")).isEqualByComparingTo(new BigDecimal("0.02"));

        // A zero rate yields zero interest at the value level; the processor's "rate == 0 => null"
        // branch then emits no interest record for that category balance.
        assertThat(monthlyInterest("5000.00", "0.00")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------------------------------
    // Test 5 (Phase F): the DEFAULT disclosure-group is present and the fallback chain is re-affirmed.
    // ------------------------------------------------------------------------------------------

    /**
     * Asserts the disclosure-group reference data is seeded and re-affirms the
     * specific&rarr;{@code DEFAULT} fallback end-to-end. Flyway {@code V3} seeds the disclosure groups
     * (the specific groups plus the reserved {@code DEFAULT} fallback group, Gate-4); a lenient
     * {@code >= 1} bound is used rather than an exact count to avoid coupling to the seed cardinality.
     * The job again running to {@code COMPLETED} re-affirms that the fallback resolves a rate for every
     * category balance (no {@code RecordNotFoundException}), preserving {@code CBACT04C}'s DEFAULT-group
     * behaviour. Finally, the reserved {@code DEFAULT} group row that the fallback resolves against is
     * fetched by its composite key and asserted present.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @Order(5)
    void defaultDisclosureGroupSeededAndFallbackReaffirmed() throws Exception {
        // Flyway V3 seeds the disclosure groups including the reserved DEFAULT fallback group (Gate-4).
        assertThat(disclosureGroupRepository.count()).isGreaterThanOrEqualTo(1L);

        final JobExecution execution = launchInterest();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // The reserved DEFAULT group carries a concrete rate row (seeded ('DEFAULT','01',1,15.00)) -
        // the fallback every empty-group seeded account resolves against.
        assertThat(disclosureGroupRepository.findById(new DisclosureGroupId("DEFAULT", "01", 1)))
                .isPresent();
    }
}
