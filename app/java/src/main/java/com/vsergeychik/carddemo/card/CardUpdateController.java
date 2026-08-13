package com.vsergeychik.carddemo.card;

import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardDetails;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.ChangeAction;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CommArea;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.FieldMetadata;
import com.vsergeychik.carddemo.card.dto.CardUpdateResponse;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.AidRequestParameter;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.ConversationStateSeal;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import jakarta.validation.Valid;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PUT /api/cards/&#123;cardNum&#125;} - the credit-card update screen, CSD transaction {@code CCUP}
 * ({@code app/csd/CARDDEMO.CSD:367-369}), migrated from {@code app/cbl/COCRDUPC.cbl} (1,560 lines), mapset
 * {@code COCRDUP} ({@code :128}) and map {@code CCRDUPA}.
 *
 * <p>A verified detail that makes the defect harmless in this program: {@code LIT-CCLISTMAP} is never
 * referenced in the {@code PROCEDURE DIVISION}.
 */
@RestController
public class CardUpdateController {
    private static final Log LOG = LogFactory.getLog(CardUpdateController.class);

    static final String LIT_THISPGM = "COCRDUPC";

    static final String LIT_THISTRANID = "CCUP";

    static final String LIT_THISMAPSET = "COCRDUP ";

    static final String LIT_THISMAP = "CCRDUPA";

    static final String LIT_CCLISTPGM = "COCRDLIC";

    static final String LIT_CCLISTTRANID = "CCLI";

    static final String LIT_CCLISTMAPSET = "COCRDLI";

    static final String LIT_CCLISTMAP = "CCRDSLA";

    static final String LIT_MENUPGM = "COMEN01C";

    static final String LIT_MENUTRANID = "CM00";

    static final String LIT_MENUMAPSET = "COMEN01";

    static final String LIT_MENUMAP = "COMEN1A";

    static final String LIT_CARDDTLPGM = "COCRDSLC";

    static final String LIT_CARDDTLTRANID = "CCDL";

    static final String LIT_CARDDTLMAPSET = "COCRDSL";

    static final String LIT_CARDDTLMAP = "CCRDSLA";

    static final String LIT_CARDFILENAME = CardRepository.BASE_CICS_FILE_NAME;

    static final String LIT_CARDFILENAME_ACCT_PATH = CardRepository.ALTERNATE_INDEX_CICS_FILE_NAME;

    static final String LIT_ALL_ALPHA_FROM =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    static final String LIT_ALL_SPACES_TO = " ".repeat(LIT_ALL_ALPHA_FROM.length());

    static final String LIT_UPPER = CardUpdateService.LIT_UPPER;

    static final String LIT_LOWER = CardUpdateService.LIT_LOWER;

    static final int WS_TRANID_LENGTH = 4;

    static final int WS_UCTRANS_LENGTH = 4;

    static final int CARD_NAME_CHECK_LENGTH = 50;

    static final int CARD_MONTH_CHECK_LENGTH = 2;

    static final int CARD_YEAR_CHECK_LENGTH = 4;

    static final int WS_LONG_MSG_LENGTH = 500;

    static final int WS_INFO_MSG_LENGTH = CardUpdateRequest.INFOMSG_LENGTH;

    static final int WS_RETURN_MSG_LENGTH = CardScreenState.CCARD_RETURN_MSG_LENGTH;

    static final int WS_CARD_RID_CARDNUM_LENGTH = CardScreenState.CC_CARD_NUM_LENGTH;

    static final int WS_CARD_RID_ACCT_ID_LENGTH = CardScreenState.CC_ACCT_ID_LENGTH;

    static final int WS_COMMAREA_LENGTH = 2000;

    static final int THIS_PROGCOMMAREA_LENGTH = CommArea.RECORD_LENGTH;

    static final int PASSED_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + THIS_PROGCOMMAREA_LENGTH;

    static final int NO_COMMAREA_LENGTH = 0;

    static final String INPUT_OK = "0";

    static final String INPUT_ERROR = "1";

    static final String INPUT_PENDING = "\u0000";

    static final String FLG_FILTER_NOT_OK = "0";

    static final String FLG_FILTER_ISVALID = "1";

    static final String FLG_FILTER_BLANK = " ";

    static final String WS_RETURN_FLAG_OFF = "\u0000";

    static final String WS_RETURN_FLAG_ON = "1";

    static final String PFK_VALID = "0";

    static final String PFK_INVALID = "1";

    static final String FLG_YES_NO_CHECK_INITIAL = "N";

    static final String CARD_STATUS_YES = "Y";

    static final String CARD_STATUS_NO = "N";

    static final int VALID_MONTH_MINIMUM = 1;

    static final int VALID_MONTH_MAXIMUM = 12;

    static final int VALID_YEAR_MINIMUM = 1950;

    static final int VALID_YEAR_MAXIMUM = 2099;

    private static final FixedWidthCodec PIC_X_CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    static final String WS_INFO_MSG_SPACES = CardScreenState.spaces(WS_INFO_MSG_LENGTH);

    static final String WS_INFO_MSG_LOW_VALUES = CardScreenState.lowValues(WS_INFO_MSG_LENGTH);

    static final String FOUND_CARDS_FOR_ACCOUNT = PIC_X_CODEC.movePicX(
            "Details of selected card shown above", WS_INFO_MSG_LENGTH);

    static final String PROMPT_FOR_SEARCH_KEYS = PIC_X_CODEC.movePicX(
            "Please enter Account and Card Number", WS_INFO_MSG_LENGTH);

    static final String PROMPT_FOR_CHANGES = PIC_X_CODEC.movePicX(
            "Update card details presented above.", WS_INFO_MSG_LENGTH);

    static final String PROMPT_FOR_CONFIRMATION = PIC_X_CODEC.movePicX(
            "Changes validated.Press F5 to save", WS_INFO_MSG_LENGTH);

    static final String CONFIRM_UPDATE_SUCCESS = PIC_X_CODEC.movePicX(
            "Changes committed to database", WS_INFO_MSG_LENGTH);

    static final String INFORM_FAILURE = PIC_X_CODEC.movePicX(
            "Changes unsuccessful. Please try again", WS_INFO_MSG_LENGTH);

    static final String WS_RETURN_MSG_OFF = CardUpdateService.RETURN_MESSAGE_OFF;

    static final String WS_EXIT_MESSAGE = PIC_X_CODEC.movePicX(
            "PF03 pressed.Exiting              ", WS_RETURN_MSG_LENGTH);

    static final String WS_PROMPT_FOR_ACCT = PIC_X_CODEC.movePicX(
            "Account number not provided", WS_RETURN_MSG_LENGTH);

    static final String WS_PROMPT_FOR_CARD = PIC_X_CODEC.movePicX(
            "Card number not provided", WS_RETURN_MSG_LENGTH);

    static final String WS_PROMPT_FOR_NAME = PIC_X_CODEC.movePicX(
            "Card name not provided", WS_RETURN_MSG_LENGTH);

    static final String WS_NAME_MUST_BE_ALPHA = PIC_X_CODEC.movePicX(
            "Card name can only contain alphabets and spaces", WS_RETURN_MSG_LENGTH);

    static final String NO_SEARCH_CRITERIA_RECEIVED = PIC_X_CODEC.movePicX(
            "No input received", WS_RETURN_MSG_LENGTH);

    static final String NO_CHANGES_DETECTED = PIC_X_CODEC.movePicX(
            "No change detected with respect to values fetched.", WS_RETURN_MSG_LENGTH);

    static final String SEARCHED_ACCT_ZEROES = PIC_X_CODEC.movePicX(
            "Account number must be a non zero 11 digit number", WS_RETURN_MSG_LENGTH);

    static final String SEARCHED_ACCT_NOT_NUMERIC = SEARCHED_ACCT_ZEROES;

    static final String SEARCHED_CARD_NOT_NUMERIC = PIC_X_CODEC.movePicX(
            "Card number if supplied must be a 16 digit number", WS_RETURN_MSG_LENGTH);

    static final String CARD_STATUS_MUST_BE_YES_NO = PIC_X_CODEC.movePicX(
            "Card Active Status must be Y or N", WS_RETURN_MSG_LENGTH);

    static final String CARD_EXPIRY_MONTH_NOT_VALID = PIC_X_CODEC.movePicX(
            "Card expiry month must be between 1 and 12", WS_RETURN_MSG_LENGTH);

    static final String CARD_EXPIRY_YEAR_NOT_VALID = PIC_X_CODEC.movePicX(
            "Invalid card expiry year", WS_RETURN_MSG_LENGTH);

    static final String DID_NOT_FIND_ACCT_IN_CARDXREF = PIC_X_CODEC.movePicX(
            "Did not find this account in cards database", WS_RETURN_MSG_LENGTH);

    static final String DID_NOT_FIND_ACCTCARD_COMBO = PIC_X_CODEC.movePicX(
            "Did not find cards for this search condition", WS_RETURN_MSG_LENGTH);

    static final String COULD_NOT_LOCK_FOR_UPDATE = PIC_X_CODEC.movePicX(
            CardUpdateService.MSG_COULD_NOT_LOCK_FOR_UPDATE, WS_RETURN_MSG_LENGTH);

    static final String DATA_WAS_CHANGED_BEFORE_UPDATE = PIC_X_CODEC.movePicX(
            CardUpdateService.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE, WS_RETURN_MSG_LENGTH);

    static final String LOCKED_BUT_UPDATE_FAILED = PIC_X_CODEC.movePicX(
            CardUpdateService.MSG_LOCKED_BUT_UPDATE_FAILED, WS_RETURN_MSG_LENGTH);

    static final String XREF_READ_ERROR = PIC_X_CODEC.movePicX(
            "Error reading Card Data File", WS_RETURN_MSG_LENGTH);

    static final String CODING_TO_BE_DONE = PIC_X_CODEC.movePicX(
            "Looks Good.... so far", WS_RETURN_MSG_LENGTH);

