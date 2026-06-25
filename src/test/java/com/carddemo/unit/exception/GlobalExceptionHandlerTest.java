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

import java.util.LinkedHashMap;
import java.util.Map;

import com.carddemo.exception.AuthenticationFailedException;
import com.carddemo.exception.BusinessRuleException;
import com.carddemo.exception.ConcurrentUpdateException;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.GlobalExceptionHandler;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GlobalExceptionHandler} covering the seven custom
 * exception-to-{@link ProblemDetail} mappings and the R1 requirement that record
 * identifiers are never exposed in the HTTP response body or in server logs.
 *
 * <p>Each handler is exercised directly (no Spring context) with a
 * {@link MockHttpServletRequest}. A Logback {@link ListAppender} attached to the
 * handler logger captures emitted events so the test can assert that a sensitive
 * record key never appears in any log line.</p>
 */
@DisplayName("GlobalExceptionHandler - RFC 7807 mappings and R1 no-PII-leak guarantees")
class GlobalExceptionHandlerTest {

    private static final String SENSITIVE_KEY = "12345678901";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/" + SENSITIVE_KEY);

    private Logger handlerLogger;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogCapture() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        handlerLogger.addAppender(logAppender);
        handlerLogger.setLevel(Level.TRACE);
        MDC.put("correlationId", "test-correlation-id");
    }

    @AfterEach
    void detachLogCapture() {
        handlerLogger.detachAppender(logAppender);
        MDC.clear();
    }

    private boolean anyLogContains(String fragment) {
        return logAppender.list.stream()
                .anyMatch(event -> event.getFormattedMessage().contains(fragment));
    }

    // ------------------------------------------------------------------
    // RecordNotFoundException -> 404, no key leak in detail or logs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RecordNotFoundException -> 404 with a key-free, entity-aware detail and key-free logs")
    void recordNotFoundMapsTo404WithoutLeakingKey() {
        RecordNotFoundException ex = new RecordNotFoundException("Account", SENSITIVE_KEY);

        ResponseEntity<ProblemDetail> response = handler.handleRecordNotFound(ex, request);
        ProblemDetail body = response.getBody();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(404);
        assertThat(body.getDetail())
                .as("404 detail must not contain the record key")
                .doesNotContain(SENSITIVE_KEY);
        assertThat(body.getDetail()).contains("Account");
        assertThat(anyLogContains(SENSITIVE_KEY))
                .as("the record key must never be logged")
                .isFalse();
        assertThat(body.getProperties()).containsEntry("correlationId", "test-correlation-id");
    }

    @Test
    @DisplayName("RecordNotFoundException with only a raw message -> generic detail, no message echo")
    void recordNotFoundWithRawMessageDoesNotEchoMessage() {
        RecordNotFoundException ex = new RecordNotFoundException("Account not found: " + SENSITIVE_KEY);

        ResponseEntity<ProblemDetail> response = handler.handleRecordNotFound(ex, request);
        ProblemDetail body = response.getBody();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body).isNotNull();
        assertThat(body.getDetail()).isEqualTo("The requested resource could not be found.");
        assertThat(body.getDetail()).doesNotContain(SENSITIVE_KEY);
        assertThat(anyLogContains(SENSITIVE_KEY)).isFalse();
    }

    // ------------------------------------------------------------------
    // DuplicateRecordException -> 409, no key leak in detail or logs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DuplicateRecordException -> 409 with a key-free detail and key-free logs")
    void duplicateRecordMapsTo409WithoutLeakingKey() {
        DuplicateRecordException ex = new DuplicateRecordException("Card", SENSITIVE_KEY);

        ResponseEntity<ProblemDetail> response = handler.handleDuplicateRecord(ex, request);
        ProblemDetail body = response.getBody();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(409);
        assertThat(body.getDetail())
                .as("409 detail must not contain the record key")
                .doesNotContain(SENSITIVE_KEY);
        assertThat(body.getDetail()).contains("Card");
        assertThat(anyLogContains(SENSITIVE_KEY))
                .as("the record key must never be logged")
                .isFalse();
    }

    // ------------------------------------------------------------------
    // ConcurrentUpdateException -> 409
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ConcurrentUpdateException -> 409")
    void concurrentUpdateMapsTo409() {
        ConcurrentUpdateException ex = new ConcurrentUpdateException();

        ResponseEntity<ProblemDetail> response = handler.handleConcurrentUpdate(ex, request);
        ProblemDetail body = response.getBody();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(409);
        assertThat(body.getTitle()).isEqualTo("Concurrent Update Conflict");
    }

    // ------------------------------------------------------------------
    // ValidationException -> 400 with per-field errors
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ValidationException -> 400 with the per-field errors property")
    void validationMapsTo400WithFieldErrors() {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        fieldErrors.put("acctId", "Account id is required.");
        ValidationException ex = new ValidationException("Validation failed", fieldErrors);

        ResponseEntity<ProblemDetail> response = handler.handleValidation(ex, request);
        ProblemDetail body = response.getBody();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getProperties()).containsKey("errors");
        assertThat(body.getProperties().get("errors")).isEqualTo(fieldErrors);
    }

    // ------------------------------------------------------------------
    // BusinessRuleException -> 422 with rule code
    // ------------------------------------------------------------------

    @Test
    @DisplayName("BusinessRuleException -> 422 with the rule code property")
    void businessRuleMapsTo422WithRuleCode() {
        BusinessRuleException ex = new BusinessRuleException("OVERLIMIT", "Account is over its credit limit.");

        ResponseEntity<ProblemDetail> response = handler.handleBusinessRule(ex, request);
        ProblemDetail body = response.getBody();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(422);
        assertThat(body.getProperties()).containsEntry("ruleCode", "OVERLIMIT");
    }

    // ------------------------------------------------------------------
    // AuthenticationFailedException -> 401
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AuthenticationFailedException -> 401")
    void authenticationFailedMapsTo401() {
        AuthenticationFailedException ex = new AuthenticationFailedException("Invalid credentials");

        ResponseEntity<ProblemDetail> response = handler.handleAuthenticationFailed(ex, request);
        ProblemDetail body = response.getBody();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(401);
        assertThat(body.getTitle()).isEqualTo("Authentication Failed");
    }

    // ------------------------------------------------------------------
    // FileAccessException -> 500 with a generic body (no operation / status leak)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("FileAccessException -> 500 with a generic detail that hides operation and status")
    void fileAccessMapsTo500WithGenericDetail() {
        FileAccessException ex = new FileAccessException("READ ACCTFILE", "92", null);

        ResponseEntity<ProblemDetail> response = handler.handleFileAccess(ex, request);
        ProblemDetail body = response.getBody();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(500);
        assertThat(body.getDetail())
                .isEqualTo("An internal error occurred. Please contact support if the problem persists.");
        assertThat(body.getDetail()).doesNotContain("ACCTFILE");
        assertThat(body.getDetail()).doesNotContain("92");
    }
}
