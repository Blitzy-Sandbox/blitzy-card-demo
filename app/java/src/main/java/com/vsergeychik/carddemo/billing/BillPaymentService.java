package com.vsergeychik.carddemo.billing;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.billing.dto.BillPaymentRequest;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse.CursorField;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.TransactionRepository.BrowseDirection;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

@Service
public class BillPaymentService {
    private static final Log LOG = LogFactory.getLog(BillPaymentService.class);

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COBIL00C'} - line 37.
     */
    public static final String WS_PGMNAME = "COBIL00C";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CB00'} - line 38.
     */
    public static final String WS_TRANID = "CB00";

    /**
     * {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} - line 40.
     */
    public static final String WS_TRANSACT_FILE = "TRANSACT";

    /**
     * {@code WS-ACCTDAT-FILE PIC X(08) VALUE 'ACCTDAT '} - line 41.
     */
    public static final String WS_ACCTDAT_FILE = "ACCTDAT ";

    /**
     * {@code WS-CXACAIX-FILE PIC X(08) VALUE 'CXACAIX '} - line 42, padded to eight for the same reason as
     * {@link #WS_ACCTDAT_FILE}.
     */
    public static final String WS_CXACAIX_FILE = "CXACAIX ";

    // Every one is read from a PICTURE clause in the source or a copybook, never chosen, and every move in
    // this class is performed at one of them.

    /**
     * {@code WS-MESSAGE PIC X(80) VALUE SPACES} - line 39.
     */
    public static final int WS_MESSAGE_LENGTH = BillPaymentResponse.WS_MESSAGE_LENGTH;

    /**
     * {@code ERRMSGO PIC X(78)} - the message field of the symbolic map,
     * {@code app/cpy-bms/COBIL00.CPY:78}.
     */
    public static final int ERR_MSG_LENGTH = BillPaymentResponse.ERR_MSG_LENGTH;

    /**
     * {@code ACTIDINI PIC X(11)} - {@code app/cpy-bms/COBIL00.CPY:60}.
     */
    public static final int ACT_ID_IN_LENGTH = BillPaymentResponse.ACT_ID_IN_LENGTH;

    /**
     * {@code CURBALI PIC X(14)} - {@code app/cpy-bms/COBIL00.CPY:66}, an exact fit for the mask.
     */
    public static final int CUR_BAL_LENGTH = BillPaymentResponse.CUR_BAL_LENGTH;

    /**
     * {@code CONFIRMI PIC X(1)} - {@code app/cpy-bms/COBIL00.CPY:72}.
     */
    public static final int CONFIRM_LENGTH = BillPaymentResponse.CONFIRM_LENGTH;

    /**
     * {@code WS-RESP-CD} and {@code WS-REAS-CD} are {@code PIC S9(09) COMP} - lines 46 and 47 - so the
     * {@code DISPLAY} of either shows nine digit positions.
     */
    public static final int WS_RESP_CD_DIGITS = 9;

    /**
     * {@code WS-TRAN-ID-NUM PIC 9(16) VALUE ZEROS} - line 57, the same width as {@code TRAN-ID}.
     */
    public static final int WS_TRAN_ID_NUM_DIGITS = TranRecord.TRAN_ID_LENGTH;

    /**
     * {@code WS-CUR-DATE-X10 PIC X(10) VALUE SPACES} - line 60, the {@code FORMATTIME} date.
     */
    public static final int WS_CUR_DATE_X10_LENGTH = 10;

    /**
     * {@code WS-CUR-TIME-X08 PIC X(08) VALUE SPACES} - line 61, the {@code FORMATTIME} time.
     */
    public static final int WS_CUR_TIME_X08_LENGTH = 8;

    /**
     * {@code 88 ERR-FLG-ON VALUE 'Y'} - line 44.
     */
    public static final String ERR_FLG_ON = "Y";

    /**
     * {@code 88 ERR-FLG-OFF VALUE 'N'} - line 45, and the item's own {@code VALUE}.
     */
    public static final String ERR_FLG_OFF = "N";

    /**
     * {@code 88 USR-MODIFIED-YES VALUE 'Y'} - line 49.
     */
    public static final String USR_MODIFIED_YES = "Y";

    /**
     * {@code 88 USR-MODIFIED-NO VALUE 'N'} - line 50, and the item's own {@code VALUE}.
     */
    public static final String USR_MODIFIED_NO = "N";

    /**
     * {@code 88 CONF-PAY-YES VALUE 'Y'} - line 52.
     */
    public static final String CONF_PAY_YES = "Y";

    /**
     * {@code 88 CONF-PAY-NO VALUE 'N'} - line 53, and the item's own {@code VALUE}.
     */
    public static final String CONF_PAY_NO = "N";

    /**
     * {@code WS-TRAN-AMT PIC +99999999.99} - line 55: a forced sign, eight integer digits, the decimal
     * point and two fraction digits, so twelve characters.
     */
    public static final int WS_TRAN_AMT_LENGTH = 12;

    /**
     * The initial content of {@code WS-TRAN-AMT}.
     */
    public static final String WS_TRAN_AMT_INITIAL = " ".repeat(WS_TRAN_AMT_LENGTH);

    /**
     * {@code WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} - line 58.
     */
    public static final String WS_TRAN_DATE = "00/00/00";

    public static final char CURR_BAL_SIGN_POSITIVE = '+';

    public static final char CURR_BAL_SIGN_NEGATIVE = '-';

    public static final int CURR_BAL_INTEGER_DIGITS = AccountRecord.MONETARY_INTEGER_DIGITS;

    public static final int CURR_BAL_FRACTION_DIGITS = CobolDecimal.MONETARY_SCALE;

    public static final char CURR_BAL_DECIMAL_POINT = '.';

    /**
     * The mask's total width: one sign, ten integer digits, one point and two fraction digits.
     *
     * <p>Exactly {@link #CUR_BAL_LENGTH}, so {@code MOVE WS-CURR-BAL TO CURBALI} at line 194 neither pads
     * nor truncates.
     */
    public static final int WS_CURR_BAL_LENGTH =
            1 + CURR_BAL_INTEGER_DIGITS + 1 + CURR_BAL_FRACTION_DIGITS;

    /**
     * The initial content of {@code WS-CURR-BAL}, which like {@link #WS_TRAN_AMT_INITIAL} has no
     * {@code VALUE} clause.
     */
    public static final String WS_CURR_BAL_INITIAL = " ".repeat(WS_CURR_BAL_LENGTH);

    /**
     * Line 161: {@code MOVE 'Acct ID can NOT be empty...' TO WS-MESSAGE}.
     */
    public static final String MSG_ACCT_ID_EMPTY = "Acct ID can NOT be empty...";

    /**
     * Line 187: {@code MOVE 'Invalid value. Valid values are (Y/N)...' TO WS-MESSAGE}, the
     * {@code WHEN OTHER} arm of the confirmation switch.
     */
    public static final String MSG_INVALID_CONFIRM_VALUE =
            "Invalid value. Valid values are (Y/N)...";

    /**
     * Line 201: {@code MOVE 'You have nothing to pay...' TO WS-MESSAGE}.
     */
    public static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /**
     * Line 237: {@code MOVE 'Confirm to make a bill payment...' TO WS-MESSAGE}.
     */
    public static final String MSG_CONFIRM_TO_PAY = "Confirm to make a bill payment...";

    /**
     * Lines 361, 392 and 425: {@code MOVE 'Account ID NOT found...' TO WS-MESSAGE}.
     */
    public static final String MSG_ACCOUNT_ID_NOT_FOUND = "Account ID NOT found...";

    /**
     * Line 368: the {@code WHEN OTHER} arm of {@code READ-ACCTDAT-FILE}.
     */
    public static final String MSG_UNABLE_TO_LOOKUP_ACCOUNT = "Unable to lookup Account...";

    /**
     * Line 399: the {@code WHEN OTHER} arm of {@code UPDATE-ACCTDAT-FILE}.
     */
    public static final String MSG_UNABLE_TO_UPDATE_ACCOUNT = "Unable to Update Account...";

    /**
     * Line 432: the {@code WHEN OTHER} arm of {@code READ-CXACAIX-FILE}.
     */
    public static final String MSG_UNABLE_TO_LOOKUP_XREF_AIX = "Unable to lookup XREF AIX file...";

    /**
     * Line 456: the {@code NOTFND} arm of {@code STARTBR-TRANSACT-FILE}.
     */
    public static final String MSG_TRANSACTION_ID_NOT_FOUND = "Transaction ID NOT found...";

