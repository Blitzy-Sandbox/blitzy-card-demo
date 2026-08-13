package com.vsergeychik.carddemo.user;

import com.vsergeychik.carddemo.common.AidRequestParameter;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
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
 * <p>{@code PF3} conventionally abandons, and {@code app/cbl/COUSR03C.cbl} - the sibling delete screen -
 * does exactly that.
 */
@RestController
public class UserUpdateController {
    private static final Log LOG = LogFactory.getLog(UserUpdateController.class);

    /**
     * The code page of this program's {@code WORKING-STORAGE} and of its screen buffer.
     */
    public static final Charset WORKING_STORAGE_CHARSET = StandardCharsets.US_ASCII;

    private static final FixedWidthCodec PICTURE_RULES = new FixedWidthCodec(WORKING_STORAGE_CHARSET);

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COUSR02C'} - line 36.
     */
    public static final String WS_PGMNAME = "COUSR02C";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CU02'} - line 37.
     */
    public static final String WS_TRANID = "CU02";

    public static final String EIBCALEN_PARAM = "eibcalen";

    public static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    public static final String EIBAID_PARAM_ALIAS = AidRequestParameter.ALTERNATE_NAME;

    static final int AID_MIN = 0;

    static final int AID_MAX = 255;

    static final int RAW_AID_LENGTH = 1;

    static final char MAX_AID_CODE_POINT = 0x00FF;

    static final String USRIDIN_MEMBER = "usridin";

    static final String AID_MEMBER = "aid";

    /**
     * Declared width of {@code WS-MESSAGE PIC X(80)} - line 38.
     */
    public static final int WS_MESSAGE_LENGTH = 80;

    /**
     * Declared digits of {@code WS-RESP-CD} and {@code WS-REAS-CD}: {@code PIC S9(09) COMP}, lines 43-44.
     */
    public static final int WS_RESP_CD_DIGITS = 9;

    /**
     * {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC '} - line 39, the {@code DATASET} option of both file
     * commands.
     */
    public static final String WS_USRSEC_FILE = SecUserRepository.CICS_FILE_NAME_IMAGE;

    /**
     * {@code 88 ERR-FLG-ON VALUE 'Y'} over {@code WS-ERR-FLG PIC X(01)} - lines 40-41.
     */
    public static final String ERR_FLG_ON = "Y";

    /**
     * {@code 88 ERR-FLG-OFF VALUE 'N'} - line 42, and the field's {@code VALUE} clause at line 40.
     */
    public static final String ERR_FLG_OFF = "N";

    /**
     * {@code 88 USR-MODIFIED-YES VALUE 'Y'} over {@code WS-USR-MODIFIED PIC X(01)} - lines 45-46.
     */
    public static final String USR_MODIFIED_YES = "Y";

    /**
     * {@code 88 USR-MODIFIED-NO VALUE 'N'} - line 47, and the field's {@code VALUE} at line 45.
     */
    public static final String USR_MODIFIED_NO = "N";

    public static final String LIT_SIGNON_PGM = "COSGN00C";

    public static final String LIT_ADMIN_PGM = "COADM01C";

    public static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    public static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    public static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    public static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    public static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /**
     * {@code 'Press PF5 key to save your updates ...'} - lines 336-337, the successful-lookup prompt.
     */
    public static final String MSG_PRESS_PF5 = "Press PF5 key to save your updates ...";

    public static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

    public static final String MSG_UNABLE_TO_LOOKUP = "Unable to lookup User...";

    public static final String MSG_UNABLE_TO_UPDATE = "Unable to Update User...";

    public static final String MSG_PLEASE_MODIFY = "Please modify to update ...";

    public static final String MSG_UPDATED_PREFIX = "User ";

    public static final String MSG_UPDATED_SUFFIX = " has been updated ...";

    private static final String SPACE = " ";

    private static final String LOW_VALUE = "\u0000";

    public static final int NO_COMMAREA_LENGTH = 0;

    /**
     * {@link ProgramState#termination()} for the {@code EXEC CICS XCTL} of lines 258-261.
     */
    public static final String TERMINATION_XCTL = "XCTL";

    /**
     * {@link ProgramState#termination()} for the {@code EXEC CICS RETURN TRANSID} of lines 135-138.
     */
    public static final String TERMINATION_RETURN_TRANSID = "RETURN_TRANSID";

    /**
     * {@code EIBCALEN} when this program's own communication area travelled: 194 bytes, not 160.
     */
    public static final int PASSED_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + Cu02Info.LENGTH;

    private final SecUserRepository secUserRepository;

    private final Clock clock;

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

    /**
     * The five symbolic-map fields {@code COUSR02C} ever moves {@code -1} into, which is how it asks CICS
     * to place the cursor.
     */
    public enum ScreenField {
        USRIDIN("USRIDINL"),

        FNAME("FNAMEL"),

        LNAME("LNAMEL"),

        PASSWD("PASSWDL"),

        USRTYPE("USRTYPEL");

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

