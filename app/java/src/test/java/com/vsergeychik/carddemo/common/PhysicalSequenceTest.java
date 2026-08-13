package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves the physical-record ordinal contract: what it accepts, what it refuses, and what it renders.
 */
@DisplayName("PhysicalSequence - the physical-record ordinal a sequential read is ordered by")
class PhysicalSequenceTest {
    private static final String ROW_IDENTIFIER = "_ROWID_";

    @Nested
    @DisplayName("Resolution - required, stripped, and never defaulted")
    class Resolution {
        @Test
        @DisplayName("a configured ordinal resolves to itself")
        void aConfiguredOrdinalResolves() {
            assertThat(PhysicalSequence.of(ROW_IDENTIFIER).expression()).isEqualTo(ROW_IDENTIFIER);
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" resolves to {1}")
        @CsvSource({
            "'  RRN  ', RRN",
            "'RECORD_ORDINAL ', RECORD_ORDINAL",
            "' _ROWID_', _ROWID_",
        })
        @DisplayName("surrounding whitespace is stripped, because a YAML value legitimately carries it")
        void whitespaceIsStripped(String configured, String expected) {
            assertThat(PhysicalSequence.of(configured).expression()).isEqualTo(expected);
        }

        @Test
        @DisplayName("an unset ordinal is refused by name, so a deployment cannot start without one")
        void anUnsetOrdinalIsRefused() {
            assertThatIllegalArgumentException().isThrownBy(() -> PhysicalSequence.of(null))
                    .withMessageContaining(PhysicalSequence.EXPRESSION_PROPERTY)
                    .withMessageContaining("not set");
        }

        @ParameterizedTest(name = "[{index}] a blank value is refused")
        @ValueSource(strings = { "", " ", "   ", "\t", "\n" })
        @DisplayName("a blank ordinal is refused by name too - blank is not a name")
        void aBlankOrdinalIsRefused(String configured) {
            assertThatIllegalArgumentException().isThrownBy(() -> PhysicalSequence.of(configured))
                    .withMessageContaining(PhysicalSequence.EXPRESSION_PROPERTY)
                    .withMessageContaining("blank");
        }

        @Test
        @DisplayName("the canonical constructor applies the grammar too, so the factory cannot be "
                + "bypassed")
        void theCanonicalConstructorIsGuarded() {
            assertThatNullPointerException().isThrownBy(() -> new PhysicalSequence(null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PhysicalSequence("RRN DESC"))
                    .withMessageContaining(PhysicalSequence.EXPRESSION_PROPERTY);
            assertThatIllegalArgumentException().isThrownBy(() -> new PhysicalSequence(""));
        }

        @Test
        @DisplayName("the published property and bean names are the ones the configuration binds")
        void thePublishedNamesAreStable() {
            assertThat(PhysicalSequence.EXPRESSION_PROPERTY)
                    .isEqualTo("carddemo.physical-sequence.expression");
            assertThat(PhysicalSequence.BEAN_NAME).isEqualTo("carddemoPhysicalSequence");
            assertThat(PhysicalSequence.MAX_EXPRESSION_LENGTH).isEqualTo(128);
        }
    }

    @Nested
    @DisplayName("The grammar - a single bare identifier, because the value is rendered unquoted")
    class Grammar {
        @ParameterizedTest(name = "[{index}] {0} is accepted")
        @ValueSource(strings = {
            "_ROWID_",
            "RRN",
            "RECORD_ORDINAL",
            "seq",
            "Seq9",
            "#POS",
            "@ORDINAL",
            "$N",
            "A",
            "_",
            "a1234567890",
        })
        @DisplayName("every shape a real gateway ordinal takes is accepted")
        void legitimateOrdinalsAreAccepted(String configured) {
            assertThat(PhysicalSequence.of(configured).expression()).isEqualTo(configured);
        }

