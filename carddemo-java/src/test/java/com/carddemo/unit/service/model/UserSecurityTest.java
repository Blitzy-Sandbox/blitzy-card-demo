package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UserSecurity} and its nested {@link UserSecurity.UserTypeConverter}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}):
 * {@link UserSecurity} is the JPA mapping of the COBOL {@code SEC-USER-DATA} record
 * (copybook {@code app/cpy/CSUSR01Y.cpy}, RECLN 80) re-platforming the VSAM {@code USRSEC}
 * KSDS. The plaintext {@code SEC-USR-PWD PIC X(08)} is widened to a 60-char BCrypt hash
 * column (constraint C-003) and the single-character {@code SEC-USR-TYPE} is mapped via the
 * attribute converter. Identity-based {@code equals}/{@code hashCode} follow the
 * {@code SEC-USR-ID} primary key.</p>
 */
@DisplayName("UserSecurity - CSUSR01Y entity mapping, identity, and user-type conversion")
class UserSecurityTest {

    @Test
    @DisplayName("every field round-trips through its setter/getter pair")
    void fieldsRoundTrip() {
        UserSecurity user = new UserSecurity();
        user.setSecUsrId("USER0001");
        user.setSecUsrFname("ADA");
        user.setSecUsrLname("LOVELACE");
        user.setSecUsrPwd("$2a$10$bcrypthashvaluethatislongenoughforcolumn0000000000000");
        user.setSecUsrType(UserType.ADMIN);

        assertThat(user.getSecUsrId()).isEqualTo("USER0001");
        assertThat(user.getSecUsrFname()).isEqualTo("ADA");
        assertThat(user.getSecUsrLname()).isEqualTo("LOVELACE");
        assertThat(user.getSecUsrPwd())
                .isEqualTo("$2a$10$bcrypthashvaluethatislongenoughforcolumn0000000000000");
        assertThat(user.getSecUsrType()).isEqualTo(UserType.ADMIN);
    }

    @Nested
    @DisplayName("equals/hashCode follow the SEC-USR-ID identity")
    class Identity {

        @Test
        @DisplayName("same id is equal and shares a hashCode; reflexive")
        void sameIdIsEqual() {
            UserSecurity a = new UserSecurity();
            a.setSecUsrId("USER0001");
            a.setSecUsrFname("ADA");
            UserSecurity b = new UserSecurity();
            b.setSecUsrId("USER0001");
            b.setSecUsrFname("DIFFERENT");

            assertThat(a).isEqualTo(a);
            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("different id is not equal; null and other types are not equal")
        void differentIdNotEqual() {
            UserSecurity a = new UserSecurity();
            a.setSecUsrId("USER0001");
            UserSecurity c = new UserSecurity();
            c.setSecUsrId("USER0002");

            assertThat(a).isNotEqualTo(c);
            assertThat(a).isNotEqualTo(null);
            assertThat(a).isNotEqualTo("USER0001");
        }
    }

    @Nested
    @DisplayName("UserTypeConverter maps UserType <-> single-character code")
    class Converter {

        private final UserSecurity.UserTypeConverter converter = new UserSecurity.UserTypeConverter();

        @Test
        @DisplayName("convertToDatabaseColumn writes the enum code, null-safe")
        void toColumn() {
            assertThat(converter.convertToDatabaseColumn(UserType.ADMIN))
                    .isEqualTo(UserType.ADMIN.getCode());
            assertThat(converter.convertToDatabaseColumn(UserType.USER))
                    .isEqualTo(UserType.USER.getCode());
            assertThat(converter.convertToDatabaseColumn(null)).isNull();
        }

        @Test
        @DisplayName("convertToEntityAttribute resolves the code back to the enum")
        void toEntity() {
            assertThat(converter.convertToEntityAttribute(UserType.ADMIN.getCode()))
                    .isEqualTo(UserType.ADMIN);
            assertThat(converter.convertToEntityAttribute(UserType.USER.getCode()))
                    .isEqualTo(UserType.USER);
        }
    }
}
