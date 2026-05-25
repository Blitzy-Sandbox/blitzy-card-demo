/*
 * Copyright 2024 AWS CardDemo Migration Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.awsm2.carddemo.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Low-level COBOL fixed-width record codec primitives used by entity-level
 * {@code parse(byte[])} / {@code format()} methods to round-trip ASCII
 * fixed-width records originally produced by the COBOL CardDemo file layouts.
 *
 * <p>Code Review CP7 FINAL — CRITICAL: This class delivers the production-side
 * parse/format building blocks that the {@code GoldenOutputDiffTest} requires
 * to prove the AAP &sect;0.2.2 byte-identical regulatory-output guarantee. Each
 * COBOL {@code PIC} clause used in the seven copybooks driving the golden
 * fixtures has a one-to-one helper here:</p>
 * <ul>
 *   <li>{@code PIC X(n)} (alphanumeric, left-justified, SPACE-padded) —
 *       {@link #parseText(byte[], int, int)} / {@link #formatText(String, int)}</li>
 *   <li>{@code PIC 9(n)} (unsigned numeric, right-justified, ZERO-padded) —
 *       {@link #parseLong(byte[], int, int)} / {@link #formatLong(long, int)}
 *       and {@link #parseInt(byte[], int, int)} /
 *       {@link #formatInt(int, int)}</li>
 *   <li>{@code PIC S9(m)V9(s)} (signed zoned-decimal with implicit decimal
 *       point at position {@code s} from the right) —
 *       {@link #parseZonedDecimal(byte[], int, int, int)} /
 *       {@link #formatZonedDecimal(BigDecimal, int, int)}</li>
 *   <li>ISO date {@code yyyy-MM-dd} stored as {@code PIC X(10)} —
 *       {@link #parseLocalDate(byte[], int, int)} /
 *       {@link #formatLocalDate(LocalDate)}</li>
 * </ul>
 *
 * <h2>Zoned-decimal encoding (signed COBOL DISPLAY)</h2>
 * <p>COBOL signed numeric fields (PIC S9(n)V9(s)) on z/OS use overpunched
 * sign in the rightmost byte. The sign is folded onto the last digit using the
 * IBM convention:</p>
 * <table>
 *   <tr><th>Last digit</th><th>Positive overpunch</th><th>Negative overpunch</th></tr>
 *   <tr><td>0</td><td>{@code {}</td><td>{@code }}</td></tr>
 *   <tr><td>1</td><td>{@code A}</td><td>{@code J}</td></tr>
 *   <tr><td>2</td><td>{@code B}</td><td>{@code K}</td></tr>
 *   <tr><td>3</td><td>{@code C}</td><td>{@code L}</td></tr>
 *   <tr><td>4</td><td>{@code D}</td><td>{@code M}</td></tr>
 *   <tr><td>5</td><td>{@code E}</td><td>{@code N}</td></tr>
 *   <tr><td>6</td><td>{@code F}</td><td>{@code O}</td></tr>
 *   <tr><td>7</td><td>{@code G}</td><td>{@code P}</td></tr>
 *   <tr><td>8</td><td>{@code H}</td><td>{@code Q}</td></tr>
 *   <tr><td>9</td><td>{@code I}</td><td>{@code R}</td></tr>
 * </table>
 *
 * <p>Example: {@code PIC S9(10)V99} value 194.00 → 12 digits "000000019400"
 * → overpunched as {@code "00000001940{"} (positive 0 in the units digit).</p>
 *
 * <h2>Character set</h2>
 * <p>All ASCII fixtures in {@code app/data/ASCII/} and
 * {@code src/test/resources/golden/} are 7-bit ASCII. This codec uses
 * {@link StandardCharsets#US_ASCII} for encode/decode operations to preserve
 * the byte-level identity required by AAP &sect;0.2.2.</p>
 *
 * <h2>Round-trip discipline</h2>
 * <p>Every {@code parseX(...)} method extracts a semantic value, and every
 * {@code formatX(...)} method emits exactly the inverse byte sequence. Pairs
 * are mutually inverse for valid inputs:</p>
 * <pre>{@code
 * BigDecimal v = CobolCodec.parseZonedDecimal(bytes, off, 12, 2);
 * byte[] out = CobolCodec.formatZonedDecimal(v, 12, 2);
 * // Arrays.copyOfRange(bytes, off, off + 12) equals out
 * }</pre>
 *
 * <p>This class is stateless, final, has no public constructor, and is safe
 * for concurrent use from any number of threads.</p>
 *
 * @see com.awsm2.carddemo.domain.Account
 * @see com.awsm2.carddemo.domain.Card
 * @see com.awsm2.carddemo.domain.Customer
 * @see com.awsm2.carddemo.domain.CardCrossReference
 * @see com.awsm2.carddemo.domain.Transaction
 * @see com.awsm2.carddemo.domain.DailyTransaction
 * @see com.awsm2.carddemo.domain.DisclosureGroup
 * @see com.awsm2.carddemo.domain.TransactionCategoryBalance
 * @see com.awsm2.carddemo.domain.TransactionCategory
 * @see com.awsm2.carddemo.domain.TransactionType
 */
