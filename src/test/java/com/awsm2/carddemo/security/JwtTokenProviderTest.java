/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.security;

import com.awsm2.carddemo.adapter.SecretsManagerService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito unit tests for {@link JwtTokenProvider} — the
 * AWS Secrets Manager-backed JWT issuer/validator that replaces the
 * COBOL {@code COCOM01Y.cpy} {@code CARDDEMO-COMMAREA} identity
 * propagation pattern from COSGN00C.cbl L222-L240.
 *
 * <h2>QA Final Checkpoint 13 Maj-5 fix</h2>
 * <p>Prior to this test class, JWT issuance and validation was only
 * exercised indirectly by {@code SignonServiceTest} (mocking
 * {@code JwtTokenProvider.issueToken}) and by
 * {@code EndToEndTransactionWorkflowIT}. The QA CP13 report (finding
 * Maj-5) requires a dedicated test class to lock the security
 * primitive contract against regression.</p>
 *
 * <h2>Coverage scope</h2>
 * <ul>
 *   <li><b>{@code @PostConstruct initSigningKey()}</b> — Secrets Manager
 *       happy path, blank ARN rejection, empty secret rejection, key
 *       too short (HS256 requires ≥ 32 bytes / 256 bits).</li>
 *   <li><b>{@code issueToken}</b> — claim set correctness (iss, sub,
 *       iat, exp, userType, firstName, lastName), null first/last name
 *       mapped to empty string, throws NPE on null userId/userType.</li>
 *   <li><b>{@code validateToken}</b> — accepts valid token (returns
 *       Claims), rejects tampered signature, rejects expired,
 *       rejects malformed, rejects wrong issuer.</li>
 *   <li><b>{@code isValid}</b> — returns true/false predicate; never
 *       throws.</li>
 *   <li><b>{@code getExpiration}</b> — returns the configured TTL.</li>
 * </ul>
 *
 * @see JwtTokenProvider
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtTokenProvider — QA CP13 Maj-5 dedicated test class")
class JwtTokenProviderTest {

    /** A 32-byte (256-bit) HS256 signing key used by happy-path tests. */
    private static final String TEST_SIGNING_KEY_32 = "abcdefghijklmnopqrstuvwxyz012345";

    /** A 64-byte (512-bit) HS256 signing key used to verify keys longer than the minimum work. */
    private static final String TEST_SIGNING_KEY_64 =
            "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZab";

    private static final String SECRET_ARN =
            "arn:aws:secretsmanager:us-east-1:123456789012:secret:carddemo/jwt-signing-key-AbCdEf";

    private static final String FIELD_NAME = "jwtSigningKey";

    private static final Duration EXPIRATION = Duration.ofMinutes(30);

