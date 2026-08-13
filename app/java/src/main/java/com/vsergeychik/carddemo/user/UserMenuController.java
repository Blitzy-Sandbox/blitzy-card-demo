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
 * <p>Note in particular that paging uses {@code CDEMO-CU00-PAGE-NUM} in the communication area and never
 * {@code WS-PAGE-NUM}: two similarly named items, one live and one dead.
 */
@RestController
public final class UserMenuController {
    private static final Log LOG = LogFactory.getLog(UserMenuController.class);

    static final String USER_LIST_PATH = "/api/users";

    static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    static final String EIBAID_PARAM_ALIAS = AidRequestParameter.ALTERNATE_NAME;

    static final int RAW_AID_LENGTH = 1;

    static final char MAX_AID_CODE_POINT = 0x00FF;

    private static final int AID_MIN = 0;

    private static final int AID_MAX = 255;

    static final String WS_PGMNAME = "COUSR00C";

    static final String WS_TRANID = "CU00";

    static final String WS_USRSEC_FILE = SecUserRepository.CICS_FILE_NAME_IMAGE;

    static final int WS_MESSAGE_LENGTH = 80;

    private static final int PAGE_SIZE = 10;

    private static final int FIRST_ROW = 1;

    private static final int FORWARD_LOOP_BOUND = PAGE_SIZE + 1;

    private static final int BACKWARD_LOOP_BOUND = 0;

    static final int CURSOR_ON_USRIDIN = -1;

    static final int NO_CURSOR = 0;

    static final String MSG_ALREADY_AT_TOP = "You are already at the top of the page...";

    static final String MSG_ALREADY_AT_BOTTOM = "You are already at the bottom of the page...";

    static final String MSG_AT_TOP = "You are at the top of the page...";

    static final String MSG_REACHED_BOTTOM = "You have reached the bottom of the page...";

    static final String MSG_REACHED_TOP = "You have reached the top of the page...";

    static final String MSG_UNABLE_TO_LOOKUP = "Unable to lookup User...";

    static final String MSG_INVALID_SELECTION = UserListResponse.INVALID_SELECTION_MESSAGE;

    static final String MSG_INVALID_KEY = SystemMessages.CCDA_MSG_INVALID_KEY;

    static final String DISPLAY_RESP = "RESP:";

    static final String DISPLAY_REAS = "REAS:";

    static final int WS_RESP_CD_DIGITS = 9;

    static final String LIT_USER_UPDATE_PGM = UserListResponse.NEXT_PROGRAM_USER_UPDATE;

    static final String LIT_USER_DELETE_PGM = UserListResponse.NEXT_PROGRAM_USER_DELETE;

    static final String LIT_SIGNON_PGM = UserListResponse.NEXT_PROGRAM_SIGNON;

    static final String LIT_ADMIN_MENU_PGM = "COADM01C";

    static final char SEL_UPDATE_UPPER = 'U';

    static final char SEL_UPDATE_LOWER = 'u';

    static final char SEL_DELETE_UPPER = 'D';

    static final char SEL_DELETE_LOWER = 'd';

    private static final char SPACE = ' ';

    private static final char LOW_VALUE = '\u0000';

    private final SecUserRepository secUserRepository;

    private final FixedWidthCodec codec;

    private final Clock clock;

