package com.blitzy.carddemo.tests.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blitzy.carddemo.domain.util.Decimals;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * jqwik 1.9.3 property-based tests for {@link Decimals}.
 *
 * <p>This class is the 100% line-coverage quality gate for the central monetary
 * arithmetic facade per AAP &sect;0.6.1 and AAP &sect;0.7.2. Each property
 * exercises a different behavioral invariant of {@code Decimals}, with random
 * inputs drawn from COBOL-valid {@code PIC S9(n)V99} ranges to ensure
 * byte-for-byte parity with the COBOL baseline.
 *
 * <p>Coverage targets (every line of {@link Decimals} MUST be reached):
 * <ul>
 *   <li>add / subtract / multiply / divide / scaled &mdash;
 *       arithmetic + null/scale validation</li>
 *   <li>multiplyRounded / divideRounded &mdash; HALF_EVEN (banker's) rounding</li>
 *   <li>parseSignedPacked / encodeSignedPacked &mdash;
 *       COMP-3 round-trip + sign nibble cases</li>
 *   <li>parseZonedDecimal / encodeZonedDecimal &mdash;
 *       ASCII zoned-decimal + all overpunch chars</li>
 *   <li>formatEditMask &mdash; fixed-width COBOL-edited display formatting
 *       with HALF_EVEN rounding</li>
 * </ul>
 *
 * <p>Generator domain ranges are derived directly from COBOL copybooks under
 * {@code app/cpy/}:
 * <ul>
 *   <li>{@code PIC S9(10)V99} (ACCT-CURR-BAL, ACCT-CREDIT-LIMIT,
 *       ACCT-CASH-CREDIT-LIMIT, ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT in
 *       {@code CVACT01Y.cpy}) &rarr;
 *       {@code [-9_999_999_999.99, +9_999_999_999.99]}</li>
 *   <li>{@code PIC S9(09)V99} (TRAN-AMT in {@code CVTRA05Y.cpy},
 *       DALYTRAN-AMT in {@code CVTRA06Y.cpy}, TRAN-CAT-BAL in
 *       {@code CVTRA01Y.cpy}) &rarr; {@code [-999_999_999.99,
 *       +999_999_999.99]}</li>
 *   <li>{@code PIC S9(04)V99} (DIS-INT-RATE in {@code CVTRA02Y.cpy})
 *       &rarr; {@code [-9_999.99, +9_999.99]}</li>
 * </ul>
 *
 * <p>Source COBOL reference programs:
 * <ul>
 *   <li>{@code app/cbl/CBACT04C.cbl} L462-L468 &mdash; interest formula
 *       {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
 *       (NO {@code ROUNDED} clause &rarr; uses {@code DEFAULT_MODE} = DOWN
 *       truncation)</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl} L508, L527, L547, L549, L551 &mdash;
 *       posting ADDs against running balances (no {@code ROUNDED} &rarr;
 *       truncation throughout)</li>
 * </ul>
 *
 * <p>Forbidden constructs (per AAP &sect;0.6.7 / agent prompt Phase 14):
 * <ul>
 *   <li>NO {@code double} or {@code float} for any value (AAP &sect;0.6.1
 *       verbatim defect)</li>
 *   <li>NO {@code org.junit.jupiter.api.Assertions} &mdash; AssertJ exclusively</li>
 *   <li>NO {@code org.junit.jupiter.api.Test} &mdash; jqwik {@link Property}
 *       and {@link Example} exclusively</li>
 *   <li>NO {@code System.out} / {@code System.err} print statements</li>
 *   <li>NO {@code --enable-preview} JVM flag features</li>
 * </ul>
 *
 * @see com.blitzy.carddemo.domain.util.Decimals
 */
public class DecimalsProperties {

    // =====================================================================
    // Phase 1 - Generator (@Provide) methods
    // =====================================================================

    /**
     * COBOL {@code PIC S9(10)V99} domain: account-monetary fields in
     * {@code CVACT01Y.cpy}.
     *
     * @return BigDecimal arbitrary in the closed range
     *         {@code [-9_999_999_999.99, +9_999_999_999.99]} with scale 2.
     */
    @Provide
    Arbitrary<BigDecimal> accountMonetary() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("-9999999999.99"), new BigDecimal("9999999999.99"))
                .ofScale(2)
                .shrinkTowards(BigDecimal.ZERO);
    }

    /**
     * COBOL {@code PIC S9(09)V99} domain: transaction-monetary fields in
     * {@code CVTRA05Y.cpy}, {@code CVTRA06Y.cpy}, and {@code CVTRA01Y.cpy}.
     *
     * @return BigDecimal arbitrary in the closed range
     *         {@code [-999_999_999.99, +999_999_999.99]} with scale 2.
     */
    @Provide
    Arbitrary<BigDecimal> transactionMonetary() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("-999999999.99"), new BigDecimal("999999999.99"))
                .ofScale(2)
                .shrinkTowards(BigDecimal.ZERO);
    }

    /**
     * COBOL {@code PIC S9(04)V99} domain: disclosure interest rate in
     * {@code CVTRA02Y.cpy} (DIS-INT-RATE).
     *
     * @return BigDecimal arbitrary in the closed range {@code [-9_999.99,
     *         +9_999.99]} with scale 2.
     */
    @Provide
    Arbitrary<BigDecimal> interestRate() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("-9999.99"), new BigDecimal("9999.99"))
                .ofScale(2)
                .shrinkTowards(BigDecimal.ZERO);
    }

    /**
     * Non-zero transaction-monetary domain for divide tests.
     *
     * @return {@link #transactionMonetary()} filtered to exclude exact zero.
     */
    @Provide
    Arbitrary<BigDecimal> nonZeroTransactionMonetary() {
        return transactionMonetary().filter(v -> v.signum() != 0);
    }

    /**
     * The set of {@link RoundingMode} values that the {@link Decimals} API
     * accepts safely (every value except {@link RoundingMode#UNNECESSARY},
     * which throws {@link ArithmeticException} when rounding is actually
     * required and is therefore not a "safe" caller-supplied mode for
     * general arithmetic).
     *
     * @return arbitrary cycling through the 7 safe rounding modes.
     */
    @Provide
    Arbitrary<RoundingMode> safeRoundingModes() {
        return Arbitraries.of(
                RoundingMode.UP, RoundingMode.DOWN,
                RoundingMode.CEILING, RoundingMode.FLOOR,
                RoundingMode.HALF_UP, RoundingMode.HALF_DOWN,
                RoundingMode.HALF_EVEN);
    }

    /**
     * Generates valid COMP-3 packed-decimal buffers with BCD digits in
     * positions 0..(2*length-2) and a recognized sign nibble
     * ({@code 0xD}, {@code 0xC}, or {@code 0xF}) in the trailing low
     * nibble.
     *
     * @return arbitrary {@code byte[]} buffer with length in [2, 8].
     */
    @Provide
    Arbitrary<byte[]> validComp3Buffers() {
        return Arbitraries.integers().between(2, 8).flatMap(len -> {
            int totalDigits = 2 * len - 1;
            Arbitrary<int[]> digits = Arbitraries.integers().between(0, 9)
                    .array(int[].class).ofSize(totalDigits);
            Arbitrary<Integer> signNibble = Arbitraries.of(0xD, 0xC, 0xF);
            return Combinators.combine(digits, signNibble).as((d, sn) -> {
                byte[] buf = new byte[len];
                for (int i = 0; i < totalDigits; i++) {
                    int byteIdx = i / 2;
                    if (i % 2 == 0) {
                        buf[byteIdx] = (byte) ((d[i] & 0x0F) << 4);
                    } else {
                        buf[byteIdx] |= (byte) (d[i] & 0x0F);
                    }
                }
                buf[len - 1] = (byte) ((buf[len - 1] & 0xF0) | (sn & 0x0F));
                return buf;
            });
        });
    }

    /**
     * Generates valid zoned-decimal buffers: ASCII digits in positions
     * 0..(length-2), with a valid overpunch character or plain digit in
     * the trailing position.
     *
     * @return arbitrary {@code byte[]} buffer with length in [3, 12].
     */
    @Provide
    Arbitrary<byte[]> validZonedBuffers() {
        return Arbitraries.integers().between(3, 12).flatMap(len -> {
            Arbitrary<int[]> leading = Arbitraries.integers().between('0', '9')
                    .array(int[].class).ofSize(len - 1);
            Arbitrary<Character> trailing = Arbitraries.of(
                    '{', '}',
                    'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I',
                    'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R',
                    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9');
            return Combinators.combine(leading, trailing).as((arr, last) -> {
                byte[] buf = new byte[len];
                for (int i = 0; i < arr.length; i++) {
                    buf[i] = (byte) arr[i];
                }
                buf[len - 1] = (byte) last.charValue();
                return buf;
            });
        });
    }

    // =====================================================================
    // Phase 2 - Constants verification (@Example tests)
    // =====================================================================

    @Example
    @Label("DEFAULT_MATH_CONTEXT must be MathContext.DECIMAL128 (34-digit precision)")
    void constantMathContextIsDecimal128() {
        assertThat(Decimals.DEFAULT_MATH_CONTEXT).isEqualTo(MathContext.DECIMAL128);
        assertThat(Decimals.DEFAULT_MATH_CONTEXT.getPrecision()).isEqualTo(34);
    }

    @Example
    @Label("ROUNDED_MODE must be HALF_EVEN (banker's rounding) per COBOL ROUNDED clause")
    void constantRoundedModeIsHalfEven() {
        assertThat(Decimals.ROUNDED_MODE).isEqualTo(RoundingMode.HALF_EVEN);
    }

    @Example
    @Label("DEFAULT_MODE must be DOWN (truncation) for unrounded COBOL arithmetic")
    void constantDefaultModeIsDown() {
        assertThat(Decimals.DEFAULT_MODE).isEqualTo(RoundingMode.DOWN);
    }

    @Example
    @Label("DEFAULT_MONETARY_SCALE must be 2 (for PIC S9(n)V99 fields)")
    void constantDefaultMonetaryScaleIsTwo() {
        assertThat(Decimals.DEFAULT_MONETARY_SCALE).isEqualTo(2);
    }

    // =====================================================================
    // Phase 3 - add arithmetic properties
    // =====================================================================

    @Property(tries = 1000)
    @Label("add: a + b == b + a (commutativity)")
    void addIsCommutative(
            @ForAll("transactionMonetary") BigDecimal a,
            @ForAll("transactionMonetary") BigDecimal b,
            @ForAll("safeRoundingModes") RoundingMode mode) {
        BigDecimal ab = Decimals.add(a, b, 2, mode);
        BigDecimal ba = Decimals.add(b, a, 2, mode);
        assertThat(ab).isEqualByComparingTo(ba);
        assertThat(ab.scale()).isEqualTo(2);
        assertThat(ba.scale()).isEqualTo(2);
    }

    @Property(tries = 1000)
    @Label("add: a + 0 == a (additive identity)")
    void addIdentity(@ForAll("transactionMonetary") BigDecimal a) {
        BigDecimal sum = Decimals.add(a, BigDecimal.ZERO, 2, RoundingMode.DOWN);
        assertThat(sum).isEqualByComparingTo(a);
        assertThat(sum.scale()).isEqualTo(2);
    }

    @Example
    @Label("add: 1.20 + 0 retains trailing zero (does not normalize to 1.2)")
    void addPreservesTrailingZeroScale() {
        BigDecimal result = Decimals.add(
                new BigDecimal("1.20"), BigDecimal.ZERO, 2, RoundingMode.DOWN);
        assertThat(result.toPlainString()).isEqualTo("1.20");
        assertThat(result.scale()).isEqualTo(2);
    }

    @Example
    @Label("add: null a throws NullPointerException")
    void addNullAThrows() {
        assertThatThrownBy(
                () -> Decimals.add(null, BigDecimal.ONE, 2, RoundingMode.DOWN))
                .isInstanceOf(NullPointerException.class);
    }

    @Example
    @Label("add: null b throws NullPointerException")
    void addNullBThrows() {
        assertThatThrownBy(
                () -> Decimals.add(BigDecimal.ONE, null, 2, RoundingMode.DOWN))
                .isInstanceOf(NullPointerException.class);
    }

    @Example
    @Label("add: null mode throws NullPointerException")
    void addNullModeThrows() {
        assertThatThrownBy(
                () -> Decimals.add(BigDecimal.ONE, BigDecimal.ONE, 2, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Property(tries = 50)
    @Label("add: negative scale throws IllegalArgumentException")
    void addNegativeScaleThrows(
            @ForAll @IntRange(min = -10, max = -1) int negScale,
            @ForAll("transactionMonetary") BigDecimal a,
            @ForAll("transactionMonetary") BigDecimal b) {
        assertThatThrownBy(() -> Decimals.add(a, b, negScale, RoundingMode.DOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("add: CBTRN02C posting parity - ACCT-CURR-BAL += DALYTRAN-AMT preserves scale 2")
    void addCobolPostingParity() {
        // CBTRN02C L547: ADD DALYTRAN-AMT TO ACCT-CURR-BAL (no ROUNDED).
        BigDecimal acctBal = new BigDecimal("1500.00");
        BigDecimal tranAmt = new BigDecimal("-75.50"); // debit
        BigDecimal updated = Decimals.add(acctBal, tranAmt, 2, RoundingMode.DOWN);
        assertThat(updated.toPlainString()).isEqualTo("1424.50");
        assertThat(updated.scale()).isEqualTo(2);
    }

    // =====================================================================
    // Phase 4 - subtract arithmetic properties
    // =====================================================================

    @Property(tries = 1000)
    @Label("subtract: a - b == -(b - a) (anti-commutativity)")
    void subtractIsAntiCommutative(
            @ForAll("transactionMonetary") BigDecimal a,
            @ForAll("transactionMonetary") BigDecimal b) {
        BigDecimal ab = Decimals.subtract(a, b, 2, RoundingMode.DOWN);
        BigDecimal ba = Decimals.subtract(b, a, 2, RoundingMode.DOWN);
        assertThat(ab).isEqualByComparingTo(ba.negate());
    }

    @Property(tries = 500)
    @Label("subtract: a - 0 == a")
    void subtractZeroIdentity(@ForAll("transactionMonetary") BigDecimal a) {
        BigDecimal result = Decimals.subtract(a, BigDecimal.ZERO, 2, RoundingMode.DOWN);
        assertThat(result).isEqualByComparingTo(a);
        assertThat(result.scale()).isEqualTo(2);
    }

    @Property(tries = 500)
    @Label("subtract: a - a == 0 at scale 2")
    void subtractSelfIsZero(@ForAll("transactionMonetary") BigDecimal a) {
        BigDecimal diff = Decimals.subtract(a, a, 2, RoundingMode.DOWN);
        assertThat(diff).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(diff.scale()).isEqualTo(2);
    }

    @Example
    @Label("subtract: null a throws NullPointerException")
    void subtractNullAThrows() {
        assertThatThrownBy(
                () -> Decimals.subtract(null, BigDecimal.ONE, 2, RoundingMode.DOWN))
                .isInstanceOf(NullPointerException.class);
    }

    @Example
    @Label("subtract: null b throws NullPointerException")
    void subtractNullBThrows() {
        assertThatThrownBy(
                () -> Decimals.subtract(BigDecimal.ONE, null, 2, RoundingMode.DOWN))
                .isInstanceOf(NullPointerException.class);
    }

    @Example
    @Label("subtract: null mode throws NullPointerException")
    void subtractNullModeThrows() {
        assertThatThrownBy(
                () -> Decimals.subtract(BigDecimal.ONE, BigDecimal.ONE, 2, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Property(tries = 50)
    @Label("subtract: negative scale throws IllegalArgumentException")
    void subtractNegativeScaleThrows(
            @ForAll @IntRange(min = -10, max = -1) int negScale,
            @ForAll("transactionMonetary") BigDecimal a,
            @ForAll("transactionMonetary") BigDecimal b) {
        assertThatThrownBy(
                () -> Decimals.subtract(a, b, negScale, RoundingMode.DOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =====================================================================
    // Phase 5 - multiply / multiplyRounded properties
    // =====================================================================

    @Property(tries = 1000)
    @Label("multiply: a * b == b * a (commutativity)")
    void multiplyIsCommutative(
            @ForAll("interestRate") BigDecimal a,
            @ForAll("interestRate") BigDecimal b) {
        BigDecimal ab = Decimals.multiply(a, b, 4, RoundingMode.DOWN);
        BigDecimal ba = Decimals.multiply(b, a, 4, RoundingMode.DOWN);
        assertThat(ab).isEqualByComparingTo(ba);
    }

    @Property(tries = 500)
    @Label("multiply: a * 0 == 0")
    void multiplyByZeroIsZero(@ForAll("transactionMonetary") BigDecimal a) {
        BigDecimal product = Decimals.multiply(a, BigDecimal.ZERO, 2, RoundingMode.DOWN);
        assertThat(product).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(product.scale()).isEqualTo(2);
    }

    @Property(tries = 500)
    @Label("multiply: a * 1 == a (at requested scale)")
    void multiplyByOneIsIdentity(@ForAll("transactionMonetary") BigDecimal a) {
        BigDecimal product = Decimals.multiply(a, BigDecimal.ONE, 2, RoundingMode.DOWN);
        assertThat(product).isEqualByComparingTo(a);
        assertThat(product.scale()).isEqualTo(2);
    }

    @Example
    @Label("multiplyRounded: 0.50 * 0.50 with scale 1 uses HALF_EVEN -> 0.2 (banker's: round to even)")
    void multiplyRoundedUsesHalfEven_RoundsToEven() {
        // 0.50 * 0.50 = 0.2500; rounded to 1 decimal HALF_EVEN -> 0.2 (even neighbor).
        BigDecimal product = Decimals.multiplyRounded(
                new BigDecimal("0.50"), new BigDecimal("0.50"), 1);
        assertThat(product.toPlainString()).isEqualTo("0.2");
        assertThat(product.scale()).isEqualTo(1);
    }

    @Example
    @Label("multiplyRounded: 1.5 * 1.0 with scale 0 uses HALF_EVEN -> 2 (even up)")
    void multiplyRoundedUsesHalfEven_RoundsUpToEven() {
        BigDecimal product = Decimals.multiplyRounded(
                new BigDecimal("1.5"), new BigDecimal("1.0"), 0);
        assertThat(product.toPlainString()).isEqualTo("2");
    }

    @Example
    @Label("multiplyRounded: 2.5 * 1.0 with scale 0 uses HALF_EVEN -> 2 (even down)")
    void multiplyRoundedUsesHalfEven_RoundsDownToEven() {
        BigDecimal product = Decimals.multiplyRounded(
                new BigDecimal("2.5"), new BigDecimal("1.0"), 0);
        assertThat(product.toPlainString()).isEqualTo("2");
    }

    @Property(tries = 1000)
    @Label("multiplyRounded: result.scale() == requested scale (preserves trailing zeros)")
    void multiplyRoundedPreservesScale(
            @ForAll("interestRate") BigDecimal a,
            @ForAll("interestRate") BigDecimal b,
            @ForAll @IntRange(min = 0, max = 6) int scale) {
        BigDecimal product = Decimals.multiplyRounded(a, b, scale);
        assertThat(product.scale()).isEqualTo(scale);
    }

    @Example
    @Label("multiply: null a throws NullPointerException")
    void multiplyNullAThrows() {
        assertThatThrownBy(
                () -> Decimals.multiply(null, BigDecimal.ONE, 2, RoundingMode.DOWN))
                .isInstanceOf(NullPointerException.class);
    }

    @Example
    @Label("multiply: null b throws NullPointerException")
    void multiplyNullBThrows() {
        assertThatThrownBy(
                () -> Decimals.multiply(BigDecimal.ONE, null, 2, RoundingMode.DOWN))
                .isInstanceOf(NullPointerException.class);
    }

    @Example
    @Label("multiply: null mode throws NullPointerException")
    void multiplyNullModeThrows() {
        assertThatThrownBy(
                () -> Decimals.multiply(BigDecimal.ONE, BigDecimal.ONE, 2, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Property(tries = 50)
    @Label("multiply: negative scale throws IllegalArgumentException")
    void multiplyNegativeScaleThrows(
            @ForAll @IntRange(min = -10, max = -1) int negScale,
            @ForAll("transactionMonetary") BigDecimal a,
            @ForAll("transactionMonetary") BigDecimal b) {
        assertThatThrownBy(
                () -> Decimals.multiply(a, b, negScale, RoundingMode.DOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("multiplyRounded: null a throws NullPointerException")
    void multiplyRoundedNullAThrows() {
        assertThatThrownBy(
                () -> Decimals.multiplyRounded(null, BigDecimal.ONE, 2))
                .isInstanceOf(NullPointerException.class);
    }

    @Example
    @Label("multiplyRounded: null b throws NullPointerException")
    void multiplyRoundedNullBThrows() {
        assertThatThrownBy(
                () -> Decimals.multiplyRounded(BigDecimal.ONE, null, 2))
                .isInstanceOf(NullPointerException.class);
    }

    @Property(tries = 50)
    @Label("multiplyRounded: negative scale throws IllegalArgumentException")
    void multiplyRoundedNegativeScaleThrows(
            @ForAll @IntRange(min = -10, max = -1) int negScale,
            @ForAll("transactionMonetary") BigDecimal a,
            @ForAll("transactionMonetary") BigDecimal b) {
        assertThatThrownBy(() -> Decimals.multiplyRounded(a, b, negScale))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =====================================================================
    // Phase 6 - divide / divideRounded properties
    // =====================================================================

    @Property(tries = 200)
    @Label("divide: division by zero throws ArithmeticException")
    void divideByZeroThrows(@ForAll("transactionMonetary") BigDecimal a) {
        assertThatThrownBy(
                () -> Decimals.divide(a, BigDecimal.ZERO, 2, RoundingMode.DOWN))
                .isInstanceOf(ArithmeticException.class);
    }

    @Property(tries = 200)
    @Label("divideRounded: division by zero throws ArithmeticException")
    void divideRoundedByZeroThrows(@ForAll("transactionMonetary") BigDecimal a) {
        assertThatThrownBy(() -> Decimals.divideRounded(a, BigDecimal.ZERO, 2))
                .isInstanceOf(ArithmeticException.class);
    }

    @Property(tries = 500)
    @Label("divide: a / 1 == a at requested scale")
    void divideByOneIsIdentity(@ForAll("transactionMonetary") BigDecimal a) {
        BigDecimal q = Decimals.divide(a, BigDecimal.ONE, 2, RoundingMode.DOWN);
        assertThat(q).isEqualByComparingTo(a);
        assertThat(q.scale()).isEqualTo(2);
    }

    @Example
    @Label("divideRounded: 1 / 4 with scale 1 uses HALF_EVEN -> 0.2 (banker's)")
    void divideRoundedUsesHalfEven() {
        // 1 / 4 = 0.25; scale 1 HALF_EVEN -> 0.2 (round to even).
        BigDecimal q = Decimals.divideRounded(BigDecimal.ONE, new BigDecimal("4"), 1);
        assertThat(q.toPlainString()).isEqualTo("0.2");
    }

    @Example
    @Label("divide: CBACT04C interest formula parity - (1000.00 * 12.00) / 1200 = 10.00 (truncated)")
    void divideCobolInterestFormulaParity() {
        // COBOL: COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
        // No ROUNDED -> truncation (RoundingMode.DOWN).
        BigDecimal balance = new BigDecimal("1000.00");
        BigDecimal rate = new BigDecimal("12.00");
        BigDecimal product = Decimals.multiply(balance, rate, 4, RoundingMode.DOWN);
        BigDecimal interest = Decimals.divide(
                product, new BigDecimal("1200"), 2, RoundingMode.DOWN);
        assertThat(interest.toPlainString()).isEqualTo("10.00");
        assertThat(interest.scale()).isEqualTo(2);
    }

    @Example
    @Label("divide: CBACT04C interest formula - truncation, not rounding (0.0825 -> 0.08)")
    void divideCobolInterestTruncatesNotRounds() {
        // (100.00 * 0.99) / 1200 = 99.00 / 1200 = 0.0825 exactly -> scale 2 truncated = 0.08.
        BigDecimal product = Decimals.multiply(
                new BigDecimal("100.00"), new BigDecimal("0.99"), 4, RoundingMode.DOWN);
        BigDecimal interest = Decimals.divide(
                product, new BigDecimal("1200"), 2, RoundingMode.DOWN);
        assertThat(interest.toPlainString()).isEqualTo("0.08");
    }

    @Property(tries = 1000)
    @Label("divideRounded: result.scale() == requested scale")
    void divideRoundedPreservesScale(
            @ForAll("transactionMonetary") BigDecimal a,
            @ForAll("nonZeroTransactionMonetary") BigDecimal b,
            @ForAll @IntRange(min = 0, max = 6) int scale) {
        BigDecimal q = Decimals.divideRounded(a, b, scale);
        assertThat(q.scale()).isEqualTo(scale);
    }

    @Example
    @Label("divide: null a throws NullPointerException")
    void divideNullAThrows() {
        assertThatThrownBy(
                () -> Decimals.divide(null, BigDecimal.ONE, 2, RoundingMode.DOWN))
                .isInstanceOf(NullPointerException.class);
    }

    @Example
    @Label("divide: null b throws NullPointerException")
    void divideNullBThrows() {
        assertThatThrownBy(
                () -> Decimals.divide(BigDecimal.ONE, null, 2, RoundingMode.DOWN))
                .isInstanceOf(NullPointerException.class);
    }

    @Example
    @Label("divide: null mode throws NullPointerException")
    void divideNullModeThrows() {
        assertThatThrownBy(
                () -> Decimals.divide(BigDecimal.ONE, BigDecimal.ONE, 2, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Property(tries = 50)
    @Label("divide: negative scale throws IllegalArgumentException")
    void divideNegativeScaleThrows(
            @ForAll @IntRange(min = -10, max = -1) int negScale,
            @ForAll("transactionMonetary") BigDecimal a,
            @ForAll("nonZeroTransactionMonetary") BigDecimal b) {
        assertThatThrownBy(
                () -> Decimals.divide(a, b, negScale, RoundingMode.DOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("divideRounded: null a throws NullPointerException")
    void divideRoundedNullAThrows() {
        assertThatThrownBy(() -> Decimals.divideRounded(null, BigDecimal.ONE, 2))
                .isInstanceOf(NullPointerException.class);
    }

    @Example
    @Label("divideRounded: null b throws NullPointerException")
    void divideRoundedNullBThrows() {
        assertThatThrownBy(() -> Decimals.divideRounded(BigDecimal.ONE, null, 2))
                .isInstanceOf(NullPointerException.class);
    }

    @Property(tries = 50)
    @Label("divideRounded: negative scale throws IllegalArgumentException")
    void divideRoundedNegativeScaleThrows(
            @ForAll @IntRange(min = -10, max = -1) int negScale,
            @ForAll("transactionMonetary") BigDecimal a,
            @ForAll("nonZeroTransactionMonetary") BigDecimal b) {
        assertThatThrownBy(() -> Decimals.divideRounded(a, b, negScale))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =====================================================================
    // Phase 7 - scaled properties
    // =====================================================================

    @Property(tries = 1000)
    @Label("scaled: result.scale() == requested scale")
    void scaledPreservesRequestedScale(
            @ForAll("transactionMonetary") BigDecimal v,
            @ForAll @IntRange(min = 0, max = 10) int scale) {
        BigDecimal result = Decimals.scaled(v, scale, RoundingMode.DOWN);
        assertThat(result.scale()).isEqualTo(scale);
    }

    @Property(tries = 500)
    @Label("scaled: idempotent for same scale and mode")
    void scaledIsIdempotent(
            @ForAll("transactionMonetary") BigDecimal v,
            @ForAll @IntRange(min = 0, max = 6) int scale,
            @ForAll("safeRoundingModes") RoundingMode mode) {
        BigDecimal once = Decimals.scaled(v, scale, mode);
        BigDecimal twice = Decimals.scaled(once, scale, mode);
        assertThat(twice).isEqualTo(once);
    }

    @Example
    @Label("scaled: scaled(1.20, 2, DOWN) preserves trailing zero -> '1.20'")
    void scaledPreservesTrailingZero() {
        BigDecimal v = Decimals.scaled(new BigDecimal("1.20"), 2, RoundingMode.DOWN);
        assertThat(v.toPlainString()).isEqualTo("1.20");
    }

    @Example
    @Label("scaled: null value throws NullPointerException")
    void scaledNullValueThrows() {
        assertThatThrownBy(() -> Decimals.scaled(null, 2, RoundingMode.DOWN))
                .isInstanceOf(NullPointerException.class);
    }

    @Example
    @Label("scaled: null mode throws NullPointerException")
    void scaledNullModeThrows() {
        assertThatThrownBy(() -> Decimals.scaled(BigDecimal.ONE, 2, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Property(tries = 50)
    @Label("scaled: negative scale throws IllegalArgumentException")
    void scaledNegativeScaleThrows(
            @ForAll @IntRange(min = -10, max = -1) int negScale,
            @ForAll("transactionMonetary") BigDecimal v) {
        assertThatThrownBy(() -> Decimals.scaled(v, negScale, RoundingMode.DOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =====================================================================
    // Phase 8 - parseSignedPacked / encodeSignedPacked (COMP-3) properties
    // =====================================================================

    @Property(tries = 1000)
    @Label("COMP-3 round-trip: parseSignedPacked(encodeSignedPacked(v, 6, 2), 0, 6, 2) == v")
    void comp3EncodeParseRoundTrip(@ForAll("transactionMonetary") BigDecimal v) {
        // PIC S9(09)V99 = 11 digits = 6 bytes COMP-3.
        int length = 6;
        byte[] encoded = Decimals.encodeSignedPacked(v, length, 2);
        assertThat(encoded).hasSize(length);
        BigDecimal decoded = Decimals.parseSignedPacked(encoded, 0, length, 2);
        assertThat(decoded).isEqualByComparingTo(v);
        assertThat(decoded.scale()).isEqualTo(2);
    }

    @Property(tries = 1000)
    @Label("COMP-3 reverse round-trip: encoding the parse of a buffer re-emits the digit nibbles and canonicalizes the sign nibble")
    void comp3ParseEncodeRoundTrip(@ForAll("validComp3Buffers") byte[] buf) {
        int length = buf.length;
        // Parse with scale 0 (no decimal interpretation).
        BigDecimal v = Decimals.parseSignedPacked(buf, 0, length, 0);
        byte[] reencoded = Decimals.encodeSignedPacked(v, length, 0);
        // Digit nibbles in bytes 0..length-2 must match exactly.
        for (int i = 0; i < length - 1; i++) {
            assertThat(reencoded[i]).isEqualTo(buf[i]);
        }
        // High nibble of last byte (last digit) must match.
        assertThat(reencoded[length - 1] & 0xF0).isEqualTo(buf[length - 1] & 0xF0);
        // Sign-nibble canonicalization: encoder always emits 0xC (positive)
        // or 0xD (negative); the 0xF sign nibble parses positive and
        // canonicalizes to 0xC on re-encode.
        int newSign = reencoded[length - 1] & 0x0F;
        if (v.signum() < 0) {
            assertThat(newSign).isEqualTo(0xD);
        } else {
            assertThat(newSign).isEqualTo(0xC);
        }
    }

    @Example
    @Label("parseSignedPacked: sign nibble 0xD parses as negative")
    void comp3SignNibbleDIsNegative() {
        // Value -123 in 2 bytes: digits 123, sign D.
        byte[] buf = new byte[] { (byte) 0x12, (byte) 0x3D };
        BigDecimal v = Decimals.parseSignedPacked(buf, 0, 2, 0);
        assertThat(v).isEqualByComparingTo(new BigDecimal("-123"));
    }

    @Example
    @Label("parseSignedPacked: sign nibble 0xC parses as positive (signed)")
    void comp3SignNibbleCIsPositive() {
        byte[] buf = new byte[] { (byte) 0x12, (byte) 0x3C };
        BigDecimal v = Decimals.parseSignedPacked(buf, 0, 2, 0);
        assertThat(v).isEqualByComparingTo(new BigDecimal("123"));
    }

    @Example
    @Label("parseSignedPacked: sign nibble 0xF parses as positive (unsigned)")
    void comp3SignNibbleFIsPositive() {
        byte[] buf = new byte[] { (byte) 0x12, (byte) 0x3F };
        BigDecimal v = Decimals.parseSignedPacked(buf, 0, 2, 0);
        assertThat(v).isEqualByComparingTo(new BigDecimal("123"));
    }

    @Example
    @Label("parseSignedPacked: invalid sign nibble (e.g. 0xE) throws IllegalArgumentException")
    void comp3InvalidSignNibbleThrows() {
        byte[] buf = new byte[] { (byte) 0x12, (byte) 0x3E };
        assertThatThrownBy(() -> Decimals.parseSignedPacked(buf, 0, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("parseSignedPacked: BCD nibble > 9 throws IllegalArgumentException")
    void comp3InvalidDigitNibbleThrows() {
        // High nibble of byte 0 is 0xA (invalid BCD).
        byte[] buf = new byte[] { (byte) 0xA0, (byte) 0x0C };
        assertThatThrownBy(() -> Decimals.parseSignedPacked(buf, 0, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("encodeSignedPacked: negative values emit 0xD sign nibble")
    void comp3EncodeNegativeEmitsD() {
        byte[] buf = Decimals.encodeSignedPacked(new BigDecimal("-123"), 2, 0);
        assertThat(buf[1] & 0x0F).isEqualTo(0xD);
    }

    @Example
    @Label("encodeSignedPacked: positive values emit 0xC sign nibble")
    void comp3EncodePositiveEmitsC() {
        byte[] buf = Decimals.encodeSignedPacked(new BigDecimal("123"), 2, 0);
        assertThat(buf[1] & 0x0F).isEqualTo(0xC);
    }

    @Example
    @Label("encodeSignedPacked: zero emits 0xC sign nibble (positive zero)")
    void comp3EncodeZeroEmitsC() {
        byte[] buf = Decimals.encodeSignedPacked(BigDecimal.ZERO, 2, 0);
        assertThat(buf[1] & 0x0F).isEqualTo(0xC);
    }

    @Example
    @Label("encodeSignedPacked: value exceeding 2*length-1 digits throws IllegalArgumentException")
    void comp3EncodeOverflowThrows() {
        // length=2 -> capacity 3 digits; encoding 4-digit value must throw.
        assertThatThrownBy(
                () -> Decimals.encodeSignedPacked(new BigDecimal("1234"), 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("parseSignedPacked: negative offset throws IAE or IndexOutOfBoundsException")
    void comp3NegativeOffsetThrows() {
        byte[] buf = new byte[] { 0x12, 0x3C };
        assertThatThrownBy(() -> Decimals.parseSignedPacked(buf, -1, 2, 0))
                .isInstanceOfAny(
                        IllegalArgumentException.class,
                        IndexOutOfBoundsException.class);
    }

    @Example
    @Label("parseSignedPacked: offset+length > buffer.length throws IndexOutOfBoundsException")
    void comp3OutOfBoundsThrows() {
        byte[] buf = new byte[] { 0x12, 0x3C };
        assertThatThrownBy(() -> Decimals.parseSignedPacked(buf, 1, 2, 0))
                .isInstanceOfAny(
                        IllegalArgumentException.class,
                        IndexOutOfBoundsException.class);
    }

    @Example
    @Label("parseSignedPacked: null buffer throws NullPointerException")
    void comp3ParseNullBufferThrows() {
        assertThatThrownBy(() -> Decimals.parseSignedPacked(null, 0, 2, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Example
    @Label("encodeSignedPacked: null value throws NullPointerException")
    void comp3EncodeNullValueThrows() {
        assertThatThrownBy(() -> Decimals.encodeSignedPacked(null, 2, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Property(tries = 50)
    @Label("parseSignedPacked: negative scale throws IllegalArgumentException")
    void comp3ParseNegativeScaleThrows(
            @ForAll @IntRange(min = -10, max = -1) int negScale) {
        byte[] buf = new byte[] { 0x12, 0x3C };
        assertThatThrownBy(
                () -> Decimals.parseSignedPacked(buf, 0, 2, negScale))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property(tries = 50)
    @Label("encodeSignedPacked: negative scale throws IllegalArgumentException")
    void comp3EncodeNegativeScaleThrows(
            @ForAll @IntRange(min = -10, max = -1) int negScale) {
        assertThatThrownBy(
                () -> Decimals.encodeSignedPacked(BigDecimal.ONE, 2, negScale))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =====================================================================
    // Phase 9 - parseZonedDecimal / encodeZonedDecimal properties
    // =====================================================================

    @Property(tries = 1000)
    @Label("Zoned round-trip: parse(encode(v, 11, 2), 0, 11, 2) == v for PIC S9(09)V99")
    void zonedEncodeParseRoundTrip(@ForAll("transactionMonetary") BigDecimal v) {
        // PIC S9(09)V99 -> 11 digits / 11 zoned bytes.
        int length = 11;
        byte[] encoded = Decimals.encodeZonedDecimal(v, length, 2);
        assertThat(encoded).hasSize(length);
        BigDecimal decoded = Decimals.parseZonedDecimal(encoded, 0, length, 2);
        assertThat(decoded).isEqualByComparingTo(v);
        assertThat(decoded.scale()).isEqualTo(2);
    }

    @Property(tries = 500)
    @Label("Zoned round-trip for PIC S9(10)V99: 12-byte width")
    void zonedEncodeParseRoundTripAccountMonetary(
            @ForAll("accountMonetary") BigDecimal v) {
        int length = 12;
        byte[] encoded = Decimals.encodeZonedDecimal(v, length, 2);
        BigDecimal decoded = Decimals.parseZonedDecimal(encoded, 0, length, 2);
        assertThat(decoded).isEqualByComparingTo(v);
    }

    @Example
    @Label("parseZonedDecimal: '0000005047G' (PIC S9(09)V99, scale 2) -> 504.77")
    void zonedFixtureG() {
        // G overpunch at position 11 contributes trailing digit 7 with positive sign.
        // Digits: 0000005047 + 7 -> 00000050477 -> movePointLeft(2) -> 504.77.
        byte[] buf = "0000005047G".getBytes(StandardCharsets.US_ASCII);
        BigDecimal v = Decimals.parseZonedDecimal(buf, 0, 11, 2);
        assertThat(v.toPlainString()).isEqualTo("504.77");
    }

    @Example
    @Label("parseZonedDecimal: '0000009190}' (PIC S9(09)V99, scale 2) -> -919.00")
    void zonedFixtureCurlyClose() {
        // '}' overpunch at position 11 contributes trailing digit 0 with negative sign.
        // Digits: 0000009190 + 0 -> 00000091900 -> negative -> movePointLeft(2) -> -919.00.
        byte[] buf = "0000009190}".getBytes(StandardCharsets.US_ASCII);
        BigDecimal v = Decimals.parseZonedDecimal(buf, 0, 11, 2);
        assertThat(v.toPlainString()).isEqualTo("-919.00");
    }

    @Example
    @Label("parseZonedDecimal: '00000001940{' (PIC S9(10)V99, scale 2) -> 194.00")
    void zonedFixtureCurlyOpen() {
        byte[] buf = "00000001940{".getBytes(StandardCharsets.US_ASCII);
        BigDecimal v = Decimals.parseZonedDecimal(buf, 0, 12, 2);
        assertThat(v.toPlainString()).isEqualTo("194.00");
    }

    @Example
    @Label("parseZonedDecimal: '0000000709R' (PIC S9(09)V99, scale 2) -> -70.99")
    void zonedFixtureR() {
        // R overpunch -> negative, lastDigit = R - J + 1 = 9.
        // Digits: 0000000709 + 9 -> 00000007099 -> negative -> movePointLeft(2) -> -70.99.
        byte[] buf = "0000000709R".getBytes(StandardCharsets.US_ASCII);
        BigDecimal v = Decimals.parseZonedDecimal(buf, 0, 11, 2);
        assertThat(v.toPlainString()).isEqualTo("-70.99");
    }

    @Example
    @Label("parseZonedDecimal: '00000020200{' (PIC S9(10)V99 ACCT-CREDIT-LIMIT, scale 2) -> 2020.00")
    void zonedFixtureCreditLimit() {
        byte[] buf = "00000020200{".getBytes(StandardCharsets.US_ASCII);
        BigDecimal v = Decimals.parseZonedDecimal(buf, 0, 12, 2);
        assertThat(v.toPlainString()).isEqualTo("2020.00");
    }

    @Example
    @Label("parseZonedDecimal: overpunch '{' = +0")
    void zonedOverpunchCurlyOpen() {
        BigDecimal v = Decimals.parseZonedDecimal(
                "00{".getBytes(StandardCharsets.US_ASCII), 0, 3, 0);
        assertThat(v).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Property(tries = 9)
    @Label("parseZonedDecimal: overpunch 'A'..'I' = +1..+9")
    void zonedOverpunchPositiveLetters(@ForAll @IntRange(min = 1, max = 9) int digit) {
        char overpunch = (char) ('A' + digit - 1);
        byte[] buf = ("00" + overpunch).getBytes(StandardCharsets.US_ASCII);
        BigDecimal v = Decimals.parseZonedDecimal(buf, 0, 3, 0);
        assertThat(v).isEqualByComparingTo(BigDecimal.valueOf(digit));
    }

    @Example
    @Label("parseZonedDecimal: overpunch '}' = -0")
    void zonedOverpunchCurlyClose() {
        BigDecimal v = Decimals.parseZonedDecimal(
                "00}".getBytes(StandardCharsets.US_ASCII), 0, 3, 0);
        // -0 must compare equal to 0.
        assertThat(v).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Property(tries = 9)
    @Label("parseZonedDecimal: overpunch 'J'..'R' = -1..-9")
    void zonedOverpunchNegativeLetters(@ForAll @IntRange(min = 1, max = 9) int digit) {
        char overpunch = (char) ('J' + digit - 1);
        byte[] buf = ("00" + overpunch).getBytes(StandardCharsets.US_ASCII);
        BigDecimal v = Decimals.parseZonedDecimal(buf, 0, 3, 0);
        assertThat(v).isEqualByComparingTo(BigDecimal.valueOf(-digit));
    }

    @Property(tries = 10)
    @Label("parseZonedDecimal: trailing digit '0'..'9' treated as positive (fixture compat)")
    void zonedTrailingDigitImpliedPositive(@ForAll @IntRange(min = 0, max = 9) int d) {
        byte[] buf = ("00" + d).getBytes(StandardCharsets.US_ASCII);
        BigDecimal v = Decimals.parseZonedDecimal(buf, 0, 3, 0);
        assertThat(v).isEqualByComparingTo(BigDecimal.valueOf(d));
    }

    @Example
    @Label("parseZonedDecimal: invalid trailing character ('@') throws IllegalArgumentException")
    void zonedInvalidTrailingThrows() {
        byte[] buf = "00@".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> Decimals.parseZonedDecimal(buf, 0, 3, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("parseZonedDecimal: non-digit in leading positions throws IllegalArgumentException")
    void zonedInvalidLeadingThrows() {
        byte[] buf = "0A0".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> Decimals.parseZonedDecimal(buf, 0, 3, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("encodeZonedDecimal: negative value emits 'J'..'R' or '}' for trailing char")
    void zonedEncodeNegativeUsesNegativeOverpunch() {
        byte[] buf = Decimals.encodeZonedDecimal(new BigDecimal("-50.47"), 11, 2);
        char trailing = (char) buf[buf.length - 1];
        assertThat("}JKLMNOPQR".indexOf(trailing)).isNotEqualTo(-1);
    }

    @Example
    @Label("encodeZonedDecimal: positive value emits '{' or 'A'..'I' for trailing char")
    void zonedEncodePositiveUsesPositiveOverpunch() {
        byte[] buf = Decimals.encodeZonedDecimal(new BigDecimal("50.47"), 11, 2);
        char trailing = (char) buf[buf.length - 1];
        assertThat("{ABCDEFGHI".indexOf(trailing)).isNotEqualTo(-1);
    }

    @Example
    @Label("encodeZonedDecimal: zero emits '{' (positive zero)")
    void zonedEncodeZeroUsesCurlyOpen() {
        byte[] buf = Decimals.encodeZonedDecimal(BigDecimal.ZERO, 3, 0);
        assertThat((char) buf[2]).isEqualTo('{');
    }

    @Example
    @Label("encodeZonedDecimal: value with more digits than length throws IllegalArgumentException")
    void zonedEncodeOverflowThrows() {
        // length=3 -> capacity 3 digits; encoding 4-digit value must throw.
        assertThatThrownBy(
                () -> Decimals.encodeZonedDecimal(new BigDecimal("1234"), 3, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("parseZonedDecimal: null buffer throws NullPointerException")
    void zonedParseNullBufferThrows() {
        assertThatThrownBy(() -> Decimals.parseZonedDecimal(null, 0, 3, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Example
    @Label("encodeZonedDecimal: null value throws NullPointerException")
    void zonedEncodeNullValueThrows() {
        assertThatThrownBy(() -> Decimals.encodeZonedDecimal(null, 3, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Example
    @Label("parseZonedDecimal: negative offset throws IAE or IndexOutOfBoundsException")
    void zonedParseNegativeOffsetThrows() {
        byte[] buf = "00{".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> Decimals.parseZonedDecimal(buf, -1, 3, 0))
                .isInstanceOfAny(
                        IllegalArgumentException.class,
                        IndexOutOfBoundsException.class);
    }

    @Example
    @Label("parseZonedDecimal: offset+length > buffer.length throws IndexOutOfBoundsException")
    void zonedParseOutOfBoundsThrows() {
        byte[] buf = "00{".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> Decimals.parseZonedDecimal(buf, 2, 3, 0))
                .isInstanceOfAny(
                        IllegalArgumentException.class,
                        IndexOutOfBoundsException.class);
    }

    @Property(tries = 50)
    @Label("parseZonedDecimal: negative scale throws IllegalArgumentException")
    void zonedParseNegativeScaleThrows(
            @ForAll @IntRange(min = -10, max = -1) int negScale) {
        byte[] buf = "00{".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(
                () -> Decimals.parseZonedDecimal(buf, 0, 3, negScale))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property(tries = 50)
    @Label("encodeZonedDecimal: negative scale throws IllegalArgumentException")
    void zonedEncodeNegativeScaleThrows(
            @ForAll @IntRange(min = -10, max = -1) int negScale) {
        assertThatThrownBy(
                () -> Decimals.encodeZonedDecimal(BigDecimal.ONE, 3, negScale))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =====================================================================
    // Phase 10 - Cross-method algebraic properties
    // =====================================================================

    @Property(tries = 500)
    @Label("Distributivity (relaxed): |((a+b)*c) - (a*c + b*c)| <= 0.01 at scale 2")
    void multiplyDistributesOverAdditionApproximately(
            @ForAll("interestRate") BigDecimal a,
            @ForAll("interestRate") BigDecimal b,
            @ForAll("interestRate") BigDecimal c) {
        BigDecimal lhs = Decimals.multiply(
                Decimals.add(a, b, 4, RoundingMode.DOWN), c, 2, RoundingMode.DOWN);
        BigDecimal rhs = Decimals.add(
                Decimals.multiply(a, c, 4, RoundingMode.DOWN),
                Decimals.multiply(b, c, 4, RoundingMode.DOWN),
                2, RoundingMode.DOWN);
        BigDecimal diff = lhs.subtract(rhs).abs();
        // ±0.01 tolerance accommodates a single truncation step at scale 2.
        assertThat(diff).isLessThanOrEqualTo(new BigDecimal("0.01"));
    }

    @Property(tries = 1000)
    @Label("Inverse: subtract(add(a, b, 2, DOWN), b, 2, DOWN) == a (scale 2)")
    void addThenSubtractIsInverse(
            @ForAll("transactionMonetary") BigDecimal a,
            @ForAll("transactionMonetary") BigDecimal b) {
        BigDecimal sum = Decimals.add(a, b, 2, RoundingMode.DOWN);
        BigDecimal restored = Decimals.subtract(sum, b, 2, RoundingMode.DOWN);
        assertThat(restored).isEqualByComparingTo(a);
    }

    @Property(tries = 500)
    @Label("Inverse (relaxed): divide(multiply(a, b, 4), b, 2) ~= a (within ±0.01)")
    void multiplyThenDivideIsApproximateInverse(
            @ForAll("interestRate") BigDecimal a,
            @ForAll("nonZeroTransactionMonetary") BigDecimal b) {
        BigDecimal product = Decimals.multiply(a, b, 4, RoundingMode.DOWN);
        BigDecimal restored = Decimals.divide(product, b, 2, RoundingMode.DOWN);
        BigDecimal diff = restored.subtract(a.setScale(2, RoundingMode.DOWN)).abs();
        assertThat(diff).isLessThanOrEqualTo(new BigDecimal("0.01"));
    }

    // =====================================================================
    // Phase 11 - Utility class pattern verification
    // =====================================================================

    /**
     * Verifies the utility-class enforcement pattern on {@link Decimals}
     * without invoking the private constructor.
     *
     * <p><strong>No-reflection-invocation policy</strong>: per AAP
     * &sect;0.7.4 the production codebase forbids reflection
     * ({@code setAccessible} / {@code newInstance}) in new code unless
     * faithfully translating an existing COBOL construct. Although this
     * test is in test scope, the rule still applies; therefore the
     * verification performs reflective <em>inspection</em> only
     * ({@link Class#getDeclaredConstructor(Class...)},
     * {@link Constructor#getModifiers()},
     * {@link Class#getModifiers()},
     * {@link Class#getDeclaredConstructors()}) and never calls
     * {@code setAccessible(true)} nor {@code newInstance()}. The
     * Java reflective inspection of modifier flags is not access-
     * controlled and does NOT require {@code setAccessible}.</p>
     *
     * <p><strong>JaCoCo coverage of the private constructor</strong>:
     * the private no-arg constructor of a {@code final} utility class
     * with an empty body is automatically filtered out of JaCoCo's
     * coverage counters by JaCoCo 0.8.5+'s built-in "Private empty
     * no-arg constructor" filter (see {@code jacoco-maven-plugin
     * 0.8.14} in {@code java/pom.xml}). The {@link Decimals}
     * constructor body is intentionally empty for this reason &mdash;
     * defensive throwing was removed because it broke filter matching
     * without providing any added safety beyond the {@code private}
     * access modifier; see the Javadoc on
     * {@link Decimals#Decimals()} for details. The 100% line-coverage
     * gate on {@code Decimals} configured in
     * {@code carddemo-tests/pom.xml} therefore remains satisfiable
     * without invoking the constructor.</p>
     *
     * <p>Assertions verified:</p>
     * <ol>
     *   <li>{@link Decimals} is {@code final} (cannot be subclassed).</li>
     *   <li>{@link Decimals} declares exactly one constructor (no
     *       overloads).</li>
     *   <li>That constructor is {@code private} (cannot be invoked from
     *       outside the package).</li>
     * </ol>
     */
    @Example
    @Label("Decimals utility class: final, single private no-arg constructor (modifier inspection only)")
    void decimalsIsUtilityClass() throws NoSuchMethodException {
        // (1) Class is final.
        assertThat(Modifier.isFinal(Decimals.class.getModifiers()))
                .as("Decimals must be final to prevent subclassing")
                .isTrue();
        // (2) Exactly one declared constructor.
        Constructor<?>[] ctors = Decimals.class.getDeclaredConstructors();
        assertThat(ctors)
                .as("Decimals must declare exactly one constructor")
                .hasSize(1);
        // (3) That constructor is private.
        Constructor<Decimals> ctor = Decimals.class.getDeclaredConstructor();
        assertThat(Modifier.isPrivate(ctor.getModifiers()))
                .as("Decimals constructor must be private to prevent instantiation")
                .isTrue();
    }

    // =====================================================================
    // Phase 12 - COBOL arithmetic faithfulness (end-to-end pipeline parity)
    // =====================================================================

    @Example
    @Label("CBACT04C: TRAN-CAT-BAL=1000.00, DIS-INT-RATE=18.00 -> WS-MONTHLY-INT=15.00")
    void cbact04cInterestPipeline() {
        // COBOL (CBACT04C L462-L468):
        //   COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
        //   (no ROUNDED -> truncation = RoundingMode.DOWN)
        BigDecimal balance = new BigDecimal("1000.00");
        BigDecimal rate = new BigDecimal("18.00");
        BigDecimal product = Decimals.multiply(balance, rate, 4, RoundingMode.DOWN);
        BigDecimal monthlyInterest = Decimals.divide(
                product, new BigDecimal("1200"), 2, RoundingMode.DOWN);
        assertThat(monthlyInterest.toPlainString()).isEqualTo("15.00");

        // COBOL: ADD WS-MONTHLY-INT TO WS-TOTAL-INT
        BigDecimal totalInterest = Decimals.add(
                BigDecimal.ZERO.setScale(2), monthlyInterest, 2, RoundingMode.DOWN);
        assertThat(totalInterest.toPlainString()).isEqualTo("15.00");

        // COBOL: ADD WS-TOTAL-INT TO ACCT-CURR-BAL
        BigDecimal newBalance = Decimals.add(
                balance, totalInterest, 2, RoundingMode.DOWN);
        assertThat(newBalance.toPlainString()).isEqualTo("1015.00");
    }

    @Example
    @Label("CBTRN02C: posting pipeline - credit then debit on running balance")
    void cbtrn02cPostingPipeline() {
        BigDecimal accountBalance = new BigDecimal("500.00");

        // COBOL (CBTRN02C L547): ADD DALYTRAN-AMT TO ACCT-CURR-BAL (credit).
        BigDecimal credit = new BigDecimal("250.75");
        accountBalance = Decimals.add(accountBalance, credit, 2, RoundingMode.DOWN);
        assertThat(accountBalance.toPlainString()).isEqualTo("750.75");

        // COBOL: ADD DALYTRAN-AMT TO ACCT-CURR-BAL (debit; negative amount).
        BigDecimal debit = new BigDecimal("-125.30");
        accountBalance = Decimals.add(accountBalance, debit, 2, RoundingMode.DOWN);
        assertThat(accountBalance.toPlainString()).isEqualTo("625.45");

        // Scale never normalizes away from 2.
        assertThat(accountBalance.scale()).isEqualTo(2);
    }

    @Property(tries = 500)
    @Label("Sequential posting: 10 transactions never normalize scale away from 2")
    void sequentialPostingPreservesScale(
            @ForAll("transactionMonetary") BigDecimal startBalance) {
        BigDecimal[] amounts = {
                new BigDecimal("10.00"), new BigDecimal("-5.50"),
                new BigDecimal("0.01"), new BigDecimal("-0.01"),
                new BigDecimal("100.00"), new BigDecimal("-50.25"),
                new BigDecimal("0.99"), new BigDecimal("-100.00"),
                new BigDecimal("1.20"), new BigDecimal("-1.20")
        };
        BigDecimal balance = startBalance;
        for (BigDecimal amt : amounts) {
            balance = Decimals.add(balance, amt, 2, RoundingMode.DOWN);
            assertThat(balance.scale()).isEqualTo(2);
        }
    }

    // =====================================================================
    // Phase 13 - formatEditMask coverage tests (Decimals.java L795-L832)
    //
    // formatEditMask is not in the schema's members_exposed list, but the
    // AAP §0.6.1 mandate of 100% line coverage on monetary code requires
    // its branches to be exercised. The schema is a minimum, not a maximum
    // (additional helper tests are permitted).
    // =====================================================================

    @Example
    @Label("formatEditMask: 194.00 with (10,2) -> ' 0000000194.00' (14 chars, positive)")
    void formatEditMaskPositiveValue() {
        String formatted = Decimals.formatEditMask(new BigDecimal("194.00"), 10, 2);
        assertThat(formatted).isEqualTo(" 0000000194.00");
        assertThat(formatted).hasSize(14);
    }

    @Example
    @Label("formatEditMask: -91.90 with (10,2) -> '-0000000091.90' (14 chars, negative)")
    void formatEditMaskNegativeValue() {
        String formatted = Decimals.formatEditMask(new BigDecimal("-91.90"), 10, 2);
        assertThat(formatted).isEqualTo("-0000000091.90");
        assertThat(formatted).hasSize(14);
    }

    @Example
    @Label("formatEditMask: ZERO with (5,0) -> ' 00000' (6 chars, no decimal point)")
    void formatEditMaskZeroNoDecimal() {
        String formatted = Decimals.formatEditMask(BigDecimal.ZERO, 5, 0);
        assertThat(formatted).isEqualTo(" 00000");
        assertThat(formatted).hasSize(6);
    }

    @Example
    @Label("formatEditMask: 12345.6789 with (5,2) -> ' 12345.68' (HALF_EVEN rounding)")
    void formatEditMaskRounding() {
        // 12345.6789 rounded to 2 decimals HALF_EVEN -> 12345.68.
        String formatted = Decimals.formatEditMask(
                new BigDecimal("12345.6789"), 5, 2);
        assertThat(formatted).isEqualTo(" 12345.68");
    }

    @Example
    @Label("formatEditMask: null value throws NullPointerException")
    void formatEditMaskNullValueThrows() {
        assertThatThrownBy(() -> Decimals.formatEditMask(null, 10, 2))
                .isInstanceOf(NullPointerException.class);
    }

    @Property(tries = 50)
    @Label("formatEditMask: integerDigits < 1 throws IllegalArgumentException")
    void formatEditMaskNonPositiveIntegerDigitsThrows(
            @ForAll @IntRange(min = -10, max = 0) int integerDigits) {
        assertThatThrownBy(
                () -> Decimals.formatEditMask(BigDecimal.ZERO, integerDigits, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property(tries = 50)
    @Label("formatEditMask: decimalDigits < 0 throws IllegalArgumentException")
    void formatEditMaskNegativeDecimalDigitsThrows(
            @ForAll @IntRange(min = -10, max = -1) int decimalDigits) {
        assertThatThrownBy(
                () -> Decimals.formatEditMask(BigDecimal.ZERO, 5, decimalDigits))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("formatEditMask: integer part exceeding integerDigits throws IllegalArgumentException")
    void formatEditMaskIntegerOverflowThrows() {
        // 99999.99 has 5 integer digits; integerDigits=3 -> overflow.
        assertThatThrownBy(
                () -> Decimals.formatEditMask(new BigDecimal("99999.99"), 3, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =====================================================================
    // Phase 14 - Remaining coverage gates (length < 1 branches, UNNECESSARY
    // rescale-failure branches, last-byte high-nibble > 9, and negative
    // trailing-zero overpunch). Each test targets a specific branch of
    // Decimals that the schema's exports list does not name but which the
    // AAP §0.6.1 100%-line-coverage mandate still requires.
    // =====================================================================

    @Example
    @Label("parseSignedPacked: length < 1 throws IllegalArgumentException")
    void comp3ParseLengthZeroThrows() {
        byte[] buf = new byte[] { (byte) 0x12, (byte) 0x3C };
        assertThatThrownBy(() -> Decimals.parseSignedPacked(buf, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("encodeSignedPacked: length < 1 throws IllegalArgumentException")
    void comp3EncodeLengthZeroThrows() {
        assertThatThrownBy(
                () -> Decimals.encodeSignedPacked(BigDecimal.ONE, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("parseZonedDecimal: length < 1 throws IllegalArgumentException")
    void zonedParseLengthZeroThrows() {
        byte[] buf = "00{".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> Decimals.parseZonedDecimal(buf, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("encodeZonedDecimal: length < 1 throws IllegalArgumentException")
    void zonedEncodeLengthZeroThrows() {
        assertThatThrownBy(
                () -> Decimals.encodeZonedDecimal(BigDecimal.ONE, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("parseSignedPacked: high nibble of last byte > 9 throws IllegalArgumentException")
    void comp3LastByteHighNibbleInvalidThrows() {
        // Byte 0xAC: high nibble 0xA (invalid digit, > 9); low nibble 0xC (valid sign).
        byte[] buf = new byte[] { (byte) 0x12, (byte) 0xAC };
        assertThatThrownBy(() -> Decimals.parseSignedPacked(buf, 0, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("encodeSignedPacked: scale requiring rounding throws IllegalArgumentException (UNNECESSARY)")
    void comp3EncodeScaleRequiringRoundingThrows() {
        // value 1.25 has scale 2; passing scale=0 (where 0.25 is non-zero)
        // triggers setScale(0, UNNECESSARY) -> ArithmeticException -> wrapped
        // as IllegalArgumentException by encodeSignedPacked.
        assertThatThrownBy(
                () -> Decimals.encodeSignedPacked(new BigDecimal("1.25"), 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("encodeZonedDecimal: scale requiring rounding throws IllegalArgumentException (UNNECESSARY)")
    void zonedEncodeScaleRequiringRoundingThrows() {
        // value 1.25 has scale 2; passing scale=0 triggers the wrapped IAE.
        assertThatThrownBy(
                () -> Decimals.encodeZonedDecimal(new BigDecimal("1.25"), 3, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("encodeZonedDecimal: negative value with trailing digit 0 emits '}' overpunch")
    void zonedEncodeNegativeTrailingZeroEmitsCurlyClose() {
        // -10 with length=3, scale=0 -> digits="010", lastDigit=0, negative=true -> '}'.
        byte[] buf = Decimals.encodeZonedDecimal(new BigDecimal("-10"), 3, 0);
        assertThat((char) buf[2]).isEqualTo('}');
    }

    @Example
    @Label("encodeSignedPacked: re-encoded with scale 2 matches expected canonical bytes")
    void comp3EncodeCanonicalBytes() {
        // Value 123.45 with PIC S9(03)V99 -> length=3 bytes, capacity 5 digits,
        // digits="12345", canonical encoded bytes: 0x12, 0x34, 0x5C (positive).
        byte[] buf = Decimals.encodeSignedPacked(new BigDecimal("123.45"), 3, 2);
        assertThat(buf).hasSize(3);
        assertThat(buf[0]).isEqualTo((byte) 0x12);
        assertThat(buf[1]).isEqualTo((byte) 0x34);
        assertThat(buf[2]).isEqualTo((byte) 0x5C);
    }

    @Example
    @Label("encodeZonedDecimal: value with same digit count as length emits no leading zeros")
    void zonedEncodeNoLeadingPadding() {
        // 123 with length=3, scale=0 -> digits="123", lastDigit=3, positive -> 'C'.
        byte[] buf = Decimals.encodeZonedDecimal(new BigDecimal("123"), 3, 0);
        assertThat((char) buf[0]).isEqualTo('1');
        assertThat((char) buf[1]).isEqualTo('2');
        assertThat((char) buf[2]).isEqualTo('C');
    }

    // =====================================================================
    // Phase 15 - formatEditMaskUnsigned coverage tests
    // (Decimals.java L903-L943; DFSORT EDIT=(T...T.T...T) translation)
    //
    // formatEditMaskUnsigned is not in the schema's members_exposed list, but
    // the AAP §0.6.1 mandate of 100% line coverage on monetary code requires
    // every branch of this monetary helper to be exercised. The schema is a
    // minimum, not a maximum (additional helper tests are permitted).
    //
    // These tests mirror the canonical examples documented in the
    // formatEditMaskUnsigned Javadoc (Decimals.java L867-L879) which itself
    // matches the JCL specification for
    // EDIT=(TTTTTTTTT.TT) in app/jcl/PRTCATBL.jcl L53-L56.
    // =====================================================================

    @Example
    @Label("formatEditMaskUnsigned: 194.00 with (9,2) -> '000000194.00' (12 chars, positive)")
    void formatEditMaskUnsignedPositiveValue() {
        String formatted = Decimals.formatEditMaskUnsigned(new BigDecimal("194.00"), 9, 2);
        assertThat(formatted).isEqualTo("000000194.00");
        assertThat(formatted).hasSize(12);
    }

    @Example
    @Label("formatEditMaskUnsigned: ZERO with (9,2) -> '000000000.00' (12 chars)")
    void formatEditMaskUnsignedZeroWithDecimal() {
        String formatted = Decimals.formatEditMaskUnsigned(BigDecimal.ZERO, 9, 2);
        assertThat(formatted).isEqualTo("000000000.00");
        assertThat(formatted).hasSize(12);
    }

    @Example
    @Label("formatEditMaskUnsigned: -91.90 with (9,2) -> '000000091.90' (absolute value, DFSORT default)")
    void formatEditMaskUnsignedNegativeValueDropsSign() {
        // DFSORT unsigned-EDIT default: negative values rendered as their
        // absolute value (the sign is dropped). This is the basis for the
        // AAP key insight ("Negative balances are NOT expected for TCATBAL;
        // if encountered, the EDIT mask would emit them as positive").
        String formatted = Decimals.formatEditMaskUnsigned(new BigDecimal("-91.90"), 9, 2);
        assertThat(formatted).isEqualTo("000000091.90");
        assertThat(formatted).hasSize(12);
    }

    @Example
    @Label("formatEditMaskUnsigned: ZERO with (5,0) -> '00000' (5 chars, no decimal point)")
    void formatEditMaskUnsignedZeroNoDecimal() {
        String formatted = Decimals.formatEditMaskUnsigned(BigDecimal.ZERO, 5, 0);
        assertThat(formatted).isEqualTo("00000");
        assertThat(formatted).hasSize(5);
    }

    @Example
    @Label("formatEditMaskUnsigned: 12345.6789 with (5,2) -> '12345.68' (HALF_EVEN rounding)")
    void formatEditMaskUnsignedRounding() {
        // 12345.6789 rounded to 2 decimals HALF_EVEN -> 12345.68; integer
        // part has 5 digits which exactly fills integerDigits=5.
        String formatted = Decimals.formatEditMaskUnsigned(
                new BigDecimal("12345.6789"), 5, 2);
        assertThat(formatted).isEqualTo("12345.68");
    }

    @Example
    @Label("formatEditMaskUnsigned: HALF_EVEN rounding pulls 0.005 down to 0.00")
    void formatEditMaskUnsignedBankersRoundingDown() {
        // Banker's rounding (HALF_EVEN): 0.005 -> 0.00 (rounds to even).
        String formatted = Decimals.formatEditMaskUnsigned(
                new BigDecimal("0.005"), 1, 2);
        assertThat(formatted).isEqualTo("0.00");
    }

    @Example
    @Label("formatEditMaskUnsigned: HALF_EVEN rounding pulls 0.015 up to 0.02")
    void formatEditMaskUnsignedBankersRoundingUp() {
        // Banker's rounding (HALF_EVEN): 0.015 -> 0.02 (rounds to even).
        String formatted = Decimals.formatEditMaskUnsigned(
                new BigDecimal("0.015"), 1, 2);
        assertThat(formatted).isEqualTo("0.02");
    }

    @Example
    @Label("formatEditMaskUnsigned: integer-only value at exact width emits no padding")
    void formatEditMaskUnsignedExactWidthNoPadding() {
        // 99999 with (5,0) -> exactly fills, no leading zero padding required.
        String formatted = Decimals.formatEditMaskUnsigned(
                new BigDecimal("99999"), 5, 0);
        assertThat(formatted).isEqualTo("99999");
    }

    @Example
    @Label("formatEditMaskUnsigned: null value throws NullPointerException")
    void formatEditMaskUnsignedNullValueThrows() {
        assertThatThrownBy(() -> Decimals.formatEditMaskUnsigned(null, 9, 2))
                .isInstanceOf(NullPointerException.class);
    }

    @Property(tries = 50)
    @Label("formatEditMaskUnsigned: integerDigits < 1 throws IllegalArgumentException")
    void formatEditMaskUnsignedNonPositiveIntegerDigitsThrows(
            @ForAll @IntRange(min = -10, max = 0) int integerDigits) {
        assertThatThrownBy(
                () -> Decimals.formatEditMaskUnsigned(BigDecimal.ZERO, integerDigits, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property(tries = 50)
    @Label("formatEditMaskUnsigned: decimalDigits < 0 throws IllegalArgumentException")
    void formatEditMaskUnsignedNegativeDecimalDigitsThrows(
            @ForAll @IntRange(min = -10, max = -1) int decimalDigits) {
        assertThatThrownBy(
                () -> Decimals.formatEditMaskUnsigned(BigDecimal.ZERO, 5, decimalDigits))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("formatEditMaskUnsigned: integer part exceeding integerDigits throws IllegalArgumentException")
    void formatEditMaskUnsignedIntegerOverflowThrows() {
        // 99999.99 has 5 integer digits; integerDigits=3 -> overflow.
        assertThatThrownBy(
                () -> Decimals.formatEditMaskUnsigned(new BigDecimal("99999.99"), 3, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("formatEditMaskUnsigned: negative value overflow uses absolute value digit count")
    void formatEditMaskUnsignedNegativeOverflowThrows() {
        // -99999.99 has 5 integer digits in absolute value; integerDigits=3 -> overflow
        // (the absolute value's digit count is what matters, not the negative).
        assertThatThrownBy(
                () -> Decimals.formatEditMaskUnsigned(new BigDecimal("-99999.99"), 3, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    @Label("formatEditMaskUnsigned: single integer digit, no decimals -> single digit")
    void formatEditMaskUnsignedSingleDigit() {
        String formatted = Decimals.formatEditMaskUnsigned(new BigDecimal("7"), 1, 0);
        assertThat(formatted).isEqualTo("7");
        assertThat(formatted).hasSize(1);
    }

    @Example
    @Label("formatEditMaskUnsigned: PRTCATBL JCL canonical TCATBAL formatting (9 int, 2 dec)")
    void formatEditMaskUnsignedPrtCatBlCanonical() {
        // Reproduce the documented PRTCATBL.jcl L53-L56 OUTREC FIELDS=(
        // ...TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),...) specification with the
        // canonical TCATBAL examples from the AAP fixtures.
        assertThat(Decimals.formatEditMaskUnsigned(new BigDecimal("194.00"), 9, 2))
                .isEqualTo("000000194.00");
        assertThat(Decimals.formatEditMaskUnsigned(new BigDecimal("1.23"), 9, 2))
                .isEqualTo("000000001.23");
        assertThat(Decimals.formatEditMaskUnsigned(new BigDecimal("999999999.99"), 9, 2))
                .isEqualTo("999999999.99");
    }

    @Property(tries = 200)
    @Label("formatEditMaskUnsigned: output width is always integerDigits + (decimalDigits > 0 ? 1 + decimalDigits : 0)")
    void formatEditMaskUnsignedWidthInvariant(
            @ForAll("transactionMonetary") BigDecimal value) {
        // The Javadoc contract: output width is exactly
        // integerDigits + (decimalDigits > 0 ? 1 + decimalDigits : 0).
        // Use integerDigits=9, decimalDigits=2 (PIC S9(09)V99 -> 9 + 1 + 2 = 12 chars).
        String formatted = Decimals.formatEditMaskUnsigned(value, 9, 2);
        assertThat(formatted).hasSize(12);
        // Verify embedded decimal point at position 9 (0-indexed).
        assertThat(formatted.charAt(9)).isEqualTo('.');
        // Verify all other characters are digits (no sign character).
        for (int i = 0; i < formatted.length(); i++) {
            if (i != 9) {
                assertThat(Character.isDigit(formatted.charAt(i)))
                        .as("character at position %d must be a digit (got '%c')", i, formatted.charAt(i))
                        .isTrue();
            }
        }
    }

    @Property(tries = 200)
    @Label("formatEditMaskUnsigned: output equals absolute value of corresponding formatEditMask digits")
    void formatEditMaskUnsignedEqualsAbsOfSignedFormatDigits(
            @ForAll("transactionMonetary") BigDecimal value) {
        // The documented relationship: formatEditMaskUnsigned == formatEditMask
        // with the leading sign character dropped (DFSORT unsigned-EDIT default).
        // formatEditMask emits 12 + 1 (sign) = 13 chars for (9, 2); the substring
        // at offsets [1, end) is the unsigned representation of the absolute value.
        String signed = Decimals.formatEditMask(value, 9, 2);
        String unsignedFromValue = Decimals.formatEditMaskUnsigned(value, 9, 2);
        // formatEditMask emits a leading ' ' for non-negative and '-' for negative;
        // formatEditMaskUnsigned drops it entirely.
        assertThat(unsignedFromValue).isEqualTo(signed.substring(1));
    }

    @Example
    @Label("formatEditMaskUnsigned: zero-padding triggered when value has fewer integer digits than integerDigits")
    void formatEditMaskUnsignedZeroPadding() {
        // value=1.00 has 1 integer digit; integerDigits=9 -> 8 leading zeros.
        String formatted = Decimals.formatEditMaskUnsigned(new BigDecimal("1.00"), 9, 2);
        assertThat(formatted).isEqualTo("000000001.00");
    }

    @Example
    @Label("formatEditMaskUnsigned: no zero-padding when value's digit count equals total width")
    void formatEditMaskUnsignedNoZeroPaddingAtMaxWidth() {
        // 999999999.99 has 11 unscaled digits exactly equal to totalDigits=11.
        String formatted = Decimals.formatEditMaskUnsigned(new BigDecimal("999999999.99"), 9, 2);
        assertThat(formatted).isEqualTo("999999999.99");
    }
}
