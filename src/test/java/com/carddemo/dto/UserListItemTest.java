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
 * Unit tests for {@link UserListItem}, the immutable projection of a single row
 * of the legacy CardDemo <strong>List Users</strong> screen (online transaction
 * {@code CU00}, program {@code COUSR00C}, BMS map {@code COUSR00}).
 *
 * <p>Each list row is backed by the {@code SEC-USER-DATA} record of copybook
 * {@code CSUSR01Y} (referenced by source SHA {@code 27d6c6f}, never copied into
 * the target): {@code SEC-USR-ID PIC X(08)}, {@code SEC-USR-FNAME PIC X(20)},
 * {@code SEC-USR-LNAME PIC X(20)} and {@code SEC-USR-TYPE PIC X(01)}. The record
 * also declares {@code SEC-USR-PWD PIC X(08)} — a <em>plaintext</em> password —
 * which this response DTO deliberately does <strong>not</strong> map. The tests
 * below turn that security contract into an executable guarantee.</p>
 *
 * <p>The suite is organised into the three phases mandated for this DTO:</p>
 * <ol>
 *   <li><strong>JSON round-trip and key contract</strong> — the record survives a
 *       serialize/deserialize cycle intact and emits exactly the four expected
 *       JSON keys, with {@code userId} preserved as a fixed-width text value;</li>
 *   <li><strong>Sensitive-data safety</strong> — neither the type's component set
 *       nor its serialized JSON ever exposes a password, password hash, or
 *       {@code pwd} field (the {@code SEC-USR-PWD} parity guard);</li>
 *   <li><strong>User-type values</strong> — the single-character {@code userType}
 *       round-trips for both the administrator ({@code "A"}) and regular
 *       ({@code "U"}) values defined by {@code SEC-USR-TYPE}.</li>
 * </ol>
 *
 * <p>These are pure, framework-free unit tests: no Spring context and no
 * Testcontainers are loaded. All JSON handling flows through the shared,
 * production-mirroring {@link DtoTestSupport} helpers so that assertions match
 * exactly what the running application would emit, and the tests run in
 * milliseconds toward the Gate&nbsp;8 (&ge;80%) coverage threshold. No COBOL
 * source is reproduced here; rationale lives in {@code docs/decision-log.md}.</p>
 */
@DisplayName("UserListItem — user-list row DTO: JSON contract, no-credential safety, and user-type values")
class UserListItemTest {

    /** A representative 8-character user identifier ({@code SEC-USR-ID PIC X(08)}). */
    private static final String SAMPLE_USER_ID = "USER0001";

    /** A representative first name well within the 20-character legacy width. */
    private static final String SAMPLE_FIRST_NAME = "Ada";

    /** A representative last name well within the 20-character legacy width. */
    private static final String SAMPLE_LAST_NAME = "Lovelace";

    /** The administrator user type ({@code SEC-USR-TYPE} value {@code "A"}). */
    private static final String ADMIN_USER_TYPE = "A";

    /**
     * A reusable {@link TypeReference} for the top-level JSON object so that a DTO
     * can be deserialized into a {@code Map} without any raw type or unchecked
     * cast, keeping the suite clean under {@code -Xlint:all}.
     */
    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<Map<String, Object>>() { };

    /**
     * Builds a canonical admin list row used across the suite.
     *
     * @return a fully populated {@link UserListItem} with a valid admin type
     */
    private static UserListItem sampleAdminRow() {
        return new UserListItem(SAMPLE_USER_ID, SAMPLE_FIRST_NAME, SAMPLE_LAST_NAME, ADMIN_USER_TYPE);
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
            UserListItem original = sampleAdminRow();

            UserListItem restored = DtoTestSupport.roundTrip(original, UserListItem.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.userId()).isEqualTo(SAMPLE_USER_ID);
            assertThat(restored.firstName()).isEqualTo(SAMPLE_FIRST_NAME);
            assertThat(restored.lastName()).isEqualTo(SAMPLE_LAST_NAME);
            assertThat(restored.userType()).isEqualTo(ADMIN_USER_TYPE);
        }

        @Test
        @DisplayName("serializes exactly the four expected keys: userId, firstName, lastName, userType")
        void serializesExactlyTheFourExpectedKeys() {
            String json = DtoTestSupport.toJson(sampleAdminRow());

            Map<String, Object> fields = DtoTestSupport.fromJson(json, JSON_OBJECT);

            // containsOnlyKeys is exhaustive: it proves both that the four expected
            // keys are present and that no additional (e.g. credential) key leaks in.
            assertThat(fields).containsOnlyKeys("userId", "firstName", "lastName", "userType");
        }

        @Test
        @DisplayName("userId stays a JSON string, preserving 8-char width and leading zeros")
        void userIdStaysStringPreservingWidth() {
            // An all-digit identifier is the adversarial case: a naive numeric mapping
            // would drop the leading zeros and coerce "00000001" to the number 1.
            // SEC-USR-ID is PIC X(08), so it must remain 8 characters of text.
            String numericLookingId = "00000001";
            UserListItem original =
                    new UserListItem(numericLookingId, SAMPLE_FIRST_NAME, SAMPLE_LAST_NAME, ADMIN_USER_TYPE);

            String json = DtoTestSupport.toJson(original);
            Map<String, Object> fields = DtoTestSupport.fromJson(json, JSON_OBJECT);

            assertThat(fields.get("userId"))
                    .as("userId must deserialize to a String, never a numeric node")
                    .isInstanceOf(String.class)
                    .isEqualTo(numericLookingId);

            assertThat(DtoTestSupport.roundTrip(original, UserListItem.class).userId())
                    .as("the 8-character width and leading zeros survive a full round-trip")
                    .isEqualTo(numericLookingId)
                    .hasSize(8);
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
            // so plaintext SEC-USR-PWD can never be surfaced by this list-row DTO.
            DtoTestSupport.assertNoComponentNamed(UserListItem.class, "password", "passwordHash", "pwd");
        }

        @Test
        @DisplayName("serialized JSON contains no password / passwordHash / pwd key")
        void jsonNeverContainsCredentialKeys() {
            String json = DtoTestSupport.toJson(sampleAdminRow());

            assertThat(json)
                    .as("list-row JSON must never carry credential material (SEC-USR-PWD is not mapped)")
                    .doesNotContainIgnoringCase("password")
                    .doesNotContainIgnoringCase("passwordHash")
                    .doesNotContainIgnoringCase("pwd");
        }
    }

    // ------------------------------------------------------------------
    // Phase 3 — user-type values (SEC-USR-TYPE: "A" admin, "U" regular user).
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 3 — user-type values (SEC-USR-TYPE)")
    class UserTypeValues {

        @ParameterizedTest(name = "userType \"{0}\" round-trips unchanged")
        @ValueSource(strings = {"A", "U"})
        @DisplayName("accepts and round-trips the admin (A) and regular (U) user types")
        void roundTripsEachUserType(String userType) {
            UserListItem original =
                    new UserListItem(SAMPLE_USER_ID, SAMPLE_FIRST_NAME, SAMPLE_LAST_NAME, userType);

            UserListItem restored = DtoTestSupport.roundTrip(original, UserListItem.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.userType())
                    .as("single-character SEC-USR-TYPE value must survive the round-trip")
                    .isEqualTo(userType);
        }
    }
}
