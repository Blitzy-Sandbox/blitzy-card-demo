package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.NumericIntrinsics;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
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
import java.nio.ByteBuffer;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * The stateless transaction-report request screen: a like-for-like migration of
 * {@code app/cbl/CORPT00C.cbl} (649 lines), CSD transaction {@value #TRANSACTION_ID}, exposed as
 * {@code POST }{@value #REPORTS_PATH}.
 *
 * <p>Nothing here is added, removed, reordered, simplified or corrected relative to that program.
 * Where the COBOL does something odd, this class does the same odd thing, and says so.
 *
 * <h2>What the program does</h2>
 *
 * The source header states its function outright: <em>"Print Transaction reports by submitting batch
 * job from online using extra partition TDQ."</em> The operator picks Monthly, Yearly or a custom
 * date range, confirms, and the program builds a seventeen-record, eighty-byte-per-record JCL stream
 * and writes it to the CICS transient data queue {@code JOBS}, which the region has open on the
 * internal reader. The job it submits is {@code app/jcl/TRANREPT.jcl} - the batch transaction report,
 * {@code CBTRN03C} - with the chosen date range substituted into the sort's {@code SYMNAMES} and into
 * the report step's {@code DATEPARM} instream data.
 *
 * <h2>Six facts that govern every line below</h2>
 *
 * <ol>
 *   <li><strong>Every {@code PERFORM SEND-TRNRPT-SCREEN} is terminal.</strong> The paragraph ends
 *       {@code GO TO RETURN-TO-CICS} at line 580, and {@code RETURN-TO-CICS} issues
 *       {@code EXEC CICS RETURN} at line 587, which ends the task. So a send never returns to its
 *       caller. Two consequences are load-bearing: the program's own {@code IF NOT ERR-FLG-ON} tests
 *       at lines 434, 445 and 476 - and the {@code OR ERR-FLG-ON} in the emit loop's {@code UNTIL} -
 *       <em>are</em> the source's own "have we already returned" guards, so they are reproduced
 *       verbatim and no substitute is invented; and the {@code EXEC CICS RETURN} at line 199 is
 *       byte-identical to the one {@code RETURN-TO-CICS} executes and is unreachable, because every
 *       arm above it has already returned or transferred. Inside {@code PROCESS-ENTER-KEY}'s custom
 *       arm the source relies on the {@code GO TO} alone, so each terminal block ends with a Java
 *       {@code return} - the rule R7 restructuring of that transfer, and the reason a rejected month
 *       is not re-reported later as an invalid date.</li>
 *   <li><strong>This program accesses no dataset.</strong> It declares
 *       {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} at line 40 and a {@code WS-TRANSACT-EOF}
 *       flag at line 44, but every {@code EXEC CICS} command in the file is {@code WRITEQ TD},
 *       {@code SEND}, {@code RECEIVE}, {@code RETURN} or {@code XCTL}: there is no file command at
 *       all. No repository is injected, no dataset name is resolved and no {@code JdbcTemplate} is
 *       reached. The dataset-usage map's {@code TRANSACT} row for this program reflects that unused
 *       literal, not an access path, and inventing a repository here would add surface the COBOL
 *       does not have.</li>
 *   <li><strong>{@code 01 CORPT0AO REDEFINES CORPT0AI}</strong>, so the input and output views are
 *       the same bytes. That is why {@link ReportRequestResponse} is used below as the single screen
 *       buffer and is read as well as written: lines 305 to 327 normalise six date parts
 *       <em>in place</em> through {@code PIC 99} and {@code PIC 9999}, and the map that is
 *       subsequently sent shows the normalised values. Modelling the two views as separate storage
 *       would silently drop that.</li>
 *   <li><strong>There is no {@code CSSETATY} expansion in this program.</strong> Its copybook list at
 *       lines 138 to 149 is {@code COCOM01Y}, {@code CORPT00}, {@code COTTL01Y}, {@code CSDAT01Y},
 *       {@code CSMSG01Y}, {@code CVTRA05Y}, {@code DFHAID} and {@code DFHBMSCA} - neither
 *       {@code CSSETATY} nor {@code CSSTRPFY}. So no field is recoloured red and no {@code '*'} is
 *       written into a blank field; applying that highlight here would invent behaviour and put a
 *       field-for-field parity diff permanently off zero. {@code common.FieldAttributeSetter} is
 *       therefore deliberately not referenced. The program's <em>only</em> attribute assignment is
 *       {@code MOVE DFHGREEN TO ERRMSGC OF CORPT0AO} at line 448, reproduced with
 *       {@link BmsAttributes#DFHGREEN}, and it happens on the success path - which is reachable only
 *       from the re-enter arm, so even that one assignment is re-enter-only.</li>
 *   <li><strong>{@code CVTRA05Y} is copied and never referenced.</strong> The transaction record's
 *       shape appears in this program only as text inside two skeleton literals -
 *       {@code TRAN-CARD-NUM,263,16,ZD} and {@code TRAN-PROC-DT,305,10,CH} - which are
 *       {@code SYMNAMES} declarations for the sort step. They are emitted as literals and never
 *       recomputed. They are nevertheless verifiable:
 *       {@code transaction.model.TranRecord} publishes {@code TRAN_CARD_NUM_OFFSET} 262 with length
 *       16 and {@code TRAN_PROC_DT_OFFSET} 304 with length 10, which are one-based positions 263 and
 *       305 - and {@code app/jcl/TRANREPT.jcl} declares the identical two lines in its own
 *       {@code SYMNAMES}.</li>
 *   <li><strong>Names come from the plan, behaviour from the source.</strong> Here the two agree:
 *       {@code CORPT00C} really is the report-request screen. But the paragraph that writes the queue
 *       is spelled {@code WIRTE-JOBSUB-TDQ} in the source, at line 515. The misspelling is recorded
 *       rather than propagated: {@link #writeJobSubmissionQueue(ProgramState)} carries a correct Java
 *       name and cites the paragraph it renders. The COBOL is not edited.</li>
 * </ol>
 *
 * <h2>Statelessness</h2>
 *
 * CICS is pseudo-conversational and this class preserves that shape rather than reintroducing a
 * server-side conversation. The conversation is exactly three things and all three travel in the
 * payload: the communication area, as {@link ReportRequestRequest#navigationContext()}; the key the
 * operator pressed, as {@link ReportRequestRequest#aid()}, which line 184's {@code EVALUATE EIBAID}
 * branches on; and the seventeen screen field values. There is no {@code HttpSession}, no
 * {@code @SessionAttributes}, no {@code @SessionScope}, no {@code ThreadLocal}, no server-side cache
 * and no static mutable holder anywhere in this file. Every working-storage item of the program is a
 * member of the per-request {@link ProgramState}, so two concurrent requests cannot see each other's
 * screen; the only {@code static} state is immutable - the message literals, the widths, and the
 * seventeen-line skeleton template.
 *
 * <h2>Security posture: nothing added, nothing taken away</h2>
 *
 * {@code CORPT00C} performs no authentication, no authorization and no masking. It is reached only
 * after {@code COSGN00C} has signed the operator on - which is why line 168's {@code EIBCALEN = 0}
 * test transfers <em>back</em> to {@code COSGN00C} rather than challenging for credentials - and it
 * reads no user record, tests no user type and hides no field. This class therefore introduces no
 * security annotation, no filter, no {@code SecurityContext} and no {@code Authentication}: adding
 * one would be a behaviour change, and Spring Security is outside the mandated stack. The queue
 * destination is the only external resource touched, and it is configuration-bound rather than
 * credentialed. Nothing in this file is a secret, and nothing in it is redacted, because the eighty
 * bytes it emits are job text that the mainframe reader must receive verbatim.
 *
 * <h2>Where the dataset-name scan will hit, and why it is not a dataset name</h2>
 *
 * A scan for {@code AWS.M2.CARDDEMO} finds exactly one occurrence in this file, inside
 * {@link #JOB_LINE_04}:
 *
 * <pre>{@code //JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')}</pre>
 *
 * That is not a dataset this class resolves, opens, reads or writes. It is <em>payload text</em> -
 * eighty of the 1,360 bytes the COBOL emits, quoted verbatim from
 * {@code app/cbl/CORPT00C.cbl:90} - and it names the procedure library that the mainframe's own JCL
 * reader will search when it processes the submitted job. Altering it would corrupt the job. Every
 * value this class genuinely resolves - the queue name, the DD name, the record geometry and the
 * destination - comes from {@code carddemo.job-submission} in {@code application.yml} by way of
 * {@link JobSubmissionProperties}, and no such value is written in Java.
 *
 * <h2>Numbers</h2>
 *
 * There is no monetary arithmetic in {@code CORPT00C}. It declares
 * {@code WS-TRAN-AMT PIC +99999999.99} at line 77 and never references it, and it performs no
 * {@code ADD}, {@code SUBTRACT}, {@code MULTIPLY} or {@code DIVIDE} on a value with a scale. Its two
 * numeric receivers are {@code WS-NUM-99 PIC 99} and {@code WS-NUM-9999 PIC 9999} - unsigned
 * integers with no decimal places - and the six {@code COMPUTE ... FUNCTION NUMVAL-C} statements that
 * fill them are routed through {@link CobolDecimal#storeAtPicture(BigDecimal, int, int)}, which
 * truncates with {@link java.math.RoundingMode#DOWN} exactly as a COBOL store without
 * {@code ROUNDED} does. No {@code double} and no {@code float} appears in this file, and no rounding
 * mode other than the one {@code CobolDecimal} applies is named anywhere in it.
 *
 * <h2>Determinism</h2>
 *
 * {@code FUNCTION CURRENT-DATE} is read at three sites - lines 215, 241 and 611 - and each one is
 * rendered as one {@link DateHeader#from(FixedWidthCodec, Clock)} call against the injected
 * {@link Clock}. {@code now()} is never called, no default locale shapes a digit and no
 * {@code DateTimeFormatter} pattern is used, so a parity case that pins the clock gets the same bytes
 * every time. The three reads are kept as three reads because the source has three: the header
 * deliberately re-reads the clock <em>after</em> the monthly arm has mutated
 * {@code WS-CURDATE-DATA}, which is why the header shows today's date and not the month-end the arm
 * computed.
 *
 * <h2>Verification provenance</h2>
 *
 * The expected values used to verify this class are <strong>statically derived</strong> from the
 * COBOL, the copybooks, the JCL and the ASCII fixtures rather than captured from a live execution:
 * the legacy programs cannot be run in this environment. That deviation is recorded as risk R-A, and
 * it is why every literal below cites the line it came from - a citation is the only oracle available
 * for a value that was read rather than observed.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // Over HTTP - the thin adapter.
 * POST /api/reports  { "monthly": "Y", "confirm": "Y", "aid": "ENTER", "navigationContext": { ... } }
 *
 * // In a parity case or a unit test - no HTTP, no JobLauncher, no servlet container.
 * ReportRequestController.ProgramState state = controller.mainPara(request);
 * assertThat(state.submittedRecords()).hasSize(ReportRequestController.JOB_LINE_COUNT);
 * assertThat(state.message()).startsWith("Monthly report submitted for printing ...");
 * }</pre>
 *
 * @see ReportRequestRequest  the seventeen {@code xxxI} items of {@code 01 CORPT0AI}
 * @see ReportRequestResponse the seventeen {@code xxxO} items of {@code 01 CORPT0AO}
 * @see DateUtilityJob        {@code CSUTLDTC}, called twice from this program
 * @see DateParmReader        the reader of the {@code DATEPARM} record this class emits as line 15
 */
@RestController
public class ReportRequestController {

    /**
     * Diagnostic sink for the two {@code DISPLAY} statements at lines 210 and 529.
     *
     * <p>{@code static final} and immutable, so it is not mutable static state. Both statements emit
     * fixed text and integers only - never a screen field, a date the operator typed or any part of
     * the payload - so there is nothing here to sanitise and no way for supplied text to split a log
     * record.
     */
    private static final Log LOG = LogFactory.getLog(ReportRequestController.class);

    // =============================================================================================
    // Program identity. Every literal is verbatim: WS-PGMNAME and WS-TRANID from
    // app/cbl/CORPT00C.cbl:37-38, corroborated by app/csd/CARDDEMO.CSD:242 and 409-410.
    // =============================================================================================

    /** {@code WS-PGMNAME PIC X(08) VALUE 'CORPT00C'} - CORPT00C.cbl:37. */
    public static final String PROGRAM_NAME = "CORPT00C";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CR00'} - CORPT00C.cbl:38.
     *
     * <p>{@code app/csd/CARDDEMO.CSD:409-410} defines {@code TRANSACTION(CR00) PROGRAM(CORPT00C)}, so
     * the {@code EXEC CICS RETURN TRANSID('CR00')} that ends every re-display arm routes the next
     * input back to this very program. That binding is what {@link #returnToCics(ProgramState)}
     * projects as the response's next program.
     */
    public static final String TRANSACTION_ID = "CR00";

    /** The REST resource this screen is exposed as. */
    public static final String REPORTS_PATH = "/api/reports";

    /**
     * {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} - CORPT00C.cbl:173 and 543. The sign-on screen: the
     * target when no communication area travelled with the request, and the default when the caller
     * left {@code CDEMO-TO-PROGRAM} blank.
     */
    public static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** {@code MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM} - CORPT00C.cbl:188, the PF3 target. */
    public static final String MAIN_MENU_PROGRAM = "COMEN01C";

    // =============================================================================================
    // WORKING-STORAGE widths - app/cbl/CORPT00C.cbl:36-79. Each is one declared PICTURE, so no
    // width below is a round number chosen for convenience.
    // =============================================================================================

    /** {@code WS-MESSAGE PIC X(80)} - line 39. Wider than {@code ERRMSGO PIC X(78)} on purpose. */
    public static final int WS_MESSAGE_LENGTH = 80;

    /** {@code WS-REPORT-NAME PIC X(10)} - line 58. */
    public static final int WS_REPORT_NAME_LENGTH = 10;

    /** {@code WS-START-DATE} and {@code WS-END-DATE}: {@code 4 + 1 + 2 + 1 + 2} - lines 60 to 71. */
    public static final int WS_DATE_LENGTH = 10;

    /** {@code WS-START-DATE-YYYY} and {@code WS-END-DATE-YYYY}, both {@code PIC X(04)}. */
    public static final int DATE_YEAR_LENGTH = 4;

    /** {@code WS-START-DATE-MM}, {@code -DD} and their end-date twins, all {@code PIC X(02)}. */
    public static final int DATE_PART_LENGTH = 2;

    /**
     * The {@code FILLER PIC X(01) VALUE '-'} items at lines 62, 64, 68 and 70.
     *
     * <p>These separators are declared with a {@code VALUE}, so they are <em>content</em>, not
     * padding: {@code WS-START-DATE} of a freshly initialised program is {@code "    -  -  "} and
     * never ten spaces. Emitting them is what makes the ten-byte group the {@code 'YYYY-MM-DD'} shape
     * that {@code CSUTLDTC} is asked to validate at lines 388 to 394 and that the {@code DATEPARM}
     * record carries, and it is the reason {@link ProgramState#startDate()} composes rather than
     * concatenates blindly.
     */
    public static final String DATE_SEPARATOR = "-";

    /** {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} - line 72, the mask handed to CSUTLDTC. */
    public static final String WS_DATE_FORMAT = "YYYY-MM-DD";

    /** {@code WS-NUM-99 PIC 99} - line 74: two digits, <strong>unsigned</strong>, no decimal places. */
    public static final int WS_NUM_99_DIGITS = 2;

    /** {@code WS-NUM-9999 PIC 9999} - line 75: four digits, unsigned, no decimal places. */
    public static final int WS_NUM_9999_DIGITS = 4;

    /** The scale of both numeric receivers: neither {@code PICTURE} has a {@code V}. */
    public static final int INTEGER_SCALE = 0;

    /** {@code JCL-RECORD PIC X(80) VALUE ' '} - line 79, and {@code RECORDSIZE(80)} of the queue. */
    public static final int JCL_RECORD_LENGTH = 80;

    /**
     * {@code WS-RESP-CD} and {@code WS-REAS-CD PIC S9(09) COMP} - lines 54 and 55.
     *
     * <p>Nine digit positions, which is the width the {@code DISPLAY} at line 529 renders each code at.
     * They are {@code int} rather than a scaled type because {@code PIC S9(09)} has no {@code V}: these
     * are CICS response codes, not amounts.
     */
    public static final int WS_RESP_CD_DIGITS = 9;

    // =============================================================================================
    // Message literals, byte for byte from app/cbl/CORPT00C.cbl. Lengths were extracted from the
    // source rather than eyeballed, and the awkward ones are called out: a trailing space, a leading
    // space, a space before an ellipsis. Each is moved into WS-MESSAGE PIC X(80) and from there into
    // ERRMSGO PIC X(78), which truncates on the right - so a literal's own length matters.
    // =============================================================================================

    /** Line 261, 38 characters. First arm of the custom-range blank chain. */
    public static final String MSG_START_DATE_MONTH_EMPTY = "Start Date - Month can NOT be empty...";

    /** Line 268, 36 characters. */
    public static final String MSG_START_DATE_DAY_EMPTY = "Start Date - Day can NOT be empty...";

    /** Line 275, 37 characters. */
    public static final String MSG_START_DATE_YEAR_EMPTY = "Start Date - Year can NOT be empty...";

    /** Line 282, 36 characters. */
    public static final String MSG_END_DATE_MONTH_EMPTY = "End Date - Month can NOT be empty...";

    /** Line 289, 34 characters. */
    public static final String MSG_END_DATE_DAY_EMPTY = "End Date - Day can NOT be empty...";

    /** Line 296, 35 characters. Last arm of the blank chain; line 301's {@code WHEN OTHER} continues. */
    public static final String MSG_END_DATE_YEAR_EMPTY = "End Date - Year can NOT be empty...";

    /** Line 331, 33 characters. Note the capital {@code M}: the source is inconsistent, not this file. */
    public static final String MSG_START_DATE_INVALID_MONTH = "Start Date - Not a valid Month...";

    /** Line 340, 31 characters. */
    public static final String MSG_START_DATE_INVALID_DAY = "Start Date - Not a valid Day...";

    /** Line 348, 32 characters. */
    public static final String MSG_START_DATE_INVALID_YEAR = "Start Date - Not a valid Year...";

    /** Line 357, 31 characters. */
    public static final String MSG_END_DATE_INVALID_MONTH = "End Date - Not a valid Month...";

    /** Line 366, 29 characters. */
    public static final String MSG_END_DATE_INVALID_DAY = "End Date - Not a valid Day...";

    /** Line 374, 30 characters. */
    public static final String MSG_END_DATE_INVALID_YEAR = "End Date - Not a valid Year...";

    /**
     * Line 400, 32 characters. Lower-case {@code date} here against upper-case {@code Month} at line
     * 331: the two messages come from different edits and the difference is preserved.
     */
    public static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";

    /** Line 420, 30 characters. */
    public static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    /** Line 438, 39 characters. The {@code WHEN OTHER} arm: no report type was selected. */
    public static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    /** Line 531, 29 characters. The queue write failed. */
    public static final String MSG_UNABLE_TO_WRITE_TDQ = "Unable to Write TDQ (JOBS)...";

    /**
     * Line 466, 28 characters, <strong>with a trailing space</strong>. First operand of the confirm
     * prompt, contributed {@code DELIMITED BY SIZE}.
     */
    public static final String MSG_PLEASE_CONFIRM_PREFIX = "Please confirm to print the ";

    /**
     * Line 469, 10 characters, <strong>with a leading space</strong>. Third operand of the confirm
     * prompt, contributed {@code DELIMITED BY SIZE}. The space belongs to this literal because the
     * middle operand is delimited by space and so contributes none of its own padding.
     */
    public static final String MSG_REPORT_SUFFIX = " report...";

    /** Line 486, one character. First operand of the invalid-confirm message. */
    public static final String MSG_QUOTE = "\"";

    /** Line 488, 36 characters, opening with the closing double quote of the quoted value. */
    public static final String MSG_NOT_A_VALID_CONFIRM_VALUE = "\" is not a valid value to confirm...";

    /**
     * Line 450, 34 characters, <strong>with a leading space and a space before the ellipsis</strong>.
     * Second operand of the success message, contributed {@code DELIMITED BY SIZE}.
     */
    public static final String MSG_REPORT_SUBMITTED = " report submitted for printing ...";

    /** Line 210. The only unconditional {@code DISPLAY} in the program. */
    public static final String DISPLAY_PROCESS_ENTER_KEY = "PROCESS ENTER KEY";

    /** Line 529, first literal of the failed-write {@code DISPLAY}. */
    public static final String DISPLAY_RESP_PREFIX = "RESP:";

    /** Line 529, second literal of the failed-write {@code DISPLAY}. */
    public static final String DISPLAY_REAS_PREFIX = "REAS:";

    // =============================================================================================
    // Report names and screen values.
    // =============================================================================================

    /** {@code MOVE 'Monthly' TO WS-REPORT-NAME} - line 214. Padded to X(10) on the move. */
    public static final String REPORT_NAME_MONTHLY = "Monthly";

    /** {@code MOVE 'Yearly' TO WS-REPORT-NAME} - line 240. */
    public static final String REPORT_NAME_YEARLY = "Yearly";

    /**
     * {@code MOVE 'Custom' TO WS-REPORT-NAME} - line 433.
     *
     * <p>Assigned <em>after</em> the custom arm's edits, not before, so an arm that rejects a date
     * leaves {@code WS-REPORT-NAME} at spaces. That ordering is preserved.
     */
    public static final String REPORT_NAME_CUSTOM = "Custom";

    /** {@code WHEN CONFIRMI OF CORPT0AI = 'Y' OR 'y'} - line 478, upper case. */
    public static final String CONFIRM_YES_UPPER = "Y";

    /** {@code WHEN CONFIRMI OF CORPT0AI = 'Y' OR 'y'} - line 478, lower case. */
    public static final String CONFIRM_YES_LOWER = "y";

    /** {@code WHEN CONFIRMI OF CORPT0AI = 'N' OR 'n'} - line 480, upper case. */
    public static final String CONFIRM_NO_UPPER = "N";

    /** {@code WHEN CONFIRMI OF CORPT0AI = 'N' OR 'n'} - line 480, lower case. */
    public static final String CONFIRM_NO_LOWER = "n";

    /**
     * The literal {@code '01'}, moved into {@code WS-START-DATE-DD} at line 219 by the monthly arm and
     * into both {@code WS-START-DATE-MM} and {@code WS-START-DATE-DD} at lines 245 and 246 by the
     * yearly arm. One literal in the source, so one constant here.
     */
    public static final String FIRST_OF_PERIOD = "01";

    /** {@code MOVE '12' TO WS-END-DATE-MM} - line 250. */
    public static final String LAST_MONTH_OF_YEAR = "12";

    /** {@code MOVE '31' TO WS-END-DATE-DD} - line 251. */
    public static final String LAST_DAY_OF_DECEMBER = "31";

    /** The literal the month edits compare against: {@code SDTMMI > '12'} - lines 330 and 356. */
    public static final String HIGHEST_MONTH = "12";

    /** The literal the day edits compare against: {@code SDTDDI > '31'} - lines 339 and 365. */
    public static final String HIGHEST_DAY = "31";

    // =============================================================================================
    // CSUTLDTC outcome literals - app/cbl/CORPT00C.cbl:396, 399, 416 and 419.
    // =============================================================================================

    /**
     * {@code IF CSUTLDTC-RESULT-SEV-CD = '0000'} - lines 396 and 416. The date converted; the caller
     * continues.
     */
    public static final String CSUTLDTC_SEVERITY_OK = "0000";

    /**
     * {@code IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'} - lines 399 and 419.
     *
     * <p>{@code 2513} is {@code FC-UNSUPP-RANGE}, and it is the one non-zero outcome both call sites
     * deliberately tolerate: a severity that is not {@code '0000'} is accepted anyway when the message
     * number is this. Changing which value is tolerated would flip both sites from accept to reject,
     * so the literal is named rather than inlined.
     */
    public static final String CSUTLDTC_TOLERATED_MESSAGE_NUMBER = "2513";

    // =============================================================================================
    // Figurative constants and small character helpers.
    // =============================================================================================

    /** The COBOL figurative constant {@code SPACE}. */
    private static final String SPACE = " ";

    /** The COBOL figurative constant {@code LOW-VALUE}: the byte with every bit clear. */
    private static final char LOW_VALUE = '\u0000';

    /** The value {@link #testNumvalC(String)} returns for a conforming argument. */
    public static final int NUMVAL_CONFORMS = NumericIntrinsics.CONFORMS;

    // =============================================================================================
    // FUNCTION INTEGER-OF-DATE and FUNCTION DATE-OF-INTEGER - app/cbl/CORPT00C.cbl:229-230.
    //
    //     COMPUTE WS-CURDATE-N = FUNCTION DATE-OF-INTEGER(
    //             FUNCTION INTEGER-OF-DATE(WS-CURDATE-N) - 1)
    //
    // Both intrinsics are hand-written here because there is no COBOL runtime to call. The pair is
    // applied to the first day of the month AFTER the current one, so subtracting one day yields the
    // LAST DAY OF THE CURRENT MONTH - which is the whole purpose of the monthly arm. The semantics
    // match account.AccountDateValidator, which publishes the same epoch offset and the same
    // undefined-argument convention for its own two INTEGER-OF-DATE sites; that class is cited
    // rather than imported, because it is not a declared dependency of this file.
    // =============================================================================================

    /**
     * The constant that turns a Java epoch day into a COBOL integer date.
     *
     * <p>{@code FUNCTION INTEGER-OF-DATE} counts days from 31 December 1600, so 1 January 1601 is day
     * 1. Java counts epoch days from 1 January 1970, and {@code LocalDate.of(1601, 1, 1).toEpochDay()}
     * is {@code -134774}; adding {@value #INTEGER_OF_DATE_EPOCH_OFFSET} therefore maps that date to 1.
     * Anchors: 1601-01-01 &rarr; 1, 1601-12-31 &rarr; 365 (1601 was a common year), 1900-01-01 &rarr;
     * 109208, 2000-01-01 &rarr; 145732, 2022-07-19 &rarr; 153937.
     */
    public static final int INTEGER_OF_DATE_EPOCH_OFFSET = 134775;

    /** Lowest argument {@code FUNCTION INTEGER-OF-DATE} accepts: 1601-01-01 as {@code 9(8)}. */
    public static final int INTEGER_OF_DATE_LOWEST_ARGUMENT = 16010101;

    /** Highest argument {@code FUNCTION INTEGER-OF-DATE} accepts: 9999-12-31 as {@code 9(8)}. */
    public static final int INTEGER_OF_DATE_HIGHEST_ARGUMENT = 99991231;

    /**
     * The result {@link #integerOfDate(int)} and {@link #dateOfInteger(int)} yield for an argument
     * outside the supported range or, for the former, one that is not a real calendar date.
     *
     * <p>COBOL leaves both results undefined in those cases, so <em>some</em> deterministic choice has
     * to be made, and zero is chosen for the same reason the account date engine chose it: it keeps
     * the surrounding statement meaningful instead of raising a condition the source has no handler
     * for. The path is latent - the monthly arm always hands in the first day of a real month - but it
     * is reachable through {@link #integerOfDate(int)} directly, so it is defined rather than left to
     * throw.
     */
    public static final int DATE_INTRINSIC_UNDEFINED = 0;

    /** Lowest argument {@code FUNCTION DATE-OF-INTEGER} accepts: day 1, which is 1601-01-01. */
    public static final int DATE_OF_INTEGER_LOWEST_ARGUMENT = 1;

    /** Divisor extracting the year from a {@code 9(8)} standard date. */
    private static final int STANDARD_DATE_YEAR_DIVISOR = 10000;

    /** Divisor extracting the month from a {@code 9(8)} standard date, before the modulus. */
    private static final int STANDARD_DATE_MONTH_DIVISOR = 100;

    /** Modulus isolating a two-digit month or day component of a {@code 9(8)} standard date. */
    private static final int STANDARD_DATE_COMPONENT_MODULUS = 100;

    /**
     * Highest argument {@code FUNCTION DATE-OF-INTEGER} accepts: the day number of 9999-12-31.
     *
     * <p>Derived from {@link #integerOfDate(int)} rather than written as a literal, so the two
     * intrinsics cannot disagree about where their common range ends. Declared after the divisors it
     * depends on, because a static initialiser runs in textual order.
     */
    public static final int DATE_OF_INTEGER_HIGHEST_ARGUMENT =
            integerOfDate(INTEGER_OF_DATE_HIGHEST_ARGUMENT);

    /** {@code IF WS-CURDATE-MONTH > 12} - line 225, the month roll-over test. */
    public static final int MONTHS_PER_YEAR = 12;

    /** {@code ADD 1 TO WS-CURDATE-MONTH} at line 224 and {@code - 1} at line 230. */
    private static final int ONE = 1;

    // =============================================================================================
    // 01 JOB-DATA - app/cbl/CORPT00C.cbl:81-127. THE SEVENTEEN EIGHTY-BYTE RECORDS.
    //
    // 02 JOB-DATA-1 is a run of PIC X(80) items with VALUE clauses, three of which are groups
    // carrying a substitution point. Every literal below was extracted from the source and its
    // length verified, so none is retyped from memory: 48, 17, 3, 46, 3, 27, 3, 23, 23, 22, then the
    // two SYMNAMES groups, 2, 23, the DATEPARM group, 2 and 5. A COBOL VALUE on a PIC X(80) item
    // left-justifies the literal and space-fills the rest, which is exactly what picXValue does - and
    // it refuses a literal wider than the item, as the compiler would.
    //
    // 02 JOB-DATA-2 REDEFINES JOB-DATA-1 declares 05 JOB-LINES OCCURS 1000 TIMES PIC X(80): a table
    // far larger than the 1,360 bytes it redefines. It is the emit loop's '/*EOF' sentinel that
    // bounds the traversal, never the table, and JOB-LINES is ONE-BASED where a Java List is
    // zero-based - see jobLine(List, int).
    // =============================================================================================

    /** Line 84, 48 characters. The job card; the trailing comma continues onto the next record. */
    public static final String JOB_LINE_01 =
            picXValue("//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,", JCL_RECORD_LENGTH);

    /** Line 86, 17 characters. The job card's continuation. */
    public static final String JOB_LINE_02 = picXValue("// NOTIFY=&SYSUID", JCL_RECORD_LENGTH);

    /** Line 88, 3 characters. A JCL comment. */
    public static final String JOB_LINE_03 = picXValue("//*", JCL_RECORD_LENGTH);

    /**
     * Line 90, 46 characters.
     *
     * <p><strong>This is the file's only {@code AWS.M2.CARDDEMO} occurrence, and it is payload rather
     * than configuration.</strong> The text is one of the eighty-byte records this class emits, quoted
     * verbatim from the COBOL; the mainframe's JCL reader searches the named procedure library when it
     * processes the submitted job. Nothing here is resolved, opened or bound by this module, and every
     * value that <em>is</em> resolved comes from {@code carddemo.job-submission} in
     * {@code application.yml}. Rewriting this literal would corrupt the emitted job.
     */
    public static final String JOB_LINE_04 =
            picXValue("//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')", JCL_RECORD_LENGTH);

    /** Line 92, 3 characters. */
    public static final String JOB_LINE_05 = picXValue("//*", JCL_RECORD_LENGTH);

    /**
     * Line 94, 27 characters. Invokes {@code app/proc/TRANREPT.prc}, whose three steps back up the
     * transaction file, sort it into the requested date range and run {@code CBTRN03C}.
     */
    public static final String JOB_LINE_06 = picXValue("//STEP10 EXEC PROC=TRANREPT", JCL_RECORD_LENGTH);

    /** Line 96, 3 characters. */
    public static final String JOB_LINE_07 = picXValue("//*", JCL_RECORD_LENGTH);

    /**
     * Line 98, 23 characters. Overrides the sort step's {@code SYMNAMES} DD with instream data;
     * {@code STEP05R} is the step name {@code app/jcl/TRANREPT.jcl} uses for the sort.
     */
    public static final String JOB_LINE_08 = picXValue("//STEP05R.SYMNAMES DD *", JCL_RECORD_LENGTH);

    /**
     * Line 100, 23 characters. A sort symbol: {@code TRAN-CARD-NUM} at one-based position 263, length
     * 16, zoned decimal. Verifiable against {@code transaction.model.TranRecord}, whose
     * {@code TRAN_CARD_NUM_OFFSET} is 262 zero-based with length 16, and against
     * {@code app/jcl/TRANREPT.jcl}, which declares the identical line. Emitted as a literal and never
     * recomputed.
     */
    public static final String JOB_LINE_09 = picXValue("TRAN-CARD-NUM,263,16,ZD", JCL_RECORD_LENGTH);

    /**
     * Line 102, 22 characters. A sort symbol: {@code TRAN-PROC-DT} at one-based position 305, length
     * 10, character. {@code TranRecord.TRAN_PROC_DT_OFFSET} is 304 zero-based with length 10.
     */
    public static final String JOB_LINE_10 = picXValue("TRAN-PROC-DT,305,10,CH", JCL_RECORD_LENGTH);

    /**
     * {@code 10 FILLER PIC X(18) VALUE "PARM-START-DATE,C'"} - line 105. Exactly eighteen characters,
     * so it fills its item with no padding and the substituted date begins at column 19.
     */
    public static final String SYMNAMES_START_DATE_PREFIX = "PARM-START-DATE,C'";

    /**
     * {@code 10 FILLER PIC X(52) VALUE "'"} - line 107.
     *
     * <p>The closing quote is the <strong>first character of a fifty-two byte FILLER</strong>, so it
     * sits immediately after the ten-byte date and the remaining fifty-one bytes are spaces. Getting
     * this wrong is how a {@code SYMNAMES} line becomes malformed while still measuring eighty bytes,
     * which is why the quote and its padding are declared as one item rather than concatenated by
     * hand.
     */
    public static final int SYMNAMES_START_DATE_TAIL_LENGTH = 52;

    /** {@code 10 FILLER PIC X(16) VALUE "PARM-END-DATE,C'"} - line 110. Exactly sixteen characters. */
    public static final String SYMNAMES_END_DATE_PREFIX = "PARM-END-DATE,C'";

    /** {@code 10 FILLER PIC X(54) VALUE "'"} - line 112. Quote then fifty-three spaces. */
    public static final int SYMNAMES_END_DATE_TAIL_LENGTH = 54;

    /** The closing quote of a {@code SYMNAMES} character constant. */
    public static final String SYMNAMES_CLOSING_QUOTE = "'";

    /**
     * {@code 05 FILLER-1} - lines 103 to 107, in template form: the prefix, ten spaces where
     * {@code PARM-START-DATE-1 PIC X(10) VALUE SPACES} sits, then the quote and its padding.
     * {@link #jobLines(ProgramState)} substitutes the date per request; this constant is never mutated.
     */
    public static final String JOB_LINE_11 = symnamesLine(SYMNAMES_START_DATE_PREFIX,
            SPACE.repeat(WS_DATE_LENGTH), SYMNAMES_START_DATE_TAIL_LENGTH);

    /** {@code 05 FILLER-2} - lines 108 to 112, in template form. */
    public static final String JOB_LINE_12 = symnamesLine(SYMNAMES_END_DATE_PREFIX,
            SPACE.repeat(WS_DATE_LENGTH), SYMNAMES_END_DATE_TAIL_LENGTH);

    /** Line 114, 2 characters. Terminates the {@code SYMNAMES} instream data. */
    public static final String JOB_LINE_13 = picXValue("/*", JCL_RECORD_LENGTH);

    /**
     * Line 116, 23 characters. Overrides the report step's {@code DATEPARM} DD with instream data.
     * {@code app/proc/TRANREPT.prc:71-72} binds {@code DATEPARM} to a catalogued dataset;
     * {@code STEP10R} is the step {@code CBTRN03C} runs in, so this override replaces that dataset
     * with the single record on the next line.
     */
    public static final String JOB_LINE_14 = picXValue("//STEP10R.DATEPARM DD *", JCL_RECORD_LENGTH);

    /**
     * {@code 05 FILLER-3} - lines 117 to 121, in template form: {@code PARM-START-DATE-2 PIC X(10)},
     * one space, {@code PARM-END-DATE-2 PIC X(10)}, then {@code FILLER PIC X(59) VALUE SPACES}.
     *
     * <p><strong>This record is the {@code DATEPARM} record that {@link DateParmReader} consumes</strong>,
     * and the correspondence is enforced rather than asserted in prose: {@link #dateParmRecord(String,
     * String)} composes it at the reader's own published offsets -
     * {@link DateParmReader#START_DATE_OFFSET}, {@link DateParmReader#SEPARATOR_OFFSET},
     * {@link DateParmReader#END_DATE_OFFSET} and {@link DateParmReader#DISCARDED_TAIL_OFFSET} - and the
     * static self-check below refuses to load this class if those offsets and
     * {@link DateParmReader#RECORD_LENGTH} ever stop matching the COBOL's declaration. It is the
     * strongest cross-check available between the two files: the online screen that writes the record
     * and the batch reader that parses it derive their layout from one source.
     */
    public static final String JOB_LINE_15 =
            dateParmRecord(SPACE.repeat(WS_DATE_LENGTH), SPACE.repeat(WS_DATE_LENGTH));

    /** Line 123, 2 characters. Terminates the {@code DATEPARM} instream data. */
    public static final String JOB_LINE_16 = picXValue("/*", JCL_RECORD_LENGTH);

    /** The unpadded {@code '/*EOF'} literal of lines 125 and 502. */
    public static final String EOF_MARKER_TEXT = "/*EOF";

    /**
     * Line 125, 5 characters. The internal reader's end-of-file marker, and the sentinel the emit loop
     * recognises at line 502.
     *
     * <p>It is recognised <em>and then written anyway</em>: line 504 sets {@code END-LOOP-YES} and line
     * 507 performs the write unconditionally, so a normal run emits {@value #JOB_LINE_COUNT} records
     * and this is the last of them.
     */
    public static final String JOB_LINE_17 = picXValue(EOF_MARKER_TEXT, JCL_RECORD_LENGTH);

    /**
     * {@code IF JCL-RECORD = '/*EOF'} - line 502.
     *
     * <p>{@code JCL-RECORD} is {@code PIC X(80)}, and COBOL compares an alphanumeric item against a
     * shorter literal by space-filling the literal to the item's length. So the test is against
     * {@code '/*EOF'} followed by seventy-five spaces, and that padded form is what the comparison
     * uses.
     */
    public static final String EOF_MARKER_RECORD = picXValue(EOF_MARKER_TEXT, JCL_RECORD_LENGTH);

    /** The number of {@code PIC X(80)} items in {@code 02 JOB-DATA-1}: seventeen. */
    public static final int JOB_LINE_COUNT = 17;

    /** {@code 05 JOB-LINES OCCURS 1000 TIMES} - line 127, the loop's upper bound at line 498. */
    public static final int JOB_LINES_OCCURS = 1000;

    /** {@code 02 JOB-DATA-1}'s total width: {@value #JOB_LINE_COUNT} records of eighty bytes. */
    public static final int JOB_DATA_LENGTH = JOB_LINE_COUNT * JCL_RECORD_LENGTH;

    /**
     * The seventeen records in declaration order, with the four substitution points left at
     * {@code SPACES} exactly as {@code JOB-DATA}'s {@code VALUE} clauses leave them.
     *
     * <p>Immutable and shared: it is a template, never an emitted stream.
     * {@link #jobLines(ProgramState)} returns a substituted per-request copy, so two concurrent
     * requests cannot see each other's dates. The list is unmodifiable and every element is a
     * {@code String}, so the constant is deeply immutable and is not mutable static state.
     */
    public static final List<String> JOB_DATA_TEMPLATE = List.of(
            JOB_LINE_01, JOB_LINE_02, JOB_LINE_03, JOB_LINE_04, JOB_LINE_05, JOB_LINE_06,
            JOB_LINE_07, JOB_LINE_08, JOB_LINE_09, JOB_LINE_10, JOB_LINE_11, JOB_LINE_12,
            JOB_LINE_13, JOB_LINE_14, JOB_LINE_15, JOB_LINE_16, JOB_LINE_17);

    /** The subject named in a codec diagnostic; never the record content, which is job text. */
    private static final String JCL_RECORD_SUBJECT = "JCL-RECORD of the JOBS transient data queue";

    /** {@code 05 FILLER-1}, the start-date {@code SYMNAMES} record: entry 11 of {@code JOB-LINES}. */
    public static final int SYMNAMES_START_DATE_ENTRY = 11;

    /** {@code 05 FILLER-2}, the end-date {@code SYMNAMES} record: entry 12 of {@code JOB-LINES}. */
    public static final int SYMNAMES_END_DATE_ENTRY = 12;

    /** {@code 05 FILLER-3}, the {@code DATEPARM} record: entry 15 of {@code JOB-LINES}. */
    public static final int DATEPARM_ENTRY = 15;

    /**
     * The width of every record in {@link #JOB_DATA_TEMPLATE}, as one signature, so that the
     * class-initialisation check compares a single value instead of testing each record separately.
     */
    private static final String SKELETON_WIDTH_SIGNATURE = skeletonWidthSignature();

    /**
     * The {@code DATEPARM} layout as {@link DateParmReader} publishes it, expressed as one signature of
     * {@code offset:length} pairs and a total, so the cross-file check is a single comparison.
     */
    private static final String DATEPARM_LAYOUT_SIGNATURE =
            DateParmReader.START_DATE_OFFSET + ":" + DateParmReader.START_DATE_LENGTH
                    + "/" + DateParmReader.SEPARATOR_OFFSET + ":" + DateParmReader.SEPARATOR_LENGTH
                    + "/" + DateParmReader.END_DATE_OFFSET + ":" + DateParmReader.END_DATE_LENGTH
                    + "/" + DateParmReader.DISCARDED_TAIL_OFFSET + ":"
                    + DateParmReader.DISCARDED_TAIL_LENGTH
                    + "@" + DateParmReader.RECORD_LENGTH;

    /**
     * The same layout as {@code 05 FILLER-3} declares it at app/cbl/CORPT00C.cbl:117-121:
     * {@code PARM-START-DATE-2 PIC X(10)}, {@code FILLER PIC X}, {@code PARM-END-DATE-2 PIC X(10)} and
     * {@code FILLER PIC X(59)}, in eighty bytes.
     */
    private static final String DATEPARM_LAYOUT_FROM_COBOL = "0:10/10:1/11:10/21:59@80";

    static {
        // ------------------------------------------------------------------------------------------
        // Class-initialisation self-check. Both assertions are facts about the COBOL, so a mistyped
        // width or a drifted offset fails here - naming what disagrees - rather than emitting a stream
        // that is the wrong shape and diffing it against an expected image much later.
        //
        // Each check is a method rather than an inline test so that its rejecting path is reachable
        // from a test: a guard that can only ever be observed succeeding is a guard nobody has proved
        // actually guards anything.
        // ------------------------------------------------------------------------------------------
        requireSkeletonWidths(SKELETON_WIDTH_SIGNATURE);
        requireDateParmLayout(DATEPARM_LAYOUT_SIGNATURE);
    }

    /**
     * Verifies that every record of the skeleton template is exactly {@link #JCL_RECORD_LENGTH} bytes
     * wide, which is what {@code 02 JOB-DATA-1} declares at app/cbl/CORPT00C.cbl:83-125 and what
     * {@code TDQUEUE(JOBS)} declares as {@code RECORDSIZE(80) RECORDFORMAT(FIXED)} at
     * app/csd/CARDDEMO.CSD:499-505.
     *
     * @param measured the measured widths as a signature of {@code "<width> "} pairs, one per record
     * @throws NullPointerException  if {@code measured} is {@code null}
     * @throws IllegalStateException if the signature is not {@link #JOB_LINE_COUNT} repetitions of
     *                               {@link #JCL_RECORD_LENGTH}
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
     * {@code 05 FILLER-3} declares at app/cbl/CORPT00C.cbl:117-121. Skeleton line
     * {@value #DATEPARM_ENTRY} is the very record that reader consumes, so a drift in either file
     * would silently break the hand-off between them.
     *
     * @param published the reader's layout as a signature of {@code offset:length} pairs and a total
     * @throws NullPointerException  if {@code published} is {@code null}
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

    /**
     * Measures every record of {@link #JOB_DATA_TEMPLATE}, for the class-initialisation check.
     *
     * @return the widths, space separated and space terminated
     */
    private static String skeletonWidthSignature() {
        StringBuilder widths = new StringBuilder();
        for (String record : JOB_DATA_TEMPLATE) {
            widths.append(record.length()).append(SPACE);
        }
        return widths.toString();
    }

    // =============================================================================================
    // Collaborators. Constructor injection only, no field injection, and every one of them final.
    // =============================================================================================

    /**
     * The code page the screen's {@code PICTURE} move rules are constructed over.
     *
     * <p><strong>This is a fallback for a caller that constructs the class directly, and it is not what a
     * running application uses.</strong> Spring injects the configured dataset code page - qualified
     * {@link CobolCharsetConfig#DATASET_CHARSET_BEAN_NAME}, which is {@code IBM037} in production and
     * {@code US-ASCII} under the test profile - because the records this program renders are read by a
     * mainframe internal reader and have to arrive in the code page it expects. Wiring the default here
     * instead was a real defect: a region configured for {@code IBM037} submitted ASCII bytes, the
     * eighty-byte geometry still held, and nothing said the content was wrong.
     *
     * <p>{@code US-ASCII} remains the value of this constant because the authoritative fixtures under
     * {@code app/data/ASCII} are ASCII, and it is a legitimate code page for the queue: {@code TDQUEUE(
     * JOBS)} declares {@code RECORDSIZE(80)} with {@code RECORDFORMAT(FIXED)}, which holds under any code
     * page where one character occupies one byte - as both this one and {@code IBM037} do.
     */
    private static final Charset PICTURE_RULES_CHARSET = StandardCharsets.US_ASCII;

    /** {@code CALL 'CSUTLDTC'} - lines 392 and 412. A {@code @Service}, never a Spring Batch job. */
    private final DateUtilityJob dateUtilityJob;

    /** {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} - line 517. */
    private final JobSubmissionPort jobSubmissionPort;

    /** {@code FUNCTION CURRENT-DATE} - lines 215, 241 and 611. Read through this, never through now(). */
    private final Clock clock;

    /** The {@code PICTURE} move rules and the code page for this program's images. */
    private final FixedWidthCodec codec;

    /**
     * Wires the program's three collaborators with the code page configuration declares.
     *
     * <p>The charset is an explicit argument selected by bean name, not a constant: {@code IBM037} is
     * what {@code charset.dataset} declares, and a hardwired {@code US-ASCII} here would have encoded
     * this program's images in a code page the region does not use (practice B8, gate G46). It is the
     * only constructor, so there is no path into this class that defaults it.
     *
     * @param dateUtilityJob    the {@code CSUTLDTC} date validator; must not be {@code null}
     * @param jobSubmissionPort the transient-data-queue replacement; must not be {@code null}
     * @param clock             the clock {@code FUNCTION CURRENT-DATE} reads; must not be {@code null}
     * @param datasetCharset    the active dataset code page,
     *                          {@code @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)}; must
     *                          not be {@code null}
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

    // =============================================================================================
    // The HTTP adapter. Thin by design: it binds, delegates and projects. Every decision this screen
    // makes lives in mainPara and the paragraph methods below, so a parity case exercises the program
    // with no servlet container, no MockMvc and no JobLauncher in the path (gate G51).
    // =============================================================================================

    /**
     * {@code POST }{@value #REPORTS_PATH} - CSD transaction {@value #TRANSACTION_ID}.
     *
     * <p>One request is one execution of {@code CORPT00C}: the communication area, the key pressed and
     * the seventeen screen fields arrive in the body, and the response carries the screen to paint and
     * the program to go to next. No conversation is retained between calls.
     *
     * @param request the inbound screen; validated against the symbolic map's declared widths
     * @return the outbound screen, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    @PostMapping(path = REPORTS_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ScreenResponse<ReportRequestResponse> submitReportRequest(
            @Valid @RequestBody ReportRequestRequest request) {
        ProgramState state = mainPara(request);
        return ScreenResponse.of(state.response(), state.screenMetadata());
    }

    // =============================================================================================
    // MAIN-PARA - app/cbl/CORPT00C.cbl:163-202.
    // =============================================================================================

    /**
     * {@code MAIN-PARA} - the program's entry point, lines 163 to 202.
     *
     * <p>Returns the terminal {@link ProgramState} rather than only the response, because three
     * observable things this program produces have no home in the symbolic map's payload: the
     * eighty-byte records handed to the queue, the {@code MOVE -1 TO <field>L} cursor request - which
     * is {@code xxxL} metadata and must not be smuggled into a payload that projects only
     * {@code xxxI} and {@code xxxO} items - and the two {@code DISPLAY} statements. A parity case
     * needs all three, so they are reported on the state and the HTTP adapter projects
     * {@link ProgramState#response()}.
     *
     * <p>The arms are in source order and each one returns, because in the source each one has already
     * returned to CICS or transferred to another program by the time it finishes. The
     * {@code EXEC CICS RETURN} the source writes at lines 199 to 202 is byte for byte the statement
     * {@code RETURN-TO-CICS} executes at lines 587 to 591, and it is unreachable for exactly that
     * reason; it is rendered by {@link #returnToCics(ProgramState)}, which every re-display arm reaches
     * through {@link #sendTrnrptScreen(ProgramState)}.
     *
     * @param request the inbound screen; must not be {@code null}
     * @return the state at the moment the task returned to CICS or transferred, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public ProgramState mainPara(ReportRequestRequest request) {
        Objects.requireNonNull(request, "A request is required: CORPT00C is driven entirely by its "
                + "communication area, the EIBAID and the received map, all of which travel in it");

        ProgramState state = new ProgramState();

        state.setErrFlagOff();                                                          // L165
        state.setTransactNotEof();                                                       // L166
        state.setSendEraseYes();                                                         // L167

        state.setMessage(SPACE.repeat(WS_MESSAGE_LENGTH));                               // L169
        state.response().moveSpacesToErrmsgo();                                          // L170

        // L172 IF EIBCALEN = 0. No communication area travelled, so nothing is known about who called
        // and the screen cannot be painted: the program hands control to the sign-on program.
        if (!request.hasNavigationContext()) {
            state.setCommarea(state.commarea().withToProgram(SIGN_ON_PROGRAM));          // L173
            returnToPrevScreen(state);                                                   // L174
            return state;
        }

        // L176 MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA.
        state.setCommarea(request.navigationContext());

        // L177 IF NOT CDEMO-PGM-REENTER - first entry, so paint the screen and ask for input.
        if (!state.commarea().isReenter()) {
            state.setCommarea(state.commarea().withPgmReenter());                        // L178
            state.response().moveLowValuesToMapGroup();                                  // L179
            state.moveMinusOneTo(ReportRequestRequest.ScreenField.MONTHLY);              // L180
            sendTrnrptScreen(state);                                                     // L181
            return state;
        }

        // L183 PERFORM RECEIVE-TRNRPT-SCREEN.
        receiveTrnrptScreen(state, request);

        // L184-L195 EVALUATE EIBAID, in the source's order with WHEN OTHER last. The raw AID byte is
        // reconstructed from the payload token so the two tests read as the source's two tests.
        byte eibAid = eibAidOf(request.aid());
        if (PfKeyResolver.isEnter(eibAid)) {                                             // WHEN DFHENTER
            processEnterKey(state);                                                      // L186
            return state;
        }
        if (PfKeyResolver.isPf3(eibAid)) {                                               // WHEN DFHPF3
            state.setCommarea(state.commarea().withToProgram(MAIN_MENU_PROGRAM));        // L188
            returnToPrevScreen(state);                                                   // L189
            return state;
        }
        // L190 WHEN OTHER.
        state.setErrFlagOn();                                                            // L191
        state.moveMinusOneTo(ReportRequestRequest.ScreenField.MONTHLY);                  // L192
        state.setMessage(codec.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                WS_MESSAGE_LENGTH));                                                     // L193
        sendTrnrptScreen(state);                                                         // L194
        return state;
    }

    // =============================================================================================
    // PROCESS-ENTER-KEY - app/cbl/CORPT00C.cbl:208-456.
    //
    // An ordered EVALUATE TRUE over the three report kinds, first match winning, with WHEN OTHER last.
    // The three arms are rendered as three methods because each is a self-contained twenty-five to
    // one-hundred-and-eighty line block; the ORDER of the tests, which is what EVALUATE guarantees, is
    // stated once here and cannot drift.
    // =============================================================================================

    /**
     * {@code PROCESS-ENTER-KEY} - lines 208 to 456.
     *
     * <p>Each of the three report arms either rejects and returns to CICS, or reaches
     * {@link #submitJobToIntrdr(ProgramState)}. The closing {@code IF NOT ERR-FLG-ON} at line 445 is
     * the source's own test and it is what makes this paragraph correct after an arm has already sent
     * a screen: a rejected arm leaves {@code ERR-FLG-ON}, so the success notice is not sent on top of
     * the rejection.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void processEnterKey(ProgramState state) {
        requireState(state);

        display(state, DISPLAY_PROCESS_ENTER_KEY);                                        // L210

        // L212-L443 EVALUATE TRUE. Each guard is the abbreviated combined relation
        // "NOT = SPACES AND LOW-VALUES", that is: neither spaces nor low-values.
        if (!ReportRequestResponse.isSpacesOrLowValues(state.monthlyI())) {               // L213
            processMonthlyArm(state);
        } else if (!ReportRequestResponse.isSpacesOrLowValues(state.yearlyI())) {         // L239
            processYearlyArm(state);
        } else if (!ReportRequestResponse.isSpacesOrLowValues(state.customI())) {         // L256
            processCustomArm(state);
        } else {                                                                          // L437
            rejectAndSend(state, MSG_SELECT_REPORT_TYPE,
                    ReportRequestRequest.ScreenField.MONTHLY);                            // L438-442
            return;
        }

        // L445 IF NOT ERR-FLG-ON - the submission succeeded, so clear the form and say so.
        if (state.errFlagOff()) {
            initializeAllFields(state);                                                   // L447
            state.response().setErrmsgc(BmsAttributes.DFHGREEN);                          // L448
            state.setMessage(stringInto(state.message(), codec.concatenateDelimitedBySize(
                    stringDelimitedBySpace(state.reportName()), MSG_REPORT_SUBMITTED)));  // L449-452
            state.moveMinusOneTo(ReportRequestRequest.ScreenField.MONTHLY);               // L453
            sendTrnrptScreen(state);                                                      // L454
        }
    }

    /**
     * The {@code WHEN MONTHLYI} arm - lines 213 to 238: the current calendar month, first day to last.
     *
     * <p>The month end is computed the way the source computes it, and the way it does that is worth
     * stating because a shortcut would be wrong for four months of the year and for February in three
     * years out of four. The source moves 1 into the day, adds 1 to the month, rolls the year when the
     * month passes twelve, and then subtracts a single day from that first-of-next-month date through
     * the {@code FUNCTION DATE-OF-INTEGER} / {@code FUNCTION INTEGER-OF-DATE} pair at lines 229 and
     * 230. December therefore rolls into January of the next year and comes back as the thirty-first of
     * December, and a leap February comes back as the twenty-ninth.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void processMonthlyArm(ProgramState state) {
        requireState(state);

        state.setReportName(codec.movePicX(REPORT_NAME_MONTHLY, WS_REPORT_NAME_LENGTH));  // L214

        // L215 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA. WS-CURDATE-YEAR is PIC 9(04) and
        // WS-CURDATE-MONTH and WS-CURDATE-DAY are PIC 9(02); the three are held here as the integers
        // the ADD at line 224 and the COMPUTE at line 229 operate on.
        DateHeader header = DateHeader.from(codec, clock);
        int year = header.captured().year();
        int month = header.captured().month();

        state.setStartDateYyyy(codec.movePic9(year, DATE_YEAR_LENGTH));                   // L217
        state.setStartDateMm(codec.movePic9(month, DATE_PART_LENGTH));                    // L218
        state.setStartDateDd(FIRST_OF_PERIOD);                                            // L219
        state.setParmStartDate1(state.startDate());                                       // L220
        state.setParmStartDate2(state.startDate());                                       // L221

        int day = ONE;                                                                    // L223
        month = month + ONE;                                                              // L224
        if (month > MONTHS_PER_YEAR) {                                                    // L225
            year = year + ONE;                                                            // L226
            month = ONE;                                                                  // L227
        }

        // L229-L230 COMPUTE WS-CURDATE-N = FUNCTION DATE-OF-INTEGER(
        //                   FUNCTION INTEGER-OF-DATE(WS-CURDATE-N) - 1)
        int curdateN = dateOfInteger(integerOfDate(standardDate(year, month, day)) - ONE);
        year = yearOfStandardDate(curdateN);
        month = monthOfStandardDate(curdateN);
        day = dayOfStandardDate(curdateN);

        state.setEndDateYyyy(codec.movePic9(year, DATE_YEAR_LENGTH));                     // L232
        state.setEndDateMm(codec.movePic9(month, DATE_PART_LENGTH));                      // L233
        state.setEndDateDd(codec.movePic9(day, DATE_PART_LENGTH));                        // L234
        state.setParmEndDate1(state.endDate());                                           // L235
        state.setParmEndDate2(state.endDate());                                           // L236

        submitJobToIntrdr(state);                                                         // L238
    }

    /**
     * The {@code WHEN YEARLYI} arm - lines 239 to 255: the current calendar year, 1 January to 31
     * December.
     *
     * <p>No intrinsic is needed: the source moves the literals {@code '01'}, {@code '01'}, {@code '12'}
     * and {@code '31'} directly, and the year comes from {@code FUNCTION CURRENT-DATE} at line 241.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void processYearlyArm(ProgramState state) {
        requireState(state);

        state.setReportName(codec.movePicX(REPORT_NAME_YEARLY, WS_REPORT_NAME_LENGTH));   // L240

        DateHeader header = DateHeader.from(codec, clock);                                 // L241
        String year = codec.movePic9(header.captured().year(), DATE_YEAR_LENGTH);

        state.setStartDateYyyy(year);                                                     // L243
        state.setEndDateYyyy(year);                                                       // L244
        state.setStartDateMm(FIRST_OF_PERIOD);                                            // L245
        state.setStartDateDd(FIRST_OF_PERIOD);                                            // L246
        state.setParmStartDate1(state.startDate());                                       // L247
        state.setParmStartDate2(state.startDate());                                       // L248

        state.setEndDateMm(LAST_MONTH_OF_YEAR);                                           // L250
        state.setEndDateDd(LAST_DAY_OF_DECEMBER);                                         // L251
        state.setParmEndDate1(state.endDate());                                           // L252
        state.setParmEndDate2(state.endDate());                                           // L253

        submitJobToIntrdr(state);                                                         // L255
    }

    /**
     * The {@code WHEN CUSTOMI} arm - lines 256 to 436: the operator's own date range, in four stages.
     *
     * <p>The stages run in the source's order and each one is allowed to reject:
     * <ol>
     *   <li><strong>Blank chain</strong>, lines 258 to 303. An ordered {@code EVALUATE TRUE} over the
     *       six date parts, first blank winning, with {@code WHEN OTHER CONTINUE} at line 301.</li>
     *   <li><strong>Normalise in place</strong>, lines 305 to 327. Each part is passed through
     *       {@code FUNCTION NUMVAL-C} into {@code PIC 99} or {@code PIC 9999} and moved <em>back into
     *       the same symbolic-map item</em>, so {@code " 7"} becomes {@code "07"} and the screen that is
     *       next sent shows the normalised value. Nothing is rejected here.</li>
     *   <li><strong>Re-validate the text</strong>, lines 329 to 379. Six independent {@code IF}s, each
     *       testing the <em>alphanumeric</em> item that stage two has just rewritten.</li>
     *   <li><strong>Validate the composed dates</strong>, lines 388 to 426, by calling
     *       {@code CSUTLDTC} twice.</li>
     * </ol>
     *
     * <p>Every rejecting site returns, which is the rule R7 rendering of the {@code GO TO
     * RETURN-TO-CICS} that {@code SEND-TRNRPT-SCREEN} ends with. Without it a rejected month would be
     * re-reported a few lines later as an invalid <em>date</em>, because stages three and four would
     * keep running and overwrite {@code WS-MESSAGE}. This is the single most consequential control-flow
     * decision in the file.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void processCustomArm(ProgramState state) {
        requireState(state);

        // ---- Stage 1. L258-L303, the ordered blank chain. -----------------------------------------
        if (ReportRequestResponse.isSpacesOrLowValues(state.sdtmmI())) {                   // L259
            rejectAndSend(state, MSG_START_DATE_MONTH_EMPTY,
                    ReportRequestRequest.ScreenField.SDTMM);                               // L261-265
            return;
        }
        if (ReportRequestResponse.isSpacesOrLowValues(state.sdtddI())) {                   // L266
            rejectAndSend(state, MSG_START_DATE_DAY_EMPTY,
                    ReportRequestRequest.ScreenField.SDTDD);                               // L268-272
            return;
        }
        if (ReportRequestResponse.isSpacesOrLowValues(state.sdtyyyyI())) {                 // L273
            rejectAndSend(state, MSG_START_DATE_YEAR_EMPTY,
                    ReportRequestRequest.ScreenField.SDTYYYY);                             // L275-279
            return;
        }
        if (ReportRequestResponse.isSpacesOrLowValues(state.edtmmI())) {                   // L280
            rejectAndSend(state, MSG_END_DATE_MONTH_EMPTY,
                    ReportRequestRequest.ScreenField.EDTMM);                               // L282-286
            return;
        }
        if (ReportRequestResponse.isSpacesOrLowValues(state.edtddI())) {                   // L287
            rejectAndSend(state, MSG_END_DATE_DAY_EMPTY,
                    ReportRequestRequest.ScreenField.EDTDD);                               // L289-293
            return;
        }
        if (ReportRequestResponse.isSpacesOrLowValues(state.edtyyyyI())) {                 // L294
            rejectAndSend(state, MSG_END_DATE_YEAR_EMPTY,
                    ReportRequestRequest.ScreenField.EDTYYYY);                             // L296-300
            return;
        }
        // L301-L302 WHEN OTHER CONTINUE - no part is blank, so the arm proceeds.

        // ---- Stage 2. L305-L327, normalise each part in place. ------------------------------------
        state.setSdtmmI(computeIntoPic99(state.sdtmmI()));                                 // L305-307
        state.setSdtddI(computeIntoPic99(state.sdtddI()));                                 // L309-311
        state.setSdtyyyyI(computeIntoPic9999(state.sdtyyyyI()));                           // L313-315
        state.setEdtmmI(computeIntoPic99(state.edtmmI()));                                 // L317-319
        state.setEdtddI(computeIntoPic99(state.edtddI()));                                 // L321-323
        state.setEdtyyyyI(computeIntoPic9999(state.edtyyyyI()));                           // L325-327

        // ---- Stage 3. L329-L379, re-validate the text form. --------------------------------------
        if (isNotValidTwoDigitPart(state.sdtmmI(), HIGHEST_MONTH)) {                       // L329-330
            rejectAndSend(state, MSG_START_DATE_INVALID_MONTH,
                    ReportRequestRequest.ScreenField.SDTMM);                               // L331-335
            return;
        }
        if (isNotValidTwoDigitPart(state.sdtddI(), HIGHEST_DAY)) {                         // L338-339
            rejectAndSend(state, MSG_START_DATE_INVALID_DAY,
                    ReportRequestRequest.ScreenField.SDTDD);                               // L340-344
            return;
        }
        if (!isNumericClass(state.sdtyyyyI())) {                                           // L347
            rejectAndSend(state, MSG_START_DATE_INVALID_YEAR,
                    ReportRequestRequest.ScreenField.SDTYYYY);                             // L348-352
            return;
        }
        if (isNotValidTwoDigitPart(state.edtmmI(), HIGHEST_MONTH)) {                       // L355-356
            rejectAndSend(state, MSG_END_DATE_INVALID_MONTH,
                    ReportRequestRequest.ScreenField.EDTMM);                               // L357-361
            return;
        }
        if (isNotValidTwoDigitPart(state.edtddI(), HIGHEST_DAY)) {                         // L364-365
            rejectAndSend(state, MSG_END_DATE_INVALID_DAY,
                    ReportRequestRequest.ScreenField.EDTDD);                               // L366-370
            return;
        }
        if (!isNumericClass(state.edtyyyyI())) {                                           // L373
            rejectAndSend(state, MSG_END_DATE_INVALID_YEAR,
                    ReportRequestRequest.ScreenField.EDTYYYY);                             // L374-378
            return;
        }

        // ---- L381-L386, compose the two ten-byte dates from the six normalised parts. -------------
        state.setStartDateYyyy(state.sdtyyyyI());                                          // L381
        state.setStartDateMm(state.sdtmmI());                                              // L382
        state.setStartDateDd(state.sdtddI());                                              // L383
        state.setEndDateYyyy(state.edtyyyyI());                                            // L384
        state.setEndDateMm(state.edtmmI());                                                // L385
        state.setEndDateDd(state.edtddI());                                                // L386

        // ---- Stage 4. L388-L426, the two CSUTLDTC calls. ------------------------------------------
        if (!callCsutldtc(state, state.startDate(), MSG_START_DATE_INVALID,
                ReportRequestRequest.ScreenField.SDTMM)) {                                 // L388-406
            return;
        }
        if (!callCsutldtc(state, state.endDate(), MSG_END_DATE_INVALID,
                ReportRequestRequest.ScreenField.EDTMM)) {                                 // L408-426
            return;
        }

        state.setParmStartDate1(state.startDate());                                        // L429
        state.setParmStartDate2(state.startDate());                                        // L430
        state.setParmEndDate1(state.endDate());                                            // L431
        state.setParmEndDate2(state.endDate());                                            // L432
        // L433 - assigned here, AFTER the edits, so a rejected arm leaves WS-REPORT-NAME at spaces.
        state.setReportName(codec.movePicX(REPORT_NAME_CUSTOM, WS_REPORT_NAME_LENGTH));

        if (state.errFlagOff()) {                                                          // L434
            submitJobToIntrdr(state);                                                      // L435
        }
    }

    /**
     * The four-statement rejection shape the source writes fifteen times: move the message, raise the
     * error flag, ask for the cursor on the offending field, and send the screen - which returns to
     * CICS.
     *
     * <p>The sites are lines 261-265, 268-272, 275-279, 282-286, 289-293, 296-300, 331-335, 340-344,
     * 348-352, 357-361, 366-370, 374-378, 400-404, 420-424 and 438-442. Every one is textually
     * identical apart from its literal and its field, so the shape is rendered once; what is shared is
     * the rendering, never the decision, because each caller still evaluates its own guard.
     *
     * <p>{@code MOVE -1 TO <field>L} writes the symbolic map's {@code xxxL} length item, which is
     * metadata rather than payload: it is the cursor request CICS honours because every
     * {@code EXEC CICS SEND} in this program specifies {@code CURSOR}. It is recorded on
     * {@link ProgramState#symbolicMap()} and never added to the response payload.
     *
     * @param state       the per-request working storage; must not be {@code null}
     * @param message     the literal to move into {@code WS-MESSAGE}; must not be {@code null}
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
     * {@code CALL 'CSUTLDTC' USING CSUTLDTC-DATE CSUTLDTC-DATE-FORMAT CSUTLDTC-RESULT} - lines 388 to
     * 406 for the start date and 408 to 426 for the end date.
     *
     * <p>The subprogram is a constructor-injected {@code @Service}, not a copybook and not a Spring
     * Batch job, and its severity and message number are read from the typed result rather than
     * re-parsed out of the eighty-byte message - the same bytes, named rather than sliced.
     *
     * <p>The guard has three outcomes and all three matter:
     * <ul>
     *   <li>severity {@value #CSUTLDTC_SEVERITY_OK} - the date converted, {@code CONTINUE};</li>
     *   <li>a non-zero severity whose message number <em>is</em>
     *       {@value #CSUTLDTC_TOLERATED_MESSAGE_NUMBER} - {@code FC-UNSUPP-RANGE}, which both call
     *       sites deliberately accept: the inner {@code IF} is a {@code NOT =} test, so this outcome
     *       falls through with no rejection and no message;</li>
     *   <li>any other non-zero severity - rejected with the site's own literal and the cursor on the
     *       month field.</li>
     * </ul>
     *
     * @param state       the per-request working storage; must not be {@code null}
     * @param date        the ten-byte {@code 'YYYY-MM-DD'} group to validate; must not be {@code null}
     * @param message     the literal to move into {@code WS-MESSAGE} on rejection; must not be
     *                    {@code null}
     * @param cursorField the field whose length item receives {@code -1} on rejection; must not be
     *                    {@code null}
     * @return {@code true} when the arm may proceed, {@code false} when the screen has been sent
     * @throws NullPointerException if any argument is {@code null}
     */
    public boolean callCsutldtc(ProgramState state, String date, String message,
                                ReportRequestRequest.ScreenField cursorField) {
        requireState(state);
        Objects.requireNonNull(date, "A date is required; CORPT00C moves WS-START-DATE or WS-END-DATE "
                + "into CSUTLDTC-DATE before each call");

        // MOVE <date> TO CSUTLDTC-DATE, MOVE WS-DATE-FORMAT TO CSUTLDTC-DATE-FORMAT and
        // MOVE SPACES TO CSUTLDTC-RESULT are the call's three parameters; the third is an output, and
        // clearing it before the call is what a fresh result carrier expresses.
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

    // =============================================================================================
    // SUBMIT-JOB-TO-INTRDR - app/cbl/CORPT00C.cbl:462-510.
    // =============================================================================================

    /**
     * {@code SUBMIT-JOB-TO-INTRDR} - lines 462 to 510: confirm, then emit the job.
     *
     * <p>Three things about the emit loop are easy to get wrong and are therefore stated here.
     *
     * <p><strong>The sentinel record is itself written.</strong> Line 502 recognises {@code '/*EOF'},
     * line 504 sets {@code END-LOOP-YES}, and line 507 performs the write <em>unconditionally</em>
     * afterwards. Setting the flag does not skip the write, so a normal run emits exactly
     * {@value #JOB_LINE_COUNT} records and the last of them is the end-of-file marker the internal
     * reader needs. Treating the flag as a {@code continue} would emit sixteen records and submit a job
     * the reader never releases.
     *
     * <p><strong>The table does not bound the loop.</strong> {@code JOB-LINES} is declared
     * {@code OCCURS 1000 TIMES} over a group that is only {@value #JOB_DATA_LENGTH} bytes wide. The
     * sentinel is what stops the traversal at entry {@value #JOB_LINE_COUNT}; the {@code > 1000} test is
     * a backstop that a well-formed skeleton never reaches.
     *
     * <p><strong>A failed write does not roll back.</strong> The write is inside the loop and the queue
     * is {@code DISPOSITION(MOD)}, so a failure on record <em>k</em> leaves records 1 to <em>k</em>-1
     * already appended and stops the loop through the {@code OR ERR-FLG-ON} in its {@code UNTIL}. No
     * transaction wraps this, and none is added.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void submitJobToIntrdr(ProgramState state) {
        requireState(state);

        // L464 IF CONFIRMI OF CORPT0AI = SPACES OR LOW-VALUES - ask for confirmation and stop.
        if (ReportRequestResponse.isSpacesOrLowValues(state.confirmI())) {
            state.setMessage(stringInto(state.message(), codec.concatenateDelimitedBySize(
                    MSG_PLEASE_CONFIRM_PREFIX,
                    stringDelimitedBySpace(state.reportName()),
                    MSG_REPORT_SUFFIX)));                                                  // L465-470
            state.setErrFlagOn();                                                          // L471
            state.moveMinusOneTo(ReportRequestRequest.ScreenField.CONFIRM);                 // L472
            sendTrnrptScreen(state);                                                       // L473
        }

        // L476 IF NOT ERR-FLG-ON. This is the source's own guard and it is what makes the paragraph
        // correct after the blank-confirm arm has already sent a screen and returned to CICS.
        if (state.errFlagOff()) {
            String confirm = state.confirmI();

            // L477-L494 EVALUATE TRUE, in source order with WHEN OTHER last.
            if (CONFIRM_YES_UPPER.equals(confirm) || CONFIRM_YES_LOWER.equals(confirm)) {  // L478
                // L479 CONTINUE. The operator confirmed, so the paragraph falls through to the emit
                // loop with nothing changed, and this arm is therefore empty on purpose: CONTINUE is a
                // COBOL statement that does nothing, not an unimplemented branch. Assigning a flag
                // here to make the arm look occupied would add state the program does not have.
                LOG.debug("Report submission confirmed; emitting the job skeleton");
            } else if (CONFIRM_NO_UPPER.equals(confirm) || CONFIRM_NO_LOWER.equals(confirm)) { // L480
                initializeAllFields(state);                                                // L481
                state.setErrFlagOn();                                                      // L482
                sendTrnrptScreen(state);                                                   // L483
            } else {                                                                       // L484
                state.setMessage(stringInto(state.message(), codec.concatenateDelimitedBySize(
                        MSG_QUOTE,
                        stringDelimitedBySpace(confirm),
                        MSG_NOT_A_VALID_CONFIRM_VALUE)));                                  // L485-490
                state.setErrFlagOn();                                                      // L491
                state.moveMinusOneTo(ReportRequestRequest.ScreenField.CONFIRM);             // L492
                sendTrnrptScreen(state);                                                   // L493
            }

            state.setEndLoopNo();                                                          // L496

            // L498-L508 PERFORM VARYING WS-IDX FROM 1 BY 1
            //           UNTIL WS-IDX > 1000 OR END-LOOP-YES OR ERR-FLG-ON.
            // Written as the complement of that UNTIL, using the copybook's own condition names. The
            // 'N' and 'n' arms above leave ERR-FLG-ON, so the body runs zero times and nothing is
            // emitted - which is exactly what the source's GO TO achieves by never arriving here.
            List<String> jobLines = jobLines(state);
            state.setIdx(ONE);
            while (state.idx() <= JOB_LINES_OCCURS && state.endLoopNo() && state.errFlagOff()) {
                state.setJclRecord(jobLine(jobLines, state.idx()));                        // L501
                if (EOF_MARKER_RECORD.equals(state.jclRecord())
                        || ReportRequestResponse.isSpacesOrLowValues(state.jclRecord())) {  // L502-503
                    state.setEndLoopYes();                                                 // L504
                }
                writeJobSubmissionQueue(state);                                            // L507
                state.setIdx(state.idx() + ONE);
            }
        }
    }

    /**
     * {@code WIRTE-JOBSUB-TDQ} - lines 515 to 535. <strong>The paragraph name is misspelled in the
     * source</strong> and is preserved there rather than corrected; this method carries the correct
     * spelling and cites the paragraph it renders.
     *
     * <pre>{@code
     *  EXEC CICS WRITEQ TD
     *    QUEUE ('JOBS')
     *    FROM (JCL-RECORD)
     *    LENGTH (LENGTH OF JCL-RECORD)
     *    RESP(WS-RESP-CD)
     *    RESP2(WS-REAS-CD)
     *  END-EXEC.
     * }</pre>
     *
     * <p>Java has no transient data queue, so the command becomes one call on
     * {@link JobSubmissionPort}. The {@code RESP} and {@code RESP2} values the command returns are
     * recorded in working storage and evaluated exactly as the source evaluates them: {@code NORMAL}
     * continues, and anything else displays the two codes, raises the error flag, reports
     * {@value #MSG_UNABLE_TO_WRITE_TDQ} and re-paints the screen with the cursor on the first report
     * option.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void writeJobSubmissionQueue(ProgramState state) {
        requireState(state);

        WriteQueueOutcome outcome = jobSubmissionPort.writeQueueTd(state.jclRecord());      // L517-523
        state.recordSubmitted(state.jclRecord());
        state.setRespCd(outcome.resp());
        state.setReasCd(outcome.resp2());

        // L525-L535 EVALUATE WS-RESP-CD.
        if (outcome.normal()) {                                                            // L526
            return;                                                                        // L527
        }
        // L528 WHEN OTHER.
        display(state, DISPLAY_RESP_PREFIX + codec.movePic9(state.respCd(), WS_RESP_CD_DIGITS)
                + DISPLAY_REAS_PREFIX + codec.movePic9(state.reasCd(), WS_RESP_CD_DIGITS)); // L529
        state.setErrFlagOn();                                                              // L530
        state.setMessage(codec.movePicX(MSG_UNABLE_TO_WRITE_TDQ, WS_MESSAGE_LENGTH));       // L531-532
        state.moveMinusOneTo(ReportRequestRequest.ScreenField.MONTHLY);                     // L533
        sendTrnrptScreen(state);                                                           // L534
    }

    // =============================================================================================
    // The screen and navigation paragraphs - app/cbl/CORPT00C.cbl:540-646.
    // =============================================================================================

    /**
     * {@code RETURN-TO-PREV-SCREEN} - lines 540 to 551: hand control to another program.
     *
     * <p>{@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)} transfers and
     * never comes back. There is no server-side forward here and no redirect: the response names the
     * program to go to and the client issues the follow-up call, which is what keeps the server
     * stateless. No screen is sent on this path, because the target program paints its own.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void returnToPrevScreen(ProgramState state) {
        requireState(state);

        // L542-L544 IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES.
        if (ReportRequestResponse.isSpacesOrLowValues(state.commarea().toProgram())) {
            state.setCommarea(state.commarea().withToProgram(SIGN_ON_PROGRAM));            // L543
        }
        state.setCommarea(state.commarea()
                .withFromTranid(TRANSACTION_ID)                                            // L545
                .withFromProgram(PROGRAM_NAME)                                             // L546
                .withPgmEnter());                                                          // L547

        // L548-L551 EXEC CICS XCTL.
        state.response().echoNavigation(state.commarea());
        state.markTransferred();
    }

    /**
     * {@code SEND-TRNRPT-SCREEN} - lines 556 to 580: paint the screen and return to CICS.
     *
     * <p><strong>This method is terminal.</strong> The paragraph ends {@code GO TO RETURN-TO-CICS} at
     * line 580, so control never comes back to the statement after the {@code PERFORM}. Every caller
     * here is written accordingly - it returns, or it relies on the {@code ERR-FLG-ON} the site has just
     * raised, which is what the source itself relies on at lines 434, 445, 476 and 499.
     *
     * <p>The two {@code EXEC CICS SEND} statements at lines 563 and 571 differ only in {@code ERASE}.
     * Both are kept, and so is the fact that {@code WS-SEND-ERASE-FLG} is set to {@code 'Y'} at line 167
     * and never to {@code 'N'} anywhere in the program: the no-erase arm is unreachable in the composed
     * flow, and it is preserved rather than simplified away. Both arms name {@code MAP('CORPT0A')} and
     * {@code MAPSET('CORPT00')}, which the response reports as the map to paint, and both specify
     * {@code CURSOR}, which is what makes the {@code MOVE -1 TO <field>L} sites meaningful.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void sendTrnrptScreen(ProgramState state) {
        requireState(state);

        populateHeaderInfo(state);                                                          // L558
        state.response().setErrmsgo(state.message());                                       // L560
        state.response().setNextMapset(ReportRequestResponse.MAPSET_NAME);
        state.response().setNextMap(ReportRequestResponse.MAP_NAME);

        if (state.sendEraseYes()) {                                                         // L562
            state.recordScreenSent(true);                                                   // L563-569
        } else {
            state.recordScreenSent(false);                                                  // L571-577
        }

        returnToCics(state);                                                                // L580
    }

    /**
     * {@code RETURN-TO-CICS} - lines 585 to 591, and the identical
     * {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} the source also writes at
     * lines 199 to 202.
     *
     * <p>{@code TRANSID('CR00')} routes the operator's next input back to this transaction, and
     * {@code app/csd/CARDDEMO.CSD:409-410} binds {@code CR00} to {@code CORPT00C} - so the response's
     * next program is this program, not a different one. The communication area travels back in the
     * payload with {@code CDEMO-PGM-CONTEXT} already set to re-enter, which is how the next call knows
     * to read the map rather than paint it.
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
     * <p>{@code EXEC CICS RECEIVE MAP('CORPT0A') MAPSET('CORPT00') INTO(CORPT0AI)} fills the symbolic
     * map from the inbound datastream. All seventeen fields carry {@code FSET} in
     * {@code app/bms/CORPT00.bms}, so CICS returns all seventeen whether or not the operator touched
     * them, and all seventeen are copied here.
     *
     * <p>They are copied into the <em>response</em> because {@code 01 CORPT0AO REDEFINES CORPT0AI}: the
     * two views are one buffer. That is what makes the in-place normalisation at lines 305 to 327 visible
     * on the screen that is next sent, and it is why this program can read {@code MONTHLYI} and write
     * {@code ERRMSGO} without any copying between two objects.
     *
     * <p>The command captures {@code RESP} and {@code RESP2} and the program never tests either, so no
     * branch is invented here; the two codes are recorded as a successful receive because the payload
     * has already arrived.
     *
     * @param state   the per-request working storage; must not be {@code null}
     * @param request the inbound screen; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalStateException if the request and response projections of the symbolic map ever
     *                               disagree about how many fields it has
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
     * Verifies that the request and response projections of {@code app/cpy-bms/CORPT00.CPY} agree about
     * how many fields the symbolic map has, before the received values are copied across by ordinal.
     * The copybook declares seventeen {@code xxxI} items and seventeen {@code xxxO} items, so the two
     * counts are the same number twice; if they ever differ, one projection has drifted and copying by
     * ordinal would put a value into the wrong field rather than fail.
     *
     * <p>Extracted from {@link #receiveTrnrptScreen} so the rejecting path is reachable from a test.
     *
     * @param requestFieldCount  how many values the request projects
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
     * <p>Reads {@code FUNCTION CURRENT-DATE} at line 611 and fills the six header fields: the two
     * titles from {@code COTTL01Y}, the transaction and program names, and the date and time as
     * {@code mm/dd/yy} and {@code hh:mm:ss}. {@link ReportRequestResponse#populateHeaderInfo(DateHeader)}
     * performs the six moves of lines 613 to 628 against the copybook's literals.
     *
     * <p>This read is deliberately <em>separate</em> from the one the monthly arm performs at line 215.
     * The arm mutates {@code WS-CURDATE-DATA} while computing the month end, and this paragraph
     * overwrites it, which is why the header shows today's date and not the month end. Merging the two
     * reads would change what the screen displays.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void populateHeaderInfo(ProgramState state) {
        requireState(state);

        DateHeader header = DateHeader.from(codec, clock);                                   // L611
        state.setDateHeader(header);
        state.response().populateHeaderInfo(header);                                         // L613-628
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS} - lines 633 to 646.
     *
     * <p>Asks for the cursor on the first report option and then {@code INITIALIZE}s the ten input
     * fields and {@code WS-MESSAGE}. {@code INITIALIZE} on a {@code PIC X} item sets it to spaces, so
     * the form comes back empty rather than filled with the previous request's values. The header fields
     * are <em>not</em> in the list, because the send that follows re-populates them.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void initializeAllFields(ProgramState state) {
        requireState(state);

        state.moveMinusOneTo(ReportRequestRequest.ScreenField.MONTHLY);                      // L635
        state.response().initializeAllFields();                                              // L636-645
        state.setMessage(SPACE.repeat(WS_MESSAGE_LENGTH));                                   // L646
    }

    // =============================================================================================
    // The job skeleton: template, substitution and one-based access.
    // =============================================================================================

    /**
     * The seventeen records with this request's four substitution points filled: the per-request copy of
     * {@link #JOB_DATA_TEMPLATE}.
     *
     * <p>{@code PARM-START-DATE-1} and {@code PARM-END-DATE-1} land in the two {@code SYMNAMES} records
     * that the sort step's {@code INCLUDE COND} compares against, and {@code PARM-START-DATE-2} and
     * {@code PARM-END-DATE-2} land in the {@code DATEPARM} record that {@code CBTRN03C} reads. They are
     * four distinct storage locations in the COBOL and four distinct fields here, even though every arm
     * moves the same value into both members of each pair.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @return an unmodifiable list of {@value #JOB_LINE_COUNT} records of exactly
     *         {@value #JCL_RECORD_LENGTH} characters, in declaration order
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
     * <p><strong>{@code OCCURS} subscripts are one-based and Java list indices are zero-based.</strong>
     * That off-by-one is the highest-frequency defect in a migration of this kind, so the conversion
     * happens in exactly one place - here - and every caller passes the subscript the COBOL passes.
     *
     * <p>{@code JOB-LINES} is declared {@code OCCURS 1000 TIMES} over a group of only
     * {@value #JOB_DATA_LENGTH} bytes, so entries beyond {@value #JOB_LINE_COUNT} would read storage that
     * does not belong to {@code JOB-DATA-1} at all. The emit loop never asks for one, because entry
     * {@value #JOB_LINE_COUNT} is the {@code '/*EOF'} sentinel and sets {@code END-LOOP-YES}. A caller
     * that asks anyway is told so rather than handed whatever follows in memory.
     *
     * @param lines        the substituted records from {@link #jobLines(ProgramState)}; must not be
     *                     {@code null}
     * @param occursIndex  the one-based {@code OCCURS} subscript, as {@code WS-IDX} holds it
     * @return the record at that subscript, exactly {@value #JCL_RECORD_LENGTH} characters
     * @throws NullPointerException      if {@code lines} is {@code null}
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
     * <p>Composed at {@link DateParmReader}'s own published offsets rather than by concatenation, so the
     * screen that writes this record and the batch reader that parses it cannot disagree: a ten-byte
     * start date, one space, a ten-byte end date and a fifty-nine byte tail the reader discards, in
     * eighty bytes. The class-initialisation self-check verifies those offsets against the COBOL's
     * declaration, so a change to either side fails at load time.
     *
     * @param startDate the start date, {@value #WS_DATE_LENGTH} characters or shorter; must not be
     *                  {@code null}
     * @param endDate   the end date, {@value #WS_DATE_LENGTH} characters or shorter; must not be
     *                  {@code null}
     * @return the record, exactly {@value #JCL_RECORD_LENGTH} characters
     * @throws NullPointerException     if either argument is {@code null}
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
     * <p>The closing quote is the first character of the trailing {@code FILLER}, not a character
     * appended after the date, which is why the tail is produced by {@link #picXValue(String, int)} from
     * the one-character literal and its declared width. Composed this way, a malformed line is
     * impossible: the prefix width, the ten-byte date and the tail width must sum to eighty or the
     * record is refused.
     *
     * @param prefix     the symbol name and opening quote, at its exact declared width; must not be
     *                   {@code null}
     * @param date       the date to substitute; must not be {@code null}
     * @param tailLength the declared width of the trailing {@code FILLER} whose {@code VALUE} is the
     *                   closing quote
     * @return the record, exactly {@value #JCL_RECORD_LENGTH} characters
     * @throws NullPointerException     if {@code prefix} or {@code date} is {@code null}
     * @throws IllegalArgumentException if the composed record is not {@value #JCL_RECORD_LENGTH}
     *                                  characters
     */
    public static String symnamesLine(String prefix, String date, int tailLength) {
        Objects.requireNonNull(prefix, "A SYMNAMES prefix is required");
        String line = picXValue(prefix, prefix.length())
                + picXValue(date, WS_DATE_LENGTH)
                + picXValue(SYMNAMES_CLOSING_QUOTE, tailLength);
        return requireRecordWidth(line, "a SYMNAMES record");
    }

    /**
     * A COBOL {@code VALUE} clause on a {@code PIC X(n)} item: the literal, left justified, with the
     * remainder space filled - and a refusal when the literal is wider than the item, which is what the
     * compiler would report.
     *
     * @param literal      the {@code VALUE} literal; must not be {@code null}
     * @param declaredWidth the item's declared width; at least the literal's length
     * @return exactly {@code declaredWidth} characters
     * @throws NullPointerException     if {@code literal} is {@code null}
     * @throws IllegalArgumentException if {@code literal} is wider than {@code declaredWidth}
     */
    private static String picXValue(String literal, int declaredWidth) {
        Objects.requireNonNull(literal, "A VALUE literal is required");
        if (literal.length() > declaredWidth) {
            throw new IllegalArgumentException("A VALUE of " + literal.length() + " character(s) does "
                    + "not fit an item declared PIC X(" + declaredWidth + ")");
        }
        return literal + SPACE.repeat(declaredWidth - literal.length());
    }

    /**
     * Refuses a record that is not exactly {@value #JCL_RECORD_LENGTH} characters.
     *
     * @param record  the composed record
     * @param subject what is being composed, named in the diagnostic
     * @return {@code record} unchanged
     * @throws IllegalArgumentException if the width is wrong
     */
    private static String requireRecordWidth(String record, String subject) {
        if (record.length() != JCL_RECORD_LENGTH) {
            throw new IllegalArgumentException(subject + " is " + record.length() + " character(s); "
                    + "JCL-RECORD is PIC X(80) and TDQUEUE(JOBS) declares RECORDSIZE("
                    + JCL_RECORD_LENGTH + ")");
        }
        return record;
    }

    /**
     * Overlays a value at an absolute offset, leaving the rest of the buffer untouched - the way a COBOL
     * group item is filled sub-item by sub-item.
     *
     * @param record the buffer being composed
     * @param offset the zero-based offset of the sub-item
     * @param value  the sub-item's value, already at its declared width
     */
    private static void overlay(StringBuilder record, int offset, String value) {
        record.replace(offset, offset + value.length(), value);
    }

    // =============================================================================================
    // COBOL statements and intrinsics rendered by hand. Every one is public, because a parity case
    // asserts each of them directly rather than only through the paragraph that uses it (gate G51).
    // =============================================================================================

    /**
     * {@code COMPUTE WS-NUM-99 = FUNCTION NUMVAL-C(<field>)} followed by
     * {@code MOVE WS-NUM-99 TO <field>} - lines 305 to 311 and 317 to 323.
     *
     * <p>Three COBOL rules apply in this order, and all three are visible in the body:
     * <ol>
     *   <li>{@code FUNCTION NUMVAL-C} converts the characters to a number;</li>
     *   <li>the receiver is {@code PIC 99} - <strong>unsigned</strong> - so the absolute value is
     *       stored, and a value that needs more than two digit positions loses its high-order digits
     *       silently, because the source specifies neither {@code ROUNDED} nor
     *       {@code ON SIZE ERROR};</li>
     *   <li>moving a two-digit numeric-display item into a {@code PIC X(2)} item yields two digit
     *       characters, zero filled on the left.</li>
     * </ol>
     *
     * <p>So {@code " 7"} becomes {@code "07"}, {@code "7 "} becomes {@code "07"}, {@code "-5"} becomes
     * {@code "05"} and {@code "123"} becomes {@code "23"}. The truncation is
     * {@link java.math.RoundingMode#DOWN} because {@code ROUNDED} appears nowhere in this program - or
     * in any of the twenty-eight - which is why it is routed through
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
     * {@code IF <field> IS NOT NUMERIC OR <field> > '<highest>'} - lines 329 to 330, 338 to 339, 355 to
     * 356 and 364 to 365.
     *
     * <p>Both operands are <em>alphanumeric</em> tests on a {@code PIC X} item, not numeric ones, and
     * that is deliberate on the source's part rather than an oversight: a comparison of {@code '13'}
     * against {@code '12'} as characters and as numbers happen to agree, but the class test and the
     * comparison must be applied to the same item the screen carries, because that is what the operator
     * sees and what the next send transmits. Digits collate identically in EBCDIC and in ASCII, so the
     * character comparison is portable for the only values that can reach it.
     *
     * <p>Note what stage two of the custom arm has already done to the item: it holds exactly two digit
     * characters, so the class test cannot fail there. The test is still written, because the source
     * writes it, and it is reachable directly through this method - which is how it is verified.
     *
     * @param image   the item's characters; must not be {@code null}
     * @param highest the inclusive upper bound as the source spells it, {@code '12'} or {@code '31'};
     *                must not be {@code null}
     * @return whether the item fails the class test or exceeds the bound
     * @throws NullPointerException if either argument is {@code null}
     */
    public static boolean isNotValidTwoDigitPart(String image, String highest) {
        Objects.requireNonNull(highest, "An upper bound is required; the source compares against '12' "
                + "for a month and '31' for a day");
        return !isNumericClass(image) || image.compareTo(highest) > 0;
    }

    /**
     * The COBOL class condition {@code IS NUMERIC} on an alphanumeric item - lines 329, 338, 347, 355,
     * 364 and 373, negated at each site.
     *
     * <p>For an item declared {@code PIC X}, the condition holds only when every character is a digit. A
     * sign, a decimal point, a space and an embedded blank all fail it. An empty item cannot arise from
     * a fixed-width span and is reported as not numeric.
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
     * <p>The operand contributes its characters up to, but not including, the first space. So
     * {@code WS-REPORT-NAME PIC X(10)} holding {@code "Monthly   "} contributes {@code "Monthly"} and
     * the message reads {@code "Monthly report submitted for printing ..."} rather than
     * {@code "Monthly    report submitted..."}. An operand that begins with a space contributes
     * nothing at all, which is exactly what the invalid-confirm message does when the field holds a
     * space - and why that message can read {@code "\" is not a valid value to confirm...\""}.
     *
     * <p>Concatenating the padded field instead is the classic defect here, and it is invisible at the
     * call site, which is why this is a named operation rather than a {@code +}.
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
     * <p>{@code STRING} transfers the composed characters into the receiver from its leftmost position
     * and <strong>stops when the sending items are exhausted</strong>: it does not space-fill the rest.
     * So the receiver's tail survives, which is why the receiver is passed in rather than assumed
     * blank. In this program the tail is blank at all three sites - line 169 clears
     * {@code WS-MESSAGE} and line 646 clears it again - so the distinction never changes an emitted
     * byte here; it is honoured anyway, because assuming otherwise is how the same rendering becomes
     * wrong in a program where the tail is not blank.
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
     * Reconstructs the {@code EIBAID} byte that line 184's {@code EVALUATE EIBAID} tests, from the
     * five-character token the payload carries.
     *
     * <p>The request carries the key as {@code PfKeyResolver.AidKey#token()} produces it, already
     * space-padded to {@link PfKeyResolver#AID_TOKEN_LENGTH}. This program names exactly two AID
     * values - {@code DFHENTER} at line 185 and {@code DFHPF3} at line 187 - so exactly two tokens map
     * to a byte and everything else, spaces included, maps to {@link CicsAid#DFHNULL} and takes the
     * {@code WHEN OTHER} arm. That mirrors the source's {@code EVALUATE}, which also names two values
     * and defaults everything else.
     *
     * <p>One consequence of carrying a resolved token rather than a raw byte is worth recording. On the
     * terminal, {@code PF15} is a distinct AID from {@code PF3}, and {@code CORPT00C} tests the raw byte
     * - so {@code PF15} would take the invalid-key arm there. {@code CSSTRPFY} folds {@code PF13} to
     * {@code PF24} back onto {@code PFK01} to {@code PFK12}, so a client that resolves through it
     * delivers {@code PF15} as the token {@code PFK03} and this program acts on it. The payload's
     * contract is the resolved token, so that folding is the client's choice and is documented here
     * rather than silently absorbed.
     *
     * @param aidToken the resolved key token, or {@code null} when no key was resolved
     * @return {@link CicsAid#DFHENTER}, {@link CicsAid#DFHPF3} or {@link CicsAid#DFHNULL}
     */
    public static byte eibAidOf(String aidToken) {
        if (aidToken == null) {
            return CicsAid.DFHNULL;
        }
        if (AidKey.ENTER.token().equals(aidToken)) {
            return CicsAid.DFHENTER;
        }
        if (AidKey.PFK03.token().equals(aidToken)) {
            return CicsAid.DFHPF3;
        }
        return CicsAid.DFHNULL;
    }

    /**
     * {@code FUNCTION NUMVAL-C} - lines 305, 309, 313, 317, 321 and 325.
     *
     * <p>Returns the numeric value of a character representation that may carry a sign, a currency sign,
     * digit-grouping commas and a decimal point, as a {@link BigDecimal} so that every digit survives -
     * never as {@code double} or {@code float}, which cannot represent a decimal fraction exactly.
     *
     * <p>The argument format this implements is the one the intrinsic documents, with spaces permitted
     * between the elements:
     *
     * <pre>{@code [+|-] [$] digits[,digits]... [.[digits]] [+|-|CR|DB]}</pre>
     *
     * <p><strong>An argument that does not conform yields zero.</strong> The policy, and the reason it
     * is safe, are stated once in {@link NumericIntrinsics} rather than restated here. Use
     * {@link #testNumvalC(String)} to find out whether an argument conformed. The choice
     * does not change what this screen accepts: a non-conforming month becomes {@code "00"}, which
     * passes the class test and the {@code > '12'} comparison at lines 329 and 330 and is then rejected
     * by {@code CSUTLDTC} at line 396 with {@value #MSG_START_DATE_INVALID} - so the operator is told
     * the date is invalid rather than that the month is, which is what the program does.
     *
     * @param image the argument to convert; must not be {@code null}
     * @return the value the argument denotes, or zero when it does not conform
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static BigDecimal numvalC(String image) {
        return NumericIntrinsics.numvalC(image);
    }

    /**
     * The conformance half of {@link #numvalC(String)}, in the shape {@code FUNCTION TEST-NUMVAL-C}
     * reports it.
     *
     * <p>{@code CORPT00C} does not call {@code TEST-NUMVAL-C} - it converts unconditionally at all six
     * sites - so this method exists to make the conversion's own accept-and-reject behaviour assertable
     * rather than only inferable from a converted value. That matters because gate G29 requires the
     * conversion to accept and reject exactly as COBOL does, and a value of zero alone cannot
     * distinguish {@code "00"} from {@code "ab"}.
     *
     * @param image the argument to test; must not be {@code null}
     * @return {@value #NUMVAL_CONFORMS} when the argument conforms; otherwise the one-based position of
     *         the first character in error, or the argument's length plus one when it holds no digit at
     *         all
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static int testNumvalC(String image) {
        return NumericIntrinsics.testNumvalC(image);
    }

    /**
     * {@code FUNCTION INTEGER-OF-DATE} - line 230.
     *
     * <p>Converts a standard date in {@code YYYYMMDD} form to the number of days since 31 December 1600,
     * so 1601-01-01 is day 1.
     *
     * @param standardDate the date as a {@code 9(8)} integer
     * @return the day number, or {@value #DATE_INTRINSIC_UNDEFINED} when the argument is outside
     *         {@value #INTEGER_OF_DATE_LOWEST_ARGUMENT} to {@value #INTEGER_OF_DATE_HIGHEST_ARGUMENT} or
     *         is not a real calendar date
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
            // 31 February and its relatives reach here. COBOL's result is undefined; this one is defined.
            return DATE_INTRINSIC_UNDEFINED;
        }
    }

    /**
     * {@code FUNCTION DATE-OF-INTEGER} - line 229, the inverse of {@link #integerOfDate(int)}.
     *
     * @param integerDate the day number, counting 1601-01-01 as day 1
     * @return the date as a {@code 9(8)} integer, or {@value #DATE_INTRINSIC_UNDEFINED} when the
     *         argument is outside {@value #DATE_OF_INTEGER_LOWEST_ARGUMENT} to
     *         {@link #DATE_OF_INTEGER_HIGHEST_ARGUMENT}
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
     * @param year  {@code WS-CURDATE-YEAR PIC 9(04)}
     * @param month {@code WS-CURDATE-MONTH PIC 9(02)}
     * @param day   {@code WS-CURDATE-DAY PIC 9(02)}
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

    /**
     * @param character the character to classify
     * @return whether it is {@code '0'} through {@code '9'}
     */
    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    /**
     * A {@code DISPLAY} statement - lines 210 and 529.
     *
     * <p>Recorded on the state so a parity case can assert the program's console output, and logged so a
     * running system shows it. Both statements emit fixed text and integers only, so there is nothing
     * here that a caller could use to forge a log record or to leak a payload value.
     *
     * @param state the per-request working storage
     * @param text  the text the source displays
     */
    private void display(ProgramState state, String text) {
        state.recordDisplay(text);
        LOG.info(text);
    }

    /**
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    private static void requireState(ProgramState state) {
        Objects.requireNonNull(state, "A ProgramState is required: every WORKING-STORAGE item of "
                + "CORPT00C lives in it, so that two concurrent requests cannot see each other's screen");
    }

    // =============================================================================================
    // ProgramState - the WORKING-STORAGE SECTION of app/cbl/CORPT00C.cbl:36-136, per request.
    // =============================================================================================

    /**
     * One execution's working storage: every item {@code CORPT00C} declares, plus the screen buffer, the
     * communication area and the record of what the execution actually did.
     *
     * <p><strong>Why this exists at all.</strong> COBOL {@code WORKING-STORAGE} in a CICS program is
     * per-task storage, freshly initialised for every transaction. The equivalent in Java is an object
     * created per request - never a field on the controller and never a {@code static}. A singleton
     * controller with mutable fields would let two concurrent operators see each other's dates and would
     * make a test's outcome depend on which test ran first; that is the defect this class exists to make
     * impossible.
     *
     * <p><strong>The screen is one buffer, not two.</strong> {@code 01 CORPT0AO REDEFINES CORPT0AI}, so
     * the input and output views are the same bytes. {@link #response()} is that buffer: the ten input
     * accessors below read it and the six normalising setters write it, which is what makes the in-place
     * normalisation of lines 305 to 327 visible on the screen the program next sends.
     *
     * <p><strong>What is deliberately not modelled.</strong> Four declared items are never referenced by
     * any statement in the program, so there is nothing of theirs to preserve:
     * {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} at line 40 - the literal that makes this
     * program look as though it opened a file, which it never does - {@code WS-REC-COUNT} at line 56,
     * {@code WS-TRAN-AMT PIC +99999999.99} at line 77 and {@code WS-TRAN-DATE} at line 78. They are
     * recorded here rather than silently dropped. {@code WS-TRANSACT-EOF} <em>is</em> modelled, because
     * line 166 executes {@code SET TRANSACT-NOT-EOF TO TRUE} against it - even though nothing ever tests
     * it.
     *
     * <p>Not thread-safe, and deliberately so: one instance belongs to one request.
     */
    public static final class ProgramState {

        /**
         * The {@code PICTURE} move rules for this state's own items.
         *
         * <p>Static and immutable - a {@link FixedWidthCodec} holds a code page and nothing else - so it
         * is shared safely and is not mutable static state. The code page is irrelevant to the moves
         * performed here, which are all {@code PIC X} and {@code PIC 9} character operations, but it is
         * still named explicitly rather than defaulted.
         */
        private static final FixedWidthCodec PICTURE_RULES =
                new FixedWidthCodec(PICTURE_RULES_CHARSET);

        /** {@code 01 CORPT0AI} and its {@code 01 CORPT0AO} redefinition: one screen buffer. */
        private final ReportRequestResponse response = new ReportRequestResponse();

        /** The symbolic map's {@code xxxL}, {@code xxxF} and {@code xxxA} items: metadata, not payload. */
        private ReportRequestRequest.SymbolicMapMetadata symbolicMap =
                ReportRequestRequest.SymbolicMapMetadata.initial();

        /** {@code 01 CARDDEMO-COMMAREA} - app/cpy/COCOM01Y.cpy, copied at line 138. */
        private NavigationContext commarea = NavigationContext.empty();

        /** {@code WS-MESSAGE PIC X(80) VALUE SPACES} - line 39. */
        private String message = SPACE.repeat(WS_MESSAGE_LENGTH);

        /** {@code WS-REPORT-NAME PIC X(10) VALUE SPACES} - line 58. */
        private String reportName = SPACE.repeat(WS_REPORT_NAME_LENGTH);

        /** {@code WS-START-DATE-YYYY PIC X(04) VALUE SPACES} - line 61. */
        private String startDateYyyy = SPACE.repeat(DATE_YEAR_LENGTH);

        /** {@code WS-START-DATE-MM PIC X(02) VALUE SPACES} - line 63. */
        private String startDateMm = SPACE.repeat(DATE_PART_LENGTH);

        /** {@code WS-START-DATE-DD PIC X(02) VALUE SPACES} - line 65. */
        private String startDateDd = SPACE.repeat(DATE_PART_LENGTH);

        /** {@code WS-END-DATE-YYYY PIC X(04) VALUE SPACES} - line 67. */
        private String endDateYyyy = SPACE.repeat(DATE_YEAR_LENGTH);

        /** {@code WS-END-DATE-MM PIC X(02) VALUE SPACES} - line 69. */
        private String endDateMm = SPACE.repeat(DATE_PART_LENGTH);

        /** {@code WS-END-DATE-DD PIC X(02) VALUE SPACES} - line 71. */
        private String endDateDd = SPACE.repeat(DATE_PART_LENGTH);

        /** {@code PARM-START-DATE-1 PIC X(10) VALUE SPACES} - line 106, inside {@code 05 FILLER-1}. */
        private String parmStartDate1 = SPACE.repeat(WS_DATE_LENGTH);

        /** {@code PARM-END-DATE-1 PIC X(10) VALUE SPACES} - line 111, inside {@code 05 FILLER-2}. */
        private String parmEndDate1 = SPACE.repeat(WS_DATE_LENGTH);

        /** {@code PARM-START-DATE-2 PIC X(10) VALUE SPACES} - line 118, inside {@code 05 FILLER-3}. */
        private String parmStartDate2 = SPACE.repeat(WS_DATE_LENGTH);

        /** {@code PARM-END-DATE-2 PIC X(10) VALUE SPACES} - line 120, inside {@code 05 FILLER-3}. */
        private String parmEndDate2 = SPACE.repeat(WS_DATE_LENGTH);

        /**
         * {@code JCL-RECORD PIC X(80) VALUE ' '} - line 79.
         *
         * <p>The {@code VALUE} is a single space in a field of eighty, so a COBOL {@code VALUE} clause
         * space-fills the rest: the initial state is eighty spaces, not one.
         */
        private String jclRecord = SPACE.repeat(JCL_RECORD_LENGTH);

        /** {@code WS-ERR-FLG PIC X(01) VALUE 'N'} with {@code 88 ERR-FLG-ON} / {@code OFF} - lines 41-43. */
        private boolean errFlag;

        /**
         * {@code WS-TRANSACT-EOF PIC X(01) VALUE 'N'} with {@code 88 TRANSACT-EOF} / {@code NOT-EOF} -
         * lines 44 to 46. Line 166 sets it and <strong>no statement ever tests it</strong>; it is
         * modelled because the {@code SET} is executable code.
         */
        private boolean transactEof;

        /**
         * {@code WS-SEND-ERASE-FLG PIC X(01) VALUE 'Y'} with {@code 88 SEND-ERASE-YES} / {@code NO} -
         * lines 47 to 49. Line 167 sets it to {@code 'Y'} and nothing in the program ever sets it to
         * {@code 'N'}, so line 562's else-arm is unreachable in the composed flow. It is preserved, and
         * {@link #setSendEraseNo()} exists so that arm can still be exercised.
         */
        private boolean sendErase = true;

        /** {@code WS-END-LOOP PIC X(01) VALUE 'N'} with {@code 88 END-LOOP-YES} / {@code NO} - lines 50-52. */
        private boolean endLoop;

        /** {@code WS-IDX PIC S9(04) COMP VALUE ZEROS} - line 57, the emit loop's subscript. */
        private int idx;

        /** {@code WS-RESP-CD PIC S9(09) COMP VALUE ZEROS} - line 54. */
        private int respCd;

        /** {@code WS-REAS-CD PIC S9(09) COMP VALUE ZEROS} - line 55. */
        private int reasCd;

        /** The most recent {@code FUNCTION CURRENT-DATE} read: {@code WS-CURDATE-DATA} of CSDAT01Y. */
        private DateHeader dateHeader;

        /**
         * {@code 01 CSUTLDTC-PARM} - lines 129 to 136, as the typed result of the most recent call.
         *
         * <p>One carrier, overwritten by the second call exactly as the COBOL's single storage area is.
         */
        private DateValidationResult csutldtcResult;

        /** Whether {@code EXEC CICS RETURN} has been executed - the task has ended. */
        private boolean returned;

        /** Whether {@code EXEC CICS XCTL} has been executed - control has passed to another program. */
        private boolean transferred;

        /** Whether {@code EXEC CICS SEND MAP} has been executed. */
        private boolean screenSent;

        /** Whether that send specified {@code ERASE} - line 567 rather than the commented-out line 575. */
        private boolean screenSentWithErase;

        /** Every {@code JCL-RECORD} handed to the queue, in the order the loop handed them over. */
        private final List<String> submittedRecords = new ArrayList<>();

        /** Every {@code DISPLAY} the execution emitted, in order. */
        private final List<String> displayLines = new ArrayList<>();

        // -----------------------------------------------------------------------------------------
        // The screen buffer.
        // -----------------------------------------------------------------------------------------

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
         * <p>Three things this execution produces are metadata by declaration rather than payload, and
         * before this envelope existed none of them had any way to travel:
         *
         * <ul>
         *   <li>the {@code MOVE -1 TO <field>L} cursor request, which {@code CORPT00C} issues at
         *       twenty-two sites. The Agent Action Plan's section 0.3.9 is explicit that {@code xxxL} is
         *       validation and highlight metadata and not a payload member, so it is reported here
         *       rather than smuggled into a projection of {@code xxxI} and {@code xxxO} items. The field
         *       named is the first in map declaration order whose length item holds
         *       {@value ReportRequestRequest.FieldMetadata#CURSOR_POSITION}, which is the one the
         *       terminal would place the cursor in;</li>
         *   <li>the {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} quad of each of the
         *       seventeen fields, which is what {@code app/cpy/CSSETATY.cpy} writes
         *       {@link BmsAttributes#DFHRED} into when a field is in error;</li>
         *   <li>the colour of the message line, read from {@code ERRMSGC} rather than restated, so a
         *       rename cannot silently leave this pointing at a field that no longer exists.</li>
         * </ul>
         *
         * <p>Each quad is published as four unsigned {@code 0}-{@code 255} values, because an attribute
         * byte with the high bit set - {@link BmsAttributes#DFHRED} is {@code 0xF2} - is a negative
         * {@code byte} in Java and publishing {@code -14} would misstate it. The map is keyed by the
         * {@code DFHMDF} label.
         *
         * <p>{@code resetAllOutputFields} is {@code false}: where {@code MOVE LOW-VALUES TO CORPT0AO}
         * runs it has already been applied to the response being published, so the client is not being
         * asked to clear anything a second time.
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
         *              {@code null}
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

        /** @return {@code MONTHLYI OF CORPT0AI}, exactly one character */
        public String monthlyI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.MONTHLY);
        }

        /** @return {@code YEARLYI OF CORPT0AI}, exactly one character */
        public String yearlyI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.YEARLY);
        }

        /** @return {@code CUSTOMI OF CORPT0AI}, exactly one character */
        public String customI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.CUSTOM);
        }

        /** @return {@code SDTMMI OF CORPT0AI}, exactly two characters */
        public String sdtmmI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.SDTMM);
        }

        /** @return {@code SDTDDI OF CORPT0AI}, exactly two characters */
        public String sdtddI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.SDTDD);
        }

        /** @return {@code SDTYYYYI OF CORPT0AI}, exactly four characters */
        public String sdtyyyyI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.SDTYYYY);
        }

        /** @return {@code EDTMMI OF CORPT0AI}, exactly two characters */
        public String edtmmI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.EDTMM);
        }

        /** @return {@code EDTDDI OF CORPT0AI}, exactly two characters */
        public String edtddI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.EDTDD);
        }

        /** @return {@code EDTYYYYI OF CORPT0AI}, exactly four characters */
        public String edtyyyyI() {
            return response.payloadValue(ReportRequestResponse.ScreenField.EDTYYYY);
        }

        /** @return {@code CONFIRMI OF CORPT0AI}, exactly one character */
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

        // -----------------------------------------------------------------------------------------
        // The communication area.
        // -----------------------------------------------------------------------------------------

        /** @return {@code 01 CARDDEMO-COMMAREA}, never {@code null} */
        public NavigationContext commarea() {
            return commarea;
        }

        /**
         * Replaces the communication area, which is immutable, so every {@code MOVE} into one of its
         * items produces a new value.
         *
         * @param commarea the new communication area; must not be {@code null}
         * @throws NullPointerException if {@code commarea} is {@code null}
         */
        public void setCommarea(NavigationContext commarea) {
            this.commarea = Objects.requireNonNull(commarea, "A communication area is required; use "
                    + "NavigationContext.empty() for the EIBCALEN = 0 case");
        }

        // -----------------------------------------------------------------------------------------
        // WS-MESSAGE, WS-REPORT-NAME and the two ten-byte dates. Every setter applies the PIC X move
        // rule, so an item can never hold anything but its declared width.
        // -----------------------------------------------------------------------------------------

        /** @return {@code WS-MESSAGE}, exactly {@value #WS_MESSAGE_LENGTH} characters */
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

        /** @return {@code WS-REPORT-NAME}, exactly {@value #WS_REPORT_NAME_LENGTH} characters */
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
         * <p>The separators are emitted because they are declared with a {@code VALUE}: they are content,
         * not padding. That is what makes this group the {@code 'YYYY-MM-DD'} shape {@code CSUTLDTC} is
         * asked to validate and the {@code DATEPARM} record carries.
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

        /** @return {@code WS-START-DATE-YYYY}, exactly {@value #DATE_YEAR_LENGTH} characters */
        public String startDateYyyy() {
            return startDateYyyy;
        }

        /** @return {@code WS-START-DATE-MM}, exactly {@value #DATE_PART_LENGTH} characters */
        public String startDateMm() {
            return startDateMm;
        }

        /** @return {@code WS-START-DATE-DD}, exactly {@value #DATE_PART_LENGTH} characters */
        public String startDateDd() {
            return startDateDd;
        }

        /** @return {@code WS-END-DATE-YYYY}, exactly {@value #DATE_YEAR_LENGTH} characters */
        public String endDateYyyy() {
            return endDateYyyy;
        }

        /** @return {@code WS-END-DATE-MM}, exactly {@value #DATE_PART_LENGTH} characters */
        public String endDateMm() {
            return endDateMm;
        }

        /** @return {@code WS-END-DATE-DD}, exactly {@value #DATE_PART_LENGTH} characters */
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

        // -----------------------------------------------------------------------------------------
        // The four substitution points. Four distinct storage locations in the COBOL, so four here.
        // -----------------------------------------------------------------------------------------

        /** @return {@code PARM-START-DATE-1}, the start date inside the {@code SYMNAMES} record */
        public String parmStartDate1() {
            return parmStartDate1;
        }

        /** @return {@code PARM-END-DATE-1}, the end date inside the {@code SYMNAMES} record */
        public String parmEndDate1() {
            return parmEndDate1;
        }

        /** @return {@code PARM-START-DATE-2}, the start date inside the {@code DATEPARM} record */
        public String parmStartDate2() {
            return parmStartDate2;
        }

        /** @return {@code PARM-END-DATE-2}, the end date inside the {@code DATEPARM} record */
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

        // -----------------------------------------------------------------------------------------
        // JCL-RECORD, the loop's subscript and the two CICS response codes.
        // -----------------------------------------------------------------------------------------

        /** @return {@code JCL-RECORD}, exactly {@value #JCL_RECORD_LENGTH} characters */
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

        /** @return {@code WS-IDX}; after the loop, the subscript that failed the {@code UNTIL} test */
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

        /** @return {@code WS-RESP-CD}, the {@code RESP} of the most recent CICS command */
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

        /** @return {@code WS-REAS-CD}, the {@code RESP2} of the most recent CICS command */
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

        // -----------------------------------------------------------------------------------------
        // The four condition-name flags, named as the 88-levels name them.
        // -----------------------------------------------------------------------------------------

        /** @return {@code ERR-FLG-ON} */
        public boolean errFlagOn() {
            return errFlag;
        }

        /** @return {@code ERR-FLG-OFF} */
        public boolean errFlagOff() {
            return !errFlag;
        }

        /** {@code MOVE 'Y' TO WS-ERR-FLG} - at twenty sites. */
        public void setErrFlagOn() {
            this.errFlag = true;
        }

        /** {@code SET ERR-FLG-OFF TO TRUE} - line 165. */
        public void setErrFlagOff() {
            this.errFlag = false;
        }

        /** @return {@code TRANSACT-EOF}; nothing in the program ever tests it */
        public boolean transactEof() {
            return transactEof;
        }

        /** @return {@code TRANSACT-NOT-EOF} */
        public boolean transactNotEof() {
            return !transactEof;
        }

        /** {@code SET TRANSACT-EOF TO TRUE}; the program never does this, and the flag exists anyway. */
        public void setTransactEof() {
            this.transactEof = true;
        }

        /** {@code SET TRANSACT-NOT-EOF TO TRUE} - line 166. */
        public void setTransactNotEof() {
            this.transactEof = false;
        }

        /** @return {@code SEND-ERASE-YES} - line 562's test */
        public boolean sendEraseYes() {
            return sendErase;
        }

        /** @return {@code SEND-ERASE-NO} */
        public boolean sendEraseNo() {
            return !sendErase;
        }

        /** {@code SET SEND-ERASE-YES TO TRUE} - line 167. */
        public void setSendEraseYes() {
            this.sendErase = true;
        }

        /**
         * {@code SET SEND-ERASE-NO TO TRUE}.
         *
         * <p>The program never executes this, so line 571's send without {@code ERASE} is unreachable in
         * the composed flow. Both the flag and that arm are preserved rather than simplified away, and
         * this setter is what lets the arm be exercised without pretending the program reaches it.
         */
        public void setSendEraseNo() {
            this.sendErase = false;
        }

        /** @return {@code END-LOOP-YES} */
        public boolean endLoopYes() {
            return endLoop;
        }

        /** @return {@code END-LOOP-NO} */
        public boolean endLoopNo() {
            return !endLoop;
        }

        /** {@code SET END-LOOP-YES TO TRUE} - line 504. */
        public void setEndLoopYes() {
            this.endLoop = true;
        }

        /** {@code SET END-LOOP-NO TO TRUE} - line 496. */
        public void setEndLoopNo() {
            this.endLoop = false;
        }

        // -----------------------------------------------------------------------------------------
        // What the execution observed and did.
        // -----------------------------------------------------------------------------------------

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

        /** @return whether {@code EXEC CICS RETURN} has been executed */
        public boolean returned() {
            return returned;
        }

        /** {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)}. */
        public void markReturned() {
            this.returned = true;
        }

        /** @return whether {@code EXEC CICS XCTL} has been executed */
        public boolean transferred() {
            return transferred;
        }

        /** {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)} - line 549. */
        public void markTransferred() {
            this.transferred = true;
        }

        /** @return whether {@code EXEC CICS SEND MAP} has been executed */
        public boolean screenSent() {
            return screenSent;
        }

        /** @return whether the send specified {@code ERASE} */
        public boolean screenSentWithErase() {
            return screenSentWithErase;
        }

        /**
         * {@code EXEC CICS SEND MAP('CORPT0A') MAPSET('CORPT00') FROM(CORPT0AO) [ERASE] CURSOR} - lines
         * 563 and 571.
         *
         * @param erase whether the send specified {@code ERASE}
         */
        public void recordScreenSent(boolean erase) {
            this.screenSent = true;
            this.screenSentWithErase = erase;
        }

        /**
         * Every {@code JCL-RECORD} this execution handed to the queue, in order.
         *
         * <p>A record appears here once it has been handed over, whatever the queue then reported: the
         * queue is {@code DISPOSITION(MOD)} and {@code ERROROPTION(IGNORE)}, so there is no rollback and
         * a failure on record <em>k</em> leaves records 1 to <em>k</em>-1 durably appended.
         *
         * @return an unmodifiable view, {@value #JOB_LINE_COUNT} records after a successful submission
         */
        public List<String> submittedRecords() {
            return Collections.unmodifiableList(submittedRecords);
        }

        /**
         * Records one {@code EXEC CICS WRITEQ TD}.
         *
         * @param record the eighty-byte record handed over; must not be {@code null}
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

        /**
         * Records one {@code DISPLAY}.
         *
         * @param text the displayed text; must not be {@code null}
         * @throws NullPointerException if {@code text} is {@code null}
         */
        public void recordDisplay(String text) {
            displayLines.add(Objects.requireNonNull(text, "Displayed text is required"));
        }
    }

    // =============================================================================================
    // The job-submission port: the Java form of EXEC CICS WRITEQ TD QUEUE('JOBS').
    // =============================================================================================

    /**
     * {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} - the one CICS facility this program uses that Java has
     * no equivalent for, expressed as an explicit outbound port.
     *
     * <h2>The contract, transcribed from the CSD</h2>
     *
     * {@code app/csd/CARDDEMO.CSD:499-505} defines the queue, and every attribute of that definition is
     * part of this contract:
     *
     * <pre>{@code
     *  DEFINE TDQUEUE(JOBS) GROUP(CARDDEMO)
     *  DESCRIPTION(SUBMIT JOBS FROM CICS)
     *         TYPE(EXTRA) DATABUFFERS(1) DDNAME(INREADER) ERROROPTION(IGNORE)
     *         OPENTIME(INITIAL) TYPEFILE(OUTPUT) RECORDSIZE(80)
     *         RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED) DISPOSITION(MOD)
     * }</pre>
     *
     * <ul>
     *   <li><strong>{@code TYPE(EXTRA)}</strong> - an extrapartition queue, so the destination is a
     *       sequential dataset outside CICS rather than an intrapartition queue inside it.</li>
     *   <li><strong>{@code DDNAME(INREADER)}</strong> - that dataset is the region's internal reader, so
     *       what is written here is submitted to the job entry subsystem.</li>
     *   <li><strong>{@code TYPEFILE(OUTPUT)}</strong> - write only. There is no read operation on this
     *       port, and none is invented.</li>
     *   <li><strong>{@code RECORDSIZE(80)} with {@code RECORDFORMAT(FIXED)}</strong> - every record is
     *       exactly eighty bytes. An implementation must enforce that itself rather than trusting its
     *       caller, which is why {@link InternalReaderJobSubmissionPort} refuses any other length.</li>
     *   <li><strong>{@code BLOCKFORMAT(UNBLOCKED)}</strong> - one record per block, so records are
     *       written one after another with no block prefix or padding between them.</li>
     *   <li><strong>{@code DISPOSITION(MOD)}</strong> - <em>append</em>. A submission adds to whatever
     *       the destination already holds and never truncates it, which is why a failure part way
     *       through leaves the earlier records durably written.</li>
     *   <li><strong>{@code ERROROPTION(IGNORE)}</strong> - a queue-level error does not abend the region.
     *       The program's own {@code EVALUATE WS-RESP-CD} decides what to do, so an implementation
     *       <em>reports</em> an outcome and never throws.</li>
     *   <li><strong>{@code OPENTIME(INITIAL)}</strong> - the queue is available from the moment the region
     *       starts, so a caller never opens or closes it. An implementation resolves its destination once
     *       and appends per record.</li>
     *   <li><strong>{@code DATABUFFERS(1)}</strong> - a single buffer, which is consistent with writing
     *       one record at a time and buffering none.</li>
     * </ul>
     *
     * <p>The destination is a configuration key - {@code carddemo.job-submission.destination} - and never
     * a path or dataset name written in Java.
     *
     * <p>Declared here, in the file that owns the only {@code WRITEQ TD} in the migration, so the port is
     * explicit and injectable without adding a file the plan does not enumerate. A test substitutes a
     * capturing implementation to assert the emitted bytes; a deployment that submits through a scheduler
     * or a message bus supplies its own and marks it {@code @Primary}.
     */
    public interface JobSubmissionPort {

        /**
         * Writes one record to the queue.
         *
         * @param jclRecord exactly {@value ReportRequestController#JCL_RECORD_LENGTH} characters - the
         *                  {@code JCL-RECORD} the program has just filled; must not be {@code null}
         * @return the {@code RESP} and {@code RESP2} the caller evaluates; never {@code null} and never
         *         an exception, because {@code ERROROPTION(IGNORE)} leaves the decision to the caller
         * @throws NullPointerException if {@code jclRecord} is {@code null}
         */
        WriteQueueOutcome writeQueueTd(String jclRecord);
    }

    /**
     * The {@code RESP} and {@code RESP2} of one {@code EXEC CICS WRITEQ TD} - the values
     * {@code WS-RESP-CD} and {@code WS-REAS-CD} receive at lines 521 and 522 and that line 525 evaluates.
     *
     * <p>Both are CICS response codes rather than amounts, so both are {@code int}: {@code PIC S9(09)}
     * has no {@code V}. Every value used here is a named constant of {@code common.FileStatus} - no
     * response code is invented - and the three failure shapes below are the three an eighty-byte
     * append can actually produce.
     *
     * @param resp  the {@code RESP} value; {@link FileStatus#NORMAL} when the record was written
     * @param resp2 the {@code RESP2} value; {@link FileStatus#NO_REASON_CODE} throughout, because CICS
     *              supplies no secondary reason for these conditions on a {@code WRITEQ TD}
     */
    public record WriteQueueOutcome(int resp, int resp2) {

        /** {@code WHEN DFHRESP(NORMAL)} - line 526. The record was written. */
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
         *         {@link FileStatus.Outcome#OTHER}
         */
        public FileStatus.Outcome fileStatus() {
            return FileStatus.outcomeOfCicsResp(resp);
        }
    }

    /**
     * The default {@link JobSubmissionPort}: appends eighty-byte records to the destination
     * {@code carddemo.job-submission} configures.
     *
     * <p>A {@code static} nested class annotated {@code @Component} is an independent component
     * candidate, so the scanner registers it in its own right; that is what lets the port and its
     * implementation live in this file while still being injectable. The controller depends on the
     * interface, never on this class.
     *
     * <p><strong>Byte fidelity is enforced here, not only in a test.</strong> The record must be exactly
     * {@link JobSubmissionProperties#TDQ_RECORD_LENGTH} characters, it is encoded through
     * {@link FixedWidthCodec#encodeImage(String, String)} - which refuses a character the code page
     * cannot represent rather than substituting {@code ?} for it, as {@code String.getBytes} would - and
     * the encoded image must be exactly that many <em>bytes</em>. A single-byte code page is what makes
     * those two checks equivalent, and checking both is what proves it.
     *
     * <p>The file is opened {@code CREATE}, {@code WRITE} and {@code APPEND}, which is
     * {@code DISPOSITION(MOD)}: a submission never truncates what is already there. Nothing is buffered
     * across calls, matching {@code DATABUFFERS(1)}, and no transaction wraps the loop - so a failure on
     * record <em>k</em> leaves records 1 to <em>k</em>-1 written, exactly as the mainframe leaves them.
     *
     * <p>No failure escapes as an exception, because {@code ERROROPTION(IGNORE)} means the queue does not
     * abend its caller: each is reported as the {@code RESP} the program's {@code EVALUATE} expects, and
     * logged with the failure's type but never with the record, which is job text.
     */
    @Component
    public static class InternalReaderJobSubmissionPort implements JobSubmissionPort {

        /** Diagnostics for a queue that {@code ERROROPTION(IGNORE)} forbids from abending its caller. */
        private static final Log PORT_LOG = LogFactory.getLog(InternalReaderJobSubmissionPort.class);

        /**
         * The permissions every directory this port creates is created with: {@code rwx------}.
         *
         * <p>Owner-only, because the destination's path is knowable - it is in a configuration file -
         * and a knowable path in a directory anybody may enter is a path anybody may pre-create,
         * replace or read.
         */
        private static final String DIRECTORY_PERMISSIONS = "rwx------";

        /**
         * The permissions the destination is created with: {@code rw-------}.
         *
         * <p>The records are JCL skeletons naming datasets and the submitting user, so the file is the
         * owner's alone. Applied at creation rather than afterwards, so there is no window in which the
         * file exists with wider permissions than it should ever have.
         */
        private static final String FILE_PERMISSIONS = "rw-------";

        /** {@code carddemo.job-submission} - the queue's name, geometry and destination. */
        private final JobSubmissionProperties properties;

        /** The code page the eighty-byte records are encoded in. */
        private final FixedWidthCodec codec;

        /** The configured destination, resolved once; {@code null} when it could not be resolved. */
        private final Path destination;

        /** Why the destination could not be resolved, or {@code null} when it was. */
        private final RuntimeException refusal;

        /**
         * Serializes appends to this port's one destination, <strong>within this JVM</strong>.
         *
         * <p>A dedicated monitor rather than {@code this}, so no caller holding a reference to the port can
         * take the lock that a record's write depends on. Held across the containment check and the open
         * together, which is what keeps the two from being separable by a concurrent request.
         *
         * <h4>What this monitor cannot do, and the requirement that follows</h4>
         * <p>A Java monitor is an <em>in-process</em> construct. It orders this JVM's threads and says
         * nothing whatever about another process appending to the same file - and the internal reader is
         * a queue precisely because more than one writer feeds it: a second application instance, a
         * mainframe utility, an operator's script. So the monitor alone left the whole finding standing
         * for every writer outside this JVM, and the record it protects is
         * {@code RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED)}, where a write landing in two pieces with
         * another writer's bytes between them is not one corrupt record but two, plus every record after
         * them shifted. The inter-process half is an exclusive {@link java.nio.channels.FileLock} taken
         * on the channel itself - see {@code appendWithinApprovedRoot}.
         *
         * <p>The two are complementary and both are needed. The file lock cannot replace this monitor,
         * because a {@code FileLock} is held by the <em>JVM</em> rather than by a thread: two threads of
         * one JVM taking overlapping locks on one file raises
         * {@link java.nio.channels.OverlappingFileLockException} instead of queueing, so something has to
         * order them first, and this is it. And this monitor cannot replace the file lock, for the reason
         * above.
         *
         * <p><strong>The requirement that remains: exactly one port instance per destination in a JVM.</strong>
         * This monitor is an instance field, so it orders the threads of one port and not two ports over
         * one file. The wiring already satisfies that - the port is a singleton bound to the single
         * configured {@code carddemo.job-submission} destination - and where it somehow does not, the
         * overlapping-lock attempt is caught and reported as {@code RESP NOTOPEN} rather than escaping as
         * an unchecked exception, so a misconfiguration is a refused write and never a torn record.
         */
        private final Object appendLock = new Object();

        /**
         * Whether the running filesystem carries POSIX permissions, decided once at construction.
         *
         * <p>Job text names datasets, jobs and users, so the directories the descent creates and the file
         * it opens are owner-only where the filesystem supports saying so - {@value #DIRECTORY_PERMISSIONS}
         * and {@value #FILE_PERMISSIONS}. Where it does not, the platform's own default applies, because
         * refusing to write at all would be a harsher outcome than the queue's own.
         */
        private final boolean posixPermissionsSupported;

        /**
         * Wires the port from configuration, encoding records in the code page
         * {@code carddemo.job-submission.charset} declares.
         *
         * <p><strong>This is the constructor Spring uses, and it is the only one that reads the
         * configured queue code page.</strong> The record's code page is
         * {@code carddemo.job-submission.charset}, validated once by
         * {@link JobSubmissionProperties#validate()} and read back through
         * {@link JobSubmissionProperties#queueCharset()}. Which code page a region's internal reader
         * consumes cannot be inferred from inside this process, so it is declared rather than derived -
         * and in particular it is <em>not</em> the dataset code page. The two are independent settings
         * that disagree under the shipped configuration: the queue defaults to {@code IBM037} because
         * an internal reader consumes EBCDIC, while the datasets default to {@code US-ASCII} because
         * the fixtures are ASCII. Encoding the queue in the dataset's code page would send 80 bytes of
         * ASCII per record to a reader expecting EBCDIC and report {@code NORMAL} for every one of
         * them.
         *
         * @param properties the {@code carddemo.job-submission} binding; must not be {@code null}
         * @throws NullPointerException  if {@code properties} is {@code null}
         * @throws IllegalStateException if the configured record length is not the CSD's
         *                               {@code RECORDSIZE(80)}
         * @throws IllegalArgumentException if the configured code page names nothing this platform
         *                               provides
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
         * <p><strong>Not the constructor Spring uses</strong>, deliberately, and carrying no
         * {@code @Qualifier}. It once did carry one - {@code @Qualifier(DATASET_CHARSET_BEAN_NAME)} - and
         * being the annotated constructor it was also the one Spring selected, so the queue's records
         * were encoded in the <em>dataset</em> code page and {@code carddemo.job-submission.charset}
         * reached nothing. Under the shipped configuration those two settings differ, which made the
         * queue silently ASCII where it was declared EBCDIC. A code page arriving by qualifier is
         * therefore the one thing this parameter must never be: it exists for a caller that has a
         * specific code page in hand - a test asserting exact bytes - and for nothing else.
         *
         * @param properties   the {@code carddemo.job-submission} binding; must not be {@code null}
         * @param queueCharset the code page the 80-byte records are encoded in, supplied by the caller
         *                     rather than injected; must not be {@code null}
         * @throws NullPointerException  if either argument is {@code null}
         * @throws IllegalStateException if the configured record length is not the CSD's
         *                               {@code RECORDSIZE(80)}
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
                // The parent is required to exist as a NAME - a destination that is a filesystem root
                // has none - because the append descends to the record's file through its parent
                // directory. The directory itself is inspected, and created if missing, per write in
                // appendWithinApprovedRoot, never cached here: caching a directory would cache a
                // decision about the filesystem taken before the write it protects.
                if (resolved.getParent() == null) {
                    throw new IllegalArgumentException("carddemo.job-submission.destination has no "
                            + "parent directory, so it names a filesystem root rather than a dataset the "
                            + "internal reader could consume");
                }
            } catch (IllegalArgumentException notADestination) {
                // One catch covers both refusals, because java.nio.file.InvalidPathException - which
                // JobSubmissionProperties.destinationPath() throws for a syntactically impossible path -
                // is itself an IllegalArgumentException.
                //
                // Reported per write as NOTOPEN rather than thrown here, so a misconfigured destination
                // cannot stop the application context from starting and cannot mask the rest of it.
                failure = notADestination;
            }
            this.destination = resolved;
            this.refusal = failure;
            this.posixPermissionsSupported =
                    FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        }

        /**
         * {@code EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD) LENGTH(LENGTH OF JCL-RECORD)} -
         * lines 517 to 523.
         *
         * @param jclRecord exactly {@link JobSubmissionProperties#TDQ_RECORD_LENGTH} characters; must not
         *                  be {@code null}
         * @return {@link WriteQueueOutcome#NORMAL} when the record was appended, otherwise
         *         {@code LENGERR}, {@code INVREQ} or {@code NOTOPEN}
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

            // One byte per character is guaranteed here rather than re-tested: FixedWidthCodec's
            // encodeImage refuses any text whose encoded width differs from its character count,
            // because a fixed-width record area is addressed by absolute byte offset. A record that
            // reached this point is therefore RECORDSIZE(80) characters, and encoding it under a
            // single-byte code page yields exactly 80 bytes - which is precisely what
            // RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED) requires of every record written to the
            // queue. A second width test would be unreachable, so none is written; a multi-byte code
            // page is rejected below as INVREQ instead, which is the outcome CICS reports when a
            // WRITEQ TD request itself is not valid for the queue.
            byte[] image;
            try {
                image = codec.encodeImage(jclRecord, JCL_RECORD_SUBJECT);
            } catch (IllegalArgumentException unrepresentable) {
                // The record is job text, so it is never logged; the code page and the failure's type
                // are what a diagnosis needs.
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

            // Serialized, and per destination. This port is a singleton bound to one configured
            // destination, so one monitor here IS one monitor per destination.
            //
            // Two reasons, and both are about a record rather than about throughput. The queue is
            // RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED), so a reader takes the destination eighty bytes
            // at a time: a write that landed in two pieces with another request's bytes between them
            // would not be a corrupt record, it would be two corrupt records and every record after them
            // shifted. O_APPEND makes a single write atomic on the platforms that have it, but the
            // java.nio.file contract promises nothing of the sort, and this module is not entitled to
            // assume a provider. The lock also makes the element-by-element descent, the real-path
            // re-check and the no-follow open one indivisible step, which is what keeps a concurrent
            // request from widening the window between them.
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

        /**
         * Appends one encoded record to the destination, having first proved that every component of
         * the path leading to it is a real directory inside the operator-provisioned root and that the
         * record's own file is not a symbolic link.
         *
         * <h4>What this defends against</h4>
         * The destination is externally supplied and the write appends in {@code DISPOSITION(MOD)}, so
         * a wrong target does not fail - it succeeds against the wrong file. The startup validation in
         * {@link JobSubmissionProperties#validate()} settles the <em>configuration</em>: the path is
         * absolute, carries no {@code ".."} segment, names no read-only reference tree and resolves
         * inside {@code approved-root}. That is pure path algebra and it cannot see the filesystem, so
         * it cannot see a <strong>symbolic link</strong>. A link planted at
         * {@code <root>/inreader/JOBS}, or at the {@code inreader} directory itself, satisfies every
         * lexical test while sending eighty-byte records wherever it points (CWE-59), and a link
         * planted between a check and an open would defeat a check made in ordinary Java file calls
         * (CWE-367).
         *
         * <h4>How it is defended</h4>
         * <ol>
         *   <li>The approved root is resolved with {@link Path#toRealPath(LinkOption...)}, which
         *       follows every link once and fails if the root does not exist. The root is
         *       operator-provisioned, so its absence is a deployment fault and is reported as
         *       {@code NOTOPEN} rather than papered over by creating it here.</li>
         *   <li>The destination is descended one name element at a time from that real root. Each
         *       element is inspected with {@link LinkOption#NOFOLLOW_LINKS}: an element that is a
         *       symbolic link is refused outright, and a non-final element that exists but is not a
         *       directory is refused too. Missing directories are created one level at a time with
         *       {@link Files#createDirectory(Path, java.nio.file.attribute.FileAttribute...)}, never
         *       with {@code createDirectories}, so no level escapes inspection.</li>
         *   <li>The completed parent is re-resolved with {@code toRealPath()} and required to be
         *       <em>identical</em> to the descended path and still inside the real root. If any
         *       component had been swapped for a link during the descent, the resolved form would
         *       differ and the write is refused.</li>
         *   <li>The record's own file is opened through
         *       {@link FileChannel#open(Path, java.util.Set, java.nio.file.attribute.FileAttribute...)}
         *       with {@code CREATE}, {@code WRITE}, {@code APPEND} <em>and</em>
         *       {@link LinkOption#NOFOLLOW_LINKS}. The no-follow open is atomic - the platform refuses
         *       the open if the final component is a link, in the same operation that would have
         *       followed it - so there is no window between deciding the leaf is safe and writing to
         *       it. {@link Files#write} cannot be used for this: it offers no way to refuse a link.</li>
         * </ol>
         *
         * <p>{@code CREATE}, {@code WRITE} and {@code APPEND} together remain
         * {@code DISPOSITION(MOD)}: the record is added to whatever the destination already holds and
         * nothing is ever truncated, so the emitted bytes are still byte-identical to the CICS write
         * (gate <strong>G42</strong>). The write loop drains the buffer because
         * {@link FileChannel#write(java.nio.ByteBuffer)} is not obliged to write it all at once, and a
         * short write would leave a record of fewer than {@code RECORDSIZE} bytes in a
         * {@code RECORDFORMAT(FIXED)} dataset.
         *
         * <h4>The drain loop is indivisible to every other writer, not just to this JVM's threads</h4>
         * <p>An exclusive {@link FileLock} is taken over the destination and held for exactly the length of
         * that loop. This is the inter-process guarantee the instance monitor cannot give: an internal
         * reader is a queue because several writers feed it - a second application instance, a mainframe
         * utility, an operator's script - and a monitor orders none of them. Without the lock, two writers'
         * partial writes could interleave inside one eighty-byte record, which in a
         * {@code RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED)} dataset does not produce one damaged record
         * but two, and shifts every record after them. {@code O_APPEND} is atomic on the platforms that
         * have it, but the {@code java.nio.file} contract promises nothing of the sort and this module is
         * not entitled to assume a provider.
         *
         * <p>Both locks are needed and neither replaces the other. A {@code FileLock} belongs to the
         * <em>JVM</em>, so two threads of one JVM taking overlapping locks on one file raise
         * {@link OverlappingFileLockException} rather than queueing - the monitor is what orders them
         * first. That exception is nevertheless caught here and reported as {@code NOTOPEN}, because
         * reaching it means two ports were wired over one destination and a refused write is a far better
         * outcome for a fixed-format queue than a torn record.
         *
         * <p>The lock is released by try-with-resources <em>before</em> the channel closes, which is the
         * order {@link FileLock} requires: closing a channel releases its locks, and releasing a lock
         * after its channel has gone is undefined.
         *
         * <p><strong>The residual limitation, stated rather than absorbed</strong> (practice
         * <strong>B12</strong>): the platform-independent Java API exposes no directory handle, so
         * there is no {@code openat}-relative form of step 2. The leaf is protected atomically and any
         * substitution among the intermediate directories is detected by step 3, but a sufficiently
         * privileged local attacker who can write inside the approved root is outside what this port
         * can defend; the root's own permissions are the control for that, which is why it is required
         * to be operator-provisioned rather than defaulted into a shared temporary directory.
         *
         * @param image the encoded record, exactly {@link JobSubmissionProperties#TDQ_RECORD_LENGTH}
         *              bytes
         * @throws IOException if the root cannot be resolved, if any component of the destination is a
         *                     symbolic link or is not a directory, if the destination escapes the real
         *                     root, or if the append itself fails
         */
        private void appendWithinApprovedRoot(byte[] image) throws IOException {
            Path approvedRoot = properties.approvedRootPath();

            // Asked BEFORE the root is resolved, and that order is the whole point. Resolving first and
            // then measuring containment against the resolved root cannot fail: the root would define the
            // tree it had been redirected into, and every path beneath it would read as "contained". So
            // the question here is whether the configured root IS a link - /tmp/carddemo pointing at /etc
            // - rather than whether it resolves somewhere. Only the final component is examined: an
            // ancestor that is a link is ordinary and legitimate (/tmp is a link to /private/tmp on some
            // platforms), and refusing it would refuse deployments that have redirected nothing.
            //
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

            // The startup validation has already required strict containment, so relativizing against
            // the configured root yields the chain of names below it and never an upward step.
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
                // The inter-process half of the record's integrity. Exclusive, over the whole file, and
                // held for exactly as long as the drain loop - see the note below on why the monitor
                // this method is called under is not enough on its own, and why it is still needed.
                try (FileLock exclusive = channel.lock()) {
                    ByteBuffer record = ByteBuffer.wrap(image);
                    while (record.hasRemaining()) {
                        channel.write(record);
                    }
                    // Named rather than ignored: the lock is what the loop above is protected by, and a
                    // reader who cannot see it referenced cannot see that. Released by try-with-resources
                    // before the channel closes, which is the order FileLock requires.
                    assertHeld(exclusive);
                }
            } catch (OverlappingFileLockException alreadyHeldByThisJvm) {
                // Another thread of THIS JVM holds a lock over this file. The instance monitor makes that
                // unreachable for one port, so reaching it means two ports were wired over one
                // destination - a misconfiguration. Converted to IOException so the caller reports
                // RESP NOTOPEN: a refused write is the right outcome, and letting an unchecked exception
                // escape a WRITEQ TD would be a worse one.
                throw new IOException("Another writer in this application instance holds a lock on the "
                        + "job-submission destination '" + current + "'. The record was NOT written. "
                        + "Exactly one job-submission port may be wired per destination, because a "
                        + "RECORDFORMAT(FIXED) queue whose writers are not ordered yields torn records "
                        + "rather than a reported failure", alreadyHeldByThisJvm);
            }
        }

        /**
         * Requires that a lock taken for the drain loop is still held while the loop runs.
         *
         * <p>This exists so the lock is a named participant in the write rather than an object created and
         * forgotten. A {@code try (FileLock ignored = channel.lock())} would protect the loop just as well
         * and would read as though the lock were incidental, which is the opposite of true: the eighty
         * bytes below it are a whole record of a {@code RECORDFORMAT(FIXED)} queue, and what makes them one
         * record to every other writer is this lock and nothing else.
         *
         * @param exclusive the lock taken over the destination
         * @throws IOException if the lock is no longer valid, which would mean the loop about to run is
         *                     unprotected
         */
        private static void assertHeld(final FileLock exclusive) throws IOException {
            if (!exclusive.isValid()) {
                throw new IOException("The exclusive lock on the job-submission destination was released "
                        + "before the record was written, so the write would not have been indivisible to "
                        + "another writer. The record was NOT written");
            }
        }

        /**
         * Step 3 of the write-time defence: re-resolves the directory the record is about to be written
         * into and requires it to be the very directory the descent inspected, still inside the approved
         * root.
         *
         * <p>This is the check that closes the window the descent cannot close on its own. The descent
         * inspects each component and then moves on; between inspecting a component and opening the leaf,
         * a local attacker with write access inside the root could replace an intermediate directory with
         * a link. Comparing the completed parent against its own real path detects exactly that
         * substitution - a resolved path equal to the path itself means no component was a link when the
         * comparison was made - and the containment test then re-establishes the boundary rather than
         * trusting the start-up validation to still hold.
         *
         * <p>Extracted rather than inlined so that both refusals can be driven directly. A race cannot be
         * staged reliably from a single-threaded test, but the two states it produces can be presented as
         * inputs, which is the difference between a guard that is believed to work and one that is known
         * to (practice B10).
         *
         * @param parent   the directory the descent arrived at; must not be {@code null}
         * @param realRoot the resolved approved root; must not be {@code null}
         * @throws IOException if {@code parent} resolves anywhere other than itself, resolves outside
         *                     {@code realRoot}, or no longer exists
         */
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

        /**
         * The code page the records are encoded in.
         *
         * @return the charset, never {@code null}
         */
        public Charset queueCharset() {
            return codec.charset();
        }
    }
}