    static final String ACCOUNT_FILTER_MUST_BE_11_DIGITS = PIC_X_CODEC.movePicX(
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER", WS_RETURN_MSG_LENGTH);

    static final String CARD_ID_FILTER_MUST_BE_16_DIGITS = PIC_X_CODEC.movePicX(
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER", WS_RETURN_MSG_LENGTH);

    static final String UNEXPECTED_DATA_SCENARIO = PIC_X_CODEC.movePicX(
            "UNEXPECTED DATA SCENARIO", SystemMessages.ABEND_MSG_LENGTH);

    static final String UNEXPECTED_DATA_ABEND_CODE = "0001";

    static final String UNEXPECTED_ABEND_OCCURRED = PIC_X_CODEC.movePicX(
            "UNEXPECTED ABEND OCCURRED.", SystemMessages.ABEND_MSG_LENGTH);

    static final String ABEND_ROUTINE_ABCODE = "9999";

    static final String NO_TRIGGERING_FAILURE = "no triggering failure";

    static final String FILE_ERROR_PREFIX = "File Error: ";

    static final int ERROR_OPNAME_LENGTH = 8;

    static final String FILE_ERROR_ON = " on ";

    static final int ERROR_FILE_LENGTH = 9;

    static final String FILE_ERROR_RETURNED_RESP = " returned RESP ";

    static final int ERROR_RESP_LENGTH = 10;

    static final String FILE_ERROR_RESP2 = ",RESP2 ";

    static final String FILE_ERROR_TRAILER = "     ";

    static final int RESP_CODE_DIGITS = 9;

    static final int FILE_ERROR_MESSAGE_LENGTH =
            FILE_ERROR_PREFIX.length() + ERROR_OPNAME_LENGTH + FILE_ERROR_ON.length()
                    + ERROR_FILE_LENGTH + FILE_ERROR_RETURNED_RESP.length() + ERROR_RESP_LENGTH
                    + FILE_ERROR_RESP2.length() + ERROR_RESP_LENGTH + FILE_ERROR_TRAILER.length();

    static final String READ_OPERATION_NAME = CardRepository.READ_OPERATION_NAME;

    static {
        if (FILE_ERROR_MESSAGE_LENGTH != 80) {
            throw new AssertionError("WS-FILE-ERROR-MESSAGE must be exactly 80 characters, as "
                    + "app/cbl/COCRDUPC.cbl:133-152 declares it, but the transcribed parts sum to "
                    + FILE_ERROR_MESSAGE_LENGTH + "; a filler has been mistranscribed");
        }
        if (THIS_PROGCOMMAREA_LENGTH != 329) {
            throw new AssertionError("WS-THIS-PROGCOMMAREA must be exactly 329 bytes - "
                    + "CCUP-CHANGE-ACTION (1) + CCUP-OLD-DETAILS (89) + CCUP-NEW-DETAILS (89) + "
                    + "CARD-UPDATE-RECORD (150, the FILLER X(59) at app/cbl/COCRDUPC.cbl:321 "
                    + "included) - but CardUpdateRequest.CommArea reports "
                    + THIS_PROGCOMMAREA_LENGTH);
        }
        if (PASSED_COMMAREA_LENGTH != 489) {
            throw new AssertionError("The passed commarea must be exactly 489 bytes - "
                    + "CARDDEMO-COMMAREA (160) followed by WS-THIS-PROGCOMMAREA (329) - but the parts "
                    + "sum to " + PASSED_COMMAREA_LENGTH);
        }
        requireLiteral(LIT_CARDFILENAME, "CARDDAT ", "LIT-CARDFILENAME", 252);
        requireLiteral(LIT_CARDFILENAME_ACCT_PATH, "CARDAIX ", "LIT-CARDFILENAME-ACCT-PATH", 254);
        requireLiteral(READ_OPERATION_NAME, "READ", "ERROR-OPNAME's only value", 1407);
        requireLiteral(LIT_UPPER, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "LIT-UPPER", 261);
        requireLiteral(LIT_LOWER, "abcdefghijklmnopqrstuvwxyz", "LIT-LOWER", 263);
        requireLiteral(WS_RETURN_MSG_OFF, " ".repeat(75), "WS-RETURN-MSG-OFF (SPACES)", 174);
        requireLiteral(ScreenTitles.CCDA_TITLE01, "      AWS Mainframe Modernization       ",
                "CCDA-TITLE01", "app/cpy/COTTL01Y.cpy:18-19");
        requireLiteral(ScreenTitles.CCDA_TITLE02, "              CardDemo                  ",
                "CCDA-TITLE02", "app/cpy/COTTL01Y.cpy:20+22");
    }

    /**
     * Verifies a literal this class borrows from another type against the value
     * {@code app/cbl/COCRDUPC.cbl} declares, so a change on either side cannot pass unnoticed.
     *
     * @param actual the borrowed value
     * @param expected the value the COBOL declares
     * @param cobolName the COBOL item's name, for the failure message
     * @param cobolLine the line of {@code app/cbl/COCRDUPC.cbl} that declares it
     * @throws AssertionError if the two disagree
     */
    private static void requireLiteral(String actual, String expected, String cobolName,
            int cobolLine) {
        requireLiteral(actual, expected, cobolName, "app/cbl/COCRDUPC.cbl:" + cobolLine);
    }

    private static void requireLiteral(String actual, String expected, String cobolName,
            String where) {
        if (!expected.equals(actual)) {
            throw new AssertionError(cobolName + " is declared as '" + expected + "' at " + where
                    + ", but this class resolved it to '" + actual + "'");
        }
    }

    private final CardRepository cardRepository;

    private final CardUpdateService cardUpdateService;

    private final Clock clock;

    private final FixedWidthCodec codec;

    /**
     * The seal that carries {@code WS-THIS-PROGCOMMAREA} across a stateless turn without letting a caller
     * write it, or read the {@code CARD-CVV-CD} inside it.
     *
     * <p>See {@link #STATE_PURPOSE} for what the token is bound to, and {@link ConversationStateSeal} for
     * why the area cannot travel as structured JSON.
     */
    private final ConversationStateSeal conversationStateSeal;

    /**
     * Spring's constructor, wiring the repository, the update service, the clock, the conversation-state
     * seal and the active dataset code page.
     *
     * @param cardRepository the card file's repository, for the unlocked display read; must not be
     *     {@code null}
     * @param cardUpdateService {@code 9200}/{@code 9300}, the locked rewrite; must not be {@code null}
     * @param clock the clock {@code FUNCTION CURRENT-DATE} reads; must not be {@code null}
     * @param datasetCharset the active dataset code page, from
     *     {@code @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)}; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CardUpdateController(
            CardRepository cardRepository,
            CardUpdateService cardUpdateService,
            Clock clock,
            ConversationStateSeal conversationStateSeal,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset) {
        this.cardRepository = Objects.requireNonNull(cardRepository,
                "A CardRepository is required: 9100-GETCARD-BYACCTCARD reads CARDDAT without the "
                        + "UPDATE option, and this controller reaches it no other way");
        this.cardUpdateService = Objects.requireNonNull(cardUpdateService,
                "A CardUpdateService is required: 9200-WRITE-PROCESSING is the only path that writes, "
                        + "and it is deliberately not implemented here");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: 3100-SCREEN-INIT reads FUNCTION CURRENT-DATE twice, and reading "
                        + "a clock inline would make every parity case non-deterministic");
        this.conversationStateSeal = Objects.requireNonNull(conversationStateSeal,
                "A ConversationStateSeal is required: 1200-EDIT-MAP-INPUTS skips all four edits when the "
                        + "commarea reads CCUP-CHANGES-OK-NOT-CONFIRMED (app/cbl/COCRDUPC.cbl:685-693), "
                        + "so that byte has to be unforgeable while it travels in the payload, and the "
                        + "same area carries CARD-CVV-CD, which no DFHMDF field of COCRDUP paints");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset,
                "A dataset charset is required: a fixed-width mainframe field is bytes in a specific "
                        + "code page, so the code page is always stated and never taken from the "
                        + "platform"));
    }

    FixedWidthCodec codec() {
        return codec;
    }

    /**
     * One task's worth of {@code WORKING-STORAGE}: {@code WS-MISC-STORAGE} in full, {@code CC-WORK-AREA},
     * {@code CARDDEMO-COMMAREA}, {@code WS-THIS-PROGCOMMAREA} and {@code WS-COMMAREA}.
     *
     * <p>Deliberately mutable, because {@code WORKING-STORAGE} is: the program assigns into it throughout
     * processing.
     */
    static final class Conversation {
        int wsRespCd;

        int wsReasCd;

        String wsTranid;

        String wsUctrans;

        String wsInputFlag;

        String wsEditAcctFlag;

        String wsEditCardFlag;

        String wsEditCardnameFlag;

        String wsEditCardstatusFlag;

        String wsEditCardexpmonFlag;

        String wsEditCardexpyearFlag;

        String wsReturnFlag;

        String wsPfkFlag;

        String cardNameCheck;

        String flgYesNoCheck;

        String cardMonthCheck;

        String cardYearCheck;

        String cardAcctIdX;

        String cardCvvCdX;

        String cardCardNumX;

        String cardNameEmbossedX;

        String cardStatusX;

        String cardExpiraionDateX;

        String wsCardRidCardnum;

        String wsCardRidAcctId;

        String errorOpname;

        String errorFile;

        String errorResp;

        String errorResp2;

        String wsLongMsg;

        String wsInfoMsg;

        String wsReturnMsg;

        CardScreenState ccWorkArea;

        NavigationContext carddemoCommarea;

        CommArea thisProgCommarea;

        String wsCommarea;

        Optional<CardRecord> cardRecord;

        SystemMessages.AbendData abendData;

        SecUserRecord secUserData;

        CustomerRecord customerRecord;

        DateHeader dateHeader;

        boolean returned;

        String cursorField;

        Conversation() {
            // Every member is assigned by initializeStorage, which reproduces the COBOL INITIALIZE
            // precisely - spaces for alphanumeric items and zeros for numeric ones, never Java defaults,
            // and never null.
        }

        boolean inputOk() {
            return INPUT_OK.equals(wsInputFlag);
        }

        boolean inputError() {
            return INPUT_ERROR.equals(wsInputFlag);
        }

        /**
         * {@code 88 INPUT-PENDING VALUE LOW-VALUES} - {@code :56}; declared, never tested by the source
         * (B5).
         */
        boolean inputPending() {
            return INPUT_PENDING.equals(wsInputFlag);
        }

        boolean flgAcctfilterNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditAcctFlag);
        }

        boolean flgAcctfilterIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditAcctFlag);
        }

        boolean flgAcctfilterBlank() {
            return FLG_FILTER_BLANK.equals(wsEditAcctFlag);
        }

        boolean flgCardfilterNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditCardFlag);
        }

        boolean flgCardfilterIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditCardFlag);
        }

        boolean flgCardfilterBlank() {
            return FLG_FILTER_BLANK.equals(wsEditCardFlag);
        }

        boolean flgCardnameNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditCardnameFlag);
        }

        boolean flgCardnameIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditCardnameFlag);
        }

        boolean flgCardnameBlank() {
            return FLG_FILTER_BLANK.equals(wsEditCardnameFlag);
        }

        boolean flgCardstatusNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditCardstatusFlag);
        }

        boolean flgCardstatusIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditCardstatusFlag);
        }

        boolean flgCardstatusBlank() {
            return FLG_FILTER_BLANK.equals(wsEditCardstatusFlag);
        }

        boolean flgCardexpmonNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditCardexpmonFlag);
        }

        boolean flgCardexpmonIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditCardexpmonFlag);
        }

        boolean flgCardexpmonBlank() {
            return FLG_FILTER_BLANK.equals(wsEditCardexpmonFlag);
        }

        boolean flgCardexpyearNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditCardexpyearFlag);
        }

        boolean flgCardexpyearIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditCardexpyearFlag);
        }

        boolean flgCardexpyearBlank() {
            return FLG_FILTER_BLANK.equals(wsEditCardexpyearFlag);
        }

        /**
         * {@code 88 WS-RETURN-FLAG-OFF VALUE LOW-VALUES} - {@code :82}; declared, never tested (B5).
         */
        boolean wsReturnFlagOff() {
            return WS_RETURN_FLAG_OFF.equals(wsReturnFlag);
        }

        /**
         * {@code 88 WS-RETURN-FLAG-ON VALUE '1'} - {@code :83}; declared, never tested (B5).
         */
        boolean wsReturnFlagOn() {
            return WS_RETURN_FLAG_ON.equals(wsReturnFlag);
        }

        boolean pfkValid() {
            return PFK_VALID.equals(wsPfkFlag);
        }

        boolean pfkInvalid() {
            return PFK_INVALID.equals(wsPfkFlag);
        }

        boolean flgYesNoValid() {
            return CARD_STATUS_YES.equals(flgYesNoCheck) || CARD_STATUS_NO.equals(flgYesNoCheck);
        }

        boolean noInfoMessage() {
            return WS_INFO_MSG_SPACES.equals(wsInfoMsg) || WS_INFO_MSG_LOW_VALUES.equals(wsInfoMsg);
        }

        boolean foundCardsForAccount() {
            return FOUND_CARDS_FOR_ACCOUNT.equals(wsInfoMsg);
        }

        boolean promptForConfirmation() {
            return PROMPT_FOR_CONFIRMATION.equals(wsInfoMsg);
        }

        boolean returnMessageOff() {
            return CardUpdateService.isReturnMessageOff(wsReturnMsg);
        }

        boolean noChangesDetected() {
            return NO_CHANGES_DETECTED.equals(wsReturnMsg);
        }

        boolean couldNotLockForUpdate() {
            return COULD_NOT_LOCK_FOR_UPDATE.equals(wsReturnMsg);
        }

        boolean lockedButUpdateFailed() {
            return LOCKED_BUT_UPDATE_FAILED.equals(wsReturnMsg);
        }

        boolean dataWasChangedBeforeUpdate() {
            return DATA_WAS_CHANGED_BEFORE_UPDATE.equals(wsReturnMsg);
        }

        ChangeAction changeAction() {
            return thisProgCommarea.changeAction();
        }

        void setChangeAction(ChangeAction action) {
            thisProgCommarea = thisProgCommarea.withChangeAction(
                    Objects.requireNonNull(action, "CCUP-CHANGE-ACTION always holds one byte"));
        }

        CardDetails oldDetails() {
            return thisProgCommarea.oldDetails();
        }

        void setOldDetails(CardDetails details) {
            thisProgCommarea = thisProgCommarea.withOldDetails(details);
        }

        CardDetails newDetails() {
            return thisProgCommarea.newDetails();
        }

        void setNewDetails(CardDetails details) {
            thisProgCommarea = thisProgCommarea.withNewDetails(details);
        }

        long cardMonthCheckN(FixedWidthCodec codec) {
            return zonedDigitsValue(cardMonthCheck, CARD_MONTH_CHECK_LENGTH, codec);
        }

        long cardYearCheckN(FixedWidthCodec codec) {
            return zonedDigitsValue(cardYearCheck, CARD_YEAR_CHECK_LENGTH, codec);
        }
    }

    static long zonedDigitsValue(String image, int width, FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to read a zoned span: the digit lives in "
                + "each byte's low-order nibble, so the code page has to be stated and is never "
                + "assumed");
        String moved = codec.movePicX(image == null ? "" : image, width);
        byte[] bytes = moved.getBytes(codec.charset());
        long value = 0;
        for (byte encoded : bytes) {
            int nibble = encoded & ZONED_DIGIT_NIBBLE_MASK;
            int digit = nibble <= MAX_DIGIT_NIBBLE ? nibble : NO_ZONED_DIGIT;
            value = value * DECIMAL_RADIX + digit;
        }
        return value;
    }

    private static final int ZONED_DIGIT_NIBBLE_MASK = 0x0F;

    private static final int MAX_DIGIT_NIBBLE = 9;

    private static final int NO_ZONED_DIGIT = 0;

    private static final int DECIMAL_RADIX = 10;

    static boolean isLowValuesOrSpaces(String value, int length) {
        String image = PIC_X_CODEC.movePicX(value == null ? "" : value, length);
        return CardScreenState.lowValues(length).equals(image)
                || CardScreenState.spaces(length).equals(image);
    }

    static boolean isAllZeroCharacters(String value, int length) {
        String image = PIC_X_CODEC.movePicX(value == null ? "" : value, length);
        return "0".repeat(length).equals(image);
    }

    static String inspectConverting(String subject, String from, String to) {
        if (from.length() != to.length()) {
            throw new IllegalArgumentException("INSPECT ... CONVERTING requires operands of equal "
                    + "length, but the FROM operand is " + from.length() + " characters and the TO "
                    + "operand is " + to.length());
        }
        StringBuilder translated = new StringBuilder(subject.length());
        for (int index = 0; index < subject.length(); index++) {
            char original = subject.charAt(index);
            int position = from.indexOf(original);
            translated.append(position < 0 ? original : to.charAt(position));
        }
        return translated.toString();
    }

    static String functionUpperCase(String argument) {
        return inspectConverting(argument, LIT_LOWER, LIT_UPPER);
    }

    static boolean isTrimmedEmpty(String argument) {
        for (int index = 0; index < argument.length(); index++) {
            if (argument.charAt(index) != ' ') {
                return false;
            }
        }
        return true;
    }

    private static final int AID_MIN = 0;

    private static final int AID_MAX = 255;

    static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    static final String EIBAID_PARAM_ALIAS = AidRequestParameter.ALTERNATE_NAME;

    static final String EIBCALEN_PARAM = "eibcalen";

    static final String CARDSID_MEMBER = "cardsid";
    static final String NO_CRITERION_IMAGE = "*";

    /**
     * The payload member the sealed {@code WS-THIS-PROGCOMMAREA} travels in, spelled as the client sends
     * it.
     */
    static final String STATE_TOKEN_MEMBER = "stateToken";

    /**
     * What a state token issued by this screen is bound to, as GCM additional authenticated data.
     *
     * <p>Naming the program and the member means a token issued for the account-update conversation cannot
     * be presented here, and vice versa: the two carry different areas of different widths, and one
     * accepted as the other would be a different program's confirmation.
     */
    static final String STATE_PURPOSE = LIT_THISPGM + "/" + STATE_TOKEN_MEMBER;

    /** The route: {@code PUT /api/cards/{cardNum}}, CSD transaction {@code CCUP}. */
    static final String CARD_UPDATE_PATH = "/api/cards/{cardNum}";

    /**
     * Accepts and processes a credit-card update: {@code PUT /api/cards/&#123;cardNum&#125;}, transaction
     * {@code CCUP}, mapset {@code COCRDUP}, map {@code CCRDUPA}, seventeen fields.
     *
     * <p>Always answers {@code 200 OK}, because {@code COCRDUPC} always ends in {@code EXEC CICS RETURN} or
     * {@code EXEC CICS XCTL}: every path either paints the screen ({@code COMMON-RETURN},
     * {@code app/cbl/COCRDUPC.cbl:546-558}) or transfers control ({@code :473-476}).
     *
     * @param cardNum {@code CARDSIDI PIC X(16)} - the card number this URI addresses, and the
     *     {@code CARDDAT} key {@code 9100-GETCARD-BYACCTCARD} reads with
     * @param request the whole {@code 01 CCRDUPAI} symbolic-map request, validated against the declared
     *     widths
     * @param eibAid {@code EIBAID} - the raw attention identifier byte, {@code 0}-{@code 255}, under the
     *     alternate spelling
     * @param eibcalen {@code EIBCALEN} - the length of the area that arrived, which is
     *     {@value NavigationContext#COMMAREA_LENGTH} from the card list and {@value #WS_COMMAREA_LENGTH} from
     *     this program's own
     * @param eibaid the same attention identifier under {@link AidRequestParameter#CANONICAL_NAME}
     * @return the painted screen and its metadata: the seventeen fields, the next-screen triple, both
     *     commareas, the work area and the attribute quads, all in the body
     * @throws IllegalArgumentException if {@code cardNum} or a bound field is wider than its
     *     {@code PICTURE}, if the AID is outside {@code 0}-{@code 255}, if the two AID spellings disagree
     */
    @PutMapping(path = CARD_UPDATE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScreenResponse<CardUpdateResponse>> updateCardDetail(
            @PathVariable("cardNum") String cardNum,
            @Valid @RequestBody(required = false) CardUpdateRequest request,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid,
            @RequestParam(name = EIBCALEN_PARAM, required = false) Integer eibcalen,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid) {
        Objects.requireNonNull(cardNum, "A card number is required in the path: it is the RIDFLD of "
                + "the READ at app/cbl/COCRDUPC.cbl:1382-1390 and of the READ ... UPDATE at "
                + ":1427-1436");

        CardUpdateRequest received = bind(cardNum, request);
        int commareaLength = resolveEibcalen(eibcalen, received);
        byte attentionIdentifier =
                resolveAttentionIdentifier(AidRequestParameter.resolve(eibaid, eibAid));

        PaintedScreen painted = handle(received, commareaLength, attentionIdentifier);
        CardUpdateResponse sealed = sealState(painted.response(), received.getCardsid());
        return ResponseEntity.ok(ScreenResponse.of(sealed,
                screenMetadataOf(sealed, painted.inputArea(), painted.cursorField())));
    }

    CardUpdateRequest bind(String cardNum, CardUpdateRequest request) {
        if (cardNum.length() > CardUpdateRequest.CARDSID_LENGTH) {
            throw ScreenInputRejectedException.tooWide(CARDSID_MEMBER,
                    "CARDSIDI PIC X(" + CardUpdateRequest.CARDSID_LENGTH + ")",
                    CardUpdateRequest.CARDSID_LENGTH, cardNum.length());
        }

        CardUpdateRequest received = request == null ? new CardUpdateRequest()
                : new CardUpdateRequest(request);
        ScreenInputRejectedException.requireKeyAgreement(CARDSID_MEMBER, cardNum,
                received.getCardsid(), CardUpdateRequest.CARDSID_LENGTH, codec, NO_CRITERION_IMAGE);
        received.setCardsid(codec.movePicX(cardNum, CardUpdateRequest.CARDSID_LENGTH));

        if (received.hasNavigationContext()) {
            received.setNavigationContext(
                    received.getNavigationContext().withCardNum(carriedCardNumber(cardNum)));
        }

        // A COBOL alphanumeric item has no absent state, so no null guard is written for a state that
        // cannot occur.
        String acctsid = received.getAcctsid();
        if (acctsid.length() > CardUpdateRequest.ACCTSID_LENGTH) {
            throw new IllegalArgumentException("ACCTSID is ACCTSIDI PIC X("
                    + CardUpdateRequest.ACCTSID_LENGTH + ") and was given " + acctsid.length()
                    + " characters. Padding it would keep the leading "
                    + CardUpdateRequest.ACCTSID_LENGTH + " and filter on a different account.");
        }
        received.setAcctsid(codec.movePicX(acctsid, CardUpdateRequest.ACCTSID_LENGTH));
        unsealState(received);
        return received;
    }

    /**
     * Restores {@code WS-THIS-PROGCOMMAREA} from the token the client sent back, and refuses one this
     * screen did not issue for this card.
     *
     * <p>An absent token is not a refusal: it is {@code EIBCALEN = 0}, the cold start {@code :388} branches
     * on, and it leaves the request carrying {@link CardUpdateRequest.CommArea#initialised()} - the
     * {@code INITIALIZE WS-THIS-PROGCOMMAREA} state, from which {@code CCUP-DETAILS-NOT-FETCHED} holds and
     * no write arm is reachable. What is refused is a token that is not one, or one issued for another
     * card: accepting either would let a confirmation proved for one record stand as proof for another, and
     * would hand back a {@code CCUP-OLD-DETAILS} - CVV included - that belongs to a different card.
     *
     * <p>A request that already carries a structured commarea and no token keeps it. That is how the
     * program flow, a service and a parity case construct this request - in Java, where the area is the
     * program's own storage rather than something a caller supplies - and it is why the wire contract and
     * the in-process contract can differ without the parity corpus changing.
     *
     * @param received the request, already keyed from the URI; mutated in place
     * @throws ScreenInputRejectedException if the token is not this screen's, or names another card
     */
    private void unsealState(CardUpdateRequest received) {
        String token = received.getStateToken();
        if (token.isEmpty()) {
            return;
        }
        received.setCommArea(CardUpdateRequest.CommArea.decode(
                conversationStateSeal.unseal(STATE_TOKEN_MEMBER, STATE_PURPOSE,
                        received.getCardsid(), token),
                codec));
    }

    /**
     * Seals {@code WS-THIS-PROGCOMMAREA} into the token the client carries to its next turn.
     *
     * <p>Applied at the HTTP boundary rather than inside the program flow, so every arm of
     * {@code COMMON-RETURN} and the {@code XCTL} arm are covered by one statement, and so a parity case,
     * which drives the flow directly and compares the structured 329 bytes, sees exactly what it always
     * saw.
     *
     * @param painted  the response the interaction produced; mutated in place
     * @param identity the canonical card number to bind the token to
     * @return the same response, now carrying its sealed area
     */
    private CardUpdateResponse sealState(CardUpdateResponse painted, String identity) {
        painted.setStateToken(conversationStateSeal.seal(STATE_PURPOSE, identity,
                painted.getCommArea().encode(codec)));
        return painted;
    }

    /**
     * The URI's card number as {@code CDEMO-CARD-NUM PIC 9(16)} holds it.
     *
     * <p>{@code CARDSID} is {@code PIC X(16)} and the communication area's carried card number is
     * {@code PIC 9(16)}, so the projection has to cross that boundary. A path value of sixteen digits or
     * fewer is the number it spells, leading zeros and all. Anything a {@code PIC 9(16)} item cannot
     * hold - a blank segment, {@code '*'}, or any value with a non-digit in it - yields zero, which is
     * the area's own unset value and the one {@code :1093} already tests for. Zero rather than a
     * refusal, because zero is the only answer that cannot name a card the URI does not: the arm reads
     * it, finds nothing, and the source's {@code NOTFND} handling paints
     * {@link #DID_NOT_FIND_ACCTCARD_COMBO}, exactly as it does for a card the operator typed that does
     * not exist.
     *
     * <p>Leading and trailing spaces are stripped before the digit test, because a client echoing a
     * painted screen sends {@code CARDSID} space-padded to its declared width and a padded number is the
     * same number.
     *
     * @param cardNum the path variable, already known to fit {@code CARDSID}
     * @return the number for {@code CDEMO-CARD-NUM}, or {@code 0} when the path states none a
     *         {@code PIC 9(16)} item could hold
     */
    static long carriedCardNumber(String cardNum) {
        String trimmed = cardNum.trim();
        if (trimmed.isEmpty()) {
            return 0L;
        }
        for (int index = 0; index < trimmed.length(); index++) {
            if (trimmed.charAt(index) < '0' || trimmed.charAt(index) > '9') {
                return 0L;
            }
        }
        return Long.parseLong(trimmed);
    }

    static int resolveEibcalen(Integer eibcalen, CardUpdateRequest request) {
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
                    + "because app/cbl/COCRDUPC.cbl:388 uses it to decide whether the conversation's "
                    + "state survives the turn.");
        }
        return stated;
    }

    static byte resolveAttentionIdentifier(Integer eibAid) {
        if (eibAid == null) {
            return CicsAid.DFHENTER;
        }
        int value = eibAid;
        if (value < AID_MIN || value > AID_MAX) {
            throw ScreenInputRejectedException.outsideRange(EIBAID_PARAM,
                    "one EIBAID byte", AID_MIN, AID_MAX);
        }
        return (byte) value;
    }

    PaintedScreen handle(CardUpdateRequest request, int eibcalen, byte eibAid) {
        Objects.requireNonNull(request, "A request is required: COCRDUPC is entered with a terminal "
                + "input area, and an absent one is spaces rather than nothing");

        CardUpdateResponse response = new CardUpdateResponse();
        Conversation task = new Conversation();

        // Every abend from here on is routed to the handler, exactly as the CICS declarative did, and the
        // handler has the last word.
        try {
            main0000(request, response, task, eibcalen, eibAid);
        } catch (AbendException alreadyAbending) {
            throw alreadyAbending;
        } catch (ScreenInputRejectedException callersInput) {
            throw callersInput;
        } catch (RuntimeException abend) {
            throw abendRoutine(task, response, abend);
        }
        return new PaintedScreen(response, request, task.cursorField);
    }

    /**
     * What one execution of {@code COCRDUPC} produced: the painted map, and the {@code DFHMDF} label the
     * cursor was requested on.
     *
     * @param response the painted output map area {@code CCRDUPAO}, never {@code null}
     * @param inputArea the input map area {@code CCRDUPAI} as {@code 3300} left it, carrying the
     *     {@code xxxA} attribute byte and the {@code xxxL} length item of every field; never {@code null}
     * @param cursorField the {@code DFHMDF} label {@code 3300}'s cursor {@code EVALUATE} chose, or
     *     {@code null} when the paragraph did not run - which happens on the {@code XCTL} arm at
     *     {@code :435-476}
     */
    record PaintedScreen(CardUpdateResponse response, CardUpdateRequest inputArea, String cursorField) {
        PaintedScreen {
            Objects.requireNonNull(response, "A painted map is always produced: even the XCTL arm at "
                    + "app/cbl/COCRDUPC.cbl:435-476 returns the commarea it built");
            Objects.requireNonNull(inputArea, "The input map area is always produced: COCRDUPC is "
                    + "entered with CCRDUPAI, and 3300 writes the xxxA attribute of every field into it "
                    + "even on the arms that receive no map");
        }
    }

    void main0000(CardUpdateRequest request, CardUpdateResponse response, Conversation task,
            int eibcalen, byte eibAid) {
        initializeStorage(request, task);

        task.wsTranid = codec.movePicX(LIT_THISTRANID, WS_TRANID_LENGTH);

        task.wsReturnMsg = WS_RETURN_MSG_OFF;

        restoreCommarea(task, eibcalen);

        storePfKeyYYYY(task, eibAid);

        validatePfKey(task);

        dispatch0000(request, response, task);

        if (!task.returned) {
            commonReturn(response, task);
        }
    }

    void initializeStorage(CardUpdateRequest request, Conversation task) {
        // The request's own work area is deliberately NOT copied in: the COBOL discards it too, which is
        // why CCARD-AID has to be re-derived from EIBAID on every turn rather than remembered.
        task.ccWorkArea = new CardScreenState();
        task.ccWorkArea.initializeWorkArea();

        initializeMiscStorage(task);

        task.wsCommarea = CardScreenState.spaces(WS_COMMAREA_LENGTH);

        task.abendData = SystemMessages.AbendData.spaces();

        task.secUserData = SecUserRecord.blank();
        task.customerRecord = new CustomerRecord();

        task.dateHeader = null;
        task.cursorField = null;
        task.returned = false;

        task.carddemoCommarea = request.hasNavigationContext()
                ? request.getNavigationContext()
                : NavigationContext.empty();
        task.thisProgCommarea = request.getCommArea();
    }

    void initializeMiscStorage(Conversation task) {
        task.wsRespCd = 0;
        task.wsReasCd = 0;
        task.wsTranid = CardScreenState.spaces(WS_TRANID_LENGTH);
        task.wsUctrans = CardScreenState.spaces(WS_UCTRANS_LENGTH);

        task.wsInputFlag = FLG_FILTER_BLANK;
        task.wsEditAcctFlag = FLG_FILTER_BLANK;
        task.wsEditCardFlag = FLG_FILTER_BLANK;
        task.wsEditCardnameFlag = FLG_FILTER_BLANK;
        task.wsEditCardstatusFlag = FLG_FILTER_BLANK;
        task.wsEditCardexpmonFlag = FLG_FILTER_BLANK;
        task.wsEditCardexpyearFlag = FLG_FILTER_BLANK;
        task.wsReturnFlag = FLG_FILTER_BLANK;
        task.wsPfkFlag = FLG_FILTER_BLANK;

        task.cardNameCheck = CardScreenState.spaces(CARD_NAME_CHECK_LENGTH);
        task.flgYesNoCheck = FLG_FILTER_BLANK;
        task.cardMonthCheck = CardScreenState.spaces(CARD_MONTH_CHECK_LENGTH);
        task.cardYearCheck = CardScreenState.spaces(CARD_YEAR_CHECK_LENGTH);

        task.cardAcctIdX = CardScreenState.spaces(CardScreenState.CC_ACCT_ID_LENGTH);
        task.cardCvvCdX = CardScreenState.spaces(CardRecord.CARD_CVV_CD_LENGTH);
        task.cardCardNumX = CardScreenState.spaces(CardScreenState.CC_CARD_NUM_LENGTH);
        task.cardNameEmbossedX = CardScreenState.spaces(CardRecord.CARD_EMBOSSED_NAME_LENGTH);
        task.cardStatusX = CardScreenState.spaces(CardRecord.CARD_ACTIVE_STATUS_LENGTH);
        task.cardExpiraionDateX = CardScreenState.spaces(CardRecord.CARD_EXPIRAION_DATE_LENGTH);

        task.wsCardRidCardnum = CardScreenState.spaces(WS_CARD_RID_CARDNUM_LENGTH);
        task.wsCardRidAcctId = codec.movePic9(0L, WS_CARD_RID_ACCT_ID_LENGTH);

        task.errorOpname = CardScreenState.spaces(ERROR_OPNAME_LENGTH);
        task.errorFile = CardScreenState.spaces(ERROR_FILE_LENGTH);
        task.errorResp = CardScreenState.spaces(ERROR_RESP_LENGTH);
        task.errorResp2 = CardScreenState.spaces(ERROR_RESP_LENGTH);

        task.wsLongMsg = CardScreenState.spaces(WS_LONG_MSG_LENGTH);
        task.wsInfoMsg = WS_INFO_MSG_SPACES;
        task.wsReturnMsg = WS_RETURN_MSG_OFF;

        task.cardRecord = Optional.empty();
    }

    void restoreCommarea(Conversation task, int eibcalen) {
        boolean noCommareaPassed = eibcalen == NO_COMMAREA_LENGTH;
        boolean freshEntryFromMenu = LIT_MENUPGM.equals(
                codec.movePicX(task.carddemoCommarea.fromProgram(),
                        NavigationContext.FROM_PROGRAM_LENGTH))
                && !task.carddemoCommarea.isReenter();

        if (noCommareaPassed || freshEntryFromMenu) {
            task.carddemoCommarea = NavigationContext.empty();
            task.thisProgCommarea = CommArea.initialised();
            task.carddemoCommarea = task.carddemoCommarea.withPgmEnter();
            task.setChangeAction(ChangeAction.initial());
            return;
        }

        byte[] dfhcommarea = new byte[PASSED_COMMAREA_LENGTH];
        byte[] passedCommarea = task.carddemoCommarea.toFixedWidth(codec);
        byte[] passedTrailer = task.thisProgCommarea.encode(codec);
        System.arraycopy(passedCommarea, 0, dfhcommarea, 0, NavigationContext.COMMAREA_LENGTH);
        System.arraycopy(passedTrailer, 0, dfhcommarea, NavigationContext.COMMAREA_LENGTH,
                THIS_PROGCOMMAREA_LENGTH);

        byte[] commareaSpan = new byte[NavigationContext.COMMAREA_LENGTH];
        System.arraycopy(dfhcommarea, 0, commareaSpan, 0, NavigationContext.COMMAREA_LENGTH);
        task.carddemoCommarea = NavigationContext.fromFixedWidth(codec, commareaSpan);

        byte[] trailerSpan = new byte[THIS_PROGCOMMAREA_LENGTH];
        System.arraycopy(dfhcommarea, NavigationContext.COMMAREA_LENGTH, trailerSpan, 0,
                THIS_PROGCOMMAREA_LENGTH);
        task.thisProgCommarea = CommArea.decode(trailerSpan, codec);
    }

    void storePfKeyYYYY(Conversation task, byte eibAid) {
        Optional<PfKeyResolver.AidKey> stored =
                PfKeyResolver.storePfKey(eibAid, task.ccWorkArea.aidKey());
        // ifPresent, never orElse: on no match nothing is stored, which is exactly what the copybook's
        // missing WHEN OTHER does.
        stored.ifPresent(task.ccWorkArea::setCcardAidCondition);
    }

    void validatePfKey(Conversation task) {
        task.wsPfkFlag = PFK_INVALID;

        ChangeAction action = task.changeAction();
        boolean accepted = task.ccWorkArea.isCcardAidEnter()
                || task.ccWorkArea.isCcardAidPfk03()
                || (task.ccWorkArea.isCcardAidPfk05() && action.isChangesOkNotConfirmed())
                || (task.ccWorkArea.isCcardAidPfk12() && !action.isDetailsNotFetched());
        if (accepted) {
            task.wsPfkFlag = PFK_VALID;
        }

        if (task.pfkInvalid()) {
            task.ccWorkArea.setCcardAidCondition(PfKeyResolver.AidKey.ENTER);
        }
    }

    void dispatch0000(CardUpdateRequest request, CardUpdateResponse response, Conversation task) {
        if (task.ccWorkArea.isCcardAidPfk03()
                || (task.changeAction().isChangesOkayedAndDone() && lastMapsetIsCardList(task))
                || (task.changeAction().isChangesFailed() && lastMapsetIsCardList(task))) {
            backNavigationPfk03(response, task);
            return;
        }

        if ((task.carddemoCommarea.isEnter() && fromProgramIsCardList(task))
                || (task.ccWorkArea.isCcardAidPfk12() && fromProgramIsCardList(task))) {
            enterFromCardList(request, response, task);
            return;
        }

        if ((task.changeAction().isDetailsNotFetched() && task.carddemoCommarea.isEnter())
                || (fromProgramIsMenu(task) && !task.carddemoCommarea.isReenter())) {
            promptForSearchKeys(request, response, task);
            return;
        }

        if (task.changeAction().isChangesOkayedAndDone() || task.changeAction().isChangesFailed()) {
            resetAfterUpdate(request, response, task);
            return;
        }

        processDecideAndSend(request, response, task);
    }

    boolean lastMapsetIsCardList(Conversation task) {
        return lastMapsetIsCardList(task.carddemoCommarea);
    }

    boolean lastMapsetIsCardList(NavigationContext commarea) {
        return LIT_CCLISTMAPSET.equals(
                codec.movePicX(commarea.lastMapset(), NavigationContext.LAST_MAPSET_LENGTH));
    }

    boolean fromProgramIsCardList(Conversation task) {
        return LIT_CCLISTPGM.equals(codec.movePicX(task.carddemoCommarea.fromProgram(),
                NavigationContext.FROM_PROGRAM_LENGTH));
    }

    boolean fromProgramIsMenu(Conversation task) {
        return LIT_MENUPGM.equals(codec.movePicX(task.carddemoCommarea.fromProgram(),
                NavigationContext.FROM_PROGRAM_LENGTH));
    }

    void backNavigationPfk03(CardUpdateResponse response, Conversation task) {
        task.ccWorkArea.setCcardAidCondition(PfKeyResolver.AidKey.PFK03);

        NavigationContext commarea = task.carddemoCommarea;

        String toTranid = isLowValuesOrSpaces(commarea.fromTranid(),
                NavigationContext.FROM_TRANID_LENGTH)
                        ? LIT_MENUTRANID
                        : commarea.fromTranid();

        String toProgram = isLowValuesOrSpaces(commarea.fromProgram(),
                NavigationContext.FROM_PROGRAM_LENGTH)
                        ? LIT_MENUPGM
                        : commarea.fromProgram();

        commarea = commarea
                .withToTranid(codec.movePicX(toTranid, NavigationContext.TO_TRANID_LENGTH))
                .withToProgram(codec.movePicX(toProgram, NavigationContext.TO_PROGRAM_LENGTH))
                .withFromTranid(codec.movePicX(LIT_THISTRANID, NavigationContext.FROM_TRANID_LENGTH))
                .withFromProgram(codec.movePicX(LIT_THISPGM, NavigationContext.FROM_PROGRAM_LENGTH));

        if (lastMapsetIsCardList(commarea)) {
            commarea = commarea.withAcctId(0L).withCardNum(0L);
        }

        commarea = commarea
                .withUserTypeUser()
                .withPgmEnter()
                .withLastMapset(codec.movePicX(LIT_THISMAPSET, NavigationContext.LAST_MAPSET_LENGTH))
                .withLastMap(codec.movePicX(LIT_THISMAP, NavigationContext.LAST_MAP_LENGTH));

        task.carddemoCommarea = commarea;

        // :473-476 - EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA) The XCTL passes
        // CARDDEMO-COMMAREA and NOTHING ELSE - not WS-THIS-PROGCOMMAREA, which COMMON-RETURN appends at
        // :550-552 but this arm never reaches.
        response.setNavigationContext(commarea);
        response.setCardScreenState(task.ccWorkArea);
        response.setCommArea(CommArea.initialised());
        response.setNextProgram(commarea.toProgram());
        task.returned = true;
    }

    void enterFromCardList(CardUpdateRequest request, CardUpdateResponse response, Conversation task) {
        task.carddemoCommarea = task.carddemoCommarea.withPgmReenter();

        task.wsInputFlag = INPUT_OK;

        task.wsEditAcctFlag = FLG_FILTER_ISVALID;
        task.wsEditCardFlag = FLG_FILTER_ISVALID;

        task.ccWorkArea.setCcAcctIdN(task.carddemoCommarea.acctId());
        task.ccWorkArea.setCcCardNumN(task.carddemoCommarea.cardNum());

        readData9000(task);

        task.setChangeAction(ChangeAction.showDetails());

        sendMap3000(request, response, task);

        commonReturn(response, task);
    }

    void promptForSearchKeys(CardUpdateRequest request, CardUpdateResponse response,
            Conversation task) {
        task.thisProgCommarea = CommArea.initialised();

        sendMap3000(request, response, task);

        task.carddemoCommarea = task.carddemoCommarea.withPgmReenter();

        task.setChangeAction(ChangeAction.initial());

        commonReturn(response, task);
    }

    void resetAfterUpdate(CardUpdateRequest request, CardUpdateResponse response, Conversation task) {
        task.thisProgCommarea = CommArea.initialised();

        initializeMiscStorage(task);

        task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L).withCardNum(0L);

        task.carddemoCommarea = task.carddemoCommarea.withPgmEnter();

        sendMap3000(request, response, task);

        task.carddemoCommarea = task.carddemoCommarea.withPgmReenter();

        task.setChangeAction(ChangeAction.initial());

        commonReturn(response, task);
    }

    void processDecideAndSend(CardUpdateRequest request, CardUpdateResponse response,
            Conversation task) {
        processInputs1000(request, task);

        decideAction2000(task);

        sendMap3000(request, response, task);

        commonReturn(response, task);
    }

    void commonReturn(CardUpdateResponse response, Conversation task) {
        task.ccWorkArea.setCcardErrorMsg(
                codec.movePicX(task.wsReturnMsg, CardScreenState.CCARD_ERROR_MSG_LENGTH));

        task.wsCommarea = composeReturnedCommarea(task);

        response.setNavigationContext(task.carddemoCommarea);
        response.setCommArea(task.thisProgCommarea);
        response.setCardScreenState(task.ccWorkArea);
        response.transferTo(task.ccWorkArea.getCcardNextProg(),
                task.ccWorkArea.getCcardNextMapset(),
                task.ccWorkArea.getCcardNextMap());
        task.returned = true;
    }

    String composeReturnedCommarea(Conversation task) {
        byte[] area = CardScreenState.spaces(WS_COMMAREA_LENGTH).getBytes(codec.charset());
        byte[] commarea = task.carddemoCommarea.toFixedWidth(codec);
        byte[] trailer = task.thisProgCommarea.encode(codec);
        System.arraycopy(commarea, 0, area, 0, NavigationContext.COMMAREA_LENGTH);
        System.arraycopy(trailer, 0, area, NavigationContext.COMMAREA_LENGTH,
                THIS_PROGCOMMAREA_LENGTH);
        return new String(area, codec.charset());
    }

    void processInputs1000(CardUpdateRequest request, Conversation task) {
        receiveMap1100(request, task);

        editMapInputs1200(task);

        task.ccWorkArea.setCcardErrorMsg(
                codec.movePicX(task.wsReturnMsg, CardScreenState.CCARD_ERROR_MSG_LENGTH));

        task.ccWorkArea.setCcardNextProg(
                codec.movePicX(LIT_THISPGM, CardScreenState.CCARD_NEXT_PROG_LENGTH));
        task.ccWorkArea.setCcardNextMapset(
                codec.movePicX(LIT_THISMAPSET, CardScreenState.CCARD_NEXT_MAPSET_LENGTH));
        task.ccWorkArea.setCcardNextMap(
                codec.movePicX(LIT_THISMAP, CardScreenState.CCARD_NEXT_MAP_LENGTH));
    }

    void receiveMap1100(CardUpdateRequest request, Conversation task) {
        CardDetails typed = CardDetails.initialised(DetailGroup.NEW);

        if (isAsteriskOrSpaces(request.getAcctsid(), CardUpdateRequest.ACCTSID_LENGTH)) {
            task.ccWorkArea.setCcAcctIdToLowValues();
            typed = typed.withAcctid(CardScreenState.lowValues(CardDetails.ACCTID_LENGTH));
        } else {
            task.ccWorkArea.setCcAcctId(
                    codec.movePicX(request.getAcctsid(), CardScreenState.CC_ACCT_ID_LENGTH));
            typed = typed.withAcctid(
                    codec.movePicX(request.getAcctsid(), CardDetails.ACCTID_LENGTH));
        }

        if (isAsteriskOrSpaces(request.getCardsid(), CardUpdateRequest.CARDSID_LENGTH)) {
            task.ccWorkArea.setCcCardNumToLowValues();
            typed = typed.withCardid(CardScreenState.lowValues(CardDetails.CARDID_LENGTH));
        } else {
            task.ccWorkArea.setCcCardNum(
                    codec.movePicX(request.getCardsid(), CardScreenState.CC_CARD_NUM_LENGTH));
            typed = typed.withCardid(
                    codec.movePicX(request.getCardsid(), CardDetails.CARDID_LENGTH));
        }

        typed = typed.withCrdname(
                isAsteriskOrSpaces(request.getCrdname(), CardUpdateRequest.CRDNAME_LENGTH)
                        ? CardScreenState.lowValues(CardDetails.CRDNAME_LENGTH)
                        : codec.movePicX(request.getCrdname(), CardDetails.CRDNAME_LENGTH));

        typed = typed.withCrdstcd(
                isAsteriskOrSpaces(request.getCrdstcd(), CardUpdateRequest.CRDSTCD_LENGTH)
                        ? CardScreenState.lowValues(CardDetails.CRDSTCD_LENGTH)
                        : codec.movePicX(request.getCrdstcd(), CardDetails.CRDSTCD_LENGTH));

        typed = typed.withExpday(codec.movePicX(request.getExpday(), CardDetails.EXPDAY_LENGTH));

        typed = typed.withExpmon(
                isAsteriskOrSpaces(request.getExpmon(), CardUpdateRequest.EXPMON_LENGTH)
                        ? CardScreenState.lowValues(CardDetails.EXPMON_LENGTH)
                        : codec.movePicX(request.getExpmon(), CardDetails.EXPMON_LENGTH));

        typed = typed.withExpyear(
                isAsteriskOrSpaces(request.getExpyear(), CardUpdateRequest.EXPYEAR_LENGTH)
                        ? CardScreenState.lowValues(CardDetails.EXPYEAR_LENGTH)
                        : codec.movePicX(request.getExpyear(), CardDetails.EXPYEAR_LENGTH));

        task.setNewDetails(typed);
    }

    static boolean isAsteriskOrSpaces(String value, int length) {
        String image = PIC_X_CODEC.movePicX(value == null ? "" : value, length);
        return PIC_X_CODEC.movePicX(FieldAttributeSetter.ASTERISK, length).equals(image)
                || CardScreenState.spaces(length).equals(image);
    }

    void editMapInputs1200(Conversation task) {
        task.wsInputFlag = INPUT_OK;

        if (task.changeAction().isDetailsNotFetched()) {
            editAccount1210(task);

            editCard1220(task);

            task.setNewDetails(lowValueCarddata(task.newDetails()));

            if (task.flgAcctfilterBlank() && task.flgCardfilterBlank()) {
                task.wsReturnMsg = NO_SEARCH_CRITERIA_RECEIVED;
            }

            return;
        }

        task.wsInfoMsg = FOUND_CARDS_FOR_ACCOUNT;

        task.wsEditAcctFlag = FLG_FILTER_ISVALID;
        task.wsEditCardFlag = FLG_FILTER_ISVALID;

        CardDetails fetched = task.oldDetails();

        task.carddemoCommarea = task.carddemoCommarea
                .withAcctId(codec.decodePic9(codec.movePicX(fetched.acctid(),
                        CardDetails.ACCTID_LENGTH)))
                .withCardNum(codec.decodePic9(codec.movePicX(fetched.cardid(),
                        CardDetails.CARDID_LENGTH)));

        task.cardNameEmbossedX =
                codec.movePicX(fetched.crdname(), CardRecord.CARD_EMBOSSED_NAME_LENGTH);
        task.cardStatusX =
                codec.movePicX(fetched.crdstcd(), CardRecord.CARD_ACTIVE_STATUS_LENGTH);
        task.cardExpiraionDateX = writeExpiryComponents(task.cardExpiraionDateX,
                fetched.expyear(), fetched.expmon(), fetched.expday());

        if (functionUpperCase(task.newDetails().ccupCarddata())
                .equals(functionUpperCase(fetched.ccupCarddata()))) {
            task.wsReturnMsg = NO_CHANGES_DETECTED;
        }

        if (task.noChangesDetected()
                || task.changeAction().isChangesOkNotConfirmed()
                || task.changeAction().isChangesOkayedAndDone()) {
            task.wsEditCardnameFlag = FLG_FILTER_ISVALID;
            task.wsEditCardstatusFlag = FLG_FILTER_ISVALID;
            task.wsEditCardexpmonFlag = FLG_FILTER_ISVALID;
            task.wsEditCardexpyearFlag = FLG_FILTER_ISVALID;
            return;
        }

        runFieldEdits1200(task);
    }

    /**
     * The edit pass of {@code 1200-EDIT-MAP-INPUTS} - {@code app/cbl/COCRDUPC.cbl:696-714}.
     *
     * <p>The four card-data edits, in source order, bracketed by the pessimistic
     * {@code SET CCUP-CHANGES-NOT-OK} at {@code :696} and the
     * {@code SET CCUP-CHANGES-OK-NOT-CONFIRMED} at {@code :713} that only executes when none of them found
     * an error. Order is behaviour, not style: the first edit to fail owns {@code WS-RETURN-MSG}, so moving
     * a call changes the message the operator reads.
     *
     * <p>It is a separate method from {@link #editMapInputs1200} for one reason. The source runs this pass
     * on the turn that produces the confirmation prompt and then skips it at {@code :685-693} on the
     * confirming turn, because on a 3270 the confirmed values and the edited values are necessarily the
     * same bytes. Over HTTP they are two independent messages, so {@link #decideAction2000} re-runs this
     * pass through {@link #editsStillPassBeforeWrite} immediately before {@code 9200-WRITE-PROCESSING} to
     * establish that the values about to be written are the values that actually passed. The pass is
     * idempotent and ends in the state the confirming turn is already in, so for values that do edit clean
     * the re-run changes nothing at all.
     *
     * @param task this task's storage
     */
    void runFieldEdits1200(Conversation task) {
        // :696 - SET CCUP-CHANGES-NOT-OK TO TRUE, before the four edits run
        task.setChangeAction(ChangeAction.changesNotOk());

        editName1230(task);
        editCardStatus1240(task);
        editExpiryMon1250(task);
        editExpiryYear1260(task);

        if (!task.inputError()) {
            task.setChangeAction(ChangeAction.changesOkNotConfirmed());
        }
    }

    /**
     * Re-runs the {@code 1200-EDIT-MAP-INPUTS} edit pass immediately before
     * {@code 9200-WRITE-PROCESSING}, so that what is written is what actually passed validation.
     *
     * <p>Why this exists. {@code CCUP-CHANGES-OK-NOT-CONFIRMED} is the source's record that the four edits
     * ran and all of them passed; on the confirming turn {@code :685-693} therefore skips them and
     * {@code :988-1001} writes. On a 3270 that is sound, because the confirmed field values and the edited
     * field values are the same bytes of the same screen image. Over HTTP the confirmation and the values
     * arrive as two independent messages, so the state saying "these values passed" and the values
     * themselves must be re-associated before anything is persisted. {@link ConversationStateSeal} already
     * establishes that the state is one this route issued; this establishes that it describes the values in
     * hand.
     *
     * <p>Why it changes nothing for a well-behaved caller. {@link #runFieldEdits1200} is a pure function of
     * {@code CCUP-NEW-CARDDATA} - it reads the four typed items and writes only {@code WS-INPUT-FLAG}, the
     * four {@code CICS-*-EDIT-VARS} scratch items, the four field flags, {@code WS-RETURN-MSG} and
     * {@code CCUP-CHANGE-ACTION} - and it ends by setting the very state the confirming turn is already in.
     * When the pass is clean every item it touched is restored to the image the turn had before the check,
     * so the request proceeds through {@code 9200} byte for byte as it did before. Only a caller presenting
     * values that do not edit clean sees any difference, and what it sees is the screen {@code 1200} paints
     * for exactly that input.
     *
     * @param task this task's storage
     * @return whether every edit passed, and so whether the write may proceed
     */
    private boolean editsStillPassBeforeWrite(Conversation task) {
        String inputFlagBefore = task.wsInputFlag;
        String returnMsgBefore = task.wsReturnMsg;
        String cardnameFlagBefore = task.wsEditCardnameFlag;
        String cardstatusFlagBefore = task.wsEditCardstatusFlag;
        String cardexpmonFlagBefore = task.wsEditCardexpmonFlag;
        String cardexpyearFlagBefore = task.wsEditCardexpyearFlag;
        String nameCheckBefore = task.cardNameCheck;
        String yesNoCheckBefore = task.flgYesNoCheck;
        String monthCheckBefore = task.cardMonthCheck;
        String yearCheckBefore = task.cardYearCheck;
        ChangeAction actionBefore = task.changeAction();

        // :643 - SET INPUT-OK TO TRUE, the same clean slate 1200 gives the pass on the turn that runs it.
        task.wsInputFlag = INPUT_OK;
        runFieldEdits1200(task);

        if (task.inputError()) {
            // Leave the pass's own verdicts in place: the four flags carry the highlights, WS-RETURN-MSG
            // carries the message, and CCUP-CHANGES-NOT-OK is the state 1200 leaves on a failed pass.
            return false;
        }

        task.wsInputFlag = inputFlagBefore;
        task.wsReturnMsg = returnMsgBefore;
        task.wsEditCardnameFlag = cardnameFlagBefore;
        task.wsEditCardstatusFlag = cardstatusFlagBefore;
        task.wsEditCardexpmonFlag = cardexpmonFlagBefore;
        task.wsEditCardexpyearFlag = cardexpyearFlagBefore;
        task.cardNameCheck = nameCheckBefore;
        task.flgYesNoCheck = yesNoCheckBefore;
        task.cardMonthCheck = monthCheckBefore;
        task.cardYearCheck = yearCheckBefore;
        task.setChangeAction(actionBefore);
        return true;
    }

    /**
     * {@code MOVE LOW-VALUES TO CCUP-NEW-CARDDATA} - {@code app/cbl/COCRDUPC.cbl:653}.
     *
     * <p>{@code CCUP-NEW-CARDDATA} is the group at {@code :307-313}: the fifty-byte name, the
     * four-, two- and two-byte expiry components and the one-byte status - fifty-nine bytes in all. The
     * account and card identifiers sit <em>outside</em> it, at {@code :304-306}, and are not touched;
     * that is what lets the search keys survive an edit that wipes the card data.
     *
     * @param details the typed detail group
     * @return the same group with its five card-data items at {@code LOW-VALUES}
     */
    static CardDetails lowValueCarddata(CardDetails details) {
        return details
                .withCrdname(CardScreenState.lowValues(CardDetails.CRDNAME_LENGTH))
                .withExpyear(CardScreenState.lowValues(CardDetails.EXPYEAR_LENGTH))
                .withExpmon(CardScreenState.lowValues(CardDetails.EXPMON_LENGTH))
                .withExpday(CardScreenState.lowValues(CardDetails.EXPDAY_LENGTH))
                .withCrdstcd(CardScreenState.lowValues(CardDetails.CRDSTCD_LENGTH));
    }

    String writeExpiryComponents(String current, String year, String month, String day) {
        char[] image = codec.movePicX(current, CardRecord.CARD_EXPIRAION_DATE_LENGTH).toCharArray();
        char[] yearImage = codec.movePicX(year, CardRecord.EXPIRAION_YEAR_END_INDEX
                - CardRecord.EXPIRAION_YEAR_BEGIN_INDEX).toCharArray();
        char[] monthImage = codec.movePicX(month, CardRecord.EXPIRAION_MONTH_END_INDEX
                - CardRecord.EXPIRAION_MONTH_BEGIN_INDEX).toCharArray();
        char[] dayImage = codec.movePicX(day, CardRecord.EXPIRAION_DAY_END_INDEX
                - CardRecord.EXPIRAION_DAY_BEGIN_INDEX).toCharArray();
        System.arraycopy(yearImage, 0, image, CardRecord.EXPIRAION_YEAR_BEGIN_INDEX,
                yearImage.length);
        System.arraycopy(monthImage, 0, image, CardRecord.EXPIRAION_MONTH_BEGIN_INDEX,
                monthImage.length);
        System.arraycopy(dayImage, 0, image, CardRecord.EXPIRAION_DAY_BEGIN_INDEX, dayImage.length);
        return new String(image);
    }

    void editAccount1210(Conversation task) {
        task.wsEditAcctFlag = FLG_FILTER_NOT_OK;

        if (task.ccWorkArea.isCcAcctIdLowValues()
                || task.ccWorkArea.isCcAcctIdSpaces()
                || task.ccWorkArea.isCcAcctIdNZeros()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_BLANK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = WS_PROMPT_FOR_ACCT;
            }
            task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L);
            task.setNewDetails(task.newDetails()
                    .withAcctid(CardScreenState.lowValues(CardDetails.ACCTID_LENGTH)));
            return;
        }

        if (!task.ccWorkArea.isCcAcctIdNumeric()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = ACCOUNT_FILTER_MUST_BE_11_DIGITS;
            }
            task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L);
            task.setNewDetails(task.newDetails()
                    .withAcctid(CardScreenState.lowValues(CardDetails.ACCTID_LENGTH)));
            return;
        }

        task.carddemoCommarea = task.carddemoCommarea
                .withAcctId(codec.decodePic9(task.ccWorkArea.getCcAcctId()));
        task.setNewDetails(task.newDetails()
                .withAcctid(codec.movePicX(task.ccWorkArea.getCcAcctId(),
                        CardDetails.ACCTID_LENGTH)));
        task.wsEditAcctFlag = FLG_FILTER_ISVALID;
    }

    void editCard1220(Conversation task) {
        task.wsEditCardFlag = FLG_FILTER_NOT_OK;

        if (task.ccWorkArea.isCcCardNumLowValues()
                || task.ccWorkArea.isCcCardNumSpaces()
                || task.ccWorkArea.isCcCardNumNZeros()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditCardFlag = FLG_FILTER_BLANK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = WS_PROMPT_FOR_CARD;
            }
            task.carddemoCommarea = task.carddemoCommarea.withCardNum(0L);
            task.setNewDetails(task.newDetails()
                    .withCardid("0".repeat(CardDetails.CARDID_LENGTH)));
            return;
        }

        if (!task.ccWorkArea.isCcCardNumNumeric()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditCardFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = CARD_ID_FILTER_MUST_BE_16_DIGITS;
            }
            task.carddemoCommarea = task.carddemoCommarea.withCardNum(0L);
            task.setNewDetails(task.newDetails()
                    .withCardid(CardScreenState.lowValues(CardDetails.CARDID_LENGTH)));
            return;
        }

        task.carddemoCommarea =
                task.carddemoCommarea.withCardNum(task.ccWorkArea.getCcCardNumN());
        task.setNewDetails(task.newDetails()
                .withCardid(codec.movePicX(task.ccWorkArea.getCcCardNum(),
                        CardDetails.CARDID_LENGTH)));
        task.wsEditCardFlag = FLG_FILTER_ISVALID;
    }

    /**
     * {@code 1230-EDIT-NAME} - {@code app/cbl/COCRDUPC.cbl:806-840}: the embossed name must be supplied and
     * must contain nothing but letters and spaces.
     *
     * @param task this task's storage
     */
    void editName1230(Conversation task) {
        task.wsEditCardnameFlag = FLG_FILTER_NOT_OK;

        String typed = task.newDetails().crdname();

        if (isLowValuesOrSpaces(typed, CardDetails.CRDNAME_LENGTH)
                || isAllZeroCharacters(typed, CardDetails.CRDNAME_LENGTH)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditCardnameFlag = FLG_FILTER_BLANK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = WS_PROMPT_FOR_NAME;
            }
            return;
        }

        task.cardNameCheck = codec.movePicX(typed, CARD_NAME_CHECK_LENGTH);
        task.cardNameCheck =
                inspectConverting(task.cardNameCheck, LIT_ALL_ALPHA_FROM, LIT_ALL_SPACES_TO);

        if (!isTrimmedEmpty(task.cardNameCheck)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditCardnameFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = WS_NAME_MUST_BE_ALPHA;
            }
            return;
        }

        task.wsEditCardnameFlag = FLG_FILTER_ISVALID;
    }

    /**
     * {@code 1240-EDIT-CARDSTATUS} - {@code app/cbl/COCRDUPC.cbl:845-874}: the active-status flag must be
     * {@code 'Y'} or {@code 'N'}.
     *
     * @param task this task's storage
     */
    void editCardStatus1240(Conversation task) {
        task.wsEditCardstatusFlag = FLG_FILTER_NOT_OK;

        String typed = task.newDetails().crdstcd();

        if (isLowValuesOrSpaces(typed, CardDetails.CRDSTCD_LENGTH)
                || isAllZeroCharacters(typed, CardDetails.CRDSTCD_LENGTH)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditCardstatusFlag = FLG_FILTER_BLANK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = CARD_STATUS_MUST_BE_YES_NO;
            }
            return;
        }

        task.flgYesNoCheck = codec.movePicX(typed, CardDetails.CRDSTCD_LENGTH);

        if (task.flgYesNoValid()) {
            task.wsEditCardstatusFlag = FLG_FILTER_ISVALID;
            return;
        }
        task.wsInputFlag = INPUT_ERROR;
        task.wsEditCardstatusFlag = FLG_FILTER_NOT_OK;
        if (task.returnMessageOff()) {
            task.wsReturnMsg = CARD_STATUS_MUST_BE_YES_NO;
        }
    }

    /**
     * {@code 1250-EDIT-EXPIRY-MON} - {@code app/cbl/COCRDUPC.cbl:877-910}: the expiry month must be one to
     * twelve.
     *
     * @param task this task's storage
     */
    void editExpiryMon1250(Conversation task) {
        task.wsEditCardexpmonFlag = FLG_FILTER_NOT_OK;

        String typed = task.newDetails().expmon();

        if (isLowValuesOrSpaces(typed, CardDetails.EXPMON_LENGTH)
                || isAllZeroCharacters(typed, CardDetails.EXPMON_LENGTH)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditCardexpmonFlag = FLG_FILTER_BLANK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = CARD_EXPIRY_MONTH_NOT_VALID;
            }
            return;
        }

        task.cardMonthCheck = codec.movePicX(typed, CARD_MONTH_CHECK_LENGTH);

        long month = task.cardMonthCheckN(codec);
        if (month >= VALID_MONTH_MINIMUM && month <= VALID_MONTH_MAXIMUM) {
            task.wsEditCardexpmonFlag = FLG_FILTER_ISVALID;
            return;
        }
        task.wsInputFlag = INPUT_ERROR;
        task.wsEditCardexpmonFlag = FLG_FILTER_NOT_OK;
        if (task.returnMessageOff()) {
            task.wsReturnMsg = CARD_EXPIRY_MONTH_NOT_VALID;
        }
    }

    /**
     * {@code 1260-EDIT-EXPIRY-YEAR} - {@code app/cbl/COCRDUPC.cbl:913-945}: the expiry year must be 1950 to
     * 2099.
     *
     * @param task this task's storage
     */
    void editExpiryYear1260(Conversation task) {
        String typed = task.newDetails().expyear();

        if (isLowValuesOrSpaces(typed, CardDetails.EXPYEAR_LENGTH)
                || isAllZeroCharacters(typed, CardDetails.EXPYEAR_LENGTH)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditCardexpyearFlag = FLG_FILTER_BLANK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = CARD_EXPIRY_YEAR_NOT_VALID;
            }
            return;
        }

        task.wsEditCardexpyearFlag = FLG_FILTER_NOT_OK;

        task.cardYearCheck = codec.movePicX(typed, CARD_YEAR_CHECK_LENGTH);

        long year = task.cardYearCheckN(codec);
        if (year >= VALID_YEAR_MINIMUM && year <= VALID_YEAR_MAXIMUM) {
            task.wsEditCardexpyearFlag = FLG_FILTER_ISVALID;
            return;
        }
        task.wsInputFlag = INPUT_ERROR;
        task.wsEditCardexpyearFlag = FLG_FILTER_NOT_OK;
        if (task.returnMessageOff()) {
            task.wsReturnMsg = CARD_EXPIRY_YEAR_NOT_VALID;
        }
    }

    void decideAction2000(Conversation task) {
        ChangeAction action = task.changeAction();

        if (action.isDetailsNotFetched() || task.ccWorkArea.isCcardAidPfk12()) {
            if (task.flgAcctfilterIsvalid() && task.flgCardfilterIsvalid()) {
                readData9000(task);
                if (task.foundCardsForAccount()) {
                    task.setChangeAction(ChangeAction.showDetails());
                }
            }
            return;
        }

        if (action.isShowDetails()) {
            if (!task.inputError() && !task.noChangesDetected()) {
                task.setChangeAction(ChangeAction.changesOkNotConfirmed());
            }
            return;
        }

        if (action.isChangesNotOk()) {
            return;
        }

        if (action.isChangesOkNotConfirmed() && task.ccWorkArea.isCcardAidPfk05()) {
            if (!editsStillPassBeforeWrite(task)) {
                // The values on this turn do not edit clean, so there is nothing to confirm. The edits'
                // own flags and message stand and 3000-SEND-MAP repaints them, which is byte for byte the
                // screen the source paints when 1200 finds an error.
                return;
            }
            writeProcessing9200(task);
            return;
        }

        if (action.isChangesOkNotConfirmed()) {
            return;
        }

        if (action.isChangesOkayedAndDone()) {
            task.setChangeAction(ChangeAction.showDetails());
            if (isLowValuesOrSpaces(task.carddemoCommarea.fromTranid(),
                    NavigationContext.FROM_TRANID_LENGTH)) {
                task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L).withCardNum(0L);
                task.carddemoCommarea = task.carddemoCommarea.withAcctStatus(
                        CardScreenState.lowValues(NavigationContext.ACCT_STATUS_LENGTH));
            }
            return;
        }

        unexpectedDataScenario(task);
    }

    void writeProcessing9200(Conversation task) {
        CardUpdateService.WriteResult result = cardUpdateService.writeProcessing(
                task.ccWorkArea, task.oldDetails(), task.newDetails(), task.wsReturnMsg, codec);

        if (result.inputError()) {
            task.wsInputFlag = INPUT_ERROR;
        }

        task.wsReturnMsg = codec.movePicX(result.returnMessage(), WS_RETURN_MSG_LENGTH);

        task.setOldDetails(result.oldDetails());

        result.cardUpdateRecord().ifPresent(staged ->
                task.thisProgCommarea = task.thisProgCommarea.withCardUpdateRecord(staged));

        result.cardUpdateCvvCdImage().ifPresent(image ->
                task.cardCvvCdX = codec.movePicX(image, CardRecord.CARD_CVV_CD_LENGTH));

        result.failedOperation().ifPresent(operation -> {
            task.errorOpname = codec.movePicX(operation, ERROR_OPNAME_LENGTH);
            task.wsRespCd = result.resp();
            task.wsReasCd = result.resp2();
        });

        if (task.couldNotLockForUpdate()) {
            task.setChangeAction(ChangeAction.changesOkayedLockError());
            return;
        }
        if (task.lockedButUpdateFailed()) {
            task.setChangeAction(ChangeAction.changesOkayedButFailed());
            return;
        }
        if (task.dataWasChangedBeforeUpdate()) {
            task.setChangeAction(ChangeAction.showDetails());
            return;
        }
        task.setChangeAction(ChangeAction.changesOkayedAndDone());
    }

    void unexpectedDataScenario(Conversation task) {
        task.abendData = task.abendData
                .withAbendCulprit(codec.movePicX(LIT_THISPGM, SystemMessages.ABEND_CULPRIT_LENGTH))
                .withAbendCode(UNEXPECTED_DATA_ABEND_CODE)
                .withAbendReason(CardScreenState.spaces(SystemMessages.ABEND_REASON_LENGTH))
                .withAbendMsg(UNEXPECTED_DATA_SCENARIO);

        // The routine ends in EXEC CICS ABEND, so it does not come back; the exception is thrown rather
        // than returned so that no statement after this one can execute, exactly as the abend guarantees.
        throw abendRoutine(task, null, null);
    }

    void readData9000(Conversation task) {
        CardDetails snapshot = CardDetails.initialised(DetailGroup.OLD);

        // CC-ACCT-ID is X(11) into CCUP-OLD-ACCTID X(11), CC-CARD-NUM is X(16) into CCUP-OLD-CARDID X(16):
        // equal widths, so no truncation, but routed through the codec regardless because a MOVE is a MOVE.
        snapshot = snapshot
                .withAcctid(codec.movePicX(task.ccWorkArea.getCcAcctId(), CardDetails.ACCTID_LENGTH))
                .withCardid(codec.movePicX(task.ccWorkArea.getCcCardNum(), CardDetails.CARDID_LENGTH));
        task.setOldDetails(snapshot);

        getCardByAcctCard9100(task);

        if (!task.foundCardsForAccount()) {
            return;
        }
        CardRecord record = task.cardRecord.orElseThrow(() -> new IllegalStateException(
                "app/cbl/COCRDUPC.cbl:1352 reads CARD-RECORD under IF FOUND-CARDS-FOR-ACCOUNT, which "
                        + "9100 sets only on DFHRESP(NORMAL) - the arm that populated the INTO area"));

        String foldedName = inspectConverting(record.cardEmbossedName(), LIT_LOWER, LIT_UPPER);
        record = record.withCardEmbossedName(foldedName);
        task.cardRecord = Optional.of(record);

        task.setOldDetails(task.oldDetails()
                .withCvvCd(codec.movePicX(record.cardCvvCdImage(codec), CardDetails.CVV_CD_LENGTH))
                .withCrdname(codec.movePicX(foldedName, CardDetails.CRDNAME_LENGTH))
                .withExpyear(codec.movePicX(record.cardExpiraionDateYear(), CardDetails.EXPYEAR_LENGTH))
                .withExpmon(codec.movePicX(record.cardExpiraionDateMonth(), CardDetails.EXPMON_LENGTH))
                .withExpday(codec.movePicX(record.cardExpiraionDateDay(), CardDetails.EXPDAY_LENGTH))
                .withCrdstcd(codec.movePicX(record.cardActiveStatus(), CardDetails.CRDSTCD_LENGTH)));
    }

    void getCardByAcctCard9100(Conversation task) {
        task.wsCardRidCardnum = codec.movePicX(task.ccWorkArea.getCcCardNum(), WS_CARD_RID_CARDNUM_LENGTH);

        // KEYLENGTH is LENGTH OF WS-CARD-RID-CARDNUM, i.e. the full 16, so this is a fully-qualified keyed
        // read and never a generic browse.
        CardRepository.CardReadResult result = cardRepository.readByCardNumber(task.wsCardRidCardnum);
        task.wsRespCd = result.resp();
        task.wsReasCd = result.resp2();

        if (result.isNormal()) {
            task.cardRecord = Optional.of(result.requireRecord());
            task.wsInfoMsg = FOUND_CARDS_FOR_ACCOUNT;
            return;
        }
        if (result.isNotFound()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            task.wsEditCardFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = DID_NOT_FIND_ACCTCARD_COMBO;
            }
            return;
        }

        task.wsInputFlag = INPUT_ERROR;
        if (task.returnMessageOff()) {
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
        }
        recordFileError(task, READ_OPERATION_NAME, LIT_CARDFILENAME);
    }

    void recordFileError(Conversation task, String operation, String fileName) {
        task.errorOpname = codec.movePicX(operation, ERROR_OPNAME_LENGTH);
        task.errorFile = codec.movePicX(fileName, ERROR_FILE_LENGTH);
        task.errorResp = responseCodeImage(task.wsRespCd);
        task.errorResp2 = responseCodeImage(task.wsReasCd);
        task.wsReturnMsg = codec.movePicX(fileErrorMessage(task), WS_RETURN_MSG_LENGTH);
    }

    /**
     * {@code WS-FILE-ERROR-MESSAGE} - {@code app/cbl/COCRDUPC.cbl:133-152} - composed from its eight spans,
     * in declaration order, to exactly {@link #FILE_ERROR_MESSAGE_LENGTH} characters.
     *
     * @param task this task's storage, supplying the four variable spans
     * @return the composed message, exactly {@link #FILE_ERROR_MESSAGE_LENGTH} characters
     * @throws IllegalStateException if the composition is not exactly that wide
     */
    String fileErrorMessage(Conversation task) {
        String composed = FILE_ERROR_PREFIX
                + codec.movePicX(task.errorOpname, ERROR_OPNAME_LENGTH)
                + FILE_ERROR_ON
                + codec.movePicX(task.errorFile, ERROR_FILE_LENGTH)
                + FILE_ERROR_RETURNED_RESP
                + codec.movePicX(task.errorResp, ERROR_RESP_LENGTH)
                + FILE_ERROR_RESP2
                + codec.movePicX(task.errorResp2, ERROR_RESP_LENGTH)
                + FILE_ERROR_TRAILER;
        if (composed.length() != FILE_ERROR_MESSAGE_LENGTH) {
            throw new IllegalStateException("WS-FILE-ERROR-MESSAGE at app/cbl/COCRDUPC.cbl:133-152 is "
                    + FILE_ERROR_MESSAGE_LENGTH + " characters - 12+8+4+9+15+10+7+10+5 - but this "
                    + "composition is " + composed.length());
        }
        return composed;
    }

    String responseCodeImage(int responseCode) {
        String digits = codec.movePic9(Math.abs((long) responseCode), RESP_CODE_DIGITS);
        return codec.movePicX(digits, ERROR_RESP_LENGTH);
    }

    void sendMap3000(CardUpdateRequest request, CardUpdateResponse response, Conversation task) {
        screenInit3100(response, task);
        setupScreenVars3200(response, task);
        setupInfomsg3250(response, task);
        setupScreenAttrs3300(request, response, task);
        sendScreen3400(response, task);
    }

    void screenInit3100(CardUpdateResponse response, Conversation task) {
        DateHeader firstReading = DateHeader.from(codec, clock).withCurdateMmDdYyFromTimestamp();

        DateHeader secondReading = DateHeader.from(codec, clock).withCurdateMmDdYyFromTimestamp();
        task.dateHeader = secondReading;

        if (LOG.isTraceEnabled() && !firstReading.wsTimestamp().equals(secondReading.wsTimestamp())) {
            LOG.trace("app/cbl/COCRDUPC.cbl:1055 and :1062 both evaluate FUNCTION CURRENT-DATE and the "
                    + "readings differ; the second is used, as the source does");
        }

        response.screenInit(secondReading);
    }

    void setupScreenVars3200(CardUpdateResponse response, Conversation task) {
        if (task.carddemoCommarea.isEnter()) {
            return;
        }

        if (task.ccWorkArea.isCcAcctIdNZeros()) {
            response.setOutputItem(CardUpdateResponse.ACCTSID,
                    CardScreenState.lowValues(CardUpdateResponse.ACCTSIDO_LENGTH));
        } else {
            response.setOutputItem(CardUpdateResponse.ACCTSID, task.ccWorkArea.getCcAcctId());
        }

        if (task.ccWorkArea.isCcCardNumNZeros()) {
            response.setOutputItem(CardUpdateResponse.CARDSID,
                    CardScreenState.lowValues(CardUpdateResponse.CARDSIDO_LENGTH));
        } else {
            response.setOutputItem(CardUpdateResponse.CARDSID, task.ccWorkArea.getCcCardNum());
        }

        ChangeAction action = task.changeAction();
        if (action.isDetailsNotFetched()) {
            response.setOutputItem(CardUpdateResponse.CRDNAME,
                    CardScreenState.lowValues(CardUpdateResponse.CRDNAMEO_LENGTH));
            response.setOutputItem(CardUpdateResponse.CRDSTCD,
                    CardScreenState.lowValues(CardUpdateResponse.CRDSTCDO_LENGTH));
            response.setOutputItem(CardUpdateResponse.EXPDAY,
                    CardScreenState.lowValues(CardUpdateResponse.EXPDAYO_LENGTH));
            response.setOutputItem(CardUpdateResponse.EXPMON,
                    CardScreenState.lowValues(CardUpdateResponse.EXPMONO_LENGTH));
            response.setOutputItem(CardUpdateResponse.EXPYEAR,
                    CardScreenState.lowValues(CardUpdateResponse.EXPYEARO_LENGTH));
            return;
        }
        if (action.isShowDetails()) {
            paintDetailItems3200(response, task.oldDetails(), task.oldDetails().expday());
            return;
        }
        if (action.isChangesMade()) {
            paintDetailItems3200(response, task.newDetails(), task.oldDetails().expday());
            return;
        }
        paintDetailItems3200(response, task.oldDetails(), task.oldDetails().expday());
    }

    private void paintDetailItems3200(CardUpdateResponse response, CardDetails details, String expday) {
        response.setOutputItem(CardUpdateResponse.CRDNAME, details.crdname());
        response.setOutputItem(CardUpdateResponse.CRDSTCD, details.crdstcd());
        response.setOutputItem(CardUpdateResponse.EXPDAY, expday);
        response.setOutputItem(CardUpdateResponse.EXPMON, details.expmon());
        response.setOutputItem(CardUpdateResponse.EXPYEAR, details.expyear());
    }

    void setupInfomsg3250(CardUpdateResponse response, Conversation task) {
        ChangeAction action = task.changeAction();

        if (task.carddemoCommarea.isEnter()) {
            task.wsInfoMsg = PROMPT_FOR_SEARCH_KEYS;
        } else if (action.isDetailsNotFetched()) {
            task.wsInfoMsg = PROMPT_FOR_SEARCH_KEYS;
        } else if (action.isShowDetails()) {
            task.wsInfoMsg = FOUND_CARDS_FOR_ACCOUNT;
        } else if (action.isChangesNotOk()) {
            task.wsInfoMsg = PROMPT_FOR_CHANGES;
        } else if (action.isChangesOkNotConfirmed()) {
            task.wsInfoMsg = PROMPT_FOR_CONFIRMATION;
        } else if (action.isChangesOkayedAndDone()) {
            task.wsInfoMsg = CONFIRM_UPDATE_SUCCESS;
        } else if (action.isChangesOkayedLockError()) {
            task.wsInfoMsg = INFORM_FAILURE;
        } else if (action.isChangesOkayedButFailed()) {
            task.wsInfoMsg = INFORM_FAILURE;
        } else if (task.noInfoMessage()) {
            task.wsInfoMsg = PROMPT_FOR_SEARCH_KEYS;
        }

        response.setOutputItem(CardUpdateResponse.INFOMSG, task.wsInfoMsg);
        response.setOutputItem(CardUpdateResponse.ERRMSG, task.wsReturnMsg);
    }

    void setupScreenAttrs3300(CardUpdateRequest request, CardUpdateResponse response,
            Conversation task) {
        protectOrUnprotect3300(request, task);
        positionCursor3300(request, task);
        setupColour3300(response, task);
        messageAttributes3300(request, task);
    }

    void protectOrUnprotect3300(CardUpdateRequest request, Conversation task) {
        ChangeAction action = task.changeAction();

        if (action.isDetailsNotFetched()) {
            putFieldAttribute(request, CardUpdateRequest.ACCTSID_FIELD, BmsAttributes.DFHBMFSE);
            putFieldAttribute(request, CardUpdateRequest.CARDSID_FIELD, BmsAttributes.DFHBMFSE);
            putFieldAttribute(request, CardUpdateRequest.CRDNAME_FIELD, BmsAttributes.DFHBMPRF);
            putFieldAttribute(request, CardUpdateRequest.CRDSTCD_FIELD, BmsAttributes.DFHBMPRF);
            putFieldAttribute(request, CardUpdateRequest.EXPMON_FIELD, BmsAttributes.DFHBMPRF);
            putFieldAttribute(request, CardUpdateRequest.EXPYEAR_FIELD, BmsAttributes.DFHBMPRF);
            return;
        }
        if (action.isShowDetails() || action.isChangesNotOk()) {
            putFieldAttribute(request, CardUpdateRequest.ACCTSID_FIELD, BmsAttributes.DFHBMPRF);
            putFieldAttribute(request, CardUpdateRequest.CARDSID_FIELD, BmsAttributes.DFHBMPRF);
            putFieldAttribute(request, CardUpdateRequest.CRDNAME_FIELD, BmsAttributes.DFHBMFSE);
            putFieldAttribute(request, CardUpdateRequest.CRDSTCD_FIELD, BmsAttributes.DFHBMFSE);
            putFieldAttribute(request, CardUpdateRequest.EXPMON_FIELD, BmsAttributes.DFHBMFSE);
            putFieldAttribute(request, CardUpdateRequest.EXPYEAR_FIELD, BmsAttributes.DFHBMFSE);
            return;
        }
        if (action.isChangesOkNotConfirmed() || action.isChangesOkayedAndDone()) {
            putFieldAttribute(request, CardUpdateRequest.ACCTSID_FIELD, BmsAttributes.DFHBMPRF);
            putFieldAttribute(request, CardUpdateRequest.CARDSID_FIELD, BmsAttributes.DFHBMPRF);
            putFieldAttribute(request, CardUpdateRequest.CRDNAME_FIELD, BmsAttributes.DFHBMPRF);
            putFieldAttribute(request, CardUpdateRequest.CRDSTCD_FIELD, BmsAttributes.DFHBMPRF);
            putFieldAttribute(request, CardUpdateRequest.EXPMON_FIELD, BmsAttributes.DFHBMPRF);
            putFieldAttribute(request, CardUpdateRequest.EXPYEAR_FIELD, BmsAttributes.DFHBMPRF);
            return;
        }
        putFieldAttribute(request, CardUpdateRequest.ACCTSID_FIELD, BmsAttributes.DFHBMFSE);
        putFieldAttribute(request, CardUpdateRequest.CARDSID_FIELD, BmsAttributes.DFHBMFSE);
        putFieldAttribute(request, CardUpdateRequest.CRDNAME_FIELD, BmsAttributes.DFHBMPRF);
        putFieldAttribute(request, CardUpdateRequest.CRDSTCD_FIELD, BmsAttributes.DFHBMPRF);
        putFieldAttribute(request, CardUpdateRequest.EXPMON_FIELD, BmsAttributes.DFHBMPRF);
        putFieldAttribute(request, CardUpdateRequest.EXPYEAR_FIELD, BmsAttributes.DFHBMPRF);
    }

    void positionCursor3300(CardUpdateRequest request, Conversation task) {
        if (task.foundCardsForAccount() || task.noChangesDetected()) {
            placeCursor3300(request, task, CardUpdateResponse.CRDNAME);
            return;
        }
        if (task.flgAcctfilterNotOk() || task.flgAcctfilterBlank()) {
            placeCursor3300(request, task, CardUpdateResponse.ACCTSID);
            return;
        }
        if (task.flgCardfilterNotOk() || task.flgCardfilterBlank()) {
            placeCursor3300(request, task, CardUpdateResponse.CARDSID);
            return;
        }
        if (task.flgCardnameNotOk() || task.flgCardnameBlank()) {
            placeCursor3300(request, task, CardUpdateResponse.CRDNAME);
            return;
        }
        if (task.flgCardstatusNotOk() || task.flgCardstatusBlank()) {
            placeCursor3300(request, task, CardUpdateResponse.CRDSTCD);
            return;
        }
        if (task.flgCardexpmonNotOk() || task.flgCardexpmonBlank()) {
            placeCursor3300(request, task, CardUpdateResponse.EXPMON);
            return;
        }
        if (task.flgCardexpyearNotOk() || task.flgCardexpyearBlank()) {
            placeCursor3300(request, task, CardUpdateResponse.EXPYEAR);
            return;
        }
        placeCursor3300(request, task, CardUpdateResponse.ACCTSID);
    }

    void placeCursor3300(CardUpdateRequest request, Conversation task, String label) {
        request.putFieldMetadata(request.metadataFor(label)
                .withLengthItem(CardUpdateRequest.FieldMetadata.CURSOR_LENGTH_ITEM));
        task.cursorField = label;
    }

    void setupColour3300(CardUpdateResponse response, Conversation task) {
        if (lastMapsetIsCardList(task)) {
            response.setColour(CardUpdateResponse.ACCTSID, BmsAttributes.DFHDFCOL);
            response.setColour(CardUpdateResponse.CARDSID, BmsAttributes.DFHDFCOL);
        }

        boolean reenter = task.carddemoCommarea.isReenter();
        boolean changesNotOk = task.changeAction().isChangesNotOk();

        if (task.flgAcctfilterNotOk()) {
            response.setColour(CardUpdateResponse.ACCTSID, BmsAttributes.DFHRED);
        }
        if (task.flgAcctfilterBlank()) {
            response.applyHighlight(CardUpdateResponse.ACCTSID, FieldValidationState.BLANK, reenter);
        }

        if (task.flgCardfilterNotOk()) {
            response.setColour(CardUpdateResponse.CARDSID, BmsAttributes.DFHRED);
        }
        if (task.flgCardfilterBlank()) {
            response.applyHighlight(CardUpdateResponse.CARDSID, FieldValidationState.BLANK, reenter);
        }

        if (task.flgCardnameNotOk() && changesNotOk) {
            response.setColour(CardUpdateResponse.CRDNAME, BmsAttributes.DFHRED);
        }
        if (task.flgCardnameBlank()) {
            response.applyHighlight(CardUpdateResponse.CRDNAME, FieldValidationState.BLANK,
                    changesNotOk);
        }

        if (task.flgCardstatusNotOk() && changesNotOk) {
            response.setColour(CardUpdateResponse.CRDSTCD, BmsAttributes.DFHRED);
        }
        if (task.flgCardstatusBlank()) {
            response.applyHighlight(CardUpdateResponse.CRDSTCD, FieldValidationState.BLANK,
                    changesNotOk);
        }

        response.setColour(CardUpdateResponse.EXPDAY, BmsAttributes.DFHBMDAR);

        if (task.flgCardexpmonNotOk() && changesNotOk) {
            response.setColour(CardUpdateResponse.EXPMON, BmsAttributes.DFHRED);
        }
        if (task.flgCardexpmonBlank()) {
            response.applyHighlight(CardUpdateResponse.EXPMON, FieldValidationState.BLANK,
                    changesNotOk);
        }

        if (task.flgCardexpyearNotOk() && changesNotOk) {
            response.setColour(CardUpdateResponse.EXPYEAR, BmsAttributes.DFHRED);
        }
        if (task.flgCardexpyearBlank()) {
            response.applyHighlight(CardUpdateResponse.EXPYEAR, FieldValidationState.BLANK,
                    changesNotOk);
        }
    }

    void messageAttributes3300(CardUpdateRequest request, Conversation task) {
        putFieldAttribute(request, CardUpdateRequest.INFOMSG_FIELD,
                task.noInfoMessage() ? BmsAttributes.DFHBMDAR : BmsAttributes.DFHBMBRY);

        if (task.promptForConfirmation()) {
            putFieldAttribute(request, CardUpdateRequest.FKEYSC_FIELD, BmsAttributes.DFHBMBRY);
        }
    }

    void putFieldAttribute(CardUpdateRequest request, String label, byte attribute) {
        request.putFieldMetadata(request.metadataFor(label)
                .withAttributeItem(String.valueOf((char) (attribute & 0xFF))));
    }

    void sendScreen3400(CardUpdateResponse response, Conversation task) {
        task.ccWorkArea.setCcardNextMapset(
                codec.movePicX(LIT_THISMAPSET, CardScreenState.CCARD_NEXT_MAPSET_LENGTH));
        task.ccWorkArea.setCcardNextMap(codec.movePicX(LIT_THISMAP, CardScreenState.CCARD_NEXT_MAP_LENGTH));

        response.setNextMapset(task.ccWorkArea.getCcardNextMapset());
        response.setNextMap(task.ccWorkArea.getCcardNextMap());

        // RESP is set to the normal condition because serialising a response cannot fail the way a terminal
        // write can, and leaving WS-RESP-CD holding a stale file-read RESP would misreport what happened
        // last.
        task.wsRespCd = FileStatus.NORMAL;
    }

    ScreenMetadata screenMetadataOf(CardUpdateResponse response, CardUpdateRequest inputArea,
            String cursorField) {
        Objects.requireNonNull(response, "The painted output area is required to project its metadata");
        Objects.requireNonNull(inputArea, "The input area is required: it is where 3300 wrote the xxxA "
                + "attribute byte of every field, and without it the protection half of every quad "
                + "would be reported as the LOW-VALUES the output area was initialised with");
        Map<String, ScreenMetadata.FieldMetadata> quads = new LinkedHashMap<>();
        for (CardUpdateResponse.ScreenField field : CardUpdateResponse.namedFields()) {
            CardUpdateResponse.FieldAttributes attributes = response.attributesOf(field.name());
            byte attribute = attributeByteOf(inputArea, field.name());
            quads.put(field.name(), ScreenMetadata.FieldMetadata.of(attributes.getColour(),
                    attribute, attributes.getHilight(), attributes.getValidn()));
        }
        return ScreenMetadata.of(cursorField,
                response.colourOf(CardUpdateResponse.ERRMSG),
                true,
                quads);
    }

    static byte attributeByteOf(CardUpdateRequest inputArea, String label) {
        return (byte) (inputArea.metadataFor(label).attributeItem().charAt(0) & 0xFF);
    }

    AbendException abendRoutine(Conversation task, CardUpdateResponse response, RuntimeException cause) {
        SystemMessages.AbendData abendData =
                task.abendData == null ? SystemMessages.AbendData.spaces() : task.abendData;

        if (CardScreenState.lowValues(SystemMessages.ABEND_MSG_LENGTH).equals(abendData.abendMsg())) {
            abendData = abendData.withAbendMsg(UNEXPECTED_ABEND_OCCURRED);
        }
        abendData = abendData
                .withAbendCulprit(codec.movePicX(LIT_THISPGM, SystemMessages.ABEND_CULPRIT_LENGTH))
                .toDeclaredWidths();
        task.abendData = abendData;

        LOG.error("app/cbl/COCRDUPC.cbl:1531 ABEND-ROUTINE: " + abendDataImage(abendData)
                + " ABCODE " + ABEND_ROUTINE_ABCODE + " ["
                + (cause == null ? NO_TRIGGERING_FAILURE : BackendDiagnostic.of(cause).describe())
                + ']');

        if (response != null) {
            response.setErrmsgo(codec.movePicX(abendData.abendMsg(),
                    CardUpdateResponse.ERRMSGO_LENGTH));
        }
        task.returned = true;

        return AbendException.withoutAbendParameters(LIT_THISPGM,
                        AbendException.RETURN_CODE_IO_ERROR,
                        "ABCODE " + ABEND_ROUTINE_ABCODE + ": " + abendData.abendMsg().trim(), cause)
                .withSourceDiagnostic(abendDataImage(abendData));
    }

    String abendDataImage(SystemMessages.AbendData abendData) {
        SystemMessages.AbendData atWidth = abendData.toDeclaredWidths();
        return atWidth.abendCode() + atWidth.abendCulprit() + atWidth.abendReason()
                + atWidth.abendMsg();
    }
}
