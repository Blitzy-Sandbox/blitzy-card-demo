package com.carddemo.unit.entity;

import com.carddemo.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link User} entity (COBOL CSUSR01Y SEC-USER-DATA, RECLN 80 @ 27d6c6f).
 * Security parity: the legacy plaintext X(08) password becomes a BCrypt VARCHAR(60) column (constraint C-003),
 * and toString() MUST NOT leak the password value.
 */
@DisplayName("User entity - CSUSR01Y (80B): CHAR(8) PK, BCrypt VARCHAR(60) password, toString excludes password, NO @Version")
class UserTest {

    private static final String SECRET = "$2a$10$SECRETbcryptHASHvalueDOnotLEAK0000000000000000000000";

    private static Field field(String name) throws NoSuchFieldException {
        return User.class.getDeclaredField(name);
    }

    private static boolean hasVersionAnnotation(Class<?> type) {
        for (Field f : type.getDeclaredFields()) {
            if (f.isAnnotationPresent(Version.class)) {
                return true;
            }
        }
        return false;
    }

    private static void assertNoFloatingPointFields(Class<?> type) {
        for (Field f : type.getDeclaredFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            assertThat(f.getType())
                    .as("field '%s' must not be floating-point (decimal exactness, AAP 0.6.1)", f.getName())
                    .isNotIn(double.class, float.class, Double.class, Float.class);
        }
    }

    @Test
    @DisplayName("no field is double/float")
    void noFloatingPointFields() {
        assertNoFloatingPointFields(User.class);
    }

    @Test
    @DisplayName("has NO @Version field")
    void versionAbsent() {
        assertThat(hasVersionAnnotation(User.class)).as("User must NOT carry @Version").isFalse();
    }

    @Test
    @DisplayName("primary key userId is @Id String mapped to char(8) column user_id")
    void primaryKey() throws NoSuchFieldException {
        assertThat(field("userId").isAnnotationPresent(Id.class)).isTrue();
        assertThat(field("userId").getType()).isEqualTo(String.class);
        Column column = field("userId").getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo("user_id");
        assertThat(column.length()).isEqualTo(8);
    }

    @Test
    @DisplayName("password is a BCrypt-sized String VARCHAR(60) (constraint C-003)")
    void passwordIsBcryptColumn() throws NoSuchFieldException {
        Field f = field("password");
        assertThat(f.getType()).isEqualTo(String.class);
        Column column = f.getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo("password");
        assertThat(column.length()).as("BCrypt hash is 60 chars").isEqualTo(60);
    }

    @Test
    @DisplayName("toString() MUST NOT leak the password value but should identify the user")
    void toStringExcludesPassword() {
        User u = new User();
        u.setUserId("ADMIN001");
        u.setFirstName("Ada");
        u.setLastName("Admin");
        u.setUserType("A");
        u.setPassword(SECRET);

        String rendered = u.toString();
        assertThat(rendered).as("password value must never appear in toString").doesNotContain(SECRET);
        assertThat(rendered).as("toString should still identify the user by id").contains("ADMIN001");
    }

    @Test
    @DisplayName("equals/hashCode are identity-based (userId only)")
    void equalsAndHashCodeOverIdentity() {
        User a = new User();
        a.setUserId("USER0001");
        User b = new User();
        b.setUserId("USER0001");
        b.setFirstName("Changed");
        User other = new User();
        other.setUserId("USER0002");

        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not-a-user");
        assertThat(a.toString()).isNotNull();
    }
}
