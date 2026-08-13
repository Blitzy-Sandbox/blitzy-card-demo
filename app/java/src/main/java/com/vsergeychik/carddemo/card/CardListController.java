package com.vsergeychik.carddemo.card;

import com.vsergeychik.carddemo.card.CardRepository.BrowseDirection;
import com.vsergeychik.carddemo.card.CardRepository.CardBrowse;
import com.vsergeychik.carddemo.card.CardRepository.CardReadResult;
import com.vsergeychik.carddemo.card.dto.CardListRequest;
import com.vsergeychik.carddemo.card.dto.CardListRequest.CardKey;
import com.vsergeychik.carddemo.card.dto.CardListRequest.PageCursor;
import com.vsergeychik.carddemo.card.dto.CardListRequest.ScreenRowTable;
import com.vsergeychik.carddemo.card.dto.CardListRequest.SelectionErrorFlags;
import com.vsergeychik.carddemo.card.dto.CardListRequest.SelectionFlags;
import com.vsergeychik.carddemo.card.dto.CardListResponse;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest;
import com.vsergeychik.carddemo.card.dto.CardSelectResponse;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.AidRequestParameter;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import jakarta.validation.Valid;

import java.nio.charset.Charset;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CICS transaction {@code CCLI} - the paged credit card list screen, translated from
 * {@code app/cbl/COCRDLIC.cbl} (1,459 lines) into a stateless {@code @RestController} exposing
 * {@code GET /api/cards}.
 *
 * <p>A row whose three members are all blank is restored to {@code LOW-VALUES}, because that is the state
 * {@code MOVE LOW-VALUES TO WS-ALL-ROWS} [{@code :1124}, {@code :1266}] left an unfilled row in and what a
 * 3270 returns for a field that was never written.
 */
@RestController
public class CardListController {
    private static final int WS_MAX_SCREEN_LINES = 7;

    private static final int FIRST_ROW = 1;

    private static final int LAST_ROW = WS_MAX_SCREEN_LINES;

    private static final int NO_ROW_SELECTED = 0;

    static final String WS_CONTEXT_FRESH_START = "0";

    static final String WS_CONTEXT_FRESH_START_NO = "1";

    static final String LIT_THISPGM = "COCRDLIC";

    static final String LIT_THISTRANID = "CCLI";

    static final String LIT_THISMAPSET = "COCRDLI";

    static final String LIT_THISMAP = "CCRDLIA";

    static final String LIT_MENUPGM = "COMEN01C";

    static final String LIT_MENUTRANID = "CM00";

    static final String LIT_MENUMAPSET = "COMEN01";

    static final String LIT_MENUMAP = "COMEN1A";

    static final String LIT_CARDDTLPGM = "COCRDSLC";

    static final String LIT_CARDDTLTRANID = "CCDL";

    static final String LIT_CARDDTLMAPSET = "COCRDSL";

    static final String LIT_CARDDTLMAP = "CCRDSLA";

    static final String LIT_CARDUPDPGM = "COCRDUPC";

    static final String LIT_CARDUPDTRANID = "CCUP";

    static final String LIT_CARDUPDMAPSET = "COCRDUP";

    static final String LIT_CARDUPDMAP = "CCRDUPA";

    static final String LIT_CARD_FILE = CardRepository.BASE_CICS_FILE_NAME;

    static final String LIT_CARD_FILE_ACCT_PATH = CardRepository.ALTERNATE_INDEX_CICS_FILE_NAME;

    static final String WS_INFORM_REC_ACTIONS = "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";

    static final String WS_EXIT_MESSAGE = "PF03 PRESSED.EXITING";

    static final String WS_NO_RECORDS_FOUND = "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    static final String WS_MORE_THAN_1_ACTION = "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE";

    static final String WS_INVALID_ACTION_CODE = "INVALID ACTION CODE";

    static final String MSG_ACCOUNT_FILTER =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    static final String MSG_CARD_FILTER =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    static final String MSG_NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";

    static final String MSG_NO_PREVIOUS_PAGES = "NO PREVIOUS PAGES TO DISPLAY";

    static final String MSG_NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";

    private static final String FILE_ERROR_PREFIX = "File Error:";

    private static final int FILE_ERROR_PREFIX_LENGTH = 12;

    private static final int ERROR_OPNAME_LENGTH = 8;

    private static final String FILE_ERROR_ON = " on ";

    private static final int ERROR_FILE_LENGTH = 9;

    private static final String FILE_ERROR_RETURNED_RESP = " returned RESP ";

    private static final int ERROR_RESP_LENGTH = 10;

    private static final String FILE_ERROR_RESP2 = ",RESP2 ";

    private static final int FILE_ERROR_TRAILER_LENGTH = 5;

    static final int FILE_ERROR_MESSAGE_LENGTH = FILE_ERROR_PREFIX_LENGTH + ERROR_OPNAME_LENGTH
            + 4 + ERROR_FILE_LENGTH + 15 + ERROR_RESP_LENGTH + 7 + ERROR_RESP_LENGTH
            + FILE_ERROR_TRAILER_LENGTH;

    private static final int RESP_DIGITS = 9;

    static final String ERROR_OPNAME_READ = CardRepository.READ_OPERATION_NAME;

    private static final int WS_ERROR_MSG_LENGTH = CardScreenState.CCARD_ERROR_MSG_LENGTH;

    private static final int WS_INFO_MSG_LENGTH = CardListResponse.INFOMSGO_LENGTH;

    private static final int WS_CARD_RID_CARDNUM_LENGTH = CardRecord.CARD_NUM_LENGTH;

    private static final int ACCT_ID_DIGITS = CardRecord.CARD_ACCT_ID_LENGTH;

    private static final int CARD_NUM_DIGITS = CardScreenState.CC_CARD_NUM_LENGTH;

    static final String CARD_LIST_PATH = "/api/cards";

    static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    static final String EIBAID_PARAM_ALIAS = AidRequestParameter.ALTERNATE_NAME;

    private static final int AID_MIN = 0;

    private static final int AID_MAX = 255;

    private final CardRepository cardRepository;

    private final FixedWidthCodec codec;

    private final Clock clock;

