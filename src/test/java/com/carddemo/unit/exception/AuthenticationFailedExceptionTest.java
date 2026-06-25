package com.carddemo.unit.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;

import com.carddemo.exception.AuthenticationFailedException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure JUnit&nbsp;5 unit tests for {@link AuthenticationFailedException}.
 *
 * <p>The system under test (SUT) is the Java realization of the COBOL signon-failure
 * outcomes emitted by {@code COSGN00C} &mdash; specifically the user-facing messages
 * {@code "Wrong Password. Try again ..."} and {@code "User not found. Try again ..."}.
 * {@code GlobalExceptionHandler} maps this exception to HTTP&nbsp;401 (Unauthorized); that
 * HTTP-status mapping is intentionally <em>not</em> asserted here &mdash; it belongs to
 * {@code GlobalExceptionHandlerTest}.</p>
 *
 * <p><strong>Naming note:</strong> the SUT is deliberately <em>not</em> named
 * {@code AuthenticationException} to avoid a clash with Spring Security's
 * {@code org.springframework.security.core.AuthenticationException}. That Spring Security
 * type is therefore intentionally never imported by this test.</p>
 *
 * <p>This is a POJO test: no Mockito, no Spring context, no database/AWS/network access.
 * The highest-value assertions are the no-credential-field security check and the
 * {@code serialVersionUID} reflection check.</p>
 */
class AuthenticationFailedExceptionTest {

    /**
     * Byte-exact COBOL {@code COSGN00C} parity literal for an incorrect password
     * (app/cbl/COSGN00C.cbl &#64; commit 27d6c6f, line 242).
     */
    private static final String WRONG_PASSWORD_MESSAGE = "Wrong Password. Try again ...";

    /**
     * Byte-exact COBOL {@code COSGN00C} parity literal for an unknown user
     * (app/cbl/COSGN00C.cbl &#64; commit 27d6c6f, line 249).
     */
    private static final String USER_NOT_FOUND_MESSAGE = "User not found. Try again ...";

    @Test
    @DisplayName("(message) constructor stores the COSGN00C parity message byte-exact and leaves the cause null")
    void messageOnlyConstructor() {
        AuthenticationFailedException wrongPassword =
            new AuthenticationFailedException(WRONG_PASSWORD_MESSAGE);
        assertThat(wrongPassword.getMessage()).isEqualTo(WRONG_PASSWORD_MESSAGE);
        assertThat(wrongPassword.getCause()).isNull();

        AuthenticationFailedException userNotFound =
            new AuthenticationFailedException(USER_NOT_FOUND_MESSAGE);
        assertThat(userNotFound.getMessage()).isEqualTo(USER_NOT_FOUND_MESSAGE);
        assertThat(userNotFound.getCause()).isNull();
    }

    @Test
    @DisplayName("(message, cause) constructor preserves the message and the same cause instance")
    void messageAndCauseConstructor() {
        RuntimeException cause = new RuntimeException("c");
        AuthenticationFailedException exception =
            new AuthenticationFailedException(WRONG_PASSWORD_MESSAGE, cause);
        assertThat(exception.getMessage()).isEqualTo(WRONG_PASSWORD_MESSAGE);
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("declares no credential/secret-bearing field (never echoes the attempted secret)")
    void declaresNoCredentialOrSecretField() {
        // SECURITY: parity with COSGN00C, which only emits a generic "Wrong Password"/"User
        // not found" message and never echoes the entered secret. This exception must never
        // carry the attempted password/secret into logs or problem responses. The synthetic
        // serialVersionUID is the only permitted declared field and is excluded from this check.
        for (Field field : AuthenticationFailedException.class.getDeclaredFields()) {
            if ("serialVersionUID".equals(field.getName())) {
                continue;
            }
            String lower = field.getName().toLowerCase(Locale.ROOT);
            assertThat(lower)
                .as("declared field '%s' must not look like a credential/secret carrier", field.getName())
                .doesNotContain("password")
                .doesNotContain("passwd")
                .doesNotContain("pwd")
                .doesNotContain("secret")
                .doesNotContain("credential")
                .doesNotContain("token")
                .doesNotContain("apikey");
        }
    }

    @Test
    @DisplayName("declares only static fields (captures no instance state)")
    void declaresOnlyStaticFields() {
        // Defensive companion to the credential-name check: per the current contract the
        // exception has zero instance fields, so every declared field must be static (only
        // the static serialVersionUID exists). If a non-secret instance field is legitimately
        // added later, relax this to the credential-name check in declaresNoCredentialOrSecretField.
        for (Field field : AuthenticationFailedException.class.getDeclaredFields()) {
            assertThat(Modifier.isStatic(field.getModifiers()))
                .as("declared field '%s' must be static; the exception holds no instance state", field.getName())
                .isTrue();
        }
    }

    @Test
    @DisplayName("serialVersionUID is a private static final long equal to 1L")
    void serialVersionUidIsPrivateStaticFinalLongOne() throws Exception {
        Field field = AuthenticationFailedException.class.getDeclaredField("serialVersionUID");
        field.setAccessible(true);
        int modifiers = field.getModifiers();
        assertThat(field.getType()).isEqualTo(long.class);
        assertThat(Modifier.isPrivate(modifiers)).isTrue();
        assertThat(Modifier.isStatic(modifiers)).isTrue();
        assertThat(Modifier.isFinal(modifiers)).isTrue();
        assertThat(field.getLong(null)).isEqualTo(1L);
    }

    @Test
    @DisplayName("is a RuntimeException subtype (unchecked propagation, no checked-exception plumbing)")
    void isRuntimeExceptionSubtype() {
        assertThat(new AuthenticationFailedException("x")).isInstanceOf(RuntimeException.class);
    }
}
