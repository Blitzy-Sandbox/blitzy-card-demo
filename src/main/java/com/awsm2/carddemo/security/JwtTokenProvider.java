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
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;

/**
 * Issues and validates HS256-signed JWT bearer tokens for the CardDemo REST API.
 *
 * <p>This component replaces the CICS pseudo-conversational identity propagation
 * model in which the {@code CARDDEMO-COMMAREA} structure (see
 * {@code app/cpy/COCOM01Y.cpy}) carried the authenticated {@code CDEMO-USER-ID}
 * and {@code CDEMO-USER-TYPE} between transactions. After successful
 * authentication in {@code SignonService} (which itself replaces
 * {@code app/cbl/COSGN00C.cbl}), this provider issues a signed JWT whose claims
 * set mirrors the identity portion of the COMMAREA:</p>
 *
 * <ul>
 *   <li>{@code sub} (subject) = {@code SEC-USR-ID} (8-character user identifier
 *       from {@code app/cpy/CSUSR01Y.cpy})</li>
 *   <li>{@code userType} = {@code SEC-USR-TYPE} (single character: {@code 'A'}
 *       for admin, {@code 'U'} for user)</li>
 *   <li>{@code firstName} = {@code SEC-USR-FNAME} (display only; up to 20
 *       chars)</li>
 *   <li>{@code lastName} = {@code SEC-USR-LNAME} (display only; up to 20
 *       chars)</li>
 *   <li>{@code iss} (issuer) — fixed {@value #ISSUER} so multi-tenant
 *       deployments can discriminate</li>
 *   <li>{@code iat} (issued-at) and {@code exp} (expiration) standard claims
 *       per RFC 7519</li>
 * </ul>
 *
 * <h2>Key management (AAP &sect;0.6.4, &sect;0.7.1)</h2>
 * <p>The HMAC-SHA-256 signing key is fetched at startup from AWS Secrets
 * Manager via {@link SecretsManagerService#getSecretJsonField(String, String)}.
 * The key value is never hardcoded, never persisted to {@code application.yml},
 * and never logged. Only the Secrets Manager ARN is referenced in configuration
 * &mdash; the ARN itself is non-sensitive (it is a public AWS resource
 * identifier; the secret value behind the ARN is what is sensitive).</p>
 *
 * <p>The bean is annotated {@link RefreshScope &#64;RefreshScope} so that a
 * Spring Cloud {@code RefreshEvent} (triggered by Secrets Manager key rotation
 * per AAP &sect;0.6.4) destroys and re-instantiates this bean, picking up the
 * rotated key on next bean access without restarting the application. Existing
 * JWTs signed with the previous key will fail validation gracefully after
 * rotation &mdash; clients re-authenticate via {@code POST /api/auth/signin}.</p>
 *
 * <h2>Algorithm selection (RFC 7518 &sect;3.2)</h2>
 * <p>HS256 (HMAC-SHA-256) is the chosen JWT signature algorithm. RFC 7518
 * &sect;3.2 mandates that HS256 keys be at least 256 bits (32 bytes) long.
 * This provider <strong>fails fast at bean-init time</strong> if the
 * configured signing key material is shorter than 32 bytes, preventing a
 * weak-key misconfiguration from reaching production.</p>
 *
 * <h2>Security hygiene (AAP &sect;0.6.6, &sect;0.7.1)</h2>
 * <ul>
 *   <li>Signing key bytes are NEVER logged.</li>
 *   <li>Issued or received tokens are NEVER logged at INFO &mdash; DEBUG only,
 *       for development tracing.</li>
 *   <li>Passwords are NEVER seen by this provider &mdash; password verification
 *       is performed exclusively by
 *       {@code BCryptPasswordEncoderBean#passwordEncoder().matches(...)} in
 *       {@code SignonService}.</li>
 *   <li>Only the ARN <em>suffix</em> (last 12 characters) is logged at INFO
 *       during startup, to confirm "key loaded from the intended secret"
 *       without exposing the full path.</li>
 * </ul>
 *
 * <h2>Adapter isolation (AAP &sect;0.7.1)</h2>
 * <p>This class NEVER inlines the AWS SDK {@code SecretsManagerClient}.
 * All Secrets Manager access is delegated to {@link SecretsManagerService},
 * the dedicated adapter for AWS Secrets Manager per the AAP rule
 * "isolate all AWS service integrations in dedicated adapter classes &mdash;
 * never inline AWS SDK calls in business logic".</p>
 *
 * <h2>Thread safety</h2>
 * <p>This class is thread-safe after {@link #initSigningKey()} completes.
 * The {@link SecretKey} field is assigned once during {@code @PostConstruct}
 * and never mutated thereafter; the JJWT 0.12.x builder and parser are
 * thread-safe per their library contract; SLF4J loggers are thread-safe by
 * the SLF4J specification.</p>
 *
 * <p>Replaces: {@code COCOM01Y.cpy CARDDEMO-COMMAREA} identity propagation,
 * established by {@code COSGN00C.cbl} {@code READ-USER-SEC-FILE} (L209-L246)
 * on successful signon.</p>
 *
 * @see SecretsManagerService
 * @see JwtAuthenticationFilter
 * @see com.awsm2.carddemo.config.SecurityConfig
 */
