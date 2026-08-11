package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.NumericIntrinsics;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewRequest;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse.ScreenField;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import com.vsergeychik.carddemo.util.DateUtilityJob.DateValidationResult;

import jakarta.validation.Valid;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The stateless add-a-transaction screen: a like-for-like migration of {@code app/cbl/COTRN02C.cbl}
 * (783 lines), CSD transaction {@value #TRANSACTION_ID}, exposed as
 * {@code POST }{@value #TRANSACTIONS_PATH}.
 *
 * <p>Nothing here is added, removed, reordered, simplified or corrected relative to that program.
 * Where the COBOL does something odd, this class does the same odd thing, and says so.
 *
 * <h2>⚠ Read this first: the class name contradicts the program (risk R-B)</h2>
 *
 * <strong>This class is named {@code TransactionViewController} and the program it migrates
 * {@code ADD}s a transaction.</strong> The divergence is real, it is known, and it is recorded here
 * rather than corrected. Three independent sources agree that {@code CT02} is the <em>add</em>
 * screen:
 *
 * <ul>
 *   <li>{@code app/cbl/COTRN02C.cbl} line 5 states, verbatim:
 *       <pre>* Function    : Add a new Transaction to TRANSACT file</pre></li>
 *   <li>{@code README.md} lines 213-231 tabulate the online inventory and its line 224 reads
 *       {@code | | CT02 | COTRN02 | COTRN02C | Transaction Add |}, with {@code CT01} / {@code COTRN01C}
 *       on the line above as {@code Transaction View}. So the prompt's two names are
 *       <strong>swapped</strong> with respect to the sources.</li>
 *   <li>{@code app/bms/COTRN02.bms} carries {@code ACTIDIN}, {@code CARDNIN} and {@code CONFIRM} and
 *       has no transaction-id input at all; fourteen of its twenty-one fields are {@code UNPROT},
 *       which is a data-entry form rather than a display.</li>
 * </ul>
 *
 * The refactoring plan calls this "the single highest-risk naming ambiguity" and carries it as risk
 * <strong>R-B</strong> in its open risk register, flagged for user confirmation. The binding
 * resolution is its rule <strong>R1</strong> together with practice <strong>B4</strong>:
 * <em>the name comes from the prompt, the behaviour comes from the paired source, and the conflict is
 * surfaced rather than silently corrected.</em> Accordingly:
 *
 * <ul>
 *   <li>the mandated name {@code TransactionViewController} is honoured <strong>verbatim</strong> and
 *       must not be renamed;</li>
 *   <li><strong>this controller validates and inserts a transaction record.</strong> It edits the
 *       operator's input, resolves the account/card cross-reference, generates the next
 *       {@code TRAN-ID} by browsing the master backwards, and writes a new 350-byte
 *       {@code TRAN-RECORD};</li>
 *   <li>the genuine <em>view</em> behaviour belongs to {@code TransactionAddController}
 *       ({@code COTRN01C}), this class's mirror-image sibling, whose name is inverted in the same
 *       way and for the same reason;</li>
 *   <li>neither the name nor the behaviour may be inverted to "fix" the mismatch. Renaming the class
 *       would break the mandated structural inventory; reimplementing it as a read-only view would
 *       break every parity case.</li>
 * </ul>
 *
 * <p>The REST resource follows the <em>behaviour</em>, not the name: an insert is
 * {@code POST }{@value #TRANSACTIONS_PATH}. That collides with neither {@code CT00}'s
 * {@code GET /api/transactions} list nor the natural {@code GET /api/transactions/{tranId}} of the
 * sibling view screen, so the swap costs nothing at the HTTP layer and is not hidden by it either.
 *
 * <h2>There are no project rules; twelve engineering practices bind instead</h2>
 *
 * {@code review_rules} returns exactly one line, {@code "No user rules provided."} - that single line
 * is the entire rules document, so <strong>no user-specified rule governs this file</strong>. Their
 * absence is not licence to lower the bar: the refactoring plan's substitutes <strong>B1-B12</strong>
 * are held as binding constraints here, and the ones that bear on this class are honoured as follows.
 *
 * <ul>
 *   <li><strong>B3 - reference inputs are immutable.</strong> Not one byte under {@code app/cbl},
 *       {@code app/cpy}, {@code app/cpy-bms}, {@code app/bms}, {@code app/csd} or {@code app/data} is
 *       written by this work; they are the parity oracle.</li>
 *   <li><strong>B4 - no silent scope creep.</strong> The R-B swap above, and every dead declaration
 *       listed below, are documented rather than tidied.</li>
 *   <li><strong>B5 - dead code is preserved.</strong> {@code COTRN02C} declares five items it never
 *       uses, and all five survive here: {@link #WS_ACCTDAT_FILE}, {@link #WS_TRAN_AMT_PICTURE},
 *       {@link #WS_TRAN_DATE_VALUE}, the {@code WS-USR-MODIFIED} flag
 *       ({@link ProgramState#usrModifiedNo()}) and the unreferenced {@code COPY CVACT01Y}
 *       ({@link #COPIED_ACCOUNT_RECORD_LENGTH}). So does the unreachable {@code IF ERR-FLG-ON}
 *       blanking block at {@code :237-249} and the unreachable {@code EXEC CICS RETURN} at
 *       {@code :156}.</li>
 *   <li><strong>B6 - the security posture is neither weakened nor unrequestedly strengthened.</strong>
 *       There is no authentication, no authorization, no security annotation and no masking anywhere
 *       in this class (gate G41). The account id, the card number and the merchant id are carried and
 *       echoed at their full declared width, because that is what the legacy screen observably
 *       does.</li>
 *   <li><strong>B8 - explicit over implicit.</strong> No wildcard import (gate G52). No
 *       {@code AWS.M2.CARDDEMO.*} literal; every dataset is reached through its repository, which
 *       resolves its name from configuration (gate G46). Every scaled value is a {@link BigDecimal}
 *       routed through {@link CobolDecimal}, at the receiver's declared scale and with
 *       {@link java.math.RoundingMode#DOWN} - there is no {@code double}, no {@code float} (gate G22)
 *       and no {@code HALF_UP}, {@code HALF_EVEN}, {@code CEILING} or {@code FLOOR} (gate G24). Every
 *       {@code MOVE} that crosses a width goes through {@link FixedWidthCodec}, so the direction of
 *       the truncation is declared at the call site. The {@code PIC +99999999.99} edit mask is
 *       rendered by {@link #wsTranAmtEdited(BigDecimal)}, which composes digits itself and therefore
 *       cannot vary with a default locale.</li>
 *   <li><strong>B9 - no static mutable state</strong> (gate G53). Every {@code WORKING-STORAGE} item
 *       of {@code COTRN02C} lives in a per-request {@link ProgramState}; the only static members here
 *       are immutable constants and pure functions, and every collaborator is constructor-injected.
 *       Two concurrent requests cannot see each other's screen.</li>
 *   <li><strong>B10 / G51 - the logic is reachable without HTTP.</strong> This package has no
 *       {@code *Service} class, so every paragraph is a {@code public} method taking a
 *       {@link ProgramState}; {@link #mainPara(TransactionViewRequest)} returns the terminal state
 *       rather than only the response, and {@link #addTransaction(TransactionViewRequest)} is a thin
 *       adapter over it. A parity case drives plain Java with no {@code MockMvc} and no
 *       {@code JobLauncher} in the path.</li>
 *   <li><strong>B12 - environmental limits are documented.</strong> The parity expectations for this
 *       program are <em>statically derived</em> from the COBOL, the copybooks and the ASCII fixtures,
 *       because the legacy programs cannot be executed in this environment (risk <strong>R-A</strong>,
 *       eight independently verified blockers). Provenance is the only thing that changed; the
 *       twenty cases, the field-by-field diff and the zero-diff gate all stand.</li>
 * </ul>
 *
 * <p><strong>Gate G37 is absolute and is met structurally.</strong> There is no {@code HttpSession},
 * no {@code @SessionAttributes}, no {@code getSession}, no {@code ThreadLocal}, no server-side cache
 * and no static "current request" holder anywhere in this file. CICS is pseudo-conversational, so the
 * whole conversation - the {@value ProgramState#PASSED_COMMAREA_LENGTH}-byte communication area, the
 * {@code EIBAID} and the twenty-one screen values - arrives in the request and leaves in the response
 * (rule R6). {@code EXEC CICS XCTL} at {@code :509} becomes the response's next program, mapset and
 * map, resolved by the client (gate G40).
 *
 * <h2>Four facts that govern every line below</h2>
 *
 * <ol>
 *   <li><strong>Every {@code PERFORM SEND-TRNADD-SCREEN} is terminal.</strong> The paragraph ends with
 *       {@code EXEC CICS RETURN} at {@code :530}, which ends the CICS task, so control never comes
 *       back to the statement after the {@code PERFORM}. Three consequences are load-bearing.
 *       <em>First</em>, each Java block that sends ends with a {@code return}, and each caller guards
 *       with {@link ProgramState#taskEnded()} - that is the rule R7 restructuring of a non-local exit,
 *       and it is why a rejected account id is not then also parsed. <em>Second</em>, the
 *       {@code COMPUTE} at {@code :204} sits syntactically <em>after</em> the {@code IF ... IS NOT
 *       NUMERIC} block rather than inside it, and is nonetheless unreachable when that block fires;
 *       both facts are reproduced exactly, which is what makes the guard, and not an added
 *       {@code else}, the faithful translation. <em>Third</em>, the {@code EXEC CICS RETURN} the
 *       source writes at {@code :156} is byte for byte the one {@code SEND-TRNADD-SCREEN} executes and
 *       is unreachable for the same reason; it is preserved, not deleted.</li>
 *   <li><strong>{@code RETURN-TO-PREV-SCREEN} is terminal too.</strong> It ends with
 *       {@code EXEC CICS XCTL} at {@code :509}, which transfers rather than returns, so
 *       {@link ProgramState} distinguishes {@link ProgramState#returned()} from
 *       {@link ProgramState#transferred()} - a parity case has to be able to tell a repaint from a
 *       hand-off.</li>
 *   <li><strong>{@code COTRN2AO REDEFINES COTRN2AI}, so the screen is one buffer.</strong> The
 *       twenty-one {@code xxxI} items and the twenty-one {@code xxxO} items sit at identical offsets,
 *       which is why this program can read {@code ACTIDINI} and write {@code ACTIDINO} without copying
 *       anything, and why the four normalise-in-place writes at {@code :206-207}, {@code :209},
 *       {@code :386} and {@code :485} are visible on the very next screen it sends. The buffer is held
 *       once, as {@link ProgramState#response()}, and every payload accessor on the state reads and
 *       writes it. The {@code xxxL} halfwords are <em>metadata</em>, never payload (gate G9), so
 *       {@code MOVE -1 TO xxxL} lands on {@link ProgramState#symbolicMap()} instead.</li>
 *   <li><strong>This program touches three datasets, and declares a fourth it never opens.</strong>
 *       {@code CXACAIX} and {@code CCXREF} are two access paths over <em>one</em> cross-reference
 *       dataset, never two tables (gate G45): the account-id read uses the alternate-index path with
 *       its eleven-byte key and the card-number read uses the base cluster with its sixteen-byte key.
 *       {@code TRANSACT} is browsed backwards for the highest key and then written. {@code ACCTDAT} is
 *       declared at {@code :40} and, together with {@code COPY CVACT01Y} at {@code :89}, is never
 *       referenced - see {@link #WS_ACCTDAT_FILE}.</li>
 * </ol>
 *
 * <h2>What the screen does, end to end</h2>
 *
 * <pre>
 * POST /api/transactions
 * {
 *   "actidin": "00000000011", "cardnin": "                ",
 *   "ttypcd": "01", "tcatcd": "0001", "trnsrc": "POS TERM  ",
 *   "tdesc": "Coffee", "trnamt": "+00000012.34",
 *   "torigdt": "2022-07-18", "tprocdt": "2022-07-18",
 *   "mid": "000123456", "mname": "Kwik-E-Mart", "mcity": "Springfield", "mzip": "0000012345",
 *   "confirm": "Y",
 *   "aid": "ENTER",
 *   "navigationContext": { "pgmContext": 1, ... }
 * }
 * </pre>
 *
 * The account id resolves the card number through {@code CXACAIX} and is written back into the screen
 * normalised to eleven digits; the eleven detail fields are edited in the source's order; the amount
 * is re-rendered through the {@code +99999999.99} mask; both dates go through {@code CSUTLDTC}; the
 * master is browsed backwards for the highest {@code TRAN-ID}, one is added to it, and the new record
 * is written. The response carries {@code "Transaction added successfully.  Your Tran ID is
 * 0000000000000051."} in {@code errmsgo} with {@code ERRMSGC} set to {@link BmsAttributes#DFHGREEN},
 * and every input field blanked ready for the next entry.
 *
 * @see TransactionViewRequest the inbound projection of {@code 01 COTRN2AI}
 * @see TransactionViewResponse the outbound projection of {@code 01 COTRN2AO}
 * @see DateUtilityJob the {@code CSUTLDTC} subprogram, injected rather than copied
 */
@RestController
public class TransactionViewController {

    /**
     * The commons-logging channel, used only for the two {@code DISPLAY} statements' text.
     *
     * <p>Every call is single-argument by construction. Handing a logger a throwable would emit the
     * driver's own message and its cause chain verbatim, and a driver composes that message around the
     * record it refused - so a repository refusal is reported through its sanitized description and
     * never as an exception object.
     */
    private static final Log LOG = LogFactory.getLog(TransactionViewController.class);

    // =================================================================================================
    // Identity - app/cbl/COTRN02C.cbl:36-42 (01 WS-VARIABLES) and app/csd/CARDDEMO.CSD:271, :439.
    // =================================================================================================

    /** {@code 05 WS-PGMNAME PIC X(08) VALUE 'COTRN02C'} - L36, and {@code PROGRAM(COTRN02C)} in the CSD. */
    public static final String PROGRAM_NAME = "COTRN02C";

    /** {@code 05 WS-TRANID PIC X(04) VALUE 'CT02'} - L37, and {@code TRANSACTION(CT02)} in the CSD. */
    public static final String TRANSACTION_ID = "CT02";

    /**
     * The REST resource: an insert, so {@code POST}.
     *
     * <p>Chosen from the program's behaviour rather than from the class's mandated name - see the R-B
     * discussion in this class's documentation. It cannot collide with {@code COTRN00C}'s
     * {@code GET /api/transactions} (different method) nor with the sibling view screen's natural
     * {@code GET /api/transactions/{tranId}} (different template).
     */
    public static final String TRANSACTIONS_PATH = "/api/transactions";

    /** {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} - L116 and L503, the no-communication-area target. */
    public static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** {@code MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM} - L138, the PF3 target when no caller is recorded. */
    public static final String MAIN_MENU_PROGRAM = "COMEN01C";

    // =================================================================================================
    // Widths - app/cbl/COTRN02C.cbl:38-69 and the copybooks it copies.
    // =================================================================================================

    /** {@code 05 WS-MESSAGE PIC X(80) VALUE SPACES} - L38. Wider than {@code ERRMSGO}, deliberately. */
    public static final int WS_MESSAGE_LENGTH = 80;

    /** {@code 05 WS-ACCT-ID-N PIC 9(11) VALUE 0} - L55, and {@code XREF-ACCT-ID PIC 9(11)}. */
    public static final int WS_ACCT_ID_N_DIGITS = 11;

    /** {@code 05 WS-CARD-NUM-N PIC 9(16) VALUE 0} - L56, and {@code XREF-CARD-NUM PIC X(16)}. */
    public static final int WS_CARD_NUM_N_DIGITS = 16;

    /** {@code 05 WS-TRAN-ID-N PIC 9(16) VALUE ZEROS} - L57, and {@code TRAN-ID PIC X(16)}. */
    public static final int WS_TRAN_ID_N_DIGITS = 16;

    /** {@code 05 WS-TRAN-AMT-N PIC S9(9)V99 VALUE ZERO} - L58: nine integer digits. */
    public static final int WS_TRAN_AMT_N_INTEGER_DIGITS = 9;

    /** The scale of every monetary item in this program, which is the codebase-wide scale of two. */
    public static final int MONETARY_SCALE = CobolDecimal.MONETARY_SCALE;

    /**
     * {@code 05 WS-TRAN-AMT-E PIC +99999999.99 VALUE ZEROS} - L59: <strong>eight</strong> integer digits.
     *
     * <p>One fewer than {@code WS-TRAN-AMT-N} and one fewer than {@code TRAN-AMT}, so
     * {@code MOVE WS-TRAN-AMT-N TO WS-TRAN-AMT-E} at L385 and {@code MOVE TRAN-AMT TO WS-TRAN-AMT-E} at
     * L481 both genuinely discard the ninth integer digit. That is the legacy behaviour and it is
     * preserved rather than corrected - widening the mask to nine digits would change what the screen
     * shows for any amount at or above one hundred million.
     */
    public static final int WS_TRAN_AMT_E_INTEGER_DIGITS = 8;

    /**
     * The rendered width of {@code PIC +99999999.99}: one forced sign, eight integer digits, the
     * literal decimal point and two fraction digits.
     *
     * <p>Exactly {@link TransactionViewRequest#TRNAMT_LENGTH}, which is why the screen field is
     * {@code PIC X(12)} and why the positional check at L340-343 can index into it.
     */
    public static final int WS_TRAN_AMT_E_LENGTH =
            1 + WS_TRAN_AMT_E_INTEGER_DIGITS + 1 + MONETARY_SCALE;

    /** {@code 05 WS-RESP-CD PIC S9(09) COMP} - L47, rendered nine digits wide by {@code DISPLAY}. */
    public static final int WS_RESP_CD_DIGITS = 9;

    /** {@code 05 WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} - L60, the mask both date checks pass. */
    public static final String WS_DATE_FORMAT = "YYYY-MM-DD";

    /**
     * The width of the two title literals {@code POPULATE-HEADER-INFO} moves at L556-557.
     *
     * <p>{@code COPY COTTL01Y} at L84 declares {@code CCDA-TITLE01} and {@code CCDA-TITLE02} as
     * {@code PIC X(40)}, which is exactly {@code TITLE01O} and {@code TITLE02O} - so those two moves are
     * width-for-width and nothing is truncated. Sourced from {@link ScreenTitles#TITLE_LENGTH} so the
     * agreement is compile-checked rather than asserted in prose.
     *
     * <p>{@link ScreenTitles#CCDA_THANK_YOU} is a third {@code PIC X(40)} literal in the same copybook and
     * is <strong>not</strong> {@link SystemMessages#CCDA_MSG_THANK_YOU}, which is {@code PIC X(50)} in
     * {@code CSMSG01Y}. This program uses neither; the distinction is recorded because confusing them
     * produces a message of the wrong width, and this program does use {@code CSMSG01Y}'s other literal,
     * {@link SystemMessages#CCDA_MSG_INVALID_KEY}, at L150.
     */
    public static final int SCREEN_TITLE_LENGTH = ScreenTitles.TITLE_LENGTH;

    // =================================================================================================
    // The four dataset literals of L39-L42. Every one of them names a CICS file, and exactly one of
    // them is never used.
    // =================================================================================================

    /**
     * {@code 05 WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} - L39.
     *
     * <p>Published so a reviewer can confirm the literal without opening the COBOL. It is
     * <strong>not</strong> how the dataset is addressed: {@link TransactionRepository} resolves the real
     * name from {@code carddemo.datasets.TRANSACT}, so no {@code AWS.M2.CARDDEMO.*} literal appears in
     * Java (gate G46). The value is taken from the repository's own constant rather than retyped, so the
     * two cannot drift.
     */
    public static final String WS_TRANSACT_FILE = TransactionRepository.CICS_FILE_NAME;

    /** {@code 05 WS-CCXREF-FILE PIC X(08) VALUE 'CCXREF  '} - L41: the base cluster, sixteen-byte key. */
    public static final String WS_CCXREF_FILE = CardXrefRepository.BASE_DD_NAME;

    /**
     * {@code 05 WS-CXACAIX-FILE PIC X(08) VALUE 'CXACAIX '} - L42: the alternate-index path over the
     * same cross-reference dataset, eleven-byte key.
     */
    public static final String WS_CXACAIX_FILE = CardXrefRepository.ALTERNATE_INDEX_DD_NAME;

    /**
     * {@code 05 WS-ACCTDAT-FILE PIC X(08) VALUE 'ACCTDAT '} - L40. <strong>Dead: declared and never
     * referenced.</strong>
     *
     * <p>Verified by reading every {@code EXEC CICS} command in the program - there are eight, and none
     * of them names this file. It is preserved because practice B5 forbids tidying a legacy declaration
     * away: deleting it would make the Java working storage narrower than the COBOL's, and a future
     * reader comparing the two would be told the dataset was never in the picture. The value is sourced
     * from {@link AccountRepository#CICS_FILE_NAME} so that the constant is real rather than a retyped
     * string, and so that this file's only mention of {@code ACCTDAT} is a compile-checked one.
     *
     * <p><strong>No account access is performed anywhere in this class</strong>, because the program
     * performs none. Do not "complete" the migration by adding one.
     */
    public static final String WS_ACCTDAT_FILE = AccountRepository.CICS_FILE_NAME;

    /**
     * {@code COPY CVACT01Y} - L89. <strong>Dead: copied and never referenced.</strong>
     *
     * <p>{@code COTRN02C} copies the 300-byte {@code ACCOUNT-RECORD} and then uses no field of it,
     * which is the copybook counterpart of the dead {@link #WS_ACCTDAT_FILE} literal above. Recording
     * its declared width keeps the fact visible and compile-checked; modelling an account here would be
     * an unrequested behaviour change.
     */
    public static final int COPIED_ACCOUNT_RECORD_LENGTH = AccountRecord.RECORD_LENGTH;

    /**
     * {@code 05 WS-TRAN-AMT PIC +99999999.99} - L53. <strong>Dead: declared and never referenced.</strong>
     *
     * <p>Distinct from {@code WS-TRAN-AMT-E} at L59, which carries the same picture and <em>is</em>
     * used. Both are recorded so the duplicate declaration stays visible.
     */
    public static final String WS_TRAN_AMT_PICTURE = "+99999999.99";

    /**
     * {@code 05 WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} - L54. <strong>Dead: declared and never
     * referenced.</strong>
     */
    public static final String WS_TRAN_DATE_VALUE = "00/00/00";

    // =================================================================================================
    // The CSUTLDTC acceptance rule - app/cbl/COTRN02C.cbl:62-69 and :397-427.
    // =================================================================================================

    /**
     * {@code IF CSUTLDTC-RESULT-SEV-CD = '0000'} - L397 and L417: the unconditional accept.
     *
     * <p>{@code CSUTLDTC} composes its eighty-byte reply with the {@code CEEDAYS} feedback severity in
     * its first four bytes, and {@code FC-INVALID-DATE} - the token that means <em>valid</em> - carries
     * severity zero.
     */
    public static final String CSUTLDTC_SEVERITY_OK = "0000";

    /**
     * {@code IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'} - L400 and L420: the one error both call sites
     * deliberately <strong>accept</strong>.
     *
     * <p>{@code app/cbl/CSUTLDTC.cbl:66} declares
     * {@code 88 FC-UNSUPP-RANGE VALUE X'000309D159C3C5C5'}, whose first halfword {@code X'0003'} is
     * severity 3 and whose second {@code X'09D1'} is decimal 2513. So a date outside the range
     * {@code CEEDAYS} supports is reported as an error and then tolerated by this screen. Preserve that
     * tolerance exactly: turning it into a rejection would refuse dates the legacy program accepts, and
     * dropping it would accept dates the legacy program refuses.
     */
    public static final String CSUTLDTC_TOLERATED_MESSAGE_NUMBER = "2513";

    // =================================================================================================
    // WS-MESSAGE literals. Every one is transcribed byte for byte from the source, including the
    // trailing ellipses, the capitalisation and the double space in the success notice.
    // =================================================================================================

    /** L178-179, the {@code CONFIRM} arm for {@code 'N'}, {@code 'n'}, spaces and low-values. */
    public static final String MSG_CONFIRM_TO_ADD = "Confirm to add this transaction...";

    /** L184-185, the {@code CONFIRM} {@code WHEN OTHER} arm. */
    public static final String MSG_INVALID_CONFIRM_VALUE = "Invalid value. Valid values are (Y/N)...";

    /** L199-200. */
    public static final String MSG_ACCOUNT_ID_NOT_NUMERIC = "Account ID must be Numeric...";

    /** L213-214. */
    public static final String MSG_CARD_NUMBER_NOT_NUMERIC = "Card Number must be Numeric...";

    /** L226-227, the {@code VALIDATE-INPUT-KEY-FIELDS} {@code WHEN OTHER} arm. */
    public static final String MSG_KEY_REQUIRED = "Account or Card Number must be entered...";

    /** L254-255. */
    public static final String MSG_TYPE_CD_EMPTY = "Type CD can NOT be empty...";

    /** L260-261. */
    public static final String MSG_CATEGORY_CD_EMPTY = "Category CD can NOT be empty...";

    /** L266-267. */
    public static final String MSG_SOURCE_EMPTY = "Source can NOT be empty...";

    /** L272-273. */
    public static final String MSG_DESCRIPTION_EMPTY = "Description can NOT be empty...";

    /** L278-279. */
    public static final String MSG_AMOUNT_EMPTY = "Amount can NOT be empty...";

    /** L284-285. */
    public static final String MSG_ORIG_DATE_EMPTY = "Orig Date can NOT be empty...";

    /** L290-291. */
    public static final String MSG_PROC_DATE_EMPTY = "Proc Date can NOT be empty...";

    /** L296-297. */
    public static final String MSG_MERCHANT_ID_EMPTY = "Merchant ID can NOT be empty...";

    /** L302-303. */
    public static final String MSG_MERCHANT_NAME_EMPTY = "Merchant Name can NOT be empty...";

    /** L308-309. */
    public static final String MSG_MERCHANT_CITY_EMPTY = "Merchant City can NOT be empty...";

    /** L314-315. */
    public static final String MSG_MERCHANT_ZIP_EMPTY = "Merchant Zip can NOT be empty...";

    /** L325-326. */
    public static final String MSG_TYPE_CD_NOT_NUMERIC = "Type CD must be Numeric...";

    /** L331-332. */
    public static final String MSG_CATEGORY_CD_NOT_NUMERIC = "Category CD must be Numeric...";

    /** L345-346. Note the mask quoted to the operator shows a minus sign, not the {@code +} of L59. */
    public static final String MSG_AMOUNT_FORMAT = "Amount should be in format -99999999.99";

    /** L360-361. */
    public static final String MSG_ORIG_DATE_FORMAT = "Orig Date should be in format YYYY-MM-DD";

    /** L375-376. */
    public static final String MSG_PROC_DATE_FORMAT = "Proc Date should be in format YYYY-MM-DD";

    /** L401-402, the {@code CSUTLDTC} rejection for {@code TORIGDTI}. */
    public static final String MSG_ORIG_DATE_INVALID = "Orig Date - Not a valid date...";

    /** L421-422, the {@code CSUTLDTC} rejection for {@code TPROCDTI}. */
    public static final String MSG_PROC_DATE_INVALID = "Proc Date - Not a valid date...";

    /** L432-433. */
    public static final String MSG_MERCHANT_ID_NOT_NUMERIC = "Merchant ID must be Numeric...";

    /** L593-594, the {@code CXACAIX} {@code NOTFND} arm. */
    public static final String MSG_ACCOUNT_ID_NOT_FOUND = "Account ID NOT found...";

    /** L600-601, the {@code CXACAIX} {@code WHEN OTHER} arm. */
    public static final String MSG_XREF_AIX_LOOKUP_FAILED =
            "Unable to lookup Acct in XREF AIX file...";

    /** L626-627, the {@code CCXREF} {@code NOTFND} arm. */
    public static final String MSG_CARD_NUMBER_NOT_FOUND = "Card Number NOT found...";

    /** L633-634, the {@code CCXREF} {@code WHEN OTHER} arm. */
    public static final String MSG_XREF_LOOKUP_FAILED = "Unable to lookup Card # in XREF file...";

    /** L657-658, the {@code STARTBR} {@code NOTFND} arm. */
    public static final String MSG_TRANSACTION_ID_NOT_FOUND = "Transaction ID NOT found...";

    /**
     * L664-665 and L693-694, the {@code STARTBR} and {@code READPREV} {@code WHEN OTHER} arms.
     *
     * <p>One literal, two sites, byte-identical in the source - so it is declared once here too.
     */
    public static final String MSG_TRANSACTION_LOOKUP_FAILED = "Unable to lookup Transaction...";

    /** L738-739, the {@code WRITE} {@code DUPKEY} / {@code DUPREC} arm. */
    public static final String MSG_TRAN_ID_ALREADY_EXISTS = "Tran ID already exist...";

    /** L745-746, the {@code WRITE} {@code WHEN OTHER} arm. */
    public static final String MSG_UNABLE_TO_ADD = "Unable to Add Transaction...";

    /**
     * The first {@code STRING} operand of the success notice - L728-729,
     * {@code DELIMITED BY SIZE}, so its trailing space counts.
     */
    public static final String MSG_ADDED_SUCCESSFULLY = "Transaction added successfully. ";

    /**
     * The second {@code STRING} operand - L730, also {@code DELIMITED BY SIZE}, and it opens with a
     * space of its own. Two adjacent spaces therefore appear in the composed message, which is
     * deliberate and must be preserved.
     */
    public static final String MSG_YOUR_TRAN_ID_IS = " Your Tran ID is ";

    /** The fourth {@code STRING} operand - L732, the full stop that closes the notice. */
    public static final String MSG_FULL_STOP = ".";

    /** {@code DISPLAY 'RESP:'} - L598, L631, L662, L691 and L743. */
    public static final String DISPLAY_RESP_PREFIX = "RESP:";

    /** {@code 'REAS:'} - the second literal of the same five {@code DISPLAY} statements. */
    public static final String DISPLAY_REAS_PREFIX = "REAS:";

    // =================================================================================================
    // The CONFIRM values of the EVALUATE at L169-L188, in the source's order.
    // =================================================================================================

    /** {@code WHEN 'Y'} - L170. */
    public static final String CONFIRM_YES_UPPER = "Y";

    /** {@code WHEN 'y'} - L171. Both cases are accepted, and only these two. */
    public static final String CONFIRM_YES_LOWER = "y";

    /** {@code WHEN 'N'} - L173. */
    public static final String CONFIRM_NO_UPPER = "N";

    /** {@code WHEN 'n'} - L174. */
    public static final String CONFIRM_NO_LOWER = "n";

    // =================================================================================================
    // Positional edit geometry - the reference modifications at L340-L343 and L354-L373. Declared as
    // named constants because a mistyped offset here is the classic silent parity defect, and because
    // the source's own choices (a nine-digit amount checked in an eight-digit window, position 9 of the
    // amount left unchecked) have to be visible to be preserved.
    // =================================================================================================

    /** {@code TRNAMTI(1:1)} - the sign position, which must be {@code '-'} or {@code '+'}. */
    public static final int AMOUNT_SIGN_OFFSET = 0;

    /** {@code TRNAMTI(2:8)} - the eight integer digits. */
    public static final int AMOUNT_INTEGER_OFFSET = 1;

    /** The length of the {@code TRNAMTI(2:8)} window. */
    public static final int AMOUNT_INTEGER_LENGTH = 8;

    /**
     * {@code TRNAMTI(10:1)} - the decimal point.
     *
     * <p>Position 9 is <strong>never checked</strong>: the integer window ends at position 9 exclusive
     * and the point is tested at position 10, so byte 9 of the field is unexamined. The mask
     * {@code +99999999.99} puts the ninth integer digit exactly there, which is the same off-by-one the
     * eight-digit edit mask carries. Reproduced as written.
     */
    public static final int AMOUNT_POINT_OFFSET = 9;

    /** {@code TRNAMTI(11:2)} - the two fraction digits. */
    public static final int AMOUNT_FRACTION_OFFSET = 10;

    /** The length of the {@code TRNAMTI(11:2)} window. */
    public static final int AMOUNT_FRACTION_LENGTH = 2;

    /** {@code '.'} - the literal the decimal-point position must hold. */
    public static final char DECIMAL_POINT = '.';

    /** {@code '-'} - one of the two signs the amount's first position may hold. */
    public static final char MINUS_SIGN = '-';

    /** {@code '+'} - the other, and the one the edit mask always emits for a non-negative value. */
    public static final char PLUS_SIGN = '+';

    /** {@code xxxDTI(1:4)} - the year. */
    public static final int DATE_YEAR_OFFSET = 0;

    /** The width of the year window. */
    public static final int DATE_YEAR_LENGTH = 4;

    /** {@code xxxDTI(5:1)} - the first hyphen. */
    public static final int DATE_FIRST_SEPARATOR_OFFSET = 4;

    /** {@code xxxDTI(6:2)} - the month. */
    public static final int DATE_MONTH_OFFSET = 5;

    /** {@code xxxDTI(8:1)} - the second hyphen. */
    public static final int DATE_SECOND_SEPARATOR_OFFSET = 7;

    /** {@code xxxDTI(9:2)} - the day. */
    public static final int DATE_DAY_OFFSET = 8;

    /** The width of the month and of the day window alike. */
    public static final int DATE_PART_LENGTH = 2;

    /** {@code '-'} - the separator both date checks require at positions 5 and 8. */
    public static final char DATE_SEPARATOR = '-';

    // =================================================================================================
    // Intrinsic-function conventions.
    // =================================================================================================

    /**
     * The verdict {@link #testNumval(String)} and {@link #testNumvalC(String)} return for an argument
     * that conforms, matching how {@code FUNCTION TEST-NUMVAL} reports one.
     */
    public static final int NUMVAL_CONFORMS = 0;

    /** {@code MOVE ZEROS TO TRAN-ID} - L689: the {@code ENDFILE} arm, so the first generated id is 1. */
    public static final String TRAN_ID_ZEROS = "0".repeat(WS_TRAN_ID_N_DIGITS);

    /** A single space, used wherever the source writes {@code SPACES} into a character field. */
    private static final String SPACE = " ";

    /** {@code ADD 1 TO WS-TRAN-ID-N} - L449. */
    private static final long ONE = 1L;


    // =================================================================================================
    // Collaborators. All final, all constructor-injected, none static (practice B9, gate G53).
    // =================================================================================================

    /** The {@code TRANSACT} master: the backward browse of L444-447 and the {@code WRITE} of L713. */
    private final TransactionRepository transactionRepository;

    /**
     * The cross-reference dataset, reached by <strong>two access paths and never as two tables</strong>
     * (gate G45): {@code CXACAIX} for L578 and {@code CCXREF} for L611.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * {@code CALL 'CSUTLDTC'} at L393 and L413 - injected, not copied.
     *
     * <p>It is a {@code @Service} and emphatically <strong>not</strong> a Spring Batch {@code Job}
     * despite the {@code ...Job} name the prompt assigns it (gate G12): no {@code EXEC PGM=} names
     * {@code CSUTLDTC} anywhere in {@code app/jcl} or {@code app/proc}, and its only two callers in the
     * whole estate are this program and {@code CORPT00C} - both of them online.
     */
    private final DateUtilityJob dateUtilityJob;

    /**
     * The clock behind {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at L554.
     *
     * <p>Injected rather than read inline, because a {@code LocalDateTime.now()} buried in
     * {@code POPULATE-HEADER-INFO} would make the header of every parity case non-deterministic and the
     * case would then have to stop asserting two of its twenty-one fields.
     */
    private final Clock clock;

    /**
     * The picture-rule engine every {@code MOVE} in this class is routed through, carrying the active
     * dataset code page.
     *
     * <p>{@code MOVE} is the dominant parity risk in this codebase - 2,795 occurrences across the
     * twenty-eight programs - because COBOL truncates a {@code PIC X} receiver on the right and a
     * {@code PIC 9} receiver on the left, and a plain Java assignment does neither. Naming the
     * direction at the call site is the whole point of going through the codec.
     */
    private final FixedWidthCodec codec;

    /**
     * The wiring constructor.
     *
     * <p>The code page is deliberately <strong>not</strong> a separate injection point: it is taken from
     * the {@link TransactionRepository} this controller writes through, so the two can never disagree
     * about how a 350-byte record is encoded. That also means this class names no charset bean, which
     * matters because the module publishes three {@link Charset} beans and no primary among them -
     * selection is mandatory by design, and the most defensible selection here is "whatever the dataset
     * I am writing to uses".
     *
     * @param transactionRepository the {@code TRANSACT} master; must not be {@code null}
     * @param cardXrefRepository    the cross-reference dataset and its alternate-index path; must not be
     *                              {@code null}
     * @param dateUtilityJob        the {@code CSUTLDTC} subprogram; must not be {@code null}
     * @param clock                 the clock {@code POPULATE-HEADER-INFO} reads; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}, or if the repository reports no code
     *                              page
     */
    @Autowired
    public TransactionViewController(TransactionRepository transactionRepository,
                                     CardXrefRepository cardXrefRepository,
                                     DateUtilityJob dateUtilityJob,
                                     Clock clock) {
        this(transactionRepository, cardXrefRepository, dateUtilityJob, clock,
                repositoryCharset(transactionRepository));
    }

    /**
     * The explicit-charset constructor, for a caller that already holds the code page.
     *
     * <p>A parity case has to build its expected 350-byte image with the <em>same</em> code page the
     * controller encodes with - building it with a different one compares two encodings and calls the
     * difference a defect - so the page is settable directly rather than only derivable from a
     * repository.
     *
     * @param transactionRepository the {@code TRANSACT} master; must not be {@code null}
     * @param cardXrefRepository    the cross-reference dataset; must not be {@code null}
     * @param dateUtilityJob        the {@code CSUTLDTC} subprogram; must not be {@code null}
     * @param clock                 the clock; must not be {@code null}
     * @param datasetCharset        the code page every fixed-width field is encoded in; must not be
     *                              {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public TransactionViewController(TransactionRepository transactionRepository,
                                     CardXrefRepository cardXrefRepository,
                                     DateUtilityJob dateUtilityJob,
                                     Clock clock,
                                     Charset datasetCharset) {
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "A TransactionRepository is required: COTRN02C browses TRANSACT backwards for the "
                        + "highest TRAN-ID at L444-447 and writes the new record at L713, and this "
                        + "controller reaches that dataset no other way");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository,
                "A CardXrefRepository is required: COTRN02C reads the CXACAIX path at L578 and the "
                        + "CCXREF base at L611, which are two access paths over one dataset");
        this.dateUtilityJob = Objects.requireNonNull(dateUtilityJob,
                "A DateUtilityJob is required: COTRN02C calls CSUTLDTC at L393 and L413, and the "
                        + "'2513' tolerance those two sites apply cannot be reproduced without it");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: POPULATE-HEADER-INFO reads FUNCTION CURRENT-DATE at L554, and "
                        + "reading a clock inline would make every parity case non-deterministic");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset,
                "A dataset charset is required: a fixed-width mainframe field is bytes in a specific "
                        + "code page, so the page is always stated and never taken from the platform"));
    }

    /**
     * Reads the code page from the repository the wiring constructor was handed.
     *
     * <p>Extracted so both null cases - an absent repository and a repository that reports no page - are
     * reported with their own message and are reachable from a plain unit test.
     *
     * @param repository the repository supplied to the wiring constructor
     * @return its dataset code page, never {@code null}
     * @throws NullPointerException if the repository is {@code null} or reports no code page
     */
    private static Charset repositoryCharset(TransactionRepository repository) {
        Objects.requireNonNull(repository, "A TransactionRepository is required before its code page "
                + "can be read");
        return Objects.requireNonNull(repository.datasetCharset(),
                "The TransactionRepository reported no dataset code page; a 350-byte TRAN-RECORD "
                        + "cannot be encoded without one");
    }

    /**
     * The code page every fixed-width field in this controller is encoded in.
     *
     * <p>Package-visible rather than {@code public}: a parity case and a deployment check both need to
     * assert which page was wired without starting the application (residual risk R-E), and a code page
     * is this module's business and no client's.
     *
     * @return the codec, never {@code null} and immutable
     */
    FixedWidthCodec codec() {
        return codec;
    }

    // =================================================================================================
    // The HTTP adapter. Thin by design (practice B10, gate G51): it maps and it delegates, and every
    // decision this screen makes is in a method a parity case can call with no HTTP in the path.
    // =================================================================================================

    /**
     * {@code POST }{@value #TRANSACTIONS_PATH} - validate the operator's entry and insert a transaction.
     *
     * <p>An insert, because {@code COTRN02C} inserts. See the R-B discussion in this class's
     * documentation for why the class is nonetheless named {@code TransactionViewController}.
     *
     * <p>Bean Validation on the request body is width-only: {@code @Size} maxima taken from the
     * symbolic map's {@code PICTURE} clauses, with no {@code @NotNull}, no {@code @Pattern} and no
     * {@code @Digits}. The program performs its own extensive editing at L193-437, and any additional
     * Java rejection would refuse an input the COBOL accepts, which is a parity break.
     *
     * <h2>Why this method is transactional and {@link #mainPara} is not annotated</h2>
     *
     * <p>The add path is two commands against one file, and they belong together. L644-650 browses
     * {@code TRANSACT} backwards to take the highest existing identifier - the source's own comment
     * calls it a high-water mark - and L713-721 writes the record built from the next one. The module's
     * pool runs with {@code auto-commit: false}, so an insert issued with no transaction open is rolled
     * back when the connection returns to the pool, while the screen still says
     * {@code 'Transaction added successfully...'} and names the identifier: the repository reported
     * {@code NORMAL} and it was telling the truth about the statement it executed. Splitting the two
     * commands across two connections is the second problem - the probe would read a snapshot the write
     * never sees.
     *
     * <p>{@code @Transactional} on the entry point reproduces the CICS task's unit of work, which is
     * what spans the probe and the write on the mainframe and what the task's syncpoint at
     * {@code RETURN} commits. It sits here rather than deeper for the same reason
     * {@code COUSR02C}'s does: a parity test drives {@link #mainPara} directly with a stubbed
     * repository, where there is no connection to commit. Note that the duplicate arm at L740-741
     * remains reachable and is not made redundant by the boundary - two operators adding at once can
     * still compute the same next key, because the probe takes no lock, exactly as the COBOL takes
     * none.
     *
     * @param request the inbound screen, the communication area and the {@code EIBAID}; must not be
     *                {@code null}
     * @return the painted screen, the next program, the communication area to carry forward and the
     *         presentation metadata - the cursor request, the twenty-one attribute quads and the
     *         message colour - which are metadata by declaration and travel beside the screen rather
     *         than inside it
     * @throws NullPointerException if {@code request} is {@code null}
     */
    @PostMapping(path = TRANSACTIONS_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ScreenResponse<TransactionViewResponse> addTransaction(
            @Valid @RequestBody TransactionViewRequest request) {
        ProgramState state = mainPara(request);
        return ScreenResponse.of(state.response(), state.screenMetadata());
    }

    // =================================================================================================
    // MAIN-PARA - app/cbl/COTRN02C.cbl:107-159.
    // =================================================================================================

    /**
     * {@code MAIN-PARA} - the program's entry point, lines 107 to 159.
     *
     * <p>Returns the terminal {@link ProgramState} rather than only the response, because three things
     * this program observably produces have no home in the symbolic map's payload: the
     * {@code MOVE -1 TO xxxL} cursor request, which is {@code xxxL} metadata and must not be smuggled
     * into a payload that projects only {@code xxxI} and {@code xxxO} items (gate G9); the five
     * {@code DISPLAY} lines; and the record actually handed to the master. A parity case needs all
     * three, so they are reported on the state and {@link #addTransaction} projects
     * {@link ProgramState#response()}.
     *
     * <p>Each arm ends with a {@code return}, because in the source each arm has already returned to
     * CICS or transferred to another program by the time it finishes. The {@code EXEC CICS RETURN} the
     * source writes at L156-159 is byte for byte the one {@code SEND-TRNADD-SCREEN} executes at L530,
     * and it is unreachable for exactly that reason; it is rendered by
     * {@link #returnToCics(ProgramState)}, which every re-display arm reaches through
     * {@link #sendTrnaddScreen(ProgramState)}.
     *
     * @param request the inbound screen; must not be {@code null}
     * @return the state at the moment the task returned to CICS or transferred; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public ProgramState mainPara(TransactionViewRequest request) {
        Objects.requireNonNull(request, "A request is required: COTRN02C is driven entirely by its "
                + "communication area, the EIBAID and the received map, all of which travel in it");

        ProgramState state = new ProgramState(codec);

        state.setErrFlagOff();                                                            // L109
        state.setUsrModifiedNo();                                                         // L110

        state.setMessage(spaces(WS_MESSAGE_LENGTH));                                      // L112
        state.response().clearErrmsgo();                                                  // L113

        // L115 IF EIBCALEN = 0. Nothing was passed, so nothing is known about who called and the screen
        // cannot be painted: the program hands control to the sign-on program.
        if (!request.hasNavigationContext()) {
            state.setCommarea(state.commarea().withToProgram(SIGN_ON_PROGRAM));            // L116
            returnToPrevScreen(state);                                                    // L117
            return state;
        }

        // L119 MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA - the 160-byte area and the 58-byte
        // CT02 extension the program appends to it at L72-80, together 218 bytes.
        state.setCommarea(request.getNavigationContext());
        state.adoptCt02Info(request.getCt02Info());

        // L120 IF NOT CDEMO-PGM-REENTER - first entry, so paint the screen and ask for input.
        if (!state.commarea().isReenter()) {
            state.setCommarea(state.commarea().withPgmReenter());                          // L121
            state.moveLowValuesToOutputMap();                                              // L122
            state.moveMinusOneTo(ScreenField.ACTIDIN);                                     // L123

            // L124 IF CDEMO-CT02-TRN-SELECTED NOT = SPACES AND LOW-VALUES. A transaction identifier
            // moves into the CARD NUMBER field, not into an identifier field - the map has none. That is
            // the source's own quirk, most likely inherited from COTRN01C where the same commarea slot
            // does key a transaction, and it is reproduced rather than repaired.
            if (!isSpacesOrLowValues(state.ct02Info().getTrnSelected())) {
                state.setCardninI(state.ct02Info().getTrnSelected());                      // L126-127
                processEnterKey(state);                                                    // L128
            }

            // L130 PERFORM SEND-TRNADD-SCREEN. Guarded, because every arm of PROCESS-ENTER-KEY ends the
            // task: when the CT02 cursor carried a selection this statement is unreachable in the source
            // and must not paint a second screen over the one already sent. When it did not, this is the
            // only send on the first-entry path.
            if (!state.taskEnded()) {
                sendTrnaddScreen(state);                                                   // L130
            }
            return state;
        }

        // L132 PERFORM RECEIVE-TRNADD-SCREEN.
        receiveTrnaddScreen(state, request);

        // L133-L152 EVALUATE EIBAID, in the source's order with WHEN OTHER last (gate G30). The raw AID
        // byte is reconstructed from the payload token so that these read as the source's four tests.
        byte eibAid = eibAidOf(request.getAid());
        if (PfKeyResolver.isEnter(eibAid)) {                                               // WHEN DFHENTER
            processEnterKey(state);                                                        // L135
            return state;
        }
        if (PfKeyResolver.isPf3(eibAid)) {                                                 // WHEN DFHPF3
            // L137 IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES.
            if (isSpacesOrLowValues(state.commarea().fromProgram())) {
                state.setCommarea(state.commarea().withToProgram(MAIN_MENU_PROGRAM));       // L138
            } else {
                state.setCommarea(state.commarea()
                        .withToProgram(state.commarea().fromProgram()));                    // L140-141
            }
            returnToPrevScreen(state);                                                      // L143
            return state;
        }
        if (PfKeyResolver.isPf4(eibAid)) {                                                  // WHEN DFHPF4
            clearCurrentScreen(state);                                                       // L145
            return state;
        }
        if (PfKeyResolver.isPf5(eibAid)) {                                                   // WHEN DFHPF5
            copyLastTranData(state);                                                          // L147
            return state;
        }
        // L148 WHEN OTHER.
        state.setErrFlagOn();                                                                 // L149
        state.setMessage(codec.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                WS_MESSAGE_LENGTH));                                                          // L150
        sendTrnaddScreen(state);                                                              // L151
        return state;
    }

    // =================================================================================================
    // PROCESS-ENTER-KEY - app/cbl/COTRN02C.cbl:164-188.
    // =================================================================================================

    /**
     * {@code PROCESS-ENTER-KEY} - lines 164 to 188.
     *
     * <p>Two validation passes and then an ordered {@code EVALUATE CONFIRMI} whose first match wins and
     * whose {@code WHEN OTHER} is last. The two guards after the validation performs are the rule R7
     * rendering of the fact that both paragraphs can end the task: neither of them returns to this
     * point after a rejection.
     *
     * <p><strong>Every arm of the {@code EVALUATE} ends the task</strong>, which is why
     * {@link #mainPara}'s L130 send is unreachable once this paragraph has run: {@code 'Y'} and
     * {@code 'y'} reach {@code ADD-TRANSACTION}, which always finishes in
     * {@code WRITE-TRANSACT-FILE} and whose three arms all send; the other four values send directly.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void processEnterKey(ProgramState state) {
        requireState(state);

        validateInputKeyFields(state);                                                        // L166
        if (state.taskEnded()) {
            return;
        }
        validateInputDataFields(state);                                                       // L167
        if (state.taskEnded()) {
            return;
        }

        // L169-L188 EVALUATE CONFIRMI OF COTRN2AI.
        String confirm = state.confirmI();
        if (CONFIRM_YES_UPPER.equals(confirm) || CONFIRM_YES_LOWER.equals(confirm)) {          // L170-171
            addTransaction(state);                                                             // L172
            return;
        }
        if (CONFIRM_NO_UPPER.equals(confirm) || CONFIRM_NO_LOWER.equals(confirm)
                || isSpacesOrLowValues(confirm)) {                                             // L173-176
            rejectAndSend(state, MSG_CONFIRM_TO_ADD, ScreenField.CONFIRM);                     // L177-181
            return;
        }
        // L182 WHEN OTHER.
        rejectAndSend(state, MSG_INVALID_CONFIRM_VALUE, ScreenField.CONFIRM);                  // L183-187
    }


    // =================================================================================================
    // VALIDATE-INPUT-KEY-FIELDS - app/cbl/COTRN02C.cbl:193-230.
    //
    // An ordered EVALUATE TRUE whose first match wins: the account id is preferred over the card number
    // when both were entered, and the WHEN OTHER arm is what an empty screen reaches. Each of the two
    // arms resolves ONE key and writes the OTHER back onto the screen from the cross-reference record,
    // so after this paragraph both key fields hold consistent, normalised values.
    // =================================================================================================

    /**
     * {@code VALIDATE-INPUT-KEY-FIELDS} - lines 193 to 230.
     *
     * <p>Two structural details are load-bearing and neither is an accident of transcription.
     *
     * <p><strong>The {@code COMPUTE} is outside the numeric test, and is nonetheless unreachable when
     * that test fires.</strong> L197-203 is a complete {@code IF ... END-IF} and L204 begins a new
     * statement, so the conversion is written unconditionally - but the {@code IF} ends with
     * {@code PERFORM SEND-TRNADD-SCREEN}, which returns to CICS, so a non-numeric account id never
     * reaches the conversion. Both facts are reproduced: the conversion sits after the block, and the
     * block returns. An {@code else} here would read more naturally and would be a different program.
     *
     * <p><strong>The normalised value is written back into the input item.</strong>
     * {@code MOVE WS-ACCT-ID-N TO XREF-ACCT-ID ACTIDINI} at L206-207 is one statement with two
     * receivers, and the second of them is the screen field the operator typed into. That is legal and
     * intended: {@code COTRN2AO REDEFINES COTRN2AI}, so the eleven-digit normalised form is what the
     * next {@code SEND} shows. The same pattern appears at L220-221 for the card number.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void validateInputKeyFields(ProgramState state) {
        requireState(state);

        // L195-L230 EVALUATE TRUE. Each guard is the abbreviated combined relation
        // "NOT = SPACES AND LOW-VALUES", that is: neither spaces nor low-values.
        if (!isSpacesOrLowValues(state.actidinI())) {                                      // L196
            // L197 IF ACTIDINI IS NOT NUMERIC.
            if (!isNumericClass(state.actidinI())) {
                rejectAndSend(state, MSG_ACCOUNT_ID_NOT_NUMERIC, ScreenField.ACTIDIN);     // L198-202
                return;
            }
            state.setWsAcctIdN(computeIntoPic9(numval(state.actidinI()),
                    WS_ACCT_ID_N_DIGITS));                                                 // L204-205
            String normalised = codec.movePic9(state.wsAcctIdN(), WS_ACCT_ID_N_DIGITS);
            state.setXrefAcctId(normalised);                                               // L206
            state.setActidinI(normalised);                                                 // L207
            readCxacaixFile(state);                                                        // L208
            if (state.taskEnded()) {
                return;
            }
            state.setCardninI(state.cardXrefRecord().xrefCardNum());                       // L209
            return;
        }
        if (!isSpacesOrLowValues(state.cardninI())) {                                      // L210
            // L211 IF CARDNINI IS NOT NUMERIC.
            if (!isNumericClass(state.cardninI())) {
                rejectAndSend(state, MSG_CARD_NUMBER_NOT_NUMERIC, ScreenField.CARDNIN);    // L212-216
                return;
            }
            state.setWsCardNumN(computeIntoPic9(numval(state.cardninI()),
                    WS_CARD_NUM_N_DIGITS));                                                // L218-219
            String normalised = codec.movePic9(state.wsCardNumN(), WS_CARD_NUM_N_DIGITS);
            state.setXrefCardNum(normalised);                                              // L220
            state.setCardninI(normalised);                                                 // L221
            readCcxrefFile(state);                                                         // L222
            if (state.taskEnded()) {
                return;
            }
            // L223 MOVE XREF-ACCT-ID TO ACTIDINI: a PIC 9(11) source into a PIC X(11) receiver, so the
            // eleven digit characters move across left justified and nothing is edited.
            state.setActidinI(codec.movePic9(state.cardXrefRecord().xrefAcctId(),
                    WS_ACCT_ID_N_DIGITS));
            return;
        }
        // L224 WHEN OTHER - neither key was entered.
        rejectAndSend(state, MSG_KEY_REQUIRED, ScreenField.ACTIDIN);                       // L225-229
    }

    // =================================================================================================
    // VALIDATE-INPUT-DATA-FIELDS - app/cbl/COTRN02C.cbl:235-437.
    //
    // The largest paragraph: one conditional blanking block, five ordered EVALUATE TRUEs, two CSUTLDTC
    // calls and one closing IF, in exactly that order. Every rejection ends the task, so the paragraph
    // reports the FIRST fault it finds and no later test runs - which is why the order is the contract.
    // =================================================================================================

    /**
     * {@code VALIDATE-INPUT-DATA-FIELDS} - lines 235 to 437.
     *
     * <p>The six checks are, in the source's order: the eleven-arm empty cascade (L251-320), the
     * two-arm numeric cascade over the type and category codes (L322-337), the positional amount check
     * (L339-351), the positional origination-date check (L353-366), the positional processing-date check
     * (L368-381), and - after the amount has been normalised and both dates have been through
     * {@code CSUTLDTC} - the merchant-id numeric check (L430-436).
     *
     * <p>Three things about it are worth stating before reading the code.
     *
     * <p><strong>The opening {@code IF ERR-FLG-ON} block at L237-249 is unreachable</strong> and is
     * preserved anyway (practice B5). Its only caller is {@code PROCESS-ENTER-KEY} at L167, immediately
     * after {@code VALIDATE-INPUT-KEY-FIELDS} - and every path in that paragraph that raises the flag
     * also returns to CICS, so the flag is always off on arrival. The block is what the program would do
     * if the flag were somehow set: blank the eleven detail fields so a rejected screen does not appear
     * to have been half accepted. It is rendered faithfully and is reachable from a test that sets the
     * flag directly, which is how its behaviour is pinned without inventing a caller for it.
     *
     * <p><strong>The amount is normalised in place through an eight-digit mask.</strong> L383-386
     * converts {@code TRNAMTI} with {@code FUNCTION NUMVAL-C}, stores the result in a
     * {@code PIC S9(9)V99} carrier, re-renders it through {@code PIC +99999999.99} and writes it back
     * over the screen field. The mask holds one integer digit fewer than the carrier, so an amount at or
     * above one hundred million is silently re-displayed without its leading digit. That is the legacy
     * behaviour; see {@link #WS_TRAN_AMT_E_INTEGER_DIGITS}.
     *
     * <p><strong>Both {@code CSUTLDTC} calls tolerate message 2513.</strong> Severity {@code '0000'} is
     * accepted outright; any other severity is accepted anyway when the message number is
     * {@value #CSUTLDTC_TOLERATED_MESSAGE_NUMBER}, which is {@code FC-UNSUPP-RANGE}. Only a third
     * outcome - a non-zero severity with a different message number - rejects.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void validateInputDataFields(ProgramState state) {
        requireState(state);

        // L237-L249 IF ERR-FLG-ON: blank the eleven detail fields. Unreachable from PROCESS-ENTER-KEY,
        // preserved because it is what the program says (practice B5).
        if (state.errFlagOn()) {
            blankDetailFields(state);                                                      // L238-248
        }

        // L251-L320 EVALUATE TRUE - the empty cascade, eleven arms then WHEN OTHER CONTINUE.
        if (isSpacesOrLowValues(state.ttypcdI())) {                                        // L252
            rejectAndSend(state, MSG_TYPE_CD_EMPTY, ScreenField.TTYPCD);                   // L253-257
            return;
        }
        if (isSpacesOrLowValues(state.tcatcdI())) {                                        // L258
            rejectAndSend(state, MSG_CATEGORY_CD_EMPTY, ScreenField.TCATCD);               // L259-263
            return;
        }
        if (isSpacesOrLowValues(state.trnsrcI())) {                                        // L264
            rejectAndSend(state, MSG_SOURCE_EMPTY, ScreenField.TRNSRC);                    // L265-269
            return;
        }
        if (isSpacesOrLowValues(state.tdescI())) {                                         // L270
            rejectAndSend(state, MSG_DESCRIPTION_EMPTY, ScreenField.TDESC);                // L271-275
            return;
        }
        if (isSpacesOrLowValues(state.trnamtI())) {                                        // L276
            rejectAndSend(state, MSG_AMOUNT_EMPTY, ScreenField.TRNAMT);                    // L277-281
            return;
        }
        if (isSpacesOrLowValues(state.torigdtI())) {                                       // L282
            rejectAndSend(state, MSG_ORIG_DATE_EMPTY, ScreenField.TORIGDT);                // L283-287
            return;
        }
        if (isSpacesOrLowValues(state.tprocdtI())) {                                       // L288
            rejectAndSend(state, MSG_PROC_DATE_EMPTY, ScreenField.TPROCDT);                // L289-293
            return;
        }
        if (isSpacesOrLowValues(state.midI())) {                                           // L294
            rejectAndSend(state, MSG_MERCHANT_ID_EMPTY, ScreenField.MID);                  // L295-299
            return;
        }
        if (isSpacesOrLowValues(state.mnameI())) {                                         // L300
            rejectAndSend(state, MSG_MERCHANT_NAME_EMPTY, ScreenField.MNAME);              // L301-305
            return;
        }
        if (isSpacesOrLowValues(state.mcityI())) {                                         // L306
            rejectAndSend(state, MSG_MERCHANT_CITY_EMPTY, ScreenField.MCITY);              // L307-311
            return;
        }
        if (isSpacesOrLowValues(state.mzipI())) {                                          // L312
            rejectAndSend(state, MSG_MERCHANT_ZIP_EMPTY, ScreenField.MZIP);                // L313-317
            return;
        }
        // L318 WHEN OTHER CONTINUE - no field is empty, so fall through to the next cascade.

        // L322-L337 EVALUATE TRUE - the numeric cascade, two arms then WHEN OTHER CONTINUE.
        if (!isNumericClass(state.ttypcdI())) {                                            // L323
            rejectAndSend(state, MSG_TYPE_CD_NOT_NUMERIC, ScreenField.TTYPCD);             // L324-328
            return;
        }
        if (!isNumericClass(state.tcatcdI())) {                                            // L329
            rejectAndSend(state, MSG_CATEGORY_CD_NOT_NUMERIC, ScreenField.TCATCD);         // L330-334
            return;
        }
        // L335 WHEN OTHER CONTINUE.

        // L339-L351 EVALUATE TRUE - the positional amount check. Four WHEN clauses share one action, so
        // the first that holds rejects and the rest are not evaluated.
        if (isMalformedAmount(state.trnamtI())) {
            rejectAndSend(state, MSG_AMOUNT_FORMAT, ScreenField.TRNAMT);                   // L344-348
            return;
        }
        // L349 WHEN OTHER CONTINUE.

        // L353-L366 EVALUATE TRUE - the positional origination-date check, five WHEN clauses, one action.
        if (isMalformedDate(state.torigdtI())) {
            rejectAndSend(state, MSG_ORIG_DATE_FORMAT, ScreenField.TORIGDT);               // L359-363
            return;
        }
        // L364 WHEN OTHER CONTINUE.

        // L368-L381 EVALUATE TRUE - the positional processing-date check, identical in shape.
        if (isMalformedDate(state.tprocdtI())) {
            rejectAndSend(state, MSG_PROC_DATE_FORMAT, ScreenField.TPROCDT);               // L374-378
            return;
        }
        // L379 WHEN OTHER CONTINUE.

        // L383-L386 the amount's normalise-in-place round trip: character form to numeric carrier to
        // edited form and back onto the screen.
        state.setWsTranAmtN(CobolDecimal.storeAtPicture(numvalC(state.trnamtI()),
                WS_TRAN_AMT_N_INTEGER_DIGITS, MONETARY_SCALE));                            // L383-384
        state.setWsTranAmtE(wsTranAmtEdited(state.wsTranAmtN()));                          // L385
        state.setTrnamtI(state.wsTranAmtE());                                              // L386

        // L389-L407 CSUTLDTC over TORIGDTI.
        if (!callCsutldtc(state, state.torigdtI())) {                                      // L389-400
            rejectAndSend(state, MSG_ORIG_DATE_INVALID, ScreenField.TORIGDT);              // L401-405
            return;
        }

        // L409-L427 CSUTLDTC over TPROCDTI.
        if (!callCsutldtc(state, state.tprocdtI())) {                                      // L409-420
            rejectAndSend(state, MSG_PROC_DATE_INVALID, ScreenField.TPROCDT);              // L421-425
            return;
        }

        // L430-L436 IF MIDI IS NOT NUMERIC - the one edit that is a bare IF rather than an EVALUATE arm,
        // and the last thing this paragraph does.
        if (!isNumericClass(state.midI())) {
            rejectAndSend(state, MSG_MERCHANT_ID_NOT_NUMERIC, ScreenField.MID);            // L431-435
        }
    }

    /**
     * {@code MOVE SPACES TO TTYPCDI ... MZIPI} - lines 238 to 248, the eleven receivers of the
     * unreachable {@code IF ERR-FLG-ON} block.
     *
     * <p>Extracted so the block's behaviour is pinned by a test that sets the flag directly rather than
     * left unexercised. The two key fields are deliberately <strong>not</strong> in the list: the source
     * blanks the eleven detail fields and leaves {@code ACTIDINI}, {@code CARDNINI} and {@code CONFIRMI}
     * alone, which is what distinguishes this block from {@code INITIALIZE-ALL-FIELDS}.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void blankDetailFields(ProgramState state) {
        requireState(state);
        for (ScreenField field : DETAIL_FIELDS) {
            state.setPayload(field, spaces(field.width()));
        }
    }

    /**
     * The eleven detail fields the {@code IF ERR-FLG-ON} block at L238-248 blanks, in the source's order.
     *
     * <p>Immutable, so it is a constant and not mutable static state (practice B9, gate G53).
     */
    private static final List<ScreenField> DETAIL_FIELDS = List.of(
            ScreenField.TTYPCD, ScreenField.TCATCD, ScreenField.TRNSRC, ScreenField.TRNAMT,
            ScreenField.TDESC, ScreenField.TORIGDT, ScreenField.TPROCDT, ScreenField.MID,
            ScreenField.MNAME, ScreenField.MCITY, ScreenField.MZIP);

    /**
     * The positional amount check of L340-343, as one predicate over the four {@code WHEN} clauses that
     * share the single rejection at L344-348.
     *
     * <pre>
     * WHEN TRNAMTI(1:1)  NOT EQUAL '-' AND '+'     the sign
     * WHEN TRNAMTI(2:8)  NOT NUMERIC               eight integer digits
     * WHEN TRNAMTI(10:1) NOT = '.'                 the decimal point
     * WHEN TRNAMTI(11:2) IS NOT NUMERIC            two fraction digits
     * </pre>
     *
     * <p>Position 9 of the twelve is examined by none of them, and that gap is preserved. It is where the
     * ninth integer digit of a {@code PIC S9(9)V99} amount would sit, and the fact that the screen's
     * mask has no room for it is the same off-by-one from the other direction.
     *
     * @param image the {@code TRNAMTI} value at its declared width; must not be {@code null}
     * @return {@code true} when any of the four clauses holds, so the screen must be rejected
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static boolean isMalformedAmount(String image) {
        Objects.requireNonNull(image, "An amount image is required: the screen field is PIC X(12) and "
                + "the source indexes into it by position");
        char sign = charAtOrSpace(image, AMOUNT_SIGN_OFFSET);
        if (sign != MINUS_SIGN && sign != PLUS_SIGN) {
            return true;
        }
        if (!isNumericClass(window(image, AMOUNT_INTEGER_OFFSET, AMOUNT_INTEGER_LENGTH))) {
            return true;
        }
        if (charAtOrSpace(image, AMOUNT_POINT_OFFSET) != DECIMAL_POINT) {
            return true;
        }
        return !isNumericClass(window(image, AMOUNT_FRACTION_OFFSET, AMOUNT_FRACTION_LENGTH));
    }

    /**
     * The positional date check of L354-358 and L369-373, which are byte-identical apart from the field
     * they read - so they are one predicate here, applied twice.
     *
     * <pre>
     * WHEN xxxDTI(1:4) IS NOT NUMERIC     the year
     * WHEN xxxDTI(5:1) NOT EQUAL '-'
     * WHEN xxxDTI(6:2) NOT NUMERIC        the month
     * WHEN xxxDTI(8:1) NOT EQUAL '-'
     * WHEN xxxDTI(9:2) NOT NUMERIC        the day
     * </pre>
     *
     * <p>The five windows tile the ten bytes exactly, so this check alone establishes the
     * {@code YYYY-MM-DD} shape. Whether the date so shaped is a real calendar date is a separate
     * question, and it is {@code CSUTLDTC} at L393 and L413 that answers it.
     *
     * @param image the {@code TORIGDTI} or {@code TPROCDTI} value at its declared width; must not be
     *              {@code null}
     * @return {@code true} when any of the five clauses holds, so the screen must be rejected
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static boolean isMalformedDate(String image) {
        Objects.requireNonNull(image, "A date image is required: the screen field is PIC X(10) and the "
                + "source indexes into it by position");
        if (!isNumericClass(window(image, DATE_YEAR_OFFSET, DATE_YEAR_LENGTH))) {
            return true;
        }
        if (charAtOrSpace(image, DATE_FIRST_SEPARATOR_OFFSET) != DATE_SEPARATOR) {
            return true;
        }
        if (!isNumericClass(window(image, DATE_MONTH_OFFSET, DATE_PART_LENGTH))) {
            return true;
        }
        if (charAtOrSpace(image, DATE_SECOND_SEPARATOR_OFFSET) != DATE_SEPARATOR) {
            return true;
        }
        return !isNumericClass(window(image, DATE_DAY_OFFSET, DATE_PART_LENGTH));
    }

    /**
     * One {@code CALL 'CSUTLDTC'} site - L389-400 for the origination date and L409-420 for the
     * processing date, which differ only in the field they pass and the message they report.
     *
     * <p>The four statements the source writes before each call are all here: the date moves into
     * {@code CSUTLDTC-DATE}, the {@value #WS_DATE_FORMAT} mask moves into
     * {@code CSUTLDTC-DATE-FORMAT}, the eighty-byte result area is blanked, and the subprogram is
     * called with the three parameters in order. The result is recorded on the state so a parity case can
     * assert the exact eighty bytes the subprogram composed, not merely the verdict drawn from them.
     *
     * <p>The verdict itself is read from the result's own severity and message-number accessors rather
     * than by re-slicing the eighty bytes at offsets 0 and 15. The two views are the same bytes -
     * {@code CSUTLDTC-RESULT-SEV-CD} redefines the first four and
     * {@code CSUTLDTC-RESULT-MSG-NUM} the four at offset 15 - and reading the typed view removes one
     * place for an offset to be mistyped.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @param date  the ten-byte date to validate; must not be {@code null}
     * @return {@code true} when the call site accepts the date - severity
     *         {@value #CSUTLDTC_SEVERITY_OK}, or any severity with message number
     *         {@value #CSUTLDTC_TOLERATED_MESSAGE_NUMBER}; {@code false} when it rejects
     * @throws NullPointerException if either argument is {@code null}
     */
    public boolean callCsutldtc(ProgramState state, String date) {
        requireState(state);
        Objects.requireNonNull(date, "A date is required: the source moves the screen field into "
                + "CSUTLDTC-DATE before every call");

        state.setCsutldtcDate(codec.movePicX(date, DateUtilityJob.LS_DATE_LENGTH));
        state.setCsutldtcDateFormat(codec.movePicX(WS_DATE_FORMAT,
                DateUtilityJob.LS_DATE_FORMAT_LENGTH));
        state.setCsutldtcResult(null);

        DateValidationResult result =
                dateUtilityJob.validateDate(state.csutldtcDate(), state.csutldtcDateFormat());
        state.setCsutldtcResult(result);

        // L397 IF CSUTLDTC-RESULT-SEV-CD = '0000' CONTINUE.
        if (CSUTLDTC_SEVERITY_OK.equals(result.severityCode())) {
            return true;
        }
        // L400 IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513' - so 2513 is accepted, everything else rejects.
        return CSUTLDTC_TOLERATED_MESSAGE_NUMBER.equals(result.messageNumber());
    }


    // =================================================================================================
    // ADD-TRANSACTION - app/cbl/COTRN02C.cbl:442-466.
    // =================================================================================================

    /**
     * {@code ADD-TRANSACTION} - lines 442 to 466: generate the next identifier, build the record and
     * write it.
     *
     * <p><strong>How the identifier is generated.</strong> L444-449 moves {@code HIGH-VALUES} into
     * {@code TRAN-ID}, starts a browse there, reads backwards once, ends the browse, reads the key it
     * landed on into a {@code PIC 9(16)} carrier and adds one. So the new identifier is the highest
     * existing key plus one - and on an empty master the {@code ENDFILE} arm at L688-689 has already
     * moved zeros into {@code TRAN-ID}, which makes the first identifier ever issued {@code 1}. There is
     * no gap-filling and no reuse of a deleted key: this is a high-water mark.
     *
     * <p><strong>Why {@code INITIALIZE} comes after the browse.</strong> L450 initialises the record area
     * <em>after</em> L448 has read {@code TRAN-ID} out of it, which is the only ordering that works -
     * initialising first would zero the key the browse just returned. {@code INITIALIZE} sets numeric
     * items to zero and alphanumeric items to spaces and leaves {@code FILLER} alone; here the record
     * area is replaced with a fresh one whose twenty-byte trailing {@code FILLER} is spaces, so the
     * written image is 350 bytes with nothing undefined in it (gates G19, G21).
     *
     * <p><strong>The thirteen moves are in the source's order and every cross-width one is declared.</strong>
     * Five receivers are wider than their senders - {@code TDESCI X(60)} into {@code TRAN-DESC X(100)},
     * {@code MNAMEI X(30)} into {@code X(50)}, {@code MCITYI X(25)} into {@code X(50)}, and both dates
     * {@code X(10)} into the {@code X(26)} timestamps - and all five are left justified and padded on the
     * right with spaces, which is the {@code PIC X} rule and is applied by
     * {@link TranRecord}'s own {@code move...} methods rather than by a bare Java assignment.
     *
     * <p><strong>The amount is converted a second time.</strong> L456 calls {@code FUNCTION NUMVAL-C}
     * over {@code TRNAMTI} again, and by now that field holds the <em>edited</em> form L386 wrote back -
     * so the conversion runs over {@code +00000012.34} rather than over whatever the operator typed. The
     * mask emits a sign the intrinsic accepts and grouping the intrinsic tolerates, so the round trip is
     * value-preserving up to the eight-digit width of the mask. Where it is not - an amount at or above
     * one hundred million - the record receives the truncated value, because the truncation happened on
     * the screen at L385 and this conversion faithfully reads what is there.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void addTransaction(ProgramState state) {
        requireState(state);

        state.moveHighValuesToTranId();                                                    // L444

        // L445-L447 The browse spans three statements, and either of the two rejecting arms between them
        // ends the task before L447's ENDBR. Under CICS that is harmless because task termination
        // releases the browse implicitly; there is no such implicit release here, so the request boundary
        // performs it. releaseBrowse is a no-op when L447 already ran.
        try {
            startbrTransactFile(state);                                                    // L445
            if (state.taskEnded()) {
                return;
            }
            readprevTransactFile(state);                                                   // L446
            if (state.taskEnded()) {
                return;
            }
            endbrTransactFile(state);                                                      // L447
        } finally {
            releaseBrowse(state);
        }

        state.setWsTranIdN(movePicXToPic9(state.tranRecord().tranId(),
                WS_TRAN_ID_N_DIGITS));                                                     // L448
        state.setWsTranIdN(addOneToPic9(state.wsTranIdN(), WS_TRAN_ID_N_DIGITS));           // L449

        state.initializeTranRecord();                                                      // L450

        TranRecord record = state.tranRecord();
        record.moveTranId(codec.movePic9(state.wsTranIdN(), WS_TRAN_ID_N_DIGITS));          // L451
        record.moveTranTypeCd(state.ttypcdI());                                             // L452
        record.moveTranCatCd(movePicXToPic9Image(state.tcatcdI(),
                TranRecord.TRAN_CAT_CD_LENGTH));                                            // L453
        record.moveTranSource(state.trnsrcI());                                              // L454
        record.moveTranDesc(state.tdescI());                                                 // L455
        state.setWsTranAmtN(CobolDecimal.storeAtPicture(numvalC(state.trnamtI()),
                WS_TRAN_AMT_N_INTEGER_DIGITS, MONETARY_SCALE));                              // L456-457
        record.moveTranAmt(state.wsTranAmtN());                                              // L458
        record.moveTranCardNum(state.cardninI());                                            // L459
        record.moveTranMerchantId(movePicXToPic9Image(state.midI(),
                TranRecord.TRAN_MERCHANT_ID_LENGTH));                                        // L460
        record.moveTranMerchantName(state.mnameI());                                          // L461
        record.moveTranMerchantCity(state.mcityI());                                          // L462
        record.moveTranMerchantZip(state.mzipI());                                            // L463
        record.moveTranOrigTs(state.torigdtI());                                              // L464
        record.moveTranProcTs(state.tprocdtI());                                              // L465

        writeTransactFile(state);                                                             // L466
    }

    // =================================================================================================
    // COPY-LAST-TRAN-DATA - app/cbl/COTRN02C.cbl:471-495: the PF5 convenience.
    // =================================================================================================

    /**
     * {@code COPY-LAST-TRAN-DATA} - lines 471 to 495: fill the form from the most recent transaction, then
     * process it as though the operator had pressed Enter.
     *
     * <p>It re-validates the key fields first, because the operator may have changed the account or card
     * since the last send, and it then repeats the same backward browse {@code ADD-TRANSACTION} uses.
     * Eleven fields come back off the record; the amount comes back through the eight-digit edit mask, so
     * an amount at or above one hundred million is re-displayed without its leading digit exactly as it
     * would be after a fresh entry.
     *
     * <p><strong>The trailing {@code PERFORM PROCESS-ENTER-KEY} at L495 is what makes this a
     * "copy and submit" rather than a "copy".</strong> {@code CONFIRMI} is untouched by this paragraph,
     * so unless the operator had already typed {@code Y} the enter processing rejects with
     * {@value #MSG_CONFIRM_TO_ADD} - the copied values are on the screen and the operator is asked to
     * confirm. That is the source's flow and it is preserved.
     *
     * <p><strong>{@code IF NOT ERR-FLG-ON} at L480 is always true when this paragraph reaches it.</strong>
     * The key validation and all three browse paragraphs return to CICS on every path that raises the
     * flag, so an error can never survive to that test. The test is the source's own, it is reproduced,
     * and it is reachable from a test that sets the flag directly.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void copyLastTranData(ProgramState state) {
        requireState(state);

        validateInputKeyFields(state);                                                        // L473
        if (state.taskEnded()) {
            return;
        }

        state.moveHighValuesToTranId();                                                       // L475

        // L476-L478 The same three-statement browse as ADD-TRANSACTION, with the same two rejecting arms
        // between them. See that paragraph for why the request boundary releases it.
        try {
            startbrTransactFile(state);                                                       // L476
            if (state.taskEnded()) {
                return;
            }
            readprevTransactFile(state);                                                      // L477
            if (state.taskEnded()) {
                return;
            }
            endbrTransactFile(state);                                                         // L478
        } finally {
            releaseBrowse(state);
        }

        // L480 IF NOT ERR-FLG-ON.
        if (!state.errFlagOn()) {
            TranRecord record = state.tranRecord();
            state.setWsTranAmtE(wsTranAmtEdited(record.tranAmt()));                           // L481
            state.setTtypcdI(record.tranTypeCd());                                            // L482
            state.setTcatcdI(record.tranCatCdImage());                                        // L483
            state.setTrnsrcI(record.tranSource());                                            // L484
            state.setTrnamtI(state.wsTranAmtE());                                             // L485
            state.setTdescI(record.tranDesc());                                               // L486
            state.setTorigdtI(record.tranOrigTs());                                           // L487
            state.setTprocdtI(record.tranProcTs());                                           // L488
            state.setMidI(record.tranMerchantIdImage());                                      // L489
            state.setMnameI(record.tranMerchantName());                                        // L490
            state.setMcityI(record.tranMerchantCity());                                        // L491
            state.setMzipI(record.tranMerchantZip());                                          // L492
        }

        processEnterKey(state);                                                                // L495
    }

    // =================================================================================================
    // RETURN-TO-PREV-SCREEN - app/cbl/COTRN02C.cbl:500-511.
    // =================================================================================================

    /**
     * {@code RETURN-TO-PREV-SCREEN} - lines 500 to 511: hand control to another program.
     *
     * <p>{@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)} at L508-511 is a
     * transfer, not a return, and it is <strong>terminal</strong>. In a stateless REST projection there
     * is no server-side forward: the response names the program, the mapset and the map the client should
     * call next and the client makes that call, which is what keeps this screen free of session affinity
     * (gate G40, rule R6).
     *
     * <p>The three communication-area stores before the transfer are the audit trail the target program
     * reads: who called it, from which transaction, and - by zeroing {@code CDEMO-PGM-CONTEXT} - that the
     * target is being entered for the first time rather than re-entered.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void returnToPrevScreen(ProgramState state) {
        requireState(state);

        // L502 IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES.
        if (isSpacesOrLowValues(state.commarea().toProgram())) {
            state.setCommarea(state.commarea().withToProgram(SIGN_ON_PROGRAM));               // L503
        }
        state.setCommarea(state.commarea()
                .withFromTranid(TRANSACTION_ID)                                              // L505
                .withFromProgram(PROGRAM_NAME)                                                // L506
                .withPgmEnter());                                                             // L507

        // L508-L511 EXEC CICS XCTL.
        state.response().setNavigationContext(state.commarea());
        state.response().setCt02Info(state.ct02Info());
        state.response().setNextProgram(state.commarea().toProgram());

        // The mapset and map are blanked, and that is the whole of the correction here. An XCTL hands
        // control to another program, and which map that program will paint is its decision, made after
        // this one has ended: COTRN02C names no map in the XCTL at L508-511, and the CDEMO-LAST-MAPSET
        // and CDEMO-LAST-MAP items it passes are the caller's, not the target's. Leaving this response's
        // own COTRN02 / COTRN2A defaults standing would tell the client to paint the screen it is
        // leaving, which is the one screen that is certainly wrong. Blank means "not stated here" -
        // the client follows nextProgram, and the target's own reply names its map.
        state.response().setNextMapset(spaces(NavigationContext.LAST_MAPSET_LENGTH));
        state.response().setNextMap(spaces(NavigationContext.LAST_MAP_LENGTH));
        state.markTransferred();
    }

    // =================================================================================================
    // SEND-TRNADD-SCREEN and RECEIVE-TRNADD-SCREEN - app/cbl/COTRN02C.cbl:516-547.
    // =================================================================================================

    /**
     * {@code SEND-TRNADD-SCREEN} - lines 516 to 534: paint the screen and return to CICS.
     *
     * <p><strong>This method is terminal.</strong> The paragraph ends with {@code EXEC CICS RETURN} at
     * L530-534, so control never comes back to the statement after the {@code PERFORM}. Every caller here
     * is written accordingly, and {@link ProgramState#taskEnded()} is how a caller that must not continue
     * finds out.
     *
     * <p>{@code MOVE WS-MESSAGE TO ERRMSGO} at L520 moves eighty characters into a seventy-eight
     * character receiver, so <strong>the last two characters of the message are discarded</strong>. That
     * is the {@code PIC X} rule and it is not a defect to be fixed: the longest literal this program can
     * produce is the success notice at sixty-six characters, so the truncation is never observable in
     * practice - and it is applied anyway, by {@link TransactionViewResponse#setErrmsgo(String)}, because
     * a message that did overflow must lose its tail rather than its head.
     *
     * <p>{@code ERASE} and {@code CURSOR} are both specified. {@code CURSOR} is what gives the
     * {@code MOVE -1 TO xxxL} sites their meaning: the field whose length item is negative is where the
     * cursor lands, and that is reported on {@link ProgramState#cursorRequestedOn(ScreenField)} rather
     * than in the payload, because {@code xxxL} is metadata (gate G9).
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void sendTrnaddScreen(ProgramState state) {
        requireState(state);

        populateHeaderInfo(state);                                                            // L518
        state.response().setErrmsgo(state.message());                                          // L520

        // L522-L528 EXEC CICS SEND MAP('COTRN2A') MAPSET('COTRN02') FROM(COTRN2AO) ERASE CURSOR.
        state.response().setNextMapset(TransactionViewResponse.MAPSET_NAME);
        state.response().setNextMap(TransactionViewResponse.MAP_NAME);
        state.recordScreenSent();

        returnToCics(state);                                                                   // L530-534
    }

    /**
     * {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} - lines 530 to 534, and the
     * byte-identical statement the source also writes at lines 156 to 159.
     *
     * <p>{@code TRANSID('CT02')} routes the operator's next input back to this same transaction, and
     * {@code app/csd/CARDDEMO.CSD:439-440} binds {@code CT02} to {@code COTRN02C} - so the response's next
     * program is this program, not a different one. The communication area travels back in the payload
     * with {@code CDEMO-PGM-CONTEXT} already set to re-enter, which is how the next call knows to read the
     * map rather than paint it. Both halves of the 218-byte area go: the 160-byte
     * {@code CARDDEMO-COMMAREA} and the 58-byte {@code CDEMO-CT02-INFO} extension.
     *
     * <p>The statement at L156 is unreachable because every arm above it has already returned or
     * transferred; it is preserved as this one method rather than deleted, which is the honest rendering
     * of two identical statements one of which cannot run.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void returnToCics(ProgramState state) {
        requireState(state);

        state.response().setNavigationContext(state.commarea());
        state.response().setCt02Info(state.ct02Info());
        state.response().setNextProgram(PROGRAM_NAME);
        state.markReturned();
    }

    /**
     * {@code RECEIVE-TRNADD-SCREEN} - lines 539 to 547.
     *
     * <p>{@code EXEC CICS RECEIVE MAP('COTRN2A') MAPSET('COTRN02') INTO(COTRN2AI)} fills the symbolic map
     * from the inbound datastream. All twenty-one fields carry {@code FSET} in
     * {@code app/bms/COTRN02.bms}, so CICS returns all twenty-one whether or not the operator touched
     * them, and all twenty-one are copied here.
     *
     * <p>They are copied into the <em>response</em> because {@code 01 COTRN2AO REDEFINES COTRN2AI}: the
     * two views are one buffer, which is what makes the four normalise-in-place writes visible on the next
     * screen sent. The values arrive already at their declared widths -
     * {@link TransactionViewRequest#payloadImages()} applies the {@code PIC X} rule and renders an absent
     * member as low-values, which is what CICS leaves in a field it did not transmit.
     *
     * <p>The command captures {@code RESP} and {@code RESP2} and the program never tests either, so no
     * branch is invented here; the two codes are recorded as a successful receive, because the payload has
     * already arrived.
     *
     * @param state   the per-request working storage; must not be {@code null}
     * @param request the inbound screen; must not be {@code null}
     * @throws NullPointerException  if either argument is {@code null}
     * @throws IllegalStateException if the request and response projections of the symbolic map disagree
     *                               about how many fields it has
     */
    public void receiveTrnaddScreen(ProgramState state, TransactionViewRequest request) {
        requireState(state);
        Objects.requireNonNull(request, "A request is required: it carries the received map");

        Map<String, String> received = request.payloadImages();
        requireMatchingProjections(received.size(), ScreenField.values().length);
        for (TransactionViewRequest.ScreenField field : TransactionViewRequest.ScreenField.values()) {
            state.setPayload(ScreenField.ofLabel(field.label()), received.get(field.inputItem()));
        }

        state.setRespCd(FileStatus.NORMAL);
        state.setReasCd(FileStatus.NO_REASON_CODE);
    }

    /**
     * Verifies that the request and response projections of {@code app/cpy-bms/COTRN02.CPY} agree about
     * how many fields the symbolic map has, before the received values are copied across by name.
     *
     * <p>The copybook declares twenty-one {@code xxxI} items and twenty-one {@code xxxO} items, so the two
     * counts are the same number twice. If they ever differ, one projection has drifted and the copy would
     * silently leave a field unset rather than fail. Extracted so the rejecting path is reachable from a
     * test.
     *
     * @param requestFieldCount  how many field images the request projects
     * @param responseFieldCount how many payload fields the response projects
     * @throws IllegalStateException if the two counts differ
     */
    public static void requireMatchingProjections(int requestFieldCount, int responseFieldCount) {
        if (requestFieldCount != responseFieldCount) {
            throw new IllegalStateException("The request projects " + requestFieldCount + " field(s) of "
                    + "the symbolic map and the response projects " + responseFieldCount + "; both are "
                    + "app/cpy-bms/COTRN02.CPY, which declares " + TransactionViewResponse.FIELD_COUNT
                    + ", so one projection has drifted");
        }
    }

    // =================================================================================================
    // POPULATE-HEADER-INFO - app/cbl/COTRN02C.cbl:552-571.
    // =================================================================================================

    /**
     * {@code POPULATE-HEADER-INFO} - lines 552 to 571: the six header fields.
     *
     * <p>{@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at L554 is read once per send, from the
     * injected {@link Clock}, and the six moves at L556-571 are applied by
     * {@link TransactionViewResponse#populateHeaderInfo(DateHeader)} against the copybooks' own literals:
     * the two titles from {@code COTTL01Y}, this transaction's identifier, this program's name, the date
     * as {@code mm/dd/yy} and the time as {@code hh:mm:ss}.
     *
     * <p>The date is assembled from three separate moves at L561-563 and only then moved to the screen at
     * L565, and the year contribution is {@code WS-CURDATE-YEAR(3:2)} - the last two digits of a
     * four-digit year. So the header is a two-digit year by construction, not by accident.
     *
     * <p>Note that {@link ScreenTitles#CCDA_THANK_YOU} is <strong>not</strong>
     * {@link SystemMessages#CCDA_MSG_THANK_YOU}: the first is {@code PIC X(40)} in {@code COTTL01Y} and
     * the second is {@code PIC X(50)} in {@code CSMSG01Y}. This program uses neither - it is noted here
     * because confusing the two is an easy way to produce a message of the wrong width, and because the
     * two title literals this paragraph <em>does</em> use come from the same copybook as the first.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void populateHeaderInfo(ProgramState state) {
        requireState(state);

        state.setDateHeader(DateHeader.from(codec, clock));                                    // L554
        state.response().populateHeaderInfo(state.dateHeader());                                // L556-571
    }


    // =================================================================================================
    // READ-CXACAIX-FILE and READ-CCXREF-FILE - app/cbl/COTRN02C.cbl:576-637.
    //
    // TWO ACCESS PATHS, ONE DATASET (gate G45). CXACAIX is the alternate-index path over the
    // cross-reference cluster and is keyed by the eleven-digit account id; CCXREF is the base cluster and
    // is keyed by the sixteen-character card number. Neither is a separate table, and no schema exists to
    // make them one.
    // =================================================================================================

    /**
     * {@code READ-CXACAIX-FILE} - lines 576 to 604: find the card that belongs to an account.
     *
     * <p>{@code EXEC CICS READ DATASET(WS-CXACAIX-FILE) INTO(CARD-XREF-RECORD) RIDFLD(XREF-ACCT-ID)
     * KEYLENGTH(LENGTH OF XREF-ACCT-ID)} - so the key is the eleven digits the caller has just normalised
     * into {@code XREF-ACCT-ID}, and the whole fifty-byte cross-reference record comes back.
     *
     * <p>Three outcomes, in the source's order with {@code WHEN OTHER} last (gate G30): a record continues,
     * {@code NOTFND} rejects with {@value #MSG_ACCOUNT_ID_NOT_FOUND}, and anything else displays the
     * {@code RESP}/{@code RESP2} pair and rejects with {@value #MSG_XREF_AIX_LOOKUP_FAILED}. The
     * repository reports one further outcome the CICS command cannot - end of file - and it is folded into
     * the {@code WHEN OTHER} arm, which is where every non-{@code NORMAL}, non-{@code NOTFND} condition
     * belongs (gate G47).
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void readCxacaixFile(ProgramState state) {
        requireState(state);

        CardXrefRepository.ReadResult result =
                cardXrefRepository.readByAccountIdViaAltIndex(state.xrefAcctId());           // L578-586
        state.setRespCd(result.cicsResp());
        state.setReasCd(result.cicsResp2());

        // L588-L604 EVALUATE WS-RESP-CD.
        if (result.isFound()) {                                                              // WHEN NORMAL
            state.setCardXrefRecord(result.record().orElseThrow(missingRecord(result.ddName())));
            return;                                                                          // L590 CONTINUE
        }
        if (result.isNotFound()) {                                                           // WHEN NOTFND
            rejectAndSend(state, MSG_ACCOUNT_ID_NOT_FOUND, ScreenField.ACTIDIN);             // L592-596
            return;
        }
        // L597 WHEN OTHER.
        displayRespAndReas(state);                                                            // L598
        rejectAndSend(state, MSG_XREF_AIX_LOOKUP_FAILED, ScreenField.ACTIDIN);                // L599-603
    }

    /**
     * {@code READ-CCXREF-FILE} - lines 609 to 637: find the account that belongs to a card.
     *
     * <p>{@code EXEC CICS READ DATASET(WS-CCXREF-FILE) INTO(CARD-XREF-RECORD) RIDFLD(XREF-CARD-NUM)
     * KEYLENGTH(LENGTH OF XREF-CARD-NUM)} - the base cluster and its sixteen-character primary key, which
     * is the same dataset the paragraph above reaches by its alternate index.
     *
     * <p>The three arms mirror that paragraph's exactly, with the messages and the cursor field changed:
     * {@value #MSG_CARD_NUMBER_NOT_FOUND} and {@value #MSG_XREF_LOOKUP_FAILED}, both placing the cursor on
     * the card-number field rather than the account field.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void readCcxrefFile(ProgramState state) {
        requireState(state);

        CardXrefRepository.ReadResult result =
                cardXrefRepository.readByCardNumber(state.xrefCardNum());                     // L611-619
        state.setRespCd(result.cicsResp());
        state.setReasCd(result.cicsResp2());

        // L621-L637 EVALUATE WS-RESP-CD.
        if (result.isFound()) {                                                               // WHEN NORMAL
            state.setCardXrefRecord(result.record().orElseThrow(missingRecord(result.ddName())));
            return;                                                                           // L623 CONTINUE
        }
        if (result.isNotFound()) {                                                            // WHEN NOTFND
            rejectAndSend(state, MSG_CARD_NUMBER_NOT_FOUND, ScreenField.CARDNIN);             // L625-629
            return;
        }
        // L630 WHEN OTHER.
        displayRespAndReas(state);                                                             // L631
        rejectAndSend(state, MSG_XREF_LOOKUP_FAILED, ScreenField.CARDNIN);                     // L632-636
    }

    // =================================================================================================
    // The TRANSACT browse - app/cbl/COTRN02C.cbl:642-706. Three paragraphs, always performed as a
    // sequence, and their only purpose is to obtain the highest existing TRAN-ID.
    // =================================================================================================

    /**
     * {@code STARTBR-TRANSACT-FILE} - lines 642 to 668: position the browse at the end of the master.
     *
     * <p>{@code EXEC CICS STARTBR DATASET(WS-TRANSACT-FILE) RIDFLD(TRAN-ID)
     * KEYLENGTH(LENGTH OF TRAN-ID)} with {@code TRAN-ID} holding {@code HIGH-VALUES} from the caller's
     * L444 or L475. {@code HIGH-VALUES} is {@code X'FF'} repeated, which is not a character every code page
     * can represent - {@code US-ASCII} has no mapping for it - so the repository expresses the intent
     * rather than the byte: {@link TransactionRepository.BrowseDirection#BACKWARD} positions after the last
     * record so the first {@code READPREV} returns it, in any code page. That equivalence is documented on
     * the repository against this very program.
     *
     * <p>Three outcomes in source order. A successful position continues; {@code NOTFND} rejects with
     * {@value #MSG_TRANSACTION_ID_NOT_FOUND}; anything else displays the {@code RESP}/{@code RESP2} pair
     * and rejects with {@value #MSG_TRANSACTION_LOOKUP_FAILED}. Both rejections place the cursor on
     * {@code ACTIDINL} - the <em>account</em> field, not a transaction field - because the map has no
     * transaction field to place it on.
     *
     * <p>The repository deliberately returns no status from positioning, because the source captures
     * {@code RESP} and {@code RESP2} here and the paragraph that follows never tests them for a successful
     * position. The guard chain therefore begins at the first read, and the two rejecting arms of this
     * paragraph are unreachable through the repository - they are written out in full so that a reviewer
     * comparing the two files finds the same three arms, and they are reachable from a test that drives
     * this method's own outcome parameterisation.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void startbrTransactFile(ProgramState state) {
        requireState(state);

        state.openBrowse(transactionRepository.startBrowse(
                TransactionRepository.BrowseDirection.BACKWARD));                             // L644-650
        state.setRespCd(FileStatus.NORMAL);
        state.setReasCd(FileStatus.NO_REASON_CODE);

        // L652-L668 EVALUATE WS-RESP-CD. Positioning reports NORMAL, so this resolves to the CONTINUE arm;
        // the other two are rendered by startbrOutcome so neither literal nor cursor placement is lost.
        startbrOutcome(state, FileStatus.Outcome.OK);                                          // L653-654
    }

    /**
     * The {@code EVALUATE WS-RESP-CD} of {@code STARTBR-TRANSACT-FILE} - lines 652 to 668 - as a function
     * of the outcome, so all three arms are reachable and none of the three literals is unexercised.
     *
     * @param state   the per-request working storage; must not be {@code null}
     * @param outcome the classification of the {@code STARTBR} response
     * @throws NullPointerException if either argument is {@code null}
     */
    public void startbrOutcome(ProgramState state, FileStatus.Outcome outcome) {
        requireState(state);
        Objects.requireNonNull(outcome, "A STARTBR outcome is required");

        switch (outcome) {
            case OK -> {                                                                       // L653-654
                // CONTINUE: the browse is positioned and the caller reads from it.
            }
            case NOT_FOUND -> rejectAndSend(state, MSG_TRANSACTION_ID_NOT_FOUND,
                    ScreenField.ACTIDIN);                                                      // L655-660
            default -> {                                                                       // L661 OTHER
                displayRespAndReas(state);                                                      // L662
                rejectAndSend(state, MSG_TRANSACTION_LOOKUP_FAILED, ScreenField.ACTIDIN);       // L663-667
            }
        }
    }

    /**
     * {@code READPREV-TRANSACT-FILE} - lines 673 to 697: read the record with the highest key.
     *
     * <p>{@code EXEC CICS READPREV DATASET(WS-TRANSACT-FILE) INTO(TRAN-RECORD)
     * LENGTH(LENGTH OF TRAN-RECORD) RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID)} - so both the record and
     * the identifier field are overwritten by the read, which is exactly why the caller can read
     * {@code TRAN-ID} straight afterwards.
     *
     * <p>Three outcomes, and <strong>the middle one is the interesting one</strong>:
     * <ul>
     *   <li>{@code NORMAL} continues, with the record and its key in the record area;</li>
     *   <li>{@code ENDFILE} - an empty master - <strong>moves zeros into {@code TRAN-ID}</strong> and does
     *       <em>not</em> raise the error flag. So an empty file is a normal condition here, and the caller
     *       adds one to zero and issues identifier {@code 1}. Getting this arm wrong is the difference
     *       between an empty master being loadable and being permanently unloadable;</li>
     *   <li>anything else displays the {@code RESP}/{@code RESP2} pair and rejects with
     *       {@value #MSG_TRANSACTION_LOOKUP_FAILED}, cursor on the account field.</li>
     * </ul>
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException  if {@code state} is {@code null}
     * @throws IllegalStateException if no browse has been positioned, because the source never issues this
     *                               read without the {@code STARTBR} that precedes it at L445 and L476
     */
    public void readprevTransactFile(ProgramState state) {
        requireState(state);

        TransactionRepository.ReadResult result = state.requireBrowse().readPrev();            // L675-683
        // RESP_NOT_REPORTED, never NORMAL: an outcome that carries no CICS response is not a reported
        // DFHRESP(NORMAL), and storing zero would make the two indistinguishable on the DISPLAY at L698.
        state.setRespCd(result.cicsResp().orElse(FileStatus.RESP_NOT_REPORTED));
        state.setReasCd(result.cicsResp2());

        // L685-L697 EVALUATE WS-RESP-CD.
        if (result.isRecordReturned()) {                                                       // WHEN NORMAL
            state.setTranRecord(result.requireRecord());
            return;                                                                            // L687 CONTINUE
        }
        if (result.isEndOfFile()) {                                                            // WHEN ENDFILE
            state.tranRecord().moveTranId(TRAN_ID_ZEROS);                                       // L689
            return;
        }
        // L690 WHEN OTHER - including NOTFND, which this command can also raise.
        displayRespAndReas(state);                                                              // L691
        rejectAndSend(state, MSG_TRANSACTION_LOOKUP_FAILED, ScreenField.ACTIDIN);               // L692-696
    }

    /**
     * Releases the transaction browse on the way out of a paragraph that did not reach its {@code ENDBR}.
     *
     * <p>{@code ADD-TRANSACTION} and {@code COPY-LAST-TRAN-DATA} both position a browse at {@code L445}
     * and {@code L476} and end it at {@code L447} and {@code L478}, with two rejecting arms in between
     * that end the task first. Under CICS the unended browse costs nothing, because terminating the task
     * releases it; a request handler has no equivalent implicit release, so this supplies one.
     *
     * <p>Nothing observable changes. {@code EXEC CICS ENDBR} at {@code L704} carries no {@code RESP} and
     * the paragraph tests no outcome, so the program already inspects nothing from its own {@code ENDBR};
     * and {@link ProgramState#closeBrowse()} is null-safe and clears the handle, so a paragraph that
     * reached {@code L447} or {@code L478} finds nothing left to release. No map is sent, no message set
     * and no flag raised. Any fault raised while releasing is swallowed so it cannot displace the
     * request's own outcome.
     *
     * @param state the per-request working storage
     */
    private static void releaseBrowse(ProgramState state) {
        if (!state.browseOpen()) {
            return;
        }
        try {
            state.closeBrowse();
        } catch (RuntimeException cleanupFailure) {
            // Only the failure's TYPE is logged - never the throwable and never its message. A driver
            // composes its message around the value it refused, and a transaction row carries the card
            // number and the amount (CWE-532); a newline in that text could forge a second log entry
            // (CWE-117). A class name carries no data and no newline.
            LOG.warn("Ending the " + WS_TRANSACT_FILE + " browse of " + PROGRAM_NAME + " after a request "
                    + "that did not reach ENDBR failed - " + cleanupFailure.getClass().getName()
                    + ". The request's own outcome is unchanged, because the request's own failure is "
                    + "the one that matters.");
        }
    }

    /**
     * {@code ENDBR-TRANSACT-FILE} - lines 702 to 706: end the browse.
     *
     * <p>{@code EXEC CICS ENDBR DATASET(WS-TRANSACT-FILE)} with <strong>no {@code RESP}</strong>, so the
     * source neither captures nor tests a condition here and no branch is invented. Ending a browse that
     * has already ended does nothing, and ending one that was never positioned does nothing either, which
     * is why this method is safe to call unconditionally.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void endbrTransactFile(ProgramState state) {
        requireState(state);
        state.closeBrowse();                                                                    // L704-706
    }

    // =================================================================================================
    // WRITE-TRANSACT-FILE - app/cbl/COTRN02C.cbl:711-749.
    // =================================================================================================

    /**
     * {@code WRITE-TRANSACT-FILE} - lines 711 to 749: insert the record and report the outcome.
     *
     * <p>{@code EXEC CICS WRITE DATASET(WS-TRANSACT-FILE) FROM(TRAN-RECORD)
     * LENGTH(LENGTH OF TRAN-RECORD) RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID)} writes the full
     * <strong>350</strong> bytes, trailing twenty-byte {@code FILLER} included and space filled (gates
     * G19, G21). Omitting the {@code FILLER} would make the record 330 bytes and every consumer's offsets
     * would still be right - which is exactly why the width, not the field list, is what has to be
     * asserted.
     *
     * <p>Three outcomes, in the source's order:
     * <ul>
     *   <li><strong>{@code NORMAL}</strong> - blank the whole form so the next entry starts clean, set the
     *       error line's colour to {@link BmsAttributes#DFHGREEN} because this one is good news rather
     *       than an error, and compose the notice with {@code STRING}. The identifier is contributed
     *       {@code DELIMITED BY SPACE}, so it stops at the first space; a zero-filled sixteen-digit key
     *       contains none, and all sixteen digits appear. Note that the two literals put
     *       <strong>two</strong> spaces between the sentence and the phrase, because the first ends with
     *       one and the second begins with one.</li>
     *   <li><strong>{@code DUPKEY} or {@code DUPREC}</strong> - one shared arm, because L735 and L736 are
     *       two {@code WHEN} clauses over a single action. Rejects with
     *       {@value #MSG_TRAN_ID_ALREADY_EXISTS}. It is reachable in principle: the identifier is a
     *       high-water mark taken without a lock, so two operators adding at once can both compute the
     *       same next key.</li>
     *   <li><strong>anything else</strong> - display the {@code RESP}/{@code RESP2} pair and reject with
     *       {@value #MSG_UNABLE_TO_ADD}. A backend refusal surfaces here as this arm rather than as an
     *       escaping exception, which is what keeps the operator's screen intact (gate G47).</li>
     * </ul>
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void writeTransactFile(ProgramState state) {
        requireState(state);

        TransactionRepository.WriteResult result =
                transactionRepository.write(state.tranRecord());                                // L713-721
        state.recordWritten(state.tranRecord());
        // RESP_NOT_REPORTED, never NORMAL - see readprevTransactFile. A write that reported no CICS
        // response reaches WHEN OTHER, and its DISPLAY must not render RESP: 000000000.
        state.setRespCd(result.cicsResp().orElse(FileStatus.RESP_NOT_REPORTED));
        state.setReasCd(result.cicsResp2());

        // L723-L749 EVALUATE WS-RESP-CD.
        if (result.isWritten()) {                                                               // WHEN NORMAL
            initializeAllFields(state);                                                          // L725
            state.setMessage(spaces(WS_MESSAGE_LENGTH));                                          // L726
            state.response().getMetadata(ScreenField.ERRMSG).setColour(BmsAttributes.DFHGREEN);   // L727
            state.setMessage(stringInto(state.message(), codec.concatenateDelimitedBySize(
                    MSG_ADDED_SUCCESSFULLY,
                    MSG_YOUR_TRAN_ID_IS,
                    stringDelimitedBySpace(state.tranRecord().tranId()),
                    MSG_FULL_STOP)));                                                             // L728-733
            sendTrnaddScreen(state);                                                              // L734
            return;
        }
        if (result.isDuplicate()) {                                                  // WHEN DUPKEY / DUPREC
            rejectAndSend(state, MSG_TRAN_ID_ALREADY_EXISTS, ScreenField.ACTIDIN);               // L737-741
            return;
        }
        // L742 WHEN OTHER.
        displayRespAndReas(state);                                                                // L743
        rejectAndSend(state, MSG_UNABLE_TO_ADD, ScreenField.ACTIDIN);                             // L744-748
    }

    // =================================================================================================
    // CLEAR-CURRENT-SCREEN and INITIALIZE-ALL-FIELDS - app/cbl/COTRN02C.cbl:754-779.
    // =================================================================================================

    /**
     * {@code CLEAR-CURRENT-SCREEN} - lines 754 to 757: the PF4 action.
     *
     * <p>Two performs and nothing else: blank everything, then repaint. The repaint is terminal, so this
     * paragraph ends the task.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void clearCurrentScreen(ProgramState state) {
        requireState(state);

        initializeAllFields(state);                                                               // L756
        sendTrnaddScreen(state);                                                                  // L757
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS} - lines 762 to 779: blank the form and put the cursor back.
     *
     * <p>Fourteen screen fields and the eighty-byte message area, plus the cursor request on
     * {@code ACTIDINL}. The list is wider than the eleven-field one at L238-248: it additionally clears
     * both key fields and {@code CONFIRMI}, which is what makes it safe to run straight after a successful
     * write - a stale {@code Y} left in the confirmation field would add a second transaction on the next
     * Enter.
     *
     * <p>{@code ERRMSGI} is deliberately <strong>not</strong> in the list. The successful-write path at
     * L725-733 calls this paragraph and <em>then</em> composes its notice, so blanking the error line here
     * would be undone immediately; and the failure paths do not call it at all.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void initializeAllFields(ProgramState state) {
        requireState(state);

        state.moveMinusOneTo(ScreenField.ACTIDIN);                                                 // L764
        for (ScreenField field : ALL_INPUT_FIELDS) {
            state.setPayload(field, spaces(field.width()));                                       // L765-778
        }
        state.setMessage(spaces(WS_MESSAGE_LENGTH));                                               // L779
    }

    /**
     * The fourteen receivers of {@code INITIALIZE-ALL-FIELDS}, in the source's order at L765-778.
     *
     * <p>Immutable, so it is a constant rather than mutable static state (practice B9, gate G53).
     */
    private static final List<ScreenField> ALL_INPUT_FIELDS = List.of(
            ScreenField.ACTIDIN, ScreenField.CARDNIN, ScreenField.TTYPCD, ScreenField.TCATCD,
            ScreenField.TRNSRC, ScreenField.TRNAMT, ScreenField.TDESC, ScreenField.TORIGDT,
            ScreenField.TPROCDT, ScreenField.MID, ScreenField.MNAME, ScreenField.MCITY,
            ScreenField.MZIP, ScreenField.CONFIRM);

    // =================================================================================================
    // The shared rejection shape. Twenty of the twenty-five message sites are the same four statements in
    // the same order, so they are written once - which also means the ERR-FLG, the message, the cursor and
    // the send can never drift apart between sites.
    // =================================================================================================

    /**
     * The four statements every rejection site writes:
     * {@code MOVE 'Y' TO WS-ERR-FLG}, {@code MOVE <literal> TO WS-MESSAGE},
     * {@code MOVE -1 TO <field>L} and {@code PERFORM SEND-TRNADD-SCREEN}.
     *
     * <p><strong>Terminal</strong>, because the send is. Every caller returns immediately afterwards.
     *
     * <p>The order matters and is the source's: the flag first, then the message, then the cursor, then the
     * send - so {@code SEND-TRNADD-SCREEN} sees the finished message and the placed cursor.
     *
     * <p><strong>No field is recoloured and no asterisk is written.</strong> {@code COTRN02C} does not
     * copy {@code CSSETATY} - its copybook list at L71-93 is {@code COCOM01Y}, {@code COTRN02},
     * {@code COTTL01Y}, {@code CSDAT01Y}, {@code CSMSG01Y}, {@code CVTRA05Y}, {@code CVACT01Y},
     * {@code CVACT03Y}, {@code DFHAID} and {@code DFHBMSCA}, and {@code CSSETATY} has exactly one consumer
     * in the whole estate, which is {@code COACTUPC}. Applying the {@code DFHRED}-plus-{@code '*'}
     * highlight here would invent behaviour, and worse: {@code CSSETATY} writes the asterisk into the
     * field's <em>output item</em>, which on this screen is the same storage as the {@code xxxI} item the
     * operator typed into - so the highlight would overwrite a payload value and put the field-for-field
     * parity diff permanently off zero. {@code common.FieldAttributeSetter} is therefore deliberately not
     * referenced by this class, exactly as it is deliberately not referenced by {@code CORPT00C}'s
     * controller for the same reason.
     *
     * <p>Gate G38 - "the {@code CSSETATY} error highlight applies only in the re-enter state" - is
     * satisfied here in the strongest possible way: this program applies it in <em>no</em> state, because
     * it does not have it. The program's only attribute assignment anywhere is
     * {@code MOVE DFHGREEN TO ERRMSGC OF COTRN2AO} at L727, which is on the success path and is reproduced
     * with {@link BmsAttributes#DFHGREEN} - and that path is reachable only from the re-enter arm, so even
     * that single assignment is re-enter-only.
     *
     * @param state   the per-request working storage; must not be {@code null}
     * @param message the byte-exact literal the source moves into {@code WS-MESSAGE}; must not be
     *                {@code null}
     * @param cursor  the field whose length item receives {@code -1}; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public void rejectAndSend(ProgramState state, String message, ScreenField cursor) {
        requireState(state);
        Objects.requireNonNull(message, "A rejection carries the source's own literal");
        Objects.requireNonNull(cursor, "A rejection places the cursor on a named field");

        state.setErrFlagOn();
        state.setMessage(codec.movePicX(message, WS_MESSAGE_LENGTH));
        state.moveMinusOneTo(cursor);
        sendTrnaddScreen(state);
    }

    /**
     * {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} - the five {@code WHEN OTHER} arms at L598,
     * L631, L662, L691 and L743, which are byte-identical.
     *
     * <p>{@code WS-RESP-CD} and {@code WS-REAS-CD} are both {@code PIC S9(09) COMP}, so each renders as
     * nine digits. Emitted to the log so an operator can correlate the screen's message with the region's
     * output, and recorded on the state so a parity case can assert the line without reading a log file.
     *
     * <p>Only fixed text and two integers reach the log, so there is nothing here a caller could use to
     * forge a log record or to leak a payload value; and the call is single-argument, so no throwable's
     * message or cause chain is emitted.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void displayRespAndReas(ProgramState state) {
        requireState(state);

        String line = DISPLAY_RESP_PREFIX + respImage(state.respCd())
                + DISPLAY_REAS_PREFIX + respImage(state.reasCd());
        state.recordDisplay(line);
        LOG.info(line);
    }

    /**
     * A {@code PIC S9(09) COMP} response or reason code as {@code DISPLAY} renders it - or, where none
     * was reported, as {@link FileStatus#RESP_NOT_REPORTED}'s width-preserving image.
     *
     * <p>Both branches are exactly {@value #WS_RESP_CD_DIGITS} characters, so the composed line keeps the
     * shape {@code DISPLAY} gives it: its operands are concatenated at their declared widths, and a
     * substitute of any other length would shift every character after it.
     *
     * <p>The unreported case cannot go through {@link FixedWidthCodec#movePic9(long, int)}:
     * {@link FileStatus#RESP_NOT_REPORTED} is negative and a {@code PIC 9} receiver has no image for a
     * negative value, so the codec refuses it. Storing zero instead would be worse than refusing -
     * zero <em>is</em> {@link FileStatus#NORMAL}, so a command that reported nothing would be rendered
     * as one that succeeded.
     *
     * @param code the value held in {@code WS-RESP-CD} or {@code WS-REAS-CD}
     * @return exactly {@value #WS_RESP_CD_DIGITS} characters
     */
    private String respImage(int code) {
        return FileStatus.respReported(code)
                ? codec.movePic9(code, WS_RESP_CD_DIGITS)
                : FileStatus.respNotReportedImage(WS_RESP_CD_DIGITS);
    }


    // =================================================================================================
    // COBOL class tests and figurative constants.
    // =================================================================================================

    /**
     * {@code SPACES} for a receiver of the given width.
     *
     * @param length the receiver's declared width; must not be negative
     * @return a string of exactly {@code length} spaces
     * @throws IllegalArgumentException if {@code length} is negative
     */
    public static String spaces(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("A field cannot be " + length + " characters wide");
        }
        return SPACE.repeat(length);
    }

    /**
     * The combined relation {@code = SPACES OR LOW-VALUES}, and its negation
     * {@code NOT = SPACES AND LOW-VALUES}, which the source writes at L124, L137, L196, L210 and in eleven
     * arms of the empty cascade.
     *
     * <p>{@code LOW-VALUES} is {@code X'00'} repeated, and it is what CICS leaves in a symbolic-map item
     * for a field it did not transmit and what {@code MOVE LOW-VALUES TO COTRN2AO} at L122 writes into all
     * of them. So a screen field is "empty" when it holds only spaces, only nulls, or a mixture -
     * which is what this predicate reports.
     *
     * <p>An empty string counts as empty, and so does {@code null}: a JSON member the client omitted
     * altogether is a field CICS did not transmit, and is treated as such rather than rejected. That
     * matters because the program's own tests are all "empty or not", so a rejection here would refuse a
     * screen the COBOL accepts.
     *
     * @param image the field value, possibly {@code null}
     * @return {@code true} when every character is a space or a null, including when there are none
     */
    public static boolean isSpacesOrLowValues(String image) {
        if (image == null) {
            return true;
        }
        for (int index = 0; index < image.length(); index++) {
            char character = image.charAt(index);
            if (character != ' ' && character != '\u0000') {
                return false;
            }
        }
        return true;
    }

    /**
     * The COBOL {@code NUMERIC} class test for an alphanumeric item, as used at L197, L211, L323, L329,
     * L341, L343, L354, L356, L358, L369, L371, L373 and L430.
     *
     * <p>For a {@code PIC X} item the test is true when <strong>every</strong> character is a digit. An
     * empty item is not numeric, spaces are not numeric, nulls are not numeric, and neither a sign nor a
     * decimal point is a digit - so {@code "+0000001234"} fails the test and {@code "0000001234"} passes
     * it. That is why {@code ACTIDINI} must be typed without a sign and why the amount is checked
     * positionally instead of with this test.
     *
     * @param image the field value, possibly {@code null}
     * @return {@code true} when the value is non-empty and every character is {@code '0'} to {@code '9'}
     */
    public static boolean isNumericClass(String image) {
        if (image == null || image.isEmpty()) {
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
     * A reference modification {@code image(offset + 1 : length)}, tolerant of an image shorter than its
     * declared width.
     *
     * <p>COBOL cannot have a short field - the item is its declared width, always - but a JSON payload
     * can arrive with one, so the missing positions are read as spaces. Spaces fail every class test and
     * every literal comparison the source makes, so a short field is rejected rather than accepted, which
     * is the safe direction and the one a fixed-width field would produce if it were space padded.
     *
     * @param image  the field value; must not be {@code null}
     * @param offset the zero-based start of the window
     * @param length the window's width
     * @return exactly {@code length} characters
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static String window(String image, int offset, int length) {
        Objects.requireNonNull(image, "A reference modification needs an item to read from");
        StringBuilder window = new StringBuilder(length);
        for (int index = offset; index < offset + length; index++) {
            window.append(charAtOrSpace(image, index));
        }
        return window.toString();
    }

    /**
     * One character of a reference modification, reading a space beyond the end of a short image, for the
     * reason given on {@link #window(String, int, int)}.
     *
     * @param image  the field value; must not be {@code null}
     * @param offset the zero-based position
     * @return the character at that position, or a space when the image does not reach it
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static char charAtOrSpace(String image, int offset) {
        Objects.requireNonNull(image, "A reference modification needs an item to read from");
        if (offset < 0 || offset >= image.length()) {
            return ' ';
        }
        return image.charAt(offset);
    }

    // =================================================================================================
    // FUNCTION NUMVAL and FUNCTION NUMVAL-C - the four intrinsic sites at L204, L218, L383 and L456
    // (gate G29). Both are implemented as one scan that yields the value and the conformance verdict
    // together, so a caller cannot read one without the other having come from the same pass.
    // =================================================================================================

    /**
     * {@code FUNCTION NUMVAL} - lines 204 and 218.
     *
     * <p>Returns the numeric value of a character representation that may carry a sign and a decimal
     * point, as a {@link BigDecimal} so every digit survives - never as {@code double} or
     * {@code float}, which cannot represent a decimal fraction exactly (gate G22). The argument format
     * is the one the intrinsic documents, with spaces permitted between the elements:
     *
     * <pre>{@code [+|-] digits[.[digits]] [+|-|CR|DB]}</pre>
     *
     * <p>It differs from {@link #numvalC(String)} in <strong>two</strong> ways, not one: {@code NUMVAL}
     * accepts neither a currency sign nor a digit-grouping comma. Both are {@code NUMVAL-C}
     * extensions, which is why {@code "$12"} and {@code "1,234"} convert under {@code NUMVAL-C} and
     * neither conforms under {@code NUMVAL}. {@code CR} and {@code DB}, by contrast, belong to
     * <em>both</em> intrinsics.
     *
     * <p><strong>An argument that does not conform yields zero.</strong> The policy, and the reason it
     * is safe, are stated once in {@link NumericIntrinsics} rather than restated here. It changes
     * nothing this screen accepts: both call sites are guarded by an {@code IS NOT NUMERIC} test that
     * has already rejected every non-conforming value - a comma is not numeric, so the guard errors
     * first - so the fallback is defensive rather than reachable through the composed flow. Use
     * {@link #testNumval(String)} when the verdict itself is what matters.
     *
     * @param image the argument to convert; must not be {@code null}
     * @return the value the argument denotes, or zero when it does not conform
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static BigDecimal numval(String image) {
        return NumericIntrinsics.numval(image);
    }

    /**
     * The conformance half of {@link #numval(String)}, in the shape {@code FUNCTION TEST-NUMVAL} reports
     * it.
     *
     * <p>{@code COTRN02C} does not call {@code TEST-NUMVAL} - it converts unconditionally at both sites -
     * so this method exists to make the conversion's own accept-and-reject behaviour assertable rather
     * than only inferable from a converted value. Gate G29 requires the conversion to accept and reject
     * exactly as COBOL does, and a value of zero alone cannot distinguish {@code "00"} from {@code "ab"}.
     *
     * @param image the argument to test; must not be {@code null}
     * @return {@value #NUMVAL_CONFORMS} when the argument conforms; otherwise the one-based position of the
     *         first character in error, or the argument's length plus one when it holds no digit at all
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static int testNumval(String image) {
        return NumericIntrinsics.testNumval(image);
    }

    /**
     * {@code FUNCTION NUMVAL-C} - lines 383 and 456.
     *
     * <p>As {@link #numval(String)}, and additionally accepting a currency sign between the leading
     * sign and the first digit, and digit-grouping commas within the integer part:
     *
     * <pre>{@code [+|-] [$] digits[,digits]... [.[digits]] [+|-|CR|DB]}</pre>
     *
     * <p>Both call sites read {@code TRNAMTI}, which the positional check at L340-343 has already forced
     * into the shape {@code [-+]dddddddd.dd} - so in the composed flow the argument always conforms and
     * always carries its sign in the leading position. The tolerance for grouping commas and a trailing
     * sign is therefore unexercised by this screen and is implemented anyway, because the intrinsic has it
     * and a parity case is entitled to drive it.
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
     * @return {@value #NUMVAL_CONFORMS} when the argument conforms; otherwise the one-based position of the
     *         first character in error, or the argument's length plus one when it holds no digit at all
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static int testNumvalC(String image) {
        return NumericIntrinsics.testNumvalC(image);
    }

    /**
     * @param character the character to classify
     * @return {@code true} when it is {@code '0'} to {@code '9'}
     */
    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    // =================================================================================================
    // Numeric MOVE and COMPUTE receivers.
    // =================================================================================================

    /**
     * {@code COMPUTE <unsigned PIC 9(n)> = <expression>} - the receivers of L204 and L218.
     *
     * <p>Two truncations apply, in this order, and neither raises a condition because neither
     * {@code ROUNDED} nor {@code ON SIZE ERROR} appears anywhere in this program - or, for
     * {@code ROUNDED}, anywhere in the twenty-eight:
     * <ol>
     *   <li>the fraction is discarded, because the receiver has no fraction digits. Truncation, never
     *       rounding, which is why {@link CobolDecimal#COBOL_ROUNDING} is
     *       {@link java.math.RoundingMode#DOWN} (gate G24);</li>
     *   <li>integer digits beyond the receiver's width are discarded, the low-order digits surviving;</li>
     *   <li>and finally the sign is dropped, because {@code PIC 9} has no sign position.</li>
     * </ol>
     *
     * @param value  the computed value; must not be {@code null}
     * @param digits the receiver's declared digit count; at least 1 and at most 18
     * @return the stored value as a non-negative {@code long}
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code digits} is outside 1 to 18
     */
    public static long computeIntoPic9(BigDecimal value, int digits) {
        Objects.requireNonNull(value, "A COMPUTE needs a value to store");
        requireDigitCount(digits);
        return CobolDecimal.storeAtPicture(value, digits, 0).abs().longValueExact();
    }

    /**
     * {@code MOVE <PIC X(n)> TO <PIC 9(m)>} - line 448, {@code MOVE TRAN-ID TO WS-TRAN-ID-N}.
     *
     * <p>COBOL treats the alphanumeric sender as an unsigned integer and aligns it on the receiver's
     * implied decimal point, so the <strong>low-order</strong> digits survive an over-wide sender and the
     * receiver is zero filled on the left for a short one - the opposite direction to a {@code PIC X}
     * receiver, which is exactly why this move goes through a named helper rather than an assignment.
     *
     * <p>A sender that is not all digits is undefined in COBOL. Zero is returned, deterministically, and
     * the case is defensive rather than reachable: the only caller reads {@code TRAN-ID} immediately after
     * {@code READPREV-TRANSACT-FILE}, which leaves either a real sixteen-digit key or the sixteen zeros of
     * its {@code ENDFILE} arm there.
     *
     * @param image  the alphanumeric sender; must not be {@code null}
     * @param digits the receiver's declared digit count; at least 1 and at most 18
     * @return the sender read as an unsigned integer, or zero when it is not all digits
     * @throws NullPointerException     if {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code digits} is outside 1 to 18
     */
    public static long movePicXToPic9(String image, int digits) {
        Objects.requireNonNull(image, "A numeric MOVE needs a sender");
        requireDigitCount(digits);
        if (!isNumericClass(image)) {
            return 0L;
        }
        String kept = image.length() > digits ? image.substring(image.length() - digits) : image;
        return Long.parseLong(kept);
    }

    /**
     * The same move as {@link #movePicXToPic9(String, int)} rendered back as a digit image, for the two
     * record fields that are {@code PIC 9} and whose senders are screen fields:
     * {@code TRAN-CAT-CD PIC 9(04)} at L453 and {@code TRAN-MERCHANT-ID PIC 9(09)} at L460.
     *
     * @param image  the alphanumeric sender; must not be {@code null}
     * @param digits the receiver's declared digit count; at least 1 and at most 18
     * @return exactly {@code digits} digit characters
     * @throws NullPointerException     if {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code digits} is outside 1 to 18
     */
    public static String movePicXToPic9Image(String image, int digits) {
        long value = movePicXToPic9(image, digits);
        StringBuilder rendered = new StringBuilder(Long.toString(value));
        while (rendered.length() < digits) {
            rendered.insert(0, '0');
        }
        return rendered.toString();
    }

    /**
     * {@code ADD 1 TO WS-TRAN-ID-N} - line 449.
     *
     * <p>{@code ON SIZE ERROR} is absent, so an add that overflows the receiver's sixteen digits keeps the
     * low-order sixteen and reports nothing: {@code 9999999999999999 + 1} becomes zero rather than
     * failing. Reproduced rather than guarded, because guarding it would make the program refuse a
     * condition it currently absorbs.
     *
     * @param value  the current value; must not be negative
     * @param digits the receiver's declared digit count; at least 1 and at most 18
     * @return the incremented value, wrapped to {@code digits} digits
     * @throws IllegalArgumentException if {@code value} is negative or {@code digits} is outside 1 to 18
     */
    public static long addOneToPic9(long value, int digits) {
        requireDigitCount(digits);
        if (value < 0) {
            throw new IllegalArgumentException("PIC 9 has no sign position, so " + value
                    + " cannot be the current value of an unsigned counter");
        }
        BigDecimal incremented = BigDecimal.valueOf(value).add(BigDecimal.valueOf(ONE));
        return CobolDecimal.storeAtPicture(incremented, digits, 0).longValueExact();
    }

    /**
     * Requires a digit count a {@code long} can hold, which bounds every numeric receiver in this program:
     * the widest is {@code PIC 9(16)}.
     *
     * @param digits the declared digit count
     * @throws IllegalArgumentException if it is below 1 or above 18
     */
    private static void requireDigitCount(int digits) {
        if (digits < 1 || digits > 18) {
            throw new IllegalArgumentException("A PIC 9 receiver of " + digits + " digit(s) is not one "
                    + "this program declares; the widest is PIC 9(16) and a long holds 18 digits");
        }
    }

    /**
     * {@code MOVE <numeric> TO WS-TRAN-AMT-E}, whose picture is {@code +99999999.99} - lines 385 and 481.
     *
     * <p>A numeric-edited receiver, so the result is a <strong>character</strong> image of exactly
     * {@value #WS_TRAN_AMT_E_LENGTH} positions: a forced sign, eight integer digits zero filled on the
     * left, the insertion character {@code '.'}, and two fraction digits. {@code +} is emitted for zero
     * and for any positive value and {@code -} only for a negative one, which is what the {@code +}
     * insertion symbol means as distinct from {@code -}.
     *
     * <p><strong>Eight integer digits, not nine.</strong> The senders are {@code WS-TRAN-AMT-N} and
     * {@code TRAN-AMT}, both {@code S9(9)V99}, so a value at or above one hundred million loses its
     * leading digit here. That is the source's own arithmetic and it is preserved
     * (see {@link #WS_TRAN_AMT_E_INTEGER_DIGITS}); the receiver is not widened to make the number look
     * right.
     *
     * <p>The image is composed from the value's own digits rather than by a formatter, so it cannot vary
     * with a default locale: no grouping separator can appear, and the decimal separator is the literal
     * {@code '.'} the picture specifies rather than whatever a locale would choose (practice B8).
     *
     * @param value the sending value; must not be {@code null}
     * @return exactly {@value #WS_TRAN_AMT_E_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public String wsTranAmtEdited(BigDecimal value) {
        Objects.requireNonNull(value, "A numeric-edited MOVE needs a sending value");

        BigDecimal stored = CobolDecimal.storeAtPicture(value, WS_TRAN_AMT_E_INTEGER_DIGITS,
                MONETARY_SCALE);
        String digits = codec.movePic9(stored.abs().unscaledValue().toString(),
                WS_TRAN_AMT_E_INTEGER_DIGITS + MONETARY_SCALE);
        char sign = stored.signum() < 0 ? MINUS_SIGN : PLUS_SIGN;
        return sign
                + digits.substring(0, WS_TRAN_AMT_E_INTEGER_DIGITS)
                + DECIMAL_POINT
                + digits.substring(WS_TRAN_AMT_E_INTEGER_DIGITS);
    }

    // =================================================================================================
    // STRING and DISPLAY support.
    // =================================================================================================

    /**
     * {@code <item> DELIMITED BY SPACE} - line 731, the identifier operand of the success notice.
     *
     * <p>Transfers characters up to but not including the first space, so a sixteen-character key that
     * holds no space contributes all sixteen characters, and one that has been space padded contributes
     * only its significant prefix. The identifier this program writes is always zero filled, so all
     * sixteen digits appear - which is why the notice reads
     * {@code "... Your Tran ID is 0000000000000051."} rather than {@code "... is 51."}
     *
     * @param sendingItem the item to transfer; must not be {@code null}
     * @return the characters before the first space, or the whole item when it holds none
     * @throws NullPointerException if {@code sendingItem} is {@code null}
     */
    public static String stringDelimitedBySpace(String sendingItem) {
        Objects.requireNonNull(sendingItem, "A STRING operand is required");
        int firstSpace = sendingItem.indexOf(' ');
        return firstSpace < 0 ? sendingItem : sendingItem.substring(0, firstSpace);
    }

    /**
     * {@code STRING ... INTO <receiver>} - lines 728 to 733.
     *
     * <p>{@code STRING} overlays the receiver from its leftmost position and <strong>leaves the rest of it
     * exactly as it was</strong>; it does not space fill the remainder and it does not have a
     * {@code POINTER} phrase here. The receiver is blanked at L726 immediately before, so the untouched
     * remainder is spaces - and that ordering is why the two statements are reproduced as two rather than
     * collapsed into one.
     *
     * <p>A composition longer than the receiver is truncated on the right, and the source specifies no
     * {@code ON OVERFLOW} phrase so nothing is reported. The longest notice this program can produce is
     * sixty-six characters into an eighty-character receiver, so the truncation is not reachable through
     * the composed flow.
     *
     * @param receiver the receiving item at its declared width; must not be {@code null}
     * @param composed the concatenated operands; must not be {@code null}
     * @return the receiver with {@code composed} overlaid from position one, at its original width
     * @throws NullPointerException if either argument is {@code null}
     */
    public static String stringInto(String receiver, String composed) {
        Objects.requireNonNull(receiver, "A STRING statement needs a receiving item");
        Objects.requireNonNull(composed, "A STRING statement needs something to move");
        if (composed.length() >= receiver.length()) {
            return composed.substring(0, receiver.length());
        }
        return composed + receiver.substring(composed.length());
    }

    /**
     * Reconstructs the {@code EIBAID} byte from the payload's mnemonic token, so the four tests of the
     * {@code EVALUATE EIBAID} at L133-152 read as the source's four tests against {@code DFHAID}
     * constants rather than as string comparisons.
     *
     * <p>Only the four keys this program acts on are recognised; every other token - and every
     * unrecognised one - maps to {@link CicsAid#DFHNULL}, which reaches the {@code WHEN OTHER} arm and the
     * invalid-key message. That is the correct fallback: an AID the program does not name is an AID it
     * treats as invalid.
     *
     * @param aidToken the mnemonic from the payload, for example {@code "ENTER"} or {@code "PFK03"};
     *                 may be {@code null}, which is an operator who pressed nothing this program knows
     * @return the corresponding {@code DFHAID} byte, or {@link CicsAid#DFHNULL}
     */
    public static byte eibAidOf(String aidToken) {
        if (aidToken == null) {
            return CicsAid.DFHNULL;
        }
        String token = aidToken.strip();
        if (PfKeyResolver.AidKey.ENTER.token().strip().equals(token)) {
            return CicsAid.DFHENTER;
        }
        if (PfKeyResolver.AidKey.PFK03.token().strip().equals(token)) {
            return CicsAid.DFHPF3;
        }
        if (PfKeyResolver.AidKey.PFK04.token().strip().equals(token)) {
            return CicsAid.DFHPF4;
        }
        if (PfKeyResolver.AidKey.PFK05.token().strip().equals(token)) {
            return CicsAid.DFHPF5;
        }
        return CicsAid.DFHNULL;
    }

    /**
     * The supplier behind {@code result.record().orElseThrow(...)} on a repository outcome that reports a
     * record and then does not carry one.
     *
     * <p>Unreachable through the repositories, whose own constructors reject that combination - it is
     * expressed so the {@code Optional} is unwrapped with a diagnosis rather than with a bare
     * {@code get()}.
     *
     * @param ddName the dataset the read named
     * @return a supplier of the failure
     */
    private static Supplier<IllegalStateException> missingRecord(String ddName) {
        return () -> new IllegalStateException("A read of " + ddName + " reported that it found a record "
                + "and then carried none; the outcome and the record disagree");
    }

    /**
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    private static void requireState(ProgramState state) {
        Objects.requireNonNull(state, "A ProgramState is required: every WORKING-STORAGE item of "
                + "COTRN02C lives in it, so that two concurrent requests cannot see each other's screen");
    }


    // =================================================================================================
    // ProgramState - the WORKING-STORAGE SECTION of app/cbl/COTRN02C.cbl:35-93, per request.
    //
    // Every item the program declares lives here, and nothing lives in a static field (practice B9, gate
    // G53). That is not a stylistic preference: COTRN02C's working storage includes the whole screen
    // buffer, the operator's account and card, the record about to be written and the error flag that
    // decides what is shown - so a static home for any of it would let two concurrent requests read each
    // other's data, and would make a test's outcome depend on which test ran before it.
    // =================================================================================================

    /**
     * The per-request working storage of {@code COTRN02C}: the screen buffer, the communication area, the
     * record area and every flag and carrier between them.
     *
     * <p>Also carries the three things that are observable behaviour but have no home in the symbolic
     * map's payload, because a parity case has to assert all three: the {@code MOVE -1 TO xxxL} cursor
     * requests (metadata, gate G9), the {@code DISPLAY} lines, and the record actually handed to the
     * master.
     *
     * <p>{@code final} and mutable-by-method rather than immutable-with-copies, because the COBOL it stands
     * for is a single storage area that statements write into. Making it immutable would force a new
     * instance per {@code MOVE} - about forty of them on the success path - and every one of those copies
     * would be a place for a field to be dropped.
     */
    public static final class ProgramState {

        /**
         * The length of the communication area this program passes: {@code CARDDEMO-COMMAREA} plus the
         * {@code 05 CDEMO-CT02-INFO} group it appends at L72-80.
         *
         * <p>160 + 58 = 218. The extension is carried in the payload rather than by widening
         * {@link NavigationContext}, because {@code COCOM01Y} is copied by all seventeen online programs
         * and only this one - together with {@code COTRN00C} and {@code COTRN01C}, which append groups of
         * their own - has anything to add to it.
         */
        public static final int PASSED_COMMAREA_LENGTH =
                NavigationContext.COMMAREA_LENGTH + TransactionViewResponse.Ct02Info.LENGTH;

        /**
         * {@code 01 COTRN2AO} - the screen buffer, and by {@code REDEFINES} also {@code 01 COTRN2AI}.
         *
         * <p>One object, because the copybook declares one storage area. Reading a field means reading the
         * {@code xxxI} view of these bytes and writing one means writing the {@code xxxO} view of the very
         * same bytes, which is what makes the four normalise-in-place writes work.
         */
        private final TransactionViewResponse response = new TransactionViewResponse();

        /**
         * The {@code xxxL} halfwords of {@code 01 COTRN2AI}, which are the cursor requests.
         *
         * <p>Held separately from {@link #response} because they are <em>metadata</em> and must never
         * become JSON payload members (gate G9): {@code MOVE -1 TO ACTIDINL} tells CICS where to put the
         * cursor and is not a value the operator typed or the program displays.
         */
        private final TransactionViewRequest symbolicMap = new TransactionViewRequest();

        /** {@code 01 CARDDEMO-COMMAREA} - L71, the 160 bytes every online program shares. */
        private NavigationContext commarea = NavigationContext.empty();

        /** {@code 05 CDEMO-CT02-INFO} - L72-80, the 58 bytes this program appends to it. */
        private TransactionViewResponse.Ct02Info ct02Info = new TransactionViewResponse.Ct02Info();

        /** {@code 05 WS-MESSAGE PIC X(80)} - L38. */
        private String message = spaces(WS_MESSAGE_LENGTH);

        /** {@code 05 WS-ERR-FLG PIC X(01)} - L44, with its {@code 88 ERR-FLG-ON} / {@code -OFF} pair. */
        private boolean errFlag;

        /**
         * {@code 05 WS-USR-MODIFIED PIC X(01)} - L49, with its {@code 88 USR-MODIFIED-YES} /
         * {@code -NO} pair. <strong>Dead: set to {@code 'N'} once at L110 and never set or tested
         * again.</strong> Preserved because practice B5 forbids tidying a legacy declaration away.
         */
        private boolean usrModified;

        /** {@code 05 WS-ACCT-ID-N PIC 9(11)} - L55. */
        private long wsAcctIdN;

        /** {@code 05 WS-CARD-NUM-N PIC 9(16)} - L56. */
        private long wsCardNumN;

        /** {@code 05 WS-TRAN-ID-N PIC 9(16)} - L57. */
        private long wsTranIdN;

        /** {@code 05 WS-TRAN-AMT-N PIC S9(9)V99} - L58, at scale exactly two. */
        private BigDecimal wsTranAmtN = CobolDecimal.zero(MONETARY_SCALE);

        /** {@code 05 WS-TRAN-AMT-E PIC +99999999.99} - L59, a twelve-character edited image. */
        private String wsTranAmtE = spaces(WS_TRAN_AMT_E_LENGTH);

        /** {@code 05 CSUTLDTC-DATE PIC X(10)} - L63. */
        private String csutldtcDate = spaces(DateUtilityJob.LS_DATE_LENGTH);

        /** {@code 05 CSUTLDTC-DATE-FORMAT PIC X(10)} - L64. */
        private String csutldtcDateFormat = spaces(DateUtilityJob.LS_DATE_FORMAT_LENGTH);

        /**
         * {@code 05 CSUTLDTC-RESULT} - L65-69, the eighty bytes the subprogram composes.
         *
         * <p>{@code null} between calls, which is what {@code MOVE SPACES TO CSUTLDTC-RESULT} at L391 and
         * L411 amounts to: the area holds no verdict until the call fills it.
         */
        private DateValidationResult csutldtcResult;

        /**
         * {@code 01 CARD-XREF-RECORD} - the fifty-byte record area {@code COPY CVACT03Y} at L90 declares.
         */
        private CardXrefRecord cardXrefRecord =
                new CardXrefRecord(spaces(CardXrefRecord.XREF_CARD_NUM_LENGTH), 0, 0L);

        /** {@code XREF-ACCT-ID} as the {@code RIDFLD} of the {@code CXACAIX} read at L582. */
        private String xrefAcctId = spaces(CardXrefRecord.XREF_ACCT_ID_LENGTH);

        /** {@code XREF-CARD-NUM} as the {@code RIDFLD} of the {@code CCXREF} read at L615. */
        private String xrefCardNum = spaces(CardXrefRecord.XREF_CARD_NUM_LENGTH);

        /**
         * {@code 01 TRAN-RECORD} - the 350-byte record area {@code COPY CVTRA05Y} at L88 declares.
         *
         * <p>Starts as a fresh area: numerics zero, alphanumerics spaces, and the trailing twenty-byte
         * {@code FILLER} spaces. The COBOL area is uninitialised working storage until L450, and this is
         * the deterministic reading of that - which matters for exactly one path, the {@code ENDFILE} arm
         * of {@code COPY-LAST-TRAN-DATA}, where the copy-back reads an area no record was ever read into.
         */
        private TranRecord tranRecord;

        /**
         * Whether {@code MOVE HIGH-VALUES TO TRAN-ID} has been executed - L444 and L475.
         *
         * <p>{@code HIGH-VALUES} is {@code X'FF'} repeated, which {@code US-ASCII} cannot represent, so the
         * repository expresses the intent as a browse direction rather than as a key. This flag records
         * that the intent was expressed, so a parity case can assert the boundary the browse started from.
         */
        private boolean tranIdAtHighValues;

        /** The open browse between {@code STARTBR} at L644 and {@code ENDBR} at L704; {@code null} otherwise. */
        private TransactionRepository.Browse browse;

        /** {@code 05 WS-RESP-CD PIC S9(09) COMP} - L47. */
        private int respCd;

        /** {@code 05 WS-REAS-CD PIC S9(09) COMP} - L48. */
        private int reasCd;

        /** {@code WS-CURDATE-DATA} of {@code COPY CSDAT01Y} at L85, filled by L554. */
        private DateHeader dateHeader;

        /** Whether {@code EXEC CICS RETURN} has executed - L530, and the unreachable L156. */
        private boolean returned;

        /** Whether {@code EXEC CICS XCTL} has executed - L508. */
        private boolean transferred;

        /** Whether {@code EXEC CICS SEND MAP} has executed - L522. */
        private boolean screenSent;

        /** The {@code DISPLAY} lines this task emitted, in order. */
        private final List<String> displayLines = new ArrayList<>();

        /** The record images handed to {@code EXEC CICS WRITE} at L713, in order. */
        private final List<String> writtenRecords = new ArrayList<>();

        /**
         * The picture-rule engine this area's own {@code MOVE}s go through.
         *
         * <p>Held because two of them are numeric: re-synchronising {@code XREF-ACCT-ID} from a record whose
         * accessor reports it as an integer needs the {@code PIC 9} zero fill, and rebuilding
         * {@code TRAN-RECORD} needs the code page.
         */
        private final FixedWidthCodec codec;

        /**
         * Opens a fresh working-storage area.
         *
         * @param codec the picture-rule engine, whose code page the record areas are built in; must not be
         *              {@code null}
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public ProgramState(FixedWidthCodec codec) {
            this.codec = Objects.requireNonNull(codec, "A codec is required: TRAN-RECORD is 350 bytes in "
                    + "a specific code page, and the area cannot be built without knowing which");
            this.tranRecord = new TranRecord(codec.charset());
            // 01 COTRN2AO is declared in WORKING-STORAGE at L82 with no VALUE clause, so a freshly
            // opened area holds the storage's initial image and not spaces. Every arm this program can
            // take either passes through MOVE LOW-VALUES TO COTRN2AO at L122 or leaves the area
            // untouched, and the one field the program does paint with spaces before any arm is chosen
            // is ERRMSGO, which L113 clears explicitly. Opening the area at spaces would put a value in
            // twenty of these twenty-one items that no statement in the program ever moved there, and
            // FieldDiffer reports a substituted byte (gate G21), so the resting image is stated once in
            // common.ScreenFieldImage and reached here through the response's own LOW-VALUES rule.
            for (ScreenField field : ScreenField.values()) {
                response.setOutputItem(field, TransactionViewResponse.lowValues(field.width()));
            }
        }

        // -------------------------------------------------------------------------------------------
        // The screen buffer.
        // -------------------------------------------------------------------------------------------

        /**
         * {@code 01 COTRN2AO} - the buffer this task will send.
         *
         * @return the response, never {@code null}
         */
        public TransactionViewResponse response() {
            return response;
        }

        /**
         * The {@code xxxL} halfwords, which carry the cursor requests.
         *
         * @return the metadata carrier, never {@code null}
         */
        public TransactionViewRequest symbolicMap() {
            return symbolicMap;
        }

        /**
         * Reads one payload field - the {@code xxxI} view.
         *
         * @param field the field to read; must not be {@code null}
         * @return its value at its declared width, never {@code null}
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public String payload(ScreenField field) {
            return response.getOutputItem(field);
        }

        /**
         * Writes one payload field - the {@code xxxO} view of the same bytes.
         *
         * <p>The value is put through the {@code PIC X} rule by the response's own setter, so a short one
         * space pads on the right and an over-wide one truncates on the right.
         *
         * @param field the field to write; must not be {@code null}
         * @param value the value to move into it; must not be {@code null}
         * @throws NullPointerException if either argument is {@code null}
         */
        public void setPayload(ScreenField field, String value) {
            response.setOutputItem(field, value);
        }

        /**
         * {@code MOVE LOW-VALUES TO COTRN2AO} - L122.
         *
         * <p>The move is over the whole 555-byte group, so it clears the {@code xxxL} halfwords as well as
         * the payload items - which is precisely why L123 sets the cursor <em>after</em> it and not before.
         * Both halves are cleared here for that reason.
         */
        public void moveLowValuesToOutputMap() {
            response.moveLowValuesToOutputMap();
            symbolicMap.resetMetadata();
        }

        /**
         * {@code MOVE -1 TO <field>L} - the twenty cursor placements.
         *
         * @param field the field the cursor should land on; must not be {@code null}
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public void moveMinusOneTo(ScreenField field) {
            Objects.requireNonNull(field, "A cursor placement names a field");
            symbolicMap.requestCursor(TransactionViewRequest.ScreenField.valueOf(field.name()));
        }

        /**
         * Whether a field's length item holds {@value TransactionViewRequest#CURSOR_REQUEST}.
         *
         * @param field the field to test; must not be {@code null}
         * @return {@code true} when this task asked for the cursor there
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public boolean cursorRequestedOn(ScreenField field) {
            Objects.requireNonNull(field, "A cursor query names a field");
            return symbolicMap.isCursorRequested(
                    TransactionViewRequest.ScreenField.valueOf(field.name()));
        }

        /**
         * This screen's presentation metadata, in the shared envelope every online response publishes.
         *
         * <p>Three things this execution produces are metadata by declaration rather than payload, and
         * before this envelope existed none of them had any way to travel:
         *
         * <ul>
         *   <li>the {@code MOVE -1 TO <field>L} cursor request, an {@code xxxL} item. The Agent Action
         *       Plan's section 0.3.9 is explicit that {@code xxxL} is validation and highlight metadata
         *       and not a payload member, so it is reported here rather than smuggled into a projection
         *       of {@code xxxI} and {@code xxxO} items. The field named is the first in map declaration
         *       order whose length item holds {@value TransactionViewRequest#CURSOR_REQUEST}, which is
         *       the one the terminal would place the cursor in;</li>
         *   <li>the {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} quad of each of the
         *       twenty-one fields, which is what {@code app/cpy/CSSETATY.cpy} writes
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
         * <p>{@code resetAllOutputFields} is {@code false}: where {@code MOVE LOW-VALUES TO COTRN2AO}
         * runs it has already been applied to the response being published, so the client is not being
         * asked to clear anything a second time.
         *
         * @return the metadata; never {@code null}
         */
        public ScreenMetadata screenMetadata() {
            Map<String, ScreenMetadata.FieldMetadata> fields = new LinkedHashMap<>();
            String cursorOn = null;
            for (ScreenField field : ScreenField.values()) {
                TransactionViewResponse.FieldMetadata quad = response.getMetadata(field);
                fields.put(field.label(), ScreenMetadata.FieldMetadata.of(quad.getColour(),
                        quad.getProgrammedSymbols(), quad.getHighlight(), quad.getValidation()));
                if (cursorOn == null && cursorRequestedOn(field)) {
                    cursorOn = field.label();
                }
            }
            return ScreenMetadata.of(cursorOn,
                    response.getMetadata(ScreenField.ERRMSG).getColour(),
                    false,
                    fields);
        }

        /** @return {@code ACTIDINI OF COTRN2AI} */
        public String actidinI() {
            return payload(ScreenField.ACTIDIN);
        }

        /** @param value the value to move into {@code ACTIDINI}; must not be {@code null} */
        public void setActidinI(String value) {
            setPayload(ScreenField.ACTIDIN, value);
        }

        /** @return {@code CARDNINI OF COTRN2AI} */
        public String cardninI() {
            return payload(ScreenField.CARDNIN);
        }

        /** @param value the value to move into {@code CARDNINI}; must not be {@code null} */
        public void setCardninI(String value) {
            setPayload(ScreenField.CARDNIN, value);
        }

        /** @return {@code TTYPCDI OF COTRN2AI} */
        public String ttypcdI() {
            return payload(ScreenField.TTYPCD);
        }

        /** @param value the value to move into {@code TTYPCDI}; must not be {@code null} */
        public void setTtypcdI(String value) {
            setPayload(ScreenField.TTYPCD, value);
        }

        /** @return {@code TCATCDI OF COTRN2AI} */
        public String tcatcdI() {
            return payload(ScreenField.TCATCD);
        }

        /** @param value the value to move into {@code TCATCDI}; must not be {@code null} */
        public void setTcatcdI(String value) {
            setPayload(ScreenField.TCATCD, value);
        }

        /** @return {@code TRNSRCI OF COTRN2AI} */
        public String trnsrcI() {
            return payload(ScreenField.TRNSRC);
        }

        /** @param value the value to move into {@code TRNSRCI}; must not be {@code null} */
        public void setTrnsrcI(String value) {
            setPayload(ScreenField.TRNSRC, value);
        }

        /** @return {@code TDESCI OF COTRN2AI} */
        public String tdescI() {
            return payload(ScreenField.TDESC);
        }

        /** @param value the value to move into {@code TDESCI}; must not be {@code null} */
        public void setTdescI(String value) {
            setPayload(ScreenField.TDESC, value);
        }

        /** @return {@code TRNAMTI OF COTRN2AI} */
        public String trnamtI() {
            return payload(ScreenField.TRNAMT);
        }

        /** @param value the value to move into {@code TRNAMTI}; must not be {@code null} */
        public void setTrnamtI(String value) {
            setPayload(ScreenField.TRNAMT, value);
        }

        /** @return {@code TORIGDTI OF COTRN2AI} */
        public String torigdtI() {
            return payload(ScreenField.TORIGDT);
        }

        /** @param value the value to move into {@code TORIGDTI}; must not be {@code null} */
        public void setTorigdtI(String value) {
            setPayload(ScreenField.TORIGDT, value);
        }

        /** @return {@code TPROCDTI OF COTRN2AI} */
        public String tprocdtI() {
            return payload(ScreenField.TPROCDT);
        }

        /** @param value the value to move into {@code TPROCDTI}; must not be {@code null} */
        public void setTprocdtI(String value) {
            setPayload(ScreenField.TPROCDT, value);
        }

        /** @return {@code MIDI OF COTRN2AI} */
        public String midI() {
            return payload(ScreenField.MID);
        }

        /** @param value the value to move into {@code MIDI}; must not be {@code null} */
        public void setMidI(String value) {
            setPayload(ScreenField.MID, value);
        }

        /** @return {@code MNAMEI OF COTRN2AI} */
        public String mnameI() {
            return payload(ScreenField.MNAME);
        }

        /** @param value the value to move into {@code MNAMEI}; must not be {@code null} */
        public void setMnameI(String value) {
            setPayload(ScreenField.MNAME, value);
        }

        /** @return {@code MCITYI OF COTRN2AI} */
        public String mcityI() {
            return payload(ScreenField.MCITY);
        }

        /** @param value the value to move into {@code MCITYI}; must not be {@code null} */
        public void setMcityI(String value) {
            setPayload(ScreenField.MCITY, value);
        }

        /** @return {@code MZIPI OF COTRN2AI} */
        public String mzipI() {
            return payload(ScreenField.MZIP);
        }

        /** @param value the value to move into {@code MZIPI}; must not be {@code null} */
        public void setMzipI(String value) {
            setPayload(ScreenField.MZIP, value);
        }

        /** @return {@code CONFIRMI OF COTRN2AI} */
        public String confirmI() {
            return payload(ScreenField.CONFIRM);
        }

        /** @param value the value to move into {@code CONFIRMI}; must not be {@code null} */
        public void setConfirmI(String value) {
            setPayload(ScreenField.CONFIRM, value);
        }

        /** @return {@code ERRMSGO OF COTRN2AO} */
        public String errmsgO() {
            return payload(ScreenField.ERRMSG);
        }

        // -------------------------------------------------------------------------------------------
        // The communication area.
        // -------------------------------------------------------------------------------------------

        /** @return {@code CARDDEMO-COMMAREA}, never {@code null} */
        public NavigationContext commarea() {
            return commarea;
        }

        /**
         * @param commarea the area to adopt; {@code null} is read as the empty area, which is the
         *                 {@code EIBCALEN = 0} state L115 tests for
         */
        public void setCommarea(NavigationContext commarea) {
            this.commarea = commarea == null ? NavigationContext.empty() : commarea;
        }

        /** @return {@code CDEMO-CT02-INFO}, never {@code null} */
        public TransactionViewResponse.Ct02Info ct02Info() {
            return ct02Info;
        }

        /**
         * @param ct02Info the extension to adopt; {@code null} is read as a fresh one
         */
        public void setCt02Info(TransactionViewResponse.Ct02Info ct02Info) {
            this.ct02Info = ct02Info == null ? new TransactionViewResponse.Ct02Info() : ct02Info;
        }

        /**
         * Copies the request's projection of {@code CDEMO-CT02-INFO} into the program's own copy, which is
         * the second half of {@code MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA} at L119.
         *
         * <p>Field by field rather than by reference, because the request and the response project the same
         * COBOL group as two distinct Java types - they are separate declarations of one storage layout,
         * and copying the six fields is what crosses between them without either type having to know the
         * other.
         *
         * @param source the request's projection; {@code null} is read as a fresh one
         */
        public void adoptCt02Info(TransactionViewRequest.Ct02Info source) {
            TransactionViewResponse.Ct02Info adopted = new TransactionViewResponse.Ct02Info();
            if (source != null) {
                adopted.setTrnidFirst(source.getTrnidFirst());
                adopted.setTrnidLast(source.getTrnidLast());
                adopted.setPageNum(source.getPageNum());
                adopted.setNextPageFlg(source.getNextPageFlg());
                adopted.setTrnSelFlg(source.getTrnSelFlg());
                adopted.setTrnSelected(source.getTrnSelected());
            }
            this.ct02Info = adopted;
        }

        // -------------------------------------------------------------------------------------------
        // Flags and carriers.
        // -------------------------------------------------------------------------------------------

        /** @return {@code WS-MESSAGE}, eighty characters, never {@code null} */
        public String message() {
            return message;
        }

        /** @param message the value to move into {@code WS-MESSAGE}; must not be {@code null} */
        public void setMessage(String message) {
            this.message = Objects.requireNonNull(message, "WS-MESSAGE is PIC X(80); move SPACES "
                    + "explicitly rather than null");
        }

        /** @return {@code true} when {@code 88 ERR-FLG-ON} holds */
        public boolean errFlagOn() {
            return errFlag;
        }

        /** @return {@code true} when {@code 88 ERR-FLG-OFF} holds */
        public boolean errFlagOff() {
            return !errFlag;
        }

        /** {@code MOVE 'Y' TO WS-ERR-FLG}. */
        public void setErrFlagOn() {
            this.errFlag = true;
        }

        /** {@code SET ERR-FLG-OFF TO TRUE} - L109. */
        public void setErrFlagOff() {
            this.errFlag = false;
        }

        /** @return {@code true} when {@code 88 USR-MODIFIED-YES} holds; never true in the composed flow */
        public boolean usrModifiedYes() {
            return usrModified;
        }

        /** @return {@code true} when {@code 88 USR-MODIFIED-NO} holds */
        public boolean usrModifiedNo() {
            return !usrModified;
        }

        /** {@code SET USR-MODIFIED-NO TO TRUE} - L110, the only statement that touches this flag. */
        public void setUsrModifiedNo() {
            this.usrModified = false;
        }

        /**
         * {@code SET USR-MODIFIED-YES TO TRUE} - a state the program declares at L50 and never sets.
         *
         * <p>Provided so the declared condition name is addressable, exactly as the copybook's own unused
         * slots are (practice B5). No paragraph calls it.
         */
        public void setUsrModifiedYes() {
            this.usrModified = true;
        }

        /** @return {@code WS-ACCT-ID-N} */
        public long wsAcctIdN() {
            return wsAcctIdN;
        }

        /** @param wsAcctIdN the value stored into {@code WS-ACCT-ID-N} */
        public void setWsAcctIdN(long wsAcctIdN) {
            this.wsAcctIdN = wsAcctIdN;
        }

        /** @return {@code WS-CARD-NUM-N} */
        public long wsCardNumN() {
            return wsCardNumN;
        }

        /** @param wsCardNumN the value stored into {@code WS-CARD-NUM-N} */
        public void setWsCardNumN(long wsCardNumN) {
            this.wsCardNumN = wsCardNumN;
        }

        /** @return {@code WS-TRAN-ID-N} */
        public long wsTranIdN() {
            return wsTranIdN;
        }

        /** @param wsTranIdN the value stored into {@code WS-TRAN-ID-N} */
        public void setWsTranIdN(long wsTranIdN) {
            this.wsTranIdN = wsTranIdN;
        }

        /** @return {@code WS-TRAN-AMT-N}, at scale exactly two, never {@code null} */
        public BigDecimal wsTranAmtN() {
            return wsTranAmtN;
        }

        /**
         * @param wsTranAmtN the value stored into {@code WS-TRAN-AMT-N}; must not be {@code null} and must
         *                   already be at the receiver's scale
         * @throws NullPointerException     if {@code wsTranAmtN} is {@code null}
         * @throws IllegalArgumentException if its scale is not {@value #MONETARY_SCALE}
         */
        public void setWsTranAmtN(BigDecimal wsTranAmtN) {
            Objects.requireNonNull(wsTranAmtN, "WS-TRAN-AMT-N is PIC S9(9)V99 and holds a value, never "
                    + "null; move zero explicitly");
            if (wsTranAmtN.scale() != MONETARY_SCALE) {
                throw new IllegalArgumentException("WS-TRAN-AMT-N is PIC S9(9)V99, so its scale is "
                        + MONETARY_SCALE + " and not " + wsTranAmtN.scale() + "; store the value through "
                        + "CobolDecimal so the truncation is applied where it can be reviewed");
            }
            this.wsTranAmtN = wsTranAmtN;
        }

        /** @return {@code WS-TRAN-AMT-E}, twelve characters, never {@code null} */
        public String wsTranAmtE() {
            return wsTranAmtE;
        }

        /**
         * @param wsTranAmtE the edited image stored into {@code WS-TRAN-AMT-E}; must not be {@code null}
         * @throws NullPointerException if {@code wsTranAmtE} is {@code null}
         */
        public void setWsTranAmtE(String wsTranAmtE) {
            this.wsTranAmtE = Objects.requireNonNull(wsTranAmtE, "WS-TRAN-AMT-E is a twelve-character "
                    + "edited image and is never null");
        }

        /** @return {@code CSUTLDTC-DATE}, ten characters, never {@code null} */
        public String csutldtcDate() {
            return csutldtcDate;
        }

        /** @param csutldtcDate the date moved into the parameter area; must not be {@code null} */
        public void setCsutldtcDate(String csutldtcDate) {
            this.csutldtcDate = Objects.requireNonNull(csutldtcDate,
                    "CSUTLDTC-DATE is PIC X(10) and is never null");
        }

        /** @return {@code CSUTLDTC-DATE-FORMAT}, ten characters, never {@code null} */
        public String csutldtcDateFormat() {
            return csutldtcDateFormat;
        }

        /** @param csutldtcDateFormat the mask moved into the parameter area; must not be {@code null} */
        public void setCsutldtcDateFormat(String csutldtcDateFormat) {
            this.csutldtcDateFormat = Objects.requireNonNull(csutldtcDateFormat,
                    "CSUTLDTC-DATE-FORMAT is PIC X(10) and is never null");
        }

        /** @return the eighty bytes the subprogram last composed, or {@code null} between calls */
        public DateValidationResult csutldtcResult() {
            return csutldtcResult;
        }

        /**
         * @param csutldtcResult the reply to record; {@code null} expresses
         *                       {@code MOVE SPACES TO CSUTLDTC-RESULT}
         */
        public void setCsutldtcResult(DateValidationResult csutldtcResult) {
            this.csutldtcResult = csutldtcResult;
        }

        // -------------------------------------------------------------------------------------------
        // The record areas.
        // -------------------------------------------------------------------------------------------

        /** @return {@code CARD-XREF-RECORD}, never {@code null} */
        public CardXrefRecord cardXrefRecord() {
            return cardXrefRecord;
        }

        /**
         * {@code INTO(CARD-XREF-RECORD)} - the read at L580 and L613 overwrites the whole fifty-byte area,
         * key fields included, so the two {@code RIDFLD} images are re-synchronised from the record here.
         * That is what makes {@code MOVE XREF-CARD-NUM TO CARDNINI} at L209 read the cross-referenced card
         * rather than the key the read went in with.
         *
         * @param cardXrefRecord the record just read; must not be {@code null}
         * @throws NullPointerException if {@code cardXrefRecord} is {@code null}
         */
        public void setCardXrefRecord(CardXrefRecord cardXrefRecord) {
            this.cardXrefRecord = Objects.requireNonNull(cardXrefRecord,
                    "A cross-reference record is required; a read that found nothing takes the NOTFND arm "
                            + "instead of storing null");
            this.xrefCardNum = cardXrefRecord.xrefCardNum();
            this.xrefAcctId = codec.movePic9(cardXrefRecord.xrefAcctId(),
                    CardXrefRecord.XREF_ACCT_ID_LENGTH);
        }

        /** @return {@code XREF-ACCT-ID} as the eleven-digit {@code RIDFLD} image, never {@code null} */
        public String xrefAcctId() {
            return xrefAcctId;
        }

        /** @param xrefAcctId the key moved into {@code XREF-ACCT-ID}; must not be {@code null} */
        public void setXrefAcctId(String xrefAcctId) {
            this.xrefAcctId = Objects.requireNonNull(xrefAcctId,
                    "XREF-ACCT-ID is PIC 9(11) and is never null");
        }

        /** @return {@code XREF-CARD-NUM} as the sixteen-character {@code RIDFLD} image, never {@code null} */
        public String xrefCardNum() {
            return xrefCardNum;
        }

        /** @param xrefCardNum the key moved into {@code XREF-CARD-NUM}; must not be {@code null} */
        public void setXrefCardNum(String xrefCardNum) {
            this.xrefCardNum = Objects.requireNonNull(xrefCardNum,
                    "XREF-CARD-NUM is PIC X(16) and is never null");
        }

        /** @return {@code TRAN-RECORD}, never {@code null} */
        public TranRecord tranRecord() {
            return tranRecord;
        }

        /**
         * {@code INTO(TRAN-RECORD)} - the read at L677.
         *
         * @param tranRecord the record just read; must not be {@code null}
         * @throws NullPointerException if {@code tranRecord} is {@code null}
         */
        public void setTranRecord(TranRecord tranRecord) {
            this.tranRecord = Objects.requireNonNull(tranRecord,
                    "A transaction record is required; an end-of-file takes the ENDFILE arm instead of "
                            + "storing null");
        }

        /**
         * {@code INITIALIZE TRAN-RECORD} - L450: numerics to zero, alphanumerics to spaces, and the
         * trailing twenty-byte {@code FILLER} left as spaces so the written image is a full 350 bytes
         * (gates G19, G21).
         */
        public void initializeTranRecord() {
            this.tranRecord = new TranRecord(tranRecord.charset());
        }

        /** {@code MOVE HIGH-VALUES TO TRAN-ID} - L444 and L475. */
        public void moveHighValuesToTranId() {
            this.tranIdAtHighValues = true;
        }

        /** @return {@code true} once the browse boundary has been set to {@code HIGH-VALUES} */
        public boolean tranIdAtHighValues() {
            return tranIdAtHighValues;
        }

        // -------------------------------------------------------------------------------------------
        // The browse, the response codes and the terminal flags.
        // -------------------------------------------------------------------------------------------

        /**
         * {@code EXEC CICS STARTBR} - L644.
         *
         * @param browse the positioned handle; must not be {@code null}
         * @throws NullPointerException if {@code browse} is {@code null}
         */
        public void openBrowse(TransactionRepository.Browse browse) {
            this.browse = Objects.requireNonNull(browse, "A positioned browse is required");
        }

        /**
         * The open browse.
         *
         * @return the handle, never {@code null}
         * @throws IllegalStateException if no browse is open, because the source never issues a
         *                               {@code READPREV} without the {@code STARTBR} that precedes it
         */
        public TransactionRepository.Browse requireBrowse() {
            if (browse == null) {
                throw new IllegalStateException("No browse of " + WS_TRANSACT_FILE + " is positioned; "
                        + "COTRN02C issues STARTBR at L445 and L476 immediately before every READPREV, so "
                        + "a read without one means the sequence was entered part way through");
            }
            return browse;
        }

        /** {@code EXEC CICS ENDBR} - L704. Ending a browse that is not open does nothing. */
        public void closeBrowse() {
            if (browse != null) {
                browse.endBrowse();
                browse = null;
            }
        }

        /** @return {@code true} while a browse is positioned */
        public boolean browseOpen() {
            return browse != null;
        }

        /** @return {@code WS-RESP-CD} */
        public int respCd() {
            return respCd;
        }

        /** @param respCd the value stored into {@code WS-RESP-CD} */
        public void setRespCd(int respCd) {
            this.respCd = respCd;
        }

        /** @return {@code WS-REAS-CD} */
        public int reasCd() {
            return reasCd;
        }

        /** @param reasCd the value stored into {@code WS-REAS-CD} */
        public void setReasCd(int reasCd) {
            this.reasCd = reasCd;
        }

        /** @return the date and time L554 captured, or {@code null} before the first send */
        public DateHeader dateHeader() {
            return dateHeader;
        }

        /** @param dateHeader the captured date and time; must not be {@code null} */
        public void setDateHeader(DateHeader dateHeader) {
            this.dateHeader = Objects.requireNonNull(dateHeader,
                    "POPULATE-HEADER-INFO always captures a date and time before it moves them");
        }

        /** @return {@code true} once {@code EXEC CICS RETURN} has executed */
        public boolean returned() {
            return returned;
        }

        /** {@code EXEC CICS RETURN} - L530. */
        public void markReturned() {
            this.returned = true;
        }

        /** @return {@code true} once {@code EXEC CICS XCTL} has executed */
        public boolean transferred() {
            return transferred;
        }

        /** {@code EXEC CICS XCTL} - L508. */
        public void markTransferred() {
            this.transferred = true;
        }

        /**
         * Whether this task has ended, by either route.
         *
         * <p>This is the guard every caller of a terminal paragraph consults, and it is the rule R7
         * rendering of a non-local exit: in the source the statement after the {@code PERFORM} is simply
         * never reached, and here it is reached and skipped.
         *
         * @return {@code true} once the task has returned to CICS or transferred
         */
        public boolean taskEnded() {
            return returned || transferred;
        }

        /** @return {@code true} once {@code EXEC CICS SEND MAP} has executed */
        public boolean screenSent() {
            return screenSent;
        }

        /** {@code EXEC CICS SEND MAP ... ERASE CURSOR} - L522. */
        public void recordScreenSent() {
            this.screenSent = true;
        }

        /**
         * The {@code DISPLAY} lines this task emitted, oldest first.
         *
         * @return an unmodifiable view, never {@code null}
         */
        public List<String> displayLines() {
            return Collections.unmodifiableList(displayLines);
        }

        /**
         * @param text the line the source displays; must not be {@code null}
         * @throws NullPointerException if {@code text} is {@code null}
         */
        public void recordDisplay(String text) {
            displayLines.add(Objects.requireNonNull(text, "A DISPLAY emits a line, never null"));
        }

        /**
         * The record images handed to {@code EXEC CICS WRITE}, oldest first, each exactly
         * {@value TranRecord#RECORD_LENGTH} characters including the trailing {@code FILLER}.
         *
         * <p>This is what a parity case diffs field by field, which is why the whole image is kept rather
         * than only the fields the program moved into it (gates G19, G21).
         *
         * @return an unmodifiable view, never {@code null}
         */
        public List<String> writtenRecords() {
            return Collections.unmodifiableList(writtenRecords);
        }

        /**
         * Records the image of a record just written.
         *
         * @param record the record handed to the repository; must not be {@code null}
         * @throws NullPointerException if {@code record} is {@code null}
         */
        public void recordWritten(TranRecord record) {
            Objects.requireNonNull(record, "A write hands over a record, never null");
            writtenRecords.add(record.displayImage());
        }
    }
}
