package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UserSecurity} (COBOL SEC-USER-DATA, copybook CSUSR01Y,
 * RECLN 80) and its nested {@link UserSecurity.UserTypeConverter}. Confirms the
 * field accessor contract, identity-by-id equality, and the bidirectional
 * single-character user-type conversion (including {@code null} handling).
 */
class UserSecurityTest {

    @Test
    @DisplayName("all SEC-USER-DATA fields round-trip through accessors")
    void fieldsRoundTrip() {
        UserSecurity u = new UserSecurity();
        u.setSecUsrId("ADMIN001");
        u.setSecUsrFname("ALICE");
        u.setSecUsrLname("ADMIN");
        u.setSecUsrPwd("$2a$10$abcdefghijklmnopqrstuv");
        u.setSecUsrType(UserType.ADMIN);

        assertThat(u.getSecUsrId()).isEqualTo("ADMIN001");
        assertThat(u.getSecUsrFname()).isEqualTo("ALICE");
        assertThat(u.getSecUsrLname()).isEqualTo("ADMIN");
        assertThat(u.getSecUsrPwd()).isEqualTo("$2a$10$abcdefghijklmnopqrstuv");
        assertThat(u.getSecUsrType()).isEqualTo(UserType.ADMIN);
    }

    @Test
    @DisplayName("identity is by sec_usr_id only; equals(null)/equals(other type) are false")
    void identityBySecUsrId() {
        UserSecurity a = new UserSecurity();
        a.setSecUsrId("USER0001");
        a.setSecUsrFname("BOB");
        UserSecurity same = new UserSecurity();
        same.setSecUsrId("USER0001");
        same.setSecUsrFname("ROBERT");
        UserSecurity diff = new UserSecurity();
        diff.setSecUsrId("USER0002");

        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(same);
        assertThat(a).hasSameHashCodeAs(same);
        assertThat(a).isNotEqualTo(diff);
        assertThat(a.equals(null)).isFalse();
        assertThat(a.equals("nope")).isFalse();
    }

    @Test
    @DisplayName("UserTypeConverter maps enum to single-character DB code (null-safe)")
    void converterToDatabaseColumn() {
        UserSecurity.UserTypeConverter converter = new UserSecurity.UserTypeConverter();
        assertThat(converter.convertToDatabaseColumn(UserType.ADMIN)).isEqualTo("A");
        assertThat(converter.convertToDatabaseColumn(UserType.USER)).isEqualTo("U");
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    @DisplayName("UserTypeConverter maps DB code to enum (null/unknown -> null)")
    void converterToEntityAttribute() {
        UserSecurity.UserTypeConverter converter = new UserSecurity.UserTypeConverter();
        assertThat(converter.convertToEntityAttribute("A")).isEqualTo(UserType.ADMIN);
        assertThat(converter.convertToEntityAttribute("U")).isEqualTo(UserType.USER);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("Z")).isNull();
    }
}
