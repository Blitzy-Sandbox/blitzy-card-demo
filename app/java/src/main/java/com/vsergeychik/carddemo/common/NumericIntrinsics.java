package com.vsergeychik.carddemo.common;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The single home of the COBOL numeric-conversion intrinsics for this migration: {@code FUNCTION NUMVAL},
 * {@code FUNCTION NUMVAL-C} and their conformance companions {@code FUNCTION TEST-NUMVAL} and
 * {@code FUNCTION TEST-NUMVAL-C}.
 *
 * <p>A COBOL item is never absent, so a {@code null} here is a Java defect and is reported as one.
 */
public final class NumericIntrinsics {
    public static final int CONFORMS = 0;

    public static final int MAXIMUM_DIGITS = 18;

    /**
     * The value returned for an argument that does not conform.
     */
    public static final BigDecimal NON_CONFORMING_VALUE = BigDecimal.ZERO;

    private static final char SPACE = ' ';

    private static final char DECIMAL_POINT = '.';

    private static final char DIGIT_SEPARATOR = ',';

    private static final char CURRENCY_SIGN = '$';

    private static final char PLUS_SIGN = '+';

    private static final char MINUS_SIGN = '-';

    private static final String CREDIT_INDICATOR = "CR";

    private static final String DEBIT_INDICATOR = "DB";

    private NumericIntrinsics() {
        throw new AssertionError("NumericIntrinsics is a holder for the COBOL conversion intrinsics "
                + "and is never instantiated");
    }

    /**
     * {@code FUNCTION NUMVAL} - the value of a character representation of a number.
     *
     * @param image the argument to convert; must not be {@code null}
     * @return the value the argument denotes, or {@link #NON_CONFORMING_VALUE} when it does not conform
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static BigDecimal numval(String image) {
        return scanNumval(image).value();
    }

    /**
     * {@code FUNCTION TEST-NUMVAL} - whether an argument is a valid operand of {@link #numval(String)}.
     *
     * @param image the argument to test; must not be {@code null}
     * @return {@value #CONFORMS} when the argument conforms; otherwise the one-based position of the first
     *     character in error, or the argument's length plus one when it holds no digit
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static int testNumval(String image) {
        return scanNumval(image).errorPosition();
    }

    /**
     * {@code FUNCTION NUMVAL-C} - as {@link #numval(String)}, but the argument may also carry a currency
     * sign and grouping commas.
     *
     * @param image the argument to convert; must not be {@code null}
     * @return the value the argument denotes, or {@link #NON_CONFORMING_VALUE} when it does not conform
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static BigDecimal numvalC(String image) {
        return scanNumvalC(image).value();
    }

    /**
     * {@code FUNCTION TEST-NUMVAL-C} - whether an argument is a valid operand of {@link #numvalC(String)}.
     *
     * @param image the argument to test; must not be {@code null}
     * @return {@value #CONFORMS} when the argument conforms; otherwise the one-based position of the first
     *     character in error, or the argument's length plus one when it holds no digit
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static int testNumvalC(String image) {
        return scanNumvalC(image).errorPosition();
    }

    /**
     * One {@code NUMVAL} pass, yielding the value and the verdict together.
     *
     * @param image the argument; must not be {@code null}
     * @return the scan's outcome
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static Scan scanNumval(String image) {
        Objects.requireNonNull(image, "FUNCTION NUMVAL and FUNCTION TEST-NUMVAL require an argument; "
                + "a COBOL item is never absent, so a null here is a Java defect");
        return scan(image, false);
    }

    /**
     * One {@code NUMVAL-C} pass, yielding the value and the verdict together.
     *
     * @param image the argument; must not be {@code null}
     * @return the scan's outcome
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static Scan scanNumvalC(String image) {
        Objects.requireNonNull(image, "FUNCTION NUMVAL-C and FUNCTION TEST-NUMVAL-C require an "
                + "argument; a COBOL item is never absent, so a null here is a Java defect");
        return scan(image, true);
    }

    private static Scan scan(String image, boolean currencyForm) {
        int length = image.length();
        int index = skipSpaces(image, 0);

        boolean negative = false;
        boolean leadingSignSeen = false;
        if (index < length && isSign(image.charAt(index))) {
            negative = image.charAt(index) == MINUS_SIGN;
            leadingSignSeen = true;
            index = skipSpaces(image, index + 1);
        }

        if (currencyForm && index < length && image.charAt(index) == CURRENCY_SIGN) {
            index = skipSpaces(image, index + 1);
        }

        StringBuilder digits = new StringBuilder();
        int fractionDigits = 0;
        boolean decimalPointSeen = false;
        while (index < length) {
            char character = image.charAt(index);
            if (isDigit(character)) {
                if (digits.length() == MAXIMUM_DIGITS) {
                    return Scan.rejected(index + 1);
                }
                digits.append(character);
                if (decimalPointSeen) {
                    fractionDigits++;
                }
                index++;
            } else if (character == DECIMAL_POINT && !decimalPointSeen) {
                decimalPointSeen = true;
                index++;
            } else if (currencyForm && character == DIGIT_SEPARATOR && !decimalPointSeen
                    && digits.length() > 0 && index + 1 < length && isDigit(image.charAt(index + 1))) {
                index++;
            } else {
                break;
            }
        }

        if (digits.length() == 0) {
            return Scan.rejected(length + 1);
        }

        index = skipSpaces(image, index);
        if (index < length && !leadingSignSeen) {
            char character = image.charAt(index);
            if (isSign(character)) {
                negative = character == MINUS_SIGN;
                index = skipSpaces(image, index + 1);
            } else if (image.startsWith(CREDIT_INDICATOR, index)
                    || image.startsWith(DEBIT_INDICATOR, index)) {
                negative = true;
                index = skipSpaces(image, index + CREDIT_INDICATOR.length());
            }
        }

        if (index != length) {
            return Scan.rejected(index + 1);
        }

        BigDecimal magnitude = new BigDecimal(digits.toString()).movePointLeft(fractionDigits);
        return Scan.accepted(negative ? magnitude.negate() : magnitude);
    }

    private static int skipSpaces(String image, int from) {
        int index = from;
        while (index < image.length() && image.charAt(index) == SPACE) {
            index++;
        }
        return index;
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private static boolean isSign(char character) {
        return character == PLUS_SIGN || character == MINUS_SIGN;
    }

    /**
     * The outcome of one intrinsic scan: the converted value and the conformance verdict together.
     *
     * @param value the converted value, or {@link #NON_CONFORMING_VALUE} when {@code errorPosition} is not
     *     {@value #CONFORMS}
     * @param errorPosition {@value #CONFORMS} when the argument conforms; otherwise the one-based position
     *     of the first character in error, or the argument's length plus one when it holds no digit
     */
    public record Scan(BigDecimal value, int errorPosition) {
        public Scan {
            Objects.requireNonNull(value, "A scan always carries a value; a non-conforming argument "
                    + "carries NON_CONFORMING_VALUE rather than null");
            if (errorPosition < CONFORMS) {
                throw new IllegalArgumentException("A reported position is one-based, so it is never "
                        + "negative: " + errorPosition);
            }
        }

        /**
         * Whether the scanned argument was a valid operand.
         *
         * @return {@code true} when the argument conforms
         */
        public boolean conforms() {
            return errorPosition == CONFORMS;
        }

        static Scan accepted(BigDecimal value) {
            return new Scan(value, CONFORMS);
        }

        static Scan rejected(int position) {
            return new Scan(NON_CONFORMING_VALUE, position);
        }
    }
}
