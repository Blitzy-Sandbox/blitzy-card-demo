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
 * The stateless delete-user screen: a like-for-like migration of {@code app/cbl/COUSR03C.cbl} (359 lines),
 * CSD transaction {@link #WS_TRANID} [{@code app/csd/CARDDEMO.CSD:479-480}], exposed as
 * {@code DELETE}{@link #USERS_PATH}.
 *
 * <p>Every item of {@code WORKING-STORAGE} - {@code WS-ERR-FLG}, {@code WS-MESSAGE}, {@code WS-RESP-CD},
 * {@code WS-REAS-CD}, {@code WS-USR-MODIFIED} and {@code SEC-USER-DATA} - lives on a per-request
 * {@link ProgramState} and never on this class, so two concurrent requests cannot see each other's screen.
 */
@RestController
public class UserDeleteController {
    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COUSR03C'} - {@code app/cbl/COUSR03C.cbl:36}.
     */
    public static final String WS_PGMNAME = UserDeleteRequest.PROGRAM_NAME;

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CU03'} - {@code app/cbl/COUSR03C.cbl:37}.
     */
    public static final String WS_TRANID = UserDeleteRequest.TRANSACTION_ID;

    public static final String WS_MAP = UserDeleteRequest.MAP_NAME;

    /**
     * The BMS mapset: {@code COUSR03}, named at lines 221 and 234.
     */
    public static final String WS_MAPSET = UserDeleteRequest.MAPSET_NAME;

    public static final String USERS_PATH = "/api/users/{userId}";

    public static final String USER_ID_VARIABLE = "userId";

    /**
     * The query parameter carrying the raw {@code EIBAID} byte as an unsigned {@code 0}-{@code 255} value.
     */
    public static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    public static final String EIBAID_PARAM_ALIAS = AidRequestParameter.ALTERNATE_NAME;

    static final int AID_MIN = 0;

    static final int AID_MAX = 255;

    static final int RAW_AID_LENGTH = 1;

    static final char MAX_AID_CODE_POINT = 0x00FF;

    /**
     * {@code 'COSGN00C'} - the sign-on program, moved into {@code CDEMO-TO-PROGRAM} at line 91 when
     * {@code EIBCALEN = 0} and again at line 200 when the target is otherwise unset.
     */
    public static final String LIT_SIGNON_PGM = "COSGN00C";

    /**
     * {@code 'COADM01C'} - the administrator menu, moved into {@code CDEMO-TO-PROGRAM} at line 113 when PF3
     * has no caller to return to, and unconditionally at line 124 on PF12.
     */
    public static final String LIT_ADMIN_PGM = "COADM01C";

    /**
     * {@code 'User ID can NOT be empty...'} - moved into {@code WS-MESSAGE} at line 147 on the ENTER path
     * and at line 179 on the PF5 path.
     */
    public static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    public static final String MSG_PRESS_PF5 = "Press PF5 key to delete this user ...";

    public static final String MSG_USER_ID_NOT_FOUND = "User ID NOT found...";

    public static final String MSG_UNABLE_TO_LOOKUP_USER = "Unable to lookup User...";

    public static final String MSG_UNABLE_TO_UPDATE_USER = "Unable to Update User...";

    public static final String MSG_USER_PREFIX = "User ";

    public static final String MSG_HAS_BEEN_DELETED_SUFFIX = " has been deleted ...";

    public static final String DISPLAY_RESP_PREFIX = "RESP:";

    public static final String DISPLAY_REAS_PREFIX = "REAS:";

    /**
     * The digit width of {@code WS-RESP-CD} and {@code WS-REAS-CD}, both {@code PIC S9(09) COMP} -
     * {@code app/cbl/COUSR03C.cbl:43-44}.
     */
    public static final int WS_RESP_CD_DIGITS = 9;

    // Every one is read from the type that owns the declaration rather than restated here, so a single
    // copybook value can never disagree with itself across the module.

    /**
     * {@code WS-MESSAGE PIC X(80) VALUE SPACES} - {@code app/cbl/COUSR03C.cbl:38}.
     */
    public static final int WS_MESSAGE_LENGTH = UserDeleteResponse.WS_MESSAGE_LENGTH;

    /**
     * {@code ERRMSGO PIC X(78)} - {@code app/cpy-bms/COUSR03.CPY:152}.
     */
    public static final int ERR_MSG_LENGTH = UserDeleteResponse.ERR_MSG_LENGTH;

    /**
     * {@code USRIDINI PIC X(8)} - {@code app/cpy-bms/COUSR03.CPY:60}.
     */
    public static final int USR_ID_IN_LENGTH = UserDeleteRequest.USRIDIN_LENGTH;

    static final String USRIDIN_MEMBER = "usridin";

    static final String AID_MEMBER = "aid";

    /**
     * {@code FNAMEI PIC X(20)} - {@code app/cpy-bms/COUSR03.CPY:66}.
     */
    public static final int F_NAME_LENGTH = UserDeleteRequest.FNAME_LENGTH;

    /**
     * {@code LNAMEI PIC X(20)} - {@code app/cpy-bms/COUSR03.CPY:72}.
     */
    public static final int L_NAME_LENGTH = UserDeleteRequest.LNAME_LENGTH;

    /**
     * {@code USRTYPEI PIC X(1)} - {@code app/cpy-bms/COUSR03.CPY:78}.
     */
    public static final int USR_TYPE_LENGTH = UserDeleteRequest.USRTYPE_LENGTH;

    /**
     * {@code TRNNAMEI PIC X(4)} - {@code app/cpy-bms/COUSR03.CPY:24}.
     */
    public static final int TRN_NAME_LENGTH = UserDeleteRequest.TRNNAME_LENGTH;

    /**
     * {@code PGMNAMEI PIC X(8)} - {@code app/cpy-bms/COUSR03.CPY:42}.
     */
    public static final int PGM_NAME_LENGTH = UserDeleteRequest.PGMNAME_LENGTH;

    /**
     * {@code TITLE01I} and {@code TITLE02I}, both {@code PIC X(40)}.
     */
    public static final int TITLE_LENGTH = ScreenTitles.TITLE_LENGTH;

    /**
     * {@code CURDATEI PIC X(8)} - the {@code MM/DD/YY} header field.
     */
    public static final int CUR_DATE_LENGTH = UserDeleteRequest.CURDATE_LENGTH;

    /**
     * {@code CURTIMEI PIC X(8)} - the {@code HH:MM:SS} header field.
     */
    public static final int CUR_TIME_LENGTH = UserDeleteRequest.CURTIME_LENGTH;

    /**
     * {@code SEC-USR-ID PIC X(08)} - {@code app/cpy/CSUSR01Y.cpy:18}, and the {@code USRSEC} key.
     */
    public static final int SEC_USR_ID_LENGTH = SecUserRecord.SEC_USR_ID_LENGTH;

    /**
     * {@code CDEMO-TO-PROGRAM PIC X(08)} - {@code app/cpy/COCOM01Y.cpy:24}.
     */
    public static final int TO_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /**
     * {@code CDEMO-FROM-TRANID PIC X(04)} - {@code app/cpy/COCOM01Y.cpy:21}.
     */
    public static final int FROM_TRANID_LENGTH = NavigationContext.FROM_TRANID_LENGTH;

    /**
     * {@code CDEMO-FROM-PROGRAM PIC X(08)} - {@code app/cpy/COCOM01Y.cpy:22}.
     */
    public static final int FROM_PROGRAM_LENGTH = NavigationContext.FROM_PROGRAM_LENGTH;

    /**
     * {@code CDEMO-LAST-MAP PIC X(7)} - the width the navigation triple's map field carries.
     */
    public static final int NEXT_MAP_LENGTH = UserDeleteResponse.NEXT_MAP_LENGTH;

    /**
     * {@code CDEMO-LAST-MAPSET PIC X(7)} - the width the navigation triple's mapset field carries.
     */
    public static final int NEXT_MAPSET_LENGTH = UserDeleteResponse.NEXT_MAPSET_LENGTH;

    private static final String SPACE = " ";

    private static final char LOW_VALUE = '\u0000';

    private static final Log LOG = LogFactory.getLog(UserDeleteController.class);

    private final SecUserRepository secUserRepository;

    private final Clock clock;

    private final FixedWidthCodec codec;

    public UserDeleteController(SecUserRepository secUserRepository, Clock clock) {
        this.secUserRepository = Objects.requireNonNull(secUserRepository,
                "A SecUserRepository is required: COUSR03C reads USRSEC for update at lines 269-278 and "
                        + "deletes the held record at lines 307-311, and reaches the dataset no other way");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: POPULATE-HEADER-INFO reads FUNCTION CURRENT-DATE at line 245, and "
                        + "reading a clock inline would make every parity case non-deterministic");
        this.codec = new FixedWidthCodec(secUserRepository.datasetCharset());
    }

    FixedWidthCodec codec() {
        return codec;
    }

    /**
     * {@code DELETE}{@link #USERS_PATH} - the whole of transaction {@link #WS_TRANID}.
     *
     * @param userId the user id from the path; the resource's identity, and the value both {@code USRIDIN}
     *     and {@code CDEMO-CU03-USR-SELECTED} carry once bound {@code COUSR03C} tests {@code EIBAID} inline and
     *     does not
     * @param request the inbound screen, validated against the symbolic map's declared widths; {@code null}
     *     when no body was sent, which is {@code EIBCALEN = 0}
     * @param eibaid {@code EIBAID} as an unsigned {@code 0}-{@code 255} byte under {@link #EIBAID_PARAM},
     *     optional
     * @param eibAid the same value under {@link #EIBAID_PARAM_ALIAS}; at most one need be sent
     * @return the outbound screen - the eleven map fields, the communication area and the navigation
     *     triple; never {@code null}
     * @throws NullPointerException if {@code userId} is {@code null}
     * @throws IllegalArgumentException if {@code userId} is wider than {@code USRIDIN}, if the stated AID
     *     byte is outside {@code 0}-{@code 255}, or if both spellings are present and disagree
     */
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
     * @throws IllegalArgumentException if it is wider than {@link #USR_ID_IN_LENGTH} characters
     */
    static void requireIdentityFits(String userId) {
        if (userId.length() > USR_ID_IN_LENGTH) {
            throw ScreenInputRejectedException.tooWide(USRIDIN_MEMBER,
                    "USRIDINI PIC X(" + USR_ID_IN_LENGTH + ") and SEC-USR-ID PIC X("
                            + USR_ID_IN_LENGTH + ")",
                    USR_ID_IN_LENGTH, userId.length());
        }
    }

    UserDeleteRequest bindPathIdentity(String userId, UserDeleteRequest request) {
        if (request == null) {
            return null;
        }
        ScreenInputRejectedException.requireKeyAgreement(USRIDIN_MEMBER, userId, request.usrIdIn(),
                USR_ID_IN_LENGTH, codec);
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

    /**
     * Reads the raw {@code EIBAID} byte out of the payload's {@code aid} member.
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

    byte resolveEibAid(Integer eibaid, UserDeleteRequest request) {
        String aidImage = request == null ? null : request.aid();
        if (eibaid == null) {
            return eibAidOf(aidImage);
        }
        return AidRequestParameter.requireStatedAid(AID_MEMBER, eibaid, aidImage, codec);
    }

    /**
     * {@code MAIN-PARA} - the program's entry point, lines 82 to 137, driven by a raw {@code EIBAID} byte -
     * the form the source itself is written in.
     *
     * @param request the inbound screen, or {@code null} for {@code EIBCALEN = 0}
     * @param eibAid the raw EBCDIC attention-identifier byte, as CICS places it in {@code EIBAID}
     * @param usrSelected {@code CDEMO-CU03-USR-SELECTED}; consulted on first entry only, and may be
     *     {@code null}
     * @return the state at the moment the task returned to CICS or transferred; never {@code null}
     */
    public ProgramState mainPara(UserDeleteRequest request, byte eibAid, String usrSelected) {
        ProgramState state = new ProgramState();

        state.setCu03Info(request == null ? Cu03Info.initial() : request.cu03Info());

        state.setErrFlagOff();

        state.setUsrModifiedNo();

        state.setMessage(spaces(WS_MESSAGE_LENGTH));
        state.setResponse(state.response().withErrMsg(spaces(ERR_MSG_LENGTH)));

        if (request == null || request.navigationContext() == null) {
            state.setCommarea(state.commarea()
                    .withToProgram(codec.movePicX(LIT_SIGNON_PGM, TO_PROGRAM_LENGTH)));
            returnToPrevScreen(state);
            return returnToCics(state);
        }

        state.setCommarea(request.navigationContext());

        if (!state.commarea().isReenter()) {
            firstEntry(state, usrSelected);
        } else {
            receiveUsrdelScreen(state, request);
            dispatchAid(state, eibAid);
        }
        return returnToCics(state);
    }

    private void firstEntry(ProgramState state, String usrSelected) {
        state.setCommarea(state.commarea().withPgmReenter());

        state.setResponse(UserDeleteResponse.empty());

        state.moveMinusOneTo(CursorField.USRIDINL);

        if (!isSpacesOrLowValues(usrSelected)) {
            state.setResponse(state.response()
                    .withUsrIdIn(codec.movePicX(usrSelected, USR_ID_IN_LENGTH)));
            processEnterKey(state);
        }

        // L105 PERFORM SEND-USRDEL-SCREEN - outside the guard, and so always performed.
        sendUsrdelScreen(state);
    }

    private void dispatchAid(ProgramState state, byte eibAid) {
        if (PfKeyResolver.isEnter(eibAid)) {
            processEnterKey(state);
            return;
        }

        // There is deliberately NO delete here: COUSR02C performs UPDATE-USER-INFO on its own PF3 arm at
        // lines 111-119, and this program does not. The asymmetry is the source's, and it stands.
        if (PfKeyResolver.isPf3(eibAid)) {
            if (isSpacesOrLowValues(state.commarea().fromProgram())) {
                state.setCommarea(state.commarea()
                        .withToProgram(codec.movePicX(LIT_ADMIN_PGM, TO_PROGRAM_LENGTH)));
            } else {
                state.setCommarea(state.commarea()
                        .withToProgram(codec.movePicX(state.commarea().fromProgram(),
                                TO_PROGRAM_LENGTH)));
            }
            returnToPrevScreen(state);
            return;
        }

        if (PfKeyResolver.isPf4(eibAid)) {
            clearCurrentScreen(state);
            return;
        }

        if (PfKeyResolver.isPf5(eibAid)) {
            deleteUserInfo(state);
            return;
        }

        if (PfKeyResolver.isPf12(eibAid)) {
            state.setCommarea(state.commarea()
                    .withToProgram(codec.movePicX(LIT_ADMIN_PGM, TO_PROGRAM_LENGTH)));
            returnToPrevScreen(state);
            return;
        }

        invalidKey(state);
    }

    private void invalidKey(ProgramState state) {
        state.setErrFlagOn();
        state.setMessage(codec.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH));
        sendUsrdelScreen(state);
    }

    /**
     * {@code PROCESS-ENTER-KEY} - the fetch that paints the record for confirmation, lines 142 to 169.
     *
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void processEnterKey(ProgramState state) {
        requireState(state);

        if (isSpacesOrLowValues(state.response().usrIdIn())) {
            state.setErrFlagOn();
            state.setMessage(codec.movePicX(MSG_USER_ID_EMPTY, WS_MESSAGE_LENGTH));
            state.moveMinusOneTo(CursorField.USRIDINL);
            sendUsrdelScreen(state);
        } else {
            state.moveMinusOneTo(CursorField.USRIDINL);
        }

        if (!state.isErrFlagOn()) {
            state.setResponse(state.response()
                    .withFName(spaces(F_NAME_LENGTH))
                    .withLName(spaces(L_NAME_LENGTH))
                    .withUsrType(spaces(USR_TYPE_LENGTH)));
            state.moveToSecUsrId(codec.movePicX(state.response().usrIdIn(), SEC_USR_ID_LENGTH));
            readUserSecFile(state);
        }

        if (!state.isErrFlagOn()) {
            SecUserRecord record = state.secUserData();
            state.setResponse(state.response()
                    .withFName(codec.movePicX(record.secUsrFname(), F_NAME_LENGTH))
                    .withLName(codec.movePicX(record.secUsrLname(), L_NAME_LENGTH))
                    .withUsrType(codec.movePicX(record.secUsrType(), USR_TYPE_LENGTH)));
            sendUsrdelScreen(state);
        }

        // SEC-USR-PWD is deliberately not read, not painted and not logged.
    }

    /**
     * {@code DELETE-USER-INFO} - the PF5 confirm path, lines 174 to 192.
     *
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void deleteUserInfo(ProgramState state) {
        requireState(state);

        if (isSpacesOrLowValues(state.response().usrIdIn())) {
            state.setErrFlagOn();
            state.setMessage(codec.movePicX(MSG_USER_ID_EMPTY, WS_MESSAGE_LENGTH));
            state.moveMinusOneTo(CursorField.USRIDINL);
            sendUsrdelScreen(state);
        } else {
            state.moveMinusOneTo(CursorField.USRIDINL);
        }

        if (!state.isErrFlagOn()) {
            state.moveToSecUsrId(codec.movePicX(state.response().usrIdIn(), SEC_USR_ID_LENGTH));
            readUserSecFile(state);
            deleteUserSecFile(state);
        }
    }

    /**
     * {@code READ-USER-SEC-FILE} - the locking read, lines 267 to 300.
     *
     * @param state the per-request working storage; {@code SEC-USR-ID} must already hold the key
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void readUserSecFile(ProgramState state) {
        requireState(state);

        ReadResult read = secUserRepository.readForUpdate(state.secUserData().secUsrId());

        read.record().ifPresent(state::setSecUserData);

        state.holdRecord(read.hold());

        state.setRespCd(read.cicsResp()
                .orElse(classifiedResp(read.isFound(), read.isNotFound())));
        state.setReasCd(read.cicsResp2());

        if (read.isFound()) {
            state.setMessage(codec.movePicX(MSG_PRESS_PF5, WS_MESSAGE_LENGTH));
            state.setErrMsgColour(BmsAttributes.DFHNEUTR);
            sendUsrdelScreen(state);
        } else if (read.isNotFound()) {
            state.setErrFlagOn();
            state.setMessage(codec.movePicX(MSG_USER_ID_NOT_FOUND, WS_MESSAGE_LENGTH));
            state.moveMinusOneTo(CursorField.USRIDINL);
            sendUsrdelScreen(state);
        } else {
            display(state, respDisplayLine(state));
            state.setErrFlagOn();
            state.setMessage(codec.movePicX(MSG_UNABLE_TO_LOOKUP_USER, WS_MESSAGE_LENGTH));
            state.moveMinusOneTo(CursorField.FNAMEL);
            sendUsrdelScreen(state);
        }
    }

    /**
     * {@code DELETE-USER-SEC-FILE} - the keyless delete, lines 305 to 336.
     *
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void deleteUserSecFile(ProgramState state) {
        requireState(state);

        Optional<HeldRecord> held = state.heldRecord();
        if (held.isEmpty()) {
            state.setRespCd(FileStatus.INVREQ);
            state.setReasCd(FileStatus.NO_REASON_CODE);
            deleteWhenOther(state);
            return;
        }

        WriteResult deleted = held.get().deleteHeld();

        state.setRespCd(deleted.cicsResp()
                .orElse(classifiedResp(deleted.isWritten(), deleted.isNotFound())));
        state.setReasCd(deleted.cicsResp2());

        if (deleted.isWritten()) {
            state.releaseHold();
            deleteNormal(state);
        } else if (deleted.isNotFound()) {
            deleteNotFound(state);
        } else {
            deleteWhenOther(state);
        }
    }

    private void deleteNormal(ProgramState state) {
        initializeAllFields(state);

        state.setMessage(spaces(WS_MESSAGE_LENGTH));

        state.setErrMsgColour(BmsAttributes.DFHGREEN);

        state.setMessage(codec.movePicX(
                codec.concatenateDelimitedBySize(MSG_USER_PREFIX,
                        delimitedBySpace(state.secUserData().secUsrId()),
                        MSG_HAS_BEEN_DELETED_SUFFIX),
                WS_MESSAGE_LENGTH));

        sendUsrdelScreen(state);
    }

    private void deleteNotFound(ProgramState state) {
        state.setErrFlagOn();
        state.setMessage(codec.movePicX(MSG_USER_ID_NOT_FOUND, WS_MESSAGE_LENGTH));
        state.moveMinusOneTo(CursorField.USRIDINL);
        sendUsrdelScreen(state);
    }

    private void deleteWhenOther(ProgramState state) {
        display(state, respDisplayLine(state));
        state.setErrFlagOn();
        state.setMessage(codec.movePicX(MSG_UNABLE_TO_UPDATE_USER, WS_MESSAGE_LENGTH));
        state.moveMinusOneTo(CursorField.FNAMEL);
        sendUsrdelScreen(state);
    }

    /**
     * {@code RETURN-TO-PREV-SCREEN} - the transfer of control, lines 197 to 208.
     *
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void returnToPrevScreen(ProgramState state) {
        requireState(state);

        if (isSpacesOrLowValues(state.commarea().toProgram())) {
            state.setCommarea(state.commarea()
                    .withToProgram(codec.movePicX(LIT_SIGNON_PGM, TO_PROGRAM_LENGTH)));
        }

        state.setCommarea(state.commarea()
                .withFromTranid(codec.movePicX(WS_TRANID, FROM_TRANID_LENGTH))
                .withFromProgram(codec.movePicX(WS_PGMNAME, FROM_PROGRAM_LENGTH))
                .withPgmEnter());

        state.setResponse(state.response()
                .withNextProgram(state.commarea().toProgram())
                .withNavigationContext(state.commarea())
                .withCu03Info(state.cu03Info()));
        state.markTransferred();
    }

    /**
     * {@code SEND-USRDEL-SCREEN} - lines 213 to 225.
     *
     * <p>{@code WS-MESSAGE} is {@code PIC X(80)} and {@code ERRMSGO} is {@code PIC X(78)}, so line 217 is a
     * truncating move.
     *
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void sendUsrdelScreen(ProgramState state) {
        requireState(state);

        populateHeaderInfo(state);

        state.setResponse(state.response().withErrMsg(codec.movePicX(state.message(), ERR_MSG_LENGTH)));

        state.recordSend();
    }

    /**
     * {@code RECEIVE-USRDEL-SCREEN} - lines 230 to 238.
     *
     * @param state the per-request working storage
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

        state.setRespCd(FileStatus.NORMAL);
        state.setReasCd(FileStatus.NO_REASON_CODE);
    }

    /**
     * {@code POPULATE-HEADER-INFO} - the six header fields, lines 243 to 262.
     *
     * <p>{@link DateHeader} owns that assembly - including taking the two low-order digits of the year for
     * the two-digit field, which line 254 does as {@code WS-CURDATE-YEAR(3:2)} - and it takes the injected
     * {@link Clock}, so nothing here calls a clock directly and every parity case is reproducible.
     *
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void populateHeaderInfo(ProgramState state) {
        requireState(state);

        DateHeader header = DateHeader.from(codec, clock);
        state.setDateHeader(header);

        state.setResponse(state.response()
                .withTitle01(codec.movePicX(ScreenTitles.CCDA_TITLE01, TITLE_LENGTH))
                .withTitle02(codec.movePicX(ScreenTitles.CCDA_TITLE02, TITLE_LENGTH))
                .withTrnName(codec.movePicX(WS_TRANID, TRN_NAME_LENGTH))
                .withPgmName(codec.movePicX(WS_PGMNAME, PGM_NAME_LENGTH))
                .withCurDate(codec.movePicX(header.wsCurdateMmDdYy(), CUR_DATE_LENGTH))
                .withCurTime(codec.movePicX(header.wsCurtimeHhMmSs(), CUR_TIME_LENGTH)));
    }

    /**
     * {@code CLEAR-CURRENT-SCREEN} - lines 341 to 344, the PF4 arm.
     *
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void clearCurrentScreen(ProgramState state) {
        requireState(state);
        initializeAllFields(state);
        sendUsrdelScreen(state);
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS} - lines 349 to 356.
     *
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void initializeAllFields(ProgramState state) {
        requireState(state);

        state.moveMinusOneTo(CursorField.USRIDINL);

        state.setResponse(state.response()
                .withUsrIdIn(spaces(USR_ID_IN_LENGTH))
                .withFName(spaces(F_NAME_LENGTH))
                .withLName(spaces(L_NAME_LENGTH))
                .withUsrType(spaces(USR_TYPE_LENGTH)));

        state.setMessage(spaces(WS_MESSAGE_LENGTH));
    }

    private ProgramState returnToCics(ProgramState state) {
        if (state.isTransferred()) {
            return state;
        }
        state.setResponse(state.response()
                .withNavigationContext(state.commarea())
                .withCu03Info(state.cu03Info())
                .withNextProgram(codec.movePicX(WS_PGMNAME, TO_PROGRAM_LENGTH))
                .withNextMapset(codec.movePicX(WS_MAPSET, NEXT_MAPSET_LENGTH))
                .withNextMap(codec.movePicX(WS_MAP, NEXT_MAP_LENGTH)));
        state.markReturned();
        return state;
    }

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

    static String delimitedBySpace(String value) {
        Objects.requireNonNull(value, "A sending item is required for STRING ... DELIMITED BY SPACE");
        int firstSpace = value.indexOf(' ');
        if (firstSpace < 0) {
            return value;
        }
        return value.substring(0, firstSpace);
    }

    private static String spaces(int width) {
        return SPACE.repeat(width);
    }

    private String receivedField(String value, int width) {
        if (value == null) {
            return spaces(width);
        }
        return codec.movePicX(value, width);
    }

    private static int classifiedResp(boolean normal, boolean notFound) {
        if (normal) {
            return FileStatus.NORMAL;
        }
        if (notFound) {
            return FileStatus.NOTFND;
        }
        return FileStatus.INVREQ;
    }

    private String respDisplayLine(ProgramState state) {
        return DISPLAY_RESP_PREFIX + codec.movePic9(state.respCd(), WS_RESP_CD_DIGITS)
                + DISPLAY_REAS_PREFIX + codec.movePic9(state.reasCd(), WS_RESP_CD_DIGITS);
    }

    private void display(ProgramState state, String text) {
        state.recordDisplay(text);
        LOG.info(text);
    }

    private static void requireState(ProgramState state) {
        Objects.requireNonNull(state, "A ProgramState is required: every WORKING-STORAGE item of "
                + "COUSR03C lives in it, so that two concurrent requests cannot see each other's screen");
    }

    /**
     * The two screen fields {@code COUSR03C} ever asks the cursor to land on.
     */
    public enum CursorField {
        /**
         * {@code USRIDINL} - {@code app/cpy-bms/COUSR03.CPY:55}, the user-id field's length item.
         */
        USRIDINL("USRIDINL"),

        /**
         * {@code FNAMEL} - {@code app/cpy-bms/COUSR03.CPY:61}, the first-name field's length item.
         */
        FNAMEL("FNAMEL");

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
         * @return the label; never {@code null}
         */
        public String dfhmdfLabel() {
            return lengthItem.substring(0, lengthItem.length() - 1);
        }
    }

    /**
     * Every mutable item {@code COUSR03C} declares, for the life of one request.
     */
    public static final String ERR_MSG_COLOUR_ITEM = "ERRMSGC";

    /**
     * One {@code EXEC CICS SEND MAP}: what the map area held at that moment.
     *
     * @param fields the eleven {@code xxxO} values at this send; unmodifiable
     * @param errMsgColour {@code ERRMSGC OF COUSR3AO} at this send
     */
    public record Send(Map<String, String> fields, byte errMsgColour) {
        public Send {
            Objects.requireNonNull(fields, "A send carries the field values it painted; use an empty map "
                    + "for none rather than null");
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        /**
         * The colour byte's copybook mnemonic.
         *
         * @return {@code DFHNEUTR}, {@code DFHRED}, {@code DFHGREEN} or {@code DFHDFCOL}; never
         *     {@code null}
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

    public static final class ProgramState {
        private UserDeleteResponse response = UserDeleteResponse.empty();

        private NavigationContext commarea = NavigationContext.empty();

        private String message = spaces(WS_MESSAGE_LENGTH);

        private boolean errFlag;

        private boolean usrModified;

        private int respCd;

        private int reasCd;

        private SecUserRecord secUserData = SecUserRecord.blank();

        private HeldRecord heldRecord;

        private CursorField cursorField;

        private Cu03Info cu03Info = Cu03Info.initial();

        private byte errMsgColour = BmsAttributes.DFHDFCOL;

        private DateHeader dateHeader;

        private boolean returned;

        private boolean transferred;

        private final List<Send> sends = new ArrayList<>(3);

        private final List<String> displayLines = new ArrayList<>();

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

        public void setCommarea(NavigationContext value) {
            this.commarea = Objects.requireNonNull(value, "A communication area is required; use "
                    + "NavigationContext.empty() for the cold-start state");
        }

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

        /**
         * {@code MOVE 'Y' TO WS-ERR-FLG} - lines 127, 146, 178, 288, 295, 324 and 331.
         */
        public void setErrFlagOn() {
            this.errFlag = true;
        }

        /**
         * {@code SET ERR-FLG-OFF TO TRUE} - line 84.
         */
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

        /**
         * {@code SET USR-MODIFIED-NO TO TRUE} - line 85, the only statement that touches the flag.
         */
        public void setUsrModifiedNo() {
            this.usrModified = false;
        }

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
         * <p>One field of the record area, not the whole area: the remaining five items keep whatever they
         * held, exactly as a COBOL {@code MOVE} into a group's sub-item leaves its siblings alone.
         *
         * @param image the key at its declared width of {@value UserDeleteController#SEC_USR_ID_LENGTH}
         * @throws NullPointerException if {@code image} is {@code null}
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

        /**
         * Releases the hold, because the record it stood for no longer exists.
         */
        public void releaseHold() {
            this.heldRecord = null;
        }

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

        void setCu03Info(Cu03Info info) {
            this.cu03Info = info == null ? Cu03Info.initial() : info;
        }

        /**
         * This screen's presentation metadata, in the shared envelope every online response publishes.
         *
         * @return the metadata, never {@code null}
         */
        public ScreenMetadata screenMetadata() {
            return ScreenMetadata.of(cursorField().map(CursorField::dfhmdfLabel).orElse(null),
                    errMsgColour,
                    false);
        }

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

        /**
         * Records {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)}.
         */
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

        /**
         * Records {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)}.
         */
        public void markTransferred() {
            this.transferred = true;
        }

        /**
         * How many times {@code EXEC CICS SEND MAP} was executed.
         *
         * @return the number of sends - {@code sends().size()}, named for readability
         */
        public int sendCount() {
            return sends.size();
        }

        /**
         * One entry per {@code EXEC CICS SEND MAP}, in order.
         *
         * @return the snapshots; unmodifiable, possibly empty, never {@code null}
         */
        public List<Send> sends() {
            return Collections.unmodifiableList(sends);
        }

        /**
         * Records one {@code EXEC CICS SEND MAP ... ERASE CURSOR}.
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

        public void recordDisplay(String text) {
            displayLines.add(Objects.requireNonNull(text, "Displayed text is required"));
        }
    }
}
