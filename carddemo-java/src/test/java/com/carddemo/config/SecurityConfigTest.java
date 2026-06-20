package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link SecurityConfig}: the BCrypt {@link PasswordEncoder}, the
 * HMAC (HS256) {@link JwtEncoder}/{@link JwtDecoder} pair (round-trip), and the
 * fail-fast HMAC-key contract enforced when the configured secret is absent,
 * blank, or shorter than the 32-byte HS256 minimum. The {@code @Value} secret
 * field is injected via {@link ReflectionTestUtils} (no Spring context).
 */
class SecurityConfigTest {

    /** A 34-byte secret, comfortably above the 32-byte HS256 minimum. */
    private static final String VALID_SECRET = "0123456789012345678901234567890123";

    private SecurityConfig config;

    @BeforeEach
    void setUp() {
        config = new SecurityConfig();
    }

    @Test
    @DisplayName("passwordEncoder produces a BCrypt hash that verifies the raw password")
    void passwordEncoderRoundTrip() {
        PasswordEncoder encoder = config.passwordEncoder();
        String hash = encoder.encode("S3cret!");

        assertThat(hash).isNotEqualTo("S3cret!");
        assertThat(hash).startsWith("$2");
        assertThat(encoder.matches("S3cret!", hash)).isTrue();
        assertThat(encoder.matches("wrong", hash)).isFalse();
    }

    @Test
    @DisplayName("a token minted by jwtEncoder is verifiable by jwtDecoder (HS256 round-trip)")
    void jwtEncoderDecoderRoundTrip() {
        ReflectionTestUtils.setField(config, "jwtSecret", VALID_SECRET);
        JwtEncoder encoder = config.jwtEncoder();
        JwtDecoder decoder = config.jwtDecoder();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("USER0001")
                .claim("roles", List.of("ROLE_ADMIN"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        Jwt encoded = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims));

        Jwt decoded = decoder.decode(encoded.getTokenValue());

        assertThat(decoded.getSubject()).isEqualTo("USER0001");
        assertThat(decoded.getClaimAsStringList("roles")).containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("a null secret fails fast without leaking the secret value")
    void hmacKeyRejectsNullSecret() {
        // jwtSecret left unset (null)
        assertThatThrownBy(config::jwtEncoder)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be set");
    }

    @Test
    @DisplayName("a blank secret fails fast")
    void hmacKeyRejectsBlankSecret() {
        ReflectionTestUtils.setField(config, "jwtSecret", "   ");
        assertThatThrownBy(config::jwtDecoder)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be set");
    }

    @Test
    @DisplayName("a too-short secret (under 32 bytes) fails fast")
    void hmacKeyRejectsShortSecret() {
        ReflectionTestUtils.setField(config, "jwtSecret", "short-secret");
        assertThatThrownBy(config::jwtEncoder)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(">=32");
    }
}
