package com.vsergeychik.carddemo.util;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * The Java translation of {@code app/cbl/CSUTLDTC.cbl} - the CardDemo date-validation subprogram
 * that wraps the IBM Language Environment service {@code CEEDAYS}.
 *
 * <h2>The class name says "Job"; the source is a called subprogram, and this is a {@code @Service}</h2>
 * The name {@code DateUtilityJob} is <strong>mandated verbatim</strong> and is deliberately
 * <em>not</em> corrected here, because a mandated name never authorises a change of behaviour: the
 * name comes from the migration plan and the behaviour comes from the COBOL source. The behaviour is
 * unambiguous - {@code CSUTLDTC} is a {@code CALL}ed subprogram, not a batch job:
 * <ul>
 *   <li>it has four call sites, all in <em>online</em> CICS programs -
 *       {@code app/cbl/CORPT00C.cbl} at L392 and L412, and {@code app/cbl/COTRN02C.cbl} at L393 and
 *       L413;</li>
 *   <li>a search for {@code CSUTLDTC} across {@code app/jcl/}, {@code app/proc/} and
 *       {@code app/csd/} returns <strong>zero</strong> matches, so there is no {@code EXEC PGM=}
 *       step, no DD binding, and no CICS {@code PROGRAM} or {@code TRANSACTION} definition anywhere
 *       that could make it a job or a transaction.</li>
 * </ul>
 * It is therefore a plain Spring {@link Service} invoked by an ordinary Java method call. Nothing in
 * this file references Spring Batch: there is no {@code Job}, no {@code Step}, no {@code Tasklet},
 * no {@code JobParameters} and no {@code ExitStatus}. The conflict between the name and the
 * behaviour is recorded here rather than quietly resolved, which is the standing rule for every
 * conflict found during this migration.
 *
 * <h2>The contract: three COBOL parameters, two in and one out</h2>
 * {@code CSUTLDTC.cbl} L83-L88 declares
 * <pre>
 *   LINKAGE SECTION.
 *      01 LS-DATE         PIC X(10).
 *      01 LS-DATE-FORMAT  PIC X(10).
 *      01 LS-RESULT       PIC X(80).
 *   PROCEDURE DIVISION USING LS-DATE, LS-DATE-FORMAT, LS-RESULT.
 * </pre>
 * {@code LS-RESULT} is the output parameter, so in Java it becomes the return value rather than a
 * mutated argument: {@link #validateDate(String, String)} takes the two inputs and returns a
 * {@link DateValidationResult}. Both inputs are received as declared {@code PIC X(10)} values and
 * are normalised to exactly ten characters through the fixed-width codec - right-padded with spaces
 * when short and right-truncated when long - because a COBOL caller physically cannot pass anything
 * else. No further validation, null-hostility or sanitisation is added: the COBOL simply uses
 * whatever ten bytes it is handed, and so does this class.
 *
 * <h2>The 80-byte result is a byte-exact fixed-width image</h2>
 * {@code WS-MESSAGE} (L42-L57) is a positional record whose declared spans sum to exactly 80 bytes.
 * It is built here through {@link FixedWidthRecord} and {@link FixedWidthCodec} at absolute offsets,
 * never by string concatenation, and its geometry is proved mechanically: {@link RecordLayout}
 * refuses to be constructed unless every span is contiguous from offset 0 and the total is exactly
 * the declared record length, so a dropped or mis-sized span fails at class-initialisation time
 * rather than corrupting the wire format.
 *
 * <p>Three of the spans are {@code FILLER}s carrying literal {@code VALUE} clauses, and all three
 * are <strong>wider than their text</strong> or exactly fit it:
 * <table border="1">
 *   <caption>The literal FILLER spans</caption>
 *   <tr><th>Offset</th><th>Declared</th><th>Literal</th><th>Emitted</th></tr>
 *   <tr><td>4</td><td>{@code PIC X(11)}</td><td>{@code 'Mesg Code:'} (10)</td>
 *       <td>{@code "Mesg Code: "} - one trailing pad space</td></tr>
 *   <tr><td>36</td><td>{@code PIC X(09)}</td><td>{@code 'TstDate:'} (8)</td>
 *       <td>{@code "TstDate: "} - one trailing pad space</td></tr>
 *   <tr><td>56</td><td>{@code PIC X(10)}</td><td>{@code 'Mask used:'} (10)</td>
 *       <td>{@code "Mask used:"} - an exact fit, no pad</td></tr>
 * </table>
 * These literals appear in <em>every</em> output. {@code INITIALIZE WS-MESSAGE} (L90) sets
 * elementary {@code PIC X} items to spaces and {@code PIC 9} items to zero but ignores {@code FILLER}
 * items and non-first {@code REDEFINES}, so the three literals survive from their {@code VALUE}
 * clauses untouched. Trimming a pad byte or omitting a span would shift every following offset and
 * break both callers, which slice the 80 bytes positionally.
 *
 * <h2>Two {@code REDEFINES} pairs, both written numerically and read as characters</h2>
 * L43/L44 and L46/L47 declare {@code WS-SEVERITY PIC X(04)} overlaid by
 * {@code WS-SEVERITY-N PIC 9(4)}, and {@code WS-MSG-NO PIC X(04)} overlaid by
 * {@code WS-MSG-NO-N PIC 9(4)}. Each pair is modelled as two typed descriptors over <em>one</em>
 * backing span, so a write through the numeric view is immediately visible through the character
 * view exactly as in COBOL. The program writes both numerically (L123, L124) and both callers read
 * them as text, comparing against the literals {@code '0000'} and {@code '2513'}. Both views are
 * scale-free {@code PIC 9(4)} integers and are therefore held as {@code int}: this program contains
 * no monetary field, no {@code COMPUTE} and no {@code ROUNDED}, so no {@code BigDecimal}, no
 * {@code setScale} and no {@code RoundingMode} appears anywhere in this file.
 *
 * <h2>{@code FC-INVALID-DATE} is the success token - the name is inverted, and stays inverted</h2>
 * Of the nine {@code 88}-level feedback tokens at L62-L70, the one named
 * {@code FC-INVALID-DATE} carries {@code VALUE X'0000000000000000'}: severity 0 and message number
 * 0, which is {@code CEE000}, the <em>successful</em> outcome. It selects the result text
 * {@code 'Date is valid'} at L129-L130. The misleading name is preserved verbatim and the mapping
 * is not flipped, because both are part of the behaviour being migrated.
 *
 * <h2>Message 2513 is load-bearing at every call site</h2>
 * {@code X'09D1'} is 2513, the message number of {@code FC-UNSUPP-RANGE}. All four call sites use
 * the identical acceptance chain ({@code COTRN02C} L397-L407 and L417-L427, {@code CORPT00C}
 * L396-L406 and L416-L426):
 * <pre>
 *   IF CSUTLDTC-RESULT-SEV-CD = '0000'
 *       CONTINUE
 *   ELSE
 *       IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'
 *           ... reject ...
 *       END-IF
 *   END-IF
 * </pre>
 * so a date reported as out of the supported range is <strong>silently accepted</strong> by every
 * caller despite carrying severity 3. Emitting any other message number for an out-of-range date
 * would flip all four call sites from accept to reject. That is why the range check maps to 2513 and
 * nothing else, and why the inherited quirk is preserved rather than corrected.
 *
 * <h2>The L122 group-move defect is reproduced deliberately</h2>
 * {@code MOVE WS-DATE-TO-TEST TO WS-DATE} (L122) moves a <em>group</em> item into
 * {@code WS-DATE PIC X(10)}. The group is {@code Vstring-length PIC S9(4) BINARY} (a big-endian
 * halfword) followed by the ten text bytes, so its image is twelve bytes and an alphanumeric group
 * move keeps the leading ten and discards the rest. Because L105-L106 sets the length from
 * {@code LENGTH OF LS-DATE} - the <em>declared</em> width of {@code PIC X(10)}, which is always 10
 * regardless of content - the planted prefix is invariably {@code 0x00 0x0A}. The reported
 * "TstDate:" span therefore holds two non-printable bytes followed by only the <em>first eight</em>
 * characters of the input date; the final two characters are lost in every single call. This is
 * fully deterministic and is asserted rather than tolerated: for the input {@code "2022-07-18"} the
 * bytes at offsets 45 to 54 are {@code 00 0A 32 30 32 32 2D 30 37 2D}. Correcting it would be a
 * behaviour change, so it is reproduced exactly. The mask span {@code WS-DATE-FMT} at offset 66 is
 * <em>not</em> affected and always holds the clean ten-character mask.
 *
 * <h2>Provenance: the CEEDAYS substitute is statically derived, not captured</h2>
 * No Language Environment runtime exists in this environment and no {@code CEE*} service is
 * available to Java, so {@code CALL "CEEDAYS"} (L116-L120) is replaced by the hand-written private
 * validator {@link #ceedays(String, String)}. Its behaviour is derived <strong>statically</strong>
 * from two places in the source - the nine {@code 88}-level hex {@code VALUE}s at L62-L70 and the
 * {@code EVALUATE TRUE} at L128-L149 - cross-checked against the IBM z/OS Language Environment
 * runtime-message documentation for the meaning of each feedback code. It was <strong>not</strong>
 * captured from a live {@code CEEDAYS} execution, and a future reader must not assume it was. Two
 * consequences are stated openly rather than hidden:
 * <ul>
 *   <li>the supported picture-string repertoire is bounded and enumerated on
 *       {@link PictureItemKind}; anything outside it is reported as
 *       {@code 'Bad Pic String '}. Both in-repository callers pass only {@code 'YYYY-MM-DD'}
 *       ({@code COTRN02C} L60, {@code CORPT00C} L72), so no caller exercises the bounds;</li>
 *   <li>the order in which the validator applies its checks is the documented order recorded on
 *       {@link #ceedays(String, String)}, chosen from the documented meaning of each code
 *       rather than observed from a running service.</li>
 * </ul>
 *
 * <h2>Statelessness, determinism and thread safety</h2>
 * Every COBOL {@code WORKING-STORAGE} item - {@code WS-MESSAGE}, {@code WS-DATE-TO-TEST},
 * {@code WS-DATE-FORMAT}, {@code FEEDBACK-CODE} and {@code OUTPUT-LILLIAN} - is a
 * <strong>per-invocation local</strong>, never a field and never static. The only static members are
 * immutable constants: the offsets and widths, the ten result literals, the feedback-token table and
 * the two immutable lists. {@link #validateDate(String, String)} is a pure function of its two
 * arguments: it consults no clock, no random source, no system property, no locale default and no
 * I/O, so a singleton instance is safe to share across threads. Where case folding is needed it is
 * performed against {@link Locale#ROOT}, and the four-digit zero fill goes through the codec's
 * {@code PIC 9} path, so no default-locale digit shaping can corrupt the severity or
 * message-number spans. The class emits no console output and no log records: the only
 * {@code DISPLAY} in the source (L96) is commented out, as is the {@code GOBACK} at L101, and
 * {@code CSUTLDTC} is not one of the nine {@code CALL 'CEE3ABD'} abend sites, so nothing here writes
 * to a stream, terminates the process or raises an abend.
 *
 * @see #validateDate(String, String)
 * @see DateValidationResult
 */
@Service
public class DateUtilityJob {

    // ---------------------------------------------------------------------------------------------
    // Linkage widths - app/cbl/CSUTLDTC.cbl L83-L86.
    // ---------------------------------------------------------------------------------------------

    /** {@code 01 LS-DATE PIC X(10)} - L84. The input date is exactly ten characters. */
    public static final int LS_DATE_LENGTH = 10;

    /** {@code 01 LS-DATE-FORMAT PIC X(10)} - L85. The picture string is exactly ten characters. */
    public static final int LS_DATE_FORMAT_LENGTH = 10;

    /** {@code 01 LS-RESULT PIC X(80)} - L86, and the declared width of {@code WS-MESSAGE}. */
    public static final int LS_RESULT_LENGTH = 80;

    // ---------------------------------------------------------------------------------------------
    // WS-MESSAGE geometry - app/cbl/CSUTLDTC.cbl L42-L57. Offsets are absolute and 0-based; every
    // width is named so no magic number appears in the layout below. The declared spans sum to
    // 4 + 11 + 4 + 1 + 15 + 1 + 9 + 10 + 1 + 10 + 10 + 1 + 3 = 80, which RecordLayout re-verifies.
    // ---------------------------------------------------------------------------------------------

    /** {@code 02 WS-SEVERITY PIC X(04)} - L43, at the start of the record. */
    private static final int WS_SEVERITY_OFFSET = 0;

    /** Width of both {@code WS-SEVERITY} and its numeric overlay {@code WS-SEVERITY-N}. */
    private static final int SEVERITY_AND_MSG_NO_LENGTH = 4;

    /** {@code 02 FILLER PIC X(11) VALUE 'Mesg Code:'} - L45. */
    private static final int FILLER_MESG_CODE_OFFSET = 4;

    /** Declared width of the {@code 'Mesg Code:'} filler: 11 bytes for 10 characters of text. */
    private static final int FILLER_MESG_CODE_LENGTH = 11;

    /** {@code 02 WS-MSG-NO PIC X(04)} - L46. */
    private static final int WS_MSG_NO_OFFSET = 15;

    /** {@code 02 FILLER PIC X(01) VALUE SPACE} - L48. */
    private static final int FILLER_AFTER_MSG_NO_OFFSET = 19;

    /** {@code 02 WS-RESULT PIC X(15)} - L49, the span the {@code EVALUATE} at L128-L149 fills. */
    private static final int WS_RESULT_OFFSET = 20;

    /** {@code PIC X(15)} - the width the source documents at L126-L127. */
    private static final int WS_RESULT_LENGTH = 15;

    /** {@code 02 FILLER PIC X(01) VALUE SPACE} - L50. */
    private static final int FILLER_AFTER_RESULT_OFFSET = 35;

    /** {@code 02 FILLER PIC X(09) VALUE 'TstDate:'} - L51. */
    private static final int FILLER_TST_DATE_OFFSET = 36;

    /** Declared width of the {@code 'TstDate:'} filler: 9 bytes for 8 characters of text. */
    private static final int FILLER_TST_DATE_LENGTH = 9;

    /** {@code 02 WS-DATE PIC X(10) VALUE SPACES} - L52. Corrupted by the L122 group move. */
    private static final int WS_DATE_OFFSET = 45;

    /** {@code 02 FILLER PIC X(01) VALUE SPACE} - L53. */
    private static final int FILLER_AFTER_DATE_OFFSET = 55;

    /** {@code 02 FILLER PIC X(10) VALUE 'Mask used:'} - L54; text and span are both 10 wide. */
    private static final int FILLER_MASK_USED_OFFSET = 56;

    /**
     * Declared width of the {@code 'Mask used:'} filler: 10 bytes for 10 characters of text, so this
     * is the one literal filler whose text fits exactly and takes no trailing pad byte.
     */
    private static final int FILLER_MASK_USED_LENGTH = 10;

    /** {@code 02 WS-DATE-FMT PIC X(10)} - L55. Holds the clean mask; never corrupted. */
    private static final int WS_DATE_FMT_OFFSET = 66;

    /** {@code 02 FILLER PIC X(01) VALUE SPACE} - L56. */
    private static final int FILLER_AFTER_FMT_OFFSET = 76;

    /** {@code 02 FILLER PIC X(03) VALUE SPACES} - L57, the trailing span that closes the 80 bytes. */
    private static final int FILLER_TRAILING_OFFSET = 77;

    /** Declared width of the trailing {@code FILLER PIC X(03)} - L57. */
    private static final int FILLER_TRAILING_LENGTH = 3;

    /** Declared width of each single-byte {@code FILLER PIC X(01) VALUE SPACE} - L48, L50, L53, L56. */
    private static final int SINGLE_BYTE_FILLER_LENGTH = 1;

    // ---------------------------------------------------------------------------------------------
    // Literal VALUE clauses. Reproduced byte-for-byte; the codec supplies the trailing pad where the
    // declared span is wider than the literal.
    // ---------------------------------------------------------------------------------------------

    /** {@code VALUE 'Mesg Code:'} - L45; ten characters placed in an eleven-byte span. */
    private static final String LITERAL_MESG_CODE = "Mesg Code:";

    /** {@code VALUE 'TstDate:'} - L51; eight characters placed in a nine-byte span. */
    private static final String LITERAL_TST_DATE = "TstDate:";

    /** {@code VALUE 'Mask used:'} - L54; ten characters exactly filling a ten-byte span. */
    private static final String LITERAL_MASK_USED = "Mask used:";

    /** {@code VALUE SPACE} for the four single-byte fillers at L48, L50, L53 and L56. */
    private static final String ONE_SPACE = " ";

    /** {@code VALUE SPACES} for the trailing {@code FILLER PIC X(03)} at L57. */
    private static final String THREE_SPACES = "   ";

    /** {@code VALUE SPACES} on {@code WS-DATE} at L52, and the {@code MOVE SPACES} at L91. */
    private static final String TEN_SPACES = "          ";

    // ---------------------------------------------------------------------------------------------
    // The WS-MESSAGE layout. Each REDEFINES overlay is declared immediately after the storage it
    // redefines, which is what lets RecordLayout confirm the overlay lies inside already-declared
    // storage while contributing nothing to the record total.
    // ---------------------------------------------------------------------------------------------

    /** {@code 02 WS-SEVERITY PIC X(04)} - L43. The character view the callers compare. */
    private static final FieldSpan WS_SEVERITY = FieldSpan.alphanumeric(
            "WS-SEVERITY", WS_SEVERITY_OFFSET, SEVERITY_AND_MSG_NO_LENGTH);

    /** {@code 02 WS-SEVERITY-N REDEFINES WS-SEVERITY PIC 9(4)} - L44. The numeric view L123 writes. */
    private static final FieldSpan WS_SEVERITY_N =
            WS_SEVERITY.redefinedAs("WS-SEVERITY-N", PictureKind.UNSIGNED_NUMERIC);

    /** {@code 02 FILLER PIC X(11) VALUE 'Mesg Code:'} - L45. */
    private static final FieldSpan FILLER_MESG_CODE = FieldSpan.filler(
            FILLER_MESG_CODE_OFFSET, FILLER_MESG_CODE_LENGTH, LITERAL_MESG_CODE);

    /** {@code 02 WS-MSG-NO PIC X(04)} - L46. The character view the callers compare with '2513'. */
    private static final FieldSpan WS_MSG_NO = FieldSpan.alphanumeric(
            "WS-MSG-NO", WS_MSG_NO_OFFSET, SEVERITY_AND_MSG_NO_LENGTH);

    /** {@code 02 WS-MSG-NO-N REDEFINES WS-MSG-NO PIC 9(4)} - L47. The numeric view L124 writes. */
    private static final FieldSpan WS_MSG_NO_N =
            WS_MSG_NO.redefinedAs("WS-MSG-NO-N", PictureKind.UNSIGNED_NUMERIC);

    /** {@code 02 FILLER PIC X(01) VALUE SPACE} - L48. */
    private static final FieldSpan FILLER_AFTER_MSG_NO = FieldSpan.filler(
            FILLER_AFTER_MSG_NO_OFFSET, SINGLE_BYTE_FILLER_LENGTH, ONE_SPACE);

    /** {@code 02 WS-RESULT PIC X(15)} - L49. */
    private static final FieldSpan WS_RESULT = FieldSpan.alphanumeric(
            "WS-RESULT", WS_RESULT_OFFSET, WS_RESULT_LENGTH);

    /** {@code 02 FILLER PIC X(01) VALUE SPACE} - L50. */
    private static final FieldSpan FILLER_AFTER_RESULT = FieldSpan.filler(
            FILLER_AFTER_RESULT_OFFSET, SINGLE_BYTE_FILLER_LENGTH, ONE_SPACE);

    /** {@code 02 FILLER PIC X(09) VALUE 'TstDate:'} - L51. */
    private static final FieldSpan FILLER_TST_DATE = FieldSpan.filler(
            FILLER_TST_DATE_OFFSET, FILLER_TST_DATE_LENGTH, LITERAL_TST_DATE);

    /** {@code 02 WS-DATE PIC X(10) VALUE SPACES} - L52. */
    private static final FieldSpan WS_DATE = FieldSpan
            .alphanumeric("WS-DATE", WS_DATE_OFFSET, LS_DATE_LENGTH)
            .withInitialValue(TEN_SPACES);

    /** {@code 02 FILLER PIC X(01) VALUE SPACE} - L53. */
    private static final FieldSpan FILLER_AFTER_DATE = FieldSpan.filler(
            FILLER_AFTER_DATE_OFFSET, SINGLE_BYTE_FILLER_LENGTH, ONE_SPACE);

    /** {@code 02 FILLER PIC X(10) VALUE 'Mask used:'} - L54. */
    private static final FieldSpan FILLER_MASK_USED = FieldSpan.filler(
            FILLER_MASK_USED_OFFSET, FILLER_MASK_USED_LENGTH, LITERAL_MASK_USED);

    /** {@code 02 WS-DATE-FMT PIC X(10)} - L55. */
    private static final FieldSpan WS_DATE_FMT = FieldSpan.alphanumeric(
            "WS-DATE-FMT", WS_DATE_FMT_OFFSET, LS_DATE_FORMAT_LENGTH);

    /** {@code 02 FILLER PIC X(01) VALUE SPACE} - L56. */
    private static final FieldSpan FILLER_AFTER_FMT = FieldSpan.filler(
            FILLER_AFTER_FMT_OFFSET, SINGLE_BYTE_FILLER_LENGTH, ONE_SPACE);

    /** {@code 02 FILLER PIC X(03) VALUE SPACES} - L57. */
    private static final FieldSpan FILLER_TRAILING = FieldSpan.filler(
            FILLER_TRAILING_OFFSET, FILLER_TRAILING_LENGTH, THREE_SPACES);

    /**
     * {@code 01 WS-MESSAGE} - L42-L57, in declaration order and complete to the byte.
     *
     * <p>Constructing this constant is the total-width self-check: {@link RecordLayout} rejects any
     * gap, any overlap that is not declared as an overlay, and any total other than
     * {@link #LS_RESULT_LENGTH}, so the 80-byte geometry is proved when this class initialises
     * rather than discovered by a caller slicing the result.
     */
    private static final RecordLayout WS_MESSAGE_LAYOUT = RecordLayout.of(LS_RESULT_LENGTH,
            WS_SEVERITY,
            WS_SEVERITY_N,
            FILLER_MESG_CODE,
            WS_MSG_NO,
            WS_MSG_NO_N,
            FILLER_AFTER_MSG_NO,
            WS_RESULT,
            FILLER_AFTER_RESULT,
            FILLER_TST_DATE,
            WS_DATE,
            FILLER_AFTER_DATE,
            FILLER_MASK_USED,
            WS_DATE_FMT,
            FILLER_AFTER_FMT,
            FILLER_TRAILING);

    // ---------------------------------------------------------------------------------------------
    // The ten WS-RESULT literals from the EVALUATE at L128-L149, verbatim and in source order.
    // WS-RESULT is PIC X(15) and the source documents that width at L126-L127. Eight of the ten
    // literals already carry their own significant trailing spaces inside the quotes; only the first
    // two are shorter than fifteen and are padded on the right by the codec's PIC X move, exactly as
    // a COBOL MOVE to a PIC X(15) receiver pads. Capitalisation and punctuation are preserved
    // exactly, including the full stop in 'Unsupp. Range' and the lower-case m in 'Invalid month'.
    // ---------------------------------------------------------------------------------------------

    /** L130 - {@code 'Date is valid'}, 13 characters, right-padded to 15. The success text. */
    private static final String RESULT_DATE_IS_VALID = "Date is valid";

    /** L132 - {@code 'Insufficient'}, 12 characters, right-padded to 15. */
    private static final String RESULT_INSUFFICIENT = "Insufficient";

    /** L134 - {@code 'Datevalue error'}, already exactly 15 characters. */
    private static final String RESULT_DATEVALUE_ERROR = "Datevalue error";

    /** L136 - {@code 'Invalid Era    '}, 15 characters including four trailing spaces. */
    private static final String RESULT_INVALID_ERA = "Invalid Era    ";

    /** L138 - {@code 'Unsupp. Range  '}, 15 characters including two trailing spaces. */
    private static final String RESULT_UNSUPP_RANGE = "Unsupp. Range  ";

    /** L140 - {@code 'Invalid month  '}, 15 characters including two trailing spaces. */
    private static final String RESULT_INVALID_MONTH = "Invalid month  ";

    /** L142 - {@code 'Bad Pic String '}, 15 characters including one trailing space. */
    private static final String RESULT_BAD_PIC_STRING = "Bad Pic String ";

    /** L144 - {@code 'Nonnumeric data'}, already exactly 15 characters. */
    private static final String RESULT_NONNUMERIC_DATA = "Nonnumeric data";

    /** L146 - {@code 'YearInEra is 0 '}, 15 characters including one trailing space. */
    private static final String RESULT_YEAR_IN_ERA_ZERO = "YearInEra is 0 ";

    /** L148 - {@code 'Date is invalid'}, the {@code WHEN OTHER} text, already exactly 15 characters. */
    private static final String RESULT_DATE_IS_INVALID = "Date is invalid";

    // ---------------------------------------------------------------------------------------------
    // Feedback-code severities and the sentinel message number, named so the token table below reads
    // as the decoded 88-level values rather than as bare integers.
    // ---------------------------------------------------------------------------------------------

    /**
     * The severity carried by {@code FC-INVALID-DATE}, whose {@code VALUE X'0000000000000000'}
     * decodes to severity 0. Language Environment reports a successful call as {@code CEE000}, so
     * zero is the success severity and becomes {@code RETURN-CODE} 0 at L98.
     */
    private static final int SEVERITY_SUCCESS = 0;

    /**
     * The severity carried by all eight named error tokens, whose {@code VALUE}s begin
     * {@code X'0003'} - severity 3, "severe". It becomes {@code RETURN-CODE} 3 at L98.
     */
    private static final int SEVERITY_SEVERE = 3;

    /**
     * The message number of {@code FC-INVALID-DATE} (zero, from {@code CEE000}) and of the
     * {@link FeedbackToken#UNENUMERATED} sentinel.
     */
    private static final int MESSAGE_NUMBER_NONE = 0;

    /**
     * The value Language Environment leaves in the output parameter when it does not calculate a
     * Lillian day count. The IBM runtime-message documentation states for each of these conditions
     * that "the output value is set to 0", which is also the value L114 pre-loads into
     * {@code OUTPUT-LILLIAN} before the call.
     */
    private static final int LILLIAN_NOT_CALCULATED = 0;

    /**
     * The nine {@code 88}-level feedback tokens declared on {@code FEEDBACK-TOKEN-VALUE} at
     * {@code app/cbl/CSUTLDTC.cbl} L62-L70, plus one sentinel for the {@code WHEN OTHER} path.
     *
     * <p>Each {@code 88} level compares the full eight-byte token: a {@code SEVERITY S9(4) BINARY}
     * halfword, a {@code MSG-NO S9(4) BINARY} halfword, a one-byte {@code CASE-SEV-CTL} and a
     * three-byte {@code FACILITY-ID}. Decoding the nine hex literals mechanically gives the severity
     * and message number recorded on each constant below; every error token carries the control byte
     * {@code X'59'} and the facility bytes {@code X'C3C5C5'}, which is {@code "CEE"} in EBCDIC and
     * confirms these are Language Environment messages. Only the severity and the message number
     * reach the 80-byte result (L123, L124), so only those two are modelled.
     *
     * <table border="1">
     *   <caption>The decoded 88-level table</caption>
     *   <tr><th>88 level</th><th>Declared VALUE</th><th>Severity</th><th>Message</th></tr>
     *   <tr><td>{@code FC-INVALID-DATE}</td><td>{@code X'0000000000000000'}</td><td>0</td><td>0</td></tr>
     *   <tr><td>{@code FC-INSUFFICIENT-DATA}</td><td>{@code X'000309CB59C3C5C5'}</td><td>3</td><td>2507</td></tr>
     *   <tr><td>{@code FC-BAD-DATE-VALUE}</td><td>{@code X'000309CC59C3C5C5'}</td><td>3</td><td>2508</td></tr>
     *   <tr><td>{@code FC-INVALID-ERA}</td><td>{@code X'000309CD59C3C5C5'}</td><td>3</td><td>2509</td></tr>
     *   <tr><td>{@code FC-UNSUPP-RANGE}</td><td>{@code X'000309D159C3C5C5'}</td><td>3</td><td>2513</td></tr>
     *   <tr><td>{@code FC-INVALID-MONTH}</td><td>{@code X'000309D559C3C5C5'}</td><td>3</td><td>2517</td></tr>
     *   <tr><td>{@code FC-BAD-PIC-STRING}</td><td>{@code X'000309D659C3C5C5'}</td><td>3</td><td>2518</td></tr>
     *   <tr><td>{@code FC-NON-NUMERIC-DATA}</td><td>{@code X'000309D859C3C5C5'}</td><td>3</td><td>2520</td></tr>
     *   <tr><td>{@code FC-YEAR-IN-ERA-ZERO}</td><td>{@code X'000309D959C3C5C5'}</td><td>3</td><td>2521</td></tr>
     * </table>
     *
     * <p>The COBOL names are kept exactly as declared, including the inverted
     * {@link #FC_INVALID_DATE}, whose all-zeros value is the <em>successful</em> outcome.
     */
    private enum FeedbackToken {

        /**
         * L62 - {@code VALUE X'0000000000000000'}. Severity 0, message 0: {@code CEE000}, a
         * successful conversion. The name says "invalid" and the value says "valid"; the name is
         * preserved and the mapping is not flipped. Selects {@code 'Date is valid'} at L129-L130.
         */
        FC_INVALID_DATE(SEVERITY_SUCCESS, MESSAGE_NUMBER_NONE),

        /**
         * L63 - {@code VALUE X'000309CB59C3C5C5'}. Severity 3, message 2507. IBM documents this as
         * insufficient data: the <em>picture string</em> did not carry enough information to compute
         * a Lillian value, for example a month and day with no year. The minimum is either year,
         * month and day, or year and Julian day.
         */
        FC_INSUFFICIENT_DATA(SEVERITY_SEVERE, 2507),

        /**
         * L64 - {@code VALUE X'000309CC59C3C5C5'}. Severity 3, message 2508. IBM documents this as
         * the date value being invalid: the day-of-month or day-of-year is not valid for the given
         * year and month - 29 February in a common year, day 366 in a common year, 31 June, or day
         * zero.
         */
        FC_BAD_DATE_VALUE(SEVERITY_SEVERE, 2508),

        /**
         * L65 - {@code VALUE X'000309CD59C3C5C5'}. Severity 3, message 2509. IBM documents this as
         * the era not being recognised: the era field did not contain a supported era name.
         */
        FC_INVALID_ERA(SEVERITY_SEVERE, 2509),

        /**
         * L66 - {@code VALUE X'000309D159C3C5C5'}. Severity 3, message <strong>2513</strong>
         * ({@code X'09D1'}): the date is not within the supported range. This is the load-bearing
         * one - all four call sites test {@code CSUTLDTC-RESULT-MSG-NUM NOT = '2513'} and therefore
         * accept it silently in spite of severity 3.
         */
        FC_UNSUPP_RANGE(SEVERITY_SEVERE, 2513),

        /**
         * L67 - {@code VALUE X'000309D559C3C5C5'}. Severity 3, message 2517: the month value or
         * month name was not recognised.
         */
        FC_INVALID_MONTH(SEVERITY_SEVERE, 2517),

        /**
         * L68 - {@code VALUE X'000309D659C3C5C5'}. Severity 3, message 2518: the picture string was
         * not valid.
         */
        FC_BAD_PIC_STRING(SEVERITY_SEVERE, 2518),

        /**
         * L69 - {@code VALUE X'000309D859C3C5C5'}. Severity 3, message 2520: non-numeric data was
         * found where numeric data was expected.
         */
        FC_NON_NUMERIC_DATA(SEVERITY_SEVERE, 2520),

        /**
         * L70 - {@code VALUE X'000309D959C3C5C5'}. Severity 3, message 2521: the year-within-era
         * value was zero.
         */
        FC_YEAR_IN_ERA_ZERO(SEVERITY_SEVERE, 2521),

        /**
         * The {@code WHEN OTHER} path at L147-L148, reached for any feedback token the nine
         * {@code 88} levels do not enumerate.
         *
         * <p>This is <strong>not</strong> an IBM feedback code and does not claim to be one. Its
         * message number is deliberately {@link #MESSAGE_NUMBER_NONE} together with severity
         * {@link #SEVERITY_SEVERE}, a combination that cannot equal any of the nine declared
         * {@code VALUE}s - the all-zeros success token has severity 0 and every named error token has
         * a message number of at least 2507 - so it always falls through to {@code WHEN OTHER} and
         * yields {@code 'Date is invalid'}. Per the source, the severity and message number written
         * into the result on that path are whatever the validator returned and are never forced to a
         * named value, so the callers see {@code '0003'} and {@code '0000'} and reject the date,
         * which is the correct outcome for input the picture string never described.
         *
         * <p>The validator emits it for exactly the two conditions no documented CEEDAYS code
         * covers: a literal delimiter in the picture string that the input date does not match, and
         * input characters left over beyond everything the picture described. Neither is
         * "insufficient data" (which IBM attributes to the picture string), nor non-numeric data in
         * a numeric field, nor an era, month, year, day or range fault.
         */
        UNENUMERATED(SEVERITY_SEVERE, MESSAGE_NUMBER_NONE);

        /** The decoded {@code SEVERITY S9(4) BINARY} halfword; 0 for success, 3 for every error. */
        private final int severity;

        /** The decoded {@code MSG-NO S9(4) BINARY} halfword. */
        private final int messageNumber;

        FeedbackToken(int severity, int messageNumber) {
            this.severity = severity;
            this.messageNumber = messageNumber;
        }

        /**
         * The value L123 moves into {@code WS-SEVERITY-N} and L98 moves into {@code RETURN-CODE}.
         *
         * @return 0 for the success token, 3 for every error token and for the sentinel
         */
        int severity() {
            return severity;
        }

        /**
         * The value L124 moves into {@code WS-MSG-NO-N}.
         *
         * @return the decoded Language Environment message number, or 0 for the success token and
         *         for the sentinel
         */
        int messageNumber() {
            return messageNumber;
        }
    }

    /**
     * What the {@code CEEDAYS} substitute produces: the feedback token, and the Lillian day count it
     * would have written into {@code OUTPUT-LILLIAN}.
     *
     * <p>{@code 01 OUTPUT-LILLIAN PIC S9(9) USAGE IS BINARY} (L41) is pre-set to zero at L114,
     * filled by the call at L116-L120, and then <strong>never read again</strong> by
     * {@code CSUTLDTC}: it is not moved into {@code WS-MESSAGE}, not returned through
     * {@code LS-RESULT} and not exposed to any caller. It is preserved here for exactly that reason
     * and is deliberately left unconsumed. Surfacing it on {@link DateValidationResult} would add a
     * capability the COBOL never offered, which is a new feature rather than a migration.
     *
     * @param feedbackCode  the {@code FEEDBACK-CODE} the call returned, which drives L123, L124 and
     *                      the {@code EVALUATE} at L128-L149
     * @param outputLillian the Lillian day count, or {@link #LILLIAN_NOT_CALCULATED} when the
     *                      conversion failed. Internal and intentionally unread
     */
    private record CeedaysOutcome(FeedbackToken feedbackCode, int outputLillian) {

        /**
         * A successful conversion: the all-zeros {@code CEE000} token, which the nine {@code 88}
         * levels match as {@code FC-INVALID-DATE}.
         *
         * @param outputLillian the computed Lillian day count
         * @return the successful outcome
         */
        static CeedaysOutcome accepted(int outputLillian) {
            return new CeedaysOutcome(FeedbackToken.FC_INVALID_DATE, outputLillian);
        }

        /**
         * A failed conversion. The Lillian output is left at zero, matching the documented
         * "the output value is set to 0" behaviour of every one of these conditions.
         *
         * @param feedbackCode the token describing the failure
         * @return the failed outcome
         */
        static CeedaysOutcome rejected(FeedbackToken feedbackCode) {
            return new CeedaysOutcome(feedbackCode, LILLIAN_NOT_CALCULATED);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The picture-string model. CEEDAYS is handed a picture string describing where each date field
    // sits inside the input string; this is the hand-written, bounded reimplementation of that
    // description. The repertoire is enumerated rather than open-ended so a reviewer can audit the
    // whole rejection set against the nine feedback tokens.
    // ---------------------------------------------------------------------------------------------

    /**
     * The date field a picture token supplies. Used only to detect a token declared twice, and to
     * decide whether the picture carries the minimum information CEEDAYS requires.
     */
    private enum PictureRole {

        /** The year within the era, from {@code YYYY} or {@code YY}. */
        YEAR,

        /** The month, from {@code MM} or {@code MMM}. */
        MONTH,

        /** The day of the month, from {@code DD}. */
        DAY_OF_MONTH,

        /** The day of the year, from {@code DDD}. */
        JULIAN_DAY,

        /** The era name, from a {@code <...>} era field. */
        ERA,

        /** A literal delimiter, which supplies no date field and may repeat freely. */
        NONE
    }

    /**
     * The picture tokens this substitute recognises, and the width each one consumes.
     *
     * <p>This repertoire is <strong>bounded on purpose</strong> and the bound is documented rather
     * than hidden: any letter in the picture string that does not begin one of these tokens is
     * reported as {@link FeedbackToken#FC_BAD_PIC_STRING}, and any character that is not a letter is
     * treated as a literal delimiter that the input date must match exactly. Longer tokens are tried
     * before their shorter prefixes - {@code YYYY} before {@code YY}, {@code MMM} before {@code MM},
     * {@code DDD} before {@code DD} - so the match is greedy and unambiguous. Both in-repository
     * callers pass only {@code 'YYYY-MM-DD'}, so no caller reaches the bound.
     */
    private enum PictureItemKind {

        /** {@code YYYY} - a four-digit year within the era. */
        YEAR_4(4, 4, PictureRole.YEAR),

        /**
         * {@code YY} - a two-digit year, resolved through the fixed century window recorded on
         * {@link DateUtilityJob#TWO_DIGIT_YEAR_WINDOW_PIVOT}.
         */
        YEAR_2(2, 2, PictureRole.YEAR),

        /** {@code MMM} - a three-character month abbreviation, {@code JAN} through {@code DEC}. */
        MONTH_NAME_3(3, 3, PictureRole.MONTH),

        /** {@code MM} - a two-digit month. */
        MONTH_2(2, 2, PictureRole.MONTH),

        /** {@code DDD} - a three-digit day of the year. */
        JULIAN_DAY_3(3, 3, PictureRole.JULIAN_DAY),

        /** {@code DD} - a two-digit day of the month. */
        DAY_2(2, 2, PictureRole.DAY_OF_MONTH),

        /**
         * An era field, written in the picture string between {@code <} and {@code >}. The picture
         * token length varies with the delimiters and is measured at parse time rather than taken
         * from here; the two supported era names are both two characters, so the input width is
         * fixed.
         */
        ERA(0, 2, PictureRole.ERA),

        /** A literal delimiter: one picture character matching one input character exactly. */
        LITERAL(1, 1, PictureRole.NONE);

        /** How many picture-string characters the token occupies; zero for the variable era field. */
        private final int pictureTokenLength;

        /** How many characters of the input date the token consumes. */
        private final int inputWidth;

        /** The date field the token supplies. */
        private final PictureRole role;

        PictureItemKind(int pictureTokenLength, int inputWidth, PictureRole role) {
            this.pictureTokenLength = pictureTokenLength;
            this.inputWidth = inputWidth;
            this.role = role;
        }

        /**
         * @return the number of picture-string characters this token occupies, or zero for
         *         {@link #ERA}, whose width depends on its delimiters
         */
        int pictureTokenLength() {
            return pictureTokenLength;
        }

        /**
         * @return the number of input-date characters this token consumes
         */
        int inputWidth() {
            return inputWidth;
        }

        /**
         * @return the date field this token supplies, or {@link PictureRole#NONE} for a literal
         */
        PictureRole role() {
            return role;
        }
    }

    /**
     * One parsed element of a picture string.
     *
     * @param kind    the token this element represents
     * @param literal the character the input must match, meaningful only when {@code kind} is
     *                {@link PictureItemKind#LITERAL}
     */
    private record PictureItem(PictureItemKind kind, char literal) {

        /** The placeholder stored for any element that is not a literal delimiter. */
        private static final char NOT_A_LITERAL = '\0';

        /**
         * @param kind the date-field token
         * @return an element representing a date field
         */
        static PictureItem field(PictureItemKind kind) {
            return new PictureItem(kind, NOT_A_LITERAL);
        }

        /**
         * @param literal the character the input date must carry at this position
         * @return an element representing a literal delimiter
         */
        static PictureItem literal(char literal) {
            return new PictureItem(PictureItemKind.LITERAL, literal);
        }
    }

    /**
     * A parsed picture string, or the reason it was refused.
     *
     * @param items      the elements in picture order, empty when the picture was refused
     * @param inputWidth the total number of input-date characters the elements consume
     * @param julianDay  {@code true} when the picture locates the day of the year rather than the
     *                   month and day of the month
     * @param rejection  the feedback token to report, or {@code null} when the picture is usable
     */
    private record ParsedPicture(List<PictureItem> items,
                                 int inputWidth,
                                 boolean julianDay,
                                 FeedbackToken rejection) {

        /** The width and flags carried by a refused picture, which is never scanned. */
        private static final int NO_INPUT_CONSUMED = 0;

        /**
         * @param rejection the token describing why the picture string is unusable
         * @return a refused picture
         */
        static ParsedPicture rejected(FeedbackToken rejection) {
            return new ParsedPicture(List.of(), NO_INPUT_CONSUMED, false, rejection);
        }

        /**
         * @param items      the parsed elements in picture order
         * @param inputWidth the total input width the elements consume
         * @param julianDay  whether the picture locates a day of the year
         * @return a usable picture
         */
        static ParsedPicture accepted(List<PictureItem> items, int inputWidth, boolean julianDay) {
            return new ParsedPicture(List.copyOf(items), inputWidth, julianDay, null);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Picture-string tokens and the era repertoire.
    // ---------------------------------------------------------------------------------------------

    /** The {@code YYYY} picture token: a four-digit year within the era. */
    private static final String TOKEN_YEAR_4 = "YYYY";

    /** The {@code YY} picture token: a two-digit year, resolved through the century window. */
    private static final String TOKEN_YEAR_2 = "YY";

    /** The {@code MMM} picture token: a three-character month abbreviation. */
    private static final String TOKEN_MONTH_NAME = "MMM";

    /** The {@code MM} picture token: a two-digit month. */
    private static final String TOKEN_MONTH = "MM";

    /** The {@code DDD} picture token: a three-digit day of the year. */
    private static final String TOKEN_JULIAN_DAY = "DDD";

    /** The {@code DD} picture token: a two-digit day of the month. */
    private static final String TOKEN_DAY = "DD";

    /** Opens an era field in a picture string. */
    private static final char ERA_FIELD_OPEN = '<';

    /** Closes an era field in a picture string. */
    private static final char ERA_FIELD_CLOSE = '>';

    /** The era name for the common era; the date is taken forward from year 1. */
    private static final String ERA_COMMON = "AD";

    /**
     * The era name for dates before the common era. Every such date precedes the Lillian epoch of
     * 15 October 1582, so it is reported as out of the supported range.
     */
    private static final String ERA_BEFORE_COMMON = "BC";

    /**
     * The month abbreviations a {@code MMM} field accepts, in calendar order, compared after folding
     * the input to upper case against {@link Locale#ROOT}. Immutable, so this constant carries no
     * mutable static state.
     */
    private static final List<String> MONTH_ABBREVIATIONS = List.of(
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");

    /** The era names an era field accepts, compared after folding to upper case. Immutable. */
    private static final List<String> ERA_NAMES = List.of(ERA_COMMON, ERA_BEFORE_COMMON);

    /** The result of {@link List#indexOf(Object)} when the value is absent. */
    private static final int NOT_FOUND = -1;

    // ---------------------------------------------------------------------------------------------
    // Calendar constants. Every one is named; the calendar arithmetic below is hand-written so its
    // rejection set can be audited against the nine feedback tokens rather than inherited from a
    // date-library parser whose leniency cannot be inspected.
    // ---------------------------------------------------------------------------------------------

    /**
     * A two-digit year at or below this pivot is read as being in the twenty-first century and above
     * it as being in the twentieth, giving the window 1950 to 2049. The window is a documented choice
     * of this substitute, not a value read from the COBOL, and it means a {@code YY} field can never
     * produce a year-within-era of zero.
     */
    private static final int TWO_DIGIT_YEAR_WINDOW_PIVOT = 49;

    /** The century added to a two-digit year at or below the window pivot. */
    private static final int TWENTY_FIRST_CENTURY_BASE = 2000;

    /** The century added to a two-digit year above the window pivot. */
    private static final int TWENTIETH_CENTURY_BASE = 1900;

    /** The year-within-era value {@code FC-YEAR-IN-ERA-ZERO} reports. */
    private static final int YEAR_WITHIN_ERA_ZERO = 0;

    /** The lowest valid month number. */
    private static final int FIRST_MONTH = 1;

    /** The highest valid month number. */
    private static final int LAST_MONTH = 12;

    /** January, used as the anchor month when the picture locates a day of the year. */
    private static final int JANUARY = 1;

    /** February, the only month whose length depends on the year. */
    private static final int FEBRUARY = 2;

    /** April, one of the four thirty-day months. */
    private static final int APRIL = 4;

    /** June, one of the four thirty-day months. */
    private static final int JUNE = 6;

    /** September, one of the four thirty-day months. */
    private static final int SEPTEMBER = 9;

    /** November, one of the four thirty-day months. */
    private static final int NOVEMBER = 11;

    /** The lowest valid day of the month, and the anchor day for a day-of-year picture. */
    private static final int FIRST_DAY_OF_MONTH = 1;

    /** The lowest valid day of the year. */
    private static final int FIRST_DAY_OF_YEAR = 1;

    /** The length of the seven thirty-one-day months. */
    private static final int DAYS_IN_LONG_MONTH = 31;

    /** The length of the four thirty-day months. */
    private static final int DAYS_IN_SHORT_MONTH = 30;

    /** The length of February in a leap year. */
    private static final int DAYS_IN_LEAP_FEBRUARY = 29;

    /** The length of February in a common year. */
    private static final int DAYS_IN_COMMON_FEBRUARY = 28;

    /** The number of days in a leap year. */
    private static final int DAYS_IN_LEAP_YEAR = 366;

    /** The number of days in a common year. */
    private static final int DAYS_IN_COMMON_YEAR = 365;

    /** A leap year in the Gregorian calendar is divisible by this, subject to the century rules. */
    private static final int LEAP_YEAR_DIVISOR = 4;

    /** A century year is not a leap year unless it also satisfies {@link #LEAP_CYCLE_DIVISOR}. */
    private static final int CENTURY_DIVISOR = 100;

    /** A century year divisible by this is a leap year after all. */
    private static final int LEAP_CYCLE_DIVISOR = 400;

    /** Shifts March to position 0 so the leap day falls at the end of the shifted year. */
    private static final int MARCH_ALIGNED_MONTH_SHIFT = 9;

    /** The number of months in a year, used to wrap the shifted month. */
    private static final int MONTHS_IN_YEAR = 12;

    /** January and February belong to the preceding shifted year; this divisor detects them. */
    private static final int SHIFTED_YEAR_ROLLOVER_DIVISOR = 10;

    /** Numerator of the cumulative month-length series in the shifted calendar. */
    private static final int MONTH_LENGTH_NUMERATOR = 306;

    /** Bias of the cumulative month-length series in the shifted calendar. */
    private static final int MONTH_LENGTH_BIAS = 5;

    /** Denominator of the cumulative month-length series in the shifted calendar. */
    private static final int MONTH_LENGTH_DENOMINATOR = 10;

    /** The year of the day immediately before the Lillian epoch. */
    private static final int LILLIAN_EPOCH_EVE_YEAR = 1582;

    /** The month of the day immediately before the Lillian epoch. */
    private static final int LILLIAN_EPOCH_EVE_MONTH = 10;

    /**
     * The day immediately before the Lillian epoch. Lillian day 1 is 15 October 1582, the first day
     * of the Gregorian calendar, so 14 October 1582 is day 0 and every earlier date - including the
     * ten days from the 5th to the 14th that the Gregorian reform skipped entirely - yields a
     * non-positive count and is reported as out of the supported range.
     */
    private static final int LILLIAN_EPOCH_EVE_DAY = 14;

    /** The lowest Lillian day count Language Environment supports: 15 October 1582. */
    private static final int FIRST_LILLIAN_DAY = 1;

    /**
     * The day number of 14 October 1582, subtracted from every day number to yield a Lillian count.
     * Computed once, from the same hand-written arithmetic the validator uses.
     */
    private static final int LILLIAN_EPOCH_EVE_DAY_NUMBER = gregorianDayNumber(
            LILLIAN_EPOCH_EVE_YEAR, LILLIAN_EPOCH_EVE_MONTH, LILLIAN_EPOCH_EVE_DAY);

    // ---------------------------------------------------------------------------------------------
    // Character-level constants for the hand-written digit scan.
    // ---------------------------------------------------------------------------------------------

    /** The lowest character a zoned {@code DISPLAY} digit position may hold. */
    private static final char DIGIT_ZERO = '0';

    /** The highest character a zoned {@code DISPLAY} digit position may hold. */
    private static final char DIGIT_NINE = '9';

    /** The character a blank input position holds. */
    private static final char SPACE_CHARACTER = ' ';

    /** The lowest upper-case letter, for the explicit ASCII letter test in the picture parser. */
    private static final char UPPER_CASE_A = 'A';

    /** The highest upper-case letter, for the explicit ASCII letter test in the picture parser. */
    private static final char UPPER_CASE_Z = 'Z';

    /** The lowest lower-case letter, for the explicit ASCII letter test in the picture parser. */
    private static final char LOWER_CASE_A = 'a';

    /** The highest lower-case letter, for the explicit ASCII letter test in the picture parser. */
    private static final char LOWER_CASE_Z = 'z';

    /** The radix of a zoned {@code DISPLAY} field. */
    private static final int DECIMAL_RADIX = 10;

    // ---------------------------------------------------------------------------------------------
    // The L122 group move.
    // ---------------------------------------------------------------------------------------------

    /**
     * The width of {@code 02 Vstring-length PIC S9(4) BINARY} (L26) - a halfword, big-endian on
     * z/Architecture. These are the two bytes the L122 group move plants in front of the date text.
     */
    private static final int VSTRING_LENGTH_BYTES = 2;

    /**
     * How many characters of the date text survive the L122 group move into {@code WS-DATE PIC X(10)}
     * once the two halfword bytes have consumed the front of the span: the final two characters of
     * the input date are discarded in every call.
     */
    private static final int GROUP_MOVE_SURVIVING_TEXT_BYTES = LS_DATE_LENGTH - VSTRING_LENGTH_BYTES;

    /** Masks one byte out of the halfword when it is split into its two bytes. */
    private static final int BYTE_MASK = 0xFF;

    // ---------------------------------------------------------------------------------------------
    // Instance state: one immutable codec, derived from an explicitly named code page.
    // ---------------------------------------------------------------------------------------------

    /**
     * The code page the 80-byte result is rendered in when no other is supplied.
     *
     * <p>It is named explicitly and is never the platform default. {@code US-ASCII} is the code page
     * of the ASCII fixtures this module is verified against, and it is a single-byte encoding, which
     * a fixed-width record area addressed by absolute offset requires. The two bytes the L122 group
     * move plants are binary rather than character data and are therefore identical under any code
     * page; every other byte of the result is a single-byte character.
     */
    public static final Charset DEFAULT_MESSAGE_CHARSET = StandardCharsets.US_ASCII;

    /**
     * The fixed-width codec that renders the 80-byte result. Immutable and stateless beyond the code
     * page it holds, so sharing one instance across threads is safe; it is built once here rather
     * than per call. It supplies the {@code PIC X} right-pad and right-truncate move, the
     * {@code PIC 9} left-zero-fill move used for the two numeric overlays, and the code page for the
     * byte rendering.
     */
    private final FixedWidthCodec codec;

    /**
     * Creates the service against {@link #DEFAULT_MESSAGE_CHARSET}.
     *
     * <p>This is the constructor the Spring container uses. It exists so the class can also be
     * instantiated directly in a plain unit test with no application context, which is what keeps
     * every branch of the validator reachable without HTTP or a job launcher in the path.
     */
    public DateUtilityJob() {
        this(DEFAULT_MESSAGE_CHARSET);
    }

    /**
     * Creates the service against an explicitly supplied code page.
     *
     * @param messageCharset the code page the 80-byte result is rendered in. It must encode the
     *                       digits, the space and the sign overpunch characters to exactly one byte
     *                       each, which the codec verifies, because a fixed-width record area is
     *                       addressed by absolute byte offset
     * @throws IllegalArgumentException if the code page is not single-byte over that repertoire
     * @throws NullPointerException     if {@code messageCharset} is {@code null}
     */
    public DateUtilityJob(Charset messageCharset) {
        this.codec = new FixedWidthCodec(messageCharset);
    }

    /**
     * Validates a date against a picture string, exactly as {@code CSUTLDTC} does.
     *
     * <p>This is the whole public surface of the program and the Java form of
     * {@code PROCEDURE DIVISION USING LS-DATE, LS-DATE-FORMAT, LS-RESULT} (L88). The two inputs
     * correspond to {@code LS-DATE} and {@code LS-DATE-FORMAT}; the third COBOL parameter,
     * {@code LS-RESULT PIC X(80)}, is an output and becomes the return value. The mainline it
     * reproduces is L88-L102:
     * <ol>
     *   <li>L90 {@code INITIALIZE WS-MESSAGE} - allocate the 80-byte area, space-filling the named
     *       character fields and writing the three literal {@code FILLER}s from their {@code VALUE}
     *       clauses;</li>
     *   <li>L91 {@code MOVE SPACES TO WS-DATE} - redundant, because L90 has already blanked that
     *       field, and preserved for exactly that reason;</li>
     *   <li>L93-L94 {@code PERFORM A000-MAIN THRU A000-MAIN-EXIT} - a range perform over a paragraph
     *       and its own exit label, so it collapses to one method call; {@code A000-MAIN-EXIT}
     *       (L152-L154) is a bare {@code EXIT};</li>
     *   <li>L96 {@code DISPLAY WS-MESSAGE} - commented out in the source, so nothing is written to
     *       any stream or log here;</li>
     *   <li>L97 {@code MOVE WS-MESSAGE TO LS-RESULT} and L98
     *       {@code MOVE WS-SEVERITY-N TO RETURN-CODE} - read back through both views of the severity
     *       overlay and package the outcome;</li>
     *   <li>L100 {@code EXIT PROGRAM} - an ordinary return. The {@code GOBACK} at L101 is commented
     *       out, and {@code CSUTLDTC} is not one of the nine {@code CALL 'CEE3ABD'} abend sites, so
     *       this method never terminates the process and never raises an abend.</li>
     * </ol>
     *
     * <p>The returned object is fresh on every call, which satisfies the {@code MOVE SPACES TO
     * CSUTLDTC-RESULT} each caller performs before invoking the program.
     *
     * @param lsDate       {@code LS-DATE PIC X(10)} - the date to test. Used as handed over: it is
     *                     normalised to ten characters by the {@code PIC X} move and is otherwise
     *                     neither trimmed, rejected nor sanitised, because the COBOL does none of
     *                     those things
     * @param lsDateFormat {@code LS-DATE-FORMAT PIC X(10)} - the picture string describing where each
     *                     field sits in {@code lsDate}. Both callers pass {@code 'YYYY-MM-DD'}
     * @return the 80-byte outcome: the four-character severity and message-number text the callers
     *         compare, the fifteen-character result text, the numeric {@code RETURN-CODE} and the
     *         byte-exact rendering of {@code WS-MESSAGE}
     * @throws NullPointerException if either argument is {@code null}, which the fixed-width move
     *                              reports rather than silently treating as blanks
     */
    public DateValidationResult validateDate(String lsDate, String lsDateFormat) {
        // Every WORKING-STORAGE item is a per-invocation local. Nothing here is a field, and nothing
        // is static, so a singleton instance of this service is safe to share across threads.
        final FixedWidthRecord wsMessage = codec.newRecord(WS_MESSAGE_LAYOUT);  // L90 INITIALIZE
        codec.writePicX(wsMessage, WS_DATE, TEN_SPACES);                        // L91, redundant

        a000Main(wsMessage, lsDate, lsDateFormat);                              // L93-L94

        // L96 DISPLAY WS-MESSAGE is commented out in the source: emit nothing.
        // L97 MOVE WS-MESSAGE TO LS-RESULT, then L98 MOVE WS-SEVERITY-N TO RETURN-CODE. The severity
        // is read back through the numeric overlay and the character view of the very same bytes.
        return new DateValidationResult(
                codec.readPicX(wsMessage, WS_SEVERITY),
                codec.readPicX(wsMessage, WS_MSG_NO),
                codec.readPicX(wsMessage, WS_RESULT),
                codec.readPic9AsInt(wsMessage, WS_SEVERITY_N),
                wsMessage.readString(WS_SEVERITY_OFFSET, wsMessage.recordLength()),
                wsMessage.toByteArray(),
                codec.charset());
        // L100 EXIT PROGRAM.
    }

    /**
     * {@code A000-MAIN} - L103-L151, in source order and with the source's side effects intact.
     *
     * <p>The order matters and is preserved exactly: the clean date reaches {@code WS-DATE} first
     * (L107-L108), the clean mask reaches {@code WS-DATE-FMT} (L111-L113), the conversion runs
     * (L116-L120), and only then does the group move at L122 overwrite {@code WS-DATE} with the
     * twelve-byte group image truncated to ten bytes. Reversing those last two steps, or skipping the
     * group move, would change the reported "TstDate:" span.
     *
     * @param wsMessage    the 80-byte area to fill
     * @param lsDate       the caller's {@code LS-DATE}
     * @param lsDateFormat the caller's {@code LS-DATE-FORMAT}
     */
    private void a000Main(FixedWidthRecord wsMessage, String lsDate, String lsDateFormat) {
        // L105-L106 MOVE LENGTH OF LS-DATE TO VSTRING-LENGTH OF WS-DATE-TO-TEST. LENGTH OF returns
        // the DECLARED width of PIC X(10), so the varying-string length is always 10 whatever the
        // content - which is precisely what makes the L122 defect deterministic.
        // L107-L108 is a multi-receiver MOVE: the same ten characters reach both VSTRING-TEXT OF
        // WS-DATE-TO-TEST and WS-DATE.
        final String wsDateToTestText = codec.movePicX(lsDate, LS_DATE_LENGTH);
        codec.writePicX(wsMessage, WS_DATE, wsDateToTestText);

        // L109-L110 sets VSTRING-LENGTH OF WS-DATE-FORMAT the same way. Note the naming trap: this
        // WS-DATE-FORMAT is CSUTLDTC's own varying-string group (L33), a different item from the
        // callers' WS-DATE-FORMAT PIC X(10) literal. L111-L113 is again a multi-receiver MOVE, and
        // WS-DATE-FMT is never corrupted afterwards.
        final String wsDateFormatText = codec.movePicX(lsDateFormat, LS_DATE_FORMAT_LENGTH);
        codec.writePicX(wsMessage, WS_DATE_FMT, wsDateFormatText);

        // L114 MOVE 0 TO OUTPUT-LILLIAN, then L116-L120 CALL "CEEDAYS". The Lillian day count the
        // call produces is carried on the outcome and, exactly as in the source, never read again.
        final CeedaysOutcome outcome = ceedays(wsDateToTestText, wsDateFormatText);
        final FeedbackToken feedbackCode = outcome.feedbackCode();

        // L122 MOVE WS-DATE-TO-TEST TO WS-DATE - the group move, reproduced deliberately.
        wsMessage.writeSpanBytes(WS_DATE, wsDateToTestGroupImage(wsDateToTestText));

        // L123 MOVE SEVERITY OF FEEDBACK-CODE TO WS-SEVERITY-N and L124 MOVE MSG-NO OF FEEDBACK-CODE
        // TO WS-MSG-NO-N. Both write through the numeric REDEFINES view, which left-zero-fills to the
        // four declared digits, and both are read back by the callers through the character view.
        codec.writePic9(wsMessage, WS_SEVERITY_N, feedbackCode.severity());
        codec.writePic9(wsMessage, WS_MSG_NO_N, feedbackCode.messageNumber());

        // L128-L149 EVALUATE TRUE. The nine named WHENs appear below in source order and WHEN OTHER
        // is last, so the first match wins and there is no fall-through, exactly as EVALUATE
        // behaves. The 88 levels are mutually exclusive because their declared VALUEs differ, so at
        // most one arm can apply. The result text is moved into WS-RESULT PIC X(15), which pads the
        // two literals shorter than fifteen on the right.
        final String wsResult = switch (feedbackCode) {
            case FC_INVALID_DATE -> RESULT_DATE_IS_VALID;            // L129-L130
            case FC_INSUFFICIENT_DATA -> RESULT_INSUFFICIENT;        // L131-L132
            case FC_BAD_DATE_VALUE -> RESULT_DATEVALUE_ERROR;        // L133-L134
            case FC_INVALID_ERA -> RESULT_INVALID_ERA;               // L135-L136
            case FC_UNSUPP_RANGE -> RESULT_UNSUPP_RANGE;             // L137-L138
            case FC_INVALID_MONTH -> RESULT_INVALID_MONTH;           // L139-L140
            case FC_BAD_PIC_STRING -> RESULT_BAD_PIC_STRING;         // L141-L142
            case FC_NON_NUMERIC_DATA -> RESULT_NONNUMERIC_DATA;      // L143-L144
            case FC_YEAR_IN_ERA_ZERO -> RESULT_YEAR_IN_ERA_ZERO;     // L145-L146
            default -> RESULT_DATE_IS_INVALID;                       // L147-L148 WHEN OTHER
        };
        codec.writePicX(wsMessage, WS_RESULT, wsResult);
        // L151 ends A000-MAIN; L152-L154 A000-MAIN-EXIT is a bare EXIT and needs no counterpart.
    }

    /**
     * Builds the ten bytes the L122 group move leaves in {@code WS-DATE}.
     *
     * <p>{@code WS-DATE-TO-TEST} (L25-L31) is a group of {@code Vstring-length PIC S9(4) BINARY}
     * followed by {@code Vstring-text}, so with a length of ten its storage image is twelve bytes:
     * the big-endian halfword {@code 0x00 0x0A} and then the ten text bytes. Moving that group into
     * an alphanumeric {@code PIC X(10)} receiver is a group move, which is left-justified and
     * truncated on the right, so the halfword survives and only the <em>first eight</em> text bytes
     * follow it. The halfword is derived from {@link #LS_DATE_LENGTH} rather than written as a
     * literal, so the two bytes are visibly the declared width that {@code LENGTH OF} returned.
     *
     * @param vstringText the ten characters held in {@code VSTRING-TEXT OF WS-DATE-TO-TEST}
     * @return exactly {@link #LS_DATE_LENGTH} bytes: the halfword followed by the surviving text
     */
    private byte[] wsDateToTestGroupImage(String vstringText) {
        final byte[] groupImage = new byte[LS_DATE_LENGTH];
        groupImage[0] = (byte) ((LS_DATE_LENGTH >> Byte.SIZE) & BYTE_MASK);
        groupImage[1] = (byte) (LS_DATE_LENGTH & BYTE_MASK);
        System.arraycopy(vstringText.getBytes(codec.charset()), 0, groupImage,
                VSTRING_LENGTH_BYTES, GROUP_MOVE_SURVIVING_TEXT_BYTES);
        return groupImage;
    }

    /**
     * The hand-written stand-in for
     * {@code CALL "CEEDAYS" USING WS-DATE-TO-TEST, WS-DATE-FORMAT, OUTPUT-LILLIAN, FEEDBACK-CODE}
     * (L116-L120).
     *
     * <p>No Language Environment runtime is reachable from Java, so the service is reimplemented here
     * rather than delegated. It is <strong>not</strong> delegated to a date-formatting library either:
     * the whole point of this method is that its rejection set can be read off against the nine
     * {@code 88}-level tokens, which a library parser's leniency settings could not guarantee.
     *
     * <p><strong>The order of the checks is the contract.</strong> It is derived from the documented
     * meaning of each Language Environment message rather than observed from a running service, and
     * it is applied as a single guard chain in which the first failure wins:
     * <ol>
     *   <li>the picture string does not parse - an unrecognised letter, a token declared twice, an
     *       unterminated era field, or a day of the year combined with a month or day of the month:
     *       {@link FeedbackToken#FC_BAD_PIC_STRING} (2518);</li>
     *   <li>the picture string parses but carries too little information - IBM requires either a
     *       year, month and day, or a year and a day of the year:
     *       {@link FeedbackToken#FC_INSUFFICIENT_DATA} (2507). Note that this condition is attributed
     *       to the <em>picture string</em>, not to a short input date;</li>
     *   <li>the input is shorter than the picture describes, or a literal delimiter in the picture
     *       does not appear in the input, or the input carries non-blank characters beyond everything
     *       the picture described: {@link FeedbackToken#UNENUMERATED}, because none of the nine
     *       documented conditions covers a shape mismatch of this kind, and the source's
     *       {@code WHEN OTHER} arm is exactly where an unenumerated token belongs;</li>
     *   <li>an era field holds a name that is not supported:
     *       {@link FeedbackToken#FC_INVALID_ERA} (2509);</li>
     *   <li>a numeric field position holds anything other than a digit - a blank included:
     *       {@link FeedbackToken#FC_NON_NUMERIC_DATA} (2520);</li>
     *   <li>a month-abbreviation field holds a name that is not a month:
     *       {@link FeedbackToken#FC_INVALID_MONTH} (2517);</li>
     *   <li>the year within the era is zero: {@link FeedbackToken#FC_YEAR_IN_ERA_ZERO} (2521);</li>
     *   <li>the month is outside 1 to 12: {@link FeedbackToken#FC_INVALID_MONTH} (2517);</li>
     *   <li>the day of the month is outside the length of that month in that year, or the day of the
     *       year is outside the length of that year: {@link FeedbackToken#FC_BAD_DATE_VALUE} (2508).
     *       This is the condition IBM illustrates with 29 February in a common year, day 366 of a
     *       common year, 31 June and day zero;</li>
     *   <li>the date precedes the Lillian epoch of 15 October 1582, which includes every date before
     *       the common era and the ten days the Gregorian reform skipped:
     *       {@link FeedbackToken#FC_UNSUPP_RANGE} (<strong>2513</strong>, the number all four callers
     *       accept silently);</li>
     *   <li>otherwise the conversion succeeds and yields the Lillian day count.</li>
     * </ol>
     * The upper end of the supported range needs no test: a year is captured from at most four digits
     * and is therefore never later than 9999, which the Lillian range includes.
     *
     * @param inputCharDate the ten characters held in {@code VSTRING-TEXT OF WS-DATE-TO-TEST}
     * @param pictureString the ten characters held in {@code VSTRING-TEXT OF WS-DATE-FORMAT}
     * @return the feedback token and, on success, the Lillian day count
     */
    private static CeedaysOutcome ceedays(String inputCharDate, String pictureString) {
        final ParsedPicture picture = parsePictureString(pictureString);
        if (picture.rejection() != null) {
            return CeedaysOutcome.rejected(picture.rejection());
        }
        return scanInputDate(inputCharDate, picture);
    }

    /**
     * Parses a picture string into positional elements, or refuses it.
     *
     * <p>Tokens are matched greedily, longest first, so {@code YYYY} is never mistaken for two
     * {@code YY} fields and {@code MMM} is never mistaken for {@code MM} followed by a stray letter.
     * A character that is not a letter and does not open an era field is a literal delimiter the input
     * must reproduce exactly; a letter that begins no token means the picture string itself is
     * invalid. Trailing spaces are significant and are treated as literal delimiters, because the
     * program hands CEEDAYS the full declared ten bytes of {@code LS-DATE-FORMAT} - the varying-string
     * length comes from {@code LENGTH OF}, not from the trimmed content.
     *
     * @param pictureString the ten-character picture string
     * @return the parsed elements, or a refusal carrying {@link FeedbackToken#FC_BAD_PIC_STRING} or
     *         {@link FeedbackToken#FC_INSUFFICIENT_DATA}
     */
    private static ParsedPicture parsePictureString(String pictureString) {
        final List<PictureItem> items = new ArrayList<>();
        final boolean[] declaredRoles = new boolean[PictureRole.values().length];
        int inputWidth = 0;
        int cursor = 0;
        while (cursor < pictureString.length()) {
            final PictureItemKind kind = matchPictureToken(pictureString, cursor);
            if (kind == null) {
                final char delimiter = pictureString.charAt(cursor);
                if (isAsciiLetter(delimiter)) {
                    return ParsedPicture.rejected(FeedbackToken.FC_BAD_PIC_STRING);
                }
                items.add(PictureItem.literal(delimiter));
                inputWidth += PictureItemKind.LITERAL.inputWidth();
                cursor += PictureItemKind.LITERAL.pictureTokenLength();
                continue;
            }
            if (declaredRoles[kind.role().ordinal()]) {
                return ParsedPicture.rejected(FeedbackToken.FC_BAD_PIC_STRING);
            }
            if (kind == PictureItemKind.ERA) {
                final int closingDelimiter = pictureString.indexOf(ERA_FIELD_CLOSE, cursor);
                if (closingDelimiter == NOT_FOUND) {
                    return ParsedPicture.rejected(FeedbackToken.FC_BAD_PIC_STRING);
                }
                cursor = closingDelimiter + 1;
            } else {
                cursor += kind.pictureTokenLength();
            }
            declaredRoles[kind.role().ordinal()] = true;
            items.add(PictureItem.field(kind));
            inputWidth += kind.inputWidth();
        }

        final boolean hasYear = declaredRoles[PictureRole.YEAR.ordinal()];
        final boolean hasMonth = declaredRoles[PictureRole.MONTH.ordinal()];
        final boolean hasDayOfMonth = declaredRoles[PictureRole.DAY_OF_MONTH.ordinal()];
        final boolean hasDayOfYear = declaredRoles[PictureRole.JULIAN_DAY.ordinal()];
        // A day of the year and a month or day of the month describe the same information twice, so
        // the picture contradicts itself and is not a valid picture string.
        if (hasDayOfYear && (hasMonth || hasDayOfMonth)) {
            return ParsedPicture.rejected(FeedbackToken.FC_BAD_PIC_STRING);
        }
        // IBM's documented minimum: either the year, month and day, or the year and the day of the
        // year. Anything less cannot yield a Lillian value, which is what 2507 reports.
        if (!hasYear || !(hasDayOfYear || (hasMonth && hasDayOfMonth))) {
            return ParsedPicture.rejected(FeedbackToken.FC_INSUFFICIENT_DATA);
        }
        return ParsedPicture.accepted(items, inputWidth, hasDayOfYear);
    }

    /**
     * Identifies the picture token beginning at a position, if any.
     *
     * @param pictureString the picture string being parsed
     * @param cursor        the position to examine
     * @return the matched token, or {@code null} when the character begins no token and is therefore
     *         either a literal delimiter or an invalid letter
     */
    private static PictureItemKind matchPictureToken(String pictureString, int cursor) {
        if (pictureString.startsWith(TOKEN_YEAR_4, cursor)) {
            return PictureItemKind.YEAR_4;
        }
        if (pictureString.startsWith(TOKEN_YEAR_2, cursor)) {
            return PictureItemKind.YEAR_2;
        }
        if (pictureString.startsWith(TOKEN_MONTH_NAME, cursor)) {
            return PictureItemKind.MONTH_NAME_3;
        }
        if (pictureString.startsWith(TOKEN_MONTH, cursor)) {
            return PictureItemKind.MONTH_2;
        }
        if (pictureString.startsWith(TOKEN_JULIAN_DAY, cursor)) {
            return PictureItemKind.JULIAN_DAY_3;
        }
        if (pictureString.startsWith(TOKEN_DAY, cursor)) {
            return PictureItemKind.DAY_2;
        }
        if (pictureString.charAt(cursor) == ERA_FIELD_OPEN) {
            return PictureItemKind.ERA;
        }
        return null;
    }

    /**
     * Scans the input date against a parsed picture and applies the calendar checks, in the order
     * documented on {@link #ceedays(String, String)}.
     *
     * @param inputCharDate the ten characters of the input date
     * @param picture       the parsed picture string
     * @return the feedback token and, on success, the Lillian day count
     */
    private static CeedaysOutcome scanInputDate(String inputCharDate, ParsedPicture picture) {
        // The picture cannot describe more positions than the input supplies. With both operands
        // fixed at ten characters this cannot arise from either in-repository caller, but the guard
        // keeps every field extraction below inside the input and gives the shape mismatch the same
        // unenumerated outcome as a delimiter that does not match.
        if (picture.inputWidth() > inputCharDate.length()) {
            return CeedaysOutcome.rejected(FeedbackToken.UNENUMERATED);
        }

        int yearWithinEra = YEAR_WITHIN_ERA_ZERO;
        int month = JANUARY;
        int dayOfMonth = FIRST_DAY_OF_MONTH;
        int dayOfYear = FIRST_DAY_OF_YEAR;
        boolean beforeCommonEra = false;
        int cursor = 0;

        for (PictureItem item : picture.items()) {
            final String field = inputCharDate.substring(cursor, cursor + item.kind().inputWidth());
            cursor += item.kind().inputWidth();
            switch (item.kind()) {
                case YEAR_4 -> {
                    if (!isAllDigits(field)) {
                        return CeedaysOutcome.rejected(FeedbackToken.FC_NON_NUMERIC_DATA);
                    }
                    yearWithinEra = digitsToInt(field);
                }
                case YEAR_2 -> {
                    if (!isAllDigits(field)) {
                        return CeedaysOutcome.rejected(FeedbackToken.FC_NON_NUMERIC_DATA);
                    }
                    yearWithinEra = resolveTwoDigitYear(digitsToInt(field));
                }
                case MONTH_NAME_3 -> {
                    month = monthFromAbbreviation(field);
                    if (month < FIRST_MONTH) {
                        return CeedaysOutcome.rejected(FeedbackToken.FC_INVALID_MONTH);
                    }
                }
                case MONTH_2 -> {
                    if (!isAllDigits(field)) {
                        return CeedaysOutcome.rejected(FeedbackToken.FC_NON_NUMERIC_DATA);
                    }
                    month = digitsToInt(field);
                }
                case JULIAN_DAY_3 -> {
                    if (!isAllDigits(field)) {
                        return CeedaysOutcome.rejected(FeedbackToken.FC_NON_NUMERIC_DATA);
                    }
                    dayOfYear = digitsToInt(field);
                }
                case DAY_2 -> {
                    if (!isAllDigits(field)) {
                        return CeedaysOutcome.rejected(FeedbackToken.FC_NON_NUMERIC_DATA);
                    }
                    dayOfMonth = digitsToInt(field);
                }
                case ERA -> {
                    final String eraName = field.toUpperCase(Locale.ROOT);
                    if (!ERA_NAMES.contains(eraName)) {
                        return CeedaysOutcome.rejected(FeedbackToken.FC_INVALID_ERA);
                    }
                    beforeCommonEra = ERA_BEFORE_COMMON.equals(eraName);
                }
                case LITERAL -> {
                    if (field.charAt(0) != item.literal()) {
                        return CeedaysOutcome.rejected(FeedbackToken.UNENUMERATED);
                    }
                }
            }
        }

        // Whatever the picture never described must be blank. Real characters beyond the end of the
        // description are neither missing data nor a bad value, so no documented code fits them.
        if (!isAllSpaces(inputCharDate, cursor)) {
            return CeedaysOutcome.rejected(FeedbackToken.UNENUMERATED);
        }
        if (yearWithinEra == YEAR_WITHIN_ERA_ZERO) {
            return CeedaysOutcome.rejected(FeedbackToken.FC_YEAR_IN_ERA_ZERO);
        }
        if (month < FIRST_MONTH || month > LAST_MONTH) {
            return CeedaysOutcome.rejected(FeedbackToken.FC_INVALID_MONTH);
        }
        if (picture.julianDay()) {
            if (dayOfYear < FIRST_DAY_OF_YEAR || dayOfYear > daysInYear(yearWithinEra)) {
                return CeedaysOutcome.rejected(FeedbackToken.FC_BAD_DATE_VALUE);
            }
        } else if (dayOfMonth < FIRST_DAY_OF_MONTH
                || dayOfMonth > daysInMonth(yearWithinEra, month)) {
            return CeedaysOutcome.rejected(FeedbackToken.FC_BAD_DATE_VALUE);
        }
        // Every date before the common era precedes the Lillian epoch by construction.
        if (beforeCommonEra) {
            return CeedaysOutcome.rejected(FeedbackToken.FC_UNSUPP_RANGE);
        }
        final int outputLillian = picture.julianDay()
                ? lillianDay(yearWithinEra, JANUARY, FIRST_DAY_OF_MONTH) + dayOfYear - FIRST_DAY_OF_YEAR
                : lillianDay(yearWithinEra, month, dayOfMonth);
        if (outputLillian < FIRST_LILLIAN_DAY) {
            return CeedaysOutcome.rejected(FeedbackToken.FC_UNSUPP_RANGE);
        }
        return CeedaysOutcome.accepted(outputLillian);
    }

    /**
     * Whether every character of a field is a zoned {@code DISPLAY} digit. A blank counts as
     * non-numeric, which is what makes a partially typed date report 2520.
     *
     * @param field the field extracted from the input date
     * @return {@code true} when every character is {@code '0'} through {@code '9'}
     */
    private static boolean isAllDigits(String field) {
        for (int index = 0; index < field.length(); index++) {
            final char character = field.charAt(index);
            if (character < DIGIT_ZERO || character > DIGIT_NINE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts a field already proved to hold only digits into its value. Accumulating by hand keeps
     * the conversion independent of any locale-sensitive parsing.
     *
     * @param digits a field of zoned {@code DISPLAY} digits
     * @return the value the digits denote
     */
    private static int digitsToInt(String digits) {
        int value = 0;
        for (int index = 0; index < digits.length(); index++) {
            value = value * DECIMAL_RADIX + (digits.charAt(index) - DIGIT_ZERO);
        }
        return value;
    }

    /**
     * Whether the tail of the input from a position onwards is entirely blank.
     *
     * @param text      the input date
     * @param fromIndex the first position to examine; may equal the length, in which case there is
     *                  no tail and the answer is {@code true}
     * @return {@code true} when no non-blank character remains
     */
    private static boolean isAllSpaces(String text, int fromIndex) {
        for (int index = fromIndex; index < text.length(); index++) {
            if (text.charAt(index) != SPACE_CHARACTER) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a character is an ASCII letter. Tested explicitly rather than through a Unicode-aware
     * classifier, so the set of characters that can invalidate a picture string is exactly the set a
     * reviewer can see here.
     *
     * @param character the picture-string character to classify
     * @return {@code true} for {@code A}-{@code Z} and {@code a}-{@code z}
     */
    private static boolean isAsciiLetter(char character) {
        return (character >= UPPER_CASE_A && character <= UPPER_CASE_Z)
                || (character >= LOWER_CASE_A && character <= LOWER_CASE_Z);
    }

    /**
     * Applies the fixed century window to a two-digit year.
     *
     * @param twoDigitYear the value of a {@code YY} field, 0 to 99
     * @return the four-digit year within the window 1950 to 2049
     */
    private static int resolveTwoDigitYear(int twoDigitYear) {
        return twoDigitYear <= TWO_DIGIT_YEAR_WINDOW_PIVOT
                ? TWENTY_FIRST_CENTURY_BASE + twoDigitYear
                : TWENTIETH_CENTURY_BASE + twoDigitYear;
    }

    /**
     * Resolves a three-character month abbreviation, folding case against {@link Locale#ROOT} so the
     * result cannot vary with the default locale.
     *
     * @param abbreviation the three characters of a {@code MMM} field
     * @return the month number 1 to 12, or 0 when the abbreviation is not a month
     */
    private static int monthFromAbbreviation(String abbreviation) {
        return MONTH_ABBREVIATIONS.indexOf(abbreviation.toUpperCase(Locale.ROOT)) + FIRST_MONTH;
    }

    /**
     * Whether a year is a leap year in the Gregorian calendar.
     *
     * @param year the year within the common era
     * @return {@code true} when the year carries 29 February
     */
    private static boolean isLeapYear(int year) {
        return (year % LEAP_YEAR_DIVISOR == 0 && year % CENTURY_DIVISOR != 0)
                || year % LEAP_CYCLE_DIVISOR == 0;
    }

    /**
     * The number of days in a year, which bounds a day-of-year field.
     *
     * @param year the year within the common era
     * @return 366 in a leap year, otherwise 365
     */
    private static int daysInYear(int year) {
        return isLeapYear(year) ? DAYS_IN_LEAP_YEAR : DAYS_IN_COMMON_YEAR;
    }

    /**
     * The number of days in a month, which bounds a day-of-month field. February depends on the year,
     * which is what makes 29 February in a common year report 2508.
     *
     * @param year  the year within the common era
     * @param month the month, already proved to be 1 to 12
     * @return the length of that month in that year
     */
    private static int daysInMonth(int year, int month) {
        return switch (month) {
            case FEBRUARY -> isLeapYear(year) ? DAYS_IN_LEAP_FEBRUARY : DAYS_IN_COMMON_FEBRUARY;
            case APRIL, JUNE, SEPTEMBER, NOVEMBER -> DAYS_IN_SHORT_MONTH;
            default -> DAYS_IN_LONG_MONTH;
        };
    }

    /**
     * A continuous day number for a proleptic Gregorian date, used only as the difference against the
     * Lillian epoch.
     *
     * <p>The month is shifted so that March is position 0 and February is position 11, which puts the
     * leap day at the end of the shifted year and removes it from the cumulative month-length series.
     * January and February therefore belong to the preceding shifted year, which the rollover divisor
     * detects. The series {@code (month * 306 + 5) / 10} reproduces the cumulative lengths of
     * March through February exactly.
     *
     * @param year  the year within the common era
     * @param month the month, 1 to 12
     * @param day   the day of the month, 1 to the length of that month
     * @return a day number that increases by one per calendar day
     */
    private static int gregorianDayNumber(int year, int month, int day) {
        final int marchAlignedMonth = (month + MARCH_ALIGNED_MONTH_SHIFT) % MONTHS_IN_YEAR;
        final int marchAlignedYear = year - marchAlignedMonth / SHIFTED_YEAR_ROLLOVER_DIVISOR;
        return DAYS_IN_COMMON_YEAR * marchAlignedYear
                + marchAlignedYear / LEAP_YEAR_DIVISOR
                - marchAlignedYear / CENTURY_DIVISOR
                + marchAlignedYear / LEAP_CYCLE_DIVISOR
                + (marchAlignedMonth * MONTH_LENGTH_NUMERATOR + MONTH_LENGTH_BIAS)
                        / MONTH_LENGTH_DENOMINATOR
                + (day - FIRST_DAY_OF_MONTH);
    }

    /**
     * The Lillian day count of a date: the number of days since 14 October 1582, so that 15 October
     * 1582 is day 1. A count below {@link #FIRST_LILLIAN_DAY} means the date is outside the range
     * Language Environment supports.
     *
     * @param year  the year within the common era
     * @param month the month, 1 to 12
     * @param day   the day of the month
     * @return the Lillian day count, which may be zero or negative for a date before the epoch
     */
    private static int lillianDay(int year, int month, int day) {
        return gregorianDayNumber(year, month, day) - LILLIAN_EPOCH_EVE_DAY_NUMBER;
    }

    /**
     * {@code LS-RESULT PIC X(80)} together with the {@code RETURN-CODE} the program sets - the whole
     * observable output of {@code CSUTLDTC}.
     *
     * <h2>Why it is more than an 80-character string</h2>
     * Both callers overlay the 80 bytes with an identical positional group
     * ({@code app/cbl/COTRN02C.cbl} L62-L69 and {@code app/cbl/CORPT00C.cbl} L129-L136):
     * <pre>
     *   05 CSUTLDTC-RESULT.
     *      10 CSUTLDTC-RESULT-SEV-CD       PIC X(04).
     *      10 FILLER                       PIC X(11).
     *      10 CSUTLDTC-RESULT-MSG-NUM      PIC X(04).
     *      10 CSUTLDTC-RESULT-MSG          PIC X(61).
     * </pre>
     * which sums to 80 and lines up exactly with {@code WS-MESSAGE}. Their acceptance test reads two
     * of those fields as text - {@code SEV-CD = '0000'} and {@code MSG-NUM NOT = '2513'} - so this
     * type exposes both discretely, as the four-character images the comparisons expect rather than
     * as integers a caller would have to reformat and risk formatting differently. It also exposes
     * the complete image, so a caller or a parity harness that wants to slice the bytes positionally
     * can, and so nothing about the layout is hidden behind the accessors.
     *
     * <h2>The image is byte-exact, including two non-printable bytes</h2>
     * {@link #message()} is exactly {@link DateUtilityJob#LS_RESULT_LENGTH} characters and
     * {@link #messageBytes()} exactly that many bytes. Positions 45 and 46 hold {@code 0x00} and
     * {@code 0x0A}: the halfword the L122 group move plants in front of the date, which is part of the
     * output and not a defect to be cleaned up on the way out. Everything else is single-byte
     * character data in {@link #charset()}.
     *
     * <h2>Immutability</h2>
     * Every field is final and the byte array is copied on the way out, so an instance cannot be
     * altered after {@link DateUtilityJob#validateDate(String, String)} returns it. A fresh instance
     * is produced per call, which is what the {@code MOVE SPACES TO CSUTLDTC-RESULT} each caller
     * performs beforehand amounts to. Value equality is deliberately not defined: the parity contract
     * compares field by field so that a difference is reported against a named field rather than as a
     * single unequal blob.
     */
    public static final class DateValidationResult {

        /** {@code WS-SEVERITY PIC X(04)} at offset 0 - {@code '0000'} or {@code '0003'}. */
        private final String severityCode;

        /** {@code WS-MSG-NO PIC X(04)} at offset 15 - for example {@code '2513'}. */
        private final String messageNumber;

        /** {@code WS-RESULT PIC X(15)} at offset 20 - one of the ten literals, padded to 15. */
        private final String result;

        /** The value L98 moves into {@code RETURN-CODE}: the severity as a number. */
        private final int returnCode;

        /** The complete {@code WS-MESSAGE} image, exactly 80 characters. */
        private final String message;

        /** The complete {@code WS-MESSAGE} image, exactly 80 bytes in {@link #charset}. */
        private final byte[] messageBytes;

        /** The code page {@link #messageBytes} is encoded in. */
        private final Charset charset;

        /**
         * @param severityCode  the four-character severity image
         * @param messageNumber the four-character message-number image
         * @param result        the fifteen-character result text
         * @param returnCode    the numeric severity
         * @param message       the complete 80-character image
         * @param messageBytes  the complete 80-byte image, taken as given because the caller within
         *                      this class supplies a fresh array from the record area
         * @param charset       the code page of {@code messageBytes}
         */
        private DateValidationResult(String severityCode,
                                     String messageNumber,
                                     String result,
                                     int returnCode,
                                     String message,
                                     byte[] messageBytes,
                                     Charset charset) {
            this.severityCode = severityCode;
            this.messageNumber = messageNumber;
            this.result = result;
            this.returnCode = returnCode;
            this.message = message;
            this.messageBytes = messageBytes;
            this.charset = charset;
        }

        /**
         * {@code CSUTLDTC-RESULT-SEV-CD} - the severity as the four-character text the callers
         * compare against the literal {@code '0000'}.
         *
         * @return {@code "0000"} when the date converted, otherwise {@code "0003"}
         */
        public String severityCode() {
            return severityCode;
        }

        /**
         * {@code CSUTLDTC-RESULT-MSG-NUM} - the message number as the four-character text the callers
         * compare against the literal {@code '2513'}.
         *
         * @return {@code "0000"} for a successful conversion or for an unenumerated feedback token,
         *         otherwise the four digits of the Language Environment message number
         */
        public String messageNumber() {
            return messageNumber;
        }

        /**
         * {@code WS-RESULT} - the fifteen-character description the {@code EVALUATE} at L128-L149
         * selected, right-padded to its declared width.
         *
         * @return exactly fifteen characters, trailing spaces included
         */
        public String result() {
            return result;
        }

        /**
         * The value {@code MOVE WS-SEVERITY-N TO RETURN-CODE} (L98) leaves in the COBOL return code.
         *
         * @return 0 for a successful conversion, 3 for every failure
         */
        public int returnCode() {
            return returnCode;
        }

        /**
         * The complete {@code WS-MESSAGE} image that L97 moves into {@code LS-RESULT}.
         *
         * @return exactly {@link DateUtilityJob#LS_RESULT_LENGTH} characters, including the two
         *         non-printable characters the L122 group move plants at positions 45 and 46
         */
        public String message() {
            return message;
        }

        /**
         * The complete {@code WS-MESSAGE} image as bytes, copied so the result stays immutable.
         *
         * @return a fresh array of exactly {@link DateUtilityJob#LS_RESULT_LENGTH} bytes encoded in
         *         {@link #charset()}
         */
        public byte[] messageBytes() {
            return messageBytes.clone();
        }

        /**
         * The code page {@link #messageBytes()} is encoded in, stated explicitly so no reader has to
         * assume a platform default was used.
         *
         * @return the code page the service was constructed with
         */
        public Charset charset() {
            return charset;
        }

        /**
         * A diagnostic rendering of the discrete fields. The 80-character image is deliberately left
         * out, because it carries two non-printable bytes that would corrupt any surrounding text.
         *
         * @return the severity, message number, result text and return code
         */
        @Override
        public String toString() {
            return "DateValidationResult[severityCode=" + severityCode
                    + ", messageNumber=" + messageNumber
                    + ", result='" + result + '\''
                    + ", returnCode=" + returnCode + ']';
        }
    }
}
