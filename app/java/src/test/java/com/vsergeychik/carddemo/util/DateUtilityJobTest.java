package com.vsergeychik.carddemo.util;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.util.DateUtilityJob.DateValidationResult;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link DateUtilityJob}, the Java form of {@code app/cbl/CSUTLDTC.cbl} (157 lines) -
 * the CardDemo date-validation subprogram that wraps the IBM Language Environment service
 * {@code CEEDAYS}.
 *
 * <h2>Provenance: every expected value here is STATICALLY DERIVED, never captured</h2>
 * No Language Environment runtime exists in this environment and no {@code CEE*} service is reachable
 * from Java - {@code cobc --list-intrinsics} matches zero {@code CEE*} entries, which is one of the
 * eight independently verified blockers that make executing the legacy programs impossible here.
 * Consequently <strong>every constant in this file was transcribed by reading
 * {@code app/cbl/CSUTLDTC.cbl} and its callers at authoring time</strong>: the thirteen span widths
 * from L42-L57, the three literal {@code FILLER} {@code VALUE} clauses from L45, L51 and L54, the ten
 * {@code WS-RESULT} texts from L128-L148, and the nine hexadecimal {@code 88}-level {@code VALUE}s
 * from L62-L70. They were <strong>NOT</strong> captured from a live {@code CEEDAYS} or {@code CSUTLDTC}
 * execution, and no future reader should assume they were. Where a value is derivable rather than
 * merely quotable it is <em>computed</em> in the assertion - the severity and message number of each
 * feedback token are decoded from the raw hexadecimal literal inside
 * {@link #decodeSeverity(String)} and {@link #decodeMessageNumber(String)} rather than written out as
 * magic numbers, so a transcription slip in the hex and a transcription slip in the decimal cannot
 * cancel each other out.
 *
 * <p>This test reads <strong>no file at all</strong>. It never opens anything under {@code app/cbl/},
 * {@code app/cpy/}, {@code app/cpy-bms/}, {@code app/bms/}, {@code app/jcl/}, {@code app/proc/},
 * {@code app/csd/}, {@code app/ctl/}, {@code app/catlg/} or {@code app/data/}: those paths are the
 * parity oracle and are immutable, so their content is inlined here as Java constants instead.
 *
 * <h2>The unit under test is a {@code @Service}, not a Spring Batch {@code Job}</h2>
 * The name {@code DateUtilityJob} is mandated verbatim and says "Job"; the behaviour says otherwise,
 * and behaviour wins. {@code CSUTLDTC} is a {@code CALL}ed subprogram: a search for it across
 * {@code app/jcl/}, {@code app/proc/} and {@code app/csd/} returns <strong>zero</strong> matches, so
 * there is no {@code EXEC PGM=} step, no DD binding and no CICS {@code PROGRAM} or
 * {@code TRANSACTION} definition that could make it a job or a transaction. It must therefore never
 * be counted toward the module's ten batch {@code Job} beans. Accordingly this class asserts against
 * the service <strong>directly</strong>: it starts no Spring context, launches no job and issues no
 * HTTP request, which is what keeps every branch of the validator reachable and the suite fast.
 *
 * <h2>Five call sites, three injectors</h2>
 * The migration plan lists four {@code CALL 'CSUTLDTC'} sites. Re-counting the source finds
 * <strong>five</strong>: {@code CORPT00C.cbl} L392 and L412, {@code COTRN02C.cbl} L393 and L413, and
 * - the one the plan omits - {@code app/cpy/CSUTLDPY.cpy} L293. That copybook's only consumer is
 * {@code COACTUPC.cbl}, so {@code account.AccountDateValidator} is a genuine <em>third</em> injector
 * of this singleton alongside {@code ReportRequestController} and {@code TransactionViewController}.
 * That is why {@link Statelessness} is a real requirement here rather than ceremony, and it is also
 * why {@link CallerProjections} asserts the copybook's acceptance rule, which differs materially from
 * the two programs' rule.
 *
 * <h2>Gates that deliberately do not apply, so nobody thinks they were forgotten</h2>
 * <ul>
 *   <li><strong>G32 (restructured backward {@code GO TO}s) does not apply.</strong>
 *       {@code CSUTLDTC} contains <em>no</em> {@code GO TO} whatsoever. Its only control transfer is
 *       the single benign {@code PERFORM A000-MAIN THRU A000-MAIN-EXIT} at L93-L94, a range perform
 *       over a paragraph and its own exit label, which collapses to one method call. Only
 *       {@code CBSTM03A} has loop-forming backward jumps.</li>
 *   <li><strong>G35 (abend mapping) does not apply.</strong> {@code CSUTLDTC} is not one of the nine
 *       {@code CALL 'CEE3ABD'} sites, so no {@code AbendException} can arise. Rather than silently
 *       omitting the gate, {@link ReturnCodeAndAbend#noAbendIsEverRaisedForAnyEvaluateArm()} asserts
 *       positively that nothing is thrown for any of the ten arms.</li>
 *   <li><strong>G47 ({@code FileStatus} outcomes) does not apply.</strong> The program declares no
 *       {@code FD}, opens no dataset and performs pure computation, so there is no file status to
 *       drive.</li>
 *   <li><strong>G21's letter does not fit; G36 governs.</strong> G21 speaks of {@code FILLER} spans
 *       being <em>space-filled</em>, but three of this record's fillers carry literal {@code VALUE}
 *       clauses - {@code 'Mesg Code:'}, {@code 'TstDate:'} and {@code 'Mask used:'}. Space-filling
 *       them in pursuit of G21's wording would corrupt the message and break both callers, which
 *       slice the eighty bytes positionally. This suite therefore asserts the three literals
 *       <em>and</em> the five genuinely blank fillers in every single case.</li>
 * </ul>
 *
 * <h2>Two inherited quirks are documented and asserted, not corrected</h2>
 * <ul>
 *   <li>{@code MOVE WS-SEVERITY-N TO RETURN-CODE} (L98) yields return code <strong>3</strong> for
 *       every error, which sits outside the {@code {0, 4, 8, 12}} set the batch programs use, because
 *       this program moves the <em>severity</em> rather than an {@code APPL-RESULT} constant. It is
 *       recorded as an inherited property and never remapped - see
 *       {@link ReturnCodeAndAbend#returnCodeThreeSitsOutsideTheBatchAbendCodeSet()}.</li>
 *   <li>At the fifth call site the actuals are eight bytes wide - {@code WS-EDIT-DATE-CCYYMMDD}
 *       (CC+YY+MM+DD) and {@code WS-DATE-FORMAT PIC X(08)} - yet the formals are {@code PIC X(10)}.
 *       A COBOL {@code CALL BY REFERENCE} would let the callee read two bytes of <em>adjacent
 *       storage</em>. That storage-adjacency artefact is not expressible in Java and is not part of
 *       the typed contract, so the ruling is ordinary {@code PIC X(10)} right-space-padding and
 *       {@code 'YYYYMMDD'} becomes {@code 'YYYYMMDD  '}. <strong>No test here reads adjacent
 *       memory.</strong></li>
 * </ul>
 *
 * <h2>Numeric hygiene</h2>
 * Severity and message number are scale-free {@code PIC 9(4)} integers, so they are held as
 * {@code int} and as their four-character zero-filled image. There is no {@code double} and no
 * {@code float} anywhere in this file, and no {@code RoundingMode} is referenced at all: the program
 * performs no monetary arithmetic, and since {@code ROUNDED} appears zero times across all 28 legacy
 * programs the only faithful mode would ever be {@code DOWN}, which nothing here needs.
 *
 * @see DateUtilityJob#validateDate(String, String)
 */
@DisplayName("DateUtilityJob - the CSUTLDTC date-validation subprogram")
class DateUtilityJobTest {

    // =============================================================================================
    // The WS-MESSAGE geometry, transcribed from app/cbl/CSUTLDTC.cbl L42-L57.
    //
    // Offsets are absolute and 0-based. Each width is named and each offset is derived from the
    // previous span rather than written as an independent literal, so the table cannot drift out of
    // agreement with itself: a wrong width moves every following offset and the computed total, which
    // RecordGeometry then fails on.
    // =============================================================================================

    /** {@code 02 WS-SEVERITY PIC X(04)} - L43, and its numeric overlay {@code WS-SEVERITY-N} - L44. */
    private static final int SEVERITY_OFFSET = 0;

    /** Declared width shared by {@code WS-SEVERITY}/{@code -N} and {@code WS-MSG-NO}/{@code -N}. */
    private static final int SEVERITY_LENGTH = 4;

    /** {@code 02 FILLER PIC X(11) VALUE 'Mesg Code:'} - L45: ten characters in an eleven-byte span. */
    private static final int MESG_CODE_FILLER_OFFSET = SEVERITY_OFFSET + SEVERITY_LENGTH;

    /** Declared width of the {@code 'Mesg Code:'} filler - L45. */
    private static final int MESG_CODE_FILLER_LENGTH = 11;

    /** {@code 02 WS-MSG-NO PIC X(04)} - L46, and its numeric overlay {@code WS-MSG-NO-N} - L47. */
    private static final int MSG_NO_OFFSET = MESG_CODE_FILLER_OFFSET + MESG_CODE_FILLER_LENGTH;

    /** Declared width of {@code WS-MSG-NO} - L46. */
    private static final int MSG_NO_LENGTH = 4;

    /** {@code 02 FILLER PIC X(01) VALUE SPACE} - L48. */
    private static final int FILLER_AFTER_MSG_NO_OFFSET = MSG_NO_OFFSET + MSG_NO_LENGTH;

    /** Declared width of every single-byte {@code FILLER PIC X(01) VALUE SPACE} - L48, L50, L53, L56. */
    private static final int SINGLE_SPACE_FILLER_LENGTH = 1;

    /** {@code 02 WS-RESULT PIC X(15)} - L49, the span the {@code EVALUATE} at L128-L149 fills. */
    private static final int RESULT_OFFSET =
            FILLER_AFTER_MSG_NO_OFFSET + SINGLE_SPACE_FILLER_LENGTH;

    /** {@code PIC X(15)} - the width the source itself documents at L126-L127. */
    private static final int RESULT_LENGTH = 15;

    /** {@code 02 FILLER PIC X(01) VALUE SPACE} - L50. */
    private static final int FILLER_AFTER_RESULT_OFFSET = RESULT_OFFSET + RESULT_LENGTH;

    /** {@code 02 FILLER PIC X(09) VALUE 'TstDate:'} - L51: eight characters in a nine-byte span. */
    private static final int TST_DATE_FILLER_OFFSET =
            FILLER_AFTER_RESULT_OFFSET + SINGLE_SPACE_FILLER_LENGTH;

    /** Declared width of the {@code 'TstDate:'} filler - L51. */
    private static final int TST_DATE_FILLER_LENGTH = 9;

    /**
     * {@code 02 WS-DATE PIC X(10) VALUE SPACES} - L52.
     *
     * <p>This span does <strong>not</strong> hold the input date. The group move at L122 overwrites
     * it; see {@link GroupMoveTrap}.
     */
    private static final int DATE_OFFSET = TST_DATE_FILLER_OFFSET + TST_DATE_FILLER_LENGTH;

    /** {@code PIC X(10)}, shared by {@code WS-DATE} - L52 - and {@code WS-DATE-FMT} - L55. */
    private static final int DATE_LENGTH = 10;

    /** {@code 02 FILLER PIC X(01) VALUE SPACE} - L53. */
    private static final int FILLER_AFTER_DATE_OFFSET = DATE_OFFSET + DATE_LENGTH;

    /** {@code 02 FILLER PIC X(10) VALUE 'Mask used:'} - L54: an exact fit, so no trailing pad. */
    private static final int MASK_USED_FILLER_OFFSET =
            FILLER_AFTER_DATE_OFFSET + SINGLE_SPACE_FILLER_LENGTH;

    /** Declared width of the {@code 'Mask used:'} filler - L54. */
    private static final int MASK_USED_FILLER_LENGTH = 10;

    /** {@code 02 WS-DATE-FMT PIC X(10)} - L55. Holds the clean mask and is never corrupted. */
    private static final int DATE_FMT_OFFSET = MASK_USED_FILLER_OFFSET + MASK_USED_FILLER_LENGTH;

    /** {@code 02 FILLER PIC X(01) VALUE SPACE} - L56. */
    private static final int FILLER_AFTER_FMT_OFFSET = DATE_FMT_OFFSET + DATE_LENGTH;

    /** {@code 02 FILLER PIC X(03) VALUE SPACES} - L57, the span that closes the eighty bytes. */
    private static final int TRAILING_FILLER_OFFSET =
            FILLER_AFTER_FMT_OFFSET + SINGLE_SPACE_FILLER_LENGTH;

    /** Declared width of the trailing {@code FILLER PIC X(03) VALUE SPACES} - L57. */
    private static final int TRAILING_FILLER_LENGTH = 3;

    /** {@code 01 LS-RESULT PIC X(80)} - L86, and the declared width of {@code WS-MESSAGE} - L42. */
    private static final int MESSAGE_LENGTH = 80;

    // =============================================================================================
    // The literal VALUE clauses, transcribed verbatim from L45, L51 and L54, together with the images
    // they occupy once the PIC X move has padded them to their declared spans.
    // =============================================================================================

    /** {@code VALUE 'Mesg Code:'} - L45, exactly as written in the source. */
    private static final String MESG_CODE_LITERAL = "Mesg Code:";

    /** {@code VALUE 'TstDate:'} - L51, exactly as written in the source. */
    private static final String TST_DATE_LITERAL = "TstDate:";

    /** {@code VALUE 'Mask used:'} - L54, exactly as written in the source. */
    private static final String MASK_USED_LITERAL = "Mask used:";

    /** The eleven-byte image of L45: the ten-character literal plus one trailing pad space. */
    private static final String MESG_CODE_FILLER_IMAGE = "Mesg Code: ";

    /** The nine-byte image of L51: the eight-character literal plus one trailing pad space. */
    private static final String TST_DATE_FILLER_IMAGE = "TstDate: ";

    /** The ten-byte image of L54: the literal fills the span exactly, so there is no pad. */
    private static final String MASK_USED_FILLER_IMAGE = "Mask used:";

    /** The image of each {@code FILLER PIC X(01) VALUE SPACE} - L48, L50, L53 and L56. */
    private static final String ONE_SPACE = " ";

    /** The image of the trailing {@code FILLER PIC X(03) VALUE SPACES} - L57. */
    private static final String THREE_SPACES = "   ";

    // =============================================================================================
    // The ten WS-RESULT texts from the EVALUATE at L128-L148, verbatim and in source order.
    //
    // Trailing spaces are SIGNIFICANT. Two of the source literals are shorter than the PIC X(15)
    // receiver - 'Date is valid' is 13 characters and 'Insufficient' is 12 - and a COBOL MOVE to an
    // alphanumeric receiver pads on the right, so the STORED images carry the pad. The other eight
    // literals already contain their own trailing spaces inside the quotes. Every one of the ten
    // therefore occupies [20,35) as exactly fifteen bytes, and none may ever be trimmed, stripped or
    // compared with 'contains'.
    // =============================================================================================

    /** L130 {@code 'Date is valid'} - thirteen source characters, right-padded to fifteen. */
    private static final String RESULT_DATE_IS_VALID = "Date is valid  ";

    /** L132 {@code 'Insufficient'} - twelve source characters, right-padded to fifteen. */
    private static final String RESULT_INSUFFICIENT = "Insufficient   ";

    /** L134 {@code 'Datevalue error'} - already exactly fifteen characters. */
    private static final String RESULT_DATEVALUE_ERROR = "Datevalue error";

    /** L136 {@code 'Invalid Era    '} - fifteen characters including four trailing spaces. */
    private static final String RESULT_INVALID_ERA = "Invalid Era    ";

    /** L138 {@code 'Unsupp. Range  '} - fifteen characters including two trailing spaces. */
    private static final String RESULT_UNSUPP_RANGE = "Unsupp. Range  ";

    /** L140 {@code 'Invalid month  '} - fifteen characters, lower-case {@code m} preserved. */
    private static final String RESULT_INVALID_MONTH = "Invalid month  ";

    /** L142 {@code 'Bad Pic String '} - fifteen characters including one trailing space. */
    private static final String RESULT_BAD_PIC_STRING = "Bad Pic String ";

    /** L144 {@code 'Nonnumeric data'} - already exactly fifteen characters. */
    private static final String RESULT_NONNUMERIC_DATA = "Nonnumeric data";

    /** L146 {@code 'YearInEra is 0 '} - fifteen characters including one trailing space. */
    private static final String RESULT_YEAR_IN_ERA_ZERO = "YearInEra is 0 ";

    /** L148 {@code 'Date is invalid'} - the {@code WHEN OTHER} text, exactly fifteen characters. */
    private static final String RESULT_DATE_IS_INVALID = "Date is invalid";

    // =============================================================================================
    // The nine 88-level feedback tokens from app/cbl/CSUTLDTC.cbl L62-L70, as their raw hexadecimal
    // VALUE literals.
    //
    // The eight-byte token decomposes as SEVERITY S9(4) BINARY (2 bytes) + MSG-NO S9(4) BINARY (2) +
    // CASE-SEV-CTL X (1) + FACILITY-ID XXX (3), which is the sixteen hexadecimal digits below. Only
    // the severity and the message number ever reach the eighty bytes, at L123 and L124.
    //
    // These are stored as hex STRINGS on purpose: every expected severity and message number in this
    // suite is DECODED from the literal by decodeSeverity/decodeMessageNumber rather than duplicated
    // as a decimal constant, so the transcription is checked mechanically instead of being asserted
    // against a second hand-copied number that could contain the very same slip.
    // =============================================================================================

    /**
     * L62 {@code 88 FC-INVALID-DATE VALUE X'0000000000000000'}.
     *
     * <p><strong>THE INVERSION TRAP.</strong> This all-zeros token is the <em>SUCCESS</em> outcome -
     * severity 0, message 0, which Language Environment reports as {@code CEE000} - and L129-L130
     * maps it to {@code 'Date is valid'}. The condition name flatly contradicts its meaning. A naive
     * expectation inverts the success case and every other row then looks plausible while the suite
     * proves the opposite of the truth. The name is preserved verbatim and the mapping is not
     * flipped, because both are part of the behaviour under migration.
     */
    private static final String TOKEN_FC_INVALID_DATE = "0000000000000000";

    /** L63 {@code 88 FC-INSUFFICIENT-DATA VALUE X'000309CB59C3C5C5'} - severity 3, message 2507. */
    private static final String TOKEN_FC_INSUFFICIENT_DATA = "000309CB59C3C5C5";

    /** L64 {@code 88 FC-BAD-DATE-VALUE VALUE X'000309CC59C3C5C5'} - severity 3, message 2508. */
    private static final String TOKEN_FC_BAD_DATE_VALUE = "000309CC59C3C5C5";

    /** L65 {@code 88 FC-INVALID-ERA VALUE X'000309CD59C3C5C5'} - severity 3, message 2509. */
    private static final String TOKEN_FC_INVALID_ERA = "000309CD59C3C5C5";

    /**
     * L66 {@code 88 FC-UNSUPP-RANGE VALUE X'000309D159C3C5C5'} - severity 3, message
     * <strong>2513</strong> ({@code X'09D1'}).
     *
     * <p>This is the load-bearing token: both program call sites test
     * {@code CSUTLDTC-RESULT-MSG-NUM NOT = '2513'} and therefore accept it silently in spite of
     * severity 3, while the copybook call site tests {@code WS-SEVERITY-N = 0} numerically and
     * rejects it. The service itself must never special-case it - see {@link CallerProjections}.
     */
    private static final String TOKEN_FC_UNSUPP_RANGE = "000309D159C3C5C5";

    /** L67 {@code 88 FC-INVALID-MONTH VALUE X'000309D559C3C5C5'} - severity 3, message 2517. */
    private static final String TOKEN_FC_INVALID_MONTH = "000309D559C3C5C5";

    /** L68 {@code 88 FC-BAD-PIC-STRING VALUE X'000309D659C3C5C5'} - severity 3, message 2518. */
    private static final String TOKEN_FC_BAD_PIC_STRING = "000309D659C3C5C5";

    /** L69 {@code 88 FC-NON-NUMERIC-DATA VALUE X'000309D859C3C5C5'} - severity 3, message 2520. */
    private static final String TOKEN_FC_NON_NUMERIC_DATA = "000309D859C3C5C5";

    /** L70 {@code 88 FC-YEAR-IN-ERA-ZERO VALUE X'000309D959C3C5C5'} - severity 3, message 2521. */
    private static final String TOKEN_FC_YEAR_IN_ERA_ZERO = "000309D959C3C5C5";

    /**
     * The {@code WHEN OTHER} arm at L147-L148, which has no {@code 88}-level {@code VALUE} of its own
     * because it is by definition every token the nine do not enumerate.
     *
     * <p>The implementation reports severity 3 with message number 0 on this path. That pair cannot
     * equal any declared {@code VALUE} - the all-zeros success token carries severity 0, and every
     * named error token carries a message number of at least 2507 - so it necessarily falls through
     * to {@code WHEN OTHER}. The sentinel is written here in the same sixteen-hex-digit shape as the
     * real tokens purely so the ordering assertions can treat all ten uniformly; it is
     * <strong>not</strong> an IBM feedback code and does not claim to be one.
     */
    private static final String TOKEN_UNENUMERATED_SENTINEL = "0003000059C3C5C5";

    /** {@code X'C3C5C5'} - the {@code FACILITY-ID} of every named error token, {@code 'CEE'} in EBCDIC. */
    private static final String FACILITY_ID_CEE_EBCDIC = "C3C5C5";

    /** The IBM code page the {@code FACILITY-ID} bytes are expressed in. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /** The three characters {@code X'C3C5C5'} decodes to under {@link #EBCDIC}. */
    private static final String FACILITY_ID_TEXT = "CEE";

    /** {@code X'59'} - the {@code CASE-SEV-CTL} byte every named error token carries. */
    private static final int CASE_SEV_CTL_ERROR = 0x59;

    // =============================================================================================
    // The two picture strings that actually reach this service, from its five real call sites.
    // =============================================================================================

    /**
     * {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} - declared at {@code CORPT00C.cbl} L72 and
     * {@code COTRN02C.cbl} L60. Ten characters, so it fills the {@code X(10)} formal exactly.
     */
    private static final String MASK_HYPHENATED = "YYYY-MM-DD";

    /**
     * {@code WS-DATE-FORMAT PIC X(08) VALUE 'YYYYMMDD'} - declared in {@code app/cpy/CSUTLDWY.cpy}
     * L58-L59 and re-established at {@code app/cpy/CSUTLDPY.cpy} L291, immediately before the fifth
     * {@code CALL} at L293. Only eight characters against an {@code X(10)} formal.
     */
    private static final String MASK_COMPACT = "YYYYMMDD";

    /**
     * The ten-byte image {@link #MASK_COMPACT} occupies once the {@code PIC X(10)} move has padded
     * it. This is the Java ruling on the eight-byte-actual divergence described in the class comment:
     * ordinary right-space-padding, never a read of adjacent storage.
     */
    private static final String MASK_COMPACT_IMAGE = "YYYYMMDD  ";

    // =============================================================================================
    // The caller-side 80-byte projection - a genuinely DIFFERENT split of the same bytes.
    //
    // Declared identically at app/cbl/CORPT00C.cbl L129-L136 and app/cbl/COTRN02C.cbl L62-L69:
    //   10 CSUTLDTC-RESULT-SEV-CD   PIC X(04)
    //   10 FILLER                   PIC X(11)
    //   10 CSUTLDTC-RESULT-MSG-NUM  PIC X(04)
    //   10 CSUTLDTC-RESULT-MSG      PIC X(61)
    // Four spans, not thirteen, and the last one swallows everything from offset 19 to the end.
    // =============================================================================================

    /** {@code CSUTLDTC-RESULT-SEV-CD PIC X(04)} at offset 0. */
    private static final int CALLER_SEV_CD_OFFSET = 0;

    /** Declared width of {@code CSUTLDTC-RESULT-SEV-CD}. */
    private static final int CALLER_SEV_CD_LENGTH = 4;

    /** The caller's unnamed {@code FILLER PIC X(11)}, which spans the {@code 'Mesg Code: '} text. */
    private static final int CALLER_FILLER_OFFSET = CALLER_SEV_CD_OFFSET + CALLER_SEV_CD_LENGTH;

    /** Declared width of the caller's {@code FILLER PIC X(11)}. */
    private static final int CALLER_FILLER_LENGTH = 11;

    /** {@code CSUTLDTC-RESULT-MSG-NUM PIC X(04)} at offset 15. */
    private static final int CALLER_MSG_NUM_OFFSET = CALLER_FILLER_OFFSET + CALLER_FILLER_LENGTH;

    /** Declared width of {@code CSUTLDTC-RESULT-MSG-NUM}. */
    private static final int CALLER_MSG_NUM_LENGTH = 4;

    /** {@code CSUTLDTC-RESULT-MSG PIC X(61)} at offset 19, running to the end of the record. */
    private static final int CALLER_MSG_OFFSET = CALLER_MSG_NUM_OFFSET + CALLER_MSG_NUM_LENGTH;

    /** Declared width of {@code CSUTLDTC-RESULT-MSG} - sixty-one bytes, spanning [19,80). */
    private static final int CALLER_MSG_LENGTH = 61;

    /** The literal both program call sites compare the severity code against, for acceptance. */
    private static final String ACCEPTED_SEVERITY_CODE = "0000";

    /** The message number both program call sites tolerate in spite of a non-zero severity. */
    private static final String TOLERATED_MESSAGE_NUMBER = "2513";

    /** {@code IF WS-SEVERITY-N = 0} - the numeric acceptance test at {@code CSUTLDPY.cpy} L298. */
    private static final int ACCEPTED_SEVERITY_NUMBER = 0;

    // =============================================================================================
    // The four caller rejection texts, verbatim, so a later edit cannot silently reword them.
    // Asserted as TEXT only: nothing here asserts how those callers are wired, which belongs to the
    // transaction and account test packages.
    // =============================================================================================

    /** {@code CORPT00C.cbl} L400, on the start-date call at L392. */
    private static final String ERROR_TEXT_START_DATE = "Start Date - Not a valid date...";

    /** {@code CORPT00C.cbl} L420, on the end-date call at L412. */
    private static final String ERROR_TEXT_END_DATE = "End Date - Not a valid date...";

    /** {@code COTRN02C.cbl} L401, on the origination-date call at L393. */
    private static final String ERROR_TEXT_ORIG_DATE = "Orig Date - Not a valid date...";

    /** {@code COTRN02C.cbl} L421, on the processing-date call at L413. */
    private static final String ERROR_TEXT_PROC_DATE = "Proc Date - Not a valid date...";

    // =============================================================================================
    // Group-move constants - app/cbl/CSUTLDTC.cbl L25-L31, L105-L108 and L122.
    // =============================================================================================

    /** {@code 02 Vstring-length PIC S9(4) BINARY} - L26: a two-byte big-endian halfword. */
    private static final int VSTRING_LENGTH_BYTES = 2;

    /**
     * How many characters of {@code Vstring-text} survive the L122 group move into {@code PIC X(10)}.
     *
     * <p>Derived, not assumed: the group image is {@link #VSTRING_LENGTH_BYTES} plus ten text bytes,
     * an alphanumeric group move is left-justified and truncated on the right, and the receiver is
     * {@link #DATE_LENGTH} wide - so ten minus two text bytes survive.
     */
    private static final int SURVIVING_TEXT_BYTES = DATE_LENGTH - VSTRING_LENGTH_BYTES;

    /** Mask isolating one byte when the halfword is split into its big-endian components. */
    private static final int BYTE_MASK = 0xFF;

    /** The representative input date used wherever one concrete date is needed. */
    private static final String SAMPLE_DATE = "2022-07-18";

    // =============================================================================================
    // Shared fixtures. Every one is immutable, so this class holds NO mutable static state.
    // =============================================================================================

    /** The code page the service renders its eighty bytes in, named explicitly and never defaulted. */
    private static final Charset MESSAGE_CHARSET = StandardCharsets.US_ASCII;

    /**
     * A fresh service for the current test.
     *
     * <p>Deliberately a method rather than a field: it keeps the instance per-test, which is what
     * lets {@link Statelessness} prove sharing is safe by <em>choosing</em> when to reuse one
     * instance rather than inheriting a shared one by accident. It also documents that the class
     * needs no repository, no {@code JdbcTemplate}, no dataset binding, no configuration key, no
     * clock and no mock - constructor injection with nothing to inject.
     *
     * @return a new service bound to {@link #MESSAGE_CHARSET}
     */
    private static DateUtilityJob newService() {
        return new DateUtilityJob(MESSAGE_CHARSET);
    }

    /**
     * Decodes the {@code SEVERITY S9(4) BINARY} halfword from a raw {@code 88}-level hex literal.
     *
     * @param hexToken the sixteen hexadecimal digits of the token
     * @return the first two bytes read as a big-endian halfword
     */
    private static int decodeSeverity(String hexToken) {
        return Integer.parseInt(hexToken.substring(0, 4), 16);
    }

    /**
     * Decodes the {@code MSG-NO S9(4) BINARY} halfword from a raw {@code 88}-level hex literal.
     *
     * @param hexToken the sixteen hexadecimal digits of the token
     * @return the second two bytes read as a big-endian halfword
     */
    private static int decodeMessageNumber(String hexToken) {
        return Integer.parseInt(hexToken.substring(4, 8), 16);
    }

    /**
     * Renders a decoded halfword the way {@code PIC 9(4)} stores it: four digits, left zero-filled.
     *
     * <p>Formatted against {@link Locale#ROOT} explicitly. A locale-sensitive format would let
     * default digit shaping substitute non-ASCII digits and silently corrupt a span that the callers
     * compare byte-for-byte against {@code '0000'} and {@code '2513'}.
     *
     * @param value the decoded severity or message number
     * @return exactly four characters
     */
    private static String asPicNine4(int value) {
        return String.format(Locale.ROOT, "%04d", value);
    }

    /**
     * Builds the ten bytes the L122 group move leaves in {@code WS-DATE}, from group-move semantics.
     *
     * <p><strong>This is derived from the move, never from the input date.</strong>
     * {@code WS-DATE-TO-TEST} (L25-L31) is {@code Vstring-length PIC S9(4) BINARY} followed by
     * {@code Vstring-text}, and L105-L106 sets that length from {@code LENGTH OF LS-DATE} - the
     * <em>declared</em> width of {@code PIC X(10)}, which is always ten whatever the content. So the
     * group's storage image is twelve bytes: a big-endian halfword of value ten, then the ten text
     * bytes. Moving that group into an elementary alphanumeric {@code PIC X(10)} receiver is a group
     * move, left-justified and truncated on the right, so the halfword survives and only the first
     * {@link #SURVIVING_TEXT_BYTES} text bytes follow it. The last two characters of the date are
     * lost on every single call.
     *
     * @param paddedDate the ten-character image of {@code LS-DATE} after the {@code PIC X} move
     * @return exactly {@link #DATE_LENGTH} bytes
     */
    private static byte[] expectedGroupMoveImage(String paddedDate) {
        final byte[] image = new byte[DATE_LENGTH];
        // The halfword is big-endian: high-order byte first. A little-endian assumption would give
        // 0x0A 0x00 and be wrong.
        image[0] = (byte) ((DATE_LENGTH >>> Byte.SIZE) & BYTE_MASK);
        image[1] = (byte) (DATE_LENGTH & BYTE_MASK);
        final byte[] text = paddedDate.getBytes(MESSAGE_CHARSET);
        System.arraycopy(text, 0, image, VSTRING_LENGTH_BYTES, SURVIVING_TEXT_BYTES);
        return image;
    }

    /**
     * Applies a COBOL {@code MOVE} to a {@code PIC X(n)} receiver: right-pad when short, right-truncate
     * when long. Routed through the production codec so the test never re-implements the rule it is
     * verifying against.
     *
     * @param source       the sending item
     * @param targetLength the declared width of the receiver
     * @return exactly {@code targetLength} characters
     */
    private static String movePicX(String source, int targetLength) {
        return new FixedWidthCodec(MESSAGE_CHARSET).movePicX(source, targetLength);
    }

    /**
     * The acceptance predicate both <em>program</em> call sites apply - {@code CORPT00C.cbl} L396-L406
     * and L416-L426, {@code COTRN02C.cbl} L397-L407 and L417-L427:
     * <pre>
     *   IF CSUTLDTC-RESULT-SEV-CD = '0000'   *&gt; accept
     *   ELSE IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'   *&gt; reject
     * </pre>
     * Both comparisons are <strong>alphanumeric</strong>, against the four-character images, and the
     * chain tolerates message 2513 in spite of its severity 3.
     *
     * @param result the outcome the service returned
     * @return {@code true} when the caller would accept the date
     */
    private static boolean programCallerAccepts(DateValidationResult result) {
        return ACCEPTED_SEVERITY_CODE.equals(result.severityCode())
                || TOLERATED_MESSAGE_NUMBER.equals(result.messageNumber());
    }

    /**
     * The acceptance predicate the <em>copybook</em> call site applies - {@code app/cpy/CSUTLDPY.cpy}
     * L298, {@code IF WS-SEVERITY-N = 0}.
     *
     * <p>This is a <strong>numeric</strong> comparison against the {@code REDEFINES} overlay, and it
     * does <strong>not</strong> tolerate 2513: any non-zero severity is an error there. The two rules
     * genuinely disagree about message 2513, which is precisely why the service must never decide the
     * question itself.
     *
     * @param result the outcome the service returned
     * @return {@code true} when the copybook would accept the date
     */
    private static boolean copybookCallerAccepts(DateValidationResult result) {
        return result.returnCode() == ACCEPTED_SEVERITY_NUMBER;
    }

    // =============================================================================================
    // The ten EVALUATE arms, in source order.
    // =============================================================================================

    /**
     * One arm of the {@code EVALUATE TRUE} at {@code app/cbl/CSUTLDTC.cbl} L128-L149.
     *
     * @param armNumber      the arm's 1-based position in source order, 1 through 10
     * @param cobolName      the {@code 88}-level condition name, or the {@code WHEN OTHER} marker
     * @param hexToken       the raw hexadecimal {@code VALUE} literal, from which the expected
     *                       severity and message number are <em>decoded</em> rather than duplicated
     * @param inputDate      an {@code LS-DATE} that drives the validator down this arm
     * @param pictureMask    the {@code LS-DATE-FORMAT} accompanying {@code inputDate}
     * @param expectedResult the fifteen-character {@code WS-RESULT} image this arm stores, trailing
     *                       spaces included
     */
    private record EvaluateArm(int armNumber,
                               String cobolName,
                               String hexToken,
                               String inputDate,
                               String pictureMask,
                               String expectedResult) {

        /** @return the four-character {@code WS-SEVERITY} image, decoded from {@link #hexToken} */
        String expectedSeverityCode() {
            return asPicNine4(decodeSeverity(hexToken));
        }

        /** @return the four-character {@code WS-MSG-NO} image, decoded from {@link #hexToken} */
        String expectedMessageNumber() {
            return asPicNine4(decodeMessageNumber(hexToken));
        }

        /** @return the {@code RETURN-CODE} L98 leaves behind: the severity as a number */
        int expectedReturnCode() {
            return decodeSeverity(hexToken);
        }

        @Override
        public String toString() {
            return "arm " + armNumber + " " + cobolName + " -> '" + expectedResult + "'";
        }
    }

    /**
     * The ten arms of {@code EVALUATE TRUE} (L128-L149) in <strong>source order</strong>, each paired
     * with a {@code (date, mask)} input that drives the validator down it.
     *
     * <p>Order is preserved because G30 requires the {@code WHEN} sequence to be honoured with
     * {@code WHEN OTHER} last, and because reading this list beside L128-L149 is how a reviewer checks
     * the transcription. Every arm is reached through the <strong>public</strong> three-parameter API:
     * no reflection, no package-private seam and no test-only accessor is needed, which also means the
     * assertions exercise exactly the path the five real call sites take.
     *
     * @return the ten arms, in the order the {@code EVALUATE} declares them
     */
    private static Stream<EvaluateArm> evaluateArmsInSourceOrder() {
        return Stream.of(
                // Arm 1, L129-L130. THE INVERSION TRAP: the condition is named FC-INVALID-DATE but
                // its all-zeros VALUE is the SUCCESS token, and it selects 'Date is valid'.
                new EvaluateArm(1, "FC-INVALID-DATE", TOKEN_FC_INVALID_DATE,
                        SAMPLE_DATE, MASK_HYPHENATED, RESULT_DATE_IS_VALID),

                // Arm 2, L131-L132. The picture string carries too little to compute a Lillian value:
                // IBM requires year+month+day, or year+day-of-year. 'YYYY' alone supplies only a year.
                new EvaluateArm(2, "FC-INSUFFICIENT-DATA", TOKEN_FC_INSUFFICIENT_DATA,
                        SAMPLE_DATE, "YYYY      ", RESULT_INSUFFICIENT),

                // Arm 3, L133-L134. A day-of-month invalid for that year and month - IBM's own
                // illustration is 31 June, and 30 February is the same fault.
                new EvaluateArm(3, "FC-BAD-DATE-VALUE", TOKEN_FC_BAD_DATE_VALUE,
                        "2022-02-30", MASK_HYPHENATED, RESULT_DATEVALUE_ERROR),

                // Arm 4, L135-L136. An era field holding a name that is not an era. '<CC>' occupies
                // four PICTURE characters but consumes only two INPUT characters, and its content is
                // skipped, so '<CC>YYMMDD' is exactly ten picture characters describing eight input
                // characters; the era NAME comes from the input, where 'ZZ' is not 'AD' or 'BC'.
                new EvaluateArm(4, "FC-INVALID-ERA", TOKEN_FC_INVALID_ERA,
                        "ZZ220718  ", "<CC>YYMMDD", RESULT_INVALID_ERA),

                // Arm 5, L137-L138. Outside the supported range: the Lillian epoch is 15 October 1582
                // and 1500 precedes it. This is message 2513, the one both program callers tolerate.
                new EvaluateArm(5, "FC-UNSUPP-RANGE", TOKEN_FC_UNSUPP_RANGE,
                        "1500-07-18", MASK_HYPHENATED, RESULT_UNSUPP_RANGE),

                // Arm 6, L139-L140. A month outside 1 to 12.
                new EvaluateArm(6, "FC-INVALID-MONTH", TOKEN_FC_INVALID_MONTH,
                        "2022-13-18", MASK_HYPHENATED, RESULT_INVALID_MONTH),

                // Arm 7, L141-L142. The picture string itself is not valid: 'QQQQ' begins no token.
                new EvaluateArm(7, "FC-BAD-PIC-STRING", TOKEN_FC_BAD_PIC_STRING,
                        SAMPLE_DATE, "QQQQ-MM-DD", RESULT_BAD_PIC_STRING),

                // Arm 8, L143-L144. Non-numeric data where the picture located digits.
                new EvaluateArm(8, "FC-NON-NUMERIC-DATA", TOKEN_FC_NON_NUMERIC_DATA,
                        "20XX-07-18", MASK_HYPHENATED, RESULT_NONNUMERIC_DATA),

                // Arm 9, L145-L146. A year-within-era of zero.
                new EvaluateArm(9, "FC-YEAR-IN-ERA-ZERO", TOKEN_FC_YEAR_IN_ERA_ZERO,
                        "0000-07-18", MASK_HYPHENATED, RESULT_YEAR_IN_ERA_ZERO),

                // Arm 10, L147-L148, WHEN OTHER. Reached through the public API by a literal-delimiter
                // mismatch: the picture demands '-' at offsets 4 and 7 and the input supplies '/'.
                // No documented CEEDAYS code describes a shape mismatch of that kind, which is exactly
                // what WHEN OTHER exists for.
                new EvaluateArm(10, "WHEN OTHER", TOKEN_UNENUMERATED_SENTINEL,
                        "2022/07/18", MASK_HYPHENATED, RESULT_DATE_IS_INVALID));
    }

    /**
     * Asserts the invariant part of every eighty-byte result: the three literal {@code FILLER}s and
     * the five genuinely blank ones.
     *
     * <p>This is the core of <strong>G36</strong> and it holds in <em>every</em> case, success and
     * failure alike. {@code INITIALIZE WS-MESSAGE} (L90) sets elementary {@code PIC X} items to spaces
     * and {@code PIC 9} items to zero but <strong>ignores {@code FILLER} items</strong> and items
     * bearing {@code REDEFINES}, so all three literals survive from their {@code VALUE} clauses
     * untouched no matter which arm fires.
     *
     * @param message the eighty-character image the service returned
     */
    private static void assertFillersAreIntact(String message) {
        assertThat(message).as("the message must be exactly LS-RESULT PIC X(80)")
                .hasSize(MESSAGE_LENGTH);

        // The three literal fillers - L45, L51, L54. Two of them carry a trailing pad byte because
        // the declared span is wider than the literal; the third fits exactly.
        assertThat(message.substring(MESG_CODE_FILLER_OFFSET,
                MESG_CODE_FILLER_OFFSET + MESG_CODE_FILLER_LENGTH))
                .as("L45 FILLER PIC X(11) VALUE 'Mesg Code:' - literal plus one pad space")
                .isEqualTo(MESG_CODE_FILLER_IMAGE)
                .startsWith(MESG_CODE_LITERAL)
                .hasSize(MESG_CODE_FILLER_LENGTH);
        assertThat(message.substring(TST_DATE_FILLER_OFFSET,
                TST_DATE_FILLER_OFFSET + TST_DATE_FILLER_LENGTH))
                .as("L51 FILLER PIC X(09) VALUE 'TstDate:' - literal plus one pad space")
                .isEqualTo(TST_DATE_FILLER_IMAGE)
                .startsWith(TST_DATE_LITERAL)
                .hasSize(TST_DATE_FILLER_LENGTH);
        assertThat(message.substring(MASK_USED_FILLER_OFFSET,
                MASK_USED_FILLER_OFFSET + MASK_USED_FILLER_LENGTH))
                .as("L54 FILLER PIC X(10) VALUE 'Mask used:' - an exact fit, so no pad byte")
                .isEqualTo(MASK_USED_FILLER_IMAGE)
                .isEqualTo(MASK_USED_LITERAL)
                .hasSize(MASK_USED_FILLER_LENGTH);

        // The five blank fillers - L48, L50, L53, L56 and the trailing L57.
        assertThat(message.substring(FILLER_AFTER_MSG_NO_OFFSET,
                FILLER_AFTER_MSG_NO_OFFSET + SINGLE_SPACE_FILLER_LENGTH))
                .as("L48 FILLER PIC X(01) VALUE SPACE").isEqualTo(ONE_SPACE);
        assertThat(message.substring(FILLER_AFTER_RESULT_OFFSET,
                FILLER_AFTER_RESULT_OFFSET + SINGLE_SPACE_FILLER_LENGTH))
                .as("L50 FILLER PIC X(01) VALUE SPACE").isEqualTo(ONE_SPACE);
        assertThat(message.substring(FILLER_AFTER_DATE_OFFSET,
                FILLER_AFTER_DATE_OFFSET + SINGLE_SPACE_FILLER_LENGTH))
                .as("L53 FILLER PIC X(01) VALUE SPACE").isEqualTo(ONE_SPACE);
        assertThat(message.substring(FILLER_AFTER_FMT_OFFSET,
                FILLER_AFTER_FMT_OFFSET + SINGLE_SPACE_FILLER_LENGTH))
                .as("L56 FILLER PIC X(01) VALUE SPACE").isEqualTo(ONE_SPACE);
        assertThat(message.substring(TRAILING_FILLER_OFFSET,
                TRAILING_FILLER_OFFSET + TRAILING_FILLER_LENGTH))
                .as("L57 FILLER PIC X(03) VALUE SPACES").isEqualTo(THREE_SPACES);
    }

    // =============================================================================================
    // Group 1 - the eighty-byte geometry.
    // =============================================================================================

    /**
     * The record geometry of {@code WS-MESSAGE} (L42-L57) and of the caller-side projection.
     *
     * <p>Both totals are asserted as <strong>computed sums of the declared widths</strong>, never as
     * the literal 80. A magic 80 would still pass if two widths were transposed; a computed sum fails
     * the moment any single width is wrong.
     */
    @Nested
    @DisplayName("WS-MESSAGE geometry - app/cbl/CSUTLDTC.cbl L42-L57")
    class RecordGeometry {

        @Test
        @DisplayName("the thirteen declared spans sum to exactly eighty bytes")
        void thirteenDeclaredSpansSumToExactlyEighty() {
            // 4 + 11 + 4 + 1 + 15 + 1 + 9 + 10 + 1 + 10 + 10 + 1 + 3, written as the named widths in
            // declaration order so the arithmetic is auditable against the copybook line by line.
            final int computedTotal = SEVERITY_LENGTH
                    + MESG_CODE_FILLER_LENGTH
                    + MSG_NO_LENGTH
                    + SINGLE_SPACE_FILLER_LENGTH
                    + RESULT_LENGTH
                    + SINGLE_SPACE_FILLER_LENGTH
                    + TST_DATE_FILLER_LENGTH
                    + DATE_LENGTH
                    + SINGLE_SPACE_FILLER_LENGTH
                    + MASK_USED_FILLER_LENGTH
                    + DATE_LENGTH
                    + SINGLE_SPACE_FILLER_LENGTH
                    + TRAILING_FILLER_LENGTH;

            assertThat(computedTotal)
                    .as("the thirteen WS-MESSAGE spans must fill LS-RESULT PIC X(80) exactly")
                    .isEqualTo(MESSAGE_LENGTH)
                    .isEqualTo(DateUtilityJob.LS_RESULT_LENGTH);
        }

        @Test
        @DisplayName("the caller's four-span projection also sums to exactly eighty bytes")
        void callerProjectionSumsToExactlyEighty() {
            // 4 + 11 + 4 + 61 - a genuinely different split of the very same bytes, declared at
            // CORPT00C.cbl L129-L136 and COTRN02C.cbl L62-L69.
            final int computedTotal = CALLER_SEV_CD_LENGTH
                    + CALLER_FILLER_LENGTH
                    + CALLER_MSG_NUM_LENGTH
                    + CALLER_MSG_LENGTH;

            assertThat(computedTotal)
                    .as("the caller's CSUTLDTC-RESULT must also be exactly eighty bytes")
                    .isEqualTo(MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("each span sits at the absolute offset the copybook declares")
        void eachSpanSitsAtItsDeclaredAbsoluteOffset() {
            // Absolute, 0-based offsets read straight off L42-L57. Asserted explicitly rather than
            // inferred, because every caller slices these eighty bytes positionally.
            assertThat(SEVERITY_OFFSET).as("L43 WS-SEVERITY").isZero();
            assertThat(MESG_CODE_FILLER_OFFSET).as("L45 'Mesg Code:' filler").isEqualTo(4);
            assertThat(MSG_NO_OFFSET).as("L46 WS-MSG-NO").isEqualTo(15);
            assertThat(FILLER_AFTER_MSG_NO_OFFSET).as("L48 space filler").isEqualTo(19);
            assertThat(RESULT_OFFSET).as("L49 WS-RESULT").isEqualTo(20);
            assertThat(FILLER_AFTER_RESULT_OFFSET).as("L50 space filler").isEqualTo(35);
            assertThat(TST_DATE_FILLER_OFFSET).as("L51 'TstDate:' filler").isEqualTo(36);
            assertThat(DATE_OFFSET).as("L52 WS-DATE").isEqualTo(45);
            assertThat(FILLER_AFTER_DATE_OFFSET).as("L53 space filler").isEqualTo(55);
            assertThat(MASK_USED_FILLER_OFFSET).as("L54 'Mask used:' filler").isEqualTo(56);
            assertThat(DATE_FMT_OFFSET).as("L55 WS-DATE-FMT").isEqualTo(66);
            assertThat(FILLER_AFTER_FMT_OFFSET).as("L56 space filler").isEqualTo(76);
            assertThat(TRAILING_FILLER_OFFSET).as("L57 trailing filler").isEqualTo(77);
            assertThat(TRAILING_FILLER_OFFSET + TRAILING_FILLER_LENGTH)
                    .as("the last span must close the record at exactly eighty bytes")
                    .isEqualTo(MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("an independent transcription of the layout is contiguous and eighty bytes wide")
        void anIndependentTranscriptionOfTheLayoutIsContiguousAndEightyWide() {
            // A SECOND, independent transcription of L42-L57, declared here through the same
            // fixed-width primitives the production code uses. RecordLayout refuses to be constructed
            // unless every span is contiguous from offset 0 and the total equals the declared length,
            // so simply building this constant proves the geometry - and building it from the test's
            // own constants proves the test agrees with the production layout rather than restating it.
            final FieldSpan severity =
                    FieldSpan.alphanumeric("WS-SEVERITY", SEVERITY_OFFSET, SEVERITY_LENGTH);
            final FieldSpan msgNo =
                    FieldSpan.alphanumeric("WS-MSG-NO", MSG_NO_OFFSET, MSG_NO_LENGTH);

            final RecordLayout layout = RecordLayout.of(MESSAGE_LENGTH,
                    severity,
                    // L44 WS-SEVERITY-N REDEFINES WS-SEVERITY PIC 9(4) - an overlay over the SAME
                    // storage, contributing nothing to the record total.
                    severity.redefinedAs("WS-SEVERITY-N", PictureKind.UNSIGNED_NUMERIC),
                    FieldSpan.filler(MESG_CODE_FILLER_OFFSET, MESG_CODE_FILLER_LENGTH,
                            MESG_CODE_LITERAL),
                    msgNo,
                    // L47 WS-MSG-NO-N REDEFINES WS-MSG-NO PIC 9(4).
                    msgNo.redefinedAs("WS-MSG-NO-N", PictureKind.UNSIGNED_NUMERIC),
                    FieldSpan.filler(FILLER_AFTER_MSG_NO_OFFSET, SINGLE_SPACE_FILLER_LENGTH,
                            ONE_SPACE),
                    FieldSpan.alphanumeric("WS-RESULT", RESULT_OFFSET, RESULT_LENGTH),
                    FieldSpan.filler(FILLER_AFTER_RESULT_OFFSET, SINGLE_SPACE_FILLER_LENGTH,
                            ONE_SPACE),
                    FieldSpan.filler(TST_DATE_FILLER_OFFSET, TST_DATE_FILLER_LENGTH,
                            TST_DATE_LITERAL),
                    FieldSpan.alphanumeric("WS-DATE", DATE_OFFSET, DATE_LENGTH),
                    FieldSpan.filler(FILLER_AFTER_DATE_OFFSET, SINGLE_SPACE_FILLER_LENGTH, ONE_SPACE),
                    FieldSpan.filler(MASK_USED_FILLER_OFFSET, MASK_USED_FILLER_LENGTH,
                            MASK_USED_LITERAL),
                    FieldSpan.alphanumeric("WS-DATE-FMT", DATE_FMT_OFFSET, DATE_LENGTH),
                    FieldSpan.filler(FILLER_AFTER_FMT_OFFSET, SINGLE_SPACE_FILLER_LENGTH, ONE_SPACE),
                    FieldSpan.filler(TRAILING_FILLER_OFFSET, TRAILING_FILLER_LENGTH, THREE_SPACES));

            assertThat(layout.recordLength()).isEqualTo(MESSAGE_LENGTH);
            assertThat(layout.hasSpan("WS-SEVERITY")).isTrue();
            assertThat(layout.hasSpan("WS-SEVERITY-N")).isTrue();
            assertThat(layout.hasSpan("WS-MSG-NO")).isTrue();
            assertThat(layout.hasSpan("WS-MSG-NO-N")).isTrue();
            assertThat(layout.hasSpan("WS-RESULT")).isTrue();
            assertThat(layout.hasSpan("WS-DATE")).isTrue();
            assertThat(layout.hasSpan("WS-DATE-FMT")).isTrue();

            // The two REDEFINES overlays must address exactly the storage they redefine.
            assertThat(layout.span("WS-SEVERITY-N").offset())
                    .isEqualTo(layout.span("WS-SEVERITY").offset());
            assertThat(layout.span("WS-SEVERITY-N").length())
                    .isEqualTo(layout.span("WS-SEVERITY").length());
            assertThat(layout.span("WS-SEVERITY-N").redefinition()).isTrue();
            assertThat(layout.span("WS-MSG-NO-N").offset())
                    .isEqualTo(layout.span("WS-MSG-NO").offset());
            assertThat(layout.span("WS-MSG-NO-N").redefinition()).isTrue();

            // WS-RESULT ends where the following space filler begins, and WS-DATE-FMT closes at 76.
            assertThat(layout.span("WS-RESULT").endOffsetExclusive())
                    .isEqualTo(FILLER_AFTER_RESULT_OFFSET);
            assertThat(layout.span("WS-DATE-FMT").endOffsetExclusive())
                    .isEqualTo(FILLER_AFTER_FMT_OFFSET);
        }

        @Test
        @DisplayName("the declared linkage widths match PROCEDURE DIVISION USING at L83-L88")
        void declaredLinkageWidthsMatchTheProcedureDivisionUsingClause() {
            // 01 LS-DATE PIC X(10) - L84, 01 LS-DATE-FORMAT PIC X(10) - L85,
            // 01 LS-RESULT PIC X(80) - L86.
            assertThat(DateUtilityJob.LS_DATE_LENGTH).as("LS-DATE PIC X(10)").isEqualTo(DATE_LENGTH);
            assertThat(DateUtilityJob.LS_DATE_FORMAT_LENGTH).as("LS-DATE-FORMAT PIC X(10)")
                    .isEqualTo(DATE_LENGTH);
            assertThat(DateUtilityJob.LS_RESULT_LENGTH).as("LS-RESULT PIC X(80)")
                    .isEqualTo(MESSAGE_LENGTH);
        }
    }

    // =============================================================================================
    // Group 2 - all ten EVALUATE arms. This one parameterised method carries the bulk of the
    // package's branch surface, which is what makes the coverage gate cheap here.
    // =============================================================================================

    /**
     * The {@code EVALUATE TRUE} at L128-L149, one arm at a time.
     *
     * <p>Ten invocations, one per arm, in source order. Each asserts the full observable outcome: both
     * four-character numeric images, the fifteen-character result text, the return code, the eighty-byte
     * width and every filler.
     */
    @Nested
    @DisplayName("EVALUATE TRUE - all ten arms, L128-L149")
    class EvaluateArms {

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.vsergeychik.carddemo.util.DateUtilityJobTest#evaluateArmsInSourceOrder")
        @DisplayName("each arm yields its own severity, message number, result text and return code")
        void eachArmYieldsItsOwnObservableOutcome(EvaluateArm arm) {
            final DateValidationResult result =
                    newService().validateDate(arm.inputDate(), arm.pictureMask());

            // L123 MOVE SEVERITY OF FEEDBACK-CODE TO WS-SEVERITY-N, then read back through the
            // character view. PIC 9(4) ZERO-fills, so severity 3 is '0003' - never '3', never '   3'.
            assertThat(result.severityCode())
                    .as("WS-SEVERITY at [0,4) for %s", arm.cobolName())
                    .isEqualTo(arm.expectedSeverityCode())
                    .hasSize(SEVERITY_LENGTH)
                    .containsOnlyDigits();

            // L124 MOVE MSG-NO OF FEEDBACK-CODE TO WS-MSG-NO-N.
            assertThat(result.messageNumber())
                    .as("WS-MSG-NO at [15,19) for %s", arm.cobolName())
                    .isEqualTo(arm.expectedMessageNumber())
                    .hasSize(MSG_NO_LENGTH)
                    .containsOnlyDigits();

            // WS-RESULT PIC X(15). Trailing spaces are significant: 'Date is valid' is 13 source
            // characters and 'Insufficient' is 12, and the MOVE right-pads both to fifteen. Compared
            // with exact equality - never trimmed, never stripped, never 'contains'.
            assertThat(result.result())
                    .as("WS-RESULT at [20,35) for %s - trailing spaces are significant",
                            arm.cobolName())
                    .isEqualTo(arm.expectedResult())
                    .hasSize(RESULT_LENGTH);

            // L98 MOVE WS-SEVERITY-N TO RETURN-CODE: 0 for the success token, 3 for every error.
            assertThat(result.returnCode())
                    .as("RETURN-CODE for %s", arm.cobolName())
                    .isEqualTo(arm.expectedReturnCode());

            // L97 MOVE WS-MESSAGE TO LS-RESULT is X(80) -> X(80), an exact copy.
            assertThat(result.message()).hasSize(MESSAGE_LENGTH);
            assertThat(result.messageBytes()).hasSize(MESSAGE_LENGTH);

            // The result text must appear at its absolute offset, not merely on the accessor.
            assertThat(result.message().substring(RESULT_OFFSET, RESULT_OFFSET + RESULT_LENGTH))
                    .isEqualTo(arm.expectedResult());
            assertThat(result.message().substring(SEVERITY_OFFSET,
                    SEVERITY_OFFSET + SEVERITY_LENGTH)).isEqualTo(arm.expectedSeverityCode());
            assertThat(result.message().substring(MSG_NO_OFFSET, MSG_NO_OFFSET + MSG_NO_LENGTH))
                    .isEqualTo(arm.expectedMessageNumber());

            // G36 - all three literal fillers and all five blank ones, on EVERY arm including every
            // error arm. INITIALIZE (L90) never touches a FILLER, so the literals always survive.
            assertFillersAreIntact(result.message());

            // The mask span is never corrupted, on any arm.
            assertThat(result.message().substring(DATE_FMT_OFFSET, DATE_FMT_OFFSET + DATE_LENGTH))
                    .as("WS-DATE-FMT at [66,76) holds the clean mask on every arm")
                    .isEqualTo(movePicX(arm.pictureMask(), DATE_LENGTH));

            // The group-move prefix is invariant on every arm, because the varying-string length comes
            // from LENGTH OF LS-DATE and is therefore always ten.
            final byte[] dateSpan = new byte[DATE_LENGTH];
            System.arraycopy(result.messageBytes(), DATE_OFFSET, dateSpan, 0, DATE_LENGTH);
            assertThat(dateSpan)
                    .as("WS-DATE at [45,55) after the L122 group move for %s", arm.cobolName())
                    .isEqualTo(expectedGroupMoveImage(movePicX(arm.inputDate(), DATE_LENGTH)));
        }

        @Test
        @DisplayName("the table transcribes exactly ten arms, matching L128-L149")
        void theTableTranscribesExactlyTenArms() {
            final List<EvaluateArm> arms = evaluateArmsInSourceOrder().toList();

            // Nine named 88-levels at L62-L70 plus the WHEN OTHER default at L147-L148.
            assertThat(arms).hasSize(10);
            assertThat(arms).extracting(EvaluateArm::armNumber)
                    .as("arms must be listed in source order, 1 through 10")
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            assertThat(arms.get(arms.size() - 1).cobolName())
                    .as("WHEN OTHER must be last - it is the default, not a match")
                    .isEqualTo("WHEN OTHER");
        }

        @Test
        @DisplayName("every transcribed hex token decodes to a CEE facility and the stated severity")
        void everyTranscribedHexTokenDecodesToACeeFacility() {
            // Mechanically re-derives the 88-level VALUEs at L62-L70 from their raw hex, proving the
            // transcription rather than restating it. The eight bytes are SEVERITY(2) + MSG-NO(2) +
            // CASE-SEV-CTL(1) + FACILITY-ID(3).
            final List<EvaluateArm> namedArms = evaluateArmsInSourceOrder()
                    .filter(arm -> !"WHEN OTHER".equals(arm.cobolName()))
                    .toList();
            assertThat(namedArms).as("L62-L70 declares nine named 88-levels").hasSize(9);

            for (EvaluateArm arm : namedArms) {
                assertThat(arm.hexToken())
                        .as("%s must be sixteen hex digits - eight bytes", arm.cobolName())
                        .hasSize(16);
                if (TOKEN_FC_INVALID_DATE.equals(arm.hexToken())) {
                    // The all-zeros success token carries no facility and no control byte.
                    assertThat(decodeSeverity(arm.hexToken())).isZero();
                    assertThat(decodeMessageNumber(arm.hexToken())).isZero();
                    continue;
                }
                // Every named ERROR token: severity 3, control byte X'59', facility X'C3C5C5'.
                assertThat(decodeSeverity(arm.hexToken()))
                        .as("%s severity", arm.cobolName()).isEqualTo(3);
                assertThat(decodeMessageNumber(arm.hexToken()))
                        .as("%s message number", arm.cobolName()).isGreaterThanOrEqualTo(2507);
                assertThat(Integer.parseInt(arm.hexToken().substring(8, 10), 16))
                        .as("%s CASE-SEV-CTL", arm.cobolName()).isEqualTo(CASE_SEV_CTL_ERROR);
                assertThat(arm.hexToken().substring(10, 16))
                        .as("%s FACILITY-ID", arm.cobolName()).isEqualTo(FACILITY_ID_CEE_EBCDIC);
            }

            // X'C3C5C5' really is 'CEE' - decoded, not asserted from a comment. This is what confirms
            // the nine tokens are Language Environment messages.
            final byte[] facility = {(byte) 0xC3, (byte) 0xC5, (byte) 0xC5};
            assertThat(new String(facility, EBCDIC)).isEqualTo(FACILITY_ID_TEXT);
        }

        @Test
        @DisplayName("the nine declared token values are mutually exclusive")
        void theNineDeclaredTokenValuesAreMutuallyExclusive() {
            // This is WHY the WHEN order cannot change an outcome by itself: no two 88-levels can
            // match the same eight bytes, so at most one arm can ever apply.
            final Set<String> distinct = new LinkedHashSet<>(List.of(
                    TOKEN_FC_INVALID_DATE, TOKEN_FC_INSUFFICIENT_DATA, TOKEN_FC_BAD_DATE_VALUE,
                    TOKEN_FC_INVALID_ERA, TOKEN_FC_UNSUPP_RANGE, TOKEN_FC_INVALID_MONTH,
                    TOKEN_FC_BAD_PIC_STRING, TOKEN_FC_NON_NUMERIC_DATA, TOKEN_FC_YEAR_IN_ERA_ZERO));
            assertThat(distinct).hasSize(9);

            // The WHEN OTHER sentinel must be unable to equal any of them, or it would be captured by
            // an earlier arm instead of falling through. Severity 3 with message 0 is a pair no
            // declared VALUE carries.
            assertThat(distinct).doesNotContain(TOKEN_UNENUMERATED_SENTINEL);
            assertThat(decodeSeverity(TOKEN_UNENUMERATED_SENTINEL)).isEqualTo(3);
            assertThat(decodeMessageNumber(TOKEN_UNENUMERATED_SENTINEL)).isZero();
        }
    }

    // =============================================================================================
    // Group 3 - EVALUATE ordering (G30) and both states of every 88-level (G50).
    // =============================================================================================

    /**
     * That the {@code WHEN} sequence at L128-L149 is honoured, with {@code WHEN OTHER} last.
     *
     * <p>Ordering is asserted two ways, because the nine token values are mutually exclusive and so
     * ordering alone cannot change an outcome. First <em>exhaustively</em>: each of the nine distinct
     * tokens yields <strong>its own</strong> result text, which proves no earlier arm shadows a later
     * one. Then by <em>default</em>: an unenumerated token falls through to {@code 'Date is invalid'}
     * and nothing else, which proves {@code WHEN OTHER} really is the last resort rather than an arm
     * that competes.
     */
    @Nested
    @DisplayName("EVALUATE ordering and 88-level truth states")
    class EvaluateOrdering {

        @Test
        @DisplayName("the ten arms yield ten distinct result texts, so no arm shadows another")
        void theTenArmsYieldTenDistinctResultTexts() {
            final DateUtilityJob service = newService();
            final Set<String> observed = new LinkedHashSet<>();
            final Set<String> expected = new LinkedHashSet<>();

            for (EvaluateArm arm : evaluateArmsInSourceOrder().toList()) {
                observed.add(service.validateDate(arm.inputDate(), arm.pictureMask()).result());
                expected.add(arm.expectedResult());
            }

            // Ten arms, ten different texts. Had an earlier WHEN captured a later arm's token, two
            // arms would collapse onto one text and this set would be short.
            assertThat(expected).as("L128-L148 declares ten distinct texts").hasSize(10);
            assertThat(observed).as("each arm must produce its own text, in source order")
                    .hasSize(10)
                    .containsExactlyElementsOf(expected);
        }

        @ParameterizedTest(name = "[{index}] {0} must not answer with another arm''s text")
        @MethodSource("com.vsergeychik.carddemo.util.DateUtilityJobTest#evaluateArmsInSourceOrder")
        @DisplayName("G50 - every 88-level is driven both true and false")
        void every88LevelIsDrivenBothTrueAndFalse(EvaluateArm arm) {
            final DateUtilityJob service = newService();

            // The TRUE state: the condition matches its own token and stores its own text.
            assertThat(service.validateDate(arm.inputDate(), arm.pictureMask()).result())
                    .as("%s must be TRUE for its own token", arm.cobolName())
                    .isEqualTo(arm.expectedResult());

            // The FALSE state: for every OTHER arm's input this condition must not match, so this
            // arm's text must not appear. Driving all nine others gives far more than the required
            // "at least one other token" and makes the exclusion exhaustive.
            for (EvaluateArm other : evaluateArmsInSourceOrder().toList()) {
                if (other.armNumber() == arm.armNumber()) {
                    continue;
                }
                assertThat(service.validateDate(other.inputDate(), other.pictureMask()).result())
                        .as("%s must be FALSE for the input that drives %s",
                                arm.cobolName(), other.cobolName())
                        .isNotEqualTo(arm.expectedResult())
                        .isEqualTo(other.expectedResult());
            }
        }

        @Test
        @DisplayName("WHEN OTHER is the default: an unenumerated token yields 'Date is invalid' only")
        void whenOtherIsTheDefaultAndYieldsDateIsInvalidOnly() {
            // A literal-delimiter mismatch: the picture demands '-' at offsets 4 and 7, the input
            // supplies '/'. No documented CEEDAYS condition describes that, so the feedback token is
            // outside the nine 88-levels and L147-L148 must catch it.
            final DateValidationResult result = newService().validateDate("2022/07/18", MASK_HYPHENATED);

            assertThat(result.result())
                    .as("the WHEN OTHER arm at L147-L148")
                    .isEqualTo(RESULT_DATE_IS_INVALID);

            // ...and nothing else. It must not have been captured by any of the nine named arms.
            assertThat(result.result()).isNotIn(
                    RESULT_DATE_IS_VALID, RESULT_INSUFFICIENT, RESULT_DATEVALUE_ERROR,
                    RESULT_INVALID_ERA, RESULT_UNSUPP_RANGE, RESULT_INVALID_MONTH,
                    RESULT_BAD_PIC_STRING, RESULT_NONNUMERIC_DATA, RESULT_YEAR_IN_ERA_ZERO);

            // The severity and message number on this path are whatever the validator returned and are
            // never forced to a named value, so the callers see '0003'/'0000' and reject the date -
            // the right outcome for input the picture string never described.
            assertThat(result.severityCode()).isEqualTo(asPicNine4(3));
            assertThat(result.messageNumber()).isEqualTo(asPicNine4(0));
            assertThat(result.returnCode()).isEqualTo(3);
            assertFillersAreIntact(result.message());
        }

        @Test
        @DisplayName("a second, independent WHEN OTHER trigger reaches the same arm")
        void aSecondIndependentWhenOtherTriggerReachesTheSameArm() {
            // Leftover non-blank input: '<CC>YYMMDD' describes only eight input characters, so the
            // final two must be blank. Supplying '99' there is neither missing data nor a bad value,
            // so once again no documented code fits and WHEN OTHER catches it. Two structurally
            // different routes to the same arm guard against the arm being reachable by accident.
            final DateValidationResult result = newService().validateDate("AD22071899", "<CC>YYMMDD");

            assertThat(result.result()).isEqualTo(RESULT_DATE_IS_INVALID);
            assertThat(result.returnCode()).isEqualTo(3);
        }

        @Test
        @DisplayName("the success arm is FC-INVALID-DATE - the inverted name is preserved, not flipped")
        void theSuccessArmIsTheInvertedlyNamedFcInvalidDate() {
            // THE INVERSION TRAP, asserted head-on. 88 FC-INVALID-DATE VALUE X'0000000000000000'
            // (L62) is the ALL-ZEROS SUCCESS token, and L129-L130 maps it to 'Date is valid'. The
            // condition NAME contradicts its MEANING. A naive expectation inverts this and then every
            // other row still looks plausible, so the suite would prove the opposite of the truth.
            assertThat(decodeSeverity(TOKEN_FC_INVALID_DATE))
                    .as("FC-INVALID-DATE carries severity 0 - CEE000, a SUCCESSFUL conversion")
                    .isZero();
            assertThat(decodeMessageNumber(TOKEN_FC_INVALID_DATE)).isZero();

            final DateValidationResult result = newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            assertThat(result.result())
                    .as("the arm named 'INVALID' is the one that reports the date VALID")
                    .isEqualTo(RESULT_DATE_IS_VALID)
                    .isNotEqualTo(RESULT_DATE_IS_INVALID);
            assertThat(result.returnCode()).isZero();
        }
    }

    // =============================================================================================
    // Group 4 - the two REDEFINES round-trips (G34).
    // =============================================================================================

    /**
     * The two {@code REDEFINES} pairs at L43/L44 and L46/L47: two typed accessors over <em>one</em>
     * backing span.
     *
     * <p>This round-trip is not academic. The copybook call site tests the <strong>numeric</strong>
     * view ({@code IF WS-SEVERITY-N = 0}, {@code CSUTLDPY.cpy} L298) while both program call sites test
     * the <strong>alphanumeric</strong> view ({@code IF CSUTLDTC-RESULT-SEV-CD = '0000'}). The two
     * views agreeing is therefore a genuine correctness requirement, not a formality.
     */
    @Nested
    @DisplayName("REDEFINES overlays - L43/L44 and L46/L47")
    class RedefinesRoundTrips {

        @Test
        @DisplayName("a write through the numeric view is visible through the character view")
        void aWriteThroughTheNumericViewIsVisibleThroughTheCharacterView() {
            final FixedWidthCodec codec = new FixedWidthCodec(MESSAGE_CHARSET);
            final FieldSpan character =
                    FieldSpan.alphanumeric("WS-SEVERITY", SEVERITY_OFFSET, SEVERITY_LENGTH);
            final FieldSpan numeric =
                    character.redefinedAs("WS-SEVERITY-N", PictureKind.UNSIGNED_NUMERIC);
            final FixedWidthRecord record =
                    FixedWidthRecord.forLayout(RecordLayout.of(SEVERITY_LENGTH, character, numeric),
                            MESSAGE_CHARSET);

            // The two descriptors must address exactly the same bytes - that is what REDEFINES means.
            assertThat(numeric.offset()).isEqualTo(character.offset());
            assertThat(numeric.length()).isEqualTo(character.length());
            assertThat(numeric.redefinition()).isTrue();
            assertThat(character.redefinition()).isFalse();

            // L123 writes numerically; the callers read the same bytes as characters. Round-trip every
            // severity the program can produce, plus the message numbers, through both views.
            for (int value : new int[] {0, 3, 2507, 2508, 2509, 2513, 2517, 2518, 2520, 2521}) {
                codec.writePic9(record, numeric, value);

                final String characterImage = codec.readPicX(record, character);
                assertThat(characterImage)
                        .as("PIC 9(4) left-zero-fills, so %d must render as four digits", value)
                        .hasSize(SEVERITY_LENGTH)
                        .isEqualTo(asPicNine4(value));

                // ...and parsing the character image back must recover the original number.
                assertThat(codec.readPic9AsInt(record, numeric)).isEqualTo(value);
                assertThat(Integer.parseInt(characterImage)).isEqualTo(value);
            }
        }

        @Test
        @DisplayName("PIC 9(4) zero-fills rather than space-padding, so 3 becomes '0003'")
        void picNineFourZeroFillsRatherThanSpacePadding() {
            // The single most likely REDEFINES mistake: rendering the severity through the CHARACTER
            // view's PIC X rules, which would right-space-pad to '3   ', or through a plain integer
            // conversion, which would give '3'. Both would break the callers' '0000' comparison.
            assertThat(asPicNine4(3)).isEqualTo("0003").isNotEqualTo("3").isNotEqualTo("   3");
            assertThat(asPicNine4(0)).isEqualTo("0000");
            assertThat(asPicNine4(2513)).isEqualTo("2513");

            final DateValidationResult error = newService().validateDate("2022-13-18", MASK_HYPHENATED);
            assertThat(error.severityCode()).isEqualTo("0003");
            assertThat(error.messageNumber()).isEqualTo("2517");

            final DateValidationResult success = newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            assertThat(success.severityCode()).isEqualTo("0000");
            assertThat(success.messageNumber()).isEqualTo("0000");
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.vsergeychik.carddemo.util.DateUtilityJobTest#evaluateArmsInSourceOrder")
        @DisplayName("both overlay views agree on every arm the service can produce")
        void bothOverlayViewsAgreeOnEveryArm(EvaluateArm arm) {
            final DateValidationResult result =
                    newService().validateDate(arm.inputDate(), arm.pictureMask());

            // The numeric view (RETURN-CODE, from WS-SEVERITY-N at L98) and the character view
            // (WS-SEVERITY at [0,4)) are two readings of ONE span, so they can never disagree.
            assertThat(Integer.parseInt(result.severityCode()))
                    .as("WS-SEVERITY parsed numerically must equal WS-SEVERITY-N")
                    .isEqualTo(result.returnCode());
            assertThat(asPicNine4(result.returnCode())).isEqualTo(result.severityCode());

            // The message-number overlay is a four-digit image of a number in both views too.
            assertThat(Integer.parseInt(result.messageNumber()))
                    .isEqualTo(decodeMessageNumber(arm.hexToken()));
        }
    }

    // =============================================================================================
    // Group 5 - RETURN-CODE, and the absence of any abend.
    // =============================================================================================

    /**
     * {@code MOVE WS-SEVERITY-N TO RETURN-CODE} at L98, and the fact that nothing here ever abends.
     *
     * <p>L100 is {@code EXIT PROGRAM}, an ordinary return; the {@code GOBACK} at L101 is commented out
     * in the source. There is no observable difference between the two to assert, so it is recorded
     * here rather than tested.
     */
    @Nested
    @DisplayName("RETURN-CODE at L98, and the absence of an abend")
    class ReturnCodeAndAbend {

        @Test
        @DisplayName("the return code is the severity: 0 for the valid token, 3 for all eight errors")
        void theReturnCodeIsTheSeverity() {
            final DateUtilityJob service = newService();

            // Arm 1 is the only zero.
            assertThat(service.validateDate(SAMPLE_DATE, MASK_HYPHENATED).returnCode())
                    .as("FC-INVALID-DATE is the SUCCESS token, so RETURN-CODE is 0")
                    .isZero();

            // Arms 2 through 9 all carry severity 3, and so does the WHEN OTHER sentinel.
            for (EvaluateArm arm : evaluateArmsInSourceOrder().toList()) {
                if (arm.armNumber() == 1) {
                    continue;
                }
                assertThat(service.validateDate(arm.inputDate(), arm.pictureMask()).returnCode())
                        .as("%s carries severity 3", arm.cobolName())
                        .isEqualTo(3);
            }
        }

        @Test
        @DisplayName("return code 3 sits outside the batch {0,4,8,12} set - documented, not remapped")
        void returnCodeThreeSitsOutsideTheBatchAbendCodeSet() {
            // DOCUMENT, DO NOT FIX. The batch programs set RETURN-CODE from an APPL-RESULT constant
            // drawn from {0, 4, 8, 12}; CSUTLDTC instead moves the raw SEVERITY, which is 3. That is an
            // inherited property of the source, and remapping 3 to 4, 8 or 12 to make it look
            // conventional would be a behaviour change. It is asserted here so the divergence is
            // visible and locked, rather than left as a surprise for a later reader.
            final DateValidationResult error = newService().validateDate("2022-13-18", MASK_HYPHENATED);

            assertThat(error.returnCode())
                    .as("CSUTLDTC moves the SEVERITY, not an APPL-RESULT constant")
                    .isEqualTo(3)
                    .isNotIn(0, 4, 8, 12);

            // The only two return codes this program can ever produce.
            final Set<Integer> observed = new LinkedHashSet<>();
            final DateUtilityJob service = newService();
            for (EvaluateArm arm : evaluateArmsInSourceOrder().toList()) {
                observed.add(service.validateDate(arm.inputDate(), arm.pictureMask()).returnCode());
            }
            assertThat(observed).containsExactlyInAnyOrder(0, 3);
        }

        @Test
        @DisplayName("G35 is N/A: no AbendException is ever raised, for any of the ten arms")
        void noAbendIsEverRaisedForAnyEvaluateArm() {
            // CSUTLDTC is not one of the nine CALL 'CEE3ABD' sites, so no abend can arise. Stated as a
            // POSITIVE assertion rather than as a silent omission, so a future change that introduced
            // an abend path here would fail rather than pass unnoticed.
            final DateUtilityJob service = newService();
            for (EvaluateArm arm : evaluateArmsInSourceOrder().toList()) {
                assertThatCode(() -> service.validateDate(arm.inputDate(), arm.pictureMask()))
                        .as("%s must return normally - CSUTLDTC never abends", arm.cobolName())
                        .doesNotThrowAnyException();
            }
        }
    }

    // =============================================================================================
    // Group 6 - the L122 group-move trap. The single most likely place a plausible implementation
    // silently diverges.
    // =============================================================================================

    /**
     * {@code MOVE WS-DATE-TO-TEST TO WS-DATE} at L122 - a <strong>group</strong> move into an
     * elementary alphanumeric item.
     *
     * <p>L107-L108 briefly puts the clean ten-character date into {@code WS-DATE}. L122 then overwrites
     * it with the twelve-byte storage image of {@code WS-DATE-TO-TEST} truncated to ten, so the
     * reported "TstDate:" span holds a two-byte binary halfword followed by only the <em>first eight</em>
     * characters of the date. The last two characters are lost on every single call.
     */
    @Nested
    @DisplayName("the L122 group-move defect, reproduced deliberately")
    class GroupMoveTrap {

        @Test
        @DisplayName("WS-DATE holds a big-endian halfword then only the first eight date characters")
        void wsDateHoldsAHalfwordThenOnlyTheFirstEightDateCharacters() {
            final DateValidationResult result = newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            final byte[] message = result.messageBytes();

            // Compared as BYTES, never as a String: 0x00 and 0x0A are non-printable, 0x0A is in fact
            // the ASCII newline, and their character meaning differs between US-ASCII and IBM037.
            final byte[] actual = new byte[DATE_LENGTH];
            System.arraycopy(message, DATE_OFFSET, actual, 0, DATE_LENGTH);

            // The expectation is DERIVED FROM GROUP-MOVE SEMANTICS, never from the input date: a
            // big-endian halfword of the DECLARED width ten, then ten-minus-two surviving text bytes.
            assertThat(actual)
                    .as("[45,55) = 00 0A '2' '0' '2' '2' '-' '0' '7' '-'")
                    .isEqualTo(expectedGroupMoveImage(SAMPLE_DATE));

            // Spelled out byte by byte as well, so the intent survives a refactor of the helper.
            assertThat(actual[0]).as("halfword high-order byte - BIG-endian").isEqualTo((byte) 0x00);
            assertThat(actual[1]).as("halfword low-order byte - decimal 10").isEqualTo((byte) 0x0A);
            assertThat(new String(actual, VSTRING_LENGTH_BYTES, SURVIVING_TEXT_BYTES, MESSAGE_CHARSET))
                    .as("only the first eight characters of '2022-07-18' survive")
                    .isEqualTo("2022-07-")
                    .hasSize(SURVIVING_TEXT_BYTES);

            // The two characters the move discards must NOT appear at the end of the span.
            assertThat(new String(actual, MESSAGE_CHARSET))
                    .as("the final '18' of the input date is lost, not relocated")
                    .doesNotEndWith("18");
        }

        @Test
        @DisplayName("a little-endian halfword would be 0x0A 0x00 and is explicitly not what is stored")
        void theHalfwordIsBigEndianNotLittleEndian() {
            final byte[] image = expectedGroupMoveImage(SAMPLE_DATE);

            // Vstring-length is PIC S9(4) BINARY on a big-endian machine, so the high-order byte comes
            // first. A little-endian assumption yields 0x0A 0x00 and is wrong; asserting the negative
            // makes the byte order a deliberate decision rather than an accident of the platform.
            assertThat(new byte[] {image[0], image[1]}).isEqualTo(new byte[] {0x00, 0x0A});
            assertThat(new byte[] {image[0], image[1]}).isNotEqualTo(new byte[] {0x0A, 0x00});
        }

        @ParameterizedTest(name = "[{index}] date=''{0}''")
        @CsvSource({
            "2022-07-18",
            "1999-01-01",
            "2000-02-29",
            "0000-07-18",
            "20XX-07-18",
            "2022/07/18",
        })
        @DisplayName("the halfword prefix is invariant for every input, valid or not")
        void theHalfwordPrefixIsInvariantForEveryInput(String inputDate) {
            // The prefix is invariant because L105-L106 takes the length from LENGTH OF LS-DATE - the
            // DECLARED width of PIC X(10) - which is always ten regardless of what the field contains.
            // That is exactly what makes this defect deterministic rather than data-dependent.
            final byte[] message = newService().validateDate(inputDate, MASK_HYPHENATED).messageBytes();

            assertThat(message[DATE_OFFSET]).isEqualTo((byte) 0x00);
            assertThat(message[DATE_OFFSET + 1]).isEqualTo((byte) 0x0A);

            final byte[] span = new byte[DATE_LENGTH];
            System.arraycopy(message, DATE_OFFSET, span, 0, DATE_LENGTH);
            assertThat(span).isEqualTo(expectedGroupMoveImage(movePicX(inputDate, DATE_LENGTH)));
        }

        @Test
        @DisplayName("the date span is always corrupted while the mask span never is")
        void theDateSpanIsAlwaysCorruptedWhileTheMaskSpanNeverIs() {
            // THE ASYMMETRY THAT CATCHES COPY-PASTE IMPLEMENTATIONS, asserted in ONE test so an
            // implementation that treats the two identically fails immediately. Both spans are
            // PIC X(10) and both receive a multi-receiver MOVE, but only WS-DATE is group-overwritten
            // afterwards at L122; WS-DATE-FMT is set once at L111-L113 and never touched again.
            final DateValidationResult result = newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            final byte[] message = result.messageBytes();

            final byte[] dateSpan = new byte[DATE_LENGTH];
            System.arraycopy(message, DATE_OFFSET, dateSpan, 0, DATE_LENGTH);
            final byte[] maskSpan = new byte[DATE_LENGTH];
            System.arraycopy(message, DATE_FMT_OFFSET, maskSpan, 0, DATE_LENGTH);

            // WS-DATE at [45,55): CORRUPTED - it does not hold the input date.
            assertThat(dateSpan)
                    .as("WS-DATE is overwritten by the L122 group move")
                    .isNotEqualTo(SAMPLE_DATE.getBytes(MESSAGE_CHARSET))
                    .isEqualTo(expectedGroupMoveImage(SAMPLE_DATE));

            // WS-DATE-FMT at [66,76): CLEAN - it holds the mask verbatim.
            assertThat(maskSpan)
                    .as("WS-DATE-FMT is never group-overwritten")
                    .isEqualTo(MASK_HYPHENATED.getBytes(MESSAGE_CHARSET));
            assertThat(result.message().substring(DATE_FMT_OFFSET, DATE_FMT_OFFSET + DATE_LENGTH))
                    .isEqualTo(MASK_HYPHENATED);

            // Same declared shape, opposite outcome.
            assertThat(dateSpan).hasSameSizeAs(maskSpan);
            assertThat(dateSpan).isNotEqualTo(maskSpan);
        }

        @Test
        @DisplayName("OUTPUT-LILLIAN never leaks into the eighty bytes")
        void outputLillianNeverLeaksIntoTheEightyBytes() {
            // 01 OUTPUT-LILLIAN PIC S9(9) BINARY (L41) is zeroed at L114 and filled by the call at
            // L119, but is NEVER moved into WS-MESSAGE and never returned. It is computed and then
            // discarded, and it stays that way: surfacing it would add a capability the COBOL never
            // offered, which is a new feature rather than a migration.
            //
            // Proved by reconstructing the complete eighty bytes from the thirteen declared spans and
            // requiring exact equality. If any additional value - a Lillian day count or anything else
            // - leaked into the record, this comparison would fail, because every byte is accounted
            // for by the table.
            final DateValidationResult result = newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED);

            final FixedWidthRecord expected =
                    new FixedWidthRecord(MESSAGE_LENGTH, MESSAGE_CHARSET);
            expected.writeString(SEVERITY_OFFSET, SEVERITY_LENGTH, asPicNine4(0));
            expected.writeString(MESG_CODE_FILLER_OFFSET, MESG_CODE_FILLER_LENGTH,
                    MESG_CODE_FILLER_IMAGE);
            expected.writeString(MSG_NO_OFFSET, MSG_NO_LENGTH, asPicNine4(0));
            expected.writeString(FILLER_AFTER_MSG_NO_OFFSET, SINGLE_SPACE_FILLER_LENGTH, ONE_SPACE);
            expected.writeString(RESULT_OFFSET, RESULT_LENGTH, RESULT_DATE_IS_VALID);
            expected.writeString(FILLER_AFTER_RESULT_OFFSET, SINGLE_SPACE_FILLER_LENGTH, ONE_SPACE);
            expected.writeString(TST_DATE_FILLER_OFFSET, TST_DATE_FILLER_LENGTH,
                    TST_DATE_FILLER_IMAGE);
            expected.writeBytes(DATE_OFFSET, expectedGroupMoveImage(SAMPLE_DATE));
            expected.writeString(FILLER_AFTER_DATE_OFFSET, SINGLE_SPACE_FILLER_LENGTH, ONE_SPACE);
            expected.writeString(MASK_USED_FILLER_OFFSET, MASK_USED_FILLER_LENGTH,
                    MASK_USED_FILLER_IMAGE);
            expected.writeString(DATE_FMT_OFFSET, DATE_LENGTH, MASK_HYPHENATED);
            expected.writeString(FILLER_AFTER_FMT_OFFSET, SINGLE_SPACE_FILLER_LENGTH, ONE_SPACE);
            expected.writeString(TRAILING_FILLER_OFFSET, TRAILING_FILLER_LENGTH, THREE_SPACES);

            assertThat(result.messageBytes())
                    .as("every one of the eighty bytes is accounted for by the thirteen spans, so "
                            + "nothing - least of all OUTPUT-LILLIAN - can have leaked in")
                    .isEqualTo(expected.toByteArray());

            // The only non-printable bytes in the whole record are the two the group move planted.
            final byte[] actual = result.messageBytes();
            for (int offset = 0; offset < actual.length; offset++) {
                if (offset == DATE_OFFSET || offset == DATE_OFFSET + 1) {
                    continue;
                }
                assertThat(actual[offset])
                        .as("byte %d must be a printable single-byte character", offset)
                        .isGreaterThanOrEqualTo((byte) 0x20);
            }
        }
    }

    // =============================================================================================
    // Group 7 - the two projections of the same eighty bytes, and the three acceptance rules across
    // the five call sites.
    // =============================================================================================

    /**
     * The two distinct projections of one eighty-byte result, and the acceptance policy of each caller.
     *
     * <ul>
     *   <li><strong>Producer projection - thirteen fields.</strong>
     *       {@code app/cpy/CSUTLDWY.cpy} L60-L85 declares {@code WS-DATE-VALIDATION-RESULT} as a
     *       byte-for-byte mirror of {@code CSUTLDTC}'s internal {@code WS-MESSAGE}: the same thirteen
     *       items, the same widths, the same three literals, the same two {@code REDEFINES} pairs and
     *       the same eighty-byte total.</li>
     *   <li><strong>Caller projection - four fields.</strong> {@code CORPT00C.cbl} L129-L136 and
     *       {@code COTRN02C.cbl} L62-L69 declare a genuinely different split: {@code X(04)} +
     *       {@code X(11)} + {@code X(04)} + {@code X(61)}, where the last span swallows everything
     *       from offset 19 to the end.</li>
     * </ul>
     */
    @Nested
    @DisplayName("both 80-byte projections and the five call sites' acceptance rules")
    class CallerProjections {

        @Test
        @DisplayName("the thirteen-field producer projection decodes every span at its own offset")
        void theThirteenFieldProducerProjectionDecodesEverySpan() {
            final DateValidationResult result = newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            final String message = result.message();

            // This is the CSUTLDWY mirror, read span by span at absolute offsets.
            assertThat(message.substring(SEVERITY_OFFSET, SEVERITY_OFFSET + SEVERITY_LENGTH))
                    .isEqualTo("0000");
            assertThat(message.substring(MSG_NO_OFFSET, MSG_NO_OFFSET + MSG_NO_LENGTH))
                    .isEqualTo("0000");
            assertThat(message.substring(RESULT_OFFSET, RESULT_OFFSET + RESULT_LENGTH))
                    .isEqualTo(RESULT_DATE_IS_VALID);
            assertThat(message.substring(DATE_FMT_OFFSET, DATE_FMT_OFFSET + DATE_LENGTH))
                    .isEqualTo(MASK_HYPHENATED);
            assertFillersAreIntact(message);

            // Each accessor must agree with its own positional slice - the object view and the byte
            // view are two readings of one record, so they cannot disagree.
            assertThat(result.severityCode())
                    .isEqualTo(message.substring(SEVERITY_OFFSET, SEVERITY_OFFSET + SEVERITY_LENGTH));
            assertThat(result.messageNumber())
                    .isEqualTo(message.substring(MSG_NO_OFFSET, MSG_NO_OFFSET + MSG_NO_LENGTH));
            assertThat(result.result())
                    .isEqualTo(message.substring(RESULT_OFFSET, RESULT_OFFSET + RESULT_LENGTH));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.vsergeychik.carddemo.util.DateUtilityJobTest#evaluateArmsInSourceOrder")
        @DisplayName("the four-field caller projection reads the same severity and message number")
        void theFourFieldCallerProjectionReadsTheSameValues(EvaluateArm arm) {
            final DateValidationResult result =
                    newService().validateDate(arm.inputDate(), arm.pictureMask());
            final String message = result.message();

            // Slice the SAME eighty bytes the caller's way: 4 + 11 + 4 + 61.
            final String callerSevCd = message.substring(CALLER_SEV_CD_OFFSET,
                    CALLER_SEV_CD_OFFSET + CALLER_SEV_CD_LENGTH);
            final String callerFiller = message.substring(CALLER_FILLER_OFFSET,
                    CALLER_FILLER_OFFSET + CALLER_FILLER_LENGTH);
            final String callerMsgNum = message.substring(CALLER_MSG_NUM_OFFSET,
                    CALLER_MSG_NUM_OFFSET + CALLER_MSG_NUM_LENGTH);
            final String callerMsg = message.substring(CALLER_MSG_OFFSET,
                    CALLER_MSG_OFFSET + CALLER_MSG_LENGTH);

            // A different split must still yield the same two values the callers compare.
            assertThat(callerSevCd).isEqualTo(arm.expectedSeverityCode());
            assertThat(callerMsgNum).isEqualTo(arm.expectedMessageNumber());

            // The caller's anonymous FILLER X(11) happens to span the 'Mesg Code: ' text, which is why
            // the caller never has to know that literal exists.
            assertThat(callerFiller).isEqualTo(MESG_CODE_FILLER_IMAGE);

            // CSUTLDTC-RESULT-MSG PIC X(61) spans [19,80) - it starts at the blank filler after the
            // message number and runs to the very end of the record.
            assertThat(CALLER_MSG_OFFSET).isEqualTo(19);
            assertThat(CALLER_MSG_OFFSET + CALLER_MSG_LENGTH).isEqualTo(MESSAGE_LENGTH);
            assertThat(callerMsg).hasSize(CALLER_MSG_LENGTH);
            assertThat(callerMsg).startsWith(ONE_SPACE + arm.expectedResult());
            assertThat(callerMsg).endsWith(THREE_SPACES);

            // Both projections must agree with the object accessors too.
            assertThat(callerSevCd).isEqualTo(result.severityCode());
            assertThat(callerMsgNum).isEqualTo(result.messageNumber());
        }

        @Test
        @DisplayName("the service never special-cases 2513 - accept/reject policy belongs to the caller")
        void theServiceNeverSpecialCasesMessage2513() {
            // The 2513 row must be structurally INDISTINGUISHABLE from the other error rows: same
            // severity, same widths, same fillers, same return code. If the service tried to be helpful
            // and treated 2513 as acceptable, the copybook call site - which rejects it - would silently
            // change behaviour.
            final DateValidationResult unsuppRange =
                    newService().validateDate("1500-07-18", MASK_HYPHENATED);
            final DateValidationResult otherError =
                    newService().validateDate("2022-13-18", MASK_HYPHENATED);

            assertThat(unsuppRange.messageNumber()).isEqualTo(TOLERATED_MESSAGE_NUMBER);
            assertThat(unsuppRange.severityCode())
                    .as("2513 carries severity 3 exactly like every other error")
                    .isEqualTo(otherError.severityCode());
            assertThat(unsuppRange.returnCode())
                    .as("2513 yields the same RETURN-CODE as every other error")
                    .isEqualTo(otherError.returnCode());
            assertThat(unsuppRange.message()).hasSameSizeAs(otherError.message());
            assertFillersAreIntact(unsuppRange.message());

            // The two caller predicates over the very same result, which is the whole point: one
            // accepts it and the other rejects it, so the service must not decide.
            assertThat(ACCEPTED_SEVERITY_CODE.equals(unsuppRange.severityCode()))
                    .as("'0000'-equality is FALSE for 2513").isFalse();
            assertThat(TOLERATED_MESSAGE_NUMBER.equals(unsuppRange.messageNumber()))
                    .as("'2513'-equality is TRUE for 2513").isTrue();
        }

        @Test
        @DisplayName("the program callers tolerate 2513 while the copybook caller does not")
        void theProgramCallersTolerate2513WhileTheCopybookCallerDoesNot() {
            final DateValidationResult unsuppRange =
                    newService().validateDate("1500-07-18", MASK_HYPHENATED);

            // CORPT00C L396-L406 / L416-L426 and COTRN02C L397-L407 / L417-L427: alphanumeric, and the
            // ELSE-IF lets 2513 through.
            assertThat(programCallerAccepts(unsuppRange))
                    .as("CORPT00C and COTRN02C accept 2513 in spite of severity 3")
                    .isTrue();

            // CSUTLDPY.cpy L298 IF WS-SEVERITY-N = 0: NUMERIC, and it tolerates nothing non-zero.
            assertThat(copybookCallerAccepts(unsuppRange))
                    .as("CSUTLDPY rejects 2513, because its test is on the severity alone")
                    .isFalse();

            // The two rules genuinely disagree about this one message number - and agree everywhere
            // else, which is what makes 2513 the single interesting value in the contract.
            final DateUtilityJob service = newService();
            for (EvaluateArm arm : evaluateArmsInSourceOrder().toList()) {
                final DateValidationResult result =
                        service.validateDate(arm.inputDate(), arm.pictureMask());
                final boolean disagreement =
                        programCallerAccepts(result) != copybookCallerAccepts(result);
                assertThat(disagreement)
                        .as("the two acceptance rules may differ ONLY on message 2513, not on %s",
                                arm.cobolName())
                        .isEqualTo(TOLERATED_MESSAGE_NUMBER.equals(result.messageNumber()));
            }
        }

        @Test
        @DisplayName("both callers accept the valid token and reject every non-2513 error")
        void bothCallersAcceptTheValidTokenAndRejectEveryNon2513Error() {
            final DateUtilityJob service = newService();

            final DateValidationResult valid = service.validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            assertThat(programCallerAccepts(valid)).isTrue();
            assertThat(copybookCallerAccepts(valid)).isTrue();

            for (EvaluateArm arm : evaluateArmsInSourceOrder().toList()) {
                if (arm.armNumber() == 1 || arm.armNumber() == 5) {
                    continue; // arm 1 is the success token; arm 5 is the tolerated 2513.
                }
                final DateValidationResult error =
                        service.validateDate(arm.inputDate(), arm.pictureMask());
                assertThat(programCallerAccepts(error))
                        .as("%s must be rejected by the program callers", arm.cobolName()).isFalse();
                assertThat(copybookCallerAccepts(error))
                        .as("%s must be rejected by the copybook caller", arm.cobolName()).isFalse();
            }
        }

        @Test
        @DisplayName("the four caller rejection texts are verbatim")
        void theFourCallerRejectionTextsAreVerbatim() {
            // Asserted as TEXT only, so a later reword cannot slip through unnoticed. Nothing here
            // asserts how those callers are wired - that belongs to the transaction and account test
            // packages, not to this one.
            assertThat(ERROR_TEXT_START_DATE).isEqualTo("Start Date - Not a valid date...");
            assertThat(ERROR_TEXT_END_DATE).isEqualTo("End Date - Not a valid date...");
            assertThat(ERROR_TEXT_ORIG_DATE).isEqualTo("Orig Date - Not a valid date...");
            assertThat(ERROR_TEXT_PROC_DATE).isEqualTo("Proc Date - Not a valid date...");

            // All four share the same suffix and differ only in the field they name, which is what
            // makes a copy-paste slip between them plausible and worth pinning.
            final List<String> texts = List.of(ERROR_TEXT_START_DATE, ERROR_TEXT_END_DATE,
                    ERROR_TEXT_ORIG_DATE, ERROR_TEXT_PROC_DATE);
            assertThat(texts).allSatisfy(text -> assertThat(text).endsWith(" - Not a valid date..."));
            assertThat(new LinkedHashSet<>(texts)).as("all four must be distinct").hasSize(4);
        }

        @Test
        @DisplayName("both real masks render at their declared ten-byte width")
        void bothRealMasksRenderAtTheirDeclaredTenByteWidth() {
            // 'YYYY-MM-DD' - PIC X(10) at CORPT00C L72 and COTRN02C L60. An exact fit.
            final DateValidationResult hyphenated =
                    newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            assertThat(hyphenated.message().substring(DATE_FMT_OFFSET, DATE_FMT_OFFSET + DATE_LENGTH))
                    .isEqualTo(MASK_HYPHENATED)
                    .hasSize(DATE_LENGTH);

            // 'YYYYMMDD' - PIC X(08) in CSUTLDWY L58-L59, re-established at CSUTLDPY L291 before the
            // FIFTH call at L293. Only eight characters against an X(10) formal, so the PIC X move
            // right-space-pads it. DOCUMENT, DO NOT FIX: in COBOL BY REFERENCE the callee would read
            // two bytes of ADJACENT STORAGE there. That artefact is not expressible in Java and is not
            // part of the typed contract, so the ruling is ordinary right-space-padding - and NO test
            // here reads adjacent memory.
            final DateValidationResult compact = newService().validateDate("20220718  ", MASK_COMPACT);
            assertThat(compact.message().substring(DATE_FMT_OFFSET, DATE_FMT_OFFSET + DATE_LENGTH))
                    .isEqualTo(MASK_COMPACT_IMAGE)
                    .hasSize(DATE_LENGTH);
            assertThat(MASK_COMPACT_IMAGE).isEqualTo(movePicX(MASK_COMPACT, DATE_LENGTH));
            assertThat(MASK_COMPACT).hasSize(8);

            // The compact mask really does validate a date, so the padding is benign rather than merely
            // cosmetic - the two trailing pad spaces become literal delimiters the input must match.
            assertThat(compact.severityCode()).isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(compact.result()).isEqualTo(RESULT_DATE_IS_VALID);
        }
    }

    // =============================================================================================
    // Group 8 - statelessness, and safe sharing by three injectors.
    // =============================================================================================

    /**
     * That the service holds no mutable state and is therefore safe to share.
     *
     * <p>This is a real requirement rather than ceremony: {@code ReportRequestController},
     * {@code TransactionViewController} and {@code account.AccountDateValidator} - the third injector,
     * reached through {@code CSUTLDPY.cpy} L293 - all share one singleton. Every COBOL
     * {@code WORKING-STORAGE} item here ({@code WS-MESSAGE}, {@code WS-DATE-TO-TEST},
     * {@code WS-DATE-FORMAT}, {@code FEEDBACK-CODE}, {@code OUTPUT-LILLIAN}) must therefore be a
     * per-invocation local, never a field.
     */
    @Nested
    @DisplayName("statelessness - three collaborators share one singleton")
    class Statelessness {

        @Test
        @DisplayName("repeated calls on one instance depend only on their own arguments")
        void repeatedCallsOnOneInstanceDependOnlyOnTheirOwnArguments() {
            final DateUtilityJob shared = newService();
            final List<String> firstPass = new ArrayList<>();
            final List<String> secondPass = new ArrayList<>();

            for (EvaluateArm arm : evaluateArmsInSourceOrder().toList()) {
                firstPass.add(shared.validateDate(arm.inputDate(), arm.pictureMask()).message());
            }
            // Drive every arm again through the very same instance, in the same order.
            for (EvaluateArm arm : evaluateArmsInSourceOrder().toList()) {
                secondPass.add(shared.validateDate(arm.inputDate(), arm.pictureMask()).message());
            }

            assertThat(secondPass)
                    .as("a second pass over one instance must reproduce the first exactly")
                    .containsExactlyElementsOf(firstPass);
        }

        @Test
        @DisplayName("interleaved calls never bleed a stale result, severity or mask through")
        void interleavedCallsNeverBleedThrough() {
            final DateUtilityJob shared = newService();

            // Two input sets with DIFFERENT outcomes and DIFFERENT masks, deliberately interleaved. A
            // retained WS-RESULT, WS-SEVERITY or WS-DATE-FMT would show up as set A answering with set
            // B's values on the second round.
            final DateValidationResult a1 = shared.validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            final DateValidationResult b1 = shared.validateDate("20220718  ", MASK_COMPACT);
            final DateValidationResult c1 = shared.validateDate("2022-13-18", MASK_HYPHENATED);
            final DateValidationResult a2 = shared.validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            final DateValidationResult b2 = shared.validateDate("20220718  ", MASK_COMPACT);
            final DateValidationResult c2 = shared.validateDate("2022-13-18", MASK_HYPHENATED);

            assertThat(a2.message()).isEqualTo(a1.message());
            assertThat(b2.message()).isEqualTo(b1.message());
            assertThat(c2.message()).isEqualTo(c1.message());

            // The mask span must follow its own call's argument, never a neighbour's.
            assertThat(a2.message().substring(DATE_FMT_OFFSET, DATE_FMT_OFFSET + DATE_LENGTH))
                    .isEqualTo(MASK_HYPHENATED);
            assertThat(b2.message().substring(DATE_FMT_OFFSET, DATE_FMT_OFFSET + DATE_LENGTH))
                    .isEqualTo(MASK_COMPACT_IMAGE);

            // The result text and severity must likewise follow their own call.
            assertThat(a2.result()).isEqualTo(RESULT_DATE_IS_VALID);
            assertThat(c2.result()).isEqualTo(RESULT_INVALID_MONTH);
            assertThat(a2.returnCode()).isZero();
            assertThat(c2.returnCode()).isEqualTo(3);

            // An error following a success must not inherit the success's severity, and vice versa.
            assertThat(c1.severityCode()).isNotEqualTo(a1.severityCode());
            assertThat(a2.severityCode()).isEqualTo(ACCEPTED_SEVERITY_CODE);
        }

        @Test
        @DisplayName("the class declares no mutable instance field and no static mutable field")
        void theClassDeclaresNoMutableOrStaticMutableField() {
            // Structural introspection of declared field MODIFIERS only. This reaches no private
            // behaviour, needs no --add-opens, and is the direct expression of the requirement: COBOL
            // WORKING-STORAGE must never have become static Java state, because that would break
            // request isolation for three concurrent injectors and make tests order-dependent.
            for (Field field : DateUtilityJob.class.getDeclaredFields()) {
                final int modifiers = field.getModifiers();

                assertThat(Modifier.isFinal(modifiers))
                        .as("field '%s' must be final - no mutable state may exist here",
                                field.getName())
                        .isTrue();

                if (Modifier.isStatic(modifiers)) {
                    // A static field is permitted only as an immutable constant.
                    assertThat(Modifier.isFinal(modifiers))
                            .as("static field '%s' must be final", field.getName())
                            .isTrue();
                } else {
                    // Instance fields must be private, so no collaborator can mutate them.
                    assertThat(Modifier.isPublic(modifiers))
                            .as("instance field '%s' must not be public", field.getName())
                            .isFalse();
                }
            }

            // The result object must expose no writable field either.
            for (Field field : DateValidationResult.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("result field '%s' must be final", field.getName()).isTrue();
                assertThat(Modifier.isPublic(field.getModifiers()))
                        .as("result field '%s' must not be public", field.getName()).isFalse();
            }
        }

        @Test
        @DisplayName("each call returns a fresh result whose bytes cannot be mutated by a caller")
        void eachCallReturnsAFreshDefensivelyCopiedResult() {
            final DateUtilityJob shared = newService();

            // MOVE SPACES TO CSUTLDTC-RESULT precedes every real CALL, so a fresh result per call is
            // exactly the COBOL behaviour.
            final DateValidationResult first = shared.validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            final DateValidationResult second = shared.validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            assertThat(first).isNotSameAs(second);
            assertThat(first.message()).isEqualTo(second.message());

            // Mutating the returned array must not touch the result, or one injector could corrupt
            // another's view.
            final byte[] borrowed = first.messageBytes();
            borrowed[SEVERITY_OFFSET] = (byte) 'X';
            assertThat(first.messageBytes())
                    .as("messageBytes() must hand back a defensive copy")
                    .isNotEqualTo(borrowed)
                    .isEqualTo(second.messageBytes());
        }
    }

    // =============================================================================================
    // Group 9 - the public three-parameter contract.
    // =============================================================================================

    /**
     * {@code PROCEDURE DIVISION USING LS-DATE, LS-DATE-FORMAT, LS-RESULT} (L88) as a Java method: the
     * two inputs become arguments and the output parameter becomes the return value.
     */
    @Nested
    @DisplayName("the public three-parameter contract - L83-L88")
    class PublicContract {

        @Test
        @DisplayName("a null date or mask is rejected rather than silently treated as blanks")
        void aNullDateOrMaskIsRejected() {
            final DateUtilityJob service = newService();

            // A COBOL caller physically cannot pass a null, so there is no legacy behaviour to
            // reproduce; reporting it beats inventing a blank-input semantic the source never had.
            assertThatNullPointerException()
                    .isThrownBy(() -> service.validateDate(null, MASK_HYPHENATED));
            assertThatNullPointerException()
                    .isThrownBy(() -> service.validateDate(SAMPLE_DATE, null));
        }

        @Test
        @DisplayName("the code page is named explicitly and is never the platform default")
        void theCodePageIsNamedExplicitly() {
            // Relying on the platform default is the classic mainframe-data defect: a fixed-width
            // record addressed by absolute byte offset needs a single-byte code page, chosen on purpose.
            assertThat(DateUtilityJob.DEFAULT_MESSAGE_CHARSET)
                    .as("the default must be a named single-byte code page")
                    .isEqualTo(StandardCharsets.US_ASCII);

            assertThat(new DateUtilityJob().validateDate(SAMPLE_DATE, MASK_HYPHENATED).charset())
                    .isEqualTo(DateUtilityJob.DEFAULT_MESSAGE_CHARSET);
            assertThat(newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED).charset())
                    .isEqualTo(MESSAGE_CHARSET);

            // The no-argument constructor must agree with the explicit one byte for byte.
            assertThat(new DateUtilityJob().validateDate(SAMPLE_DATE, MASK_HYPHENATED).messageBytes())
                    .isEqualTo(newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED).messageBytes());
        }

        @Test
        @DisplayName("a multi-byte code page is refused, because absolute offsets require one byte each")
        void aMultiByteCodePageIsRefused() {
            // UTF-16 encodes a digit to two bytes, which would silently double every span and destroy
            // the eighty-byte geometry, so it must be refused at construction rather than at read time.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DateUtilityJob(StandardCharsets.UTF_16));
            assertThatNullPointerException().isThrownBy(() -> new DateUtilityJob(null));
        }

        @Test
        @DisplayName("a short input is right-padded and a long one right-truncated by the PIC X move")
        void aShortInputIsRightPaddedAndALongOneRightTruncated() {
            final DateUtilityJob service = newService();

            // COBOL right-pads an alphanumeric receiver, so a nine-character date gains a trailing
            // space - which lands in the DD field and is not a digit, hence 'Nonnumeric data'.
            final DateValidationResult padded = service.validateDate("2022-07-1", MASK_HYPHENATED);
            assertThat(movePicX("2022-07-1", DATE_LENGTH)).isEqualTo("2022-07-1 ");
            assertThat(padded.result()).isEqualTo(RESULT_NONNUMERIC_DATA);
            assertThat(padded.message()).hasSize(MESSAGE_LENGTH);

            // COBOL truncates an alphanumeric sender on the RIGHT, so the excess is dropped and the
            // first ten characters still form a valid date.
            final DateValidationResult truncated =
                    service.validateDate(SAMPLE_DATE + "XYZ", MASK_HYPHENATED);
            assertThat(movePicX(SAMPLE_DATE + "XYZ", DATE_LENGTH)).isEqualTo(SAMPLE_DATE);
            assertThat(truncated.messageBytes())
                    .as("the excess characters are discarded, so this equals the untruncated call")
                    .isEqualTo(service.validateDate(SAMPLE_DATE, MASK_HYPHENATED).messageBytes());

            // Even an entirely blank input yields a well-formed eighty bytes with the fillers intact.
            final DateValidationResult blank = service.validateDate("", "");
            assertThat(blank.message()).hasSize(MESSAGE_LENGTH);
            assertFillersAreIntact(blank.message());
        }

        @Test
        @DisplayName("toString reports the discrete fields and omits the non-printable image")
        void toStringReportsTheDiscreteFieldsOnly() {
            // The eighty-character image carries two non-printable bytes - one of them the ASCII
            // newline - which would corrupt any surrounding diagnostic text, so it is left out.
            final DateValidationResult result = newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED);

            assertThat(result.toString())
                    .contains("0000")
                    .contains(RESULT_DATE_IS_VALID)
                    .doesNotContain("\n")
                    .doesNotContain("\u0000");
        }
    }

    // =============================================================================================
    // Group 10 - the validator's branch surface.
    //
    // The nine documented feedback conditions are each reachable by several structurally different
    // routes, and the guard chain that distinguishes them is where the real branch count lives: the
    // picture parser, the input scanner and the calendar arithmetic. Group 2 proves each ARM is
    // selected; this group drives the DECISIONS that select it, which is what the branch-coverage gate
    // actually measures.
    //
    // Every expectation below is derived from the documented meaning of the Language Environment
    // message, cross-checked against the copybook layouts - not captured from a running service.
    // =============================================================================================

    /**
     * The guard chain inside the {@code CEEDAYS} substitute, driven condition by condition.
     *
     * <p>The chain is a first-failure-wins sequence: picture-string faults, then insufficient picture
     * information, then input-shape mismatches, then era, then non-numeric data, then month name, then
     * a zero year, then month range, then day range, then the supported range, and only then success.
     */
    @Nested
    @DisplayName("the CEEDAYS substitute's guard chain")
    class ValidatorBranchSurface {

        @ParameterizedTest(name = "[{index}] ''{0}'' / ''{1}'' -> {2} ({3})")
        @CsvSource(delimiter = '|', value = {
            // ---- successful conversions, one per picture-token kind -------------------------------
            "2022-07-18 | YYYY-MM-DD | 0000 | 0000 | YYYY MM DD, the mask both programs pass",
            "20220718   | YYYYMMDD   | 0000 | 0000 | the compact mask the copybook passes",
            "49-07-18   | YY-MM-DD   | 0000 | 0000 | YY at the century-window pivot, 2049",
            "50-07-18   | YY-MM-DD   | 0000 | 0000 | YY just past the pivot, 1950",
            "00-07-18   | YY-MM-DD   | 0000 | 0000 | YY zero maps to 2000, never to year zero",
            "99-07-18   | YY-MM-DD   | 0000 | 0000 | YY at the top of the window, 1999",
            "01JAN2022  | DDMMMYYYY  | 0000 | 0000 | MMM, the first month abbreviation",
            "01DEC2022  | DDMMMYYYY  | 0000 | 0000 | MMM, the last month abbreviation",
            "01jun2022  | DDMMMYYYY  | 0000 | 0000 | MMM folded to upper case against Locale.ROOT",
            "2021-365   | YYYY-DDD   | 0000 | 0000 | DDD, the last day of a common year",
            "2020-366   | YYYY-DDD   | 0000 | 0000 | DDD, the leap day of a leap year",
            "AD220718   | <CC>YYMMDD | 0000 | 0000 | an era field naming the common era",
            "ad220718   | <CC>YYMMDD | 0000 | 0000 | an era name folded to upper case",
            "2022{07{18 | YYYY{MM{DD | 0000 | 0000 | '{' sorts above 'z' yet is a literal, not a letter",
            "2022~07~18 | YYYY~MM~DD | 0000 | 0000 | '~' likewise, at the top of printable ASCII",
            // ---- the leap-year rule, all three divisibility branches -----------------------------
            "2020-02-29 | YYYY-MM-DD | 0000 | 0000 | divisible by 4, a leap year",
            "2000-02-29 | YYYY-MM-DD | 0000 | 0000 | divisible by 400, a leap year",
            "1900-02-29 | YYYY-MM-DD | 0003 | 2508 | divisible by 100 not 400, NOT a leap year",
            "2021-02-29 | YYYY-MM-DD | 0003 | 2508 | a common year has no 29 February",
            "2021-02-28 | YYYY-MM-DD | 0000 | 0000 | 28 February is always valid",
            // ---- month lengths, both the 31-day and the 30-day sets ------------------------------
            "2022-01-31 | YYYY-MM-DD | 0000 | 0000 | January has 31 days",
            "2022-12-31 | YYYY-MM-DD | 0000 | 0000 | December has 31 days",
            "2022-04-30 | YYYY-MM-DD | 0000 | 0000 | April has 30 days",
            "2022-04-31 | YYYY-MM-DD | 0003 | 2508 | April has no 31st",
            "2022-06-31 | YYYY-MM-DD | 0003 | 2508 | June has no 31st - IBM's own illustration",
            "2022-09-31 | YYYY-MM-DD | 0003 | 2508 | September has no 31st",
            "2022-11-31 | YYYY-MM-DD | 0003 | 2508 | November has no 31st",
            "2022-07-00 | YYYY-MM-DD | 0003 | 2508 | day zero is not a valid day of the month",
            // ---- day-of-year range --------------------------------------------------------------
            "2021-366   | YYYY-DDD   | 0003 | 2508 | day 366 of a common year",
            "2021-000   | YYYY-DDD   | 0003 | 2508 | day zero of the year",
            "2021-367   | YYYY-DDD   | 0003 | 2508 | beyond the length of any year",
            // ---- month range and month name -----------------------------------------------------
            "2022-00-15 | YYYY-MM-DD | 0003 | 2517 | month zero is below the range",
            "2022-13-15 | YYYY-MM-DD | 0003 | 2517 | month 13 is above the range",
            "01ZZZ2022  | DDMMMYYYY  | 0003 | 2517 | an abbreviation that is not a month",
            // ---- a year-within-era of zero ------------------------------------------------------
            "0000-07-18 | YYYY-MM-DD | 0003 | 2521 | a four-digit year of zero",
            // ---- non-numeric data in each numeric field kind -------------------------------------
            "20A2-07-18 | YYYY-MM-DD | 0003 | 2520 | a letter in the year field",
            "2022-A7-18 | YYYY-MM-DD | 0003 | 2520 | a letter in the month field",
            "2022-07-A8 | YYYY-MM-DD | 0003 | 2520 | a letter in the day field",
            "2022-A00   | YYYY-DDD   | 0003 | 2520 | a letter in the day-of-year field",
            "AB-07-18   | YY-MM-DD   | 0003 | 2520 | a letter in a two-digit year field",
            "2022-07-1  | YYYY-MM-DD | 0003 | 2520 | a blank counts as non-numeric",
            // ---- the supported range, on both sides of the Lillian epoch --------------------------
            "1582-10-15 | YYYY-MM-DD | 0000 | 0000 | the Lillian epoch itself is in range",
            "1582-10-14 | YYYY-MM-DD | 0003 | 2513 | a day the Gregorian reform skipped",
            "1582-09-30 | YYYY-MM-DD | 0003 | 2513 | the month before the epoch",
            "1581-12-31 | YYYY-MM-DD | 0003 | 2513 | the year before the epoch",
            "9999-12-31 | YYYY-MM-DD | 0000 | 0000 | the top of the four-digit year range",
            "BC220718   | <CC>YYMMDD | 0003 | 2513 | every date before the common era is out of range",
            // ---- an unrecognised era ------------------------------------------------------------
            "ZZ220718   | <CC>YYMMDD | 0003 | 2509 | an era name that is neither AD nor BC",
            // ---- picture-string faults ----------------------------------------------------------
            "2022-07-18 | QQQQ-MM-DD | 0003 | 2518 | a letter that begins no token",
            "2022-07-18 | YYYY-MM-DZ | 0003 | 2518 | a lone letter where a token was expected",
            "2022-07-18 | YYYYYYYYYY | 0003 | 2518 | the year role declared twice",
            "2022-07-18 | YYYY-MM-MM | 0003 | 2518 | the month role declared twice",
            "AD22-07-18 | <CC YYMMDD | 0003 | 2518 | an era field with no closing delimiter",
            "2022-07018 | YYYY-DDDMM | 0003 | 2518 | a day of the year AND a month contradict",
            "20220718   | YYYYDDDDD  | 0003 | 2518 | a day of the year AND a day of the month clash",
            "2022-07-18 | yyyy-MM-DD | 0003 | 2518 | picture tokens are case-SENSITIVE: lower-case YYYY",
            "2022-07-18 | YYYY-mm-DD | 0003 | 2518 | picture tokens are case-SENSITIVE: lower-case MM",
            "2022-07-18 | YYYY-MM-dd | 0003 | 2518 | picture tokens are case-SENSITIVE: lower-case DD",
            "01jan2022  | DDmmmYYYY  | 0003 | 2518 | picture tokens are case-SENSITIVE: lower-case MMM",
            // ---- insufficient picture information (attributed to the PICTURE, not the input) ------
            "2022       | YYYY       | 0003 | 2507 | a year alone cannot yield a Lillian value",
            "2022-07    | YYYY-MM    | 0003 | 2507 | a year and month with no day",
            "2022-07-18 | MM-DD      | 0003 | 2507 | a month and day with no year",
            // ---- shape mismatches, which no documented code covers -> WHEN OTHER -----------------
            "2022/07/18 | YYYY-MM-DD | 0003 | 0000 | a literal delimiter the input does not match",
            "2022-07.18 | YYYY-MM-DD | 0003 | 0000 | a literal mismatch at the second delimiter",
            "2022-07-18 | YYYY{MM{DD | 0003 | 0000 | a literal mismatch on an above-'z' delimiter",
            "AD22071899 | <CC>YYMMDD | 0003 | 0000 | non-blank input beyond the picture's description",
        })
        @DisplayName("each guard-chain condition reports its documented severity and message number")
        void eachGuardChainConditionReportsItsDocumentedOutcome(String inputDate,
                                                               String pictureMask,
                                                               String expectedSeverityCode,
                                                               String expectedMessageNumber,
                                                               String rationale) {
            final DateValidationResult result = newService().validateDate(inputDate, pictureMask);

            assertThat(result.severityCode())
                    .as("WS-SEVERITY for '%s' / '%s' - %s", inputDate, pictureMask, rationale)
                    .isEqualTo(expectedSeverityCode);
            assertThat(result.messageNumber())
                    .as("WS-MSG-NO for '%s' / '%s' - %s", inputDate, pictureMask, rationale)
                    .isEqualTo(expectedMessageNumber);

            // Whatever the outcome, the record geometry and the fillers are invariant.
            assertThat(result.message()).hasSize(MESSAGE_LENGTH);
            assertThat(result.result()).hasSize(RESULT_LENGTH);
            assertFillersAreIntact(result.message());

            // The return code always mirrors the severity, and the mask span is always clean.
            assertThat(asPicNine4(result.returnCode())).isEqualTo(result.severityCode());
            assertThat(result.message().substring(DATE_FMT_OFFSET, DATE_FMT_OFFSET + DATE_LENGTH))
                    .isEqualTo(movePicX(pictureMask, DATE_LENGTH));
        }

        @ParameterizedTest(name = "[{index}] month {0} -> ''{1}''")
        @CsvSource({
            "JAN, 2022-01-31", "FEB, 2022-02-28", "MAR, 2022-03-31", "APR, 2022-04-30",
            "MAY, 2022-05-31", "JUN, 2022-06-30", "JUL, 2022-07-31", "AUG, 2022-08-31",
            "SEP, 2022-09-30", "OCT, 2022-10-31", "NOV, 2022-11-30", "DEC, 2022-12-31",
        })
        @DisplayName("all twelve month abbreviations resolve, and each month's last day is valid")
        void allTwelveMonthAbbreviationsResolveToTheirOwnMonth(String abbreviation,
                                                              String equivalentNumericDate) {
            final DateUtilityJob service = newService();

            // Drive the whole MMM lookup table, and the month-length table alongside it: the numeric
            // form of the same date must agree with the abbreviated form.
            final String lastDay = equivalentNumericDate.substring(8, 10);
            final DateValidationResult byName =
                    service.validateDate(lastDay + abbreviation + "2022 ", "DDMMMYYYY ");
            final DateValidationResult byNumber =
                    service.validateDate(equivalentNumericDate, MASK_HYPHENATED);

            assertThat(byName.severityCode())
                    .as("%s must resolve to a month", abbreviation)
                    .isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(byNumber.severityCode())
                    .as("the last day of %s must be valid", abbreviation)
                    .isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(byName.result()).isEqualTo(byNumber.result()).isEqualTo(RESULT_DATE_IS_VALID);
        }

        @Test
        @DisplayName("picture tokens are case-sensitive while input month and era names are folded")
        void pictureTokensAreCaseSensitiveWhileInputNamesAreFolded() {
            // A genuine and easily-missed ASYMMETRY, pinned here so neither half can drift:
            //   * the PICTURE STRING is matched literally, so a lower-case token is not a token at all
            //     and the picture is rejected as invalid;
            //   * the INPUT's month abbreviation and era name are folded to upper case against
            //     Locale.ROOT before lookup, so either case is accepted there.
            // Reading only one half of that would suggest the whole validator is case-insensitive.
            final DateUtilityJob service = newService();

            // Picture side - rejected, because 'yyyy' begins no token and is then a stray letter.
            assertThat(service.validateDate(SAMPLE_DATE, "yyyy-MM-DD").result())
                    .as("a lower-case picture token is not recognised")
                    .isEqualTo(RESULT_BAD_PIC_STRING);
            assertThat(service.validateDate(SAMPLE_DATE, MASK_HYPHENATED).result())
                    .as("the same picture in upper case is accepted")
                    .isEqualTo(RESULT_DATE_IS_VALID);

            // Input side - accepted in either case, for both the month name and the era name. Folding
            // changes the OUTCOME, so both cases validate identically...
            final DateValidationResult upperMonth = service.validateDate("01JUN2022 ", "DDMMMYYYY ");
            final DateValidationResult lowerMonth = service.validateDate("01jun2022 ", "DDMMMYYYY ");
            assertThat(lowerMonth.severityCode()).isEqualTo(upperMonth.severityCode())
                    .isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(lowerMonth.messageNumber()).isEqualTo(upperMonth.messageNumber());
            assertThat(lowerMonth.result()).isEqualTo(upperMonth.result())
                    .isEqualTo(RESULT_DATE_IS_VALID);
            assertThat(lowerMonth.returnCode()).isEqualTo(upperMonth.returnCode()).isZero();

            final DateValidationResult upperEra = service.validateDate("AD220718  ", "<CC>YYMMDD");
            final DateValidationResult lowerEra = service.validateDate("ad220718  ", "<CC>YYMMDD");
            assertThat(lowerEra.severityCode()).isEqualTo(upperEra.severityCode())
                    .isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(lowerEra.result()).isEqualTo(upperEra.result()).isEqualTo(RESULT_DATE_IS_VALID);

            // ...but folding is applied only for the LOOKUP, never to the stored bytes. The "TstDate:"
            // span echoes the raw input verbatim through the L122 group move, so it preserves the
            // input's original case and the two eighty-byte images are NOT identical. They differ
            // inside [45,55) and nowhere else, which is a second, independent confirmation that the
            // group move copies raw input bytes rather than anything the validator normalised.
            assertThat(lowerMonth.messageBytes())
                    .as("the echoed input keeps its own case, so the images are not byte-identical")
                    .isNotEqualTo(upperMonth.messageBytes());

            final byte[] lower = lowerMonth.messageBytes();
            final byte[] upper = upperMonth.messageBytes();
            for (int offset = 0; offset < MESSAGE_LENGTH; offset++) {
                if (offset >= DATE_OFFSET && offset < DATE_OFFSET + DATE_LENGTH) {
                    continue; // the echoed-input span is the one place case may differ
                }
                assertThat(lower[offset])
                        .as("byte %d lies outside the echoed-input span and must match exactly", offset)
                        .isEqualTo(upper[offset]);
            }

            // And within that span the difference really is only letter case.
            assertThat(new String(lower, DATE_OFFSET, DATE_LENGTH, MESSAGE_CHARSET)
                    .toUpperCase(Locale.ROOT))
                    .isEqualTo(new String(upper, DATE_OFFSET, DATE_LENGTH, MESSAGE_CHARSET)
                            .toUpperCase(Locale.ROOT));
        }

        @Test
        @DisplayName("the two structurally unreachable guards are analysed, not overlooked")
        void theTwoStructurallyUnreachableGuardsAreAnalysedNotOverlooked() {
            // Two branches in the validator cannot be driven through the public API, and the reason is
            // recorded here rather than left as an unexplained coverage gap. Neither is a defect and
            // neither may be deleted: each is a defensive guard whose absence would turn a future
            // change into a silent out-of-bounds read.
            //
            // (1) "the picture describes more input than the input supplies", which would report the
            //     unenumerated token. It is unreachable because LS-DATE is normalised to exactly ten
            //     characters and no picture string can describe more than ten input positions: every
            //     token consumes at most as many input characters as it occupies picture characters,
            //     and the era field consumes FEWER (four picture characters for two input characters).
            //     The proof is asserted below rather than merely asserted in prose.
            //
            // (2) the default arm of the exhaustive switch over the eight picture-token kinds. All
            //     eight are driven by this suite - YEAR_4, YEAR_2, MONTH_NAME_3, MONTH_2, JULIAN_DAY_3,
            //     DAY_2, ERA and LITERAL - so the arm the compiler synthesises for an unknown constant
            //     is dead by construction.
            //
            // Every input is exactly ten characters wide after the PIC X move, whatever was passed.
            assertThat(movePicX("", DATE_LENGTH)).hasSize(DATE_LENGTH);
            assertThat(movePicX("2022-07-18XYZ", DATE_LENGTH)).hasSize(DATE_LENGTH);
            assertThat(movePicX(SAMPLE_DATE, DATE_LENGTH)).hasSize(DATE_LENGTH);

            // And an era field really does cost more picture characters than input characters, which is
            // what makes it impossible for a ten-character picture to out-describe a ten-character
            // input: '<CC>YYMMDD' is ten picture characters describing only eight input positions.
            final DateValidationResult eraResult =
                    newService().validateDate("AD220718  ", "<CC>YYMMDD");
            assertThat("<CC>YYMMDD").hasSize(DATE_LENGTH);
            assertThat(eraResult.severityCode())
                    .as("the era picture leaves two input positions undescribed, which must be blank")
                    .isEqualTo(ACCEPTED_SEVERITY_CODE);

            // All eight picture-token kinds are exercised by this suite, which is why the switch's
            // default arm is unreachable. Driven here as one compact proof.
            final DateUtilityJob service = newService();
            assertThat(service.validateDate(SAMPLE_DATE, MASK_HYPHENATED).severityCode())
                    .as("YEAR_4, MONTH_2, DAY_2 and LITERAL").isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(service.validateDate("49-07-18  ", "YY-MM-DD  ").severityCode())
                    .as("YEAR_2").isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(service.validateDate("01JAN2022 ", "DDMMMYYYY ").severityCode())
                    .as("MONTH_NAME_3").isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(service.validateDate("2021-365  ", "YYYY-DDD  ").severityCode())
                    .as("JULIAN_DAY_3").isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(eraResult.severityCode()).as("ERA").isEqualTo(ACCEPTED_SEVERITY_CODE);
        }
    }
}