    @Mock
    private SecretsManagerService secretsManagerService;

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        // Default happy-path stubbing — individual tests override as needed.
        // lenient() because not every test invokes the stub.
        lenient().when(secretsManagerService.getSecretJsonField(SECRET_ARN, FIELD_NAME))
                .thenReturn(Optional.of(TEST_SIGNING_KEY_32));
    }

    /**
     * Helper to construct a JwtTokenProvider and invoke {@code @PostConstruct}.
     * The constructor stores the params; {@code initSigningKey()} fetches the
     * key from Secrets Manager. Tests that need to verify {@code initSigningKey}
     * failure modes call this helper expecting a thrown exception.
     */
    private JwtTokenProvider buildAndInit() {
        JwtTokenProvider p = new JwtTokenProvider(
                secretsManagerService, SECRET_ARN, FIELD_NAME, EXPIRATION);
        p.initSigningKey();
        return p;
    }

    /**
     * Deterministically tampers a JWT's signature so the resulting token will
     * always fail HMAC validation.
     *
     * <p><b>Why not just flip the last character?</b> A 256-bit HS256 signature
     * is encoded as 43 base64url characters. The 43rd (last) character holds
     * only 4 useful bits of the signature; its low 2 bits are
     * "padding" / "unused" bits that some JJWT base64url decoder versions
     * mask off when reconstructing the signature bytes. As a consequence,
     * pairs of trailing characters such as {@code A↔B} or {@code C↔D} can
     * decode to identical signature byte arrays, leaving the "tampered" token
     * actually valid and turning the test into a probabilistic failure
     * (~6 % per run, ~12 % across two consecutive verify runs).</p>
     *
     * <p>This helper instead flips a character five positions <i>into</i>
     * the signature segment, where every one of the 6 base64url bits maps
     * to a real signature bit. Any single bit flipped in that region is
     * guaranteed to change the decoded signature, so HMAC verification
     * will always reject the token deterministically.</p>
     *
     * <p>The choice of {@code 'A' ↔ 'B'} as the replacement pair preserves
     * the "single-bit flip" semantic of the original test (one base64url
     * value swapped for an adjacent one) while moving the change away
     * from the padding-bit boundary.</p>
     */
    private static String tamperSignature(String token) {
        int sigStart = token.lastIndexOf('.') + 1;
        // 5 chars into the signature is well within the "all bits useful" zone:
        // every base64url char from position [0,41] in a 43-char HS256 signature
        // contributes 6 useful bits to the decoded byte stream — there are no
        // padding bits until char 42 (the 43rd / last character).
        int tamperPos = sigStart + 5;
        char originalChar = token.charAt(tamperPos);
        char replacementChar = (originalChar == 'A') ? 'B' : 'A';
        return token.substring(0, tamperPos)
                + replacementChar
                + token.substring(tamperPos + 1);
    }

    // -------------------------------------------------------------------------
    // @PostConstruct initSigningKey
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("initSigningKey — Secrets Manager key fetch and validation")
    class InitSigningKeyTests {

        @Test
        @DisplayName("happy path: 32-byte key from Secrets Manager initializes successfully")
        void happyPathInitializesProvider() {
            provider = buildAndInit();

            // Successful init is verified by being able to issue a token.
            String token = provider.issueToken("USER0001", "U", "John", "Doe");
            assertThat(token).isNotBlank();
        }

        @Test
        @DisplayName("64-byte key is also accepted (Keys.hmacShaKeyFor will pick HS512-capable key)")
        void longKeyAccepted() {
            when(secretsManagerService.getSecretJsonField(SECRET_ARN, FIELD_NAME))
                    .thenReturn(Optional.of(TEST_SIGNING_KEY_64));

            provider = buildAndInit();
            assertThat(provider.issueToken("USER0001", "U", "John", "Doe")).isNotBlank();
        }

        @Test
        @DisplayName("blank ARN throws IllegalStateException")
        void blankArnThrows() {
            JwtTokenProvider p = new JwtTokenProvider(
                    secretsManagerService, "  ", FIELD_NAME, EXPIRATION);

            assertThatThrownBy(p::initSigningKey)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("signing-key-secret-arn");
        }

        @Test
        @DisplayName("empty secret field throws IllegalStateException with ARN suffix in message")
        void emptySecretThrows() {
            when(secretsManagerService.getSecretJsonField(SECRET_ARN, FIELD_NAME))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> buildAndInit())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not found")
                    .hasMessageContaining(FIELD_NAME);
        }

        @Test
        @DisplayName("blank secret value throws IllegalStateException")
        void blankSecretValueThrows() {
            when(secretsManagerService.getSecretJsonField(SECRET_ARN, FIELD_NAME))
                    .thenReturn(Optional.of("   "));

            assertThatThrownBy(() -> buildAndInit())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("key shorter than 32 bytes throws IllegalStateException with byte count")
        void shortKeyThrows() {
            // 31 bytes — one byte below MIN_HS256_KEY_BYTES
            String shortKey = "a".repeat(31);
            when(secretsManagerService.getSecretJsonField(SECRET_ARN, FIELD_NAME))
                    .thenReturn(Optional.of(shortKey));

            assertThatThrownBy(() -> buildAndInit())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("too short")
                    .hasMessageContaining("32") // MIN_HS256_KEY_BYTES
                    .hasMessageContaining("31"); // actual length
        }

        @Test
        @DisplayName("error message never contains the raw key material (PCI-DSS safety)")
        void errorMessageDoesNotLeakKey() {
            String shortKey = "DO_NOT_LEAK_ME_PLEASE_____________"; // 34 chars (but we'll truncate to 31)
            String truncated = shortKey.substring(0, 31);
            when(secretsManagerService.getSecretJsonField(SECRET_ARN, FIELD_NAME))
                    .thenReturn(Optional.of(truncated));

            assertThatThrownBy(() -> buildAndInit())
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(ex -> {
                        // The exception message must not include the literal
                        // key material; only the byte count and ARN suffix.
                        assertThat(ex.getMessage()).doesNotContain("DO_NOT_LEAK_ME");
                    });
        }
    }

    // -------------------------------------------------------------------------
    // issueToken — claim set construction
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("issueToken — JWT compact-serialisation contract")
    class IssueTokenTests {

        @BeforeEach
        void initProvider() {
            provider = buildAndInit();
        }

        @Test
        @DisplayName("issued token contains iss=carddemo, sub=userId, custom claims")
        void tokenContainsExpectedClaims() {
            String token = provider.issueToken("ADMIN001", "A", "Jane", "Smith");

            Claims claims = provider.validateToken(token);

            assertThat(claims.getIssuer()).isEqualTo("carddemo");
            assertThat(claims.getSubject()).isEqualTo("ADMIN001");
            assertThat(claims.get("userType", String.class)).isEqualTo("A");
            assertThat(claims.get("firstName", String.class)).isEqualTo("Jane");
            assertThat(claims.get("lastName", String.class)).isEqualTo("Smith");
            assertThat(claims.getIssuedAt()).isNotNull();
            assertThat(claims.getExpiration()).isNotNull();
            // exp should be approximately now + 30 min (within ±5s tolerance for test slack)
            Instant now = Instant.now();
            Instant exp = claims.getExpiration().toInstant();
            assertThat(exp).isBetween(
                    now.plus(EXPIRATION).minusSeconds(5),
                    now.plus(EXPIRATION).plusSeconds(5));
        }

        @Test
        @DisplayName("null firstName/lastName are mapped to empty strings (not null)")
        void nullNamesMappedToEmptyString() {
            String token = provider.issueToken("USER0002", "U", null, null);
            Claims claims = provider.validateToken(token);

            assertThat(claims.get("firstName", String.class)).isEqualTo("");
            assertThat(claims.get("lastName", String.class)).isEqualTo("");
        }

        @Test
        @DisplayName("null userId throws NullPointerException")
        void nullUserIdThrows() {
            assertThatThrownBy(() ->
                    provider.issueToken(null, "U", "X", "Y"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("userId");
        }

        @Test
        @DisplayName("null userType throws NullPointerException")
        void nullUserTypeThrows() {
            assertThatThrownBy(() ->
                    provider.issueToken("USER0001", null, "X", "Y"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("userType");
        }

        @Test
        @DisplayName("issued token has alg=HS256 in header")
        void tokenUsesHs256Algorithm() {
            String token = provider.issueToken("USER0001", "U", "X", "Y");

            // JWT compact format: <header>.<payload>.<signature>
            // Decode the header (base64url) and verify alg=HS256
            String[] parts = token.split("\\.");
            assertThat(parts).hasSize(3);
            String header = new String(
                    java.util.Base64.getUrlDecoder().decode(parts[0]),
                    StandardCharsets.UTF_8);
            assertThat(header).contains("\"alg\":\"HS256\"");
        }
    }

    // -------------------------------------------------------------------------
    // validateToken — signature, expiration, issuer, format
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("validateToken — accepts valid, rejects all forms of invalid")
    class ValidateTokenTests {

        @BeforeEach
        void initProvider() {
            provider = buildAndInit();
        }

        @Test
        @DisplayName("valid token returns parsed Claims")
        void validTokenReturnsClaims() {
            String token = provider.issueToken("USER0001", "U", "X", "Y");

            Claims claims = provider.validateToken(token);

            assertThat(claims).isNotNull();
            assertThat(claims.getSubject()).isEqualTo("USER0001");
        }

        @Test
        @DisplayName("tampered token (signature mismatch) throws JwtException")
        void tamperedTokenThrowsSignatureException() {
            String token = provider.issueToken("USER0001", "U", "X", "Y");

            // Tamper with the signature deterministically — see
            // tamperSignature() Javadoc for why we cannot flip the last char.
            String tampered = tamperSignature(token);

            assertThatThrownBy(() -> provider.validateToken(tampered))
                    .isInstanceOf(JwtException.class);
        }

        @Test
        @DisplayName("token signed with different key throws SignatureException")
        void wrongKeyThrowsSignatureException() {
            // Build a token signed with a DIFFERENT key (32 bytes, different content).
            String differentKey = "ZYXWVUTSRQPONMLKJIHGFEDCBA987654";
            SecretKey wrongKey = new SecretKeySpec(differentKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            String token = Jwts.builder()
                    .issuer("carddemo")
                    .subject("USER0001")
                    .issuedAt(Date.from(Instant.now()))
                    .expiration(Date.from(Instant.now().plusSeconds(300)))
                    .signWith(wrongKey, Jwts.SIG.HS256)
                    .compact();

            assertThatThrownBy(() -> provider.validateToken(token))
                    .isInstanceOf(SignatureException.class);
        }

        @Test
        @DisplayName("expired token throws ExpiredJwtException")
        void expiredTokenThrowsExpiredException() {
            // Manually build a token with exp in the past, using the SAME key.
            SecretKey key = new SecretKeySpec(
                    TEST_SIGNING_KEY_32.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            String token = Jwts.builder()
                    .issuer("carddemo")
                    .subject("USER0001")
                    .issuedAt(Date.from(Instant.now().minusSeconds(7200))) // 2 hours ago
                    .expiration(Date.from(Instant.now().minusSeconds(3600))) // 1 hour ago — expired
                    .signWith(key, Jwts.SIG.HS256)
                    .compact();

            assertThatThrownBy(() -> provider.validateToken(token))
                    .isInstanceOf(ExpiredJwtException.class);
        }

        @Test
        @DisplayName("malformed token (not 3 dot-separated segments) throws MalformedJwtException")
        void malformedTokenThrowsMalformedException() {
            String malformed = "this.is.not.valid.token";

            assertThatThrownBy(() -> provider.validateToken(malformed))
                    .isInstanceOf(JwtException.class);
        }

        @Test
        @DisplayName("token with wrong issuer throws JwtException (requireIssuer enforcement)")
        void wrongIssuerThrowsJwtException() {
            // Token signed with the CORRECT key but the WRONG issuer.
            SecretKey key = new SecretKeySpec(
                    TEST_SIGNING_KEY_32.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            String token = Jwts.builder()
                    .issuer("not-carddemo")
                    .subject("USER0001")
                    .issuedAt(Date.from(Instant.now()))
                    .expiration(Date.from(Instant.now().plusSeconds(300)))
                    .signWith(key, Jwts.SIG.HS256)
                    .compact();

            assertThatThrownBy(() -> provider.validateToken(token))
                    .isInstanceOf(JwtException.class);
        }

        @Test
        @DisplayName("null token throws NullPointerException")
        void nullTokenThrowsNpe() {
            assertThatThrownBy(() -> provider.validateToken(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("token");
        }

        @Test
        @DisplayName("empty token throws IllegalArgumentException or JwtException")
        void emptyTokenThrows() {
            assertThatThrownBy(() -> provider.validateToken(""))
                    .isInstanceOfAny(IllegalArgumentException.class, JwtException.class);
        }
    }

    // -------------------------------------------------------------------------
    // isValid — boolean predicate, never throws
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("isValid — boolean predicate, swallows all exceptions")
    class IsValidPredicateTests {

        @BeforeEach
        void initProvider() {
            provider = buildAndInit();
        }

        @Test
        @DisplayName("returns true for valid token")
        void validReturnsTrue() {
            String token = provider.issueToken("USER0001", "U", "X", "Y");
            assertThat(provider.isValid(token)).isTrue();
        }

        @Test
        @DisplayName("returns false for null")
        void nullReturnsFalse() {
            assertThat(provider.isValid(null)).isFalse();
        }

        @Test
        @DisplayName("returns false for malformed token (no throw)")
        void malformedReturnsFalse() {
            assertThat(provider.isValid("not.a.token")).isFalse();
        }

        @Test
        @DisplayName("returns false for tampered token (no throw)")
        void tamperedReturnsFalse() {
            String token = provider.issueToken("USER0001", "U", "X", "Y");
            // Use the shared tamperSignature() helper so this test is
            // deterministic — see helper's Javadoc for the base64url
            // padding-bit pitfall the previous inline implementation hit.
            String tampered = tamperSignature(token);
            assertThat(provider.isValid(tampered)).isFalse();
        }

        @Test
        @DisplayName("returns false for empty string (no throw)")
        void emptyReturnsFalse() {
            assertThat(provider.isValid("")).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // getExpiration — config getter
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("getExpiration — TTL getter")
    class GetExpirationTests {

        @Test
        @DisplayName("returns the configured Duration")
        void returnsConfiguredDuration() {
            Duration custom = Duration.ofMinutes(45);
            JwtTokenProvider p = new JwtTokenProvider(
                    secretsManagerService, SECRET_ARN, FIELD_NAME, custom);
            // No init required for this method.

            assertThat(p.getExpiration()).isEqualTo(custom);
        }

        @Test
        @DisplayName("default expiration (PT30M) is honored when configured")
        void defaultExpiration() {
            JwtTokenProvider p = new JwtTokenProvider(
                    secretsManagerService, SECRET_ARN, FIELD_NAME, EXPIRATION);
            assertThat(p.getExpiration()).isEqualTo(Duration.ofMinutes(30));
        }
    }
}
