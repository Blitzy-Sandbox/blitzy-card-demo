package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The numeric-parity seam of the CardDemo COBOL to Java 21 migration, under test.
 */
@DisplayName("CobolDecimal - the numeric-parity seam: truncation, scale 2, and nothing else")
class CobolDecimalTest {
    private static final BigDecimal SAMPLE_CATEGORY_BALANCE = new BigDecimal("1000.00");

    private static final BigDecimal SAMPLE_INTEREST_RATE = new BigDecimal("12.50");

    private static final int NINE_DIGIT_PRECISION = 9;

    private static final int TEN_DIGIT_PRECISION = 10;

    @Nested
    @DisplayName("Constants and policy - the rounding mode is the whole ballgame")
    class ConstantsAndPolicy {
        @Test
        @DisplayName("MONETARY_SCALE is 2, because the V-scale scan returns only V99, 34 times")
        void monetaryScaleIsTwo() {
            assertThat(CobolDecimal.MONETARY_SCALE).isEqualTo(2);
        }

        @Test
        @DisplayName("COBOL_ROUNDING is exactly DOWN, because ROUNDED occurs zero times in 28 programs")
        void roundingModeIsDown() {
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);
        }

        @Test
        @DisplayName("COBOL_ROUNDING is none of the modes that would silently break parity")
        void roundingModeIsNoneOfTheParityBreakingModes() {
            assertThat(CobolDecimal.COBOL_ROUNDING)
                    .isNotEqualTo(RoundingMode.HALF_UP)
                    .isNotEqualTo(RoundingMode.HALF_EVEN)
                    .isNotEqualTo(RoundingMode.CEILING)
                    .isNotEqualTo(RoundingMode.FLOOR);
        }

        @Test
        @DisplayName("COBOL_ROUNDING is none of the remaining JDK modes either")
        void roundingModeIsNoneOfTheOtherJdkModes() {
            assertThat(CobolDecimal.COBOL_ROUNDING)
                    .isNotIn(RoundingMode.UP, RoundingMode.HALF_DOWN, RoundingMode.UNNECESSARY);
        }

        @Test
        @DisplayName("MONTHLY_INTEREST_DIVISOR is the literal 1200 written at CBACT04C.cbl:L465")
        void interestDivisorIsTwelveHundred() {
            assertThat(CobolDecimal.MONTHLY_INTEREST_DIVISOR).isEqualTo(1200L);
        }

