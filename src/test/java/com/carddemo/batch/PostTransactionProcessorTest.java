package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.exception.FileProcessingException;
import com.carddemo.exception.RejectReason;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;

/**
 * Pure, fast unit test for {@link PostTransactionProcessor}, the per-record body
 * of the daily-transaction posting job migrated from the COBOL batch program
 * {@code CBTRN02C} ({@code app/cbl/CBTRN02C.cbl}, frozen reference SHA
 * {@code 27d6c6f} &mdash; read-only, not copied into this repository).
 *
 * <p>All three repositories are Mockito mocks and a fixed {@link Clock} is
 * injected through the package-private constructor, so the processing timestamp
 * ({@code TRAN-PROC-TS}) is deterministic and the suite touches no Spring
 * context, database, Testcontainers, Docker, or live AWS.</p>
 *
 * <p>The COBOL {@code 1500-VALIDATE-TRAN} decision tree is exercised branch by
 * branch &mdash; the four reject reasons ({@link RejectReason#INVALID_CARD_NUMBER},
 * {@link RejectReason#ACCOUNT_NOT_FOUND}, {@link RejectReason#OVERLIMIT},
 * {@link RejectReason#ACCOUNT_EXPIRED}) and the posted path
 * ({@code 2000-POST-TRANSACTION} &rarr; {@code 2700-UPDATE-TCATBAL} &rarr;
 * {@code 2800-UPDATE-ACCOUNT-REC}) for both the category-balance create/update
 * forks and the credit/debit cycle-accumulator forks. The ABEND-class
 * origination-timestamp fault ({@code 9999-ABEND-PROGRAM}) is asserted to surface
 * as a {@link FileProcessingException}. Monetary assertions check both value and
 * scale&nbsp;2 (decimal fidelity; no {@code float}/{@code double}).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostTransactionProcessor — COBOL CBTRN02C posting (SHA 27d6c6f)")
class PostTransactionProcessorTest {

    private static final String CARD_NUM = "4111111111111111";
    private static final long ACCT_ID = 987654321L;
    private static final String TYPE_CD = "01";
    private static final int CAT_CD = 5;
    /** A valid ISO origination timestamp whose date component is 2024-01-10. */
    private static final String ORIG_TS = "2024-01-10T12:00:00.000000";

    /** Fixed clock so {@code TRAN-PROC-TS} is deterministic. */
    private final Clock clock = Clock.fixed(Instant.parse("2024-06-15T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    private PostTransactionProcessor processor;

    private PostTransactionProcessor newProcessor() {
        return new PostTransactionProcessor(
                cardXrefRepository, accountRepository,
                transactionCategoryBalanceRepository, clock);
    }

    private static DailyTransaction dt(String amount, String origTs) {
        DailyTransaction d = new DailyTransaction();
        d.setDalytranId("0000000000000001");
        d.setDalytranCardNum(CARD_NUM);
        d.setDalytranTypeCd(TYPE_CD);
        d.setDalytranCatCd(CAT_CD);
        d.setDalytranSource("POS");
        d.setDalytranDesc("PURCHASE");
        d.setDalytranAmt(new BigDecimal(amount));
        d.setDalytranMerchantId(123L);
        d.setDalytranMerchantName("ACME");
        d.setDalytranMerchantCity("CITY");
        d.setDalytranMerchantZip("00000");
        d.setDalytranOrigTs(origTs);
        return d;
    }

    private static CardXref xref() {
        CardXref x = new CardXref();
        x.setXrefCardNum(CARD_NUM);
        x.setXrefAcctId(ACCT_ID);
        x.setXrefCustId(42L);
        return x;
    }

    /**
     * Builds an account with the supplied credit limit and expiration date and
     * zeroed cycle accumulators + balance, suitable for both reject and posted paths.
     */
    private static Account account(String creditLimit, LocalDate expiration) {
        Account a = new Account();
        a.setAcctId(ACCT_ID);
        a.setAcctCreditLimit(new BigDecimal(creditLimit));
        a.setAcctCurrCycCredit(new BigDecimal("0.00"));
        a.setAcctCurrCycDebit(new BigDecimal("0.00"));
        a.setAcctCurrBal(new BigDecimal("500.00"));
        a.setAcctExpirationDate(expiration);
        return a;
    }

    @Nested
    @DisplayName("1500-VALIDATE-TRAN — reject branches (no side-effects)")
    class RejectBranches {

        @Test
        @DisplayName("null daily transaction → NullPointerException")
        void nullDt() {
            processor = newProcessor();
            assertThatThrownBy(() -> processor.process(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("card cross-reference missing → INVALID_CARD_NUMBER, nothing saved")
        void invalidCardNumber() {
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
            processor = newProcessor();

            var result = processor.process(dt("100.00", ORIG_TS));

            assertThat(result.isRejected()).isTrue();
            assertThat(result.rejectReason()).isEqualTo(RejectReason.INVALID_CARD_NUMBER);
            assertThat(result.postedTransaction()).isNull();
            verify(accountRepository, never()).save(any());
            verify(transactionCategoryBalanceRepository, never()).save(any());
        }

        @Test
        @DisplayName("account missing → ACCOUNT_NOT_FOUND, nothing saved")
        void accountNotFound() {
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref()));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());
            processor = newProcessor();

            var result = processor.process(dt("100.00", ORIG_TS));

            assertThat(result.rejectReason()).isEqualTo(RejectReason.ACCOUNT_NOT_FOUND);
            verify(accountRepository, never()).save(any());
        }

        @Test
        @DisplayName("credit limit exceeded → OVERLIMIT (not expired)")
        void overlimit() {
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref()));
            // limit 500 < wsTempBal (0 - 0 + 1000) = 1000 → overlimit; expiration far in future.
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(account("500.00", LocalDate.of(2024, 12, 31))));
            processor = newProcessor();

            var result = processor.process(dt("1000.00", ORIG_TS));

            assertThat(result.rejectReason()).isEqualTo(RejectReason.OVERLIMIT);
            verify(accountRepository, never()).save(any());
            verify(transactionCategoryBalanceRepository, never()).save(any());
        }

        @Test
        @DisplayName("origination date after expiration → ACCOUNT_EXPIRED (overrides overlimit ok)")
        void expired() {
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref()));
            // limit high (not overlimit) but expiration 2023-12-31 < orig 2024-01-10 → expired.
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(account("100000.00", LocalDate.of(2023, 12, 31))));
            processor = newProcessor();

            var result = processor.process(dt("100.00", ORIG_TS));

            assertThat(result.rejectReason()).isEqualTo(RejectReason.ACCOUNT_EXPIRED);
            verify(accountRepository, never()).save(any());
        }

        @Test
        @DisplayName("unparseable DALYTRAN-ORIG-TS → FileProcessingException (9999-ABEND)")
        void unparseableOrigTs() {
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref()));
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(account("100000.00", LocalDate.of(2024, 12, 31))));
            processor = newProcessor();

            // origTs shorter than the 10-char date prefix → data fault.
            assertThatThrownBy(() -> processor.process(dt("100.00", "2024")))
                    .isInstanceOf(FileProcessingException.class);
        }
    }

    @Nested
    @DisplayName("Posted path — 2000/2700/2800")
    class PostedPath {

        @Test
        @DisplayName("credit txn: creates TCATBAL, adds to cycle-credit, posts")
        void postedCreditCreatesCategoryBalance() {
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref()));
            Account acct = account("100000.00", LocalDate.of(2024, 12, 31));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(acct));
            // 2700-A create branch: no existing category balance.
            when(transactionCategoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                    .thenReturn(Optional.empty());
            processor = newProcessor();

            var result = processor.process(dt("100.00", ORIG_TS));

            assertThat(result.isPosted()).isTrue();
            assertThat(result.postedTransaction()).isNotNull();
            assertThat(result.postedTransaction().getTranId()).isEqualTo("0000000000000001");
            assertThat(result.postedTransaction().getTranAmt()).isEqualByComparingTo("100.00");
            assertThat(result.postedTransaction().getTranAmt().scale()).isEqualTo(2);
            assertThat(result.postedTransaction().getTranProcTs()).isNotBlank();

            // 2800: balance += 100 → 600.00; amount >= 0 → cycle credit += 100 → 100.00.
            assertThat(acct.getAcctCurrBal()).isEqualByComparingTo("600.00");
            assertThat(acct.getAcctCurrCycCredit()).isEqualByComparingTo("100.00");
            assertThat(acct.getAcctCurrCycDebit()).isEqualByComparingTo("0.00");
            verify(accountRepository).save(acct);

            // 2700-A: created category balance seeded with the amount at scale 2.
            ArgumentCaptor<TransactionCategoryBalance> cap =
                    ArgumentCaptor.forClass(TransactionCategoryBalance.class);
            verify(transactionCategoryBalanceRepository).save(cap.capture());
            assertThat(cap.getValue().getTranCatBal()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("debit txn: updates existing TCATBAL, adds to cycle-debit, posts")
        void postedDebitUpdatesCategoryBalance() {
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref()));
            Account acct = account("100000.00", LocalDate.of(2024, 12, 31));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(acct));

            TransactionCategoryBalance existing = new TransactionCategoryBalance();
            existing.setId(new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD));
            existing.setTranCatBal(new BigDecimal("200.00"));
            when(transactionCategoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                    .thenReturn(Optional.of(existing));
            processor = newProcessor();

            var result = processor.process(dt("-50.00", ORIG_TS));

            assertThat(result.isPosted()).isTrue();
            // 2700-B: 200 + (-50) = 150.00.
            assertThat(existing.getTranCatBal()).isEqualByComparingTo("150.00");
            verify(transactionCategoryBalanceRepository).save(existing);

            // 2800: balance += -50 → 450.00; amount < 0 → cycle debit += -50 → -50.00.
            assertThat(acct.getAcctCurrBal()).isEqualByComparingTo("450.00");
            assertThat(acct.getAcctCurrCycDebit()).isEqualByComparingTo("-50.00");
            assertThat(acct.getAcctCurrCycCredit()).isEqualByComparingTo("0.00");
            verify(accountRepository).save(acct);
        }
    }
}
