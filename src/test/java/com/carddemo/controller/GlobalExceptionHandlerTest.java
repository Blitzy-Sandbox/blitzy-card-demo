package com.carddemo.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import com.carddemo.dto.ErrorResponse;
import com.carddemo.exception.DateValidationException;
import com.carddemo.exception.DuplicateResourceException;
import com.carddemo.exception.FileProcessingException;
import com.carddemo.exception.FileStatusCode;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;

/**
 * Pure, context-free unit test for {@link GlobalExceptionHandler} — the single
 * {@code @RestControllerAdvice} that maps every exception to a
 * {@link ErrorResponse} JSON body with the correct HTTP status.
 *
 * <p>The suite loads no Spring context and no database; it instantiates the
 * handler directly, drives each {@code @ExceptionHandler} (and the framework
 * {@code handleMethodArgumentNotValid} override via the inherited dispatch
 * entry point), and asserts the status, domain {@code code}, message, path,
 * timestamp, {@code fieldErrors}, and MDC {@code correlationId}. It also pins
 * the security guarantees the migrated API must uphold: failed sign-on returns
 * a single generic message (no account-existence leak), rejected input values
 * are never echoed, and internal 500 detail (SQL, abend code/culprit) never
 * reaches the client. Design rationale lives in {@code docs/decision-log.md}.
 */
@DisplayName("GlobalExceptionHandler — centralized exception -> HTTP mapper")
class GlobalExceptionHandlerTest {

