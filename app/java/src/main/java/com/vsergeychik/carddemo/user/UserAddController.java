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
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.dto.UserAddRequest;
import com.vsergeychik.carddemo.user.dto.UserAddResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import jakarta.validation.Valid;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/users} - CICS transaction {@code CU01}, program {@code app/cbl/COUSR01C.cbl},
 * the Add User screen.
 *
 * <p>A like-for-like translation of a 299-line CICS COBOL program whose stated function is
 * <em>"Add a new Regular/Admin user to USRSEC file"</em>. The transaction is bound to the program by
 * {@code app/csd/CARDDEMO.CSD:459-460}, {@code DEFINE TRANSACTION(CU01) ... PROGRAM(COUSR01C)}.
 *
 * <p>Every decision below is transcribed from the source, not designed. Where the COBOL is
 * inconsistent, this class is inconsistent in the same way and says so, because a "harmonised"
 * translation would make a real difference invisible to a field-by-field diff.
 *
 * <h2>Paragraph map</h2>
 *
 * The COBOL paragraph structure is preserved one-for-one so a reviewer can read the two side by side:
 *
 * <table border="1">
 *   <caption>COBOL paragraph to Java method</caption>
 *   <tr><th>Paragraph</th><th>Lines</th><th>Method</th></tr>
 *   <tr><td>{@code MAIN-PARA}</td><td>71-110</td><td>{@link #mainPara(UserAddRequest, byte, int)}</td></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY}</td><td>115-160</td><td>{@code processEnterKey}</td></tr>
 *   <tr><td>{@code RETURN-TO-PREV-SCREEN}</td><td>165-178</td><td>{@code returnToPrevScreen}</td></tr>
 *   <tr><td>{@code SEND-USRADD-SCREEN}</td><td>184-196</td><td>{@code sendUsraddScreen}</td></tr>
 *   <tr><td>{@code RECEIVE-USRADD-SCREEN}</td><td>201-209</td><td>{@code receiveUsraddScreen}</td></tr>
 *   <tr><td>{@code POPULATE-HEADER-INFO}</td><td>214-233</td><td>{@code populateHeaderInfo}</td></tr>
 *   <tr><td>{@code WRITE-USER-SEC-FILE}</td><td>238-274</td><td>{@code writeUserSecFile}</td></tr>
 *   <tr><td>{@code CLEAR-CURRENT-SCREEN}</td><td>279-282</td><td>{@code clearCurrentScreen}</td></tr>
 *   <tr><td>{@code INITIALIZE-ALL-FIELDS}</td><td>287-295</td><td>{@code initializeAllFields}</td></tr>
 * </table>
 *
 * <h2>Four commented-out statements that must stay dead</h2>
 *
 * This program is unusually rich in code its author disabled, and reproducing any of it would be a
 * behaviour change. All four were verified by reading the source, and one of them corrects the
 * project's own specification:
 *
 * <ol>
 *   <li><strong>{@code COUSR01C:57} is {@code *COPY DFHATTR.}</strong> - commented out. The
 *       specification lists {@code DFHATTR} as having two consumers, {@code COSGN00C} and this
 *       program; in fact no {@code DFHATTR} constant resolves into {@code COUSR01C} at all. Only
 *       {@code COPY DFHAID} (line 55) and {@code COPY DFHBMSCA} (line 56) are live, which is why
 *       {@link BmsAttributes} is consulted here for exactly one value - {@link BmsAttributes#DFHGREEN}
 *       - and no attribute behaviour is invented.</li>
 *   <li><strong>{@code COUSR01C:172-173}</strong> - {@code MOVE WS-USER-ID TO CDEMO-USER-ID} and
 *       {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE}, both commented out. {@code WS-USER-ID} is not
 *       even declared in {@code WS-VARIABLES}, so the first would not compile if enabled. Neither
 *       communication-area field is written on the return path here.</li>
 *   <li><strong>{@code COUSR01C:268}</strong> - {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD},
 *       commented out on the write-failure arm. Its siblings {@code COUSR00C} and {@code COUSR03C}
 *       leave the equivalent line live; this program does not. That single commented line is the
 *       program's <em>only</em> {@code DISPLAY}, which is why this class holds no logger and emits no
 *       diagnostic line anywhere.</li>
 *   <li><strong>{@code app/cpy/COTTL01Y.cpy}</strong> carries a commented-out alternative for
 *       {@code CCDA-TITLE02}. {@link ScreenTitles} already resolves the live one.</li>
 * </ol>
 *
 * <h2>The sharpest parity trap: no case normalisation</h2>
 *
 * {@code COUSR01C:154-158} moves the five typed values straight into the record:
 *
 * <pre>
 *   MOVE USERIDI  OF COUSR1AI TO SEC-USR-ID
 *   MOVE FNAMEI   OF COUSR1AI TO SEC-USR-FNAME
 *   MOVE LNAMEI   OF COUSR1AI TO SEC-USR-LNAME
 *   MOVE PASSWDI  OF COUSR1AI TO SEC-USR-PWD
 *   MOVE USRTYPEI OF COUSR1AI TO SEC-USR-TYPE
 * </pre>
 *
 * There is no {@code FUNCTION UPPER-CASE} on any of them. {@code app/cbl/COSGN00C.cbl:132-137}, by
 * contrast, upper-cases both the id and the password before comparing. The consequence is real and
 * user-visible: <strong>a user added here with a lower-case id or password can never sign in</strong>,
 * because sign-on will compare the upper-cased input against the verbatim stored bytes.
 *
 * <p>That is legacy behaviour, and it is <strong>not</strong> a defect to be fixed here. Nothing on
 * this path upper-cases, lower-cases, trims, strips or otherwise normalises a typed value; the five
 * values reach {@link SecUserRecord} exactly as received. The only transformation applied is the
 * fixed-width {@code PIC X} move itself, performed by {@link FixedWidthCodec} so that the padding is
 * explicit and reviewable rather than incidental.
 *
 * <h2>The password is plaintext, and stays that way</h2>
 *
 * {@code SEC-USR-PWD PIC X(08)} of {@code app/cpy/CSUSR01Y.cpy} is written in the clear, exactly as
 * {@code COUSR01C:157} writes it. The posture is left as the legacy design has it - neither
 * strengthened nor weakened:
 *
 * <ul>
 *   <li>Not strengthened: there is no hash, no salt, no encoder, no digest and no token, because any
 *       of those would change observable behaviour and would pull in a security framework that is out
 *       of scope.</li>
 *   <li>Not weakened: nothing here logs. The class has no logger at all, so neither the request nor
 *       the password can reach a log line, and the password is never composed into a message, an
 *       exception or a diagnostic. It is echoed back only on the screen field the map itself declares,
 *       where {@code app/bms/COUSR01.bms:126} marks it {@code ATTRB=(DRK,FSET,UNPROT)} - a dark,
 *       non-display field.</li>
 * </ul>
 *
 * <h2>Statelessness</h2>
 *
 * CICS is pseudo-conversational: the program ends after painting a screen and is re-entered from the
 * top on the next keystroke, so the only state that survives is what it handed back in its
 * communication area. This class reproduces that literally. There is no {@code HttpSession}, no
 * {@code @SessionAttributes}, no {@code ThreadLocal}, no static cache and no static mutable field of
 * any kind; the {@link NavigationContext}, the attention identifier and the screen values all travel
 * in the request and response payloads. Two successive calls share nothing.
 *
 * <p>{@code EXEC CICS XCTL} at {@code COUSR01C:176} likewise becomes data rather than control flow:
 * the response names its target in {@link UserAddResponse#nextProgram()} and the client issues the
 * follow-up call. There is no server-side forward, no redirect and no session affinity.
 *
 * <h2>Where the screen metadata went</h2>
 *
 * {@code app/cpy-bms/COUSR01.CPY} declares, for every one of the twelve fields, a length item
 * {@code xxxL}, an attribute item {@code xxxF}/{@code xxxA} on the input view and four attribute
 * items {@code xxxC}/{@code xxxP}/{@code xxxH}/{@code xxxV} on the output view. None of those is
 * payload, so none of them appears in {@link UserAddResponse}. The two this program actually uses are
 * carried on {@link ProgramState} instead, where a test can assert them and a JSON serialiser cannot
 * reach them:
 *
 * <ul>
 *   <li>{@code xxxL} as a cursor signal. {@code MOVE -1 TO FNAMEL} positions the cursor, and the
 *       program does it at lines 86, 100, 122, 128, 134, 140, 146, 149, 265, 272 and 289. Recorded as
 *       {@link ProgramState#cursorField()}.</li>
 *   <li>{@code ERRMSGC} as the message colour. {@code COUSR01C:254} moves {@link BmsAttributes#DFHGREEN}
 *       into it to turn the success message green, overriding the map's own {@code COLOR=RED}
 *       ({@code app/bms/COUSR01.bms:152}). Recorded as {@link ProgramState#errMsgColour()}.</li>
 * </ul>
 *
 * <h2>Testability</h2>
 *
 * All behaviour lives in {@link #mainPara(UserAddRequest, byte, int)} and the paragraph methods it
 * drives, none of which mentions a servlet type. A unit test constructs the controller with a fixed
 * {@link Clock} and a stub repository and calls {@code mainPara} as a plain Java method, reaching every
 * branch - both attention-identifier arms, all six arms of the blank-field chain and all four write
 * outcomes - with no HTTP layer and no {@code MockMvc} in the path. The
 * {@link #addUser(UserAddRequest, Integer, Integer)} adapter exists only to bind and project.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * UserAddController controller = new UserAddController(secUserRepository, Clock.systemUTC());
 *
 * // First entry: no communication area, so EIBCALEN is zero and the program transfers to sign-on.
 * UserAddResponse signOn = controller.mainPara(firstRequest, CicsAid.DFHENTER, 0).response();
 *
 * // ENTER with every field populated: the record is written and the screen confirms it.
 * UserAddController.ProgramState state = controller.mainPara(filledRequest, CicsAid.DFHENTER,
 *         NavigationContext.COMMAREA_LENGTH);
 * state.errMsgColour();   // BmsAttributes.DFHGREEN
 * state.message();        // "User ADMIN001 has been added ..." padded to eighty
 * </pre>
 *
 * @see SecUserRepository the {@code USRSEC} dataset, reached only through this repository
 * @see SecUserRecord the eighty-byte {@code SEC-USER-DATA} record of {@code app/cpy/CSUSR01Y.cpy}
 * @see UserAddRequest the {@code xxxI} projection of {@code 01 COUSR1AI}
 * @see UserAddResponse the {@code xxxO} projection of {@code 01 COUSR1AO}
 * <h2>This endpoint is unauthenticated and unauthorized - an accepted divergence (CWE-306 and CWE-862)</h2>
 *
 * <p>This controller adds a record to {@code USRSEC} with no authentication and no role check.
 * Nothing here establishes who is calling or that they administer users, so this endpoint can create
 * a security record - including an administrator one - for any caller that can reach it.
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
public class UserAddController {

    // =================================================================================================
    // Route and query parameters.
    // =================================================================================================

    /**
     * The one route this screen exposes. CICS transaction {@code CU01} of
     * {@code app/csd/CARDDEMO.CSD:459}, which names {@code PROGRAM(COUSR01C)}.
     */
    public static final String USERS_PATH = "/api/users";

    /**
     * Query parameter carrying the raw {@code EIBAID} byte that {@code COUSR01C:90} evaluates.
     *
     * <p>Optional. When absent the attention identifier is taken from
     * {@link UserAddRequest#aid()}, and when that is absent too it defaults to
     * {@link CicsAid#DFHENTER} - a CICS terminal always presents some attention identifier, and
     * {@code ENTER} is the only default that cannot reach a branch the operator could not have
     * reached.
     */
    public static final String EIBAID_PARAM = "eibAid";

    /**
     * Query parameter carrying {@code EIBCALEN}, the length of the communication area passed in.
     *
     * <p>Optional. When absent it is derived from the payload: a {@code null}
     * {@link UserAddRequest#navigationContext()} means no communication area was passed and therefore
     * {@code EIBCALEN = 0}, which is precisely the condition {@code COUSR01C:78} tests.
     */
    public static final String EIBCALEN_PARAM = "eibcalen";

    // =================================================================================================
    // Code page. WORKING-STORAGE here is screen and message text, never dataset bytes, so the value is
    // US-ASCII rather than the EBCDIC code page the datasets use. It is stated explicitly and is never
    // the platform default (practice B8); the overloaded constructor exists so a parity case can pin a
    // different one.
    // =================================================================================================

    /** The code page applied to {@code WORKING-STORAGE} text. Never the platform default. */
    public static final Charset DEFAULT_WORKING_STORAGE_CHARSET = StandardCharsets.US_ASCII;

    // =================================================================================================
    // Declared widths, transcribed from the source rather than inferred.
    // =================================================================================================

    /** {@code WS-MESSAGE PIC X(80)} - {@code COUSR01C:38}. Two wider than the field it feeds. */
    public static final int WS_MESSAGE_LENGTH = UserAddResponse.WS_MESSAGE_LENGTH;

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COUSR01C'} - {@code COUSR01C:36}. */
    public static final String WS_PGMNAME = UserAddResponse.PROGRAM_NAME;

    /** {@code WS-TRANID PIC X(04) VALUE 'CU01'} - {@code COUSR01C:37}. */
    public static final String WS_TRANID = UserAddResponse.TRANSACTION_ID;

    /** {@code MAP('COUSR1A')} - {@code COUSR01C:191}, {@code 204}. */
    public static final String MAP_NAME = UserAddResponse.MAP_NAME;

    /** {@code MAPSET('COUSR01')} - {@code COUSR01C:192}, {@code 205}. */
    public static final String MAPSET_NAME = UserAddResponse.MAPSET_NAME;

    // =================================================================================================
    // The two programs this screen transfers to, and the one it falls back to.
    // =================================================================================================

    /**
     * {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} - {@code COUSR01C:79} and {@code 168}.
     *
     * <p>Used twice, for two different reasons: on entry with no communication area, and inside
     * {@code RETURN-TO-PREV-SCREEN} as the fallback when the caller named no target.
     */
    public static final String SIGNON_PROGRAM = "COSGN00C";

    /** {@code MOVE 'COADM01C' TO CDEMO-TO-PROGRAM} - {@code COUSR01C:94}, the PF3 target. */
    public static final String ADMIN_MENU_PROGRAM = "COADM01C";

    // =================================================================================================
    // Message literals, byte-exact. Every one is transcribed character for character from the source
    // line cited, including the ellipsis: the five field messages and the two failure messages end
    // '...' with NO preceding space, while the success suffix begins ' has been added ...' WITH one.
    // A field-for-field differ compares these as text, so a single stray space is a parity failure.
    // =================================================================================================

    /** {@code COUSR01C:120-121}, arm one of the blank-field chain. */
    public static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** {@code COUSR01C:126-127}, arm two. */
    public static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /** {@code COUSR01C:132-133}, arm three. */
    public static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** {@code COUSR01C:138-139}, arm four. */
    public static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** {@code COUSR01C:144-145}, arm five. */
    public static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /**
     * {@code 'User '} - the first {@code STRING} operand at {@code COUSR01C:255}, contributed
     * {@code DELIMITED BY SIZE} so all five characters including the trailing space are transferred.
     */
    public static final String MSG_USER_ADDED_PREFIX = "User ";

    /**
     * {@code ' has been added ...'} - the third {@code STRING} operand at {@code COUSR01C:257}, also
     * {@code DELIMITED BY SIZE}. Nineteen characters, leading space included.
     */
    public static final String MSG_USER_ADDED_SUFFIX = " has been added ...";

    /** {@code COUSR01C:263-264}, shared by the {@code DUPKEY} and {@code DUPREC} arms. */
    public static final String MSG_USER_ID_EXISTS = "User ID already exist...";

    /** {@code COUSR01C:270-271}, the {@code WHEN OTHER} write-failure arm. */
    public static final String MSG_UNABLE_TO_ADD = "Unable to Add User...";

    // =================================================================================================
    // Cursor targets, named by the symbolic-map item that receives the MOVE -1. Carried as the COBOL
    // item name so a parity comparison reads FNAMEL rather than an invented ordinal.
    // =================================================================================================

    /** {@code FNAMEL OF COUSR1AI} - the cursor target at lines 86, 100, 122, 149, 272 and 289. */
    public static final String CURSOR_FNAME = "FNAMEL";

    /** {@code LNAMEL OF COUSR1AI} - {@code COUSR01C:128}. */
    public static final String CURSOR_LNAME = "LNAMEL";

    /** {@code USERIDL OF COUSR1AI} - {@code COUSR01C:134} and {@code 265}. */
    public static final String CURSOR_USERID = "USERIDL";

    /** {@code PASSWDL OF COUSR1AI} - {@code COUSR01C:140}. */
    public static final String CURSOR_PASSWD = "PASSWDL";

    /** {@code USRTYPEL OF COUSR1AI} - {@code COUSR01C:146}. */
    public static final String CURSOR_USRTYPE = "USRTYPEL";

    /**
     * The value moved into an {@code xxxL} item to request the cursor. {@code -1} is the CICS
     * convention and is what every one of the eleven cursor moves in this program uses.
     */
    public static final int CURSOR_REQUESTED = -1;

    /**
     * Every cursor target this screen can select, in the order the source first uses each. Published
     * so a test can assert the set is closed rather than trusting a string literal at a call site.
     */
    public static final List<String> CURSOR_FIELDS =
            List.of(CURSOR_FNAME, CURSOR_LNAME, CURSOR_USERID, CURSOR_PASSWD, CURSOR_USRTYPE);

    // =================================================================================================
    // Figurative constants and the attention-identifier byte range.
    // =================================================================================================

    /** A single space, the {@code SPACES} figurative constant at field width one. */
    private static final char SPACE = ' ';

    /**
     * {@code LOW-VALUES} - the lowest character in the collating sequence, {@code x'00'}. Modelled the
     * same way {@link SecUserRepository#LOW_VALUES_KEY} models it, so the two agree.
     */
    private static final char LOW_VALUE = '\u0000';

    /**
     * {@code MOVE SPACES TO WS-MESSAGE} at the field's declared width - {@code COUSR01C:75} and
     * {@code 253} and {@code 295}.
     *
     * <p>A {@link String} is immutable, so publishing this as a constant is not shared mutable state;
     * it is the same value the three source lines produce, computed once.
     */
    private static final String SPACES_MESSAGE = String.valueOf(SPACE).repeat(WS_MESSAGE_LENGTH);

    /** Lowest value an {@code EIBAID} byte can carry, as an unsigned quantity. */
    private static final int AID_MIN = 0;

    /** Highest value an {@code EIBAID} byte can carry, as an unsigned quantity. */
    private static final int AID_MAX = 255;

    /**
     * The response code recorded when the repository reports no CICS {@code RESP} at all - a permanent
     * error, where {@code CicsResponse.none()} carries an empty response. Chosen so it can never be
     * mistaken for {@link FileStatus#NORMAL}, {@link FileStatus#DUPKEY} or {@link FileStatus#DUPREC},
     * which sends it down the {@code WHEN OTHER} arm exactly as an unrecognised {@code RESP} would.
     *
     * <p>Taken from {@link FileStatus#RESP_NOT_REPORTED} rather than declared here. This controller had
     * the convention first and had it right; it is now the module's single convention, shared by every
     * online program that captures a {@code RESP}, so the meaning of the value cannot differ between two
     * screens reading the same repository.
     */
    private static final int RESP_NOT_REPORTED = FileStatus.RESP_NOT_REPORTED;

    // =================================================================================================
    // Collaborators. All three are final and constructor-injected; there is no field injection and no
    // static mutable state, so nothing survives between calls (practice B9).
    // =================================================================================================

    /**
     * The {@code USRSEC} dataset. {@code COUSR01C:240-248} issues
     * {@code EXEC CICS WRITE DATASET(WS-USRSEC-FILE)} against it; the dataset name itself is the
     * repository's business, so no {@code AWS.M2.CARDDEMO} literal appears in this class.
     */
    private final SecUserRepository secUserRepository;

    /**
     * {@code FUNCTION CURRENT-DATE} - read at {@code COUSR01C:216} and nowhere else. Injected so a
     * parity case can pin the instant and compare header bytes; the wall clock is never consulted
     * directly.
     */
    private final Clock clock;

    /**
     * The {@code PIC X} move rule at the declared code page. Every pad and every truncation in this
     * class routes through it, so the direction of truncation is a stated decision rather than an
     * accident of Java string handling.
     */
    private final FixedWidthCodec codec;

    // =================================================================================================
    // Construction.
    // =================================================================================================

    /**
     * The bean constructor, applying {@link #DEFAULT_WORKING_STORAGE_CHARSET}.
     *
     * @param secUserRepository the {@code USRSEC} dataset; must not be {@code null}
     * @param clock             the source of {@code FUNCTION CURRENT-DATE}; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    @Autowired
    public UserAddController(SecUserRepository secUserRepository, Clock clock) {
        this(secUserRepository, clock, DEFAULT_WORKING_STORAGE_CHARSET);
    }

    /**
     * The full constructor, taking the {@code WORKING-STORAGE} code page explicitly.
     *
     * @param secUserRepository     the {@code USRSEC} dataset; must not be {@code null}
     * @param clock                 the source of {@code FUNCTION CURRENT-DATE}; must not be {@code null}
     * @param workingStorageCharset the code page applied to screen and message text; must not be
     *                              {@code null}, and is never defaulted from the platform
     * @throws NullPointerException if any argument is {@code null}
     */
    public UserAddController(SecUserRepository secUserRepository,
                             Clock clock,
                             Charset workingStorageCharset) {
        this.secUserRepository = Objects.requireNonNull(secUserRepository, "A SecUserRepository is "
                + "required: app/cbl/COUSR01C.cbl:240-248 issues EXEC CICS WRITE against the USRSEC "
                + "dataset, and the dataset is reached only through the repository");
        this.clock = Objects.requireNonNull(clock, "A Clock is required: FUNCTION CURRENT-DATE is read "
                + "from it at app/cbl/COUSR01C.cbl:216, never from the wall clock, so a parity case can "
                + "pin the instant and compare the header bytes");
        Objects.requireNonNull(workingStorageCharset, "A code page is required for the PIC X move "
                + "rule; it is never the platform default");
        this.codec = new FixedWidthCodec(workingStorageCharset);
    }

    // =================================================================================================
    // The HTTP adapter. Thin by design: it binds, delegates and projects. It mentions no servlet type
    // and holds no decision of its own, so every branch below is reachable from a plain method call.
    // =================================================================================================

    /**
     * {@code POST /api/users} - CICS transaction {@code CU01}.
     *
     * <p>The body is optional so that the {@code EIBCALEN = 0} branch of {@code COUSR01C:78} is
     * representable: a caller with no communication area and nothing typed is a legitimate, handled
     * input, and the program answers it by transferring to sign-on.
     *
     * <p>No {@code consumes} is declared, precisely so a body-less call binds rather than being
     * refused with an unsupported-media-type before the program runs.
     *
     * <h2>Why this method is transactional and {@link #mainPara} is not annotated</h2>
     *
     * <p>Line 240 issues {@code EXEC CICS WRITE} against {@code USRSEC}. A CICS task always has a unit
     * of work, and the task's syncpoint at {@code RETURN} is what makes that write durable - so the
     * write and the task boundary are the same boundary. The module's pool runs with
     * {@code auto-commit: false} (that is what lets {@code COUSR02C}'s {@code READ ... UPDATE} hold a
     * row lock across its {@code REWRITE}), which means an insert issued with no transaction open is
     * rolled back when the connection returns to the pool. The screen would still have said
     * {@code 'User ... has been added ...'}, because the repository reported {@code NORMAL} and the
     * repository was telling the truth about the statement it executed.
     *
     * <p>{@code @Transactional} here reproduces the task's unit of work, and it is placed on the HTTP
     * boundary rather than deeper for the same reason {@code COUSR02C}'s is: a parity test drives
     * {@link #mainPara} directly with a stubbed repository, where there is no connection to commit and
     * no transaction to want. Annotating the entry point keeps the runtime correct without putting
     * infrastructure in the path of the program's own tests.
     *
     * @param request  the {@code xxxI} projection of the received map; may be {@code null}
     * @param eibAid   the raw {@code EIBAID} byte as an unsigned {@code 0}-{@code 255} value; optional
     * @param eibcalen the communication-area length; optional, derived from the payload when absent
     * @return the {@code xxxO} projection of the map the program painted, or of the screen it
     *         transferred to
     * @throws IllegalArgumentException if {@code eibAid} is outside {@code 0}-{@code 255}, or if
     *                                  {@code eibcalen} is neither {@code 0} nor
     *                                  {@value NavigationContext#COMMAREA_LENGTH} or disagrees with what
     *                                  the payload carried
     */
    @PostMapping(path = USERS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ScreenResponse<UserAddResponse> addUser(
            @Valid @RequestBody(required = false) UserAddRequest request,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibAid,
            @RequestParam(name = EIBCALEN_PARAM, required = false) Integer eibcalen) {
        ProgramState state =
                mainPara(request, resolveEibAid(eibAid, request), resolveEibcalen(eibcalen, request));
        return ScreenResponse.of(state.response(), state.screenMetadata());
    }

    // =================================================================================================
    // MAIN-PARA - app/cbl/COUSR01C.cbl:71-110.
    // =================================================================================================

    /**
     * {@code MAIN-PARA} - {@code COUSR01C:71-110}, with the attention identifier and the
     * communication-area length derived from the payload.
     *
     * <p>Equivalent to {@link #mainPara(UserAddRequest, byte, int)} called with the same defaults the
     * HTTP adapter applies.
     *
     * @param request the {@code xxxI} projection of the received map; may be {@code null}
     * @return the state the program ended in
     */
    public ProgramState mainPara(UserAddRequest request) {
        return mainPara(request, resolveEibAid(null, request), resolveEibcalen(null, request));
    }

    /**
     * {@code MAIN-PARA} - {@code COUSR01C:71-110}.
     *
     * <p>The paragraph in full, in source order:
     *
     * <pre>
     *   SET ERR-FLG-OFF TO TRUE                                        L73
     *   MOVE SPACES TO WS-MESSAGE, ERRMSGO OF COUSR1AO                 L75-76
     *   IF EIBCALEN = 0                                                L78
     *       MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM                        L79
     *       PERFORM RETURN-TO-PREV-SCREEN                              L80
     *   ELSE
     *       MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA          L82
     *       IF NOT CDEMO-PGM-REENTER                                   L83
     *           SET CDEMO-PGM-REENTER TO TRUE                          L84
     *           MOVE LOW-VALUES TO COUSR1AO                            L85
     *           MOVE -1 TO FNAMEL OF COUSR1AI                          L86
     *           PERFORM SEND-USRADD-SCREEN                             L87
     *       ELSE
     *           PERFORM RECEIVE-USRADD-SCREEN                          L89
     *           EVALUATE EIBAID                                        L90
     *               WHEN DFHENTER  ... PROCESS-ENTER-KEY               L91-92
     *               WHEN DFHPF3    ... 'COADM01C', RETURN-TO-PREV       L93-95
     *               WHEN DFHPF4    ... CLEAR-CURRENT-SCREEN            L96-97
     *               WHEN OTHER     ... invalid key, cursor, SEND       L98-102
     *   EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA) L107-110
     * </pre>
     *
     * <p>Three details of that listing are load-bearing and easy to lose in translation:
     *
     * <ul>
     *   <li><strong>First entry neither validates nor writes.</strong> The {@code NOT
     *       CDEMO-PGM-REENTER} arm flips the context to re-enter, blanks the output map and paints an
     *       empty screen. Nothing is read from the five data fields and the dataset is not touched.</li>
     *   <li><strong>{@code MOVE LOW-VALUES TO COUSR1AO} blanks the whole output map</strong>, header
     *       included - but {@code SEND-USRADD-SCREEN} then repopulates the six header fields and the
     *       message, so only the five data fields are left at {@code LOW-VALUES}.</li>
     *   <li><strong>{@code EXEC CICS RETURN} is not reached on a transfer path.</strong> {@code XCTL}
     *       passes control to another program and does not come back, so the two arms that perform
     *       {@code RETURN-TO-PREV-SCREEN} end the task there. {@link ProgramState#returned()} records
     *       which of the two endings actually happened.</li>
     * </ul>
     *
     * <p>The {@code WHEN OTHER} arm at {@code COUSR01C:98-102} does three things, not two: it raises
     * the error flag, <em>moves the cursor to the first-name field</em> (line 100) and sets the
     * invalid-key message. The cursor move is easy to overlook and is reproduced here.
     *
     * @param request  the {@code xxxI} projection of the received map; may be {@code null}, which is
     *                 treated as a map where nothing was typed
     * @param eibAid   the {@code EIBAID} byte the terminal presented
     * @param eibcalen the communication-area length; {@code 0} selects the sign-on transfer
     * @return the state the program ended in, carrying the response, the message, the error flag, the
     *         cursor and the message colour
     * @throws IllegalArgumentException if {@code eibcalen} is negative
     */
    public ProgramState mainPara(UserAddRequest request, byte eibAid, int eibcalen) {
        if (eibcalen < 0) {
            throw new IllegalArgumentException("EIBCALEN is a length and cannot be negative, but was "
                    + eibcalen + ". app/cbl/COUSR01C.cbl:78 tests it against zero and line 82 uses it "
                    + "as a reference-modification length, so a negative value has no meaning.");
        }

        ProgramState state = new ProgramState(codec, eibAid, eibcalen);

        // L73: SET ERR-FLG-OFF TO TRUE. The flag starts explicitly off on every entry.
        state.setErrFlgOff();

        // L75-76: MOVE SPACES TO WS-MESSAGE, ERRMSGO OF COUSR1AO. Both are blanked, and both are
        // blanked to SPACES rather than LOW-VALUES.
        state.setMessage(SPACES_MESSAGE);
        state.setErrMsg(spaces(UserAddResponse.ERR_MSG_LENGTH));

        if (eibcalen == 0) {
            // L79-80: no communication area at all. The screen cannot be painted because there is no
            // context to paint it from, so the program hands control to sign-on.
            state.setCommarea(state.commarea().withToProgram(SIGNON_PROGRAM));
            returnToPrevScreen(state);
            return state;
        }

        // L82: MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA. The caller's context is adopted
        // wholesale; a payload that carried none is treated as an empty one.
        state.setCommarea(commareaOf(request));

        if (!state.commarea().isReenter()) {
            // L84-87: first entry. Flip the context, blank the output map, place the cursor and paint.
            // Neither validation nor the write is reached from here.
            state.setCommarea(state.commarea().withPgmReenter());
            moveLowValuesToOutputMap(state);
            state.setCursorField(CURSOR_FNAME);
            sendUsraddScreen(state);
        } else {
            // L89: PERFORM RECEIVE-USRADD-SCREEN. The payload IS the received map.
            receiveUsraddScreen(state, request);

            // L90-103: EVALUATE EIBAID. The arms are tested in source order and WHEN OTHER is last, so
            // a key matching nothing - including an attention identifier the resolver does not
            // recognise at all - falls through to the invalid-key answer.
            Optional<PfKeyResolver.AidKey> aidKey = PfKeyResolver.resolve(eibAid);
            state.setAidKey(aidKey);

            if (aidKey.isPresent() && aidKey.get() == PfKeyResolver.AidKey.ENTER) {
                // L91-92: WHEN DFHENTER.
                processEnterKey(state);
            } else if (aidKey.isPresent() && aidKey.get() == PfKeyResolver.AidKey.PFK03) {
                // L93-95: WHEN DFHPF3. Back to the administrative menu, by transfer.
                state.setCommarea(state.commarea().withToProgram(ADMIN_MENU_PROGRAM));
                returnToPrevScreen(state);
                return state;
            } else if (aidKey.isPresent() && aidKey.get() == PfKeyResolver.AidKey.PFK04) {
                // L96-97: WHEN DFHPF4. Clear what was typed and repaint.
                clearCurrentScreen(state);
            } else {
                // L98-102: WHEN OTHER. Three effects, all of them reproduced.
                state.setErrFlgOn();
                state.setCursorField(CURSOR_FNAME);
                state.setMessage(movePicXToMessage(SystemMessages.CCDA_MSG_INVALID_KEY));
                sendUsraddScreen(state);
            }
        }

        // L107-110: EXEC CICS RETURN TRANSID('CU01') COMMAREA(CARDDEMO-COMMAREA). Reached on every arm
        // that did not transfer, which is what keeps the conversation on this transaction.
        state.setReturned(true);
        return state;
    }

    // =================================================================================================
    // PROCESS-ENTER-KEY - app/cbl/COUSR01C.cbl:115-160.
    // =================================================================================================

    /**
     * {@code PROCESS-ENTER-KEY} - {@code COUSR01C:115-160}.
     *
     * <p>An ordered {@code EVALUATE TRUE} in which the <strong>first</strong> matching arm wins,
     * followed by a single guarded write. The order is the whole of the contract: a submission with
     * two fields blank produces the message of the <em>earlier</em> arm, so reordering the chain - even
     * into the order the fields appear on the screen or in the record - changes the answer.
     *
     * <table border="1">
     *   <caption>The blank-field chain, in source order</caption>
     *   <tr><th>Arm</th><th>Tested</th><th>Lines</th><th>Message</th><th>Cursor</th></tr>
     *   <tr><td>1</td><td>{@code FNAMEI}</td><td>118-123</td>
     *       <td>{@value #MSG_FIRST_NAME_EMPTY}</td><td>{@value #CURSOR_FNAME}</td></tr>
     *   <tr><td>2</td><td>{@code LNAMEI}</td><td>124-129</td>
     *       <td>{@value #MSG_LAST_NAME_EMPTY}</td><td>{@value #CURSOR_LNAME}</td></tr>
     *   <tr><td>3</td><td>{@code USERIDI}</td><td>130-135</td>
     *       <td>{@value #MSG_USER_ID_EMPTY}</td><td>{@value #CURSOR_USERID}</td></tr>
     *   <tr><td>4</td><td>{@code PASSWDI}</td><td>136-141</td>
     *       <td>{@value #MSG_PASSWORD_EMPTY}</td><td>{@value #CURSOR_PASSWD}</td></tr>
     *   <tr><td>5</td><td>{@code USRTYPEI}</td><td>142-147</td>
     *       <td>{@value #MSG_USER_TYPE_EMPTY}</td><td>{@value #CURSOR_USRTYPE}</td></tr>
     *   <tr><td>6</td><td>{@code WHEN OTHER}</td><td>148-150</td>
     *       <td>none</td><td>{@value #CURSOR_FNAME}</td></tr>
     * </table>
     *
     * <p>Four properties of that chain are preserved deliberately:
     *
     * <ul>
     *   <li><strong>Each test is {@code = SPACES OR LOW-VALUES}</strong>, which is two comparisons
     *       against figurative constants, not one emptiness test. A field of all spaces and a field
     *       never typed both match; a field of <em>mixed</em> spaces and nulls matches neither. See
     *       {@link #isSpacesOrLowValues(String)}.</li>
     *   <li><strong>{@code WHEN OTHER} is not a no-op.</strong> Line 149 moves the cursor to the
     *       first-name field and only then does line 150 {@code CONTINUE}. Both effects happen.</li>
     *   <li><strong>There is no user-type value check.</strong> The map's caption reads
     *       {@code '(A=Admin, U=User)'} ({@code app/bms/COUSR01.bms:150}), but the program tests only
     *       that {@code USRTYPEI} is non-blank. <em>Any</em> single character is accepted and stored.
     *       Adding a whitelist would be a new validation rule.</li>
     *   <li><strong>No value is normalised.</strong> Lines 154-158 carry no {@code FUNCTION
     *       UPPER-CASE}; see this class's own documentation for why that matters.</li>
     * </ul>
     *
     * <p>The write at line 159 is guarded by {@code IF NOT ERR-FLG-ON} (line 153), so it runs only when
     * every arm was skipped - that is, only from the {@code WHEN OTHER} arm.
     *
     * @param state the program state; mutated in place, exactly as {@code WORKING-STORAGE} is
     */
    private void processEnterKey(ProgramState state) {
        // L117-151: EVALUATE TRUE. Ordered, first match wins, WHEN OTHER last.
        if (isSpacesOrLowValues(state.fName())) {
            // L118-123
            failField(state, MSG_FIRST_NAME_EMPTY, CURSOR_FNAME);
        } else if (isSpacesOrLowValues(state.lName())) {
            // L124-129
            failField(state, MSG_LAST_NAME_EMPTY, CURSOR_LNAME);
        } else if (isSpacesOrLowValues(state.userId())) {
            // L130-135
            failField(state, MSG_USER_ID_EMPTY, CURSOR_USERID);
        } else if (isSpacesOrLowValues(state.passwd())) {
            // L136-141
            failField(state, MSG_PASSWORD_EMPTY, CURSOR_PASSWD);
        } else if (isSpacesOrLowValues(state.usrType())) {
            // L142-147
            failField(state, MSG_USER_TYPE_EMPTY, CURSOR_USRTYPE);
        } else {
            // L148-150: WHEN OTHER. The cursor moves, then control continues to the write. The error
            // flag is deliberately NOT raised and no screen is sent from here.
            state.setCursorField(CURSOR_FNAME);
        }

        // L153-160: IF NOT ERR-FLG-ON. Reached on every arm, but true only after WHEN OTHER.
        if (!state.errFlgOn()) {
            // L154-158: the five moves into SEC-USER-DATA, in the source's own order - USERID first,
            // then FNAME, LNAME, PASSWD, USRTYPE. Each screen field is exactly as wide as the record
            // field it feeds (FNAME/LNAME X(20), USERID/PASSWD X(8), USRTYPE X(1)), so these are clean
            // same-width moves; they are still routed through the codec's PIC X rule so the padding is
            // explicit. SEC-USR-FILLER X(23) is not assigned by this program and is space-filled by
            // SecUserRecord, which is what keeps the record exactly eighty bytes.
            state.setSecUserData(SecUserRecord.of(state.userId(),
                    state.fName(),
                    state.lName(),
                    state.passwd(),
                    state.usrType(),
                    codec.charset()));

            // L159: PERFORM WRITE-USER-SEC-FILE.
            writeUserSecFile(state);
        }
    }

    /**
     * The shared body of the five failing arms of {@code PROCESS-ENTER-KEY}.
     *
     * <p>Every one of arms one to five performs the same four statements in the same order - raise the
     * flag, set the message, place the cursor, paint the screen - differing only in the message and the
     * cursor target. Factoring them keeps the ordering visible in one place instead of five.
     *
     * @param state       the program state
     * @param message     the byte-exact literal moved into {@code WS-MESSAGE}
     * @param cursorField the {@code xxxL} item receiving {@value #CURSOR_REQUESTED}
     */
    private void failField(ProgramState state, String message, String cursorField) {
        state.setErrFlgOn();
        state.setMessage(movePicXToMessage(message));
        state.setCursorField(cursorField);
        sendUsraddScreen(state);
    }

    // =================================================================================================
    // RETURN-TO-PREV-SCREEN - app/cbl/COUSR01C.cbl:165-178.
    // =================================================================================================

    /**
     * {@code RETURN-TO-PREV-SCREEN} - {@code COUSR01C:165-178}.
     *
     * <pre>
     *   IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES                     L167
     *       MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM                        L168
     *   END-IF
     *   MOVE WS-TRANID    TO CDEMO-FROM-TRANID                         L170
     *   MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM                        L171
     *  *MOVE WS-USER-ID   TO CDEMO-USER-ID                             L172  &lt;- commented out
     *  *MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE                           L173  &lt;- commented out
     *   MOVE ZEROS        TO CDEMO-PGM-CONTEXT                         L174
     *   EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(...)         L175-178
     * </pre>
     *
     * <p><strong>Lines 172 and 173 do not execute.</strong> Neither {@code CDEMO-USER-ID} nor
     * {@code CDEMO-USER-TYPE} is written here, and reproducing either would leak state onto the next
     * screen that the legacy program never leaked. {@code WS-USER-ID} is not even declared in this
     * program's {@code WS-VARIABLES}, so line 172 could not compile if it were enabled - which is
     * corroborating evidence that it is dead rather than merely dormant.
     *
     * <p>Note also that {@code CDEMO-PGM-CONTEXT} is reset to zero, i.e. to {@code CDEMO-PGM-ENTER}.
     * The screen being transferred to therefore sees a first entry, not a re-entry.
     *
     * <p>{@code XCTL} becomes data: the target is published as {@link UserAddResponse#nextProgram()}
     * and the client makes the next call. Because {@code XCTL} does not return, the caller stops here
     * and the {@code EXEC CICS RETURN} at the end of {@code MAIN-PARA} is never reached - which
     * {@link ProgramState#returned()} reflects by staying {@code false}.
     *
     * @param state the program state
     */
    private void returnToPrevScreen(ProgramState state) {
        // L167-169: the fallback. A caller that named no target goes to sign-on.
        if (isSpacesOrLowValues(state.commarea().toProgram())) {
            state.setCommarea(state.commarea().withToProgram(SIGNON_PROGRAM));
        }

        // L170-171, L174. Lines 172-173 are commented out in the source and are not reproduced.
        state.setCommarea(state.commarea()
                .withFromTranid(WS_TRANID)
                .withFromProgram(WS_PGMNAME)
                .withPgmEnter());

        // L175-178: EXEC CICS XCTL. Stateless: the target is a response field, never a forward. The
        // COBOL passes PROGRAM and COMMAREA only - no MAP and no MAPSET - so neither is published.
        state.setNextProgram(state.commarea().toProgram());
        state.setTransferred(true);
    }

    // =================================================================================================
    // SEND-USRADD-SCREEN and RECEIVE-USRADD-SCREEN - app/cbl/COUSR01C.cbl:184-209.
    // =================================================================================================

    /**
     * {@code SEND-USRADD-SCREEN} - {@code COUSR01C:184-196}.
     *
     * <pre>
     *   PERFORM POPULATE-HEADER-INFO                                   L186
     *   MOVE WS-MESSAGE TO ERRMSGO OF COUSR1AO                         L188
     *   EXEC CICS SEND MAP('COUSR1A') MAPSET('COUSR01')
     *                  FROM(COUSR1AO) ERASE CURSOR                    L190-196
     * </pre>
     *
     * <p>The move at line 188 is the one narrowing in this program: {@code WS-MESSAGE} is
     * {@code PIC X(80)} ({@code COUSR01C:38}) but {@code ERRMSGO} is {@code PIC X(78)}
     * ({@code app/cpy-bms/COUSR01.CPY:164}). An alphanumeric {@code MOVE} into a narrower receiver
     * discards the <strong>rightmost</strong> characters, so the last two are lost. That is performed
     * by {@link FixedWidthCodec#movePicX(String, int)} rather than by a Java {@code substring}, so the
     * direction of truncation is a stated decision.
     *
     * <p>{@code CURSOR} is what makes the {@code MOVE -1} into an {@code xxxL} item take effect, and
     * {@code ERASE} is why the screen is repainted whole. Because this projection is a REST response
     * rather than a 3270 data stream, both are recorded on the state rather than transmitted.
     *
     * @param state the program state
     */
    private void sendUsraddScreen(ProgramState state) {
        // L186
        populateHeaderInfo(state);

        // L188: the 80-into-78 narrowing, truncating on the right.
        state.setErrMsg(codec.movePicX(state.message(), UserAddResponse.ERR_MSG_LENGTH));

        // L190-196: EXEC CICS SEND ... ERASE CURSOR.
        state.recordSend();
    }

    /**
     * {@code RECEIVE-USRADD-SCREEN} - {@code COUSR01C:201-209}.
     *
     * <pre>
     *   EXEC CICS RECEIVE MAP('COUSR1A') MAPSET('COUSR01')
     *                     INTO(COUSR1AI) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)   L203-209
     * </pre>
     *
     * <p>In CICS this fills {@code COUSR1AI} from the terminal's inbound data stream. Here the request
     * payload <em>is</em> that inbound map, so receiving means adopting the payload's twelve fields into
     * the map storage at their declared widths.
     *
     * <p>Each value is put through the codec's {@code PIC X} rule, which pads a short value on the
     * right and truncates an over-long one on the right - the same thing CICS does when it moves
     * terminal data into a fixed-width symbolic-map item. <strong>Nothing else is done to any value.</strong>
     * In particular the five data fields are not upper-cased, not trimmed and not defaulted, because
     * {@code COUSR01C} does none of those things to them.
     *
     * <p>A {@code null} payload is treated as a map where nothing was typed: every field becomes
     * {@code LOW-VALUES} at its declared width, which is exactly what the blank-field chain then
     * detects.
     *
     * <p>The program ignores the {@code RESP} and {@code RESP2} it requests here - it neither tests
     * them nor branches on them - so no outcome is derived from the receive.
     *
     * @param state   the program state
     * @param request the received map; may be {@code null}
     */
    private void receiveUsraddScreen(ProgramState state, UserAddRequest request) {
        if (request == null) {
            moveLowValuesToOutputMap(state);
            return;
        }

        // The six header fields. The program overwrites all of them in POPULATE-HEADER-INFO before the
        // next send, but they are adopted first because RECEIVE genuinely does overlay the whole map.
        state.setTrnName(receiveField(request.trnName(), UserAddRequest.TRNNAME_LENGTH));
        state.setTitle01(receiveField(request.title01(), UserAddRequest.TITLE01_LENGTH));
        state.setCurDate(receiveField(request.curDate(), UserAddRequest.CURDATE_LENGTH));
        state.setPgmName(receiveField(request.pgmName(), UserAddRequest.PGMNAME_LENGTH));
        state.setTitle02(receiveField(request.title02(), UserAddRequest.TITLE02_LENGTH));
        state.setCurTime(receiveField(request.curTime(), UserAddRequest.CURTIME_LENGTH));

        // The five data fields, verbatim at their declared widths. No normalisation of any kind.
        state.setFName(receiveField(request.fName(), UserAddRequest.FNAME_LENGTH));
        state.setLName(receiveField(request.lName(), UserAddRequest.LNAME_LENGTH));
        state.setUserId(receiveField(request.userId(), UserAddRequest.USERID_LENGTH));
        state.setPasswd(receiveField(request.passwd(), UserAddRequest.PASSWD_LENGTH));
        state.setUsrType(receiveField(request.usrType(), UserAddRequest.USRTYPE_LENGTH));

        // The message field as it came back from the terminal. MAIN-PARA has already blanked it at
        // line 76 and SEND-USRADD-SCREEN will overwrite it at line 188, so this only matters to a
        // caller inspecting the received map.
        state.setErrMsg(receiveField(request.errMsg(), UserAddResponse.ERR_MSG_LENGTH));
    }

    /**
     * One field of an {@code EXEC CICS RECEIVE MAP}, brought to its declared width.
     *
     * <p>A field the caller omitted was never typed, which in map storage is {@code LOW-VALUES}; a
     * field the caller supplied is moved in under the {@code PIC X} rule. The distinction matters
     * because the blank-field chain tests against both figurative constants.
     *
     * @param value         the received value, or {@code null} if the caller omitted the field
     * @param declaredWidth the {@code xxxI} {@code PICTURE} width
     * @return the value at exactly {@code declaredWidth} characters
     */
    private String receiveField(String value, int declaredWidth) {
        if (value == null) {
            return lowValues(declaredWidth);
        }
        return codec.movePicX(value, declaredWidth);
    }

    // =================================================================================================
    // POPULATE-HEADER-INFO - app/cbl/COUSR01C.cbl:214-233.
    // =================================================================================================

    /**
     * {@code POPULATE-HEADER-INFO} - {@code COUSR01C:214-233}.
     *
     * <pre>
     *   MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA                  L216
     *   MOVE CCDA-TITLE01          TO TITLE01O                         L218
     *   MOVE CCDA-TITLE02          TO TITLE02O                         L219
     *   MOVE WS-TRANID             TO TRNNAMEO                         L220
     *   MOVE WS-PGMNAME            TO PGMNAMEO                         L221
     *   MOVE WS-CURDATE-MONTH      TO WS-CURDATE-MM                    L223
     *   MOVE WS-CURDATE-DAY        TO WS-CURDATE-DD                    L224
     *   MOVE WS-CURDATE-YEAR(3:2)  TO WS-CURDATE-YY                    L225
     *   MOVE WS-CURDATE-MM-DD-YY   TO CURDATEO                         L227
     *   MOVE WS-CURTIME-HOURS      TO WS-CURTIME-HH                    L229
     *   MOVE WS-CURTIME-MINUTE     TO WS-CURTIME-MM                    L230
     *   MOVE WS-CURTIME-SECOND     TO WS-CURTIME-SS                    L231
     *   MOVE WS-CURTIME-HH-MM-SS   TO CURTIMEO                         L233
     * </pre>
     *
     * <p>The reference modification {@code WS-CURDATE-YEAR(3:2)} takes the last two digits of the
     * four-digit year, which is why the header shows {@code mm/dd/yy} rather than a full year.
     * {@link DateHeader} composes both eight-character values from the injected {@link Clock}, so
     * {@code FUNCTION CURRENT-DATE} is read from exactly one place and nothing here consults the wall
     * clock.
     *
     * <p>{@code CURTIMEO} is {@code PIC X(8)} on this screen. Only {@code app/cpy-bms/COSGN00.CPY}
     * declares a nine-character time field, and this program has no {@code EXEC CICS ASSIGN APPLID} or
     * {@code SYSID}, so there is no application-identifier field to populate either.
     *
     * @param state the program state
     */
    private void populateHeaderInfo(ProgramState state) {
        // L216: FUNCTION CURRENT-DATE, read once per send from the injected clock.
        DateHeader header = DateHeader.from(codec, clock);
        state.setDateHeader(header);

        // L218-221
        state.setTitle01(codec.movePicX(ScreenTitles.CCDA_TITLE01, UserAddResponse.TITLE01_LENGTH));
        state.setTitle02(codec.movePicX(ScreenTitles.CCDA_TITLE02, UserAddResponse.TITLE02_LENGTH));
        state.setTrnName(codec.movePicX(WS_TRANID, UserAddResponse.TRN_NAME_LENGTH));
        state.setPgmName(codec.movePicX(WS_PGMNAME, UserAddResponse.PGM_NAME_LENGTH));

        // L223-227: the mm/dd/yy header date.
        state.setCurDate(codec.movePicX(header.wsCurdateMmDdYy(), UserAddResponse.CUR_DATE_LENGTH));

        // L229-233: the hh:mm:ss header time.
        state.setCurTime(codec.movePicX(header.wsCurtimeHhMmSs(), UserAddResponse.CUR_TIME_LENGTH));
    }

    // =================================================================================================
    // CLEAR-CURRENT-SCREEN and INITIALIZE-ALL-FIELDS - app/cbl/COUSR01C.cbl:279-295.
    // =================================================================================================

    /**
     * {@code CLEAR-CURRENT-SCREEN} - {@code COUSR01C:279-282}. Two statements, in order.
     *
     * <pre>
     *   PERFORM INITIALIZE-ALL-FIELDS                                  L281
     *   PERFORM SEND-USRADD-SCREEN                                     L282
     * </pre>
     *
     * <p>This is the PF4 answer. The error flag is deliberately not raised, so the repainted screen
     * carries no message and no error colour.
     *
     * @param state the program state
     */
    private void clearCurrentScreen(ProgramState state) {
        initializeAllFields(state);
        sendUsraddScreen(state);
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS} - {@code COUSR01C:287-295}.
     *
     * <pre>
     *   MOVE -1     TO FNAMEL OF COUSR1AI                              L289
     *   MOVE SPACES TO USERIDI, FNAMEI, LNAMEI, PASSWDI, USRTYPEI,
     *                  WS-MESSAGE                                      L290-295
     * </pre>
     *
     * <p>Two things about that single {@code MOVE SPACES} are easy to miss:
     *
     * <ul>
     *   <li><strong>{@code WS-MESSAGE} is one of its receivers.</strong> Blanking the screen also
     *       blanks the pending message, which is why PF4 repaints with no text at all.</li>
     *   <li><strong>The header fields are not receivers.</strong> Only the five data items are
     *       blanked, and they are blanked to {@code SPACES} - not to {@code LOW-VALUES} as first entry
     *       does at line 85.</li>
     * </ul>
     *
     * <p>{@code SEC-USER-DATA} is also not a receiver. It lives in {@code WORKING-STORAGE}, not in the
     * map, so it survives this paragraph intact - which is precisely what lets the success message at
     * line 256 still read {@code SEC-USR-ID} after this has run.
     *
     * @param state the program state
     */
    private void initializeAllFields(ProgramState state) {
        // L289
        state.setCursorField(CURSOR_FNAME);

        // L290-294: the five data items, to SPACES.
        state.setUserId(spaces(UserAddResponse.USER_ID_LENGTH));
        state.setFName(spaces(UserAddResponse.F_NAME_LENGTH));
        state.setLName(spaces(UserAddResponse.L_NAME_LENGTH));
        state.setPasswd(spaces(UserAddResponse.PASSWD_LENGTH));
        state.setUsrType(spaces(UserAddResponse.USR_TYPE_LENGTH));

        // L295: and WS-MESSAGE, the receiver that is not a screen field.
        state.setMessage(SPACES_MESSAGE);
    }

    /**
     * {@code MOVE LOW-VALUES TO COUSR1AO} - {@code COUSR01C:85}, and the {@code RECEIVE} of a payload
     * that carried no map at all.
     *
     * <p>Sets the entire map - all twelve fields, header included - to {@code LOW-VALUES} at each
     * field's declared width. On first entry {@code SEND-USRADD-SCREEN} immediately repopulates the six
     * header fields and the message, so only the five data fields are actually left at
     * {@code LOW-VALUES} when the screen is painted.
     *
     * <p>This is distinct from {@link #initializeAllFields(ProgramState)}, which blanks five fields to
     * {@code SPACES} and leaves the header alone. Conflating the two would change what a caller
     * observes on a first entry as against a PF4 clear.
     *
     * @param state the program state
     */
    private void moveLowValuesToOutputMap(ProgramState state) {
        state.setTrnName(lowValues(UserAddResponse.TRN_NAME_LENGTH));
        state.setTitle01(lowValues(UserAddResponse.TITLE01_LENGTH));
        state.setCurDate(lowValues(UserAddResponse.CUR_DATE_LENGTH));
        state.setPgmName(lowValues(UserAddResponse.PGM_NAME_LENGTH));
        state.setTitle02(lowValues(UserAddResponse.TITLE02_LENGTH));
        state.setCurTime(lowValues(UserAddResponse.CUR_TIME_LENGTH));
        state.setFName(lowValues(UserAddResponse.F_NAME_LENGTH));
        state.setLName(lowValues(UserAddResponse.L_NAME_LENGTH));
        state.setUserId(lowValues(UserAddResponse.USER_ID_LENGTH));
        state.setPasswd(lowValues(UserAddResponse.PASSWD_LENGTH));
        state.setUsrType(lowValues(UserAddResponse.USR_TYPE_LENGTH));
        state.setErrMsg(lowValues(UserAddResponse.ERR_MSG_LENGTH));
    }

    // =================================================================================================
    // WRITE-USER-SEC-FILE - app/cbl/COUSR01C.cbl:238-274.
    // =================================================================================================

    /**
     * {@code WRITE-USER-SEC-FILE} - {@code COUSR01C:238-274}. The program's only dataset operation.
     *
     * <pre>
     *   EXEC CICS WRITE DATASET(WS-USRSEC-FILE)
     *                   FROM(SEC-USER-DATA)
     *                   LENGTH(LENGTH OF SEC-USER-DATA)
     *                   RIDFLD(SEC-USR-ID)
     *                   KEYLENGTH(LENGTH OF SEC-USR-ID)
     *                   RESP(WS-RESP-CD) RESP2(WS-REAS-CD)             L240-248
     *   EVALUATE WS-RESP-CD                                            L250
     *       WHEN DFHRESP(NORMAL)  ... confirm, green                   L251-259
     *       WHEN DFHRESP(DUPKEY)                                       L260
     *       WHEN DFHRESP(DUPREC)  ... already exists                   L261-266
     *       WHEN OTHER            ... unable to add                    L267-273
     * </pre>
     *
     * <p>The eighty-byte length and the eight-byte key are properties of
     * {@link SecUserRecord}, and the dataset name is a property of {@link SecUserRepository}, so
     * neither appears here.
     *
     * <h3>The three arms</h3>
     *
     * <p><strong>{@code NORMAL}.</strong> Four statements whose <em>order</em> is the subtle part.
     * {@code INITIALIZE-ALL-FIELDS} runs <strong>first</strong> (line 252), blanking the five screen
     * fields and {@code WS-MESSAGE}; the message is only then composed (lines 255-258). That works
     * because {@code SEC-USR-ID} lives in {@code WORKING-STORAGE}, not in the map, so it survives the
     * blanking and is still readable when the {@code STRING} runs. Translating the two in the other
     * order, or reading the screen field instead of the record field, would produce an empty id in the
     * confirmation.
     *
     * <p><strong>{@code DUPKEY} and {@code DUPREC}.</strong> Lines 260 and 261 are two consecutive
     * {@code WHEN} clauses sharing one action - COBOL's way of writing "either of these". They stay two
     * distinguishable outcomes at the repository boundary, where
     * {@code WriteResult.duplicateKey()} carries {@link FileStatus#DUPKEY} and
     * {@code WriteResult.duplicateRecord()} carries {@link FileStatus#DUPREC}, and are collapsed to one
     * action here exactly as the source collapses them.
     *
     * <p><strong>{@code WHEN OTHER}.</strong> Note the cursor goes to the <strong>first-name</strong>
     * field (line 272), not to the user-id field as the duplicate arm does (line 265). Note also that
     * this arm emits <strong>no diagnostic</strong>: the {@code DISPLAY 'RESP:' ... 'REAS:'} at line 268
     * is commented out in this program, unlike in {@code COUSR00C} and {@code COUSR03C} where the
     * equivalent line is live. A repository that reported no {@code RESP} at all - a permanent error -
     * also lands here, which is the correct answer for a response code the {@code EVALUATE} does not
     * name.
     *
     * @param state the program state, carrying the record to write
     */
    private void writeUserSecFile(ProgramState state) {
        // L240-248: EXEC CICS WRITE. Reached only with a record built, because PROCESS-ENTER-KEY guards
        // the call with IF NOT ERR-FLG-ON.
        SecUserRepository.WriteResult result = secUserRepository.add(state.requireSecUserData());

        // RESP(WS-RESP-CD) and RESP2(WS-REAS-CD). A permanent error reports no RESP at all, which is
        // recorded as RESP_NOT_REPORTED so it can never be mistaken for one of the three named values.
        OptionalInt reportedResp = result.response().resp();
        int respCd = reportedResp.orElse(RESP_NOT_REPORTED);
        state.setRespCd(respCd);
        state.setReasCd(result.response().resp2());
        state.setWriteStatus(result.status());

        // L250-274: EVALUATE WS-RESP-CD, in source order with WHEN OTHER last.
        if (respCd == FileStatus.NORMAL) {
            // L252: PERFORM INITIALIZE-ALL-FIELDS - first, before the message is composed.
            initializeAllFields(state);

            // L253: MOVE SPACES TO WS-MESSAGE. Redundant after INITIALIZE-ALL-FIELDS, which already
            // blanked it at line 295, but reproduced because the source states it. It also matters:
            // STRING does not space-fill the remainder of its receiver, so the receiver being blank
            // beforehand is what leaves the tail of WS-MESSAGE as spaces rather than stale text.
            state.setMessage(SPACES_MESSAGE);

            // L254: MOVE DFHGREEN TO ERRMSGC. The message colour, overriding the map's own COLOR=RED.
            // It is symbolic-map metadata, so it is recorded on the state and never on the payload.
            state.setErrMsgColour(BmsAttributes.DFHGREEN);

            // L255-258: STRING 'User ' DELIMITED BY SIZE, SEC-USR-ID DELIMITED BY SPACE,
            //                  ' has been added ...' DELIMITED BY SIZE INTO WS-MESSAGE.
            // The id is read from the RECORD, which survived the blanking, and is cut at its first
            // space so a short id leaves no padding gap.
            String composed = MSG_USER_ADDED_PREFIX
                    + stringDelimitedBySpace(state.requireSecUserData().secUsrId())
                    + MSG_USER_ADDED_SUFFIX;
            state.setMessage(movePicXToMessage(composed));

            state.setWroteRecord(true);

            // L259
            sendUsraddScreen(state);
        } else if (respCd == FileStatus.DUPKEY || respCd == FileStatus.DUPREC) {
            // L260-266: two WHEN clauses, one action.
            state.setErrFlgOn();
            state.setMessage(movePicXToMessage(MSG_USER_ID_EXISTS));
            state.setCursorField(CURSOR_USERID);
            sendUsraddScreen(state);
        } else {
            // L267-273: WHEN OTHER. The DISPLAY at line 268 is commented out, so nothing is logged.
            state.setErrFlgOn();
            state.setMessage(movePicXToMessage(MSG_UNABLE_TO_ADD));
            state.setCursorField(CURSOR_FNAME);
            sendUsraddScreen(state);
        }
    }

    // =================================================================================================
    // COBOL primitives. Each is a named operation rather than an inline expression, because each is a
    // place a plausible-looking Java idiom would diverge from COBOL and the divergence would be
    // invisible at the call site.
    // =================================================================================================

    /**
     * {@code IF <field> = SPACES OR LOW-VALUES} - the test used by all five arms of
     * {@code PROCESS-ENTER-KEY} ({@code COUSR01C:118}, {@code 124}, {@code 130}, {@code 136},
     * {@code 142}) and by the target fallback in {@code RETURN-TO-PREV-SCREEN} ({@code COUSR01C:167}).
     *
     * <p>This is <strong>two comparisons against figurative constants</strong>, not one emptiness test,
     * and the difference is observable:
     *
     * <ul>
     *   <li>A field of all spaces matches - it equals {@code SPACES}.</li>
     *   <li>A field never typed matches - map storage holds {@code LOW-VALUES}, and a payload that
     *       omitted the field is the same thing.</li>
     *   <li>A field of <strong>mixed</strong> spaces and nulls matches <strong>neither</strong>
     *       constant and is therefore <em>not</em> blank. A Java {@code isBlank()} would wrongly call
     *       it blank, and {@code isEmpty()} would wrongly call an all-spaces field non-blank. Both
     *       would flip an arm of the chain.</li>
     * </ul>
     *
     * <p>A {@code null} is treated as never typed, and so is the empty string: a zero-length value in a
     * fixed-width field is a field holding its width in padding, which matches.
     *
     * @param value the field value; may be {@code null}
     * @return {@code true} if the field equals {@code SPACES} or equals {@code LOW-VALUES}
     */
    static boolean isSpacesOrLowValues(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        boolean allSpaces = true;
        boolean allLowValues = true;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != SPACE) {
                allSpaces = false;
            }
            if (character != LOW_VALUE) {
                allLowValues = false;
            }
            if (!allSpaces && !allLowValues) {
                return false;
            }
        }
        return true;
    }

    /**
     * A {@code STRING} operand contributed {@code DELIMITED BY SPACE} - {@code COUSR01C:256}, where
     * {@code SEC-USR-ID} is the sending item.
     *
     * <p>The operand contributes its characters up to, but <strong>not including</strong>, the first
     * space. So an eight-character id such as {@code "ADMIN001"} contributes all eight and the message
     * reads {@code "User ADMIN001 has been added ..."}, while a four-character id stored as
     * {@code "USR1    "} contributes {@code "USR1"} and the message reads
     * {@code "User USR1 has been added ..."} with no padding gap. An operand that begins with a space
     * contributes nothing at all.
     *
     * <p>Concatenating the padded field instead is the classic defect here, and it is invisible at the
     * call site - which is why this is a named operation rather than a {@code +}. It is deliberately
     * <em>not</em> {@code trim()} or {@code strip()}: those would also remove leading whitespace and
     * would keep text that follows an interior space, and neither is what {@code DELIMITED BY SPACE}
     * does.
     *
     * @param sendingItem the operand at its declared width; must not be {@code null}
     * @return the operand's characters up to the first space, possibly empty
     * @throws NullPointerException if {@code sendingItem} is {@code null}
     */
    public static String stringDelimitedBySpace(String sendingItem) {
        Objects.requireNonNull(sendingItem, "A sending item is required for STRING ... DELIMITED BY "
                + "SPACE; app/cbl/COUSR01C.cbl:256 contributes SEC-USR-ID that way");
        int firstSpace = sendingItem.indexOf(SPACE);
        if (firstSpace < 0) {
            return sendingItem;
        }
        return sendingItem.substring(0, firstSpace);
    }

    /**
     * {@code MOVE '<literal>' TO WS-MESSAGE} - the receiver is {@code PIC X(80)}, so a shorter literal
     * is padded on the right and a longer one would be truncated on the right.
     *
     * @param literal the sending literal
     * @return the literal at exactly {@value #WS_MESSAGE_LENGTH} characters
     */
    private String movePicXToMessage(String literal) {
        return codec.movePicX(literal, WS_MESSAGE_LENGTH);
    }

    /**
     * The {@code SPACES} figurative constant at a given field width.
     *
     * @param width the field's declared width
     * @return a string of exactly {@code width} spaces
     */
    private static String spaces(int width) {
        return String.valueOf(SPACE).repeat(width);
    }

    /**
     * The {@code LOW-VALUES} figurative constant at a given field width.
     *
     * @param width the field's declared width
     * @return a string of exactly {@code width} {@code x'00'} characters
     */
    private static String lowValues(int width) {
        return String.valueOf(LOW_VALUE).repeat(width);
    }

    /**
     * {@code MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA} - {@code COUSR01C:82}.
     *
     * @param request the payload; may be {@code null}
     * @return the caller's communication area, or an empty one if none was passed
     */
    private static NavigationContext commareaOf(UserAddRequest request) {
        if (request == null || request.navigationContext() == null) {
            return NavigationContext.empty();
        }
        return request.navigationContext();
    }

    // =================================================================================================
    // Parameter resolution for the HTTP adapter. Both are package-visible so a test can pin the
    // defaulting rules directly, which is where a wrong default would otherwise hide.
    // =================================================================================================

    /**
     * Resolves the {@code EIBAID} byte that {@code COUSR01C:90} evaluates.
     *
     * <p>Three sources, in precedence order:
     *
     * <ol>
     *   <li>The {@value #EIBAID_PARAM} query parameter, as an unsigned {@code 0}-{@code 255} value.</li>
     *   <li>{@link UserAddRequest#aid()}, the five-character token {@link PfKeyResolver.AidKey}
     *       publishes. The inbound token is brought to its declared width by the codec's {@code PIC X}
     *       rule first, so both {@code "PA1"} and {@code "PA1  "} resolve. A token naming no known key
     *       maps to {@link CicsAid#DFHNULL}, which {@link PfKeyResolver#resolve(byte)} reports as no
     *       match and which therefore reaches {@code WHEN OTHER} - the same answer the program gives
     *       any key it does not handle.</li>
     *   <li>{@link CicsAid#DFHENTER}. A CICS terminal always presents some attention identifier, and
     *       {@code ENTER} is the only default that cannot reach a branch the operator could not have
     *       reached.</li>
     * </ol>
     *
     * @param eibAid  the query parameter value, or {@code null} if absent
     * @param request the payload; may be {@code null}
     * @return the {@code EIBAID} byte to evaluate
     * @throws IllegalArgumentException if {@code eibAid} is outside {@code 0}-{@code 255}
     */
    byte resolveEibAid(Integer eibAid, UserAddRequest request) {
        if (eibAid != null) {
            int value = eibAid;
            if (value < AID_MIN || value > AID_MAX) {
                throw new IllegalArgumentException("The " + EIBAID_PARAM + " parameter carries one "
                        + "EIBAID byte and must be " + AID_MIN + " to " + AID_MAX + ", but was " + value
                        + ". Narrowing it silently would select an attention identifier the caller "
                        + "never pressed.");
            }
            return (byte) value;
        }
        if (request != null && request.aid() != null && !request.aid().isEmpty()) {
            return aidByteOfToken(request.aid());
        }
        return CicsAid.DFHENTER;
    }

    /**
     * Maps a {@link PfKeyResolver.AidKey} token back to the {@code EIBAID} byte it stands for.
     *
     * <p>Written as an exhaustive comparison against the tokens the enum itself publishes, so the two
     * cannot drift apart, and routed through the codec's {@code PIC X} rule so an unpadded spelling
     * matches the padded token. An unrecognised token yields {@link CicsAid#DFHNULL}, which is not an
     * attention identifier the resolver knows.
     *
     * @param token the token as received
     * @return the corresponding {@code EIBAID} byte, or {@link CicsAid#DFHNULL} if the token names no
     *         known key
     */
    private byte aidByteOfToken(String token) {
        String image = codec.movePicX(token, PfKeyResolver.AID_TOKEN_LENGTH);
        if (image.equals(PfKeyResolver.AidKey.ENTER.token())) {
            return CicsAid.DFHENTER;
        }
        if (image.equals(PfKeyResolver.AidKey.CLEAR.token())) {
            return CicsAid.DFHCLEAR;
        }
        if (image.equals(PfKeyResolver.AidKey.PA1.token())) {
            return CicsAid.DFHPA1;
        }
        if (image.equals(PfKeyResolver.AidKey.PA2.token())) {
            return CicsAid.DFHPA2;
        }
        if (image.equals(PfKeyResolver.AidKey.PFK01.token())) {
            return CicsAid.DFHPF1;
        }
        if (image.equals(PfKeyResolver.AidKey.PFK02.token())) {
            return CicsAid.DFHPF2;
        }
        if (image.equals(PfKeyResolver.AidKey.PFK03.token())) {
            return CicsAid.DFHPF3;
        }
        if (image.equals(PfKeyResolver.AidKey.PFK04.token())) {
            return CicsAid.DFHPF4;
        }
        if (image.equals(PfKeyResolver.AidKey.PFK05.token())) {
            return CicsAid.DFHPF5;
        }
        if (image.equals(PfKeyResolver.AidKey.PFK06.token())) {
            return CicsAid.DFHPF6;
        }
        if (image.equals(PfKeyResolver.AidKey.PFK07.token())) {
            return CicsAid.DFHPF7;
        }
        if (image.equals(PfKeyResolver.AidKey.PFK08.token())) {
            return CicsAid.DFHPF8;
        }
        if (image.equals(PfKeyResolver.AidKey.PFK09.token())) {
            return CicsAid.DFHPF9;
        }
        if (image.equals(PfKeyResolver.AidKey.PFK10.token())) {
            return CicsAid.DFHPF10;
        }
        if (image.equals(PfKeyResolver.AidKey.PFK11.token())) {
            return CicsAid.DFHPF11;
        }
        if (image.equals(PfKeyResolver.AidKey.PFK12.token())) {
            return CicsAid.DFHPF12;
        }
        return CicsAid.DFHNULL;
    }

    /**
     * The {@code DFHMDF} label behind an {@code xxxL} item name - {@code FNAMEL} yields {@code FNAME}.
     *
     * <p>Not string surgery for convenience: a BMS symbolic map names each of a field's items by
     * suffixing the {@code DFHMDF} label, so {@code xxxL} is the label plus {@code 'L'} by the
     * generator's own rule - {@code app/cpy-bms/COUSR01.CPY} shows every field's four items built that
     * way. Reporting the label keeps the cursor request readable next to the payload members, which carry
     * the same labels.
     *
     * @param lengthItem the {@code xxxL} item name, for example {@value #CURSOR_FNAME}
     * @return the label; never {@code null}
     */
    static String dfhmdfLabel(String lengthItem) {
        return lengthItem.substring(0, lengthItem.length() - 1);
    }

    /**
     * Resolves {@code EIBCALEN}, the length of the communication area passed in, and refuses any
     * statement the carrier does not support.
     *
     * <h4>Why a caller may not simply declare it</h4>
     * {@code EIBCALEN} is not caller data on a real terminal: CICS sets it to the length of the area it
     * actually passed. {@code app/cbl/COUSR01C.cbl:78} tests it against zero to decide whether the
     * conversation had any state at all - and its zero arm transfers straight to the sign-on program -
     * so a caller free to state it could discard state that was sent, or claim state that was not.
     *
     * <h4>Why the two accepted values are 0 and {@value NavigationContext#COMMAREA_LENGTH}</h4>
     * Line 78 compares against zero and nothing else, and line 82's
     * {@code MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA} reads the copybook's own
     * {@value NavigationContext#COMMAREA_LENGTH} bytes. {@code COUSR01C} copies only
     * {@code app/cpy/COCOM01Y.cpy} - it declares no extension group of its own, unlike its {@code CU02}
     * and {@code CU03} siblings - so the area it is passed is exactly the copybook, and the request is
     * in one of two states: absent, or complete at {@value NavigationContext#COMMAREA_LENGTH} bytes.
     *
     * @param eibcalen the query parameter value, or {@code null} if absent
     * @param request  the payload; may be {@code null}
     * @return {@code 0} or {@value NavigationContext#COMMAREA_LENGTH}
     * @throws IllegalArgumentException if the stated value is neither length, or contradicts the carrier
     */
    static int resolveEibcalen(Integer eibcalen, UserAddRequest request) {
        int carried = request == null || request.navigationContext() == null
                ? 0
                : NavigationContext.COMMAREA_LENGTH;
        if (eibcalen == null) {
            return carried;
        }
        int stated = eibcalen;
        if (stated != 0 && stated != NavigationContext.COMMAREA_LENGTH) {
            throw new IllegalArgumentException("The " + EIBCALEN_PARAM + " parameter is " + stated
                    + ", but CICS sets EIBCALEN to the length of the area it passed - which for this "
                    + "program is either 0 or " + NavigationContext.COMMAREA_LENGTH
                    + ", the whole of CARDDEMO-COMMAREA, since COUSR01C declares no extension of its own.");
        }
        if (stated != carried) {
            throw new IllegalArgumentException("The " + EIBCALEN_PARAM + " parameter says " + stated
                    + " but the payload carries " + (carried == 0 ? "no" : "a")
                    + " communication area. EIBCALEN describes what arrived; it cannot contradict it, "
                    + "because app/cbl/COUSR01C.cbl:78 uses it to decide whether the conversation had any "
                    + "state at all.");
        }
        return stated;
    }

    // =================================================================================================
    // ProgramState - the WORKING-STORAGE of one execution.
    // =================================================================================================

    /**
     * The {@code WORKING-STORAGE} of a single execution of {@code COUSR01C}.
     *
     * <p>One instance is created inside {@link #mainPara(UserAddRequest, byte, int)} and discarded when
     * it returns, which is what makes this class stateless: <strong>every field below is an instance
     * field of a per-call object, and there is not one static mutable field anywhere.</strong> That is
     * the deliberate translation of CICS semantics - {@code WORKING-STORAGE} is fresh for every task,
     * so turning {@code WS-ERR-FLG} or {@code WS-MESSAGE} into a static Java field would both break
     * request isolation and make tests order-dependent.
     *
     * <h2>What it holds, and why some of it is not on the wire</h2>
     *
     * <ul>
     *   <li><strong>{@code WS-VARIABLES}</strong> - {@code WS-MESSAGE}, {@code WS-ERR-FLG} with its two
     *       condition names, {@code WS-RESP-CD} and {@code WS-REAS-CD} ({@code COUSR01C:35-44}).</li>
     *   <li><strong>{@code CARDDEMO-COMMAREA}</strong> - the conversation context, which does travel on
     *       the wire.</li>
     *   <li><strong>The map storage</strong> - twelve fields. {@code 01 COUSR1AO REDEFINES COUSR1AI}
     *       ({@code app/cpy-bms/COUSR01.CPY:91}), and each field's {@code xxxI} and {@code xxxO} items
     *       sit at the same offset for the same width - the input group is
     *       {@code L}(2) + {@code F}(1) + filler(4) + {@code I}(n) and the output group is
     *       filler(3) + {@code C} + {@code P} + {@code H} + {@code V} + {@code O}(n), both
     *       {@code 7 + n}. They are therefore <strong>one storage viewed two ways</strong>, which is
     *       why this class holds twelve values rather than twenty-four, and why blanking an input item
     *       necessarily blanks its output item.</li>
     *   <li><strong>Symbolic-map metadata</strong> - {@link #cursorField()} for the {@code xxxL} that
     *       received {@code MOVE -1}, and {@link #errMsgColour()} for {@code ERRMSGC}. Neither is a
     *       payload field, so neither reaches {@link UserAddResponse} and neither can be serialised.
     *       They live here so a test can assert them.</li>
     *   <li><strong>{@code SEC-USER-DATA}</strong> - the eighty-byte record, present only once built.</li>
     *   <li><strong>Terminal outcome</strong> - which of {@code EXEC CICS SEND}, {@code XCTL} and
     *       {@code RETURN} actually happened, so a test can tell the endings apart.</li>
     * </ul>
     */
    public static final class ProgramState {

        /** The {@code PIC X} move rule, used to bring the initial map values to their widths. */
        private final FixedWidthCodec codec;

        /** The {@code EIBAID} byte this execution evaluated. */
        private final byte eibAid;

        /** {@code EIBCALEN} - the communication-area length this execution was entered with. */
        private final int eibcalen;

        /** {@code WS-MESSAGE PIC X(80) VALUE SPACES} - {@code COUSR01C:38}. */
        private String message = SPACES_MESSAGE;

        /**
         * {@code WS-ERR-FLG PIC X(01) VALUE 'N'} with {@code 88 ERR-FLG-ON VALUE 'Y'} and
         * {@code 88 ERR-FLG-OFF VALUE 'N'} - {@code COUSR01C:40-42}. The two condition names are
         * exposed as {@link #errFlgOn()} and {@link #errFlgOff()} so both sides of the switch are
         * assertable.
         */
        private boolean errFlg;

        /** {@code WS-RESP-CD PIC S9(09) COMP VALUE ZEROS} - {@code COUSR01C:43}. */
        private int respCd;

        /** {@code WS-REAS-CD PIC S9(09) COMP VALUE ZEROS} - {@code COUSR01C:44}. */
        private int reasCd;

        /** The two-character batch-equivalent status the repository reported, once a write happened. */
        private String writeStatus;

        /** {@code CARDDEMO-COMMAREA} - {@code app/cpy/COCOM01Y.cpy}, copied at {@code COUSR01C:46}. */
        private NavigationContext commarea = NavigationContext.empty();

        // The map storage: one value per field, shared by the xxxI and xxxO views.
        private String trnName;
        private String title01;
        private String curDate;
        private String pgmName;
        private String title02;
        private String curTime;
        private String fName;
        private String lName;
        private String userId;
        private String passwd;
        private String usrType;
        private String errMsg;

        /**
         * The {@code xxxL} item that last received {@code MOVE -1}, named as the copybook names it, or
         * {@code null} if the cursor was never positioned.
         */
        private String cursorField;

        /**
         * {@code ERRMSGC} - the message colour. Starts at {@link BmsAttributes#DFHDFCOL}, the default
         * that map storage holds before anything is moved into it, and is changed only by the successful
         * write arm at {@code COUSR01C:254}.
         */
        private byte errMsgColour = BmsAttributes.DFHDFCOL;

        /** {@code SEC-USER-DATA} - {@code app/cpy/CSUSR01Y.cpy}. Absent until the five moves run. */
        private SecUserRecord secUserData;

        /** The attention identifier as resolved, empty when it matched no known key. */
        private Optional<PfKeyResolver.AidKey> aidKey = Optional.empty();

        /** {@code FUNCTION CURRENT-DATE} as captured for the most recent send. */
        private DateHeader dateHeader;

        /** {@code CDEMO-TO-PROGRAM} as published on a transfer, or {@code null} if none happened. */
        private String nextProgram;

        /** How many times {@code EXEC CICS SEND MAP} ran. Exactly one on every non-transfer path. */
        private int sendCount;

        /** Whether {@code EXEC CICS XCTL} ran - {@code COUSR01C:175-178}. */
        private boolean transferred;

        /** Whether {@code EXEC CICS RETURN} ran - {@code COUSR01C:107-110}. */
        private boolean returned;

        /** Whether the {@code USRSEC} write reported {@code NORMAL}. */
        private boolean wroteRecord;

        /**
         * Creates the {@code WORKING-STORAGE} of one execution, with the map at its declared widths.
         *
         * <p>Every map field starts at {@code LOW-VALUES}, which is what a CICS symbolic map holds
         * before anything is moved into it - the copybook declares no {@code VALUE} clause on any item.
         *
         * @param codec    the {@code PIC X} move rule; must not be {@code null}
         * @param eibAid   the {@code EIBAID} byte being evaluated
         * @param eibcalen the communication-area length
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        private ProgramState(FixedWidthCodec codec, byte eibAid, int eibcalen) {
            this.codec = Objects.requireNonNull(codec, "A codec is required to hold the map at its "
                    + "declared widths");
            this.eibAid = eibAid;
            this.eibcalen = eibcalen;
            this.trnName = lowValues(UserAddResponse.TRN_NAME_LENGTH);
            this.title01 = lowValues(UserAddResponse.TITLE01_LENGTH);
            this.curDate = lowValues(UserAddResponse.CUR_DATE_LENGTH);
            this.pgmName = lowValues(UserAddResponse.PGM_NAME_LENGTH);
            this.title02 = lowValues(UserAddResponse.TITLE02_LENGTH);
            this.curTime = lowValues(UserAddResponse.CUR_TIME_LENGTH);
            this.fName = lowValues(UserAddResponse.F_NAME_LENGTH);
            this.lName = lowValues(UserAddResponse.L_NAME_LENGTH);
            this.userId = lowValues(UserAddResponse.USER_ID_LENGTH);
            this.passwd = lowValues(UserAddResponse.PASSWD_LENGTH);
            this.usrType = lowValues(UserAddResponse.USR_TYPE_LENGTH);
            this.errMsg = lowValues(UserAddResponse.ERR_MSG_LENGTH);
        }

        // -------------------------------------------------------------------------------------------
        // Projection onto the wire.
        // -------------------------------------------------------------------------------------------

        /**
         * The map as {@code EXEC CICS SEND} would have transmitted it, projected onto the payload
         * contract.
         *
         * <p>Sixteen components: the twelve {@code xxxO} items, the communication area, and the three
         * navigation fields. The cursor and the message colour are <strong>deliberately absent</strong> -
         * they are {@code xxxL} and {@code xxxC} metadata, and putting them on the wire would break the
         * rule that every payload field traces to a name-labelled {@code DFHMDF}.
         *
         * <p>{@code nextMapset} and {@code nextMap} are populated only where the program actually names
         * a map: on a send, the conversation stays on {@code COUSR1A} of {@code COUSR01} and returns to
         * {@code COUSR01C}, whereas {@code XCTL} at {@code COUSR01C:176} passes a program and a
         * communication area and no map at all, so both stay {@code null} there.
         *
         * @return the response payload
         */
        public UserAddResponse response() {
            String responseNextMapset = transferred ? null : MAPSET_NAME;
            String responseNextMap = transferred ? null : MAP_NAME;
            String responseNextProgram = transferred ? nextProgram : WS_PGMNAME;
            return new UserAddResponse(trnName,
                                       title01,
                                       curDate,
                                       pgmName,
                                       title02,
                                       curTime,
                                       fName,
                                       lName,
                                       userId,
                                       passwd,
                                       usrType,
                                       errMsg,
                                       commarea,
                                       responseNextProgram,
                                       responseNextMapset,
                                       responseNextMap);
        }

        // -------------------------------------------------------------------------------------------
        // WS-VARIABLES.
        // -------------------------------------------------------------------------------------------

        /** @return {@code WS-MESSAGE}, always at its declared eighty characters */
        public String message() {
            return message;
        }

        void setMessage(String message) {
            this.message = codec.movePicX(message, WS_MESSAGE_LENGTH);
        }

        /** @return {@code true} when {@code ERR-FLG-ON} - {@code WS-ERR-FLG} holds {@code 'Y'} */
        public boolean errFlgOn() {
            return errFlg;
        }

        /** @return {@code true} when {@code ERR-FLG-OFF} - {@code WS-ERR-FLG} holds {@code 'N'} */
        public boolean errFlgOff() {
            return !errFlg;
        }

        void setErrFlgOn() {
            this.errFlg = true;
        }

        void setErrFlgOff() {
            this.errFlg = false;
        }

        /** @return {@code WS-RESP-CD}, or {@value UserAddController#RESP_NOT_REPORTED} if none reported */
        public int respCd() {
            return respCd;
        }

        void setRespCd(int respCd) {
            this.respCd = respCd;
        }

        /** @return {@code WS-REAS-CD} */
        public int reasCd() {
            return reasCd;
        }

        void setReasCd(int reasCd) {
            this.reasCd = reasCd;
        }

        /** @return the repository's two-character status, or empty if no write was attempted */
        public Optional<String> writeStatus() {
            return Optional.ofNullable(writeStatus);
        }

        void setWriteStatus(String writeStatus) {
            this.writeStatus = writeStatus;
        }

        // -------------------------------------------------------------------------------------------
        // Communication area and entry conditions.
        // -------------------------------------------------------------------------------------------

        /** @return {@code CARDDEMO-COMMAREA} */
        public NavigationContext commarea() {
            return commarea;
        }

        void setCommarea(NavigationContext commarea) {
            this.commarea = Objects.requireNonNull(commarea, "The communication area is never null; an "
                    + "absent one is NavigationContext.empty()");
        }

        /** @return the {@code EIBAID} byte this execution evaluated */
        public byte eibAid() {
            return eibAid;
        }

        /** @return {@code EIBCALEN} as this execution was entered with it */
        public int eibcalen() {
            return eibcalen;
        }

        /** @return the resolved attention identifier, empty when the key matched none */
        public Optional<PfKeyResolver.AidKey> aidKey() {
            return aidKey;
        }

        void setAidKey(Optional<PfKeyResolver.AidKey> aidKey) {
            this.aidKey = Objects.requireNonNull(aidKey, "An unresolved attention identifier is an "
                    + "empty Optional, never null");
        }

        // -------------------------------------------------------------------------------------------
        // The map storage. Twelve values, each shared by its xxxI and xxxO views.
        // -------------------------------------------------------------------------------------------

        /** @return {@code TRNNAMEI} / {@code TRNNAMEO} */
        public String trnName() {
            return trnName;
        }

        void setTrnName(String trnName) {
            this.trnName = trnName;
        }

        /** @return {@code TITLE01I} / {@code TITLE01O} */
        public String title01() {
            return title01;
        }

        void setTitle01(String title01) {
            this.title01 = title01;
        }

        /** @return {@code CURDATEI} / {@code CURDATEO} */
        public String curDate() {
            return curDate;
        }

        void setCurDate(String curDate) {
            this.curDate = curDate;
        }

        /** @return {@code PGMNAMEI} / {@code PGMNAMEO} */
        public String pgmName() {
            return pgmName;
        }

        void setPgmName(String pgmName) {
            this.pgmName = pgmName;
        }

        /** @return {@code TITLE02I} / {@code TITLE02O} */
        public String title02() {
            return title02;
        }

        void setTitle02(String title02) {
            this.title02 = title02;
        }

        /** @return {@code CURTIMEI} / {@code CURTIMEO}, eight characters on this screen */
        public String curTime() {
            return curTime;
        }

        void setCurTime(String curTime) {
            this.curTime = curTime;
        }

        /** @return {@code FNAMEI} / {@code FNAMEO}, verbatim and never normalised */
        public String fName() {
            return fName;
        }

        void setFName(String fName) {
            this.fName = fName;
        }

        /** @return {@code LNAMEI} / {@code LNAMEO}, verbatim and never normalised */
        public String lName() {
            return lName;
        }

        void setLName(String lName) {
            this.lName = lName;
        }

        /** @return {@code USERIDI} / {@code USERIDO}, verbatim and never upper-cased */
        public String userId() {
            return userId;
        }

        void setUserId(String userId) {
            this.userId = userId;
        }

        /**
         * {@code PASSWDI} / {@code PASSWDO}, verbatim and never hashed.
         *
         * <p>Exposed because {@code COUSR01C:157} stores exactly these bytes and a parity case has to
         * be able to assert that. It is never logged and never composed into a message.
         *
         * @return the typed password at its declared width
         */
        public String passwd() {
            return passwd;
        }

        void setPasswd(String passwd) {
            this.passwd = passwd;
        }

        /** @return {@code USRTYPEI} / {@code USRTYPEO}; any non-blank character is accepted */
        public String usrType() {
            return usrType;
        }

        void setUsrType(String usrType) {
            this.usrType = usrType;
        }

        /** @return {@code ERRMSGI} / {@code ERRMSGO}, the message narrowed to seventy-eight */
        public String errMsg() {
            return errMsg;
        }

        void setErrMsg(String errMsg) {
            this.errMsg = errMsg;
        }

        // -------------------------------------------------------------------------------------------
        // Symbolic-map metadata. Assertable here, unreachable from the wire.
        // -------------------------------------------------------------------------------------------

        /**
         * @return the {@code xxxL} item that received {@code MOVE -1}, named as the copybook names it,
         *         or empty if the cursor was never positioned
         */
        public Optional<String> cursorField() {
            return Optional.ofNullable(cursorField);
        }

        /**
         * This screen's presentation metadata, in the shared envelope every online response publishes.
         *
         * <p>{@code COUSR01} declares no {@code xxxC}, {@code xxxP}, {@code xxxH} or {@code xxxV} items
         * beyond the message line, so there are no per-field quads to project and the field map is empty -
         * an accurate empty rather than a missing one. The two things this program writes that are
         * metadata by declaration, and that had no way to travel, are reported:
         *
         * <ul>
         *   <li>{@code MOVE -1 TO xxxL} - the cursor request, named by its {@code DFHMDF} label. The
         *       {@code xxxL} item is {@code COMP PIC S9(4)} input-group metadata and never a payload
         *       member (gate G9).</li>
         *   <li>{@code MOVE <colour> TO ERRMSGC OF COUSR1AO} - the colour of the message line, as its
         *       unsigned byte value: green on the successful add and red on every refusal.</li>
         * </ul>
         *
         * <p>{@code resetAllOutputFields} is {@code false}: the {@code MOVE LOW-VALUES} has already been
         * performed on this response, so the cleared state is in the values the client receives and there
         * is nothing left for it to repeat.
         *
         * @return the metadata, never {@code null}
         */
        public ScreenMetadata screenMetadata() {
            return ScreenMetadata.of(cursorField().map(UserAddController::dfhmdfLabel).orElse(null),
                    errMsgColour(),
                    false);
        }

        void setCursorField(String cursorField) {
            this.cursorField = cursorField;
        }

        /**
         * @return {@code ERRMSGC}, the message colour - {@link BmsAttributes#DFHGREEN} after a
         *         successful add, and {@link BmsAttributes#DFHDFCOL} otherwise
         */
        public byte errMsgColour() {
            return errMsgColour;
        }

        void setErrMsgColour(byte errMsgColour) {
            this.errMsgColour = errMsgColour;
        }

        // -------------------------------------------------------------------------------------------
        // SEC-USER-DATA and terminal outcome.
        // -------------------------------------------------------------------------------------------

        /** @return the eighty-byte record built by the five moves, or empty if none was built */
        public Optional<SecUserRecord> secUserData() {
            return Optional.ofNullable(secUserData);
        }

        void setSecUserData(SecUserRecord secUserData) {
            this.secUserData = secUserData;
        }

        /**
         * The record, required.
         *
         * @return the record built by the five moves
         * @throws IllegalStateException if no record was built, which would mean the write was reached
         *                               without the guard at {@code COUSR01C:153} having passed
         */
        SecUserRecord requireSecUserData() {
            if (secUserData == null) {
                throw new IllegalStateException("SEC-USER-DATA has not been built. app/cbl/COUSR01C.cbl"
                        + ":159 performs WRITE-USER-SEC-FILE only inside IF NOT ERR-FLG-ON at line 153, "
                        + "after the five moves at lines 154-158, so reaching the write without a record "
                        + "would mean that guard was bypassed.");
            }
            return secUserData;
        }

        /** @return the captured {@code FUNCTION CURRENT-DATE}, or empty if no screen was painted */
        public Optional<DateHeader> dateHeader() {
            return Optional.ofNullable(dateHeader);
        }

        void setDateHeader(DateHeader dateHeader) {
            this.dateHeader = dateHeader;
        }

        /** @return how many times {@code EXEC CICS SEND MAP} ran */
        public int sendCount() {
            return sendCount;
        }

        /** @return {@code true} if the screen was painted at least once */
        public boolean screenSent() {
            return sendCount > 0;
        }

        void recordSend() {
            this.sendCount++;
        }

        /**
         * @return {@code true} if {@code EXEC CICS XCTL} ran, in which case {@code EXEC CICS RETURN}
         *         did not and {@link #returned()} is {@code false}
         */
        public boolean transferred() {
            return transferred;
        }

        void setTransferred(boolean transferred) {
            this.transferred = transferred;
        }

        /** @return {@code true} if {@code EXEC CICS RETURN} ran, keeping the conversation on {@code CU01} */
        public boolean returned() {
            return returned;
        }

        void setReturned(boolean returned) {
            this.returned = returned;
        }

        void setNextProgram(String nextProgram) {
            this.nextProgram = nextProgram;
        }

        /** @return {@code true} if the {@code USRSEC} write reported {@code NORMAL} */
        public boolean wroteRecord() {
            return wroteRecord;
        }

        void setWroteRecord(boolean wroteRecord) {
            this.wroteRecord = wroteRecord;
        }
    }
}
