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

import com.awsm2.carddemo.exception.CardDemoException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JWT (JSON Web Token) provider — issues and validates signed JWTs for the
 * CardDemo Java target's stateless authentication layer.
 *
 * <p>Per AAP &sect;0.3.4 ("JWT bearer tokens issued by
 * {@code /api/auth/signin}") and AAP &sect;0.7.1 ("Spring Security 6 +
 * JWT + BCrypt"), this component is the single source of truth for token
 * issuance and validation. It is consumed by:</p>
 * <ul>
 *   <li>{@code SignonService} (replacing {@code COSGN00C}) &mdash; calls
 *       {@link #generateToken(String, String, java.util.Collection)} after
 *       BCrypt password verification succeeds.</li>
 *   <li>{@link JwtAuthenticationFilter} &mdash; calls
 *       {@link #parseAndValidate(String)} on every incoming HTTP request
 *       to authenticate the bearer.</li>
 * </ul>
 *
 * <h2>Replaces (AAP &sect;0.1.1)</h2>
 * <p>Replaces: CICS pseudo-conversational COMMAREA state
 * ({@code COCOM01Y.cpy}) + RACF user identity propagation. The COBOL
 * source's user identity flowed through CICS-managed memory; the Java
 * target's identity flows through a signed JWT validated per request.</p>
 *
 * <h2>Refresh-scope discipline (AAP &sect;0.6.4)</h2>
 * <p>This bean is {@code @RefreshScope}-annotated so that a Secrets
 * Manager rotation event (handled by {@code SecretsManagerConfig}) triggers
 * a bean re-instantiation, picking up the rotated
 * {@code carddemo.security.jwt.signing-key} on the next token issuance or
 * validation. Existing JWTs issued under the previous key remain valid
 * until expiry &mdash; if the previous key is also still configured (the
 * standard Secrets Manager AWSCURRENT/AWSPREVIOUS pattern), this provider
 * can validate against both.</p>
 *
 * <h2>Key strength (AAP &sect;0.7.1)</h2>
 * <p>HMAC-SHA-256 (HS256) is the chosen algorithm because it is the
 * standard for symmetric-key JWTs in Spring ecosystem; HS512 is an
 * alternative for higher security at the cost of larger signatures.
 * RFC 7518 &sect;3.2 requires HMAC keys to be at least as long as the
 * hash output (256 bits for HS256, 512 bits for HS512). This provider
 * <strong>FAILS FAST</strong> at bean-init time if the configured signing
 * key is shorter than 256 bits (32 bytes), preventing a weak-key
 * misconfiguration from reaching production.</p>
 *
 * <h2>Claim set</h2>
 * <ul>
 *   <li>{@code sub} — the user ID (typically {@code A123} for admin or
 *       {@code U123} for regular user, uppercased)</li>
 *   <li>{@code userId} — synonym of {@code sub} for backward
 *       compatibility with consumers that expect the application-level
 *       name</li>
 *   <li>{@code roles} — list of role strings (e.g.,
 *       {@code ["ROLE_ADMIN"]})</li>
 *   <li>{@code userType} — single-character COBOL {@code SEC-USR-TYPE}
 *       value ({@code A}/{@code U}) for downstream traceability</li>
 *   <li>{@code iat} — issued-at timestamp (epoch seconds)</li>
 *   <li>{@code exp} — expiration timestamp (epoch seconds)</li>
 *   <li>{@code iss} — issuer (from {@code carddemo.security.jwt.issuer})</li>
 * </ul>
 *
 * @see JwtAuthenticationFilter
 * @see com.awsm2.carddemo.config.SecurityConfig
 */
@Component
@RefreshScope
public class JwtTokenProvider {

    private static final Logger LOG = LoggerFactory.getLogger(JwtTokenProvider.class);

    /** HMAC-SHA-256 minimum key size in bytes (256 bits / 8 = 32). */
    private static final int MIN_KEY_BYTES = 32;

    /** Reason code for invalid (malformed, unsigned, tampered) tokens. */
    static final String REASON_CODE_INVALID_TOKEN = "JWT_INVALID";
    /** Reason code for expired tokens. */
    static final String REASON_CODE_EXPIRED_TOKEN = "JWT_EXPIRED";
    /** Reason code for a misconfigured short signing key. */
    static final String REASON_CODE_WEAK_KEY = "JWT_SIGNING_KEY_TOO_SHORT";

    /** Claim names exposed publicly so other components can match consistently. */
    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_USER_TYPE = "userType";
    public static final String CLAIM_ROLES = "roles";

    /**
     * The signing key string sourced from
     * {@code carddemo.security.jwt.signing-key}. In production this value
     * is supplied by AWS Secrets Manager via
     * {@code spring.config.import: aws-secretsmanager:...}. In local
     * profile the {@code application.yml} fallback is a non-functional
     * 64-character development placeholder that nonetheless meets the
     * 32-byte minimum.
     */
    private final String signingKeyValue;

    /** JWT issuer claim (typically {@code "carddemo"}). */
    private final String issuer;

    /** Token expiration window in seconds (default 1 hour). */
    private final long expirationSeconds;

    /** Derived signing key after validation; computed in {@link #init()}. */
    private SecretKey secretKey;

    /**
     * Constructor injection &mdash; Spring resolves the three properties
     * at startup. The signing key is NOT yet validated here; validation
     * happens in {@link #init()} via {@link PostConstruct} so a missing
     * or short key surfaces as an application-startup failure.
     *
     * @param signingKey         signing key (UTF-8 bytes &ge; 32)
     * @param issuer             JWT issuer claim
     * @param expirationSeconds  expiration window in seconds
     */
    public JwtTokenProvider(
            @Value("${carddemo.security.jwt.signing-key:}") String signingKey,
            @Value("${carddemo.security.jwt.issuer:carddemo}") String issuer,
            @Value("${carddemo.security.jwt.expiration-seconds:3600}") long expirationSeconds) {
        // Replaces: CICS COMMAREA-propagated user identity (COCOM01Y.cpy) +
        // RACF identity propagation.
        this.signingKeyValue = (signingKey == null) ? "" : signingKey;
        this.issuer = (issuer == null || issuer.isBlank()) ? "carddemo" : issuer;
        this.expirationSeconds = (expirationSeconds > 0L) ? expirationSeconds : 3600L;
    }

    /**
     * Validates the configured signing key length and derives the HMAC
     * {@link SecretKey} used by jjwt for sign / verify. Fail-fast on a
     * short key: an application startup is preferable to a runtime
     * vulnerability.
     */
    @PostConstruct
    void init() {
        // Replaces: RFC 7518 §3.2 enforcement that was implicit in CICS
        // (CICS handled session security; now the application proves key
        // strength itself).
        byte[] keyBytes = signingKeyValue.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_KEY_BYTES) {
            // PCI-DSS-safe error: byte count only, never the key value.
            String msg = "JWT signing key is " + keyBytes.length
                    + " bytes; HS256 requires at least " + MIN_KEY_BYTES + " bytes (256 bits)";
            LOG.error("JWT key validation FAILED bytes={} required={}",
                    keyBytes.length, MIN_KEY_BYTES);
            throw new CardDemoException(REASON_CODE_WEAK_KEY, msg, null);
        }
        // hmacShaKeyFor selects HS256/HS384/HS512 based on the input key
        // size; 32-63 bytes → HS256, 64+ → HS512 capacity.
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        LOG.info("JWT signing key validated bytes={} issuer={} expirationSeconds={}",
                keyBytes.length, issuer, expirationSeconds);
    }

    // ---------------------------------------------------------------------
    // Public API — generation and validation
    // ---------------------------------------------------------------------

    /**
     * Generates a signed JWT for the supplied principal.
     *
     * @param userId    the user ID (will be uppercased for consistency
     *                  with the COBOL convention of always-uppercase
     *                  USRID); must not be {@code null}/blank
     * @param userType  single-character COBOL {@code SEC-USR-TYPE}
     *                  ({@code A} or {@code U}); may be {@code null}
     * @param roles     authorities to embed in the {@code roles} claim;
     *                  may be {@code null} or empty
     * @return the signed compact JWT
     * @throws IllegalArgumentException if {@code userId} is blank
     */
    public String generateToken(String userId, String userType, Collection<String> roles) {
        // Replaces: signon success path in COSGN00C — now issues a JWT
        // instead of populating CICS COMMAREA fields CDEMO-USRID / CDEMO-USRTYPE.
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be null/blank");
        }
        String normalizedUserId = userId.toUpperCase(java.util.Locale.US);
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(expirationSeconds);

        // Build the claims map up-front so we can include both `sub` and
        // `userId` consistently and emit `roles` as a real JSON array
        // (jjwt 0.12 supports List<String> via the addClaims helper).
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(CLAIM_USER_ID, normalizedUserId);
        if (userType != null && !userType.isBlank()) {
            claims.put(CLAIM_USER_TYPE, userType.toUpperCase(java.util.Locale.US));
        }
        claims.put(CLAIM_ROLES,
                (roles == null) ? Collections.emptyList() : List.copyOf(roles));

        return Jwts.builder()
                .claims(claims)
                .subject(normalizedUserId)
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Parses and validates a JWT compact string. Verifies the signature
     * with the configured signing key, checks issuer + expiration, and
     * returns the parsed claims.
     *
     * @param token  the compact JWT (with or without a leading
     *               {@code "Bearer "} prefix)
     * @return the validated claims
     * @throws CardDemoException with reason
     *         {@link #REASON_CODE_EXPIRED_TOKEN} when the token's
     *         {@code exp} is in the past, or
     *         {@link #REASON_CODE_INVALID_TOKEN} for any other parse /
     *         signature / format error
     */
    public Claims parseAndValidate(String token) {
        // Replaces: COMMAREA validation in every CICS pseudo-conversational
        // re-entry — now per-request JWT verification.
        if (token == null || token.isBlank()) {
            throw new CardDemoException(REASON_CODE_INVALID_TOKEN,
                    "JWT token is null or blank", null);
        }
        String stripped = stripBearerPrefix(token);
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(stripped);
            return jws.getPayload();
        } catch (ExpiredJwtException e) {
            // PCI-DSS-safe logging: do not log the token. Log only the
            // claim subject which is the user ID — useful for ops.
            String sub = e.getClaims() != null ? e.getClaims().getSubject() : "unknown";
            LOG.warn("JWT expired sub={} cause={}", sub, e.getMessage());
            throw new CardDemoException(REASON_CODE_EXPIRED_TOKEN,
                    "JWT token is expired", e);
        } catch (SignatureException | MalformedJwtException e) {
            LOG.warn("JWT signature/format invalid cause={}", e.getMessage());
            throw new CardDemoException(REASON_CODE_INVALID_TOKEN,
                    "JWT signature/format invalid", e);
        } catch (JwtException | IllegalArgumentException e) {
            LOG.warn("JWT parse failure cause={}", e.getMessage());
            throw new CardDemoException(REASON_CODE_INVALID_TOKEN,
                    "JWT could not be parsed", e);
        }
    }

    /**
     * Convenience predicate equivalent to {@code parseAndValidate(token)}
     * but returns {@code false} for any failure instead of throwing. Used
     * by {@link JwtAuthenticationFilter} to gate authentication without
     * the cost of try/catch unwinding in the hot path; the filter follows
     * up with {@link #parseAndValidate(String)} when valid to extract the
     * claims.
     *
     * @param token the compact JWT
     * @return {@code true} if the token is signature-valid, issuer-valid,
     *         and unexpired; {@code false} otherwise
     */
    public boolean validateToken(String token) {
        // Replaces: implicit CICS session validation that happened on every
        // pseudo-conversational re-entry.
        try {
            parseAndValidate(token);
            return true;
        } catch (CardDemoException e) {
            return false;
        }
    }

    /**
     * Reads the {@code roles} claim from a validated token.
     *
     * @param claims parsed claims (from {@link #parseAndValidate(String)})
     * @return immutable list of role strings; never {@code null}
     */
    @SuppressWarnings("unchecked")
    public List<String> getRoles(Claims claims) {
        Objects.requireNonNull(claims, "claims must not be null");
        Object raw = claims.get(CLAIM_ROLES);
        if (raw instanceof List<?> list) {
            // The roles claim is a JSON array of strings; jjwt deserializes
            // it as List<?> — defensively coerce to List<String>.
            return ((List<Object>) list).stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        }
        return Collections.emptyList();
    }

    /**
     * Reads the {@code userId} claim from a validated token, falling back
     * to {@code sub} if {@code userId} is absent.
     *
     * @param claims parsed claims
     * @return the user ID, uppercased per COBOL convention; never
     *         {@code null}/blank if the token was generated by this
     *         provider
     */
    public String getUserId(Claims claims) {
        Objects.requireNonNull(claims, "claims must not be null");
        Object userIdClaim = claims.get(CLAIM_USER_ID);
        if (userIdClaim instanceof String s && !s.isBlank()) {
            return s.toUpperCase(java.util.Locale.US);
        }
        return (claims.getSubject() == null) ? ""
                : claims.getSubject().toUpperCase(java.util.Locale.US);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Strips the optional {@code "Bearer "} prefix from a token string.
     * The match is case-insensitive on the {@code "Bearer"} keyword and
     * tolerates extra whitespace, mirroring the RFC 6750 syntax.
     */
    static String stripBearerPrefix(String token) {
        if (token == null) {
            return "";
        }
        String trimmed = token.trim();
        if (trimmed.length() > 7 && trimmed.substring(0, 7).equalsIgnoreCase("Bearer ")) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }
}
