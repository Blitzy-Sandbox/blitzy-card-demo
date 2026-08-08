package com.vsergeychik.carddemo.common;

import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The COBOL copybook {@code app/cpy/CSDAT01Y.cpy} group item {@code 01 WS-DATE-TIME}: the date and
 * time header every CICS screen in this system is stamped with, rendered from an instant the caller
 * supplies rather than from the wall clock.
 *
 * <h2>The copybook, verbatim</h2>
 * Transcribed from {@code app/cpy/CSDAT01Y.cpy} lines 17 to 55. Every field below has a descriptor
 * in {@link #WS_DATE_TIME_LAYOUT} carrying the same name, the same width and the same declared
 * {@code VALUE}.
 * <pre>
 *  01 WS-DATE-TIME.
 *    05 WS-CURDATE-DATA.
 *      10  WS-CURDATE.
 *        15  WS-CURDATE-YEAR         PIC 9(04).
 *        15  WS-CURDATE-MONTH        PIC 9(02).
 *        15  WS-CURDATE-DAY          PIC 9(02).
 *      10 WS-CURDATE-N REDEFINES WS-CURDATE PIC 9(08).
 *      10  WS-CURTIME.
 *        15  WS-CURTIME-HOURS        PIC 9(02).
 *        15  WS-CURTIME-MINUTE       PIC 9(02).
 *        15  WS-CURTIME-SECOND       PIC 9(02).
 *        15  WS-CURTIME-MILSEC       PIC 9(02).
 *      10 WS-CURTIME-N REDEFINES WS-CURTIME PIC 9(08).
 *    05 WS-CURDATE-MM-DD-YY.
 *      10  WS-CURDATE-MM             PIC 9(02).
 *      10  FILLER                    PIC X(01) VALUE '/'.
 *      10  WS-CURDATE-DD             PIC 9(02).
 *      10  FILLER                    PIC X(01) VALUE '/'.
 *      10  WS-CURDATE-YY             PIC 9(02).
 *    05 WS-CURTIME-HH-MM-SS.
 *      10  WS-CURTIME-HH             PIC 9(02).
 *      10  FILLER                    PIC X(01) VALUE ':'.
 *      10  WS-CURTIME-MM             PIC 9(02).
 *      10  FILLER                    PIC X(01) VALUE ':'.
 *      10  WS-CURTIME-SS             PIC 9(02).
 *    05 WS-TIMESTAMP.
 *      10  WS-TIMESTAMP-DT-YYYY      PIC 9(04).
 *      10  FILLER                    PIC X(01) VALUE '-'.
 *      10  WS-TIMESTAMP-DT-MM        PIC 9(02).
 *      10  FILLER                    PIC X(01) VALUE '-'.
 *      10  WS-TIMESTAMP-DT-DD        PIC 9(02).
 *      10  FILLER                    PIC X(01) VALUE ' '.
 *      10  WS-TIMESTAMP-TM-HH        PIC 9(02).
 *      10  FILLER                    PIC X(01) VALUE ':'.
 *      10  WS-TIMESTAMP-TM-MM        PIC 9(02).
 *      10  FILLER                    PIC X(01) VALUE ':'.
 *      10  WS-TIMESTAMP-TM-SS        PIC 9(02).
 *      10  FILLER                    PIC X(01) VALUE '.'.
 *      10  WS-TIMESTAMP-TM-MS6       PIC 9(06).
 * </pre>
 *
 * <h2>Widths - 58 bytes, in four sub-groups</h2>
 * <table border="1">
 *   <caption>The declared geometry, and how each total is reached</caption>
 *   <tr><th>Sub-group</th><th>Composition</th><th>Bytes</th></tr>
 *   <tr><td>{@code WS-CURDATE-DATA}</td>
 *       <td>{@code WS-CURDATE} (4 + 2 + 2 = 8) then {@code WS-CURTIME} (2 + 2 + 2 + 2 = 8)</td>
 *       <td>16</td></tr>
 *   <tr><td>{@code WS-CURDATE-MM-DD-YY}</td><td>2 + 1 + 2 + 1 + 2</td><td>8</td></tr>
 *   <tr><td>{@code WS-CURTIME-HH-MM-SS}</td><td>2 + 1 + 2 + 1 + 2</td><td>8</td></tr>
 *   <tr><td>{@code WS-TIMESTAMP}</td>
 *       <td>4 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 6</td><td>26</td></tr>
 *   <tr><td><strong>{@code WS-DATE-TIME}</strong></td><td></td>
 *       <td><strong>58</strong></td></tr>
 * </table>
 * The two {@code REDEFINES} items contribute nothing: they are alternate eight-digit views of spans
 * the layout already accounts for. {@link #WS_DATE_TIME_LAYOUT} refuses to be constructed unless the
 * storage spans sum to exactly {@link #WS_DATE_TIME_LENGTH}, so this arithmetic is proven at class
 * initialisation rather than asserted in prose.
 *
 * <h2>This copybook is the counter-example to "FILLER is spaces"</h2>
 * Every separator here is a {@code FILLER} carrying an explicit, non-space {@code VALUE}:
 * {@code '/'} twice, {@code ':'} four times, {@code '-'} twice, {@code ' '} once and {@code '.'}
 * once. The general guidance that a {@code FILLER} is "emitted as spaces" is correct for the
 * persisted record copybooks - {@code CVACT01Y}'s {@code FILLER X(178)} and {@code CVACT03Y}'s
 * {@code FILLER X(14)} really do hold spaces - but it is <strong>not</strong> true here, and this
 * class is where that qualification is recorded rather than quietly normalised. The precise rule,
 * implemented by {@link FixedWidthCodec#writeDeclaredValue(FixedWidthRecord, FieldSpan)}, is:
 * <em>a span emits its declared literal when one is present, and a pad byte only when none is.</em>
 *
 * <p>Blanket space-filling is not a cosmetic error. It turns {@code 12/25/24} into {@code 12 25 24}
 * and {@code 2024-12-25 13:45:07.089123} into a blank-riddled string, on the heading line of all 17
 * screens at once, while every field-level length check still passes. That is why the separators are
 * declared as {@link FieldSpan#filler(int, int, String)} descriptors carrying their literals rather
 * than special-cased in a formatter.
 *
 * <h2>The instant always arrives from the caller - {@code now()} is never called</h2>
 * There is no call to {@code LocalDate.now()}, {@code LocalDateTime.now()}, {@code LocalTime.now()},
 * {@code Instant.now()}, {@code System.currentTimeMillis()} or {@code new java.util.Date()} anywhere
 * in this class, and there must never be one. Time enters through {@link #from(FixedWidthCodec,
 * Clock)}, which reads a supplied {@link Clock} exactly once, or through the
 * {@code of(...)} factories, which take the date and time outright.
 *
 * <p>This is not a stylistic preference. Each of the 17 online programs stamps its screen with this
 * header, so if the header consulted the wall clock no parity case could ever assert an exact byte
 * image and the "diff count equals zero" acceptance gate would be unreachable for every online
 * program. One {@link Clock} parameter removes that entire class of problem: {@code Clock.fixed(...)}
 * makes each rendering reproducible, and a test can prove it by building twice from the same clock
 * and comparing character for character.
 *
 * <h2>One capture, many renderings</h2>
 * {@code WS-CURDATE-MM-DD-YY}, {@code WS-CURTIME-HH-MM-SS} and {@code WS-TIMESTAMP} are renderings
 * of the same instant as {@code WS-CURDATE-DATA}, never independent values. The COBOL says so
 * explicitly: {@code POPULATE-HEADER-INFO} moves {@code FUNCTION CURRENT-DATE} into
 * {@code WS-CURDATE-DATA} and then copies the components across one by one -
 * {@code app/cbl/COMEN01C.cbl:214-231} and {@code app/cbl/COSGN00C.cbl:179-196} are
 * character-for-character identical in this respect. Every accessor below therefore derives from the
 * single {@link CapturedDateTime} this instance holds, so the renderings cannot disagree across a
 * second boundary.
 *
 * <h2>{@code FUNCTION CURRENT-DATE} returns 21 characters and 5 of them are discarded</h2>
 * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} moves a 21-character intrinsic result -
 * {@code YYYYMMDD} then {@code HHMMSSss} then a five-character GMT offset - into a 16-byte
 * alphanumeric group. A cross-width alphanumeric {@code MOVE} truncates on the <strong>right</strong>,
 * so the offset is dropped and the header carries local date and time with no timezone information
 * at hundredths-of-a-second resolution.
 *
 * <p>That width is proven inside this repository rather than taken from documentation.
 * {@code app/cbl/CBACT04C.cbl:141-149} declares
 * <pre>
 *  01  COBOL-TS.
 *      05 COB-YYYY  PIC X(04).   05 COB-MM   PIC X(02).   05 COB-DD  PIC X(02).
 *      05 COB-HH    PIC X(02).   05 COB-MIN  PIC X(02).   05 COB-SS  PIC X(02).
 *      05 COB-MIL   PIC X(02).   05 COB-REST PIC X(05).
 * </pre>
 * which sums to exactly 21 bytes and is filled by {@code MOVE FUNCTION CURRENT-DATE TO COBOL-TS}
 * at {@code app/cbl/CBACT04C.cbl:614}. {@code COB-REST PIC X(05)} <em>is</em> the offset that
 * {@code WS-CURDATE-DATA}'s narrower 16 bytes discard. {@link #functionCurrentDate()} produces the
 * 21-character form, {@link #wsCurdateData()} is defined as that value truncated to 16, and
 * {@link #discardedGmtOffset()} returns the 5 characters that fell off, so the truncation is
 * demonstrable rather than merely described.
 *
 * <h2>Two fractional-second precisions coexist, and neither is derived from the other</h2>
 * {@code WS-CURTIME-MILSEC} is {@code PIC 9(02)} and receives the {@code ss} field of
 * {@code FUNCTION CURRENT-DATE}, which is <strong>hundredths</strong> of a second.
 * <em>Despite its name it does not hold milliseconds.</em> The name is preserved exactly as the
 * copybook spells it, and the field is neither renamed nor widened to three digits, because the
 * parity differ compares field by field <em>by name</em> and a corrected name would make a real
 * difference invisible. {@code WS-TIMESTAMP-TM-MS6} is {@code PIC 9(06)} - six digits, microsecond
 * resolution. Both are rendered at their own declared precision from the one captured instant;
 * neither is obtained by padding the other.
 *
 * <h2>A related 26-byte timestamp that is <em>not</em> interchangeable with this one</h2>
 * {@code app/cbl/CBACT04C.cbl:150-165} declares {@code DB2-FORMAT-TS PIC X(26)} and redefines it
 * into the same field partition as {@code WS-TIMESTAMP} - and it is indeed the same 26 bytes wide -
 * but its <strong>separators differ</strong>, so the two byte images are not the same. Recorded here
 * as a finding rather than reconciled:
 * <table border="1">
 *   <caption>Same width, same partition, different separators</caption>
 *   <tr><th>Position</th><th>{@code WS-TIMESTAMP} (this copybook)</th>
 *       <th>{@code DB2-FORMAT-TS} ({@code CBACT04C})</th></tr>
 *   <tr><td>after {@code DD}</td><td>{@code ' '} (a space, line 48)</td>
 *       <td>{@code '-'} ({@code DB2-STREEP-3}, set at line 623)</td></tr>
 *   <tr><td>after {@code HH} and {@code MM}</td><td>{@code ':'} (lines 50 and 52)</td>
 *       <td>{@code '.'} ({@code DB2-DOT-1}, {@code DB2-DOT-2}, set at line 624)</td></tr>
 *   <tr><td>trailing 6 digits</td><td>one {@code PIC 9(06)} microsecond field</td>
 *       <td>{@code DB2-MIL PIC 9(02)} then the literal {@code '0000'} (lines 621 and 622)</td></tr>
 * </table>
 * So {@code WS-TIMESTAMP} reads {@code 2024-12-25 13:45:07.089123} while {@code CBACT04C} writes
 * {@code 2024-12-25-13.45.07.080000}. Both forms are available -
 * {@link #wsTimestamp()} and {@link #db2FormatTimestamp()} - and a translator must pick the one its
 * program actually declares.
 *
 * <h2>No calendar validation, because the COBOL performs none</h2>
 * A {@code PIC 9(02)} receiver holds {@code 00} to {@code 99} and imposes no calendar meaning, so
 * this class validates only that each component fits its declared digit count. Month {@code 00} is
 * legitimately reachable: {@code app/cbl/COBIL00C.cbl:263} issues {@code INITIALIZE WS-TIMESTAMP}
 * before populating it, and {@code app/cbl/COTRN00C.cbl:384} moves a stored
 * {@code TRAN-ORIG-TS PIC X(26)} straight into the group and reads the sub-fields back out.
 * Rejecting an impossible date would be a new business rule, which a like-for-like migration must
 * not introduce.
 *
 * <h2>Immutability and thread safety</h2>
 * An instance is immutable: the captured components live in a {@link CapturedDateTime} record and
 * there is no setter and no mutable field. There is no static mutable state either -
 * {@link #WS_DATE_TIME_LAYOUT} is an immutable {@link RecordLayout} whose span list is defensively
 * copied, and every other constant is a primitive or a {@link String}. COBOL
 * {@code WORKING-STORAGE} deliberately does not become static Java state here, because that would
 * break request isolation across the 17 controllers and make tests order-dependent. Instances are
 * therefore safe to share across threads.
 *
 * <h2>Position in the module</h2>
 * The {@link FixedWidthCodec} is a constructor argument, never a field-injected or statically
 * resolved one. It is required for two distinct reasons: it owns the module's single zero-fill
 * implementation, so no second left-zero-pad is written here, and it owns the code page, so
 * {@link #toBytes()} never has to guess one. This class carries no framework annotation; where a
 * container-managed codec is wanted the configuration package declares it and passes it in.
 *
 * <p>{@code COPY CSDAT01Y.} appears in all <strong>17</strong> CICS online programs, making this one
 * of the six universal online includes, so every controller in the system renders its heading line
 * from this type.
 *
 * @see FixedWidthCodec
 * @see FixedWidthRecord
 * @see ScreenTitles
 */
public final class DateHeader {

    // =================================================================================================
    // Declared widths. Every number below is a digit count or byte count read directly from
    // app/cpy/CSDAT01Y.cpy, named so that no width is ever written as a bare literal at a call site.
    // =================================================================================================

    /**
     * The total width of {@code 01 WS-DATE-TIME} in bytes: {@code 16 + 8 + 8 + 26}. Declared as one
     * named total so the sum is checkable in a single place, and enforced by
     * {@link #WS_DATE_TIME_LAYOUT}, which cannot be constructed if the spans disagree with it.
     */
    public static final int WS_DATE_TIME_LENGTH = 58;

    /**
     * {@code WS-CURDATE-DATA} - 16 bytes, the {@code YYYYMMDDHHMMSSss} span that
     * {@code MOVE FUNCTION CURRENT-DATE} populates. This is also the width that truncates the
     * intrinsic's 21 characters, discarding its trailing GMT offset.
     */
    public static final int WS_CURDATE_DATA_LENGTH = 16;

    /** {@code WS-CURDATE} - 8 bytes, {@code YYYYMMDD}: 4 + 2 + 2. */
    public static final int WS_CURDATE_LENGTH = 8;

    /** {@code WS-CURTIME} - 8 bytes, {@code HHMMSSss}: 2 + 2 + 2 + 2. */
    public static final int WS_CURTIME_LENGTH = 8;

    /** {@code WS-CURDATE-MM-DD-YY} - 8 bytes, {@code MM/DD/YY}: 2 + 1 + 2 + 1 + 2. */
    public static final int WS_CURDATE_MM_DD_YY_LENGTH = 8;

    /** {@code WS-CURTIME-HH-MM-SS} - 8 bytes, {@code HH:MM:SS}: 2 + 1 + 2 + 1 + 2. */
    public static final int WS_CURTIME_HH_MM_SS_LENGTH = 8;

    /**
     * {@code WS-TIMESTAMP} - 26 bytes, {@code YYYY-MM-DD HH:MM:SS.ssssss}:
     * {@code 4 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 6}.
     */
    public static final int WS_TIMESTAMP_LENGTH = 26;

    /**
     * The width of the {@code FUNCTION CURRENT-DATE} intrinsic result: 21 characters,
     * {@code YYYYMMDD} + {@code HHMMSSss} + a five-character GMT offset. Evidenced by
     * {@code 01 COBOL-TS} at {@code app/cbl/CBACT04C.cbl:141-149}, which sums to exactly this and is
     * the receiver of that intrinsic at line 614.
     */
    public static final int FUNCTION_CURRENT_DATE_LENGTH = 21;

    /**
     * The width of the GMT offset that {@code FUNCTION CURRENT-DATE} appends and that
     * {@code WS-CURDATE-DATA} discards: 5 characters in {@code shhmm} form. This is
     * {@code COB-REST PIC X(05)} of {@code app/cbl/CBACT04C.cbl:149}.
     */
    public static final int GMT_OFFSET_LENGTH = 5;

    /** {@code PIC 9(04)} - {@code WS-CURDATE-YEAR} and {@code WS-TIMESTAMP-DT-YYYY}. */
    public static final int YEAR_DIGITS = 4;

    /** {@code PIC 9(02)} - {@code WS-CURDATE-MONTH}, {@code WS-CURDATE-MM}, {@code -DT-MM}. */
    public static final int MONTH_DIGITS = 2;

    /** {@code PIC 9(02)} - {@code WS-CURDATE-DAY}, {@code WS-CURDATE-DD}, {@code -DT-DD}. */
    public static final int DAY_DIGITS = 2;

    /**
     * {@code PIC 9(02)} - {@code WS-CURDATE-YY}, the two-digit year. The COBOL fills it by reference
     * modification, {@code MOVE WS-CURDATE-YEAR(3:2) TO WS-CURDATE-YY}, keeping the low-order two
     * digits of the four-digit year.
     */
    public static final int TWO_DIGIT_YEAR_DIGITS = 2;

    /** {@code PIC 9(02)} - {@code WS-CURTIME-HOURS}, {@code WS-CURTIME-HH}, {@code -TM-HH}. */
    public static final int HOURS_DIGITS = 2;

    /** {@code PIC 9(02)} - {@code WS-CURTIME-MINUTE}, {@code WS-CURTIME-MM}, {@code -TM-MM}. */
    public static final int MINUTE_DIGITS = 2;

    /** {@code PIC 9(02)} - {@code WS-CURTIME-SECOND}, {@code WS-CURTIME-SS}, {@code -TM-SS}. */
    public static final int SECOND_DIGITS = 2;

    /**
     * {@code PIC 9(02)} - {@code WS-CURTIME-MILSEC}. Two digits, and therefore
     * <strong>hundredths</strong> of a second, not milliseconds, whatever the name suggests. Never
     * widen this.
     */
    public static final int MILSEC_DIGITS = 2;

    /**
     * {@code PIC 9(06)} - {@code WS-TIMESTAMP-TM-MS6}. Six digits, microsecond resolution, and a
     * different precision from {@link #MILSEC_DIGITS}.
     */
    public static final int MICROSECOND_DIGITS = 6;

    /**
     * {@code PIC 9(08)} - the width of both {@code REDEFINES} views, {@code WS-CURDATE-N} over
     * {@code WS-CURDATE} and {@code WS-CURTIME-N} over {@code WS-CURTIME}.
     */
    public static final int REDEFINED_VIEW_DIGITS = 8;

    /** {@code PIC X(01)} - the width of every separator {@code FILLER} in this copybook. */
    public static final int SEPARATOR_LENGTH = 1;

    // =================================================================================================
    // Declared absolute offsets of the four sub-groups. Exposed because a caller holding a serialised
    // 58-byte image needs them to address a sub-group without re-deriving the arithmetic.
    // =================================================================================================

    /** Absolute 0-based offset of {@code WS-CURDATE-DATA}, and of {@code WS-CURDATE} within it. */
    public static final int WS_CURDATE_DATA_OFFSET = 0;

    /** Absolute 0-based offset of {@code WS-CURTIME}, the second half of {@code WS-CURDATE-DATA}. */
    public static final int WS_CURTIME_OFFSET = WS_CURDATE_DATA_OFFSET + WS_CURDATE_LENGTH;

    /** Absolute 0-based offset of {@code WS-CURDATE-MM-DD-YY}: byte 16. */
    public static final int WS_CURDATE_MM_DD_YY_OFFSET =
            WS_CURDATE_DATA_OFFSET + WS_CURDATE_DATA_LENGTH;

    /** Absolute 0-based offset of {@code WS-CURTIME-HH-MM-SS}: byte 24. */
    public static final int WS_CURTIME_HH_MM_SS_OFFSET =
            WS_CURDATE_MM_DD_YY_OFFSET + WS_CURDATE_MM_DD_YY_LENGTH;

    /** Absolute 0-based offset of {@code WS-TIMESTAMP}: byte 32. */
    public static final int WS_TIMESTAMP_OFFSET =
            WS_CURTIME_HH_MM_SS_OFFSET + WS_CURTIME_HH_MM_SS_LENGTH;

    // =================================================================================================
    // Separator literals. Each one is a FILLER ... VALUE from the copybook, cited to the line it was
    // read from, so a reviewer can see it is transcribed data rather than a formatting choice.
    // =================================================================================================

    /**
     * {@code FILLER PIC X(01) VALUE '/'} - {@code app/cpy/CSDAT01Y.cpy:32} and
     * {@code app/cpy/CSDAT01Y.cpy:34}, the two separators of {@code WS-CURDATE-MM-DD-YY}.
     */
    public static final char DATE_SEPARATOR = '/';

    /**
     * {@code FILLER PIC X(01) VALUE ':'} - {@code app/cpy/CSDAT01Y.cpy:38} and
     * {@code app/cpy/CSDAT01Y.cpy:40} in {@code WS-CURTIME-HH-MM-SS}, and
     * {@code app/cpy/CSDAT01Y.cpy:50} and {@code app/cpy/CSDAT01Y.cpy:52} in {@code WS-TIMESTAMP}.
     */
    public static final char TIME_SEPARATOR = ':';

    /**
     * {@code FILLER PIC X(01) VALUE '-'} - {@code app/cpy/CSDAT01Y.cpy:44} and
     * {@code app/cpy/CSDAT01Y.cpy:46}, the date separators inside {@code WS-TIMESTAMP}.
     */
    public static final char TIMESTAMP_DATE_SEPARATOR = '-';

    /**
     * {@code FILLER PIC X(01) VALUE ' '} - {@code app/cpy/CSDAT01Y.cpy:48}. A space here is the
     * declared literal, not a pad byte, and it is the one position where {@code WS-TIMESTAMP} and
     * {@code CBACT04C}'s {@code DB2-FORMAT-TS} visibly disagree: that program writes {@code '-'}.
     */
    public static final char TIMESTAMP_DATE_TIME_SEPARATOR = ' ';

    /**
     * {@code FILLER PIC X(01) VALUE '.'} - {@code app/cpy/CSDAT01Y.cpy:54}, ahead of the six-digit
     * microsecond field.
     */
    public static final char TIMESTAMP_FRACTION_SEPARATOR = '.';

    /**
     * The separator {@code app/cbl/CBACT04C.cbl:623} moves into {@code DB2-STREEP-3}, where
     * {@code WS-TIMESTAMP} declares a space. Held separately so
     * {@link #db2FormatTimestamp()} cannot accidentally be built from this copybook's separators.
     */
    private static final char DB2_DATE_TIME_SEPARATOR = '-';

    /**
     * The separator {@code app/cbl/CBACT04C.cbl:624} moves into {@code DB2-DOT-1} and
     * {@code DB2-DOT-2}, where {@code WS-TIMESTAMP} declares a colon.
     */
    private static final char DB2_TIME_SEPARATOR = '.';

    /**
     * The literal {@code app/cbl/CBACT04C.cbl:622} moves into {@code DB2-REST PIC X(04)}, which
     * follows the two-digit {@code DB2-MIL} to fill that timestamp's six fractional positions. This
     * is why {@link #db2FormatTimestamp()} carries hundredths padded with four zeros while
     * {@link #wsTimestamp()} carries true microseconds.
     */
    private static final String DB2_FRACTION_REMAINDER = "0000";

    /** The sign character {@code FUNCTION CURRENT-DATE} uses for an offset east of Greenwich. */
    private static final char GMT_OFFSET_POSITIVE_SIGN = '+';

    /** The sign character {@code FUNCTION CURRENT-DATE} uses for an offset west of Greenwich. */
    private static final char GMT_OFFSET_NEGATIVE_SIGN = '-';

    /** Seconds per minute, used only to reduce a {@link ZoneOffset} to whole minutes. */
    private static final int SECONDS_PER_MINUTE = 60;

    /** Minutes per hour, used only to split an offset into its {@code hh} and {@code mm} halves. */
    private static final int MINUTES_PER_HOUR = 60;

    /** Nanoseconds per microsecond - the divisor that yields {@code WS-TIMESTAMP-TM-MS6}. */
    private static final int NANOS_PER_MICROSECOND = 1_000;

    /** Nanoseconds per hundredth of a second - the divisor that yields {@code WS-CURTIME-MILSEC}. */
    private static final int NANOS_PER_HUNDREDTH = 10_000_000;

    /**
     * Microseconds per hundredth of a second. Used in exactly one direction: recovering
     * {@code WS-CURTIME-MILSEC} from a {@code WS-TIMESTAMP} image, which carries the fractional
     * second only at its own six-digit precision. It is never used the other way round, because
     * padding hundredths out to six digits would fabricate precision the instant does not have.
     */
    private static final int MICROSECONDS_PER_HUNDREDTH = NANOS_PER_HUNDREDTH / NANOS_PER_MICROSECOND;

    /**
     * The offset recorded when none is available. A rendered {@code WS-TIMESTAMP} image carries no
     * timezone information at all, and neither does a bare {@link LocalDateTime}, so both are taken
     * as Greenwich. This affects nothing but the five characters
     * {@link #functionCurrentDate()} appends and {@link #wsCurdateData()} immediately truncates away.
     */
    private static final int NO_GMT_OFFSET = 0;

    /**
     * The largest GMT offset expressible as {@code shhmm} with a two-digit hour, in minutes. An
     * offset beyond this could not be rendered into {@link #GMT_OFFSET_LENGTH} characters, so it is
     * rejected rather than silently reshaped.
     */
    private static final int MAX_GMT_OFFSET_MINUTES = 23 * MINUTES_PER_HOUR + 59;

    // =================================================================================================
    // Copybook field names, verbatim. These are the keys of fieldImages() and the names the parity
    // differ compares by, so each is declared once and never spelled a second time as a literal.
    // =================================================================================================

    /** {@code 05 WS-CURDATE-DATA} - the 16-byte group, addressable as a whole. */
    public static final String WS_CURDATE_DATA = "WS-CURDATE-DATA";

    /** {@code 10 WS-CURDATE} - the 8-byte {@code YYYYMMDD} group. */
    public static final String WS_CURDATE = "WS-CURDATE";

    /** {@code 15 WS-CURDATE-YEAR PIC 9(04)}. */
    public static final String WS_CURDATE_YEAR = "WS-CURDATE-YEAR";

    /** {@code 15 WS-CURDATE-MONTH PIC 9(02)}. */
    public static final String WS_CURDATE_MONTH = "WS-CURDATE-MONTH";

    /** {@code 15 WS-CURDATE-DAY PIC 9(02)}. */
    public static final String WS_CURDATE_DAY = "WS-CURDATE-DAY";

    /** {@code 10 WS-CURDATE-N REDEFINES WS-CURDATE PIC 9(08)} - a view, not storage of its own. */
    public static final String WS_CURDATE_N = "WS-CURDATE-N";

    /** {@code 10 WS-CURTIME} - the 8-byte {@code HHMMSSss} group. */
    public static final String WS_CURTIME = "WS-CURTIME";

    /** {@code 15 WS-CURTIME-HOURS PIC 9(02)}. */
    public static final String WS_CURTIME_HOURS = "WS-CURTIME-HOURS";

    /** {@code 15 WS-CURTIME-MINUTE PIC 9(02)} - singular in the copybook, preserved as such. */
    public static final String WS_CURTIME_MINUTE = "WS-CURTIME-MINUTE";

    /** {@code 15 WS-CURTIME-SECOND PIC 9(02)} - singular in the copybook, preserved as such. */
    public static final String WS_CURTIME_SECOND = "WS-CURTIME-SECOND";

    /**
     * {@code 15 WS-CURTIME-MILSEC PIC 9(02)} - hundredths of a second despite the name. The name is
     * carried verbatim; correcting it would break name-keyed field comparison.
     */
    public static final String WS_CURTIME_MILSEC = "WS-CURTIME-MILSEC";

    /** {@code 10 WS-CURTIME-N REDEFINES WS-CURTIME PIC 9(08)} - a view, not storage of its own. */
    public static final String WS_CURTIME_N = "WS-CURTIME-N";

    /** {@code 05 WS-CURDATE-MM-DD-YY} - the 8-byte {@code MM/DD/YY} group. */
    public static final String WS_CURDATE_MM_DD_YY = "WS-CURDATE-MM-DD-YY";

    /** {@code 10 WS-CURDATE-MM PIC 9(02)}. */
    public static final String WS_CURDATE_MM = "WS-CURDATE-MM";

    /** {@code 10 WS-CURDATE-DD PIC 9(02)}. */
    public static final String WS_CURDATE_DD = "WS-CURDATE-DD";

    /** {@code 10 WS-CURDATE-YY PIC 9(02)} - the two-digit year. */
    public static final String WS_CURDATE_YY = "WS-CURDATE-YY";

    /** {@code 05 WS-CURTIME-HH-MM-SS} - the 8-byte {@code HH:MM:SS} group. */
    public static final String WS_CURTIME_HH_MM_SS = "WS-CURTIME-HH-MM-SS";

    /** {@code 10 WS-CURTIME-HH PIC 9(02)}. */
    public static final String WS_CURTIME_HH = "WS-CURTIME-HH";

    /** {@code 10 WS-CURTIME-MM PIC 9(02)} - minutes here, distinct from {@code WS-CURDATE-MM}. */
    public static final String WS_CURTIME_MM = "WS-CURTIME-MM";

    /** {@code 10 WS-CURTIME-SS PIC 9(02)}. */
    public static final String WS_CURTIME_SS = "WS-CURTIME-SS";

    /** {@code 05 WS-TIMESTAMP} - the 26-byte group, moved whole to and from {@code TRAN-ORIG-TS}. */
    public static final String WS_TIMESTAMP = "WS-TIMESTAMP";

    /** {@code 10 WS-TIMESTAMP-DT-YYYY PIC 9(04)}. */
    public static final String WS_TIMESTAMP_DT_YYYY = "WS-TIMESTAMP-DT-YYYY";

    /** {@code 10 WS-TIMESTAMP-DT-MM PIC 9(02)}. */
    public static final String WS_TIMESTAMP_DT_MM = "WS-TIMESTAMP-DT-MM";

    /** {@code 10 WS-TIMESTAMP-DT-DD PIC 9(02)}. */
    public static final String WS_TIMESTAMP_DT_DD = "WS-TIMESTAMP-DT-DD";

    /** {@code 10 WS-TIMESTAMP-TM-HH PIC 9(02)}. */
    public static final String WS_TIMESTAMP_TM_HH = "WS-TIMESTAMP-TM-HH";

    /** {@code 10 WS-TIMESTAMP-TM-MM PIC 9(02)}. */
    public static final String WS_TIMESTAMP_TM_MM = "WS-TIMESTAMP-TM-MM";

    /** {@code 10 WS-TIMESTAMP-TM-SS PIC 9(02)}. */
    public static final String WS_TIMESTAMP_TM_SS = "WS-TIMESTAMP-TM-SS";

    /** {@code 10 WS-TIMESTAMP-TM-MS6 PIC 9(06)} - six digits, microseconds. */
    public static final String WS_TIMESTAMP_TM_MS6 = "WS-TIMESTAMP-TM-MS6";

    // =================================================================================================
    // The layout. Declared in copybook order, with every FILLER carrying its declared VALUE and every
    // group item declared as a REDEFINES view over the elementary items it contains. RecordLayout
    // proves the geometry at class initialisation: contiguous from byte 0, overlays inside storage
    // already declared, and a storage total of exactly WS_DATE_TIME_LENGTH.
    // =================================================================================================

    /**
     * The 58-byte layout of {@code 01 WS-DATE-TIME}, transcribed span by span from
     * {@code app/cpy/CSDAT01Y.cpy}.
     *
     * <p>Thirty-eight descriptors: 20 elementary numeric fields, the 10 separator {@code FILLER}s
     * carrying their declared literals, the 2 {@code REDEFINES} numeric views, and the 6 COBOL group
     * items. The group items are declared as {@link PictureKind#ALPHANUMERIC} overlays rather than
     * omitted, because they are referable COBOL names that programs move whole -
     * {@code MOVE WS-TIMESTAMP TO TRAN-ORIG-TS} at {@code app/cbl/COBIL00C.cbl:231} and
     * {@code MOVE TRAN-ORIG-TS TO WS-TIMESTAMP} at {@code app/cbl/COTRN00C.cbl:384} - so
     * {@code WS_DATE_TIME_LAYOUT.span("WS-TIMESTAMP")} has to resolve.
     *
     * <p>An overlay contributes no storage, which is why 20 elementary fields plus 10 fillers still
     * sum to 58. The constant is safe to expose: a {@link RecordLayout} is an immutable record whose
     * span list is defensively copied, so publishing it cannot introduce shared mutable state.
     */
    public static final RecordLayout WS_DATE_TIME_LAYOUT = RecordLayout.of(WS_DATE_TIME_LENGTH,
            // 05 WS-CURDATE-DATA, 10 WS-CURDATE - app/cpy/CSDAT01Y.cpy:18-22
            FieldSpan.unsignedNumeric(WS_CURDATE_YEAR, 0, YEAR_DIGITS),
            FieldSpan.unsignedNumeric(WS_CURDATE_MONTH, 4, MONTH_DIGITS),
            FieldSpan.unsignedNumeric(WS_CURDATE_DAY, 6, DAY_DIGITS),
            FieldSpan.redefining(WS_CURDATE, WS_CURDATE_DATA_OFFSET, WS_CURDATE_LENGTH,
                    PictureKind.ALPHANUMERIC),
            // 10 WS-CURDATE-N REDEFINES WS-CURDATE PIC 9(08) - app/cpy/CSDAT01Y.cpy:23
            FieldSpan.redefining(WS_CURDATE_N, WS_CURDATE_DATA_OFFSET, REDEFINED_VIEW_DIGITS,
                    PictureKind.UNSIGNED_NUMERIC),
            // 10 WS-CURTIME - app/cpy/CSDAT01Y.cpy:24-28
            FieldSpan.unsignedNumeric(WS_CURTIME_HOURS, 8, HOURS_DIGITS),
            FieldSpan.unsignedNumeric(WS_CURTIME_MINUTE, 10, MINUTE_DIGITS),
            FieldSpan.unsignedNumeric(WS_CURTIME_SECOND, 12, SECOND_DIGITS),
            FieldSpan.unsignedNumeric(WS_CURTIME_MILSEC, 14, MILSEC_DIGITS),
            FieldSpan.redefining(WS_CURTIME, WS_CURTIME_OFFSET, WS_CURTIME_LENGTH,
                    PictureKind.ALPHANUMERIC),
            // 10 WS-CURTIME-N REDEFINES WS-CURTIME PIC 9(08) - app/cpy/CSDAT01Y.cpy:29
            FieldSpan.redefining(WS_CURTIME_N, WS_CURTIME_OFFSET, REDEFINED_VIEW_DIGITS,
                    PictureKind.UNSIGNED_NUMERIC),
            FieldSpan.redefining(WS_CURDATE_DATA, WS_CURDATE_DATA_OFFSET, WS_CURDATE_DATA_LENGTH,
                    PictureKind.ALPHANUMERIC),
            // 05 WS-CURDATE-MM-DD-YY - app/cpy/CSDAT01Y.cpy:30-35
            FieldSpan.unsignedNumeric(WS_CURDATE_MM, 16, MONTH_DIGITS),
            FieldSpan.filler(18, SEPARATOR_LENGTH, String.valueOf(DATE_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_CURDATE_DD, 19, DAY_DIGITS),
            FieldSpan.filler(21, SEPARATOR_LENGTH, String.valueOf(DATE_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_CURDATE_YY, 22, TWO_DIGIT_YEAR_DIGITS),
            FieldSpan.redefining(WS_CURDATE_MM_DD_YY, WS_CURDATE_MM_DD_YY_OFFSET,
                    WS_CURDATE_MM_DD_YY_LENGTH, PictureKind.ALPHANUMERIC),
            // 05 WS-CURTIME-HH-MM-SS - app/cpy/CSDAT01Y.cpy:36-41
            FieldSpan.unsignedNumeric(WS_CURTIME_HH, 24, HOURS_DIGITS),
            FieldSpan.filler(26, SEPARATOR_LENGTH, String.valueOf(TIME_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_CURTIME_MM, 27, MINUTE_DIGITS),
            FieldSpan.filler(29, SEPARATOR_LENGTH, String.valueOf(TIME_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_CURTIME_SS, 30, SECOND_DIGITS),
            FieldSpan.redefining(WS_CURTIME_HH_MM_SS, WS_CURTIME_HH_MM_SS_OFFSET,
                    WS_CURTIME_HH_MM_SS_LENGTH, PictureKind.ALPHANUMERIC),
            // 05 WS-TIMESTAMP - app/cpy/CSDAT01Y.cpy:42-55
            FieldSpan.unsignedNumeric(WS_TIMESTAMP_DT_YYYY, 32, YEAR_DIGITS),
            FieldSpan.filler(36, SEPARATOR_LENGTH, String.valueOf(TIMESTAMP_DATE_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_TIMESTAMP_DT_MM, 37, MONTH_DIGITS),
            FieldSpan.filler(39, SEPARATOR_LENGTH, String.valueOf(TIMESTAMP_DATE_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_TIMESTAMP_DT_DD, 40, DAY_DIGITS),
            FieldSpan.filler(42, SEPARATOR_LENGTH, String.valueOf(TIMESTAMP_DATE_TIME_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_TIMESTAMP_TM_HH, 43, HOURS_DIGITS),
            FieldSpan.filler(45, SEPARATOR_LENGTH, String.valueOf(TIME_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_TIMESTAMP_TM_MM, 46, MINUTE_DIGITS),
            FieldSpan.filler(48, SEPARATOR_LENGTH, String.valueOf(TIME_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_TIMESTAMP_TM_SS, 49, SECOND_DIGITS),
            FieldSpan.filler(51, SEPARATOR_LENGTH, String.valueOf(TIMESTAMP_FRACTION_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_TIMESTAMP_TM_MS6, 52, MICROSECOND_DIGITS),
            FieldSpan.redefining(WS_TIMESTAMP, WS_TIMESTAMP_OFFSET, WS_TIMESTAMP_LENGTH,
                    PictureKind.ALPHANUMERIC));

    // =================================================================================================
    // The captured instant.
    // =================================================================================================

    /**
     * One instant, decomposed into exactly the components {@code app/cpy/CSDAT01Y.cpy} declares. This
     * is the whole of a {@link DateHeader}'s time-bearing state, and it is captured once so that every
     * rendering is a projection of the same moment rather than a fresh reading of a clock.
     *
     * <h2>Why the fractional second appears twice</h2>
     * {@code hundredths} and {@code microseconds} are carried as separate components on purpose.
     * {@code WS-CURTIME-MILSEC} is {@code PIC 9(02)} and {@code WS-TIMESTAMP-TM-MS6} is
     * {@code PIC 9(06)}: two declared precisions of the same instant. Storing only one and padding it
     * to produce the other would be a silent divergence, and storing them independently would let
     * them drift, so both are derived here, at capture time, from the one nanosecond-of-second value.
     *
     * <h2>Why only width is validated</h2>
     * Each component is checked to fit its declared digit count and nothing more. A {@code PIC 9(02)}
     * receiver holds {@code 00} to {@code 99} and the COBOL performs no calendar test, so a month of
     * {@code 00} is legitimate rather than corrupt: {@code app/cbl/COBIL00C.cbl:263} issues
     * {@code INITIALIZE WS-TIMESTAMP} before populating the group, and
     * {@code app/cbl/COTRN00C.cbl:384} moves a stored {@code TRAN-ORIG-TS PIC X(26)} into it and
     * reads the sub-fields straight back out. Adding a calendar check would be a new business rule.
     *
     * @param year             {@code WS-CURDATE-YEAR}, 0 to 9999
     * @param month            {@code WS-CURDATE-MONTH}, 0 to 99; not calendar-checked
     * @param day              {@code WS-CURDATE-DAY}, 0 to 99; not calendar-checked
     * @param hours            {@code WS-CURTIME-HOURS}, 0 to 99
     * @param minutes          {@code WS-CURTIME-MINUTE}, 0 to 99
     * @param seconds          {@code WS-CURTIME-SECOND}, 0 to 99
     * @param hundredths       {@code WS-CURTIME-MILSEC}, 0 to 99 - hundredths of a second, not
     *                         milliseconds
     * @param microseconds     {@code WS-TIMESTAMP-TM-MS6}, 0 to 999999
     * @param gmtOffsetMinutes the offset from Greenwich in whole minutes, which
     *                         {@code FUNCTION CURRENT-DATE} appends as {@code shhmm} and
     *                         {@code WS-CURDATE-DATA} then discards. Between -1439 and 1439
     *                         inclusive, so it always renders in five characters
     */
    public record CapturedDateTime(int year,
                                   int month,
                                   int day,
                                   int hours,
                                   int minutes,
                                   int seconds,
                                   int hundredths,
                                   int microseconds,
                                   int gmtOffsetMinutes) {

        /**
         * Validates every component against the width its {@code PICTURE} declares, so an
         * out-of-range value fails where it is supplied rather than as a silently truncated digit in
         * a rendered header.
         *
         * @throws IllegalArgumentException if any component cannot be represented in its declared
         *                                  digit count, or the offset exceeds plus or minus 23:59
         */
        public CapturedDateTime {
            requireDigitWidth(year, YEAR_DIGITS, WS_CURDATE_YEAR);
            requireDigitWidth(month, MONTH_DIGITS, WS_CURDATE_MONTH);
            requireDigitWidth(day, DAY_DIGITS, WS_CURDATE_DAY);
            requireDigitWidth(hours, HOURS_DIGITS, WS_CURTIME_HOURS);
            requireDigitWidth(minutes, MINUTE_DIGITS, WS_CURTIME_MINUTE);
            requireDigitWidth(seconds, SECOND_DIGITS, WS_CURTIME_SECOND);
            requireDigitWidth(hundredths, MILSEC_DIGITS, WS_CURTIME_MILSEC);
            requireDigitWidth(microseconds, MICROSECOND_DIGITS, WS_TIMESTAMP_TM_MS6);
            if (gmtOffsetMinutes < -MAX_GMT_OFFSET_MINUTES
                    || gmtOffsetMinutes > MAX_GMT_OFFSET_MINUTES) {
                throw new IllegalArgumentException("GMT offset of " + gmtOffsetMinutes
                        + " minute(s) cannot be rendered as the " + GMT_OFFSET_LENGTH
                        + "-character shhmm tail that FUNCTION CURRENT-DATE appends; the hour part "
                        + "would need more than two digits");
            }
        }

        /**
         * Decomposes one local date and time, together with the offset that produced it, into the
         * copybook's components. The nanosecond-of-second is reduced twice, at the two precisions the
         * copybook declares, and truncated toward zero in both cases because COBOL truncates on store
         * and {@code ROUNDED} appears nowhere in this codebase.
         *
         * @param dateTime         the local date and time; the sole source of every component
         * @param gmtOffsetMinutes the offset from Greenwich in whole minutes
         * @return the captured components
         * @throws NullPointerException     if {@code dateTime} is {@code null}
         * @throws IllegalArgumentException if any resulting component falls outside its declared
         *                                  digit count
         */
        public static CapturedDateTime of(LocalDateTime dateTime, int gmtOffsetMinutes) {
            Objects.requireNonNull(dateTime, "A local date and time is required to build a date "
                    + "header; this type never reads a clock of its own, so the instant must be "
                    + "supplied by the caller");
            int nanoOfSecond = dateTime.getNano();
            return new CapturedDateTime(dateTime.getYear(),
                    dateTime.getMonthValue(),
                    dateTime.getDayOfMonth(),
                    dateTime.getHour(),
                    dateTime.getMinute(),
                    dateTime.getSecond(),
                    nanoOfSecond / NANOS_PER_HUNDREDTH,
                    nanoOfSecond / NANOS_PER_MICROSECOND,
                    gmtOffsetMinutes);
        }

        /**
         * Rejects a component that its {@code PICTURE} cannot hold. The exclusive limit is computed
         * by repeated integer multiplication rather than by {@code Math.pow}, because no value in
         * this class - not even an intermediate one - is ever represented as a floating-point number.
         */
        private static void requireDigitWidth(int value, int digits, String fieldName) {
            int exclusiveLimit = 1;
            for (int decade = 0; decade < digits; decade++) {
                exclusiveLimit *= 10;
            }
            if (value < 0 || value >= exclusiveLimit) {
                throw new IllegalArgumentException("Value " + value + " does not fit " + fieldName
                        + " PIC 9(0" + digits + "), which holds 0 to " + (exclusiveLimit - 1)
                        + "; a wider value would be truncated on the left and the loss would be "
                        + "invisible in the rendered header");
            }
        }
    }

    /**
     * The module's fixed-width codec: the single implementation of COBOL's zero-fill and truncation
     * rules, and the holder of the code page {@link #toBytes()} encodes with. Supplied by the caller
     * through the constructor and never resolved statically, so no second zero-fill exists in this
     * class and no code page is ever assumed.
     */
    private final FixedWidthCodec codec;

    /** The one captured instant every rendering below is projected from. Never reassigned. */
    private final CapturedDateTime captured;

    /**
     * Pairs a captured instant with the codec that will render it. Private: an instance is always
     * obtained from one of the factories, each of which names where its instant came from.
     */
    private DateHeader(FixedWidthCodec codec, CapturedDateTime captured) {
        this.codec = codec;
        this.captured = captured;
    }

    // =================================================================================================
    // Factories. Every one of them takes its instant from the caller. There is no no-argument form,
    // because a no-argument form could only read the wall clock.
    // =================================================================================================

    /**
     * Captures the instant a {@link Clock} reports, reading it <strong>once</strong>.
     *
     * <p>This is the production entry point and the reason the class is testable: pass the
     * application's clock in a controller, pass {@code Clock.fixed(instant, zone)} in a test or a
     * parity case, and the rendered bytes are identical every time. The clock supplies both halves of
     * what {@code FUNCTION CURRENT-DATE} returns - its zone converts the instant to a local date and
     * time, and the zone's rules at that instant give the {@code shhmm} offset the intrinsic appends
     * and {@code WS-CURDATE-DATA} discards.
     *
     * <p>Any residual seconds in an offset are dropped, because {@code shhmm} cannot express them.
     * Only a handful of historical zones have such offsets and none is in use at any modern instant.
     *
     * @param codec the fixed-width codec supplying zero-fill and the code page
     * @param clock the clock to read; read exactly once, so the renderings cannot straddle a second
     *              boundary
     * @return the header, capturing the clock's current instant
     * @throws NullPointerException     if {@code codec} or {@code clock} is {@code null}
     * @throws IllegalArgumentException if the clock reports an instant outside the range a
     *                                  {@code PIC 9(04)} year can hold
     */
    public static DateHeader from(FixedWidthCodec codec, Clock clock) {
        requireCodec(codec);
        Objects.requireNonNull(clock, "A Clock is required: this type never calls now() of its own, "
                + "because a header that read the wall clock could not be compared byte for byte "
                + "against an expected parity image");
        Instant instant = clock.instant();
        ZoneId zone = clock.getZone();
        ZoneOffset offset = zone.getRules().getOffset(instant);
        return new DateHeader(codec, CapturedDateTime.of(LocalDateTime.ofInstant(instant, zone),
                offset.getTotalSeconds() / SECONDS_PER_MINUTE));
    }

    /**
     * Captures an explicit local date and time, treating it as having no offset from Greenwich.
     *
     * <p>Intended for parity cases and unit tests, which state the instant outright. The offset is
     * taken as {@code +0000} because a {@link LocalDateTime} carries none, and it affects nothing but
     * the five characters {@link #functionCurrentDate()} appends and {@link #wsCurdateData()} then
     * truncates away. Use {@link #of(FixedWidthCodec, LocalDateTime, ZoneOffset)} where the offset
     * matters.
     *
     * @param codec    the fixed-width codec supplying zero-fill and the code page
     * @param dateTime the local date and time to render
     * @return the header, capturing {@code dateTime}
     * @throws NullPointerException     if {@code codec} or {@code dateTime} is {@code null}
     * @throws IllegalArgumentException if any component falls outside its declared digit count
     */
    public static DateHeader of(FixedWidthCodec codec, LocalDateTime dateTime) {
        return of(codec, dateTime, ZoneOffset.UTC);
    }

    /**
     * Captures an explicit local date and time together with an explicit offset from Greenwich.
     *
     * @param codec    the fixed-width codec supplying zero-fill and the code page
     * @param dateTime the local date and time to render
     * @param offset   the offset {@code FUNCTION CURRENT-DATE} would report alongside it; any
     *                 residual seconds are dropped, since {@code shhmm} cannot carry them
     * @return the header, capturing {@code dateTime} at {@code offset}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if any component falls outside its declared digit count, or
     *                                  the offset exceeds plus or minus 23:59
     */
    public static DateHeader of(FixedWidthCodec codec, LocalDateTime dateTime, ZoneOffset offset) {
        requireCodec(codec);
        Objects.requireNonNull(offset, "A ZoneOffset is required; pass ZoneOffset.UTC for the "
                + "+0000 tail, and note that the offset is discarded by the 16-byte WS-CURDATE-DATA "
                + "receiver in any case");
        return new DateHeader(codec,
                CapturedDateTime.of(dateTime, offset.getTotalSeconds() / SECONDS_PER_MINUTE));
    }

    /**
     * Rebuilds a header from an already-rendered 26-character {@code WS-TIMESTAMP} image, modelling
     * {@code MOVE TRAN-ORIG-TS TO WS-TIMESTAMP} at {@code app/cbl/COTRN00C.cbl:384}.
     *
     * <p>{@code COTRN00C} moves a stored transaction timestamp into the group and then reads
     * {@code WS-TIMESTAMP-DT-YYYY(3:2)}, {@code -DT-MM} and {@code -DT-DD} back out to paint the
     * screen. That makes the group an input as well as an output, and this factory is that direction.
     * The separators are verified against the copybook's declared literals rather than skipped over,
     * so a 26-character value in some other convention - {@code CBACT04C}'s {@code DB2-FORMAT-TS}
     * form, for instance - is rejected here rather than decoded into plausible-looking nonsense.
     *
     * <p>{@code WS-CURTIME-MILSEC} is derived as the hundredths implied by the image's six-digit
     * microsecond field, which is the only fractional information the image carries.
     *
     * @param codec the fixed-width codec supplying zero-fill and the code page
     * @param image exactly {@value #WS_TIMESTAMP_LENGTH} characters in
     *              {@code YYYY-MM-DD HH:MM:SS.ssssss} form
     * @return the header the image denotes, carrying a {@code +0000} offset because an image records
     *         none
     * @throws NullPointerException     if {@code codec} or {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly 26 characters, does not carry
     *                                  the declared separators at the declared positions, or holds a
     *                                  non-digit where a digit belongs
     */
    public static DateHeader ofTimestampImage(FixedWidthCodec codec, String image) {
        requireCodec(codec);
        Objects.requireNonNull(image, "A 26-character WS-TIMESTAMP image is required; this factory "
                + "models MOVE TRAN-ORIG-TS TO WS-TIMESTAMP, so its input is the stored image");
        if (image.length() != WS_TIMESTAMP_LENGTH) {
            throw new IllegalArgumentException("WS-TIMESTAMP is PIC X(" + WS_TIMESTAMP_LENGTH
                    + ") but the supplied image is " + image.length() + " character(s); a stored "
                    + "TRAN-ORIG-TS moved into this group is always exactly its declared width");
        }

        // MOVE TRAN-ORIG-TS TO WS-TIMESTAMP. The group is an alphanumeric receiver of exactly this
        // width, so the image lands verbatim; the sub-fields are then read back through the very
        // same layout that toBytes() writes with, which is what keeps the two directions consistent
        // without a single offset literal appearing here.
        FixedWidthRecord area = codec.newRecord(WS_DATE_TIME_LAYOUT);
        codec.writePicX(area, WS_DATE_TIME_LAYOUT.span(WS_TIMESTAMP), image);

        // Read in copybook declaration order, so a malformed image is reported against the FIRST
        // sub-field that is wrong rather than against whichever one an expression happened to
        // evaluate first. Held in locals for that reason alone.
        int year = readTimestampField(codec, area, WS_TIMESTAMP_DT_YYYY);
        int month = readTimestampField(codec, area, WS_TIMESTAMP_DT_MM);
        int day = readTimestampField(codec, area, WS_TIMESTAMP_DT_DD);
        int hours = readTimestampField(codec, area, WS_TIMESTAMP_TM_HH);
        int minutes = readTimestampField(codec, area, WS_TIMESTAMP_TM_MM);
        int seconds = readTimestampField(codec, area, WS_TIMESTAMP_TM_SS);
        int microseconds = readTimestampField(codec, area, WS_TIMESTAMP_TM_MS6);

        DateHeader header = new DateHeader(codec, new CapturedDateTime(year, month, day,
                hours, minutes, seconds,
                microseconds / MICROSECONDS_PER_HUNDREDTH,
                microseconds,
                NO_GMT_OFFSET));

        // Re-render and compare. This is what verifies the separators, and it verifies them all at
        // once: an image whose declared FILLER positions hold anything other than the copybook's
        // '-', ' ', ':' and '.' cannot reproduce itself, and is therefore not a WS-TIMESTAMP image.
        String reRendered = header.wsTimestamp();
        if (!reRendered.equals(image)) {
            throw new IllegalArgumentException("Image '" + image + "' does not round-trip as a "
                    + "WS-TIMESTAMP: re-rendering its fields through the declared layout yields '"
                    + reRendered + "'. WS-TIMESTAMP declares its separators as FILLER ... VALUE "
                    + "literals - '" + TIMESTAMP_DATE_SEPARATOR + "', '"
                    + TIMESTAMP_DATE_TIME_SEPARATOR + "', '" + TIME_SEPARATOR + "' and '"
                    + TIMESTAMP_FRACTION_SEPARATOR + "' - so an image in another convention, such "
                    + "as CBACT04C's DB2-FORMAT-TS form, is a different layout rather than a "
                    + "variant of this one");
        }
        return header;
    }

    private static void requireCodec(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required: it owns this module's only "
                + "zero-fill implementation and the code page the 58-byte image is encoded in, "
                + "neither of which this class re-implements or assumes");
    }

    /**
     * Reads one numeric sub-field of {@code WS-TIMESTAMP} out of a populated record area, naming the
     * field if its bytes are not the digits the layout declares. Without this wrapper a malformed
     * stored timestamp would report only the offending characters, leaving the reader to work out
     * which of the seven sub-fields they belong to.
     */
    private static int readTimestampField(FixedWidthCodec codec,
                                          FixedWidthRecord area,
                                          String fieldName) {
        FieldSpan span = WS_DATE_TIME_LAYOUT.span(fieldName);
        try {
            return codec.readPic9AsInt(area, span);
        } catch (IllegalArgumentException notDigits) {
            throw new IllegalArgumentException("WS-TIMESTAMP sub-field " + fieldName
                    + " is declared PIC 9(0" + span.length() + ") at byte "
                    + (span.offset() - WS_TIMESTAMP_OFFSET + 1) + " of the 26-character image, but "
                    + "the image holds '" + area.readSpan(span) + "' there", notDigits);
        }
    }

    // =================================================================================================
    // The captured instant, and the codec that renders it.
    // =================================================================================================

    /**
     * The instant this header was built from, decomposed into the copybook's components. Every
     * rendering below is a projection of this one value, which is why they can never disagree.
     *
     * @return the captured components; an immutable record
     */
    public CapturedDateTime captured() {
        return captured;
    }

    /**
     * The codec this header renders through - the source of its zero-fill and of the code page
     * {@link #toBytes()} encodes in.
     *
     * @return the codec supplied at construction
     */
    public FixedWidthCodec codec() {
        return codec;
    }

    // =================================================================================================
    // WS-CURDATE-DATA and its two REDEFINES views.
    // =================================================================================================

    /**
     * {@code WS-CURDATE} - 8 characters, {@code YYYYMMDD}.
     *
     * <p>{@code app/cpy/CSDAT01Y.cpy:19-22}. Each component is zero-filled on the left to its
     * declared digit count through the codec, so 2 January renders {@code 20240102} and never
     * {@code 202412}.
     *
     * @return exactly {@value #WS_CURDATE_LENGTH} characters
     */
    public String wsCurdate() {
        return pic9(captured.year(), YEAR_DIGITS)
                + pic9(captured.month(), MONTH_DIGITS)
                + pic9(captured.day(), DAY_DIGITS);
    }

    /**
     * {@code WS-CURDATE-N REDEFINES WS-CURDATE PIC 9(08)} - the same eight bytes read as one
     * unsigned number, {@code app/cpy/CSDAT01Y.cpy:23}.
     *
     * <p>This is deliberately implemented as a <em>decode of {@link #wsCurdate()}</em> rather than as
     * an independently computed number. A {@code REDEFINES} item is a second view of one storage span,
     * not a second field, so the two cannot be allowed to drift; deriving one from the other is what
     * guarantees they never do. Note that the leading zeros of a year below 1000 are lost in the
     * numeric view, exactly as they are in COBOL - the digits are still there in the eight-byte span.
     *
     * @return the value the eight-character {@code YYYYMMDD} span denotes, 0 to 99999999
     */
    public int wsCurdateN() {
        return codec.decodePic9AsInt(wsCurdate());
    }

    /**
     * {@code WS-CURTIME} - 8 characters, {@code HHMMSSss}, where {@code ss} is
     * <strong>hundredths</strong> of a second from {@code WS-CURTIME-MILSEC}.
     *
     * <p>{@code app/cpy/CSDAT01Y.cpy:24-28}.
     *
     * @return exactly {@value #WS_CURTIME_LENGTH} characters
     */
    public String wsCurtime() {
        return pic9(captured.hours(), HOURS_DIGITS)
                + pic9(captured.minutes(), MINUTE_DIGITS)
                + pic9(captured.seconds(), SECOND_DIGITS)
                + pic9(captured.hundredths(), MILSEC_DIGITS);
    }

    /**
     * {@code WS-CURTIME-N REDEFINES WS-CURTIME PIC 9(08)} - the same eight bytes read as one
     * unsigned number, {@code app/cpy/CSDAT01Y.cpy:29}. Derived from {@link #wsCurtime()} for the
     * same reason {@link #wsCurdateN()} is derived from {@link #wsCurdate()}.
     *
     * @return the value the eight-character {@code HHMMSSss} span denotes, 0 to 99999999
     */
    public int wsCurtimeN() {
        return codec.decodePic9AsInt(wsCurtime());
    }

    /**
     * The 21-character result of {@code FUNCTION CURRENT-DATE}: {@code YYYYMMDD} then
     * {@code HHMMSSss} then the {@code shhmm} offset from Greenwich.
     *
     * <p>Modelled explicitly rather than left implicit, because the whole behaviour of
     * {@link #wsCurdateData()} is a consequence of this value being three characters and a sign wider
     * than its receiver. The width is corroborated inside this repository by
     * {@code 01 COBOL-TS} at {@code app/cbl/CBACT04C.cbl:141-149}, whose eight items sum to exactly
     * 21 bytes and which receives this intrinsic at line 614.
     *
     * @return exactly {@value #FUNCTION_CURRENT_DATE_LENGTH} characters
     */
    public String functionCurrentDate() {
        return wsCurdate() + wsCurtime() + gmtOffsetImage();
    }

    /**
     * The {@code shhmm} offset from Greenwich that {@code FUNCTION CURRENT-DATE} appends -
     * {@code COB-REST PIC X(05)} in {@code app/cbl/CBACT04C.cbl:149}.
     *
     * @return exactly {@value #GMT_OFFSET_LENGTH} characters, for example {@code +0000} or
     *         {@code -0600}
     */
    public String gmtOffsetImage() {
        int offsetMinutes = captured.gmtOffsetMinutes();
        char sign = offsetMinutes < 0 ? GMT_OFFSET_NEGATIVE_SIGN : GMT_OFFSET_POSITIVE_SIGN;
        int magnitude = Math.abs(offsetMinutes);
        return sign
                + pic9(magnitude / MINUTES_PER_HOUR, HOURS_DIGITS)
                + pic9(magnitude % MINUTES_PER_HOUR, MINUTE_DIGITS);
    }

    /**
     * {@code WS-CURDATE-DATA} - 16 characters, {@code YYYYMMDDHHMMSSss}.
     *
     * <p>This is {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA}, and it is implemented as
     * exactly that: {@link #functionCurrentDate()} put through the codec's alphanumeric move at this
     * group's declared width. A cross-width alphanumeric {@code MOVE} truncates on the
     * <strong>right</strong>, so the five-character GMT offset is discarded and the header carries no
     * timezone information at all. Defining the accessor as the truncating move rather than as a
     * separate concatenation is what makes that loss real rather than merely documented; see
     * {@link #discardedGmtOffset()} for the characters that fall off.
     *
     * <p>Verified at {@code app/cbl/COMEN01C.cbl:214} and {@code app/cbl/COSGN00C.cbl:179}.
     *
     * @return exactly {@value #WS_CURDATE_DATA_LENGTH} characters
     */
    public String wsCurdateData() {
        return codec.movePicX(functionCurrentDate(), WS_CURDATE_DATA_LENGTH);
    }

    /**
     * The five characters that {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} throws away:
     * the tail of {@link #functionCurrentDate()} beyond this group's 16 bytes.
     *
     * <p>Exposed so the truncation is provable in a test rather than asserted in a comment. It equals
     * {@link #gmtOffsetImage()}, but it is computed from the intrinsic result, which is what makes it
     * evidence.
     *
     * @return exactly {@value #GMT_OFFSET_LENGTH} characters
     */
    public String discardedGmtOffset() {
        return functionCurrentDate().substring(WS_CURDATE_DATA_LENGTH);
    }

    // =================================================================================================
    // The two edited screen renderings. Both carry separator FILLERs that emit their declared VALUE.
    // =================================================================================================

    /**
     * {@code WS-CURDATE-MM-DD-YY} - 8 characters, {@code MM/DD/YY}, the date as every screen's
     * heading line shows it. {@code app/cpy/CSDAT01Y.cpy:30-35}.
     *
     * <p>The two {@code '/'} characters are the copybook's own
     * {@code FILLER PIC X(01) VALUE '/'} declarations at lines 32 and 34, not a formatting choice,
     * and the year is <strong>two digits</strong> - see {@link #wsCurdateYy()}.
     *
     * @return exactly {@value #WS_CURDATE_MM_DD_YY_LENGTH} characters, for example {@code 12/25/24}
     */
    public String wsCurdateMmDdYy() {
        return pic9(captured.month(), MONTH_DIGITS)
                + DATE_SEPARATOR
                + pic9(captured.day(), DAY_DIGITS)
                + DATE_SEPARATOR
                + wsCurdateYy();
    }

    /**
     * {@code WS-CURDATE-YY} - the two-digit year, {@code app/cpy/CSDAT01Y.cpy:35}.
     *
     * <p>The COBOL fills it by <em>reference modification</em>:
     * {@code MOVE WS-CURDATE-YEAR(3:2) TO WS-CURDATE-YY} at {@code app/cbl/COMEN01C.cbl:223} and
     * {@code app/cbl/COSGN00C.cbl:188}, which takes character positions 3 and 4 of the four-digit
     * year - its low-order two digits. {@code app/cbl/COTRN00C.cbl:385} does the same off
     * {@code WS-TIMESTAMP-DT-YYYY}. That mechanism is reproduced literally here, as a substring of
     * the rendered four-digit year, and it agrees with the numeric {@code MOVE} rule that a
     * {@code PIC 9(04)} into a {@code PIC 9(02)} receiver keeps the low-order digits: 2024 gives
     * {@code 24}, 1999 gives {@code 99} and 2000 gives {@code 00}.
     *
     * @return exactly {@value #TWO_DIGIT_YEAR_DIGITS} characters
     */
    public String wsCurdateYy() {
        return pic9(captured.year(), YEAR_DIGITS).substring(YEAR_DIGITS - TWO_DIGIT_YEAR_DIGITS);
    }

    /**
     * {@code WS-CURTIME-HH-MM-SS} - 8 characters, {@code HH:MM:SS}, the time as every screen's
     * heading line shows it. {@code app/cpy/CSDAT01Y.cpy:36-41}.
     *
     * <p>The two {@code ':'} characters are the copybook's
     * {@code FILLER PIC X(01) VALUE ':'} declarations at lines 38 and 40. Note that the hundredths
     * this system captures in {@code WS-CURTIME-MILSEC} do <em>not</em> appear here: the screen shows
     * whole seconds only.
     *
     * @return exactly {@value #WS_CURTIME_HH_MM_SS_LENGTH} characters, for example {@code 03:04:05}
     */
    public String wsCurtimeHhMmSs() {
        return pic9(captured.hours(), HOURS_DIGITS)
                + TIME_SEPARATOR
                + pic9(captured.minutes(), MINUTE_DIGITS)
                + TIME_SEPARATOR
                + pic9(captured.seconds(), SECOND_DIGITS);
    }

    // =================================================================================================
    // The two 26-byte timestamps. Same width, same field partition, different separators - and the
    // difference is a property of the sources, recorded rather than reconciled.
    // =================================================================================================

    /**
     * {@code WS-TIMESTAMP} - 26 characters, {@code YYYY-MM-DD HH:MM:SS.ssssss}.
     * {@code app/cpy/CSDAT01Y.cpy:42-55}.
     *
     * <p>Every separator is a declared literal: {@code '-'} at lines 44 and 46, a
     * <strong>space</strong> at line 48, {@code ':'} at lines 50 and 52 and {@code '.'} at line 54.
     * The fractional part is the six-digit {@code WS-TIMESTAMP-TM-MS6}, that is microseconds - a
     * different and finer precision than the two-digit hundredths of {@link #wsCurtime()}, and not
     * obtained by padding them.
     *
     * <p>This is the form {@code app/cbl/COBIL00C.cbl:231} moves into {@code TRAN-ORIG-TS} and
     * {@code app/cbl/COTRN00C.cbl:384} moves back out of it. It is <em>not</em> the form
     * {@code CBACT04C} writes - see {@link #db2FormatTimestamp()}.
     *
     * @return exactly {@value #WS_TIMESTAMP_LENGTH} characters
     */
    public String wsTimestamp() {
        return pic9(captured.year(), YEAR_DIGITS)
                + TIMESTAMP_DATE_SEPARATOR
                + pic9(captured.month(), MONTH_DIGITS)
                + TIMESTAMP_DATE_SEPARATOR
                + pic9(captured.day(), DAY_DIGITS)
                + TIMESTAMP_DATE_TIME_SEPARATOR
                + pic9(captured.hours(), HOURS_DIGITS)
                + TIME_SEPARATOR
                + pic9(captured.minutes(), MINUTE_DIGITS)
                + TIME_SEPARATOR
                + pic9(captured.seconds(), SECOND_DIGITS)
                + TIMESTAMP_FRACTION_SEPARATOR
                + pic9(captured.microseconds(), MICROSECOND_DIGITS);
    }

    /**
     * {@code DB2-FORMAT-TS PIC X(26)} as {@code app/cbl/CBACT04C.cbl} builds it - 26 characters in
     * {@code YYYY-MM-DD-HH.MM.SS.ssssss} form.
     *
     * <p>The same width and the same field partition as {@link #wsTimestamp()}, but
     * <strong>three differences</strong>, all read from
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP} at {@code app/cbl/CBACT04C.cbl:613-626}:
     * <ul>
     *   <li>the separator after the day is {@code '-'} ({@code DB2-STREEP-3}, set at line 623) where
     *       this copybook declares a space;</li>
     *   <li>the time separators are {@code '.'} ({@code DB2-DOT-1} and {@code DB2-DOT-2}, set at line
     *       624) where this copybook declares colons;</li>
     *   <li>the six fractional positions are {@code DB2-MIL PIC 9(02)} - the same hundredths
     *       {@code WS-CURTIME-MILSEC} carries, moved at line 621 - followed by the literal
     *       {@code '0000'} moved into {@code DB2-REST PIC X(04)} at line 622. So this form really is
     *       hundredths padded with four zeros, whereas {@link #wsTimestamp()} carries true
     *       microseconds.</li>
     * </ul>
     * Both forms are provided, distinctly named, so a translator uses the one its program declares
     * instead of assuming the two are interchangeable. The discrepancy is recorded here as a finding
     * about the sources; neither source is corrected.
     *
     * @return exactly {@value #WS_TIMESTAMP_LENGTH} characters
     */
    public String db2FormatTimestamp() {
        return pic9(captured.year(), YEAR_DIGITS)
                + TIMESTAMP_DATE_SEPARATOR
                + pic9(captured.month(), MONTH_DIGITS)
                + TIMESTAMP_DATE_SEPARATOR
                + pic9(captured.day(), DAY_DIGITS)
                + DB2_DATE_TIME_SEPARATOR
                + pic9(captured.hours(), HOURS_DIGITS)
                + DB2_TIME_SEPARATOR
                + pic9(captured.minutes(), MINUTE_DIGITS)
                + DB2_TIME_SEPARATOR
                + pic9(captured.seconds(), SECOND_DIGITS)
                + DB2_TIME_SEPARATOR
                + pic9(captured.hundredths(), MILSEC_DIGITS)
                + DB2_FRACTION_REMAINDER;
    }

    // =================================================================================================
    // The whole 58-byte structure.
    // =================================================================================================

    /**
     * Every referable elementary field of {@code 01 WS-DATE-TIME} as its raw image, keyed by the
     * copybook's own field name and returned in copybook declaration order.
     *
     * <p>These 20 entries are what {@link #toBytes()} writes and what a field-by-field comparison
     * consumes: names verbatim, images untrimmed and zero-filled to their declared widths. The
     * separator {@code FILLER}s are absent because {@code FILLER} is not a referable COBOL name and
     * this copybook declares ten of them - they cannot be distinct keys. They are not thereby
     * unchecked: {@link #WS_DATE_TIME_LAYOUT} proves each is present and emits its declared literal.
     * The group items and the two {@code REDEFINES} views are absent for a different reason - they
     * are views of bytes these 20 entries already supply, so including them would let a caller
     * overwrite the same span twice.
     *
     * @return an unmodifiable, insertion-ordered map of 20 field names to images
     */
    public Map<String, String> fieldImages() {
        Map<String, String> images = new LinkedHashMap<>();
        images.put(WS_CURDATE_YEAR, pic9(captured.year(), YEAR_DIGITS));
        images.put(WS_CURDATE_MONTH, pic9(captured.month(), MONTH_DIGITS));
        images.put(WS_CURDATE_DAY, pic9(captured.day(), DAY_DIGITS));
        images.put(WS_CURTIME_HOURS, pic9(captured.hours(), HOURS_DIGITS));
        images.put(WS_CURTIME_MINUTE, pic9(captured.minutes(), MINUTE_DIGITS));
        images.put(WS_CURTIME_SECOND, pic9(captured.seconds(), SECOND_DIGITS));
        images.put(WS_CURTIME_MILSEC, pic9(captured.hundredths(), MILSEC_DIGITS));
        images.put(WS_CURDATE_MM, pic9(captured.month(), MONTH_DIGITS));
        images.put(WS_CURDATE_DD, pic9(captured.day(), DAY_DIGITS));
        images.put(WS_CURDATE_YY, wsCurdateYy());
        images.put(WS_CURTIME_HH, pic9(captured.hours(), HOURS_DIGITS));
        images.put(WS_CURTIME_MM, pic9(captured.minutes(), MINUTE_DIGITS));
        images.put(WS_CURTIME_SS, pic9(captured.seconds(), SECOND_DIGITS));
        images.put(WS_TIMESTAMP_DT_YYYY, pic9(captured.year(), YEAR_DIGITS));
        images.put(WS_TIMESTAMP_DT_MM, pic9(captured.month(), MONTH_DIGITS));
        images.put(WS_TIMESTAMP_DT_DD, pic9(captured.day(), DAY_DIGITS));
        images.put(WS_TIMESTAMP_TM_HH, pic9(captured.hours(), HOURS_DIGITS));
        images.put(WS_TIMESTAMP_TM_MM, pic9(captured.minutes(), MINUTE_DIGITS));
        images.put(WS_TIMESTAMP_TM_SS, pic9(captured.seconds(), SECOND_DIGITS));
        images.put(WS_TIMESTAMP_TM_MS6, pic9(captured.microseconds(), MICROSECOND_DIGITS));
        return Collections.unmodifiableMap(images);
    }

    /**
     * The complete {@code 01 WS-DATE-TIME} area as {@value #WS_DATE_TIME_LENGTH} bytes in the codec's
     * code page.
     *
     * <p>Built by initialising the layout - which is where each separator {@code FILLER} emits its
     * declared {@code VALUE} rather than a pad byte - and then writing the 20 field images over it.
     * The result is provable in both directions: its length is fixed by the layout's self-check, and
     * {@code codec.deserialise(WS_DATE_TIME_LAYOUT, bytes)} reads back every field, group and
     * {@code REDEFINES} view by name.
     *
     * @return a fresh array of exactly {@value #WS_DATE_TIME_LENGTH} bytes
     */
    public byte[] toBytes() {
        return codec.serialise(WS_DATE_TIME_LAYOUT, fieldImages());
    }

    /**
     * Zero-fills a component to its declared digit count through the codec.
     *
     * <p>Every numeric rendering in this class goes through here, and here goes through
     * {@link FixedWidthCodec#movePic9(long, int)}. There is deliberately no local padding, no
     * {@code String.format} and no {@code %02d}: the module has exactly one implementation of
     * COBOL's left zero-fill, and a second one - even a correct-looking one - would be a place for
     * the two to diverge later. It also keeps this class free of any locale-sensitive formatting.
     */
    private String pic9(int value, int digits) {
        return codec.movePic9(value, digits);
    }

    // =================================================================================================
    // Value semantics.
    // =================================================================================================

    /**
     * Two headers are equal when they captured the same instant and render in the same code page.
     *
     * <p>The code page is part of the identity because it decides {@link #toBytes()}: the same instant
     * rendered through {@code IBM037} and through {@code US-ASCII} produces the same characters but
     * different bytes. The codec's charset is compared rather than the codec instance itself, since
     * the charset is the whole of a codec's state and two codecs over one charset are
     * interchangeable.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a {@code DateHeader} with the same captured instant
     *         and code page
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DateHeader that)) {
            return false;
        }
        return captured.equals(that.captured)
                && codec.charset().equals(that.codec.charset());
    }

    /**
     * Consistent with {@link #equals(Object)}: derived from the captured instant and the code page.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(captured, codec.charset());
    }

    /**
     * A diagnostic rendering naming the captured instant and the code page.
     *
     * <p>Deliberately <em>not</em> one of the copybook's field images: a log line that looked like a
     * {@code WS-TIMESTAMP} could be mistaken for one when a parity difference is being traced. The
     * timestamp form is included because it is the most legible of the renderings, but it is labelled.
     *
     * @return for example
     *         {@code DateHeader[WS-TIMESTAMP=2024-12-25 13:45:07.089123, offset=+0000, charset=US-ASCII]}
     */
    @Override
    public String toString() {
        return "DateHeader[" + WS_TIMESTAMP + "=" + wsTimestamp()
                + ", offset=" + gmtOffsetImage()
                + ", charset=" + codec.charset().name() + "]";
    }
}
