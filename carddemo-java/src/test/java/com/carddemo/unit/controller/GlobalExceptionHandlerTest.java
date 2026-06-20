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
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.slf4j.MDC;

/**
 * Unit tests for {@link GlobalExceptionHandler}. Verifies the RFC 7807 status/title/detail mapping
 * for every handler, and specifically the CWE-209 fix: 500-level responses ({@link FileAccessException}
 * and the {@link CardDemoException} catch-all) return only a sanitized generic detail — never the
 * internal exception message (e.g. an S3 bucket/object path) — while 4xx responses still carry their
 * domain messages. Also verifies the request correlation id is surfaced on 500 responses.
 */
class GlobalExceptionHandlerTest {

    private static final String SANITIZED = "Internal processing error";
    /** A message containing internal resource detail that must NOT reach the client on a 500. */
    private static final String LEAKY_MESSAGE =
            "Failed to write reject file to S3 carddemo-batch-output/DALYREJS";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("FileAccessException -> 500 with sanitized detail; internal S3 path is not leaked")
    void fileAccessIsSanitized() {
        ProblemDetail body = handler.handleFileAccess(
                new FileAccessException(LEAKY_MESSAGE)).getBody();

        assertThat(body.getStatus()).isEqualTo(500);
        assertThat(body.getTitle()).isEqualTo("File Access Error");
        assertThat(body.getDetail()).isEqualTo(SANITIZED);
        // The internal bucket/object path must never appear in the client-facing body.
        assertThat(body.getDetail()).doesNotContain("carddemo-batch-output");
        assertThat(body.getDetail()).doesNotContain("DALYREJS");
    }

    @Test
    @DisplayName("CardDemoException catch-all -> 500 with sanitized detail; internal message not leaked")
    void cardDemoIsSanitized() {
        ProblemDetail body = handler.handleCardDemo(
                new CardDemoException("internal datasource jdbc:postgresql://db:5432/secret failed")).getBody();

        assertThat(body.getStatus()).isEqualTo(500);
        assertThat(body.getTitle()).isEqualTo("Processing Error");
        assertThat(body.getDetail()).isEqualTo(SANITIZED);
        assertThat(body.getDetail()).doesNotContain("jdbc:postgresql");
    }

    @Test
    @DisplayName("A 500 response surfaces the request correlation id when present in the MDC")
    void internalErrorSurfacesCorrelationId() {
        MDC.put("correlationId", "test-correlation-123");
        ProblemDetail body = handler.handleFileAccess(new FileAccessException(LEAKY_MESSAGE)).getBody();
        assertThat(body.getProperties()).containsEntry("correlationId", "test-correlation-123");
    }

    @Test
    @DisplayName("A 500 response omits the correlationId property when none is in the MDC")
    void internalErrorOmitsCorrelationIdWhenAbsent() {
        ProblemDetail body = handler.handleCardDemo(new CardDemoException("boom")).getBody();
        assertThat(body.getProperties() == null
                || !body.getProperties().containsKey("correlationId")).isTrue();
    }

    @Test
    @DisplayName("RecordNotFoundException -> 404 with the domain message preserved")
    void recordNotFound() {
        ResponseEntity<ProblemDetail> resp = handler.handleRecordNotFound(
                new RecordNotFoundException("Account 00000000001 not found"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().getTitle()).isEqualTo("Record Not Found");
        assertThat(resp.getBody().getDetail()).isEqualTo("Account 00000000001 not found");
    }

    @Test
    @DisplayName("DuplicateRecordException -> 409 with the domain message preserved")
    void duplicateRecord() {
        ProblemDetail body = handler.handleDuplicateRecord(
                new DuplicateRecordException("User already exists")).getBody();
        assertThat(body.getStatus()).isEqualTo(409);
        assertThat(body.getTitle()).isEqualTo("Duplicate Record");
        assertThat(body.getDetail()).isEqualTo("User already exists");
    }

    @Test
    @DisplayName("ConcurrencyException -> 409 with the domain message preserved")
    void concurrency() {
        ProblemDetail body = handler.handleConcurrency(
                new ConcurrencyException("Account was updated by another user")).getBody();
        assertThat(body.getStatus()).isEqualTo(409);
        assertThat(body.getTitle()).isEqualTo("Concurrent Update Conflict");
        assertThat(body.getDetail()).isEqualTo("Account was updated by another user");
    }

    @Test
    @DisplayName("ValidationException -> 400 with the domain message preserved")
    void validation() {
        ProblemDetail body = handler.handleValidation(
                new ValidationException("Amount must be positive")).getBody();
        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getTitle()).isEqualTo("Validation Error");
        assertThat(body.getDetail()).isEqualTo("Amount must be positive");
    }

