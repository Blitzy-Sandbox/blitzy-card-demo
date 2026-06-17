package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.model.enums.UserType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UserType} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies the CSUSR01Y SEC-USR-TYPE code mapping (A/U) per AAP {@code §0.7/§0.8}.
 */
@DisplayName("UserType enum — code mapping (CSUSR01Y SEC-USR-TYPE)")
class UserTypeTest {

    @Test
    @DisplayName("getCode() returns the single-character COBOL code")
    void getCodeReturnsCobolCode() {
        assertThat(UserType.ADMIN.getCode()).isEqualTo("A");
        assertThat(UserType.USER.getCode()).isEqualTo("U");
    }

    @Test
    @DisplayName("exactly two constants exist (no feature expansion)")
    void hasExactlyTwoConstants() {
        assertThat(UserType.values()).containsExactly(UserType.ADMIN, UserType.USER);
    }

    @Test
    @DisplayName("fromCode maps known codes to the matching constant")
    void fromCodeMapsKnownCodes() {
        assertThat(UserType.fromCode("A")).isEqualTo(UserType.ADMIN);
        assertThat(UserType.fromCode("U")).isEqualTo(UserType.USER);
    }

    @Test
    @DisplayName("fromCode returns null for unknown code and for null input")
    void fromCodeReturnsNullForUnknownOrNull() {
        assertThat(UserType.fromCode("X")).isNull();
        assertThat(UserType.fromCode(null)).isNull();
    }

    // ---------------------------------------------------------------------
    // F3: JSON deserialization parity — accept COBOL-native "A"/"U" AND the
    // enum names "ADMIN"/"USER"; serialization must remain the enum NAME.
    // ---------------------------------------------------------------------

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("fromJson accepts COBOL-native single-character codes (A/U)")
    void fromJsonAcceptsSingleCharCodes() {
        assertThat(UserType.fromJson("A")).isEqualTo(UserType.ADMIN);
        assertThat(UserType.fromJson("U")).isEqualTo(UserType.USER);
    }

    @Test
    @DisplayName("fromJson still accepts enum constant names (ADMIN/USER)")
    void fromJsonAcceptsEnumNames() {
        assertThat(UserType.fromJson("ADMIN")).isEqualTo(UserType.ADMIN);
        assertThat(UserType.fromJson("USER")).isEqualTo(UserType.USER);
    }

    @Test
    @DisplayName("fromJson is case-insensitive and trims surrounding whitespace")
    void fromJsonIsCaseInsensitiveAndTrims() {
        assertThat(UserType.fromJson("a")).isEqualTo(UserType.ADMIN);
        assertThat(UserType.fromJson(" u ")).isEqualTo(UserType.USER);
        assertThat(UserType.fromJson("admin")).isEqualTo(UserType.ADMIN);
        assertThat(UserType.fromJson(" User ")).isEqualTo(UserType.USER);
    }

    @Test
    @DisplayName("fromJson passes null through as null (validation handled elsewhere)")
    void fromJsonPassesNullThrough() {
        assertThat(UserType.fromJson(null)).isNull();
    }

    @Test
    @DisplayName("fromJson rejects an unknown value with IllegalArgumentException")
    void fromJsonRejectsUnknownValue() {
        assertThatThrownBy(() -> UserType.fromJson("X"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid userType");
    }

    @Test
    @DisplayName("Jackson deserializes both \"A\"/\"U\" and \"ADMIN\"/\"USER\" JSON tokens")
    void jacksonDeserializesCodesAndNames() throws JsonProcessingException {
        assertThat(MAPPER.readValue("\"A\"", UserType.class)).isEqualTo(UserType.ADMIN);
        assertThat(MAPPER.readValue("\"U\"", UserType.class)).isEqualTo(UserType.USER);
        assertThat(MAPPER.readValue("\"ADMIN\"", UserType.class)).isEqualTo(UserType.ADMIN);
        assertThat(MAPPER.readValue("\"USER\"", UserType.class)).isEqualTo(UserType.USER);
    }

    @Test
    @DisplayName("Jackson rejects an unknown JSON token as a deserialization failure")
    void jacksonRejectsUnknownToken() {
        assertThatThrownBy(() -> MAPPER.readValue("\"X\"", UserType.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    @DisplayName("Serialization remains the enum NAME (no @JsonValue regression)")
    void serializationEmitsEnumName() throws JsonProcessingException {
        assertThat(MAPPER.writeValueAsString(UserType.ADMIN)).isEqualTo("\"ADMIN\"");
        assertThat(MAPPER.writeValueAsString(UserType.USER)).isEqualTo("\"USER\"");
    }
}
