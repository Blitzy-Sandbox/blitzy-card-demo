package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.config.WebConfig;
import com.vsergeychik.carddemo.config.WebConfig.JobSubmissionProperties;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.transaction.DateParmReader.DateParm;
import com.vsergeychik.carddemo.transaction.ReportRequestController.InternalReaderJobSubmissionPort;
import com.vsergeychik.carddemo.transaction.ReportRequestController.JobSubmissionPort;
import com.vsergeychik.carddemo.transaction.ReportRequestController.ProgramState;
import com.vsergeychik.carddemo.transaction.ReportRequestController.WriteQueueOutcome;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestRequest;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestResponse;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import com.vsergeychik.carddemo.util.DateUtilityJob.DateValidationResult;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.NonReadableChannelException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.junit.jupiter.api.Timeout;
import com.vsergeychik.carddemo.testsupport.ConcurrentTasks;

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


    /**
     * The code page {@code application-test.yml} names under {@code carddemo.charset.dataset}, which is
     * what {@code CobolCharsetConfig} publishes as the active dataset charset under this profile.
     *
     * <p>Stated here, and passed to the production customizer, because the inbound screen-text boundary
     * judges every value against the page in force rather than against a page of its own choosing: a
     * mapper built for a test has to name the same one the profile does or it is not the production
     * mapper.
     */
    private static final Charset TEST_PROFILE_CHARSET = StandardCharsets.US_ASCII;

    /**
     * The code page every test wires, which is the one {@code charset.dataset} declares.
     *
     * <p>{@code IBM037} rather than {@code US-ASCII} deliberately: the program's images and the
     * eighty-byte queue records are encoded in the region's own code page, and this class asserts on the
     * bytes that reach the destination, so it must use the code page a deployment uses.
     */
    private static final Charset DATASET_CHARSET = Charset.forName("IBM037");

    /**
     * The payload image of {@code DFHENTER}, which is what {@code EVALUATE EIBAID} branches on first.
     *
     * <p>One character, whose code point <em>is</em> the byte - not the five-character {@code CCARD-AID}
     * token, which folds {@code PF13}-{@code PF24} onto {@code PF1}-{@code PF12} and which
     * {@code CORPT00C} never sees, because it does not copy {@code CSSTRPFY}.
     */
    private static final String ENTER = PfKeyResolver.aidImage(CicsAid.DFHENTER);

    /** {@code 2026-08-09T14:05:06Z} - a fixed instant, so {@code CURDATE} and {@code CURTIME} are exact. */
    private static final String FIXED_INSTANT = "2026-08-09T14:05:06Z";

    /** Midnight on the same day, for the cases that assert on dates rather than on the time header. */
    private static final String FIXED_MIDNIGHT = "2026-08-09T00:00:00Z";

    private final DateUtilityJob dateUtility = new DateUtilityJob();

    private final CapturingPort port = new CapturingPort();

    private ReportRequestController controllerAt(String instant) {
        return new ReportRequestController(dateUtility, port,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), DATASET_CHARSET);
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
        return new JobSubmissionProperties("JOBS", "INREADER", "US-ASCII", 80, "FIXED",
                "UNBLOCKED", "MOD", root.toString(), destination.toString());
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
                    Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), DATASET_CHARSET)
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
        @DisplayName("one character is the EIBAID byte, and any other width is DFHNULL")
        void theAidResolution() {
            assertThat(ReportRequestController.eibAidOf(ENTER)).isEqualTo(CicsAid.DFHENTER);
            assertThat(ReportRequestController.eibAidOf(PfKeyResolver.aidImage(CicsAid.DFHPF3)))
                    .isEqualTo(CicsAid.DFHPF3);
            assertThat(ReportRequestController.eibAidOf(PfKeyResolver.aidImage(CicsAid.DFHPF15)))
                    .as("PF15 is not PF3: CORPT00C compares EIBAID and takes WHEN OTHER at :190")
                    .isEqualTo(CicsAid.DFHPF15);
            assertThat(ReportRequestController.eibAidOf("PFK03"))
                    .as("a folded CCARD-AID token is not one byte, so it names no key at all")
                    .isEqualTo(CicsAid.DFHNULL);
            assertThat(ReportRequestController.eibAidOf(null)).isEqualTo(CicsAid.DFHNULL);
            assertThat(ReportRequestController.eibAidOf("     ")).isEqualTo(CicsAid.DFHNULL);
            assertThat(ReportRequestController.eibAidOf(String.valueOf((char) 0x01F3)))
                    .as("a character above the one-byte AID space is never narrowed onto DFHPF3")
                    .isEqualTo(CicsAid.DFHNULL);
        }

        @Test
        @DisplayName("the query parameter states the byte, and it wins over the payload's image")
        void theParameterWinsOverThePayload() {
            String enterImage = PfKeyResolver.aidImage(CicsAid.DFHENTER);
            ReportRequestController controller = controllerAt(FIXED_MIDNIGHT);

            assertThat(controller.resolveEibAid(CicsAid.DFHPF3 & 0xFF, null))
                    .isEqualTo(CicsAid.DFHPF3);
            assertThat(controller.resolveEibAid(CicsAid.DFHENTER & 0xFF, enterImage))
                    .as("one key stated twice, consistently")
                    .isEqualTo(CicsAid.DFHENTER);
            assertThat(controller.resolveEibAid(null, enterImage))
                    .isEqualTo(CicsAid.DFHENTER);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> controller.resolveEibAid(256, null))
                    .withMessageContaining(ReportRequestController.EIBAID_PARAM);
        }

        @Test
        @DisplayName("an image naming a different key from the stated byte is refused, not discarded")
        void aContradictingImageIsRefused() {
            ReportRequestController controller = controllerAt(FIXED_MIDNIGHT);

            assertThatThrownBy(() -> controller.resolveEibAid(CicsAid.DFHPF3 & 0xFF,
                    PfKeyResolver.aidImage(CicsAid.DFHENTER)))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .hasMessageContaining("aid");
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
            ProgramState pf3 = controllerAt(FIXED_MIDNIGHT)
                    .mainPara(reenter().withAid(PfKeyResolver.aidImage(CicsAid.DFHPF3)));
            assertThat(pf3.transferred()).isTrue();
            assertThat(pf3.response().getNextProgram()).isEqualTo("COMEN01C");
            assertThat(pf3.commarea().fromTranid()).isEqualTo("CR00");
            assertThat(pf3.commarea().fromProgram()).isEqualTo("CORPT00C");

            ProgramState other = controllerAt(FIXED_MIDNIGHT)
                    .mainPara(reenter().withAid(PfKeyResolver.aidImage(CicsAid.DFHPF7)));
            assertThat(other.errFlagOn()).isTrue();
            assertThat(other.message()).startsWith("Invalid key pressed. Please see below...");
            assertThat(other.cursorRequestedOn(ReportRequestRequest.ScreenField.MONTHLY)).isTrue();
            assertThat(other.submittedRecords()).isEmpty();

            // The PF3 arm places no cursor - it transfers instead of sending - so the metadata names no
            // field. Reporting a field the program never asked for would be worse than reporting none.
            assertThat(pf3.screenMetadata().cursorField()).isNull();
            assertThat(other.screenMetadata().cursorField()).isEqualTo("MONTHLY");
        }

        @Test
        @DisplayName("a raw PF15 byte is an invalid key, where the folded token took the PF3 transfer")
        void aRawUpperKeyIsNotItsFoldedPartner() {
            // CORPT00C compares EIBAID inline at lines 184 to 195 and names only DFHENTER and DFHPF3, so
            // on the terminal PF15 paints the invalid-key message. CSSTRPFY folds PF15 onto 'PFK03', so a
            // request that could only send the token had to take the PF3 transfer to COMEN01C instead.
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15)).contains(PfKeyResolver.AidKey.PFK03);

            ProgramState state = controllerAt(FIXED_MIDNIGHT)
                    .mainPara(noKeyStated(), Byte.toUnsignedInt(CicsAid.DFHPF15));

            assertThat(state.transferred())
                    .as("PF15 does not transfer, because line 187 tests WHEN DFHPF3")
                    .isFalse();
            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message()).startsWith("Invalid key pressed. Please see below...");
            assertThat(state.submittedRecords()).isEmpty();
        }

        @Test
        @DisplayName("a raw PF3 byte still transfers to COMEN01C, so the lower key is unaffected")
        void aRawLowerKeyStillTakesItsArm() {
            ProgramState state = controllerAt(FIXED_MIDNIGHT)
                    .mainPara(noKeyStated(), Byte.toUnsignedInt(CicsAid.DFHPF3));

            assertThat(state.transferred()).isTrue();
            assertThat(state.response().getNextProgram()).isEqualTo("COMEN01C");
        }

        @ParameterizedTest(name = "a raw DFHPF{0} byte is an invalid key here")
        @ValueSource(ints = {13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24})
        @DisplayName("all twelve upper function keys reach WHEN OTHER when stated as raw bytes")
        void everyUpperKeyIsInvalidHere(int pfNumber) {
            ProgramState state = controllerAt(FIXED_MIDNIGHT)
                    .mainPara(noKeyStated(), Byte.toUnsignedInt(functionKey(pfNumber)));

            assertThat(state.transferred()).isFalse();
            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.submittedRecords()).isEmpty();
        }

        @Test
        @DisplayName("the byte wins over the token, and a token restating it is accepted")
        void theByteWinsAndAConsistentTokenIsAccepted() {
            ProgramState state = controllerAt(FIXED_MIDNIGHT)
                    .mainPara(reenter().withAid("PFK03"), Byte.toUnsignedInt(CicsAid.DFHPF15));

            assertThat(state.transferred()).isFalse();
            assertThat(state.errFlagOn()).isTrue();
        }

        @Test
        @DisplayName("a token naming a different key is refused rather than discarded")
        void aDisagreeingTokenIsRefused() {
            assertThatThrownBy(() -> controllerAt(FIXED_MIDNIGHT)
                    .mainPara(reenter().withAid("PFK07"), Byte.toUnsignedInt(CicsAid.DFHPF3)))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .hasMessageContaining("aid");
        }

        @ParameterizedTest(name = "a stated {0} is refused")
        @ValueSource(ints = {-1, 256, 4096})
        @DisplayName("a value that is not one byte is refused rather than narrowed")
        void anImpossibleByteIsRefused(int stated) {
            assertThatThrownBy(() -> controllerAt(FIXED_MIDNIGHT).mainPara(noKeyStated(), stated))
                    .isInstanceOf(ScreenInputRejectedException.class);
        }

        @Test
        @DisplayName("both spellings of the parameter reach the same byte through the route")
        void bothSpellingsAreHonoured() {
            assertThat(controllerAt(FIXED_MIDNIGHT)
                    .submitReportRequest(noKeyStated(), Byte.toUnsignedInt(CicsAid.DFHPF15), null)
                    .screen().getErrmsgo())
                    .startsWith("Invalid key pressed");
            assertThat(controllerAt(FIXED_MIDNIGHT)
                    .submitReportRequest(noKeyStated(), null, Byte.toUnsignedInt(CicsAid.DFHPF15))
                    .screen().getErrmsgo())
                    .startsWith("Invalid key pressed");
        }

        /**
         * A re-entry whose {@code CCARD-AID} member states no key, so the raw byte is the only statement.
         *
         * <p>{@code withAid(null)} normalises to the five spaces {@code RECEIVE MAP} leaves in an
         * unpainted field, which is what a client sending the byte instead of the token sends.
         */
        private static ReportRequestRequest noKeyStated() {
            return reenter().withAid(null);
        }

        /** A {@link CicsAid} function-key constant by number, so the copybook name is the source. */
        private static byte functionKey(int pfNumber) {
            try {
                return CicsAid.class.getDeclaredField("DFHPF" + pfNumber).getByte(null);
            } catch (ReflectiveOperationException absent) {
                throw new AssertionError("CicsAid does not declare DFHPF" + pfNumber, absent);
            }
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
            // An XCTL states no map: which map COSGN00C paints is its decision, made after this program
            // has ended, and CORPT00C names none in the XCTL at :548-551. Publishing CORPT00 / CORPT0A
            // here would tell the client to repaint the screen it is leaving.
            assertThat(state.response().getNextMapset()).isBlank()
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(state.response().getNextMap()).isBlank()
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
        }

        @Test
        @DisplayName("a SEND names this screen's own mapset and map, which is the other case")
        void aSendNamesThisScreen() {
            ProgramState state = new ProgramState();

            controllerAt(FIXED_MIDNIGHT).sendTrnrptScreen(state);

            assertThat(state.response().getNextMapset()).isEqualTo(ReportRequestResponse.MAPSET_NAME);
            assertThat(state.response().getNextMap()).isEqualTo(ReportRequestResponse.MAP_NAME);
            assertThat(state.response().getNextProgram())
                    .isEqualTo(ReportRequestController.PROGRAM_NAME);
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
                    Clock.fixed(Instant.parse(FIXED_MIDNIGHT), ZoneOffset.UTC), DATASET_CHARSET)
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
                    Clock.fixed(Instant.parse(FIXED_MIDNIGHT), ZoneOffset.UTC), DATASET_CHARSET)
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
                    new InternalReaderJobSubmissionPort(properties(root, destination), DATASET_CHARSET);

            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01))
                    .isEqualTo(WriteQueueOutcome.NORMAL);
            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_17).normal()).isTrue();

            assertThat(Files.readAllBytes(destination)).hasSize(160);
            assertThat(submitter.properties().queueName()).isEqualTo("JOBS");
            assertThat(submitter.properties().ddName()).isEqualTo("INREADER");
            assertThat(submitter.queueCharset())
                    .as("the records are encoded in the configured code page, not in a hardwired one")
                    .isEqualTo(DATASET_CHARSET);
            assertThat(WriteQueueOutcome.NORMAL.fileStatus()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(WriteQueueOutcome.notOpen().fileStatus()).isEqualTo(FileStatus.Outcome.OTHER);
        }

        // -----------------------------------------------------------------------------------------
        // Inter-process record integrity. The instance monitor orders this JVM's threads and nothing
        // else, and an internal reader is a queue because several writers feed it. A write that landed
        // in two pieces with another writer's bytes between them is not one damaged record of a
        // RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED) queue - it is two, and every record after them
        // shifted. So the drain loop is held under an exclusive FileLock over the destination.
        // -----------------------------------------------------------------------------------------

        @Test
        @DisplayName("the append takes an exclusive lock on the destination and releases it after")
        void theAppendIsHeldUnderAnExclusiveFileLock(@TempDir Path root) throws IOException {
            Path destination = root.resolve("inreader").resolve("JOBS");
            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination), DATASET_CHARSET);

            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).normal()).isTrue();

            // Released: a second, independent channel can take the same exclusive lock afterwards. A lock
            // left held would make the next writer - this application's next request included - block or
            // fail for ever, so releasing it is as much of the contract as taking it. Within one JVM an
            // overlapping request raises OverlappingFileLockException rather than blocking, so this
            // succeeding is precisely the evidence that nothing is still held.
            try (FileChannel probe = FileChannel.open(destination, StandardOpenOption.WRITE);
                    FileLock taken = probe.lock()) {
                assertThat(taken.isValid())
                        .as("the port released its lock, so an overlapping one can now be taken")
                        .isTrue();
            }

            // And exclusive rather than shared - proved structurally rather than by inspecting the port's
            // own lock, which is not observable from out here. The port opens the destination for WRITE and
            // APPEND and never for READ, and a shared lock over a channel that cannot be read is refused by
            // the platform. So the only lock the port is able to take over that channel is an exclusive
            // one: a downgrade to lock(0, Long.MAX_VALUE, true) would not weaken the guarantee quietly, it
            // would fail every append outright.
            try (FileChannel asThePortOpensIt = FileChannel.open(destination,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                    LinkOption.NOFOLLOW_LINKS)) {
                assertThatExceptionOfType(NonReadableChannelException.class)
                        .as("a shared lock is not available over the channel the port opens")
                        .isThrownBy(() -> asThePortOpensIt.lock(0L, Long.MAX_VALUE, true));
                try (FileLock exclusive = asThePortOpensIt.lock()) {
                    assertThat(exclusive.isShared())
                            .as("whereas the exclusive lock the port takes is available, and is exclusive")
                            .isFalse();
                }
            }

            assertThat(Files.readAllBytes(destination))
                    .as("and the record itself is one whole eighty-byte record")
                    .hasSize(80);
        }

        @Test
        @Timeout(value = 120, unit = TimeUnit.SECONDS)
        @DisplayName("two ports over one destination never tear a record: each write lands whole or is "
                + "refused whole")
        void twoPortInstancesContendingNeverTearARecord(@TempDir Path root) throws Exception {
            // The monitor orders one port's threads. Two ports have two monitors, so the monitor orders
            // nothing between them - which is the configuration the finding is about, and the one the file
            // lock is here for. Under it every write must be all-or-nothing: the queue may end up short of
            // records, and the refusals say so, but it must never contain a fragment of one, because a
            // RECORDFORMAT(FIXED) reader takes eighty bytes at a time and a fragment shifts every record
            // after it.
            Path destination = root.resolve("inreader").resolve("JOBS");
            JobSubmissionProperties properties = properties(root, destination);
            InternalReaderJobSubmissionPort first =
                    new InternalReaderJobSubmissionPort(properties, DATASET_CHARSET);
            InternalReaderJobSubmissionPort second =
                    new InternalReaderJobSubmissionPort(properties, DATASET_CHARSET);

            int perPort = 40;
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger refused = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> submitted = new ArrayList<>();
            for (InternalReaderJobSubmissionPort port : List.of(first, second)) {
                String record = pad("//P" + (port == first ? 0 : 1));
                submitted.add(pool.submit(() -> {
                    start.await();
                    for (int written = 0; written < perPort; written++) {
                        if (port.writeQueueTd(record).normal()) {
                            accepted.incrementAndGet();
                        } else {
                            refused.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            try {
                start.countDown();
                for (Future<?> future : submitted) {
                    future.get(ConcurrentTasks.TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
            } finally {
                // In a finally, and shutdownNow rather than shutdown: if a future.get above throws, the
                // pool would otherwise leak its threads into the next test in this JVM fork.
                pool.shutdownNow();
            }
            assertThat(pool.awaitTermination(ConcurrentTasks.TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("the pool must terminate - a writer still running would corrupt the next test")
                    .isTrue();

            assertThat(accepted.get() + refused.get()).isEqualTo(2 * perPort);
            byte[] queue = Files.readAllBytes(destination);
            assertThat(queue)
                    .as("exactly the accepted writes are on the queue - a refusal wrote nothing at all")
                    .hasSize(accepted.get() * 80);
            // Decoded in the configured code page, which is the one these ports were given - the records on
            // the queue are EBCDIC bytes, not ASCII ones.
            for (int offset = 0; offset < queue.length; offset += 80) {
                assertThat(new String(queue, offset, 80, DATASET_CHARSET))
                        .as("every eighty-byte slot is one port's whole record, never a splice of two")
                        .matches("//P[01] {76}");
            }
        }

        @Test
        @DisplayName("a destination another process holds locked is refused as NOTOPEN, unwritten")
        void aLockedDestinationIsRefusedRatherThanTorn(@TempDir Path root) throws IOException {
            Path destination = root.resolve("inreader").resolve("JOBS");
            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination), DATASET_CHARSET);
            // Seed one record so the file exists and holds a known, whole record before the contention.
            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).normal()).isTrue();

            // A lock this JVM already holds. FileLock is owned by the JVM rather than by a thread, so an
            // overlapping request raises OverlappingFileLockException - which stands in here for the
            // condition a genuinely separate process produces, and is the one the port must not let
            // escape as an unchecked exception out of a WRITEQ TD.
            try (FileChannel holder = FileChannel.open(destination, StandardOpenOption.WRITE);
                    FileLock held = holder.lock()) {
                assertThat(held.isValid()).isTrue();

                WriteQueueOutcome refused = submitter.writeQueueTd(ReportRequestController.JOB_LINE_17);

                assertThat(refused.normal()).isFalse();
                assertThat(refused.fileStatus()).isEqualTo(FileStatus.Outcome.OTHER);
                assertThat(refused).isEqualTo(WriteQueueOutcome.notOpen());
            }

            assertThat(Files.readAllBytes(destination))
                    .as("the refused record was NOT written: one whole record, not one and a fragment")
                    .hasSize(80);
        }

        @Test
        @DisplayName("a lock that is no longer held refuses the write rather than running it unprotected")
        void aReleasedLockRefusesTheWrite(@TempDir Path root) throws Exception {
            // assertHeld is what makes the lock a named participant in the drain loop rather than an
            // object created and forgotten. Its failure arm cannot be reached from outside - nothing can
            // invalidate this JVM's lock mid-loop - so it is reached directly here. An assertion that has
            // never once executed is indistinguishable from a broken one.
            Path destination = root.resolve("inreader").resolve("JOBS");
            Files.createDirectories(destination.getParent());
            Files.createFile(destination);

            FileLock released;
            try (FileChannel channel = FileChannel.open(destination, StandardOpenOption.WRITE)) {
                released = channel.lock();
                released.release();
            }
            assertThat(released.isValid())
                    .as("a released lock is exactly the state the guard exists to detect")
                    .isFalse();

            Method assertHeld = InternalReaderJobSubmissionPort.class
                    .getDeclaredMethod("assertHeld", FileLock.class);
            assertHeld.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(() -> assertHeld.invoke(null, released))
                    .withCauseInstanceOf(IOException.class)
                    .satisfies(raised -> assertThat(raised.getCause())
                            .as("and says the record was not written, because an IOException from here "
                                    + "is reported to the caller as RESP NOTOPEN")
                            .hasMessageContaining("NOT written"));
        }

        @Test
        @DisplayName("a lock that is held lets the write proceed, so the guard is not simply always on")
        void aHeldLockPermitsTheWrite(@TempDir Path root) throws Exception {
            // The other arm. A guard that refused everything would pass the test above and break every
            // append, so the arm the port actually takes is exercised too.
            Path destination = root.resolve("inreader").resolve("JOBS");
            Files.createDirectories(destination.getParent());
            Files.createFile(destination);

            Method assertHeld = InternalReaderJobSubmissionPort.class
                    .getDeclaredMethod("assertHeld", FileLock.class);
            assertHeld.setAccessible(true);

            try (FileChannel channel = FileChannel.open(destination, StandardOpenOption.WRITE);
                    FileLock held = channel.lock()) {
                assertThatCode(() -> assertHeld.invoke(null, held)).doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("many appends in sequence leave whole records only, never a partial one")
        void everyAppendLeavesAWholeRecord(@TempDir Path root) throws IOException {
            Path destination = root.resolve("inreader").resolve("JOBS");
            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination), DATASET_CHARSET);

            for (int written = 0; written < 25; written++) {
                assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).normal()).isTrue();
            }

            byte[] queue = Files.readAllBytes(destination);
            assertThat(queue).hasSize(25 * 80);
            assertThat(queue.length % 80)
                    .as("a reader takes this destination eighty bytes at a time, so the total must divide")
                    .isZero();
        }

        @Test
        @DisplayName("a record of the wrong width, or one the code page cannot carry, is refused")
        void theRefusals(@TempDir Path root) throws IOException {
            Path destination = root.resolve("inreader").resolve("JOBS");
            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination), DATASET_CHARSET);

            assertThat(submitter.writeQueueTd("too short").resp()).isEqualTo(FileStatus.LENGERR);
            assertThat(submitter.writeQueueTd(" ".repeat(81)).resp()).isEqualTo(FileStatus.LENGERR);

            // The premise is asserted rather than assumed, because the port's code page now comes from
            // configuration: if a future binding could represent this character, the refusal below
            // would stop being a refusal and this case would silently stop testing anything.
            assertThat(DATASET_CHARSET.newEncoder().canEncode('\u20ac'))
                    .as("%s cannot represent U+20AC, which is what makes the record unwritable",
                            DATASET_CHARSET.name())
                    .isFalse();
            assertThat(submitter.writeQueueTd("\u20ac".repeat(80)).resp()).isEqualTo(FileStatus.INVREQ);

            // A fixed-width record area is addressed by absolute byte offset, so a code page that
            // encodes one character to more than one byte cannot carry it: INVREQ, never a 160-byte
            // "eighty-byte" record. U+00E9 is deliberately a character IBM037 does carry in one byte,
            // so what this case isolates is the WIDTH, not the representability tested above.
            assertThat(DATASET_CHARSET.newEncoder().canEncode('\u00e9')).isTrue();
            InternalReaderJobSubmissionPort utf8 = new InternalReaderJobSubmissionPort(
                    properties(root, destination), StandardCharsets.UTF_8);
            assertThat(utf8.writeQueueTd("\u00e9".repeat(80)).resp()).isEqualTo(FileStatus.INVREQ);
        }

        @Test
        @DisplayName("every character the JCL skeletons actually contain survives the configured page")
        void theSkeletonTextIsCarriedByTheConfiguredCodePage(@TempDir Path root) throws IOException {
            Path destination = root.resolve("inreader").resolve("JOBS");
            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination), DATASET_CHARSET);

            // Gate G42 requires the emitted records to be byte-identical to the source's 80-byte
            // skeleton records. Under a configured code page that means every character of every
            // skeleton line must encode to exactly one byte in it - so all seventeen are written, and
            // the resulting file is asserted to be exactly seventeen eighty-byte records.
            List<String> skeleton = List.of(ReportRequestController.JOB_LINE_01,
                    ReportRequestController.JOB_LINE_02, ReportRequestController.JOB_LINE_03,
                    ReportRequestController.JOB_LINE_04, ReportRequestController.JOB_LINE_05,
                    ReportRequestController.JOB_LINE_06, ReportRequestController.JOB_LINE_07,
                    ReportRequestController.JOB_LINE_08, ReportRequestController.JOB_LINE_09,
                    ReportRequestController.JOB_LINE_10, ReportRequestController.JOB_LINE_11,
                    ReportRequestController.JOB_LINE_12, ReportRequestController.JOB_LINE_13,
                    ReportRequestController.JOB_LINE_14, ReportRequestController.JOB_LINE_15,
                    ReportRequestController.JOB_LINE_16, ReportRequestController.JOB_LINE_17);
            assertThat(skeleton).hasSize(ReportRequestController.JOB_LINE_COUNT);

            for (String line : skeleton) {
                assertThat(submitter.writeQueueTd(line))
                        .as("skeleton line '%s' is writable in %s", line.strip(), DATASET_CHARSET.name())
                        .isEqualTo(WriteQueueOutcome.NORMAL);
            }

            assertThat(Files.readAllBytes(destination))
                    .hasSize(skeleton.size() * JobSubmissionProperties.TDQ_RECORD_LENGTH);
        }

        @Test
        @DisplayName("an unusable destination reports NOTOPEN per write rather than failing start-up")
        void theUnusableDestination(@TempDir Path root) throws IOException {
            InternalReaderJobSubmissionPort rootless =
                    new InternalReaderJobSubmissionPort(properties(root, Path.of("/")),
                            DATASET_CHARSET);
            assertThat(rootless.writeQueueTd(ReportRequestController.JOB_LINE_01).resp())
                    .isEqualTo(FileStatus.NOTOPEN);

            Path occupied = Files.createFile(root.resolve("occupied"));
            InternalReaderJobSubmissionPort blocked = new InternalReaderJobSubmissionPort(
                    properties(root, occupied.resolve("JOBS")), DATASET_CHARSET);
            assertThat(blocked.writeQueueTd(ReportRequestController.JOB_LINE_01).resp())
                    .isEqualTo(FileStatus.NOTOPEN);
        }

        @Test
        @DisplayName("a symbolic link at the destination itself is refused, not followed")
        void aLinkedDestinationIsRefused(@TempDir Path root) throws IOException {
            // The whole point of DISPOSITION(MOD): a wrong target does not fail, it succeeds against
            // the wrong file. A link planted at the destination satisfies every lexical check the
            // startup validation can make - it is absolute, has no '..' segment, names no reference
            // tree and lies inside the approved root - while sending every 80-byte record to whatever
            // it points at (CWE-59).
            Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
            Path stolen = elsewhere.resolve("STOLEN");
            Path inreader = Files.createDirectories(root.resolve("inreader"));
            Path destination = inreader.resolve("JOBS");
            Files.createSymbolicLink(destination, stolen);

            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination));

            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).resp())
                    .isEqualTo(FileStatus.NOTOPEN);
            assertThat(Files.exists(stolen)).isFalse();
        }

        @Test
        @DisplayName("a symbolic link on the way to the destination is refused too")
        void aLinkedDirectoryComponentIsRefused(@TempDir Path root) throws IOException {
            // The link need not be the leaf. A link where a directory is expected redirects everything
            // beneath it, and no amount of path algebra performed at start-up can see it.
            Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
            Files.createSymbolicLink(root.resolve("inreader"), elsewhere);
            Path destination = root.resolve("inreader").resolve("JOBS");

            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination));

            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).resp())
                    .isEqualTo(FileStatus.NOTOPEN);
            assertThat(Files.exists(elsewhere.resolve("JOBS"))).isFalse();
        }

        @Test
        @DisplayName("an approved root that does not exist is a deployment fault, reported as NOTOPEN")
        void anAbsentApprovedRootIsRefused(@TempDir Path root) {
            // The root is operator-provisioned on purpose: it is the boundary every write is checked
            // against, and a port that created its own boundary would be checking its own work. Its
            // absence is therefore reported rather than repaired.
            Path missing = root.resolve("never-provisioned");
            InternalReaderJobSubmissionPort submitter = new InternalReaderJobSubmissionPort(
                    properties(missing, missing.resolve("inreader").resolve("JOBS")));

            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).resp())
                    .isEqualTo(FileStatus.NOTOPEN);
        }

        @Test
        @DisplayName("directories below the root are created one inspected level at a time")
        void theDirectoriesBelowTheRootAreCreated(@TempDir Path root) throws IOException {
            Path destination = root.resolve("inreader").resolve("today").resolve("JOBS");
            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination));

            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).normal()).isTrue();

            assertThat(Files.isDirectory(root.resolve("inreader"))).isTrue();
            assertThat(Files.readAllBytes(destination)).hasSize(80);
        }

        /**
         * The write-time containment re-check, driven directly.
         *
         * <p>It exists to catch a directory being substituted <em>after</em> the descent inspected it and
         * <em>before</em> the leaf is opened. That race cannot be staged reliably from a single-threaded
         * test, but the two states it would produce can be handed to the check as inputs, which is what
         * makes the guard known to work rather than assumed to.
         */
        @Test
        @DisplayName("a directory that resolves elsewhere than itself is refused before the open")
        void aSubstitutedParentIsRefused(@TempDir Path root) throws IOException {
            Path real = Files.createDirectory(root.resolve("real"));
            Path substituted = Files.createSymbolicLink(root.resolve("inreader"), real);

            assertThatExceptionOfType(IOException.class)
                    .isThrownBy(() -> InternalReaderJobSubmissionPort
                            .requireParentStillWithinRoot(substituted, root.toRealPath()))
                    .withMessageContaining("resolves elsewhere");

            // And the honest case passes: a real directory below the root resolves to itself.
            InternalReaderJobSubmissionPort.requireParentStillWithinRoot(real.toRealPath(),
                    root.toRealPath());
        }

        @Test
        @DisplayName("a directory that resolves outside the approved root is refused before the open")
        void aParentOutsideTheRootIsRefused(@TempDir Path root, @TempDir Path elsewhere)
                throws IOException {
            Path outside = Files.createDirectory(elsewhere.resolve("inreader")).toRealPath();

            assertThatExceptionOfType(IOException.class)
                    .isThrownBy(() -> InternalReaderJobSubmissionPort
                            .requireParentStillWithinRoot(outside, root.toRealPath()))
                    .withMessageContaining("resolves elsewhere");
        }

        @Test
        @DisplayName("a binding that is not the CSD's RECORDSIZE(80) is refused at construction")
        void theBindingIsCheckedAgainstTheCsd(@TempDir Path root) {
            assertThatIllegalStateException().isThrownBy(() -> new InternalReaderJobSubmissionPort(
                    new JobSubmissionProperties("JOBS", "INREADER", "US-ASCII", 79, "FIXED",
                            "UNBLOCKED", "MOD", root.toString(),
                            root.resolve("JOBS").toString())));
        }

        @Test
        @DisplayName("the code page comes from the configuration, not from a hard-wired default")
        void theCodePageIsTheConfiguredOne(@TempDir Path root) throws IOException {
            // An internal reader consumes EBCDIC. A port that hard-wired US-ASCII would append eighty
            // bytes of the wrong code page and report NORMAL for each, which is the failure mode this
            // property exists to remove: the bytes look like the right length and are the wrong text.
            Path destination = root.resolve("inreader").resolve("JOBS");
            JobSubmissionProperties ebcdic = new JobSubmissionProperties("JOBS", "INREADER", "IBM037",
                    80, "FIXED", "UNBLOCKED", "MOD", root.toString(), destination.toString());

            InternalReaderJobSubmissionPort submitter = new InternalReaderJobSubmissionPort(ebcdic);

            assertThat(submitter.queueCharset()).isEqualTo(Charset.forName("IBM037"));
            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).normal()).isTrue();

            byte[] written = Files.readAllBytes(destination);
            assertThat(written).hasSize(80);
            // Byte-for-byte the EBCDIC encoding of the record, and demonstrably NOT the ASCII one -
            // which is the whole distinction the finding was about (gate G42).
            assertThat(written).isEqualTo(
                    ReportRequestController.JOB_LINE_01.getBytes(Charset.forName("IBM037")));
            assertThat(written).isNotEqualTo(
                    ReportRequestController.JOB_LINE_01.getBytes(StandardCharsets.US_ASCII));
            // The text is recoverable, so the record is the record CORPT00C composed and not mojibake.
            assertThat(new String(written, Charset.forName("IBM037")))
                    .isEqualTo(ReportRequestController.JOB_LINE_01);
        }

        @Test
        @DisplayName("a destination that is a symbolic link is refused rather than followed")
        void aSymbolicLinkDestinationIsRefused(@TempDir Path root) throws IOException {
            // CWE-59. The destination's path is in a configuration file, so it is knowable; if it is
            // replaced by a link before the first write, an unhardened append lands on the link's
            // target instead - job text naming datasets and users, written wherever somebody else chose.
            Path outside = Files.createFile(root.resolve("elsewhere"));
            Path directory = Files.createDirectories(root.resolve("inreader"));
            Path destination = directory.resolve("JOBS");
            assumeSymbolicLinksSupported(() -> Files.createSymbolicLink(destination, outside));

            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination));

            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).resp())
                    .isEqualTo(FileStatus.NOTOPEN);
            // The link's target is untouched: the refusal happened at the open, not after a partial
            // write.
            assertThat(Files.readAllBytes(outside)).isEmpty();
        }

        @Test
        @DisplayName("a directory element that is a symbolic link is refused rather than followed")
        void aSymbolicLinkDirectoryElementIsRefused(@TempDir Path root) throws IOException {
            Path outside = Files.createDirectories(root.resolve("elsewhere"));
            Path linked = root.resolve("inreader");
            assumeSymbolicLinksSupported(() -> Files.createSymbolicLink(linked, outside));

            InternalReaderJobSubmissionPort submitter = new InternalReaderJobSubmissionPort(
                    properties(root, linked.resolve("JOBS")));

            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).resp())
                    .isEqualTo(FileStatus.NOTOPEN);
            assertThat(Files.exists(outside.resolve("JOBS"))).isFalse();
        }

        @Test
        @DisplayName("a directory that resolves outside the approved root once links are followed is "
                + "refused")
        void anEscapingRealPathIsRefused(@TempDir Path parent) throws IOException {
            // JobSubmissionProperties.validate() proved containment over the configured STRINGS, and
            // deliberately touched no filesystem - so what it cannot see is a link that leaves the tree.
            // The writer therefore re-establishes containment against real paths immediately before the
            // open, which is also why it is re-checked per write rather than once at start-up.
            Path root = Files.createDirectories(parent.resolve("approved"));
            Path escape = Files.createDirectories(parent.resolve("outside").resolve("inreader"));
            Path linked = root.resolve("inreader");
            assumeSymbolicLinksSupported(() -> Files.createSymbolicLink(linked, escape));

            InternalReaderJobSubmissionPort submitter = new InternalReaderJobSubmissionPort(
                    properties(root, linked.resolve("JOBS")));

            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).resp())
                    .isEqualTo(FileStatus.NOTOPEN);
            assertThat(Files.exists(escape.resolve("JOBS"))).isFalse();
        }

        @Test
        @DisplayName("the destination and every directory this port creates are owner-only")
        void theCreatedPathIsOwnerOnly(@TempDir Path root) throws IOException {
            // CWE-377. The previous default put the destination beneath the shared temporary directory,
            // where a world-writable predictable path could be pre-created or read by any local account.
            // The root moved out of it, and creation is owner-only so a wider-permissioned deployment
            // directory does not re-open the same exposure through inheritance.
            Path destination = root.resolve("inreader").resolve("deep").resolve("JOBS");
            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination));

            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).normal()).isTrue();

            assumeThat(FileSystems.getDefault().supportedFileAttributeViews()).contains("posix");
            assertThat(Files.getPosixFilePermissions(destination))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE);
            assertThat(Files.getPosixFilePermissions(root.resolve("inreader")))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
            assertThat(Files.getPosixFilePermissions(destination.getParent()))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        }

        @Test
        @DisplayName("an existing directory tree is appended to rather than re-created or refused")
        void anExistingTreeIsAppendedTo(@TempDir Path root) throws IOException {
            // The hardened creation must not have made the ordinary case - a deployment whose directory
            // already exists, with whatever permissions the deployment chose - into a failure.
            Path directory = Files.createDirectories(root.resolve("inreader"));
            Path destination = directory.resolve("JOBS");
            Files.write(destination, "existing".getBytes(StandardCharsets.US_ASCII));

            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination));

            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).normal()).isTrue();

            // DISPOSITION(MOD): the eight bytes already there survive and the record follows them.
            assertThat(Files.readAllBytes(destination)).hasSize(88);
            assertThat(new String(Files.readAllBytes(destination), StandardCharsets.US_ASCII))
                    .startsWith("existing")
                    .endsWith(ReportRequestController.JOB_LINE_01);
        }

        /**
         * Creates a symbolic link, skipping the calling test where the filesystem will not have one.
         *
         * <p>Every hardening these tests assert is about links, so a filesystem without them - a Windows
         * volume without the privilege, for instance - has nothing to assert rather than something
         * broken. Skipped rather than passed vacuously, so a run that could not exercise the hardening
         * says so.
         *
         * @param link the link to create
         * @throws IOException if the link cannot be created for a reason other than lack of support
         */
        private void assumeSymbolicLinksSupported(LinkCreation link) throws IOException {
            try {
                link.create();
            } catch (UnsupportedOperationException | FileSystemException unsupported) {
                abort("This filesystem does not support symbolic links, so there is no link-following "
                        + "hazard to assert against here: " + unsupported.getClass().getName());
            }
        }

        /** A symbolic-link creation that may not be supported. */
        @FunctionalInterface
        private interface LinkCreation {

            /**
             * Creates the link.
             *
             * @throws IOException if it cannot be created
             */
            void create() throws IOException;
        }

        // ----------------------------------------------------- the code page the records actually carry

        @Test
        @DisplayName("the injected code page is the one the records are encoded in, EBCDIC included")
        void theInjectedCodePageIsWhatTheRecordsCarry(@TempDir Path root) throws IOException {
            // A region whose internal reader expects EBCDIC gets EBCDIC. Under IBM037 a '/' is 0x61, so
            // the two leading slashes of every JCL record are observable in the destination's bytes - and
            // they are NOT 0x2F, which is what US-ASCII would have written.
            Charset ebcdic = Charset.forName("IBM037");
            Path destination = root.resolve("inreader").resolve("JOBS");
            InternalReaderJobSubmissionPort submitter = new InternalReaderJobSubmissionPort(
                    properties(root, destination), ebcdic);

            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).normal()).isTrue();

            byte[] written = Files.readAllBytes(destination);
            assertThat(written).hasSize(80);
            assertThat(written).isEqualTo(ReportRequestController.JOB_LINE_01.getBytes(ebcdic));
            assertThat(written[0]).isEqualTo((byte) 0x61);
            assertThat(submitter.queueCharset()).isEqualTo(ebcdic);
        }

        @Test
        @DisplayName("Spring selects the constructor that reads carddemo.job-submission.charset, and no "
                + "code page arrives on this port by qualifier")
        void theInjectedConstructorIsTheOneSpringSelects() throws Exception {
            // The queue's code page and the datasets' code page are two independent settings, and under
            // the shipped configuration they disagree: carddemo.job-submission.charset defaults to IBM037
            // because an internal reader consumes EBCDIC, carddemo.charset.dataset to US-ASCII in the test
            // profile because the fixtures are ASCII. @Autowired sat on the two-argument constructor with
            // @Qualifier(DATASET_CHARSET_BEAN_NAME), so Spring built the port with the DATASET code page
            // and the configured queue code page reached nothing at all. The annotation belongs on the
            // constructor that reads the property, and the explicit overload must carry no qualifier -
            // otherwise the same mistake is one annotation away from returning.
            Constructor<?> fromProperties = InternalReaderJobSubmissionPort.class.getConstructor(
                    JobSubmissionProperties.class);
            Constructor<?> explicit = InternalReaderJobSubmissionPort.class.getConstructor(
                    JobSubmissionProperties.class, Charset.class);

            assertThat(fromProperties.isAnnotationPresent(Autowired.class))
                    .as("the configured queue code page must be the injection point")
                    .isTrue();
            assertThat(explicit.isAnnotationPresent(Autowired.class))
                    .as("the caller-supplied code page must not be")
                    .isFalse();
            assertThat(explicit.getParameters()[1].getAnnotation(Qualifier.class))
                    .as("no code page reaches this port by qualifier - that is what sent the dataset's "
                            + "page to the queue")
                    .isNull();
            assertThat(Arrays.stream(InternalReaderJobSubmissionPort.class.getConstructors())
                    .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                    .count())
                    .as("exactly one injection point, so which constructor Spring picks is not a guess")
                    .isEqualTo(1);

            // Same wiring on the controller, whose working-storage images are rendered the same way.
            Constructor<?> controller = ReportRequestController.class.getConstructor(
                    DateUtilityJob.class, JobSubmissionPort.class, Clock.class, Charset.class);
            assertThat(controller.isAnnotationPresent(Autowired.class)).isTrue();
            assertThat(controller.getParameters()[3].getAnnotation(Qualifier.class).value())
                    .isEqualTo(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME);
            // Stronger than "the three-argument constructor is not the injection point": there IS no
            // three-argument constructor any more. A convenience overload that defaulted the code page
            // was the path by which US-ASCII reached a region configured for IBM037, so it was removed
            // rather than merely left unannotated - nothing can default the code page now.
            assertThat(ReportRequestController.class.getConstructors())
                    .as("one constructor, and it takes the code page")
                    .hasSize(1);
            assertThat(ReportRequestController.class.getConstructors()[0].getParameterTypes())
                    .containsExactly(DateUtilityJob.class, JobSubmissionPort.class, Clock.class,
                            Charset.class);
        }

        @Test
        @DisplayName("in a context where the two code pages differ, the queue's records are written in "
                + "the QUEUE's code page")
        void theQueueCodePageWinsOverTheDatasetCodePage(@TempDir Path root) throws IOException {
            // The wiring assertion above says which constructor Spring picks. This says what that means
            // in bytes, in a real context, with the two settings deliberately set to different values -
            // which is the only arrangement in which the defect was visible at all. The dataset page is
            // US-ASCII and the queue page is IBM037, so a '/' is 0x2F if the port took the dataset bean
            // and 0x61 if it took the configured queue page.
            Path destination = root.resolve("inreader").resolve("JOBS");

            new ApplicationContextRunner()
                    .withUserConfiguration(CobolCharsetConfig.class, BoundJobSubmission.class)
                    .withBean(InternalReaderJobSubmissionPort.class)
                    .withPropertyValues(
                            CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY + "=IBM037",
                            CobolCharsetConfig.ASCII_CHARSET_PROPERTY + "=US-ASCII",
                            CobolCharsetConfig.DATASET_CHARSET_PROPERTY + "=US-ASCII",
                            "carddemo.job-submission.queue-name=JOBS",
                            "carddemo.job-submission.dd-name=INREADER",
                            "carddemo.job-submission.charset=IBM037",
                            "carddemo.job-submission.record-length=80",
                            "carddemo.job-submission.record-format=FIXED",
                            "carddemo.job-submission.block-format=UNBLOCKED",
                            "carddemo.job-submission.disposition=MOD",
                            "carddemo.job-submission.approved-root=" + root,
                            "carddemo.job-submission.destination=" + destination)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        InternalReaderJobSubmissionPort submitter =
                                context.getBean(InternalReaderJobSubmissionPort.class);
                        Charset queue = Charset.forName("IBM037");

                        assertThat(submitter.queueCharset())
                                .as("the configured queue page, not the dataset bean")
                                .isEqualTo(queue)
                                .isNotEqualTo(context.getBean(
                                        CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME, Charset.class));
                        assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).normal())
                                .isTrue();

                        byte[] written = Files.readAllBytes(destination);
                        assertThat(written).hasSize(80)
                                .isEqualTo(ReportRequestController.JOB_LINE_01.getBytes(queue));
                        assertThat(written[0])
                                .as("EBCDIC '/' is 0x61; US-ASCII would have written 0x2F")
                                .isEqualTo((byte) 0x61);
                    });
        }

        /**
         * Binds {@code carddemo.job-submission} so the port can be constructed the way Spring constructs
         * it - from the property record rather than from a hand-built instance.
         */
        @Configuration
        @EnableConfigurationProperties(JobSubmissionProperties.class)
        static class BoundJobSubmission {
        }

        // --------------------------------------------------- one complete record per write, in order

        @Test
        @Timeout(value = 120, unit = TimeUnit.SECONDS)
        @DisplayName("concurrent writes each land as one whole 80-byte record, none interleaved")
        void concurrentWritesAreWholeRecords(@TempDir Path root) throws Exception {
            // RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED) means the reader takes the destination eighty
            // bytes at a time. A write that landed in two pieces with another request's bytes between
            // them would not corrupt one record, it would corrupt two and shift every record after them.
            //
            // What this test proves, stated plainly: the invariant holds under contention. It does not
            // prove the lock is what holds it here, because a POSIX provider's O_APPEND already writes
            // eighty bytes indivisibly and this test cannot make it do otherwise. The lock is what makes
            // the invariant independent of the provider - java.nio.file promises nothing about it - and
            // what makes the containment check and the open one indivisible step.
            Path destination = root.resolve("inreader").resolve("JOBS");
            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination));
            int writers = 8;
            int perWriter = 25;
            ExecutorService pool = Executors.newFixedThreadPool(writers);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> submitted = new ArrayList<>();
            for (int writer = 0; writer < writers; writer++) {
                String record = pad("//W" + writer);
                submitted.add(pool.submit(() -> {
                    start.await();
                    for (int written = 0; written < perWriter; written++) {
                        assertThat(submitter.writeQueueTd(record).normal()).isTrue();
                    }
                    return null;
                }));
            }
            try {
                start.countDown();
                for (Future<?> future : submitted) {
                    future.get(ConcurrentTasks.TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
            } finally {
                // In a finally, and shutdownNow rather than shutdown: if a future.get above throws, the
                // pool would otherwise leak its threads into the next test in this JVM fork.
                pool.shutdownNow();
            }
            assertThat(pool.awaitTermination(ConcurrentTasks.TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("the pool must terminate - a writer still running would corrupt the next test")
                    .isTrue();

            byte[] written = Files.readAllBytes(destination);
            assertThat(written).hasSize(writers * perWriter * 80);
            // Every 80-byte slot must be exactly one writer's record - not a splice of two.
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (int offset = 0; offset < written.length; offset += 80) {
                String slot = new String(written, offset, 80, StandardCharsets.US_ASCII);
                assertThat(slot).matches("//W\\d {76}");
                counts.merge(slot, 1, Integer::sum);
            }
            assertThat(counts).hasSize(writers);
            assertThat(counts.values()).allMatch(count -> count == perWriter);
        }

        // ------------------------------------------------------------- CWE-59 and CWE-367 containment

        @Test
        @DisplayName("a symlinked approved root cannot redirect the append")
        void aSymlinkedRootIsRefused(@TempDir Path scratch) throws IOException {
            // The plainest form of the redirection: /tmp/carddemo is a link to somewhere else. Lexical
            // containment cannot see it, because both configured paths still read as contained.
            Path elsewhere = Files.createDirectories(scratch.resolve("elsewhere"));
            Path root = scratch.resolve("approved");
            assumeSymlinksSupported(() -> Files.createSymbolicLink(root, elsewhere));
            Path destination = root.resolve("JOBS");
            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination));

            // The write is refused rather than redirected, and NOTOPEN is what an extrapartition queue
            // whose dataset cannot be opened reports.
            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).resp())
                    .isEqualTo(FileStatus.NOTOPEN);
            assertThat(Files.exists(elsewhere.resolve("JOBS")))
                    .as("nothing may be written through the link")
                    .isFalse();
        }

        @Test
        @DisplayName("a symlinked destination file cannot redirect the append - NOFOLLOW_LINKS")
        void aSymlinkedDestinationIsRefused(@TempDir Path root) throws IOException {
            // The one a containment check cannot reach: the final component is where checking stops and
            // opening begins, so it is the open that has to refuse. O_NOFOLLOW leaves no window at all.
            Path outside = Files.createFile(root.resolve("outside.txt"));
            Path directory = Files.createDirectories(root.resolve("inreader"));
            Path destination = directory.resolve("JOBS");
            assumeSymlinksSupported(() -> Files.createSymbolicLink(destination, outside));
            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination));

            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).resp())
                    .isEqualTo(FileStatus.NOTOPEN);
            assertThat(Files.size(outside))
                    .as("the linked-to file must not have received the record")
                    .isZero();
        }

        @Test
        @DisplayName("a symlinked directory between the root and the file cannot redirect the append")
        void aSymlinkedParentIsRefused(@TempDir Path scratch) throws IOException {
            Path root = Files.createDirectories(scratch.resolve("approved"));
            Path elsewhere = Files.createDirectories(scratch.resolve("elsewhere"));
            Path directory = root.resolve("inreader");
            assumeSymlinksSupported(() -> Files.createSymbolicLink(directory, elsewhere));
            Path destination = directory.resolve("JOBS");
            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination));

            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).resp())
                    .isEqualTo(FileStatus.NOTOPEN);
            assertThat(Files.exists(elsewhere.resolve("JOBS"))).isFalse();
        }

        @Test
        @DisplayName("containment is revalidated per write, so a root swapped mid-run stops the appends")
        void containmentIsRevalidatedPerWrite(@TempDir Path scratch) throws IOException {
            // The time-of-check window (CWE-367). The first write establishes a legitimate destination;
            // the root is then replaced by a link, as an attacker who wins the race would arrange. The
            // second write must refuse, which it can only do if it checks again rather than trusting what
            // the first write established.
            Path root = Files.createDirectories(scratch.resolve("approved"));
            Path elsewhere = Files.createDirectories(scratch.resolve("elsewhere"));
            Path destination = root.resolve("inreader").resolve("JOBS");
            InternalReaderJobSubmissionPort submitter =
                    new InternalReaderJobSubmissionPort(properties(root, destination));
            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_01).normal()).isTrue();
            assertThat(Files.readAllBytes(destination)).hasSize(80);

            Files.delete(destination);
            Files.delete(destination.getParent());
            Files.delete(root);
            assumeSymlinksSupported(() -> Files.createSymbolicLink(root, elsewhere));

            assertThat(submitter.writeQueueTd(ReportRequestController.JOB_LINE_02).resp())
                    .isEqualTo(FileStatus.NOTOPEN);
            assertThat(Files.exists(elsewhere.resolve("inreader"))).isFalse();
        }

        /**
         * Creates a symbolic link, skipping the test where the filesystem will not have one.
         *
         * @param link the link to create
         */
        private void assumeSymlinksSupported(SymlinkCreation link) {
            try {
                link.create();
            } catch (IOException | UnsupportedOperationException unsupported) {
                Assumptions.abort("this filesystem does not support symbolic links, so the redirection "
                        + "these tests defend against cannot be constructed here: "
                        + unsupported.getMessage());
            }
        }

        /** A symbolic-link creation that may not be supported. */
        private interface SymlinkCreation {
            void create() throws IOException;
        }
    }

    // =================================================================================================
    // The HTTP surface
    // =================================================================================================

    @Nested
    @DisplayName("the HTTP surface - a thin adapter over MAIN-PARA, and the symbolic map contract")
    class TheHttpSurface {

        @Test
        @DisplayName("the adapter adds nothing to MAIN-PARA, and carries the metadata beside it")
        void theAdapterIsThin() {
            ScreenResponse<ReportRequestResponse> answer = controllerAt(FIXED_INSTANT)
                    .submitReportRequest(ReportRequestRequest.empty(), null, null);

            ReportRequestResponse response = answer.screen();
            assertThat(response.getNextProgram()).isEqualTo("CORPT00C");
            assertThat(response.getTrnnameo()).isEqualTo("CR00");
            assertThat(ReportRequestController.REPORTS_PATH).isEqualTo("/api/reports");
            // The cursor request and all seventeen attribute quads are metadata by declaration, so they
            // travel beside the screen rather than not travelling at all.
            assertThat(answer.screenMetadata().fields())
                    .hasSize(ReportRequestResponse.ScreenField.values().length);
            assertThat(answer.screenMetadata().cursorField()).isEqualTo("MONTHLY");
        }

        @Test
        @DisplayName("POST /api/reports is mapped and drives the same flow the plain call drives")
        void theEndpointIsMapped() throws Exception {
            Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
            new WebConfig().carddemoJacksonCustomizer(TEST_PROFILE_CHARSET).customize(builder);
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
            super(dateUtilityJob, submissionPort, clock, DATASET_CHARSET);
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
            super(dateUtilityJob, submissionPort, clock, DATASET_CHARSET);
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
            super(dateUtilityJob, submissionPort, clock, DATASET_CHARSET);
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
            super(dateUtilityJob, submissionPort, clock, DATASET_CHARSET);
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