    /**
     * Lines 463 and 492: the {@code WHEN OTHER} arms of {@code STARTBR-TRANSACT-FILE} and
     * {@code READPREV-TRANSACT-FILE}, which share one text.
     */
    public static final String MSG_UNABLE_TO_LOOKUP_TRANSACTION = "Unable to lookup Transaction...";

    /**
     * Line 536: the shared {@code DUPKEY}/{@code DUPREC} arm of {@code WRITE-TRANSACT-FILE}.
     */
    public static final String MSG_TRAN_ID_ALREADY_EXIST = "Tran ID already exist...";

    /**
     * Line 543: the {@code WHEN OTHER} arm of {@code WRITE-TRANSACT-FILE}.
     */
    public static final String MSG_UNABLE_TO_ADD_TRANSACTION =
            "Unable to Add Bill pay Transaction...";

    public static final String DISPLAY_RESP_PREFIX = "RESP:";

    public static final String DISPLAY_REAS_PREFIX = "REAS:";

    public static final String SUCCESS_PREFIX = "Payment successful. ";

    public static final String SUCCESS_INFIX = " Your Transaction ID is ";

    public static final String SUCCESS_SUFFIX = ".";

    /**
     * Line 220: {@code MOVE '02' TO TRAN-TYPE-CD}.
     */
    public static final String TRAN_TYPE_CD_BILL_PAYMENT = "02";

    /**
     * Line 221: {@code MOVE 2 TO TRAN-CAT-CD}.
     */
    public static final int TRAN_CAT_CD_BILL_PAYMENT = 2;

    /**
     * Line 222: {@code MOVE 'POS TERM' TO TRAN-SOURCE}.
     */
    public static final String TRAN_SOURCE_POS_TERM = "POS TERM";

    /**
     * Line 223: {@code MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC}.
     */
    public static final String TRAN_DESC_BILL_PAYMENT_ONLINE = "BILL PAYMENT - ONLINE";

    /**
     * Line 226: {@code MOVE 999999999 TO TRAN-MERCHANT-ID}.
     */
    public static final long TRAN_MERCHANT_ID_BILL_PAYMENT = 999999999L;

    /**
     * Line 227: {@code MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME}, into {@code PIC X(50)}.
     */
    public static final String TRAN_MERCHANT_NAME_BILL_PAYMENT = "BILL PAYMENT";

    /**
     * Lines 228 and 229: {@code MOVE 'N/A'} into {@code TRAN-MERCHANT-CITY PIC X(50)} and into
     * {@code TRAN-MERCHANT-ZIP PIC X(10)}.
     */
    public static final String TRAN_MERCHANT_NOT_APPLICABLE = "N/A";

    /**
     * The outcome a successful {@code STARTBR} reports, named for the arm it selects.
     */
    public static final Outcome STARTBR_SUCCESSFUL_OUTCOME = Outcome.OK;

    /**
     * The single character COBOL {@code HIGH-VALUES} stands for: {@code X'FF'}, the highest byte value.
     */
    public static final char HIGH_VALUES = '\u00ff';

    /**
     * The single character COBOL {@code LOW-VALUES} stands for: {@code X'00'}.
     */
    public static final char LOW_VALUES = '\u0000';

    /**
     * {@code MOVE HIGH-VALUES TO TRAN-ID} - line 212, the record identification field the {@code STARTBR}
     * at line 445 positions on.
     */
    public static final String HIGH_VALUES_TRAN_ID =
            String.valueOf(HIGH_VALUES).repeat(TranRecord.TRAN_ID_LENGTH);

    /**
     * {@code MOVE ZEROS TO TRAN-ID} - line 488, the {@code ENDFILE} arm of {@code READPREV}.
     */
    public static final String ZEROS_TRAN_ID = "0".repeat(TranRecord.TRAN_ID_LENGTH);

    /**
     * Milliseconds from {@code 1900-01-01T00:00:00Z} to the Java epoch, {@code 1970-01-01T00:00:00Z}.
     */
    public static final long CICS_ABSTIME_EPOCH_OFFSET_MILLIS = 25_567L * 86_400L * 1_000L;

    /**
     * {@code MOVE DFHGREEN TO ERRMSGC OF COBIL0AO} - line 526, on the successful-{@code WRITE} arm.
     */
    public static final String MESSAGE_HIGHLIGHT_GREEN =
            Character.toString(BmsAttributes.unsigned(BmsAttributes.DFHGREEN));

    private static final String UNIT_OF_WORK_DESCRIPTION =
            "PROCESS-ENTER-KEY (app/cbl/COBIL00C.cbl:154-244): read " + WS_ACCTDAT_FILE.trim()
                    + " for update, read " + WS_CXACAIX_FILE.trim() + ", browse and add to "
                    + WS_TRANSACT_FILE + ", then rewrite the account - one CICS task, one syncpoint";

    private final AccountRepository accountRepository;

    private final CardXrefRepository cardXrefRepository;

    private final TransactionRepository transactionRepository;

    private final Clock clock;

    private final DatasetUnitOfWork unitOfWork;

    private final FixedWidthCodec codec;

