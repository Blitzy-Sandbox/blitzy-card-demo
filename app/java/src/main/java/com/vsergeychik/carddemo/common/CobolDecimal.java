package com.vsergeychik.carddemo.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The single home of numeric parity for the CardDemo COBOL to Java 21 migration.
 *
 * <p>COBOL only rounds when a statement explicitly says {@code ROUNDED}; absent it, excess fractional
 * digits are truncated when the result is stored.
 */
public final class CobolDecimal {
    /**
     * The scale of every scaled numeric field in the CardDemo data model: exactly {@code 2}.
     *
     * <p>Justified by the {@code PICTURE} V-scale scan across {@code app/cbl} and {@code app/cpy}, which
     * returns only {@code V99} and does so in exactly 34 occurrences.
     */
    public static final int MONETARY_SCALE = 2;

    /**
     * The only rounding mode permitted in a monetary path: {@link RoundingMode#DOWN}, which truncates
     * toward zero.
     *
     * <p>COBOL rounds only where a statement says so explicitly, so every store in this system truncates
     * its excess fractional digits.
     */
    public static final RoundingMode COBOL_ROUNDING = RoundingMode.DOWN;

    /**
     * The divisor in the monthly interest formula: the integer literal {@code 1200} written in
     * {@code app/cbl/CBACT04C.cbl:L465}.
     */
    public static final long MONTHLY_INTEREST_DIVISOR = 1200L;

    private CobolDecimal() {
        throw new AssertionError("CobolDecimal is a non-instantiable utility holder");
    }

    /**
     * Stores {@code value} into a receiving field of the given scale, truncating excess fractional digits
     * exactly as COBOL does.
     *
     * @param value the computed value to store; must not be {@code null}
     * @param scale the receiving field's declared scale, taken from its {@code PICTURE} clause; must not be
     *     negative
     * @return {@code value} truncated to {@code scale}, whose {@link BigDecimal#scale()} is exactly
     *     {@code scale}
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative
     */
    public static BigDecimal store(BigDecimal value, int scale) {
        Objects.requireNonNull(value, "value must not be null");
        requireValidScale(scale);
        return value.setScale(scale, COBOL_ROUNDING);
    }

    /**
     * Stores {@code value} into a monetary receiving field, that is, one of scale {@link #MONETARY_SCALE}.
     *
     * @param value the computed value to store; must not be {@code null}
     * @return {@code value} truncated to scale 2
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static BigDecimal storeMonetary(BigDecimal value) {
        return store(value, MONETARY_SCALE);
    }

    /**
     * Stores {@code value} into a receiving field declared as {@code PIC S9(integerDigits)V(scale)},
     * reproducing COBOL's silent truncation at both ends.
     *
     * @param value the computed value to store; must not be {@code null}
     * @param integerDigits the count of digit positions to the left of the implied decimal point, that is,
     *     {@code p} in {@code PIC S9(p)V...}; must not be negative
     * @param scale the count of digit positions to the right of the implied decimal point; must not be
     *     negative
     * @return {@code value} truncated to {@code scale} and reduced to at most {@code integerDigits} integer
     *     digits, whose {@link BigDecimal#scale()} is exactly {@code scale}
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code integerDigits} or {@code scale} is negative
     */
    public static BigDecimal storeAtPicture(BigDecimal value, int integerDigits, int scale) {
        Objects.requireNonNull(value, "value must not be null");
        requireValidIntegerDigits(integerDigits);
        requireValidScale(scale);

        BigDecimal fractionTruncated = value.setScale(scale, COBOL_ROUNDING);

        int presentIntegerDigits = fractionTruncated.precision() - fractionTruncated.scale();
        if (presentIntegerDigits <= integerDigits) {
            return fractionTruncated;
        }

        BigDecimal modulus = BigDecimal.TEN.pow(integerDigits);
        return fractionTruncated.remainder(modulus).setScale(scale, COBOL_ROUNDING);
    }

    /**
     * Returns zero at the given scale, for translating {@code MOVE 0} into a scaled field.
     *
     * @param scale the receiving field's declared scale; must not be negative
     * @return zero, with {@link BigDecimal#scale()} equal to {@code scale}
     * @throws IllegalArgumentException if {@code scale} is negative
     */
    public static BigDecimal zero(int scale) {
        requireValidScale(scale);
        return BigDecimal.ZERO.setScale(scale, COBOL_ROUNDING);
    }

    /**
     * Returns {@code 0.00}: zero at {@link #MONETARY_SCALE}, the scale every scaled field in this system
     * uses.
     *
     * @return zero at scale 2
     */
    public static BigDecimal monetaryZero() {
        return zero(MONETARY_SCALE);
    }

    /**
     * Adds two values exactly and stores the sum into a receiving field of the given scale, reproducing the
     * COBOL {@code ADD} verb.
     *
     * @param augend the running value being added to, the COBOL receiver's prior contents; must not be
     *     {@code null}
     * @param addend the value being added; must not be {@code null}
     * @param scale the receiving field's declared scale; must not be negative
     * @return the sum, truncated to {@code scale}
     * @throws NullPointerException if either operand is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative
     */
    public static BigDecimal add(BigDecimal augend, BigDecimal addend, int scale) {
        Objects.requireNonNull(augend, "augend must not be null");
        Objects.requireNonNull(addend, "addend must not be null");
        requireValidScale(scale);
        return augend.add(addend).setScale(scale, COBOL_ROUNDING);
    }

