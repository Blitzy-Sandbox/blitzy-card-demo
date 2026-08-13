package com.vsergeychik.carddemo.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The seal that makes a program's own communication area survive a stateless round trip without becoming
 * something the client can write.
 *
 * <h2>The problem this exists to solve</h2>
 * <p>{@code COACTUPC} and {@code COCRDUPC} are two-phase writers. The first turn edits what was typed and,
 * if every edit passes, records {@code ACUP-CHANGES-OK-NOT-CONFIRMED} / {@code CCUP-CHANGES-OK-NOT-CONFIRMED}
 * - the single byte {@code 'N'} - in the program's own commarea alongside the old and new snapshots. The
 * second turn reads that byte, and on {@code PF5} writes. The byte is <em>proof that the edits already
 * passed</em>: {@code app/cbl/COACTUPC.cbl:1463-1468} and {@code app/cbl/COCRDUPC.cbl:685-693} both skip
 * every field edit when it is set, on the reasonable premise that recomputing a verdict already reached
 * would be wasted work.
 *
 * <p>On a 3270 that premise holds, because the operator cannot write the commarea: CICS passes it, the
 * terminal never sees it. Rule <strong>R6</strong> and gate <strong>G37</strong> require the same state to
 * travel in the REST payload instead, and the moment it does, a caller can compose it. A hand-built body
 * claiming {@code 'N'} skips all twenty-four account edits or all four card edits and reaches persistence
 * on the next {@code PF5} - which is the whole of the two-phase design defeated by one byte. The
 * account and card snapshots are worse still: {@code CCUP-OLD-DETAILS} carries {@code CARD-CVV-CD}, a value
 * that appears on no {@code DFHMDF} field of {@code COCRDUP} and that a terminal is therefore never shown.
 *
 * <h2>What this class does</h2>
 * <p>It converts a fixed-width state image into one opaque token and back:
 *
 * <ul>
 *   <li>{@link #seal(String, String, byte[])} encrypts the image under AES-256-GCM and returns it
 *       Base64-URL encoded. The client carries the token verbatim and can neither read it nor alter it:
 *       GCM is authenticated encryption, so a single flipped bit fails the tag and the token is refused
 *       rather than decrypted into something else.</li>
 *   <li>{@link #unseal(String, String, String, String)} verifies and decrypts, and refuses anything that
 *       was not issued by this deployment for this screen and this record.</li>
 * </ul>
 *
 * <p><strong>The bytes are unchanged.</strong> What is sealed is exactly the image the COBOL commarea
 * holds - 873 bytes for {@code COACTUPC}, 329 for {@code COCRDUPC} - and what comes back out is byte for
 * byte the same image, decoded by the same {@code FixedWidthCodec} that would have decoded it from a
 * structured payload. Parity is therefore untouched: no field is renamed, dropped, widened or rounded, and
 * a parity case still drives the controller with a commarea object and asserts the one it gets back. Only
 * the <em>wire form</em> changes, from a structured JSON object a caller can compose into a token only this
 * deployment can produce.
 *
 * <h2>What is bound into the seal, and why</h2>
 * <p>Two things travel with the image, and both are covered by the authentication tag:
 *
 * <ul>
 *   <li><strong>The purpose</strong>, as GCM additional authenticated data. It names the screen and the
 *       member the token travels in, so a token issued for the card-update conversation cannot be
 *       presented to the account-update conversation. It is authenticated but not secret, which is exactly
 *       what AAD is for.</li>
 *   <li><strong>The record key</strong>, inside the plaintext. It is recovered on unsealing and compared
 *       against the key the URI names, so state issued for one account or card cannot be replayed against
 *       another. Carrying it in the plaintext rather than in the AAD is deliberate: it lets the refusal say
 *       <em>which</em> of the two things went wrong - a failed tag is a forged or tampered token, a key
 *       mismatch is a token issued for a different record - instead of collapsing both into one opaque
 *       failure. Neither refusal echoes a value.</li>
 * </ul>
 *
 * <h2>What it deliberately does not do</h2>
 * <ul>
 *   <li><strong>No expiry, and no server-side record of issued tokens.</strong> A CICS commarea has no
 *       lifetime and no server-side registry, and giving one to the migrated conversation would invent a
 *       state - "your screen timed out" - that no legacy path produces. A captured token can therefore be
 *       replayed, exactly as a captured commarea could be; what bounds the damage is the program's own
 *       concurrency control, {@code 9700-CHECK-CHANGE-IN-REC} and {@code 9300-CHECK-CHANGE-IN-REC}, which
 *       re-read the record and refuse a rewrite when it no longer matches the sealed snapshot.</li>
 *   <li><strong>No server-side session.</strong> The token is a payload member, so gate
 *       <strong>G37</strong> still holds: nothing about the conversation is held between calls.</li>
 *   <li><strong>No new dependency.</strong> AES-GCM, SHA-256, {@link SecureRandom} and
 *       {@link Base64} are all JDK, so AAP 0.5.6's closed dependency set is untouched and no security
 *       framework is introduced (the plaintext {@code SEC-USR-PWD} comparison of gate <strong>G41</strong>
 *       is a different question and is left exactly as {@code COSGN00C} performs it).</li>
 *   <li><strong>No static mutable state.</strong> The key is derived once in the constructor and the
 *       cipher is created per call, so the component is immutable and safe to share across requests.</li>
 * </ul>
 *
 * @see #SECRET_PROPERTY the configuration key the deployment must supply
 */
@Component
public final class ConversationStateSeal {

    /**
     * Configuration key supplying the sealing secret: {@value}.
     *
     * <p>Deployment-supplied and required, for the same reason {@code carddemo.cics.applid} is: there is no
     * value this module could invent that would be safe. A generated per-process key would make a token
     * unusable on the next instance of a horizontally scaled deployment, and a constant compiled into the
     * jar would make every deployment's tokens forgeable by anyone holding the jar. A deployment that
     * names no secret is refused at startup instead.
     */
    public static final String SECRET_PROPERTY = "carddemo.conversation-state.secret";

    /** The environment variable {@code application.yml} reads {@link #SECRET_PROPERTY} from: {@value}. */
    public static final String SECRET_ENVIRONMENT_VARIABLE = "CARDDEMO_CONVERSATION_STATE_SECRET";

    /**
     * Fewest characters the secret may carry: {@value}.
     *
     * <p>The key is a SHA-256 digest of the secret whatever its length, so a short secret would still
     * produce a 256-bit key - and would still be guessable at the strength of the secret rather than of the
     * key. Thirty-two characters is the width at which guessing the secret stops being the cheaper attack.
     */
    public static final int MINIMUM_SECRET_LENGTH = 32;

    /** The transformation: AES in Galois/Counter mode, which authenticates as well as encrypts. */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** The key algorithm the derived digest is used as. */
    private static final String KEY_ALGORITHM = "AES";

    /** The digest that turns a secret of any length into a 256-bit key. */
    private static final String KEY_DERIVATION = "SHA-256";

    /** Initialisation-vector width in bytes: 12 is the size GCM is specified for and performs best at. */
    private static final int IV_LENGTH = 12;

    /** Authentication-tag width in bits: the maximum GCM defines. */
    private static final int TAG_LENGTH_BITS = 128;

    /** Width of the big-endian length prefix that introduces the key inside the plaintext. */
    private static final int KEY_LENGTH_PREFIX = 2;

    /** Widest key the length prefix can describe, which every screen key is far inside of. */
    private static final int MAX_KEY_LENGTH = 0xFFFF;

    /** The derived key. Immutable and shared; a {@link Cipher} is created per call rather than reused. */
    private final SecretKeySpec key;

    /** The source of initialisation vectors. Thread-safe by contract. */
    private final SecureRandom random;

    /**
     * Derives the sealing key from the deployment's secret.
     *
     * @param secret the configured secret; must be non-blank and at least
     *               {@value #MINIMUM_SECRET_LENGTH} characters
     * @throws IllegalStateException if the secret is absent, blank or too short, or if the platform
     *                               provides no SHA-256
     */
    public ConversationStateSeal(@Value("${" + SECRET_PROPERTY + "}") final String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("The conversation-state secret is required: set "
                    + SECRET_PROPERTY + " (environment variable " + SECRET_ENVIRONMENT_VARIABLE + "). "
                    + "It is what makes a program's own communication area unforgeable while it travels "
                    + "in the payload, and there is no default this module could choose safely.");
        }
        if (secret.length() < MINIMUM_SECRET_LENGTH) {
            throw new IllegalStateException("The conversation-state secret named by " + SECRET_PROPERTY
                    + " must be at least " + MINIMUM_SECRET_LENGTH + " characters. The configured value "
                    + "is shorter, which would make guessing the secret cheaper than attacking the key "
                    + "derived from it. The value itself is not echoed here.");
        }
        this.key = new SecretKeySpec(digest(secret), KEY_ALGORITHM);
        this.random = new SecureRandom();
    }

    /**
     * Seals a fixed-width state image into one opaque token.
     *
     * @param purpose the screen and member this token belongs to, authenticated but not secret; must not
     *                be {@code null} or blank
     * @param recordKey the key of the record the state belongs to, at whatever width the caller holds it;
     *                  must not be {@code null} and must be no wider than {@value #MAX_KEY_LENGTH} bytes
     * @param image   the state image, exactly the bytes the COBOL commarea holds; must not be {@code null}
     * @return the Base64-URL token, never {@code null}
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalArgumentException if {@code purpose} is blank or {@code recordKey} is too wide
     * @throws IllegalStateException if the platform refuses the cipher
     */
    public String seal(final String purpose, final String recordKey, final byte[] image) {
        final byte[] keyBytes = keyBytes(purpose, recordKey);
        Objects.requireNonNull(image, "A state image is required to seal");
        final byte[] plaintext = new byte[KEY_LENGTH_PREFIX + keyBytes.length + image.length];
        plaintext[0] = (byte) (keyBytes.length >>> 8);
        plaintext[1] = (byte) keyBytes.length;
        System.arraycopy(keyBytes, 0, plaintext, KEY_LENGTH_PREFIX, keyBytes.length);
        System.arraycopy(image, 0, plaintext, KEY_LENGTH_PREFIX + keyBytes.length, image.length);

        final byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        final byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, iv, purpose, plaintext);
        final byte[] token = new byte[IV_LENGTH + ciphertext.length];
        System.arraycopy(iv, 0, token, 0, IV_LENGTH);
        System.arraycopy(ciphertext, 0, token, IV_LENGTH, ciphertext.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    /**
     * Verifies a token and returns the state image it carries.
     *
     * @param member    the payload member the token arrived in, named in a refusal so the caller knows
     *                  what to correct; must not be {@code null}
     * @param purpose   the screen and member the token must have been issued for; must not be {@code null}
     *                  or blank
     * @param recordKey the key the URI names, which the sealed key must equal; must not be {@code null}
     * @param token     the token as the caller sent it; must not be {@code null}
     * @return the state image, byte for byte as it was sealed; never {@code null}
     * @throws NullPointerException         if any argument is {@code null}
     * @throws IllegalArgumentException     if {@code purpose} is blank or {@code recordKey} is too wide
     * @throws ScreenInputRejectedException if the token is not a token this deployment issued for this
     *                                      screen, or was issued for a different record
     */
    public byte[] unseal(final String member, final String purpose, final String recordKey,
            final String token) {
        Objects.requireNonNull(member, "The payload member's name is required to name it in a refusal");
        final byte[] expectedKey = keyBytes(purpose, recordKey);
        Objects.requireNonNull(token, "A token is required to unseal");

        final byte[] sealed = decodeToken(member, token);
        final byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(sealed, 0, iv, 0, IV_LENGTH);
        final byte[] ciphertext = new byte[sealed.length - IV_LENGTH];
        System.arraycopy(sealed, IV_LENGTH, ciphertext, 0, ciphertext.length);

        final byte[] plaintext;
        try {
            plaintext = crypt(Cipher.DECRYPT_MODE, iv, purpose, ciphertext);
        } catch (final IllegalStateException rejected) {
            throw ScreenInputRejectedException.unauthenticStateToken(member);
        }
        if (plaintext.length < KEY_LENGTH_PREFIX) {
            throw ScreenInputRejectedException.unauthenticStateToken(member);
        }
        final int sealedKeyLength = ((plaintext[0] & 0xFF) << 8) | (plaintext[1] & 0xFF);
        if (sealedKeyLength > plaintext.length - KEY_LENGTH_PREFIX) {
            throw ScreenInputRejectedException.unauthenticStateToken(member);
        }
        final byte[] sealedKey = new byte[sealedKeyLength];
        System.arraycopy(plaintext, KEY_LENGTH_PREFIX, sealedKey, 0, sealedKeyLength);
        if (!MessageDigest.isEqual(expectedKey, sealedKey)) {
            throw ScreenInputRejectedException.stateTokenNamesAnotherRecord(member);
        }
        final int imageLength = plaintext.length - KEY_LENGTH_PREFIX - sealedKeyLength;
        final byte[] image = new byte[imageLength];
        System.arraycopy(plaintext, KEY_LENGTH_PREFIX + sealedKeyLength, image, 0, imageLength);
        return image;
    }

    /**
     * Decodes the Base64-URL envelope, refusing anything that cannot hold an initialisation vector and a
     * tag.
     *
     * @param member the member to name in a refusal
     * @param token  the token as it arrived
     * @return the decoded bytes
     * @throws ScreenInputRejectedException if the token is not Base64-URL, or is too short to be one
     */
    private static byte[] decodeToken(final String member, final String token) {
        final byte[] sealed;
        try {
            sealed = Base64.getUrlDecoder().decode(token);
        } catch (final IllegalArgumentException notBase64) {
            throw ScreenInputRejectedException.unauthenticStateToken(member);
        }
        if (sealed.length <= IV_LENGTH + TAG_LENGTH_BITS / Byte.SIZE) {
            throw ScreenInputRejectedException.unauthenticStateToken(member);
        }
        return sealed;
    }

    /**
     * Runs the cipher in one direction, with the purpose as additional authenticated data.
     *
     * @param mode    {@link Cipher#ENCRYPT_MODE} or {@link Cipher#DECRYPT_MODE}
     * @param iv      the initialisation vector
     * @param purpose the AAD
     * @param input   the bytes to transform
     * @return the transformed bytes
     * @throws IllegalStateException if the platform refuses the transformation, or - on decryption - if the
     *                               authentication tag does not verify
     */
    private byte[] crypt(final int mode, final byte[] iv, final String purpose, final byte[] input) {
        try {
            final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (final GeneralSecurityException refused) {
            throw new IllegalStateException("The conversation-state seal could not complete: "
                    + refused.getClass().getSimpleName(), refused);
        }
    }

    /**
     * Validates the purpose and renders the record key as the bytes that go inside the seal.
     *
     * @param purpose   the purpose, which must name something
     * @param recordKey the record key
     * @return the key's UTF-8 bytes
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if the purpose is blank or the key is too wide to describe
     */
    private static byte[] keyBytes(final String purpose, final String recordKey) {
        Objects.requireNonNull(purpose, "A purpose is required: it is what binds a token to one screen");
        Objects.requireNonNull(recordKey, "A record key is required: it is what binds a token to one "
                + "record, so state issued for one account or card cannot be replayed against another");
        if (purpose.isBlank()) {
            throw new IllegalArgumentException("A blank purpose would bind a token to no screen at all");
        }
        final byte[] keyBytes = recordKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("A record key of " + keyBytes.length + " bytes cannot be "
                    + "described by a " + KEY_LENGTH_PREFIX + "-byte length prefix");
        }
        return keyBytes;
    }

    /**
     * Derives the AES key from the secret.
     *
     * @param secret the configured secret
     * @return the 256-bit key material
     * @throws IllegalStateException if the platform provides no SHA-256
     */
    private static byte[] digest(final String secret) {
        try {
            return MessageDigest.getInstance(KEY_DERIVATION)
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (final NoSuchAlgorithmException absent) {
            throw new IllegalStateException("This platform provides no " + KEY_DERIVATION
                    + ", so no conversation-state key can be derived", absent);
        }
    }

    /**
     * A rendering that cannot disclose the key or the secret it was derived from.
     *
     * @return a fixed description; never {@code null}
     */
    @Override
    public String toString() {
        return "ConversationStateSeal[" + TRANSFORMATION + ", key=" + SensitiveDiagnostics.redacted()
                + "]";
    }
}
