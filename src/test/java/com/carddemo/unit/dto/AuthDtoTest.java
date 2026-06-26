package com.carddemo.unit.dto;

import com.carddemo.dto.AuthDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure JUnit 5 unit tests for {@link AuthDto} and its nested signon request/response records.
 *
 * <p>These tests are deliberately Spring-free: there is no application context, no
 * Testcontainers, and no Mockito. They pin the DTO contract two complementary ways:
 * <ol>
 *   <li><strong>Shape via reflection</strong> &mdash; the nested {@code record} component
 *       names, order, and types must match the field contract of the BMS mapset
 *       {@code app/bms/COSGN00.bms} and program {@code app/cbl/COSGN00C.cbl} @ SHA
 *       {@code 27d6c6f}: {@code USERID X(8)}, {@code PASSWD X(8)}, and the single-character
 *       {@code SEC-USR-TYPE X(1)} ({@code 'A'} routes to the admin menu {@code COADM01C}).</li>
 *   <li><strong>Bean Validation</strong> &mdash; a standalone jakarta {@link Validator}
 *       confirms the {@code @Size(max = 8)} constraints fire on the expected property
 *       paths and that the inclusive {@code X(8)} boundary passes. {@code SigninRequest}
 *       carries no {@code @NotBlank}: blank presence is validated at the service layer so
 *       the byte-exact COSGN00C literals are emitted (P-2; DECISION_LOG D-056), so a blank
 *       (within-length) field must raise no DTO-layer violation. {@code userType} on
 *       {@code SigninResponse} is capped at one character while the JWT {@code token} is
 *       unconstrained.</li>
 * </ol>
 *
 * <p>Decimal-exactness guardrail (AAP &sect;0.6.1): no record component may be {@code double},
 * {@code float}, {@code Double}, or {@code Float}. The signon contract is textual, so every
 * component must be a {@link String}.
 */
@DisplayName("AuthDto - signon request/response contract (COSGN00 @ 27d6c6f)")
class AuthDtoTest {

    /**
     * Retained in a static field for the lifetime of the test class so the factory reference
     * is never garbage-collected mid-run and is not flagged as an ignored/leaked resource.
     */
    private static ValidatorFactory validatorFactory;

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    // ---------------------------------------------------------------------
    // Reflection helpers (shared by the shape and decimal-exactness tests)
    // ---------------------------------------------------------------------

    /** Returns the declared record component names of {@code recordType} in declaration order. */
    private static List<String> recordComponentNames(Class<?> recordType) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : recordType.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /** Asserts that every record component of {@code recordType} is a {@link String}. */
    private static void assertEveryComponentIsString(Class<?> recordType) {
        for (RecordComponent component : recordType.getRecordComponents()) {
            assertThat(component.getType())
                    .as("component '%s' of %s must be java.lang.String",
                            component.getName(), recordType.getSimpleName())
                    .isEqualTo(String.class);
        }
    }

    /** Asserts that no record component of {@code recordType} is a floating-point type. */
    private static void assertNoFloatingPointComponents(Class<?> recordType) {
        for (RecordComponent component : recordType.getRecordComponents()) {
            assertThat(component.getType())
                    .as("component '%s' of %s must not be floating-point (AAP 0.6.1)",
                            component.getName(), recordType.getSimpleName())
                    .isNotIn(double.class, float.class, Double.class, Float.class);
        }
    }

    // ---------------------------------------------------------------------
    // Record shape
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("SigninRequest has exactly two components in order: userId, password")
    void signinRequestHasTwoComponentsInOrder() {
        assertThat(AuthDto.SigninRequest.class.getRecordComponents())
                .as("SigninRequest models the two unprotected map fields USERID and PASSWD")
                .hasSize(2);
        assertThat(recordComponentNames(AuthDto.SigninRequest.class))
                .containsExactly("userId", "password");
    }

    @Test
    @DisplayName("SigninResponse has exactly three components in order: token, userId, userType")
    void signinResponseHasThreeComponentsInOrder() {
        assertThat(AuthDto.SigninResponse.class.getRecordComponents())
                .as("SigninResponse carries the issued JWT plus the authenticated identity")
                .hasSize(3);
        assertThat(recordComponentNames(AuthDto.SigninResponse.class))
                .containsExactly("token", "userId", "userType");
    }

    @Test
    @DisplayName("every component of both records is a String")
    void allComponentsAreStrings() {
        assertEveryComponentIsString(AuthDto.SigninRequest.class);
        assertEveryComponentIsString(AuthDto.SigninResponse.class);
    }

    @Test
    @DisplayName("AuthDto is a non-instantiable holder (single private no-arg constructor)")
    void authDtoIsNonInstantiableHolder() {
        var constructors = AuthDto.class.getDeclaredConstructors();
        assertThat(constructors)
                .as("AuthDto should declare exactly one (private) constructor")
                .hasSize(1);
        assertThat(Modifier.isPrivate(constructors[0].getModifiers()))
                .as("AuthDto's only constructor must be private (it is a type holder)")
                .isTrue();
        assertThat(constructors[0].getParameterCount())
                .as("AuthDto's private constructor must be no-arg")
                .isZero();
    }

