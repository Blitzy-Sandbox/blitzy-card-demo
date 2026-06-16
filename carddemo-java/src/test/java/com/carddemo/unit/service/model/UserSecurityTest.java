package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UserSecurity} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies CSUSR01Y round-trip, the BCrypt-sized 60-char password (C-003, NOT 8), the UserType
 * attribute converter, and identity by secUsrId per AAP {@code §0.8.1}.
 */
@DisplayName("UserSecurity entity — CSUSR01Y mapping, BCrypt password, UserType converter")
class UserSecurityTest {

    /** Canonical 60-character BCrypt hash used to assert the widened password column. */
    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private UserSecurity user;

    @BeforeEach
    void setUp() {
        user = new UserSecurity();
    }

    @Test
    @DisplayName("fields round-trip; password holds a 60-char BCrypt hash (not the legacy 8-char plaintext)")
    void fieldsRoundTripWithBcryptPassword() {
        user.setSecUsrId("ADMIN001");
        user.setSecUsrFname("ALICE");
        user.setSecUsrLname("ADMIN");
        user.setSecUsrPwd(BCRYPT_HASH);
        user.setSecUsrType(UserType.ADMIN);

        assertThat(user.getSecUsrId()).isEqualTo("ADMIN001");
        assertThat(user.getSecUsrFname()).isEqualTo("ALICE");
        assertThat(user.getSecUsrLname()).isEqualTo("ADMIN");
        assertThat(user.getSecUsrPwd()).isEqualTo(BCRYPT_HASH);
        assertThat(user.getSecUsrPwd()).hasSize(60);
        assertThat(user.getSecUsrType()).isEqualTo(UserType.ADMIN);
    }

    @Test
    @DisplayName("UserTypeConverter maps UserType<->code, null-safe in both directions")
    void userTypeConverterRoundTrip() {
        UserSecurity.UserTypeConverter converter = new UserSecurity.UserTypeConverter();
        assertThat(converter.convertToDatabaseColumn(UserType.ADMIN)).isEqualTo("A");
        assertThat(converter.convertToDatabaseColumn(UserType.USER)).isEqualTo("U");
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute("A")).isEqualTo(UserType.ADMIN);
        assertThat(converter.convertToEntityAttribute("U")).isEqualTo(UserType.USER);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("X")).isNull();
    }

    @Test
    @DisplayName("identity is by secUsrId only; equals(null)/equals(other type) are false")
    void identityBySecUsrId() {
        user.setSecUsrId("USER0001");
        user.setSecUsrType(UserType.USER);
        UserSecurity same = new UserSecurity();
        same.setSecUsrId("USER0001");
        same.setSecUsrType(UserType.ADMIN);
        UserSecurity diff = new UserSecurity();
        diff.setSecUsrId("USER0002");

        assertThat(user).isEqualTo(same);
        assertThat(user).hasSameHashCodeAs(same);
        assertThat(user).isNotEqualTo(diff);
        assertThat(user.equals(null)).isFalse();
        assertThat(user.equals("nope")).isFalse();
    }
}
