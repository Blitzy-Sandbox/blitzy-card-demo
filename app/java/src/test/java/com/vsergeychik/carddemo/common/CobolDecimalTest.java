package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity tests for {@link CobolDecimal}, the single home of numeric parity.
 *
 * <p>These tests are the enforcement point for the numeric behaviour of the whole migration. They
 * are deliberately assertive about exact values <em>and</em> exact scales, because a
 * {@link BigDecimal} of the right value and the wrong scale still renders the wrong fixed-width
 * byte image, and a fixed-width record whose width is wrong invalidates every offset after it.
 *
 * <p>The suite is organised around the three properties of the COBOL source that determine the
 * implementation: {@code ROUNDED} appears zero times, so results truncate rather than round;
 * {@code ON SIZE ERROR} appears zero times, so overflow is silent at both ends; and every scaled
 * numeric in the system has scale exactly 2. Several tests name the specific COBOL statement they
 * protect, so that a failure points at a translation decision rather than merely at a number.
 *
 * <p>Every guard clause in the class under test is exercised from both sides, because the build
 * enforces at least 90% branch coverage independently for each package.
 */
@DisplayName("CobolDecimal - COBOL fixed-point numeric parity")
class CobolDecimalTest {

    /** {@code TRAN-CAT-BAL} from the worked interest example: a scale-2 category balance. */
    private static final BigDecimal SAMPLE_CATEGORY_BALANCE = new BigDecimal("1000.00");

    /** {@code DIS-INT-RATE} from the worked interest example: 12.5% expressed at scale 2. */
    private static final BigDecimal SAMPLE_INTEREST_RATE = new BigDecimal("12.50");

    @Nested
    @DisplayName("Constants")
    class Constants {

        @Test
        @DisplayName("the monetary scale is 2, matching the V99-only PICTURE scan")
        void monetaryScaleIsTwo() {
            assertThat(CobolDecimal.MONETARY_SCALE).isEqualTo(2);
        }

        @Test
        @DisplayName("the rounding mode is DOWN, because ROUNDED never appears in the source")
        void roundingModeIsDown() {
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);
        }

        @Test
        @DisplayName("the rounding mode is none of the modes that would break parity")
        void roundingModeIsNotAnyRoundingVariant() {
            assertThat(CobolDecimal.COBOL_ROUNDING)
                    .isNotIn(RoundingMode.HALF_UP, RoundingMode.HALF_EVEN, RoundingMode.HALF_DOWN,
                            RoundingMode.CEILING, RoundingMode.FLOOR, RoundingMode.UP,
                            RoundingMode.UNNECESSARY);
        }

        @Test
        @DisplayName("the interest divisor is the literal 1200 from CBACT04C.cbl:L465")
        void interestDivisorIsTwelveHundred() {
            assertThat(CobolDecimal.MONTHLY_INTEREST_DIVISOR).isEqualTo(1200L);
        }

        @Test
        @DisplayName("the class cannot be instantiated, not even reflectively")
        void cannotBeInstantiated() throws ReflectiveOperationException {
            Constructor<CobolDecimal> constructor = CobolDecimal.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }

    @Nested
    @DisplayName("store - truncation on the receiving field's scale")
    class Store {

        @Test
        @DisplayName("a positive value is truncated, not rounded: 1.239 stores as 1.23")
        void truncatesPositiveValue() {
            assertThat(CobolDecimal.store(new BigDecimal("1.239"), 2))
                    .isEqualTo(new BigDecimal("1.23"));
        }

        @Test
        @DisplayName("a negative value truncates toward zero: -1.239 stores as -1.23, not -1.24")
        void truncatesNegativeValueTowardZero() {
            BigDecimal stored = CobolDecimal.store(new BigDecimal("-1.239"), 2);

            assertThat(stored).isEqualTo(new BigDecimal("-1.23"));
            // FLOOR would produce -1.24. Asserting the absence of that value explicitly, because
            // choosing FLOOR over DOWN is the single most plausible way to break negative parity.
            assertThat(stored).isNotEqualTo(new BigDecimal("-1.24"));
        }

