/*
 * WebConfig.java
 *
 * Spring MVC configuration and global exception handling for the CardDemo REST API.
 *
 * This configuration class provides three critical infrastructure components:
 * 1. CORS configuration — Enables cross-origin REST API access with observability
 *    header propagation (X-Correlation-Id).
 * 2. Jackson ObjectMapper customization — Ensures BigDecimal financial fields from
 *    COBOL COMP-3/COMP mappings serialize as plain decimal strings (never scientific
 *    notation) and java.time types serialize as ISO-8601 strings.
 * 3. Global exception handler — Maps the CardDemo exception hierarchy to appropriate
 *    HTTP status codes with structured JSON error responses, preserving COBOL FILE
 *    STATUS code traceability.
 *
 * COBOL Traceability:
 * - COBOL programs handled errors inline per program paragraph (GO TO error-paragraph,
 *   MOVE error-message TO screen field, SEND MAP). There was no centralized error
 *   handling facility in the BMS 3270 architecture.
 * - This class centralizes all REST API error handling, replacing the per-program
 *   error handling pattern with a single @ControllerAdvice.
 * - FILE STATUS codes (00, 22, 23, etc.) are preserved in the exception hierarchy
 *   and surfaced via the errorCode field in error responses.
 *
 * AAP References:
 * - §0.4.1: Spring Boot 3.5.x with Jakarta EE 10 APIs
 * - §0.7.1: Observability — correlation ID propagation in error responses
 * - §0.8.2: BigDecimal precision — no scientific notation in JSON serialization
 * - §0.8.4: Transaction/concurrency error mapping (SYNCPOINT → @Transactional)
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.config;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentModificationException;
import com.cardemo.exception.CreditLimitExceededException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.ExpiredCardException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.CommonsRequestLoggingFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring MVC configuration class providing REST API infrastructure for the
 * CardDemo application.
 *
 * <p>Configures CORS policies, Jackson JSON serialization, and request logging.
 * Also contains the centralized {@link GlobalExceptionHandler} and
 * {@link ErrorResponse} DTO used across all REST controller error responses.</p>
 *
 * <p>This class replaces the per-program error handling pattern found in the
 * original COBOL BMS 3270 architecture, where each of the 18 online programs
 * handled errors inline within their own paragraphs.</p>
 *
 * @see GlobalExceptionHandler
 * @see ErrorResponse
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Configures Cross-Origin Resource Sharing (CORS) for all API endpoints.
     *
     * <p>Allows all origins for development flexibility (production environments
     * should restrict origins via Spring profiles or environment configuration).
     * Exposes the {@code X-Correlation-Id} header to enable observability
     * correlation ID propagation to API consumers (AAP §0.7.1).</p>
     *
     * <p>Replaces COBOL BMS 3270 terminal I/O, which had no cross-origin
     * concerns as all interaction was via dedicated terminal sessions.</p>
     *
     * @param registry the {@link CorsRegistry} for registering CORS mappings
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Correlation-Id");
    }

    /**
     * Customizes the Jackson {@code ObjectMapper} for CardDemo-specific
     * serialization requirements.
     *
     * <p>Critical configuration for COBOL-to-Java data precision preservation:</p>
     * <ul>
     *   <li>{@code JavaTimeModule} — Enables ISO-8601 date serialization for
     *       {@code java.time} types, replacing COBOL LE CEEDAYS date handling</li>
     *   <li>{@code WRITE_DATES_AS_TIMESTAMPS=false} — Produces human-readable
     *       ISO-8601 strings instead of numeric epoch timestamps</li>
     *   <li>{@code WRITE_BIGDECIMAL_AS_PLAIN=true} — Ensures all BigDecimal
     *       financial fields from COBOL COMP-3/COMP mappings serialize as plain
     *       decimal strings (e.g., "1234.56") instead of scientific notation
     *       (e.g., "1.23456E3") per AAP §0.8.2</li>
     *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES=false} — Permissive deserialization
     *       for forward compatibility with evolving API contracts</li>
     *   <li>{@code NON_NULL} inclusion — Excludes null fields from JSON output,
     *       reducing payload size and producing cleaner API responses</li>
     * </ul>
     *
     * @return the Jackson customizer bean for Spring Boot auto-configuration
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .modulesToInstall(new JavaTimeModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .featuresToEnable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .serializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * Creates a request/response logging filter enabled only in the local
     * development profile.
     *
     * <p>Logs request URI, query string, and payload body for debugging.
     * Headers are excluded to prevent accidental logging of authentication
     * tokens or other sensitive data (AAP §0.8.1 — no hardcoded credentials
     * or sensitive data exposure).</p>
     *
     * <p>This filter is only active when {@code spring.profiles.active=local},
     * preventing verbose request logging in test, staging, or production
     * environments.</p>
     *
     * @return the configured {@link CommonsRequestLoggingFilter}
     */
    @Bean
    @Profile("local")
    public CommonsRequestLoggingFilter requestLoggingFilter() {
        CommonsRequestLoggingFilter filter = new CommonsRequestLoggingFilter();
        filter.setIncludeQueryString(true);
        filter.setIncludePayload(true);
        filter.setMaxPayloadLength(10000);
        filter.setIncludeHeaders(false);
        filter.setAfterMessagePrefix("REQUEST DATA: ");
        return filter;
    }

    /**
     * Structured error response DTO for all REST API error responses.
     *
     * <p>This record provides a consistent JSON error format across all exception
     * types handled by the {@link GlobalExceptionHandler}. It includes observability
     * fields ({@code correlationId}, {@code timestamp}) and COBOL traceability
     * fields ({@code errorCode}) for comprehensive error reporting.</p>
     *
     * <p>Example JSON output:</p>
     * <pre>{@code
     * {
     *   "status": 404,
     *   "error": "Not Found",
     *   "message": "Account not found with id: 00000000001",
     *   "errorCode": "RNF",
     *   "timestamp": "2026-03-17T10:15:30.123Z",
     *   "path": "/api/accounts/00000000001",
     *   "correlationId": "abc-123-def"
     * }
     * }</pre>
     *
     * @param status        the HTTP status code (e.g., 404, 409, 422, 500)
     * @param error         the HTTP status reason phrase (e.g., "Not Found")
     * @param message       the human-readable error message
     * @param errorCode     the application error code from the CardDemo exception
     *                      hierarchy (e.g., "RNF", "DUP", "LOCK", "CREDIT",
     *                      "EXPIRY", "VALID", "CARDDEMO_ERROR")
     * @param fieldErrors   optional list of per-field validation errors, each
     *                      containing "field", "rejectedValue", and "message" keys;
     *                      {@code null} when not a validation error
     * @param timestamp     the ISO-8601 UTC timestamp when the error occurred
     * @param path          the request URI that triggered the error
     * @param correlationId the correlation ID from MDC for observability tracing
     */
    public record ErrorResponse(
            int status,
            String error,
            String message,
            String errorCode,
            List<Map<String, String>> fieldErrors,
            String timestamp,
            String path,
            String correlationId
    ) { }

    /**
     * Global exception handler for the CardDemo REST API.
     *
     * <p>Maps the CardDemo exception hierarchy to appropriate HTTP status codes
     * with structured {@link ErrorResponse} JSON bodies. This centralized handler
     * replaces the per-program error handling pattern in the original COBOL BMS
     * 3270 architecture.</p>
     *
     * <h3>Exception-to-HTTP Status Mapping (from COBOL Error Patterns)</h3>
     * <table>
     *   <caption>Exception mappings from COBOL FILE STATUS and CICS RESP codes</caption>
     *   <tr><th>Exception</th><th>HTTP Status</th><th>COBOL Origin</th></tr>
     *   <tr><td>{@link RecordNotFoundException}</td><td>404</td>
     *       <td>FILE STATUS 23, DFHRESP(NOTFND)</td></tr>
     *   <tr><td>{@link DuplicateRecordException}</td><td>409</td>
     *       <td>FILE STATUS 22, DFHRESP(DUPKEY/DUPREC)</td></tr>
     *   <tr><td>{@link ConcurrentModificationException}</td><td>409</td>
     *       <td>CICS REWRITE failure, LOCKED-BUT-UPDATE-FAILED</td></tr>
     *   <tr><td>{@link CreditLimitExceededException}</td><td>422</td>
     *       <td>Reject code 102 (OVERLIMIT TRANSACTION)</td></tr>
     *   <tr><td>{@link ExpiredCardException}</td><td>422</td>
     *       <td>Reject code 103 (ACCT EXPIRATION)</td></tr>
     *   <tr><td>{@link ValidationException}</td><td>400</td>
     *       <td>COACTUPC 9700-CHECK-CHANGE-IN-REC</td></tr>
     *   <tr><td>{@link ConstraintViolationException}</td><td>400</td>
     *       <td>CSSETATY.cpy field attribute validation</td></tr>
     *   <tr><td>{@link CardDemoException}</td><td>500</td>
     *       <td>Catch-all for application errors</td></tr>
     *   <tr><td>{@link Exception}</td><td>500</td>
     *       <td>Generic catch-all (no detail exposure)</td></tr>
     * </table>
     */
    @RestControllerAdvice
    public static class GlobalExceptionHandler {

        private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

        /** MDC key for the request correlation ID injected by the observability filter. */
        private static final String CORRELATION_ID_KEY = "correlationId";

        /**
         * Handles {@link RecordNotFoundException} — COBOL FILE STATUS 23 / DFHRESP(NOTFND).
         *
         * <p>Returns HTTP 404 (Not Found) with structured error details. This maps
         * from the COBOL pattern where INVALID KEY on READ/START/DELETE operations
         * triggers a display message to the 3270 terminal.</p>
         *
         * @param ex      the record-not-found exception containing entity context
         * @param request the HTTP request for URI extraction
         * @return HTTP 404 response with structured error body
         */
        @ExceptionHandler(RecordNotFoundException.class)
        public ResponseEntity<ErrorResponse> handleRecordNotFoundException(
                RecordNotFoundException ex, HttpServletRequest request) {
            log.warn("Record not found [errorCode={}, fileStatus={}]: {}",
                    ex.getErrorCode(), ex.getFileStatusCode(), ex.getMessage());
            ErrorResponse response = buildErrorResponse(
                    HttpStatus.NOT_FOUND, ex.getMessage(), ex.getErrorCode(), null, request);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        /**
         * Handles {@link DuplicateRecordException} — COBOL FILE STATUS 22 / DFHRESP(DUPKEY).
         *
         * <p>Returns HTTP 409 (Conflict) for duplicate key violations during write
         * operations. Maps from COBOL WRITE INVALID KEY patterns in COTRN02C.cbl
         * and COUSR01C.cbl.</p>
         *
         * @param ex      the duplicate-record exception with entity context
         * @param request the HTTP request for URI extraction
         * @return HTTP 409 response with structured error body
         */
        @ExceptionHandler(DuplicateRecordException.class)
        public ResponseEntity<ErrorResponse> handleDuplicateRecordException(
                DuplicateRecordException ex, HttpServletRequest request) {
            log.warn("Duplicate record [errorCode={}, fileStatus={}]: {}",
                    ex.getErrorCode(), ex.getFileStatusCode(), ex.getMessage());
            ErrorResponse response = buildErrorResponse(
                    HttpStatus.CONFLICT, ex.getMessage(), ex.getErrorCode(), null, request);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        /**
         * Handles {@link ConcurrentModificationException} — CICS REWRITE snapshot mismatch.
         *
         * <p>Returns HTTP 409 (Conflict) for optimistic locking failures. Maps from
         * COBOL LOCKED-BUT-UPDATE-FAILED and DATA-WAS-CHANGED-BEFORE-UPDATE flags
         * in COACTUPC.cbl and COCRDUPC.cbl. The Spring {@code @Transactional} rollback
         * preserves COBOL SYNCPOINT ROLLBACK semantics.</p>
         *
         * @param ex      the concurrent-modification exception with entity context
         * @param request the HTTP request for URI extraction
         * @return HTTP 409 response with structured error body
         */
        @ExceptionHandler(ConcurrentModificationException.class)
        public ResponseEntity<ErrorResponse> handleConcurrentModificationException(
                ConcurrentModificationException ex, HttpServletRequest request) {
            log.warn("Concurrent modification [errorCode={}]: {}",
                    ex.getErrorCode(), ex.getMessage());
            ErrorResponse response = buildErrorResponse(
                    HttpStatus.CONFLICT, ex.getMessage(), ex.getErrorCode(), null, request);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        /**
         * Handles {@link CreditLimitExceededException} — COBOL reject code 102.
         *
         * <p>Returns HTTP 422 (Unprocessable Entity) when a transaction amount
         * exceeds the account credit limit. Maps from CBTRN02C.cbl paragraph
         * 1500-B-LOOKUP-ACCT where WS-VALIDATION-FAIL-REASON is set to 102
         * with description 'OVERLIMIT TRANSACTION'.</p>
         *
         * @param ex      the credit-limit-exceeded exception with financial context
         * @param request the HTTP request for URI extraction
         * @return HTTP 422 response with structured error body
         */
        @ExceptionHandler(CreditLimitExceededException.class)
        public ResponseEntity<ErrorResponse> handleCreditLimitExceededException(
                CreditLimitExceededException ex, HttpServletRequest request) {
            log.warn("Credit limit exceeded [errorCode={}]: {}",
                    ex.getErrorCode(), ex.getMessage());
            ErrorResponse response = buildErrorResponse(
                    HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(),
                    ex.getErrorCode(), null, request);
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }

        /**
         * Handles {@link ExpiredCardException} — COBOL reject code 103.
         *
         * <p>Returns HTTP 422 (Unprocessable Entity) when a transaction is
         * attempted on an expired account. Maps from CBTRN02C.cbl paragraph
         * 1500-B-LOOKUP-ACCT where WS-VALIDATION-FAIL-REASON is set to 103
         * with description 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'.</p>
         *
         * @param ex      the expired-card exception with date context
         * @param request the HTTP request for URI extraction
         * @return HTTP 422 response with structured error body
         */
        @ExceptionHandler(ExpiredCardException.class)
        public ResponseEntity<ErrorResponse> handleExpiredCardException(
                ExpiredCardException ex, HttpServletRequest request) {
            log.warn("Expired card/account [errorCode={}]: {}",
                    ex.getErrorCode(), ex.getMessage());
            ErrorResponse response = buildErrorResponse(
                    HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(),
                    ex.getErrorCode(), null, request);
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }

        /**
         * Handles {@link ValidationException} — COBOL field-level validation failures.
         *
         * <p>Returns HTTP 400 (Bad Request) with per-field error details. Maps from
         * COACTUPC.cbl paragraph 9700-CHECK-CHANGE-IN-REC which validates 15+ fields
         * individually, and from CSLKPCDY.cpy NANPA/state/ZIP validation tables.</p>
         *
         * <p>The {@code fieldErrors} list in the response provides structured
         * per-field validation error details (field name, rejected value, message),
         * enabling API consumers to highlight specific input fields.</p>
         *
         * @param ex      the validation exception with field-level error details
         * @param request the HTTP request for URI extraction
         * @return HTTP 400 response with structured error body including fieldErrors
         */
        @ExceptionHandler(ValidationException.class)
        public ResponseEntity<ErrorResponse> handleValidationException(
                ValidationException ex, HttpServletRequest request) {
            log.warn("Validation failure [errorCode={}, fields={}]: {}",
                    ex.getErrorCode(), ex.getFieldErrors().size(), ex.getMessage());
            List<Map<String, String>> fieldErrorList = ex.getFieldErrors().stream()
                    .map(fe -> {
                        Map<String, String> entry = new HashMap<>();
                        entry.put("field", fe.fieldName());
                        entry.put("rejectedValue", fe.rejectedValue());
                        entry.put("message", fe.message());
                        return entry;
                    })
                    .toList();
            List<Map<String, String>> errors = fieldErrorList.isEmpty() ? null : fieldErrorList;
            ErrorResponse response = buildErrorResponse(
                    HttpStatus.BAD_REQUEST, ex.getMessage(), ex.getErrorCode(),
                    errors, request);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        /**
         * Handles {@link ConstraintViolationException} — Jakarta Bean Validation failures.
         *
         * <p>Returns HTTP 400 (Bad Request) when {@code @Valid} annotation triggers
         * constraint violations on request DTOs. Replaces the COBOL CSSETATY.cpy
         * field attribute validation pattern where BMS screen fields were validated
         * per-field before processing.</p>
         *
         * @param ex      the constraint violation exception from Jakarta Bean Validation
         * @param request the HTTP request for URI extraction
         * @return HTTP 400 response with structured field error details
         */
        @ExceptionHandler(ConstraintViolationException.class)
        public ResponseEntity<ErrorResponse> handleConstraintViolationException(
                ConstraintViolationException ex, HttpServletRequest request) {
            log.warn("Constraint violation: {}", ex.getMessage());
            List<Map<String, String>> fieldErrorList = ex.getConstraintViolations().stream()
                    .map(violation -> {
                        Map<String, String> entry = new HashMap<>();
                        entry.put("field", violation.getPropertyPath().toString());
                        Object invalidValue = violation.getInvalidValue();
                        entry.put("rejectedValue",
                                invalidValue != null ? String.valueOf(invalidValue) : null);
                        entry.put("message", violation.getMessage());
                        return entry;
                    })
                    .toList();
            List<Map<String, String>> errors = fieldErrorList.isEmpty() ? null : fieldErrorList;
            ErrorResponse response = buildErrorResponse(
                    HttpStatus.BAD_REQUEST, "Validation failed", "VALID",
                    errors, request);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        /**
         * Handles {@link MethodArgumentNotValidException} — Spring MVC {@code @Valid}
         * annotation failures on {@code @RequestBody} DTOs.
         *
         * <p>Returns HTTP 400 (Bad Request) with per-field error details extracted from
         * the {@link org.springframework.validation.BindingResult}. This handler covers
         * the standard Spring MVC validation path for request body deserialization, which
         * is distinct from the {@link ConstraintViolationException} handler (which covers
         * {@code @Validated} on path/query parameters).</p>
         *
         * <p>Without this handler, Spring's default exception resolution would intercept
         * {@code MethodArgumentNotValidException} and return a 400 response without
         * the structured {@link ErrorResponse} format, breaking the API contract defined
         * in {@code docs/api-contracts.md} section 1.6.</p>
         *
         * @param ex      the method argument validation exception from Spring MVC
         * @param request the HTTP request for URI extraction
         * @return HTTP 400 response with structured fieldErrors array
         */
        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(
                MethodArgumentNotValidException ex, HttpServletRequest request) {
            log.warn("Method argument validation failed: {}", ex.getMessage());
            List<Map<String, String>> fieldErrorList = ex.getBindingResult()
                    .getFieldErrors().stream()
                    .map(fieldError -> {
                        Map<String, String> entry = new HashMap<>();
                        entry.put("field", fieldError.getField());
                        Object rejectedValue = fieldError.getRejectedValue();
                        entry.put("rejectedValue",
                                rejectedValue != null ? String.valueOf(rejectedValue) : null);
                        entry.put("message", fieldError.getDefaultMessage());
                        return entry;
                    })
                    .toList();
            List<Map<String, String>> errors = fieldErrorList.isEmpty() ? null : fieldErrorList;
            ErrorResponse response = buildErrorResponse(
                    HttpStatus.BAD_REQUEST, "Validation failed", "VALID",
                    errors, request);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        /**
         * Handles {@link HttpMessageNotReadableException} — malformed or missing
         * request body.
         *
         * <p>Returns HTTP 400 (Bad Request) when the request body cannot be
         * deserialized by Jackson. Common causes include malformed JSON syntax,
         * empty request bodies for endpoints expecting {@code @RequestBody}, and
         * type coercion failures (e.g., string where number expected).</p>
         *
         * <p>This handler is critical for all REST endpoints — without it,
         * malformed payloads fall through to the generic {@code Exception} handler
         * and return HTTP 500, which incorrectly signals a server error for what
         * is a client input problem.</p>
         *
         * <p>No direct COBOL equivalent — BMS 3270 screens enforced field-level
         * formatting at the terminal level before data reached the COBOL program.
         * In the REST API, clients can send arbitrary payloads, so server-side
         * deserialization error handling is essential.</p>
         *
         * @param ex      the message-not-readable exception from Jackson/Spring MVC
         * @param request the HTTP request for URI extraction
         * @return HTTP 400 response with structured error body
         */
        @ExceptionHandler(HttpMessageNotReadableException.class)
        public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(
                HttpMessageNotReadableException ex, HttpServletRequest request) {
            log.warn("Malformed request body: {}", ex.getMessage());
            ErrorResponse response = buildErrorResponse(
                    HttpStatus.BAD_REQUEST,
                    "Malformed or missing request body. Ensure the request contains valid JSON.",
                    "BAD_REQUEST", null, request);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        /**
         * Handles {@link HttpMediaTypeNotSupportedException} — unsupported
         * Content-Type header.
         *
         * <p>Returns HTTP 415 (Unsupported Media Type) when the request
         * Content-Type header does not match any media type accepted by the
         * target endpoint. For CardDemo REST endpoints, the accepted type is
         * {@code application/json}.</p>
         *
         * <p>Without this handler, unsupported content types fall through to the
         * generic {@code Exception} handler and return HTTP 500, which incorrectly
         * signals a server error for what is a client configuration problem.</p>
         *
         * <p>No direct COBOL equivalent — BMS 3270 terminal protocol had a fixed
         * data encoding (EBCDIC), so content-type negotiation was not applicable.</p>
         *
         * @param ex      the media-type-not-supported exception from Spring MVC
         * @param request the HTTP request for URI extraction
         * @return HTTP 415 response with structured error body
         */
        @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
        public ResponseEntity<ErrorResponse> handleHttpMediaTypeNotSupportedException(
                HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
            log.warn("Unsupported media type: {}", ex.getContentType());
            String message = String.format(
                    "Content type '%s' is not supported. Use 'application/json'.",
                    ex.getContentType());
            ErrorResponse response = buildErrorResponse(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, message,
                    "UNSUPPORTED_MEDIA_TYPE", null, request);
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(response);
        }

        /**
         * Handles {@link HttpRequestMethodNotSupportedException} — returned by
         * Spring MVC when a request uses an HTTP method not supported by the handler.
         *
         * <p>Returns HTTP 405 (Method Not Allowed) instead of Spring's default 500
         * error. This ensures that e.g. GET /api/auth/signin (a POST-only endpoint)
         * returns a proper 405 response with a structured error body.</p>
         *
         * @param ex      the method-not-supported exception from Spring MVC
         * @param request the HTTP request for URI extraction
         * @return HTTP 405 response with allowed methods listed in the error message
         */
        @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
        public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
                HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
            log.warn("Method not allowed: {} {} (supported: {})",
                    request.getMethod(), request.getRequestURI(), ex.getSupportedHttpMethods());
            String message = String.format(
                    "Request method '%s' is not supported for this endpoint. Supported methods: %s",
                    ex.getMethod(), ex.getSupportedHttpMethods());
            ErrorResponse response = buildErrorResponse(
                    HttpStatus.METHOD_NOT_ALLOWED, message,
                    "METHOD_NOT_ALLOWED", null, request);
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(response);
        }

        /**
         * Handles {@link IllegalArgumentException} — returned when input validation
         * in service methods rejects invalid parameters.
         *
         * <p>Returns HTTP 400 (Bad Request) with the exception message as the error
         * detail. This handler catches validation exceptions thrown by service-layer
         * input checks (e.g., non-numeric card number in
         * {@code CardDetailService.validateCardNumber()}) that are not already covered
         * by Bean Validation or Spring MVC binding exceptions.</p>
         *
         * <p>In the COBOL source, these map to field-level edit paragraphs such as
         * {@code 2220-EDIT-CARD} in COCRDSLC.cbl, which set an error message and
         * re-display the BMS screen. In the REST API target, this maps to HTTP 400
         * with a descriptive error message.</p>
         *
         * @param ex      the IllegalArgumentException from service-layer validation
         * @param request the HTTP request for URI extraction
         * @return HTTP 400 response with validation error details
         */
        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
                IllegalArgumentException ex, HttpServletRequest request) {
            log.warn("Invalid argument: {} {} — {}",
                    request.getMethod(), request.getRequestURI(), ex.getMessage());
            ErrorResponse response = buildErrorResponse(
                    HttpStatus.BAD_REQUEST, ex.getMessage(),
                    "INVALID_ARGUMENT", null, request);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        /**
         * Handles {@link CardDemoException} — catch-all for application exceptions.
         *
         * <p>Returns HTTP 500 (Internal Server Error) for any CardDemoException
         * subclass not explicitly handled by the more specific exception handlers
         * above. Logs the full stack trace and the COBOL FILE STATUS code (if
         * present) for debugging and traceability.</p>
         *
         * @param ex      the base CardDemo exception
         * @param request the HTTP request for URI extraction
         * @return HTTP 500 response with error code from the exception hierarchy
         */
        @ExceptionHandler(CardDemoException.class)
        public ResponseEntity<ErrorResponse> handleCardDemoException(
                CardDemoException ex, HttpServletRequest request) {
            log.error("CardDemo error [errorCode={}, fileStatus={}]: {}",
                    ex.getErrorCode(), ex.getFileStatusCode(), ex.getMessage(), ex);
            ErrorResponse response = buildErrorResponse(
                    HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(),
                    ex.getErrorCode(), null, request);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }

        /**
         * Handles {@link NoResourceFoundException} — returned by Spring MVC when
         * no handler mapping is found for a request URI.
         *
         * <p>Returns HTTP 404 (Not Found) with a descriptive message indicating
         * the requested resource does not exist. This handler is necessary because
         * {@code NoResourceFoundException} (a {@link org.springframework.web.servlet.resource.NoResourceFoundException})
         * would otherwise be caught by the generic {@code Exception.class} handler
         * and incorrectly returned as HTTP 500.</p>
         *
         * <p>This has no direct COBOL equivalent — in the BMS 3270 architecture,
         * invalid transaction IDs were handled by the CICS transaction routing
         * mechanism, which displayed a "Transaction not found" message on the
         * terminal. In the REST API target, this maps to standard HTTP 404
         * semantics for non-existent endpoints.</p>
         *
         * @param ex      the NoResourceFoundException from Spring MVC
         * @param request the HTTP request for URI extraction
         * @return HTTP 404 response with descriptive error message
         */
        @ExceptionHandler(NoResourceFoundException.class)
        public ResponseEntity<ErrorResponse> handleNoResourceFoundException(
                NoResourceFoundException ex, HttpServletRequest request) {
            log.warn("Resource not found: {} {}", request.getMethod(), request.getRequestURI());
            ErrorResponse response = buildErrorResponse(
                    HttpStatus.NOT_FOUND, ex.getMessage(),
                    "RESOURCE_NOT_FOUND", null, request);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        /**
         * Handles generic {@link Exception} — ultimate catch-all for unexpected errors.
         *
         * <p>Returns HTTP 500 (Internal Server Error) with a generic message that
         * does not expose internal implementation details (security best practice).
         * Logs the full stack trace for debugging.</p>
         *
         * <p>This handler ensures that no unhandled exception results in a raw
         * stack trace being returned to API consumers.</p>
         *
         * @param ex      the unexpected exception
         * @param request the HTTP request for URI extraction
         * @return HTTP 500 response with generic error message
         */
        @ExceptionHandler(Exception.class)
        public ResponseEntity<ErrorResponse> handleGenericException(
                Exception ex, HttpServletRequest request) {
            log.error("Unexpected error: {}", ex.getMessage(), ex);
            ErrorResponse response = buildErrorResponse(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "An unexpected error occurred. Please contact support.",
                    null, null, request);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }

        /**
         * Builds a structured {@link ErrorResponse} with observability context.
         *
         * <p>Populates the response with the current UTC timestamp and the
         * correlation ID from MDC (injected by the observability filter per
         * AAP §0.7.1). The request URI is extracted from the servlet request
         * for traceability.</p>
         *
         * @param status      the HTTP status for the error response
         * @param message     the human-readable error message
         * @param errorCode   the application error code (may be {@code null})
         * @param fieldErrors optional list of per-field validation errors
         * @param request     the HTTP servlet request for URI extraction
         * @return the fully populated error response
         */
        private ErrorResponse buildErrorResponse(HttpStatus status, String message,
                String errorCode, List<Map<String, String>> fieldErrors,
                HttpServletRequest request) {
            return new ErrorResponse(
                    status.value(),
                    status.getReasonPhrase(),
                    message,
                    errorCode,
                    fieldErrors,
                    Instant.now().toString(),
                    request.getRequestURI(),
                    MDC.get(CORRELATION_ID_KEY)
            );
        }
    }
}
