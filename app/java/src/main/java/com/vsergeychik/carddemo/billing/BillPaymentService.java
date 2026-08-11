package com.vsergeychik.carddemo.billing;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.billing.dto.BillPaymentRequest;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse.CursorField;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.TransactionRepository.BrowseDirection;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

/**
 * {@code COBIL00C} - Bill Payment. Every decision this transaction makes, and its one monetary
 * computation, expressed in Java without changing a single observable outcome.
 *
 * <p>The source is {@code app/cbl/COBIL00C.cbl}, 572 lines, whose own header reads
 * <em>"Bill Payment - Pay account balance in full and a tractionsaction for the online bill
 * payment."</em> The typographical error in that sentence is the source's and is quoted rather than
 * corrected, which is the smallest possible illustration of this class's governing rule: <strong>this
 * is a like-for-like language migration, not a redesign. No new features, no changed business rules.
 * Where the COBOL does something odd, this class does the same odd thing.</strong>
 *
 * <h2>Why the logic lives here and not in the controller</h2>
 *
 * <p>Every branch, every message, every arithmetic step and every file operation of the program is in
 * this class. {@code BillPaymentController} binds HTTP, calls {@link #processEnterKey(BillPaymentRequest)}
 * and projects the returned {@link PaymentState} onto {@link BillPaymentResponse}; it decides nothing.
 * That split is what makes the twenty parity cases and the unit tests able to drive every arm of the
 * program with no servlet container, no {@code MockMvc} and no {@code JobLauncher} in the path.
 *
 * <h2>The structure of {@code PROCESS-ENTER-KEY} - four stages that never short-circuit</h2>
 *
 * <p>This is the single most important structural fact about the program, and the easiest thing to get
 * wrong. {@code PERFORM SEND-BILLPAY-SCREEN} <strong>returns and control continues</strong> - it is a
 * paragraph call, not an exit. So after a validation failure the paragraph carries on to the next
 * {@code IF NOT ERR-FLG-ON} guard, finds it false, skips that stage's body, and still runs to the end
 * of the paragraph. Reproducing the guards as early {@code return}s would be wrong twice over: it
 * would suppress statements the COBOL executes <em>outside</em> a guard, and it would change how many
 * times the screen is sent. The four stages here are therefore four sequential guarded blocks in one
 * method, and the fall-through is visible in the source layout.
 *
 * <h2>Two defects that are preserved, not fixed</h2>
 *
 * <ol>
 *   <li><strong>The stale balance.</strong> Lines 193-194 sit inside the stage-two guard but
 *       <em>outside</em> the confirmation switch, so they run even when that switch has just raised the
 *       error flag. On the {@code 'N'} path, and on every path where the account read failed, no
 *       account was ever read - yet a stale {@code ACCT-CURR-BAL} is still edited through the
 *       {@code PIC +9999999999.99} mask and stored into the balance field, <em>after</em> the screen
 *       has already been sent. See {@link #processEnterKeyInTask(PaymentState, String, String)}.</li>
 *   <li><strong>The unguarded payment sequence.</strong> Lines 211-235 contain no
 *       {@code IF NOT ERR-FLG-ON} between the individual {@code PERFORM}s. A failed cross-reference
 *       read raises the error flag and repaints the screen, and the sequence then <em>continues</em> -
 *       it browses the transaction master, builds a record, writes it, computes the new balance and
 *       rewrites the account. See {@link #makeBillPayment(PaymentState)}.</li>
 * </ol>
 *
 * <p>Both are genuine defects in a production mainframe program. Both are reproduced exactly, because
 * a parity harness diffs the bytes that actually get written and a "corrected" version would fail.
 *
 * <h2>The headline numeric trap</h2>
 *
 * <p>{@code ACCT-CURR-BAL} is {@code PIC S9(10)V99} - ten integer digits
 * ({@code app/cpy/CVACT01Y.cpy:7}). {@code TRAN-AMT} is {@code PIC S9(09)V99} - <strong>nine</strong>
 * ({@code app/cpy/CVTRA05Y.cpy:10}). {@code MOVE ACCT-CURR-BAL TO TRAN-AMT} at line 224 is a
 * cross-width numeric move, and a numeric receiver is aligned on its implied decimal point, so the
 * digit that does not fit is the <strong>high-order</strong> one. A balance of
 * {@code 1234567890.12} therefore becomes {@code 234567890.12} in the transaction record, and
 * {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} at line 234 <strong>does not yield
 * zero</strong>. It is not simplified to a zero assignment, the subtraction is not skipped, and
 * {@code TRAN-AMT} is not widened to avoid the truncation.
 *
 * <p>Rounding never happens. The keyword {@code ROUNDED} occurs <strong>zero</strong> times in this
 * program and zero times in any of the twenty-eight programs this migration covers, so COBOL truncates
 * excess fractional digits on store and the only faithful mode is
 * {@link java.math.RoundingMode#DOWN}. Every arithmetic step here routes through
 * {@link CobolDecimal}, which names the scale and the mode in one place. There is no
 * {@code HALF_UP}, no {@code HALF_EVEN}, no {@code CEILING} and no {@code FLOOR} anywhere in this
 * file, and no {@code double} or {@code float} for any value derived from a {@code PIC 9...V...}
 * field.
 *
 * <h2>Arithmetic inventory</h2>
 *
 * <p>The whole program contains exactly two arithmetic statements, and both are here:
 * <ul>
 *   <li>{@code ADD 1 TO WS-TRAN-ID-NUM} at line 217 - non-monetary, on a {@code PIC 9(16)} counter;</li>
 *   <li>{@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} at line 234 - the one monetary
 *       computation.</li>
 * </ul>
 * There is no {@code MULTIPLY}, no {@code DIVIDE} and no {@code SUBTRACT} verb, no {@code GO TO} and
 * no {@code PERFORM ... THRU} - so there is no control-flow restructuring to do beyond the guard-chain
 * fall-through described above. There are eighteen {@code EVALUATE} statements and every one is
 * order-sensitive: the first matching {@code WHEN} wins and {@code WHEN OTHER} is last.
 *
 * <h2>Statelessness and thread safety</h2>
 *
 * <p>This bean is a stateless singleton. Every item of {@code COBIL00C}'s {@code WORKING-STORAGE}
 * lives on a {@link PaymentState} created per invocation, because {@code WORKING-STORAGE} in a CICS
 * program is per-task storage and holding it in instance or static fields would let two concurrent
 * requests overwrite each other's screen. There is no mutable {@code static} field in this file and no
 * server-side session state: the communication area, the pressed key and the screen values all travel
 * in the request and response payloads.
 *
 * <h2>What this class deliberately does not contain</h2>
 *
 * <ul>
 *   <li>No repository, no record model and no data-access code. The three datasets it reaches are
 *       served by {@link AccountRepository}, {@link CardXrefRepository} and
 *       {@link TransactionRepository}, and the alternate index {@code CXACAIX} is a second finder
 *       method on the existing cross-reference repository - never a second repository and never a
 *       second table.</li>
 *   <li>No DDL, no schema migration, no entity annotation, no version column and no index. And no
 *       change check: {@code COBIL00C} has no {@code 9300}/{@code 9700-CHECK-CHANGE-IN-REC} paragraph
 *       at all, so unlike the account and card update programs it simply reads for update and
 *       rewrites. Inventing an optimistic-concurrency comparison here would be a new feature.</li>
 *   <li>No dataset name. Every one resolves from configuration inside the repositories.</li>
 *   <li>No charset. The code page is taken from the repository that owns the dataset, so it can never
 *       disagree with the bytes being read, and it is never the platform default.</li>
 *   <li>No HTTP concern of any kind, no caching, no batching and no parallelism. Reordering the
 *       payment sequence would change the observable write ordering, which is precisely what the
 *       parity gate measures.</li>
 * </ul>
 *
 * <h2>Governing practice</h2>
 *
 * <p>{@code review_rules} reports that <strong>no user rules were provided</strong> for this project.
 * Their absence is not permission to lower the bar: the enterprise best-practice substitutes of the
 * agent action plan bind instead, and the ones that shape this file are the closed dependency set,
 * the immutability of the COBOL reference sources, the preservation of dead code and defects, naming
 * the scale and rounding mode at every arithmetic site, no wildcard imports, no static mutable state,
 * and hand-written reviewable fixed-width codecs addressed by absolute copybook offset.
 *
 * @see BillPaymentRequest
 * @see BillPaymentResponse
 */
@Service
public class BillPaymentService {

    /**
     * The diagnostic log. {@code COBIL00C} writes {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:'
     * WS-REAS-CD} on the {@code WHEN OTHER} arm of six of its seven file operations - lines 366, 397,
     * 430, 461, 490 and 541 - and nothing else. Those statements carry two response codes and two
     * fixed literals, so no record content, no account identifier and no operator input can reach a
     * log through them.
     */
    private static final Log LOG = LogFactory.getLog(BillPaymentService.class);

    // =================================================================================================
    // Program identity - WS-VARIABLES, app/cbl/COBIL00C.cbl:36-42.
    // =================================================================================================

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COBIL00C'} - line 37. */
    public static final String WS_PGMNAME = "COBIL00C";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CB00'} - line 38.
     *
     * <p>{@code app/csd/CARDDEMO.CSD:337-338} defines {@code TRANSACTION(CB00)} against
     * {@code PROGRAM(COBIL00C)}, so this is the transaction identifier the screen shows and the one
     * {@code EXEC CICS RETURN TRANSID(WS-TRANID)} at line 147 hands back.
     */
    public static final String WS_TRANID = "CB00";

    /**
     * {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} - line 40.
     *
     * <p>Exactly eight characters with no padding needed, unlike the two below. This is the
     * <strong>CICS file name</strong> the four transaction-master commands name in
     * {@code DATASET(WS-TRANSACT-FILE)}; it is not a dataset name. The dataset behind it resolves from
     * configuration inside {@link TransactionRepository}.
     */
    public static final String WS_TRANSACT_FILE = "TRANSACT";

    /**
     * {@code WS-ACCTDAT-FILE PIC X(08) VALUE 'ACCTDAT '} - line 41.
     *
     * <p><strong>The trailing space is part of the value and is not a typographical accident.</strong>
     * The item is {@code PIC X(08)} and {@code ACCTDAT} is seven characters, so COBOL pads it to eight
     * and CICS receives eight characters. Trimming it here would change what the program declares.
     */
    public static final String WS_ACCTDAT_FILE = "ACCTDAT ";

    /**
     * {@code WS-CXACAIX-FILE PIC X(08) VALUE 'CXACAIX '} - line 42, padded to eight for the same
     * reason as {@link #WS_ACCTDAT_FILE}.
     *
     * <p>{@code app/csd/CARDDEMO.CSD:63-75} defines this file with
     * {@code DESCRIPTION(ALTERNATE INDEX TO CCXREF VIA ACCOUNT KEY)}, which is the proof that it is a
     * <em>path over the {@code CCXREF} base</em> rather than a dataset of its own - and therefore that
     * it is reached through a second finder method on {@link CardXrefRepository} rather than through a
     * repository or a table of its own.
     */
    public static final String WS_CXACAIX_FILE = "CXACAIX ";

    // =================================================================================================
    // Declared widths. Every one is read from a PICTURE clause in the source or a copybook, never
    // chosen, and every move in this class is performed at one of them.
    // =================================================================================================

    /** {@code WS-MESSAGE PIC X(80) VALUE SPACES} - line 39. */
    public static final int WS_MESSAGE_LENGTH = BillPaymentResponse.WS_MESSAGE_LENGTH;

    /**
     * {@code ERRMSGO PIC X(78)} - the message field of the symbolic map,
     * {@code app/cpy-bms/COBIL00.CPY:78}.
     *
     * <p>Two characters narrower than {@link #WS_MESSAGE_LENGTH}, which is why
     * {@code MOVE WS-MESSAGE TO ERRMSGO} at line 293 loses two bytes off the <strong>right</strong>
     * end. That truncation is performed deliberately, through the codec's alphanumeric helper, at the
     * one place the COBOL performs it.
     */
    public static final int ERR_MSG_LENGTH = BillPaymentResponse.ERR_MSG_LENGTH;

    /** {@code ACTIDINI PIC X(11)} - {@code app/cpy-bms/COBIL00.CPY:60}. */
    public static final int ACT_ID_IN_LENGTH = BillPaymentResponse.ACT_ID_IN_LENGTH;

    /** {@code CURBALI PIC X(14)} - {@code app/cpy-bms/COBIL00.CPY:66}, an exact fit for the mask. */
    public static final int CUR_BAL_LENGTH = BillPaymentResponse.CUR_BAL_LENGTH;

    /** {@code CONFIRMI PIC X(1)} - {@code app/cpy-bms/COBIL00.CPY:72}. */
    public static final int CONFIRM_LENGTH = BillPaymentResponse.CONFIRM_LENGTH;

    /**
     * {@code WS-RESP-CD} and {@code WS-REAS-CD} are {@code PIC S9(09) COMP} - lines 46 and 47 - so the
     * {@code DISPLAY} of either shows nine digit positions.
     */
    public static final int WS_RESP_CD_DIGITS = 9;

    /** {@code WS-TRAN-ID-NUM PIC 9(16) VALUE ZEROS} - line 57, the same width as {@code TRAN-ID}. */
    public static final int WS_TRAN_ID_NUM_DIGITS = TranRecord.TRAN_ID_LENGTH;

    /** {@code WS-CUR-DATE-X10 PIC X(10) VALUE SPACES} - line 60, the {@code FORMATTIME} date. */
    public static final int WS_CUR_DATE_X10_LENGTH = 10;

    /** {@code WS-CUR-TIME-X08 PIC X(08) VALUE SPACES} - line 61, the {@code FORMATTIME} time. */
    public static final int WS_CUR_TIME_X08_LENGTH = 8;

    // =================================================================================================
    // The three one-character flags and their 88-level condition names - lines 43-53. Held as the
    // declared characters rather than as booleans, because a COBOL flag can hold a value that satisfies
    // neither condition name and the program's own tests are written against the characters.
    // =================================================================================================

    /** {@code 88 ERR-FLG-ON VALUE 'Y'} - line 44. */
    public static final String ERR_FLG_ON = "Y";

    /** {@code 88 ERR-FLG-OFF VALUE 'N'} - line 45, and the item's own {@code VALUE}. */
    public static final String ERR_FLG_OFF = "N";

    /**
     * {@code 88 USR-MODIFIED-YES VALUE 'Y'} - line 49.
     *
     * <p><strong>Never set by this program.</strong> Line 102 sets {@code USR-MODIFIED-NO} and no
     * statement in the remaining 470 lines changes the item or reads it, so
     * {@code WS-USR-MODIFIED} is write-only state and this condition name is dead. Both are preserved
     * rather than dropped: the declaration is part of the program's data division, and a reader
     * comparing this class against the source would otherwise find a condition name missing with no
     * record of why.
     */
    public static final String USR_MODIFIED_YES = "Y";

    /** {@code 88 USR-MODIFIED-NO VALUE 'N'} - line 50, and the item's own {@code VALUE}. */
    public static final String USR_MODIFIED_NO = "N";

    /** {@code 88 CONF-PAY-YES VALUE 'Y'} - line 52. */
    public static final String CONF_PAY_YES = "Y";

    /** {@code 88 CONF-PAY-NO VALUE 'N'} - line 53, and the item's own {@code VALUE}. */
    public static final String CONF_PAY_NO = "N";

    // =================================================================================================
    // Declared and never referenced. Preserved verbatim, because a like-for-like migration keeps the
    // declared shape of the data division and because a future reader must be able to see that these
    // items exist and are unused rather than wonder whether they were overlooked.
    // =================================================================================================

    /**
     * {@code WS-TRAN-AMT PIC +99999999.99} - line 55: a forced sign, eight integer digits, the decimal
     * point and two fraction digits, so twelve characters.
     *
     * <p><strong>Declared and never referenced anywhere in the program.</strong> Note that it is two
     * integer digits narrower than {@link #CUR_BAL_LENGTH}'s mask, which is a real difference and not a
     * transcription slip: had this item ever been used to edit {@code ACCT-CURR-BAL} it would have lost
     * the two high-order digits. It never is.
     */
    public static final int WS_TRAN_AMT_LENGTH = 12;

    /**
     * The initial content of {@code WS-TRAN-AMT}.
     *
     * <p>The item carries no {@code VALUE} clause, so COBOL leaves its initial content unspecified for
     * a numeric-edited field. Spaces is the deterministic choice, it is what the rest of this module
     * uses for the same situation, and nothing in the program ever reads the item, so the choice is not
     * observable.
     */
    public static final String WS_TRAN_AMT_INITIAL = " ".repeat(WS_TRAN_AMT_LENGTH);

    /**
     * {@code WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} - line 58.
     *
     * <p><strong>Declared and never referenced anywhere in the program.</strong> Unlike
     * {@link #WS_TRAN_AMT_INITIAL} this one has an explicit {@code VALUE}, so its content is the
     * source's and not a choice, and it is {@code static final} because no statement in the program can
     * change it.
     */
    public static final String WS_TRAN_DATE = "00/00/00";

    // =================================================================================================
    // The WS-CURR-BAL edit mask - PIC +9999999999.99, line 56.
    //
    // Implemented explicitly, character position by character position. Not through String.format,
    // whose %+011.2f is a floating-point conversion of exactly the kind rule R4 forbids, and not
    // through NumberFormat, whose grouping and decimal separators follow a locale that a mainframe
    // edit mask does not have.
    // =================================================================================================

    /** The {@code +} of the picture, emitted for a value of zero or above. */
    public static final char CURR_BAL_SIGN_POSITIVE = '+';

    /** What the {@code +} of the picture emits for a value below zero. */
    public static final char CURR_BAL_SIGN_NEGATIVE = '-';

    /**
     * The ten {@code 9} positions left of the point.
     *
     * <p>{@code 9} and not {@code Z}: a {@code 9} position always emits a digit, so the integer part is
     * zero-filled and never blank-suppressed. A balance of {@code 1234.56} renders
     * {@code "+0000001234.56"} and not {@code "+      1234.56"}.
     */
    public static final int CURR_BAL_INTEGER_DIGITS = AccountRecord.MONETARY_INTEGER_DIGITS;

    /** The two {@code 9} positions right of the point. */
    public static final int CURR_BAL_FRACTION_DIGITS = CobolDecimal.MONETARY_SCALE;