    @Autowired
    public CardListController(CardRepository cardRepository,
                              @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)
                              Charset datasetCharset,
                              Clock clock) {
        this(cardRepository,
                new FixedWidthCodec(Objects.requireNonNull(datasetCharset,
                        "A dataset charset is required: the card list renders fixed-width fields, so "
                                + "the code page is stated explicitly and never taken from the "
                                + "platform")),
                clock);
    }

    public CardListController(CardRepository cardRepository, FixedWidthCodec codec, Clock clock) {
        this.cardRepository = Objects.requireNonNull(cardRepository, "A CardRepository is required: "
                + "the card list reaches CARDDAT only through it, never through a JdbcTemplate and "
                + "never by dataset name");
        this.codec = Objects.requireNonNull(codec, "A fixed-width codec is required: it owns the "
                + "PIC X and PIC 9 MOVE rules every field of this screen is written with");
        this.clock = Objects.requireNonNull(clock, "A Clock is required: FUNCTION CURRENT-DATE is read "
                + "from an injected clock so a test can pin the rendered header");
        verifyScreenContract();
        verifyNavigationContract();
    }

    static void verifyScreenContract() {
        requireAgreement(WS_MAX_SCREEN_LINES, CardListRequest.PAGE_SIZE,
                "WS-MAX-SCREEN-LINES and CardListRequest.PAGE_SIZE");
        requireAgreement(WS_MAX_SCREEN_LINES, CardListResponse.PAGE_SIZE,
                "WS-MAX-SCREEN-LINES and CardListResponse.PAGE_SIZE");
        requireAgreement(LAST_ROW, CardListResponse.LAST_ROW,
                "the last OCCURS subscript and CardListResponse.LAST_ROW");

        // app/cbl/COCRDLIC.cbl:153-171 sums to exactly eighty.
        requireAgreement(80, FILE_ERROR_MESSAGE_LENGTH,
                "the composed width of WS-FILE-ERROR-MESSAGE");

        requireAgreement(ScreenTitles.TITLE_LENGTH, CardListResponse.TITLE01O_LENGTH,
                "CCDA-TITLE01 and TITLE01O");
        requireAgreement(ScreenTitles.TITLE_LENGTH, CardListResponse.TITLE02O_LENGTH,
                "CCDA-TITLE02 and TITLE02O");

        // CSMSG01Y is COPYed at COCRDLIC.cbl:281 and its two texts are never moved by this program, which
        // uses its own 88-level literals instead. Both relationships are asserted rather than assumed,
        // because a future change to either width would change which of the two happens.
        requireOrdering(CardListResponse.INFOMSGO_LENGTH, SystemMessages.MESSAGE_LENGTH,
                "INFOMSGO must stay narrower than a standard CSMSG01Y message");
        requireOrdering(SystemMessages.MESSAGE_LENGTH, CardListResponse.ERRMSGO_LENGTH,
                "ERRMSGO must stay wider than a standard CSMSG01Y message");

        requireAgreement(SecUserRecord.SEC_USR_TYPE_LENGTH, NavigationContext.USER_TYPE_LENGTH,
                "SEC-USR-TYPE and CDEMO-USER-TYPE");

        requireAgreement(CardScreenState.CC_ACCT_ID_LENGTH, CardListResponse.ACCTSIDO_LENGTH,
                "CC-ACCT-ID and ACCTSIDO");
        requireAgreement(CardScreenState.CC_CARD_NUM_LENGTH, CardListResponse.CARDSIDO_LENGTH,
                "CC-CARD-NUM and CARDSIDO");

        requireAgreement(CardRecord.CARD_NUM_LENGTH, CardListResponse.CRDNUM_LENGTH,
                "CARD-NUM and CRDNUMn");
        requireAgreement(CardRecord.CARD_ACCT_ID_LENGTH, CardListResponse.ACCTNO_LENGTH,
                "CARD-ACCT-ID and ACCTNOn");
        requireAgreement(CardRecord.CARD_ACTIVE_STATUS_LENGTH, CardListResponse.CRDSTS_LENGTH,
                "CARD-ACTIVE-STATUS and CRDSTSn");
    }

    static void verifyNavigationContract() {
        requireAgreement(LIT_CARDDTLPGM, CardSelectResponse.THIS_PROGRAM,
                "LIT-CARDDTLPGM and the card-detail program");
        requireAgreement(LIT_CARDDTLTRANID, CardSelectResponse.THIS_TRANID,
                "LIT-CARDDTLTRANID and the card-detail transaction");
        requireAgreement(LIT_CARDDTLMAPSET, CardSelectResponse.THIS_MAPSET,
                "LIT-CARDDTLMAPSET and the card-detail mapset");
        requireAgreement(LIT_CARDDTLMAP, CardSelectResponse.MAP_NAME,
                "LIT-CARDDTLMAP and the card-detail map");

        requireAgreement(NavigationContext.ACCT_ID_LENGTH, CardSelectRequest.ACCTSID_LENGTH,
                "CDEMO-ACCT-ID and the card-detail ACCTSID field");
        requireAgreement(NavigationContext.CARD_NUM_LENGTH, CardSelectRequest.CARDSID_LENGTH,
                "CDEMO-CARD-NUM and the card-detail CARDSID field");

        requireAgreement(8, CardScreenState.CCARD_NEXT_PROG_LENGTH, "CCARD-NEXT-PROG");
        requireAgreement(7, CardScreenState.CCARD_NEXT_MAPSET_LENGTH, "CCARD-NEXT-MAPSET");
        requireAgreement(7, CardScreenState.CCARD_NEXT_MAP_LENGTH, "CCARD-NEXT-MAP");
    }

    static void requireAgreement(int expected, int actual, String subject) {
        if (expected != actual) {
            throw new IllegalStateException(subject + " must agree; app/cbl/COCRDLIC.cbl and its "
                    + "copybooks fix this at " + expected + " but the collaborating type declares "
                    + actual + ". The card list decodes by absolute width, so a disagreement here is "
                    + "silently wrong data rather than a visible failure.");
        }
    }

    static void requireAgreement(String expected, String actual, String subject) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(subject + " must agree; app/cbl/COCRDLIC.cbl declares '"
                    + expected + "' but the collaborating type declares '" + actual + "'. The "
                    + "navigation target is what the client calls next, so a disagreement here sends "
                    + "the operator to the wrong screen.");
        }
    }

    static void requireOrdering(int smaller, int larger, String subject) {
        if (smaller >= larger) {
            throw new IllegalStateException(subject + ", but " + smaller + " is not below " + larger
                    + ". Which of padding and truncation happens on this screen's message lines "
                    + "depends on that ordering.");
        }
    }

    /**
     * {@code GET /api/cards} - the REST projection of CSD transaction {@code CCLI}.
     *
     * @param request the inbound screen and communication area, or {@code null} for the cold start
     * @param eibaid the terminal's attention identifier as an unsigned byte {@code 0..255} under the
     *     canonical parameter name, or {@code null} for {@link CicsAid#DFHENTER}
     * @param eibAid the same value under the alternate spelling; at most one of the two need be sent
     * @return the {@code CCRDLIAO} projection - or, on a transfer of control, the navigation triple naming
     *     where the client goes next - together with this screen's presentation metadata
     * @throws IllegalArgumentException if the AID is outside {@code 0..255}, or if both spellings are
     *     present and disagree
     */
    @GetMapping(path = CARD_LIST_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScreenResponse<CardListResponse> getCards(
            @Valid @RequestBody(required = false) CardListRequest request,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid) {
        WorkArea ws = new WorkArea();
        CardListResponse painted =
                listCards(request, resolveEibAid(AidRequestParameter.resolve(eibaid, eibAid)), ws);
        return ScreenResponse.of(painted, painted.screenMetadata(ws.inputArea));
    }

    static byte resolveEibAid(Integer eibaid) {
        if (eibaid == null) {
            // A CICS terminal always presents some AID. ENTER is the one the program itself falls back to
            // for every key it does not handle (app/cbl/COCRDLIC.cbl:378-380), so it is the only default
            // that cannot reach a branch the operator could not have reached.
            return CicsAid.DFHENTER;
        }
        int value = eibaid;
        if (value < AID_MIN || value > AID_MAX) {
            throw ScreenInputRejectedException.outsideRange(EIBAID_PARAM,
                    "one EIBAID byte", AID_MIN, AID_MAX);
        }
        return (byte) value;
    }

    CardListResponse listCards(CardListRequest incoming, byte eibAid) {
        return listCards(incoming, eibAid, new WorkArea());
    }

    CardListResponse listCards(CardListRequest incoming, byte eibAid, WorkArea ws) {
        Objects.requireNonNull(ws, "A work area is required; the INITIALIZEd state is new WorkArea()");
        CardListRequest request = incoming == null ? new CardListRequest() : incoming;
        ws.inputArea = request;
        CardListResponse response = new CardListResponse();

        ws.wsTranid = codec.movePicX(LIT_THISTRANID, NavigationContext.FROM_TRANID_LENGTH);

        ws.setWsErrorMsgOff();

        ws.eibcalenZero = !request.hasNavigationContext();
        if (ws.eibcalenZero) {
            ws.commarea = NavigationContext.empty()
                    .withFromTranid(LIT_THISTRANID)
                    .withFromProgram(LIT_THISPGM)
                    .withUserTypeUser()
                    .withPgmEnter()
                    .withLastMap(LIT_THISMAP)
                    .withLastMapset(LIT_THISMAPSET);
            ws.cursor = PageCursor.initialised()
                    .withScreenNum(CardListRequest.FIRST_PAGE_SCREEN_NUM)
                    .withLastPageDisplayed(CardListRequest.LAST_PAGE_NOT_SHOWN);
            ws.screenRows = initializedScreenRowTable();
        } else {
            ws.commarea = request.getNavigationContext();
            ws.cursor = request.getPageCursor();
            ws.screenRows = restoreScreenRowTable(request);
        }

        if (ws.commarea.isEnter() && !isThisProgram(ws.commarea)) {
            ws.cursor = PageCursor.initialised();
            ws.screenRows = initializedScreenRowTable();
            ws.commarea = ws.commarea
                    .withPgmEnter()
                    .withLastMap(LIT_THISMAP);
            ws.cursor = ws.cursor
                    .withScreenNum(CardListRequest.FIRST_PAGE_SCREEN_NUM)
                    .withLastPageDisplayed(CardListRequest.LAST_PAGE_NOT_SHOWN);
        }

        // Here the previous token is always spaces, because :300 has just INITIALIZEd CC-WORK-AREA, so an
        // unrecognised byte leaves CCARD-AID matching no condition at all - which the validity guard
        // immediately below then turns into ENTER.
        Optional<AidKey> storedAid = PfKeyResolver.storePfKey(eibAid, ws.ccWorkArea.aidKey());
        storedAid.ifPresent(ws.ccWorkArea::setCcardAidCondition);

        if (!ws.eibcalenZero && isThisProgram(ws.commarea)) {
            receiveMap(request, ws);
        }

        ws.setPfkInvalid();
        if (ws.ccWorkArea.isCcardAidEnter()
                || ws.ccWorkArea.isCcardAidPfk03()
                || ws.ccWorkArea.isCcardAidPfk07()
                || ws.ccWorkArea.isCcardAidPfk08()) {
            ws.setPfkValid();
        }
        if (ws.isPfkInvalid()) {
            ws.ccWorkArea.setCcardAidCondition(AidKey.ENTER);
        }

        if (ws.ccWorkArea.isCcardAidPfk03() && isThisProgram(ws.commarea)) {
            return transferToMenu(ws, response);
        }

        if (ws.ccWorkArea.isCcardAidPfk08()) {
            ws.pfk08Continued = true;
        } else {
            ws.cursor = ws.cursor.withLastPageDisplayed(CardListRequest.LAST_PAGE_NOT_SHOWN);
        }

        return dispatch(request, ws, response);
    }

    boolean isThisProgram(NavigationContext commarea) {
        return codec.movePicX(commarea.fromProgram(), NavigationContext.FROM_PROGRAM_LENGTH)
                .equals(LIT_THISPGM);
    }

    // Every arm terminates the program - six by GO TO COMMON-RETURN and two by EXEC CICS XCTL - so this
    // method always produces the response.

    CardListResponse dispatch(CardListRequest request, WorkArea ws,
            CardListResponse response) {
        if (ws.isInputError()) {
            applyInputErrorReturnState(ws);
            if (!ws.isFlgAcctfilterNotOk() && !ws.isFlgCardfilterNotOk()) {
                readForward(ws);
            }
            sendMap(request, ws, response);
            return commonReturn(ws, response);
        }

        if (ws.ccWorkArea.isCcardAidPfk07() && ws.cursor.isFirstPage()) {
            ws.wsCardRidCardnum = codec.movePicX(ws.cursor.firstCardNum(),
                    WS_CARD_RID_CARDNUM_LENGTH);
            readForward(ws);
            sendMap(request, ws, response);
            return commonReturn(ws, response);
        }

        if (ws.ccWorkArea.isCcardAidPfk03()
                || (ws.commarea.isReenter() && !isThisProgram(ws.commarea))) {
            ws.commarea = NavigationContext.empty()
                    .withFromTranid(LIT_THISTRANID)
                    .withFromProgram(LIT_THISPGM)
                    .withUserTypeUser()
                    .withPgmEnter()
                    .withLastMap(LIT_THISMAP)
                    .withLastMapset(LIT_THISMAPSET);
            ws.cursor = PageCursor.initialised()
                    .withScreenNum(CardListRequest.FIRST_PAGE_SCREEN_NUM)
                    .withLastPageDisplayed(CardListRequest.LAST_PAGE_NOT_SHOWN);
            ws.screenRows = initializedScreenRowTable();
            ws.wsCardRidCardnum = codec.movePicX(ws.cursor.firstCardNum(),
                    WS_CARD_RID_CARDNUM_LENGTH);
            readForward(ws);
            sendMap(request, ws, response);
            return commonReturn(ws, response);
        }

        if (ws.ccWorkArea.isCcardAidPfk08() && ws.cursor.isNextPageExists()) {
            ws.wsCardRidCardnum = codec.movePicX(ws.cursor.lastCardNum(),
                    WS_CARD_RID_CARDNUM_LENGTH);
            ws.cursor = ws.cursor.withScreenNum(addToScreenNum(ws.cursor.screenNum(), 1));
            readForward(ws);
            sendMap(request, ws, response);
            return commonReturn(ws, response);
        }

        if (ws.ccWorkArea.isCcardAidPfk07() && !ws.cursor.isFirstPage()) {
            ws.wsCardRidCardnum = codec.movePicX(ws.cursor.firstCardNum(),
                    WS_CARD_RID_CARDNUM_LENGTH);
            ws.cursor = ws.cursor.withScreenNum(addToScreenNum(ws.cursor.screenNum(), -1));
            readBackwards(ws);
            sendMap(request, ws, response);
            return commonReturn(ws, response);
        }

        if (ws.ccWorkArea.isCcardAidEnter()
                && ws.isViewRequestedOnSelected()
                && isThisProgram(ws.commarea)) {
            return transferToCard(ws, response, LIT_CARDDTLPGM, LIT_CARDDTLMAPSET, LIT_CARDDTLMAP);
        }

        if (ws.ccWorkArea.isCcardAidEnter()
                && ws.isUpdateRequestedOnSelected()
                && isThisProgram(ws.commarea)) {
            return transferToCard(ws, response, LIT_CARDUPDPGM, LIT_CARDUPDMAPSET, LIT_CARDUPDMAP);
        }

        ws.wsCardRidCardnum = codec.movePicX(ws.cursor.firstCardNum(),
                WS_CARD_RID_CARDNUM_LENGTH);
        readForward(ws);
        sendMap(request, ws, response);
        return commonReturn(ws, response);
    }

    /**
     * The seven moves the input-error path makes before it repaints - {@code app/cbl/COCRDLIC.cbl:423-430},
     * repeated verbatim as the unreachable tail at {@code :587-594}.
     *
     * @param ws the work area
     */
    void applyInputErrorReturnState(WorkArea ws) {
        // :423 MOVE WS-ERROR-MSG TO CCARD-ERROR-MSG - X(75) into X(75), so neither padded nor truncated.
        ws.ccWorkArea.setCcardErrorMsg(codec.movePicX(ws.wsErrorMsg,
                CardScreenState.CCARD_ERROR_MSG_LENGTH));
        ws.commarea = ws.commarea
                .withFromProgram(LIT_THISPGM)
                .withLastMapset(LIT_THISMAPSET)
                .withLastMap(LIT_THISMAP);
        ws.ccWorkArea.setCcardNextProg(LIT_THISPGM);
        ws.ccWorkArea.setCcardNextMapset(LIT_THISMAPSET);
        ws.ccWorkArea.setCcardNextMap(LIT_THISMAP);
    }

    static int addToScreenNum(int current, int delta) {
        return Math.abs(current + delta) % 10;
    }

    // That is exactly what the COBOL leaves in CCRDLIAO, and no field is painted here to make the response
    // look more complete than the program made it.

    CardListResponse transferToMenu(WorkArea ws, CardListResponse response) {
        ws.commarea = ws.commarea
                .withFromTranid(LIT_THISTRANID)
                .withFromProgram(LIT_THISPGM)
                .withUserTypeUser()
                .withPgmEnter()
                .withLastMapset(LIT_THISMAPSET)
                .withLastMap(LIT_THISMAP)
                .withToProgram(LIT_MENUPGM);

        ws.ccWorkArea.setCcardNextMapset(LIT_MENUMAPSET);
        ws.ccWorkArea.setCcardNextMap(LIT_THISMAP);

        ws.setWsErrorMsg(codec.movePicX(WS_EXIT_MESSAGE, WS_ERROR_MSG_LENGTH));

        response.setNextTarget(LIT_MENUPGM, LIT_MENUMAPSET, ws.ccWorkArea.getCcardNextMap());
        return transferred(ws, response);
    }

    CardListResponse transferToCard(WorkArea ws, CardListResponse response,
            String program, String mapset, String map) {
        ws.commarea = ws.commarea
                .withFromTranid(LIT_THISTRANID)
                .withFromProgram(LIT_THISPGM)
                .withUserTypeUser()
                .withPgmEnter()
                .withLastMapset(LIT_THISMAPSET)
                .withLastMap(LIT_THISMAP);

        ws.ccWorkArea.setCcardNextProg(program);
        ws.ccWorkArea.setCcardNextMapset(mapset);
        ws.ccWorkArea.setCcardNextMap(map);

        CardListRequest.ScreenRow selected = ws.screenRows.row(ws.iSelected);
        ws.commarea = ws.commarea.withAcctId(digitsOf(selected.acctNo(), ACCT_ID_DIGITS));
        ws.commarea = ws.commarea.withCardNum(digitsOf(selected.cardNum(), CARD_NUM_DIGITS));

        response.setNextTarget(ws.ccWorkArea.getCcardNextProg(),
                ws.ccWorkArea.getCcardNextMapset(),
                ws.ccWorkArea.getCcardNextMap());
        return transferred(ws, response);
    }

    static CardListResponse transferred(WorkArea ws, CardListResponse response) {
        response.setNavigationContext(ws.commarea);
        response.setCardScreenState(ws.ccWorkArea);
        response.setPageCursor(ws.cursor);
        return response;
    }

    CardListResponse commonReturn(WorkArea ws, CardListResponse response) {
        ws.commarea = ws.commarea
                .withFromTranid(LIT_THISTRANID)
                .withFromProgram(LIT_THISPGM)
                .withLastMapset(LIT_THISMAPSET)
                .withLastMap(LIT_THISMAP);
        response.setNavigationContext(ws.commarea);
        response.setCardScreenState(ws.ccWorkArea);
        response.setPageCursor(ws.cursor);
        return response;
    }

    void receiveMap(CardListRequest request, WorkArea ws) {
        receiveScreen(request, ws);
        editInputs(ws);
    }

    void receiveScreen(CardListRequest request, WorkArea ws) {
        ws.ccWorkArea.setCcAcctId(codec.movePicX(request.getAcctsid(),
                CardScreenState.CC_ACCT_ID_LENGTH));
        ws.ccWorkArea.setCcCardNum(codec.movePicX(request.getCardsid(),
                CardScreenState.CC_CARD_NUM_LENGTH));

        SelectionFlags flags = ws.selectionFlags;
        for (int row = FIRST_ROW; row <= LAST_ROW; row++) {
            flags = flags.withSelection(row, selectionCharOf(request, row));
        }
        ws.selectionFlags = flags;
    }

    char selectionCharOf(CardListRequest request, int row) {
        String typed = codec.movePicX(request.row(row).crdSel(), CardListResponse.CRDSEL_LENGTH);
        return typed.charAt(0);
    }

    void editInputs(WorkArea ws) {
        ws.setInputOk();
        ws.setFlgProtectSelectRowsNo();
        editAccount(ws);
        editCard(ws);
        editArray(ws);
    }

    void editAccount(WorkArea ws) {
        ws.setFlgAcctfilterBlank();

        if (ws.ccWorkArea.isCcAcctIdLowValues()
                || ws.ccWorkArea.isCcAcctIdSpaces()
                || ws.ccWorkArea.isCcAcctIdNZeros()) {
            ws.setFlgAcctfilterBlank();
            ws.commarea = ws.commarea.withAcctId(0L);
            return;
        }

        if (!ws.ccWorkArea.isCcAcctIdNumeric()) {
            ws.setInputError();
            ws.setFlgAcctfilterNotOk();
            ws.setFlgProtectSelectRowsYes();
            ws.setWsErrorMsg(codec.movePicX(MSG_ACCOUNT_FILTER, WS_ERROR_MSG_LENGTH));
            ws.commarea = ws.commarea.withAcctId(0L);
            return;
        }
        ws.commarea = ws.commarea.withAcctId(ws.ccWorkArea.getCcAcctIdN());
        ws.setFlgAcctfilterIsValid();
    }

    void editCard(WorkArea ws) {
        ws.setFlgCardfilterBlank();

        if (ws.ccWorkArea.isCcCardNumLowValues()
                || ws.ccWorkArea.isCcCardNumSpaces()
                || ws.ccWorkArea.isCcCardNumNZeros()) {
            ws.setFlgCardfilterBlank();
            ws.commarea = ws.commarea.withCardNum(0L);
            return;
        }

        if (!ws.ccWorkArea.isCcCardNumNumeric()) {
            ws.setInputError();
            ws.setFlgCardfilterNotOk();
            ws.setFlgProtectSelectRowsYes();
            if (ws.isWsErrorMsgOff()) {
                ws.setWsErrorMsg(codec.movePicX(MSG_CARD_FILTER, WS_ERROR_MSG_LENGTH));
            }
            ws.commarea = ws.commarea.withCardNum(0L);
            return;
        }
        ws.commarea = ws.commarea.withCardNum(ws.ccWorkArea.getCcCardNumN());
        ws.setFlgCardfilterIsValid();
    }

    void editArray(WorkArea ws) {
        if (ws.isInputError()) {
            return;
        }

        ws.i = ws.i + ws.selectionFlags.selectedRowCount();

        if (ws.i > 1) {
            ws.setInputError();
            ws.setWsErrorMsg(codec.movePicX(WS_MORE_THAN_1_ACTION, WS_ERROR_MSG_LENGTH));
            ws.selectionErrorFlags = SelectionErrorFlags.fromSelectionFlags(ws.selectionFlags);
        }

        ws.iSelected = NO_ROW_SELECTED;

        for (ws.i = FIRST_ROW; ws.i <= LAST_ROW; ws.i++) {
            if (ws.selectionFlags.isSelectOk(ws.i)) {
                ws.iSelected = ws.i;
                if (isWsMoreThan1Action(ws)) {
                    ws.selectionErrorFlags = ws.selectionErrorFlags.withRowInError(ws.i);
                }
            } else if (ws.selectionFlags.isSelectBlank(ws.i)) {
                continue;
            } else {
                ws.setInputError();
                ws.selectionErrorFlags = ws.selectionErrorFlags.withRowInError(ws.i);
                if (ws.isWsErrorMsgOff()) {
                    ws.setWsErrorMsg(codec.movePicX(WS_INVALID_ACTION_CODE,
                            WS_ERROR_MSG_LENGTH));
                }
            }
        }
    }

    void readForward(WorkArea ws) {
        ws.screenRows = ScreenRowTable.lowValues();

        try (CardBrowse browse = cardRepository.startBrowse(ws.wsCardRidCardnum,
                BrowseDirection.FORWARD)) {
            ws.wsScrnCounter = 0;
            ws.cursor = ws.cursor.withNextPageExists();
            ws.setMoreRecordsToRead();

            while (!ws.isReadLoopExit()) {
                CardReadResult read = browse.readNext();
                recordResponse(ws, read);

                if (read.isNormal() || read.isDuplicateKey()) {
                    CardRecord card = read.requireRecord();
                    filterRecord(ws, card);
                    if (ws.isDonotExcludeThisRecord()) {
                        placeRow(ws, card);
                    }
                    if (ws.wsScrnCounter == WS_MAX_SCREEN_LINES) {
                        ws.setReadLoopExit();
                        ws.cursor = ws.cursor.withLastCardKey(keyOf(card));
                        lookAhead(ws, browse);
                    }
                } else if (read.isEndOfFile()) {
                    ws.setReadLoopExit();
                    ws.cursor = ws.cursor.withNextPageNotExists();
                    ws.cursor = ws.cursor.withLastCardKey(ws.lastCardRead == null
                            ? CardKey.lowValues()
                            : keyOf(ws.lastCardRead));
                    if (ws.isWsErrorMsgOff()) {
                        ws.setWsErrorMsg(codec.movePicX(MSG_NO_MORE_RECORDS, WS_ERROR_MSG_LENGTH));
                    }
                    if (ws.cursor.screenNum() == CardListRequest.FIRST_PAGE_SCREEN_NUM
                            && ws.wsScrnCounter == 0) {
                        ws.setWsErrorMsg(codec.movePicX(WS_NO_RECORDS_FOUND, WS_ERROR_MSG_LENGTH));
                    }
                } else {
                    ws.setReadLoopExit();
                    ws.setWsErrorMsg(codec.movePicX(fileErrorMessage(ws), WS_ERROR_MSG_LENGTH));
                }
            }
        }
    }

    void lookAhead(WorkArea ws, CardBrowse browse) {
        CardReadResult ahead = browse.readNext();
        recordResponse(ws, ahead);
        if (ahead.isNormal() || ahead.isDuplicateKey()) {
            ws.cursor = ws.cursor.withNextPageExists();
            ws.cursor = ws.cursor.withLastCardKey(keyOf(ahead.requireRecord()));
        } else if (ahead.isEndOfFile()) {
            ws.cursor = ws.cursor.withNextPageNotExists();
            if (ws.isWsErrorMsgOff()) {
                ws.setWsErrorMsg(codec.movePicX(MSG_NO_MORE_RECORDS, WS_ERROR_MSG_LENGTH));
            }
        } else {
            ws.setReadLoopExit();
            ws.setWsErrorMsg(codec.movePicX(fileErrorMessage(ws), WS_ERROR_MSG_LENGTH));
        }
    }

    void readBackwards(WorkArea ws) {
        ws.screenRows = ScreenRowTable.lowValues();
        ws.cursor = ws.cursor.withLastCardKeyFromFirst();

        try (CardBrowse browse = cardRepository.startBrowse(ws.wsCardRidCardnum,
                BrowseDirection.BACKWARD)) {
            ws.wsScrnCounter = WS_MAX_SCREEN_LINES + 1;
            ws.cursor = ws.cursor.withNextPageExists();
            ws.setMoreRecordsToRead();

            CardReadResult first = browse.readPrev();
            recordResponse(ws, first);
            if (first.isNormal() || first.isDuplicateKey()) {
                ws.wsScrnCounter--;
            } else {
                ws.setReadLoopExit();
                ws.setWsErrorMsg(codec.movePicX(fileErrorMessage(ws), WS_ERROR_MSG_LENGTH));
                return;
            }

            while (!ws.isReadLoopExit()) {
                CardReadResult read = browse.readPrev();
                recordResponse(ws, read);
                if (read.isNormal() || read.isDuplicateKey()) {
                    CardRecord card = read.requireRecord();
                    filterRecord(ws, card);
                    if (ws.isDonotExcludeThisRecord()) {
                        writeRow(ws, ws.wsScrnCounter, card);
                        ws.wsScrnCounter--;
                        if (ws.wsScrnCounter == 0) {
                            ws.setReadLoopExit();
                            ws.cursor = ws.cursor.withFirstCardKey(keyOf(card));
                        }
                    }
                } else {
                    ws.setReadLoopExit();
                    ws.setWsErrorMsg(codec.movePicX(fileErrorMessage(ws), WS_ERROR_MSG_LENGTH));
                }
            }
        }
    }

    void filterRecord(WorkArea ws, CardRecord card) {
        ws.setDonotExcludeThisRecord();

        if (ws.isFlgAcctfilterIsValid()) {
            String cardAcctId = codec.movePic9(card.cardAcctId(), ACCT_ID_DIGITS);
            if (!cardAcctId.equals(codec.movePicX(ws.ccWorkArea.getCcAcctId(),
                    CardScreenState.CC_ACCT_ID_LENGTH))) {
                ws.setExcludeThisRecord();
                return;
            }
        }

        if (ws.isFlgCardfilterIsValid()) {
            String filterCardNum = codec.movePic9(ws.ccWorkArea.getCcCardNumN(), CARD_NUM_DIGITS);
            if (!codec.movePicX(card.cardNum(), CardRecord.CARD_NUM_LENGTH).equals(filterCardNum)) {
                ws.setExcludeThisRecord();
                return;
            }
        }
    }

    void placeRow(WorkArea ws, CardRecord card) {
        ws.wsScrnCounter++;
        writeRow(ws, ws.wsScrnCounter, card);
        if (ws.wsScrnCounter == FIRST_ROW) {
            ws.cursor = ws.cursor.withFirstCardKey(keyOf(card));
            if (ws.cursor.screenNum() == 0) {
                ws.cursor = ws.cursor.withScreenNum(
                        addToScreenNum(ws.cursor.screenNum(), 1));
            }
        }
    }

    void writeRow(WorkArea ws, int row, CardRecord card) {
        String acctNo = codec.movePic9(card.cardAcctId(), ACCT_ID_DIGITS);
        String cardNum = codec.movePicX(card.cardNum(), CardRecord.CARD_NUM_LENGTH);
        String status = codec.movePicX(card.cardActiveStatus(), CardRecord.CARD_ACTIVE_STATUS_LENGTH);
        ws.screenRows = ws.screenRows.withRow(row,
                new CardListRequest.ScreenRow(acctNo, cardNum, status));
    }

    CardKey keyOf(CardRecord card) {
        return new CardKey(codec.movePicX(card.cardNum(), CardRecord.CARD_NUM_LENGTH),
                card.cardAcctId());
    }

    static void recordResponse(WorkArea ws, CardReadResult read) {
        ws.wsRespCd = read.resp();
        ws.wsReasCd = read.resp2();
        ws.lastOutcome = read.outcome();
        read.record().ifPresent(card -> ws.lastCardRead = card);
    }

    void sendMap(CardListRequest request, WorkArea ws, CardListResponse response) {
        screenInit(ws, response);
        screenArrayInit(ws, response);
        setupArrayAttribs(request, ws, response);
        setupScreenAttrs(request, ws, response);
        setupMessage(ws, response);
        sendScreen(ws, response);
    }

    void screenInit(WorkArea ws, CardListResponse response) {
        DateHeader discardedFirstReading = DateHeader.from(codec, clock);
        ws.wsCurdateData = discardedFirstReading.wsCurdateData();
        DateHeader current = DateHeader.from(codec, clock);
        ws.dateHeader = current;

        response.setPageCursor(ws.cursor);
        ws.setWsNoInfoMessage();
        response.applyScreenInit(current, ws.wsInfoMsg);
    }

    void screenArrayInit(WorkArea ws, CardListResponse response) {
        for (int row = FIRST_ROW; row <= LAST_ROW; row++) {
            CardListRequest.ScreenRow source = ws.screenRows.row(row);
            if (source.isCleared()) {
                continue;
            }
            response.setEditSelect(row, String.valueOf(ws.selectionFlags.at(row)));
            response.setScreenRow(row, CardListResponse.ScreenRow.of(source.acctNo(),
                    source.cardNum(), source.cardStatus()));
        }
    }

    void setupArrayAttribs(CardListRequest request, WorkArea ws, CardListResponse response) {
        boolean protect = ws.isFlgProtectSelectRowsYes();
        response.setEditSelectErrorFlags(ws.selectionErrorFlags.flags());

        for (int row = FIRST_ROW; row <= LAST_ROW; row++) {
            String label = rowSelectionLabel(row);
            if (response.screenRow(row).isLowValues() || protect) {
                putFieldAttribute(request, label,
                        row == FIRST_ROW ? BmsAttributes.DFHBMPRF : BmsAttributes.DFHBMPRO);
                continue;
            }
            response.applyRowSelectHighlight(row, protect);
            if (row != FIRST_ROW && ws.selectionErrorFlags.isRowSelectError(row)) {
                placeCursor(ws, label);
            }
            putFieldAttribute(request, label, BmsAttributes.DFHBMFSE);
        }
    }

    void setupScreenAttrs(CardListRequest request, WorkArea ws, CardListResponse response) {
        String acctLabel = fieldLabel(CardListResponse.ACCTSIDO_ITEM);
        String cardLabel = fieldLabel(CardListResponse.CARDSIDO_ITEM);

        boolean fromMenu = ws.commarea.isEnter()
                && codec.movePicX(ws.commarea.fromProgram(), NavigationContext.FROM_PROGRAM_LENGTH)
                        .equals(LIT_MENUPGM);
        if (!ws.eibcalenZero && !fromMenu) {
            if (ws.isFlgAcctfilterIsValid() || ws.isFlgAcctfilterNotOk()) {
                response.setAcctsido(ws.ccWorkArea.getCcAcctId());
                putFieldAttribute(request, acctLabel, BmsAttributes.DFHBMFSE);
            } else if (ws.commarea.acctId() == 0L) {
                response.setAcctsido(CardScreenState.lowValues(
                        CardListResponse.ACCTSIDO_LENGTH));
            } else {
                response.setAcctsido(codec.movePic9(ws.commarea.acctId(), ACCT_ID_DIGITS));
                putFieldAttribute(request, acctLabel, BmsAttributes.DFHBMFSE);
            }

            if (ws.isFlgCardfilterIsValid() || ws.isFlgCardfilterNotOk()) {
                response.setCardsido(ws.ccWorkArea.getCcCardNum());
                putFieldAttribute(request, cardLabel, BmsAttributes.DFHBMFSE);
            } else if (ws.commarea.cardNum() == 0L) {
                response.setCardsido(CardScreenState.lowValues(
                        CardListResponse.CARDSIDO_LENGTH));
            } else {
                response.setCardsido(codec.movePic9(ws.commarea.cardNum(), CARD_NUM_DIGITS));
                putFieldAttribute(request, cardLabel, BmsAttributes.DFHBMFSE);
            }
        }

        if (ws.acctFilterState().notOk()) {
            response.fieldAttributes(acctLabel).setColour(BmsAttributes.DFHRED);
            placeCursor(ws, acctLabel);
        }
        if (ws.cardFilterState().notOk()) {
            response.fieldAttributes(cardLabel).setColour(BmsAttributes.DFHRED);
            placeCursor(ws, cardLabel);
        }
        if (ws.isInputOk()) {
            placeCursor(ws, acctLabel);
        }
    }

    void setupMessage(WorkArea ws, CardListResponse response) {
        if (ws.isFlgAcctfilterNotOk() || ws.isFlgCardfilterNotOk()) {
            ws.messageArm = MessageArm.FILTER_IN_ERROR;
        } else if (ws.ccWorkArea.isCcardAidPfk07() && ws.cursor.isFirstPage()) {
            ws.setWsErrorMsg(codec.movePicX(MSG_NO_PREVIOUS_PAGES, WS_ERROR_MSG_LENGTH));
            ws.messageArm = MessageArm.NO_PREVIOUS_PAGES;
        } else if (ws.ccWorkArea.isCcardAidPfk08()
                && ws.cursor.isNextPageNotExists()
                && ws.cursor.isLastPageShown()) {
            ws.setWsErrorMsg(codec.movePicX(MSG_NO_MORE_PAGES, WS_ERROR_MSG_LENGTH));
            ws.messageArm = MessageArm.NO_MORE_PAGES;
        } else if (ws.ccWorkArea.isCcardAidPfk08() && ws.cursor.isNextPageNotExists()) {
            ws.setWsInfoMsg(codec.movePicX(WS_INFORM_REC_ACTIONS, WS_INFO_MSG_LENGTH));
            if (ws.cursor.isLastPageNotShown() && ws.cursor.isNextPageNotExists()) {
                ws.cursor = ws.cursor.withLastPageDisplayed(CardListRequest.LAST_PAGE_SHOWN);
            }
            ws.messageArm = MessageArm.LAST_PAGE_REACHED;
        } else if (isWsNoInfoMessage(ws) || ws.cursor.isNextPageExists()) {
            ws.setWsInfoMsg(codec.movePicX(WS_INFORM_REC_ACTIONS, WS_INFO_MSG_LENGTH));
            ws.messageArm = MessageArm.INFORM_REC_ACTIONS;
        } else {
            ws.setWsNoInfoMessage();
            ws.messageArm = MessageArm.NO_INFO_MESSAGE;
        }

        boolean showInfo = !isWsNoInfoMessage(ws) && !isWsNoRecordsFound(ws);
        response.applyMessages(ws.wsErrorMsg, showInfo ? ws.wsInfoMsg : null);
    }

    void sendScreen(WorkArea ws, CardListResponse response) {
        ws.wsRespCd = FileStatus.NORMAL;
        ws.wsReasCd = CardRepository.NO_REASON_CODE;
        ws.lastOutcome = Outcome.OK;
        ws.mapSent = true;
        response.setPageCursor(ws.cursor);
        response.setCardScreenState(ws.ccWorkArea);
        response.setCursorField(ws.cursorField);
    }

    String fileErrorMessage(WorkArea ws) {
        ws.errorOpname = codec.movePicX(ERROR_OPNAME_READ, ERROR_OPNAME_LENGTH);
        ws.errorFile = codec.movePicX(LIT_CARD_FILE, ERROR_FILE_LENGTH);
        ws.errorResp = codec.movePicX(codec.movePic9(ws.wsRespCd, RESP_DIGITS), ERROR_RESP_LENGTH);
        ws.errorResp2 = codec.movePicX(codec.movePic9(ws.wsReasCd, RESP_DIGITS), ERROR_RESP_LENGTH);
        return codec.movePicX(FILE_ERROR_PREFIX, FILE_ERROR_PREFIX_LENGTH)
                + ws.errorOpname
                + FILE_ERROR_ON
                + ws.errorFile
                + FILE_ERROR_RETURNED_RESP
                + ws.errorResp
                + FILE_ERROR_RESP2
                + ws.errorResp2
                + " ".repeat(FILE_ERROR_TRAILER_LENGTH);
    }

    static boolean isWsNoInfoMessage(WorkArea ws) {
        return isEvery(ws.wsInfoMsg, ' ') || isEvery(ws.wsInfoMsg, WorkArea.LOW_VALUE);
    }

    boolean isWsNoRecordsFound(WorkArea ws) {
        return codec.movePicX(WS_NO_RECORDS_FOUND, WS_ERROR_MSG_LENGTH).equals(ws.wsErrorMsg);
    }

    boolean isWsMoreThan1Action(WorkArea ws) {
        return codec.movePicX(WS_MORE_THAN_1_ACTION, WS_ERROR_MSG_LENGTH).equals(ws.wsErrorMsg);
    }

    static boolean isEvery(String value, char character) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != character) {
                return false;
            }
        }
        return true;
    }

    static ScreenRowTable initializedScreenRowTable() {
        List<CardListRequest.ScreenRow> initialised = new ArrayList<>(WS_MAX_SCREEN_LINES);
        for (int row = FIRST_ROW; row <= LAST_ROW; row++) {
            initialised.add(new CardListRequest.ScreenRow(
                    CardScreenState.spaces(CardListRequest.SCREEN_ROW_ACCTNO_LENGTH),
                    CardScreenState.spaces(CardListRequest.SCREEN_ROW_CARD_NUM_LENGTH),
                    CardScreenState.spaces(CardListRequest.SCREEN_ROW_CARD_STATUS_LENGTH)));
        }
        return new ScreenRowTable(initialised);
    }

    ScreenRowTable restoreScreenRowTable(CardListRequest request) {
        ScreenRowTable restored = ScreenRowTable.lowValues();
        for (int row = FIRST_ROW; row <= LAST_ROW; row++) {
            String acctNo = codec.movePicX(request.row(row).acctNo(),
                    CardListRequest.SCREEN_ROW_ACCTNO_LENGTH);
            String cardNum = codec.movePicX(request.row(row).crdNum(),
                    CardListRequest.SCREEN_ROW_CARD_NUM_LENGTH);
            String status = codec.movePicX(request.row(row).crdSts(),
                    CardListRequest.SCREEN_ROW_CARD_STATUS_LENGTH);
            if (isBlankOrLowValues(acctNo) && isBlankOrLowValues(cardNum)
                    && isBlankOrLowValues(status)) {
                continue;   // the row was never written, so it stays LOW-VALUES
            }
            restored = restored.withRow(row,
                    new CardListRequest.ScreenRow(acctNo, cardNum, status));
        }
        return restored;
    }

    static boolean isBlankOrLowValues(String value) {
        return isEvery(value, ' ') || isEvery(value, WorkArea.LOW_VALUE);
    }

    static void putFieldAttribute(CardListRequest request, String label, byte attribute) {
        int reportedLength = request.fieldMetadataOf(label)
                .map(CardListRequest.FieldMetadata::inputLength)
                .orElse(0);
        request.putFieldMetadata(new CardListRequest.FieldMetadata(label, reportedLength,
                (char) (attribute & 0xFF)));
    }

    static void placeCursor(WorkArea ws, String label) {
        ws.cursorField = label;
    }

    static String rowSelectionLabel(int row) {
        return fieldLabel(CardListResponse.crdselItem(row));
    }

    static String fieldLabel(String itemName) {
        return CardListResponse.mapField(itemName).screenFieldPrefix();
    }

    static long digitsOf(String image, int width) {
        if (image.length() != width) {
            return 0L;
        }
        for (int index = 0; index < width; index++) {
            char digit = image.charAt(index);
            if (digit < '0' || digit > '9') {
                return 0L;
            }
        }
        return Long.parseLong(image);
    }

    /**
     * The seven arms of {@code 1400-SETUP-MESSAGE} [{@code app/cbl/COCRDLIC.cbl:897-922}].
     */
    enum MessageArm {
        NONE,

        FILTER_IN_ERROR,

        NO_PREVIOUS_PAGES,

        NO_MORE_PAGES,

        LAST_PAGE_REACHED,

        INFORM_REC_ACTIONS,

        NO_INFO_MESSAGE
    }

    /**
     * One transaction's {@code WORKING-STORAGE}.
     */
    static final class WorkArea {
        static final char LOW_VALUE = '\u0000';

        int wsRespCd = FileStatus.NORMAL;

        int wsReasCd = CardRepository.NO_REASON_CODE;

        String wsTranid = " ".repeat(NavigationContext.FROM_TRANID_LENGTH);

        Outcome lastOutcome = Outcome.OK;

        CardListRequest inputArea;

        CardRecord lastCardRead;

        char wsInputFlag = ' ';

        char wsEditAcctFlag = ' ';

        char wsEditCardFlag = ' ';

        SelectionFlags selectionFlags = SelectionFlags.spacesFilled();

        SelectionErrorFlags selectionErrorFlags = SelectionErrorFlags.none();

        int i;

        int iSelected;

        char flgProtectSelectRows = ' ';

        String wsInfoMsg = " ".repeat(WS_INFO_MSG_LENGTH);

        String wsErrorMsg = " ".repeat(WS_ERROR_MSG_LENGTH);

        char wsPfkFlag = ' ';

        MessageArm messageArm = MessageArm.NONE;

        String wsCardRidCardnum = " ".repeat(WS_CARD_RID_CARDNUM_LENGTH);

        int wsScrnCounter;

        char wsFilterRecordFlag = ' ';

        char wsRecordsToProcessFlag = ' ';

        String errorOpname = " ".repeat(ERROR_OPNAME_LENGTH);

        String errorFile = " ".repeat(ERROR_FILE_LENGTH);

        String errorResp = " ".repeat(ERROR_RESP_LENGTH);

        String errorResp2 = " ".repeat(ERROR_RESP_LENGTH);

        final CardScreenState ccWorkArea = new CardScreenState();

        NavigationContext commarea = NavigationContext.empty();

        PageCursor cursor = PageCursor.initialised();

        ScreenRowTable screenRows = ScreenRowTable.lowValues();

        boolean eibcalenZero;

        boolean pfk08Continued;

        boolean mapSent;

        String cursorField;

        String wsCurdateData = "";

        DateHeader dateHeader;

        boolean isInputOk() {
            return wsInputFlag == '0' || wsInputFlag == ' ' || wsInputFlag == LOW_VALUE;
        }

        boolean isInputError() {
            return wsInputFlag == '1';
        }

        void setInputOk() {
            wsInputFlag = '0';
        }

        void setInputError() {
            wsInputFlag = '1';
        }

        boolean isFlgAcctfilterNotOk() {
            return wsEditAcctFlag == '0';
        }

        boolean isFlgAcctfilterIsValid() {
            return wsEditAcctFlag == '1';
        }

        boolean isFlgAcctfilterBlank() {
            return wsEditAcctFlag == ' ';
        }

        void setFlgAcctfilterNotOk() {
            wsEditAcctFlag = '0';
        }

        void setFlgAcctfilterIsValid() {
            wsEditAcctFlag = '1';
        }

        void setFlgAcctfilterBlank() {
            wsEditAcctFlag = ' ';
        }

        boolean isFlgCardfilterNotOk() {
            return wsEditCardFlag == '0';
        }

        boolean isFlgCardfilterIsValid() {
            return wsEditCardFlag == '1';
        }

        boolean isFlgCardfilterBlank() {
            return wsEditCardFlag == ' ';
        }

        void setFlgCardfilterNotOk() {
            wsEditCardFlag = '0';
        }

        void setFlgCardfilterIsValid() {
            wsEditCardFlag = '1';
        }

        void setFlgCardfilterBlank() {
            wsEditCardFlag = ' ';
        }

        FieldValidationState acctFilterState() {
            return FieldValidationState.of(isFlgAcctfilterNotOk(), isFlgAcctfilterBlank());
        }

        FieldValidationState cardFilterState() {
            return FieldValidationState.of(isFlgCardfilterNotOk(), isFlgCardfilterBlank());
        }

        boolean isFlgProtectSelectRowsNo() {
            return flgProtectSelectRows == '0';
        }

        boolean isFlgProtectSelectRowsYes() {
            return flgProtectSelectRows == '1';
        }

        void setFlgProtectSelectRowsNo() {
            flgProtectSelectRows = '0';
        }

        void setFlgProtectSelectRowsYes() {
            flgProtectSelectRows = '1';
        }

        boolean isPfkValid() {
            return wsPfkFlag == '0';
        }

        boolean isPfkInvalid() {
            return wsPfkFlag == '1';
        }

        void setPfkValid() {
            wsPfkFlag = '0';
        }

        void setPfkInvalid() {
            wsPfkFlag = '1';
        }

        boolean isExcludeThisRecord() {
            return wsFilterRecordFlag == '0';
        }

        boolean isDonotExcludeThisRecord() {
            return wsFilterRecordFlag == '1';
        }

        void setExcludeThisRecord() {
            wsFilterRecordFlag = '0';
        }

        void setDonotExcludeThisRecord() {
            wsFilterRecordFlag = '1';
        }

        boolean isReadLoopExit() {
            return wsRecordsToProcessFlag == '0';
        }

        boolean isMoreRecordsToRead() {
            return wsRecordsToProcessFlag == '1';
        }

        void setReadLoopExit() {
            wsRecordsToProcessFlag = '0';
        }

        void setMoreRecordsToRead() {
            wsRecordsToProcessFlag = '1';
        }

        boolean isWsErrorMsgOff() {
            return isEvery(wsErrorMsg, ' ');
        }

        void setWsErrorMsgOff() {
            wsErrorMsg = " ".repeat(WS_ERROR_MSG_LENGTH);
        }

        void setWsErrorMsg(String message) {
            wsErrorMsg = message;
        }

        void setWsNoInfoMessage() {
            wsInfoMsg = " ".repeat(WS_INFO_MSG_LENGTH);
        }

        void setWsInfoMsg(String message) {
            wsInfoMsg = message;
        }

        boolean isViewRequestedOnSelected() {
            return SelectionFlags.isDetailRequested(iSelected)
                    && selectionFlags.isViewRequestedOn(iSelected);
        }

        boolean isUpdateRequestedOnSelected() {
            return SelectionFlags.isDetailRequested(iSelected)
                    && selectionFlags.isUpdateRequestedOn(iSelected);
        }

        boolean isDetailWasRequested() {
            return SelectionFlags.isDetailRequested(iSelected);
        }
    }
}
