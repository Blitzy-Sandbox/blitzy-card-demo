package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import com.vsergeychik.carddemo.util.DateUtilityJob.DateValidationResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The Java translation of CardDemo's generic {@code CCYYMMDD} date-edit engine: the procedure
 * copybook {@code app/cpy/CSUTLDPY.cpy} (375 lines) together with its working-storage copybook
 * {@code app/cpy/CSUTLDWY.cpy} (89 lines).
 *
 * <h2>One consumer, and the contract is its contract</h2>
 * Both copybooks are copied by exactly one program, {@code app/cbl/COACTUPC.cbl} - the working
 * storage at {@code COACTUPC:L166} ({@code COPY 'CSUTLDWY'}) and the procedure text at the end of the
 * procedure division. No generality beyond that consumer is invented here. {@code COACTUPC} drives
 * this engine at four sites, each of which sets the field name first, moves eight characters in,
 * performs the whole paragraph range, and moves the three flag bytes out:
 * <table border="1">
 *   <caption>The four {@code COACTUPC} call sites</caption>
 *   <tr><th>Field</th><th>Lines</th><th>{@code WS-EDIT-VARIABLE-NAME}</th><th>Extra step</th></tr>
 *   <tr><td>Open Date</td><td>L1478-L1481</td><td>{@code 'Open Date'}</td><td>-</td></tr>
 *   <tr><td>Expiry Date</td><td>L1490-L1493</td><td>{@code 'Expiry Date'}</td><td>-</td></tr>
 *   <tr><td>Reissue Date</td><td>L1503-L1506</td><td>{@code 'Reissue Date'}</td><td>-</td></tr>
 *   <tr><td>Date of Birth</td><td>L1533-L1541</td><td>{@code 'Date of Birth'}</td>
 *       <td>then {@code IF WS-EDIT-DT-OF-BIRTH-ISVALID PERFORM EDIT-DATE-OF-BIRTH ...}</td></tr>
 * </table>
 * The Java equivalent of {@code PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT} is
 * {@link #editDateCcyymmddThruExit(EditDateState)}; the equivalent of
 * {@code PERFORM EDIT-DATE-OF-BIRTH THRU EDIT-DATE-OF-BIRTH-EXIT} is
 * {@link #editDateOfBirth(EditDateState, Clock)}.
 *
 * <h2>The range perform is the unit of behaviour, not the paragraph</h2>
 * {@code CSUTLDPY} is deliberately written in the "intentional violation of structured programming
 * norms" style its own comment at {@code CSUTLDPY:L41} advertises: every validation stage ends with
 * {@code GO TO <paragraph>-EXIT}, and each {@code -EXIT} paragraph holds a bare {@code EXIT}, which in
 * COBOL is a <strong>no-op and not a return</strong>. Because the caller performs a
 * <em>range</em> of paragraphs, control falls out of one {@code -EXIT} straight into the next
 * paragraph. So {@code GO TO EDIT-YEAR-CCYY-EXIT} abandons the rest of the year edit but still runs
 * the month, day and combination edits. The physical order of the range is, and must remain:
 * <ol>
 *   <li>{@code EDIT-DATE-CCYYMMDD} - L18-L20, one statement;</li>
 *   <li>{@code EDIT-YEAR-CCYY} - L25-L87, and {@code EDIT-YEAR-CCYY-EXIT} L88-L90;</li>
 *   <li>{@code EDIT-MONTH} - L91-L144, and {@code EDIT-MONTH-EXIT} L145-L147;</li>
 *   <li>{@code EDIT-DAY} - L150-L204, and {@code EDIT-DAY-EXIT} L205-L207;</li>
 *   <li>{@code EDIT-DAY-MONTH-YEAR} - L209-L279, and {@code EDIT-DAY-MONTH-YEAR-EXIT} L280-L282;</li>
 *   <li>{@code EDIT-DATE-LE} - L284-L321;</li>
 *   <li>{@code EDIT-DATE-LE-EXIT} - L323-L328, which is where the defect below lives;</li>
 *   <li>{@code EDIT-DATE-CCYYMMDD-EXIT} - L329-L331, the end of the range.</li>
 * </ol>
 * Only {@code EDIT-DAY-MONTH-YEAR} jumps forward past paragraphs, with four
 * {@code GO TO EDIT-DATE-CCYYMMDD-EXIT} sites (L225, L240, L270, L277). Those four are the only paths
 * that skip {@code EDIT-DATE-LE}, and - critically - the only paths that skip L327.
 *
 * <h2>The L323/L327 fall-through, preserved deliberately</h2>
 * {@code EDIT-DATE-LE-EXIT} at L323 reads:
 * <pre>
 *    EDIT-DATE-LE-EXIT.
 *        EXIT
 *        .
 *   *    If we got here all edits were cleared
 *        SET WS-EDIT-DATE-IS-VALID        TO TRUE
 *        .
 * </pre>
 * The {@code EXIT} at L324 is a no-op, and the {@code SET} at L327 is a <em>separate sentence in the
 * same paragraph</em> - the next paragraph does not begin until L329. So the {@code SET} executes on
 * <strong>every</strong> path that reaches {@code EDIT-DATE-LE-EXIT}, including the error path at
 * L315 that has just set {@code INPUT-ERROR} and all three flags to {@code NOT-OK}. Since
 * {@code 88 WS-EDIT-DATE-IS-VALID VALUE LOW-VALUES} is a group condition over the concatenation of
 * all three flag bytes, that one statement resets year, month and day to {@code ISVALID}.
 *
 * <p>The observable consequence, which {@link #editDateCcyymmddThruExit(EditDateState)} reproduces
 * exactly: when {@code CSUTLDTC} rejects a date, the caller is handed
 * {@code WS-EDIT-DATE-FLGS = LOW-VALUES} - "all three fields are fine" - while
 * {@code INPUT-ERROR} remains set and {@code WS-RETURN-MSG} still carries the diagnostic, because
 * neither of those lives in {@code WS-EDIT-DATE-FLGS}. For the Date of Birth site that matters twice
 * over: {@code COACTUPC:L1539} gates {@code EDIT-DATE-OF-BIRTH} on the flags alone, so a date the
 * Language Environment rejected is still put through the birth-date check.
 *
 * <p>This is preserved, not corrected. It is exactly the class of behaviour the migration's rule B5
 * covers: where the COBOL does something odd, the Java does the same odd thing, because a caller may
 * already depend on it and because the parity gate diffs against the COBOL, not against intent.
 *
 * <h2>The guard order is per paragraph and is not uniform - read this before editing</h2>
 * The three field edits do <em>not</em> share a guard order, and the difference is observable:
 * <ul>
 *   <li>{@code EDIT-YEAR-CCYY} opens by setting {@code FLG-YEAR-NOT-OK} (L27), then tests blank
 *       (L30), then {@code IS NOT NUMERIC} (L48) - a <strong>class condition</strong> that demands
 *       four actual digits - then the century (L70).</li>
 *   <li>{@code EDIT-MONTH} opens by setting {@code FLG-MONTH-NOT-OK} (L92), tests blank (L94), then
 *       tests the <strong>range first</strong> at L111 through {@code 88 WS-VALID-MONTH}, and only
 *       afterwards runs {@code FUNCTION TEST-NUMVAL} and the normalising {@code COMPUTE} at
 *       L126-L129. The range test therefore reads the <em>raw</em> zoned value of whatever the screen
 *       supplied. {@code MM = ' 5'} reads as 05 and is accepted; {@code MM = '5 '} reads as 50 and is
 *       rejected - even though {@code TEST-NUMVAL} would have accepted both.</li>
 *   <li>{@code EDIT-DAY} opens by setting {@code FLG-DAY-ISVALID} (L152) - the opposite of the other
 *       two - tests blank (L154), then runs {@code TEST-NUMVAL} and the normalising {@code COMPUTE}
 *       <strong>first</strong> at L170-L173, and tests the range afterwards at L187. So
 *       {@code DD = '5 '} is normalised to {@code '05'} and accepted, which is the mirror image of
 *       the month behaviour.</li>
 * </ul>
 * Making these three uniform would be a behaviour change. The order below is the source's order,
 * statement for statement.
 *
 * <h2>Two views over eight bytes</h2>
 * {@code WS-EDIT-DATE-CCYYMMDD} is eight character bytes with six {@code REDEFINES} overlays on them:
 * {@code 9(2)} over each of CC, YY, MM and DD, {@code 9(4)} over CCYY and {@code 9(8)} over the
 * whole. The character view is what the screen supplies; the numeric view is what every
 * {@code 88}-level tests. A non-numeric character view makes the numeric view meaningless, which is
 * precisely why the COBOL checks numeric-ness at all - and why the numeric view here is a documented
 * zoned interpretation that <strong>never throws</strong> ({@link #zonedValueOf(byte[])}). A blank or
 * lettered field must arrive at a {@code 'B'} or {@code '0'} flag, never at an exception, because the
 * caller's next statement is a flag test.
 *
 * <h2>The 80-byte result is a shared contract with {@code CSUTLDTC}</h2>
 * {@code WS-DATE-VALIDATION-RESULT} ({@code CSUTLDWY:L60-L85}) and {@code CSUTLDTC}'s own
 * {@code WS-MESSAGE} ({@code CSUTLDTC:L42-L57}) are the <em>same</em> 80-byte layout, declared twice.
 * That is unavoidable - the field is passed by reference as {@code LS-RESULT PIC X(80)} - and it is
 * the reason {@link #WS_DATE_VALIDATION_RESULT_LAYOUT} is transcribed here independently rather than
 * borrowed: reading the severity back through <em>this</em> class's offsets is what proves the two
 * transcriptions agree. A one-byte disagreement would silently change
 * {@code AccountUpdateController}'s error text, and the parity diff would be non-zero.
 *
 * <h2>Statelessness and thread safety</h2>
 * Every COBOL working-storage item this engine touches is per-invocation and lives in
 * {@link EditDateState}, which the caller creates and owns. This class holds three immutable
 * collaborators - the codec, the date-utility service and a {@link Clock} - and <strong>no mutable
 * state, static or otherwise</strong>. The only static members are constants: widths, offsets,
 * {@code 88}-level values, the record layouts and the byte-exact message literals. A singleton
 * instance is therefore safe to share across concurrent requests, which matters because
 * {@code COACTUPC} is a pseudo-conversational transaction whose state travels in the payload rather
 * than in the server.
 *
 * @see EditDateState
 * @see #editDateCcyymmddThruExit(EditDateState)
 * @see DateUtilityJob
 */
@Component
public final class AccountDateValidator {

    // =================================================================================================
    // Declared widths - app/cpy/CSUTLDWY.cpy, plus the two items CSUTLDPY reads from its consumer's
    // working storage (app/cbl/COACTUPC.cbl).
    // =================================================================================================

    /** {@code 10 WS-EDIT-DATE-CCYYMMDD} - CSUTLDWY L4: CC + YY + MM + DD, eight character bytes. */
    public static final int WS_EDIT_DATE_CCYYMMDD_LENGTH = 8;

    /** Width of {@code WS-EDIT-DATE-CC}, {@code -YY}, {@code -MM} and {@code -DD} - CSUTLDWY L6-L27. */
    public static final int WS_EDIT_DATE_PART_LENGTH = 2;

    /** {@code 20 WS-EDIT-DATE-CCYY} - CSUTLDWY L5: the century and year together. */
    public static final int WS_EDIT_DATE_CCYY_LENGTH = 4;

    /** {@code 10 WS-EDIT-DATE-FLGS} - CSUTLDWY L43: three one-byte flags, moved out as a group. */
    public static final int WS_EDIT_DATE_FLGS_LENGTH = 3;

    /** {@code 10 WS-DATE-FORMAT PIC X(08)} - CSUTLDWY L58. */
    public static final int WS_DATE_FORMAT_LENGTH = 8;

    /** {@code VALUE 'YYYYMMDD'} - CSUTLDWY L59, and the mask {@code EDIT-DATE-LE} moves in at L291. */
    public static final String WS_DATE_FORMAT_VALUE = "YYYYMMDD";

    /** {@code 10 WS-DATE-VALIDATION-RESULT} - CSUTLDWY L60-L85, exactly 80 bytes. */
    public static final int WS_DATE_VALIDATION_RESULT_LENGTH = 80;

    /** {@code 10 WS-EDIT-VARIABLE-NAME PIC X(25)} - app/cbl/COACTUPC.cbl L53. */
    public static final int WS_EDIT_VARIABLE_NAME_LENGTH = 25;

    /** {@code 05 WS-RETURN-MSG PIC X(75)} - app/cbl/COACTUPC.cbl L479. */
    public static final int WS_RETURN_MSG_LENGTH = 75;

    // =================================================================================================
    // 88-level values - app/cpy/CSUTLDWY.cpy. Every condition name is named here and tested by a
    // predicate on EditDateState, so no bare literal appears in a guard.
    // =================================================================================================

    /** {@code 88 THIS-CENTURY VALUE 20} - CSUTLDWY L9. */
    public static final int THIS_CENTURY = 20;

    /** {@code 88 LAST-CENTURY VALUE 19} - CSUTLDWY L10. */
    public static final int LAST_CENTURY = 19;

    /** Low bound of {@code 88 WS-VALID-MONTH VALUES 1 THROUGH 12} - CSUTLDWY L19-L20. */
    public static final int WS_VALID_MONTH_LOW = 1;

    /** High bound of {@code 88 WS-VALID-MONTH VALUES 1 THROUGH 12} - CSUTLDWY L19-L20. */
    public static final int WS_VALID_MONTH_HIGH = 12;

    /** {@code 88 WS-FEBRUARY VALUE 2} - CSUTLDWY L24. */
    public static final int WS_FEBRUARY = 2;

    /** Low bound of {@code 88 WS-VALID-DAY VALUES 1 THROUGH 31} - CSUTLDWY L28-L29. */
    public static final int WS_VALID_DAY_LOW = 1;

    /** High bound of {@code 88 WS-VALID-DAY VALUES 1 THROUGH 31} - CSUTLDWY L28-L29. */
    public static final int WS_VALID_DAY_HIGH = 31;

    /** {@code 88 WS-DAY-31 VALUE 31} - CSUTLDWY L30. */
    public static final int WS_DAY_31 = 31;

    /** {@code 88 WS-DAY-30 VALUE 30} - CSUTLDWY L31. */
    public static final int WS_DAY_30 = 30;

    /** {@code 88 WS-DAY-29 VALUE 29} - CSUTLDWY L32. */
    public static final int WS_DAY_29 = 29;

    /** Low bound of {@code 88 WS-VALID-FEB-DAY VALUES 1 THROUGH 28} - CSUTLDWY L33-L34. */
    public static final int WS_VALID_FEB_DAY_LOW = 1;

    /** High bound of {@code 88 WS-VALID-FEB-DAY VALUES 1 THROUGH 28} - CSUTLDWY L33-L34. */
    public static final int WS_VALID_FEB_DAY_HIGH = 28;

    /**
     * {@code 88 WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12} - CSUTLDWY L21-L23.
     *
     * <p>An array is the faithful shape for a {@code VALUES} list of discrete values: membership, not
     * a range. It is private and never handed out, and {@link EditDateState#ws31DayMonth()} is the
     * only reader, so the array cannot be mutated from outside this class.
     */
    private static final int[] WS_31_DAY_MONTH_VALUES = {1, 3, 5, 7, 8, 10, 12};

    /**
     * {@code MOVE 400 TO WS-DIV-BY} - CSUTLDPY L246, taken when {@code WS-EDIT-DATE-YY-N = 0}, that
     * is for a century year such as 1900 or 2000.
     */
    public static final int LEAP_DIVISOR_CENTURY = 400;

    /**
     * {@code MOVE 4 TO WS-DIV-BY} - CSUTLDPY L248, and the {@code VALUE 4} the consumer declares on
     * {@code WS-DIV-BY} at {@code COACTUPC:L152-L153}.
     */
    public static final int LEAP_DIVISOR_ORDINARY = 4;

    /** {@code IF WS-EDIT-DATE-YY-N = 0} - CSUTLDPY L245, the century-year discriminator. */
    private static final int CENTURY_YEAR_YY = 0;

    /** {@code IF WS-REMAINDER = ZEROES} - CSUTLDPY L256, the leap-year acceptance test. */
    private static final int LEAP_REMAINDER_OK = 0;

    /** {@code IF WS-SEVERITY-N = 0} - CSUTLDPY L298: the severity that means the date converted. */
    private static final int SEVERITY_OK = 0;

    // =================================================================================================
    // Message literals - app/cpy/CSUTLDPY.cpy, byte for byte including every leading and trailing
    // space. These are appended to FUNCTION TRIM(WS-EDIT-VARIABLE-NAME), so the leading space or its
    // absence is significant: two of the thirteen deliberately abut the field name with no space, and
    // three of them place the colon differently. Do not tidy them.
    // =================================================================================================

    /** CSUTLDPY L37 - year blank. Note the space before the colon. */
    public static final String MSG_YEAR_MUST_BE_SUPPLIED = " : Year must be supplied.";

    /** CSUTLDPY L54 - year not numeric. No colon at all in this one. */
    public static final String MSG_YEAR_MUST_BE_4_DIGITS = " must be 4 digit number.";

    /** CSUTLDPY L79 - century neither 19 nor 20. */
    public static final String MSG_CENTURY_NOT_VALID = " : Century is not valid.";

    /** CSUTLDPY L101 - month blank. */
    public static final String MSG_MONTH_MUST_BE_SUPPLIED = " : Month must be supplied.";

    /** CSUTLDPY L119 and L136 - the identical literal on both month rejection paths. */
    public static final String MSG_MONTH_MUST_BE_1_TO_12 =
            ": Month must be a number between 1 and 12.";

    /** CSUTLDPY L161 - day blank. */
    public static final String MSG_DAY_MUST_BE_SUPPLIED = " : Day must be supplied.";

    /** CSUTLDPY L180 and L195 - the identical literal on both day rejection paths, lower-case 'day'. */
    public static final String MSG_DAY_MUST_BE_1_TO_31 = ":day must be a number between 1 and 31.";

    /** CSUTLDPY L221 - day 31 in a month that has thirty or fewer. */
    public static final String MSG_CANNOT_HAVE_31_DAYS = ":Cannot have 31 days in this month.";

    /** CSUTLDPY L236 - day 30 in February. */
    public static final String MSG_CANNOT_HAVE_30_DAYS = ":Cannot have 30 days in this month.";

    /** CSUTLDPY L266 - 29 February in a common year. One sentence, no space after the full stop. */
    public static final String MSG_NOT_A_LEAP_YEAR =
            ":Not a leap year.Cannot have 29 days in this month.";

    /** CSUTLDPY L308 - the first fragment of the {@code CSUTLDTC} diagnostic. */
    public static final String MSG_VALIDATION_ERROR_SEV_CODE = " validation error Sev code: ";

    /** CSUTLDPY L310 - the second fragment, sitting between the severity and the message number. */
    public static final String MSG_MESSAGE_CODE = " Message code: ";

    /** CSUTLDPY L363 - date of birth in the future. Leading colon, no space; one trailing space. */
    public static final String MSG_CANNOT_BE_IN_THE_FUTURE = ":cannot be in the future ";

    // =================================================================================================
    // FUNCTION INTEGER-OF-DATE - app/cpy/CSUTLDPY.cpy L346 and L348.
    // =================================================================================================

    /**
     * The constant that turns a Java epoch day into a COBOL integer date.
     *
     * <p>{@code FUNCTION INTEGER-OF-DATE} returns the number of days since 31 December 1600, so
     * 1 January 1601 is day 1. Java counts epoch days from 1 January 1970, and
     * {@code LocalDate.of(1601, 1, 1).toEpochDay()} is {@code -134774}; adding {@code 134775}
     * therefore maps that date to 1. Verified against the anchors 1601-01-01 &rarr; 1,
     * 1601-12-31 &rarr; 365 (1601 was a common year), 1900-01-01 &rarr; 109208,
     * 2000-01-01 &rarr; 145732 and 2099-12-31 &rarr; 182256 - every one inside
     * {@code PIC S9(9) BINARY}, which is why {@link EditDateState#editDateBinary()} is an
     * {@code int} and never a floating-point type.
     */
    public static final int INTEGER_OF_DATE_EPOCH_OFFSET = 134775;

    /** Lowest argument {@code FUNCTION INTEGER-OF-DATE} accepts: 1601-01-01 as {@code 9(8)}. */
    public static final int INTEGER_OF_DATE_LOWEST_ARGUMENT = 16010101;

    /** Highest argument {@code FUNCTION INTEGER-OF-DATE} accepts: 9999-12-31 as {@code 9(8)}. */
    public static final int INTEGER_OF_DATE_HIGHEST_ARGUMENT = 99991231;

    /**
     * The value {@link #integerOfDate(int)} yields for an argument that is not a standard date.
     *
     * <p>COBOL leaves the result of {@code FUNCTION INTEGER-OF-DATE} undefined when its argument is
     * not a valid standard date in the supported range, so <em>some</em> deterministic choice has to
     * be made. Zero is chosen because it keeps the surrounding comparison meaningful:
     * {@code IF WS-CURRENT-DATE-BINARY &gt; WS-EDIT-DATE-BINARY} (L350) then still succeeds, so an
     * unconvertible date is not additionally reported as being in the future, and the verdict the
     * earlier edits already reached is left standing. The path is latent rather than ordinary - the
     * year, month, day and combination edits reject every impossible date before this point - but it
     * is reachable through the L327 fall-through, so it must be defined rather than left to throw.
     */
    public static final int INTEGER_OF_DATE_UNDEFINED = 0;

    /** Divisor extracting the year from a {@code 9(8)} standard date. */
    private static final int STANDARD_DATE_YEAR_DIVISOR = 10000;

    /** Divisor extracting the month from a {@code 9(8)} standard date, before the modulus. */
    private static final int STANDARD_DATE_MONTH_DIVISOR = 100;

    /** Modulus isolating a two-digit month or day component of a {@code 9(8)} standard date. */
    private static final int STANDARD_DATE_COMPONENT_MODULUS = 100;

    // =================================================================================================
    // FUNCTION CURRENT-DATE - app/cpy/CSUTLDPY.cpy L343.
    // =================================================================================================

    /**
     * The declared width of {@code FUNCTION CURRENT-DATE}: {@code YYYYMMDDhhmmsshh} then the sign of
     * the offset from Greenwich and {@code HHMM}, that is 8 + 8 + 1 + 4 = 21 characters. The receiver
     * at L343 is {@code WS-CURRENT-DATE-YYYYMMDD PIC X(8)}, so thirteen of those characters are
     * discarded by the alphanumeric move's right truncation.
     */
    public static final int CURRENT_DATE_INTRINSIC_LENGTH = 21;

    /** Width of each two-digit time component of {@code FUNCTION CURRENT-DATE}. */
    private static final int TIME_COMPONENT_LENGTH = 2;

    /** Nanoseconds per hundredth of a second, the resolution {@code FUNCTION CURRENT-DATE} reports. */
    private static final int NANOS_PER_HUNDREDTH = 10_000_000;

    /** Seconds per minute, for rendering the Greenwich offset as {@code HHMM}. */
    private static final int SECONDS_PER_MINUTE = 60;

    /** Minutes per hour, for rendering the Greenwich offset as {@code HHMM}. */
    private static final int MINUTES_PER_HOUR = 60;

    /** The sign character {@code FUNCTION CURRENT-DATE} emits for an offset at or after Greenwich. */
    private static final char OFFSET_SIGN_AHEAD = '+';

    /** The sign character {@code FUNCTION CURRENT-DATE} emits for an offset behind Greenwich. */
    private static final char OFFSET_SIGN_BEHIND = '-';

    // =================================================================================================
    // Character constants. Named rather than inlined so a guard never reads as a bare literal.
    // =================================================================================================

    /** The COBOL figurative constant {@code SPACE}. */
    private static final char SPACE = ' ';

    /** The COBOL figurative constant {@code LOW-VALUE}: the byte with every bit clear. */
    private static final char LOW_VALUE = '\u0000';

    /** The decimal point {@code FUNCTION NUMVAL} accepts. */
    private static final char DECIMAL_POINT = '.';

    /** The plus sign {@code FUNCTION NUMVAL} accepts in either the leading or the trailing position. */
    private static final char PLUS_SIGN = '+';

    /** The minus sign {@code FUNCTION NUMVAL} accepts in either the leading or the trailing position. */
    private static final char MINUS_SIGN = '-';

    /** The trailing credit indicator {@code FUNCTION NUMVAL} treats as a negative sign. */
    private static final String CREDIT_INDICATOR = "CR";

    /** The trailing debit indicator {@code FUNCTION NUMVAL} treats as a negative sign. */
    private static final String DEBIT_INDICATOR = "DB";

    /** Mask isolating the low-order four bits of a byte: the digit of a zoned {@code DISPLAY} byte. */
    private static final int ZONED_DIGIT_MASK = 0x0F;

    /** The radix of a zoned {@code DISPLAY} field: one decimal digit per byte. */
    private static final int DECIMAL_RADIX = 10;

    /** {@link #scanNumval(String)} reports this position when the argument conforms. */
    private static final int NUMVAL_CONFORMS = 0;

    /** The charset the no-argument and two-argument constructors default to, matching the siblings. */
    private static final Charset DEFAULT_CHARSET = StandardCharsets.US_ASCII;

    // =================================================================================================
    // The flag model - app/cpy/CSUTLDWY.cpy L43-L57.
    // =================================================================================================

    /**
     * The three states each of {@code WS-EDIT-YEAR-FLG}, {@code WS-EDIT-MONTH} and
     * {@code WS-EDIT-DAY} can hold - CSUTLDWY L46-L57.
     *
     * <p>An enum carries the state and {@link #flagByte()} carries the byte, because both are
     * observable. The state is what a guard tests; the byte is what
     * {@code MOVE WS-EDIT-DATE-FLGS TO WS-EDIT-OPEN-DATE-FLGS} ({@code COACTUPC:L1482}) transfers, and
     * what the group conditions {@code 88 WS-EDIT-DATE-IS-VALID VALUE LOW-VALUES} and
     * {@code 88 WS-EDIT-DATE-IS-INVALID VALUE '000'} (L44-L45) are evaluated over. Storing only the
     * state would lose the group tests; storing only the byte would lose the names.
     *
     * <p>Note the field names in the copybook: only the year flag carries the {@code -FLG} suffix.
     * The month and day flags are plainly {@code WS-EDIT-MONTH} and {@code WS-EDIT-DAY}.
     */
    public enum EditFlag {

        /**
         * {@code 88 FLG-YEAR-ISVALID VALUE LOW-VALUES} and its month and day counterparts - L47, L51,
         * L55. This is also the state a freshly created {@link EditDateState} starts in, matching the
         * binary zeros IBM leaves in working storage that declares no {@code VALUE}.
         */
        ISVALID(LOW_VALUE),

        /** {@code 88 FLG-YEAR-NOT-OK VALUE '0'} and its counterparts - L48, L52, L56. */
        NOT_OK('0'),

        /** {@code 88 FLG-YEAR-BLANK VALUE 'B'} and its counterparts - L49, L53, L57. */
        BLANK('B');

        /** The byte this state occupies in the three-byte {@code WS-EDIT-DATE-FLGS} group. */
        private final char flagByte;

        /**
         * @param flagByte the declared {@code VALUE} of the matching {@code 88} level
         */
        EditFlag(char flagByte) {
            this.flagByte = flagByte;
        }

        /**
         * The byte this state occupies in {@code WS-EDIT-DATE-FLGS}.
         *
         * @return {@code LOW-VALUE}, {@code '0'} or {@code 'B'}
         */
        public char flagByte() {
            return flagByte;
        }

        /**
         * Recovers the state a flag byte denotes, which is what reading a flag group back in from a
         * per-field holder such as {@code WS-EDIT-OPEN-DATE-FLGS} amounts to.
         *
         * @param flagByte the byte to interpret
         * @return the matching state
         * @throws IllegalArgumentException if the byte is none of the three declared values, because
         *                                  a fourth value has no meaning in the copybook and
         *                                  guessing one would hide a corrupted flag group
         */
        public static EditFlag ofByte(char flagByte) {
            for (EditFlag candidate : values()) {
                if (candidate.flagByte == flagByte) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Flag byte 0x"
                    + Integer.toHexString(flagByte) + " is none of the three values CSUTLDWY declares "
                    + "(LOW-VALUE for ISVALID, '0' for NOT-OK, 'B' for BLANK)");
        }
    }

    /**
     * The three states of {@code WS-INPUT-FLAG} - {@code app/cbl/COACTUPC.cbl} L170-L174.
     *
     * <p>This flag belongs to the consumer, not to {@code CSUTLDWY}, but {@code CSUTLDPY} both sets it
     * ({@code SET INPUT-ERROR TO TRUE}, eleven sites) and tests it ({@code IF NOT INPUT-ERROR} at
     * L318), so it has to travel in the state object. It is <strong>shared across every field
     * {@code COACTUPC} edits</strong>, not reset per date, and that has two visible consequences: the
     * {@code IF NOT INPUT-ERROR} at L318 can be false because an earlier field failed, and - together
     * with {@code 88 WS-RETURN-MSG-OFF} - only the first failing field of the whole screen produces a
     * message.
     */
    public enum InputFlag {

        /** {@code 88 INPUT-PENDING VALUE LOW-VALUES} - COACTUPC L174, and the initial state. */
        PENDING(LOW_VALUE),

        /** {@code 88 INPUT-OK VALUE '0'} - COACTUPC L172. */
        OK('0'),

        /** {@code 88 INPUT-ERROR VALUE '1'} - COACTUPC L173. */
        ERROR('1');

        /** The byte this state occupies in {@code WS-INPUT-FLAG PIC X(1)}. */
        private final char flagByte;

        /**
         * @param flagByte the declared {@code VALUE} of the matching {@code 88} level
         */
        InputFlag(char flagByte) {
            this.flagByte = flagByte;
        }

        /**
         * The byte this state occupies in {@code WS-INPUT-FLAG}.
         *
         * @return {@code LOW-VALUE}, {@code '0'} or {@code '1'}
         */
        public char flagByte() {
            return flagByte;
        }

        /**
         * Recovers the state a flag byte denotes.
         *
         * @param flagByte the byte to interpret
         * @return the matching state
         * @throws IllegalArgumentException if the byte is none of the three declared values
         */
        public static InputFlag ofByte(char flagByte) {
            for (InputFlag candidate : values()) {
                if (candidate.flagByte == flagByte) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Input flag byte 0x"
                    + Integer.toHexString(flagByte) + " is none of the three values COACTUPC declares "
                    + "(LOW-VALUE for INPUT-PENDING, '0' for INPUT-OK, '1' for INPUT-ERROR)");
        }
    }

    // =================================================================================================
    // WS-EDIT-DATE-CCYYMMDD geometry - app/cpy/CSUTLDWY.cpy L4-L36. Eight bytes of storage with six
    // REDEFINES overlays. The layout lists elementary storage in copybook order and each overlay
    // immediately after the storage it views, because RecordLayout verifies that an overlay falls
    // inside storage already declared ahead of it - a group item and a REDEFINES both occupy no
    // storage of their own, so their position in the list is a declaration detail, not a layout one.
    // =================================================================================================

    /** {@code 25 WS-EDIT-DATE-CC PIC X(2)} - CSUTLDWY L6. */
    private static final FieldSpan WS_EDIT_DATE_CC =
            FieldSpan.alphanumeric("WS-EDIT-DATE-CC", 0, WS_EDIT_DATE_PART_LENGTH);

    /** {@code 25 WS-EDIT-DATE-CC-N REDEFINES WS-EDIT-DATE-CC PIC 9(2)} - CSUTLDWY L7-L8. */
    private static final FieldSpan WS_EDIT_DATE_CC_N = FieldSpan.redefining(
            "WS-EDIT-DATE-CC-N", 0, WS_EDIT_DATE_PART_LENGTH, PictureKind.UNSIGNED_NUMERIC);

    /** {@code 25 WS-EDIT-DATE-YY PIC X(2)} - CSUTLDWY L11. */
    private static final FieldSpan WS_EDIT_DATE_YY = FieldSpan.alphanumeric(
            "WS-EDIT-DATE-YY", WS_EDIT_DATE_PART_LENGTH, WS_EDIT_DATE_PART_LENGTH);

    /** {@code 25 WS-EDIT-DATE-YY-N REDEFINES WS-EDIT-DATE-YY PIC 9(2)} - CSUTLDWY L12-L13. */
    private static final FieldSpan WS_EDIT_DATE_YY_N = FieldSpan.redefining("WS-EDIT-DATE-YY-N",
            WS_EDIT_DATE_PART_LENGTH, WS_EDIT_DATE_PART_LENGTH, PictureKind.UNSIGNED_NUMERIC);

    /**
     * {@code 20 WS-EDIT-DATE-CCYY} - CSUTLDWY L5. A group item over {@code CC} and {@code YY}; it
     * occupies no storage of its own, so it is declared as an overlay of the four bytes its two
     * children own. {@code EDIT-YEAR-CCYY} tests this group directly at L30, L31 and L48.
     */
    private static final FieldSpan WS_EDIT_DATE_CCYY = FieldSpan.redefining(
            "WS-EDIT-DATE-CCYY", 0, WS_EDIT_DATE_CCYY_LENGTH, PictureKind.ALPHANUMERIC);

    /** {@code 20 WS-EDIT-DATE-CCYY-N REDEFINES WS-EDIT-DATE-CCYY PIC 9(4)} - CSUTLDWY L14-L15. */
    private static final FieldSpan WS_EDIT_DATE_CCYY_N = FieldSpan.redefining(
            "WS-EDIT-DATE-CCYY-N", 0, WS_EDIT_DATE_CCYY_LENGTH, PictureKind.UNSIGNED_NUMERIC);

    /** {@code 20 WS-EDIT-DATE-MM PIC X(2)} - CSUTLDWY L16. */
    private static final FieldSpan WS_EDIT_DATE_MM = FieldSpan.alphanumeric(
            "WS-EDIT-DATE-MM", WS_EDIT_DATE_CCYY_LENGTH, WS_EDIT_DATE_PART_LENGTH);

    /** {@code 20 WS-EDIT-DATE-MM-N REDEFINES WS-EDIT-DATE-MM PIC 9(2)} - CSUTLDWY L17-L18. */
    private static final FieldSpan WS_EDIT_DATE_MM_N = FieldSpan.redefining("WS-EDIT-DATE-MM-N",
            WS_EDIT_DATE_CCYY_LENGTH, WS_EDIT_DATE_PART_LENGTH, PictureKind.UNSIGNED_NUMERIC);

    /** {@code 20 WS-EDIT-DATE-DD PIC X(2)} - CSUTLDWY L25. */
    private static final FieldSpan WS_EDIT_DATE_DD = FieldSpan.alphanumeric("WS-EDIT-DATE-DD",
            WS_EDIT_DATE_CCYY_LENGTH + WS_EDIT_DATE_PART_LENGTH, WS_EDIT_DATE_PART_LENGTH);

    /** {@code 20 WS-EDIT-DATE-DD-N REDEFINES WS-EDIT-DATE-DD PIC 9(2)} - CSUTLDWY L26-L27. */
    private static final FieldSpan WS_EDIT_DATE_DD_N = FieldSpan.redefining("WS-EDIT-DATE-DD-N",
            WS_EDIT_DATE_CCYY_LENGTH + WS_EDIT_DATE_PART_LENGTH, WS_EDIT_DATE_PART_LENGTH,
            PictureKind.UNSIGNED_NUMERIC);

    /**
     * {@code 10 WS-EDIT-DATE-CCYYMMDD-N REDEFINES WS-EDIT-DATE-CCYYMMDD PIC 9(8)} - CSUTLDWY L35-L36.
     * This is the argument {@code EDIT-DATE-OF-BIRTH} hands {@code FUNCTION INTEGER-OF-DATE} at L346.
     */
    private static final FieldSpan WS_EDIT_DATE_CCYYMMDD_N = FieldSpan.redefining(
            "WS-EDIT-DATE-CCYYMMDD-N", 0, WS_EDIT_DATE_CCYYMMDD_LENGTH,
            PictureKind.UNSIGNED_NUMERIC);

    /**
     * The eight-byte edit area with all six overlays. {@link RecordLayout} re-verifies on class
     * initialisation that the storage spans tile bytes 0 to 7 with no gap and no overlap and that
     * every overlay falls inside them, so a mistranscribed offset fails here rather than producing a
     * plausible wrong answer at a call site.
     */
    private static final RecordLayout WS_EDIT_DATE_CCYYMMDD_LAYOUT = RecordLayout.of(
            WS_EDIT_DATE_CCYYMMDD_LENGTH,
            WS_EDIT_DATE_CC, WS_EDIT_DATE_CC_N,
            WS_EDIT_DATE_YY, WS_EDIT_DATE_YY_N,
            WS_EDIT_DATE_CCYY, WS_EDIT_DATE_CCYY_N,
            WS_EDIT_DATE_MM, WS_EDIT_DATE_MM_N,
            WS_EDIT_DATE_DD, WS_EDIT_DATE_DD_N,
            WS_EDIT_DATE_CCYYMMDD_N);

    // =================================================================================================
    // WS-DATE-VALIDATION-RESULT geometry - app/cpy/CSUTLDWY.cpy L60-L85. Transcribed independently of
    // DateUtilityJob's WS-MESSAGE (app/cbl/CSUTLDTC.cbl L42-L57) precisely so that reading the
    // severity back through these offsets proves the two declarations of the one 80-byte area agree.
    // The declared spans sum to 4 + 11 + 4 + 1 + 15 + 1 + 9 + 10 + 1 + 10 + 10 + 1 + 3 = 80.
    // =================================================================================================

    /** {@code 20 WS-SEVERITY PIC X(04)} - CSUTLDWY L61, at the start of the area. */
    private static final FieldSpan WS_SEVERITY = FieldSpan.alphanumeric("WS-SEVERITY", 0, 4);

    /** {@code 20 WS-SEVERITY-N REDEFINES WS-SEVERITY PIC 9(4)} - CSUTLDWY L62-L63. */
    private static final FieldSpan WS_SEVERITY_N =
            FieldSpan.redefining("WS-SEVERITY-N", 0, 4, PictureKind.UNSIGNED_NUMERIC);

    /** {@code 20 FILLER PIC X(11) VALUE 'Mesg Code:'} - CSUTLDWY L64-L65: ten characters in eleven. */
    private static final FieldSpan FILLER_MESG_CODE = FieldSpan.filler(4, 11, "Mesg Code:");

    /** {@code 20 WS-MSG-NO PIC X(04)} - CSUTLDWY L66. */
    private static final FieldSpan WS_MSG_NO = FieldSpan.alphanumeric("WS-MSG-NO", 15, 4);

    /** {@code 20 WS-MSG-NO-N REDEFINES WS-MSG-NO Pic 9(4)} - CSUTLDWY L67-L68. */
    private static final FieldSpan WS_MSG_NO_N =
            FieldSpan.redefining("WS-MSG-NO-N", 15, 4, PictureKind.UNSIGNED_NUMERIC);

    /** {@code 20 FILLER PIC X(01) VALUE SPACE} - CSUTLDWY L69-L70. */
    private static final FieldSpan FILLER_AFTER_MSG_NO = FieldSpan.filler(19, 1, " ");

    /** {@code 20 WS-RESULT PIC X(15)} - CSUTLDWY L71: the fifteen-character verdict text. */
    private static final FieldSpan WS_RESULT = FieldSpan.alphanumeric("WS-RESULT", 20, 15);

    /** {@code 20 FILLER PIC X(01) VALUE SPACE} - CSUTLDWY L72-L73. */
    private static final FieldSpan FILLER_AFTER_RESULT = FieldSpan.filler(35, 1, " ");

    /** {@code 20 FILLER PIC X(09) VALUE 'TstDate:'} - CSUTLDWY L74-L75: eight characters in nine. */
    private static final FieldSpan FILLER_TST_DATE = FieldSpan.filler(36, 9, "TstDate:");

    /** {@code 20 WS-DATE PIC X(10)} - CSUTLDWY L76. */
    private static final FieldSpan WS_DATE = FieldSpan.alphanumeric("WS-DATE", 45, 10);

    /** {@code 20 FILLER PIC X(01) VALUE SPACE} - CSUTLDWY L77-L78. */
    private static final FieldSpan FILLER_AFTER_DATE = FieldSpan.filler(55, 1, " ");

    /** {@code 20 FILLER PIC X(10) VALUE 'Mask used:'} - CSUTLDWY L79-L80: exactly ten characters. */
    private static final FieldSpan FILLER_MASK_USED = FieldSpan.filler(56, 10, "Mask used:");

    /** {@code 20 WS-DATE-FMT PIC X(10)} - CSUTLDWY L81. */
    private static final FieldSpan WS_DATE_FMT = FieldSpan.alphanumeric("WS-DATE-FMT", 66, 10);

    /** {@code 20 FILLER PIC X(01) VALUE SPACE} - CSUTLDWY L82-L83. */
    private static final FieldSpan FILLER_AFTER_FMT = FieldSpan.filler(76, 1, " ");

    /** {@code 20 FILLER PIC X(03) VALUE SPACES} - CSUTLDWY L84-L85, closing the eighty bytes. */
    private static final FieldSpan FILLER_TRAILING = FieldSpan.filler(77, 3, "   ");

    /** The 80-byte result area, every span declared including all six {@code FILLER}s. */
    private static final RecordLayout WS_DATE_VALIDATION_RESULT_LAYOUT = RecordLayout.of(
            WS_DATE_VALIDATION_RESULT_LENGTH,
            WS_SEVERITY, WS_SEVERITY_N, FILLER_MESG_CODE,
            WS_MSG_NO, WS_MSG_NO_N, FILLER_AFTER_MSG_NO,
            WS_RESULT, FILLER_AFTER_RESULT, FILLER_TST_DATE,
            WS_DATE, FILLER_AFTER_DATE, FILLER_MASK_USED,
            WS_DATE_FMT, FILLER_AFTER_FMT, FILLER_TRAILING);

    // =================================================================================================
    // WS-RETURN-MSG - app/cbl/COACTUPC.cbl L479. One span, so that the eleven STRING ... DELIMITED BY
    // SIZE INTO WS-RETURN-MSG statements can go through the codec's own STRING primitive, which leaves
    // the untouched remainder of the receiver alone exactly as COBOL's STRING does.
    // =================================================================================================

    /** {@code 05 WS-RETURN-MSG PIC X(75)} - COACTUPC L479. */
    private static final FieldSpan WS_RETURN_MSG =
            FieldSpan.alphanumeric("WS-RETURN-MSG", 0, WS_RETURN_MSG_LENGTH);

    /** The 75-byte message area. */
    private static final RecordLayout WS_RETURN_MSG_LAYOUT =
            RecordLayout.of(WS_RETURN_MSG_LENGTH, WS_RETURN_MSG);

    // =================================================================================================
    // WS-CURRENT-DATE - app/cpy/CSUTLDWY.cpy L38-L42. The character portion and its numeric overlay
    // are a record; the trailing WS-CURRENT-DATE-BINARY PIC S9(9) BINARY is a four-byte binary integer
    // that no statement in CSUTLDPY ever reads as bytes, so it is an int field on the state rather
    // than a span - a binary span in a zoned-DISPLAY record area would be a fiction.
    // =================================================================================================

    /** {@code 20 WS-CURRENT-DATE-YYYYMMDD PIC X(8)} - CSUTLDWY L39. */
    private static final FieldSpan WS_CURRENT_DATE_YYYYMMDD = FieldSpan.alphanumeric(
            "WS-CURRENT-DATE-YYYYMMDD", 0, WS_EDIT_DATE_CCYYMMDD_LENGTH);

    /**
     * {@code 20 WS-CURRENT-DATE-YYYYMMDD-N REDEFINES WS-CURRENT-DATE-YYYYMMDD PIC 9(8)} - CSUTLDWY
     * L40-L41. This is the argument {@code EDIT-DATE-OF-BIRTH} hands
     * {@code FUNCTION INTEGER-OF-DATE} at L348.
     */
    private static final FieldSpan WS_CURRENT_DATE_YYYYMMDD_N = FieldSpan.redefining(
            "WS-CURRENT-DATE-YYYYMMDD-N", 0, WS_EDIT_DATE_CCYYMMDD_LENGTH,
            PictureKind.UNSIGNED_NUMERIC);

    /** The eight character bytes of {@code WS-CURRENT-DATE} with their numeric overlay. */
    private static final RecordLayout WS_CURRENT_DATE_LAYOUT = RecordLayout.of(
            WS_EDIT_DATE_CCYYMMDD_LENGTH, WS_CURRENT_DATE_YYYYMMDD, WS_CURRENT_DATE_YYYYMMDD_N);

    // =================================================================================================
    // The per-invocation state.
    // =================================================================================================

    /**
     * Every working-storage item the date-edit engine reads or writes, for one validation.
     *
     * <p>This is the Java form of {@code app/cpy/CSUTLDWY.cpy} plus the four items {@code CSUTLDPY}
     * borrows from its consumer's working storage: {@code WS-EDIT-VARIABLE-NAME}
     * ({@code COACTUPC:L53}), {@code WS-INPUT-FLAG} ({@code COACTUPC:L170}), {@code WS-RETURN-MSG}
     * ({@code COACTUPC:L479}) and the three calculation variables {@code WS-DIV-BY},
     * {@code WS-DIVIDEND} and {@code WS-REMAINDER} ({@code COACTUPC:L152-L158}).
     *
     * <p><strong>It is per-invocation and it is mutable, which is exactly why nothing here is
     * static.</strong> COBOL working storage is a single shared area for a single-threaded run; a Java
     * service handling concurrent requests cannot share one, so the caller creates a state, drives the
     * validation, reads the outcome and discards it. Reuse across two dates is safe only in the way
     * the COBOL reuses it - by moving a fresh eight characters in first, which
     * {@link #editDateCcyymmddThruExit(EditDateState)} then follows with
     * {@code SET WS-EDIT-DATE-IS-INVALID} at L19 before any flag is read.
     *
     * <p>Initial contents match what {@code COACTUPC} presents on entry: the eight edit bytes and the
     * twenty-five name bytes are spaces, {@code WS-RETURN-MSG} is spaces so that
     * {@code 88 WS-RETURN-MSG-OFF} holds - as {@code COACTUPC:L876} explicitly arranges - the three
     * edit flags and {@code WS-INPUT-FLAG} are {@code LOW-VALUES}, matching both
     * {@code 88 INPUT-PENDING} and the binary zeros IBM leaves in working storage that declares no
     * {@code VALUE}, {@code WS-DATE-FORMAT} carries its declared {@code VALUE 'YYYYMMDD'} and
     * {@code WS-DIV-BY} its declared {@code VALUE 4}.
     */
    public static final class EditDateState {

        /** The codec applying every {@code PICTURE} rule; shared with the validator that made this. */
        private final FixedWidthCodec codec;

        /** {@code WS-EDIT-DATE-CCYYMMDD} and its six overlays - eight bytes. */
        private final FixedWidthRecord editDateArea;

        /** {@code WS-DATE-VALIDATION-RESULT} - eighty bytes. */
        private final FixedWidthRecord dateValidationArea;

        /** {@code WS-RETURN-MSG} - seventy-five bytes, so {@code STRING ... INTO} can be exact. */
        private final FixedWidthRecord returnMsgArea;

        /** The character portion of {@code WS-CURRENT-DATE} and its numeric overlay - eight bytes. */
        private final FixedWidthRecord currentDateArea;

        /** {@code WS-EDIT-YEAR-FLG} - CSUTLDWY L46. */
        private EditFlag yearFlag = EditFlag.ISVALID;

        /** {@code WS-EDIT-MONTH} - CSUTLDWY L50. The copybook omits a {@code -FLG} suffix here. */
        private EditFlag monthFlag = EditFlag.ISVALID;

        /** {@code WS-EDIT-DAY} - CSUTLDWY L54. */
        private EditFlag dayFlag = EditFlag.ISVALID;

        /** {@code WS-INPUT-FLAG} - COACTUPC L170-L174, shared across every field on the screen. */
        private InputFlag inputFlag = InputFlag.PENDING;

        /** {@code WS-EDIT-VARIABLE-NAME PIC X(25)} - COACTUPC L53, always exactly 25 characters. */
        private String editVariableName;

        /** {@code WS-DATE-FORMAT PIC X(08) VALUE 'YYYYMMDD'} - CSUTLDWY L58-L59. */
        private String dateFormat = WS_DATE_FORMAT_VALUE;

        /** {@code WS-EDIT-DATE-BINARY PIC S9(9) BINARY} - CSUTLDWY L37. */
        private int editDateBinary;

        /** {@code WS-CURRENT-DATE-BINARY PIC S9(9) BINARY} - CSUTLDWY L42. */
        private int currentDateBinary;

        /** {@code WS-DIV-BY PIC S9(4) COMP-3 VALUE 4} - COACTUPC L152-L153. */
        private int divBy = LEAP_DIVISOR_ORDINARY;

        /** {@code WS-DIVIDEND PIC S9(4) COMP-3 VALUE 0} - COACTUPC L154-L155. */
        private int dividend;

        /** {@code WS-REMAINDER PIC S9(4) COMP-3 VALUE 0} - COACTUPC L157-L158. */
        private int remainder;

        /**
         * Creates a state whose {@code PICTURE} rules are applied by the supplied codec.
         *
         * <p>Prefer {@link AccountDateValidator#newState()}, which hands over the validator's own
         * codec so the state and the validator cannot disagree about the code page - and therefore
         * cannot disagree about where a byte sits in the eighty-byte result area.
         *
         * @param codec the codec to apply; must not be {@code null}
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public EditDateState(FixedWidthCodec codec) {
            this.codec = Objects.requireNonNull(codec, "A FixedWidthCodec is required: every field "
                    + "of CSUTLDWY is positioned by absolute byte offset and moved under a PICTURE "
                    + "rule, and applying those rules is the codec's responsibility");
            this.editDateArea = codec.newRecord(WS_EDIT_DATE_CCYYMMDD_LAYOUT);
            this.dateValidationArea = codec.newRecord(WS_DATE_VALIDATION_RESULT_LAYOUT);
            this.returnMsgArea = codec.newRecord(WS_RETURN_MSG_LAYOUT);
            this.currentDateArea = codec.newRecord(WS_CURRENT_DATE_LAYOUT);
            this.editVariableName = codec.movePicX("", WS_EDIT_VARIABLE_NAME_LENGTH);
        }

        /**
         * Creates a state against {@link StandardCharsets#US_ASCII}, the code page the ASCII fixtures
         * under {@code app/data/ASCII} are held in.
         */
        public EditDateState() {
            this(new FixedWidthCodec(DEFAULT_CHARSET));
        }

        // -----------------------------------------------------------------------------------------
        // WS-EDIT-DATE-CCYYMMDD - the character view. This is what the screen supplies.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code WS-EDIT-DATE-CCYYMMDD} - CSUTLDWY L4.
         *
         * @return exactly eight characters, never trimmed
         */
        public String editDateCcyymmdd() {
            return editDateArea.readString(0, WS_EDIT_DATE_CCYYMMDD_LENGTH);
        }

        /**
         * {@code MOVE <source> TO WS-EDIT-DATE-CCYYMMDD} - the move every one of the four
         * {@code COACTUPC} call sites performs (L1479, L1491, L1504, L1534-L1535).
         *
         * <p>The receiver is an eight-byte alphanumeric group, so the {@code PIC X} rule applies: a
         * shorter value is padded on the right with spaces and a longer one is truncated on the
         * right. A screen field arriving as spaces therefore lands as spaces, which is precisely the
         * input the blank guards at L30, L94 and L154 are looking for.
         *
         * @param source the sending value; may be any length, and may be empty
         * @throws NullPointerException if {@code source} is {@code null}
         */
        public void setEditDateCcyymmdd(String source) {
            editDateArea.writeString(0, WS_EDIT_DATE_CCYYMMDD_LENGTH,
                    codec.movePicX(source, WS_EDIT_DATE_CCYYMMDD_LENGTH));
        }

        /**
         * {@code WS-EDIT-DATE-CC PIC X(2)} - CSUTLDWY L6.
         *
         * @return the two century characters
         */
        public String cc() {
            return codec.readPicX(editDateArea, WS_EDIT_DATE_CC);
        }

        /**
         * {@code MOVE <value> TO WS-EDIT-DATE-CC}.
         *
         * @param value the sending value, padded or truncated on the right to two characters
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setCc(String value) {
            codec.writePicX(editDateArea, WS_EDIT_DATE_CC, value);
        }

        /**
         * {@code WS-EDIT-DATE-YY PIC X(2)} - CSUTLDWY L11.
         *
         * @return the two year-within-century characters
         */
        public String yy() {
            return codec.readPicX(editDateArea, WS_EDIT_DATE_YY);
        }

        /**
         * {@code MOVE <value> TO WS-EDIT-DATE-YY}.
         *
         * @param value the sending value, padded or truncated on the right to two characters
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setYy(String value) {
            codec.writePicX(editDateArea, WS_EDIT_DATE_YY, value);
        }

        /**
         * {@code WS-EDIT-DATE-CCYY} - CSUTLDWY L5. The group the year edit tests at L30, L31 and L48.
         *
         * @return the four century-and-year characters
         */
        public String ccyy() {
            return codec.readPicX(editDateArea, WS_EDIT_DATE_CCYY);
        }

        /**
         * {@code MOVE <value> TO WS-EDIT-DATE-CCYY}.
         *
         * @param value the sending value, padded or truncated on the right to four characters
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setCcyy(String value) {
            codec.writePicX(editDateArea, WS_EDIT_DATE_CCYY, value);
        }

        /**
         * {@code WS-EDIT-DATE-MM PIC X(2)} - CSUTLDWY L16.
         *
         * @return the two month characters
         */
        public String mm() {
            return codec.readPicX(editDateArea, WS_EDIT_DATE_MM);
        }

        /**
         * {@code MOVE <value> TO WS-EDIT-DATE-MM}.
         *
         * @param value the sending value, padded or truncated on the right to two characters
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setMm(String value) {
            codec.writePicX(editDateArea, WS_EDIT_DATE_MM, value);
        }

        /**
         * {@code WS-EDIT-DATE-DD PIC X(2)} - CSUTLDWY L25.
         *
         * @return the two day characters
         */
        public String dd() {
            return codec.readPicX(editDateArea, WS_EDIT_DATE_DD);
        }

        /**
         * {@code MOVE <value> TO WS-EDIT-DATE-DD}.
         *
         * @param value the sending value, padded or truncated on the right to two characters
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setDd(String value) {
            codec.writePicX(editDateArea, WS_EDIT_DATE_DD, value);
        }

        // -----------------------------------------------------------------------------------------
        // WS-EDIT-DATE-CCYYMMDD - the numeric view. This is what every 88 level tests. It reads the
        // same bytes through the zoned DISPLAY interpretation and never throws, because a lettered or
        // blank screen field has to arrive at a flag, not at an exception.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code WS-EDIT-DATE-CC-N PIC 9(2)} - CSUTLDWY L7-L8.
         *
         * @return the zoned value of the two century bytes
         */
        public int ccN() {
            return (int) zonedValueOf(editDateArea.readSpanBytes(WS_EDIT_DATE_CC_N));
        }

        /**
         * {@code WS-EDIT-DATE-YY-N PIC 9(2)} - CSUTLDWY L12-L13. The century-year discriminator the
         * leap-year test reads at L245.
         *
         * @return the zoned value of the two year bytes
         */
        public int yyN() {
            return (int) zonedValueOf(editDateArea.readSpanBytes(WS_EDIT_DATE_YY_N));
        }

        /**
         * {@code WS-EDIT-DATE-CCYY-N PIC 9(4)} - CSUTLDWY L14-L15. The dividend of the leap-year
         * division at L251-L254.
         *
         * @return the zoned value of the four century-and-year bytes
         */
        public int ccyyN() {
            return (int) zonedValueOf(editDateArea.readSpanBytes(WS_EDIT_DATE_CCYY_N));
        }

        /**
         * {@code WS-EDIT-DATE-MM-N PIC 9(2)} - CSUTLDWY L17-L18.
         *
         * @return the zoned value of the two month bytes
         */
        public int mmN() {
            return (int) zonedValueOf(editDateArea.readSpanBytes(WS_EDIT_DATE_MM_N));
        }

        /**
         * {@code COMPUTE WS-EDIT-DATE-MM-N = ...} - CSUTLDPY L127-L129, the normalising store that
         * rewrites the two month bytes as zero-filled digits.
         *
         * @param value the value to store; must not be negative, because {@code PIC 9} has no sign
         *              position - the caller removes the sign first, exactly as a COBOL store into an
         *              unsigned receiver does
         * @throws IllegalArgumentException if {@code value} is negative
         */
        public void setMmN(long value) {
            codec.writePic9(editDateArea, WS_EDIT_DATE_MM_N, value);
        }

        /**
         * {@code WS-EDIT-DATE-DD-N PIC 9(2)} - CSUTLDWY L26-L27.
         *
         * @return the zoned value of the two day bytes
         */
        public int ddN() {
            return (int) zonedValueOf(editDateArea.readSpanBytes(WS_EDIT_DATE_DD_N));
        }

        /**
         * {@code COMPUTE WS-EDIT-DATE-DD-N = ...} - CSUTLDPY L171-L173, the normalising store that
         * rewrites the two day bytes as zero-filled digits.
         *
         * @param value the value to store; must not be negative
         * @throws IllegalArgumentException if {@code value} is negative
         */
        public void setDdN(long value) {
            codec.writePic9(editDateArea, WS_EDIT_DATE_DD_N, value);
        }

        /**
         * {@code WS-EDIT-DATE-CCYYMMDD-N PIC 9(8)} - CSUTLDWY L35-L36.
         *
         * @return the zoned value of all eight bytes, the standard-date argument of
         *         {@code FUNCTION INTEGER-OF-DATE} at L346
         */
        public int ccyymmddN() {
            return (int) zonedValueOf(editDateArea.readSpanBytes(WS_EDIT_DATE_CCYYMMDD_N));
        }

        // -----------------------------------------------------------------------------------------
        // The value 88 levels - app/cpy/CSUTLDWY.cpy L9-L34. One named predicate each, so that no
        // guard in a paragraph reads as a bare numeric comparison.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code 88 THIS-CENTURY VALUE 20} - CSUTLDWY L9.
         *
         * @return whether the century bytes read as 20
         */
        public boolean thisCentury() {
            return ccN() == THIS_CENTURY;
        }

        /**
         * {@code 88 LAST-CENTURY VALUE 19} - CSUTLDWY L10.
         *
         * @return whether the century bytes read as 19
         */
        public boolean lastCentury() {
            return ccN() == LAST_CENTURY;
        }

        /**
         * {@code 88 WS-VALID-MONTH VALUES 1 THROUGH 12} - CSUTLDWY L19-L20.
         *
         * @return whether the month bytes read as 1 through 12 inclusive
         */
        public boolean wsValidMonth() {
            int month = mmN();
            return month >= WS_VALID_MONTH_LOW && month <= WS_VALID_MONTH_HIGH;
        }

        /**
         * {@code 88 WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12} - CSUTLDWY L21-L23.
         *
         * <p>A discrete {@code VALUES} list, not a range: February and the four thirty-day months are
         * absent, which is what makes {@code IF NOT WS-31-DAY-MONTH AND WS-DAY-31} at L213 reject 31
         * April as well as 31 February.
         *
         * @return whether the month bytes read as one of the seven months with thirty-one days
         */
        public boolean ws31DayMonth() {
            int month = mmN();
            for (int candidate : WS_31_DAY_MONTH_VALUES) {
                if (candidate == month) {
                    return true;
                }
            }
            return false;
        }

        /**
         * {@code 88 WS-FEBRUARY VALUE 2} - CSUTLDWY L24.
         *
         * @return whether the month bytes read as 2
         */
        public boolean wsFebruary() {
            return mmN() == WS_FEBRUARY;
        }

        /**
         * {@code 88 WS-VALID-DAY VALUES 1 THROUGH 31} - CSUTLDWY L28-L29.
         *
         * @return whether the day bytes read as 1 through 31 inclusive
         */
        public boolean wsValidDay() {
            int day = ddN();
            return day >= WS_VALID_DAY_LOW && day <= WS_VALID_DAY_HIGH;
        }

        /**
         * {@code 88 WS-DAY-31 VALUE 31} - CSUTLDWY L30.
         *
         * @return whether the day bytes read as 31
         */
        public boolean wsDay31() {
            return ddN() == WS_DAY_31;
        }

        /**
         * {@code 88 WS-DAY-30 VALUE 30} - CSUTLDWY L31.
         *
         * @return whether the day bytes read as 30
         */
        public boolean wsDay30() {
            return ddN() == WS_DAY_30;
        }

        /**
         * {@code 88 WS-DAY-29 VALUE 29} - CSUTLDWY L32.
         *
         * @return whether the day bytes read as 29
         */
        public boolean wsDay29() {
            return ddN() == WS_DAY_29;
        }

        /**
         * {@code 88 WS-VALID-FEB-DAY VALUES 1 THROUGH 28} - CSUTLDWY L33-L34.
         *
         * <p><strong>Declared but never referenced.</strong> No statement in {@code CSUTLDPY} tests
         * it: February is handled by the {@code WS-DAY-30}, {@code WS-DAY-29} and
         * {@code NOT WS-31-DAY-MONTH AND WS-DAY-31} guards instead. It is reproduced because it is
         * part of the copybook's declared contract, and an unreferenced condition name is not dead
         * code to be deleted - deleting it would change the copybook the migration is a translation
         * of. It is offered here so a caller can test what the copybook declares.
         *
         * @return whether the day bytes read as 1 through 28 inclusive
         */
        public boolean wsValidFebDay() {
            int day = ddN();
            return day >= WS_VALID_FEB_DAY_LOW && day <= WS_VALID_FEB_DAY_HIGH;
        }

        // -----------------------------------------------------------------------------------------
        // WS-EDIT-DATE-FLGS - app/cpy/CSUTLDWY.cpy L43-L57, including the two group conditions.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code WS-EDIT-YEAR-FLG} - CSUTLDWY L46.
         *
         * @return the year flag's state
         */
        public EditFlag yearFlag() {
            return yearFlag;
        }

        /**
         * {@code WS-EDIT-MONTH} - CSUTLDWY L50.
         *
         * @return the month flag's state
         */
        public EditFlag monthFlag() {
            return monthFlag;
        }

        /**
         * {@code WS-EDIT-DAY} - CSUTLDWY L54.
         *
         * @return the day flag's state
         */
        public EditFlag dayFlag() {
            return dayFlag;
        }

        /**
         * {@code SET FLG-YEAR-ISVALID | FLG-YEAR-NOT-OK | FLG-YEAR-BLANK TO TRUE}.
         *
         * @param flag the state to set; must not be {@code null}
         * @throws NullPointerException if {@code flag} is {@code null}
         */
        public void setYearFlag(EditFlag flag) {
            this.yearFlag = Objects.requireNonNull(flag, "A year flag state is required");
        }

        /**
         * {@code SET FLG-MONTH-ISVALID | FLG-MONTH-NOT-OK | FLG-MONTH-BLANK TO TRUE}.
         *
         * @param flag the state to set; must not be {@code null}
         * @throws NullPointerException if {@code flag} is {@code null}
         */
        public void setMonthFlag(EditFlag flag) {
            this.monthFlag = Objects.requireNonNull(flag, "A month flag state is required");
        }

        /**
         * {@code SET FLG-DAY-ISVALID | FLG-DAY-NOT-OK | FLG-DAY-BLANK TO TRUE}.
         *
         * @param flag the state to set; must not be {@code null}
         * @throws NullPointerException if {@code flag} is {@code null}
         */
        public void setDayFlag(EditFlag flag) {
            this.dayFlag = Objects.requireNonNull(flag, "A day flag state is required");
        }

        /**
         * {@code 88 FLG-YEAR-ISVALID VALUE LOW-VALUES} - CSUTLDWY L47.
         *
         * @return whether the year flag is {@link EditFlag#ISVALID}
         */
        public boolean flgYearIsvalid() {
            return yearFlag == EditFlag.ISVALID;
        }

        /**
         * {@code 88 FLG-YEAR-NOT-OK VALUE '0'} - CSUTLDWY L48.
         *
         * @return whether the year flag is {@link EditFlag#NOT_OK}
         */
        public boolean flgYearNotOk() {
            return yearFlag == EditFlag.NOT_OK;
        }

        /**
         * {@code 88 FLG-YEAR-BLANK VALUE 'B'} - CSUTLDWY L49.
         *
         * @return whether the year flag is {@link EditFlag#BLANK}
         */
        public boolean flgYearBlank() {
            return yearFlag == EditFlag.BLANK;
        }

        /**
         * {@code 88 FLG-MONTH-ISVALID VALUE LOW-VALUES} - CSUTLDWY L51.
         *
         * @return whether the month flag is {@link EditFlag#ISVALID}
         */
        public boolean flgMonthIsvalid() {
            return monthFlag == EditFlag.ISVALID;
        }

        /**
         * {@code 88 FLG-MONTH-NOT-OK VALUE '0'} - CSUTLDWY L52.
         *
         * @return whether the month flag is {@link EditFlag#NOT_OK}
         */
        public boolean flgMonthNotOk() {
            return monthFlag == EditFlag.NOT_OK;
        }

        /**
         * {@code 88 FLG-MONTH-BLANK VALUE 'B'} - CSUTLDWY L53.
         *
         * @return whether the month flag is {@link EditFlag#BLANK}
         */
        public boolean flgMonthBlank() {
            return monthFlag == EditFlag.BLANK;
        }

        /**
         * {@code 88 FLG-DAY-ISVALID VALUE LOW-VALUES} - CSUTLDWY L55.
         *
         * @return whether the day flag is {@link EditFlag#ISVALID}
         */
        public boolean flgDayIsvalid() {
            return dayFlag == EditFlag.ISVALID;
        }

        /**
         * {@code 88 FLG-DAY-NOT-OK VALUE '0'} - CSUTLDWY L56.
         *
         * @return whether the day flag is {@link EditFlag#NOT_OK}
         */
        public boolean flgDayNotOk() {
            return dayFlag == EditFlag.NOT_OK;
        }

        /**
         * {@code 88 FLG-DAY-BLANK VALUE 'B'} - CSUTLDWY L57.
         *
         * @return whether the day flag is {@link EditFlag#BLANK}
         */
        public boolean flgDayBlank() {
            return dayFlag == EditFlag.BLANK;
        }

        /**
         * {@code WS-EDIT-DATE-FLGS} - CSUTLDWY L43, as the three bytes a group move transfers.
         *
         * <p>This is the value {@code MOVE WS-EDIT-DATE-FLGS TO WS-EDIT-OPEN-DATE-FLGS} and its three
         * siblings move out at {@code COACTUPC:L1482}, {@code L1494}, {@code L1507} and {@code L1538}.
         *
         * @return exactly three characters, in year, month, day order
         */
        public String flagsImage() {
            return String.valueOf(new char[] {
                    yearFlag.flagByte(), monthFlag.flagByte(), dayFlag.flagByte()});
        }

        /**
         * {@code 88 WS-EDIT-DATE-IS-VALID VALUE LOW-VALUES} - CSUTLDWY L44.
         *
         * <p>A group condition over all three flag bytes at once. It is the gate at L274 that decides
         * whether {@code EDIT-DATE-LE} runs at all, and it is what {@code COACTUPC:L1539} tests before
         * performing the birth-date check.
         *
         * @return whether all three flags are {@link EditFlag#ISVALID}
         */
        public boolean wsEditDateIsValid() {
            return flgYearIsvalid() && flgMonthIsvalid() && flgDayIsvalid();
        }

        /**
         * {@code 88 WS-EDIT-DATE-IS-INVALID VALUE '000'} - CSUTLDWY L45.
         *
         * @return whether all three flags are {@link EditFlag#NOT_OK}
         */
        public boolean wsEditDateIsInvalid() {
            return flgYearNotOk() && flgMonthNotOk() && flgDayNotOk();
        }

        /**
         * {@code SET WS-EDIT-DATE-IS-VALID TO TRUE} - CSUTLDPY L327, the group set that clears all
         * three flags at once.
         *
         * <p>Setting a group condition name assigns the group, so this is not shorthand for three
         * individual sets: it overwrites whatever the three flags held, which is exactly why L327
         * discards the {@code NOT-OK} states the error path at L301-L304 had just established.
         */
        public void setWsEditDateIsValid() {
            yearFlag = EditFlag.ISVALID;
            monthFlag = EditFlag.ISVALID;
            dayFlag = EditFlag.ISVALID;
        }

        /**
         * {@code SET WS-EDIT-DATE-IS-INVALID TO TRUE} - CSUTLDPY L19, the first statement of the
         * range, which starts every validation from "all three fields are bad".
         */
        public void setWsEditDateIsInvalid() {
            yearFlag = EditFlag.NOT_OK;
            monthFlag = EditFlag.NOT_OK;
            dayFlag = EditFlag.NOT_OK;
        }

        // -----------------------------------------------------------------------------------------
        // WS-INPUT-FLAG - app/cbl/COACTUPC.cbl L170-L174.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code WS-INPUT-FLAG} - COACTUPC L170.
         *
         * @return the shared input flag's state
         */
        public InputFlag inputFlag() {
            return inputFlag;
        }

        /**
         * {@code 88 INPUT-ERROR VALUE '1'} - COACTUPC L173.
         *
         * @return whether the shared input flag records an error, from this field or an earlier one
         */
        public boolean inputError() {
            return inputFlag == InputFlag.ERROR;
        }

        /**
         * {@code 88 INPUT-OK VALUE '0'} - COACTUPC L172.
         *
         * @return whether the shared input flag records success
         */
        public boolean inputOk() {
            return inputFlag == InputFlag.OK;
        }

        /**
         * {@code 88 INPUT-PENDING VALUE LOW-VALUES} - COACTUPC L174.
         *
         * @return whether the shared input flag is still unset
         */
        public boolean inputPending() {
            return inputFlag == InputFlag.PENDING;
        }

        /** {@code SET INPUT-ERROR TO TRUE} - the eleven error paths of CSUTLDPY. */
        public void setInputError() {
            inputFlag = InputFlag.ERROR;
        }

        /** {@code SET INPUT-OK TO TRUE}, for a caller resetting the shared flag between screens. */
        public void setInputOk() {
            inputFlag = InputFlag.OK;
        }

        /** {@code SET INPUT-PENDING TO TRUE}, the {@code LOW-VALUES} state the flag starts in. */
        public void setInputPending() {
            inputFlag = InputFlag.PENDING;
        }

        // -----------------------------------------------------------------------------------------
        // WS-EDIT-VARIABLE-NAME and WS-RETURN-MSG - app/cbl/COACTUPC.cbl L53 and L479-L480.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code WS-EDIT-VARIABLE-NAME PIC X(25)} - COACTUPC L53.
         *
         * @return exactly twenty-five characters, space-padded, as
         *         {@code FUNCTION TRIM} receives it
         */
        public String editVariableName() {
            return editVariableName;
        }

        /**
         * {@code MOVE '<name>' TO WS-EDIT-VARIABLE-NAME} - COACTUPC L1478, L1490, L1503 and L1533,
         * which supply {@code 'Open Date'}, {@code 'Expiry Date'}, {@code 'Reissue Date'} and
         * {@code 'Date of Birth'} respectively.
         *
         * @param name the field name; padded or truncated on the right to twenty-five characters
         * @throws NullPointerException if {@code name} is {@code null}
         */
        public void setEditVariableName(String name) {
            this.editVariableName = codec.movePicX(name, WS_EDIT_VARIABLE_NAME_LENGTH);
        }

        /**
         * {@code WS-RETURN-MSG PIC X(75)} - COACTUPC L479.
         *
         * @return exactly seventy-five characters, never trimmed
         */
        public String returnMessage() {
            return codec.readPicX(returnMsgArea, WS_RETURN_MSG);
        }

        /**
         * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} - COACTUPC L480.
         *
         * <p>Guards every one of the thirteen message sites in {@code CSUTLDPY}. Because the field is
         * shared across the whole screen and is only blanked once per pass
         * ({@code SET WS-RETURN-MSG-OFF TO TRUE} at {@code COACTUPC:L876}), the first field to fail
         * claims the message and every later failure is silent.
         *
         * @return whether the message slot is still all spaces
         */
        public boolean returnMsgOff() {
            return returnMessage().equals(codec.movePicX("", WS_RETURN_MSG_LENGTH));
        }

        /** {@code SET WS-RETURN-MSG-OFF TO TRUE} - COACTUPC L876: blank the whole seventy-five. */
        public void setReturnMsgOff() {
            codec.writePicX(returnMsgArea, WS_RETURN_MSG, "");
        }

        /**
         * {@code STRING <operands> DELIMITED BY SIZE INTO WS-RETURN-MSG} - the thirteen message sites.
         *
         * <p>Delegated to the codec's {@code STRING} primitive, which transfers from the left and
         * <strong>leaves the rest of the receiver untouched</strong>, exactly as COBOL's {@code STRING}
         * does - it is {@code MOVE}, not {@code STRING}, that space-fills a short sending item. In
         * practice the tail is already spaces, because the guard
         * {@link #returnMsgOff()} only lets the first message through.
         *
         * @param operands the sending items, each contributing its full width; at least one, none
         *                 {@code null}
         * @throws NullPointerException     if {@code operands} or any operand is {@code null}
         * @throws IllegalArgumentException if no operand is supplied
         */
        public void stringIntoReturnMessage(String... operands) {
            codec.stringIntoDelimitedBySize(returnMsgArea, WS_RETURN_MSG, operands);
        }

        // -----------------------------------------------------------------------------------------
        // WS-DATE-FORMAT and WS-DATE-VALIDATION-RESULT - app/cpy/CSUTLDWY.cpy L58-L85.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code WS-DATE-FORMAT PIC X(08)} - CSUTLDWY L58.
         *
         * @return exactly eight characters; {@code 'YYYYMMDD'} unless a caller changed it
         */
        public String dateFormat() {
            return dateFormat;
        }

        /**
         * {@code MOVE 'YYYYMMDD' TO WS-DATE-FORMAT} - CSUTLDPY L291.
         *
         * @param format the mask; padded or truncated on the right to eight characters
         * @throws NullPointerException if {@code format} is {@code null}
         */
        public void setDateFormat(String format) {
            this.dateFormat = codec.movePicX(format, WS_DATE_FORMAT_LENGTH);
        }

        /**
         * {@code INITIALIZE WS-DATE-VALIDATION-RESULT} - CSUTLDPY L290.
         *
         * <p>Blanks the five named character fields and re-establishes the three literal
         * {@code FILLER}s from their {@code VALUE} clauses. COBOL's {@code INITIALIZE} skips
         * {@code FILLER} altogether and so leaves those literals in place; writing them from the
         * declaration reaches the same eighty bytes and additionally proves, on every call, that the
         * layout still sums to eighty.
         */
        public void initializeDateValidationResult() {
            dateValidationArea.initialise(WS_DATE_VALIDATION_RESULT_LAYOUT);
        }

        /**
         * Receives what {@code CSUTLDTC} wrote into {@code WS-DATE-VALIDATION-RESULT}.
         *
         * <p>The COBOL {@code CALL} passes the area by reference, so the callee's
         * {@code MOVE WS-MESSAGE TO LS-RESULT} ({@code CSUTLDTC:L97}) overwrites all eighty bytes of
         * the caller's field in place. This method is that overwrite.
         *
         * @param eightyBytes the result the service produced; must be exactly
         *                    {@link AccountDateValidator#WS_DATE_VALIDATION_RESULT_LENGTH} bytes
         * @throws NullPointerException     if {@code eightyBytes} is {@code null}
         * @throws IllegalArgumentException if the length is not eighty, which would mean this class's
         *                                  transcription of the area and {@code DateUtilityJob}'s
         *                                  have diverged - the one defect that would silently change
         *                                  the error text every caller displays
         */
        public void acceptDateValidationResult(byte[] eightyBytes) {
            Objects.requireNonNull(eightyBytes, "The eighty-byte CSUTLDTC result is required");
            if (eightyBytes.length != WS_DATE_VALIDATION_RESULT_LENGTH) {
                throw new IllegalArgumentException("CSUTLDTC returned " + eightyBytes.length
                        + " byte(s) but LS-RESULT is declared PIC X("
                        + WS_DATE_VALIDATION_RESULT_LENGTH + "); WS-DATE-VALIDATION-RESULT "
                        + "(app/cpy/CSUTLDWY.cpy L60-L85) and WS-MESSAGE (app/cbl/CSUTLDTC.cbl "
                        + "L42-L57) are one area declared twice and must agree byte for byte");
            }
            dateValidationArea.writeBytes(0, eightyBytes);
        }

        /**
         * {@code WS-DATE-VALIDATION-RESULT} - CSUTLDWY L60.
         *
         * @return exactly eighty characters
         */
        public String dateValidationResult() {
            return dateValidationArea.readString(0, WS_DATE_VALIDATION_RESULT_LENGTH);
        }

        /**
         * The eighty bytes of {@code WS-DATE-VALIDATION-RESULT}, for a caller comparing them byte for
         * byte rather than character for character.
         *
         * @return a fresh array of exactly eighty bytes
         */
        public byte[] dateValidationResultBytes() {
            return dateValidationArea.toByteArray();
        }

        /**
         * {@code WS-SEVERITY PIC X(04)} - CSUTLDWY L61. The character view, which is what the L309
         * message fragment transfers.
         *
         * @return exactly four characters
         */
        public String wsSeverity() {
            return codec.readPicX(dateValidationArea, WS_SEVERITY);
        }

        /**
         * {@code WS-SEVERITY-N PIC 9(4)} - CSUTLDWY L62-L63. The numeric view, which is what the
         * decision at L298 tests.
         *
         * @return the zoned value of the four severity bytes; zero when the service reported success
         */
        public int wsSeverityN() {
            return (int) zonedValueOf(dateValidationArea.readSpanBytes(WS_SEVERITY_N));
        }

        /**
         * {@code WS-MSG-NO PIC X(04)} - CSUTLDWY L66. The character view the L311 message fragment
         * transfers.
         *
         * @return exactly four characters
         */
        public String wsMsgNo() {
            return codec.readPicX(dateValidationArea, WS_MSG_NO);
        }

        /**
         * {@code WS-MSG-NO-N Pic 9(4)} - CSUTLDWY L67-L68.
         *
         * @return the zoned value of the four message-number bytes
         */
        public int wsMsgNoN() {
            return (int) zonedValueOf(dateValidationArea.readSpanBytes(WS_MSG_NO_N));
        }

        /**
         * {@code WS-RESULT PIC X(15)} - CSUTLDWY L71: one of the ten verdict texts
         * {@code CSUTLDTC:L128-L149} selects.
         *
         * @return exactly fifteen characters, trailing spaces included
         */
        public String wsResult() {
            return codec.readPicX(dateValidationArea, WS_RESULT);
        }

        /**
         * {@code WS-DATE PIC X(10)} - CSUTLDWY L76, the {@code TstDate:} span.
         *
         * @return exactly ten characters as {@code CSUTLDTC} left them, including the two halfword
         *         bytes its own group move at {@code CSUTLDTC:L122} places in front of the date
         */
        public String wsDate() {
            return codec.readPicX(dateValidationArea, WS_DATE);
        }

        /**
         * {@code WS-DATE-FMT PIC X(10)} - CSUTLDWY L81, the {@code Mask used:} span.
         *
         * @return exactly ten characters
         */
        public String wsDateFmt() {
            return codec.readPicX(dateValidationArea, WS_DATE_FMT);
        }

        // -----------------------------------------------------------------------------------------
        // WS-CURRENT-DATE, WS-EDIT-DATE-BINARY and the three calculation variables.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code WS-CURRENT-DATE-YYYYMMDD PIC X(8)} - CSUTLDWY L39.
         *
         * @return exactly eight characters
         */
        public String currentDateYyyymmdd() {
            return codec.readPicX(currentDateArea, WS_CURRENT_DATE_YYYYMMDD);
        }

        /**
         * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURRENT-DATE-YYYYMMDD} - CSUTLDPY L343.
         *
         * <p>The intrinsic returns twenty-one characters and the receiver holds eight, so the
         * {@code PIC X} rule truncates on the <strong>right</strong>: the date survives and the time
         * and Greenwich offset are discarded. Passing the full twenty-one characters here, rather than
         * a pre-trimmed eight, is what makes that truncation visible and testable.
         *
         * @param intrinsicValue the sending value, typically the twenty-one characters
         *                       {@link AccountDateValidator#currentDateIntrinsic(Clock)} produces
         * @throws NullPointerException if {@code intrinsicValue} is {@code null}
         */
        public void setCurrentDateYyyymmdd(String intrinsicValue) {
            codec.writePicX(currentDateArea, WS_CURRENT_DATE_YYYYMMDD, intrinsicValue);
        }

        /**
         * {@code WS-CURRENT-DATE-YYYYMMDD-N PIC 9(8)} - CSUTLDWY L40-L41.
         *
         * @return the zoned value of the eight current-date bytes, the argument
         *         {@code FUNCTION INTEGER-OF-DATE} receives at L348
         */
        public int currentDateYyyymmddN() {
            return (int) zonedValueOf(currentDateArea.readSpanBytes(WS_CURRENT_DATE_YYYYMMDD_N));
        }

        /**
         * {@code WS-EDIT-DATE-BINARY PIC S9(9) BINARY} - CSUTLDWY L37, set by the
         * {@code COMPUTE} at L345-L346.
         *
         * @return the integer date of the value being edited
         */
        public int editDateBinary() {
            return editDateBinary;
        }

        /**
         * {@code COMPUTE WS-EDIT-DATE-BINARY = FUNCTION INTEGER-OF-DATE (...)} - CSUTLDPY L345-L346.
         *
         * @param integerDate the integer date to store; {@code PIC S9(9)} is signed and holds
         *                    &plusmn;999,999,999, which the 1601-9999 range of the intrinsic cannot
         *                    exceed
         */
        public void setEditDateBinary(int integerDate) {
            this.editDateBinary = integerDate;
        }

        /**
         * {@code WS-CURRENT-DATE-BINARY PIC S9(9) BINARY} - CSUTLDWY L42, set by the
         * {@code COMPUTE} at L347-L348.
         *
         * @return the integer date of today
         */
        public int currentDateBinary() {
            return currentDateBinary;
        }

        /**
         * {@code COMPUTE WS-CURRENT-DATE-BINARY = FUNCTION INTEGER-OF-DATE (...)} - CSUTLDPY
         * L347-L348.
         *
         * @param integerDate the integer date to store
         */
        public void setCurrentDateBinary(int integerDate) {
            this.currentDateBinary = integerDate;
        }

        /**
         * {@code WS-DIV-BY PIC S9(4) COMP-3} - COACTUPC L152, the leap-year divisor chosen at L246 or
         * L248.
         *
         * @return 400 for a century year, 4 otherwise
         */
        public int divBy() {
            return divBy;
        }

        /**
         * {@code MOVE 400 | 4 TO WS-DIV-BY} - CSUTLDPY L246 and L248.
         *
         * @param divisor the divisor to store
         */
        public void setDivBy(int divisor) {
            this.divBy = divisor;
        }

        /**
         * {@code WS-DIVIDEND PIC S9(4) COMP-3} - COACTUPC L154, the quotient of the leap-year
         * division at L251-L254.
         *
         * <p>The name is the copybook's, not a description: {@code GIVING WS-DIVIDEND} makes it the
         * quotient. It is written and never read, and it is reproduced because the store is a
         * statement of the source.
         *
         * @return the quotient of the last leap-year division
         */
        public int dividend() {
            return dividend;
        }

        /**
         * {@code WS-REMAINDER PIC S9(4) COMP-3} - COACTUPC L157, the remainder tested at L256.
         *
         * @return the remainder of the last leap-year division
         */
        public int remainder() {
            return remainder;
        }

        /**
         * {@code DIVIDE WS-EDIT-DATE-CCYY-N BY WS-DIV-BY GIVING WS-DIVIDEND REMAINDER WS-REMAINDER} -
         * CSUTLDPY L251-L254.
         *
         * <p>Integer division on two {@code COMP-3} operands: both results are exact, so no rounding
         * mode arises and no {@code ROUNDED} phrase appears - consistent with the whole codebase, in
         * which {@code ROUNDED} occurs zero times.
         *
         * @param quotient  the value {@code GIVING} stores
         * @param remainder the value {@code REMAINDER} stores
         */
        public void setDivisionResult(int quotient, int remainder) {
            this.dividend = quotient;
            this.remainder = remainder;
        }

        /**
         * A diagnostic rendering, for a failing assertion to print. Control characters in the flag
         * group are shown as hexadecimal so a {@code LOW-VALUE} is visible rather than invisible.
         *
         * @return the edit bytes, the flag group, the input flag and the message slot
         */
        @Override
        public String toString() {
            StringBuilder flags = new StringBuilder();
            for (char flagByte : flagsImage().toCharArray()) {
                flags.append(flagByte == LOW_VALUE ? "<LOW>" : String.valueOf(flagByte));
            }
            return "EditDateState[date='" + editDateCcyymmdd() + "', flags=" + flags
                    + ", inputFlag=" + inputFlag + ", name='" + editVariableName.trim()
                    + "', returnMsg='" + returnMessage().trim() + "']";
        }
    }

    // =================================================================================================
    // Collaborators. Three immutable references and nothing else - no mutable state, static or
    // instance, so one shared instance serves concurrent requests safely.
    // =================================================================================================

    /** Applies every {@code PICTURE} move, pad and truncate rule, and owns the code page. */
    private final FixedWidthCodec codec;

    /**
     * {@code CSUTLDTC}, the called subprogram of {@code EDIT-DATE-LE} - CSUTLDPY L293-L296.
     *
     * <p>This is the <strong>fifth</strong> call site of {@code CSUTLDTC} in the application, and the
     * one a program-level scan misses, because it lives inside a copybook rather than in a program: the
     * other four are {@code CORPT00C:L392}, {@code CORPT00C:L412}, {@code COTRN02C:L393} and
     * {@code COTRN02C:L413}. It is the reason {@code COACTUPC} depends on {@code CSUTLDTC} at all.
     */
    private final DateUtilityJob dateUtility;

    /**
     * The source of "today" for {@code FUNCTION CURRENT-DATE} - CSUTLDPY L343.
     *
     * <p>Held so that the container can supply one and so that a caller need not thread it through
     * every call, but every reading still goes through {@link #currentDateIntrinsic(Clock)} with an
     * explicit clock, which is the convention {@code common/DateHeader} established: nothing in this
     * class calls {@code LocalDate.now()} or any other no-argument clock reader, because a birth-date
     * check whose verdict depends on the wall clock cannot be asserted. Pass
     * {@code Clock.fixed(instant, zone)} to pin it.
     */
    private final Clock clock;

    /**
     * Creates the validator the Spring container wires, against
     * {@link StandardCharsets#US_ASCII} and the system clock.
     *
     * <p>This is the constructor the container selects - it carries {@link Autowired} because the class
     * declares more than one and there is no no-argument candidate to fall back to. No
     * {@link FixedWidthCodec} bean exists in this module, so a codec is created here at the same code
     * page as the sibling components, matching {@code account/AreaCodeLookup} and
     * {@code util/DateUtilityJob}.
     *
     * @param dateUtility the {@code CSUTLDTC} service; must not be {@code null}
     * @throws NullPointerException if {@code dateUtility} is {@code null}
     */
    @Autowired
    public AccountDateValidator(DateUtilityJob dateUtility) {
        this(new FixedWidthCodec(DEFAULT_CHARSET), dateUtility, Clock.systemDefaultZone());
    }

    /**
     * Creates the validator against an explicitly supplied codec and the system clock.
     *
     * @param codec       the codec whose code page positions every byte of the eighty-byte result;
     *                    must not be {@code null}
     * @param dateUtility the {@code CSUTLDTC} service; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public AccountDateValidator(FixedWidthCodec codec, DateUtilityJob dateUtility) {
        this(codec, dateUtility, Clock.systemDefaultZone());
    }

    /**
     * Creates the validator against an explicitly supplied codec and clock. This is the constructor a
     * test uses, because a fixed clock is what makes {@link #editDateOfBirth(EditDateState)}
     * assertable.
     *
     * @param codec       the codec to apply; must not be {@code null}
     * @param dateUtility the {@code CSUTLDTC} service; must not be {@code null}
     * @param clock       the clock {@code FUNCTION CURRENT-DATE} reads; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public AccountDateValidator(FixedWidthCodec codec, DateUtilityJob dateUtility, Clock clock) {
        this.codec = Objects.requireNonNull(codec, "A FixedWidthCodec is required: CSUTLDWY is "
                + "positioned by absolute byte offset and every store obeys a PICTURE rule");
        this.dateUtility = Objects.requireNonNull(dateUtility, "A DateUtilityJob is required: "
                + "EDIT-DATE-LE (app/cpy/CSUTLDPY.cpy L293-L296) calls CSUTLDTC, and without it the "
                + "final validation stage of every date edit cannot run");
        this.clock = Objects.requireNonNull(clock, "A Clock is required: this class never calls a "
                + "no-argument clock reader, so that FUNCTION CURRENT-DATE (L343) is deterministic");
    }

    /**
     * Creates a fresh {@link EditDateState} sharing this validator's codec, and therefore its code
     * page. Preferred over {@code new EditDateState()}, because a state and a validator that disagree
     * about the code page disagree about where a byte sits.
     *
     * @return a state in the condition {@code COACTUPC} presents on entry
     */
    public EditDateState newState() {
        return new EditDateState(codec);
    }

    /**
     * The clock {@code FUNCTION CURRENT-DATE} reads when no clock is supplied per call.
     *
     * @return this validator's clock, never {@code null}
     */
    public Clock clock() {
        return clock;
    }

    // =================================================================================================
    // PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT - the range every caller performs.
    // =================================================================================================

    /**
     * {@code PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT} - the whole paragraph range, as
     * performed at {@code COACTUPC:L1480-L1481}, {@code L1492-L1493}, {@code L1505-L1506} and
     * {@code L1536-L1537}.
     *
     * <p>A range perform executes the paragraphs in <em>physical</em> order and falls through their
     * boundaries, so the eight paragraphs below run in sequence and a {@code GO TO <para>-EXIT} only
     * abandons the remainder of its own paragraph. That is why a blank year does not prevent the month
     * and day edits from running, and it is the single most important thing to preserve here.
     *
     * <p>Two control transfers cross paragraph boundaries and both are reproduced explicitly:
     * <ul>
     *   <li>{@code EDIT-DAY-MONTH-YEAR}'s four {@code GO TO EDIT-DATE-CCYYMMDD-EXIT} sites (L225,
     *       L240, L270, L277) leave the range early. They are the only paths that skip
     *       {@code EDIT-DATE-LE}, and the only paths that skip L327 - so they are the only paths on
     *       which the flags this engine sets actually survive to be read by the caller.</li>
     *   <li>{@code EDIT-DATE-LE}'s {@code GO TO EDIT-DATE-LE-EXIT} (L315) lands on a paragraph whose
     *       {@code EXIT} is a no-op and whose second sentence is
     *       {@code SET WS-EDIT-DATE-IS-VALID TO TRUE} (L327). The set therefore runs on both the
     *       success and the failure path, which resets all three flags to {@code ISVALID} even when
     *       the Language Environment has just rejected the date. {@code INPUT-ERROR} and
     *       {@code WS-RETURN-MSG} are untouched by it, so the diagnostic survives while the flags say
     *       the date is fine. This is preserved deliberately; see the class comment.</li>
     * </ul>
     *
     * <p>On return the caller reads {@link EditDateState#flagsImage()} - the
     * {@code MOVE WS-EDIT-DATE-FLGS TO ...} every call site performs - and, for a birth date, gates
     * {@link #editDateOfBirth(EditDateState)} on {@link EditDateState#wsEditDateIsValid()} exactly as
     * {@code COACTUPC:L1539} does.
     *
     * @param state the per-invocation working storage, with the eight edit bytes and the field name
     *              already moved in; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void editDateCcyymmddThruExit(EditDateState state) {
        Objects.requireNonNull(state, "An EditDateState is required: it is the working storage of "
                + "app/cpy/CSUTLDWY.cpy, which the caller owns for the duration of one validation");

        editDateCcyymmdd(state);                    // L18-L20
        editYearCcyy(state);                        // L25-L87, then L88-L90 EXIT (a no-op)
        editMonth(state);                           // L91-L144, then L145-L147 EXIT
        editDay(state);                             // L150-L204, then L205-L207 EXIT
        if (!editDayMonthYear(state)) {             // L209-L279, then L280-L282 EXIT
            // GO TO EDIT-DATE-CCYYMMDD-EXIT from L225, L240, L270 or L277: EDIT-DATE-LE is skipped and
            // so is L327, so the flags set above are what the caller sees.
            return;
        }

        editDateLe(state);                          // L284-L321

        // L323-L328 EDIT-DATE-LE-EXIT. The EXIT at L324 is a no-op, not a return, and L327 is a
        // separate sentence in the same paragraph - the next paragraph does not start until L329. So
        // this runs on EVERY path that reaches here, the L315 error path included, and the group set
        // discards the three NOT-OK flags that path had just established. Preserved deliberately.
        state.setWsEditDateIsValid();

        // L329-L331 EDIT-DATE-CCYYMMDD-EXIT: EXIT, the end of the performed range.
    }

    /**
     * {@code EDIT-DATE-CCYYMMDD} - CSUTLDPY L18-L20, one statement.
     *
     * <p>{@code SET WS-EDIT-DATE-IS-INVALID TO TRUE} assigns the three-byte group {@code '000'}, so
     * every validation starts from "year, month and day are all bad" and each stage has to clear its
     * own flag. That is also why a state may be reused for a second date: this statement wipes the
     * previous verdict before anything reads it.
     *
     * @param state the working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void editDateCcyymmdd(EditDateState state) {
        Objects.requireNonNull(state, "An EditDateState is required");
        state.setWsEditDateIsInvalid();                                          // L19
    }

    /**
     * {@code EDIT-YEAR-CCYY} - CSUTLDPY L25-L87, with {@code GO TO EDIT-YEAR-CCYY-EXIT} as a
     * {@code return}.
     *
     * <p>Three rejections in source order: not supplied (L30), not four digits (L48), and a century
     * that is neither 19 nor 20 (L70). The middle test is the COBOL <em>class condition</em>
     * {@code IS NOT NUMERIC}, which demands four actual digits and accepts neither a sign, a decimal
     * point nor an embedded space - deliberately stricter than the {@code FUNCTION TEST-NUMVAL} the
     * month and day edits use, and the difference is observable: {@code '19 4'} fails here but would
     * pass {@code TEST-NUMVAL}.
     *
     * <p>The century restriction is the copybook's own joke, at L66-L68: "Not having learnt our lesson
     * from history and Y2K, and being unable to imagine COBOL in the 2100s, we code only 19 and 20 as
     * valid century values." It is a real constraint and is reproduced, not widened.
     *
     * @param state the working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void editYearCcyy(EditDateState state) {
        Objects.requireNonNull(state, "An EditDateState is required");

        state.setYearFlag(EditFlag.NOT_OK);                                      // L27

        // L30-L31 IF WS-EDIT-DATE-CCYY EQUAL LOW-VALUES OR EQUAL SPACES - both figurative constants
        // are tested, and against the four-byte group rather than either child.
        String ccyy = state.ccyy();
        if (isAllLowValues(ccyy) || isAllSpaces(ccyy)) {
            state.setInputError();                                               // L32
            state.setYearFlag(EditFlag.BLANK);                                   // L33
            stringErrorMessage(state, MSG_YEAR_MUST_BE_SUPPLIED);                // L34-L40
            return;                                                              // L42
        }

        // L48 IF WS-EDIT-DATE-CCYY IS NOT NUMERIC.
        if (!isNumericClass(ccyy)) {
            state.setInputError();                                               // L49
            state.setYearFlag(EditFlag.NOT_OK);                                  // L50
            stringErrorMessage(state, MSG_YEAR_MUST_BE_4_DIGITS);                // L51-L57
            return;                                                              // L58
        }

        // L70-L71 IF THIS-CENTURY OR LAST-CENTURY CONTINUE ELSE <reject>. The CONTINUE arm is empty,
        // so the Java form tests the negation of the same disjunction, operands in source order.
        if (!(state.thisCentury() || state.lastCentury())) {
            state.setInputError();                                               // L74
            state.setYearFlag(EditFlag.NOT_OK);                                  // L75
            stringErrorMessage(state, MSG_CENTURY_NOT_VALID);                    // L76-L82
            return;                                                              // L83
        }

        state.setYearFlag(EditFlag.ISVALID);                                     // L86
    }

    /**
     * {@code EDIT-MONTH} - CSUTLDPY L91-L144, with {@code GO TO EDIT-MONTH-EXIT} as a {@code return}.
     *
     * <p><strong>The range test precedes the numeric test here</strong> (L111 before L126), which is
     * the opposite of {@link #editDay(EditDateState)} and is observable. {@code 88 WS-VALID-MONTH} is
     * declared on the numeric overlay of two character bytes, so at L111 it reads the raw zoned value
     * of whatever the screen supplied, before the normalising {@code COMPUTE} at L127 has run:
     * <ul>
     *   <li>{@code ' 5'} reads as 05, passes the range, is normalised to {@code '05'} and is accepted
     *       as May;</li>
     *   <li>{@code '5 '} reads as 50, fails the range and is rejected - even though
     *       {@code FUNCTION TEST-NUMVAL} would have accepted it and {@code FUNCTION NUMVAL} would have
     *       made it 5.</li>
     * </ul>
     * Swapping the two guards would quietly start accepting the second form. Do not.
     *
     * <p>Both rejection paths carry the identical literal, from L119 and L136.
     *
     * @param state the working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void editMonth(EditDateState state) {
        Objects.requireNonNull(state, "An EditDateState is required");

        state.setMonthFlag(EditFlag.NOT_OK);                                     // L92

        // L94-L95 IF WS-EDIT-DATE-MM EQUAL LOW-VALUES OR EQUAL SPACES.
        String mm = state.mm();
        if (isAllLowValues(mm) || isAllSpaces(mm)) {
            state.setInputError();                                               // L96
            state.setMonthFlag(EditFlag.BLANK);                                  // L97
            stringErrorMessage(state, MSG_MONTH_MUST_BE_SUPPLIED);               // L98-L104
            return;                                                              // L105
        }

        // L111 IF WS-VALID-MONTH CONTINUE ELSE <reject>. The raw zoned value, before normalisation.
        if (!state.wsValidMonth()) {
            state.setInputError();                                               // L114
            state.setMonthFlag(EditFlag.NOT_OK);                                 // L115
            stringErrorMessage(state, MSG_MONTH_MUST_BE_1_TO_12);                // L116-L122
            return;                                                              // L123
        }

        // L126 IF FUNCTION TEST-NUMVAL (WS-EDIT-DATE-MM) = 0, then L127-L129
        // COMPUTE WS-EDIT-DATE-MM-N = FUNCTION NUMVAL (WS-EDIT-DATE-MM), which rewrites the two bytes
        // as zero-filled digits.
        NumvalScan scan = scanNumval(mm);
        if (scan.conforms()) {
            state.setMmN(storeIntoUnsigned(scan.value()));                       // L127-L129
        } else {
            state.setInputError();                                               // L131
            state.setMonthFlag(EditFlag.NOT_OK);                                 // L132
            stringErrorMessage(state, MSG_MONTH_MUST_BE_1_TO_12);                // L133-L139
            return;                                                              // L140
        }

        state.setMonthFlag(EditFlag.ISVALID);                                    // L143
    }

    /**
     * {@code EDIT-DAY} - CSUTLDPY L150-L204, with {@code GO TO EDIT-DAY-EXIT} as a {@code return}.
     *
     * <p>Two asymmetries with the year and month edits, both deliberate and both preserved:
     * <ul>
     *   <li>the paragraph <strong>opens</strong> with {@code SET FLG-DAY-ISVALID TO TRUE} (L152) where
     *       the other two open with {@code NOT-OK}. A day edit therefore leaves the flag
     *       {@code ISVALID} unless it explicitly rejects, whereas a year or month edit must reach its
     *       final statement to clear its own flag;</li>
     *   <li>the <strong>numeric test precedes the range test</strong> (L170 before L187), the reverse
     *       of {@link #editMonth(EditDateState)}. So {@code DD = '5 '} is normalised to {@code '05'}
     *       first and then accepted, while {@code MM = '5 '} is rejected.</li>
     * </ul>
     * The final {@code SET FLG-DAY-ISVALID TO TRUE} at L203 is a second, redundant set of the state
     * L152 already established; it is reproduced because it is a statement of the source and because
     * a reader comparing the two files should find them the same length.
     *
     * <p>Both rejection paths carry the identical literal, from L180 and L195, with a lower-case
     * "day".
     *
     * @param state the working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void editDay(EditDateState state) {
        Objects.requireNonNull(state, "An EditDateState is required");

        state.setDayFlag(EditFlag.ISVALID);                                      // L152

        // L154-L155 IF WS-EDIT-DATE-DD EQUAL LOW-VALUES OR EQUAL SPACES.
        String dd = state.dd();
        if (isAllLowValues(dd) || isAllSpaces(dd)) {
            state.setInputError();                                               // L156
            state.setDayFlag(EditFlag.BLANK);                                    // L157
            stringErrorMessage(state, MSG_DAY_MUST_BE_SUPPLIED);                 // L158-L164
            return;                                                              // L165
        }

        // L170 IF FUNCTION TEST-NUMVAL (WS-EDIT-DATE-DD) = 0, then L171-L173
        // COMPUTE WS-EDIT-DATE-DD-N = FUNCTION NUMVAL (WS-EDIT-DATE-DD).
        NumvalScan scan = scanNumval(dd);
        if (scan.conforms()) {
            state.setDdN(storeIntoUnsigned(scan.value()));                       // L171-L173
        } else {
            state.setInputError();                                               // L175
            state.setDayFlag(EditFlag.NOT_OK);                                   // L176
            stringErrorMessage(state, MSG_DAY_MUST_BE_1_TO_31);                  // L177-L183
            return;                                                              // L184
        }

        // L187 IF WS-VALID-DAY CONTINUE ELSE <reject>, on the value L171 has just normalised.
        if (!state.wsValidDay()) {
            state.setInputError();                                               // L190
            state.setDayFlag(EditFlag.NOT_OK);                                   // L191
            stringErrorMessage(state, MSG_DAY_MUST_BE_1_TO_31);                  // L192-L198
            return;                                                              // L199
        }

        state.setDayFlag(EditFlag.ISVALID);                                      // L203
    }

    /**
     * {@code EDIT-DAY-MONTH-YEAR} - CSUTLDPY L209-L279: the combination checks no single-field edit
     * can make.
     *
     * <p>Three rejections in source order, then the gate:
     * <ol>
     *   <li>L213 {@code IF NOT WS-31-DAY-MONTH AND WS-DAY-31}. Because
     *       {@code 88 WS-31-DAY-MONTH} is a discrete {@code VALUES} list rather than a range, this one
     *       guard rejects 31 April and 31 June as well as 31 February. It marks the day
     *       <em>and</em> the month {@code NOT-OK}, because either could be the mistake;</li>
     *   <li>L228 {@code IF WS-FEBRUARY AND WS-DAY-30};</li>
     *   <li>L243 {@code IF WS-FEBRUARY AND WS-DAY-29}, which runs the leap-year test: the divisor is
     *       400 when {@code WS-EDIT-DATE-YY-N} is zero and 4 otherwise (L245-L249), the four-digit year
     *       is divided by it (L251-L254), and a non-zero remainder rejects (L256). Over the only
     *       century values the year edit admits, 19 and 20, that reproduces the Gregorian rule exactly:
     *       2000 divides by 400 and is a leap year, 1900 does not and is not, and 2020 and 2021 are
     *       decided by the divisor 4. All three flags are marked {@code NOT-OK} here, the year
     *       included, since the year is genuinely part of the verdict.</li>
     * </ol>
     * The gate at L274 - {@code IF WS-EDIT-DATE-IS-VALID CONTINUE ELSE GO TO EDIT-DATE-CCYYMMDD-EXIT}
     * - is a group test over all three flag bytes. Any earlier stage that left a {@code '0'} or a
     * {@code 'B'} stops the range here, which both saves the {@code CSUTLDTC} call and preserves the
     * flags for the caller.
     *
     * @param state the working storage; must not be {@code null}
     * @return {@code true} if control fell through to {@code EDIT-DATE-LE}, {@code false} if the
     *         paragraph took one of its four {@code GO TO EDIT-DATE-CCYYMMDD-EXIT} branches - the
     *         boolean is the explicit form of a jump that leaves the performed range
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public boolean editDayMonthYear(EditDateState state) {
        Objects.requireNonNull(state, "An EditDateState is required");

        // L213-L214 IF NOT WS-31-DAY-MONTH AND WS-DAY-31.
        if (!state.ws31DayMonth() && state.wsDay31()) {
            state.setInputError();                                               // L215
            state.setDayFlag(EditFlag.NOT_OK);                                   // L216
            state.setMonthFlag(EditFlag.NOT_OK);                                 // L217
            stringErrorMessage(state, MSG_CANNOT_HAVE_31_DAYS);                  // L218-L224
            return false;                                                        // L225
        }

        // L228-L229 IF WS-FEBRUARY AND WS-DAY-30.
        if (state.wsFebruary() && state.wsDay30()) {
            state.setInputError();                                               // L230
            state.setDayFlag(EditFlag.NOT_OK);                                   // L231
            state.setMonthFlag(EditFlag.NOT_OK);                                 // L232
            stringErrorMessage(state, MSG_CANNOT_HAVE_30_DAYS);                  // L233-L239
            return false;                                                        // L240
        }

        // L243-L244 IF WS-FEBRUARY AND WS-DAY-29.
        if (state.wsFebruary() && state.wsDay29()) {
            // L245-L249 IF WS-EDIT-DATE-YY-N = 0 MOVE 400 TO WS-DIV-BY ELSE MOVE 4 TO WS-DIV-BY.
            if (state.yyN() == CENTURY_YEAR_YY) {
                state.setDivBy(LEAP_DIVISOR_CENTURY);                            // L246
            } else {
                state.setDivBy(LEAP_DIVISOR_ORDINARY);                           // L248
            }

            // L251-L254 DIVIDE WS-EDIT-DATE-CCYY-N BY WS-DIV-BY GIVING WS-DIVIDEND REMAINDER
            // WS-REMAINDER. Integer division of two COMP-3 operands: exact, so no ROUNDED phrase
            // arises - and none appears anywhere in the twenty-eight programs.
            int year = state.ccyyN();
            int divisor = state.divBy();
            state.setDivisionResult(year / divisor, year % divisor);

            // L256 IF WS-REMAINDER = ZEROES CONTINUE ELSE <reject>.
            if (state.remainder() != LEAP_REMAINDER_OK) {
                state.setInputError();                                           // L259
                state.setDayFlag(EditFlag.NOT_OK);                               // L260
                state.setMonthFlag(EditFlag.NOT_OK);                             // L261
                state.setYearFlag(EditFlag.NOT_OK);                              // L262
                stringErrorMessage(state, MSG_NOT_A_LEAP_YEAR);                  // L263-L269
                return false;                                                    // L270
            }
        }

        // L274-L278 IF WS-EDIT-DATE-IS-VALID CONTINUE ELSE GO TO EDIT-DATE-CCYYMMDD-EXIT.
        if (!state.wsEditDateIsValid()) {
            return false;                                                        // L277
        }

        return true;                                                             // fall through to L284
    }

    /**
     * {@code EDIT-DATE-LE} - CSUTLDPY L284-L321: the last resort, "in case some one managed to enter a
     * bad date that passsed all the edits above" (L286-L287, the source's own spelling).
     *
     * <p>Calls {@code CSUTLDTC} through {@link DateUtilityJob}, which is why this class needs that
     * collaborator at all. Three details of the call are contractual:
     * <ul>
     *   <li><strong>The argument widths do not match, and that is faithful.</strong>
     *       {@code WS-EDIT-DATE-CCYYMMDD} is eight bytes and {@code WS-DATE-FORMAT} is
     *       {@code PIC X(08)}, while {@code CSUTLDTC} declares {@code LS-DATE PIC X(10)} and
     *       {@code LS-DATE-FORMAT PIC X(10)} ({@code CSUTLDTC:L84-L85}). A COBOL {@code CALL ... USING}
     *       passes by reference with no length coercion, so the callee reads ten bytes over an
     *       eight-byte field and the two extra bytes are whatever storage follows. Rather than leave
     *       that to chance, both arguments are widened here to ten with the {@code PIC X} rule - space
     *       padded on the right - which is the same normalisation {@link DateUtilityJob} applies to
     *       anything it is handed, so the two classes agree. The widened mask {@code 'YYYYMMDD  '} is
     *       accepted because trailing spaces in a picture string are literal delimiters that the
     *       widened date's own trailing spaces match. The widths are <em>not</em> "corrected" to ten in
     *       the copybook model, because the copybook says eight.</li>
     *   <li>The eighty-byte result is written back into {@code WS-DATE-VALIDATION-RESULT}, because the
     *       parameter is passed by reference and {@code CSUTLDTC:L97} moves its own
     *       {@code WS-MESSAGE} into it. The severity is then read back through <em>this</em> class's
     *       offsets, which is what proves the two declarations of that one area agree.</li>
     *   <li>The message at L306-L313 has five operands, not two, and takes the severity and message
     *       number through their <em>character</em> views - four characters each, zero-filled by the
     *       service - so the text reads {@code "... Sev code: 0003 Message code: 2508"}.</li>
     * </ul>
     *
     * <p>The {@code IF NOT INPUT-ERROR} at L318 tests the flag <em>shared with every other field on
     * the screen</em>, so a date that validates cleanly still leaves {@code FLG-DAY-ISVALID} unset if
     * an earlier field failed. That cross-field coupling is real and is preserved.
     *
     * <p>Note what this method does <strong>not</strong> do: it does not perform the
     * {@code SET WS-EDIT-DATE-IS-VALID TO TRUE} at L327. That statement belongs to
     * {@code EDIT-DATE-LE-EXIT}, a different paragraph, and lives in
     * {@link #editDateCcyymmddThruExit(EditDateState)} where the fall-through puts it.
     *
     * @param state the working storage; must not be {@code null}
     * @throws NullPointerException     if {@code state} is {@code null}
     * @throws IllegalArgumentException if the service returns other than eighty bytes
     */
    public void editDateLe(EditDateState state) {
        Objects.requireNonNull(state, "An EditDateState is required");

        state.initializeDateValidationResult();                                  // L290
        state.setDateFormat(WS_DATE_FORMAT_VALUE);                               // L291

        // L293-L296 CALL 'CSUTLDTC' USING WS-EDIT-DATE-CCYYMMDD, WS-DATE-FORMAT,
        // WS-DATE-VALIDATION-RESULT. BY REFERENCE, so the eight-byte and eight-character arguments are
        // read as the ten each linkage item declares; both are widened deliberately.
        DateValidationResult result = dateUtility.validateDate(
                codec.movePicX(state.editDateCcyymmdd(), DateUtilityJob.LS_DATE_LENGTH),
                codec.movePicX(state.dateFormat(), DateUtilityJob.LS_DATE_FORMAT_LENGTH));
        state.acceptDateValidationResult(result.messageBytes());

        // L298 IF WS-SEVERITY-N = 0 CONTINUE ELSE <reject>.
        if (state.wsSeverityN() != SEVERITY_OK) {
            state.setInputError();                                               // L301
            state.setDayFlag(EditFlag.NOT_OK);                                   // L302
            state.setMonthFlag(EditFlag.NOT_OK);                                 // L303
            state.setYearFlag(EditFlag.NOT_OK);                                  // L304
            if (state.returnMsgOff()) {                                          // L305
                state.stringIntoReturnMessage(                                   // L306-L313
                        trim(state.editVariableName()),
                        MSG_VALIDATION_ERROR_SEV_CODE,
                        state.wsSeverity(),
                        MSG_MESSAGE_CODE,
                        state.wsMsgNo());
            }
            return;                                                              // L315
        }

        // L318-L320 IF NOT INPUT-ERROR SET FLG-DAY-ISVALID TO TRUE. The flag is shared across the
        // whole screen, so an earlier field's failure suppresses this.
        if (!state.inputError()) {
            state.setDayFlag(EditFlag.ISVALID);                                  // L319
        }
    }

    // =================================================================================================
    // PERFORM EDIT-DATE-OF-BIRTH THRU EDIT-DATE-OF-BIRTH-EXIT - app/cbl/COACTUPC.cbl L1540-L1541.
    // =================================================================================================

    /**
     * {@code EDIT-DATE-OF-BIRTH} - CSUTLDPY L341-L368, reading this validator's own clock.
     *
     * @param state the working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     * @see #editDateOfBirth(EditDateState, Clock)
     */
    public void editDateOfBirth(EditDateState state) {
        editDateOfBirth(state, clock);
    }

    /**
     * {@code EDIT-DATE-OF-BIRTH} - CSUTLDPY L341-L368, against an explicit clock.
     *
     * <p>{@code PERFORM EDIT-DATE-OF-BIRTH THRU EDIT-DATE-OF-BIRTH-EXIT} ({@code COACTUPC:L1540-L1541})
     * is a range over one paragraph and its own bare {@code EXIT}, so it collapses to this one method.
     * {@code COACTUPC} performs it only when the flag group came back {@code ISVALID} (L1539) - which,
     * because of the L327 fall-through, includes the case where {@code CSUTLDTC} rejected the date.
     *
     * <p>Three things here are easy to get wrong and are each pinned by a test:
     * <ol>
     *   <li>L343 {@code MOVE FUNCTION CURRENT-DATE TO WS-CURRENT-DATE-YYYYMMDD}. The intrinsic yields
     *       twenty-one characters and the receiver holds eight, so the alphanumeric move truncates on
     *       the <strong>right</strong>: the date is kept and the time and Greenwich offset are
     *       discarded. The full twenty-one are built and handed over so the truncation is real rather
     *       than assumed.</li>
     *   <li>L345-L348, two {@code FUNCTION INTEGER-OF-DATE} conversions. Day 1 is 1601-01-01; an
     *       off-by-one in that epoch silently changes the verdict for a birth date of exactly
     *       yesterday or exactly today.</li>
     *   <li>L350 {@code IF WS-CURRENT-DATE-BINARY &gt; WS-EDIT-DATE-BINARY}. The comparison is
     *       <strong>strict</strong>, so a birth date equal to today is rejected as being in the
     *       future. That is the source's behaviour and it is preserved; the Java form below tests the
     *       negation, {@code &lt;=}, for the rejection path.</li>
     * </ol>
     * The commented-out {@code FUNCTION FIND-DURATION} alternative at L351-L353 stays absent: it is not
     * code, and reinstating it would be a redesign.
     *
     * <p>All three flags and the shared input flag are marked on rejection, and the message at
     * L361-L365 has exactly two operands - the trimmed field name and {@code ':cannot be in the future
     * '}, whose leading colon carries no space and whose single trailing space is significant.
     *
     * @param state the working storage, holding the eight date bytes to test; must not be {@code null}
     * @param clock the clock {@code FUNCTION CURRENT-DATE} reads; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public void editDateOfBirth(EditDateState state, Clock clock) {
        Objects.requireNonNull(state, "An EditDateState is required");
        Objects.requireNonNull(clock, "A Clock is required to evaluate FUNCTION CURRENT-DATE");

        state.setCurrentDateYyyymmdd(currentDateIntrinsic(clock));               // L343

        state.setEditDateBinary(integerOfDate(state.ccyymmddN()));               // L345-L346
        state.setCurrentDateBinary(integerOfDate(state.currentDateYyyymmddN())); // L347-L348

        // L350 IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY CONTINUE ELSE <reject>. Strict, so
        // today is rejected; the negation below is therefore <= and not <.
        if (state.currentDateBinary() <= state.editDateBinary()) {
            state.setInputError();                                               // L356
            state.setDayFlag(EditFlag.NOT_OK);                                   // L357
            state.setMonthFlag(EditFlag.NOT_OK);                                 // L358
            state.setYearFlag(EditFlag.NOT_OK);                                  // L359
            stringErrorMessage(state, MSG_CANNOT_BE_IN_THE_FUTURE);              // L360-L366
            return;                                                              // L367
        }

        // L354 CONTINUE: the paragraph has nothing further, and L370-L372
        // EDIT-DATE-OF-BIRTH-EXIT is a bare EXIT.
    }

    // =================================================================================================
    // The COBOL intrinsics and figurative-constant tests this engine relies on. None is available from
    // the module's dependencies, so each is implemented here, named after the function it reproduces.
    // =================================================================================================

    /**
     * {@code FUNCTION INTEGER-OF-DATE} - CSUTLDPY L346 and L348.
     *
     * <p>Converts a standard date in {@code YYYYMMDD} form to the count of days since 31 December
     * 1600, so that 1601-01-01 is 1. Implemented as
     * {@code LocalDate.toEpochDay() + }{@link #INTEGER_OF_DATE_EPOCH_OFFSET}, verified against
     * 1601-01-01&nbsp;&rarr;&nbsp;1, 1601-12-31&nbsp;&rarr;&nbsp;365, 1900-01-01&nbsp;&rarr;&nbsp;109208,
     * 2000-01-01&nbsp;&rarr;&nbsp;145732 and 2099-12-31&nbsp;&rarr;&nbsp;182256.
     *
     * <p>An argument outside {@code 1601-01-01} to {@code 9999-12-31}, or one that names no real
     * calendar day such as {@code 20220231}, is not a standard date and COBOL leaves the result
     * undefined. This method returns {@link #INTEGER_OF_DATE_UNDEFINED} for those, deterministically
     * and without throwing, because the only caller's next statement is a comparison and an exception
     * there would abandon a screen edit that the COBOL completes.
     *
     * @param standardDate the date as {@code 9(8)}, that is {@code year * 10000 + month * 100 + day}
     * @return the integer date, or {@link #INTEGER_OF_DATE_UNDEFINED} if the argument is not a standard
     *         date in the supported range
     */
    public static int integerOfDate(int standardDate) {
        if (standardDate < INTEGER_OF_DATE_LOWEST_ARGUMENT
                || standardDate > INTEGER_OF_DATE_HIGHEST_ARGUMENT) {
            return INTEGER_OF_DATE_UNDEFINED;
        }
        int year = standardDate / STANDARD_DATE_YEAR_DIVISOR;
        int month = standardDate / STANDARD_DATE_MONTH_DIVISOR % STANDARD_DATE_COMPONENT_MODULUS;
        int day = standardDate % STANDARD_DATE_COMPONENT_MODULUS;
        try {
            return (int) (LocalDate.of(year, month, day).toEpochDay())
                    + INTEGER_OF_DATE_EPOCH_OFFSET;
        } catch (DateTimeException notAStandardDate) {
            // 31 February and its relatives reach here. COBOL's result is undefined; ours is defined.
            return INTEGER_OF_DATE_UNDEFINED;
        }
    }

    /**
     * {@code FUNCTION CURRENT-DATE} - CSUTLDPY L343.
     *
     * <p>Produces the intrinsic's full twenty-one characters:
     * {@code YYYYMMDD} then {@code hhmmsshh} - the last pair being hundredths of a second - then the
     * sign of the offset from Greenwich and that offset as {@code HHMM}. The caller moves the result
     * into {@code PIC X(8)}, which discards the last thirteen; they are produced anyway so the
     * truncation is a real move rather than an assumption, and so a test can assert the width.
     *
     * <p>Every component is rendered through the codec's {@code PIC 9} move, which zero-fills on the
     * left from {@code Long.toString}, so no default locale can shape a digit and no
     * {@code DateTimeFormatter} pattern can drift.
     *
     * @param clock the clock to read, once; must not be {@code null}
     * @return exactly {@link #CURRENT_DATE_INTRINSIC_LENGTH} characters
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public String currentDateIntrinsic(Clock clock) {
        Objects.requireNonNull(clock, "A Clock is required: FUNCTION CURRENT-DATE is read from a "
                + "supplied clock exactly once so that its value is deterministic");
        ZonedDateTime now = ZonedDateTime.now(clock);
        int offsetMinutes = now.getOffset().getTotalSeconds() / SECONDS_PER_MINUTE;
        char offsetSign = offsetMinutes < 0 ? OFFSET_SIGN_BEHIND : OFFSET_SIGN_AHEAD;
        int absoluteOffsetMinutes = Math.abs(offsetMinutes);
        return codec.concatenateDelimitedBySize(
                codec.movePic9(now.getYear() * STANDARD_DATE_YEAR_DIVISOR
                                + now.getMonthValue() * STANDARD_DATE_MONTH_DIVISOR
                                + now.getDayOfMonth(),
                        WS_EDIT_DATE_CCYYMMDD_LENGTH),
                codec.movePic9(now.getHour(), TIME_COMPONENT_LENGTH),
                codec.movePic9(now.getMinute(), TIME_COMPONENT_LENGTH),
                codec.movePic9(now.getSecond(), TIME_COMPONENT_LENGTH),
                codec.movePic9(now.getNano() / NANOS_PER_HUNDREDTH, TIME_COMPONENT_LENGTH),
                String.valueOf(offsetSign),
                codec.movePic9(absoluteOffsetMinutes / MINUTES_PER_HOUR, TIME_COMPONENT_LENGTH),
                codec.movePic9(absoluteOffsetMinutes % MINUTES_PER_HOUR, TIME_COMPONENT_LENGTH));
    }

    /**
     * {@code FUNCTION TRIM} - CSUTLDPY L36, L53, L78, L100, L118, L135, L160, L179, L194, L220, L235,
     * L265, L307 and L362.
     *
     * <p>With no {@code LEADING} or {@code TRAILING} phrase, {@code FUNCTION TRIM} removes spaces from
     * <strong>both</strong> ends. Only the space character is removed - not tabs, not
     * {@code LOW-VALUES}, not any other character {@code Character.isWhitespace} would accept - because
     * that is what the function does and because the argument here,
     * {@code WS-EDIT-VARIABLE-NAME PIC X(25)}, is space-padded by definition.
     *
     * @param value the sending item; must not be {@code null}
     * @return {@code value} without its leading or trailing spaces, possibly empty
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static String trim(String value) {
        Objects.requireNonNull(value, "FUNCTION TRIM requires an argument");
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == SPACE) {
            start++;
        }
        while (end > start && value.charAt(end - 1) == SPACE) {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * {@code FUNCTION TEST-NUMVAL} - CSUTLDPY L126 and L170.
     *
     * @param image the argument to test; must not be {@code null}
     * @return zero if the argument is a valid operand of {@link #numval(String)}; otherwise the
     *         one-based position of the first character in error, or the argument's length plus one when
     *         it holds no digits at all - all spaces, a bare sign or a bare decimal point
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static int testNumval(String image) {
        return scanNumval(image).errorPosition();
    }

    /**
     * {@code FUNCTION NUMVAL} - CSUTLDPY L128 and L172.
     *
     * <p>Returns the numeric value of a character representation, as a {@link BigDecimal} so that a
     * fractional argument keeps every digit - never as {@code double} or {@code float}, which cannot
     * represent a decimal fraction exactly.
     *
     * @param image the argument to convert; must not be {@code null}
     * @return the value the argument denotes, or zero when the argument is not a valid operand. COBOL
     *         leaves the latter undefined, and zero is chosen because it is unreachable in this
     *         engine: both call sites test {@link #testNumval(String)} first and take the rejection
     *         branch instead
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static BigDecimal numval(String image) {
        return scanNumval(image).value();
    }

    /**
     * Tests the COBOL class condition {@code IS NUMERIC} on an alphanumeric item - CSUTLDPY L48,
     * negated.
     *
     * <p>For an item declared {@code PIC X}, the condition holds only when every character is a digit.
     * A sign, a decimal point and an embedded or trailing space all fail it, which is what makes the
     * year edit stricter than the month and day edits. An empty item cannot arise from a fixed-width
     * span, and is reported as not numeric.
     *
     * @param image the item's characters; must not be {@code null}
     * @return whether every character is {@code '0'} through {@code '9'} and there is at least one
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static boolean isNumericClass(String image) {
        Objects.requireNonNull(image, "A class condition requires an item to test");
        if (image.isEmpty()) {
            return false;
        }
        for (int index = 0; index < image.length(); index++) {
            if (!isDigit(image.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests a field against the figurative constant {@code SPACES} - CSUTLDPY L31, L95 and L155.
     *
     * @param image the field's characters; must not be {@code null}
     * @return whether every character is a space, and there is at least one
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static boolean isAllSpaces(String image) {
        return isEveryCharacter(image, SPACE);
    }

    /**
     * Tests a field against the figurative constant {@code LOW-VALUES} - CSUTLDPY L30, L94 and L154.
     *
     * <p>{@code LOW-VALUE} is the byte with every bit clear, which is a distinct state from a space and
     * is exactly what a CICS map delivers for a field the terminal never transmitted. Both constants
     * are tested at each of the three blank guards, and both must be, because either can arrive.
     *
     * @param image the field's characters; must not be {@code null}
     * @return whether every character is {@code LOW-VALUE}, and there is at least one
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static boolean isAllLowValues(String image) {
        return isEveryCharacter(image, LOW_VALUE);
    }

    /**
     * The shared body of {@link #isAllSpaces(String)} and {@link #isAllLowValues(String)}.
     *
     * @param image     the field's characters
     * @param character the figurative constant's byte
     * @return whether the field consists wholly of that character
     */
    private static boolean isEveryCharacter(String image, char character) {
        Objects.requireNonNull(image, "A figurative-constant comparison requires a field to test");
        if (image.isEmpty()) {
            return false;
        }
        for (int index = 0; index < image.length(); index++) {
            if (image.charAt(index) != character) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads a span as a zoned {@code DISPLAY} number - the {@code REDEFINES ... PIC 9(n)} view that
     * every {@code 88} level in {@code CSUTLDWY} is declared on.
     *
     * <p>A zoned {@code DISPLAY} digit is the low-order four bits of its byte, and the zone in the high
     * four bits is ignored on a read. That is why the interpretation is encoding-independent for the
     * characters that matter: {@code '0'} to {@code '9'} yield 0 to 9 under both US-ASCII
     * ({@code 0x30}-{@code 0x39}) and EBCDIC ({@code 0xF0}-{@code 0xF9}), and both a space
     * ({@code 0x20} or {@code 0x40}) and a {@code LOW-VALUE} ({@code 0x00}) yield zero.
     *
     * <p>It <strong>never throws</strong>, and that is the point.
     * {@link FixedWidthCodec#decodePic9(String)} deliberately rejects a non-digit, because a dataset
     * span that is not digits is a data or offset defect worth failing on; but this view is over a
     * <em>screen</em> field, where a letter or a blank is ordinary user input whose correct outcome is a
     * {@code '0'} or {@code 'B'} flag. On an all-digit span the two agree exactly, which a test
     * asserts. A byte whose low nibble exceeds nine is an invalid digit code that IBM leaves
     * undefined; the nibble's value is contributed, deterministically, and no reachable input produces
     * one.
     *
     * @param span the span's bytes
     * @return the value the zoned digits denote; for the widest span here, eight bytes, it cannot
     *         exceed 166,666,665 even for invalid digit codes, so an {@code int} always holds it
     */
    private static long zonedValueOf(byte[] span) {
        long value = 0;
        for (byte zonedByte : span) {
            value = value * DECIMAL_RADIX + (zonedByte & ZONED_DIGIT_MASK);
        }
        return value;
    }

    /**
     * {@code COMPUTE <unsigned PIC 9 receiver> = FUNCTION NUMVAL (...)} - CSUTLDPY L127-L129 and
     * L171-L173.
     *
     * <p>Two COBOL store rules apply, in this order. First, the receiver has no fractional digits and
     * the statement carries no {@code ROUNDED} phrase - as no statement in any of the twenty-eight
     * programs does - so the excess fractional digits are <strong>truncated</strong>, which is
     * {@link RoundingMode#DOWN} and never {@code HALF_UP} or {@code HALF_EVEN}. Second,
     * {@code PIC 9} is unsigned and has no sign position, so what is stored is the magnitude:
     * {@code '-1'} in a two-byte day field becomes {@code '01'}.
     *
     * @param value the value {@code FUNCTION NUMVAL} produced
     * @return the non-negative integral value to store
     */
    private static long storeIntoUnsigned(BigDecimal value) {
        return value.setScale(0, RoundingMode.DOWN).abs().longValue();
    }

    /**
     * The shared engine of {@link #testNumval(String)} and {@link #numval(String)}: one pass that both
     * validates and converts, because {@code TEST-NUMVAL} and {@code NUMVAL} are defined over the same
     * grammar and the source always calls them as a pair.
     *
     * <p>The accepted form is a leading run of spaces, then either a leading sign or nothing, then
     * digits with at most one decimal point among them, then trailing spaces, then - only if no leading
     * sign was given - a trailing {@code +}, {@code -}, {@code CR} or {@code DB}, then trailing spaces,
     * then the end of the item. {@code CR} and {@code DB} are matched in upper case only, as the
     * standard defines them. Anything else is reported at the one-based position of the offending
     * character; an item with no digits at all is reported at its length plus one.
     *
     * @param image the argument; must not be {@code null}
     * @return the outcome: a conforming scan carrying the value, or a rejection carrying the position
     * @throws NullPointerException if {@code image} is {@code null}
     */
    private static NumvalScan scanNumval(String image) {
        Objects.requireNonNull(image, "FUNCTION TEST-NUMVAL and FUNCTION NUMVAL require an argument");
        int length = image.length();
        int index = skipSpaces(image, 0);

        boolean negative = false;
        boolean leadingSignSeen = false;
        if (index < length && isSign(image.charAt(index))) {
            negative = image.charAt(index) == MINUS_SIGN;
            leadingSignSeen = true;
            index = skipSpaces(image, index + 1);
        }

        StringBuilder digits = new StringBuilder();
        int fractionDigits = 0;
        boolean decimalPointSeen = false;
        while (index < length) {
            char character = image.charAt(index);
            if (isDigit(character)) {
                digits.append(character);
                if (decimalPointSeen) {
                    fractionDigits++;
                }
                index++;
            } else if (character == DECIMAL_POINT && !decimalPointSeen) {
                decimalPointSeen = true;
                index++;
            } else {
                break;
            }
        }
        if (digits.length() == 0) {
            // No digits anywhere: all spaces, a bare sign, or a bare decimal point. IBM reports this
            // at the length plus one rather than at a character position.
            return NumvalScan.rejected(length + 1);
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
            return NumvalScan.rejected(index + 1);
        }

        BigDecimal magnitude = new BigDecimal(digits.toString())
                .movePointLeft(fractionDigits);
        return NumvalScan.accepted(negative ? magnitude.negate() : magnitude);
    }

    /**
     * Advances past a run of spaces, which {@code FUNCTION NUMVAL} permits at three places in its
     * argument.
     *
     * @param image the argument
     * @param from  the index to start at
     * @return the index of the first character at or after {@code from} that is not a space, or the
     *         argument's length
     */
    private static int skipSpaces(String image, int from) {
        int index = from;
        while (index < image.length() && image.charAt(index) == SPACE) {
            index++;
        }
        return index;
    }

    /**
     * @param character the character to classify
     * @return whether it is {@code '0'} through {@code '9'}
     */
    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    /**
     * @param character the character to classify
     * @return whether it is a sign {@code FUNCTION NUMVAL} accepts in either position
     */
    private static boolean isSign(char character) {
        return character == PLUS_SIGN || character == MINUS_SIGN;
    }

    /**
     * {@code IF WS-RETURN-MSG-OFF STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) '<literal>' DELIMITED BY
     * SIZE INTO WS-RETURN-MSG END-IF} - the twelve two-operand message sites of {@code CSUTLDPY}:
     * L34-L40, L51-L57, L76-L82, L98-L104, L116-L122, L133-L139, L158-L164, L177-L183, L192-L198,
     * L218-L224, L233-L239, L263-L269 and L360-L366.
     *
     * <p>Every one of those sites is textually identical apart from its literal, so the guard and the
     * transfer are rendered once. The guard is still evaluated at each site, because each site calls
     * this method - what is shared is the rendering, not the decision. The remaining site, L306-L313 in
     * {@code EDIT-DATE-LE}, has five operands and is written out where it occurs.
     *
     * @param state   the working storage holding the field name and the message slot
     * @param literal the site's message literal, byte for byte as the copybook declares it
     */
    private void stringErrorMessage(EditDateState state, String literal) {
        if (state.returnMsgOff()) {
            state.stringIntoReturnMessage(trim(state.editVariableName()), literal);
        }
    }

    /**
     * The outcome of one {@code FUNCTION TEST-NUMVAL} and {@code FUNCTION NUMVAL} pair.
     *
     * @param errorPosition zero when the argument conforms, otherwise the one-based position
     *                      {@code TEST-NUMVAL} reports
     * @param value         the value {@code NUMVAL} yields, or zero when the argument does not conform
     */
    private record NumvalScan(int errorPosition, BigDecimal value) {

        /**
         * @param value the converted value
         * @return a conforming outcome
         */
        static NumvalScan accepted(BigDecimal value) {
            return new NumvalScan(NUMVAL_CONFORMS, value);
        }

        /**
         * @param position the one-based position to report
         * @return a rejecting outcome
         */
        static NumvalScan rejected(int position) {
            return new NumvalScan(position, BigDecimal.ZERO);
        }

        /**
         * {@code IF FUNCTION TEST-NUMVAL (...) = 0} - CSUTLDPY L126 and L170.
         *
         * @return whether the argument is a valid {@code NUMVAL} operand
         */
        boolean conforms() {
            return errorPosition == NUMVAL_CONFORMS;
        }
    }
}