public final class CobolCodec {

    /** All COBOL fixtures in this project use 7-bit ASCII encoding. */
    public static final Charset ASCII = StandardCharsets.US_ASCII;

    /** ISO-8601 {@code yyyy-MM-dd} date pattern stored as PIC X(10). */
    public static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** ASCII space (0x20) used to pad PIC X(n) fields on the right. */
    public static final byte ASCII_SPACE = 0x20;

    /** ASCII zero (0x30) used to pad PIC 9(n) fields on the left. */
    public static final byte ASCII_ZERO = 0x30;

    // Zoned-decimal overpunch tables (index = absolute digit 0..9).
    private static final char[] ZONED_POSITIVE = {
            '{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'
    };

    private static final char[] ZONED_NEGATIVE = {
            '}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'
    };

    private CobolCodec() {
        // Utility class — never instantiated.
    }

    // ------------------------------------------------------------------
    // Text fields (PIC X(n))
    // ------------------------------------------------------------------

    /**
     * Extract a fixed-width text slice from the given byte array. The slice
     * is decoded as ASCII; trailing SPACE characters are <em>preserved</em>
     * to keep the round-trip exact. Callers that want a "trimmed" view
     * should call {@code String#stripTrailing()} themselves.
     *
     * @param src       source byte array
     * @param offset    offset of the first byte of the field within {@code src}
     * @param length    number of bytes in the field (per COBOL PIC X(n))
     * @return the field's ASCII content, exactly {@code length} characters
     */
    public static String parseText(byte[] src, int offset, int length) {
        return new String(src, offset, length, ASCII);
    }

    /**
     * Render a {@code PIC X(n)} field as exactly {@code length} ASCII bytes,
     * left-justified and SPACE-padded (COBOL VALUE SPACES convention).
     * Truncates if {@code value} is longer than {@code length}.
     *
     * @param value  the textual value (may be {@code null}, treated as empty)
     * @param length the COBOL field width
     * @return a byte array of exactly {@code length} bytes
     */
    public static byte[] formatText(String value, int length) {
        byte[] out = new byte[length];
        if (value != null) {
            byte[] src = value.getBytes(ASCII);
            int copy = Math.min(src.length, length);
            System.arraycopy(src, 0, out, 0, copy);
            for (int i = copy; i < length; i++) {
                out[i] = ASCII_SPACE;
            }
        } else {
            for (int i = 0; i < length; i++) {
                out[i] = ASCII_SPACE;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Numeric fields (PIC 9(n))
    // ------------------------------------------------------------------

    /**
     * Parse an unsigned numeric field {@code PIC 9(n)} into a {@code long}.
     *
     * @param src    source byte array
     * @param offset offset of the field
     * @param length the field width
     * @return the parsed long value (always non-negative)
     */
    public static long parseLong(byte[] src, int offset, int length) {
        String s = parseText(src, offset, length).trim();
        if (s.isEmpty()) {
            return 0L;
        }
        return Long.parseLong(s);
    }

    /**
     * Parse an unsigned numeric field {@code PIC 9(n)} into an {@code int}.
     *
     * @param src    source byte array
     * @param offset offset of the field
     * @param length the field width
     * @return the parsed int value (always non-negative)
     */
    public static int parseInt(byte[] src, int offset, int length) {
        return Math.toIntExact(parseLong(src, offset, length));
    }

    /**
     * Format an unsigned numeric value as {@code PIC 9(n)}: exactly
     * {@code length} ASCII bytes, right-justified, ZERO-padded on the left.
     *
     * @param value  the value (must be non-negative)
     * @param length the COBOL field width
     * @return a byte array of exactly {@code length} bytes
     */
    public static byte[] formatLong(long value, int length) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    "PIC 9(" + length + ") cannot encode negative value: " + value);
        }
        return zeroPadAscii(Long.toString(value), length);
    }