    public BillPaymentService(AccountRepository accountRepository,
                             CardXrefRepository cardXrefRepository,
                             TransactionRepository transactionRepository,
                             Clock clock,
                             DatasetUnitOfWork unitOfWork) {
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "An AccountRepository is required: app/cbl/COBIL00C.cbl:345-354 reads " + "the "
                        + WS_ACCTDAT_FILE.trim() + " file for update and :379-385 rewrites it, and the "
                        + "balance the payment debits comes from and returns to that record");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository,
                "A CardXrefRepository is required: app/cbl/COBIL00C.cbl:410-418 reads the "
                        + WS_CXACAIX_FILE.trim() + " alternate index by account id to obtain the card "
                        + "number the transaction record carries");
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "A TransactionRepository is required: app/cbl/COBIL00C.cbl:443-482 browses the "
                        + WS_TRANSACT_FILE + " master backwards for the highest identifier and :512-520 "
                        + "adds the payment transaction under the next one");
        this.clock = Objects.requireNonNull(clock, "A Clock is required: EXEC CICS ASKTIME is read from "
                + "it at app/cbl/COBIL00C.cbl:251-253 and never from the wall clock, so a parity case "
                + "can pin the instant that lands in TRAN-ORIG-TS and TRAN-PROC-TS");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "A unit of work is required: "
                + "app/cbl/COBIL00C.cbl:351 states the UPDATE option, so the read takes a record lock, "
                + "and a lock outside a unit of work is released before the task that asked for it can "
                + "rely on it - which is why AccountRepository refuses to issue one there");

        Charset datasetCharset = accountRepository.datasetCharset();
        if (datasetCharset == null) {
            throw new IllegalStateException("The account repository reported no dataset code page. "
                    + "Every image this program renders - the fourteen-character balance mask, the "
                    + "eighty-byte message, the 350-byte transaction record - is fixed-width, so the "
                    + "code page is always stated explicitly by configuration and is never taken from "
                    + "the platform default");
        }
        this.codec = new FixedWidthCodec(datasetCharset);
    }

    /**
     * The code page every image this service renders is encoded in, and the move rules that go with it.
     *
     * @return the codec built at construction from {@link AccountRepository#datasetCharset()}; never
     *     {@code null} and never replaced
     */
    public FixedWidthCodec codec() {
        return codec;
    }

    /**
     * {@code PROCESS-ENTER-KEY} driven from a bound request: the form the controller calls.
     *
     * <p>The remaining seven payload members are header fields that {@code POPULATE-HEADER-INFO} writes
     * rather than reads, and the paragraph never consults them.
     *
     * @param request the bound screen; must not be {@code null}
     * @return the execution's working storage, complete: the flags, the message, the screen fields, the
     *     records touched, every screen the paragraph sent and every diagnostic it wrote
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public PaymentState processEnterKey(BillPaymentRequest request) {
        Objects.requireNonNull(request, "A bound screen is required: PROCESS-ENTER-KEY reads ACTIDINI "
                + "at app/cbl/COBIL00C.cbl:159 and CONFIRMI at :173, and returns the communication area "
                + "the client carries into the next call");
        return processEnterKey(request.getActIdIn(), request.getConfirm(),
                request.getNavigationContext());
    }

    /**
     * {@code PROCESS-ENTER-KEY} driven from its three inputs: the form a parity case calls.
     *
     * @param actIdIn {@code ACTIDINI OF COBIL0AI}, {@code PIC X(11)}
     * @param confirm {@code CONFIRMI OF COBIL0AI}, {@code PIC X(1)}
     * @param commarea {@code CARDDEMO-COMMAREA}
     * @return the execution's working storage; never {@code null}
     */
    public PaymentState processEnterKey(String actIdIn, String confirm, NavigationContext commarea) {
        PaymentState state = new PaymentState(codec,
                commarea == null ? NavigationContext.empty() : commarea);
        String actIdInField = materialise(actIdIn, ACT_ID_IN_LENGTH);
        String confirmField = materialise(confirm, CONFIRM_LENGTH);
        state.setActIdIn(actIdInField);
        state.setConfirm(confirmField);

        return unitOfWork.execute(UNIT_OF_WORK_DESCRIPTION, () -> {
            processEnterKeyInTask(state, actIdInField, confirmField);
            return state;
        });
    }

    private void processEnterKeyInTask(PaymentState state, String actIdInField, String confirmField) {
        state.setConfPayNo();

        if (isSpacesOrLowValues(actIdInField)) {
            state.setErrFlagOn();
            state.setMessage(MSG_ACCT_ID_EMPTY);
            state.setCursorField(CursorField.ACTIDIN);
            sendBillpayScreen(state);
        } else {
            state.setAcctIdCheckContinued();
        }

        if (!state.isErrFlagOn()) {
            String numericKey = moveScreenIdToNumericField(actIdInField, ACT_ID_IN_LENGTH);
            state.setAcctIdRidfld(numericKey);
            state.setXrefAcctIdRidfld(numericKey);

            evaluateConfirmation(state, confirmField);

            // On the 'N' arm no account was ever read, so the balance edited here is the initial content of
            // the WORKING-STORAGE record area - and the screen has already been sent, so the edited value
            // lands in the field of a screen the user will never see with it.
            state.setCurrBalEdited(editCurrBal(state.accountRecord().getAcctCurrBal()));
            state.setCurBal(codec.movePicX(state.currBalEdited(), CUR_BAL_LENGTH));
        }

        if (!state.isErrFlagOn()) {
            if (isNothingToPay(state.accountRecord().getAcctCurrBal(), actIdInField)) {
                state.setErrFlagOn();
                state.setMessage(MSG_NOTHING_TO_PAY);
                state.setCursorField(CursorField.ACTIDIN);
                sendBillpayScreen(state);
            }
        }

        if (!state.isErrFlagOn()) {
            if (state.isConfPayYes()) {
                makeBillPayment(state);
            } else {
                state.setMessage(MSG_CONFIRM_TO_PAY);
                state.setCursorField(CursorField.CONFIRM);
            }

            sendBillpayScreen(state);
        }
    }

    /**
     * The compound condition at lines 198-199:
     * {@code IF ACCT-CURR-BAL <= ZEROS AND ACTIDINI OF COBIL0AI NOT = SPACES AND LOW-VALUES}.
     *
     * <p>The condition is nonetheless a conjunction in the source, so it is reproduced as one and made
     * reachable in all four combinations - otherwise half of it could never be shown to behave as the COBOL
     * does.
     *
     * @param balance {@code ACCT-CURR-BAL} as the working record holds it; must not be {@code null}
     * @param actIdIn {@code ACTIDINI} at its declared width; must not be {@code null}
     * @return {@code true} when the balance is zero or below and an identifier was supplied
     * @throws NullPointerException if either argument is {@code null}
     */
    public boolean isNothingToPay(BigDecimal balance, String actIdIn) {
        Objects.requireNonNull(balance, "A balance is required: app/cbl/COBIL00C.cbl:198 compares "
                + "ACCT-CURR-BAL against ZEROS, and a numeric field has no null");
        Objects.requireNonNull(actIdIn, "An account identifier field is required: "
                + "app/cbl/COBIL00C.cbl:199 compares ACTIDINI against SPACES and LOW-VALUES");
        return balance.signum() <= 0 && !isSpacesOrLowValues(actIdIn);
    }

    private void evaluateConfirmation(PaymentState state, String confirmField) {
        if (CONF_PAY_YES.equals(confirmField) || "y".equals(confirmField)) {
            state.setConfPayYes();
            readAcctdatFile(state);
        } else if ("N".equals(confirmField) || "n".equals(confirmField)) {
            clearCurrentScreen(state);
            state.setErrFlagOn();
        } else if (isSpacesOrLowValues(confirmField)) {
            readAcctdatFile(state);
        } else {
            state.setErrFlagOn();
            state.setMessage(MSG_INVALID_CONFIRM_VALUE);
            state.setCursorField(CursorField.CONFIRM);
            sendBillpayScreen(state);
        }
    }

    private void makeBillPayment(PaymentState state) {
        readCxacaixFile(state);

        state.setTranIdRidfld(HIGH_VALUES_TRAN_ID);

        // :213 PERFORM STARTBR-TRANSACT-FILE try-with-resources closes the handle even if a later step
        // raises, which is the ENDBR the COBOL performs unconditionally at :215 in a program that cannot
        // raise.
        try (TransactionRepository.Browse browse =
                     transactionRepository.startBrowse(BrowseDirection.BACKWARD)) {
            // The real STARTBR outcome, not an assumption: NOTFND and WHEN OTHER are both reachable, and
            // both leave TRAN-ID holding the HIGH-VALUES of :212 - which is what makes :216 a data
            // exception, exactly as it is on the mainframe.
            startbrTransactFile(state, browse.positioningOutcome());

            readprevTransactFile(state, browse.readPrev());

            endbrTransactFile(browse);
        }

        String tranIdImage = codec.movePic9(state.tranIdRidfld(), WS_TRAN_ID_NUM_DIGITS);
        state.setTranIdNum(codec.decodePic9(tranIdImage));

        // A PIC 9(16) receiver has no ON SIZE ERROR clause here, so an increment past sixteen nines
        // discards the high-order digit and wraps to zeros - which is exactly what the codec's numeric move
        // does, and why the sum is put back through it rather than kept as a long.
        long incremented = state.tranIdNum() + 1L;
        String nextTranId = codec.movePic9(incremented, WS_TRAN_ID_NUM_DIGITS);
        state.setTranIdNum(codec.decodePic9(nextTranId));

        TranRecord tranRecord = new TranRecord(codec.charset());
        state.setTranRecord(tranRecord);

        tranRecord.moveTranId(nextTranId);
        tranRecord.moveTranTypeCd(TRAN_TYPE_CD_BILL_PAYMENT);
        tranRecord.moveTranCatCd(TRAN_CAT_CD_BILL_PAYMENT);
        tranRecord.moveTranSource(TRAN_SOURCE_POS_TERM);
        tranRecord.moveTranDesc(TRAN_DESC_BILL_PAYMENT_ONLINE);

        // :224 MOVE ACCT-CURR-BAL TO TRAN-AMT THE HEADLINE PARITY TRAP.
        BigDecimal balanceBeforePayment = state.accountRecord().getAcctCurrBal();
        BigDecimal storedTranAmt = CobolDecimal.storeAtPicture(balanceBeforePayment,
                TranRecord.TRAN_AMT_INTEGER_DIGITS, TranRecord.TRAN_AMT_SCALE);
        tranRecord.moveTranAmt(storedTranAmt);

        // :225 MOVE XREF-CARD-NUM TO TRAN-CARD-NUM - X(16) to X(16), neither padded nor truncated.
        tranRecord.moveTranCardNum(state.xrefCardNum());

        tranRecord.moveTranMerchantId(TRAN_MERCHANT_ID_BILL_PAYMENT);
        tranRecord.moveTranMerchantName(TRAN_MERCHANT_NAME_BILL_PAYMENT);
        tranRecord.moveTranMerchantCity(TRAN_MERCHANT_NOT_APPLICABLE);
        tranRecord.moveTranMerchantZip(TRAN_MERCHANT_NOT_APPLICABLE);

        String timestamp = getCurrentTimestamp(state);

        tranRecord.moveTranOrigTs(timestamp);
        tranRecord.moveTranProcTs(timestamp);

        writeTransactFile(state);

        BigDecimal debitedBalance = CobolDecimal.subtract(balanceBeforePayment,
                tranRecord.tranAmt(), CobolDecimal.MONETARY_SCALE);
        state.accountRecord().setAcctCurrBal(CobolDecimal.storeAtPicture(debitedBalance,
                AccountRecord.MONETARY_INTEGER_DIGITS, CobolDecimal.MONETARY_SCALE));

        updateAcctdatFile(state);
    }

    /**
     * {@code GET-CURRENT-TIMESTAMP}: composes the {@code WS-TIMESTAMP} image the transaction record's two
     * timestamp fields receive.
     *
     * <p>The offset from Greenwich is not carried into the header, because it affects only the five
     * characters {@code FUNCTION CURRENT-DATE} appends and this program never reads that view from this
     * header: the screen's own date and time come from {@code POPULATE-HEADER-INFO}, which is the
     * controller's.
     *
     * @param state the execution's working storage; receives {@code WS-ABS-TIME}, {@code WS-CUR-DATE-X10},
     *     {@code WS-CUR-TIME-X08} and {@code WS-TIMESTAMP}
     * @return the twenty-six character image, {@code YYYY-MM-DD HH:MM:SS.000000}; never {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public String getCurrentTimestamp(PaymentState state) {
        requireState(state);

        Instant instant = clock.instant();
        state.setAbsTime(instant.toEpochMilli() + CICS_ABSTIME_EPOCH_OFFSET_MILLIS);

        DateHeader header = DateHeader.of(codec, LocalDateTime.ofInstant(instant, clock.getZone()));
        DateHeader.CapturedDateTime captured = header.captured();

        String date10 = codec.movePic9(captured.year(), DateHeader.YEAR_DIGITS)
                + DateHeader.TIMESTAMP_DATE_SEPARATOR
                + codec.movePic9(captured.month(), DateHeader.MONTH_DIGITS)
                + DateHeader.TIMESTAMP_DATE_SEPARATOR
                + codec.movePic9(captured.day(), DateHeader.DAY_DIGITS);
        String time08 = codec.movePic9(captured.hours(), DateHeader.HOURS_DIGITS)
                + DateHeader.TIME_SEPARATOR
                + codec.movePic9(captured.minutes(), DateHeader.MINUTE_DIGITS)
                + DateHeader.TIME_SEPARATOR
                + codec.movePic9(captured.seconds(), DateHeader.SECOND_DIGITS);
        state.setCurDateX10(codec.movePicX(date10, WS_CUR_DATE_X10_LENGTH));
        state.setCurTimeX08(codec.movePicX(time08, WS_CUR_TIME_X08_LENGTH));

        String timestamp = header
                .withTimestampFromFormatTime(state.curDateX10(), state.curTimeX08())
                .wsTimestamp();
        state.setTimestamp(timestamp);
        return timestamp;
    }

    // Each is an EXEC CICS command followed by an ordered EVALUATE WS-RESP-CD, and the ordering is the
    // contract: the first matching WHEN wins and WHEN OTHER is last. The repositories surface outcomes and
    // never abend on a caller's behalf, so the whole guard chain lives here where the COBOL puts it.

    /**
     * {@code READ-ACCTDAT-FILE} - lines 343 to 372.
     *
     * @param state the execution's working storage; the record identification field must already be set
     *     from the two-receiver {@code MOVE} at lines 170-171
     * @throws NullPointerException if {@code state} is {@code null}
     * @throws IllegalStateException if no unit of work is open, since a locking read outside one would hold
     *     nothing
     */
    public void readAcctdatFile(PaymentState state) {
        requireState(state);
        AccountRepository.ReadResult result = accountRepository.readForUpdate(state.acctIdRidfld());
        state.setResponseCodes(result.cicsResp().orElse(FileStatus.RESP_NOT_REPORTED),
                result.cicsResp2());

        if (result.isFound()) {
            state.setAccountRecord(result.account().orElseThrow(
                    () -> new IllegalStateException("A successful read of " + WS_ACCTDAT_FILE.trim()
                            + " carries the decoded record; AccountRepository.ReadResult enforces that "
                            + "invariant at construction, so an empty record here would mean the "
                            + "repository's own contract had been broken")));
            return;
        }
        if (result.isNotFound()) {
            rejectAndSend(state, MSG_ACCOUNT_ID_NOT_FOUND, CursorField.ACTIDIN);
            return;
        }
        display(state);
        rejectAndSend(state, MSG_UNABLE_TO_LOOKUP_ACCOUNT, CursorField.ACTIDIN);
    }

    /**
     * {@code UPDATE-ACCTDAT-FILE} - lines 377 to 403.
     *
     * @param state the execution's working storage, carrying the record to write
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void updateAcctdatFile(PaymentState state) {
        requireState(state);
        AccountRepository.WriteResult result = accountRepository.rewrite(state.accountRecord());
        state.setResponseCodes(result.cicsResp().orElse(FileStatus.RESP_NOT_REPORTED),
                result.cicsResp2());

        if (result.isWritten()) {
            state.setAccountRewritten();
            return;
        }
        if (result.isNotFound()) {
            rejectAndSend(state, MSG_ACCOUNT_ID_NOT_FOUND, CursorField.ACTIDIN);
            return;
        }
        display(state);
        rejectAndSend(state, MSG_UNABLE_TO_UPDATE_ACCOUNT, CursorField.ACTIDIN);
    }

    /**
     * {@code READ-CXACAIX-FILE} - lines 408 to 436.
     *
     * @param state the execution's working storage; the alternate-index key must already be set from the
     *     two-receiver {@code MOVE} at lines 170-171
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void readCxacaixFile(PaymentState state) {
        requireState(state);
        CardXrefRepository.ReadResult result =
                cardXrefRepository.readByAccountIdViaAltIndex(state.xrefAcctIdRidfld());
        state.setResponseCodes(result.cicsResp(), result.cicsResp2());

        if (result.isFound()) {
            state.setCardXrefRecord(result.record().orElseThrow(
                    () -> new IllegalStateException("A successful read of " + WS_CXACAIX_FILE.trim()
                            + " carries the decoded record; CardXrefRepository.ReadResult enforces "
                            + "that invariant at construction, so an empty record here would mean the "
                            + "repository's own contract had been broken")));
            return;
        }
        if (result.isNotFound()) {
            rejectAndSend(state, MSG_ACCOUNT_ID_NOT_FOUND, CursorField.ACTIDIN);
            return;
        }
        display(state);
        rejectAndSend(state, MSG_UNABLE_TO_LOOKUP_XREF_AIX, CursorField.ACTIDIN);
    }

    /**
     * {@code STARTBR-TRANSACT-FILE} - lines 441 to 467: the ordered {@code EVALUATE} that follows the
     * browse position.
     *
     * @param state the execution's working storage
     * @param positioningOutcome the outcome CICS reported for the position
     * @throws NullPointerException if {@code state} or {@code positioningOutcome} is {@code null}
     */
    public void startbrTransactFile(PaymentState state, Outcome positioningOutcome) {
        requireState(state);
        Objects.requireNonNull(positioningOutcome, "A positioning outcome is required: "
                + "app/cbl/COBIL00C.cbl:451-467 evaluates WS-RESP-CD after the STARTBR, and this method "
                + "is that EVALUATE");

        if (positioningOutcome == Outcome.OK) {
            state.setBrowseStarted();
            return;
        }
        if (positioningOutcome == Outcome.NOT_FOUND) {
            rejectAndSend(state, MSG_TRANSACTION_ID_NOT_FOUND, CursorField.ACTIDIN);
            return;
        }
        display(state);
        rejectAndSend(state, MSG_UNABLE_TO_LOOKUP_TRANSACTION, CursorField.ACTIDIN);
    }

    /**
     * {@code READPREV-TRANSACT-FILE} - lines 472 to 496: the ordered {@code EVALUATE} that follows the
     * backward read.
     *
     * @param state the execution's working storage
     * @param result the outcome the backward read reported
     * @throws NullPointerException if {@code state} or {@code result} is {@code null}
     */
    public void readprevTransactFile(PaymentState state, TransactionRepository.ReadResult result) {
        requireState(state);
        Objects.requireNonNull(result, "A read outcome is required: app/cbl/COBIL00C.cbl:484-496 "
                + "evaluates WS-RESP-CD after the READPREV, and this method is that EVALUATE");
        state.setResponseCodes(result.cicsResp().orElse(FileStatus.RESP_NOT_REPORTED),
                result.cicsResp2());

        if (result.isFound()) {
            TranRecord highest = result.record().orElseThrow(
                    () -> new IllegalStateException("A successful read of " + WS_TRANSACT_FILE
                            + " carries the decoded record; TransactionRepository.ReadResult enforces "
                            + "that invariant at construction, so an empty record here would mean the "
                            + "repository's own contract had been broken"));
            state.setTranRecord(highest);
            state.setTranIdRidfld(highest.tranId());
            return;
        }
        if (result.isEndOfFile()) {
            state.setTranIdRidfld(ZEROS_TRAN_ID);
            return;
        }
        display(state);
        rejectAndSend(state, MSG_UNABLE_TO_LOOKUP_TRANSACTION, CursorField.ACTIDIN);
    }

    /**
     * {@code ENDBR-TRANSACT-FILE} - lines 501 to 505.
     *
     * @param browse the browse handle to end; must not be {@code null}
     * @throws NullPointerException if {@code browse} is {@code null}
     */
    public void endbrTransactFile(TransactionRepository.Browse browse) {
        Objects.requireNonNull(browse, "A browse handle is required to end a browse: "
                + "app/cbl/COBIL00C.cbl:503-505 names the dataset whose browse is being ended");
        browse.endBrowse();
    }

    /**
     * {@code WRITE-TRANSACT-FILE} - lines 510 to 547, and the only arm of the program that reports success.
     *
     * @param state the execution's working storage, carrying the record to add
     * @throws NullPointerException if {@code state} is {@code null}
     * @throws IllegalStateException if no transaction record has been assembled, which cannot happen on the
     *     program's own path
     */
    public void writeTransactFile(PaymentState state) {
        requireState(state);
        TranRecord record = state.tranRecord().orElseThrow(() -> new IllegalStateException(
                "A transaction record must be assembled before it can be added: "
                        + "app/cbl/COBIL00C.cbl:218-232 builds TRAN-RECORD and :512-520 writes it, so "
                        + "reaching the write with no record would mean the payment sequence had been "
                        + "entered other than at its first statement"));
        TransactionRepository.WriteResult result = transactionRepository.write(record);
        state.setResponseCodes(result.cicsResp().orElse(FileStatus.RESP_NOT_REPORTED),
                result.cicsResp2());

        if (result.isWritten()) {
            initializeAllFields(state);
            state.setMessageSpaces();
            state.setMessageHighlight(MESSAGE_HIGHLIGHT_GREEN);
            state.setMessage(successMessage(record.tranId()));
            sendBillpayScreen(state);
            return;
        }
        if (result.isDuplicate()) {
            rejectAndSend(state, MSG_TRAN_ID_ALREADY_EXIST, CursorField.ACTIDIN);
            return;
        }
        display(state);
        rejectAndSend(state, MSG_UNABLE_TO_ADD_TRANSACTION, CursorField.ACTIDIN);
    }

    private String successMessage(String tranId) {
        Objects.requireNonNull(tranId, "The identifier of the transaction just written is required: "
                + "app/cbl/COBIL00C.cbl:529 strings TRAN-ID into the confirmation");
        return codec.concatenateDelimitedBySize(SUCCESS_PREFIX, SUCCESS_INFIX,
                delimitedBySpace(tranId), SUCCESS_SUFFIX);
    }

    static String delimitedBySpace(String operand) {
        int firstSpace = operand.indexOf(' ');
        return firstSpace < 0 ? operand : operand.substring(0, firstSpace);
    }

    /**
     * {@code SEND-BILLPAY-SCREEN} - lines 289 to 301.
     *
     * <p>{@code WS-MESSAGE} is {@code PIC X(80)} and {@code ERRMSGO} is {@code PIC X(78)}, and a
     * {@code PIC X} receiver is filled from its leftmost position with the overflow discarded - so the
     * surviving characters are the leading 78 and never the trailing 78.
     *
     * @param state the execution's working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void sendBillpayScreen(PaymentState state) {
        requireState(state);
        // :293 MOVE WS-MESSAGE TO ERRMSGO OF COBIL0AO - 80 into 78, truncating on the right.
        state.setErrMsg(codec.movePicX(state.message(), ERR_MSG_LENGTH));
        state.recordSend();
    }

    /**
     * {@code CLEAR-CURRENT-SCREEN} - lines 552 to 555: {@code PERFORM INITIALIZE-ALL-FIELDS} then
     * {@code PERFORM SEND-BILLPAY-SCREEN}.
     *
     * @param state the execution's working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void clearCurrentScreen(PaymentState state) {
        requireState(state);
        initializeAllFields(state);
        sendBillpayScreen(state);
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS} - lines 560 to 566.
     *
     * @param state the execution's working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void initializeAllFields(PaymentState state) {
        requireState(state);
        state.setCursorField(CursorField.ACTIDIN);
        state.setActIdIn(spaces(ACT_ID_IN_LENGTH));
        state.setCurBal(spaces(CUR_BAL_LENGTH));
        state.setConfirm(spaces(CONFIRM_LENGTH));
        state.setMessageSpaces();
    }

    /**
     * The {@code WHEN OTHER} arm of the key switch - lines 138 to 141.
     *
     * @param state the execution's working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void invalidKeyPressed(PaymentState state) {
        requireState(state);
        state.setErrFlagOn();
        state.setMessage(SystemMessages.CCDA_MSG_INVALID_KEY);
        sendBillpayScreen(state);
    }

    private void rejectAndSend(PaymentState state, String message, CursorField cursor) {
        state.setErrFlagOn();
        state.setMessage(message);
        state.setCursorField(cursor);
        sendBillpayScreen(state);
    }

    // Every one of them is a COBOL rule expressed once, so that no call site has to remember which end a
    // receiver truncates.

    /**
     * {@code MOVE ACCT-CURR-BAL TO WS-CURR-BAL} - line 193: renders a balance through the
     * {@code PIC +9999999999.99} numeric-edited picture.
     *
     * <p>The receiver of the ten integer positions is the same width as {@code ACCT-CURR-BAL}'s own
     * picture, so a stored balance always fits.
     *
     * @param balance the value to edit; must not be {@code null}
     * @return exactly {@link #WS_CURR_BAL_LENGTH} characters, which is exactly {@link #CUR_BAL_LENGTH} - an
     *     exact fit for the screen field
     * @throws NullPointerException if {@code balance} is {@code null}
     */
    public String editCurrBal(BigDecimal balance) {
        Objects.requireNonNull(balance, "A balance is required to edit: app/cbl/COBIL00C.cbl:193 moves "
                + "ACCT-CURR-BAL into the PIC +9999999999.99 item, and a numeric field has no null");

        BigDecimal stored = CobolDecimal.storeAtPicture(balance, CURR_BAL_INTEGER_DIGITS,
                CURR_BAL_FRACTION_DIGITS);
        char sign = stored.signum() < 0 ? CURR_BAL_SIGN_NEGATIVE : CURR_BAL_SIGN_POSITIVE;

        String digits = codec.movePic9(stored.abs().unscaledValue().toString(),
                CURR_BAL_INTEGER_DIGITS + CURR_BAL_FRACTION_DIGITS);

        return sign + digits.substring(0, CURR_BAL_INTEGER_DIGITS)
                + CURR_BAL_DECIMAL_POINT + digits.substring(CURR_BAL_INTEGER_DIGITS);
    }

    private String materialise(String value, int width) {
        return value == null ? lowValues(width) : codec.movePicX(value, width);
    }

    private static boolean isSpacesOrLowValues(String field) {
        boolean allSpaces = true;
        boolean allLowValues = true;
        for (int position = 0; position < field.length(); position++) {
            char character = field.charAt(position);
            if (character != ' ') {
                allSpaces = false;
            }
            if (character != LOW_VALUES) {
                allLowValues = false;
            }
        }
        return allSpaces || allLowValues;
    }

    private String moveScreenIdToNumericField(String field, int width) {
        return isAllDigits(field) ? codec.movePic9(field, width) : field;
    }

    static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int position = 0; position < value.length(); position++) {
            char character = value.charAt(position);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private void display(PaymentState state) {
        String text = DISPLAY_RESP_PREFIX + respImage(state.respCd())
                + DISPLAY_REAS_PREFIX + respImage(state.reasCd());
        state.recordDisplay(text);
        LOG.info(text);
    }

    /**
     * A {@code PIC S9(09) COMP} response or reason code as {@code DISPLAY} renders it - or, where the
     * operation reported none, an image of the same width that cannot be mistaken for a code.
     *
     * @param code the value held in {@code WS-RESP-CD} or {@code WS-REAS-CD}
     * @return exactly {@link #WS_RESP_CD_DIGITS} characters
     */
    private String respImage(int code) {
        return FileStatus.respReported(code)
                ? codec.movePic9(code, WS_RESP_CD_DIGITS)
                : FileStatus.respNotReportedImage(WS_RESP_CD_DIGITS);
    }

    private static void requireState(PaymentState state) {
        Objects.requireNonNull(state, "A PaymentState is required: every WORKING-STORAGE item of "
                + WS_PGMNAME + " lives in it, so that two concurrent requests cannot see each other's "
                + "screen");
    }

    private static String spaces(int length) {
        return " ".repeat(length);
    }

    private static String lowValues(int length) {
        // One implementation of the LOW-VALUES image, in common.ScreenFieldImage, so the choice cannot
        // drift back apart across screens. Any width validation above is this method's own contract.
        return ScreenFieldImage.unpainted(length);
    }

    /**
     * A snapshot of everything
     * {@code EXEC CICS SEND MAP('COBIL0A') MAPSET('COBIL00') FROM(COBIL0AO) ERASE CURSOR} transmitted,
     * taken at the moment it was issued.
     *
     * @param ordinal which send this was, counting from one
     * @param errMsg {@code ERRMSGO PIC X(78)} as transmitted - {@code WS-MESSAGE} after the two-byte right
     *     truncation of line 293
     * @param actIdIn {@code ACTIDINI PIC X(11)} as transmitted
     * @param curBal {@code CURBALI PIC X(14)} as transmitted
     * @param confirm {@code CONFIRMI PIC X(1)} as transmitted
     * @param cursorField which field the {@code CURSOR} option placed the cursor in
     * @param messageHighlight the message line's colour override, or {@code null} where none applied and
     *     the mapset's declared red stands
     */
    public record SentScreen(int ordinal,
                             String errMsg,
                             String actIdIn,
                             String curBal,
                             String confirm,
                             CursorField cursorField,
                             String messageHighlight) {
    }

    /**
     * One execution's working storage: every item {@code COBIL00C} declares, plus the observable effects
     * that have no home in the ten-field payload.
     */
    public static final class PaymentState {
        private final FixedWidthCodec codec;

        private final NavigationContext commarea;

        private final String tranAmtEdited = WS_TRAN_AMT_INITIAL;

        private final String tranDate = WS_TRAN_DATE;

        private String message;

        private String errFlg = ERR_FLG_OFF;

        private String usrModified = USR_MODIFIED_NO;

        private String confPayFlg = CONF_PAY_NO;

        private int respCd = FileStatus.NORMAL;

        private int reasCd = FileStatus.NO_REASON_CODE;

        private String currBalEdited = WS_CURR_BAL_INITIAL;

        private long tranIdNum;

        private long absTime;

        private String curDateX10;

        private String curTimeX08;

        private String timestamp;

        private AccountRecord accountRecord;

        private CardXrefRecord cardXrefRecord;

        private TranRecord tranRecord;

        private String acctIdRidfld;

        private String xrefAcctIdRidfld;

        private String tranIdRidfld;

        private String actIdIn;

        private String curBal;

        private String confirm;

        private String errMsg;

        private CursorField cursorField = CursorField.NONE;

        private String messageHighlight;

        private boolean acctIdCheckContinued;

        private boolean browseStarted;

        private boolean accountRewritten;

        private final List<SentScreen> sentScreens = new ArrayList<>();

        private final List<String> displays = new ArrayList<>();

        /**
         * Creates the working storage in the state the {@code VALUE} clauses describe, with the three
         * record areas in the state COBOL leaves an item that has none.
         *
         * @param codec the move rules and code page for this execution's images; must not be {@code null}
         * @param commarea the communication area the caller received; must not be {@code null}
         * @throws NullPointerException if either argument is {@code null}
         */
        public PaymentState(FixedWidthCodec codec, NavigationContext commarea) {
            this.codec = Objects.requireNonNull(codec, "A FixedWidthCodec is required: every item held "
                    + "here is kept at its declared PICTURE width, and the code page of a fixed-width "
                    + "image is always stated explicitly");
            this.commarea = Objects.requireNonNull(commarea, "A communication area is required; pass "
                    + "NavigationContext.empty() for the state a first entry produces");
            this.message = spaces(WS_MESSAGE_LENGTH);
            this.actIdIn = spaces(ACT_ID_IN_LENGTH);
            this.curBal = spaces(CUR_BAL_LENGTH);
            this.confirm = spaces(CONFIRM_LENGTH);
            this.errMsg = spaces(ERR_MSG_LENGTH);
            this.curDateX10 = spaces(WS_CUR_DATE_X10_LENGTH);
            this.curTimeX08 = spaces(WS_CUR_TIME_X08_LENGTH);
            this.timestamp = spaces(DateHeader.WS_TIMESTAMP_LENGTH);
            this.acctIdRidfld = spaces(ACT_ID_IN_LENGTH);
            this.xrefAcctIdRidfld = spaces(ACT_ID_IN_LENGTH);
            this.tranIdRidfld = spaces(TranRecord.TRAN_ID_LENGTH);
            this.accountRecord = new AccountRecord(codec.charset());
        }

        /**
         * {@code IF ERR-FLG-ON} - lines 169, 197 and 208 all test the negation of this.
         *
         * @return {@code true} when {@code WS-ERR-FLG} holds {@value BillPaymentService#ERR_FLG_ON}
         */
        public boolean isErrFlagOn() {
            return ERR_FLG_ON.equals(errFlg);
        }

        /**
         * {@code MOVE 'Y' TO WS-ERR-FLG} - lines 139, 160, 181, 186, 200, 360, 367, 391, 398, 424, 431,
         * 455, 462, 491, 535 and 542.
         */
        public void setErrFlagOn() {
            this.errFlg = ERR_FLG_ON;
        }

        /**
         * {@code WS-ERR-FLG} verbatim, so a caller can distinguish "off" from "holding something that is
         * neither condition name" - which two independent {@code 88}-level tests genuinely can.
         *
         * @return one character; {@value BillPaymentService#ERR_FLG_OFF} until an error is raised
         */
        public String errFlg() {
            return errFlg;
        }

        /**
         * {@code IF CONF-PAY-YES} - the test at line 210 that decides whether the payment happens.
         *
         * @return {@code true} when {@code WS-CONF-PAY-FLG} holds {@value BillPaymentService#CONF_PAY_YES}
         */
        public boolean isConfPayYes() {
            return CONF_PAY_YES.equals(confPayFlg);
        }

        /**
         * {@code SET CONF-PAY-YES TO TRUE} - line 176.
         */
        public void setConfPayYes() {
            this.confPayFlg = CONF_PAY_YES;
        }

        /**
         * {@code SET CONF-PAY-NO TO TRUE} - line 156, the paragraph's first statement.
         */
        public void setConfPayNo() {
            this.confPayFlg = CONF_PAY_NO;
        }

        /**
         * {@code WS-CONF-PAY-FLG} verbatim.
         *
         * @return one character; {@value BillPaymentService#CONF_PAY_NO} unless the user confirmed
         */
        public String confPayFlg() {
            return confPayFlg;
        }

        /**
         * {@code WS-USR-MODIFIED} verbatim - write-only state, never read by the program.
         *
         * @return one character, always {@value BillPaymentService#USR_MODIFIED_NO} on any path this
         *     service reaches, because line 102 is the only statement that writes it
         */
        public String usrModified() {
            return usrModified;
        }

        /**
         * {@code SET USR-MODIFIED-NO TO TRUE} - line 102, in {@code MAIN-PARA}.
         */
        public void setUsrModifiedNo() {
            this.usrModified = USR_MODIFIED_NO;
        }

        /**
         * Whether an account identifier was supplied, which is the {@code WHEN OTHER / CONTINUE} arm of
         * lines 165-166.
         *
         * @return {@code true} when the empty-identifier check took its no-op arm
         */
        public boolean acctIdCheckContinued() {
            return acctIdCheckContinued;
        }

        /**
         * Records that lines 165-166 took the {@code CONTINUE} arm.
         */
        public void setAcctIdCheckContinued() {
            this.acctIdCheckContinued = true;
        }

        /**
         * Whether the browse position reported success at lines 452-453.
         *
         * @return {@code true} when {@code STARTBR-TRANSACT-FILE} took its {@code NORMAL} arm
         */
        public boolean browseStarted() {
            return browseStarted;
        }

        /**
         * Records the {@code NORMAL} arm of {@code STARTBR-TRANSACT-FILE}, lines 452-453.
         */
        public void setBrowseStarted() {
            this.browseStarted = true;
        }

        /**
         * Whether the account rewrite reported success at lines 388-389.
         *
         * @return {@code true} when {@code UPDATE-ACCTDAT-FILE} took its {@code NORMAL} arm
         */
        public boolean accountRewritten() {
            return accountRewritten;
        }

        /**
         * Records the {@code NORMAL} arm of {@code UPDATE-ACCTDAT-FILE}, lines 388-389.
         */
        public void setAccountRewritten() {
            this.accountRewritten = true;
        }

        /**
         * {@code WS-MESSAGE PIC X(80)}.
         *
         * @return exactly {@value BillPaymentService#WS_MESSAGE_LENGTH} characters
         */
        public String message() {
            return message;
        }

        /**
         * {@code MOVE <literal> TO WS-MESSAGE}: stores the value at the item's declared width, so a shorter
         * literal is space-padded on the right and a longer one truncated on the right.
         *
         * @param value the sending value; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setMessage(String value) {
            Objects.requireNonNull(value, "A message is required; move SPACES explicitly with "
                    + "setMessageSpaces() to blank the field");
            this.message = codec.movePicX(value, WS_MESSAGE_LENGTH);
        }

        /**
         * {@code MOVE SPACES TO WS-MESSAGE} - lines 104, 525 and 566.
         */
        public void setMessageSpaces() {
            this.message = spaces(WS_MESSAGE_LENGTH);
        }

        /**
         * {@code ACTIDINI PIC X(11)}.
         *
         * @return exactly {@value BillPaymentService#ACT_ID_IN_LENGTH} characters
         */
        public String actIdIn() {
            return actIdIn;
        }

        public void setActIdIn(String value) {
            Objects.requireNonNull(value, "ACTIDINI is PIC X(" + ACT_ID_IN_LENGTH + ") and has no null "
                    + "representation; move SPACES or LOW-VALUES explicitly");
            this.actIdIn = codec.movePicX(value, ACT_ID_IN_LENGTH);
        }

        /**
         * {@code CURBALI PIC X(14)} - the balance as the screen carries it, already edited.
         *
         * @return exactly {@value BillPaymentService#CUR_BAL_LENGTH} characters
         */
        public String curBal() {
            return curBal;
        }

        public void setCurBal(String value) {
            Objects.requireNonNull(value, "CURBALI is PIC X(" + CUR_BAL_LENGTH + ") and has no null "
                    + "representation; move SPACES explicitly");
            this.curBal = codec.movePicX(value, CUR_BAL_LENGTH);
        }

        /**
         * {@code CONFIRMI PIC X(1)}.
         *
         * @return exactly {@value BillPaymentService#CONFIRM_LENGTH} character
         */
        public String confirm() {
            return confirm;
        }

        /**
         * Stores {@code CONFIRMI} at its declared width, with no case folding.
         *
         * @param value the sending value; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setConfirm(String value) {
            Objects.requireNonNull(value, "CONFIRMI is PIC X(" + CONFIRM_LENGTH + ") and has no null "
                    + "representation; move SPACES or LOW-VALUES explicitly");
            this.confirm = codec.movePicX(value, CONFIRM_LENGTH);
        }

        /**
         * {@code ERRMSGO PIC X(78)} - the message as the last send transmitted it.
         *
         * @return exactly {@value BillPaymentService#ERR_MSG_LENGTH} characters
         */
        public String errMsg() {
            return errMsg;
        }

        /**
         * {@code MOVE WS-MESSAGE TO ERRMSGO OF COBIL0AO} - line 293.
         *
         * @param value the value the send transmits; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setErrMsg(String value) {
            this.errMsg = Objects.requireNonNull(value, "ERRMSGO is PIC X(" + ERR_MSG_LENGTH + ") and "
                    + "has no null representation");
        }

        /**
         * {@code WS-CURR-BAL PIC +9999999999.99} - the edited balance before it reaches the screen field.
         *
         * @return exactly {@value BillPaymentService#WS_CURR_BAL_LENGTH} characters
         */
        public String currBalEdited() {
            return currBalEdited;
        }

        /**
         * {@code MOVE ACCT-CURR-BAL TO WS-CURR-BAL} - line 193.
         *
         * @param value the edited image; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         * @throws IllegalArgumentException if it is not exactly
         *     {@link BillPaymentService#WS_CURR_BAL_LENGTH} characters, since a numeric-edited image is never
         *     padded or truncated - every position of the picture always emits
         */
        public void setCurrBalEdited(String value) {
            Objects.requireNonNull(value, "An edited balance is required");
            if (value.length() != WS_CURR_BAL_LENGTH) {
                throw new IllegalArgumentException("WS-CURR-BAL is PIC +9999999999.99, which always "
                        + "emits exactly " + WS_CURR_BAL_LENGTH + " characters - a sign, "
                        + CURR_BAL_INTEGER_DIGITS + " integer digits, the point and "
                        + CURR_BAL_FRACTION_DIGITS + " fraction digits; " + value.length()
                        + " was supplied");
            }
            this.currBalEdited = value;
        }

        /**
         * {@code WS-TRAN-AMT PIC +99999999.99} - declared at line 55 and never referenced.
         *
         * @return exactly {@value BillPaymentService#WS_TRAN_AMT_LENGTH} spaces, always
         */
        public String tranAmtEdited() {
            return tranAmtEdited;
        }

        /**
         * {@code WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} - declared at line 58 and never referenced.
         *
         * @return {@value BillPaymentService#WS_TRAN_DATE}, always
         */
        public String tranDate() {
            return tranDate;
        }

        /**
         * Which field the last {@code MOVE -1 TO <field>L} named.
         *
         * @return one of the three constants; {@link CursorField#NONE} until a statement names a field
         */
        public CursorField cursorField() {
            return cursorField;
        }

        /**
         * {@code MOVE -1 TO <field>L} - the CICS idiom for placing the cursor.
         *
         * @param value the field to place it in; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setCursorField(CursorField value) {
            this.cursorField = Objects.requireNonNull(value, "A cursor target is required; use "
                    + "CursorField.NONE for the paths that name no field and leave the map's own IC "
                    + "position standing");
        }

        /**
         * {@code ERRMSGC PICTURE X} - the message line's colour override.
         *
         * @return one character, or {@code null} where the program applied none and the mapset's declared
         *     red stands
         */
        public String messageHighlight() {
            return messageHighlight;
        }

        /**
         * {@code MOVE DFHGREEN TO ERRMSGC OF COBIL0AO} - line 526, the program's only attribute override.
         *
         * @param value the attribute character, normally
         *     {@link BillPaymentService#MESSAGE_HIGHLIGHT_GREEN}; {@code null} requests no override
         */
        public void setMessageHighlight(String value) {
            this.messageHighlight = value;
        }

        /**
         * Every screen the execution sent, in the order it sent them.
         *
         * @return an unmodifiable view; empty when no send was reached
         */
        public List<SentScreen> sentScreens() {
            return Collections.unmodifiableList(sentScreens);
        }

        /**
         * How many times {@code EXEC CICS SEND MAP} was issued.
         *
         * @return zero, one or two on the program's own paths
         */
        public int screensSent() {
            return sentScreens.size();
        }

        /**
         * {@code EXEC CICS SEND MAP('COBIL0A') MAPSET('COBIL00') FROM(COBIL0AO) ERASE CURSOR} - lines
         * 295-301: snapshots the transmitted screen.
         */
        public void recordSend() {
            sentScreens.add(new SentScreen(sentScreens.size() + 1, errMsg, actIdIn, curBal, confirm,
                    cursorField, messageHighlight));
        }

        /**
         * The text of every {@code DISPLAY} the execution reached, in order.
         *
         * @return an unmodifiable view; empty unless a {@code WHEN OTHER} arm was taken
         */
        public List<String> displays() {
            return Collections.unmodifiableList(displays);
        }

        /**
         * Records one {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD}.
         *
         * @param text the composed line; must not be {@code null}
         * @throws NullPointerException if {@code text} is {@code null}
         */
        public void recordDisplay(String text) {
            displays.add(Objects.requireNonNull(text, "A DISPLAY emits text"));
        }

        /**
         * {@code WS-RESP-CD} - the CICS response of the last file operation.
         *
         * @return the reported value, or {@link FileStatus#RESP_NOT_REPORTED} where the operation surfaced
         *     none
         */
        public int respCd() {
            return respCd;
        }

        /**
         * {@code WS-REAS-CD} - the CICS reason code of the last file operation.
         *
         * @return the reported value, {@link FileStatus#NO_REASON_CODE} where there was none
         */
        public int reasCd() {
            return reasCd;
        }

        /**
         * {@code RESP(WS-RESP-CD) RESP2(WS-REAS-CD)} - records what one file operation reported.
         *
         * @param resp the response code
         * @param reas the reason code
         */
        public void setResponseCodes(int resp, int reas) {
            this.respCd = resp;
            this.reasCd = reas;
        }

        /**
         * {@code ACCT-ID PIC 9(11)} as the account read's {@code RIDFLD}.
         *
         * @return exactly {@value BillPaymentService#ACT_ID_IN_LENGTH} characters
         */
        public String acctIdRidfld() {
            return acctIdRidfld;
        }

        /**
         * The first receiver of the {@code MOVE} at lines 170-171.
         *
         * @param value the eleven characters; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setAcctIdRidfld(String value) {
            this.acctIdRidfld = requireKeyWidth(value, "ACCT-ID");
        }

        /**
         * {@code XREF-ACCT-ID PIC 9(11)} as the alternate-index read's {@code RIDFLD}.
         *
         * @return exactly {@value BillPaymentService#ACT_ID_IN_LENGTH} characters
         */
        public String xrefAcctIdRidfld() {
            return xrefAcctIdRidfld;
        }

        /**
         * The second receiver of the {@code MOVE} at lines 170-171.
         *
         * @param value the eleven characters; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setXrefAcctIdRidfld(String value) {
            this.xrefAcctIdRidfld = requireKeyWidth(value, "XREF-ACCT-ID");
        }

        /**
         * {@code TRAN-ID PIC X(16)} as the browse and write {@code RIDFLD}.
         *
         * @return exactly sixteen characters
         */
        public String tranIdRidfld() {
            return tranIdRidfld;
        }

        /**
         * Sets the transaction record identification field: {@code HIGH-VALUES} at line 212, the highest
         * existing identifier on the {@code READPREV} success arm, {@code ZEROS} on its {@code ENDFILE}
         * arm.
         *
         * @param value the sixteen characters; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         * @throws IllegalArgumentException if it is not exactly sixteen characters
         */
        public void setTranIdRidfld(String value) {
            Objects.requireNonNull(value, "TRAN-ID is PIC X(" + TranRecord.TRAN_ID_LENGTH + ") and has "
                    + "no null representation");
            if (value.length() != TranRecord.TRAN_ID_LENGTH) {
                throw new IllegalArgumentException("TRAN-ID is PIC X(" + TranRecord.TRAN_ID_LENGTH
                        + ") and a record identification field is always its declared width; "
                        + value.length() + " characters were supplied");
            }
            this.tranIdRidfld = value;
        }

        /**
         * {@code WS-TRAN-ID-NUM PIC 9(16)} - the numeric view of the identifier, before and after the
         * increment at line 217.
         *
         * @return the value the sixteen digits denote
         */
        public long tranIdNum() {
            return tranIdNum;
        }

        /**
         * Sets {@code WS-TRAN-ID-NUM}.
         *
         * @param value the value; must not be negative, because {@code PIC 9} is unsigned and has no sign
         *     position at all
         * @throws IllegalArgumentException if {@code value} is negative
         */
        public void setTranIdNum(long value) {
            if (value < 0) {
                throw new IllegalArgumentException("WS-TRAN-ID-NUM is PIC 9(" + WS_TRAN_ID_NUM_DIGITS
                        + "), an unsigned picture with no sign position, so it cannot hold " + value);
            }
            this.tranIdNum = value;
        }

        /**
         * {@code WS-ABS-TIME PIC S9(15) COMP-3} - the CICS absolute time, milliseconds since 1900-01-01.
         *
         * @return the value {@code ASKTIME} reported, zero until it has been called
         */
        public long absTime() {
            return absTime;
        }

        /**
         * Sets {@code WS-ABS-TIME} from {@code EXEC CICS ASKTIME}.
         *
         * @param value the absolute time in milliseconds
         */
        public void setAbsTime(long value) {
            this.absTime = value;
        }

        /**
         * {@code WS-CUR-DATE-X10 PIC X(10)} - {@code FORMATTIME}'s date with {@code DATESEP('-')}.
         *
         * @return exactly {@value BillPaymentService#WS_CUR_DATE_X10_LENGTH} characters
         */
        public String curDateX10() {
            return curDateX10;
        }

        /**
         * Sets {@code WS-CUR-DATE-X10} at its declared width.
         *
         * @param value the sending value; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setCurDateX10(String value) {
            Objects.requireNonNull(value, "WS-CUR-DATE-X10 is PIC X(" + WS_CUR_DATE_X10_LENGTH + ")");
            this.curDateX10 = codec.movePicX(value, WS_CUR_DATE_X10_LENGTH);
        }

        /**
         * {@code WS-CUR-TIME-X08 PIC X(08)} - {@code FORMATTIME}'s time with {@code TIMESEP(':')}.
         *
         * @return exactly {@value BillPaymentService#WS_CUR_TIME_X08_LENGTH} characters
         */
        public String curTimeX08() {
            return curTimeX08;
        }

        /**
         * Sets {@code WS-CUR-TIME-X08} at its declared width.
         *
         * @param value the sending value; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setCurTimeX08(String value) {
            Objects.requireNonNull(value, "WS-CUR-TIME-X08 is PIC X(" + WS_CUR_TIME_X08_LENGTH + ")");
            this.curTimeX08 = codec.movePicX(value, WS_CUR_TIME_X08_LENGTH);
        }

        /**
         * {@code WS-TIMESTAMP} - the twenty-six character image that both timestamp fields of the
         * transaction record receive.
         *
         * @return exactly {@value DateHeader#WS_TIMESTAMP_LENGTH} characters, spaces until composed
         */
        public String timestamp() {
            return timestamp;
        }

        /**
         * Sets {@code WS-TIMESTAMP}.
         *
         * @param value the composed image; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         * @throws IllegalArgumentException if it is not exactly {@link DateHeader#WS_TIMESTAMP_LENGTH}
         *     characters, since it is a group of fixed-width items and both receivers are {@code PIC X(26)}
         */
        public void setTimestamp(String value) {
            Objects.requireNonNull(value, "A timestamp image is required");
            if (value.length() != DateHeader.WS_TIMESTAMP_LENGTH) {
                throw new IllegalArgumentException("WS-TIMESTAMP occupies exactly "
                        + DateHeader.WS_TIMESTAMP_LENGTH + " characters and TRAN-ORIG-TS and "
                        + "TRAN-PROC-TS are both PIC X(" + TranRecord.TRAN_ORIG_TS_LENGTH + "); "
                        + value.length() + " characters were supplied");
            }
            this.timestamp = value;
        }

        /**
         * {@code ACCOUNT-RECORD} - the 300-byte working area.
         *
         * @return the record area; mutable, because line 234 changes the balance in place
         */
        public AccountRecord accountRecord() {
            return accountRecord;
        }

        /**
         * {@code READ ... INTO(ACCOUNT-RECORD)} - replaces all 300 bytes.
         *
         * @param value the record read; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setAccountRecord(AccountRecord value) {
            this.accountRecord = Objects.requireNonNull(value, "INTO(ACCOUNT-RECORD) replaces the whole "
                    + "record area, so a read that succeeded has a record to put there");
        }

        /**
         * {@code CARD-XREF-RECORD} - the 50-byte working area, present only after a successful
         * alternate-index read.
         *
         * @return the record read, or an empty {@link Optional} where the read missed, failed or was never
         *     issued
         */
        public Optional<CardXrefRecord> cardXrefRecord() {
            return Optional.ofNullable(cardXrefRecord);
        }

        /**
         * {@code READ ... INTO(CARD-XREF-RECORD)} - replaces all 50 bytes.
         *
         * @param value the record read; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setCardXrefRecord(CardXrefRecord value) {
            this.cardXrefRecord = Objects.requireNonNull(value, "INTO(CARD-XREF-RECORD) replaces the "
                    + "whole record area, so a read that succeeded has a record to put there");
        }

        /**
         * {@code XREF-CARD-NUM PIC X(16)} as line 225 finds it - the sender of
         * {@code MOVE XREF-CARD-NUM TO TRAN-CARD-NUM}.
         *
         * @return exactly sixteen characters: the card number when the read succeeded, sixteen
         *     {@code LOW-VALUES} characters when it did not
         */
        public String xrefCardNum() {
            return cardXrefRecord == null
                    ? lowValues(CardXrefRecord.XREF_CARD_NUM_LENGTH)
                    : codec.movePicX(cardXrefRecord.xrefCardNum(),
                            CardXrefRecord.XREF_CARD_NUM_LENGTH);
        }

        /**
         * {@code TRAN-RECORD} - the 350-byte working area.
         *
         * @return the record area, or an empty {@link Optional} before either happens
         */
        public Optional<TranRecord> tranRecord() {
            return Optional.ofNullable(tranRecord);
        }

        /**
         * Replaces the transaction record area - {@code READ ... INTO(TRAN-RECORD)} at line 476, and
         * {@code INITIALIZE TRAN-RECORD} at line 218.
         *
         * @param value the record area; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setTranRecord(TranRecord value) {
            this.tranRecord = Objects.requireNonNull(value, "A record area is required; both the read "
                    + "and the INITIALIZE supply one");
        }

        /**
         * {@code CARDDEMO-COMMAREA} - carried in and handed straight back out.
         *
         * @return the communication area the caller supplied, unchanged; never {@code null}
         */
        public NavigationContext commarea() {
            return commarea;
        }

        private static String requireKeyWidth(String value, String field) {
            Objects.requireNonNull(value, () -> field + " is PIC 9(" + ACT_ID_IN_LENGTH + ") and has no "
                    + "null representation");
            if (value.length() != ACT_ID_IN_LENGTH) {
                throw new IllegalArgumentException(field + " is PIC 9(" + ACT_ID_IN_LENGTH + ") and a "
                        + "record identification field is always its declared width, untrimmed and "
                        + "unparsed; " + value.length() + " characters were supplied");
            }
            return value;
        }
    }

}