    /**
     * What one {@code EXEC CICS SEND MAP} put on the terminal.
     *
     * <p>Keys are the {@code xxxO} names {@code app/cpy-bms/COUSR02.CPY} spells, in screen order, exactly
     * as {@link UserUpdateResponse#fieldValues()} produces them - so a comparison walks the fields in the
     * order the mapset declares rather than an arbitrary one.
     *
     * @param fields the twelve {@code xxxO} values at this send; unmodifiable
     * @param errMsgColour {@code ERRMSGC OF COUSR2AO} at this send
     */
    public record Send(Map<String, String> fields, byte errMsgColour) {
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
         * @return an unmodifiable single-entry map from {@code ERRMSGC} to its mnemonic
         */
        public Map<String, String> attributes() {
            return Map.of(ERR_MSG_COLOUR_ITEM, errMsgColourMnemonic());
        }

        public String value(String cobolName) {
            return fields.get(cobolName);
        }
    }

    public static final String ERR_MSG_COLOUR_ITEM = "ERRMSGC";

    /**
     * {@code PUT /api/users/&#123;userId&#125;} - CICS transaction {@code CU02}, the only route this
     * program has.
     *
     * <p>A CICS task always has a unit of work, so the lock spans both commands; under auto-commit a row
     * lock would be gone before the rewrite and the caller would never know.
     *
     * @param userId the resource identity; the value that occupies {@code USRIDIN} and thence
     *     {@code SEC-USR-ID}
     * @param request the twelve {@code xxxI} items, the communication area, its 34-byte extension and the
     *     one-character {@code EIBAID} image; validated against the symbolic map's declared widths
     * @param eibcalen {@code EIBCALEN}, optional
     * @param eibaid {@code EIBAID} as an unsigned {@code 0}-{@code 255} byte under {@link #EIBAID_PARAM},
     *     optional
     * @param eibAid the same value under {@link #EIBAID_PARAM_ALIAS}; at most one need be sent
     * @return the painted screen and its metadata: the twelve {@code xxxO} values, the navigation triple,
     *     the communication area and its extension, all in the body so that nothing is retained server-side
     * @throws NullPointerException if {@code userId} or {@code request} is {@code null}
     * @throws IllegalArgumentException if {@code userId} is wider than {@code USRIDIN}, or if
     *     {@code eibcalen} is negative or disagrees with the carrier
     */
    @PutMapping(path = "/api/users/{userId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ScreenResponse<UserUpdateResponse> updateUser(
            @PathVariable("userId") String userId,
            @Valid @RequestBody UserUpdateRequest request,
            @RequestParam(name = EIBCALEN_PARAM, required = false) Integer eibcalen,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid) {
        Objects.requireNonNull(userId, "A user id is required: it is the RIDFLD of the READ at line 322 "
                + "and of the REWRITE's held record at line 360");
        Objects.requireNonNull(request, "A request is required: COUSR02C is entered with a terminal "
                + "input area, and an absent one is spaces rather than nothing");
        requireIdentityFits(userId);

        int commareaLength = resolveEibcalen(eibcalen, request);

        // The binding rule, applied in exactly one place, on EVERY turn.
        //
        // COUSR02C:95-105 is the first-entry arm: it blanks the output map, places the cursor and - only
        // if CDEMO-CU02-USR-SELECTED is neither SPACES nor LOW-VALUES - moves that extension field into
        // USRIDINI and performs PROCESS-ENTER-KEY. :106-107 is the other arm: PERFORM
        // RECEIVE-USRUPD-SCREEN, then EVALUATE EIBAID, from where the screen's own USRIDINI is what the
        // program reads (:146, :180) - and :162 and :216 make it the RIDFLD of the locking read and of
        // the rewrite. Both carriers are therefore bound from the URI on every turn, so the record read,
        // the record painted and the record rewritten are always the one the URI names. The path has
        // already been required to fit, so the MOVE only pads.
        //
        // USRIDIN is the field the operator types into, so a value there naming a DIFFERENT user is two
        // keys in one request and is refused before anything is read or locked. Overwriting it silently
        // discarded the operator's own typed identity with no message; honouring it let PUT /api/users/A
        // read, paint and rewrite user B.
        ScreenInputRejectedException.requireKeyAgreement(USRIDIN_MEMBER, userId, request.usrIdIn(),
                UserUpdateRequest.USRIDIN_LENGTH, PICTURE_RULES);
        Cu02Info arrived = request.cu02Info();
        String identity = PICTURE_RULES.movePicX(userId, UserUpdateRequest.USRIDIN_LENGTH);
        Cu02Info cu02Info = new Cu02Info(arrived.usridFirst(),
                arrived.usridLast(),
                arrived.pageNum(),
                arrived.nextPageFlg(),
                arrived.usrSelFlg(),
                identity);

        UserUpdateRequest received = new UserUpdateRequest(request.trnName(),
                request.title01(),
                request.curDate(),
                request.pgmName(),
                request.title02(),
                request.curTime(),
                identity,
                request.fName(),
                request.lName(),
                request.passwd(),
                request.usrType(),
                request.errMsg(),
                request.navigationContext(),
                request.aid(),
                cu02Info);

        ProgramState state = handle(received, commareaLength,
                resolveEibAid(AidRequestParameter.resolve(eibaid, eibAid),
                        request == null ? null : request.aid()), cu02Info);
        return ScreenResponse.of(state.response(), state.screenMetadata());
    }