    /**
     * Format an unsigned numeric value as {@code PIC 9(n)}: exactly
     * {@code length} ASCII bytes, right-justified, ZERO-padded on the left.
     *
     * @param value  the value (must be non-negative)
     * @param length the COBOL field width
     * @return a byte array of exactly {@code length} bytes
     */
    public static byte[] formatInt(int value, int length) {
        return formatLong((long) value, length);
    }

    // ------------------------------------------------------------------
    // Signed zoned-decimal (PIC S9(m)V9(s))
    // ------------------------------------------------------------------

    /**
     * Parse a signed COBOL zoned-decimal field {@code PIC S9(m)V9(s)} into a
     * {@link BigDecimal} with scale {@code s}. The sign is decoded from the
     * IBM overpunch on the rightmost byte.
     *
     * @param src    source byte array
     * @param offset offset of the field
     * @param length the field width (number of digits, including the sign byte)
     * @param scale  the implied decimal scale {@code V9(scale)}
     * @return the parsed {@link BigDecimal} with the given {@code scale}
     */
    public static BigDecimal parseZonedDecimal(byte[] src, int offset, int length, int scale) {
        if (length < 1) {
            throw new IllegalArgumentException("zoned-decimal length must be ≥ 1");
        }
        // Extract digits as a mutable char[]
        char[] digits = new char[length];
        for (int i = 0; i < length; i++) {
            digits[i] = (char) (src[offset + i] & 0xFF);
        }
        char last = digits[length - 1];
        boolean negative;
        int signDigit;
        int idxPos = indexOf(ZONED_POSITIVE, last);
        int idxNeg = indexOf(ZONED_NEGATIVE, last);
        if (idxPos >= 0) {
            negative = false;
            signDigit = idxPos;
        } else if (idxNeg >= 0) {
            negative = true;
            signDigit = idxNeg;
        } else if (last >= '0' && last <= '9') {
            // No overpunch — implicit positive (some COBOL programs emit
            // raw digits when the field is initialised to ZERO).
            negative = false;
            signDigit = last - '0';
        } else {
            throw new IllegalArgumentException(
                    "Invalid zoned-decimal sign byte at position " + (offset + length - 1)
                            + ": 0x" + Integer.toHexString(last & 0xFF));
        }
        digits[length - 1] = (char) ('0' + signDigit);
        BigInteger unscaled = new BigInteger(new String(digits));
        if (negative) {
            unscaled = unscaled.negate();
        }
        return new BigDecimal(unscaled, scale);
    }

    /**
     * Format a {@link BigDecimal} value as a signed COBOL zoned-decimal field
     * {@code PIC S9(m)V9(scale)}: exactly {@code length} ASCII bytes,
     * right-justified, ZERO-padded on the left, with sign overpunched on the
     * rightmost byte using the IBM convention.
     *
     * @param value  the value to encode
     * @param length the total field width (must be ≥ scale + 1)
     * @param scale  the implied decimal scale
     * @return a byte array of exactly {@code length} bytes
     * @throws ArithmeticException if the value's magnitude does not fit
     */
    public static byte[] formatZonedDecimal(BigDecimal value, int length, int scale) {
        if (length < 1) {
            throw new IllegalArgumentException("zoned-decimal length must be ≥ 1");
        }
        if (value == null) {
            value = BigDecimal.ZERO;
        }
        BigDecimal scaled = value.setScale(scale, RoundingMode.HALF_EVEN);
        BigInteger unscaled = scaled.unscaledValue();
        boolean negative = unscaled.signum() < 0;
        String digits = unscaled.abs().toString();
        if (digits.length() > length) {
            throw new ArithmeticException(
                    "BigDecimal value " + value + " (unscaled " + digits
                            + ") does not fit in PIC S9(" + (length - scale)
                            + ")V9(" + scale + ") — overflow at format time");
        }
        // Left-pad with ASCII zeros to exactly `length` digits
        byte[] out = new byte[length];
        int padCount = length - digits.length();
        for (int i = 0; i < padCount; i++) {
            out[i] = ASCII_ZERO;
        }
        for (int i = 0; i < digits.length(); i++) {
            out[padCount + i] = (byte) digits.charAt(i);
        }
        // Overpunch sign onto rightmost byte
        int lastDigit = out[length - 1] - ASCII_ZERO;
        out[length - 1] = (byte) (negative
                ? ZONED_NEGATIVE[lastDigit]
                : ZONED_POSITIVE[lastDigit]);
        return out;
    }

