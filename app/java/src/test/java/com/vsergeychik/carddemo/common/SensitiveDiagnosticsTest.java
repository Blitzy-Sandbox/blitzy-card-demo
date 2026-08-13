package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.vsergeychik.carddemo.common.SensitiveDiagnostics.Disclosure;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link SensitiveDiagnostics}, this module's single disclosure policy.
 */
@DisplayName("SensitiveDiagnostics - the module's disclosure policy")
class SensitiveDiagnosticsTest {
    @Nested
    @DisplayName("The policy's own constants")
    class Constants {
        @Test
        @DisplayName("one marker, and it cannot be mistaken for stored data")
        void oneMarkerThatCannotBeMistakenForData() {
            assertThat(SensitiveDiagnostics.REDACTED).isEqualTo("[redacted]");
            assertThat(SensitiveDiagnostics.redacted()).isEqualTo(SensitiveDiagnostics.REDACTED);
            assertThat(SensitiveDiagnostics.MASK_CHARACTER).isEqualTo('*');
            assertThat(SensitiveDiagnostics.ABSENT).isEqualTo("null");
        }

        @Test
        @DisplayName("the reveal length is a genuine minority of the narrowest identifier field")
        void theRevealLengthIsAMinority() {
            assertThat(SensitiveDiagnostics.REVEALED_TRAILING_DIGITS).isEqualTo(4).isLessThan(9);
        }

        @Test
        @DisplayName("it is a policy, not an object: not instantiable and holds no state")
        void itIsNotInstantiable() throws Exception {
            Constructor<SensitiveDiagnostics> constructor =
                    SensitiveDiagnostics.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);

