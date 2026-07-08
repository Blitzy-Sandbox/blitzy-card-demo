package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link UserCreateRequest}, the REST "add user" request body that
 * replaces the legacy CardDemo {@code COUSR01} BMS screen (CICS transaction
 * {@code CU01}, backing program {@code COUSR01C}).
 *
 * <p>The DTO carries the five editable inputs migrated from the BMS symbolic map
 * ({@code USERIDI}, {@code FNAMEI}, {@code LNAMEI}, {@code PASSWDI},
 * {@code USRTYPEI}), which map one-to-one onto the file-based user-security record
 * ({@code CSUSR01Y}: {@code SEC-USR-ID PIC X(08)}, {@code SEC-USR-FNAME PIC X(20)},
 * {@code SEC-USR-LNAME PIC X(20)}, {@code SEC-USR-PWD PIC X(08)},
 * {@code SEC-USR-TYPE PIC X(01)}). Four independent concerns are exercised, mirroring
 * the migration's parity and security requirements:</p>
 * <ol>
 *   <li><strong>Valid instances</strong> — a well-formed request for each of the two
 *       legacy roles ({@code A} = administrator, {@code U} = regular user) produces
 *       zero constraint violations, and values exactly at each field's maximum width
 *       are accepted (the {@code @Size} bound is inclusive).</li>
 *   <li><strong>Jakarta validation failures</strong> — {@code @NotBlank} reproduces
 *       the mandatory-field edit of {@code CICS RECEIVE MAP}; {@code @Size} reproduces
 *       the fixed COBOL {@code PIC X(n)} field widths (8/20/20/8/1); and
 *       {@code @Pattern("[AU]")} reproduces the case-sensitive role edit of the
 *       {@code USRTYPEI} field. Every violation is asserted by its property path
 *       ({@link ConstraintViolation#getPropertyPath()}) and by the offending
 *       constraint annotation, so an accidental annotation change breaks the
 *       build.</li>
 *   <li><strong>Password redaction</strong> — {@link UserCreateRequest#toString()}
 *       must never render the plaintext password (the {@code SEC-USR-PWD}
 *       sensitivity, Constraint C-003 / Decision Log D-002), so the credential can
 *       never leak into a log line, stack trace, or error message; the non-sensitive
 *       fields remain visible for troubleshooting.</li>
 *   <li><strong>JSON binding</strong> — the request must deserialize from the
 *       on-the-wire contract and round-trip without loss. Production does not mark
 *       the password write-only, so the serialized form retains it for binding; the
 *       redaction guard above applies to diagnostic output only, never to the wire
 *       contract.</li>
 * </ol>
 *
 * <p>The tests are deliberately pure and framework-free: they use only the shared
 * programmatic {@link jakarta.validation.Validator} and {@code ObjectMapper} exposed
 * by {@link DtoTestSupport} — no Spring context, no Testcontainers, and no mocks — so
 * they run in milliseconds and contribute fast line coverage toward the Gate&nbsp;8
 * (&ge;80%) JaCoCo threshold. The credential literals below are obvious, non-secret
 * test fixtures. Legacy source constructs are referenced by commit SHA
 * {@code 27d6c6f}; no COBOL source is reproduced. Rationale is documented in
 * {@code docs/decision-log.md}.</p>
 */
@DisplayName("UserCreateRequest — validation, password redaction, and JSON binding")
class UserCreateRequestTest {

    /** A valid user id at the {@code X(08)} boundary (exactly 8 characters). */
    private static final String VALID_USER_ID = "USER0001";

    /** A valid first name well within the {@code X(20)} width. */
    private static final String VALID_FIRST_NAME = "John";

    /** A valid last name well within the {@code X(20)} width. */
    private static final String VALID_LAST_NAME = "Doe";

    /** A valid password at the {@code X(08)} boundary (exactly 8 characters). */
    private static final String VALID_PASSWORD = "PASS1234";

    /** The administrator role code ({@code SEC-USR-TYPE} = {@code A}). */
    private static final String ADMIN_TYPE = "A";

    /** The regular-user role code ({@code SEC-USR-TYPE} = {@code U}). */
    private static final String USER_TYPE = "U";

    /**
     * A distinctive plaintext password used by the redaction tests; it must never
     * appear in {@link UserCreateRequest#toString()} output.
     */
    private static final String SECRET_PASSWORD = "SECRET99";

    /** A 9-character value: one over the legacy {@code X(08)} width (userId/password). */
    private static final String NINE_CHARACTERS = "ABCDE1234";

    /** A 21-character value: one over the legacy {@code X(20)} width (firstName/lastName). */
    private static final String TWENTY_ONE_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTU";

    // ------------------------------------------------------------------
    // Fixture builders. Each varies exactly one field and keeps the other
    // four valid, so a single-field failure test yields exactly one
    // violation (assertable via singleElement()).
    // ------------------------------------------------------------------

    /** @return a fully valid administrator request (all five fields legal). */
    private static UserCreateRequest validRequest() {
        return new UserCreateRequest(VALID_USER_ID, VALID_FIRST_NAME, VALID_LAST_NAME, VALID_PASSWORD, ADMIN_TYPE);
    }

    private static UserCreateRequest withUserId(String userId) {
        return new UserCreateRequest(userId, VALID_FIRST_NAME, VALID_LAST_NAME, VALID_PASSWORD, ADMIN_TYPE);
    }

    private static UserCreateRequest withFirstName(String firstName) {
        return new UserCreateRequest(VALID_USER_ID, firstName, VALID_LAST_NAME, VALID_PASSWORD, ADMIN_TYPE);
    }

    private static UserCreateRequest withLastName(String lastName) {
        return new UserCreateRequest(VALID_USER_ID, VALID_FIRST_NAME, lastName, VALID_PASSWORD, ADMIN_TYPE);
    }

    private static UserCreateRequest withPassword(String password) {
        return new UserCreateRequest(VALID_USER_ID, VALID_FIRST_NAME, VALID_LAST_NAME, password, ADMIN_TYPE);
    }

    private static UserCreateRequest withUserType(String userType) {
        return new UserCreateRequest(VALID_USER_ID, VALID_FIRST_NAME, VALID_LAST_NAME, VALID_PASSWORD, userType);
    }

    /**
     * Asserts that validation produced exactly one violation, on the expected
     * property path and raised by the expected constraint annotation.
     *
     * @param violations         the set returned by the programmatic validator
     * @param expectedPath       the property path expected to be in violation
     * @param expectedConstraint the constraint annotation type expected to fire
     */
    private static void assertSingleViolation(
            Set<ConstraintViolation<UserCreateRequest>> violations,
            String expectedPath,
            Class<? extends Annotation> expectedConstraint) {
        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString())
                    .as("violated property path")
                    .isEqualTo(expectedPath);
            assertThat(violation.getConstraintDescriptor().getAnnotation())
                    .as("violated constraint annotation on '%s'", expectedPath)
                    .isInstanceOf(expectedConstraint);
        });
    }

    // ==================================================================
    // Phase 1 — valid instances (zero violations).
    // ==================================================================

    @Test
    @DisplayName("A well-formed administrator request (userType='A') has no violations")
    void validAdministratorRequestHasNoViolations() {
        Set<ConstraintViolation<UserCreateRequest>> violations = DtoTestSupport.validate(validRequest());

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("A well-formed regular-user request (userType='U') has no violations")
    void validRegularUserRequestHasNoViolations() {
        Set<ConstraintViolation<UserCreateRequest>> violations = DtoTestSupport.validate(withUserType(USER_TYPE));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Values exactly at each field's maximum width pass validation (inclusive @Size)")
    void maximumWidthBoundaryValuesHaveNoViolations() {
        // userId = 8, firstName = 20, lastName = 20, password = 8, userType = 1.
        UserCreateRequest request = new UserCreateRequest(
                "12345678",
                "ABCDEFGHIJKLMNOPQRST",
                "ABCDEFGHIJKLMNOPQRST",
                "12345678",
                USER_TYPE);

        Set<ConstraintViolation<UserCreateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).isEmpty();
    }

    // ==================================================================
    // Phase 2a — @NotBlank (mandatory-field edit from CICS RECEIVE MAP).
    // Only userId/firstName/lastName/password are @NotBlank-tested: a blank
    // userType would additionally trip @Pattern, so it is covered separately.
    // ==================================================================

    @ParameterizedTest(name = "blank userId [{0}] is rejected by @NotBlank")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("Null, empty, or whitespace-only userId violates @NotBlank on 'userId'")
    void blankUserIdViolatesNotBlank(String blankUserId) {
        Set<ConstraintViolation<UserCreateRequest>> violations = DtoTestSupport.validate(withUserId(blankUserId));

        assertSingleViolation(violations, "userId", NotBlank.class);
    }

    @ParameterizedTest(name = "blank firstName [{0}] is rejected by @NotBlank")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("Null, empty, or whitespace-only firstName violates @NotBlank on 'firstName'")
    void blankFirstNameViolatesNotBlank(String blankFirstName) {
        Set<ConstraintViolation<UserCreateRequest>> violations = DtoTestSupport.validate(withFirstName(blankFirstName));

        assertSingleViolation(violations, "firstName", NotBlank.class);
    }

    @ParameterizedTest(name = "blank lastName [{0}] is rejected by @NotBlank")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("Null, empty, or whitespace-only lastName violates @NotBlank on 'lastName'")
    void blankLastNameViolatesNotBlank(String blankLastName) {
        Set<ConstraintViolation<UserCreateRequest>> violations = DtoTestSupport.validate(withLastName(blankLastName));

        assertSingleViolation(violations, "lastName", NotBlank.class);
    }

    @ParameterizedTest(name = "blank password [{0}] is rejected by @NotBlank")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("Null, empty, or whitespace-only password violates @NotBlank on 'password'")
    void blankPasswordViolatesNotBlank(String blankPassword) {
        Set<ConstraintViolation<UserCreateRequest>> violations = DtoTestSupport.validate(withPassword(blankPassword));

        assertSingleViolation(violations, "password", NotBlank.class);
    }

    // ==================================================================
    // Phase 2b — @Size (fixed COBOL PIC X(n) field widths: 8/20/20/8).
    // Each value is one character over its limit; the field is otherwise
    // non-blank so only @Size fires.
    // ==================================================================

    @Test
    @DisplayName("A userId of 9 characters violates @Size(max = 8) on 'userId'")
    void userIdOverEightViolatesSize() {
        Set<ConstraintViolation<UserCreateRequest>> violations = DtoTestSupport.validate(withUserId(NINE_CHARACTERS));

        assertSingleViolation(violations, "userId", Size.class);
    }

    @Test
    @DisplayName("A firstName of 21 characters violates @Size(max = 20) on 'firstName'")
    void firstNameOverTwentyViolatesSize() {
        Set<ConstraintViolation<UserCreateRequest>> violations =
                DtoTestSupport.validate(withFirstName(TWENTY_ONE_CHARACTERS));

        assertSingleViolation(violations, "firstName", Size.class);
    }

    @Test
    @DisplayName("A lastName of 21 characters violates @Size(max = 20) on 'lastName'")
    void lastNameOverTwentyViolatesSize() {
        Set<ConstraintViolation<UserCreateRequest>> violations =
                DtoTestSupport.validate(withLastName(TWENTY_ONE_CHARACTERS));

        assertSingleViolation(violations, "lastName", Size.class);
    }

    @Test
    @DisplayName("A password of 9 characters violates @Size(max = 8) on 'password'")
    void passwordOverEightViolatesSize() {
        Set<ConstraintViolation<UserCreateRequest>> violations = DtoTestSupport.validate(withPassword(NINE_CHARACTERS));

        assertSingleViolation(violations, "password", Size.class);
    }

    // ==================================================================
    // Phase 2c — @Pattern("[AU]") on userType. The role edit is
    // case-sensitive: only uppercase 'A' or 'U' is accepted. Every listed
    // value is a single character, so @Size(max = 1) passes and only
    // @Pattern fires.
    // ==================================================================

    @ParameterizedTest(name = "userType [{0}] outside the [AU] set is rejected by @Pattern")
    @ValueSource(strings = {"X", "a", "u", "1"})
    @DisplayName("A userType other than 'A'/'U' (incl. lowercase) violates @Pattern on 'userType'")
    void invalidUserTypeViolatesPattern(String invalidUserType) {
        Set<ConstraintViolation<UserCreateRequest>> violations =
                DtoTestSupport.validate(withUserType(invalidUserType));

        assertSingleViolation(violations, "userType", Pattern.class);
    }

    // ==================================================================
    // Phase 3 — toString() password redaction (CRITICAL sensitive-data
    // safety). The plaintext password (SEC-USR-PWD) must never be rendered.
    // ==================================================================

    @Test
    @DisplayName("toString() omits the plaintext password but keeps the non-sensitive fields")
    void toStringRedactsPasswordButKeepsOtherFields() {
        UserCreateRequest request = new UserCreateRequest(
                VALID_USER_ID, VALID_FIRST_NAME, VALID_LAST_NAME, SECRET_PASSWORD, ADMIN_TYPE);

        String rendered = request.toString();

        assertThat(rendered)
                .as("toString() must never leak the plaintext password")
                .doesNotContain(SECRET_PASSWORD);
        assertThat(rendered)
                .as("toString() should substitute a redaction marker for the password")
                .containsAnyOf("REDACTED", "***");
        assertThat(rendered)
                .as("toString() should still expose the non-sensitive fields for troubleshooting")
                .contains(VALID_USER_ID)
                .contains(VALID_FIRST_NAME)
                .contains(VALID_LAST_NAME);
    }

    @Test
    @DisplayName("Accessors return the exact constructor values (redaction is presentation-only)")
    void accessorsReturnConstructorValues() {
        UserCreateRequest request = new UserCreateRequest(
                VALID_USER_ID, VALID_FIRST_NAME, VALID_LAST_NAME, SECRET_PASSWORD, ADMIN_TYPE);

        // The masking applied by toString() must not alter the stored data: the real
        // password is retained so the user service can BCrypt-hash it before persisting
        // (Decision Log D-002 / Constraint C-003).
        assertThat(request.userId()).isEqualTo(VALID_USER_ID);
        assertThat(request.firstName()).isEqualTo(VALID_FIRST_NAME);
        assertThat(request.lastName()).isEqualTo(VALID_LAST_NAME);
        assertThat(request.password()).isEqualTo(SECRET_PASSWORD);
        assertThat(request.userType()).isEqualTo(ADMIN_TYPE);
    }

    // ==================================================================
    // Phase 4 — JSON binding / round-trip. The request must bind from the
    // wire contract; production does not annotate the password write-only,
    // so the serialized form retains it (redaction is toString-only).
    // ==================================================================

    @Test
    @DisplayName("Deserializes the wire contract into the correct fields")
    void deserializesFromJson() {
        String json = "{\"userId\":\"USER0001\",\"firstName\":\"John\",\"lastName\":\"Doe\","
                + "\"password\":\"PASS1234\",\"userType\":\"A\"}";

        UserCreateRequest request = DtoTestSupport.fromJson(json, UserCreateRequest.class);

        assertThat(request.userId()).isEqualTo(VALID_USER_ID);
        assertThat(request.firstName()).isEqualTo(VALID_FIRST_NAME);
        assertThat(request.lastName()).isEqualTo(VALID_LAST_NAME);
        assertThat(request.password()).isEqualTo(VALID_PASSWORD);
        assertThat(request.userType()).isEqualTo(ADMIN_TYPE);
    }

    @Test
    @DisplayName("A JSON round-trip preserves every component")
    void jsonRoundTripPreservesAllFields() {
        UserCreateRequest original = validRequest();

        UserCreateRequest restored = DtoTestSupport.roundTrip(original, UserCreateRequest.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.userId()).isEqualTo(VALID_USER_ID);
        assertThat(restored.firstName()).isEqualTo(VALID_FIRST_NAME);
        assertThat(restored.lastName()).isEqualTo(VALID_LAST_NAME);
        assertThat(restored.password()).isEqualTo(VALID_PASSWORD);
        assertThat(restored.userType()).isEqualTo(ADMIN_TYPE);
    }

    @Test
    @DisplayName("userId stays a JSON string on the wire and after binding")
    void userIdStaysString() {
        String json = DtoTestSupport.toJson(validRequest());

        // The identifier must serialize as a quoted JSON string, never coerced to a
        // number; the leading-zero-safe legacy X(08) contract depends on it.
        assertThat(json).contains("\"userId\":\"" + VALID_USER_ID + "\"");

        UserCreateRequest restored = DtoTestSupport.fromJson(json, UserCreateRequest.class);
        assertThat(restored.userId()).isInstanceOf(String.class).isEqualTo(VALID_USER_ID);
    }
}
