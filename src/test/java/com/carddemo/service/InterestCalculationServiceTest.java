package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.carddemo.entity.Account;
import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;

/**
 * Pure, fast unit test for {@link InterestCalculationService}, the monthly
 * interest-accrual engine migrated from the COBOL batch program {@code CBACT04C}
 * ({@code app/cbl/CBACT04C.cbl}, frozen reference commit SHA {@code 27d6c6f}
 * &mdash; read-only, <em>not</em> copied into this repository).
 *
 * <h2>Test strategy</h2>
 * <p>All five collaborators are Mockito mocks and the service is constructed via
 * {@link InjectMocks constructor injection}, so <strong>no Spring context, no
 * database, no Testcontainers, no Docker and no live AWS</strong> are involved
 * &mdash; the suite is a millisecond-scale, deterministic unit test. The
 * {@link MockitoExtension} runs with its default <em>strict stubs</em> policy, so
 * each test stubs only the collaborator interactions it actually exercises; an
 * unused stub fails the build, keeping the tests honest.</p>
 *
 * <h2>Coverage of the migrated behaviour (traceability to {@code CBACT04C})</h2>
 * <ul>
 *   <li>{@link InterestCalculationService#calculateMonthlyInterest(BigDecimal, BigDecimal)}
 *       &mdash; COBOL {@code 1300-COMPUTE-INTEREST}
 *       ({@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}):
 *       the {@code balance * rate / 1200} formula at {@link BigDecimal} scale&nbsp;2
 *       with {@code HALF_UP} rounding, including a genuine {@code x.xx5} rounding
 *       tie and the null-safe / zero-rate branches.</li>
 *   <li>{@link InterestCalculationService#resolveInterestRate(String, String, Integer)}
 *       &mdash; COBOL {@code 1200-GET-INTEREST-RATE} plus its
 *       {@code 1200-A-GET-DEFAULT-INT-RATE} {@code 'DEFAULT'} fallback (the VSAM
 *       {@code FILE STATUS '23'} branch) and the "no rate on file &rarr; zero"
 *       behaviour.</li>
 *   <li>{@link InterestCalculationService#applyInterestToAccount(Long)} &mdash;
 *       the per-account body of the main {@code PERFORM UNTIL END-OF-FILE} loop
 *       together with the {@code 1050-UPDATE-ACCOUNT} boundary: it writes one
 *       interest {@code Transaction} per non-zero-rate category
 *       ({@code 1300-B-WRITE-TX}), rolls the accrued total into the account
 *       balance and resets the current-cycle credit/debit accumulators, and
 *       writes nothing when the rate is zero.</li>
 * </ul>
 *
 * <h2>Decimal-fidelity discipline (AAP&nbsp;G2, &sect;0.8.2)</h2>
 * <p>Every monetary assertion compares by <em>value</em> via
 * {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo(String)
 * isEqualByComparingTo} (i.e. {@link BigDecimal#compareTo(BigDecimal)}{@code == 0},
 * never {@link BigDecimal#equals(Object)}) and additionally pins the scale with
 * {@code assertThat(value.scale()).isEqualTo(2)} wherever a two-decimal money
 * value is expected. No {@code float} or {@code double} appears anywhere in this
 * test.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterestCalculationService — COBOL CBACT04C interest accrual (SHA 27d6c6f)")
class InterestCalculationServiceTest {

    /** Account identifier reused across the {@code applyInterestToAccount} scenarios. */
    private static final long ACCT_ID = 55L;

    /** Card number the mocked cross-reference resolves for {@link #ACCT_ID}. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The two-decimal money scale enforced across the whole service (COBOL {@code Vnn}). */
    private static final int MONEY_SCALE = 2;

    @Mock
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    @Mock
    private DisclosureGroupRepository disclosureGroupRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CrossReferenceService crossReferenceService;

    @InjectMocks
    private InterestCalculationService service;

    // ------------------------------------------------------------------
    // Test data factories
    // ------------------------------------------------------------------

    /**
     * Builds a {@link DisclosureGroup} carrying only the interest rate &mdash; the
     * single field {@link InterestCalculationService#resolveInterestRate(String, String, Integer)}
     * reads.
     *
     * @param rate the annual interest rate to expose via {@code getDisIntRate()}
     *             (may be {@code null} to model a row with no rate)
     * @return a disclosure-group stub with the supplied rate
     */
    private static DisclosureGroup group(BigDecimal rate) {
        DisclosureGroup disclosureGroup = new DisclosureGroup();
        disclosureGroup.setDisIntRate(rate);
        return disclosureGroup;
    }

    /**
     * Builds a {@link TransactionCategoryBalance} with a composite key and running
     * balance &mdash; the two properties the service reads per category.
     *
     * @param acctId  the owning account id ({@code TRANCAT-ACCT-ID})
     * @param typeCd  the transaction type code ({@code TRANCAT-TYPE-CD})
     * @param catCd   the transaction category code ({@code TRANCAT-CD})
     * @param balance the category running balance ({@code TRAN-CAT-BAL})
     * @return a category-balance stub
     */
    private static TransactionCategoryBalance categoryBalance(
            long acctId, String typeCd, int catCd, String balance) {
        TransactionCategoryBalance tcb = new TransactionCategoryBalance();
        tcb.setId(new TransactionCategoryBalanceId(acctId, typeCd, catCd));
        tcb.setTranCatBal(new BigDecimal(balance));
        return tcb;
    }

    /**
     * Builds an {@link Account} with the group id and current balance the service
     * reads; the current-cycle accumulators are deliberately left unset because the
     * service overwrites them at the account boundary.
     *
     * @param groupId     the account group id ({@code ACCT-GROUP-ID})
     * @param currentBal  the current balance ({@code ACCT-CURR-BAL})
     * @return an account stub
     */
    private static Account account(String groupId, String currentBal) {
        Account account = new Account();
        account.setAcctGroupId(groupId);
        account.setAcctCurrBal(new BigDecimal(currentBal));
        return account;
    }

    // ==================================================================
    // Phase 1 — calculateMonthlyInterest (pure decimal math, no mocks)
    // ==================================================================

    @Test
    @DisplayName("calculateMonthlyInterest: 1000.00 @ 12.00% → 10.00 (scale 2)")
    void calculateMonthlyInterest_basic() {
        // 1300-COMPUTE-INTEREST: 1000.00 * 12.00 / 1200 = 10.00.
        BigDecimal result = service.calculateMonthlyInterest(
                new BigDecimal("1000.00"), new BigDecimal("12.00"));

        assertThat(result).isEqualByComparingTo("10.00");
        assertThat(result.scale()).isEqualTo(MONEY_SCALE);
    }

    @Test
    @DisplayName("calculateMonthlyInterest: HALF_UP tie 0.125 → 0.13 (scale 2)")
    void calculateMonthlyInterest_rounding_halfUp() {
        // 1000.00 * 0.15 = 150.0000 ; 150.0000 / 1200 = 0.125 exactly — a true
        // half-way tie. HALF_UP rounds the trailing .5 away from zero: 0.125 → 0.13
        // (HALF_DOWN / HALF_EVEN would yield 0.12), proving the COBOL rounding mode.
        BigDecimal result = service.calculateMonthlyInterest(
                new BigDecimal("1000.00"), new BigDecimal("0.15"));

        assertThat(result).isEqualByComparingTo("0.13");
        assertThat(result.scale()).isEqualTo(MONEY_SCALE);
    }

    @Test
    @DisplayName("calculateMonthlyInterest: zero rate → 0.00 (scale 2)")
    void calculateMonthlyInterest_zeroRate_returnsZero() {
        // IF DIS-INT-RATE NOT = 0 guard is upstream; the formula itself yields 0.00.
        BigDecimal result = service.calculateMonthlyInterest(
                new BigDecimal("1000.00"), BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo("0.00");
        assertThat(result.scale()).isEqualTo(MONEY_SCALE);
    }

    @Test
    @DisplayName("calculateMonthlyInterest: null balance → 0.00 (null-safe)")
    void calculateMonthlyInterest_nullBalance_returnsZero() {
        BigDecimal result = service.calculateMonthlyInterest(null, new BigDecimal("12.00"));

        assertThat(result).isEqualByComparingTo("0.00");
        assertThat(result.scale()).isEqualTo(MONEY_SCALE);
    }

    @Test
    @DisplayName("calculateMonthlyInterest: null rate → 0.00 (null-safe)")
    void calculateMonthlyInterest_nullRate_returnsZero() {
        BigDecimal result = service.calculateMonthlyInterest(new BigDecimal("1000.00"), null);

        assertThat(result).isEqualByComparingTo("0.00");
        assertThat(result.scale()).isEqualTo(MONEY_SCALE);
    }

    // ==================================================================
    // Phase 2a — resolveInterestRate (Mockito)
    // ==================================================================

    @Test
    @DisplayName("resolveInterestRate: specific group found → its rate")
    void resolveInterestRate_groupFound_returnsGroupRate() {
        // 1200-GET-INTEREST-RATE: full-composite-key read hits the account's own group.
        when(disclosureGroupRepository.findById(new DisclosureGroupId("GRP1", "01", 5)))
                .thenReturn(Optional.of(group(new BigDecimal("5.50"))));

        BigDecimal rate = service.resolveInterestRate("GRP1", "01", 5);

        assertThat(rate).isEqualByComparingTo("5.50");
        assertThat(rate.scale()).isEqualTo(MONEY_SCALE);
    }

    @Test
    @DisplayName("resolveInterestRate: specific missing → 'DEFAULT' fallback rate")
    void resolveInterestRate_groupMissing_fallsBackToDefault() {
        // 1200-A-GET-DEFAULT-INT-RATE: the FILE STATUS '23' branch re-reads with 'DEFAULT'.
        when(disclosureGroupRepository.findById(new DisclosureGroupId("GRP1", "01", 5)))
                .thenReturn(Optional.empty());
        when(disclosureGroupRepository.findById(new DisclosureGroupId("DEFAULT", "01", 5)))
                .thenReturn(Optional.of(group(new BigDecimal("3.25"))));

        BigDecimal rate = service.resolveInterestRate("GRP1", "01", 5);

        assertThat(rate).isEqualByComparingTo("3.25");
        assertThat(rate.scale()).isEqualTo(MONEY_SCALE);
    }

    @Test
    @DisplayName("resolveInterestRate: neither specific nor DEFAULT on file → ZERO")
    void resolveInterestRate_neitherFound_returnsZero() {
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.empty());

        // No rate anywhere ⇒ accrue nothing for the category (compareTo tolerates scale 0).
        assertThat(service.resolveInterestRate("GRP1", "01", 5)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("resolveInterestRate: group found but null rate → ZERO")
    void resolveInterestRate_groupFoundButNullRate_returnsZero() {
        when(disclosureGroupRepository.findById(new DisclosureGroupId("GRP1", "01", 5)))
                .thenReturn(Optional.of(group(null)));

        assertThat(service.resolveInterestRate("GRP1", "01", 5)).isEqualByComparingTo("0");
    }

    // ==================================================================
    // Phase 2b — applyInterestToAccount (Mockito)
    // ==================================================================

    @Test
    @DisplayName("applyInterestToAccount: unknown account → ResourceNotFoundException")
    void applyInterestToAccount_accountNotFound_throwsResourceNotFound() {
        // 1100-GET-ACCT-DATA INVALID KEY branch → ResourceNotFoundException; nothing else runs.
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyInterestToAccount(ACCT_ID))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("applyInterestToAccount: zero rate posts no transaction; cycle still reset")
    void applyInterestToAccount_zeroRate_postsNoTransaction() {
        Account account = account("GRP1", "500.00");
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(crossReferenceService.resolvePrimaryCardNumber(ACCT_ID)).thenReturn(CARD_NUMBER);
        when(crossReferenceService.generateNextTransactionId()).thenReturn("0000000000002000");
        when(transactionCategoryBalanceRepository.findByIdTrancatAcctId(ACCT_ID))
                .thenReturn(List.of(categoryBalance(ACCT_ID, "01", 5, "500.00")));
        // No disclosure group on file (specific and DEFAULT both empty) ⇒ rate 0 ⇒ no accrual.
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.empty());

        BigDecimal total = service.applyInterestToAccount(ACCT_ID);

        // COBOL posts NO interest transaction when DIS-INT-RATE = 0.
        verify(transactionRepository, never()).save(any());

        // Total interest is 0.00 and the balance is unchanged.
        assertThat(total).isEqualByComparingTo("0.00");
        assertThat(total.scale()).isEqualTo(MONEY_SCALE);
        assertThat(account.getAcctCurrBal()).isEqualByComparingTo("500.00");
        assertThat(account.getAcctCurrBal().scale()).isEqualTo(MONEY_SCALE);

        // 1050-UPDATE-ACCOUNT still runs at the boundary: cycle credit/debit reset to 0.00.
        assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo("0.00");
        assertThat(account.getAcctCurrCycCredit().scale()).isEqualTo(MONEY_SCALE);
        assertThat(account.getAcctCurrCycDebit()).isEqualByComparingTo("0.00");
        assertThat(account.getAcctCurrCycDebit().scale()).isEqualTo(MONEY_SCALE);
        verify(accountRepository).save(account);
    }

    @Test
    @DisplayName("applyInterestToAccount: non-zero rate posts interest txn and updates account")
    void applyInterestToAccount_nonZero_postsInterestTxnAndUpdatesAccount() {
        Account account = account("GRP1", "1000.00");
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(crossReferenceService.resolvePrimaryCardNumber(ACCT_ID)).thenReturn(CARD_NUMBER);
        when(crossReferenceService.generateNextTransactionId()).thenReturn("0000000000001000");
        when(transactionCategoryBalanceRepository.findByIdTrancatAcctId(ACCT_ID))
                .thenReturn(List.of(categoryBalance(ACCT_ID, "01", 5, "1000.00")));
        when(disclosureGroupRepository.findById(new DisclosureGroupId("GRP1", "01", 5)))
                .thenReturn(Optional.of(group(new BigDecimal("12.00"))));

        BigDecimal total = service.applyInterestToAccount(ACCT_ID);

        // 1300-COMPUTE-INTEREST: 1000.00 * 12.00 / 1200 = 10.00 accrued for the account.
        assertThat(total).isEqualByComparingTo("10.00");
        assertThat(total.scale()).isEqualTo(MONEY_SCALE);

        // 1050-UPDATE-ACCOUNT: ADD WS-TOTAL-INT TO ACCT-CURR-BAL (1000.00 + 10.00 = 1010.00)
        // and MOVE 0 TO the current-cycle credit/debit accumulators.
        assertThat(account.getAcctCurrBal()).isEqualByComparingTo("1010.00");
        assertThat(account.getAcctCurrBal().scale()).isEqualTo(MONEY_SCALE);
        assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo("0.00");
        assertThat(account.getAcctCurrCycCredit().scale()).isEqualTo(MONEY_SCALE);
        assertThat(account.getAcctCurrCycDebit()).isEqualByComparingTo("0.00");
        assertThat(account.getAcctCurrCycDebit().scale()).isEqualTo(MONEY_SCALE);
        verify(accountRepository).save(account);

        // 1300-B-WRITE-TX: exactly one interest transaction, carrying the exact COBOL constants.
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction posted = captor.getValue();

        assertThat(posted.getTranTypeCd()).isEqualTo("01");           // MOVE '01' TO TRAN-TYPE-CD
        assertThat(posted.getTranCatCd()).isEqualTo(5);               // MOVE '05' TO TRAN-CAT-CD
        assertThat(posted.getTranSource()).isEqualTo("System");       // MOVE 'System' TO TRAN-SOURCE
        assertThat(posted.getTranDesc()).isEqualTo("Int. for a/c " + ACCT_ID); // STRING 'Int. for a/c ' ACCT-ID
        assertThat(posted.getTranCardNum()).isEqualTo(CARD_NUMBER);   // XREF-CARD-NUM
        assertThat(posted.getTranId()).isEqualTo("0000000000001000"); // %016d of the next id
        assertThat(posted.getTranMerchantId()).isEqualTo(0L);         // MOVE 0 TO merchant id
        assertThat(posted.getTranMerchantName()).isEmpty();           // MOVE SPACES → "" (repadded at I/O)
        assertThat(posted.getTranMerchantCity()).isEmpty();
        assertThat(posted.getTranMerchantZip()).isEmpty();

        // Origination and processing timestamps: the same 26-char DB2 value used for both.
        assertThat(posted.getTranOrigTs()).isNotNull().hasSize(26);
        assertThat(posted.getTranProcTs()).isEqualTo(posted.getTranOrigTs());

        // Interest amount at scale 2 (decimal fidelity).
        assertThat(posted.getTranAmt()).isEqualByComparingTo("10.00");
        assertThat(posted.getTranAmt().scale()).isEqualTo(MONEY_SCALE);
    }
}