    /**
     * Requires the path identity to fit {@code USRIDIN PIC X(08)}, refusing it rather than truncating.
     *
     * @param userId the path variable
     * @throws IllegalArgumentException if it is wider than {@value UserUpdateRequest#USRIDIN_LENGTH}
     *     characters
     */
    static void requireIdentityFits(String userId) {
        if (userId.length() > UserUpdateRequest.USRIDIN_LENGTH) {
            throw ScreenInputRejectedException.tooWide(USRIDIN_MEMBER,
                    "USRIDINI PIC X(" + UserUpdateRequest.USRIDIN_LENGTH
                            + ") and SEC-USR-ID PIC X(" + UserUpdateRequest.USRIDIN_LENGTH + ")",
                    UserUpdateRequest.USRIDIN_LENGTH, userId.length());
        }
    }

    static int resolveEibcalen(Integer eibcalen, UserUpdateRequest request) {
        boolean carried = request.hasNavigationContext();
        if (eibcalen == null) {
            return carried ? PASSED_COMMAREA_LENGTH : NO_COMMAREA_LENGTH;
        }
        int stated = eibcalen;
        if (stated < NO_COMMAREA_LENGTH) {
            throw new IllegalArgumentException("The " + EIBCALEN_PARAM + " parameter is " + stated
                    + ", and EIBCALEN is the length of the area CICS passed, which cannot be negative.");
        }
        if ((stated == NO_COMMAREA_LENGTH) == carried) {
            throw new IllegalArgumentException("The " + EIBCALEN_PARAM + " parameter says " + stated
                    + " but the payload carries " + (carried ? "a" : "no")
                    + " communication area. EIBCALEN describes what arrived; it cannot contradict it, "
                    + "because app/cbl/COUSR02C.cbl:90 uses it to decide whether the conversation had "
                    + "any state at all.");
        }
        return stated;
    }

    static byte resolveAttentionIdentifier(String aidImage) {
        if (aidImage == null) {
            return CicsAid.DFHENTER;
        }
        if (aidImage.length() != RAW_AID_LENGTH) {
            return CicsAid.DFHNULL;
        }
        char stated = aidImage.charAt(0);
        if (stated > MAX_AID_CODE_POINT) {
            return CicsAid.DFHNULL;
        }
        return (byte) stated;
    }

    static byte resolveEibAid(Integer statedAid, String aidToken) {
        if (statedAid == null) {
            return resolveAttentionIdentifier(aidToken);
        }
        return AidRequestParameter.requireStatedAid(AID_MEMBER, statedAid, aidToken, PICTURE_RULES);
    }

