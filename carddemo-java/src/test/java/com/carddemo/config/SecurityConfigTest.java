package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link SecurityConfig}.
 *
 * <p>Traceability (REFERENCE-ONLY; lineage {@code COSGN00C}/{@code CSUSR01Y}; source commit
 * {@code 27d6c6f}): verifies the BCrypt password encoder round-trips, the HS256 JWT
 * encoder/decoder beans are constructed from a valid secret, and the secret guard rejects a
 * missing, blank, or under-length secret without leaking the secret value. The fully-wired
 * {@code securityFilterChain} is intentionally exercised by integration tests rather than here.</p>
 */
@DisplayName("SecurityConfig - BCrypt encoder and HS256 JWT beans")
class SecurityConfigTest {

    private static final String VALID_SECRET = "this-is-a-test-jwt-secret-key-1234567890";
    private static final String EXPECTED_GUARD_MESSAGE =
            "carddemo.security.jwt.secret must be set (>=32 chars); provide via JWT_SECRET env";

    private final SecurityConfig config = new SecurityConfig();

    @Nested
    @DisplayName("Password encoder")
    class PasswordEncoderBean {

        @Test
        @DisplayName("Produces a BCrypt encoder that round-trips a password")
        void roundTrips() {
            PasswordEncoder encoder = config.passwordEncoder();

            assertThat(encoder).isInstanceOf(BCryptPasswordEncoder.class);
            String hash = encoder.encode("S3cret!");
            assertThat(hash).isNotEqualTo("S3cret!");
            assertThat(encoder.matches("S3cret!", hash)).isTrue();
            assertThat(encoder.matches("wrong", hash)).isFalse();
        }
    }

    @Nested
    @DisplayName("JWT beans with a valid secret")
    class JwtBeans {

        @Test
        @DisplayName("Builds the HS256 encoder and decoder")
        void buildsEncoderAndDecoder() {
            ReflectionTestUtils.setField(config, "jwtSecret", VALID_SECRET);

            JwtEncoder encoder = config.jwtEncoder();
            JwtDecoder decoder = config.jwtDecoder();

            assertThat(encoder).isNotNull();
            assertThat(decoder).isNotNull();
        }
    }

    @Nested
    @DisplayName("Secret guard")
    class SecretGuard {

        @Test
        @DisplayName("Rejects a null secret")
        void rejectsNull() {
            ReflectionTestUtils.setField(config, "jwtSecret", null);

            assertThatThrownBy(config::jwtEncoder)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(EXPECTED_GUARD_MESSAGE);
        }

        @Test
        @DisplayName("Rejects a blank secret")
        void rejectsBlank() {
            ReflectionTestUtils.setField(config, "jwtSecret", "   ");

            assertThatThrownBy(config::jwtDecoder)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(EXPECTED_GUARD_MESSAGE);
        }

        @Test
        @DisplayName("Rejects an under-length secret")
        void rejectsShort() {
            ReflectionTestUtils.setField(config, "jwtSecret", "too-short");

            assertThatThrownBy(config::jwtEncoder)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(EXPECTED_GUARD_MESSAGE);
        }
    }
}
