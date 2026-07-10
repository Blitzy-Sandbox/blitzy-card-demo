package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link UserResponse}, the immutable response body returned by
 * the CardDemo user-management endpoints. It is the shared read / confirmation
 * view behind the legacy online transactions {@code CU00} (list users),
 * {@code CU01} (add user), {@code CU02} (update user) and {@code CU03}
 * (view / delete user), programs {@code COUSR00C}–{@code COUSR03C}. Its field
 * layout is translated from the BMS symbolic map {@code COUSR03}
 * ({@code app/cpy-bms/COUSR03.CPY}), whose display fields are backed by the
 * {@code SEC-USER-DATA} record of copybook {@code CSUSR01Y}
 * ({@code app/cpy/CSUSR01Y.cpy}); source is referenced by SHA {@code 27d6c6f} and
 * never copied into the target.
 *
 * <p>The record exposes exactly five text components —
 * {@code userId} ({@code SEC-USR-ID PIC X(08)}),
 * {@code firstName} ({@code SEC-USR-FNAME PIC X(20)}),
 * {@code lastName} ({@code SEC-USR-LNAME PIC X(20)}),
 * {@code userType} ({@code SEC-USR-TYPE PIC X(01)}) and an optional
 * {@code message} (operation confirmation, {@code null} for a plain read). It
 * deliberately does <strong>not</strong> map {@code SEC-USR-PWD PIC X(08)}, the
 * plaintext password carried by the same COBOL record; the tests below turn that
 * omission into an executable guarantee.</p>
 *
 * <p>The suite is organised into the three phases mandated for this DTO:</p>
 * <ol>
 *   <li><strong>JSON round-trip and key contract</strong> — the record survives a
 *       serialize/deserialize cycle intact and emits exactly the five expected
 *       JSON keys, with {@code userId} preserved as a fixed-width text value and a
 *       {@code null} {@code message} (plain read) round-tripping cleanly;</li>
 *   <li><strong>Sensitive-data safety</strong> — neither the type's component set
 *       nor its serialized JSON ever exposes a password, password hash, or
 *       {@code pwd} field (the {@code SEC-USR-PWD} parity guard);</li>
 *   <li><strong>User-type values and confirmation message</strong> — the
 *       single-character {@code userType} round-trips for both the administrator
 *       ({@code "A"}) and regular ({@code "U"}) values defined by
 *       {@code SEC-USR-TYPE}, and the human-readable {@code message} survives the
 *       wire contract intact.</li>
 * </ol>
 *
 * <p>These are pure, framework-free unit tests: no Spring context and no
 * Testcontainers are loaded. All JSON handling flows through the shared,
 * production-mirroring {@link DtoTestSupport} helpers so that assertions match
 * exactly what the running application would emit, and the tests run in
 * milliseconds toward the Gate&nbsp;8 (&ge;80%) coverage threshold. The literals
 * used below are obvious, non-secret test fixtures; no COBOL source is reproduced
 * and rationale lives in {@code docs/decision-log.md}.</p>
 */
@DisplayName("UserResponse — user CRUD response DTO: JSON contract, no-credential safety, user-type and message")
class UserResponseTest {

    /** A representative 8-character user identifier ({@code SEC-USR-ID PIC X(08)}). */
    private static final String SAMPLE_USER_ID = "USER0001";

    /** A representative first name well within the 20-character legacy width. */
    private static final String SAMPLE_FIRST_NAME = "Ada";

    /** A representative last name well within the 20-character legacy width. */
    private static final String SAMPLE_LAST_NAME = "Lovelace";

    /** The administrator user type ({@code SEC-USR-TYPE} value {@code "A"}). */
    private static final String ADMIN_USER_TYPE = "A";

    /** The regular user type ({@code SEC-USR-TYPE} value {@code "U"}). */
    private static final String REGULAR_USER_TYPE = "U";

    /**
     * A representative operation-confirmation message such as an add / update /
     * delete endpoint returns. Chosen so it contains none of the forbidden
     * credential tokens, keeping the JSON credential-scan assertions meaningful.
     */
    private static final String SAMPLE_MESSAGE = "User USER0001 has been updated successfully";

    /**
     * A reusable {@link TypeReference} for the top-level JSON object so that a DTO
     * can be deserialized into a {@code Map} without any raw type or unchecked
     * cast, keeping the suite clean under {@code -Xlint:all}.
     */
    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<Map<String, Object>>() { };

    /**
     * Builds a canonical, fully populated admin response (with a non-null
     * confirmation message) used across the suite.
     *
     * @return a {@link UserResponse} with a valid admin type and a message
     */
    private static UserResponse sampleAdminResponse() {
        return new UserResponse(
                SAMPLE_USER_ID, SAMPLE_FIRST_NAME, SAMPLE_LAST_NAME, ADMIN_USER_TYPE, SAMPLE_MESSAGE);
    }

    // ------------------------------------------------------------------
    // Phase 1 — JSON round-trip and key contract.
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 1 — JSON round-trip and key contract")
    class JsonRoundTripAndKeyContract {

        @Test
        @DisplayName("round-trips through JSON preserving equality and every component")
        void roundTripPreservesAllComponents() {
            UserResponse original = sampleAdminResponse();

            UserResponse restored = DtoTestSupport.roundTrip(original, UserResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.userId()).isEqualTo(SAMPLE_USER_ID);
            assertThat(restored.firstName()).isEqualTo(SAMPLE_FIRST_NAME);
            assertThat(restored.lastName()).isEqualTo(SAMPLE_LAST_NAME);
            assertThat(restored.userType()).isEqualTo(ADMIN_USER_TYPE);
            assertThat(restored.message()).isEqualTo(SAMPLE_MESSAGE);
        }

