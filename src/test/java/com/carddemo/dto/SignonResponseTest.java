package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link SignonResponse}, the REST sign-on (authentication)
 * <em>response</em> body returned by the CardDemo sign-on transaction (online
 * transaction {@code CC00}, backing program {@code COSGN00C}, BMS map
 * {@code COSGN00}). It is the stateless replacement for the CICS
 * pseudo-conversational {@code COMMAREA} user context: the entire session travels
 * inside the issued JWT ({@link SignonResponse#token()}), and the remaining fields
 * simply echo the authenticated identity and mapped role (AAP&nbsp;&sect;0.8.4,
 * COMMAREA&nbsp;&rarr;&nbsp;JWT).
 *
 * <p>Identity fields derive from the {@code SEC-USER-DATA} record of copybook
 * {@code CSUSR01Y} (referenced by source SHA {@code 27d6c6f}, never copied into
 * the target): {@code SEC-USR-ID PIC X(08)} &rarr; {@code userId},
 * {@code SEC-USR-FNAME PIC X(20)} &rarr; {@code firstName},
 * {@code SEC-USR-LNAME PIC X(20)} &rarr; {@code lastName}, and
 * {@code SEC-USR-TYPE PIC X(01)} &rarr; {@code role} (mapped to a role name). The
 * record also declares {@code SEC-USR-PWD PIC X(08)} — a <em>plaintext</em>
 * password — which this response DTO deliberately does <strong>not</strong> map;
 * the tests below turn that security contract into an executable guarantee.</p>
 *
 * <p>The suite is organised into the three phases mandated for this DTO:</p>
 * <ol>
 *   <li><strong>JSON round-trip and key contract</strong> — the record survives a
 *       serialize/deserialize cycle intact, emits exactly the six expected JSON
 *       keys, keeps {@code userId} a fixed-width text value, and round-trips the
 *       {@code "Bearer"} token scheme;</li>
 *   <li><strong>Sensitive-data safety</strong> — neither the type's component set
 *       nor its serialized JSON ever exposes a password / password hash / pwd
 *       field (the {@code SEC-USR-PWD} parity guard), and
 *       {@link SignonResponse#toString()} redacts the credential-equivalent bearer
 *       token so it can never leak into a log line, stack trace, or error
 *       message;</li>
 *   <li><strong>Identity fidelity and role mapping</strong> — the
 *       {@code SEC-USR-TYPE} code is mapped exactly as production does: the
 *       administrator code {@code 'A'} yields {@link SignonResponse#ROLE_ADMIN}
 *       and every other value (including the regular-user code {@code 'U'}) yields
 *       {@link SignonResponse#ROLE_USER}, mirroring the COBOL
 *       {@code IF CDEMO-USRTYP-ADMIN ... ELSE ...} routing.</li>
 * </ol>
 *
 * <p>These are pure, framework-free unit tests: no Spring context and no
 * Testcontainers are loaded. All JSON handling flows through the shared,
 * production-mirroring {@link DtoTestSupport} helpers so that assertions match
 * exactly what the running application emits, and the tests run in milliseconds
 * toward the Gate&nbsp;8 (&ge;80%) JaCoCo threshold. The token/name literals below
 * are obvious, non-secret test fixtures; no COBOL source is reproduced. Rationale
 * lives in {@code docs/decision-log.md}.</p>
 */
@DisplayName("SignonResponse — signon (CC00) response DTO: JSON contract, no-credential safety, and identity/role fidelity")
class SignonResponseTest {

    /**
     * A representative bearer-token fixture. To {@link SignonResponse} the token is
     * an <em>opaque</em> {@code String}, so this deliberately uses an unmistakable,
     * non-functional placeholder rather than a real JWT: it carries no credential
     * material and, by construction, cannot match any provider secret pattern. It is
     * also composed so that it contains none of the forbidden credential substrings
     * ({@code password} / {@code passwordHash} / {@code pwd}), which keeps the
     * Phase&nbsp;2 whole-document JSON scan a meaningful assertion about the DTO
     * rather than an accident of the fixture.
     */
    private static final String SAMPLE_TOKEN = "test-bearer-token-SIGNON-FIXTURE-0001";

    /** A representative 8-character user identifier ({@code SEC-USR-ID PIC X(08)}). */
    private static final String SAMPLE_USER_ID = "ADMIN001";

    /** A representative first name well within the 20-character legacy width. */
    private static final String SAMPLE_FIRST_NAME = "Grace";

    /** A representative last name well within the 20-character legacy width. */
    private static final String SAMPLE_LAST_NAME = "Hopper";

    /** The administrator {@code SEC-USR-TYPE} code ({@code 'A'}). */
    private static final String ADMIN_USER_TYPE = "A";

    /** The regular-user {@code SEC-USR-TYPE} code ({@code 'U'}). */
    private static final String REGULAR_USER_TYPE = "U";

    /** A 9-character user id: one over the legacy {@code X(08)} width. */
    private static final String NINE_CHARACTER_USER_ID = "ADMIN0001";

    /**
     * A reusable {@link TypeReference} for the top-level JSON object so a DTO can be
     * deserialized into a {@code Map} without any raw type or unchecked cast,
     * keeping the suite clean under {@code -Xlint:all}.
     */
    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<Map<String, Object>>() { };

    /**
     * Builds a canonical, fully-populated administrator sign-on response used across
     * the suite. Every component is non-null so key-contract assertions are robust
     * regardless of any null-inclusion serialization policy.
     *
     * @return a fully populated {@link SignonResponse} with the admin role
     */
    private static SignonResponse sampleAdminResponse() {
        return new SignonResponse(
                SAMPLE_TOKEN,
                SignonResponse.BEARER,
                SAMPLE_USER_ID,
                SAMPLE_FIRST_NAME,
                SAMPLE_LAST_NAME,
                SignonResponse.ROLE_ADMIN);
    }

    // ------------------------------------------------------------------
    // Phase 1 — JSON round-trip and key contract.
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 1 — JSON round-trip and key contract")
    class JsonRoundTrip {

        @Test
        @DisplayName("round-trips through JSON preserving equality and every component")
        void roundTripPreservesAllComponents() {
            SignonResponse original = sampleAdminResponse();

            SignonResponse restored = DtoTestSupport.roundTrip(original, SignonResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.token()).isEqualTo(SAMPLE_TOKEN);
            assertThat(restored.tokenType()).isEqualTo(SignonResponse.BEARER);
            assertThat(restored.userId()).isEqualTo(SAMPLE_USER_ID);
            assertThat(restored.firstName()).isEqualTo(SAMPLE_FIRST_NAME);
            assertThat(restored.lastName()).isEqualTo(SAMPLE_LAST_NAME);
            assertThat(restored.role()).isEqualTo(SignonResponse.ROLE_ADMIN);
        }

        @Test
        @DisplayName("serializes exactly the six expected keys: token, tokenType, userId, firstName, lastName, role")
        void serializesExactlyTheSixExpectedKeys() {
            String json = DtoTestSupport.toJson(sampleAdminResponse());

            Map<String, Object> fields = DtoTestSupport.fromJson(json, JSON_OBJECT);

            // containsOnlyKeys is exhaustive: it proves both that the six expected
            // keys are present and that no additional (e.g. credential) key leaks in.
            assertThat(fields).containsOnlyKeys(
                    "token", "tokenType", "userId", "firstName", "lastName", "role");
        }

        @Test
        @DisplayName("the \"Bearer\" token scheme is carried on the wire and survives a round-trip")
        void tokenTypeBearerRoundTrips() {
            SignonResponse original = sampleAdminResponse();

            // The production constant is the literal "Bearer"; assert the literal so a
            // change to the scheme name breaks the build.
            assertThat(original.tokenType()).isEqualTo("Bearer");

            Map<String, Object> fields = DtoTestSupport.fromJson(DtoTestSupport.toJson(original), JSON_OBJECT);
            assertThat(fields.get("tokenType"))
                    .as("tokenType must serialize as the JSON string \"Bearer\"")
                    .isEqualTo("Bearer");

            assertThat(DtoTestSupport.roundTrip(original, SignonResponse.class).tokenType())
                    .as("the Bearer scheme must survive a full serialize/deserialize cycle")
                    .isEqualTo("Bearer");
        }

        @Test
        @DisplayName("userId stays a JSON string, preserving 8-char width and leading zeros")
        void userIdStaysStringPreservingWidth() {
            // An all-digit identifier is the adversarial case: a naive numeric mapping
            // would drop the leading zeros and coerce "00000001" to the number 1.
            // SEC-USR-ID is PIC X(08), so it must remain 8 characters of text.
            String numericLookingId = "00000001";
            SignonResponse original = new SignonResponse(
                    SAMPLE_TOKEN, SignonResponse.BEARER, numericLookingId,
                    SAMPLE_FIRST_NAME, SAMPLE_LAST_NAME, SignonResponse.ROLE_USER);

            String json = DtoTestSupport.toJson(original);
            Map<String, Object> fields = DtoTestSupport.fromJson(json, JSON_OBJECT);

            assertThat(fields.get("userId"))
                    .as("userId must deserialize to a String, never a numeric node")
                    .isInstanceOf(String.class)
                    .isEqualTo(numericLookingId);

            assertThat(DtoTestSupport.roundTrip(original, SignonResponse.class).userId())
                    .as("the 8-character width and leading zeros survive a full round-trip")
                    .isEqualTo(numericLookingId)
                    .hasSize(8);
        }
    }

    // ------------------------------------------------------------------
    // Phase 2 — sensitive-data safety (SEC-USR-PWD is never exposed; the
    // bearer token is redacted in diagnostic output). CRITICAL.
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 2 — sensitive-data safety (never expose a password; redact the token)")
    class SensitiveDataSafety {

        @Test
        @DisplayName("declares no password-like component (password / passwordHash / pwd)")
        void declaresNoPasswordLikeComponent() {
            // Reflection proof: no record component name matches a credential token,
            // so plaintext SEC-USR-PWD can never be surfaced by this response DTO.
            DtoTestSupport.assertNoComponentNamed(SignonResponse.class, "password", "passwordHash", "pwd");
        }

        @Test
        @DisplayName("serialized JSON contains no password / passwordHash / pwd key")
        void jsonNeverContainsCredentialKeys() {
            String json = DtoTestSupport.toJson(sampleAdminResponse());

            assertThat(json)
                    .as("sign-on-response JSON must never carry credential material (SEC-USR-PWD is not mapped)")
                    .doesNotContainIgnoringCase("password")
                    .doesNotContainIgnoringCase("passwordHash")
                    .doesNotContainIgnoringCase("pwd");
        }

        @Test
        @DisplayName("toString() redacts the JWT token but still shows the non-sensitive identity/role fields")
        void toStringRedactsTokenButKeepsIdentity() {
            SignonResponse response = sampleAdminResponse();

            String rendered = response.toString();

            assertThat(rendered)
                    .as("toString() must never leak the bearer token (a credential-equivalent secret)")
                    .doesNotContain(SAMPLE_TOKEN);
            assertThat(rendered)
                    .as("toString() should substitute a redaction marker for the token")
                    .containsAnyOf("REDACTED", "***");
            assertThat(rendered)
                    .as("toString() should still expose the non-sensitive identity and role fields")
                    .contains(SAMPLE_USER_ID)
                    .contains(SAMPLE_FIRST_NAME)
                    .contains(SAMPLE_LAST_NAME)
                    .contains(SignonResponse.ROLE_ADMIN);
        }

        @Test
        @DisplayName("the token accessor and wire JSON still carry the real token (redaction is presentation-only)")
        void tokenRemainsAvailableOnWireContract() {
            SignonResponse response = sampleAdminResponse();

            // Redaction in toString() must not alter the stored data or the wire
            // contract: the client legitimately needs the issued token to authenticate
            // subsequent requests (the JWT is the whole session context).
            assertThat(response.token())
                    .as("the accessor must return the exact issued token")
                    .isEqualTo(SAMPLE_TOKEN);
            assertThat(DtoTestSupport.toJson(response))
                    .as("the response body must carry the token so the client can use it")
                    .contains(SAMPLE_TOKEN);
        }
    }

    // ------------------------------------------------------------------
    // Phase 3 — identity fidelity and SEC-USR-TYPE role mapping.
    // 'A' (CDEMO-USRTYP-ADMIN) -> ROLE_ADMIN; every other value -> ROLE_USER.
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 3 — identity fidelity and SEC-USR-TYPE role mapping")
    class IdentityAndRoleMapping {

        @Test
        @DisplayName("of(...) defaults tokenType to Bearer and maps admin type 'A' to ROLE_ADMIN")
        void factoryMapsAdminType() {
            SignonResponse response = SignonResponse.of(
                    SAMPLE_TOKEN, SAMPLE_USER_ID, SAMPLE_FIRST_NAME, SAMPLE_LAST_NAME, ADMIN_USER_TYPE);

            assertThat(response.token()).isEqualTo(SAMPLE_TOKEN);
            assertThat(response.tokenType())
                    .as("of(...) must default the scheme to the Bearer constant")
                    .isEqualTo(SignonResponse.BEARER);
            assertThat(response.userId()).isEqualTo(SAMPLE_USER_ID);
            assertThat(response.firstName()).isEqualTo(SAMPLE_FIRST_NAME);
            assertThat(response.lastName()).isEqualTo(SAMPLE_LAST_NAME);
            assertThat(response.role())
                    .as("SEC-USR-TYPE 'A' must map to the administrator role name")
                    .isEqualTo(SignonResponse.ROLE_ADMIN);
        }

        @Test
        @DisplayName("of(...) maps regular-user type 'U' to ROLE_USER")
        void factoryMapsRegularType() {
            SignonResponse response = SignonResponse.of(
                    SAMPLE_TOKEN, SAMPLE_USER_ID, SAMPLE_FIRST_NAME, SAMPLE_LAST_NAME, REGULAR_USER_TYPE);

            assertThat(response.role())
                    .as("SEC-USR-TYPE 'U' must map to the regular-user role name")
                    .isEqualTo(SignonResponse.ROLE_USER);
            assertThat(response.tokenType()).isEqualTo(SignonResponse.BEARER);
        }

        @ParameterizedTest(name = "SEC-USR-TYPE [{0}] maps to role [{1}]")
        @CsvSource({
            "A, ADMIN",
            "U, USER",
            "a, ADMIN",
            "u, USER",
            "X, USER"
        })
        @DisplayName("mapRole normalizes SEC-USR-TYPE: only 'A' (any case) yields ADMIN; all else yields USER")
        void mapRoleNormalizesUserType(String secUsrType, String expectedRole) {
            // Production trims and upper-cases (Locale.ROOT) before comparing, so the
            // lower-case 'a'/'u' cases exercise the normalization path, and 'X' proves
            // that any non-admin code routes to the regular menu (COMEN01C).
            assertThat(SignonResponse.mapRole(secUsrType)).isEqualTo(expectedRole);
        }

        @Test
        @DisplayName("mapRole treats null, empty, and whitespace-only SEC-USR-TYPE as a regular user")
        void mapRoleBlankIsRegularUser() {
            assertThat(SignonResponse.mapRole(null))
                    .as("a null SEC-USR-TYPE defaults to the regular-user role")
                    .isEqualTo(SignonResponse.ROLE_USER);
            assertThat(SignonResponse.mapRole(""))
                    .as("an empty SEC-USR-TYPE defaults to the regular-user role")
                    .isEqualTo(SignonResponse.ROLE_USER);
            assertThat(SignonResponse.mapRole("   "))
                    .as("a whitespace-only SEC-USR-TYPE defaults to the regular-user role")
                    .isEqualTo(SignonResponse.ROLE_USER);
        }

        @Test
        @DisplayName("space-padded admin code (fixed-width PIC X(01)) still maps to ROLE_ADMIN")
        void mapRoleSpacePaddedAdmin() {
            // SEC-USR-TYPE is read from a fixed-width, space-padded SEC-USER-DATA
            // record; the surrounding pad must not defeat the administrator match.
            assertThat(SignonResponse.mapRole("A ")).isEqualTo(SignonResponse.ROLE_ADMIN);
            assertThat(SignonResponse.mapRole(" A ")).isEqualTo(SignonResponse.ROLE_ADMIN);
        }

        @ParameterizedTest(name = "role [{0}] serializes as a JSON string and round-trips unchanged")
        @ValueSource(strings = {"ADMIN", "USER"})
        @DisplayName("both mapped role names round-trip through JSON as strings")
        void roleValuesRoundTrip(String role) {
            SignonResponse original = new SignonResponse(
                    SAMPLE_TOKEN, SignonResponse.BEARER, SAMPLE_USER_ID,
                    SAMPLE_FIRST_NAME, SAMPLE_LAST_NAME, role);

            SignonResponse restored = DtoTestSupport.roundTrip(original, SignonResponse.class);
            assertThat(restored.role())
                    .as("the mapped role name must survive the round-trip")
                    .isEqualTo(role);

            assertThat(DtoTestSupport.fromJson(DtoTestSupport.toJson(original), JSON_OBJECT).get("role"))
                    .as("role must serialize as a JSON string")
                    .isEqualTo(role);
        }

        @Test
        @DisplayName("an 8-character userId satisfies the @Size(max = 8) width constraint")
        void eightCharacterUserIdHasNoViolations() {
            SignonResponse response = sampleAdminResponse();

            Set<ConstraintViolation<SignonResponse>> violations = DtoTestSupport.validate(response);

            assertThat(violations)
                    .as("an 8-character SEC-USR-ID echo must not violate any constraint")
                    .isEmpty();
        }

        @Test
        @DisplayName("a userId longer than 8 characters violates @Size(max = 8) on 'userId'")
        void userIdLongerThanEightViolatesSize() {
            SignonResponse response = new SignonResponse(
                    SAMPLE_TOKEN, SignonResponse.BEARER, NINE_CHARACTER_USER_ID,
                    SAMPLE_FIRST_NAME, SAMPLE_LAST_NAME, SignonResponse.ROLE_ADMIN);

            Set<ConstraintViolation<SignonResponse>> violations = DtoTestSupport.validate(response);

            assertThat(violations).singleElement().satisfies(violation -> {
                assertThat(violation.getPropertyPath().toString()).isEqualTo("userId");
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Size.class);
            });
        }
    }
}
