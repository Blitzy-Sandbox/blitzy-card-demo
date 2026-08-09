package com.vsergeychik.carddemo.common;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The single home of the COBOL numeric-conversion intrinsics for this migration:
 * {@code FUNCTION NUMVAL}, {@code FUNCTION NUMVAL-C} and their conformance companions
 * {@code FUNCTION TEST-NUMVAL} and {@code FUNCTION TEST-NUMVAL-C}.
 *
 * <p>Like {@link CobolDecimal}, this is a foundation type: it depends on nothing inside
 * {@code com.vsergeychik.carddemo} and every program that converts a screen field or a report
 * operand to a number depends on it. Gate G29 requires that these intrinsics "accept and reject
 * exactly as COBOL does", and a rule of that kind can only be held if it is implemented once. Four
 * programs in this estate call them, and before this class existed each carried its own scanner:
 *
 * <ul>
 *   <li>{@code CSUTLDPY} L126, L128, L170 and L172 - unsuffixed {@code NUMVAL} on a month and a day,
 *       reached through {@code COACTUPC}</li>
 *   <li>{@code COACTUPC} - {@code NUMVAL-C} on the monetary operands</li>
 *   <li>{@code COTRN02C} L204 and L218 - {@code NUMVAL}; L383 and L456 - {@code NUMVAL-C}</li>
 *   <li>{@code CORPT00C} L305, L309, L313, L317, L321 and L325 - {@code NUMVAL-C}</li>
 * </ul>
 *
 * <h2>The two grammars are not the same grammar</h2>
 *
 * <p>This is the substantive reason the duplication mattered rather than merely being untidy. The
 * two intrinsics admit <em>different</em> argument syntax, and the difference is easy to lose when
 * the scanner is written four times:
 *
 * <ul>
 *   <li>{@code NUMVAL}'s argument admits only spaces, an optional sign, the digits {@code 0}
 *       through {@code 9}, at most one decimal point, and a trailing {@code +}, {@code -},
 *       {@code CR} or {@code DB}. It admits <strong>no currency sign and no grouping comma.</strong></li>
 *   <li>{@code NUMVAL-C}'s argument admits all of that <em>and additionally</em> a currency sign and
 *       grouping commas within the integer part.</li>
 * </ul>
 *
 * <p>Both admit {@code CR} and {@code DB}. That is worth stating explicitly because it is the half
 * of the difference that is tempting to "tidy away": a credit indicator looks like a currency-ish
 * notion and therefore looks as though it might belong only to {@code NUMVAL-C}. It does not.
 * Removing it from {@code NUMVAL} would itself be a parity defect.
 *
 * <h2>The digit limit is the compiler's, not this class's</h2>
 *
 * <p>The argument of either intrinsic may not contain more digits than the arithmetic mode allows:
 * {@value #MAXIMUM_DIGITS} under {@code ARITH(COMPAT)}, or 31 under {@code ARITH(EXTEND)}. Which
 * applies is a compile-time property of the program, and it was determined by search rather than
 * assumed: there is no {@code ARITH} option, no {@code PROCESS} statement and no {@code CBL} card
 * anywhere in {@code app/cbl}, {@code app/jcl}, {@code app/proc} or {@code samples}. The default
 * therefore governs, which is {@code ARITH(COMPAT)} and hence {@value #MAXIMUM_DIGITS} digits.
 *
 * <p>Every field these intrinsics are applied to in this estate is far narrower than that - a
 * two-character month, a twelve-character amount - so the limit is unreachable through any of the
 * four callers. It is enforced anyway, because "unreachable today" is a property of the current
 * screen definitions rather than of the intrinsic, and an intrinsic that silently accepted a
 * twenty-five-digit operand would be wrong about the language.
 *
 * <h2>What a non-conforming argument yields</h2>
 *
 * <p>COBOL leaves the value of a non-conforming argument <em>undefined</em>. This class defines it,
 * once, so that no caller has to: the value is {@link #NON_CONFORMING_VALUE} and the reported
 * position is non-zero. Choosing zero is safe rather than arbitrary, because it is unreachable in
 * every one of the four callers - each tests conformance first and takes its own error branch - so
 * the choice is observable only to this class's own tests. What matters is that it is stated in one
 * place instead of being separately re-invented at each call site, which is what makes the value
 * and the verdict incapable of disagreeing.
 *
 * <h2>Position reporting</h2>
 *
 * <p>{@code TEST-NUMVAL} and {@code TEST-NUMVAL-C} return {@value #CONFORMS} for a conforming
 * argument, and otherwise the <em>one-based</em> position of the first character in error. An
 * argument holding no digit at all - all spaces, a bare sign, a bare currency sign or a bare
 * decimal point - is reported at the argument's length plus one, because there is no offending
 * character to point at. Both conventions are IBM's, and both are relied on by
 * {@code CSUTLDPY}, which moves the reported position into a message.
 *
 * <h2>Design invariants</h2>
 *
 * <ul>
 *   <li>One pass produces both the value and the verdict, so they can never disagree. The source
 *       always calls the pair together, and a second scan could drift from the first.</li>
 *   <li>{@link BigDecimal} throughout, never {@code double} or {@code float} - rule R4 and gate
 *       G22. A fractional argument keeps every digit it was given.</li>
 *   <li>Stateless and immutable: no instance exists and no static field is mutable, so there is
 *       nothing to synchronise and nothing to reset between tests.</li>
 *   <li>{@code null} is refused rather than read as zero. A COBOL item is never absent, so a
 *       {@code null} here is a Java defect and is reported as one.</li>
 * </ul>
 */
public final class NumericIntrinsics {

    /**
     * The position {@link #testNumval(String)} and {@link #testNumvalC(String)} report when the
     * argument conforms.
     *
     * <p>Zero is not a valid one-based character position, which is exactly why the intrinsic can use
     * it as the "no error" answer without ambiguity.
     */
    public static final int CONFORMS = 0;

    /**
     * The greatest number of digits either intrinsic's argument may contain, under the
     * {@code ARITH(COMPAT)} arithmetic mode this estate compiles with.
     *
     * <p>{@code ARITH(EXTEND)} would raise this to 31, but no source file in the repository selects
     * it - see the class documentation.
     */
    public static final int MAXIMUM_DIGITS = 18;

    /**
     * The value returned for an argument that does not conform.
     *
     * <p>COBOL leaves this undefined; this class defines it. See the class documentation for why
     * zero is a safe choice rather than a lossy one.
     */
    public static final BigDecimal NON_CONFORMING_VALUE = BigDecimal.ZERO;

    /** A space, permitted at several places in either argument. */
    private static final char SPACE = ' ';

    /** The decimal point; at most one may appear among the digits. */
    private static final char DECIMAL_POINT = '.';

    /** The grouping separator - {@code NUMVAL-C} only, and only between integer digits. */
    private static final char DIGIT_SEPARATOR = ',';

    /** The currency sign - {@code NUMVAL-C} only. */
    private static final char CURRENCY_SIGN = '$';

    /** A leading or trailing plus. */
    private static final char PLUS_SIGN = '+';

    /** A leading or trailing minus. */
    private static final char MINUS_SIGN = '-';

    /** The trailing credit indicator, matched in upper case only as the standard defines it. */
    private static final String CREDIT_INDICATOR = "CR";

    /** The trailing debit indicator, matched in upper case only as the standard defines it. */
    private static final String DEBIT_INDICATOR = "DB";

    /**
     * Never instantiated: this is a set of intrinsic functions, not an object with state.
     */
    private NumericIntrinsics() {
        throw new AssertionError("NumericIntrinsics is a holder for the COBOL conversion intrinsics "
                + "and is never instantiated");
    }

    /**
     * {@code FUNCTION NUMVAL} - the value of a character representation of a number.
     *
     * @param image the argument to convert; must not be {@code null}
     * @return the value the argument denotes, or {@link #NON_CONFORMING_VALUE} when it does not
     *         conform
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static BigDecimal numval(String image) {
        return scanNumval(image).value();
    }

    /**
     * {@code FUNCTION TEST-NUMVAL} - whether an argument is a valid operand of
     * {@link #numval(String)}.
     *
     * @param image the argument to test; must not be {@code null}
     * @return {@value #CONFORMS} when the argument conforms; otherwise the one-based position of the
     *         first character in error, or the argument's length plus one when it holds no digit
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static int testNumval(String image) {
        return scanNumval(image).errorPosition();
    }

    /**
     * {@code FUNCTION NUMVAL-C} - as {@link #numval(String)}, but the argument may also carry a
     * currency sign and grouping commas.
     *
     * @param image the argument to convert; must not be {@code null}
     * @return the value the argument denotes, or {@link #NON_CONFORMING_VALUE} when it does not
     *         conform
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static BigDecimal numvalC(String image) {
        return scanNumvalC(image).value();
    }

    /**
     * {@code FUNCTION TEST-NUMVAL-C} - whether an argument is a valid operand of
     * {@link #numvalC(String)}.
     *
     * @param image the argument to test; must not be {@code null}
     * @return {@value #CONFORMS} when the argument conforms; otherwise the one-based position of the
     *         first character in error, or the argument's length plus one when it holds no digit
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static int testNumvalC(String image) {
        return scanNumvalC(image).errorPosition();
    }

    /**
     * One {@code NUMVAL} pass, yielding the value and the verdict together.
     *
     * <p>Callers that need both - {@code CSUTLDPY} tests conformance and then converts - should use
     * this rather than calling {@link #numval(String)} and {@link #testNumval(String)} in
     * succession, so that one scan answers both questions.
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

    /**
     * The one scanner behind all six accessors above.
     *
     * <p>The accepted form is: a run of spaces; then either a leading sign or nothing; then - for
     * {@code NUMVAL-C} only - a currency sign optionally followed by spaces; then digits with at
     * most one decimal point among them, and - for {@code NUMVAL-C} only - grouping commas between
     * integer digits; then trailing spaces; then, only if no leading sign was given, a trailing
     * {@code +}, {@code -}, {@code CR} or {@code DB}; then trailing spaces; then the end of the
     * item.
     *
     * <p>The single {@code currencyForm} flag governs <em>both</em> extensions, because they arrive
     * together: {@code NUMVAL-C} admits the currency sign and the grouping comma, and {@code NUMVAL}
     * admits neither. Threading one flag rather than two keeps it impossible to configure a grammar
     * that no intrinsic actually has.
     *
     * @param image        the argument, already checked for {@code null}
     * @param currencyForm {@code true} for {@code NUMVAL-C}, {@code false} for {@code NUMVAL}
     * @return a conforming scan carrying the value, or a rejection carrying the position
     */
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

        // The currency sign contributes no digit and may be followed by spaces. NUMVAL has no
        // currency-sign position at all, so under that grammar a '$' here is simply the first
        // character in error and falls through to the digit loop, which stops on it.
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
                    // One digit more than the arithmetic mode allows. Reported at this digit's own
                    // position, which is the first character that makes the argument invalid.
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
                // A grouping comma: NUMVAL-C only, permitted between digits of the integer part
                // only, and contributing no digit of its own.
                index++;
            } else {
                break;
            }
        }

        if (digits.length() == 0) {
            // No digit anywhere: all spaces, a bare sign, a bare currency sign or a bare decimal
            // point. Reported at the length plus one, because there is no character to point at.
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
                // CR and DB both denote a credit, and both belong to NUMVAL as well as NUMVAL-C.
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

    /**
     * Advances past a run of spaces.
     *
     * @param image the argument
     * @param from  the 0-based index to start at
     * @return the 0-based index of the first character at or after {@code from} that is not a space,
     *         or the argument's length when none is
     */
    private static int skipSpaces(String image, int from) {
        int index = from;
        while (index < image.length() && image.charAt(index) == SPACE) {
            index++;
        }
        return index;
    }

    /**
     * Whether a character is one of the ten COBOL digits.
     *
     * <p>Deliberately not {@link Character#isDigit(char)}, which accepts the decimal digits of every
     * Unicode script. A COBOL {@code PIC 9} position holds one of exactly ten characters, and a
     * Devanagari digit is not one of them.
     *
     * @param character the character to test
     * @return {@code true} for {@code '0'} through {@code '9'} only
     */
    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    /**
     * Whether a character is an operational sign.
     *
     * @param character the character to test
     * @return {@code true} for {@code '+'} or {@code '-'}
     */
    private static boolean isSign(char character) {
        return character == PLUS_SIGN || character == MINUS_SIGN;
    }

    /**
     * The outcome of one intrinsic scan: the converted value and the conformance verdict together.
     *
     * <p>The pair is returned as one object so that a caller cannot obtain a value from one scan and
     * a verdict from another. That is not a theoretical concern: the value of a non-conforming
     * argument is defined here as zero, and zero is also a perfectly ordinary conforming value, so
     * the verdict is the only thing that distinguishes {@code "0"} from {@code "abc"}.
     *
     * @param value         the converted value, or {@link #NON_CONFORMING_VALUE} when
     *                      {@code errorPosition} is not {@value #CONFORMS}
     * @param errorPosition {@value #CONFORMS} when the argument conforms; otherwise the one-based
     *                      position of the first character in error, or the argument's length plus
     *                      one when it holds no digit
     */
    public record Scan(BigDecimal value, int errorPosition) {

        /**
         * Checks the pair's own invariants, so a malformed scan cannot be constructed.
         *
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if {@code errorPosition} is negative
         */
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

        /**
         * A conforming outcome.
         *
         * @param value the converted value; must not be {@code null}
         * @return a scan carrying {@code value} and {@value #CONFORMS}
         */
        static Scan accepted(BigDecimal value) {
            return new Scan(value, CONFORMS);
        }

        /**
         * A non-conforming outcome.
         *
         * @param position the one-based position of the first character in error, or the argument's
         *                 length plus one when it holds no digit
         * @return a scan carrying {@link #NON_CONFORMING_VALUE} and {@code position}
         */
        static Scan rejected(int position) {
            return new Scan(NON_CONFORMING_VALUE, position);
        }
    }
}
