package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.AidRequestParameter;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.transaction.TransactionRepository.Browse;
import com.vsergeychik.carddemo.transaction.TransactionRepository.BrowseDirection;
import com.vsergeychik.carddemo.transaction.TransactionRepository.ReadResult;
import com.vsergeychik.carddemo.transaction.dto.TransactionListRequest;
import com.vsergeychik.carddemo.transaction.dto.TransactionListRequest.PaginationCursor;
import com.vsergeychik.carddemo.transaction.dto.TransactionListResponse;
import com.vsergeychik.carddemo.transaction.dto.TransactionListResponse.TransactionListCursor;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import jakarta.validation.Valid;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CICS transaction {@code CT00} - the paged transaction list screen, translated from
 * {@code app/cbl/COTRN00C.cbl} (699 lines) into a stateless {@code @RestController} exposing
 * {@code GET /api/transactions}.
 *
 * <p>Making it tunable would let a caller ask for a page this screen cannot render and a page count the
 * COBOL would never compute.
 *
 * <p>The name says menu and the source lists: {@code COTRN00C}'s own header reads
 * <em>"Function    : List Transactions from TRANSACT file"</em>. The mandated class name is kept verbatim
 * rather than corrected towards the behaviour, and no server-side conversation state - no
 * {@code HttpSession}, no session attribute - stands behind the paging, which travels in the payload.
 */
@RestController
public class TransactionMenuController {
    private static final Log LOG = LogFactory.getLog(TransactionMenuController.class);

    static final String LIT_THIS_PGM = "COTRN00C";

    static final String LIT_THIS_TRANID = "CT00";

    static final String LIT_THIS_MAPSET = "COTRN00";

    static final String LIT_THIS_MAP = "COTRN0A";

    static final String LIT_TRANSACT_FILE = "TRANSACT";

    static final String LIT_SIGNON_PGM = "COSGN00C";

    static final String LIT_MENU_PGM = "COMEN01C";

    static final String LIT_TRAN_VIEW_PGM = "COTRN01C";

    private static final int PAGE_SIZE = 10;

    private static final int FIRST_ROW = 1;

    private static final int LAST_ROW = PAGE_SIZE;

    private static final int FORWARD_LOOP_LIMIT = PAGE_SIZE + 1;

    private static final int BACKWARD_LOOP_FLOOR = 0;

    private static final int PAGE_NUM_MODULUS = 100_000_000;

    static final int WS_MESSAGE_LENGTH = 80;

    static final int WS_TRAN_AMT_LENGTH = 12;

    static final int WS_TRAN_AMT_INTEGER_DIGITS = 8;

    static final int WS_TRAN_DATE_LENGTH = 8;

    static final String WS_TRAN_DATE_INITIAL = "00/00/00";

    static final int WS_TIMESTAMP_LENGTH = 26;

    private static final String SPACE = " ";

    private static final char LOW_VALUE = '\u0000';

    static final String MSG_INVALID_SELECTION = "Invalid selection. Valid value is S";

    static final String MSG_TRAN_ID_NOT_NUMERIC = "Tran ID must be Numeric ...";

    static final String MSG_ALREADY_TOP_OF_PAGE = "You are already at the top of the page...";

    static final String MSG_ALREADY_BOTTOM_OF_PAGE = "You are already at the bottom of the page...";

    static final String MSG_AT_TOP_OF_PAGE = "You are at the top of the page...";

    static final String MSG_REACHED_BOTTOM_OF_PAGE = "You have reached the bottom of the page...";

    static final String MSG_REACHED_TOP_OF_PAGE = "You have reached the top of the page...";

    static final String MSG_UNABLE_TO_LOOKUP = "Unable to lookup transaction...";

    static final String SELECTION_VIEW_UPPER = "S";

    static final String SELECTION_VIEW_LOWER = "s";

    public static final String TRANSACTIONS_PATH = "/api/transactions";

    static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    static final String EIBAID_PARAM_ALIAS = AidRequestParameter.ALTERNATE_NAME;

    private static final int AID_MIN = 0;

    private static final int AID_MAX = 255;

    static final int RAW_AID_LENGTH = 1;

    static final char MAX_AID_CODE_POINT = 0x00FF;

    private final TransactionRepository transactionRepository;

    private final FixedWidthCodec codec;

    private final Clock clock;

