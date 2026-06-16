package com.carddemo.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.controller.GlobalExceptionHandler;
import com.carddemo.exception.CardDemoException;
import com.carddemo.exception.ConcurrencyException;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.TransactionPostingException;
import com.carddemo.exception.ValidationException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Unit tests for {@link GlobalExceptionHandler}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}):
 * the advice re-platforms the COBOL FILE STATUS / ABEND handling of the CICS and batch
 * programs into RFC 7807 {@link ProblemDetail} responses. These tests pin the domain
 * exception &rarr; HTTP status mapping and, per the CWE-209 hardening, assert that the two
 * 500-level handlers return a fixed sanitized detail rather than the raw exception message
 * (which can carry S3 bucket/object keys or FILE STATUS diagnostics), while the 4xx handlers
 * continue to surface their safe business-validation messages.</p>
 */
@DisplayName("GlobalExceptionHandler - domain exception -> RFC 7807 ProblemDetail mapping")
class GlobalExceptionHandlerTest {

    /** Sanitized 500 detail returned for {@link FileAccessException} (must not echo raw detail). */
    private static final String FILE_ACCESS_SANITIZED_DETAIL =
            "A backend file or queue operation failed; please retry or contact support with your correlation ID.";

    /** Sanitized 500 detail returned for an unmapped {@link CardDemoException}. */
    private static final String PROCESSING_SANITIZED_DETAIL =
            "An unexpected processing error occurred; please retry or contact support with your correlation ID.";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Nested
    @DisplayName("4xx handlers surface the business-validation message verbatim")
    class ClientErrors {

        @Test
        @DisplayName("RecordNotFoundException -> 404 Record Not Found")
        void recordNotFound() {
            ResponseEntity<ProblemDetail> response =
                    handler.handleRecordNotFound(new RecordNotFoundException("Account 00000000123 not found"));

            assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getTitle()).isEqualTo("Record Not Found");
            assertThat(response.getBody().getDetail()).isEqualTo("Account 00000000123 not found");
        }

