package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.AidRequestParameter;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.NumericIntrinsics;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.WebConfig.JobSubmissionProperties;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestRequest;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestResponse;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import com.vsergeychik.carddemo.util.DateUtilityJob.DateValidationResult;

import jakarta.validation.Valid;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.Charset;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The stateless transaction-report request screen: a like-for-like migration of
 * {@code app/cbl/CORPT00C.cbl} (649 lines), CSD transaction {@link #TRANSACTION_ID}, exposed as
 * {@code POST}{@link #REPORTS_PATH}.
 *
 * <p>The three reads are kept as three reads because the source has three: the header deliberately re-reads
 * the clock after the monthly arm has mutated {@code WS-CURDATE-DATA}, which is why the header shows
 * today's date and not the month-end the arm computed.
 */
@RestController
public class ReportRequestController {
    private static final Log LOG = LogFactory.getLog(ReportRequestController.class);

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'CORPT00C'} - CORPT00C.cbl:37.
     */
    public static final String PROGRAM_NAME = "CORPT00C";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CR00'} - CORPT00C.cbl:38.
     */
    public static final String TRANSACTION_ID = "CR00";

    public static final String REPORTS_PATH = "/api/reports";

    static final String AID_MEMBER = "aid";

    /**
     * Query parameter carrying the raw {@code EIBAID} byte as an unsigned {@code 0}-{@code 255} value.
     */
    public static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    public static final String EIBAID_PARAM_ALIAS = AidRequestParameter.ALTERNATE_NAME;

    static final int AID_MIN = 0;

    static final int AID_MAX = 255;

    static final int RAW_AID_LENGTH = 1;

    static final char MAX_AID_CODE_POINT = 0x00FF;

    /**
     * {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} - CORPT00C.cbl:173 and 543.
     */
    public static final String SIGN_ON_PROGRAM = "COSGN00C";

    /**
     * {@code MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM} - CORPT00C.cbl:188, the PF3 target.
     */
    public static final String MAIN_MENU_PROGRAM = "COMEN01C";

    /**
     * {@code WS-MESSAGE PIC X(80)} - line 39.
     */
    public static final int WS_MESSAGE_LENGTH = 80;

    /**
     * {@code WS-REPORT-NAME PIC X(10)} - line 58.
     */
    public static final int WS_REPORT_NAME_LENGTH = 10;

    /**
     * {@code WS-START-DATE} and {@code WS-END-DATE}: {@code 4 + 1 + 2 + 1 + 2} - lines 60 to 71.
     */
    public static final int WS_DATE_LENGTH = 10;

    /**
     * {@code WS-START-DATE-YYYY} and {@code WS-END-DATE-YYYY}, both {@code PIC X(04)}.
     */
    public static final int DATE_YEAR_LENGTH = 4;

    /**
     * {@code WS-START-DATE-MM}, {@code -DD} and their end-date twins, all {@code PIC X(02)}.
     */
    public static final int DATE_PART_LENGTH = 2;

    /**
     * The {@code FILLER PIC X(01) VALUE '-'} items at lines 62, 64, 68 and 70.
     */
    public static final String DATE_SEPARATOR = "-";

    /**
     * {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} - line 72, the mask handed to CSUTLDTC.
     */
    public static final String WS_DATE_FORMAT = "YYYY-MM-DD";

    /**
     * {@code WS-NUM-99 PIC 99} - line 74: two digits, unsigned, no decimal places.
     */
    public static final int WS_NUM_99_DIGITS = 2;

    /**
     * {@code WS-NUM-9999 PIC 9999} - line 75: four digits, unsigned, no decimal places.
     */
    public static final int WS_NUM_9999_DIGITS = 4;

    /**
     * The scale of both numeric receivers: neither {@code PICTURE} has a {@code V}.
     */
    public static final int INTEGER_SCALE = 0;

    /**
     * {@code JCL-RECORD PIC X(80) VALUE ' '} - line 79, and {@code RECORDSIZE(80)} of the queue.
     */
    public static final int JCL_RECORD_LENGTH = 80;

    /**
     * {@code WS-RESP-CD} and {@code WS-REAS-CD PIC S9(09) COMP} - lines 54 and 55.
     */
    public static final int WS_RESP_CD_DIGITS = 9;

    // Each is moved into WS-MESSAGE PIC X(80) and from there into ERRMSGO PIC X(78), which truncates on the
    // right - so a literal's own length matters.

    public static final String MSG_START_DATE_MONTH_EMPTY = "Start Date - Month can NOT be empty...";

    public static final String MSG_START_DATE_DAY_EMPTY = "Start Date - Day can NOT be empty...";

    public static final String MSG_START_DATE_YEAR_EMPTY = "Start Date - Year can NOT be empty...";

    public static final String MSG_END_DATE_MONTH_EMPTY = "End Date - Month can NOT be empty...";

    public static final String MSG_END_DATE_DAY_EMPTY = "End Date - Day can NOT be empty...";

    public static final String MSG_END_DATE_YEAR_EMPTY = "End Date - Year can NOT be empty...";

    public static final String MSG_START_DATE_INVALID_MONTH = "Start Date - Not a valid Month...";

    public static final String MSG_START_DATE_INVALID_DAY = "Start Date - Not a valid Day...";

    public static final String MSG_START_DATE_INVALID_YEAR = "Start Date - Not a valid Year...";

    public static final String MSG_END_DATE_INVALID_MONTH = "End Date - Not a valid Month...";

    public static final String MSG_END_DATE_INVALID_DAY = "End Date - Not a valid Day...";

    public static final String MSG_END_DATE_INVALID_YEAR = "End Date - Not a valid Year...";

    public static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";

    public static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    public static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    public static final String MSG_UNABLE_TO_WRITE_TDQ = "Unable to Write TDQ (JOBS)...";

    public static final String MSG_PLEASE_CONFIRM_PREFIX = "Please confirm to print the ";

    public static final String MSG_REPORT_SUFFIX = " report...";

    public static final String MSG_QUOTE = "\"";

    public static final String MSG_NOT_A_VALID_CONFIRM_VALUE = "\" is not a valid value to confirm...";

    public static final String MSG_REPORT_SUBMITTED = " report submitted for printing ...";

    public static final String DISPLAY_PROCESS_ENTER_KEY = "PROCESS ENTER KEY";

    public static final String DISPLAY_RESP_PREFIX = "RESP:";

    public static final String DISPLAY_REAS_PREFIX = "REAS:";

    /**
     * {@code MOVE 'Monthly' TO WS-REPORT-NAME} - line 214.
     */
    public static final String REPORT_NAME_MONTHLY = "Monthly";

    /**
     * {@code MOVE 'Yearly' TO WS-REPORT-NAME} - line 240.
     */
    public static final String REPORT_NAME_YEARLY = "Yearly";

    /**
     * {@code MOVE 'Custom' TO WS-REPORT-NAME} - line 433.
     */
    public static final String REPORT_NAME_CUSTOM = "Custom";

    public static final String CONFIRM_YES_UPPER = "Y";

    public static final String CONFIRM_YES_LOWER = "y";

    public static final String CONFIRM_NO_UPPER = "N";

    public static final String CONFIRM_NO_LOWER = "n";

    /**
     * The literal {@code '01'}, moved into {@code WS-START-DATE-DD} at line 219 by the monthly arm and into
     * both {@code WS-START-DATE-MM} and {@code WS-START-DATE-DD} at lines 245 and 246 by the yearly arm.
     */
    public static final String FIRST_OF_PERIOD = "01";

    /**
     * {@code MOVE '12' TO WS-END-DATE-MM} - line 250.
     */
    public static final String LAST_MONTH_OF_YEAR = "12";

    /**
     * {@code MOVE '31' TO WS-END-DATE-DD} - line 251.
     */
    public static final String LAST_DAY_OF_DECEMBER = "31";

    public static final String HIGHEST_MONTH = "12";

    public static final String HIGHEST_DAY = "31";

    /**
     * {@code IF CSUTLDTC-RESULT-SEV-CD = '0000'} - lines 396 and 416.
     */
    public static final String CSUTLDTC_SEVERITY_OK = "0000";

    /**
     * {@code IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'} - lines 399 and 419.
     */
    public static final String CSUTLDTC_TOLERATED_MESSAGE_NUMBER = "2513";

    private static final String SPACE = " ";

    private static final char LOW_VALUE = '\u0000';

    public static final int NUMVAL_CONFORMS = NumericIntrinsics.CONFORMS;

    /**
     * The constant that turns a Java epoch day into a COBOL integer date.
     */
    public static final int INTEGER_OF_DATE_EPOCH_OFFSET = 134775;

    /**
     * Lowest argument {@code FUNCTION INTEGER-OF-DATE} accepts: 1601-01-01 as {@code 9(8)}.
     */
    public static final int INTEGER_OF_DATE_LOWEST_ARGUMENT = 16010101;

    /**
     * Highest argument {@code FUNCTION INTEGER-OF-DATE} accepts: 9999-12-31 as {@code 9(8)}.
     */
    public static final int INTEGER_OF_DATE_HIGHEST_ARGUMENT = 99991231;

    /**
     * The result {@link #integerOfDate(int)} and {@link #dateOfInteger(int)} yield for an argument outside
     * the supported range or, for the former, one that is not a real calendar date.
     */
    public static final int DATE_INTRINSIC_UNDEFINED = 0;

    /**
     * Lowest argument {@code FUNCTION DATE-OF-INTEGER} accepts: day 1, which is 1601-01-01.
     */
    public static final int DATE_OF_INTEGER_LOWEST_ARGUMENT = 1;

    private static final int STANDARD_DATE_YEAR_DIVISOR = 10000;

    private static final int STANDARD_DATE_MONTH_DIVISOR = 100;

    private static final int STANDARD_DATE_COMPONENT_MODULUS = 100;

    /**
     * Highest argument {@code FUNCTION DATE-OF-INTEGER} accepts: the day number of 9999-12-31.
     */
    public static final int DATE_OF_INTEGER_HIGHEST_ARGUMENT =
            integerOfDate(INTEGER_OF_DATE_HIGHEST_ARGUMENT);

    /**
     * {@code IF WS-CURDATE-MONTH > 12} - line 225, the month roll-over test.
     */
    public static final int MONTHS_PER_YEAR = 12;

    private static final int ONE = 1;

    public static final String JOB_LINE_01 =
            picXValue("//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,", JCL_RECORD_LENGTH);

    public static final String JOB_LINE_02 = picXValue("// NOTIFY=&SYSUID", JCL_RECORD_LENGTH);

    public static final String JOB_LINE_03 = picXValue("//*", JCL_RECORD_LENGTH);

    public static final String JOB_LINE_04 =
            picXValue("//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')", JCL_RECORD_LENGTH);

    public static final String JOB_LINE_05 = picXValue("//*", JCL_RECORD_LENGTH);

    public static final String JOB_LINE_06 = picXValue("//STEP10 EXEC PROC=TRANREPT", JCL_RECORD_LENGTH);

    public static final String JOB_LINE_07 = picXValue("//*", JCL_RECORD_LENGTH);

    public static final String JOB_LINE_08 = picXValue("//STEP05R.SYMNAMES DD *", JCL_RECORD_LENGTH);

    public static final String JOB_LINE_09 = picXValue("TRAN-CARD-NUM,263,16,ZD", JCL_RECORD_LENGTH);

    public static final String JOB_LINE_10 = picXValue("TRAN-PROC-DT,305,10,CH", JCL_RECORD_LENGTH);

    /**
     * {@code 10 FILLER PIC X(18) VALUE "PARM-START-DATE,C'"} - line 105.
     */
    public static final String SYMNAMES_START_DATE_PREFIX = "PARM-START-DATE,C'";

    /**
     * {@code 10 FILLER PIC X(52) VALUE "'"} - line 107.
     */
    public static final int SYMNAMES_START_DATE_TAIL_LENGTH = 52;

    /**
     * {@code 10 FILLER PIC X(16) VALUE "PARM-END-DATE,C'"} - line 110.
     */
    public static final String SYMNAMES_END_DATE_PREFIX = "PARM-END-DATE,C'";

    /**
     * {@code 10 FILLER PIC X(54) VALUE "'"} - line 112.
     */
    public static final int SYMNAMES_END_DATE_TAIL_LENGTH = 54;

    public static final String SYMNAMES_CLOSING_QUOTE = "'";

    /**
     * {@code 05 FILLER-1} - lines 103 to 107, in template form: the prefix, ten spaces where
     * {@code PARM-START-DATE-1 PIC X(10) VALUE SPACES} sits, then the quote and its padding.
     */
    public static final String JOB_LINE_11 = symnamesLine(SYMNAMES_START_DATE_PREFIX,
            SPACE.repeat(WS_DATE_LENGTH), SYMNAMES_START_DATE_TAIL_LENGTH);

    /**
     * {@code 05 FILLER-2} - lines 108 to 112, in template form.
     */
    public static final String JOB_LINE_12 = symnamesLine(SYMNAMES_END_DATE_PREFIX,
            SPACE.repeat(WS_DATE_LENGTH), SYMNAMES_END_DATE_TAIL_LENGTH);

    public static final String JOB_LINE_13 = picXValue("/*", JCL_RECORD_LENGTH);

    public static final String JOB_LINE_14 = picXValue("//STEP10R.DATEPARM DD *", JCL_RECORD_LENGTH);

    /**
     * {@code 05 FILLER-3} - lines 117 to 121, in template form: {@code PARM-START-DATE-2 PIC X(10)}, one
     * space, {@code PARM-END-DATE-2 PIC X(10)}, then {@code FILLER PIC X(59) VALUE SPACES}.
     */
    public static final String JOB_LINE_15 =
            dateParmRecord(SPACE.repeat(WS_DATE_LENGTH), SPACE.repeat(WS_DATE_LENGTH));

    public static final String JOB_LINE_16 = picXValue("/*", JCL_RECORD_LENGTH);

    public static final String EOF_MARKER_TEXT = "/*EOF";

    public static final String JOB_LINE_17 = picXValue(EOF_MARKER_TEXT, JCL_RECORD_LENGTH);

    /**
     * {@code IF JCL-RECORD = '/*EOF'} - line 502.
     */
    public static final String EOF_MARKER_RECORD = picXValue(EOF_MARKER_TEXT, JCL_RECORD_LENGTH);

    /**
     * The number of {@code PIC X(80)} items in {@code 02 JOB-DATA-1}: seventeen.
     */
    public static final int JOB_LINE_COUNT = 17;

    /**
     * {@code 05 JOB-LINES OCCURS 1000 TIMES} - line 127, the loop's upper bound at line 498.
     */
    public static final int JOB_LINES_OCCURS = 1000;

    /**
     * {@code 02 JOB-DATA-1}'s total width: {@value #JOB_LINE_COUNT} records of eighty bytes.
     */
    public static final int JOB_DATA_LENGTH = JOB_LINE_COUNT * JCL_RECORD_LENGTH;

    /**
     * The seventeen records in declaration order, with the four substitution points left at {@code SPACES}
     * exactly as {@code JOB-DATA}'s {@code VALUE} clauses leave them.
     */
    public static final List<String> JOB_DATA_TEMPLATE = List.of(
            JOB_LINE_01, JOB_LINE_02, JOB_LINE_03, JOB_LINE_04, JOB_LINE_05, JOB_LINE_06,
            JOB_LINE_07, JOB_LINE_08, JOB_LINE_09, JOB_LINE_10, JOB_LINE_11, JOB_LINE_12,
            JOB_LINE_13, JOB_LINE_14, JOB_LINE_15, JOB_LINE_16, JOB_LINE_17);

    private static final String JCL_RECORD_SUBJECT = "JCL-RECORD of the JOBS transient data queue";

    /**
     * {@code 05 FILLER-1}, the start-date {@code SYMNAMES} record: entry 11 of {@code JOB-LINES}.
     */
    public static final int SYMNAMES_START_DATE_ENTRY = 11;

    /**
     * {@code 05 FILLER-2}, the end-date {@code SYMNAMES} record: entry 12 of {@code JOB-LINES}.
     */
    public static final int SYMNAMES_END_DATE_ENTRY = 12;

    /**
     * {@code 05 FILLER-3}, the {@code DATEPARM} record: entry 15 of {@code JOB-LINES}.
     */
    public static final int DATEPARM_ENTRY = 15;

    private static final String SKELETON_WIDTH_SIGNATURE = skeletonWidthSignature();

    private static final String DATEPARM_LAYOUT_SIGNATURE =
            DateParmReader.START_DATE_OFFSET + ":" + DateParmReader.START_DATE_LENGTH
                    + "/" + DateParmReader.SEPARATOR_OFFSET + ":" + DateParmReader.SEPARATOR_LENGTH
                    + "/" + DateParmReader.END_DATE_OFFSET + ":" + DateParmReader.END_DATE_LENGTH
                    + "/" + DateParmReader.DISCARDED_TAIL_OFFSET + ":"
                    + DateParmReader.DISCARDED_TAIL_LENGTH
                    + "@" + DateParmReader.RECORD_LENGTH;

    private static final String DATEPARM_LAYOUT_FROM_COBOL = "0:10/10:1/11:10/21:59@80";

    static {
        requireSkeletonWidths(SKELETON_WIDTH_SIGNATURE);
        requireDateParmLayout(DATEPARM_LAYOUT_SIGNATURE);
    }

    /**
     * Verifies that every record of the skeleton template is exactly {@link #JCL_RECORD_LENGTH} bytes wide,
     * which is what {@code 02 JOB-DATA-1} declares at app/cbl/CORPT00C.cbl:83-125 and what
     * {@code TDQUEUE(JOBS)} declares as {@code RECORDSIZE(80) RECORDFORMAT(FIXED)} at
     * app/csd/CARDDEMO.CSD:499-505.
     *
     * @param measured the measured widths as a signature of {@code "<width> "} pairs, one per record
     * @throws NullPointerException if {@code measured} is {@code null}
     * @throws IllegalStateException if the signature is not {@link #JOB_LINE_COUNT} repetitions of
     *     {@link #JCL_RECORD_LENGTH}
     */
    public static void requireSkeletonWidths(String measured) {
        Objects.requireNonNull(measured, "A measured width signature is required to check the skeleton");
        String expectedWidths = (JCL_RECORD_LENGTH + SPACE).repeat(JOB_LINE_COUNT);
        if (!measured.equals(expectedWidths)) {
            throw new IllegalStateException("02 JOB-DATA-1 declares " + JOB_LINE_COUNT + " items of "
                    + JCL_RECORD_LENGTH + " bytes at app/cbl/CORPT00C.cbl:83-125, and TDQUEUE(JOBS) "
                    + "declares RECORDSIZE(" + JCL_RECORD_LENGTH + "), but the template measures '"
                    + measured.trim() + "'");
        }
    }

    /**
     * Verifies that the {@code DATEPARM} layout {@link DateParmReader} publishes is the one
     * {@code 05 FILLER-3} declares at app/cbl/CORPT00C.cbl:117-121.
     *
     * @param published the reader's layout as a signature of {@code offset:length} pairs and a total
     * @throws NullPointerException if {@code published} is {@code null}
     * @throws IllegalStateException if the reader's layout is not the COBOL's layout
     */
    public static void requireDateParmLayout(String published) {
        Objects.requireNonNull(published, "A published layout signature is required to check DATEPARM");
        if (!published.equals(DATEPARM_LAYOUT_FROM_COBOL)) {
            throw new IllegalStateException("Skeleton line " + DATEPARM_ENTRY + " is the DATEPARM record "
                    + "DateParmReader consumes, so the two layouts must agree. CORPT00C.cbl:117-121 "
                    + "declares '" + DATEPARM_LAYOUT_FROM_COBOL + "' and DateParmReader publishes '"
                    + published + "'. One file has been changed without the other");
        }
    }

    private static String skeletonWidthSignature() {
        StringBuilder widths = new StringBuilder();
        for (String record : JOB_DATA_TEMPLATE) {
            widths.append(record.length()).append(SPACE);
        }
        return widths.toString();
    }

    private static final Charset PICTURE_RULES_CHARSET = StandardCharsets.US_ASCII;

    private final DateUtilityJob dateUtilityJob;

    private final JobSubmissionPort jobSubmissionPort;

    private final Clock clock;

    private final FixedWidthCodec codec;

    /**
     * Wires the program's three collaborators with the code page configuration declares.
     *
     * @param dateUtilityJob the {@code CSUTLDTC} date validator; must not be {@code null}
     * @param jobSubmissionPort the transient-data-queue replacement; must not be {@code null}
     * @param clock the clock {@code FUNCTION CURRENT-DATE} reads; must not be {@code null}
     * @param datasetCharset the active dataset code page,
     *     {@code @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)}; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    @Autowired
    public ReportRequestController(DateUtilityJob dateUtilityJob,
                                   JobSubmissionPort jobSubmissionPort,
                                   Clock clock,
                                   @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)
                                   Charset datasetCharset) {
        this.dateUtilityJob = Objects.requireNonNull(dateUtilityJob, "A DateUtilityJob is required: "
                + "app/cbl/CORPT00C.cbl:392 and 412 CALL 'CSUTLDTC' to validate the custom range, and "
                + "the tolerated message number " + CSUTLDTC_TOLERATED_MESSAGE_NUMBER + " is read from "
                + "its result rather than re-parsed from the eighty-byte message");
        this.jobSubmissionPort = Objects.requireNonNull(jobSubmissionPort, "A JobSubmissionPort is "
                + "required: app/cbl/CORPT00C.cbl:517 writes each eighty-byte record to the CICS "
                + "transient data queue JOBS, and Java has no transient data queue");
        this.clock = Objects.requireNonNull(clock, "A Clock is required: FUNCTION CURRENT-DATE is read "
                + "from it at lines 215, 241 and 611, and never from the wall clock, so a parity case "
                + "can pin the instant and compare bytes");
        Objects.requireNonNull(datasetCharset, "A code page is required: this program renders "
                + "fixed-width images and composes eighty-byte JCL records, so the code page is stated "
                + "explicitly by configuration and never taken from the platform or from a constant");
        this.codec = new FixedWidthCodec(datasetCharset);
    }

    /**
     * {@code POST}{@link #REPORTS_PATH} - CSD transaction {@link #TRANSACTION_ID}.
     *
     * <p>The five-character token cannot say {@code PF15}, because the copybook folds it onto
     * {@code 'PFK03'} and this program would then take its {@code PF3} arm.
     *
     * @param request the inbound screen; validated against the symbolic map's declared widths
     * @param eibaid the attention identifier as an unsigned {@code 0}-{@code 255} byte under
     *     {@link #EIBAID_PARAM}, or {@code null} to take it from the payload's own {@code aid} member
     * @param eibAid the same value under {@link #EIBAID_PARAM_ALIAS}; at most one need be sent
     * @return the outbound screen, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws IllegalArgumentException if the stated byte is outside {@code 0}-{@code 255}, or if both
     *     spellings are present and disagree
     */
    @PostMapping(path = REPORTS_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ScreenResponse<ReportRequestResponse> submitReportRequest(
            @Valid @RequestBody ReportRequestRequest request,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid) {
        ProgramState state = mainPara(request, AidRequestParameter.resolve(eibaid, eibAid));
        return ScreenResponse.of(state.response(), state.screenMetadata());
    }

    /**
     * {@code MAIN-PARA} - the program's entry point, lines 163 to 202.
     *
     * @param request the inbound screen; must not be {@code null}
     * @return the state at the moment the task returned to CICS or transferred, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public ProgramState mainPara(ReportRequestRequest request) {
        return mainPara(request, null);
    }

    /**
     * {@code MAIN-PARA} with the raw attention identifier the request stated.
     *
     * @param request the inbound screen; must not be {@code null}
     * @param statedAid the raw {@code EIBAID} byte as an unsigned value, or {@code null} when the request
     *     named no key
     * @return the state at the moment the task returned to CICS or transferred, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public ProgramState mainPara(ReportRequestRequest request, Integer statedAid) {
        Objects.requireNonNull(request, "A request is required: CORPT00C is driven entirely by its "
                + "communication area, the EIBAID and the received map, all of which travel in it");
        return mainPara(request, resolveEibAid(statedAid, request.aid()));
    }

    /**
     * {@code MAIN-PARA} with the attention identifier supplied separately from the payload.
     *
     * @param request the inbound screen; must not be {@code null}
     * @param eibAid the raw {@code EIBAID} byte {@code :184} evaluates
     * @return the state at the moment the task returned to CICS or transferred, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public ProgramState mainPara(ReportRequestRequest request, byte eibAid) {
        Objects.requireNonNull(request, "A request is required: CORPT00C is driven entirely by its "
                + "communication area, the EIBAID and the received map, all of which travel in it");

        ProgramState state = new ProgramState();

        state.setErrFlagOff();
        state.setTransactNotEof();
        state.setSendEraseYes();

        state.setMessage(SPACE.repeat(WS_MESSAGE_LENGTH));
        state.response().moveSpacesToErrmsgo();

        if (!request.hasNavigationContext()) {
            state.setCommarea(state.commarea().withToProgram(SIGN_ON_PROGRAM));
            returnToPrevScreen(state);
            return state;
        }

        state.setCommarea(request.navigationContext());

        if (!state.commarea().isReenter()) {
            state.setCommarea(state.commarea().withPgmReenter());
            state.response().moveLowValuesToMapGroup();
            state.moveMinusOneTo(ReportRequestRequest.ScreenField.MONTHLY);
            sendTrnrptScreen(state);
            return state;
        }

        receiveTrnrptScreen(state, request);

        if (PfKeyResolver.isEnter(eibAid)) {
            processEnterKey(state);
            return state;
        }
        if (PfKeyResolver.isPf3(eibAid)) {
            state.setCommarea(state.commarea().withToProgram(MAIN_MENU_PROGRAM));
            returnToPrevScreen(state);
            return state;
        }
        state.setErrFlagOn();
        state.moveMinusOneTo(ReportRequestRequest.ScreenField.MONTHLY);
        state.setMessage(codec.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                WS_MESSAGE_LENGTH));
        sendTrnrptScreen(state);
        return state;
    }

    /**
     * {@code PROCESS-ENTER-KEY} - lines 208 to 456.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void processEnterKey(ProgramState state) {
        requireState(state);

        display(state, DISPLAY_PROCESS_ENTER_KEY);

        if (!ReportRequestResponse.isSpacesOrLowValues(state.monthlyI())) {
            processMonthlyArm(state);
        } else if (!ReportRequestResponse.isSpacesOrLowValues(state.yearlyI())) {
            processYearlyArm(state);
        } else if (!ReportRequestResponse.isSpacesOrLowValues(state.customI())) {
            processCustomArm(state);
        } else {
            rejectAndSend(state, MSG_SELECT_REPORT_TYPE,
                    ReportRequestRequest.ScreenField.MONTHLY);
            return;
        }

        if (state.errFlagOff()) {
            initializeAllFields(state);
            state.response().setErrmsgc(BmsAttributes.DFHGREEN);
            state.setMessage(stringInto(state.message(), codec.concatenateDelimitedBySize(
                    stringDelimitedBySpace(state.reportName()), MSG_REPORT_SUBMITTED)));
            state.moveMinusOneTo(ReportRequestRequest.ScreenField.MONTHLY);
            sendTrnrptScreen(state);
        }
    }

    /**
     * The {@code WHEN MONTHLYI} arm - lines 213 to 238: the current calendar month, first day to last.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void processMonthlyArm(ProgramState state) {
        requireState(state);

        state.setReportName(codec.movePicX(REPORT_NAME_MONTHLY, WS_REPORT_NAME_LENGTH));

        DateHeader header = DateHeader.from(codec, clock);
        int year = header.captured().year();
        int month = header.captured().month();

        state.setStartDateYyyy(codec.movePic9(year, DATE_YEAR_LENGTH));
        state.setStartDateMm(codec.movePic9(month, DATE_PART_LENGTH));
        state.setStartDateDd(FIRST_OF_PERIOD);
        state.setParmStartDate1(state.startDate());
        state.setParmStartDate2(state.startDate());

        int day = ONE;
        month = month + ONE;
        if (month > MONTHS_PER_YEAR) {
            year = year + ONE;
            month = ONE;
        }

        int curdateN = dateOfInteger(integerOfDate(standardDate(year, month, day)) - ONE);
        year = yearOfStandardDate(curdateN);
        month = monthOfStandardDate(curdateN);
        day = dayOfStandardDate(curdateN);

        state.setEndDateYyyy(codec.movePic9(year, DATE_YEAR_LENGTH));
        state.setEndDateMm(codec.movePic9(month, DATE_PART_LENGTH));
        state.setEndDateDd(codec.movePic9(day, DATE_PART_LENGTH));
        state.setParmEndDate1(state.endDate());
        state.setParmEndDate2(state.endDate());

        submitJobToIntrdr(state);
    }

    /**
     * The {@code WHEN YEARLYI} arm - lines 239 to 255: the current calendar year, 1 January to 31 December.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void processYearlyArm(ProgramState state) {
        requireState(state);

        state.setReportName(codec.movePicX(REPORT_NAME_YEARLY, WS_REPORT_NAME_LENGTH));

        DateHeader header = DateHeader.from(codec, clock);
        String year = codec.movePic9(header.captured().year(), DATE_YEAR_LENGTH);

        state.setStartDateYyyy(year);
        state.setEndDateYyyy(year);
        state.setStartDateMm(FIRST_OF_PERIOD);
        state.setStartDateDd(FIRST_OF_PERIOD);
        state.setParmStartDate1(state.startDate());
        state.setParmStartDate2(state.startDate());

        state.setEndDateMm(LAST_MONTH_OF_YEAR);
        state.setEndDateDd(LAST_DAY_OF_DECEMBER);
        state.setParmEndDate1(state.endDate());
        state.setParmEndDate2(state.endDate());

        submitJobToIntrdr(state);
    }

    /**
     * The {@code WHEN CUSTOMI} arm - lines 256 to 436: the operator's own date range, in four stages.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void processCustomArm(ProgramState state) {
        requireState(state);

        if (ReportRequestResponse.isSpacesOrLowValues(state.sdtmmI())) {
            rejectAndSend(state, MSG_START_DATE_MONTH_EMPTY,
                    ReportRequestRequest.ScreenField.SDTMM);
            return;
        }
        if (ReportRequestResponse.isSpacesOrLowValues(state.sdtddI())) {
            rejectAndSend(state, MSG_START_DATE_DAY_EMPTY,
                    ReportRequestRequest.ScreenField.SDTDD);
            return;
        }
        if (ReportRequestResponse.isSpacesOrLowValues(state.sdtyyyyI())) {
            rejectAndSend(state, MSG_START_DATE_YEAR_EMPTY,
                    ReportRequestRequest.ScreenField.SDTYYYY);
            return;
        }
        if (ReportRequestResponse.isSpacesOrLowValues(state.edtmmI())) {
            rejectAndSend(state, MSG_END_DATE_MONTH_EMPTY,
                    ReportRequestRequest.ScreenField.EDTMM);
            return;
        }
        if (ReportRequestResponse.isSpacesOrLowValues(state.edtddI())) {
            rejectAndSend(state, MSG_END_DATE_DAY_EMPTY,
                    ReportRequestRequest.ScreenField.EDTDD);
            return;
        }
        if (ReportRequestResponse.isSpacesOrLowValues(state.edtyyyyI())) {
            rejectAndSend(state, MSG_END_DATE_YEAR_EMPTY,
                    ReportRequestRequest.ScreenField.EDTYYYY);
            return;
        }

        state.setSdtmmI(computeIntoPic99(state.sdtmmI()));
        state.setSdtddI(computeIntoPic99(state.sdtddI()));
        state.setSdtyyyyI(computeIntoPic9999(state.sdtyyyyI()));
        state.setEdtmmI(computeIntoPic99(state.edtmmI()));
        state.setEdtddI(computeIntoPic99(state.edtddI()));
        state.setEdtyyyyI(computeIntoPic9999(state.edtyyyyI()));

        if (isNotValidTwoDigitPart(state.sdtmmI(), HIGHEST_MONTH)) {
            rejectAndSend(state, MSG_START_DATE_INVALID_MONTH,
                    ReportRequestRequest.ScreenField.SDTMM);
            return;
        }
        if (isNotValidTwoDigitPart(state.sdtddI(), HIGHEST_DAY)) {
            rejectAndSend(state, MSG_START_DATE_INVALID_DAY,
                    ReportRequestRequest.ScreenField.SDTDD);
            return;
        }
        if (!isNumericClass(state.sdtyyyyI())) {
            rejectAndSend(state, MSG_START_DATE_INVALID_YEAR,
                    ReportRequestRequest.ScreenField.SDTYYYY);
            return;
        }
        if (isNotValidTwoDigitPart(state.edtmmI(), HIGHEST_MONTH)) {
            rejectAndSend(state, MSG_END_DATE_INVALID_MONTH,
                    ReportRequestRequest.ScreenField.EDTMM);
            return;
        }
        if (isNotValidTwoDigitPart(state.edtddI(), HIGHEST_DAY)) {
            rejectAndSend(state, MSG_END_DATE_INVALID_DAY,
                    ReportRequestRequest.ScreenField.EDTDD);
            return;
        }
        if (!isNumericClass(state.edtyyyyI())) {
            rejectAndSend(state, MSG_END_DATE_INVALID_YEAR,
                    ReportRequestRequest.ScreenField.EDTYYYY);
            return;
        }

        state.setStartDateYyyy(state.sdtyyyyI());
        state.setStartDateMm(state.sdtmmI());
        state.setStartDateDd(state.sdtddI());
        state.setEndDateYyyy(state.edtyyyyI());
        state.setEndDateMm(state.edtmmI());
        state.setEndDateDd(state.edtddI());

        if (!callCsutldtc(state, state.startDate(), MSG_START_DATE_INVALID,
                ReportRequestRequest.ScreenField.SDTMM)) {
            return;
        }
        if (!callCsutldtc(state, state.endDate(), MSG_END_DATE_INVALID,
                ReportRequestRequest.ScreenField.EDTMM)) {
            return;
        }

        state.setParmStartDate1(state.startDate());
        state.setParmStartDate2(state.startDate());
        state.setParmEndDate1(state.endDate());
        state.setParmEndDate2(state.endDate());
        state.setReportName(codec.movePicX(REPORT_NAME_CUSTOM, WS_REPORT_NAME_LENGTH));

        if (state.errFlagOff()) {
            submitJobToIntrdr(state);
        }
    }

    /**
     * The four-statement rejection shape the source writes fifteen times: move the message, raise the error
     * flag, ask for the cursor on the offending field, and send the screen - which returns to CICS.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @param message the literal to move into {@code WS-MESSAGE}; must not be {@code null}
     * @param cursorField the field whose length item receives {@code -1}; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public void rejectAndSend(ProgramState state, String message,
                              ReportRequestRequest.ScreenField cursorField) {
        requireState(state);
        Objects.requireNonNull(message, "A message literal is required; CORPT00C moves one at every "
                + "rejection site");
        Objects.requireNonNull(cursorField, "A cursor field is required; every rejection site moves -1 "
                + "into exactly one length item");

        state.setMessage(codec.movePicX(message, WS_MESSAGE_LENGTH));
        state.setErrFlagOn();
        state.moveMinusOneTo(cursorField);
        sendTrnrptScreen(state);
    }

    /**
     * {@code CALL 'CSUTLDTC' USING CSUTLDTC-DATE CSUTLDTC-DATE-FORMAT CSUTLDTC-RESULT} - lines 388 to 406
     * for the start date and 408 to 426 for the end date.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @param date the ten-byte {@code 'YYYY-MM-DD'} group to validate; must not be {@code null}
     * @param message the literal to move into {@code WS-MESSAGE} on rejection; must not be {@code null}
     * @param cursorField the field whose length item receives {@code -1} on rejection; must not be
     *     {@code null}
     * @return {@code true} when the arm may proceed, {@code false} when the screen has been sent
     * @throws NullPointerException if any argument is {@code null}
     */
    public boolean callCsutldtc(ProgramState state, String date, String message,
                                ReportRequestRequest.ScreenField cursorField) {
        requireState(state);
        Objects.requireNonNull(date, "A date is required; CORPT00C moves WS-START-DATE or WS-END-DATE "
                + "into CSUTLDTC-DATE before each call");

        DateValidationResult result = dateUtilityJob.validateDate(
                codec.movePicX(date, DateUtilityJob.LS_DATE_LENGTH),
                codec.movePicX(WS_DATE_FORMAT, DateUtilityJob.LS_DATE_FORMAT_LENGTH));
        state.setCsutldtcResult(result);

        if (CSUTLDTC_SEVERITY_OK.equals(result.severityCode())) {
            return true;
        }
        if (!CSUTLDTC_TOLERATED_MESSAGE_NUMBER.equals(result.messageNumber())) {
            rejectAndSend(state, message, cursorField);
            return false;
        }
        return true;
    }

    /**
     * {@code SUBMIT-JOB-TO-INTRDR} - lines 462 to 510: confirm, then emit the job.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void submitJobToIntrdr(ProgramState state) {
        requireState(state);

        if (ReportRequestResponse.isSpacesOrLowValues(state.confirmI())) {
            state.setMessage(stringInto(state.message(), codec.concatenateDelimitedBySize(
                    MSG_PLEASE_CONFIRM_PREFIX,
                    stringDelimitedBySpace(state.reportName()),
                    MSG_REPORT_SUFFIX)));
            state.setErrFlagOn();
            state.moveMinusOneTo(ReportRequestRequest.ScreenField.CONFIRM);
            sendTrnrptScreen(state);
        }

        if (state.errFlagOff()) {
            String confirm = state.confirmI();

            if (CONFIRM_YES_UPPER.equals(confirm) || CONFIRM_YES_LOWER.equals(confirm)) {
                LOG.debug("Report submission confirmed; emitting the job skeleton");
            } else if (CONFIRM_NO_UPPER.equals(confirm) || CONFIRM_NO_LOWER.equals(confirm)) {
                initializeAllFields(state);
                state.setErrFlagOn();
                sendTrnrptScreen(state);
            } else {
                state.setMessage(stringInto(state.message(), codec.concatenateDelimitedBySize(
                        MSG_QUOTE,
                        stringDelimitedBySpace(confirm),
                        MSG_NOT_A_VALID_CONFIRM_VALUE)));
                state.setErrFlagOn();
                state.moveMinusOneTo(ReportRequestRequest.ScreenField.CONFIRM);
                sendTrnrptScreen(state);
            }

            state.setEndLoopNo();

            // The 'N' and 'n' arms above leave ERR-FLG-ON, so the body runs zero times and nothing is
            // emitted - which is exactly what the source's GO TO achieves by never arriving here.
            List<String> jobLines = jobLines(state);
            state.setIdx(ONE);
            while (state.idx() <= JOB_LINES_OCCURS && state.endLoopNo() && state.errFlagOff()) {
                state.setJclRecord(jobLine(jobLines, state.idx()));
                if (EOF_MARKER_RECORD.equals(state.jclRecord())
                        || ReportRequestResponse.isSpacesOrLowValues(state.jclRecord())) {
                    state.setEndLoopYes();
                }
                writeJobSubmissionQueue(state);
                state.setIdx(state.idx() + ONE);
            }
        }
    }

    /**
     * {@code WIRTE-JOBSUB-TDQ} - lines 515 to 535.
     *
     * <p><strong>A record is recorded as submitted only once the queue has accepted it.</strong> The
     * {@code EVALUATE} at lines 525 to 535 has exactly one arm on which the {@code WRITEQ TD}
     * succeeded, {@code WHEN DFHRESP(NORMAL)}; on every other arm the command appended nothing and the
     * queue is unchanged. Recording the hand-off before the response is evaluated would count an
     * attempt as an append, and a caller reading {@link ProgramState#submittedRecords()} would be told
     * the queue holds a record it does not hold. The queue's {@code DISPOSITION(MOD)} and
     * {@code ERROROPTION(IGNORE)} attributes are about the records that came <em>before</em> a failure:
     * they survive it, because there is no rollback. They say nothing about the record that failed.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void writeJobSubmissionQueue(ProgramState state) {
        requireState(state);

        WriteQueueOutcome outcome = jobSubmissionPort.writeQueueTd(state.jclRecord());      // L517-523
        state.setRespCd(outcome.resp());
        state.setReasCd(outcome.resp2());

        // L525-L535 EVALUATE WS-RESP-CD.
        if (outcome.normal()) {                                                            // L526
            // The queue accepted the record, so the queue now holds it. This is the only arm on which
            // that is true, which is why the record is counted here rather than at the hand-off above.
            state.recordSubmitted(state.jclRecord());
            return;                                                                        // L527
        }
        display(state, DISPLAY_RESP_PREFIX + codec.movePic9(state.respCd(), WS_RESP_CD_DIGITS)
                + DISPLAY_REAS_PREFIX + codec.movePic9(state.reasCd(), WS_RESP_CD_DIGITS));
        state.setErrFlagOn();
        state.setMessage(codec.movePicX(MSG_UNABLE_TO_WRITE_TDQ, WS_MESSAGE_LENGTH));
        state.moveMinusOneTo(ReportRequestRequest.ScreenField.MONTHLY);
        sendTrnrptScreen(state);
    }

    /**
     * {@code RETURN-TO-PREV-SCREEN} - lines 540 to 551: hand control to another program.
     *
     * <p>{@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)} transfers and never
     * comes back.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void returnToPrevScreen(ProgramState state) {
        requireState(state);

        if (ReportRequestResponse.isSpacesOrLowValues(state.commarea().toProgram())) {
            state.setCommarea(state.commarea().withToProgram(SIGN_ON_PROGRAM));
        }
        state.setCommarea(state.commarea()
                .withFromTranid(TRANSACTION_ID)
                .withFromProgram(PROGRAM_NAME)
                .withPgmEnter());

        state.response().echoNavigation(state.commarea());
        state.markTransferred();
    }

    /**
     * {@code SEND-TRNRPT-SCREEN} - lines 556 to 580: paint the screen and return to CICS.
     *
     * <p>The paragraph ends {@code GO TO RETURN-TO-CICS} at line 580, so control never comes back to the
     * statement after the {@code PERFORM}.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void sendTrnrptScreen(ProgramState state) {
        requireState(state);

        populateHeaderInfo(state);
        state.response().setErrmsgo(state.message());
        state.response().setNextMapset(ReportRequestResponse.MAPSET_NAME);
        state.response().setNextMap(ReportRequestResponse.MAP_NAME);

        if (state.sendEraseYes()) {
            state.recordScreenSent(true);
        } else {
            state.recordScreenSent(false);
        }

        returnToCics(state);
    }

    /**
     * {@code RETURN-TO-CICS} - lines 585 to 591, and the identical
     * {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} the source also writes at
     * lines 199 to 202.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void returnToCics(ProgramState state) {
        requireState(state);

        state.response().setNavigationContext(state.commarea());
        state.response().setNextProgram(PROGRAM_NAME);
        state.markReturned();
    }

    /**
     * {@code RECEIVE-TRNRPT-SCREEN} - lines 596 to 604.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @param request the inbound screen; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalStateException if the request and response projections of the symbolic map ever
     *     disagree about how many fields it has
     */
    public void receiveTrnrptScreen(ProgramState state, ReportRequestRequest request) {
        requireState(state);
        Objects.requireNonNull(request, "A request is required: it carries the received map");

        List<String> received = request.fieldValues();
        ReportRequestResponse.ScreenField[] fields = ReportRequestResponse.ScreenField.values();
        requireMatchingProjections(received.size(), fields.length);
        for (int field = 0; field < fields.length; field++) {
            state.response().setPayloadValue(fields[field], received.get(field));
        }

        state.setRespCd(FileStatus.NORMAL);
        state.setReasCd(FileStatus.NO_REASON_CODE);
    }

    /**
     * Verifies that the request and response projections of {@code app/cpy-bms/CORPT00.CPY} agree about how
     * many fields the symbolic map has, before the received values are copied across by ordinal.
     *
     * @param requestFieldCount how many values the request projects
     * @param responseFieldCount how many payload fields the response projects
     * @throws IllegalStateException if the two counts differ
     */
    public static void requireMatchingProjections(int requestFieldCount, int responseFieldCount) {
        if (requestFieldCount != responseFieldCount) {
            throw new IllegalStateException("The request projects " + requestFieldCount + " field(s) of "
                    + "the symbolic map and the response projects " + responseFieldCount + "; both are "
                    + "app/cpy-bms/CORPT00.CPY, which declares " + ReportRequestRequest.FIELD_COUNT
                    + ", so one projection has drifted");
        }
    }

    /**
     * {@code POPULATE-HEADER-INFO} - lines 609 to 628.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void populateHeaderInfo(ProgramState state) {
        requireState(state);

        DateHeader header = DateHeader.from(codec, clock);
        state.setDateHeader(header);
        state.response().populateHeaderInfo(header);
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS} - lines 633 to 646.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void initializeAllFields(ProgramState state) {
        requireState(state);

        state.moveMinusOneTo(ReportRequestRequest.ScreenField.MONTHLY);
        state.response().initializeAllFields();
        state.setMessage(SPACE.repeat(WS_MESSAGE_LENGTH));
    }

    /**
     * The seventeen records with this request's four substitution points filled: the per-request copy of
     * {@link #JOB_DATA_TEMPLATE}.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @return an unmodifiable list of {@value #JOB_LINE_COUNT} records of exactly
     *     {@value #JCL_RECORD_LENGTH} characters, in declaration order
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public List<String> jobLines(ProgramState state) {
        requireState(state);

        List<String> lines = new ArrayList<>(JOB_DATA_TEMPLATE);
        lines.set(SYMNAMES_START_DATE_ENTRY - ONE, symnamesLine(SYMNAMES_START_DATE_PREFIX,
                state.parmStartDate1(), SYMNAMES_START_DATE_TAIL_LENGTH));
        lines.set(SYMNAMES_END_DATE_ENTRY - ONE, symnamesLine(SYMNAMES_END_DATE_PREFIX,
                state.parmEndDate1(), SYMNAMES_END_DATE_TAIL_LENGTH));
        lines.set(DATEPARM_ENTRY - ONE, dateParmRecord(state.parmStartDate2(), state.parmEndDate2()));
        return Collections.unmodifiableList(lines);
    }

    /**
     * {@code MOVE JOB-LINES(WS-IDX) TO JCL-RECORD} - line 501.
     *
     * <p>The emit loop never asks for one, because entry {@value #JOB_LINE_COUNT} is the {@code '/*EOF'}
     * sentinel and sets {@code END-LOOP-YES}.
     *
     * @param lines the substituted records from {@link #jobLines(ProgramState)}; must not be {@code null}
     * @param occursIndex the one-based {@code OCCURS} subscript, as {@code WS-IDX} holds it
     * @return the record at that subscript, exactly {@value #JCL_RECORD_LENGTH} characters
     * @throws NullPointerException if {@code lines} is {@code null}
     * @throws IndexOutOfBoundsException if {@code occursIndex} is below 1 or beyond the declared records
     */
    public static String jobLine(List<String> lines, int occursIndex) {
        Objects.requireNonNull(lines, "The substituted skeleton is required to read JOB-LINES");
        if (occursIndex < ONE || occursIndex > lines.size()) {
            throw new IndexOutOfBoundsException("JOB-LINES subscript " + occursIndex + " is outside "
                    + "the " + lines.size() + " PIC X(80) items 02 JOB-DATA-1 actually declares at "
                    + "app/cbl/CORPT00C.cbl:83-125. The table says OCCURS " + JOB_LINES_OCCURS
                    + " TIMES but redefines only " + JOB_DATA_LENGTH + " bytes, so the emit loop is "
                    + "bounded by the '" + EOF_MARKER_TEXT + "' sentinel and never by the subscript");
        }
        return lines.get(occursIndex - ONE);
    }

    /**
     * {@code 05 FILLER-3} - lines 117 to 121: the {@code DATEPARM} record.
     *
     * @param startDate the start date, {@value #WS_DATE_LENGTH} characters or shorter; must not be
     *     {@code null}
     * @param endDate the end date, {@value #WS_DATE_LENGTH} characters or shorter; must not be {@code null}
     * @return the record, exactly {@value #JCL_RECORD_LENGTH} characters
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if either date is wider than {@value #WS_DATE_LENGTH}
     */
    public static String dateParmRecord(String startDate, String endDate) {
        StringBuilder record = new StringBuilder(SPACE.repeat(DateParmReader.RECORD_LENGTH));
        overlay(record, DateParmReader.START_DATE_OFFSET,
                picXValue(startDate, DateParmReader.START_DATE_LENGTH));
        overlay(record, DateParmReader.SEPARATOR_OFFSET, SPACE);
        overlay(record, DateParmReader.END_DATE_OFFSET,
                picXValue(endDate, DateParmReader.END_DATE_LENGTH));
        return requireRecordWidth(record.toString(), "the " + DateParmReader.DD_NAME + " record");
    }

    /**
     * {@code 05 FILLER-1} and {@code 05 FILLER-2} - lines 103 to 112: a {@code SYMNAMES} record.
     *
     * @param prefix the symbol name and opening quote, at its exact declared width; must not be
     *     {@code null}
     * @param date the date to substitute; must not be {@code null}
     * @param tailLength the declared width of the trailing {@code FILLER} whose {@code VALUE} is the
     *     closing quote
     * @return the record, exactly {@value #JCL_RECORD_LENGTH} characters
     * @throws NullPointerException if {@code prefix} or {@code date} is {@code null}
     * @throws IllegalArgumentException if the composed record is not {@value #JCL_RECORD_LENGTH} characters
     */
    public static String symnamesLine(String prefix, String date, int tailLength) {
        Objects.requireNonNull(prefix, "A SYMNAMES prefix is required");
        String line = picXValue(prefix, prefix.length())
                + picXValue(date, WS_DATE_LENGTH)
                + picXValue(SYMNAMES_CLOSING_QUOTE, tailLength);
        return requireRecordWidth(line, "a SYMNAMES record");
    }

    private static String picXValue(String literal, int declaredWidth) {
        Objects.requireNonNull(literal, "A VALUE literal is required");
        if (literal.length() > declaredWidth) {
            throw new IllegalArgumentException("A VALUE of " + literal.length() + " character(s) does "
                    + "not fit an item declared PIC X(" + declaredWidth + ")");
        }
        return literal + SPACE.repeat(declaredWidth - literal.length());
    }

    private static String requireRecordWidth(String record, String subject) {
        if (record.length() != JCL_RECORD_LENGTH) {
            throw new IllegalArgumentException(subject + " is " + record.length() + " character(s); "
                    + "JCL-RECORD is PIC X(80) and TDQUEUE(JOBS) declares RECORDSIZE("
                    + JCL_RECORD_LENGTH + ")");
        }
        return record;
    }

    private static void overlay(StringBuilder record, int offset, String value) {
        record.replace(offset, offset + value.length(), value);
    }

    /**
     * {@code COMPUTE WS-NUM-99 = FUNCTION NUMVAL-C(<field>)} followed by {@code MOVE WS-NUM-99 TO <field>}
     * - lines 305 to 311 and 317 to 323.
     *
     * <p>The truncation is {@link java.math.RoundingMode#DOWN} because {@code ROUNDED} appears nowhere in
     * this program - or in any of the twenty-eight - which is why it is routed through
     * {@link CobolDecimal#storeAtPicture(BigDecimal, int, int)} rather than performed here.
     *
     * @param source the symbolic-map item's current characters; must not be {@code null}
     * @return exactly {@value #WS_NUM_99_DIGITS} digit characters
     * @throws NullPointerException if {@code source} is {@code null}
     */
    public String computeIntoPic99(String source) {
        BigDecimal stored = CobolDecimal.storeAtPicture(numvalC(source).abs(), WS_NUM_99_DIGITS,
                INTEGER_SCALE);
        return codec.movePic9(stored.longValueExact(), WS_NUM_99_DIGITS);
    }

    /**
     * {@code COMPUTE WS-NUM-9999 = FUNCTION NUMVAL-C(<field>)} followed by
     * {@code MOVE WS-NUM-9999 TO <field>} - lines 313 to 315 and 325 to 327: the same three rules as
     * {@link #computeIntoPic99(String)} against a four-digit unsigned receiver.
     *
     * @param source the symbolic-map item's current characters; must not be {@code null}
     * @return exactly {@value #WS_NUM_9999_DIGITS} digit characters
     * @throws NullPointerException if {@code source} is {@code null}
     */
    public String computeIntoPic9999(String source) {
        BigDecimal stored = CobolDecimal.storeAtPicture(numvalC(source).abs(), WS_NUM_9999_DIGITS,
                INTEGER_SCALE);
        return codec.movePic9(stored.longValueExact(), WS_NUM_9999_DIGITS);
    }

    /**
     * {@code IF <field> IS NOT NUMERIC OR <field> > '<highest>'} - lines 329 to 330, 338 to 339, 355 to 356
     * and 364 to 365.
     *
     * @param image the item's characters; must not be {@code null}
     * @param highest the inclusive upper bound as the source spells it, {@code '12'} or {@code '31'}; must
     *     not be {@code null}
     * @return whether the item fails the class test or exceeds the bound
     * @throws NullPointerException if either argument is {@code null}
     */
    public static boolean isNotValidTwoDigitPart(String image, String highest) {
        Objects.requireNonNull(highest, "An upper bound is required; the source compares against '12' "
                + "for a month and '31' for a day");
        return !isNumericClass(image) || image.compareTo(highest) > 0;
    }

    /**
     * The COBOL class condition {@code IS NUMERIC} on an alphanumeric item - lines 329, 338, 347, 355, 364
     * and 373, negated at each site.
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
     * A {@code STRING} operand {@code DELIMITED BY SPACE} - lines 449, 468 and 487.
     *
     * @param sendingItem the operand at its declared width; must not be {@code null}
     * @return the operand's characters up to the first space, possibly empty
     * @throws NullPointerException if {@code sendingItem} is {@code null}
     */
    public static String stringDelimitedBySpace(String sendingItem) {
        Objects.requireNonNull(sendingItem, "A sending item is required for STRING ... DELIMITED BY "
                + "SPACE");
        int firstSpace = sendingItem.indexOf(SPACE);
        if (firstSpace < 0) {
            return sendingItem;
        }
        return sendingItem.substring(0, firstSpace);
    }

    /**
     * {@code STRING ... INTO WS-MESSAGE} - lines 449 to 452, 465 to 470 and 485 to 490.
     *
     * @param receiver the receiving item at its declared width; must not be {@code null}
     * @param composed the concatenated sending items; must not be {@code null}
     * @return an image of exactly the receiver's width
     * @throws NullPointerException if either argument is {@code null}
     */
    public static String stringInto(String receiver, String composed) {
        Objects.requireNonNull(receiver, "A receiving item is required for STRING ... INTO");
        Objects.requireNonNull(composed, "The composed sending items are required for STRING ... INTO");
        if (composed.length() >= receiver.length()) {
            return composed.substring(0, receiver.length());
        }
        return composed + receiver.substring(composed.length());
    }

    /**
     * Reads the {@code EIBAID} byte that line 184's {@code EVALUATE EIBAID} tests out of the payload's
     * {@code aid} member.
     *
     * @param aidImage the {@code aid} member as it arrived, or {@code null} when the payload omitted it
     * @return the raw EBCDIC attention-identifier byte; never throws
     */
    public static byte eibAidOf(String aidImage) {
        if (aidImage == null || aidImage.length() != RAW_AID_LENGTH) {
            return CicsAid.DFHNULL;
        }
        char stated = aidImage.charAt(0);
        if (stated > MAX_AID_CODE_POINT) {
            return CicsAid.DFHNULL;
        }
        return (byte) stated;
    }

    byte resolveEibAid(Integer statedAid, String aidToken) {
        if (statedAid == null) {
            return eibAidOf(aidToken);
        }
        return AidRequestParameter.requireStatedAid(AID_MEMBER, statedAid, aidToken, codec);
    }

    /**
     * {@code FUNCTION NUMVAL-C} - lines 305, 309, 313, 317, 321 and 325.
     *
     * @param image the argument to convert; must not be {@code null}
     * @return the value the argument denotes, or zero when it does not conform
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static BigDecimal numvalC(String image) {
        return NumericIntrinsics.numvalC(image);
    }

    /**
     * The conformance half of {@link #numvalC(String)}, in the shape {@code FUNCTION TEST-NUMVAL-C} reports
     * it.
     *
     * @param image the argument to test; must not be {@code null}
     * @return {@link #NUMVAL_CONFORMS} when the argument conforms; otherwise the one-based position of the
     *     first character in error, or the argument's length plus one when it holds no digit at all
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static int testNumvalC(String image) {
        return NumericIntrinsics.testNumvalC(image);
    }

    /**
     * {@code FUNCTION INTEGER-OF-DATE} - line 230.
     *
     * @param standardDate the date as a {@code 9(8)} integer
     * @return the day number, or {@value #DATE_INTRINSIC_UNDEFINED} when the argument is outside
     *     {@value #INTEGER_OF_DATE_LOWEST_ARGUMENT} to {@value #INTEGER_OF_DATE_HIGHEST_ARGUMENT} or is not a
     *     real calendar
     */
    public static int integerOfDate(int standardDate) {
        if (standardDate < INTEGER_OF_DATE_LOWEST_ARGUMENT
                || standardDate > INTEGER_OF_DATE_HIGHEST_ARGUMENT) {
            return DATE_INTRINSIC_UNDEFINED;
        }
        int year = standardDate / STANDARD_DATE_YEAR_DIVISOR;
        int month = standardDate / STANDARD_DATE_MONTH_DIVISOR % STANDARD_DATE_COMPONENT_MODULUS;
        int day = standardDate % STANDARD_DATE_COMPONENT_MODULUS;
        try {
            return (int) LocalDate.of(year, month, day).toEpochDay() + INTEGER_OF_DATE_EPOCH_OFFSET;
        } catch (DateTimeException notAStandardDate) {
            return DATE_INTRINSIC_UNDEFINED;
        }
    }

    /**
     * {@code FUNCTION DATE-OF-INTEGER} - line 229, the inverse of {@link #integerOfDate(int)}.
     *
     * @param integerDate the day number, counting 1601-01-01 as day 1
     * @return the date as a {@code 9(8)} integer, or {@value #DATE_INTRINSIC_UNDEFINED} when the argument
     *     is outside {@value #DATE_OF_INTEGER_LOWEST_ARGUMENT} to {@link #DATE_OF_INTEGER_HIGHEST_ARGUMENT}
     */
    public static int dateOfInteger(int integerDate) {
        if (integerDate < DATE_OF_INTEGER_LOWEST_ARGUMENT
                || integerDate > DATE_OF_INTEGER_HIGHEST_ARGUMENT) {
            return DATE_INTRINSIC_UNDEFINED;
        }
        LocalDate date = LocalDate.ofEpochDay((long) integerDate - INTEGER_OF_DATE_EPOCH_OFFSET);
        return standardDate(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }

    /**
     * Composes {@code WS-CURDATE-N}, the {@code PIC 9(08)} view that
     * {@code 10 WS-CURDATE-N REDEFINES WS-CURDATE} lays over the year, month and day sub-items of
     * {@code CSDAT01Y}.
     *
     * @param year {@code WS-CURDATE-YEAR PIC 9(04)}
     * @param month {@code WS-CURDATE-MONTH PIC 9(02)}
     * @param day {@code WS-CURDATE-DAY PIC 9(02)}
     * @return the eight-digit standard date
     */
    public static int standardDate(int year, int month, int day) {
        return year * STANDARD_DATE_YEAR_DIVISOR + month * STANDARD_DATE_MONTH_DIVISOR + day;
    }

    /**
     * {@code WS-CURDATE-YEAR}, read back through the {@code WS-CURDATE-N} redefinition.
     *
     * @param standardDate the eight-digit standard date
     * @return the four-digit year
     */
    public static int yearOfStandardDate(int standardDate) {
        return standardDate / STANDARD_DATE_YEAR_DIVISOR;
    }

    /**
     * {@code WS-CURDATE-MONTH}, read back through the {@code WS-CURDATE-N} redefinition.
     *
     * @param standardDate the eight-digit standard date
     * @return the two-digit month
     */
    public static int monthOfStandardDate(int standardDate) {
        return standardDate / STANDARD_DATE_MONTH_DIVISOR % STANDARD_DATE_COMPONENT_MODULUS;
    }

    /**
     * {@code WS-CURDATE-DAY}, read back through the {@code WS-CURDATE-N} redefinition.
     *
     * @param standardDate the eight-digit standard date
     * @return the two-digit day
     */
    public static int dayOfStandardDate(int standardDate) {
        return standardDate % STANDARD_DATE_COMPONENT_MODULUS;
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private void display(ProgramState state, String text) {
        state.recordDisplay(text);
        LOG.info(text);
    }

    private static void requireState(ProgramState state) {
        Objects.requireNonNull(state, "A ProgramState is required: every WORKING-STORAGE item of "
                + "CORPT00C lives in it, so that two concurrent requests cannot see each other's screen");
    }

    /**
     * One execution's working storage: every item {@code CORPT00C} declares, plus the screen buffer, the
     * communication area and the record of what the execution actually did.
     */
    public static final class ProgramState {
        private static final FixedWidthCodec PICTURE_RULES =
                new FixedWidthCodec(PICTURE_RULES_CHARSET);

        private final ReportRequestResponse response = new ReportRequestResponse();

        private ReportRequestRequest.SymbolicMapMetadata symbolicMap =
                ReportRequestRequest.SymbolicMapMetadata.initial();

        private NavigationContext commarea = NavigationContext.empty();

        private String message = SPACE.repeat(WS_MESSAGE_LENGTH);

        private String reportName = SPACE.repeat(WS_REPORT_NAME_LENGTH);

        private String startDateYyyy = SPACE.repeat(DATE_YEAR_LENGTH);

        private String startDateMm = SPACE.repeat(DATE_PART_LENGTH);

        private String startDateDd = SPACE.repeat(DATE_PART_LENGTH);

        private String endDateYyyy = SPACE.repeat(DATE_YEAR_LENGTH);

        private String endDateMm = SPACE.repeat(DATE_PART_LENGTH);

        private String endDateDd = SPACE.repeat(DATE_PART_LENGTH);

        private String parmStartDate1 = SPACE.repeat(WS_DATE_LENGTH);

        private String parmEndDate1 = SPACE.repeat(WS_DATE_LENGTH);

        private String parmStartDate2 = SPACE.repeat(WS_DATE_LENGTH);

        private String parmEndDate2 = SPACE.repeat(WS_DATE_LENGTH);

        private String jclRecord = SPACE.repeat(JCL_RECORD_LENGTH);

        private boolean errFlag;

        private boolean transactEof;

        private boolean sendErase = true;

        private boolean endLoop;

        private int idx;

        private int respCd;

        private int reasCd;

        private DateHeader dateHeader;

        private DateValidationResult csutldtcResult;

        private boolean returned;

        private boolean transferred;

        private boolean screenSent;

        private boolean screenSentWithErase;

        private final List<String> submittedRecords = new ArrayList<>();

        private final List<String> displayLines = new ArrayList<>();

        /**
         * {@code 01 CORPT0AI} / {@code 01 CORPT0AO} - the one buffer both views describe.
         *
         * @return the screen, never {@code null}
         */
        public ReportRequestResponse response() {
            return response;
        }

        /**
         * The symbolic map's metadata, carrying the {@code MOVE -1 TO <field>L} cursor request.
         *
         * @return the metadata of all seventeen fields, never {@code null}
         */
        public ReportRequestRequest.SymbolicMapMetadata symbolicMap() {
            return symbolicMap;
        }

        /**
         * This screen's presentation metadata, in the shared envelope every online response publishes.
         *
         * @return the metadata; never {@code null}
         */
        public ScreenMetadata screenMetadata() {
            Map<String, ScreenMetadata.FieldMetadata> fields = new LinkedHashMap<>();
            for (ReportRequestResponse.ScreenField field : ReportRequestResponse.ScreenField.values()) {
                ReportRequestResponse.FieldAttributes quad = response.attributesOf(field);
                fields.put(field.baseName(), ScreenMetadata.FieldMetadata.of(quad.colour(), quad.ps(),
                        quad.hilight(), quad.validn()));
            }
            String cursorOn = null;
            for (ReportRequestRequest.ScreenField field : ReportRequestRequest.ScreenField.values()) {
                if (symbolicMap.metadata(field).cursorRequested()) {
                    cursorOn = field.bmsName();
                    break;
                }
            }
            return ScreenMetadata.of(cursorOn,
                    response.attributesOf(ReportRequestResponse.ScreenField.ERRMSG).colour(),
                    false,
                    fields);
        }

        /**
         * {@code MOVE -1 TO <field>L OF CORPT0AI} - the cursor request, at twenty-two sites.
         *
         * @param field the field whose {@code xxxL} length item receives {@code -1}; must not be
         *     {@code null}
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public void moveMinusOneTo(ReportRequestRequest.ScreenField field) {
            symbolicMap = symbolicMap.withCursorAt(field);
        }

        /**
         * Whether a field is currently asking for the cursor.
         *
         * @param field the field to inspect; must not be {@code null}
         * @return whether its length item holds {@code -1}
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public boolean cursorRequestedOn(ReportRequestRequest.ScreenField field) {
            return symbolicMap.metadata(field).cursorRequested();
        }

        public String monthlyI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.MONTHLY);
        }

        public String yearlyI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.YEARLY);
        }

        public String customI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.CUSTOM);
        }

        public String sdtmmI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.SDTMM);
        }

        public String sdtddI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.SDTDD);
        }

        public String sdtyyyyI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.SDTYYYY);
        }

        public String edtmmI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.EDTMM);
        }

        public String edtddI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.EDTDD);
        }

        public String edtyyyyI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.EDTYYYY);
        }

        public String confirmI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.CONFIRM);
        }

        /**
         * {@code MOVE WS-NUM-99 TO SDTMMI OF CORPT0AI} - line 307.
         *
         * @param value the normalised value; must not be {@code null}
         */
        public void setSdtmmI(String value) {
            response.setPayloadValue(ReportRequestResponse.ScreenField.SDTMM, value);
        }

        /**
         * {@code MOVE WS-NUM-99 TO SDTDDI OF CORPT0AI} - line 311.
         *
         * @param value the normalised value; must not be {@code null}
         */
        public void setSdtddI(String value) {
            response.setPayloadValue(ReportRequestResponse.ScreenField.SDTDD, value);
        }

        /**
         * {@code MOVE WS-NUM-9999 TO SDTYYYYI OF CORPT0AI} - line 315.
         *
         * @param value the normalised value; must not be {@code null}
         */
        public void setSdtyyyyI(String value) {
            response.setPayloadValue(ReportRequestResponse.ScreenField.SDTYYYY, value);
        }

        /**
         * {@code MOVE WS-NUM-99 TO EDTMMI OF CORPT0AI} - line 319.
         *
         * @param value the normalised value; must not be {@code null}
         */
        public void setEdtmmI(String value) {
            response.setPayloadValue(ReportRequestResponse.ScreenField.EDTMM, value);
        }

        /**
         * {@code MOVE WS-NUM-99 TO EDTDDI OF CORPT0AI} - line 323.
         *
         * @param value the normalised value; must not be {@code null}
         */
        public void setEdtddI(String value) {
            response.setPayloadValue(ReportRequestResponse.ScreenField.EDTDD, value);
        }

        /**
         * {@code MOVE WS-NUM-9999 TO EDTYYYYI OF CORPT0AI} - line 327.
         *
         * @param value the normalised value; must not be {@code null}
         */
        public void setEdtyyyyI(String value) {
            response.setPayloadValue(ReportRequestResponse.ScreenField.EDTYYYY, value);
        }

        public NavigationContext commarea() {
            return commarea;
        }

        /**
         * Replaces the communication area, which is immutable, so every {@code MOVE} into one of its items
         * produces a new value.
         *
         * @param commarea the new communication area; must not be {@code null}
         * @throws NullPointerException if {@code commarea} is {@code null}
         */
        public void setCommarea(NavigationContext commarea) {
            this.commarea = Objects.requireNonNull(commarea, "A communication area is required; use "
                    + "NavigationContext.empty() for the EIBCALEN = 0 case");
        }

        // Every setter applies the PIC X move rule, so an item can never hold anything but its declared
        // width.

        public String message() {
            return message;
        }

        /**
         * {@code MOVE ... TO WS-MESSAGE}.
         *
         * @param message the value; must not be {@code null}
         * @throws NullPointerException if {@code message} is {@code null}
         */
        public void setMessage(String message) {
            this.message = PICTURE_RULES.movePicX(message, WS_MESSAGE_LENGTH);
        }

        public String reportName() {
            return reportName;
        }

        /**
         * {@code MOVE ... TO WS-REPORT-NAME} - lines 214, 240 and 433.
         *
         * @param reportName the value; must not be {@code null}
         * @throws NullPointerException if {@code reportName} is {@code null}
         */
        public void setReportName(String reportName) {
            this.reportName = PICTURE_RULES.movePicX(reportName, WS_REPORT_NAME_LENGTH);
        }

        /**
         * {@code WS-START-DATE} - the whole ten-byte group, composed from its three sub-items and the two
         * {@code FILLER PIC X(01) VALUE '-'} separators at lines 62 and 64.
         *
         * @return exactly {@value #WS_DATE_LENGTH} characters
         */
        public String startDate() {
            return startDateYyyy + DATE_SEPARATOR + startDateMm + DATE_SEPARATOR + startDateDd;
        }

        /**
         * {@code WS-END-DATE} - the whole ten-byte group, with the separators of lines 68 and 70.
         *
         * @return exactly {@value #WS_DATE_LENGTH} characters
         */
        public String endDate() {
            return endDateYyyy + DATE_SEPARATOR + endDateMm + DATE_SEPARATOR + endDateDd;
        }

        public String startDateYyyy() {
            return startDateYyyy;
        }

        public String startDateMm() {
            return startDateMm;
        }

        public String startDateDd() {
            return startDateDd;
        }

        public String endDateYyyy() {
            return endDateYyyy;
        }

        public String endDateMm() {
            return endDateMm;
        }

        public String endDateDd() {
            return endDateDd;
        }

        /**
         * {@code MOVE ... TO WS-START-DATE-YYYY} - lines 217, 243 and 381.
         *
         * @param value the value; must not be {@code null}
         */
        public void setStartDateYyyy(String value) {
            this.startDateYyyy = PICTURE_RULES.movePicX(value, DATE_YEAR_LENGTH);
        }

        /**
         * {@code MOVE ... TO WS-START-DATE-MM} - lines 218, 245 and 382.
         *
         * @param value the value; must not be {@code null}
         */
        public void setStartDateMm(String value) {
            this.startDateMm = PICTURE_RULES.movePicX(value, DATE_PART_LENGTH);
        }

        /**
         * {@code MOVE ... TO WS-START-DATE-DD} - lines 219, 246 and 383.
         *
         * @param value the value; must not be {@code null}
         */
        public void setStartDateDd(String value) {
            this.startDateDd = PICTURE_RULES.movePicX(value, DATE_PART_LENGTH);
        }

        /**
         * {@code MOVE ... TO WS-END-DATE-YYYY} - lines 232, 244 and 384.
         *
         * @param value the value; must not be {@code null}
         */
        public void setEndDateYyyy(String value) {
            this.endDateYyyy = PICTURE_RULES.movePicX(value, DATE_YEAR_LENGTH);
        }

        /**
         * {@code MOVE ... TO WS-END-DATE-MM} - lines 233, 250 and 385.
         *
         * @param value the value; must not be {@code null}
         */
        public void setEndDateMm(String value) {
            this.endDateMm = PICTURE_RULES.movePicX(value, DATE_PART_LENGTH);
        }

        /**
         * {@code MOVE ... TO WS-END-DATE-DD} - lines 234, 251 and 386.
         *
         * @param value the value; must not be {@code null}
         */
        public void setEndDateDd(String value) {
            this.endDateDd = PICTURE_RULES.movePicX(value, DATE_PART_LENGTH);
        }

        public String parmStartDate1() {
            return parmStartDate1;
        }

        public String parmEndDate1() {
            return parmEndDate1;
        }

        public String parmStartDate2() {
            return parmStartDate2;
        }

        public String parmEndDate2() {
            return parmEndDate2;
        }

        /**
         * {@code MOVE WS-START-DATE TO PARM-START-DATE-1} - lines 220, 247 and 429.
         *
         * @param value the value; must not be {@code null}
         */
        public void setParmStartDate1(String value) {
            this.parmStartDate1 = PICTURE_RULES.movePicX(value, WS_DATE_LENGTH);
        }

        /**
         * {@code MOVE WS-END-DATE TO PARM-END-DATE-1} - lines 235, 252 and 431.
         *
         * @param value the value; must not be {@code null}
         */
        public void setParmEndDate1(String value) {
            this.parmEndDate1 = PICTURE_RULES.movePicX(value, WS_DATE_LENGTH);
        }

        /**
         * {@code MOVE WS-START-DATE TO PARM-START-DATE-2} - lines 221, 248 and 430.
         *
         * @param value the value; must not be {@code null}
         */
        public void setParmStartDate2(String value) {
            this.parmStartDate2 = PICTURE_RULES.movePicX(value, WS_DATE_LENGTH);
        }

        /**
         * {@code MOVE WS-END-DATE TO PARM-END-DATE-2} - lines 236, 253 and 432.
         *
         * @param value the value; must not be {@code null}
         */
        public void setParmEndDate2(String value) {
            this.parmEndDate2 = PICTURE_RULES.movePicX(value, WS_DATE_LENGTH);
        }

        public String jclRecord() {
            return jclRecord;
        }

        /**
         * {@code MOVE JOB-LINES(WS-IDX) TO JCL-RECORD} - line 501.
         *
         * @param value the record; must not be {@code null}
         */
        public void setJclRecord(String value) {
            this.jclRecord = PICTURE_RULES.movePicX(value, JCL_RECORD_LENGTH);
        }

        public int idx() {
            return idx;
        }

        /**
         * {@code PERFORM VARYING WS-IDX} - line 498.
         *
         * @param idx the subscript
         */
        public void setIdx(int idx) {
            this.idx = idx;
        }

        public int respCd() {
            return respCd;
        }

        /**
         * {@code RESP(WS-RESP-CD)} - lines 521 and 602.
         *
         * @param respCd the response code
         */
        public void setRespCd(int respCd) {
            this.respCd = respCd;
        }

        public int reasCd() {
            return reasCd;
        }

        /**
         * {@code RESP2(WS-REAS-CD)} - lines 522 and 603.
         *
         * @param reasCd the reason code
         */
        public void setReasCd(int reasCd) {
            this.reasCd = reasCd;
        }

        public boolean errFlagOn() {
            return errFlag;
        }

        public boolean errFlagOff() {
            return !errFlag;
        }

        /**
         * {@code MOVE 'Y' TO WS-ERR-FLG} - at twenty sites.
         */
        public void setErrFlagOn() {
            this.errFlag = true;
        }

        /**
         * {@code SET ERR-FLG-OFF TO TRUE} - line 165.
         */
        public void setErrFlagOff() {
            this.errFlag = false;
        }

        public boolean transactEof() {
            return transactEof;
        }

        public boolean transactNotEof() {
            return !transactEof;
        }

        /**
         * {@code SET TRANSACT-EOF TO TRUE}; the program never does this, and the flag exists anyway.
         */
        public void setTransactEof() {
            this.transactEof = true;
        }

        /**
         * {@code SET TRANSACT-NOT-EOF TO TRUE} - line 166.
         */
        public void setTransactNotEof() {
            this.transactEof = false;
        }

        public boolean sendEraseYes() {
            return sendErase;
        }

        public boolean sendEraseNo() {
            return !sendErase;
        }

        /**
         * {@code SET SEND-ERASE-YES TO TRUE} - line 167.
         */
        public void setSendEraseYes() {
            this.sendErase = true;
        }

        /**
         * {@code SET SEND-ERASE-NO TO TRUE}.
         */
        public void setSendEraseNo() {
            this.sendErase = false;
        }

        public boolean endLoopYes() {
            return endLoop;
        }

        public boolean endLoopNo() {
            return !endLoop;
        }

        /**
         * {@code SET END-LOOP-YES TO TRUE} - line 504.
         */
        public void setEndLoopYes() {
            this.endLoop = true;
        }

        /**
         * {@code SET END-LOOP-NO TO TRUE} - line 496.
         */
        public void setEndLoopNo() {
            this.endLoop = false;
        }

        /**
         * The most recent {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA}.
         *
         * @return the captured date and time, or {@code null} before the first read
         */
        public DateHeader dateHeader() {
            return dateHeader;
        }

        /**
         * Records a {@code FUNCTION CURRENT-DATE} read.
         *
         * @param dateHeader the captured date and time; must not be {@code null}
         * @throws NullPointerException if {@code dateHeader} is {@code null}
         */
        public void setDateHeader(DateHeader dateHeader) {
            this.dateHeader = Objects.requireNonNull(dateHeader, "A DateHeader is required to record a "
                    + "FUNCTION CURRENT-DATE read");
        }

        /**
         * {@code CSUTLDTC-RESULT} of the most recent {@code CALL 'CSUTLDTC'}.
         *
         * @return the result, or {@code null} when the arm never called it
         */
        public DateValidationResult csutldtcResult() {
            return csutldtcResult;
        }

        /**
         * Records a {@code CALL 'CSUTLDTC'} result into the single carrier both call sites share.
         *
         * @param csutldtcResult the result; must not be {@code null}
         * @throws NullPointerException if {@code csutldtcResult} is {@code null}
         */
        public void setCsutldtcResult(DateValidationResult csutldtcResult) {
            this.csutldtcResult = Objects.requireNonNull(csutldtcResult, "A CSUTLDTC result is required; "
                    + "the COBOL clears CSUTLDTC-RESULT and the call fills it");
        }

        public boolean returned() {
            return returned;
        }

        /**
         * {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)}.
         */
        public void markReturned() {
            this.returned = true;
        }

        public boolean transferred() {
            return transferred;
        }

        /**
         * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)} - line 549.
         */
        public void markTransferred() {
            this.transferred = true;
        }

        public boolean screenSent() {
            return screenSent;
        }

        public boolean screenSentWithErase() {
            return screenSentWithErase;
        }

        /**
         * {@code EXEC CICS SEND MAP('CORPT0A') MAPSET('CORPT00') FROM(CORPT0AO) [ERASE] CURSOR} - lines 563
         * and 571.
         *
         * @param erase whether the send specified {@code ERASE}
         */
        public void recordScreenSent(boolean erase) {
            this.screenSent = true;
            this.screenSentWithErase = erase;
        }

        /**
         * Every {@code JCL-RECORD} the {@code JOBS} queue accepted from this execution, in order.
         *
         * @return an unmodifiable view, {@value #JOB_LINE_COUNT} records after a successful submission
         */
        public List<String> submittedRecords() {
            return Collections.unmodifiableList(submittedRecords);
        }

        /**
         * Records one {@code EXEC CICS WRITEQ TD} that reported {@code DFHRESP(NORMAL)}.
         *
         * <p>Called from the {@code WHEN DFHRESP(NORMAL)} arm only. A refused write must not be
         * recorded, because the queue was not appended to and
         * {@link #submittedRecords()} reports what the queue holds.
         *
         * @param record the eighty-byte record the queue accepted; must not be {@code null}
         * @throws NullPointerException if {@code record} is {@code null}
         */
        public void recordSubmitted(String record) {
            submittedRecords.add(Objects.requireNonNull(record, "A record is required"));
        }

        /**
         * Every {@code DISPLAY} this execution emitted, in order.
         *
         * @return an unmodifiable view
         */
        public List<String> displayLines() {
            return Collections.unmodifiableList(displayLines);
        }

        public void recordDisplay(String text) {
            displayLines.add(Objects.requireNonNull(text, "Displayed text is required"));
        }
    }

    /**
     * {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} - the one CICS facility this program uses that Java has no
     * equivalent for, expressed as an explicit outbound port.
     *
     * <p>The program's own {@code EVALUATE WS-RESP-CD} decides what to do, so an implementation reports an
     * outcome and never throws.
     */
    public interface JobSubmissionPort {
        WriteQueueOutcome writeQueueTd(String jclRecord);
    }

    /**
     * The {@code RESP} and {@code RESP2} of one {@code EXEC CICS WRITEQ TD} - the values {@code WS-RESP-CD}
     * and {@code WS-REAS-CD} receive at lines 521 and 522 and that line 525 evaluates.
     *
     * @param resp the {@code RESP} value; {@link FileStatus#NORMAL} when the record was written
     * @param resp2 the {@code RESP2} value; {@link FileStatus#NO_REASON_CODE} throughout, because CICS
     *     supplies no secondary reason for these conditions on a {@code WRITEQ TD}
     */
    public record WriteQueueOutcome(int resp, int resp2) {
        /**
         * {@code WHEN DFHRESP(NORMAL)} - line 526.
         */
        public static final WriteQueueOutcome NORMAL =
                new WriteQueueOutcome(FileStatus.NORMAL, FileStatus.NO_REASON_CODE);

        /**
         * {@code LENGERR}: the record is not {@value ReportRequestController#JCL_RECORD_LENGTH} bytes, so
         * it cannot go to a queue declared {@code RECORDSIZE(80) RECORDFORMAT(FIXED)}.
         *
         * @return the outcome, never {@code null}
         */
        public static WriteQueueOutcome lengthError() {
            return new WriteQueueOutcome(FileStatus.LENGERR, FileStatus.NO_REASON_CODE);
        }

        /**
         * {@code INVREQ}: the request cannot be honoured as issued - here, a character the queue's code
         * page cannot represent.
         *
         * @return the outcome, never {@code null}
         */
        public static WriteQueueOutcome invalidRequest() {
            return new WriteQueueOutcome(FileStatus.INVREQ, FileStatus.NO_REASON_CODE);
        }

        /**
         * {@code NOTOPEN}: the destination could not be reached, which is what an extrapartition queue
         * whose dataset cannot be opened reports.
         *
         * @return the outcome, never {@code null}
         */
        public static WriteQueueOutcome notOpen() {
            return new WriteQueueOutcome(FileStatus.NOTOPEN, FileStatus.NO_REASON_CODE);
        }

        /**
         * {@code WHEN DFHRESP(NORMAL)} - the test line 526 performs.
         *
         * @return whether the write succeeded
         */
        public boolean normal() {
            return resp == FileStatus.NORMAL;
        }

        /**
         * The batch-side reading of the same outcome, for a caller that speaks {@code FILE STATUS}.
         *
         * @return {@link FileStatus.Outcome#OK} for a normal write, otherwise
         *     {@link FileStatus.Outcome#OTHER}
         */
        public FileStatus.Outcome fileStatus() {
            return FileStatus.outcomeOfCicsResp(resp);
        }
    }

    /**
     * The default {@link JobSubmissionPort}: appends eighty-byte records to the destination
     * {@code carddemo.job-submission} configures.
     */
    @Component
    public static class InternalReaderJobSubmissionPort implements JobSubmissionPort {
        private static final Log PORT_LOG = LogFactory.getLog(InternalReaderJobSubmissionPort.class);

        private static final String DIRECTORY_PERMISSIONS = "rwx------";

        private static final String FILE_PERMISSIONS = "rw-------";

        private final JobSubmissionProperties properties;

        private final FixedWidthCodec codec;

        private final Path destination;

        private final RuntimeException refusal;

        private final Object appendLock = new Object();

        private final boolean posixPermissionsSupported;

        /**
         * Wires the port from configuration, encoding records in the code page
         * {@code carddemo.job-submission.charset} declares.
         *
         * @param properties the {@code carddemo.job-submission} binding; must not be {@code null}
         * @throws NullPointerException if {@code properties} is {@code null}
         * @throws IllegalStateException if the configured record length is not the CSD's
         *     {@code RECORDSIZE(80)}
         * @throws IllegalArgumentException if the configured code page names nothing this platform provides
         */
        @Autowired
        public InternalReaderJobSubmissionPort(JobSubmissionProperties properties) {
            this(Objects.requireNonNull(properties, "The carddemo.job-submission binding is required: "
                            + "the queue name, the record geometry, the code page and the destination "
                            + "are configured, never written in Java"),
                    properties.queueCharset());
        }

        /**
         * Wires the port with an explicit queue code page, overriding
         * {@code carddemo.job-submission.charset}.
         *
         * @param properties the {@code carddemo.job-submission} binding; must not be {@code null}
         * @param queueCharset the code page the 80-byte records are encoded in, supplied by the caller
         *     rather than injected; must not be {@code null}
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalStateException if the configured record length is not the CSD's
         *     {@code RECORDSIZE(80)}
         */
        public InternalReaderJobSubmissionPort(JobSubmissionProperties properties,
                                              Charset queueCharset) {
            this.properties = Objects.requireNonNull(properties, "The carddemo.job-submission binding is "
                    + "required: the queue name, the record geometry and the destination are configured, "
                    + "never written in Java");
            Objects.requireNonNull(queueCharset, "A code page is required; it is never the platform "
                    + "default");
            this.codec = new FixedWidthCodec(queueCharset);

            if (properties.recordLength() != JobSubmissionProperties.TDQ_RECORD_LENGTH) {
                throw new IllegalStateException("carddemo.job-submission.record-length is "
                        + properties.recordLength() + ", but app/csd/CARDDEMO.CSD:503 declares "
                        + "RECORDSIZE(" + JobSubmissionProperties.TDQ_RECORD_LENGTH + ") for TDQUEUE("
                        + properties.queueName() + ") and app/cbl/CORPT00C.cbl:79 declares JCL-RECORD "
                        + "PIC X(" + JobSubmissionProperties.TDQ_RECORD_LENGTH + "). A record width is "
                        + "fixed by the program and must not be overridden per profile");
            }

            Path resolved = null;
            RuntimeException failure = null;
            try {
                resolved = properties.destinationPath();
                if (resolved.getParent() == null) {
                    throw new IllegalArgumentException("carddemo.job-submission.destination has no "
                            + "parent directory, so it names a filesystem root rather than a dataset the "
                            + "internal reader could consume");
                }
            } catch (IllegalArgumentException notADestination) {
                failure = notADestination;
            }
            this.destination = resolved;
            this.refusal = failure;
            this.posixPermissionsSupported =
                    FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        }

        /**
         * {@code EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD) LENGTH(LENGTH OF JCL-RECORD)} - lines
         * 517 to 523.
         *
         * @param jclRecord exactly {@link JobSubmissionProperties#TDQ_RECORD_LENGTH} characters; must not
         *     be {@code null}
         * @return {@link WriteQueueOutcome#NORMAL} when the record was appended, otherwise {@code LENGERR},
         *     {@code INVREQ} or {@code NOTOPEN}
         * @throws NullPointerException if {@code jclRecord} is {@code null}
         */
        @Override
        public WriteQueueOutcome writeQueueTd(String jclRecord) {
            Objects.requireNonNull(jclRecord, "A record is required: EXEC CICS WRITEQ TD writes FROM("
                    + "JCL-RECORD), which is never absent");

            if (jclRecord.length() != properties.recordLength()) {
                PORT_LOG.error("Refusing a " + jclRecord.length() + "-character record: TDQUEUE("
                        + properties.queueName() + ") declares RECORDSIZE(" + properties.recordLength()
                        + ") with RECORDFORMAT(" + properties.recordFormat() + "); reporting RESP "
                        + "LENGERR");
                return WriteQueueOutcome.lengthError();
            }

            // A second width test would be unreachable, so none is written; a multi-byte code page is
            // rejected below as INVREQ instead, which is the outcome CICS reports when a WRITEQ TD request
            // itself is not valid for the queue.
            byte[] image;
            try {
                image = codec.encodeImage(jclRecord, JCL_RECORD_SUBJECT);
            } catch (IllegalArgumentException unrepresentable) {
                PORT_LOG.error("A character of the record cannot be represented as one byte in code "
                        + "page " + codec.charset().name() + " ("
                        + unrepresentable.getClass().getName() + "); reporting RESP INVREQ");
                return WriteQueueOutcome.invalidRequest();
            }

            if (refusal != null) {
                PORT_LOG.error("The job-submission destination is not usable ("
                        + refusal.getClass().getName() + "); reporting RESP NOTOPEN, which is what an "
                        + "extrapartition queue whose dataset cannot be opened reports");
                return WriteQueueOutcome.notOpen();
            }

            synchronized (appendLock) {
                try {
                    appendWithinApprovedRoot(image);
                } catch (IOException cannotAppend) {
                    PORT_LOG.error("Could not append a " + properties.recordLength()
                            + "-byte record to the " + properties.disposition()
                            + " job-submission destination (" + cannotAppend.getClass().getName()
                            + "); reporting RESP NOTOPEN");
                    return WriteQueueOutcome.notOpen();
                }
            }

            return WriteQueueOutcome.NORMAL;
        }

        private void appendWithinApprovedRoot(byte[] image) throws IOException {
            Path approvedRoot = properties.approvedRootPath();

            // Per write rather than once at startup, because the root can be replaced between two writes
            // (CWE-367) - which is exactly what the second write of a run has to be able to refuse.
            if (Files.isSymbolicLink(approvedRoot)) {
                throw new IOException("The approved root '" + approvedRoot + "' is a symbolic link, so it "
                        + "names a directory tree other than the one the configuration reads as approved. "
                        + "Job-submission output goes to a directory this deployment owns; relocating it "
                        + "is a change of carddemo.job-submission.approved-root, not something a link may "
                        + "do on its own");
            }

            Path realRoot = approvedRoot.toRealPath();
            if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("The approved root '" + approvedRoot + "' resolves to '" + realRoot
                        + "', which is not a directory. The root names the directory tree job-submission "
                        + "output may go in, so it cannot resolve to a file or to a dangling link");
            }

            Path below = approvedRoot.relativize(destination);
            Path current = realRoot;
            for (int element = 0; element < below.getNameCount(); element++) {
                Path next = current.resolve(below.getName(element));
                boolean last = element == below.getNameCount() - 1;
                if (Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(next)) {
                        throw new IOException("Refusing to write through a symbolic link: the "
                                + "job-submission destination's component " + (element + 1) + " of "
                                + below.getNameCount() + " below the approved root is a link, and "
                                + "following it would append records outside the root the deployment "
                                + "approved");
                    }
                    if (!last && !Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Refusing to write: component " + (element + 1)
                                + " of the job-submission destination exists but is not a directory");
                    }
                } else if (!last) {
                    if (posixPermissionsSupported) {
                        Files.createDirectory(next, PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString(DIRECTORY_PERMISSIONS)));
                    } else {
                        Files.createDirectory(next);
                    }
                }
                current = next;
            }

            requireParentStillWithinRoot(current.getParent(), realRoot);

            Set<OpenOption> options = Set.of(StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                    LinkOption.NOFOLLOW_LINKS);
            try (FileChannel channel = posixPermissionsSupported
                    ? FileChannel.open(current, options, PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString(FILE_PERMISSIONS)))
                    : FileChannel.open(current, options)) {
                try (FileLock exclusive = channel.lock()) {
                    ByteBuffer record = ByteBuffer.wrap(image);
                    while (record.hasRemaining()) {
                        channel.write(record);
                    }
                    assertHeld(exclusive);
                }
            } catch (OverlappingFileLockException alreadyHeldByThisJvm) {
                throw new IOException("Another writer in this application instance holds a lock on the "
                        + "job-submission destination '" + current + "'. The record was NOT written. "
                        + "Exactly one job-submission port may be wired per destination, because a "
                        + "RECORDFORMAT(FIXED) queue whose writers are not ordered yields torn records "
                        + "rather than a reported failure", alreadyHeldByThisJvm);
            }
        }

        private static void assertHeld(final FileLock exclusive) throws IOException {
            if (!exclusive.isValid()) {
                throw new IOException("The exclusive lock on the job-submission destination was released "
                        + "before the record was written, so the write would not have been indivisible to "
                        + "another writer. The record was NOT written");
            }
        }

        static void requireParentStillWithinRoot(final Path parent, final Path realRoot)
                throws IOException {
            final Path resolvedParent = parent.toRealPath();
            if (!resolvedParent.equals(parent) || !resolvedParent.startsWith(realRoot)) {
                throw new IOException("Refusing to write: the job-submission destination's directory "
                        + "resolves elsewhere than where it was verified, so a path component changed "
                        + "while the record was being written");
            }
        }

        /**
         * The configuration this port was wired from, for a diagnostic or a test that asserts the CSD
         * contract is what reached the runtime.
         *
         * @return the binding, never {@code null}
         */
        public JobSubmissionProperties properties() {
            return properties;
        }

        public Charset queueCharset() {
            return codec.charset();
        }
    }
}
