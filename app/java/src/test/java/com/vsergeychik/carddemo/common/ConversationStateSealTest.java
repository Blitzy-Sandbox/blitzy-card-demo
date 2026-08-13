package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ConversationStateSeal} - the thing that makes a program's own communication area survive a
 * stateless round trip without becoming something a client can write.
 *
 * <h2>What is actually under test</h2>
 * Two properties, and the second is the one that matters:
 *
 * <ul>
 *   <li><strong>Fidelity.</strong> What goes in comes out, byte for byte, at every width the two callers
 *       use - 873 bytes for {@code COACTUPC}'s {@code WS-THIS-PROGCOMMAREA} and 329 for
 *       {@code COCRDUPC}'s. A seal that quietly truncated or re-encoded would break parity everywhere at
 *       once, so the round trip is asserted on real widths rather than on a short sample.</li>
 *   <li><strong>Refusal.</strong> Every way a caller could try to supply state it was not given: a token
 *       that is not one, a token with a bit flipped, a token issued for another screen, a token issued for
 *       another record, a truncated envelope, and a token whose decrypted shape is nonsense. Each is
 *       refused with a {@link ScreenInputRejectedException} naming the member and echoing no value.</li>
 * </ul>
 *
 * <p>The last of those cannot be produced through {@link ConversationStateSeal#seal}, because {@code seal}
 * always writes a well-formed plaintext. {@link ShapeOfADecryptedToken} therefore derives the key the way
 * the class documents - SHA-256 of the secret - and encrypts a deliberately malformed plaintext. Holding the
 * secret is exactly the position an insider is in, and the assertion is that even from there a malformed
 * token is refused rather than trusted.
 */
@DisplayName("ConversationStateSeal - the authenticated carrier for WS-THIS-PROGCOMMAREA")
class ConversationStateSealTest {

    /** A secret comfortably past {@link ConversationStateSeal#MINIMUM_SECRET_LENGTH}. */
    private static final String SECRET = "conversation-state-seal-unit-test-secret-01";

    /** A second, different secret - a second deployment, or a rotated key. */
    private static final String OTHER_SECRET = "conversation-state-seal-unit-test-secret-02";

    /** The purpose {@code AccountUpdateController} binds its tokens to. */
    private static final String ACCOUNT_PURPOSE = "COACTUPC/stateToken";

    /** The purpose {@code CardUpdateController} binds its tokens to. */
    private static final String CARD_PURPOSE = "COCRDUPC/stateToken";

    /** The member a refusal names, as the client spells it in its own body. */
    private static final String MEMBER = "stateToken";

    /** {@code ACCTSIDI PIC X(11)} as the account route canonicalises it. */
    private static final String ACCOUNT_KEY = "00000000011";

    /** {@code CARDSIDI PIC X(16)}. */
    private static final String CARD_KEY = "4111111111111111";

    /** {@code WS-THIS-PROGCOMMAREA} for {@code COACTUPC}: 1 + 436 + 436. */
    private static final int ACCOUNT_AREA_LENGTH = 873;

    /** {@code WS-THIS-PROGCOMMAREA} for {@code COCRDUPC}: 1 + 89 + 89 + 150. */
    private static final int CARD_AREA_LENGTH = 329;

    private static ConversationStateSeal seal() {
        return new ConversationStateSeal(SECRET);
    }

    /**
     * An area whose every byte is distinguishable from its neighbours, so a fidelity failure names the
     * offset that moved rather than merely reporting inequality.
     *
     * @param length the width to produce
     * @return the image
     */
    private static byte[] area(int length) {
        byte[] image = new byte[length];
        for (int index = 0; index < length; index++) {
            image[index] = (byte) (index % 251);
        }
        return image;
    }

    @Nested
    @DisplayName("Fidelity - the bytes are unchanged, at both real widths")
    class Fidelity {

        @ParameterizedTest(name = "an area of {0} bytes round-trips unchanged")
        @ValueSource(ints = {ACCOUNT_AREA_LENGTH, CARD_AREA_LENGTH, 1, 4096})
        @DisplayName("what is sealed is what is unsealed, byte for byte")
        void theBytesSurvive(int length) {
            ConversationStateSeal seal = seal();
            byte[] image = area(length);

            byte[] recovered =
                    seal.unseal(MEMBER, ACCOUNT_PURPOSE, ACCOUNT_KEY,
                            seal.seal(ACCOUNT_PURPOSE, ACCOUNT_KEY, image));

            assertThat(recovered).isEqualTo(image);
        }

        @Test
        @DisplayName("an empty area is a legal area, because INITIALIZE produces one of zero length "
                + "nowhere but the seal must not decide that")
        void anEmptyAreaSurvives() {
            ConversationStateSeal seal = seal();

            assertThat(seal.unseal(MEMBER, CARD_PURPOSE, CARD_KEY,
                    seal.seal(CARD_PURPOSE, CARD_KEY, new byte[0])))
                    .isEmpty();
        }

        @Test
        @DisplayName("sealing the same area twice gives two different tokens - a fresh IV per seal")
        void everySealGetsItsOwnInitialisationVector() {
            ConversationStateSeal seal = seal();
            byte[] image = area(CARD_AREA_LENGTH);

            String first = seal.seal(CARD_PURPOSE, CARD_KEY, image);
            String second = seal.seal(CARD_PURPOSE, CARD_KEY, image);

            // GCM is catastrophically weak under IV reuse, so a deterministic token would be a defect
            // rather than a convenience. Both still unseal to the one area.
            assertThat(first).isNotEqualTo(second);
            assertThat(seal.unseal(MEMBER, CARD_PURPOSE, CARD_KEY, first))
                    .isEqualTo(seal.unseal(MEMBER, CARD_PURPOSE, CARD_KEY, second));
        }

        @Test
        @DisplayName("a token is URL-safe and unpadded, so it survives a path, a query and a header")
        void theTokenIsUrlSafe() {
            String token = seal().seal(ACCOUNT_PURPOSE, ACCOUNT_KEY, area(ACCOUNT_AREA_LENGTH));

            assertThat(token).matches("[A-Za-z0-9_-]+");
        }

        @Test
        @DisplayName("the area's own bytes do not appear in the token")
        void theTokenDoesNotPublishWhatItCarries() {
            // The CVV inside CCUP-OLD-DETAILS is the reason this has to be encryption and not a signature.
            byte[] image = "123 IS THE CARD VERIFICATION VALUE".getBytes(StandardCharsets.US_ASCII);

            String token = seal().seal(CARD_PURPOSE, CARD_KEY, image);

            // Compared as ISO-8859-1, which is the one charset that maps every byte to exactly one
            // character, so this is a genuine subsequence test over the bytes rather than over a decoding
            // that might have replaced some of them. Asserted on the whole plaintext and on a long,
            // distinctive run of it rather than on the three CVV digits: three bytes turn up in fifty
            // random ones about three times in a million, and a gate that fails three builds in a million
            // for no reason is worse than no gate.
            String envelope =
                    new String(Base64.getUrlDecoder().decode(token), StandardCharsets.ISO_8859_1);
            assertThat(envelope)
                    .as("the envelope is ciphertext and a tag, not the area with a wrapper on it")
                    .doesNotContain(new String(image, StandardCharsets.ISO_8859_1))
                    .doesNotContain("IS THE CARD VERIFICATION VALUE");
            assertThat(token).doesNotContain("IS THE CARD VERIFICATION VALUE");
        }

        @Test
        @DisplayName("two instances built from one secret interoperate, which is what a scaled "
                + "deployment needs")
        void twoInstancesOfOneSecretInteroperate() {
            String token = new ConversationStateSeal(SECRET)
                    .seal(ACCOUNT_PURPOSE, ACCOUNT_KEY, area(ACCOUNT_AREA_LENGTH));

            assertThat(new ConversationStateSeal(SECRET)
                    .unseal(MEMBER, ACCOUNT_PURPOSE, ACCOUNT_KEY, token))
                    .isEqualTo(area(ACCOUNT_AREA_LENGTH));
        }
    }

    @Nested
    @DisplayName("Refusal - every way a caller could supply state it was not given")
    class Refusal {

        @ParameterizedTest(name = "\"{0}\" is not a token")
        @ValueSource(strings = {"", "not-a-token", "!!!!", "AAAA", "0123456789abcdef",
            "eyJjaGFuZ2VBY3Rpb24iOiJOIn0"})
        @DisplayName("a value that is not a token this deployment issued is refused")
        void aValueThatIsNotATokenIsRefused(String supplied) {
            assertThatThrownBy(() -> seal().unseal(MEMBER, ACCOUNT_PURPOSE, ACCOUNT_KEY, supplied))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .satisfies(refused -> {
                        ScreenInputRejectedException rejection = (ScreenInputRejectedException) refused;
                        assertThat(rejection.reason())
                                .isEqualTo(ScreenInputRejectedException.Reason.UNAUTHENTIC_STATE);
                        assertThat(rejection.member()).contains(MEMBER);
                        assertThat(rejection.publicDetail())
                                .as("a refusal names the member and echoes no value")
                                .doesNotContain(supplied.isEmpty() ? "\u0000" : supplied);
                    });
        }

        @Test
        @DisplayName("a token with one bit flipped fails the authentication tag")
        void aTamperedTokenIsRefused() {
            ConversationStateSeal seal = seal();
            byte[] envelope = Base64.getUrlDecoder()
                    .decode(seal.seal(CARD_PURPOSE, CARD_KEY, area(CARD_AREA_LENGTH)));
            // The last byte is inside the tag, so this is the cheapest tamper there is - and it is still
            // detected, which is the whole reason GCM was chosen over a bare cipher.
            envelope[envelope.length - 1] ^= 0x01;
            String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);

            assertThatThrownBy(() -> seal.unseal(MEMBER, CARD_PURPOSE, CARD_KEY, tampered))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .hasFieldOrPropertyWithValue("reason",
                            ScreenInputRejectedException.Reason.UNAUTHENTIC_STATE);
        }

        @Test
        @DisplayName("a token whose ciphertext body is altered fails the tag too")
        void anAlteredBodyIsRefused() {
            ConversationStateSeal seal = seal();
            byte[] envelope = Base64.getUrlDecoder()
                    .decode(seal.seal(CARD_PURPOSE, CARD_KEY, area(CARD_AREA_LENGTH)));
            // Byte 12 is the first byte of ciphertext, immediately after the 12-byte IV: this is where a
            // caller trying to flip CCUP-CHANGE-ACTION from 'E' to 'N' would aim.
            envelope[12] ^= 0x0F;

            assertThatThrownBy(() -> seal.unseal(MEMBER, CARD_PURPOSE, CARD_KEY,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(envelope)))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .hasFieldOrPropertyWithValue("reason",
                            ScreenInputRejectedException.Reason.UNAUTHENTIC_STATE);
        }

        @Test
        @DisplayName("an envelope too short to hold an IV and a tag is refused before any cipher runs")
        void aTruncatedEnvelopeIsRefused() {
            // 27 bytes: a 12-byte IV plus a 16-byte tag is the shortest legal envelope, so anything at or
            // below 28 cannot be one.
            String truncated = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[27]);

            assertThatThrownBy(() -> seal().unseal(MEMBER, CARD_PURPOSE, CARD_KEY, truncated))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .hasFieldOrPropertyWithValue("reason",
                            ScreenInputRejectedException.Reason.UNAUTHENTIC_STATE);
        }

        @Test
        @DisplayName("a token issued for the account screen is refused by the card screen, and back")
        void aTokenIsBoundToItsScreen() {
            ConversationStateSeal seal = seal();
            String forAccount = seal.seal(ACCOUNT_PURPOSE, ACCOUNT_KEY, area(ACCOUNT_AREA_LENGTH));
            String forCard = seal.seal(CARD_PURPOSE, CARD_KEY, area(CARD_AREA_LENGTH));

            // The purpose is GCM additional authenticated data, so a mismatch is a tag failure: the wrong
            // screen's token does not decrypt into something the wrong screen might use.
            assertThatThrownBy(() -> seal.unseal(MEMBER, CARD_PURPOSE, CARD_KEY, forAccount))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .hasFieldOrPropertyWithValue("reason",
                            ScreenInputRejectedException.Reason.UNAUTHENTIC_STATE);
            assertThatThrownBy(() -> seal.unseal(MEMBER, ACCOUNT_PURPOSE, ACCOUNT_KEY, forCard))
                    .isInstanceOf(ScreenInputRejectedException.class);
        }

        @Test
        @DisplayName("a token issued for one record is refused for another, and says so specifically")
        void aTokenIsBoundToItsRecord() {
            ConversationStateSeal seal = seal();
            String forThisAccount = seal.seal(ACCOUNT_PURPOSE, ACCOUNT_KEY, area(ACCOUNT_AREA_LENGTH));

            assertThatThrownBy(() ->
                    seal.unseal(MEMBER, ACCOUNT_PURPOSE, "00000000099", forThisAccount))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .satisfies(refused -> {
                        ScreenInputRejectedException rejection = (ScreenInputRejectedException) refused;
                        // Distinguished from a forgery on purpose: a caller replaying its own state against
                        // somebody else's URI has a different mistake to correct than one sending rubbish,
                        // and neither refusal echoes the key.
                        assertThat(rejection.reason()).isEqualTo(
                                ScreenInputRejectedException.Reason.STATE_NAMES_ANOTHER_RECORD);
                        assertThat(rejection.publicDetail())
                                .contains(MEMBER)
                                .doesNotContain(ACCOUNT_KEY)
                                .doesNotContain("00000000099");
                    });
        }

        @Test
        @DisplayName("a token from another deployment's secret is refused")
        void anotherDeploymentsTokenIsRefused() {
            String elsewhere = new ConversationStateSeal(OTHER_SECRET)
                    .seal(ACCOUNT_PURPOSE, ACCOUNT_KEY, area(ACCOUNT_AREA_LENGTH));

            assertThatThrownBy(() -> seal().unseal(MEMBER, ACCOUNT_PURPOSE, ACCOUNT_KEY, elsewhere))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .hasFieldOrPropertyWithValue("reason",
                            ScreenInputRejectedException.Reason.UNAUTHENTIC_STATE);
        }
    }

    @Nested
    @DisplayName("The shape of a decrypted token - checked even when the tag verifies")
    class ShapeOfADecryptedToken {

        /**
         * Encrypts an arbitrary plaintext under the same key {@link ConversationStateSeal} derives, so a
         * token can be produced that authenticates and is still malformed.
         *
         * @param purpose   the AAD to bind
         * @param plaintext the bytes to encrypt, whatever shape they are
         * @return the token
         * @throws Exception if the platform refuses the cipher, which would fail the suite loudly
         */
        private static String tokenOver(String purpose, byte[] plaintext) throws Exception {
            SecretKeySpec key = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(SECRET.getBytes(StandardCharsets.UTF_8)), "AES");
            byte[] iv = new byte[12];
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] envelope = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
        }

        @Test
        @DisplayName("a plaintext too short to hold the key-length prefix is refused")
        void aPlaintextWithNoLengthPrefixIsRefused() throws Exception {
            String token = tokenOver(ACCOUNT_PURPOSE, new byte[] {0x41});

            assertThatThrownBy(() -> seal().unseal(MEMBER, ACCOUNT_PURPOSE, ACCOUNT_KEY, token))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .hasFieldOrPropertyWithValue("reason",
                            ScreenInputRejectedException.Reason.UNAUTHENTIC_STATE);
        }

        @Test
        @DisplayName("a key length longer than the plaintext is refused rather than read past the end")
        void aLengthPrefixBeyondThePlaintextIsRefused() throws Exception {
            // Declares a 0x0100-byte key inside a plaintext of four bytes. Trusting it would read off the
            // end of the array; refusing it is the only answer that is not a bug.
            String token = tokenOver(ACCOUNT_PURPOSE, new byte[] {0x01, 0x00, 0x41, 0x42});

            assertThatThrownBy(() -> seal().unseal(MEMBER, ACCOUNT_PURPOSE, ACCOUNT_KEY, token))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .hasFieldOrPropertyWithValue("reason",
                            ScreenInputRejectedException.Reason.UNAUTHENTIC_STATE);
        }

        @Test
        @DisplayName("a plaintext holding exactly a zero-length key and no image unseals to nothing")
        void aZeroLengthKeyAndNoImageIsTheEmptyAnswer() throws Exception {
            // The boundary the two checks above bracket: length 0, image 0, and the expected key must then
            // also be empty for the comparison to hold.
            String token = tokenOver(ACCOUNT_PURPOSE, new byte[] {0x00, 0x00});

            assertThat(seal().unseal(MEMBER, ACCOUNT_PURPOSE, "", token)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Configuration - a deployment that names no secret is refused at startup")
    class Configuration {

        @Test
        @DisplayName("an absent secret is refused, and the property and variable are both named")
        void anAbsentSecretIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new ConversationStateSeal(null))
                    .withMessageContaining(ConversationStateSeal.SECRET_PROPERTY)
                    .withMessageContaining(ConversationStateSeal.SECRET_ENVIRONMENT_VARIABLE);
        }

        @ParameterizedTest(name = "a secret of \"{0}\" is refused as blank")
        @ValueSource(strings = {"", " ", "\t", "\n", "    "})
        @DisplayName("a blank secret is refused: a whitespace value is an unset one")
        void aBlankSecretIsRefused(String blank) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new ConversationStateSeal(blank))
                    .withMessageContaining(ConversationStateSeal.SECRET_PROPERTY);
        }

        @Test
        @DisplayName("a secret shorter than the floor is refused, and is not echoed in the refusal")
        void aShortSecretIsRefused() {
            String tooShort = "x".repeat(ConversationStateSeal.MINIMUM_SECRET_LENGTH - 1);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new ConversationStateSeal(tooShort))
                    .withMessageContaining(
                            String.valueOf(ConversationStateSeal.MINIMUM_SECRET_LENGTH))
                    .withMessageNotContaining(tooShort);
        }

        @Test
        @DisplayName("a secret exactly at the floor is accepted, so the boundary is inclusive")
        void aSecretAtTheFloorIsAccepted() {
            String atTheFloor = "y".repeat(ConversationStateSeal.MINIMUM_SECRET_LENGTH);
            ConversationStateSeal seal = new ConversationStateSeal(atTheFloor);

            assertThat(seal.unseal(MEMBER, CARD_PURPOSE, CARD_KEY,
                    seal.seal(CARD_PURPOSE, CARD_KEY, area(CARD_AREA_LENGTH))))
                    .isEqualTo(area(CARD_AREA_LENGTH));
        }

        @Test
        @DisplayName("the property and the environment variable name each other's convention")
        void theConfigurationNamesAreTheOnesApplicationYmlBinds() {
            assertThat(ConversationStateSeal.SECRET_PROPERTY)
                    .isEqualTo("carddemo.conversation-state.secret");
            assertThat(ConversationStateSeal.SECRET_ENVIRONMENT_VARIABLE)
                    .isEqualTo("CARDDEMO_CONVERSATION_STATE_SECRET");
            assertThat(ConversationStateSeal.MINIMUM_SECRET_LENGTH).isEqualTo(32);
        }
    }

    @Nested
    @DisplayName("Arguments - a caller that cannot name a screen or a record is a programming error")
    class Arguments {

        @Test
        @DisplayName("a null purpose, record key, image, member or token is a NullPointerException")
        void nullsAreProgrammingErrors() {
            ConversationStateSeal seal = seal();
            String token = seal.seal(CARD_PURPOSE, CARD_KEY, area(CARD_AREA_LENGTH));

            assertThatNullPointerException()
                    .isThrownBy(() -> seal.seal(null, CARD_KEY, area(1)));
            assertThatNullPointerException()
                    .isThrownBy(() -> seal.seal(CARD_PURPOSE, null, area(1)));
            assertThatNullPointerException()
                    .isThrownBy(() -> seal.seal(CARD_PURPOSE, CARD_KEY, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> seal.unseal(null, CARD_PURPOSE, CARD_KEY, token));
            assertThatNullPointerException()
                    .isThrownBy(() -> seal.unseal(MEMBER, null, CARD_KEY, token));
            assertThatNullPointerException()
                    .isThrownBy(() -> seal.unseal(MEMBER, CARD_PURPOSE, null, token));
            assertThatNullPointerException()
                    .isThrownBy(() -> seal.unseal(MEMBER, CARD_PURPOSE, CARD_KEY, null));
        }

        @ParameterizedTest(name = "a purpose of \"{0}\" binds a token to no screen")
        @ValueSource(strings = {"", " ", "\t"})
        @DisplayName("a blank purpose is refused: it would bind a token to no screen at all")
        void aBlankPurposeIsRefused(String blank) {
            ConversationStateSeal seal = seal();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> seal.seal(blank, CARD_KEY, area(1)));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> seal.unseal(MEMBER, blank, CARD_KEY, "irrelevant"));
        }

        @Test
        @DisplayName("a record key wider than the length prefix can describe is refused")
        void anUndescribableRecordKeyIsRefused() {
            ConversationStateSeal seal = seal();
            String enormous = "9".repeat(0x10000);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> seal.seal(CARD_PURPOSE, enormous, area(1)))
                    .withMessageContaining("length prefix");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> seal.unseal(MEMBER, CARD_PURPOSE, enormous, "irrelevant"));
        }

        @Test
        @DisplayName("a record key at the widest describable value is accepted, so the bound is "
                + "inclusive")
        void theWidestDescribableKeyIsAccepted() {
            ConversationStateSeal seal = seal();
            String widest = "8".repeat(0xFFFF);

            assertThat(seal.unseal(MEMBER, CARD_PURPOSE, widest,
                    seal.seal(CARD_PURPOSE, widest, area(4))))
                    .isEqualTo(area(4));
        }
    }

    @Nested
    @DisplayName("Diagnostics - the key and the secret it came from reach no log")
    class Diagnostics {

        @Test
        @DisplayName("the rendering names the transformation and redacts the key")
        void theRenderingDisclosesNothing() {
            String rendered = new ConversationStateSeal(SECRET).toString();

            assertThat(rendered)
                    .contains("AES/GCM/NoPadding")
                    .contains(SensitiveDiagnostics.redacted())
                    .doesNotContain(SECRET);
        }

        @Test
        @DisplayName("no field of the class is static and mutable, so nothing is shared between requests")
        void noStaticMutableState() {
            for (java.lang.reflect.Field field : ConversationStateSeal.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                boolean isStatic = java.lang.reflect.Modifier.isStatic(field.getModifiers());
                assertThat(!isStatic || java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                        .as("%s must not be static and mutable", field.getName())
                        .isTrue();
            }
            assertThat(java.lang.reflect.Modifier.isFinal(ConversationStateSeal.class.getModifiers()))
                    .as("a subtype could weaken the seal while satisfying the same injection point")
                    .isTrue();
        }
    }
}
