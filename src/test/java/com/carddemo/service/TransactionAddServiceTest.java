package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.TransactionRepository;

/**
 * Pure, fast Mockito unit tests for {@link TransactionAddService}, the online
 * <em>Add Transaction</em> (CT02) service migrated from the COBOL program
 * {@code COTRN02C} ({@code app/cbl/COTRN02C.cbl}, frozen reference SHA
 * {@code 27d6c6f} — read-only, not copied into this repository).
 *
 * <p>The collaborators {@link TransactionRepository},
 * {@link CrossReferenceService} and {@link DateValidationService} are Mockito
 * mocks; the service is assembled via {@link InjectMocks constructor injection},
 * so no Spring context, database, or live AWS is loaded.</p>
 *
 * <p>The suite pins two review findings against {@code COTRN02C}:</p>
 * <ul>
 *   <li><b>M6</b> — a {@code null} request body must be rejected with a typed
 *       HTTP-400 {@link ValidationException} before any field dereference, never
 *       an unhandled {@link NullPointerException} / HTTP&nbsp;500;</li>
 *   <li><b>M7</b> — the {@code VALIDATE-INPUT-DATA-FIELDS} edits must be enforced
 *       at the service boundary: every mandatory field, the numeric edits, the
 *       {@code YYYY-MM-DD} original/processed date format and {@code CSUTLDTC}
 *       calendar validity, with the verbatim legacy message literals; and the
 *       validated dates must be stored verbatim rather than defaulted to the
 *       current time.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionAddService — COTRN02C (CT02) add-transaction parity")
class TransactionAddServiceTest {

    /** Verbatim summary message the service raises for a rejected add. */
    private static final String MSG_VALIDATION_SUMMARY = "Transaction add validation failed";

    /** A valid original date in the legacy {@code YYYY-MM-DD} screen form. */
    private static final String VALID_ORIG_DATE = "2024-06-15";

    /** A valid processed date in the legacy {@code YYYY-MM-DD} screen form. */
    private static final String VALID_PROC_DATE = "2024-06-16";

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CrossReferenceService crossReferenceService;

    @Mock
    private DateValidationService dateValidationService;

    @InjectMocks
    private TransactionAddService service;

    /**
     * Builds a fully valid, confirmed add request. Individual tests copy this and
     * blank a single field to exercise one edit at a time.
     */
    private static TransactionAddRequest validRequest() {
        return new TransactionAddRequest(
                "12345678901",          // accountId (numeric)
                "4111111111111111",     // cardNumber
                "01",                   // typeCode (numeric)
                "0005",                 // categoryCode (numeric)
                "POS",                  // source
                "Grocery purchase",     // description
                new BigDecimal("123.45"), // amount
                VALID_ORIG_DATE,        // originalTimestamp (YYYY-MM-DD)
                VALID_PROC_DATE,        // processedTimestamp (YYYY-MM-DD)
                "000000123",            // merchantId (numeric)
                "ACME Store",           // merchantName
                "Springfield",          // merchantCity
                "62704",                // merchantZip
                Boolean.TRUE);          // confirm
    }

    @Nested
    @DisplayName("Input-contract guard (M6)")
    class InputContractGuard {

        @Test
        @DisplayName("addTransaction: a null request body -> ValidationException(400) before any field access")
        void addTransaction_nullRequest_throwsValidationException() {
            // M6: a null request body must be a typed HTTP-400 validation error,
            // never an unhandled NullPointerException / HTTP 500 on the
            // request.confirm()/accountId()/amount() dereferences.
            assertThatThrownBy(() -> service.addTransaction(null))
                    .isInstanceOfSatisfying(ValidationException.class,
                            ex -> assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                    .hasMessage(MSG_VALIDATION_SUMMARY);

            verifyNoInteractions(transactionRepository, crossReferenceService, dateValidationService);
        }
    }

    @Nested
    @DisplayName("VALIDATE-INPUT-DATA-FIELDS parity (M7)")
    class DataFieldParity {

        @BeforeEach
        void dateValidatorAcceptsValidDates() {
            // The happy path treats both dates as calendar-valid; individual
            // negative tests override this. lenient() keeps strict-stub tests that
            // reject before the date edit (or that never reach it) from failing.
            lenient().when(dateValidationService.isValidDateCcyyMmDd(anyString())).thenReturn(true);
        }

        @Test
        @DisplayName("a fully valid, confirmed request persists and returns the success message")
        void validRequest_persistsAndConfirms() {
            when(crossReferenceService.generateNextTransactionId()).thenReturn("0000000000000042");

            TransactionAddResponse response = service.addTransaction(validRequest());

            assertThat(response.transactionId()).isEqualTo("0000000000000042");
            assertThat(response.message()).isEqualTo("Transaction added successfully. ");
            verify(transactionRepository).save(any(Transaction.class));
        }

        @Test
        @DisplayName("validated orig/proc dates are stored verbatim, never defaulted to the current time")
        void validRequest_storesDatesVerbatim() {
            when(crossReferenceService.generateNextTransactionId()).thenReturn("0000000000000042");

            service.addTransaction(validRequest());

            ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txnCaptor.capture());
            Transaction saved = txnCaptor.getValue();
            assertThat(saved.getTranOrigTs()).isEqualTo(VALID_ORIG_DATE);
            assertThat(saved.getTranProcTs()).isEqualTo(VALID_PROC_DATE);
        }

        @Test
        @DisplayName("a blank source is rejected with the verbatim 'Source can NOT be empty...' edit")
        void blankSource_rejected() {
            TransactionAddRequest request = withSource(null);

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOfSatisfying(ValidationException.class, ex -> {
                        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(ex.getFieldErrors()).containsEntry("source", "Source can NOT be empty...");
                    });
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("a blank description/merchant name/city/zip each surface their verbatim empty edits")
        void blankMandatoryTextFields_rejected() {
            TransactionAddRequest request = new TransactionAddRequest(
                    "12345678901", "4111111111111111", "01", "0005",
                    "POS", "  ", new BigDecimal("1.00"), VALID_ORIG_DATE, VALID_PROC_DATE,
                    "000000123", "", "  ", "", Boolean.TRUE);

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOfSatisfying(ValidationException.class, ex -> {
                        Map<String, String> errs = ex.getFieldErrors();
                        assertThat(errs).containsEntry("description", "Description can NOT be empty...");
                        assertThat(errs).containsEntry("merchantName", "Merchant Name can NOT be empty...");
                        assertThat(errs).containsEntry("merchantCity", "Merchant City can NOT be empty...");
                        assertThat(errs).containsEntry("merchantZip", "Merchant Zip can NOT be empty...");
                    });
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("a missing original date is rejected with 'Orig Date can NOT be empty...'")
        void missingOrigDate_rejected() {
            TransactionAddRequest request = withDates(null, VALID_PROC_DATE);

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOfSatisfying(ValidationException.class, ex ->
                            assertThat(ex.getFieldErrors())
                                    .containsEntry("originalTimestamp", "Orig Date can NOT be empty..."));
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("a badly-formatted processed date is rejected with 'Proc Date should be in format YYYY-MM-DD'")
        void badFormatProcDate_rejected() {
            TransactionAddRequest request = withDates(VALID_ORIG_DATE, "06/16/2024");

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOfSatisfying(ValidationException.class, ex ->
                            assertThat(ex.getFieldErrors())
                                    .containsEntry("processedTimestamp",
                                            "Proc Date should be in format YYYY-MM-DD"));
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("a well-formatted but non-calendar original date is rejected via CSUTLDTC ('Orig Date - Not a valid date...')")
        void invalidCalendarOrigDate_rejected() {
            // Format edit passes (YYYY-MM-DD) but the calendar-validity check fails.
            when(dateValidationService.isValidDateCcyyMmDd("20240230")).thenReturn(false);
            TransactionAddRequest request = withDates("2024-02-30", VALID_PROC_DATE);

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOfSatisfying(ValidationException.class, ex ->
                            assertThat(ex.getFieldErrors())
                                    .containsEntry("originalTimestamp", "Orig Date - Not a valid date..."));

            // The hyphens are stripped to CCYYMMDD before the validator is consulted.
            verify(dateValidationService).isValidDateCcyyMmDd("20240230");
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("an empty type code surfaces 'Type CD can NOT be empty...' (distinct from the numeric edit)")
        void emptyTypeCode_rejected() {
            TransactionAddRequest request = new TransactionAddRequest(
                    "12345678901", "4111111111111111", "  ", "0005",
                    "POS", "Grocery purchase", new BigDecimal("1.00"), VALID_ORIG_DATE, VALID_PROC_DATE,
                    "000000123", "ACME Store", "Springfield", "62704", Boolean.TRUE);

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOfSatisfying(ValidationException.class, ex ->
                            assertThat(ex.getFieldErrors())
                                    .containsEntry("typeCode", "Type CD can NOT be empty..."));
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("a non-numeric merchant id surfaces 'Merchant ID must be Numeric...'")
        void nonNumericMerchantId_rejected() {
            TransactionAddRequest request = new TransactionAddRequest(
                    "12345678901", "4111111111111111", "01", "0005",
                    "POS", "Grocery purchase", new BigDecimal("1.00"), VALID_ORIG_DATE, VALID_PROC_DATE,
                    "12AB", "ACME Store", "Springfield", "62704", Boolean.TRUE);

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOfSatisfying(ValidationException.class, ex ->
                            assertThat(ex.getFieldErrors())
                                    .containsEntry("merchantId", "Merchant ID must be Numeric..."));
            verify(transactionRepository, never()).save(any());
        }
    }

    // ------------------------------------------------------------------
    // Fixture mutators
    // ------------------------------------------------------------------

    private static TransactionAddRequest withSource(String source) {
        return new TransactionAddRequest(
                "12345678901", "4111111111111111", "01", "0005",
                source, "Grocery purchase", new BigDecimal("123.45"), VALID_ORIG_DATE, VALID_PROC_DATE,
                "000000123", "ACME Store", "Springfield", "62704", Boolean.TRUE);
    }

    private static TransactionAddRequest withDates(String origDate, String procDate) {
        return new TransactionAddRequest(
                "12345678901", "4111111111111111", "01", "0005",
                "POS", "Grocery purchase", new BigDecimal("123.45"), origDate, procDate,
                "000000123", "ACME Store", "Springfield", "62704", Boolean.TRUE);
    }
}