    /**
     * {@code MAIN-PARA} - the whole transaction, in the source's order.
     *
     * <p>The arms are tested in source order through {@code common.PfKeyResolver}'s byte-equality
     * predicates, which are the same {@code ==} the COBOL {@code EVALUATE EIBAID} performs - so
     * {@code PF15} does not match {@code WHEN DFHPF3}, exactly as on the mainframe.
     *
     * @param request the terminal input area and the carried communication area; must not be {@code null}
     * @param eibcalen {@code EIBCALEN}, the length of the passed communication area
     * @param eibAid {@code EIBAID}, the raw attention-identifier byte
     * @param cu02Info this program's own 34-byte commarea extension; must not be {@code null}
     * @return the terminal state: the response body plus the colour byte, the cursor request, the send
     *     count, the {@code DISPLAY} lines and the record area
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

        ProgramState state = new ProgramState(request, cu02Info, eibAid);

        state.setErrFlgOff();
        state.setUsrModifiedNo();
        state.setWsMessage(spaces(WS_MESSAGE_LENGTH));
        state.setErrMsg(spaces(UserUpdateResponse.ERR_MSG_LENGTH));

        if (eibcalen == NO_COMMAREA_LENGTH) {
            state.setCommarea(state.commarea().withToProgram(LIT_SIGNON_PGM));
            returnToPrevScreen(state);
        } else {
            if (!state.commarea().isReenter()) {
                state.setCommarea(state.commarea().withPgmReenter());
                state.moveLowValuesToScreenBuffer();
                state.requestCursorOn(ScreenField.USRIDIN);
                if (!isSpacesOrLowValues(state.cu02Info().usrSelected())) {
                    state.setUsrIdIn(PICTURE_RULES.movePicX(state.cu02Info().usrSelected(),
                            UserUpdateResponse.USR_ID_IN_LENGTH));
                    processEnterKey(state);
                }
                sendUsrupdScreen(state);
            } else {
                receiveUsrupdScreen(state);
                if (PfKeyResolver.isEnter(eibAid)) {
                    processEnterKey(state);
                } else if (PfKeyResolver.isPf3(eibAid)) {
                    updateUserInfo(state);
                    if (isSpacesOrLowValues(state.commarea().fromProgram())) {
                        state.setCommarea(state.commarea().withToProgram(LIT_ADMIN_PGM));
                    } else {
                        state.setCommarea(
                                state.commarea().withToProgram(state.commarea().fromProgram()));
                    }
                    returnToPrevScreen(state);
                } else if (PfKeyResolver.isPf4(eibAid)) {
                    clearCurrentScreen(state);
                } else if (PfKeyResolver.isPf5(eibAid)) {
                    updateUserInfo(state);
                } else if (PfKeyResolver.isPf12(eibAid)) {
                    state.setCommarea(state.commarea().withToProgram(LIT_ADMIN_PGM));
                    returnToPrevScreen(state);
                } else {
                    state.setErrFlgOn();
                    state.setWsMessage(PICTURE_RULES.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                            WS_MESSAGE_LENGTH));
                    sendUsrupdScreen(state);
                }
            }
        }

        state.recordReturn(WS_TRANID);
        return state;
    }

    void processEnterKey(ProgramState state) {
        if (isSpacesOrLowValues(state.usrIdIn())) {
            state.setErrFlgOn();
            state.setWsMessage(PICTURE_RULES.movePicX(MSG_USER_ID_EMPTY, WS_MESSAGE_LENGTH));
            state.requestCursorOn(ScreenField.USRIDIN);
            sendUsrupdScreen(state);
        } else {
            state.requestCursorOn(ScreenField.USRIDIN);
        }

        if (!state.errFlgOn()) {
            state.setFName(spaces(UserUpdateResponse.FNAME_LENGTH));
            state.setLName(spaces(UserUpdateResponse.LNAME_LENGTH));
            state.setPasswd(spaces(UserUpdateResponse.PASSWD_LENGTH));
            state.setUsrType(spaces(UserUpdateResponse.USR_TYPE_LENGTH));
            state.setSecUsrId(state.usrIdIn());
            readUserSecFile(state);
        }

        if (!state.errFlgOn()) {
            SecUserRecord record = state.secUserData();
            state.setFName(PICTURE_RULES.movePicX(record.secUsrFname(),
                    UserUpdateResponse.FNAME_LENGTH));
            state.setLName(PICTURE_RULES.movePicX(record.secUsrLname(),
                    UserUpdateResponse.LNAME_LENGTH));
            state.setPasswd(PICTURE_RULES.movePicX(record.secUsrPwd(),
                    UserUpdateResponse.PASSWD_LENGTH));
            state.setUsrType(PICTURE_RULES.movePicX(record.secUsrType(),
                    UserUpdateResponse.USR_TYPE_LENGTH));
            sendUsrupdScreen(state);
        }
    }

    void updateUserInfo(ProgramState state) {
        if (isSpacesOrLowValues(state.usrIdIn())) {
            state.setErrFlgOn();
            state.setWsMessage(PICTURE_RULES.movePicX(MSG_USER_ID_EMPTY, WS_MESSAGE_LENGTH));
            state.requestCursorOn(ScreenField.USRIDIN);
            sendUsrupdScreen(state);
        } else if (isSpacesOrLowValues(state.fName())) {
            state.setErrFlgOn();
            state.setWsMessage(
                    PICTURE_RULES.movePicX(MSG_FIRST_NAME_EMPTY, WS_MESSAGE_LENGTH));
            state.requestCursorOn(ScreenField.FNAME);
            sendUsrupdScreen(state);
        } else if (isSpacesOrLowValues(state.lName())) {
            state.setErrFlgOn();
            state.setWsMessage(
                    PICTURE_RULES.movePicX(MSG_LAST_NAME_EMPTY, WS_MESSAGE_LENGTH));
            state.requestCursorOn(ScreenField.LNAME);
            sendUsrupdScreen(state);
        } else if (isSpacesOrLowValues(state.passwd())) {
            state.setErrFlgOn();
            state.setWsMessage(
                    PICTURE_RULES.movePicX(MSG_PASSWORD_EMPTY, WS_MESSAGE_LENGTH));
            state.requestCursorOn(ScreenField.PASSWD);
            sendUsrupdScreen(state);
        } else if (isSpacesOrLowValues(state.usrType())) {
            state.setErrFlgOn();
            state.setWsMessage(
                    PICTURE_RULES.movePicX(MSG_USER_TYPE_EMPTY, WS_MESSAGE_LENGTH));
            state.requestCursorOn(ScreenField.USRTYPE);
            sendUsrupdScreen(state);
        } else {
            state.requestCursorOn(ScreenField.FNAME);
        }

        if (!state.errFlgOn()) {
            state.setSecUsrId(state.usrIdIn());
            readUserSecFile(state);

            // Not in the source, and not a change to what the source computes. Line 169 put the stored
            // SEC-USR-PWD on the screen so that line 227 below would read EQUAL when the operator left
            // the field alone - ATTRB=(DRK,FSET) let the 3270 hold and return a value the operator could
            // not see. HTTP has no DRK bit and no single recipient, so UserUpdateResponse publishes
            // PASSWD_UNCHANGED in the stored secret's place; restoring the stored value here is what
            // makes an untouched field read equal again, so USR-MODIFIED-YES is not set spuriously and
            // the locked rewrite carries the secret through unchanged. A genuinely retyped password is
            // some other value and is compared verbatim, exactly as before.
            //
            // Guarded on the read having succeeded: on NOTFND there is no stored value to restore, and
            // the marker must then behave like any other typed value so the four independent IFs below
            // reach the same outcome they would for one.
            if (!state.errFlgOn()
                    && UserUpdateResponse.PASSWD_UNCHANGED.equals(state.passwd())) {
                state.setPasswd(state.secUserData().secUsrPwd());
            }

            // :219-234 - FOUR INDEPENDENT IFs. No error guard precedes them; see the class notes.
            if (!state.fName().equals(state.secUserData().secUsrFname())) {   // :219
                state.setSecUsrFname(state.fName());                      // :220
                state.setUsrModifiedYes();                                // :221
            }
            if (!state.lName().equals(state.secUserData().secUsrLname())) {
                state.setSecUsrLname(state.lName());
                state.setUsrModifiedYes();
            }
            if (!state.passwd().equals(state.secUserData().secUsrPwd())) {
                state.setSecUsrPwd(state.passwd());
                state.setUsrModifiedYes();
            }
            if (!state.usrType().equals(state.secUserData().secUsrType())) {
                state.setSecUsrType(state.usrType());
                state.setUsrModifiedYes();
            }

            if (state.usrModifiedYes()) {
                updateUserSecFile(state);
            } else {
                state.setWsMessage(
                        PICTURE_RULES.movePicX(MSG_PLEASE_MODIFY, WS_MESSAGE_LENGTH));
                state.setErrMsgColour(BmsAttributes.DFHRED);
                sendUsrupdScreen(state);
            }
        }
    }

    void readUserSecFile(ProgramState state) {
        SecUserRepository.ReadResult result =
                secUserRepository.readForUpdate(state.secUserData().secUsrId());
        // RESP_NOT_REPORTED, never NORMAL: an outcome carrying no CICS response is not a reported
        // DFHRESP(NORMAL), and storing zero would make the two indistinguishable on the DISPLAY at :347.
        state.recordFileResponse(result.cicsResp().orElse(FileStatus.RESP_NOT_REPORTED),
                result.cicsResp2());

        if (result.isFound()) {
            state.setSecUserData(result.requireRecord());
            state.setHold(result.hold());
            state.setWsMessage(PICTURE_RULES.movePicX(MSG_PRESS_PF5, WS_MESSAGE_LENGTH));
            state.setErrMsgColour(BmsAttributes.DFHNEUTR);
            sendUsrupdScreen(state);
        } else if (result.isNotFound()) {
            state.setErrFlgOn();
            state.setWsMessage(PICTURE_RULES.movePicX(MSG_USER_NOT_FOUND, WS_MESSAGE_LENGTH));
            state.requestCursorOn(ScreenField.USRIDIN);
            sendUsrupdScreen(state);
        } else {
            state.recordDisplayLine(displayLine(state.wsRespCd(), state.wsReasCd()));
            state.setErrFlgOn();
            state.setWsMessage(
                    PICTURE_RULES.movePicX(MSG_UNABLE_TO_LOOKUP, WS_MESSAGE_LENGTH));
            state.requestCursorOn(ScreenField.FNAME);
            sendUsrupdScreen(state);
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
     * <p><strong>The rewrite goes through the hold, and the no-hold path is a reported condition rather
     * than a statement.</strong> {@link SecUserRepository.HeldRecord#rewrite(SecUserRecord)} is the
     * operation this command is: no {@code RIDFLD} means "replace the record this task holds", so the row
     * written is the row {@link #readUserSecFile} locked and nothing in {@code SEC-USER-DATA} can redirect
     * it. On the path described in {@link #updateUserInfo} the read failed and <em>no record is held at
     * all</em>, yet the program still issues the command - so that path is reproduced by reporting what
     * CICS reports for a {@code REWRITE} with nothing held, {@code DFHRESP(INVREQ)} behind the
     * permanent-error status, <strong>without touching the dataset</strong>. It lands on the
     * {@code WHEN OTHER} arm at {@code :383-389}: the {@code DISPLAY} of {@code RESP} 16 and
     * {@code REAS} 0, then {@code 'Unable to Update User...'}. Issuing a keyed rewrite there instead would
     * be a different command from the one the source contains, and against a backend that had acquired
     * the row in the meantime it would overwrite a record this task had never read.
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
        // :360-366 EXEC CICS REWRITE DATASET(WS-USRSEC-FILE) FROM(SEC-USER-DATA) - no RIDFLD, so the
        // record replaced is the one the READ ... UPDATE at :322-331 holds. With no hold there is nothing
        // to replace, which is the INVREQ condition and not a keyed write.
        SecUserRepository.WriteResult result = state.hold()
                .map(hold -> hold.rewrite(state.secUserData()))
                .orElseGet(UserUpdateController::rewriteWithNothingHeld);
        // RESP_NOT_REPORTED, never NORMAL - see readUserSecFile. The DISPLAY at :384 must not render
        // RESP: 000000000 for a rewrite that reported no CICS response at all.
        state.recordFileResponse(result.cicsResp().orElse(FileStatus.RESP_NOT_REPORTED),
                result.cicsResp2());

        if (result.isWritten()) {
            state.setWsMessage(spaces(WS_MESSAGE_LENGTH));
            state.setErrMsgColour(BmsAttributes.DFHGREEN);
            state.setWsMessage(updatedConfirmation(state.secUserData().secUsrId()));
            sendUsrupdScreen(state);
        } else if (result.isNotFound()) {
            state.setErrFlgOn();
            state.setWsMessage(PICTURE_RULES.movePicX(MSG_USER_NOT_FOUND, WS_MESSAGE_LENGTH));
            state.requestCursorOn(ScreenField.USRIDIN);
            sendUsrupdScreen(state);
        } else {
            state.recordDisplayLine(displayLine(state.wsRespCd(), state.wsReasCd()));
            state.setErrFlgOn();
            state.setWsMessage(
                    PICTURE_RULES.movePicX(MSG_UNABLE_TO_UPDATE, WS_MESSAGE_LENGTH));
            state.requestCursorOn(ScreenField.FNAME);
            sendUsrupdScreen(state);
        }
    }

    /**
     * What {@code EXEC CICS REWRITE} reports when the task holds no record: {@code DFHRESP(INVREQ)}.
     *
     * <p>Reached on exactly one path, and it is a path the source really has. {@code UPDATE-USER-INFO}
     * does not re-test {@code WS-ERR-FLG} after {@code READ-USER-SEC-FILE} - see {@link #updateUserInfo} -
     * so a read that answered {@code NOTFND} or failed still falls through the four change tests at
     * {@code :219-234} and still performs {@code UPDATE-USER-SEC-FILE} at {@code :237}. The command it
     * issues at {@code :360-366} carries no {@code RIDFLD}, and a rewrite with nothing held is an invalid
     * request: CICS raises {@code INVREQ} and the file is not touched. That is what this returns, so the
     * {@code EVALUATE} at {@code :368-390} takes its {@code WHEN OTHER} arm - which is why the operator
     * sees {@code 'Unable to Update User...'} after {@code 'User ID NOT found...'} rather than the same
     * message twice.
     *
     * <p>No dataset interaction of any kind happens on this path. Reproducing the fall-through with a
     * keyed rewrite would be a different command with different concurrency behaviour: between the failed
     * read and the write, another task may have added the very key the operator typed, and a keyed rewrite
     * would then replace a record this task never read and never showed anyone.
     *
     * @return the invalid-request outcome, carrying {@code RESP} 16 and {@code RESP2} 0
     */
    private static SecUserRepository.WriteResult rewriteWithNothingHeld() {
        LOG.warn("COUSR02C reached UPDATE-USER-SEC-FILE with no record held for update: "
                + "app/cbl/COUSR02C.cbl:215-237 does not re-test WS-ERR-FLG after READ-USER-SEC-FILE, so a "
                + "failed read still performs the REWRITE. The command carries no RIDFLD, so with nothing "
                + "held it is an invalid request - DFHRESP(INVREQ), file status "
                + FileStatus.toStatusImage(SecUserRepository.PERMANENT_ERROR_STATUS) + " - and the "
                + "security-user file is not touched.");
        return SecUserRepository.WriteResult.of(SecUserRepository.PERMANENT_ERROR_STATUS,
                CicsResponse.of(FileStatus.INVREQ));
    }

