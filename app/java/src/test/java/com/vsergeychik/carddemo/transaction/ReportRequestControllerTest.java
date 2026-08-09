package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.config.WebConfig;
import com.vsergeychik.carddemo.config.WebConfig.JobSubmissionProperties;
import com.vsergeychik.carddemo.transaction.DateParmReader.DateParm;
import com.vsergeychik.carddemo.transaction.ReportRequestController.InternalReaderJobSubmissionPort;
import com.vsergeychik.carddemo.transaction.ReportRequestController.JobSubmissionPort;
import com.vsergeychik.carddemo.transaction.ReportRequestController.ProgramState;
import com.vsergeychik.carddemo.transaction.ReportRequestController.WriteQueueOutcome;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestRequest;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestResponse;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import com.vsergeychik.carddemo.util.DateUtilityJob.DateValidationResult;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Proves {@link ReportRequestController} against {@code app/cbl/CORPT00C.cbl}, the 649-line CICS
 * program behind transaction {@code CR00}.
 *
 * <p>Three properties of that program shape every test here, and none of them is obvious from the
 * Java alone:
 * <ul>
 *   <li>{@code SEND-TRNRPT-SCREEN} ends {@code GO TO RETURN-TO-CICS} at line 580, so every
 *       {@code PERFORM SEND-TRNRPT-SCREEN} <strong>ends the task</strong>. A test that asserts on a
 *       message is therefore asserting on the last thing the program did, not on an intermediate
 *       state.</li>
 *   <li>The program writes an eighty-byte JCL stream to transient data queue {@code JOBS}. Its shape
 *       is not derived at run time - it is seventeen literal {@code PIC X(80)} items at lines 83 to
 *       125 with four date substitution points - so the stream is asserted <strong>byte for
 *       byte</strong>, 17 x 80 = 1,360 bytes, and line 15 is additionally asserted to parse through
 *       {@link DateParmReader}'s published layout, because it <em>is</em> that reader's record.</li>
 *   <li>Every date the program derives comes from {@code FUNCTION CURRENT-DATE}. The controller reads
 *       an injected {@link Clock} instead, so every date case here pins the clock and the expected
 *       bytes are exact rather than approximate.</li>
 * </ul>
 *
 * <p>The expected values are <strong>statically derived</strong> from the COBOL, the copybooks and the
 * JCL rather than captured from a legacy run, because the legacy programs cannot be executed in this
 * environment. That is the deviation recorded as risk R-A.
 */
@DisplayName("ReportRequestController - CORPT00C, transaction CR00, POST /api/reports")
class ReportRequestControllerTest {

    /** The AID token for {@code DFHENTER}, which is what {@code EVALUATE EIBAID} branches on first. */
    private static final String ENTER = "ENTER";

    /** {@code 2026-08-09T14:05:06Z} - a fixed instant, so {@code CURDATE} and {@code CURTIME} are exact. */
    private static final String FIXED_INSTANT = "2026-08-09T14:05:06Z";

    /** Midnight on the same day, for the cases that assert on dates rather than on the time header. */
    private static final String FIXED_MIDNIGHT = "2026-08-09T00:00:00Z";

    private final DateUtilityJob dateUtility = new DateUtilityJob();

    private final CapturingPort port = new CapturingPort();

