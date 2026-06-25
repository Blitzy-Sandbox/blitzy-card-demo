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
package com.carddemo.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

/**
 * Stateless helper for issuing, validating, and parsing the signed JSON Web
 * Tokens (JWTs) that carry an authenticated user's identity and authority
 * between requests.
 *
 * <p>Each token records the user identifier as its subject and the
 * single-character user-type code ({@code 'A'} administrator, {@code 'U'}
 * standard user) as the {@value #CLAIM_USER_TYPE} claim. Together these two
 * values are the only cross-request state; no server-side session is retained.</p>
 *
 * <p>The HMAC-SHA shared secret, token lifetime, and issuer are supplied through
 * the {@code carddemo.security.jwt.*} configuration properties and injected at
 * construction. The derived {@link SecretKey} is built once and reused, so an
 * instance is immutable after construction and therefore safe to share across
 * threads.</p>
 */
@Service
public class JwtTokenService {

    /**
     * Name of the custom claim that carries the single-character user-type code
     * ({@code 'A'} administrator, {@code 'U'} standard user).
     */
    public static final String CLAIM_USER_TYPE = "userType";

    private final SecretKey signingKey;
    private final long expirationMs;
    private final String issuer;

    /**
     * Creates the token service from externalized signing configuration.
     *
     * @param secret       the HMAC-SHA shared secret; it must be at least
     *                     32 bytes (256 bits) so that it is strong enough for
     *                     HS256, otherwise key derivation fails fast at startup
     * @param expirationMs the token time-to-live, in milliseconds, measured from
     *                     the instant of issuance
     * @param issuer       the {@code iss} value stamped into every issued token
     *                     and required of every parsed token
     */
    public JwtTokenService(
            @Value("${carddemo.security.jwt.secret}") String secret,
            @Value("${carddemo.security.jwt.expiration-ms}") long expirationMs,
            @Value("${carddemo.security.jwt.issuer}") String issuer) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
        this.issuer = issuer;
    }

    /**
     * Issues a signed JWT for an authenticated user.
     *
     * @param userId   the authenticated user identifier, stamped as the token
     *                 subject
     * @param userType the single-character user-type code, stamped as the
     *                 {@value #CLAIM_USER_TYPE} claim
     * @return the compact, URL-safe, signed JWT string
     */
    public String generateToken(String userId, String userType) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .issuer(issuer)
                .claim(CLAIM_USER_TYPE, userType)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Reports whether a token is well-formed, correctly signed, issued by the
     * configured issuer, and unexpired. This method never throws and never logs
     * the supplied token.
     *
     * @param token the compact JWT string to inspect; may be {@code null} or blank
     * @return {@code true} if the token passes every check, {@code false} otherwise
     */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * Extracts the subject (user identifier) from a token.
     *
     * @param token the compact JWT string
     * @return the token subject
     * @throws JwtException             if the token is invalid, tampered, expired,
     *                                  or issued by an unexpected issuer
     * @throws IllegalArgumentException if the token is {@code null} or blank
     */
    public String getUserId(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * Extracts the user-type code from a token.
     *
     * @param token the compact JWT string
     * @return the {@value #CLAIM_USER_TYPE} claim value
     * @throws JwtException             if the token is invalid, tampered, expired,
     *                                  or issued by an unexpected issuer
     * @throws IllegalArgumentException if the token is {@code null} or blank
     */
    public String getUserType(String token) {
        return parseClaims(token).get(CLAIM_USER_TYPE, String.class);
    }

    /**
     * Returns the configured token lifetime, in milliseconds.
     *
     * @return the token time-to-live in milliseconds
     */
    public long getExpirationMs() {
        return expirationMs;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
