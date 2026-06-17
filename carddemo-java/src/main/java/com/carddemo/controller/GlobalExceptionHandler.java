package com.carddemo.controller;

import com.carddemo.exception.AuthenticationFailedException;
import com.carddemo.exception.CardDemoException;
import com.carddemo.exception.ConcurrencyException;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.TransactionPostingException;
import com.carddemo.exception.ValidationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Centralized translation of CardDemo domain exceptions and Spring MVC binding
 * errors into RFC 7807 ProblemDetail HTTP responses. Re-platforms the COBOL
 * FILE STATUS / ABEND handling surfaced by the CICS and batch programs
 * (reference only; lineage commit 27d6c6f). Registered at highest precedence so
 * domain-specific mappings win over framework defaults.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Client-safe detail for {@link FileAccessException} (HTTP 500). The raw exception message can
     * carry internal implementation diagnostics (S3 bucket/object keys, FILE STATUS codes), so it
     * is logged server-side only and never returned to the client (CWE-209). The correlation id
     * returned in the {@code X-Correlation-Id} response header lets support correlate the request
     * with the full server-side log entry.
     */
    private static final String FILE_ACCESS_SANITIZED_DETAIL =
            "A backend file or queue operation failed; please retry or contact support with your correlation ID.";

    /**
     * Client-safe detail for an unmapped {@link CardDemoException} (HTTP 500). Same rationale as
     * {@link #FILE_ACCESS_SANITIZED_DETAIL}: internal detail is logged server-side, not returned.
     */
    private static final String PROCESSING_SANITIZED_DETAIL =
            "An unexpected processing error occurred; please retry or contact support with your correlation ID.";

    /**
     * Client-safe detail for a request body that cannot be deserialized (HTTP 400). The raw
     * {@link HttpMessageNotReadableException} message can echo the offending JSON fragment, parser
     * line/column positions, and target type internals, so it is logged at debug server-side only
     * and a stable, non-leaking detail is returned to the client (CWE-209). This keeps the
     * malformed-body response shape identical to every other handled error (populated 400
     * ProblemDetail) regardless of whether the target endpoint is public or protected.
     */
    private static final String MALFORMED_BODY_DETAIL =
            "The request body could not be read or is not valid JSON.";

    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleRecordNotFound(RecordNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "Record Not Found", ex.getMessage());
    }

    @ExceptionHandler(DuplicateRecordException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateRecord(DuplicateRecordException ex) {
        return build(HttpStatus.CONFLICT, "Duplicate Record", ex.getMessage());
    }

    @ExceptionHandler(ConcurrencyException.class)
    public ResponseEntity<ProblemDetail> handleConcurrency(ConcurrencyException ex) {
        return build(HttpStatus.CONFLICT, "Concurrent Update Conflict", ex.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ProblemDetail> handleValidation(ValidationException ex) {
        return build(HttpStatus.BAD_REQUEST, "Validation Error", ex.getMessage());
    }

    @ExceptionHandler(TransactionPostingException.class)
    public ResponseEntity<ProblemDetail> handleTransactionPosting(TransactionPostingException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "Transaction Posting Rejected", ex.getMessage());
    }

    @ExceptionHandler(FileAccessException.class)
    public ResponseEntity<ProblemDetail> handleFileAccess(FileAccessException ex) {
        // Log full diagnostics (message + stack trace) server-side; the correlation id is attached
        // automatically via MDC. Return a sanitized, stable detail so internal file/queue
        // identifiers (S3 bucket/object keys, FILE STATUS codes) are never exposed (CWE-209).
        log.error("File access failure handled at API boundary", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "File Access Error", FILE_ACCESS_SANITIZED_DETAIL);
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationFailed(AuthenticationFailedException ex) {
        // Sign-on failure (unknown user OR wrong password). The service has already generalized the
        // outcome to a single client-safe message and recorded the specific reason in metrics/logs, so
        // every authentication failure returns an identical 401 and cannot be used to enumerate user
        // ids (CWE-204). This dedicated handler wins over the CardDemoException fallback (more specific
        // type) and keeps the failure at 401 rather than the fallback's sanitized 500.
        return build(HttpStatus.UNAUTHORIZED, "Authentication Failed", ex.getMessage());
    }

    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ProblemDetail> handleCardDemo(CardDemoException ex) {
        // Fallback for unmapped CardDemoException subclasses. Log full diagnostics server-side and
        // return a sanitized 500 detail so raw entity/status diagnostics are not leaked (CWE-209).
        log.error("Unhandled CardDemo processing failure at API boundary", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Processing Error", PROCESSING_SANITIZED_DETAIL);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (detail.isEmpty()) {
            detail = "Request validation failed";
        }
        return build(HttpStatus.BAD_REQUEST, "Validation Error", detail);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String detail = "Parameter '" + ex.getName() + "' has an invalid value";
        return build(HttpStatus.BAD_REQUEST, "Invalid Request Parameter", detail);
    }

    /**
     * Translates a body that the message converter cannot deserialize (malformed/syntactically
     * invalid JSON, wrong content type, empty required body) into a populated RFC 7807 400 response.
     * Without this mapping the framework default yields a 400 with a {@code null} title/detail on
     * protected endpoints, and on the public sign-in endpoint the unauthenticated {@code /error}
     * re-dispatch surfaces a misleading empty 401 — an inconsistent contract for adversarial input.
     * The raw exception detail is logged at debug only and never returned, keeping the response free
     * of parser internals and offending-fragment echoes (CWE-209).
     *
     * @param ex the converter failure raised while reading the request body
     * @return a 400 {@link ProblemDetail} with a stable, client-safe detail
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        log.debug("Malformed request body rejected at API boundary: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Malformed Request Body", MALFORMED_BODY_DETAIL);
    }

    private ResponseEntity<ProblemDetail> build(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? "" : detail);
        problem.setTitle(title);
        return ResponseEntity.status(status).body(problem);
    }
}
