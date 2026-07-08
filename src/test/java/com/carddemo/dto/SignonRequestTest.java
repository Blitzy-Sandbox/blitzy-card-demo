package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link SignonRequest}, the REST sign-on (authentication) request
 * body that replaces the legacy CardDemo {@code COSGN00} BMS screen (CICS
 * transaction {@code CC00}, backing program {@code COSGN00C}).
 *
 * <p>The DTO carries the two 8-character alphanumeric inputs migrated from the BMS
 * symbolic map — {@code USERIDI} and {@code PASSWDI}, each {@code PIC X(8)} — which
 * are verified downstream against the user-security record ({@code CSUSR01Y}:
 * {@code SEC-USR-ID PIC X(08)}, {@code SEC-USR-PWD PIC X(08)}). Three independent
 * concerns are exercised here, mirroring the migration's parity and security
 * requirements:</p>
 * <ol>
 *   <li><strong>Jakarta validation</strong> — {@code @NotBlank} reproduces the
 *       mandatory-field edit performed by {@code CICS RECEIVE MAP}, and
 *       {@code @Size(max = 8)} reproduces the fixed legacy {@code X(08)} field
 *       width. Violations are asserted by their property path
 *       ({@link ConstraintViolation#getPropertyPath()}) and by the offending
 *       constraint annotation, so an accidental annotation change breaks the
 *       build.</li>
 *   <li><strong>Password redaction</strong> — {@link SignonRequest#toString()}
 *       must never render the plaintext password (the {@code SEC-USR-PWD}
 *       sensitivity), so it can never leak into a log line, stack trace, or error
 *       message.</li>
 *   <li><strong>JSON binding</strong> — the request must deserialize from the
 *       on-the-wire contract and round-trip without loss. Because production does
 *       not annotate the password write-only, the serialized form intentionally
 *       retains the password key so the value can bind; the redaction guard above
 *       applies to diagnostic output only, not to the wire contract.</li>
 * </ol>
 *
 * <p>The tests are deliberately pure and framework-free: they use the shared
 * programmatic {@link jakarta.validation.Validator} and {@code ObjectMapper}
 * exposed by {@link DtoTestSupport} — no Spring context, no Testcontainers, and no
 * mocks — so they run in milliseconds and contribute fast line coverage toward the
 * Gate&nbsp;8 (&ge;80%) JaCoCo threshold. The credential literals used below are
 * obvious, non-secret test fixtures. Source constructs are referenced by SHA
 * {@code 27d6c6f}; no COBOL source is reproduced. Rationale is documented in
 * {@code docs/decision-log.md}.</p>
 */
@DisplayName("SignonRequest — validation, password redaction, and JSON binding")
class SignonRequestTest {

    /** A valid user id at the {@code X(08)} boundary (exactly 8 characters). */
    private static final String VALID_USER_ID = "ADMIN001";

    /** A valid password at the {@code X(08)} boundary (exactly 8 characters). */
    private static final String VALID_PASSWORD = "PASS1234";

    /**
     * A distinctive plaintext password used by the redaction tests; it must never
     * appear in {@link SignonRequest#toString()} output.
     */
    private static final String SECRET_PASSWORD = "SECRET99";

    /** A 9-character value: one over the legacy {@code X(08)} width. */
    private static final String NINE_CHARACTERS = "ABCDE1234";

    // ------------------------------------------------------------------
    // Phase 1 — Jakarta Bean Validation (programmatic Validator).
    // @NotBlank reproduces the CICS RECEIVE MAP mandatory-field edit;
    // @Size(max = 8) reproduces the fixed COBOL X(08) field width.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A well-formed request (both fields <= 8 chars) has no violations")
    void validRequestHasNoViolations() {
        SignonRequest request = new SignonRequest(VALID_USER_ID, VALID_PASSWORD);

        Set<ConstraintViolation<SignonRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Single-character values are accepted (only max length and non-blank are constrained)")
    void minimalNonBlankValuesAreAccepted() {
        SignonRequest request = new SignonRequest("A", "B");

        Set<ConstraintViolation<SignonRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).isEmpty();
    }

    @ParameterizedTest(name = "blank userId [{0}] is rejected by @NotBlank")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("Null, empty, or whitespace-only userId violates @NotBlank on 'userId'")
    void blankUserIdViolatesNotBlank(String blankUserId) {
        SignonRequest request = new SignonRequest(blankUserId, VALID_PASSWORD);

        Set<ConstraintViolation<SignonRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("userId");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(NotBlank.class);
        });
    }

    @ParameterizedTest(name = "blank password [{0}] is rejected by @NotBlank")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("Null, empty, or whitespace-only password violates @NotBlank on 'password'")
    void blankPasswordViolatesNotBlank(String blankPassword) {
        SignonRequest request = new SignonRequest(VALID_USER_ID, blankPassword);

        Set<ConstraintViolation<SignonRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("password");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(NotBlank.class);
        });
    }

    @Test
    @DisplayName("A userId longer than 8 characters violates @Size(max = 8) on 'userId'")
    void userIdLongerThanEightViolatesSize() {
        SignonRequest request = new SignonRequest(NINE_CHARACTERS, VALID_PASSWORD);

        Set<ConstraintViolation<SignonRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("userId");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Size.class);
        });
    }

    @Test
    @DisplayName("A password longer than 8 characters violates @Size(max = 8) on 'password'")
    void passwordLongerThanEightViolatesSize() {
        SignonRequest request = new SignonRequest(VALID_USER_ID, NINE_CHARACTERS);

        Set<ConstraintViolation<SignonRequest>> violations = DtoTestSupport.validate(request);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("password");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Size.class);
        });
    }

    // ------------------------------------------------------------------
    // Phase 2 — toString() password redaction (sensitive-data safety).
    // The plaintext password (SEC-USR-PWD) must never be rendered.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("toString() omits the plaintext password but still shows the userId")
    void toStringRedactsPasswordButKeepsUserId() {
        SignonRequest request = new SignonRequest(VALID_USER_ID, SECRET_PASSWORD);

        String rendered = request.toString();

        assertThat(rendered)
                .as("toString() must never leak the plaintext password")
                .doesNotContain(SECRET_PASSWORD);
        assertThat(rendered)
                .as("toString() should still expose the non-sensitive userId")
                .contains(VALID_USER_ID);
        assertThat(rendered)
                .as("toString() should substitute a redaction marker for the password")
                .containsAnyOf("REDACTED", "***");
    }

    @Test
    @DisplayName("Accessors return the exact constructor values (redaction is presentation-only)")
    void accessorsReturnConstructorValues() {
        SignonRequest request = new SignonRequest(VALID_USER_ID, SECRET_PASSWORD);

        // The masking applied by toString() must not alter the stored data: the real
        // password is retained so the service layer can compare it to the stored
        // BCrypt hash (Decision Log D-002 / Constraint C-003).
        assertThat(request.userId()).isEqualTo(VALID_USER_ID);
        assertThat(request.password()).isEqualTo(SECRET_PASSWORD);
    }

    // ------------------------------------------------------------------
    // Phase 3 — JSON binding / round-trip. The request must bind from the
    // wire contract; production does not annotate the password write-only,
    // so the serialized form retains it (redaction is toString-only).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Deserializes the wire contract into the correct fields")
    void deserializesFromJson() {
        String json = "{\"userId\":\"ADMIN001\",\"password\":\"PASS1234\"}";

        SignonRequest request = DtoTestSupport.fromJson(json, SignonRequest.class);

        assertThat(request.userId()).isEqualTo("ADMIN001");
        assertThat(request.password()).isEqualTo("PASS1234");
    }

    @Test
    @DisplayName("A JSON round-trip preserves both components")
    void jsonRoundTripPreservesBothFields() {
        SignonRequest original = new SignonRequest(VALID_USER_ID, VALID_PASSWORD);

        SignonRequest restored = DtoTestSupport.roundTrip(original, SignonRequest.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.userId()).isEqualTo(VALID_USER_ID);
        assertThat(restored.password()).isEqualTo(VALID_PASSWORD);
    }

    @Test
    @DisplayName("Serialization retains the password key so the request can bind on the wire")
    void serializedJsonRetainsPasswordKeyForBinding() {
        String json = DtoTestSupport.toJson(new SignonRequest(VALID_USER_ID, SECRET_PASSWORD));

        // The wire contract for a request body legitimately carries the password so
        // it can be deserialized/bound; only diagnostic output (toString) is masked.
        assertThat(json).contains("\"userId\"").contains(VALID_USER_ID);
        assertThat(json).contains("\"password\"").contains(SECRET_PASSWORD);
    }
}