        @ParameterizedTest(name = "storing {0} at scale 2 yields {1}")
        @CsvSource({
            "1.239,     1.23",
            "1.231,     1.23",
            "1.999,     1.99",
            "-1.239,    -1.23",
            "-1.999,    -1.99",
            "0.009,     0.00",
            "-0.009,    0.00",
            "10.416666, 10.41",
            "1.23,      1.23",
        })
        @DisplayName("truncation is symmetric about zero for every representative magnitude")
        void truncatesTowardZero(String input, String expected) {
            assertThat(CobolDecimal.store(new BigDecimal(input), 2))
                    .isEqualTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("scaling up pads with trailing zeros, which a fixed-width writer needs")
        void scalingUpIsExact() {
            assertThat(CobolDecimal.store(new BigDecimal("42"), 2))
                    .isEqualTo(new BigDecimal("42.00"))
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
        @DisplayName("storeMonetary applies scale 2 without the caller naming it")
        void storeMonetaryUsesScaleTwo() {
            assertThat(CobolDecimal.storeMonetary(new BigDecimal("7.019")))
                    .isEqualTo(new BigDecimal("7.01"))
                    .hasScaleOf(CobolDecimal.MONETARY_SCALE);
        }

        @Test
        @DisplayName("a null value is rejected")
        void rejectsNullValue() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.store(null, 2))
                    .withMessageContaining("value");
        }

