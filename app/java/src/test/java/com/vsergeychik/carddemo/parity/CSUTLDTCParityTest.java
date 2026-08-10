package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import com.vsergeychik.carddemo.util.DateUtilityJob.DateValidationResult;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.stereotype.Service;

/**
 * The twenty-case parity gate for {@code CSUTLDTC}, the called date-validation subprogram
 * translated to {@code com.vsergeychik.carddemo.util.DateUtilityJob}.
 *
 * <h2>What this class asserts</h2>
 * <p>{@code CSUTLDTC} is a pure function of two parameters with an eighty-byte output. It opens no
 * file, is reached by no JCL, and is not one of the nine {@code CALL 'CEE3ABD'} abend sites, so its
 * entire observable behaviour is the eighty bytes {@code MOVE WS-MESSAGE TO LS-RESULT} produces at
 * {@code app/cbl/CSUTLDTC.cbl:97} and the severity {@code MOVE WS-SEVERITY-N TO RETURN-CODE}
 * produces at {@code :98}. Both are compared field by field for each of the twenty cases in
 * {@code src/test/resources/parity/CSUTLDTC/}, and each case must produce a diff count of zero.
 *
 * <p>That absence of I/O is what makes this program the right place to prove the fixed-width
 * machinery end to end. It is the only one of the twenty-eight with zero dataset access, so a
 * failure here can only be a defect in the message layout, the {@code PIC X} move, the numeric
 * overlay or the picture-string validator - never in a repository, a seeded fixture or a code page
 * applied to a record. Everything the three-hundred and three-hundred-and-fifty byte records depend
 * on is exercised here first, against a layout small enough to read in full.
 *
 * <h2>The baseline is statically derived, never captured</h2>
 * <p>Every expected value in the twenty case files was derived by reading
 * {@code app/cbl/CSUTLDTC.cbl} - the {@code 01 WS-MESSAGE} declaration at {@code :42-:57}, the nine
 * {@code 88}-level feedback tokens at {@code :62-:70}, the group move at {@code :122} and the
 * {@code EVALUATE TRUE} at {@code :128-:149}. None of it was captured from a running COBOL program,
 * because running one is impossible in this environment. Two of the eight recorded blockers land
 * squarely on this program:
 * <ul>
 *   <li>the available compiler refuses to build it as an executable at all, because
 *       {@code PROCEDURE DIVISION USING} at {@code :88} carries a {@code USING} clause - the failure
 *       is reported against {@code :90}, the first statement of the division, which is why the plan
 *       records it as {@code CSUTLDTC:90}. It is a subprogram, not a main program, and would need a
 *       bespoke driver harness even to load;</li>
 *   <li>no Language Environment services are reachable, so the {@code CALL "CEEDAYS"} at
 *       {@code :116} that decides every one of these outcomes has no implementation to call. A
 *       search of the compiler's intrinsics matches zero {@code CEE*} entries.</li>
 * </ul>
 * The substitution is deliberate, is recorded as the highest-severity open risk in the Agent Action
 * Plan, and changes only the <em>provenance</em> of the expected values: twenty cases per program,
 * field-for-field diffing and the diff-count-of-zero gate all stand. The residual risk is that a
 * statically derived expectation can encode a misreading, and it is mitigated the way the plan
 * prescribes - the eighty-byte layout is reproduced from the copybook-style declaration
 * mechanically rather than from prose, and {@link #theWsMessageLayoutAccountsForEveryOneOfTheEightyBytes()}
 * makes that reproduction executable rather than a comment.
 *
 * <h2>The name says Job; the unit is a {@code @Service}</h2>
 * <p>{@code DateUtilityJob} is the class name the migration mandates, and it is used verbatim. It is
 * also wrong about what the class is, and this test asserts the truth rather than the name:
 * {@code CSUTLDTC} appears in no {@code EXEC PGM=} anywhere in {@code app/jcl/}, {@code app/proc/}
 * or {@code app/csd/}, and is instead {@code CALL}ed four times from two <em>online</em> programs -
 * {@code CORPT00C} at {@code :392} and {@code :412}, {@code COTRN02C} at {@code :393} and
 * {@code :413}. A called subprogram is not a job.
 * {@link #noSpringBatchJobStepOrTaskletOriginatesFromThisType()} proves no Spring Batch
 * {@code Job}, {@code Step} or {@code Tasklet} originates from this type and that it is annotated
 * {@link Service}, so the divergence is documented and enforced rather than quietly designed away.
 * Every case declares {@link UnitKind#SERVICE} and the unit is constructed and called as a plain
 * object - no {@code JobLauncher}, no job repository, no HTTP layer.
 *
 * <h2>Message 2513 is the value both callers accept - siblings may rely on it</h2>
 * <p>{@code FC-UNSUPP-RANGE} ({@code :66}) carries severity 3 and message number 2513, and it is the
 * one error both online callers deliberately let through. {@link #CASE_UNSUPPORTED_RANGE_BOUNDARY}
 * and {@code case11} pin it, so {@code CORPT00CParityTest} and {@code COTRN02CParityTest} can rely
 * on the four bytes {@code 2513} standing at offset 15 with {@code 0003} at offset 0 and
 * {@code 'Unsupp. Range  '} at offset 20. {@link #ARMS} is the single transcription of that mapping
 * for all three classes.
 *
 * <h2>Where the two LINKAGE parameters live, and why</h2>
 * <p>{@link ParityCase} can carry a unit's inputs as seeded datasets, as batch job parameters or as
 * an online screen request. {@code CSUTLDTC}'s inputs are none of those - they are {@code CALL}
 * parameters, {@code LS-DATE PIC X(10)} and {@code LS-DATE-FORMAT PIC X(10)} at {@code :84-:85} -
 * and this is the only in-scope program shaped that way. Rather than smuggle them through a
 * fabricated dataset binding key or an invented {@code xxxI} map field, which would misdescribe a
 * program that touches no dataset and paints no screen, they are declared in {@link #LINKAGE} and
 * restated verbatim in each case file's {@code description}.
 * {@link #eachCaseDescriptionStatesTheLinkageParametersItIsRunWith()} refuses any drift between the
 * two. The assertion stays honest: the expectation lives in the case file, the input lives here, and
 * the unit computes the output from the input alone -
 * {@link ParityHarness.Invocation} deliberately exposes no part of the expectation, and this
 * class's adapter reads nothing from it but {@link ParityHarness.Invocation#caseId()} and
 * {@link ParityHarness.Invocation#charset()}.
 *
 * <h2>Nine of the ten EVALUATE arms are reachable; the tenth is proved unreachable</h2>
 * <p>The {@code EVALUATE TRUE} at {@code :128-:149} has ten arms. Nine are selected by a feedback
 * token the validator can produce and each has a case here. The tenth, {@code WHEN OTHER} at
 * {@code :147-:148}, is selected only by a token no {@code 88}-level names, which a real Language
 * Environment might return and this translation never manufactures - so no input date can reach it.
 * That is asserted as a property by
 * {@link #theWhenOtherArmIsUnreachableThroughTheByteContract()} rather than left as a gap, and the
 * arm's own mapping is covered directly by {@code DateUtilityJobTest} in the {@code util} package,
 * which sits inside the package that can see the seam.
 *
 * <h2>Standards</h2>
 * <p>{@code review_rules} reports <em>no user rules provided</em> for this project, so no rule
 * governs this file. Their absence is not a lower bar: the enterprise practices the plan substitutes
 * apply instead. Concretely - versions come from the Boot parent and nothing is pinned here; the
 * reference COBOL is read and never written; the {@code Job}-versus-{@code Service} divergence is
 * documented rather than corrected; every charset is named and never defaulted; there are no
 * wildcard imports, no {@code double}, no {@code float} and no static mutable state; and the tests
 * ship with the implementation rather than after it.
 *
 * @see DateUtilityJob
 * @see ParityHarness
 * @see FieldDiffer
 */
@DisplayName("CSUTLDTC parity - the called date-validation subprogram, 20 cases")
class CSUTLDTCParityTest {

    /**
     * The COBOL program name, which is also the stem of the resource directory the cases load from
     * and of this class. The three spellings are identical on purpose, so no case conversion happens
     * anywhere.
     */
    private static final String PROGRAM = "CSUTLDTC";

    /** The gate's case count. Twenty per program, and a short set is not a smaller gate. */
    private static final int EXPECTED_CASE_COUNT = 20;

