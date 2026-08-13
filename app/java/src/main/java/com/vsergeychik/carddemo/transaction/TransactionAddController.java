package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.AidRequestParameter;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.TransactionRepository.ReadResult;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest.Ct01Info;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddResponse;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddResponse.ScreenField;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.math.BigInteger;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code app/cbl/COTRN01C.cbl} - CSD transaction {@code CT01}, mapset {@code COTRN01}, map {@code COTRN1A}
 * - translated to a stateless Spring Web controller.
 *
 * <p>The class name and the behaviour disagree, and the source is the authority. {@code COTRN01C}'s own
 * header reads <em>"Function    : View a Transaction from TRANSACT file"</em>, so this controller reads a
 * transaction and writes nothing: it issues no {@code WRITE}, {@code REWRITE} or {@code DELETE}, and
 * {@code app/cbl/COTRN01C.cbl} contains none. The mandated class name is kept verbatim rather than
 * corrected towards the behaviour, and its sibling {@link TransactionViewController} carries the
 * mirror-image swap - that one, translated from {@code COTRN02C}, is the screen that adds a transaction.
 */
@RestController
public final class TransactionAddController {
    private static final Log LOG = LogFactory.getLog(TransactionAddController.class);

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COTRN01C'} - {@code :36}.
     */
    public static final String PROGRAM_NAME = "COTRN01C";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CT01'} - {@code :37}; CSD transaction at {@code :429}.
     */
    public static final String TRANSACTION_ID = "CT01";

    /**
     * {@code MAPSET('COTRN01')} - {@code :221}; CSD mapset definition at {@code :149}.
     */
    public static final String MAPSET_NAME = "COTRN01";

    /**
     * {@code MAP('COTRN1A')} - {@code :220}, the single {@code DFHMDI} of the mapset.
     */
    public static final String MAP_NAME = "COTRN1A";

    /**
     * {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} - {@code :39}, the CICS file name the
     * {@code READ} at {@code :270} names.
     */
    public static final String TRANSACT_FILE_NAME = TransactionRepository.CICS_FILE_NAME;

    /**
     * The path this screen is reached at: {@code GET /api/transactions/&#123;tranId&#125;}.
     */
    public static final String TRANSACTION_DETAIL_PATH = "/api/transactions/{tranId}";

    public static final String TRAN_ID_VARIABLE = "tranId";

    static final String TRNIDIN_MEMBER = "trnidin";

    static final String AID_MEMBER = "aid";

    static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    static final String EIBAID_PARAM_ALIAS = AidRequestParameter.ALTERNATE_NAME;

    static final int AID_MIN = 0;

    static final int AID_MAX = 255;

    /**
     * {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} - {@code :95} on {@code EIBCALEN = 0}, and again at
     * {@code :200} as {@code RETURN-TO-PREV-SCREEN}'s own default.
     */
    public static final String SIGN_ON_PROGRAM = "COSGN00C";

    /**
     * {@code MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM} - {@code :117}, the PF3 target when
     * {@code CDEMO-FROM-PROGRAM} carries no caller.
     */
    public static final String MAIN_MENU_PROGRAM = "COMEN01C";

    /**
     * {@code MOVE 'COTRN00C' TO CDEMO-TO-PROGRAM} - {@code :126}, the PF5 target.
     */
    public static final String TRANSACTION_LIST_PROGRAM = "COTRN00C";

    // Every width is the PICTURE clause's own, so a move that truncates does so in the direction and at the
    // position COBOL truncates.

    /**
     * {@code WS-MESSAGE PIC X(80) VALUE SPACES} - {@code :38}.
     */
    public static final int WS_MESSAGE_LENGTH = 80;

    public static final char MAX_AID_CODE_POINT = 0x00FF;

    /**
     * The character count of {@code WS-TRAN-AMT PIC +99999999.99} - {@code :49}.
     */
    public static final int WS_TRAN_AMT_LENGTH = 12;

    /**
     * The integer digit positions of {@code WS-TRAN-AMT} - eight, and this is the detail that makes the
     * amount a parity hazard rather than a formatting chore.
     */
    public static final int WS_TRAN_AMT_INTEGER_DIGITS = 8;

    /**
     * The fraction digit positions of {@code WS-TRAN-AMT} - two, matching
     * {@link CobolDecimal#MONETARY_SCALE} and every signed {@code PICTURE} in the estate.
     */
    public static final int WS_TRAN_AMT_SCALE = CobolDecimal.MONETARY_SCALE;

    public static final int WS_TRAN_AMT_DIGIT_COUNT =
            WS_TRAN_AMT_INTEGER_DIGITS + WS_TRAN_AMT_SCALE;

    /**
     * The character a fixed {@code +} insertion emits for a value that is zero or positive.
     *
     * <p>Fixed rather than floating: the {@code PICTURE} is {@code +99999999.99}, so the sign occupies
     * position one always and is never suppressed.
     */
    public static final char EDITED_SIGN_POSITIVE = '+';

    public static final char EDITED_SIGN_NEGATIVE = '-';

    /**
     * The actual decimal point of {@code PIC +99999999.99} - a character position, not a scale.
     */
    public static final char EDITED_DECIMAL_POINT = '.';

    /**
     * {@code WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} - {@code :50}.
     */
    public static final String WS_TRAN_DATE_INITIAL = "00/00/00";

    /**
     * The digit positions {@code WS-RESP-CD} and {@code WS-REAS-CD} display - {@code :43-44}.
     */
    public static final int WS_RESP_CD_DIGITS = 9;

    private static final String UNIT_OF_WORK_DESCRIPTION =
            "MAIN-PARA (app/cbl/COTRN01C.cbl:86-139)";

    // Each is moved into WS-MESSAGE PIC X(80) and from there into ERRMSGO PIC X(78), which truncates on the
    // right; none of these is long enough to lose a character, and the move rule is applied regardless.

    /**
     * {@code MOVE 'Tran ID can NOT be empty...' TO WS-MESSAGE} - {@code :149-150}.
     */
    public static final String MSG_TRAN_ID_EMPTY = "Tran ID can NOT be empty...";

    /**
     * {@code MOVE 'Transaction ID NOT found...' TO WS-MESSAGE} - {@code :285-286}.
     */
    public static final String MSG_TRAN_ID_NOT_FOUND = "Transaction ID NOT found...";

    /**
     * {@code MOVE 'Unable to lookup Transaction...' TO WS-MESSAGE} - {@code :292-293}.
     */
    public static final String MSG_UNABLE_TO_LOOKUP = "Unable to lookup Transaction...";

    public static final String DISPLAY_RESP_PREFIX = "RESP:";

    public static final String DISPLAY_REAS_PREFIX = "REAS:";