        @Test
        @DisplayName("the class is final, so no subclass can add state to it")
        void theClassIsFinal() {
            assertThat(Modifier.isFinal(CobolDecimal.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("the class cannot be instantiated, not even reflectively")
        void cannotBeInstantiatedEvenReflectively() throws ReflectiveOperationException {
            Constructor<CobolDecimal> constructor = CobolDecimal.class.getDeclaredConstructor();
            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class)
                    .havingCause()
                    .withMessage("CobolDecimal is a non-instantiable utility holder");
        }

        @Test
        @DisplayName("only a single constructor exists, so there is no state-bearing alternative")
        void exposesExactlyOneConstructor() {
            assertThat(CobolDecimal.class.getDeclaredConstructors()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("store - truncation toward zero on the receiving field's scale")
    class Store {
        @Test
        @DisplayName("excess fraction digits are truncated, not rounded: 10.419 stores as 10.41")
        void truncatesExcessFractionDigits() {
            assertThat(CobolDecimal.store(new BigDecimal("10.419"), 2))
                    .isEqualTo(new BigDecimal("10.41"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a half-way value truncates down: 10.415 stores as 10.41, proving the mode")
        void truncatesAHalfWayValueDownwards() {
            BigDecimal stored = CobolDecimal.store(new BigDecimal("10.415"), 2);

            assertThat(stored).isEqualTo(new BigDecimal("10.41")).hasScaleOf(2);
            assertThat(stored).isNotEqualTo(new BigDecimal("10.42"));
            assertThat(new BigDecimal("10.415").setScale(2, RoundingMode.HALF_UP))
                    .isEqualTo(new BigDecimal("10.42"));
            assertThat(new BigDecimal("10.415").setScale(2, RoundingMode.HALF_EVEN))
                    .isEqualTo(new BigDecimal("10.42"));
        }

        @Test
        @DisplayName("a negative value truncates toward zero: -10.419 stores as -10.41")
        void truncatesNegativeValueTowardZero() {
            assertThat(CobolDecimal.store(new BigDecimal("-10.419"), 2))
                    .isEqualTo(new BigDecimal("-10.41"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("-10.415 stores as -10.41, the only case that distinguishes DOWN from FLOOR")
        void truncatesNegativeHalfWayValueTowardZeroRatherThanDownwards() {
            BigDecimal stored = CobolDecimal.store(new BigDecimal("-10.415"), 2);

            assertThat(stored).isEqualTo(new BigDecimal("-10.41")).hasScaleOf(2);
            assertThat(stored).isNotEqualTo(new BigDecimal("-10.42"));
            assertThat(new BigDecimal("-10.415").setScale(2, RoundingMode.FLOOR))
                    .isEqualTo(new BigDecimal("-10.42"));
        }

        @ParameterizedTest(name = "storing {0} at scale 2 yields {1}")
        @CsvSource({
            "10.419,    10.41",
            "10.415,    10.41",
            "10.411,    10.41",
            "1.239,     1.23",
            "1.999,     1.99",
            "-10.419,   -10.41",
            "-10.415,   -10.41",
            "-1.239,    -1.23",
            "-1.999,    -1.99",
            "0.009,     0.00",
            "-0.009,    0.00",
            "10.416666, 10.41",
        })
        @DisplayName("truncation is symmetric about zero at every representative magnitude")
        void truncatesTowardZeroSymmetrically(String input, String expected) {
            assertThat(CobolDecimal.store(new BigDecimal(input), 2))
                    .isEqualTo(new BigDecimal(expected))
                    .hasScaleOf(2);
        }

        @ParameterizedTest(name = "an exact scale-2 value {0} passes through unchanged")
        @ValueSource(strings = {"0.00", "1.00", "123.45", "-123.45", "9999999999.99"})
        @DisplayName("a value already at the receiver's scale is returned unchanged, scale included")
        void passesExactValuesThroughUnchanged(String exactValue) {
            BigDecimal input = new BigDecimal(exactValue);

            BigDecimal stored = CobolDecimal.store(input, 2);

            assertThat(stored).isEqualTo(input).hasScaleOf(2);
            assertThat(stored.toPlainString()).isEqualTo(exactValue);
        }

        @ParameterizedTest(name = "storing {0} at scale 2 raises the scale to yield {1}")
        @CsvSource({
            "7,     7.00",
            "7.5,   7.50",
            "42,    42.00",
            "-7,    -7.00",
            "-7.5,  -7.50",
            "0,     0.00",
        })
        @DisplayName("scaling up pads with trailing zeros, which the fixed-width writer needs")
        void raisesScaleByPaddingWithZeros(String input, String expected) {
            assertThat(CobolDecimal.store(new BigDecimal(input), 2))
                    .isEqualTo(new BigDecimal(expected))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a scale of zero is permitted, for the unscaled PIC 9(n) fields")
        void supportsScaleZero() {
            assertThat(CobolDecimal.store(new BigDecimal("42.99"), 0))
                    .isEqualTo(new BigDecimal("42"))
                    .hasScaleOf(0);
        }

        @Test
        @DisplayName("a scale wider than the input is honoured, up to a report-sized scale")
        void supportsAWideScale() {
            assertThat(CobolDecimal.store(new BigDecimal("1.5"), 6))
                    .isEqualTo(new BigDecimal("1.500000"))
                    .hasScaleOf(6);
        }

        @Test
        @DisplayName("storeMonetary applies scale 2 without the caller naming it")
        void storeMonetaryUsesTheMonetaryScale() {
            assertThat(CobolDecimal.storeMonetary(new BigDecimal("7.019")))
                    .isEqualTo(new BigDecimal("7.01"))
                    .hasScaleOf(CobolDecimal.MONETARY_SCALE);
        }

        @Test
        @DisplayName("storeMonetary agrees with store at MONETARY_SCALE for the same input")
        void storeMonetaryAgreesWithStoreAtTheMonetaryScale() {
            BigDecimal value = new BigDecimal("-10.415");

            assertThat(CobolDecimal.storeMonetary(value))
                    .isEqualTo(CobolDecimal.store(value, CobolDecimal.MONETARY_SCALE));
        }

        @Test
        @DisplayName("a null value is rejected by store, naming the offending parameter")
        void rejectsNullValue() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.store(null, 2))
                    .withMessage("value must not be null");
        }

        @Test
        @DisplayName("a null value is rejected by storeMonetary too")
        void storeMonetaryRejectsNullValue() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.storeMonetary(null))
                    .withMessage("value must not be null");
        }

        @Test
        @DisplayName("a negative scale is rejected, since no PICTURE clause declares one")
        void rejectsNegativeScale() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CobolDecimal.store(BigDecimal.ONE, -1))
                    .withMessage("scale must not be negative, but was -1"
                            + "; a COBOL PICTURE clause never declares a negative scale");
        }
    }

    @Nested
    @DisplayName("storeAtPicture - silent truncation at BOTH ends, because ON SIZE ERROR is absent")
    class StoreAtPicture {
        @Test
        @DisplayName("a value that fits has only its fraction truncated")
        void passesThroughAValueThatFits() {
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("1234.5678"),
                    TEN_DIGIT_PRECISION, 2))
                    .isEqualTo(new BigDecimal("1234.56"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a value exactly at the S9(10)V99 boundary loses nothing")
        void passesThroughAValueExactlyAtTheBoundary() {
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("9999999999.99"),
                    TEN_DIGIT_PRECISION, 2))
                    .isEqualTo(new BigDecimal("9999999999.99"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("excess high-order digits are dropped silently for an S9(09)V99 receiver")
        void discardsHighOrderDigitsForNineDigitReceiver() {
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("1234567890.12"),
                    NINE_DIGIT_PRECISION, 2))
                    .isEqualTo(new BigDecimal("234567890.12"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("the negative counterpart keeps the sign of the computed value")
        void discardsHighOrderDigitsPreservingSign() {
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("-1234567890.12"),
                    NINE_DIGIT_PRECISION, 2))
                    .isEqualTo(new BigDecimal("-234567890.12"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("excess high-order digits are dropped for an S9(10)V99 receiver too")
        void discardsHighOrderDigitsForTenDigitReceiver() {
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("12345678901.23"),
                    TEN_DIGIT_PRECISION, 2))
                    .isEqualTo(new BigDecimal("2345678901.23"))
                    .hasScaleOf(2);
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("-12345678901.23"),
                    TEN_DIGIT_PRECISION, 2))
                    .isEqualTo(new BigDecimal("-2345678901.23"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("overflow never throws, no matter how far the value exceeds the receiver")
        void overflowNeverThrows() {
            BigDecimal wildlyOversized = new BigDecimal("999999999999999999999.99");

            assertThat(CobolDecimal.storeAtPicture(wildlyOversized, NINE_DIGIT_PRECISION, 2))
                    .isEqualTo(new BigDecimal("999999999.99"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a value whose surviving digits are all zero wraps to 0.00, not to a maximum")
        void wrapsToZeroWhenEverySurvivingDigitIsZero() {
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("1000000000.00"),
                    NINE_DIGIT_PRECISION, 2))
                    .isEqualTo(new BigDecimal("0.00"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("both ends truncate together: the fraction and the high-order digits at once")
        void truncatesBothEndsInOneOperation() {
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("98765.4321"), 4, 2))
                    .isEqualTo(new BigDecimal("8765.43"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("the fraction is truncated before the overflow test, never rounded into it")
        void truncatesTheFractionBeforeTestingForOverflow() {
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("9999999999.999"),
                    NINE_DIGIT_PRECISION, 2))
                    .isEqualTo(new BigDecimal("999999999.99"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a value below one fits any receiver, though its precision is under its scale")
        void handlesValuesBelowOne() {
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("0.419"),
                    NINE_DIGIT_PRECISION, 2))
                    .isEqualTo(new BigDecimal("0.41"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("zero fits any receiver and takes the receiver's scale")
        void handlesZero() {
            assertThat(CobolDecimal.storeAtPicture(BigDecimal.ZERO, TEN_DIGIT_PRECISION, 2))
                    .isEqualTo(new BigDecimal("0.00"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("zero integer digits models PIC SV99, retaining only the fraction")
        void zeroIntegerDigitsRetainsOnlyTheFraction() {
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("10.419"), 0, 2))
                    .isEqualTo(new BigDecimal("0.41"))
                    .hasScaleOf(2);
        }

        @ParameterizedTest(name = "PIC S9({1})V99 storing {0} yields {2}")
        @CsvSource({
            "9999999999.99, 10, 9999999999.99",
            "999999999.99,   9, 999999999.99",
            "9999.99,        4, 9999.99",
            "12345678901.23, 10, 2345678901.23",
            "1234567890.12,   9, 234567890.12",
            "12345.67,        4, 2345.67",
            "-12345678901.23, 10, -2345678901.23",
            "-1234567890.12,   9, -234567890.12",
            "-12345.67,        4, -2345.67",
        })
        @DisplayName("silent overflow behaviour holds across every declared integer precision")
        void wrapsAtEveryDeclaredPrecision(String input, int integerDigits, String expected) {
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal(input), integerDigits, 2))
                    .isEqualTo(new BigDecimal(expected))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a null value is rejected")
        void rejectsNullValue() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.storeAtPicture(null, TEN_DIGIT_PRECISION, 2))
                    .withMessage("value must not be null");
        }

        @Test
        @DisplayName("negative integer precision is rejected")
        void rejectsNegativeIntegerDigits() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CobolDecimal.storeAtPicture(BigDecimal.ONE, -1, 2))
                    .withMessage("integerDigits must not be negative, but was -1"
                            + "; a COBOL PICTURE clause never declares negative precision");
        }

        @Test
        @DisplayName("a negative scale is rejected")
        void rejectsNegativeScale() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CobolDecimal.storeAtPicture(BigDecimal.ONE,
                            TEN_DIGIT_PRECISION, -1))
                    .withMessage("scale must not be negative, but was -1"
                            + "; a COBOL PICTURE clause never declares a negative scale");
        }
    }

    @Nested
    @DisplayName("zero and monetaryZero - the MOVE 0 factories")
    class Zero {
        @Test
        @DisplayName("zero(2) renders as 0.00, so the byte image keeps both decimal positions")
        void zeroAtScaleTwoRendersWithBothDecimals() {
            BigDecimal zero = CobolDecimal.zero(2);

            assertThat(zero).hasScaleOf(2);
            assertThat(zero.toPlainString()).isEqualTo("0.00");
            assertThat(zero.signum()).isZero();
        }

        @Test
        @DisplayName("monetaryZero matches zero(MONETARY_SCALE)")
        void monetaryZeroUsesTheMonetaryScale() {
            assertThat(CobolDecimal.monetaryZero())
                    .isEqualTo(CobolDecimal.zero(CobolDecimal.MONETARY_SCALE))
                    .hasScaleOf(2);
            assertThat(CobolDecimal.monetaryZero().toPlainString()).isEqualTo("0.00");
        }

        @Test
        @DisplayName("the scaled zero is deliberately NOT equal to BigDecimal.ZERO")
        void isNotEqualToUnscaledZero() {
            assertThat(CobolDecimal.monetaryZero()).isNotEqualTo(BigDecimal.ZERO);
            assertThat(CobolDecimal.monetaryZero()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("CBACT04C.cbl:L353-L354 - both cycle-amount resets yield scale-2 zero")
        void reproducesTheCycleAmountResets() {
            BigDecimal cycleCredit = CobolDecimal.monetaryZero();
            BigDecimal cycleDebit = CobolDecimal.monetaryZero();

            assertThat(cycleCredit).hasScaleOf(2);
            assertThat(cycleDebit).hasScaleOf(2);
            assertThat(cycleCredit.toPlainString()).isEqualTo("0.00");
            assertThat(cycleDebit.toPlainString()).isEqualTo("0.00");
        }

        @Test
        @DisplayName("CBACT04C.cbl:L200 - the per-account accumulator reset is scale-2 zero")
        void reproducesTheAccumulatorReset() {
            assertThat(CobolDecimal.monetaryZero())
                    .isEqualTo(new BigDecimal("0.00"))
                    .hasScaleOf(2);
        }

        @ParameterizedTest(name = "zero at scale {0} keeps that scale")
        @ValueSource(ints = {0, 1, 2, 4, 6})
        @DisplayName("any non-negative scale is honoured, including scale 0")
        void honoursAnyNonNegativeScale(int scale) {
            assertThat(CobolDecimal.zero(scale)).hasScaleOf(scale).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("zero(0) renders as a bare 0, matching an unscaled PIC 9(n) field")
        void zeroAtScaleZeroRendersWithoutADecimalPoint() {
            assertThat(CobolDecimal.zero(0).toPlainString()).isEqualTo("0");
        }

        @Test
        @DisplayName("a negative scale is rejected")
        void rejectsNegativeScale() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CobolDecimal.zero(-1))
                    .withMessage("scale must not be negative, but was -1"
                            + "; a COBOL PICTURE clause never declares a negative scale");
        }
    }

    @Nested
    @DisplayName("add and subtract - the 51 ADD and 11 SUBTRACT sites")
    class AddAndSubtract {
        @Test
        @DisplayName("adding to a scaled zero preserves both the value and the scale")
        void addPreservesScale() {
            BigDecimal sum = CobolDecimal.add(CobolDecimal.monetaryZero(),
                    new BigDecimal("10.41"), 2);

            assertThat(sum).isEqualTo(new BigDecimal("10.41")).hasScaleOf(2);
        }

        @Test
        @DisplayName("addition of two scale-2 operands is exact")
        void addIsExact() {
            assertThat(CobolDecimal.add(new BigDecimal("1234.56"), new BigDecimal("0.44"), 2))
                    .isEqualTo(new BigDecimal("1235.00"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a sum carrying more fraction than the receiver is truncated, not rounded")
        void addTruncatesToTheReceiverScale() {
            assertThat(CobolDecimal.add(new BigDecimal("1.005"), new BigDecimal("0.006"), 2))
                    .isEqualTo(new BigDecimal("1.01"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a half-way sum truncates down rather than rounding up")
        void addTruncatesAHalfWaySumDownwards() {
            BigDecimal sum = CobolDecimal.add(new BigDecimal("1.230"), new BigDecimal("0.005"), 2);

            assertThat(sum).isEqualTo(new BigDecimal("1.23"));
            assertThat(sum).isNotEqualTo(new BigDecimal("1.24"));
        }

        @Test
        @DisplayName("addition handles a negative addend, reducing the running value")
        void addHandlesNegativeOperands() {
            assertThat(CobolDecimal.add(new BigDecimal("10.00"), new BigDecimal("-25.50"), 2))
                    .isEqualTo(new BigDecimal("-25.50").add(new BigDecimal("10.00")))
                    .isEqualTo(new BigDecimal("-15.50"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("addition of operands with differing scales stores at the receiver's scale")
        void addNormalisesDifferingOperandScales() {
            assertThat(CobolDecimal.add(new BigDecimal("10.4"), new BigDecimal("0.019"), 2))
                    .isEqualTo(new BigDecimal("10.41"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("subtraction of two scale-2 operands is exact")
        void subtractIsExact() {
            assertThat(CobolDecimal.subtract(new BigDecimal("1000.00"),
                    new BigDecimal("250.75"), 2))
                    .isEqualTo(new BigDecimal("749.25"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("subtraction may cross zero into a negative balance")
        void subtractMayGoNegative() {
            assertThat(CobolDecimal.subtract(new BigDecimal("100.00"),
                    new BigDecimal("150.00"), 2))
                    .isEqualTo(new BigDecimal("-50.00"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a difference carrying more fraction than the receiver is truncated")
        void subtractTruncatesToTheReceiverScale() {
            assertThat(CobolDecimal.subtract(new BigDecimal("1.009"),
                    new BigDecimal("0.001"), 2))
                    .isEqualTo(new BigDecimal("1.00"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a negative difference truncates toward zero, not away from it")
        void subtractTruncatesNegativeDifferenceTowardZero() {
            BigDecimal difference =
                    CobolDecimal.subtract(new BigDecimal("0.00"), new BigDecimal("0.019"), 2);

            assertThat(difference).isEqualTo(new BigDecimal("-0.01"));
            assertThat(difference).isNotEqualTo(new BigDecimal("-0.02"));
        }

        @Test
        @DisplayName("null operands are rejected on add, each naming its own parameter")
        void addRejectsNulls() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.add(null, BigDecimal.ONE, 2))
                    .withMessage("augend must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.add(BigDecimal.ONE, null, 2))
                    .withMessage("addend must not be null");
        }

        @Test
        @DisplayName("null operands are rejected on subtract, each naming its own parameter")
        void subtractRejectsNulls() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.subtract(null, BigDecimal.ONE, 2))
                    .withMessage("minuend must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.subtract(BigDecimal.ONE, null, 2))
                    .withMessage("subtrahend must not be null");
        }

        @Test
        @DisplayName("a negative scale is rejected on add")
        void addRejectsNegativeScale() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CobolDecimal.add(BigDecimal.ONE, BigDecimal.ONE, -1))
                    .withMessageContaining("scale must not be negative");
        }

        @Test
        @DisplayName("a negative scale is rejected on subtract")
        void subtractRejectsNegativeScale() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CobolDecimal.subtract(BigDecimal.ONE, BigDecimal.ONE, -1))
                    .withMessageContaining("scale must not be negative");
        }
    }

    @Nested
    @DisplayName("multiplyAndStore and divide - exact intermediates, one truncation")
    class MultiplyAndDivide {
        @Test
        @DisplayName("multiplyAndStore forms the exact product, then truncates exactly once")
        void multiplyAndStoreTruncatesOnce() {
            assertThat(CobolDecimal.multiplyAndStore(SAMPLE_CATEGORY_BALANCE,
                    SAMPLE_INTEREST_RATE, 2))
                    .isEqualTo(new BigDecimal("12500.00"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("the exact product of two scale-2 operands carries scale 4")
        void theExactProductCarriesTheSumOfTheOperandScales() {
            BigDecimal exactProduct = SAMPLE_CATEGORY_BALANCE.multiply(SAMPLE_INTEREST_RATE);

            assertThat(exactProduct).isEqualTo(new BigDecimal("12500.0000")).hasScaleOf(4);
        }

        @Test
        @DisplayName("multiplyAndStore truncates a product with more fraction than the receiver")
        void multiplyAndStoreTruncatesExcessFraction() {
            assertThat(CobolDecimal.multiplyAndStore(new BigDecimal("1.11"),
                    new BigDecimal("1.11"), 2))
                    .isEqualTo(new BigDecimal("1.23"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("multiplyAndStore keeps the full product when the receiver is wide enough")
        void multiplyAndStoreKeepsTheFullProductAtScaleFour() {
            assertThat(CobolDecimal.multiplyAndStore(new BigDecimal("1.11"),
                    new BigDecimal("1.11"), 4))
                    .isEqualTo(new BigDecimal("1.2321"))
                    .hasScaleOf(4);
        }

        @Test
        @DisplayName("multiplyAndStore preserves the sign of a single negative factor")
        void multiplyAndStoreHandlesNegativeFactor() {
            assertThat(CobolDecimal.multiplyAndStore(new BigDecimal("-2.50"),
                    new BigDecimal("4.00"), 2))
                    .isEqualTo(new BigDecimal("-10.00"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("multiplyAndStore truncates a negative product toward zero")
        void multiplyAndStoreTruncatesNegativeProductTowardZero() {
            assertThat(CobolDecimal.multiplyAndStore(new BigDecimal("-1.11"),
                    new BigDecimal("1.11"), 2))
                    .isEqualTo(new BigDecimal("-1.23"));
        }

        @Test
        @DisplayName("a non-terminating quotient truncates instead of throwing: 100.00 / 3 = 33.33")
        void divideDoesNotThrowOnNonTerminatingQuotient() {
            assertThat(CobolDecimal.divide(new BigDecimal("100.00"), new BigDecimal("3"), 2))
                    .isEqualTo(new BigDecimal("33.33"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("the helper supplies scale AND mode, where scale-free BigDecimal.divide throws")
        void divideAlwaysSuppliesAnExplicitScaleAndMode() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> BigDecimal.ONE.divide(new BigDecimal("1200")))
                    .withMessageContaining("Non-terminating decimal expansion");

            assertThat(CobolDecimal.divide(BigDecimal.ONE, new BigDecimal("1200"), 2))
                    .isEqualTo(new BigDecimal("0.00"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("no divide overload exists that omits the scale, so the mode cannot be dodged")
        void exposesNoScaleFreeDivideOverload() {
            assertThat(CobolDecimal.class.getDeclaredMethods())
                    .filteredOn(method -> "divide".equals(method.getName()))
                    .hasSize(1)
                    .allSatisfy(method -> assertThat(method.getParameterTypes())
                            .containsExactly(BigDecimal.class, BigDecimal.class, int.class));

            assertThat(CobolDecimal.class.getDeclaredMethods())
                    .noneSatisfy(method -> assertThat(method.getParameterTypes())
                            .contains(RoundingMode.class));
        }

        @Test
        @DisplayName("a negative non-terminating quotient truncates toward zero, not downward")
        void divideTruncatesNegativeQuotientTowardZero() {
            BigDecimal quotient =
                    CobolDecimal.divide(new BigDecimal("-100.00"), new BigDecimal("3"), 2);

            assertThat(quotient).isEqualTo(new BigDecimal("-33.33"));
            assertThat(quotient).isNotEqualTo(new BigDecimal("-33.34"));
        }

        @Test
        @DisplayName("an exact quotient is unaffected by the rounding mode")
        void divideHandlesExactQuotient() {
            assertThat(CobolDecimal.divide(new BigDecimal("100.00"), new BigDecimal("4"), 2))
                    .isEqualTo(new BigDecimal("25.00"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a zero divisor raises a clear ArithmeticException naming the condition")
        void divideRejectsZeroDivisor() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> CobolDecimal.divide(BigDecimal.ONE, BigDecimal.ZERO, 2))
                    .withMessageContaining("COBOL divide by zero")
                    .withMessageContaining("dividend was 1");
        }

        @Test
        @DisplayName("a scaled zero divisor is caught too, which equals(ZERO) would have missed")
        void divideRejectsScaledZeroDivisor() {
            BigDecimal scaledZero = new BigDecimal("0.00");
            assertThat(scaledZero).isNotEqualTo(BigDecimal.ZERO);
            assertThat(scaledZero).isEqualByComparingTo(BigDecimal.ZERO);

            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> CobolDecimal.divide(BigDecimal.ONE, scaledZero, 2))
                    .withMessageContaining("COBOL divide by zero");
        }

        @Test
        @DisplayName("a negative divisor is accepted and the sign propagates")
        void divideHandlesNegativeDivisor() {
            assertThat(CobolDecimal.divide(new BigDecimal("100.00"), new BigDecimal("-4"), 2))
                    .isEqualTo(new BigDecimal("-25.00"));
        }

        @Test
        @DisplayName("null operands are rejected on divide, each naming its own parameter")
        void divideRejectsNulls() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.divide(null, BigDecimal.ONE, 2))
                    .withMessage("dividend must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.divide(BigDecimal.ONE, null, 2))
                    .withMessage("divisor must not be null");
        }

        @Test
        @DisplayName("null factors are rejected on multiplyAndStore")
        void multiplyAndStoreRejectsNulls() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.multiplyAndStore(null, BigDecimal.ONE, 2))
                    .withMessage("multiplicand must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.multiplyAndStore(BigDecimal.ONE, null, 2))
                    .withMessage("multiplier must not be null");
        }

        @Test
        @DisplayName("a negative scale is rejected on divide and on multiplyAndStore")
        void rejectsNegativeScale() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CobolDecimal.divide(BigDecimal.ONE, BigDecimal.TEN, -1))
                    .withMessageContaining("scale must not be negative");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CobolDecimal.multiplyAndStore(BigDecimal.ONE,
                            BigDecimal.TEN, -1))
                    .withMessageContaining("scale must not be negative");
        }
    }

    @Nested
    @DisplayName("multiplyThenDivide - the COMPUTE product-over-literal shape, truncated once")
    class MultiplyThenDivide {
        @Test
        @DisplayName("the intermediate product stays exact, so the store is the only truncation")
        void formsTheProductExactlyAndTruncatesOnlyOnTheStore() {
            BigDecimal storeOnce = CobolDecimal.multiplyThenDivide(new BigDecimal("1.11"),
                    new BigDecimal("1.11"), 3L, 4);
            BigDecimal truncatedIntermediate = CobolDecimal.divide(
                    CobolDecimal.multiplyAndStore(new BigDecimal("1.11"), new BigDecimal("1.11"), 2),
                    new BigDecimal("3"), 4);

            assertThat(storeOnce).isEqualTo(new BigDecimal("0.4107")).hasScaleOf(4);
            assertThat(truncatedIntermediate).isEqualTo(new BigDecimal("0.4100"));
            assertThat(storeOnce).isNotEqualTo(truncatedIntermediate);
        }

        @Test
        @DisplayName("a sub-cent product survives the division rather than being flattened early")
        void keepsASubCentProductAliveAcrossTheDivision() {
            assertThat(CobolDecimal.multiplyThenDivide(new BigDecimal("0.01"),
                    new BigDecimal("0.01"), 1L, 4))
                    .isEqualTo(new BigDecimal("0.0001"))
                    .hasScaleOf(4);
        }

        @Test
        @DisplayName("the quotient is truncated to the receiver's scale")
        void truncatesToTheReceiverScale() {
            assertThat(CobolDecimal.multiplyThenDivide(new BigDecimal("100.00"),
                    new BigDecimal("1.00"), 3L, 2))
                    .isEqualTo(new BigDecimal("33.33"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a negative divisor is accepted and the sign propagates")
        void handlesNegativeDivisor() {
            assertThat(CobolDecimal.multiplyThenDivide(new BigDecimal("100.00"),
                    new BigDecimal("1.00"), -4L, 2))
                    .isEqualTo(new BigDecimal("-25.00"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a zero divisor raises the same clear ArithmeticException as divide")
        void rejectsZeroDivisor() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> CobolDecimal.multiplyThenDivide(BigDecimal.ONE,
                            BigDecimal.ONE, 0L, 2))
                    .withMessageContaining("COBOL divide by zero");
        }

        @Test
        @DisplayName("null factors are rejected, each naming its own parameter")
        void rejectsNulls() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.multiplyThenDivide(null, BigDecimal.ONE, 1L, 2))
                    .withMessage("multiplicand must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.multiplyThenDivide(BigDecimal.ONE, null, 1L, 2))
                    .withMessage("multiplier must not be null");
        }

        @Test
        @DisplayName("a negative scale is rejected")
        void rejectsNegativeScale() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CobolDecimal.multiplyThenDivide(BigDecimal.ONE,
                            BigDecimal.ONE, 1L, -1))
                    .withMessageContaining("scale must not be negative");
        }
    }

    @Nested
    @DisplayName("monthlyInterest - CBACT04C.cbl:L464-L465, the canonical parity assertion")
    class MonthlyInterest {
        @Test
        @DisplayName("1000.00 at 12.50% yields 10.41, and never 10.42")
        void reproducesTheWorkedExample() {
            BigDecimal interest =
                    CobolDecimal.monthlyInterest(SAMPLE_CATEGORY_BALANCE, SAMPLE_INTEREST_RATE);

            assertThat(interest).isEqualTo(new BigDecimal("10.41")).hasScaleOf(2);
            assertThat(interest).isNotEqualTo(new BigDecimal("10.42"));
        }

        @Test
        @DisplayName("the result equals an explicit multiply-then-divide by 1200 at scale 2")
        void agreesWithTheExplicitFormula() {
            assertThat(CobolDecimal.monthlyInterest(SAMPLE_CATEGORY_BALANCE, SAMPLE_INTEREST_RATE))
                    .isEqualTo(CobolDecimal.multiplyThenDivide(SAMPLE_CATEGORY_BALANCE,
                            SAMPLE_INTEREST_RATE, CobolDecimal.MONTHLY_INTEREST_DIVISOR,
                            CobolDecimal.MONETARY_SCALE));
        }

        @ParameterizedTest(name = "TRAN-CAT-BAL {0} at DIS-INT-RATE {1} yields {2}")
        @CsvSource({
            "1000.00,   12.50,  10.41",
            "100.00,     5.00,   0.41",
            "-1000.00,  12.50, -10.41",
            "2500.75,   19.99,  41.65",
            "0.01,       1.00,   0.00",
            "1000.00,    0.00,   0.00",
            "0.00,      12.50,   0.00",
            "-2500.75,  19.99, -41.65",
            "-0.01,      1.00,   0.00",
            "100.00,    24.99,   2.08",
            "1.00,      11.99,   0.00",
            "10.00,     11.99,   0.09",
            "500.00,    18.00,   7.50",
        })
        @DisplayName("the formula truncates to 2 dp across the representative input range")
        void truncatesAcrossTheInputRange(String balance, String rate, String expected) {
            BigDecimal interest =
                    CobolDecimal.monthlyInterest(new BigDecimal(balance), new BigDecimal(rate));

            assertThat(interest).isEqualTo(new BigDecimal(expected)).hasScaleOf(2);
        }

        @ParameterizedTest(name = "{0} at {1} is not the rounded answer {2}")
        @CsvSource({
            "1000.00,   12.50,  10.42",
            "100.00,     5.00,   0.42",
            "2500.75,   19.99,  41.66",
            "-1000.00,  12.50, -10.42",
            "-2500.75,  19.99, -41.66",
            "-0.01,      1.00,  -0.01",
        })
        @DisplayName("the formula never produces the rounded or floored answer")
        void neverProducesTheRoundedAnswer(String balance, String rate, String wrongAnswer) {
            assertThat(CobolDecimal.monthlyInterest(new BigDecimal(balance), new BigDecimal(rate)))
                    .isNotEqualTo(new BigDecimal(wrongAnswer));
        }

        @Test
        @DisplayName("null operands are rejected")
        void rejectsNulls() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.monthlyInterest(null, SAMPLE_INTEREST_RATE))
                    .withMessage("multiplicand must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.monthlyInterest(SAMPLE_CATEGORY_BALANCE, null))
                    .withMessage("multiplier must not be null");
        }
    }

    @Nested
    @DisplayName("Accumulation - ADD WS-MONTHLY-INT TO WS-TOTAL-INT, with no drift")
    class Accumulation {
        @Test
        @DisplayName("CBACT04C.cbl:L467 - one hundred additions of 0.01 total exactly 1.00")
        void accumulatesOneHundredCentsExactly() {
            BigDecimal runningTotal = CobolDecimal.monetaryZero();
            for (int category = 0; category < 100; category++) {
                runningTotal = CobolDecimal.add(runningTotal, new BigDecimal("0.01"),
                        CobolDecimal.MONETARY_SCALE);
            }

            assertThat(runningTotal).isEqualTo(new BigDecimal("1.00")).hasScaleOf(2);
            assertThat(runningTotal.toPlainString()).isEqualTo("1.00");
        }

        @Test
        @DisplayName("CBACT04C.cbl:L467 - seven additions of 10.41 total exactly 72.87")
        void accumulatesTheWorkedInterestAmountExactly() {
            BigDecimal runningTotal = CobolDecimal.monetaryZero();
            for (int category = 0; category < 7; category++) {
                runningTotal = CobolDecimal.add(runningTotal, new BigDecimal("10.41"),
                        CobolDecimal.MONETARY_SCALE);
                assertThat(runningTotal).hasScaleOf(2);
            }

            assertThat(runningTotal).isEqualTo(new BigDecimal("72.87")).hasScaleOf(2);
        }

        @Test
        @DisplayName("accumulating computed interest keeps the running total at scale 2 throughout")
        void accumulatesComputedInterestWithoutScaleDrift() {
            BigDecimal runningTotal = CobolDecimal.monetaryZero();

            runningTotal = CobolDecimal.add(runningTotal,
                    CobolDecimal.monthlyInterest(new BigDecimal("1000.00"),
                            new BigDecimal("12.50")),
                    CobolDecimal.MONETARY_SCALE);
            assertThat(runningTotal).isEqualTo(new BigDecimal("10.41")).hasScaleOf(2);

            runningTotal = CobolDecimal.add(runningTotal,
                    CobolDecimal.monthlyInterest(new BigDecimal("100.00"), new BigDecimal("5.00")),
                    CobolDecimal.MONETARY_SCALE);
            assertThat(runningTotal).isEqualTo(new BigDecimal("10.82")).hasScaleOf(2);

            runningTotal = CobolDecimal.add(runningTotal,
                    CobolDecimal.monthlyInterest(new BigDecimal("2500.75"),
                            new BigDecimal("19.99")),
                    CobolDecimal.MONETARY_SCALE);

            assertThat(runningTotal).isEqualTo(new BigDecimal("52.47")).hasScaleOf(2);
        }

        @Test
        @DisplayName("a credit category contributes negative interest and reduces the total")
        void accumulatesNegativeInterest() {
            BigDecimal runningTotal = CobolDecimal.add(new BigDecimal("10.41"),
                    CobolDecimal.monthlyInterest(new BigDecimal("-1000.00"),
                            new BigDecimal("12.50")),
                    CobolDecimal.MONETARY_SCALE);

            assertThat(runningTotal).isEqualTo(new BigDecimal("0.00")).hasScaleOf(2);
        }
    }

    @Nested
    @DisplayName("End-to-end COBOL statement sequences")
    class CobolStatementSequences {
        @Test
        @DisplayName("CBACT04C.cbl:L352-L354 - the arithmetic half of the account-break sequence")
        void reproducesTheAccountBreakArithmetic() {
            BigDecimal totalInterest = CobolDecimal.monetaryZero();
            totalInterest = CobolDecimal.add(totalInterest,
                    CobolDecimal.monthlyInterest(new BigDecimal("1000.00"),
                            new BigDecimal("12.50")),
                    CobolDecimal.MONETARY_SCALE);
            totalInterest = CobolDecimal.add(totalInterest,
                    CobolDecimal.monthlyInterest(new BigDecimal("500.00"), new BigDecimal("18.00")),
                    CobolDecimal.MONETARY_SCALE);

            assertThat(totalInterest).isEqualTo(new BigDecimal("17.91")).hasScaleOf(2);

            BigDecimal currentBalance = new BigDecimal("1500.00");
            BigDecimal updatedBalance = CobolDecimal.storeAtPicture(
                    currentBalance.add(totalInterest), TEN_DIGIT_PRECISION,
                    CobolDecimal.MONETARY_SCALE);
            assertThat(updatedBalance).isEqualTo(new BigDecimal("1517.91")).hasScaleOf(2);

            assertThat(CobolDecimal.add(currentBalance, totalInterest,
                    CobolDecimal.MONETARY_SCALE)).isEqualTo(updatedBalance);

            assertThat(CobolDecimal.monetaryZero()).hasScaleOf(2);
            assertThat(CobolDecimal.monetaryZero().toPlainString()).isEqualTo("0.00");
        }

        @Test
        @DisplayName("CBACT04C.cbl:L352 - posting interest onto a negative balance")
        void postsInterestOntoANegativeBalance() {
            BigDecimal creditBalance = new BigDecimal("-250.00");
            BigDecimal totalInterest = new BigDecimal("17.91");

            assertThat(CobolDecimal.add(creditBalance, totalInterest,
                    CobolDecimal.MONETARY_SCALE))
                    .isEqualTo(new BigDecimal("-232.09"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("CBACT04C.cbl:L490 - MOVE WS-MONTHLY-INT TO TRAN-AMT loses nothing")
        void monthlyInterestMovesIntoTranAmountUnchanged() {
            BigDecimal monthlyInterest =
                    CobolDecimal.monthlyInterest(SAMPLE_CATEGORY_BALANCE, SAMPLE_INTEREST_RATE);

            assertThat(CobolDecimal.storeAtPicture(monthlyInterest, NINE_DIGIT_PRECISION,
                    CobolDecimal.MONETARY_SCALE))
                    .isEqualTo(monthlyInterest)
                    .isEqualTo(new BigDecimal("10.41"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("COBIL00C.cbl:L234 - COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT")
        void reproducesTheBillPaymentDebit() {
            BigDecimal balance = new BigDecimal("1517.91");
            BigDecimal payment = new BigDecimal("517.91");

            assertThat(CobolDecimal.subtract(balance, payment, CobolDecimal.MONETARY_SCALE))
                    .isEqualTo(new BigDecimal("1000.00"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("COBIL00C.cbl:L234 - a payment larger than the balance drives it negative")
        void billPaymentMayDriveTheBalanceNegative() {
            BigDecimal balance = new BigDecimal("100.00");
            BigDecimal payment = new BigDecimal("150.75");

            assertThat(CobolDecimal.subtract(balance, payment, CobolDecimal.MONETARY_SCALE))
                    .isEqualTo(new BigDecimal("-50.75"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("COBIL00C.cbl:L234 - operands of differing scales still store at scale 2")
        void billPaymentNormalisesDifferingOperandScales() {
            assertThat(CobolDecimal.subtract(new BigDecimal("1517.9"), new BigDecimal("517.91"),
                    CobolDecimal.MONETARY_SCALE))
                    .isEqualTo(new BigDecimal("999.99"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("COBIL00C.cbl:L234 - paying the full balance leaves exactly 0.00")
        void payingTheFullBalanceLeavesScaledZero() {
            BigDecimal balance = new BigDecimal("1517.91");

            BigDecimal remaining =
                    CobolDecimal.subtract(balance, balance, CobolDecimal.MONETARY_SCALE);

            assertThat(remaining).isEqualTo(new BigDecimal("0.00")).hasScaleOf(2);
            assertThat(remaining.toPlainString()).isEqualTo("0.00");
        }

        @Test
        @DisplayName("CBTRN02C.cbl:L403-L405 - the chained COMPUTE WS-TEMP-BAL expression")
        void reproducesTheChainedTemporaryBalance() {
            BigDecimal cycleCredit = new BigDecimal("2500.00");
            BigDecimal cycleDebit = new BigDecimal("1000.00");
            BigDecimal dailyAmount = new BigDecimal("125.75");

            BigDecimal temporaryBalance = CobolDecimal.add(
                    CobolDecimal.subtract(cycleCredit, cycleDebit, CobolDecimal.MONETARY_SCALE),
                    dailyAmount, CobolDecimal.MONETARY_SCALE);

            assertThat(temporaryBalance).isEqualTo(new BigDecimal("1625.75")).hasScaleOf(2);
            assertThat(temporaryBalance).isEqualTo(CobolDecimal.storeMonetary(
                    cycleCredit.subtract(cycleDebit).add(dailyAmount)));
        }

        @Test
        @DisplayName("CBTRN02C.cbl:L403-L405 - an intermediate wider than 2 dp is fixed by the store")
        void theChainedExpressionIsFixedToScaleTwoByTheStore() {
            BigDecimal cycleCredit = new BigDecimal("2500.00");
            BigDecimal cycleDebit = new BigDecimal("1000.005");
            BigDecimal dailyAmount = new BigDecimal("125.75");

            BigDecimal exactExpression = cycleCredit.subtract(cycleDebit).add(dailyAmount);
            assertThat(exactExpression).isEqualTo(new BigDecimal("1625.745")).hasScaleOf(3);

            BigDecimal temporaryBalance = CobolDecimal.storeAtPicture(exactExpression,
                    NINE_DIGIT_PRECISION, CobolDecimal.MONETARY_SCALE);

            assertThat(temporaryBalance).isEqualTo(new BigDecimal("1625.74")).hasScaleOf(2);
            assertThat(temporaryBalance).isNotEqualTo(new BigDecimal("1625.75"));
        }

        @Test
        @DisplayName("CBTRN02C.cbl:L403-L405 - a debit-heavy cycle yields a negative temp balance")
        void theChainedExpressionMayGoNegative() {
            BigDecimal cycleCredit = new BigDecimal("100.00");
            BigDecimal cycleDebit = new BigDecimal("2500.00");
            BigDecimal dailyAmount = new BigDecimal("125.75");

            BigDecimal temporaryBalance = CobolDecimal.add(
                    CobolDecimal.subtract(cycleCredit, cycleDebit, CobolDecimal.MONETARY_SCALE),
                    dailyAmount, CobolDecimal.MONETARY_SCALE);

            assertThat(temporaryBalance).isEqualTo(new BigDecimal("-2274.25")).hasScaleOf(2);
        }

        @Test
        @DisplayName("CBTRN02C.cbl:L407 - the credit-limit comparison is scale-insensitive")
        void creditLimitComparisonIsScaleInsensitive() {
            BigDecimal creditLimit = new BigDecimal("1625.75");
            BigDecimal temporaryBalance = CobolDecimal.storeMonetary(new BigDecimal("1625.750"));

            assertThat(creditLimit).isEqualByComparingTo(temporaryBalance);
            assertThat(creditLimit.compareTo(temporaryBalance)).isZero();
            assertThat(creditLimit.compareTo(temporaryBalance) >= 0).isTrue();
        }

        @Test
        @DisplayName("CBACT04C.cbl:L518-L520 - the fee stub contributes nothing, by design")
        void theFeeStubContributesNothing() {
            BigDecimal balanceBeforeFees = new BigDecimal("1517.91");

            BigDecimal balanceAfterFees = CobolDecimal.add(balanceBeforeFees,
                    CobolDecimal.monetaryZero(), CobolDecimal.MONETARY_SCALE);

            assertThat(balanceAfterFees).isEqualTo(balanceBeforeFees).hasScaleOf(2);
        }
    }
}
