package com.vsergeychik.carddemo.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The single home of numeric parity for the CardDemo COBOL to Java 21 migration.
 *
 * <p>This is the deepest foundation type in the module: it depends on nothing inside
 * {@code com.vsergeychik.carddemo} and almost everything monetary depends on it. Every fixed-point
 * arithmetic operation and every "store into a receiving field" in the translated code routes
 * through here, so that numeric behaviour has exactly one place to be reviewed, one place to be
 * tested, and one place to be got wrong.
 *
 * <h2>Why this class exists: three properties of the legacy source</h2>
 *
 * <p>Three facts about the 28 COBOL programs in {@code app/cbl} were established by exhaustive
 * search of the source in this repository. Together they fully determine the behaviour implemented
 * below, and each is unusual enough that a Java engineer reaching for familiar defaults would get
 * it wrong.
 *
 * <ol>
 *   <li><b>The keyword {@code ROUNDED} appears zero times.</b> Not once, in any of the 28
 *       programs, nor in any of the 28 copybooks. COBOL only rounds when a statement explicitly
 *       says {@code ROUNDED}; absent it, excess fractional digits are <em>truncated</em> when the
 *       result is stored. {@link RoundingMode#DOWN} is therefore the only faithful mode, and it is
 *       correct for both signs because these are signed {@code S9(...)} fields and {@code DOWN}
 *       truncates toward zero. Note in particular that {@link RoundingMode#FLOOR} is <em>not</em>
 *       equivalent: storing {@code -1.239} at scale 2 must yield {@code -1.23}, whereas
 *       {@code FLOOR} would yield {@code -1.24}.</li>
 *   <li><b>The phrase {@code ON SIZE ERROR} appears zero times.</b> A COBOL store into a
 *       {@code PIC S9(p)V99} receiver therefore truncates at <em>both</em> ends and reports
 *       nothing: excess low-order fractional digits are dropped, and excess high-order integer
 *       digits are dropped silently, the receiver keeping only its low-order {@code p} integer
 *       digits together with the sign of the computed value. Both halves of that behaviour are
 *       modelled here; see {@link #storeAtPicture(BigDecimal, int, int)}. A helper that only
 *       adjusted the scale would be incomplete and would diverge from the COBOL on overflow.</li>
 *   <li><b>Every scaled numeric in the system has scale exactly 2.</b> A scan of the
 *       {@code PICTURE} V-scales across {@code app/cbl} and {@code app/cpy} returns only
 *       {@code V99}, in exactly 34 occurrences. There is no {@code V9} and no {@code V999}. The
 *       <em>scale</em> is thus invariant, while the <em>integer precision</em> varies: the signed
 *       decimal forms present are {@code S9(10)V99}, {@code S9(09)V99}, {@code S9(9)V99} and
 *       {@code S9(04)V99}. That is precisely why
 *       {@link #storeAtPicture(BigDecimal, int, int)} takes the integer precision as a parameter
 *       instead of assuming a width.</li>
 * </ol>
 *
 * <p>The short form worth carrying: because {@code ROUNDED} and {@code ON SIZE ERROR} are
 * <em>both</em> entirely absent, this system's numeric behaviour is uniformly "truncate silently at
 * both ends".
 *
 * <h2>Correction of a superseded design document</h2>
 *
 * <p>{@code docs/technical-specifications.md} asserts {@link RoundingMode#HALF_EVEN} for the
 * interest calculation. That assertion is <b>wrong</b> for this codebase, for the reason given in
 * point 1 above, and this class supersedes it. That document describes a materially different and
 * non-authoritative design and is deliberately left unmodified rather than silently corrected.
 *
 * <h2>Design invariants</h2>
 *
 * <ul>
 *   <li><b>No operation accepts a {@link RoundingMode}.</b> Every method rounds with
 *       {@link #COBOL_ROUNDING} and nothing else. This is a deliberate parity guarantee rather
 *       than an omission: a caller cannot introduce {@code HALF_UP}, {@code HALF_EVEN},
 *       {@code CEILING} or {@code FLOOR} into a monetary path even by accident, and the rounding
 *       behaviour of all 28 translated programs can be audited by reading this one file.</li>
 *   <li><b>No {@code double} and no {@code float}, anywhere.</b> Not in a signature, not in a
 *       field, not in a local, not in a literal, and not through
 *       {@code BigDecimal.valueOf(double)}. Binary floating point cannot represent decimal
 *       fractions exactly, so a single such value would silently corrupt a monetary figure.
 *       Construct values from {@code String} or from {@code long}.</li>
 *   <li><b>No division without an explicit scale.</b> The no-argument
 *       {@code BigDecimal.divide(BigDecimal)} throws {@link ArithmeticException} whenever the
 *       quotient does not terminate, which for real interest inputs is the common case rather than
 *       the exception. Every division here supplies both a target scale and a rounding mode.</li>
 *   <li><b>Stateless, immutable and non-instantiable.</b> Only {@code static final}
 *       deeply-immutable constants and pure static methods; no caches, no memo tables, no mutable
 *       arrays, no static mutable state of any kind. {@link BigDecimal} and {@link RoundingMode}
 *       are themselves immutable, so the constants are safe to publish. Every method is therefore
 *       thread-safe and free of shared state; COBOL {@code WORKING-STORAGE} must never become a
 *       static Java field.</li>
 *   <li><b>No framework coupling.</b> This type carries no annotations and imports nothing beyond
 *       {@link BigDecimal}, {@link RoundingMode} and {@link Objects}. It is usable from a plain
 *       unit test with no application context.</li>
 * </ul>
 *
 * <h2>Governing standards</h2>
 *
 * <p>No user-specified rules were provided for this project, so no project rule governs this file.
 * Its absence is not treated as licence to lower the bar: the enterprise best practices recorded in
 * the plan bind instead, and the ones that shape this file are explicit naming of scale and
 * rounding at every call site, no static mutable state, documenting rather than silently repairing
 * discrepancies, determinism with no locale- or environment-dependent behaviour, and tests authored
 * alongside the code.
 *
 * <p><b>A recorded observation, not a correction.</b> A raw scan of the signed decimal
 * {@code PICTURE} forms yields occurrence counts of 20, 10, 3 and 1 for {@code S9(10)V99},
 * {@code S9(09)V99}, {@code S9(9)V99} and {@code S9(04)V99}, totalling the same 34 as the
 * {@code V99} scan. The migration plan records lower per-form figures because it counts code
 * occurrences only, excluding comment lines. The discrepancy is noted here and deliberately left
 * alone; it does not affect the load-bearing invariant, which is that the scale is always 2 while
 * the integer precision varies.
 *
 * @see #monthlyInterest(BigDecimal, BigDecimal) the interest formula this class exists to protect
 */
public final class CobolDecimal {

    /**
     * The scale of every scaled numeric field in the CardDemo data model: exactly {@code 2}.
     *
     * <p>Justified by the {@code PICTURE} V-scale scan across {@code app/cbl} and {@code app/cpy},
     * which returns only {@code V99} and does so in exactly 34 occurrences. There is no
     * {@code V9}, no {@code V999} and no unscaled-decimal variant anywhere in the system, so every
     * monetary amount, balance, credit limit and interest rate carries two decimal places.
     *
     * <p>Prefer this constant to a bare literal {@code 2} at call sites, so that the reason for the
     * value stays attached to its use.
     */
    public static final int MONETARY_SCALE = 2;

    /**
     * The only rounding mode permitted in a monetary path: {@link RoundingMode#DOWN}, which
     * truncates toward zero.
     *
     * <p>The keyword {@code ROUNDED} appears zero times across all 28 COBOL programs and all 28
     * copybooks. COBOL rounds only where a statement says so explicitly, so every store in this
     * system truncates its excess fractional digits. {@code DOWN} reproduces that for both signs,
     * which matters because the fields are signed.
     *
     * <p>{@code HALF_UP}, {@code HALF_EVEN}, {@code CEILING}, {@code FLOOR}, {@code UP},
     * {@code HALF_DOWN} and {@code UNNECESSARY} are all incorrect here and appear nowhere in this
     * class. Every rounding decision in the module is expressed through this one named constant,
     * so the rule can be verified by searching for a single identifier rather than by auditing 28
     * programs.
     */
    public static final RoundingMode COBOL_ROUNDING = RoundingMode.DOWN;

    /**
     * The divisor in the monthly interest formula: the integer literal {@code 1200} written in
     * {@code app/cbl/CBACT04C.cbl:L465}.
     *
     * <p>It converts an annual percentage rate into a monthly fraction in a single step: twelve
     * months multiplied by the hundred that turns a percentage into a fraction. The COBOL performs
     * exactly one division in the entire system and this is its divisor.
     *
     * @see #monthlyInterest(BigDecimal, BigDecimal)
     */
    public static final long MONTHLY_INTEREST_DIVISOR = 1200L;

    /**
     * Prevents instantiation and, being private, subclassing as well. This type is a stateless
     * collection of pure static operations; an instance would carry no meaning.
     *
     * @throws AssertionError always, including when invoked reflectively
     */
    private CobolDecimal() {
        throw new AssertionError("CobolDecimal is a non-instantiable utility holder");
    }

    /**
     * Stores {@code value} into a receiving field of the given scale, truncating excess fractional
     * digits exactly as COBOL does.
     *
     * <p>This is the primitive seam that the whole class exists to centralise: it is the one place
     * {@code setScale(scale, RoundingMode.DOWN)} is written. Excess fractional digits are dropped
     * rather than rounded, because {@code ROUNDED} appears zero times in the source. Truncation is
     * toward zero, so {@code 1.239} becomes {@code 1.23} and {@code -1.239} becomes {@code -1.23}.
     *
     * <p>Scaling <em>up</em> is exact and always safe: a value of smaller scale is padded with
     * trailing zeros, which is what gives a fixed-width writer the digits it needs.
     *
     * <p>This operation adjusts the fraction only. It does not bound the integer part; use
     * {@link #storeAtPicture(BigDecimal, int, int)} where the receiver's integer precision matters.
     *
     * @param value the computed value to store; must not be {@code null}
     * @param scale the receiving field's declared scale, taken from its {@code PICTURE} clause;
     *              must not be negative
     * @return {@code value} truncated to {@code scale}, whose {@link BigDecimal#scale()} is exactly
     *         {@code scale}
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative
     */
    public static BigDecimal store(BigDecimal value, int scale) {
        Objects.requireNonNull(value, "value must not be null");
        requireValidScale(scale);
        return value.setScale(scale, COBOL_ROUNDING);
    }

    /**
     * Stores {@code value} into a monetary receiving field, that is, one of scale
     * {@link #MONETARY_SCALE}.
     *
     * <p>Every scaled numeric in this system has scale 2, so this covers the overwhelming majority
     * of stores. It serves, among others, {@code MOVE WS-MONTHLY-INT TO TRAN-AMT} at
     * {@code app/cbl/CBACT04C.cbl:L490}, where sender and receiver are both {@code S9(09)V99} and
     * no truncation occurs.
     *
     * @param value the computed value to store; must not be {@code null}
     * @return {@code value} truncated to scale 2
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static BigDecimal storeMonetary(BigDecimal value) {
        return store(value, MONETARY_SCALE);
    }

    /**
     * Stores {@code value} into a receiving field declared as
     * {@code PIC S9(integerDigits)V(scale)}, reproducing COBOL's silent truncation at <em>both</em>
     * ends.
     *
     * <p>Two things happen, in this order:
     * <ol>
     *   <li>excess fractional digits are truncated toward zero to {@code scale}, because
     *       {@code ROUNDED} is absent from the source; then</li>
     *   <li>integer digits beyond {@code integerDigits} are discarded, the receiver keeping its
     *       low-order {@code integerDigits} digits and the sign of the computed value, because
     *       {@code ON SIZE ERROR} is absent from the source.</li>
     * </ol>
     *
     * <p>The second step is what distinguishes this operation from {@link #store}. COBOL does not
     * raise a condition on high-order overflow unless the program asks for one with
     * {@code ON SIZE ERROR}, and no program here does, so an oversized result is quietly wrapped
     * instead of reported. <b>This method therefore never throws on overflow.</b> Discarding the
     * high-order digits is implemented as a remainder by {@code 10} raised to
     * {@code integerDigits}, which keeps the low-order digits and takes its sign from the dividend
     * exactly as the receiving field does. When the value already fits, it is returned with its
     * fraction truncated and nothing else changed.
     *
     * <p>Worked example: storing {@code 12345678901.23} into an {@code S9(10)V99} field yields
     * {@code 2345678901.23}, and storing {@code -12345678901.23} yields {@code -2345678901.23}.
     *
     * @param value         the computed value to store; must not be {@code null}
     * @param integerDigits the count of digit positions to the left of the implied decimal point,
     *                      that is, {@code p} in {@code PIC S9(p)V...}; must not be negative. Zero
     *                      is meaningful and denotes a receiver such as {@code PIC SV99}, which
     *                      retains only the fraction
     * @param scale         the count of digit positions to the right of the implied decimal point;
     *                      must not be negative
     * @return {@code value} truncated to {@code scale} and reduced to at most
     *         {@code integerDigits} integer digits, whose {@link BigDecimal#scale()} is exactly
     *         {@code scale}
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code integerDigits} or {@code scale} is negative
     */
    public static BigDecimal storeAtPicture(BigDecimal value, int integerDigits, int scale) {
        Objects.requireNonNull(value, "value must not be null");
        requireValidIntegerDigits(integerDigits);
        requireValidScale(scale);

        BigDecimal fractionTruncated = value.setScale(scale, COBOL_ROUNDING);

        // BigDecimal.precision() counts significant digits and ignores the sign, so subtracting the
        // scale yields the number of digit positions left of the decimal point. The result can be
        // zero or negative for values below one - for instance 0.41 has precision 2 and scale 2 -
        // and such values trivially fit any non-negative receiver.
        int presentIntegerDigits = fractionTruncated.precision() - fractionTruncated.scale();
        if (presentIntegerDigits <= integerDigits) {
            return fractionTruncated;
        }

        // Reached only when the value genuinely overflows the receiver, which also bounds the
        // exponent below: integerDigits is strictly less than the digit count of a real value here,
        // so the power of ten computed next is always small.
        BigDecimal modulus = BigDecimal.TEN.pow(integerDigits);
        return fractionTruncated.remainder(modulus).setScale(scale, COBOL_ROUNDING);
    }

    /**
     * Returns zero at the given scale, for translating {@code MOVE 0} into a scaled field.
     *
     * <p>{@link BigDecimal#ZERO} has scale 0 and renders as {@code "0"}. A scale-0 zero written
     * into a scale-2 field produces the wrong fixed-width byte image, so a {@code MOVE 0} must
     * produce {@code 0.00} rather than {@code 0}. This factory exists so that no caller has to
     * remember that.
     *
     * <p>It serves three {@code MOVE 0} statements in the interest calculator, all of them into
     * scale-2 fields: the per-account accumulator reset {@code MOVE 0 TO WS-TOTAL-INT} at
     * {@code app/cbl/CBACT04C.cbl:L200}, and the two cycle-amount resets
     * {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} and {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} at
     * {@code app/cbl/CBACT04C.cbl:L353} and {@code app/cbl/CBACT04C.cbl:L354}.
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
     * Returns {@code 0.00}: zero at {@link #MONETARY_SCALE}, the scale every scaled field in this
     * system uses.
     *
     * <p>The returned value renders as {@code "0.00"} and reports a scale of 2. Note that it is
     * <em>not</em> {@link Object#equals equal} to {@link BigDecimal#ZERO}, because
     * {@link BigDecimal#equals(Object)} compares scale as well as value; use
     * {@link BigDecimal#compareTo(BigDecimal)} or {@link BigDecimal#signum()} to test a
     * {@code BigDecimal} for zero.
     *
     * @return zero at scale 2
     */
    public static BigDecimal monetaryZero() {
        return zero(MONETARY_SCALE);
    }

    /**
     * Adds two values exactly and stores the sum into a receiving field of the given scale,
     * reproducing the COBOL {@code ADD} verb.
     *
     * <p>{@link BigDecimal#add(BigDecimal)} is exact, so the only truncation is the final store.
     * There are 51 {@code ADD} sites across the 28 programs. Two of them matter most: the interest
     * accumulator {@code ADD WS-MONTHLY-INT TO WS-TOTAL-INT} at
     * {@code app/cbl/CBACT04C.cbl:L467}, and the account-break posting
     * {@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL} at {@code app/cbl/CBACT04C.cbl:L352}, which is the
     * first of the four
     * ordered steps that close out an account.
     *
     * <p>Because every scaled field here has scale 2, adding two scale-2 values is exact at scale 2
     * and the store changes nothing. Passing the receiver's scale explicitly is still required, so
     * that the receiving field's declared shape is visible at the call site rather than inferred
     * from whichever operand happened to be widest.
     *
     * @param augend the running value being added to, the COBOL receiver's prior contents; must not
     *               be {@code null}
     * @param addend the value being added; must not be {@code null}
     * @param scale  the receiving field's declared scale; must not be negative
     * @return the sum, truncated to {@code scale}
     * @throws NullPointerException     if either operand is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative
     */
    public static BigDecimal add(BigDecimal augend, BigDecimal addend, int scale) {
        Objects.requireNonNull(augend, "augend must not be null");
        Objects.requireNonNull(addend, "addend must not be null");
        requireValidScale(scale);
        return augend.add(addend).setScale(scale, COBOL_ROUNDING);
    }

    /**
     * Subtracts one value from another exactly and stores the difference into a receiving field of
     * the given scale, reproducing the COBOL {@code SUBTRACT} verb and subtraction inside
     * {@code COMPUTE}.
     *
     * <p>{@link BigDecimal#subtract(BigDecimal)} is exact, so the only truncation is the final
     * store. There are 11 {@code SUBTRACT} sites across the 28 programs, and this operation also
     * serves the bill payment debit {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} at
     * {@code app/cbl/COBIL00C.cbl:L234}, where an {@code S9(10)V99} balance is reduced by an
     * {@code S9(09)V99} amount into a scale-2 receiver.
     *
     * <p>Chained expressions compose safely. The four-stage validation cascade computes
     * {@code WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT} at
     * {@code app/cbl/CBTRN02C.cbl:L403-L405}; since all three operands and the receiver are
     * scale 2, subtracting and then adding at scale 2 gives the same result as evaluating the
     * whole expression exactly and storing once.
     *
     * @param minuend    the value being reduced, the COBOL receiver's prior contents; must not be
     *                   {@code null}
     * @param subtrahend the value being taken away; must not be {@code null}
     * @param scale      the receiving field's declared scale; must not be negative
     * @return the difference, truncated to {@code scale}
     * @throws NullPointerException     if either operand is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative
     */
    public static BigDecimal subtract(BigDecimal minuend, BigDecimal subtrahend, int scale) {
        Objects.requireNonNull(minuend, "minuend must not be null");
        Objects.requireNonNull(subtrahend, "subtrahend must not be null");
        requireValidScale(scale);
        return minuend.subtract(subtrahend).setScale(scale, COBOL_ROUNDING);
    }

    /**
     * Multiplies two values exactly and stores the product into a receiving field of the given
     * scale.
     *
     * <p>{@link BigDecimal#multiply(BigDecimal)} is exact and produces a result whose scale is the
     * sum of the operands' scales, so multiplying two scale-2 values yields a scale-4 intermediate.
     * Truncation happens once, on the store, which is where COBOL applies it too.
     *
     * <p>No COBOL {@code MULTIPLY} verb appears anywhere in the 28 programs; multiplication occurs
     * only inside {@code COMPUTE} expressions. Where a product is immediately divided, prefer
     * {@link #multiplyThenDivide(BigDecimal, BigDecimal, long, int)}, which keeps the intermediate
     * product exact instead of truncating it before the division.
     *
     * @param multiplicand the first factor; must not be {@code null}
     * @param multiplier   the second factor; must not be {@code null}
     * @param scale        the receiving field's declared scale; must not be negative
     * @return the product, truncated to {@code scale}
     * @throws NullPointerException     if either factor is {@code null}
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
     * <p>Both a target scale and a rounding mode are always supplied to the underlying
     * {@link BigDecimal#divide(BigDecimal, int, RoundingMode)}. This is not a stylistic preference:
     * the no-argument {@code BigDecimal.divide(BigDecimal)} throws {@link ArithmeticException}
     * whenever the exact quotient has no terminating decimal expansion, and for the interest
     * calculation that is the ordinary case rather than an edge case. Dividing {@code 100.00} by
     * {@code 3} here returns {@code 33.33} instead of failing.
     *
     * <p>The rounding mode is fixed to {@link #COBOL_ROUNDING} and cannot be overridden by a
     * caller, which is what makes it impossible to introduce a non-COBOL rounding mode into a
     * monetary path through this method.
     *
     * <p>A zero divisor is rejected explicitly, with a message naming the condition, rather than
     * being left to surface as an unexplained arithmetic failure from deep inside
     * {@link BigDecimal}. The test uses {@link BigDecimal#signum()}; comparing against
     * {@link BigDecimal#ZERO} with {@link Object#equals equals} would be a latent defect, because
     * {@code new BigDecimal("0.00").equals(BigDecimal.ZERO)} is {@code false} - the scales differ,
     * and every monetary value in this system carries a scale of 2.
     *
     * @param dividend the value being divided; must not be {@code null}
     * @param divisor  the value to divide by; must not be {@code null} and must not be zero at any
     *                 scale
     * @param scale    the receiving field's declared scale; must not be negative
     * @return the quotient, truncated to {@code scale}
     * @throws NullPointerException     if either operand is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative
     * @throws ArithmeticException      if {@code divisor} is zero
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
     * Multiplies two values exactly, then divides the product by an integer divisor into a
     * receiving field of the given scale. This is the shape of a COBOL {@code COMPUTE} whose
     * expression is a product over a literal.
     *
     * <p>The ordering is the point. The product is formed exactly, with no intermediate store, and
     * truncation is applied once at the end, on the division. Truncating the product first would
     * discard digits the COBOL still had available when it divided, and would produce a different
     * answer.
     *
     * <p>The divisor is a {@code long} rather than a {@link BigDecimal} deliberately: the 28
     * programs contain no {@code DIVIDE} verb at all, and the single division in the entire system
     * divides by an integer literal. Typing the parameter this way makes a fractional divisor
     * unrepresentable, and it also rules out a {@code double} divisor by construction.
     *
     * @param multiplicand the first factor; must not be {@code null}
     * @param multiplier   the second factor; must not be {@code null}
     * @param divisor      the integer literal to divide the exact product by; must not be zero
     * @param scale        the receiving field's declared scale; must not be negative
     * @return the quotient of the exact product and {@code divisor}, truncated to {@code scale}
     * @throws NullPointerException     if either factor is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative
     * @throws ArithmeticException      if {@code divisor} is zero
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
     * <p>The COBOL is a single statement in paragraph {@code 1300-COMPUTE-INTEREST}:
     * <pre>{@code
     * COMPUTE WS-MONTHLY-INT
     *  = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
     * }</pre>
     * with no {@code ROUNDED} phrase and no {@code ON SIZE ERROR} phrase, storing into
     * {@code WS-MONTHLY-INT PIC S9(09)V99} declared at {@code app/cbl/CBACT04C.cbl:L168}.
     *
     * <p>The declared operand shapes fix the arithmetic completely.
     * {@code TRAN-CAT-BAL} is {@code PIC S9(09)V99} [{@code app/cpy/CVTRA01Y.cpy:9}] and
     * {@code DIS-INT-RATE} is {@code PIC S9(04)V99} [{@code app/cpy/CVTRA02Y.cpy:9}], so the exact
     * product carries scale 4, and the division by {@link #MONTHLY_INTEREST_DIVISOR} is the last
     * operation before the store and therefore the only place truncation occurs.
     *
     * <p>Worked example, which is the canonical parity assertion for this formula: a balance of
     * {@code 1000.00} at a rate of {@code 12.50} gives an exact product of {@code 12500.0000}; the
     * exact quotient is {@code 10.41666...}; and the stored result is {@code 10.41}. It is never
     * {@code 10.42} - that would be the answer if this system rounded, and it does not.
     *
     * <p>The caller adds the result to a running total with
     * {@link #add(BigDecimal, BigDecimal, int)}, mirroring
     * {@code ADD WS-MONTHLY-INT TO WS-TOTAL-INT} at {@code app/cbl/CBACT04C.cbl:L467}.
     *
     * @param transactionCategoryBalance {@code TRAN-CAT-BAL}, the category balance to charge
     *                                   interest on; must not be {@code null}
     * @param disclosureInterestRate     {@code DIS-INT-RATE}, the annual rate as a percentage, so
     *                                   {@code 12.50} means twelve and a half percent; must not be
     *                                   {@code null}
     * @return the monthly interest, truncated to {@link #MONETARY_SCALE}
     * @throws NullPointerException if either operand is {@code null}
     */
    public static BigDecimal monthlyInterest(BigDecimal transactionCategoryBalance,
            BigDecimal disclosureInterestRate) {
        return multiplyThenDivide(transactionCategoryBalance, disclosureInterestRate,
                MONTHLY_INTEREST_DIVISOR, MONETARY_SCALE);
    }

    /**
     * Validates a receiving field's scale.
     *
     * <p>A COBOL {@code PICTURE} clause never declares a negative number of decimal places, so a
     * negative scale is always a programming error at the call site rather than a data condition.
     * It is rejected eagerly, because {@link BigDecimal} would otherwise accept it and silently
     * produce a value scaled by a power of ten.
     *
     * <p>Centralising the check here means the guard exists exactly once and is covered exactly
     * once by the test suite.
     *
     * @param scale the scale to validate
     * @throws IllegalArgumentException if {@code scale} is negative
     */
    private static void requireValidScale(int scale) {
        if (scale < 0) {
            throw new IllegalArgumentException(
                    "scale must not be negative, but was " + scale
                            + "; a COBOL PICTURE clause never declares a negative scale");
        }
    }

    /**
     * Validates a receiving field's integer precision.
     *
     * <p>Zero is permitted and meaningful: a receiver such as {@code PIC SV99} has no digit
     * positions left of the implied decimal point and retains only the fraction. A negative count
     * is meaningless and is rejected.
     *
     * @param integerDigits the count of digit positions left of the implied decimal point
     * @throws IllegalArgumentException if {@code integerDigits} is negative
     */
    private static void requireValidIntegerDigits(int integerDigits) {
        if (integerDigits < 0) {
            throw new IllegalArgumentException(
                    "integerDigits must not be negative, but was " + integerDigits
                            + "; a COBOL PICTURE clause never declares negative precision");
        }
    }
}
