package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The one implementation of the COBOL numeric-conversion intrinsics, under test.
 *
 * <p>Four programs in this estate convert a character field to a number, and before
 * {@link NumericIntrinsics} existed each carried its own scanner. Collapsing them concentrated the
 * risk: a defect here is now a defect in {@code CSUTLDPY}, {@code COACTUPC}, {@code COTRN02C} and
 * {@code CORPT00C} simultaneously. This suite is therefore written as the gate G29 firewall
 * ("accept and reject exactly as COBOL does") rather than as incidental coverage of a helper.
 *
 * <h2>What this suite is really guarding</h2>
 *
 * <p>The two intrinsics do not share a grammar, and the difference is the thing four separate
 * implementations were most likely to get wrong - and did:
 *
 * <ul>
 *   <li>{@code NUMVAL} admits <strong>no currency sign and no grouping comma</strong>.</li>
 *   <li>{@code NUMVAL-C} admits <strong>both</strong>.</li>
 *   <li>{@code CR} and {@code DB} belong to <strong>both</strong>, which is the half of the
 *       distinction that looks removable and is not.</li>
 * </ul>
 *
 * <p>Every one of those three claims is asserted below in the form "this argument conforms under one
 * intrinsic and does not conform under the other", because an assertion that merely converts a value
 * cannot distinguish a grammar that is right from a grammar that is generous.
 *
 * <h2>Provenance of the expected values</h2>
 *
 * <p>The legacy COBOL cannot be executed in this environment, so these expectations are
 * <em>statically derived</em> from the language definition of the intrinsics and from the four
 * calling programs' guards, not captured from a live run. The digit limit in particular is derived
 * rather than observed: it follows from the absence of any {@code ARITH}, {@code PROCESS} or
 * {@code CBL} option anywhere in {@code app/cbl}, {@code app/jcl}, {@code app/proc} or
 * {@code samples}, which leaves the {@code ARITH(COMPAT)} default in force.
 */
@DisplayName("NumericIntrinsics - FUNCTION NUMVAL, NUMVAL-C and their TEST- companions (gate G29)")
class NumericIntrinsicsTest {

    // =================================================================================================
    // The shape of the holder itself.
    // =================================================================================================

    @Nested
    @DisplayName("the holder is a set of functions, not an object")
    class TheHolder {

