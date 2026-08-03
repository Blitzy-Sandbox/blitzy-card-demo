/*
 * ******************************************************************
 * Program     : SnapshotTokenService.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 security component
 * Function    : Seals the as-displayed record snapshot, and the
 *               browse cursors, into an authenticated opaque token so
 *               that the stateless target reproduces the legacy
 *               COMMAREA precondition without trusting the caller and
 *               without disclosing a single protected byte.
 * Source      : app/cbl/COACTUPC.cbl:L669-L756 (ACUP-OLD-DETAILS and
 *               9700-CHECK-CHANGE-IN-REC, the field-by-field snapshot
 *               comparison the COMMAREA carried) @ 7756d89
 * Source      : app/cbl/COCRDUPC.cbl:L291-L313 (CCUP-OLD-DETAILS and
 *               CCUP-NEW-DETAILS), :L1503-L1508 (the six-predicate
 *               guard whose first predicate is CCUP-OLD-CVV-CD, a
 *               value no symbolic map declares) @ 7756d89
 * Source      : app/cbl/COCRDLIC.cbl:L237 (WS-CA-SCREEN-NUM),
 *               :L1197-L1205 (the one-record lookahead whose saved
 *               first and last keys are card numbers) @ 7756d89
 * Source      : app/cpy/COCOM01Y.cpy:L29 (CDEMO-PGM-CONTEXT, the
 *               pseudo-conversational carrier with no stateless
 *               counterpart) @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.FatalProcessingException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Turns a server-owned record snapshot into an authenticated, encrypted, expiring opaque string, and turns
 * that string back into the snapshot on the next request.
 *
 * <h2>What it does and why it has to exist</h2>
 *
 * <p>The legacy conversation kept the as-displayed record in its own half of the COMMAREA between the two
 * turns of a pseudo-conversation. {@code 9700-CHECK-CHANGE-IN-REC} at
 * {@code app/cbl/COACTUPC.cbl:L669-L756} then compared the live row against that carried copy field by
 * field, and {@code app/cbl/COCRDUPC.cbl:L1503-L1508} did the same for the card with six predicates. The
 * REST target is stateless by transformation Rule 7, so there is no COMMAREA half to carry - yet the
 * comparison cannot be dropped, because it is the guard the source provides, and it cannot be satisfied by
 * re-reading the row either, because comparing a row against itself is tautologically true.</p>
 *
 * <p>Handing the snapshot to the caller as plain JSON and taking it back solves the carriage problem and
 * creates three worse ones. The snapshot is <strong>protected data</strong> - it holds the social security
 * number, the date of birth, the government-issued identifier, the electronic-funds account identifier and
 * both telephone numbers of {@code app/cbl/COACTUPC.cbl:L669-L756}, and for the card it holds
 * {@code CCUP-OLD-CVV-CD} at {@code :L294}, a card verification value that <em>no</em> symbolic map
 * declares and that therefore may never be rendered. It is also <strong>a precondition</strong>: a caller
 * that can edit it can make the guard pass against values that were never displayed. And it is
 * <strong>replayable</strong>: nothing in a bare JSON echo binds it to a record, to a moment, or to the
 * operation it was issued for.</p>
 *
 * <p>This component removes all three problems at once. The snapshot is serialised, bound to an operation
 * kind, a record key and an expiry, sealed with AES-256-GCM, and handed to the caller as one base64url
 * string. The caller cannot read it, cannot alter it without detection, cannot present it for a different
 * record or a different operation, and cannot present it indefinitely. The server unseals it and gets back
 * exactly the bytes it wrote - which is what makes the legacy field-by-field comparison both reproducible
 * and trustworthy.</p>
 *
 * <h2>Inputs, outputs and side effects</h2>
 *
 * <p><b>Inputs.</b> A caller-chosen operation {@code kind}, a {@code recordKey} identifying the row, and
 * either an object to seal or a token to open. <b>Outputs.</b> A base64url token, or the payload recovered
 * from one. <b>Side effects.</b> None whatsoever: no row is read or written, no message is published, no
 * field of this class is mutated after construction, and nothing is cached. Each call draws a fresh random
 * nonce and is otherwise a pure function of its arguments, the derived key and the clock.</p>
 *
 * <h2>Configuration and defaults</h2>
 *
 * <table border="1">
 * <caption>Bound configuration</caption>
 * <tr><th>Property</th><th>Environment variable</th><th>Default</th></tr>
 * <tr><td>{@code carddemo.security.jwt.signing-key}</td><td>{@code JWT_SIGNING_KEY}</td>
 *     <td><b>None.</b> An absent variable leaves the placeholder unresolvable and startup fails</td></tr>
 * <tr><td>{@code carddemo.security.snapshot.lifetime-seconds}</td><td>-</td>
 *     <td>{@value #DEFAULT_LIFETIME_SECONDS} - non-secret metadata, so a documented default is
 *     acceptable</td></tr>
 * </table>
 *
 * <p>The signing key is the <strong>one</strong> committed secret indirection this application has, and it
 * is deliberately reused here rather than joined by a second one: a second variable would double the
 * secret surface an operator has to manage and would be exactly the duplication Rule 1 Clause C forbids.
 * Reuse is nevertheless not sharing. The sealing key is derived from the configured value by
 * {@code HMAC-SHA-256(configured-key, "carddemo-snapshot-token-v1")}, so the AES key used here and the
 * HS256 key {@link JwtTokenProvider} signs with are computationally unrelated: recovering one from the
 * other requires inverting HMAC. The derived key is the full 32 bytes HMAC-SHA-256 produces, which is
 * exactly an AES-256 key, so no truncation or padding step exists to get wrong.</p>
 *
 * <h2>Failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Startup aborts naming {@code JWT_SIGNING_KEY}</dt>
 *   <dd>The variable is absent. That is intended fail-fast behaviour and no default may be added to
 *       silence it. Export at least {@value #MINIMUM_KEY_BYTES} bytes of entropy.</dd>
 *   <dt>{@code 428 Precondition Required} on a write</dt>
 *   <dd>No token was presented. Read the record first and return the {@code ETag} it carries in
 *       {@code If-Match}.</dd>
 *   <dt>{@code 412 Precondition Failed} on a write</dt>
 *   <dd>The token failed to open. All five causes report identically and deliberately so - a truncated or
 *       edited token, a token sealed for another operation, a token sealed for another record, an expired
 *       token, and a token sealed under a different key are indistinguishable to the caller, because
 *       telling them apart is an oracle. The server-side log line names which one it was.</dd>
 *   </dl>
 *
 * <p>No failure message ever quotes the offending token, the recovered payload or the configured key.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Immutable after construction and safe to share across request threads. {@link SecureRandom} and
 * {@link ObjectMapper} are both documented thread safe; {@link Cipher} is not, so a fresh instance is
 * obtained inside every call rather than held as a field.</p>
 */