    private ReportRequestController controllerAt(String instant) {
        return new ReportRequestController(dateUtility, port,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    // =================================================================================================
    // Shared builders. A request either carries a communication area whose CDEMO-PGM-CONTEXT is REENTER
    // - which is every request after the first - or carries none at all, which is EIBCALEN = 0.
    // =================================================================================================

    private static ReportRequestRequest reenter() {
        return ReportRequestRequest.empty()
                .withNavigationContext(
                        ReportRequestRequest.empty().navigationContext().withPgmReenter())
                .withAid(ENTER);
    }

    private static ReportRequestRequest customRequest(String sdtmm, String sdtdd, String sdtyyyy,
                                                     String edtmm, String edtdd, String edtyyyy) {
        return reenter().withCustom("Y").withSdtmm(sdtmm).withSdtdd(sdtdd).withSdtyyyy(sdtyyyy)
                .withEdtmm(edtmm).withEdtdd(edtdd).withEdtyyyy(edtyyyy).withConfirm("Y");
    }

    private static JobSubmissionProperties properties(Path root, Path destination) {
        return new JobSubmissionProperties("JOBS", "INREADER", 80, "FIXED", "UNBLOCKED", "MOD",
                root.toString(), destination.toString());
    }

    /** Right-space-pads to the eighty bytes every skeleton record occupies. */
    private static String pad(String text) {
        return text + " ".repeat(ReportRequestController.JCL_RECORD_LENGTH - text.length());
    }

    // =================================================================================================
    // The seventeen-line skeleton - app/cbl/CORPT00C.cbl:83-125
    // =================================================================================================

    @Nested
    @DisplayName("the seventeen eighty-byte skeleton records and their four substitution points")
    class TheSkeleton {

        @Test
        @DisplayName("the template is seventeen records of exactly eighty bytes")
        void theTemplateGeometryIsTheCopybookGeometry() {
            assertThat(ReportRequestController.JOB_DATA_TEMPLATE).hasSize(17);
            assertThat(ReportRequestController.JOB_LINE_COUNT).isEqualTo(17);
            assertThat(ReportRequestController.JOB_DATA_LENGTH).isEqualTo(1360);
            assertThat(ReportRequestController.JOB_DATA_TEMPLATE)
                    .allSatisfy(record -> assertThat(record).hasSize(80));
            assertThat(ReportRequestController.JOB_LINE_01)
                    .startsWith("//TRNRPT00 JOB 'TRAN REPORT'");
            assertThat(ReportRequestController.JOB_LINE_17)
                    .isEqualTo(ReportRequestController.EOF_MARKER_RECORD);
            assertThat(ReportRequestController.EOF_MARKER_TEXT).isEqualTo("/*EOF");
        }

        @Test
        @DisplayName("the four dates land where FILLER-1, FILLER-2 and FILLER-3 declare them")
        void theSubstitutionPointsAndTheQuoteColumns() {
            ProgramState state = new ProgramState();
            state.setParmStartDate1("2026-08-01");
            state.setParmEndDate1("2026-08-31");
            state.setParmStartDate2("2026-08-01");
            state.setParmEndDate2("2026-08-31");
            List<String> lines = controllerAt(FIXED_MIDNIGHT).jobLines(state);

            assertThat(lines).hasSize(17).allSatisfy(record -> assertThat(record).hasSize(80));
            // FILLER-1: X(18) prefix, X(10) date, then X(52) whose first byte is the closing quote.
            assertThat(lines.get(10)).isEqualTo(pad("PARM-START-DATE,C'2026-08-01'"));
            assertThat(lines.get(10).charAt(28)).isEqualTo('\'');
            // FILLER-2: X(16) prefix, X(10) date, then X(54) whose first byte is the closing quote.
            assertThat(lines.get(11)).isEqualTo(pad("PARM-END-DATE,C'2026-08-31'"));
            assertThat(lines.get(11).charAt(26)).isEqualTo('\'');
            // FILLER-3: X(10) + one space + X(10) + fifty-nine spaces.
            assertThat(lines.get(14)).isEqualTo(pad("2026-08-01 2026-08-31"));
            // The two SYMNAMES offsets are emitted as literals, matching app/jcl/TRANREPT.jcl.
            assertThat(lines.get(8)).isEqualTo(pad("TRAN-CARD-NUM,263,16,ZD"));
            assertThat(lines.get(9)).isEqualTo(pad("TRAN-PROC-DT,305,10,CH"));
            assertThat(lines.get(3)).contains("AWS.M2.CARDDEMO.PROC");
        }

        @Test
        @DisplayName("skeleton line fifteen is the record DateParmReader consumes")
        void lineFifteenRoundTripsThroughTheReadersLayout() {
            String record = ReportRequestController.dateParmRecord("2026-01-01", "2026-12-31");

            assertThat(record).hasSize(DateParmReader.RECORD_LENGTH);
            DateParm parsed = new DateParm(
                    record.substring(DateParmReader.START_DATE_OFFSET,
                            DateParmReader.START_DATE_OFFSET + DateParmReader.START_DATE_LENGTH),
                    record.substring(DateParmReader.SEPARATOR_OFFSET,
                            DateParmReader.SEPARATOR_OFFSET + DateParmReader.SEPARATOR_LENGTH),
                    record.substring(DateParmReader.END_DATE_OFFSET,
                            DateParmReader.END_DATE_OFFSET + DateParmReader.END_DATE_LENGTH));
            assertThat(parsed.startDate()).isEqualTo("2026-01-01");
            assertThat(parsed.endDate()).isEqualTo("2026-12-31");
            assertThat(parsed.receiverImage())
                    .isEqualTo(record.substring(0, DateParmReader.RECEIVER_LENGTH));
            assertThat(record.substring(DateParmReader.DISCARDED_TAIL_OFFSET)).isBlank();
        }

        @Test
        @DisplayName("JOB-LINES is subscripted from one, and outside 1..17 is a subscript error")
        void theOccursTableIsOneBased() {
            List<String> lines = ReportRequestController.JOB_DATA_TEMPLATE;

            assertThat(ReportRequestController.jobLine(lines, 1))
                    .isEqualTo(ReportRequestController.JOB_LINE_01);
            assertThat(ReportRequestController.jobLine(lines, 17))
                    .isEqualTo(ReportRequestController.JOB_LINE_17);
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> ReportRequestController.jobLine(lines, 0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> ReportRequestController.jobLine(lines, 18));
        }

        @Test
        @DisplayName("a mis-sized prefix or date is refused rather than padded into place")
        void malformedSkeletonPartsAreRefused() {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> ReportRequestController.symnamesLine("X", "2026-01-01", 52));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> ReportRequestController.symnamesLine(
                            ReportRequestController.SYMNAMES_START_DATE_PREFIX, "2026-01-011", 52));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> ReportRequestController.dateParmRecord("2026-01-011", "2026-12-31"));
        }

