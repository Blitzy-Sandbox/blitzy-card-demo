package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
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
                    ScreenInputRejectedException.unrepresentable("ACSFNAM", "ACSFNAMI", ASCII, 0xE9);

            assertThat(rejected).isInstanceOf(IllegalArgumentException.class);
            assertThat(rejected).isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("is NOT an AbendException: staying out of that family is the whole reason it exists")
        void isNotAnAbend() {
            assertThat(ScreenInputRejectedException.unrepresentable("ACSFNAM", "ACSFNAMI", ASCII, 0xE9))
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
                    ScreenInputRejectedException.unrepresentable("ACSFNAM", "ACSFNAMI", ASCII, 0xE9);

            assertThat(rejected.member()).contains("ACSFNAM");
            assertThat(rejected.getMessage()).contains("ACSFNAM").contains("ACSFNAMI");
        }

        @Test
        @DisplayName("reports the code point in U+XXXX form rather than rendering the character, and "
                + "names the code page that refused it")
        void reportsTheCodePointAndTheCodePage() {
            ScreenInputRejectedException rejected =
                    ScreenInputRejectedException.unrepresentable("ACSFNAM", "ACSFNAMI", ASCII, 0xE9);

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
                    .unrepresentable("ACSFNAM", "ACSFNAMI", ASCII, codePoint).getMessage();

            assertThat(message).contains("U+" + String.format("%04X", codePoint));
            assertThat(message).doesNotContain(new String(Character.toChars(codePoint)));
        }

        @Test
        @DisplayName("carries the code page it was given, so an EBCDIC deployment reports IBM037")
        void carriesTheCodePageItWasGiven() {
            assertThat(ScreenInputRejectedException
                    .unrepresentable("ACSFNAM", "ACSFNAMI", EBCDIC, 0x1F600).getMessage())
                    .contains("IBM037");
        }

        @Test
        @DisplayName("says so explicitly that the value is not echoed, so a reader is not left "
                + "wondering whether it was")
        void saysTheValueIsNotEchoed() {
            assertThat(ScreenInputRejectedException
                    .unrepresentable("ACSFNAM", "ACSFNAMI", ASCII, 0xE9).getMessage())
                    .contains("not echoed");
        }

        @Test
        @DisplayName("rejects a null member, item name or charset rather than composing a message with "
                + "\"null\" in it")
        void rejectsNullArguments() {
            assertThatNullPointerException().isThrownBy(() -> ScreenInputRejectedException
                    .unrepresentable(null, "ACSFNAMI", ASCII, 0xE9));
            assertThatNullPointerException().isThrownBy(() -> ScreenInputRejectedException
                    .unrepresentable("ACSFNAM", null, ASCII, 0xE9));
            assertThatNullPointerException().isThrownBy(() -> ScreenInputRejectedException
                    .unrepresentable("ACSFNAM", "ACSFNAMI", null, 0xE9));
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
                        assertThat(rejected.member()).contains("ACSLNAM");
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
                    .satisfies(rejected -> assertThat(rejected.member()).contains("ACSFNAM"));
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
}
