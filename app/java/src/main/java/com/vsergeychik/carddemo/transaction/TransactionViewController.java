package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AidRequestParameter;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.NumericIntrinsics;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewRequest;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse.ScreenField;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import com.vsergeychik.carddemo.util.DateUtilityJob.DateValidationResult;

import jakarta.validation.Valid;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The stateless add-a-transaction screen: a like-for-like migration of {@code app/cbl/COTRN02C.cbl} (783
 * lines), CSD transaction {@link #TRANSACTION_ID}, exposed as {@code POST}{@link #TRANSACTIONS_PATH}.
 *
 * <p>The {@code PIC +99999999.99} edit mask is rendered by {@link #wsTranAmtEdited(BigDecimal)}, which
 * composes digits itself and therefore cannot vary with a default locale.
 *
 * <p>The class name and the behaviour disagree, and the source is the authority. {@code COTRN02C}'s own
 * header reads <em>"Function    : Add a new Transaction to TRANSACT file"</em>, so
 * this controller validates and inserts a transaction record. The mandated class name is
 * kept verbatim rather than corrected towards the behaviour, and its sibling
 * {@link TransactionAddController} carries the mirror-image swap - that one, translated from
 * {@code COTRN01C}, is the screen that views a transaction.
 */
@RestController
public class TransactionViewController {
    private static final Log LOG = LogFactory.getLog(TransactionViewController.class);

    /**
     * {@code 05 WS-PGMNAME PIC X(08) VALUE 'COTRN02C'} - L36, and {@code PROGRAM(COTRN02C)} in the CSD.
     */
    public static final String PROGRAM_NAME = "COTRN02C";

    /**
     * {@code 05 WS-TRANID PIC X(04) VALUE 'CT02'} - L37, and {@code TRANSACTION(CT02)} in the CSD.
     */
    public static final String TRANSACTION_ID = "CT02";

    public static final String TRANSACTIONS_PATH = "/api/transactions";

    static final String AID_MEMBER = "aid";

    /**
     * {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} - L116 and L503, the no-communication-area target.
     */
    public static final String SIGN_ON_PROGRAM = "COSGN00C";

    /**
     * {@code MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM} - L138, the PF3 target when no caller is recorded.
     */
    public static final String MAIN_MENU_PROGRAM = "COMEN01C";

    /**
     * {@code 05 WS-MESSAGE PIC X(80) VALUE SPACES} - L38.
     */
    public static final int WS_MESSAGE_LENGTH = 80;

    /**
     * {@code 05 WS-ACCT-ID-N PIC 9(11) VALUE 0} - L55, and {@code XREF-ACCT-ID PIC 9(11)}.
     */
    public static final int WS_ACCT_ID_N_DIGITS = 11;

    /**
     * {@code 05 WS-CARD-NUM-N PIC 9(16) VALUE 0} - L56, and {@code XREF-CARD-NUM PIC X(16)}.
     */
    public static final int WS_CARD_NUM_N_DIGITS = 16;

    /**
     * {@code 05 WS-TRAN-ID-N PIC 9(16) VALUE ZEROS} - L57, and {@code TRAN-ID PIC X(16)}.
     */
    public static final int WS_TRAN_ID_N_DIGITS = 16;

    /**
     * {@code 05 WS-TRAN-AMT-N PIC S9(9)V99 VALUE ZERO} - L58: nine integer digits.
     */
    public static final int WS_TRAN_AMT_N_INTEGER_DIGITS = 9;

    public static final int MONETARY_SCALE = CobolDecimal.MONETARY_SCALE;

    /**
     * {@code 05 WS-TRAN-AMT-E PIC +99999999.99 VALUE ZEROS} - L59: eight integer digits.
     */
    public static final int WS_TRAN_AMT_E_INTEGER_DIGITS = 8;

    /**
     * The rendered width of {@code PIC +99999999.99}: one forced sign, eight integer digits, the literal
     * decimal point and two fraction digits.
     */
    public static final int WS_TRAN_AMT_E_LENGTH =
            1 + WS_TRAN_AMT_E_INTEGER_DIGITS + 1 + MONETARY_SCALE;

    /**
     * {@code 05 WS-RESP-CD PIC S9(09) COMP} - L47, rendered nine digits wide by {@code DISPLAY}.
     */
    public static final int WS_RESP_CD_DIGITS = 9;

    /**
     * {@code 05 WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} - L60, the mask both date checks pass.
     */
    public static final String WS_DATE_FORMAT = "YYYY-MM-DD";

    /**
     * The width of the two title literals {@code POPULATE-HEADER-INFO} moves at L556-557.
     */
    public static final int SCREEN_TITLE_LENGTH = ScreenTitles.TITLE_LENGTH;

    // Every one of them names a CICS file, and exactly one of them is never used.

    /**
     * {@code 05 WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} - L39.
     */
    public static final String WS_TRANSACT_FILE = TransactionRepository.CICS_FILE_NAME;

    /**
     * {@code 05 WS-CCXREF-FILE PIC X(08) VALUE 'CCXREF '} - L41: the base cluster, sixteen-byte key.
     */
    public static final String WS_CCXREF_FILE = CardXrefRepository.BASE_DD_NAME;

    /**
     * {@code 05 WS-CXACAIX-FILE PIC X(08) VALUE 'CXACAIX '} - L42: the alternate-index path over the same
     * cross-reference dataset, eleven-byte key.
     */
    public static final String WS_CXACAIX_FILE = CardXrefRepository.ALTERNATE_INDEX_DD_NAME;

    /**
     * {@code 05 WS-ACCTDAT-FILE PIC X(08) VALUE 'ACCTDAT '} - L40.
     */
    public static final String WS_ACCTDAT_FILE = AccountRepository.CICS_FILE_NAME;

    public static final int COPIED_ACCOUNT_RECORD_LENGTH = AccountRecord.RECORD_LENGTH;

    /**
     * {@code 05 WS-TRAN-AMT PIC +99999999.99} - L53.
     */
    public static final String WS_TRAN_AMT_PICTURE = "+99999999.99";

    /**
     * {@code 05 WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} - L54.
     */
    public static final String WS_TRAN_DATE_VALUE = "00/00/00";

    /**
     * {@code IF CSUTLDTC-RESULT-SEV-CD = '0000'} - L397 and L417: the unconditional accept.
     */
    public static final String CSUTLDTC_SEVERITY_OK = "0000";

    /**
     * {@code IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'} - L400 and L420: the one error both call sites
     * deliberately accept.
     */
    public static final String CSUTLDTC_TOLERATED_MESSAGE_NUMBER = "2513";

    public static final String MSG_CONFIRM_TO_ADD = "Confirm to add this transaction...";

    public static final String MSG_INVALID_CONFIRM_VALUE = "Invalid value. Valid values are (Y/N)...";

    public static final String MSG_ACCOUNT_ID_NOT_NUMERIC = "Account ID must be Numeric...";

    public static final String MSG_CARD_NUMBER_NOT_NUMERIC = "Card Number must be Numeric...";

    /**
     * L226-227, the {@code VALIDATE-INPUT-KEY-FIELDS} {@code WHEN OTHER} arm.
     */
    public static final String MSG_KEY_REQUIRED = "Account or Card Number must be entered...";

    public static final String MSG_TYPE_CD_EMPTY = "Type CD can NOT be empty...";

    public static final String MSG_CATEGORY_CD_EMPTY = "Category CD can NOT be empty...";

    public static final String MSG_SOURCE_EMPTY = "Source can NOT be empty...";

    public static final String MSG_DESCRIPTION_EMPTY = "Description can NOT be empty...";

    public static final String MSG_AMOUNT_EMPTY = "Amount can NOT be empty...";

    public static final String MSG_ORIG_DATE_EMPTY = "Orig Date can NOT be empty...";

    public static final String MSG_PROC_DATE_EMPTY = "Proc Date can NOT be empty...";

    public static final String MSG_MERCHANT_ID_EMPTY = "Merchant ID can NOT be empty...";

    public static final String MSG_MERCHANT_NAME_EMPTY = "Merchant Name can NOT be empty...";

    public static final String MSG_MERCHANT_CITY_EMPTY = "Merchant City can NOT be empty...";

    public static final String MSG_MERCHANT_ZIP_EMPTY = "Merchant Zip can NOT be empty...";

    public static final String MSG_TYPE_CD_NOT_NUMERIC = "Type CD must be Numeric...";

    public static final String MSG_CATEGORY_CD_NOT_NUMERIC = "Category CD must be Numeric...";

    public static final String MSG_AMOUNT_FORMAT = "Amount should be in format -99999999.99";

    public static final String MSG_ORIG_DATE_FORMAT = "Orig Date should be in format YYYY-MM-DD";

    public static final String MSG_PROC_DATE_FORMAT = "Proc Date should be in format YYYY-MM-DD";

    public static final String MSG_ORIG_DATE_INVALID = "Orig Date - Not a valid date...";

    public static final String MSG_PROC_DATE_INVALID = "Proc Date - Not a valid date...";

    public static final String MSG_MERCHANT_ID_NOT_NUMERIC = "Merchant ID must be Numeric...";

    public static final String MSG_ACCOUNT_ID_NOT_FOUND = "Account ID NOT found...";

    public static final String MSG_XREF_AIX_LOOKUP_FAILED =
            "Unable to lookup Acct in XREF AIX file...";

    public static final String MSG_CARD_NUMBER_NOT_FOUND = "Card Number NOT found...";

    public static final String MSG_XREF_LOOKUP_FAILED = "Unable to lookup Card # in XREF file...";

    public static final String MSG_TRANSACTION_ID_NOT_FOUND = "Transaction ID NOT found...";

    public static final String MSG_TRANSACTION_LOOKUP_FAILED = "Unable to lookup Transaction...";

    public static final String MSG_TRAN_ID_ALREADY_EXISTS = "Tran ID already exist...";

    public static final String MSG_UNABLE_TO_ADD = "Unable to Add Transaction...";

    public static final String MSG_ADDED_SUCCESSFULLY = "Transaction added successfully. ";

    public static final String MSG_YOUR_TRAN_ID_IS = " Your Tran ID is ";

    public static final String MSG_FULL_STOP = ".";

    public static final String DISPLAY_RESP_PREFIX = "RESP:";

    public static final String DISPLAY_REAS_PREFIX = "REAS:";

    public static final String CONFIRM_YES_UPPER = "Y";

    public static final String CONFIRM_YES_LOWER = "y";

    public static final String CONFIRM_NO_UPPER = "N";

    public static final String CONFIRM_NO_LOWER = "n";

    public static final int AMOUNT_SIGN_OFFSET = 0;

    public static final int AMOUNT_INTEGER_OFFSET = 1;

    public static final int AMOUNT_INTEGER_LENGTH = 8;

    public static final int AMOUNT_POINT_OFFSET = 9;

    public static final int AMOUNT_FRACTION_OFFSET = 10;

    public static final int AMOUNT_FRACTION_LENGTH = 2;

    public static final char DECIMAL_POINT = '.';

    public static final char MINUS_SIGN = '-';

    public static final char PLUS_SIGN = '+';

    public static final int DATE_YEAR_OFFSET = 0;

    public static final int DATE_YEAR_LENGTH = 4;

    public static final int DATE_FIRST_SEPARATOR_OFFSET = 4;

    public static final int DATE_MONTH_OFFSET = 5;

    public static final int DATE_SECOND_SEPARATOR_OFFSET = 7;

    public static final int DATE_DAY_OFFSET = 8;

    public static final int DATE_PART_LENGTH = 2;

    public static final char DATE_SEPARATOR = '-';

    /**
     * The verdict {@link #testNumval(String)} and {@link #testNumvalC(String)} return for an argument that
     * conforms, matching how {@code FUNCTION TEST-NUMVAL} reports one.
     */
    public static final int NUMVAL_CONFORMS = 0;

    /**
     * {@code MOVE ZEROS TO TRAN-ID} - L689: the {@code ENDFILE} arm, so the first generated id is 1.
     */
    public static final String TRAN_ID_ZEROS = "0".repeat(WS_TRAN_ID_N_DIGITS);

    private static final String SPACE = " ";

    private static final long ONE = 1L;

    private final TransactionRepository transactionRepository;

    private final CardXrefRepository cardXrefRepository;

    private final DateUtilityJob dateUtilityJob;

    private final Clock clock;

    private final FixedWidthCodec codec;

    @Autowired
    public TransactionViewController(TransactionRepository transactionRepository,
                                     CardXrefRepository cardXrefRepository,
                                     DateUtilityJob dateUtilityJob,
                                     Clock clock) {
        this(transactionRepository, cardXrefRepository, dateUtilityJob, clock,
                repositoryCharset(transactionRepository));
    }

    /**
     * The explicit-charset constructor, for a caller that already holds the code page.
     *
     * @param transactionRepository the {@code TRANSACT} master; must not be {@code null}
     * @param cardXrefRepository the cross-reference dataset; must not be {@code null}
     * @param dateUtilityJob the {@code CSUTLDTC} subprogram; must not be {@code null}
     * @param clock the clock; must not be {@code null}
     * @param datasetCharset the code page every fixed-width field is encoded in; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public TransactionViewController(TransactionRepository transactionRepository,
                                     CardXrefRepository cardXrefRepository,
                                     DateUtilityJob dateUtilityJob,
                                     Clock clock,
                                     Charset datasetCharset) {
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "A TransactionRepository is required: COTRN02C browses TRANSACT backwards for the "
                        + "highest TRAN-ID at L444-447 and writes the new record at L713, and this "
                        + "controller reaches that dataset no other way");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository,
                "A CardXrefRepository is required: COTRN02C reads the CXACAIX path at L578 and the "
                        + "CCXREF base at L611, which are two access paths over one dataset");
        this.dateUtilityJob = Objects.requireNonNull(dateUtilityJob,
                "A DateUtilityJob is required: COTRN02C calls CSUTLDTC at L393 and L413, and the "
                        + "'2513' tolerance those two sites apply cannot be reproduced without it");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: POPULATE-HEADER-INFO reads FUNCTION CURRENT-DATE at L554, and "
                        + "reading a clock inline would make every parity case non-deterministic");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset,
                "A dataset charset is required: a fixed-width mainframe field is bytes in a specific "
                        + "code page, so the page is always stated and never taken from the platform"));
    }

    private static Charset repositoryCharset(TransactionRepository repository) {
        Objects.requireNonNull(repository, "A TransactionRepository is required before its code page "
                + "can be read");
        return Objects.requireNonNull(repository.datasetCharset(),
                "The TransactionRepository reported no dataset code page; a 350-byte TRAN-RECORD "
                        + "cannot be encoded without one");
    }

    FixedWidthCodec codec() {
        return codec;
    }

    /**
     * Query parameter carrying the raw {@code EIBAID} byte as an unsigned {@code 0}-{@code 255} value.
     */
    public static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    public static final String EIBAID_PARAM_ALIAS = AidRequestParameter.ALTERNATE_NAME;

    static final int AID_MIN = 0;

    static final int AID_MAX = 255;

    static final int RAW_AID_LENGTH = 1;

    static final char MAX_AID_CODE_POINT = 0x00FF;

    /**
     * {@code POST}{@link #TRANSACTIONS_PATH} - validate the operator's entry and insert a transaction.
     *
     * <p>The program performs its own extensive editing at L193-437, and any additional Java rejection
     * would refuse an input the COBOL accepts, which is a parity break.
     *
     * @param request the inbound screen, the communication area and the {@code EIBAID}; must not be
     *     {@code null}
     * @param eibaid the raw {@code EIBAID} byte as an unsigned {@code 0}-{@code 255} value, or {@code null}
     *     when the request names no key
     * @param eibAid the accepted alternate spelling of the same parameter
     * @return the painted screen, the next program, the communication area to carry forward and the
     *     presentation metadata - the cursor request, the twenty-one attribute quads and the message colour
     * @throws NullPointerException if {@code request} is {@code null}
     */
    @PostMapping(path = TRANSACTIONS_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ScreenResponse<TransactionViewResponse> addTransaction(
            @Valid @RequestBody TransactionViewRequest request,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid) {
        ProgramState state = mainPara(request, AidRequestParameter.resolve(eibaid, eibAid));
        return ScreenResponse.of(state.response(), state.screenMetadata());
    }

    /**
     * {@code MAIN-PARA} - the program's entry point, lines 107 to 159.
     *
     * @param request the inbound screen; must not be {@code null}
     * @return the state at the moment the task returned to CICS or transferred; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public ProgramState mainPara(TransactionViewRequest request) {
        return mainPara(request, null);
    }

    /**
     * {@code MAIN-PARA} with the raw attention identifier the request stated.
     *
     * @param request the inbound screen; must not be {@code null}
     * @param statedAid the raw {@code EIBAID} byte as an unsigned value, or {@code null} when the request
     *     named no key
     * @return the state at the moment the task returned to CICS or transferred; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public ProgramState mainPara(TransactionViewRequest request, Integer statedAid) {
        Objects.requireNonNull(request, "A request is required: COTRN02C is driven entirely by its "
                + "communication area, the EIBAID and the received map, all of which travel in it");
        return mainPara(request, resolveEibAid(statedAid, request.getAid()));
    }

    /**
     * {@code MAIN-PARA} with the attention identifier supplied separately from the payload.
     *
     * @param request the inbound screen, the communication area and the received map; must not be
     *     {@code null}
     * @param eibAid the raw {@code EIBAID} byte the {@code EVALUATE} at L133 compares
     * @return the terminal state of the invocation, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public ProgramState mainPara(TransactionViewRequest request, byte eibAid) {
        Objects.requireNonNull(request, "A request is required: COTRN02C is driven entirely by its "
                + "communication area, the EIBAID and the received map, all of which travel in it");

        ProgramState state = new ProgramState(codec);

        state.setErrFlagOff();
        state.setUsrModifiedNo();

        state.setMessage(spaces(WS_MESSAGE_LENGTH));
        state.response().clearErrmsgo();

        if (!request.hasNavigationContext()) {
            state.setCommarea(state.commarea().withToProgram(SIGN_ON_PROGRAM));
            returnToPrevScreen(state);
            return state;
        }

        state.setCommarea(request.getNavigationContext());
        state.adoptCt02Info(request.getCt02Info());

        if (!state.commarea().isReenter()) {
            state.setCommarea(state.commarea().withPgmReenter());
            state.moveLowValuesToOutputMap();
            state.moveMinusOneTo(ScreenField.ACTIDIN);

            if (!isSpacesOrLowValues(state.ct02Info().getTrnSelected())) {
                state.setCardninI(state.ct02Info().getTrnSelected());
                processEnterKey(state);
            }

            // Guarded, because every arm of PROCESS-ENTER-KEY ends the task: when the CT02 cursor carried a
            // selection this statement is unreachable in the source and must not paint a second screen over
            // the one already sent.
            if (!state.taskEnded()) {
                sendTrnaddScreen(state);
            }
            return state;
        }

        receiveTrnaddScreen(state, request);

        // The raw EIBAID the caller stated: :133's EVALUATE compares the byte, so PF15 is distinct from PF3
        // here and takes the invalid-key arm, exactly as the COBOL does.
        if (PfKeyResolver.isEnter(eibAid)) {
            processEnterKey(state);
            return state;
        }
        if (PfKeyResolver.isPf3(eibAid)) {
            if (isSpacesOrLowValues(state.commarea().fromProgram())) {
                state.setCommarea(state.commarea().withToProgram(MAIN_MENU_PROGRAM));
            } else {
                state.setCommarea(state.commarea()
                        .withToProgram(state.commarea().fromProgram()));
            }
            returnToPrevScreen(state);
            return state;
        }
        if (PfKeyResolver.isPf4(eibAid)) {
            clearCurrentScreen(state);
            return state;
        }
        if (PfKeyResolver.isPf5(eibAid)) {
            copyLastTranData(state);
            return state;
        }
        state.setErrFlagOn();
        state.setMessage(codec.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                WS_MESSAGE_LENGTH));
        sendTrnaddScreen(state);
        return state;
    }

    /**
     * {@code PROCESS-ENTER-KEY} - lines 164 to 188.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void processEnterKey(ProgramState state) {
        requireState(state);

        validateInputKeyFields(state);
        if (state.taskEnded()) {
            return;
        }
        validateInputDataFields(state);
        if (state.taskEnded()) {
            return;
        }

        String confirm = state.confirmI();
        if (CONFIRM_YES_UPPER.equals(confirm) || CONFIRM_YES_LOWER.equals(confirm)) {
            addTransaction(state);
            return;
        }
        if (CONFIRM_NO_UPPER.equals(confirm) || CONFIRM_NO_LOWER.equals(confirm)
                || isSpacesOrLowValues(confirm)) {
            rejectAndSend(state, MSG_CONFIRM_TO_ADD, ScreenField.CONFIRM);
            return;
        }
        rejectAndSend(state, MSG_INVALID_CONFIRM_VALUE, ScreenField.CONFIRM);
    }

    /**
     * {@code VALIDATE-INPUT-KEY-FIELDS} - lines 193 to 230.
     *
     * <p>L197-203 is a complete {@code IF ... END-IF} and L204 begins a new statement, so the conversion is
     * written unconditionally - but the {@code IF} ends with {@code PERFORM SEND-TRNADD-SCREEN}, which
     * returns to CICS, so a non-numeric account id never reaches the conversion.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void validateInputKeyFields(ProgramState state) {
        requireState(state);

        if (!isSpacesOrLowValues(state.actidinI())) {
            if (!isNumericClass(state.actidinI())) {
                rejectAndSend(state, MSG_ACCOUNT_ID_NOT_NUMERIC, ScreenField.ACTIDIN);
                return;
            }
            state.setWsAcctIdN(computeIntoPic9(numval(state.actidinI()),
                    WS_ACCT_ID_N_DIGITS));
            String normalised = codec.movePic9(state.wsAcctIdN(), WS_ACCT_ID_N_DIGITS);
            state.setXrefAcctId(normalised);
            state.setActidinI(normalised);
            readCxacaixFile(state);
            if (state.taskEnded()) {
                return;
            }
            state.setCardninI(state.cardXrefRecord().xrefCardNum());
            return;
        }
        if (!isSpacesOrLowValues(state.cardninI())) {
            if (!isNumericClass(state.cardninI())) {
                rejectAndSend(state, MSG_CARD_NUMBER_NOT_NUMERIC, ScreenField.CARDNIN);
                return;
            }
            state.setWsCardNumN(computeIntoPic9(numval(state.cardninI()),
                    WS_CARD_NUM_N_DIGITS));
            String normalised = codec.movePic9(state.wsCardNumN(), WS_CARD_NUM_N_DIGITS);
            state.setXrefCardNum(normalised);
            state.setCardninI(normalised);
            readCcxrefFile(state);
            if (state.taskEnded()) {
                return;
            }
            state.setActidinI(codec.movePic9(state.cardXrefRecord().xrefAcctId(),
                    WS_ACCT_ID_N_DIGITS));
            return;
        }
        rejectAndSend(state, MSG_KEY_REQUIRED, ScreenField.ACTIDIN);
    }

    /**
     * {@code VALIDATE-INPUT-DATA-FIELDS} - lines 235 to 437.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void validateInputDataFields(ProgramState state) {
        requireState(state);

        if (state.errFlagOn()) {
            blankDetailFields(state);
        }

        if (isSpacesOrLowValues(state.ttypcdI())) {
            rejectAndSend(state, MSG_TYPE_CD_EMPTY, ScreenField.TTYPCD);
            return;
        }
        if (isSpacesOrLowValues(state.tcatcdI())) {
            rejectAndSend(state, MSG_CATEGORY_CD_EMPTY, ScreenField.TCATCD);
            return;
        }
        if (isSpacesOrLowValues(state.trnsrcI())) {
            rejectAndSend(state, MSG_SOURCE_EMPTY, ScreenField.TRNSRC);
            return;
        }
        if (isSpacesOrLowValues(state.tdescI())) {
            rejectAndSend(state, MSG_DESCRIPTION_EMPTY, ScreenField.TDESC);
            return;
        }
        if (isSpacesOrLowValues(state.trnamtI())) {
            rejectAndSend(state, MSG_AMOUNT_EMPTY, ScreenField.TRNAMT);
            return;
        }
        if (isSpacesOrLowValues(state.torigdtI())) {
            rejectAndSend(state, MSG_ORIG_DATE_EMPTY, ScreenField.TORIGDT);
            return;
        }
        if (isSpacesOrLowValues(state.tprocdtI())) {
            rejectAndSend(state, MSG_PROC_DATE_EMPTY, ScreenField.TPROCDT);
            return;
        }
        if (isSpacesOrLowValues(state.midI())) {
            rejectAndSend(state, MSG_MERCHANT_ID_EMPTY, ScreenField.MID);
            return;
        }
        if (isSpacesOrLowValues(state.mnameI())) {
            rejectAndSend(state, MSG_MERCHANT_NAME_EMPTY, ScreenField.MNAME);
            return;
        }
        if (isSpacesOrLowValues(state.mcityI())) {
            rejectAndSend(state, MSG_MERCHANT_CITY_EMPTY, ScreenField.MCITY);
            return;
        }
        if (isSpacesOrLowValues(state.mzipI())) {
            rejectAndSend(state, MSG_MERCHANT_ZIP_EMPTY, ScreenField.MZIP);
            return;
        }

        if (!isNumericClass(state.ttypcdI())) {
            rejectAndSend(state, MSG_TYPE_CD_NOT_NUMERIC, ScreenField.TTYPCD);
            return;
        }
        if (!isNumericClass(state.tcatcdI())) {
            rejectAndSend(state, MSG_CATEGORY_CD_NOT_NUMERIC, ScreenField.TCATCD);
            return;
        }

        if (isMalformedAmount(state.trnamtI())) {
            rejectAndSend(state, MSG_AMOUNT_FORMAT, ScreenField.TRNAMT);
            return;
        }

        if (isMalformedDate(state.torigdtI())) {
            rejectAndSend(state, MSG_ORIG_DATE_FORMAT, ScreenField.TORIGDT);
            return;
        }

        if (isMalformedDate(state.tprocdtI())) {
            rejectAndSend(state, MSG_PROC_DATE_FORMAT, ScreenField.TPROCDT);
            return;
        }

        state.setWsTranAmtN(CobolDecimal.storeAtPicture(numvalC(state.trnamtI()),
                WS_TRAN_AMT_N_INTEGER_DIGITS, MONETARY_SCALE));
        state.setWsTranAmtE(wsTranAmtEdited(state.wsTranAmtN()));
        state.setTrnamtI(state.wsTranAmtE());

        if (!callCsutldtc(state, state.torigdtI())) {
            rejectAndSend(state, MSG_ORIG_DATE_INVALID, ScreenField.TORIGDT);
            return;
        }

        if (!callCsutldtc(state, state.tprocdtI())) {
            rejectAndSend(state, MSG_PROC_DATE_INVALID, ScreenField.TPROCDT);
            return;
        }

        if (!isNumericClass(state.midI())) {
            rejectAndSend(state, MSG_MERCHANT_ID_NOT_NUMERIC, ScreenField.MID);
        }
    }

    /**
     * {@code MOVE SPACES TO TTYPCDI ... MZIPI} - lines 238 to 248, the eleven receivers of the unreachable
     * {@code IF ERR-FLG-ON} block.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void blankDetailFields(ProgramState state) {
        requireState(state);
        for (ScreenField field : DETAIL_FIELDS) {
            state.setPayload(field, spaces(field.width()));
        }
    }

    private static final List<ScreenField> DETAIL_FIELDS = List.of(
            ScreenField.TTYPCD, ScreenField.TCATCD, ScreenField.TRNSRC, ScreenField.TRNAMT,
            ScreenField.TDESC, ScreenField.TORIGDT, ScreenField.TPROCDT, ScreenField.MID,
            ScreenField.MNAME, ScreenField.MCITY, ScreenField.MZIP);

    /**
     * The positional amount check of L340-343, as one predicate over the four {@code WHEN} clauses that
     * share the single rejection at L344-348.
     *
     * @param image the {@code TRNAMTI} value at its declared width; must not be {@code null}
     * @return {@code true} when any of the four clauses holds, so the screen must be rejected
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static boolean isMalformedAmount(String image) {
        Objects.requireNonNull(image, "An amount image is required: the screen field is PIC X(12) and "
                + "the source indexes into it by position");
        char sign = charAtOrSpace(image, AMOUNT_SIGN_OFFSET);
        if (sign != MINUS_SIGN && sign != PLUS_SIGN) {
            return true;
        }
        if (!isNumericClass(window(image, AMOUNT_INTEGER_OFFSET, AMOUNT_INTEGER_LENGTH))) {
            return true;
        }
        if (charAtOrSpace(image, AMOUNT_POINT_OFFSET) != DECIMAL_POINT) {
            return true;
        }
        return !isNumericClass(window(image, AMOUNT_FRACTION_OFFSET, AMOUNT_FRACTION_LENGTH));
    }

    /**
     * The positional date check of L354-358 and L369-373, which are byte-identical apart from the field
     * they read - so they are one predicate here, applied twice.
     *
     * <p>The five windows tile the ten bytes exactly, so this check alone establishes the
     * {@code YYYY-MM-DD} shape.
     *
     * @param image the {@code TORIGDTI} or {@code TPROCDTI} value at its declared width; must not be
     *     {@code null}
     * @return {@code true} when any of the five clauses holds, so the screen must be rejected
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static boolean isMalformedDate(String image) {
        Objects.requireNonNull(image, "A date image is required: the screen field is PIC X(10) and the "
                + "source indexes into it by position");
        if (!isNumericClass(window(image, DATE_YEAR_OFFSET, DATE_YEAR_LENGTH))) {
            return true;
        }
        if (charAtOrSpace(image, DATE_FIRST_SEPARATOR_OFFSET) != DATE_SEPARATOR) {
            return true;
        }
        if (!isNumericClass(window(image, DATE_MONTH_OFFSET, DATE_PART_LENGTH))) {
            return true;
        }
        if (charAtOrSpace(image, DATE_SECOND_SEPARATOR_OFFSET) != DATE_SEPARATOR) {
            return true;
        }
        return !isNumericClass(window(image, DATE_DAY_OFFSET, DATE_PART_LENGTH));
    }

    /**
     * One {@code CALL 'CSUTLDTC'} site - L389-400 for the origination date and L409-420 for the processing
     * date, which differ only in the field they pass and the message they report.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @param date the ten-byte date to validate; must not be {@code null}
     * @return {@code true} when the call site accepts the date - severity {@link #CSUTLDTC_SEVERITY_OK}, or
     *     any severity with message number {@link #CSUTLDTC_TOLERATED_MESSAGE_NUMBER}; {@code false} when it
     *     rejects
     * @throws NullPointerException if either argument is {@code null}
     */
    public boolean callCsutldtc(ProgramState state, String date) {
        requireState(state);
        Objects.requireNonNull(date, "A date is required: the source moves the screen field into "
                + "CSUTLDTC-DATE before every call");

        state.setCsutldtcDate(codec.movePicX(date, DateUtilityJob.LS_DATE_LENGTH));
        state.setCsutldtcDateFormat(codec.movePicX(WS_DATE_FORMAT,
                DateUtilityJob.LS_DATE_FORMAT_LENGTH));
        state.setCsutldtcResult(null);

        DateValidationResult result =
                dateUtilityJob.validateDate(state.csutldtcDate(), state.csutldtcDateFormat());
        state.setCsutldtcResult(result);

        if (CSUTLDTC_SEVERITY_OK.equals(result.severityCode())) {
            return true;
        }
        return CSUTLDTC_TOLERATED_MESSAGE_NUMBER.equals(result.messageNumber());
    }

    /**
     * {@code ADD-TRANSACTION} - lines 442 to 466: generate the next identifier, build the record and write
     * it.
     *
     * <p>L450 initialises the record area after L448 has read {@code TRAN-ID} out of it, which is the only
     * ordering that works - initialising first would zero the key the browse just returned.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void addTransaction(ProgramState state) {
        requireState(state);

        state.moveHighValuesToTranId();

        try {
            startbrTransactFile(state);
            if (state.taskEnded()) {
                return;
            }
            readprevTransactFile(state);
            if (state.taskEnded()) {
                return;
            }
            endbrTransactFile(state);
        } finally {
            releaseBrowse(state);
        }

        state.setWsTranIdN(movePicXToPic9(state.tranRecord().tranId(),
                WS_TRAN_ID_N_DIGITS));
        state.setWsTranIdN(addOneToPic9(state.wsTranIdN(), WS_TRAN_ID_N_DIGITS));

        state.initializeTranRecord();

        TranRecord record = state.tranRecord();
        record.moveTranId(codec.movePic9(state.wsTranIdN(), WS_TRAN_ID_N_DIGITS));
        record.moveTranTypeCd(state.ttypcdI());
        record.moveTranCatCd(movePicXToPic9Image(state.tcatcdI(),
                TranRecord.TRAN_CAT_CD_LENGTH));
        record.moveTranSource(state.trnsrcI());
        record.moveTranDesc(state.tdescI());
        state.setWsTranAmtN(CobolDecimal.storeAtPicture(numvalC(state.trnamtI()),
                WS_TRAN_AMT_N_INTEGER_DIGITS, MONETARY_SCALE));
        record.moveTranAmt(state.wsTranAmtN());
        record.moveTranCardNum(state.cardninI());
        record.moveTranMerchantId(movePicXToPic9Image(state.midI(),
                TranRecord.TRAN_MERCHANT_ID_LENGTH));
        record.moveTranMerchantName(state.mnameI());
        record.moveTranMerchantCity(state.mcityI());
        record.moveTranMerchantZip(state.mzipI());
        record.moveTranOrigTs(state.torigdtI());
        record.moveTranProcTs(state.tprocdtI());

        writeTransactFile(state);
    }

    /**
     * {@code COPY-LAST-TRAN-DATA} - lines 471 to 495: fill the form from the most recent transaction, then
     * process it as though the operator had pressed Enter.
     *
     * <p>{@code IF NOT ERR-FLG-ON} at L480 is always true when this paragraph reaches it.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void copyLastTranData(ProgramState state) {
        requireState(state);

        validateInputKeyFields(state);
        if (state.taskEnded()) {
            return;
        }

        state.moveHighValuesToTranId();

        try {
            startbrTransactFile(state);
            if (state.taskEnded()) {
                return;
            }
            readprevTransactFile(state);
            if (state.taskEnded()) {
                return;
            }
            endbrTransactFile(state);
        } finally {
            releaseBrowse(state);
        }

        if (!state.errFlagOn()) {
            TranRecord record = state.tranRecord();
            state.setWsTranAmtE(wsTranAmtEdited(record.tranAmt()));
            state.setTtypcdI(record.tranTypeCd());
            state.setTcatcdI(record.tranCatCdImage());
            state.setTrnsrcI(record.tranSource());
            state.setTrnamtI(state.wsTranAmtE());
            state.setTdescI(record.tranDesc());
            state.setTorigdtI(record.tranOrigTs());
            state.setTprocdtI(record.tranProcTs());
            state.setMidI(record.tranMerchantIdImage());
            state.setMnameI(record.tranMerchantName());
            state.setMcityI(record.tranMerchantCity());
            state.setMzipI(record.tranMerchantZip());
        }

        processEnterKey(state);
    }

    /**
     * {@code RETURN-TO-PREV-SCREEN} - lines 500 to 511: hand control to another program.
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

        state.response().setNavigationContext(state.commarea());
        state.response().setCt02Info(state.ct02Info());
        state.response().setNextProgram(state.commarea().toProgram());

        state.response().setNextMapset(spaces(NavigationContext.LAST_MAPSET_LENGTH));
        state.response().setNextMap(spaces(NavigationContext.LAST_MAP_LENGTH));
        state.markTransferred();
    }

    /**
     * {@code SEND-TRNADD-SCREEN} - lines 516 to 534: paint the screen and return to CICS.
     *
     * <p>The paragraph ends with {@code EXEC CICS RETURN} at L530-534, so control never comes back to the
     * statement after the {@code PERFORM}.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void sendTrnaddScreen(ProgramState state) {
        requireState(state);

        populateHeaderInfo(state);
        state.response().setErrmsgo(state.message());

        state.response().setNextMapset(TransactionViewResponse.MAPSET_NAME);
        state.response().setNextMap(TransactionViewResponse.MAP_NAME);
        state.recordScreenSent();

        returnToCics(state);
    }

    /**
     * {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} - lines 530 to 534, and the
     * byte-identical statement the source also writes at lines 156 to 159.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void returnToCics(ProgramState state) {
        requireState(state);

        state.response().setNavigationContext(state.commarea());
        state.response().setCt02Info(state.ct02Info());
        state.response().setNextProgram(PROGRAM_NAME);
        state.markReturned();
    }

    /**
     * {@code RECEIVE-TRNADD-SCREEN} - lines 539 to 547.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @param request the inbound screen; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalStateException if the request and response projections of the symbolic map disagree
     *     about how many fields it has
     */
    public void receiveTrnaddScreen(ProgramState state, TransactionViewRequest request) {
        requireState(state);
        Objects.requireNonNull(request, "A request is required: it carries the received map");

        Map<String, String> received = request.payloadImages();
        requireMatchingProjections(received.size(), ScreenField.values().length);
        for (TransactionViewRequest.ScreenField field : TransactionViewRequest.ScreenField.values()) {
            state.setPayload(ScreenField.ofLabel(field.label()), received.get(field.inputItem()));
        }

        state.setRespCd(FileStatus.NORMAL);
        state.setReasCd(FileStatus.NO_REASON_CODE);
    }

    /**
     * Verifies that the request and response projections of {@code app/cpy-bms/COTRN02.CPY} agree about how
     * many fields the symbolic map has, before the received values are copied across by name.
     *
     * @param requestFieldCount how many field images the request projects
     * @param responseFieldCount how many payload fields the response projects
     * @throws IllegalStateException if the two counts differ
     */
    public static void requireMatchingProjections(int requestFieldCount, int responseFieldCount) {
        if (requestFieldCount != responseFieldCount) {
            throw new IllegalStateException("The request projects " + requestFieldCount + " field(s) of "
                    + "the symbolic map and the response projects " + responseFieldCount + "; both are "
                    + "app/cpy-bms/COTRN02.CPY, which declares " + TransactionViewResponse.FIELD_COUNT
                    + ", so one projection has drifted");
        }
    }

    /**
     * {@code POPULATE-HEADER-INFO} - lines 552 to 571: the six header fields.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void populateHeaderInfo(ProgramState state) {
        requireState(state);

        state.setDateHeader(DateHeader.from(codec, clock));
        state.response().populateHeaderInfo(state.dateHeader());
    }

    /**
     * {@code READ-CXACAIX-FILE} - lines 576 to 604: find the card that belongs to an account.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void readCxacaixFile(ProgramState state) {
        requireState(state);

        CardXrefRepository.ReadResult result =
                cardXrefRepository.readByAccountIdViaAltIndex(state.xrefAcctId());
        state.setRespCd(result.cicsResp());
        state.setReasCd(result.cicsResp2());

        if (result.isFound()) {
            state.setCardXrefRecord(result.record().orElseThrow(missingRecord(result.ddName())));
            return;
        }
        if (result.isNotFound()) {
            rejectAndSend(state, MSG_ACCOUNT_ID_NOT_FOUND, ScreenField.ACTIDIN);
            return;
        }
        displayRespAndReas(state);
        rejectAndSend(state, MSG_XREF_AIX_LOOKUP_FAILED, ScreenField.ACTIDIN);
    }

    /**
     * {@code READ-CCXREF-FILE} - lines 609 to 637: find the account that belongs to a card.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void readCcxrefFile(ProgramState state) {
        requireState(state);

        CardXrefRepository.ReadResult result =
                cardXrefRepository.readByCardNumber(state.xrefCardNum());
        state.setRespCd(result.cicsResp());
        state.setReasCd(result.cicsResp2());

        if (result.isFound()) {
            state.setCardXrefRecord(result.record().orElseThrow(missingRecord(result.ddName())));
            return;
        }
        if (result.isNotFound()) {
            rejectAndSend(state, MSG_CARD_NUMBER_NOT_FOUND, ScreenField.CARDNIN);
            return;
        }
        displayRespAndReas(state);
        rejectAndSend(state, MSG_XREF_LOOKUP_FAILED, ScreenField.CARDNIN);
    }

    // Three paragraphs, always performed as a sequence, and their only purpose is to obtain the highest
    // existing TRAN-ID.

    /**
     * {@code STARTBR-TRANSACT-FILE} - lines 642 to 668: position the browse at the end of the master.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void startbrTransactFile(ProgramState state) {
        requireState(state);

        TransactionRepository.Browse browse = transactionRepository.startBrowse(
                TransactionRepository.BrowseDirection.BACKWARD);
        state.openBrowse(browse);

        TransactionRepository.ReadResult positioning = browse.positioningResult();
        // RESP_NOT_REPORTED, never NORMAL, for an outcome that carries no CICS response - the same rule
        // readprevTransactFile applies, and for the same reason: zero IS DFHRESP(NORMAL), so storing it
        // would make a position that reported nothing indistinguishable from one that succeeded.
        state.setRespCd(positioning.cicsResp().orElse(FileStatus.RESP_NOT_REPORTED));
        state.setReasCd(positioning.cicsResp2());

        startbrOutcome(state, positioning.outcome());
    }

    /**
     * The {@code EVALUATE WS-RESP-CD} of {@code STARTBR-TRANSACT-FILE} - lines 652 to 668 - as a function
     * of the outcome, so all three arms are reachable and none of the three literals is unexercised.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @param outcome the classification of the {@code STARTBR} response
     * @throws NullPointerException if either argument is {@code null}
     */
    public void startbrOutcome(ProgramState state, FileStatus.Outcome outcome) {
        requireState(state);
        Objects.requireNonNull(outcome, "A STARTBR outcome is required");

        switch (outcome) {
            case OK -> {
            }
            case NOT_FOUND -> rejectAndSend(state, MSG_TRANSACTION_ID_NOT_FOUND,
                    ScreenField.ACTIDIN);
            default -> {
                displayRespAndReas(state);
                rejectAndSend(state, MSG_TRANSACTION_LOOKUP_FAILED, ScreenField.ACTIDIN);
            }
        }
    }

    /**
     * {@code READPREV-TRANSACT-FILE} - lines 673 to 697: read the record with the highest key.
     *
     * <p>{@code EXEC CICS READPREV DATASET(WS-TRANSACT-FILE) INTO(TRAN-RECORD) LENGTH(LENGTH OF TRAN-RECORD) RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID)}
     * - so both the record and the identifier field are overwritten by the read, which is exactly why the
     * caller can read {@code TRAN-ID} straight afterwards.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     * @throws IllegalStateException if no browse has been positioned, because the source never issues this
     *     read without the {@code STARTBR} that precedes it at L445 and L476
     */
    public void readprevTransactFile(ProgramState state) {
        requireState(state);

        TransactionRepository.ReadResult result = state.requireBrowse().readPrev();
        // RESP_NOT_REPORTED, never NORMAL: an outcome that carries no CICS response is not a reported
        // DFHRESP(NORMAL), and storing zero would make the two indistinguishable on the DISPLAY at L698.
        state.setRespCd(result.cicsResp().orElse(FileStatus.RESP_NOT_REPORTED));
        state.setReasCd(result.cicsResp2());

        if (result.isRecordReturned()) {
            state.setTranRecord(result.requireRecord());
            return;
        }
        if (result.isEndOfFile()) {
            state.tranRecord().moveTranId(TRAN_ID_ZEROS);
            return;
        }
        displayRespAndReas(state);
        rejectAndSend(state, MSG_TRANSACTION_LOOKUP_FAILED, ScreenField.ACTIDIN);
    }

    private static void releaseBrowse(ProgramState state) {
        if (!state.browseOpen()) {
            return;
        }
        try {
            state.closeBrowse();
        } catch (RuntimeException cleanupFailure) {
            LOG.warn("Ending the " + WS_TRANSACT_FILE + " browse of " + PROGRAM_NAME + " after a request "
                    + "that did not reach ENDBR failed - " + cleanupFailure.getClass().getName()
                    + ". The request's own outcome is unchanged, because the request's own failure is "
                    + "the one that matters.");
        }
    }

    /**
     * {@code ENDBR-TRANSACT-FILE} - lines 702 to 706: end the browse.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void endbrTransactFile(ProgramState state) {
        requireState(state);
        state.closeBrowse();
    }

    /**
     * {@code WRITE-TRANSACT-FILE} - lines 711 to 749: insert the record and report the outcome.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void writeTransactFile(ProgramState state) {
        requireState(state);

        TransactionRepository.WriteResult result =
                transactionRepository.write(state.tranRecord());                                // L713-721
        // RESP_NOT_REPORTED, never NORMAL - see readprevTransactFile. A write that reported no CICS
        // response reaches WHEN OTHER, and its DISPLAY must not render RESP: 000000000.
        state.setRespCd(result.cicsResp().orElse(FileStatus.RESP_NOT_REPORTED));
        state.setReasCd(result.cicsResp2());

        // L723-L749 EVALUATE WS-RESP-CD.
        if (result.isWritten()) {                                                               // WHEN NORMAL
            // The record is in the dataset, so it is recorded here - the one arm on which that is true.
            // Taken before INITIALIZE-ALL-FIELDS on purpose: that paragraph blanks the fourteen screen
            // fields the record was composed from, and the image has to be the one the write carried.
            state.recordWritten(state.tranRecord());
            initializeAllFields(state);                                                          // L725
            state.setMessage(spaces(WS_MESSAGE_LENGTH));                                          // L726
            state.response().getMetadata(ScreenField.ERRMSG).setColour(BmsAttributes.DFHGREEN);   // L727
            state.setMessage(stringInto(state.message(), codec.concatenateDelimitedBySize(
                    MSG_ADDED_SUCCESSFULLY,
                    MSG_YOUR_TRAN_ID_IS,
                    stringDelimitedBySpace(state.tranRecord().tranId()),
                    MSG_FULL_STOP)));
            sendTrnaddScreen(state);
            return;
        }
        if (result.isDuplicate()) {
            rejectAndSend(state, MSG_TRAN_ID_ALREADY_EXISTS, ScreenField.ACTIDIN);
            return;
        }
        displayRespAndReas(state);
        rejectAndSend(state, MSG_UNABLE_TO_ADD, ScreenField.ACTIDIN);
    }

    /**
     * {@code CLEAR-CURRENT-SCREEN} - lines 754 to 757: the PF4 action.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void clearCurrentScreen(ProgramState state) {
        requireState(state);

        initializeAllFields(state);
        sendTrnaddScreen(state);
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS} - lines 762 to 779: blank the form and put the cursor back.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void initializeAllFields(ProgramState state) {
        requireState(state);

        state.moveMinusOneTo(ScreenField.ACTIDIN);
        for (ScreenField field : ALL_INPUT_FIELDS) {
            state.setPayload(field, spaces(field.width()));
        }
        state.setMessage(spaces(WS_MESSAGE_LENGTH));
    }

    private static final List<ScreenField> ALL_INPUT_FIELDS = List.of(
            ScreenField.ACTIDIN, ScreenField.CARDNIN, ScreenField.TTYPCD, ScreenField.TCATCD,
            ScreenField.TRNSRC, ScreenField.TRNAMT, ScreenField.TDESC, ScreenField.TORIGDT,
            ScreenField.TPROCDT, ScreenField.MID, ScreenField.MNAME, ScreenField.MCITY,
            ScreenField.MZIP, ScreenField.CONFIRM);

    // Twenty of the twenty-five message sites are the same four statements in the same order, so they are
    // written once - which also means the ERR-FLG, the message, the cursor and the send can never drift
    // apart between sites.

    /**
     * The four statements every rejection site writes: {@code MOVE 'Y' TO WS-ERR-FLG},
     * {@code MOVE <literal> TO WS-MESSAGE}, {@code MOVE -1 TO <field>L} and
     * {@code PERFORM SEND-TRNADD-SCREEN}.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @param message the byte-exact literal the source moves into {@code WS-MESSAGE}; must not be
     *     {@code null}
     * @param cursor the field whose length item receives {@code -1}; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public void rejectAndSend(ProgramState state, String message, ScreenField cursor) {
        requireState(state);
        Objects.requireNonNull(message, "A rejection carries the source's own literal");
        Objects.requireNonNull(cursor, "A rejection places the cursor on a named field");

        state.setErrFlagOn();
        state.setMessage(codec.movePicX(message, WS_MESSAGE_LENGTH));
        state.moveMinusOneTo(cursor);
        sendTrnaddScreen(state);
    }

    /**
     * {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} - the five {@code WHEN OTHER} arms at L598,
     * L631, L662, L691 and L743, which are byte-identical.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void displayRespAndReas(ProgramState state) {
        requireState(state);

        String line = DISPLAY_RESP_PREFIX + respImage(state.respCd())
                + DISPLAY_REAS_PREFIX + respImage(state.reasCd());
        state.recordDisplay(line);
        LOG.info(line);
    }

    private String respImage(int code) {
        return FileStatus.respReported(code)
                ? codec.movePic9(code, WS_RESP_CD_DIGITS)
                : FileStatus.respNotReportedImage(WS_RESP_CD_DIGITS);
    }

    public static String spaces(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("A field cannot be " + length + " characters wide");
        }
        return SPACE.repeat(length);
    }

    /**
     * The combined relation {@code = SPACES OR LOW-VALUES}, and its negation
     * {@code NOT = SPACES AND LOW-VALUES}, which the source writes at L124, L137, L196, L210 and in eleven
     * arms of the empty cascade.
     *
     * @param image the field value, possibly {@code null}
     * @return {@code true} when the item is entirely spaces or entirely low-values, including when it is
     *     empty
     */
    public static boolean isSpacesOrLowValues(String image) {
        if (image == null) {
            return true;
        }
        boolean allSpaces = true;
        boolean allLowValues = true;
        for (int index = 0; index < image.length(); index++) {
            char character = image.charAt(index);
            if (character != ' ') {
                allSpaces = false;
            }
            if (character != '\u0000') {
                allLowValues = false;
            }
        }
        return allSpaces || allLowValues;
    }

    /**
     * The COBOL {@code NUMERIC} class test for an alphanumeric item, as used at L197, L211, L323, L329,
     * L341, L343, L354, L356, L358, L369, L371, L373 and L430.
     *
     * @param image the field value, possibly {@code null}
     * @return {@code true} when the value is non-empty and every character is {@code '0'} to {@code '9'}
     */
    public static boolean isNumericClass(String image) {
        if (image == null || image.isEmpty()) {
            return false;
        }
        for (int index = 0; index < image.length(); index++) {
            if (!isDigit(image.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A reference modification {@code image(offset + 1 : length)}, tolerant of an image shorter than its
     * declared width.
     *
     * <p>COBOL cannot have a short field - the item is its declared width, always - but a JSON payload can
     * arrive with one, so the missing positions are read as spaces.
     *
     * @param image the field value; must not be {@code null}
     * @param offset the zero-based start of the window
     * @param length the window's width
     * @return exactly {@code length} characters
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static String window(String image, int offset, int length) {
        Objects.requireNonNull(image, "A reference modification needs an item to read from");
        StringBuilder window = new StringBuilder(length);
        for (int index = offset; index < offset + length; index++) {
            window.append(charAtOrSpace(image, index));
        }
        return window.toString();
    }

    /**
     * One character of a reference modification, reading a space beyond the end of a short image, for the
     * reason given on {@link #window(String, int, int)}.
     *
     * @param image the field value; must not be {@code null}
     * @param offset the zero-based position
     * @return the character at that position, or a space when the image does not reach it
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static char charAtOrSpace(String image, int offset) {
        Objects.requireNonNull(image, "A reference modification needs an item to read from");
        if (offset < 0 || offset >= image.length()) {
            return ' ';
        }
        return image.charAt(offset);
    }

    public static BigDecimal numval(String image) {
        return NumericIntrinsics.numval(image);
    }

    /**
     * The conformance half of {@link #numval(String)}, in the shape {@code FUNCTION TEST-NUMVAL} reports
     * it.
     *
     * @param image the argument to test; must not be {@code null}
     * @return {@value #NUMVAL_CONFORMS} when the argument conforms; otherwise the one-based position of the
     *     first character in error, or the argument's length plus one when it holds no digit at all
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static int testNumval(String image) {
        return NumericIntrinsics.testNumval(image);
    }

    /**
     * {@code FUNCTION NUMVAL-C} - lines 383 and 456.
     *
     * @param image the argument to convert; must not be {@code null}
     * @return the value the argument denotes, or zero when it does not conform
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static BigDecimal numvalC(String image) {
        return NumericIntrinsics.numvalC(image);
    }

    /**
     * The conformance half of {@link #numvalC(String)}, in the shape {@code FUNCTION TEST-NUMVAL-C} reports
     * it.
     *
     * @param image the argument to test; must not be {@code null}
     * @return {@value #NUMVAL_CONFORMS} when the argument conforms; otherwise the one-based position of the
     *     first character in error, or the argument's length plus one when it holds no digit at all
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static int testNumvalC(String image) {
        return NumericIntrinsics.testNumvalC(image);
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    /**
     * {@code COMPUTE <unsigned PIC 9(n)> = <expression>} - the receivers of L204 and L218.
     *
     * <p>Two truncations apply, in this order, and neither raises a condition because neither
     * {@code ROUNDED} nor {@code ON SIZE ERROR} appears anywhere in this program - or, for {@code ROUNDED},
     * anywhere in the twenty-eight: the fraction is discarded, because the receiver has no fraction digits.
     *
     * @param value the computed value; must not be {@code null}
     * @param digits the receiver's declared digit count; at least 1 and at most 18
     * @return the stored value as a non-negative {@code long}
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code digits} is outside 1 to 18
     */
    public static long computeIntoPic9(BigDecimal value, int digits) {
        Objects.requireNonNull(value, "A COMPUTE needs a value to store");
        requireDigitCount(digits);
        return CobolDecimal.storeAtPicture(value, digits, 0).abs().longValueExact();
    }

    /**
     * {@code MOVE <PIC X(n)> TO <PIC 9(m)>} - line 448, {@code MOVE TRAN-ID TO WS-TRAN-ID-N}.
     *
     * @param image the alphanumeric sender; must not be {@code null}
     * @param digits the receiver's declared digit count; at least 1 and at most 18
     * @return the sender read as an unsigned integer, or zero when it is not all digits
     * @throws NullPointerException if {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code digits} is outside 1 to 18
     */
    public static long movePicXToPic9(String image, int digits) {
        Objects.requireNonNull(image, "A numeric MOVE needs a sender");
        requireDigitCount(digits);
        if (!isNumericClass(image)) {
            return 0L;
        }
        String kept = image.length() > digits ? image.substring(image.length() - digits) : image;
        return Long.parseLong(kept);
    }

    /**
     * The same move as {@link #movePicXToPic9(String, int)} rendered back as a digit image, for the two
     * record fields that are {@code PIC 9} and whose senders are screen fields:
     * {@code TRAN-CAT-CD PIC 9(04)} at L453 and {@code TRAN-MERCHANT-ID PIC 9(09)} at L460.
     *
     * @param image the alphanumeric sender; must not be {@code null}
     * @param digits the receiver's declared digit count; at least 1 and at most 18
     * @return exactly {@code digits} digit characters
     * @throws NullPointerException if {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code digits} is outside 1 to 18
     */
    public static String movePicXToPic9Image(String image, int digits) {
        long value = movePicXToPic9(image, digits);
        StringBuilder rendered = new StringBuilder(Long.toString(value));
        while (rendered.length() < digits) {
            rendered.insert(0, '0');
        }
        return rendered.toString();
    }

    /**
     * {@code ADD 1 TO WS-TRAN-ID-N} - line 449.
     *
     * @param value the current value; must not be negative
     * @param digits the receiver's declared digit count; at least 1 and at most 18
     * @return the incremented value, wrapped to {@code digits} digits
     * @throws IllegalArgumentException if {@code value} is negative or {@code digits} is outside 1 to 18
     */
    public static long addOneToPic9(long value, int digits) {
        requireDigitCount(digits);
        if (value < 0) {
            throw new IllegalArgumentException("PIC 9 has no sign position, so " + value
                    + " cannot be the current value of an unsigned counter");
        }
        BigDecimal incremented = BigDecimal.valueOf(value).add(BigDecimal.valueOf(ONE));
        return CobolDecimal.storeAtPicture(incremented, digits, 0).longValueExact();
    }

    /**
     * Requires a digit count a {@code long} can hold, which bounds every numeric receiver in this program:
     * the widest is {@code PIC 9(16)}.
     *
     * @param digits the declared digit count
     * @throws IllegalArgumentException if it is below 1 or above 18
     */
    private static void requireDigitCount(int digits) {
        if (digits < 1 || digits > 18) {
            throw new IllegalArgumentException("A PIC 9 receiver of " + digits + " digit(s) is not one "
                    + "this program declares; the widest is PIC 9(16) and a long holds 18 digits");
        }
    }

    /**
     * {@code MOVE <numeric> TO WS-TRAN-AMT-E}, whose picture is {@code +99999999.99} - lines 385 and 481.
     *
     * @param value the sending value; must not be {@code null}
     * @return exactly {@link #WS_TRAN_AMT_E_LENGTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public String wsTranAmtEdited(BigDecimal value) {
        Objects.requireNonNull(value, "A numeric-edited MOVE needs a sending value");

        BigDecimal stored = CobolDecimal.storeAtPicture(value, WS_TRAN_AMT_E_INTEGER_DIGITS,
                MONETARY_SCALE);
        String digits = codec.movePic9(stored.abs().unscaledValue().toString(),
                WS_TRAN_AMT_E_INTEGER_DIGITS + MONETARY_SCALE);
        char sign = stored.signum() < 0 ? MINUS_SIGN : PLUS_SIGN;
        return sign
                + digits.substring(0, WS_TRAN_AMT_E_INTEGER_DIGITS)
                + DECIMAL_POINT
                + digits.substring(WS_TRAN_AMT_E_INTEGER_DIGITS);
    }

    /**
     * {@code <item> DELIMITED BY SPACE} - line 731, the identifier operand of the success notice.
     *
     * @param sendingItem the item to transfer; must not be {@code null}
     * @return the characters before the first space, or the whole item when it holds none
     * @throws NullPointerException if {@code sendingItem} is {@code null}
     */
    public static String stringDelimitedBySpace(String sendingItem) {
        Objects.requireNonNull(sendingItem, "A STRING operand is required");
        int firstSpace = sendingItem.indexOf(' ');
        return firstSpace < 0 ? sendingItem : sendingItem.substring(0, firstSpace);
    }

    /**
     * {@code STRING ... INTO <receiver>} - lines 728 to 733.
     *
     * @param receiver the receiving item at its declared width; must not be {@code null}
     * @param composed the concatenated operands; must not be {@code null}
     * @return the receiver with {@code composed} overlaid from position one, at its original width
     * @throws NullPointerException if either argument is {@code null}
     */
    public static String stringInto(String receiver, String composed) {
        Objects.requireNonNull(receiver, "A STRING statement needs a receiving item");
        Objects.requireNonNull(composed, "A STRING statement needs something to move");
        if (composed.length() >= receiver.length()) {
            return composed.substring(0, receiver.length());
        }
        return composed + receiver.substring(composed.length());
    }

    /**
     * Reads the raw {@code EIBAID} byte out of the payload's {@code aid} member, so the four tests of the
     * {@code EVALUATE EIBAID} at L133-152 are the byte equalities the source writes.
     *
     * @param aidImage the {@code aid} member from the payload - one character whose code point is the raw
     *     {@code EIBAID} byte; may be {@code null}
     * @return the {@code EIBAID} byte, or {@link CicsAid#DFHNULL}
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

    byte resolveEibAid(Integer statedAid, String aidToken) {
        if (statedAid == null) {
            return eibAidOf(aidToken);
        }
        return AidRequestParameter.requireStatedAid(AID_MEMBER, statedAid, aidToken, codec);
    }

    private static Supplier<IllegalStateException> missingRecord(String ddName) {
        return () -> new IllegalStateException("A read of " + ddName + " reported that it found a record "
                + "and then carried none; the outcome and the record disagree");
    }

    private static void requireState(ProgramState state) {
        Objects.requireNonNull(state, "A ProgramState is required: every WORKING-STORAGE item of "
                + "COTRN02C lives in it, so that two concurrent requests cannot see each other's screen");
    }

    /**
     * The per-request working storage of {@code COTRN02C}: the screen buffer, the communication area, the
     * record area and every flag and carrier between them.
     */
    public static final class ProgramState {
        /**
         * The length of the communication area this program passes: {@code CARDDEMO-COMMAREA} plus the
         * {@code 05 CDEMO-CT02-INFO} group it appends at L72-80. 160 + 58 = 218.
         */
        public static final int PASSED_COMMAREA_LENGTH =
                NavigationContext.COMMAREA_LENGTH + TransactionViewResponse.Ct02Info.LENGTH;

        private final TransactionViewResponse response = new TransactionViewResponse();

        private final TransactionViewRequest symbolicMap = new TransactionViewRequest();

        private NavigationContext commarea = NavigationContext.empty();

        private TransactionViewResponse.Ct02Info ct02Info = new TransactionViewResponse.Ct02Info();

        private String message = spaces(WS_MESSAGE_LENGTH);

        private boolean errFlag;

        private boolean usrModified;

        private long wsAcctIdN;

        private long wsCardNumN;

        private long wsTranIdN;

        private BigDecimal wsTranAmtN = CobolDecimal.zero(MONETARY_SCALE);

        private String wsTranAmtE = spaces(WS_TRAN_AMT_E_LENGTH);

        private String csutldtcDate = spaces(DateUtilityJob.LS_DATE_LENGTH);

        private String csutldtcDateFormat = spaces(DateUtilityJob.LS_DATE_FORMAT_LENGTH);

        private DateValidationResult csutldtcResult;

        private CardXrefRecord cardXrefRecord =
                new CardXrefRecord(spaces(CardXrefRecord.XREF_CARD_NUM_LENGTH), 0, 0L);

        private String xrefAcctId = spaces(CardXrefRecord.XREF_ACCT_ID_LENGTH);

        private String xrefCardNum = spaces(CardXrefRecord.XREF_CARD_NUM_LENGTH);

        private TranRecord tranRecord;

        private boolean tranIdAtHighValues;

        private TransactionRepository.Browse browse;

        private int respCd;

        private int reasCd;

        private DateHeader dateHeader;

        private boolean returned;

        private boolean transferred;

        private boolean screenSent;

        private final List<String> displayLines = new ArrayList<>();

        private final List<String> writtenRecords = new ArrayList<>();

        private final FixedWidthCodec codec;

        public ProgramState(FixedWidthCodec codec) {
            this.codec = Objects.requireNonNull(codec, "A codec is required: TRAN-RECORD is 350 bytes in "
                    + "a specific code page, and the area cannot be built without knowing which");
            this.tranRecord = new TranRecord(codec.charset());
            for (ScreenField field : ScreenField.values()) {
                response.setOutputItem(field, TransactionViewResponse.lowValues(field.width()));
            }
        }

        /**
         * {@code 01 COTRN2AO} - the buffer this task will send.
         *
         * @return the response, never {@code null}
         */
        public TransactionViewResponse response() {
            return response;
        }

        /**
         * The {@code xxxL} halfwords, which carry the cursor requests.
         *
         * @return the metadata carrier, never {@code null}
         */
        public TransactionViewRequest symbolicMap() {
            return symbolicMap;
        }

        public String payload(ScreenField field) {
            return response.getOutputItem(field);
        }

        /**
         * Writes one payload field - the {@code xxxO} view of the same bytes.
         *
         * <p>The value is put through the {@code PIC X} rule by the response's own setter, so a short one
         * space pads on the right and an over-wide one truncates on the right.
         *
         * @param field the field to write; must not be {@code null}
         * @param value the value to move into it; must not be {@code null}
         * @throws NullPointerException if either argument is {@code null}
         */
        public void setPayload(ScreenField field, String value) {
            response.setOutputItem(field, value);
        }

        /**
         * {@code MOVE LOW-VALUES TO COTRN2AO} - L122.
         */
        public void moveLowValuesToOutputMap() {
            response.moveLowValuesToOutputMap();
            symbolicMap.resetMetadata();
        }

        /**
         * {@code MOVE -1 TO <field>L} - the twenty cursor placements.
         *
         * @param field the field the cursor should land on; must not be {@code null}
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public void moveMinusOneTo(ScreenField field) {
            Objects.requireNonNull(field, "A cursor placement names a field");
            symbolicMap.requestCursor(TransactionViewRequest.ScreenField.valueOf(field.name()));
        }

        /**
         * Whether a field's length item holds {@value TransactionViewRequest#CURSOR_REQUEST}.
         *
         * @param field the field to test; must not be {@code null}
         * @return {@code true} when this task asked for the cursor there
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public boolean cursorRequestedOn(ScreenField field) {
            Objects.requireNonNull(field, "A cursor query names a field");
            return symbolicMap.isCursorRequested(
                    TransactionViewRequest.ScreenField.valueOf(field.name()));
        }

        /**
         * This screen's presentation metadata, in the shared envelope every online response publishes.
         *
         * @return the metadata; never {@code null}
         */
        public ScreenMetadata screenMetadata() {
            Map<String, ScreenMetadata.FieldMetadata> fields = new LinkedHashMap<>();
            String cursorOn = null;
            for (ScreenField field : ScreenField.values()) {
                TransactionViewResponse.FieldMetadata quad = response.getMetadata(field);
                fields.put(field.label(), ScreenMetadata.FieldMetadata.of(quad.getColour(),
                        quad.getProgrammedSymbols(), quad.getHighlight(), quad.getValidation()));
                if (cursorOn == null && cursorRequestedOn(field)) {
                    cursorOn = field.label();
                }
            }
            return ScreenMetadata.of(cursorOn,
                    response.getMetadata(ScreenField.ERRMSG).getColour(),
                    false,
                    fields);
        }

        public String actidinI() {
            return payload(ScreenField.ACTIDIN);
        }

        public void setActidinI(String value) {
            setPayload(ScreenField.ACTIDIN, value);
        }

        public String cardninI() {
            return payload(ScreenField.CARDNIN);
        }

        public void setCardninI(String value) {
            setPayload(ScreenField.CARDNIN, value);
        }

        public String ttypcdI() {
            return payload(ScreenField.TTYPCD);
        }

        public void setTtypcdI(String value) {
            setPayload(ScreenField.TTYPCD, value);
        }

        public String tcatcdI() {
            return payload(ScreenField.TCATCD);
        }

        public void setTcatcdI(String value) {
            setPayload(ScreenField.TCATCD, value);
        }

        public String trnsrcI() {
            return payload(ScreenField.TRNSRC);
        }

        public void setTrnsrcI(String value) {
            setPayload(ScreenField.TRNSRC, value);
        }

        public String tdescI() {
            return payload(ScreenField.TDESC);
        }

        public void setTdescI(String value) {
            setPayload(ScreenField.TDESC, value);
        }

        public String trnamtI() {
            return payload(ScreenField.TRNAMT);
        }

        public void setTrnamtI(String value) {
            setPayload(ScreenField.TRNAMT, value);
        }

        public String torigdtI() {
            return payload(ScreenField.TORIGDT);
        }

        public void setTorigdtI(String value) {
            setPayload(ScreenField.TORIGDT, value);
        }

        public String tprocdtI() {
            return payload(ScreenField.TPROCDT);
        }

        public void setTprocdtI(String value) {
            setPayload(ScreenField.TPROCDT, value);
        }

        public String midI() {
            return payload(ScreenField.MID);
        }

        public void setMidI(String value) {
            setPayload(ScreenField.MID, value);
        }

        public String mnameI() {
            return payload(ScreenField.MNAME);
        }

        public void setMnameI(String value) {
            setPayload(ScreenField.MNAME, value);
        }

        public String mcityI() {
            return payload(ScreenField.MCITY);
        }

        public void setMcityI(String value) {
            setPayload(ScreenField.MCITY, value);
        }

        public String mzipI() {
            return payload(ScreenField.MZIP);
        }

        public void setMzipI(String value) {
            setPayload(ScreenField.MZIP, value);
        }

        public String confirmI() {
            return payload(ScreenField.CONFIRM);
        }

        public void setConfirmI(String value) {
            setPayload(ScreenField.CONFIRM, value);
        }

        public String errmsgO() {
            return payload(ScreenField.ERRMSG);
        }

        public NavigationContext commarea() {
            return commarea;
        }

        public void setCommarea(NavigationContext commarea) {
            this.commarea = commarea == null ? NavigationContext.empty() : commarea;
        }

        public TransactionViewResponse.Ct02Info ct02Info() {
            return ct02Info;
        }

        public void setCt02Info(TransactionViewResponse.Ct02Info ct02Info) {
            this.ct02Info = ct02Info == null ? new TransactionViewResponse.Ct02Info() : ct02Info;
        }

        /**
         * Copies the request's projection of {@code CDEMO-CT02-INFO} into the program's own copy, which is
         * the second half of {@code MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA} at L119.
         *
         * @param source the request's projection; {@code null} is read as a fresh one
         */
        public void adoptCt02Info(TransactionViewRequest.Ct02Info source) {
            TransactionViewResponse.Ct02Info adopted = new TransactionViewResponse.Ct02Info();
            if (source != null) {
                adopted.setTrnidFirst(source.getTrnidFirst());
                adopted.setTrnidLast(source.getTrnidLast());
                adopted.setPageNum(source.getPageNum());
                adopted.setNextPageFlg(source.getNextPageFlg());
                adopted.setTrnSelFlg(source.getTrnSelFlg());
                adopted.setTrnSelected(source.getTrnSelected());
            }
            this.ct02Info = adopted;
        }

        public String message() {
            return message;
        }

        public void setMessage(String message) {
            this.message = Objects.requireNonNull(message, "WS-MESSAGE is PIC X(80); move SPACES "
                    + "explicitly rather than null");
        }

        public boolean errFlagOn() {
            return errFlag;
        }

        public boolean errFlagOff() {
            return !errFlag;
        }

        /**
         * {@code MOVE 'Y' TO WS-ERR-FLG}.
         */
        public void setErrFlagOn() {
            this.errFlag = true;
        }

        /**
         * {@code SET ERR-FLG-OFF TO TRUE} - L109.
         */
        public void setErrFlagOff() {
            this.errFlag = false;
        }

        public boolean usrModifiedYes() {
            return usrModified;
        }

        public boolean usrModifiedNo() {
            return !usrModified;
        }

        /**
         * {@code SET USR-MODIFIED-NO TO TRUE} - L110, the only statement that touches this flag.
         */
        public void setUsrModifiedNo() {
            this.usrModified = false;
        }

        /**
         * {@code SET USR-MODIFIED-YES TO TRUE} - a state the program declares at L50 and never sets.
         */
        public void setUsrModifiedYes() {
            this.usrModified = true;
        }

        public long wsAcctIdN() {
            return wsAcctIdN;
        }

        public void setWsAcctIdN(long wsAcctIdN) {
            this.wsAcctIdN = wsAcctIdN;
        }

        public long wsCardNumN() {
            return wsCardNumN;
        }

        public void setWsCardNumN(long wsCardNumN) {
            this.wsCardNumN = wsCardNumN;
        }

        public long wsTranIdN() {
            return wsTranIdN;
        }

        public void setWsTranIdN(long wsTranIdN) {
            this.wsTranIdN = wsTranIdN;
        }

        public BigDecimal wsTranAmtN() {
            return wsTranAmtN;
        }

        public void setWsTranAmtN(BigDecimal wsTranAmtN) {
            Objects.requireNonNull(wsTranAmtN, "WS-TRAN-AMT-N is PIC S9(9)V99 and holds a value, never "
                    + "null; move zero explicitly");
            if (wsTranAmtN.scale() != MONETARY_SCALE) {
                throw new IllegalArgumentException("WS-TRAN-AMT-N is PIC S9(9)V99, so its scale is "
                        + MONETARY_SCALE + " and not " + wsTranAmtN.scale() + "; store the value through "
                        + "CobolDecimal so the truncation is applied where it can be reviewed");
            }
            this.wsTranAmtN = wsTranAmtN;
        }

        public String wsTranAmtE() {
            return wsTranAmtE;
        }

        public void setWsTranAmtE(String wsTranAmtE) {
            this.wsTranAmtE = Objects.requireNonNull(wsTranAmtE, "WS-TRAN-AMT-E is a twelve-character "
                    + "edited image and is never null");
        }

        public String csutldtcDate() {
            return csutldtcDate;
        }

        public void setCsutldtcDate(String csutldtcDate) {
            this.csutldtcDate = Objects.requireNonNull(csutldtcDate,
                    "CSUTLDTC-DATE is PIC X(10) and is never null");
        }

        public String csutldtcDateFormat() {
            return csutldtcDateFormat;
        }

        public void setCsutldtcDateFormat(String csutldtcDateFormat) {
            this.csutldtcDateFormat = Objects.requireNonNull(csutldtcDateFormat,
                    "CSUTLDTC-DATE-FORMAT is PIC X(10) and is never null");
        }

        public DateValidationResult csutldtcResult() {
            return csutldtcResult;
        }

        public void setCsutldtcResult(DateValidationResult csutldtcResult) {
            this.csutldtcResult = csutldtcResult;
        }

        public CardXrefRecord cardXrefRecord() {
            return cardXrefRecord;
        }

        /**
         * {@code INTO(CARD-XREF-RECORD)} - the read at L580 and L613 overwrites the whole fifty-byte area,
         * key fields included, so the two {@code RIDFLD} images are re-synchronised from the record here.
         *
         * @param cardXrefRecord the record just read; must not be {@code null}
         * @throws NullPointerException if {@code cardXrefRecord} is {@code null}
         */
        public void setCardXrefRecord(CardXrefRecord cardXrefRecord) {
            this.cardXrefRecord = Objects.requireNonNull(cardXrefRecord,
                    "A cross-reference record is required; a read that found nothing takes the NOTFND arm "
                            + "instead of storing null");
            this.xrefCardNum = cardXrefRecord.xrefCardNum();
            this.xrefAcctId = codec.movePic9(cardXrefRecord.xrefAcctId(),
                    CardXrefRecord.XREF_ACCT_ID_LENGTH);
        }

        public String xrefAcctId() {
            return xrefAcctId;
        }

        public void setXrefAcctId(String xrefAcctId) {
            this.xrefAcctId = Objects.requireNonNull(xrefAcctId,
                    "XREF-ACCT-ID is PIC 9(11) and is never null");
        }

        public String xrefCardNum() {
            return xrefCardNum;
        }

        public void setXrefCardNum(String xrefCardNum) {
            this.xrefCardNum = Objects.requireNonNull(xrefCardNum,
                    "XREF-CARD-NUM is PIC X(16) and is never null");
        }

        public TranRecord tranRecord() {
            return tranRecord;
        }

        /**
         * {@code INTO(TRAN-RECORD)} - the read at L677.
         *
         * @param tranRecord the record just read; must not be {@code null}
         * @throws NullPointerException if {@code tranRecord} is {@code null}
         */
        public void setTranRecord(TranRecord tranRecord) {
            this.tranRecord = Objects.requireNonNull(tranRecord,
                    "A transaction record is required; an end-of-file takes the ENDFILE arm instead of "
                            + "storing null");
        }

        /**
         * {@code INITIALIZE TRAN-RECORD} - L450: numerics to zero, alphanumerics to spaces, and the
         * trailing twenty-byte {@code FILLER} left exactly as it was.
         */
        public void initializeTranRecord() {
            this.tranRecord.initialize();
        }

        /**
         * {@code MOVE HIGH-VALUES TO TRAN-ID} - L444 and L475.
         */
        public void moveHighValuesToTranId() {
            this.tranIdAtHighValues = true;
        }

        public boolean tranIdAtHighValues() {
            return tranIdAtHighValues;
        }

        /**
         * {@code EXEC CICS STARTBR} - L644.
         *
         * @param browse the positioned handle; must not be {@code null}
         * @throws NullPointerException if {@code browse} is {@code null}
         */
        public void openBrowse(TransactionRepository.Browse browse) {
            this.browse = Objects.requireNonNull(browse, "A positioned browse is required");
        }

        public TransactionRepository.Browse requireBrowse() {
            if (browse == null) {
                throw new IllegalStateException("No browse of " + WS_TRANSACT_FILE + " is positioned; "
                        + "COTRN02C issues STARTBR at L445 and L476 immediately before every READPREV, so "
                        + "a read without one means the sequence was entered part way through");
            }
            return browse;
        }

        /**
         * {@code EXEC CICS ENDBR} - L704.
         */
        public void closeBrowse() {
            if (browse != null) {
                browse.endBrowse();
                browse = null;
            }
        }

        public boolean browseOpen() {
            return browse != null;
        }

        public int respCd() {
            return respCd;
        }

        public void setRespCd(int respCd) {
            this.respCd = respCd;
        }

        public int reasCd() {
            return reasCd;
        }

        public void setReasCd(int reasCd) {
            this.reasCd = reasCd;
        }

        public DateHeader dateHeader() {
            return dateHeader;
        }

        public void setDateHeader(DateHeader dateHeader) {
            this.dateHeader = Objects.requireNonNull(dateHeader,
                    "POPULATE-HEADER-INFO always captures a date and time before it moves them");
        }

        public boolean returned() {
            return returned;
        }

        /**
         * {@code EXEC CICS RETURN} - L530.
         */
        public void markReturned() {
            this.returned = true;
        }

        public boolean transferred() {
            return transferred;
        }

        /**
         * {@code EXEC CICS XCTL} - L508.
         */
        public void markTransferred() {
            this.transferred = true;
        }

        /**
         * Whether this task has ended, by either route.
         *
         * @return {@code true} once the task has returned to CICS or transferred
         */
        public boolean taskEnded() {
            return returned || transferred;
        }

        public boolean screenSent() {
            return screenSent;
        }

        /**
         * {@code EXEC CICS SEND MAP ... ERASE CURSOR} - L522.
         */
        public void recordScreenSent() {
            this.screenSent = true;
        }

        /**
         * The {@code DISPLAY} lines this task emitted, oldest first.
         *
         * @return an unmodifiable view, never {@code null}
         */
        public List<String> displayLines() {
            return Collections.unmodifiableList(displayLines);
        }

        public void recordDisplay(String text) {
            displayLines.add(Objects.requireNonNull(text, "A DISPLAY emits a line, never null"));
        }

        /**
         * The record images {@code EXEC CICS WRITE} inserted, oldest first, each exactly
         * {@value TranRecord#RECORD_LENGTH} characters including the trailing {@code FILLER}.
         *
         * @return an unmodifiable view, never {@code null}
         */
        public List<String> writtenRecords() {
            return Collections.unmodifiableList(writtenRecords);
        }

        public void recordWritten(TranRecord record) {
            Objects.requireNonNull(record, "A write hands over a record, never null");
            writtenRecords.add(record.displayImage());
        }
    }
}