        @Test
        @DisplayName("it cannot be instantiated, not even reflectively")
        void cannotBeInstantiatedEvenReflectively() throws ReflectiveOperationException {
            Constructor<NumericIntrinsics> constructor =
                    NumericIntrinsics.class.getDeclaredConstructor();
            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("only one constructor exists, so there is no state-bearing alternative")
        void exposesExactlyOneConstructor() {
            assertThat(NumericIntrinsics.class.getDeclaredConstructors()).hasSize(1);
        }

        @Test
        @DisplayName("the published constants say what the class documents")
        void thePublishedConstants() {
            assertThat(NumericIntrinsics.CONFORMS)
                    .as("zero is not a valid one-based position, which is why it can mean 'no error'")
                    .isZero();
            assertThat(NumericIntrinsics.MAXIMUM_DIGITS)
                    .as("ARITH(COMPAT) - no ARITH, PROCESS or CBL option selects EXTEND anywhere")
                    .isEqualTo(18);
            assertThat(NumericIntrinsics.NON_CONFORMING_VALUE).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // =================================================================================================
    // FUNCTION NUMVAL - the narrower grammar.
    // =================================================================================================

    @Nested
    @DisplayName("FUNCTION NUMVAL")
    class Numval {

        @ParameterizedTest(name = "NUMVAL(\"{0}\") = {1}")
        @CsvSource({
            "'1234',            1234",
            "'0000001234',      1234",
            "'  1234  ',        1234",
            "'+1234',           1234",
            "'-1234',          -1234",
            "'12.34',           12.34",
            "'12.',             12",
            "'.34',             0.34",
            "'1234-',          -1234",
            "'1234 -',         -1234",
            "'1234+',           1234",
            "'1234CR',         -1234",
            "'1234DB',         -1234",
            "'1234 CR ',       -1234",
            "'+ 1234',          1234",
            "'- 12.34',        -12.34",
        })
        @DisplayName("conforming arguments convert exactly, and report no error position")
        void conformingArguments(String image, BigDecimal expected) {
            assertThat(NumericIntrinsics.numval(image)).isEqualByComparingTo(expected);
            assertThat(NumericIntrinsics.testNumval(image)).isEqualTo(NumericIntrinsics.CONFORMS);
            assertThat(NumericIntrinsics.scanNumval(image).conforms()).isTrue();
        }

        @ParameterizedTest(name = "NUMVAL(\"{0}\") does not conform")
        @ValueSource(strings = {"", "   ", "+", "-", ".", "+.", "abc", "12ab", "12.3.4", "12 34",
            "1234CRX", "1234C", "1234D", "1234CD", "+1234-", "-1234+", "1234cr", "1234db"})
        @DisplayName("non-conforming arguments are reported at a positive position and yield zero")
        void nonConformingArguments(String image) {
            assertThat(NumericIntrinsics.testNumval(image))
                    .isNotEqualTo(NumericIntrinsics.CONFORMS)
                    .isPositive();
            assertThat(NumericIntrinsics.numval(image))
                    .isEqualByComparingTo(NumericIntrinsics.NON_CONFORMING_VALUE);
            assertThat(NumericIntrinsics.scanNumval(image).conforms()).isFalse();
        }

        @ParameterizedTest(name = "NUMVAL(\"{0}\") rejects the comma")
        @ValueSource(strings = {"1,234", "1,234,567", ",1", "1,", "1,a", "1.2,3", ",", "1,,234",
            "-1,234", "1,234-"})
        @DisplayName("no comma of any kind is accepted: the grouping comma is a NUMVAL-C extension")
        void theCommaIsNotPartOfThisGrammar(String image) {
            // The defect this class was created to fix. A single scanner shared between the two
            // intrinsics had accepted the grouping comma under both, which made NUMVAL more generous
            // than the language. Asserted as a family rather than as one case, because the previous
            // implementation accepted several of these and rejected others for unrelated reasons.
            assertThat(NumericIntrinsics.testNumval(image)).isPositive();
            assertThat(NumericIntrinsics.numval(image)).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("no currency sign is accepted either")
        void theCurrencySignIsNotPartOfThisGrammar() {
            assertThat(NumericIntrinsics.testNumval("$1234")).isPositive();
            assertThat(NumericIntrinsics.testNumval("-$1234")).isPositive();
            assertThat(NumericIntrinsics.numval("$1234")).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // =================================================================================================
    // FUNCTION NUMVAL-C - the wider grammar.
    // =================================================================================================

    @Nested
    @DisplayName("FUNCTION NUMVAL-C")
    class NumvalC {

        @ParameterizedTest(name = "NUMVAL-C(\"{0}\") = {1}")
        @CsvSource({
            "'1234',            1234",
            "'$1234',           1234",
            "'$ 1234',          1234",
            "'1,234',           1234",
            "'1,234,567',       1234567",
            "'$1,234.56',       1234.56",
            "'- $ 1,234.56',   -1234.56",
            "'$1,234.56CR',    -1234.56",
            "'$1,234.56DB',    -1234.56",
            "'+00000012.34',    12.34",
            "'-00000012.34',   -12.34",
            "'1,234.56-',      -1234.56",
        })
        @DisplayName("conforming arguments convert exactly")
        void conformingArguments(String image, BigDecimal expected) {
            assertThat(NumericIntrinsics.numvalC(image)).isEqualByComparingTo(expected);
            assertThat(NumericIntrinsics.testNumvalC(image)).isEqualTo(NumericIntrinsics.CONFORMS);
        }

        @ParameterizedTest(name = "NUMVAL-C(\"{0}\") does not conform")
        @ValueSource(strings = {"", "$", "$ ", ",", "12$34", "$12X", "1,", "1,a", "1.2,3", ",1",
            "1,,234", "12.3.4", "1234$"})
        @DisplayName("a bare, misplaced or ungrouped separator is still refused")
        void nonConformingArguments(String image) {
            assertThat(NumericIntrinsics.testNumvalC(image)).isPositive();
            assertThat(NumericIntrinsics.numvalC(image)).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("the grouping comma is permitted only between digits of the integer part")
        void theGroupingCommaIsPositional() {
            assertThat(NumericIntrinsics.numvalC("1,234,567"))
                    .isEqualByComparingTo(new BigDecimal("1234567"));
            assertThat(NumericIntrinsics.testNumvalC("1,"))
                    .as("a trailing comma has no following digit")
                    .isPositive();
            assertThat(NumericIntrinsics.testNumvalC("1,a"))
                    .as("a comma must be followed by a digit")
                    .isPositive();
            assertThat(NumericIntrinsics.testNumvalC("1.2,3"))
                    .as("a comma after the decimal point is not a grouping comma")
                    .isPositive();
            assertThat(NumericIntrinsics.testNumvalC(",1"))
                    .as("a comma with no preceding digit is not a grouping comma")
                    .isPositive();
        }

        @Test
        @DisplayName("the currency sign follows the leading sign and precedes the first digit")
        void theCurrencySignIsPositional() {
            assertThat(NumericIntrinsics.testNumvalC("-$1234")).isEqualTo(NumericIntrinsics.CONFORMS);
            assertThat(NumericIntrinsics.testNumvalC("$-1234"))
                    .as("the sign never follows the currency sign")
                    .isPositive();
            assertThat(NumericIntrinsics.testNumvalC("12$34"))
                    .as("the currency sign never appears among the digits")
                    .isPositive();
        }
    }

    // =================================================================================================
    // The differences between the two, stated as differences.
    // =================================================================================================

    @Nested
    @DisplayName("the two grammars differ in exactly two places, and agree everywhere else")
    class TheTwoGrammars {

        @ParameterizedTest(name = "\"{0}\" conforms under NUMVAL-C only")
        @ValueSource(strings = {"$1234", "$ 1234", "1,234", "1,234,567", "$1,234.56",
            "- $ 1,234.56"})
        @DisplayName("the currency sign and the grouping comma are the two NUMVAL-C extensions")
        void theTwoExtensions(String image) {
            assertThat(NumericIntrinsics.testNumvalC(image))
                    .as("NUMVAL-C accepts it")
                    .isEqualTo(NumericIntrinsics.CONFORMS);
            assertThat(NumericIntrinsics.testNumval(image))
                    .as("NUMVAL does not")
                    .isPositive();
        }

        @ParameterizedTest(name = "\"{0}\" conforms under both")
        @ValueSource(strings = {"1234", "-1234", "+1234", "12.34", "1234-", "1234+", "1234CR",
            "1234DB", "  1234  ", "1234 CR "})
        @DisplayName("CR and DB, the signs and the decimal point belong to both intrinsics")
        void theSharedGrammar(String image) {
            assertThat(NumericIntrinsics.testNumval(image)).isEqualTo(NumericIntrinsics.CONFORMS);
            assertThat(NumericIntrinsics.testNumvalC(image)).isEqualTo(NumericIntrinsics.CONFORMS);
            assertThat(NumericIntrinsics.numval(image))
                    .as("and they convert identically, not merely both successfully")
                    .isEqualByComparingTo(NumericIntrinsics.numvalC(image));
        }

        @ParameterizedTest(name = "\"{0}\" conforms under neither")
        @ValueSource(strings = {"", "   ", "+", "-", ".", "abc", "12ab", "12.3.4", "12 34", "1234C",
            "1234CD", "+1234-", "-1234+", ","})
        @DisplayName("neither intrinsic is a superset of the other's rejections")
        void theSharedRejections(String image) {
            assertThat(NumericIntrinsics.testNumval(image)).isPositive();
            assertThat(NumericIntrinsics.testNumvalC(image)).isPositive();
        }
    }

    // =================================================================================================
    // Position reporting - CSUTLDPY moves the reported position into an operator message.
    // =================================================================================================

    @Nested
    @DisplayName("the reported position is IBM's, one-based, with a length-plus-one no-digit case")
    class PositionReporting {

        @ParameterizedTest(name = "\"{0}\" is reported at its length plus one, {1}")
        @CsvSource({
            "'',        1",
            "'   ',     4",
            "'+',       2",
            "'-',       2",
            "'.',       2",
            "'     ',   6",
        })
        @DisplayName("an argument holding no digit is reported at its length plus one")
        void theNoDigitCase(String image, int expected) {
            // There is no offending character to point at, so IBM reports one past the end. Both
            // intrinsics share this rule.
            assertThat(NumericIntrinsics.testNumval(image)).isEqualTo(expected);
            assertThat(NumericIntrinsics.testNumvalC(image)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "NUMVAL(\"{0}\") is reported at {1}")
        @CsvSource({
            "'12ab',    3",
            "'1,234',   2",
            "'$1234',   6",
            "'12 34',   4",
            "'1234C',   5",
            "'12.3.4',  5",
        })
        @DisplayName("otherwise the one-based position of the first character in error is reported")
        void theOffendingCharacter(String image, int expected) {
            // "$1234" is reported at 6 rather than at 1: NUMVAL has no currency-sign position, so the
            // scan finds no digit before the '$', stops there, and the no-digit rule applies - length
            // 5 plus one. That is the language's own consequence rather than a special case.
            assertThat(NumericIntrinsics.testNumval(image)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a rejection carries zero as its value, and the verdict is what distinguishes it")
        void theValueAndTheVerdictTravelTogether() {
            NumericIntrinsics.Scan rejected = NumericIntrinsics.scanNumval("abc");
            NumericIntrinsics.Scan zero = NumericIntrinsics.scanNumval("0");

            assertThat(rejected.value()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(zero.value()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(rejected.conforms()).isFalse();
            assertThat(zero.conforms())
                    .as("the value alone cannot tell \"0\" from \"abc\"; the verdict can")
                    .isTrue();
        }
    }

    // =================================================================================================
    // The ARITH(COMPAT) digit limit.
    // =================================================================================================

    @Nested
    @DisplayName("the argument may not carry more digits than the arithmetic mode allows")
    class TheDigitLimit {

        @Test
        @DisplayName("exactly MAXIMUM_DIGITS digits conform, and convert without loss")
        void theBoundaryConforms() {
            String eighteen = "123456789012345678";
            assertThat(eighteen).hasSize(NumericIntrinsics.MAXIMUM_DIGITS);

            assertThat(NumericIntrinsics.testNumval(eighteen)).isEqualTo(NumericIntrinsics.CONFORMS);
            assertThat(NumericIntrinsics.numval(eighteen))
                    .as("BigDecimal, so every one of the eighteen digits survives")
                    .isEqualByComparingTo(new BigDecimal(eighteen));
        }

        @Test
        @DisplayName("one digit more is refused, at that digit's own position")
        void theBoundaryPlusOneIsRefused() {
            String nineteen = "1234567890123456789";
            assertThat(nineteen).hasSize(NumericIntrinsics.MAXIMUM_DIGITS + 1);

            assertThat(NumericIntrinsics.testNumval(nineteen))
                    .as("the nineteenth digit is the first character that makes it invalid")
                    .isEqualTo(NumericIntrinsics.MAXIMUM_DIGITS + 1);
            assertThat(NumericIntrinsics.numval(nineteen)).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("the limit counts every digit, including leading zeros and fraction digits")
        void theLimitCountsEveryDigitCharacter() {
            // A COBOL argument arrives as a fixed-width item, so leading zeros are digits that were
            // really there. The limit is on the argument's digits, not on the value's magnitude.
            assertThat(NumericIntrinsics.testNumval("000000000000000000"))
                    .as("eighteen zeros are eighteen digits")
                    .isEqualTo(NumericIntrinsics.CONFORMS);
            assertThat(NumericIntrinsics.testNumval("0000000000000000000"))
                    .as("nineteen zeros are one digit too many")
                    .isPositive();
            assertThat(NumericIntrinsics.testNumval("1234567890123456.78"))
                    .as("sixteen integer digits plus two fraction digits is eighteen")
                    .isEqualTo(NumericIntrinsics.CONFORMS);
            assertThat(NumericIntrinsics.testNumval("1234567890123456.789")).isPositive();
        }

        @Test
        @DisplayName("the limit applies to NUMVAL-C too, and grouping commas are not digits")
        void theLimitAppliesToBothIntrinsics() {
            assertThat(NumericIntrinsics.testNumvalC("123,456,789,012,345,678"))
                    .as("eighteen digits with five commas among them")
                    .isEqualTo(NumericIntrinsics.CONFORMS);
            assertThat(NumericIntrinsics.numvalC("123,456,789,012,345,678"))
                    .isEqualByComparingTo(new BigDecimal("123456789012345678"));
            assertThat(NumericIntrinsics.testNumvalC("$1,234,567,890,123,456,789")).isPositive();
        }

        @Test
        @DisplayName("every field the four callers apply these to is far below the limit")
        void theLimitIsUnreachableThroughTheCallers() {
            // Recorded so that a future screen widening is noticed here rather than in production:
            // CSUTLDPY converts a two-character month and day, and the widest field any caller
            // converts is the twelve-character transaction amount.
            assertThat(NumericIntrinsics.testNumval("12")).isEqualTo(NumericIntrinsics.CONFORMS);
            assertThat(NumericIntrinsics.testNumvalC("+00000012.34"))
                    .isEqualTo(NumericIntrinsics.CONFORMS);
            assertThat("+00000012.34".length()).isLessThan(NumericIntrinsics.MAXIMUM_DIGITS);
        }
    }

    // =================================================================================================
    // The Scan pair.
    // =================================================================================================

    @Nested
    @DisplayName("a scan carries the value and the verdict as one object")
    class TheScan {

        @Test
        @DisplayName("one pass answers both questions, so they cannot come from different scans")
        void onePassAnswersBoth() {
            NumericIntrinsics.Scan scan = NumericIntrinsics.scanNumvalC("$1,234.56");

            assertThat(scan.conforms()).isTrue();
            assertThat(scan.errorPosition()).isEqualTo(NumericIntrinsics.CONFORMS);
            assertThat(scan.value()).isEqualByComparingTo(new BigDecimal("1234.56"));
        }

        @Test
        @DisplayName("a null value is refused, so a conforming scan always carries a number")
        void aNullValueIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new NumericIntrinsics.Scan(null, NumericIntrinsics.CONFORMS));
        }

        @Test
        @DisplayName("a negative position is refused, because a reported position is one-based")
        void aNegativePositionIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new NumericIntrinsics.Scan(BigDecimal.ZERO, -1))
                    .withMessageContaining("one-based");
        }

        @Test
        @DisplayName("a scan is a value: equal components make equal scans")
        void aScanIsAValue() {
            assertThat(NumericIntrinsics.scanNumval("12"))
                    .isEqualTo(NumericIntrinsics.scanNumvalC("12"))
                    .hasSameHashCodeAs(NumericIntrinsics.scanNumvalC("12"));
            assertThat(NumericIntrinsics.scanNumval("12")).hasToString(
                    NumericIntrinsics.scanNumvalC("12").toString());
        }
    }

    // =================================================================================================
    // Null arguments.
    // =================================================================================================

    @Nested
    @DisplayName("a null argument is a Java defect, not a value")
    class NullArguments {

        @Test
        @DisplayName("all six accessors refuse null rather than reading it as zero")
        void allSixAccessorsRefuseNull() {
            // A COBOL item is never absent, so there is no COBOL behaviour to be faithful to here and
            // treating null as zero would hide the defect that produced it.
            assertThatNullPointerException().isThrownBy(() -> NumericIntrinsics.numval(null));
            assertThatNullPointerException().isThrownBy(() -> NumericIntrinsics.testNumval(null));
            assertThatNullPointerException().isThrownBy(() -> NumericIntrinsics.numvalC(null));
            assertThatNullPointerException().isThrownBy(() -> NumericIntrinsics.testNumvalC(null));
            assertThatNullPointerException().isThrownBy(() -> NumericIntrinsics.scanNumval(null));
            assertThatNullPointerException().isThrownBy(() -> NumericIntrinsics.scanNumvalC(null));
        }
    }
}