    /**
     * Composes {@code WS-MESSAGE} exactly as the {@code STRING} statement at lines 372-375 does.
     *
     * @param secUsrId the key from the record area, {@value SecUserRecord#SEC_USR_ID_LENGTH} characters
     * @return {@code WS-MESSAGE} as the statement leaves it, exactly {@value #WS_MESSAGE_LENGTH} characters
     */
    static String updatedConfirmation(String secUsrId) {
        return PICTURE_RULES.movePicX(
                PICTURE_RULES.concatenateDelimitedBySize(MSG_UPDATED_PREFIX,
                        delimitedBySpace(secUsrId),
                        MSG_UPDATED_SUFFIX),
                WS_MESSAGE_LENGTH);
    }

    static String delimitedBySpace(String value) {
        Objects.requireNonNull(value, "A sending item is required; STRING ... DELIMITED BY SPACE reads "
                + "characters out of a field, and a COBOL field is never absent");
        int firstSpace = value.indexOf(SPACE);
        return firstSpace < 0 ? value : value.substring(0, firstSpace);
    }

    void returnToPrevScreen(ProgramState state) {
        if (isSpacesOrLowValues(state.commarea().toProgram())) {
            state.setCommarea(state.commarea().withToProgram(LIT_SIGNON_PGM));
        }
        state.setCommarea(state.commarea()
                .withFromTranid(WS_TRANID)
                .withFromProgram(WS_PGMNAME)
                .withPgmContext(NavigationContext.PGM_CONTEXT_ENTER));
        state.recordTransfer(state.commarea().toProgram());
    }

