package com.cardemo.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.cardemo.model.enums.UserType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stateless authentication-token service &mdash; the single, dedicated home for
 * the CardDemo migration's <strong>COMMAREA&nbsp;&rarr;&nbsp;stateless token</strong>
 * substitution (AAP &sect;0.1.2).
 *
 * <h2>Why this class exists</h2>
 * <p>The legacy CICS pseudo-conversational model carried per-session state in the
 * {@code CARDDEMO-COMMAREA} via {@code EXEC CICS RETURN TRANSID COMMAREA}. The
 * Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x target is stateless, so that carry-over
 * is replaced by a self-contained, JDK-only HMAC-SHA256-signed compact token. The
 * token-issuance logic previously lived inline in
 * {@code com.cardemo.service.auth.AuthenticationService}; it is extracted here so
 * that <em>both</em> token <strong>issuance</strong> (sign-on) and token
 * <strong>validation</strong> (every protected request, via
 * {@link TokenAuthenticationFilter}) share one implementation and one configured
 * secret. Isolating this cross-cutting security primitive in its own module
 * follows the Minimal Change Clause directive to "isolate new implementations in
 * dedicated files/modules" (AAP &sect;0.7.1).</p>
 *
 * <h2>Token format (unchanged from the prior inline implementation)</h2>
 * <p>The token is the standard compact triple
 * {@code base64url(header) + "." + base64url(payload) + "." +
 * base64url(HMAC-SHA256(header + "." + payload))}, with a fixed
 * {@code {"alg":"HS256","typ":"JWT"}} header and the minimal claim set
 * {@code sub} (signed-in user id), {@code typ} (the {@link UserType} code
 * {@code 'A'}/{@code 'U'}), {@code iat} (issued-at epoch seconds) and {@code exp}
 * (expiry = {@code iat + ttl}). No JWT library is introduced; the JSON is built
 * by hand to stay JDK-only, exactly as before, so the issued token bytes are
 * byte-identical to the prior implementation (behavioral parity, AAP
 * &sect;0.7.1).</p>
 *
 * <h2>Secret policy and fail-fast validation (AAP &sect;0.7.2)</h2>
 * <p>The HMAC signing secret is injected from configuration
 * ({@code carddemo.security.token.secret}) and is <strong>never</strong>
 * hardcoded. It is validated <em>once, at construction</em>: a {@code null},
 * blank, or under-length secret fails application startup immediately with a
 * safe {@link IllegalStateException} (the secret value itself is never echoed in
 * the message or logs). HS256 requires a key of at least the hash output size, so
 * the minimum accepted length is {@value #MIN_SECRET_LENGTH_BYTES} UTF-8 bytes
 * (256&nbsp;bits). This closes the prior gap where an empty or weak operator-
 * supplied secret would still sign tokens.</p>
 *
 * <h2>Validation semantics</h2>
 * <p>{@link #parse(String)} recomputes the signature over the received
 * {@code header.payload} and compares it to the presented signature using a
 * <em>constant-time</em> comparison ({@link MessageDigest#isEqual(byte[], byte[])})
 * to avoid timing side-channels, then rejects expired tokens. Any malformed,
 * tampered, or expired token yields {@link Optional#empty()} rather than an
 * exception, so callers (the authentication filter) can treat "no valid token"
 * uniformly without exception-driven control flow.</p>
 *
 * <p><strong>Traceability:</strong> derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference and is
 * never copied into this repository.</p>
 *
 * @see com.cardemo.service.auth.AuthenticationService
 * @see TokenAuthenticationFilter
 */
@Service
public class TokenService {

    /**
     * Non-PII logger. It records only configuration outcomes and coarse token
     * validation failures; it <strong>never</strong> logs the secret, a raw
     * token, or any token claim value.
     */
    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    /**
     * Minimum accepted signing-secret length in UTF-8 bytes. HS256 (HMAC with
     * SHA-256) uses a 256-bit (32-byte) output, and RFC&nbsp;7518 &sect;3.2
     * requires an HMAC key no smaller than the hash output, so a shorter secret
     * is rejected at startup.
     */
    static final int MIN_SECRET_LENGTH_BYTES = 32;

    /** JDK-guaranteed MAC algorithm used to sign and verify the compact token. */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Fixed compact-token header: {@code {"alg":"HS256","typ":"JWT"}}. */
    private static final String TOKEN_HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    /**
     * Shared, thread-safe JSON reader used only to parse the decoded token
     * payload during validation. {@link ObjectMapper} is thread-safe once
     * configured and is configured here with defaults, so a single shared
     * instance is safe and avoids per-call allocation. Token <em>issuance</em>
     * deliberately does not use it (the payload is hand-built to stay
     * byte-identical to the prior implementation).
     */
    private static final ObjectMapper PAYLOAD_READER = new ObjectMapper();

    /**
     * The HMAC signing-secret bytes (UTF-8). Stored as a defensive copy so the
     * caller cannot mutate the key material after construction.
     */
    private final byte[] secretBytes;

    /** Token lifetime in seconds (non-secret; defaults to {@code 3600} when unset). */
    private final long tokenTtlSeconds;

    /**
     * Constructs the token service, binding and <strong>validating</strong> the
     * signing secret and lifetime from configuration.
     *
     * <p>The secret is injected via {@link Value @Value} from
     * {@code carddemo.security.token.secret} and has <strong>no</strong> default,
     * so it must be supplied externally (env var / vault) per AAP &sect;0.7.2.
     * It is validated immediately: a {@code null}, blank, or shorter-than-
     * {@value #MIN_SECRET_LENGTH_BYTES}-byte secret throws
     * {@link IllegalStateException}, failing startup with a safe message that
     * never reveals the configured value. The non-secret TTL falls back to one
     * hour and must be positive.</p>
     *
     * @param tokenSecret     HMAC signing secret bound from
     *                        {@code carddemo.security.token.secret}
     * @param tokenTtlSeconds token lifetime bound from
     *                        {@code carddemo.security.token.ttl-seconds}
     *                        (default {@code 3600})
     * @throws IllegalStateException if the secret is null/blank/too short or the
     *                               TTL is not positive (fail-fast at startup)
     */
    public TokenService(
            @Value("${carddemo.security.token.secret}") String tokenSecret,
            @Value("${carddemo.security.token.ttl-seconds:3600}") long tokenTtlSeconds) {
        // -----------------------------------------------------------------
        // Fail-fast secret validation (AAP §0.7.2). Reject a missing/blank or
        // weak secret BEFORE any token is ever signed. The secret value is never
        // included in the exception message — only its inadequacy is reported.
        // -----------------------------------------------------------------
        if (tokenSecret == null || tokenSecret.isBlank()) {
            throw new IllegalStateException(
                    "carddemo.security.token.secret must be configured with a non-blank value "
                    + "(supply CARDDEMO_SECURITY_TOKEN_SECRET via environment or vault).");
        }
        final byte[] bytes = tokenSecret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "carddemo.security.token.secret is too weak: HS256 requires at least "
                    + MIN_SECRET_LENGTH_BYTES + " bytes (256 bits); configure a longer random secret.");
        }
        if (tokenTtlSeconds <= 0) {
            throw new IllegalStateException(
                    "carddemo.security.token.ttl-seconds must be a positive number of seconds.");
        }
        // Defensive copy: the service owns its key material.
        this.secretBytes = bytes.clone();
        this.tokenTtlSeconds = tokenTtlSeconds;
        log.info("TokenService initialized (HS256, ttl={}s); signing secret validated.", tokenTtlSeconds);
    }

    /**
     * Issues a self-contained, JDK-only HMAC-SHA256-signed compact token &mdash;
     * the stateless replacement for the CICS pseudo-conversational
     * {@code CARDDEMO-COMMAREA} carry-over (AAP &sect;0.1.2).
     *
     * <p>The output is byte-identical to the prior inline implementation in
     * {@code AuthenticationService}: same header, same minimal claim set
     * ({@code sub}, {@code typ}, {@code iat}, {@code exp}), same base64url
     * encoding. No JWT library is used.</p>
     *
     * @param userId the signed-in user id placed in the {@code sub} claim
     * @param type   the signed-in user's role placed in the {@code typ} claim
     * @return the signed compact token
     */
    public String issue(String userId, UserType type) {
        final long issuedAt = Instant.now().getEpochSecond();   // iat
        final long expiresAt = issuedAt + tokenTtlSeconds;      // exp = iat + TTL

        // Minimal claims, JSON built by hand to stay JDK-only (no JWT dependency).
        // userId is JSON-escaped defensively; typ is the 1-char UserType code.
        final String payloadJson = "{\"sub\":\"" + jsonEscape(userId) + "\""
                + ",\"typ\":\"" + type.getCode() + "\""
                + ",\"iat\":" + issuedAt
                + ",\"exp\":" + expiresAt + "}";

        final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        final String encodedHeader =
                encoder.encodeToString(TOKEN_HEADER_JSON.getBytes(StandardCharsets.UTF_8));
        final String encodedPayload =
                encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        final String signingInput = encodedHeader + "." + encodedPayload;
        final String signature = encoder.encodeToString(sign(signingInput));
        return signingInput + "." + signature;
    }

    /**
     * Validates a presented compact token and, if it is well-formed, correctly
     * signed, and unexpired, returns its claims.
     *
     * <p>The method is total and exception-free for caller convenience: any
     * problem &mdash; {@code null}/blank input, wrong segment count, undecodable
     * base64url, signature mismatch, unparseable payload, missing required
     * claim, an unknown {@code typ} code, or an elapsed {@code exp} &mdash; yields
     * {@link Optional#empty()}. The signature check uses a constant-time
     * comparison to avoid timing side-channels.</p>
     *
     * @param token the compact token presented by the caller (typically the
     *              value after {@code "Bearer "} in the {@code Authorization}
     *              header); may be {@code null}
     * @return the token's claims if and only if the token is valid and unexpired;
     *         otherwise {@link Optional#empty()}
     */
    public Optional<TokenClaims> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        // Compact token = header.payload.signature — exactly three segments.
        final String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }

        final String signingInput = parts[0] + "." + parts[1];

        // Recompute the expected signature and compare in constant time.
        final byte[] expectedSig = sign(signingInput);
        final byte[] presentedSig;
        try {
            presentedSig = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(expectedSig, presentedSig)) {
            log.debug("Token rejected: signature mismatch.");
            return Optional.empty();
        }

        // Signature is valid — decode and parse the payload claims.
        final byte[] payloadBytes;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        final JsonNode payload;
        try {
            payload = PAYLOAD_READER.readTree(payloadBytes);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (payload == null || !payload.isObject()) {
            return Optional.empty();
        }

        final JsonNode subNode = payload.get("sub");
        final JsonNode typNode = payload.get("typ");
        final JsonNode expNode = payload.get("exp");
        final JsonNode iatNode = payload.get("iat");
        if (subNode == null || typNode == null || expNode == null || !expNode.canConvertToLong()) {
            return Optional.empty();
        }

        // Reject expired tokens (exp is epoch seconds; expiry is inclusive).
        final long expiresAt = expNode.asLong();
        final long now = Instant.now().getEpochSecond();
        if (now >= expiresAt) {
            log.debug("Token rejected: expired.");
            return Optional.empty();
        }

        // Resolve the role code to the type-safe enum; an unknown code is invalid.
        final UserType userType;
        try {
            userType = UserType.fromCode(typNode.asText());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        final long issuedAt = (iatNode != null && iatNode.canConvertToLong()) ? iatNode.asLong() : 0L;
        return Optional.of(new TokenClaims(subNode.asText(), userType, issuedAt, expiresAt));
    }

    /**
     * Computes the HMAC-SHA256 signature of the compact token's signing input
     * using the validated secret.
     *
     * <p>{@code HmacSHA256} is guaranteed present on every JRE, so a
     * {@link GeneralSecurityException} here indicates a fatal configuration
     * error rather than a recoverable condition; it is wrapped in an unchecked
     * {@link IllegalStateException}.</p>
     *
     * @param signingInput the {@code base64url(header) + "." + base64url(payload)} string
     * @return the raw HMAC-SHA256 signature bytes
     */
    private byte[] sign(String signingInput) {
        try {
            final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, HMAC_ALGORITHM));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is JDK-guaranteed; failure is a fatal configuration error.
            throw new IllegalStateException("Unable to sign the authentication token", e);
        }
    }

    /**
     * Escapes a string for safe inclusion as a JSON string value in the
     * hand-built token payload.
     *
     * <p>The JSON-significant characters ({@code "} and {@code \}), the common
     * control escapes, and any remaining C0 control character are escaped per
     * RFC&nbsp;8259 so the payload is always well-formed regardless of the input
     * (defensive: the user id is normally a short alphanumeric value).</p>
     *
     * @param value the raw string value to escape
     * @return the JSON-escaped value (without surrounding quotes)
     */
    private static String jsonEscape(String value) {
        final StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Immutable carrier for the validated claims of a compact token.
     *
     * <p>Returned by {@link #parse(String)} only when the token is well-formed,
     * correctly signed, and unexpired. The {@code subject} is the signed-in user
     * id ({@code sub}), {@code userType} is the resolved role ({@code typ}), and
     * {@code issuedAt}/{@code expiresAt} are the epoch-second {@code iat}/{@code exp}
     * claims.</p>
     *
     * @param subject   the signed-in user id (the {@code sub} claim)
     * @param userType  the resolved user role (the {@code typ} claim)
     * @param issuedAt  the issued-at epoch seconds (the {@code iat} claim; {@code 0} if absent)
     * @param expiresAt the expiry epoch seconds (the {@code exp} claim)
     */
    public record TokenClaims(String subject, UserType userType, long issuedAt, long expiresAt) {
    }
}
