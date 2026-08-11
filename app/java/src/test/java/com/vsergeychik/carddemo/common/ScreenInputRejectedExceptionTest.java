package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ScreenInputRejectedException} - the screen-boundary refusal that keeps a value the caller
 * supplied out of the abend path.
 *
 * <p>The behaviour under test is the answer a caller gets for a payload a {@code RECEIVE MAP} could
 * never have delivered. {@code COACTUPC} and {@code COCRDUPC} declare
 * {@code EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)}, so before this type existed such a payload was
 * answered {@code 500} with {@code ABEND-DATA} - blaming the transaction for the caller's input, and
 * differing from the identical input on the fifteen screens that declare no handler.
 *
 * <p>Two properties are asserted throughout because they are the point of the type: the <em>name</em> of
 * the member always reaches the answer, and the <em>value</em> never does.
 */
@DisplayName("ScreenInputRejectedException - a value no RECEIVE MAP could have delivered")
class ScreenInputRejectedExceptionTest {

    private static final Charset ASCII = StandardCharsets.US_ASCII;
    private static final Charset EBCDIC = Charset.forName("IBM037");
    private static final FixedWidthCodec ASCII_CODEC = new FixedWidthCodec(ASCII);

    /** A payload value carrying a card number, to prove it is never echoed. */
    private static final String SENSITIVE = "4111111111111111";

