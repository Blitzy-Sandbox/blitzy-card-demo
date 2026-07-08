package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link UserUpdateRequest}, the REST <em>Update User</em> request
 * body that replaces the legacy CardDemo {@code COUSR02} BMS screen (CICS
 * transaction {@code CU02}, backing program {@code COUSR02C}).
 *
 * <p>The DTO carries the four editable user attributes migrated from the BMS
 * symbolic map {@code COUSR2AI} ({@code app/cpy-bms/COUSR02.CPY}, referenced by
 * source SHA {@code 27d6c6f}; no COBOL is reproduced here), which are in turn
 * backed by the {@code SEC-USER-DATA} record layout ({@code CSUSR01Y.cpy}):
 * {@code FNAME}/{@code SEC-USR-FNAME} ({@code PIC X(20)}),
 * {@code LNAME}/{@code SEC-USR-LNAME} ({@code PIC X(20)}),
 * {@code PASSWD}/{@code SEC-USR-PWD} ({@code PIC X(8)}), and
 * {@code USRTYPE}/{@code SEC-USR-TYPE} ({@code PIC X(1)}). Five concerns are
 * exercised, mirroring the migration's parity and security requirements:</p>
 * <ol>
 *   <li><strong>Valid instances</strong> — every field is <em>optional</em> on an
 *       update (no {@code @NotBlank}); the COBOL {@code X(n)} widths survive as
 *       {@code @Size} bounds. A supplied password at the {@code X(08)} boundary,
 *       an absent (null/blank) password, and a wholly empty patch all validate
 *       cleanly.</li>
 *   <li><strong>No {@code userId} in the body</strong> — the user identifier
 *       ({@code COUSR02} field {@code USRIDIN}, {@code PIC X(8)}) selects
 *       <em>which</em> user is updated and is carried as the URL <em>path</em>
 *       variable, never the payload; it must not be a component of this record.</li>
 *   <li><strong>Validation failures</strong> — over-width names/password violate
 *       {@code @Size}; a role code outside {@code [AU]} violates {@code @Pattern}.
 *       Violations are asserted by property path
 *       ({@link ConstraintViolation#getPropertyPath()}) and offending annotation so
 *       an accidental annotation change breaks the build.</li>
 *   <li><strong>Password redaction</strong> — {@link UserUpdateRequest#toString()}
 *       must never render the plaintext password ({@code SEC-USR-PWD} sensitivity,
 *       Constraint&nbsp;C-003), so it can never leak into a log line, stack trace,
 *       or diagnostic message.</li>
 *   <li><strong>JSON binding</strong> — the request deserializes from the wire
 *       contract, an omitted password binds to {@code null} (the "leave unchanged"
 *       semantics), and a populated request round-trips without loss.</li>
 * </ol>
 *
 * <p>The tests are deliberately pure and framework-free: they use only the shared
 * programmatic {@link jakarta.validation.Validator} and {@code ObjectMapper}
 * exposed by {@link DtoTestSupport} — no Spring context, no Testcontainers, and no
 * mocks — so they run in milliseconds and contribute fast line coverage toward the
 * Gate&nbsp;8 (&ge;80%) JaCoCo threshold. The credential literals used below are
 * obvious, non-secret test fixtures. Rationale is documented in
 * {@code docs/decision-log.md}, never in code comments.</p>
 */
@DisplayName("UserUpdateRequest — optional fields, no userId in body, validation, redaction, JSON binding")
class UserUpdateRequestTest {

    /** A valid given name well within the {@code X(20)} width. */
    private static final String VALID_FIRST_NAME = "John";

    /** A valid family name well within the {@code X(20)} width. */
    private static final String VALID_LAST_NAME = "Doe";

    /** A valid new password at the {@code X(08)} boundary (exactly 8 characters). */
    private static final String VALID_PASSWORD = "NEWPASS1";

    /** A valid regular-user role code (one of {@code [AU]}). */
    private static final String VALID_USER_TYPE = "U";

    /**
     * A distinctive plaintext password used by the redaction tests; it must never
     * appear in {@link UserUpdateRequest#toString()} output. Exactly 8 characters,
     * so it is itself a <em>valid</em> password value.
     */
    private static final String SECRET_PASSWORD = "SECRET99";

    /**
     * The exact marker string {@link UserUpdateRequest#toString()} substitutes for
     * a non-null password (mirrors the production {@code REDACTED_PASSWORD} value).
     */
    private static final String REDACTION_MARKER = "<redacted>";

    /** A name exactly at the {@code @Size(max = 20)} boundary (still valid). */
    private static final String TWENTY_CHARACTERS = "A".repeat(20);

    /** A name one character over the {@code @Size(max = 20)} bound. */
    private static final String TWENTY_ONE_CHARACTERS = "A".repeat(21);

    /** A password one character over the {@code @Size(max = 8)} bound. */
    private static final String NINE_CHARACTER_PASSWORD = "ABCDE1234";

    // ------------------------------------------------------------------
    // Phase 1 — valid instances (programmatic Validator).
    // Every field is optional on update: only @Size / @Pattern apply, and
    // there is no @NotBlank, so omitting a field is always permitted.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A fully-populated request within all widths has no violations")
    void fullyPopulatedRequestHasNoViolations() {
        UserUpdateRequest request =
                new UserUpdateRequest(VALID_FIRST_NAME, VALID_LAST_NAME, VALID_PASSWORD, VALID_USER_TYPE);

        Set<ConstraintViolation<UserUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Names at the 20-char boundary and an 8-char password are accepted")
    void boundaryLengthValuesAreAccepted() {
        UserUpdateRequest request =
                new UserUpdateRequest(TWENTY_CHARACTERS, TWENTY_CHARACTERS, VALID_PASSWORD, VALID_USER_TYPE);

        Set<ConstraintViolation<UserUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).isEmpty();
    }

    @ParameterizedTest(name = "absent password [{0}] is accepted (optional on update)")
    @NullAndEmptySource
    @DisplayName("An absent (null or blank) password is accepted — omitting it leaves the credential unchanged")
    void absentPasswordIsAccepted(String absentPassword) {
        UserUpdateRequest request =
                new UserUpdateRequest(VALID_FIRST_NAME, VALID_LAST_NAME, absentPassword, VALID_USER_TYPE);

        Set<ConstraintViolation<UserUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations)
                .as("password is optional on update; '%s' must not raise a violation", absentPassword)
                .isEmpty();
    }

    @ParameterizedTest(name = "userType [{0}] is accepted")
    @ValueSource(strings = {"A", "U"})
    @DisplayName("The role codes 'A' (Admin) and 'U' (Regular User) both validate")
    void acceptedUserTypesHaveNoViolations(String userType) {
        UserUpdateRequest request =
                new UserUpdateRequest(VALID_FIRST_NAME, VALID_LAST_NAME, VALID_PASSWORD, userType);

        Set<ConstraintViolation<UserUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("A wholly empty patch (all components null) is valid — nothing is changed")
    void allNullRequestIsValid() {
        UserUpdateRequest request = new UserUpdateRequest(null, null, null, null);

        Set<ConstraintViolation<UserUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations)
                .as("an update with no supplied fields must be a legal no-op patch")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // Phase 2 — no userId component in the body (CRITICAL).
    // userId is the URL path variable (PUT /api/users/{userId}); keeping it
    // out of the body prevents a mismatch between resource and payload.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The request body exposes no 'userId' component — it is a path variable")
    void bodyHasNoUserIdComponent() {
        DtoTestSupport.assertNoComponentNamed(UserUpdateRequest.class, "userId");
    }

    @Test
    @DisplayName("The body's components are exactly firstName, lastName, password, userType")
    void bodyExposesOnlyTheFourEditableFields() {
        Set<String> names = DtoTestSupport.componentNames(UserUpdateRequest.class);

        // componentNames() lower-cases each name for case-insensitive comparison.
        assertThat(names).containsExactlyInAnyOrder("firstname", "lastname", "password", "usertype");
        assertThat(names)
                .as("userId must be carried on the URL path, never in the request body")
                .doesNotContain("userid");
    }

    // ------------------------------------------------------------------
    // Phase 3 — validation failures. @Size reproduces the fixed COBOL X(n)
    // widths; @Pattern("[AU]") reproduces the two-value role edit.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A firstName longer than 20 characters violates @Size(max = 20) on 'firstName'")
    void firstNameOverMaxViolatesSize() {
        UserUpdateRequest request =
                new UserUpdateRequest(TWENTY_ONE_CHARACTERS, VALID_LAST_NAME, VALID_PASSWORD, VALID_USER_TYPE);

        Set<ConstraintViolation<UserUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("firstName");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Size.class);
        });
    }

    @Test
    @DisplayName("A lastName longer than 20 characters violates @Size(max = 20) on 'lastName'")
    void lastNameOverMaxViolatesSize() {
        UserUpdateRequest request =
                new UserUpdateRequest(VALID_FIRST_NAME, TWENTY_ONE_CHARACTERS, VALID_PASSWORD, VALID_USER_TYPE);

        Set<ConstraintViolation<UserUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("lastName");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Size.class);
        });
    }

    @Test
    @DisplayName("A supplied password longer than 8 characters violates @Size(max = 8) on 'password'")
    void passwordOverMaxViolatesSize() {
        UserUpdateRequest request =
                new UserUpdateRequest(VALID_FIRST_NAME, VALID_LAST_NAME, NINE_CHARACTER_PASSWORD, VALID_USER_TYPE);

        Set<ConstraintViolation<UserUpdateRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("password");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Size.class);
        });
    }

    @ParameterizedTest(name = "userType [{0}] is rejected by @Pattern")
    @ValueSource(strings = {"Z", "a", "1"})
    @DisplayName("A single-character role code outside [AU] violates @Pattern on 'userType'")
    void invalidUserTypeViolatesPattern(String invalidUserType) {
        UserUpdateRequest request =
                new UserUpdateRequest(VALID_FIRST_NAME, VALID_LAST_NAME, VALID_PASSWORD, invalidUserType);

        Set<ConstraintViolation<UserUpdateRequest>> violations = DtoTestSupport.validate(request);

        // A single character satisfies @Size(max = 1), so @Pattern is the only failure.
        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("userType");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Pattern.class);
        });
    }

    // ------------------------------------------------------------------
    // Phase 4 — toString() password redaction (sensitive-data safety).
    // The plaintext password (SEC-USR-PWD) must never be rendered.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("toString() redacts a supplied password but still shows the non-sensitive fields")
    void toStringRedactsSuppliedPassword() {
        UserUpdateRequest request =
                new UserUpdateRequest(VALID_FIRST_NAME, VALID_LAST_NAME, SECRET_PASSWORD, VALID_USER_TYPE);

        String rendered = request.toString();

        assertThat(rendered)
                .as("toString() must never leak the plaintext password")
                .doesNotContain(SECRET_PASSWORD);
        assertThat(rendered)
                .as("toString() should substitute the redaction marker for the password")
                .contains(REDACTION_MARKER);
        assertThat(rendered)
                .as("toString() should still expose the non-sensitive name/role fields")
                .contains(VALID_FIRST_NAME)
                .contains(VALID_LAST_NAME)
                .contains(VALID_USER_TYPE);
    }

    @Test
    @DisplayName("Accessors return the exact constructor values (redaction is presentation-only)")
    void accessorsReturnConstructorValues() {
        UserUpdateRequest request =
                new UserUpdateRequest(VALID_FIRST_NAME, VALID_LAST_NAME, SECRET_PASSWORD, VALID_USER_TYPE);

        // Masking applied by toString() must not alter the stored data: the real
        // password is retained so the service layer can hash it with BCrypt before
        // persistence (Decision Log D-002 / Constraint C-003).
        assertThat(request.firstName()).isEqualTo(VALID_FIRST_NAME);
        assertThat(request.lastName()).isEqualTo(VALID_LAST_NAME);
        assertThat(request.password()).isEqualTo(SECRET_PASSWORD);
        assertThat(request.userType()).isEqualTo(VALID_USER_TYPE);
    }

    @Test
    @DisplayName("toString() renders an absent password as 'null', distinct from the redaction marker")
    void toStringRendersAbsentPasswordAsNull() {
        UserUpdateRequest request =
                new UserUpdateRequest(VALID_FIRST_NAME, VALID_LAST_NAME, null, VALID_USER_TYPE);

        String rendered = request.toString();

        assertThat(rendered)
                .as("a null password must be shown as 'null' so 'unchanged' stays distinguishable")
                .contains("password=null");
        assertThat(rendered)
                .as("the redaction marker is only used for a supplied password")
                .doesNotContain(REDACTION_MARKER);
    }

    // ------------------------------------------------------------------
    // Phase 5 — JSON binding / round-trip. The body binds from the wire
    // contract; an omitted password binds to null; userId is never emitted.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Deserializes a full wire contract into the correct fields")
    void deserializesFullBody() {
        String json = "{\"firstName\":\"John\",\"lastName\":\"Doe\",\"password\":\"NEWPASS1\",\"userType\":\"U\"}";

        UserUpdateRequest request = DtoTestSupport.fromJson(json, UserUpdateRequest.class);

        assertThat(request.firstName()).isEqualTo("John");
        assertThat(request.lastName()).isEqualTo("Doe");
        assertThat(request.password()).isEqualTo("NEWPASS1");
        assertThat(request.userType()).isEqualTo("U");
    }

    @Test
    @DisplayName("A body omitting the password binds it to null (leave-unchanged semantics)")
    void deserializesBodyWithoutPassword() {
        String json = "{\"firstName\":\"John\",\"lastName\":\"Doe\",\"userType\":\"U\"}";

        UserUpdateRequest request = DtoTestSupport.fromJson(json, UserUpdateRequest.class);

        assertThat(request.password())
                .as("an omitted password must bind to null so the stored credential is left unchanged")
                .isNull();
        assertThat(request.firstName()).isEqualTo("John");
        assertThat(request.lastName()).isEqualTo("Doe");
        assertThat(request.userType()).isEqualTo("U");
    }

    @Test
    @DisplayName("A JSON round-trip preserves all four components")
    void jsonRoundTripPreservesAllFields() {
        UserUpdateRequest original =
                new UserUpdateRequest(VALID_FIRST_NAME, VALID_LAST_NAME, VALID_PASSWORD, VALID_USER_TYPE);

        UserUpdateRequest restored = DtoTestSupport.roundTrip(original, UserUpdateRequest.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.firstName()).isEqualTo(VALID_FIRST_NAME);
        assertThat(restored.lastName()).isEqualTo(VALID_LAST_NAME);
        assertThat(restored.password()).isEqualTo(VALID_PASSWORD);
        assertThat(restored.userType()).isEqualTo(VALID_USER_TYPE);
    }

    @Test
    @DisplayName("The serialized body carries no 'userId' key — it is a path variable")
    void serializedBodyHasNoUserIdKey() {
        String json = DtoTestSupport.toJson(
                new UserUpdateRequest(VALID_FIRST_NAME, VALID_LAST_NAME, VALID_PASSWORD, VALID_USER_TYPE));

        assertThat(json)
                .as("userId is addressed via the URL path and must never appear in the serialized body")
                .doesNotContain("userId")
                .doesNotContain("userid");
    }
}