        @Test
        @DisplayName("serializes exactly the five expected keys: userId, firstName, lastName, userType, message")
        void serializesExactlyTheFiveExpectedKeys() {
            String json = DtoTestSupport.toJson(sampleAdminResponse());

            Map<String, Object> fields = DtoTestSupport.fromJson(json, JSON_OBJECT);

            // containsOnlyKeys is exhaustive: it proves both that the five expected
            // keys are present and that no additional (e.g. credential) key leaks in.
            assertThat(fields).containsOnlyKeys("userId", "firstName", "lastName", "userType", "message");
        }

        @Test
        @DisplayName("userId stays a JSON string, preserving 8-char width and leading zeros")
        void userIdStaysStringPreservingWidth() {
            // An all-digit identifier is the adversarial case: a naive numeric mapping
            // would drop the leading zeros and coerce "00000001" to the number 1.
            // SEC-USR-ID is PIC X(08), so it must remain 8 characters of text.
            String numericLookingId = "00000001";
            UserResponse original = new UserResponse(
                    numericLookingId, SAMPLE_FIRST_NAME, SAMPLE_LAST_NAME, ADMIN_USER_TYPE, SAMPLE_MESSAGE);

            String json = DtoTestSupport.toJson(original);
            Map<String, Object> fields = DtoTestSupport.fromJson(json, JSON_OBJECT);

            assertThat(fields.get("userId"))
                    .as("userId must deserialize to a String, never a numeric node")
                    .isInstanceOf(String.class)
                    .isEqualTo(numericLookingId);

            assertThat(DtoTestSupport.roundTrip(original, UserResponse.class).userId())
                    .as("the 8-character width and leading zeros survive a full round-trip")
                    .isEqualTo(numericLookingId)
                    .hasSize(8);
        }

        @Test
        @DisplayName("a plain read (null message) round-trips with message() still null")
        void plainReadWithNullMessageRoundTrips() {
            // CU00/CU03 read paths return the profile with no confirmation text; the
            // optional message is null and must survive the round-trip as null.
            UserResponse original = new UserResponse(
                    SAMPLE_USER_ID, SAMPLE_FIRST_NAME, SAMPLE_LAST_NAME, REGULAR_USER_TYPE, null);

            UserResponse restored = DtoTestSupport.roundTrip(original, UserResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.message())
                    .as("a plain read carries no confirmation message")
                    .isNull();
            assertThat(restored.userId()).isEqualTo(SAMPLE_USER_ID);
            assertThat(restored.userType()).isEqualTo(REGULAR_USER_TYPE);
        }
    }

    // ------------------------------------------------------------------
    // Phase 2 — sensitive-data safety (SEC-USR-PWD is never exposed). CRITICAL.
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 2 — sensitive-data safety (never expose a password)")
    class SensitiveDataSafety {

        @Test
        @DisplayName("declares no password-like component (password / passwordHash / pwd)")
        void declaresNoPasswordLikeComponent() {
            // Reflection proof: no record component name matches a credential token,
            // so plaintext SEC-USR-PWD can never be surfaced by this response DTO.
            DtoTestSupport.assertNoComponentNamed(UserResponse.class, "password", "passwordHash", "pwd");
        }

        @Test
        @DisplayName("serialized JSON contains no password / passwordHash / pwd key")
        void jsonNeverContainsCredentialKeys() {
            String json = DtoTestSupport.toJson(sampleAdminResponse());

            assertThat(json)
                    .as("response JSON must never carry credential material (SEC-USR-PWD is not mapped)")
                    .doesNotContainIgnoringCase("password")
                    .doesNotContainIgnoringCase("passwordHash")
                    .doesNotContainIgnoringCase("pwd");
        }
    }

    // ------------------------------------------------------------------
    // Phase 3 — user-type values (SEC-USR-TYPE: "A" admin, "U" regular user)
    // and the operation-confirmation message.
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 3 — user-type values (SEC-USR-TYPE) and confirmation message")
    class UserTypeAndMessage {

        @ParameterizedTest(name = "userType \"{0}\" round-trips unchanged")
        @ValueSource(strings = {"A", "U"})
        @DisplayName("accepts and round-trips the admin (A) and regular (U) user types")
        void roundTripsEachUserType(String userType) {
            UserResponse original = new UserResponse(
                    SAMPLE_USER_ID, SAMPLE_FIRST_NAME, SAMPLE_LAST_NAME, userType, SAMPLE_MESSAGE);

            UserResponse restored = DtoTestSupport.roundTrip(original, UserResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.userType())
                    .as("single-character SEC-USR-TYPE value must survive the round-trip")
                    .isEqualTo(userType);
        }

        @Test
        @DisplayName("the operation-confirmation message round-trips intact as a JSON string")
        void messageRoundTrips() {
            // A CU01/CU02/CU03 write returns a human-readable confirmation; it must
            // bind on the wire and survive unchanged.
            String confirmation = "User USER0001 has been added successfully";
            UserResponse original = new UserResponse(
                    SAMPLE_USER_ID, SAMPLE_FIRST_NAME, SAMPLE_LAST_NAME, ADMIN_USER_TYPE, confirmation);

            UserResponse restored = DtoTestSupport.roundTrip(original, UserResponse.class);
            assertThat(restored.message())
                    .as("the confirmation message must survive a full round-trip")
                    .isEqualTo(confirmation);

            String json = DtoTestSupport.toJson(original);
            Map<String, Object> fields = DtoTestSupport.fromJson(json, JSON_OBJECT);
            assertThat(fields.get("message"))
                    .as("message must serialize as a JSON string")
                    .isInstanceOf(String.class)
                    .isEqualTo(confirmation);
        }
    }
}
