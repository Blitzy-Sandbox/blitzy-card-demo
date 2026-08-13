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
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.dto.UserAddRequest;
import com.vsergeychik.carddemo.user.dto.UserAddResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import jakarta.validation.Valid;
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
 * {@code POST /api/users} - CICS transaction {@code CU01}, program {@code app/cbl/COUSR01C.cbl}, the Add
 * User screen.
 *
 * <p>The COBOL paragraph structure is preserved one-for-one so a reviewer can read the two side by side:
 * This program is unusually rich in code its author disabled, and reproducing any of it would be a
 * behaviour change.
 */
@RestController
public class UserAddController {
    public static final String USERS_PATH = "/api/users";

    /**
     * Query parameter carrying the raw {@code EIBAID} byte that {@code COUSR01C:90} evaluates.
     */
    public static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    public static final String EIBAID_PARAM_ALIAS = AidRequestParameter.ALTERNATE_NAME;

    public static final String EIBCALEN_PARAM = "eibcalen";

    // A page named here instead would be this class's opinion of how the dataset is stored, and an earlier
    // revision held exactly that opinion - a hard-coded US-ASCII - while application.yml binds IBM037 in
    // production.

    /**
     * {@code WS-MESSAGE PIC X(80)} - {@code COUSR01C:38}.
     */
    public static final int WS_MESSAGE_LENGTH = UserAddResponse.WS_MESSAGE_LENGTH;

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COUSR01C'} - {@code COUSR01C:36}.
     */
    public static final String WS_PGMNAME = UserAddResponse.PROGRAM_NAME;

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CU01'} - {@code COUSR01C:37}.
     */
    public static final String WS_TRANID = UserAddResponse.TRANSACTION_ID;

    public static final String MAP_NAME = UserAddResponse.MAP_NAME;

    public static final String MAPSET_NAME = UserAddResponse.MAPSET_NAME;

    /**
     * {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} - {@code COUSR01C:79} and {@code 168}.
     */
    public static final String SIGNON_PROGRAM = "COSGN00C";

    /**
     * {@code MOVE 'COADM01C' TO CDEMO-TO-PROGRAM} - {@code COUSR01C:94}, the PF3 target.
     */
    public static final String ADMIN_MENU_PROGRAM = "COADM01C";

    public static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    public static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    public static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    public static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    public static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    public static final String MSG_USER_ADDED_PREFIX = "User ";

    public static final String MSG_USER_ADDED_SUFFIX = " has been added ...";

    public static final String MSG_USER_ID_EXISTS = "User ID already exist...";

    public static final String MSG_UNABLE_TO_ADD = "Unable to Add User...";

    // Carried as the COBOL item name so a parity comparison reads FNAMEL rather than an invented ordinal.

    public static final String CURSOR_FNAME = "FNAMEL";

    public static final String CURSOR_LNAME = "LNAMEL";

    public static final String CURSOR_USERID = "USERIDL";

    public static final String CURSOR_PASSWD = "PASSWDL";

    public static final String CURSOR_USRTYPE = "USRTYPEL";

    /**
     * The value moved into an {@code xxxL} item to request the cursor.
     */
    public static final int CURSOR_REQUESTED = -1;

    public static final List<String> CURSOR_FIELDS =
            List.of(CURSOR_FNAME, CURSOR_LNAME, CURSOR_USERID, CURSOR_PASSWD, CURSOR_USRTYPE);

    private static final char SPACE = ' ';

    private static final char LOW_VALUE = '\u0000';

    private static final String SPACES_MESSAGE = String.valueOf(SPACE).repeat(WS_MESSAGE_LENGTH);

    private static final int AID_MIN = 0;

    private static final int AID_MAX = 255;

    static final int RAW_AID_LENGTH = 1;

    static final char MAX_AID_CODE_POINT = 0x00FF;

    private static final int RESP_NOT_REPORTED = FileStatus.RESP_NOT_REPORTED;

    private final SecUserRepository secUserRepository;

    private final Clock clock;

    private final FixedWidthCodec codec;

