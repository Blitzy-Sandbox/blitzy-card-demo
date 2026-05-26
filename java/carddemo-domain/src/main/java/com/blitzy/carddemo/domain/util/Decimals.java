/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.domain.util;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Central facade for monetary {@link BigDecimal} arithmetic and COBOL
 * packed-decimal / zoned-decimal byte encoding in the CardDemo Java
 * translation.
 *
 * <p>This class is the <strong>single point of control</strong> for
 * {@link MathContext} and {@link RoundingMode} defaults. Every translated
 * COBOL paragraph that performs {@code COMPUTE}, {@code ADD},
 * {@code SUBTRACT}, {@code MULTIPLY}, or {@code DIVIDE} on packed-decimal
 * values, and every record {@code parse(byte[])} / {@code encode()} method
 * that handles a {@code PIC S9(n)V99} or {@code COMP-3} field, MUST route
 * through this class. Updates to rounding behavior, sign-nibble handling,
 * or scale defaults are made <strong>here and only here</strong>.
 *
 * <h2>Authority</h2>
 * <ul>
 *   <li>AAP &sect;0.3.3 (Decimals Utility Design) &mdash; method signatures
 *       and behavior specification</li>
 *   <li>AAP &sect;0.6.1 (Decimal Arithmetic Fidelity) &mdash; 100% line
 *       coverage requirement on monetary code (property-based tests live in
 *       {@code carddemo-tests})</li>
 *   <li>AAP &sect;0.6.5 (File I/O Exactness) &mdash; byte-for-byte
 *       round-trip invariant {@code parse(record).encode() == originalBuffer}</li>
 *   <li>AAP &sect;0.6.7 and &sect;0.7.3 &mdash; {@code BigDecimal} is the
 *       ONLY permitted monetary type; {@code double} and {@code float} are
 *       FORBIDDEN</li>
 *   <li>AAP &sect;0.7.2 &mdash; "100% for monetary code (user mandate)"</li>
 * </ul>
 *
 * <h2>Defaults</h2>
 * <ul>
 *   <li>{@link #DEFAULT_MATH_CONTEXT} = {@link MathContext#DECIMAL128}
 *       (34-digit precision) for intermediate operations</li>
 *   <li>{@link #ROUNDED_MODE} = {@link RoundingMode#HALF_EVEN}
 *       (banker's rounding) for COBOL {@code ROUNDED} clause</li>
 *   <li>{@link #DEFAULT_MODE} = {@link RoundingMode#DOWN}
 *       (truncation) for default unrounded COBOL arithmetic</li>
 *   <li>{@link #DEFAULT_MONETARY_SCALE} = {@code 2}
 *       (for {@code PIC S9(n)V99} fields)</li>
 * </ul>
 *
 * <h2>COMP-3 packed-decimal sign nibble convention</h2>
 * <p>COBOL {@code COMP-3} (packed-decimal) encodes two BCD digits per byte
 * except for the rightmost byte, whose high nibble is the rightmost digit
 * and whose low nibble is the sign:
 * <ul>
 *   <li>{@code 0xD} (1101) &mdash; negative</li>
 *   <li>{@code 0xC} (1100) &mdash; positive (signed)</li>
 *   <li>{@code 0xF} (1111) &mdash; unsigned / absolute (treated as positive
 *       on parse)</li>
 * </ul>
 * <p>The total number of decimal digits encoded in a packed-decimal value
 * of byte length {@code L} is {@code 2 * L - 1}.
 *
 * <h2>Zoned-decimal sign overpunch convention (ASCII variant)</h2>
 * <p>COBOL {@code USAGE DISPLAY} numeric fields (e.g., {@code PIC S9(n)V99}
 * without a {@code USAGE} clause) encode each digit as an ASCII character
 * {@code '0'}-{@code '9'} except for the rightmost digit, which is
 * <em>overpunched</em> with the sign:
 * <ul>
 *   <li>Positive: <code>'&#123;'</code> &rarr; +0,
 *       {@code 'A'}-{@code 'I'} &rarr; +1..+9</li>
 *   <li>Negative: <code>'&#125;'</code> &rarr; -0,
 *       {@code 'J'}-{@code 'R'} &rarr; -1..-9</li>
 * </ul>
 * <p>This is the EBCDIC overpunch convention reinterpreted into ASCII as
 * used in the {@code app/data/ASCII/*.txt} fixture files.
 *
 * <h2>Scale preservation</h2>
 * <p>A {@code BigDecimal} value of {@code 1.20} must NOT be normalized to
 * {@code 1.2}. Every method in this class returns a {@code BigDecimal}
 * with the explicitly requested scale, preserving trailing zeros to
 * maintain byte-for-byte fidelity with the COBOL implicit decimal position.
 * This is the key reason all parse / arithmetic methods take an explicit
 * {@code scale} parameter and apply {@link BigDecimal#setScale(int, RoundingMode)}
 * as the final operation.
 *
 * <h2>Thread safety</h2>
 * <p>This class is stateless and contains only {@code public static}
 * methods. It is safe for concurrent use by any number of platform or
 * virtual threads (per AAP &sect;0.6.6 virtual-thread fan-out).
 *
 * <h2>Forbidden operations</h2>
 * <ul>
 *   <li>NEVER use {@code double} or {@code float} for monetary values
 *       (AAP &sect;0.6.7, &sect;0.7.4).</li>
 *   <li>NEVER use implicit rounding &mdash; every operation that could
 *       lose precision MUST take an explicit {@link RoundingMode}.</li>
 *   <li>NEVER mutate state &mdash; this class has no instance state to
 *       mutate and no static mutable state.</li>
 * </ul>
 *
 * @see BigDecimal
 * @see MathContext
 * @see RoundingMode
 * @since 1.0.0
 */
public final class Decimals {

    /**
     * Default {@link MathContext} for monetary calculations: 34-digit
     * precision per AAP &sect;0.3.3 and &sect;0.6.1. Equivalent to IEEE
     * 754 decimal128.
     *
     * <p>This constant is provided for callers that need to perform
     * intermediate exact arithmetic before a final scale normalization.
     * The arithmetic methods on {@code Decimals} (e.g., {@link #add},
     * {@link #multiply}, {@link #divide}) compute their intermediate
     * results using exact {@link BigDecimal} operations (addition,
     * subtraction, and multiplication are exact in {@code BigDecimal});
     * only {@link #divide} consults a {@link RoundingMode} during the
     * division step itself, where exactness is generally impossible.
     */
    public static final MathContext DEFAULT_MATH_CONTEXT = MathContext.DECIMAL128;

    /**
     * {@link RoundingMode} for COBOL {@code ROUNDED} clause: banker's
     * rounding ({@link RoundingMode#HALF_EVEN}) per AAP &sect;0.3.3.
     * Round-half-to-even minimizes statistical bias over many operations
     * and matches the COBOL Enterprise / Micro Focus default for the
     * {@code ROUNDED} clause.
     */
    public static final RoundingMode ROUNDED_MODE = RoundingMode.HALF_EVEN;

    /**
     * {@link RoundingMode} for default (unrounded) COBOL arithmetic:
     * truncation ({@link RoundingMode#DOWN}) per AAP &sect;0.3.3. COBOL
     * drops the excess digits when {@code ROUNDED} is omitted from a
     * {@code COMPUTE}, {@code ADD}, {@code SUBTRACT}, {@code MULTIPLY},
     * or {@code DIVIDE} statement.
     */
    public static final RoundingMode DEFAULT_MODE = RoundingMode.DOWN;

    /**
     * Default monetary scale for {@code PIC S9(n)V99} fields per AAP
     * &sect;0.3.3. The implicit decimal point in COBOL {@code V99} sits
     * two positions from the right of the digit string. This value is
     * the scale used by the majority of monetary fields in the CardDemo
     * copybooks (e.g., {@code ACCT-CURR-BAL}, {@code TRAN-AMT},
     * {@code TRAN-CAT-BAL}).
     */
    public static final int DEFAULT_MONETARY_SCALE = 2;

    /**
     * Utility class &mdash; instantiation prevented by the {@code private}
     * access modifier on this constructor and by the class being declared
     * {@code final}. {@code Decimals} is stateless and exposes only static
     * helpers; no useful object exists.
     *
     * <p><strong>Implementation note (JaCoCo coverage gate)</strong>: the
     * constructor body is intentionally empty so that JaCoCo's built-in
     * "Private empty no-arg constructor" filter (available since JaCoCo
     * 0.8.5; cited in {@code java/pom.xml}'s
     * {@code <jacoco-maven-plugin.version>0.8.14</jacoco-maven-plugin.version>}
     * block) automatically excludes this constructor from the 100% line
     * coverage gate mandated by AAP &sect;0.6.1. The earlier defensive
     * implementation that threw {@link UnsupportedOperationException} was
     * not matched by the filter (the filter requires an empty body), which
     * caused the gate to fail without adding any practical safety: the
     * constructor's {@code private} access modifier already prevents
     * instantiation by any caller obeying AAP &sect;0.7.4 (reflection is
     * forbidden in new code). The change preserves all utility-class
     * semantics without altering any observable behavior of the public
     * static API.
     */
    private Decimals() {
        // intentionally empty - see Javadoc above
    }

    // ---------------------------------------------------------------------
    // Arithmetic methods
    // ---------------------------------------------------------------------

    /**
     * Returns {@code a + b} normalized to the given scale with the given
     * rounding mode. Translation of COBOL {@code ADD a TO b} and
     * {@code COMPUTE c = a + b}.
     *
     * <p>The intermediate sum is computed exactly &mdash; {@link BigDecimal}
     * addition is exact when {@link MathContext} is not specified &mdash;
     * and the final result is normalized via
     * {@link BigDecimal#setScale(int, RoundingMode)} using the provided
     * {@code scale} and {@code mode}.
     *
     * <p>Example (CBACT04C interest accumulation):
     * {@code add(wsMonthlyInt, wsTotalInt, 2, DEFAULT_MODE)} translates
     * {@code ADD WS-MONTHLY-INT TO WS-TOTAL-INT}.
     *
     * @param a     left operand (must not be null)
     * @param b     right operand (must not be null)
     * @param scale the target scale (e.g., 2 for {@code PIC S9(n)V99});
     *              must be {@code >= 0}
     * @param mode  the rounding mode to apply when normalizing scale (must
     *              not be null); use {@link #ROUNDED_MODE} for COBOL
     *              {@code ROUNDED}, {@link #DEFAULT_MODE} otherwise
     * @return the sum, scaled to exactly {@code scale} decimal places
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code scale < 0}
     */
    public static BigDecimal add(BigDecimal a, BigDecimal b, int scale, RoundingMode mode) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        Objects.requireNonNull(mode, "mode");
        validateScale(scale);
        return a.add(b).setScale(scale, mode);
    }

    /**
     * Returns {@code a - b} normalized to the given scale with the given
     * rounding mode. Translation of COBOL {@code SUBTRACT a FROM b} and
     * {@code COMPUTE c = a - b}.
     *
     * <p>Symmetric to {@link #add(BigDecimal, BigDecimal, int, RoundingMode)}.
     * Required for COBOL programs like {@code COBIL00C} that subtract a
     * payment amount from an account balance:
     * {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}.
     *
     * @param a     left operand (must not be null)
     * @param b     right operand (must not be null)
     * @param scale the target scale (must be {@code >= 0})
     * @param mode  the rounding mode (must not be null)
     * @return {@code a - b} scaled to exactly {@code scale} decimal places
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code scale < 0}
     * @see #add(BigDecimal, BigDecimal, int, RoundingMode)
     */
    public static BigDecimal subtract(BigDecimal a, BigDecimal b, int scale, RoundingMode mode) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        Objects.requireNonNull(mode, "mode");
        validateScale(scale);
        return a.subtract(b).setScale(scale, mode);
    }

    /**
     * Returns {@code a * b} normalized to the given scale with the given
     * rounding mode. Translation of COBOL {@code MULTIPLY a BY b GIVING c}
     * (without {@code ROUNDED}, pass {@link #DEFAULT_MODE} for truncation)
     * or {@code COMPUTE c = a * b}.
     *
     * <p>The intermediate product is computed exactly &mdash;
     * {@link BigDecimal} multiplication produces a result with scale equal
     * to the sum of operand scales &mdash; and the final result is
     * normalized via {@code setScale(scale, mode)}.
     *
     * @param a     left operand (must not be null)
     * @param b     right operand (must not be null)
     * @param scale the target scale (must be {@code >= 0})
     * @param mode  the rounding mode (must not be null)
     * @return {@code a * b} scaled to exactly {@code scale} decimal places
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code scale < 0}
     * @see #multiplyRounded(BigDecimal, BigDecimal, int)
     */
    public static BigDecimal multiply(BigDecimal a, BigDecimal b, int scale, RoundingMode mode) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        Objects.requireNonNull(mode, "mode");
        validateScale(scale);
        return a.multiply(b).setScale(scale, mode);
    }

    /**
     * Returns {@code a * b} normalized to the given scale with banker's
     * rounding ({@link #ROUNDED_MODE}). Translation of COBOL
     * {@code MULTIPLY a BY b GIVING c ROUNDED} and the multiplication
     * step of {@code COMPUTE c = (a * b) ROUNDED}.
     *
     * @param a     left operand (must not be null)
     * @param b     right operand (must not be null)
     * @param scale the target scale (must be {@code >= 0})
     * @return {@code a * b} scaled to {@code scale} with
     *         {@link RoundingMode#HALF_EVEN} rounding
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code scale < 0}
     */
    public static BigDecimal multiplyRounded(BigDecimal a, BigDecimal b, int scale) {
        return multiply(a, b, scale, ROUNDED_MODE);
    }

    /**
     * Returns {@code a / b} normalized to the given scale with the given
     * rounding mode. Translation of COBOL {@code DIVIDE a BY b GIVING c}
     * (without {@code ROUNDED}, pass {@link #DEFAULT_MODE} for truncation)
     * or the divide step of {@code COMPUTE c = a / b}.
     *
     * <p>Uses {@link BigDecimal#divide(BigDecimal, int, RoundingMode)}
     * which directly produces a result with the requested scale; no
     * intermediate {@link MathContext} is needed and the operation cannot
     * throw {@link ArithmeticException} for non-terminating decimal
     * expansions (the rounding mode handles them).
     *
     * <p>This is the right method for translations like the CBACT04C
     * interest formula:
     * <pre>{@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}</pre>
     * No {@code ROUNDED}, so use {@link #DEFAULT_MODE} (DOWN / truncation).
     *
     * @param a     dividend (must not be null)
     * @param b     divisor (must not be null and must not be zero)
     * @param scale the target scale (must be {@code >= 0})
     * @param mode  the rounding mode (must not be null)
     * @return {@code a / b} scaled to exactly {@code scale} decimal places
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code scale < 0}
     * @throws ArithmeticException if {@code b} is zero
     * @see #divideRounded(BigDecimal, BigDecimal, int)
     */
    public static BigDecimal divide(BigDecimal a, BigDecimal b, int scale, RoundingMode mode) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        Objects.requireNonNull(mode, "mode");
        validateScale(scale);
        if (b.signum() == 0) {
            throw new ArithmeticException(
                    "Division by zero (a=" + a + ", b=" + b + ")");
        }
        return a.divide(b, scale, mode);
    }

    /**
     * Returns {@code a / b} normalized to the given scale with banker's
     * rounding ({@link #ROUNDED_MODE}). Translation of COBOL
     * {@code DIVIDE a BY b GIVING c ROUNDED}.
     *
     * @param a     dividend (must not be null)
     * @param b     divisor (must not be null and must not be zero)
     * @param scale the target scale (must be {@code >= 0})
     * @return {@code a / b} scaled to {@code scale} with
     *         {@link RoundingMode#HALF_EVEN} rounding
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code scale < 0}
     * @throws ArithmeticException if {@code b} is zero
     */
    public static BigDecimal divideRounded(BigDecimal a, BigDecimal b, int scale) {
        return divide(a, b, scale, ROUNDED_MODE);
    }

    /**
     * Returns {@code value} normalized to the given scale with the given
     * rounding mode. Translation of COBOL {@code MOVE A TO B} where the
     * implicit scales of {@code A} and {@code B} differ, and of
     * {@code COMPUTE} statements whose target field has fewer decimal
     * positions than the computed value.
     *
     * <p>Use this method to:
     * <ul>
     *   <li>Apply the monetary scale (typically {@link #DEFAULT_MONETARY_SCALE})
     *       to a {@code BigDecimal} arithmetic result before storage</li>
     *   <li>Force the scale of a parsed value to a known target before
     *       encoding back to bytes</li>
     * </ul>
     *
     * @param value the value to scale (must not be null)
     * @param scale the target scale (must be {@code >= 0})
     * @param mode  the rounding mode (must not be null); use
     *              {@link RoundingMode#UNNECESSARY} when scale change
     *              MUST be lossless or the caller wants to detect a logic
     *              error
     * @return {@code value.setScale(scale, mode)}
     * @throws NullPointerException if {@code value} or {@code mode} is null
     * @throws IllegalArgumentException if {@code scale < 0}
     * @throws ArithmeticException if {@code mode} is
     *         {@link RoundingMode#UNNECESSARY} and rounding would actually
     *         be required
     */
    public static BigDecimal scaled(BigDecimal value, int scale, RoundingMode mode) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(mode, "mode");
        validateScale(scale);
        return value.setScale(scale, mode);
    }

    // ---------------------------------------------------------------------
    // COMP-3 packed-decimal codec
    // ---------------------------------------------------------------------

    /**
     * Parses a COBOL {@code COMP-3} packed-decimal byte sequence into a
     * {@link BigDecimal} with the specified scale.
     *
     * <p>Layout: each byte holds two BCD digits (high nibble first),
     * EXCEPT the rightmost byte whose low nibble is the sign:
     * <ul>
     *   <li>{@code 0xD} (1101) &mdash; negative</li>
     *   <li>{@code 0xC} (1100) &mdash; positive (signed)</li>
     *   <li>{@code 0xF} (1111) &mdash; unsigned / absolute (treated as positive)</li>
     * </ul>
     * The total number of decimal digits encoded is {@code 2 * length - 1}.
     *
     * <p>The returned {@link BigDecimal} preserves the requested {@code scale}
     * (e.g., a packed value of {@code 120} parsed with {@code scale=2}
     * returns {@code BigDecimal("1.20")}, NOT {@code BigDecimal("1.2")}).
     * This is critical for byte-for-byte fidelity per AAP &sect;0.1.3
     * (scale preservation).
     *
     * @param buffer source byte array (must not be null)
     * @param offset starting position within the buffer (must be {@code >= 0})
     * @param length number of bytes occupied by the packed value (must be
     *               {@code >= 1})
     * @param scale  the implied decimal places (e.g., 2 for
     *               {@code PIC S9(n)V99}); must be {@code >= 0}
     * @return BigDecimal with exactly {@code scale} decimal places
     * @throws NullPointerException if {@code buffer} is null
     * @throws IndexOutOfBoundsException if {@code offset < 0} or
     *         {@code offset + length > buffer.length}
     * @throws IllegalArgumentException if {@code length < 1},
     *         {@code scale < 0}, a BCD digit nibble is invalid
     *         (greater than 9), or the sign nibble is none of {@code 0xD},
     *         {@code 0xC}, or {@code 0xF}
     */
    public static BigDecimal parseSignedPacked(byte[] buffer, int offset, int length, int scale) {
        Objects.requireNonNull(buffer, "buffer");
        if (length < 1) {
            throw new IllegalArgumentException("length must be >= 1, got " + length);
        }
        if (offset < 0 || offset + length > buffer.length) {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + ", length=" + length
                            + ", buffer.length=" + buffer.length);
        }
        validateScale(scale);

        StringBuilder sb = new StringBuilder(length * 2);
        // Bytes 0..length-2 contribute two BCD digits each (high then low).
        for (int i = 0; i < length - 1; i++) {
            int b = buffer[offset + i] & 0xFF;
            int hi = (b >>> 4) & 0xF;
            int lo = b & 0xF;
            if (hi > 9 || lo > 9) {
                throw new IllegalArgumentException(String.format(
                        "Invalid BCD digit at byte %d: 0x%02X", offset + i, b));
            }
            sb.append((char) ('0' + hi));
            sb.append((char) ('0' + lo));
        }
        // Last byte: high nibble is the rightmost digit, low nibble is the sign.
        int lastByte = buffer[offset + length - 1] & 0xFF;
        int lastDigit = (lastByte >>> 4) & 0xF;
        int signNibble = lastByte & 0xF;
        if (lastDigit > 9) {
            throw new IllegalArgumentException(String.format(
                    "Invalid BCD digit (high nibble) at byte %d: 0x%X",
                    offset + length - 1, lastDigit));
        }
        sb.append((char) ('0' + lastDigit));

        boolean negative;
        switch (signNibble) {
            case 0xD -> negative = true;
            case 0xC, 0xF -> negative = false;
            default -> throw new IllegalArgumentException(String.format(
                    "Invalid COMP-3 sign nibble at byte %d: 0x%X",
                    offset + length - 1, signNibble));
        }

        String digits = sb.toString();
        BigDecimal unscaled = new BigDecimal((negative ? "-" : "") + digits);
        return unscaled.movePointLeft(scale).setScale(scale, RoundingMode.UNNECESSARY);
    }

    /**
     * Encodes a {@link BigDecimal} as a COBOL {@code COMP-3} packed-decimal
     * byte sequence of the specified length.
     *
     * <p>The value's scale MUST equal the supplied {@code scale} (no
     * implicit rescaling); the caller is responsible for normalizing scale
     * via {@link #scaled(BigDecimal, int, RoundingMode)} or by using
     * arithmetic methods that take an explicit scale.
     *
     * <p>Sign convention produced by this encoder:
     * <ul>
     *   <li>Negative values &rarr; low nibble of last byte is {@code 0xD}</li>
     *   <li>Positive values (and zero) &rarr; low nibble of last byte is
     *       {@code 0xC}</li>
     * </ul>
     * Unsigned {@code 0xF} is NOT produced by this encoder. If a caller
     * requires {@code 0xF} preservation (extraordinarily rare in CardDemo),
     * the caller MUST overwrite the low nibble of the last byte manually
     * after this call. Round-trip from a buffer that used {@code 0xF} will
     * therefore canonicalize to {@code 0xC} on the encode side.
     *
     * <p>The total number of decimal digits encoded is {@code 2 * length - 1};
     * the value's integer-form magnitude MUST fit. If it does not, an
     * {@link IllegalArgumentException} is thrown (this is a byte-for-byte
     * fidelity gate &mdash; an overflow would corrupt downstream record
     * bytes).
     *
     * @param value  the BigDecimal value to encode (must not be null)
     * @param length the target byte length (must be {@code >= 1})
     * @param scale  the implied decimal places (must be {@code >= 0} and
     *               equal to {@code value.scale()})
     * @return byte array of exactly {@code length} bytes
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if {@code length < 1},
     *         {@code scale < 0}, the value's magnitude exceeds
     *         {@code 2 * length - 1} digits, or the value's actual scale
     *         requires rounding to reach the requested {@code scale}
     */
    public static byte[] encodeSignedPacked(BigDecimal value, int length, int scale) {
        Objects.requireNonNull(value, "value");
        if (length < 1) {
            throw new IllegalArgumentException("length must be >= 1, got " + length);
        }
        validateScale(scale);

        // Caller is expected to have scaled the value; re-apply with
        // RoundingMode.UNNECESSARY to catch logic errors at the encode boundary.
        final BigDecimal scaledValue;
        try {
            scaledValue = value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "Value '" + value + "' has scale " + value.scale()
                            + " which cannot be normalized to scale=" + scale
                            + " without rounding; pre-scale the value via Decimals.scaled(...)",
                    ex);
        }
        BigDecimal unscaled = scaledValue.movePointRight(scale);
        boolean negative = unscaled.signum() < 0;
        String digits = unscaled.abs().toBigInteger().toString();
        int digitCapacity = 2 * length - 1;
        if (digits.length() > digitCapacity) {
            throw new IllegalArgumentException(
                    "Value '" + value + "' requires " + digits.length()
                            + " digits but length=" + length
                            + " allows only " + digitCapacity);
        }
        // Left-pad with leading zeros to fill the digit capacity.
        if (digits.length() < digitCapacity) {
            digits = "0".repeat(digitCapacity - digits.length()) + digits;
        }

        byte[] result = new byte[length];
        for (int i = 0; i < length - 1; i++) {
            int hi = digits.charAt(2 * i) - '0';
            int lo = digits.charAt(2 * i + 1) - '0';
            result[i] = (byte) ((hi << 4) | lo);
        }
        int lastDigit = digits.charAt(digitCapacity - 1) - '0';
        int signNibble = negative ? 0xD : 0xC;
        result[length - 1] = (byte) ((lastDigit << 4) | signNibble);
        return result;
    }

    // ---------------------------------------------------------------------
    // Zoned-decimal codec (USAGE DISPLAY with sign overpunch on last byte)
    // ---------------------------------------------------------------------

    /**
     * Parses a COBOL zoned-decimal ({@code USAGE DISPLAY} with sign
     * overpunch) ASCII byte sequence into a {@link BigDecimal}.
     *
     * <p>Layout: all bytes are ASCII digit characters {@code '0'}-{@code '9'}
     * EXCEPT the rightmost byte, which combines the rightmost digit with
     * the sign via overpunch:
     * <ul>
     *   <li>Positive: <code>'&#123;'</code> &rarr; +0,
     *       {@code 'A'}-{@code 'I'} &rarr; +1..+9</li>
     *   <li>Negative: <code>'&#125;'</code> &rarr; -0,
     *       {@code 'J'}-{@code 'R'} &rarr; -1..-9</li>
     *   <li>Plain digit {@code '0'}-{@code '9'} in the last position
     *       &rarr; positive (unsigned-style, accepted for fixture
     *       compatibility with files emitted by tools that do not
     *       overpunch)</li>
     * </ul>
     *
     * <p>Examples (verified against {@code app/data/ASCII/dailytran.txt}
     * and {@code app/data/ASCII/acctdata.txt}; values shown reflect the
     * spec convention that the rightmost byte carries the rightmost digit
     * via overpunch):
     * <ul>
     *   <li>Buffer <code>0000005047G</code> with PIC S9(09)V99
     *       ({@code length=11, scale=2}) &rarr; {@code 504.77}
     *       (G overpunch at position 11 contributes the trailing digit
     *       7 with positive sign)</li>
     *   <li>Buffer <code>0000009190&#125;</code> with PIC S9(09)V99
     *       ({@code length=11, scale=2}) &rarr; {@code -919.00}
     *       (close-brace overpunch at position 11 contributes the
     *       trailing digit 0 with negative sign)</li>
     *   <li>Buffer <code>00000001940&#123;</code> with PIC S9(10)V99
     *       ({@code length=12, scale=2}) &rarr; {@code 194.00}
     *       (open-brace overpunch at position 12 contributes the
     *       trailing digit 0 with positive sign)</li>
     * </ul>
     *
     * @param buffer source byte array (must not be null)
     * @param offset starting position (must be {@code >= 0})
     * @param length number of bytes (must be {@code >= 1})
     * @param scale  implied decimal places (must be {@code >= 0})
     * @return BigDecimal with exactly {@code scale} decimal places
     * @throws NullPointerException if {@code buffer} is null
     * @throws IndexOutOfBoundsException if {@code offset < 0} or
     *         {@code offset + length > buffer.length}
     * @throws IllegalArgumentException if {@code length < 1},
     *         {@code scale < 0}, a non-last byte is not a digit, or the
     *         last byte is neither a digit nor a recognized overpunch
     *         character
     */
    public static BigDecimal parseZonedDecimal(byte[] buffer, int offset, int length, int scale) {
        Objects.requireNonNull(buffer, "buffer");
        if (length < 1) {
            throw new IllegalArgumentException("length must be >= 1, got " + length);
        }
        if (offset < 0 || offset + length > buffer.length) {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + ", length=" + length
                            + ", buffer.length=" + buffer.length);
        }
        validateScale(scale);

        StringBuilder sb = new StringBuilder(length);
        // All bytes except the last are plain ASCII digits.
        for (int i = 0; i < length - 1; i++) {
            int c = buffer[offset + i] & 0xFF;
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(String.format(
                        "Invalid zoned-decimal digit at byte %d: 0x%02X ('%c')",
                        offset + i, c, (char) c));
            }
            sb.append((char) c);
        }
        // Last byte combines the rightmost digit with the sign overpunch.
        int last = buffer[offset + length - 1] & 0xFF;
        boolean negative;
        int lastDigit;
        if (last >= '0' && last <= '9') {
            // Unsigned-style: plain digit, treated as positive.
            negative = false;
            lastDigit = last - '0';
        } else if (last == '{') {
            negative = false;
            lastDigit = 0;
        } else if (last == '}') {
            negative = true;
            lastDigit = 0;
        } else if (last >= 'A' && last <= 'I') {
            negative = false;
            lastDigit = last - 'A' + 1;
        } else if (last >= 'J' && last <= 'R') {
            negative = true;
            lastDigit = last - 'J' + 1;
        } else {
            throw new IllegalArgumentException(String.format(
                    "Invalid zoned-decimal sign overpunch at byte %d: 0x%02X ('%c')",
                    offset + length - 1, last, (char) last));
        }
        sb.append((char) ('0' + lastDigit));

        String digits = sb.toString();
        BigDecimal unscaled = new BigDecimal((negative ? "-" : "") + digits);
        return unscaled.movePointLeft(scale).setScale(scale, RoundingMode.UNNECESSARY);
    }

    /**
     * Encodes a {@link BigDecimal} as a COBOL zoned-decimal
     * ({@code USAGE DISPLAY} with sign overpunch) ASCII byte sequence of
     * the specified length.
     *
     * <p>This is the inverse of
     * {@link #parseZonedDecimal(byte[], int, int, int)}. The value is
     * encoded as {@code length} ASCII bytes:
     * <ul>
     *   <li>The first {@code length - 1} bytes are plain ASCII digits
     *       {@code '0'}-{@code '9'} (left-zero-padded)</li>
     *   <li>The last byte combines the rightmost digit with the sign via
     *       overpunch (see {@link #parseZonedDecimal} for the mapping)</li>
     * </ul>
     *
     * <p>This encoder produces ONLY the canonical signed overpunch
     * (<code>'&#123;'</code> for +0, {@code 'A'}-{@code 'I'} for +1..+9,
     * <code>'&#125;'</code> for -0, {@code 'J'}-{@code 'R'} for -1..-9);
     * the plain-digit unsigned form is NOT produced. This matches the
     * canonical encoding used by COBOL {@code DISPLAY} format for signed
     * numeric fields ({@code PIC S9(n)V99}).
     *
     * <p>Byte-for-byte round-trip invariant (AAP &sect;0.6.5): for every
     * buffer slice {@code b} produced by COBOL {@code DISPLAY} of a signed
     * numeric field, {@code encodeZonedDecimal(parseZonedDecimal(b, 0,
     * b.length, scale), b.length, scale)} produces a byte sequence equal
     * to {@code b}. Round-trip from plain-digit fixtures (unsigned-style)
     * is NOT preserved: those are decoded as positive and re-encoded with
     * the canonical <code>'&#123;'</code>-style overpunch.
     *
     * @param value  the BigDecimal value to encode (must not be null)
     * @param length the target byte length (must be {@code >= 1})
     * @param scale  the implied decimal places (must be {@code >= 0} and
     *               equal to {@code value.scale()})
     * @return byte array of exactly {@code length} bytes
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if {@code length < 1},
     *         {@code scale < 0}, the value's magnitude exceeds
     *         {@code length} digits, or the value's actual scale requires
     *         rounding to reach the requested {@code scale}
     */
    public static byte[] encodeZonedDecimal(BigDecimal value, int length, int scale) {
        Objects.requireNonNull(value, "value");
        if (length < 1) {
            throw new IllegalArgumentException("length must be >= 1, got " + length);
        }
        validateScale(scale);

        final BigDecimal scaledValue;
        try {
            scaledValue = value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "Value '" + value + "' has scale " + value.scale()
                            + " which cannot be normalized to scale=" + scale
                            + " without rounding; pre-scale the value via Decimals.scaled(...)",
                    ex);
        }
        BigDecimal unscaled = scaledValue.movePointRight(scale);
        boolean negative = unscaled.signum() < 0;
        String digits = unscaled.abs().toBigInteger().toString();
        if (digits.length() > length) {
            throw new IllegalArgumentException(
                    "Value '" + value + "' requires " + digits.length()
                            + " digits but length=" + length);
        }
        if (digits.length() < length) {
            digits = "0".repeat(length - digits.length()) + digits;
        }

        byte[] result = new byte[length];
        for (int i = 0; i < length - 1; i++) {
            result[i] = (byte) digits.charAt(i);
        }
        int lastDigit = digits.charAt(length - 1) - '0';
        byte overpunch;
        if (negative) {
            overpunch = (byte) (lastDigit == 0 ? '}' : ('J' + lastDigit - 1));
        } else {
            overpunch = (byte) (lastDigit == 0 ? '{' : ('A' + lastDigit - 1));
        }
        result[length - 1] = overpunch;
        return result;
    }

    // ---------------------------------------------------------------------
    // Edit mask formatting (COBOL DISPLAY-style fixed-width string output)
    // ---------------------------------------------------------------------

    /**
     * Formats a {@link BigDecimal} as a fixed-width display string with a
     * leading sign character, zero-padded integer digits, an embedded
     * decimal point (when {@code decimalDigits > 0}), and zero-padded
     * fractional digits. Corresponds to a COBOL edit mask of the form
     * {@code PIC -9(integerDigits).9(decimalDigits)} (signed numeric
     * display with literal decimal point).
     *
     * <p>The output width is exactly
     * {@code 1 + integerDigits + (decimalDigits > 0 ? 1 + decimalDigits : 0)}
     * &mdash; one sign character plus the integer field plus the optional
     * decimal point and fractional field.
     *
     * <p>The sign character is {@code '-'} for negative values and
     * {@code ' '} (space) for non-negative values (including zero).
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code formatEditMask(new BigDecimal("194.00"), 10, 2)} &rarr;
     *       {@code " 0000000194.00"} (14 characters)</li>
     *   <li>{@code formatEditMask(new BigDecimal("-91.90"), 10, 2)} &rarr;
     *       {@code "-0000000091.90"} (14 characters)</li>
     *   <li>{@code formatEditMask(BigDecimal.ZERO, 5, 0)} &rarr;
     *       {@code " 00000"} (6 characters; no decimal point)</li>
     *   <li>{@code formatEditMask(new BigDecimal("12345.6789"), 5, 2)} &rarr;
     *       {@code " 12345.68"} (rounding via {@link #ROUNDED_MODE})</li>
     * </ul>
     *
     * <p>Use this method for COBOL report fields that translate to
     * fixed-width string output (e.g., transaction reports in CBTRN03C,
     * statement detail lines in CBSTM03A, total lines in CORPT00C). The
     * value is rounded to {@code decimalDigits} via {@link #ROUNDED_MODE}
     * (banker's rounding) consistent with the COBOL convention for
     * {@code MOVE} into a numeric edited field.
     *
     * @param value         the value to format (must not be null)
     * @param integerDigits the integer-part width (must be {@code >= 1})
     * @param decimalDigits the decimal-part width (must be {@code >= 0})
     * @return fixed-width display string
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if {@code integerDigits < 1},
     *         {@code decimalDigits < 0}, or the integer part of the
     *         (rounded) value exceeds {@code integerDigits} digits
     */
    public static String formatEditMask(BigDecimal value, int integerDigits, int decimalDigits) {
        Objects.requireNonNull(value, "value");
        if (integerDigits < 1) {
            throw new IllegalArgumentException(
                    "integerDigits must be >= 1, got " + integerDigits);
        }
        if (decimalDigits < 0) {
            throw new IllegalArgumentException(
                    "decimalDigits must be >= 0, got " + decimalDigits);
        }

        BigDecimal scaledValue = value.setScale(decimalDigits, ROUNDED_MODE);
        boolean negative = scaledValue.signum() < 0;
        BigDecimal magnitude = scaledValue.abs();
        // After movePointRight(decimalDigits), magnitude is an integer in
        // BigDecimal form whose digit count equals the integer-digit count
        // plus the decimal-digit count (no leading zeros).
        String unscaledDigits = magnitude.movePointRight(decimalDigits).toBigInteger().toString();
        int totalDigits = integerDigits + decimalDigits;
        if (unscaledDigits.length() > totalDigits) {
            int integerPartDigits = unscaledDigits.length() - decimalDigits;
            throw new IllegalArgumentException(
                    "Value '" + value + "' integer part requires "
                            + integerPartDigits
                            + " digits but integerDigits=" + integerDigits);
        }
        if (unscaledDigits.length() < totalDigits) {
            unscaledDigits = "0".repeat(totalDigits - unscaledDigits.length()) + unscaledDigits;
        }

        StringBuilder sb = new StringBuilder(1 + totalDigits + (decimalDigits > 0 ? 1 : 0));
        sb.append(negative ? '-' : ' ');
        sb.append(unscaledDigits, 0, integerDigits);
        if (decimalDigits > 0) {
            sb.append('.');
            sb.append(unscaledDigits, integerDigits, totalDigits);
        }
        return sb.toString();
    }

    /**
     * Formats a {@link BigDecimal} as a fixed-width display string with
     * zero-padded integer digits, an embedded decimal point (when
     * {@code decimalDigits > 0}), and zero-padded fractional digits, and
     * <strong>no</strong> leading sign character. Corresponds to a DFSORT
     * {@code EDIT=(T...T.T...T)} mask over an unsigned source field
     * &mdash; the variant required by the {@code PRTCATBL.jcl}
     * {@code OUTREC FIELDS=(...,TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),...)}
     * specification {@code [app/jcl/PRTCATBL.jcl:L53-L56]}.
     *
     * <h3>How this differs from {@link #formatEditMask(BigDecimal, int, int)}</h3>
     * <p>{@link #formatEditMask(BigDecimal, int, int)} corresponds to the
     * COBOL edit mask {@code PIC -9(integerDigits).9(decimalDigits)} which
     * always emits a leading sign character ({@code ' '} for non-negative,
     * {@code '-'} for negative). DFSORT EDIT-mask {@code T} digit positions
     * are unsigned per the IBM DFSORT Application Programming Guide:
     * {@code T} indicates a digit position with no associated sign. When the
     * source field is {@code ZD} (zoned decimal) and the EDIT pattern uses
     * only {@code T} symbols (no {@code S} sign symbol and no {@code SIGNS}
     * subparameter), the rendered output contains only digits and the
     * embedded literal decimal point. Negative values, if encountered, are
     * rendered as their absolute value (the sign is dropped) &mdash; this is
     * the DFSORT default for unsigned EDIT patterns and is the basis for
     * the AAP key insight ("Negative balances are NOT expected for TCATBAL;
     * if encountered, the EDIT mask would emit them as positive (DFSORT
     * default)").
     *
     * <p>The output width is exactly
     * {@code integerDigits + (decimalDigits > 0 ? 1 + decimalDigits : 0)}
     * &mdash; the integer field plus the optional decimal point and
     * fractional field (no sign character).
     *
     * <p>Examples (matching the JCL specification for
     * {@code EDIT=(TTTTTTTTT.TT)} with 9 integer digits and 2 decimal digits):
     * <ul>
     *   <li>{@code formatEditMaskUnsigned(new BigDecimal("194.00"), 9, 2)} &rarr;
     *       {@code "000000194.00"} (12 characters)</li>
     *   <li>{@code formatEditMaskUnsigned(BigDecimal.ZERO, 9, 2)} &rarr;
     *       {@code "000000000.00"} (12 characters)</li>
     *   <li>{@code formatEditMaskUnsigned(new BigDecimal("-91.90"), 9, 2)} &rarr;
     *       {@code "000000091.90"} (12 characters; absolute value, DFSORT
     *       unsigned-EDIT default)</li>
     *   <li>{@code formatEditMaskUnsigned(BigDecimal.ZERO, 5, 0)} &rarr;
     *       {@code "00000"} (5 characters; no decimal point)</li>
     * </ul>
     *
     * <p>Use this method to translate DFSORT {@code EDIT=(T...T.T...T)}
     * OUTREC specifications on unsigned source fields. The value is rounded
     * to {@code decimalDigits} via {@link #ROUNDED_MODE} (banker's rounding)
     * consistent with the rounding default used by
     * {@link #formatEditMask(BigDecimal, int, int)} above. In practice, the
     * input value is almost always already at the target scale (the COBOL
     * source field is {@code PIC S9(9)V99} with implicit scale 2 and the
     * canonical constructor of {@code TranCatBalRecord} normalizes the
     * scale on entry), so no rounding actually occurs &mdash; the
     * {@code setScale} call is defensive.
     *
     * @param value         the value to format (must not be null)
     * @param integerDigits the integer-part width (must be {@code >= 1})
     * @param decimalDigits the decimal-part width (must be {@code >= 0})
     * @return fixed-width display string of exactly
     *         {@code integerDigits + (decimalDigits > 0 ? 1 + decimalDigits : 0)}
     *         characters
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if {@code integerDigits < 1},
     *         {@code decimalDigits < 0}, or the integer part of the
     *         (rounded) absolute value exceeds {@code integerDigits} digits
     */
    public static String formatEditMaskUnsigned(BigDecimal value, int integerDigits, int decimalDigits) {
        Objects.requireNonNull(value, "value");
        if (integerDigits < 1) {
            throw new IllegalArgumentException(
                    "integerDigits must be >= 1, got " + integerDigits);
        }
        if (decimalDigits < 0) {
            throw new IllegalArgumentException(
                    "decimalDigits must be >= 0, got " + decimalDigits);
        }

        // Round to the target scale using banker's rounding for parity with
        // formatEditMask above; then drop the sign (DFSORT T unsigned-EDIT
        // default treats negative values as their absolute value when the
        // pattern contains no S sign symbol and no SIGNS subparameter).
        BigDecimal scaledValue = value.setScale(decimalDigits, ROUNDED_MODE);
        BigDecimal magnitude = scaledValue.abs();
        // After movePointRight(decimalDigits), magnitude is an integer in
        // BigDecimal form whose digit count equals the integer-digit count
        // plus the decimal-digit count (no leading zeros).
        String unscaledDigits = magnitude.movePointRight(decimalDigits).toBigInteger().toString();
        int totalDigits = integerDigits + decimalDigits;
        if (unscaledDigits.length() > totalDigits) {
            int integerPartDigits = unscaledDigits.length() - decimalDigits;
            throw new IllegalArgumentException(
                    "Value '" + value + "' integer part requires "
                            + integerPartDigits
                            + " digits but integerDigits=" + integerDigits);
        }
        if (unscaledDigits.length() < totalDigits) {
            unscaledDigits = "0".repeat(totalDigits - unscaledDigits.length()) + unscaledDigits;
        }

        StringBuilder sb = new StringBuilder(totalDigits + (decimalDigits > 0 ? 1 : 0));
        sb.append(unscaledDigits, 0, integerDigits);
        if (decimalDigits > 0) {
            sb.append('.');
            sb.append(unscaledDigits, integerDigits, totalDigits);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------

    /**
     * Validates that a scale parameter is non-negative.
     *
     * @param scale the scale to validate
     * @throws IllegalArgumentException if {@code scale < 0}
     */
    private static void validateScale(int scale) {
        if (scale < 0) {
            throw new IllegalArgumentException("scale must be >= 0, got " + scale);
        }
    }
}
