package com.carddemo.controller;

import com.carddemo.exception.CardDemoException;
import com.carddemo.exception.ConcurrencyException;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.TransactionPostingException;
import com.carddemo.exception.ValidationException;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
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

    /**
     * Translates a missing, empty, or unparseable request body into a 400 RFC 7807 ProblemDetail.
     * Spring's {@code HttpMessageConverter} raises {@link HttpMessageNotReadableException} while
     * deserializing the body — before any controller method (or its {@code @Valid} binding) runs — so
     * without this handler the failure escapes the {@code @RestControllerAdvice}, is re-dispatched to
     * {@code /error}, and (on the public sign-in endpoint) is returned by the resource-server filter as
     * an empty {@code 401}. Handling it here unifies parse/coercion failures with the validation
     * contract above and keeps the response shape consistent for both public and authenticated routes.
     *
     * <p>The exception message can embed parser internals (Jackson type names, JSON-path fragments, and
     * source location offsets); per CWE-209 that detail is logged server-side only and the client
     * receives a fixed, non-sensitive message that reveals nothing about the parser or input position.
     *
     * @param ex the body-parse failure raised by the HTTP message converter
     * @return a {@code 400 Bad Request} response with a sanitized RFC 7807 problem body
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleNotReadable(HttpMessageNotReadableException ex) {
        // Parser internals (Jackson type/path/offset detail) are logged server-side only (CWE-209);
        // the client receives only the generic, non-sensitive detail below.
        log.warn("Unreadable request body: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Malformed Request",
                "Request body is missing or malformed");
    }

    /**
     * Translates an unsupported HTTP method on an otherwise-mapped route into a 405 RFC 7807
     * ProblemDetail and advertises the permitted methods in the mandatory {@code Allow} response header
     * (RFC 7231 &sect;6.5.5). Without this handler Spring renders its default (non-ProblemDetail) error
     * shape, so handling it here keeps the 405 response consistent with the rest of the error contract.
     * The supplied method name is echoed but carries no sensitive internal detail.
     *
     * @param ex the method-not-supported failure raised by Spring MVC
     * @return a {@code 405 Method Not Allowed} response with an RFC 7807 problem body and an
     *         {@code Allow} header listing the supported methods
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED,
                "Method " + ex.getMethod() + " is not supported for this endpoint");
        problem.setTitle("Method Not Allowed");
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            builder.allow(supported.toArray(new HttpMethod[0]));
        }
        return builder.body(problem);
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