    /**
     * Wiring constructor, used by the Spring container.
     *
     * @param secUserRepository the {@code USRSEC} file
     * @param datasetCharset the active dataset code page,
     *     {@code @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)}
     * @param clock the clock {@code FUNCTION CURRENT-DATE} is read from
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if a width or count this screen depends on disagrees with the copybook
     *     that owns it - see {@link #verifyScreenContract()}
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

    static void verifyScreenContract() {
        requireAgreement(PAGE_SIZE, UserListRequest.ROW_COUNT,
                "the OCCURS-10 page size and UserListRequest.ROW_COUNT");
        requireAgreement(PAGE_SIZE, UserListResponse.ROW_COUNT,
                "the OCCURS-10 page size and UserListResponse.ROW_COUNT");

        requireAgreement(UserListRequest.MAP_FIELD_COUNT, UserListResponse.MAP_FIELD_COUNT,
                "the DFHMDF field count of the request and of the response");

        requireAgreement(ScreenTitles.TITLE_LENGTH, UserListResponse.TITLE01_LENGTH,
                "CCDA-TITLE01 and TITLE01O");
        requireAgreement(ScreenTitles.TITLE_LENGTH, UserListResponse.TITLE02_LENGTH,
                "CCDA-TITLE02 and TITLE02O");

        requireAgreement(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH, UserListResponse.CURDATE_LENGTH,
                "WS-CURDATE-MM-DD-YY and CURDATEO");
        requireAgreement(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH, UserListResponse.CURTIME_LENGTH,
                "WS-CURTIME-HH-MM-SS and CURTIMEO");

        requireAgreement(WS_TRANID.length(), UserListResponse.TRNNAME_LENGTH, "WS-TRANID and TRNNAMEO");
        requireAgreement(WS_PGMNAME.length(), UserListResponse.PGMNAME_LENGTH, "WS-PGMNAME and PGMNAMEO");

        requireAgreement(SecUserRecord.SEC_USR_ID_LENGTH, UserListResponse.USRID_LENGTH,
                "SEC-USR-ID and USRIDnn");
        requireAgreement(SecUserRecord.SEC_USR_FNAME_LENGTH, UserListResponse.FNAME_LENGTH,
                "SEC-USR-FNAME and FNAMEnn");
        requireAgreement(SecUserRecord.SEC_USR_LNAME_LENGTH, UserListResponse.LNAME_LENGTH,
                "SEC-USR-LNAME and LNAMEnn");
        requireAgreement(SecUserRecord.SEC_USR_TYPE_LENGTH, UserListResponse.UTYPE_LENGTH,
                "SEC-USR-TYPE and UTYPEnn");

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

        requireAgreement(UserListRequest.CU00_INFO_LENGTH, UserListResponse.CU00_INFO_LENGTH,
                "the width of CDEMO-CU00-INFO on the request and on the response");
        requireAgreement(NavigationContext.COMMAREA_LENGTH + UserListResponse.CU00_INFO_LENGTH,
                UserListResponse.CU00_COMMAREA_LENGTH,
                "COCOM01Y plus CDEMO-CU00-INFO and the declared CU00 communication-area length");

        requireAgreement(UserListResponse.CU00_PAGE_NUM_DIGITS, UserListResponse.PAGENUM_LENGTH,
                "CDEMO-CU00-PAGE-NUM and PAGENUMO");

        // This must stay a NARROWING - if the message line ever became at least as wide as WS-MESSAGE the
        // truncation would silently stop happening.
        requireNarrowing(UserListResponse.ERRMSG_LENGTH, WS_MESSAGE_LENGTH,
                "ERRMSGO must stay narrower than WS-MESSAGE, because :526 truncates on the right");

        for (String message : messageTexts()) {
            requireFits(message, UserListResponse.ERRMSG_LENGTH);
        }
    }

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

    static void requireAgreement(int expected, int actual, String subject) {
        if (expected != actual) {
            throw new IllegalStateException(subject + " must agree, but they are " + expected + " and "
                    + actual + ". One of the two has drifted from the copybook it came from, and this "
                    + "screen's behaviour depends on them being the same.");
        }
    }

    static void requireNarrowing(int narrower, int wider, String subject) {
        if (narrower >= wider) {
            throw new IllegalStateException(subject + ", but " + narrower + " is not below " + wider
                    + ". Whether that MOVE truncates at all depends on this ordering.");
        }
    }

    static void requireFits(String message, int width) {
        if (message.length() > width) {
            throw new IllegalStateException("Message text '" + message + "' is " + message.length()
                    + " characters and cannot be displayed in a PIC X(" + width + ") field without "
                    + "losing its last " + (message.length() - width) + ". The operator would read "
                    + "something the source does not say.");
        }
    }

    /**
     * {@code GET /api/users} - the REST projection of CSD transaction {@code CU00}
     * ({@code app/csd/CARDDEMO.CSD:449}).
     *
     * <p>A caller that simply echoes the payload it was last given sends nothing of the kind - it sends
     * {@link UserListRequest#aid()}, the five-character {@code CCARD-AID} token this module publishes on
     * every response, because a raw byte cannot travel in JSON.
     *
     * @param request the inbound screen and communication area, or {@code null} for the cold start
     * @param eibaid the terminal's attention identifier as an unsigned byte {@code 0..255} under the
     *     canonical parameter name, or {@code null} to take the key from {@link UserListRequest#aid()}
     * @param eibAid the same value under the alternate spelling; at most one of the two need be sent
     * @return the {@code COUSR0AO} projection, or - on a transfer of control - the same payload with
     *     {@code nextProgram} naming where the client goes next
     * @throws IllegalArgumentException if the AID is outside {@code 0..255}, or if both spellings are
     *     present and disagree
     */
    @GetMapping(path = USER_LIST_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScreenResponse<UserListResponse> getUsers(
            @Valid @RequestBody(required = false) UserListRequest request,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid) {
        WorkArea ws = new WorkArea();
        UserListResponse painted = listUsers(
                request, resolveEibAid(AidRequestParameter.resolve(eibaid, eibAid), request), ws);
        return ScreenResponse.of(painted, screenMetadataOf(ws));
    }

    static ScreenMetadata screenMetadataOf(WorkArea ws) {
        Objects.requireNonNull(ws, "A work area is required to read the metadata the execution set");
        String cursorField = ws.usrIdInLength() == CURSOR_ON_USRIDIN
                ? UserListResponse.USRIDIN_FIELD
                : null;
        return ScreenMetadata.of(cursorField, BmsAttributes.DFHDFCOL, ws.sendEraseYes());
    }

    static byte resolveEibAid(Integer eibaid, UserListRequest request) {
        if (eibaid != null) {
            int value = eibaid;
            if (value < AID_MIN || value > AID_MAX) {
                throw ScreenInputRejectedException.outsideRange(EIBAID_PARAM,
                    "one EIBAID byte", AID_MIN, AID_MAX);
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

    static OptionalInt aidByteOfToken(String token) {
        if (token == null) {
            return OptionalInt.empty();
        }
        if (token.isBlank() || token.chars().allMatch(character -> character == 0)) {
            return OptionalInt.empty();
        }
        if (token.length() != RAW_AID_LENGTH) {
            return OptionalInt.of(CicsAid.DFHNULL & 0xFF);
        }
        char stated = token.charAt(0);
        if (stated > MAX_AID_CODE_POINT) {
            return OptionalInt.of(CicsAid.DFHNULL & 0xFF);
        }
        return OptionalInt.of(stated);
    }

    UserListResponse listUsers(UserListRequest incoming, byte eibAid) {
        return listUsers(incoming, eibAid, new WorkArea());
    }

    UserListResponse listUsers(UserListRequest incoming, byte eibAid, WorkArea ws) {
        Objects.requireNonNull(ws, "A work area is required; the initial state is new WorkArea()");

        // Not a COBOL statement: EIBAID is already in the exec interface block when the program starts, so
        // resolving it once here records what the terminal presented on every path - including the paths
        // that never reach the EVALUATE at :122 but do test EIBAID at :288 or :342.
        ws.aidKey = PfKeyResolver.resolve(eibAid);

        ws.errFlgOn = false;
        ws.userSecEof = false;
        ws.nextPageYes = false;
        ws.sendEraseYes = true;

        ws.message = spaces(WS_MESSAGE_LENGTH);
        ws.map.errMsg = spaces(UserListResponse.ERRMSG_LENGTH);

        ws.usrIdInLength = CURSOR_ON_USRIDIN;

        if (incoming == null || !incoming.hasNavigationContext()) {
            ws.commarea = ws.commarea.withToProgram(LIT_SIGNON_PGM);
            return returnToPrevScreen(ws);
        }

        ws.acceptCommarea(incoming);

        if (!ws.commarea.isReenter()) {
            ws.commarea = ws.commarea.withPgmReenter();
            ws.map.moveLowValues();
            UserListResponse transfer = processEnterKey(ws, eibAid);
            // The arm is therefore unreachable from a first entry - a property of the COBOL's own
            // sequencing, not of this translation - and it is kept rather than elided because eliding it
            // would make the two PERFORM sites differ where the source has them identical.
            if (transfer != null) {
                return transfer;
            }
            sendUsrlstScreen(ws);
        } else {
            receiveUsrlstScreen(ws, incoming);

            if (PfKeyResolver.isEnter(eibAid)) {
                UserListResponse transfer = processEnterKey(ws, eibAid);
                if (transfer != null) {
                    return transfer;
                }
            } else if (PfKeyResolver.isPf3(eibAid)) {
                ws.commarea = ws.commarea.withToProgram(LIT_ADMIN_MENU_PGM);
                return returnToPrevScreen(ws);
            } else if (PfKeyResolver.isPf7(eibAid)) {
                processPf7Key(ws, eibAid);
            } else if (PfKeyResolver.isPf8(eibAid)) {
                processPf8Key(ws, eibAid);
            } else {
                ws.errFlgOn = true;
                ws.usrIdInLength = CURSOR_ON_USRIDIN;
                ws.message = codec.movePicX(MSG_INVALID_KEY, WS_MESSAGE_LENGTH);
                sendUsrlstScreen(ws);
            }
        }

        return lastSentScreen(ws);
    }

    UserListResponse processEnterKey(WorkArea ws, byte eibAid) {
        selectTickedRow(ws);

        if (isNotBlank(ws.cu00UsrSelFlg) && isNotBlank(ws.cu00UsrSelected)) {
            char selection = ws.cu00UsrSelFlg.charAt(0);
            if (selection == SEL_UPDATE_UPPER || selection == SEL_UPDATE_LOWER) {
                return transferTo(ws, LIT_USER_UPDATE_PGM);
            } else if (selection == SEL_DELETE_UPPER || selection == SEL_DELETE_LOWER) {
                return transferTo(ws, LIT_USER_DELETE_PGM);
            } else {
                ws.message = codec.movePicX(MSG_INVALID_SELECTION, WS_MESSAGE_LENGTH);
                ws.usrIdInLength = CURSOR_ON_USRIDIN;
            }
        }

        if (isBlank(ws.map.usrIdIn)) {
            ws.secUsrId = SecUserRepository.LOW_VALUES_KEY;
        } else {
            ws.secUsrId = codec.movePicX(ws.map.usrIdIn, SecUserRecord.SEC_USR_ID_LENGTH);
        }

        ws.usrIdInLength = CURSOR_ON_USRIDIN;

        ws.cu00PageNum = UserListRequest.CU00_PAGE_NUM_INITIAL;

        processPageForward(ws, eibAid);

        if (!ws.errFlgOn) {
            ws.map.usrIdIn = codec.movePicX(String.valueOf(SPACE), UserListResponse.USRIDIN_LENGTH);
        }
        return null;
    }

    void selectTickedRow(WorkArea ws) {
        for (int rowNumber = FIRST_ROW; rowNumber <= PAGE_SIZE; rowNumber++) {
            if (isNotBlank(ws.map.sel[rowNumber - 1])) {
                ws.cu00UsrSelFlg = codec.movePicX(ws.map.sel[rowNumber - 1],
                        UserListResponse.CU00_USR_SEL_FLG_LENGTH);
                ws.cu00UsrSelected = codec.movePicX(ws.map.usrId[rowNumber - 1],
                        UserListResponse.CU00_USR_SELECTED_LENGTH);
                ws.selectedRow = rowNumber;
                return;
            }
        }
        ws.cu00UsrSelFlg = spaces(UserListResponse.CU00_USR_SEL_FLG_LENGTH);
        ws.cu00UsrSelected = spaces(UserListResponse.CU00_USR_SELECTED_LENGTH);
        ws.selectedRow = 0;
    }

    void processPf7Key(WorkArea ws, byte eibAid) {
        if (isBlank(ws.cu00UsrIdFirst)) {
            ws.secUsrId = SecUserRepository.LOW_VALUES_KEY;
        } else {
            ws.secUsrId = codec.movePicX(ws.cu00UsrIdFirst, SecUserRecord.SEC_USR_ID_LENGTH);
        }

        ws.nextPageYes = true;
        ws.usrIdInLength = CURSOR_ON_USRIDIN;

        if (ws.cu00PageNum > 1) {
            processPageBackward(ws, eibAid);
        } else {
            ws.message = codec.movePicX(MSG_ALREADY_AT_TOP, WS_MESSAGE_LENGTH);
            ws.sendEraseYes = false;
            sendUsrlstScreen(ws);
        }
    }

    void processPf8Key(WorkArea ws, byte eibAid) {
        if (isBlank(ws.cu00UsrIdLast)) {
            ws.secUsrId = SecUserRepository.HIGH_VALUES_KEY;
        } else {
            ws.secUsrId = codec.movePicX(ws.cu00UsrIdLast, SecUserRecord.SEC_USR_ID_LENGTH);
        }

        ws.usrIdInLength = CURSOR_ON_USRIDIN;

        if (ws.nextPageYes) {
            processPageForward(ws, eibAid);
        } else {
            ws.message = codec.movePicX(MSG_ALREADY_AT_BOTTOM, WS_MESSAGE_LENGTH);
            ws.sendEraseYes = false;
            sendUsrlstScreen(ws);
        }
    }

    void processPageForward(WorkArea ws, byte eibAid) {
        BrowseCursor cursor = startBrowseUserSecFile(ws);

        try {
            pageForwardOverBrowse(ws, eibAid, cursor);
        } finally {
            releaseBrowse(cursor);
        }
    }

    private void pageForwardOverBrowse(WorkArea ws, byte eibAid, BrowseCursor cursor) {
        if (ws.errFlgOn) {
            return;
        }

        if (!PfKeyResolver.isEnter(eibAid) && !PfKeyResolver.isPf7(eibAid) && !PfKeyResolver.isPf3(eibAid)) {
            readNextUserSecFile(ws, cursor);
        }

        if (!ws.userSecEof && !ws.errFlgOn) {
            for (ws.idx = FIRST_ROW; ws.idx <= PAGE_SIZE; ws.idx++) {
                initializeUserData(ws);
            }
        }

        ws.idx = FIRST_ROW;

        while (ws.idx < FORWARD_LOOP_BOUND && !ws.userSecEof && !ws.errFlgOn) {
            ReadResult read = readNextUserSecFile(ws, cursor);
            if (!ws.userSecEof && !ws.errFlgOn) {
                populateUserData(ws, read.requireRecord());
                ws.idx = ws.idx + 1;
            }
        }

        if (!ws.userSecEof && !ws.errFlgOn) {
            ws.cu00PageNum = ws.cu00PageNum + 1;
            readNextUserSecFile(ws, cursor);
            ws.nextPageYes = !ws.userSecEof && !ws.errFlgOn;
        } else {
            ws.nextPageYes = false;
            // WS-IDX is still 1 when the very first read found nothing, and then the count must not move:
            // the operator is being shown the page they were already on.
            if (ws.idx > FIRST_ROW) {
                ws.cu00PageNum = ws.cu00PageNum + 1;
            }
        }

        endBrowse(cursor);

        ws.map.pageNum = codec.movePic9(ws.cu00PageNum, UserListResponse.PAGENUM_LENGTH);

        // PROCESS-PAGE-BACKWARD deliberately does NOT do this.
        ws.map.usrIdIn = codec.movePicX(String.valueOf(SPACE), UserListResponse.USRIDIN_LENGTH);

        sendUsrlstScreen(ws);
    }

    void processPageBackward(WorkArea ws, byte eibAid) {
        BrowseCursor cursor = startBrowseUserSecFile(ws);

        try {
            pageBackwardOverBrowse(ws, eibAid, cursor);
        } finally {
            releaseBrowse(cursor);
        }
    }

    private void pageBackwardOverBrowse(WorkArea ws, byte eibAid, BrowseCursor cursor) {
        if (ws.errFlgOn) {
            return;
        }

        if (!PfKeyResolver.isEnter(eibAid) && !PfKeyResolver.isPf8(eibAid)) {
            readPrevUserSecFile(ws, cursor);
        }

        if (!ws.userSecEof && !ws.errFlgOn) {
            for (ws.idx = FIRST_ROW; ws.idx <= PAGE_SIZE; ws.idx++) {
                initializeUserData(ws);
            }
        }

        ws.idx = PAGE_SIZE;

        while (ws.idx > BACKWARD_LOOP_BOUND && !ws.userSecEof && !ws.errFlgOn) {
            ReadResult read = readPrevUserSecFile(ws, cursor);
            if (!ws.userSecEof && !ws.errFlgOn) {
                populateUserData(ws, read.requireRecord());
                ws.idx = ws.idx - 1;
            }
        }

        // The nesting is what the source indentation at :362 obscures and what the END-IF at :372 settles,
        // and it matters: after a backward page that hit the start of the file, no further read is issued
        // and the page number is left exactly as it was.
        if (!ws.userSecEof && !ws.errFlgOn) {
            readPrevUserSecFile(ws, cursor);
            if (ws.nextPageYes) {
                if (!ws.userSecEof && !ws.errFlgOn && ws.cu00PageNum > 1) {
                    ws.cu00PageNum = ws.cu00PageNum - 1;
                } else {
                    ws.cu00PageNum = 1;
                }
            }
        }

        endBrowse(cursor);

        ws.map.pageNum = codec.movePic9(ws.cu00PageNum, UserListResponse.PAGENUM_LENGTH);

        sendUsrlstScreen(ws);
    }

    void populateUserData(WorkArea ws, SecUserRecord record) {
        Objects.requireNonNull(record, "POPULATE-USER-DATA writes SEC-USER-DATA, which the READNEXT or "
                + "READPREV that preceded it has already filled");
        if (ws.idx < FIRST_ROW || ws.idx > PAGE_SIZE) {
            return;
        }
        int index = ws.idx - 1;
        ws.map.usrId[index] = codec.movePicX(record.secUsrId(), UserListResponse.USRID_LENGTH);
        ws.map.fname[index] = codec.movePicX(record.secUsrFname(), UserListResponse.FNAME_LENGTH);
        ws.map.lname[index] = codec.movePicX(record.secUsrLname(), UserListResponse.LNAME_LENGTH);
        ws.map.utype[index] = codec.movePicX(record.secUsrType(), UserListResponse.UTYPE_LENGTH);

        if (ws.idx == FIRST_ROW) {
            ws.cu00UsrIdFirst = codec.movePicX(record.secUsrId(),
                    UserListResponse.CU00_USRID_FIRST_LENGTH);
        }
        if (ws.idx == PAGE_SIZE) {
            ws.cu00UsrIdLast = codec.movePicX(record.secUsrId(), UserListResponse.CU00_USRID_LAST_LENGTH);
        }
    }

    void initializeUserData(WorkArea ws) {
        if (ws.idx < FIRST_ROW || ws.idx > PAGE_SIZE) {
            return;
        }
        int index = ws.idx - 1;
        ws.map.usrId[index] = spaces(UserListResponse.USRID_LENGTH);
        ws.map.fname[index] = spaces(UserListResponse.FNAME_LENGTH);
        ws.map.lname[index] = spaces(UserListResponse.LNAME_LENGTH);
        ws.map.utype[index] = spaces(UserListResponse.UTYPE_LENGTH);
    }

    // XCTL does not return, so each of these is the last thing the program does: the statements after the
    // PERFORM never run and neither does the EXEC CICS RETURN at :141.

    UserListResponse transferTo(WorkArea ws, String program) {
        ws.commarea = ws.commarea
                .withToProgram(program)
                .withFromTranid(WS_TRANID)
                .withFromProgram(WS_PGMNAME)
                .withPgmContext(NavigationContext.PGM_CONTEXT_ENTER);
        return transferred(ws, program);
    }

    UserListResponse returnToPrevScreen(WorkArea ws) {
        if (isBlank(ws.commarea.toProgram())) {
            ws.commarea = ws.commarea.withToProgram(LIT_SIGNON_PGM);
        }
        ws.commarea = ws.commarea
                .withFromTranid(WS_TRANID)
                .withFromProgram(WS_PGMNAME)
                .withPgmContext(NavigationContext.PGM_CONTEXT_ENTER);
        return transferred(ws, ws.commarea.toProgram());
    }

    UserListResponse transferred(WorkArea ws, String program) {
        ws.transferred = true;
        return ws.render()
                .nextProgram(codec.movePicX(program, UserListResponse.NEXT_PROGRAM_LENGTH))
                .build();
    }

    void receiveUsrlstScreen(WorkArea ws, UserListRequest incoming) {
        ws.respCd = FileStatus.NORMAL;
        ws.reasCd = FileStatus.NO_REASON_CODE;
        if (incoming == null) {
            return;
        }
        ws.map.receive(incoming, codec);
    }

    void sendUsrlstScreen(WorkArea ws) {
        populateHeaderInfo(ws);

        String message80 = codec.movePicX(ws.message, WS_MESSAGE_LENGTH);
        ws.map.errMsg = codec.movePicX(message80, UserListResponse.ERRMSG_LENGTH);

        ws.sends.add(new SentScreen(ws.render().build(), ws.sendEraseYes, ws.usrIdInLength));
    }

    void populateHeaderInfo(WorkArea ws) {
        DateHeader header = DateHeader.from(codec, clock);
        ws.map.title01 = codec.movePicX(ScreenTitles.CCDA_TITLE01, UserListResponse.TITLE01_LENGTH);
        ws.map.title02 = codec.movePicX(ScreenTitles.CCDA_TITLE02, UserListResponse.TITLE02_LENGTH);
        ws.map.trnName = codec.movePicX(WS_TRANID, UserListResponse.TRNNAME_LENGTH);
        ws.map.pgmName = codec.movePicX(WS_PGMNAME, UserListResponse.PGMNAME_LENGTH);
        ws.map.curDate = codec.movePicX(header.wsCurdateMmDdYy(), UserListResponse.CURDATE_LENGTH);
        ws.map.curTime = codec.movePicX(header.wsCurtimeHhMmSs(), UserListResponse.CURTIME_LENGTH);
    }

    UserListResponse lastSentScreen(WorkArea ws) {
        if (ws.sends.isEmpty()) {
            return ws.render().build();
        }
        return ws.sends.get(ws.sends.size() - 1).screen();
    }

    BrowseCursor startBrowseUserSecFile(WorkArea ws) {
        BrowseCursor cursor = secUserRepository.startBrowse(ws.secUsrId);
        ws.captureResponse(cursor.openCicsResp(), FileStatus.NO_REASON_CODE);

        if (cursor.openOutcome() == FileStatus.Outcome.OK) {
            return cursor;
        }
        if (cursor.openOutcome() == FileStatus.Outcome.NOT_FOUND) {
            ws.userSecEof = true;
            ws.message = codec.movePicX(MSG_AT_TOP, WS_MESSAGE_LENGTH);
            ws.usrIdInLength = CURSOR_ON_USRIDIN;
            sendUsrlstScreen(ws);
            return cursor;
        }
        display(ws, DISPLAY_RESP + respImage(ws.respCd) + DISPLAY_REAS + respImage(ws.reasCd));
        ws.errFlgOn = true;
        ws.message = codec.movePicX(MSG_UNABLE_TO_LOOKUP, WS_MESSAGE_LENGTH);
        ws.usrIdInLength = CURSOR_ON_USRIDIN;
        sendUsrlstScreen(ws);
        return cursor;
    }

    ReadResult readNextUserSecFile(WorkArea ws, BrowseCursor cursor) {
        ReadResult read = cursor.readNext();
        ws.captureResponse(read.cicsResp(), read.cicsResp2());
        ws.acceptRecord(read, codec);

        if (read.isFound()) {
            return read;
        }
        if (read.isEndOfFile()) {
            ws.userSecEof = true;
            ws.message = codec.movePicX(MSG_REACHED_BOTTOM, WS_MESSAGE_LENGTH);
            ws.usrIdInLength = CURSOR_ON_USRIDIN;
            sendUsrlstScreen(ws);
            return read;
        }
        display(ws, DISPLAY_RESP + respImage(ws.respCd) + DISPLAY_REAS + respImage(ws.reasCd));
        ws.errFlgOn = true;
        ws.message = codec.movePicX(MSG_UNABLE_TO_LOOKUP, WS_MESSAGE_LENGTH);
        ws.usrIdInLength = CURSOR_ON_USRIDIN;
        sendUsrlstScreen(ws);
        return read;
    }

    ReadResult readPrevUserSecFile(WorkArea ws, BrowseCursor cursor) {
        ReadResult read = cursor.readPrevious();
        ws.captureResponse(read.cicsResp(), read.cicsResp2());
        ws.acceptRecord(read, codec);

        if (read.isFound()) {
            return read;
        }
        if (read.isEndOfFile()) {
            ws.userSecEof = true;
            ws.message = codec.movePicX(MSG_REACHED_TOP, WS_MESSAGE_LENGTH);
            ws.usrIdInLength = CURSOR_ON_USRIDIN;
            sendUsrlstScreen(ws);
            return read;
        }
        display(ws, DISPLAY_RESP + respImage(ws.respCd) + DISPLAY_REAS + respImage(ws.reasCd));
        ws.errFlgOn = true;
        ws.message = codec.movePicX(MSG_UNABLE_TO_LOOKUP, WS_MESSAGE_LENGTH);
        ws.usrIdInLength = CURSOR_ON_USRIDIN;
        sendUsrlstScreen(ws);
        return read;
    }

    void endBrowse(BrowseCursor cursor) {
        cursor.endBrowse();
    }

    private static void releaseBrowse(BrowseCursor cursor) {
        if (!cursor.isOpen()) {
            return;
        }
        try {
            cursor.endBrowse();
        } catch (RuntimeException cleanupFailure) {
            LOG.warn("Ending the " + WS_USRSEC_FILE + " browse of " + WS_PGMNAME + " after a request "
                    + "that did not reach ENDBR failed - " + cleanupFailure.getClass().getName()
                    + ". The request's own outcome is unchanged, because the request's own failure is "
                    + "the one that matters.");
        }
    }

    private void display(WorkArea ws, String text) {
        ws.displays.add(text);
        LOG.info(text);
    }

    private static String respImage(int code) {
        return FileStatus.respReported(code)
                ? Integer.toString(code)
                : FileStatus.respNotReportedImage(WS_RESP_CD_DIGITS);
    }

    static boolean isBlank(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        return allOf(value, SPACE) || allOf(value, LOW_VALUE);
    }

    static boolean isNotBlank(String value) {
        return !isBlank(value);
    }

    private static boolean allOf(String value, char character) {
        for (int position = 0; position < value.length(); position++) {
            if (value.charAt(position) != character) {
                return false;
            }
        }
        return true;
    }

    private static String spaces(int width) {
        return String.valueOf(SPACE).repeat(width);
    }

    /**
     * One {@code EXEC CICS SEND MAP}: the screen transmitted, and the two operands that shaped it.
     *
     * @param screen the payload as this send transmitted it
     * @param erase whether {@code SEND-ERASE-YES} held, choosing the {@code ERASE} arm at {@code :529-535}
     *     over the arm at {@code :537-543} where {@code ERASE} is commented out at {@code :541}
     * @param cursorPosition the {@code USRIDINL} value the {@code CURSOR} operand acted on:
     *     {@link #CURSOR_ON_USRIDIN} to place the cursor in the start-key field, or {@value #NO_CURSOR} for
     *     none
     */
    record SentScreen(UserListResponse screen, boolean erase, int cursorPosition) {
        SentScreen {
            Objects.requireNonNull(screen, "A send transmits a screen; there is no send without one");
        }
    }

    /**
     * The {@code WORKING-STORAGE} of one execution of {@code COUSR00C}.
     *
     * <p>Package-visible with package-visible accessors, so a test in this package can drive one paragraph
     * and read every flag the COBOL set - including the ones that never reach the payload.
     */
    static final class WorkArea {
        private String message = spaces(WS_MESSAGE_LENGTH);

        private boolean errFlgOn;

        private boolean userSecEof;

        private boolean sendEraseYes = true;

        private int respCd;

        private int reasCd;

        private int idx;

        private String secUsrId = SecUserRepository.LOW_VALUES_KEY;

        private SecUserRecord secUserData = SecUserRecord.blank();

        private NavigationContext commarea = NavigationContext.empty();

        private String cu00UsrIdFirst = spaces(UserListResponse.CU00_USRID_FIRST_LENGTH);

        private String cu00UsrIdLast = spaces(UserListResponse.CU00_USRID_LAST_LENGTH);

        private int cu00PageNum;

        private boolean nextPageYes;

        private String cu00UsrSelFlg = spaces(UserListResponse.CU00_USR_SEL_FLG_LENGTH);

        private String cu00UsrSelected = spaces(UserListResponse.CU00_USR_SELECTED_LENGTH);

        private final SymbolicMap map = new SymbolicMap();

        private int usrIdInLength;

        private final List<SentScreen> sends = new ArrayList<>();

        private final List<String> displays = new ArrayList<>();

        private boolean transferred;

        private Optional<AidKey> aidKey = Optional.empty();

        private int selectedRow;

        void acceptCommarea(UserListRequest incoming) {
            commarea = incoming.navigationContext();
            cu00UsrIdFirst = incoming.cdemoCu00UsrIdFirst();
            cu00UsrIdLast = incoming.cdemoCu00UsrIdLast();
            cu00PageNum = incoming.cdemoCu00PageNum();
            nextPageYes = incoming.nextPageYes();
            cu00UsrSelFlg = incoming.cdemoCu00UsrSelFlg();
            cu00UsrSelected = incoming.cdemoCu00UsrSelected();
        }

        void acceptRecord(ReadResult read, FixedWidthCodec codec) {
            if (!read.isFound()) {
                return;
            }
            SecUserRecord record = read.requireRecord();
            secUserData = record;
            secUsrId = codec.movePicX(record.secUsrId(), SecUserRecord.SEC_USR_ID_LENGTH);
        }

        void captureResponse(OptionalInt resp, int resp2) {
            respCd = resp.orElse(FileStatus.RESP_NOT_REPORTED);
            reasCd = resp2;
        }

        UserListResponse.Builder render() {
            UserListResponse.Builder builder = map.render(UserListResponse.builder())
                    .cdemoCu00UsrIdFirst(cu00UsrIdFirst)
                    .cdemoCu00UsrIdLast(cu00UsrIdLast)
                    .cdemoCu00PageNum(cu00PageNum)
                    .cdemoCu00UsrSelFlg(cu00UsrSelFlg)
                    .cdemoCu00UsrSelected(cu00UsrSelected)
                    .navigationContext(commarea);
            return nextPageYes ? builder.nextPageYes() : builder.nextPageNo();
        }

        String message() {
            return message;
        }

        boolean errFlgOn() {
            return errFlgOn;
        }

        boolean userSecEof() {
            return userSecEof;
        }

        boolean sendEraseYes() {
            return sendEraseYes;
        }

        boolean nextPageYes() {
            return nextPageYes;
        }

        int respCd() {
            return respCd;
        }

        int reasCd() {
            return reasCd;
        }

        int idx() {
            return idx;
        }

        void idx(int value) {
            idx = value;
        }

        String secUsrId() {
            return secUsrId;
        }

        void secUsrId(String value) {
            secUsrId = value;
        }

        SecUserRecord secUserData() {
            return secUserData;
        }

        NavigationContext commarea() {
            return commarea;
        }

        String cu00UsrIdFirst() {
            return cu00UsrIdFirst;
        }

        String cu00UsrIdLast() {
            return cu00UsrIdLast;
        }

        int cu00PageNum() {
            return cu00PageNum;
        }

        String cu00UsrSelFlg() {
            return cu00UsrSelFlg;
        }

        String cu00UsrSelected() {
            return cu00UsrSelected;
        }

        SymbolicMap map() {
            return map;
        }

        int usrIdInLength() {
            return usrIdInLength;
        }

        List<SentScreen> sends() {
            return Collections.unmodifiableList(sends);
        }

        List<String> displays() {
            return Collections.unmodifiableList(displays);
        }

        boolean transferred() {
            return transferred;
        }

        Optional<AidKey> aidKey() {
            return aidKey;
        }

        int selectedRow() {
            return selectedRow;
        }
    }

    /**
     * The fifty-nine data items of map {@code COUSR0A}: eight header and paging fields, ten rows of five,
     * and the message line.
     */
    static final class SymbolicMap {
        private String trnName = ScreenFieldImage.unpainted(UserListResponse.TRNNAME_LENGTH);

        private String title01 = ScreenFieldImage.unpainted(UserListResponse.TITLE01_LENGTH);

        private String curDate = ScreenFieldImage.unpainted(UserListResponse.CURDATE_LENGTH);

        private String pgmName = ScreenFieldImage.unpainted(UserListResponse.PGMNAME_LENGTH);

        private String title02 = ScreenFieldImage.unpainted(UserListResponse.TITLE02_LENGTH);

        private String curTime = ScreenFieldImage.unpainted(UserListResponse.CURTIME_LENGTH);

        private String pageNum = ScreenFieldImage.unpainted(UserListResponse.PAGENUM_LENGTH);

        private String usrIdIn = ScreenFieldImage.unpainted(UserListResponse.USRIDIN_LENGTH);

        private final String[] sel = newRow(UserListResponse.SEL_LENGTH);

        private final String[] usrId = newRow(UserListResponse.USRID_LENGTH);

        private final String[] fname = newRow(UserListResponse.FNAME_LENGTH);

        private final String[] lname = newRow(UserListResponse.LNAME_LENGTH);

        private final String[] utype = newRow(UserListResponse.UTYPE_LENGTH);

        private String errMsg = ScreenFieldImage.unpainted(UserListResponse.ERRMSG_LENGTH);

        private static String[] newRow(int width) {
            String[] cells = new String[PAGE_SIZE];
            for (int index = 0; index < PAGE_SIZE; index++) {
                cells[index] = ScreenFieldImage.unpainted(width);
            }
            return cells;
        }

        void moveLowValues() {
            trnName = ScreenFieldImage.unpainted(UserListResponse.TRNNAME_LENGTH);
            title01 = ScreenFieldImage.unpainted(UserListResponse.TITLE01_LENGTH);
            curDate = ScreenFieldImage.unpainted(UserListResponse.CURDATE_LENGTH);
            pgmName = ScreenFieldImage.unpainted(UserListResponse.PGMNAME_LENGTH);
            title02 = ScreenFieldImage.unpainted(UserListResponse.TITLE02_LENGTH);
            curTime = ScreenFieldImage.unpainted(UserListResponse.CURTIME_LENGTH);
            pageNum = ScreenFieldImage.unpainted(UserListResponse.PAGENUM_LENGTH);
            usrIdIn = ScreenFieldImage.unpainted(UserListResponse.USRIDIN_LENGTH);
            for (int index = 0; index < PAGE_SIZE; index++) {
                sel[index] = ScreenFieldImage.unpainted(UserListResponse.SEL_LENGTH);
                usrId[index] = ScreenFieldImage.unpainted(UserListResponse.USRID_LENGTH);
                fname[index] = ScreenFieldImage.unpainted(UserListResponse.FNAME_LENGTH);
                lname[index] = ScreenFieldImage.unpainted(UserListResponse.LNAME_LENGTH);
                utype[index] = ScreenFieldImage.unpainted(UserListResponse.UTYPE_LENGTH);
            }
            errMsg = ScreenFieldImage.unpainted(UserListResponse.ERRMSG_LENGTH);
        }

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

        String trnName() {
            return trnName;
        }

        String title01() {
            return title01;
        }

        String curDate() {
            return curDate;
        }

        String pgmName() {
            return pgmName;
        }

        String title02() {
            return title02;
        }

        String curTime() {
            return curTime;
        }

        String pageNum() {
            return pageNum;
        }

        String usrIdIn() {
            return usrIdIn;
        }

        String errMsg() {
            return errMsg;
        }

        String sel(int rowNumber) {
            return sel[requireRowNumber(rowNumber) - 1];
        }

        String usrId(int rowNumber) {
            return usrId[requireRowNumber(rowNumber) - 1];
        }

        String fname(int rowNumber) {
            return fname[requireRowNumber(rowNumber) - 1];
        }

        String lname(int rowNumber) {
            return lname[requireRowNumber(rowNumber) - 1];
        }

        String utype(int rowNumber) {
            return utype[requireRowNumber(rowNumber) - 1];
        }

        void sel(int rowNumber, String value) {
            sel[requireRowNumber(rowNumber) - 1] = value;
        }

        void usrId(int rowNumber, String value) {
            usrId[requireRowNumber(rowNumber) - 1] = value;
        }

        void usrIdIn(String value) {
            usrIdIn = value;
        }

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
