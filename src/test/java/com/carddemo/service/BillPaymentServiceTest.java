package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.carddemo.dto.BillPaymentRequest;
import com.carddemo.dto.BillPaymentResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionRepository;

/**
 * Pure, fast unit test for {@link BillPaymentService}, the full-balance bill-pay
 * service (online transaction {@code CB00}) migrated from the COBOL/CICS program
 * {@code COBIL00C} ({@code app/cbl/COBIL00C.cbl}, frozen reference SHA
 * {@code 27d6c6f} &mdash; read-only, not copied into this repository).
 *
 * <p>Collaborators ({@link AccountRepository}, {@link TransactionRepository},
 * {@link CrossReferenceService}) are Mockito mocks; the service is constructed by
 * injection, so no Spring context, database, Testcontainers, Docker, or live AWS
 * is involved. Every branch of {@link BillPaymentService#payBill(BillPaymentRequest)}
 * is exercised &mdash; the account-id edit, the {@code READ-ACCTDAT} not-found
 * branch, the nothing-to-pay guard, the confirmation gate (prompt vs. execute),
 * and the posting path that writes the transaction and rewrites the balance to
 * zero &mdash; feeding the JaCoCo line-coverage gate (Gate&nbsp;8) and asserting
 * decimal fidelity (scale&nbsp;2) and the external message contract (Gate&nbsp;5).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillPaymentService — COBOL COBIL00C / CB00 bill payment (SHA 27d6c6f)")
class BillPaymentServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CrossReferenceService crossReferenceService;

    @InjectMocks
    private BillPaymentService service;

    private static final String ACCT_STR = "12345678901";
    private static final long ACCT_ID = 12345678901L;

    private static BillPaymentRequest req(String accountId, Boolean confirm) {
        return new BillPaymentRequest(accountId, confirm);
    }

    @Nested
    @DisplayName("Account-id edit — null/blank/non-numeric/non-positive")
    class IdEdit {

        @Test
        @DisplayName("null request → ValidationException")
        void nullRequest() {
            assertThatThrownBy(() -> service.payBill(null))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("null account id → ValidationException")
        void nullAccountId() {
            assertThatThrownBy(() -> service.payBill(req(null, true)))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("blank account id → ValidationException")
        void blankAccountId() {
            assertThatThrownBy(() -> service.payBill(req("   ", true)))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("non-numeric account id → ValidationException")
        void nonNumericAccountId() {
            assertThatThrownBy(() -> service.payBill(req("NOTANUMBER", true)))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("zero account id → ValidationException")
        void zeroAccountId() {
            assertThatThrownBy(() -> service.payBill(req("0", true)))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Test
    @DisplayName("account not found → ResourceNotFoundException; nothing persisted")
    void accountNotFound() {
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.payBill(req(ACCT_STR, true)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(transactionRepository, never()).save(any());
    }

    @Nested
    @DisplayName("Nothing-to-pay guard — balance must be strictly positive")
    class NothingToPay {

        @Test
        @DisplayName("null balance → ValidationException")
        void nullBalance() {
            Account account = new Account();
            account.setAcctCurrBal(null);
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

            assertThatThrownBy(() -> service.payBill(req(ACCT_STR, true)))
                    .isInstanceOf(ValidationException.class);
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("zero balance → ValidationException")
        void zeroBalance() {
            Account account = new Account();
            account.setAcctCurrBal(new BigDecimal("0.00"));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

            assertThatThrownBy(() -> service.payBill(req(ACCT_STR, true)))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("negative balance → ValidationException")
        void negativeBalance() {
            Account account = new Account();
            account.setAcctCurrBal(new BigDecimal("-5.00"));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

            assertThatThrownBy(() -> service.payBill(req(ACCT_STR, true)))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    @DisplayName("Confirmation gate — prompt without confirmation, no persistence")
    class ConfirmationGate {

        @Test
        @DisplayName("confirm=null → prompt response, no confirmation number, nothing saved")
        void confirmNull() {
            Account account = new Account();
            account.setAcctCurrBal(new BigDecimal("125.00"));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

            BillPaymentResponse response = service.payBill(req(ACCT_STR, null));

            assertThat(response.confirmationNumber()).isNull();
            assertThat(response.currentBalance()).isEqualByComparingTo("125.00");
            assertThat(response.newBalance()).isEqualByComparingTo("125.00");
            assertThat(response.message()).isNotBlank();
            verify(transactionRepository, never()).save(any());
            verify(accountRepository, never()).save(any());
        }

        @Test
        @DisplayName("confirm=false → prompt response, nothing saved")
        void confirmFalse() {
            Account account = new Account();
            account.setAcctCurrBal(new BigDecimal("125.00"));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

            BillPaymentResponse response = service.payBill(req(ACCT_STR, false));

            assertThat(response.confirmationNumber()).isNull();
            verify(transactionRepository, never()).save(any());
            verify(accountRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("confirmed payment posts full balance, zeroes account, returns success message")
    void confirmedPaymentPostsAndZeroesBalance() {
        Account account = new Account();
        account.setAcctCurrBal(new BigDecimal("250.00"));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(crossReferenceService.resolvePrimaryCardNumber(ACCT_ID)).thenReturn("4111111111111111");
        when(crossReferenceService.generateNextTransactionId()).thenReturn("0000000000000123");

        BillPaymentResponse response = service.payBill(req(ACCT_STR, true));

        // Response contract.
        assertThat(response.currentBalance()).isEqualByComparingTo("250.00");
        assertThat(response.paymentAmount()).isEqualByComparingTo("250.00");
        assertThat(response.paymentAmount().scale()).isEqualTo(2);
        assertThat(response.newBalance()).isEqualByComparingTo("0.00");
        assertThat(response.confirmationNumber()).isEqualTo("0000000000000123");
        assertThat(response.message()).contains("0000000000000123");

        // Account balance rewritten to zero (COMPUTE ACCT-CURR-BAL - TRAN-AMT).
        assertThat(account.getAcctCurrBal()).isEqualByComparingTo("0.00");
        verify(accountRepository).save(account);

        // Transaction written with the migrated constant fields (COBIL00C).
        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txnCaptor.capture());
        Transaction saved = txnCaptor.getValue();
        assertThat(saved.getTranId()).isEqualTo("0000000000000123");
        assertThat(saved.getTranAmt()).isEqualByComparingTo("250.00");
        assertThat(saved.getTranAmt().scale()).isEqualTo(2);
        assertThat(saved.getTranCardNum()).isEqualTo("4111111111111111");
        assertThat(saved.getTranTypeCd()).isEqualTo("02");
        assertThat(saved.getTranSource()).isEqualTo("POS TERM");
        assertThat(saved.getTranDesc()).isEqualTo("BILL PAYMENT - ONLINE");
    }
}
