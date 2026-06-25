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

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    private static final String TITLE_VALIDATION_FAILED = "Validation Failed";

    private static final String TITLE_CONCURRENT_UPDATE = "Concurrent Update Conflict";

    private static final String TITLE_INTERNAL_ERROR = "Internal Error";

    private static final String SLUG_VALIDATION = "validation";

    private static final String SLUG_CONCURRENT_UPDATE = "concurrent-update";

    private static final String SLUG_INTERNAL = "internal";

    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleRecordNotFound(RecordNotFoundException ex,
            HttpServletRequest request) {
        log.warn("Record not found -> 404: {}", ex.getMessage());
        ProblemDetail body = problem(HttpStatus.NOT_FOUND, "Resource Not Found",
                ex.getMessage(), "not-found", request);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(DuplicateRecordException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateRecord(DuplicateRecordException ex,
            HttpServletRequest request) {
        log.warn("Duplicate record -> 409: {}", ex.getMessage());
        ProblemDetail body = problem(HttpStatus.CONFLICT, "Duplicate Resource",
                ex.getMessage(), "duplicate", request);
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
        log.error("File access failure -> 500", ex);
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
        log.warn("Optimistic lock conflict -> 409: {}", ex.toString());
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

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> handleDataAccess(DataAccessException ex,
            HttpServletRequest request) {
        log.error("Data access failure -> 500", ex);
        ProblemDetail body = problem(HttpStatus.INTERNAL_SERVER_ERROR, TITLE_INTERNAL_ERROR,
                GENERIC_INTERNAL_DETAIL, SLUG_INTERNAL, request);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex,
            HttpServletRequest request) {
        log.error("Unhandled exception -> 500", ex);
        ProblemDetail body = problem(HttpStatus.INTERNAL_SERVER_ERROR, TITLE_INTERNAL_ERROR,
                GENERIC_INTERNAL_DETAIL, SLUG_INTERNAL, request);
        return ResponseEntity.status(body.getStatus()).body(body);
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
