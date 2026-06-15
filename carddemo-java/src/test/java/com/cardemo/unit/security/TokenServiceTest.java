package com.cardemo.unit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.model.enums.UserType;
import com.cardemo.security.TokenService;
import com.cardemo.security.TokenService.TokenClaims;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenService} — the JDK-only HMAC-SHA256 compact-token issuer/parser that
 * replaces the CICS {@code COMMAREA} carry-over (AAP §0.1.2) and owns the fail-fast signing-secret
 * validation (CP3 MINOR security-hardening finding + AAP §0.7.2 "no weak secret").
 *
 * <p>Covers: constructor secret validation (blank / too-weak / bad ttl), the issue→parse round-trip
 * (subject + role preserved for ADMIN and USER), and every rejection path of {@link TokenService#parse}
 * (null/blank, malformed segment count, tampered signature, expired, unknown role code).
 */
class TokenServiceTest {

    /** A 33-byte ASCII secret (>= the 32-byte / 256-bit HS256 minimum). */
    private static final String VALID_SECRET = "abcdefghijklmnopqrstuvwxyz0123456";

    private static String b64url(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String b64url(String raw) {
        return b64url(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Replicates the production HS256 signing so expired/unknown-role tokens can be crafted with a valid signature. */
    private static String sign(String secret, String signingInput) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return b64url(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
    }

    /** Builds a correctly-signed compact token for an arbitrary (possibly invalid) payload. */
    private static String craftSignedToken(String secret, String payloadJson) throws Exception {
        String header = b64url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = b64url(payloadJson);
        String signingInput = header + "." + payload;
        return signingInput + "." + sign(secret, signingInput);
    }

    @Nested
    @DisplayName("constructor secret validation (fail-fast)")
    class SecretValidation {

        @Test
        @DisplayName("null secret is rejected")
        void nullSecretRejected() {
            assertThatThrownBy(() -> new TokenService(null, 3600))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("blank secret is rejected")
        void blankSecretRejected() {
            assertThatThrownBy(() -> new TokenService("   ", 3600))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("secret shorter than 32 bytes is rejected")
        void weakSecretRejected() {
            assertThatThrownBy(() -> new TokenService("short-secret", 3600))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("non-positive ttl is rejected")
        void nonPositiveTtlRejected() {
            assertThatThrownBy(() -> new TokenService(VALID_SECRET, 0))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new TokenService(VALID_SECRET, -1))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a valid 32+ byte secret with positive ttl is accepted")
        void validSecretAccepted() {
            assertThatCode(() -> new TokenService(VALID_SECRET, 3600))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("issue + parse round-trip")
    class RoundTrip {

        private final TokenService service = new TokenService(VALID_SECRET, 3600);

        @Test
        @DisplayName("an ADMIN token round-trips with subject and role preserved")
        void adminRoundTrip() {
            String token = service.issue("ADMIN001", UserType.ADMIN);
            Optional<TokenClaims> claims = service.parse(token);
            assertThat(claims).isPresent();
            assertThat(claims.get().subject()).isEqualTo("ADMIN001");
            assertThat(claims.get().userType()).isEqualTo(UserType.ADMIN);
            assertThat(claims.get().expiresAt()).isGreaterThan(claims.get().issuedAt());
        }

        @Test
        @DisplayName("a USER token round-trips with subject and role preserved")
        void userRoundTrip() {
            String token = service.issue("USER0001", UserType.USER);
            Optional<TokenClaims> claims = service.parse(token);
            assertThat(claims).isPresent();
            assertThat(claims.get().subject()).isEqualTo("USER0001");
            assertThat(claims.get().userType()).isEqualTo(UserType.USER);
        }
    }

    @Nested
    @DisplayName("parse rejection paths (total, exception-free)")
    class RejectionPaths {

        private final TokenService service = new TokenService(VALID_SECRET, 3600);

        @Test
        @DisplayName("null / blank token -> empty")
        void nullOrBlank() {
            assertThat(service.parse(null)).isEmpty();
            assertThat(service.parse("")).isEmpty();
            assertThat(service.parse("   ")).isEmpty();
        }

        @Test
        @DisplayName("wrong segment count -> empty")
        void malformedSegments() {
            assertThat(service.parse("only-one-segment")).isEmpty();
            assertThat(service.parse("two.segments")).isEmpty();
            assertThat(service.parse("a.b.c.d")).isEmpty();
        }

        @Test
        @DisplayName("tampered signature -> empty")
        void tamperedSignature() {
            String token = service.issue("USER0001", UserType.USER);
            String[] parts = token.split("\\.");
            String tampered = parts[0] + "." + parts[1] + "." + b64url("not-the-real-signature");
            assertThat(service.parse(tampered)).isEmpty();
        }

        @Test
        @DisplayName("expired token (valid signature, past exp) -> empty")
        void expiredToken() throws Exception {
            long past = Instant.now().getEpochSecond() - 100;
            String payload = "{\"sub\":\"USER0001\",\"typ\":\"U\",\"iat\":" + (past - 10)
                    + ",\"exp\":" + past + "}";
            String expired = craftSignedToken(VALID_SECRET, payload);
            assertThat(service.parse(expired)).isEmpty();
        }

        @Test
        @DisplayName("unknown role code (valid signature, future exp) -> empty")
        void unknownRoleCode() throws Exception {
            long future = Instant.now().getEpochSecond() + 3600;
            String payload = "{\"sub\":\"USER0001\",\"typ\":\"Z\",\"iat\":" + Instant.now().getEpochSecond()
                    + ",\"exp\":" + future + "}";
            String token = craftSignedToken(VALID_SECRET, payload);
            assertThat(service.parse(token)).isEmpty();
        }

        @Test
        @DisplayName("a token signed with a DIFFERENT secret -> empty")
        void wrongSecret() {
            TokenService other = new TokenService("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ0", 3600);
            String foreignToken = other.issue("USER0001", UserType.USER);
            assertThat(service.parse(foreignToken)).isEmpty();
        }
    }
}