    void sendUsrupdScreen(ProgramState state) {
        populateHeaderInfo(state);
        state.setErrMsg(PICTURE_RULES.movePicX(state.wsMessage(),
                UserUpdateResponse.ERR_MSG_LENGTH));
        state.recordSend();
    }

    void receiveUsrupdScreen(ProgramState state) {
        state.recordFileResponse(FileStatus.NORMAL, FileStatus.NO_REASON_CODE);
    }

    void populateHeaderInfo(ProgramState state) {
        DateHeader header = DateHeader.from(PICTURE_RULES, clock);
        state.setTitle01(PICTURE_RULES.movePicX(ScreenTitles.CCDA_TITLE01,
                UserUpdateResponse.TITLE01_LENGTH));
        state.setTitle02(PICTURE_RULES.movePicX(ScreenTitles.CCDA_TITLE02,
                UserUpdateResponse.TITLE02_LENGTH));
        state.setTrnName(PICTURE_RULES.movePicX(WS_TRANID,
                UserUpdateResponse.TRN_NAME_LENGTH));
        state.setPgmName(PICTURE_RULES.movePicX(WS_PGMNAME,
                UserUpdateResponse.PGM_NAME_LENGTH));
        state.setCurDate(PICTURE_RULES.movePicX(header.wsCurdateMmDdYy(),
                UserUpdateResponse.CUR_DATE_LENGTH));
        state.setCurTime(PICTURE_RULES.movePicX(header.wsCurtimeHhMmSs(),
                UserUpdateResponse.CUR_TIME_LENGTH));
    }

