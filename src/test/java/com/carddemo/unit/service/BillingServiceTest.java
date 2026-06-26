/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.unit.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.carddemo.dto.BillingDto;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.exception.BusinessRuleException;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.BillingService;
import com.carddemo.service.DateValidationService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link BillingService}, the Java
 * realization of the CICS pseudo-conversational program {@code COBIL00C} (bill
 * payment, transaction {@code CB00}) at source commit {@code 27d6c6f}. The
 * service backs {@code POST /api/billing/pay}: it pays an account balance in
 * full by writing a single payment {@link Transaction} and decrementing the
 * {@link Account} current balance to zero, both inside one
 * {@code @Transactional(rollbackFor = Exception.class)} unit of work that
 * reproduces the legacy CICS implicit commit (AAP&nbsp;&sect;0.4.1.1).
 *
 * <p><strong>Framework-free isolation.</strong> The suite bootstraps
 * <strong>no</strong> Spring {@code ApplicationContext}: there is no
 * {@code @SpringBootTest}, no {@code MockMvc}, no Testcontainers, and no
 * database, AWS, or network access. The four collaborators
 * ({@link AccountRepository}, {@link CardXrefRepository},
 * {@link TransactionRepository}, {@link DateValidationService}) are Mockito
 * {@code @Mock}s injected into the {@code @InjectMocks} service through
 * constructor injection. {@link MockitoExtension} runs with its default
 * {@code STRICT_STUBS} strictness, so each test stubs only the collaborator
 * calls its control-flow path actually reaches; the validation tests assert no
 * repository interaction occurs at all.</p>
 *
 * <p><strong>Byte-exact parity (Gate&nbsp;1 / Gate&nbsp;4).</strong> Every
 * asserted message is verbatim from the compiled service, which preserves the
 * {@code COBIL00C} working-storage literals: {@code "Acct ID can NOT be
 * empty..."}, {@code "Confirm to make a bill payment..."}, {@code "Invalid
 * value. Valid values are (Y/N)..."}, {@code "Account ID NOT found..."},
 * {@code "You have nothing to pay..."} and {@code "Tran ID already exist..."}.
 * The headline success test captures the persisted {@link Transaction} with an
 * {@link ArgumentCaptor} and pins every hardcoded field the legacy
 * {@code PROCESS-ENTER-KEY} paragraph writes (type {@code '02'}, category
 * {@code 2}, source {@code "POS TERM"}, description {@code "BILL PAYMENT -
 * ONLINE"}, merchant id {@code 999999999}, merchant name {@code "BILL
 * PAYMENT"}, city/zip {@code "N/A"}). All monetary assertions use
 * {@code BigDecimal} value comparison ({@code compareTo} semantics via
 * {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo});
 * {@code double}/{@code float} are never used.</p>
 *
 * <p><strong>Documented COBOL&rarr;Java deviation.</strong> {@code COBIL00C}
 * also builds the screen literals {@code "Payment successful. "} and
 * {@code " Your Transaction ID is "} into {@code WS-MESSAGE}. The compiled
 * {@link BillingDto.PayResponse} models only the three business fields
 * ({@code accountId}, {@code currentBalance}, {@code confirm}) and carries
 * <em>no</em> message field, so &mdash; "compiled source wins" &mdash; this
 * suite asserts the success outcome through the returned balance and
 * confirmation flag rather than an unreachable success string.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingService - COBIL00C (CB00) POST /api/billing/pay @ 27d6c6f")
class BillingServiceTest {

    /** Eleven-character account identifier input ({@code ACTIDIN PIC X(11)}). */
    private static final String ACCOUNT_ID_TEXT = "00000000001";

    /** Numeric account key parsed from {@link #ACCOUNT_ID_TEXT} (used for repository lookups). */
    private static final long ACCOUNT_ID = 1L;

    /** Owning customer identifier for the cross-reference fixture. */
    private static final long CUSTOMER_ID = 100000001L;

    /** Sixteen-digit card number resolved through the {@code CXACAIX} alternate index. */
    private static final String CARD_NUMBER = "0500000000000001";

    /** Fixed twenty-six-character processing timestamp ({@code uuuu-MM-dd HH:mm:ss.SSSSSS}). */
    private static final String FIXED_TIMESTAMP = "2024-01-15 10:30:45.123456";

    /** First auto-generated identifier when the {@code TRANSACT} table is empty. */
    private static final String FIRST_TRAN_ID = "0000000000000001";

    // Byte-exact messages preserved from COBIL00C working storage (Gate 1 / Gate 4).

    /** Verbatim message for an empty account identifier. */
    private static final String MSG_ACCT_EMPTY = "Acct ID can NOT be empty...";

    /** Verbatim message re-prompting for the confirmation flag. */
    private static final String MSG_CONFIRM_REQUIRED = "Confirm to make a bill payment...";

    /** Verbatim message for an out-of-range confirmation flag. */
    private static final String MSG_CONFIRM_INVALID = "Invalid value. Valid values are (Y/N)...";

    /** Verbatim message for a missing account or cross-reference. */
    private static final String MSG_ACCT_NOT_FOUND = "Account ID NOT found...";

    /** Verbatim message for a zero or negative balance. */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /** Verbatim message for a duplicate transaction identifier. */
    private static final String MSG_TRAN_DUPLICATE = "Tran ID already exist...";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private DateValidationService dateValidationService;

    @InjectMocks
    private BillingService service;

    /**
     * Builds an active account fixture keyed on {@link #ACCOUNT_ID} carrying the
     * supplied current balance. A fresh instance is returned per call so no
     * mutable state is shared between tests.
     *
     * @param balance the current balance to seed
     * @return a populated {@link Account}
     */
    private static Account accountWithBalance(BigDecimal balance) {
        Account account = new Account();
        account.setAcctId(ACCOUNT_ID);
        account.setActiveStatus("Y");
        account.setCurrBal(balance);
        account.setVersion(0L);
        return account;
    }

    /**
     * Builds the canonical cross-reference fixture linking {@link #CARD_NUMBER}
     * to {@link #CUSTOMER_ID} and {@link #ACCOUNT_ID}.
     *
     * @return a populated {@link CardXref}
     */
    private static CardXref cardXref() {
        return new CardXref(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID);
    }

    /**
     * Builds an existing posted transaction whose identifier seeds the
     * auto-increment "find last id then add one" step.
     *
     * @param tranId the existing highest {@code TRAN-ID}
     * @return a {@link Transaction} carrying only the identifier
     */
    private static Transaction existingTransaction(String tranId) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        return transaction;
    }

    // -----------------------------------------------------------------
    // Phase 1 - successful payment (PROCESS-ENTER-KEY confirmed path)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("payBill: confirmed payment writes the hardcoded payment txn, zeroes the balance, and saves both atomically")
    void payBillSuccessWritesPaymentAndDecrementsBalance() {
        Account account = accountWithBalance(new BigDecimal("250.00"));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(cardXref()));
        when(transactionRepository.findTopByOrderByTranIdDesc()).thenReturn(Optional.empty());
        when(dateValidationService.currentTimestamp()).thenReturn(FIXED_TIMESTAMP);

        BillingDto.PayResponse response =
                service.payBill(new BillingDto.PayRequest(ACCOUNT_ID_TEXT, "Y"));

        // Capture the persisted payment transaction and pin every hardcoded field.
        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        Transaction saved = transactionCaptor.getValue();

        assertThat(saved.getTranId()).isEqualTo(FIRST_TRAN_ID);
        assertThat(saved.getTransactionType()).isEqualTo(TransactionTypeCode.PAYMENT);
        assertThat(saved.getTransactionType().getCode()).isEqualTo("02");
        assertThat(saved.getTranCatCd()).isEqualTo(2);
        assertThat(saved.getTranSource()).isEqualTo("POS TERM");
        assertThat(saved.getTranDesc()).isEqualTo("BILL PAYMENT - ONLINE");
        assertThat(saved.getMerchantId()).isEqualTo(999999999L);
        assertThat(saved.getMerchantName()).isEqualTo("BILL PAYMENT");
        assertThat(saved.getMerchantCity()).isEqualTo("N/A");
        assertThat(saved.getMerchantZip()).isEqualTo("N/A");
        assertThat(saved.getCardNum()).isEqualTo(CARD_NUMBER);
        assertThat(saved.getOrigTs()).isEqualTo(FIXED_TIMESTAMP);
        assertThat(saved.getProcTs()).isEqualTo(FIXED_TIMESTAMP);
        // TRAN-AMT == full balance paid; compare by value, never via equals/scale.
        assertThat(saved.getTranAmt()).isEqualByComparingTo(new BigDecimal("250.00"));

        // Capture the persisted account and assert the balance was decremented to zero.
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getCurrBal()).isEqualByComparingTo(BigDecimal.ZERO);

        // The returned response echoes the account, the post-payment balance, and 'Y'.
        assertThat(response.accountId()).isEqualTo(ACCOUNT_ID_TEXT);
        assertThat(response.currentBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.confirm()).isEqualTo("Y");
    }

    @Test
    @DisplayName("payBill: lowercase 'y' is honoured (case-insensitive confirm) and still posts the payment")
    void payBillLowercaseYesPostsPayment() {
        Account account = accountWithBalance(new BigDecimal("75.50"));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(cardXref()));
        when(transactionRepository.findTopByOrderByTranIdDesc()).thenReturn(Optional.empty());
        when(dateValidationService.currentTimestamp()).thenReturn(FIXED_TIMESTAMP);

        BillingDto.PayResponse response =
                service.payBill(new BillingDto.PayRequest(ACCOUNT_ID_TEXT, "y"));

        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        assertThat(transactionCaptor.getValue().getTranAmt())
                .isEqualByComparingTo(new BigDecimal("75.50"));
        verify(accountRepository).save(any());
        assertThat(response.currentBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.confirm()).isEqualTo("Y");
    }

    @Test
    @DisplayName("payBill: next id is the highest existing TRAN-ID plus one, zero-padded to 16 digits (%016d)")
    void payBillGeneratesNextSequentialTransactionId() {
        Account account = accountWithBalance(new BigDecimal("10.00"));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(cardXref()));
        when(transactionRepository.findTopByOrderByTranIdDesc())
                .thenReturn(Optional.of(existingTransaction("0000000000000041")));
        when(dateValidationService.currentTimestamp()).thenReturn(FIXED_TIMESTAMP);

        service.payBill(new BillingDto.PayRequest(ACCOUNT_ID_TEXT, "Y"));

        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        assertThat(transactionCaptor.getValue().getTranId()).isEqualTo("0000000000000042");
    }

    @Test
    @DisplayName("payBill: a collision on the generated TRAN-ID raises DuplicateRecordException and writes nothing")
    void payBillDuplicateTransactionIdThrowsAndWritesNothing() {
        Account account = accountWithBalance(new BigDecimal("250.00"));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(cardXref()));
        when(transactionRepository.findTopByOrderByTranIdDesc()).thenReturn(Optional.empty());
        when(transactionRepository.existsById(FIRST_TRAN_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.payBill(new BillingDto.PayRequest(ACCOUNT_ID_TEXT, "Y")))
                .isInstanceOf(DuplicateRecordException.class)
                .hasMessage(MSG_TRAN_DUPLICATE);

        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    // -----------------------------------------------------------------
    // Phase 2 - nothing to pay (ACCT-CURR-BAL <= ZEROS guard)
    // -----------------------------------------------------------------

    @ParameterizedTest(name = "balance \"{0}\" -> BusinessRuleException, no write")
    @ValueSource(strings = {"0.00", "-0.01", "-9999999999.99"})
    @DisplayName("payBill: a non-positive balance raises BusinessRuleException 'You have nothing to pay...' and writes nothing")
    void payBillNonPositiveBalanceThrowsBusinessRule(String balanceText) {
        Account account = accountWithBalance(new BigDecimal(balanceText));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.payBill(new BillingDto.PayRequest(ACCOUNT_ID_TEXT, "Y")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage(MSG_NOTHING_TO_PAY);

        verify(cardXrefRepository, never()).findByXrefAcctId(any());
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    // -----------------------------------------------------------------
    // Phase 3 - input / confirmation gate (edits precede every read)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("payBill: empty account id -> ValidationException 'Acct ID can NOT be empty...' (no repository access)")
    void payBillEmptyAccountIdThrowsValidation() {
        assertThatThrownBy(() -> service.payBill(new BillingDto.PayRequest("", "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_ACCT_EMPTY);

        verifyNoRepositoryWrites();
        verify(accountRepository, never()).findById(any());
    }

    @Test
    @DisplayName("payBill: null account id -> ValidationException 'Acct ID can NOT be empty...' (no repository access)")
    void payBillNullAccountIdThrowsValidation() {
        assertThatThrownBy(() -> service.payBill(new BillingDto.PayRequest(null, "Y")))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_ACCT_EMPTY);

        verifyNoRepositoryWrites();
        verify(accountRepository, never()).findById(any());
    }

    @Test
    @DisplayName("payBill: blank confirmation flag -> ValidationException 'Confirm to make a bill payment...' (no repository access)")
    void payBillBlankConfirmThrowsValidation() {
        assertThatThrownBy(() -> service.payBill(new BillingDto.PayRequest(ACCOUNT_ID_TEXT, "")))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_CONFIRM_REQUIRED);

        verifyNoRepositoryWrites();
        verify(accountRepository, never()).findById(any());
    }

    @Test
    @DisplayName("payBill: confirmation flag outside (Y/N) -> ValidationException 'Invalid value. Valid values are (Y/N)...' (no repository access)")
    void payBillInvalidConfirmThrowsValidation() {
        assertThatThrownBy(() -> service.payBill(new BillingDto.PayRequest(ACCOUNT_ID_TEXT, "X")))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_CONFIRM_INVALID);

        verifyNoRepositoryWrites();
        verify(accountRepository, never()).findById(any());
    }

    @Test
    @DisplayName("payBill: confirm 'N' cancels the payment - returns the flag echo with a null balance and writes nothing")
    void payBillConfirmNoCancelsWithoutWriting() {
        BillingDto.PayResponse response =
                service.payBill(new BillingDto.PayRequest(ACCOUNT_ID_TEXT, "N"));

        assertThat(response.accountId()).isEqualTo(ACCOUNT_ID_TEXT);
        assertThat(response.currentBalance()).isNull();
        assertThat(response.confirm()).isEqualTo("N");

        verifyNoRepositoryWrites();
        verify(accountRepository, never()).findById(any());
    }

    @Test
    @DisplayName("payBill: lowercase 'n' also cancels (case-insensitive) and writes nothing")
    void payBillLowercaseNoCancelsWithoutWriting() {
        BillingDto.PayResponse response =
                service.payBill(new BillingDto.PayRequest(ACCOUNT_ID_TEXT, "n"));

        assertThat(response.confirm()).isEqualTo("N");
        assertThat(response.currentBalance()).isNull();

        verifyNoRepositoryWrites();
        verify(accountRepository, never()).findById(any());
    }

    // -----------------------------------------------------------------
    // Phase 4 - account / cross-reference not found (RecordNotFound)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("payBill: unknown account -> RecordNotFoundException 'Account ID NOT found...' and writes nothing")
    void payBillAccountNotFoundThrowsRecordNotFound() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.payBill(new BillingDto.PayRequest(ACCOUNT_ID_TEXT, "Y")))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_ACCT_NOT_FOUND);

        verifyNoRepositoryWrites();
    }

    @Test
    @DisplayName("payBill: account present but no card cross-reference -> RecordNotFoundException 'Account ID NOT found...' and writes nothing")
    void payBillMissingCardXrefThrowsRecordNotFound() {
        Account account = accountWithBalance(new BigDecimal("250.00"));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.payBill(new BillingDto.PayRequest(ACCOUNT_ID_TEXT, "Y")))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_ACCT_NOT_FOUND);

        verifyNoRepositoryWrites();
    }

    @Test
    @DisplayName("payBill: a non-numeric account id is treated as not found -> RecordNotFoundException 'Account ID NOT found...' (no lookup)")
    void payBillNonNumericAccountIdThrowsRecordNotFound() {
        assertThatThrownBy(() -> service.payBill(new BillingDto.PayRequest("ABCDEFGHIJK", "Y")))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_ACCT_NOT_FOUND);

        verify(accountRepository, never()).findById(any());
        verifyNoRepositoryWrites();
    }

    /**
     * Asserts that neither the transaction nor the account was persisted, the
     * common post-condition for every non-confirmed or failing path.
     */
    private void verifyNoRepositoryWrites() {
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }
}