        @Test
        @DisplayName("the class-initialisation self-checks reject drift in either direction")
        void theSelfChecksActuallyGuard() {
            ReportRequestController.requireSkeletonWidths("80 ".repeat(17));
            assertThatIllegalStateException()
                    .isThrownBy(() -> ReportRequestController.requireSkeletonWidths("80 ".repeat(16)))
                    .withMessageContaining("02 JOB-DATA-1 declares 17 items of 80 bytes");

            ReportRequestController.requireDateParmLayout("0:10/10:1/11:10/21:59@80");
            assertThatIllegalStateException()
                    .isThrownBy(() -> ReportRequestController
                            .requireDateParmLayout("0:10/10:1/11:10/21:58@80"))
                    .withMessageContaining("DateParmReader consumes");

            ReportRequestController.requireMatchingProjections(17, 17);
            assertThatIllegalStateException()
                    .isThrownBy(() -> ReportRequestController.requireMatchingProjections(17, 16))
                    .withMessageContaining("has drifted");
        }
    }

    // =================================================================================================
    // FUNCTION INTEGER-OF-DATE and FUNCTION DATE-OF-INTEGER - app/cbl/CORPT00C.cbl:229
    // =================================================================================================

    @Nested
    @DisplayName("the two date intrinsics, hand-implemented as a day count from 1600-12-31")
    class TheDateIntrinsics {

        @Test
        @DisplayName("known day counts, both directions, and the undefined results")
        void theAnchorsAndTheUndefinedResults() {
            assertThat(ReportRequestController.integerOfDate(16010101)).isEqualTo(1);
            assertThat(ReportRequestController.integerOfDate(16011231)).isEqualTo(365);
            assertThat(ReportRequestController.integerOfDate(19000101)).isEqualTo(109208);
            assertThat(ReportRequestController.integerOfDate(20000101)).isEqualTo(145732);

            // Outside 1601-01-01..9999-12-31, or not a real date, the intrinsic is undefined and the
            // implementation reports zero rather than guessing.
            assertThat(ReportRequestController.integerOfDate(16001231)).isZero();
            assertThat(ReportRequestController.integerOfDate(100000101)).isZero();
            assertThat(ReportRequestController.integerOfDate(20260231)).isZero();

            assertThat(ReportRequestController.dateOfInteger(1)).isEqualTo(16010101);
            assertThat(ReportRequestController.dateOfInteger(365)).isEqualTo(16011231);
            assertThat(ReportRequestController.dateOfInteger(0)).isZero();
            assertThat(ReportRequestController.dateOfInteger(
                    ReportRequestController.DATE_OF_INTEGER_HIGHEST_ARGUMENT)).isEqualTo(99991231);
            assertThat(ReportRequestController.dateOfInteger(
                    ReportRequestController.DATE_OF_INTEGER_HIGHEST_ARGUMENT + 1)).isZero();
        }

        @Test
        @DisplayName("the standard-date form and its three parts")
        void theStandardDateForm() {
            assertThat(ReportRequestController.standardDate(2026, 8, 9)).isEqualTo(20260809);
            assertThat(ReportRequestController.yearOfStandardDate(20260809)).isEqualTo(2026);
            assertThat(ReportRequestController.monthOfStandardDate(20260809)).isEqualTo(8);
            assertThat(ReportRequestController.dayOfStandardDate(20260809)).isEqualTo(9);
        }

        @ParameterizedTest(name = "on {0} the monthly range is {1}")
        @CsvSource({
            "2026-01-15T00:00:00Z, 2026-01-01 2026-01-31",
            "2026-04-15T00:00:00Z, 2026-04-01 2026-04-30",
            "2024-02-15T00:00:00Z, 2024-02-01 2024-02-29",
            "2023-02-15T00:00:00Z, 2023-02-01 2023-02-28",
            "2026-12-15T00:00:00Z, 2026-12-01 2026-12-31"
        })
        @DisplayName("the composite yields the last day of the current month, December included")
        void theMonthEndDerivation(String instant, String expectedRange) {
            CapturingPort local = new CapturingPort();
            ProgramState state = new ReportRequestController(dateUtility, local,
                    Clock.fixed(Instant.parse(instant), ZoneOffset.UTC))
                    .mainPara(reenter().withMonthly("Y").withConfirm("Y"));

            assertThat(state.parmStartDate2() + " " + state.parmEndDate2()).isEqualTo(expectedRange);
            assertThat(local.records).hasSize(17);
        }
    }

    // =================================================================================================
    // FUNCTION NUMVAL-C and the normalise-in-place edit - app/cbl/CORPT00C.cbl:305-325
    // =================================================================================================

    @Nested
    @DisplayName("FUNCTION NUMVAL-C, and the normalise-then-revalidate order the source uses")
    class TheNumvalCIntrinsic {

        @ParameterizedTest(name = "NUMVAL-C(\"{0}\") = {1}")
        @CsvSource(quoteCharacter = '`', value = {
            "`07`, 7", "` 7`, 7", "`7 `, 7", "`+7`, 7", "`-7`, -7", "`7-`, -7",
            "`7CR`, -7", "`7DB`, -7", "`$ 1,234.56`, 1234.56", "`- $12`, -12",
            "`.5`, 0.5", "`1,2`, 12", "`12.`, 12", "`7+`, 7", "`$1,234,567.89`, 1234567.89"
        })
        @DisplayName("accepts what COBOL accepts")
        void theAcceptedForms(String image, String expected) {
            assertThat(ReportRequestController.numvalC(image)).isEqualByComparingTo(expected);
        }

        @ParameterizedTest(name = "NUMVAL-C(\"{0}\") is rejected and yields zero")
        @CsvSource(quoteCharacter = '`', value = {
            "`  `", "`ab`", "`$`", "`1a`", "`1.2.3`", "`-5-`", "`1,,2`", "`1,`", "`,5`", "`1.2,3`"
        })
        @DisplayName("rejects what COBOL rejects, and a rejection is zero")
        void theRejectedForms(String image) {
            assertThat(ReportRequestController.numvalC(image)).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("TEST-NUMVAL-C reports the offending character position, not merely a failure")
        void theConformanceCodes() {
            assertThat(ReportRequestController.testNumvalC("07"))
                    .isEqualTo(ReportRequestController.NUMVAL_CONFORMS);
            assertThat(ReportRequestController.testNumvalC("  ")).isEqualTo(3);
            assertThat(ReportRequestController.testNumvalC("ab")).isEqualTo(3);
            assertThat(ReportRequestController.testNumvalC("1a")).isEqualTo(2);
            assertThat(ReportRequestController.testNumvalC("1.2.3")).isEqualTo(4);
        }

        @Test
        @DisplayName("the result is stored back through PIC 99 and PIC 9999, low-order digits first")
        void theNormalisationInPlace() {
            ReportRequestController controller = controllerAt(FIXED_MIDNIGHT);

            assertThat(controller.computeIntoPic99(" 7")).isEqualTo("07");
            assertThat(controller.computeIntoPic99("7 ")).isEqualTo("07");
            // The receiver is unsigned, so the sign is dropped rather than stored.
            assertThat(controller.computeIntoPic99("-5")).isEqualTo("05");
            // A value too wide for PIC 99 keeps its low-order digits, which is what a COBOL store does.
            assertThat(controller.computeIntoPic99("123")).isEqualTo("23");
            assertThat(controller.computeIntoPic99("ab")).isEqualTo("00");
            assertThat(controller.computeIntoPic99("13")).isEqualTo("13");
            assertThat(controller.computeIntoPic9999("2026")).isEqualTo("2026");
            assertThat(controller.computeIntoPic9999(" 26")).isEqualTo("0026");
            assertThat(controller.computeIntoPic9999("abcd")).isEqualTo("0000");
        }

        @Test
        @DisplayName("the class test and the range test that follow the normalisation")
        void theRevalidation() {
            assertThat(ReportRequestController.isNumericClass("07")).isTrue();
            assertThat(ReportRequestController.isNumericClass(" 7")).isFalse();
            assertThat(ReportRequestController.isNumericClass("")).isFalse();
            assertThat(ReportRequestController.isNotValidTwoDigitPart("07", "12")).isFalse();
            assertThat(ReportRequestController.isNotValidTwoDigitPart("13", "12")).isTrue();
            assertThat(ReportRequestController.isNotValidTwoDigitPart("ab", "12")).isTrue();
            assertThat(ReportRequestController.isNotValidTwoDigitPart("31", "31")).isFalse();
            assertThat(ReportRequestController.isNotValidTwoDigitPart("32", "31")).isTrue();
        }

        @Test
        @DisplayName("STRING ... DELIMITED BY SPACE stops at the first space, and INTO truncates")
        void theStringVerbs() {
            assertThat(ReportRequestController.stringDelimitedBySpace("Monthly   "))
                    .isEqualTo("Monthly");
            assertThat(ReportRequestController.stringDelimitedBySpace(" ")).isEmpty();
            assertThat(ReportRequestController.stringDelimitedBySpace("Custom")).isEqualTo("Custom");
            assertThat(ReportRequestController.stringInto("        ", "ab")).isEqualTo("ab      ");
            assertThat(ReportRequestController.stringInto("  ", "abcd")).isEqualTo("ab");
        }

        @Test
        @DisplayName("the AID token resolves to the EIBAID byte, and an unknown token to DFHNULL")
        void theAidResolution() {
            assertThat(ReportRequestController.eibAidOf(ENTER)).isEqualTo(CicsAid.DFHENTER);
            assertThat(ReportRequestController.eibAidOf("PFK03")).isEqualTo(CicsAid.DFHPF3);
            assertThat(ReportRequestController.eibAidOf("PFK07")).isEqualTo(CicsAid.DFHNULL);
            assertThat(ReportRequestController.eibAidOf(null)).isEqualTo(CicsAid.DFHNULL);
            assertThat(ReportRequestController.eibAidOf("     ")).isEqualTo(CicsAid.DFHNULL);
        }
    }

    // =================================================================================================
    // MAIN-PARA - app/cbl/CORPT00C.cbl:162-199
    // =================================================================================================

    @Nested
    @DisplayName("MAIN-PARA - the cold start, the first entry, and EVALUATE EIBAID in source order")
    class MainPara {

        @Test
        @DisplayName("EIBCALEN = 0 transfers to COSGN00C without sending a screen")
        void theColdStart() {
            ProgramState state = controllerAt(FIXED_MIDNIGHT)
                    .mainPara(ReportRequestRequest.empty().withoutNavigationContext());

            assertThat(state.transferred()).isTrue();
            assertThat(state.screenSent()).isFalse();
            assertThat(state.response().getNextProgram()).isEqualTo("COSGN00C");
            assertThat(state.commarea().pgmContext()).isZero();
            assertThat(state.transactNotEof()).isTrue();
        }

        @Test
        @DisplayName("the first entry paints the map with LOW-VALUES and the cursor on MONTHLY")
        void theFirstEntry() {
            ProgramState state = controllerAt(FIXED_INSTANT).mainPara(ReportRequestRequest.empty());

            assertThat(state.screenSent()).isTrue();
            assertThat(state.screenSentWithErase()).isTrue();
            assertThat(state.returned()).isTrue();
            assertThat(state.commarea().isReenter()).isTrue();
            assertThat(state.cursorRequestedOn(ReportRequestRequest.ScreenField.MONTHLY)).isTrue();
            assertThat(state.response().getNextProgram()).isEqualTo("CORPT00C");
            assertThat(state.response().getTrnnameo()).isEqualTo("CR00");
            assertThat(state.response().getCurdateo()).isEqualTo("08/09/26");
            assertThat(state.response().getCurtimeo()).isEqualTo("14:05:06");
            assertThat(state.response().getMonthlyo()).isEqualTo("\u0000");
            assertThat(state.response().getErrmsgo()).isBlank();
        }

        @Test
        @DisplayName("PF3 transfers to COMEN01C; any other key is the invalid-key message")
        void theKeyEvaluation() {
            ProgramState pf3 = controllerAt(FIXED_MIDNIGHT).mainPara(reenter().withAid("PFK03"));
            assertThat(pf3.transferred()).isTrue();
            assertThat(pf3.response().getNextProgram()).isEqualTo("COMEN01C");
            assertThat(pf3.commarea().fromTranid()).isEqualTo("CR00");
            assertThat(pf3.commarea().fromProgram()).isEqualTo("CORPT00C");

            ProgramState other = controllerAt(FIXED_MIDNIGHT).mainPara(reenter().withAid("PFK07"));
            assertThat(other.errFlagOn()).isTrue();
            assertThat(other.message()).startsWith("Invalid key pressed. Please see below...");
            assertThat(other.cursorRequestedOn(ReportRequestRequest.ScreenField.MONTHLY)).isTrue();
            assertThat(other.submittedRecords()).isEmpty();
        }

        @Test
        @DisplayName("ENTER with no report type selected reports it and displays PROCESS ENTER KEY")
        void noReportTypeSelected() {
            ProgramState state = controllerAt(FIXED_MIDNIGHT).mainPara(reenter());

            assertThat(state.message().trim()).isEqualTo("Select a report type to print report...");
            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.displayLines()).containsExactly("PROCESS ENTER KEY");
        }

        @Test
        @DisplayName("RETURN-TO-PREV-SCREEN defaults an empty target to COSGN00C")
        void theEmptyTargetDefault() {
            // The PF3 path never reaches this arm, because MAIN-PARA moves 'COMEN01C' first. Entering
            // the paragraph cold is what lines 542 to 544 actually guard against.
            ProgramState state = new ProgramState();
            assertThat(ReportRequestResponse.isSpacesOrLowValues(state.commarea().toProgram())).isTrue();

            controllerAt(FIXED_MIDNIGHT).returnToPrevScreen(state);

            assertThat(state.transferred()).isTrue();
            assertThat(state.screenSent()).isFalse();
            assertThat(state.commarea().toProgram()).isEqualTo("COSGN00C");
            assertThat(state.commarea().isEnter()).isTrue();
            assertThat(state.response().getNextProgram()).isEqualTo("COSGN00C");
        }
    }

    // =================================================================================================
    // PROCESS-ENTER-KEY - app/cbl/CORPT00C.cbl:203-441
    // =================================================================================================

    @Nested
    @DisplayName("PROCESS-ENTER-KEY - the three report arms, in the source's order")
    class TheReportArms {

        @Test
        @DisplayName("Monthly emits seventeen records, the last of them '/*EOF'")
        void theMonthlyArm() {
            ProgramState state = controllerAt(FIXED_MIDNIGHT)
                    .mainPara(reenter().withMonthly("Y").withConfirm("Y"));

            assertThat(port.records).hasSize(17)
                    .allSatisfy(record -> assertThat(record).hasSize(80));
            assertThat(String.join("", port.records)).hasSize(1360);
            assertThat(port.records.get(16)).isEqualTo(ReportRequestController.EOF_MARKER_RECORD);
            assertThat(port.records.get(10)).isEqualTo(pad("PARM-START-DATE,C'2026-08-01'"));
            assertThat(port.records.get(11)).isEqualTo(pad("PARM-END-DATE,C'2026-08-31'"));
            assertThat(port.records.get(14)).isEqualTo(pad("2026-08-01 2026-08-31"));
            assertThat(state.submittedRecords()).hasSize(17);
            assertThat(state.idx()).isEqualTo(18);
            assertThat(state.message().trim())
                    .isEqualTo("Monthly report submitted for printing ...");
            assertThat(state.response().getErrmsgc()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(state.response().getMonthlyo()).isEqualTo(" ");
            assertThat(state.errFlagOff()).isTrue();
        }

        @Test
        @DisplayName("Yearly covers the first of January to the thirty-first of December")
        void theYearlyArm() {
            ProgramState state = controllerAt(FIXED_MIDNIGHT)
                    .mainPara(reenter().withYearly("Y").withConfirm("y"));

            assertThat(state.parmStartDate1()).isEqualTo("2026-01-01");
            assertThat(state.parmEndDate1()).isEqualTo("2026-12-31");
            assertThat(port.records).hasSize(17);
            assertThat(state.message().trim()).isEqualTo("Yearly report submitted for printing ...");
        }

        @Test
        @DisplayName("Custom accepts the typed range and submits it")
        void theCustomArm() {
            ProgramState state = controllerAt(FIXED_MIDNIGHT)
                    .mainPara(reenter().withCustom("Y").withSdtmm(" 7").withSdtdd("4")
                            .withSdtyyyy("2026").withEdtmm("07").withEdtdd("06")
                            .withEdtyyyy("2026").withConfirm("Y"));

            assertThat(state.parmStartDate1()).isEqualTo("2026-07-04");
            assertThat(state.parmEndDate1()).isEqualTo("2026-07-06");
            assertThat(state.startDate()).isEqualTo("2026-07-04");
            assertThat(state.endDate()).isEqualTo("2026-07-06");
            assertThat(state.reportName()).isEqualTo("Custom    ");
            assertThat(port.records).hasSize(17);
            assertThat(port.records.get(14)).isEqualTo(pad("2026-07-04 2026-07-06"));
            assertThat(state.csutldtcResult().severityCode()).isEqualTo("0000");
            // INITIALIZE-ALL-FIELDS runs after a successful submission, so the normalised values are
            // cleared again before the screen is sent.
            assertThat(state.response().getSdtmmo()).isEqualTo("  ");
            assertThat(state.response().getCustomo()).isEqualTo(" ");
        }

        @Test
        @DisplayName("the six edits write their normalised values back into the map")
        void theNormalisationIsVisibleOnTheScreen() {
            // A blank CONFIRMI stops the flow inside SUBMIT-JOB-TO-INTRDR - after the six edits have
            // stored their results and before INITIALIZE-ALL-FIELDS could clear them.
            ProgramState state = controllerAt(FIXED_MIDNIGHT)
                    .mainPara(reenter().withCustom("Y").withSdtmm(" 7").withSdtdd("4")
                            .withSdtyyyy("2026").withEdtmm("7").withEdtdd(" 6")
                            .withEdtyyyy(" 026"));

            assertThat(state.response().getSdtmmo()).isEqualTo("07");
            assertThat(state.response().getSdtddo()).isEqualTo("04");
            assertThat(state.response().getSdtyyyyo()).isEqualTo("2026");
            assertThat(state.response().getEdtmmo()).isEqualTo("07");
            assertThat(state.response().getEdtddo()).isEqualTo("06");
            assertThat(state.response().getEdtyyyyo()).isEqualTo("0026");
            assertThat(state.sdtmmI()).isEqualTo("07");
            assertThat(state.edtyyyyI()).isEqualTo("0026");
            assertThat(state.message().trim())
                    .isEqualTo("Please confirm to print the Custom report...");
            assertThat(port.records).isEmpty();
        }

        @ParameterizedTest(name = "{6}")
        @CsvSource(quoteCharacter = '`', value = {
            "``,   `04`, `2026`, `07`, `06`, `2026`, `Start Date - Month can NOT be empty...`",
            "`07`, ``,   `2026`, `07`, `06`, `2026`, `Start Date - Day can NOT be empty...`",
            "`07`, `04`, ``,     `07`, `06`, `2026`, `Start Date - Year can NOT be empty...`",
            "`07`, `04`, `2026`, ``,   `06`, `2026`, `End Date - Month can NOT be empty...`",
            "`07`, `04`, `2026`, `07`, ``,   `2026`, `End Date - Day can NOT be empty...`",
            "`07`, `04`, `2026`, `07`, `06`, ``,     `End Date - Year can NOT be empty...`",
            "`13`, `04`, `2026`, `07`, `06`, `2026`, `Start Date - Not a valid Month...`",
            "`07`, `32`, `2026`, `07`, `06`, `2026`, `Start Date - Not a valid Day...`",
            "`07`, `04`, `2026`, `13`, `06`, `2026`, `End Date - Not a valid Month...`",
            "`07`, `04`, `2026`, `07`, `32`, `2026`, `End Date - Not a valid Day...`",
            "`02`, `31`, `2026`, `07`, `06`, `2026`, `Start Date - Not a valid date...`",
            "`07`, `04`, `2026`, `02`, `31`, `2026`, `End Date - Not a valid date...`"
        })
        @DisplayName("the guard chain reports the first failure and submits nothing")
        void theGuardChain(String sdtmm, String sdtdd, String sdtyyyy, String edtmm, String edtdd,
                           String edtyyyy, String expectedMessage) {
            CapturingPort local = new CapturingPort();
            ProgramState state = new ReportRequestController(dateUtility, local,
                    Clock.fixed(Instant.parse(FIXED_MIDNIGHT), ZoneOffset.UTC))
                    .mainPara(customRequest(nullToEmpty(sdtmm), nullToEmpty(sdtdd),
                            nullToEmpty(sdtyyyy), nullToEmpty(edtmm), nullToEmpty(edtdd),
                            nullToEmpty(edtyyyy)));

            assertThat(state.message().trim()).isEqualTo(expectedMessage);
            assertThat(state.errFlagOn()).isTrue();
            assertThat(local.records).isEmpty();
        }

        @Test
        @DisplayName("CSUTLDTC message number 2513 is tolerated, as both call sites tolerate it")
        void theToleratedSeverity() {
            // A date before the Lillian epoch is FC-UNSUPP-RANGE: severe, but message number 2513,
            // which lines 396 and 416 accept.
            DateValidationResult probe = dateUtility.validateDate("1500-01-01", "YYYY-MM-DD");
            assertThat(probe.severityCode()).isEqualTo("0003");
            assertThat(probe.messageNumber())
                    .isEqualTo(ReportRequestController.CSUTLDTC_TOLERATED_MESSAGE_NUMBER);

            ProgramState state = controllerAt(FIXED_MIDNIGHT)
                    .mainPara(reenter().withCustom("Y").withSdtmm("01").withSdtdd("01")
                            .withSdtyyyy("1500").withEdtmm("01").withEdtdd("01")
                            .withEdtyyyy("1500").withConfirm("Y"));

            assertThat(state.errFlagOff()).isTrue();
            assertThat(port.records).hasSize(17);
            assertThat(state.parmStartDate1()).isEqualTo("1500-01-01");
        }

        @Test
        @DisplayName("the preserved IS NOT NUMERIC year arms still report what the source reports")
        void thePreservedYearArms() {
            ProgramState startYear = new NonNumericYearController(dateUtility, port,
                    Clock.fixed(Instant.parse(FIXED_MIDNIGHT), ZoneOffset.UTC), 1)
                    .mainPara(customRequest("07", "04", "2026", "07", "06", "2026"));
            assertThat(startYear.message().trim()).isEqualTo("Start Date - Not a valid Year...");

            ProgramState endYear = new NonNumericYearController(dateUtility, port,
                    Clock.fixed(Instant.parse(FIXED_MIDNIGHT), ZoneOffset.UTC), 2)
                    .mainPara(customRequest("07", "04", "2026", "07", "06", "2026"));
            assertThat(endYear.message().trim()).isEqualTo("End Date - Not a valid Year...");
        }

        @Test
        @DisplayName("the working-storage date groups keep their '-' separators and their parts")
        void theDateGroups() {
            ProgramState state = controllerAt(FIXED_MIDNIGHT)
                    .mainPara(customRequest("07", "04", "2026", "12", "31", "2027"));

            assertThat(state.startDateYyyy()).isEqualTo("2026");
            assertThat(state.startDateMm()).isEqualTo("07");
            assertThat(state.startDateDd()).isEqualTo("04");
            assertThat(state.endDateYyyy()).isEqualTo("2027");
            assertThat(state.endDateMm()).isEqualTo("12");
            assertThat(state.endDateDd()).isEqualTo("31");
            assertThat(state.startDate()).isEqualTo("2026-07-04");
            assertThat(state.endDate()).isEqualTo("2027-12-31");
        }

        private String nullToEmpty(String value) {
            return value == null ? "" : value;
        }
    }

    // =================================================================================================
    // SUBMIT-JOB-TO-INTRDR and WIRTE-JOBSUB-TDQ - app/cbl/CORPT00C.cbl:462-535
    // =================================================================================================

    @Nested
    @DisplayName("SUBMIT-JOB-TO-INTRDR - the confirmation, the emit loop, and the queue write")
    class SubmitJobToIntrdr {

        @Test
        @DisplayName("a blank confirmation asks for one, using DELIMITED BY SPACE")
        void theBlankConfirmation() {
            ProgramState state = controllerAt(FIXED_MIDNIGHT).mainPara(reenter().withMonthly("Y"));

            assertThat(state.message().trim())
                    .isEqualTo("Please confirm to print the Monthly report...");
            assertThat(state.cursorRequestedOn(ReportRequestRequest.ScreenField.CONFIRM)).isTrue();
            assertThat(port.records).isEmpty();
        }

        @ParameterizedTest(name = "CONFIRMI = \"{0}\" clears the screen and submits nothing")
        @CsvSource({"N", "n"})
        @DisplayName("a refusal re-initialises the fields and submits nothing")
        void theRefusal(String confirm) {
            ProgramState state = controllerAt(FIXED_MIDNIGHT)
                    .mainPara(reenter().withMonthly("Y").withConfirm(confirm));

            assertThat(state.message()).isBlank();
            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.response().getMonthlyo()).isEqualTo(" ");
            assertThat(state.response().getConfirmo()).isEqualTo(" ");
            assertThat(port.records).isEmpty();
        }

        @Test
        @DisplayName("any other value is quoted back in the message")
        void theInvalidConfirmation() {
            ProgramState state = controllerAt(FIXED_MIDNIGHT)
                    .mainPara(reenter().withMonthly("Y").withConfirm("Q"));

            assertThat(state.message().trim()).isEqualTo("\"Q\" is not a valid value to confirm...");
            assertThat(state.cursorRequestedOn(ReportRequestRequest.ScreenField.CONFIRM)).isTrue();
            assertThat(port.records).isEmpty();
        }

        @Test
        @DisplayName("a failed write stops the loop and leaves what was already appended")
        void aFailedWriteIsNotRolledBack() {
            CapturingPort failing = new CapturingPort();
            failing.failFrom = 3;

            ProgramState state = new ReportRequestController(dateUtility, failing,
                    Clock.fixed(Instant.parse(FIXED_MIDNIGHT), ZoneOffset.UTC))
                    .mainPara(reenter().withMonthly("Y").withConfirm("Y"));

            assertThat(failing.records).hasSize(2);
            assertThat(state.submittedRecords()).hasSize(3);
            assertThat(state.message().trim()).isEqualTo("Unable to Write TDQ (JOBS)...");
            assertThat(state.respCd()).isEqualTo(FileStatus.NOTOPEN);
            assertThat(state.cursorRequestedOn(ReportRequestRequest.ScreenField.MONTHLY)).isTrue();
            assertThat(state.displayLines())
                    .anySatisfy(line -> assertThat(line).startsWith("RESP:"));
        }

        @Test
        @DisplayName("a blank record ends the loop just as '/*EOF' does, and is still written")
        void theBlankSentinel() {
            ProgramState state = new BlankSkeletonController(dateUtility, port,
                    Clock.fixed(Instant.parse(FIXED_MIDNIGHT), ZoneOffset.UTC))
                    .mainPara(reenter().withMonthly("Y").withConfirm("Y"));

            assertThat(state.submittedRecords()).hasSize(1);
            assertThat(state.submittedRecords().get(0)).isBlank();
        }

        @Test
        @DisplayName("with no sentinel at all, the OCCURS 1000 bound ends the loop")
        void theOccursBound() {
            CapturingPort local = new CapturingPort();

            ProgramState state = new SentinellessSkeletonController(dateUtility, local,
                    Clock.fixed(Instant.parse(FIXED_MIDNIGHT), ZoneOffset.UTC))
                    .mainPara(reenter().withMonthly("Y").withConfirm("Y"));

            assertThat(local.records).hasSize(ReportRequestController.JOB_LINES_OCCURS);
            assertThat(state.idx()).isEqualTo(ReportRequestController.JOB_LINES_OCCURS + 1);
            assertThat(state.endLoopNo()).isTrue();
        }

        @Test
        @DisplayName("the source's defensive guard at line 434 declines to submit once the flag is on")
        void theDefensiveGuard() {
            CapturingPort local = new CapturingPort();

            ProgramState state = new FlagRaisingController(dateUtility, local,
                    Clock.fixed(Instant.parse(FIXED_MIDNIGHT), ZoneOffset.UTC))
                    .mainPara(customRequest("07", "04", "2026", "07", "06", "2026"));

            assertThat(state.errFlagOn()).isTrue();
            assertThat(local.records).isEmpty();
            assertThat(state.parmStartDate1()).isEqualTo("2026-07-04");
        }

        @Test
        @DisplayName("the unreachable no-ERASE send arm is preserved and still works")
        void theNoEraseArm() {
            ProgramState state = new ProgramState();
            state.setSendEraseNo();

            controllerAt(FIXED_MIDNIGHT).sendTrnrptScreen(state);

            assertThat(state.screenSent()).isTrue();
            assertThat(state.screenSentWithErase()).isFalse();
            assertThat(state.returned()).isTrue();
            assertThat(state.sendEraseNo()).isTrue();

            ProgramState erasing = new ProgramState();
            erasing.setSendEraseYes();
            assertThat(erasing.sendEraseYes()).isTrue();
            assertThat(erasing.sendEraseNo()).isFalse();
        }

        @Test
        @DisplayName("every ERR-FLG-ON guard the source writes short-circuits its paragraph")
        void theErrorFlagGuards() {
            ProgramState submitting = new ProgramState();
            submitting.setErrFlagOn();
            controllerAt(FIXED_MIDNIGHT).submitJobToIntrdr(submitting);
            assertThat(port.records).isEmpty();

            ProgramState entering = new ProgramState();
            entering.response().setPayloadValue(ReportRequestResponse.ScreenField.MONTHLY, "Y");
            entering.response().setPayloadValue(ReportRequestResponse.ScreenField.CONFIRM, "Y");
            entering.setErrFlagOn();
            controllerAt(FIXED_MIDNIGHT).processEnterKey(entering);
            assertThat(entering.message()).isBlank();
        }

        @Test
        @DisplayName("the per-request working storage starts as the WORKING-STORAGE SECTION declares")
        void theInitialWorkingStorage() {
            ProgramState state = new ProgramState();

            assertThat(state.transactNotEof()).isTrue();
            state.setTransactEof();
            assertThat(state.transactEof()).isTrue();
            assertThat(state.transactNotEof()).isFalse();

            assertThat(state.endLoopNo()).isTrue();
            state.setEndLoopYes();
            assertThat(state.endLoopYes()).isTrue();

            assertThat(state.dateHeader()).isNull();
            assertThat(state.csutldtcResult()).isNull();
            assertThat(state.jclRecord()).hasSize(80);
            // The FILLER separators of WS-START-DATE and WS-END-DATE carry '-', never a space.
            assertThat(state.startDate()).isEqualTo("    -  -  ");
            assertThat(state.endDate()).isEqualTo("    -  -  ");

            state.setReasCd(4);
            assertThat(state.reasCd()).isEqualTo(4);
        }
    }

    // =================================================================================================
    // The job-submission port - app/csd/CARDDEMO.CSD:499-505
    // =================================================================================================

    @Nested
    @DisplayName("the job-submission port - TDQUEUE(JOBS), RECORDSIZE(80), FIXED, UNBLOCKED, MOD")
    class TheJobSubmissionPort {

        @Test
        @DisplayName("records are appended as exactly eighty bytes each")
        void theAppendSemantics(@TempDir Path root) throws IOException {
            Path destination = root.resolve("inreader").resolve("JOBS");
            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination));

            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01))
                    .isEqualTo(WriteQueueOutcome.NORMAL);
            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_17).normal()).isTrue();

            assertThat(Files.readAllBytes(destination)).hasSize(160);
            assertThat(submitter.properties().queueName()).isEqualTo("JOBS");
            assertThat(submitter.properties().ddName()).isEqualTo("INREADER");
            assertThat(submitter.queueCharset()).isEqualTo(StandardCharsets.US_ASCII);
            assertThat(WriteQueueOutcome.NORMAL.fileStatus()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(WriteQueueOutcome.notOpen().fileStatus()).isEqualTo(FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("a record of the wrong width, or in a multi-byte code page, is refused")
        void theRefusals(@TempDir Path root) throws IOException {
            Path destination = root.resolve("inreader").resolve("JOBS");
            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination));

            assertThat(submitter.writeQueueTd("too short").resp()).isEqualTo(FileStatus.LENGERR);
            assertThat(submitter.writeQueueTd(" ".repeat(81)).resp()).isEqualTo(FileStatus.LENGERR);
            assertThat(submitter.writeQueueTd("\u20ac".repeat(80)).resp()).isEqualTo(FileStatus.INVREQ);

            // A fixed-width record area is addressed by absolute byte offset, so a code page that
            // encodes one character to more than one byte cannot carry it: INVREQ, never a 160-byte
            // "eighty-byte" record.
            InternalReaderJobSubmissionPort utf8 = new InternalReaderJobSubmissionPort(
                    properties(root, destination), StandardCharsets.UTF_8);
            assertThat(utf8.writeQueueTd("\u00e9".repeat(80)).resp()).isEqualTo(FileStatus.INVREQ);
        }

        @Test
        @DisplayName("an unusable destination reports NOTOPEN per write rather than failing start-up")
        void theUnusableDestination(@TempDir Path root) throws IOException {
            InternalReaderJobSubmissionPort rootless =
                    new InternalReaderJobSubmissionPort(properties(root, Path.of("/")));
            assertThat(rootless.writeQueueTd(ReportRequestController.JOB_LINE_01).resp())
                    .isEqualTo(FileStatus.NOTOPEN);

            Path occupied = Files.createFile(root.resolve("occupied"));
            InternalReaderJobSubmissionPort blocked = new InternalReaderJobSubmissionPort(
                    properties(root, occupied.resolve("JOBS")));
            assertThat(blocked.writeQueueTd(ReportRequestController.JOB_LINE_01).resp())
                    .isEqualTo(FileStatus.NOTOPEN);
        }

        @Test
        @DisplayName("a binding that is not the CSD's RECORDSIZE(80) is refused at construction")
        void theBindingIsCheckedAgainstTheCsd(@TempDir Path root) {
            assertThatIllegalStateException().isThrownBy(() -> new InternalReaderJobSubmissionPort(
                    new JobSubmissionProperties("JOBS", "INREADER", 79, "FIXED", "UNBLOCKED", "MOD",
                            root.toString(), root.resolve("JOBS").toString())));
        }
    }

    // =================================================================================================
    // The HTTP surface
    // =================================================================================================

    @Nested
    @DisplayName("the HTTP surface - a thin adapter over MAIN-PARA, and the symbolic map contract")
    class TheHttpSurface {

        @Test
        @DisplayName("the adapter adds nothing to MAIN-PARA")
        void theAdapterIsThin() {
            ReportRequestResponse response = controllerAt(FIXED_INSTANT)
                    .submitReportRequest(ReportRequestRequest.empty());

            assertThat(response.getNextProgram()).isEqualTo("CORPT00C");
            assertThat(response.getTrnnameo()).isEqualTo("CR00");
            assertThat(ReportRequestController.REPORTS_PATH).isEqualTo("/api/reports");
        }

        @Test
        @DisplayName("POST /api/reports is mapped and drives the same flow the plain call drives")
        void theEndpointIsMapped() throws Exception {
            Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
            new WebConfig().carddemoJacksonCustomizer().customize(builder);
            ObjectMapper mapper = builder.build();
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controllerAt(FIXED_INSTANT))
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                    .build();

            mockMvc.perform(post(ReportRequestController.REPORTS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(ReportRequestRequest.empty())))
                    .andExpect(status().isOk());

            mockMvc.perform(post(ReportRequestController.REPORTS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(
                                    reenter().withMonthly("Y").withConfirm("Y"))))
                    .andExpect(status().isOk());

            assertThat(port.records).hasSize(17);
        }

        @Test
        @DisplayName("the xxxL cursor request is metadata, never a payload member")
        void theCursorRequestIsMetadata() {
            ProgramState state = controllerAt(FIXED_MIDNIGHT).mainPara(ReportRequestRequest.empty());

            assertThat(state.symbolicMap().length(ReportRequestRequest.ScreenField.MONTHLY))
                    .isEqualTo(ReportRequestRequest.FieldMetadata.CURSOR_POSITION);
            assertThat(state.symbolicMap().metadata(ReportRequestRequest.ScreenField.MONTHLY)
                    .cursorRequested()).isTrue();
            assertThat(state.symbolicMap().metadata(ReportRequestRequest.ScreenField.CONFIRM)
                    .cursorRequested()).isFalse();
        }

        @Test
        @DisplayName("both projections of app/cpy-bms/CORPT00.CPY carry its seventeen fields")
        void theSeventeenFields() {
            assertThat(ReportRequestRequest.FIELD_COUNT).isEqualTo(17);
            assertThat(ReportRequestRequest.ScreenField.values()).hasSize(17);
            assertThat(ReportRequestResponse.ScreenField.values()).hasSize(17);
            assertThat(ReportRequestRequest.ScreenField.SDTYYYY.declaredLength()).isEqualTo(4);
            assertThat(ReportRequestResponse.ScreenField.ERRMSG.payloadLength()).isEqualTo(78);
        }
    }

    // =================================================================================================
    // Test doubles
    // =================================================================================================

    /** Captures what the program would have written to transient data queue {@code JOBS}. */
    private static final class CapturingPort implements JobSubmissionPort {

        private final List<String> records = new ArrayList<>();

        /** The one-based ordinal from which every write reports NOTOPEN. */
        private int failFrom = Integer.MAX_VALUE;

        @Override
        public WriteQueueOutcome writeQueueTd(String jclRecord) {
            if (records.size() + 1 >= failFrom) {
                return WriteQueueOutcome.notOpen();
            }
            records.add(jclRecord);
            return WriteQueueOutcome.NORMAL;
        }
    }

    /**
     * Returns a non-numeric year from one of the two {@code PIC 9999} stores, which is the only way to
     * reach the source's {@code IS NOT NUMERIC} year arms: the store itself always yields four digits,
     * so in the composed flow those arms cannot fire. They are preserved rather than removed.
     */
    private static final class NonNumericYearController extends ReportRequestController {

        private final int failOnCall;

        private int calls;

        NonNumericYearController(DateUtilityJob dateUtilityJob, JobSubmissionPort submissionPort,
                                 Clock clock, int failOnCall) {
            super(dateUtilityJob, submissionPort, clock);
            this.failOnCall = failOnCall;
        }

        @Override
        public String computeIntoPic9999(String source) {
            calls++;
            return calls == failOnCall ? "ab  " : super.computeIntoPic9999(source);
        }
    }

    /** A one-record skeleton whose only record is blank, for the loop's SPACES sentinel. */
    private static final class BlankSkeletonController extends ReportRequestController {

        BlankSkeletonController(DateUtilityJob dateUtilityJob, JobSubmissionPort submissionPort,
                                Clock clock) {
            super(dateUtilityJob, submissionPort, clock);
        }

        @Override
        public List<String> jobLines(ProgramState state) {
            return List.of(" ".repeat(JCL_RECORD_LENGTH));
        }
    }

    /** A skeleton with no sentinel anywhere, so only the {@code OCCURS} bound can end the loop. */
    private static final class SentinellessSkeletonController extends ReportRequestController {

        SentinellessSkeletonController(DateUtilityJob dateUtilityJob, JobSubmissionPort submissionPort,
                                       Clock clock) {
            super(dateUtilityJob, submissionPort, clock);
        }

        @Override
        public List<String> jobLines(ProgramState state) {
            return Collections.nCopies(JOB_LINES_OCCURS, pad("//NOSENTINEL"));
        }
    }

    /**
     * Raises {@code ERR-FLG-ON} without ending the paragraph, which is the only way to observe the
     * source's defensive guard at line 434 declining to submit.
     */
    private static final class FlagRaisingController extends ReportRequestController {

        FlagRaisingController(DateUtilityJob dateUtilityJob, JobSubmissionPort submissionPort,
                              Clock clock) {
            super(dateUtilityJob, submissionPort, clock);
        }

        @Override
        public boolean callCsutldtc(ProgramState state, String date, String message,
                                    ReportRequestRequest.ScreenField cursorField) {
            boolean accepted = super.callCsutldtc(state, date, message, cursorField);
            state.setErrFlagOn();
            return accepted;
        }
    }
}