        @Test
        @DisplayName("DuplicateRecordException -> 409 Duplicate Record")
        void duplicate() {
            ResponseEntity<ProblemDetail> response =
                    handler.handleDuplicateRecord(new DuplicateRecordException("Card already exists"));

            assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getTitle()).isEqualTo("Duplicate Record");
            assertThat(response.getBody().getDetail()).isEqualTo("Card already exists");
        }

        @Test
        @DisplayName("ConcurrencyException -> 409 Concurrent Update Conflict")
        void concurrency() {
            ResponseEntity<ProblemDetail> response =
                    handler.handleConcurrency(new ConcurrencyException("Record changed by another user"));

            assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getTitle()).isEqualTo("Concurrent Update Conflict");
            assertThat(response.getBody().getDetail()).isEqualTo("Record changed by another user");
        }

        @Test
        @DisplayName("ValidationException -> 400 Validation Error")
        void validation() {
            ResponseEntity<ProblemDetail> response =
                    handler.handleValidation(new ValidationException("Start Date - Not a valid date..."));

            assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getTitle()).isEqualTo("Validation Error");
            assertThat(response.getBody().getDetail()).isEqualTo("Start Date - Not a valid date...");
        }

        @Test
        @DisplayName("TransactionPostingException -> 422 Transaction Posting Rejected")
        void transactionPosting() {
            TransactionPostingException ex = new TransactionPostingException(109, "Account not found");

            ResponseEntity<ProblemDetail> response = handler.handleTransactionPosting(ex);

            assertThat(response.getStatusCode().value())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getTitle()).isEqualTo("Transaction Posting Rejected");
            assertThat(response.getBody().getDetail()).isEqualTo(ex.getMessage());
        }
    }

    @Nested
    @DisplayName("500 handlers return a sanitized detail and never echo raw internals (CWE-209)")
    class ServerErrors {

        @Test
        @DisplayName("FileAccessException -> 500 with sanitized detail, raw S3 key not leaked")
        void fileAccessSanitized() {
            FileAccessException ex = new FileAccessException(
                    "Failed to write reject file to S3 carddemo-batch-output/DALYREJS",
                    new RuntimeException("connection refused"));

            ResponseEntity<ProblemDetail> response = handler.handleFileAccess(ex);

            assertThat(response.getStatusCode().value())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getTitle()).isEqualTo("File Access Error");
            assertThat(response.getBody().getDetail()).isEqualTo(FILE_ACCESS_SANITIZED_DETAIL);
            assertThat(response.getBody().getDetail()).doesNotContain("carddemo-batch-output", "DALYREJS");
        }

        @Test
        @DisplayName("unmapped CardDemoException -> 500 with sanitized detail, raw detail not leaked")
        void cardDemoSanitized() {
            CardDemoException ex = new CardDemoException("internal entity diagnostic for ACCTDAT key 999");

            ResponseEntity<ProblemDetail> response = handler.handleCardDemo(ex);

            assertThat(response.getStatusCode().value())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getTitle()).isEqualTo("Processing Error");
            assertThat(response.getBody().getDetail()).isEqualTo(PROCESSING_SANITIZED_DETAIL);
            assertThat(response.getBody().getDetail()).doesNotContain("ACCTDAT", "999");
        }
    }

    @Nested
    @DisplayName("Spring MVC binding errors map to 400 with field-level detail")
    class BindingErrors {

        @Test
        @DisplayName("MethodArgumentNotValidException joins field errors into the detail")
        void methodArgumentNotValidWithFieldErrors() {
            BindingResult bindingResult = mock(BindingResult.class);
            when(bindingResult.getFieldErrors())
                    .thenReturn(List.of(new FieldError("reportRequest", "confirm", "must not be blank")));
            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
            when(ex.getBindingResult()).thenReturn(bindingResult);

            ResponseEntity<ProblemDetail> response = handler.handleMethodArgumentNotValid(ex);

            assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getTitle()).isEqualTo("Validation Error");
            assertThat(response.getBody().getDetail()).isEqualTo("confirm must not be blank");
        }

        @Test
        @DisplayName("MethodArgumentNotValidException with no field errors falls back to a generic detail")
        void methodArgumentNotValidEmpty() {
            BindingResult bindingResult = mock(BindingResult.class);
            when(bindingResult.getFieldErrors()).thenReturn(List.of());
            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
            when(ex.getBindingResult()).thenReturn(bindingResult);

            ResponseEntity<ProblemDetail> response = handler.handleMethodArgumentNotValid(ex);

            assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getDetail()).isEqualTo("Request validation failed");
        }

        @Test
        @DisplayName("MethodArgumentTypeMismatchException names the offending parameter")
        void typeMismatch() {
            MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
            when(ex.getName()).thenReturn("accountId");

            ResponseEntity<ProblemDetail> response = handler.handleTypeMismatch(ex);

            assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getTitle()).isEqualTo("Invalid Request Parameter");
            assertThat(response.getBody().getDetail()).isEqualTo("Parameter 'accountId' has an invalid value");
        }

        @Test
        @DisplayName("HttpMessageNotReadableException -> populated 400, raw parser/body fragment not leaked")
        void messageNotReadableSanitized() {
            // The raw converter message frequently echoes the offending JSON fragment and parser
            // coordinates; the handler must surface a stable 400 ProblemDetail without leaking it.
            HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                    "JSON parse error: Unexpected character (',' (code 44)) at [Source: (String)"
                            + "\"{\"userId\":\"ADMIN001\",,,}\"; line: 1, column: 22]",
                    mock(HttpInputMessage.class));

            ResponseEntity<ProblemDetail> response = handler.handleMessageNotReadable(ex);

            assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getTitle()).isEqualTo("Malformed Request Body");
            assertThat(response.getBody().getDetail())
                    .isEqualTo("The request body could not be read or is not valid JSON.");
            // CWE-209: the populated detail must not echo the offending fragment or parser internals.
            assertThat(response.getBody().getDetail())
                    .doesNotContain("ADMIN001", "JSON parse error", "line: 1", "column");
        }
    }
}