    @Autowired
    public UserAddController(SecUserRepository secUserRepository, Clock clock) {
        this.secUserRepository = Objects.requireNonNull(secUserRepository, "A SecUserRepository is "
                + "required: app/cbl/COUSR01C.cbl:240-248 issues EXEC CICS WRITE against the USRSEC "
                + "dataset, and the dataset is reached only through the repository");
        this.clock = Objects.requireNonNull(clock, "A Clock is required: FUNCTION CURRENT-DATE is read "
                + "from it at app/cbl/COUSR01C.cbl:216, never from the wall clock, so a parity case can "
                + "pin the instant and compare the header bytes");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(
                secUserRepository.datasetCharset(), "The USRSEC repository must report the code page it "
                        + "stores records in: the five values moved into SEC-USER-DATA are written to "
                        + "that dataset, and a page is never assumed"));
    }

    /**
     * {@code POST /api/users} - CICS transaction {@code CU01}.
     *
     * <p>A CICS task always has a unit of work, and the task's syncpoint at {@code RETURN} is what makes
     * that write durable - so the write and the task boundary are the same boundary.
     *
     * @param request the {@code xxxI} projection of the received map; may be {@code null}
     * @param eibAid the raw {@code EIBAID} byte as an unsigned {@code 0}-{@code 255} value under the
     *     alternate spelling this route originally declared; optional
     * @param eibcalen the communication-area length; optional, derived from the payload when absent
     * @param eibaid the same AID value under {@link AidRequestParameter#CANONICAL_NAME}, the spelling every
     *     online route shares
     * @return the {@code xxxO} projection of the map the program painted, or of the screen it transferred
     *     to
     * @throws IllegalArgumentException if the AID is outside {@code 0}-{@code 255}, if the two AID
     *     spellings disagree, or if {@code eibcalen} is negative or disagrees with what the payload carried
     */
    @PostMapping(path = USERS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ScreenResponse<UserAddResponse> addUser(
            @Valid @RequestBody(required = false) UserAddRequest request,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid,
            @RequestParam(name = EIBCALEN_PARAM, required = false) Integer eibcalen,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid) {
        ProgramState state = mainPara(request,
                resolveEibAid(AidRequestParameter.resolve(eibaid, eibAid), request),
                resolveEibcalen(eibcalen, request));
        return ScreenResponse.of(state.response(), state.screenMetadata());
    }

    /**
     * {@code MAIN-PARA} - {@code COUSR01C:71-110}, with the attention identifier and the communication-area
     * length derived from the payload.
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
     * @param request the {@code xxxI} projection of the received map; may be {@code null}, which is treated
     *     as a map where nothing was typed
     * @param eibAid the {@code EIBAID} byte the terminal presented
     * @param eibcalen the communication-area length; {@code 0} selects the sign-on transfer
     * @return the state the program ended in, carrying the response, the message, the error flag, the
     *     cursor and the message colour
     * @throws IllegalArgumentException if {@code eibcalen} is negative
     */
    public ProgramState mainPara(UserAddRequest request, byte eibAid, int eibcalen) {
        if (eibcalen < 0) {
            throw new IllegalArgumentException("EIBCALEN is a length and cannot be negative, but was "
                    + eibcalen + ". app/cbl/COUSR01C.cbl:78 tests it against zero and line 82 uses it "
                    + "as a reference-modification length, so a negative value has no meaning.");
        }

        ProgramState state = new ProgramState(codec, eibAid, eibcalen);

        state.setErrFlgOff();

        state.setMessage(SPACES_MESSAGE);
        state.setErrMsg(spaces(UserAddResponse.ERR_MSG_LENGTH));

        if (eibcalen == 0) {
            state.setCommarea(state.commarea().withToProgram(SIGNON_PROGRAM));
            returnToPrevScreen(state);
            return state;
        }

        state.setCommarea(commareaOf(request));

        if (!state.commarea().isReenter()) {
            state.setCommarea(state.commarea().withPgmReenter());
            moveLowValuesToOutputMap(state);
            state.setCursorField(CURSOR_FNAME);
            sendUsraddScreen(state);
        } else {
            receiveUsraddScreen(state, request);

            state.setAidKey(PfKeyResolver.resolve(eibAid));

            if (PfKeyResolver.isEnter(eibAid)) {
                processEnterKey(state);
            } else if (PfKeyResolver.isPf3(eibAid)) {
                state.setCommarea(state.commarea().withToProgram(ADMIN_MENU_PROGRAM));
                returnToPrevScreen(state);
                return state;
            } else if (PfKeyResolver.isPf4(eibAid)) {
                clearCurrentScreen(state);
            } else {
                state.setErrFlgOn();
                state.setCursorField(CURSOR_FNAME);
                state.setMessage(movePicXToMessage(SystemMessages.CCDA_MSG_INVALID_KEY));
                sendUsraddScreen(state);
            }
        }

        state.setReturned(true);
        return state;
    }

    private void processEnterKey(ProgramState state) {
        if (isSpacesOrLowValues(state.fName())) {
            failField(state, MSG_FIRST_NAME_EMPTY, CURSOR_FNAME);
        } else if (isSpacesOrLowValues(state.lName())) {
            failField(state, MSG_LAST_NAME_EMPTY, CURSOR_LNAME);
        } else if (isSpacesOrLowValues(state.userId())) {
            failField(state, MSG_USER_ID_EMPTY, CURSOR_USERID);
        } else if (isSpacesOrLowValues(state.passwd())) {
            failField(state, MSG_PASSWORD_EMPTY, CURSOR_PASSWD);
        } else if (isSpacesOrLowValues(state.usrType())) {
            failField(state, MSG_USER_TYPE_EMPTY, CURSOR_USRTYPE);
        } else {
            state.setCursorField(CURSOR_FNAME);
        }

        if (!state.errFlgOn()) {
            // Each screen field is exactly as wide as the record field it feeds (FNAME/LNAME X(20),
            // USERID/PASSWD X(8), USRTYPE X(1)), so these are clean same-width moves; they are still routed
            // through the codec's PIC X rule so the padding is explicit.
            state.setSecUserData(SecUserRecord.of(state.userId(),
                    state.fName(),
                    state.lName(),
                    state.passwd(),
                    state.usrType(),
                    codec.charset()));

            writeUserSecFile(state);
        }
    }

    private void failField(ProgramState state, String message, String cursorField) {
        state.setErrFlgOn();
        state.setMessage(movePicXToMessage(message));
        state.setCursorField(cursorField);
        sendUsraddScreen(state);
    }

    private void returnToPrevScreen(ProgramState state) {
        if (isSpacesOrLowValues(state.commarea().toProgram())) {
            state.setCommarea(state.commarea().withToProgram(SIGNON_PROGRAM));
        }

        state.setCommarea(state.commarea()
                .withFromTranid(WS_TRANID)
                .withFromProgram(WS_PGMNAME)
                .withPgmEnter());

        state.setNextProgram(state.commarea().toProgram());
        state.setTransferred(true);
    }

    private void sendUsraddScreen(ProgramState state) {
        populateHeaderInfo(state);

        state.setErrMsg(codec.movePicX(state.message(), UserAddResponse.ERR_MSG_LENGTH));

        state.recordSend();
    }

    private void receiveUsraddScreen(ProgramState state, UserAddRequest request) {
        if (request == null) {
            moveLowValuesToOutputMap(state);
            return;
        }

        state.setTrnName(receiveField(request.trnName(), UserAddRequest.TRNNAME_LENGTH));
        state.setTitle01(receiveField(request.title01(), UserAddRequest.TITLE01_LENGTH));
        state.setCurDate(receiveField(request.curDate(), UserAddRequest.CURDATE_LENGTH));
        state.setPgmName(receiveField(request.pgmName(), UserAddRequest.PGMNAME_LENGTH));
        state.setTitle02(receiveField(request.title02(), UserAddRequest.TITLE02_LENGTH));
        state.setCurTime(receiveField(request.curTime(), UserAddRequest.CURTIME_LENGTH));

        state.setFName(receiveField(request.fName(), UserAddRequest.FNAME_LENGTH));
        state.setLName(receiveField(request.lName(), UserAddRequest.LNAME_LENGTH));
        state.setUserId(receiveField(request.userId(), UserAddRequest.USERID_LENGTH));
        state.setPasswd(receiveField(request.passwd(), UserAddRequest.PASSWD_LENGTH));
        state.setUsrType(receiveField(request.usrType(), UserAddRequest.USRTYPE_LENGTH));

        state.setErrMsg(receiveField(request.errMsg(), UserAddResponse.ERR_MSG_LENGTH));
    }

    private String receiveField(String value, int declaredWidth) {
        if (value == null) {
            return lowValues(declaredWidth);
        }
        return codec.movePicX(value, declaredWidth);
    }

    private void populateHeaderInfo(ProgramState state) {
        DateHeader header = DateHeader.from(codec, clock);
        state.setDateHeader(header);

        state.setTitle01(codec.movePicX(ScreenTitles.CCDA_TITLE01, UserAddResponse.TITLE01_LENGTH));
        state.setTitle02(codec.movePicX(ScreenTitles.CCDA_TITLE02, UserAddResponse.TITLE02_LENGTH));
        state.setTrnName(codec.movePicX(WS_TRANID, UserAddResponse.TRN_NAME_LENGTH));
        state.setPgmName(codec.movePicX(WS_PGMNAME, UserAddResponse.PGM_NAME_LENGTH));

        state.setCurDate(codec.movePicX(header.wsCurdateMmDdYy(), UserAddResponse.CUR_DATE_LENGTH));

        state.setCurTime(codec.movePicX(header.wsCurtimeHhMmSs(), UserAddResponse.CUR_TIME_LENGTH));
    }

    private void clearCurrentScreen(ProgramState state) {
        initializeAllFields(state);
        sendUsraddScreen(state);
    }

    private void initializeAllFields(ProgramState state) {
        state.setCursorField(CURSOR_FNAME);

        state.setUserId(spaces(UserAddResponse.USER_ID_LENGTH));
        state.setFName(spaces(UserAddResponse.F_NAME_LENGTH));
        state.setLName(spaces(UserAddResponse.L_NAME_LENGTH));
        state.setPasswd(spaces(UserAddResponse.PASSWD_LENGTH));
        state.setUsrType(spaces(UserAddResponse.USR_TYPE_LENGTH));

        state.setMessage(SPACES_MESSAGE);
    }

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

    private void writeUserSecFile(ProgramState state) {
        SecUserRepository.WriteResult result = secUserRepository.add(state.requireSecUserData());

        OptionalInt reportedResp = result.response().resp();
        int respCd = reportedResp.orElse(RESP_NOT_REPORTED);
        state.setRespCd(respCd);
        state.setReasCd(result.response().resp2());
        state.setWriteStatus(result.status());

        if (respCd == FileStatus.NORMAL) {
            initializeAllFields(state);

            state.setMessage(SPACES_MESSAGE);

            state.setErrMsgColour(BmsAttributes.DFHGREEN);

            String composed = MSG_USER_ADDED_PREFIX
                    + stringDelimitedBySpace(state.requireSecUserData().secUsrId())
                    + MSG_USER_ADDED_SUFFIX;
            state.setMessage(movePicXToMessage(composed));

            state.setWroteRecord(true);

            sendUsraddScreen(state);
        } else if (respCd == FileStatus.DUPKEY || respCd == FileStatus.DUPREC) {
            state.setErrFlgOn();
            state.setMessage(movePicXToMessage(MSG_USER_ID_EXISTS));
            state.setCursorField(CURSOR_USERID);
            sendUsraddScreen(state);
        } else {
            state.setErrFlgOn();
            state.setMessage(movePicXToMessage(MSG_UNABLE_TO_ADD));
            state.setCursorField(CURSOR_FNAME);
            sendUsraddScreen(state);
        }
    }

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
     * {@code MOVE '<literal>' TO WS-MESSAGE} - the receiver is {@code PIC X(80)}, so a shorter literal is
     * padded on the right and a longer one would be truncated on the right.
     *
     * @param literal the sending literal
     * @return the literal at exactly {@link #WS_MESSAGE_LENGTH} characters
     */
    private String movePicXToMessage(String literal) {
        return codec.movePicX(literal, WS_MESSAGE_LENGTH);
    }

    private static String spaces(int width) {
        return String.valueOf(SPACE).repeat(width);
    }

    private static String lowValues(int width) {
        // One implementation of the LOW-VALUES image, in common.ScreenFieldImage, so the choice cannot
        // drift back apart across screens. Any width validation above is this method's own contract.
        return ScreenFieldImage.unpainted(width);
    }

    private static NavigationContext commareaOf(UserAddRequest request) {
        if (request == null || request.navigationContext() == null) {
            return NavigationContext.empty();
        }
        return request.navigationContext();
    }

    byte resolveEibAid(Integer eibAid, UserAddRequest request) {
        if (eibAid != null) {
            int value = eibAid;
            if (value < AID_MIN || value > AID_MAX) {
                throw ScreenInputRejectedException.outsideRange(EIBAID_PARAM,
                        "one EIBAID byte", AID_MIN, AID_MAX);
            }
            return (byte) value;
        }
        return eibAidOf(request == null ? null : request.aid());
    }

    static byte eibAidOf(String aidImage) {
        if (aidImage == null || aidImage.isBlank()
                || aidImage.chars().allMatch(character -> character == 0)) {
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

    static String dfhmdfLabel(String lengthItem) {
        return lengthItem.substring(0, lengthItem.length() - 1);
    }

    static int resolveEibcalen(Integer eibcalen, UserAddRequest request) {
        boolean carried = request != null && request.navigationContext() != null;
        if (eibcalen == null) {
            return carried ? NavigationContext.COMMAREA_LENGTH : 0;
        }
        int stated = eibcalen;
        if (stated < 0) {
            throw new IllegalArgumentException("The " + EIBCALEN_PARAM + " parameter is " + stated
                    + ", and EIBCALEN is the length of the area CICS passed, which cannot be negative.");
        }
        if ((stated == 0) == carried) {
            throw new IllegalArgumentException("The " + EIBCALEN_PARAM + " parameter says " + stated
                    + " but the payload carries " + (carried ? "a" : "no")
                    + " communication area. EIBCALEN describes what arrived; it cannot contradict it, "
                    + "because app/cbl/COUSR01C.cbl:78 uses it to decide whether the conversation had any "
                    + "state at all.");
        }
        return stated;
    }

    /**
     * The {@code WORKING-STORAGE} of a single execution of {@code COUSR01C}.
     */
    public static final class ProgramState {
        private final FixedWidthCodec codec;

        private final byte eibAid;

        private final int eibcalen;

        private String message = SPACES_MESSAGE;

        private boolean errFlg;

        private int respCd;

        private int reasCd;

        private String writeStatus;

        private NavigationContext commarea = NavigationContext.empty();

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

        private String cursorField;

        private byte errMsgColour = BmsAttributes.DFHDFCOL;

        private SecUserRecord secUserData;

        private Optional<PfKeyResolver.AidKey> aidKey = Optional.empty();

        private DateHeader dateHeader;

        private String nextProgram;

        private int sendCount;

        private boolean transferred;

        private boolean returned;

        private boolean wroteRecord;

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

        /**
         * The map as {@code EXEC CICS SEND} would have transmitted it, projected onto the payload contract.
         *
         * <p>The cursor and the message colour are deliberately absent - they are {@code xxxL} and
         * {@code xxxC} metadata, and putting them on the wire would break the rule that every payload field
         * traces to a name-labelled {@code DFHMDF}.
         *
         * @return the response payload
         */
        public UserAddResponse response() {
            String responseNextMapset = transferred
                    ? String.valueOf(SPACE).repeat(UserAddResponse.NEXT_MAPSET_LENGTH)
                    : MAPSET_NAME;
            String responseNextMap = transferred
                    ? String.valueOf(SPACE).repeat(UserAddResponse.NEXT_MAP_LENGTH)
                    : MAP_NAME;
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

        public String message() {
            return message;
        }

        void setMessage(String message) {
            this.message = codec.movePicX(message, WS_MESSAGE_LENGTH);
        }

        public boolean errFlgOn() {
            return errFlg;
        }

        public boolean errFlgOff() {
            return !errFlg;
        }

        void setErrFlgOn() {
            this.errFlg = true;
        }

        void setErrFlgOff() {
            this.errFlg = false;
        }

        public int respCd() {
            return respCd;
        }

        void setRespCd(int respCd) {
            this.respCd = respCd;
        }

        public int reasCd() {
            return reasCd;
        }

        void setReasCd(int reasCd) {
            this.reasCd = reasCd;
        }

        public Optional<String> writeStatus() {
            return Optional.ofNullable(writeStatus);
        }

        void setWriteStatus(String writeStatus) {
            this.writeStatus = writeStatus;
        }

        public NavigationContext commarea() {
            return commarea;
        }

        void setCommarea(NavigationContext commarea) {
            this.commarea = Objects.requireNonNull(commarea, "The communication area is never null; an "
                    + "absent one is NavigationContext.empty()");
        }

        public byte eibAid() {
            return eibAid;
        }

        public int eibcalen() {
            return eibcalen;
        }

        public Optional<PfKeyResolver.AidKey> aidKey() {
            return aidKey;
        }

        void setAidKey(Optional<PfKeyResolver.AidKey> aidKey) {
            this.aidKey = Objects.requireNonNull(aidKey, "An unresolved attention identifier is an "
                    + "empty Optional, never null");
        }

        public String trnName() {
            return trnName;
        }

        void setTrnName(String trnName) {
            this.trnName = trnName;
        }

        public String title01() {
            return title01;
        }

        void setTitle01(String title01) {
            this.title01 = title01;
        }

        public String curDate() {
            return curDate;
        }

        void setCurDate(String curDate) {
            this.curDate = curDate;
        }

        public String pgmName() {
            return pgmName;
        }

        void setPgmName(String pgmName) {
            this.pgmName = pgmName;
        }

        public String title02() {
            return title02;
        }

        void setTitle02(String title02) {
            this.title02 = title02;
        }

        public String curTime() {
            return curTime;
        }

        void setCurTime(String curTime) {
            this.curTime = curTime;
        }

        public String fName() {
            return fName;
        }

        void setFName(String fName) {
            this.fName = fName;
        }

        public String lName() {
            return lName;
        }

        void setLName(String lName) {
            this.lName = lName;
        }

        public String userId() {
            return userId;
        }

        void setUserId(String userId) {
            this.userId = userId;
        }

        /**
         * {@code PASSWDI} / {@code PASSWDO}, verbatim and never hashed.
         *
         * @return the typed password at its declared width
         */
        public String passwd() {
            return passwd;
        }

        void setPasswd(String passwd) {
            this.passwd = passwd;
        }

        public String usrType() {
            return usrType;
        }

        void setUsrType(String usrType) {
            this.usrType = usrType;
        }

        public String errMsg() {
            return errMsg;
        }

        void setErrMsg(String errMsg) {
            this.errMsg = errMsg;
        }

        public Optional<String> cursorField() {
            return Optional.ofNullable(cursorField);
        }

        /**
         * This screen's presentation metadata, in the shared envelope every online response publishes.
         *
         * @return the metadata, never {@code null}
         */
        public ScreenMetadata screenMetadata() {
            return ScreenMetadata.of(cursorField().map(UserAddController::dfhmdfLabel).orElse(null),
                    errMsgColour(),
                    false,
                    ScreenMetadata.PASSWORD_IS_NON_DISPLAY);
        }

        void setCursorField(String cursorField) {
            this.cursorField = cursorField;
        }

        public byte errMsgColour() {
            return errMsgColour;
        }

        void setErrMsgColour(byte errMsgColour) {
            this.errMsgColour = errMsgColour;
        }

        public Optional<SecUserRecord> secUserData() {
            return Optional.ofNullable(secUserData);
        }

        void setSecUserData(SecUserRecord secUserData) {
            this.secUserData = secUserData;
        }

        SecUserRecord requireSecUserData() {
            if (secUserData == null) {
                throw new IllegalStateException("SEC-USER-DATA has not been built. app/cbl/COUSR01C.cbl"
                        + ":159 performs WRITE-USER-SEC-FILE only inside IF NOT ERR-FLG-ON at line 153, "
                        + "after the five moves at lines 154-158, so reaching the write without a record "
                        + "would mean that guard was bypassed.");
            }
            return secUserData;
        }

        public Optional<DateHeader> dateHeader() {
            return Optional.ofNullable(dateHeader);
        }

        void setDateHeader(DateHeader dateHeader) {
            this.dateHeader = dateHeader;
        }

        public int sendCount() {
            return sendCount;
        }

        public boolean screenSent() {
            return sendCount > 0;
        }

        void recordSend() {
            this.sendCount++;
        }

        public boolean transferred() {
            return transferred;
        }

        void setTransferred(boolean transferred) {
            this.transferred = transferred;
        }

        public boolean returned() {
            return returned;
        }

        void setReturned(boolean returned) {
            this.returned = returned;
        }

        void setNextProgram(String nextProgram) {
            this.nextProgram = nextProgram;
        }

        public boolean wroteRecord() {
            return wroteRecord;
        }

        void setWroteRecord(boolean wroteRecord) {
            this.wroteRecord = wroteRecord;
        }
    }
}
