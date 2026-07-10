package com.carddemo.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Stateless JSON Web Token (JWT) session service.
 *
 * <p>This support service is the Java&nbsp;25 / Spring&nbsp;Boot replacement for the CICS
 * pseudo-conversational {@code COMMAREA} state that the legacy AWS CardDemo COBOL programs
 * carried across BMS screens. In the mainframe design, {@code COSGN00C} verified the signed-on
 * user against the {@code USRSEC} file and then routed to {@code COADM01C} or {@code COMEN01C}
 * using COMMAREA fields such as {@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE}. Here, that
 * conversational state is externalized into a compact, HMAC-SHA256 (HS256) signed JWT that the
 * client presents on every subsequent request, so the server retains no session state.</p>
 *
 * <p>The token carries exactly two pieces of session state:</p>
 * <ul>
 *   <li>the <em>subject</em> ({@code sub}) &mdash; the CardDemo user id (for example
 *       {@code USER0001}); and</li>
 *   <li>a {@code role} claim &mdash; either {@code ADMIN} or {@code USER}, derived by
 *       {@code SignonService} from the user-security record type ({@code SEC-USR-TYPE} of
 *       {@code 'A'} maps to {@code ADMIN}, any other value maps to {@code USER}).</li>
 * </ul>
 *
 * <p>Issuance is performed by {@code SignonService} at signon; validation and claim extraction
 * are performed by the security layer's JWT authentication filter on every protected
 * endpoint.</p>
 *
 * <p><strong>Security.</strong> The signing secret and the token lifetime are supplied
 * exclusively through externalized configuration ({@code carddemo.security.jwt.secret} and
 * {@code carddemo.security.jwt.expiration-ms}); no secret is ever hard-coded and no default
 * secret is provided. For HS256 the configured secret must be at least 32&nbsp;bytes
 * (256&nbsp;bits); a shorter secret causes jjwt to raise
 * {@link io.jsonwebtoken.security.WeakKeyException} while this service is being constructed,
 * which is the intended fail-fast behavior. Tokens, secrets, and claim values are never logged;
 * only a coarse failure category is emitted at {@code DEBUG}.</p>
 *
 * <p>This service holds no bean dependencies and is therefore thread-safe: the derived
 * {@link SecretKey} is immutable and jjwt's builder and parser instances are created per call.</p>
 */
@Service
public class JwtService {

    /** Name of the custom claim under which the authenticated user's role is stored. */
    private static final String CLAIM_ROLE = "role";

    /** SLF4J logger; emits only coarse, non-sensitive diagnostics (never tokens, secrets, or claims). */
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /**
     * Immutable HMAC signing and verification key, derived once from the configured secret and
     * reused for every sign and verify operation.
     */
    private final SecretKey key;

    /** Token lifetime in milliseconds, measured from the instant of issuance. */
    private final long expirationMs;

    /**
     * Creates the service with configuration injected from the active Spring profile.
     *
     * <p>Constructor injection is used deliberately so that the service can also be instantiated
     * directly in unit tests (passing a secret and expiration) without bootstrapping a Spring
     * application context.</p>
     *
     * @param secret       the HMAC signing secret; must be at least 32&nbsp;bytes for HS256,
     *                     otherwise {@link io.jsonwebtoken.security.WeakKeyException} is raised
     *                     here (fail-fast at startup)
     * @param expirationMs the token time-to-live in milliseconds; defaults to one hour
     *                     ({@code 3600000}) when the property is absent
     */
    public JwtService(
            @Value("${carddemo.security.jwt.secret}") String secret,
            @Value("${carddemo.security.jwt.expiration-ms:3600000}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /**
     * Issues a signed JWT for a successfully authenticated user. This is the stateless
     * replacement for populating and returning the CICS COMMAREA at signon.
     *
     * @param userId the authenticated CardDemo user id; becomes the token subject ({@code sub})
     * @param role   the authenticated user's role, {@code ADMIN} or {@code USER}
     * @return a compact, URL-safe, signed JWT string
     * @throws NullPointerException if {@code userId} or {@code role} is {@code null}
     */
    public String generateToken(String userId, String role) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .claim(CLAIM_ROLE, role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expirationMs))
                .signWith(key)
                .compact();
    }

    /**
     * Validates a token's signature and expiration without exposing any of its contents.
     *
     * <p>The JWT expiry code path mirrors the COBOL happy/sad-path split at signon: a valid,
     * unexpired, correctly signed token is accepted, and anything else is rejected rather than
     * throwing to the caller.</p>
     *
     * @param token the compact JWT string to validate
     * @return {@code true} if the token is well-formed, correctly signed, and not expired;
     *         {@code false} otherwise
     */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // Never log the token, secret, or claim contents; the failure category alone is
            // sufficient for diagnostics (for example ExpiredJwtException, SignatureException).
            log.debug("JWT validation failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Extracts the authenticated user id (the {@code sub} claim) from a token. Callers should
     * first confirm the token with {@link #validateToken(String)}.
     *
     * @param token the compact JWT string
     * @return the user id carried in the token subject
     * @throws JwtException             if the token is malformed, has an invalid signature, or is
     *                                  expired
     * @throws IllegalArgumentException if the token is {@code null} or blank
     */
    public String extractUserId(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * Extracts the {@code role} claim ({@code ADMIN} or {@code USER}) from a token. Callers
     * should first confirm the token with {@link #validateToken(String)}.
     *
     * @param token the compact JWT string
     * @return the role carried in the token {@code role} claim
     * @throws JwtException             if the token is malformed, has an invalid signature, or is
     *                                  expired
     * @throws IllegalArgumentException if the token is {@code null} or blank
     */
    public String extractRole(String token) {
        return parseClaims(token).get(CLAIM_ROLE, String.class);
    }

    /**
     * Parses and cryptographically verifies a token, returning its verified claim set. Uses the
     * jjwt&nbsp;0.12.x fluent parser API ({@code verifyWith} / {@code parseSignedClaims}).
     *
     * @param token the compact JWT string
     * @return the verified {@link Claims} payload
     * @throws JwtException             if the token is malformed, has an invalid signature, or is
     *                                  expired
     * @throws IllegalArgumentException if the token is {@code null} or blank
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