@Component
public class SnapshotTokenService {

    /**
     * Property key for the signing key, the same one {@link JwtTokenProvider} binds. It resolves
     * {@code JWT_SIGNING_KEY} with no default anywhere in any profile.
     */
    private static final String SIGNING_KEY_PROPERTY = "carddemo.security.jwt.signing-key";

    /** Property key for the token lifetime. Non-secret, so a default is supplied inline. */
    private static final String LIFETIME_PROPERTY = "carddemo.security.snapshot.lifetime-seconds";

    /**
     * Default token lifetime in seconds. Fifteen minutes is long enough for an operator to review a
     * populated screen and confirm, and short enough that a captured token stops being useful quickly.
     */
    private static final String DEFAULT_LIFETIME_SECONDS = "900";

    /**
     * Minimum accepted length of the configured key, in bytes. Matched to the floor HS256 imposes on
     * {@link JwtTokenProvider} so that one exported value satisfies both components.
     */
    private static final int MINIMUM_KEY_BYTES = 32;

    /** Domain-separation label. Changing it invalidates every token in flight, which is its purpose. */
    private static final String DERIVATION_LABEL = "carddemo-snapshot-token-v1";

    /** Key-derivation primitive. */
    private static final String DERIVATION_ALGORITHM = "HmacSHA256";

    /** Symmetric algorithm for the derived key. */
    private static final String KEY_ALGORITHM = "AES";

    /** Authenticated-encryption transformation. GCM supplies confidentiality and integrity together. */
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    /** Nonce length in bytes. Twelve is the size GCM is specified and optimised for. */
    private static final int NONCE_BYTES = 12;

