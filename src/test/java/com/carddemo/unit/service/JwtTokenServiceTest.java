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
package com.carddemo.unit.service;

import com.carddemo.service.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pure JUnit&nbsp;5 unit tests for {@link JwtTokenService}.
 *
 * <p>{@code JwtTokenService} is modern infrastructure with no 1:1 COBOL ancestor: it
 * issues and validates the stateless, signed JSON Web Tokens (JWTs) that replace the
 * legacy CICS pseudo-conversational {@code COMMAREA} (copybook {@code COCOM01Y})
 * carry-over state with token-based security that retains no server-side session
 * (AAP&nbsp;&sect;0.1.1.1, &sect;0.3.2). A token's subject is the user identifier and its
 * {@value JwtTokenService#CLAIM_USER_TYPE} claim is the single-character user-type code
 * ({@code 'A'} administrator, {@code 'U'} standard user).</p>
 *
 * <p>The system under test (SUT) is exercised as a plain POJO &mdash; constructed directly
 * with literal configuration values, exactly as the {@code @Value}-annotated constructor
 * would be populated from the {@code carddemo.security.jwt.*} properties &mdash; so no
 * Spring context, Mockito, database, AWS, or network access is required.</p>
 *
 * <p><strong>Secret encoding (compiled-source-driven).</strong> The SUT constructor derives
 * its HMAC-SHA signing key via {@code Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))};
 * that is, the configured secret is <em>Base64-decoded</em> before use. HS256 requires the
 * decoded key to be at least 256&nbsp;bits (32&nbsp;bytes), and a shorter key fails fast with
 * a {@code WeakKeyException} at construction. These tests therefore supply each secret as the
 * Base64 encoding of a raw value of at least 32&nbsp;bytes (see {@link #base64Secret(String)}),
 * rather than a bare 32-character string &mdash; the latter would Base64-decode to only
 * 24&nbsp;bytes (192&nbsp;bits) and be rejected.</p>
 *
 * <p>Assertions never reference the JJWT library directly; every behavior is observed through
 * the service's own public methods, keeping the test decoupled from JWT internals and free of
 * deprecated-API usage under the project's zero-warning ({@code -Werror}) build.</p>
 */
@DisplayName("JwtTokenService — stateless JWT issue/validate/parse")
class JwtTokenServiceTest {

    /** Issuer stamped into, and required of, every token (the {@code carddemo.security.jwt.issuer} value). */
    private static final String ISSUER = "carddemo";

    /** One-hour token lifetime, in milliseconds, for the round-trip (non-expiry) scenarios. */
    private static final long EXPIRATION_MS = 3_600_000L;

    /**
     * Base64-encoded HS256 signing secret whose decoded form is at least 32&nbsp;bytes,
     * comfortably above the 256-bit HS256 minimum. Obviously-synthetic, non-production test data.
     */
    private static final String SECRET =
            base64Secret("carddemo-unit-test-hs256-primary-signing-key-0001");

    /**
     * A second, independent Base64-encoded signing secret used to prove that a token signed with a
     * different key is rejected. Decoded form is at least 32&nbsp;bytes; obviously-synthetic test data.
     */
    private static final String OTHER_SECRET =
            base64Secret("carddemo-unit-test-hs256-secondary-signing-key-0002");

    /** The primary SUT, reconstructed before each test with the standard one-hour lifetime. */
    private JwtTokenService service;

    @BeforeEach
    void setUp() {
        service = new JwtTokenService(SECRET, EXPIRATION_MS, ISSUER);
    }

    @Test
    @DisplayName("CLAIM_USER_TYPE exposes the stable \"userType\" claim name")
    void claimUserTypeConstantIsUserType() {
        assertThat(JwtTokenService.CLAIM_USER_TYPE).isEqualTo("userType");
    }

    @Test
    @DisplayName("admin token round-trips: 3-part JWT, valid, subject and userType preserved")
    void generatesAndRoundTripsAdministratorClaims() {
        String token = service.generateToken("ADMIN001", "A");

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(service.validateToken(token)).isTrue();
        assertThat(service.getUserId(token)).isEqualTo("ADMIN001");
        assertThat(service.getUserType(token)).isEqualTo("A");
    }

    @Test
    @DisplayName("standard-user token round-trips: 3-part JWT, valid, subject and userType preserved")
    void generatesAndRoundTripsStandardUserClaims() {
        String token = service.generateToken("USER0001", "U");

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(service.validateToken(token)).isTrue();
        assertThat(service.getUserId(token)).isEqualTo("USER0001");
        assertThat(service.getUserType(token)).isEqualTo("U");
    }

    @Test
    @DisplayName("validateToken returns false for a structurally malformed token")
    void validateRejectsMalformedToken() {
        assertThat(service.validateToken("not.a.jwt")).isFalse();
    }

    @Test
    @DisplayName("validateToken returns false (never throws) for blank and null input")
    void validateRejectsBlankAndNullWithoutThrowing() {
        // Blank/null first prove the no-throw contract, then prove the false return value.
        assertThatCode(() -> service.validateToken("")).doesNotThrowAnyException();
        assertThatCode(() -> service.validateToken(null)).doesNotThrowAnyException();

        assertThat(service.validateToken("")).isFalse();
        assertThat(service.validateToken(null)).isFalse();
    }

    @Test
    @DisplayName("validateToken returns false for a token signed with a different secret")
    void validateRejectsTokenSignedWithDifferentSecret() {
        JwtTokenService otherKeyService = new JwtTokenService(OTHER_SECRET, EXPIRATION_MS, ISSUER);
        String foreignToken = otherKeyService.generateToken("ADMIN001", "A");

        // Same issuer and a well-formed token, but the signature was produced with OTHER_SECRET,
        // so signature verification against the primary service's key must fail — without throwing.
        assertThat(service.validateToken(foreignToken)).isFalse();
    }

    @Test
    @DisplayName("validateToken returns false for an already-expired token")
    void validateRejectsExpiredToken() {
        // A negative time-to-live places the expiration one second before issuance, so the token is
        // expired the instant it is created — deterministic and fast (no Thread.sleep required).
        JwtTokenService expiredTokenService = new JwtTokenService(SECRET, -1_000L, ISSUER);
        String expiredToken = expiredTokenService.generateToken("ADMIN001", "A");

        assertThat(expiredTokenService.validateToken(expiredToken)).isFalse();
    }

    /**
     * Encodes raw secret material as standard (RFC&nbsp;4648) Base64 for consumption by
     * {@link JwtTokenService}, which Base64-decodes its configured secret before deriving the
     * HMAC-SHA signing key. The raw input must be at least 32&nbsp;bytes so that the decoded key
     * satisfies the 256-bit HS256 minimum.
     *
     * @param raw the raw secret material; its UTF-8 length must be at least 32&nbsp;bytes
     * @return the Base64 encoding of {@code raw}
     */
    private static String base64Secret(String raw) {
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