    /**
     * Subtracts one value from another exactly and stores the difference into a receiving field of the
     * given scale, reproducing the COBOL {@code SUBTRACT} verb and subtraction inside {@code COMPUTE}.
     *
     * @param minuend the value being reduced, the COBOL receiver's prior contents; must not be {@code null}
     * @param subtrahend the value being taken away; must not be {@code null}
     * @param scale the receiving field's declared scale; must not be negative
     * @return the difference, truncated to {@code scale}
     * @throws NullPointerException if either operand is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative
     */
    public static BigDecimal subtract(BigDecimal minuend, BigDecimal subtrahend, int scale) {
        Objects.requireNonNull(minuend, "minuend must not be null");
        Objects.requireNonNull(subtrahend, "subtrahend must not be null");
        requireValidScale(scale);
        return minuend.subtract(subtrahend).setScale(scale, COBOL_ROUNDING);
    }

    /**
     * Multiplies two values exactly and stores the product into a receiving field of the given scale.
     *
     * <p>Truncation happens once, on the store, which is where COBOL applies it too.
     *
     * @param multiplicand the first factor; must not be {@code null}
     * @param multiplier the second factor; must not be {@code null}
     * @param scale the receiving field's declared scale; must not be negative
     * @return the product, truncated to {@code scale}
     * @throws NullPointerException if either factor is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative
     */
    public static BigDecimal multiplyAndStore(BigDecimal multiplicand, BigDecimal multiplier,
            int scale) {
        Objects.requireNonNull(multiplicand, "multiplicand must not be null");
        Objects.requireNonNull(multiplier, "multiplier must not be null");
        requireValidScale(scale);
        return multiplicand.multiply(multiplier).setScale(scale, COBOL_ROUNDING);
    }

    /**
     * Divides one value by another to the given scale, truncating the quotient.
     *
     * <p>The rounding mode is fixed to {@link #COBOL_ROUNDING} and cannot be overridden by a caller, which
     * is what makes it impossible to introduce a non-COBOL rounding mode into a monetary path through this
     * method.
     *
     * @param dividend the value being divided; must not be {@code null}
     * @param divisor the value to divide by; must not be {@code null} and must not be zero at any scale
     * @param scale the receiving field's declared scale; must not be negative
     * @return the quotient, truncated to {@code scale}
     * @throws NullPointerException if either operand is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative
     * @throws ArithmeticException if {@code divisor} is zero
     */
    public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor, int scale) {
        Objects.requireNonNull(dividend, "dividend must not be null");
        Objects.requireNonNull(divisor, "divisor must not be null");
        requireValidScale(scale);
        if (divisor.signum() == 0) {
            throw new ArithmeticException(
                    "COBOL divide by zero: divisor is zero, dividend was " + dividend);
        }
        return dividend.divide(divisor, scale, COBOL_ROUNDING);
    }

    /**
     * Multiplies two values exactly, then divides the product by an integer divisor into a receiving field
     * of the given scale.
     *
     * <p>Truncating the product first would discard digits the COBOL still had available when it divided,
     * and would produce a different answer.
     *
     * @param multiplicand the first factor; must not be {@code null}
     * @param multiplier the second factor; must not be {@code null}
     * @param divisor the integer literal to divide the exact product by; must not be zero
     * @param scale the receiving field's declared scale; must not be negative
     * @return the quotient of the exact product and {@code divisor}, truncated to {@code scale}
     * @throws NullPointerException if either factor is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative
     * @throws ArithmeticException if {@code divisor} is zero
     */
    public static BigDecimal multiplyThenDivide(BigDecimal multiplicand, BigDecimal multiplier,
            long divisor, int scale) {
        Objects.requireNonNull(multiplicand, "multiplicand must not be null");
        Objects.requireNonNull(multiplier, "multiplier must not be null");
        BigDecimal exactProduct = multiplicand.multiply(multiplier);
        return divide(exactProduct, BigDecimal.valueOf(divisor), scale);
    }

    /**
     * Computes one month's interest on a transaction category balance, reproducing
     * {@code app/cbl/CBACT04C.cbl:L464-L465} exactly.
     *
     * @param transactionCategoryBalance {@code TRAN-CAT-BAL}, the category balance to charge interest on;
     *     must not be {@code null}
     * @param disclosureInterestRate {@code DIS-INT-RATE}, the annual rate as a percentage, so {@code 12.50}
     *     means twelve and a half percent; must not be {@code null}
     * @return the monthly interest, truncated to {@link #MONETARY_SCALE}
     * @throws NullPointerException if either operand is {@code null}
     */
    public static BigDecimal monthlyInterest(BigDecimal transactionCategoryBalance,
            BigDecimal disclosureInterestRate) {
        return multiplyThenDivide(transactionCategoryBalance, disclosureInterestRate,
                MONTHLY_INTEREST_DIVISOR, MONETARY_SCALE);
    }

    private static void requireValidScale(int scale) {
        if (scale < 0) {
            throw new IllegalArgumentException(
                    "scale must not be negative, but was " + scale
                            + "; a COBOL PICTURE clause never declares a negative scale");
        }
    }

    private static void requireValidIntegerDigits(int integerDigits) {
        if (integerDigits < 0) {
            throw new IllegalArgumentException(
                    "integerDigits must not be negative, but was " + integerDigits
                            + "; a COBOL PICTURE clause never declares negative precision");
        }
    }
}