    @Autowired
    public TransactionMenuController(TransactionRepository transactionRepository,
                                     @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)
                                     Charset datasetCharset,
                                     Clock clock) {
        this(transactionRepository,
                new FixedWidthCodec(Objects.requireNonNull(datasetCharset,
                        "A dataset charset is required: the transaction list renders fixed-width "
                                + "fields, so the code page is stated explicitly and never taken "
                                + "from the platform")),
                clock);
    }

    public TransactionMenuController(TransactionRepository transactionRepository,
                                     FixedWidthCodec codec,
                                     Clock clock) {
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "A TransactionRepository is required: this screen reaches TRANSACT only through it, "
                        + "never through a JdbcTemplate and never by dataset name");
        this.codec = Objects.requireNonNull(codec, "A fixed-width codec is required: it owns the "
                + "PIC X and PIC 9 MOVE rules every field of this screen is written with");
        this.clock = Objects.requireNonNull(clock, "A Clock is required: FUNCTION CURRENT-DATE is "
                + "read from an injected clock so a test can pin the rendered heading");
        verifyScreenContract();
    }

    static void verifyScreenContract() {
        requireAgreement(PAGE_SIZE, TransactionListRequest.PAGE_SIZE,
                "the COBOL page size and TransactionListRequest.PAGE_SIZE");
        requireAgreement(PAGE_SIZE, TransactionListResponse.PAGE_SIZE,
                "the COBOL page size and TransactionListResponse.PAGE_SIZE");
        requireAgreement(FIRST_ROW, TransactionListResponse.FIRST_ROW,
                "the first EVALUATE WS-IDX arm and TransactionListResponse.FIRST_ROW");
        requireAgreement(LAST_ROW, TransactionListResponse.LAST_ROW,
                "the last EVALUATE WS-IDX arm and TransactionListResponse.LAST_ROW");
        requireAgreement(FORWARD_LOOP_LIMIT, PAGE_SIZE + 1,
                "the UNTIL WS-IDX >= 11 bound and the page size plus one");

        requireAgreement(WS_TRAN_AMT_LENGTH, TransactionListResponse.TAMT_LENGTH,
                "the width of WS-TRAN-AMT and of TAMT00nO");
        requireAgreement(WS_TRAN_DATE_LENGTH, TransactionListResponse.TDATE_LENGTH,
                "the width of WS-TRAN-DATE and of TDATEnnO");
        requireAgreement(WS_TRAN_DATE_LENGTH, WS_TRAN_DATE_INITIAL.length(),
                "the width of WS-TRAN-DATE and its declared VALUE");
        requireAgreement(WS_TRAN_AMT_LENGTH,
                1 + WS_TRAN_AMT_INTEGER_DIGITS + 1 + CobolDecimal.MONETARY_SCALE,
                "the composed width of PIC +99999999.99");
        requireAgreement(CobolDecimal.MONETARY_SCALE, TranRecord.TRAN_AMT_SCALE,
                "the monetary scale and the declared scale of TRAN-AMT");

        requireAgreement(ScreenTitles.TITLE_LENGTH, TransactionListResponse.TITLE01_LENGTH,
                "the width of CCDA-TITLE01 and of TITLE01O");
        requireAgreement(ScreenTitles.TITLE_LENGTH, TransactionListResponse.TITLE02_LENGTH,
                "the width of CCDA-TITLE02 and of TITLE02O");

        // WS-MESSAGE is X(80) at :38 and must be able to hold CCDA-MSG-INVALID-KEY, which is X(50); the
        // move to ERRMSGO then truncates to 78 (:531).
        requireOrdering(SystemMessages.MESSAGE_LENGTH, WS_MESSAGE_LENGTH,
                "the width of CCDA-MSG-INVALID-KEY and of WS-MESSAGE");
        requireOrdering(TransactionListResponse.ERRMSG_LENGTH, WS_MESSAGE_LENGTH,
                "the width of ERRMSGO and of WS-MESSAGE");

        requireAgreement(LIT_THIS_TRANID, TransactionListRequest.TRANSACTION_ID,
                "WS-TRANID and the transaction the request projects");
        requireAgreement(LIT_THIS_PGM, TransactionListRequest.PROGRAM_NAME,
                "WS-PGMNAME and the program the request projects");
        requireAgreement(LIT_THIS_MAPSET, TransactionListResponse.MAPSET_NAME,
                "the mapset of SEND MAP and the mapset the response names");
        requireAgreement(LIT_THIS_MAP, TransactionListResponse.MAP_NAME,
                "the map of SEND MAP and the map the response names");
        requireAgreement(LIT_THIS_TRANID.length(), NavigationContext.FROM_TRANID_LENGTH,
                "the width of WS-TRANID and of CDEMO-FROM-TRANID");
        requireAgreement(LIT_THIS_PGM.length(), NavigationContext.FROM_PROGRAM_LENGTH,
                "the width of WS-PGMNAME and of CDEMO-FROM-PROGRAM");
        requireAgreement(LIT_TRANSACT_FILE, TransactionRepository.CICS_FILE_NAME,
                "WS-TRANSACT-FILE and the file the repository serves");

        requireAgreement(TransactionListCursor.CURSOR_LENGTH, PaginationCursor.CURSOR_LENGTH,
                "the two projections of CDEMO-CT00-INFO");
        requireAgreement(NavigationContext.COMMAREA_LENGTH + TransactionListCursor.CURSOR_LENGTH,
                TransactionListCursor.COMMAREA_WITH_CURSOR_LENGTH,
                "CARDDEMO-COMMAREA plus CDEMO-CT00-INFO and the total the response declares");

        requireAgreement(TranRecord.TRAN_ID_LENGTH, TransactionRepository.KEY_LENGTH,
                "the width of TRAN-ID and the repository's key length");
        requireAgreement(TranRecord.TRAN_ID_LENGTH, TransactionListResponse.TRNID_LENGTH,
                "the width of TRAN-ID and of TRNIDnnO");
        requireAgreement(TranRecord.TRAN_ORIG_TS_LENGTH, WS_TIMESTAMP_LENGTH,
                "the width of TRAN-ORIG-TS and of WS-TIMESTAMP");

        requireAgreement(SELECTION_VIEW_UPPER, PaginationCursor.SELECTION_VIEW,
                "the WHEN 'S' literal and the selection value the cursor declares");
    }

    static void requireAgreement(int expected, int actual, String what) {
        if (expected != actual) {
            throw new IllegalStateException("A width this screen depends on has drifted: " + what
                    + " must agree, but they are " + expected + " and " + actual);
        }
    }

    static void requireAgreement(String expected, String actual, String what) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException("A literal this screen depends on has drifted: " + what
                    + " must agree, but they are '" + expected + "' and '" + actual + "'");
        }
    }

    static void requireOrdering(int narrower, int wider, String what) {
        if (narrower > wider) {
            throw new IllegalStateException("A width relationship this screen depends on has drifted: "
                    + what + " must be ordered, but they are " + narrower + " and " + wider);
        }
    }

    /**
     * {@code GET /api/transactions} - the REST projection of CSD transaction {@code CT00}.
     *
     * @param request the inbound screen and communication area, or {@code null} for the cold start
     * @param eibaid the terminal's attention identifier as an unsigned byte {@code 0..255} under the
     *     canonical parameter name, or {@code null} to take it from the payload's {@code EIBAID} token
     * @param eibAid the same value under the alternate spelling; at most one of the two need be sent
     * @return the {@code COTRN0AO} projection, or - on a transfer of control - the navigation triple naming
     *     where the client goes next
     * @throws IllegalArgumentException if the AID is outside {@code 0..255}, or if both spellings are
     *     present and disagree
     */
    @GetMapping(path = TRANSACTIONS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScreenResponse<TransactionListResponse> getTransactions(
            @Valid @RequestBody(required = false) TransactionListRequest request,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid) {
        WorkArea ws = new WorkArea();
        TransactionListResponse painted = listTransactions(
                request, resolveEibAid(request, AidRequestParameter.resolve(eibaid, eibAid)), ws);
        return ScreenResponse.of(painted, ws.screenMetadata(painted));
    }

    byte resolveEibAid(TransactionListRequest request, Integer eibaid) {
        if (eibaid != null) {
            return requireAidByte(eibaid);
        }
        return aidByteOfToken(request == null ? null : request.getAid());
    }

    static byte requireAidByte(int eibaid) {
        if (eibaid < AID_MIN || eibaid > AID_MAX) {
            throw ScreenInputRejectedException.outsideRange(EIBAID_PARAM,
                    "one EIBAID byte", AID_MIN, AID_MAX);
        }
        return (byte) eibaid;
    }

    byte aidByteOfToken(String token) {
        if (token == null) {
            return CicsAid.DFHENTER;
        }
        if (isAllSpaces(token) || isAllLowValues(token)) {
            return CicsAid.DFHENTER;
        }
        if (token.length() != RAW_AID_LENGTH) {
            return CicsAid.DFHNULL;
        }
        char stated = token.charAt(0);
        if (stated > MAX_AID_CODE_POINT) {
            return CicsAid.DFHNULL;
        }
        return (byte) stated;
    }

    /**
     * Runs the whole transaction, taking the attention identifier from the payload's {@code aid} image.
     *
     * @param request the inbound payload, or {@code null} for {@code EIBCALEN = 0}
     * @return the response; never {@code null}
     */
    public TransactionListResponse listTransactions(TransactionListRequest request) {
        return listTransactions(request,
                aidByteOfToken(request == null ? null : request.getAid()));
    }

    /**
     * Runs the whole transaction for an explicit {@code EIBAID} byte.
     *
     * @param request the inbound payload, or {@code null} for {@code EIBCALEN = 0}
     * @param eibAid the raw {@code EIBAID} byte
     * @return the response; never {@code null}
     */
    public TransactionListResponse listTransactions(TransactionListRequest request, byte eibAid) {
        return listTransactions(request, eibAid, new WorkArea());
    }

    /**
     * The same transaction, run against a caller-supplied work area so that every {@code WORKING-STORAGE}
     * item the COBOL sets stays observable afterwards.
     *
     * @param incoming the inbound payload, or {@code null} for {@code EIBCALEN = 0}
     * @param eibAid the raw {@code EIBAID} byte
     * @param ws the work area to run in, in its initial state
     * @return the response; never {@code null}
     * @throws NullPointerException if {@code ws} is {@code null}
     */
    public TransactionListResponse listTransactions(TransactionListRequest incoming, byte eibAid,
                                                    WorkArea ws) {
        Objects.requireNonNull(ws, "A work area is required; the declared initial state of "
                + "WORKING-STORAGE is new WorkArea()");
        TransactionListRequest request = incoming == null ? new TransactionListRequest() : incoming;
        TransactionListResponse response = new TransactionListResponse();
        ws.eibAid = eibAid;
        ws.eibcalen = request.commareaLength();

        ws.setErrFlgOff();
        ws.setTransactNotEof();
        ws.commarea = NavigationContext.empty();
        adoptCursor(ws, response, new TransactionListCursor());
        ws.cursor.setNextPageNo();
        ws.setSendEraseYes();

        ws.message = codec.movePicX("", WS_MESSAGE_LENGTH);
        response.clearErrorLine();

        placeCursorOnTranId(request, ws);

        if (ws.eibcalen == 0) {
            ws.commarea = ws.commarea.withToProgram(LIT_SIGNON_PGM);
            returnToPrevScreen(ws, response);
            return response;
        }

        ws.commarea = request.getNavigationContext();
        adoptCursor(ws, response, cursorOf(request.getCursor()));

        if (!ws.commarea.isReenter()) {
            ws.commarea = ws.commarea.withPgmReenter();
            moveLowValuesToOutputMap(response);
            if (processEnterKey(request, ws, response)) {
                return response;
            }
            sendTrnlstScreen(ws, response);
        } else {
            receiveTrnlstScreen(request, ws, response);
            if (PfKeyResolver.isEnter(ws.eibAid)) {
                if (processEnterKey(request, ws, response)) {
                    return response;
                }
            } else if (PfKeyResolver.isPf3(ws.eibAid)) {
                ws.commarea = ws.commarea.withToProgram(LIT_MENU_PGM);
                returnToPrevScreen(ws, response);
                return response;
            } else if (PfKeyResolver.isPf7(ws.eibAid)) {
                processPf7Key(request, ws, response);
            } else if (PfKeyResolver.isPf8(ws.eibAid)) {
                processPf8Key(request, ws, response);
            } else {
                ws.setErrFlgOn();
                placeCursorOnTranId(request, ws);
                ws.message = codec.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                        WS_MESSAGE_LENGTH);
                sendTrnlstScreen(ws, response);
            }
        }

        response.setNavigationContext(ws.commarea);
        return response;
    }

    boolean processEnterKey(TransactionListRequest request, WorkArea ws,
                            TransactionListResponse response) {
        if (!captureRowSelection(ws, response)) {
            ws.cursor.clearSelection();
        }

        if (isPresentCobol(ws.cursor.getTrnSelFlg()) && isPresentCobol(ws.cursor.getTrnSelected())) {
            String flag = ws.cursor.getTrnSelFlg();
            if (SELECTION_VIEW_UPPER.equals(flag) || SELECTION_VIEW_LOWER.equals(flag)) {
                ws.commarea = ws.commarea
                        .withToProgram(LIT_TRAN_VIEW_PGM)
                        .withFromTranid(LIT_THIS_TRANID)
                        .withFromProgram(LIT_THIS_PGM)
                        .withPgmContext(NavigationContext.PGM_CONTEXT_ENTER);
                transferControl(ws, response);
                return true;
            }
            ws.message = codec.movePicX(MSG_INVALID_SELECTION, WS_MESSAGE_LENGTH);
            placeCursorOnTranId(request, ws);
        }

        String typedKey = response.getTrnidinO();
        if (isAllSpaces(typedKey) || isAllLowValues(typedKey)) {
            ws.ridfld = Ridfld.lowValues();
        } else if (isCobolNumeric(typedKey)) {
            // Note what "IS NUMERIC" costs the operator: the item is PIC X(16), so EVERY one of the sixteen
            // positions must be a digit. A short entry such as "123" comes back space-padded and is
            // therefore NOT numeric and IS rejected below.
            ws.ridfld = Ridfld.ofKey(typedKey);
        } else {
            ws.setErrFlgOn();
            ws.message = codec.movePicX(MSG_TRAN_ID_NOT_NUMERIC, WS_MESSAGE_LENGTH);
            placeCursorOnTranId(request, ws);
            sendTrnlstScreen(ws, response);
        }

        placeCursorOnTranId(request, ws);

        // :224 MOVE 0 TO CDEMO-CT00-PAGE-NUM - an ENTER always restarts the page count.
        ws.cursor.setPageNum(0);

        processPageForward(request, ws, response);

        if (!ws.isErrFlgOn()) {
            response.clearTranIdInput();
        }
        return false;
    }

    boolean captureRowSelection(WorkArea ws, TransactionListResponse response) {
        for (int row = FIRST_ROW; row <= LAST_ROW; row++) {
            String cell = response.getRowSelection(row);
            if (isPresentCobol(cell)) {
                ws.cursor.setTrnSelFlg(cell);
                ws.cursor.setTrnSelected(response.getRowTransactionId(row));
                ws.selectedRow = row;
                return true;
            }
        }
        return false;
    }

    void processPf7Key(TransactionListRequest request, WorkArea ws,
                       TransactionListResponse response) {
        String first = ws.cursor.getTrnidFirst();
        ws.ridfld = isAllSpaces(first) || isAllLowValues(first)
                ? Ridfld.lowValues()
                : Ridfld.ofKey(first);
        ws.cursor.setNextPageYes();
        placeCursorOnTranId(request, ws);
        if (ws.cursor.getPageNum() > 1) {
            processPageBackward(request, ws, response);
        } else {
            ws.message = codec.movePicX(MSG_ALREADY_TOP_OF_PAGE, WS_MESSAGE_LENGTH);
            ws.setSendEraseNo();
            sendTrnlstScreen(ws, response);
        }
    }

    void processPf8Key(TransactionListRequest request, WorkArea ws,
                       TransactionListResponse response) {
        String last = ws.cursor.getTrnidLast();
        ws.ridfld = isAllSpaces(last) || isAllLowValues(last)
                ? Ridfld.highValues()
                : Ridfld.ofKey(last);
        placeCursorOnTranId(request, ws);
        if (ws.cursor.isNextPageYes()) {
            processPageForward(request, ws, response);
        } else {
            ws.message = codec.movePicX(MSG_ALREADY_BOTTOM_OF_PAGE, WS_MESSAGE_LENGTH);
            ws.setSendEraseNo();
            sendTrnlstScreen(ws, response);
        }
    }

    void processPageForward(TransactionListRequest request, WorkArea ws,
                            TransactionListResponse response) {
        try (TransactBrowse browse =
                     startbrTransactFile(request, ws, response, BrowseDirection.FORWARD)) {
            if (ws.isErrFlgOn()) {
                return;
            }
            if (!isEnterPf7OrPf3(ws.eibAid)) {
                readnextTransactFile(request, ws, response, browse);
            }
            if (ws.isTransactNotEof() && ws.isErrFlgOff()) {
                response.initializeAllTranData();
            }
            ws.idx = FIRST_ROW;
            while (ws.idx < FORWARD_LOOP_LIMIT && ws.isTransactNotEof() && ws.isErrFlgOff()) {
                ReadResult read = readnextTransactFile(request, ws, response, browse);
                if (ws.isTransactNotEof() && ws.isErrFlgOff()) {
                    populateTranData(ws, response, read.requireRecord());
                    ws.idx = ws.idx + 1;
                }
            }
            if (ws.isTransactNotEof() && ws.isErrFlgOff()) {
                addOneToPageNum(ws);
                readnextTransactFile(request, ws, response, browse);
                if (ws.isTransactNotEof() && ws.isErrFlgOff()) {
                    ws.cursor.setNextPageYes();
                } else {
                    ws.cursor.setNextPageNo();
                }
            } else {
                ws.cursor.setNextPageNo();
                if (ws.idx > FIRST_ROW) {
                    addOneToPageNum(ws);
                }
            }
            endbrTransactFile(browse);
            response.movePageNumberToScreen(codec, ws.cursor.getPageNum());
            response.clearTranIdInput();
            sendTrnlstScreen(ws, response);
        }
    }

    void processPageBackward(TransactionListRequest request, WorkArea ws,
                             TransactionListResponse response) {
        try (TransactBrowse browse =
                     startbrTransactFile(request, ws, response, BrowseDirection.BACKWARD)) {
            if (ws.isErrFlgOn()) {
                return;
            }
            if (!isEnterOrPf8(ws.eibAid)) {
                readprevTransactFile(request, ws, response, browse);
            }
            if (ws.isTransactNotEof() && ws.isErrFlgOff()) {
                response.initializeAllTranData();
            }
            ws.idx = LAST_ROW;
            while (ws.idx > BACKWARD_LOOP_FLOOR && ws.isTransactNotEof() && ws.isErrFlgOff()) {
                ReadResult read = readprevTransactFile(request, ws, response, browse);
                if (ws.isTransactNotEof() && ws.isErrFlgOff()) {
                    populateTranData(ws, response, read.requireRecord());
                    ws.idx = ws.idx - 1;
                }
            }
            if (ws.isTransactNotEof() && ws.isErrFlgOff()) {
                readprevTransactFile(request, ws, response, browse);
                if (ws.cursor.isNextPageYes()) {
                    if (ws.isTransactNotEof() && ws.isErrFlgOff()
                            && ws.cursor.getPageNum() > 1) {
                        ws.cursor.setPageNum(ws.cursor.getPageNum() - 1);
                    } else {
                        ws.cursor.setPageNum(1);
                    }
                }
            }
            endbrTransactFile(browse);
            response.movePageNumberToScreen(codec, ws.cursor.getPageNum());
            sendTrnlstScreen(ws, response);
        }
    }

    private static void addOneToPageNum(WorkArea ws) {
        ws.cursor.setPageNum((ws.cursor.getPageNum() + 1) % PAGE_NUM_MODULUS);
    }

    static boolean isEnterPf7OrPf3(byte eibAid) {
        return PfKeyResolver.isEnter(eibAid)
                || PfKeyResolver.isPf7(eibAid)
                || PfKeyResolver.isPf3(eibAid);
    }

    static boolean isEnterOrPf8(byte eibAid) {
        return PfKeyResolver.isEnter(eibAid) || PfKeyResolver.isPf8(eibAid);
    }

    void populateTranData(WorkArea ws, TransactionListResponse response, TranRecord record) {
        ws.tranAmt = editedAmount(record.tranAmt());
        ws.tranDate = editedTranDate(record.tranOrigTs());
        response.populateTranData(codec, ws.idx, record.tranId(), ws.tranDate,
                record.tranDesc(), ws.tranAmt);
    }

    TransactBrowse startbrTransactFile(TransactionListRequest request, WorkArea ws,
                                       TransactionListResponse response, BrowseDirection direction) {
        TransactBrowse browse = TransactBrowse.position(transactionRepository, direction, ws.ridfld);
        ReadResult positioning = browse.positioningOutcome();
        ws.startbrOutcome = positioning.outcome();
        switch (positionArmOf(positioning.outcome())) {
            case NORMAL -> {
            }
            case END_OF_DATA -> {
                ws.setTransactEof();
                ws.message = codec.movePicX(MSG_AT_TOP_OF_PAGE, WS_MESSAGE_LENGTH);
                placeCursorOnTranId(request, ws);
                sendTrnlstScreen(ws, response);
            }
            case FAILED -> {
                reportFileFailure(positioning);
                ws.setErrFlgOn();
                ws.message = codec.movePicX(MSG_UNABLE_TO_LOOKUP, WS_MESSAGE_LENGTH);
                placeCursorOnTranId(request, ws);
                sendTrnlstScreen(ws, response);
            }
        }
        return browse;
    }

    ReadResult readnextTransactFile(TransactionListRequest request, WorkArea ws,
                                    TransactionListResponse response, TransactBrowse browse) {
        ReadResult result = browse.read();
        ws.lastReadOutcome = result.outcome();
        ws.readCount = ws.readCount + 1;
        switch (readArmOf(result.outcome())) {
            case NORMAL -> {
            }
            case END_OF_DATA -> {
                ws.setTransactEof();
                ws.message = codec.movePicX(MSG_REACHED_BOTTOM_OF_PAGE, WS_MESSAGE_LENGTH);
                placeCursorOnTranId(request, ws);
                sendTrnlstScreen(ws, response);
            }
            case FAILED -> {
                reportFileFailure(result);
                ws.setErrFlgOn();
                ws.message = codec.movePicX(MSG_UNABLE_TO_LOOKUP, WS_MESSAGE_LENGTH);
                placeCursorOnTranId(request, ws);
                sendTrnlstScreen(ws, response);
            }
        }
        return result;
    }

    ReadResult readprevTransactFile(TransactionListRequest request, WorkArea ws,
                                    TransactionListResponse response, TransactBrowse browse) {
        ReadResult result = browse.read();
        ws.lastReadOutcome = result.outcome();
        ws.readCount = ws.readCount + 1;
        switch (readArmOf(result.outcome())) {
            case NORMAL -> {
            }
            case END_OF_DATA -> {
                ws.setTransactEof();
                ws.message = codec.movePicX(MSG_REACHED_TOP_OF_PAGE, WS_MESSAGE_LENGTH);
                placeCursorOnTranId(request, ws);
                sendTrnlstScreen(ws, response);
            }
            case FAILED -> {
                reportFileFailure(result);
                ws.setErrFlgOn();
                ws.message = codec.movePicX(MSG_UNABLE_TO_LOOKUP, WS_MESSAGE_LENGTH);
                placeCursorOnTranId(request, ws);
                sendTrnlstScreen(ws, response);
            }
        }
        return result;
    }

    void endbrTransactFile(TransactBrowse browse) {
        browse.endBrowse();
    }

    private static void reportFileFailure(ReadResult result) {
        LOG.error("A read of " + TransactionRepository.CICS_FILE_NAME + " for the transaction list "
                + "screen reported " + result.describeResponse()
                + "; reporting '" + MSG_UNABLE_TO_LOOKUP + "' to the operator");
    }

    static FileArm readArmOf(Outcome outcome) {
        return switch (outcome) {
            case OK -> FileArm.NORMAL;
            case END_OF_FILE -> FileArm.END_OF_DATA;
            case NOT_FOUND, DUPLICATE, OTHER -> FileArm.FAILED;
        };
    }

    static FileArm positionArmOf(Outcome outcome) {
        return switch (outcome) {
            case OK -> FileArm.NORMAL;
            case END_OF_FILE, NOT_FOUND -> FileArm.END_OF_DATA;
            case DUPLICATE, OTHER -> FileArm.FAILED;
        };
    }

    /**
     * The three arms every file {@code EVALUATE WS-RESP-CD} in this program has, named once so the mapping
     * is stated in one place instead of being re-derived at each call site.
     */
    enum FileArm {
        /**
         * {@code WHEN DFHRESP(NORMAL)} - a record was returned.
         */
        NORMAL,

        /**
         * {@code WHEN DFHRESP(ENDFILE)} on a read, {@code WHEN DFHRESP(NOTFND)} on a position.
         */
        END_OF_DATA,

        FAILED
    }

    void returnToPrevScreen(WorkArea ws, TransactionListResponse response) {
        String target = ws.commarea.toProgram();
        if (isAllLowValues(target) || isAllSpaces(target)) {
            ws.commarea = ws.commarea.withToProgram(LIT_SIGNON_PGM);
        }
        ws.commarea = ws.commarea
                .withFromTranid(LIT_THIS_TRANID)
                .withFromProgram(LIT_THIS_PGM)
                .withPgmContext(NavigationContext.PGM_CONTEXT_ENTER);
        transferControl(ws, response);
    }

    static void transferControl(WorkArea ws, TransactionListResponse response) {
        response.setNavigationContext(ws.commarea);
        response.echoTransferTarget(ws.commarea);
        ws.transferred = true;
    }

    void sendTrnlstScreen(WorkArea ws, TransactionListResponse response) {
        populateHeaderInfo(response);
        response.moveMessageToErrorLine(codec, ws.message);
        ws.lastSendErase = ws.isSendEraseYes();
        ws.sendCount = ws.sendCount + 1;
    }

    void receiveTrnlstScreen(TransactionListRequest request, WorkArea ws,
                             TransactionListResponse response) {
        for (String prefix : TransactionListResponse.fieldPrefixes()) {
            response.setPayloadValue(prefix, codec.movePicX(request.getPayloadValue(prefix),
                    TransactionListResponse.declaredLength(prefix)));
        }
        ws.receiveOutcome = Outcome.OK;
    }

    void moveLowValuesToOutputMap(TransactionListResponse response) {
        for (String prefix : TransactionListResponse.fieldPrefixes()) {
            response.setPayloadValue(prefix,
                    ScreenFieldImage.unpainted(TransactionListResponse.declaredLength(prefix)));
        }
        response.resetAttributesToLowValues();
    }

    void populateHeaderInfo(TransactionListResponse response) {
        response.populateHeaderInfo(DateHeader.from(codec, clock));
    }

    void placeCursorOnTranId(TransactionListRequest request, WorkArea ws) {
        request.positionCursorAt(TransactionListRequest.TRNIDIN_FIELD);
        ws.cursorField = TransactionListRequest.TRNIDIN_FIELD;
        ws.cursorPositions = ws.cursorPositions + 1;
    }

    private static void adoptCursor(WorkArea ws, TransactionListResponse response,
                                    TransactionListCursor seed) {
        response.setCursor(seed);
        ws.cursor = response.getCursor();
    }

    static TransactionListCursor cursorOf(PaginationCursor inbound) {
        Objects.requireNonNull(inbound, "A pagination cursor is required: CDEMO-CT00-INFO travels "
                + "with every request this screen serves, and defaulting it would silently restart "
                + "the browse");
        TransactionListCursor cursor = new TransactionListCursor();
        cursor.setTrnidFirst(inbound.getTrnidFirst());
        cursor.setTrnidLast(inbound.getTrnidLast());
        cursor.setPageNum(inbound.getPageNum());
        cursor.setNextPageFlg(inbound.getNextPageFlg());
        cursor.setTrnSelFlg(inbound.getTrnSelFlg());
        cursor.setTrnSelected(inbound.getTrnSelected());
        return cursor;
    }

    private static final int WS_TRAN_AMT_DIGITS =
            WS_TRAN_AMT_INTEGER_DIGITS + CobolDecimal.MONETARY_SCALE;

    private static final BigInteger AMOUNT_MODULUS = BigInteger.TEN.pow(WS_TRAN_AMT_DIGITS);

    private static final String SIGN_POSITIVE = "+";

    private static final String SIGN_NEGATIVE = "-";

    private static final String DECIMAL_POINT = ".";

    private static final int TS_YEAR_OFFSET = 0;

    private static final int TS_YEAR_LAST_TWO_OFFSET =
            DateHeader.YEAR_DIGITS - DateHeader.TWO_DIGIT_YEAR_DIGITS;

    private static final int TS_MONTH_OFFSET =
            TS_YEAR_OFFSET + DateHeader.YEAR_DIGITS + DateHeader.SEPARATOR_LENGTH;

    private static final int TS_DAY_OFFSET =
            TS_MONTH_OFFSET + DateHeader.MONTH_DIGITS + DateHeader.SEPARATOR_LENGTH;

    String editedAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "An amount is required to render WS-TRAN-AMT");
        BigDecimal stored = CobolDecimal.store(amount, CobolDecimal.MONETARY_SCALE);
        BigInteger digits = stored.abs().unscaledValue().mod(AMOUNT_MODULUS);
        String rendered = codec.movePic9(digits.longValueExact(), WS_TRAN_AMT_DIGITS);
        String sign = stored.signum() < 0 ? SIGN_NEGATIVE : SIGN_POSITIVE;
        return sign
                + rendered.substring(0, WS_TRAN_AMT_INTEGER_DIGITS)
                + DECIMAL_POINT
                + rendered.substring(WS_TRAN_AMT_INTEGER_DIGITS);
    }

    String editedTranDate(String originationTimestamp) {
        Objects.requireNonNull(originationTimestamp, "An origination timestamp is required to render "
                + "WS-TRAN-DATE; TRAN-ORIG-TS is a fixed 26-byte field and is never absent");
        String image = codec.movePicX(originationTimestamp, WS_TIMESTAMP_LENGTH);
        try {
            return DateHeader.from(codec, clock)
                    .withTimestampImage(image)
                    .withCurdateMmDdYyFromTimestamp()
                    .wsCurdateMmDdYy();
        } catch (IllegalArgumentException notAWellFormedTimestamp) {
            return editedTranDateByPosition(image);
        }
    }

    String editedTranDateByPosition(String image) {
        String yearLastTwo = image.substring(TS_YEAR_OFFSET + TS_YEAR_LAST_TWO_OFFSET,
                TS_YEAR_OFFSET + DateHeader.YEAR_DIGITS);
        String month = image.substring(TS_MONTH_OFFSET, TS_MONTH_OFFSET + DateHeader.MONTH_DIGITS);
        String day = image.substring(TS_DAY_OFFSET, TS_DAY_OFFSET + DateHeader.DAY_DIGITS);
        return month + DateHeader.DATE_SEPARATOR + day + DateHeader.DATE_SEPARATOR + yearLastTwo;
    }

    static boolean isAllSpaces(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != ' ') {
                return false;
            }
        }
        return true;
    }

    static boolean isAllLowValues(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != LOW_VALUE) {
                return false;
            }
        }
        return true;
    }

    static boolean isPresentCobol(String value) {
        return !isAllSpaces(value) && !isAllLowValues(value);
    }

    static boolean isCobolNumeric(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Which of the three things the source moves into {@code TRAN-ID} a browse is anchored on.
     */
    public enum RidfldKind {
        /**
         * {@code MOVE LOW-VALUES TO TRAN-ID} - {@code :207} and {@code :237}: before the first key.
         */
        LOW_VALUES,

        /**
         * {@code MOVE HIGH-VALUES TO TRAN-ID} - {@code :260}: after the last key.
         */
        HIGH_VALUES,

        KEY
    }

    /**
     * The {@code RIDFLD} of {@code STARTBR}: either a concrete key or one of the two boundaries.
     *
     * @param kind which of the three the source moved in
     * @param key the concrete key when {@code kind} is {@link RidfldKind#KEY}, otherwise empty
     */
    public record Ridfld(RidfldKind kind, String key) {
        public Ridfld {
            Objects.requireNonNull(kind, "A RIDFLD kind is required");
            Objects.requireNonNull(key, "A RIDFLD key is required; pass an empty string for a "
                    + "boundary");
        }

        /**
         * The {@code MOVE LOW-VALUES TO TRAN-ID} form.
         *
         * @return a boundary {@code RIDFLD} below every key
         */
        public static Ridfld lowValues() {
            return new Ridfld(RidfldKind.LOW_VALUES, "");
        }

        /**
         * The {@code MOVE HIGH-VALUES TO TRAN-ID} form.
         *
         * @return a boundary {@code RIDFLD} above every key
         */
        public static Ridfld highValues() {
            return new Ridfld(RidfldKind.HIGH_VALUES, "");
        }

        public static Ridfld ofKey(String key) {
            return new Ridfld(RidfldKind.KEY, key);
        }

        /**
         * Whether this is a boundary rather than a concrete key.
         *
         * @return {@code true} for either boundary
         */
        public boolean isBoundary() {
            return kind != RidfldKind.KEY;
        }

        /**
         * Whether a browse anchored here can return nothing at all, without a backend call being needed to
         * establish it.
         *
         * <p>Two of the six combinations are unreachable by construction: nothing can be at or after
         * {@code HIGH-VALUES} going forward, and nothing can be at or before {@code LOW-VALUES} going
         * backward.
         *
         * @param direction the direction the browse will be walked
         * @return {@code true} if no record can satisfy this anchor in that direction
         */
        public boolean isUnreachable(BrowseDirection direction) {
            return (kind == RidfldKind.HIGH_VALUES && direction == BrowseDirection.FORWARD)
                    || (kind == RidfldKind.LOW_VALUES && direction == BrowseDirection.BACKWARD);
        }
    }

    /**
     * A {@code TRANSACT} browse paired with the {@code RIDFLD} decision that precedes it.
     */
    static final class TransactBrowse implements AutoCloseable {
        private final BrowseDirection direction;

        private final Browse browse;

        private final ReadResult positioning;

        private TransactBrowse(BrowseDirection direction, Browse browse, ReadResult positioning) {
            this.direction = direction;
            this.browse = browse;
            this.positioning = positioning;
        }

        static TransactBrowse position(TransactionRepository repository, BrowseDirection direction,
                                       Ridfld ridfld) {
            Objects.requireNonNull(repository, "A repository is required to position a browse");
            Objects.requireNonNull(direction, "A direction is required to position a browse");
            Objects.requireNonNull(ridfld, "A RIDFLD is required to position a browse");
            if (ridfld.isUnreachable(direction)) {
                return new TransactBrowse(direction, null,
                        ReadResult.endOfFile(TransactionRepository.CICS_FILE_NAME));
            }
            Browse opened = ridfld.isBoundary()
                    ? repository.startBrowse(direction)
                    : repository.startBrowse(ridfld.key(), direction);
            ReadResult positioning = opened.positioningResult();
            if (opened.isStarted()) {
                return new TransactBrowse(direction, opened, positioning);
            }
            opened.endBrowse();
            return new TransactBrowse(direction, opened, positioning);
        }

        ReadResult positioningOutcome() {
            return positioning;
        }

        ReadResult read() {
            if (browse == null) {
                return ReadResult.other(TransactionRepository.CICS_FILE_NAME,
                        TransactionRepository.PERMANENT_ERROR_STATUS);
            }
            return direction == BrowseDirection.FORWARD ? browse.readNext() : browse.readPrev();
        }

        void endBrowse() {
            if (browse != null) {
                browse.endBrowse();
            }
        }

        /**
         * Ends the browse, so a handle can be used in a try-with-resources block.
         */
        @Override
        public void close() {
            endBrowse();
        }
    }

    /**
     * The program's {@code WORKING-STORAGE}, as a per-request value.
     *
     * <p>Every item the source declares is here, including the one it never uses, and every one is readable
     * so that a parity case can assert the state the COBOL would have left behind - including the items
     * that never reach the payload, which is where {@code WS-SEND-ERASE-FLG} and the cursor position live.
     */
    public static final class WorkArea {
        public static final char FLAG_YES = 'Y';

        public static final char FLAG_NO = 'N';

        private char errFlg = FLAG_NO;

        private char transactEof = FLAG_NO;

        private char sendEraseFlg = FLAG_YES;

        private String message = "";

        private int idx;

        private final int recCount;

        private String tranAmt = "";

        private String tranDate = WS_TRAN_DATE_INITIAL;

        private NavigationContext commarea = NavigationContext.empty();

        private TransactionListCursor cursor = new TransactionListCursor();

        private Ridfld ridfld = Ridfld.lowValues();

        private byte eibAid = CicsAid.DFHENTER;

        private int eibcalen;

        private String cursorField;

        private int cursorPositions;

        private int sendCount;

        private boolean lastSendErase = true;

        private int readCount;

        private Outcome startbrOutcome;

        private Outcome lastReadOutcome;

        private Outcome receiveOutcome;

        private boolean transferred;

        private int selectedRow;

        /**
         * Creates the declared {@code VALUE} state of {@code WORKING-STORAGE}.
         */
        public WorkArea() {
            this.recCount = 0;
        }

        public boolean isErrFlgOn() {
            return errFlg == FLAG_YES;
        }

        public boolean isErrFlgOff() {
            return errFlg == FLAG_NO;
        }

        void setErrFlgOn() {
            errFlg = FLAG_YES;
        }

        void setErrFlgOff() {
            errFlg = FLAG_NO;
        }

        public boolean isTransactEof() {
            return transactEof == FLAG_YES;
        }

        public boolean isTransactNotEof() {
            return transactEof == FLAG_NO;
        }

        void setTransactEof() {
            transactEof = FLAG_YES;
        }

        void setTransactNotEof() {
            transactEof = FLAG_NO;
        }

        public boolean isSendEraseYes() {
            return sendEraseFlg == FLAG_YES;
        }

        public boolean isSendEraseNo() {
            return sendEraseFlg == FLAG_NO;
        }

        void setSendEraseYes() {
            sendEraseFlg = FLAG_YES;
        }

        void setSendEraseNo() {
            sendEraseFlg = FLAG_NO;
        }

        public char errFlg() {
            return errFlg;
        }

        public char transactEof() {
            return transactEof;
        }

        public char sendEraseFlg() {
            return sendEraseFlg;
        }

        public String message() {
            return message;
        }

        public int idx() {
            return idx;
        }

        public int recCount() {
            return recCount;
        }

        public String tranAmt() {
            return tranAmt;
        }

        public String tranDate() {
            return tranDate;
        }

        public NavigationContext commarea() {
            return commarea;
        }

        public TransactionListCursor cursor() {
            return cursor;
        }

        public Ridfld ridfld() {
            return ridfld;
        }

        public byte eibAid() {
            return eibAid;
        }

        public int eibcalen() {
            return eibcalen;
        }

        public String cursorField() {
            return cursorField;
        }

        public int cursorPositions() {
            return cursorPositions;
        }

        /**
         * This screen's presentation metadata, in the shared envelope every online response publishes.
         *
         * @param painted the response this run produced; must not be {@code null}
         * @return the metadata; never {@code null}
         * @throws NullPointerException if {@code painted} is {@code null}
         */
        public ScreenMetadata screenMetadata(TransactionListResponse painted) {
            Objects.requireNonNull(painted, "A painted screen is required to read its attribute quads");
            Map<String, ScreenMetadata.FieldMetadata> fields = new LinkedHashMap<>();
            for (Map.Entry<String, TransactionListResponse.FieldAttributes> quad
                    : painted.allAttributes().entrySet()) {
                fields.put(quad.getKey(), ScreenMetadata.FieldMetadata.of(quad.getValue().colour(),
                        quad.getValue().programmedSymbols(),
                        quad.getValue().highlight(),
                        quad.getValue().validation()));
            }
            return ScreenMetadata.of(cursorField,
                    painted.attributesOf(TransactionListResponse.ERRMSG).colour(),
                    isSendEraseYes(),
                    fields);
        }

        public int sendCount() {
            return sendCount;
        }

        public boolean lastSendErase() {
            return lastSendErase;
        }

        public int readCount() {
            return readCount;
        }

        public Outcome startbrOutcome() {
            return startbrOutcome;
        }

        public Outcome lastReadOutcome() {
            return lastReadOutcome;
        }

        public Outcome receiveOutcome() {
            return receiveOutcome;
        }

        public boolean isTransferred() {
            return transferred;
        }

        public int selectedRow() {
            return selectedRow;
        }
    }
}
