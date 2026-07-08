package com.carddemo.controller;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.carddemo.dto.ErrorResponse;
import com.carddemo.exception.CardDemoException;
import com.carddemo.exception.ValidationException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

/**
 * Centralized REST exception&rarr;HTTP mapper for the migrated CardDemo service.
 *
 * <p>This is the single {@link RestControllerAdvice} for the whole application: it converts every
 * exception raised by the eight REST controllers (and the services they delegate to) into a
 * structured {@link ErrorResponse} JSON body with the correct HTTP status. It is the modern
 * replacement for the legacy COBOL {@code FILE STATUS} inspection, the {@code 9999-ABEND-PROGRAM}
 * abend path, and the on-screen {@code ERRMSG}/{@code INFOMSG} message plumbing that was scattered
 * across the eighteen online programs (source referenced by commit SHA {@code 27d6c6f}).
 *
 * <p>The class extends {@link ResponseEntityExceptionHandler} so Spring MVC's own framework
 * exceptions (unreadable body, unsupported method, unknown path, and so on) keep their correct
 * status codes instead of being swallowed by the {@link #handleGeneric(Exception, HttpServletRequest)
 * 500 fallback}; the inherited, more specific handlers always win over the catch-all.
 *
 * <p><strong>Security:</strong> no client-facing {@code message} or {@code fieldErrors} ever carries
 * a stack trace, SQL, secret, password, JWT, or unmasked PAN; failed sign-on is reported with a
 * single generic message so the API never reveals whether an account exists. Design rationale and
 * the COBOL-paragraph mappings live in {@code docs/decision-log.md} and
 * {@code docs/traceability-matrix.md} (Explainability rule), not in verbose code comments.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Structured logger; every log line carries the MDC {@code correlationId} (Observability rule). */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * MDC key holding the per-request correlation id. Kept as a literal (rather than importing
     * {@code observability.CorrelationIdFilter.CORRELATION_ID_MDC_KEY}) to avoid a dependency outside
     * this file's allowed set; it must remain exactly {@code "correlationId"} to stay aligned with the
     * correlation-id filter, the batch job listener, and {@code logback-spring.xml}.
     */
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /** Domain error code for Jakarta Validation failures (field edits migrated from the BMS screens). */
    private static final String CODE_VALIDATION_ERROR = "VALIDATION_ERROR";

    /** Generic, leak-free message for a validation failure summary. */
    private static final String MESSAGE_VALIDATION_FAILED = "Validation failed";

    /**
     * Handles the entire {@link CardDemoException} hierarchy with one method.
     *
     * <p>Because every one of the seven typed exceptions carries its own {@link HttpStatus} via
     * {@link CardDemoException#getHttpStatus()}, this single handler yields the AAP-mandated status for
     * each subtype without inspecting concrete types: 404 (resource not found), 409 (duplicate /
     * optimistic-lock conflict), 400 (validation / date validation), and 500 (fatal file processing).
     * The machine-readable {@code code} surfaces the originating COBOL {@code FILE STATUS} semantics
     * when present (for example {@code RECORD_NOT_FOUND}, {@code DUPLICATE_KEY}), otherwise the simple
     * class name. A {@link ValidationException} additionally contributes its field&rarr;message map as
     * {@code fieldErrors} (values only, never raw input).
     *
     * @param ex      the raised domain exception (never {@code null})
     * @param request the current request, used for the {@code path} field (never {@code null})
     * @return a {@link ResponseEntity} with the exception's HTTP status and an {@link ErrorResponse} body
     */
    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ErrorResponse> handleCardDemoException(final CardDemoException ex,
                                                                 final HttpServletRequest request) {
        final HttpStatus status = ex.getHttpStatus();
        final String code = (ex.getFileStatusCode() != null)
                ? ex.getFileStatusCode().name()
                : ex.getClass().getSimpleName();

        List<ErrorResponse.FieldError> fieldErrors = null;
        if (ex instanceof ValidationException validationException
                && !validationException.getFieldErrors().isEmpty()) {
            fieldErrors = new ArrayList<>(validationException.getFieldErrors().size());
            for (final var entry : validationException.getFieldErrors().entrySet()) {
                // No raw value: the field->message map never holds the rejected input, so nothing to mask.
                fieldErrors.add(new ErrorResponse.FieldError(entry.getKey(), null, entry.getValue()));
            }
        }

        logHandled(ex, status, code);
        return ResponseEntity.status(status).body(buildError(status, code, ex.getMessage(), request, fieldErrors));
    }

    /**
     * Maps a {@code @Valid @RequestBody} DTO validation failure to HTTP 400 with per-field detail.
     *
     * <p>This overrides the framework handler (rather than declaring a duplicate {@code @ExceptionHandler}
     * for {@link MethodArgumentNotValidException}, which would be an ambiguous mapping) and reproduces the
     * COBOL per-field edits (for example "Account ID must be Numeric", "Type CD can NOT be empty"). The
     * rejected value is deliberately dropped from the response &mdash; it could be a password or PAN.
     *
     * @param ex      the validation exception carrying the binding result (never {@code null})
     * @param headers the headers to use for the response (never {@code null})
     * @param status  the status computed by the framework, HTTP 400 (never {@code null})
     * @param request the current request (never {@code null})
     * @return a {@link ResponseEntity} (HTTP 400) with an {@link ErrorResponse} body listing the field errors
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(final MethodArgumentNotValidException ex,
                                                                  final HttpHeaders headers,
                                                                  final HttpStatusCode status,
                                                                  final WebRequest request) {
        final List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        for (final var fieldError : ex.getBindingResult().getFieldErrors()) {
            // SECURITY: never echo fieldError.getRejectedValue() — pass null as the rejected-value summary.
            fieldErrors.add(new ErrorResponse.FieldError(fieldError.getField(), null, fieldError.getDefaultMessage()));
        }

        log.warn("Handled {} -> {} [{}]: {} field error(s)",
                ex.getClass().getSimpleName(), HttpStatus.BAD_REQUEST.value(),
                CODE_VALIDATION_ERROR, fieldErrors.size());

        final ErrorResponse body = buildError(HttpStatus.BAD_REQUEST, CODE_VALIDATION_ERROR,
                MESSAGE_VALIDATION_FAILED, servletRequestOf(request), fieldErrors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /**
     * Maps a method-level constraint violation (for example a {@code @Pattern} path variable or query
     * parameter under {@code @Validated}) to HTTP 400 with per-field detail.
     *
     * @param ex      the constraint-violation exception (never {@code null})
     * @param request the current request (never {@code null})
     * @return a {@link ResponseEntity} (HTTP 400) with an {@link ErrorResponse} body listing the violations
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(final ConstraintViolationException ex,
                                                                   final HttpServletRequest request) {
        final List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        if (ex.getConstraintViolations() != null) {
            for (final var violation : ex.getConstraintViolations()) {
                String field = null;
                for (final var node : violation.getPropertyPath()) {
                    field = node.getName();
                }
                // SECURITY: report the constraint message only, never the invalid value.
                fieldErrors.add(new ErrorResponse.FieldError(field, null, violation.getMessage()));
            }
        }

        final HttpStatus status = HttpStatus.BAD_REQUEST;
        log.warn("Handled {} -> {} [{}]: {} violation(s)",
                ex.getClass().getSimpleName(), status.value(), CODE_VALIDATION_ERROR, fieldErrors.size());
        return ResponseEntity.status(status)
                .body(buildError(status, CODE_VALIDATION_ERROR, MESSAGE_VALIDATION_FAILED, request, fieldErrors));
    }

    /**
     * Maps a failed sign-on to HTTP 401 with a deliberately generic message.
     *
     * <p>The legacy {@code COSGN00C} program distinguished "User not found. Try again ..." from "Wrong
     * Password. Try again ...", but the modern API returns a single message so it never leaks whether an
     * account exists (a decision-log-worthy security hardening). The submitted password is never logged.
     *
     * @param ex      the authentication failure (never {@code null}); its detail is intentionally not surfaced
     * @param request the current request (never {@code null})
     * @return a {@link ResponseEntity} (HTTP 401) with a generic {@link ErrorResponse} body
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(final BadCredentialsException ex,
                                                              final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.UNAUTHORIZED;
        final String code = "AUTHENTICATION_FAILED";
        // Do NOT log ex.getMessage(): it may reveal the submitted user id or the user-exists/wrong-password path.
        log.warn("Handled {} -> {} [{}]", ex.getClass().getSimpleName(), status.value(), code);
        return ResponseEntity.status(status)
                .body(buildError(status, code, "Invalid user id or password", request, null));
    }

    /**
     * Maps an authorization denial (for example a non-admin hitting an admin-only endpoint via
     * {@code @PreAuthorize}) to HTTP 403.
     *
     * <p>Included defensively: in this application the security filter chain's {@code AccessDeniedHandler}
     * usually renders filter-level denials with the same JSON shape before they reach this advice, so this
     * method covers dispatcher-level denials and is otherwise simply never invoked &mdash; a non-conflicting
     * safety net.
     *
     * @param ex      the access-denied exception (never {@code null}); its detail is intentionally not surfaced
     * @param request the current request (never {@code null})
     * @return a {@link ResponseEntity} (HTTP 403) with a generic {@link ErrorResponse} body
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(final AccessDeniedException ex,
                                                            final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.FORBIDDEN;
        final String code = "ACCESS_DENIED";
        log.warn("Handled {} -> {} [{}]", ex.getClass().getSimpleName(), status.value(), code);
        return ResponseEntity.status(status)
                .body(buildError(status, code, "Access is denied", request, null));
    }

    /**
     * Last-resort fallback mapping any otherwise-unhandled exception to HTTP 500.
     *
     * <p>This is the modern equivalent of the COBOL {@code 9999-ABEND-PROGRAM} path. The full throwable is
     * logged server-side at {@code ERROR} (with the correlation id in the MDC), but the client only ever
     * sees a fixed, generic message &mdash; {@code ex.getMessage()}, stack traces, SQL, and secrets are
     * never exposed.
     *
     * @param ex      the unexpected exception (never {@code null})
     * @param request the current request (never {@code null})
     * @return a {@link ResponseEntity} (HTTP 500) with a generic {@link ErrorResponse} body
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(final Exception ex, final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        final String code = "INTERNAL_ERROR";
        log.error("Unhandled exception -> {} [{}]", status.value(), code, ex);
        return ResponseEntity.status(status)
                .body(buildError(status, code, "An unexpected error occurred", request, null));
    }

    /**
     * Builds the standardized {@link ErrorResponse} body, stamping the current server time, the request
     * URI, and the MDC correlation id. Null-safe: a {@code null} request yields a {@code null} path and a
     * {@code null} correlation id is passed through unchanged (never fabricated).
     *
     * @param status      the HTTP status (never {@code null})
     * @param code        the machine-readable domain error code (may be {@code null})
     * @param message     the leak-free, human-readable message (may be {@code null})
     * @param request     the current request, used for the {@code path} field (may be {@code null})
     * @param fieldErrors the per-field validation failures, or {@code null} when not a validation failure
     * @return a new immutable {@link ErrorResponse}
     */
    private ErrorResponse buildError(final HttpStatus status,
                                     final String code,
                                     final String message,
                                     final HttpServletRequest request,
                                     final List<ErrorResponse.FieldError> fieldErrors) {
        final String path = (request != null) ? request.getRequestURI() : null;
        return new ErrorResponse(
                OffsetDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                path,
                MDC.get(CORRELATION_ID_MDC_KEY),
                fieldErrors);
    }

    /**
     * Emits a structured log line for a handled exception: client errors (4xx) at {@code WARN} with a
     * concise message, server errors (5xx) at {@code ERROR} with the full throwable for diagnosis.
     *
     * @param ex     the handled exception (never {@code null})
     * @param status the resolved HTTP status (never {@code null})
     * @param code   the machine-readable domain error code (may be {@code null})
     */
    private static void logHandled(final Exception ex, final HttpStatus status, final String code) {
        if (status.is5xxServerError()) {
            log.error("Handled {} -> {} [{}]", ex.getClass().getSimpleName(), status.value(), code, ex);
        } else {
            log.warn("Handled {} -> {} [{}]: {}",
                    ex.getClass().getSimpleName(), status.value(), code, ex.getMessage());
        }
    }

    /**
     * Extracts the underlying {@link HttpServletRequest} from a Spring {@link WebRequest}, so the
     * framework-signature handlers can reuse the servlet-based {@link #buildError} helper.
     *
     * @param request the Spring web request (may be {@code null})
     * @return the underlying servlet request, or {@code null} if it is not a servlet request
     */
    private static HttpServletRequest servletRequestOf(final WebRequest request) {
        return (request instanceof ServletWebRequest servletWebRequest) ? servletWebRequest.getRequest() : null;
    }
}