        @Test
        @DisplayName("a null value is rejected by storeMonetary too")
        void storeMonetaryRejectsNullValue() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.storeMonetary(null))
                    .withMessageContaining("value");
        }

        @Test
        @DisplayName("a negative scale is rejected, since no PICTURE clause declares one")
        void rejectsNegativeScale() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CobolDecimal.store(BigDecimal.ONE, -1))
                    .withMessageContaining("scale must not be negative");
        }
    }

    @Nested
    @DisplayName("storeAtPicture - silent truncation at both ends")
    class StoreAtPicture {

        @Test
        @DisplayName("a value that fits is returned with only its fraction truncated")
        void passesThroughAValueThatFits() {
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("1234.5678"), 10, 2))
                    .isEqualTo(new BigDecimal("1234.56"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("high-order overflow silently keeps the low-order digits: S9(10)V99")
        void discardsHighOrderDigitsOnOverflow() {
            // 11 integer digits stored into a 10-integer-digit receiver: the leading 1 is dropped.
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("12345678901.23"), 10, 2))
                    .isEqualTo(new BigDecimal("2345678901.23"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("high-order overflow preserves the sign of the computed value")
        void preservesSignOnOverflow() {
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("-12345678901.23"), 10, 2))
                    .isEqualTo(new BigDecimal("-2345678901.23"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("overflow never throws, because ON SIZE ERROR appears nowhere in the source")
        void overflowDoesNotThrow() {
            BigDecimal wildlyOversized = new BigDecimal("999999999999999999999.99");

            assertThat(CobolDecimal.storeAtPicture(wildlyOversized, 9, 2))
                    .isEqualTo(new BigDecimal("999999999.99"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("both ends truncate together: fraction dropped and high-order digits dropped")
        void truncatesBothEndsAtOnce() {
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("98765.4321"), 4, 2))
                    .isEqualTo(new BigDecimal("8765.43"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a value below one fits any receiver, despite its precision being under scale")
        void handlesValuesBelowOne() {
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("0.419"), 9, 2))
                    .isEqualTo(new BigDecimal("0.41"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("zero fits any receiver and keeps the receiver's scale")
        void handlesZero() {
            assertThat(CobolDecimal.storeAtPicture(BigDecimal.ZERO, 10, 2))
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
            // Exactly at the boundary: no digits are lost.
            "9999999999.99, 10, 9999999999.99",
            // One digit over the boundary in each declared width in this codebase.
            "12345678901.23, 10, 2345678901.23",
            "1234567890.12,   9, 234567890.12",
            "12345.67,        4, 2345.67",
            // Negative counterparts keep the sign.
            "-1234567890.12,  9, -234567890.12",
            "-12345.67,       4, -2345.67",
        })
        @DisplayName("overflow behaviour holds across every declared integer precision")
        void wrapsAtEveryDeclaredPrecision(String input, int integerDigits, String expected) {
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal(input), integerDigits, 2))
                    .isEqualTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("a null value is rejected")
        void rejectsNullValue() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.storeAtPicture(null, 10, 2))
                    .withMessageContaining("value");
        }

        @Test
        @DisplayName("negative integer precision is rejected")
        void rejectsNegativeIntegerDigits() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CobolDecimal.storeAtPicture(BigDecimal.ONE, -1, 2))
                    .withMessageContaining("integerDigits must not be negative");
        }

        @Test
        @DisplayName("a negative scale is rejected")
        void rejectsNegativeScale() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CobolDecimal.storeAtPicture(BigDecimal.ONE, 10, -1))
                    .withMessageContaining("scale must not be negative");
        }
    }

    @Nested
    @DisplayName("zero - the MOVE 0 factories")
    class Zero {

        @Test
        @DisplayName("zero(2) renders as 0.00, not 0, so the byte image stays two digits wide")
        void zeroAtScaleTwoRendersWithBothDecimals() {
            BigDecimal zero = CobolDecimal.zero(2);

            assertThat(zero.scale()).isEqualTo(2);
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
        @DisplayName("the scaled zero is deliberately not equal to BigDecimal.ZERO")
        void isNotEqualToUnscaledZero() {
            // This is the whole reason the factory exists: BigDecimal.equals compares scale, so a
            // scale-0 ZERO is a different value here and would serialise one byte too narrow.
            assertThat(CobolDecimal.monetaryZero()).isNotEqualTo(BigDecimal.ZERO);
            assertThat(CobolDecimal.monetaryZero()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @ParameterizedTest(name = "zero at scale {0} keeps that scale")
        @ValueSource(ints = {0, 1, 2, 4, 6})
        @DisplayName("any non-negative scale is honoured")
        void honoursAnyNonNegativeScale(int scale) {
            assertThat(CobolDecimal.zero(scale)).hasScaleOf(scale).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a negative scale is rejected")
        void rejectsNegativeScale() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CobolDecimal.zero(-1))
                    .withMessageContaining("scale must not be negative");
        }
    }

    @Nested
    @DisplayName("add and subtract - the ADD and SUBTRACT verbs")
    class AddAndSubtract {

        @Test
        @DisplayName("adding to a scaled zero preserves both the value and the scale")
        void addPreservesScale() {
            BigDecimal sum = CobolDecimal.add(CobolDecimal.monetaryZero(),
                    new BigDecimal("10.41"), 2);

            assertThat(sum).isEqualTo(new BigDecimal("10.41"));
            assertThat(sum.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("addition of scale-2 operands is exact")
        void addIsExact() {
            assertThat(CobolDecimal.add(new BigDecimal("1234.56"), new BigDecimal("0.44"), 2))
                    .isEqualTo(new BigDecimal("1235.00"));
        }

        @Test
        @DisplayName("a sum carrying more fraction than the receiver is truncated, not rounded")
        void addTruncatesToTheReceiverScale() {
            assertThat(CobolDecimal.add(new BigDecimal("1.005"), new BigDecimal("0.006"), 2))
                    .isEqualTo(new BigDecimal("1.01"));
        }

        @Test
        @DisplayName("addition handles negative operands")
        void addHandlesNegativeOperands() {
            assertThat(CobolDecimal.add(new BigDecimal("10.00"), new BigDecimal("-25.50"), 2))
                    .isEqualTo(new BigDecimal("-15.50"));
        }

        @Test
        @DisplayName("subtraction of scale-2 operands is exact")
        void subtractIsExact() {
            assertThat(
                    CobolDecimal.subtract(new BigDecimal("1000.00"), new BigDecimal("250.75"), 2))
                    .isEqualTo(new BigDecimal("749.25"));
        }

        @Test
        @DisplayName("subtraction may cross zero into a negative balance")
        void subtractMayGoNegative() {
            assertThat(CobolDecimal.subtract(new BigDecimal("100.00"), new BigDecimal("150.00"), 2))
                    .isEqualTo(new BigDecimal("-50.00"));
        }

        @Test
        @DisplayName("a difference carrying more fraction than the receiver is truncated")
        void subtractTruncatesToTheReceiverScale() {
            assertThat(CobolDecimal.subtract(new BigDecimal("1.009"), new BigDecimal("0.001"), 2))
                    .isEqualTo(new BigDecimal("1.00"));
        }

        @Test
        @DisplayName("null operands are rejected on add")
        void addRejectsNulls() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.add(null, BigDecimal.ONE, 2))
                    .withMessageContaining("augend");
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.add(BigDecimal.ONE, null, 2))
                    .withMessageContaining("addend");
        }

        @Test
        @DisplayName("null operands are rejected on subtract")
        void subtractRejectsNulls() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.subtract(null, BigDecimal.ONE, 2))
                    .withMessageContaining("minuend");
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.subtract(BigDecimal.ONE, null, 2))
                    .withMessageContaining("subtrahend");
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
    @DisplayName("multiply and divide")
    class MultiplyAndDivide {

        @Test
        @DisplayName("multiplyAndStore forms the exact product, then truncates once")
        void multiplyAndStoreTruncatesOnce() {
            // 1000.00 * 12.50 is exactly 12500.0000 at scale 4; stored at scale 2 it is 12500.00.
            assertThat(CobolDecimal.multiplyAndStore(SAMPLE_CATEGORY_BALANCE,
                    SAMPLE_INTEREST_RATE, 2))
                    .isEqualTo(new BigDecimal("12500.00"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("multiplyAndStore truncates a product with more fraction than the receiver")
        void multiplyAndStoreTruncatesExcessFraction() {
            // 1.11 * 1.11 is exactly 1.2321; truncated at scale 2 that is 1.23, never 1.24.
            assertThat(CobolDecimal.multiplyAndStore(new BigDecimal("1.11"),
                    new BigDecimal("1.11"), 2))
                    .isEqualTo(new BigDecimal("1.23"));
        }

        @Test
        @DisplayName("multiplyAndStore preserves sign for a single negative factor")
        void multiplyAndStoreHandlesNegativeFactor() {
            assertThat(CobolDecimal.multiplyAndStore(new BigDecimal("-2.50"),
                    new BigDecimal("4.00"), 2))
                    .isEqualTo(new BigDecimal("-10.00"));
        }

        @Test
        @DisplayName("a non-terminating quotient truncates instead of throwing: 100.00 / 3 = 33.33")
        void divideDoesNotThrowOnNonTerminatingQuotient() {
            assertThat(CobolDecimal.divide(new BigDecimal("100.00"), new BigDecimal("3"), 2))
                    .isEqualTo(new BigDecimal("33.33"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a negative non-terminating quotient truncates toward zero")
        void divideTruncatesNegativeQuotientTowardZero() {
            assertThat(CobolDecimal.divide(new BigDecimal("-100.00"), new BigDecimal("3"), 2))
                    .isEqualTo(new BigDecimal("-33.33"));
        }

        @Test
        @DisplayName("an exact quotient is unaffected")
        void divideHandlesExactQuotient() {
            assertThat(CobolDecimal.divide(new BigDecimal("100.00"), new BigDecimal("4"), 2))
                    .isEqualTo(new BigDecimal("25.00"));
        }

        @Test
        @DisplayName("a zero divisor raises a clear ArithmeticException naming the condition")
        void divideRejectsZeroDivisor() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> CobolDecimal.divide(BigDecimal.ONE, BigDecimal.ZERO, 2))
                    .withMessageContaining("divide by zero");
        }

        @Test
        @DisplayName("a scaled zero divisor is caught too, which equals(ZERO) would have missed")
        void divideRejectsScaledZeroDivisor() {
            // new BigDecimal("0.00").equals(BigDecimal.ZERO) is false, so the guard must test
            // signum rather than equality. This case is what makes that distinction observable.
            BigDecimal scaledZero = new BigDecimal("0.00");
            assertThat(scaledZero).isNotEqualTo(BigDecimal.ZERO);

            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> CobolDecimal.divide(BigDecimal.ONE, scaledZero, 2))
                    .withMessageContaining("divide by zero");
        }

        @Test
        @DisplayName("null operands are rejected on divide")
        void divideRejectsNulls() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.divide(null, BigDecimal.ONE, 2))
                    .withMessageContaining("dividend");
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.divide(BigDecimal.ONE, null, 2))
                    .withMessageContaining("divisor");
        }

        @Test
        @DisplayName("null factors are rejected on multiplyAndStore")
        void multiplyAndStoreRejectsNulls() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.multiplyAndStore(null, BigDecimal.ONE, 2))
                    .withMessageContaining("multiplicand");
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.multiplyAndStore(BigDecimal.ONE, null, 2))
                    .withMessageContaining("multiplier");
        }

        @Test
        @DisplayName("a negative scale is rejected on divide and on multiplyAndStore")
        void rejectsNegativeScale() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CobolDecimal.divide(BigDecimal.ONE, BigDecimal.TEN, -1))
                    .withMessageContaining("scale must not be negative");
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () -> CobolDecimal.multiplyAndStore(BigDecimal.ONE, BigDecimal.TEN, -1))
                    .withMessageContaining("scale must not be negative");
        }
    }

    @Nested
    @DisplayName("multiplyThenDivide - the COMPUTE product-over-literal shape")
    class MultiplyThenDivide {

        @Test
        @DisplayName("the product stays exact across the division, so no digits are lost early")
        void keepsTheProductExactAcrossTheDivision() {
            // Truncating the product to scale 2 first would give 12500.00 / 1200 = 10.41 as well,
            // so a case is needed where the exact scale-4 product genuinely matters. 0.01 * 0.01 is
            // 0.0001 exactly; divided by 1 at scale 4 it survives, whereas an intermediate store at
            // scale 2 would already have flattened it to zero.
            assertThat(CobolDecimal.multiplyThenDivide(new BigDecimal("0.01"),
                    new BigDecimal("0.01"), 1L, 4))
                    .isEqualTo(new BigDecimal("0.0001"));
        }

        @Test
        @DisplayName("the quotient is truncated to the receiver's scale")
        void truncatesToTheReceiverScale() {
            assertThat(CobolDecimal.multiplyThenDivide(new BigDecimal("100.00"),
                    new BigDecimal("1.00"), 3L, 2))
                    .isEqualTo(new BigDecimal("33.33"));
        }

        @Test
        @DisplayName("a negative divisor is accepted and the sign propagates")
        void handlesNegativeDivisor() {
            assertThat(CobolDecimal.multiplyThenDivide(new BigDecimal("100.00"),
                    new BigDecimal("1.00"), -4L, 2))
                    .isEqualTo(new BigDecimal("-25.00"));
        }

        @Test
        @DisplayName("a zero divisor raises the same clear ArithmeticException")
        void rejectsZeroDivisor() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> CobolDecimal.multiplyThenDivide(BigDecimal.ONE,
                            BigDecimal.ONE, 0L, 2))
                    .withMessageContaining("divide by zero");
        }

        @Test
        @DisplayName("null factors are rejected")
        void rejectsNulls() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.multiplyThenDivide(null, BigDecimal.ONE, 1L, 2))
                    .withMessageContaining("multiplicand");
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.multiplyThenDivide(BigDecimal.ONE, null, 1L, 2))
                    .withMessageContaining("multiplier");
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
        @DisplayName("1000.00 at 12.50% yields 10.41, never 10.42")
        void reproducesTheWorkedExample() {
            BigDecimal interest =
                    CobolDecimal.monthlyInterest(SAMPLE_CATEGORY_BALANCE, SAMPLE_INTEREST_RATE);

            // The exact quotient is 10.41666...; COBOL truncates because the COMPUTE carries no
            // ROUNDED phrase, so the stored value is 10.41.
            assertThat(interest).isEqualTo(new BigDecimal("10.41"));
            assertThat(interest).isNotEqualTo(new BigDecimal("10.42"));
            assertThat(interest.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("the intermediate product is exactly 12500.0000 at scale 4")
        void theIntermediateProductCarriesScaleFour() {
            // Operand scales fix this: TRAN-CAT-BAL is S9(09)V99 and DIS-INT-RATE is S9(04)V99, so
            // the exact product carries scale 2 + 2 = 4 and the division is the last operation.
            BigDecimal exactProduct = SAMPLE_CATEGORY_BALANCE.multiply(SAMPLE_INTEREST_RATE);

            assertThat(exactProduct).isEqualTo(new BigDecimal("12500.0000"));
            assertThat(exactProduct.scale()).isEqualTo(4);
        }

        @Test
        @DisplayName("the result equals an explicit multiply-then-divide by 1200 at scale 2")
        void agreesWithTheExplicitFormula()  {
            assertThat(CobolDecimal.monthlyInterest(SAMPLE_CATEGORY_BALANCE, SAMPLE_INTEREST_RATE))
                    .isEqualTo(CobolDecimal.multiplyThenDivide(SAMPLE_CATEGORY_BALANCE,
                            SAMPLE_INTEREST_RATE, CobolDecimal.MONTHLY_INTEREST_DIVISOR,
                            CobolDecimal.MONETARY_SCALE));
        }

        @ParameterizedTest(name = "balance {0} at rate {1} yields {2}")
        @CsvSource({
            // The worked example and its neighbours.
            "1000.00,   12.50, 10.41",
            "1000.00,   00.00,  0.00",
            "0.00,      12.50,  0.00",
            // A rate at the top of DIS-INT-RATE's S9(04)V99 range.
            "100.00,    24.99,  2.08",
            // Small balances truncate to zero rather than rounding up to a cent.
            "1.00,      11.99,  0.00",
            "10.00,     11.99,  0.09",
            // A negative balance, that is, a credit, produces negative interest truncated toward
            // zero: the exact quotient is -10.41666... and the stored value is -10.41.
            "-1000.00,  12.50, -10.41",
        })
        @DisplayName("the formula truncates across the representative input range")
        void truncatesAcrossTheInputRange(String balance, String rate, String expected) {
            assertThat(CobolDecimal.monthlyInterest(new BigDecimal(balance), new BigDecimal(rate)))
                    .isEqualTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("null operands are rejected")
        void rejectsNulls() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolDecimal.monthlyInterest(null, SAMPLE_INTEREST_RATE))
                    .withMessageContaining("multiplicand");
            assertThatNullPointerException()
                    .isThrownBy(
                            () -> CobolDecimal.monthlyInterest(SAMPLE_CATEGORY_BALANCE, null))
                    .withMessageContaining("multiplier");
        }
    }

    @Nested
    @DisplayName("End-to-end COBOL statement sequences")
    class CobolStatementSequences {

        @Test
        @DisplayName("CBACT04C.cbl:L200, L352-L354 - the account-break sequence")
        void reproducesTheAccountBreakSequence() {
            // MOVE 0 TO WS-TOTAL-INT (L200) resets the per-account accumulator at scale 2.
            BigDecimal totalInterest = CobolDecimal.monetaryZero();
            assertThat(totalInterest.toPlainString()).isEqualTo("0.00");

            // Two categories accrue interest; each is accumulated by ADD (L467).
            totalInterest = CobolDecimal.add(totalInterest,
                    CobolDecimal.monthlyInterest(new BigDecimal("1000.00"),
                            new BigDecimal("12.50")),
                    CobolDecimal.MONETARY_SCALE);
            totalInterest = CobolDecimal.add(totalInterest,
                    CobolDecimal.monthlyInterest(new BigDecimal("500.00"),
                            new BigDecimal("18.00")),
                    CobolDecimal.MONETARY_SCALE);

            // 10.41 + 7.50 = 17.91
            assertThat(totalInterest).isEqualTo(new BigDecimal("17.91"));

            // L352: ADD WS-TOTAL-INT TO ACCT-CURR-BAL, into an S9(10)V99 receiver.
            BigDecimal currentBalance = new BigDecimal("1500.00");
            BigDecimal updatedBalance = CobolDecimal.add(currentBalance, totalInterest,
                    CobolDecimal.MONETARY_SCALE);
            assertThat(updatedBalance).isEqualTo(new BigDecimal("1517.91"));

            // L353 and L354: MOVE 0 TO ACCT-CURR-CYC-CREDIT and MOVE 0 TO ACCT-CURR-CYC-DEBIT.
            // Both must serialise as 0.00, not 0, or the 300-byte account record loses two bytes
            // per field and every offset after them shifts.
            BigDecimal cycleCredit = CobolDecimal.monetaryZero();
            BigDecimal cycleDebit = CobolDecimal.monetaryZero();
            assertThat(cycleCredit.toPlainString()).isEqualTo("0.00");
            assertThat(cycleDebit.toPlainString()).isEqualTo("0.00");
            assertThat(cycleCredit.scale()).isEqualTo(2);
            assertThat(cycleDebit.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("CBACT04C.cbl:L490 - MOVE WS-MONTHLY-INT TO TRAN-AMT loses nothing")
        void monthlyInterestMovesIntoTranAmountUnchanged() {
            // Both sender and receiver are S9(09)V99, so the move is width-for-width.
            BigDecimal monthlyInterest =
                    CobolDecimal.monthlyInterest(SAMPLE_CATEGORY_BALANCE, SAMPLE_INTEREST_RATE);

            assertThat(CobolDecimal.storeAtPicture(monthlyInterest, 9,
                    CobolDecimal.MONETARY_SCALE))
                    .isEqualTo(monthlyInterest);
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
        @DisplayName("COBIL00C.cbl:L234 - paying the full balance leaves exactly 0.00")
        void payingTheFullBalanceLeavesScaledZero() {
            BigDecimal balance = new BigDecimal("1517.91");

            BigDecimal remaining =
                    CobolDecimal.subtract(balance, balance, CobolDecimal.MONETARY_SCALE);

            assertThat(remaining).isEqualTo(new BigDecimal("0.00"));
            assertThat(remaining.toPlainString()).isEqualTo("0.00");
        }

        @Test
        @DisplayName("CBTRN02C.cbl:L403-L405 - the chained COMPUTE WS-TEMP-BAL expression")
        void reproducesTheChainedTemporaryBalance() {
            // COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
            BigDecimal cycleCredit = new BigDecimal("2500.00");
            BigDecimal cycleDebit = new BigDecimal("1000.00");
            BigDecimal dailyAmount = new BigDecimal("125.75");

            BigDecimal temporaryBalance = CobolDecimal.add(
                    CobolDecimal.subtract(cycleCredit, cycleDebit, CobolDecimal.MONETARY_SCALE),
                    dailyAmount, CobolDecimal.MONETARY_SCALE);

            assertThat(temporaryBalance).isEqualTo(new BigDecimal("1625.75")).hasScaleOf(2);

            // All operands and the receiver are scale 2, so chaining is equivalent to evaluating
            // the expression exactly and storing once. Asserting that equivalence directly.
            assertThat(temporaryBalance).isEqualTo(CobolDecimal.storeMonetary(
                    cycleCredit.subtract(cycleDebit).add(dailyAmount)));
        }

        @Test
        @DisplayName("an overlimit comparison is unaffected by scale differences")
        void creditLimitComparisonIsScaleInsensitive() {
            // CBTRN02C.cbl:L407 compares ACCT-CREDIT-LIMIT with WS-TEMP-BAL. compareTo is the
            // correct operator: equals would report a false mismatch across differing scales.
            BigDecimal creditLimit = new BigDecimal("1625.75");
            BigDecimal temporaryBalance = CobolDecimal.storeMonetary(new BigDecimal("1625.750"));

            assertThat(creditLimit).isEqualByComparingTo(temporaryBalance);
            assertThat(creditLimit.compareTo(temporaryBalance)).isZero();
        }
    }
}
