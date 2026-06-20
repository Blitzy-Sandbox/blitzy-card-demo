package com.carddemo.controller;

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
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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

    /** Structured logger; emitted server-side only (Observability rule, AAP 0.7.1). */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Generic, sanitized client-facing detail for 500-level responses (CWE-209 mitigation). Internal
     * implementation details — S3 bucket/object paths, datasource URLs, stack traces — are logged
     * server-side only and are NEVER placed in the response body. Clients receive this constant plus
     * a {@code correlationId} property to reference the corresponding server-side log entry.
     */
    private static final String GENERIC_INTERNAL_DETAIL = "Internal processing error";

    /** SLF4J MDC key carrying the per-request correlation id (set by {@code CorrelationIdFilter}). */
    private static final String MDC_CORRELATION_KEY = "correlationId";

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
        // Full detail (which may include internal S3 bucket/object paths) is logged server-side only;
        // the correlationId is attached to the structured log automatically via the MDC.
        log.error("File access error: {}", ex.getMessage(), ex);
        // Return a sanitized, generic detail to the client (CWE-209): no internal paths/resource
        // names are exposed.
        return buildInternalError("File Access Error");
    }

    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ProblemDetail> handleCardDemo(CardDemoException ex) {
        // Catch-all for any domain exception not matched by a more specific (4xx) handler above.
        // Full detail logged server-side only; the client receives a sanitized, generic 500.
        log.error("Unhandled domain processing error: {}", ex.getMessage(), ex);
        return buildInternalError("Processing Error");
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

    private ResponseEntity<ProblemDetail> build(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? "" : detail);
        problem.setTitle(title);
        return ResponseEntity.status(status).body(problem);
    }

    /**
     * Builds a sanitized 500-level {@link ProblemDetail} that exposes no internal implementation
     * detail (CWE-209). The body carries only the generic {@link #GENERIC_INTERNAL_DETAIL} text and,
     * when available, the request {@code correlationId} so a client can reference the matching
     * server-side log entry. The caller is responsible for logging the underlying exception
     * server-side before invoking this method.
     *
     * @param title the short, non-sensitive RFC 7807 title for the error class
     * @return a {@code 500 Internal Server Error} response with a sanitized problem body
     */
    private ResponseEntity<ProblemDetail> buildInternalError(String title) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_INTERNAL_DETAIL);
        problem.setTitle(title);
        String correlationId = MDC.get(MDC_CORRELATION_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            problem.setProperty("correlationId", correlationId);
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