    private static Map<String, String> map(String... labelsAndValues) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < labelsAndValues.length; index += 2) {
            values.put(labelsAndValues[index], labelsAndValues[index + 1]);
        }
        return values;
    }

    @Nested
    @DisplayName("It is an IllegalArgumentException, which is what routes it to the existing 400")
    class ItsPlaceInTheHierarchy {

        @Test
        @DisplayName("extends IllegalArgumentException, so CobolErrorHandler answers it 400 without a "
                + "second error contract")
        void extendsIllegalArgumentException() {
            ScreenInputRejectedException rejected =
                    ScreenInputRejectedException.unrepresentable("acsfnam", "ACSFNAMI", ASCII, 0xE9);

            assertThat(rejected).isInstanceOf(IllegalArgumentException.class);
            assertThat(rejected).isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("is NOT an AbendException: staying out of that family is the whole reason it exists")
        void isNotAnAbend() {
            assertThat(ScreenInputRejectedException.unrepresentable("acsfnam", "ACSFNAMI", ASCII, 0xE9))
                    .isNotInstanceOf(AbendException.class);
        }
    }

    @Nested
    @DisplayName("unrepresentable - a character the code page has no representation for")
    class Unrepresentable {

        @Test
        @DisplayName("names the member, so a caller can find it among 54 fields")
        void namesTheMember() {
            ScreenInputRejectedException rejected =
                    ScreenInputRejectedException.unrepresentable("acsfnam", "ACSFNAMI", ASCII, 0xE9);

            assertThat(rejected.member()).contains("acsfnam");
            assertThat(rejected.getMessage()).contains("acsfnam").contains("ACSFNAMI");
        }

        @Test
        @DisplayName("reports the code point in U+XXXX form rather than rendering the character, and "
                + "names the code page that refused it")
        void reportsTheCodePointAndTheCodePage() {
            ScreenInputRejectedException rejected =
                    ScreenInputRejectedException.unrepresentable("acsfnam", "ACSFNAMI", ASCII, 0xE9);

            assertThat(rejected.getMessage())
                    .contains("U+00E9")
                    .contains("US-ASCII")
                    .doesNotContain("\u00E9");
        }

        @ParameterizedTest(name = "code point {0} is rendered as four hex digits at least")
        @ValueSource(ints = {0x00E9, 0x4E2D, 0x1F600})
        @DisplayName("renders any code point, including one outside the BMP, without the character")
        void rendersAnyCodePoint(int codePoint) {
            String message = ScreenInputRejectedException
                    .unrepresentable("acsfnam", "ACSFNAMI", ASCII, codePoint).getMessage();

            assertThat(message).contains("U+" + String.format("%04X", codePoint));
            assertThat(message).doesNotContain(new String(Character.toChars(codePoint)));
        }

        @Test
        @DisplayName("carries the code page it was given, so an EBCDIC deployment reports IBM037")
        void carriesTheCodePageItWasGiven() {
            assertThat(ScreenInputRejectedException
                    .unrepresentable("acsfnam", "ACSFNAMI", EBCDIC, 0x1F600).getMessage())
                    .contains("IBM037");
        }

        @Test
        @DisplayName("says so explicitly that the value is not echoed, so a reader is not left "
                + "wondering whether it was")
        void saysTheValueIsNotEchoed() {
            assertThat(ScreenInputRejectedException
                    .unrepresentable("acsfnam", "ACSFNAMI", ASCII, 0xE9).getMessage())
                    .contains("not echoed");
        }

        @Test
        @DisplayName("rejects a null member, item name or charset rather than composing a message with "
                + "\"null\" in it")
        void rejectsNullArguments() {
            assertThatNullPointerException().isThrownBy(() -> ScreenInputRejectedException
                    .unrepresentable(null, "ACSFNAMI", ASCII, 0xE9));
            assertThatNullPointerException().isThrownBy(() -> ScreenInputRejectedException
                    .unrepresentable("acsfnam", null, ASCII, 0xE9));
            assertThatNullPointerException().isThrownBy(() -> ScreenInputRejectedException
                    .unrepresentable("acsfnam", "ACSFNAMI", null, 0xE9));
        }
    }

    @Nested
    @DisplayName("inconsistentCommarea - a communication area the program itself could not have written")
    class InconsistentCommarea {

        @Test
        @DisplayName("names the member and states what the program writes there, as a shape")
        void namesTheMemberAndTheExpectedShape() {
            ScreenInputRejectedException rejected = ScreenInputRejectedException.inconsistentCommarea(
                    "commArea.oldDetails.acctid",
                    "the eleven digits of the fetched account identifier");

            assertThat(rejected.member()).contains("commArea.oldDetails.acctid");
            assertThat(rejected.getMessage())
                    .contains("commArea.oldDetails.acctid")
                    .contains("eleven digits of the fetched account identifier");
        }

        @Test
        @DisplayName("tells the caller how to recover - fetch first, then send back the reply's commarea")
        void tellsTheCallerHowToRecover() {
            assertThat(ScreenInputRejectedException
                    .inconsistentCommarea("commArea.oldDetails.cardid", "sixteen digits").getMessage())
                    .contains("Fetch the record first")
                    .contains("no change action")
                    .contains("not echoed");
        }

        @Test
        @DisplayName("rejects null arguments")
        void rejectsNullArguments() {
            assertThatNullPointerException().isThrownBy(() ->
                    ScreenInputRejectedException.inconsistentCommarea(null, "digits"));
            assertThatNullPointerException().isThrownBy(() ->
                    ScreenInputRejectedException.inconsistentCommarea("commArea.oldDetails.acctid", null));
        }
    }

    @Nested
    @DisplayName("requireRepresentable - the map-receive sweep")
    class TheSweep {

        @Test
        @DisplayName("passes a map whose every value the code page can represent")
        void passesARepresentableMap() {
            assertThatCode(() -> ScreenInputRejectedException.requireRepresentable(
                    map("ACSFNAM", "JOHN", "ACSTNUM", "123456789", "ACCTSID", "00000000011"),
                    ASCII_CODEC)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses the first unrepresentable value and names that field, not another")
        void namesTheFieldAtFault() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> ScreenInputRejectedException.requireRepresentable(
                            map("ACSFNAM", "JOHN", "ACSLNAM", "MU\u00D1OZ", "ACSTNUM", "123456789"),
                            ASCII_CODEC))
                    .satisfies(rejected -> {
                        assertThat(rejected.member()).contains("acslnam");
                        assertThat(rejected.getMessage()).contains("ACSLNAMI").contains("U+00D1");
                    });
        }

        @Test
        @DisplayName("derives the symbolic item as the label with I appended, which is how BMS names "
                + "the input item of every field in all seventeen mapsets")
        void derivesTheSymbolicItemName() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> ScreenInputRejectedException.requireRepresentable(
                            map("CRDNAME", "JOS\u00C9"), ASCII_CODEC))
                    .withMessageContaining("CRDNAMEI");
        }

        @Test
        @DisplayName("reports the FIRST offending field in declaration order, so the answer is stable")
        void reportsTheFirstOffenderInOrder() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> ScreenInputRejectedException.requireRepresentable(
                            map("ACSFNAM", "JOS\u00C9", "ACSLNAM", "MU\u00D1OZ"), ASCII_CODEC))
                    .satisfies(rejected -> assertThat(rejected.member()).contains("acsfnam"));
        }

        @Test
        @DisplayName("skips a null value rather than refusing it: an absent field is spaces on a "
                + "terminal, and carries no character to judge")
        void skipsNullValues() {
            Map<String, String> withNulls = new LinkedHashMap<>();
            withNulls.put("ACSFNAM", null);
            withNulls.put("ACSLNAM", "SMITH");

            assertThatCode(() -> ScreenInputRejectedException.requireRepresentable(withNulls, ASCII_CODEC))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("passes an empty map, which is what a screen with nothing typed into it is")
        void passesAnEmptyMap() {
            assertThatCode(() -> ScreenInputRejectedException.requireRepresentable(Map.of(), ASCII_CODEC))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("judges against the codec it is given: IBM037 represents an accented letter that "
                + "US-ASCII does not, and the sweep must not refuse what the write path would accept")
        void judgesAgainstTheCodecItIsGiven() {
            Map<String, String> accented = map("CRDNAME", "JOS\u00C9");

            assertThatCode(() -> ScreenInputRejectedException
                    .requireRepresentable(accented, new FixedWidthCodec(EBCDIC)))
                    .doesNotThrowAnyException();
            assertThatExceptionOfType(ScreenInputRejectedException.class).isThrownBy(() ->
                    ScreenInputRejectedException.requireRepresentable(accented, ASCII_CODEC));
        }

        @Test
        @DisplayName("never echoes the rejected value, even when it is a card number")
        void neverEchoesTheValue() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> ScreenInputRejectedException.requireRepresentable(
                            map("CARDSID", SENSITIVE + "\u00E9"), ASCII_CODEC))
                    .satisfies(rejected ->
                            assertThat(rejected.getMessage()).doesNotContain(SENSITIVE));
        }

        @Test
        @DisplayName("rejects a null map or a null codec: the code page is stated, never assumed")
        void rejectsNullArguments() {
            assertThatNullPointerException().isThrownBy(() ->
                    ScreenInputRejectedException.requireRepresentable(null, ASCII_CODEC));
            assertThatNullPointerException().isThrownBy(() ->
                    ScreenInputRejectedException.requireRepresentable(Map.of(), null));
        }
    }

    @Nested
    @DisplayName("requireDeliverable - a control character no 3270 could have transmitted")
    class RequireDeliverable {

        @ParameterizedTest(name = "U+{0} inside a value is refused")
        @ValueSource(strings = {"0000", "0009", "000A", "000D", "001B", "001F", "007F", "0085", "009F"})
        @DisplayName("every C0 control, DEL and C1 is refused when it sits among data")
        void everyControlAmongDataIsRefused(String hex) {
            String value = "A" + (char) Integer.parseInt(hex, 16) + "B";

            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> ScreenInputRejectedException.requireDeliverable("fname", value))
                    .satisfies(rejected -> {
                        assertThat(rejected.member()).contains("fname");
                        assertThat(rejected.getMessage())
                                .contains("U+" + hex.toUpperCase(java.util.Locale.ROOT))
                                .contains("control character")
                                .doesNotContain(value);
                    });
        }

        @Test
        @DisplayName("a value that is entirely LOW-VALUES is accepted: that is how BMS delivers an "
                + "unmodified field, and how this module renders an unpainted one")
        void allLowValuesIsAccepted() {
            assertThatCode(() -> ScreenInputRejectedException
                    .requireDeliverable("fname", "\u0000".repeat(20)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("data followed by a trailing run of LOW-VALUES is accepted: that is padding")
        void trailingLowValuesAreAccepted() {
            assertThatCode(() -> ScreenInputRejectedException
                    .requireDeliverable("fname", "JOHN" + "\u0000".repeat(16)))
                    .doesNotThrowAnyException();
            assertThatCode(() -> ScreenInputRejectedException
                    .requireDeliverable("fname", "JOHN   " + "\u0000".repeat(13)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a LOW-VALUE with data after it is refused: Read Modified suppresses nulls, so no "
                + "terminal can put one between data bytes")
        void anEmbeddedLowValueIsRefused() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> ScreenInputRejectedException
                            .requireDeliverable("fname", "A\u0000B" + " ".repeat(17)));
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> ScreenInputRejectedException
                            .requireDeliverable("fname", "\u0000AB"));
            // A trailing run interrupted by a space is not a trailing run.
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> ScreenInputRejectedException
                            .requireDeliverable("fname", "JOHN\u0000\u0000 "));
        }

        @Test
        @DisplayName("the first offending character is the one reported, so the answer is stable")
        void theFirstOffenderIsReported() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> ScreenInputRejectedException
                            // The line feed is built rather than written as a \\u escape: javac
                            // processes unicode escapes before lexing, so one inside a literal would
                            // end the literal.
                            .requireDeliverable("fname", "A\u0009B" + (char) 0x0A + "C"))
                    .satisfies(rejected ->
                            assertThat(rejected.getMessage()).contains("U+0009").doesNotContain("U+000A"));
        }

        @Test
        @DisplayName("an ordinary value, a null and an empty value are all accepted unchanged")
        void ordinaryValuesAreAccepted() {
            assertThatCode(() -> {
                ScreenInputRejectedException.requireDeliverable("fname", "JOHN Q. PUBLIC-SMITH");
                ScreenInputRejectedException.requireDeliverable("fname", null);
                ScreenInputRejectedException.requireDeliverable("fname", "");
                ScreenInputRejectedException.requireDeliverable("fname", " ".repeat(20));
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("never echoes the rejected value, even when it is a card number")
        void neverEchoesTheValue() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> ScreenInputRejectedException
                            .requireDeliverable("cardsid", SENSITIVE + "\u0009"))
                    .satisfies(rejected ->
                            assertThat(rejected.getMessage()).doesNotContain(SENSITIVE));
        }

        @Test
        @DisplayName("rejects a null member rather than composing a message with \"null\" in it")
        void rejectsANullMember() {
            assertThatNullPointerException().isThrownBy(() ->
                    ScreenInputRejectedException.requireDeliverable(null, "A\u0009B"));
            assertThatNullPointerException().isThrownBy(() ->
                    ScreenInputRejectedException.controlCharacter(null, 0x09));
        }

        @Test
        @DisplayName("controlCharacter names the member and the code point without rendering it")
        void controlCharacterNamesTheMemberAndCodePoint() {
            ScreenInputRejectedException rejected =
                    ScreenInputRejectedException.controlCharacter("acsfnam", 0x001B);

            assertThat(rejected.member()).contains("acsfnam");
            assertThat(rejected.getMessage())
                    .contains("acsfnam")
                    .contains("U+001B")
                    .contains("not echoed")
                    .doesNotContain("\u001B");
        }
    }

    @Nested
    @DisplayName("requireKeyAgreement - one key per request, stated once in the URI and once on the "
            + "screen")
    class RequireKeyAgreement {

        @Test
        @DisplayName("a member the payload omits states no key, so there is nothing to disagree with")
        void anAbsentMemberStatesNoKey() {
            assertThatCode(() -> ScreenInputRejectedException.requireKeyAgreement(
                    "acctsid", "00000000011", null, 11, ASCII_CODEC))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a blank or LOW-VALUES screen field states no key either, and a client echoing a "
                + "painted screen always agrees")
        void aBlankMemberStatesNoKey() {
            assertThatCode(() -> {
                ScreenInputRejectedException.requireKeyAgreement(
                        "acctsid", "00000000011", " ".repeat(11), 11, ASCII_CODEC);
                ScreenInputRejectedException.requireKeyAgreement(
                        "acctsid", "00000000011", "\u0000".repeat(11), 11, ASCII_CODEC);
                ScreenInputRejectedException.requireKeyAgreement(
                        "acctsid", "00000000011", "00000000011", 11, ASCII_CODEC);
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a second, different key is refused rather than silently discarded, and the value "
                + "is not echoed")
        void aSecondKeyIsRefused() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> ScreenInputRejectedException.requireKeyAgreement(
                            "acctsid", "00000000011", "00000000002", 11, ASCII_CODEC))
                    .satisfies(rejected -> {
                        assertThat(rejected.member()).contains("acctsid");
                        assertThat(rejected.getMessage()).doesNotContain("00000000002");
                    });
        }

        @Test
        @DisplayName("an image the screen itself uses to mean \"no criterion supplied\" agrees, which is "
                + "why the account and card screens pass their asterisk and the user screens pass none")
        void aNoCriterionImageAgrees() {
            assertThatCode(() -> ScreenInputRejectedException.requireKeyAgreement(
                    "acctsid", "00000000011", "*", 11, ASCII_CODEC, "*"))
                    .doesNotThrowAnyException();
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> ScreenInputRejectedException.requireKeyAgreement(
                            "usridin", "USER0001", "*", 8, ASCII_CODEC));
        }

        @Test
        @DisplayName("a zero width never reaches the comparison at all: the PIC X move refuses it "
                + "first, so no key can agree vacuously through an empty image")
        void aZeroWidthNeverReachesTheComparison() {
            // Worth pinning rather than assuming, because it is what makes the "is the image entirely
            // spaces or entirely LOW-VALUES" guard safe: every image it sees is at least one character,
            // so an empty image - which would make every key look like "no key supplied" - cannot be
            // constructed. FixedWidthCodec, not this class, is what holds that.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ScreenInputRejectedException.requireKeyAgreement(
                            "acctsid", "00000000011", "00000000002", 0, ASCII_CODEC))
                    .withMessageContaining("at least one character position");
        }

        @Test
        @DisplayName("rejects a null member, URI key, codec or no-criterion image")
        void rejectsNullArguments() {
            assertThatNullPointerException().isThrownBy(() ->
                    ScreenInputRejectedException.requireKeyAgreement(
                            null, "00000000011", "x", 11, ASCII_CODEC));
            assertThatNullPointerException().isThrownBy(() ->
                    ScreenInputRejectedException.requireKeyAgreement(
                            "acctsid", null, "x", 11, ASCII_CODEC));
            assertThatNullPointerException().isThrownBy(() ->
                    ScreenInputRejectedException.requireKeyAgreement(
                            "acctsid", "00000000011", "x", 11, null));
            assertThatNullPointerException().isThrownBy(() ->
                    ScreenInputRejectedException.requireKeyAgreement(
                            "acctsid", "00000000011", "x", 11, ASCII_CODEC, (String) null));
        }
    }
}
