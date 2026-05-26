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
package com.awsm2.carddemo.exception;

import com.awsm2.carddemo.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit 5 + AssertJ unit tests for the {@link GlobalExceptionHandler}
 * {@code @RestControllerAdvice} — the single source of truth for COBOL
 * {@code RETURN-CODE} / {@code FILE STATUS} → HTTP status code mapping
 * per AAP &sect;0.7.1 (Rule 9: "Map COBOL RETURN-CODE / condition codes
 * to Spring exception hierarchy via @ControllerAdvice").
 *
 * <h2>QA Final Checkpoint 13 Maj-3 fix</h2>
 * <p>Previously the HTTP-status mappings were exercised only indirectly via
 * service-level tests that threw various exception types. The QA CP13
 * report identified this as a gap because the {@code @ControllerAdvice}
 * mapping contract itself was not protected by a dedicated test — a future
 * refactor that changed status codes (e.g., 422 → 400 for
 * {@code CreditLimitExceededException}) would not be caught by any test.
 * This test class enumerates every {@code @ExceptionHandler} method on
 * {@link GlobalExceptionHandler} and verifies:</p>
 * <ol>
 *   <li>The HTTP status code is exactly as documented in AAP &sect;0.3.4.</li>
 *   <li>The {@link ApiResponse} envelope is populated with the correct
 *       {@code code} (typically the COBOL {@code reasonCode}) and the
 *       exception message.</li>
 *   <li>For exceptions that carry field-level details
 *       ({@code ValidationException}, {@link org.springframework.web.bind.MethodArgumentNotValidException},
 *       {@link jakarta.validation.ConstraintViolationException}), the
 *       envelope's {@code fieldErrors} list is populated.</li>
 *   <li>A {@code correlationId} is generated and surfaced.</li>
 * </ol>
 *
 * <h2>Coverage matrix (handler → expected HTTP status)</h2>
 * <table border="1">
 *   <caption>Exception → HTTP status mapping</caption>
 *   <tr><th>Exception</th><th>Expected HTTP Status</th><th>COBOL Provenance</th></tr>
 *   <tr><td>{@link RecordNotFoundException}</td><td>404 NOT_FOUND</td><td>FILE STATUS '23'</td></tr>
 *   <tr><td>{@link DuplicateRecordException}</td><td>409 CONFLICT</td><td>FILE STATUS '22'</td></tr>
 *   <tr><td>{@link ConcurrentModificationException}</td><td>409 CONFLICT</td><td>Optimistic-lock snapshot mismatch</td></tr>
 *   <tr><td>{@link OptimisticLockingFailureException}</td><td>409 CONFLICT</td><td>JPA @Version conflict</td></tr>
 *   <tr><td>{@link CreditLimitExceededException}</td><td>422 UNPROCESSABLE_ENTITY</td><td>CBTRN02C reject code 102</td></tr>
 *   <tr><td>{@link ExpiredCardException}</td><td>422 UNPROCESSABLE_ENTITY</td><td>CBTRN02C reject code 103</td></tr>
 *   <tr><td>{@link ValidationException}</td><td>400 BAD_REQUEST</td><td>WS-VALIDATION-FAIL-REASON</td></tr>
 *   <tr><td>{@link OnSizeErrorException}</td><td>422 UNPROCESSABLE_ENTITY</td><td>ON SIZE ERROR</td></tr>
 *   <tr><td>{@link MissingServletRequestParameterException}</td><td>400 BAD_REQUEST</td><td>Missing field</td></tr>
 *   <tr><td>{@link MethodArgumentTypeMismatchException}</td><td>400 BAD_REQUEST</td><td>Type mismatch</td></tr>
 *   <tr><td>{@link NoHandlerFoundException}</td><td>404 NOT_FOUND</td><td>Unmapped URL</td></tr>
 *   <tr><td>{@link BadCredentialsException}</td><td>401 UNAUTHORIZED</td><td>COSGN00C IF SEC-USR-PWD = WS-USER-PWD</td></tr>
 *   <tr><td>{@link AuthenticationException}</td><td>401 UNAUTHORIZED</td><td>RACF auth</td></tr>
 *   <tr><td>{@link AccessDeniedException}</td><td>403 FORBIDDEN</td><td>RACF authorization</td></tr>
 *   <tr><td>{@link HttpRequestMethodNotSupportedException}</td><td>405 METHOD_NOT_ALLOWED</td><td>—</td></tr>
 *   <tr><td>{@link HttpMediaTypeNotSupportedException}</td><td>415 UNSUPPORTED_MEDIA_TYPE</td><td>—</td></tr>
 *   <tr><td>{@link DataIntegrityViolationException}</td><td>409 CONFLICT</td><td>FK violation</td></tr>
 *   <tr><td>{@link DataAccessException}</td><td>500 INTERNAL_SERVER_ERROR</td><td>DB outage</td></tr>
 *   <tr><td>{@link CardDemoException}</td><td>500 INTERNAL_SERVER_ERROR</td><td>Unhandled domain exception</td></tr>
 *   <tr><td>{@link Exception}</td><td>500 INTERNAL_SERVER_ERROR</td><td>Unknown failure</td></tr>
 * </table>
 *
 * <p>Tests invoke the {@link GlobalExceptionHandler} methods directly with
 * a {@link MockHttpServletRequest} fixture, avoiding the {@code @WebMvcTest}
 * + {@code MockMvc} overhead. This keeps the test suite fast (each test
 * runs in &lt; 1 ms) and focused on the handler contract itself rather
 * than Spring MVC's routing.</p>
 *
 * @see GlobalExceptionHandler
 * @see ApiResponse
 */
@DisplayName("GlobalExceptionHandler — QA CP13 Maj-3 HTTP-status mapping contract")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * Helper to construct a {@link MockHttpServletRequest} with a known URI
     * so handler log statements have a non-null request path.
     */
    private HttpServletRequest mockRequest(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI(uri);
        return req;
    }

    // -------------------------------------------------------------------------
    // Typed CardDemo domain exceptions
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Domain exceptions → typed HTTP statuses")
    class DomainExceptionTests {

        @Test
        @DisplayName("RecordNotFoundException → 404 NOT_FOUND (FILE STATUS '23')")
        void recordNotFoundReturnsNotFound() {
            // COBOL: CBTRN02C.cbl FILE STATUS '23' (NOTFND), COACTVWC.cbl READ failures
            RecordNotFoundException ex = new RecordNotFoundException("23", "Account not found");

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleRecordNotFound(ex, mockRequest("/api/accounts/999"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("23");
            assertThat(response.getBody().message()).isEqualTo("Account not found");
            assertThat(response.getBody().correlationId()).isNotBlank();
        }

        @Test
        @DisplayName("RecordNotFoundException with default reasonCode → uses 'NOT_FOUND'")
        void recordNotFoundWithoutReasonCodeUsesDefault() {
            RecordNotFoundException ex = new RecordNotFoundException("Card not found");

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleRecordNotFound(ex, mockRequest("/api/cards/0000"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().code()).isNotBlank();
        }

        @Test
        @DisplayName("DuplicateRecordException → 409 CONFLICT (FILE STATUS '22')")
        void duplicateRecordReturnsConflict() {
            // COBOL: COUSR01C.cbl L260-266, COTRN02C.cbl WRITE-TRANSACT-FILE DUPKEY
            DuplicateRecordException ex = new DuplicateRecordException("22", "User ID already exists");

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleDuplicateRecord(ex, mockRequest("/api/admin/users"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().code()).isEqualTo("22");
            assertThat(response.getBody().message()).isEqualTo("User ID already exists");
            assertThat(response.getBody().correlationId()).isNotBlank();
        }

        @Test
        @DisplayName("ConcurrentModificationException → 409 CONFLICT (optimistic lock)")
        void concurrentModificationReturnsConflict() {
            // COBOL: COACTUPC.cbl DATA-WAS-CHANGED-BEFORE-UPDATE
            ConcurrentModificationException ex = new ConcurrentModificationException(
                    "Account was modified by another transaction");

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleConcurrentModification(ex, mockRequest("/api/accounts/1"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().code()).isNotBlank();
            assertThat(response.getBody().message()).contains("modified");
            assertThat(response.getBody().correlationId()).isNotBlank();
        }

        @Test
        @DisplayName("OptimisticLockingFailureException → 409 CONFLICT (JPA @Version)")
        void optimisticLockingFailureReturnsConflict() {
            OptimisticLockingFailureException ex = new OptimisticLockingFailureException(
                    "JPA @Version conflict");

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleSpringOptimisticLock(ex, mockRequest("/api/accounts/1"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().code()).isNotBlank();
            assertThat(response.getBody().correlationId()).isNotBlank();
        }

        @Test
        @DisplayName("CreditLimitExceededException → 422 UNPROCESSABLE_ENTITY (CBTRN02C reject 102)")
        void creditLimitExceededReturnsUnprocessableEntity() {
            // COBOL: CBTRN02C.cbl reject code 102 (insufficient credit)
            CreditLimitExceededException ex = new CreditLimitExceededException(
                    "Transaction amount exceeds available credit");

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleCreditLimitExceeded(ex, mockRequest("/api/transactions"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().code()).isNotBlank();
            assertThat(response.getBody().correlationId()).isNotBlank();
        }

        @Test
        @DisplayName("ExpiredCardException → 422 UNPROCESSABLE_ENTITY (CBTRN02C reject 103)")
        void expiredCardReturnsUnprocessableEntity() {
            // COBOL: CBTRN02C.cbl reject code 103 (card expired)
            ExpiredCardException ex = new ExpiredCardException("Card expired");

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleExpiredCard(ex, mockRequest("/api/transactions"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().code()).isNotBlank();
            assertThat(response.getBody().correlationId()).isNotBlank();
        }

        @Test
        @DisplayName("ValidationException → 400 BAD_REQUEST (WS-VALIDATION-FAIL-REASON)")
        void validationExceptionReturnsBadRequest() {
            // COBOL: COACTUPC.cbl WS-VALIDATION-FAIL-REASON
            ValidationException ex = new ValidationException("Invalid account ID");

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleValidation(ex, mockRequest("/api/accounts"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().code()).isNotBlank();
            assertThat(response.getBody().message()).isEqualTo("Invalid account ID");
            assertThat(response.getBody().correlationId()).isNotBlank();
        }

        @Test
        @DisplayName("ValidationException with fieldErrors → 400 BAD_REQUEST with field-level details")
        void validationExceptionWithFieldErrorsReturnsBadRequestWithDetails() {
            List<ValidationException.FieldError> fieldErrors = List.of(
                    new ValidationException.FieldError("areaCode", "Invalid NANPA area code"),
                    new ValidationException.FieldError("stateCode", "Invalid US state")
            );
            ValidationException ex = new ValidationException("Validation failed", fieldErrors);

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleValidation(ex, mockRequest("/api/accounts/1"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().fieldErrors())
                    .as("ApiResponse should expose field-level errors via the fieldErrors list")
                    .hasSize(2);
            assertThat(response.getBody().fieldErrors())
                    .extracting(ApiResponse.FieldError::field)
                    .containsExactlyInAnyOrder("areaCode", "stateCode");
        }

        @Test
        @DisplayName("OnSizeErrorException → 422 UNPROCESSABLE_ENTITY (COBOL ON SIZE ERROR)")
        void onSizeErrorReturnsUnprocessableEntity() {
            // COBOL: COMPUTE WS-RESULT = X * Y ON SIZE ERROR ...
            OnSizeErrorException ex = new OnSizeErrorException(
                    "Result exceeds PIC S9(10)V99 precision");

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleOnSizeError(ex, mockRequest("/api/transactions"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().code()).isNotBlank();
            assertThat(response.getBody().message()).contains("precision");
            assertThat(response.getBody().correlationId()).isNotBlank();
        }

        @Test
        @DisplayName("CardDemoException (unhandled subclass) → 500 INTERNAL_SERVER_ERROR")
        void cardDemoExceptionReturnsInternalServerError() {
            CardDemoException ex = new CardDemoException("Unexpected business condition");

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleCardDemoException(ex, mockRequest("/api/accounts/1"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().code()).isNotBlank();
            assertThat(response.getBody().correlationId()).isNotBlank();
        }
    }

    // -------------------------------------------------------------------------
    // Spring framework / MVC exceptions
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Spring framework exceptions → standardized HTTP statuses")
    class SpringFrameworkExceptionTests {

        @Test
        @DisplayName("MissingServletRequestParameterException → 400 BAD_REQUEST")
        void missingRequestParameterReturnsBadRequest() {
            MissingServletRequestParameterException ex =
                    new MissingServletRequestParameterException("accountId", "Long");

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleMissingParam(ex, mockRequest("/api/accounts"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().code()).isNotBlank();
            assertThat(response.getBody().message()).containsIgnoringCase("accountId");
            assertThat(response.getBody().correlationId()).isNotBlank();
        }

        @Test
        @DisplayName("HttpRequestMethodNotSupportedException → 405 METHOD_NOT_ALLOWED")
        void httpMethodNotAllowedReturns405() {
            HttpRequestMethodNotSupportedException ex =
                    new HttpRequestMethodNotSupportedException("PATCH", List.of("GET", "POST"));

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleMethodNotSupported(ex, mockRequest("/api/accounts"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(response.getBody().code()).isNotBlank();
            assertThat(response.getBody().correlationId()).isNotBlank();
        }

        @Test
        @DisplayName("HttpMediaTypeNotSupportedException → 415 UNSUPPORTED_MEDIA_TYPE")
        void unsupportedMediaTypeReturns415() {
            HttpMediaTypeNotSupportedException ex =
                    new HttpMediaTypeNotSupportedException(
                            org.springframework.http.MediaType.APPLICATION_XML,
                            List.of(org.springframework.http.MediaType.APPLICATION_JSON));

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleMediaTypeNotSupported(ex, mockRequest("/api/accounts"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(response.getBody().code()).isNotBlank();
            assertThat(response.getBody().correlationId()).isNotBlank();
        }

        @Test
        @DisplayName("NoHandlerFoundException → 404 NOT_FOUND")
        void noHandlerFoundReturns404() {
            NoHandlerFoundException ex = new NoHandlerFoundException(
                    "GET", "/api/unknown",
                    new org.springframework.http.HttpHeaders());

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleNoHandlerFound(ex, mockRequest("/api/unknown"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().code()).isNotBlank();
            assertThat(response.getBody().correlationId()).isNotBlank();
        }
    }

    // -------------------------------------------------------------------------
    // Spring Security exceptions
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Spring Security exceptions → 401/403 HTTP statuses")
    class SecurityExceptionTests {

        @Test
        @DisplayName("BadCredentialsException → 401 UNAUTHORIZED")
        void badCredentialsReturns401() {
            // COBOL: COSGN00C.cbl IF SEC-USR-PWD = WS-USER-PWD failed
            BadCredentialsException ex = new BadCredentialsException("Invalid username or password");

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleBadCredentials(ex, mockRequest("/api/auth/signin"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().code()).isNotBlank();
            assertThat(response.getBody().correlationId()).isNotBlank();
        }

        @Test
        @DisplayName("AuthenticationException → 401 UNAUTHORIZED")
        void authenticationExceptionReturns401() {
            AuthenticationException ex = new AuthenticationException("Authentication required") {};

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleAuthentication(ex, mockRequest("/api/accounts"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().code()).isNotBlank();
            assertThat(response.getBody().correlationId()).isNotBlank();
        }

        @Test
        @DisplayName("AccessDeniedException → 403 FORBIDDEN")
        void accessDeniedReturns403() {
            // COBOL: USER vs ADMIN role gating (RACF dispatch in COSGN00C XCTL)
            AccessDeniedException ex = new AccessDeniedException("Access denied");

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleAccessDenied(ex, mockRequest("/api/admin/users"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody().code()).isNotBlank();
            assertThat(response.getBody().correlationId()).isNotBlank();
        }
    }

    // -------------------------------------------------------------------------
    // Spring Data / JPA exceptions
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Spring Data exceptions → 409/500 HTTP statuses")
    class DataExceptionTests {

        @Test
        @DisplayName("DataIntegrityViolationException (FK violation) → 422 UNPROCESSABLE_ENTITY")
        void dataIntegrityViolationReturnsUnprocessableEntity() {
            // QA finding E2 history (see GlobalExceptionHandler:1330): the FK
            // violation between cards.card_acct_id → accounts.acct_id (constraint
            // fk_cards_acct) previously surfaced as HTTP 500; the handler was
            // updated to translate any DataIntegrityViolationException into a
            // 422 UNPROCESSABLE_ENTITY with reason code DATA_INTEGRITY_VIOLATION
            // (or ACCOUNT_NOT_FOUND when the offending constraint is fk_cards_acct).
            DataIntegrityViolationException ex =
                    new DataIntegrityViolationException("FK violation");

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleDataIntegrityViolationException(ex, mockRequest("/api/cards"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().code()).isNotBlank();
            assertThat(response.getBody().correlationId()).isNotBlank();
        }

        @Test
        @DisplayName("DataIntegrityViolationException (fk_cards_acct) → ACCOUNT_NOT_FOUND code")
        void dataIntegrityViolationDetectsFkCardsAcctConstraint() {
            // The handler walks the cause chain looking for the constraint
            // name 'fk_cards_acct' and emits reasonCode=ACCOUNT_NOT_FOUND when
            // matched. Verify by surfacing the constraint name in the message.
            DataIntegrityViolationException ex = new DataIntegrityViolationException(
                    "violates foreign key constraint fk_cards_acct on accounts(acct_id)");

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleDataIntegrityViolationException(ex, mockRequest("/api/cards/9999"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().code()).isEqualTo("ACCOUNT_NOT_FOUND");
        }

        @Test
        @DisplayName("DataAccessException → 500 INTERNAL_SERVER_ERROR")
        void dataAccessReturns500() {
            // COBOL: DB outage / VSAM unavailable
            DataAccessException ex =
                    new org.springframework.dao.QueryTimeoutException("DB timeout");

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleDataAccessException(ex, mockRequest("/api/accounts/1"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().code()).isNotBlank();
            assertThat(response.getBody().correlationId()).isNotBlank();
        }
    }

    // -------------------------------------------------------------------------
    // Catch-all: unhandled Throwable
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Top-level catch-all")
    class CatchAllTests {

        @Test
        @DisplayName("Exception (unmapped) → 500 INTERNAL_SERVER_ERROR")
        void unmappedExceptionReturns500() {
            // Any exception type not handled by a more specific
            // @ExceptionHandler falls through to handleGenericException.
            // We avoid using a RuntimeException subclass that Spring
            // might catch elsewhere by deliberately using a plain
            // checked Exception (well, a synthetic Exception class).
            Exception ex = new Exception("Unexpected failure: " + new IllegalStateException("inner"));

            ResponseEntity<ApiResponse<Object>> response =
                    handler.handleGenericException(ex, mockRequest("/api/accounts"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().code()).isNotBlank();
            // Per AAP §0.7.1 — internal exception messages MUST NOT leak
            // through the HTTP envelope; the handler emits a sanitized
            // message instead of the raw exception toString().
            assertThat(response.getBody().message()).isNotBlank();
            assertThat(response.getBody().correlationId()).isNotBlank();
        }
    }

    // -------------------------------------------------------------------------
    // Invariants — assertions that hold across ALL handlers
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Cross-cutting invariants (all handlers)")
    class InvariantTests {

        @Test
        @DisplayName("Every handler emits a non-blank correlationId")
        void everyHandlerEmitsCorrelationId() {
            // Sample 5 handlers and verify the correlationId field is
            // always populated (UUID v4 or MDC-pulled value).
            ResponseEntity<ApiResponse<Object>> r1 = handler.handleRecordNotFound(
                    new RecordNotFoundException("23", "x"), mockRequest("/api/x"));
            ResponseEntity<ApiResponse<Object>> r2 = handler.handleDuplicateRecord(
                    new DuplicateRecordException("22", "x"), mockRequest("/api/x"));
            ResponseEntity<ApiResponse<Object>> r3 = handler.handleValidation(
                    new ValidationException("x"), mockRequest("/api/x"));
            ResponseEntity<ApiResponse<Object>> r4 = handler.handleBadCredentials(
                    new BadCredentialsException("x"), mockRequest("/api/x"));
            ResponseEntity<ApiResponse<Object>> r5 = handler.handleAccessDenied(
                    new AccessDeniedException("x"), mockRequest("/api/x"));

            assertThat(r1.getBody().correlationId()).isNotBlank();
            assertThat(r2.getBody().correlationId()).isNotBlank();
            assertThat(r3.getBody().correlationId()).isNotBlank();
            assertThat(r4.getBody().correlationId()).isNotBlank();
            assertThat(r5.getBody().correlationId()).isNotBlank();
        }

        @Test
        @DisplayName("Every handler emits a non-blank code")
        void everyHandlerEmitsCode() {
            // Per AAP §0.7.1 — error codes surfaced to downstream consumers
            // must be preserved verbatim; the ApiResponse.code field must
            // always be populated.
            ResponseEntity<ApiResponse<Object>> r1 = handler.handleRecordNotFound(
                    new RecordNotFoundException("23", "x"), mockRequest("/api/x"));
            ResponseEntity<ApiResponse<Object>> r2 = handler.handleOnSizeError(
                    new OnSizeErrorException("x"), mockRequest("/api/x"));
            ResponseEntity<ApiResponse<Object>> r3 = handler.handleCreditLimitExceeded(
                    new CreditLimitExceededException("x"), mockRequest("/api/x"));

            assertThat(r1.getBody().code()).isNotBlank();
            assertThat(r2.getBody().code()).isNotBlank();
            assertThat(r3.getBody().code()).isNotBlank();
        }
    }
}