    void clearCurrentScreen(ProgramState state) {
        initializeAllFields(state);
        sendUsrupdScreen(state);
    }

    void initializeAllFields(ProgramState state) {
        state.requestCursorOn(ScreenField.USRIDIN);
        state.setUsrIdIn(spaces(UserUpdateResponse.USR_ID_IN_LENGTH));
        state.setFName(spaces(UserUpdateResponse.FNAME_LENGTH));
        state.setLName(spaces(UserUpdateResponse.LNAME_LENGTH));
        state.setPasswd(spaces(UserUpdateResponse.PASSWD_LENGTH));
        state.setUsrType(spaces(UserUpdateResponse.USR_TYPE_LENGTH));
        state.setWsMessage(spaces(WS_MESSAGE_LENGTH));
    }

    static boolean isSpacesOrLowValues(String image) {
        Objects.requireNonNull(image, "A field image is required; render an absent value with "
                + "receivedImage(String, int) first, which is where null becomes LOW-VALUES");
        return isEntirely(image, ' ') || isEntirely(image, '\u0000');
    }

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
     * @param value the supplied value, or {@code null} for a field that was not transmitted
     * @param length the field's declared width
     * @return an image of exactly {@code length} characters
     */
    static String receivedImage(String value, int length) {
        return value == null
                ? LOW_VALUE.repeat(length)
                : PICTURE_RULES.movePicX(value, length);
    }

    static String spaces(int length) {
        return SPACE.repeat(length);
    }

    static String displayLine(int resp, int reas) {
        return "RESP:" + nineDigitImage(resp) + "REAS:" + nineDigitImage(reas);
    }

    private static String nineDigitImage(int value) {
        if (!FileStatus.respReported(value)) {
            return FileStatus.respNotReportedImage(WS_RESP_CD_DIGITS);
        }
        String digits = Long.toString(Math.abs((long) value));
        String padded = "0".repeat(Math.max(0, WS_RESP_CD_DIGITS - digits.length())) + digits;
        return value < 0 ? "-" + padded : padded;
    }

    /**
     * One call's {@code WORKING-STORAGE}: the mutable items of {@code 01 WS-VARIABLES}, the
     * {@code SEC-USER-DATA} record area, the communication area, and the single screen buffer that
     * {@code 01 COUSR2AI} and {@code 01 COUSR2AO} share.
     */
    public static final class ProgramState {
        private String trnName;

        private String title01;

        private String curDate;

        private String pgmName;

        private String title02;

        private String curTime;

        private String usrIdIn;

        private String fName;

        private String lName;

        private String passwd;

        private String usrType;

        private String errMsg;

        private String wsMessage;

        private String wsErrFlg;

        private String wsUsrModified;

        private int wsRespCd;

        private int wsReasCd;

        private SecUserRecord secUserData;

        private Optional<SecUserRepository.HeldRecord> hold;

        private NavigationContext commarea;

        private final Cu02Info cu02Info;

        // Observable behaviour with no DFHMDF definition behind it: metadata, never payload.

        private final byte eibAid;

        private byte errMsgColour;

        private ScreenField cursorField;

        private final List<Send> sends = new ArrayList<>();

        private final List<String> displayLines = new ArrayList<>();

        private String nextProgram;

        private String returnTransid;

        private boolean transferred;

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

        public String trnName() {
            return trnName;
        }

        void setTrnName(String value) {
            this.trnName = value;
        }

        public String title01() {
            return title01;
        }

        void setTitle01(String value) {
            this.title01 = value;
        }

        public String curDate() {
            return curDate;
        }

        void setCurDate(String value) {
            this.curDate = value;
        }

        public String pgmName() {
            return pgmName;
        }

        void setPgmName(String value) {
            this.pgmName = value;
        }

        public String title02() {
            return title02;
        }

        void setTitle02(String value) {
            this.title02 = value;
        }

        public String curTime() {
            return curTime;
        }

        void setCurTime(String value) {
            this.curTime = value;
        }

        public String usrIdIn() {
            return usrIdIn;
        }

        void setUsrIdIn(String value) {
            this.usrIdIn = value;
        }

        public String fName() {
            return fName;
        }

        void setFName(String value) {
            this.fName = value;
        }

        public String lName() {
            return lName;
        }

        void setLName(String value) {
            this.lName = value;
        }

        public String passwd() {
            return passwd;
        }

        void setPasswd(String value) {
            this.passwd = value;
        }

        public String usrType() {
            return usrType;
        }

        void setUsrType(String value) {
            this.usrType = value;
        }

        public String errMsg() {
            return errMsg;
        }

        void setErrMsg(String value) {
            this.errMsg = value;
        }

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

        public String wsMessage() {
            return wsMessage;
        }

        void setWsMessage(String value) {
            this.wsMessage = value;
        }

        public boolean errFlgOn() {
            return ERR_FLG_ON.equals(wsErrFlg);
        }

        public String wsErrFlg() {
            return wsErrFlg;
        }

        void setErrFlgOn() {
            this.wsErrFlg = ERR_FLG_ON;
        }

        void setErrFlgOff() {
            this.wsErrFlg = ERR_FLG_OFF;
        }

        public boolean usrModifiedYes() {
            return USR_MODIFIED_YES.equals(wsUsrModified);
        }

        public String wsUsrModified() {
            return wsUsrModified;
        }