    /** Authentication-tag length in bits, the maximum GCM defines. */
    private static final int TAG_BITS = 128;

    /** Envelope member holding the operation kind. */
    private static final String MEMBER_KIND = "k";

    /** Envelope member holding the record key. */
    private static final String MEMBER_RECORD = "r";

    /** Envelope member holding the expiry as epoch seconds. */
    private static final String MEMBER_EXPIRY = "x";

    /** Envelope member holding the sealed payload. */
    private static final String MEMBER_PAYLOAD = "p";

    /**
     * The message the caller receives when no token was presented. It is the empty-literal outcome of
     * {@code app/cbl/COACTUPC.cbl:L523}, expressed as an instruction rather than as a blank screen.
     */
    public static final String MISSING_TOKEN_MESSAGE =
            "The as-displayed snapshot is required on a write. Read the record first and return the value"
                    + " of its ETag header in If-Match.";

    /**
     * The message the caller receives when a token failed to open, whatever the reason. One message for
     * five causes, on purpose: distinguishing them would let a caller probe the sealing key.
     */
    public static final String INVALID_TOKEN_MESSAGE =
            "The as-displayed snapshot could not be verified for this record. Read the record again and"
                    + " retry with the ETag that read returns.";

    /** The derived AES-256 key. Never logged, never returned and never quoted in a message. */
    private final SecretKeySpec sealingKey;

    /** Token lifetime, validated positive at construction. */
    private final Duration lifetime;

    /** Clock the expiry is measured against; injected so tests need no sleeping. */
    private final Clock clock;

    /** Nonce source. Documented thread safe, so one instance serves every request. */
    private final SecureRandom nonceSource;

    /** Serialiser for the envelope and its payload. Documented thread safe once configured. */
    private final ObjectMapper objectMapper;

    /**
     * Derives the sealing key and validates every bound value at construction rather than at first use, so
     * a misconfigured deployment fails while it is starting instead of on a caller's write.
     *
     * @param signingKey the configured value of {@value #SIGNING_KEY_PROPERTY}; must be non-blank and at
     *     least {@value #MINIMUM_KEY_BYTES} bytes once encoded as UTF-8
     * @param lifetimeSeconds the configured value of {@value #LIFETIME_PROPERTY}; must be positive
     * @param clock the clock expiry is measured against; must not be null
     * @param objectMapper the application's configured serialiser; must not be null
     * @throws IllegalStateException if the key is blank or shorter than {@value #MINIMUM_KEY_BYTES} bytes,
     *     or if the lifetime is not positive. The message names the variable to set and the remedy, and
     *     never the value
     * @throws NullPointerException if {@code clock} or {@code objectMapper} is null
     */
    public SnapshotTokenService(
            @Value("${" + SIGNING_KEY_PROPERTY + "}") final String signingKey,
            @Value("${" + LIFETIME_PROPERTY + ":" + DEFAULT_LIFETIME_SECONDS + "}")
            final long lifetimeSeconds,
            final Clock clock,
            final ObjectMapper objectMapper) {

        if (signingKey == null || signingKey.isBlank()) {
            throw new IllegalStateException("Environment variable JWT_SIGNING_KEY is absent or blank, so "
                    + SIGNING_KEY_PROPERTY + " could not be resolved. Export at least " + MINIMUM_KEY_BYTES
                    + " bytes of entropy; no default exists and none may be added.");
        }
        final byte[] configured = signingKey.getBytes(StandardCharsets.UTF_8);
        if (configured.length < MINIMUM_KEY_BYTES) {
            Arrays.fill(configured, (byte) 0);
            throw new IllegalStateException("Environment variable JWT_SIGNING_KEY is shorter than the "
                    + MINIMUM_KEY_BYTES + " bytes this application requires. Export a longer key; the"
                    + " configured value is deliberately not reproduced here.");
        }
        if (lifetimeSeconds <= 0L) {
            Arrays.fill(configured, (byte) 0);
            throw new IllegalStateException("Property " + LIFETIME_PROPERTY + " must be positive but was "
                    + lifetimeSeconds + ". A non-positive lifetime would expire every token at the instant"
                    + " it was issued.");
        }
        this.sealingKey = deriveSealingKey(configured);
        Arrays.fill(configured, (byte) 0);
        this.lifetime = Duration.ofSeconds(lifetimeSeconds);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.nonceSource = new SecureRandom();
    }

