/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.unit.exception;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.carddemo.exception.AuthenticationFailedException;
import com.carddemo.exception.BusinessRuleException;
import com.carddemo.exception.ConcurrentUpdateException;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.GlobalExceptionHandler;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Pure JUnit 5 + Mockito unit test for {@link GlobalExceptionHandler}, the
 * {@code @RestControllerAdvice} that centralizes error translation into
 * RFC&nbsp;7807 {@link ProblemDetail} responses during the AWS CardDemo
 * COBOL&#8594;Java migration.
 *
 * <p>The suite is deliberately framework-free: it bootstraps <strong>no</strong>
 * Spring {@code ApplicationContext}, uses <strong>no</strong> {@code MockMvc} and
 * touches no database, AWS, or network resource. Each handler method is invoked
 * directly; the only collaborator is a Mockito-mocked
 * {@link HttpServletRequest}. This keeps the test fast and isolated while still
 * asserting the full status-mapping contract.</p>
 *
 * <p>It validates all fourteen exception&#8594;status mappings (seven custom
 * exceptions plus seven framework / Spring / Jakarta exceptions), the
 * "generic, non-leaking body" guarantee for every {@code 5xx} response, and the
 * correlation-id surfacing from {@code MDC}.</p>
 *
 * <p>Parity references (read-only @ commit {@code 27d6c6f}; never reproduced
 * here): {@code app/cbl/CBACT01C.cbl} (the account-master file-status
 * {@code 9x} abend path that {@link FileAccessException} models, mapped to a
 * generic HTTP&nbsp;500) and {@code app/cbl/COACTUPC.cbl} (the
 * {@code 9700-CHECK-CHANGE-IN-REC} re-read-and-compare optimistic-lock guard
 * whose byte-exact message {@code "Record changed by some one else. Please
 * review"} surfaces as HTTP&nbsp;409).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GlobalExceptionHandler — RFC 7807 ProblemDetail status mappings")
class GlobalExceptionHandlerTest {

    /** Correlation id seeded into MDC before each test. */
    private static final String CORRELATION_ID = "test-correlation-id-001";

    /** Exact generic detail the handler returns for every 5xx mapping. */
    private static final String GENERIC_DETAIL =
            "An internal error occurred. Please contact support if the problem persists.";

    /**
     * Distinctive raw cause text seeded into 5xx exceptions. The handler must
     * never echo this (or other internal tokens) into the response body.
     */
    private static final String SECRET = "SECRET-INTERNAL-STACK-12345";

    @Mock
    private HttpServletRequest request;

    private GlobalExceptionHandler handler;

    /**
     * Captures events emitted by the {@link GlobalExceptionHandler} logger so the
     * suite can prove the no-sensitive-logging guarantee (R1): 5xx handlers must
     * never log the throwable nor any internal token. Attached in {@link #setUp()}
     * and detached in {@link #tearDown()} to keep the logger pristine across tests.
     */
    private Logger handlerLogger;

    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        // problem() reads the request URI to populate the ProblemDetail "instance".
        // Stub leniently so a test that does not assert the instance never trips
        // strict-stub verification (every handler path does invoke it, so this is
        // belt-and-suspenders rather than strictly required).
        lenient().when(request.getRequestURI()).thenReturn("/api/test");
        MDC.put("correlationId", CORRELATION_ID);

        // Attach an in-memory Logback appender to the handler's logger so the 5xx
        // tests can assert that neither the formatted message nor an attached
        // throwable proxy carries an internal secret or stack token (R1).
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logCapture = new ListAppender<>();
        logCapture.start();
        handlerLogger.addAppender(logCapture);
    }

    @AfterEach
    void tearDown() {
        if (handlerLogger != null && logCapture != null) {
            handlerLogger.detachAppender(logCapture);
            logCapture.stop();
        }
        MDC.clear();
    }

    // ------------------------------------------------------------------
    // Custom exceptions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RecordNotFoundException -> 404 NOT_FOUND with entity-aware, key-free detail")
    void recordNotFoundMapsTo404() {
        RecordNotFoundException ex = new RecordNotFoundException("Account", "00000000011");

        ResponseEntity<ProblemDetail> response = handler.handleRecordNotFound(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.NOT_FOUND, "Resource Not Found");
        assertThat(body.getDetail()).isEqualTo("The requested Account could not be found.");
        assertThat(body.getDetail()).doesNotContain("00000000011");
        assertThat(body.getInstance()).isEqualTo(URI.create("/api/test"));
        assertThat(body.getProperties()).containsKey("timestamp");
        assertThat(body.getProperties()).containsEntry("correlationId", CORRELATION_ID);
    }

    @Test
    @DisplayName("DuplicateRecordException -> 409 CONFLICT with entity-aware, key-free detail")
    void duplicateRecordMapsTo409() {
        DuplicateRecordException ex = new DuplicateRecordException("Card", "4111111111111111");

        ResponseEntity<ProblemDetail> response = handler.handleDuplicateRecord(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.CONFLICT, "Duplicate Resource");
        assertThat(body.getDetail()).isEqualTo("A Card with the supplied identifier already exists.");
        assertThat(body.getDetail()).doesNotContain("4111111111111111");
    }

    @Test
    @DisplayName("RecordNotFoundException (message-only ctor) -> 404 surfacing the byte-exact key-free message (P-4)")
    void recordNotFoundMessageOnlySurfacesByteExactDetail() {
        // The message-only constructor carries a deliberate, key-free legacy literal
        // (COUSR02C/COUSR03C 'User ID NOT found...'). entityType is null, so the handler
        // must surface the verbatim message to reproduce the on-screen COBOL text (AAP 0.7.1.1).
        RecordNotFoundException ex = new RecordNotFoundException("User ID NOT found...");

        ResponseEntity<ProblemDetail> response = handler.handleRecordNotFound(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.NOT_FOUND, "Resource Not Found");
        assertThat(body.getDetail()).isEqualTo("User ID NOT found...");
    }

    @Test
    @DisplayName("DuplicateRecordException (message-only ctor) -> 409 surfacing the byte-exact key-free message (P-3)")
    void duplicateRecordMessageOnlySurfacesByteExactDetail() {
        // The message-only constructor carries the key-free COUSR01C literal 'User ID already exist...'.
        // entityType is null, so the handler surfaces the verbatim message (AAP 0.7.1.1).
        DuplicateRecordException ex = new DuplicateRecordException("User ID already exist...");

        ResponseEntity<ProblemDetail> response = handler.handleDuplicateRecord(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.CONFLICT, "Duplicate Resource");
        assertThat(body.getDetail()).isEqualTo("User ID already exist...");
    }

    @Test
    @DisplayName("ConcurrentUpdateException -> 409 CONFLICT with byte-exact parity message")
    void concurrentUpdateMapsTo409AndParityMessage() {
        ConcurrentUpdateException ex = new ConcurrentUpdateException();

        ResponseEntity<ProblemDetail> response = handler.handleConcurrentUpdate(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.CONFLICT, "Concurrent Update Conflict");
        // Byte-exact COBOL parity (COACTUPC.cbl:522): "some one" is two words, no trailing period.
        assertThat(body.getDetail()).isEqualTo("Record changed by some one else. Please review");
    }

    @Test
    @DisplayName("ValidationException -> 400 BAD_REQUEST carrying the seeded per-field errors")
    void validationMapsTo400WithErrors() {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        fieldErrors.put("acctId", "Account id is required.");
        fieldErrors.put("creditLimit", "Credit limit must be positive.");
        ValidationException ex = new ValidationException("Validation failed", fieldErrors);

        ResponseEntity<ProblemDetail> response = handler.handleValidation(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.BAD_REQUEST, "Validation Failed");
        assertThat(body.getDetail()).isEqualTo("Validation failed");
        assertThat(body.getProperties()).containsKey("errors");
        // The handler stores ex.getFieldErrors() verbatim; compare by content (Map.equals)
        // so no unchecked cast of the property value is needed.
        assertThat(body.getProperties().get("errors")).isEqualTo(fieldErrors);
    }

    @Test
    @DisplayName("FileAccessException -> 500 with a generic, non-leaking detail")
    void fileAccessMapsTo500Generic() {
        FileAccessException ex =
                new FileAccessException("READ ACCTFILE", "92", new RuntimeException(SECRET));

        ResponseEntity<ProblemDetail> response = handler.handleFileAccess(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error");
        assertThat(body.getDetail()).isEqualTo(GENERIC_DETAIL);
        assertThat(body.getDetail())
                .doesNotContain("Exception", "SQL", "at com.", SECRET, "ACCTFILE", "92");
        // The handler must not have logged the throwable, whose cause carries SECRET.
        assertNoSensitiveLogging();
    }

    @Test
    @DisplayName("BusinessRuleException -> 422 UNPROCESSABLE_ENTITY carrying the rule code")
    void businessRuleMapsTo422WithRuleCode() {
        BusinessRuleException ex = new BusinessRuleException("102", "Transaction exceeds the credit limit.");

        ResponseEntity<ProblemDetail> response = handler.handleBusinessRule(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.UNPROCESSABLE_ENTITY, "Business Rule Violation");
        assertThat(body.getDetail()).isEqualTo("Transaction exceeds the credit limit.");
        assertThat(body.getProperties()).containsEntry("ruleCode", "102");
    }

    @Test
    @DisplayName("AuthenticationFailedException -> 401 UNAUTHORIZED")
    void authenticationFailedMapsTo401() {
        AuthenticationFailedException ex = new AuthenticationFailedException("Invalid credentials");

        ResponseEntity<ProblemDetail> response = handler.handleAuthenticationFailed(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.UNAUTHORIZED, "Authentication Failed");
        assertThat(body.getDetail()).isEqualTo("Invalid credentials");
    }

    // ------------------------------------------------------------------
    // Framework / Spring / Jakarta exceptions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MethodArgumentNotValidException -> 400 with merged field errors")
    void methodArgumentNotValidMapsTo400() throws NoSuchMethodException {
        // A real MethodParameter is required; any public JDK method serves as the
        // reflective target since the handler only reads the binding result.
        Method method = String.class.getMethod("substring", int.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "fieldA", "must not be blank"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, binding);

        ResponseEntity<ProblemDetail> response = handler.handleMethodArgumentNotValid(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.BAD_REQUEST, "Validation Failed");
        assertThat(body.getDetail()).isEqualTo("Request validation failed");
        assertThat(body.getProperties()).containsKey("errors");
        assertThat(body.getProperties().get("errors")).isEqualTo(Map.of("fieldA", "must not be blank"));
    }

    @Test
    @DisplayName("ConstraintViolationException -> 400")
    void constraintViolationMapsTo400() {
        ConstraintViolationException ex =
                new ConstraintViolationException(Collections.<ConstraintViolation<?>>emptySet());

        ResponseEntity<ProblemDetail> response = handler.handleConstraintViolation(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.BAD_REQUEST, "Validation Failed");
        assertThat(body.getDetail()).isEqualTo("Request validation failed");
        assertThat(body.getProperties()).containsKey("errors");
    }

    @Test
    @DisplayName("HttpMessageNotReadableException -> 400 with a generic, leak-free malformed-request detail (F-1)")
    void httpMessageNotReadableMapsTo400() {
        // A malformed/empty/missing JSON body must be a client error (400), not a 500.
        HttpInputMessage inputMessage = mock(HttpInputMessage.class);
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("JSON parse error: " + SECRET, inputMessage);

        ResponseEntity<ProblemDetail> response = handler.handleHttpMessageNotReadable(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.BAD_REQUEST, "Malformed Request");
        assertThat(body.getDetail())
                .isEqualTo("The request body could not be read; ensure it is well-formed JSON.");
        // The parser message can echo fragments of the bad payload; it must never reach the body.
        assertThat(body.getDetail()).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("HttpMediaTypeNotSupportedException -> 415 (F-1)")
    void httpMediaTypeNotSupportedMapsTo415() {
        HttpMediaTypeNotSupportedException ex =
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<ProblemDetail> response = handler.handleHttpMediaTypeNotSupported(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type");
        assertThat(body.getDetail())
                .isEqualTo("The request Content-Type is not supported; use application/json.");
    }

    @Test
    @DisplayName("HttpRequestMethodNotSupportedException -> 405 with an Allow header listing supported methods (F-1)")
    void httpRequestMethodNotSupportedMapsTo405WithAllow() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("POST", List.of("GET"));

        ResponseEntity<ProblemDetail> response = handler.handleHttpRequestMethodNotSupported(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed");
        assertThat(body.getDetail()).isEqualTo("The HTTP method is not supported for this resource.");
        // RFC 7231 6.5.5: the 405 response MUST advertise the supported methods via Allow.
        assertThat(response.getHeaders().getAllow()).containsExactly(HttpMethod.GET);
    }

    @Test
    @DisplayName("NoResourceFoundException (unmapped path) -> 404 with the generic not-found detail (F-1)")
    void noResourceFoundMapsTo404() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/api/nonexistent/path");

        ResponseEntity<ProblemDetail> response = handler.handleNoHandlerFound(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.NOT_FOUND, "Resource Not Found");
        assertThat(body.getDetail()).isEqualTo("The requested resource could not be found.");
    }

    @Test
    @DisplayName("NoHandlerFoundException (unmapped path) -> 404 with the generic not-found detail (F-1)")
    void noHandlerFoundMapsTo404() {
        NoHandlerFoundException ex =
                new NoHandlerFoundException("GET", "/api/nonexistent/path", new org.springframework.http.HttpHeaders());

        ResponseEntity<ProblemDetail> response = handler.handleNoHandlerFound(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.NOT_FOUND, "Resource Not Found");
        assertThat(body.getDetail()).isEqualTo("The requested resource could not be found.");
    }

    @Test
    @DisplayName("jakarta.persistence.OptimisticLockException -> 409 with parity message")
    void jakartaOptimisticLockMapsTo409() {
        OptimisticLockException ex = new OptimisticLockException("row version mismatch");

        ResponseEntity<ProblemDetail> response = handler.handleOptimisticLock(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.CONFLICT, "Concurrent Update Conflict");
        assertThat(body.getDetail()).isEqualTo("Record changed by some one else. Please review");
    }

    @Test
    @DisplayName("Spring ObjectOptimisticLockingFailureException -> 409 with parity message")
    void springObjectOptimisticLockingFailureMapsTo409() {
        ObjectOptimisticLockingFailureException ex =
                new ObjectOptimisticLockingFailureException(Object.class, "id");

        ResponseEntity<ProblemDetail> response = handler.handleOptimisticLock(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.CONFLICT, "Concurrent Update Conflict");
        assertThat(body.getDetail()).isEqualTo("Record changed by some one else. Please review");
    }

    @Test
    @DisplayName("AccessDeniedException -> 403 FORBIDDEN")
    void accessDeniedMapsTo403() {
        AccessDeniedException ex = new AccessDeniedException("forbidden");

        ResponseEntity<ProblemDetail> response = handler.handleAccessDenied(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.FORBIDDEN, "Access Denied");
        assertThat(body.getDetail()).isEqualTo("Access is denied.");
    }

    @Test
    @DisplayName("DataAccessException -> 500 with a generic, non-leaking detail")
    void dataAccessMapsTo500Generic() {
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException(SECRET + " - bad SQL constraint");

        ResponseEntity<ProblemDetail> response = handler.handleDataAccess(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error");
        assertThat(body.getDetail()).isEqualTo(GENERIC_DETAIL);
        assertThat(body.getDetail()).doesNotContain("Exception", "SQL", "at com.", SECRET);
        assertNoSensitiveLogging();
    }

    @Test
    @DisplayName("Unhandled Exception -> 500 with a generic, non-leaking detail")
    void genericExceptionMapsTo500Generic() {
        Exception ex = new IllegalStateException(SECRET + " NullPointerException at com.evil.Boom - bad SQL");

        ResponseEntity<ProblemDetail> response = handler.handleUnexpected(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error");
        assertThat(body.getDetail()).isEqualTo(GENERIC_DETAIL);
        assertThat(body.getDetail()).doesNotContain("Exception", "SQL", "at com.", SECRET);
        assertNoSensitiveLogging();
    }

    // ------------------------------------------------------------------
    // correlationId / MDC behavior
    // ------------------------------------------------------------------

    @Test
    @DisplayName("correlationId is surfaced from MDC into the ProblemDetail")
    void correlationIdSurfacedFromMdc() {
        AuthenticationFailedException ex = new AuthenticationFailedException("denied");

        ResponseEntity<ProblemDetail> response = handler.handleAuthenticationFailed(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.UNAUTHORIZED, "Authentication Failed");
        assertThat(body.getProperties()).containsEntry("correlationId", CORRELATION_ID);
    }

    @Test
    @DisplayName("missing MDC correlationId is handled safely and the property is omitted")
    void nullCorrelationIdIsHandledSafely() {
        MDC.remove("correlationId");
        AuthenticationFailedException ex = new AuthenticationFailedException("denied");

        // The call must complete without error even when MDC has no correlation id;
        // a thrown exception would fail the test.
        ResponseEntity<ProblemDetail> response = handler.handleAuthenticationFailed(ex, request);

        ProblemDetail body = assertProblem(response, HttpStatus.UNAUTHORIZED, "Authentication Failed");
        assertThat(body.getProperties()).doesNotContainKey("correlationId");
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    /**
     * Asserts the response envelope and the common {@link ProblemDetail} fields,
     * then returns the body for per-test detail/property assertions.
     *
     * @param response       the handler result, never {@code null}
     * @param expectedStatus the expected HTTP status
     * @param expectedTitle  the expected ProblemDetail title
     * @return the non-null {@link ProblemDetail} body
     */
    private static ProblemDetail assertProblem(ResponseEntity<ProblemDetail> response,
            HttpStatus expectedStatus, String expectedTitle) {
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(expectedStatus.value());
        assertThat(body.getTitle()).isEqualTo(expectedTitle);
        return body;
    }

    /**
     * Asserts the no-sensitive-logging guarantee (R1) for a 5xx handler: at least
     * one diagnostic event was emitted, and <em>no</em> captured event echoes the
     * seeded {@link #SECRET} (or related internal stack/SQL tokens) in its formatted
     * message, nor carries a throwable proxy whose stack or message could leak it.
     */
    private void assertNoSensitiveLogging() {
        assertThat(logCapture.list)
                .as("a 5xx handler must emit at least one diagnostic log event")
                .isNotEmpty();
        for (ILoggingEvent event : logCapture.list) {
            assertThat(event.getFormattedMessage())
                    .as("log message must not echo the internal secret or stack/SQL tokens")
                    .doesNotContain(SECRET, "SQL", "Boom", "ACCTFILE");
            assertThat(event.getThrowableProxy())
                    .as("5xx logging must not attach the throwable: its stack and message can leak secrets")
                    .isNull();
        }
    }
}
