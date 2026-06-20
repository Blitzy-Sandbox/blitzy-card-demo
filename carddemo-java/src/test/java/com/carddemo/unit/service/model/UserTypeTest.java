package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.enums.UserType;
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
}