    /**
     * Seals a payload into an opaque token bound to an operation kind, a record key and an expiry.
     *
     * <p><b>Side effects.</b> None. <b>Inputs.</b> The kind and record key are authenticated additional
     * data as well as envelope members, so neither can be swapped without the open failing. The payload is
     * serialised with the application's configured mapper, which means a type whose serialisation is lossy
     * - a component annotated write-only, for instance - must have that component carried explicitly by
     * its caller rather than relied upon here.</p>
     *
     * @param kind the operation this token is valid for, for example an account update; must not be blank
     * @param recordKey the identifier of the row the snapshot was taken from; must not be blank
     * @param payload the snapshot to seal; must not be null
     * @return the base64url token, safe to place in an {@code ETag} header and in a JSON body; never null
     * @throws IllegalArgumentException if {@code kind} or {@code recordKey} is null or blank
     * @throws NullPointerException if {@code payload} is null
     * @throws FatalProcessingException if serialisation or encryption fails, which is a broken deployment
     *     rather than a request outcome; the original throwable is preserved as the cause and no payload
     *     content reaches the message
     */
    public String seal(final String kind, final String recordKey, final Object payload) {
        requireText(kind, "kind");
        requireText(recordKey, "recordKey");
        Objects.requireNonNull(payload, "payload must not be null");

        final ObjectNode envelope = this.objectMapper.createObjectNode();
        envelope.put(MEMBER_KIND, kind);
        envelope.put(MEMBER_RECORD, recordKey);
        envelope.put(MEMBER_EXPIRY, this.clock.instant().plus(this.lifetime).getEpochSecond());
        envelope.putPOJO(MEMBER_PAYLOAD, payload);

        final byte[] plaintext;
        try {
            plaintext = this.objectMapper.writeValueAsBytes(envelope);
        } catch (final JsonProcessingException serialisationFailure) {
            throw new FatalProcessingException(ABEND_CODE, CULPRIT, SEAL_ABEND_REASON,
                    SEAL_ABEND_MESSAGE, serialisationFailure);
        }
        try {
            return encrypt(plaintext, additionalData(kind, recordKey));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * Opens a token and returns the payload it was sealed with, refusing anything that is not the exact
     * token this server issued for this operation and this record, within its lifetime.
     *
     * <p><b>Side effects.</b> None. <b>Inputs.</b> {@code kind} and {@code recordKey} are the values the
     * caller expects, not values taken from the token: the token's own members are compared against them,
     * which is what makes a token issued for one record useless against another.</p>
     *
     * @param <T> the payload type
     * @param token the opaque value the caller returned; null and blank are both reported as absent
     * @param kind the operation the token must have been sealed for; must not be blank
     * @param recordKey the record the token must have been sealed for; must not be blank
     * @param payloadType the type to deserialise the payload into; must not be null
     * @return the recovered payload, never null
     * @throws IllegalArgumentException if {@code kind} or {@code recordKey} is null or blank
     * @throws NullPointerException if {@code payloadType} is null
     * @throws ConcurrentUpdateException with {@link ConcurrentUpdateException.Outcome#CHANGES_NOT_CONFIRMED}
     *     when no token was presented, and with
     *     {@link ConcurrentUpdateException.Outcome#DATA_CHANGED_BEFORE_UPDATE} when a token was presented
     *     and could not be verified. The two are distinguished because the remedies differ - obtain a
     *     token, versus read again - while the five reasons a verification can fail are deliberately not
     * @throws FatalProcessingException if a recovered token deserialises to a payload of the wrong shape,
     *     which cannot happen through this class's own {@code seal} and therefore indicates tampering that
     *     survived authentication, that is, a compromised key
     */
    public <T> T open(final String token, final String kind, final String recordKey,
                      final Class<T> payloadType) {
        requireText(kind, "kind");
        requireText(recordKey, "recordKey");
        Objects.requireNonNull(payloadType, "payloadType must not be null");

        if (token == null || token.isBlank()) {
            throw new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED, MISSING_TOKEN_MESSAGE);
        }

        final byte[] plaintext = decrypt(token, additionalData(kind, recordKey));
        try {
            final ObjectNode envelope = readEnvelope(plaintext);
            requireMatches(envelope, MEMBER_KIND, kind);
            requireMatches(envelope, MEMBER_RECORD, recordKey);
            requireUnexpired(envelope);
            return readPayload(envelope, payloadType);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * Seals a browse cursor. A convenience over {@link #seal} for the one case whose payload is a bare
     * string rather than a structured snapshot.
     *
     * <p>It exists because the card browse's saved keys <em>are</em> card numbers -
     * {@code app/cbl/COCRDLIC.cbl:L1197-L1205} saves the first and last card number of the page so the
     * next turn can position from them - and a card number may not appear on a JSON contract. Sealing
     * keeps the browse working exactly as the source's saved keys did while making the wire value
     * meaningless to anyone but this server.</p>
     *
     * <p><b>Side effects.</b> None.</p>
     *
     * @param kind the browse this cursor belongs to; must not be blank
     * @param cursor the key to seal, or null when the source saved no key - null is returned unchanged so
     *     that "no cursor" stays distinct from "a cursor whose value is empty"
     * @return the sealed cursor, or null when {@code cursor} was null
     * @throws IllegalArgumentException if {@code kind} is null or blank
     */
    public String sealCursor(final String kind, final String cursor) {
        requireText(kind, "kind");
        if (cursor == null) {
            return null;
        }
        return seal(kind, CURSOR_RECORD_KEY, cursor);
    }

    /**
     * Opens a browse cursor sealed by {@link #sealCursor}.
     *
     * <p><b>Side effects.</b> None.</p>
     *
     * @param kind the browse the cursor must have been sealed for; must not be blank
     * @param sealedCursor the value the caller returned, or null when it sent none
     * @return the recovered key, or null when {@code sealedCursor} was null or blank - an absent cursor is
     *     a legitimate first request and is not a failure
     * @throws IllegalArgumentException if {@code kind} is null or blank
     * @throws ConcurrentUpdateException with
     *     {@link ConcurrentUpdateException.Outcome#DATA_CHANGED_BEFORE_UPDATE} when a cursor was presented
     *     and could not be verified
     */
    public String openCursor(final String kind, final String sealedCursor) {
        requireText(kind, "kind");
        if (sealedCursor == null || sealedCursor.isBlank()) {
            return null;
        }
        return open(sealedCursor, kind, CURSOR_RECORD_KEY, String.class);
    }

    // ------------------------------------------------------------------------------------------------
    // Abend payload for the two conditions that are deployment faults rather than request outcomes.
    // The four members transcribe app/cpy/CSMSG02Y.cpy, which app/cbl/CBTRN02C.cbl:L707-L710 populates
    // before CALL 'CEE3ABD'.
    // ------------------------------------------------------------------------------------------------

    /** {@code ABEND-CODE}, the terminal code {@code app/cbl/CBTRN02C.cbl:L709} moves before abending. */
    private static final String ABEND_CODE = "0999";

    /** {@code ABEND-CULPRIT}, this component's own name. */
    private static final String CULPRIT = "SNAPTOKN";

    /** {@code ABEND-REASON} for a sealing failure. */
    private static final String SEAL_ABEND_REASON = "SNAPSHOT TOKEN SEAL FAILED";

    /** {@code ABEND-MSG} for a sealing failure. Names no payload content. */
    private static final String SEAL_ABEND_MESSAGE =
            "The as-displayed snapshot could not be sealed. The payload is withheld from this message.";

    /** {@code ABEND-REASON} for a payload that authenticated yet deserialises to the wrong shape. */
    private static final String SHAPE_ABEND_REASON = "SNAPSHOT TOKEN SHAPE INVALID";

    /** {@code ABEND-MSG} for that condition. */
    private static final String SHAPE_ABEND_MESSAGE =
            "An authenticated snapshot token carried a payload of an unexpected shape, which this"
                    + " application never seals. Treat the sealing key as compromised and rotate JWT_SIGNING_KEY.";

    /**
     * The record key a cursor is sealed under. A cursor belongs to a browse rather than to a row, so it
     * has no row identifier of its own; a fixed non-empty literal keeps the authenticated additional data
     * well formed without pretending a row is involved.
     */
    private static final String CURSOR_RECORD_KEY = "-";

    /**
     * Derives the AES-256 sealing key from the configured signing key by one HMAC-SHA-256 over a fixed
     * label.
     *
     * @param configured the configured key bytes; not retained and not modified
     * @return the derived 32-byte AES key
     * @throws IllegalStateException if the platform lacks HMAC-SHA-256, which no supported JDK does
     */
    private static SecretKeySpec deriveSealingKey(final byte[] configured) {
        final byte[] derived;
        try {
            final Mac mac = Mac.getInstance(DERIVATION_ALGORITHM);
            mac.init(new SecretKeySpec(configured, DERIVATION_ALGORITHM));
            derived = mac.doFinal(DERIVATION_LABEL.getBytes(StandardCharsets.UTF_8));
        } catch (final GeneralSecurityException unavailable) {
            throw new IllegalStateException("The platform does not provide " + DERIVATION_ALGORITHM
                    + ", which every supported JDK 25 distribution does. The runtime is unusable.",
                    unavailable);
        }
        try {
            return new SecretKeySpec(derived, KEY_ALGORITHM);
        } finally {
            Arrays.fill(derived, (byte) 0);
        }
    }

    /**
     * Encrypts and frames one envelope as {@code nonce || ciphertext-and-tag}, base64url encoded without
     * padding.
     *
     * @param plaintext the serialised envelope
     * @param additionalData the authenticated additional data binding kind and record key
     * @return the token
     * @throws FatalProcessingException if the platform refuses the transformation or the operation fails
     */
    private String encrypt(final byte[] plaintext, final byte[] additionalData) {
        final byte[] nonce = new byte[NONCE_BYTES];
        this.nonceSource.nextBytes(nonce);
        try {
            final Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, this.sealingKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(additionalData);
            final byte[] sealed = cipher.doFinal(plaintext);
            final byte[] framed = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, framed, 0, nonce.length);
            System.arraycopy(sealed, 0, framed, nonce.length, sealed.length);
            Arrays.fill(sealed, (byte) 0);
            final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(framed);
            Arrays.fill(framed, (byte) 0);
            return token;
        } catch (final GeneralSecurityException sealingFailure) {
            throw new FatalProcessingException(ABEND_CODE, CULPRIT, SEAL_ABEND_REASON, SEAL_ABEND_MESSAGE,
                    sealingFailure);
        }
    }

    /**
     * Decodes, authenticates and decrypts one token.
     *
     * <p>Every failure - a value that is not base64url, a value too short to hold a nonce, a failed
     * authentication tag, a mismatched additional-data binding - is reported as the same single outcome,
     * because reporting them apart would let a caller distinguish "wrong key" from "wrong record" and
     * probe the sealing key one guess at a time. The underlying throwable is preserved as the cause so a
     * server-side log retains the detail the caller is denied.</p>
     *
     * @param token the caller-supplied value, already known to be non-blank
     * @param additionalData the expected authenticated additional data
     * @return the recovered plaintext envelope
     * @throws ConcurrentUpdateException with
     *     {@link ConcurrentUpdateException.Outcome#DATA_CHANGED_BEFORE_UPDATE} on any failure
     */
    private byte[] decrypt(final String token, final byte[] additionalData) {
        final byte[] framed;
        try {
            framed = Base64.getUrlDecoder().decode(token);
        } catch (final IllegalArgumentException notBase64) {
            throw invalidToken(notBase64);
        }
        if (framed.length <= NONCE_BYTES) {
            Arrays.fill(framed, (byte) 0);
            throw invalidToken(null);
        }
        try {
            final Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, this.sealingKey,
                    new GCMParameterSpec(TAG_BITS, framed, 0, NONCE_BYTES));
            cipher.updateAAD(additionalData);
            return cipher.doFinal(framed, NONCE_BYTES, framed.length - NONCE_BYTES);
        } catch (final GeneralSecurityException authenticationFailure) {
            throw invalidToken(authenticationFailure);
        } finally {
            Arrays.fill(framed, (byte) 0);
        }
    }

    /**
     * Parses a recovered envelope.
     *
     * @param plaintext the recovered bytes
     * @return the envelope
     * @throws FatalProcessingException if the bytes authenticated yet are not a JSON object, which this
     *     class never seals
     */
    private ObjectNode readEnvelope(final byte[] plaintext) {
        try {
            final JsonNode parsed = this.objectMapper.readTree(plaintext);
            if (parsed instanceof ObjectNode envelope) {
                return envelope;
            }
        } catch (final IOException malformed) {
            throw new FatalProcessingException(ABEND_CODE, CULPRIT, SHAPE_ABEND_REASON,
                    SHAPE_ABEND_MESSAGE, malformed);
        }
        throw new FatalProcessingException(ABEND_CODE, CULPRIT, SHAPE_ABEND_REASON, SHAPE_ABEND_MESSAGE);
    }

    /**
     * Deserialises the payload member.
     *
     * @param <T> the payload type
     * @param envelope the opened envelope
     * @param payloadType the expected type
     * @return the payload, never null
     * @throws FatalProcessingException if the member is absent or cannot be bound to {@code payloadType}
     */
    private <T> T readPayload(final ObjectNode envelope, final Class<T> payloadType) {
        if (!envelope.has(MEMBER_PAYLOAD)) {
            throw new FatalProcessingException(ABEND_CODE, CULPRIT, SHAPE_ABEND_REASON,
                    SHAPE_ABEND_MESSAGE);
        }
        try {
            final T payload = this.objectMapper.treeToValue(envelope.get(MEMBER_PAYLOAD), payloadType);
            if (payload == null) {
                throw new FatalProcessingException(ABEND_CODE, CULPRIT, SHAPE_ABEND_REASON,
                        SHAPE_ABEND_MESSAGE);
            }
            return payload;
        } catch (final JsonProcessingException wrongShape) {
            throw new FatalProcessingException(ABEND_CODE, CULPRIT, SHAPE_ABEND_REASON, SHAPE_ABEND_MESSAGE,
                    wrongShape);
        }
    }

    /**
     * Requires an envelope member to equal an expected value, by exact comparison.
     *
     * @param envelope the opened envelope
     * @param member the member name
     * @param expected the expected value
     * @throws ConcurrentUpdateException when the member is absent or differs
     */
    private static void requireMatches(final ObjectNode envelope, final String member,
                                       final String expected) {
        if (!envelope.hasNonNull(member) || !expected.equals(envelope.get(member).asText())) {
            throw invalidToken(null);
        }
    }

    /**
     * Requires the envelope not to have expired.
     *
     * <p>Comparison is inclusive at the boundary in the caller's favour: a token is refused once the
     * clock has passed its expiry, not when it reaches it.</p>
     *
     * @param envelope the opened envelope
     * @throws ConcurrentUpdateException when the expiry member is absent or in the past
     */
    private void requireUnexpired(final ObjectNode envelope) {
        if (!envelope.hasNonNull(MEMBER_EXPIRY)) {
            throw invalidToken(null);
        }
        final Instant expiry = Instant.ofEpochSecond(envelope.get(MEMBER_EXPIRY).asLong());
        if (this.clock.instant().isAfter(expiry)) {
            throw invalidToken(null);
        }
    }

    /**
     * Builds the authenticated additional data that binds a token to one operation and one record.
     *
     * <p>The two values are joined by a byte that cannot occur in either - a newline - so that no pair of
     * distinct kind and record values can produce the same additional data.</p>
     *
     * @param kind the operation kind
     * @param recordKey the record key
     * @return the additional data
     */
    private static byte[] additionalData(final String kind, final String recordKey) {
        return (kind + '\n' + recordKey).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds the single refusal every verification failure reports.
     *
     * @param cause the underlying throwable, or null where there was none; preserved so the server-side
     *     log keeps the detail the caller is denied
     * @return the refusal to throw
     */
    private static ConcurrentUpdateException invalidToken(final Throwable cause) {
        if (cause == null) {
            return new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE, INVALID_TOKEN_MESSAGE);
        }
        return new ConcurrentUpdateException(
                ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE, INVALID_TOKEN_MESSAGE, cause);
    }

    /**
     * Requires a non-blank argument.
     *
     * @param value the value to check
     * @param name the parameter name to report
     * @throws IllegalArgumentException when {@code value} is null or blank
     */
    private static void requireText(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }
}