        void setUsrModifiedYes() {
            this.wsUsrModified = USR_MODIFIED_YES;
        }

        void setUsrModifiedNo() {
            this.wsUsrModified = USR_MODIFIED_NO;
        }

        public int wsRespCd() {
            return wsRespCd;
        }

        public int wsReasCd() {
            return wsReasCd;
        }

        void recordFileResponse(int resp, int reas) {
            this.wsRespCd = resp;
            this.wsReasCd = reas;
        }

        public SecUserRecord secUserData() {
            return secUserData;
        }

        void setSecUserData(SecUserRecord record) {
            this.secUserData = record;
        }

        void setSecUsrId(String value) {
            this.secUserData = SecUserRecord.of(value,
                    secUserData.secUsrFname(),
                    secUserData.secUsrLname(),
                    secUserData.secUsrPwd(),
                    secUserData.secUsrType(),
                    secUserData.secUsrFiller(),
                    PICTURE_RULES);
        }

        void setSecUsrFname(String value) {
            this.secUserData = SecUserRecord.of(secUserData.secUsrId(),
                    value,
                    secUserData.secUsrLname(),
                    secUserData.secUsrPwd(),
                    secUserData.secUsrType(),
                    secUserData.secUsrFiller(),
                    PICTURE_RULES);
        }

        void setSecUsrLname(String value) {
            this.secUserData = SecUserRecord.of(secUserData.secUsrId(),
                    secUserData.secUsrFname(),
                    value,
                    secUserData.secUsrPwd(),
                    secUserData.secUsrType(),
                    secUserData.secUsrFiller(),
                    PICTURE_RULES);
        }

        void setSecUsrPwd(String value) {
            this.secUserData = SecUserRecord.of(secUserData.secUsrId(),
                    secUserData.secUsrFname(),
                    secUserData.secUsrLname(),
                    value,
                    secUserData.secUsrType(),
                    secUserData.secUsrFiller(),
                    PICTURE_RULES);
        }

        void setSecUsrType(String value) {
            this.secUserData = SecUserRecord.of(secUserData.secUsrId(),
                    secUserData.secUsrFname(),
                    secUserData.secUsrLname(),
                    secUserData.secUsrPwd(),
                    value,
                    secUserData.secUsrFiller(),
                    PICTURE_RULES);
        }

        public Optional<SecUserRepository.HeldRecord> hold() {
            return hold;
        }

        void setHold(Optional<SecUserRepository.HeldRecord> hold) {
            this.hold = hold;
        }

        public NavigationContext commarea() {
            return commarea;
        }

        void setCommarea(NavigationContext commarea) {
            this.commarea = commarea;
        }

        public Cu02Info cu02Info() {
            return cu02Info;
        }

        public byte eibAid() {
            return eibAid;
        }

        public Optional<AidKey> aidKey() {
            return PfKeyResolver.resolve(eibAid);
        }

        public byte errMsgColour() {
            return errMsgColour;
        }

        public String errMsgColourMnemonic() {
            return BmsAttributes.colourMnemonic(errMsgColour);
        }

        void setErrMsgColour(byte colour) {
            this.errMsgColour = colour;
        }

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

        void requestCursorOn(ScreenField field) {
            this.cursorField = field;
        }

        /**
         * One entry per {@code EXEC CICS SEND MAP}, in order.
         *
         * @return the snapshots; unmodifiable, possibly empty, never {@code null}
         */
        public List<Send> sends() {
            return Collections.unmodifiableList(sends);
        }

        public int sendCount() {
            return sends.size();
        }

        public boolean screenSent() {
            return !sends.isEmpty();
        }

        void recordSend() {
            sends.add(new Send(response().fieldValues(), errMsgColour));
        }

        public List<String> displayLines() {
            return Collections.unmodifiableList(displayLines);
        }

        void recordDisplayLine(String line) {
            displayLines.add(line);
            LOG.info(line);
        }

        public String nextProgram() {
            return nextProgram;
        }

        public boolean transferred() {
            return transferred;
        }

        void recordTransfer(String program) {
            this.nextProgram = program;
            this.transferred = true;
        }

        public String returnTransid() {
            return returnTransid;
        }

        void recordReturn(String transid) {
            this.returnTransid = transid;
        }

        /**
         * The screen buffer as {@code COUSR2AO}, plus the navigation triple, the communication area and its
         * thirty-four-byte extension.
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
         * @return the metadata, never {@code null}
         */
        public ScreenMetadata screenMetadata() {
            return ScreenMetadata.of(cursorField().map(Enum::name).orElse(null),
                    errMsgColour,
                    false,
                    ScreenMetadata.PASSWORD_IS_NON_DISPLAY);
        }

        /**
         * How the program ended: {@link #TERMINATION_XCTL} or {@link #TERMINATION_RETURN_TRANSID}.
         *
         * @return the termination token, never {@code null}
         */
        public String termination() {
            return transferred ? TERMINATION_XCTL : TERMINATION_RETURN_TRANSID;
        }

        /**
         * The {@code xxxL} item a {@code MOVE -1} named, or {@code null} when no cursor was requested.
         *
         * @return the copybook item name, or {@code null}
         */
        public String cursorFieldName() {
            return cursorField == null ? null : cursorField.cobolName();
        }

        /**
         * A diagnostic rendering that names the user without disclosing their identity.
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
