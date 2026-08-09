package com.vsergeychik.carddemo.user;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.dto.UserUpdateRequest;
import com.vsergeychik.carddemo.user.dto.UserUpdateRequest.Cu02Info;
import com.vsergeychik.carddemo.user.dto.UserUpdateResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import jakarta.validation.Valid;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code COUSR02C} - "Update a user in USRSEC file" - as a stateless REST controller.
 *
 * <p>A like-for-like translation of {@code app/cbl/COUSR02C.cbl}, all 414 lines of it, paragraph by
 * paragraph and in the source's own order. Nothing is added, nothing is removed and nothing is
 * tidied. Every method below names the paragraph it came from and the line range it covers, so the
 * two can be read side by side.
 *
 * <table border="1">
 *   <caption>Paragraph to method correspondence</caption>
 *   <tr><th>COBOL paragraph</th><th>Lines</th><th>Method</th></tr>
 *   <tr><td>{@code MAIN-PARA}</td><td>82-138</td><td>{@link #handle}</td></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY}</td><td>143-172</td><td>{@link #processEnterKey}</td></tr>
 *   <tr><td>{@code UPDATE-USER-INFO}</td><td>177-245</td><td>{@link #updateUserInfo}</td></tr>
 *   <tr><td>{@code RETURN-TO-PREV-SCREEN}</td><td>250-261</td><td>{@link #returnToPrevScreen}</td></tr>
 *   <tr><td>{@code SEND-USRUPD-SCREEN}</td><td>266-278</td><td>{@link #sendUsrupdScreen}</td></tr>
 *   <tr><td>{@code RECEIVE-USRUPD-SCREEN}</td><td>283-291</td><td>{@link #receiveUsrupdScreen}</td></tr>
 *   <tr><td>{@code POPULATE-HEADER-INFO}</td><td>296-315</td><td>{@link #populateHeaderInfo}</td></tr>
 *   <tr><td>{@code READ-USER-SEC-FILE}</td><td>320-353</td><td>{@link #readUserSecFile}</td></tr>
 *   <tr><td>{@code UPDATE-USER-SEC-FILE}</td><td>358-390</td><td>{@link #updateUserSecFile}</td></tr>
 *   <tr><td>{@code CLEAR-CURRENT-SCREEN}</td><td>395-398</td><td>{@link #clearCurrentScreen}</td></tr>
 *   <tr><td>{@code INITIALIZE-ALL-FIELDS}</td><td>403-411</td><td>{@link #initializeAllFields}</td></tr>
 * </table>
 *
 * <h2>Four properties of this program that look like defects and are not</h2>
 *
 * Each was checked against a sibling program, and each divergence is real. Reproducing them is the
 * whole point of a like-for-like migration; "correcting" any one of them would be a behaviour change.
 *
 * <ol>
 *   <li><strong>{@code PF3} saves before it leaves.</strong> Lines 111-119 perform
 *       {@code UPDATE-USER-INFO} <em>first</em> and only then resolve the return target and transfer.
 *       {@code PF3} conventionally abandons, and {@code app/cbl/COUSR03C.cbl} - the sibling delete
 *       screen - does exactly that. This program does not, and its own key legend says so:
 *       {@code app/bms/COUSR02.bms:163} reads
 *       {@code 'ENTER=Fetch  F3=Save&Exit  F4=Clear  F5=Save  F12=Cancel'}. {@code PF12}, at lines
 *       124-126, is the key that cancels - it transfers without saving. The save-then-exit order is
 *       reproduced verbatim in {@link #handle}.</li>
 *   <li><strong>The change tests run even after the read has failed.</strong> Look at lines 215-245:
 *       there is an {@code IF NOT ERR-FLG-ON} at 215, the read at 217, and then the four comparisons
 *       at 219-234 with <strong>no second guard between them</strong>. So a {@code NOTFND} read flags
 *       the error, paints {@code 'User ID NOT found...'}, and the program still compares the screen
 *       against an untouched {@code SEC-USER-DATA}, still finds differences, still sets
 *       {@code USR-MODIFIED-YES} and still performs the {@code REWRITE} at 237 - which answers
 *       {@code NOTFND} in turn. {@link #updateUserInfo} has no guard there either. Adding one would
 *       remove a reachable path, and the second message it produces.</li>
 *   <li><strong>Two different validation chains, deliberately.</strong>
 *       {@code PROCESS-ENTER-KEY} at 145-155 checks <em>only</em> {@code USRIDINI}, because the other
 *       four fields are not populated yet - the lookup at 163 is what populates them.
 *       {@code UPDATE-USER-INFO} at 179-213 checks all <em>five</em>. Merging them would either make
 *       the lookup impossible or let a blank field reach the record.</li>
 *   <li><strong>No input is normalised.</strong> The program never folds case, never trims and never
 *       strips. A value that differs from the stored one only in its trailing spaces is a difference
 *       to lines 219-234, because those compare fixed-width fields byte for byte. Every comparison
 *       here therefore runs over full-width images and never over a trimmed value.</li>
 * </ol>
 *
 * <h2>This is not the optimistic-concurrency pattern</h2>
 *
 * The block at lines 219-234 detects <em>whether the operator changed anything at all</em>, so that
 * an unchanged screen can be answered with {@code 'Please modify to update ...'} instead of a
 * pointless write. It is <strong>not</strong> {@code 9300-CHECK-CHANGE-IN-REC}, the genuine
 * optimistic-concurrency check that re-reads a record and compares it against the copy the screen was
 * painted from before rewriting. That paragraph exists in {@code COACTUPC} and {@code COCRDUPC} only,
 * and the requirement for it is scoped to the account and card packages.
 *
 * <p>What guards this rewrite instead is the lock: line 322 issues {@code EXEC CICS READ ... UPDATE},
 * a held read, and line 360 issues the {@code REWRITE} against the record that read still holds.
 * There is consequently <strong>no revision counter, no entity tag, no conditional-request precondition
 * header, no second comparison read and no optimistic-lock exception type</strong> anywhere in this
 * class. A revision column would additionally be a schema change, and this migration makes none.
 *
 * <h2>Credentials are compared and stored in the clear, deliberately</h2>
 *
 * Line 227 compares {@code PASSWDI} with {@code SEC-USR-PWD PIC X(08)} byte for byte, line 228 stores
 * the screen value verbatim, and line 169 moves the stored value straight back onto the screen. There
 * is <strong>no</strong> hashing, <strong>no</strong> password encoder, <strong>no</strong> digest and
 * <strong>no</strong> security-framework type in this file.
 *
 * <p>That is neither an oversight nor an endorsement. Plaintext credential handling is an
 * <strong>inherited property of the legacy design</strong> and an <strong>explicit non-goal of this
 * migration</strong>. A digest would break the program outright: a re-hash of a supplied password
 * could never equal a stored hash, so line 227 would report a change on every single submission, and
 * the value echoed at line 169 would no longer round trip. The characteristic is documented here so
 * that it stays visible rather than being quietly buried. Nothing in this class logs or echoes the
 * password, and {@link ProgramState#toString()} withholds it.
 *
 * <h2>No server-side state, in any form</h2>
 *
 * CICS is pseudo-conversational: {@code COUSR02C} ends after painting a screen and is re-entered from
 * {@code MAIN-PARA} when a key is pressed. The only state that survives a turn is what the program
 * handed back in the communication area. This class keeps that shape exactly:
 *
 * <ul>
 *   <li>there is <strong>no servlet session handle, no session-scoped or session-attribute binding,
 *       no thread-local and no static cache</strong>. A static holder would be a session by another
 *       name;</li>
 *   <li>every field of the controller itself is {@code final} and holds an injected collaborator.
 *       {@code WS-ERR-FLG}, {@code WS-MESSAGE}, {@code WS-RESP-CD}, {@code WS-REAS-CD},
 *       {@code WS-USR-MODIFIED}, {@code SEC-USER-DATA} and the screen buffer are
 *       {@code WORKING-STORAGE}, and they live on a {@link ProgramState} created fresh per call -
 *       never on this singleton, so two concurrent requests cannot see each other's;</li>
 *   <li>the one static field, {@link #PICTURE_RULES}, is {@code static final} and immutable - a
 *       {@link FixedWidthCodec} holds a code page and nothing else;</li>
 *   <li>{@code EXEC CICS XCTL} becomes a {@code nextProgram} value in the response body. The client
 *       performs the navigation; there is no server-side forward, no redirect chain and no session
 *       affinity.</li>
 * </ul>
 *
 * <h2>The testable seam</h2>
 *
 * {@link #handle} is the whole transaction and takes no servlet type. Instantiate this class with a
 * stubbed {@link SecUserRepository} and a fixed {@link Clock} and call it directly - no HTTP, no
 * Spring, no {@code MockMvc} - and every branch is reachable, including the ones an HTTP client
 * cannot easily provoke. It returns the terminal {@link ProgramState} rather than only the response
 * body, because five observable things this program produces have no home in the symbolic map's
 * payload: the {@code ERRMSGC} colour byte, the {@code MOVE -1 TO xxxL} cursor request, how many
 * times the screen was sent, the two {@code DISPLAY} lines, and the record area as the
 * {@code REWRITE} received it. A parity case needs all five.
 *
 * @see SecUserRepository#readForUpdate(String)
 * @see SecUserRepository#rewrite(SecUserRecord)
 * @see UserUpdateRequest
 * @see UserUpdateResponse
 * <h2>This endpoint is unauthenticated and unauthorized - an accepted divergence (CWE-306, CWE-862 and CWE-522)</h2>
 *
 * <p>This controller amends a record in {@code USRSEC} and paints the existing password onto the screen with no authentication and no role check.
 * Nothing here establishes who is calling or that they administer users. This screen additionally
 * returns the stored password, because {@code app/cbl/COUSR02C.cbl:169} moves
 * {@code SEC-USR-PWD} into {@code PASSWDI} and the parity diff compares that field.
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
public class UserUpdateController {

    /**
     * The {@code SYSOUT} destination for the two {@code DISPLAY} statements at lines 347 and 384.
     *
     * <p>Both are single-argument calls carrying composed text and never a throwable, because a
     * throwable handed to a logger emits its cause chain verbatim and a driver's message is composed
     * around the record it refused. The composed text holds the {@code RESP} and {@code RESP2} values
     * and nothing else - never a user identifier and never a password.
     */
    private static final Log LOG = LogFactory.getLog(UserUpdateController.class);

    // =================================================================================================
    // Code page. Stated explicitly, never taken from the platform.
    // =================================================================================================

    /**
     * The code page of this program's {@code WORKING-STORAGE} and of its screen buffer.
     *
     * <p>{@code US-ASCII}, named rather than defaulted. It is <em>not</em> the dataset code page:
     * {@code SecUserRepository} owns that, and by the time a {@link SecUserRecord} reaches this class
     * its fields are already decoded characters. Every operation performed here is a {@code PIC X}
     * move over characters - pad on the right, truncate on the right - and none of them touches a
     * stored byte, so the page is behaviourally irrelevant and is still named so that no reader has
     * to wonder which one applied.
     */
    public static final Charset WORKING_STORAGE_CHARSET = StandardCharsets.US_ASCII;

    /**
     * The {@code PICTURE} move rules this program applies, carrying {@link #WORKING_STORAGE_CHARSET}.
     *
     * <p>{@code static final} and immutable, so it is shared safely and is not mutable static state.
     * Every widening and narrowing in this class routes through it, which is what keeps the
     * <em>direction</em> of a truncation visible: {@link FixedWidthCodec#movePicX(String, int)} names
     * the rule it applies, whereas a plain Java assignment would neither pad nor truncate and the
     * resulting parity defect would be invisible at the call site.
     */
    private static final FixedWidthCodec PICTURE_RULES = new FixedWidthCodec(WORKING_STORAGE_CHARSET);

    // =================================================================================================
    // WS-VARIABLES - app/cbl/COUSR02C.cbl:35-47. The literals; the mutable items live on ProgramState.
    // =================================================================================================

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COUSR02C'} - line 36. Sent at line 303, stored at 256. */
    public static final String WS_PGMNAME = "COUSR02C";

    /** {@code WS-TRANID PIC X(04) VALUE 'CU02'} - line 37. Sent at 302, stored at 255, returned at 136. */
    public static final String WS_TRANID = "CU02";

    /**
     * The query-parameter name carrying {@code EIBCALEN}.
     *
     * <p>Named once so the handler signature, the two refusal messages and every test agree on it.
     */
    public static final String EIBCALEN_PARAM = "eibcalen";

    /** Declared width of {@code WS-MESSAGE PIC X(80)} - line 38. Two wider than {@code ERRMSGO}. */
    public static final int WS_MESSAGE_LENGTH = 80;

    /**
     * Declared digits of {@code WS-RESP-CD} and {@code WS-REAS-CD}: {@code PIC S9(09) COMP}, lines 43-44.
     */
    public static final int WS_RESP_CD_DIGITS = 9;

    /**
     * {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} - line 39, the {@code DATASET} option of both
     * file commands.
     *
     * <p>Taken from {@link SecUserRepository#CICS_FILE_NAME_IMAGE} rather than written out, so the
     * eight-character CICS file name has one home. <strong>This is a CICS file name, not a dataset
     * name</strong>: no dataset-name literal appears anywhere in this class, because a dataset's location
     * is configuration and this controller reaches the file only through the repository.
     */
    public static final String WS_USRSEC_FILE = SecUserRepository.CICS_FILE_NAME_IMAGE;

    /** {@code 88 ERR-FLG-ON VALUE 'Y'} over {@code WS-ERR-FLG PIC X(01)} - lines 40-41. */
    public static final String ERR_FLG_ON = "Y";

    /** {@code 88 ERR-FLG-OFF VALUE 'N'} - line 42, and the field's {@code VALUE} clause at line 40. */
    public static final String ERR_FLG_OFF = "N";

    /** {@code 88 USR-MODIFIED-YES VALUE 'Y'} over {@code WS-USR-MODIFIED PIC X(01)} - lines 45-46. */
    public static final String USR_MODIFIED_YES = "Y";

    /** {@code 88 USR-MODIFIED-NO VALUE 'N'} - line 47, and the field's {@code VALUE} at line 45. */
    public static final String USR_MODIFIED_NO = "N";

    // =================================================================================================
    // Transfer targets. Three programs, each named exactly where the source names it.
    // =================================================================================================

    /** {@code 'COSGN00C'} - the cold-start target at line 91 and the fallback at line 253. */
    public static final String LIT_SIGNON_PGM = "COSGN00C";

    /** {@code 'COADM01C'} - the {@code PF3} default at line 114 and the {@code PF12} target at 125. */
    public static final String LIT_ADMIN_PGM = "COADM01C";

    // =================================================================================================
    // Message literals, transcribed CHARACTER FOR CHARACTER from the source lines cited.
    //
    // Note the two shapes and do not harmonise them: the five field errors and the two failure texts
    // end '...' with NO preceding space, while 'Please modify to update ...' and ' has been updated
    // ...' and 'Press PF5 key to save your updates ...' each carry one space before the ellipsis.
    // These reach the terminal and the parity differ compares them exactly.
    // =================================================================================================

    /** {@code 'User ID can NOT be empty...'} - lines 148-149 and again 182-183. Twenty-seven characters. */
    public static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** {@code 'First Name can NOT be empty...'} - lines 188-189. Thirty characters. */
    public static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** {@code 'Last Name can NOT be empty...'} - lines 194-195. Twenty-nine characters. */
    public static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /** {@code 'Password can NOT be empty...'} - lines 200-201. Twenty-eight characters. */
    public static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** {@code 'User Type can NOT be empty...'} - lines 206-207. Twenty-nine characters. */
    public static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /**
     * {@code 'Press PF5 key to save your updates ...'} - lines 336-337, the successful-lookup prompt.
     *
     * <p>Produced by the {@code DFHRESP(NORMAL)} arm of {@code READ-USER-SEC-FILE}, which both entry
     * paths reach: the {@code ENTER} lookup at line 163 and the save at line 217. On the save path it
     * is therefore painted <em>before</em> the change detection runs, and whatever that decides is
     * painted after it. That ordering is behaviour and is preserved rather than suppressed.
     */
    public static final String MSG_PRESS_PF5 = "Press PF5 key to save your updates ...";

    /** {@code 'User ID NOT found...'} - lines 342-343 on the read, and again 379-380 on the rewrite. */
    public static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

    /** {@code 'Unable to lookup User...'} - lines 349-350, the read's {@code WHEN OTHER} arm. */
    public static final String MSG_UNABLE_TO_LOOKUP = "Unable to lookup User...";

    /** {@code 'Unable to Update User...'} - lines 386-387, the rewrite's {@code WHEN OTHER} arm. */
    public static final String MSG_UNABLE_TO_UPDATE = "Unable to Update User...";

    /** {@code 'Please modify to update ...'} - lines 239-240, when nothing changed. Note the space. */
    public static final String MSG_PLEASE_MODIFY = "Please modify to update ...";

    /** {@code 'User '} - the first {@code DELIMITED BY SIZE} operand at line 372. Five characters. */
    public static final String MSG_UPDATED_PREFIX = "User ";

    /** {@code ' has been updated ...'} - the third operand at line 374. Twenty-one characters. */
    public static final String MSG_UPDATED_SUFFIX = " has been updated ...";

    // =================================================================================================
    // Figurative constants and commarea geometry.
    // =================================================================================================

    /** One space - COBOL's {@code SPACES}, repeated to a field's declared width. */
    private static final String SPACE = " ";

    /** One {@code X'00'} - COBOL's {@code LOW-VALUES}, repeated to a field's declared width. */
    private static final String LOW_VALUE = "\u0000";

    /** {@code EIBCALEN} when no communication area travelled - the tested state at line 90. */
    public static final int NO_COMMAREA_LENGTH = 0;

    /** {@link ProgramState#termination()} for the {@code EXEC CICS XCTL} of lines 258-261. */
    public static final String TERMINATION_XCTL = "XCTL";

    /**
     * {@link ProgramState#termination()} for the {@code EXEC CICS RETURN TRANSID} of lines 135-138.
     */
    public static final String TERMINATION_RETURN_TRANSID = "RETURN_TRANSID";

    /**
     * {@code EIBCALEN} when this program's own communication area travelled: <strong>194</strong>
     * bytes, not 160.
     *
     * <p>Worth stating precisely, because 160 is the number a reader expects. Lines 49-58 are:
     *
     * <pre>
     * COPY COCOM01Y.
     *    05 CDEMO-CU02-INFO.
     *       10 CDEMO-CU02-USRID-FIRST     PIC X(08).
     *       ...
     * </pre>
     *
     * The {@code COPY} brings in {@code 01 CARDDEMO-COMMAREA} and its {@code 05} groups, and the
     * {@code 05 CDEMO-CU02-INFO} that follows <em>continues that same {@code 01} group</em> rather
     * than starting a new record. So the area this program restores at line 94 and returns at line 137
     * is {@value NavigationContext#COMMAREA_LENGTH} + {@value Cu02Info#LENGTH} = 194 bytes.
     * {@link NavigationContext} is exactly 160 bytes and is shared by all seventeen controllers; it is
     * referenced and never widened, and the extra 34 bytes are {@link Cu02Info}.
     */
    public static final int PASSED_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + Cu02Info.LENGTH;

    // =================================================================================================
    // Injected collaborators. Constructor injection only; both fields final.
    // =================================================================================================

    /** {@code WS-USRSEC-FILE} - the only dataset this program touches, reached only through here. */
    private final SecUserRepository secUserRepository;

    /**
     * The clock {@code FUNCTION CURRENT-DATE} reads at line 298.
     *
     * <p>Injected rather than read inline, because {@code POPULATE-HEADER-INFO} runs on <em>every</em>
     * send and a program call can send more than once. Reading the wall clock inside this class would
     * make every parity case non-deterministic and would put two different times on two sends of the
     * same call.
     */
    private final Clock clock;

    /**
     * Spring's constructor.
     *
     * @param secUserRepository the security-user file's repository; must not be {@code null}
     * @param clock             the clock {@code FUNCTION CURRENT-DATE} reads; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public UserUpdateController(SecUserRepository secUserRepository, Clock clock) {
        this.secUserRepository = Objects.requireNonNull(secUserRepository,
                "A SecUserRepository is required: COUSR02C reads " + SecUserRepository.CICS_FILE_NAME
                        + " for update at line 322 and rewrites it at line 360, and this controller "
                        + "reaches that dataset no other way");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: POPULATE-HEADER-INFO reads FUNCTION CURRENT-DATE at line 298 on "
                        + "every send, and reading a clock inline would make every parity case "
                        + "non-deterministic");
    }

    // =================================================================================================
    // The xxxL items this program addresses - app/cpy-bms/COUSR02.CPY.
    // =================================================================================================

    /**
     * The five symbolic-map fields {@code COUSR02C} ever moves {@code -1} into, which is how it asks
     * CICS to place the cursor.
     *
     * <p>{@code xxxL} is the {@code COMP PIC S9(4)} length item CICS reports an input length in, and
     * {@code -1} is the documented request to leave the cursor there. It is <strong>metadata, never a
     * payload member</strong>: {@link UserUpdateResponse} projects only the twelve {@code xxxO} values,
     * so the request is reported on {@link ProgramState#cursorField()} instead.
     *
     * <p>Only these five appear, and every site is listed on the constant. The remaining seven
     * {@code xxxL} items - {@code TRNNAMEL}, {@code TITLE01L}, {@code CURDATEL}, {@code PGMNAMEL},
     * {@code TITLE02L}, {@code CURTIMEL} and {@code ERRMSGL} - are never addressed, so they are not
     * enumerated here: adding them would suggest the program can put the cursor somewhere it cannot.
     */
    public enum ScreenField {

        /**
         * {@code USRIDINL} - the identifier field. Six sites: line 98 on first entry, 150 and 153 in
         * {@code PROCESS-ENTER-KEY}, 184 in {@code UPDATE-USER-INFO}, 344 on a not-found read, 381 on a
         * not-found rewrite, and 405 in {@code INITIALIZE-ALL-FIELDS}.
         */
        USRIDIN("USRIDINL"),

        /**
         * {@code FNAMEL} - the first-name field. Four sites: line 190 for its own blank check, 211 on
         * {@code UPDATE-USER-INFO}'s {@code WHEN OTHER}, 351 on the read's {@code WHEN OTHER} and 388 on
         * the rewrite's.
         */
        FNAME("FNAMEL"),

        /** {@code LNAMEL} - the last-name field. One site: line 196. */
        LNAME("LNAMEL"),

        /** {@code PASSWDL} - the password field. One site: line 202. */
        PASSWD("PASSWDL"),

        /** {@code USRTYPEL} - the user-type field. One site: line 208. */
        USRTYPE("USRTYPEL");

        /** The symbolic-map item's name, spelled exactly as {@code app/cpy-bms/COUSR02.CPY} spells it. */
        private final String cobolName;

        ScreenField(String cobolName) {
            this.cobolName = cobolName;
        }

        /**
         * The {@code xxxL} item's name, verbatim from the copybook - trailing {@code L} and all.
         *
         * @return the copybook item name, never {@code null}
         */
        public String cobolName() {
            return cobolName;
        }
    }

    // =================================================================================================
    // One EXEC CICS SEND MAP - app/cbl/COUSR02C.cbl:272-278.
    // =================================================================================================

    /**
     * What one {@code EXEC CICS SEND MAP} put on the terminal.
     *
     * <p><strong>A snapshot, not a running total.</strong> {@code COUSR02C} can paint the screen up to
     * three times in a single call and the twelve field values differ between the paints - a successful
     * lookup sends "press PF5" over blank data fields at line 339 and then sends again over the populated
     * ones at line 171 - so what the operator actually saw is only recoverable if each send is captured
     * where it happened. {@link ProgramState#sends()} is that record, in order.
     *
     * <p>Keys are the {@code xxxO} names {@code app/cpy-bms/COUSR02.CPY} spells, in screen order, exactly
     * as {@link UserUpdateResponse#fieldValues()} produces them - so a comparison walks the fields in the
     * order the mapset declares rather than an arbitrary one.
     *
     * <p>{@code ERRMSGC} travels as metadata rather than as a thirteenth entry in {@link #fields()},
     * because it is an attribute byte and not a field value. It is {@link BmsAttributes#DFHDFCOL} for a
     * send the program reached without moving a colour - the {@code X'00'} that line 97's
     * {@code MOVE LOW-VALUES} leaves in every attribute position.
     *
     * @param fields        the twelve {@code xxxO} values at this send; unmodifiable
     * @param errMsgColour  {@code ERRMSGC OF COUSR2AO} at this send
     */
    public record Send(Map<String, String> fields, byte errMsgColour) {

        /**
         * Copies the field map so the snapshot cannot change after the fact.
         *
         * @throws NullPointerException if {@code fields} is {@code null}
         */
        public Send {
            Objects.requireNonNull(fields, "A send carries the field values it painted; use an empty map "
                    + "for none rather than null");
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        /**
         * The colour byte's copybook mnemonic - {@code DFHNEUTR}, {@code DFHRED}, {@code DFHGREEN} or
         * {@code DFHDFCOL} for this program.
         *
         * @return the mnemonic, never {@code null}
         */
        public String errMsgColourMnemonic() {
            return BmsAttributes.colourMnemonic(errMsgColour);
        }

        /**
         * The attribute items this send set, keyed by the item name the copybook spells.
         *
         * <p>One entry, {@code ERRMSGC}, because that is the only attribute item {@code COUSR02C} ever
         * moves a value into - lines 241, 338 and 371. The other forty-seven attribute items of
         * {@code COUSR2AO} are never written, so listing them would claim an assertion the program does
         * not support.
         *
         * @return an unmodifiable single-entry map from {@code ERRMSGC} to its mnemonic
         */
        public Map<String, String> attributes() {
            return Map.of(ERR_MSG_COLOUR_ITEM, errMsgColourMnemonic());
        }

        /**
         * One field's value at this send.
         *
         * @param cobolName the {@code xxxO} item name, as the copybook spells it
         * @return the value, or {@code null} when this snapshot has no such field
         */
        public String value(String cobolName) {
            return fields.get(cobolName);
        }
    }

    /**
     * The one attribute item {@code COUSR02C} writes: {@code ERRMSGC OF COUSR2AO}, the message field's
     * extended-colour byte, at lines 241, 338 and 371.
     */
    public static final String ERR_MSG_COLOUR_ITEM = "ERRMSGC";

    // =================================================================================================
    // The HTTP adapter. One route: transaction CU02 - app/csd/CARDDEMO.CSD:469-470.
    // =================================================================================================

    /**
     * {@code PUT /api/users/{userId}} - CICS transaction {@code CU02}, the only route this program has.
     *
     * <p>This method is an adapter and holds no logic. It assembles the terminal input area, infers
     * {@code EIBCALEN} and {@code EIBAID}, delegates the whole transaction to {@link #handle}, and
     * projects {@link ProgramState#response()}. Every decision the program makes is in {@link #handle}.
     *
     * <h2>The binding rule for {@code userId}, stated once</h2>
     *
     * <strong>The path variable is the resource identity, and it is the value that lands in
     * {@code USRIDIN}</strong> - and therefore in {@code SEC-USR-ID} at lines 162 and 216, which is the
     * {@code RIDFLD} of both file commands. The payload's {@link UserUpdateRequest#usrIdIn()} is the
     * echo of the same screen field from the previous turn; where both are present they name the same
     * user, and the path variable wins.
     *
     * <p><strong>No mismatch error is raised</strong>, and that is deliberate: {@code COUSR02C} has no
     * such condition, so inventing one would add a failure mode the legacy screen cannot produce. Nor is
     * either branch of {@code 'User ID can NOT be empty...'} made unreachable by this rule - a path
     * segment of percent-encoded spaces is a blank identifier, and {@link #handle} is callable directly
     * with any buffer at all.
     *
     * <h2>Why this method is transactional and {@link #handle} is not annotated</h2>
     *
     * Line 322 issues {@code EXEC CICS READ ... UPDATE} against a file defined
     * {@code UPDATEMODEL(LOCKING)}, and line 360 rewrites the record that read holds. A CICS task always
     * has a unit of work, so the lock spans both commands; under auto-commit a row lock would be gone
     * before the rewrite and the caller would never know. {@code @Transactional} here reproduces the
     * task's unit of work, and both commands run inside it.
     *
     * <p>{@link #handle} carries no such annotation, because a parity test drives it with a stubbed
     * repository for which no lock exists and none is needed. Keeping the annotation at the HTTP
     * boundary is what lets the seam stay callable with no infrastructure at all.
     *
     * <h4>The identity in the path is refused rather than truncated</h4>
     * {@code USRIDIN} is {@code PIC X(08)}, and an alphanumeric {@code MOVE} keeps the leading eight
     * characters. Rendering an over-long path value through that rule would make
     * {@code PUT /api/users/USER0001EXTRA} address {@code USER0001} - a URI updating a record it does
     * not name, and one no operator could have produced, because a 3270 field physically cannot accept
     * more characters than it declares. The {@code MOVE} is faithful for a value that fits; for one that
     * does not there is nothing faithful to reproduce, so the request is refused at the boundary before
     * any repository call and before the read-for-update takes a lock.
     *
     * <h4>The extension travels in the body, not in query parameters</h4>
     * The six items of {@code 05 CDEMO-CU02-INFO} are communication-area storage
     * [{@code app/cbl/COUSR02C.cbl:50-58}] that line 94 restores from {@code DFHCOMMAREA} together with
     * the 160 bytes in front of it, and line 260 hands back on the {@code XCTL}. They are conversation
     * state, so they travel with the conversation - in the request body as
     * {@link UserUpdateRequest#cu02Info()} and back out on the response - rather than as six query
     * parameters a caller composes by hand (rule R6).
     *
     * @param userId   the resource identity; the value that occupies {@code USRIDIN} and thence
     *                 {@code SEC-USR-ID}. At most {@value UserUpdateRequest#USRIDIN_LENGTH} characters
     * @param request  the twelve {@code xxxI} items, the communication area, its 34-byte extension and
     *                 the resolved {@code EIBAID} token; validated against the symbolic map's declared
     *                 widths
     * @param eibcalen {@code EIBCALEN}, optional. Absent it is derived from the carrier; stated it must
     *                 be {@value #NO_COMMAREA_LENGTH} or {@value #PASSED_COMMAREA_LENGTH} and must agree
     *                 with what the payload actually carried
     * @return the painted screen and its metadata: the twelve {@code xxxO} values, the navigation
     *         triple, the communication area and its extension, all in the body so that nothing is
     *         retained server-side
     * @throws NullPointerException     if {@code userId} or {@code request} is {@code null}
     * @throws IllegalArgumentException if {@code userId} is wider than {@code USRIDIN}, or if
     *                                  {@code eibcalen} is neither length or disagrees with the carrier
     */
    @PutMapping(path = "/api/users/{userId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ScreenResponse<UserUpdateResponse> updateUser(
            @PathVariable("userId") String userId,
            @Valid @RequestBody UserUpdateRequest request,
            @RequestParam(name = EIBCALEN_PARAM, required = false) Integer eibcalen) {

        Objects.requireNonNull(userId, "A user id is required: it is the RIDFLD of the READ at line 322 "
                + "and of the REWRITE's held record at line 360");
        Objects.requireNonNull(request, "A request is required: COUSR02C is entered with a terminal "
                + "input area, and an absent one is spaces rather than nothing");
        requireIdentityFits(userId);

        Cu02Info cu02Info = request.cu02Info();
        int commareaLength = resolveEibcalen(eibcalen, request);

        // The binding rule, applied in exactly one place: the path variable is the identity, so it is
        // what occupies the USRIDIN slot of the terminal input area. It has already been required to fit,
        // so the MOVE below only pads; the other eleven items travel exactly as the payload delivered
        // them.
        UserUpdateRequest received = new UserUpdateRequest(request.trnName(),
                request.title01(),
                request.curDate(),
                request.pgmName(),
                request.title02(),
                request.curTime(),
                PICTURE_RULES.movePicX(userId, UserUpdateRequest.USRIDIN_LENGTH),
                request.fName(),
                request.lName(),
                request.passwd(),
                request.usrType(),
                request.errMsg(),
                request.navigationContext(),
                request.aid(),
                cu02Info);

        ProgramState state =
                handle(received, commareaLength, resolveAttentionIdentifier(request.aid()), cu02Info);
        return ScreenResponse.of(state.response(), state.screenMetadata());
    }

    /**
     * Requires the path identity to fit {@code USRIDIN PIC X(08)}, refusing it rather than truncating.
     *
     * @param userId the path variable
     * @throws IllegalArgumentException if it is wider than
     *                                  {@value UserUpdateRequest#USRIDIN_LENGTH} characters
     */
    static void requireIdentityFits(String userId) {
        if (userId.length() > UserUpdateRequest.USRIDIN_LENGTH) {
            throw new IllegalArgumentException("The user id in the path is " + userId.length()
                    + " characters, but USRIDIN is USRIDINI PIC X("
                    + UserUpdateRequest.USRIDIN_LENGTH + ") and SEC-USR-ID is PIC X("
                    + UserUpdateRequest.USRIDIN_LENGTH + "). Padding it would keep the leading "
                    + UserUpdateRequest.USRIDIN_LENGTH + " characters and address a different user "
                    + "than the one the URI names.");
        }
    }

    /**
     * Resolves {@code EIBCALEN} from the stated value and the carrier, and refuses any statement the
     * carrier does not support.
     *
     * <h4>Why a caller may not simply declare it</h4>
     * {@code EIBCALEN} is not caller data on a real terminal: CICS sets it to the length of the area it
     * actually passed. Line 90 tests it against zero to decide whether the conversation had any state at
     * all - and its zero arm transfers straight to the sign-on program - so a caller free to state it
     * could discard state that was sent, or claim state that was not. A negative value could do neither
     * faithfully.
     *
     * <h4>Why the two accepted values are 0 and {@value #PASSED_COMMAREA_LENGTH}</h4>
     * Line 90 compares against zero and nothing else, and the only other thing the program does with the
     * area is {@code MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA} at line 94, which reads the 160
     * bytes of the copybook plus the 34 of {@code 05 CDEMO-CU02-INFO}. The projected request carries
     * exactly those two areas, so it is in one of exactly two states: absent, or complete at
     * {@value #PASSED_COMMAREA_LENGTH} bytes.
     *
     * @param eibcalen the stated value, or {@code null}
     * @param request  the bound request, whose commarea presence is the carrier
     * @return {@value #NO_COMMAREA_LENGTH} or {@value #PASSED_COMMAREA_LENGTH}
     * @throws IllegalArgumentException if the stated value is neither length, or contradicts the carrier
     */
    static int resolveEibcalen(Integer eibcalen, UserUpdateRequest request) {
        int carried = request.hasNavigationContext()
                ? PASSED_COMMAREA_LENGTH
                : NO_COMMAREA_LENGTH;
        if (eibcalen == null) {
            return carried;
        }
        int stated = eibcalen;
        if (stated != NO_COMMAREA_LENGTH && stated != PASSED_COMMAREA_LENGTH) {
            throw new IllegalArgumentException("The " + EIBCALEN_PARAM + " parameter is " + stated
                    + ", but CICS sets EIBCALEN to the length of the area it passed - which for this "
                    + "program is either " + NO_COMMAREA_LENGTH + " or " + PASSED_COMMAREA_LENGTH
                    + ", CARDDEMO-COMMAREA plus CDEMO-CU02-INFO.");
        }
        if (stated != carried) {
            throw new IllegalArgumentException("The " + EIBCALEN_PARAM + " parameter says " + stated
                    + " but the payload carries " + (carried == NO_COMMAREA_LENGTH ? "no" : "a")
                    + " communication area. EIBCALEN describes what arrived; it cannot contradict it, "
                    + "because app/cbl/COUSR02C.cbl:90 uses it to decide whether the conversation had "
                    + "any state at all.");
        }
        return stated;
    }

    /**
     * Turns the request's five-character AID token back into the raw {@code EIBAID} byte line 108
     * evaluates.
     *
     * <p>{@link PfKeyResolver.AidKey} is the token form {@code common.PfKeyResolver} produces, and this
     * is its inverse for the five keys this program branches on. An absent token is {@link
     * CicsAid#DFHENTER}, because {@code ENTER} is what a terminal transmits when no program-function key
     * was pressed, and an <em>unrecognised</em> token is {@link CicsAid#DFHNULL} - a byte no
     * {@code WHEN} clause names, so it lands on {@code WHEN OTHER} at line 127 exactly as an unmapped
     * key does.
     *
     * <p>The mapping is written out rather than computed, for the same reason {@code PfKeyResolver}
     * writes its own out: {@code PFK03} is set by both {@code PF3} and {@code PF15}, and only
     * {@code PF3} matches {@code WHEN DFHPF3} in a COBOL {@code EVALUATE EIBAID}. Returning
     * {@link CicsAid#DFHPF3} for {@code PFK03} is the faithful choice here because that is the key this
     * screen's legend offers; a caller that needs to distinguish {@code PF15} passes the byte to
     * {@link #handle} directly.
     *
     * @param aidToken the token, or {@code null} when the payload named none
     * @return the raw attention-identifier byte
     */
    static byte resolveAttentionIdentifier(String aidToken) {
        if (aidToken == null) {
            return CicsAid.DFHENTER;
        }
        String token = PICTURE_RULES.movePicX(aidToken, PfKeyResolver.AID_TOKEN_LENGTH);
        if (AidKey.ENTER.token().equals(token)) {
            return CicsAid.DFHENTER;
        }
        if (AidKey.PFK03.token().equals(token)) {
            return CicsAid.DFHPF3;
        }
        if (AidKey.PFK04.token().equals(token)) {
            return CicsAid.DFHPF4;
        }
        if (AidKey.PFK05.token().equals(token)) {
            return CicsAid.DFHPF5;
        }
        if (AidKey.PFK12.token().equals(token)) {
            return CicsAid.DFHPF12;
        }
        // Every other token - CLEAR, PA1, PA2 and the eleven other function keys - is a key this
        // program has no WHEN clause for, and so is every string that is not a token at all. DFHNULL
        // is the byte that reaches WHEN OTHER at line 127.
        return CicsAid.DFHNULL;
    }

    // =================================================================================================
    // MAIN-PARA - app/cbl/COUSR02C.cbl:82-138.
    // =================================================================================================

    /**
     * {@code MAIN-PARA} - the whole transaction, in the source's order.
     *
     * <p>This is the seam a parity case drives: no HTTP, no Spring, no {@code MockMvc}. Construct this
     * controller with a stubbed {@link SecUserRepository} and a fixed {@link Clock}, then call this.
     *
     * <p>The steps, each keyed to its line:
     * <ol>
     *   <li>{@code :84-88} {@code SET ERR-FLG-OFF}, {@code SET USR-MODIFIED-NO}, and
     *       {@code MOVE SPACES TO WS-MESSAGE, ERRMSGO OF COUSR2AO}. Note the second receiver: the
     *       message <em>field on the screen</em> is blanked as well as the working-storage message.</li>
     *   <li>{@code :90-92} {@code IF EIBCALEN = 0} - the cold start. Target {@value #LIT_SIGNON_PGM},
     *       then transfer. Nothing else in the program runs.</li>
     *   <li>{@code :94} {@code MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA} - restore the
     *       {@value #PASSED_COMMAREA_LENGTH}-byte area, which is {@link NavigationContext} plus
     *       {@link Cu02Info}.</li>
     *   <li>{@code :95-105} the first-entry arm, and it is <strong>guarded</strong>: set re-enter, blank
     *       the whole screen buffer, ask for the cursor, and perform the lookup <em>only if</em>
     *       {@code CDEMO-CU02-USR-SELECTED} arrived non-blank. Then send.</li>
     *   <li>{@code :107-131} the re-entry arm: receive the map, then the five-way
     *       {@code EVALUATE EIBAID} with its {@code WHEN OTHER}, in source order.</li>
     *   <li>{@code :135-138} {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)}.
     *       Reached on every path, including after a transfer - see {@link ProgramState#returnTransid()}
     *       for why that is faithful and why it is still recorded.</li>
     * </ol>
     *
     * <h2>The negated re-enter test, and why it is not {@code isEnter()}</h2>
     *
     * Line 95 reads {@code IF NOT CDEMO-PGM-REENTER}, not {@code IF CDEMO-PGM-ENTER}, and the two are
     * different tests. {@code CDEMO-PGM-CONTEXT} is {@code PIC 9(01)} and may hold any digit, so for a
     * value of 9 both {@code CDEMO-PGM-ENTER} and {@code CDEMO-PGM-REENTER} are false while
     * {@code NOT CDEMO-PGM-REENTER} is true. This method branches on
     * {@code !commarea.isReenter()} accordingly.
     *
     * <h2>Key dispatch, and the two keys that differ</h2>
     *
     * The arms are tested in source order through {@code common.PfKeyResolver}'s byte-equality
     * predicates, which are the same {@code ==} the COBOL {@code EVALUATE EIBAID} performs - so
     * {@code PF15} does not match {@code WHEN DFHPF3}, exactly as on the mainframe.
     *
     * <ul>
     *   <li>{@code DFHENTER} {@code :109-110} - fetch the user.</li>
     *   <li>{@code DFHPF3} {@code :111-119} - <strong>save first</strong>, then echo
     *       {@code CDEMO-FROM-PROGRAM} back as the target, or {@value #LIT_ADMIN_PGM} when it is blank,
     *       and transfer. The save happens whatever the outcome, including when validation fails and the
     *       screen is repainted - the program transfers anyway.</li>
     *   <li>{@code DFHPF4} {@code :120-121} - blank the fields and repaint.</li>
     *   <li>{@code DFHPF5} {@code :122-123} - save.</li>
     *   <li>{@code DFHPF12} {@code :124-126} - transfer to {@value #LIT_ADMIN_PGM} and
     *       <strong>do not save</strong>. This is the key that cancels; {@code PF3} is not.</li>
     *   <li>{@code WHEN OTHER} {@code :127-130} - flag the error, paint
     *       {@code CCDA-MSG-INVALID-KEY}, repaint. An AID that {@code PfKeyResolver} cannot resolve at
     *       all has no {@code WHEN} clause either, so it arrives here too.</li>
     * </ul>
     *
     * @param request   the terminal input area and the carried communication area; must not be
     *                  {@code null}
     * @param eibcalen  {@code EIBCALEN}, the length of the passed communication area
     * @param eibAid    {@code EIBAID}, the raw attention-identifier byte
     * @param cu02Info  this program's own 34-byte commarea extension; must not be {@code null}
     * @return the terminal state: the response body plus the colour byte, the cursor request, the send
     *         count, the {@code DISPLAY} lines and the record area. Never {@code null}
     * @throws NullPointerException if {@code request} or {@code cu02Info} is {@code null}
     */
    public ProgramState handle(UserUpdateRequest request,
                               int eibcalen,
                               byte eibAid,
                               Cu02Info cu02Info) {

        Objects.requireNonNull(request, "A request is required: COUSR02C is entered with a terminal "
                + "input area, and an absent one is spaces rather than nothing");
        Objects.requireNonNull(cu02Info, "The CDEMO-CU02-INFO extension is required; use "
                + "Cu02Info.initial() for the area a cold start sees");

        // WORKING-STORAGE, created per call. Never a field of this controller: a field would be shared
        // by every concurrent request, which is what request isolation means.
        ProgramState state = new ProgramState(request, cu02Info, eibAid);

        state.setErrFlgOff();                                             // :84
        state.setUsrModifiedNo();                                         // :85
        state.setWsMessage(spaces(WS_MESSAGE_LENGTH));                    // :87
        state.setErrMsg(spaces(UserUpdateResponse.ERR_MSG_LENGTH));       // :88, ERRMSGO OF COUSR2AO

        if (eibcalen == NO_COMMAREA_LENGTH) {                             // :90
            state.setCommarea(state.commarea().withToProgram(LIT_SIGNON_PGM));   // :91
            returnToPrevScreen(state);                                    // :92
        } else {
            // :94 - the area was restored into ProgramState at construction, from the payload's
            // NavigationContext and Cu02Info. A payload that carried none is the initialised area.
            if (!state.commarea().isReenter()) {                          // :95
                state.setCommarea(state.commarea().withPgmReenter());     // :96
                state.moveLowValuesToScreenBuffer();                      // :97
                state.requestCursorOn(ScreenField.USRIDIN);               // :98
                if (!isSpacesOrLowValues(state.cu02Info().usrSelected())) {   // :99-100
                    state.setUsrIdIn(PICTURE_RULES.movePicX(state.cu02Info().usrSelected(),
                            UserUpdateResponse.USR_ID_IN_LENGTH));        // :101-102
                    processEnterKey(state);                               // :103
                }
                sendUsrupdScreen(state);                                  // :105
            } else {
                receiveUsrupdScreen(state);                               // :107
                if (PfKeyResolver.isEnter(eibAid)) {                      // :109
                    processEnterKey(state);                               // :110
                } else if (PfKeyResolver.isPf3(eibAid)) {                 // :111
                    updateUserInfo(state);                                // :112 - PF3 SAVES FIRST
                    if (isSpacesOrLowValues(state.commarea().fromProgram())) {   // :113
                        state.setCommarea(state.commarea().withToProgram(LIT_ADMIN_PGM));   // :114
                    } else {
                        state.setCommarea(
                                state.commarea().withToProgram(state.commarea().fromProgram()));
                    }                                                     // :116-117
                    returnToPrevScreen(state);                            // :119
                } else if (PfKeyResolver.isPf4(eibAid)) {                 // :120
                    clearCurrentScreen(state);                            // :121
                } else if (PfKeyResolver.isPf5(eibAid)) {                 // :122
                    updateUserInfo(state);                                // :123
                } else if (PfKeyResolver.isPf12(eibAid)) {                // :124
                    state.setCommarea(state.commarea().withToProgram(LIT_ADMIN_PGM));   // :125
                    returnToPrevScreen(state);                            // :126
                } else {                                                  // :127 WHEN OTHER
                    state.setErrFlgOn();                                  // :128
                    state.setWsMessage(PICTURE_RULES.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                            WS_MESSAGE_LENGTH));                          // :129
                    sendUsrupdScreen(state);                              // :130
                }
            }
        }

        state.recordReturn(WS_TRANID);                                    // :135-138
        return state;
    }

    // =================================================================================================
    // PROCESS-ENTER-KEY - app/cbl/COUSR02C.cbl:143-172.
    // =================================================================================================

    /**
     * {@code PROCESS-ENTER-KEY} - look the user up and paint the record on the screen.
     *
     * <p><strong>Three blocks, and all three are separate statements in the source.</strong> Reading it
     * as one guarded block loses the second send:
     *
     * <ol>
     *   <li>{@code :145-155} an {@code EVALUATE TRUE} over <em>one</em> field. {@code USRIDINI} blank
     *       gives the error, the message, the cursor and a send; {@code WHEN OTHER} at 152-154 asks for
     *       the cursor <strong>as well</strong> and continues. Both arms move {@code -1} into
     *       {@code USRIDINL}, which is why the cursor request is unconditional here.</li>
     *   <li>{@code :157-164} {@code IF NOT ERR-FLG-ON}: blank the four data fields on the screen -
     *       lines 158-161, so a failed lookup leaves them empty rather than showing the previous user's
     *       values - move the identifier into {@code SEC-USR-ID}, and read.</li>
     *   <li>{@code :166-172} a <strong>second</strong> {@code IF NOT ERR-FLG-ON}, evaluated after the
     *       read has had its say: move the four record fields onto the screen and send again.</li>
     * </ol>
     *
     * <p>So a successful lookup sends <em>twice</em>: once from the read's {@code NORMAL} arm at line 339
     * carrying {@code 'Press PF5 key to save your updates ...'} in neutral, and once from line 171
     * carrying the populated fields. {@link ProgramState#sendCount()} records that, because the count is
     * observable and collapsing the two would change it.
     *
     * <p>Only {@code USRIDINI} is validated. The other four fields are checked by
     * {@link #updateUserInfo} instead, because at this point they are not populated yet - the read at
     * line 163 is what populates them. Merging the two chains would make the lookup impossible.
     *
     * @param state the per-call working storage; must not be {@code null}
     */
    void processEnterKey(ProgramState state) {
        // :145-155 EVALUATE TRUE - one arm plus WHEN OTHER, in source order.
        if (isSpacesOrLowValues(state.usrIdIn())) {                       // :146
            state.setErrFlgOn();                                          // :147
            state.setWsMessage(PICTURE_RULES.movePicX(MSG_USER_ID_EMPTY, WS_MESSAGE_LENGTH)); // :148-149
            state.requestCursorOn(ScreenField.USRIDIN);                   // :150
            sendUsrupdScreen(state);                                      // :151
        } else {                                                          // :152 WHEN OTHER
            state.requestCursorOn(ScreenField.USRIDIN);                   // :153
            // :154 CONTINUE - the arm exists to place the cursor, and does nothing else.
        }

        if (!state.errFlgOn()) {                                          // :157
            state.setFName(spaces(UserUpdateResponse.FNAME_LENGTH));      // :158
            state.setLName(spaces(UserUpdateResponse.LNAME_LENGTH));      // :159
            state.setPasswd(spaces(UserUpdateResponse.PASSWD_LENGTH));    // :160
            state.setUsrType(spaces(UserUpdateResponse.USR_TYPE_LENGTH)); // :161
            state.setSecUsrId(state.usrIdIn());                           // :162
            readUserSecFile(state);                                       // :163
        }

        if (!state.errFlgOn()) {                                          // :166
            SecUserRecord record = state.secUserData();
            state.setFName(PICTURE_RULES.movePicX(record.secUsrFname(),
                    UserUpdateResponse.FNAME_LENGTH));                    // :167
            state.setLName(PICTURE_RULES.movePicX(record.secUsrLname(),
                    UserUpdateResponse.LNAME_LENGTH));                    // :168
            state.setPasswd(PICTURE_RULES.movePicX(record.secUsrPwd(),
                    UserUpdateResponse.PASSWD_LENGTH));                   // :169
            state.setUsrType(PICTURE_RULES.movePicX(record.secUsrType(),
                    UserUpdateResponse.USR_TYPE_LENGTH));                 // :170
            sendUsrupdScreen(state);                                      // :171
        }
    }

    // =================================================================================================
    // UPDATE-USER-INFO - app/cbl/COUSR02C.cbl:177-245.
    // =================================================================================================

    /**
     * {@code UPDATE-USER-INFO} - validate all five fields, decide whether anything changed, and rewrite.
     *
     * <h2>The five-arm chain, {@code :179-213}</h2>
     *
     * An {@code EVALUATE TRUE} whose arms are tested in order, so the <strong>first</strong> blank field
     * wins and the later ones are never reached: {@code USRIDINI}, {@code FNAMEI}, {@code LNAMEI},
     * {@code PASSWDI}, {@code USRTYPEI}. Each arm flags the error, sets its own message, places the
     * cursor on its own field and sends. {@code WHEN OTHER} at 210-212 places the cursor on
     * {@code FNAMEL} and continues.
     *
     * <p>Every arm tests {@code = SPACES OR LOW-VALUES}: a field the operator cleared and a field the
     * terminal never transmitted are treated alike. There is <strong>no value whitelist on
     * {@code USRTYPEI}</strong> - the program tests it for blankness at 204 and for change at 231 and
     * never against {@code 'A'} or {@code 'U'}, so any single non-blank character is accepted. The
     * screen's own {@code '(A=Admin, U=User)'} legend is guidance to the operator, not a rule the program
     * enforces, and adding one would be a changed business rule.
     *
     * <h2>The change detection, {@code :219-234}</h2>
     *
     * <strong>Four independent {@code IF} statements, not an {@code EVALUATE}</strong>, so all four run
     * and their effects accumulate. Each one that differs copies its screen value into the record area
     * <em>and</em> sets {@code USR-MODIFIED-YES}. Short-circuiting after the first difference would
     * leave the other three changed fields unwritten.
     *
     * <table border="1">
     *   <caption>The four tests, in source order</caption>
     *   <tr><th>Lines</th><th>Screen</th><th>Record</th><th>Width</th></tr>
     *   <tr><td>219-222</td><td>{@code FNAMEI}</td><td>{@code SEC-USR-FNAME}</td><td>20</td></tr>
     *   <tr><td>223-226</td><td>{@code LNAMEI}</td><td>{@code SEC-USR-LNAME}</td><td>20</td></tr>
     *   <tr><td>227-230</td><td>{@code PASSWDI}</td><td>{@code SEC-USR-PWD}</td><td>8, plaintext</td></tr>
     *   <tr><td>231-234</td><td>{@code USRTYPEI}</td><td>{@code SEC-USR-TYPE}</td><td>1</td></tr>
     * </table>
     *
     * <p>{@code SEC-USR-ID} is the key: it is neither compared nor updated. {@code SEC-USR-FILLER X(23)}
     * is not touched either, and it still travels to the {@code REWRITE} so that the record stays exactly
     * {@value SecUserRecord#RECORD_LENGTH} bytes.
     *
     * <p>Both operands of every comparison are fixed-width images, and they are compared whole. A twenty
     * character screen field and a twenty character record field differ if their trailing spaces differ,
     * so <strong>nothing is trimmed before comparing</strong>: a trim would call a real difference no
     * difference and silently skip the write.
     *
     * <h2>The missing guard, {@code :217} to {@code :219}</h2>
     *
     * There is <strong>no {@code IF NOT ERR-FLG-ON} between the read and the four tests</strong>. Read
     * lines 215-245 again: one guard at 215, the read at 217, then the tests. So when the read reports
     * {@code NOTFND} or fails, the error is flagged and {@code 'User ID NOT found...'} or
     * {@code 'Unable to lookup User...'} has already been painted - and the program still compares the
     * screen against the record area the read did not fill, still finds differences, still sets
     * {@code USR-MODIFIED-YES}, and still performs the {@code REWRITE} at 237, which answers
     * {@code NOTFND} and paints {@code 'User ID NOT found...'} a second time.
     *
     * <p>That is reproduced exactly. Adding the guard a reader expects would remove a reachable path and
     * one of the two messages it produces, and this is a like-for-like migration.
     *
     * @param state the per-call working storage; must not be {@code null}
     */
    void updateUserInfo(ProgramState state) {
        // :179-213 EVALUATE TRUE - five arms plus WHEN OTHER, tested in source order, first match wins.
        if (isSpacesOrLowValues(state.usrIdIn())) {                       // :180
            state.setErrFlgOn();                                          // :181
            state.setWsMessage(PICTURE_RULES.movePicX(MSG_USER_ID_EMPTY, WS_MESSAGE_LENGTH)); // :182-183
            state.requestCursorOn(ScreenField.USRIDIN);                   // :184
            sendUsrupdScreen(state);                                      // :185
        } else if (isSpacesOrLowValues(state.fName())) {                  // :186
            state.setErrFlgOn();                                          // :187
            state.setWsMessage(
                    PICTURE_RULES.movePicX(MSG_FIRST_NAME_EMPTY, WS_MESSAGE_LENGTH));         // :188-189
            state.requestCursorOn(ScreenField.FNAME);                     // :190
            sendUsrupdScreen(state);                                      // :191
        } else if (isSpacesOrLowValues(state.lName())) {                  // :192
            state.setErrFlgOn();                                          // :193
            state.setWsMessage(
                    PICTURE_RULES.movePicX(MSG_LAST_NAME_EMPTY, WS_MESSAGE_LENGTH));          // :194-195
            state.requestCursorOn(ScreenField.LNAME);                     // :196
            sendUsrupdScreen(state);                                      // :197
        } else if (isSpacesOrLowValues(state.passwd())) {                 // :198
            state.setErrFlgOn();                                          // :199
            state.setWsMessage(
                    PICTURE_RULES.movePicX(MSG_PASSWORD_EMPTY, WS_MESSAGE_LENGTH));           // :200-201
            state.requestCursorOn(ScreenField.PASSWD);                    // :202
            sendUsrupdScreen(state);                                      // :203
        } else if (isSpacesOrLowValues(state.usrType())) {                // :204
            state.setErrFlgOn();                                          // :205
            state.setWsMessage(
                    PICTURE_RULES.movePicX(MSG_USER_TYPE_EMPTY, WS_MESSAGE_LENGTH));          // :206-207
            state.requestCursorOn(ScreenField.USRTYPE);                   // :208
            sendUsrupdScreen(state);                                      // :209
        } else {                                                          // :210 WHEN OTHER
            state.requestCursorOn(ScreenField.FNAME);                     // :211
            // :212 CONTINUE - the arm exists to place the cursor, and does nothing else.
        }

        if (!state.errFlgOn()) {                                          // :215
            state.setSecUsrId(state.usrIdIn());                           // :216
            readUserSecFile(state);                                       // :217

            // :219-234 - FOUR INDEPENDENT IFs. No error guard precedes them; see the class notes.
            if (!state.fName().equals(state.secUserData().secUsrFname())) {   // :219
                state.setSecUsrFname(state.fName());                      // :220
                state.setUsrModifiedYes();                                // :221
            }
            if (!state.lName().equals(state.secUserData().secUsrLname())) {   // :223
                state.setSecUsrLname(state.lName());                      // :224
                state.setUsrModifiedYes();                                // :225
            }
            if (!state.passwd().equals(state.secUserData().secUsrPwd())) {    // :227 - plaintext
                state.setSecUsrPwd(state.passwd());                       // :228
                state.setUsrModifiedYes();                                // :229
            }
            if (!state.usrType().equals(state.secUserData().secUsrType())) {  // :231
                state.setSecUsrType(state.usrType());                     // :232
                state.setUsrModifiedYes();                                // :233
            }

            if (state.usrModifiedYes()) {                                 // :236
                updateUserSecFile(state);                                 // :237
            } else {                                                      // :238
                state.setWsMessage(
                        PICTURE_RULES.movePicX(MSG_PLEASE_MODIFY, WS_MESSAGE_LENGTH));        // :239-240
                state.setErrMsgColour(BmsAttributes.DFHRED);              // :241
                sendUsrupdScreen(state);                                  // :242
            }
        }
    }

    // =================================================================================================
    // READ-USER-SEC-FILE - app/cbl/COUSR02C.cbl:320-353.
    //
    //     EXEC CICS READ
    //          DATASET   (WS-USRSEC-FILE)
    //          INTO      (SEC-USER-DATA)
    //          LENGTH    (LENGTH OF SEC-USER-DATA)
    //          RIDFLD    (SEC-USR-ID)
    //          KEYLENGTH (LENGTH OF SEC-USR-ID)
    //          UPDATE                                  <-- A HELD READ. THIS MATTERS.
    //          RESP      (WS-RESP-CD)
    //          RESP2     (WS-REAS-CD)
    //     END-EXEC.
    // =================================================================================================

    /**
     * {@code READ-USER-SEC-FILE} - the held read, and the three outcomes it branches on.
     *
     * <p>{@code UPDATE} is on the command, so this is
     * {@link SecUserRepository#readForUpdate(String)} and <strong>not</strong>
     * {@link SecUserRepository#read(String)}. The file is {@code UPDATEMODEL(LOCKING)}, so the record is
     * held from here until the unit of work ends, and the {@code REWRITE} at line 360 - which carries no
     * {@code RIDFLD} - depends on that hold. Choosing the unheld read would leave the rewrite with
     * nothing held and no lock.
     *
     * <p>The hold is recorded on {@link ProgramState#hold()} and never on this controller. CICS scopes a
     * held record to the task; a field here would scope it to the singleton, and two concurrent updates
     * would each rewrite whatever the other had just read.
     *
     * <p>The {@code EVALUATE WS-RESP-CD} at 333-353, arm for arm:
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} {@code :334-339} - the record area is filled, the message becomes
     *       {@code 'Press PF5 key to save your updates ...'}, {@code ERRMSGC} becomes
     *       {@link BmsAttributes#DFHNEUTR}, and the screen is sent. Note the redundant {@code CONTINUE}
     *       at 335 that precedes all of it: it is a no-op and is left as one.</li>
     *   <li>{@code DFHRESP(NOTFND)} {@code :340-345} - error, {@code 'User ID NOT found...'}, cursor on
     *       {@code USRIDINL}, send. The record area is <strong>not</strong> touched, because CICS fills
     *       {@code INTO} only on success.</li>
     *   <li>{@code WHEN OTHER} {@code :346-352} - the {@code DISPLAY} of {@code RESP} and {@code RESP2},
     *       then error, {@code 'Unable to lookup User...'}, cursor on {@code FNAMEL}, send.</li>
     * </ul>
     *
     * <p>Both callers reach the {@code NORMAL} arm - the {@code ENTER} lookup at line 163 and the save at
     * line 217 - so the "press PF5" prompt is produced on the save path too, before the change detection
     * runs. That ordering is preserved rather than suppressed.
     *
     * @param state the per-call working storage; must not be {@code null}
     */
    void readUserSecFile(ProgramState state) {
        SecUserRepository.ReadResult result =
                secUserRepository.readForUpdate(state.secUserData().secUsrId());   // :322-331
        state.recordFileResponse(result.cicsResp().orElse(FileStatus.NORMAL), result.cicsResp2());

        if (result.isFound()) {                                           // :334 DFHRESP(NORMAL)
            // :335 CONTINUE - a no-op in the source, left as one here.
            state.setSecUserData(result.requireRecord());                 // INTO(SEC-USER-DATA)
            state.setHold(result.hold());
            state.setWsMessage(PICTURE_RULES.movePicX(MSG_PRESS_PF5, WS_MESSAGE_LENGTH));     // :336-337
            state.setErrMsgColour(BmsAttributes.DFHNEUTR);                // :338
            sendUsrupdScreen(state);                                      // :339
        } else if (result.isNotFound()) {                                 // :340 DFHRESP(NOTFND)
            state.setErrFlgOn();                                          // :341
            state.setWsMessage(PICTURE_RULES.movePicX(MSG_USER_NOT_FOUND, WS_MESSAGE_LENGTH)); // :342-343
            state.requestCursorOn(ScreenField.USRIDIN);                   // :344
            sendUsrupdScreen(state);                                      // :345
        } else {                                                          // :346 WHEN OTHER
            state.recordDisplayLine(displayLine(state.wsRespCd(), state.wsReasCd()));         // :347
            state.setErrFlgOn();                                          // :348
            state.setWsMessage(
                    PICTURE_RULES.movePicX(MSG_UNABLE_TO_LOOKUP, WS_MESSAGE_LENGTH));         // :349-350
            state.requestCursorOn(ScreenField.FNAME);                     // :351
            sendUsrupdScreen(state);                                      // :352
        }
    }

    // =================================================================================================
    // UPDATE-USER-SEC-FILE - app/cbl/COUSR02C.cbl:358-390.
    //
    //     EXEC CICS REWRITE
    //          DATASET   (WS-USRSEC-FILE)
    //          FROM      (SEC-USER-DATA)
    //          LENGTH    (LENGTH OF SEC-USER-DATA)
    //          RESP      (WS-RESP-CD)
    //          RESP2     (WS-REAS-CD)
    //     END-EXEC.                                    <-- note: NO RIDFLD. THIS MATTERS.
    // =================================================================================================

    /**
     * {@code UPDATE-USER-SEC-FILE} - the rewrite, and the message it composes on success.
     *
     * <p>The command carries {@code FROM(SEC-USER-DATA)} and <strong>no {@code RIDFLD}</strong>, so it
     * rewrites the record the preceding held read still holds - and all
     * {@value SecUserRecord#RECORD_LENGTH} bytes of the area go out, {@code SEC-USR-FILLER} included,
     * so the stored record stays exactly its declared width.
     *
     * <p>{@link SecUserRepository#rewrite(SecUserRecord)} is the faithful form and is used deliberately
     * in preference to the handle-scoped {@code HeldRecord.rewrite}: on the path described in
     * {@link #updateUserInfo} the read failed and <em>no record is held at all</em>, yet the program
     * still issues this command. A call that required the handle could not express that, and the
     * {@code NOTFND} it produces would become unreachable.
     *
     * <p>The {@code EVALUATE WS-RESP-CD} at 368-390, arm for arm:
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} {@code :369-376} - blank the message (370), set {@code ERRMSGC} to
     *       {@link BmsAttributes#DFHGREEN} (371), compose the confirmation (372-375) and send (376).</li>
     *   <li>{@code DFHRESP(NOTFND)} {@code :377-382} - error, {@code 'User ID NOT found...'}, cursor on
     *       {@code USRIDINL}, send.</li>
     *   <li>{@code WHEN OTHER} {@code :383-389} - the {@code DISPLAY}, then error,
     *       {@code 'Unable to Update User...'}, cursor on {@code FNAMEL}, send. Note the wording differs
     *       from the read's {@code 'Unable to lookup User...'}, and note the capital {@code U} on
     *       "Update" against the lower-case {@code l} on "lookup" - both are the source's own and
     *       neither is harmonised.</li>
     * </ul>
     *
     * @param state the per-call working storage; must not be {@code null}
     */
    void updateUserSecFile(ProgramState state) {
        SecUserRepository.WriteResult result = secUserRepository.rewrite(state.secUserData()); // :360-366
        state.recordFileResponse(result.cicsResp().orElse(FileStatus.NORMAL), result.cicsResp2());

        if (result.isWritten()) {                                         // :369 DFHRESP(NORMAL)
            state.setWsMessage(spaces(WS_MESSAGE_LENGTH));                // :370
            state.setErrMsgColour(BmsAttributes.DFHGREEN);                // :371
            state.setWsMessage(updatedConfirmation(state.secUserData().secUsrId()));          // :372-375
            sendUsrupdScreen(state);                                      // :376
        } else if (result.isNotFound()) {                                 // :377 DFHRESP(NOTFND)
            state.setErrFlgOn();                                          // :378
            state.setWsMessage(PICTURE_RULES.movePicX(MSG_USER_NOT_FOUND, WS_MESSAGE_LENGTH)); // :379-380
            state.requestCursorOn(ScreenField.USRIDIN);                   // :381
            sendUsrupdScreen(state);                                      // :382
        } else {                                                          // :383 WHEN OTHER
            state.recordDisplayLine(displayLine(state.wsRespCd(), state.wsReasCd()));         // :384
            state.setErrFlgOn();                                          // :385
            state.setWsMessage(
                    PICTURE_RULES.movePicX(MSG_UNABLE_TO_UPDATE, WS_MESSAGE_LENGTH));         // :386-387
            state.requestCursorOn(ScreenField.FNAME);                     // :388
            sendUsrupdScreen(state);                                      // :389
        }
    }

    /**
     * Composes {@code WS-MESSAGE} exactly as the {@code STRING} statement at lines 372-375 does.
     *
     * <pre>
     * STRING 'User '     DELIMITED BY SIZE
     *        SEC-USR-ID  DELIMITED BY SPACE
     *        ' has been updated ...' DELIMITED BY SIZE
     *   INTO WS-MESSAGE
     * </pre>
     *
     * <p><strong>{@code DELIMITED BY SPACE} is the part that is easy to get wrong.</strong> The middle
     * operand contributes its characters only up to, and not including, the first space in it. So
     * {@code 'USER0001'} contributes all eight and {@code 'ADMIN   '} contributes five - a plain Java
     * concatenation of the raw {@code PIC X(08)} field would leave {@code 'ADMIN    has been updated
     * ...'} with three stray spaces, which is a visible divergence on the terminal and a field-level
     * difference in a parity diff. An identifier that begins with a space contributes nothing at all,
     * which is also what COBOL does.
     *
     * <p>The two outer operands are {@code DELIMITED BY SIZE} and contribute every character they have,
     * so they go through {@link FixedWidthCodec#concatenateDelimitedBySize(String...)} unchanged.
     *
     * <p>{@code STRING ... INTO} transfers from the receiver's leftmost position and does
     * <strong>not</strong> space-fill what it did not reach, but line 370 has already moved
     * {@code SPACES} into the whole eighty-character field, so padding the result to eighty is
     * byte-identical to what COBOL leaves behind. The longest possible result is 5 + 8 + 21 = 34
     * characters, so the receiver never overflows.
     *
     * @param secUsrId the key from the record area, {@value SecUserRecord#SEC_USR_ID_LENGTH} characters
     * @return {@code WS-MESSAGE} as the statement leaves it, exactly {@value #WS_MESSAGE_LENGTH}
     *         characters
     */
    static String updatedConfirmation(String secUsrId) {
        return PICTURE_RULES.movePicX(
                PICTURE_RULES.concatenateDelimitedBySize(MSG_UPDATED_PREFIX,
                        delimitedBySpace(secUsrId),
                        MSG_UPDATED_SUFFIX),
                WS_MESSAGE_LENGTH);
    }

    /**
     * A sending item as {@code DELIMITED BY SPACE} contributes it: everything before its first space.
     *
     * @param value the sending item, at its full declared width
     * @return the leading characters up to the first space, or the whole value when it holds none
     * @throws NullPointerException if {@code value} is {@code null}
     */
    static String delimitedBySpace(String value) {
        Objects.requireNonNull(value, "A sending item is required; STRING ... DELIMITED BY SPACE reads "
                + "characters out of a field, and a COBOL field is never absent");
        int firstSpace = value.indexOf(SPACE);
        return firstSpace < 0 ? value : value.substring(0, firstSpace);
    }

    // =================================================================================================
    // RETURN-TO-PREV-SCREEN - app/cbl/COUSR02C.cbl:250-261.
    // =================================================================================================

    /**
     * {@code RETURN-TO-PREV-SCREEN} - stamp the caller's identity into the area and transfer.
     *
     * <p>Four steps, and the first is a guard a reader can miss:
     * <ol>
     *   <li>{@code :252-254} {@code IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES} the target becomes
     *       {@value #LIT_SIGNON_PGM}. Every caller of this paragraph has already set a target - the cold
     *       start at 91, {@code PF3} at 114 or 116, {@code PF12} at 125 - so the guard is a backstop
     *       against an area that arrived with none, and it fires when {@code PF3} echoes back a
     *       {@code CDEMO-FROM-PROGRAM} that was itself blank. It is reproduced rather than reasoned
     *       away.</li>
     *   <li>{@code :255} {@code MOVE WS-TRANID TO CDEMO-FROM-TRANID} - {@value #WS_TRANID}.</li>
     *   <li>{@code :256} {@code MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM} - {@value #WS_PGMNAME}. Together
     *       with the line above this is how the next program learns who called it.</li>
     *   <li>{@code :257} {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT} - back to
     *       {@code CDEMO-PGM-ENTER}, so the next program sees a first entry.</li>
     *   <li>{@code :258-261} {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)
     *       COMMAREA(CARDDEMO-COMMAREA)}.</li>
     * </ol>
     *
     * <p>The {@code XCTL} becomes a <strong>response field</strong>, never a server-side forward: the
     * response names the target in {@link UserUpdateResponse#nextProgram()} and the client issues the
     * follow-up call. There is no forward, no redirect and no session affinity, and the communication
     * area travels back in the body.
     *
     * @param state the per-call working storage; must not be {@code null}
     */
    void returnToPrevScreen(ProgramState state) {
        if (isSpacesOrLowValues(state.commarea().toProgram())) {           // :252
            state.setCommarea(state.commarea().withToProgram(LIT_SIGNON_PGM));   // :253
        }
        state.setCommarea(state.commarea()
                .withFromTranid(WS_TRANID)                                // :255
                .withFromProgram(WS_PGMNAME)                              // :256
                .withPgmContext(NavigationContext.PGM_CONTEXT_ENTER));    // :257
        state.recordTransfer(state.commarea().toProgram());               // :258-261
    }

    // =================================================================================================
    // SEND-USRUPD-SCREEN and RECEIVE-USRUPD-SCREEN - app/cbl/COUSR02C.cbl:266-291.
    // =================================================================================================

    /**
     * {@code SEND-USRUPD-SCREEN} - paint {@code COUSR2A} of mapset {@code COUSR02}.
     *
     * <p>Three steps:
     * <ol>
     *   <li>{@code :268} {@code PERFORM POPULATE-HEADER-INFO} - which re-reads the clock, so a call that
     *       sends twice stamps the header twice.</li>
     *   <li>{@code :270} {@code MOVE WS-MESSAGE TO ERRMSGO OF COUSR2AO}. {@code WS-MESSAGE} is
     *       {@code PIC X(80)} and {@code ERRMSGO} is {@code PIC X(78)}, so COBOL discards two characters
     *       <strong>off the right</strong>. That narrowing is performed here, explicitly, through
     *       {@link FixedWidthCodec#movePicX(String, int)} - the one place it happens and the one place it
     *       is visible. The response type refuses an over-long value rather than trimming silently,
     *       precisely so that this line cannot be skipped by accident.</li>
     *   <li>{@code :272-278} {@code EXEC CICS SEND MAP('COUSR2A') MAPSET('COUSR02') FROM(COUSR2AO) ERASE
     *       CURSOR}. {@code CURSOR} with no value honours the {@code MOVE -1 TO xxxL} recorded on
     *       {@link ProgramState#cursorField()}.</li>
     * </ol>
     *
     * <p>The send is recorded rather than performed, and it is recorded as a <strong>snapshot</strong>:
     * {@link ProgramState#sends()} holds the twelve {@code xxxO} values and the {@code ERRMSGC} colour
     * byte as they stood at each send, in order. A count alone would not do, because this program can
     * send up to three times in one call and the field values differ between them - a successful lookup
     * paints blank data fields with "press PF5", then the same message over the populated fields.
     *
     * @param state the per-call working storage; must not be {@code null}
     */
    void sendUsrupdScreen(ProgramState state) {
        populateHeaderInfo(state);                                        // :268
        state.setErrMsg(PICTURE_RULES.movePicX(state.wsMessage(),
                UserUpdateResponse.ERR_MSG_LENGTH));                      // :270 - eighty into seventy-eight
        state.recordSend();                                               // :272-278
    }

    /**
     * {@code RECEIVE-USRUPD-SCREEN} - take the operator's input into {@code COUSR2AI}.
     *
     * <p>{@code EXEC CICS RECEIVE MAP('COUSR2A') MAPSET('COUSR02') INTO(COUSR2AI) RESP(WS-RESP-CD)
     * RESP2(WS-REAS-CD)}, lines 285-291.
     *
     * <p>In this projection the request payload <em>is</em> the received map: the twelve {@code xxxI}
     * items arrived in the body and were placed in the screen buffer when the state was built. So this
     * method transfers nothing - it records the {@code RESP} and {@code RESP2} the command reported,
     * which is {@link FileStatus#NORMAL} and {@link FileStatus#NO_REASON_CODE} for a payload that
     * deserialised.
     *
     * <p>It exists rather than being inlined because the program's shape depends on it: the receive
     * happens on the re-entry arm and <strong>not</strong> on the first-entry arm, where line 97 blanks
     * the buffer instead. Keeping the call makes that asymmetry visible at the call site. Note also that
     * {@code COUSR02C} never tests the two codes it captures here - there is no branch on the receive -
     * so recording them is all this does.
     *
     * @param state the per-call working storage; must not be {@code null}
     */
    void receiveUsrupdScreen(ProgramState state) {
        state.recordFileResponse(FileStatus.NORMAL, FileStatus.NO_REASON_CODE);   // :289-290
    }

    // =================================================================================================
    // POPULATE-HEADER-INFO - app/cbl/COUSR02C.cbl:296-315.
    // =================================================================================================

    /**
     * {@code POPULATE-HEADER-INFO} - the six header fields every screen in the application carries.
     *
     * <p>{@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at line 298, then:
     * {@code CCDA-TITLE01} to {@code TITLE01O} (300), {@code CCDA-TITLE02} to {@code TITLE02O} (301),
     * {@value #WS_TRANID} to {@code TRNNAMEO} (302), {@value #WS_PGMNAME} to {@code PGMNAMEO} (303), the
     * {@code mm/dd/yy} date to {@code CURDATEO} (305-309) and the {@code hh:mm:ss} time to
     * {@code CURTIMEO} (311-315).
     *
     * <p>Lines 305-307 assemble the date one part at a time and line 307 takes
     * {@code WS-CURDATE-YEAR(3:2)} - the last two digits of the four-digit year, so 2022 shows as
     * {@code 22}. {@code common.DateHeader} owns that composition and reproduces it from the injected
     * {@link Clock}; the clock is never read here.
     *
     * <p>{@code CURTIMEO} is {@code PIC X(8)} on this screen. Only {@code COSGN00} declares nine, and
     * this program issues no {@code EXEC CICS ASSIGN}, so there is no {@code APPLID} or {@code SYSID}
     * field to populate either.
     *
     * @param state the per-call working storage; must not be {@code null}
     */
    void populateHeaderInfo(ProgramState state) {
        DateHeader header = DateHeader.from(PICTURE_RULES, clock);         // :298
        state.setTitle01(PICTURE_RULES.movePicX(ScreenTitles.CCDA_TITLE01,
                UserUpdateResponse.TITLE01_LENGTH));                      // :300
        state.setTitle02(PICTURE_RULES.movePicX(ScreenTitles.CCDA_TITLE02,
                UserUpdateResponse.TITLE02_LENGTH));                      // :301
        state.setTrnName(PICTURE_RULES.movePicX(WS_TRANID,
                UserUpdateResponse.TRN_NAME_LENGTH));                     // :302
        state.setPgmName(PICTURE_RULES.movePicX(WS_PGMNAME,
                UserUpdateResponse.PGM_NAME_LENGTH));                     // :303
        state.setCurDate(PICTURE_RULES.movePicX(header.wsCurdateMmDdYy(),
                UserUpdateResponse.CUR_DATE_LENGTH));                     // :305-309
        state.setCurTime(PICTURE_RULES.movePicX(header.wsCurtimeHhMmSs(),
                UserUpdateResponse.CUR_TIME_LENGTH));                     // :311-315
    }

    // =================================================================================================
    // CLEAR-CURRENT-SCREEN and INITIALIZE-ALL-FIELDS - app/cbl/COUSR02C.cbl:395-411.
    // =================================================================================================

    /**
     * {@code CLEAR-CURRENT-SCREEN} - lines 395-398, the {@code PF4} action. Blank, then repaint.
     *
     * @param state the per-call working storage; must not be {@code null}
     */
    void clearCurrentScreen(ProgramState state) {
        initializeAllFields(state);                                       // :397
        sendUsrupdScreen(state);                                          // :398
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS} - lines 403-411.
     *
     * <p>{@code MOVE -1 TO USRIDINL} (405), then one {@code MOVE SPACES} with six receivers (406-411):
     * {@code USRIDINI}, {@code FNAMEI}, {@code LNAMEI}, {@code PASSWDI}, {@code USRTYPEI} and
     * {@code WS-MESSAGE}.
     *
     * <p><strong>{@code ERRMSGO} is not among them</strong>, and neither are the six header fields.
     * Blanking {@code WS-MESSAGE} is nevertheless enough to clear the message line, because line 270
     * moves it onto {@code ERRMSGO} on the very next send. The distinction matters for the colour byte:
     * this paragraph does not reset {@code ERRMSGC}, so a colour set earlier in the same call survives
     * {@code PF4}. That is the source's behaviour and it is left alone.
     *
     * @param state the per-call working storage; must not be {@code null}
     */
    void initializeAllFields(ProgramState state) {
        state.requestCursorOn(ScreenField.USRIDIN);                       // :405
        state.setUsrIdIn(spaces(UserUpdateResponse.USR_ID_IN_LENGTH));    // :406
        state.setFName(spaces(UserUpdateResponse.FNAME_LENGTH));          // :407
        state.setLName(spaces(UserUpdateResponse.LNAME_LENGTH));          // :408
        state.setPasswd(spaces(UserUpdateResponse.PASSWD_LENGTH));        // :409
        state.setUsrType(spaces(UserUpdateResponse.USR_TYPE_LENGTH));     // :410
        state.setWsMessage(spaces(WS_MESSAGE_LENGTH));                    // :411
    }

    // =================================================================================================
    // Figurative-constant tests and images. The COBOL rules, stated once each.
    // =================================================================================================

    /**
     * The COBOL condition {@code field = SPACES OR LOW-VALUES}, which every guard in this program uses.
     *
     * <p>Written as COBOL writes it: <strong>two whole-field tests joined by {@code OR}</strong>, not one
     * per-character test that accepts a mixture. A field of {@code "  \0\0    "} equals neither
     * {@code SPACES} nor {@code LOW-VALUES}, so it is not blank, and this method says so.
     *
     * <p>The two representations both mean "the operator gave nothing", and COBOL treats them alike:
     * {@code LOW-VALUES} is what a field the terminal never transmitted holds - line 97 puts it there -
     * and {@code SPACES} is what a field transmitted empty holds. An empty Java string is spaces once it
     * is rendered to its declared width, and a {@code null} is {@code LOW-VALUES}; both are handled by
     * {@link #receivedImage(String, int)} before a value reaches here.
     *
     * @param image the field's full-width image; must not be {@code null}
     * @return {@code true} when the field is all spaces or all {@code LOW-VALUES}
     * @throws NullPointerException if {@code image} is {@code null}
     */
    static boolean isSpacesOrLowValues(String image) {
        Objects.requireNonNull(image, "A field image is required; render an absent value with "
                + "receivedImage(String, int) first, which is where null becomes LOW-VALUES");
        return isEntirely(image, ' ') || isEntirely(image, '\u0000');
    }

    /**
     * Whether every character of an image is the given one - the shape of a COBOL figurative-constant
     * comparison.
     *
     * <p>An empty image reports {@code true}, which is correct for both callers: a zero-length field
     * cannot differ from {@code SPACES} or from {@code LOW-VALUES}. No field on this screen is
     * zero-length, so the case is defensive rather than reachable.
     *
     * @param image     the image to test
     * @param figurative the character the figurative constant repeats
     * @return {@code true} when every character equals {@code figurative}
     */
    private static boolean isEntirely(String image, char figurative) {
        for (int index = 0; index < image.length(); index++) {
            if (image.charAt(index) != figurative) {
                return false;
            }
        }
        return true;
    }

    /**
     * A field as the terminal delivered it: {@code LOW-VALUES} when it was never transmitted, and the
     * space-padded value when it was.
     *
     * <p>This is the one place {@code null} acquires a meaning, and the meaning is the copybook's. CICS
     * leaves an untransmitted field holding whatever the area held - {@code LOW-VALUES}, after line 97 -
     * and a transmitted one holding the operator's characters padded to the field's width. Modelling
     * {@code null} as spaces instead would erase the distinction the {@code = SPACES OR LOW-VALUES}
     * guards are written to span, and modelling it as an exception would refuse a payload the legacy
     * screen accepts.
     *
     * @param value  the supplied value, or {@code null} for a field that was not transmitted
     * @param length the field's declared width
     * @return an image of exactly {@code length} characters
     */
    static String receivedImage(String value, int length) {
        return value == null
                ? LOW_VALUE.repeat(length)
                : PICTURE_RULES.movePicX(value, length);
    }

    /**
     * COBOL's {@code SPACES}, repeated to a field's declared width.
     *
     * @param length the field's declared width
     * @return exactly {@code length} spaces
     */
    static String spaces(int length) {
        return SPACE.repeat(length);
    }

    /**
     * The {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} of lines 347 and 384.
     *
     * <p>Both fields are {@code PIC S9(09) COMP} - lines 43 and 44 - and the exact rendering a
     * {@code DISPLAY} of a binary field produces is compiler-defined. What is <em>not</em> in doubt is
     * that nine digit positions are shown, so each value is rendered zero-padded to nine digits with a
     * leading minus sign when it is negative. The two labels are the source's own literals, run together
     * with the values exactly as {@code DISPLAY} concatenates its operands.
     *
     * <p>This line is a {@code SYSOUT} diagnostic, not a graded output: the parity contract covers the
     * records written, the return code and the message text. It carries the two response codes and
     * nothing else - never a user identifier, and never a password.
     *
     * @param resp {@code WS-RESP-CD}
     * @param reas {@code WS-REAS-CD}
     * @return the diagnostic line
     */
    static String displayLine(int resp, int reas) {
        return "RESP:" + nineDigitImage(resp) + "REAS:" + nineDigitImage(reas);
    }

    /**
     * A {@code PIC S9(09)} value as {@code DISPLAY} shows it: nine digits, zero-padded, minus-signed.
     *
     * @param value the value
     * @return the nine-digit image, prefixed with {@code -} when {@code value} is negative
     */
    private static String nineDigitImage(int value) {
        String digits = Long.toString(Math.abs((long) value));
        String padded = "0".repeat(Math.max(0, WS_RESP_CD_DIGITS - digits.length())) + digits;
        return value < 0 ? "-" + padded : padded;
    }

    // =================================================================================================
    // WORKING-STORAGE and the screen buffer, per call - app/cbl/COUSR02C.cbl:33-68.
    // =================================================================================================

    /**
     * One call's {@code WORKING-STORAGE}: the mutable items of {@code 01 WS-VARIABLES}, the
     * {@code SEC-USER-DATA} record area, the communication area, and the single screen buffer that
     * {@code 01 COUSR2AI} and {@code 01 COUSR2AO} share.
     *
     * <p><strong>This exists so that none of it is a field of the controller.</strong> A COBOL program's
     * working storage belongs to the running task; a Java field would belong to the singleton and would be
     * shared by every concurrent request, so two operators updating two users would overwrite each other's
     * message, error flag and record area. One instance is created per {@link
     * UserUpdateController#handle} call and is reachable from nowhere else, which is what makes two
     * successive requests provably independent.
     *
     * <h2>One buffer, two views</h2>
     *
     * {@code app/cpy-bms/COUSR02.CPY:91} declares {@code 01 COUSR2AO REDEFINES COUSR2AI}, so the input and
     * output views occupy <strong>the same storage</strong>. That is not a detail: line 167's
     * {@code MOVE SEC-USR-FNAME TO FNAMEI OF COUSR2AI} writes the very bytes {@code FNAMEO} sends, which
     * is how a lookup paints the record on the screen without ever naming an output field. Modelling the
     * two views as separate objects would break that, so the twelve fields below are one buffer and
     * {@link #response()} is a projection of it.
     *
     * <h2>What is metadata and what is payload</h2>
     *
     * The colour byte, the cursor request, the send count, the {@code DISPLAY} lines, the record area and
     * the two response codes are all observable behaviour with no {@code DFHMDF} definition behind them,
     * so they are exposed here as clearly-named metadata and are <strong>not</strong> smuggled into
     * {@link UserUpdateResponse}, which projects the twelve {@code xxxO} values and the navigation triple
     * and nothing else.
     */
    public static final class ProgramState {

        // ---------------------------------------------------------------------------------------------
        // The shared screen buffer - COUSR2AI / COUSR2AO.
        // ---------------------------------------------------------------------------------------------

        /** {@code TRNNAMEI}/{@code TRNNAMEO} {@code PIC X(4)}. Written at line 302. */
        private String trnName;

        /** {@code TITLE01I}/{@code TITLE01O} {@code PIC X(40)}. Written at line 300. */
        private String title01;

        /** {@code CURDATEI}/{@code CURDATEO} {@code PIC X(8)}. Written at line 309. */
        private String curDate;

        /** {@code PGMNAMEI}/{@code PGMNAMEO} {@code PIC X(8)}. Written at line 303. */
        private String pgmName;

        /** {@code TITLE02I}/{@code TITLE02O} {@code PIC X(40)}. Written at line 301. */
        private String title02;

        /** {@code CURTIMEI}/{@code CURTIMEO} {@code PIC X(8)} - eight, not nine. Written at line 315. */
        private String curTime;

        /** {@code USRIDINI}/{@code USRIDINO} {@code PIC X(8)}. The key, read at lines 146, 162, 180, 216. */
        private String usrIdIn;

        /** {@code FNAMEI}/{@code FNAMEO} {@code PIC X(20)}. Compared at 219, written at 158 and 167. */
        private String fName;

        /** {@code LNAMEI}/{@code LNAMEO} {@code PIC X(20)}. Compared at 223, written at 159 and 168. */
        private String lName;

        /** {@code PASSWDI}/{@code PASSWDO} {@code PIC X(8)}. Compared at 227, written at 160 and 169. */
        private String passwd;

        /** {@code USRTYPEI}/{@code USRTYPEO} {@code PIC X(1)}. Compared at 231, written at 161 and 170. */
        private String usrType;

        /** {@code ERRMSGI}/{@code ERRMSGO} {@code PIC X(78)}. Written at 88 and 270. */
        private String errMsg;

        // ---------------------------------------------------------------------------------------------
        // WS-VARIABLES - lines 35-47 - and the areas the file commands use.
        // ---------------------------------------------------------------------------------------------

        /** {@code WS-MESSAGE PIC X(80) VALUE SPACES} - line 38. Two wider than {@code ERRMSGO}. */
        private String wsMessage;

        /** {@code WS-ERR-FLG PIC X(01) VALUE 'N'} - line 40, with its two {@code 88}-levels. */
        private String wsErrFlg;

        /** {@code WS-USR-MODIFIED PIC X(01) VALUE 'N'} - line 45, with its two {@code 88}-levels. */
        private String wsUsrModified;

        /** {@code WS-RESP-CD PIC S9(09) COMP VALUE ZEROS} - line 43. */
        private int wsRespCd;

        /** {@code WS-REAS-CD PIC S9(09) COMP VALUE ZEROS} - line 44. */
        private int wsReasCd;

        /** {@code 01 SEC-USER-DATA} of {@code app/cpy/CSUSR01Y.cpy}, copied at line 65. Eighty bytes. */
        private SecUserRecord secUserData;

        /**
         * The record the {@code READ ... UPDATE} at line 322 holds, when it succeeded.
         *
         * <p>Recorded here and never on the controller, because CICS scopes a held record to the task.
         * The {@code REWRITE} at line 360 carries no {@code RIDFLD} precisely because it acts on this
         * hold - and on the path where the read failed there is no hold, which is why
         * {@link SecUserRepository#rewrite(SecUserRecord)} rather than the handle-scoped rewrite is the
         * faithful call.
         */
        private Optional<SecUserRepository.HeldRecord> hold;

        /** {@code 01 CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}, copied at line 49. 160 bytes. */
        private NavigationContext commarea;

        /** {@code 05 CDEMO-CU02-INFO} - lines 50-58, 34 bytes. Read once, written never. */
        private final Cu02Info cu02Info;

        // ---------------------------------------------------------------------------------------------
        // Observable behaviour with no DFHMDF definition behind it: metadata, never payload.
        // ---------------------------------------------------------------------------------------------

        /** {@code EIBAID} as this call received it, kept so a parity case can name the key it drove. */
        private final byte eibAid;

        /**
         * {@code ERRMSGC OF COUSR2AO} - the extended-colour byte of the message field.
         *
         * <p>Driven three ways: {@link BmsAttributes#DFHRED} at line 241 when nothing changed,
         * {@link BmsAttributes#DFHNEUTR} at 338 for the "press PF5" prompt, and
         * {@link BmsAttributes#DFHGREEN} at 371 on a successful update. It begins as
         * {@link BmsAttributes#DFHDFCOL}, the default-colour byte, which is the {@code X'00'} that line
         * 97's {@code MOVE LOW-VALUES TO COUSR2AO} leaves in every attribute position.
         */
        private byte errMsgColour;

        /** The field a {@code MOVE -1 TO xxxL} asked for the cursor on, or {@code null} for none yet. */
        private ScreenField cursorField;

        /**
         * One snapshot per {@code EXEC CICS SEND MAP}, in the order the sends happened.
         *
         * <p>A count would not be enough. This program sends up to <strong>three</strong> times in one
         * call - the first-entry arm's lookup sends twice and line 105 sends again - and the twelve field
         * values differ between them, so what was painted is only recoverable if each send is captured
         * where it happened.
         */
        private final List<Send> sends = new ArrayList<>();

        /** The {@code DISPLAY} lines of 347 and 384, in the order they were produced. */
        private final List<String> displayLines = new ArrayList<>();

        /** {@code CDEMO-TO-PROGRAM} as the {@code XCTL} named it, or spaces when none was issued. */
        private String nextProgram;

        /** {@code TRANSID} on the {@code EXEC CICS RETURN} at line 136, or spaces before it is reached. */
        private String returnTransid;

        /** Whether {@code EXEC CICS XCTL} was issued - lines 258-261. */
        private boolean transferred;

        /**
         * Builds the task's storage as CICS hands it over.
         *
         * <p>The twelve screen fields take the {@code xxxI} values the payload carried, each rendered to
         * its declared width by {@link UserUpdateController#receivedImage(String, int)} - so an absent
         * field is {@code LOW-VALUES} and a supplied one is space-padded, exactly as a terminal delivers
         * them. The record area starts as eighty spaces, the flags at their {@code VALUE} clauses, the
         * response codes at zero, and the communication area is the payload's or, when it carried none,
         * the initialised area a cold start sees.
         *
         * @param request  the terminal input area; must not be {@code null}
         * @param cu02Info the 34-byte commarea extension; must not be {@code null}
         * @param eibAid   the raw attention identifier this call received
         */
        ProgramState(UserUpdateRequest request, Cu02Info cu02Info, byte eibAid) {
            this.cu02Info = cu02Info;
            this.eibAid = eibAid;
            this.trnName = receivedImage(request.trnName(), UserUpdateResponse.TRN_NAME_LENGTH);
            this.title01 = receivedImage(request.title01(), UserUpdateResponse.TITLE01_LENGTH);
            this.curDate = receivedImage(request.curDate(), UserUpdateResponse.CUR_DATE_LENGTH);
            this.pgmName = receivedImage(request.pgmName(), UserUpdateResponse.PGM_NAME_LENGTH);
            this.title02 = receivedImage(request.title02(), UserUpdateResponse.TITLE02_LENGTH);
            this.curTime = receivedImage(request.curTime(), UserUpdateResponse.CUR_TIME_LENGTH);
            this.usrIdIn = receivedImage(request.usrIdIn(), UserUpdateResponse.USR_ID_IN_LENGTH);
            this.fName = receivedImage(request.fName(), UserUpdateResponse.FNAME_LENGTH);
            this.lName = receivedImage(request.lName(), UserUpdateResponse.LNAME_LENGTH);
            this.passwd = receivedImage(request.passwd(), UserUpdateResponse.PASSWD_LENGTH);
            this.usrType = receivedImage(request.usrType(), UserUpdateResponse.USR_TYPE_LENGTH);
            this.errMsg = receivedImage(request.errMsg(), UserUpdateResponse.ERR_MSG_LENGTH);
            this.wsMessage = spaces(WS_MESSAGE_LENGTH);
            this.wsErrFlg = ERR_FLG_OFF;
            this.wsUsrModified = USR_MODIFIED_NO;
            this.wsRespCd = FileStatus.NORMAL;
            this.wsReasCd = FileStatus.NO_REASON_CODE;
            this.secUserData = SecUserRecord.blank();
            this.hold = Optional.empty();
            this.errMsgColour = BmsAttributes.DFHDFCOL;
            this.nextProgram = spaces(UserUpdateResponse.NEXT_PROGRAM_LENGTH);
            this.returnTransid = spaces(WS_TRANID.length());
            this.transferred = false;
            this.commarea = request.navigationContext() == null
                    ? NavigationContext.empty()
                    : request.navigationContext();
        }

        // ---------------------------------------------------------------------------------------------
        // The screen buffer: read and write, one accessor pair per field.
        // ---------------------------------------------------------------------------------------------

        /** @return {@code TRNNAMEI}/{@code TRNNAMEO}, four characters */
        public String trnName() {
            return trnName;
        }

        /** @param value the transaction name, four characters */
        void setTrnName(String value) {
            this.trnName = value;
        }

        /** @return {@code TITLE01I}/{@code TITLE01O}, forty characters */
        public String title01() {
            return title01;
        }

        /** @param value the first title line, forty characters */
        void setTitle01(String value) {
            this.title01 = value;
        }

        /** @return {@code CURDATEI}/{@code CURDATEO}, eight characters, {@code mm/dd/yy} */
        public String curDate() {
            return curDate;
        }

        /** @param value the date, eight characters */
        void setCurDate(String value) {
            this.curDate = value;
        }

        /** @return {@code PGMNAMEI}/{@code PGMNAMEO}, eight characters */
        public String pgmName() {
            return pgmName;
        }

        /** @param value the program name, eight characters */
        void setPgmName(String value) {
            this.pgmName = value;
        }

        /** @return {@code TITLE02I}/{@code TITLE02O}, forty characters */
        public String title02() {
            return title02;
        }

        /** @param value the second title line, forty characters */
        void setTitle02(String value) {
            this.title02 = value;
        }

        /** @return {@code CURTIMEI}/{@code CURTIMEO}, eight characters, {@code hh:mm:ss} */
        public String curTime() {
            return curTime;
        }

        /** @param value the time, eight characters */
        void setCurTime(String value) {
            this.curTime = value;
        }

        /** @return {@code USRIDINI}/{@code USRIDINO}, eight characters - the key, untrimmed */
        public String usrIdIn() {
            return usrIdIn;
        }

        /** @param value the identifier, eight characters */
        void setUsrIdIn(String value) {
            this.usrIdIn = value;
        }

        /** @return {@code FNAMEI}/{@code FNAMEO}, twenty characters, untrimmed */
        public String fName() {
            return fName;
        }

        /** @param value the first name, twenty characters */
        void setFName(String value) {
            this.fName = value;
        }

        /** @return {@code LNAMEI}/{@code LNAMEO}, twenty characters, untrimmed */
        public String lName() {
            return lName;
        }

        /** @param value the last name, twenty characters */
        void setLName(String value) {
            this.lName = value;
        }

        /**
         * @return {@code PASSWDI}/{@code PASSWDO}, eight characters of plaintext, untrimmed. Withheld by
         *         {@link #toString()} but returned here, because lines 227 and 228 need the value itself
         */
        public String passwd() {
            return passwd;
        }

        /** @param value the plaintext password, eight characters */
        void setPasswd(String value) {
            this.passwd = value;
        }

        /** @return {@code USRTYPEI}/{@code USRTYPEO}, one character, never validated against a whitelist */
        public String usrType() {
            return usrType;
        }

        /** @param value the user type, one character */
        void setUsrType(String value) {
            this.usrType = value;
        }

        /** @return {@code ERRMSGI}/{@code ERRMSGO}, seventy-eight characters */
        public String errMsg() {
            return errMsg;
        }

        /** @param value the message line, seventy-eight characters */
        void setErrMsg(String value) {
            this.errMsg = value;
        }

        /**
         * {@code MOVE LOW-VALUES TO COUSR2AO} - line 97, on the first-entry arm only.
         *
         * <p>Every one of the twelve fields becomes its width in {@code X'00'}. The header six are
         * repainted by {@code POPULATE-HEADER-INFO} on the send that follows; the data fields stay
         * {@code LOW-VALUES} unless line 101 or the lookup fills them, which is exactly what makes
         * {@code = SPACES OR LOW-VALUES} the guard the program needs.
         */
        void moveLowValuesToScreenBuffer() {
            this.trnName = LOW_VALUE.repeat(UserUpdateResponse.TRN_NAME_LENGTH);
            this.title01 = LOW_VALUE.repeat(UserUpdateResponse.TITLE01_LENGTH);
            this.curDate = LOW_VALUE.repeat(UserUpdateResponse.CUR_DATE_LENGTH);
            this.pgmName = LOW_VALUE.repeat(UserUpdateResponse.PGM_NAME_LENGTH);
            this.title02 = LOW_VALUE.repeat(UserUpdateResponse.TITLE02_LENGTH);
            this.curTime = LOW_VALUE.repeat(UserUpdateResponse.CUR_TIME_LENGTH);
            this.usrIdIn = LOW_VALUE.repeat(UserUpdateResponse.USR_ID_IN_LENGTH);
            this.fName = LOW_VALUE.repeat(UserUpdateResponse.FNAME_LENGTH);
            this.lName = LOW_VALUE.repeat(UserUpdateResponse.LNAME_LENGTH);
            this.passwd = LOW_VALUE.repeat(UserUpdateResponse.PASSWD_LENGTH);
            this.usrType = LOW_VALUE.repeat(UserUpdateResponse.USR_TYPE_LENGTH);
            this.errMsg = LOW_VALUE.repeat(UserUpdateResponse.ERR_MSG_LENGTH);
        }

        // ---------------------------------------------------------------------------------------------
        // WS-VARIABLES: the 88-level conditions as named predicates, and the SETs that drive them.
        // ---------------------------------------------------------------------------------------------

        /** @return {@code WS-MESSAGE}, eighty characters */
        public String wsMessage() {
            return wsMessage;
        }

        /** @param value the message, eighty characters */
        void setWsMessage(String value) {
            this.wsMessage = value;
        }

        /** @return {@code true} for {@code 88 ERR-FLG-ON VALUE 'Y'} - line 41 */
        public boolean errFlgOn() {
            return ERR_FLG_ON.equals(wsErrFlg);
        }

        /** @return the raw {@code WS-ERR-FLG} byte, so a parity case can assert the stored value */
        public String wsErrFlg() {
            return wsErrFlg;
        }

        /** {@code MOVE 'Y' TO WS-ERR-FLG} - the form the source uses, at 128, 147, 181 and six more. */
        void setErrFlgOn() {
            this.wsErrFlg = ERR_FLG_ON;
        }

        /** {@code SET ERR-FLG-OFF TO TRUE} - line 84. */
        void setErrFlgOff() {
            this.wsErrFlg = ERR_FLG_OFF;
        }

        /** @return {@code true} for {@code 88 USR-MODIFIED-YES VALUE 'Y'} - line 46, tested at 236 */
        public boolean usrModifiedYes() {
            return USR_MODIFIED_YES.equals(wsUsrModified);
        }

        /** @return the raw {@code WS-USR-MODIFIED} byte */
        public String wsUsrModified() {
            return wsUsrModified;
        }

        /** {@code SET USR-MODIFIED-YES TO TRUE} - lines 221, 225, 229 and 233. */
        void setUsrModifiedYes() {
            this.wsUsrModified = USR_MODIFIED_YES;
        }

        /** {@code SET USR-MODIFIED-NO TO TRUE} - line 85. */
        void setUsrModifiedNo() {
            this.wsUsrModified = USR_MODIFIED_NO;
        }

        /** @return {@code WS-RESP-CD} as the last file command reported it */
        public int wsRespCd() {
            return wsRespCd;
        }

        /** @return {@code WS-REAS-CD} as the last file command reported it */
        public int wsReasCd() {
            return wsReasCd;
        }

        /**
         * {@code RESP(WS-RESP-CD) RESP2(WS-REAS-CD)} - what a file command or the receive reported.
         *
         * @param resp the {@code RESP} value
         * @param reas the {@code RESP2} value
         */
        void recordFileResponse(int resp, int reas) {
            this.wsRespCd = resp;
            this.wsReasCd = reas;
        }

        // ---------------------------------------------------------------------------------------------
        // SEC-USER-DATA: the record area, and the four field-level MOVEs the change detection performs.
        // ---------------------------------------------------------------------------------------------

        /** @return the record area, all {@value SecUserRecord#RECORD_LENGTH} bytes of it */
        public SecUserRecord secUserData() {
            return secUserData;
        }

        /**
         * {@code INTO(SEC-USER-DATA)} - the whole area, as a successful read filled it.
         *
         * @param record the record the read returned
         */
        void setSecUserData(SecUserRecord record) {
            this.secUserData = record;
        }

        /**
         * {@code MOVE USRIDINI OF COUSR2AI TO SEC-USR-ID} - lines 162 and 216, setting the {@code RIDFLD}.
         *
         * <p>Only the key changes; the other five items keep whatever the area already held, which is what
         * lets a failed read leave them alone. {@code SEC-USR-FILLER} travels through untouched, so the
         * record stays exactly {@value SecUserRecord#RECORD_LENGTH} bytes.
         *
         * @param value the identifier, eight characters
         */
        void setSecUsrId(String value) {
            this.secUserData = SecUserRecord.of(value,
                    secUserData.secUsrFname(),
                    secUserData.secUsrLname(),
                    secUserData.secUsrPwd(),
                    secUserData.secUsrType(),
                    secUserData.secUsrFiller(),
                    PICTURE_RULES);
        }

        /**
         * {@code MOVE FNAMEI OF COUSR2AI TO SEC-USR-FNAME} - line 220.
         *
         * @param value the first name, twenty characters
         */
        void setSecUsrFname(String value) {
            this.secUserData = SecUserRecord.of(secUserData.secUsrId(),
                    value,
                    secUserData.secUsrLname(),
                    secUserData.secUsrPwd(),
                    secUserData.secUsrType(),
                    secUserData.secUsrFiller(),
                    PICTURE_RULES);
        }

        /**
         * {@code MOVE LNAMEI OF COUSR2AI TO SEC-USR-LNAME} - line 224.
         *
         * @param value the last name, twenty characters
         */
        void setSecUsrLname(String value) {
            this.secUserData = SecUserRecord.of(secUserData.secUsrId(),
                    secUserData.secUsrFname(),
                    value,
                    secUserData.secUsrPwd(),
                    secUserData.secUsrType(),
                    secUserData.secUsrFiller(),
                    PICTURE_RULES);
        }

        /**
         * {@code MOVE PASSWDI OF COUSR2AI TO SEC-USR-PWD} - line 228.
         *
         * <p>The value is stored exactly as the screen supplied it: not hashed, not encoded and not
         * masked. Line 169 moves the stored value straight back onto the screen, so any transformation
         * here would break the round trip as well as the comparison at line 227.
         *
         * @param value the plaintext password, eight characters
         */
        void setSecUsrPwd(String value) {
            this.secUserData = SecUserRecord.of(secUserData.secUsrId(),
                    secUserData.secUsrFname(),
                    secUserData.secUsrLname(),
                    value,
                    secUserData.secUsrType(),
                    secUserData.secUsrFiller(),
                    PICTURE_RULES);
        }

        /**
         * {@code MOVE USRTYPEI OF COUSR2AI TO SEC-USR-TYPE} - line 232.
         *
         * @param value the user type, one character
         */
        void setSecUsrType(String value) {
            this.secUserData = SecUserRecord.of(secUserData.secUsrId(),
                    secUserData.secUsrFname(),
                    secUserData.secUsrLname(),
                    secUserData.secUsrPwd(),
                    value,
                    secUserData.secUsrFiller(),
                    PICTURE_RULES);
        }

        /** @return the record this task holds from the read at line 322, or empty when it took no lock */
        public Optional<SecUserRepository.HeldRecord> hold() {
            return hold;
        }

        /**
         * Records the hold a successful {@code READ ... UPDATE} took.
         *
         * @param hold the handle, or empty
         */
        void setHold(Optional<SecUserRepository.HeldRecord> hold) {
            this.hold = hold;
        }

        // ---------------------------------------------------------------------------------------------
        // The communication area and this program's extension of it.
        // ---------------------------------------------------------------------------------------------

        /** @return {@code CARDDEMO-COMMAREA}, the 160 bytes shared by all seventeen controllers */
        public NavigationContext commarea() {
            return commarea;
        }

        /** @param commarea the area, never {@code null} */
        void setCommarea(NavigationContext commarea) {
            this.commarea = commarea;
        }

        /** @return {@code CDEMO-CU02-INFO}, the 34 bytes this program appends and hands back unchanged */
        public Cu02Info cu02Info() {
            return cu02Info;
        }

        // ---------------------------------------------------------------------------------------------
        // Metadata: the attribute byte, the cursor, the sends, the DISPLAYs and the transfer.
        // ---------------------------------------------------------------------------------------------

        /** @return {@code EIBAID} as this call received it */
        public byte eibAid() {
            return eibAid;
        }

        /** @return the resolved AID token, or empty for a byte {@code CSSTRPFY} has no branch for */
        public Optional<AidKey> aidKey() {
            return PfKeyResolver.resolve(eibAid);
        }

        /** @return {@code ERRMSGC OF COUSR2AO} - metadata, never a payload member */
        public byte errMsgColour() {
            return errMsgColour;
        }

        /** @return the colour byte's copybook mnemonic, for a readable assertion or diagnostic */
        public String errMsgColourMnemonic() {
            return BmsAttributes.colourMnemonic(errMsgColour);
        }

        /**
         * {@code MOVE DFHxxx TO ERRMSGC OF COUSR2AO} - lines 241, 338 and 371.
         *
         * @param colour the extended-colour byte, from {@code common.BmsAttributes}
         */
        void setErrMsgColour(byte colour) {
            this.errMsgColour = colour;
        }

        /** @return the field the cursor was requested on, or empty when no {@code MOVE -1} has run */
        public Optional<ScreenField> cursorField() {
            return Optional.ofNullable(cursorField);
        }

        /**
         * Whether the cursor was last requested on a particular field.
         *
         * @param field the field to test
         * @return {@code true} when {@code field} is the current request
         */
        public boolean cursorRequestedOn(ScreenField field) {
            return cursorField == field;
        }

        /**
         * {@code MOVE -1 TO xxxL} - the cursor request.
         *
         * <p>Later requests replace earlier ones, because {@code CURSOR} on the send at line 277 honours
         * whichever {@code xxxL} holds {@code -1} when the map is transmitted and the program only ever
         * sets one at a time.
         *
         * @param field the field to place the cursor on
         */
        void requestCursorOn(ScreenField field) {
            this.cursorField = field;
        }

        /**
         * One entry per {@code EXEC CICS SEND MAP}, in order.
         *
         * <p>Zero entries on the two paths that transfer without painting - the cold start at lines 90-92
         * and {@code PF12} at 124-126. One on a validation failure or an invalid key. Two on a successful
         * save, because the read's {@code NORMAL} arm paints "press PF5" before the change detection
         * decides anything. <strong>Three</strong> on arrival from the user list with a row selected: the
         * lookup at line 103 sends twice and line 105 sends unconditionally afterwards.
         *
         * @return the snapshots; unmodifiable, possibly empty, never {@code null}
         */
        public List<Send> sends() {
            return Collections.unmodifiableList(sends);
        }

        /** @return how many times the map was sent - {@code sends().size()}, named for readability */
        public int sendCount() {
            return sends.size();
        }

        /** @return whether the map was painted at all */
        public boolean screenSent() {
            return !sends.isEmpty();
        }

        /**
         * {@code EXEC CICS SEND MAP('COUSR2A') MAPSET('COUSR02') FROM(COUSR2AO) ERASE CURSOR} - lines
         * 272-278.
         *
         * <p>Captures what went to the terminal rather than only that something did: the twelve
         * {@code xxxO} values as this send left them, keyed by the names the copybook spells, plus the
         * {@code ERRMSGC} colour byte in force at that moment.
         */
        void recordSend() {
            sends.add(new Send(response().fieldValues(), errMsgColour));
        }

        /** @return the {@code DISPLAY} lines of 347 and 384, in order; unmodifiable, possibly empty */
        public List<String> displayLines() {
            return Collections.unmodifiableList(displayLines);
        }

        /**
         * {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} - lines 347 and 384.
         *
         * <p>Kept on the state so a parity case can assert it, and logged so a deployment can see it. The
         * logging call takes one argument and never a throwable.
         *
         * @param line the composed line
         */
        void recordDisplayLine(String line) {
            displayLines.add(line);
            LOG.info(line);
        }

        /** @return {@code CDEMO-TO-PROGRAM} as the {@code XCTL} named it, or spaces when none was issued */
        public String nextProgram() {
            return nextProgram;
        }

        /** @return whether {@code EXEC CICS XCTL} was issued - lines 258-261 */
        public boolean transferred() {
            return transferred;
        }

        /**
         * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} - lines 258-261, projected as response data.
         *
         * @param program the transfer target, eight characters
         */
        void recordTransfer(String program) {
            this.nextProgram = program;
            this.transferred = true;
        }

        /**
         * @return {@code TRANSID} on the {@code EXEC CICS RETURN} at line 136, or spaces before it runs
         */
        public String returnTransid() {
            return returnTransid;
        }

        /**
         * {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} - lines 135-138.
         *
         * <p>Reached on <strong>every</strong> path, the transferring ones included, because the statement
         * follows the whole {@code IF}/{@code ELSE} structure and is not inside it. On a real system an
         * {@code XCTL} does not return, so the statement is unreachable in practice after one - but it is
         * recorded regardless, because whether the program would have re-driven its own transaction is
         * observable and it is not this migration's place to decide the question the source leaves open.
         * {@link #transferred()} distinguishes the two cases.
         *
         * @param transid the transaction to re-drive, {@value UserUpdateController#WS_TRANID}
         */
        void recordReturn(String transid) {
            this.returnTransid = transid;
        }

        // ---------------------------------------------------------------------------------------------
        // The projection.
        // ---------------------------------------------------------------------------------------------

        /**
         * The screen buffer as {@code COUSR2AO}, plus the navigation triple, the communication area and
         * its thirty-four-byte extension.
         *
         * <p>Exactly the seventeen members {@link UserUpdateResponse} declares and nothing else: no
         * colour byte, no {@code xxxL}, no {@code FILLER} and no concurrency token. The presentation
         * metadata travels beside the response in {@link #screenMetadata()}, not inside it.
         *
         * <h4>What the navigation triple says, and why it changed</h4>
         * The triple answers "where is the client, and where does it go next" - the question a stateless
         * client has to ask, since there is no terminal holding a screen for it.
         *
         * <ul>
         *   <li>On a transfer ({@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)}, lines 258-261)
         *       {@link UserUpdateResponse#nextProgram()} names the target and the two map members are
         *       <strong>spaces</strong>: the {@code XCTL} names no mapset and no map, so which screen the
         *       target will paint is genuinely not known here, and publishing a guess - or this screen's
         *       own map - would state something the source does not.</li>
         *   <li>On every other path the program reaches
         *       {@code EXEC CICS RETURN TRANSID(WS-TRANID)} at 135-138 having sent
         *       {@code MAP('COUSR2A') MAPSET('COUSR02')} (lines 272-278 and 285-291). The triple therefore
         *       names {@value UserUpdateController#WS_PGMNAME},
         *       {@link UserUpdateResponse#MAPSET_NAME} and {@link UserUpdateResponse#MAP_NAME} - the
         *       screen the client is now looking at, taken from the {@code SEND} statements themselves
         *       rather than from the caller's leftovers.</li>
         * </ul>
         *
         * <p>These three members are <strong>not</strong> {@code DFHMDF} fields and not commarea fields:
         * {@code COUSR02C} copies no {@code CVCRD01Y}, so it has no {@code CCARD-NEXT-PROG} of its own.
         * They are a projection of navigation, which is why redefining what they say costs no parity.
         * {@code CDEMO-LAST-MAPSET} and {@code CDEMO-LAST-MAP} remain observable and byte-identical
         * inside {@link UserUpdateResponse#navigationContext()}, which still carries the whole
         * {@value NavigationContext#COMMAREA_LENGTH}-byte area exactly as the program leaves it -
         * including the fact that {@code COUSR02C} never writes either field.
         *
         * <p>Values are handed over at their full declared widths, so nothing is padded or trimmed on the
         * way out.
         *
         * @return the response body, never {@code null}
         */
        public UserUpdateResponse response() {
            return new UserUpdateResponse(trnName,
                    title01,
                    curDate,
                    pgmName,
                    title02,
                    curTime,
                    usrIdIn,
                    fName,
                    lName,
                    passwd,
                    usrType,
                    errMsg,
                    commarea,
                    transferred ? nextProgram : PICTURE_RULES.movePicX(WS_PGMNAME,
                            UserUpdateResponse.NEXT_PROGRAM_LENGTH),
                    transferred
                            ? SPACE.repeat(UserUpdateResponse.NEXT_MAPSET_LENGTH)
                            : UserUpdateResponse.MAPSET_NAME,
                    transferred
                            ? SPACE.repeat(UserUpdateResponse.NEXT_MAP_LENGTH)
                            : UserUpdateResponse.MAP_NAME,
                    cu02Info);
        }

        /**
         * This screen's presentation metadata, in the shared envelope every online response publishes.
         *
         * <p>{@code COUSR02} declares no {@code xxxC}, {@code xxxP}, {@code xxxH} or {@code xxxV} items,
         * so there are no per-field quads to project and the field map is empty - an accurate empty, not a
         * missing one. What this screen does have is the two things it writes that are metadata by
         * declaration and had no way to travel:
         *
         * <ul>
         *   <li>{@code MOVE -1 TO xxxL} - the cursor request. The {@code xxxL} item is
         *       {@code COMP PIC S9(4)} input-group metadata and never a payload member (gate G9), so the
         *       field it names is reported here by its {@code DFHMDF} label. Five fields are ever named;
         *       see {@link ScreenField}.</li>
         *   <li>{@code MOVE DFHRED TO ERRMSGC OF COUSR2AO} - the colour of the error line, reported as
         *       its unsigned byte value.</li>
         * </ul>
         *
         * <p>{@code resetAllOutputFields} is {@code false}: {@code MOVE LOW-VALUES TO COUSR2AO} at line 97
         * has already been performed on this response, so the cleared state is in the twelve values the
         * client receives and there is nothing left for it to repeat.
         *
         * @return the metadata, never {@code null}
         */
        public ScreenMetadata screenMetadata() {
            return ScreenMetadata.of(cursorField().map(Enum::name).orElse(null),
                    errMsgColour,
                    false);
        }

        /**
         * How the program ended: {@value #TERMINATION_XCTL} or {@value #TERMINATION_RETURN_TRANSID}.
         *
         * <p>Both are real endings and they are mutually exclusive in effect. An {@code XCTL} at lines
         * 258-261 hands control to another program; every other path reaches the
         * {@code EXEC CICS RETURN TRANSID(WS-TRANID)} at 135-138 and re-drives this transaction.
         *
         * @return the termination token, never {@code null}
         */
        public String termination() {
            return transferred ? TERMINATION_XCTL : TERMINATION_RETURN_TRANSID;
        }

        /**
         * The {@code xxxL} item a {@code MOVE -1} named, or {@code null} when no cursor was requested.
         *
         * <p>{@code null} rather than an empty string, because "no cursor request" and "a request naming
         * nothing" are different states and only the first occurs: the {@code WHEN OTHER} arm at 127-130,
         * {@code PF12} at 124-126 and the cold start at 90-92 all end without ever moving {@code -1}.
         *
         * @return the copybook item name, or {@code null}
         */
        public String cursorFieldName() {
            return cursorField == null ? null : cursorField.cobolName();
        }

        /**
         * A diagnostic rendering with the password withheld.
         *
         * <p>The screen's {@code PASSWDI}/{@code PASSWDO} value is replaced by
         * {@link NavigationContext#REDACTED}; neither the value nor its length is disclosed. Whether it is
         * blank <em>is</em> disclosed, because that is presence rather than content and it is what the
         * guard at line 198 and the change test at 227 turn on. {@code SEC-USER-DATA} withholds its own
         * password, so the record area is rendered by its own type.
         *
         * <p>Every other value is printed untrimmed, so trailing spaces stay visible: they are
         * semantically significant to the comparisons at lines 219-234.
         *
         * @return the rendering, never {@code null}
         */
        /**
         * A diagnostic rendering that names the user without disclosing their identity.
         *
         * <p>The password was already withheld, and the rest was not: the user id, both names, the error
         * and working messages and the cursor field were printed verbatim, so any log line rendering this
         * state carried a named person (CWE-532), and every one of those items is text a caller typed - a
         * CR or LF among it forges a second log line (CWE-117).
         *
         * <p>The user id is masked to its last four characters, which is enough to tell one session from
         * another; the two names are described by length only, which is what a width or padding
         * investigation actually needs; and every retained text field is escaped to a single line. The
         * counts, codes, flags and mnemonics disclose nothing and stay as they were.
         *
         * <p>Note that the blank password still renders as its escaped self rather than as
         * {@code [redacted]}, because "the field is spaces" is exactly what a reader needs to know there
         * and spaces disclose nothing - the original behaviour, kept, with the escape added.
         *
         * @return the rendering; never {@code null}
         */
        @Override
        public String toString() {
            return "ProgramState[usrIdIn=" + SensitiveDiagnostics.maskIdentifier(usrIdIn)
                    + ", fName=" + SensitiveDiagnostics.describeText(fName)
                    + ", lName=" + SensitiveDiagnostics.describeText(lName)
                    + ", passwd=" + (isSpacesOrLowValues(passwd)
                            ? DiagnosticText.singleLine(passwd) : NavigationContext.REDACTED)
                    + ", usrType=" + DiagnosticText.singleLine(usrType)
                    + ", errMsg=" + DiagnosticText.singleLine(errMsg)
                    + ", wsMessage=" + DiagnosticText.singleLine(wsMessage)
                    + ", wsErrFlg=" + wsErrFlg
                    + ", wsUsrModified=" + wsUsrModified
                    + ", wsRespCd=" + wsRespCd
                    + ", wsReasCd=" + wsReasCd
                    + ", errMsgColour=" + errMsgColourMnemonic()
                    + ", cursorField=" + cursorField
                    + ", sendCount=" + sends.size()
                    + ", termination=" + termination()
                    + ", nextProgram=" + nextProgram
                    + ", returnTransid=" + returnTransid
                    + ", secUserData=" + secUserData
                    + ", commarea=" + commarea
                    + ']';
        }
    }
}
