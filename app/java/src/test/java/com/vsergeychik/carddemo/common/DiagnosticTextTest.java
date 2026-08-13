package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link DiagnosticText}, the module's single policy for rendering a value into a diagnostic
 * without disclosing it.
 */
@DisplayName("DiagnosticText - identify a record, describe its shape, disclose nothing")
class DiagnosticTextTest {
    private static final String PAN = "4111111111111111";

    @Nested
    @DisplayName("masked - the identifiers a diagnostic correlates on")
    class Masking {
        @Test
        @DisplayName("It leaves exactly the last four characters legible")
        void itLeavesTheLastFourLegible() {
            assertThat(DiagnosticText.masked(PAN)).isEqualTo("************1111");
            assertThat(DiagnosticText.masked("00000000011")).isEqualTo("*******0011");
        }

        @Test
        @DisplayName("The rendering is the value's own width, so the mask leaks no length information")
        void theRenderingKeepsTheValuesWidth() {
            assertThat(DiagnosticText.masked(PAN)).hasSameSizeAs(PAN);
        }

        @ParameterizedTest(name = "\"{0}\" is masked entirely rather than partly disclosed")
        @ValueSource(strings = {"1", "12", "123", "1234"})
        @DisplayName("A value no longer than the allowance is masked entirely")
        void aShortValueIsMaskedEntirely(final String shortValue) {
            assertThat(DiagnosticText.masked(shortValue))
                    .isEqualTo("*".repeat(shortValue.length()))
                    .doesNotContain(shortValue);
        }

        @Test
        @DisplayName("One character past the allowance is the first to reveal anything")
        void oneCharacterPastTheAllowanceRevealsTheTail() {
            assertThat(DiagnosticText.masked("12345")).isEqualTo("*2345");
        }

        @Test
        @DisplayName("An empty value renders empty, and an absent one is named")
        void anEmptyValueRendersEmpty() {
            assertThat(DiagnosticText.masked("")).isEmpty();
            assertThat(DiagnosticText.masked(null)).isEqualTo(DiagnosticText.ABSENT);
        }

        @Test
        @DisplayName("A control character in the visible tail is still escaped")
        void aControlCharacterInTheTailIsEscaped() {
            assertThat(DiagnosticText.masked("000000\r\n12")).doesNotContain("\r").doesNotContain("\n");
        }

        @Test
        @DisplayName("A numeric identifier is zero-padded first, so its rendering never varies")
        void aNumericIdentifierIsZeroPaddedFirst() {
            assertThat(DiagnosticText.masked(11L, 11)).isEqualTo("*******0011");
            assertThat(DiagnosticText.masked(98765432101L, 11)).isEqualTo("*******2101");
            assertThat(DiagnosticText.masked(11L, 11))
                    .hasSameSizeAs(DiagnosticText.masked(98765432101L, 11));
        }

        @Test
        @DisplayName("A negative value is masked by magnitude rather than leaking a sign")
        void aNegativeValueIsMaskedByMagnitude() {
            assertThat(DiagnosticText.masked(-11L, 11)).isEqualTo("*******0011");
        }

        @Test
        @DisplayName("A value wider than its declared digits is masked rather than truncated")
        void aValueWiderThanItsDeclaredDigitsIsMasked() {
            assertThat(DiagnosticText.masked(123456L, 4)).isEqualTo("**3456");
        }

