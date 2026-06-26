/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.exception;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Centralized {@code @RestControllerAdvice} that maps application and framework
 * exceptions to RFC 7807 {@link ProblemDetail} HTTP responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private static final String ERROR_TYPE_BASE = "https://carddemo/errors/";

    private static final String GENERIC_INTERNAL_DETAIL =
            "An internal error occurred. Please contact support if the problem persists.";

    private static final String GENERIC_NOT_FOUND_DETAIL =
            "The requested resource could not be found.";

    private static final String GENERIC_DUPLICATE_DETAIL =
            "A resource with the supplied identifier already exists.";

    private static final String UNKNOWN_ENTITY_TYPE = "unknown";

    private static final String TITLE_VALIDATION_FAILED = "Validation Failed";

    private static final String TITLE_CONCURRENT_UPDATE = "Concurrent Update Conflict";

    private static final String TITLE_INTERNAL_ERROR = "Internal Error";

    private static final String SLUG_VALIDATION = "validation";

    private static final String SLUG_CONCURRENT_UPDATE = "concurrent-update";

    private static final String SLUG_INTERNAL = "internal";

    private static final String TITLE_MALFORMED_REQUEST = "Malformed Request";

    private static final String DETAIL_MALFORMED_REQUEST =
            "The request body could not be read; ensure it is well-formed JSON.";

    private static final String SLUG_MALFORMED_REQUEST = "malformed-request";

    private static final String TITLE_TYPE_MISMATCH = "Invalid Parameter";

    private static final String DETAIL_TYPE_MISMATCH =
            "A request parameter has an invalid format.";

    private static final String SLUG_TYPE_MISMATCH = "type-mismatch";

    private static final String TITLE_UNSUPPORTED_MEDIA_TYPE = "Unsupported Media Type";

    private static final String DETAIL_UNSUPPORTED_MEDIA_TYPE =
            "The request Content-Type is not supported; use application/json.";

    private static final String SLUG_UNSUPPORTED_MEDIA_TYPE = "unsupported-media-type";

    private static final String TITLE_METHOD_NOT_ALLOWED = "Method Not Allowed";

    private static final String DETAIL_METHOD_NOT_ALLOWED =
            "The HTTP method is not supported for this resource.";

    private static final String SLUG_METHOD_NOT_ALLOWED = "method-not-allowed";

    private static final String TITLE_NOT_FOUND = "Resource Not Found";

    private static final String SLUG_NOT_FOUND = "not-found";

    private static final String TITLE_DATA_CONFLICT = "Data Conflict";

    private static final String DETAIL_DATA_CONFLICT =
            "The request could not be completed because it conflicts with the current state of the data.";

    private static final String SLUG_DATA_CONFLICT = "data-conflict";

    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleRecordNotFound(RecordNotFoundException ex,
            HttpServletRequest request) {
        // Log only the non-sensitive entity type plus the correlation ID (added by problem()).
        // The entity-typed constructor's key can contain record identifiers (card numbers, account,
        // customer, or user IDs) and must never be logged (R1).
        log.warn("Record not found -> 404 (entityType={})", safeEntityType(ex.getEntityType()));
        // Detail selection preserves byte-exact COBOL parity (AAP 0.7.1.1) without leaking keys (R1):
        //   - entity-typed ctor (entityType present): the message embeds the lookup key, so emit the
        //     generic, key-free, entity-aware detail derived solely from the non-sensitive type;
        //   - message-only ctor (entityType absent): the message is a deliberate key-free legacy
        //     literal (e.g. "User ID NOT found...", "Transaction ID NOT found..."), so surface it
        //     verbatim to reproduce the on-screen COBOL text.
        String entityType = ex.getEntityType();
        String detail = (entityType != null && !entityType.isBlank())
                ? notFoundDetail(entityType)
                : messageOrDefault(ex.getMessage(), GENERIC_NOT_FOUND_DETAIL);
        ProblemDetail body = problem(HttpStatus.NOT_FOUND, TITLE_NOT_FOUND,
                detail, SLUG_NOT_FOUND, request);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(DuplicateRecordException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateRecord(DuplicateRecordException ex,
            HttpServletRequest request) {
        // Sanitized logging: the entity-typed constructor's key embeds the record identifier, so only
        // the non-sensitive entity type is logged (R1).
        log.warn("Duplicate record -> 409 (entityType={})", safeEntityType(ex.getEntityType()));
        // Detail selection mirrors handleRecordNotFound: entity-typed ctor -> generic key-free detail
        // (R1); message-only ctor -> the byte-exact key-free legacy literal (e.g.
        // "User ID already exist...", "Tran ID already exist...") surfaced verbatim (AAP 0.7.1.1).
        String entityType = ex.getEntityType();
        String detail = (entityType != null && !entityType.isBlank())
                ? duplicateDetail(entityType)
                : messageOrDefault(ex.getMessage(), GENERIC_DUPLICATE_DETAIL);
        ProblemDetail body = problem(HttpStatus.CONFLICT, "Duplicate Resource",
                detail, "duplicate", request);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(ConcurrentUpdateException.class)
    public ResponseEntity<ProblemDetail> handleConcurrentUpdate(ConcurrentUpdateException ex,
            HttpServletRequest request) {
        log.warn("Concurrent update -> 409: {}", ex.getMessage());
        ProblemDetail body = problem(HttpStatus.CONFLICT, TITLE_CONCURRENT_UPDATE,
                ex.getMessage(), SLUG_CONCURRENT_UPDATE, request);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ProblemDetail> handleValidation(ValidationException ex,
            HttpServletRequest request) {
        log.warn("Validation failed -> 400: {}", ex.getMessage());
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, TITLE_VALIDATION_FAILED,
                ex.getMessage(), SLUG_VALIDATION, request);
        body.setProperty("errors", ex.getFieldErrors());
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ProblemDetail> handleBusinessRule(BusinessRuleException ex,
            HttpServletRequest request) {
        log.warn("Business rule violation -> 422 (ruleCode={}): {}", ex.getRuleCode(), ex.getMessage());
        ProblemDetail body = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Business Rule Violation",
                ex.getMessage(), "business-rule", request);
        if (ex.getRuleCode() != null) {
            body.setProperty("ruleCode", ex.getRuleCode());
        }
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationFailed(AuthenticationFailedException ex,
            HttpServletRequest request) {
        log.warn("Authentication failed -> 401");
        ProblemDetail body = problem(HttpStatus.UNAUTHORIZED, "Authentication Failed",
                ex.getMessage(), "authentication", request);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(FileAccessException.class)
    public ResponseEntity<ProblemDetail> handleFileAccess(FileAccessException ex,
            HttpServletRequest request) {
        // 5xx server errors must not log the throwable: its message and stack frames can embed
        // record keys or upstream secrets (R1). Log only the non-sensitive exception type; the
        // correlation ID (added to MDC by problem()) ties this entry to the generic 500 response.
        log.error("File access failure -> 500 (exceptionType={})", ex.getClass().getName());
        ProblemDetail body = problem(HttpStatus.INTERNAL_SERVER_ERROR, TITLE_INTERNAL_ERROR,
                GENERIC_INTERNAL_DETAIL, SLUG_INTERNAL, request);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.merge(fieldError.getField(),
                    fieldError.getDefaultMessage() == null ? "" : fieldError.getDefaultMessage(),
                    (existing, addition) -> existing + "; " + addition);
        }
        log.warn("Bean validation failed -> 400: {} field error(s)", errors.size());
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, TITLE_VALIDATION_FAILED,
                "Request validation failed", SLUG_VALIDATION, request);
        body.setProperty("errors", errors);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
            HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getConstraintViolations().stream()
                .sorted(Comparator
                        .comparing((ConstraintViolation<?> violation) -> violation.getPropertyPath().toString())
                        .thenComparing(violation -> violation.getMessage() == null ? "" : violation.getMessage()))
                .forEach(violation -> errors.merge(
                        violation.getPropertyPath().toString(),
                        violation.getMessage() == null ? "" : violation.getMessage(),
                        (existing, addition) -> existing + "; " + addition));
        log.warn("Constraint violation -> 400: {} violation(s)", errors.size());
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, TITLE_VALIDATION_FAILED,
                "Request validation failed", SLUG_VALIDATION, request);
        body.setProperty("errors", errors);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler({OptimisticLockException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<ProblemDetail> handleOptimisticLock(RuntimeException ex,
            HttpServletRequest request) {
        // Log only the non-sensitive exception type: ex.toString() embeds the provider message,
        // which can include entity and key detail (R1). The caller receives the generic 409 detail.
        log.warn("Optimistic lock conflict -> 409 (exceptionType={})", ex.getClass().getName());
        ProblemDetail body = problem(HttpStatus.CONFLICT, TITLE_CONCURRENT_UPDATE,
                ConcurrentUpdateException.DEFAULT_MESSAGE, SLUG_CONCURRENT_UPDATE, request);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex,
            HttpServletRequest request) {
        log.warn("Access denied -> 403: {}", ex.getMessage());
        ProblemDetail body = problem(HttpStatus.FORBIDDEN, "Access Denied",
                "Access is denied.", "access-denied", request);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    // ------------------------------------------------------------------
    // Framework request errors (Spring MVC) -> correct 4xx client status.
    //
    // Without explicit handlers, these standard request-processing exceptions
    // fall through to handleUnexpected(Exception.class) and render as HTTP 500
    // logged at ERROR, mislabeling client mistakes as server faults and raising
    // false 5xx alerts. Each reuses the shared RFC 7807 problem() envelope and
    // is logged at WARN (a client error, not a server fault). The exception
    // message is never surfaced in the body (it can echo fragments of the bad
    // request); a fixed, safe detail is returned instead (R1).
    // ------------------------------------------------------------------

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        // Malformed, empty, or missing request body (for example, invalid JSON).
        log.warn("Unreadable request body -> 400 (exceptionType={})", ex.getClass().getName());
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, TITLE_MALFORMED_REQUEST,
                DETAIL_MALFORMED_REQUEST, SLUG_MALFORMED_REQUEST, request);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        // A path variable or request parameter could not be converted to its declared type
        // (for example, a non-numeric value supplied for a Long-typed account id or filter).
        // This is a client mistake, not a server fault, so it MUST be 400 rather than falling
        // through to handleUnexpected as a 500. Log only the parameter name (safe); the rejected
        // value (ex.getValue()) is never logged or surfaced, as it can echo adversarial input (R1).
        log.warn("Parameter type mismatch -> 400 (name={})", ex.getName());
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, TITLE_TYPE_MISMATCH,
                DETAIL_TYPE_MISMATCH, SLUG_TYPE_MISMATCH, request);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
            HttpServletRequest request) {
        // The request Content-Type is not one the endpoint consumes (for example, text/plain).
        log.warn("Unsupported media type -> 415 (contentType={})", ex.getContentType());
        ProblemDetail body = problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, TITLE_UNSUPPORTED_MEDIA_TYPE,
                DETAIL_UNSUPPORTED_MEDIA_TYPE, SLUG_UNSUPPORTED_MEDIA_TYPE, request);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        // A valid path invoked with an unsupported HTTP method (for example, POST on a GET resource).
        log.warn("Method not allowed -> 405 (method={})", ex.getMethod());
        ProblemDetail body = problem(HttpStatus.METHOD_NOT_ALLOWED, TITLE_METHOD_NOT_ALLOWED,
                DETAIL_METHOD_NOT_ALLOWED, SLUG_METHOD_NOT_ALLOWED, request);
        // RFC 7231 6.5.5: a 405 response MUST carry an Allow header listing the methods the
        // resource supports, so HTTP and CORS-preflight clients can recover.
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(body.getStatus());
        if (supported != null && !supported.isEmpty()) {
            builder.allow(supported.toArray(new HttpMethod[0]));
        }
        return builder.body(body);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ProblemDetail> handleNoHandlerFound(Exception ex,
            HttpServletRequest request) {
        // An unmapped path: no controller handler and no static resource matched the request.
        log.warn("No handler for request -> 404 (exceptionType={})", ex.getClass().getName());
        ProblemDetail body = problem(HttpStatus.NOT_FOUND, TITLE_NOT_FOUND,
                GENERIC_NOT_FOUND_DETAIL, SLUG_NOT_FOUND, request);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex,
            HttpServletRequest request) {
        // A database integrity constraint (for example, a primary-key collision on a generated
        // transaction id under concurrent writes) is a client-resolvable conflict with the current
        // data state, NOT a server fault. It is more specific than DataAccessException, so this
        // handler takes precedence and maps it to 409 rather than letting it fall through to the
        // 500 handleDataAccess path (QA Issue #6). The services retry transaction-id collisions
        // internally and only surface a DuplicateRecordException after exhaustion; this handler is
        // the safety net that guarantees a stray integrity violation never becomes a 500.
        // Log at WARN with only the exception type: the throwable message can embed SQL fragments
        // and rejected values and must never be logged or surfaced (R1).
        log.warn("Data integrity violation -> 409 (exceptionType={})", ex.getClass().getName());
        ProblemDetail body = problem(HttpStatus.CONFLICT, TITLE_DATA_CONFLICT,
                DETAIL_DATA_CONFLICT, SLUG_DATA_CONFLICT, request);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> handleDataAccess(DataAccessException ex,
            HttpServletRequest request) {
        // See handleFileAccess: never log the throwable for a 5xx; log only the exception type (R1).
        log.error("Data access failure -> 500 (exceptionType={})", ex.getClass().getName());
        ProblemDetail body = problem(HttpStatus.INTERNAL_SERVER_ERROR, TITLE_INTERNAL_ERROR,
                GENERIC_INTERNAL_DETAIL, SLUG_INTERNAL, request);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex,
            HttpServletRequest request) {
        // See handleFileAccess: never log the throwable for a 5xx; log only the exception type (R1).
        log.error("Unhandled exception -> 500 (exceptionType={})", ex.getClass().getName());
        ProblemDetail body = problem(HttpStatus.INTERNAL_SERVER_ERROR, TITLE_INTERNAL_ERROR,
                GENERIC_INTERNAL_DETAIL, SLUG_INTERNAL, request);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    /**
     * Returns the supplied entity type when present, or a non-sensitive
     * placeholder otherwise. The entity type is a domain or operation label
     * (for example {@code "Account"} or {@code "READ ACCTFILE"}) and never
     * contains a record key, so it is safe for diagnostic logging.
     *
     * @param entityType the entity type carried by the exception, may be {@code null}
     * @return a non-sensitive entity-type label, never {@code null}
     */
    private static String safeEntityType(String entityType) {
        return (entityType != null && !entityType.isBlank()) ? entityType : UNKNOWN_ENTITY_TYPE;
    }

    /**
     * Builds a generic not-found detail that never includes the record key. The
     * entity type is included only when it is a non-sensitive label.
     *
     * @param entityType the entity type carried by the exception, may be {@code null}
     * @return a key-free detail string for the 404 response body
     */
    private static String notFoundDetail(String entityType) {
        return (entityType != null && !entityType.isBlank())
                ? "The requested " + entityType + " could not be found."
                : GENERIC_NOT_FOUND_DETAIL;
    }

    /**
     * Builds a generic duplicate-resource detail that never includes the record
     * key. The entity type is included only when it is a non-sensitive label.
     *
     * @param entityType the entity type carried by the exception, may be {@code null}
     * @return a key-free detail string for the 409 response body
     */
    private static String duplicateDetail(String entityType) {
        return (entityType != null && !entityType.isBlank())
                ? "A " + entityType + " with the supplied identifier already exists."
                : GENERIC_DUPLICATE_DETAIL;
    }

    /**
     * Returns {@code message} when it is present and non-blank, otherwise the
     * supplied generic {@code fallback}. Used by the not-found and duplicate
     * handlers to surface a deliberate, key-free legacy literal carried by the
     * message-only exception constructor (byte-exact COBOL parity, AAP 0.7.1.1)
     * while never returning a {@code null} or empty detail.
     *
     * @param message  the exception message, may be {@code null} or blank
     * @param fallback the non-sensitive generic detail to use when no usable
     *                 message is present, never {@code null}
     * @return the message when usable, otherwise the fallback
     */
    private static String messageOrDefault(String message, String fallback) {
        return (message != null && !message.isBlank()) ? message : fallback;
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail,
            String typeSlug, HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        body.setType(URI.create(ERROR_TYPE_BASE + typeSlug));
        if (request != null) {
            String requestUri = request.getRequestURI();
            if (requestUri != null && !requestUri.isEmpty()) {
                body.setInstance(URI.create(requestUri));
            }
        }
        body.setProperty("timestamp", OffsetDateTime.now());
        String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
        if (correlationId != null) {
            body.setProperty("correlationId", correlationId);
        }
        return body;
    }
}