    /** The case that pins message 2513, quoted by the class documentation and by two siblings. */
    private static final String CASE_UNSUPPORTED_RANGE_BOUNDARY = "case10";

    // =================================================================================================
    //  THE 80-BYTE CONTRACT, TRANSCRIBED FROM app/cbl/CSUTLDTC.cbl:42-57
    //
    //  Declared here as spans rather than as one string literal so the geometry is checkable. Two of
    //  the three literal FILLERs are SHORTER than the span that holds them and are therefore right
    //  space-padded by their VALUE clause, and getting either wrong shifts every following byte:
    //    - 'Mesg Code:' is 10 characters in a PIC X(11) span   (:45)
    //    - 'TstDate:'   is  8 characters in a PIC X(09) span   (:51)
    //  'Mask used:' (:54) is exactly 10 in a PIC X(10) span and needs no pad, which is precisely why
    //  the other two are easy to overlook.
    // =================================================================================================

    /** {@code 02 WS-SEVERITY PIC X(04)} at {@code :43} - {@code '0000'} or {@code '0003'}. */
    private static final FieldSpan WS_SEVERITY = FieldSpan.alphanumeric("WS-SEVERITY", 0, 4);

    /**
     * {@code 02 WS-SEVERITY-N REDEFINES WS-SEVERITY PIC 9(4)} at {@code :44} - the numeric view
     * {@code :123} writes through and {@code :98} reads back into {@code RETURN-CODE}. One of the two
     * {@code REDEFINES} pairs this program declares.
     */
    private static final FieldSpan WS_SEVERITY_N =
            FieldSpan.redefining("WS-SEVERITY-N", 0, 4, PictureKind.UNSIGNED_NUMERIC);

    /** {@code 02 FILLER PIC X(11) VALUE 'Mesg Code:'} at {@code :45} - ten characters in eleven. */
    private static final FieldSpan FILLER_MESG_CODE = FieldSpan.filler(4, 11, "Mesg Code:");

    /** {@code 02 WS-MSG-NO PIC X(04)} at {@code :46} - for example {@code '2513'}. */
    private static final FieldSpan WS_MSG_NO = FieldSpan.alphanumeric("WS-MSG-NO", 15, 4);

    /**
     * {@code 02 WS-MSG-NO-N REDEFINES WS-MSG-NO PIC 9(4)} at {@code :47} - the numeric view
     * {@code :124} writes through. The second of the two {@code REDEFINES} pairs.
     */
    private static final FieldSpan WS_MSG_NO_N =
            FieldSpan.redefining("WS-MSG-NO-N", 15, 4, PictureKind.UNSIGNED_NUMERIC);

    /** {@code 02 FILLER PIC X(01) VALUE SPACE} at {@code :48}. */
    private static final FieldSpan FILLER_AFTER_MSG_NO = FieldSpan.filler(19, 1);

    /** {@code 02 WS-RESULT PIC X(15)} at {@code :49} - one of the ten literals, padded to fifteen. */
    private static final FieldSpan WS_RESULT = FieldSpan.alphanumeric("WS-RESULT", 20, 15);

    /** {@code 02 FILLER PIC X(01) VALUE SPACE} at {@code :50}. */
    private static final FieldSpan FILLER_AFTER_RESULT = FieldSpan.filler(35, 1);

    /** {@code 02 FILLER PIC X(09) VALUE 'TstDate:'} at {@code :51} - eight characters in nine. */
    private static final FieldSpan FILLER_TST_DATE = FieldSpan.filler(36, 9, "TstDate:");

    /**
     * {@code 02 WS-DATE PIC X(10) VALUE SPACES} at {@code :52}.
     *
     * <p>Its final content is not the date. {@code :122} moves the whole of
     * {@code WS-DATE-TO-TEST} into it, and that group is the halfword {@code Vstring-length}
     * followed by the ten text bytes - twelve bytes into a ten-byte alphanumeric receiver, which is
     * left justified and truncated on the right. The halfword survives and only the first eight text
     * bytes follow it.
     */
    private static final FieldSpan WS_DATE = FieldSpan.alphanumeric("WS-DATE", 45, 10);

    /** {@code 02 FILLER PIC X(01) VALUE SPACE} at {@code :53}. */
    private static final FieldSpan FILLER_AFTER_DATE = FieldSpan.filler(55, 1);

    /** {@code 02 FILLER PIC X(10) VALUE 'Mask used:'} at {@code :54} - exactly ten in ten. */
    private static final FieldSpan FILLER_MASK_USED = FieldSpan.filler(56, 10, "Mask used:");

    /** {@code 02 WS-DATE-FMT PIC X(10)} at {@code :55} - the picture string, echoed back. */
    private static final FieldSpan WS_DATE_FMT = FieldSpan.alphanumeric("WS-DATE-FMT", 66, 10);

    /** {@code 02 FILLER PIC X(01) VALUE SPACE} at {@code :56}. */
    private static final FieldSpan FILLER_AFTER_FMT = FieldSpan.filler(76, 1);

    /** {@code 02 FILLER PIC X(03) VALUE SPACES} at {@code :57} - the last three bytes of the 80. */
    private static final FieldSpan FILLER_TRAILING = FieldSpan.filler(77, 3);