            assertThat(SensitiveDiagnostics.class.getDeclaredFields())
                    .as("no mutable static state (practice B9, gate G53)")
                    .allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers())).isTrue());
        }
    }

    @Nested
    @DisplayName("Masking a primary account number or an identifier")
    class Masking {
        @ParameterizedTest(name = "{0} -> {1}")
        @DisplayName("the last four characters survive and the stored width is preserved")
        @CsvSource({
            "4111111111111111, ************1111",
            "0500024453765740, ************5740",
            "00000000011,      *******0011",
            "000000009,        *****0009",
            "12345,            *2345",
        })
        void theLastFourSurviveAtFullWidth(String stored, String expected) {
            assertThat(SensitiveDiagnostics.maskPan(stored)).isEqualTo(expected);
            assertThat(SensitiveDiagnostics.maskIdentifier(stored)).isEqualTo(expected);
            assertThat(SensitiveDiagnostics.maskPan(stored))
                    .as("a fixed-width diagnostic still has to report the right width")
                    .hasSameSizeAs(stored);
        }

        @ParameterizedTest
        @DisplayName("a value at or below the reveal length is masked ENTIRELY, never handed back whole")
        @ValueSource(strings = {"1", "12", "123", "1234"})
        void aShortValueIsMaskedEntirely(String stored) {
            assertThat(SensitiveDiagnostics.maskPan(stored))
                    .isEqualTo("*".repeat(stored.length()))
                    .doesNotContain(stored);
        }

        @Test
        @DisplayName("a numeric identifier is rendered at its field's declared width, left-zero-filled")
        void aNumericIdentifierUsesItsDeclaredWidth() {
            assertThat(SensitiveDiagnostics.maskIdentifier(11L, 11)).isEqualTo("*******0011");
            assertThat(SensitiveDiagnostics.maskPan(4111111111111111L, 16))
                    .isEqualTo("************1111");
            assertThat(SensitiveDiagnostics.maskIdentifier(9L, 9)).isEqualTo("*****0009");
        }

        @Test
        @DisplayName("an over-wide number keeps its low-order digits, as a COBOL numeric receiver does")
        void anOverWideNumberKeepsItsLowOrderDigits() {
            assertThat(SensitiveDiagnostics.maskIdentifier(123456789012L, 11)).isEqualTo("*******9012");
        }

        @Test
        @DisplayName("a non-positive width falls back to the number's own digit count")
        void aNonPositiveWidthUsesTheNumbersOwnDigits() {
            assertThat(SensitiveDiagnostics.maskIdentifier(123456L, 0)).isEqualTo("**3456");
            assertThat(SensitiveDiagnostics.maskIdentifier(123456L, -1)).isEqualTo("**3456");
        }

        @Test
        @DisplayName("a negative value is rendered from its magnitude, never with a sign")
        void aNegativeValueIsRenderedFromItsMagnitude() {
            assertThat(SensitiveDiagnostics.maskIdentifier(-123456L, 11))
                    .isEqualTo("*******3456")
                    .doesNotContain("-");
        }

        @Test
        @DisplayName("an empty value reports as empty rather than as a zero-length mask")
        void anEmptyValueReportsAsEmpty() {
            assertThat(SensitiveDiagnostics.maskPan("")).isEqualTo("[text len=0]");
        }

        @Test
        @DisplayName("the revealed tail is control-character escaped, so it cannot forge a log line")
        void theRevealedTailCannotForgeALogLine() {
            String forged = "411111111111\r\nOK";

            assertThat(SensitiveDiagnostics.maskPan(forged))
                    .isEqualTo("************X'0D'X'0A'OK")
                    .doesNotContain("\r")
                    .doesNotContain("\n");
            assertThat(SensitiveDiagnostics.maskPan(forged).lines()).hasSize(1);
            assertThat(SensitiveDiagnostics.maskIdentifier(forged))
                    .isEqualTo(SensitiveDiagnostics.maskPan(forged));
        }

        @Test
        @DisplayName("the mask keeps its own width even when an escape lengthens the visible tail")
        void theMaskKeepsItsWidth() {
            String stored = "0000000000000\u000109";

            String rendered = SensitiveDiagnostics.maskPan(stored);
            assertThat(rendered).startsWith("*".repeat(stored.length() - 4));
            assertThat(rendered.chars().filter(c -> c == '*').count())
                    .isEqualTo(stored.length() - 4);
            assertThat(rendered).endsWith("X'01'09");
        }

        @Test
        @DisplayName("this and DiagnosticText agree - one policy cannot be rendered two ways")
        void theTwoHelpersAgree() {
            for (String stored : new String[] {"4111111111111111", "00000000011", "1234", "1",
                                               "411111111111\r\nOK", "00000000\u009F11"}) {
                assertThat(SensitiveDiagnostics.maskPan(stored))
                        .as("maskPan and DiagnosticText.masked must render '%s' identically",
                                stored.replace("\r", "<CR>").replace("\n", "<LF>"))
                        .isEqualTo(DiagnosticText.masked(stored));
            }
            assertThat(SensitiveDiagnostics.maskPan((String) null)).isEqualTo("null");
            assertThat(DiagnosticText.masked(null)).isEqualTo("<absent>");
            assertThat(SensitiveDiagnostics.maskPan("")).isEqualTo("[text len=0]");
            assertThat(DiagnosticText.masked("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Describing free-text personal data")
    class DescribingText {
        @Test
        @DisplayName("the length is reported and not one character of the content")
        void theLengthIsReportedAndNoContent() {
            assertThat(SensitiveDiagnostics.describeText("JOHN Q PUBLIC"))
                    .isEqualTo("[text len=13]")
                    .doesNotContain("JOHN", "PUBLIC");
        }

        @Test
        @DisplayName("a blank fixed-width field is distinguishable from an empty or absent one")
        void blankEmptyAndAbsentAreDistinguishable() {
            assertThat(SensitiveDiagnostics.describeText(" ".repeat(25))).isEqualTo("[blank]");
            assertThat(SensitiveDiagnostics.describeText("")).isEqualTo("[text len=0]");
            assertThat(SensitiveDiagnostics.describeText(null)).isEqualTo("null");
        }

        @Test
        @DisplayName("a padded name reports its full declared width, so a wrong width is still visible")
        void aPaddedNameReportsItsDeclaredWidth() {
            assertThat(SensitiveDiagnostics.describeText("JANE" + " ".repeat(16)))
                    .isEqualTo("[text len=20]");
        }
    }

    @Nested
    @DisplayName("Classified rendering, for the screen DTOs that render in a loop")
    class ClassifiedRendering {
        @ParameterizedTest(name = "{0}")
        @DisplayName("each classification applies its own treatment")
        @CsvSource({
            "PLAIN,          ACTIVE,           ACTIVE",
            "IDENTIFIER,     00000000011,      *******0011",
            "PAN,            4111111111111111, ************1111",
            "TEXT,           JOHN PUBLIC,      '[text len=11]'",
            "REDACTED_VALUE, 123,              '[redacted]'",
        })
        void eachClassificationAppliesItsTreatment(Disclosure disclosure, String stored,
                                                  String expected) {
            assertThat(SensitiveDiagnostics.render(disclosure, stored)).isEqualTo(expected);
        }

        @Test
        @DisplayName("an UNCLASSIFIED field is withheld, not published")
        void anUnclassifiedFieldIsWithheld() {
            assertThat(SensitiveDiagnostics.render(null, "4111111111111111"))
                    .isEqualTo(SensitiveDiagnostics.REDACTED)
                    .doesNotContain("4111");
        }

        @Test
        @DisplayName("every classification is exercised, so none can be added without a decision")
        void everyClassificationIsExercised() {
            assertThat(Disclosure.values()).hasSize(5);
            for (Disclosure disclosure : Disclosure.values()) {
                assertThat(SensitiveDiagnostics.render(disclosure, "0000000000001234"))
                        .as("%s renders something", disclosure)
                        .isNotNull()
                        .isNotEmpty();
            }
        }
    }

    @Nested
    @DisplayName("Nothing here ever throws")
    class NeverThrows {
        @ParameterizedTest
        @DisplayName("every entry point accepts null")
        @NullSource
        void everyEntryPointAcceptsNull(String absent) {
            assertThat(SensitiveDiagnostics.maskPan(absent)).isEqualTo("null");
            assertThat(SensitiveDiagnostics.maskIdentifier(absent)).isEqualTo("null");
            assertThat(SensitiveDiagnostics.describeText(absent)).isEqualTo("null");
            assertThat(SensitiveDiagnostics.plain(absent)).isEqualTo("null");
            assertThat(SensitiveDiagnostics.render(Disclosure.PAN, absent)).isEqualTo("null");
        }

        @Test
        @DisplayName("plain() renders a non-string value and reports absence distinctly")
        void plainRendersAnyValue() {
            assertThat(SensitiveDiagnostics.plain(42)).isEqualTo("42");
            assertThat(SensitiveDiagnostics.plain("ACTIVE")).isEqualTo("ACTIVE");
            assertThat(SensitiveDiagnostics.plain(null)).isEqualTo("null");
        }

        @Test
        @DisplayName("the extremes of the long range are rendered without overflow")
        void theExtremesOfTheLongRangeAreSafe() {
            assertThat(SensitiveDiagnostics.maskIdentifier(Long.MAX_VALUE, 19)).hasSize(19);
            assertThat(SensitiveDiagnostics.maskIdentifier(Long.MIN_VALUE, 19))
                    .as("Math.abs(Long.MIN_VALUE) is negative; the rendering must still not carry a sign")
                    .doesNotContain("-");
            assertThat(SensitiveDiagnostics.maskIdentifier(0L, 11)).isEqualTo("*******0000");
        }
    }
}