    /** The {@code .} of the picture - an actual character position, not a locale decision. */
    public static final char CURR_BAL_DECIMAL_POINT = '.';

    /**
     * The mask's total width: one sign, ten integer digits, one point and two fraction digits.
     *
     * <p>Exactly {@link #CUR_BAL_LENGTH}, so {@code MOVE WS-CURR-BAL TO CURBALI} at line 194 neither
     * pads nor truncates. The assertion that the two agree is made once, here, by deriving this value
     * from the picture rather than restating it.
     */
    public static final int WS_CURR_BAL_LENGTH =
            1 + CURR_BAL_INTEGER_DIGITS + 1 + CURR_BAL_FRACTION_DIGITS;

    /**
     * The initial content of {@code WS-CURR-BAL}, which like {@link #WS_TRAN_AMT_INITIAL} has no
     * {@code VALUE} clause. Never observable: nothing reads the item before line 193 writes it.
     */
    public static final String WS_CURR_BAL_INITIAL = " ".repeat(WS_CURR_BAL_LENGTH);

    // =================================================================================================
    // Every message literal the program moves into WS-MESSAGE, byte for byte including the trailing
    // ellipsis. These are graded output: the parity harness diffs the message text, so a missing dot or
    // a corrected capital is a diff.
    // =================================================================================================

    /** Line 161: {@code MOVE 'Acct ID can NOT be empty...' TO WS-MESSAGE}. */
    public static final String MSG_ACCT_ID_EMPTY = "Acct ID can NOT be empty...";

    /**
     * Line 187: {@code MOVE 'Invalid value. Valid values are (Y/N)...' TO WS-MESSAGE}, the
     * {@code WHEN OTHER} arm of the confirmation switch.
     */
    public static final String MSG_INVALID_CONFIRM_VALUE =
            "Invalid value. Valid values are (Y/N)...";

    /** Line 201: {@code MOVE 'You have nothing to pay...' TO WS-MESSAGE}. */
    public static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /**
     * Line 237: {@code MOVE 'Confirm to make a bill payment...' TO WS-MESSAGE}.
     *
     * <p>The one message the program sets <strong>without</strong> raising the error flag - it is a
     * prompt rather than a rejection.
     */
    public static final String MSG_CONFIRM_TO_PAY = "Confirm to make a bill payment...";

    /**
     * Lines 361, 392 and 425: {@code MOVE 'Account ID NOT found...' TO WS-MESSAGE}.
     *
     * <p><strong>One text, three call sites</strong>, and they are deliberately not differentiated. The
     * account read, the account rewrite and the cross-reference read all report the same sentence on
     * their {@code NOTFND} arm, so a user cannot tell from the screen which file missed. That is the
     * program's behaviour and it is not improved here.
     */
    public static final String MSG_ACCOUNT_ID_NOT_FOUND = "Account ID NOT found...";

    /** Line 368: the {@code WHEN OTHER} arm of {@code READ-ACCTDAT-FILE}. */
    public static final String MSG_UNABLE_TO_LOOKUP_ACCOUNT = "Unable to lookup Account...";

    /** Line 399: the {@code WHEN OTHER} arm of {@code UPDATE-ACCTDAT-FILE}. */
    public static final String MSG_UNABLE_TO_UPDATE_ACCOUNT = "Unable to Update Account...";

    /** Line 432: the {@code WHEN OTHER} arm of {@code READ-CXACAIX-FILE}. */
    public static final String MSG_UNABLE_TO_LOOKUP_XREF_AIX = "Unable to lookup XREF AIX file...";

    /** Line 456: the {@code NOTFND} arm of {@code STARTBR-TRANSACT-FILE}. */
    public static final String MSG_TRANSACTION_ID_NOT_FOUND = "Transaction ID NOT found...";

    /**
     * Lines 463 and 492: the {@code WHEN OTHER} arms of {@code STARTBR-TRANSACT-FILE} and
     * {@code READPREV-TRANSACT-FILE}, which share one text.
     */
    public static final String MSG_UNABLE_TO_LOOKUP_TRANSACTION = "Unable to lookup Transaction...";

    /**
     * Line 536: the shared {@code DUPKEY}/{@code DUPREC} arm of {@code WRITE-TRANSACT-FILE}.
     *
     * <p>{@code Tran} and not {@code Transaction}, and {@code exist} and not {@code exists}. Both are
     * the source's and both are reproduced.
     */
    public static final String MSG_TRAN_ID_ALREADY_EXIST = "Tran ID already exist...";

    /** Line 543: the {@code WHEN OTHER} arm of {@code WRITE-TRANSACT-FILE}. */
    public static final String MSG_UNABLE_TO_ADD_TRANSACTION =
            "Unable to Add Bill pay Transaction...";

    /** {@code 'RESP:'} - the first literal of the six {@code DISPLAY} statements. */
    public static final String DISPLAY_RESP_PREFIX = "RESP:";

    /** {@code 'REAS:'} - the third literal of the same six statements. */
    public static final String DISPLAY_REAS_PREFIX = "REAS:";

    // =================================================================================================
    // The success message, composed by the STRING verb at lines 527-531.
    // =================================================================================================

    /** {@code 'Payment successful. '} {@code DELIMITED BY SIZE} - line 527, trailing space included. */
    public static final String SUCCESS_PREFIX = "Payment successful. ";

    /** {@code ' Your Transaction ID is '} {@code DELIMITED BY SIZE} - line 528, both spaces included. */
    public static final String SUCCESS_INFIX = " Your Transaction ID is ";

    /** {@code '.'} {@code DELIMITED BY SIZE} - line 530, the sentence's full stop. */
    public static final String SUCCESS_SUFFIX = ".";


    // =================================================================================================
    // The eleven literals the payment sequence moves into TRAN-RECORD - lines 220-229. Every one is
    // named, so that a reader can check it against the source without reading the assembly method, and
    // so that a parity case can assert against the same constant the production path uses.
    // =================================================================================================

    /**
     * Line 220: {@code MOVE '02' TO TRAN-TYPE-CD}.
     *
     * <p>{@code TRAN-TYPE-CD} is {@code PIC X(02)} ({@code app/cpy/CVTRA05Y.cpy:6}), so this is an
     * ordinary character move and the stored bytes are {@code "02"} - unlike the numerically aligned
     * move on the very next source line.
     */
    public static final String TRAN_TYPE_CD_BILL_PAYMENT = "02";

    /**
     * Line 221: {@code MOVE 2 TO TRAN-CAT-CD}.
     *
     * <p>A numeric literal into a {@code PIC 9(04)} receiver ({@code app/cpy/CVTRA05Y.cpy:7}), so the
     * stored bytes are {@code "0002"} - zero-filled on the <strong>left</strong>, because a numeric
     * receiver is aligned on its implied decimal point.
     */
    public static final int TRAN_CAT_CD_BILL_PAYMENT = 2;

    /**
     * Line 222: {@code MOVE 'POS TERM' TO TRAN-SOURCE}.
     *
     * <p>Eight characters into {@code PIC X(10)}, so the stored bytes are {@code "POS TERM  "} -
     * space-padded on the right.
     */
    public static final String TRAN_SOURCE_POS_TERM = "POS TERM";

    /**
     * Line 223: {@code MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC}.
     *
     * <p>Twenty-one characters into {@code PIC X(100)}. A {@code MOVE} blanks the whole receiver first,
     * so bytes 22 to 100 are spaces - which is what distinguishes it from a {@code STRING ... INTO},
     * and why the record is assembled with the move helper rather than the string helper.
     */
    public static final String TRAN_DESC_BILL_PAYMENT_ONLINE = "BILL PAYMENT - ONLINE";

    /**
     * Line 226: {@code MOVE 999999999 TO TRAN-MERCHANT-ID}.
     *
     * <p>Nine nines into {@code PIC 9(09)}, which fills the field exactly. The value is a sentinel
     * standing for "no real merchant" and is not validated against any merchant file, because the
     * program has none.
     */
    public static final long TRAN_MERCHANT_ID_BILL_PAYMENT = 999999999L;

    /** Line 227: {@code MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME}, into {@code PIC X(50)}. */
    public static final String TRAN_MERCHANT_NAME_BILL_PAYMENT = "BILL PAYMENT";

    /**
     * Lines 228 and 229: {@code MOVE 'N/A'} into {@code TRAN-MERCHANT-CITY PIC X(50)} and into
     * {@code TRAN-MERCHANT-ZIP PIC X(10)}. One literal, two receivers of different widths, and the
     * padding differs accordingly.
     */
    public static final String TRAN_MERCHANT_NOT_APPLICABLE = "N/A";

    // =================================================================================================
    // Boundary values, the browse position and the CICS absolute time.
    // =================================================================================================

    /**
     * The outcome {@code STARTBR-TRANSACT-FILE} can observe, and therefore the one the payment sequence
     * supplies to {@link #startbrTransactFile(PaymentState, Outcome)}.
     *
     * <p>The COBOL captures {@code RESP} and {@code RESP2} on its {@code STARTBR} at lines 447-448 and
     * evaluates them at 451-467. {@link TransactionRepository#startBrowse(BrowseDirection)} reports no
     * status at all for a position - by design, and documented on that method: positioning performs no
     * backend call, so there is nothing for it to report. The successful arm is consequently the only
     * one a Java execution can reach.
     *
     * <p>The other two arms are <strong>kept</strong> rather than deleted, because deleting an arm of an
     * ordered {@code EVALUATE} would change the guard chain, and they are reachable directly through
     * {@link #startbrTransactFile(PaymentState, Outcome)} so that each is exercised and each stays
     * provably correct. That method takes the outcome as an argument precisely so this remains true
     * without inventing a status the repository never produces.
     */
    public static final Outcome STARTBR_POSITIONING_OUTCOME = Outcome.OK;

    /**
     * The single character COBOL {@code HIGH-VALUES} stands for: {@code X'FF'}, the highest byte value.
     *
     * <p>{@code \u00ff} and not {@code \uffff}: the figurative constant is a <em>byte</em>, and a
     * one-byte code page maps it to the code point of the same numeric value.
     */
    public static final char HIGH_VALUES = '\u00ff';

    /**
     * The single character COBOL {@code LOW-VALUES} stands for: {@code X'00'}.
     *
     * <p>Also the state an IBM Enterprise COBOL {@code WORKING-STORAGE} item is left in when it carries
     * no {@code VALUE} clause, which is what makes it the honest model for a record area the program
     * never filled - see {@link PaymentState#cardXrefRecord()}.
     */
    public static final char LOW_VALUES = '\u0000';

    /**
     * {@code MOVE HIGH-VALUES TO TRAN-ID} - line 212, the record identification field the
     * {@code STARTBR} at line 445 positions on.
     *
     * <p>Sixteen {@code X'FF'} bytes. It is held here as the record of what the {@code RIDFLD} contains
     * and is <strong>never encoded</strong>: a strict single-byte encoder rejects it under
     * {@code US-ASCII}, and the repository's unanchored backward browse expresses what the COBOL
     * <em>means</em> by it - "position at the last record" - in any code page. That equivalence is
     * documented on {@link BrowseDirection#BACKWARD}, which cites this very line.
     *
     * <p>It matters beyond the browse. On the {@code WHEN OTHER} arm of {@code READPREV} the read fills
     * nothing, so {@code TRAN-ID} still holds these sixteen bytes when line 216 moves it into a
     * {@code PIC 9(16)} counter - see {@link #makeBillPayment(PaymentState)} for what z/OS does next.
     */
    public static final String HIGH_VALUES_TRAN_ID =
            String.valueOf(HIGH_VALUES).repeat(TranRecord.TRAN_ID_LENGTH);

    /**
     * {@code MOVE ZEROS TO TRAN-ID} - line 488, the {@code ENDFILE} arm of {@code READPREV}.
     *
     * <p>Sixteen character zeros. This is how the very first transaction the system ever writes gets
     * identifier {@code 0000000000000001}: an empty master reports end of file, the key becomes zeros,
     * and {@code ADD 1} makes it one.
     */
    public static final String ZEROS_TRAN_ID = "0".repeat(TranRecord.TRAN_ID_LENGTH);

    /**
     * Milliseconds from {@code 1900-01-01T00:00:00Z} to the Java epoch, {@code 1970-01-01T00:00:00Z}.
     *
     * <p>{@code EXEC CICS ASKTIME ABSTIME(WS-ABS-TIME)} at lines 251-253 reports milliseconds since
     * 1900-01-01, which is what {@code WS-ABS-TIME PIC S9(15) COMP-3} at line 59 holds. Seventy years
     * of which seventeen are leap years is 25,567 days, and 25,567 x 86,400 x 1000 is the constant
     * below - derived rather than looked up, so it can be checked without a reference.
     *
     * <p>Fifteen digits is ample: the value is currently near four times ten to the twelfth and the
     * picture holds up to ten to the fifteenth minus one.
     */
    public static final long CICS_ABSTIME_EPOCH_OFFSET_MILLIS = 25_567L * 86_400L * 1_000L;

    /**
     * {@code MOVE DFHGREEN TO ERRMSGC OF COBIL0AO} - line 526, on the successful-{@code WRITE} arm.
     *
     * <p>The program's <strong>only</strong> field-attribute override, and the reason
     * {@link BillPaymentResponse#getMessageHighlight()} exists. The mapset declares the message line
     * {@code COLOR=RED} at {@code app/bms/COBIL00.bms:127-128}, so a message renders red by default and
     * green only when it reports a completed payment.
     *
     * <p>Carried as the one character whose code point equals the unsigned value of
     * {@link BmsAttributes#DFHGREEN}, because {@code ERRMSGC} is {@code PICTURE X} - one byte - and
     * {@link BillPaymentResponse#MESSAGE_HIGHLIGHT_LENGTH} is one character. The round trip is exact:
     * {@code (byte) MESSAGE_HIGHLIGHT_GREEN.charAt(0) == BmsAttributes.DFHGREEN}. A hexadecimal
     * rendering would be five characters and would not fit the declared width.
     */
    public static final String MESSAGE_HIGHLIGHT_GREEN =
            Character.toString(BmsAttributes.unsigned(BmsAttributes.DFHGREEN));

    /**
     * What the unit of work is doing, for a failure to be able to name it by.
     *
     * <p>Names the paragraph and the three files, because a task that fails to commit has to be
     * traceable to the COBOL it was reproducing.
     */
    private static final String UNIT_OF_WORK_DESCRIPTION =
            "PROCESS-ENTER-KEY (app/cbl/COBIL00C.cbl:154-244): read " + WS_ACCTDAT_FILE.trim()
                    + " for update, read " + WS_CXACAIX_FILE.trim() + ", browse and add to "
                    + WS_TRANSACT_FILE + ", then rewrite the account - one CICS task, one syncpoint";


    // =================================================================================================
    // Collaborators. All final, all constructor-injected, none replaceable after construction. There is
    // no field injection and no @Autowired annotation anywhere in this class, so a partially wired
    // instance cannot exist and a unit test can supply stubs with no Spring context in the path.
    // =================================================================================================

    /**
     * The {@code ACCTDAT} file: read for update at lines 345-354, rewritten at lines 379-385.
     *
     * <p>Also, indirectly, the source of this class's code page - see {@link #codec}.
     */
    private final AccountRepository accountRepository;

    /**
     * The {@code CCXREF} cross-reference, reached through its {@code CXACAIX} alternate-index path at
     * lines 410-418.
     *
     * <p>One repository, two access paths. The alternate index is a finder method on the base
     * repository, not a repository of its own, because {@code CXACAIX} is a path over {@code CCXREF}
     * and not a dataset - see {@link #WS_CXACAIX_FILE}.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * The {@code TRANSACT} master: browsed backwards at lines 443-482 to find the highest existing
     * identifier, then added to at lines 512-520.
     */
    private final TransactionRepository transactionRepository;

    /**
     * The clock behind {@code EXEC CICS ASKTIME} at lines 251-253.
     *
     * <p>Injected and read explicitly, never {@code Instant.now()} and never
     * {@code LocalDateTime.now()}, so a parity case can pin the instant with
     * {@link Clock#fixed(Instant, java.time.ZoneId)} and compare the resulting twenty-six bytes of
     * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} exactly.
     *
     * <p>The program reads two clocks and this is one of them. The other is
     * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} in {@code POPULATE-HEADER-INFO} at line
     * 321, which paints the screen's date and time header and belongs to the controller. Both resolve
     * from this same bean, which is what keeps the header and the stored timestamp consistent within one
     * task.
     */
    private final Clock clock;

    /**
     * The unit-of-work boundary the whole of {@code PROCESS-ENTER-KEY} runs inside.
     *
     * <p><strong>Why this service owns the boundary rather than its caller.</strong> A CICS task always
     * has one. {@code EXEC CICS READ ... UPDATE} at line 351 takes a record lock that the task holds
     * until its syncpoint, and the program relies on that: it reads the account at line 177 or 184,
     * writes a transaction at line 233, computes the new balance at line 234 and rewrites the account at
     * line 235, and those four steps are only coherent if nothing else can move the record in between.
     * {@code PROCESS-ENTER-KEY} <em>is</em> the task's body, so the boundary belongs here.
     *
     * <p>Left to a caller it would be nowhere: {@link AccountRepository#readForUpdate(String)} refuses
     * to issue a locking read outside a transaction - correctly, since the lock would be released with
     * the statement - so without this the read path could not execute at all.
     *
     * <p>One boundary for the whole paragraph and not one per operation. Two units of work would release
     * the account lock before the transaction was written, so a concurrent payment could interleave
     * between the write and the rewrite and one of the two debits would be lost.
     */
    private final DatasetUnitOfWork unitOfWork;

    /**
     * The {@code PICTURE} move rules and the code page every image in this class is rendered in.
     *
     * <p><strong>No charset is named in this file.</strong> The code page is taken from
     * {@link AccountRepository#datasetCharset()}, which resolves it from configuration through
     * {@code CobolCharsetConfig}. Taking it from the repository rather than from a constant has a
     * concrete benefit beyond avoiding a literal: the balance this class edits and rewrites is decoded
     * from, and re-encoded into, that same dataset, so the two can never disagree about which byte is a
     * digit or a sign overpunch.
     *
     * <p>It is the owner of every pad and truncate decision here. A COBOL {@code MOVE} fills a
     * {@code PIC X} receiver from the left and discards the overflow on the <strong>right</strong>,
     * while a {@code PIC 9} receiver is aligned on its implied decimal point and discards on the
     * <strong>left</strong>. The two rules are separate methods on the codec and are never conflated
     * into a Java assignment.
     */
    private final FixedWidthCodec codec;