    /**
     * {@code 01 WS-MESSAGE} entire, in declaration order.
     *
     * <p>{@link RecordLayout} runs a full geometry self-check on construction - contiguity from byte
     * zero, no gap, no overlap, every {@code REDEFINES} overlay inside storage already declared, and
     * the storage spans summing to the declared width. Declaring the layout here therefore <em>is</em>
     * the proof that the thirteen spans account for all eighty bytes; if this transcription were
     * wrong, this field would fail to initialise and every test in the class would fail with the
     * offending descriptor named.
     */
    private static final RecordLayout WS_MESSAGE_LAYOUT = RecordLayout.of(
            DateUtilityJob.LS_RESULT_LENGTH,
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

    // =================================================================================================
    //  THE TEN EVALUATE ARMS, TRANSCRIBED FROM app/cbl/CSUTLDTC.cbl:128-149
    // =================================================================================================

    /**
     * One arm of the {@code EVALUATE TRUE} at {@code :128-:149}, with the {@code 88}-level token that
     * selects it and the input that reaches it.
     *
     * @param ordinal      the arm's one-based position in the {@code EVALUATE}, which is the order
     *                     {@code EVALUATE} evaluates them in and therefore part of the contract
     * @param conditionName the {@code 88}-level condition name from {@code :62-:70}, or
     *                     {@code WHEN OTHER} for the default arm
     * @param token        the sixteen hex digits of the feedback token's {@code VALUE} clause, kept so
     *                     the severity and message number below are checkable against the source
     *                     rather than merely asserted. {@code null} for {@code WHEN OTHER}, which has
     *                     no {@code VALUE} of its own
     * @param severityCode the four characters {@code :123} leaves in {@code WS-SEVERITY}
     * @param messageNumber the four characters {@code :124} leaves in {@code WS-MSG-NO}
     * @param resultText   the literal the arm moves into {@code WS-RESULT}, exactly as written in the
     *                     source including any trailing spaces the author baked into it
     * @param lsDate       an {@code LS-DATE} that reaches this arm, or {@code null} when the arm
     *                     cannot be reached through the public byte contract
     * @param lsDateFormat the matching {@code LS-DATE-FORMAT}, or {@code null} for an unreachable arm
     */
    private record EvaluateArm(int ordinal,
                               String conditionName,
                               String token,
                               String severityCode,
                               String messageNumber,
                               String resultText,
                               String lsDate,
                               String lsDateFormat) {

        /** Whether an input date exists that selects this arm. False for {@code WHEN OTHER} alone. */
        boolean reachable() {
            return lsDate != null;
        }

        /** The result text as {@code MOVE ... TO WS-RESULT PIC X(15)} leaves it - right space-padded. */
        String paddedResultText() {
            return movePicX(resultText, WS_RESULT.length());
        }
    }

    /**
     * All ten arms, in source order, with {@code WHEN OTHER} last.
     *
     * <p>The order is the contract. {@code EVALUATE} takes the first matching {@code WHEN} and
     * {@code WHEN OTHER} is the default, so a reordering that put {@code WHEN OTHER} anywhere but last
     * would change which arm a token selects. Reading this list beside {@code :128-:149} is how a
     * reviewer checks the transcription, which is why the token hex is carried rather than only the
     * decoded numbers.
     *
     * <p>The severity and the message number are the two halfwords at the front of each token:
     * {@code X'000309D1...'} is severity {@code 0x0003} then message {@code 0x09D1} = 2513. The
     * trailing {@code 59C3C5C5} is the condition's severity-control byte and the facility identifier
     * {@code 'CEE'}, and takes no part in the message.
     */
    private static final List<EvaluateArm> ARMS = List.of(
            // :129-:130. The name says INVALID but the all-zero token is the SUCCESS token: both
            // halfwords are zero, so severity is 0 and RETURN-CODE is 0. Preserved, not corrected.
            new EvaluateArm(1, "FC-INVALID-DATE", "0000000000000000",
                    "0000", "0000", "Date is valid", "2022-07-19", "YYYY-MM-DD"),
            // :131-:132. Attributed to the picture string, not to a short date.
            new EvaluateArm(2, "FC-INSUFFICIENT-DATA", "000309CB59C3C5C5",
                    "0003", "2507", "Insufficient", "2022-07-19", "          "),
            // :133-:134. 29 February in a common year - IBM's own illustration of the condition.
            new EvaluateArm(3, "FC-BAD-DATE-VALUE", "000309CC59C3C5C5",
                    "0003", "2508", "Datevalue error", "2023-02-29", "YYYY-MM-DD"),
            // :135-:136. An era field holding a name that is not AD or BC.
            new EvaluateArm(4, "FC-INVALID-ERA", "000309CD59C3C5C5",
                    "0003", "2509", "Invalid Era    ", "XX220719  ", "<XX>YYMMDD"),
            // :137-:138. The day before the Lillian epoch. THE ONE ERROR BOTH CALLERS ACCEPT.
            new EvaluateArm(5, "FC-UNSUPP-RANGE", "000309D159C3C5C5",
                    "0003", "2513", "Unsupp. Range  ", "1582-10-14", "YYYY-MM-DD"),
            // :139-:140. Month 00 - numeric, correctly shaped, and outside 1 to 12.
            new EvaluateArm(6, "FC-INVALID-MONTH", "000309D559C3C5C5",
                    "0003", "2517", "Invalid month  ", "2022-00-15", "YYYY-MM-DD"),
            // :141-:142. A letter that begins no recognised picture token.
            new EvaluateArm(7, "FC-BAD-PIC-STRING", "000309D659C3C5C5",
                    "0003", "2518", "Bad Pic String ", "2022-07-19", "QQQQ-MM-DD"),
            // :143-:144. Letters standing in every numeric position of a well-formed mask.
            new EvaluateArm(8, "FC-NON-NUMERIC-DATA", "000309D859C3C5C5",
                    "0003", "2520", "Nonnumeric data", "ABCD-EF-GH", "YYYY-MM-DD"),
            // :145-:146. Year zero, which no era admits; checked before the range.
            new EvaluateArm(9, "FC-YEAR-IN-ERA-ZERO", "000309D959C3C5C5",
                    "0003", "2521", "YearInEra is 0 ", "0000-01-01", "YYYY-MM-DD"),
            // :147-:148 WHEN OTHER. Deliberately NOT reachable through validateDate: it is selected
            // only by a feedback token no 88-level names, which a real Language Environment might
            // return and this translation never manufactures. The null inputs mark that, and
            // theWhenOtherArmIsUnreachableThroughTheByteContract asserts it as a property. The arm's
            // own mapping is covered by DateUtilityJobTest, which lives in the package that can see
            // the package-private seam this class cannot.
            new EvaluateArm(10, "WHEN OTHER", null,
                    null, null, "Date is invalid", null, null));

    /**
     * Each case's two {@code LINKAGE SECTION} parameters, keyed by case identifier.
     *
     * <p>{@code LS-DATE} and {@code LS-DATE-FORMAT} are both {@code PIC X(10)} ({@code :84-:85}), so
     * every value below is written out to exactly ten characters - trailing spaces included - rather
     * than left to a pad. That is not cosmetic: a mask of {@code '<XX>YYYYMMDD'} would be
     * <em>truncated</em> to {@code '<XX>YYYYMM'} by the receiving {@code PIC X(10)} and would then
     * report 2507 for want of a day rather than the 2509 it appears to ask for, so the declared width
     * has to be visible at the point the value is written.
     *
     * <p>Immutable, and the only reason it is {@code static} is that a {@code @MethodSource} is
     * static. There is no mutable static state in this class.
     */
    private static final Map<String, Linkage> LINKAGE = Map.ofEntries(
            Map.entry("case01", new Linkage("2022-07-19", "YYYY-MM-DD")),
            Map.entry("case02", new Linkage("19/07/2022", "DD/MM/YYYY")),
            Map.entry("case03", new Linkage("1582-10-15", "YYYY-MM-DD")),
            Map.entry("case04", new Linkage("2022-07-1 ", "YYYY-MM-DD")),
            Map.entry("case05", new Linkage("2022-07-19", "          ")),
            Map.entry("case06", new Linkage("2022-07-19", "YYYY      ")),
            Map.entry("case07", new Linkage("2023-02-29", "YYYY-MM-DD")),
            Map.entry("case08", new Linkage("2022-06-31", "YYYY-MM-DD")),
            Map.entry("case09", new Linkage("XX220719  ", "<XX>YYMMDD")),
            Map.entry("case10", new Linkage("1582-10-14", "YYYY-MM-DD")),
            Map.entry("case11", new Linkage("BC220719  ", "<BC>YYMMDD")),
            Map.entry("case12", new Linkage("2022-00-15", "YYYY-MM-DD")),
            Map.entry("case13", new Linkage("2022-13-15", "YYYY-MM-DD")),
            Map.entry("case14", new Linkage("2022XYZ19 ", "YYYYMMMDD ")),
            Map.entry("case15", new Linkage("2022-07-19", "QQQQ-MM-DD")),
            Map.entry("case16", new Linkage("2022-07-19", "YYYY-MM-YY")),
            Map.entry("case17", new Linkage("2022-07-19", "<AD-MM-DD ")),
            Map.entry("case18", new Linkage("ABCD-EF-GH", "YYYY-MM-DD")),
            Map.entry("case19", new Linkage("          ", "YYYY-MM-DD")),
            Map.entry("case20", new Linkage("0000-01-01", "YYYY-MM-DD")));

    /**
     * The two parameters one case calls the subprogram with.
     *
     * @param lsDate       {@code LS-DATE PIC X(10)} - the date to test, exactly ten characters
     * @param lsDateFormat {@code LS-DATE-FORMAT PIC X(10)} - the picture string, exactly ten
     *                     characters
     */
    private record Linkage(String lsDate, String lsDateFormat) {

        /** Validates both widths where they are declared, so a typo cannot reach a comparison. */
        private Linkage {
            if (lsDate.length() != DateUtilityJob.LS_DATE_LENGTH) {
                throw new IllegalArgumentException("LS-DATE is PIC X(10), so '" + lsDate + "' must be "
                        + DateUtilityJob.LS_DATE_LENGTH + " characters, not " + lsDate.length()
                        + ". Write the trailing spaces out - the declared width changes the outcome.");
            }
            if (lsDateFormat.length() != DateUtilityJob.LS_DATE_FORMAT_LENGTH) {
                throw new IllegalArgumentException("LS-DATE-FORMAT is PIC X(10), so '" + lsDateFormat
                        + "' must be " + DateUtilityJob.LS_DATE_FORMAT_LENGTH + " characters, not "
                        + lsDateFormat.length() + ". A longer mask is truncated by the receiving move "
                        + "and then reports a different feedback token than it appears to ask for.");
            }
        }
    }

    // =================================================================================================
    //  SUPPORTING CONSTANTS
    // =================================================================================================

    /** {@code Vstring-length PIC S9(4) BINARY} ({@code :26}) occupies a halfword - two bytes. */
    private static final int VSTRING_LENGTH_BYTES = 2;

    /** How many {@code Vstring-text} bytes survive the group move at {@code :122}: ten less two. */
    private static final int GROUP_MOVE_SURVIVING_TEXT_BYTES = 8;

    /** Masks a shifted halfword down to one byte without sign extension. */
    private static final int BYTE_MASK = 0xFF;

    /** {@code WS-DATE PIC X(10) VALUE SPACES}, written out so {@code :91} is visible as a move. */
    private static final String TEN_SPACES = "          ";

    /** The package every Spring Batch type lives under, matched by name so none is imported. */
    private static final String SPRING_BATCH_PACKAGE = "org.springframework.batch.";

    /** A bean-defining configuration class is the other way a {@code Job} bean could originate. */
    private static final String CONFIGURATION_ANNOTATION =
            "org.springframework.context.annotation.Configuration";

    /** A {@code @Bean} factory method is the other half of that same route. */
    private static final String BEAN_ANNOTATION = "org.springframework.context.annotation.Bean";

    // =================================================================================================
    //  THE GATE
    // =================================================================================================

    /**
     * The twenty cases, loaded from {@code parity/CSUTLDTC/case01.json} through {@code case20.json}.
     *
     * <p>{@link ParityHarness#casesOf(String)} enforces the count itself and refuses a directory
     * holding anything the twenty names do not cover, so a {@code case21.json} or a {@code case7.json}
     * fails here by name rather than being silently skipped.
     *
     * @return the twenty cases in ascending case order
     */
    private static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    /**
     * The gate: every one of the twenty cases must produce a diff count of zero.
     *
     * <p>The unit is reached the way the requirement demands - constructed through its constructor and
     * called as a plain object. There is no {@code JobLauncher}, no job repository, no application
     * context and no HTTP layer between this assertion and the code it asserts on. The charset comes
     * from the harness rather than from a platform default, and it is the code page the case's own
     * bytes are compared under.
     *
     * <p>The adapter reads exactly two things from the invocation: the case identifier, so it can look
     * up the two {@code LINKAGE} parameters, and the charset. It never sees the expectation, which is
     * what stops a unit reporting the expected answer back and passing by construction.
     *
     * @param parityCase one of the twenty cases
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("every case diffs to zero against its statically derived 80-byte expectation")
    void everyCaseDiffsToZero(ParityCase parityCase) {
        DiffResult diff = ParityHarness.usAscii().judge(parityCase, UnitKind.SERVICE, invocation -> {
            Linkage linkage = linkageFor(invocation.caseId());
            DateValidationResult result = new DateUtilityJob(invocation.charset())
                    .validateDate(linkage.lsDate(), linkage.lsDateFormat());
            // :97 MOVE WS-MESSAGE TO LS-RESULT, then :98 MOVE WS-SEVERITY-N TO RETURN-CODE. The
            // recorder is used rather than a hand-built outcome so that anything recorded before a
            // failure survives it - CSUTLDTC never abends, but the shape stays uniform across the 28.
            return invocation.recorder()
                    .message(new EmittedMessage(MessageChannel.WS_MESSAGE_80, result.message()))
                    .returnCode(result.returnCode())
                    .build();
        });

        assertThat(diff.count())
                .as("%s must diff to zero. The gate is a diff count of zero across all %d cases, so "
                        + "one difference on one case leaves the module incomplete.%n%s",
                        parityCase.caseId(), EXPECTED_CASE_COUNT, diff.render())
                .isZero();
    }

    /**
     * The set is exactly twenty - stated here as well as enforced by the loader, because the count is
     * the gate and a silently short set satisfies its wording while proving almost nothing.
     */
    @Test
    @DisplayName("the program declares exactly 20 cases, case01 through case20")
    void theProgramDeclaresExactlyTwentyCases() {
        List<ParityCase> loaded = cases();

        assertThat(loaded)
                .as("CSUTLDTC must declare exactly %d parity cases; a shorter set is not a smaller "
                        + "gate, it is a gate that passes without asking the questions",
                        EXPECTED_CASE_COUNT)
                .hasSize(EXPECTED_CASE_COUNT);

        List<String> caseIds = new ArrayList<>(loaded.size());
        for (ParityCase parityCase : loaded) {
            caseIds.add(parityCase.caseId());
            assertThat(parityCase.program())
                    .as("every case in parity/%s/ must name that program", PROGRAM)
                    .isEqualTo(PROGRAM);
        }
        assertThat(caseIds).containsExactlyElementsOf(LINKAGE.keySet().stream().sorted().toList());
    }

    /**
     * Every case is a {@link UnitKind#SERVICE} case that seeds nothing, writes nothing and takes no
     * job parameter - the shape that follows from {@code CSUTLDTC} having no {@code SELECT}, no
     * {@code EXEC CICS} and no {@code EXEC PGM=}.
     *
     * <p>This is not paperwork. A case that seeded a dataset would be describing a program that opens
     * a file, and one that declared a job parameter would be describing a program a JCL step invokes;
     * either would misdescribe this one, and both are refused here rather than left to a reader to
     * notice.
     */
    @Test
    @DisplayName("every case is a SERVICE case that seeds no dataset, writes nothing and paints no screen")
    void everyCaseIsAServiceCaseWithNoDatasetNoJobParameterAndNoScreen() {
        for (ParityCase parityCase : cases()) {
            assertThat(parityCase.unitKind())
                    .as("%s: CSUTLDTC is invoked as the @Service it became, never as a batch job",
                            parityCase.caseId())
                    .isEqualTo(UnitKind.SERVICE);
            assertThat(parityCase.inputs())
                    .as("%s: CSUTLDTC declares no SELECT and opens no file, so a seeded dataset would "
                            + "describe a different program", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.jobParameters())
                    .as("%s: no EXEC PGM= anywhere invokes CSUTLDTC, so there is no PARM to parse",
                            parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.screenRequest())
                    .as("%s: CSUTLDTC issues no EXEC CICS and owns no BMS map", parityCase.caseId())
                    .isNull();
            assertThat(parityCase.expectedResponse())
                    .as("%s: a called subprogram returns bytes, not a screen", parityCase.caseId())
                    .isNull();
            assertThat(parityCase.expectedWrites())
                    .as("%s: CSUTLDTC writes no record", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedFinalState())
                    .as("%s: with nothing seeded there is no final state to state",
                            parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedMessages())
                    .as("%s: the whole observable output is the one 80-byte LS-RESULT line",
                            parityCase.caseId())
                    .hasSize(1);
            assertThat(parityCase.expectedMessages().get(0).channel())
                    .as("%s: LS-RESULT is PIC X(80), so the line belongs on the 80-byte channel and "
                            + "not on the 78-byte screen channel", parityCase.caseId())
                    .isEqualTo(MessageChannel.WS_MESSAGE_80);
        }
    }

    /**
     * The drift guard between the case files and {@link #LINKAGE}.
     *
     * <p>Because {@link ParityCase} has no member able to carry a {@code CALL} parameter, the two
     * inputs live in this class and are restated in each case file's {@code description}. That is only
     * safe if the two cannot diverge, so each description is required to quote the exact pair the case
     * is actually run with. Change one without the other and this fails, naming the case.
     */
    @Test
    @DisplayName("each case description quotes the exact LS-DATE and LS-DATE-FORMAT it is run with")
    void eachCaseDescriptionStatesTheLinkageParametersItIsRunWith() {
        for (ParityCase parityCase : cases()) {
            Linkage linkage = linkageFor(parityCase.caseId());
            assertThat(parityCase.description())
                    .as("%s must quote LS-DATE='%s' so the case file and the LINKAGE table it is run "
                            + "from cannot drift apart", parityCase.caseId(), linkage.lsDate())
                    .contains("LS-DATE='" + linkage.lsDate() + "'");
            assertThat(parityCase.description())
                    .as("%s must quote LS-DATE-FORMAT='%s' for the same reason",
                            parityCase.caseId(), linkage.lsDateFormat())
                    .contains("LS-DATE-FORMAT='" + linkage.lsDateFormat() + "'");
        }
    }

    /**
     * Looks up a case's two parameters, failing loudly rather than defaulting.
     *
     * @param caseId the case identifier the harness supplied
     * @return the pair to call the subprogram with
     */
    private static Linkage linkageFor(String caseId) {
        Linkage linkage = LINKAGE.get(caseId);
        assertThat(linkage)
                .as("case %s has no LS-DATE / LS-DATE-FORMAT pair declared in this test class. Every "
                        + "one of the %d cases needs one, because CSUTLDTC's inputs are CALL "
                        + "parameters rather than seeded datasets and there is nowhere else for them "
                        + "to come from.", caseId, EXPECTED_CASE_COUNT)
                .isNotNull();
        return linkage;
    }

    // =================================================================================================
    //  THE EVALUATE - ORDER, EXCLUSIVITY AND COVERAGE
    // =================================================================================================

    /**
     * The transcription itself: ten arms, {@code WHEN OTHER} last, every result exactly fifteen
     * characters once moved, every named token distinct, and each severity and message number equal to
     * the two halfwords at the front of its own {@code 88}-level {@code VALUE}.
     *
     * <p>Deriving the numbers from the hex rather than asserting both independently is the point. If
     * the transcription of {@code X'000309D159C3C5C5'} were wrong, an assertion that 2513 equals 2513
     * would still pass; an assertion that 2513 equals {@code 0x09D1} would not.
     */
    @Test
    @DisplayName("the ten EVALUATE arms are transcribed in source order with WHEN OTHER last")
    void theTenArmsAreTranscribedInSourceOrderWithWhenOtherLast() {
        assertThat(ARMS)
                .as("the EVALUATE TRUE at :128-:149 has nine named WHENs plus WHEN OTHER")
                .hasSize(10);

        Set<String> tokens = new LinkedHashSet<>();
        Set<String> resultTexts = new LinkedHashSet<>();
        for (int index = 0; index < ARMS.size(); index++) {
            EvaluateArm arm = ARMS.get(index);
            assertThat(arm.ordinal())
                    .as("arm at list position %d must be arm %d - the list order IS the EVALUATE "
                            + "order, because the first matching WHEN wins", index, index + 1)
                    .isEqualTo(index + 1);
            assertThat(arm.paddedResultText())
                    .as("arm %d moves '%s' into WS-RESULT PIC X(15), which pads it to fifteen",
                            arm.ordinal(), arm.resultText())
                    .hasSize(WS_RESULT.length());
            assertThat(resultTexts.add(arm.paddedResultText()))
                    .as("arm %d shares its result text with an earlier arm, so the two would be "
                            + "indistinguishable in the 80-byte message", arm.ordinal())
                    .isTrue();

            if (arm.reachable()) {
                assertThat(tokens.add(arm.token()))
                        .as("arm %d repeats a feedback token already declared. The nine 88-levels are "
                                + "mutually exclusive precisely because their VALUEs differ, and that "
                                + "exclusivity is what makes 'first match wins' observable at all",
                                arm.ordinal())
                        .isTrue();
                assertThat(Integer.parseInt(arm.severityCode()))
                        .as("arm %d (%s): severity must equal the first halfword of %s",
                                arm.ordinal(), arm.conditionName(), arm.token())
                        .isEqualTo(halfword(arm.token(), 0));
                assertThat(Integer.parseInt(arm.messageNumber()))
                        .as("arm %d (%s): message number must equal the second halfword of %s",
                                arm.ordinal(), arm.conditionName(), arm.token())
                        .isEqualTo(halfword(arm.token(), 1));
            }
        }

        EvaluateArm last = ARMS.get(ARMS.size() - 1);
        assertThat(last.conditionName())
                .as("WHEN OTHER must be last: it is the default rather than a match, so moving it "
                        + "earlier would capture tokens the named arms own")
                .isEqualTo("WHEN OTHER");
        assertThat(last.reachable())
                .as("WHEN OTHER is selected only by a token no 88-level names, so no input reaches it")
                .isFalse();
    }

    /**
     * Every reachable arm is actually selected by its declared input, and no other arm is - which is
     * how "first match wins" is observed rather than merely asserted.
     *
     * <p>Run through {@code validateDate} directly, so the whole guard chain, the numeric overlays and
     * the fifteen-character move are all in the path. Nine arms is nine of the ten branches of the
     * {@code EVALUATE}, which is most of the branch surface of the {@code util} package in one test.
     */
    @Test
    @DisplayName("each reachable arm is selected by its own input and by no other arm's")
    void eachReachableArmIsSelectedByItsOwnInputAndNoOther() {
        Charset charset = DateUtilityJob.DEFAULT_MESSAGE_CHARSET;

        for (EvaluateArm arm : ARMS) {
            if (!arm.reachable()) {
                continue;
            }
            DateValidationResult result = new DateUtilityJob(charset)
                    .validateDate(arm.lsDate(), arm.lsDateFormat());

            assertThat(result.result())
                    .as("arm %d (%s) must be selected by LS-DATE='%s' with mask '%s'",
                            arm.ordinal(), arm.conditionName(), arm.lsDate(), arm.lsDateFormat())
                    .isEqualTo(arm.paddedResultText());
            assertThat(result.severityCode())
                    .as("arm %d writes its severity through WS-SEVERITY-N at :123", arm.ordinal())
                    .isEqualTo(arm.severityCode());
            assertThat(result.messageNumber())
                    .as("arm %d writes its message number through WS-MSG-NO-N at :124", arm.ordinal())
                    .isEqualTo(arm.messageNumber());

            for (EvaluateArm other : ARMS) {
                if (other.ordinal() == arm.ordinal()) {
                    continue;
                }
                assertThat(result.result())
                        .as("the input for arm %d must not also select arm %d (%s) - the arms are "
                                + "mutually exclusive", arm.ordinal(), other.ordinal(),
                                other.conditionName())
                        .isNotEqualTo(other.paddedResultText());
            }
        }
    }

    /**
     * All twenty cases together cover every arm the byte contract can reach, and the four cases the
     * plan calls out by name land where it says they do.
     */
    @Test
    @DisplayName("the 20 cases cover all nine reachable arms, including 00 and 13 months and 2513")
    void theTwentyCasesCoverEveryReachableArm() {
        Charset charset = DateUtilityJob.DEFAULT_MESSAGE_CHARSET;
        Set<String> observedResults = new LinkedHashSet<>();
        Set<Integer> observedReturnCodes = new LinkedHashSet<>();

        for (ParityCase parityCase : cases()) {
            Linkage linkage = linkageFor(parityCase.caseId());
            DateValidationResult result = new DateUtilityJob(charset)
                    .validateDate(linkage.lsDate(), linkage.lsDateFormat());
            observedResults.add(result.result());
            observedReturnCodes.add(result.returnCode());
        }

        for (EvaluateArm arm : ARMS) {
            if (!arm.reachable()) {
                continue;
            }
            assertThat(observedResults)
                    .as("no case reaches arm %d (%s). Every reachable arm needs one, because an arm "
                            + "no case reaches is an arm nothing is asserting about",
                            arm.ordinal(), arm.conditionName())
                    .contains(arm.paddedResultText());
        }

        // The four the plan names individually, so a later edit cannot quietly drop one.
        assertMessageNumber(charset, "case12", "2517");
        assertMessageNumber(charset, "case13", "2517");
        assertMessageNumber(charset, "case19", "2520");
        assertMessageNumber(charset, CASE_UNSUPPORTED_RANGE_BOUNDARY, "2513");

        assertThat(observedReturnCodes)
                .as("RETURN-CODE is the severity :98 moves, so the twenty cases must show both values: "
                        + "0 for a converted date and 3 for every named error")
                .containsExactlyInAnyOrder(0, 3);
    }

    /**
     * {@code MOVE WS-SEVERITY-N TO RETURN-CODE} at {@code :98}: zero when the date converted, three for
     * every one of the eight named errors, and never anything else.
     *
     * <p>Asserted against the token hex rather than against a repeated literal, so a wrong severity in
     * the transcription cannot agree with itself.
     */
    @Test
    @DisplayName("RETURN-CODE is 0 for a valid date and 3 for every named error")
    void returnCodeIsZeroForAValidDateAndThreeForEveryNamedError() {
        Charset charset = DateUtilityJob.DEFAULT_MESSAGE_CHARSET;

        for (EvaluateArm arm : ARMS) {
            if (!arm.reachable()) {
                continue;
            }
            DateValidationResult result = new DateUtilityJob(charset)
                    .validateDate(arm.lsDate(), arm.lsDateFormat());

            int severityFromToken = halfword(arm.token(), 0);
            assertThat(result.returnCode())
                    .as("arm %d (%s): RETURN-CODE is WS-SEVERITY-N, which :123 took from the first "
                            + "halfword of %s", arm.ordinal(), arm.conditionName(), arm.token())
                    .isEqualTo(severityFromToken);
            assertThat(result.returnCode())
                    .as("CSUTLDTC sets only 0 or 3; it is not one of the nine CALL 'CEE3ABD' sites and "
                            + "never sets 4, 8 or 12")
                    .isIn(0, 3);
            assertThat(Integer.parseInt(result.severityCode()))
                    .as("arm %d: the character view at offset 0 and the numeric RETURN-CODE are two "
                            + "readings of the same four bytes", arm.ordinal())
                    .isEqualTo(result.returnCode());
        }
    }

    /**
     * Arm 10 is unreachable through the byte contract, asserted as a property rather than left as an
     * untested gap.
     *
     * <p>{@code WHEN OTHER} at {@code :147-:148} is selected only by a feedback token that none of the
     * nine {@code 88}-levels names. A real Language Environment could return one; this translation
     * reproduces {@code CEEDAYS} as a total guard chain and never manufactures one, so no pair of
     * inputs can select it. The sweep below - every case input, every arm input and a cross product of
     * adversarial masks and dates - is required to land on one of the nine every time.
     *
     * <p>The arm's own mapping is still covered, just not from here:
     * {@code DateUtilityJobTest} lives in {@code com.vsergeychik.carddemo.util} and can reach the
     * package-private seam that exposes it. Reaching across from this package would mean widening that
     * seam or inventing a route through the validator, and inventing a route is what a manufactured
     * token was.
     */
    @Test
    @DisplayName("WHEN OTHER cannot be reached by any input - proved, not assumed")
    void theWhenOtherArmIsUnreachableThroughTheByteContract() {
        Charset charset = DateUtilityJob.DEFAULT_MESSAGE_CHARSET;
        DateUtilityJob service = new DateUtilityJob(charset);
        String whenOther = ARMS.get(ARMS.size() - 1).paddedResultText();

        Set<String> reachableResults = new LinkedHashSet<>();
        for (EvaluateArm arm : ARMS) {
            if (arm.reachable()) {
                reachableResults.add(arm.paddedResultText());
            }
        }

        List<String> masks = List.of(
                "YYYY-MM-DD", "DD/MM/YYYY", "YYYYMMDD  ", "YY-MM-DD  ", "YYYY-DDD  ",
                "YYYYMMMDD ", "<AD>YYMMDD", "<BC>YYMMDD", "<XX>YYMMDD", "          ",
                "YYYY      ", "QQQQ-MM-DD", "YYYY-MM-YY", "<AD-MM-DD ", "MM-DD-YYYY");
        List<String> dates = List.of(
                "2022-07-19", "0000-01-01", "9999-12-31", "1582-10-15", "1582-10-14",
                "2023-02-29", "2024-02-29", "2022-13-15", "2022-00-15", "2022-06-31",
                "ABCD-EF-GH", "          ", "----------", "0000000000", "2022JUL19 ",
                "XX220719  ", "BC220719  ", "AD220719  ", "2022-366  ", "2022-000  ");

        for (String mask : masks) {
            for (String date : dates) {
                DateValidationResult result = service.validateDate(date, mask);
                assertThat(result.result())
                        .as("LS-DATE='%s' with mask '%s' selected WHEN OTHER, so arm 10 IS reachable "
                                + "and this class must gain a twenty-first route to it", date, mask)
                        .isNotEqualTo(whenOther);
                assertThat(result.result())
                        .as("LS-DATE='%s' with mask '%s' produced a result text no arm of the "
                                + "EVALUATE declares, so the transcription in ARMS is incomplete",
                                date, mask)
                        .isIn(reachableResults);
            }
        }

        for (ParityCase parityCase : cases()) {
            Linkage linkage = linkageFor(parityCase.caseId());
            assertThat(service.validateDate(linkage.lsDate(), linkage.lsDateFormat()).result())
                    .as("%s selected WHEN OTHER", parityCase.caseId())
                    .isNotEqualTo(whenOther);
        }
    }

    // =================================================================================================
    //  THE 80 BYTES - GEOMETRY, THE TWO SHORT FILLER LITERALS, AND THE REDEFINES PAIRS
    // =================================================================================================

    /**
     * The thirteen storage spans of {@code 01 WS-MESSAGE} account for exactly eighty bytes, and the two
     * {@code REDEFINES} overlays account for none of them.
     *
     * <p>{@code 4 + 11 + 4 + 1 + 15 + 1 + 9 + 10 + 1 + 10 + 1 + 3 = 80}, and it is the eleven and the
     * nine that make it work - the literals inside them are ten and eight characters long.
     */
    @Test
    @DisplayName("the 13 storage spans of WS-MESSAGE sum to exactly 80 bytes; the 2 overlays add none")
    void theWsMessageLayoutAccountsForEveryOneOfTheEightyBytes() {
        assertThat(WS_MESSAGE_LAYOUT.recordLength())
                .as("LS-RESULT is PIC X(80) at :86, and WS-MESSAGE is what :97 moves into it")
                .isEqualTo(DateUtilityJob.LS_RESULT_LENGTH);

        int declared = 0;
        for (FieldSpan span : WS_MESSAGE_LAYOUT.storageSpans()) {
            declared += span.length();
        }
        assertThat(declared)
                .as("the storage spans must tile the record exactly; a dropped trailing FILLER is the "
                        + "usual cause of a short total and it shifts nothing, it merely truncates")
                .isEqualTo(DateUtilityJob.LS_RESULT_LENGTH);
        assertThat(WS_MESSAGE_LAYOUT.storageSpans()).hasSize(13);
        assertThat(WS_MESSAGE_LAYOUT.redefinitions())
                .as("WS-SEVERITY-N at :44 and WS-MSG-NO-N at :47 are alternative views of storage the "
                        + "layout has already counted, so they contribute no byte of their own")
                .hasSize(2);

        // The two short literals, which is where this layout is actually fragile.
        assertThat(FILLER_MESG_CODE.length())
                .as("the FILLER at :45 is PIC X(11)")
                .isEqualTo(11);
        assertThat(FILLER_MESG_CODE.initialValue())
                .as("its VALUE is 'Mesg Code:', ten characters, so the eleventh byte is a pad")
                .hasSize(10);
        assertThat(FILLER_TST_DATE.length())
                .as("the FILLER at :51 is PIC X(09)")
                .isEqualTo(9);
        assertThat(FILLER_TST_DATE.initialValue())
                .as("its VALUE is 'TstDate:', eight characters, so the ninth byte is a pad")
                .hasSize(8);
        assertThat(FILLER_MASK_USED.initialValue())
                .as("'Mask used:' fills its PIC X(10) exactly, which is why the other two are easy to "
                        + "get wrong")
                .hasSize(FILLER_MASK_USED.length());
    }

    /**
     * The width proof. Shortening either short {@code FILLER}'s span to the length of the literal it
     * holds - dropping the pad - is refused by the layout's own self-check, because the record then
     * fails to reach eighty bytes.
     *
     * <p>This is the executable form of the reason those two spans matter. Every byte after a shortened
     * {@code FILLER} shifts left by one, so {@code WS-MSG-NO} would be read from the wrong offset and
     * the callers' comparison against {@code '2513'} would silently stop matching. The self-check turns
     * that into an immediate failure at the point the layout is declared.
     */
    @Test
    @DisplayName("shortening either short FILLER's pad is refused - the record no longer reaches 80")
    void shorteningEitherShortFillerLiteralsPadBreaksTheTotalWidth() {
        // 'Mesg Code:' declared in a PIC X(10) rather than PIC X(11): every following span keeps its
        // declared offset, so the layout now shows a one-byte gap where the pad used to be.
        assertThatIllegalArgumentException()
                .as("a 'Mesg Code:' FILLER of 10 instead of 11 must be refused")
                .isThrownBy(() -> RecordLayout.of(
                        DateUtilityJob.LS_RESULT_LENGTH,
                        WS_SEVERITY,
                        WS_SEVERITY_N,
                        FieldSpan.filler(4, 10, "Mesg Code:"),
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
                        FILLER_TRAILING));

        // 'TstDate:' declared in a PIC X(08) rather than PIC X(09) - the same defect, one span later.
        assertThatIllegalArgumentException()
                .as("a 'TstDate:' FILLER of 8 instead of 9 must be refused")
                .isThrownBy(() -> RecordLayout.of(
                        DateUtilityJob.LS_RESULT_LENGTH,
                        WS_SEVERITY,
                        WS_SEVERITY_N,
                        FILLER_MESG_CODE,
                        WS_MSG_NO,
                        WS_MSG_NO_N,
                        FILLER_AFTER_MSG_NO,
                        WS_RESULT,
                        FILLER_AFTER_RESULT,
                        FieldSpan.filler(36, 8, "TstDate:"),
                        WS_DATE,
                        FILLER_AFTER_DATE,
                        FILLER_MASK_USED,
                        WS_DATE_FMT,
                        FILLER_AFTER_FMT,
                        FILLER_TRAILING));

        // And dropping the trailing FILLER entirely, which is the failure the self-check names.
        assertThatIllegalArgumentException()
                .as("omitting the trailing PIC X(03) FILLER at :57 must be refused")
                .isThrownBy(() -> RecordLayout.of(
                        DateUtilityJob.LS_RESULT_LENGTH,
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
                        FILLER_AFTER_FMT));
    }

    /**
     * The two {@code REDEFINES} pairs are two typed views of one span, exercised in both directions.
     *
     * <p>{@code :123} and {@code :124} write through the numeric views, which left-zero-fill to four
     * digits; {@code :97} hands the character views to the caller and {@code :98} reads the numeric one
     * back into {@code RETURN-CODE}. Both readings must describe the same four bytes, and the service's
     * own output must agree with them.
     */
    @Test
    @DisplayName("WS-SEVERITY/WS-SEVERITY-N and WS-MSG-NO/WS-MSG-NO-N are two views of one span")
    void theTwoRedefinesPairsAreTwoViewsOfOneSpan() {
        assertThat(WS_SEVERITY_N.offset()).isEqualTo(WS_SEVERITY.offset());
        assertThat(WS_SEVERITY_N.length()).isEqualTo(WS_SEVERITY.length());
        assertThat(WS_SEVERITY_N.redefinition()).isTrue();
        assertThat(WS_SEVERITY.redefinition()).isFalse();
        assertThat(WS_MSG_NO_N.offset()).isEqualTo(WS_MSG_NO.offset());
        assertThat(WS_MSG_NO_N.length()).isEqualTo(WS_MSG_NO.length());
        assertThat(WS_MSG_NO_N.redefinition()).isTrue();
        assertThat(WS_MSG_NO.redefinition()).isFalse();

        FixedWidthCodec codec = new FixedWidthCodec(DateUtilityJob.DEFAULT_MESSAGE_CHARSET);
        for (EvaluateArm arm : ARMS) {
            if (!arm.reachable()) {
                continue;
            }
            FixedWidthRecord wsMessage = codec.newRecord(WS_MESSAGE_LAYOUT);
            codec.writePic9(wsMessage, WS_SEVERITY_N, halfword(arm.token(), 0));   // :123
            codec.writePic9(wsMessage, WS_MSG_NO_N, halfword(arm.token(), 1));     // :124

            assertThat(codec.readPicX(wsMessage, WS_SEVERITY))
                    .as("arm %d: the character view of the severity span", arm.ordinal())
                    .isEqualTo(arm.severityCode());
            assertThat(codec.readPic9AsInt(wsMessage, WS_SEVERITY_N))
                    .as("arm %d: the numeric view of the very same four bytes", arm.ordinal())
                    .isEqualTo(halfword(arm.token(), 0));
            assertThat(codec.readPicX(wsMessage, WS_MSG_NO))
                    .as("arm %d: the character view of the message-number span", arm.ordinal())
                    .isEqualTo(arm.messageNumber());
            assertThat(codec.readPic9AsInt(wsMessage, WS_MSG_NO_N))
                    .as("arm %d: the numeric view of the very same four bytes", arm.ordinal())
                    .isEqualTo(halfword(arm.token(), 1));
        }
    }

    /**
     * The end-to-end proof of the fixed-width machinery: the eighty-byte image is reassembled here from
     * the declared spans, span by span, and must equal what the service produced.
     *
     * <p>This is the assertion the plan's "prove the fixed-width machinery before trusting it on the
     * 300- and 350-byte records" reduces to. Two independent constructions of the same eighty bytes -
     * one by the service, one from this transcription of {@code :42-:57} - have to agree, including the
     * two padded literals, the two zero-filled numeric overlays, the fifteen-character result and the
     * two non-character bytes the group move at {@code :122} plants at offsets 45 and 46.
     */
    @Test
    @DisplayName("the 80-byte image reassembled from the declared spans equals what the service emits")
    void theEightyByteImageIsAssembledFromTheDeclaredSpans() {
        Charset charset = DateUtilityJob.DEFAULT_MESSAGE_CHARSET;
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        DateUtilityJob service = new DateUtilityJob(charset);

        for (ParityCase parityCase : cases()) {
            Linkage linkage = linkageFor(parityCase.caseId());
            DateValidationResult result = service.validateDate(linkage.lsDate(),
                    linkage.lsDateFormat());
            String reassembled = assembleWsMessage(codec, result.severityCode(),
                    result.messageNumber(), result.result(), linkage.lsDate(),
                    linkage.lsDateFormat());

            assertThat(result.message())
                    .as("%s: the service's LS-RESULT must equal the image reassembled from the "
                            + "WS-MESSAGE declaration at :42-:57", parityCase.caseId())
                    .isEqualTo(reassembled);
            assertThat(result.message())
                    .as("%s: LS-RESULT is PIC X(80)", parityCase.caseId())
                    .hasSize(DateUtilityJob.LS_RESULT_LENGTH);
            assertThat(result.messageBytes())
                    .as("%s: eighty characters must be eighty bytes in %s, which is what makes an "
                            + "offset-addressed record area meaningful", parityCase.caseId(), charset)
                    .hasSize(DateUtilityJob.LS_RESULT_LENGTH);
            assertThat(result.charset())
                    .as("%s: the code page is named by the caller and never defaulted",
                            parityCase.caseId())
                    .isEqualTo(charset);

            // The group move at :122 is the one place the reported date is NOT the date supplied.
            String wsDateSpan = result.message()
                    .substring(WS_DATE.offset(), WS_DATE.endOffsetExclusive());
            assertThat(wsDateSpan.substring(VSTRING_LENGTH_BYTES))
                    .as("%s: only the first %d bytes of Vstring-text survive the group move into "
                            + "WS-DATE PIC X(10)", parityCase.caseId(),
                            GROUP_MOVE_SURVIVING_TEXT_BYTES)
                    .isEqualTo(linkage.lsDate().substring(0, GROUP_MOVE_SURVIVING_TEXT_BYTES));
            assertThat(result.message()
                    .substring(WS_DATE_FMT.offset(), WS_DATE_FMT.endOffsetExclusive()))
                    .as("%s: WS-DATE-FMT is filled at :113 and never overwritten afterwards",
                            parityCase.caseId())
                    .isEqualTo(linkage.lsDateFormat());

            // Pure computation, so the SAME instance called again with the same parameters must
            // produce the same eighty bytes. The service is a singleton in the container and is
            // reached four times from two programs, and each caller performs MOVE SPACES TO
            // CSUTLDTC-RESULT before calling - which only means anything if the result is fresh
            // rather than a view onto retained state.
            DateValidationResult again = service.validateDate(linkage.lsDate(),
                    linkage.lsDateFormat());
            assertThat(again.message())
                    .as("%s: a second call on the same instance must return the same 80 bytes - "
                            + "nothing in WORKING-STORAGE survives between invocations",
                            parityCase.caseId())
                    .isEqualTo(result.message());
            assertThat(again.returnCode()).isEqualTo(result.returnCode());
            assertThat(again).isNotSameAs(result);
            assertThat(again.messageBytes())
                    .as("%s: messageBytes() must hand out a copy, so a caller mutating it cannot "
                            + "reach the value another caller holds", parityCase.caseId())
                    .isNotSameAs(again.messageBytes())
                    .isEqualTo(result.messageBytes());
        }
    }

    // =================================================================================================
    //  GATE G12 - THE NAME SAYS JOB, THE UNIT IS A SERVICE
    // =================================================================================================

    /**
     * No Spring Batch {@code Job}, {@code Step} or {@code Tasklet} originates from {@code DateUtilityJob},
     * and the type is a {@link Service}.
     *
     * <p>{@code CSUTLDTC} is {@code CALL}ed from two online programs and appears in no {@code EXEC PGM=}
     * anywhere, so a Spring Batch job would be an invention rather than a translation. The class name
     * the migration mandates says otherwise and is kept anyway - the name comes from the plan, the
     * behaviour comes from the source - which is exactly why the behaviour needs asserting.
     *
     * <p>Spring Batch types are matched by package name rather than imported. Naming
     * {@code org.springframework.batch.core.Job} in an import would make this test depend on the very
     * artefact it exists to say is absent, and a rename or a repackage upstream would then break the
     * assertion instead of the assertion catching the regression.
     */
    @Test
    @DisplayName("no Spring Batch Job, Step or Tasklet originates from DateUtilityJob - it is a @Service")
    void noSpringBatchJobStepOrTaskletOriginatesFromThisType() {
        Class<?> unit = DateUtilityJob.class;

        assertThat(unit.getAnnotation(Service.class))
                .as("CSUTLDTC is a called subprogram, so its translation is a @Service - the shape the "
                        + "plan's gate requires despite the mandated ...Job class name")
                .isNotNull();

        for (Annotation annotation : unit.getAnnotations()) {
            String name = annotation.annotationType().getName();
            assertThat(name)
                    .as("DateUtilityJob carries @%s. A batch or configuration annotation here would "
                            + "make the class a source of Spring Batch beans", name)
                    .doesNotStartWith(SPRING_BATCH_PACKAGE)
                    .isNotEqualTo(CONFIGURATION_ANNOTATION);
        }

        assertThat(unit.getInterfaces())
                .as("DateUtilityJob implements no interface at all, so it cannot be a Tasklet, a Job "
                        + "or a StepExecutionListener")
                .isEmpty();
        assertThat(unit.getSuperclass())
                .as("DateUtilityJob extends nothing, so it inherits no batch behaviour either")
                .isEqualTo(Object.class);

        for (Method method : unit.getDeclaredMethods()) {
            assertThat(method.getReturnType().getName())
                    .as("%s.%s returns a Spring Batch type, so a Job, Step or Tasklet DOES originate "
                            + "from this class", unit.getSimpleName(), method.getName())
                    .doesNotStartWith(SPRING_BATCH_PACKAGE);
            for (Annotation annotation : method.getAnnotations()) {
                assertThat(annotation.annotationType().getName())
                        .as("%s.%s is annotated @%s; a @Bean factory method is the other way a batch "
                                + "bean could originate here", unit.getSimpleName(), method.getName(),
                                annotation.annotationType().getSimpleName())
                        .isNotEqualTo(BEAN_ANNOTATION)
                        .doesNotStartWith(SPRING_BATCH_PACKAGE);
            }
        }

        // The unit is reachable as a plain object with no context, which is what keeps the twenty cases
        // free of a JobLauncher and free of HTTP.
        assertThat(new DateUtilityJob(DateUtilityJob.DEFAULT_MESSAGE_CHARSET)
                .validateDate("2022-07-19", "YYYY-MM-DD").returnCode())
                .as("constructing the service directly and calling it must work with no Spring context")
                .isZero();
    }

    // =================================================================================================
    //  HELPERS
    // =================================================================================================

    /**
     * A COBOL {@code MOVE} into an alphanumeric receiver: left justified, space-padded on the right,
     * truncated on the right.
     *
     * @param source the sending item
     * @param width  the receiver's declared width
     * @return exactly {@code width} characters
     */
    private static String movePicX(String source, int width) {
        if (source.length() >= width) {
            return source.substring(0, width);
        }
        return source + " ".repeat(width - source.length());
    }

    /**
     * One halfword of a feedback token, read out of the sixteen hex digits of its {@code VALUE} clause.
     *
     * <p>{@code FEEDBACK-TOKEN-VALUE} ({@code :61}) overlays {@code SEVERITY PIC S9(4) BINARY} and
     * {@code MSG-NO PIC S9(4) BINARY} ({@code :72-:73}) onto its first four bytes, so halfword 0 is the
     * severity and halfword 1 is the message number.
     *
     * @param token    the sixteen hex digits, exactly as the {@code 88}-level declares them
     * @param position 0 for the severity, 1 for the message number
     * @return the halfword's value
     */
    private static int halfword(String token, int position) {
        int firstDigit = position * 4;
        return Integer.parseInt(token.substring(firstDigit, firstDigit + 4), 16);
    }

    /**
     * Asserts one case's message number, so the four the plan names are pinned individually.
     *
     * @param charset       the code page to construct the service with
     * @param caseId        the case to run
     * @param messageNumber the four characters expected at offset 15
     */
    private static void assertMessageNumber(Charset charset, String caseId, String messageNumber) {
        Linkage linkage = linkageFor(caseId);
        assertThat(new DateUtilityJob(charset)
                .validateDate(linkage.lsDate(), linkage.lsDateFormat()).messageNumber())
                .as("%s must report message %s", caseId, messageNumber)
                .isEqualTo(messageNumber);
    }

    /**
     * Reassembles {@code 01 WS-MESSAGE} from its declared spans, in the order {@code A000-MAIN}
     * fills them.
     *
     * <p>Independent of the service under test: the literals come from the {@code FILLER} spans'
     * {@code VALUE} clauses through {@link FixedWidthCodec#newRecord(RecordLayout)}, the numeric fields
     * are written through their {@code REDEFINES} views exactly as {@code :123-:124} do, and the group
     * move at {@code :122} is reproduced explicitly.
     *
     * @param codec         the codec whose code page the record is rendered in
     * @param severityCode  the four severity characters
     * @param messageNumber the four message-number characters
     * @param resultText    the result text, already at its fifteen-character width
     * @param lsDate        {@code LS-DATE} as the caller passed it
     * @param lsDateFormat  {@code LS-DATE-FORMAT} as the caller passed it
     * @return exactly eighty characters
     */
    private static String assembleWsMessage(FixedWidthCodec codec,
                                            String severityCode,
                                            String messageNumber,
                                            String resultText,
                                            String lsDate,
                                            String lsDateFormat) {
        // :90 INITIALIZE WS-MESSAGE - space-fills the named character fields and writes the three
        // literal FILLERs from their VALUE clauses. :91 MOVE SPACES TO WS-DATE is redundant after it
        // and is reproduced anyway, because the source does it.
        FixedWidthRecord wsMessage = codec.newRecord(WS_MESSAGE_LAYOUT);
        codec.writePicX(wsMessage, WS_DATE, TEN_SPACES);

        // :105-:108 the declared width of LS-DATE reaches Vstring-length, and the ten characters reach
        // both Vstring-text and WS-DATE. :109-:113 does the same for the mask and WS-DATE-FMT.
        String vstringText = codec.movePicX(lsDate, DateUtilityJob.LS_DATE_LENGTH);
        codec.writePicX(wsMessage, WS_DATE, vstringText);
        codec.writePicX(wsMessage, WS_DATE_FMT,
                codec.movePicX(lsDateFormat, DateUtilityJob.LS_DATE_FORMAT_LENGTH));

        // :122 MOVE WS-DATE-TO-TEST TO WS-DATE - the group move, which overwrites what :108 put there.
        wsMessage.writeBytes(WS_DATE.offset(), groupMoveImage(codec, vstringText));

        // :123-:124 through the numeric REDEFINES views, which left-zero-fill to four digits.
        codec.writePic9(wsMessage, WS_SEVERITY_N, Integer.parseInt(severityCode));
        codec.writePic9(wsMessage, WS_MSG_NO_N, Integer.parseInt(messageNumber));

        // :128-:149 the EVALUATE's chosen literal, moved into PIC X(15).
        codec.writePicX(wsMessage, WS_RESULT, resultText);

        // :97 MOVE WS-MESSAGE TO LS-RESULT.
        return wsMessage.readString(0, wsMessage.recordLength());
    }

    /**
     * The ten bytes {@code MOVE WS-DATE-TO-TEST TO WS-DATE} ({@code :122}) leaves behind.
     *
     * <p>{@code WS-DATE-TO-TEST} ({@code :25-:31}) is a halfword {@code Vstring-length} followed by
     * {@code Vstring-text}, so with a length of ten its storage image is twelve bytes. A group move into
     * an alphanumeric {@code PIC X(10)} receiver is left justified and truncated on the right, so the
     * halfword survives and only the first eight text bytes follow it. The halfword is derived from the
     * declared length {@code LENGTH OF} returned rather than written as a literal.
     *
     * @param codec       the codec supplying the code page for the text bytes
     * @param vstringText the ten characters held in {@code Vstring-text}
     * @return exactly ten bytes
     */
    private static byte[] groupMoveImage(FixedWidthCodec codec, String vstringText) {
        byte[] image = new byte[WS_DATE.length()];
        image[0] = (byte) ((DateUtilityJob.LS_DATE_LENGTH >> Byte.SIZE) & BYTE_MASK);
        image[1] = (byte) (DateUtilityJob.LS_DATE_LENGTH & BYTE_MASK);
        System.arraycopy(codec.encodeImage(vstringText, "VSTRING-TEXT OF WS-DATE-TO-TEST"), 0,
                image, VSTRING_LENGTH_BYTES, GROUP_MOVE_SURVIVING_TEXT_BYTES);
        return image;
    }
}
