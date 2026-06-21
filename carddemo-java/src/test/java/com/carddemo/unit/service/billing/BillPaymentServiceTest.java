package com.carddemo.unit.service.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.carddemo.service.shared.TransactionIdAllocator;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BillPaymentService}, the Java re-platforming of COBOL
 * program COBIL00C (source commit 27d6c6f).
 */
@ExtendWith(MockitoExtension.class)
class BillPaymentServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private TransactionIdAllocator transactionIdAllocator;

    @InjectMocks
    private BillPaymentService billPaymentService;

    private static Account account(long id, String balance) {
        Account account = new Account();
        account.setAcctId(id);
        account.setAcctCurrBal(new BigDecimal(balance));
        return account;
    }

    private static CardCrossReference xref(String cardNumber, long acctId) {
        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum(cardNumber);
        xref.setXrefAcctId(acctId);
        return xref;
    }

    @Test
    @DisplayName("confirm 'Y' posts the full balance, zeroes the account, saves payment with max+1 id")
    void payConfirmedYPostsFullBalanceAndZeroesAccount() {
        when(accountRepository.findById(12345L)).thenReturn(Optional.of(account(12345L, "100.00")));
        when(cardCrossReferenceRepository.findByXrefAcctId(12345L))
                .thenReturn(List.of(xref("1234567890123456", 12345L)));
        when(transactionIdAllocator.allocateNextTransactionId()).thenReturn("0000000000000124");

        BillPaymentResponse response = billPaymentService.pay(new BillPaymentRequest("12345", "Y"));

        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txnCaptor.capture());
        Transaction saved = txnCaptor.getValue();
        assertThat(saved.getTranId()).isEqualTo("0000000000000124");
        assertThat(saved.getTranTypeCd()).isEqualTo("02");
        assertThat(saved.getTranCatCd()).isEqualTo(2);
        assertThat(saved.getTranSource()).isEqualTo("POS TERM");
        assertThat(saved.getTranDesc()).isEqualTo("BILL PAYMENT - ONLINE");
        assertThat(saved.getTranAmt()).isEqualByComparingTo("100.00");
        assertThat(saved.getTranAmt().scale()).isEqualTo(2);
        assertThat(saved.getTranMerchantId()).isEqualTo(999999999L);
        assertThat(saved.getTranMerchantName()).isEqualTo("BILL PAYMENT");
        assertThat(saved.getTranMerchantCity()).isEqualTo("N/A");
        assertThat(saved.getTranMerchantZip()).isEqualTo("N/A");
        assertThat(saved.getTranCardNum()).isEqualTo("1234567890123456");
        assertThat(saved.getTranOrigTs()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}");
        assertThat(saved.getTranProcTs()).isEqualTo(saved.getTranOrigTs());

        ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(acctCaptor.capture());
        assertThat(acctCaptor.getValue().getAcctCurrBal()).isEqualByComparingTo("0.00");
        assertThat(acctCaptor.getValue().getAcctCurrBal().scale()).isEqualTo(2);

        assertThat(response.accountId()).isEqualTo("00000012345");
        assertThat(response.currentBalance()).isEqualByComparingTo("0.00");
        assertThat(response.currentBalance().scale()).isEqualTo(2);
        assertThat(response.confirm()).isEqualTo("Y");
        assertThat(response.errorMessage())
                .isEqualTo("Payment successful.  Your Transaction ID is 0000000000000124.");

        InOrder inOrder = Mockito.inOrder(accountRepository, cardCrossReferenceRepository,
                transactionIdAllocator, transactionRepository);
        inOrder.verify(accountRepository).findById(12345L);
        inOrder.verify(cardCrossReferenceRepository).findByXrefAcctId(12345L);
        inOrder.verify(transactionIdAllocator).allocateNextTransactionId();
        inOrder.verify(transactionRepository).save(any(Transaction.class));
        inOrder.verify(accountRepository).save(any(Account.class));
    }

    @Test
    @DisplayName("first transaction (null max id) generates id 0000000000000001")
    void payFirstTransactionGeneratesIdOne() {
        when(accountRepository.findById(12345L)).thenReturn(Optional.of(account(12345L, "50.00")));
        when(cardCrossReferenceRepository.findByXrefAcctId(12345L))
                .thenReturn(List.of(xref("1111222233334444", 12345L)));
        when(transactionIdAllocator.allocateNextTransactionId()).thenReturn("0000000000000001");

        BillPaymentResponse response = billPaymentService.pay(new BillPaymentRequest("12345", "Y"));

        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txnCaptor.capture());
        assertThat(txnCaptor.getValue().getTranId()).isEqualTo("0000000000000001");
        assertThat(response.errorMessage())
                .isEqualTo("Payment successful.  Your Transaction ID is 0000000000000001.");
    }

    @Test
    @DisplayName("confirm lowercase 'y' also posts the payment")
    void payConfirmedLowercaseYPostsPayment() {
        when(accountRepository.findById(12345L)).thenReturn(Optional.of(account(12345L, "10.00")));
        when(cardCrossReferenceRepository.findByXrefAcctId(12345L))
                .thenReturn(List.of(xref("1234567890123456", 12345L)));
        when(transactionIdAllocator.allocateNextTransactionId()).thenReturn("0000000000000006");

        BillPaymentResponse response = billPaymentService.pay(new BillPaymentRequest("12345", "y"));

        verify(transactionRepository).save(any(Transaction.class));
        verify(accountRepository).save(any(Account.class));
        assertThat(response.confirm()).isEqualTo("y");
        assertThat(response.errorMessage())
                .isEqualTo("Payment successful.  Your Transaction ID is 0000000000000006.");
    }

    @Test
    @DisplayName("confirm 'N' makes no change and posts nothing")
    void payConfirmedNReturnsAllNullAndNoInteractions() {
        BillPaymentResponse response = billPaymentService.pay(new BillPaymentRequest("12345", "N"));

        assertThat(response.accountId()).isNull();
        assertThat(response.currentBalance()).isNull();
        assertThat(response.confirm()).isNull();
        assertThat(response.errorMessage()).isNull();
        verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
    }

    @Test
    @DisplayName("confirm lowercase 'n' makes no change and posts nothing")
    void payConfirmedLowercaseNReturnsAllNull() {
        BillPaymentResponse response = billPaymentService.pay(new BillPaymentRequest("12345", "n"));

        assertThat(response.accountId()).isNull();
        assertThat(response.errorMessage()).isNull();
        verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
    }

    @Test
    @DisplayName("blank confirm returns a preview (balance shown) without posting")
    void payBlankConfirmReturnsPreviewWithoutPosting() {
        when(accountRepository.findById(12345L)).thenReturn(Optional.of(account(12345L, "250.75")));

        BillPaymentResponse response = billPaymentService.pay(new BillPaymentRequest("12345", ""));

        assertThat(response.accountId()).isEqualTo("00000012345");
        assertThat(response.currentBalance()).isEqualByComparingTo("250.75");
        assertThat(response.currentBalance().scale()).isEqualTo(2);
        assertThat(response.confirm()).isEqualTo("");
        assertThat(response.errorMessage()).isEqualTo("Confirm to make a bill payment...");
        verify(accountRepository).findById(12345L);
        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
        verifyNoInteractions(cardCrossReferenceRepository);
    }

    @Test
    @DisplayName("null confirm behaves like blank confirm (preview)")
    void payNullConfirmReturnsPreviewWithoutPosting() {
        when(accountRepository.findById(12345L)).thenReturn(Optional.of(account(12345L, "99.99")));

        BillPaymentResponse response = billPaymentService.pay(new BillPaymentRequest("12345", null));

        assertThat(response.currentBalance()).isEqualByComparingTo("99.99");
        assertThat(response.errorMessage()).isEqualTo("Confirm to make a bill payment...");
        verify(accountRepository).findById(12345L);
        verify(transactionRepository, never()).save(any(Transaction.class));
        verifyNoInteractions(cardCrossReferenceRepository);
    }

    @Test
    @DisplayName("empty account id throws ValidationException first, before any repository call")
    void payEmptyAccountIdThrowsValidationExceptionFirst() {
        assertThatThrownBy(() -> billPaymentService.pay(new BillPaymentRequest("", "Y")))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("accountId"))
                .hasMessage("Acct ID can NOT be empty...");
        verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
    }

    @Test
    @DisplayName("null account id throws ValidationException")
    void payNullAccountIdThrowsValidationException() {
        assertThatThrownBy(() -> billPaymentService.pay(new BillPaymentRequest(null, "Y")))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("accountId"))
                .hasMessage("Acct ID can NOT be empty...");
        verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
    }

    @Test
    @DisplayName("invalid confirm value throws ValidationException(confirm) before any repository call")
    void payInvalidConfirmThrowsValidationException() {
        assertThatThrownBy(() -> billPaymentService.pay(new BillPaymentRequest("12345", "X")))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("confirm"))
                .hasMessage("Invalid value. Valid values are (Y/N)...");
        verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
    }

    @Test
    @DisplayName("account not found maps to RecordNotFoundException (FILE STATUS 23)")
    void payAccountNotFoundThrowsRecordNotFoundException() {
        when(accountRepository.findById(99999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> billPaymentService.pay(new BillPaymentRequest("99999", "Y")))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Account ID NOT found...");
        verify(accountRepository).findById(99999L);
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(cardCrossReferenceRepository);
    }

    @Test
    @DisplayName("non-numeric account id maps to RecordNotFoundException with NumberFormatException cause")
    void payNonNumericAccountIdThrowsRecordNotFoundException() {
        assertThatThrownBy(() -> billPaymentService.pay(new BillPaymentRequest("ABCDE", "Y")))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Account ID NOT found...")
                .hasCauseInstanceOf(NumberFormatException.class);
        verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
    }

    @Test
    @DisplayName("zero balance throws 'nothing to pay' after the account read, before any save")
    void payZeroBalanceThrowsNothingToPay() {
        when(accountRepository.findById(12345L)).thenReturn(Optional.of(account(12345L, "0.00")));

        assertThatThrownBy(() -> billPaymentService.pay(new BillPaymentRequest("12345", "Y")))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("accountId"))
                .hasMessage("You have nothing to pay...");
        verify(accountRepository).findById(12345L);
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(cardCrossReferenceRepository);
    }

    @Test
    @DisplayName("negative balance also throws 'nothing to pay'")
    void payNegativeBalanceThrowsNothingToPay() {
        when(accountRepository.findById(12345L)).thenReturn(Optional.of(account(12345L, "-5.00")));

        assertThatThrownBy(() -> billPaymentService.pay(new BillPaymentRequest("12345", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("You have nothing to pay...");
        verify(accountRepository).findById(12345L);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    @DisplayName("confirmed payment with no cross-reference maps to RecordNotFoundException")
    void payConfirmedYWithNoCrossReferenceThrowsRecordNotFound() {
        when(accountRepository.findById(12345L)).thenReturn(Optional.of(account(12345L, "100.00")));
        when(cardCrossReferenceRepository.findByXrefAcctId(12345L)).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> billPaymentService.pay(new BillPaymentRequest("12345", "Y")))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Account ID NOT found...");
        verify(accountRepository).findById(12345L);
        verify(cardCrossReferenceRepository).findByXrefAcctId(12345L);
        verify(transactionIdAllocator, never()).allocateNextTransactionId();
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }
}