    /**
     * Wires the program's five collaborators.
     *
     * @param accountRepository     the {@code ACCTDAT} file, read for update and rewritten; must not be
     *                              {@code null}. Also supplies the dataset code page
     * @param cardXrefRepository    the {@code CCXREF} cross-reference, read through its {@code CXACAIX}
     *                              alternate-index path; must not be {@code null}
     * @param transactionRepository the {@code TRANSACT} master, browsed and added to; must not be
     *                              {@code null}
     * @param clock                 the clock {@code EXEC CICS ASKTIME} reads; must not be {@code null}
     * @param unitOfWork            the CICS task boundary one execution runs inside; must not be
     *                              {@code null}
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if the account repository reports no dataset code page, which would
     *                               leave every fixed-width image in this class unrenderable
     */
    public BillPaymentService(AccountRepository accountRepository,
                             CardXrefRepository cardXrefRepository,
                             TransactionRepository transactionRepository,
                             Clock clock,
                             DatasetUnitOfWork unitOfWork) {
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "An AccountRepository is required: app/cbl/COBIL00C.cbl:345-354 reads " + "the "
                        + WS_ACCTDAT_FILE.trim() + " file for update and :379-385 rewrites it, and the "
                        + "balance the payment debits comes from and returns to that record");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository,
                "A CardXrefRepository is required: app/cbl/COBIL00C.cbl:410-418 reads the "
                        + WS_CXACAIX_FILE.trim() + " alternate index by account id to obtain the card "
                        + "number the transaction record carries");
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "A TransactionRepository is required: app/cbl/COBIL00C.cbl:443-482 browses the "
                        + WS_TRANSACT_FILE + " master backwards for the highest identifier and :512-520 "
                        + "adds the payment transaction under the next one");
        this.clock = Objects.requireNonNull(clock, "A Clock is required: EXEC CICS ASKTIME is read from "
                + "it at app/cbl/COBIL00C.cbl:251-253 and never from the wall clock, so a parity case "
                + "can pin the instant that lands in TRAN-ORIG-TS and TRAN-PROC-TS");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "A unit of work is required: "
                + "app/cbl/COBIL00C.cbl:351 states the UPDATE option, so the read takes a record lock, "
                + "and a lock outside a unit of work is released before the task that asked for it can "
                + "rely on it - which is why AccountRepository refuses to issue one there");

        // The code page comes from the dataset that owns the balance, so this class names none. A stub
        // that reports no charset is refused loudly here rather than producing a NullPointerException
        // from inside the codec, where the cause would be far less obvious.
        Charset datasetCharset = accountRepository.datasetCharset();
        if (datasetCharset == null) {
            throw new IllegalStateException("The account repository reported no dataset code page. "
                    + "Every image this program renders - the fourteen-character balance mask, the "
                    + "eighty-byte message, the 350-byte transaction record - is fixed-width, so the "
                    + "code page is always stated explicitly by configuration and is never taken from "
                    + "the platform default");
        }
        this.codec = new FixedWidthCodec(datasetCharset);
    }

    /**
     * The code page every image this service renders is encoded in, and the move rules that go with it.
     *
     * <p>Exposed so that a caller assembling the response, and a parity case decoding a written record,
     * can use the identical codec rather than constructing a second one that might disagree.
     *
     * @return the codec built at construction from {@link AccountRepository#datasetCharset()}; never
     *         {@code null} and never replaced
     */
    public FixedWidthCodec codec() {
        return codec;
    }


    // =================================================================================================
    // PROCESS-ENTER-KEY - app/cbl/COBIL00C.cbl:154-244. The transaction's whole body.
    // =================================================================================================

    /**
     * {@code PROCESS-ENTER-KEY} driven from a bound request: the form the controller calls.
     *
     * <p>Reads three items from the request and nothing else - the account identifier
     * ({@code ACTIDINI}), the confirmation character ({@code CONFIRMI}) and the communication area. The
     * remaining seven payload members are header fields that {@code POPULATE-HEADER-INFO} writes rather
     * than reads, and the paragraph never consults them.
     *
     * <p>The request is not modified.
     *
     * @param request the bound screen; must not be {@code null}
     * @return the execution's working storage, complete: the flags, the message, the screen fields, the
     *         records touched, every screen the paragraph sent and every diagnostic it wrote. Never
     *         {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public PaymentState processEnterKey(BillPaymentRequest request) {
        Objects.requireNonNull(request, "A bound screen is required: PROCESS-ENTER-KEY reads ACTIDINI "
                + "at app/cbl/COBIL00C.cbl:159 and CONFIRMI at :173, and returns the communication area "
                + "the client carries into the next call");
        return processEnterKey(request.getActIdIn(), request.getConfirm(),
                request.getNavigationContext());
    }

    /**
     * {@code PROCESS-ENTER-KEY} driven from its three inputs: the form a parity case calls.
     *
     * <p><strong>The whole paragraph runs inside one unit of work</strong>, which is the Java form of
     * the CICS task that ends at the syncpoint of {@code EXEC CICS RETURN} at lines 146-149. See
     * {@link #unitOfWork} for why the boundary belongs to this method and not to its caller.
     *
     * <p>Nothing is caught here. An exception raised inside the boundary rolls the unit of work back and
     * propagates, which is the faithful outcome for the two conditions that can raise one: a z/OS data
     * exception on invalid numeric-display data (see {@link #makeBillPayment(PaymentState)}) and a
     * backend refusal the repositories could not classify. A COBOL abend also backs the task out.
     *
     * @param actIdIn  {@code ACTIDINI OF COBIL0AI}, {@code PIC X(11)}. Taken as characters and neither
     *                 trimmed nor parsed; {@code null} is accepted and read as the {@code LOW-VALUES}
     *                 state that {@code MOVE LOW-VALUES TO COBIL0AO} at line 114 produces, and a value
     *                 shorter than eleven characters is space-padded to the field's declared width
     * @param confirm  {@code CONFIRMI OF COBIL0AI}, {@code PIC X(1)}. Taken as characters with no case
     *                 folding, because the source tests {@code 'Y'} and {@code 'y'} as separate
     *                 {@code WHEN} arms rather than by normalising; {@code null} is read as
     *                 {@code LOW-VALUES}
     * @param commarea {@code CARDDEMO-COMMAREA}. Carried onto the state and returned unchanged - the
     *                 paragraph reads nothing from it and writes nothing to it; {@code null} is accepted
     *                 and becomes {@link NavigationContext#empty()}
     * @return the execution's working storage; never {@code null}
     */
    public PaymentState processEnterKey(String actIdIn, String confirm, NavigationContext commarea) {
        PaymentState state = new PaymentState(codec,
                commarea == null ? NavigationContext.empty() : commarea);
        // The screen fields are materialised at their declared symbolic-map widths once, here, so that
        // every test below compares eleven and one characters rather than whatever length a JSON string
        // happened to carry. A null stays distinguishable from spaces because it becomes LOW-VALUES.
        String actIdInField = materialise(actIdIn, ACT_ID_IN_LENGTH);
        String confirmField = materialise(confirm, CONFIRM_LENGTH);
        state.setActIdIn(actIdInField);
        state.setConfirm(confirmField);

        return unitOfWork.execute(UNIT_OF_WORK_DESCRIPTION, () -> {
            processEnterKeyInTask(state, actIdInField, confirmField);
            return state;
        });
    }

    /**
     * The body of {@code PROCESS-ENTER-KEY}, running inside the unit of work its locks need.
     *
     * <p><strong>Four sequential guarded blocks, and not four early returns.</strong> The reason is
     * stated on this class and is worth restating at the code it governs:
     * {@code PERFORM SEND-BILLPAY-SCREEN} is a paragraph call that returns, so raising the error flag
     * suppresses the <em>bodies</em> of the later stages and nothing else. In particular:
     *
     * <ul>
     *   <li>the paragraph always runs to its end, so the screen-send count is a function of how many
     *       stages were entered rather than of where the first failure was;</li>
     *   <li>lines 193-194 are inside stage two's guard but outside its {@code EVALUATE}, so they run
     *       even when that {@code EVALUATE} has just raised the flag. That is the stale-balance defect,
     *       and it is preserved: no guard is added, the statement is not skipped and the balance is not
     *       zeroed.</li>
     * </ul>
     *
     * <p>Turning any of this into a {@code return} would change the observable byte stream, which is
     * exactly what the parity harness measures.
     *
     * @param state         the execution's working storage
     * @param actIdInField  {@code ACTIDINI} at its declared eleven characters
     * @param confirmField  {@code CONFIRMI} at its declared one character
     */
    private void processEnterKeyInTask(PaymentState state, String actIdInField, String confirmField) {
        // ---------------------------------------------------------------------------------------------
        // :156  SET CONF-PAY-NO TO TRUE
        // ---------------------------------------------------------------------------------------------
        state.setConfPayNo();

        // ---------------------------------------------------------------------------------------------
        // :158-167  EVALUATE TRUE / WHEN ACTIDINI = SPACES OR LOW-VALUES ... / WHEN OTHER CONTINUE
        //
        // An ordered EVALUATE with two arms. The WHEN OTHER arm is a genuine no-op and is written out
        // rather than omitted, so that both sides of the condition are visible and a branch counter can
        // see them.
        // ---------------------------------------------------------------------------------------------
        if (isSpacesOrLowValues(actIdInField)) {
            state.setErrFlagOn();                                                          // :160
            state.setMessage(MSG_ACCT_ID_EMPTY);                                           // :161-162
            state.setCursorField(CursorField.ACTIDIN);                                     // :163
            sendBillpayScreen(state);                                                      // :164
        } else {
            // :165-166  WHEN OTHER / CONTINUE. Nothing happens to the screen, and nothing is meant to.
            // The arm is recorded rather than left empty so that "the account id was supplied" is a
            // fact a parity case can assert, and so that the no-op arm of an ordered EVALUATE is as
            // visible here as it is in the source.
            state.setAcctIdCheckContinued();
        }

        // ---------------------------------------------------------------------------------------------
        // :169-195  IF NOT ERR-FLG-ON  ... END-IF
        // ---------------------------------------------------------------------------------------------
        if (!state.isErrFlagOn()) {
            // :170-171  MOVE ACTIDINI OF COBIL0AI TO ACCT-ID
            //                                        XREF-ACCT-ID
            //
            // ONE MOVE, TWO RECEIVERS, and both are PIC 9(11) while the sender is PIC X(11). See
            // moveScreenIdToNumericField for what an equal-width alphanumeric-to-numeric move does.
            String numericKey = moveScreenIdToNumericField(actIdInField, ACT_ID_IN_LENGTH);
            state.setAcctIdRidfld(numericKey);
            state.setXrefAcctIdRidfld(numericKey);

            // :173-191  EVALUATE CONFIRMI OF COBIL0AI - ordered, first match wins, WHEN OTHER last.
            evaluateConfirmation(state, confirmField);

            // :193-194  MOVE ACCT-CURR-BAL TO WS-CURR-BAL
            //           MOVE WS-CURR-BAL   TO CURBALI OF COBIL0AI
            //
            // UNCONDITIONAL WITHIN THIS GUARD. This is the stale-balance defect and it is deliberate.
            // On the 'N' arm no account was ever read, so the balance edited here is the initial content
            // of the WORKING-STORAGE record area - and the screen has already been sent, so the edited
            // value lands in the field of a screen the user will never see with it. Every path through
            // the EVALUATE reaches these two statements, including the three that raised the error flag.
            state.setCurrBalEdited(editCurrBal(state.accountRecord().getAcctCurrBal()));    // :193
            state.setCurBal(codec.movePicX(state.currBalEdited(), CUR_BAL_LENGTH));         // :194
        }

        // ---------------------------------------------------------------------------------------------
        // :197-206  IF NOT ERR-FLG-ON / IF ACCT-CURR-BAL <= ZEROS AND ACTIDINI NOT = SPACES AND
        //           LOW-VALUES
        //
        // A compound condition, and both operands matter. The balance test uses signum rather than
        // equals: BigDecimal.equals is scale-sensitive, so a scale-2 zero is not equal to BigDecimal.ZERO
        // and a test written that way would invert this branch.
        // ---------------------------------------------------------------------------------------------
        if (!state.isErrFlagOn()) {
            if (isNothingToPay(state.accountRecord().getAcctCurrBal(), actIdInField)) {
                state.setErrFlagOn();                                                      // :200
                state.setMessage(MSG_NOTHING_TO_PAY);                                      // :201-202
                state.setCursorField(CursorField.ACTIDIN);                                 // :203
                sendBillpayScreen(state);                                                  // :204
            }
        }

        // ---------------------------------------------------------------------------------------------
        // :208-244  IF NOT ERR-FLG-ON / IF CONF-PAY-YES ... ELSE ... END-IF / PERFORM
        //           SEND-BILLPAY-SCREEN / END-IF
        // ---------------------------------------------------------------------------------------------
        if (!state.isErrFlagOn()) {
            if (state.isConfPayYes()) {                                                    // :210
                makeBillPayment(state);                                                    // :211-235
            } else {
                // :237-239. Note what is ABSENT: this arm sets no error flag. It is a prompt asking the
                // user to type Y or N, not a rejection, and the difference is observable in the flag the
                // state reports.
                state.setMessage(MSG_CONFIRM_TO_PAY);                                      // :237-238
                state.setCursorField(CursorField.CONFIRM);                                 // :239
            }

            // :242  PERFORM SEND-BILLPAY-SCREEN - unconditional within this guard, so a successful
            // payment sends the screen twice: once from the WRITE arm at :532 and once here.
            sendBillpayScreen(state);
        }
    }

    /**
     * The compound condition at lines 198-199:
     * {@code IF ACCT-CURR-BAL <= ZEROS AND ACTIDINI OF COBIL0AI NOT = SPACES AND LOW-VALUES}.
     *
     * <p>Two operands, and both are load-bearing:
     * <ul>
     *   <li><strong>the balance</strong> tested with {@link BigDecimal#signum()} and never with
     *       {@link BigDecimal#equals(Object)}. {@code equals} compares scale as well as value, so a
     *       scale-2 zero is not equal to {@link BigDecimal#ZERO} and a test written that way would
     *       invert this branch and let a zero-balance account be "paid";</li>
     *   <li><strong>the account identifier</strong> tested against <em>both</em> figurative constants,
     *       exactly as line 159 tests them. Note that the {@code AND} makes the sentence read "the
     *       balance is not payable <em>and</em> an identifier was actually supplied", which is a guard
     *       against reporting "nothing to pay" for an account nobody named.</li>
     * </ul>
     *
     * <p>Extracted as its own method for a reason that is worth stating: on the program's own path the
     * second operand is always true here, because line 158's check has already rejected an absent
     * identifier and raised the flag that suppresses this stage. The condition is nonetheless a
     * conjunction in the source, so it is reproduced as one and made reachable in all four combinations
     * - otherwise half of it could never be shown to behave as the COBOL does.
     *
     * @param balance {@code ACCT-CURR-BAL} as the working record holds it; must not be {@code null}
     * @param actIdIn {@code ACTIDINI} at its declared width; must not be {@code null}
     * @return {@code true} when the balance is zero or below <em>and</em> an identifier was supplied
     * @throws NullPointerException if either argument is {@code null}
     */
    public boolean isNothingToPay(BigDecimal balance, String actIdIn) {
        Objects.requireNonNull(balance, "A balance is required: app/cbl/COBIL00C.cbl:198 compares "
                + "ACCT-CURR-BAL against ZEROS, and a numeric field has no null");
        Objects.requireNonNull(actIdIn, "An account identifier field is required: "
                + "app/cbl/COBIL00C.cbl:199 compares ACTIDINI against SPACES and LOW-VALUES");
        return balance.signum() <= 0 && !isSpacesOrLowValues(actIdIn);
    }

    /**
     * {@code EVALUATE CONFIRMI OF COBIL0AI} - lines 173 to 191.
     *
     * <p>An ordered {@code EVALUATE} with seven {@code WHEN} clauses forming four bodies, and the source
     * order is the contract. Two pairs share a body, which is a deliberate COBOL idiom rather than a
     * fall-through: consecutive {@code WHEN} clauses with no statements between them select the next
     * body, so {@code 'Y'} and {@code 'y'} run the same code, and so do {@code 'N'} and {@code 'n'},
     * and so do {@code SPACES} and {@code LOW-VALUES}.
     *
     * <p><strong>The {@code 'N'} arm's order is observable.</strong> Line 180 performs
     * {@code CLEAR-CURRENT-SCREEN}, which blanks the fields <em>and sends the screen</em>, and only then
     * does line 181 raise the error flag. Sending before raising means the screen the user receives
     * carries a blank message rather than an error, and the flag then suppresses stages three and four.
     * Reversing the two statements would change what is transmitted.
     *
     * <p>No case folding anywhere. The source tests the two cases as separate {@code WHEN} arms, so a
     * lower-cased comparison here would collapse a distinction the program makes explicitly.
     *
     * <p>The two arms that read the account file take their key from
     * {@link PaymentState#acctIdRidfld()}, which the caller has already set from the single
     * two-receiver {@code MOVE} at lines 170-171. The key is deliberately not re-derived here, so there
     * is exactly one place where the screen field becomes a record identification field.
     *
     * @param state        the execution's working storage
     * @param confirmField {@code CONFIRMI} at one character
     */
    private void evaluateConfirmation(PaymentState state, String confirmField) {
        if (CONF_PAY_YES.equals(confirmField) || "y".equals(confirmField)) {                // :174-175
            state.setConfPayYes();                                                         // :176
            readAcctdatFile(state);                                                        // :177
        } else if ("N".equals(confirmField) || "n".equals(confirmField)) {                  // :178-179
            clearCurrentScreen(state);                                                     // :180
            state.setErrFlagOn();                                                          // :181
        } else if (isSpacesOrLowValues(confirmField)) {                                     // :182-183
            readAcctdatFile(state);                                                        // :184
        } else {
            // :185-190  WHEN OTHER.
            state.setErrFlagOn();                                                          // :186
            state.setMessage(MSG_INVALID_CONFIRM_VALUE);                                    // :187-188
            state.setCursorField(CursorField.CONFIRM);                                     // :189
            sendBillpayScreen(state);                                                      // :190
        }
    }


    // =================================================================================================
    // The payment sequence - app/cbl/COBIL00C.cbl:211-235. Fifteen statements, in this order and no
    // other, WITH NO GUARDS BETWEEN THEM.
    // =================================================================================================

    /**
     * Lines 211 to 235: read the card cross-reference, find the highest transaction identifier, build
     * and add the payment transaction, debit the balance and rewrite the account.
     *
     * <p><strong>There is no {@code IF NOT ERR-FLG-ON} anywhere inside this sequence.</strong> That is
     * not an omission in this translation; it is an omission in the source, and it is the second of the
     * two defects this class preserves. The consequences are real and are reproduced exactly:
     *
     * <ul>
     *   <li>a cross-reference read that reports {@code NOTFND} raises the error flag and repaints the
     *       screen, and the sequence then <em>continues</em>. The card number that reaches
     *       {@code TRAN-CARD-NUM} is the initial content of a record area the program never filled, and
     *       the transaction is still written, the balance still debited and the account still
     *       rewritten;</li>
     *   <li>the same is true of a failed {@code STARTBR} and of a failed {@code READPREV}.</li>
     * </ul>
     *
     * <p><strong>The {@code READPREV} {@code WHEN OTHER} arm is different, and it is worth being precise
     * about why.</strong> That arm fills nothing, so {@code TRAN-ID} still holds the sixteen
     * {@code X'FF'} bytes line 212 put there when line 216 moves it into a {@code PIC 9(16)} counter and
     * line 217 adds one to it. Arithmetic on invalid numeric-display data raises a data exception on
     * z/OS - S0C7 - and the task abends without writing anything. The codec's refusal of a non-digit
     * image is the faithful Java counterpart: it raises, the unit of work rolls back, and no record is
     * added. Fabricating a plausible identifier instead would invent behaviour the program does not
     * have, and swallowing the condition would write a transaction z/OS would never have written.
     *
     * <p>Every step below is annotated with its source line. Reordering any two of them changes the
     * observable write ordering, which is what the parity gate measures.
     *
     * @param state the execution's working storage
     */
    private void makeBillPayment(PaymentState state) {
        // :211  PERFORM READ-CXACAIX-FILE
        readCxacaixFile(state);

        // :212  MOVE HIGH-VALUES TO TRAN-ID
        //
        // Recorded, not encoded. The unanchored backward browse below is what CICS does with a
        // high-values RIDFLD - "position at the last record" - expressed in a way any code page can
        // carry. See HIGH_VALUES_TRAN_ID.
        state.setTranIdRidfld(HIGH_VALUES_TRAN_ID);

        // :213  PERFORM STARTBR-TRANSACT-FILE
        //
        // try-with-resources closes the handle even if a later step raises, which is the ENDBR the
        // COBOL performs unconditionally at :215 in a program that cannot raise. The explicit
        // endbrTransactFile below is still issued, because ending an already-ended browse does nothing
        // and because :215 is a statement of the source that must be visible here.
        try (TransactionRepository.Browse browse =
                     transactionRepository.startBrowse(BrowseDirection.BACKWARD)) {
            startbrTransactFile(state, STARTBR_POSITIONING_OUTCOME);

            // :214  PERFORM READPREV-TRANSACT-FILE
            readprevTransactFile(state, browse.readPrev());

            // :215  PERFORM ENDBR-TRANSACT-FILE
            endbrTransactFile(browse);
        }

        // :216  MOVE TRAN-ID TO WS-TRAN-ID-NUM
        //
        // X(16) into 9(16): a cross-representation move, routed through the codec's numeric helper.
        // Equal widths, so no digit is lost - but the helper refuses a non-digit image, which is the
        // S0C7 equivalent described above. Nothing here parses with Long.parseLong or Integer.parseInt.
        String tranIdImage = codec.movePic9(state.tranIdRidfld(), WS_TRAN_ID_NUM_DIGITS);
        state.setTranIdNum(codec.decodePic9(tranIdImage));

        // :217  ADD 1 TO WS-TRAN-ID-NUM
        //
        // The program's only non-monetary arithmetic. A PIC 9(16) receiver has no ON SIZE ERROR clause
        // here, so an increment past sixteen nines discards the high-order digit and wraps to zeros -
        // which is exactly what the codec's numeric move does, and why the sum is put back through it
        // rather than kept as a long.
        long incremented = state.tranIdNum() + 1L;
        String nextTranId = codec.movePic9(incremented, WS_TRAN_ID_NUM_DIGITS);
        state.setTranIdNum(codec.decodePic9(nextTranId));

        // :218  INITIALIZE TRAN-RECORD
        //
        // A fresh 350-byte area: PIC X spans and the trailing FILLER X(20) space-filled, the three
        // numeric spans zero-filled. That is exactly what INITIALIZE leaves behind for the named items,
        // and the FILLER is emitted as part of the image whether INITIALIZE touched it or not - without
        // it the record would be 330 bytes and every offset downstream of it would be wrong.
        TranRecord tranRecord = new TranRecord(codec.charset());
        state.setTranRecord(tranRecord);

        // :219-229  The eleven moves, in source order.
        tranRecord.moveTranId(nextTranId);                                                 // :219
        tranRecord.moveTranTypeCd(TRAN_TYPE_CD_BILL_PAYMENT);                              // :220
        tranRecord.moveTranCatCd(TRAN_CAT_CD_BILL_PAYMENT);                                // :221
        tranRecord.moveTranSource(TRAN_SOURCE_POS_TERM);                                   // :222
        tranRecord.moveTranDesc(TRAN_DESC_BILL_PAYMENT_ONLINE);                            // :223

        // :224  MOVE ACCT-CURR-BAL TO TRAN-AMT
        //
        // THE HEADLINE PARITY TRAP. ACCT-CURR-BAL is PIC S9(10)V99 and TRAN-AMT is PIC S9(09)V99, so
        // this is a cross-width numeric move and a numeric receiver is aligned on its IMPLIED DECIMAL
        // POINT - the digit that does not fit is the high-order one. The truncation is performed here,
        // explicitly, at the source line that performs it, naming both the receiver's picture and the
        // rounding mode, so that the value stored in the record and the value the next COMPUTE reads are
        // provably the same one.
        BigDecimal balanceBeforePayment = state.accountRecord().getAcctCurrBal();
        BigDecimal storedTranAmt = CobolDecimal.storeAtPicture(balanceBeforePayment,
                TranRecord.TRAN_AMT_INTEGER_DIGITS, TranRecord.TRAN_AMT_SCALE);
        tranRecord.moveTranAmt(storedTranAmt);

        // :225  MOVE XREF-CARD-NUM TO TRAN-CARD-NUM - X(16) to X(16), neither padded nor truncated.
        tranRecord.moveTranCardNum(state.xrefCardNum());

        tranRecord.moveTranMerchantId(TRAN_MERCHANT_ID_BILL_PAYMENT);                      // :226
        tranRecord.moveTranMerchantName(TRAN_MERCHANT_NAME_BILL_PAYMENT);                  // :227
        tranRecord.moveTranMerchantCity(TRAN_MERCHANT_NOT_APPLICABLE);                     // :228
        tranRecord.moveTranMerchantZip(TRAN_MERCHANT_NOT_APPLICABLE);                      // :229

        // :230  PERFORM GET-CURRENT-TIMESTAMP
        String timestamp = getCurrentTimestamp(state);

        // :231-232  MOVE WS-TIMESTAMP TO TRAN-ORIG-TS
        //                               TRAN-PROC-TS
        //
        // One move, two receivers, both PIC X(26) and both filled with the identical twenty-six bytes.
        // The program has no separate processing time: origination and processing are the same instant.
        tranRecord.moveTranOrigTs(timestamp);
        tranRecord.moveTranProcTs(timestamp);

        // :233  PERFORM WRITE-TRANSACT-FILE - the transaction is added BEFORE the balance changes.
        writeTransactFile(state);

        // :234  COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT
        //
        // The program's one monetary computation, and the reason the trap above matters. TRAN-AMT is
        // read back OUT OF THE RECORD rather than reused from the local, because that is the field the
        // COBOL names and because reading it back proves the record round trip. Where the balance
        // carried ten integer digits the amount carries nine, so the two are NOT equal and the
        // difference is NOT zero.
        //
        // The subtraction is exact and the store truncates: scale 2, RoundingMode.DOWN, named in
        // CobolDecimal and nowhere else. The receiver is PIC S9(10)V99, so the result is stored at ten
        // integer digits - and a difference needing eleven quietly loses its high-order digit, because
        // the statement carries no ON SIZE ERROR clause.
        BigDecimal debitedBalance = CobolDecimal.subtract(balanceBeforePayment,
                tranRecord.tranAmt(), CobolDecimal.MONETARY_SCALE);
        state.accountRecord().setAcctCurrBal(CobolDecimal.storeAtPicture(debitedBalance,
                AccountRecord.MONETARY_INTEGER_DIGITS, CobolDecimal.MONETARY_SCALE));

        // :235  PERFORM UPDATE-ACCTDAT-FILE - and only now is the account rewritten.
        updateAcctdatFile(state);
    }

    // =================================================================================================
    // GET-CURRENT-TIMESTAMP - app/cbl/COBIL00C.cbl:249-267. Twenty-six bytes, and the last six of them
    // are always zeros.
    // =================================================================================================

    /**
     * {@code GET-CURRENT-TIMESTAMP}: composes the {@code WS-TIMESTAMP} image the transaction record's
     * two timestamp fields receive.
     *
     * <p>Four statements of CICS and four of COBOL, in order:
     * <ol>
     *   <li><strong>:251-253</strong> {@code EXEC CICS ASKTIME ABSTIME(WS-ABS-TIME)} - the absolute
     *       time, milliseconds since 1900-01-01, into a {@code PIC S9(15) COMP-3} item. Modelled as a
     *       {@code long}; see {@link #CICS_ABSTIME_EPOCH_OFFSET_MILLIS}. <strong>No packed-decimal codec
     *       is built for it</strong>: {@code COMP-3} appears in no copybook of this migration, so no
     *       persisted field needs nibble unpacking and this item never leaves memory;</li>
     *   <li><strong>:255-261</strong> {@code EXEC CICS FORMATTIME ... YYYYMMDD(WS-CUR-DATE-X10)
     *       DATESEP('-') TIME(WS-CUR-TIME-X08) TIMESEP(':')} - ten characters {@code YYYY-MM-DD} and
     *       eight characters {@code HH:MM:SS}, each component zero-filled to its declared digit count
     *       through the codec;</li>
     *   <li><strong>:263</strong> {@code INITIALIZE WS-TIMESTAMP} - zeroes the group's numeric items and
     *       leaves its separator {@code FILLER}s at their declared {@code VALUE}s, so byte 11 stays a
     *       space and byte 20 stays a full stop;</li>
     *   <li><strong>:264-266</strong> the date into bytes 1-10, the time into bytes 12-19, and
     *       {@code MOVE ZEROS TO WS-TIMESTAMP-TM-MS6}.</li>
     * </ol>
     *
     * <p><strong>The fractional part is always {@code 000000}.</strong> {@code FORMATTIME} reports no
     * sub-second component, and line 266 zeroes the six microsecond positions outright, so the composed
     * image ends in {@code .000000} however precise the clock behind it was. Deriving real microseconds
     * from the instant would put digits into {@code TRAN-ORIG-TS} that the COBOL fills with zeros, and a
     * field-for-field comparison would report it.
     *
     * <p><strong>The clock is read exactly once.</strong> Both {@code ASKTIME} and {@code FORMATTIME}
     * describe the same instant in the source - the second takes the first's result as its argument - so
     * taking two readings here could straddle a second boundary and produce a timestamp whose date and
     * time disagreed. The offset from Greenwich is not carried into the header, because it affects only
     * the five characters {@code FUNCTION CURRENT-DATE} appends and this program never reads that view
     * from this header: the screen's own date and time come from {@code POPULATE-HEADER-INFO}, which is
     * the controller's.
     *
     * @param state the execution's working storage; receives {@code WS-ABS-TIME},
     *              {@code WS-CUR-DATE-X10}, {@code WS-CUR-TIME-X08} and {@code WS-TIMESTAMP}
     * @return the twenty-six character image, {@code YYYY-MM-DD HH:MM:SS.000000}; never {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public String getCurrentTimestamp(PaymentState state) {
        requireState(state);

        // :251-253  EXEC CICS ASKTIME ABSTIME(WS-ABS-TIME). One reading, used for everything below.
        Instant instant = clock.instant();
        state.setAbsTime(instant.toEpochMilli() + CICS_ABSTIME_EPOCH_OFFSET_MILLIS);

        DateHeader header = DateHeader.of(codec, LocalDateTime.ofInstant(instant, clock.getZone()));
        DateHeader.CapturedDateTime captured = header.captured();

        // :255-261  FORMATTIME. The two separators are the source's DATESEP('-') and TIMESEP(':'), and
        // every component is zero-filled to its declared width through the codec's numeric helper.
        String date10 = codec.movePic9(captured.year(), DateHeader.YEAR_DIGITS)
                + DateHeader.TIMESTAMP_DATE_SEPARATOR
                + codec.movePic9(captured.month(), DateHeader.MONTH_DIGITS)
                + DateHeader.TIMESTAMP_DATE_SEPARATOR
                + codec.movePic9(captured.day(), DateHeader.DAY_DIGITS);
        String time08 = codec.movePic9(captured.hours(), DateHeader.HOURS_DIGITS)
                + DateHeader.TIME_SEPARATOR
                + codec.movePic9(captured.minutes(), DateHeader.MINUTE_DIGITS)
                + DateHeader.TIME_SEPARATOR
                + codec.movePic9(captured.seconds(), DateHeader.SECOND_DIGITS);
        state.setCurDateX10(codec.movePicX(date10, WS_CUR_DATE_X10_LENGTH));
        state.setCurTimeX08(codec.movePicX(time08, WS_CUR_TIME_X08_LENGTH));

        // :263-266  INITIALIZE, the two reference-modified moves, and MOVE ZEROS TO the microseconds.
        // The one factory that knows this group's separator FILLERs performs all four, so the separators
        // are spelled in exactly one place and the fractional part is the literal zeros of :266.
        String timestamp = header
                .withTimestampFromFormatTime(state.curDateX10(), state.curTimeX08())
                .wsTimestamp();
        state.setTimestamp(timestamp);
        return timestamp;
    }


    // =================================================================================================
    // The seven file operations - app/cbl/COBIL00C.cbl:343-547. Each is an EXEC CICS command followed by
    // an ordered EVALUATE WS-RESP-CD, and the ordering is the contract: the first matching WHEN wins and
    // WHEN OTHER is last. The repositories surface outcomes and never abend on a caller's behalf, so the
    // whole guard chain lives here where the COBOL puts it.
    // =================================================================================================

    /**
     * {@code READ-ACCTDAT-FILE} - lines 343 to 372.
     *
     * <p>{@code EXEC CICS READ DATASET(WS-ACCTDAT-FILE) INTO(ACCOUNT-RECORD)
     * LENGTH(LENGTH OF ACCOUNT-RECORD) RIDFLD(ACCT-ID) KEYLENGTH(LENGTH OF ACCT-ID) UPDATE}, then three
     * arms: {@code NORMAL} continues, {@code NOTFND} rejects with {@link #MSG_ACCOUNT_ID_NOT_FOUND}, and
     * {@code WHEN OTHER} writes the response diagnostic and rejects with
     * {@link #MSG_UNABLE_TO_LOOKUP_ACCOUNT}. Both failing arms place the cursor in the account field and
     * send the screen.
     *
     * <p><strong>{@code UPDATE} is stated, so the read takes a record lock</strong> that the task holds
     * to its syncpoint. That is what makes the write-then-debit-then-rewrite sequence at lines 233-235
     * coherent, and it is why this method - and therefore the whole paragraph - must run inside the unit
     * of work {@link #processEnterKey(String, String, NavigationContext)} opens.
     *
     * <p>On success the whole 300-byte record replaces the working area, {@code FILLER} and all, so the
     * rewrite at line 235 writes back the bytes that were read with only the balance changed. On either
     * failing arm the working area is left exactly as it was, which is the initial state described on
     * {@link PaymentState#accountRecord()} - and that is where the stale balance of lines 193-194 comes
     * from.
     *
     * @param state the execution's working storage; the record identification field must already be set
     *              from the two-receiver {@code MOVE} at lines 170-171
     * @throws NullPointerException  if {@code state} is {@code null}
     * @throws IllegalStateException if no unit of work is open, since a locking read outside one would
     *                               hold nothing
     */
    public void readAcctdatFile(PaymentState state) {
        requireState(state);
        AccountRepository.ReadResult result = accountRepository.readForUpdate(state.acctIdRidfld());
        state.setResponseCodes(result.cicsResp().orElse(FileStatus.RESP_NOT_REPORTED),
                result.cicsResp2());

        if (result.isFound()) {
            // :357-358  WHEN DFHRESP(NORMAL) / CONTINUE - and the record area is replaced.
            state.setAccountRecord(result.account().orElseThrow(
                    () -> new IllegalStateException("A successful read of " + WS_ACCTDAT_FILE.trim()
                            + " carries the decoded record; AccountRepository.ReadResult enforces that "
                            + "invariant at construction, so an empty record here would mean the "
                            + "repository's own contract had been broken")));
            return;
        }
        if (result.isNotFound()) {
            // :359-364  WHEN DFHRESP(NOTFND)
            rejectAndSend(state, MSG_ACCOUNT_ID_NOT_FOUND, CursorField.ACTIDIN);
            return;
        }
        // :365-371  WHEN OTHER
        display(state);
        rejectAndSend(state, MSG_UNABLE_TO_LOOKUP_ACCOUNT, CursorField.ACTIDIN);
    }

    /**
     * {@code UPDATE-ACCTDAT-FILE} - lines 377 to 403.
     *
     * <p>{@code EXEC CICS REWRITE DATASET(WS-ACCTDAT-FILE) FROM(ACCOUNT-RECORD)
     * LENGTH(LENGTH OF ACCOUNT-RECORD)}, then {@code NORMAL} continues, {@code NOTFND} rejects with
     * {@link #MSG_ACCOUNT_ID_NOT_FOUND} - the same text the read uses - and {@code WHEN OTHER} rejects
     * with {@link #MSG_UNABLE_TO_UPDATE_ACCOUNT}.
     *
     * <p>All 300 bytes are written. The rewrite is addressed by the key the record itself carries, which
     * is what {@code FROM ACCOUNT-RECORD} means on a randomly accessed indexed file, so there is no
     * separate key argument here and none in the repository.
     *
     * <p>No change check precedes it. The account and card update programs both re-read and compare
     * before rewriting - their {@code 9300-CHECK-CHANGE-IN-REC} paragraph - and <strong>this program has
     * no such paragraph at all</strong>. Adding one would be a new feature; the lock taken by the
     * locking read is the only concurrency control the program has.
     *
     * @param state the execution's working storage, carrying the record to write
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void updateAcctdatFile(PaymentState state) {
        requireState(state);
        AccountRepository.WriteResult result = accountRepository.rewrite(state.accountRecord());
        state.setResponseCodes(result.cicsResp().orElse(FileStatus.RESP_NOT_REPORTED),
                result.cicsResp2());

        if (result.isWritten()) {
            // :388-389  WHEN DFHRESP(NORMAL) / CONTINUE
            state.setAccountRewritten();
            return;
        }
        if (result.isNotFound()) {
            // :390-395  WHEN DFHRESP(NOTFND)
            rejectAndSend(state, MSG_ACCOUNT_ID_NOT_FOUND, CursorField.ACTIDIN);
            return;
        }
        // :396-402  WHEN OTHER
        display(state);
        rejectAndSend(state, MSG_UNABLE_TO_UPDATE_ACCOUNT, CursorField.ACTIDIN);
    }

    /**
     * {@code READ-CXACAIX-FILE} - lines 408 to 436.
     *
     * <p>{@code EXEC CICS READ DATASET(WS-CXACAIX-FILE) INTO(CARD-XREF-RECORD)
     * LENGTH(LENGTH OF CARD-XREF-RECORD) RIDFLD(XREF-ACCT-ID) KEYLENGTH(LENGTH OF XREF-ACCT-ID)} - and
     * note what is <strong>absent</strong>: there is no {@code UPDATE} option, so this read takes no
     * lock. The cross-reference is consulted for the card number and is never written by this program.
     *
     * <p>An <strong>alternate-index path</strong>, not a dataset: eleven bytes of account identifier
     * against a file whose CSD definition reads {@code ALTERNATE INDEX TO CCXREF VIA ACCOUNT KEY}. It is
     * therefore a second finder method on the cross-reference repository, and no repository, table or
     * index is created for it here.
     *
     * <p>Three arms: {@code NORMAL} continues, {@code NOTFND} rejects with
     * {@link #MSG_ACCOUNT_ID_NOT_FOUND} - <em>the same sentence the account paths use</em>, which is
     * deliberate and not differentiated - and {@code WHEN OTHER} rejects with
     * {@link #MSG_UNABLE_TO_LOOKUP_XREF_AIX}. A duplicate on the alternate key lands on {@code WHEN
     * OTHER}, because the {@code EVALUATE} names only {@code NORMAL} and {@code NOTFND}.
     *
     * <p>Raising the error flag here does <strong>not</strong> stop the payment - see
     * {@link #makeBillPayment(PaymentState)}.
     *
     * @param state the execution's working storage; the alternate-index key must already be set from the
     *              two-receiver {@code MOVE} at lines 170-171
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void readCxacaixFile(PaymentState state) {
        requireState(state);
        CardXrefRepository.ReadResult result =
                cardXrefRepository.readByAccountIdViaAltIndex(state.xrefAcctIdRidfld());
        state.setResponseCodes(result.cicsResp(), result.cicsResp2());

        if (result.isFound()) {
            // :421-422  WHEN DFHRESP(NORMAL) / CONTINUE - and the record area is replaced.
            state.setCardXrefRecord(result.record().orElseThrow(
                    () -> new IllegalStateException("A successful read of " + WS_CXACAIX_FILE.trim()
                            + " carries the decoded record; CardXrefRepository.ReadResult enforces "
                            + "that invariant at construction, so an empty record here would mean the "
                            + "repository's own contract had been broken")));
            return;
        }
        if (result.isNotFound()) {
            // :423-428  WHEN DFHRESP(NOTFND)
            rejectAndSend(state, MSG_ACCOUNT_ID_NOT_FOUND, CursorField.ACTIDIN);
            return;
        }
        // :429-435  WHEN OTHER
        display(state);
        rejectAndSend(state, MSG_UNABLE_TO_LOOKUP_XREF_AIX, CursorField.ACTIDIN);
    }

    /**
     * {@code STARTBR-TRANSACT-FILE} - lines 441 to 467: the ordered {@code EVALUATE} that follows the
     * browse position.
     *
     * <p>{@code EXEC CICS STARTBR DATASET(WS-TRANSACT-FILE) RIDFLD(TRAN-ID)
     * KEYLENGTH(LENGTH OF TRAN-ID)} carries <strong>no {@code GTEQ} and no {@code EQUAL}</strong>
     * keyword. None is added here, and no inference is drawn about what a live CICS region "would" have
     * returned: the coded arms are reproduced and the reported outcome selects among them.
     *
     * <p><strong>Why the outcome is a parameter.</strong>
     * {@link TransactionRepository#startBrowse(BrowseDirection)} reports no status for a position,
     * because positioning performs no backend call. The payment sequence therefore supplies
     * {@link #STARTBR_POSITIONING_OUTCOME}, which is the only outcome a Java execution can observe. The
     * other two arms are kept - deleting an arm of an ordered {@code EVALUATE} would change the guard
     * chain - and are reachable through this method so that each one is exercised and stays correct.
     *
     * <p>Three arms: {@link Outcome#OK} continues, {@link Outcome#NOT_FOUND} rejects with
     * {@link #MSG_TRANSACTION_ID_NOT_FOUND}, and everything else writes the diagnostic and rejects with
     * {@link #MSG_UNABLE_TO_LOOKUP_TRANSACTION}. Note that {@link Outcome#END_OF_FILE} lands on the
     * final arm here, unlike in {@code READPREV} where it has an arm of its own.
     *
     * @param state              the execution's working storage
     * @param positioningOutcome the outcome CICS reported for the position
     * @throws NullPointerException if {@code state} or {@code positioningOutcome} is {@code null}
     */
    public void startbrTransactFile(PaymentState state, Outcome positioningOutcome) {
        requireState(state);
        Objects.requireNonNull(positioningOutcome, "A positioning outcome is required: "
                + "app/cbl/COBIL00C.cbl:451-467 evaluates WS-RESP-CD after the STARTBR, and this method "
                + "is that EVALUATE");

        if (positioningOutcome == Outcome.OK) {
            // :452-453  WHEN DFHRESP(NORMAL) / CONTINUE
            state.setBrowseStarted();
            return;
        }
        if (positioningOutcome == Outcome.NOT_FOUND) {
            // :454-459  WHEN DFHRESP(NOTFND)
            rejectAndSend(state, MSG_TRANSACTION_ID_NOT_FOUND, CursorField.ACTIDIN);
            return;
        }
        // :460-466  WHEN OTHER
        display(state);
        rejectAndSend(state, MSG_UNABLE_TO_LOOKUP_TRANSACTION, CursorField.ACTIDIN);
    }

    /**
     * {@code READPREV-TRANSACT-FILE} - lines 472 to 496: the ordered {@code EVALUATE} that follows the
     * backward read.
     *
     * <p>{@code EXEC CICS READPREV DATASET(WS-TRANSACT-FILE) INTO(TRAN-RECORD)
     * LENGTH(LENGTH OF TRAN-RECORD) RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID)}, and its three arms
     * differ from every other paragraph's:
     *
     * <ul>
     *   <li><strong>{@code NORMAL}</strong> continues, and {@code INTO(TRAN-RECORD)} has replaced the
     *       record area - so {@code TRAN-ID} now holds the highest existing identifier;</li>
     *   <li><strong>{@code ENDFILE}</strong> is <em>not</em> an error. It performs
     *       {@code MOVE ZEROS TO TRAN-ID} and nothing else: no flag, no message, no send. This is how an
     *       empty transaction master yields identifier {@code 0000000000000001} for the first payment
     *       ever made rather than failing;</li>
     *   <li><strong>{@code WHEN OTHER}</strong> writes the diagnostic and rejects with
     *       {@link #MSG_UNABLE_TO_LOOKUP_TRANSACTION}, and leaves the record identification field
     *       holding the {@code HIGH-VALUES} of line 212 - which is what makes the next two statements of
     *       the payment sequence a data exception. See {@link #makeBillPayment(PaymentState)}.</li>
     * </ul>
     *
     * <p>A {@code NOTFND} or a duplicate lands on the final arm, because the {@code EVALUATE} names only
     * {@code NORMAL} and {@code ENDFILE}.
     *
     * <p>The read itself is issued by the payment sequence, which owns the browse handle; this method is
     * the {@code EVALUATE} alone, so every arm of it is reachable from a plain unit test with a
     * hand-built outcome and no browse in the path.
     *
     * @param state  the execution's working storage
     * @param result the outcome the backward read reported
     * @throws NullPointerException if {@code state} or {@code result} is {@code null}
     */
    public void readprevTransactFile(PaymentState state, TransactionRepository.ReadResult result) {
        requireState(state);
        Objects.requireNonNull(result, "A read outcome is required: app/cbl/COBIL00C.cbl:484-496 "
                + "evaluates WS-RESP-CD after the READPREV, and this method is that EVALUATE");
        state.setResponseCodes(result.cicsResp().orElse(FileStatus.RESP_NOT_REPORTED),
                result.cicsResp2());

        if (result.isFound()) {
            // :485-486  WHEN DFHRESP(NORMAL) / CONTINUE - and INTO(TRAN-RECORD) replaced the area, so
            // the record identification field now holds the highest existing identifier.
            TranRecord highest = result.record().orElseThrow(
                    () -> new IllegalStateException("A successful read of " + WS_TRANSACT_FILE
                            + " carries the decoded record; TransactionRepository.ReadResult enforces "
                            + "that invariant at construction, so an empty record here would mean the "
                            + "repository's own contract had been broken"));
            state.setTranRecord(highest);
            state.setTranIdRidfld(highest.tranId());
            return;
        }
        if (result.isEndOfFile()) {
            // :487-488  WHEN DFHRESP(ENDFILE) / MOVE ZEROS TO TRAN-ID. No flag, no message, no send.
            state.setTranIdRidfld(ZEROS_TRAN_ID);
            return;
        }
        // :489-495  WHEN OTHER - and the record identification field keeps its HIGH-VALUES.
        display(state);
        rejectAndSend(state, MSG_UNABLE_TO_LOOKUP_TRANSACTION, CursorField.ACTIDIN);
    }

    /**
     * {@code ENDBR-TRANSACT-FILE} - lines 501 to 505.
     *
     * <p>{@code EXEC CICS ENDBR DATASET(WS-TRANSACT-FILE)}, and that is the entire paragraph. There is
     * <strong>no {@code RESP} option, no {@code RESP2} option, no {@code EVALUATE} and no error handling
     * of any kind</strong>. That is preserved exactly: no status is captured, no outcome is classified,
     * no exception is caught and no condition is reported. Adding a check here would be adding a branch
     * the program does not have, and the parity harness would see the difference in the send count of
     * every path that reaches it.
     *
     * @param browse the browse handle to end; must not be {@code null}
     * @throws NullPointerException if {@code browse} is {@code null}
     */
    public void endbrTransactFile(TransactionRepository.Browse browse) {
        Objects.requireNonNull(browse, "A browse handle is required to end a browse: "
                + "app/cbl/COBIL00C.cbl:503-505 names the dataset whose browse is being ended");
        browse.endBrowse();
    }

    /**
     * {@code WRITE-TRANSACT-FILE} - lines 510 to 547, and the only arm of the program that reports
     * success.
     *
     * <p>{@code EXEC CICS WRITE DATASET(WS-TRANSACT-FILE) FROM(TRAN-RECORD)
     * LENGTH(LENGTH OF TRAN-RECORD) RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID)}. All 350 bytes are
     * written, the trailing {@code FILLER PIC X(20)} included, and the row is keyed on the identifier the
     * record itself carries.
     *
     * <p><strong>The successful arm, lines 523-532, is five statements and their order matters:</strong>
     * <ol>
     *   <li>{@code PERFORM INITIALIZE-ALL-FIELDS} - blanks the three input fields and the message and
     *       parks the cursor in the account field;</li>
     *   <li>{@code MOVE SPACES TO WS-MESSAGE} - redundant, since the paragraph just did it, and
     *       preserved anyway;</li>
     *   <li>{@code MOVE DFHGREEN TO ERRMSGC} - the program's one attribute override;</li>
     *   <li>the {@code STRING} that composes the confirmation;</li>
     *   <li>{@code PERFORM SEND-BILLPAY-SCREEN}. Line 242 then sends it a second time, so a successful
     *       payment sends twice.</li>
     * </ol>
     *
     * <p><strong>{@code DUPKEY} and {@code DUPREC} share one arm</strong> - lines 533-534 are two
     * consecutive {@code WHEN} clauses with no statements between them, which selects the next body -
     * reporting {@link #MSG_TRAN_ID_ALREADY_EXIST}. Nothing is written on that arm.
     *
     * @param state the execution's working storage, carrying the record to add
     * @throws NullPointerException  if {@code state} is {@code null}
     * @throws IllegalStateException if no transaction record has been assembled, which cannot happen on
     *                               the program's own path
     */
    public void writeTransactFile(PaymentState state) {
        requireState(state);
        TranRecord record = state.tranRecord().orElseThrow(() -> new IllegalStateException(
                "A transaction record must be assembled before it can be added: "
                        + "app/cbl/COBIL00C.cbl:218-232 builds TRAN-RECORD and :512-520 writes it, so "
                        + "reaching the write with no record would mean the payment sequence had been "
                        + "entered other than at its first statement"));
        TransactionRepository.WriteResult result = transactionRepository.write(record);
        state.setResponseCodes(result.cicsResp().orElse(FileStatus.RESP_NOT_REPORTED),
                result.cicsResp2());

        if (result.isWritten()) {
            // :523-532  WHEN DFHRESP(NORMAL)
            initializeAllFields(state);                                                    // :524
            state.setMessageSpaces();                                                      // :525
            state.setMessageHighlight(MESSAGE_HIGHLIGHT_GREEN);                            // :526
            state.setMessage(successMessage(record.tranId()));                             // :527-531
            sendBillpayScreen(state);                                                      // :532
            return;
        }
        if (result.isDuplicate()) {
            // :533-539  WHEN DFHRESP(DUPKEY) / WHEN DFHRESP(DUPREC) - one shared body.
            rejectAndSend(state, MSG_TRAN_ID_ALREADY_EXIST, CursorField.ACTIDIN);
            return;
        }
        // :540-546  WHEN OTHER
        display(state);
        rejectAndSend(state, MSG_UNABLE_TO_ADD_TRANSACTION, CursorField.ACTIDIN);
    }

    /**
     * The {@code STRING} at lines 527 to 531, which composes the payment confirmation.
     *
     * <p>{@code STRING 'Payment successful. ' DELIMITED BY SIZE ' Your Transaction ID is ' DELIMITED BY
     * SIZE TRAN-ID DELIMITED BY SPACE '.' DELIMITED BY SIZE INTO WS-MESSAGE}.
     *
     * <p>Two properties of the verb are being reproduced, and both matter:
     * <ul>
     *   <li><strong>{@code DELIMITED BY SPACE}</strong> on the identifier takes its characters up to the
     *       first space. A {@code TRAN-ID} is a sixteen-digit zero-padded value and contains no space,
     *       so all sixteen are taken - but the delimiter is honoured rather than assumed away, because a
     *       blank identifier would contribute nothing at all;</li>
     *   <li><strong>{@code STRING} does not blank the receiver's tail.</strong> It overlays from the
     *       receiver's first byte and leaves the rest as it was, which is exactly why line 525 moves
     *       {@code SPACES} into {@code WS-MESSAGE} immediately beforehand. Both steps are reproduced:
     *       the caller blanks the field, and this method returns only the composed characters, which the
     *       state then stores at the field's declared width.</li>
     * </ul>
     *
     * <p>The composed text is 20 + 24 + 16 + 1 = 61 characters, comfortably inside
     * {@link #WS_MESSAGE_LENGTH} and therefore inside {@link #ERR_MSG_LENGTH} once the screen field
     * truncates two bytes off a value that has 19 spaces to spare.
     *
     * @param tranId {@code TRAN-ID}, the sixteen characters of the record just written
     * @return the composed confirmation; never {@code null}
     * @throws NullPointerException if {@code tranId} is {@code null}
     */
    private String successMessage(String tranId) {
        Objects.requireNonNull(tranId, "The identifier of the transaction just written is required: "
                + "app/cbl/COBIL00C.cbl:529 strings TRAN-ID into the confirmation");
        return codec.concatenateDelimitedBySize(SUCCESS_PREFIX, SUCCESS_INFIX,
                delimitedBySpace(tranId), SUCCESS_SUFFIX);
    }

    /**
     * A {@code STRING} operand's contribution under {@code DELIMITED BY SPACE}: the characters up to,
     * but not including, the first space.
     *
     * <p>Distinct from trimming. Trailing spaces are removed by both, but a value of {@code "AB CD"}
     * contributes {@code "AB"} here and {@code "AB CD"} to a trim, and a value that begins with a space
     * contributes nothing at all.
     *
     * <p>Visible to the package rather than private so that the {@code DELIMITED BY SPACE} semantic can
     * be asserted directly across all four of its cases - no space, a leading space, an interior space
     * and an empty operand. The program's own identifier is sixteen zero-padded digits and therefore
     * never contains a space, so the truncating arm is unreachable through {@code makeBillPayment}; the
     * arm nevertheless has to exist and has to be correct, because it is the entire difference between
     * {@code DELIMITED BY SPACE} and {@code DELIMITED BY SIZE}.
     *
     * @param operand the sending item
     * @return its characters up to the first space; empty when it begins with one
     */
    static String delimitedBySpace(String operand) {
        int firstSpace = operand.indexOf(' ');
        return firstSpace < 0 ? operand : operand.substring(0, firstSpace);
    }


    // =================================================================================================
    // The screen paragraphs - app/cbl/COBIL00C.cbl:289-301 and :552-566.
    // =================================================================================================

    /**
     * {@code SEND-BILLPAY-SCREEN} - lines 289 to 301.
     *
     * <p>Three statements: {@code PERFORM POPULATE-HEADER-INFO},
     * {@code MOVE WS-MESSAGE TO ERRMSGO OF COBIL0AO}, and
     * {@code EXEC CICS SEND MAP('COBIL0A') MAPSET('COBIL00') FROM(COBIL0AO) ERASE CURSOR}.
     *
     * <p><strong>This method performs the second of the three and records the third.</strong> The first,
     * {@code POPULATE-HEADER-INFO} at lines 319-338, writes the two title lines, the transaction and
     * program names and the date and time header from {@code FUNCTION CURRENT-DATE}; those five fields
     * are the controller's to fill, because they are pure presentation and involve no decision. What is
     * recorded here is what the service owns: the message the screen carries, the values of the three
     * data fields at the moment of the send, where the cursor was requested and what colour the message
     * line had.
     *
     * <p><strong>The message move loses two bytes off the right.</strong> {@code WS-MESSAGE} is
     * {@code PIC X(80)} and {@code ERRMSGO} is {@code PIC X(78)}, and a {@code PIC X} receiver is filled
     * from its leftmost position with the overflow discarded - so the surviving characters are the
     * leading 78 and never the trailing 78. It is done through the codec's alphanumeric helper so the
     * direction is a choice made at this line rather than an accident of a Java assignment. No message
     * in this program is anywhere near 78 characters, so nothing is lost in practice; the rule is applied
     * because it is the rule.
     *
     * <p>Each call appends a snapshot to {@link PaymentState#sentScreens()}. That is what makes the
     * send <em>count</em> and the send <em>order</em> assertable, which matters because several paths
     * send twice and because the stale-balance defect is only visible as a difference between what the
     * first send carried and what the state ended up holding.
     *
     * @param state the execution's working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void sendBillpayScreen(PaymentState state) {
        requireState(state);
        // :293  MOVE WS-MESSAGE TO ERRMSGO OF COBIL0AO - 80 into 78, truncating on the right.
        state.setErrMsg(codec.movePicX(state.message(), ERR_MSG_LENGTH));
        // :295-301  EXEC CICS SEND MAP ... ERASE CURSOR.
        state.recordSend();
    }

    /**
     * {@code CLEAR-CURRENT-SCREEN} - lines 552 to 555: {@code PERFORM INITIALIZE-ALL-FIELDS} then
     * {@code PERFORM SEND-BILLPAY-SCREEN}.
     *
     * <p>Two call sites, and they are very different. Line 137 reaches it from the {@code DFHPF4} arm of
     * the key switch, where clearing the screen is the whole point. Line 180 reaches it from the
     * {@code 'N'} arm of the confirmation switch, where <strong>the send happens before line 181 raises
     * the error flag</strong> - so the screen the user receives carries a blank message rather than an
     * error, and the flag then only suppresses the stages that follow. That ordering is observable and is
     * preserved.
     *
     * @param state the execution's working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void clearCurrentScreen(PaymentState state) {
        requireState(state);
        initializeAllFields(state);                                                        // :554
        sendBillpayScreen(state);                                                          // :555
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS} - lines 560 to 566.
     *
     * <p>{@code MOVE -1 TO ACTIDINL} places the cursor in the account field, and one {@code MOVE SPACES}
     * with <strong>four receivers</strong> blanks {@code ACTIDINI}, {@code CURBALI}, {@code CONFIRMI} and
     * {@code WS-MESSAGE}. Each is blanked at its own declared width, which is why the four are written
     * out separately here rather than assigned a shared empty string.
     *
     * <p>Note what is <em>not</em> blanked: {@code ERRMSGO}. The message field keeps whatever the last
     * send put in it until the next send overwrites it, which is why the successful-{@code WRITE} arm can
     * call this paragraph and then compose a confirmation into the message that has just been blanked.
     *
     * @param state the execution's working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void initializeAllFields(PaymentState state) {
        requireState(state);
        state.setCursorField(CursorField.ACTIDIN);                                         // :562
        state.setActIdIn(spaces(ACT_ID_IN_LENGTH));                                        // :563
        state.setCurBal(spaces(CUR_BAL_LENGTH));                                           // :564
        state.setConfirm(spaces(CONFIRM_LENGTH));                                          // :565
        state.setMessageSpaces();                                                          // :566
    }

    /**
     * The {@code WHEN OTHER} arm of the key switch - lines 138 to 141.
     *
     * <p>{@code MOVE 'Y' TO WS-ERR-FLG}, {@code MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE},
     * {@code PERFORM SEND-BILLPAY-SCREEN}. Reached when the terminal reports a key that is neither
     * {@code ENTER}, {@code PF3} nor {@code PF4}.
     *
     * <p>Three details are worth stating. The message comes from {@code CSMSG01Y}, which declares it
     * {@code PIC X(50)}, and {@code WS-MESSAGE} is {@code PIC X(80)}, so this is a widening alphanumeric
     * move: the fifty characters are left-aligned and the receiver is space-padded on the right to
     * eighty. <strong>No cursor statement appears on this arm</strong>, so the cursor field stays
     * whatever it was - {@link CursorField#NONE} on a fresh execution, which means the terminal applies
     * the mapset's own {@code IC} field. And the error flag <em>is</em> raised, unlike on the
     * confirmation prompt at line 237.
     *
     * <p>It lives here rather than in the controller because it is a decision arm with a message and a
     * flag, and every such arm of this program is in this class. The dispatch on the key itself, and the
     * {@code DFHPF3} arm's transfer of control, are navigation and are the controller's.
     *
     * @param state the execution's working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void invalidKeyPressed(PaymentState state) {
        requireState(state);
        state.setErrFlagOn();                                                              // :139
        state.setMessage(SystemMessages.CCDA_MSG_INVALID_KEY);                             // :140
        sendBillpayScreen(state);                                                          // :141
    }

    /**
     * The four statements every rejecting arm of every file operation shares, in the source's order:
     * raise the flag, set the message, place the cursor, send the screen.
     *
     * <p>Named once because the nine rejecting arms are identical apart from the text and, in one case,
     * the cursor target - and because the order is part of the behaviour: the message has to be in
     * {@code WS-MESSAGE} before {@link #sendBillpayScreen(PaymentState)} copies it into
     * {@code ERRMSGO}.
     *
     * @param state   the execution's working storage
     * @param message the literal the source moves into {@code WS-MESSAGE}
     * @param cursor  the field the source's {@code MOVE -1} names
     */
    private void rejectAndSend(PaymentState state, String message, CursorField cursor) {
        state.setErrFlagOn();
        state.setMessage(message);
        state.setCursorField(cursor);
        sendBillpayScreen(state);
    }

    // =================================================================================================
    // The WS-CURR-BAL edit mask, and the three move helpers. Every one of them is a COBOL rule expressed
    // once, so that no call site has to remember which end a receiver truncates.
    // =================================================================================================

    /**
     * {@code MOVE ACCT-CURR-BAL TO WS-CURR-BAL} - line 193: renders a balance through the
     * {@code PIC +9999999999.99} numeric-edited picture.
     *
     * <p>Fourteen characters, position by position:
     * <ol>
     *   <li>the {@code +} of the picture - a <strong>forced sign</strong>, so it is always emitted:
     *       {@code '+'} for a value of zero or above and {@code '-'} below. It is not a floating sign
     *       and it is not suppressed;</li>
     *   <li>ten {@code 9} positions, <strong>zero-filled</strong>. A {@code 9} always emits a digit, so
     *       {@code 1234.56} renders {@code "+0000001234.56"}. Blank suppression would need {@code Z}
     *       positions and the picture has none;</li>
     *   <li>the {@code .} of the picture, an actual character position;</li>
     *   <li>two {@code 9} positions for the fraction.</li>
     * </ol>
     *
     * <p>Written out rather than delegated. {@code String.format("%+,013.2f", ...)} would require a
     * {@code double}, which rule R4 forbids for anything derived from a {@code PIC 9...V...} field, and
     * {@code NumberFormat} applies a locale's grouping and decimal separators, which a mainframe edit
     * mask does not have. The digits come from {@link BigDecimal#unscaledValue()} of the absolute value
     * at scale 2, so no formatter and no floating-point conversion is involved anywhere.
     *
     * <p>The receiver of the ten integer positions is the same width as {@code ACCT-CURR-BAL}'s own
     * picture, so a stored balance always fits. A value carrying more integer digits than the mask can
     * hold loses its high-order digits, which is what a numeric-edited receiver does, and is applied here
     * through the codec's numeric helper so that the direction is the one COBOL uses.
     *
     * @param balance the value to edit; must not be {@code null}. Any scale is accepted and truncated to
     *                two fraction digits toward zero, because {@code ROUNDED} is absent from the source
     * @return exactly {@link #WS_CURR_BAL_LENGTH} characters, which is exactly
     *         {@link #CUR_BAL_LENGTH} - an exact fit for the screen field
     * @throws NullPointerException if {@code balance} is {@code null}
     */
    public String editCurrBal(BigDecimal balance) {
        Objects.requireNonNull(balance, "A balance is required to edit: app/cbl/COBIL00C.cbl:193 moves "
                + "ACCT-CURR-BAL into the PIC +9999999999.99 item, and a numeric field has no null");

        // Truncate the fraction and reduce the integer part exactly as the receiving picture does, in the
        // one class that names the scale and the rounding mode.
        BigDecimal stored = CobolDecimal.storeAtPicture(balance, CURR_BAL_INTEGER_DIGITS,
                CURR_BAL_FRACTION_DIGITS);
        char sign = stored.signum() < 0 ? CURR_BAL_SIGN_NEGATIVE : CURR_BAL_SIGN_POSITIVE;

        // unscaledValue() of a scale-2 value is the value times one hundred, so its digits are the ten
        // integer positions followed by the two fraction positions with no separator - precisely the
        // twelve digit positions of the picture. abs() first, because the sign has its own position.
        String digits = codec.movePic9(stored.abs().unscaledValue().toString(),
                CURR_BAL_INTEGER_DIGITS + CURR_BAL_FRACTION_DIGITS);

        return sign + digits.substring(0, CURR_BAL_INTEGER_DIGITS)
                + CURR_BAL_DECIMAL_POINT + digits.substring(CURR_BAL_INTEGER_DIGITS);
    }

    /**
     * Materialises a symbolic-map field at its declared width, so that every test and every move below
     * it operates on the number of characters the copybook declares.
     *
     * <p>A COBOL screen field is always exactly its declared width. A JSON string is not, so this is the
     * one place the two are reconciled, and it reconciles them the way the map does:
     * <ul>
     *   <li>{@code null} becomes the field filled with {@code LOW-VALUES}, which is the state
     *       {@code MOVE LOW-VALUES TO COBIL0AO} at line 114 produces. It stays distinguishable from
     *       spaces, because line 159 and line 182 test the two separately;</li>
     *   <li>anything else goes through the alphanumeric move rule - left-aligned, space-padded on the
     *       right, truncated on the right - because that is how the value reached the field in the first
     *       place.</li>
     * </ul>
     *
     * @param value the bound value, possibly {@code null} and of any length
     * @param width the field's declared width
     * @return exactly {@code width} characters
     */
    private String materialise(String value, int width) {
        return value == null ? lowValues(width) : codec.movePicX(value, width);
    }

    /**
     * The COBOL test {@code IF <field> = SPACES OR LOW-VALUES}, which lines 159, 182-183 and 199 all
     * perform.
     *
     * <p>Two figurative constants, tested separately because COBOL treats them as different values: a
     * field of blanks and a field of {@code X'00'} bytes are not equal to one another, and a program that
     * tests only one of them behaves differently from this one. A field is equal to a figurative constant
     * when <strong>every</strong> character position matches it, so a partially blank field satisfies
     * neither test - which is why {@code "123        "} passes the empty check at line 159 and goes on to
     * become a record identification field that finds nothing.
     *
     * @param field the field's characters, already at its declared width
     * @return {@code true} when every position is a space, or every position is {@code LOW-VALUES}
     */
    private static boolean isSpacesOrLowValues(String field) {
        boolean allSpaces = true;
        boolean allLowValues = true;
        for (int position = 0; position < field.length(); position++) {
            char character = field.charAt(position);
            if (character != ' ') {
                allSpaces = false;
            }
            if (character != LOW_VALUES) {
                allLowValues = false;
            }
        }
        // An empty field satisfies both loops trivially, and an empty screen field is a blank one, so the
        // disjunction is the right answer for it too.
        return allSpaces || allLowValues;
    }

    /**
     * {@code MOVE ACTIDINI OF COBIL0AI TO ACCT-ID XREF-ACCT-ID} - lines 170 and 171: an alphanumeric
     * {@code PIC X(11)} sender into two {@code PIC 9(11)} receivers.
     *
     * <p><strong>Equal widths, so the eleven bytes are copied.</strong> COBOL treats an alphanumeric
     * sender in a numeric move as an unsigned integer and aligns it on the receiver's implied decimal
     * point; with eleven digit positions receiving eleven characters there is nothing to pad and nothing
     * to truncate, and the receiving field ends up holding the sender's bytes whatever they are. That is
     * the behaviour reproduced here, and it is the reason a partially typed account identifier reaches
     * the file as {@code "123        "} and simply misses rather than being reshaped into
     * {@code "00000000123"}.
     *
     * <p>The move is routed through the codec's numeric helper for a digit image, which is the only image
     * the program's own screen can legitimately produce and which the helper returns unchanged at equal
     * width. A non-digit image cannot go through the helper - it refuses one by design, so that a data
     * defect in a dataset is never silently reinterpreted - and is carried verbatim instead, which is
     * exactly what the byte copy above produces. The distinction is deliberate and is drawn on
     * <em>provenance</em>: this value is untrusted terminal input and the COBOL does not validate it, so
     * inventing a rejection here would add an error path the program does not have. A value read from a
     * dataset is a different matter, and line 216 treats it differently for that reason.
     *
     * @param field the screen field's characters, already at its declared width
     * @param width the receiving numeric field's digit count, equal to the sender's width
     * @return exactly {@code width} characters, ready to be used as a record identification field
     */
    private String moveScreenIdToNumericField(String field, int width) {
        return isAllDigits(field) ? codec.movePic9(field, width) : field;
    }

    /**
     * Whether every character position holds a digit.
     *
     * <p>Visible to the package rather than private for the same reason as
     * {@link #delimitedBySpace(String)}: the zero-length guard is unreachable through
     * {@link #processEnterKey(String, String, NavigationContext)}, because the screen field is always
     * materialised to its declared eleven characters before it arrives, yet the guard has to be present
     * so that the helper's contract holds for every argument rather than only for the one width its
     * single caller happens to supply.
     *
     * @param value the characters to test
     * @return {@code true} when {@code value} is non-empty and every character is {@code '0'} to
     *         {@code '9'}
     */
    static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int position = 0; position < value.length(); position++) {
            char character = value.charAt(position);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    // =================================================================================================
    // Diagnostics and guards.
    // =================================================================================================

    /**
     * The {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} of the six {@code WHEN OTHER} arms -
     * lines 366, 397, 430, 461, 490 and 541.
     *
     * <p>Recorded on the state so a parity case can assert it, and logged so a running system shows it.
     * The statement emits two fixed literals and two response codes and nothing else, so no record
     * content, no account identifier and no operator input can reach a log through it.
     *
     * @param state the execution's working storage, carrying the two codes the last operation reported
     */
    private void display(PaymentState state) {
        String text = DISPLAY_RESP_PREFIX + respImage(state.respCd())
                + DISPLAY_REAS_PREFIX + respImage(state.reasCd());
        state.recordDisplay(text);
        LOG.info(text);
    }

    /**
     * A {@code PIC S9(09) COMP} response or reason code as {@code DISPLAY} renders it - or, where the
     * operation reported none, an image of the same width that cannot be mistaken for a code.
     *
     * <p>{@code DISPLAY} concatenates its operands at their declared widths, so both branches are
     * exactly {@link #WS_RESP_CD_DIGITS} characters and the composed line keeps the shape the source
     * gives it. The unreported case cannot go through the codec's numeric helper at all:
     * {@link FileStatus#RESP_NOT_REPORTED} is negative and an unsigned {@code PIC 9} receiver has no
     * image for a negative value, so the helper refuses it. That refusal is why this method exists rather
     * than the operands being rendered inline - the alternative would be storing zero, which
     * <em>is</em> {@link FileStatus#NORMAL} and would report a failed command as a successful one.
     *
     * @param code the value held in {@code WS-RESP-CD} or {@code WS-REAS-CD}
     * @return exactly {@link #WS_RESP_CD_DIGITS} characters
     */
    private String respImage(int code) {
        return FileStatus.respReported(code)
                ? codec.movePic9(code, WS_RESP_CD_DIGITS)
                : FileStatus.respNotReportedImage(WS_RESP_CD_DIGITS);
    }

    /**
     * @param state the working storage supplied
     * @throws NullPointerException if {@code state} is {@code null}
     */
    private static void requireState(PaymentState state) {
        Objects.requireNonNull(state, "A PaymentState is required: every WORKING-STORAGE item of "
                + WS_PGMNAME + " lives in it, so that two concurrent requests cannot see each other's "
                + "screen");
    }

    /**
     * A run of spaces, for blanking a field at its declared width.
     *
     * @param length how many; never negative
     * @return exactly {@code length} spaces
     */
    private static String spaces(int length) {
        return " ".repeat(length);
    }

    /**
     * A run of {@code LOW-VALUES}, for the state an unfilled field or record area is in.
     *
     * @param length how many; never negative
     * @return exactly {@code length} {@code X'00'} characters
     */
    private static String lowValues(int length) {
        // One implementation of the LOW-VALUES image, in common.ScreenFieldImage, so the choice cannot
        // drift back apart across screens. Any width validation above is this method's own contract.
        return ScreenFieldImage.unpainted(length);
    }


    // =================================================================================================
    // One screen, as it was at the moment EXEC CICS SEND MAP issued.
    // =================================================================================================

    /**
     * A snapshot of everything {@code EXEC CICS SEND MAP('COBIL0A') MAPSET('COBIL00') FROM(COBIL0AO)
     * ERASE CURSOR} transmitted, taken at the moment it was issued.
     *
     * <p>The program sends the screen up to twice per execution and the two transmissions can differ, so
     * the final state of the working storage is not by itself a record of what the user saw. Two examples
     * make the point:
     *
     * <ul>
     *   <li>the {@code 'N'} arm of the confirmation switch clears the balance field and sends, and the
     *       stale balance is written into that field <em>afterwards</em> - so the snapshot carries a blank
     *       balance while the state ends up carrying an edited one. That difference <em>is</em> the
     *       defect, and it is only observable as a difference;</li>
     *   <li>a successful payment sends once from the write's success arm with the confirmation message and
     *       a green message line, and once again from line 242 - so the send count is part of the
     *       behaviour.</li>
     * </ul>
     *
     * <p>The five header fields {@code POPULATE-HEADER-INFO} writes are absent: they are the controller's
     * and they carry no decision. What is here is what the service owns.
     *
     * @param ordinal          which send this was, counting from one
     * @param errMsg           {@code ERRMSGO PIC X(78)} as transmitted - {@code WS-MESSAGE} after the
     *                         two-byte right truncation of line 293
     * @param actIdIn          {@code ACTIDINI PIC X(11)} as transmitted
     * @param curBal           {@code CURBALI PIC X(14)} as transmitted
     * @param confirm          {@code CONFIRMI PIC X(1)} as transmitted
     * @param cursorField      which field the {@code CURSOR} option placed the cursor in
     * @param messageHighlight the message line's colour override, or {@code null} where none applied and
     *                         the mapset's declared red stands
     */
    public record SentScreen(int ordinal,
                             String errMsg,
                             String actIdIn,
                             String curBal,
                             String confirm,
                             CursorField cursorField,
                             String messageHighlight) {
    }

    // =================================================================================================
    // PaymentState - the WORKING-STORAGE of app/cbl/COBIL00C.cbl:36-85, per invocation.
    // =================================================================================================

    /**
     * One execution's working storage: every item {@code COBIL00C} declares, plus the observable effects
     * that have no home in the ten-field payload.
     *
     * <p><strong>Per invocation, never shared.</strong> {@code WORKING-STORAGE} in a CICS program is
     * per-task storage. Reproducing it as fields on the service - a Spring singleton - would let two
     * concurrent payments overwrite each other's screen and each other's balance, so one of these is
     * created inside {@link BillPaymentService#processEnterKey(String, String, NavigationContext)} and is
     * reachable from nowhere else. There is no {@code static} mutable field here and none on the service.
     *
     * <p><strong>It is also the parity fingerprint.</strong> A case asserts the ten payload fields
     * <em>and</em> the things the screen cannot show: whether the error flag ended on, whether the
     * payment was confirmed, the eighty-byte message, the fourteen-character edited balance, which field
     * the cursor was requested in, the three record areas as they ended up, the transaction identifier
     * that was allocated, the composed timestamp, the two response codes, the text of every
     * {@code DISPLAY}, and every screen that was sent.
     *
     * <p><strong>The initial content of the record areas is the honest model of an unfilled one.</strong>
     * IBM Enterprise COBOL initialises a {@code WORKING-STORAGE} item with no {@code VALUE} clause to
     * binary zeros, and a numeric-display field of {@code X'00'} bytes read under the usual rules - digit
     * from the low half of each byte, sign from the zone of the last - is a positive zero. That is why
     * {@link #accountRecord()} starts as an initialised 300-byte area whose balance reads {@code 0.00},
     * and it is what the stale balance of lines 193-194 renders when no account was ever read.
     */
    public static final class PaymentState {

        /** The move rules and code page, so every item here is held at its declared width. */
        private final FixedWidthCodec codec;

        /**
         * {@code CARDDEMO-COMMAREA} - the 160 bytes of {@code app/cpy/COCOM01Y.cpy}.
         *
         * <p>{@code final}, because {@code PROCESS-ENTER-KEY} neither reads a field of it nor writes one:
         * the communication area is consumed by {@code MAIN-PARA} and returned unchanged by
         * {@code EXEC CICS RETURN COMMAREA(CARDDEMO-COMMAREA)} at lines 146-149. It is carried so the
         * caller has one object to hand back to the client.
         */
        private final NavigationContext commarea;

        /**
         * {@code WS-TRAN-AMT PIC +99999999.99} - line 55, declared and never referenced.
         *
         * <p>{@code final}, because no statement in the program can change it.
         */
        private final String tranAmtEdited = WS_TRAN_AMT_INITIAL;

        /**
         * {@code WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} - line 58, declared and never referenced.
         *
         * <p>{@code final}, for the same reason.
         */
        private final String tranDate = WS_TRAN_DATE;

        /** {@code WS-MESSAGE PIC X(80) VALUE SPACES} - line 39. */
        private String message;

        /** {@code WS-ERR-FLG PIC X(01) VALUE 'N'} - line 43. */
        private String errFlg = ERR_FLG_OFF;

        /**
         * {@code WS-USR-MODIFIED PIC X(01) VALUE 'N'} - line 48.
         *
         * <p><strong>Write-only state.</strong> Line 102 sets it to {@value #USR_MODIFIED_NO} and no
         * statement anywhere in the program reads it or changes it again. It is carried rather than
         * dropped, because the declaration is part of the program's data division.
         */
        private String usrModified = USR_MODIFIED_NO;

        /** {@code WS-CONF-PAY-FLG PIC X(01) VALUE 'N'} - line 51. */
        private String confPayFlg = CONF_PAY_NO;

        /** {@code WS-RESP-CD PIC S9(09) COMP VALUE ZEROS} - line 46. */
        private int respCd = FileStatus.NORMAL;

        /** {@code WS-REAS-CD PIC S9(09) COMP VALUE ZEROS} - line 47. */
        private int reasCd = FileStatus.NO_REASON_CODE;

        /** {@code WS-CURR-BAL PIC +9999999999.99} - line 56, written at line 193. */
        private String currBalEdited = WS_CURR_BAL_INITIAL;

        /** {@code WS-TRAN-ID-NUM PIC 9(16) VALUE ZEROS} - line 57. */
        private long tranIdNum;

        /**
         * {@code WS-ABS-TIME PIC S9(15) COMP-3 VALUE 0} - line 59, filled by {@code ASKTIME}.
         *
         * <p>A {@code long} and not a packed-decimal image. {@code COMP-3} appears in no copybook of this
         * migration, so no persisted field needs nibble unpacking, and this item never leaves memory -
         * it is passed to {@code FORMATTIME} and read by nothing else.
         */
        private long absTime;

        /** {@code WS-CUR-DATE-X10 PIC X(10) VALUE SPACES} - line 60, filled by {@code FORMATTIME}. */
        private String curDateX10;

        /** {@code WS-CUR-TIME-X08 PIC X(08) VALUE SPACES} - line 61, filled by {@code FORMATTIME}. */
        private String curTimeX08;

        /** {@code WS-TIMESTAMP} of {@code app/cpy/CSDAT01Y.cpy:42-55} - 26 characters, composed at :263-266. */
        private String timestamp;

        /**
         * {@code ACCOUNT-RECORD} of {@code app/cpy/CVACT01Y.cpy} - 300 bytes.
         *
         * <p>Starts as an initialised area, whose balance therefore reads {@code 0.00}, and is replaced
         * wholesale by a successful {@code READ ... INTO(ACCOUNT-RECORD)}. Mutable, because line 234
         * changes the balance in place and line 235 rewrites all 300 bytes.
         */
        private AccountRecord accountRecord;

        /**
         * {@code CARD-XREF-RECORD} of {@code app/cpy/CVACT03Y.cpy} - 50 bytes, empty until a successful
         * {@code READ ... INTO(CARD-XREF-RECORD)} fills it.
         *
         * <p>Empty rather than an initialised instance, because {@link CardXrefRecord} models a stored
         * record and not a partially populated work area: it is immutable, so the one field the program
         * writes into that area independently of the read - {@code XREF-ACCT-ID}, from the two-receiver
         * {@code MOVE} at lines 170-171 - is carried as {@link #xrefAcctIdRidfld()} instead. What the
         * unfilled area contributes to the transaction record is {@link #xrefCardNum()}, which reports
         * the binary-zero state directly.
         */
        private CardXrefRecord cardXrefRecord;

        /**
         * {@code TRAN-RECORD} of {@code app/cpy/CVTRA05Y.cpy} - 350 bytes.
         *
         * <p>Empty until either a successful {@code READPREV} fills it with the highest existing
         * transaction, or line 218's {@code INITIALIZE} replaces it with the fresh area the payment is
         * assembled into. Both are recorded here, in that order, because the second overwrites the first
         * exactly as the single COBOL record area does.
         */
        private TranRecord tranRecord;

        /**
         * {@code ACCT-ID PIC 9(11)} as a record identification field - the first receiver of the
         * two-receiver {@code MOVE} at lines 170-171.
         */
        private String acctIdRidfld;

        /**
         * {@code XREF-ACCT-ID PIC 9(11)} as a record identification field - the second receiver of the
         * same {@code MOVE}. Held separately from {@link #acctIdRidfld} because the source declares two
         * distinct fields, even though one statement gives them the same eleven characters.
         */
        private String xrefAcctIdRidfld;

        /**
         * {@code TRAN-ID PIC X(16)} as a record identification field: {@code HIGH-VALUES} from line 212,
         * then the highest existing identifier or {@code ZEROS} from the {@code READPREV} arms.
         */
        private String tranIdRidfld;

        /** {@code ACTIDINI PIC X(11)} - the account identifier field of the screen. */
        private String actIdIn;

        /** {@code CURBALI PIC X(14)} - the balance field, written at line 194. */
        private String curBal;

        /** {@code CONFIRMI PIC X(1)} - the confirmation field. */
        private String confirm;

        /** {@code ERRMSGO PIC X(78)} - the message field, written by every send at line 293. */
        private String errMsg;

        /**
         * The field the last {@code MOVE -1 TO <field>L} named, collapsing the program's seventeen cursor
         * statements into one indicator.
         *
         * <p>{@code xxxL} metadata, held here and not on the payload: a projection of {@code xxxI} and
         * {@code xxxO} items has no room for a length item, and smuggling one in would break the
         * one-to-one field correspondence the response DTO maintains.
         */
        private CursorField cursorField = CursorField.NONE;

        /**
         * {@code ERRMSGC PICTURE X} - the message line's colour, {@code null} until line 526 turns it
         * green and {@code null} on every path that never reaches that line.
         */
        private String messageHighlight;

        /**
         * Whether the {@code WHEN OTHER / CONTINUE} arm of the empty-identifier check at lines 165-166
         * was the one taken - that is, whether an account identifier was supplied.
         */
        private boolean acctIdCheckContinued;

        /** Whether the {@code STARTBR} reported a successful position at lines 452-453. */
        private boolean browseStarted;

        /** Whether the {@code REWRITE} reported success at lines 388-389. */
        private boolean accountRewritten;

        /** Every screen the execution sent, in order. */
        private final List<SentScreen> sentScreens = new ArrayList<>();

        /** The text of every {@code DISPLAY} the execution reached, in order. */
        private final List<String> displays = new ArrayList<>();

        /**
         * Creates the working storage in the state the {@code VALUE} clauses describe, with the three
         * record areas in the state COBOL leaves an item that has none.
         *
         * @param codec    the move rules and code page for this execution's images; must not be
         *                 {@code null}
         * @param commarea the communication area the caller received; must not be {@code null}
         * @throws NullPointerException if either argument is {@code null}
         */
        public PaymentState(FixedWidthCodec codec, NavigationContext commarea) {
            this.codec = Objects.requireNonNull(codec, "A FixedWidthCodec is required: every item held "
                    + "here is kept at its declared PICTURE width, and the code page of a fixed-width "
                    + "image is always stated explicitly");
            this.commarea = Objects.requireNonNull(commarea, "A communication area is required; pass "
                    + "NavigationContext.empty() for the state a first entry produces");
            this.message = spaces(WS_MESSAGE_LENGTH);
            this.actIdIn = spaces(ACT_ID_IN_LENGTH);
            this.curBal = spaces(CUR_BAL_LENGTH);
            this.confirm = spaces(CONFIRM_LENGTH);
            this.errMsg = spaces(ERR_MSG_LENGTH);
            this.curDateX10 = spaces(WS_CUR_DATE_X10_LENGTH);
            this.curTimeX08 = spaces(WS_CUR_TIME_X08_LENGTH);
            this.timestamp = spaces(DateHeader.WS_TIMESTAMP_LENGTH);
            this.acctIdRidfld = spaces(ACT_ID_IN_LENGTH);
            this.xrefAcctIdRidfld = spaces(ACT_ID_IN_LENGTH);
            this.tranIdRidfld = spaces(TranRecord.TRAN_ID_LENGTH);
            // The 300-byte area an unfilled WORKING-STORAGE record amounts to: every numeric span zero,
            // so the balance the stale-balance defect edits reads 0.00.
            this.accountRecord = new AccountRecord(codec.charset());
        }

        // -----------------------------------------------------------------------------------------
        // The flags. Read as predicates and written as the source's SET statements, so a call site
        // never spells the characters and never has to remember which value means what.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code IF ERR-FLG-ON} - lines 169, 197 and 208 all test the negation of this.
         *
         * @return {@code true} when {@code WS-ERR-FLG} holds {@value BillPaymentService#ERR_FLG_ON}
         */
        public boolean isErrFlagOn() {
            return ERR_FLG_ON.equals(errFlg);
        }

        /**
         * {@code MOVE 'Y' TO WS-ERR-FLG} - lines 139, 160, 181, 186, 200, 360, 367, 391, 398, 424, 431,
         * 455, 462, 491, 535 and 542. Sixteen sites, one method.
         */
        public void setErrFlagOn() {
            this.errFlg = ERR_FLG_ON;
        }

        /**
         * {@code WS-ERR-FLG} verbatim, so a caller can distinguish "off" from "holding something that is
         * neither condition name" - which two independent {@code 88}-level tests genuinely can.
         *
         * @return one character; {@value BillPaymentService#ERR_FLG_OFF} until an error is raised
         */
        public String errFlg() {
            return errFlg;
        }

        /**
         * {@code IF CONF-PAY-YES} - the test at line 210 that decides whether the payment happens.
         *
         * @return {@code true} when {@code WS-CONF-PAY-FLG} holds
         *         {@value BillPaymentService#CONF_PAY_YES}
         */
        public boolean isConfPayYes() {
            return CONF_PAY_YES.equals(confPayFlg);
        }

        /** {@code SET CONF-PAY-YES TO TRUE} - line 176. */
        public void setConfPayYes() {
            this.confPayFlg = CONF_PAY_YES;
        }

        /** {@code SET CONF-PAY-NO TO TRUE} - line 156, the paragraph's first statement. */
        public void setConfPayNo() {
            this.confPayFlg = CONF_PAY_NO;
        }

        /**
         * {@code WS-CONF-PAY-FLG} verbatim.
         *
         * @return one character; {@value BillPaymentService#CONF_PAY_NO} unless the user confirmed
         */
        public String confPayFlg() {
            return confPayFlg;
        }

        /**
         * {@code WS-USR-MODIFIED} verbatim - write-only state, never read by the program.
         *
         * @return one character, always {@value BillPaymentService#USR_MODIFIED_NO} on any path this
         *         service reaches, because line 102 is the only statement that writes it
         */
        public String usrModified() {
            return usrModified;
        }

        /**
         * {@code SET USR-MODIFIED-NO TO TRUE} - line 102, in {@code MAIN-PARA}.
         *
         * <p>Offered so the controller can reproduce that statement rather than reach into the field, and
         * so the item's declared behaviour is expressible even though nothing ever reads it. There is
         * deliberately no setter for {@value BillPaymentService#USR_MODIFIED_YES}: no statement in the
         * program sets it, and offering one would suggest a path that does not exist.
         */
        public void setUsrModifiedNo() {
            this.usrModified = USR_MODIFIED_NO;
        }

        /**
         * Whether an account identifier was supplied, which is the {@code WHEN OTHER / CONTINUE} arm of
         * lines 165-166.
         *
         * @return {@code true} when the empty-identifier check took its no-op arm
         */
        public boolean acctIdCheckContinued() {
            return acctIdCheckContinued;
        }

        /** Records that lines 165-166 took the {@code CONTINUE} arm. */
        public void setAcctIdCheckContinued() {
            this.acctIdCheckContinued = true;
        }

        /**
         * Whether the browse position reported success at lines 452-453.
         *
         * @return {@code true} when {@code STARTBR-TRANSACT-FILE} took its {@code NORMAL} arm
         */
        public boolean browseStarted() {
            return browseStarted;
        }

        /** Records the {@code NORMAL} arm of {@code STARTBR-TRANSACT-FILE}, lines 452-453. */
        public void setBrowseStarted() {
            this.browseStarted = true;
        }

        /**
         * Whether the account rewrite reported success at lines 388-389.
         *
         * @return {@code true} when {@code UPDATE-ACCTDAT-FILE} took its {@code NORMAL} arm
         */
        public boolean accountRewritten() {
            return accountRewritten;
        }

        /** Records the {@code NORMAL} arm of {@code UPDATE-ACCTDAT-FILE}, lines 388-389. */
        public void setAccountRewritten() {
            this.accountRewritten = true;
        }

        // -----------------------------------------------------------------------------------------
        // WS-MESSAGE and the screen fields. Every setter applies its receiver's declared width, which
        // is the whole reason they are setters and not public fields.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code WS-MESSAGE PIC X(80)}.
         *
         * @return exactly {@value BillPaymentService#WS_MESSAGE_LENGTH} characters
         */
        public String message() {
            return message;
        }

        /**
         * {@code MOVE <literal> TO WS-MESSAGE}: stores the value at the item's declared width, so a
         * shorter literal is space-padded on the right and a longer one truncated on the right.
         *
         * <p>This is the move that widens {@code CCDA-MSG-INVALID-KEY PIC X(50)} to eighty at line 140,
         * and the one that stores each of the eleven shorter literals.
         *
         * @param value the sending value; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setMessage(String value) {
            Objects.requireNonNull(value, "A message is required; move SPACES explicitly with "
                    + "setMessageSpaces() to blank the field");
            this.message = codec.movePicX(value, WS_MESSAGE_LENGTH);
        }

        /** {@code MOVE SPACES TO WS-MESSAGE} - lines 104, 525 and 566. */
        public void setMessageSpaces() {
            this.message = spaces(WS_MESSAGE_LENGTH);
        }

        /**
         * {@code ACTIDINI PIC X(11)}.
         *
         * @return exactly {@value BillPaymentService#ACT_ID_IN_LENGTH} characters
         */
        public String actIdIn() {
            return actIdIn;
        }

        /**
         * Stores {@code ACTIDINI} at its declared width.
         *
         * @param value the sending value; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setActIdIn(String value) {
            Objects.requireNonNull(value, "ACTIDINI is PIC X(" + ACT_ID_IN_LENGTH + ") and has no null "
                    + "representation; move SPACES or LOW-VALUES explicitly");
            this.actIdIn = codec.movePicX(value, ACT_ID_IN_LENGTH);
        }

        /**
         * {@code CURBALI PIC X(14)} - the balance as the screen carries it, already edited.
         *
         * @return exactly {@value BillPaymentService#CUR_BAL_LENGTH} characters
         */
        public String curBal() {
            return curBal;
        }

        /**
         * Stores {@code CURBALI} at its declared width.
         *
         * @param value the sending value; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setCurBal(String value) {
            Objects.requireNonNull(value, "CURBALI is PIC X(" + CUR_BAL_LENGTH + ") and has no null "
                    + "representation; move SPACES explicitly");
            this.curBal = codec.movePicX(value, CUR_BAL_LENGTH);
        }

        /**
         * {@code CONFIRMI PIC X(1)}.
         *
         * @return exactly {@value BillPaymentService#CONFIRM_LENGTH} character
         */
        public String confirm() {
            return confirm;
        }

        /**
         * Stores {@code CONFIRMI} at its declared width, with no case folding.
         *
         * @param value the sending value; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setConfirm(String value) {
            Objects.requireNonNull(value, "CONFIRMI is PIC X(" + CONFIRM_LENGTH + ") and has no null "
                    + "representation; move SPACES or LOW-VALUES explicitly");
            this.confirm = codec.movePicX(value, CONFIRM_LENGTH);
        }

        /**
         * {@code ERRMSGO PIC X(78)} - the message as the last send transmitted it.
         *
         * @return exactly {@value BillPaymentService#ERR_MSG_LENGTH} characters
         */
        public String errMsg() {
            return errMsg;
        }

        /**
         * {@code MOVE WS-MESSAGE TO ERRMSGO OF COBIL0AO} - line 293. Stored as given, because the
         * two-byte right truncation is applied by the caller at the line that performs it, deliberately
         * and visibly, rather than silently here.
         *
         * @param value the value the send transmits; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setErrMsg(String value) {
            this.errMsg = Objects.requireNonNull(value, "ERRMSGO is PIC X(" + ERR_MSG_LENGTH + ") and "
                    + "has no null representation");
        }

        /**
         * {@code WS-CURR-BAL PIC +9999999999.99} - the edited balance before it reaches the screen field.
         *
         * @return exactly {@value BillPaymentService#WS_CURR_BAL_LENGTH} characters
         */
        public String currBalEdited() {
            return currBalEdited;
        }

        /**
         * {@code MOVE ACCT-CURR-BAL TO WS-CURR-BAL} - line 193.
         *
         * @param value the edited image; must not be {@code null}
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if it is not exactly {@link BillPaymentService#WS_CURR_BAL_LENGTH}
         *                                 characters, since a numeric-edited image is never padded or
         *                                 truncated - every position of the picture always emits
         */
        public void setCurrBalEdited(String value) {
            Objects.requireNonNull(value, "An edited balance is required");
            if (value.length() != WS_CURR_BAL_LENGTH) {
                throw new IllegalArgumentException("WS-CURR-BAL is PIC +9999999999.99, which always "
                        + "emits exactly " + WS_CURR_BAL_LENGTH + " characters - a sign, "
                        + CURR_BAL_INTEGER_DIGITS + " integer digits, the point and "
                        + CURR_BAL_FRACTION_DIGITS + " fraction digits; " + value.length()
                        + " was supplied");
            }
            this.currBalEdited = value;
        }

        /**
         * {@code WS-TRAN-AMT PIC +99999999.99} - declared at line 55 and never referenced.
         *
         * @return exactly {@value BillPaymentService#WS_TRAN_AMT_LENGTH} spaces, always
         */
        public String tranAmtEdited() {
            return tranAmtEdited;
        }

        /**
         * {@code WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} - declared at line 58 and never referenced.
         *
         * @return {@value BillPaymentService#WS_TRAN_DATE}, always
         */
        public String tranDate() {
            return tranDate;
        }

        // -----------------------------------------------------------------------------------------
        // Cursor, colour, and the record of what was sent and displayed.
        // -----------------------------------------------------------------------------------------

        /**
         * Which field the last {@code MOVE -1 TO <field>L} named.
         *
         * @return one of the three constants; {@link CursorField#NONE} until a statement names a field
         */
        public CursorField cursorField() {
            return cursorField;
        }

        /**
         * {@code MOVE -1 TO <field>L} - the CICS idiom for placing the cursor.
         *
         * @param value the field to place it in; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setCursorField(CursorField value) {
            this.cursorField = Objects.requireNonNull(value, "A cursor target is required; use "
                    + "CursorField.NONE for the paths that name no field and leave the map's own IC "
                    + "position standing");
        }

        /**
         * {@code ERRMSGC PICTURE X} - the message line's colour override.
         *
         * @return one character, or {@code null} where the program applied none and the mapset's declared
         *         red stands
         */
        public String messageHighlight() {
            return messageHighlight;
        }

        /**
         * {@code MOVE DFHGREEN TO ERRMSGC OF COBIL0AO} - line 526, the program's only attribute
         * override.
         *
         * @param value the attribute character, normally
         *              {@link BillPaymentService#MESSAGE_HIGHLIGHT_GREEN}; {@code null} requests no
         *              override
         */
        public void setMessageHighlight(String value) {
            this.messageHighlight = value;
        }

        /**
         * Every screen the execution sent, in the order it sent them.
         *
         * @return an unmodifiable view; empty when no send was reached
         */
        public List<SentScreen> sentScreens() {
            return Collections.unmodifiableList(sentScreens);
        }

        /**
         * How many times {@code EXEC CICS SEND MAP} was issued.
         *
         * @return zero, one or two on the program's own paths
         */
        public int screensSent() {
            return sentScreens.size();
        }

        /**
         * {@code EXEC CICS SEND MAP('COBIL0A') MAPSET('COBIL00') FROM(COBIL0AO) ERASE CURSOR} - lines
         * 295-301: snapshots the transmitted screen.
         */
        public void recordSend() {
            sentScreens.add(new SentScreen(sentScreens.size() + 1, errMsg, actIdIn, curBal, confirm,
                    cursorField, messageHighlight));
        }

        /**
         * The text of every {@code DISPLAY} the execution reached, in order.
         *
         * @return an unmodifiable view; empty unless a {@code WHEN OTHER} arm was taken
         */
        public List<String> displays() {
            return Collections.unmodifiableList(displays);
        }

        /**
         * Records one {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD}.
         *
         * @param text the composed line; must not be {@code null}
         * @throws NullPointerException if {@code text} is {@code null}
         */
        public void recordDisplay(String text) {
            displays.add(Objects.requireNonNull(text, "A DISPLAY emits text"));
        }

        // -----------------------------------------------------------------------------------------
        // The two response codes.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code WS-RESP-CD} - the CICS response of the last file operation.
         *
         * @return the reported value, or {@link FileStatus#RESP_NOT_REPORTED} where the operation
         *         surfaced none
         */
        public int respCd() {
            return respCd;
        }

        /**
         * {@code WS-REAS-CD} - the CICS reason code of the last file operation.
         *
         * @return the reported value, {@link FileStatus#NO_REASON_CODE} where there was none
         */
        public int reasCd() {
            return reasCd;
        }

        /**
         * {@code RESP(WS-RESP-CD) RESP2(WS-REAS-CD)} - records what one file operation reported.
         *
         * @param resp the response code
         * @param reas the reason code
         */
        public void setResponseCodes(int resp, int reas) {
            this.respCd = resp;
            this.reasCd = reas;
        }

        // -----------------------------------------------------------------------------------------
        // The record identification fields, the counter, the clock items and the three record areas.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code ACCT-ID PIC 9(11)} as the account read's {@code RIDFLD}.
         *
         * @return exactly {@value BillPaymentService#ACT_ID_IN_LENGTH} characters
         */
        public String acctIdRidfld() {
            return acctIdRidfld;
        }

        /**
         * The first receiver of the {@code MOVE} at lines 170-171.
         *
         * @param value the eleven characters; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setAcctIdRidfld(String value) {
            this.acctIdRidfld = requireKeyWidth(value, "ACCT-ID");
        }

        /**
         * {@code XREF-ACCT-ID PIC 9(11)} as the alternate-index read's {@code RIDFLD}.
         *
         * @return exactly {@value BillPaymentService#ACT_ID_IN_LENGTH} characters
         */
        public String xrefAcctIdRidfld() {
            return xrefAcctIdRidfld;
        }

        /**
         * The second receiver of the {@code MOVE} at lines 170-171.
         *
         * @param value the eleven characters; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setXrefAcctIdRidfld(String value) {
            this.xrefAcctIdRidfld = requireKeyWidth(value, "XREF-ACCT-ID");
        }

        /**
         * {@code TRAN-ID PIC X(16)} as the browse and write {@code RIDFLD}.
         *
         * @return exactly sixteen characters
         */
        public String tranIdRidfld() {
            return tranIdRidfld;
        }

        /**
         * Sets the transaction record identification field: {@code HIGH-VALUES} at line 212, the highest
         * existing identifier on the {@code READPREV} success arm, {@code ZEROS} on its {@code ENDFILE}
         * arm.
         *
         * @param value the sixteen characters; must not be {@code null}
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if it is not exactly sixteen characters
         */
        public void setTranIdRidfld(String value) {
            Objects.requireNonNull(value, "TRAN-ID is PIC X(" + TranRecord.TRAN_ID_LENGTH + ") and has "
                    + "no null representation");
            if (value.length() != TranRecord.TRAN_ID_LENGTH) {
                throw new IllegalArgumentException("TRAN-ID is PIC X(" + TranRecord.TRAN_ID_LENGTH
                        + ") and a record identification field is always its declared width; "
                        + value.length() + " characters were supplied");
            }
            this.tranIdRidfld = value;
        }

        /**
         * {@code WS-TRAN-ID-NUM PIC 9(16)} - the numeric view of the identifier, before and after the
         * increment at line 217.
         *
         * @return the value the sixteen digits denote
         */
        public long tranIdNum() {
            return tranIdNum;
        }

        /**
         * Sets {@code WS-TRAN-ID-NUM}.
         *
         * @param value the value; must not be negative, because {@code PIC 9} is unsigned and has no
         *              sign position at all
         * @throws IllegalArgumentException if {@code value} is negative
         */
        public void setTranIdNum(long value) {
            if (value < 0) {
                throw new IllegalArgumentException("WS-TRAN-ID-NUM is PIC 9(" + WS_TRAN_ID_NUM_DIGITS
                        + "), an unsigned picture with no sign position, so it cannot hold " + value);
            }
            this.tranIdNum = value;
        }

        /**
         * {@code WS-ABS-TIME PIC S9(15) COMP-3} - the CICS absolute time, milliseconds since
         * 1900-01-01.
         *
         * @return the value {@code ASKTIME} reported, zero until it has been called
         */
        public long absTime() {
            return absTime;
        }

        /**
         * Sets {@code WS-ABS-TIME} from {@code EXEC CICS ASKTIME}.
         *
         * @param value the absolute time in milliseconds
         */
        public void setAbsTime(long value) {
            this.absTime = value;
        }

        /**
         * {@code WS-CUR-DATE-X10 PIC X(10)} - {@code FORMATTIME}'s date with {@code DATESEP('-')}.
         *
         * @return exactly {@value BillPaymentService#WS_CUR_DATE_X10_LENGTH} characters
         */
        public String curDateX10() {
            return curDateX10;
        }

        /**
         * Sets {@code WS-CUR-DATE-X10} at its declared width.
         *
         * @param value the sending value; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setCurDateX10(String value) {
            Objects.requireNonNull(value, "WS-CUR-DATE-X10 is PIC X(" + WS_CUR_DATE_X10_LENGTH + ")");
            this.curDateX10 = codec.movePicX(value, WS_CUR_DATE_X10_LENGTH);
        }

        /**
         * {@code WS-CUR-TIME-X08 PIC X(08)} - {@code FORMATTIME}'s time with {@code TIMESEP(':')}.
         *
         * @return exactly {@value BillPaymentService#WS_CUR_TIME_X08_LENGTH} characters
         */
        public String curTimeX08() {
            return curTimeX08;
        }

        /**
         * Sets {@code WS-CUR-TIME-X08} at its declared width.
         *
         * @param value the sending value; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setCurTimeX08(String value) {
            Objects.requireNonNull(value, "WS-CUR-TIME-X08 is PIC X(" + WS_CUR_TIME_X08_LENGTH + ")");
            this.curTimeX08 = codec.movePicX(value, WS_CUR_TIME_X08_LENGTH);
        }

        /**
         * {@code WS-TIMESTAMP} - the twenty-six character image that both timestamp fields of the
         * transaction record receive.
         *
         * @return exactly {@value DateHeader#WS_TIMESTAMP_LENGTH} characters, spaces until composed
         */
        public String timestamp() {
            return timestamp;
        }

        /**
         * Sets {@code WS-TIMESTAMP}.
         *
         * @param value the composed image; must not be {@code null}
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if it is not exactly {@link DateHeader#WS_TIMESTAMP_LENGTH}
         *                                 characters, since it is a group of fixed-width items and both
         *                                 receivers are {@code PIC X(26)}
         */
        public void setTimestamp(String value) {
            Objects.requireNonNull(value, "A timestamp image is required");
            if (value.length() != DateHeader.WS_TIMESTAMP_LENGTH) {
                throw new IllegalArgumentException("WS-TIMESTAMP occupies exactly "
                        + DateHeader.WS_TIMESTAMP_LENGTH + " characters and TRAN-ORIG-TS and "
                        + "TRAN-PROC-TS are both PIC X(" + TranRecord.TRAN_ORIG_TS_LENGTH + "); "
                        + value.length() + " characters were supplied");
            }
            this.timestamp = value;
        }

        /**
         * {@code ACCOUNT-RECORD} - the 300-byte working area.
         *
         * <p>Never {@code null}. Before a successful read it is an initialised area whose balance reads
         * {@code 0.00}, which is what an IBM Enterprise COBOL {@code WORKING-STORAGE} record with no
         * {@code VALUE} clause amounts to and what the stale-balance defect of lines 193-194 edits.
         *
         * @return the record area; mutable, because line 234 changes the balance in place
         */
        public AccountRecord accountRecord() {
            return accountRecord;
        }

        /**
         * {@code READ ... INTO(ACCOUNT-RECORD)} - replaces all 300 bytes.
         *
         * @param value the record read; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setAccountRecord(AccountRecord value) {
            this.accountRecord = Objects.requireNonNull(value, "INTO(ACCOUNT-RECORD) replaces the whole "
                    + "record area, so a read that succeeded has a record to put there");
        }

        /**
         * {@code CARD-XREF-RECORD} - the 50-byte working area, present only after a successful
         * alternate-index read.
         *
         * @return the record read, or an empty {@link Optional} where the read missed, failed or was
         *         never issued
         */
        public Optional<CardXrefRecord> cardXrefRecord() {
            return Optional.ofNullable(cardXrefRecord);
        }

        /**
         * {@code READ ... INTO(CARD-XREF-RECORD)} - replaces all 50 bytes.
         *
         * @param value the record read; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setCardXrefRecord(CardXrefRecord value) {
            this.cardXrefRecord = Objects.requireNonNull(value, "INTO(CARD-XREF-RECORD) replaces the "
                    + "whole record area, so a read that succeeded has a record to put there");
        }

        /**
         * {@code XREF-CARD-NUM PIC X(16)} as line 225 finds it - the sender of
         * {@code MOVE XREF-CARD-NUM TO TRAN-CARD-NUM}.
         *
         * <p><strong>The unfilled case is not an error and is not guarded.</strong> The payment sequence
         * has no {@code IF NOT ERR-FLG-ON} between the cross-reference read and the record assembly, so
         * a read that missed still reaches line 225 - and what it moves is the initial content of a
         * {@code WORKING-STORAGE} record area, which is {@code LOW-VALUES}. Those sixteen
         * {@code X'00'} bytes are what this method reports, and they are what gets written. Reporting
         * spaces instead, or refusing, would each change the bytes of a record the COBOL does write.
         *
         * @return exactly sixteen characters: the card number when the read succeeded, sixteen
         *         {@code LOW-VALUES} characters when it did not
         */
        public String xrefCardNum() {
            return cardXrefRecord == null
                    ? lowValues(CardXrefRecord.XREF_CARD_NUM_LENGTH)
                    : codec.movePicX(cardXrefRecord.xrefCardNum(),
                            CardXrefRecord.XREF_CARD_NUM_LENGTH);
        }

        /**
         * {@code TRAN-RECORD} - the 350-byte working area.
         *
         * <p>Empty until either a successful {@code READPREV} fills it with the highest existing
         * transaction or line 218's {@code INITIALIZE} replaces it with the area the payment is assembled
         * into. Both write the same single COBOL record area, and the second overwrites the first.
         *
         * @return the record area, or an empty {@link Optional} before either happens
         */
        public Optional<TranRecord> tranRecord() {
            return Optional.ofNullable(tranRecord);
        }

        /**
         * Replaces the transaction record area - {@code READ ... INTO(TRAN-RECORD)} at line 476, and
         * {@code INITIALIZE TRAN-RECORD} at line 218.
         *
         * @param value the record area; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setTranRecord(TranRecord value) {
            this.tranRecord = Objects.requireNonNull(value, "A record area is required; both the read "
                    + "and the INITIALIZE supply one");
        }

        /**
         * {@code CARDDEMO-COMMAREA} - carried in and handed straight back out.
         *
         * @return the communication area the caller supplied, unchanged; never {@code null}
         */
        public NavigationContext commarea() {
            return commarea;
        }

        /**
         * Requires a record identification field of the account key's declared width.
         *
         * @param value the value supplied
         * @param field the copybook name, for the diagnostic
         * @return {@code value}, unchanged
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if it is not exactly
         *                                  {@link BillPaymentService#ACT_ID_IN_LENGTH} characters
         */
        private static String requireKeyWidth(String value, String field) {
            Objects.requireNonNull(value, () -> field + " is PIC 9(" + ACT_ID_IN_LENGTH + ") and has no "
                    + "null representation");
            if (value.length() != ACT_ID_IN_LENGTH) {
                throw new IllegalArgumentException(field + " is PIC 9(" + ACT_ID_IN_LENGTH + ") and a "
                        + "record identification field is always its declared width, untrimmed and "
                        + "unparsed; " + value.length() + " characters were supplied");
            }
            return value;
        }
    }

}