    // ------------------------------------------------------------------
    // Date fields (PIC X(10) yyyy-MM-dd)
    // ------------------------------------------------------------------

    /**
     * Parse an ISO-8601 {@code yyyy-MM-dd} date stored as {@code PIC X(10)}.
     * Returns {@code null} for an all-SPACE field (some COBOL records carry
     * optional dates as 10 spaces when the date is unset).
     *
     * @param src    source byte array
     * @param offset offset of the field
     * @param length the field width (must be 10 for ISO dates)
     * @return the parsed {@link LocalDate} or {@code null} if blank
     */
    public static LocalDate parseLocalDate(byte[] src, int offset, int length) {
        if (length != 10) {
            throw new IllegalArgumentException(
                    "ISO date must be PIC X(10); got length=" + length);
        }
        String s = parseText(src, offset, length);
        if (s.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s, ISO_DATE);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "Invalid ISO date in COBOL record at offset " + offset + ": '" + s + "'", ex);
        }
    }

    /**
     * Format a {@link LocalDate} as a {@code PIC X(10)} ISO-8601 date.
     * A {@code null} input produces 10 SPACE bytes.
     *
     * @param value the date to encode (may be {@code null})
     * @return a byte array of exactly 10 bytes
     */
    public static byte[] formatLocalDate(LocalDate value) {
        if (value == null) {
            byte[] out = new byte[10];
            for (int i = 0; i < 10; i++) {
                out[i] = ASCII_SPACE;
            }
            return out;
        }
        return value.format(ISO_DATE).getBytes(ASCII);
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Convert a decimal string to a fixed-width ASCII byte array with
     * left ZERO-padding.
     */
    private static byte[] zeroPadAscii(String digits, int length) {
        if (digits.length() > length) {
            throw new ArithmeticException(
                    "Value " + digits + " does not fit in " + length + " digits");
        }
        byte[] out = new byte[length];
        int padCount = length - digits.length();
        for (int i = 0; i < padCount; i++) {
            out[i] = ASCII_ZERO;
        }
        for (int i = 0; i < digits.length(); i++) {
            out[padCount + i] = (byte) digits.charAt(i);
        }
        return out;
    }

    private static int indexOf(char[] arr, char c) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == c) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Buffer assembly helpers
    // ------------------------------------------------------------------

    /**
     * Copy {@code src} into {@code dst} starting at {@code offset}. Helper
     * used by entity-level {@code format()} methods to assemble a fixed-
     * width record byte buffer field-by-field.
     *
     * @param dst    destination buffer
     * @param offset destination offset
     * @param src    source bytes
     * @throws ArrayIndexOutOfBoundsException if the copy overruns {@code dst}
     */
    public static void put(byte[] dst, int offset, byte[] src) {
        System.arraycopy(src, 0, dst, offset, src.length);
    }

    /**
     * Fill a range of {@code dst} with ASCII SPACEs. Useful for FILLER
     * positions that the original COBOL layout reserved for future use.
     *
     * @param dst    destination buffer
     * @param offset start offset
     * @param length number of bytes to fill
     */
    public static void fillSpaces(byte[] dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst[offset + i] = ASCII_SPACE;
        }
    }

    /**
     * Fill a range of {@code dst} with ASCII ZERO digits. Useful for
     * FILLER positions on COBOL reference-data records that historically
     * initialised FILLER to ZEROES rather than SPACES (e.g., DIS-GROUP,
     * TRAN-CAT-BAL, TRAN-TYPE, TRAN-CAT).
     *
     * @param dst    destination buffer
     * @param offset start offset
     * @param length number of bytes to fill
     */
    public static void fillZeros(byte[] dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst[offset + i] = ASCII_ZERO;
        }
    }
}