    /** MDC key the handler reads to propagate the correlation id into the body. */
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /** Request URI stamped on every request and asserted as the {@code path}. */
    private static final String REQUEST_URI = "/api/test/resource";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        request.setRequestURI(REQUEST_URI);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("ResourceNotFoundException -> 404 RECORD_NOT_FOUND")
    void resourceNotFound() {
        ResponseEntity<ErrorResponse> response =
                handler.handleCardDemoException(new ResourceNotFoundException("Account", "00000000001"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(404);
        assertThat(body.error()).isEqualTo("Not Found");
        assertThat(body.code()).isEqualTo("RECORD_NOT_FOUND");
        assertThat(body.message()).contains("Account");
        assertThat(body.path()).isEqualTo(REQUEST_URI);
        assertThat(body.timestamp()).isNotNull();
        assertThat(body.fieldErrors()).isEmpty();
    }

    @Test
    @DisplayName("DuplicateResourceException -> 409 DUPLICATE_KEY")
    void duplicateResource() {
        ResponseEntity<ErrorResponse> response =
                handler.handleCardDemoException(new DuplicateResourceException("User", "USER0001"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("DUPLICATE_KEY");
        assertThat(body.error()).isEqualTo("Conflict");
    }

    @Test
    @DisplayName("OptimisticLockConflictException -> 409 with record-changed message")
    void optimisticLockConflict() {
        ResponseEntity<ErrorResponse> response =
                handler.handleCardDemoException(new OptimisticLockConflictException(), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("OptimisticLockConflictException");
        assertThat(body.message()).isEqualTo("Record changed by some one else. Please review");
    }

    @Test
    @DisplayName("ValidationException with field errors -> 400 with populated fieldErrors (value-only)")
    void validationExceptionWithFieldErrors() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("acctId", "Account ID must be Numeric");
        fields.put("typeCd", "Type CD can NOT be empty");

        ResponseEntity<ErrorResponse> response =
                handler.handleCardDemoException(new ValidationException("Validation failed", fields), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(400);
        assertThat(body.fieldErrors()).hasSize(2);
        assertThat(body.fieldErrors()).extracting(ErrorResponse.FieldError::field)
                .containsExactlyInAnyOrder("acctId", "typeCd");
        assertThat(body.fieldErrors()).extracting(ErrorResponse.FieldError::message)
                .contains("Account ID must be Numeric", "Type CD can NOT be empty");
        // The field->message map carries no raw value, so the summary must be null.
        assertThat(body.fieldErrors()).allSatisfy(fe -> assertThat(fe.rejectedValueSummary()).isNull());
    }

    @Test
    @DisplayName("DateValidationException -> 400")
    void dateValidation() {
        ResponseEntity<ErrorResponse> response =
                handler.handleCardDemoException(new DateValidationException("Invalid date", "2024-13-40"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("DateValidationException");
        assertThat(body.error()).isEqualTo("Bad Request");
    }

    @Test
    @DisplayName("FileProcessingException -> 500 without leaking ABEND internals")
    void fileProcessing() {
        FileProcessingException ex = new FileProcessingException(
                "Unrecoverable I/O error posting transactions",
                "0999", "CBTRN02C", FileStatusCode.PERMANENT_IO_ERROR,
                new RuntimeException("VSAM status 30"));

        ResponseEntity<ErrorResponse> response = handler.handleCardDemoException(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(500);
        assertThat(body.code()).isEqualTo("PERMANENT_IO_ERROR");
        // ABEND internals (abend code, culprit) must never reach the client.
        assertThat(body.code()).doesNotContain("0999", "CBTRN02C");
        assertThat(body.message()).doesNotContain("0999", "CBTRN02C");
        assertThat(body.fieldErrors()).isEmpty();
    }

    @Test
    @DisplayName("MethodArgumentNotValidException -> 400 with fieldErrors and no rejected value leaked")
    void methodArgumentNotValid() throws Exception {
        MethodArgumentNotValidException ex = buildMethodArgumentNotValid();

        ResponseEntity<Object> response = handler.handleException(ex, new ServletWebRequest(request));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOf(ErrorResponse.class);
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(400);
        assertThat(body.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.fieldErrors()).hasSize(2);
        assertThat(body.fieldErrors()).extracting(ErrorResponse.FieldError::field)
                .containsExactlyInAnyOrder("userId", "typeCd");
        // SECURITY: the rejected value (which could be a password/PAN) must never be echoed.
        assertThat(body.fieldErrors()).allSatisfy(fe -> {
            assertThat(fe.rejectedValueSummary()).isNull();
            assertThat(fe.message()).doesNotContain("s3cr3t-value");
        });
    }

    @Test
    @DisplayName("ConstraintViolationException -> 400 with fieldErrors")
    void constraintViolation() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<ConstraintViolation<SampleBean>> violations = validator.validate(new SampleBean());
            ConstraintViolationException ex = new ConstraintViolationException(violations);

            ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(ex, request);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            ErrorResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(body.fieldErrors()).isNotEmpty();
            assertThat(body.fieldErrors()).extracting(ErrorResponse.FieldError::field).contains("typeCd");
            assertThat(body.fieldErrors()).extracting(ErrorResponse.FieldError::message)
                    .contains("Type CD can NOT be empty");
        }
    }

    @Test
    @DisplayName("BadCredentialsException -> 401 with a generic message (no account-existence leak)")
    void badCredentials() {
        // The legacy COSGN00C distinguished "User not found" from "Wrong Password"; the modern API must not.
        ResponseEntity<ErrorResponse> response =
                handler.handleBadCredentials(new BadCredentialsException("User USER0001 not found"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(401);
        assertThat(body.code()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(body.message()).isEqualTo("Invalid user id or password");
        assertThat(body.message()).doesNotContainIgnoringCase("not found");
        assertThat(body.message()).doesNotContainIgnoringCase("wrong password");
        assertThat(body.message()).doesNotContain("USER0001");
    }

    @Test
    @DisplayName("AccessDeniedException -> 403 ACCESS_DENIED")
    void accessDenied() {
        ResponseEntity<ErrorResponse> response =
                handler.handleAccessDenied(new AccessDeniedException("Admin only option"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("ACCESS_DENIED");
        assertThat(body.error()).isEqualTo("Forbidden");
    }

    @Test
    @DisplayName("Generic Exception -> 500 with a generic message (internal detail not leaked)")
    void genericException() {
        ResponseEntity<ErrorResponse> response =
                handler.handleGeneric(new IllegalStateException("SELECT secret FROM users WHERE 1=1"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(500);
        assertThat(body.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.message()).isEqualTo("An unexpected error occurred");
        assertThat(body.message()).doesNotContain("SELECT", "secret");
    }

    @Test
    @DisplayName("correlationId is copied from the MDC into the body when present")
    void correlationIdCopiedFromMdc() {
        MDC.put(CORRELATION_ID_MDC_KEY, "corr-xyz-789");

        ResponseEntity<ErrorResponse> response =
                handler.handleCardDemoException(new ResourceNotFoundException("Card", "1234"), request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().correlationId()).isEqualTo("corr-xyz-789");
    }

    @Test
    @DisplayName("correlationId is null when the MDC has none (never fabricated)")
    void correlationIdNullWhenAbsent() {
        MDC.clear();

        ResponseEntity<ErrorResponse> response =
                handler.handleGeneric(new RuntimeException("boom"), request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().correlationId()).isNull();
    }

    @Test
    @DisplayName("advice exposes unambiguous @ExceptionHandler mappings (initializes without conflict)")
    void adviceMappingsAreUnambiguous() {
        // ExceptionHandlerMethodResolver's constructor throws IllegalStateException on ambiguous
        // @ExceptionHandler mappings; successful construction proves the advice initializes and that
        // the Exception catch-all does not collide with the inherited framework handlers.
        ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

        assertThat(resolver.hasExceptionMappings()).isTrue();
        assertThat(resolver.resolveMethodByThrowable(new ResourceNotFoundException("Account", "1"))).isNotNull();
        assertThat(resolver.resolveMethodByThrowable(new IllegalStateException("unexpected"))).isNotNull();
    }

    /**
     * Builds a {@link MethodArgumentNotValidException} with two field errors, one of which carries a
     * (sensitive) rejected value, so the test can assert the value is never echoed into the response.
     *
     * @return a populated {@link MethodArgumentNotValidException}
     * @throws NoSuchMethodException if the reflective target method cannot be resolved
     */
    private static MethodArgumentNotValidException buildMethodArgumentNotValid() throws NoSuchMethodException {
        BindingResult binding = new BeanPropertyBindingResult(new Object(), "signonRequest");
        binding.addError(new org.springframework.validation.FieldError(
                "signonRequest", "userId", "s3cr3t-value", false, null, null, "Account ID must be Numeric"));
        binding.addError(new org.springframework.validation.FieldError(
                "signonRequest", "typeCd", "", false, null, null, "Type CD can NOT be empty"));
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyEndpoint", String.class), 0);
        return new MethodArgumentNotValidException(parameter, binding);
    }

    /**
     * Reflection target that provides a {@link MethodParameter} for constructing a
     * {@link MethodArgumentNotValidException}; never invoked at runtime.
     *
     * @param requestBody unused request-body placeholder parameter
     */
    private void dummyEndpoint(String requestBody) {
        // Intentionally empty — used only as a reflective handle for MethodParameter.
    }

    /** Minimal bean with a single failing constraint, used to produce a real constraint violation. */
    private static final class SampleBean {

        @NotBlank(message = "Type CD can NOT be empty")
        private String typeCd;
    }
}
