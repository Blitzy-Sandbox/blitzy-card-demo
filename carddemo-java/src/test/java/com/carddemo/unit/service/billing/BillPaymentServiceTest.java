package com.carddemo.unit.service.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.BillPaymentRequest;
import com.carddemo.model.dto.BillPaymentResponse;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.billing.BillPaymentService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BillPaymentService} (COBOL {@code COBIL00C}, commit {@code 27d6c6f},
 * REFERENCE ONLY).
 *
 * <p>The headline test pins the CP3 timestamp-parity fix: {@code GET-CURRENT-TIMESTAMP} performs
 * {@code MOVE ZEROS TO WS-TIMESTAMP-TM-MS6}, zeroing the 6-digit microsecond portion, so the posted
 * transaction's originating and processing timestamps must end in {@code .000000}. The remaining
 * tests exercise the {@code PROCESS-ENTER-KEY} branch order (confirm gate, "nothing to pay", account
 * read failure) to keep behavioral parity covered.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillPaymentService — COBIL00C bill payment")
class BillPaymentServiceTest {

    private static final String ACCOUNT_ID = "12345678901";
    private static final long ACCOUNT_ID_LONG = 12345678901L;
    private static final String CARD_NUMBER = "4111111111111111";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    private BillPaymentService service;

    @BeforeEach
    void setUp() {
        service = new BillPaymentService(
                accountRepository, transactionRepository, cardCrossReferenceRepository);
    }

    @Test
    @DisplayName("posts payment with timestamps whose microsecond portion is zeroed (.000000)")
    void postedTimestampHasZeroedMicroseconds() {
        final Account account = account(new BigDecimal("100.00"));
        when(accountRepository.findById(ACCOUNT_ID_LONG)).thenReturn(Optional.of(account));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID_LONG))
                .thenReturn(List.of(xref(CARD_NUMBER)));
        when(transactionRepository.findMaxTranId()).thenReturn("0000000000000009");

        final BillPaymentResponse response = service.pay(new BillPaymentRequest(ACCOUNT_ID, "Y"));

        final ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txnCaptor.capture());
        final Transaction posted = txnCaptor.getValue();

        // Microseconds zeroed to mirror MOVE ZEROS TO WS-TIMESTAMP-TM-MS6.
        assertThat(posted.getTranOrigTs())
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.000000");
        assertThat(posted.getTranProcTs()).isEqualTo(posted.getTranOrigTs());

        // Sanity: full-balance payment, balance decremented, success acknowledgement.
        assertThat(posted.getTranAmt()).isEqualByComparingTo("100.00");
        assertThat(posted.getTranCardNum()).isEqualTo(CARD_NUMBER);
        assertThat(response.currentBalance()).isEqualByComparingTo("0.00");
        assertThat(response.errorMessage()).contains("Your Transaction ID is");
        verify(accountRepository).save(account);
    }

    @Test
    @DisplayName("empty confirm previews the balance without posting (Confirm to make a bill payment...)")
    void previewWhenNotConfirmed() {
        final Account account = account(new BigDecimal("250.00"));
        when(accountRepository.findById(ACCOUNT_ID_LONG)).thenReturn(Optional.of(account));

        final BillPaymentResponse response = service.pay(new BillPaymentRequest(ACCOUNT_ID, ""));

        assertThat(response.currentBalance()).isEqualByComparingTo("250.00");
        assertThat(response.errorMessage()).isEqualTo("Confirm to make a bill payment...");
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("confirm = N cancels the payment, reading and posting nothing")
    void cancelWhenConfirmIsNo() {
        final BillPaymentResponse response = service.pay(new BillPaymentRequest(ACCOUNT_ID, "N"));

        assertThat(response.accountId()).isNull();
        assertThat(response.errorMessage()).isNull();
        verify(accountRepository, never()).findById(anyLong());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("zero balance is rejected with the COBOL 'nothing to pay' message")
    void nothingToPayWhenBalanceZero() {
        final Account account = account(new BigDecimal("0.00"));
        when(accountRepository.findById(ACCOUNT_ID_LONG)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.pay(new BillPaymentRequest(ACCOUNT_ID, "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("You have nothing to pay...");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("blank account id is rejected before any read")
    void emptyAccountIdRejected() {
        assertThatThrownBy(() -> service.pay(new BillPaymentRequest("", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Acct ID can NOT be empty...");
        verify(accountRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("invalid confirm value is rejected with the COBOL Y/N message")
    void invalidConfirmRejected() {
        assertThatThrownBy(() -> service.pay(new BillPaymentRequest(ACCOUNT_ID, "X")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Invalid value. Valid values are (Y/N)...");
        verify(accountRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("missing account surfaces the COBOL 'Account ID NOT found' message")
    void accountNotFound() {
        when(accountRepository.findById(ACCOUNT_ID_LONG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pay(new BillPaymentRequest(ACCOUNT_ID, "Y")))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Account ID NOT found...");
        verify(transactionRepository, never()).save(any());
    }

    private static Account account(final BigDecimal balance) {
        final Account account = new Account();
        account.setAcctId(ACCOUNT_ID_LONG);
        account.setAcctCurrBal(balance);
        return account;
    }

    private static CardCrossReference xref(final String cardNumber) {
        final CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum(cardNumber);
        xref.setXrefAcctId(ACCOUNT_ID_LONG);
        return xref;
    }
}
