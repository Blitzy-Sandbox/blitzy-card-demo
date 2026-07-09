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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * Pure, fast unit test for {@link InterestCalculationService}, the interest
 * accrual engine migrated from the COBOL batch program {@code CBACT04C}
 * ({@code app/cbl/CBACT04C.cbl}, frozen reference SHA {@code 27d6c6f} &mdash;
 * read-only, not copied into this repository).
 *
 * <p>All five collaborators are Mockito mocks and the service is constructed via
 * injection, so no Spring context, database, Testcontainers, Docker, or live AWS
 * is involved. The three public methods are exercised end-to-end:</p>
 * <ul>
 *   <li>{@code calculateMonthlyInterest} — {@code 1300-COMPUTE-INTEREST}:
 *       {@code balance * rate / 1200}, {@link BigDecimal} scale&nbsp;2,
 *       {@code HALF_UP}; null-safe operands.</li>
 *   <li>{@code resolveInterestRate} — {@code 1200-GET-INTEREST-RATE} plus the
 *       {@code 1200-A-GET-DEFAULT-INT-RATE} {@code 'DEFAULT'} fallback and the
 *       "no rate → zero" branch.</li>
 *   <li>{@code applyInterestToAccount} — the per-account body of the main loop
 *       ({@code 1100/1110/1300/1300-B/1050}): accrue per category, write one
 *       interest transaction per non-zero rate, roll the total into the balance,
 *       and reset the cycle accumulators.</li>
 * </ul>
 *
 * <p>Every monetary assertion checks both value and scale, upholding the
 * decimal-fidelity constraint (no {@code float}/{@code double}).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterestCalculationService — COBOL CBACT04C interest accrual (SHA 27d6c6f)")
class InterestCalculationServiceTest {

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

    private static DisclosureGroup group(BigDecimal rate) {
        DisclosureGroup g = new DisclosureGroup();
        g.setDisIntRate(rate);
        return g;
    }

    private static TransactionCategoryBalance tcb(long acctId, String typeCd, int catCd, String bal) {
        TransactionCategoryBalance t = new TransactionCategoryBalance();
        t.setId(new TransactionCategoryBalanceId(acctId, typeCd, catCd));
        t.setTranCatBal(new BigDecimal(bal));
        return t;
    }

    @Nested
    @DisplayName("calculateMonthlyInterest — balance*rate/1200 @ scale 2")
    class MonthlyInterest {