@Component
@RefreshScope
public class JwtTokenProvider {

    /**
     * SLF4J logger used for structured operational events. Per AAP
     * &sect;0.6.6 (PCI-DSS logging discipline), this logger MUST NOT emit
     * signing key bytes, raw JWT bytes, or full Secrets Manager ARNs at
     * any level. Token-issuance traces are confined to DEBUG; startup
     * confirmation is at INFO using only the ARN suffix via
     * {@link #summarizeArn(String)}.
     */
    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    /**
     * JWT claim name carrying the COBOL {@code SEC-USR-TYPE} value ({@code 'A'}
     * for admin or {@code 'U'} for user). Single character per the original
     * {@code app/cpy/CSUSR01Y.cpy} {@code PIC X(01)} declaration.
     */
    private static final String USER_TYPE_CLAIM = "userType";

    /**
     * JWT claim name carrying the COBOL {@code SEC-USR-FNAME} value (up to 20
     * characters per the original {@code app/cpy/CSUSR01Y.cpy}
     * {@code PIC X(20)} declaration). Display-only; never used for
     * authorisation decisions.
     */
    private static final String FIRST_NAME_CLAIM = "firstName";

    /**
     * JWT claim name carrying the COBOL {@code SEC-USR-LNAME} value (up to 20
     * characters per the original {@code app/cpy/CSUSR01Y.cpy}
     * {@code PIC X(20)} declaration). Display-only; never used for
     * authorisation decisions.
     */
    private static final String LAST_NAME_CLAIM = "lastName";

    /**
     * Standard JWT {@code iss} claim value embedded in every issued token and
     * required to match on every validated token. Multi-tenant deployments can
     * differentiate by overriding this constant via subclassing (out of scope
     * for the current AAP &mdash; one application, one issuer).
     */
    private static final String ISSUER = "carddemo";

    /**
     * Default JSON field name within the Secrets Manager secret payload that
     * contains the HS256 signing key material. The field name is overridable
     * via {@code carddemo.security.jwt.signing-key-secret-field}; default
     * matches the convention used by the rotation Lambda's secret template.
     */
    private static final String DEFAULT_SIGNING_KEY_FIELD = "jwtSigningKey";

    /**
     * HS256 minimum signing-key length in bytes per RFC 7518 &sect;3.2
     * (256 bits / 8 bits per byte = 32 bytes). Enforced at startup by
     * {@link #initSigningKey()} &mdash; a shorter key throws
     * {@link IllegalStateException} and fails Spring Boot context refresh.
     */
    private static final int MIN_HS256_KEY_BYTES = 32;

    /**
     * AWS Secrets Manager ARN that holds the JSON-encoded signing-key
     * payload. The ARN itself is not a secret; the value behind it is.
     * Sourced from {@code carddemo.security.jwt.signing-key-secret-arn}
     * which in dev/prod is supplied via AWS Systems Manager Parameter
     * Store or an environment variable on the ECS task definition. No
     * default &mdash; missing ARN fails Spring Boot startup, which is the
     * intended behaviour per AAP &sect;0.7.1 "fail fast on missing
     * credentials configuration".
     */
    private final String signingKeySecretArn;