    // ---------------------------------------------------------------------
    // Bean Validation - SigninRequest
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("SigninRequest: blank userId raises NO DTO-layer violation (presence delegated to AuthService for byte-exact text)")
    void signinRequestBlankUserIdHasNoDtoViolation() {
        // P-2 parity: SigninRequest deliberately carries no @NotBlank. A blank
        // userId must NOT be rejected at the DTO boundary, so that AuthService.signin
        // can raise the byte-exact COSGN00C literal "Please enter User ID ..." instead
        // of the generic Bean Validation default. Service-layer coverage lives in
        // AuthServiceTest.blankUserId_raisesValidationException. See DECISION_LOG D-056.
        AuthDto.SigninRequest request = new AuthDto.SigninRequest("", "secret");
        Set<ConstraintViolation<AuthDto.SigninRequest>> violations = validator.validate(request);
        assertThat(violations)
                .as("a blank (but within-length) userId must not raise any DTO-layer constraint violation")
                .isEmpty();
    }

    @Test
    @DisplayName("SigninRequest: blank password raises NO DTO-layer violation (presence delegated to AuthService for byte-exact text)")
    void signinRequestBlankPasswordHasNoDtoViolation() {
        // P-2 parity: a blank password must reach AuthService so it can raise the
        // byte-exact COSGN00C literal "Please enter Password ..." rather than the
        // generic "must not be blank". Service-layer coverage lives in
        // AuthServiceTest.blankPassword_raisesValidationException. See DECISION_LOG D-056.
        AuthDto.SigninRequest request = new AuthDto.SigninRequest("user1", "  ");
        Set<ConstraintViolation<AuthDto.SigninRequest>> violations = validator.validate(request);
        assertThat(violations)
                .as("a blank (but within-length) password must not raise any DTO-layer constraint violation")
                .isEmpty();
    }

    @Test
    @DisplayName("SigninRequest: 9-char userId violates @Size(max = 8) on path 'userId'")
    void signinRequestNineCharUserIdFailsSize() {
        AuthDto.SigninRequest request = new AuthDto.SigninRequest("123456789", "secret");
        Set<ConstraintViolation<AuthDto.SigninRequest>> violations = validator.validate(request);
        assertThat(violations)
                .as("a 9-char userId exceeds USERID X(8) and must violate on 'userId'")
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("userId"));
    }

    @Test
    @DisplayName("SigninRequest: 9-char password violates @Size(max = 8) on path 'password'")
    void signinRequestNineCharPasswordFailsSize() {
        AuthDto.SigninRequest request = new AuthDto.SigninRequest("user1", "123456789");
        Set<ConstraintViolation<AuthDto.SigninRequest>> violations = validator.validate(request);
        assertThat(violations)
                .as("a 9-char password exceeds PASSWD X(8) and must violate on 'password'")
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("password"));
    }

    @Test
    @DisplayName("SigninRequest: 8-char userId and password (X(8) boundary) pass validation")
    void signinRequestEightCharBoundaryPasses() {
        AuthDto.SigninRequest request = new AuthDto.SigninRequest("12345678", "12345678");
        Set<ConstraintViolation<AuthDto.SigninRequest>> violations = validator.validate(request);
        assertThat(violations)
                .as("exactly 8 characters is the inclusive X(8) upper bound and must pass")
                .isEmpty();
    }

    // ---------------------------------------------------------------------
    // Bean Validation - SigninResponse
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("SigninResponse: userType width 1 - 'AB' violates, 'A' + long token pass")
    void signinResponseUserTypeWidthOne() {
        AuthDto.SigninResponse tooWide =
                new AuthDto.SigninResponse("jwt.token.value", "12345678", "AB");
        Set<ConstraintViolation<AuthDto.SigninResponse>> wideViolations = validator.validate(tooWide);
        assertThat(wideViolations)
                .as("a 2-char userType exceeds SEC-USR-TYPE X(1) and must violate on 'userType'")
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("userType"));

        String longToken = "header.payload.signature.that.is.far.longer.than.eight.characters";
        AuthDto.SigninResponse valid =
                new AuthDto.SigninResponse(longToken, "12345678", "A");
        Set<ConstraintViolation<AuthDto.SigninResponse>> validViolations = validator.validate(valid);
        assertThat(validViolations)
                .as("single-char userType 'A' and an arbitrarily long (unconstrained) token must pass")
                .isEmpty();
    }

    // ---------------------------------------------------------------------
    // Decimal-exactness guardrail (AAP 0.6.1)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("no record component is double/float/Double/Float (AAP 0.6.1 decimal exactness)")
    void noFloatingPointComponents() {
        assertNoFloatingPointComponents(AuthDto.SigninRequest.class);
        assertNoFloatingPointComponents(AuthDto.SigninResponse.class);
    }
}
