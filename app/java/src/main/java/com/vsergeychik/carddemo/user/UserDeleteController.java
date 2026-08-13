package com.vsergeychik.carddemo.user;

import com.vsergeychik.carddemo.common.AidRequestParameter;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.SecUserRepository.HeldRecord;
import com.vsergeychik.carddemo.user.SecUserRepository.ReadResult;
import com.vsergeychik.carddemo.user.SecUserRepository.WriteResult;
import com.vsergeychik.carddemo.user.dto.UserDeleteRequest;
import com.vsergeychik.carddemo.user.dto.UserDeleteRequest.Cu03Info;
import com.vsergeychik.carddemo.user.dto.UserDeleteResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The stateless delete-user screen: a like-for-like migration of {@code app/cbl/COUSR03C.cbl}
 * (359 lines), CSD transaction {@value #WS_TRANID} [{@code app/csd/CARDDEMO.CSD:479-480}], exposed as
 * {@code DELETE }{@value #USERS_PATH}.
 *
 * <p>Nothing here is added, removed, reordered, simplified or corrected relative to that program.
 * Where the COBOL does something odd, this class does the same odd thing and says so at the line it
 * does it.
 *
 * <h2>What the program does</h2>
 *
 * The source header states the function outright: <em>"Delete a user from USRSEC file"</em>. The
 * operator keys a user id and presses ENTER, which reads {@code USRSEC} <strong>for update</strong>
 * and paints the first name, last name and user type so the operator can see what is about to be
 * removed; the message line then reads {@value #MSG_PRESS_PF5}. PF5 confirms and issues the delete.
 * PF4 clears the screen, PF3 and PF12 leave it, and any other key is refused.
 *
 * <h2>Five facts that govern every line below</h2>
 *
 * <ol>
 *   <li><strong>The {@code DELETE} carries no key.</strong> {@code DELETE-USER-SEC-FILE} at lines 307
 *       to 311 is {@code EXEC CICS DELETE DATASET(WS-USRSEC-FILE) RESP(..) RESP2(..)} and nothing
 *       else - there is no {@code RIDFLD}. It removes the record the preceding
 *       {@code READ ... UPDATE} left the task holding. That is why this class calls
 *       {@link HeldRecord#deleteHeld()}, which also takes no argument, and never a delete-by-key
 *       method: passing the user id would be a different operation with different semantics that no
 *       program in this application performs.</li>
 *   <li><strong>The delete-failure message says "Update", and stays wrong.</strong> Line 332 moves
 *       {@value #MSG_UNABLE_TO_UPDATE_USER} on the <em>delete</em> path - a copy/paste error carried
 *       over from {@code COUSR02C}. It is reproduced byte for byte. The text is observable behaviour,
 *       so correcting it to "Delete" would be a behaviour change of exactly the kind this migration
 *       forbids, and the parity harness would flag it. See
 *       {@link #MSG_UNABLE_TO_UPDATE_USER}.</li>
 *   <li><strong>PF3 does not save.</strong> Lines 111 to 118 resolve a return target and transfer,
 *       and that is all. {@code COUSR02C} at its own lines 111 to 119 performs
 *       {@code UPDATE-USER-INFO} first; this program performs no delete on PF3. The asymmetry between
 *       the two sibling screens is preserved rather than harmonised.</li>
 *   <li><strong>The delete runs even when the read failed.</strong> {@code DELETE-USER-INFO} at lines
 *       188 to 192 wraps <em>both</em> the read and the delete in one {@code IF NOT ERR-FLG-ON} and
 *       places <strong>no guard between them</strong>. So a not-found read displays
 *       {@value #MSG_USER_ID_NOT_FOUND}, sends, and then issues the keyless delete anyway - which,
 *       with no record held, CICS answers {@code INVREQ}, landing on the {@code WHEN OTHER} arm and
 *       overwriting the message with {@value #MSG_UNABLE_TO_UPDATE_USER}. Contrast
 *       {@code PROCESS-ENTER-KEY}, which uses two <em>separate</em> {@code IF NOT ERR-FLG-ON}
 *       statements at lines 156 and 164 and therefore does stop. See
 *       {@link #deleteUserInfo(ProgramState)}.</li>
 *   <li><strong>A send is not terminal.</strong> {@code PERFORM SEND-USRDEL-SCREEN} returns to its
 *       caller and execution continues, unlike the send in {@code CORPT00C}. That is why the happy
 *       ENTER path issues <em>two</em> sends - one from the read's {@code NORMAL} arm at line 286 and
 *       one from line 168 once the names have been painted - and why the failed-delete path above
 *       issues two as well. Each send re-derives the whole screen, so the last one is what the
 *       terminal shows and what {@link ProgramState#response()} carries; the count is available from
 *       {@link ProgramState#sendCount()} for a parity case that asserts the sequence.</li>
 * </ol>
 *
 * <h2>The screen</h2>
 *
 * {@code app/bms/COUSR03.bms} declares 26 {@code DFHMDF} fields of which <strong>11 are
 * name-labelled</strong>, and those eleven - and only those eleven - are the payload:
 * {@code TRNNAME X(4)}, {@code TITLE01 X(40)}, {@code CURDATE X(8)}, {@code PGMNAME X(8)},
 * {@code TITLE02 X(40)}, {@code CURTIME X(8)}, {@code USRIDIN X(8)}, {@code FNAME X(20)},
 * {@code LNAME X(20)}, {@code USRTYPE X(1)} and {@code ERRMSG X(78)}.
 * {@link UserDeleteRequest} and {@link UserDeleteResponse} own that contract; this class never
 * restates a width, it reads every one from them.
 *
 * <p><strong>There is no password field on this screen.</strong> That is precisely why it carries
 * eleven named fields where {@code COUSR02} carries twelve. No password is read into a response, none
 * is logged, and none is added for symmetry with the sibling screens.
 *
 * <p>{@code USRIDIN} is the only unprotected field on the map, declared
 * {@code ATTRB=(FSET,IC,NORM,UNPROT)} at {@code app/bms/COUSR03.bms:85-89}. {@code FNAME},
 * {@code LNAME} and {@code USRTYPE} are {@code ASKIP}: the lookup paints them so the operator can
 * confirm what is being deleted, and no statement in the program ever moves them back into the
 * record. They arrive on the request only because the mapset is {@code MODE=INOUT} and they are
 * {@code FSET}, and they are never written to the dataset.
 *
 * <h2>Statelessness</h2>
 *
 * No servlet session type, no session-scoped attribute annotation and no cache appears anywhere in
 * this class or in its signatures. The communication
 * area, the attention identifier and the screen values all travel in the payload: the
 * {@code CARDDEMO-COMMAREA} copied at line 49 and returned to CICS at line 136 becomes
 * {@link UserDeleteRequest#navigationContext()} inbound and
 * {@link UserDeleteResponse#navigationContext()} outbound. Every item of
 * {@code WORKING-STORAGE} - {@code WS-ERR-FLG}, {@code WS-MESSAGE}, {@code WS-RESP-CD},
 * {@code WS-REAS-CD}, {@code WS-USR-MODIFIED} and {@code SEC-USER-DATA} - lives on a per-request
 * {@link ProgramState} and never on this class, so two concurrent requests cannot see each other's
 * screen. This class holds three immutable collaborators and nothing else.
 *
 * <p>{@code EXEC CICS XCTL} becomes a response field rather than a server-side forward: the response
 * names {@link UserDeleteResponse#nextProgram()} and the client issues the follow-up call.
 *
 * <h2>The unit of work</h2>
 *
 * {@code EXEC CICS READ ... UPDATE} holds its record until the CICS task ends, and the task is the
 * unit of work. {@link SecUserRepository#readForUpdate(String)} enforces that: it refuses outright
 * when no transaction is open, because a lock that is released before the delete protects nothing. So
 * the HTTP entry point is {@link Transactional} - one request, one unit of work, exactly as one task
 * is one unit of work in CICS. A caller that drives {@link #mainPara(UserDeleteRequest, byte, String)}
 * directly against a live repository must open the unit of work itself; a unit test with a mocked
 * repository needs none.
 *
 * <h2>How this class is exercised</h2>
 *
 * All decision logic is reachable without HTTP. {@link #mainPara(UserDeleteRequest, byte, String)}
 * takes the raw {@code EIBAID} byte and returns the terminal {@link ProgramState}, so every arm of
 * {@code EVALUATE EIBAID}, both sides of {@code ERR-FLG-ON} / {@code ERR-FLG-OFF}, both sides of
 * {@code CDEMO-PGM-ENTER} / {@code CDEMO-PGM-REENTER} and every {@code RESP} arm of both file
 * operations can be driven by a plain constructor call with a mocked {@link SecUserRepository} and a
 * fixed {@link Clock}. The screen-only projection {@link #deleteUser(String, UserDeleteRequest)} adds
 * nothing but the HTTP binding.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * UserDeleteController controller = new UserDeleteController(secUserRepository,
 *         Clock.fixed(Instant.parse("2022-07-19T23:12:35Z"), ZoneOffset.UTC));
 *
 * // First call: no communication area at all, so EIBCALEN = 0 and the program hands control back
 * // to the sign-on screen (lines 90-92).
 * UserDeleteController.ProgramState cold = controller.mainPara(null, CicsAid.DFHENTER, null);
 * cold.response().nextProgram();            // "COSGN00C"
 *
 * // Fetch for confirmation: re-entry, ENTER, a user id on the screen.
 * UserDeleteRequest fetch = UserDeleteRequest.empty()
 *         .withUsrIdIn("USER0001");         // conceptually; the record is immutable
 * UserDeleteController.ProgramState shown =
 *         controller.mainPara(fetch, CicsAid.DFHENTER, null);
 * shown.response().errMsg();                // "Press PF5 key to delete this user ..." padded to 78
 *
 * // Confirm: PF5.
 * UserDeleteController.ProgramState gone = controller.mainPara(fetch, CicsAid.DFHPF5, null);
 * gone.response().errMsg();                 // "User USER0001 has been deleted ..." padded to 78
 * gone.errMsgColour();                      // BmsAttributes.DFHGREEN
 * </pre>
 *
 * @see SecUserRepository#readForUpdate(String) the locking read of lines 269 to 278
 * @see HeldRecord#deleteHeld() the keyless delete of lines 307 to 311
 * @see UserDeleteRequest the inbound projection of {@code 01 COUSR3AI}
 * @see UserDeleteResponse the outbound projection of {@code 01 COUSR3AO}
 * <h2>This endpoint is unauthenticated and unauthorized - an accepted divergence (CWE-306 and CWE-862)</h2>
 *
 * <p>This controller deletes a record from {@code USRSEC} with no authentication and no role check.
 * Nothing here establishes who is calling or that they administer users, so this endpoint can remove
 * any security record - including the last administrator - for any caller that can reach it.
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
public class UserDeleteController {

    // =================================================================================================
    // Identity. Both values are taken from the payload contract rather than restated, so this class and
    // the DTO pair cannot drift apart: there is exactly one literal for each and it lives on the type
    // that owns the screen. Practice B8 - explicit over implicit at every boundary.
    // =================================================================================================

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COUSR03C'} - {@code app/cbl/COUSR03C.cbl:36}.
     *
     * <p>Moved onto the screen by {@code MOVE WS-PGMNAME TO PGMNAMEO OF COUSR3AO} at line 250 and into
     * {@code CDEMO-FROM-PROGRAM} by {@code RETURN-TO-PREV-SCREEN} at line 203.
     */
    public static final String WS_PGMNAME = UserDeleteRequest.PROGRAM_NAME;

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CU03'} - {@code app/cbl/COUSR03C.cbl:37}.
     *
     * <p>Moved onto the screen at line 249, into {@code CDEMO-FROM-TRANID} at line 202, and handed back
     * to CICS as the next {@code TRANSID} at line 135. {@code app/csd/CARDDEMO.CSD:479-480} binds it to
     * {@value #WS_PGMNAME}.
     */
    public static final String WS_TRANID = UserDeleteRequest.TRANSACTION_ID;

    /**
     * The BMS map this program sends and receives: {@code COUSR3A}, named at lines 220 and 233.
     */
    public static final String WS_MAP = UserDeleteRequest.MAP_NAME;

    /**
     * The BMS mapset: {@code COUSR03}, named at lines 221 and 234.
     */
    public static final String WS_MAPSET = UserDeleteRequest.MAPSET_NAME;

    /**
     * The REST resource this screen projects onto: {@code DELETE }{@value #USERS_PATH}.
     *
     * <p>One route, per the migration's API surface for transaction {@value #WS_TRANID}. The path
     * variable is the user id, which is the record's key.
     */
    public static final String USERS_PATH = "/api/users/{userId}";

    /** The name of the {@value #USERS_PATH} template variable, bound explicitly rather than inferred. */
    public static final String USER_ID_VARIABLE = "userId";

    /**
     * The query parameter carrying the raw {@code EIBAID} byte as an unsigned {@code 0}-{@code 255} value.
     *
     * <p>{@link AidRequestParameter#CANONICAL_NAME}, the one spelling every online route accepts, so the
     * key is named the same way here as on the user list this screen is reached from. It is the carrier
     * that can name any of the 26 attention identifiers, including the twelve high function keys a folded
     * {@code CCARD-AID} token cannot distinguish from their low twins - which on this screen is the
     * difference between an invalid-key message and a deleted record.
     */
    public static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    /** The accepted alternate spelling of {@value #EIBAID_PARAM} - see {@link AidRequestParameter}. */
    public static final String EIBAID_PARAM_ALIAS = AidRequestParameter.ALTERNATE_NAME;

    /** The lowest value an unsigned {@code EIBAID} byte can carry. */
    static final int AID_MIN = 0;

    /** The highest value an unsigned {@code EIBAID} byte can carry. */
    static final int AID_MAX = 255;

    /**
     * The width of the raw {@code EIBAID} form of {@link UserDeleteRequest#aid()}: one character.
     *
     * <p>{@link PfKeyResolver#AID_TOKEN_LENGTH} is five, the width of the {@code CCARD-AID} token this
     * module's responses publish; this is the width of the byte lines 108 to 130 evaluate.
     */
    static final int RAW_AID_LENGTH = 1;

    /** The highest code point an attention identifier can hold - {@code EIBAID} is one byte. */
    static final char MAX_AID_CODE_POINT = 0x00FF;

    // =================================================================================================
    // Transfer targets. Three literals, each at the line that moves it into CDEMO-TO-PROGRAM.
    // =================================================================================================

    /**
     * {@code 'COSGN00C'} - the sign-on program, moved into {@code CDEMO-TO-PROGRAM} at line 91 when
     * {@code EIBCALEN = 0} and again at line 200 when the target is otherwise unset.
     */
    public static final String LIT_SIGNON_PGM = "COSGN00C";

    /**
     * {@code 'COADM01C'} - the administrator menu, moved into {@code CDEMO-TO-PROGRAM} at line 113 when
     * PF3 has no caller to return to, and unconditionally at line 124 on PF12.
     */
    public static final String LIT_ADMIN_PGM = "COADM01C";

    // =================================================================================================
    // Message literals, transcribed character for character from the source lines named beside each.
    // The ellipses are part of the text. Note where a space is and is not present before one: the five
    // status texts have none, and the success suffix has one.
    // =================================================================================================

    /**
     * {@code 'User ID can NOT be empty...'} - moved into {@code WS-MESSAGE} at line 147 on the ENTER
     * path and at line 179 on the PF5 path.
     *
     * <p>This is why {@link UserDeleteRequest} carries no {@code @NotBlank} on its user id: the program
     * answers a blank field with this message on the screen, and rejecting the request with a 400 would
     * be different observable behaviour.
     */
    public static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /**
     * {@code 'Press PF5 key to delete this user ...'} - line 283, the confirmation prompt the
     * successful lookup paints. Sent with the neutral colour byte set at line 285.
     *
     * <p>Note the space before the ellipsis, and note that this prompt is produced on the PF5 path too:
     * the read runs before the delete, so its {@code NORMAL} arm sends this message immediately before
     * the delete replaces it. The sequence is preserved rather than suppressed.
     */
    public static final String MSG_PRESS_PF5 = "Press PF5 key to delete this user ...";

    /**
     * {@code 'User ID NOT found...'} - line 289 on the lookup and line 325 on the delete.
     */
    public static final String MSG_USER_ID_NOT_FOUND = "User ID NOT found...";

    /**
     * {@code 'Unable to lookup User...'} - line 296, the lookup's {@code WHEN OTHER} arm.
     */
    public static final String MSG_UNABLE_TO_LOOKUP_USER = "Unable to lookup User...";

    /**
     * {@code 'Unable to Update User...'} - line 332, the <strong>delete's</strong> {@code WHEN OTHER}
     * arm.
     *
     * <p><strong>This text is wrong in the legacy source and is preserved exactly as it stands.</strong>
     * It says "Update" on a screen whose only write is a delete, because the paragraph was copied from
     * {@code COUSR02C}, where the same {@code WHEN OTHER} arm follows a {@code REWRITE}. It is a defect,
     * it is observable, and it is not this migration's to fix: the mandate is a like-for-like language
     * migration with no changed behaviour, and inherited defects are preserved rather than tidied.
     *
     * <p>A reviewer reaching for the word "Delete" here should stop. No such wording exists anywhere in
     * the legacy program, so none may exist anywhere in its translation - not in this constant, not in a
     * comment, and not in a test's expected value.
     */
    public static final String MSG_UNABLE_TO_UPDATE_USER = "Unable to Update User...";

    /**
     * {@code 'User '} - the first operand of the success message, contributed
     * {@code DELIMITED BY SIZE} at line 318. Five characters, the trailing space included.
     */
    public static final String MSG_USER_PREFIX = "User ";

    /**
     * {@code ' has been deleted ...'} - the third operand of the success message, contributed
     * {@code DELIMITED BY SIZE} at line 320. Twenty-one characters, with a leading space and a space
     * before the ellipsis.
     *
     * <p>The leading space belongs to this literal because the middle operand is delimited by space and
     * so contributes no padding of its own.
     */
    public static final String MSG_HAS_BEEN_DELETED_SUFFIX = " has been deleted ...";

    // =================================================================================================
    // DISPLAY. Both statements in this program are live - neither is commented out, unlike the
    // equivalent in COUSR01C - so both are rendered and both are recorded.
    // =================================================================================================

    /** {@code 'RESP:'} - the first literal of the {@code DISPLAY} at lines 294 and 330. */
    public static final String DISPLAY_RESP_PREFIX = "RESP:";

    /** {@code 'REAS:'} - the third literal of the {@code DISPLAY} at lines 294 and 330. */
    public static final String DISPLAY_REAS_PREFIX = "REAS:";

    /**
     * The digit width of {@code WS-RESP-CD} and {@code WS-REAS-CD}, both
     * {@code PIC S9(09) COMP} - {@code app/cbl/COUSR03C.cbl:43-44}.
     *
     * <p>A {@code DISPLAY} of a binary item converts it to display form, so each code renders as nine
     * digit characters.
     */
    public static final int WS_RESP_CD_DIGITS = 9;

    // =================================================================================================
    // Widths. Every one is read from the type that owns the declaration rather than restated here, so a
    // single copybook value can never disagree with itself across the module.
    // =================================================================================================

    /** {@code WS-MESSAGE PIC X(80) VALUE SPACES} - {@code app/cbl/COUSR03C.cbl:38}. */
    public static final int WS_MESSAGE_LENGTH = UserDeleteResponse.WS_MESSAGE_LENGTH;

    /**
     * {@code ERRMSGO PIC X(78)} - {@code app/cpy-bms/COUSR03.CPY:152}.
     *
     * <p>Two narrower than {@code WS-MESSAGE}, which is why {@code MOVE WS-MESSAGE TO ERRMSGO} at line
     * 217 is a truncating move and is routed through {@link FixedWidthCodec#movePicX(String, int)}.
     */
    public static final int ERR_MSG_LENGTH = UserDeleteResponse.ERR_MSG_LENGTH;

    /** {@code USRIDINI PIC X(8)} - {@code app/cpy-bms/COUSR03.CPY:60}. */
    public static final int USR_ID_IN_LENGTH = UserDeleteRequest.USRIDIN_LENGTH;

    /**
     * The payload member the URI's user identity binds, spelled as the client sends it.
     *
     * <p>Lowercase and {@code xxxI}-derived, which is this module's one JSON naming convention, so a
     * refusal names the member the caller can find in its own request body.
     */
    static final String USRIDIN_MEMBER = "usridin";

    /**
     * The payload member carrying the {@code CCARD-AID} token, spelled as the client sends it.
     *
     * <p>Named in a refusal so a caller with an eleven-field body knows which member contradicted the raw
     * byte it also sent.
     */
    static final String AID_MEMBER = "aid";


    /** {@code FNAMEI PIC X(20)} - {@code app/cpy-bms/COUSR03.CPY:66}. */
    public static final int F_NAME_LENGTH = UserDeleteRequest.FNAME_LENGTH;

    /** {@code LNAMEI PIC X(20)} - {@code app/cpy-bms/COUSR03.CPY:72}. */
    public static final int L_NAME_LENGTH = UserDeleteRequest.LNAME_LENGTH;

    /** {@code USRTYPEI PIC X(1)} - {@code app/cpy-bms/COUSR03.CPY:78}. */
    public static final int USR_TYPE_LENGTH = UserDeleteRequest.USRTYPE_LENGTH;

    /** {@code TRNNAMEI PIC X(4)} - {@code app/cpy-bms/COUSR03.CPY:24}. */
    public static final int TRN_NAME_LENGTH = UserDeleteRequest.TRNNAME_LENGTH;

    /** {@code PGMNAMEI PIC X(8)} - {@code app/cpy-bms/COUSR03.CPY:42}. */
    public static final int PGM_NAME_LENGTH = UserDeleteRequest.PGMNAME_LENGTH;

    /** {@code TITLE01I} and {@code TITLE02I}, both {@code PIC X(40)}. */
    public static final int TITLE_LENGTH = ScreenTitles.TITLE_LENGTH;

    /** {@code CURDATEI PIC X(8)} - the {@code MM/DD/YY} header field. */
    public static final int CUR_DATE_LENGTH = UserDeleteRequest.CURDATE_LENGTH;

    /**
     * {@code CURTIMEI PIC X(8)} - the {@code HH:MM:SS} header field.
     *
     * <p>Eight, not nine: of the seventeen mapsets only {@code COSGN00} widens its time field.
     */
    public static final int CUR_TIME_LENGTH = UserDeleteRequest.CURTIME_LENGTH;

    /** {@code SEC-USR-ID PIC X(08)} - {@code app/cpy/CSUSR01Y.cpy:18}, and the {@code USRSEC} key. */
    public static final int SEC_USR_ID_LENGTH = SecUserRecord.SEC_USR_ID_LENGTH;

    /** {@code CDEMO-TO-PROGRAM PIC X(08)} - {@code app/cpy/COCOM01Y.cpy:24}. */
    public static final int TO_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /** {@code CDEMO-FROM-TRANID PIC X(04)} - {@code app/cpy/COCOM01Y.cpy:21}. */
    public static final int FROM_TRANID_LENGTH = NavigationContext.FROM_TRANID_LENGTH;

    /** {@code CDEMO-FROM-PROGRAM PIC X(08)} - {@code app/cpy/COCOM01Y.cpy:22}. */
    public static final int FROM_PROGRAM_LENGTH = NavigationContext.FROM_PROGRAM_LENGTH;

    /** {@code CDEMO-LAST-MAP PIC X(7)} - the width the navigation triple's map field carries. */
    public static final int NEXT_MAP_LENGTH = UserDeleteResponse.NEXT_MAP_LENGTH;

    /** {@code CDEMO-LAST-MAPSET PIC X(7)} - the width the navigation triple's mapset field carries. */
    public static final int NEXT_MAPSET_LENGTH = UserDeleteResponse.NEXT_MAPSET_LENGTH;

    /** The single space, used to build the space-filled images a cleared {@code PIC X} field holds. */
    private static final String SPACE = " ";

    /**
     * The COBOL figurative constant {@code LOW-VALUES}: the lowest character in the collating sequence,
     * which is the null byte.
     *
     * <p>Needed because two tests in this program compare a field against {@code SPACES} <em>and</em>
     * {@code LOW-VALUES} - lines 99, 145 and 177 - and a screen field that has been cleared with
     * {@code MOVE LOW-VALUES} holds this character rather than a space.
     */
    private static final char LOW_VALUE = '\u0000';

    /** Where this class writes the two {@code DISPLAY} statements of lines 294 and 330. */
    private static final Log LOG = LogFactory.getLog(UserDeleteController.class);

    // =================================================================================================
    // Collaborators. Three, all immutable, all constructor injected, and no other field on this class -
    // every mutable item of WORKING-STORAGE lives on ProgramState instead.
    // =================================================================================================

    /** The {@code USRSEC} dataset, reached only through this repository and never by name. */
    private final SecUserRepository secUserRepository;

    /** The clock {@code FUNCTION CURRENT-DATE} reads at line 245, injected so a parity case is stable. */
    private final Clock clock;

    /** The {@code PICTURE} move rules, carrying the dataset's code page. */
    private final FixedWidthCodec codec;

    /**
     * Constructs the controller.
     *
     * <p>The code page comes from {@link SecUserRepository#datasetCharset()} rather than from a second
     * injection point, so the controller cannot possibly render a field in a different encoding from the
     * repository it reads that field through. It is never the platform default: a fixed-width mainframe
     * field is bytes in a specific code page, and the page is always stated.
     *
     * @param secUserRepository the security-user dataset; {@code COUSR03C} touches no other file
     * @param clock             the clock {@code POPULATE-HEADER-INFO} reads
     * @throws NullPointerException if either argument is {@code null}
     */
    public UserDeleteController(SecUserRepository secUserRepository, Clock clock) {
        this.secUserRepository = Objects.requireNonNull(secUserRepository,
                "A SecUserRepository is required: COUSR03C reads USRSEC for update at lines 269-278 and "
                        + "deletes the held record at lines 307-311, and reaches the dataset no other way");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: POPULATE-HEADER-INFO reads FUNCTION CURRENT-DATE at line 245, and "
                        + "reading a clock inline would make every parity case non-deterministic");
        this.codec = new FixedWidthCodec(secUserRepository.datasetCharset());
    }

    /**
     * The codec this controller performs every {@code MOVE} through, carrying the active code page.
     *
     * <p>Package-visible for verification rather than convenience: a parity case must build its expected
     * images with the same code page the controller renders with, and a deployment check needs to assert
     * which page was wired without starting the application. A code page is this module's business and
     * no client's, so it is not {@code public}.
     *
     * @return the codec; never {@code null}, and immutable
     */
    FixedWidthCodec codec() {
        return codec;
    }

    // =================================================================================================
    // The HTTP binding. This method adds the route, the payload binding and the unit of work, and no
    // behaviour whatsoever: every decision is taken by mainPara and is reachable without HTTP (gate
    // G51). No servlet type appears in the signature, so a unit test calls it directly.
    // =================================================================================================

    /**
     * {@code DELETE }{@value #USERS_PATH} - the whole of transaction {@value #WS_TRANID}.
     *
     * <h4>What the path variable is</h4>
     * The user id in the path and the payload's {@code USRIDIN} name the same key, and the path variable
     * is authoritative for the resource's identity. Its role differs between the two entry paths,
     * exactly as the source's two sources of the key differ:
     *
     * <ul>
     *   <li><strong>First entry</strong> - {@code CDEMO-PGM-CONTEXT} is not {@code REENTER}, so the path
     *       variable plays {@code CDEMO-CU03-USR-SELECTED} [{@code app/cbl/COUSR03C.cbl:58}], the id the
     *       user-list screen hands over when the operator marks a row for deletion. Lines 99 to 104 move
     *       it onto the screen and fetch the record; a blank one paints an empty screen instead.</li>
     *   <li><strong>Re-entry</strong> - the screen carries the id in {@code USRIDIN} and the program reads
     *       it from there at lines 145, 160, 177 and 189. The path variable is not consulted, exactly as
     *       the source does not consult the communication area's selected id once the operator is typing
     *       into the map.</li>
     * </ul>
     *
     * <h4>The path is projected into every carrier of the key, and nothing is refused for disagreeing</h4>
     * On a real terminal the two carriers are one value: the operator sees {@code USRIDIN}, and
     * {@code CDEMO-CU03-USR-SELECTED} is the same id handed over by the list screen. A URI introduces a
     * second, authoritative statement of that id, so the seam makes the URI win in <em>both</em> carriers
     * before any source logic runs - {@code USRIDIN}, which lines 145, 160, 177 and 189 read, and the
     * {@code CDEMO-CU03-USR-SELECTED} extension, which lines 99 to 102 read. One key, one value,
     * whichever turn the conversation is on.
     *
     * <p>No disagreement error is raised, and that is deliberate: {@code COUSR03C} has no such condition
     * anywhere, so inventing one would add a failure mode the legacy screen cannot produce - and it would
     * leave the more dangerous half of the problem unsolved, because a blank {@code USRIDIN} is not a
     * disagreement yet would still have left the read and delete key blank on re-entry. Projecting closes
     * both: a client that echoes a painted screen agrees with the path and sees no change, and a client
     * that names a second user in the body has that value replaced rather than acted on. Neither branch
     * of {@code 'User ID can NOT be empty...'} becomes unreachable - a path segment of percent-encoded
     * spaces is a blank identifier, and {@link #mainPara(UserDeleteRequest, Optional, String)} is
     * callable directly with any buffer at all.
     *
     * <h4>The identity is refused rather than truncated</h4>
     * {@code USRIDIN} is {@code PIC X(08)}. An alphanumeric {@code MOVE} would keep the leading eight
     * characters, so {@code DELETE /api/users/USER0001EXTRA} would delete {@code USER0001} - a record the
     * URI does not name, reached by a value no 3270 field could have held. Refused at the boundary.
     *
     * <h4>Why the body is optional</h4>
     * An absent body is {@code EIBCALEN = 0}: the cold start the source handles at lines 90 to 92 by
     * handing control to {@value #LIT_SIGNON_PGM}. A body whose communication area is absent projects the
     * same condition.
     *
     * <h4>Why this method is transactional</h4>
     * {@code EXEC CICS READ ... UPDATE} at lines 269 to 278 holds its record for the life of the CICS
     * task, and the delete at lines 307 to 311 acts on what is still held. One request is one task and
     * therefore one unit of work. {@link SecUserRepository#readForUpdate(String)} refuses to run outside
     * one, which turns a silent concurrency defect into a loud wiring failure.
     *
     * @param userId  the user id from the path; the resource's identity, and the value both
     *                {@code USRIDIN} and {@code CDEMO-CU03-USR-SELECTED} carry once bound
     * <h4>Why the raw {@code EIBAID} byte matters more on this screen than on any other</h4>
     * {@code COUSR03C} tests {@code EIBAID} inline and does not copy {@code app/cpy/CSSTRPFY.cpy}, so on
     * the terminal {@code PF17} matches none of its five {@code WHEN} clauses and paints "invalid key".
     * The copybook folds {@code PF17} onto {@code 'PFK05'}, and {@code PFK05} is this program's
     * <strong>confirm-and-delete</strong> arm at lines 121-122. A caller restricted to the five-character
     * token therefore cannot express {@code PF17} at all, and a token-driven dispatch would delete a user
     * record on a key the mainframe rejects. Both spellings of {@link AidRequestParameter} bind here and
     * carry all 256 values, and a stated byte is resolved <em>without</em> the fold - see
     * {@link PfKeyResolver#resolveWithoutFolding(byte)} - so {@code PF17} reaches {@code WHEN OTHER}. A
     * request that sends neither parameter keeps the token decode it always had.
     *
     * @param request the inbound screen, validated against the symbolic map's declared widths;
     *                {@code null} when no body was sent, which is {@code EIBCALEN = 0}
     * @param eibaid  {@code EIBAID} as an unsigned {@code 0}-{@code 255} byte under
     *                {@value #EIBAID_PARAM}, optional. Absent it is read from the payload's own
     *                one-character {@code aid} image; stated it wins, because an integer names any of the
     *                256 values where a folded token cannot - and on this screen the fold would put
     *                {@code PF17} on the delete arm
     * @param eibAid  the same value under {@value #EIBAID_PARAM_ALIAS}; at most one need be sent
     * @return the outbound screen - the eleven map fields, the communication area and the navigation
     *         triple; never {@code null}
     * @throws NullPointerException     if {@code userId} is {@code null}
     * @throws IllegalArgumentException if {@code userId} is wider than {@code USRIDIN}, if the stated AID
     *                                  byte is outside {@code 0}-{@code 255}, or if both spellings are
     *                                  present and disagree
     */
    // The two AID parameters are appended last: Spring binds by the name in the annotation and never by
    // position, so the two arguments this method already had keep their meaning for every direct caller.
    @DeleteMapping(path = USERS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ScreenResponse<UserDeleteResponse> deleteUser(
            @PathVariable(USER_ID_VARIABLE) String userId,
            @Valid @RequestBody(required = false) UserDeleteRequest request,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid) {
        Objects.requireNonNull(userId, "A user id is required in the path: it is the record's key, and "
                + "on first entry it is CDEMO-CU03-USR-SELECTED");
        requireIdentityFits(userId);
        UserDeleteRequest received = bindPathIdentity(userId, request);
        String usrSelected = received == null ? userId : received.cu03Info().usrSelected();
        ProgramState state = mainPara(received,
                resolveEibAid(AidRequestParameter.resolve(eibaid, eibAid), received), usrSelected);
        return ScreenResponse.of(state.response(), state.screenMetadata());
    }

    /**
     * Requires the path identity to fit {@code USRIDIN PIC X(08)}, refusing it rather than truncating.
     *
     * @param userId the path variable
     * @throws IllegalArgumentException if it is wider than {@value #USR_ID_IN_LENGTH} characters
     */
    static void requireIdentityFits(String userId) {
        if (userId.length() > USR_ID_IN_LENGTH) {
            throw ScreenInputRejectedException.tooWide(USRIDIN_MEMBER,
                    "USRIDINI PIC X(" + USR_ID_IN_LENGTH + ") and SEC-USR-ID PIC X("
                            + USR_ID_IN_LENGTH + ")",
                    USR_ID_IN_LENGTH, userId.length());
        }
    }

    /**
     * Projects the path's identity into every carrier of the key this transaction reads, so that the URI
     * is the only statement of which record is acted on.
     *
     * <p>{@code COUSR03C} reads the id from two places, and which one it reads depends on the turn:
     * {@code CDEMO-CU03-USR-SELECTED} on first entry [lines 99-102], and {@code USRIDINI} on re-entry
     * [lines 145, 160, 177 and 189]. <strong>So the path seeds the first entry and is ignored on a
     * re-entry.</strong> On first entry - a payload carrying no communication area, or one whose context
     * is not re-entry - both the extension's selected id and the {@code USRIDIN} slot are written from
     * the path, which is exactly what line 99's {@code MOVE CDEMO-CU03-USR-SELECTED TO USRIDINI} does
     * with the id the list screen handed over. The value is written at {@code USRIDIN}'s declared
     * {@code PIC X(08)} width, because that is what the field holds on a terminal; the path has already
     * been required to fit, so the {@code MOVE} only pads.
     *
     * <p>On a re-entry the received {@code USRIDINI} is carried through <strong>exactly as it
     * arrived</strong>. Typing another user id over the painted screen and then pressing PF5 is the
     * source's own delete sequence [{@code :145}, {@code :160}, {@code :177}, {@code :189}], and the
     * program's own validation - {@code 'User ID can NOT be empty...'} for a blank field, {@code 'User ID
     * NOT found...'} for one that names no record - is what governs it. Overwriting the field from the
     * URI would discard the operator's typed id, and refusing the request for differing from the URI
     * would answer a state the legacy screen produces. Every other member of the payload travels exactly
     * as the caller delivered it.
     *
     * @param userId  the path variable, already known to fit {@value #USR_ID_IN_LENGTH} characters
     * @param request the bound body, or {@code null} for {@code EIBCALEN = 0}
     * @return the request to execute, or {@code null} when there was no body at all - a body cannot be
     *         invented here, because its absence is what line 90 branches on
     */
    UserDeleteRequest bindPathIdentity(String userId, UserDeleteRequest request) {
        if (request == null) {
            return null;
        }
        if (request.navigationContext() != null && request.navigationContext().isReenter()) {
            // :106-107 - RECEIVE-USRDEL-SCREEN then EVALUATE EIBAID. The screen's own key is what the
            // program reads from here on, so nothing about the payload is rewritten.
            return request;
        }
        String identity = codec.movePicX(userId, USR_ID_IN_LENGTH);
        Cu03Info extension = request.cu03Info();
        return new UserDeleteRequest(request.trnName(),
                request.title01(),
                request.curDate(),
                request.pgmName(),
                request.title02(),
                request.curTime(),
                identity,
                request.fName(),
                request.lName(),
                request.usrType(),
                request.errMsg(),
                request.navigationContext(),
                request.aid(),
                new Cu03Info(extension.usridFirst(),
                        extension.usridLast(),
                        extension.pageNum(),
                        extension.nextPageFlg(),
                        extension.usrSelFlg(),
                        identity));
    }

    // =================================================================================================
    // Attention-identifier resolution. COUSR03C tests EIBAID inline and does not copy CSSTRPFY, so the
    // module's one resolver is used in its NON-FOLDING form here: the boolean outcomes are then identical
    // to the source's five WHEN clauses, and the resolver's explicit no-match result joins WHEN OTHER.
    // Folding would be wrong on this screen specifically - PFK05 is the delete arm - and not merely
    // imprecise.
    // =================================================================================================

    /**
     * Reads the raw {@code EIBAID} byte out of the payload's {@code aid} member.
     *
     * <p><strong>One character is the byte.</strong> Its code point <em>is</em> the attention identifier,
     * so {@code DFHENTER} travels as {@code U+007D} and {@code DFHPF5} - this screen's confirm-delete key
     * - as {@code U+00F5}, and the {@code EVALUATE EIBAID} at lines 108 to 130 compares exactly that.
     *
     * <p><strong>Any other width, and an absent value, yield {@link CicsAid#DFHNULL}</strong>, which
     * {@link PfKeyResolver#resolve(byte)} matches to no condition name at all and which is none of the
     * five values this program names. It therefore reaches the {@code WHEN OTHER} arm at lines 126 to 129,
     * which is the answer the source gives to any key that is not one of its five. It is deliberately
     * <strong>not</strong> defaulted to {@code DFHENTER}: this program, unlike {@code COCRDLIC}, does not
     * fold unhandled keys onto ENTER, and quietly doing so here would perform a lookup the operator never
     * asked for.
     *
     * <h4>Why a {@code CCARD-AID} token is no longer decoded back to a byte</h4>
     * {@code app/cpy/CSSTRPFY.cpy} folds {@code DFHPF13}-{@code DFHPF24} onto {@code 'PFK01'}-{@code
     * 'PFK12'}, so {@code 'PFK05'} stands for {@code DFHPF5} <em>and</em> {@code DFHPF17}. Decoding it had
     * to choose, and choosing {@code DFHPF5} <strong>deleted the user</strong> for a {@code PF17} press
     * the source answers with the invalid-key message at lines 126 to 129 - the most consequential fold in
     * this module, because the key it folds onto is the one that destroys a record. {@code COUSR03C} does
     * not copy {@code CSSTRPFY}: it compares {@code EIBAID} itself, so the fold is not its behaviour and
     * there is nothing to invert. The token survives as derived metadata on the way out, where
     * {@link PfKeyResolver#resolve(byte)} produces it.
     *
     * <p>A character above {@link #MAX_AID_CODE_POINT} is reported as {@code DFHNULL} rather than
     * narrowed: {@code EIBAID} is one byte, so a cast of {@code U+01F5} would keep its low eight bits and
     * land on {@code 0xF5}, which <em>is</em> {@code DFHPF5}, the delete key.
     *
     * @param aidImage the {@code aid} member as it arrived, or {@code null} when the caller named no key
     * @return the raw attention-identifier byte; never throws
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

    /**
     * Resolves the byte lines 108 to 130 evaluate, from the query parameter or from the payload.
     *
     * <p>The parameter wins when present, because an unsigned {@code 0}-{@code 255} integer can name any
     * of the 26 attention identifiers. When it is absent the payload's own one-character {@code aid} image
     * is read; {@link #eibAidOf(String)} states what any other width means.
     *
     * <p>An image stated beside a disagreeing byte is refused rather than dropped, which is the shared
     * rule in {@link AidRequestParameter#requireStatedAid(String, Integer, String, FixedWidthCodec)}:
     * discarding the caller's own statement of the key is the fault this parameter exists to remove.
     *
     * @param eibaid  the stated byte as an unsigned value, or {@code null} if neither spelling was sent
     * @param request the inbound screen; may be {@code null}, which is {@code EIBCALEN = 0}
     * @return the raw attention-identifier byte
     * @throws IllegalArgumentException if {@code eibaid} is outside {@code 0}-{@code 255}, or the payload
     *                                  states a different key beside it
     */
    byte resolveEibAid(Integer eibaid, UserDeleteRequest request) {
        String aidImage = request == null ? null : request.aid();
        if (eibaid == null) {
            return eibAidOf(aidImage);
        }
        return AidRequestParameter.requireStatedAid(AID_MEMBER, eibaid, aidImage, codec);
    }

    // =================================================================================================
    // MAIN-PARA - app/cbl/COUSR03C.cbl:82-137.
    // =================================================================================================

    /**
     * {@code MAIN-PARA} - the program's entry point, lines 82 to 137, driven by a raw {@code EIBAID} byte
     * - the form the source itself is written in.
     *
     * <p>The byte is compared, not folded. {@link PfKeyResolver#isEnter(byte)} and its siblings are
     * equalities against one constant each, so {@code DFHPF17} does <em>not</em> behave as {@code DFHPF5}
     * here - which is what {@code EIBAID = DFHPF5} means, and it matters more on this screen than on any
     * other, because {@code PF5} is the key that deletes the record. Both kinds of unhandled key reach
     * {@code WHEN OTHER} and are indistinguishable there, exactly as on a terminal: {@code DFHPF7}, a key
     * {@code CSSTRPFY} names but this program has no arm for, and {@code DFHPA3}, which {@code CSSTRPFY}
     * does not name at all.
     *
     * <p>Returns the terminal {@link ProgramState} rather than only the response, because four
     * observable things this program produces have no home in the map's payload: the
     * {@code MOVE -1 TO <field>L} cursor request and the {@code MOVE <colour> TO ERRMSGC} colour byte,
     * which are {@code xxxL} and {@code xxxC} metadata and must not be smuggled into a payload that
     * projects only {@code xxxI} and {@code xxxO} items; the two {@code DISPLAY} statements; and how many
     * times the screen was sent. A parity case needs all four, so they are reported on the state and the
     * HTTP adapter projects {@link ProgramState#response()}.
     *
     * <p>The three top-level arms are in source order:
     *
     * <ol>
     *   <li><strong>Lines 90 to 92</strong> - {@code EIBCALEN = 0}: no communication area was passed, so
     *       the program names {@value #LIT_SIGNON_PGM} and transfers. Nothing is read and nothing is
     *       deleted.</li>
     *   <li><strong>Lines 95 to 105</strong> - first entry: mark the conversation re-entrant, clear the
     *       screen, put the cursor on the user id, and - <em>only if</em>
     *       {@code CDEMO-CU03-USR-SELECTED} names someone - fetch that record before sending. The guard
     *       at line 99 is load-bearing: without a selected user the screen is painted empty and no read
     *       is issued.</li>
     *   <li><strong>Lines 107 to 130</strong> - re-entry: receive the map, then dispatch on the AID.</li>
     * </ol>
     *
     * @param request     the inbound screen, or {@code null} for {@code EIBCALEN = 0}
     * @param eibAid      the raw EBCDIC attention-identifier byte, as CICS places it in {@code EIBAID}
     * @param usrSelected {@code CDEMO-CU03-USR-SELECTED}; consulted on first entry only, and may be
     *                    {@code null}
     * @return the state at the moment the task returned to CICS or transferred; never {@code null}
     */
    public ProgramState mainPara(UserDeleteRequest request, byte eibAid, String usrSelected) {
        ProgramState state = new ProgramState();

        // The 34-byte extension arrives with the communication area and leaves with it, unchanged apart
        // from the one item this program reads. Recorded on the state here so both terminals - the XCTL at
        // lines 205-208 and the RETURN at 133-136 - hand back the same thirty-four bytes that arrived.
        state.setCu03Info(request == null ? Cu03Info.initial() : request.cu03Info());

        // L84  SET ERR-FLG-OFF TO TRUE - the flag starts 'N', and ProgramState starts it false.
        state.setErrFlagOff();

        // L85  SET USR-MODIFIED-NO TO TRUE. Nothing in this program ever sets it to 'Y' and nothing ever
        // tests it, but the SET is executable code, so it is executed rather than dropped.
        state.setUsrModifiedNo();

        // L87-88  MOVE SPACES TO WS-MESSAGE, ERRMSGO OF COUSR3AO. Blanking ERRMSGO is redundant in
        // practice - every send re-derives it from WS-MESSAGE at line 217 - and it is performed anyway,
        // in this order, because the source performs it.
        state.setMessage(spaces(WS_MESSAGE_LENGTH));
        state.setResponse(state.response().withErrMsg(spaces(ERR_MSG_LENGTH)));

        // L90  IF EIBCALEN = 0. An absent payload and a payload with no communication area are the same
        // condition: nothing was passed.
        if (request == null || request.navigationContext() == null) {
            // L91  MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
            state.setCommarea(state.commarea()
                    .withToProgram(codec.movePicX(LIT_SIGNON_PGM, TO_PROGRAM_LENGTH)));
            // L92  PERFORM RETURN-TO-PREV-SCREEN
            returnToPrevScreen(state);
            return returnToCics(state);
        }

        // L94  MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA
        state.setCommarea(request.navigationContext());

        // L95  IF NOT CDEMO-PGM-REENTER - true for context 0 and for any value that is not 1.
        if (!state.commarea().isReenter()) {
            firstEntry(state, usrSelected);
        } else {
            // L107  PERFORM RECEIVE-USRDEL-SCREEN
            receiveUsrdelScreen(state, request);
            // L108-130  EVALUATE EIBAID
            dispatchAid(state, eibAid);
        }
        return returnToCics(state);
    }

    /**
     * The {@code IF NOT CDEMO-PGM-REENTER} arm - lines 95 to 105.
     *
     * <p>Four statements and one guarded fetch, in source order. The fetch is guarded because line 99
     * guards it: {@code IF CDEMO-CU03-USR-SELECTED NOT = SPACES AND LOW-VALUES}, which is the same
     * predicate lines 145 and 177 spell the other way round as {@code = SPACES OR LOW-VALUES}, negated.
     *
     * <p>Note that the send at line 105 is unconditional and sits <em>outside</em> the guard, so a fetch
     * that has already sent - once for the lookup's own arm, and again from line 168 when it succeeded -
     * sends a third time here. The screen is re-derived on each send, so the outcome is the last one; the
     * count is on {@link ProgramState#sendCount()}.
     *
     * @param state       the per-request working storage
     * @param usrSelected {@code CDEMO-CU03-USR-SELECTED}, which may be {@code null} or blank
     */
    private void firstEntry(ProgramState state, String usrSelected) {
        // L96  SET CDEMO-PGM-REENTER TO TRUE
        state.setCommarea(state.commarea().withPgmReenter());

        // L97  MOVE LOW-VALUES TO COUSR3AO - the cleared screen, which a PIC X field then holds as
        // spaces; UserDeleteResponse.empty() is that shape and says so.
        state.setResponse(UserDeleteResponse.empty());

        // L98  MOVE -1 TO USRIDINL OF COUSR3AI
        state.moveMinusOneTo(CursorField.USRIDINL);

        // L99-100  IF CDEMO-CU03-USR-SELECTED NOT = SPACES AND LOW-VALUES
        if (!isSpacesOrLowValues(usrSelected)) {
            // L101-102  MOVE CDEMO-CU03-USR-SELECTED TO USRIDINI OF COUSR3AI
            state.setResponse(state.response()
                    .withUsrIdIn(codec.movePicX(usrSelected, USR_ID_IN_LENGTH)));
            // L103  PERFORM PROCESS-ENTER-KEY
            processEnterKey(state);
        }

        // L105  PERFORM SEND-USRDEL-SCREEN - outside the guard, and so always performed.
        sendUsrdelScreen(state);
    }

    /**
     * {@code EVALUATE EIBAID} - lines 108 to 130.
     *
     * <p>The five keys the program handles and its {@code WHEN OTHER}, in source order. A COBOL
     * {@code EVALUATE} takes the first matching arm and the arms here test distinct constants, so an
     * ordered {@code if} chain preserves both the outcome and the order in which a reader meets them.
     *
     * <p>Each test is {@link PfKeyResolver}'s byte equality against one constant, which is what
     * {@code EIBAID = DFHPF5} is. Nothing is folded on the way in, so a {@code PF17} press does not reach
     * the {@code PF5} arm and delete a record; it reaches {@code WHEN OTHER}, where the source puts it.
     *
     * <p>{@code WHEN OTHER} is reached three ways and they are indistinguishable there, exactly as on a
     * terminal: a key {@code CSSTRPFY} names but this program has no arm for - PF1, PF7, CLEAR and so on -
     * a key {@code CSSTRPFY} does not name at all, such as PA3, and {@code DFHNULL}, which is what the
     * payload's {@code aid} member states when it carries no identifiable byte.
     *
     * @param state  the per-request working storage
     * @param eibAid the raw attention-identifier byte
     */
    private void dispatchAid(ProgramState state, byte eibAid) {
        // L109-110  WHEN DFHENTER
        if (PfKeyResolver.isEnter(eibAid)) {
            processEnterKey(state);
            return;
        }

        // L111-118  WHEN DFHPF3. Resolve a return target, then transfer - and nothing else. There is
        // deliberately NO delete here: COUSR02C performs UPDATE-USER-INFO on its own PF3 arm at
        // lines 111-119, and this program does not. The asymmetry is the source's, and it stands.
        if (PfKeyResolver.isPf3(eibAid)) {
            // L112  IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES
            if (isSpacesOrLowValues(state.commarea().fromProgram())) {
                // L113  MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
                state.setCommarea(state.commarea()
                        .withToProgram(codec.movePicX(LIT_ADMIN_PGM, TO_PROGRAM_LENGTH)));
            } else {
                // L115-116  MOVE CDEMO-FROM-PROGRAM TO CDEMO-TO-PROGRAM
                state.setCommarea(state.commarea()
                        .withToProgram(codec.movePicX(state.commarea().fromProgram(),
                                TO_PROGRAM_LENGTH)));
            }
            // L118  PERFORM RETURN-TO-PREV-SCREEN
            returnToPrevScreen(state);
            return;
        }

        // L119-120  WHEN DFHPF4
        if (PfKeyResolver.isPf4(eibAid)) {
            clearCurrentScreen(state);
            return;
        }

        // L121-122  WHEN DFHPF5 - the confirm key, and the only route to the delete.
        if (PfKeyResolver.isPf5(eibAid)) {
            deleteUserInfo(state);
            return;
        }

        // L123-125  WHEN DFHPF12
        if (PfKeyResolver.isPf12(eibAid)) {
            // L124  MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
            state.setCommarea(state.commarea()
                    .withToProgram(codec.movePicX(LIT_ADMIN_PGM, TO_PROGRAM_LENGTH)));
            // L125  PERFORM RETURN-TO-PREV-SCREEN
            returnToPrevScreen(state);
            return;
        }

        // L126-129  WHEN OTHER
        invalidKey(state);
    }

    /**
     * The {@code WHEN OTHER} arm of {@code EVALUATE EIBAID} - lines 126 to 129.
     *
     * <p>{@code CCDA-MSG-INVALID-KEY} is {@code PIC X(50)} [{@code app/cpy/CSMSG01Y.cpy:20-21}] and
     * {@code WS-MESSAGE} is {@code PIC X(80)}, so the move pads on the right; the codec is used rather
     * than a concatenation so the receiving width is named at the call site.
     *
     * @param state the per-request working storage
     */
    private void invalidKey(ProgramState state) {
        // L127  MOVE 'Y' TO WS-ERR-FLG
        state.setErrFlagOn();
        // L128  MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE
        state.setMessage(codec.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH));
        // L129  PERFORM SEND-USRDEL-SCREEN
        sendUsrdelScreen(state);
    }

    // =================================================================================================
    // PROCESS-ENTER-KEY - app/cbl/COUSR03C.cbl:142-169.
    // =================================================================================================

    /**
     * {@code PROCESS-ENTER-KEY} - the fetch that paints the record for confirmation, lines 142 to 169.
     *
     * <p>Three statements, and the shape of the last two is what makes this paragraph behave differently
     * from {@link #deleteUserInfo(ProgramState)}:
     *
     * <ol>
     *   <li><strong>Lines 144 to 154</strong> - {@code EVALUATE TRUE} over one field and one field only.
     *       {@code USRIDIN} is the sole validated input on this screen; {@code FNAME}, {@code LNAME} and
     *       {@code USRTYPE} are {@code ASKIP} and are never validated because they are never keyed. Both
     *       arms put the cursor on the user id - line 149 on the failure and line 152 on the way
     *       through - so the field the operator must fix is where the cursor lands either way.</li>
     *   <li><strong>Lines 156 to 162</strong> - the first {@code IF NOT ERR-FLG-ON}: blank the three
     *       display-only fields, move the screen's id into {@code SEC-USR-ID}, and read.</li>
     *   <li><strong>Lines 164 to 169</strong> - a <em>second, separate</em> {@code IF NOT ERR-FLG-ON}:
     *       paint the three fields from the record just read and send again. Because it is a separate
     *       statement, a failed read stops here. That is the difference from
     *       {@link #deleteUserInfo(ProgramState)}, which wraps its read and its delete in one guard and
     *       therefore does not stop.</li>
     * </ol>
     *
     * <p>On the successful path the screen is sent <strong>twice</strong>: once by the read's own
     * {@code NORMAL} arm at line 286, carrying {@value #MSG_PRESS_PF5}, and once at line 168 with the
     * names painted. The message is not cleared in between, so the second send carries the same prompt -
     * and the neutral colour byte the read set - alongside the record's details.
     *
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void processEnterKey(ProgramState state) {
        requireState(state);

        // L144-154  EVALUATE TRUE
        if (isSpacesOrLowValues(state.response().usrIdIn())) {
            // L145  WHEN USRIDINI OF COUSR3AI = SPACES OR LOW-VALUES
            state.setErrFlagOn();                                                            // L146
            state.setMessage(codec.movePicX(MSG_USER_ID_EMPTY, WS_MESSAGE_LENGTH));          // L147-148
            state.moveMinusOneTo(CursorField.USRIDINL);                                      // L149
            sendUsrdelScreen(state);                                                         // L150
        } else {
            // L151-153  WHEN OTHER: the cursor still moves, and then CONTINUE - which does nothing, and
            // is written in the source, and so is represented by this arm having no further statement.
            state.moveMinusOneTo(CursorField.USRIDINL);                                       // L152
        }

        // L156  IF NOT ERR-FLG-ON
        if (!state.isErrFlagOn()) {
            // L157-159  MOVE SPACES TO FNAMEI, LNAMEI, USRTYPEI - the previous record's details are
            // cleared before the read, so a failed lookup cannot leave the last user's name on screen.
            state.setResponse(state.response()
                    .withFName(spaces(F_NAME_LENGTH))
                    .withLName(spaces(L_NAME_LENGTH))
                    .withUsrType(spaces(USR_TYPE_LENGTH)));
            // L160  MOVE USRIDINI OF COUSR3AI TO SEC-USR-ID - X(8) into X(8), width preserving, and
            // still routed through the codec so the receiver's declared width is named at the call site.
            state.moveToSecUsrId(codec.movePicX(state.response().usrIdIn(), SEC_USR_ID_LENGTH));
            // L161  PERFORM READ-USER-SEC-FILE
            readUserSecFile(state);
        }

        // L164  IF NOT ERR-FLG-ON - a second statement, not an else-arm of the first.
        if (!state.isErrFlagOn()) {
            SecUserRecord record = state.secUserData();
            state.setResponse(state.response()
                    // L165  MOVE SEC-USR-FNAME TO FNAMEI OF COUSR3AI
                    .withFName(codec.movePicX(record.secUsrFname(), F_NAME_LENGTH))
                    // L166  MOVE SEC-USR-LNAME TO LNAMEI OF COUSR3AI
                    .withLName(codec.movePicX(record.secUsrLname(), L_NAME_LENGTH))
                    // L167  MOVE SEC-USR-TYPE TO USRTYPEI OF COUSR3AI
                    .withUsrType(codec.movePicX(record.secUsrType(), USR_TYPE_LENGTH)));
            // L168  PERFORM SEND-USRDEL-SCREEN
            sendUsrdelScreen(state);
        }

        // SEC-USR-PWD is deliberately not read, not painted and not logged. This screen declares no
        // password field at all - which is why it carries eleven named fields where COUSR02 carries
        // twelve - and adding one for symmetry would disclose a credential the operator never asked for.
    }

    // =================================================================================================
    // DELETE-USER-INFO - app/cbl/COUSR03C.cbl:174-192.
    // =================================================================================================

    /**
     * {@code DELETE-USER-INFO} - the PF5 confirm path, lines 174 to 192.
     *
     * <p>The validation is <strong>identical</strong> to {@link #processEnterKey(ProgramState)}: the same
     * single field, the same message, the same cursor. What differs is the guard that follows it.
     *
     * <p><strong>Lines 188 to 192 wrap the read and the delete in one {@code IF NOT ERR-FLG-ON} and place
     * no guard between them.</strong> The consequence is observable and is preserved exactly:
     *
     * <ul>
     *   <li>The read is a <em>read for update</em>. It is what takes the lock the keyless delete then acts
     *       on, so the delete depends on it - and the order read-then-delete is not interchangeable.</li>
     *   <li>When the read succeeds, its own {@code NORMAL} arm has already sent the screen carrying
     *       {@value #MSG_PRESS_PF5} before the delete replaces that message. The prompt is therefore
     *       produced on the PF5 path too, immediately before the deletion; the sequence is preserved
     *       rather than suppressed.</li>
     *   <li>When the read <em>fails</em>, the error flag is raised and the screen is sent - and then the
     *       delete is issued anyway. With no record held the command names none, CICS answers
     *       {@code INVREQ}, and the {@code WHEN OTHER} arm overwrites the message with
     *       {@value #MSG_UNABLE_TO_UPDATE_USER} and moves the cursor to the first name. A translation
     *       that inserted the missing guard would produce {@value #MSG_USER_ID_NOT_FOUND} instead, and
     *       would be wrong.</li>
     * </ul>
     *
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void deleteUserInfo(ProgramState state) {
        requireState(state);

        // L176-186  EVALUATE TRUE - the same single-field check as PROCESS-ENTER-KEY, lines 144-154.
        if (isSpacesOrLowValues(state.response().usrIdIn())) {
            // L177  WHEN USRIDINI OF COUSR3AI = SPACES OR LOW-VALUES
            state.setErrFlagOn();                                                            // L178
            state.setMessage(codec.movePicX(MSG_USER_ID_EMPTY, WS_MESSAGE_LENGTH));          // L179-180
            state.moveMinusOneTo(CursorField.USRIDINL);                                      // L181
            sendUsrdelScreen(state);                                                         // L182
        } else {
            // L183-185  WHEN OTHER: move the cursor, then CONTINUE.
            state.moveMinusOneTo(CursorField.USRIDINL);                                       // L184
        }

        // L188  IF NOT ERR-FLG-ON - ONE statement covering all three of the following lines.
        if (!state.isErrFlagOn()) {
            // L189  MOVE USRIDINI OF COUSR3AI TO SEC-USR-ID
            state.moveToSecUsrId(codec.movePicX(state.response().usrIdIn(), SEC_USR_ID_LENGTH));
            // L190  PERFORM READ-USER-SEC-FILE - the locking read that the delete depends on.
            readUserSecFile(state);
            // L191  PERFORM DELETE-USER-SEC-FILE - unconditional. There is NO second IF here.
            deleteUserSecFile(state);
        }
    }

    // =================================================================================================
    // READ-USER-SEC-FILE - app/cbl/COUSR03C.cbl:267-300.
    // =================================================================================================

    /**
     * {@code READ-USER-SEC-FILE} - the locking read, lines 267 to 300.
     *
     * <pre>{@code
     *  EXEC CICS READ
     *       DATASET   (WS-USRSEC-FILE)
     *       INTO      (SEC-USER-DATA)
     *       LENGTH    (LENGTH OF SEC-USER-DATA)
     *       RIDFLD    (SEC-USR-ID)
     *       KEYLENGTH (LENGTH OF SEC-USR-ID)
     *       UPDATE
     *       RESP      (WS-RESP-CD)
     *       RESP2     (WS-REAS-CD)
     *  END-EXEC.
     * }</pre>
     *
     * <p>{@code UPDATE} is the operative word, and it is why this is
     * {@link SecUserRepository#readForUpdate(String)} and not {@link SecUserRepository#read(String)}:
     * the record must still be held when the delete at line 307 runs. The dataset is named only inside
     * the repository - {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} at line 39 becomes
     * {@link SecUserRepository#CICS_FILE_NAME_IMAGE} - so no dataset literal appears here.
     *
     * <p>The three {@code RESP} arms, lines 280 to 300:
     *
     * <ul>
     *   <li>{@code NORMAL} - line 282 is a bare {@code CONTINUE} followed by three more statements,
     *       which is redundant and harmless; the message becomes {@value #MSG_PRESS_PF5}, the message
     *       line's colour byte becomes {@link BmsAttributes#DFHNEUTR} at line 285, and the screen is
     *       sent. The error flag is <strong>not</strong> raised, which is what lets
     *       {@code PROCESS-ENTER-KEY} continue into its second guard.</li>
     *   <li>{@code NOTFND} - {@value #MSG_USER_ID_NOT_FOUND}, cursor on the <em>user id</em>.</li>
     *   <li>{@code WHEN OTHER} - a live {@code DISPLAY} of both codes at line 294,
     *       {@value #MSG_UNABLE_TO_LOOKUP_USER}, and cursor on the <em>first name</em> at line 298. The
     *       cursor target differs from the not-found arm; that is what the source does.</li>
     * </ul>
     *
     * <p>{@code DFHRESP(ENDFILE)} and every other response the source does not enumerate land on
     * {@code WHEN OTHER}, because {@code EVALUATE} tests {@code NORMAL} and {@code NOTFND} and nothing
     * else.
     *
     * @param state the per-request working storage; {@code SEC-USR-ID} must already hold the key
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void readUserSecFile(ProgramState state) {
        requireState(state);

        // L269-278  the command. RIDFLD(SEC-USR-ID) and KEYLENGTH(LENGTH OF SEC-USR-ID) are the
        // eight-character key working storage already holds.
        ReadResult read = secUserRepository.readForUpdate(state.secUserData().secUsrId());

        // L271  INTO(SEC-USER-DATA) - the record area is filled only when the read succeeded; a failed
        // read leaves it holding what it held, which is why SEC-USR-ID survives to build the message.
        read.record().ifPresent(state::setSecUserData);

        // L275  UPDATE - the task now holds the record, and that hold is the delete's only subject.
        state.holdRecord(read.hold());

        // L276-277  RESP(WS-RESP-CD) RESP2(WS-REAS-CD). A deployment whose adapter reports genuine CICS
        // values is believed; where it reports none, the response is the one this outcome corresponds to,
        // so the DISPLAY at line 294 shows a CICS response rather than a fabricated number.
        state.setRespCd(read.cicsResp()
                .orElse(classifiedResp(read.isFound(), read.isNotFound())));
        state.setReasCd(read.cicsResp2());

        // L280-300  EVALUATE WS-RESP-CD
        if (read.isFound()) {
            // L281-286  WHEN DFHRESP(NORMAL)
            state.setMessage(codec.movePicX(MSG_PRESS_PF5, WS_MESSAGE_LENGTH));              // L283-284
            state.setErrMsgColour(BmsAttributes.DFHNEUTR);                                   // L285
            sendUsrdelScreen(state);                                                         // L286
        } else if (read.isNotFound()) {
            // L287-292  WHEN DFHRESP(NOTFND)
            state.setErrFlagOn();                                                            // L288
            state.setMessage(codec.movePicX(MSG_USER_ID_NOT_FOUND, WS_MESSAGE_LENGTH));      // L289-290
            state.moveMinusOneTo(CursorField.USRIDINL);                                      // L291
            sendUsrdelScreen(state);                                                         // L292
        } else {
            // L293-299  WHEN OTHER
            display(state, respDisplayLine(state));                                          // L294
            state.setErrFlagOn();                                                            // L295
            state.setMessage(codec.movePicX(MSG_UNABLE_TO_LOOKUP_USER, WS_MESSAGE_LENGTH));  // L296-297
            state.moveMinusOneTo(CursorField.FNAMEL);                                        // L298
            sendUsrdelScreen(state);                                                         // L299
        }
    }

    // =================================================================================================
    // DELETE-USER-SEC-FILE - app/cbl/COUSR03C.cbl:305-336. The critical contract of this program.
    // =================================================================================================

    /**
     * {@code DELETE-USER-SEC-FILE} - the keyless delete, lines 305 to 336.
     *
     * <pre>{@code
     *  EXEC CICS DELETE
     *       DATASET   (WS-USRSEC-FILE)
     *       RESP      (WS-RESP-CD)
     *       RESP2     (WS-REAS-CD)
     *  END-EXEC.
     * }</pre>
     *
     * <p><strong>A dataset, a response and a reason code. That is the entire command.</strong> There is
     * no {@code RIDFLD}, so it does not delete a record identified by key - it deletes the record the
     * task is <em>holding</em> from the {@code READ ... UPDATE} at line 269. The translation is
     * {@link HeldRecord#deleteHeld()}, which is reached from the hold itself and likewise takes no
     * argument, so the precondition is visible in the call rather than only in this comment. A
     * delete-by-key call would be a different operation with different semantics, and no program in this
     * application performs one.
     *
     * <p>When the task holds nothing - which happens whenever the preceding read failed, because
     * {@link #deleteUserInfo(ProgramState)} places no guard between the two - the command can name no
     * record and CICS answers {@code INVREQ}. That is not the not-found condition: it is the
     * {@code WHEN OTHER} arm, and it is why a PF5 against an unknown user ends up reporting
     * {@value #MSG_UNABLE_TO_UPDATE_USER} rather than {@value #MSG_USER_ID_NOT_FOUND}.
     *
     * <p>The three {@code RESP} arms are {@link #deleteNormal(ProgramState)},
     * {@link #deleteNotFound(ProgramState)} and {@link #deleteWhenOther(ProgramState)}, lines 314 to 335.
     * {@link WriteResult#isDuplicate()} is not one of them: {@code EVALUATE} tests {@code NORMAL} and
     * {@code NOTFND} and nothing else, so a duplicate response lands on {@code WHEN OTHER} exactly as any
     * other unenumerated one does.
     *
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void deleteUserSecFile(ProgramState state) {
        requireState(state);

        Optional<HeldRecord> held = state.heldRecord();
        if (held.isEmpty()) {
            // L307-311 issued with nothing held. CICS raises INVREQ for a DELETE that names neither a
            // RIDFLD nor a held record, and INVREQ is neither NORMAL nor NOTFND.
            state.setRespCd(FileStatus.INVREQ);
            state.setReasCd(FileStatus.NO_REASON_CODE);
            deleteWhenOther(state);
            return;
        }

        // L307-311  EXEC CICS DELETE DATASET(WS-USRSEC-FILE) - no key, and none passed.
        WriteResult deleted = held.get().deleteHeld();

        // L309-310  RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
        state.setRespCd(deleted.cicsResp()
                .orElse(classifiedResp(deleted.isWritten(), deleted.isNotFound())));
        state.setReasCd(deleted.cicsResp2());

        // L313-336  EVALUATE WS-RESP-CD
        if (deleted.isWritten()) {
            // The record is gone, so the task holds nothing further. Releasing the hold keeps the state
            // truthful for anything that inspects it afterwards.
            state.releaseHold();
            deleteNormal(state);
        } else if (deleted.isNotFound()) {
            deleteNotFound(state);
        } else {
            deleteWhenOther(state);
        }
    }

    /**
     * {@code WHEN DFHRESP(NORMAL)} of the delete - lines 314 to 322.
     *
     * <p>Five statements whose <strong>order is load-bearing</strong>:
     *
     * <ol>
     *   <li>Line 315 {@code PERFORM INITIALIZE-ALL-FIELDS} - the screen is emptied. Note what this does
     *       <em>not</em> touch: {@code SEC-USER-DATA} is working storage and is left alone, which is the
     *       only reason step 4 can still name the user.</li>
     *   <li>Line 316 {@code MOVE SPACES TO WS-MESSAGE} - already done by line 356 inside
     *       {@code INITIALIZE-ALL-FIELDS}, and done again here because the source does it again.</li>
     *   <li>Line 317 {@code MOVE DFHGREEN TO ERRMSGC} - the message line turns green. This is the
     *       {@code xxxC} colour byte, so it is carried as {@link ProgramState#errMsgColour()} metadata and
     *       never as a payload member.</li>
     *   <li>Lines 318 to 321 the {@code STRING}, described below.</li>
     *   <li>Line 322 {@code PERFORM SEND-USRDEL-SCREEN}.</li>
     * </ol>
     *
     * <h4>The {@code STRING} statement</h4>
     * <pre>{@code
     *  STRING 'User '     DELIMITED BY SIZE
     *         SEC-USR-ID  DELIMITED BY SPACE
     *         ' has been deleted ...' DELIMITED BY SIZE
     *    INTO WS-MESSAGE
     * }</pre>
     *
     * <p>The middle operand is delimited <strong>by space, not by size</strong>, so it contributes only
     * the characters before its first space: {@code "USER0001"} contributes all eight, {@code "AB      "}
     * contributes two, and an id that begins with a space contributes none. A plain Java concatenation of
     * the raw eight-byte field would leave the trailing spaces embedded in the middle of the sentence and
     * diverge on every id shorter than eight. {@link #delimitedBySpace(String)} performs that trim, and
     * the three resulting operands are then concatenated at their full contributed widths.
     *
     * <p>{@code STRING} transfers into the receiver from its leftmost position and does <em>not</em>
     * space-fill what it did not reach - but line 316 has just blanked all eighty characters, so padding
     * the composition out to {@value #WS_MESSAGE_LENGTH} with spaces produces exactly the same eighty
     * characters. Should the composition ever exceed eighty, the codec's right truncation is also what
     * {@code STRING} does, since the statement declares no {@code ON OVERFLOW}.
     *
     * @param state the per-request working storage
     */
    private void deleteNormal(ProgramState state) {
        // L315  PERFORM INITIALIZE-ALL-FIELDS
        initializeAllFields(state);

        // L316  MOVE SPACES TO WS-MESSAGE
        state.setMessage(spaces(WS_MESSAGE_LENGTH));

        // L317  MOVE DFHGREEN TO ERRMSGC OF COUSR3AO
        state.setErrMsgColour(BmsAttributes.DFHGREEN);

        // L318-321  STRING ... INTO WS-MESSAGE. SEC-USR-ID survived INITIALIZE-ALL-FIELDS because that
        // paragraph clears screen fields and WS-MESSAGE only.
        state.setMessage(codec.movePicX(
                codec.concatenateDelimitedBySize(MSG_USER_PREFIX,
                        delimitedBySpace(state.secUserData().secUsrId()),
                        MSG_HAS_BEEN_DELETED_SUFFIX),
                WS_MESSAGE_LENGTH));

        // L322  PERFORM SEND-USRDEL-SCREEN
        sendUsrdelScreen(state);
    }

    /**
     * {@code WHEN DFHRESP(NOTFND)} of the delete - lines 323 to 328.
     *
     * <p>Reachable when the record is gone between the read and the delete. The message and the cursor
     * target are the same as the lookup's not-found arm, and the colour byte is left as it stands.
     *
     * @param state the per-request working storage
     */
    private void deleteNotFound(ProgramState state) {
        state.setErrFlagOn();                                                                // L324
        state.setMessage(codec.movePicX(MSG_USER_ID_NOT_FOUND, WS_MESSAGE_LENGTH));          // L325-326
        state.moveMinusOneTo(CursorField.USRIDINL);                                          // L327
        sendUsrdelScreen(state);                                                             // L328
    }

    /**
     * {@code WHEN OTHER} of the delete - lines 329 to 335.
     *
     * <p>Four things happen, and two of them are easy to get wrong:
     *
     * <ul>
     *   <li>The {@code DISPLAY} at line 330 is <strong>live</strong>. The equivalent statement in
     *       {@code COUSR01C} is commented out; this one is not, so it is emitted.</li>
     *   <li>The message is {@value #MSG_UNABLE_TO_UPDATE_USER} - the word "Update", on the delete path.
     *       See {@link #MSG_UNABLE_TO_UPDATE_USER}: it is a defect in the legacy source and it is
     *       preserved deliberately and exactly.</li>
     *   <li>The cursor goes to the <strong>first name</strong> at line 334, not to the user id. That
     *       field is {@code ASKIP} and cannot be typed into, which makes the choice odd - and it is the
     *       source's choice.</li>
     *   <li>The error flag is raised at line 331.</li>
     * </ul>
     *
     * @param state the per-request working storage
     */
    private void deleteWhenOther(ProgramState state) {
        display(state, respDisplayLine(state));                                              // L330
        state.setErrFlagOn();                                                                // L331
        // L332-333  MOVE 'Unable to Update User...' TO WS-MESSAGE. PRESERVED SOURCE DEFECT - the word
        // "Update" belongs to COUSR02C's rewrite path and was copied here unchanged. Do not "fix" it.
        state.setMessage(codec.movePicX(MSG_UNABLE_TO_UPDATE_USER, WS_MESSAGE_LENGTH));
        state.moveMinusOneTo(CursorField.FNAMEL);                                            // L334
        sendUsrdelScreen(state);                                                             // L335
    }

    // =================================================================================================
    // RETURN-TO-PREV-SCREEN - app/cbl/COUSR03C.cbl:197-208.
    // =================================================================================================

    /**
     * {@code RETURN-TO-PREV-SCREEN} - the transfer of control, lines 197 to 208.
     *
     * <p>Four moves and an {@code XCTL}. The guard at line 199 is a safety net: whichever caller reached
     * this paragraph has normally already named a target, and a blank or low-values one falls back to
     * {@value #LIT_SIGNON_PGM}. Lines 202 to 204 then stamp the outgoing communication area with where
     * control is coming <em>from</em>, and reset the context so the program that receives it is entered
     * fresh rather than as a re-entry.
     *
     * <p>{@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)} at lines 205 to 208
     * becomes a response field: the server names the target in {@link UserDeleteResponse#nextProgram()}
     * and the client issues the follow-up call. There is no server-side forward, no redirect chain and no
     * session affinity.
     *
     * <p>The next mapset and next map are left as they stand, which is blank. {@code COUSR03C} sets
     * neither {@code CDEMO-LAST-MAP} nor {@code CDEMO-LAST-MAPSET} anywhere in its 359 lines - the
     * program being transferred to owns its own map - and naming one here would be an invention.
     *
     * <p>Because control leaves the program, the {@code EXEC CICS RETURN} at lines 134 to 137 is not
     * reached on this path. {@link #returnToCics(ProgramState)} honours that.
     *
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void returnToPrevScreen(ProgramState state) {
        requireState(state);

        // L199-201  IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES → MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
        if (isSpacesOrLowValues(state.commarea().toProgram())) {
            state.setCommarea(state.commarea()
                    .withToProgram(codec.movePicX(LIT_SIGNON_PGM, TO_PROGRAM_LENGTH)));
        }

        state.setCommarea(state.commarea()
                // L202  MOVE WS-TRANID TO CDEMO-FROM-TRANID
                .withFromTranid(codec.movePicX(WS_TRANID, FROM_TRANID_LENGTH))
                // L203  MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
                .withFromProgram(codec.movePicX(WS_PGMNAME, FROM_PROGRAM_LENGTH))
                // L204  MOVE ZEROS TO CDEMO-PGM-CONTEXT - which is condition name CDEMO-PGM-ENTER.
                .withPgmEnter());

        // L205-208  EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)
        state.setResponse(state.response()
                .withNextProgram(state.commarea().toProgram())
                .withNavigationContext(state.commarea())
                // L207  COMMAREA(CARDDEMO-COMMAREA) - which is the 160 bytes plus these 34.
                .withCu03Info(state.cu03Info()));
        state.markTransferred();
    }

    // =================================================================================================
    // SEND-USRDEL-SCREEN and RECEIVE-USRDEL-SCREEN - app/cbl/COUSR03C.cbl:213-238.
    // =================================================================================================

    /**
     * {@code SEND-USRDEL-SCREEN} - lines 213 to 225.
     *
     * <pre>{@code
     *  PERFORM POPULATE-HEADER-INFO
     *  MOVE WS-MESSAGE TO ERRMSGO OF COUSR3AO
     *  EXEC CICS SEND MAP('COUSR3A') MAPSET('COUSR03') FROM(COUSR3AO) ERASE CURSOR END-EXEC.
     * }</pre>
     *
     * <p>Three things to note:
     *
     * <ul>
     *   <li><strong>The header is refreshed on every send</strong>, so the date and time on the screen are
     *       those of the moment it was painted, not of the moment the task started.</li>
     *   <li><strong>{@code WS-MESSAGE} is {@code PIC X(80)} and {@code ERRMSGO} is {@code PIC X(78)}</strong>,
     *       so line 217 is a truncating move. It goes through
     *       {@link FixedWidthCodec#movePicX(String, int)} so the direction - COBOL truncates a
     *       {@code PIC X} receiver on the <em>right</em> - is chosen deliberately and is visible here
     *       rather than left to a plain assignment that would neither pad nor truncate.</li>
     *   <li><strong>This paragraph is not terminal.</strong> It returns to its caller, which is why the
     *       program can send more than once in a single task. {@code ERASE} means each send replaces the
     *       whole screen, so the last one is what the terminal shows; {@code CURSOR} means the send
     *       honours the most recent {@code MOVE -1 TO <field>L}, which is
     *       {@link ProgramState#cursorField()}.</li>
     * </ul>
     *
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void sendUsrdelScreen(ProgramState state) {
        requireState(state);

        // L215  PERFORM POPULATE-HEADER-INFO
        populateHeaderInfo(state);

        // L217  MOVE WS-MESSAGE TO ERRMSGO OF COUSR3AO - eighty into seventy-eight.
        state.setResponse(state.response().withErrMsg(codec.movePicX(state.message(), ERR_MSG_LENGTH)));

        // L219-225  EXEC CICS SEND MAP ... ERASE CURSOR
        state.recordSend();
    }

    /**
     * {@code RECEIVE-USRDEL-SCREEN} - lines 230 to 238.
     *
     * <pre>{@code
     *  EXEC CICS RECEIVE MAP('COUSR3A') MAPSET('COUSR03') INTO(COUSR3AI)
     *            RESP(WS-RESP-CD) RESP2(WS-REAS-CD) END-EXEC.
     * }</pre>
     *
     * <p>In a stateless projection the request payload <em>is</em> the received map, so this paragraph
     * copies the eleven inbound {@code xxxI} items into the one screen buffer. All eleven are copied, not
     * just the one unprotected field, because every one of them is declared {@code FSET} on the map and
     * is therefore transmitted; {@code POPULATE-HEADER-INFO} overwrites the six header fields again on
     * the way out.
     *
     * <p>A member the caller omitted arrives as {@code null} and becomes spaces - the state a symbolic-map
     * field holds when the terminal transmitted nothing for it. That is what lets a caller send only the
     * user id and have the blank-field check at line 145 behave exactly as it does on a terminal.
     *
     * <p>{@code COUSR3AO REDEFINES COUSR3AI}: input and output are one buffer, not two, which is why the
     * received values are written into the same {@link UserDeleteResponse} the sends later render. The
     * captured {@code RESP} and {@code RESP2} are recorded because the command captures them, and the
     * source never tests them - there is no {@code EVALUATE} after this command.
     *
     * @param state   the per-request working storage
     * @param request the inbound screen
     * @throws NullPointerException if {@code state} or {@code request} is {@code null}
     */
    public void receiveUsrdelScreen(ProgramState state, UserDeleteRequest request) {
        requireState(state);
        Objects.requireNonNull(request, "An inbound screen is required: RECEIVE MAP INTO(COUSR3AI) at "
                + "app/cbl/COUSR03C.cbl:232-238 is what fills the map, and the request payload is that map");

        state.setResponse(state.response()
                .withTrnName(receivedField(request.trnName(), TRN_NAME_LENGTH))
                .withTitle01(receivedField(request.title01(), TITLE_LENGTH))
                .withCurDate(receivedField(request.curDate(), CUR_DATE_LENGTH))
                .withPgmName(receivedField(request.pgmName(), PGM_NAME_LENGTH))
                .withTitle02(receivedField(request.title02(), TITLE_LENGTH))
                .withCurTime(receivedField(request.curTime(), CUR_TIME_LENGTH))
                .withUsrIdIn(receivedField(request.usrIdIn(), USR_ID_IN_LENGTH))
                .withFName(receivedField(request.fName(), F_NAME_LENGTH))
                .withLName(receivedField(request.lName(), L_NAME_LENGTH))
                .withUsrType(receivedField(request.usrType(), USR_TYPE_LENGTH))
                .withErrMsg(receivedField(request.errMsg(), ERR_MSG_LENGTH)));

        // L236-237  RESP(WS-RESP-CD) RESP2(WS-REAS-CD). A payload that arrived is a receive that
        // succeeded; the source captures the pair and then evaluates neither.
        state.setRespCd(FileStatus.NORMAL);
        state.setReasCd(FileStatus.NO_REASON_CODE);
    }

    // =================================================================================================
    // POPULATE-HEADER-INFO - app/cbl/COUSR03C.cbl:243-262.
    // =================================================================================================

    /**
     * {@code POPULATE-HEADER-INFO} - the six header fields, lines 243 to 262.
     *
     * <p>{@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at line 245 reads the clock, and lines 252
     * to 262 assemble {@code WS-CURDATE-MM-DD-YY} and {@code WS-CURTIME-HH-MM-SS} from its parts.
     * {@link DateHeader} owns that assembly - including taking the two low-order digits of the year for
     * the two-digit field, which line 254 does as {@code WS-CURDATE-YEAR(3:2)} - and it takes the
     * injected {@link Clock}, so nothing here calls a clock directly and every parity case is
     * reproducible.
     *
     * <p>The two titles are the copybook literals of {@code COTTL01Y}, carried verbatim by
     * {@link ScreenTitles} including the leading and trailing spaces that make each exactly forty
     * characters. {@code TRNNAMEO} and {@code PGMNAMEO} take {@value #WS_TRANID} and
     * {@value #WS_PGMNAME}.
     *
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void populateHeaderInfo(ProgramState state) {
        requireState(state);

        // L245  MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA
        DateHeader header = DateHeader.from(codec, clock);
        state.setDateHeader(header);

        state.setResponse(state.response()
                // L247  MOVE CCDA-TITLE01 TO TITLE01O OF COUSR3AO
                .withTitle01(codec.movePicX(ScreenTitles.CCDA_TITLE01, TITLE_LENGTH))
                // L248  MOVE CCDA-TITLE02 TO TITLE02O OF COUSR3AO
                .withTitle02(codec.movePicX(ScreenTitles.CCDA_TITLE02, TITLE_LENGTH))
                // L249  MOVE WS-TRANID TO TRNNAMEO OF COUSR3AO
                .withTrnName(codec.movePicX(WS_TRANID, TRN_NAME_LENGTH))
                // L250  MOVE WS-PGMNAME TO PGMNAMEO OF COUSR3AO
                .withPgmName(codec.movePicX(WS_PGMNAME, PGM_NAME_LENGTH))
                // L252-256  the MM/DD/YY assembly, then MOVE WS-CURDATE-MM-DD-YY TO CURDATEO
                .withCurDate(codec.movePicX(header.wsCurdateMmDdYy(), CUR_DATE_LENGTH))
                // L258-262  the HH:MM:SS assembly, then MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO
                .withCurTime(codec.movePicX(header.wsCurtimeHhMmSs(), CUR_TIME_LENGTH)));
    }

    // =================================================================================================
    // CLEAR-CURRENT-SCREEN and INITIALIZE-ALL-FIELDS - app/cbl/COUSR03C.cbl:341-356.
    // =================================================================================================

    /**
     * {@code CLEAR-CURRENT-SCREEN} - lines 341 to 344, the PF4 arm.
     *
     * <p>Two performs and nothing else: empty the fields, then send. No file is touched, so PF4 on a
     * screen showing a record simply forgets it.
     *
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void clearCurrentScreen(ProgramState state) {
        requireState(state);
        initializeAllFields(state);   // L343
        sendUsrdelScreen(state);      // L344
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS} - lines 349 to 356.
     *
     * <p>The cursor goes to the user id, and five items are blanked: {@code USRIDINI}, {@code FNAMEI},
     * {@code LNAMEI}, {@code USRTYPEI} and {@code WS-MESSAGE}.
     *
     * <p><strong>Note what is not in that list.</strong> There is no password item, because this screen
     * has no password field - the sole reason it carries eleven named fields where {@code COUSR02}
     * carries twelve. And {@code SEC-USER-DATA} is not in the list either: it is working storage, it is
     * left untouched, and that is precisely why {@link #deleteNormal(ProgramState)} can still name the
     * deleted user in its success message after calling this paragraph.
     *
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void initializeAllFields(ProgramState state) {
        requireState(state);

        // L351  MOVE -1 TO USRIDINL OF COUSR3AI
        state.moveMinusOneTo(CursorField.USRIDINL);

        // L352-355  MOVE SPACES TO USRIDINI, FNAMEI, LNAMEI, USRTYPEI
        state.setResponse(state.response()
                .withUsrIdIn(spaces(USR_ID_IN_LENGTH))
                .withFName(spaces(F_NAME_LENGTH))
                .withLName(spaces(L_NAME_LENGTH))
                .withUsrType(spaces(USR_TYPE_LENGTH)));

        // L356  ... WS-MESSAGE, the fifth receiver of the same MOVE SPACES.
        state.setMessage(spaces(WS_MESSAGE_LENGTH));
    }

    // =================================================================================================
    // EXEC CICS RETURN - app/cbl/COUSR03C.cbl:134-137.
    // =================================================================================================

    /**
     * {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} - lines 134 to 137.
     *
     * <p>Reached by every path that did not transfer. {@code TRANSID(}{@value #WS_TRANID}{@code )} means
     * the terminal's next input comes back to this same transaction, so the response names this program,
     * this mapset and this map as where the conversation continues - which is what the screen the send
     * just painted is showing.
     *
     * <p>On a path that executed {@code EXEC CICS XCTL} the task has already left the program, so this
     * statement is unreachable and the navigation triple stays as
     * {@link #returnToPrevScreen(ProgramState)} set it.
     *
     * @param state the per-request working storage
     * @return {@code state}, for the caller to return
     */
    private ProgramState returnToCics(ProgramState state) {
        if (state.isTransferred()) {
            return state;
        }
        state.setResponse(state.response()
                .withNavigationContext(state.commarea())
                // L135  COMMAREA(CARDDEMO-COMMAREA) - the 160 bytes plus these 34.
                .withCu03Info(state.cu03Info())
                .withNextProgram(codec.movePicX(WS_PGMNAME, TO_PROGRAM_LENGTH))
                .withNextMapset(codec.movePicX(WS_MAPSET, NEXT_MAPSET_LENGTH))
                .withNextMap(codec.movePicX(WS_MAP, NEXT_MAP_LENGTH)));
        state.markReturned();
        return state;
    }

    // =================================================================================================
    // COBOL statements and figurative constants rendered by hand. Each is package-visible or public so a
    // parity case asserts it directly rather than only through the paragraph that uses it (gate G51).
    // =================================================================================================

    /**
     * The COBOL test {@code <field> = SPACES OR LOW-VALUES}, and its inverse
     * {@code <field> NOT = SPACES AND LOW-VALUES}.
     *
     * <p>The program spells the same predicate both ways - line 99 negated, lines 112, 145, 177 and 199
     * plain. {@code = SPACES OR LOW-VALUES} is COBOL's abbreviated combined relation, and it expands to
     * {@code = SPACES OR <field> = LOW-VALUES}: <strong>two separate whole-item comparisons</strong>,
     * each against a figurative constant that fills the item's entire length.
     *
     * <p><strong>A mixture of the two therefore equals neither, and the condition is false.</strong> An
     * item holding, say, four spaces then four nulls is not all-spaces and is not all-low-values, so
     * COBOL treats it as a value the operator supplied rather than as an empty field. Reporting a mixed
     * image as blank would take the empty arm where the source takes the populated one.
     *
     * <p>An absent value is blank: a payload member the caller omitted is exactly a screen field the
     * terminal transmitted nothing for, which CICS leaves as {@code LOW-VALUES} - so it satisfies the
     * second comparison rather than being a third rule of its own.
     *
     * @param value the field's characters, or {@code null} when the member was absent
     * @return whether the field is entirely spaces or entirely low-values
     */
    static boolean isSpacesOrLowValues(String value) {
        if (value == null) {
            return true;
        }
        boolean allSpaces = true;
        boolean allLowValues = true;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != ' ') {
                allSpaces = false;
            }
            if (character != LOW_VALUE) {
                allLowValues = false;
            }
        }
        return allSpaces || allLowValues;
    }

    /**
     * A {@code STRING} operand qualified {@code DELIMITED BY SPACE} - line 319.
     *
     * <p>The operand contributes the characters before its first space and stops there. For
     * {@code SEC-USR-ID PIC X(08)} that means {@code "USER0001"} contributes all eight characters,
     * {@code "AB      "} contributes {@code "AB"}, and an identifier that begins with a space contributes
     * nothing at all. The delimiter itself is not transferred.
     *
     * <p>This is the difference between the source's message and a plain Java concatenation of the raw
     * field: without the trim, every identifier shorter than eight characters would embed its padding in
     * the middle of the sentence.
     *
     * @param value the sending item at its full declared width; must not be {@code null}
     * @return the characters before the first space, or the whole value when it contains none
     * @throws NullPointerException if {@code value} is {@code null}
     */
    static String delimitedBySpace(String value) {
        Objects.requireNonNull(value, "A sending item is required for STRING ... DELIMITED BY SPACE");
        int firstSpace = value.indexOf(' ');
        if (firstSpace < 0) {
            return value;
        }
        return value.substring(0, firstSpace);
    }

    /**
     * A run of {@code width} spaces - the value {@code MOVE SPACES} puts in a {@code PIC X} field.
     *
     * <p>This is COBOL's unconditional space fill, not the alphanumeric {@code MOVE} rule; the pad and
     * truncate rule lives in {@link FixedWidthCodec} alone and is never reimplemented here.
     *
     * @param width the receiver's declared width
     * @return exactly {@code width} spaces
     */
    private static String spaces(int width) {
        return SPACE.repeat(width);
    }

    /**
     * A received {@code xxxI} item, at its declared width.
     *
     * <p>An absent member becomes spaces rather than being rejected: on a terminal, a field that
     * transmitted nothing arrives blank, and that is the state the source's blank-field tests are written
     * against. A value of the wrong width is padded or right-truncated by the codec, which is what a
     * {@code MOVE} into the symbolic map's {@code PIC X} item does.
     *
     * @param value the inbound member, possibly {@code null}
     * @param width the item's declared width
     * @return exactly {@code width} characters
     */
    private String receivedField(String value, int width) {
        if (value == null) {
            return spaces(width);
        }
        return codec.movePicX(value, width);
    }

    /**
     * The CICS response an outcome corresponds to, for a deployment whose adapter reports none.
     *
     * <p>Used only to fill {@code WS-RESP-CD} for the {@code DISPLAY} at lines 294 and 330. It cannot
     * influence a branch: the arm was already chosen from the repository's own classification, and this
     * value is the response that classification means. {@link FileStatus#INVREQ} stands for the
     * unenumerated case - which is also literally what CICS raises for the keyless delete when the task
     * holds no record.
     *
     * @param normal   whether the operation succeeded
     * @param notFound whether the keyed record was absent
     * @return {@link FileStatus#NORMAL}, {@link FileStatus#NOTFND} or {@link FileStatus#INVREQ}
     */
    private static int classifiedResp(boolean normal, boolean notFound) {
        if (normal) {
            return FileStatus.NORMAL;
        }
        if (notFound) {
            return FileStatus.NOTFND;
        }
        return FileStatus.INVREQ;
    }

    /**
     * {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} - lines 294 and 330, rendered.
     *
     * <p>Both items are {@code PIC S9(09) COMP}, and a {@code DISPLAY} of a binary item converts it to
     * display form, so each renders as {@value #WS_RESP_CD_DIGITS} digit characters with no separator
     * between the literal and the number. Both are CICS response values and so are never negative.
     *
     * @param state the per-request working storage holding the two codes
     * @return the line the source displays
     */
    private String respDisplayLine(ProgramState state) {
        return DISPLAY_RESP_PREFIX + codec.movePic9(state.respCd(), WS_RESP_CD_DIGITS)
                + DISPLAY_REAS_PREFIX + codec.movePic9(state.reasCd(), WS_RESP_CD_DIGITS);
    }

    /**
     * A {@code DISPLAY} statement.
     *
     * <p>Recorded on the state so a parity case can assert the program's console output, and logged so a
     * running system shows it. Both statements in this program emit fixed text and two CICS response
     * codes and nothing else - no key, no record image, no personal name and certainly no password - so
     * there is nothing here a caller could use to forge a log record or to leak a payload value.
     *
     * @param state the per-request working storage
     * @param text  the text the source displays
     */
    private void display(ProgramState state, String text) {
        state.recordDisplay(text);
        LOG.info(text);
    }

    /**
     * Refuses an absent state.
     *
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    private static void requireState(ProgramState state) {
        Objects.requireNonNull(state, "A ProgramState is required: every WORKING-STORAGE item of "
                + "COUSR03C lives in it, so that two concurrent requests cannot see each other's screen");
    }

    // =================================================================================================
    // The cursor request. This is symbolic-map metadata and is deliberately NOT a payload member.
    // =================================================================================================

    /**
     * The two screen fields {@code COUSR03C} ever asks the cursor to land on.
     *
     * <p>{@code MOVE -1 TO <field>L} sets the symbolic map's length item to minus one, which is how a
     * program tells {@code SEND MAP ... CURSOR} where to put the cursor. The {@code xxxL} items are
     * <strong>metadata, not payload</strong> - the JSON projects only the {@code xxxI} and {@code xxxO}
     * items - so the request is reported on {@link ProgramState#cursorField()} instead.
     *
     * <p>Only two of the eleven fields are ever named, and which one it is carries information:
     *
     * <ul>
     *   <li>{@link #USRIDINL} - lines 98, 149, 152, 181, 184, 291, 327 and 351. The blank-field
     *       messages, the successful path, the not-found arms and the cleared screen all send the operator
     *       back to the one field on this map that can be typed into.</li>
     *   <li>{@link #FNAMEL} - lines 298 and 334, the two {@code WHEN OTHER} arms only. That field is
     *       {@code ASKIP} on the map and cannot be typed into at all, which makes the choice an oddity of
     *       the source; it is reproduced rather than corrected.</li>
     * </ul>
     */
    public enum CursorField {

        /** {@code USRIDINL} - {@code app/cpy-bms/COUSR03.CPY:55}, the user-id field's length item. */
        USRIDINL("USRIDINL"),

        /** {@code FNAMEL} - {@code app/cpy-bms/COUSR03.CPY:61}, the first-name field's length item. */
        FNAMEL("FNAMEL");

        /** The symbolic-map length item this constant names, spelled as the copybook spells it. */
        private final String lengthItem;

        CursorField(String lengthItem) {
            this.lengthItem = lengthItem;
        }

        /**
         * The {@code xxxL} item's name, for a diagnostic or an assertion that wants to name the field.
         *
         * @return the copybook name; never {@code null}
         */
        public String lengthItem() {
            return lengthItem;
        }

        /**
         * The {@code DFHMDF} label behind the length item - {@code USRIDINL} yields {@code USRIDIN}.
         *
         * <p>Not string surgery for convenience: a BMS symbolic map names each of a field's items by
         * suffixing the {@code DFHMDF} label, so {@code xxxL} is the label plus {@code 'L'} by the
         * generator's own rule. {@code app/cpy-bms/COUSR03.CPY:55-58} shows the four items of
         * {@code USRIDIN} - {@code USRIDINL}, {@code USRIDINF}, {@code USRIDINA} and {@code USRIDINI} -
         * all built that way. Reporting the label rather than the length item keeps the cursor request
         * readable next to the payload members, which carry the same labels.
         *
         * @return the label; never {@code null}
         */
        public String dfhmdfLabel() {
            return lengthItem.substring(0, lengthItem.length() - 1);
        }
    }

    // =================================================================================================
    // WORKING STORAGE - app/cbl/COUSR03C.cbl:35-68.
    // =================================================================================================

    /**
     * Every mutable item {@code COUSR03C} declares, for the life of one request.
     *
     * <p><strong>Why this exists at all.</strong> COBOL {@code WORKING-STORAGE} is per-task storage, and
     * the faithful Java equivalent of per-task storage is a per-request object - not a field on a
     * singleton bean, which would be shared across every concurrent request and would let one operator's
     * screen leak into another's. There is no static mutable state anywhere in this file and no mutable
     * field on the controller; one instance of this class belongs to one call of
     * {@link UserDeleteController#mainPara(UserDeleteRequest, Optional, String)} and is not thread-safe,
     * deliberately.
     *
     * <p><strong>The screen is one buffer, not two.</strong> {@code 01 COUSR3AO REDEFINES COUSR3AI}
     * [{@code app/cpy-bms/COUSR03.CPY:85}], so the input and output views describe the same bytes.
     * {@link #response()} is that buffer: {@code RECEIVE MAP} writes the terminal's values into it and
     * every {@code SEND MAP} renders it, which is what makes an inbound {@code USRIDIN} visible to the
     * validation and then visible again on the screen that is sent back.
     *
     * <p><strong>What is carried here rather than in the payload.</strong> Four observable things have no
     * home among the eleven {@code DFHMDF} fields, and each would be a contract violation if it were
     * smuggled into one: the {@code MOVE -1 TO <field>L} cursor request ({@link #cursorField()}), the
     * {@code MOVE <colour> TO ERRMSGC} colour byte ({@link #errMsgColour()}), the two {@code DISPLAY}
     * statements ({@link #displayLines()}), and how many times the screen was sent
     * ({@link #sendCount()}).
     *
     * <p><strong>What is deliberately absent.</strong> No password is held. {@code SEC-USER-DATA} does
     * carry {@code SEC-USR-PWD} because the copybook declares it and a read fills all eighty bytes, but
     * nothing in this program moves it anywhere, this class never renders it, and
     * {@link SecUserRecord#toString()} withholds it.
     */
    public static final String ERR_MSG_COLOUR_ITEM = "ERRMSGC";

    /**
     * One {@code EXEC CICS SEND MAP}: what the map area held at that moment.
     *
     * <p>{@code SEND MAP ... FROM(COUSR3AO)} transmits the whole area, so a snapshot is eleven values and
     * not the subset the send happened to change. The colour byte travels alongside rather than inside,
     * because {@code ERRMSGC} is an attribute item and not one of the eleven {@code DFHMDF} fields; a
     * default of {@code DFHDFCOL} is what {@code MOVE LOW-VALUES TO COUSR3AO} leaves in every attribute
     * position on a send the program reached without moving a colour.
     *
     * @param fields       the eleven {@code xxxO} values at this send; unmodifiable
     * @param errMsgColour {@code ERRMSGC OF COUSR3AO} at this send
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
         * The colour byte's copybook mnemonic.
         *
         * @return {@code DFHNEUTR}, {@code DFHRED}, {@code DFHGREEN} or {@code DFHDFCOL}; never
         *         {@code null}
         */
        public String errMsgColourMnemonic() {
            return BmsAttributes.colourMnemonic(errMsgColour);
        }

        /**
         * The attribute items this send set, keyed by the item name the copybook spells.
         *
         * <p>One entry, {@code ERRMSGC}, because that is the only attribute item {@code COUSR03C} ever
         * moves a value into - lines 285, 317 and 353. Listing the other attribute items of
         * {@code COUSR3AO} would claim an assertion the program does not support.
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

    public static final class ProgramState {

        /** {@code 01 COUSR3AI} and its {@code 01 COUSR3AO} redefinition: the one screen buffer. */
        private UserDeleteResponse response = UserDeleteResponse.empty();

        /** {@code 01 CARDDEMO-COMMAREA} - {@code app/cpy/COCOM01Y.cpy}, copied at line 49. */
        private NavigationContext commarea = NavigationContext.empty();

        /** {@code 05 WS-MESSAGE PIC X(80) VALUE SPACES} - line 38. */
        private String message = spaces(WS_MESSAGE_LENGTH);

        /**
         * {@code 05 WS-ERR-FLG PIC X(01) VALUE 'N'} with {@code 88 ERR-FLG-ON VALUE 'Y'} and
         * {@code 88 ERR-FLG-OFF VALUE 'N'} - lines 40 to 42.
         */
        private boolean errFlag;

        /**
         * {@code 05 WS-USR-MODIFIED PIC X(01) VALUE 'N'} with {@code 88 USR-MODIFIED-YES} and
         * {@code 88 USR-MODIFIED-NO} - lines 45 to 47.
         *
         * <p>Line 85 sets it to {@code NO} and <strong>no statement in the program ever sets it to
         * {@code YES} or tests it</strong>. It is modelled because the {@code SET} is executable code, and
         * it is reported because a parity case that fingerprints working storage would otherwise miss it.
         */
        private boolean usrModified;

        /** {@code 05 WS-RESP-CD PIC S9(09) COMP VALUE ZEROS} - line 43. */
        private int respCd;

        /** {@code 05 WS-REAS-CD PIC S9(09) COMP VALUE ZEROS} - line 44. */
        private int reasCd;

        /**
         * {@code 01 SEC-USER-DATA} - {@code app/cpy/CSUSR01Y.cpy}, copied at line 65: 80 bytes.
         *
         * <p>Holds the key before the read - {@code MOVE USRIDINI TO SEC-USR-ID} at lines 160 and 189 -
         * and the record after it. It is <strong>not</strong> cleared by
         * {@code INITIALIZE-ALL-FIELDS}, which is what lets the success message at line 319 still name the
         * deleted user.
         */
        private SecUserRecord secUserData = SecUserRecord.blank();

        /**
         * The record this task holds from {@code EXEC CICS READ ... UPDATE} at line 275.
         *
         * <p>It has no COBOL counterpart, because on the mainframe the hold is implicit task state that
         * the keyless {@code DELETE} at line 307 reaches without naming. Making it explicit is what turns
         * that command's unwritten precondition into something a call site can honour.
         */
        private HeldRecord heldRecord;

        /** The most recent {@code MOVE -1 TO <field>L}: {@code xxxL} metadata, never a payload member. */
        private CursorField cursorField;

        /**
         * {@code 05 CDEMO-CU03-INFO} - the thirty-four bytes behind the communication area, carried
         * through from the request to whichever terminal the task reaches.
         */
        private Cu03Info cu03Info = Cu03Info.initial();

        /**
         * {@code ERRMSGC OF COUSR3AO} - the message line's colour byte, {@code xxxC} metadata.
         *
         * <p>Starts at {@link BmsAttributes#DFHDFCOL}, the default colour, which is also the value
         * {@code MOVE LOW-VALUES TO COUSR3AO} at line 97 leaves in it. Line 285 sets
         * {@link BmsAttributes#DFHNEUTR} for the confirmation prompt and line 317 sets
         * {@link BmsAttributes#DFHGREEN} once the delete has succeeded. The map itself declares
         * {@code COLOR=RED} for this field [{@code app/bms/COUSR03.bms:140-143}], which is what an
         * unoverridden error message shows in.
         */
        private byte errMsgColour = BmsAttributes.DFHDFCOL;

        /** {@code WS-CURDATE-DATA} of {@code CSDAT01Y} - the last {@code FUNCTION CURRENT-DATE}, line 245. */
        private DateHeader dateHeader;

        /** Whether {@code EXEC CICS RETURN} was executed - lines 134 to 137. */
        private boolean returned;

        /** Whether {@code EXEC CICS XCTL} was executed - lines 205 to 208. */
        private boolean transferred;

        /** How many times {@code EXEC CICS SEND MAP} was executed; a send is not terminal here. */
        private final List<Send> sends = new ArrayList<>(3);

        /** Every {@code DISPLAY} the execution emitted, in order - lines 294 and 330. */
        private final List<String> displayLines = new ArrayList<>();

        // -----------------------------------------------------------------------------------------
        // The screen buffer and the communication area.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code 01 COUSR3AI} / {@code 01 COUSR3AO} - the one buffer both views describe.
         *
         * @return the screen; never {@code null}
         */
        public UserDeleteResponse response() {
            return response;
        }

        /**
         * Replaces the screen buffer, which is what any {@code MOVE ... TO <field> OF COUSR3AO} does.
         *
         * @param value the new buffer; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setResponse(UserDeleteResponse value) {
            this.response = Objects.requireNonNull(value, "A screen buffer is required; a COBOL record "
                    + "area is never absent - use UserDeleteResponse.empty() for a cleared screen");
        }

        /**
         * {@code 01 CARDDEMO-COMMAREA}.
         *
         * @return the communication area; never {@code null}
         */
        public NavigationContext commarea() {
            return commarea;
        }

        /**
         * Replaces the communication area.
         *
         * @param value the new communication area; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setCommarea(NavigationContext value) {
            this.commarea = Objects.requireNonNull(value, "A communication area is required; use "
                    + "NavigationContext.empty() for the cold-start state");
        }

        // -----------------------------------------------------------------------------------------
        // WS-MESSAGE, WS-ERR-FLG and WS-USR-MODIFIED.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code WS-MESSAGE PIC X(80)}.
         *
         * @return the message at its declared width; never {@code null}
         */
        public String message() {
            return message;
        }

        /**
         * {@code MOVE ... TO WS-MESSAGE}.
         *
         * @param value the message, already at the receiver's declared width
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setMessage(String value) {
            this.message = Objects.requireNonNull(value, "A message is required; to blank WS-MESSAGE move "
                    + "eighty spaces, which is what MOVE SPACES puts there");
        }

        /**
         * {@code 88 ERR-FLG-ON} - the condition the two {@code IF NOT ERR-FLG-ON} guards test.
         *
         * @return whether the error flag holds {@code 'Y'}
         */
        public boolean isErrFlagOn() {
            return errFlag;
        }

        /** {@code MOVE 'Y' TO WS-ERR-FLG} - lines 127, 146, 178, 288, 295, 324 and 331. */
        public void setErrFlagOn() {
            this.errFlag = true;
        }

        /** {@code SET ERR-FLG-OFF TO TRUE} - line 84. */
        public void setErrFlagOff() {
            this.errFlag = false;
        }

        /**
         * {@code 88 USR-MODIFIED-YES} - never true in this program, and reported all the same.
         *
         * @return whether the modified flag holds {@code 'Y'}
         */
        public boolean isUsrModified() {
            return usrModified;
        }

        /** {@code SET USR-MODIFIED-NO TO TRUE} - line 85, the only statement that touches the flag. */
        public void setUsrModifiedNo() {
            this.usrModified = false;
        }

        // -----------------------------------------------------------------------------------------
        // WS-RESP-CD and WS-REAS-CD.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code WS-RESP-CD} - the {@code RESP} the last file command reported.
         *
         * @return the CICS response value
         */
        public int respCd() {
            return respCd;
        }

        /**
         * {@code RESP(WS-RESP-CD)}.
         *
         * @param value the CICS response value; never negative, as CICS responses are not
         */
        public void setRespCd(int value) {
            this.respCd = value;
        }

        /**
         * {@code WS-REAS-CD} - the {@code RESP2} the last file command reported.
         *
         * @return the CICS reason code
         */
        public int reasCd() {
            return reasCd;
        }

        /**
         * {@code RESP2(WS-REAS-CD)}.
         *
         * @param value the CICS reason code; never negative
         */
        public void setReasCd(int value) {
            this.reasCd = value;
        }

        // -----------------------------------------------------------------------------------------
        // SEC-USER-DATA and the held record.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code 01 SEC-USER-DATA} - the 80-byte record area.
         *
         * @return the record area; never {@code null}
         */
        public SecUserRecord secUserData() {
            return secUserData;
        }

        /**
         * {@code INTO(SEC-USER-DATA)} - the whole record area is replaced, which is what a successful
         * {@code READ} does to all eighty bytes.
         *
         * @param value the record just read; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setSecUserData(SecUserRecord value) {
            this.secUserData = Objects.requireNonNull(value, "A record is required; a failed read leaves "
                    + "the area as it was rather than emptying it, so nothing calls this with null");
        }

        /**
         * {@code MOVE USRIDINI OF COUSR3AI TO SEC-USR-ID} - lines 160 and 189.
         *
         * <p>One field of the record area, not the whole area: the remaining five items keep whatever
         * they held, exactly as a COBOL {@code MOVE} into a group's sub-item leaves its siblings alone.
         *
         * @param image the key at its declared width of {@value UserDeleteController#SEC_USR_ID_LENGTH}
         * @throws NullPointerException     if {@code image} is {@code null}
         * @throws IllegalArgumentException if {@code image} is not exactly the declared width
         */
        public void moveToSecUsrId(String image) {
            this.secUserData = new SecUserRecord(image,
                    secUserData.secUsrFname(),
                    secUserData.secUsrLname(),
                    secUserData.secUsrPwd(),
                    secUserData.secUsrType(),
                    secUserData.secUsrFiller());
        }

        /**
         * The record this task holds from the {@code READ ... UPDATE}, and the delete's only subject.
         *
         * @return the hold, or empty when the read did not take one; never {@code null}
         */
        public Optional<HeldRecord> heldRecord() {
            return Optional.ofNullable(heldRecord);
        }

        /**
         * Records what the {@code READ ... UPDATE} left held - which is nothing when it failed.
         *
         * @param hold the hold the read reported; must not be {@code null}, though it may be empty
         * @throws NullPointerException if {@code hold} is {@code null}
         */
        public void holdRecord(Optional<HeldRecord> hold) {
            Objects.requireNonNull(hold, "A hold is reported as an empty Optional when the read took "
                    + "none, never as null");
            this.heldRecord = hold.orElse(null);
        }

        /** Releases the hold, because the record it stood for no longer exists. */
        public void releaseHold() {
            this.heldRecord = null;
        }

        // -----------------------------------------------------------------------------------------
        // Symbolic-map metadata: the cursor request and the message line's colour byte.
        // -----------------------------------------------------------------------------------------

        /**
         * The most recent {@code MOVE -1 TO <field>L}.
         *
         * @return the field the next {@code SEND MAP ... CURSOR} puts the cursor on, or empty when no
         *         request has been made; never {@code null}
         */
        public Optional<CursorField> cursorField() {
            return Optional.ofNullable(cursorField);
        }

        /**
         * {@code 05 CDEMO-CU03-INFO} as it arrived, which is also how it leaves.
         *
         * @return the extension; never {@code null}
         */
        public Cu03Info cu03Info() {
            return cu03Info;
        }

        /**
         * Records the extension the communication area arrived with.
         *
         * @param info the extension, or {@code null} for {@link Cu03Info#initial()}
         */
        void setCu03Info(Cu03Info info) {
            this.cu03Info = info == null ? Cu03Info.initial() : info;
        }

        /**
         * This screen's presentation metadata, in the shared envelope every online response publishes.
         *
         * <p>{@code COUSR03} declares no {@code xxxC}, {@code xxxP}, {@code xxxH} or {@code xxxV} items
         * beyond the message line, so there are no per-field quads to project and the field map is empty -
         * an accurate empty rather than a missing one. The two things this program does write that are
         * metadata by declaration, and that had no way to travel at all, are reported:
         *
         * <ul>
         *   <li>{@code MOVE -1 TO <field>L} - the cursor request, named by its {@code DFHMDF} label. The
         *       {@code xxxL} item is {@code COMP PIC S9(4)} input-group metadata and never a payload
         *       member (gate G9); only {@code USRIDINL} and {@code FNAMEL} are ever named.</li>
         *   <li>{@code MOVE <colour> TO ERRMSGC OF COUSR3AO} - the colour of the message line, as its
         *       unsigned byte value. {@link BmsAttributes#DFHGREEN} on the successful delete at line 314
         *       and {@link BmsAttributes#DFHRED} on every refusal.</li>
         * </ul>
         *
         * <p>{@code resetAllOutputFields} is {@code false}: {@code MOVE LOW-VALUES TO COUSR3AO} at line 97
         * has already been performed on this response, so the cleared state is in the eleven values the
         * client receives and there is nothing left for it to repeat.
         *
         * @return the metadata, never {@code null}
         */
        public ScreenMetadata screenMetadata() {
            return ScreenMetadata.of(cursorField().map(CursorField::dfhmdfLabel).orElse(null),
                    errMsgColour,
                    false);
        }

        /**
         * {@code MOVE -1 TO <field>L}.
         *
         * @param field the field to place the cursor on; must not be {@code null}
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public void moveMinusOneTo(CursorField field) {
            this.cursorField = Objects.requireNonNull(field, "A cursor target is required; COUSR03C names "
                    + "only USRIDINL and FNAMEL");
        }

        /**
         * {@code ERRMSGC OF COUSR3AO} - the message line's colour byte.
         *
         * @return the colour attribute, one of the {@link BmsAttributes} colour constants
         */
        public byte errMsgColour() {
            return errMsgColour;
        }

        /**
         * {@code MOVE <colour> TO ERRMSGC OF COUSR3AO} - lines 285 and 317.
         *
         * @param colour the colour attribute byte
         */
        public void setErrMsgColour(byte colour) {
            this.errMsgColour = colour;
        }

        // -----------------------------------------------------------------------------------------
        // The header capture, the terminal state and the console output.
        // -----------------------------------------------------------------------------------------

        /**
         * The most recent {@code FUNCTION CURRENT-DATE} capture.
         *
         * @return the header, or empty before the first send; never {@code null}
         */
        public Optional<DateHeader> dateHeader() {
            return Optional.ofNullable(dateHeader);
        }

        /**
         * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} - line 245.
         *
         * @param header the capture; must not be {@code null}
         * @throws NullPointerException if {@code header} is {@code null}
         */
        public void setDateHeader(DateHeader header) {
            this.dateHeader = Objects.requireNonNull(header, "A date capture is required: every send "
                    + "performs POPULATE-HEADER-INFO, which reads the clock first");
        }

        /**
         * Whether {@code EXEC CICS RETURN} was executed - lines 134 to 137.
         *
         * @return {@code true} when the task returned to CICS rather than transferring
         */
        public boolean isReturned() {
            return returned;
        }

        /** Records {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)}. */
        public void markReturned() {
            this.returned = true;
        }

        /**
         * Whether {@code EXEC CICS XCTL} was executed - lines 205 to 208.
         *
         * @return {@code true} when control passed to the program the response names
         */
        public boolean isTransferred() {
            return transferred;
        }

        /** Records {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)}. */
        public void markTransferred() {
            this.transferred = true;
        }

        /**
         * How many times {@code EXEC CICS SEND MAP} was executed.
         *
         * <p>More than one is normal here and is not a defect: a send returns to its caller, so the
         * successful fetch sends twice and a failed confirm sends twice as well. Each send re-derives the
         * whole screen, so {@link #response()} is what the last one painted.
         *
         * @return the number of sends - {@code sends().size()}, named for readability
         */
        public int sendCount() {
            return sends.size();
        }

        /**
         * One entry per {@code EXEC CICS SEND MAP}, in order.
         *
         * <p>Why the count alone is not enough: {@code EXEC CICS SEND MAP ... FROM(COUSR3AO)} sends the
         * <strong>whole</strong> map area, so a path that sends twice put eleven values on the terminal
         * twice - and they were not the same eleven both times. Arrival from the user list is the clearest
         * case: the lookup paints the record and the prompt, and the unconditional send that follows paints
         * it again after the type has been resolved. {@link #response()} holds only what the last send
         * painted, so without these snapshots the earlier ones are unobservable and a parity case can only
         * assert the final screen.
         *
         * @return the snapshots; unmodifiable, possibly empty, never {@code null}
         */
        public List<Send> sends() {
            return Collections.unmodifiableList(sends);
        }

        /**
         * Records one {@code EXEC CICS SEND MAP ... ERASE CURSOR}.
         *
         * <p>Captures what went to the terminal rather than only that something did: the eleven
         * {@code xxxO} values as this send left them, keyed by the names {@code app/cpy-bms/COUSR03.CPY}
         * spells, plus the {@code ERRMSGC} colour byte in force at that moment.
         */
        public void recordSend() {
            sends.add(new Send(response().fieldValues(), errMsgColour));
        }

        /**
         * Every {@code DISPLAY} the execution emitted, in order.
         *
         * @return an unmodifiable view; never {@code null}
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
}