    /**
     * The JSON field name within the secret payload that holds the actual
     * key material. Sourced from
     * {@code carddemo.security.jwt.signing-key-secret-field}; defaults to
     * {@value #DEFAULT_SIGNING_KEY_FIELD}. The field name is non-sensitive
     * and may be logged.
     */
    private final String signingKeySecretField;

    /**
     * Configured JWT token time-to-live. Sourced from
     * {@code carddemo.security.jwt.expiration}; defaults to {@code PT30M}
     * (30 minutes) per AAP guidance "expiry tuned for security". ISO-8601
     * duration format (e.g., {@code PT30M}, {@code PT1H}, {@code PT0S} for
     * test-only zero TTL).
     */
    private final Duration expiration;

    /**
     * Adapter for AWS Secrets Manager. The sole route to Secrets Manager
     * from this class &mdash; per AAP &sect;0.7.1, the AWS SDK
     * {@code SecretsManagerClient} is never referenced directly here.
     */
    private final SecretsManagerService secretsManagerService;

    /**
     * The HMAC-SHA-256 signing key derived from the Secrets Manager-fetched
     * key material at {@code @PostConstruct} time. Volatile is unnecessary
     * because the field is published via the Spring bean container's
     * happens-before guarantee and never mutated after init.
     *
     * <p>This key is treated as a secret and is NEVER logged, returned, or
     * exposed through any public accessor on this class.</p>
     */
    private SecretKey signingKey;

    /**
     * Constructor injection of the Secrets Manager adapter and the three
     * configuration properties that control signing-key resolution and
     * token lifetime. Constructor injection is mandated by AAP &sect;0.3.3
     * (Dependency Injection &mdash; constructor injection for all
     * {@code @Service}, {@code @Repository}, {@code @Component}, adapter,
     * and config beans).
     *
     * @param secretsManagerService the AWS Secrets Manager adapter used at
     *                              {@link #initSigningKey()} time and on
     *                              every {@code @RefreshScope} re-creation
     *                              following a key rotation event; must
     *                              not be {@code null}
     * @param signingKeySecretArn   the AWS Secrets Manager ARN that holds
     *                              the JSON-encoded signing-key payload;
     *                              required, no default, fails startup if
     *                              missing
     * @param signingKeySecretField the JSON field name within the secret
     *                              payload that contains the actual key
     *                              material; defaults to
     *                              {@value #DEFAULT_SIGNING_KEY_FIELD}
     * @param expiration            ISO-8601 token TTL; defaults to
     *                              {@code PT30M} (30 minutes)
     */
    public JwtTokenProvider(
            SecretsManagerService secretsManagerService,
            @Value("${carddemo.security.jwt.signing-key-secret-arn}") String signingKeySecretArn,
            @Value("${carddemo.security.jwt.signing-key-secret-field:jwtSigningKey}") String signingKeySecretField,
            @Value("${carddemo.security.jwt.expiration:PT30M}") Duration expiration) {
        // Replaces: CICS COMMAREA-propagated user identity (COCOM01Y.cpy) +
        // RACF identity propagation. Constructor capture only — the actual
        // Secrets Manager fetch is deferred to @PostConstruct so the bean
        // factory can complete construction and dependency injection before
        // any I/O occurs.
        this.secretsManagerService = Objects.requireNonNull(secretsManagerService, "secretsManagerService");
        this.signingKeySecretArn = Objects.requireNonNull(signingKeySecretArn, "signingKeySecretArn");
        this.signingKeySecretField = Objects.requireNonNull(signingKeySecretField, "signingKeySecretField");
        this.expiration = Objects.requireNonNull(expiration, "expiration");
    }

