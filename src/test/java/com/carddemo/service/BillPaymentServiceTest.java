package com.carddemo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
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
 * Pure, fast Mockito unit test for {@link BillPaymentService}, the online
 * full-balance <strong>Bill Payment</strong> service (transaction {@code CB00})
 * migrated from the COBOL/CICS program {@code COBIL00C}
 * ({@code app/cbl/COBIL00C.cbl}, frozen reference SHA {@code 27d6c6f} &mdash;
 * read-only, never copied into this repository).
 *
 * <p>The three collaborators ({@link AccountRepository},
 * {@link TransactionRepository}, {@link CrossReferenceService}) are Mockito
 * mocks and the service is built by constructor injection, so <em>no</em> Spring
 * context, database, Testcontainers, Docker, or live AWS is involved. Every
 * branch of {@link BillPaymentService#payBill(BillPaymentRequest)} is exercised
 * &mdash; the account-id edit, the {@code READ-ACCTDAT} not-found branch, the
 * nothing-to-pay guard, the confirmation gate (prompt vs. execute), and the
 * posting path that writes the transaction and rewrites the balance to zero
 * &mdash; feeding the JaCoCo line-coverage gate (Gate&nbsp;8) and asserting both
 * the external message contract (Gate&nbsp;5) and decimal fidelity.</p>
 *
 * <h2>Parity anchors ({@code COBIL00C} {@code PROCESS-ENTER-KEY}, source L197&ndash;L244)</h2>
 * <ul>
 *   <li>Nothing-to-pay guard (L197&ndash;L206): {@code 'You have nothing to pay...'}.</li>
 *   <li>Confirmation gate (L237): {@code 'Confirm to make a bill payment...'} with no persistence.</li>
 *   <li>Posted payment record (L220&ndash;L229): {@code TRAN-TYPE-CD='02'},
 *       {@code TRAN-CAT-CD=2}, {@code TRAN-SOURCE='POS TERM'},
 *       {@code TRAN-DESC='BILL PAYMENT - ONLINE'}, {@code TRAN-MERCHANT-ID=999999999},
 *       {@code TRAN-MERCHANT-NAME='BILL PAYMENT'}, city/zip {@code 'N/A'}.</li>
 *   <li>Balance zeroing (L234): {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}.</li>
 * </ul>
 *
 * <p><strong>Money rule.</strong> Every monetary assertion compares with
 * {@link BigDecimal#compareTo(BigDecimal)} and additionally pins the
 * {@link BigDecimal#scale() scale} to {@value #MONEY_SCALE}; floating-point types
 * are never used for money (AAP&nbsp;&sect;0.8.2, Gate&nbsp;2).</p>
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

    // ---- Fixtures -------------------------------------------------------

    /** Eleven-digit account id string accepted by the DTO {@code \d{1,11}} pattern. */
    private static final String ACCOUNT_ID_STR = "12345678901";

    /** The same account id parsed to the primitive {@code long} the service keys on. */
    private static final long ACCOUNT_ID = 12345678901L;

    /** Synthetic 16-digit card number (never a real PAN) returned by the xref stub. */
    private static final String CARD_NUM = "1234567890123456";

    /** Synthetic 16-digit, zero-padded transaction id returned by the id-generator stub. */
    private static final String TRAN_ID = "0000000000000123";

    /** Positive, scale-2 balance used by the confirm-gate and success paths. */
    private static final BigDecimal POSITIVE_BALANCE = new BigDecimal("150.75");

    /** Mandated monetary scale, matching the COBOL {@code PIC S9(n)V99} pictures. */
    private static final int MONEY_SCALE = 2;

    // Verbatim COBIL00C / service messages (SHA 27d6c6f).
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";
    private static final String MSG_CONFIRM = "Confirm to make a bill payment...";
    private static final String MSG_ACCT_NOT_FOUND = "Account ID NOT found...";
    private static final String MSG_INVALID_ACCT =
            "Account number must be a non zero 11 digit number";

    // Posted-payment constants (COBIL00C PROCESS-ENTER-KEY, source L220-L229).
    private static final String TRAN_TYPE_CD = "02";
    private static final int TRAN_CAT_CD = 2;
    private static final String TRAN_SOURCE = "POS TERM";
    private static final String TRAN_DESC = "BILL PAYMENT - ONLINE";
    private static final long TRAN_MERCHANT_ID = 999_999_999L;
    private static final String TRAN_MERCHANT_NAME = "BILL PAYMENT";
    private static final String TRAN_MERCHANT_NA = "N/A";

    // ---- Helpers --------------------------------------------------------

    /**
     * Builds an {@link Account} keyed on {@link #ACCOUNT_ID} with the supplied
     * current balance (which may be {@code null} to exercise the null-balance
     * guard).
     */
    private static Account accountWithBalance(BigDecimal balance) {
        Account account = new Account();
        account.setAcctId(ACCOUNT_ID);
        account.setAcctCurrBal(balance);
        return account;
    }

    /** Builds a {@link BillPaymentRequest} for {@link #ACCOUNT_ID_STR} with the given confirm flag. */
    private static BillPaymentRequest request(Boolean confirm) {
        return new BillPaymentRequest(ACCOUNT_ID_STR, confirm);
    }

    /**
     * Asserts a monetary value equals {@code expected} numerically (via
     * {@link BigDecimal#compareTo(BigDecimal)}, so trailing-zero scale differences
     * do not matter) and is held at the mandated scale of {@value #MONEY_SCALE}.
     */
    private static void assertMoney(BigDecimal expected, BigDecimal actual) {
        assertNotNull(actual, "monetary value must not be null");
        assertEquals(0, actual.compareTo(expected),
                "expected " + expected + " but was " + actual);
        assertEquals(MONEY_SCALE, actual.scale(), "monetary scale must be " + MONEY_SCALE);
    }

    // ---- Account-id edit (parseAndValidateAccountId, COBIL00C L159-L164) --

    @Test
    @DisplayName("null request → ValidationException; nothing touched")
    void payBill_nullRequest_throwsValidation() {
        ValidationException ex =
                assertThrows(ValidationException.class, () -> service.payBill(null));

        assertEquals(MSG_INVALID_ACCT, ex.getMessage());
        verifyNoInteractions(accountRepository, transactionRepository, crossReferenceService);
    }

    @Test
    @DisplayName("blank account id → ValidationException; nothing touched")
    void payBill_blankAccountId_throwsValidation() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.payBill(new BillPaymentRequest("   ", true)));

        assertEquals(MSG_INVALID_ACCT, ex.getMessage());
        verifyNoInteractions(accountRepository, transactionRepository, crossReferenceService);
    }

    @Test
    @DisplayName("non-numeric account id → ValidationException; nothing touched")
    void payBill_nonNumericAccountId_throwsValidation() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.payBill(new BillPaymentRequest("ABCDEFGHIJK", true)));

        assertEquals(MSG_INVALID_ACCT, ex.getMessage());
        verifyNoInteractions(accountRepository, transactionRepository, crossReferenceService);
    }

    @Test
    @DisplayName("zero (non-positive) account id → ValidationException; nothing touched")
    void payBill_zeroAccountId_throwsValidation() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.payBill(new BillPaymentRequest("0", true)));

        assertEquals(MSG_INVALID_ACCT, ex.getMessage());
        verifyNoInteractions(accountRepository, transactionRepository, crossReferenceService);
    }

    // ---- Account lookup (READ-ACCTDAT-FILE DFHRESP(NOTFND), L343-L372) ----

    @Test
    @DisplayName("account not found → ResourceNotFoundException; no writes, no xref")
    void payBill_accountNotFound_throwsResourceNotFound() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.payBill(request(true)));

        assertEquals(MSG_ACCT_NOT_FOUND, ex.getMessage());
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(crossReferenceService);
    }

    // ---- Nothing-to-pay guard (L197-L206) --------------------------------

    @Test
    @DisplayName("zero balance → ValidationException 'You have nothing to pay...'; no writes")
    void payBill_zeroBalance_throwsValidation() {
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(accountWithBalance(new BigDecimal("0.00"))));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.payBill(request(true)));

        assertEquals(MSG_NOTHING_TO_PAY, ex.getMessage());
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(crossReferenceService);
    }

    @Test
    @DisplayName("negative balance → ValidationException 'You have nothing to pay...'; no writes")
    void payBill_negativeBalance_throwsValidation() {
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(accountWithBalance(new BigDecimal("-25.00"))));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.payBill(request(true)));

        assertEquals(MSG_NOTHING_TO_PAY, ex.getMessage());
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(crossReferenceService);
    }

    @Test
    @DisplayName("null balance → ValidationException 'You have nothing to pay...'; no writes")
    void payBill_nullBalance_throwsValidation() {
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(accountWithBalance(null)));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.payBill(request(true)));

        assertEquals(MSG_NOTHING_TO_PAY, ex.getMessage());
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(crossReferenceService);
    }

    // ---- Confirmation gate (L208-L240): prompt, no persistence -----------

    @Test
    @DisplayName("confirm=null → confirmation prompt, null confirmation number, NO writes")
    void payBill_confirmNull_returnsPromptNoWrite() {
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(accountWithBalance(POSITIVE_BALANCE)));

        BillPaymentResponse response = service.payBill(request(null));

        assertEquals(MSG_CONFIRM, response.message());
        assertNull(response.confirmationNumber());
        assertEquals(ACCOUNT_ID_STR, response.accountId());
        // Unconfirmed: current balance and payment amount are echoed; the new
        // balance previews the PROJECTED post-payment balance (full payoff -> 0.00),
        // shown for review without any persistence (F-BILLPAY-PREVIEW).
        assertMoney(POSITIVE_BALANCE, response.currentBalance());
        assertMoney(POSITIVE_BALANCE, response.paymentAmount());
        assertMoney(new BigDecimal("0.00"), response.newBalance());
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(crossReferenceService);
    }

    @Test
    @DisplayName("confirm=false → confirmation prompt, null confirmation number, NO writes")
    void payBill_confirmFalse_returnsPromptNoWrite() {
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(accountWithBalance(POSITIVE_BALANCE)));

        BillPaymentResponse response = service.payBill(request(false));

        assertEquals(MSG_CONFIRM, response.message());
        assertNull(response.confirmationNumber());
        assertMoney(POSITIVE_BALANCE, response.currentBalance());
        // New balance previews the PROJECTED post-payment balance (F-BILLPAY-PREVIEW).
        assertMoney(new BigDecimal("0.00"), response.newBalance());
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(crossReferenceService);
    }

    // ---- Payment execution (L210-L235): post txn + zero the balance ------

    @Test
    @DisplayName("confirmed payment posts the bill-payment txn, zeroes the balance, saves both")
    void payBill_success_postsPaymentAndZeroesBalance() {
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(accountWithBalance(POSITIVE_BALANCE)));
        when(crossReferenceService.resolvePrimaryCardNumber(ACCOUNT_ID)).thenReturn(CARD_NUM);
        when(crossReferenceService.generateNextTransactionId()).thenReturn(TRAN_ID);

        BillPaymentResponse response = service.payBill(request(true));

        // -- Transaction posted with the verbatim COBIL00C constant fields (L219-L232).
        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txnCaptor.capture());
        Transaction saved = txnCaptor.getValue();
        assertEquals(TRAN_ID, saved.getTranId());
        assertEquals(TRAN_TYPE_CD, saved.getTranTypeCd());
        assertEquals(Integer.valueOf(TRAN_CAT_CD), saved.getTranCatCd());
        assertEquals(TRAN_SOURCE, saved.getTranSource());
        assertEquals(TRAN_DESC, saved.getTranDesc());
        assertEquals(Long.valueOf(TRAN_MERCHANT_ID), saved.getTranMerchantId());
        assertEquals(TRAN_MERCHANT_NAME, saved.getTranMerchantName());
        assertEquals(TRAN_MERCHANT_NA, saved.getTranMerchantCity());
        assertEquals(TRAN_MERCHANT_NA, saved.getTranMerchantZip());
        assertEquals(CARD_NUM, saved.getTranCardNum());
        // TRAN-AMT == full pre-payment balance (money rule: compareTo + scale()==2).
        assertMoney(POSITIVE_BALANCE, saved.getTranAmt());
        // TRAN-ORIG-TS == TRAN-PROC-TS, both from one timestamp (L230-L232).
        assertNotNull(saved.getTranOrigTs());
        assertEquals(saved.getTranOrigTs(), saved.getTranProcTs());

        // -- Account balance rewritten to 0.00 (COMPUTE ACCT-CURR-BAL - TRAN-AMT, L234).
        ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(acctCaptor.capture());
        Account savedAcct = acctCaptor.getValue();
        assertEquals(0, savedAcct.getAcctCurrBal().compareTo(BigDecimal.ZERO));
        assertEquals(MONEY_SCALE, savedAcct.getAcctCurrBal().scale());

        // -- Response contract: pre balance, full payment, zeroed new balance.
        assertMoney(POSITIVE_BALANCE, response.currentBalance());
        assertMoney(POSITIVE_BALANCE, response.paymentAmount());
        assertEquals(0, response.newBalance().compareTo(BigDecimal.ZERO));
        assertEquals(MONEY_SCALE, response.newBalance().scale());
        assertEquals(TRAN_ID, response.confirmationNumber());
        assertNotNull(response.message());
        assertTrue(response.message().contains(TRAN_ID),
                "success message must carry the generated transaction id");
    }

    @Test
    @DisplayName("confirmed payment stamps the generated transaction id onto the saved txn")
    void payBill_success_generatesTransactionId() {
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(accountWithBalance(POSITIVE_BALANCE)));
        when(crossReferenceService.resolvePrimaryCardNumber(ACCOUNT_ID)).thenReturn(CARD_NUM);
        when(crossReferenceService.generateNextTransactionId()).thenReturn(TRAN_ID);

        BillPaymentResponse response = service.payBill(request(true));

        verify(crossReferenceService).generateNextTransactionId();
        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txnCaptor.capture());
        assertEquals(TRAN_ID, txnCaptor.getValue().getTranId());
        assertEquals(TRAN_ID, response.confirmationNumber());
    }
}
