package com.cardemo.unit.batch.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.batch.processors.TransactionPostingProcessor;
import com.cardemo.model.dto.PostedTransactionResult;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TransactionPostingProcessor} — the {@code CBTRN02C} {@code 1500-VALIDATE-TRAN}
 * cascade, a highest-risk migration site flagged by the CP3 review. Verifies the reject-code cascade in
 * exact COBOL order — 100 (xref) → 101 (account) → 102 (over-limit) → 103 (expiration) — including the
 * "reject &amp; continue" contract (never throws, never returns null) and the last-failure-wins rule
 * where a 103 expiration failure overwrites a 102 over-limit failure (two independent IFs, not else-if).
 */
class TransactionPostingProcessorTest {

    private static final String CARD_NUM = "1234567890123456";
    private static final Long ACCT_ID = 1L;
    private static final LocalDateTime ORIG_TS = LocalDateTime.of(2024, 1, 1, 12, 0);

    private CardCrossReferenceRepository crossReferenceRepository;
    private AccountRepository accountRepository;
    private TransactionPostingProcessor processor;

    @BeforeEach
    void setUp() {
        crossReferenceRepository = mock(CardCrossReferenceRepository.class);
        accountRepository = mock(AccountRepository.class);
        processor = new TransactionPostingProcessor(crossReferenceRepository, accountRepository);
    }

    private DailyTransaction dailyTransaction(BigDecimal amount) {
        DailyTransaction d = new DailyTransaction();
        d.setDalytranId("DT00000000000001");
        d.setDalytranCardNum(CARD_NUM);
        d.setDalytranAmt(amount);
        d.setDalytranOrigTs(ORIG_TS);
        return d;
    }

    private void stubXref() {
        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum(CARD_NUM);
        xref.setXrefAcctId(ACCT_ID);
        when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref));
    }

    private Account account(BigDecimal creditLimit, BigDecimal cycCredit, BigDecimal cycDebit,
            LocalDate expiration) {
        Account a = new Account();
        a.setAcctId(ACCT_ID);
        a.setAcctCreditLimit(creditLimit);
        a.setAcctCurrCycCredit(cycCredit);
        a.setAcctCurrCycDebit(cycDebit);
        a.setAcctExpirationDate(expiration);
        return a;
    }

    @Test
    @DisplayName("reject 100: missing cross-reference -> INVALID_CARD_NUMBER, account lookup skipped")
    void reject100MissingXref() {
        when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

        PostedTransactionResult result = processor.process(dailyTransaction(new BigDecimal("100.00")));

        assertThat(result).isNotNull();
        assertThat(result.rejectCode()).isEqualTo(RejectCode.INVALID_CARD_NUMBER);
        assertThat(result.isRejected()).isTrue();
        assertThat(result.transaction()).isNull();
        assertThat(result.account()).isNull();
        assertThat(result.crossReference()).isNull();
        // 1500-B (account lookup) is guarded out when 1500-A already failed.
        verify(accountRepository, never()).findById(ACCT_ID);
    }

    @Test
    @DisplayName("reject 101: xref found but account missing -> ACCOUNT_NOT_FOUND")
    void reject101MissingAccount() {
        stubXref();
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

        PostedTransactionResult result = processor.process(dailyTransaction(new BigDecimal("100.00")));

        assertThat(result.rejectCode()).isEqualTo(RejectCode.ACCOUNT_NOT_FOUND);
        assertThat(result.account()).isNull();
        assertThat(result.crossReference()).isNotNull();
    }

    @Test
    @DisplayName("reject 102: credit limit < (cycCredit - cycDebit + amount) -> OVERLIMIT_TRANSACTION")
    void reject102OverLimit() {
        stubXref();
        // tempBal = 1000 - 0 + 100 = 1100; creditLimit 1000 < 1100 -> 102. Expiry in the far future -> no 103.
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(
                account(new BigDecimal("1000.00"), new BigDecimal("1000.00"),
                        new BigDecimal("0.00"), LocalDate.of(2099, 12, 31))));

        PostedTransactionResult result = processor.process(dailyTransaction(new BigDecimal("100.00")));

        assertThat(result.rejectCode()).isEqualTo(RejectCode.OVERLIMIT_TRANSACTION);
        assertThat(result.account()).isNotNull();
        assertThat(result.crossReference()).isNotNull();
    }

    @Test
    @DisplayName("reject 103: account expiration precedes the transaction date -> TRANSACTION_AFTER_EXPIRATION")
    void reject103Expired() {
        stubXref();
        // creditLimit high -> no 102; expiry 2020-01-01 < origTs 2024-01-01 -> 103.
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(
                account(new BigDecimal("5000.00"), new BigDecimal("1000.00"),
                        new BigDecimal("0.00"), LocalDate.of(2020, 1, 1))));

        PostedTransactionResult result = processor.process(dailyTransaction(new BigDecimal("100.00")));

        assertThat(result.rejectCode()).isEqualTo(RejectCode.TRANSACTION_AFTER_EXPIRATION);
    }

    @Test
    @DisplayName("last-failure-wins: when BOTH 102 and 103 fail, 103 overwrites 102")
    void reject103OverwritesReject102() {
        stubXref();
        // creditLimit 1000 < tempBal 1100 -> would set 102; expiry 2020 < 2024 -> sets 103 last.
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(
                account(new BigDecimal("1000.00"), new BigDecimal("1000.00"),
                        new BigDecimal("0.00"), LocalDate.of(2020, 1, 1))));

        PostedTransactionResult result = processor.process(dailyTransaction(new BigDecimal("100.00")));

        assertThat(result.rejectCode()).isEqualTo(RejectCode.TRANSACTION_AFTER_EXPIRATION);
    }

    @Test
    @DisplayName("accepted: all checks pass -> NONE, mapped transaction present")
    void acceptedTransaction() {
        stubXref();
        // tempBal = 1000 - 0 + 100 = 1100; creditLimit 5000 >= 1100 -> no 102; expiry future -> no 103.
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(
                account(new BigDecimal("5000.00"), new BigDecimal("1000.00"),
                        new BigDecimal("0.00"), LocalDate.of(2099, 12, 31))));

        PostedTransactionResult result = processor.process(dailyTransaction(new BigDecimal("100.00")));

        assertThat(result.rejectCode()).isEqualTo(RejectCode.NONE);
        assertThat(result.isAccepted()).isTrue();
        assertThat(result.transaction()).isNotNull();
        assertThat(result.transaction().getTranCardNum()).isEqualTo(CARD_NUM);
        assertThat(result.transaction().getTranAmt()).isEqualByComparingTo("100.00");
        assertThat(result.account()).isNotNull();
        assertThat(result.crossReference()).isNotNull();
    }
}