    /**
     * COBOL {@code SPACE}: {@code X'40'} on the mainframe, {@code 0x20} in this module's code page.
     */
    public static final char SPACE = ' ';

    /**
     * COBOL {@code LOW-VALUE}: {@code X'00'} in every code page.
     */
    public static final char LOW_VALUE = '\u0000';

    /**
     * The code page this program's {@code WORKING-STORAGE} images are rendered in.
     */
    public static final Charset DEFAULT_WORKING_STORAGE_CHARSET = StandardCharsets.US_ASCII;

    private final TransactionRepository transactionRepository;

    private final Clock clock;

    private final FixedWidthCodec codec;

    private final DatasetUnitOfWork unitOfWork;

    /**
     * Wires the program's three collaborators, rendering images in
     * {@link #DEFAULT_WORKING_STORAGE_CHARSET}.
     *
     * @param transactionRepository the {@code TRANSACT} dataset; must not be {@code null}
     * @param clock the clock {@code FUNCTION CURRENT-DATE} reads; must not be {@code null}
     * @param unitOfWork the CICS task boundary one execution runs inside; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    @Autowired
    public TransactionAddController(TransactionRepository transactionRepository,
                                    Clock clock,
                                    DatasetUnitOfWork unitOfWork) {
        this(transactionRepository, clock, unitOfWork, DEFAULT_WORKING_STORAGE_CHARSET);
    }

    /**
     * Wires the program's three collaborators with an explicit code page.
     *
     * @param transactionRepository the {@code TRANSACT} dataset; must not be {@code null}
     * @param clock the clock {@code FUNCTION CURRENT-DATE} reads; must not be {@code null}
     * @param unitOfWork the CICS task boundary one execution runs inside; must not be {@code null}
     * @param workingStorageCharset the code page for this program's images; must not be {@code null}, and
     *     never the platform default
     * @throws NullPointerException if any argument is {@code null}
     */
    public TransactionAddController(TransactionRepository transactionRepository,
                                    Clock clock,
                                    DatasetUnitOfWork unitOfWork,
                                    Charset workingStorageCharset) {
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "A TransactionRepository is required: app/cbl/COTRN01C.cbl:269-278 reads the "
                + TRANSACT_FILE_NAME + " file by TRAN-ID, and that keyed read is the only file "
                + "operation in the program - there is no write, rewrite or delete anywhere in it");
        this.clock = Objects.requireNonNull(clock, "A Clock is required: FUNCTION CURRENT-DATE is "
                + "read from it at app/cbl/COTRN01C.cbl:245 to build the screen's date and time "
                + "header, and never from the wall clock, so a parity case can pin the instant");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "A unit of work is required: "
                + "app/cbl/COTRN01C.cbl:275 states the UPDATE option, so the read takes a record lock, "
                + "and a lock outside a unit of work is released before the task that asked for it can "
                + "rely on it - which is why TransactionRepository refuses to issue one there");
        Objects.requireNonNull(workingStorageCharset, "A code page is required; it is never the "
                + "platform default");
        this.codec = new FixedWidthCodec(workingStorageCharset);
    }

    /**
     * {@code GET /api/transactions/}&#123;tranId&#125; - CSD transaction {@link #TRANSACTION_ID}, program
     * {@link #PROGRAM_NAME}.
     *
     * @param tranId the transaction id being viewed - the {@code RIDFLD} of the read at {@code :217-224}
     *     and the value {@code TRNIDIN} carries; must not be {@code null}
     * @param request the inbound screen, or {@code null} for a cold start; validated against the symbolic
     *     map's declared widths
     * @param eibaid the raw {@code EIBAID} byte as an unsigned {@code 0}-{@code 255} value
     * @param eibAid the same value under {@link AidRequestParameter#ALTERNATE_NAME}
     * @return the outbound screen and its presentation metadata, never {@code null}
     * @throws NullPointerException if {@code tranId} is {@code null}
     * @throws IllegalArgumentException if {@code tranId} is wider than {@code TRNIDIN}, if the AID is
     *     outside {@code 0}-{@code 255}, or if the two AID spellings disagree
     */
    @GetMapping(path = TRANSACTION_DETAIL_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScreenResponse<TransactionAddResponse> viewTransaction(
            @PathVariable(TRAN_ID_VARIABLE) String tranId,
            @Valid @RequestBody(required = false) TransactionAddRequest request,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid) {
        Objects.requireNonNull(tranId, "A transaction id is required in the path: it is the RIDFLD of "
                + "the READ at app/cbl/COTRN01C.cbl:217-224 and the value TRNIDIN carries");

        TransactionAddRequest bound = bind(tranId, request);
        ProgramState state = mainPara(bound,
                resolveEibAid(AidRequestParameter.resolve(eibaid, eibAid), bound));
        return ScreenResponse.of(state.response(), state.screenMetadata());
    }

    static byte resolveEibAid(Integer eibaid, TransactionAddRequest request) {
        if (eibaid != null) {
            int value = eibaid;
            if (value < AID_MIN || value > AID_MAX) {
                throw ScreenInputRejectedException.outsideRange(EIBAID_PARAM,
                        "one EIBAID byte", AID_MIN, AID_MAX);
            }
            return (byte) value;
        }
        return eibAidOf(request == null ? null : request.getAid());
    }

    TransactionAddRequest bind(String tranId, TransactionAddRequest request) {
        if (tranId.length() > TransactionAddRequest.TRNIDIN_LENGTH) {
            throw ScreenInputRejectedException.tooWide(TRNIDIN_MEMBER,
                    "TRNIDINI PIC X(" + TransactionAddRequest.TRNIDIN_LENGTH
                            + ") and TRAN-ID PIC X(" + TransactionAddRequest.TRNIDIN_LENGTH + ")",
                    TransactionAddRequest.TRNIDIN_LENGTH, tranId.length());
        }

        TransactionAddRequest received = request == null
                ? new TransactionAddRequest()
                : new TransactionAddRequest(request);

        // The binding rule, applied in exactly one place, on EVERY turn.
        //
        // :99-108 is the first-entry arm: it blanks the output map, places the cursor and - only when
        // CDEMO-CT01-TRN-SELECTED is neither SPACES nor LOW-VALUES - moves that extension field into
        // TRNIDINI and performs PROCESS-ENTER-KEY. :110-111 is the other arm: PERFORM
        // RECEIVE-TRNVIEW-SCREEN, then EVALUATE EIBAID, from where TRNIDINI is what the program reads
        // (:147, :217-224). Both carriers are therefore bound from the URI before either arm is chosen,
        // each at its own declared width; the path has already been required to fit, so both MOVEs pad.
        //
        // The two carriers are judged differently because they are different things. TRNIDINI is the
        // screen field the operator types into, so a value there naming a DIFFERENT transaction from the
        // URI is two keys in one request and is refused. CDEMO-CT01-TRN-SELECTED is NOT a screen field -
        // its only writer is the transaction-list program - so it is projected rather than judged.
        ScreenInputRejectedException.requireKeyAgreement(TRNIDIN_MEMBER, tranId, received.getTrnidin(),
                TransactionAddRequest.TRNIDIN_LENGTH, codec);
        String identity = codec.movePicX(tranId, TransactionAddRequest.TRNIDIN_LENGTH);
        received.setTrnidin(identity);
        received.getCt01Info().setTrnSelected(
                codec.movePicX(tranId, Ct01Info.TRN_SELECTED_LENGTH));
        return received;
    }

    /**
     * {@code MAIN-PARA} - the program's entry point, lines 86 to 139.
     *
     * @param request the inbound screen; must not be {@code null}
     * @return the state at the moment the task returned to CICS or transferred, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public ProgramState mainPara(TransactionAddRequest request) {
        Objects.requireNonNull(request, "A request is required: COTRN01C is driven entirely by its "
                + "communication area, the EIBAID and the received map, all of which travel in it");
        return mainPara(request, eibAidOf(request.getAid()));
    }

    /**
     * {@code MAIN-PARA} with the attention identifier supplied separately from the payload.
     *
     * @param request the inbound screen; must not be {@code null}
     * @param eibAid the raw {@code EIBAID} byte {@code :112} evaluates
     * @return the state at the moment the task returned to CICS or transferred, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public ProgramState mainPara(TransactionAddRequest request, byte eibAid) {
        Objects.requireNonNull(request, "A request is required: COTRN01C is driven entirely by its "
                + "communication area, the EIBAID and the received map, all of which travel in it");

        return unitOfWork.execute(UNIT_OF_WORK_DESCRIPTION,
                () -> mainParaUnderLock(request, eibAid));
    }

    private ProgramState mainParaUnderLock(TransactionAddRequest request, byte eibAid) {
        ProgramState state = new ProgramState(codec);

        state.setErrFlagOff();
        state.setUsrModifiedNo();
        state.setMessage(spaces(WS_MESSAGE_LENGTH));
        state.response().setErrmsgo(spaces(ScreenField.ERRMSGO.payloadLength()));

        if (!request.hasNavigationContext()) {
            state.setCommarea(state.commarea().withToProgram(SIGN_ON_PROGRAM));
            returnToPrevScreen(state);
            return state;
        }

        state.setCommarea(request.getNavigationContext());
        state.setCt01Info(new Ct01Info(request.getCt01Info()));

        if (!state.commarea().isReenter()) {
            state.setCommarea(state.commarea().withPgmReenter());
            moveLowValuesToOutputMap(state.response());
            state.moveMinusOneTo(ScreenField.TRNIDINO);

            if (!isSpacesOrLowValues(state.ct01Info().getTrnSelected())) {
                state.response().setTrnidino(state.ct01Info().getTrnSelected());
                processEnterKey(state);
            }

            sendTrnviewScreen(state);
            returnToCics(state);
            return state;
        }

        receiveTrnviewScreen(state, request);

        // The tests are raw-byte equalities, exactly as the source's EVALUATE compares them: PF15 is a
        // distinct AID from PF3 here and takes the invalid-key arm, which is what the COBOL does.
        state.setResolvedAid(PfKeyResolver.resolve(eibAid));

        if (PfKeyResolver.isEnter(eibAid)) {
            processEnterKey(state);
        } else if (PfKeyResolver.isPf3(eibAid)) {
            if (isSpacesOrLowValues(state.commarea().fromProgram())) {
                state.setCommarea(state.commarea().withToProgram(MAIN_MENU_PROGRAM));
            } else {
                state.setCommarea(state.commarea()
                        .withToProgram(state.commarea().fromProgram()));
            }
            returnToPrevScreen(state);
            return state;
        } else if (PfKeyResolver.isPf4(eibAid)) {
            clearCurrentScreen(state);
        } else if (PfKeyResolver.isPf5(eibAid)) {
            state.setCommarea(state.commarea().withToProgram(TRANSACTION_LIST_PROGRAM));
            returnToPrevScreen(state);
            return state;
        } else {
            state.setErrFlagOn();
            state.setMessage(codec.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                    WS_MESSAGE_LENGTH));
            sendTrnviewScreen(state);
        }

        returnToCics(state);
        return state;
    }

    /**
     * {@code PROCESS-ENTER-KEY} - lines 144 to 192: validate the entered transaction id, read the record,
     * paint the detail.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void processEnterKey(ProgramState state) {
        requireState(state);

        if (isSpacesOrLowValues(state.response().getTrnidino())) {
            state.setErrFlagOn();
            state.setMessage(codec.movePicX(MSG_TRAN_ID_EMPTY, WS_MESSAGE_LENGTH));
            state.moveMinusOneTo(ScreenField.TRNIDINO);
            sendTrnviewScreen(state);
        } else {
            state.moveMinusOneTo(ScreenField.TRNIDINO);
        }

        if (!state.errFlagOn()) {
            blankDetailFields(state.response());
            // TRAN-ID is PIC X(16) and TRNIDINI is PIC X(16), so this is a same-width move; the rule is
            // applied anyway, because a payload that arrived short must not shorten the key.
            state.setTranId(codec.movePicX(state.response().getTrnidino(),
                    TranRecord.TRAN_ID_LENGTH));
            readTransactFile(state);
        }

        if (!state.errFlagOn()) {
            TranRecord record = requireRecordRead(state.tranRecord());

            state.setTranAmtEdited(editedTranAmt(record.tranAmt()));

            state.response().setTrnido(record.tranId());
            state.response().setCardnumo(record.tranCardNum());
            state.response().setTtypcdo(record.tranTypeCd());
            state.response().setTcatcdo(record.tranCatCdImage());
            state.response().setTrnsrco(record.tranSource());
            state.response().setTrnamto(state.tranAmtEdited());
            state.response().setTdesco(record.tranDesc());
            // L185-L186 the two timestamps are PIC X(26) into PIC X(10): the leading date survives and the
            // time is truncated away, again on the right.
            state.response().setTorigdto(record.tranOrigTs());
            state.response().setTprocdto(record.tranProcTs());
            state.response().setMido(record.tranMerchantIdImage());
            state.response().setMnameo(record.tranMerchantName());
            state.response().setMcityo(record.tranMerchantCity());
            state.response().setMzipo(record.tranMerchantZip());

            sendTrnviewScreen(state);
        }
    }

    /**
     * The record the read at {@code :173} returned, for the block at {@code :176-192}.
     *
     * @param record the record the read reported; must not be {@code null}
     * @return the record
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalStateException if it is empty, naming what that would mean
     */
    public static TranRecord requireRecordRead(Optional<TranRecord> record) {
        Objects.requireNonNull(record, "An absent record is an empty Optional, never null");
        return record.orElseThrow(() -> new IllegalStateException(
                "app/cbl/COTRN01C.cbl:176 was reached with ERR-FLG-OFF and no TRAN-RECORD in hand. "
                + "That combination cannot arise from the program: :158 only skips the read when the "
                + "flag is already on, and every non-NORMAL arm of READ-TRANSACT-FILE (:280-296) "
                + "turns it on. Reaching it means the read outcome and the error flag have been set "
                + "independently of one another"));
    }

    /**
     * {@code READ-TRANSACT-FILE} - lines 267 to 296: the keyed read of {@link #TRANSACT_FILE_NAME} and the
     * three-armed evaluation of its response.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     * @throws IllegalStateException if no unit of work is open, because {@code :275}'s {@code UPDATE}
     *     option cannot take a lock that nothing would hold
     */
    public void readTransactFile(ProgramState state) {
        requireState(state);

        ReadResult result = transactionRepository.readForUpdateByTranId(state.tranId());
        state.setReadResult(result);
        // Where the repository reports no CICS condition - an artefact of the JDBC substitution, never of
        // CICS itself - the sentinel is stored rather than the item being left at the zero its VALUE clause
        // gave it.
        state.setRespCd(result.cicsResp().orElse(FileStatus.RESP_NOT_REPORTED));
        state.setReasCd(result.cicsResp2());

        if (result.isFound()) {
            return;
        }
        if (result.isNotFound()) {
            rejectAndSend(state, MSG_TRAN_ID_NOT_FOUND);
            return;
        }
        display(state, DISPLAY_RESP_PREFIX + respImage(state.respCd())
                + DISPLAY_REAS_PREFIX + respImage(state.reasCd()));
        rejectAndSend(state, MSG_UNABLE_TO_LOOKUP);
    }

    private String respImage(int code) {
        return FileStatus.respReported(code)
                ? codec.movePic9(code, WS_RESP_CD_DIGITS)
                : FileStatus.respNotReportedImage(WS_RESP_CD_DIGITS);
    }

    /**
     * The four statements the rejecting arms share - {@code :148-152}, {@code :284-288} and
     * {@code :291-295} - in the source's order: raise the flag, set the message, place the cursor in the
     * lookup field, send the screen.
     *
     * <p>Named once because the three sites are identical apart from the text, and because the order is
     * part of the behaviour: the message must be in {@code WS-MESSAGE} before
     * {@link #sendTrnviewScreen(ProgramState)} copies it into {@code ERRMSGO}.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @param message the literal the source moves into {@code WS-MESSAGE}; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public void rejectAndSend(ProgramState state, String message) {
        requireState(state);
        Objects.requireNonNull(message, "A rejection carries the message text the source moves");

        state.setErrFlagOn();
        state.setMessage(codec.movePicX(message, WS_MESSAGE_LENGTH));
        state.moveMinusOneTo(ScreenField.TRNIDINO);
        sendTrnviewScreen(state);
    }

    /**
     * {@code RETURN-TO-PREV-SCREEN} - lines 197 to 208: hand control to another program.
     *
     * <p>{@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)} at {@code :205-208}
     * transfers and never comes back, which is why every caller of this method returns immediately
     * afterwards and why the {@code EXEC CICS RETURN} at {@code :136} is unreachable from these arms.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void returnToPrevScreen(ProgramState state) {
        requireState(state);

        if (isSpacesOrLowValues(state.commarea().toProgram())) {
            state.setCommarea(state.commarea().withToProgram(SIGN_ON_PROGRAM));
        }
        state.setCommarea(state.commarea()
                .withFromTranid(TRANSACTION_ID)
                .withFromProgram(PROGRAM_NAME)
                .withPgmEnter());

        state.response().setNextProgram(state.commarea().toProgram());
        state.response().setNextMapset(spaces(TransactionAddResponse.NEXT_MAPSET_LENGTH));
        state.response().setNextMap(spaces(TransactionAddResponse.NEXT_MAP_LENGTH));
        echoPassedCommarea(state);
        state.markTransferred();
    }

    /**
     * {@code SEND-TRNVIEW-SCREEN} - lines 213 to 225: paint the screen.
     *
     * <p>None of this program's three literals, nor {@code CCDA-MSG-INVALID-KEY} at {@code PIC X(50)}, is
     * long enough to lose a character - and the rule is applied by the setter regardless, so a longer
     * message could never shift the field.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void sendTrnviewScreen(ProgramState state) {
        requireState(state);

        populateHeaderInfo(state);
        state.response().setErrmsgo(state.message());

        state.setLookupFieldHighlight(lookupFieldHighlight(state.commarea().isReenter()));
        state.response().applyHighlight(ScreenField.TRNIDINO, state.lookupFieldHighlight());

        state.response().setNextMapset(MAPSET_NAME);
        state.response().setNextMap(MAP_NAME);
        state.recordScreenSent();
    }

    /**
     * {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} - lines 136 to 139.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void returnToCics(ProgramState state) {
        requireState(state);

        state.response().setNextProgram(PROGRAM_NAME);
        echoPassedCommarea(state);
        state.markReturned();
    }

    /**
     * {@code COMMAREA(CARDDEMO-COMMAREA)} - the 218 bytes both the {@code XCTL} at {@code :207} and the
     * {@code RETURN} at {@code :138} pass: the 160-byte {@code CARDDEMO-COMMAREA} plus the 58-byte
     * {@code CDEMO-CT01-INFO} extension this program appends at {@code :53-61}.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void echoPassedCommarea(ProgramState state) {
        requireState(state);

        state.response().setNavigationContext(state.commarea());
        state.response().setCt01Info(new Ct01Info(state.ct01Info()));
    }

    /**
     * {@code RECEIVE-TRNVIEW-SCREEN} - lines 230 to 238.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @param request the inbound screen; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalStateException if the request and response projections of the symbolic map disagree
     *     about how many fields it has
     */
    public void receiveTrnviewScreen(ProgramState state, TransactionAddRequest request) {
        requireState(state);
        Objects.requireNonNull(request, "A request is required: it carries the received map");

        Map<String, String> received = request.toFieldImages(codec);
        List<String> names = TransactionAddRequest.PAYLOAD_FIELD_NAMES;
        ScreenField[] fields = ScreenField.values();
        requireMatchingProjections(names.size(), fields.length);
        for (int field = 0; field < fields.length; field++) {
            state.response().setPayload(fields[field], received.get(names.get(field)));
        }

        state.setRespCd(FileStatus.NORMAL);
        state.setReasCd(FileStatus.NO_REASON_CODE);
    }

    /**
     * Verifies that the two projections of {@code app/cpy-bms/COTRN01.CPY} agree about how many fields the
     * symbolic map has, before the received values are copied across by ordinal.
     *
     * @param requestFieldCount how many field names the request projects
     * @param responseFieldCount how many payload fields the response projects
     * @throws IllegalStateException if the two counts differ
     */
    public static void requireMatchingProjections(int requestFieldCount, int responseFieldCount) {
        if (requestFieldCount != responseFieldCount) {
            throw new IllegalStateException("The request projects " + requestFieldCount
                    + " field(s) of the symbolic map and the response projects " + responseFieldCount
                    + "; both are app/cpy-bms/COTRN01.CPY, which declares "
                    + TransactionAddRequest.PAYLOAD_FIELD_COUNT + " xxxI items and the same number of "
                    + "xxxO items, so one projection has drifted");
        }
    }

    /**
     * {@code POPULATE-HEADER-INFO} - lines 243 to 262: the six header fields every CardDemo screen carries.
     *
     * <p>{@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at {@code :245} is read from the injected
     * {@link Clock} and never from the wall clock, so a parity case can pin the instant and compare the two
     * header images byte for byte.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void populateHeaderInfo(ProgramState state) {
        requireState(state);

        DateHeader header = DateHeader.from(codec, clock);
        state.setDateHeader(header);

        state.response().setTitle01o(ScreenTitles.CCDA_TITLE01);
        state.response().setTitle02o(ScreenTitles.CCDA_TITLE02);
        state.response().setTrnnameo(TRANSACTION_ID);
        state.response().setPgmnameo(PROGRAM_NAME);
        state.response().setCurdateo(header.wsCurdateMmDdYy());
        state.response().setCurtimeo(header.wsCurtimeHhMmSs());
    }

    /**
     * {@code CLEAR-CURRENT-SCREEN} - lines 301 to 304, the PF4 arm: blank everything and repaint.
     *
     * <p>The error flag is deliberately left alone, so a screen cleared after a rejection keeps
     * {@code ERR-FLG-OFF} - which it already had, because the PF4 arm is only reached from a re-entry that
     * began with {@code SET ERR-FLG-OFF} at {@code :88}.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void clearCurrentScreen(ProgramState state) {
        requireState(state);

        initializeAllFields(state);
        sendTrnviewScreen(state);
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS} - lines 309 to 326.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void initializeAllFields(ProgramState state) {
        requireState(state);

        state.moveMinusOneTo(ScreenField.TRNIDINO);
        state.response().setTrnidino(spaces(ScreenField.TRNIDINO.payloadLength()));
        blankDetailFields(state.response());
        state.setMessage(spaces(WS_MESSAGE_LENGTH));
    }

    /**
     * The thirteen fields the source blanks in one statement at {@code :159-171} and again at
     * {@code :313-325}, in the source's listed order.
     */
    public static final List<ScreenField> DETAIL_FIELDS = List.of(
            ScreenField.TRNIDO, ScreenField.CARDNUMO, ScreenField.TTYPCDO, ScreenField.TCATCDO,
            ScreenField.TRNSRCO, ScreenField.TRNAMTO, ScreenField.TDESCO, ScreenField.TORIGDTO,
            ScreenField.TPROCDTO, ScreenField.MIDO, ScreenField.MNAMEO, ScreenField.MCITYO,
            ScreenField.MZIPO);

    /**
     * {@code MOVE SPACES TO} the {@linkplain #DETAIL_FIELDS thirteen detail fields} - {@code :159-171} and
     * {@code :313-325}.
     *
     * @param response the output map; must not be {@code null}
     * @throws NullPointerException if {@code response} is {@code null}
     */
    public static void blankDetailFields(TransactionAddResponse response) {
        Objects.requireNonNull(response, "An output map is required to blank its detail fields");
        for (ScreenField field : DETAIL_FIELDS) {
            response.setPayload(field, spaces(field.payloadLength()));
        }
    }

    /**
     * {@code MOVE LOW-VALUES TO COTRN1AO} - {@code :101}, the first-entry reset of the whole 575-byte
     * output map.
     *
     * @param response the output map; must not be {@code null}
     * @throws NullPointerException if {@code response} is {@code null}
     */
    public static void moveLowValuesToOutputMap(TransactionAddResponse response) {
        Objects.requireNonNull(response, "An output map is required to move LOW-VALUES into it");
        for (ScreenField field : ScreenField.values()) {
            response.setPayload(field, lowValues(field.payloadLength()));
            response.setAttributes(field, TransactionAddResponse.AttributeQuad.defaults());
        }
    }

    /**
     * The COBOL figurative constant {@code SPACES} sized to a field.
     *
     * @param width the field width in characters, zero or more
     * @return a string of exactly {@code width} spaces
     * @throws IllegalArgumentException if {@code width} is negative
     */
    public static String spaces(int width) {
        return String.valueOf(SPACE).repeat(width);
    }

    /**
     * The COBOL figurative constant {@code LOW-VALUES} sized to a field.
     *
     * @param width the field width in characters, zero or more
     * @return a string of exactly {@code width} {@link #LOW_VALUE} characters
     * @throws IllegalArgumentException if {@code width} is negative
     */
    public static String lowValues(int width) {
        // One implementation of the LOW-VALUES image, in common.ScreenFieldImage, so the choice cannot
        // drift back apart across screens. Any width validation above is this method's own contract.
        return ScreenFieldImage.unpainted(width);
    }

    /**
     * {@code IF <item> = SPACES OR LOW-VALUES} - the one predicate behind all four figurative-constant
     * comparisons in the program: {@code :116} on {@code CDEMO-FROM-PROGRAM}, {@code :147} on
     * {@code TRNIDINI}, {@code :199} on {@code CDEMO-TO-PROGRAM}, and - negated - {@code :103} on
     * {@code CDEMO-CT01-TRN-SELECTED}, whose {@code NOT = SPACES AND LOW-VALUES} is this condition's exact
     * complement.
     *
     * @param value the item to test, possibly {@code null}
     * @return {@code true} when the item is entirely spaces or entirely low-values
     */
    public static boolean isSpacesOrLowValues(String value) {
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
        }
        return allSpaces || allLowValues;
    }

    /**
     * The raw {@code EIBAID} byte the {@code EVALUATE} at {@code :112} compares against.
     *
     * <p>An absent or empty value becomes {@code DFHNULL} for the same reason, which is what this screen
     * has always answered a blank {@code aid} with.
     *
     * @param aid the attention identifier from the payload - one character whose code point is the raw
     *     {@code EIBAID} byte - possibly {@code null}
     * @return the raw {@code EIBAID} byte
     * @throws IllegalArgumentException if {@code aid} is longer than
     *     {@value TransactionAddRequest#AID_TOKEN_LENGTH} characters, or is one character whose code point is
     *     above {@code U+00FF}
     */
    public static byte eibAidOf(String aid) {
        if (aid == null || aid.isEmpty()) {
            return CicsAid.DFHNULL;
        }
        if (aid.length() > TransactionAddRequest.AID_TOKEN_LENGTH) {
            throw ScreenInputRejectedException.tooWide(AID_MEMBER,
                    "EIBAID, one byte, or the CCARD-AID token, five characters",
                    TransactionAddRequest.AID_TOKEN_LENGTH, aid.length());
        }
        if (aid.length() > TransactionAddRequest.AID_LENGTH) {
            // Two to five characters is the CCARD-AID token, and a token cannot say which key was pressed:
            // CSSTRPFY folds PF13-PF24 onto PF1-PF12. COTRN01C compares EIBAID itself, so there is no fold
            // to invert - the value names no key this program can identify, which is DFHNULL and therefore
            // WHEN OTHER.
            return CicsAid.DFHNULL;
        }
        char aidCharacter = aid.charAt(0);
        if (aidCharacter > MAX_AID_CODE_POINT) {
            throw ScreenInputRejectedException.outsideRange(AID_MEMBER, "one EIBAID byte",
                    0, MAX_AID_CODE_POINT);
        }
        return (byte) aidCharacter;
    }

    /**
     * {@code MOVE TRAN-AMT TO WS-TRAN-AMT} followed by {@code MOVE WS-TRAN-AMT TO TRNAMTI} - {@code :177}
     * and {@code :183}, rendered as the twelve characters {@code PIC +99999999.99} produces.
     *
     * @param tranAmt the record's {@code TRAN-AMT}; must not be {@code null}
     * @return exactly {@value #WS_TRAN_AMT_LENGTH} characters
     * @throws NullPointerException if {@code tranAmt} is {@code null}
     */
    public static String editedTranAmt(BigDecimal tranAmt) {
        Objects.requireNonNull(tranAmt, "An amount is required to edit it; TRAN-AMT is PIC S9(09)V99 "
                + "and a COBOL numeric item has no absent state");

        BigDecimal stored = CobolDecimal.storeAtPicture(tranAmt, WS_TRAN_AMT_INTEGER_DIGITS,
                WS_TRAN_AMT_SCALE);
        char sign = stored.signum() < 0 ? EDITED_SIGN_NEGATIVE : EDITED_SIGN_POSITIVE;
        BigInteger unscaledHundredths = stored.abs().unscaledValue();
        String digits = requireEditedDigits(unscaledHundredths.toString());

        return sign + digits.substring(0, WS_TRAN_AMT_INTEGER_DIGITS) + EDITED_DECIMAL_POINT
                + digits.substring(WS_TRAN_AMT_INTEGER_DIGITS);
    }

    /**
     * Left-zero-fills the amount's digits to the {@link #WS_TRAN_AMT_DIGIT_COUNT} positions
     * {@code PIC +99999999.99} declares, and refuses a digit string that cannot fit.
     *
     * @param digits the unscaled digits of the stored amount, without sign or decimal point; must not be
     *     {@code null}
     * @return exactly {@link #WS_TRAN_AMT_DIGIT_COUNT} digit characters
     * @throws NullPointerException if {@code digits} is {@code null}
     * @throws IllegalStateException if {@code digits} holds more than {@link #WS_TRAN_AMT_DIGIT_COUNT}
     *     characters
     */
    public static String requireEditedDigits(String digits) {
        Objects.requireNonNull(digits, "A digit string is required to fill it to the declared width");
        if (digits.length() > WS_TRAN_AMT_DIGIT_COUNT) {
            throw new IllegalStateException("WS-TRAN-AMT PIC +99999999.99 has "
                    + WS_TRAN_AMT_DIGIT_COUNT + " digit positions - "
                    + WS_TRAN_AMT_INTEGER_DIGITS + " integer and " + WS_TRAN_AMT_SCALE
                    + " fraction - but '" + digits + "' has " + digits.length()
                    + "; CobolDecimal.storeAtPicture should already have reduced the amount to fit");
        }
        return "0".repeat(WS_TRAN_AMT_DIGIT_COUNT - digits.length()) + digits;
    }

    /**
     * {@code FLG-<field>-NOT-OK} for the lookup field: always {@code false}.
     */
    public static final boolean LOOKUP_FIELD_NOT_OK = false;

    public static final boolean LOOKUP_FIELD_BLANK = false;

    /**
     * The {@code CSSETATY} decision for the lookup field - which, for this program, is always "touch
     * nothing".
     *
     * @param reenter {@code true} when {@code CDEMO-PGM-REENTER} holds
     * @return the decision, always {@linkplain FieldHighlight#untouched() untouched}; never {@code null}
     */
    public static FieldHighlight lookupFieldHighlight(boolean reenter) {
        return FieldAttributeSetter.resolveFromFlags(LOOKUP_FIELD_NOT_OK, LOOKUP_FIELD_BLANK, reenter,
                ScreenField.TRNIDINO.baseName(), MAP_NAME);
    }

    /**
     * Whether the lookup field carries the {@code CSSETATY} error colour.
     *
     * <p>Published so a test can state the negative directly: this screen never recolours a field, so
     * {@code TRNIDINC} must never hold {@link BmsAttributes#DFHRED}, on a first entry, on a re-entry, after
     * an empty-key rejection and after a not-found rejection alike.
     *
     * @param response the output map; must not be {@code null}
     * @return {@code true} when {@code TRNIDINC} holds {@link BmsAttributes#DFHRED}
     * @throws NullPointerException if {@code response} is {@code null}
     */
    public static boolean isErrorColoured(TransactionAddResponse response) {
        Objects.requireNonNull(response, "An output map is required to read its colour item");
        return response.attributes(ScreenField.TRNIDINO).colour() == BmsAttributes.DFHRED;
    }

    private void display(ProgramState state, String text) {
        state.recordDisplay(text);
        LOG.info(text);
    }

    private static void requireState(ProgramState state) {
        Objects.requireNonNull(state, "A ProgramState is required: every WORKING-STORAGE item of "
                + "COTRN01C lives in it, so that two concurrent requests cannot see each other's "
                + "screen");
    }

    /**
     * One execution's working storage: everything {@code COTRN01C} declares between {@code :35} and
     * {@code :61}, plus the observable effects that have no home in the 21-field payload.
     */
    public static final class ProgramState {
        /**
         * {@code 88 ERR-FLG-ON VALUE 'Y'} - {@code :41}.
         */
        public static final String ERR_FLG_ON = "Y";

        /**
         * {@code 88 ERR-FLG-OFF VALUE 'N'} - {@code :42}, and the item's own {@code VALUE}.
         */
        public static final String ERR_FLG_OFF = "N";

        /**
         * {@code 88 USR-MODIFIED-YES VALUE 'Y'} - {@code :46}.
         */
        public static final String USR_MODIFIED_YES = "Y";

        /**
         * {@code 88 USR-MODIFIED-NO VALUE 'N'} - {@code :47}, and the item's own {@code VALUE}.
         */
        public static final String USR_MODIFIED_NO = "N";

        private final TransactionAddResponse response = new TransactionAddResponse();

        private final FixedWidthCodec codec;

        private String message;

        private String errFlg = ERR_FLG_OFF;

        private String usrModified = USR_MODIFIED_NO;

        private String tranAmtEdited;

        private final String tranDate = WS_TRAN_DATE_INITIAL;

        private int respCd = FileStatus.NORMAL;

        private int reasCd = FileStatus.NO_REASON_CODE;

        private NavigationContext commarea = NavigationContext.empty();

        private Ct01Info ct01Info = new Ct01Info();

        private String tranId;

        private ReadResult readResult;

        private DateHeader dateHeader;

        private ScreenField cursorField;

        private Optional<AidKey> resolvedAid = Optional.empty();

        private FieldHighlight lookupFieldHighlight;

        private final List<String> displays = new ArrayList<>();

        private int screensSent;

        private boolean returned;

        private boolean transferred;

        /**
         * Creates the working storage in the state the {@code VALUE} clauses describe.
         *
         * @param codec the move rules and code page for this execution's images; must not be {@code null}
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public ProgramState(FixedWidthCodec codec) {
            this.codec = Objects.requireNonNull(codec, "A FixedWidthCodec is required: every item "
                    + "held here is kept at its declared PICTURE width, and the code page of a "
                    + "fixed-width image is always stated explicitly");
            this.message = spaces(WS_MESSAGE_LENGTH);
            this.tranAmtEdited = spaces(WS_TRAN_AMT_LENGTH);
            this.tranId = spaces(TranRecord.TRAN_ID_LENGTH);
            this.lookupFieldHighlight =
                    FieldHighlight.none(ScreenField.TRNIDINO.baseName(), MAP_NAME);
        }

        public TransactionAddResponse response() {
            return response;
        }

        /**
         * {@code WS-MESSAGE}.
         *
         * @return exactly {@value TransactionAddController#WS_MESSAGE_LENGTH} characters
         */
        public String message() {
            return message;
        }

        /**
         * {@code MOVE <text> TO WS-MESSAGE}, at the item's declared width.
         *
         * @param value the text to store; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setMessage(String value) {
            Objects.requireNonNull(value, "A message is required; move spaces explicitly to clear it");
            this.message = codec.movePicX(value, WS_MESSAGE_LENGTH);
        }

        /**
         * {@code WS-ERR-FLG}, as the byte it holds.
         *
         * @return {@link #ERR_FLG_ON} or {@link #ERR_FLG_OFF}
         */
        public String errFlg() {
            return errFlg;
        }

        /**
         * {@code 88 ERR-FLG-ON}.
         *
         * @return {@code true} when the item holds {@link #ERR_FLG_ON}
         */
        public boolean errFlagOn() {
            return ERR_FLG_ON.equals(errFlg);
        }

        /**
         * {@code 88 ERR-FLG-OFF}.
         *
         * <p>Deliberately not the negation of {@link #errFlagOn()}: the item is {@code PIC X(01)} and could
         * in principle hold some third byte, for which both condition names are correctly false.
         *
         * @return {@code true} when the item holds {@link #ERR_FLG_OFF}
         */
        public boolean errFlagOff() {
            return ERR_FLG_OFF.equals(errFlg);
        }

        /**
         * {@code MOVE 'Y' TO WS-ERR-FLG} - {@code :129}, {@code :148}, {@code :284}, {@code :291}.
         */
        public void setErrFlagOn() {
            this.errFlg = ERR_FLG_ON;
        }

        /**
         * {@code SET ERR-FLG-OFF TO TRUE} - {@code :88}.
         */
        public void setErrFlagOff() {
            this.errFlg = ERR_FLG_OFF;
        }

        /**
         * {@code WS-USR-MODIFIED}, as the byte it holds.
         *
         * @return {@link #USR_MODIFIED_YES} or {@link #USR_MODIFIED_NO}
         */
        public String usrModified() {
            return usrModified;
        }

        /**
         * {@code 88 USR-MODIFIED-YES} - never true in this program; see {@link #USR_MODIFIED_YES}.
         *
         * @return {@code true} when the item holds {@link #USR_MODIFIED_YES}
         */
        public boolean usrModifiedYes() {
            return USR_MODIFIED_YES.equals(usrModified);
        }

        /**
         * {@code 88 USR-MODIFIED-NO}.
         *
         * @return {@code true} when the item holds {@link #USR_MODIFIED_NO}
         */
        public boolean usrModifiedNo() {
            return USR_MODIFIED_NO.equals(usrModified);
        }

        /**
         * {@code SET USR-MODIFIED-NO TO TRUE} - {@code :89}, the only site that writes the item.
         */
        public void setUsrModifiedNo() {
            this.usrModified = USR_MODIFIED_NO;
        }

        /**
         * {@code WS-TRAN-AMT} - the twelve-character edited amount.
         *
         * @return exactly {@value TransactionAddController#WS_TRAN_AMT_LENGTH} characters
         */
        public String tranAmtEdited() {
            return tranAmtEdited;
        }

        /**
         * {@code MOVE TRAN-AMT TO WS-TRAN-AMT} - {@code :177}, already edited by
         * {@link TransactionAddController#editedTranAmt(BigDecimal)}.
         *
         * @param value the edited image; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setTranAmtEdited(String value) {
            Objects.requireNonNull(value, "An edited amount is required");
            this.tranAmtEdited = codec.movePicX(value, WS_TRAN_AMT_LENGTH);
        }

        /**
         * {@code WS-TRAN-DATE} - the declared-and-unused item, at its {@code VALUE}.
         *
         * @return {@value TransactionAddController#WS_TRAN_DATE_INITIAL}, always
         */
        public String tranDate() {
            return tranDate;
        }

        /**
         * {@code WS-RESP-CD}.
         *
         * @return the CICS {@code RESP} the last command reported, or zero when none did
         */
        public int respCd() {
            return respCd;
        }

        /**
         * {@code MOVE <resp> TO WS-RESP-CD}, as the {@code RESP} option of a command performs it.
         *
         * @param value the response code
         */
        public void setRespCd(int value) {
            this.respCd = value;
        }

        /**
         * {@code WS-REAS-CD}.
         *
         * @return the CICS {@code RESP2} the last command reported
         */
        public int reasCd() {
            return reasCd;
        }

        /**
         * {@code MOVE <resp2> TO WS-REAS-CD}, as the {@code RESP2} option of a command performs it.
         *
         * @param value the reason code
         */
        public void setReasCd(int value) {
            this.reasCd = value;
        }

        /**
         * {@code CARDDEMO-COMMAREA}.
         *
         * @return the 160-byte communication area, never {@code null}
         */
        public NavigationContext commarea() {
            return commarea;
        }

        /**
         * Replaces the communication area, as each {@code MOVE ... TO CDEMO-...} does.
         *
         * @param value the area to hold; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setCommarea(NavigationContext value) {
            this.commarea = Objects.requireNonNull(value, "A communication area is required; the "
                    + "EIBCALEN = 0 case is distinguished by TransactionAddRequest, not by a null "
                    + "here");
        }

        /**
         * {@code CDEMO-CT01-INFO}.
         *
         * @return the 58-byte extension, never {@code null}
         */
        public Ct01Info ct01Info() {
            return ct01Info;
        }

        public void setCt01Info(Ct01Info value) {
            this.ct01Info = Objects.requireNonNull(value, "A CDEMO-CT01-INFO extension is required; "
                    + "the request always carries one, initialised if the client sent none");
        }

        /**
         * {@code TRAN-ID} of {@code TRAN-RECORD} - the key the read at {@code :273} rides on.
         *
         * @return exactly {@value TranRecord#TRAN_ID_LENGTH} characters
         */
        public String tranId() {
            return tranId;
        }

        /**
         * {@code MOVE TRNIDINI OF COTRN1AI TO TRAN-ID} - {@code :172}.
         *
         * @param value the key to store; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setTranId(String value) {
            Objects.requireNonNull(value, "A read key is required");
            this.tranId = codec.movePicX(value, TranRecord.TRAN_ID_LENGTH);
        }

        public Optional<ReadResult> readResult() {
            return Optional.ofNullable(readResult);
        }

        /**
         * Records the read outcome, as the {@code INTO} and {@code RESP} options do together.
         *
         * @param value the outcome; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setReadResult(ReadResult value) {
            this.readResult = Objects.requireNonNull(value, "A read outcome is required; an absent "
                    + "record is reported by the outcome, never by a null outcome");
        }

        /**
         * {@code TRAN-RECORD} - the 350 bytes the read moved in.
         *
         * @return the record, or empty when no read has returned one
         */
        public Optional<TranRecord> tranRecord() {
            if (readResult == null) {
                return Optional.empty();
            }
            return readResult.record();
        }

        /**
         * {@code WS-DATE-TIME} as {@code POPULATE-HEADER-INFO} filled it.
         *
         * @return the header, or empty when no screen has been sent
         */
        public Optional<DateHeader> dateHeader() {
            return Optional.ofNullable(dateHeader);
        }

        /**
         * Records {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} - {@code :245}.
         *
         * @param value the captured date and time; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setDateHeader(DateHeader value) {
            this.dateHeader = Objects.requireNonNull(value, "A captured date and time is required");
        }

        public ScreenField cursorField() {
            return cursorField;
        }

        public boolean cursorRequested() {
            return cursorField != null;
        }

        /**
         * This screen's presentation metadata, in the shared envelope every online response publishes.
         *
         * @return the metadata; never {@code null}
         */
        public ScreenMetadata screenMetadata() {
            Map<ScreenField, TransactionAddResponse.AttributeQuad> quads = response.attributeItems();
            Map<String, ScreenMetadata.FieldMetadata> fields = new LinkedHashMap<>();
            for (Map.Entry<ScreenField, TransactionAddResponse.AttributeQuad> quad : quads.entrySet()) {
                fields.put(quad.getKey().baseName(), ScreenMetadata.FieldMetadata.of(
                        quad.getValue().colour(),
                        quad.getValue().programmedSymbols(),
                        quad.getValue().highlight(),
                        quad.getValue().validation()));
            }
            return ScreenMetadata.of(cursorField == null ? null : cursorField.baseName(),
                    response.attributes(ScreenField.ERRMSGO).colour(),
                    false,
                    fields);
        }

        public void moveMinusOneTo(ScreenField field) {
            this.cursorField = Objects.requireNonNull(field, "A field is required to place the "
                    + "cursor in it");
        }

        public Optional<AidKey> resolvedAid() {
            return resolvedAid;
        }

        public void setResolvedAid(Optional<AidKey> value) {
            this.resolvedAid = Objects.requireNonNull(value, "An unmatched AID is an empty Optional, "
                    + "never null");
        }

        public FieldHighlight lookupFieldHighlight() {
            return lookupFieldHighlight;
        }

        public void setLookupFieldHighlight(FieldHighlight value) {
            this.lookupFieldHighlight = Objects.requireNonNull(value, "FieldAttributeSetter returns "
                    + "FieldHighlight.none(..) rather than null when nothing is to be done");
        }

        public List<String> displays() {
            return Collections.unmodifiableList(displays);
        }

        public void recordDisplay(String text) {
            displays.add(Objects.requireNonNull(text, "A DISPLAY carries text"));
        }

        public int screensSent() {
            return screensSent;
        }

        public boolean screenSent() {
            return screensSent > 0;
        }

        /**
         * Records one {@code EXEC CICS SEND MAP ... ERASE CURSOR} - {@code :219-225}.
         */
        public void recordScreenSent() {
            this.screensSent++;
        }

        /**
         * Whether the task returned to CICS.
         *
         * @return {@code true} when {@code EXEC CICS RETURN TRANSID('CT01')} at {@code :136} was reached
         */
        public boolean returned() {
            return returned;
        }

        /**
         * Records {@code EXEC CICS RETURN} - {@code :136-139}.
         */
        public void markReturned() {
            this.returned = true;
        }

        /**
         * Whether the task transferred to another program.
         *
         * @return {@code true} when {@code EXEC CICS XCTL} at {@code :205} was reached, in which case
         *     {@link #returned()} is false: the transfer never comes back
         */
        public boolean transferred() {
            return transferred;
        }

        /**
         * Records {@code EXEC CICS XCTL} - {@code :205-208}.
         */
        public void markTransferred() {
            this.transferred = true;
        }
    }
}