        @ParameterizedTest(name = "a declared digit count of {0} is refused")
        @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
        @DisplayName("A non-positive digit count is refused: no PIC 9 field is that narrow")
        void aNonPositiveDigitCountIsRefused(final int digits) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DiagnosticText.masked(1L, digits))
                    .withMessageContaining("at least one digit position");
        }
    }

    @Nested
    @DisplayName("omitted - everything a person or a balance supplied")
    class Withholding {
        @Test
        @DisplayName("It reports the width and nothing else")
        void itReportsTheWidthAndNothingElse() {
            assertThat(DiagnosticText.omitted("SMITH               "))
                    .isEqualTo(DiagnosticText.OMITTED + ":20")
                    .doesNotContain("SMITH");
            assertThat(DiagnosticText.omitted("")).isEqualTo(DiagnosticText.OMITTED + ":0");
        }

        @Test
        @DisplayName("An absent value is named rather than reported as zero width")
        void anAbsentValueIsNamed() {
            assertThat(DiagnosticText.omitted(null)).isEqualTo(DiagnosticText.ABSENT);
            assertThat(DiagnosticText.omitted(null)).isNotEqualTo(DiagnosticText.omitted(""));
        }

        @Test
        @DisplayName("A number reports no width, because a digit count would disclose a magnitude")
        void aNumberReportsNoWidth() {
            assertThat(DiagnosticText.omitted()).isEqualTo(DiagnosticText.OMITTED);
            assertThat(DiagnosticText.omitted()).doesNotContain(":");
        }

        @Test
        @DisplayName("The marker is the one SecUserRecord already uses for a withheld password")
        void theMarkerMatchesTheEstablishedOne() {
            assertThat(DiagnosticText.OMITTED).isEqualTo("<omitted>");
        }
    }

    @Nested
    @DisplayName("singleLine - a log line the caller cannot forge")
    class Escaping {
        @Test
        @DisplayName("Text with nothing to escape is returned unchanged and unallocated")
        void textWithNothingToEscapeIsUnchanged() {
            String plain = "COCRDLIC";

            assertThat(DiagnosticText.singleLine(plain)).isSameAs(plain);
        }

        @Test
        @DisplayName("Each control character becomes a COBOL hexadecimal literal")
        void eachControlCharacterBecomesAHexLiteral() {
            assertThat(DiagnosticText.singleLine("a\rb")).isEqualTo("aX'0D'b");
            assertThat(DiagnosticText.singleLine("a\nb")).isEqualTo("aX'0A'b");
            assertThat(DiagnosticText.singleLine("a\tb")).isEqualTo("aX'09'b");
            assertThat(DiagnosticText.singleLine("a\u001Bb")).isEqualTo("aX'1B'b");
        }

        @Test
        @DisplayName("A forged second log line cannot survive the escape")
        void aForgedSecondLogLineCannotSurvive() {
            String forged = "0001\r\n2026-01-01 INFO  Everything is fine";

            String escaped = DiagnosticText.singleLine(forged);

            assertThat(escaped).doesNotContain("\r").doesNotContain("\n");
            assertThat(escaped.lines()).hasSize(1);
            assertThat(escaped).contains("X'0D'").contains("X'0A'")
                    .contains("Everything is fine");
        }

        @Test
        @DisplayName("The escape is unambiguous: it contains nothing needing escaping in turn")
        void theEscapeIsUnambiguous() {
            assertThat(DiagnosticText.singleLine(DiagnosticText.singleLine("a\nb")))
                    .isEqualTo(DiagnosticText.singleLine("a\nb"));
        }

        @Test
        @DisplayName("A leading control character is escaped, not skipped")
        void aLeadingControlCharacterIsEscaped() {
            assertThat(DiagnosticText.singleLine("\u0000AB")).isEqualTo("X'00'AB");
        }

        @Test
        @DisplayName("DEL and the C1 range are escaped too, not only the C0 range")
        void delAndTheC1RangeAreEscaped() {
            assertThat(DiagnosticText.singleLine("a\u007Fb")).isEqualTo("aX'7F'b");
            assertThat(DiagnosticText.singleLine("a\u0085b")).isEqualTo("aX'85'b");
            assertThat(DiagnosticText.singleLine("a\u009Fb")).isEqualTo("aX'9F'b");
            assertThat(DiagnosticText.singleLine("a\u00A0b")).isEqualTo("a\u00A0b");
        }

        @Test
        @DisplayName("An absent value is named")
        void anAbsentValueIsNamed() {
            assertThat(DiagnosticText.singleLine(null)).isEqualTo(DiagnosticText.ABSENT);
        }
    }

    @Nested
    @DisplayName("screenField - the decision taken from the DFHMDF label")
    class ScreenFieldClassification {
        @ParameterizedTest(name = "{0} is masked")
        @ValueSource(strings = {
            "ACCTNO1O", "ACCTNO7I", "ACCTSIDO", "ACCTSIDI", "ACCTID",
            "ACTIDINI", "ACTIDINO", "CARDNINI", "CARDNINO", "CARDNUMO",
            "CARDSIDO", "CARDSIDI", "CARDID", "CRDNUM1O", "CRDNUM7I",
        })
        @DisplayName("Every spelling of an account or card number is masked")
        void everySpellingOfAnIdentifierIsMasked(final String label) {
            assertThat(DiagnosticText.screenField(label, PAN))
                    .isEqualTo(DiagnosticText.masked(PAN))
                    .doesNotContain(PAN);
        }

        @ParameterizedTest(name = "{0} is withheld")
        @ValueSource(strings = {
            "ACSTSSNI", "ACTSSN1I", "ACTSSN3O", "ACSGOVTI", "ACSEFTCI",
            "ACSTDOBI", "DOBDAYI", "DOBMONI", "DOBYEARO",
            "FNAMEI", "FNAME01O", "MNAMEI", "LNAMEO", "LNAME10I",
            "CRDNAMEI", "CVVCD", "ACRDLIMO", "EXPYEAR", "EXPMON", "EXPDAY",
        })
        @DisplayName("Every personal or monetary field is withheld with its width")
        void everyPersonalFieldIsWithheld(final String label) {
            assertThat(DiagnosticText.screenField(label, "123456789"))
                    .isEqualTo(DiagnosticText.OMITTED + ":9")
                    .doesNotContain("123456789");
        }

        @ParameterizedTest(name = "{0} renders in full")
        @ValueSource(strings = {
            "PGMNAMEO", "TRNNAMEI", "TITLE01O", "CURDATEO", "CURTIMEO", "PAGENOO",
            "CRDSEL1O", "CRDSTS1O", "CRDSTP2O", "CRDSTCDO", "ERRMSGO", "INFOMSGO",
            "TRNIDINI", "TTYPCDI", "TCATCDI", "TRNSRCI", "CONFIRMI",
        })
        @DisplayName("A label that identifies nobody renders in full, keeping the diagnostic useful")
        void aLabelThatIdentifiesNobodyRendersInFull(final String label) {
            assertThat(DiagnosticText.screenField(label, "COCRDLIC")).isEqualTo("COCRDLIC");
        }

        @Test
        @DisplayName("PGMNAME and TRNNAME are the cases a substring rule gets wrong")
        void programAndTransactionNamesAreNotPersonalNames() {
            assertThat("PGMNAMEO").contains("MNAME");
            assertThat(DiagnosticText.screenField("PGMNAMEO", "COCRDLIC")).isEqualTo("COCRDLIC");
            assertThat(DiagnosticText.screenField("TRNNAMEI", "CCLI")).isEqualTo("CCLI");
        }

        @Test
        @DisplayName("A card status shares three letters with a card number and is not masked")
        void aCardStatusIsNotACardNumber() {
            assertThat("CRDSTS1O").startsWith("CRD");
            assertThat(DiagnosticText.screenField("CRDSTS1O", "Y")).isEqualTo("Y");
        }

        @Test
        @DisplayName("Every symbolic-map suffix of one field reaches the same decision")
        void everySuffixOfOneFieldReachesTheSameDecision() {
            for (String suffix : new String[] {"I", "O", "L", "F", "A", "C", "H", "P", "V"}) {
                assertThat(DiagnosticText.screenField("ACCTNO1" + suffix, PAN))
                        .as("ACCTNO1%s", suffix)
                        .isEqualTo(DiagnosticText.masked(PAN));
            }
        }

        @Test
        @DisplayName("A base label ending in a suffix letter keeps its last character")
        void aBaseLabelEndingInASuffixLetterIsUnharmed() {
            assertThat(DiagnosticText.screenField("CRDNAME", "SMITH"))
                    .isEqualTo(DiagnosticText.OMITTED + ":5");
            assertThat(DiagnosticText.screenField("ACCTID", "00000000011"))
                    .isEqualTo(DiagnosticText.masked("00000000011"));
        }

        @Test
        @DisplayName("A single-character label is not stripped away to nothing")
        void aSingleCharacterLabelIsNotStrippedAway() {
            assertThat(DiagnosticText.screenField("O", "value")).isEqualTo("value");
            assertThat(DiagnosticText.screenField("1", "value")).isEqualTo("value");
        }

        @Test
        @DisplayName("A repeating field's row digits are dropped however many there are")
        void rowDigitsAreDropped() {
            assertThat(DiagnosticText.screenField("LNAME10I", "SMITH"))
                    .isEqualTo(DiagnosticText.screenField("LNAME01I", "SMITH"))
                    .isEqualTo(DiagnosticText.OMITTED + ":5");
            assertThat(DiagnosticText.screenField("12345I", "value")).isEqualTo("value");
        }

        @Test
        @DisplayName("The label is matched case-insensitively, and a null label is refused")
        void theLabelIsMatchedCaseInsensitively() {
            assertThat(DiagnosticText.screenField("acctno1o", PAN))
                    .isEqualTo(DiagnosticText.masked(PAN));
            assertThatNullPointerException()
                    .isThrownBy(() -> DiagnosticText.screenField(null, PAN))
                    .withMessageContaining("DFHMDF label is required");
        }

        @Test
        @DisplayName("An absent value is named whichever decision the label reaches")
        void anAbsentValueIsNamed() {
            assertThat(DiagnosticText.screenField("ACCTNO1O", null)).isEqualTo(DiagnosticText.ABSENT);
            assertThat(DiagnosticText.screenField("CRDNAMEI", null)).isEqualTo(DiagnosticText.ABSENT);
            assertThat(DiagnosticText.screenField("PGMNAMEO", null)).isEqualTo(DiagnosticText.ABSENT);
        }
    }

    @Nested
    @DisplayName("Structure - a policy, not a value, and no state to share")
    class Structure {
        @Test
        @DisplayName("It cannot be instantiated, even reflectively")
        void itCannotBeInstantiated() throws ReflectiveOperationException {
            Constructor<DiagnosticText> constructor = DiagnosticText.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> {
                        try {
                            constructor.newInstance();
                        } catch (InvocationTargetException wrapped) {
                            throw new IllegalArgumentException(wrapped.getCause().getMessage());
                        }
                    })
                    .withMessageContaining("policy, not a value");
        }

        @Test
        @DisplayName("Every field is static final, so nothing is shared between requests (gate G53)")
        void everyFieldIsStaticFinal() {
            for (Field field : DiagnosticText.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isStatic(field.getModifiers()))
                        .as("%s is static", field.getName()).isTrue();
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s is final", field.getName()).isTrue();
            }
        }

        @Test
        @DisplayName("The class is final, so the policy cannot be subclassed and weakened")
        void theClassIsFinal() {
            assertThat(Modifier.isFinal(DiagnosticText.class.getModifiers())).isTrue();
        }
    }
}
