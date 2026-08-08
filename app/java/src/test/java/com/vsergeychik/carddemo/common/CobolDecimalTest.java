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
 *
 * <p>{@link CobolDecimal} is the single point through which every monetary and scaled-numeric
 * operation in the module passes, and the only place {@link RoundingMode#DOWN} is named. This suite
 * is therefore the module's numeric-parity firewall: if it is weak, every downstream arithmetic
 * guarantee is unverified. It asserts exact values <em>and</em> exact scales throughout, because a
 * {@link BigDecimal} with the right value and the wrong scale still renders the wrong fixed-width
 * byte image, and one wrong width invalidates every record offset after it.
 *
 * <h2>Provenance of every expected value</h2>
 *
 * <p>The legacy COBOL <b>cannot be executed in this environment</b>: there is no z/OS runtime, the
 * available compiler has indexed file support disabled, no Language Environment {@code CEE*}
 * services exist, and no CICS emulator is present. Every expectation below is consequently
 * <em>statically derived</em> — read out of the COBOL source, the copybook {@code PICTURE} clauses
 * and the JCL contracts, then computed with arbitrary-precision decimal arithmetic — rather than
 * captured from a live run. Each non-obvious value carries a comment citing its origin as
 * {@code file:Lnnn} so a failure points at a translation decision rather than merely at a number.
 *
 * <h2>The three source properties that determine every assertion here</h2>
 *
 * <p>All three were re-counted in this checkout over comment-stripped source, which is the only
 * method that reproduces them: strip every line whose column 7 holds an asterisk, across all 28
 * programs in {@code app/cbl}.
 *
 * <ol>
 *   <li><b>{@code ROUNDED} occurs zero times</b> — and so do the {@code MULTIPLY} and
 *       {@code DIVIDE} verbs. COBOL rounds only where a statement says {@code ROUNDED} explicitly,
 *       so every store in this system <em>truncates</em> its excess fractional digits.
 *       {@code RoundingMode.DOWN} is the only faithful mode. The one division in the entire system
 *       is the {@code / 1200} inside a {@code COMPUTE}.</li>
 *   <li><b>{@code ON SIZE ERROR} occurs zero times</b> — so an oversized result is truncated
 *       silently at the <em>high</em> order too, rather than raising a condition. That is the least
 *       obvious half of the contract and has its own dedicated cases below.</li>
 *   <li><b>Every scaled numeric has scale exactly 2</b> — a {@code PICTURE} V-scale scan across
 *       {@code app/cbl} and {@code app/cpy} returns only {@code V99}, in 34 occurrences. There is
 *       no {@code V9} and no {@code V999}. Integer precision varies ({@code S9(10)V99},
 *       {@code S9(09)V99}, {@code S9(9)V99}, {@code S9(04)V99}); the scale never does.</li>
 * </ol>
 *
 * <h2>Three traps this suite exists to catch</h2>
 *
 * <ol>
 *   <li>{@code HALF_UP} and {@code HALF_EVEN} <em>look</em> like correct money rounding and are
 *       wrong here, because {@code ROUNDED} is never used. Caught by the half-way store cases and
 *       by the interest rows whose exact quotient ends in a repeating 6.</li>
 *   <li>{@code FLOOR} is <b>indistinguishable from {@code DOWN} until a negative value is
 *       tested</b>. Caught by {@code -10.415} and by the {@code -1000.00} interest row.</li>
 *   <li>{@code ON SIZE ERROR} being absent means an over-large value must wrap silently rather
 *       than throw. Caught by the {@code storeAtPicture} overflow cases.</li>
 * </ol>
 *
 * <h2>Governing standards</h2>
 *
 * <p><b>No user-specified rules were provided for this project</b>, so no project rule governs this
 * file. That absence is not treated as licence to lower the bar; the enterprise best practices
 * recorded in the migration plan bind instead, and the ones shaping this file are: only the
 * dependencies the build already pins (JUnit Jupiter and AssertJ — deliberately no Mockito, since
 * the class under test has no collaborators); no {@code double} and no {@code float} anywhere, not
 * even in a literal; no wildcard imports of any kind, including static ones, so that each imported
 * member stays auditable; no static mutable state; determinism with no reliance on locale, default
 * charset, ordering or randomness; and no reference file read at runtime — every expected value is
 * a literal in this source.
 *
 * <p>Every guard clause in the class under test is driven from both sides, because the build
 * enforces at least 90% <em>branch</em> coverage independently for each package, not merely for the
 * bundle as a whole.
 *
 * @see CobolDecimal#monthlyInterest(BigDecimal, BigDecimal) the formula this suite chiefly protects
 */
@DisplayName("CobolDecimal - the numeric-parity seam: truncation, scale 2, and nothing else")
class CobolDecimalTest {

    /**
     * {@code TRAN-CAT-BAL} from the canonical worked example: a scale-2 category balance.
     *
     * <p>Declared {@code PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy:9}. Immutable, so publishing
     * it as a constant introduces no shared mutable state.
     */
    private static final BigDecimal SAMPLE_CATEGORY_BALANCE = new BigDecimal("1000.00");

    /**
     * {@code DIS-INT-RATE} from the canonical worked example: 12.5% expressed at scale 2.
     *
     * <p>Declared {@code PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy:9}. The rate is a
     * percentage, so 12.50 means twelve and a half percent per annum.
     */
    private static final BigDecimal SAMPLE_INTEREST_RATE = new BigDecimal("12.50");

    /**
     * The integer precision of an {@code S9(09)V99} receiver, such as {@code WS-MONTHLY-INT}
     * [{@code app/cbl/CBACT04C.cbl:L168}], {@code WS-TEMP-BAL}
     * [{@code app/cbl/CBTRN02C.cbl:L187}], {@code TRAN-AMT} [{@code app/cpy/CVTRA05Y.cpy:10}] and
     * {@code TRAN-CAT-BAL} [{@code app/cpy/CVTRA01Y.cpy:9}].
     */
    private static final int NINE_DIGIT_PRECISION = 9;

    /**
     * The integer precision of an {@code S9(10)V99} receiver: the five money fields of the account
     * record — {@code ACCT-CURR-BAL}, {@code ACCT-CREDIT-LIMIT}, {@code ACCT-CASH-CREDIT-LIMIT},
     * {@code ACCT-CURR-CYC-CREDIT} and {@code ACCT-CURR-CYC-DEBIT}
     * [{@code app/cpy/CVACT01Y.cpy:7-9,13-14}].
     */
    private static final int TEN_DIGIT_PRECISION = 10;

    @Nested
    @DisplayName("Constants and policy - the rounding mode is the whole ballgame")
    class ConstantsAndPolicy {

        @Test
        @DisplayName("MONETARY_SCALE is 2, because the V-scale scan returns only V99, 34 times")
        void monetaryScaleIsTwo() {
            // Every scaled numeric in app/cbl and app/cpy is V99: 34 occurrences, comprising
            // S9(10)V99 x20, S9(09)V99 x10, S9(9)V99 x3 and S9(04)V99 x1. No V9 and no V999 exist,
            // so the scale is a system-wide invariant while the integer precision varies.
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
            // Rule R2 of the migration plan: numeric parity is truncation, not rounding. The
            // keyword ROUNDED appears ZERO times across all 28 programs, so COBOL truncates excess
            // fractional digits on store and DOWN is the only faithful mode.
            //
            // This assertion also records a deliberate correction: the superseded
            // docs/technical-specifications.md asserts HALF_EVEN for the interest calculation. That
            // claim is wrong for this codebase. The document is left unmodified rather than
            // silently repaired, and the correction lives here, where it can fail a build.
            assertThat(CobolDecimal.COBOL_ROUNDING)
                    .isNotEqualTo(RoundingMode.HALF_UP)
                    .isNotEqualTo(RoundingMode.HALF_EVEN)
                    .isNotEqualTo(RoundingMode.CEILING)
                    .isNotEqualTo(RoundingMode.FLOOR);
        }

        @Test
        @DisplayName("COBOL_ROUNDING is none of the remaining JDK modes either")
        void roundingModeIsNoneOfTheOtherJdkModes() {
            // Completeness: DOWN is asserted positively above, so every other member of the
            // enumeration must be excluded for the constant to be pinned rather than merely
            // plausible.
            assertThat(CobolDecimal.COBOL_ROUNDING)
                    .isNotIn(RoundingMode.UP, RoundingMode.HALF_DOWN, RoundingMode.UNNECESSARY);
        }

        @Test
        @DisplayName("MONTHLY_INTEREST_DIVISOR is the literal 1200 written at CBACT04C.cbl:L465")
        void interestDivisorIsTwelveHundred() {
            // Twelve months multiplied by the hundred that converts a percentage to a fraction.
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
            // Evidence that no instance state can ever exist: the sole constructor is private and
            // throws. Asserting the exact message as well as the type, so that a future refactor
            // that quietly re-opened construction would fail here.
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
            // The plainest statement of the contract. There is no ROUNDED phrase anywhere in the
            // 28 programs, so the third fractional digit is discarded rather than considered.
            assertThat(CobolDecimal.store(new BigDecimal("10.419"), 2))
                    .isEqualTo(new BigDecimal("10.41"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a half-way value truncates down: 10.415 stores as 10.41, proving the mode")
        void truncatesAHalfWayValueDownwards() {
            BigDecimal stored = CobolDecimal.store(new BigDecimal("10.415"), 2);

            // This single case proves the rounding mode. 10.415 sits exactly half way between
            // 10.41 and 10.42, so HALF_UP and HALF_EVEN would BOTH yield 10.42 while DOWN yields
            // 10.41. Asserting the wrong answer's absence explicitly, because reaching for
            // "correct money rounding" is the most plausible way to break this.
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

            // THE most valuable case in this file. For every positive input, FLOOR and DOWN agree,
            // so a FLOOR implementation would pass every other assertion here. They diverge only
            // on negatives: DOWN truncates toward zero and gives -10.41, whereas FLOOR truncates
            // toward negative infinity and gives -10.42. The fields are signed S9(...) fields and
            // negative balances are ordinary in this system, so the distinction is load-bearing.
            assertThat(stored).isEqualTo(new BigDecimal("-10.41")).hasScaleOf(2);
            assertThat(stored).isNotEqualTo(new BigDecimal("-10.42"));
            assertThat(new BigDecimal("-10.415").setScale(2, RoundingMode.FLOOR))
                    .isEqualTo(new BigDecimal("-10.42"));
        }

        @ParameterizedTest(name = "storing {0} at scale 2 yields {1}")
        @CsvSource({
            // Excess fraction digits, both signs, across representative magnitudes.
            "10.419,    10.41",
            "10.415,    10.41",
            "10.411,    10.41",
            "1.239,     1.23",
            "1.999,     1.99",
            "-10.419,   -10.41",
            "-10.415,   -10.41",
            "-1.239,    -1.23",
            "-1.999,    -1.99",
            // Truncation collapses a sub-cent magnitude to zero rather than promoting it to a cent.
            // BigDecimal has no negative zero, so -0.009 stores as 0.00 and not as -0.00.
            "0.009,     0.00",
            "-0.009,    0.00",
            // The repeating quotient of the interest formula, truncated in isolation.
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

            // isEqualTo delegates to BigDecimal.equals, which compares SCALE as well as value: it
            // is the strict comparison and the right one here, because a scale-3 result would
            // serialise one byte too wide even though it is numerically identical. hasScaleOf then
            // states the scale directly rather than leaving it implied.
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
            // Scaling up is always exact. It matters because a sender narrower than its receiver
            // must still produce the receiver's full digit count in the record image.
            assertThat(CobolDecimal.store(new BigDecimal(input), 2))
                    .isEqualTo(new BigDecimal(expected))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a scale of zero is permitted, for the unscaled PIC 9(n) fields")
        void supportsScaleZero() {
            // Not every field is scaled: PIC 9(11) ACCT-ID and PIC 9(09) counters are scale 0.
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
            // The widest value an ACCT-CURR-BAL can hold [app/cpy/CVACT01Y.cpy:7].
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("9999999999.99"),
                    TEN_DIGIT_PRECISION, 2))
                    .isEqualTo(new BigDecimal("9999999999.99"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("excess high-order digits are dropped silently for an S9(09)V99 receiver")
        void discardsHighOrderDigitsForNineDigitReceiver() {
            // A ten-integer-digit value stored into WS-MONTHLY-INT PIC S9(09)V99
            // [app/cbl/CBACT04C.cbl:L168]: the leading 1 is discarded and the low-order nine
            // integer digits survive.
            //
            // The phrase ON SIZE ERROR occurs ZERO times across all 28 programs, so COBOL raises no
            // condition on high-order overflow: it quietly keeps what fits. Anything that threw or
            // saturated here would diverge from the legacy behaviour.
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("1234567890.12"),
                    NINE_DIGIT_PRECISION, 2))
                    .isEqualTo(new BigDecimal("234567890.12"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("the negative counterpart keeps the sign of the computed value")
        void discardsHighOrderDigitsPreservingSign() {
            // The receiving field takes its sign from the computed value, which is what a remainder
            // by a power of ten reproduces: the remainder's sign follows the dividend.
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("-1234567890.12"),
                    NINE_DIGIT_PRECISION, 2))
                    .isEqualTo(new BigDecimal("-234567890.12"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("excess high-order digits are dropped for an S9(10)V99 receiver too")
        void discardsHighOrderDigitsForTenDigitReceiver() {
            // Eleven integer digits into the account record's S9(10)V99 money fields
            // [app/cpy/CVACT01Y.cpy:7-9,13-14].
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
            // Twenty-one integer digits into a nine-digit receiver. ON SIZE ERROR = 0 occurrences,
            // so this must return a value rather than raise anything at all.
            BigDecimal wildlyOversized = new BigDecimal("999999999999999999999.99");

            assertThat(CobolDecimal.storeAtPicture(wildlyOversized, NINE_DIGIT_PRECISION, 2))
                    .isEqualTo(new BigDecimal("999999999.99"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a value whose surviving digits are all zero wraps to 0.00, not to a maximum")
        void wrapsToZeroWhenEverySurvivingDigitIsZero() {
            // 1000000000.00 has ten integer digits; the low-order nine are all zero, so an
            // S9(09)V99 receiver ends up holding exactly zero. A saturating implementation would
            // have produced 999999999.99 here, which is the distinguishing observation.
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("1000000000.00"),
                    NINE_DIGIT_PRECISION, 2))
                    .isEqualTo(new BigDecimal("0.00"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("both ends truncate together: the fraction and the high-order digits at once")
        void truncatesBothEndsInOneOperation() {
            // 98765.4321 into PIC S9(4)V99: the fraction loses .0021 and the integer part loses
            // its leading 9.
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("98765.4321"), 4, 2))
                    .isEqualTo(new BigDecimal("8765.43"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("the fraction is truncated before the overflow test, never rounded into it")
        void truncatesTheFractionBeforeTestingForOverflow() {
            // 9999999999.999 has ten integer digits and three fractional digits. Truncating the
            // fraction first yields 9999999999.99, which still overflows a nine-digit receiver, so
            // the leading 9 is then discarded. Had the fraction been ROUNDED up instead, the value
            // would have carried into an eleventh integer digit and the residue would differ.
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("9999999999.999"),
                    NINE_DIGIT_PRECISION, 2))
                    .isEqualTo(new BigDecimal("999999999.99"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a value below one fits any receiver, though its precision is under its scale")
        void handlesValuesBelowOne() {
            // 0.419 has precision 3 and scale 3, so precision minus scale is 0: the guard must
            // treat a non-positive integer-digit count as fitting rather than as an anomaly.
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
            // integerDigits = 0 is meaningful, not a degenerate argument: a receiver with no digit
            // positions left of the implied point keeps the fraction and discards the rest.
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("10.419"), 0, 2))
                    .isEqualTo(new BigDecimal("0.41"))
                    .hasScaleOf(2);
        }

        @ParameterizedTest(name = "PIC S9({1})V99 storing {0} yields {2}")
        @CsvSource({
            // Exactly at the boundary of each precision declared in this codebase: nothing is lost.
            "9999999999.99, 10, 9999999999.99",
            "999999999.99,   9, 999999999.99",
            "9999.99,        4, 9999.99",
            // One integer digit over the boundary, in each declared precision.
            "12345678901.23, 10, 2345678901.23",
            "1234567890.12,   9, 234567890.12",
            "12345.67,        4, 2345.67",
            // Negative counterparts, which keep the sign of the computed value.
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

            // Asserting the SCALE, not merely the value. BigDecimal.ZERO has scale 0 and renders as
            // "0"; writing that into a scale-2 field would emit two bytes too few and shift every
            // offset after it in the 300-byte account record.
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
            // The entire reason the factory exists. BigDecimal.equals compares scale, so a scale-0
            // ZERO is a different value here and would serialise one byte too narrow. Numeric
            // comparison is therefore the correct operator for a zero test, and equality is not.
            assertThat(CobolDecimal.monetaryZero()).isNotEqualTo(BigDecimal.ZERO);
            assertThat(CobolDecimal.monetaryZero()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("CBACT04C.cbl:L353-L354 - both cycle-amount resets yield scale-2 zero")
        void reproducesTheCycleAmountResets() {
            // MOVE 0 TO ACCT-CURR-CYC-CREDIT [app/cbl/CBACT04C.cbl:L353]
            // MOVE 0 TO ACCT-CURR-CYC-DEBIT  [app/cbl/CBACT04C.cbl:L354]
            // Both receivers are PIC S9(10)V99 [app/cpy/CVACT01Y.cpy:13-14], so both must render
            // twelve digit positions, of which the last two are the fraction.
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
            // MOVE 0 TO WS-TOTAL-INT, into PIC S9(09)V99 [app/cbl/CBACT04C.cbl:L169].
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

        // Verb counts are the statement-initial figures: ADD 51, SUBTRACT 11, COMPUTE 32, taken
        // over comment-stripped source. A bare word-boundary grep instead reports ADD 53 and
        // COMPUTE 37, because a hyphen is a non-word character and so \bCOMPUTE\b also matches the
        // paragraph names 1300-COMPUTE-INTEREST and 1400-COMPUTE-FEES and the terminator
        // END-COMPUTE. The statement-initial figures are the ones cited throughout this file.

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
            // 1.005 + 0.006 is exactly 1.011; truncated at scale 2 that is 1.01. HALF_UP would give
            // 1.01 here as well, so the discriminating half-way cases live in the store block.
            assertThat(CobolDecimal.add(new BigDecimal("1.005"), new BigDecimal("0.006"), 2))
                    .isEqualTo(new BigDecimal("1.01"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("a half-way sum truncates down rather than rounding up")
        void addTruncatesAHalfWaySumDownwards() {
            // 1.230 + 0.005 is exactly 1.235, precisely half way between 1.23 and 1.24. HALF_UP and
            // HALF_EVEN would both give 1.24; truncation gives 1.23.
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
            // A scale-1 sender added to a scale-3 sender: the receiver's declared scale decides the
            // stored width, not whichever operand happened to be widest.
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
            // 0.00 - 0.019 is exactly -0.019; truncation toward zero gives -0.01, whereas FLOOR
            // would give -0.02.
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
            // 1000.00 * 12.50 is exactly 12500.0000 at scale 4; stored at scale 2 it is 12500.00.
            assertThat(CobolDecimal.multiplyAndStore(SAMPLE_CATEGORY_BALANCE,
                    SAMPLE_INTEREST_RATE, 2))
                    .isEqualTo(new BigDecimal("12500.00"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("the exact product of two scale-2 operands carries scale 4")
        void theExactProductCarriesTheSumOfTheOperandScales() {
            // Operand scales fix this completely: TRAN-CAT-BAL is S9(09)V99 and DIS-INT-RATE is
            // S9(04)V99, so BigDecimal.multiply yields scale 2 + 2 = 4 with no digits discarded.
            // Those two extra digits are precisely what the subsequent division still has to work
            // with, which is why the product must never be stored on the way through.
            BigDecimal exactProduct = SAMPLE_CATEGORY_BALANCE.multiply(SAMPLE_INTEREST_RATE);

            assertThat(exactProduct).isEqualTo(new BigDecimal("12500.0000")).hasScaleOf(4);
        }

        @Test
        @DisplayName("multiplyAndStore truncates a product with more fraction than the receiver")
        void multiplyAndStoreTruncatesExcessFraction() {
            // 1.11 * 1.11 is exactly 1.2321; truncated at scale 2 that is 1.23, never 1.24.
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
            // -1.11 * 1.11 is exactly -1.2321; DOWN gives -1.23 while FLOOR would give -1.24.
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
            // The reason the helper takes a mandatory scale rather than offering a convenient
            // scale-free overload. 1 / 1200 has no terminating decimal expansion, so the
            // no-argument BigDecimal.divide raises ArithmeticException. Since 1200 is the divisor of
            // the one division in the entire system [app/cbl/CBACT04C.cbl:L465], a scale-free
            // overload would fail on the system's ONLY division rather than on some exotic edge.
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> BigDecimal.ONE.divide(new BigDecimal("1200")))
                    .withMessageContaining("Non-terminating decimal expansion");

            // The class's helper handles the same operation without complaint.
            assertThat(CobolDecimal.divide(BigDecimal.ONE, new BigDecimal("1200"), 2))
                    .isEqualTo(new BigDecimal("0.00"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("no divide overload exists that omits the scale, so the mode cannot be dodged")
        void exposesNoScaleFreeDivideOverload() {
            // Structural guarantee rather than a behavioural one: every public divide entry point
            // requires a scale, so a caller cannot reach BigDecimal's own rounding defaults - nor
            // introduce HALF_UP, HALF_EVEN, CEILING or FLOOR - through this class. Equally, no
            // method accepts a RoundingMode, so COBOL_ROUNDING is the only mode reachable.
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
            // -100.00 / 3 is -33.333...; DOWN gives -33.33 while FLOOR would give -33.34.
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
            // new BigDecimal("0.00").equals(BigDecimal.ZERO) is false, so the guard has to test
            // signum rather than equality. This case is what makes that distinction observable, and
            // it matters because every monetary value in this system carries scale 2.
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
            // The subtlest correctness property in the whole class, and the one most likely to be
            // broken by an intuitive refactor that "tidies up" by storing the product first.
            //
            // 1.11 * 1.11 is exactly 1.2321. Dividing that exact product by 3 into a scale-4
            // receiver gives 0.4107. Truncating the product to scale 2 first would give 1.23, and
            // 1.23 / 3 is 0.41, which at scale 4 is 0.4100. The two orderings therefore disagree in
            // the third decimal place, and only the store-once ordering matches COBOL, which keeps
            // full intermediate precision within a single COMPUTE expression.
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
            // 0.01 * 0.01 is exactly 0.0001. An intermediate store at scale 2 would already have
            // flattened that to 0.00 and the result would be zero; keeping the product exact
            // preserves it.
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

            // COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
            //   [app/cbl/CBACT04C.cbl:L464-L465], receiver WS-MONTHLY-INT PIC S9(09)V99 at L168.
            // The exact product is 12500.0000 and the exact quotient is 10.41666...; the COMPUTE
            // carries no ROUNDED phrase, so the stored value is 10.41. 10.42 is the answer this
            // system would give if it rounded, and it does not.
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
            // Every row below was derived twice - once with 60-digit decimal arithmetic and once
            // against this class - and the two agree. The rounding-mode discriminators are:
            //
            //  * 1000.00 / 12.50 -> quotient 10.41666...  DOWN 10.41, HALF_UP and HALF_EVEN 10.42
            //  * 100.00  /  5.00 -> quotient  0.41666...  DOWN  0.41, HALF_UP and HALF_EVEN  0.42
            //       These two rows are the DOWN-versus-HALF_UP discriminators.
            //  * -1000.00 / 12.50 -> quotient -10.41666... DOWN -10.41, FLOOR -10.42
            //       This row is THE DOWN-versus-FLOOR discriminator: for every positive input FLOOR
            //       and DOWN agree, so without a negative row a FLOOR implementation passes.
            //  * 2500.75 / 19.99 -> product 49989.9925, quotient 41.65832708333...
            //       DOWN 41.65, HALF_UP 41.66.
            //  * 0.01 / 1.00 -> quotient 0.00000833..., DOWN 0.00: a sub-cent result truncates to
            //       zero rather than being promoted to a cent.
            "1000.00,   12.50,  10.41",
            "100.00,     5.00,   0.41",
            "-1000.00,  12.50, -10.41",
            "2500.75,   19.99,  41.65",
            "0.01,       1.00,   0.00",
            // A zero rate is legitimate: CBACT04C falls back to a DEFAULT disclosure group when no
            // specific group matches, and that group's DIS-INT-RATE may be zero.
            "1000.00,    0.00,   0.00",
            // A zero balance produces no interest.
            "0.00,      12.50,   0.00",
            // Further negative coverage. -2500.75 / 19.99 is a second DOWN-versus-FLOOR
            // discriminator: DOWN -41.65, FLOOR -41.66. And -0.01 / 1.00 truncates to 0.00, because
            // BigDecimal has no negative zero, whereas FLOOR would have produced -0.01.
            "-2500.75,  19.99, -41.65",
            "-0.01,      1.00,   0.00",
            // A rate near the top of DIS-INT-RATE's S9(04)V99 range, and small balances whose
            // interest truncates to zero or to a single cent.
            "100.00,    24.99,   2.08",
            "1.00,      11.99,   0.00",
            "10.00,     11.99,   0.09",
            "500.00,    18.00,   7.50",
        })
        @DisplayName("the formula truncates to 2 dp across the representative input range")
        void truncatesAcrossTheInputRange(String balance, String rate, String expected) {
            BigDecimal interest =
                    CobolDecimal.monthlyInterest(new BigDecimal(balance), new BigDecimal(rate));

            // Value AND scale: WS-MONTHLY-INT is PIC S9(09)V99, so a scale other than 2 would
            // serialise the wrong width even where the number is right.
            assertThat(interest).isEqualTo(new BigDecimal(expected)).hasScaleOf(2);
        }

        @ParameterizedTest(name = "{0} at {1} is not the rounded answer {2}")
        @CsvSource({
            "1000.00,   12.50,  10.42",
            "100.00,     5.00,   0.42",
            "2500.75,   19.99,  41.66",
            // The FLOOR answers for the negative rows.
            "-1000.00,  12.50, -10.42",
            "-2500.75,  19.99, -41.66",
            "-0.01,      1.00,  -0.01",
        })
        @DisplayName("the formula never produces the rounded or floored answer")
        void neverProducesTheRoundedAnswer(String balance, String rate, String wrongAnswer) {
            // Stated as an explicit negative so that switching the mode to HALF_UP, HALF_EVEN or
            // FLOOR fails here loudly rather than drifting a cent at a time through a batch run.
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
            // ADD WS-MONTHLY-INT TO WS-TOTAL-INT [app/cbl/CBACT04C.cbl:L467] runs once per
            // transaction category, so a busy account accumulates many small interest amounts into
            // one scale-2 field before the account break posts it.
            //
            // This is the concrete argument for banning binary floating point. Accumulating 0.01 a
            // hundred times in a Java double yields 1.0000000000000007, not 1.0, because 0.01 has
            // no exact binary representation; the error then flows into ACCT-CURR-BAL and every
            // record written afterwards. BigDecimal at a fixed scale accumulates exactly.
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
            // 10.41 is the worked example's monthly interest. Seven such amounts are exactly 72.87;
            // the same sum in a double is 72.86999999999999, which truncates to 72.86 and loses a
            // cent. The scale never drifts either: it stays 2 at every step.
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
            // The realistic shape: each addend is itself the output of the interest formula, so the
            // accumulator is fed truncated scale-2 values rather than round numbers.
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

            // 10.41 + 0.41 + 41.65 = 52.47. Each addend was truncated before being added, which is
            // exactly what COBOL does: it stores WS-MONTHLY-INT and only then adds it.
            assertThat(runningTotal).isEqualTo(new BigDecimal("52.47")).hasScaleOf(2);
        }

        @Test
        @DisplayName("a credit category contributes negative interest and reduces the total")
        void accumulatesNegativeInterest() {
            BigDecimal runningTotal = CobolDecimal.add(new BigDecimal("10.41"),
                    CobolDecimal.monthlyInterest(new BigDecimal("-1000.00"),
                            new BigDecimal("12.50")),
                    CobolDecimal.MONETARY_SCALE);

            // 10.41 + (-10.41) = 0.00, and the zero must still carry scale 2.
            assertThat(runningTotal).isEqualTo(new BigDecimal("0.00")).hasScaleOf(2);
        }
    }

    @Nested
    @DisplayName("End-to-end COBOL statement sequences")
    class CobolStatementSequences {

        @Test
        @DisplayName("CBACT04C.cbl:L352-L354 - the arithmetic half of the account-break sequence")
        void reproducesTheAccountBreakArithmetic() {
            // Paragraph 1050-UPDATE-ACCOUNT [app/cbl/CBACT04C.cbl:L350] performs four ordered steps:
            //   L352  ADD WS-TOTAL-INT TO ACCT-CURR-BAL
            //   L353  MOVE 0 TO ACCT-CURR-CYC-CREDIT
            //   L354  MOVE 0 TO ACCT-CURR-CYC-DEBIT
            //   L356  REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
            //
            // The STEP ORDERING and the REWRITE itself are asserted by AccountInterestCalcJob's own
            // test, which owns the repository interaction; a unit test of this class has no record
            // and no repository to rewrite. What this file guarantees is the arithmetic those steps
            // depend on: the balance addition and the two scale-2 zeros. If the arithmetic here is
            // wrong, the job test cannot detect it, because the job would faithfully persist a wrong
            // number.
            BigDecimal totalInterest = CobolDecimal.monetaryZero();
            totalInterest = CobolDecimal.add(totalInterest,
                    CobolDecimal.monthlyInterest(new BigDecimal("1000.00"),
                            new BigDecimal("12.50")),
                    CobolDecimal.MONETARY_SCALE);
            totalInterest = CobolDecimal.add(totalInterest,
                    CobolDecimal.monthlyInterest(new BigDecimal("500.00"), new BigDecimal("18.00")),
                    CobolDecimal.MONETARY_SCALE);

            // 10.41 + 7.50 = 17.91
            assertThat(totalInterest).isEqualTo(new BigDecimal("17.91")).hasScaleOf(2);

            // L352, into ACCT-CURR-BAL PIC S9(10)V99 [app/cpy/CVACT01Y.cpy:7]. storeAtPicture is
            // used so the receiver's declared integer precision is honoured, not merely its scale.
            BigDecimal currentBalance = new BigDecimal("1500.00");
            BigDecimal updatedBalance = CobolDecimal.storeAtPicture(
                    currentBalance.add(totalInterest), TEN_DIGIT_PRECISION,
                    CobolDecimal.MONETARY_SCALE);
            assertThat(updatedBalance).isEqualTo(new BigDecimal("1517.91")).hasScaleOf(2);

            // The same posting expressed through add, which is what the translated job calls.
            assertThat(CobolDecimal.add(currentBalance, totalInterest,
                    CobolDecimal.MONETARY_SCALE)).isEqualTo(updatedBalance);

            // L353 and L354. Both receivers are PIC S9(10)V99 [app/cpy/CVACT01Y.cpy:13-14]; a
            // scale-0 zero would emit two bytes too few and shift every offset that follows in the
            // 300-byte account record.
            assertThat(CobolDecimal.monetaryZero()).hasScaleOf(2);
            assertThat(CobolDecimal.monetaryZero().toPlainString()).isEqualTo("0.00");
        }

        @Test
        @DisplayName("CBACT04C.cbl:L352 - posting interest onto a negative balance")
        void postsInterestOntoANegativeBalance() {
            // A credit balance is ordinary in this data model: ACCT-CURR-BAL is signed S9(10)V99.
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
            // Sender WS-MONTHLY-INT PIC S9(09)V99 [app/cbl/CBACT04C.cbl:L168] and receiver
            // TRAN-AMT PIC S9(09)V99 [app/cpy/CVTRA05Y.cpy:10] are width-for-width, so the move is
            // lossless and the stored transaction amount equals the computed interest exactly.
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
            // An S9(10)V99 balance [app/cpy/CVACT01Y.cpy:7] reduced by an S9(09)V99 amount
            // [app/cpy/CVTRA05Y.cpy:10] into a scale-2 receiver.
            BigDecimal balance = new BigDecimal("1517.91");
            BigDecimal payment = new BigDecimal("517.91");

            assertThat(CobolDecimal.subtract(balance, payment, CobolDecimal.MONETARY_SCALE))
                    .isEqualTo(new BigDecimal("1000.00"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("COBIL00C.cbl:L234 - a payment larger than the balance drives it negative")
        void billPaymentMayDriveTheBalanceNegative() {
            // Nothing in COBIL00C clamps the result at zero, so an overpayment simply produces a
            // negative ACCT-CURR-BAL. Clamping it would be a behaviour change.
            BigDecimal balance = new BigDecimal("100.00");
            BigDecimal payment = new BigDecimal("150.75");

            assertThat(CobolDecimal.subtract(balance, payment, CobolDecimal.MONETARY_SCALE))
                    .isEqualTo(new BigDecimal("-50.75"))
                    .hasScaleOf(2);
        }

        @Test
        @DisplayName("COBIL00C.cbl:L234 - operands of differing scales still store at scale 2")
        void billPaymentNormalisesDifferingOperandScales() {
            // A scale-1 balance debited by a scale-2 amount: 1517.9 - 517.91 is exactly 999.99. The
            // receiver's declared scale decides the stored width, so the result is 999.99 and not
            // 999.9. Differing scales arise whenever a value has been through a computation that
            // produced fewer fractional digits than the field declares.
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
            // COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
            //                     - ACCT-CURR-CYC-DEBIT
            //                     + DALYTRAN-AMT
            // [app/cbl/CBTRN02C.cbl:L403-L405], receiver WS-TEMP-BAL PIC S9(09)V99 at L187.
            // All three operands are scale 2, so chaining at scale 2 equals evaluating the whole
            // expression exactly and storing once.
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
            // The discriminating variant. Here one operand carries three fractional digits, so the
            // exact expression value is 1625.745 - wider than the S9(09)V99 receiver at
            // [app/cbl/CBTRN02C.cbl:L187]. It is the STORE that fixes the scale, and it truncates:
            // the stored value is 1625.74. HALF_UP or HALF_EVEN would have produced 1625.75, which
            // is a cent adrift and would then be compared against ACCT-CREDIT-LIMIT at L407 - so a
            // rounding error here can flip an over-limit decision.
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
            // IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL [app/cbl/CBTRN02C.cbl:L407]. compareTo is the
            // correct operator: equals compares scale as well as value and would report a false
            // mismatch between 1625.75 and 1625.750, wrongly rejecting a transaction that is
            // exactly at the limit.
            BigDecimal creditLimit = new BigDecimal("1625.75");
            BigDecimal temporaryBalance = CobolDecimal.storeMonetary(new BigDecimal("1625.750"));

            assertThat(creditLimit).isEqualByComparingTo(temporaryBalance);
            assertThat(creditLimit.compareTo(temporaryBalance)).isZero();
            assertThat(creditLimit.compareTo(temporaryBalance) >= 0).isTrue();
        }

        @Test
        @DisplayName("CBACT04C.cbl:L518-L520 - the fee stub contributes nothing, by design")
        void theFeeStubContributesNothing() {
            // Paragraph 1400-COMPUTE-FEES is marked "* To be implemented" and its body is a bare
            // EXIT [app/cbl/CBACT04C.cbl:L518-L520]. Preserving that as a no-op is a parity
            // requirement, not an oversight: computing a fee would be a new feature. Expressed here
            // as the arithmetic identity the stub implies - adding a zero fee leaves the balance and
            // its scale untouched.
            BigDecimal balanceBeforeFees = new BigDecimal("1517.91");

            BigDecimal balanceAfterFees = CobolDecimal.add(balanceBeforeFees,
                    CobolDecimal.monetaryZero(), CobolDecimal.MONETARY_SCALE);

            assertThat(balanceAfterFees).isEqualTo(balanceBeforeFees).hasScaleOf(2);
        }
    }
}
