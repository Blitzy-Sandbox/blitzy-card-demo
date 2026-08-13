package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ScreenInputRejectedException} - the screen-boundary refusal that keeps a value the caller supplied
 * out of the abend path.
 */
@DisplayName("ScreenInputRejectedException - a value no RECEIVE MAP could have delivered")
class ScreenInputRejectedExceptionTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;
    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final String SENSITIVE = "4111111111111111";

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
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> ScreenInputRejectedException
                            .requireDeliverable("fname", "JOHN\u0000\u0000 "));
        }

        @Test
        @DisplayName("the first offending character is the one reported, so the answer is stable")
        void theFirstOffenderIsReported() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> ScreenInputRejectedException
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
    @DisplayName("conflictingAid - one attention identifier, stated twice, disagreeing with itself")
    class ConflictingAid {
        @Test
        @DisplayName("the diagnostic names both carriers and explains which one is authoritative")
        void theDiagnosticNamesBothCarriers() {
            ScreenInputRejectedException refusal =
                    ScreenInputRejectedException.conflictingAid("aid", "eibaid");

            assertThat(refusal.getMessage())
                    .contains("aid")
                    .contains("eibaid")
                    .contains("one attention identifier")
                    .contains("folded");
            assertThat(refusal.member()).contains("aid");
        }

        @Test
        @DisplayName("it reuses CONTRADICTORY_SPELLINGS, so it publishes no new sentence")
        void itReusesTheContradictionReason() {
            ScreenInputRejectedException refusal =
                    ScreenInputRejectedException.conflictingAid("aid", "eibaid");

            assertThat(refusal.reason())
                    .isEqualTo(ScreenInputRejectedException.Reason.CONTRADICTORY_SPELLINGS);
            assertThat(refusal.publicDetail())
                    .isEqualTo(ScreenInputRejectedException.contradictorySpellings("aid", "eibAid",
                            "one EIBAID").publicDetail());
        }

        @Test
        @DisplayName("neither stated key reaches the published text, and nor do the 3270 mechanics")
        void thePublishedTextDisclosesNothing() {
            String published = ScreenInputRejectedException.conflictingAid("aid", "eibaid")
                    .publicDetail();

            assertThat(published)
                    .contains("aid")
                    .doesNotContain("PFK")
                    .doesNotContain("CSSTRPFY")
                    .doesNotContain("EIBAID")
                    .doesNotContain("folded")
                    .doesNotContain("token");
        }

        @Test
        @DisplayName("both names are required, because the answer names the member the caller must fix")
        void bothNamesAreRequired() {
            assertThatNullPointerException().isThrownBy(
                    () -> ScreenInputRejectedException.conflictingAid(null, "eibaid"));
            assertThatNullPointerException().isThrownBy(
                    () -> ScreenInputRejectedException.conflictingAid("aid", null));
        }

        @Test
        @DisplayName("it is an IllegalArgumentException, so it takes the existing 400 route")
        void itTakesTheExistingRoute() {
            assertThat(ScreenInputRejectedException.conflictingAid("aid", "eibaid"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