    /**
     * Fetches the HS256 signing key material from AWS Secrets Manager and
     * derives the {@link SecretKey} used by JJWT for signing and
     * verification. Invoked once during bean initialisation and again each
     * time {@link RefreshScope &#64;RefreshScope} re-creates the bean on
     * Secrets Manager rotation (AAP &sect;0.6.4).
     *
     * <p>Validation gates:</p>
     * <ol>
     *   <li>The Secrets Manager call MUST resolve the configured field; an
     *       empty {@link Optional} or blank value fails startup.</li>
     *   <li>The key material MUST decode to at least 32 bytes
     *       ({@value #MIN_HS256_KEY_BYTES}) of UTF-8 to satisfy
     *       RFC 7518 &sect;3.2 for HS256.</li>
     * </ol>
     *
     * <p>Replaces: hardcoded plaintext {@code SEC-USR-PWD} comparison in
     * {@code COSGN00C.cbl} L223 ({@code IF SEC-USR-PWD = WS-USER-PWD}).
     * JWT signing material is now centrally rotated by AWS Secrets Manager
     * and never stored alongside user records.</p>
     *
     * @throws IllegalStateException if the configured secret does not yield
     *                               a key that meets the HS256 length
     *                               requirement
     */
    @PostConstruct
    void initSigningKey() {
        // Replaces: hardcoded plaintext SEC-USR-PWD comparison in
        // COSGN00C.cbl L223 (IF SEC-USR-PWD = WS-USER-PWD). JWT signing
        // material is centrally rotated via AWS Secrets Manager and is
        // never persisted alongside user records.
        if (signingKeySecretArn.isBlank()) {
            throw new IllegalStateException(
                    "carddemo.security.jwt.signing-key-secret-arn must be configured (AWS Secrets Manager ARN)");
        }
        // SecretsManagerService.getSecretJsonField returns Optional<String>
        // — Optional.empty() when the JSON field is absent or explicitly
        // null; Optional.of("") when the field is present but blank. Both
        // conditions are treated as misconfiguration and fail startup.
        Optional<String> keyMaterialOpt =
                secretsManagerService.getSecretJsonField(signingKeySecretArn, signingKeySecretField);
        if (keyMaterialOpt.isEmpty() || keyMaterialOpt.get().isBlank()) {
            throw new IllegalStateException(
                    "JWT signing key not found in Secrets Manager (ARN suffix=" + summarizeArn(signingKeySecretArn)
                            + ", field=" + signingKeySecretField + ")");
        }
        String keyMaterial = keyMaterialOpt.get();
        byte[] keyBytes = keyMaterial.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_HS256_KEY_BYTES) {
            // PCI-DSS-safe error message: byte count only, never the key
            // bytes themselves. The byte count alone gives operators
            // enough information to diagnose a misconfiguration without
            // leaking key material in logs or stack traces.
            throw new IllegalStateException(
                    "JWT signing key is too short for HS256 (need at least " + MIN_HS256_KEY_BYTES
                            + " bytes / 256 bits); got " + keyBytes.length
                            + " bytes from Secrets Manager (ARN suffix=" + summarizeArn(signingKeySecretArn) + ")");
        }
        // Keys.hmacShaKeyFor selects HS256/HS384/HS512 based on the input
        // key size: 32-47 bytes → HS256, 48-63 → HS384, 64+ → HS512. We
        // explicitly require >= 32 bytes above and then sign exclusively
        // with Jwts.SIG.HS256 in issueToken() — the resulting JWA header
        // will always be {"alg":"HS256","typ":"JWT"}.
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        // Operational confirmation: ARN suffix only, never the key bytes.
        // INFO level is acceptable here because (a) the message fires at
        // most twice per application lifetime (initial start + each
        // rotation) and (b) the truncated ARN is not sensitive while the
        // confirmation it carries is valuable for ops correlation.
        log.info("JwtTokenProvider initialized with HS256 signing key from Secrets Manager (ARN suffix={}, ttl={})",
                summarizeArn(signingKeySecretArn), expiration);
    }

    // ---------------------------------------------------------------------
    // Public API — token issuance
    // ---------------------------------------------------------------------

    /**
     * Issues a signed HS256 JWT carrying the supplied identity claims.
     *
     * <p>The token's claim set mirrors the COBOL {@code CARDDEMO-COMMAREA}
     * identity propagation pattern (COCOM01Y.cpy) but is cryptographically
     * signed rather than carried in CICS-managed memory:</p>
     *
     * <ul>
     *   <li>{@code sub} = {@code userId} (verbatim, no normalisation
     *       performed here &mdash; the caller is responsible for upper-
     *       casing to match the COBOL convention; see
     *       {@code SignonService})</li>
     *   <li>{@code iss} = {@value #ISSUER}</li>
     *   <li>{@code iat} = now (UTC)</li>
     *   <li>{@code exp} = now + configured {@link #expiration}</li>
     *   <li>{@code userType} = {@code userType}</li>
     *   <li>{@code firstName} = {@code firstName} or {@code ""} if
     *       {@code null}</li>
     *   <li>{@code lastName} = {@code lastName} or {@code ""} if
     *       {@code null}</li>
     * </ul>
     *
     * @param userId    the 8-character user identifier (COBOL
     *                  {@code SEC-USR-ID}); must not be {@code null}
     * @param userType  single-character user type (COBOL
     *                  {@code SEC-USR-TYPE}: {@code 'A'} admin /
     *                  {@code 'U'} user); must not be {@code null}
     * @param firstName the user's first name (COBOL {@code SEC-USR-FNAME},
     *                  up to 20 chars); may be {@code null} or blank
     * @param lastName  the user's last name (COBOL {@code SEC-USR-LNAME},
     *                  up to 20 chars); may be {@code null} or blank
     * @return the compact JWT bearer token string ready for the
     *         {@code Authorization: Bearer ...} header
     * @throws NullPointerException if {@code userId} or {@code userType} is
     *                              {@code null}
     */
    public String issueToken(String userId, String userType, String firstName, String lastName) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(userType, "userType");
        // COBOL: COSGN00C.cbl L222-L240 — on successful READ USRSEC + password
        //        match, populate CDEMO-USER-ID, CDEMO-USER-TYPE in
        //        CARDDEMO-COMMAREA, then XCTL.
        //        Java equivalent: build a signed JWT carrying those identity
        //        claims. The CICS-managed COMMAREA persistence is replaced
        //        by the cryptographically signed bearer token returned here.
        Instant now = Instant.now();
        Instant exp = now.plus(expiration);
        String fname = (firstName == null) ? "" : firstName;
        String lname = (lastName == null) ? "" : lastName;
        // JJWT 0.12.x builder API: fluent setters use the unprefixed
        // standard claim names (issuer, subject, issuedAt, expiration);
        // custom claims use .claim(name, value). The signing key + algorithm
        // are supplied together via signWith(key, alg) so JJWT can validate
        // the key/alg combination at compact() time.
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim(USER_TYPE_CLAIM, userType)
                .claim(FIRST_NAME_CLAIM, fname)
                .claim(LAST_NAME_CLAIM, lname)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        // PCI-DSS-safe logging: userId + userType are not sensitive on their
        // own (they appear in audit trails per AAP §0.6.6); the token itself
        // is NEVER logged. The ttl is logged for ops correlation.
        log.debug("Issued JWT for user {} (type={}, ttl={})", userId, userType, expiration);
        return token;
    }

    // ---------------------------------------------------------------------
    // Public API — token validation
    // ---------------------------------------------------------------------

    /**
     * Parses, verifies the signature, and validates the standard claims of
     * a JWT bearer token. Returns the parsed {@link Claims} payload on
     * success. Callers obtain the identity by reading
     * {@link Claims#getSubject()} for the user ID and
     * {@link Claims#get(String, Class)} with claim names exposed via the
     * constants on this class.
     *
     * <p>This method throws on any validation failure rather than returning
     * a status &mdash; the throw-on-fail contract matches the JJWT 0.12.x
     * API and lets {@link JwtAuthenticationFilter} log the specific
     * failure type (expired / malformed / signature mismatch).</p>
     *
     * <p>Validation gates per JJWT 0.12.x semantics:</p>
     * <ol>
     *   <li><strong>Signature</strong> &mdash;
     *       {@code Jwts.parser().verifyWith(signingKey)} ensures the HMAC
     *       signature matches the configured key. A mismatched key
     *       (e.g., a stale token after rotation) yields
     *       {@code io.jsonwebtoken.security.SignatureException}.</li>
     *   <li><strong>Issuer</strong> &mdash; {@code .requireIssuer(ISSUER)}
     *       enforces {@code iss = "carddemo"}.</li>
     *   <li><strong>Expiration</strong> &mdash; JJWT automatically rejects
     *       tokens whose {@code exp} is in the past with
     *       {@link io.jsonwebtoken.ExpiredJwtException}.</li>
     *   <li><strong>Format</strong> &mdash; malformed compact strings
     *       (wrong number of dot-separated segments, unparseable JSON,
     *       etc.) yield {@link io.jsonwebtoken.MalformedJwtException}.</li>
     * </ol>
     *
     * @param token the compact JWT bearer string (without the
     *              {@code "Bearer "} prefix &mdash; the filter is
     *              responsible for prefix removal); must not be
     *              {@code null}
     * @return the parsed {@link Claims} payload (never {@code null} on
     *         successful return)
     * @throws JwtException             on any signature / format /
     *                                  issuer / expiration validation
     *                                  failure (this is the JJWT base
     *                                  type for all token errors)
     * @throws IllegalArgumentException if {@code token} is empty or
     *                                  consists only of whitespace (JJWT
     *                                  rejects with this type before
     *                                  reaching the parser internals)
     * @throws NullPointerException     if {@code token} is {@code null}
     */
    public Claims validateToken(String token) {
        Objects.requireNonNull(token, "token");
        // JJWT 0.12.x parser API:
        //   .verifyWith(SecretKey)     — supplies the HMAC verification key
        //   .requireIssuer(String)     — enforces iss claim equality
        //   .build()                   — finalises the parser
        //   .parseSignedClaims(token)  — parses, verifies signature, and
        //                                 returns Jws<Claims>
        //   .getPayload()              — extracts the Claims payload
        // ExpiredJwtException is raised by parseSignedClaims when exp < now.
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(ISSUER)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Convenience predicate equivalent to invoking
     * {@link #validateToken(String)} and returning {@code false} on any
     * failure. Intended for non-filter callers (tests, ad-hoc validity
     * checks); production callers should prefer {@link #validateToken}
     * directly so they can distinguish between expired and malformed
     * tokens.
     *
     * @param token the compact JWT bearer string
     * @return {@code true} if {@code token} is a valid, signed,
     *         unexpired CardDemo-issued JWT; {@code false} for any
     *         validation failure or null input
     */
    public boolean isValid(String token) {
        if (token == null) {
            return false;
        }
        try {
            validateToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            // Don't log here — isValid() is invoked from hot paths where
            // log lines per invalid token can saturate CloudWatch. Callers
            // that want detail invoke validateToken() and catch directly.
            return false;
        }
    }

    /**
     * Returns the configured token time-to-live. Consumed by
     * {@code SignonService} when populating {@code SignonResponseDto.expiresAt}
     * so the client knows when to refresh proactively.
     *
     * @return the configured ISO-8601 {@link Duration}; never {@code null}
     */
    public Duration getExpiration() {
        return expiration;
    }

    // ---------------------------------------------------------------------
    // Helpers — package-private/private utilities
    // ---------------------------------------------------------------------

    /**
     * Truncates a Secrets Manager ARN to its last 12 characters for safe
     * logging. ARNs follow the form
     * {@code arn:aws:secretsmanager:<region>:<acct>:secret:<name>-<suffix>}
     * &mdash; the trailing random suffix is sufficient to disambiguate
     * different secrets in logs without revealing the secret name, account
     * ID, or region.
     *
     * @param arn the full ARN, or {@code null}
     * @return the last 12 characters prefixed by {@code "..."}, or the
     *         full ARN if it is 12 characters or fewer, or the literal
     *         {@code "(null)"} if the input is {@code null}
     */
    private static String summarizeArn(String arn) {
        // Defensive: arn should never be null at this point (constructor
        // requireNonNull and @Value would have failed startup), but the
        // helper is also invoked in error-message construction paths
        // where a defensive default avoids cascading NPEs.
        if (arn == null) {
            return "(null)";
        }
        return arn.length() <= 12 ? arn : "..." + arn.substring(arn.length() - 12);
    }
}
