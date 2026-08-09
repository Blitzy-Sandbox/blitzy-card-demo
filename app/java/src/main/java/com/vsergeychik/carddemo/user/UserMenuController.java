package com.vsergeychik.carddemo.user;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.user.SecUserRepository.BrowseCursor;
import com.vsergeychik.carddemo.user.SecUserRepository.ReadResult;
import com.vsergeychik.carddemo.user.dto.UserListRequest;
import com.vsergeychik.carddemo.user.dto.UserListResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import jakarta.validation.Valid;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@code COUSR00C} - the security-user list screen, CSD transaction {@code CU00}.
 *
 * <h2>The class name says "menu"; the program is a list. Read this first.</h2>
 * <strong>This class does not render a menu.</strong> The name {@code UserMenuController} is mandated
 * verbatim by the migration prompt and is used verbatim under transformation rule <strong>R1</strong> -
 * <em>the name comes from the prompt, the behaviour comes from the source</em> - but two independent
 * pieces of evidence in this repository say the program lists users:
 * <ul>
 *   <li>{@code app/cbl/COUSR00C.cbl:5}, the program's own header:
 *       {@code * Function    : List all users from USRSEC file};</li>
 *   <li>{@code README.md:228}, the transaction inventory:
 *       {@code | CU00 | COUSR00 | COUSR00C | List Users |}.</li>
 * </ul>
 * The application's actual menus are {@code COADM01C} and {@code COMEN01C}, in the sibling
 * {@code admin} package. This divergence is catalogued in the migration plan's class-name divergence
 * register as risk <strong>R-C</strong>; renaming the class would violate R1, so the name stands and
 * this paragraph exists so that no later reader is misled by it.
 *
 * <h2>What the screen does</h2>
 * Ten users at a time are browsed out of the {@code USRSEC} key-sequenced file and painted into the ten
 * repeating rows of map {@code COUSR0A}. The operator pages with PF7 and PF8, may type a start
 * user-identifier to reposition the browse, and may tick one row with {@code U} or {@code D} to be taken
 * to the update or the delete screen. PF3 goes back to the admin menu.
 *
 * <h2>The 34-byte communication-area extension, and why it is not in {@code NavigationContext}</h2>
 * {@code app/cbl/COUSR00C.cbl:66-75} appends a further {@code 05}-level group immediately after
 * {@code COPY COCOM01Y.}, <em>inside the same {@code 01 CARDDEMO-COMMAREA}</em>:
 * <pre>
 * 05 CDEMO-CU00-INFO.
 *    10 CDEMO-CU00-USRID-FIRST     PIC X(08).
 *    10 CDEMO-CU00-USRID-LAST      PIC X(08).
 *    10 CDEMO-CU00-PAGE-NUM        PIC 9(08).
 *    10 CDEMO-CU00-NEXT-PAGE-FLG   PIC X(01) VALUE 'N'.
 *       88 NEXT-PAGE-YES                     VALUE 'Y'.
 *       88 NEXT-PAGE-NO                      VALUE 'N'.
 *    10 CDEMO-CU00-USR-SEL-FLG     PIC X(01).
 *    10 CDEMO-CU00-USR-SELECTED    PIC X(08).
 * </pre>
 * That is 8 + 8 + 8 + 1 + 1 + 8 = <strong>34</strong> bytes on top of the 160 bytes of
 * {@code app/cpy/COCOM01Y.cpy}, so this transaction's communication area is <strong>194</strong> bytes
 * where every other screen's is 160.
 *
 * <p>The extension is <strong>specific to {@code COUSR00C}</strong> and is therefore carried in the
 * {@link UserListRequest} and {@link UserListResponse} payload, never added to
 * {@link NavigationContext}: that type is exactly {@value NavigationContext#COMMAREA_LENGTH} bytes and
 * is shared by all seventeen online programs, so widening it would change every other screen's byte
 * image. {@code CDEMO-CU00-PAGE-NUM} is {@code PIC 9(08)} - unsigned, no {@code V}, no scale - so it is
 * an {@code int}; no {@code BigDecimal} is involved and no {@code double} or {@code float} appears
 * anywhere in this file.
 *
 * <h2>Statelessness (rule R6)</h2>
 * The whole pseudo-conversation travels in the payload: the communication area, the {@code CDEMO-CU00}
 * paging anchors, the attention identifier and the screen's own field values. This class holds
 * <strong>three final collaborators and nothing else</strong>; every value the COBOL keeps in
 * {@code WORKING-STORAGE} lives on a {@link WorkArea} created per call. There is no {@code HttpSession},
 * no {@code @SessionAttributes}, no static cache and no mutable static field, so two successive requests
 * share nothing and a test needs no reset between cases.
 *
 * <h2>One storage, two views: why the program writes the {@code I} items</h2>
 * {@code POPULATE-USER-DATA} moves record fields into {@code USRID01I}, {@code FNAME01I} and friends -
 * the <em>input</em> items - and {@code SEND-USRLST-SCREEN} then sends {@code FROM(COUSR0AO)}. That is
 * not a defect, and the reason is arithmetic. In {@code app/cpy-bms/COUSR00.CPY} each field of
 * {@code 01 COUSR0AI} occupies {@code L}(2) + {@code F}(1) + {@code FILLER}(4) + {@code I}(n) = 7 + n
 * bytes, and each field of {@code 01 COUSR0AO REDEFINES COUSR0AI} occupies {@code FILLER}(3) +
 * {@code C} + {@code P} + {@code H} + {@code V}(4) + {@code O}(n) = 7 + n bytes. Both maps open with the
 * same 12-byte {@code TIOAPFX} filler, so the strides match and the {@code xxxI} and {@code xxxO} data
 * items <strong>occupy exactly the same bytes</strong>. Writing one writes the other.
 *
 * <p>This class models that faithfully with a single {@link SymbolicMap} per call, read as
 * {@code COUSR0AI} and rendered as {@code COUSR0AO}. It is why {@code MOVE CDEMO-CU00-PAGE-NUM TO
 * PAGENUMI} at {@code :327} and {@code :376} reaches the screen even though it names the input item.
 *
 * <h2>Field contract: 59 fields, and the metadata that is not payload</h2>
 * Every payload member traces to one of the <strong>59</strong> name-labelled {@code DFHMDF} definitions
 * of {@code app/bms/COUSR00.bms} - 8 header and paging fields, 10 rows of 5, and the message line - and
 * every width comes from the corresponding {@code xxxI PIC X(n)} of the symbolic map. The
 * {@code xxxL}/{@code xxxF}/{@code xxxA} items of {@code COUSR0AI} and the
 * {@code xxxC}/{@code xxxP}/{@code xxxH}/{@code xxxV} items of {@code COUSR0AO} are validation and
 * highlight metadata and are <strong>not</strong> payload members: the one this program uses is
 * {@code USRIDINL}, which it sets to {@code -1} to place the cursor, and that is held on the
 * {@link WorkArea} as {@link WorkArea#usrIdInLength()} rather than on the wire.
 *
 * <p>{@code COUSR00C} copies {@code DFHAID} and {@code DFHBMSCA} but <strong>not</strong>
 * {@code DFHATTR}, and - unlike its siblings {@code COUSR01C}, {@code COUSR02C} and {@code COUSR03C} -
 * it moves <em>no</em> attribute or colour byte of its own. {@link BmsAttributes} is therefore referenced
 * for the copybook it stands in for and no {@code DFHRED} or {@code DFHGREEN} move is invented here.
 *
 * <h2>Preserved oddities (practice B5 - never tidy these)</h2>
 * <ol>
 *   <li><strong>Four dead working-storage items.</strong> {@code WS-REC-COUNT} ({@code :52}),
 *       {@code WS-PAGE-NUM} ({@code :54}) and the whole of {@code 01 WS-USER-DATA} with its
 *       {@code 02 USER-REC OCCURS 10 TIMES} ({@code :56-64}) are declared and <em>never referenced in
 *       the {@code PROCEDURE DIVISION}</em> - verified by scanning the file: every occurrence of those
 *       names lies above {@code :97}, where the division begins. They are consequently <strong>not
 *       modelled here</strong>, not even as unused fields. Note in particular that paging uses
 *       {@code CDEMO-CU00-PAGE-NUM} in the communication area and never {@code WS-PAGE-NUM}: two
 *       similarly named items, one live and one dead.</li>
 *   <li><strong>{@code CONTINUE} is a no-op, not a branch terminator.</strong> The
 *       {@code WHEN DFHRESP(NOTFND)} and {@code WHEN DFHRESP(ENDFILE)} arms at {@code :601},
 *       {@code :635} and {@code :669} open with a bare {@code CONTINUE} and the statements after it
 *       <em>do</em> execute. Reading it as "do nothing" would silently drop the end-of-file flag and the
 *       message, which is the single easiest way to break this screen.</li>
 *   <li><strong>Two {@code WHEN} labels sharing one action is case tolerance.</strong>
 *       {@code WHEN 'U' WHEN 'u'} at {@code :190-191} and {@code WHEN 'D' WHEN 'd'} at {@code :200-201}
 *       accept either case; both are kept.</li>
 *   <li><strong>The {@code HIGH-VALUES} PF8 start key finds nothing, on purpose.</strong> See
 *       {@link #processPf8Key(WorkArea, byte)}.</li>
 * </ol>
 *
 * <h2>Two CICS verbs with no Java equivalent, surfaced rather than dropped (practice B12)</h2>
 * <ul>
 *   <li><strong>{@code SEND} with and without {@code ERASE}.</strong> {@code :528-544} chooses between
 *       them on {@code WS-SEND-ERASE-FLG}; the {@code ERASE} operand is commented out in the second arm
 *       at {@code :541}. HTTP has no screen to erase and {@link UserListResponse} declares no member for
 *       it, so the intent is recorded on the work area: every send appends a
 *       {@link SentScreen} carrying the rendered payload <em>and</em> its erase intent, and
 *       {@link WorkArea#sends()} is the whole sequence.</li>
 *   <li><strong>{@code ENDBR}.</strong> {@code :687-691} specifies no {@code RESP} and the paragraph has
 *       no {@code EVALUATE} after it, so the program inspects no outcome. {@link #endBrowse(BrowseCursor)}
 *       is correspondingly a documented no-op with no error path - inventing one would create a branch
 *       the source cannot take.</li>
 * </ul>
 *
 * <h2>Why a single screen can be sent more than once</h2>
 * The end-of-file arm of {@code READNEXT} sends the screen from inside the paging loop, and
 * {@code PROCESS-PAGE-FORWARD} then sends it again at {@code :329}. A CICS terminal simply receives two
 * transmissions; a REST response can carry only the last. {@code WS-MESSAGE} is not cleared in between,
 * so the message the earlier send carried survives into the final payload - but where two sends carry
 * <em>different</em> messages, only {@link WorkArea#sends()} shows both. The HTTP body is always the
 * last send, which is what the terminal would be left displaying.
 *
 * <h2>No service class, and why every paragraph is package-visible</h2>
 * The migration plan lists exactly one service in this package, {@code SignOnService} for
 * {@code COSGN00C}, so none is invented for {@code COUSR00C}. Instead <strong>one</strong> method here is
 * public beyond the constructors - the request mapping - and every paragraph is a package-visible method
 * taking an explicit {@link WorkArea}. No servlet type appears in any signature, so a plain JUnit test in
 * this package drives the pager, the selection scan and each browse arm directly, with a stubbed
 * {@link SecUserRepository} and no {@code MockMvc} in the path.
 *
 * <h2>Page size ten is behaviour, not configuration</h2>
 * The literals {@code 10} and {@code 11} are hard-coded in {@code :293}, {@code :300}, {@code :347} and
 * {@code :352}. The page size is accordingly a {@code private static final int} and is deliberately
 * <strong>not</strong> a {@code @Value}, an {@code application.yml} key, a request parameter or a
 * constructor argument: making it tunable would let a deployment change observable behaviour.
 *
 * @see SecUserRepository the only route to {@code USRSEC}; no dataset name and no
 *      {@code JdbcTemplate} appears in this file
 * <h2>This endpoint is unauthenticated and unauthorized - an accepted divergence (CWE-306, CWE-862 and CWE-522)</h2>
 *
 * <p>This controller lists the records in {@code USRSEC} with no authentication and no role check.
 * Nothing here establishes who is calling or that they administer users, so this endpoint enumerates
 * the security file - user identifiers, names and types - for any caller that can reach it.
 *
 * <p>That is inherited from the legacy design rather than introduced here: in CICS the region controls
 * which transactions an operator can reach and no COBOL program in {@code app/cbl} performs a check of
 * its own. It is not remedied here because every remedy is either excluded from the migration's closed
 * dependency set or changes an observable outcome that the parity diff compares. The full disposition -
 * the three exposures, the evidence for each, why each remedy is unavailable, and what a deployment must
 * do instead - is stated once in {@link SignOnService}, which owns this package's credential handling.
 * Read it before changing anything on this path.

 */
@RestController
public final class UserMenuController {

    // =================================================================================================
    // Diagnostics.
    // =================================================================================================

    /**
     * The log the two {@code DISPLAY} statement shapes of this program write to.
     *
     * <p>Static and {@code final}, holding no request state - the one static member this class has. Each
     * {@code DISPLAY} is also recorded on the work area, so a parity case can assert the program's
     * console output without capturing a log.
     */
    private static final Log LOG = LogFactory.getLog(UserMenuController.class);

    // =================================================================================================
    // The HTTP surface's names.
    // =================================================================================================

    /** {@code GET /api/users} - the REST projection of CSD transaction {@code CU00}. */
    static final String USER_LIST_PATH = "/api/users";

    /**
     * The query parameter carrying {@code EIBAID}.
     *
     * <p>{@code EIBAID} is one byte of the CICS exec interface block. A raw byte cannot travel in a query
     * string, so it arrives as its unsigned integer value and is narrowed by
     * {@link #resolveEibAid(Integer, UserListRequest)}.
     */
    static final String EIBAID_PARAM = "eibaid";

    /**
     * The {@code PIC X} move rule, applied to the inbound {@code CCARD-AID} token so that an unpadded
     * spelling matches the copybook literal.
     *
     * <p>{@code static final} and immutable, and {@link FixedWidthCodec#movePicX(String, int)} is a pure
     * {@link String} operation that never consults the charset, so the code page named here selects
     * nothing (practice B9: static final and immutable is not shared mutable state).
     */
    private static final FixedWidthCodec AID_TOKEN_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    /** The lowest value an unsigned {@code EIBAID} byte can carry. */
    private static final int AID_MIN = 0;

    /** The highest value an unsigned {@code EIBAID} byte can carry. */
    private static final int AID_MAX = 255;

    // =================================================================================================
    // WS-VARIABLES literals - app/cbl/COUSR00C.cbl:35-48.
    // =================================================================================================

    /** {@code 05 WS-PGMNAME PIC X(08) VALUE 'COUSR00C'} - {@code :36}. */
    static final String WS_PGMNAME = "COUSR00C";

    /** {@code 05 WS-TRANID PIC X(04) VALUE 'CU00'} - {@code :37}. */
    static final String WS_TRANID = "CU00";

    /**
     * {@code 05 WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} - {@code :39}.
     *
     * <p>The eight-character CICS <em>file</em> name, trailing spaces included, which is the
     * {@code DATASET} operand of all four browse commands. It is <strong>not</strong> a dataset name: the
     * data set this file maps to is resolved from configuration inside {@link SecUserRepository}, and no
     * dataset literal appears in this class.
     */
    static final String WS_USRSEC_FILE = SecUserRepository.CICS_FILE_NAME_IMAGE;

    /** {@code 05 WS-MESSAGE PIC X(80) VALUE SPACES} - {@code :38}. */
    static final int WS_MESSAGE_LENGTH = 80;

    // =================================================================================================
    // Paging geometry. Behaviour, not configuration - see the class documentation.
    // =================================================================================================

    /**
     * Ten rows to a page: the {@code 10} of {@code UNTIL WS-IDX > 10} at {@code :293} and {@code :347}
     * and of {@code MOVE 10 TO WS-IDX} at {@code :352}.
     */
    private static final int PAGE_SIZE = 10;

    /** The first {@code WS-IDX} value, {@code MOVE 1 TO WS-IDX} at {@code :298}. {@code WS-IDX} is 1-based. */
    private static final int FIRST_ROW = 1;

    /**
     * The forward loop's exclusive bound: the {@code 11} of {@code UNTIL WS-IDX >= 11} at {@code :300}.
     *
     * <p>Derived from {@link #PAGE_SIZE} rather than written as a second literal, so the two cannot drift
     * apart. {@code :293} fills rows 1 to 10 and {@code :300} stops when the eleventh would be needed.
     */
    private static final int FORWARD_LOOP_BOUND = PAGE_SIZE + 1;

    /** The backward loop's exclusive bound: the {@code 0} of {@code UNTIL WS-IDX <= 0} at {@code :354}. */
    private static final int BACKWARD_LOOP_BOUND = 0;

    /**
     * {@code MOVE -1 TO USRIDINL} - the BMS convention for "put the cursor in this field", used at
     * {@code :108}, {@code :134}, {@code :214}, {@code :224}, {@code :246}, {@code :268} and in all three
     * browse-failure arms.
     */
    static final int CURSOR_ON_USRIDIN = -1;

    /** The {@code xxxL} value of a field the cursor is not being placed in. */
    static final int NO_CURSOR = 0;

    // =================================================================================================
    // Message texts. Every literal is byte-exact, including the three-dot ellipsis with NO preceding
    // space - contrast COSGN00C, which writes ' ...' in some of its texts.
    // =================================================================================================

    /** {@code :251-252}, PF7 pressed while already on page 1. */
    static final String MSG_ALREADY_AT_TOP = "You are already at the top of the page...";

    /** {@code :273-274}, PF8 pressed while {@code NEXT-PAGE-NO} holds. */
    static final String MSG_ALREADY_AT_BOTTOM = "You are already at the bottom of the page...";

    /** {@code :603-604}, the {@code STARTBR} {@code WHEN DFHRESP(NOTFND)} arm. */
    static final String MSG_AT_TOP = "You are at the top of the page...";

    /** {@code :637-638}, the {@code READNEXT} {@code WHEN DFHRESP(ENDFILE)} arm. */
    static final String MSG_REACHED_BOTTOM = "You have reached the bottom of the page...";

    /**
     * {@code :671-672}, the {@code READPREV} {@code WHEN DFHRESP(ENDFILE)} arm.
     *
     * <p>Note "top" here against "bottom" in {@link #MSG_REACHED_BOTTOM}: the two arms are otherwise
     * identical and the one word is the difference, so they are two constants rather than one.
     */
    static final String MSG_REACHED_TOP = "You have reached the top of the page...";

    /**
     * {@code :610-611}, {@code :644-645} and {@code :678-679} - the {@code WHEN OTHER} arm of all three
     * browse commands, which share one text.
     */
    static final String MSG_UNABLE_TO_LOOKUP = "Unable to lookup User...";

    /**
     * {@code :211-213}, a selection character that is neither {@code U} nor {@code D}.
     *
     * <p>No trailing dots, unlike every other message on this screen. Taken from the response payload's
     * own constant so the two cannot disagree.
     */
    static final String MSG_INVALID_SELECTION = UserListResponse.INVALID_SELECTION_MESSAGE;

    /** {@code :135}, {@code MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE} for an unhandled key. */
    static final String MSG_INVALID_KEY = SystemMessages.CCDA_MSG_INVALID_KEY;

    /** The first literal of the {@code DISPLAY} in each {@code WHEN OTHER} arm. */
    static final String DISPLAY_RESP = "RESP:";

    /** The second literal of the {@code DISPLAY} in each {@code WHEN OTHER} arm. */
    static final String DISPLAY_REAS = "REAS:";

    /**
     * Digit positions in {@code WS-RESP-CD} and {@code WS-REAS-CD}: {@code 9}, from
     * {@code 01 WS-RESP-CD PIC S9(09) COMP} and {@code 01 WS-REAS-CD PIC S9(09) COMP}.
     *
     * <p>Used only to size the image of an unreported response code, so that substitute occupies the
     * digit positions the field declares. A reported code is rendered as this controller has always
     * rendered it, and that rendering is not this finding's subject.
     */
    static final int WS_RESP_CD_DIGITS = 9;

    // =================================================================================================
    // Navigation targets and selection characters.
    // =================================================================================================

    /** {@code :192}, {@code MOVE 'COUSR02C' TO CDEMO-TO-PROGRAM} for selection {@code U} or {@code u}. */
    static final String LIT_USER_UPDATE_PGM = UserListResponse.NEXT_PROGRAM_USER_UPDATE;

    /** {@code :202}, {@code MOVE 'COUSR03C' TO CDEMO-TO-PROGRAM} for selection {@code D} or {@code d}. */
    static final String LIT_USER_DELETE_PGM = UserListResponse.NEXT_PROGRAM_USER_DELETE;

    /** {@code :111} and {@code :509}, the cold-start and default return target. */
    static final String LIT_SIGNON_PGM = UserListResponse.NEXT_PROGRAM_SIGNON;

    /** {@code :126}, {@code MOVE 'COADM01C' TO CDEMO-TO-PROGRAM} for PF3. */
    static final String LIT_ADMIN_MENU_PGM = "COADM01C";

    /** {@code WHEN 'U'} at {@code :190}. */
    static final char SEL_UPDATE_UPPER = 'U';

    /** {@code WHEN 'u'} at {@code :191} - deliberate case tolerance, not a duplicate. */
    static final char SEL_UPDATE_LOWER = 'u';

    /** {@code WHEN 'D'} at {@code :200}. */
    static final char SEL_DELETE_UPPER = 'D';

    /** {@code WHEN 'd'} at {@code :201} - deliberate case tolerance, not a duplicate. */
    static final char SEL_DELETE_LOWER = 'd';

    /** The {@code SPACES} figurative constant, one character wide. */
    private static final char SPACE = ' ';

    /** The {@code LOW-VALUES} figurative constant, one character wide. */
    private static final char LOW_VALUE = '\u0000';

    // =================================================================================================
    // Collaborators. Three, all final, all constructor-injected. No other state exists on this class.
    // =================================================================================================

    /**
     * The {@code USRSEC} file.
     *
     * <p>The only route to the security-user data set. This class issues no SQL, holds no
     * {@code JdbcTemplate} and names no data set.
     */
    private final SecUserRepository secUserRepository;

    /**
     * The fixed-width codec, carrying the dataset code page.
     *
     * <p>Every cross-width {@code MOVE} on this screen goes through it, so the direction of truncation is
     * chosen deliberately per receiver rather than left to a Java assignment that would neither pad nor
     * truncate. The 80-to-78 narrowing of the message line at {@code :526} is the one that matters most.
     */
    private final FixedWidthCodec codec;

    /**
     * The clock {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} ({@code :564}) reads.
     *
     * <p>Injected rather than consulted inline, so a test can pin it with {@code Clock.fixed(...)} and
     * assert an exact rendered header. {@link Clock} is immutable and thread-safe, so one shared instance
     * serves every concurrent request.
     */
    private final Clock clock;

    // =================================================================================================
    // Construction. Two constructors: one for the container, one for a test.
    // =================================================================================================

    /**
     * Wiring constructor, used by the Spring container.
     *
     * <p>It exists only so that the canonical constructor can take the codec itself. The code page
     * arrives as an explicit argument selected by bean name, because a fixed-width mainframe field is
     * bytes in a specific code page and the platform default is never consulted anywhere in this module.
     *
     * @param secUserRepository the {@code USRSEC} file
     * @param datasetCharset    the active dataset code page,
     *                          {@code @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)}
     * @param clock             the clock {@code FUNCTION CURRENT-DATE} is read from
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if a width or count this screen depends on disagrees with the
     *                               copybook that owns it - see {@link #verifyScreenContract()}
     */
    @Autowired
    public UserMenuController(SecUserRepository secUserRepository,
                              @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)
                              Charset datasetCharset,
                              Clock clock) {
        this(secUserRepository,
                new FixedWidthCodec(Objects.requireNonNull(datasetCharset,
                        "A dataset charset is required: the user list renders fixed-width fields, so "
                                + "the code page is stated explicitly and never taken from the "
                                + "platform")),
                clock);
    }

    /**
     * Canonical constructor. This is the one a unit test calls: it needs no Spring context, no
     * {@code MockMvc} and no backend, only a stubbed {@link SecUserRepository}.
     *
     * <p>Both guards run before it returns, so a width that has drifted fails at context refresh rather
     * than in the middle of a request.
     *
     * @param secUserRepository the {@code USRSEC} file
     * @param codec             the fixed-width codec, carrying the dataset code page
     * @param clock             the clock {@code FUNCTION CURRENT-DATE} is read from
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if a width or count this screen depends on disagrees with the
     *                               copybook that owns it
     */
    public UserMenuController(SecUserRepository secUserRepository, FixedWidthCodec codec, Clock clock) {
        this.secUserRepository = Objects.requireNonNull(secUserRepository, "A SecUserRepository is "
                + "required: this screen reaches USRSEC only through it, never through a JdbcTemplate "
                + "and never by dataset name");
        this.codec = Objects.requireNonNull(codec, "A fixed-width codec is required: it owns the PIC X "
                + "and PIC 9 MOVE rules every field of this screen is written with, including the "
                + "80-to-78 narrowing of the message line at COUSR00C:526");
        this.clock = Objects.requireNonNull(clock, "A Clock is required: FUNCTION CURRENT-DATE at "
                + "COUSR00C:564 is read from an injected clock so a test can pin the rendered header");
        verifyScreenContract();
        verifyNavigationContract();
    }

    // =================================================================================================
    // Contract verification. Both guards run at construction, both fail loudly, and each names the
    // authority it defends. None of these can be allowed to drift silently: a page size that disagreed
    // would page wrongly, a title width that disagreed would pad the header wrongly, and a message width
    // that disagreed would truncate the wrong number of characters.
    // =================================================================================================

    /**
     * Checks every width and count this screen's behaviour depends on against the type that owns it.
     *
     * @throws IllegalStateException if any check fails
     */
    static void verifyScreenContract() {
        // The page size, asserted against both halves of the DTO pair. app/cbl/COUSR00C.cbl:293, :347.
        requireAgreement(PAGE_SIZE, UserListRequest.ROW_COUNT,
                "the OCCURS-10 page size and UserListRequest.ROW_COUNT");
        requireAgreement(PAGE_SIZE, UserListResponse.ROW_COUNT,
                "the OCCURS-10 page size and UserListResponse.ROW_COUNT");

        // The 59 name-labelled DFHMDF fields of app/bms/COUSR00.bms, asserted on both halves.
        requireAgreement(UserListRequest.MAP_FIELD_COUNT, UserListResponse.MAP_FIELD_COUNT,
                "the DFHMDF field count of the request and of the response");

        // COTTL01Y's two titles against the map items :566-567 moves them into.
        requireAgreement(ScreenTitles.TITLE_LENGTH, UserListResponse.TITLE01_LENGTH,
                "CCDA-TITLE01 and TITLE01O");
        requireAgreement(ScreenTitles.TITLE_LENGTH, UserListResponse.TITLE02_LENGTH,
                "CCDA-TITLE02 and TITLE02O");

        // CSDAT01Y's two composed header images against the map items :575 and :581 move them into.
        requireAgreement(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH, UserListResponse.CURDATE_LENGTH,
                "WS-CURDATE-MM-DD-YY and CURDATEO");
        requireAgreement(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH, UserListResponse.CURTIME_LENGTH,
                "WS-CURTIME-HH-MM-SS and CURTIMEO");

        // :568-569 move the two WS-VARIABLES literals into the header. Their own widths are the
        // authority for how wide those map items must be.
        requireAgreement(WS_TRANID.length(), UserListResponse.TRNNAME_LENGTH, "WS-TRANID and TRNNAMEO");
        requireAgreement(WS_PGMNAME.length(), UserListResponse.PGMNAME_LENGTH, "WS-PGMNAME and PGMNAMEO");

        // The three CSUSR01Y fields :388-438 project into each row, plus the key.
        requireAgreement(SecUserRecord.SEC_USR_ID_LENGTH, UserListResponse.USRID_LENGTH,
                "SEC-USR-ID and USRIDnn");
        requireAgreement(SecUserRecord.SEC_USR_FNAME_LENGTH, UserListResponse.FNAME_LENGTH,
                "SEC-USR-FNAME and FNAMEnn");
        requireAgreement(SecUserRecord.SEC_USR_LNAME_LENGTH, UserListResponse.LNAME_LENGTH,
                "SEC-USR-LNAME and LNAMEnn");
        requireAgreement(SecUserRecord.SEC_USR_TYPE_LENGTH, UserListResponse.UTYPE_LENGTH,
                "SEC-USR-TYPE and UTYPEnn");

        // The browse key. :590 passes KEYLENGTH(LENGTH OF SEC-USR-ID), and the two paging anchors and
        // the selected identifier are all the same width because each holds one such key.
        requireAgreement(SecUserRecord.KEY_LENGTH, SecUserRepository.KEY_LENGTH,
                "SEC-USR-ID and the repository's browse key length");
        requireAgreement(SecUserRecord.SEC_USR_ID_LENGTH, UserListResponse.CU00_USRID_FIRST_LENGTH,
                "SEC-USR-ID and CDEMO-CU00-USRID-FIRST");
        requireAgreement(SecUserRecord.SEC_USR_ID_LENGTH, UserListResponse.CU00_USRID_LAST_LENGTH,
                "SEC-USR-ID and CDEMO-CU00-USRID-LAST");
        requireAgreement(SecUserRecord.SEC_USR_ID_LENGTH, UserListResponse.CU00_USR_SELECTED_LENGTH,
                "SEC-USR-ID and CDEMO-CU00-USR-SELECTED");
        requireAgreement(SecUserRecord.SEC_USR_ID_LENGTH, UserListResponse.USRIDIN_LENGTH,
                "SEC-USR-ID and USRIDINO, which supplies the browse key at :221");

        // The 34-byte extension, and the 194-byte total it produces on top of COCOM01Y's 160.
        requireAgreement(UserListRequest.CU00_INFO_LENGTH, UserListResponse.CU00_INFO_LENGTH,
                "the width of CDEMO-CU00-INFO on the request and on the response");
        requireAgreement(NavigationContext.COMMAREA_LENGTH + UserListResponse.CU00_INFO_LENGTH,
                UserListResponse.CU00_COMMAREA_LENGTH,
                "COCOM01Y plus CDEMO-CU00-INFO and the declared CU00 communication-area length");

        // :327 and :376 move a PIC 9(08) into a PIC X(8). The two widths must match digit for character,
        // or the rendered page number would be padded or truncated.
        requireAgreement(UserListResponse.CU00_PAGE_NUM_DIGITS, UserListResponse.PAGENUM_LENGTH,
                "CDEMO-CU00-PAGE-NUM and PAGENUMO");

        // :526 narrows WS-MESSAGE into ERRMSGO. This must stay a NARROWING - if the message line ever
        // became at least as wide as WS-MESSAGE the truncation would silently stop happening.
        requireNarrowing(UserListResponse.ERRMSG_LENGTH, WS_MESSAGE_LENGTH,
                "ERRMSGO must stay narrower than WS-MESSAGE, because :526 truncates on the right");

        // Every message this screen can display must fit the field it is displayed in, or the text the
        // operator reads would differ from the text the source moves.
        for (String message : messageTexts()) {
            requireFits(message, UserListResponse.ERRMSG_LENGTH);
        }
    }

    /**
     * Checks the four navigation targets against the types that own them.
     *
     * <p>{@code CDEMO-TO-PROGRAM} is {@code PIC X(08)} and every target this program names is exactly
     * eight characters, so a target that did not fit would be truncated into a program name that does not
     * exist. The three that come from the response payload are additionally checked for identity, so a
     * change on either side is caught here rather than by a client following a wrong target.
     *
     * @throws IllegalStateException if any check fails
     */
    static void verifyNavigationContract() {
        for (String target : List.of(LIT_USER_UPDATE_PGM, LIT_USER_DELETE_PGM, LIT_SIGNON_PGM,
                LIT_ADMIN_MENU_PGM)) {
            requireAgreement(NavigationContext.TO_PROGRAM_LENGTH, target.length(),
                    "CDEMO-TO-PROGRAM and the transfer target '" + target + "'");
        }
        requireAgreement(NavigationContext.FROM_TRANID_LENGTH, WS_TRANID.length(),
                "CDEMO-FROM-TRANID and WS-TRANID, moved at :193 and :511");
        requireAgreement(NavigationContext.FROM_PROGRAM_LENGTH, WS_PGMNAME.length(),
                "CDEMO-FROM-PROGRAM and WS-PGMNAME, moved at :194 and :512");
    }

    /**
     * Every message text this screen can move into {@code WS-MESSAGE}.
     *
     * <p>Enumerated rather than derived, so that adding a message without checking it against the field
     * width is not possible: {@link #verifyScreenContract()} walks this list.
     *
     * @return the seven texts, in the order the paragraphs that set them appear in the source
     */
    static List<String> messageTexts() {
        return List.of(MSG_INVALID_KEY,
                MSG_INVALID_SELECTION,
                MSG_ALREADY_AT_TOP,
                MSG_ALREADY_AT_BOTTOM,
                MSG_AT_TOP,
                MSG_REACHED_BOTTOM,
                MSG_REACHED_TOP,
                MSG_UNABLE_TO_LOOKUP);
    }

    /**
     * Asserts that two widths or counts that must be equal are equal.
     *
     * <p>Package-visible rather than private for the same reason every paragraph of this class is: a guard
     * whose failure path has never been executed is a guard nobody has verified. A test drives it with
     * values that disagree, so the diagnostic is proved to arrive rather than assumed to.
     *
     * @param expected the value the authority declares
     * @param actual   the value the collaborating type declares
     * @param subject  what the two are, so a failure says which pair disagreed
     * @throws IllegalStateException if they differ
     */
    static void requireAgreement(int expected, int actual, String subject) {
        if (expected != actual) {
            throw new IllegalStateException(subject + " must agree, but they are " + expected + " and "
                    + actual + ". One of the two has drifted from the copybook it came from, and this "
                    + "screen's behaviour depends on them being the same.");
        }
    }

    /**
     * Asserts that a receiving field is strictly narrower than the field moved into it.
     *
     * @param narrower the receiving width
     * @param wider    the sending width
     * @param subject  the relationship being defended
     * @throws IllegalStateException if the ordering does not hold
     */
    static void requireNarrowing(int narrower, int wider, String subject) {
        if (narrower >= wider) {
            throw new IllegalStateException(subject + ", but " + narrower + " is not below " + wider
                    + ". Whether that MOVE truncates at all depends on this ordering.");
        }
    }

    /**
     * Asserts that a message text fits the field it is displayed in.
     *
     * @param message the text
     * @param width   the receiving field's declared width
     * @throws IllegalStateException if the text is too long
     */
    static void requireFits(String message, int width) {
        if (message.length() > width) {
            throw new IllegalStateException("Message text '" + message + "' is " + message.length()
                    + " characters and cannot be displayed in a PIC X(" + width + ") field without "
                    + "losing its last " + (message.length() - width) + ". The operator would read "
                    + "something the source does not say.");
        }
    }

    // =================================================================================================
    // THE HTTP SURFACE. Deliberately thin: it binds, it narrows the AID byte, and it delegates. Not one
    // decision is taken here, so every decision below is reachable from a plain JUnit test with no
    // MockMvc in the path. No servlet type appears in any signature - no HttpServletRequest, no
    // HttpServletResponse and above all no HttpSession, because the pseudo-conversation travels in the
    // payload.
    // =================================================================================================

    /**
     * {@code GET /api/users} - the REST projection of CSD transaction {@code CU00}
     * ({@code app/csd/CARDDEMO.CSD:449}).
     *
     * <p><strong>An absent body is the cold start.</strong> {@code app/cbl/COUSR00C.cbl:110} tests
     * {@code IF EIBCALEN = 0} to distinguish a transaction started fresh from one continuing a
     * pseudo-conversation, and a {@code GET} nobody sent a payload with is exactly that: the parameter is
     * {@code required = false} and arrives {@code null}, which {@link #listUsers(UserListRequest, byte)}
     * treats as {@code EIBCALEN = 0} and answers with the sign-on target. A request whose body is present
     * but whose {@code navigationContext} is {@code null} is the same state, because
     * {@link UserListRequest} deliberately carries that absence rather than substituting an empty area.
     *
     * <p>A continuing request sends back the payload it last received, carrying the communication area,
     * the {@code CDEMO-CU00} paging anchors and the screen the operator was looking at. Nothing is held
     * server-side between the two.
     *
     * <p><strong>Which key was pressed can be stated two ways, and both are honoured.</strong> A caller
     * that has the raw {@code EIBAID} byte sends it as the {@value #EIBAID_PARAM} parameter. A caller that
     * simply echoes the payload it was last given sends nothing of the kind - it sends
     * {@link UserListRequest#aid()}, the five-character {@code CCARD-AID} token this module publishes on
     * every response, because a raw byte cannot travel in JSON. Reading only the parameter made
     * {@code COUSR00C}'s {@code WHEN DFHPF7} and {@code WHEN DFHPF8} arms [{@code :122-131}] unreachable
     * to exactly the stateless client this projection is designed for: paging forward and back was
     * impossible without knowing an EBCDIC constant. The parameter still wins when it is supplied, since
     * it is the more precise statement; the token is decoded when it is not; and
     * {@link CicsAid#DFHENTER} is the default only when neither names a key.
     *
     * @param request the inbound screen and communication area, or {@code null} for the cold start
     * @param eibaid  the terminal's attention identifier as an unsigned byte {@code 0..255}, or
     *                {@code null} to take the key from {@link UserListRequest#aid()}
     * @return the {@code COUSR0AO} projection, or - on a transfer of control - the same payload with
     *         {@code nextProgram} naming where the client goes next
     * @throws IllegalArgumentException if {@code eibaid} is outside {@code 0..255}
     */
    @GetMapping(path = USER_LIST_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScreenResponse<UserListResponse> getUsers(
            @Valid @RequestBody(required = false) UserListRequest request,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid) {
        // The work area is created here rather than inside the two-argument overload so that the two
        // values COUSR00C sets which are presentation metadata - the cursor request and the ERASE choice -
        // are still reachable when the envelope is built. They are not payload members and never become
        // any, which is exactly why they need the envelope to travel at all.
        WorkArea ws = new WorkArea();
        UserListResponse painted = listUsers(request, resolveEibAid(eibaid, request), ws);
        return ScreenResponse.of(painted, screenMetadataOf(ws));
    }

    /**
     * This screen's presentation metadata, in the shared envelope every online response publishes.
     *
     * <p>{@code COUSR00} moves no attribute or colour byte of its own - the class documentation above
     * records that, and it is why {@code messageColour} is {@link BmsAttributes#DFHDFCOL}, the map's own
     * declared default, rather than a value this program chose. The field map is empty for the same
     * reason: there are no {@code xxxC}, {@code xxxP}, {@code xxxH} or {@code xxxV} items this program
     * writes, and an empty map states that accurately rather than omitting the question.
     *
     * <p>The two facts it does set are reported:
     *
     * <ul>
     *   <li>{@code MOVE -1 TO USRIDINL OF COUSR0AI} at {@code :108} and {@code :224} - the cursor
     *       request, named by its {@code DFHMDF} label. {@code USRIDINL} is
     *       {@code COMP PIC S9(4)} input-group metadata and never a payload member (gate G9), and
     *       {@link UserListRequest} rejects a negative length by construction, so this is the only route
     *       it has to a client.</li>
     *   <li>{@code SEND ... ERASE} versus {@code SEND ... } without it - reported as
     *       {@code resetAllOutputFields}. It is the terminal-level clear rather than the map-buffer
     *       {@code MOVE LOW-VALUES}, and it is the same instruction to a client: clear what is on the
     *       screen before painting what follows.</li>
     * </ul>
     *
     * @param ws the work area the execution ran in; must not be {@code null}
     * @return the metadata, never {@code null}
     * @throws NullPointerException if {@code ws} is {@code null}
     */
    static ScreenMetadata screenMetadataOf(WorkArea ws) {
        Objects.requireNonNull(ws, "A work area is required to read the metadata the execution set");
        String cursorField = ws.usrIdInLength() == CURSOR_ON_USRIDIN
                ? UserListResponse.USRIDIN_FIELD
                : null;
        return ScreenMetadata.of(cursorField, BmsAttributes.DFHDFCOL, ws.sendEraseYes());
    }

    /**
     * Resolves {@code EIBAID} from the two carriers a stateless caller has, in order of precedence.
     *
     * <ol>
     *   <li>The {@value #EIBAID_PARAM} parameter, as an unsigned {@code 0}-{@code 255} value. The most
     *       precise statement, so it wins whenever it is supplied. Its range is checked rather than
     *       silently wrapped, because {@code 300} is not an attention identifier and quietly becoming
     *       {@code 0x2C} would send the request down a branch the operator never asked for.</li>
     *   <li>{@link UserListRequest#aid()}, the five-character {@code CCARD-AID} token every response of
     *       this module publishes. A client that echoes what it was given states the key this way and no
     *       other, which is why ignoring it made {@code WHEN DFHPF7} and {@code WHEN DFHPF8}
     *       [{@code app/cbl/COUSR00C.cbl:122-131}] unreachable over HTTP.</li>
     *   <li>{@link CicsAid#DFHENTER}, when neither carrier names a key. A CICS terminal always presents
     *       some AID, and ENTER is the one this program handles first [{@code :123}] - the only default
     *       that cannot reach a branch the operator could not have reached.</li>
     * </ol>
     *
     * @param eibaid  the unsigned byte value, or {@code null} when the caller named none
     * @param request the payload, whose token is the second carrier; may be {@code null}
     * @return the raw AID byte
     * @throws IllegalArgumentException if {@code eibaid} is outside {@code 0..255}
     */
    static byte resolveEibAid(Integer eibaid, UserListRequest request) {
        if (eibaid != null) {
            int value = eibaid;
            if (value < AID_MIN || value > AID_MAX) {
                throw new IllegalArgumentException("The " + EIBAID_PARAM + " parameter carries one EIBAID "
                        + "byte and must be " + AID_MIN + " to " + AID_MAX + ", but was " + value
                        + ". Narrowing it silently would select an attention identifier the caller never "
                        + "pressed.");
            }
            return (byte) value;
        }
        if (request != null) {
            OptionalInt fromToken = aidByteOfToken(request.aid());
            if (fromToken.isPresent()) {
                return (byte) fromToken.getAsInt();
            }
        }
        return CicsAid.DFHENTER;
    }

    /**
     * Maps a {@code CCARD-AID} token back onto the {@code EIBAID} byte it stands for.
     *
     * <p>The inverse of {@link PfKeyResolver#resolve(byte)}, written against the tokens
     * {@link AidKey} itself publishes so the two cannot drift apart, and matched on the token's declared
     * {@code PIC X(5)} image so that both {@code "PA1"} and {@code "PA1  "} resolve - the copybook
     * literal carries two trailing spaces and a client may send either form.
     *
     * <p>Three inputs yield no key at all, and the caller falls back rather than guessing: {@code null},
     * a token that is blank or {@code LOW-VALUES}, and a token matching none of the sixteen. The last is
     * <strong>not</strong> mapped to {@link CicsAid#DFHNULL} here: this program's {@code WHEN OTHER} at
     * {@code :133-137} flags an error and re-sends, so answering an unintelligible token that way would
     * turn a caller's mistake into the operator-facing "invalid key" message. Falling back to ENTER
     * instead reaches the arm the program handles first, which is what a terminal presenting no
     * recognised key would have produced.
     *
     * @param token the token as received, of any length, or {@code null}
     * @return the byte the token stands for, or {@link OptionalInt#empty()} when it names no key
     */
    static OptionalInt aidByteOfToken(String token) {
        if (token == null) {
            return OptionalInt.empty();
        }
        String image = AID_TOKEN_RULES.movePicX(token, PfKeyResolver.AID_TOKEN_LENGTH);
        if (image.isBlank() || image.chars().allMatch(character -> character == 0)) {
            return OptionalInt.empty();
        }
        for (AidKey candidate : AidKey.values()) {
            if (candidate.token().equals(image)) {
                return OptionalInt.of(canonicalByteOf(candidate) & 0xFF);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * The {@code EIBAID} byte {@link PfKeyResolver#resolve(byte)} maps onto each token.
     *
     * <p>{@code CSSTRPFY} folds {@code DFHPF13}-{@code DFHPF24} onto {@code PFK01}-{@code PFK12}, so a
     * token has more than one possible origin; the low key of each pair is returned, which is the one the
     * resolver and every {@code EVALUATE EIBAID} in this program treat identically to its high twin.
     *
     * @param key the token's key; must not be {@code null}
     * @return the canonical raw AID byte
     */
    private static byte canonicalByteOf(AidKey key) {
        return switch (key) {
            case ENTER -> CicsAid.DFHENTER;
            case CLEAR -> CicsAid.DFHCLEAR;
            case PA1 -> CicsAid.DFHPA1;
            case PA2 -> CicsAid.DFHPA2;
            case PFK01 -> CicsAid.DFHPF1;
            case PFK02 -> CicsAid.DFHPF2;
            case PFK03 -> CicsAid.DFHPF3;
            case PFK04 -> CicsAid.DFHPF4;
            case PFK05 -> CicsAid.DFHPF5;
            case PFK06 -> CicsAid.DFHPF6;
            case PFK07 -> CicsAid.DFHPF7;
            case PFK08 -> CicsAid.DFHPF8;
            case PFK09 -> CicsAid.DFHPF9;
            case PFK10 -> CicsAid.DFHPF10;
            case PFK11 -> CicsAid.DFHPF11;
            case PFK12 -> CicsAid.DFHPF12;
        };
    }

    // =================================================================================================
    // MAIN-PARA - app/cbl/COUSR00C.cbl:98-144.
    //
    // The order of the first fifteen lines is load-bearing and is reproduced exactly. In particular
    // :100-103 set four flags INCLUDING SET NEXT-PAGE-NO, which writes a COMMUNICATION-AREA field - and
    // then :114 moves the inbound communication area over the top of it, restoring the caller's value.
    // Setting the flag after the move, which reads more naturally, would make PF8 believe there is never
    // a next page.
    // =================================================================================================

    /**
     * Runs the whole transaction and returns the screen, or the transfer target.
     *
     * <p>This is the decision entry point: it takes a payload and an AID byte, needs no HTTP and no
     * Spring context, and is what every behavioural test drives.
     *
     * @param incoming the inbound payload, or {@code null} for {@code EIBCALEN = 0}
     * @param eibAid   the raw {@code EIBAID} byte
     * @return the response; never {@code null}
     */
    UserListResponse listUsers(UserListRequest incoming, byte eibAid) {
        return listUsers(incoming, eibAid, new WorkArea());
    }

    /**
     * The same transaction, run against a caller-supplied work area so that every
     * {@code WORKING-STORAGE} value the COBOL sets is observable afterwards.
     *
     * <p>{@code COUSR00C} sets values that never reach the payload - the cursor marker
     * {@code USRIDINL}, the {@code SEND ... ERASE} choice, and the intermediate screen an end-of-file arm
     * sends from inside the paging loop - and this overload is how a test asserts them without a payload
     * member being invented to carry them. Production traffic uses
     * {@link #listUsers(UserListRequest, byte)}.
     *
     * @param incoming the inbound payload, or {@code null} for {@code EIBCALEN = 0}
     * @param eibAid   the raw {@code EIBAID} byte
     * @param ws       the work area to run in, in its freshly constructed state
     * @return the response; never {@code null}
     * @throws NullPointerException if {@code ws} is {@code null}
     */
    UserListResponse listUsers(UserListRequest incoming, byte eibAid, WorkArea ws) {
        Objects.requireNonNull(ws, "A work area is required; the initial state is new WorkArea()");

        // Not a COBOL statement: EIBAID is already in the exec interface block when the program starts, so
        // resolving it once here records what the terminal presented on every path - including the paths
        // that never reach the EVALUATE at :122 but do test EIBAID at :288 or :342. An AID the resolver
        // does not recognise leaves this empty and reaches WHEN OTHER, exactly as the inline tests do.
        ws.aidKey = PfKeyResolver.resolve(eibAid);

        // :100-103  SET ERR-FLG-OFF / USER-SEC-NOT-EOF / NEXT-PAGE-NO / SEND-ERASE-YES TO TRUE.
        ws.errFlgOn = false;
        ws.userSecEof = false;
        ws.nextPageYes = false;
        ws.sendEraseYes = true;

        // :105-106  MOVE SPACES TO WS-MESSAGE, ERRMSGO OF COUSR0AO.
        ws.message = spaces(WS_MESSAGE_LENGTH);
        ws.map.errMsg = spaces(UserListResponse.ERRMSG_LENGTH);

        // :108  MOVE -1 TO USRIDINL OF COUSR0AI.
        ws.usrIdInLength = CURSOR_ON_USRIDIN;

        // :110  IF EIBCALEN = 0. An absent payload, and a payload carrying no communication area, are
        // the same state: no area was passed. UserListRequest reports which of the two it is, and both
        // answer here.
        if (incoming == null || !incoming.hasNavigationContext()) {
            // :111  MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
            ws.commarea = ws.commarea.withToProgram(LIT_SIGNON_PGM);
            // :112  PERFORM RETURN-TO-PREV-SCREEN - an XCTL, so nothing below runs.
            return returnToPrevScreen(ws);
        }

        // :114  MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA. Every communication-area field,
        // including the whole 34-byte CDEMO-CU00-INFO group, comes from the caller here - which is what
        // overwrites the SET NEXT-PAGE-NO of :102.
        ws.acceptCommarea(incoming);

        // :115  IF NOT CDEMO-PGM-REENTER
        if (!ws.commarea.isReenter()) {
            // :116  SET CDEMO-PGM-REENTER TO TRUE
            ws.commarea = ws.commarea.withPgmReenter();
            // :117  MOVE LOW-VALUES TO COUSR0AO. The WHOLE map is blanked, so on first entry the
            // request's screen fields are deliberately discarded: USRIDINI and all ten SEL cells read as
            // low values, which is why a first entry always lists from the top of the file with no
            // selection acted on.
            ws.map.moveLowValues();
            // :118  PERFORM PROCESS-ENTER-KEY
            UserListResponse transfer = processEnterKey(ws, eibAid);
            // The transfer is honoured here because :118 performs the same paragraph :124 does, and that
            // paragraph can issue an XCTL. On THIS path it never does, and the reason is the statement
            // immediately above: :117 blanks the whole map, so all ten SEL cells read as low values, the
            // scan at :151-185 always reaches its WHEN OTHER, and the guard at :187-188 always fails.
            // The arm is therefore unreachable from a first entry - a property of the COBOL's own
            // sequencing, not of this translation - and it is kept rather than elided because eliding it
            // would make the two PERFORM sites differ where the source has them identical.
            if (transfer != null) {
                return transfer;
            }
            // :119  PERFORM SEND-USRLST-SCREEN
            sendUsrlstScreen(ws);
        } else {
            // :121  PERFORM RECEIVE-USRLST-SCREEN
            receiveUsrlstScreen(ws, incoming);

            // :122-137  EVALUATE EIBAID. Source order preserved, WHEN OTHER last. The keys are resolved
            // through the shared resolver even though this program tests EIBAID inline and does not COPY
            // CSSTRPFY: an AID the resolver does not recognise is not one of the four handled keys, so it
            // joins WHEN OTHER exactly as an inline test would put it there.
            if (PfKeyResolver.isEnter(eibAid)) {
                // :123-124  WHEN DFHENTER
                UserListResponse transfer = processEnterKey(ws, eibAid);
                if (transfer != null) {
                    return transfer;
                }
            } else if (PfKeyResolver.isPf3(eibAid)) {
                // :125-127  WHEN DFHPF3
                ws.commarea = ws.commarea.withToProgram(LIT_ADMIN_MENU_PGM);
                return returnToPrevScreen(ws);
            } else if (PfKeyResolver.isPf7(eibAid)) {
                // :128-129  WHEN DFHPF7
                processPf7Key(ws, eibAid);
            } else if (PfKeyResolver.isPf8(eibAid)) {
                // :130-131  WHEN DFHPF8
                processPf8Key(ws, eibAid);
            } else {
                // :132-136  WHEN OTHER
                ws.errFlgOn = true;                                   // :133
                ws.usrIdInLength = CURSOR_ON_USRIDIN;                 // :134
                ws.message = codec.movePicX(MSG_INVALID_KEY, WS_MESSAGE_LENGTH);  // :135
                sendUsrlstScreen(ws);                                 // :136
            }
        }

        // :141-144  EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA). The transaction ends
        // holding the terminal, so the client sends the payload back on the next keystroke. The body is
        // the last screen sent; where more than one was sent, ws.sends() has them all.
        return lastSentScreen(ws);
    }

    // =================================================================================================
    // PROCESS-ENTER-KEY - app/cbl/COUSR00C.cbl:149-232.
    //
    // Three parts in strict order: an ordered eleven-arm EVALUATE TRUE that finds the ticked row, a
    // four-arm EVALUATE on the selection character that either transfers control or complains, and then -
    // unconditionally, whether or not a row was ticked - a forward page from the top.
    // =================================================================================================

    /**
     * {@code :149-232} - the ENTER key: act on a ticked row, then list from the start key.
     *
     * @param ws     the work area
     * @param eibAid the raw {@code EIBAID} byte, which the forward page consults at {@code :288}
     * @return the transfer payload when {@code :196} or {@code :206} issued an {@code XCTL}, otherwise
     *         {@code null} to say the paragraph fell through to its end
     */
    UserListResponse processEnterKey(WorkArea ws, byte eibAid) {
        // :151-185  EVALUATE TRUE over SEL0001I .. SEL0010I.
        selectTickedRow(ws);

        // :187-216  IF the flag and the identifier are both non-blank, act on the selection.
        if (isNotBlank(ws.cu00UsrSelFlg) && isNotBlank(ws.cu00UsrSelected)) {
            // :189-215  EVALUATE CDEMO-CU00-USR-SEL-FLG. Source order, WHEN OTHER last.
            char selection = ws.cu00UsrSelFlg.charAt(0);
            if (selection == SEL_UPDATE_UPPER || selection == SEL_UPDATE_LOWER) {
                // :190-199  WHEN 'U' / WHEN 'u' - two labels, one action.
                return transferTo(ws, LIT_USER_UPDATE_PGM);
            } else if (selection == SEL_DELETE_UPPER || selection == SEL_DELETE_LOWER) {
                // :200-209  WHEN 'D' / WHEN 'd'
                return transferTo(ws, LIT_USER_DELETE_PGM);
            } else {
                // :210-214  WHEN OTHER. Note: no error flag is set here, so the paragraph carries on and
                // pages forward anyway - the complaint and a freshly listed page arrive together.
                ws.message = codec.movePicX(MSG_INVALID_SELECTION, WS_MESSAGE_LENGTH);
                ws.usrIdInLength = CURSOR_ON_USRIDIN;
            }
        }

        // :218-222  IF USRIDINI = SPACES OR LOW-VALUES -> start at the beginning of the file, otherwise
        // at what the operator typed.
        if (isBlank(ws.map.usrIdIn)) {
            ws.secUsrId = SecUserRepository.LOW_VALUES_KEY;
        } else {
            ws.secUsrId = codec.movePicX(ws.map.usrIdIn, SecUserRecord.SEC_USR_ID_LENGTH);
        }

        // :224  MOVE -1 TO USRIDINL OF COUSR0AI
        ws.usrIdInLength = CURSOR_ON_USRIDIN;

        // :227  MOVE 0 TO CDEMO-CU00-PAGE-NUM. The count restarts, so the page about to be built becomes
        // page 1 when :309 increments it.
        ws.cu00PageNum = UserListRequest.CU00_PAGE_NUM_INITIAL;

        // :228  PERFORM PROCESS-PAGE-FORWARD
        processPageForward(ws, eibAid);

        // :230-232  IF NOT ERR-FLG-ON -> MOVE SPACE TO USRIDINO. A single space into a PIC X(8) receiver
        // is padded to eight, so this blanks the field; :328 has already done it on the success path, and
        // this second move is what covers the path where :328 was not reached.
        if (!ws.errFlgOn) {
            ws.map.usrIdIn = codec.movePicX(String.valueOf(SPACE), UserListResponse.USRIDIN_LENGTH);
        }
        return null;
    }

    /**
     * {@code :151-185} - the ordered eleven-arm {@code EVALUATE TRUE} that finds the ticked row.
     *
     * <p><strong>First non-blank wins.</strong> {@code EVALUATE TRUE} evaluates its {@code WHEN}
     * conditions in source order and executes only the first that holds, so a screen with two rows ticked
     * acts on the lower-numbered one and the other is ignored without comment. This is <em>not</em> a
     * search for "the only" selection and multiple ticks are <em>not</em> rejected; scanning for a unique
     * selection, or complaining about two, would both be new behaviour.
     *
     * <p>Each arm tests {@code SEL000nI NOT = SPACES AND LOW-VALUES}, which is COBOL's abbreviated
     * combined relation for {@code NOT = SPACES AND NOT = LOW-VALUES} - see {@link #isNotBlank(String)}.
     *
     * <p>{@code WHEN OTHER} at {@code :182-184} clears <em>both</em> the flag and the identifier, which
     * matters: they arrived in the communication area from the previous cycle, and leaving them set would
     * re-transfer on a screen where nothing was ticked.
     *
     * @param ws the work area
     */
    void selectTickedRow(WorkArea ws) {
        for (int rowNumber = FIRST_ROW; rowNumber <= PAGE_SIZE; rowNumber++) {
            // One loop iteration is one WHEN arm: :152-154 for row 1, :155-157 for row 2, and so on to
            // :179-181 for row 10. The arms are byte-identical apart from the subscript, so they are one
            // loop rather than ten copies - the ORDER, which is what the semantics rest on, is preserved
            // because the loop ascends and returns on the first match.
            if (isNotBlank(ws.map.sel[rowNumber - 1])) {
                ws.cu00UsrSelFlg = codec.movePicX(ws.map.sel[rowNumber - 1],
                        UserListResponse.CU00_USR_SEL_FLG_LENGTH);
                ws.cu00UsrSelected = codec.movePicX(ws.map.usrId[rowNumber - 1],
                        UserListResponse.CU00_USR_SELECTED_LENGTH);
                ws.selectedRow = rowNumber;
                return;
            }
        }
        // :182-184  WHEN OTHER - no row was ticked.
        ws.cu00UsrSelFlg = spaces(UserListResponse.CU00_USR_SEL_FLG_LENGTH);
        ws.cu00UsrSelected = spaces(UserListResponse.CU00_USR_SELECTED_LENGTH);
        ws.selectedRow = 0;
    }

    // =================================================================================================
    // PROCESS-PF7-KEY - app/cbl/COUSR00C.cbl:237-255.
    // =================================================================================================

    /**
     * {@code :237-255} - PF7, page backward.
     *
     * <p>The browse is anchored on {@code CDEMO-CU00-USRID-FIRST}, the identifier row 1 of the displayed
     * page carries, or at the start of the file when that anchor is blank - which is the state of a page
     * that was never filled.
     *
     * <p>{@code SET NEXT-PAGE-YES TO TRUE} at {@code :245} is unconditional and correct: paging back
     * always leaves a page ahead, because the page being left is that page. It is also what
     * {@code :364}'s {@code IF NEXT-PAGE-YES} then tests, so the two are read together.
     *
     * @param ws     the work area
     * @param eibAid the raw {@code EIBAID} byte, which {@code :342} consults
     */
    void processPf7Key(WorkArea ws, byte eibAid) {
        // :239-243
        if (isBlank(ws.cu00UsrIdFirst)) {
            ws.secUsrId = SecUserRepository.LOW_VALUES_KEY;
        } else {
            ws.secUsrId = codec.movePicX(ws.cu00UsrIdFirst, SecUserRecord.SEC_USR_ID_LENGTH);
        }

        ws.nextPageYes = true;                        // :245
        ws.usrIdInLength = CURSOR_ON_USRIDIN;         // :246

        // :248-255
        if (ws.cu00PageNum > 1) {
            processPageBackward(ws, eibAid);
        } else {
            ws.message = codec.movePicX(MSG_ALREADY_AT_TOP, WS_MESSAGE_LENGTH);  // :251-252
            ws.sendEraseYes = false;                                             // :253
            sendUsrlstScreen(ws);                                                // :254
        }
    }

    // =================================================================================================
    // PROCESS-PF8-KEY - app/cbl/COUSR00C.cbl:260-277.
    // =================================================================================================

    /**
     * {@code :260-277} - PF8, page forward.
     *
     * <p><strong>The {@code HIGH-VALUES} anchor finds nothing, and that is the behaviour.</strong>
     * {@code :262-266} anchors the browse on {@code CDEMO-CU00-USRID-LAST} or, when that is blank, on
     * {@code HIGH-VALUES}. With the greater-than-or-equal positioning that {@code STARTBR} defaults to -
     * {@code GTEQ} is commented out at {@code :592}, which selects the default it would have requested -
     * no key can be at or after high values, so the {@code STARTBR} reports {@code NOTFND} and
     * {@code :600-606} paints "You are at the top of the page..." on a request to page <em>forward</em>.
     * Odd, and preserved (practice B5).
     *
     * <p>Because that arm sets no error flag, {@code PROCESS-PAGE-FORWARD} continues past it: with
     * {@code EIBAID} equal to PF8 the test at {@code :288} holds, a {@code READNEXT} is issued against a
     * browse that was never established, and its {@code WHEN OTHER} arm overwrites the message with
     * "Unable to lookup User..." and raises the error flag. Both sends are recorded, in that order, and
     * the final payload carries the second message. Following the source literally is the point: the
     * {@code READNEXT} at {@code :288} is guarded by {@code IF NOT ERR-FLG-ON} alone and by nothing that
     * tests end-of-file.
     *
     * @param ws     the work area
     * @param eibAid the raw {@code EIBAID} byte, which {@code :288} consults
     */
    void processPf8Key(WorkArea ws, byte eibAid) {
        // :262-266
        if (isBlank(ws.cu00UsrIdLast)) {
            ws.secUsrId = SecUserRepository.HIGH_VALUES_KEY;
        } else {
            ws.secUsrId = codec.movePicX(ws.cu00UsrIdLast, SecUserRecord.SEC_USR_ID_LENGTH);
        }

        ws.usrIdInLength = CURSOR_ON_USRIDIN;         // :268

        // :270-277
        if (ws.nextPageYes) {
            processPageForward(ws, eibAid);
        } else {
            ws.message = codec.movePicX(MSG_ALREADY_AT_BOTTOM, WS_MESSAGE_LENGTH);  // :273-274
            ws.sendEraseYes = false;                                                // :275
            sendUsrlstScreen(ws);                                                   // :276
        }
    }

    // =================================================================================================
    // PROCESS-PAGE-FORWARD - app/cbl/COUSR00C.cbl:282-331.
    //
    // Six steps, and the order of every one of them is observable. Two of the reads exist only to move
    // the browse position: the one at :289 discards the anchor record, and the one at :311 looks ahead to
    // decide whether a further page exists. Dropping either shifts the whole page by one record.
    // =================================================================================================

    /**
     * {@code :282-331} - fills the ten rows with the ten records at or after {@code SEC-USR-ID}.
     *
     * <p>The page number is incremented on <strong>two</strong> different paths, and both are needed:
     * <ul>
     *   <li>{@code :309-310}, when the fill loop ran out of rows rather than out of records - a full page,
     *       with more to come;</li>
     *   <li>{@code :320-321}, when it ran out of records but had placed at least one - a short final page.
     *       The {@code IF WS-IDX > 1} guard is what distinguishes that from a page that found nothing at
     *       all, which must leave the count alone.</li>
     * </ul>
     *
     * @param ws     the work area
     * @param eibAid the raw {@code EIBAID} byte
     */
    void processPageForward(WorkArea ws, byte eibAid) {
        // :284  PERFORM STARTBR-USER-SEC-FILE
        BrowseCursor cursor = startBrowseUserSecFile(ws);

        try {
            pageForwardOverBrowse(ws, eibAid, cursor);
        } finally {
            releaseBrowse(cursor);
        }
    }

    /**
     * Everything {@code PROCESS-PAGE-FORWARD} performs once the {@code STARTBR} has been issued.
     *
     * <p>Split out so the browse can be released on every exit - including {@code :286}'s early return
     * and any fault raised by a {@code READNEXT} - without the paragraph body acquiring a nesting level
     * it does not have in the source.
     *
     * @param ws     the work area
     * @param eibAid the raw {@code EIBAID} byte
     * @param cursor the browse the {@code STARTBR} returned
     */
    private void pageForwardOverBrowse(WorkArea ws, byte eibAid, BrowseCursor cursor) {

        // :286  IF NOT ERR-FLG-ON. Note what this does NOT test: end of file. A STARTBR that reported
        // NOTFND sets the end-of-file flag but no error flag, so this body still runs - see
        // processPf8Key(WorkArea, byte).
        if (ws.errFlgOn) {
            return;
        }

        // :288-290  IF EIBAID NOT = DFHENTER AND DFHPF7 AND DFHPF3 -> one READNEXT.
        //
        // That is COBOL's abbreviated combined relation, and it expands to
        //     EIBAID NOT = DFHENTER AND EIBAID NOT = DFHPF7 AND EIBAID NOT = DFHPF3
        // so it holds only when the AID is NONE of the three. The truth table:
        //     ENTER -> false   (arrived from :124, the anchor is a start key, so keep the first record)
        //     PF7   -> false   (arrived from :249 by way of :364 - not a forward page from a PF7 anchor)
        //     PF3   -> false   (never reaches here; PF3 returns at :127)
        //     PF8   -> TRUE    (the anchor IS the last row already displayed, so discard it)
        //     other -> TRUE
        // Reading it as "not equal to ENTER, or PF7, or PF3" would invert the PF8 case and shift the page
        // by exactly one record - the defect this comment exists to prevent.
        if (!PfKeyResolver.isEnter(eibAid) && !PfKeyResolver.isPf7(eibAid) && !PfKeyResolver.isPf3(eibAid)) {
            readNextUserSecFile(ws, cursor);
        }

        // :292-296  IF USER-SEC-NOT-EOF AND ERR-FLG-OFF -> blank all ten rows.
        //
        // The guard is deliberate and consequential: when the read above hit the end of the file, the ten
        // rows are NOT blanked and keep whatever the request carried, so the operator's screen is left
        // as-is under the new message rather than being wiped. PERFORM VARYING tests before the body, so
        // it runs for WS-IDX 1 through 10 and leaves WS-IDX at 11 - which :298 then overwrites.
        if (!ws.userSecEof && !ws.errFlgOn) {
            for (ws.idx = FIRST_ROW; ws.idx <= PAGE_SIZE; ws.idx++) {
                initializeUserData(ws);
            }
        }

        // :298  MOVE 1 TO WS-IDX
        ws.idx = FIRST_ROW;

        // :300-306  PERFORM UNTIL WS-IDX >= 11 OR USER-SEC-EOF OR ERR-FLG-ON.
        // Test-before, so a loop that starts at end of file never reads. The read happens FIRST and the
        // subscript only advances on a record, so a failed read leaves the row it would have filled blank.
        while (ws.idx < FORWARD_LOOP_BOUND && !ws.userSecEof && !ws.errFlgOn) {
            ReadResult read = readNextUserSecFile(ws, cursor);
            if (!ws.userSecEof && !ws.errFlgOn) {
                populateUserData(ws, read.requireRecord());   // :303
                ws.idx = ws.idx + 1;                          // :304  COMPUTE WS-IDX = WS-IDX + 1
            }
        }

        // :308-323
        if (!ws.userSecEof && !ws.errFlgOn) {
            // :309-310  A full page: count it, then look one record ahead.
            ws.cu00PageNum = ws.cu00PageNum + 1;
            // :311  PERFORM READNEXT-USER-SEC-FILE. This read exists ONLY to answer "is there another
            // page?" - the record it returns is discarded, and its end-of-file arm is what puts "You have
            // reached the bottom of the page..." on a page that is full but final.
            readNextUserSecFile(ws, cursor);
            // :312-316
            ws.nextPageYes = !ws.userSecEof && !ws.errFlgOn;
        } else {
            // :318  SET NEXT-PAGE-NO TO TRUE
            ws.nextPageYes = false;
            // :319-322  IF WS-IDX > 1 -> count the short page. WS-IDX is still 1 when the very first read
            // found nothing, and then the count must not move: the operator is being shown the page they
            // were already on.
            if (ws.idx > FIRST_ROW) {
                ws.cu00PageNum = ws.cu00PageNum + 1;
            }
        }

        // :325  PERFORM ENDBR-USER-SEC-FILE
        endBrowse(cursor);

        // :327  MOVE CDEMO-CU00-PAGE-NUM TO PAGENUMI OF COUSR0AI. A PIC 9(08) sender into a PIC X(8)
        // receiver, so the eight-digit zero-filled image is what reaches the screen. It names the INPUT
        // item, which works because the I and O items overlay - see the class documentation.
        ws.map.pageNum = codec.movePic9(ws.cu00PageNum, UserListResponse.PAGENUM_LENGTH);

        // :328  MOVE SPACE TO USRIDINO OF COUSR0AO - the start-key field is cleared once the page it
        // asked for has been built. PROCESS-PAGE-BACKWARD deliberately does NOT do this.
        ws.map.usrIdIn = codec.movePicX(String.valueOf(SPACE), UserListResponse.USRIDIN_LENGTH);

        // :329  PERFORM SEND-USRLST-SCREEN
        sendUsrlstScreen(ws);
    }

    // =================================================================================================
    // PROCESS-PAGE-BACKWARD - app/cbl/COUSR00C.cbl:336-379.
    //
    // The mirror of the forward page, with three differences that are easy to lose: the abbreviated
    // relation at :342 names TWO operands and not three, the fill runs from row 10 DOWN to row 1, and the
    // read-behind at :363 is nested inside its own IF at :362.
    // =================================================================================================

    /**
     * {@code :336-379} - fills the ten rows with the ten records before {@code SEC-USR-ID}.
     *
     * <p><strong>The fill descends.</strong> {@code MOVE 10 TO WS-IDX} at {@code :352} and
     * {@code COMPUTE WS-IDX = WS-IDX - 1} at {@code :358} mean the first record read - the highest key
     * below the anchor - lands in row 10 and each lower key lands one row higher, so the finished page
     * reads in ascending key order exactly as a forward page does.
     *
     * <p><strong>A short backward page leaves its leading rows blank.</strong> Fewer than ten records
     * below the anchor exhausts the file before {@code WS-IDX} reaches 1, so rows 1 upwards stay blank and
     * the data sits at the bottom of the screen - and, because row 1 is what sets it,
     * {@code CDEMO-CU00-USRID-FIRST} is never written on such a page. Both are behaviour, not defects.
     *
     * @param ws     the work area
     * @param eibAid the raw {@code EIBAID} byte
     */
    void processPageBackward(WorkArea ws, byte eibAid) {
        // :338  PERFORM STARTBR-USER-SEC-FILE
        BrowseCursor cursor = startBrowseUserSecFile(ws);

        try {
            pageBackwardOverBrowse(ws, eibAid, cursor);
        } finally {
            releaseBrowse(cursor);
        }
    }

    /**
     * Everything {@code PROCESS-PAGE-BACKWARD} performs once the {@code STARTBR} has been issued.
     *
     * <p>Split out for the same reason as its forward counterpart: {@code :340}'s early return and any
     * fault from a {@code READPREV} both bypass the {@code ENDBR} at {@code :374}.
     *
     * @param ws     the work area
     * @param eibAid the raw {@code EIBAID} byte
     * @param cursor the browse the {@code STARTBR} returned
     */
    private void pageBackwardOverBrowse(WorkArea ws, byte eibAid, BrowseCursor cursor) {

        // :340  IF NOT ERR-FLG-ON
        if (ws.errFlgOn) {
            return;
        }

        // :342-344  IF EIBAID NOT = DFHENTER AND DFHPF8 -> one READPREV.
        //
        // TWO operands here, not the three of :288. Expanded:
        //     EIBAID NOT = DFHENTER AND EIBAID NOT = DFHPF8
        //     PF7   -> TRUE    (the anchor IS row 1 of the page being left, so discard it)
        //     ENTER -> false
        //     PF8   -> false
        //     other -> TRUE
        // PF7 is the only key that reaches this paragraph in practice, and it is precisely the key the
        // test lets through.
        if (!PfKeyResolver.isEnter(eibAid) && !PfKeyResolver.isPf8(eibAid)) {
            readPrevUserSecFile(ws, cursor);
        }

        // :346-350  Blank all ten rows, under the same end-of-file guard as the forward page. The blanking
        // loop itself still ASCENDS - it is PERFORM VARYING FROM 1 BY 1 in both paragraphs - and only the
        // fill descends.
        if (!ws.userSecEof && !ws.errFlgOn) {
            for (ws.idx = FIRST_ROW; ws.idx <= PAGE_SIZE; ws.idx++) {
                initializeUserData(ws);
            }
        }

        // :352  MOVE 10 TO WS-IDX
        ws.idx = PAGE_SIZE;

        // :354-360  PERFORM UNTIL WS-IDX <= 0 OR USER-SEC-EOF OR ERR-FLG-ON
        while (ws.idx > BACKWARD_LOOP_BOUND && !ws.userSecEof && !ws.errFlgOn) {
            ReadResult read = readPrevUserSecFile(ws, cursor);
            if (!ws.userSecEof && !ws.errFlgOn) {
                populateUserData(ws, read.requireRecord());   // :357
                ws.idx = ws.idx - 1;                          // :358  COMPUTE WS-IDX = WS-IDX - 1
            }
        }

        // :362-372. The read-behind is INSIDE this IF, and IF NEXT-PAGE-YES is nested inside that again.
        // The nesting is what the source indentation at :362 obscures and what the END-IF at :372 settles,
        // and it matters: after a backward page that hit the start of the file, no further read is issued
        // and the page number is left exactly as it was.
        if (!ws.userSecEof && !ws.errFlgOn) {
            // :363  PERFORM READPREV-USER-SEC-FILE - a look-behind, like :311's look-ahead. Its record is
            // discarded and only its outcome is used.
            readPrevUserSecFile(ws, cursor);
            // :364  IF NEXT-PAGE-YES. Always true when arriving from :245, which sets it unconditionally.
            if (ws.nextPageYes) {
                // :365-370
                if (!ws.userSecEof && !ws.errFlgOn && ws.cu00PageNum > 1) {
                    // :367  SUBTRACT 1 FROM CDEMO-CU00-PAGE-NUM. The > 1 guard keeps this away from zero,
                    // which a PIC 9(08) unsigned field could not represent as a decrement.
                    ws.cu00PageNum = ws.cu00PageNum - 1;
                } else {
                    // :369  MOVE 1 TO CDEMO-CU00-PAGE-NUM - the start of the file has been reached, so
                    // this is page 1 by definition however the count had drifted.
                    ws.cu00PageNum = 1;
                }
            }
        }

        // :374  PERFORM ENDBR-USER-SEC-FILE
        endBrowse(cursor);

        // :376  MOVE CDEMO-CU00-PAGE-NUM TO PAGENUMI OF COUSR0AI
        ws.map.pageNum = codec.movePic9(ws.cu00PageNum, UserListResponse.PAGENUM_LENGTH);

        // :377  PERFORM SEND-USRLST-SCREEN. Note the absence of :328's MOVE SPACE TO USRIDINO: a backward
        // page leaves the start-key field showing whatever it showed.
        sendUsrlstScreen(ws);
    }

    // =================================================================================================
    // POPULATE-USER-DATA and INITIALIZE-USER-DATA - app/cbl/COUSR00C.cbl:384-441 and :446-501.
    //
    // Each is an EVALUATE WS-IDX with ten identical arms and a WHEN OTHER -> CONTINUE. WS-IDX IS ONE
    // BASED: WHEN 1 writes USRID01I. The DTO pair addresses rows by that same one-based number, so the
    // single conversion to a Java index lives in one expression per method and nowhere else.
    // =================================================================================================

    /**
     * {@code :384-441} - writes one record into the row {@code WS-IDX} names.
     *
     * <p><strong>Two arms do more than the other eight.</strong> {@code :388-389} is one {@code MOVE} with
     * <em>two</em> receivers - {@code USRID01I} and {@code CDEMO-CU00-USRID-FIRST} - and {@code :434-435}
     * is the same shape for {@code USRID10I} and {@code CDEMO-CU00-USRID-LAST}. Those two anchors are
     * exactly what PF7 and PF8 start their browses from, so an off-by-one here would not corrupt the
     * display; it would silently break paging in a way that only shows up two keystrokes later. Verify
     * both the first and the last element.
     *
     * <p>{@code WHEN OTHER -> CONTINUE} at {@code :439-440} is a real arm and is reachable: a subscript
     * outside 1 to 10 leaves the screen untouched rather than failing. It is preserved as a no-op and not
     * turned into an exception.
     *
     * @param ws     the work area, whose {@code idx} selects the row
     * @param record the record just read
     * @throws NullPointerException if {@code record} is {@code null}
     */
    void populateUserData(WorkArea ws, SecUserRecord record) {
        Objects.requireNonNull(record, "POPULATE-USER-DATA writes SEC-USER-DATA, which the READNEXT or "
                + "READPREV that preceded it has already filled");
        if (ws.idx < FIRST_ROW || ws.idx > PAGE_SIZE) {
            // :439-440  WHEN OTHER -> CONTINUE.
            return;
        }
        int index = ws.idx - 1;
        ws.map.usrId[index] = codec.movePicX(record.secUsrId(), UserListResponse.USRID_LENGTH);
        ws.map.fname[index] = codec.movePicX(record.secUsrFname(), UserListResponse.FNAME_LENGTH);
        ws.map.lname[index] = codec.movePicX(record.secUsrLname(), UserListResponse.LNAME_LENGTH);
        ws.map.utype[index] = codec.movePicX(record.secUsrType(), UserListResponse.UTYPE_LENGTH);

        if (ws.idx == FIRST_ROW) {
            // :388-389  the second receiver of the WHEN 1 arm's first MOVE.
            ws.cu00UsrIdFirst = codec.movePicX(record.secUsrId(),
                    UserListResponse.CU00_USRID_FIRST_LENGTH);
        }
        if (ws.idx == PAGE_SIZE) {
            // :434-435  the second receiver of the WHEN 10 arm's first MOVE.
            ws.cu00UsrIdLast = codec.movePicX(record.secUsrId(), UserListResponse.CU00_USRID_LAST_LENGTH);
        }
    }

    /**
     * {@code :446-501} - blanks the four data cells of the row {@code WS-IDX} names.
     *
     * <p>The paragraph moves {@code SPACES} into {@code USRIDnnI}, {@code FNAMEnnI}, {@code LNAMEnnI} and
     * {@code UTYPEnnI} and <strong>touches nothing else</strong>. Two omissions are deliberate and are
     * reproduced: the selection cell {@code SEL000nI} keeps whatever the operator typed, and neither
     * paging anchor is cleared - so a page that finds nothing leaves both anchors pointing where they
     * pointed.
     *
     * <p>{@code WHEN OTHER -> CONTINUE} at {@code :499-500} is a real arm here too.
     *
     * @param ws the work area, whose {@code idx} selects the row
     */
    void initializeUserData(WorkArea ws) {
        if (ws.idx < FIRST_ROW || ws.idx > PAGE_SIZE) {
            // :499-500  WHEN OTHER -> CONTINUE.
            return;
        }
        int index = ws.idx - 1;
        ws.map.usrId[index] = spaces(UserListResponse.USRID_LENGTH);
        ws.map.fname[index] = spaces(UserListResponse.FNAME_LENGTH);
        ws.map.lname[index] = spaces(UserListResponse.LNAME_LENGTH);
        ws.map.utype[index] = spaces(UserListResponse.UTYPE_LENGTH);
    }

    // =================================================================================================
    // THE THREE TRANSFER SITES - EXEC CICS XCTL at :196, :206 and :514.
    //
    // XCTL does not return, so each of these is the last thing the program does: the statements after the
    // PERFORM never run and neither does the EXEC CICS RETURN at :141. In the stateless projection the
    // transfer becomes a nextProgram field on the response and the client issues the follow-up call -
    // there is no server-side forward, no redirect and no session affinity.
    // =================================================================================================

    /**
     * {@code :190-209} - a row was ticked with {@code U}, {@code u}, {@code D} or {@code d}, so transfer.
     *
     * <p>The four communication-area moves are identical in both arms ({@code :192-195} and
     * {@code :202-205}), so they share this method and only the target differs.
     *
     * @param ws      the work area
     * @param program {@code 'COUSR02C'} for an update or {@code 'COUSR03C'} for a delete
     * @return the payload naming the target; never {@code null}
     */
    UserListResponse transferTo(WorkArea ws, String program) {
        ws.commarea = ws.commarea
                .withToProgram(program)             // :192 / :202
                .withFromTranid(WS_TRANID)          // :193 / :203
                .withFromProgram(WS_PGMNAME)        // :194 / :204
                .withPgmContext(NavigationContext.PGM_CONTEXT_ENTER);  // :195 / :205  MOVE 0
        // :196-199 / :206-209  EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)
        return transferred(ws, program);
    }

    /**
     * {@code :506-517} - {@code RETURN-TO-PREV-SCREEN}, reached from the cold start at {@code :112} and
     * from PF3 at {@code :127}.
     *
     * <p>{@code :508-510} defaults the target to the sign-on program when the caller named none, which is
     * what makes a communication area arriving with a blank {@code CDEMO-TO-PROGRAM} land somewhere real
     * rather than transferring to a program whose name is eight spaces.
     *
     * @param ws the work area
     * @return the payload naming the target; never {@code null}
     */
    UserListResponse returnToPrevScreen(WorkArea ws) {
        // :508-510  IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
        if (isBlank(ws.commarea.toProgram())) {
            ws.commarea = ws.commarea.withToProgram(LIT_SIGNON_PGM);
        }
        ws.commarea = ws.commarea
                .withFromTranid(WS_TRANID)          // :511
                .withFromProgram(WS_PGMNAME)        // :512
                .withPgmContext(NavigationContext.PGM_CONTEXT_ENTER);  // :513  MOVE ZEROS
        // :514-517  EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)
        return transferred(ws, ws.commarea.toProgram());
    }

    /**
     * Renders the payload an {@code XCTL} leaves behind.
     *
     * <p>{@code COMMAREA(CARDDEMO-COMMAREA)} is what actually crosses the transfer, so the shared area and
     * the whole 34-byte {@code CDEMO-CU00-INFO} group are carried - the target program needs them, and the
     * client needs them to come back here afterwards. The map is carried too: it did not cross the
     * transfer in CICS, but it is the operator's screen and the caller has no other way to see the state
     * the transfer was decided from.
     *
     * <p><strong>{@code nextMapset} and {@code nextMap} stay blank, deliberately.</strong>
     * {@code COUSR00C} names neither: it never writes {@code CDEMO-LAST-MAP} or
     * {@code CDEMO-LAST-MAPSET} - verified across the whole file - and has no {@code CCARD-NEXT-*}
     * equivalent. Filling them in would invent a navigation instruction the source does not give.
     *
     * <p>No screen is sent, which is why {@link WorkArea#sends()} is empty on every transfer path.
     *
     * @param ws      the work area
     * @param program the {@code XCTL} target
     * @return the payload; never {@code null}
     */
    UserListResponse transferred(WorkArea ws, String program) {
        ws.transferred = true;
        return ws.render()
                .nextProgram(codec.movePicX(program, UserListResponse.NEXT_PROGRAM_LENGTH))
                .build();
    }

    // =================================================================================================
    // RECEIVE-USRLST-SCREEN and SEND-USRLST-SCREEN - app/cbl/COUSR00C.cbl:549-557 and :522-544.
    // =================================================================================================

    /**
     * {@code :549-557} - {@code EXEC CICS RECEIVE MAP INTO(COUSR0AI)}.
     *
     * <p>The inbound payload <em>is</em> the received map, so this copies the operator's fifty-nine field
     * values into the single symbolic-map storage. {@code RESP} and {@code RESP2} are captured because the
     * command asks for them, and - faithfully - the program then <strong>never tests them</strong>: there
     * is no {@code EVALUATE} after this paragraph. A receive that failed would leave the map holding what
     * it held, which is the state this method reproduces when the payload is absent.
     *
     * @param ws       the work area
     * @param incoming the inbound payload
     */
    void receiveUsrlstScreen(WorkArea ws, UserListRequest incoming) {
        ws.respCd = FileStatus.NORMAL;
        ws.reasCd = FileStatus.NO_REASON_CODE;
        if (incoming == null) {
            return;
        }
        ws.map.receive(incoming, codec);
    }

    /**
     * {@code :522-544} - {@code EXEC CICS SEND MAP}, with or without {@code ERASE}.
     *
     * <p>Three steps: populate the header, narrow the message into the message line, and transmit.
     *
     * <p>{@code :526 MOVE WS-MESSAGE TO ERRMSGO} moves a {@code PIC X(80)} sender into a
     * {@code PIC X(78)} receiver, so the last two characters of the eighty-character image are discarded -
     * <strong>truncation on the right</strong>, which is the COBOL rule for an alphanumeric receiver and
     * the opposite of the numeric one. It is done in two explicit steps through the codec so the direction
     * is chosen rather than inherited: compose the eighty-character image the way {@code WS-MESSAGE} holds
     * it, then narrow that image to seventy-eight.
     *
     * <p>The {@code ERASE} choice at {@code :528-544} has no HTTP equivalent, so it is recorded on the
     * work area alongside the screen it applied to rather than dropped.
     *
     * @param ws the work area
     */
    void sendUsrlstScreen(WorkArea ws) {
        populateHeaderInfo(ws);                                             // :524

        // :526  the 80-to-78 narrowing, in the two moves it really is.
        String message80 = codec.movePicX(ws.message, WS_MESSAGE_LENGTH);
        ws.map.errMsg = codec.movePicX(message80, UserListResponse.ERRMSG_LENGTH);

        // :528-544  SEND MAP('COUSR0A') MAPSET('COUSR00') FROM(COUSR0AO) [ERASE] CURSOR.
        ws.sends.add(new SentScreen(ws.render().build(), ws.sendEraseYes, ws.usrIdInLength));
    }

    /**
     * {@code :562-581} - {@code POPULATE-HEADER-INFO}.
     *
     * <p>{@code :564} reads {@code FUNCTION CURRENT-DATE} once per send, so a screen sent twice within one
     * transaction can legitimately carry two different times. The value comes from the injected clock, and
     * the two composed images - {@code MM/DD/YY} from {@code :571-575} and {@code HH:MM:SS} from
     * {@code :577-581} - are assembled by {@link DateHeader}, which owns that composition including the
     * {@code WS-CURDATE-YEAR(3:2)} reference-modification that turns a four-digit year into two.
     *
     * <p>This screen has <strong>no</strong> {@code APPLID} or {@code SYSID} field: {@code COUSR00C}
     * issues no {@code EXEC CICS ASSIGN}, unlike several of its siblings, so none is populated.
     *
     * @param ws the work area
     */
    void populateHeaderInfo(WorkArea ws) {
        DateHeader header = DateHeader.from(codec, clock);                   // :564
        ws.map.title01 = codec.movePicX(ScreenTitles.CCDA_TITLE01, UserListResponse.TITLE01_LENGTH);
        ws.map.title02 = codec.movePicX(ScreenTitles.CCDA_TITLE02, UserListResponse.TITLE02_LENGTH);
        ws.map.trnName = codec.movePicX(WS_TRANID, UserListResponse.TRNNAME_LENGTH);      // :568
        ws.map.pgmName = codec.movePicX(WS_PGMNAME, UserListResponse.PGMNAME_LENGTH);     // :569
        ws.map.curDate = codec.movePicX(header.wsCurdateMmDdYy(), UserListResponse.CURDATE_LENGTH);
        ws.map.curTime = codec.movePicX(header.wsCurtimeHhMmSs(), UserListResponse.CURTIME_LENGTH);
    }

    /**
     * The screen the terminal is left displaying: the last one sent.
     *
     * <p>Every non-transferring path through {@code MAIN-PARA} sends at least once, so this normally reads
     * the tail of {@link WorkArea#sends()}. The one path that sends nothing is the ENTER arm reached with a
     * {@code STARTBR} that raised the error flag, whose {@code IF NOT ERR-FLG-ON} at {@code :286} skips the
     * send at {@code :329} - and even there the failing browse arm itself has already sent. Should a caller
     * still reach the end having sent nothing, the current map state is rendered, because
     * {@code EXEC CICS RETURN} at {@code :141} returns the screen as it stands rather than nothing at all.
     *
     * @param ws the work area
     * @return the payload; never {@code null}
     */
    UserListResponse lastSentScreen(WorkArea ws) {
        if (ws.sends.isEmpty()) {
            return ws.render().build();
        }
        return ws.sends.get(ws.sends.size() - 1).screen();
    }

    // =================================================================================================
    // THE FOUR BROWSE PARAGRAPHS - app/cbl/COUSR00C.cbl:586-691.
    //
    // Each of the first three is an EXEC CICS command followed by an EVALUATE WS-RESP-CD with three arms.
    // Two properties of those EVALUATEs are load-bearing and are the classic way to get this wrong:
    //
    //   1. THE BARE `CONTINUE` IS A NO-OP, NOT A BRANCH TERMINATOR. :601, :635 and :669 each open their
    //      arm with `CONTINUE` and the statements after it - the flag, the message, the cursor and the
    //      send - all execute. COBOL's CONTINUE does nothing whatsoever; it is not a `break`, not a
    //      `continue` and not a `return`. Reading it as "this arm does nothing" silently drops the
    //      end-of-file flag, which turns the paging loop into a loop that never terminates on data.
    //
    //   2. ONLY `WHEN OTHER` RAISES THE ERROR FLAG. The NOTFND and ENDFILE arms set USER-SEC-EOF and
    //      leave WS-ERR-FLG alone, so a caller's `IF NOT ERR-FLG-ON` still passes after them. That is
    //      what lets PROCESS-PAGE-FORWARD carry on past a STARTBR that found nothing.
    //
    // The browse cursor is a value: startBrowse returns it and every read takes it back. It is never a
    // field on this class or on the repository, so two concurrent browses cannot interfere.
    // =================================================================================================

    /**
     * {@code :586-614} - {@code STARTBR}, then its three-arm {@code EVALUATE}.
     *
     * <p>{@code GTEQ} is commented out at {@code :592}, which selects the greater-than-or-equal
     * positioning it would have requested, because that is {@code STARTBR}'s documented default when
     * neither {@code GTEQ} nor {@code EQUAL} is given. The anchor therefore need not be a key that exists -
     * which is what makes {@link SecUserRepository#LOW_VALUES_KEY} position at the first record.
     *
     * <p>The {@code NOTFND} arm is reachable in normal use, not only on a damaged file: PF8 anchors on
     * {@link SecUserRepository#HIGH_VALUES_KEY} when it has no last-row identifier, and no key is at or
     * after high values.
     *
     * @param ws the work area
     * @return the cursor, open or not; never {@code null}
     */
    BrowseCursor startBrowseUserSecFile(WorkArea ws) {
        // :588-595  EXEC CICS STARTBR DATASET(WS-USRSEC-FILE) RIDFLD(SEC-USR-ID)
        //           KEYLENGTH(LENGTH OF SEC-USR-ID) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
        BrowseCursor cursor = secUserRepository.startBrowse(ws.secUsrId);
        ws.captureResponse(cursor.openCicsResp(), FileStatus.NO_REASON_CODE);

        // :597-614  EVALUATE WS-RESP-CD
        if (cursor.openOutcome() == FileStatus.Outcome.OK) {
            // :598-599  WHEN DFHRESP(NORMAL) -> CONTINUE. Here the CONTINUE really is the whole arm,
            // because nothing follows it before the next WHEN.
            return cursor;
        }
        if (cursor.openOutcome() == FileStatus.Outcome.NOT_FOUND) {
            // :600-606  WHEN DFHRESP(NOTFND). CONTINUE at :601 does nothing; all four statements below it
            // run. Note what is NOT here: no error flag.
            ws.userSecEof = true;                                              // :602
            ws.message = codec.movePicX(MSG_AT_TOP, WS_MESSAGE_LENGTH);        // :603-604
            ws.usrIdInLength = CURSOR_ON_USRIDIN;                              // :605
            sendUsrlstScreen(ws);                                              // :606
            return cursor;
        }
        // :607-613  WHEN OTHER
        display(ws, DISPLAY_RESP + respImage(ws.respCd) + DISPLAY_REAS + respImage(ws.reasCd));       // :608
        ws.errFlgOn = true;                                                    // :609
        ws.message = codec.movePicX(MSG_UNABLE_TO_LOOKUP, WS_MESSAGE_LENGTH);  // :610-611
        ws.usrIdInLength = CURSOR_ON_USRIDIN;                                  // :612
        sendUsrlstScreen(ws);                                                  // :613
        return cursor;
    }

    /**
     * {@code :619-648} - {@code READNEXT}, then its three-arm {@code EVALUATE}.
     *
     * <p>Reached from three places with three different purposes, all sharing this one paragraph: the
     * discard at {@code :289}, the fill at {@code :301}, and the look-ahead at {@code :311}. The
     * look-ahead is why "You have reached the bottom of the page..." can appear on a page that is
     * completely full - the eleventh read failed, not the tenth.
     *
     * @param ws     the work area
     * @param cursor the open browse
     * @return the read outcome, so the caller can take the record without re-reading; never {@code null}
     */
    ReadResult readNextUserSecFile(WorkArea ws, BrowseCursor cursor) {
        // :621-629  EXEC CICS READNEXT DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA)
        //           LENGTH(LENGTH OF SEC-USER-DATA) RIDFLD(SEC-USR-ID)
        //           KEYLENGTH(LENGTH OF SEC-USR-ID) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
        ReadResult read = cursor.readNext();
        ws.captureResponse(read.cicsResp(), read.cicsResp2());
        ws.acceptRecord(read, codec);

        // :631-648  EVALUATE WS-RESP-CD
        if (read.isFound()) {
            // :632-633  WHEN DFHRESP(NORMAL) -> CONTINUE
            return read;
        }
        if (read.isEndOfFile()) {
            // :634-640  WHEN DFHRESP(ENDFILE). CONTINUE at :635 is a no-op; the four statements run.
            ws.userSecEof = true;                                                  // :636
            ws.message = codec.movePicX(MSG_REACHED_BOTTOM, WS_MESSAGE_LENGTH);    // :637-638
            ws.usrIdInLength = CURSOR_ON_USRIDIN;                                  // :639
            sendUsrlstScreen(ws);                                                  // :640
            return read;
        }
        // :641-647  WHEN OTHER
        display(ws, DISPLAY_RESP + respImage(ws.respCd) + DISPLAY_REAS + respImage(ws.reasCd));           // :642
        ws.errFlgOn = true;                                                        // :643
        ws.message = codec.movePicX(MSG_UNABLE_TO_LOOKUP, WS_MESSAGE_LENGTH);      // :644-645
        ws.usrIdInLength = CURSOR_ON_USRIDIN;                                      // :646
        sendUsrlstScreen(ws);                                                      // :647
        return read;
    }

    /**
     * {@code :653-682} - {@code READPREV}, then its three-arm {@code EVALUATE}.
     *
     * <p>Identical to {@link #readNextUserSecFile(WorkArea, BrowseCursor)} apart from one word: the
     * end-of-file text says "<strong>top</strong> of the page", because running out of records while
     * descending means the start of the file has been reached. The two texts are two constants for exactly
     * that reason.
     *
     * @param ws     the work area
     * @param cursor the open browse
     * @return the read outcome; never {@code null}
     */
    ReadResult readPrevUserSecFile(WorkArea ws, BrowseCursor cursor) {
        // :655-663  EXEC CICS READPREV DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA)
        //           LENGTH(LENGTH OF SEC-USER-DATA) RIDFLD(SEC-USR-ID)
        //           KEYLENGTH(LENGTH OF SEC-USR-ID) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
        ReadResult read = cursor.readPrevious();
        ws.captureResponse(read.cicsResp(), read.cicsResp2());
        ws.acceptRecord(read, codec);

        // :665-682  EVALUATE WS-RESP-CD
        if (read.isFound()) {
            // :666-667  WHEN DFHRESP(NORMAL) -> CONTINUE
            return read;
        }
        if (read.isEndOfFile()) {
            // :668-674  WHEN DFHRESP(ENDFILE). CONTINUE at :669 is a no-op.
            ws.userSecEof = true;                                                  // :670
            ws.message = codec.movePicX(MSG_REACHED_TOP, WS_MESSAGE_LENGTH);       // :671-672
            ws.usrIdInLength = CURSOR_ON_USRIDIN;                                  // :673
            sendUsrlstScreen(ws);                                                  // :674
            return read;
        }
        // :675-681  WHEN OTHER
        display(ws, DISPLAY_RESP + respImage(ws.respCd) + DISPLAY_REAS + respImage(ws.reasCd));           // :676
        ws.errFlgOn = true;                                                        // :677
        ws.message = codec.movePicX(MSG_UNABLE_TO_LOOKUP, WS_MESSAGE_LENGTH);      // :678-679
        ws.usrIdInLength = CURSOR_ON_USRIDIN;                                      // :680
        sendUsrlstScreen(ws);                                                      // :681
        return read;
    }

    /**
     * {@code :687-691} - {@code EXEC CICS ENDBR DATASET(WS-USRSEC-FILE)}.
     *
     * <p><strong>Nothing is checked, deliberately.</strong> The command specifies no {@code RESP} and no
     * {@code RESP2}, and the paragraph has no {@code EVALUATE} after it, so the program looks at no
     * outcome. Adding an error path here would create a branch the source cannot take and that no test
     * could justify. It is called on both paging paths, at {@code :325} and {@code :374}, and it is safe to
     * call on a browse that never opened.
     *
     * @param cursor the browse to end
     */
    void endBrowse(BrowseCursor cursor) {
        cursor.endBrowse();
    }

    /**
     * Ends a browse that the paragraph's own {@code ENDBR} did not reach.
     *
     * <p>{@code :286} and {@code :340} are {@code IF NOT ERR-FLG-ON} guards that skip the remainder of
     * their paragraph - the {@code ENDBR} at {@code :325} and {@code :374} included - so a
     * {@code STARTBR} that reported anything other than {@code NORMAL} or {@code NOTFND} leaves the
     * browse unended, and so does any fault raised by a read. This releases it, and changes nothing the
     * transaction observably produces:
     *
     * <ul>
     *   <li><strong>There is nothing observable to change.</strong> {@code :687-691} specifies no
     *       {@code RESP} and no {@code EVALUATE} follows it, so the program already inspects no outcome
     *       from its own {@code ENDBR}; and {@link BrowseCursor#endBrowse()} sets a flag without issuing
     *       I/O. No map is sent, no message is set, no error flag is raised.</li>
     *   <li><strong>It cannot end the same browse twice.</strong> The guard is
     *       {@link BrowseCursor#isOpen()}, which a cursor reports as {@code false} once it has been
     *       ended, so a request that reached its own {@code ENDBR} finds nothing left to do - and a
     *       {@code STARTBR} that never resolved its statements is likewise skipped rather than
     *       "ended".</li>
     *   <li><strong>It cannot displace a failure.</strong> Anything raised while releasing is swallowed,
     *       so a caller unwinding from a real fault still receives that fault.</li>
     * </ul>
     *
     * @param cursor the cursor {@code STARTBR} returned; must not be {@code null}
     */
    private static void releaseBrowse(BrowseCursor cursor) {
        if (!cursor.isOpen()) {
            return;
        }
        try {
            cursor.endBrowse();
        } catch (RuntimeException cleanupFailure) {
            // Only the failure's TYPE is logged - never the throwable and never its message. A driver
            // composes its message around the value it refused, and a security row carries SEC-USR-PWD
            // in plaintext (CWE-532); a newline in that text could forge a second log entry (CWE-117).
            // A class name carries no data and no newline.
            LOG.warn("Ending the " + WS_USRSEC_FILE + " browse of " + WS_PGMNAME + " after a request "
                    + "that did not reach ENDBR failed - " + cleanupFailure.getClass().getName()
                    + ". The request's own outcome is unchanged, because the request's own failure is "
                    + "the one that matters.");
        }
    }

    /**
     * A {@code DISPLAY} statement - {@code :608}, {@code :642} and {@code :676}.
     *
     * <p>Recorded on the work area so a parity case can assert the program's console output, and logged so
     * that a running system shows it. The text is composed from two fixed literals and two integers taken
     * from the CICS response, so there is nothing in it a caller could use to forge a log record or to leak
     * a payload value - and no throwable is passed, because a logger handed one emits its whole cause
     * chain, which is where a backend's own message about the record it refused would escape.
     *
     * @param ws   the work area
     * @param text the text the source displays
     */
    private void display(WorkArea ws, String text) {
        ws.displays.add(text);
        LOG.info(text);
    }

    /**
     * A {@code PIC S9(09) COMP} response or reason code as this program's {@code DISPLAY} renders it -
     * or, where none was reported, as {@value #WS_RESP_CD_DIGITS} asterisks.
     *
     * <p><strong>The reported branch is left exactly as it was.</strong>
     * {@code DISPLAY 'RESP:' WS-RESP-CD} of a binary field produces a compiler-defined rendering, this
     * controller has always emitted the value's plain decimal form, and the three lines it composes are
     * covered by parity cases. Changing them is not this finding's subject and would break those cases
     * for every reported response.
     *
     * <p>What changes is only the case where there is no response code to render. Every number available
     * there misrepresents it: {@code 0} <em>is</em> {@link FileStatus#NORMAL} and would report a failed
     * command as a successful one, and {@code -1} would read as a {@code DFHRESP} value CICS does not
     * define. The asterisk image is not a number at all, which is the only honest report, and it occupies
     * the digit positions the field declares.
     *
     * @param code the value held in {@code WS-RESP-CD} or {@code WS-REAS-CD}
     * @return the value's decimal form, or {@value #WS_RESP_CD_DIGITS} asterisks when none was reported
     */
    private static String respImage(int code) {
        return FileStatus.respReported(code)
                ? Integer.toString(code)
                : FileStatus.respNotReportedImage(WS_RESP_CD_DIGITS);
    }

    // =================================================================================================
    // Figurative-constant tests. COBOL's SPACES and LOW-VALUES both mean "empty" on this screen, and the
    // program tests for them together everywhere - which is the only reason a single pair of helpers can
    // serve all nine sites.
    // =================================================================================================

    /**
     * {@code IF <field> = SPACES OR LOW-VALUES} - the abbreviated combined relation used at {@code :218},
     * {@code :239}, {@code :262} and {@code :508}.
     *
     * <p>It expands to {@code = SPACES OR = LOW-VALUES}, so a value is "blank" when it is <em>entirely</em>
     * spaces or <em>entirely</em> low values. A mixed value such as {@code "A\0\0\0\0\0\0\0"} is neither,
     * and is therefore not blank - which is the behaviour, however unlikely the value.
     *
     * <p>An empty or {@code null} string counts as blank: a COBOL field has no such state, and both stand
     * for a field the caller did not fill.
     *
     * @param value the field image
     * @return whether the COBOL test would hold
     */
    static boolean isBlank(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        return allOf(value, SPACE) || allOf(value, LOW_VALUE);
    }

    /**
     * {@code IF <field> NOT = SPACES AND LOW-VALUES} - the abbreviated combined relation used in all ten
     * selection arms at {@code :152-181} and in the two guards at {@code :187-188}.
     *
     * <p>It expands to {@code NOT = SPACES AND NOT = LOW-VALUES}, which is the exact negation of
     * {@link #isBlank(String)} - so this is written as that negation rather than as a second scan, and the
     * two cannot drift apart.
     *
     * @param value the field image
     * @return whether the COBOL test would hold
     */
    static boolean isNotBlank(String value) {
        return !isBlank(value);
    }

    /**
     * @param value     the image to test
     * @param character the character every position must hold
     * @return whether every character of {@code value} is {@code character}
     */
    private static boolean allOf(String value, char character) {
        for (int position = 0; position < value.length(); position++) {
            if (value.charAt(position) != character) {
                return false;
            }
        }
        return true;
    }

    /**
     * The {@code SPACES} figurative constant at a declared width.
     *
     * @param width the receiving field's declared width
     * @return exactly {@code width} spaces
     */
    private static String spaces(int width) {
        return String.valueOf(SPACE).repeat(width);
    }

    // =================================================================================================
    // ONE SEND - app/cbl/COUSR00C.cbl:528-544.
    // =================================================================================================

    /**
     * One {@code EXEC CICS SEND MAP}: the screen transmitted, and the two operands that shaped it.
     *
     * <p>{@link UserListResponse} declares no member for either operand, because neither is a field of the
     * map - and neither may be silently discarded, so both are recorded here. This record is what makes the
     * {@code ERASE} distinction and the "sent twice" behaviour assertable.
     *
     * @param screen         the payload as this send transmitted it
     * @param erase          whether {@code SEND-ERASE-YES} held, choosing the {@code ERASE} arm at
     *                       {@code :529-535} over the arm at {@code :537-543} where {@code ERASE} is
     *                       commented out at {@code :541}
     * @param cursorPosition the {@code USRIDINL} value the {@code CURSOR} operand acted on:
     *                       {@value #CURSOR_ON_USRIDIN} to place the cursor in the start-key field, or
     *                       {@value #NO_CURSOR} for none
     */
    record SentScreen(UserListResponse screen, boolean erase, int cursorPosition) {

        /**
         * @throws NullPointerException if {@code screen} is {@code null}
         */
        SentScreen {
            Objects.requireNonNull(screen, "A send transmits a screen; there is no send without one");
        }
    }

    // =================================================================================================
    // WORKING-STORAGE - app/cbl/COUSR00C.cbl:33-84, one instance per call.
    //
    // Every value here is COBOL WORKING-STORAGE or the communication area, and therefore per-task state.
    // In Java it is per-call: a new WorkArea for every request, never a field on the controller and never
    // static. That is what makes the bean safe to share, makes two successive requests independent, and
    // makes a test need no reset between cases.
    //
    // NOT modelled, on purpose (practice B5): WS-REC-COUNT (:52), WS-PAGE-NUM (:54) and the whole of
    // 01 WS-USER-DATA with its 02 USER-REC OCCURS 10 TIMES (:56-64). All three are declared and never
    // referenced in the PROCEDURE DIVISION. Paging uses CDEMO-CU00-PAGE-NUM, not WS-PAGE-NUM.
    // =================================================================================================

    /**
     * The {@code WORKING-STORAGE} of one execution of {@code COUSR00C}.
     *
     * <p>Package-visible with package-visible accessors, so a test in this package can drive one paragraph
     * and read every flag the COBOL set - including the ones that never reach the payload.
     */
    static final class WorkArea {

        // ---- WS-VARIABLES, :35-54 -------------------------------------------------------------------

        /** {@code 05 WS-MESSAGE PIC X(80) VALUE SPACES} - {@code :38}. */
        private String message = spaces(WS_MESSAGE_LENGTH);

        /**
         * {@code 05 WS-ERR-FLG PIC X(01) VALUE 'N'} with {@code 88 ERR-FLG-ON} / {@code 88 ERR-FLG-OFF} -
         * {@code :40-42}.
         *
         * <p>Only the {@code WHEN OTHER} arm of the three browse commands raises it. The end-of-file and
         * not-found arms deliberately do not, which is why a caller's {@code IF NOT ERR-FLG-ON} still
         * passes after them.
         */
        private boolean errFlgOn;

        /**
         * {@code 05 WS-USER-SEC-EOF PIC X(01) VALUE 'N'} with {@code 88 USER-SEC-EOF} /
         * {@code 88 USER-SEC-NOT-EOF} - {@code :43-45}.
         */
        private boolean userSecEof;

        /**
         * {@code 05 WS-SEND-ERASE-FLG PIC X(01) VALUE 'Y'} with {@code 88 SEND-ERASE-YES} /
         * {@code 88 SEND-ERASE-NO} - {@code :46-48}.
         *
         * <p>Starts {@code true} because the declaration says {@code VALUE 'Y'}, and {@code :103} sets it
         * again on every entry. Only the two "already at the ..." paths clear it.
         */
        private boolean sendEraseYes = true;

        /** {@code 05 WS-RESP-CD PIC S9(09) COMP VALUE ZEROS} - {@code :50}, the {@code RESP} of each command. */
        private int respCd;

        /** {@code 05 WS-REAS-CD PIC S9(09) COMP VALUE ZEROS} - {@code :51}, the {@code RESP2} of each command. */
        private int reasCd;

        /**
         * {@code 05 WS-IDX PIC S9(04) COMP VALUE ZEROS} - {@code :53}, the row subscript.
         *
         * <p><strong>One-based, 1 through 10</strong>, matching {@code EVALUATE WS-IDX WHEN 1 .. WHEN 10}.
         * It is never used as a Java index; the two paragraphs that address a row convert it in exactly one
         * expression each.
         */
        private int idx;

        // ---- SEC-USER-DATA, COPY CSUSR01Y at :81 ----------------------------------------------------

        /**
         * {@code 05 SEC-USR-ID PIC X(08)} - the {@code RIDFLD} of all three browse commands.
         *
         * <p>Starts at {@link SecUserRepository#LOW_VALUES_KEY} rather than spaces because every path that
         * reaches a {@code STARTBR} sets it first, and low values is the value the {@code SPACES OR
         * LOW-VALUES} branch of each of those paths chooses.
         */
        private String secUsrId = SecUserRepository.LOW_VALUES_KEY;

        /**
         * The record area {@code INTO(SEC-USER-DATA)} fills - {@code :623} and {@code :657}.
         *
         * <p>Retained after a failed read exactly as COBOL retains it: a {@code READNEXT} that reports end
         * of file does not blank the area, so it still holds the previous record.
         */
        private SecUserRecord secUserData = SecUserRecord.blank();

        // ---- CARDDEMO-COMMAREA, COPY COCOM01Y at :66 ------------------------------------------------

        /** The 160-byte shared communication area. */
        private NavigationContext commarea = NavigationContext.empty();

        // ---- CDEMO-CU00-INFO, the 34-byte extension at :67-75 ---------------------------------------

        /** {@code 10 CDEMO-CU00-USRID-FIRST PIC X(08)} - {@code :68}, the backward anchor, set from row 1. */
        private String cu00UsrIdFirst = spaces(UserListResponse.CU00_USRID_FIRST_LENGTH);

        /** {@code 10 CDEMO-CU00-USRID-LAST PIC X(08)} - {@code :69}, the forward anchor, set from row 10. */
        private String cu00UsrIdLast = spaces(UserListResponse.CU00_USRID_LAST_LENGTH);

        /**
         * {@code 10 CDEMO-CU00-PAGE-NUM PIC 9(08)} - {@code :70}.
         *
         * <p>An {@code int}: the picture is unsigned with no {@code V} and no scale, so there is no decimal
         * position for a {@code BigDecimal} to carry and no reason for a binary floating-point type to be
         * anywhere near it.
         */
        private int cu00PageNum;

        /**
         * {@code 10 CDEMO-CU00-NEXT-PAGE-FLG PIC X(01) VALUE 'N'} with {@code 88 NEXT-PAGE-YES} /
         * {@code 88 NEXT-PAGE-NO} - {@code :71-73}.
         *
         * <p>Held as the boolean the two {@code 88} levels describe. The wire form is the single character,
         * and {@link #render(FixedWidthCodec)} converts.
         */
        private boolean nextPageYes;

        /** {@code 10 CDEMO-CU00-USR-SEL-FLG PIC X(01)} - {@code :74}. No {@code VALUE} clause, so it starts blank. */
        private String cu00UsrSelFlg = spaces(UserListResponse.CU00_USR_SEL_FLG_LENGTH);

        /** {@code 10 CDEMO-CU00-USR-SELECTED PIC X(08)} - {@code :75}. */
        private String cu00UsrSelected = spaces(UserListResponse.CU00_USR_SELECTED_LENGTH);

        // ---- COUSR0AI / COUSR0AO, COPY COUSR00 at :76 -----------------------------------------------

        /** The single symbolic-map storage, read as {@code COUSR0AI} and rendered as {@code COUSR0AO}. */
        private final SymbolicMap map = new SymbolicMap();

        /**
         * {@code USRIDINL OF COUSR0AI} - the length item the program writes {@code -1} into to place the
         * cursor.
         *
         * <p>A {@code xxxL} item is metadata, not a payload member, so it lives here and not on the wire.
         */
        private int usrIdInLength;

        // ---- Observability. Not COBOL state; the record of what the program did. ---------------------

        /** Every {@code SEND MAP}, in order. */
        private final List<SentScreen> sends = new ArrayList<>();

        /** Every {@code DISPLAY}, in order. */
        private final List<String> displays = new ArrayList<>();

        /** Whether an {@code XCTL} transferred control, in which case no send occurred. */
        private boolean transferred;

        /** The {@code AidKey} the shared resolver made of {@code EIBAID}, empty when it recognised none. */
        private Optional<AidKey> aidKey = Optional.empty();

        /** The one-based row the selection scan matched, or {@code 0} when it reached {@code WHEN OTHER}. */
        private int selectedRow;

        // ---- State transitions ----------------------------------------------------------------------

        /**
         * {@code :114 MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA} - takes the whole 194-byte area,
         * shared part and {@code CDEMO-CU00-INFO} extension alike, from the caller.
         *
         * <p>This is what overwrites the {@code SET NEXT-PAGE-NO} of {@code :102}, so it must happen after
         * it and does.
         *
         * @param incoming the inbound payload, whose communication area is present
         */
        void acceptCommarea(UserListRequest incoming) {
            commarea = incoming.navigationContext();
            cu00UsrIdFirst = incoming.cdemoCu00UsrIdFirst();
            cu00UsrIdLast = incoming.cdemoCu00UsrIdLast();
            cu00PageNum = incoming.cdemoCu00PageNum();
            nextPageYes = incoming.nextPageYes();
            cu00UsrSelFlg = incoming.cdemoCu00UsrSelFlg();
            cu00UsrSelected = incoming.cdemoCu00UsrSelected();
        }

        /**
         * {@code INTO(SEC-USER-DATA)} - stores the record a read returned.
         *
         * <p>A read that returned none leaves the area alone, which is the CICS behaviour: the area is only
         * written when a record was moved into it.
         *
         * @param read  the read outcome
         * @param codec the codec, so the stored image is at the copybook's declared widths
         */
        void acceptRecord(ReadResult read, FixedWidthCodec codec) {
            if (!read.isFound()) {
                return;
            }
            SecUserRecord record = read.requireRecord();
            secUserData = record;
            // RIDFLD is updated by the browse, so the key of the record just read becomes the current
            // position - which is what a subsequent STARTBR on this work area would anchor on.
            secUsrId = codec.movePicX(record.secUsrId(), SecUserRecord.SEC_USR_ID_LENGTH);
        }

        /**
         * Stores the {@code RESP} and {@code RESP2} a command reported, for the {@code DISPLAY} to render.
         *
         * <p>A command that reported <strong>no</strong> response code records
         * {@link FileStatus#RESP_NOT_REPORTED} rather than {@link FileStatus#NORMAL}. Recording
         * {@code NORMAL} was the defect: it is {@code WS-RESP-CD}'s {@code VALUE ZEROS} initial state
         * <em>and</em> the value CICS sets on success, so the three {@code WHEN OTHER} arms at
         * {@code :608}, {@code :642} and {@code :676} would each have displayed {@code RESP:0} for a
         * command that reported nothing at all - a line that says the read succeeded, on the arm reached
         * only because it did not. The sentinel cannot collide with any {@code DFHRESP} value and is
         * rendered by {@link UserMenuController#respImage(int)} as asterisks rather than as a number.
         *
         * @param resp  the response code, or empty when none was reported
         * @param resp2 the reason code
         */
        void captureResponse(OptionalInt resp, int resp2) {
            respCd = resp.orElse(FileStatus.RESP_NOT_REPORTED);
            reasCd = resp2;
        }

        /**
         * Renders the current map and communication-area state as a response builder.
         *
         * <p>This is the {@code FROM(COUSR0AO)} projection: the same storage the {@code xxxI} items were
         * written into, read out through its {@code xxxO} view. The two paging anchors, the page number, the
         * next-page flag and the selection travel with it because {@code EXEC CICS RETURN
         * COMMAREA(CARDDEMO-COMMAREA)} at {@code :143} carries them to the next cycle.
         *
         * <p>{@code nextMapset} and {@code nextMap} are left at their blank seed: {@code COUSR00C} writes
         * neither {@code CDEMO-LAST-MAP} nor {@code CDEMO-LAST-MAPSET}.
         *
         * <p>No codec is needed and none is taken: {@code PAGENUMO} already holds the rendered image that
         * {@code :327} or {@code :376} put there, and rendering it again here would paint a page number
         * onto a screen those two lines never reached - a transfer, for instance.
         *
         * @return a builder holding the whole screen; never {@code null}
         */
        UserListResponse.Builder render() {
            UserListResponse.Builder builder = map.render(UserListResponse.builder())
                    .cdemoCu00UsrIdFirst(cu00UsrIdFirst)
                    .cdemoCu00UsrIdLast(cu00UsrIdLast)
                    .cdemoCu00PageNum(cu00PageNum)
                    .cdemoCu00UsrSelFlg(cu00UsrSelFlg)
                    .cdemoCu00UsrSelected(cu00UsrSelected)
                    .navigationContext(commarea);
            // The two 88 levels, back to the one character the field holds.
            return nextPageYes ? builder.nextPageYes() : builder.nextPageNo();
        }

        // ---- Read-through accessors, for a test that drives one paragraph ---------------------------

        /** @return {@code WS-MESSAGE}, at its declared eighty characters */
        String message() {
            return message;
        }

        /** @return whether {@code 88 ERR-FLG-ON} holds */
        boolean errFlgOn() {
            return errFlgOn;
        }

        /** @return whether {@code 88 USER-SEC-EOF} holds */
        boolean userSecEof() {
            return userSecEof;
        }

        /** @return whether {@code 88 SEND-ERASE-YES} holds */
        boolean sendEraseYes() {
            return sendEraseYes;
        }

        /** @return whether {@code 88 NEXT-PAGE-YES} holds */
        boolean nextPageYes() {
            return nextPageYes;
        }

        /** @return {@code WS-RESP-CD}, the last {@code RESP} reported */
        int respCd() {
            return respCd;
        }

        /** @return {@code WS-REAS-CD}, the last {@code RESP2} reported */
        int reasCd() {
            return reasCd;
        }

        /** @return {@code WS-IDX}, one-based */
        int idx() {
            return idx;
        }

        /**
         * Sets {@code WS-IDX}, the row subscript.
         *
         * <p>{@code POPULATE-USER-DATA} and {@code INITIALIZE-USER-DATA} both switch on this field and
         * never assign it - their callers do, at {@code :293}, {@code :298}, {@code :347} and
         * {@code :352} - so a test driving either paragraph on its own has to establish it, including the
         * out-of-range values that reach the {@code WHEN OTHER} arm.
         *
         * @param value the subscript, one-based, and deliberately unvalidated so that
         *              {@code WHEN OTHER} stays reachable
         */
        void idx(int value) {
            idx = value;
        }

        /** @return {@code SEC-USR-ID}, the {@code RIDFLD} */
        String secUsrId() {
            return secUsrId;
        }

        /**
         * Sets {@code SEC-USR-ID}, the {@code RIDFLD} a browse is anchored on.
         *
         * <p>Every paragraph that reaches a {@code STARTBR} sets this field first - {@code :219}/{@code :221}
         * for ENTER, {@code :240}/{@code :242} for PF7 and {@code :263}/{@code :265} for PF8 - so a test
         * driving one paging paragraph on its own has to be able to establish the same precondition its
         * caller would. Without it the paragraph would browse from the field's initial low values, which is
         * a different case and would make such a test pass for the wrong reason.
         *
         * <p>It is <strong>not</strong> a payload member: {@code SEC-USR-ID} is {@code WORKING-STORAGE},
         * derived on every path from a value that <em>is</em> carried - {@code USRIDINI},
         * {@code CDEMO-CU00-USRID-FIRST} or {@code CDEMO-CU00-USRID-LAST}.
         *
         * @param value the eight-character key to anchor on
         */
        void secUsrId(String value) {
            secUsrId = value;
        }

        /** @return the record area {@code INTO(SEC-USER-DATA)} last filled */
        SecUserRecord secUserData() {
            return secUserData;
        }

        /** @return the 160-byte shared communication area */
        NavigationContext commarea() {
            return commarea;
        }

        /** @return {@code CDEMO-CU00-USRID-FIRST} */
        String cu00UsrIdFirst() {
            return cu00UsrIdFirst;
        }

        /** @return {@code CDEMO-CU00-USRID-LAST} */
        String cu00UsrIdLast() {
            return cu00UsrIdLast;
        }

        /** @return {@code CDEMO-CU00-PAGE-NUM} */
        int cu00PageNum() {
            return cu00PageNum;
        }

        /** @return {@code CDEMO-CU00-USR-SEL-FLG} */
        String cu00UsrSelFlg() {
            return cu00UsrSelFlg;
        }

        /** @return {@code CDEMO-CU00-USR-SELECTED} */
        String cu00UsrSelected() {
            return cu00UsrSelected;
        }

        /** @return the symbolic map, the one storage {@code COUSR0AI} and {@code COUSR0AO} share */
        SymbolicMap map() {
            return map;
        }

        /** @return {@code USRIDINL}, {@value UserMenuController#CURSOR_ON_USRIDIN} when the cursor is set */
        int usrIdInLength() {
            return usrIdInLength;
        }

        /** @return every {@code SEND MAP} this execution issued, in order; unmodifiable */
        List<SentScreen> sends() {
            return Collections.unmodifiableList(sends);
        }

        /** @return every {@code DISPLAY} this execution issued, in order; unmodifiable */
        List<String> displays() {
            return Collections.unmodifiableList(displays);
        }

        /** @return whether an {@code XCTL} transferred control */
        boolean transferred() {
            return transferred;
        }

        /** @return the resolved attention identifier, empty when the resolver recognised none */
        Optional<AidKey> aidKey() {
            return aidKey;
        }

        /** @return the one-based row the selection scan matched, or {@code 0} for {@code WHEN OTHER} */
        int selectedRow() {
            return selectedRow;
        }
    }

    // =================================================================================================
    // THE SYMBOLIC MAP - COPY COUSR00 at app/cbl/COUSR00C.cbl:76, app/cpy-bms/COUSR00.CPY.
    //
    // ONE storage, TWO views. 01 COUSR0AO REDEFINES COUSR0AI, and because both maps open with the same
    // 12-byte TIOAPFX filler and both allot 7 + n bytes per field - L(2) + F(1) + FILLER(4) + I(n) on the
    // input side, FILLER(3) + C + P + H + V(4) + O(n) on the output side - the xxxI and xxxO DATA ITEMS
    // OCCUPY THE SAME BYTES. That is why the program can write USRID01I and PAGENUMI and then send
    // FROM(COUSR0AO), and it is why one set of fields here is correct rather than two.
    //
    // The xxxL, xxxF and xxxA items of the input view and the xxxC, xxxP, xxxH and xxxV items of the
    // output view are validation and highlight metadata, not data, so none of them is here. The one this
    // program uses is USRIDINL, which lives on the WorkArea.
    // =================================================================================================

    /**
     * The fifty-nine data items of map {@code COUSR0A}: eight header and paging fields, ten rows of five,
     * and the message line.
     *
     * <p>Mutable and per-call, held by exactly one {@link WorkArea}. Every write goes through
     * {@link FixedWidthCodec}, so each field holds an image of its declared width and the direction of any
     * truncation was chosen rather than inherited.
     */
    static final class SymbolicMap {

        /** {@code TRNNAMEI} / {@code TRNNAMEO}, {@code PIC X(4)}. */
        private String trnName = spaces(UserListResponse.TRNNAME_LENGTH);

        /** {@code TITLE01I} / {@code TITLE01O}, {@code PIC X(40)}. */
        private String title01 = spaces(UserListResponse.TITLE01_LENGTH);

        /** {@code CURDATEI} / {@code CURDATEO}, {@code PIC X(8)}, holding {@code MM/DD/YY}. */
        private String curDate = spaces(UserListResponse.CURDATE_LENGTH);

        /** {@code PGMNAMEI} / {@code PGMNAMEO}, {@code PIC X(8)}. */
        private String pgmName = spaces(UserListResponse.PGMNAME_LENGTH);

        /** {@code TITLE02I} / {@code TITLE02O}, {@code PIC X(40)}. */
        private String title02 = spaces(UserListResponse.TITLE02_LENGTH);

        /** {@code CURTIMEI} / {@code CURTIMEO}, {@code PIC X(8)}, holding {@code HH:MM:SS}. */
        private String curTime = spaces(UserListResponse.CURTIME_LENGTH);

        /**
         * {@code PAGENUMI} / {@code PAGENUMO}, {@code PIC X(8)}.
         *
         * <p>The rendered form of the {@code PIC 9(08)} page number, zero-filled to eight characters by the
         * numeric {@code MOVE} at {@code :327} and {@code :376}.
         */
        private String pageNum = spaces(UserListResponse.PAGENUM_LENGTH);

        /**
         * {@code USRIDINI} / {@code USRIDINO}, {@code PIC X(8)} - the operator's start key.
         *
         * <p>The only unprotected field on the screen besides the ten selection cells
         * ({@code ATTRB=(FSET,NORM,UNPROT)} at {@code app/bms/COUSR00.bms:95}). Blank is meaningful: it
         * means "start at the beginning of the file".
         */
        private String usrIdIn = spaces(UserListResponse.USRIDIN_LENGTH);

        /**
         * {@code SEL0001I} .. {@code SEL0010I}, {@code PIC X(1)} each. Index {@code 0} holds row 1.
         *
         * <p>Operator input that the program reads and <strong>never writes</strong>: neither
         * {@code POPULATE-USER-DATA} nor {@code INITIALIZE-USER-DATA} touches a selection cell, so a tick
         * survives onto the next screen and the {@code WHEN OTHER} of {@code :182-184} is what stops it
         * being acted on twice.
         */
        private final String[] sel = newRow(UserListResponse.SEL_LENGTH);

        /** {@code USRID01I} .. {@code USRID10I}, {@code PIC X(8)} each. Index {@code 0} holds row 1. */
        private final String[] usrId = newRow(UserListResponse.USRID_LENGTH);

        /** {@code FNAME01I} .. {@code FNAME10I}, {@code PIC X(20)} each - a clean 20-to-20 move from the record. */
        private final String[] fname = newRow(UserListResponse.FNAME_LENGTH);

        /** {@code LNAME01I} .. {@code LNAME10I}, {@code PIC X(20)} each - a clean 20-to-20 move from the record. */
        private final String[] lname = newRow(UserListResponse.LNAME_LENGTH);

        /** {@code UTYPE01I} .. {@code UTYPE10I}, {@code PIC X(1)} each - a clean 1-to-1 move from the record. */
        private final String[] utype = newRow(UserListResponse.UTYPE_LENGTH);

        /**
         * {@code ERRMSGI} / {@code ERRMSGO}, {@code PIC X(78)} - the message line.
         *
         * <p>Seventy-eight, against {@code WS-MESSAGE}'s eighty. The two-character difference is the whole
         * reason {@code :526} is a truncating move.
         */
        private String errMsg = spaces(UserListResponse.ERRMSG_LENGTH);

        /**
         * @param width the declared width of one cell
         * @return ten space-filled cells
         */
        private static String[] newRow(int width) {
            String[] cells = new String[PAGE_SIZE];
            for (int index = 0; index < PAGE_SIZE; index++) {
                cells[index] = spaces(width);
            }
            return cells;
        }

        /**
         * {@code :117 MOVE LOW-VALUES TO COUSR0AO} - blanks the entire map.
         *
         * <p>Every field, not only the ten rows: the header, the page number, the operator's start key, all
         * ten selection cells and the message line. That is what makes a first entry ignore the screen
         * fields the payload carried and always list from the top with no selection acted on.
         *
         * <p>Spaces are the faithful Java rendering of {@code LOW-VALUES} here, because this program tests
         * the two together everywhere it tests either - see {@link UserMenuController#isBlank(String)}.
         */
        void moveLowValues() {
            trnName = spaces(UserListResponse.TRNNAME_LENGTH);
            title01 = spaces(UserListResponse.TITLE01_LENGTH);
            curDate = spaces(UserListResponse.CURDATE_LENGTH);
            pgmName = spaces(UserListResponse.PGMNAME_LENGTH);
            title02 = spaces(UserListResponse.TITLE02_LENGTH);
            curTime = spaces(UserListResponse.CURTIME_LENGTH);
            pageNum = spaces(UserListResponse.PAGENUM_LENGTH);
            usrIdIn = spaces(UserListResponse.USRIDIN_LENGTH);
            for (int index = 0; index < PAGE_SIZE; index++) {
                sel[index] = spaces(UserListResponse.SEL_LENGTH);
                usrId[index] = spaces(UserListResponse.USRID_LENGTH);
                fname[index] = spaces(UserListResponse.FNAME_LENGTH);
                lname[index] = spaces(UserListResponse.LNAME_LENGTH);
                utype[index] = spaces(UserListResponse.UTYPE_LENGTH);
            }
            errMsg = spaces(UserListResponse.ERRMSG_LENGTH);
        }

        /**
         * {@code :551-557 EXEC CICS RECEIVE MAP INTO(COUSR0AI)} - takes the operator's field values.
         *
         * <p>All fifty-nine are taken, including the header fields the operator cannot type into: a
         * {@code RECEIVE} fills the whole map area, and every field of this one carries {@code FSET}
         * ({@code app/bms/COUSR00.bms:34} onwards), so the terminal returns them all. Each is passed through
         * the codec to its declared width, so a payload that carried a short value is padded exactly as the
         * receive would leave it.
         *
         * @param incoming the inbound payload
         * @param codec    the codec
         */
        void receive(UserListRequest incoming, FixedWidthCodec codec) {
            trnName = codec.movePicX(incoming.trnName(), UserListResponse.TRNNAME_LENGTH);
            title01 = codec.movePicX(incoming.title01(), UserListResponse.TITLE01_LENGTH);
            curDate = codec.movePicX(incoming.curDate(), UserListResponse.CURDATE_LENGTH);
            pgmName = codec.movePicX(incoming.pgmName(), UserListResponse.PGMNAME_LENGTH);
            title02 = codec.movePicX(incoming.title02(), UserListResponse.TITLE02_LENGTH);
            curTime = codec.movePicX(incoming.curTime(), UserListResponse.CURTIME_LENGTH);
            pageNum = codec.movePicX(incoming.pageNum(), UserListResponse.PAGENUM_LENGTH);
            usrIdIn = codec.movePicX(incoming.usrIdIn(), UserListResponse.USRIDIN_LENGTH);
            for (int rowNumber = FIRST_ROW; rowNumber <= PAGE_SIZE; rowNumber++) {
                UserListRequest.UserListRow row = incoming.row(rowNumber);
                int index = rowNumber - 1;
                sel[index] = codec.movePicX(row.sel(), UserListResponse.SEL_LENGTH);
                usrId[index] = codec.movePicX(row.usrId(), UserListResponse.USRID_LENGTH);
                fname[index] = codec.movePicX(row.fname(), UserListResponse.FNAME_LENGTH);
                lname[index] = codec.movePicX(row.lname(), UserListResponse.LNAME_LENGTH);
                utype[index] = codec.movePicX(row.utype(), UserListResponse.UTYPE_LENGTH);
            }
            errMsg = codec.movePicX(incoming.errMsg(), UserListResponse.ERRMSG_LENGTH);
        }

        /**
         * {@code FROM(COUSR0AO)} - projects the storage through its output view onto a response builder.
         *
         * @param builder the builder to fill
         * @return {@code builder}, for chaining
         */
        UserListResponse.Builder render(UserListResponse.Builder builder) {
            builder.trnName(trnName)
                    .title01(title01)
                    .curDate(curDate)
                    .pgmName(pgmName)
                    .title02(title02)
                    .curTime(curTime)
                    .pageNum(pageNum)
                    .usrIdIn(usrIdIn)
                    .errMsg(errMsg);
            for (int rowNumber = FIRST_ROW; rowNumber <= PAGE_SIZE; rowNumber++) {
                int index = rowNumber - 1;
                builder.populateRow(rowNumber, usrId[index], fname[index], lname[index], utype[index])
                        .selection(rowNumber, sel[index]);
            }
            return builder;
        }

        // ---- Read-through accessors, one-based to match the COBOL subscript -------------------------

        /** @return {@code TRNNAMEO} */
        String trnName() {
            return trnName;
        }

        /** @return {@code TITLE01O} */
        String title01() {
            return title01;
        }

        /** @return {@code CURDATEO} */
        String curDate() {
            return curDate;
        }

        /** @return {@code PGMNAMEO} */
        String pgmName() {
            return pgmName;
        }

        /** @return {@code TITLE02O} */
        String title02() {
            return title02;
        }

        /** @return {@code CURTIMEO} */
        String curTime() {
            return curTime;
        }

        /** @return {@code PAGENUMO}, the eight-character rendering of the page number */
        String pageNum() {
            return pageNum;
        }

        /** @return {@code USRIDINO} */
        String usrIdIn() {
            return usrIdIn;
        }

        /** @return {@code ERRMSGO} */
        String errMsg() {
            return errMsg;
        }

        /**
         * @param rowNumber the one-based {@code WS-IDX} row, 1 to 10
         * @return {@code SEL000n}
         * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to 10
         */
        String sel(int rowNumber) {
            return sel[requireRowNumber(rowNumber) - 1];
        }

        /**
         * @param rowNumber the one-based {@code WS-IDX} row, 1 to 10
         * @return {@code USRIDnn}
         * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to 10
         */
        String usrId(int rowNumber) {
            return usrId[requireRowNumber(rowNumber) - 1];
        }

        /**
         * @param rowNumber the one-based {@code WS-IDX} row, 1 to 10
         * @return {@code FNAMEnn}
         * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to 10
         */
        String fname(int rowNumber) {
            return fname[requireRowNumber(rowNumber) - 1];
        }

        /**
         * @param rowNumber the one-based {@code WS-IDX} row, 1 to 10
         * @return {@code LNAMEnn}
         * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to 10
         */
        String lname(int rowNumber) {
            return lname[requireRowNumber(rowNumber) - 1];
        }

        /**
         * @param rowNumber the one-based {@code WS-IDX} row, 1 to 10
         * @return {@code UTYPEnn}
         * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to 10
         */
        String utype(int rowNumber) {
            return utype[requireRowNumber(rowNumber) - 1];
        }

        /**
         * Writes one selection cell, so a test can tick a row without assembling a whole payload.
         *
         * @param rowNumber the one-based {@code WS-IDX} row, 1 to 10
         * @param value     the cell value
         * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to 10
         */
        void sel(int rowNumber, String value) {
            sel[requireRowNumber(rowNumber) - 1] = value;
        }

        /**
         * Writes one identifier cell, so a test can set up the row a selection points at.
         *
         * @param rowNumber the one-based {@code WS-IDX} row, 1 to 10
         * @param value     the cell value
         * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to 10
         */
        void usrId(int rowNumber, String value) {
            usrId[requireRowNumber(rowNumber) - 1] = value;
        }

        /**
         * Writes {@code USRIDINO}, so a test can supply a start key without assembling a whole payload.
         *
         * @param value the field value
         */
        void usrIdIn(String value) {
            usrIdIn = value;
        }

        /**
         * Rejects a subscript outside the {@code OCCURS 10} range.
         *
         * <p>{@code WS-IDX} is one-based and so is this: {@code 1} addresses the row whose fields are
         * {@code SEL0001} and {@code USRID01}, and {@code 10} addresses the {@code ..10} row. The
         * conversion to a Java index is the {@code - 1} at each call site of this method and appears
         * nowhere else.
         *
         * @param rowNumber the one-based row number
         * @return {@code rowNumber}, unchanged
         * @throws IllegalArgumentException if it is outside 1 to 10
         */
        private static int requireRowNumber(int rowNumber) {
            if (rowNumber < FIRST_ROW || rowNumber > PAGE_SIZE) {
                throw new IllegalArgumentException("Row " + rowNumber + " does not exist: map COUSR0A "
                        + "declares " + PAGE_SIZE + " rows and WS-IDX addresses them from " + FIRST_ROW
                        + " to " + PAGE_SIZE + " inclusive, not from zero");
            }
            return rowNumber;
        }
    }
}