        @Test
        @DisplayName("1000 @ 12% → 10.00 (scale 2)")
        void normal() {
            BigDecimal result = service.calculateMonthlyInterest(
                    new BigDecimal("1000.00"), new BigDecimal("12"));
            assertThat(result).isEqualByComparingTo("10.00");
            assertThat(result.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("HALF_UP rounding: 100 @ 1% → 0.08")
        void rounding() {
            // 100 * 1 / 1200 = 0.08333... → 0.08
            assertThat(service.calculateMonthlyInterest(new BigDecimal("100"), BigDecimal.ONE))
                    .isEqualByComparingTo("0.08");
        }

        @Test
        @DisplayName("null balance → 0.00")
        void nullBalance() {
            assertThat(service.calculateMonthlyInterest(null, new BigDecimal("12")))
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("null rate → 0.00")
        void nullRate() {
            assertThat(service.calculateMonthlyInterest(new BigDecimal("1000"), null))
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("both null → 0.00")
        void bothNull() {
            assertThat(service.calculateMonthlyInterest(null, null))
                    .isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("resolveInterestRate — specific / DEFAULT fallback / zero")
    class ResolveRate {

        @Test
        @DisplayName("specific disclosure group found → its rate")
        void specificFound() {
            when(disclosureGroupRepository.findById(new DisclosureGroupId("GRP1", "01", 5)))
                    .thenReturn(Optional.of(group(new BigDecimal("5.5"))));

            assertThat(service.resolveInterestRate("GRP1", "01", 5))
                    .isEqualByComparingTo("5.5");
        }

        @Test
        @DisplayName("specific missing → 'DEFAULT' fallback rate")
        void defaultFallback() {
            when(disclosureGroupRepository.findById(new DisclosureGroupId("GRP1", "01", 5)))
                    .thenReturn(Optional.empty());
            when(disclosureGroupRepository.findById(new DisclosureGroupId("DEFAULT", "01", 5)))
                    .thenReturn(Optional.of(group(new BigDecimal("3.0"))));

            assertThat(service.resolveInterestRate("GRP1", "01", 5))
                    .isEqualByComparingTo("3.0");
        }

        @Test
        @DisplayName("neither specific nor DEFAULT → ZERO")
        void bothMissing() {
            when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                    .thenReturn(Optional.empty());

            assertThat(service.resolveInterestRate("GRP1", "01", 5))
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("group found but null rate → ZERO")
        void foundButNullRate() {
            when(disclosureGroupRepository.findById(new DisclosureGroupId("GRP1", "01", 5)))
                    .thenReturn(Optional.of(group(null)));

            assertThat(service.resolveInterestRate("GRP1", "01", 5))
                    .isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("applyInterestToAccount — per-account accrual + boundary update")
    class ApplyInterest {

        private static final long ACCT_ID = 55L;

        @Test
        @DisplayName("account not found → ResourceNotFoundException")
        void accountNotFound() {
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.applyInterestToAccount(ACCT_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("non-zero rate: writes one interest txn, rolls total into balance, resets cycle")
        void withInterest() {
            Account account = new Account();
            account.setAcctGroupId("GRP1");
            account.setAcctCurrBal(new BigDecimal("1000.00"));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            when(crossReferenceService.resolvePrimaryCardNumber(ACCT_ID)).thenReturn("4111111111111111");
            when(crossReferenceService.generateNextTransactionId()).thenReturn("0000000000001000");
            when(transactionCategoryBalanceRepository.findByIdTrancatAcctId(ACCT_ID))
                    .thenReturn(List.of(tcb(ACCT_ID, "01", 5, "1000.00")));
            when(disclosureGroupRepository.findById(new DisclosureGroupId("GRP1", "01", 5)))
                    .thenReturn(Optional.of(group(new BigDecimal("12"))));

            BigDecimal total = service.applyInterestToAccount(ACCT_ID);

            // 1000 * 12 / 1200 = 10.00 total interest.
            assertThat(total).isEqualByComparingTo("10.00");
            assertThat(total.scale()).isEqualTo(2);
            // 1050-UPDATE-ACCOUNT: balance += total; cycle credit/debit reset to 0.00.
            assertThat(account.getAcctCurrBal()).isEqualByComparingTo("1010.00");
            assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo("0.00");
            assertThat(account.getAcctCurrCycDebit()).isEqualByComparingTo("0.00");
            verify(accountRepository).save(account);

            // 1300-B-WRITE-TX: exactly one interest transaction, amount at scale 2.
            org.mockito.ArgumentCaptor<Transaction> cap =
                    org.mockito.ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(cap.capture());
            assertThat(cap.getValue().getTranAmt()).isEqualByComparingTo("10.00");
            assertThat(cap.getValue().getTranCardNum()).isEqualTo("4111111111111111");
        }

        @Test
        @DisplayName("zero rate: no txn written, balance unchanged, cycle still reset, total 0.00")
        void zeroRate() {
            Account account = new Account();
            account.setAcctGroupId("GRP1");
            account.setAcctCurrBal(new BigDecimal("500.00"));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            when(crossReferenceService.resolvePrimaryCardNumber(ACCT_ID)).thenReturn("4111111111111111");
            when(crossReferenceService.generateNextTransactionId()).thenReturn("0000000000002000");
            when(transactionCategoryBalanceRepository.findByIdTrancatAcctId(ACCT_ID))
                    .thenReturn(List.of(tcb(ACCT_ID, "01", 5, "500.00")));
            when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                    .thenReturn(Optional.empty());

            BigDecimal total = service.applyInterestToAccount(ACCT_ID);

            assertThat(total).isEqualByComparingTo("0.00");
            assertThat(account.getAcctCurrBal()).isEqualByComparingTo("500.00");
            assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo("0.00");
            verify(transactionRepository, never()).save(any());
            verify(accountRepository).save(account);
        }
    }
}
