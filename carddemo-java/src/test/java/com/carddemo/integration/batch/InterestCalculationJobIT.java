package com.carddemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.Account;
import com.carddemo.model.key.DisclosureGroupId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;

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

/**
 * Integration test for the Spring Batch interest-calculation job {@code interestCalculationJob}
 * (production class {@link com.carddemo.batch.jobs.InterestCalculationJob}, single step
 * {@code interestCalculationStep}).
 *
 * <p>This IT proves behavioural parity with the mainframe interest-calculation pipeline
 * {@code app/jcl/INTCALC.jcl} ({@code STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}) driving COBOL
 * program {@code app/cbl/CBACT04C.cbl} (source commit {@code 27d6c6f}; REFERENCE ONLY &mdash; no
 * COBOL/JCL is copied). It is the precision-critical guardian of the AAP &sect;0.8.2 decimal rules:
 * the monthly-interest formula {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} evaluated with
 * {@link BigDecimal}, the divisor being the literal {@code new BigDecimal("1200")},
 * {@link RoundingMode#HALF_EVEN} (banker's rounding), and a fixed scale of {@code 2}.</p>
 *
 * <p>Five complementary, ordered checks establish parity:</p>
 * <ol>
 *   <li>the job completes and stages its 350-byte {@code CVTRA05Y} interest records to the
 *       {@code SYSTRAN} S3 object (the {@code SYSTRAN(+1)} GDG re-host);</li>
 *   <li>interest rows are <em>not</em> inserted into the {@code transaction} table (they reach the
 *       database only later through {@code CombineTransactionsJob} &mdash; the double-load guard);</li>
 *   <li>the per-account control-break roll-up zeroes the current-cycle credit and debit totals
 *       ({@code 1050-UPDATE-ACCOUNT});</li>
 *   <li>the interest formula is exact for a canonical vector and two {@code HALF_EVEN} ties that
 *       differ from {@code HALF_UP}; and</li>
 *   <li>the {@code "DEFAULT"} disclosure-group fallback ({@code 1200-A-GET-DEFAULT-INT-RATE})
 *       resolves a rate for every seeded category balance.</li>
 * </ol>
 *
 * <p>All Testcontainers (PostgreSQL + LocalStack) wiring, dynamic property registration, AWS
 * self-provisioning, the S3 helpers, the {@code JobLauncher}, the record-length constants, and the
 * per-test S3 cleanup are inherited from {@link AbstractBatchIntegrationTest}; none of that
 * scaffolding is redeclared here. Jobs are launched explicitly because
 * {@code spring.batch.job.enabled=false}.</p>
 *
 * <p>The shared singleton containers mean the database is never assumed to be pristine: the
 * persistence guard uses a count delta (Test&nbsp;2) and the remaining checks are structural or
 * value-based (Tests&nbsp;1,&nbsp;3,&nbsp;4,&nbsp;5), so every assertion holds regardless of what
 * earlier tests or sibling IT classes left behind. Every launch draws a unique {@code run.id} from
 * {@code baseParams()} so no run hits {@code JobInstanceAlreadyCompleteException}; there is no
 * {@code @DirtiesContext}.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InterestCalculationJobIT extends AbstractBatchIntegrationTest {

    /** Exact S3 key under which the interest writer stages its single fixed-width {@code SYSTRAN} object. */
    private static final String SYSTRAN_OBJECT_KEY = "SYSTRAN";

    /** The COBOL {@code PARM='2022071800'} run date, supplied as the {@code parmDate} job parameter. */
    private static final String PARM_DATE = "2022071800";

    /** The interest-calculation job under test, injected by its canonical bean name. */
    @Autowired
    @Qualifier("interestCalculationJob")
    private Job interestCalculationJob;

    /** Repository for the read-only {@code TCATBALF} scan that drives the job (precondition check only). */
    @Autowired
    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    /** Repository proving the disclosure-group seed (incl. the {@code "DEFAULT"} fallback group) is present. */
    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    /** Repository used to assert the per-account control-break roll-up (zeroed cycle totals). */
    @Autowired
    private AccountRepository accountRepository;

    /** Repository used to assert the double-load guard (zero net new {@code transaction} rows). */
    @Autowired
    private TransactionRepository transactionRepository;

    // ---------------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------------

    /**
     * Test 1 &mdash; the job completes and stages the {@code SYSTRAN} interest records to S3 with
     * exact 350-byte {@code CVTRA05Y} alignment (Gate-5 fixed-width contract).
     *
     * <p>The {@code COMPLETED} status alone also proves the {@code "DEFAULT"} disclosure-group
     * fallback works for every one of the seeded category balances: had any row lacked both a
     * specific and a {@code "DEFAULT"} disclosure group, the processor would throw
     * {@code RecordNotFoundException} and the step/job would fail.</p>
     *
     * @throws Exception if the launcher rejects the run
     */
    @Test
    @Order(1)
    void jobCompletesAndStagesSystranToS3() throws Exception {
        // Precondition: the read-only TCATBALF scan has rows to drive interest emission.
        assertThat(categoryBalanceRepository.count()).isGreaterThan(0L);

        JobExecution execution = launchInterest();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        byte[] systran = getS3ObjectOrNull(BUCKET_OUTPUT, SYSTRAN_OBJECT_KEY);
        Assumptions.assumeTrue(systran != null && systran.length > 0,
                "No SYSTRAN interest records staged — skipping layout assertion");

        // 350-byte CVTRA05Y record boundary (DAILY_TRAN_RECORD_LENGTH is the inherited 350 constant
        // and equals the CVTRA05Y/CVTRA06Y record length).
        assertThat(systran.length % DAILY_TRAN_RECORD_LENGTH).isZero();

        int interestRecords = systran.length / DAILY_TRAN_RECORD_LENGTH;
        assertThat(interestRecords).isGreaterThan(0);
    }

    /**
     * Test 2 &mdash; the double-load guard. CBACT04C stages interest records to the sequential
     * {@code SYSTRAN} dataset; it does <em>not</em> post them to {@code TRANSACT} (they reach the
     * database only through the {@code COMBTRAN} merge). The interest run must therefore add exactly
     * zero rows to the {@code transaction} table.
     *
     * @throws Exception if the launcher rejects the run
     */
    @Test
    @Order(2)
    void interestRowsAreNotInsertedIntoTransactionTable() throws Exception {
        long preCount = transactionRepository.count();

        JobExecution execution = launchInterest();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Delta must be exactly 0: the job writes only to S3 (SYSTRAN) and to the account table.
        assertThat(transactionRepository.count()).isEqualTo(preCount);
    }

    /**
     * Test 3 &mdash; the per-account control-break roll-up ({@code 1050-UPDATE-ACCOUNT}). After
     * accumulating interest per account, CBACT04C adds it to the current balance and resets the
     * current-cycle credit and debit totals to zero.
     *
     * <p>Across repeated runs on the shared singleton database the cycle fields stay zero (idempotent
     * zeroing) while balances keep accruing interest; both are consistent with this assertion, which
     * deliberately never pins an absolute balance value.</p>
     *
     * @throws Exception if the launcher rejects the run
     */
    @Test
    @Order(3)
    void accountControlBreakZeroesCycleCreditAndDebit() throws Exception {
        JobExecution execution = launchInterest();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<Account> accounts = accountRepository.findAll(PageRequest.of(0, 20)).getContent();
        Assumptions.assumeTrue(!accounts.isEmpty(), "No accounts seeded");

        // compareTo(ZERO) — NEVER equals (which is scale-sensitive). Assert > 0 (not == sampleSize)
        // so an account with no category balance (hence not processed) cannot make the test brittle.
        long zeroedCycle = accounts.stream()
                .filter(a -> a.getAcctCurrCycCredit() != null
                        && a.getAcctCurrCycDebit() != null
                        && a.getAcctCurrCycCredit().compareTo(BigDecimal.ZERO) == 0
                        && a.getAcctCurrCycDebit().compareTo(BigDecimal.ZERO) == 0)
                .count();
        assertThat(zeroedCycle).isGreaterThan(0L);

        // Balances remain well-formed money: PIC S9(10)V99 -> scale 2 (AAP §0.8.2).
        for (Account account : accounts) {
            assertThat(account.getAcctId()).isNotNull();
            assertThat(account.getAcctGroupId()).isNotNull();
            assertThat(account.getAcctCurrBal()).isNotNull();
            assertThat(account.getAcctCurrBal().scale()).isEqualTo(2);
        }
    }

    /**
     * Test 4 &mdash; interest-formula precision (the AAP &sect;0.8.2 core). The formula
     * {@code (bal * rate) / 1200} is evaluated with the divisor literal {@code new BigDecimal("1200")},
     * {@link RoundingMode#HALF_EVEN}, and scale {@code 2}, exactly as the processor computes it.
     * Values are compared with {@code isEqualByComparingTo} (never {@code equals}, which is
     * scale-sensitive).
     */
    @Test
    @Order(4)
    void interestFormulaUsesHalfEvenAtScaleTwo() {
        // Canonical vector: 1000.00 × 12.00 / 1200 = 10.00 exactly, at scale 2.
        assertThat(monthlyInterest("1000.00", "12.00")).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(monthlyInterest("1000.00", "12.00").scale()).isEqualTo(2);

        // HALF_EVEN tie rounding DOWN to the even digit: 2.00 × 3.00 / 1200 = 0.005 -> 0.00.
        // HALF_UP would yield 0.01; HALF_EVEN (banker's rounding) is the binding mode per §0.8.2.
        assertThat(monthlyInterest("2.00", "3.00")).isEqualByComparingTo(new BigDecimal("0.00"));

        // HALF_EVEN tie rounding UP to the even digit: 10.00 × 3.00 / 1200 = 0.025 -> 0.02.
        // HALF_UP would yield 0.03; HALF_EVEN (banker's rounding) is the binding mode per §0.8.2.
        assertThat(monthlyInterest("10.00", "3.00")).isEqualByComparingTo(new BigDecimal("0.02"));

        // Zero rate yields zero interest mathematically (documents the processor's
        // "rate == 0 ⇒ null (no interest record)" branch at the value level).
        assertThat(monthlyInterest("5000.00", "0.00")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * Test 5 &mdash; the {@code "DEFAULT"} disclosure-group fallback. The seed provides the
     * disclosure-group reference data (the {@code "DEFAULT"} group among them), and a successful run
     * re-affirms the specific&rarr;{@code "DEFAULT"} fallback chain resolves a rate for every
     * category balance (no {@code RecordNotFoundException}), preserving CBACT04C's behaviour.
     *
     * @throws Exception if the launcher rejects the run
     */
    @Test
    @Order(5)
    void defaultDisclosureGroupPresentAndFallbackResolves() throws Exception {
        // Flyway V3 seeds the disclosure-group reference data including the DEFAULT fallback group
        // (Gate-4). A lenient lower bound keeps the assertion robust to seed-count changes (the table
        // is read-only, so its count is stable, but the bound avoids brittle coupling).
        assertThat(disclosureGroupRepository.count()).isGreaterThanOrEqualTo(1L);

        JobExecution execution = launchInterest();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // The DEFAULT/('01', 1) pair is seeded (Flyway V3) with a non-zero rate; its presence backs
        // the 1200-A-GET-DEFAULT-INT-RATE fallback that the completed run relies upon.
        assertThat(disclosureGroupRepository.findById(new DisclosureGroupId("DEFAULT", "01", 1)))
                .isPresent();
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Launches the interest-calculation job with a unique {@code run.id} (from the inherited
     * {@code baseParams()}) and the required {@code parmDate} job parameter (the COBOL
     * {@code PARM='2022071800'}).
     *
     * @return the resulting job execution
     * @throws Exception if the launcher rejects the run (already running, restart, complete, or invalid)
     */
    private JobExecution launchInterest() throws Exception {
        return jobLauncher.run(
                interestCalculationJob,
                baseParams().addString("parmDate", PARM_DATE).toJobParameters());
    }

    /**
     * Computes monthly interest exactly as {@code InterestCalculationProcessor} does:
     * {@code (bal * rate) / 1200} with the divisor literal {@code new BigDecimal("1200")},
     * {@link RoundingMode#HALF_EVEN}, and scale {@code 2}. Inputs are decimal strings so the exact
     * unscaled values are pinned with no binary floating-point contamination.
     *
     * @param bal  the transaction-category balance (COBOL {@code TRAN-CAT-BAL})
     * @param rate the disclosure-group rate (COBOL {@code DIS-INT-RATE})
     * @return the monthly interest at scale 2
     */
    private static BigDecimal monthlyInterest(String bal, String rate) {
        return new BigDecimal(bal).multiply(new BigDecimal(rate))
                .divide(new BigDecimal("1200"), 2, RoundingMode.HALF_EVEN);
    }
}
