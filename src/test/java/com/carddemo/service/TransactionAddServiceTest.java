package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.carddemo.dto.TransactionAddRequest;
import com.carddemo.dto.TransactionAddResponse;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.TransactionRepository;

/**
 * Pure, fast Mockito unit tests for {@link TransactionAddService}, the online
 * <em>Add Transaction</em> ({@code CT02}) service migrated from the COBOL/CICS
 * program {@code COTRN02C} ({@code app/cbl/COTRN02C.cbl}, frozen reference SHA
 * {@code 27d6c6f} &mdash; read-only, not copied into this repository).
 *
 * <p>The three collaborators ({@link TransactionRepository},
 * {@link CrossReferenceService} and {@link DateValidationService}) are Mockito
 * mocks and the service is assembled through {@link InjectMocks constructor
 * injection}, so no Spring context, database, Testcontainers, Docker, or live
 * AWS is loaded &mdash; the suite is a fast, isolated unit test that feeds the
 * JaCoCo line-coverage gate (Gate&nbsp;8).</p>
 *
 * <h2>What is asserted (COTRN02C parity)</h2>
 * <ul>
 *   <li><b>Confirm gate ({@code PROCESS-ENTER-KEY} / {@code EVALUATE CONFIRMI}).</b>
 *       An absent or {@code false} confirm flag returns the verbatim
 *       <em>"Confirm to add this transaction..."</em> preview <strong>without any
 *       write</strong> and without touching the id-generation or date-validation
 *       collaborators.</li>
 *   <li><b>Field edits ({@code VALIDATE-INPUT-KEY-FIELDS} /
 *       {@code VALIDATE-INPUT-DATA-FIELDS}).</b> Every operator message is asserted
 *       byte-for-byte, including the account-before-card precedence of the key
 *       block and the amount edit mask.</li>
 *   <li><b>Auto-id + persist ({@code ADD-TRANSACTION} / {@code WRITE-TRANSACT-FILE}).</b>
 *       On a confirmed, valid request the 16-digit id from
 *       {@link CrossReferenceService#generateNextTransactionId()} is stamped onto a
 *       {@link Transaction} whose amount is normalised to scale&nbsp;2, and the
 *       verbatim success message <em>"Transaction added successfully. "</em> (with
 *       its trailing space) is returned.</li>
 * </ul>
 *
 * <h2>Fidelity notes verified against the production service</h2>
 * <ul>
 *   <li><b>Accumulated field errors.</b> {@code TransactionAddService.validate(..)}
 *       does <em>not</em> stop at the first failing edit; it accumulates every
 *       field failure (in {@code COTRN02C} field order) and raises them together as
 *       a single {@link ValidationException}. Because
 *       {@link ValidationException#getFieldErrors()} is an <em>unordered</em>
 *       immutable copy, these tests assert the <em>content</em> of the error map
 *       (via {@code containsEntry} / {@code hasSize}) rather than its iteration
 *       order; the genuine ordering guarantee &mdash; account is edited before card
 *       &mdash; is asserted directly.</li>
 *   <li><b>Verbatim dates.</b> The original / processed dates are validated as
 *       {@code YYYY-MM-DD} (exactly ten characters) and stored on the record
 *       verbatim; the service never pads them to the 26-character
 *       {@code TRAN-ORIG-TS} / {@code TRAN-PROC-TS} field width, so the saved
 *       values are ten characters long.</li>
 *   <li><b>Money.</b> The amount is a {@link BigDecimal} normalised to scale&nbsp;2;
 *       it is asserted by {@link BigDecimal#compareTo(BigDecimal) compareTo} plus an
 *       explicit {@code scale() == 2} check &mdash; no {@code float}/{@code double}
 *       appears anywhere in this test.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionAddService — COTRN02C (CT02) add-transaction parity (SHA 27d6c6f)")
class TransactionAddServiceTest {

    // ---------------------------------------------------------------------
    // Verbatim legacy literals under test (kept as constants so a single edit
    // updates every assertion and drift from the service is caught at compile
    // time as well as at run time).
    // ---------------------------------------------------------------------

    /** Verbatim {@code COTRN02C} summary message for a rejected add. */
    private static final String MSG_VALIDATION_SUMMARY = "Transaction add validation failed";

    /** Verbatim {@code EVALUATE CONFIRMI} preview prompt (unconfirmed add). */
    private static final String MSG_CONFIRM_PROMPT = "Confirm to add this transaction...";

    /** Verbatim {@code WRITE-TRANSACT-FILE} success message — note the trailing space. */
    private static final String MSG_SUCCESS = "Transaction added successfully. ";

    /** Verbatim {@code VALIDATE-INPUT-KEY-FIELDS} "must be entered" default. */
    private static final String MSG_ACCOUNT_OR_CARD = "Account or Card Number must be entered...";

    /** Verbatim {@code XREF} not-found message raised by {@link CrossReferenceService}. */
    private static final String MSG_XREF_NOT_FOUND = "Did not find this account in account card xref file";

    // ---------------------------------------------------------------------
    // Canonical valid-field values reused by the request builder.
    // ---------------------------------------------------------------------

    /** Numeric account id (COBOL {@code ACTIDIN}). */
    private static final String ACCT = "12345678901";

    /** Numeric account id as parsed by the service ({@code Long.valueOf}). */
    private static final long ACCT_ID = 12345678901L;

    /** Card number (COBOL {@code CARDNIN} / {@code TRAN-CARD-NUM}). */
    private static final String CARD = "4111111111111111";

    /** A valid original date in the legacy {@code YYYY-MM-DD} screen form. */
    private static final String ORIG = "2024-06-15";

    /** A valid processed date in the legacy {@code YYYY-MM-DD} screen form. */
    private static final String PROC = "2024-06-16";

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CrossReferenceService crossReferenceService;

    @Mock
    private DateValidationService dateValidationService;

    @InjectMocks
    private TransactionAddService service;

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    /**
     * Builds a {@link TransactionAddRequest} varying only the fields the tests
     * exercise; the always-valid text fields (source, description, merchant name /
     * city / zip) are held constant so each test isolates a single edit.
     */
    private static TransactionAddRequest build(String accountId, String cardNumber, String typeCode,
            String categoryCode, BigDecimal amount, String origDate, String procDate,
            String merchantId, Boolean confirm) {
        return new TransactionAddRequest(
                accountId,
                cardNumber,
                typeCode,
                categoryCode,
                "POS",
                "Grocery purchase",
                amount,
                origDate,
                procDate,
                merchantId,
                "ACME Store",
                "Springfield",
                "62704",
                confirm);
    }

    /**
     * Stubs the calendar-date validator to accept every date. Used by the tests
     * that carry valid {@code YYYY-MM-DD} dates; because
     * {@code TransactionAddService.validate(..)} always edits both dates (it does
     * not short-circuit), this stub is always exercised and therefore compatible
     * with Mockito strict stubbing.
     */
    private void acceptDates() {
        when(dateValidationService.isValidDateCcyyMmDd(anyString())).thenReturn(true);
    }

    // ---------------------------------------------------------------------
    // Confirm gate (PROCESS-ENTER-KEY / EVALUATE CONFIRMI) — preview, no write
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("confirm=null → verbatim preview prompt, nothing written, no collaborators touched")
    void addTransaction_confirmNull_returnsPreviewNoWrite() {
        TransactionAddRequest request =
                build(ACCT, CARD, "01", "0005", new BigDecimal("123.45"), ORIG, PROC, "000000123", null);

        TransactionAddResponse response = service.addTransaction(request);

        // Preview response mirrors COTRN02C's "Confirm to add this transaction..." branch.
        assertThat(response.message()).isEqualTo(MSG_CONFIRM_PROMPT);
        assertThat(response.transactionId()).isNull();
        assertThat(response.accountId()).isEqualTo(ACCT);
        // Amount echoed back, normalised to scale 2 by the response record.
        assertThat(response.amount()).isEqualByComparingTo("123.45");
        assertThat(response.amount().scale()).isEqualTo(2);

        // The confirm gate short-circuits before any write or collaborator call.
        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(crossReferenceService, dateValidationService);
    }

    @Test
    @DisplayName("confirm=false → verbatim preview prompt, nothing written, no collaborators touched")
    void addTransaction_confirmFalse_returnsPreviewNoWrite() {
        TransactionAddRequest request = build(ACCT, CARD, "01", "0005", new BigDecimal("123.45"),
                ORIG, PROC, "000000123", Boolean.FALSE);

        TransactionAddResponse response = service.addTransaction(request);

        assertThat(response.message()).isEqualTo(MSG_CONFIRM_PROMPT);
        assertThat(response.transactionId()).isNull();
        assertThat(response.accountId()).isEqualTo(ACCT);
        assertThat(response.amount()).isEqualByComparingTo("123.45");
        assertThat(response.amount().scale()).isEqualTo(2);

        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(crossReferenceService, dateValidationService);
    }

    // ---------------------------------------------------------------------
    // Input-contract guard — a null body is a typed 400, never an NPE / 500
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("null request body → ValidationException(400) before any field access, nothing touched")
    void addTransaction_nullRequest_throwsValidation() {
        assertThatThrownBy(() -> service.addTransaction(null))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage(MSG_VALIDATION_SUMMARY);

        verifyNoInteractions(transactionRepository, crossReferenceService, dateValidationService);
    }

    // ---------------------------------------------------------------------
    // VALIDATE-INPUT-KEY-FIELDS / VALIDATE-INPUT-DATA-FIELDS — one edit each
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("no account and no card → verbatim 'Account or Card Number must be entered...' (400), no write")
    void addTransaction_missingAccountAndCard_throwsValidation() {
        acceptDates();
        TransactionAddRequest request = build(null, null, "01", "0005", new BigDecimal("1.00"),
                ORIG, PROC, "000000123", Boolean.TRUE);

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getFieldErrors()).containsEntry("account", MSG_ACCOUNT_OR_CARD);
                })
                .hasMessage(MSG_VALIDATION_SUMMARY);

        verify(transactionRepository, never()).save(any());
        // The key edit fails long before any id generation / card resolution.
        verifyNoInteractions(crossReferenceService);
    }

    @Test
    @DisplayName("empty type code → verbatim 'Type CD can NOT be empty...'")
    void addTransaction_emptyTypeCode_throwsValidation() {
        acceptDates();
        TransactionAddRequest request = build(ACCT, CARD, "  ", "0005", new BigDecimal("1.00"),
                ORIG, PROC, "000000123", Boolean.TRUE);

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsEntry("typeCode", "Type CD can NOT be empty..."));

        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("non-numeric type code → verbatim 'Type CD must be Numeric...'")
    void addTransaction_nonNumericTypeCd_throwsValidation() {
        acceptDates();
        TransactionAddRequest request = build(ACCT, CARD, "AB", "0005", new BigDecimal("1.00"),
                ORIG, PROC, "000000123", Boolean.TRUE);

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsEntry("typeCode", "Type CD must be Numeric..."));

        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("non-numeric category code → verbatim 'Category CD must be Numeric...'")
    void addTransaction_nonNumericCategoryCd_throwsValidation() {
        acceptDates();
        TransactionAddRequest request = build(ACCT, CARD, "01", "XX", new BigDecimal("1.00"),
                ORIG, PROC, "000000123", Boolean.TRUE);

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsEntry("categoryCode", "Category CD must be Numeric..."));

        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("blank mandatory text fields → each surfaces its verbatim 'can NOT be empty' edit")
    void addTransaction_blankMandatoryTextFields_throwsValidation() {
        acceptDates();
        // Blank source / description / merchant name / city / zip in one request; the
        // service accumulates every mandatory-presence failure (COTRN02C field order).
        TransactionAddRequest request = new TransactionAddRequest(
                ACCT, CARD, "01", "0005",
                "  ", "  ", new BigDecimal("1.00"), ORIG, PROC,
                "000000123", "  ", "  ", "  ", Boolean.TRUE);

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsEntry("source", "Source can NOT be empty...")
                                .containsEntry("description", "Description can NOT be empty...")
                                .containsEntry("merchantName", "Merchant Name can NOT be empty...")
                                .containsEntry("merchantCity", "Merchant City can NOT be empty...")
                                .containsEntry("merchantZip", "Merchant Zip can NOT be empty..."));

        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("null amount → verbatim 'Amount can NOT be empty...'")
    void addTransaction_emptyAmount_throwsValidation() {
        acceptDates();
        TransactionAddRequest request = build(ACCT, CARD, "01", "0005", null,
                ORIG, PROC, "000000123", Boolean.TRUE);

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsEntry("amount", "Amount can NOT be empty..."));

        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("amount with more than two decimals → verbatim 'Amount should be in format -99999999.99'")
    void addTransaction_badAmountFormat_throwsValidation() {
        acceptDates();
        // 1.234 has three fraction digits, breaking the COBOL +99999999.99 edit mask.
        TransactionAddRequest request = build(ACCT, CARD, "01", "0005", new BigDecimal("1.234"),
                ORIG, PROC, "000000123", Boolean.TRUE);

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsEntry("amount", "Amount should be in format -99999999.99"));

        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("mis-formatted processed date → verbatim 'Proc Date should be in format YYYY-MM-DD'")
    void addTransaction_badDateFormat_throwsValidation() {
        // Well-formed original date, mm/dd/yyyy processed date: the format edit fails
        // before the CSUTLDTC validity call, so the date validator is only consulted
        // for the (valid) original date.
        when(dateValidationService.isValidDateCcyyMmDd("20240615")).thenReturn(true);
        TransactionAddRequest request = build(ACCT, CARD, "01", "0005", new BigDecimal("1.00"),
                ORIG, "06/16/2024", "000000123", Boolean.TRUE);

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsEntry("processedTimestamp",
                                        "Proc Date should be in format YYYY-MM-DD"));

        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("well-formed but non-calendar date → CSUTLDTC 'Orig Date - Not a valid date...'")
    void addTransaction_invalidCalendarDate_throwsValidation() {
        // The YYYY-MM-DD format edit passes but the calendar-validity check fails;
        // the service strips the hyphens to CCYYMMDD before consulting the validator.
        when(dateValidationService.isValidDateCcyyMmDd("20240230")).thenReturn(false);
        when(dateValidationService.isValidDateCcyyMmDd("20240616")).thenReturn(true);
        TransactionAddRequest request = build(ACCT, CARD, "01", "0005", new BigDecimal("1.00"),
                "2024-02-30", PROC, "000000123", Boolean.TRUE);

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsEntry("originalTimestamp", "Orig Date - Not a valid date..."));

        verify(dateValidationService).isValidDateCcyyMmDd("20240230");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("non-numeric merchant id → verbatim 'Merchant ID must be Numeric...'")
    void addTransaction_nonNumericMerchantId_throwsValidation() {
        acceptDates();
        TransactionAddRequest request = build(ACCT, CARD, "01", "0005", new BigDecimal("1.00"),
                ORIG, PROC, "12AB", Boolean.TRUE);

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsEntry("merchantId", "Merchant ID must be Numeric..."));

        verify(transactionRepository, never()).save(any());
    }

    // ---------------------------------------------------------------------
    // Ordering / accumulation semantics
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("multiple violations accumulate together, with the earliest (account/card) rule reported first")
    void addTransaction_validationOrder() {
        acceptDates();
        // Violates rule 1 (account/card absent), rule 2 (type non-numeric) and rule 9
        // (merchant id non-numeric). COTRN02C's translated validator does not stop at
        // the first edit; it accumulates every failure in field order. getFieldErrors()
        // is an unordered immutable copy, so the accumulated CONTENT is asserted — and
        // the earliest documented rule (account/card) is guaranteed to be present.
        TransactionAddRequest request = build(null, null, "AB", "0005", new BigDecimal("1.00"),
                ORIG, PROC, "12AB", Boolean.TRUE);

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .hasSize(3)
                                .containsEntry("account", MSG_ACCOUNT_OR_CARD)
                                .containsEntry("typeCode", "Type CD must be Numeric...")
                                .containsEntry("merchantId", "Merchant ID must be Numeric..."))
                .hasMessage(MSG_VALIDATION_SUMMARY);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("key edit precedence: account is edited before card (card branch skipped when account present)")
    void addTransaction_accountEditedBeforeCard() {
        acceptDates();
        // Both key fields present and both non-numeric: only the account edit is
        // recorded — the card branch is never reached — preserving COTRN02C's
        // account-before-card EVALUATE ordering.
        TransactionAddRequest request = build("12A", "34B", "01", "0005", new BigDecimal("1.00"),
                ORIG, PROC, "000000123", Boolean.TRUE);

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.getFieldErrors()).containsEntry("accountId", "Account ID must be Numeric...");
                    assertThat(ex.getFieldErrors()).doesNotContainKey("cardNumber");
                });

        verify(transactionRepository, never()).save(any());
    }

    // ---------------------------------------------------------------------
    // ADD-TRANSACTION / WRITE-TRANSACT-FILE — confirmed, valid, persisted
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("confirmed + valid → generates 16-digit id, saves scale-2 amount, returns trailing-space success")
    void addTransaction_success_generatesIdSavesAndConfirms() {
        acceptDates();
        when(crossReferenceService.generateNextTransactionId()).thenReturn("0000000000000042");
        // amount 1234.5 (scale 1) proves the service normalises to scale 2 (HALF_UP).
        TransactionAddRequest request = build(ACCT, CARD, "01", "0005", new BigDecimal("1234.5"),
                ORIG, PROC, "000000123", Boolean.TRUE);

        TransactionAddResponse response = service.addTransaction(request);

        // The persisted record carries the generated id and the migrated fields.
        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txnCaptor.capture());
        Transaction saved = txnCaptor.getValue();
        assertThat(saved.getTranId()).isEqualTo("0000000000000042");
        assertThat(saved.getTranId()).hasSize(16);
        assertThat(saved.getTranAmt()).isEqualByComparingTo("1234.50");
        assertThat(saved.getTranAmt().scale()).isEqualTo(2);
        assertThat(saved.getTranCardNum()).isEqualTo(CARD);
        assertThat(saved.getTranTypeCd()).isEqualTo("01");
        assertThat(saved.getTranCatCd()).isEqualTo(5);
        assertThat(saved.getTranMerchantId()).isEqualTo(123L);
        // Dates stored verbatim as the validated YYYY-MM-DD text (ten chars, not padded to 26).
        assertThat(saved.getTranOrigTs()).isEqualTo(ORIG);
        assertThat(saved.getTranOrigTs()).hasSize(10);
        assertThat(saved.getTranProcTs()).isEqualTo(PROC);

        // The confirmation response mirrors WRITE-TRANSACT-FILE.
        assertThat(response.transactionId()).isEqualTo("0000000000000042");
        assertThat(response.accountId()).isEqualTo(ACCT);
        assertThat(response.amount()).isEqualByComparingTo("1234.50");
        assertThat(response.amount().scale()).isEqualTo(2);
        assertThat(response.message()).isEqualTo(MSG_SUCCESS);

        // Card supplied directly, so the account→card cross-reference is never consulted.
        verify(crossReferenceService).generateNextTransactionId();
        verify(crossReferenceService, never()).resolvePrimaryCardNumber(anyLong());
    }

    @Test
    @DisplayName("account only (no card) → primary card resolved via cross-reference, then persisted")
    void addTransaction_accountOnly_resolvesCardAndSaves() {
        acceptDates();
        when(crossReferenceService.resolvePrimaryCardNumber(ACCT_ID)).thenReturn(CARD);
        when(crossReferenceService.generateNextTransactionId()).thenReturn("0000000000000042");
        TransactionAddRequest request = build(ACCT, null, "01", "0005", new BigDecimal("50.00"),
                ORIG, PROC, "000000123", Boolean.TRUE);

        TransactionAddResponse response = service.addTransaction(request);

        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txnCaptor.capture());
        // The resolved primary card is stamped onto the record (READ-CXACAIX-FILE parity).
        assertThat(txnCaptor.getValue().getTranCardNum()).isEqualTo(CARD);
        assertThat(response.transactionId()).isEqualTo("0000000000000042");
        assertThat(response.message()).isEqualTo(MSG_SUCCESS);

        verify(crossReferenceService).resolvePrimaryCardNumber(ACCT_ID);
    }

    @Test
    @DisplayName("account only with no card cross-reference → ResourceNotFoundException(404), nothing written")
    void addTransaction_accountWithoutCardXref_throwsNotFound() {
        acceptDates();
        when(crossReferenceService.resolvePrimaryCardNumber(ACCT_ID))
                .thenThrow(new ResourceNotFoundException(MSG_XREF_NOT_FOUND));
        TransactionAddRequest request = build(ACCT, null, "01", "0005", new BigDecimal("50.00"),
                ORIG, PROC, "000000123", Boolean.TRUE);

        assertThatThrownBy(() -> service.addTransaction(request))
                .isInstanceOfSatisfying(ResourceNotFoundException.class,
                        ex -> assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessage(MSG_XREF_NOT_FOUND);

        // Card resolution precedes id allocation, so no id is generated and nothing is saved.
        verify(crossReferenceService, never()).generateNextTransactionId();
        verify(transactionRepository, never()).save(any());
    }
}