    @Test
    @DisplayName("TransactionPostingException -> 422 with the domain message preserved")
    void transactionPosting() {
        ProblemDetail body = handler.handleTransactionPosting(
                new TransactionPostingException(109, "OVERLIMIT TRANSACTION")).getBody();
        assertThat(body.getStatus()).isEqualTo(422);
        assertThat(body.getTitle()).isEqualTo("Transaction Posting Rejected");
        assertThat(body.getDetail()).contains("OVERLIMIT TRANSACTION");
    }

    @Test
    @DisplayName("MethodArgumentNotValidException -> 400 aggregating field errors")
    void methodArgumentNotValid() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult br = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(br);
        when(br.getFieldErrors()).thenReturn(List.of(
                new FieldError("req", "amount", "must not be null")));

        ProblemDetail body = handler.handleMethodArgumentNotValid(ex).getBody();
        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getTitle()).isEqualTo("Validation Error");
        assertThat(body.getDetail()).contains("amount").contains("must not be null");
    }

    @Test
    @DisplayName("MethodArgumentNotValidException with no field errors -> generic 400 detail")
    void methodArgumentNotValidNoFieldErrors() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult br = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(br);
        when(br.getFieldErrors()).thenReturn(List.of());

        ProblemDetail body = handler.handleMethodArgumentNotValid(ex).getBody();
        assertThat(body.getDetail()).isEqualTo("Request validation failed");
    }

    @Test
    @DisplayName("MethodArgumentTypeMismatchException -> 400 naming the offending parameter")
    void typeMismatch() {
        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        when(ex.getName()).thenReturn("acctId");

        ProblemDetail body = handler.handleTypeMismatch(ex).getBody();
        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getTitle()).isEqualTo("Invalid Request Parameter");
        assertThat(body.getDetail()).contains("acctId");
    }

    @Test
    @DisplayName("HttpMessageNotReadableException -> 400 RFC7807 sanitized; parser internals not leaked")
    void notReadableIsSanitized() {
        // A message embedding Jackson parser internals (type, JSON-path, source offset) that must
        // never reach the client per CWE-209.
        HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
        when(ex.getMessage()).thenReturn(
                "JSON parse error: Unexpected character; nested exception is "
                        + "com.fasterxml.jackson.core.JsonParseException at [Source: (String)\"{bad\"; "
                        + "line: 1, column: 5]");

        ResponseEntity<ProblemDetail> resp = handler.handleNotReadable(ex);
        ProblemDetail body = resp.getBody();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getTitle()).isEqualTo("Malformed Request");
        assertThat(body.getDetail()).isEqualTo("Request body is missing or malformed");
        // The parser internals must not be echoed back to the client.
        assertThat(body.getDetail()).doesNotContain("jackson");
        assertThat(body.getDetail()).doesNotContain("JsonParseException");
        assertThat(body.getDetail()).doesNotContain("Source");
        assertThat(body.getDetail()).doesNotContain("column");
    }

    @Test
    @DisplayName("HttpRequestMethodNotSupportedException -> 405 RFC7807 with the Allow header set")
    void methodNotSupportedSetsAllowHeader() {
        HttpRequestMethodNotSupportedException ex = mock(HttpRequestMethodNotSupportedException.class);
        when(ex.getMethod()).thenReturn("POST");
        when(ex.getSupportedHttpMethods())
                .thenReturn(Set.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE));

        ResponseEntity<ProblemDetail> resp = handler.handleMethodNotSupported(ex);
        ProblemDetail body = resp.getBody();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(body.getStatus()).isEqualTo(405);
        assertThat(body.getTitle()).isEqualTo("Method Not Allowed");
        assertThat(body.getDetail()).contains("POST");
        // The mandatory Allow header advertises the permitted methods (RFC 7231 6.5.5).
        assertThat(resp.getHeaders().getAllow())
                .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE);
    }

    @Test
    @DisplayName("HttpRequestMethodNotSupportedException with no supported methods -> 405, no Allow header")
    void methodNotSupportedWithoutSupportedMethods() {
        HttpRequestMethodNotSupportedException ex = mock(HttpRequestMethodNotSupportedException.class);
        when(ex.getMethod()).thenReturn("PATCH");
        when(ex.getSupportedHttpMethods()).thenReturn(null);

        ResponseEntity<ProblemDetail> resp = handler.handleMethodNotSupported(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(resp.getBody().getDetail()).contains("PATCH");
        // No supported methods are known, so the Allow header is left unset (empty).
        assertThat(resp.getHeaders().getAllow()).isEmpty();
    }
}
