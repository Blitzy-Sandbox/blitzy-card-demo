package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure, Spring-free unit test for {@link JwtService}.
 *
 * <p>{@code JwtService} is the Java&nbsp;25 / Spring&nbsp;Boot replacement for the CICS
 * pseudo-conversational {@code COMMAREA} session state used by the legacy AWS CardDemo signon
 * program {@code COSGN00C}. In the mainframe design that program authenticated the user, moved
 * the user id into {@code CDEMO-USER-ID}, derived the user type, and carried that state forward
 * across BMS screens in the COMMAREA. Here the same two pieces of session state — the user id
 * (token subject) and the role claim ({@code ADMIN} or {@code USER}) — are externalized into a
 * compact, HMAC-SHA256 signed JWT, so this suite verifies the issue/validate/extract contract
 * that the signon and security layers depend on.</p>
 *
 * <p>The test constructs the service directly through its constructor with a test-only signing
 * secret and expiration; it therefore loads <strong>no</strong> Spring {@code ApplicationContext},
 * starts no database, and touches no AWS or network resource, so every case runs in-memory and in
 * milliseconds. Coverage spans every public method of the service and each documented failure
 * branch (tampered signature, expiry, and null / empty / blank / malformed input), feeding the
 * project line-coverage gate. The design rationale for the stateless-JWT session model lives in
 * {@code docs/decision-log.md}, not in these comments.</p>
 */
@DisplayName("JwtService — stateless JWT session (CICS COMMAREA replacement)")
class JwtServiceTest {

    /**
     * Test-only HMAC signing secret. It is deliberately 62&nbsp;bytes long so that it satisfies
     * the 32-byte (256-bit) minimum HS256 requires; a shorter secret would make jjwt raise a
     * {@code WeakKeyException} while the service is being constructed. This value is not a
     * production secret and exists solely to exercise the signing path.
     */
    private static final String SECRET =
            "test-only-jwt-secret-that-is-at-least-32-bytes-long-1234567890";

    /** Positive, one-hour token lifetime used for the happy-path cases. */
    private static final long EXPIRATION_MS = 3_600_000L;

    /**
     * Negative lifetime used to mint an already-expired token deterministically: the issued
     * token's {@code exp} is set one minute in the past, so validation fails on expiry regardless
     * of how fast the test runs (no reliance on wall-clock timing between issue and validate).
     */
    private static final long EXPIRED_TTL_MS = -60_000L;

    /** Representative CardDemo user id; becomes the JWT subject (mirrors {@code CDEMO-USER-ID}). */
    private static final String USER_ID = "USER0001";

    /** Administrative role, derived from {@code SEC-USR-TYPE = 'A'} in the legacy USRSEC record. */
    private static final String ADMIN_ROLE = "ADMIN";

    /** Regular-user role, derived from any non-{@code 'A'} {@code SEC-USR-TYPE} value. */
    private static final String USER_ROLE = "USER";

    /** Service under test, freshly built before each case with the positive expiration. */
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        // Constructor injection lets the service be instantiated without a Spring context.
        jwtService = new JwtService(SECRET, EXPIRATION_MS);
    }

    // ---------------------------------------------------------------------
    // Phase 2 — Issuance and claim round-trips (happy paths)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("generateToken then extractUserId/extractRole round-trips the ADMIN session")
    void generateToken_thenExtractUserIdAndRole_roundTrips() {
        String token = jwtService.generateToken(USER_ID, ADMIN_ROLE);

        assertThat(token).isNotNull().isNotBlank();
        assertThat(jwtService.extractUserId(token)).isEqualTo(USER_ID);
        assertThat(jwtService.extractRole(token)).isEqualTo(ADMIN_ROLE);
    }

    @Test
    @DisplayName("generateToken round-trips the USER role claim")
    void generateToken_userRole_roundTrips() {
        String token = jwtService.generateToken(USER_ID, USER_ROLE);

        assertThat(jwtService.extractUserId(token)).isEqualTo(USER_ID);
        assertThat(jwtService.extractRole(token)).isEqualTo(USER_ROLE);
    }

    @Test
    @DisplayName("generateToken rejects null userId and null role with NullPointerException")
    void generateToken_nullArguments_throwNpe() {
        assertThatThrownBy(() -> jwtService.generateToken(null, ADMIN_ROLE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userId");

        assertThatThrownBy(() -> jwtService.generateToken(USER_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("role");
    }

    // ---------------------------------------------------------------------
    // Phase 2 — Validation: accept the good, reject the bad (never throw)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("validateToken accepts a freshly issued, correctly signed token")
    void validateToken_validToken_returnsTrue() {
        String token = jwtService.generateToken(USER_ID, ADMIN_ROLE);

        assertThat(jwtService.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("validateToken rejects a signature-tampered token (returns false, does not throw)")
    void validateToken_tamperedToken_returnsFalse() {
        String token = jwtService.generateToken(USER_ID, ADMIN_ROLE);
        String tampered = tamperSignature(token);

        // Guard: the tampering actually mutated the token string.
        assertThat(tampered).isNotEqualTo(token);
        // A broken signature is rejected as false, never surfaced as an exception.
        assertThat(jwtService.validateToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("validateToken rejects an expired token (returns false, does not throw)")
    void validateToken_expiredToken_returnsFalse() {
        // Same secret, so the signature is valid; only the expiry differs.
        JwtService shortLived = new JwtService(SECRET, EXPIRED_TTL_MS);
        String expired = shortLived.generateToken(USER_ID, ADMIN_ROLE);

        assertThat(jwtService.validateToken(expired)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not.a.jwt", "a.b", "onlyonesegment"})
    @DisplayName("validateToken rejects null/empty/blank/malformed input (returns false, does not throw)")
    void validateToken_malformedToken_returnsFalse(String badToken) {
        assertThat(jwtService.validateToken(badToken)).isFalse();
    }

    // ---------------------------------------------------------------------
    // Phase 2 — Extraction contract on an invalid token (documented to throw)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("extractUserId/extractRole throw JwtException on a tampered token")
    void extract_tamperedToken_throwsJwtException() {
        String tampered = tamperSignature(jwtService.generateToken(USER_ID, ADMIN_ROLE));

        // Unlike validateToken, the extract methods propagate verification failures so that
        // callers must validate first; a bad signature surfaces as a JwtException subtype.
        assertThatThrownBy(() -> jwtService.extractUserId(tampered))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwtService.extractRole(tampered))
                .isInstanceOf(JwtException.class);
    }

    // ---------------------------------------------------------------------
    // Test helpers
    // ---------------------------------------------------------------------

    /**
     * Returns a copy of the given compact JWS with a corrupted signature. A compact JWS always
     * has exactly three dot-delimited Base64URL segments ({@code header.payload.signature}); this
     * flips the leading character of the signature segment ({@code 'A'} &harr; {@code 'B'}).
     * Because the first character carries the most-significant bits of the first signature byte,
     * the change always alters the decoded signature (there is no Base64URL trailing-bit
     * malleability at the leading position), forcing verification to fail.
     *
     * @param token a valid compact JWS produced by {@link JwtService#generateToken(String, String)}
     * @return the same token with a single, guaranteed-effective mutation in its signature segment
     */
    private static String tamperSignature(String token) {
        String[] parts = token.split("\\.");
        char[] signature = parts[2].toCharArray();
        signature[0] = signature[0] == 'A' ? 'B' : 'A';
        return parts[0] + "." + parts[1] + "." + new String(signature);
    }
}
