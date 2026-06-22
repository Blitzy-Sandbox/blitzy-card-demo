package com.carddemo.controller;

import com.carddemo.exception.CardDemoException;
import com.carddemo.exception.ConcurrencyException;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.TransactionPostingException;
import com.carddemo.exception.ValidationException;
import jakarta.persistence.OptimisticLockException;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /** RFC 7807 title shared by every 409 optimistic-concurrency conflict response. */
    private static final String CONCURRENCY_CONFLICT_TITLE = "Concurrent Update Conflict";

    /**
     * Client-facing detail for a 409 optimistic-lock conflict. Byte-identical to the COACTUPC
     * redisplay message ({@code app/cbl/COACTUPC.cbl} L522, reference only; lineage commit
     * {@code 27d6c6f}) that the domain {@link ConcurrencyException} also carries, so a conflict
     * surfaced by the persistence framework and one thrown by the service layer return the same body.
     */
    private static final String CONCURRENCY_CONFLICT_DETAIL =
            "Record changed by some one else. Please review";

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
        return build(HttpStatus.CONFLICT, CONCURRENCY_CONFLICT_TITLE, ex.getMessage());
    }

    /**
     * Translates a JPA/Hibernate optimistic-locking failure into the same 409 RFC 7807
     * {@link ProblemDetail} as the domain {@link ConcurrencyException}, completing the AAP
     * &sect;0.8.4 "{@code @Version} optimistic locking ... with {@code OptimisticLockException}
     * handling" contract on EVERY concurrent-update path.
     *
     * <p>Most concurrent updates are caught inside the service layer (for example
     * {@code CardUpdateService} and the dirty-account path of {@code AccountUpdateService}), which
     * wraps the failure as a {@link ConcurrencyException} at {@code saveAndFlush} time. The
     * customer-only / no-field-change account path, however, advances the account version with a
     * {@code LockModeType.OPTIMISTIC_FORCE_INCREMENT} lock whose increment Hibernate defers to
     * {@code beforeTransactionCompletion} (transaction commit) — AFTER the {@code @Transactional}
     * service method and its local {@code try/catch} have returned. The resulting
     * {@link ObjectOptimisticLockingFailureException} (or a {@link OptimisticLockException} surfaced
     * directly by the provider) therefore escapes the service and must be mapped here so the client
     * still receives the 409 conflict contract rather than a 500.
     *
     * <p>Data integrity is unaffected: the optimistic lock has already rolled back the losing
     * transaction (exactly one writer wins, versions advance by one), so this handler only corrects
     * the HTTP status mapping. The framework message can embed the entity name and identifier, so
     * per CWE-209 it is logged server-side only and the client receives the fixed COACTUPC-parity
     * detail.
     *
     * @param ex the optimistic-lock failure raised by the persistence layer (Spring's
     *           {@link ObjectOptimisticLockingFailureException} or the JPA
     *           {@link OptimisticLockException})
     * @return a {@code 409 Conflict} response whose body is identical to the domain
     *         {@link ConcurrencyException} mapping
     */
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ProblemDetail> handleOptimisticLock(Exception ex) {
        // Entity/identifier internals are logged server-side only (CWE-209); the client receives
        // only the fixed, non-sensitive concurrency detail below.
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, CONCURRENCY_CONFLICT_TITLE, CONCURRENCY_CONFLICT_DETAIL);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ProblemDetail> handleValidation(ValidationException ex) {
        return build(HttpStatus.BAD_REQUEST, "Validation Error", ex.getMessage());
    }

    /**
     * Translates a database integrity violation (for example a primary-key or unique-constraint
     * collision raised by the persistence provider on flush) into a 409 RFC 7807 ProblemDetail,
     * keeping conflict responses consistent with the domain {@link DuplicateRecordException} and
     * {@link ConcurrencyException} handlers above. The framework exception message can embed the
     * offending SQL, constraint, table, and column names; per CWE-209 that detail is logged
     * server-side only and the client receives a fixed, non-sensitive message.
     *
     * @param ex the data-integrity failure raised by the persistence layer
     * @return a {@code 409 Conflict} response with a sanitized RFC 7807 problem body
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        // Constraint/SQL/column internals are logged server-side only (CWE-209); the client receives
        // only the generic, non-sensitive detail below.
        log.warn("Data integrity violation: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "Data Conflict",
                "The request conflicts with existing data");
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

    /**
     * Translates an unsupported request {@code Content-Type} into a 415 RFC 7807 ProblemDetail so the
     * media-type error shares the same envelope as the handled domain errors (the framework default
     * {@code {timestamp,status,error,path}} envelope is replaced). The list of supported media types is
     * the API's own published contract (for example {@code application/json}) and is safe to surface; the
     * client-sent content type is not echoed.
     *
     * @param ex the framework media-type exception
     * @return a 415 response carrying the RFC 7807 ProblemDetail
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        String detail = ex.getSupportedMediaTypes().isEmpty()
                ? "The request media type is not supported"
                : "The request media type is not supported; supported types: " + ex.getSupportedMediaTypes();
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type", detail);
    }

    /**
     * Translates a no-handler / no-static-resource match (unmapped route) into a 404 RFC 7807
     * ProblemDetail so unmapped-route errors share the same envelope as the handled domain errors
     * (the framework default {@code {timestamp,status,error,path}} envelope is replaced). The requested
     * path is logged server-side only and is deliberately NOT echoed in the client response (CWE-209):
     * the client already knows the path it requested, and not reflecting it avoids surfacing internal
     * routing detail.
     *
     * @param ex the framework no-resource-found exception
     * @return a 404 response carrying the RFC 7807 ProblemDetail
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResourceFound(NoResourceFoundException ex) {
        log.debug("No resource found for request path: {}", ex.getResourcePath());
        return build(HttpStatus.NOT_FOUND, "Resource Not Found", "The requested resource was not found");
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
