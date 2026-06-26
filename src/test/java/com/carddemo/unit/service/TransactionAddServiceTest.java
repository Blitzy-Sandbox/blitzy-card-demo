/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.unit.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.carddemo.dto.TransactionDto;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.TransactionAddService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link TransactionAddService}, the
 * Java realization of the CICS pseudo-conversational transaction-add program
 * {@code COTRN02C} (transaction {@code CT02}, "Add a Transaction") @
 * {@code 27d6c6f} (AAP &sect;0.4.1.1, backing {@code POST /api/transactions}).
 *
 * <p>The legacy program first edits and resolves the account/card search key
 * ({@code VALIDATE-INPUT-KEY-FIELDS}, including the {@code READ-CXACAIX-FILE}
 * account alternate-index read and the {@code READ-CCXREF-FILE} card primary-key
 * read), then edits the data fields ({@code VALIDATE-INPUT-DATA-FIELDS}), then
 * applies the {@code EVALUATE CONFIRMI} confirmation gate, generates the next
 * sequential {@code TRAN-ID} ({@code STARTBR}/{@code READPREV} on the highest
 * existing id, plus one), and finally writes the {@code TRAN-RECORD}
 * ({@code WRITE-TRANSACT-FILE}). The Java service collapses these paragraphs into
 * one {@code @Transactional} {@link TransactionAddService#addTransaction} call:
 * the confirmation gate, the ordered field-edit cascade (delegating calendar
 * validity to {@link DateValidationService}), the auto-generated 16-character
 * zero-padded identifier, and the keyed insert.</p>
 *
 * <p>The suite is deliberately framework-free: it bootstraps <strong>no</strong>
 * Spring {@code ApplicationContext}, uses <strong>no</strong>
 * {@code @SpringBootTest}, {@code MockMvc}, Testcontainers, or live database, and
 * touches no AWS or network resource. The three collaborators
 * ({@link TransactionRepository}, {@link CardXrefRepository}, and
 * {@link DateValidationService}) are Mockito-mocked and injected via
 * {@link InjectMocks} through the service's constructor. {@link MockitoExtension}
 * runs with strict stubbing, so each test stubs only the interactions it
 * exercises; the single shared "valid date" stub is registered leniently because
 * the early-exit paths (confirmation gate, key-field errors, cross-reference
 * lookup failures) legitimately never reach calendar validation.</p>
 *
 * <p>Exception detail messages are asserted <strong>verbatim</strong> because
 * they form part of the observable, byte-equivalent behavior guarded by
 * Gate&nbsp;1 and Gate&nbsp;4. Every literal below is reproduced exactly from the
 * compiled {@link TransactionAddService} (itself a byte-exact copy of the
 * {@code COTRN02C} working-storage literals @ {@code 27d6c6f}). Monetary amounts
 * are compared with {@link BigDecimal#compareTo(BigDecimal)} semantics
 * (AssertJ {@code isEqualByComparingTo}) so scale never masks an inequality.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionAddService — COTRN02C (CT02) add-transaction @ 27d6c6f")
class TransactionAddServiceTest {

    /** Account identifier ({@code ACTIDIN}); {@code FUNCTION NUMVAL} yields {@link #ACCOUNT_ID_N}. */
    private static final String ACCOUNT_ID = "11";

    /** Numeric form of {@link #ACCOUNT_ID} used as the {@code XREF-ACCT-ID} alternate-index key. */
    private static final long ACCOUNT_ID_N = 11L;

    /** Card number supplied on the wire / stored on the cross-reference primary key ({@code CARDNIN}). */
    private static final String SUBMITTED_CARD = "4111111111111111";

    /** Distinct card number returned by the account alternate-index read (proves account precedence). */
    private static final String DERIVED_CARD = "4222222222222222";

    /** Two-character transaction type code ({@code TTYPCD}); {@code "01"} resolves to {@link TransactionTypeCode#PURCHASE}. */
    private static final String TYPE_CODE = "01";

    /** Transaction category code ({@code TCATCD}); parsed to the integer {@code 1}. */
    private static final String CATEGORY_CODE = "0001";

    /** Origination source ({@code TRNSRC}). */
    private static final String SOURCE = "POS";

    /** Transaction description ({@code TDESC}). */
    private static final String DESCRIPTION = "Test purchase";

    /** Monetary amount ({@code TRNAMT}); within the {@code -99999999.99} screen format. */
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    /** Origination date ({@code TORIGDT}), {@code YYYY-MM-DD}. */
    private static final String ORIG_DATE = "2023-01-15";

    /** Processing date ({@code TPROCDT}), {@code YYYY-MM-DD}. */
    private static final String PROC_DATE = "2023-01-16";

    /** Merchant identifier ({@code MID}); parsed to the long {@code 123456789}. */
    private static final String MERCHANT_ID = "123456789";

    /** Merchant name ({@code MNAME}). */
    private static final String MERCHANT_NAME = "ACME STORE";

    /** Merchant city ({@code MCITY}). */
    private static final String MERCHANT_CITY = "SEATTLE";

    /** Merchant ZIP code ({@code MZIP}). */
    private static final String MERCHANT_ZIP = "98101";

    /** Generated identifier for the first-ever transaction ({@code ENDFILE} branch + 1, zero-padded). */
    private static final String FIRST_TRAN_ID = "0000000000000001";

    /** A representative existing highest {@code TRAN-ID} used to exercise the increment step. */
    private static final String HIGHEST_TRAN_ID = "0000000000000009";

    /** The identifier the service must generate from {@link #HIGHEST_TRAN_ID} (9 + 1, zero-padded). */
    private static final String NEXT_TRAN_ID = "0000000000000010";

    // Byte-exact COTRN02C literals @ 27d6c6f (Gate 1 / Gate 4 observable behavior).
    private static final String MSG_CONFIRM = "Confirm to add this transaction...";
    private static final String MSG_INVALID_YN = "Invalid value. Valid values are (Y/N)...";
    private static final String MSG_ACCT_OR_CARD = "Account or Card Number must be entered...";
    private static final String MSG_ACCT_NUMERIC = "Account ID must be Numeric...";
    private static final String MSG_CARD_NUMERIC = "Card Number must be Numeric...";
    private static final String MSG_ACCT_NOT_FOUND = "Account ID NOT found...";
    private static final String MSG_CARD_NOT_FOUND = "Card Number NOT found...";
    private static final String MSG_XREF_ACCT_LOOKUP = "Unable to lookup Acct in XREF AIX file...";
    private static final String MSG_XREF_CARD_LOOKUP = "Unable to lookup Card # in XREF file...";
    private static final String MSG_TYPE_EMPTY = "Type CD can NOT be empty...";
    private static final String MSG_AMOUNT_FORMAT = "Amount should be in format -99999999.99";
    private static final String MSG_ORIG_DATE_FORMAT = "Orig Date should be in format YYYY-MM-DD";
    private static final String MSG_DUP_TRAN_ID = "Tran ID already exist...";
    private static final String MSG_UNABLE_TO_ADD = "Unable to Add Transaction...";

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private CardXrefRepository cardXrefRepository;
    @Mock
    private DateValidationService dateValidationService;
    // A bare PlatformTransactionManager mock is sufficient: the real
    // TransactionTemplate the service builds over it executes its action
    // callback synchronously (getTransaction() returns a null status, and
    // commit()/rollback() are no-ops on the mock), so each retry attempt runs
    // inline on the test thread with no NPE.
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private TransactionAddService service;

    @BeforeEach
    void setUp() {
        // The canonical request carries well-formed, valid dates; calendar
        // validity is not the focus of most tests. Registered leniently because
        // the confirmation-gate, key-field, and lookup-failure paths legitimately
        // short-circuit before VALIDATE-INPUT-DATA-FIELDS reaches the date checks.
        lenient().when(dateValidationService.isValidDate(anyString())).thenReturn(true);
    }

    // -----------------------------------------------------------------
    // Request factories. AddRequest is a 14-component record (no withers),
    // so each helper rebuilds the full payload, varying only the field(s)
    // under test while holding every other field at its valid constant.
    // -----------------------------------------------------------------

    private static TransactionDto.AddRequest fullRequest(String accountId, String cardNumber,
            String typeCode, BigDecimal amount, String originDate, String confirm) {
        return new TransactionDto.AddRequest(
                accountId,
                cardNumber,
                typeCode,
                CATEGORY_CODE,
                SOURCE,
                DESCRIPTION,
                amount,
                originDate,
                PROC_DATE,
                MERCHANT_ID,
                MERCHANT_NAME,
                MERCHANT_CITY,
                MERCHANT_ZIP,
                confirm);
    }

    /** A confirmed ({@code Y}) request whose data fields are all valid; only the key fields and type vary. */
    private static TransactionDto.AddRequest request(String accountId, String cardNumber, String typeCode) {
        return fullRequest(accountId, cardNumber, typeCode, AMOUNT, ORIG_DATE, "Y");
    }

    /** A canonical account-keyed request varying only the confirmation flag. */
    private static TransactionDto.AddRequest requestWithConfirm(String confirm) {
        return fullRequest(ACCOUNT_ID, "", TYPE_CODE, AMOUNT, ORIG_DATE, confirm);
    }

    /** A canonical account-keyed request varying only the amount. */
    private static TransactionDto.AddRequest requestWithAmount(BigDecimal amount) {
        return fullRequest(ACCOUNT_ID, "", TYPE_CODE, amount, ORIG_DATE, "Y");
    }

    /** A canonical account-keyed request varying only the origination date. */
    private static TransactionDto.AddRequest requestWithOriginDate(String originDate) {
        return fullRequest(ACCOUNT_ID, "", TYPE_CODE, AMOUNT, originDate, "Y");
    }

    private static Transaction transactionWithId(String tranId) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        return transaction;
    }

    /** Stubs the account alternate-index read to resolve {@link #ACCOUNT_ID} to {@link #SUBMITTED_CARD}. */
    private void stubAccountResolvesToSubmittedCard() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID_N))
                .thenReturn(List.of(new CardXref(SUBMITTED_CARD, 1L, ACCOUNT_ID_N)));
    }

    /** Stubs the id-generation read so the table is empty and the first id is one. */
    private void stubFirstIdAvailable() {
        when(transactionRepository.findTopByOrderByTranIdDesc()).thenReturn(Optional.empty());
    }

    // =================================================================
    // Account / card cross-reference resolution (VALIDATE-INPUT-KEY-FIELDS)
    // =================================================================

    @Test
    @DisplayName("account-only input resolves the card via the account alternate index (READ-CXACAIX-FILE)")
    void accountOnlyResolvesCardViaAix() {
        stubAccountResolvesToSubmittedCard();
        stubFirstIdAvailable();
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);

        TransactionDto.Detail detail = service.addTransaction(request(ACCOUNT_ID, "", TYPE_CODE));

        assertThat(detail.cardNumber()).isEqualTo(SUBMITTED_CARD);
        assertThat(detail.transactionId()).isEqualTo(FIRST_TRAN_ID);
        verify(cardXrefRepository, never()).findById(anyString());
        verify(transactionRepository).insert(captor.capture());
        assertThat(captor.getValue().getCardNum()).isEqualTo(SUBMITTED_CARD);
    }

    @Test
    @DisplayName("card-only input is validated via the cross-reference primary key (READ-CCXREF-FILE)")
    void cardOnlyValidatesViaPrimaryKey() {
        when(cardXrefRepository.findById(SUBMITTED_CARD))
                .thenReturn(Optional.of(new CardXref(SUBMITTED_CARD, 1L, ACCOUNT_ID_N)));
        stubFirstIdAvailable();

        TransactionDto.Detail detail = service.addTransaction(request("", SUBMITTED_CARD, TYPE_CODE));

        assertThat(detail.cardNumber()).isEqualTo(SUBMITTED_CARD);
        verify(cardXrefRepository, never()).findByXrefAcctId(anyLong());
        verify(transactionRepository).insert(any(Transaction.class));
    }

    @Test
    @DisplayName("both supplied: account branch wins and overrides the submitted card")
    void accountTakesPrecedenceOverCard() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID_N))
                .thenReturn(List.of(new CardXref(DERIVED_CARD, 1L, ACCOUNT_ID_N)));
        stubFirstIdAvailable();

        TransactionDto.Detail detail = service.addTransaction(request(ACCOUNT_ID, SUBMITTED_CARD, TYPE_CODE));

        assertThat(detail.cardNumber()).isEqualTo(DERIVED_CARD);
        verify(cardXrefRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("missing account xref yields the legacy 'Account ID NOT found...' and writes nothing")
    void accountNotFound() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID_N)).thenReturn(List.of());

        assertThatThrownBy(() -> service.addTransaction(request(ACCOUNT_ID, "", TYPE_CODE)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_ACCT_NOT_FOUND);
        verify(transactionRepository, never()).insert(any(Transaction.class));
    }

    @Test
    @DisplayName("missing card xref yields the legacy 'Card Number NOT found...' and writes nothing")
    void cardNotFound() {
        when(cardXrefRepository.findById(SUBMITTED_CARD)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addTransaction(request("", SUBMITTED_CARD, TYPE_CODE)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_CARD_NOT_FOUND);
        verify(transactionRepository, never()).insert(any(Transaction.class));
    }

    @Test
    @DisplayName("non-numeric account id short-circuits before any xref read")
    void nonNumericAccountSkipsXref() {
        assertThatThrownBy(() -> service.addTransaction(request("12X", "", TYPE_CODE)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_ACCT_NUMERIC);
        verify(cardXrefRepository, never()).findByXrefAcctId(anyLong());
    }

    @Test
    @DisplayName("non-numeric card number short-circuits before any xref read")
    void nonNumericCardSkipsXref() {
        assertThatThrownBy(() -> service.addTransaction(request("", "4111-BAD", TYPE_CODE)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_CARD_NUMERIC);
        verify(cardXrefRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("key-field xref error takes precedence over a later data-field error (insertion order)")
    void keyErrorPrecedesDataError() {
        // Account not found (key error) AND an empty type code (data error): the
        // surfaced message must be the key error because VALIDATE-INPUT-KEY-FIELDS
        // runs first and its entry is recorded ahead of the data-field entries.
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID_N)).thenReturn(List.of());

        assertThatThrownBy(() -> service.addTransaction(request(ACCOUNT_ID, "", "")))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_ACCT_NOT_FOUND);
    }

    @Test
    @DisplayName("non not-found account lookup failure maps to FileAccessException (WHEN OTHER)")
    void accountLookupFailureMapsToFileAccess() {
        when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID_N))
                .thenThrow(new DataAccessResourceFailureException("db unavailable"));

        assertThatThrownBy(() -> service.addTransaction(request(ACCOUNT_ID, "", TYPE_CODE)))
                .isInstanceOf(FileAccessException.class)
                .hasMessage(MSG_XREF_ACCT_LOOKUP);
    }

    @Test
    @DisplayName("non not-found card lookup failure maps to FileAccessException (WHEN OTHER)")
    void cardLookupFailureMapsToFileAccess() {
        when(cardXrefRepository.findById(SUBMITTED_CARD))
                .thenThrow(new DataAccessResourceFailureException("db unavailable"));

        assertThatThrownBy(() -> service.addTransaction(request("", SUBMITTED_CARD, TYPE_CODE)))
                .isInstanceOf(FileAccessException.class)
                .hasMessage(MSG_XREF_CARD_LOOKUP);
    }

    // =================================================================
    // Phase 1 — successful add + auto-id increment (ADD-TRANSACTION)
    // =================================================================

    @Test
    @DisplayName("addTransaction: increments the highest id (9 -> 10) and persists every TRAN-RECORD field")
    void successIncrementsHighestIdAndPersistsAllFields() {
        stubAccountResolvesToSubmittedCard();
        when(transactionRepository.findTopByOrderByTranIdDesc())
                .thenReturn(Optional.of(transactionWithId(HIGHEST_TRAN_ID)));
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);

        TransactionDto.Detail detail = service.addTransaction(request(ACCOUNT_ID, "", TYPE_CODE));

        // The returned detail view carries the generated id and every echoed field.
        assertThat(detail.transactionId()).isEqualTo(NEXT_TRAN_ID);
        assertThat(detail.cardNumber()).isEqualTo(SUBMITTED_CARD);
        assertThat(detail.transactionType()).isEqualTo(TYPE_CODE);
        assertThat(detail.categoryCode()).isEqualTo(CATEGORY_CODE);
        assertThat(detail.source()).isEqualTo(SOURCE);
        assertThat(detail.description()).isEqualTo(DESCRIPTION);
        assertThat(detail.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(detail.originDate()).isEqualTo(ORIG_DATE);
        assertThat(detail.processDate()).isEqualTo(PROC_DATE);
        assertThat(detail.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(detail.merchantName()).isEqualTo(MERCHANT_NAME);
        assertThat(detail.merchantCity()).isEqualTo(MERCHANT_CITY);
        assertThat(detail.merchantZip()).isEqualTo(MERCHANT_ZIP);
        // The detail surfaces the byte-exact COTRN02C success banner naming the
        // generated transaction id (two spaces after "successfully.", trailing period).
        assertThat(detail.confirmationMessage())
                .isEqualTo("Transaction added successfully.  Your Tran ID is " + NEXT_TRAN_ID + ".");

        // The persisted entity mirrors the COBOL ADD-TRANSACTION field moves.
        verify(transactionRepository).insert(captor.capture());
        Transaction saved = captor.getValue();
        assertThat(saved.getTranId()).isEqualTo(NEXT_TRAN_ID);
        assertThat(saved.getCardNum()).isEqualTo(SUBMITTED_CARD);
        assertThat(saved.getTranTypeCd()).isEqualTo(TransactionTypeCode.PURCHASE.getCode());
        assertThat(saved.getTranCatCd()).isEqualTo(1);
        assertThat(saved.getTranSource()).isEqualTo(SOURCE);
        assertThat(saved.getTranDesc()).isEqualTo(DESCRIPTION);
        assertThat(saved.getTranAmt()).isEqualByComparingTo(AMOUNT);
        assertThat(saved.getMerchantId()).isEqualTo(123456789L);
        assertThat(saved.getMerchantName()).isEqualTo(MERCHANT_NAME);
        assertThat(saved.getMerchantCity()).isEqualTo(MERCHANT_CITY);
        assertThat(saved.getMerchantZip()).isEqualTo(MERCHANT_ZIP);
        assertThat(saved.getOrigTs()).isEqualTo(ORIG_DATE);
        assertThat(saved.getProcTs()).isEqualTo(PROC_DATE);
    }

    // =================================================================
    // Phase 2 — first-ever transaction (ENDFILE branch)
    // =================================================================

    @Test
    @DisplayName("addTransaction: an empty TRANSACT file generates the first id 0000000000000001")
    void firstEverTransactionGeneratesIdOne() {
        stubAccountResolvesToSubmittedCard();
        stubFirstIdAvailable();

        TransactionDto.Detail detail = service.addTransaction(request(ACCOUNT_ID, "", TYPE_CODE));

        assertThat(detail.transactionId()).isEqualTo(FIRST_TRAN_ID);
        verify(transactionRepository).insert(any(Transaction.class));
    }

    // =================================================================
    // Phase 3 — confirmation gate (EVALUATE CONFIRMI)
    // =================================================================

    @Test
    @DisplayName("addTransaction: a blank confirmation prompts 'Confirm to add this transaction...' and writes nothing")
    void blankConfirmationPromptsAndDoesNotSave() {
        assertThatThrownBy(() -> service.addTransaction(requestWithConfirm("")))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_CONFIRM);

        verify(transactionRepository, never()).insert(any(Transaction.class));
        verify(cardXrefRepository, never()).findByXrefAcctId(anyLong());
    }

    @Test
    @DisplayName("addTransaction: a non-Y/N confirmation is rejected with 'Invalid value. Valid values are (Y/N)...'")
    void invalidConfirmationRejectedWithYnMessage() {
        assertThatThrownBy(() -> service.addTransaction(requestWithConfirm("X")))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_INVALID_YN);

        verify(transactionRepository, never()).insert(any(Transaction.class));
    }

    @Test
    @DisplayName("addTransaction: a declined 'N' confirmation re-prompts (same as blank) and writes nothing")
    void declinedConfirmationRePromptsAndDoesNotSave() {
        // COBOL EVALUATE CONFIRMI maps WHEN 'N'/'n'/SPACES/LOW-VALUES to the SAME
        // re-prompt message; declining is a re-prompt, not a distinct cancellation.
        assertThatThrownBy(() -> service.addTransaction(requestWithConfirm("N")))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_CONFIRM);

        verify(transactionRepository, never()).insert(any(Transaction.class));
    }

    // =================================================================
    // Phase 4 — field validation (VALIDATE-INPUT-DATA-FIELDS), no write
    // =================================================================

    @Test
    @DisplayName("addTransaction: neither account nor card -> 'Account or Card Number must be entered...'")
    void missingAccountAndCardRejected() {
        Throwable thrown = catchThrowable(() -> service.addTransaction(request("", "", TYPE_CODE)));

        assertThat(thrown)
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_ACCT_OR_CARD);
        assertThat(((ValidationException) thrown).getFieldErrors())
                .containsExactly(entry("accountId", MSG_ACCT_OR_CARD));
        verify(transactionRepository, never()).insert(any(Transaction.class));
        verify(cardXrefRepository, never()).findByXrefAcctId(anyLong());
        verify(cardXrefRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("addTransaction: a blank type code -> 'Type CD can NOT be empty...'")
    void blankTypeCodeRejected() {
        stubAccountResolvesToSubmittedCard();

        Throwable thrown = catchThrowable(() -> service.addTransaction(request(ACCOUNT_ID, "", "")));

        assertThat(thrown)
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_TYPE_EMPTY);
        assertThat(((ValidationException) thrown).getFieldErrors())
                .containsExactly(entry("typeCode", MSG_TYPE_EMPTY));
        verify(transactionRepository, never()).insert(any(Transaction.class));
    }

    @Test
    @DisplayName("addTransaction: an out-of-range amount -> 'Amount should be in format -99999999.99'")
    void amountExceedingFormatRejected() {
        stubAccountResolvesToSubmittedCard();

        Throwable thrown =
                catchThrowable(() -> service.addTransaction(requestWithAmount(new BigDecimal("100000000.00"))));

        assertThat(thrown)
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_AMOUNT_FORMAT);
        assertThat(((ValidationException) thrown).getFieldErrors())
                .containsExactly(entry("amount", MSG_AMOUNT_FORMAT));
        verify(transactionRepository, never()).insert(any(Transaction.class));
    }

    @Test
    @DisplayName("addTransaction: a malformed origination date -> 'Orig Date should be in format YYYY-MM-DD'")
    void malformedOriginDateRejected() {
        stubAccountResolvesToSubmittedCard();

        Throwable thrown = catchThrowable(() -> service.addTransaction(requestWithOriginDate("2023/01/15")));

        assertThat(thrown)
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_ORIG_DATE_FORMAT);
        assertThat(((ValidationException) thrown).getFieldErrors())
                .containsExactly(entry("originDate", MSG_ORIG_DATE_FORMAT));
        verify(transactionRepository, never()).insert(any(Transaction.class));
    }

    // =================================================================
    // Phase 5 — duplicate id + persistence-failure translation
    // (WRITE-TRANSACT-FILE DUPKEY/DUPREC and WHEN OTHER)
    // =================================================================

    @Test
    @DisplayName("addTransaction: a transient id collision is retried and the next attempt succeeds (concurrency parity, Issue #6)")
    void transientCollisionThenRetrySucceeds() {
        stubAccountResolvesToSubmittedCard();
        when(transactionRepository.findTopByOrderByTranIdDesc())
                .thenReturn(Optional.of(transactionWithId(HIGHEST_TRAN_ID)));
        // The first attempt loses the id race: a concurrent writer claimed the same
        // id, so the flush raises a primary-key violation. The retry re-reads the
        // highest id and commits — reproducing the serialized record-locking the
        // legacy CICS/VSAM write provided, instead of surfacing a 500.
        when(transactionRepository.insert(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"pk_transactions\""))
                .thenReturn(transactionWithId(NEXT_TRAN_ID));

        TransactionDto.Detail detail = service.addTransaction(request(ACCOUNT_ID, "", TYPE_CODE));

        assertThat(detail.transactionId()).isEqualTo(NEXT_TRAN_ID);
        // The write was attempted twice: the collision rolled back attempt one, the
        // retry committed attempt two.
        verify(transactionRepository, times(2)).insert(any(Transaction.class));
    }

    @Test
    @DisplayName("addTransaction: a persistent unique-key violation exhausts retries -> DuplicateRecordException (DUPKEY/DUPREC parity)")
    void duplicateIdViaIntegrityViolationRejected() {
        stubAccountResolvesToSubmittedCard();
        when(transactionRepository.findTopByOrderByTranIdDesc())
                .thenReturn(Optional.of(transactionWithId(HIGHEST_TRAN_ID)));
        DataIntegrityViolationException integrityViolation =
                new DataIntegrityViolationException("duplicate key value violates unique constraint \"transactions_pkey\"");
        // Every attempt collides, so the bounded retry exhausts and surfaces the
        // byte-exact legacy duplicate message, carrying the last collision as cause.
        when(transactionRepository.insert(any(Transaction.class))).thenThrow(integrityViolation);

        assertThatThrownBy(() -> service.addTransaction(request(ACCOUNT_ID, "", TYPE_CODE)))
                .isInstanceOf(DuplicateRecordException.class)
                .hasMessage(MSG_DUP_TRAN_ID)
                .hasCause(integrityViolation);
    }

    @Test
    @DisplayName("addTransaction: a generic data-access failure on save -> FileAccessException 'Unable to Add Transaction...'")
    void genericPersistenceFailureMapsToFileAccess() {
        stubAccountResolvesToSubmittedCard();
        when(transactionRepository.findTopByOrderByTranIdDesc())
                .thenReturn(Optional.of(transactionWithId(HIGHEST_TRAN_ID)));
        DataAccessResourceFailureException dataAccessFailure =
                new DataAccessResourceFailureException("simulated data-store connection failure");
        when(transactionRepository.insert(any(Transaction.class))).thenThrow(dataAccessFailure);

        assertThatThrownBy(() -> service.addTransaction(request(ACCOUNT_ID, "", TYPE_CODE)))
                .isInstanceOf(FileAccessException.class)
                .hasMessage(MSG_UNABLE_TO_ADD)
                .hasCause(dataAccessFailure);
    }

    // =================================================================
    // Cascade ordering — COTRN02C resolves the key, edits the data,
    // finds the highest id, guards the duplicate, then writes.
    // =================================================================

    @Test
    @DisplayName("addTransaction: executes resolve -> validate-date -> find-highest -> write in order")
    void happyPathExecutesCobolCascadeInOrder() {
        stubAccountResolvesToSubmittedCard();
        stubFirstIdAvailable();

        service.addTransaction(request(ACCOUNT_ID, "", TYPE_CODE));

        InOrder inOrder = inOrder(cardXrefRepository, dateValidationService, transactionRepository);
        inOrder.verify(cardXrefRepository).findByXrefAcctId(ACCOUNT_ID_N);
        // Both the origination and processing dates are calendar-checked (twice),
        // and both occur before the highest-id read.
        inOrder.verify(dateValidationService, times(2)).isValidDate(anyString());
        // Inside the retried unit of work: read the highest id, then flush the
        // write (the prior racy existsById pre-check was removed for Issue #6).
        inOrder.verify(transactionRepository).findTopByOrderByTranIdDesc();
        inOrder.verify(transactionRepository).insert(any(Transaction.class));
    }
}