        @Test
        @DisplayName("an ordinal of exactly the maximum length is accepted, and one byte longer is not")
        void theLengthBoundIsInclusive() {
            String longest = "R".repeat(PhysicalSequence.MAX_EXPRESSION_LENGTH);

            assertThat(PhysicalSequence.of(longest).expression()).isEqualTo(longest);
            assertThatIllegalArgumentException().isThrownBy(() -> PhysicalSequence.of(longest + "R"))
                    .withMessageContaining("at most " + PhysicalSequence.MAX_EXPRESSION_LENGTH);
        }

        @ParameterizedTest(name = "[{index}] {0} is refused")
        @ValueSource(strings = {
            "1RRN",
            "9",
            "-RRN",
            ".RRN",
            "\"RRN\"",
            "'RRN'",
        })
        @DisplayName("a value that does not begin with a letter, an underscore or # @ $ is refused")
        void aBadInitialCharacterIsRefused(String configured) {
            assertThatIllegalArgumentException().isThrownBy(() -> PhysicalSequence.of(configured))
                    .withMessageContaining("0-based position 0")
                    .withMessageContaining("a letter or one of");
        }

        @ParameterizedTest(name = "[{index}] {0} is refused")
        @ValueSource(strings = {
            "RRN DESC",
            "RRN ASC",
            "RRN,SEQ",
            "RRN;DROP TABLE X",
            "RRN)",
            "COUNT(*)",
            "RRN--comment",
            "RRN/*x*/",
            "RRN'",
            "RRN\"",
            "RR\nN",
            "T.RRN",
            "RRN+1",
            "RRN=1",
        })
        @DisplayName("nothing that could open a second clause or a second statement gets through")
        void nothingThatCouldExtendTheStatementIsAccepted(String configured) {
            assertThatIllegalArgumentException().isThrownBy(() -> PhysicalSequence.of(configured))
                    .withMessageContaining(PhysicalSequence.EXPRESSION_PROPERTY)
                    .withMessageContaining("0-based position");
        }

        @Test
        @DisplayName("an interior refusal names the offending position, not merely the value")
        void anInteriorRefusalNamesThePosition() {
            assertThatIllegalArgumentException().isThrownBy(() -> PhysicalSequence.of("RR N"))
                    .withMessageContaining("0-based position 2")
                    .withMessageContaining("a letter, a digit or one of");
        }
    }

    @Nested
    @DisplayName("Rendering - ascending, unquoted, and unmistakable in a diagnostic")
    class Rendering {
        @Test
        @DisplayName("the clause is ascending, because a physical ordinal increases with position")
        void theClauseIsAscending() {
            assertThat(PhysicalSequence.of(ROW_IDENTIFIER).orderByClause())
                    .isEqualTo(" ORDER BY _ROWID_ ASC");
        }

        @Test
        @DisplayName("the ordinal is rendered unquoted, because a pseudo-column cannot be delimited")
        void theOrdinalIsRenderedUnquoted() {
            assertThat(PhysicalSequence.of(ROW_IDENTIFIER).orderByClause()).doesNotContain("\"");
        }

        @Test
        @DisplayName("the diagnostic names the ordinal and the key that supplied it")
        void theDiagnosticNamesTheKey() {
            assertThat(PhysicalSequence.of("RRN").describe())
                    .contains("RRN")
                    .contains(PhysicalSequence.EXPRESSION_PROPERTY);
        }

        @Test
        @DisplayName("two ordinals with the same name are the same value")
        void itHasValueSemantics() {
            assertThat(PhysicalSequence.of("RRN")).isEqualTo(PhysicalSequence.of(" RRN "));
            assertThat(PhysicalSequence.of("RRN")).isNotEqualTo(PhysicalSequence.of("SEQ"));
            assertThat(PhysicalSequence.of("RRN").hashCode())
                    .isEqualTo(PhysicalSequence.of("RRN").hashCode());
        }
    }
}
