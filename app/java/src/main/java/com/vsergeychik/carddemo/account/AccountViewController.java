package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.dto.AccountViewRequest;
import com.vsergeychik.carddemo.account.dto.AccountViewResponse;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
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
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import jakarta.validation.Valid;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code COACTVWC} - "Accept and process Account View request" - translated paragraph for paragraph from
 * {@code app/cbl/COACTVWC.cbl} (941 lines) and published as CICS transaction {@code CAVW}'s REST
 * projection, {@code GET /api/accounts/&#123;acctId&#125;}.
 *
 * <p>Consequently the guards {@code IF DID-NOT-FIND-ACCT-IN-ACCTDAT} at {@code :704} and
 * {@code IF DID-NOT-FIND-CUST-IN-CUSTDAT} at {@code :713} never fire, so an account that is missing from
 * the master file still falls through to the customer read.
 */
@RestController
public class AccountViewController {
    private static final Log LOG = LogFactory.getLog(AccountViewController.class);

    static final String LIT_THISPGM = "COACTVWC";

    static final String LIT_THISTRANID = "CAVW";

    static final String LIT_THISMAPSET = "COACTVW ";

    static final String LIT_THISMAP = "CACTVWA";

    static final String LIT_CCLISTPGM = "COCRDLIC";

    static final String LIT_CCLISTTRANID = "CCLI";

    static final String LIT_CCLISTMAPSET = "COCRDLI";

    static final String LIT_CCLISTMAP = "CCRDSLA";

    static final String LIT_CARDUPDATEPGM = "COCRDUPC";

    static final String LIT_CARDUDPATETRANID = "CCUP";

    static final String LIT_CARDUPDATEMAPSET = "COCRDUP ";

    static final String LIT_CARDUPDATEMAP = "CCRDUPA";

    static final String LIT_MENUPGM = "COMEN01C";

    static final String LIT_MENUTRANID = "CM00";

    static final String LIT_MENUMAPSET = "COMEN01";

    static final String LIT_MENUMAP = "COMEN1A";

    static final String LIT_CARDDTLPGM = "COCRDSLC";

    static final String LIT_CARDDTLTRANID = "CCDL";

    static final String LIT_CARDDTLMAPSET = "COCRDSL";

    static final String LIT_CARDDTLMAP = "CCRDSLA";

    static final int CICS_FILE_NAME_LENGTH = 8;

    static final String LIT_ALL_ALPHA_FROM =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    static final String LIT_ALL_SPACES_TO = CardScreenState.spaces(52);

    static final String LIT_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    static final String LIT_LOWER = "abcdefghijklmnopqrstuvwxyz";

    // The PIC X move rule, and nothing else. movePicX, movePic9 and decodePic9 are pure character
    // operations - they left justify, truncate on the right and pad with spaces, exactly as COBOL does -
    // and none of them consults the charset, so the page this codec carries cannot change their result.

    private static final FixedWidthCodec PIC_X_CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    static final String LIT_ACCTFILENAME =
            PIC_X_CODEC.movePicX(AccountRepository.CICS_FILE_NAME, CICS_FILE_NAME_LENGTH);

    static final String LIT_CARDFILENAME = "CARDDAT ";

    static final String LIT_CUSTFILENAME =
            PIC_X_CODEC.movePicX(CustomerRepository.CICS_FILE_NAME, CICS_FILE_NAME_LENGTH);

    static final String LIT_CARDFILENAME_ACCT_PATH = "CARDAIX ";

    static final String LIT_CARDXREFNAME_ACCT_PATH = PIC_X_CODEC.movePicX(
            CardXrefRepository.ALTERNATE_INDEX_DD_NAME, CICS_FILE_NAME_LENGTH);

    static final int WS_TRANID_LENGTH = 4;

    static final int WS_CARD_RID_CARDNUM_LENGTH = CardScreenState.CC_CARD_NUM_LENGTH;

    static final int WS_CARD_RID_CUST_ID_LENGTH = CardScreenState.CC_CUST_ID_LENGTH;

    static final int WS_CARD_RID_ACCT_ID_LENGTH = CardScreenState.CC_ACCT_ID_LENGTH;

    static final int WS_LONG_MSG_LENGTH = 500;

    static final int WS_INFO_MSG_LENGTH = AccountViewResponse.WS_INFO_MSG_LENGTH;

    static final int WS_RETURN_MSG_LENGTH = AccountViewResponse.WS_RETURN_MSG_LENGTH;

    static final int WS_COMMAREA_LENGTH = 2000;

    static final int THIS_PROGCOMMAREA_LENGTH =
            ThisProgCommarea.CA_FROM_PROGRAM_LENGTH + ThisProgCommarea.CA_FROM_TRANID_LENGTH;

    static final int PASSED_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + THIS_PROGCOMMAREA_LENGTH;

    static final int NO_COMMAREA_LENGTH = 0;

    static final String INPUT_OK = "0";

    static final String INPUT_ERROR = "1";

    static final String INPUT_PENDING = "\u0000";

    static final String PFK_VALID = "0";

    static final String PFK_INVALID = "1";

    static final String FLG_FILTER_NOT_OK = "0";

    static final String FLG_FILTER_ISVALID = "1";

    static final String FLG_FILTER_BLANK = " ";

    static final String FOUND_IN_MASTER = "1";

    static final String INITIALIZED_FLAG = " ";

    static final String WS_INFO_MSG_SPACES = CardScreenState.spaces(WS_INFO_MSG_LENGTH);

    static final String WS_INFO_MSG_LOW_VALUES = CardScreenState.lowValues(WS_INFO_MSG_LENGTH);

    static final String WS_PROMPT_FOR_INPUT_TEXT = "Enter or update id of account to display";

    static final String WS_PROMPT_FOR_INPUT =
            PIC_X_CODEC.movePicX(WS_PROMPT_FOR_INPUT_TEXT, WS_INFO_MSG_LENGTH);

    static final String WS_INFORM_OUTPUT_TEXT = "Displaying details of given Account";

    static final String WS_INFORM_OUTPUT =
            PIC_X_CODEC.movePicX(WS_INFORM_OUTPUT_TEXT, WS_INFO_MSG_LENGTH);

    static final String WS_RETURN_MSG_OFF = CardScreenState.spaces(WS_RETURN_MSG_LENGTH);

    static final String WS_EXIT_MESSAGE =
            PIC_X_CODEC.movePicX("PF03 pressed.Exiting              ", WS_RETURN_MSG_LENGTH);

    static final String WS_PROMPT_FOR_ACCT =
            PIC_X_CODEC.movePicX("Account number not provided", WS_RETURN_MSG_LENGTH);

    static final String NO_SEARCH_CRITERIA_RECEIVED =
            PIC_X_CODEC.movePicX("No input received", WS_RETURN_MSG_LENGTH);

    static final String SEARCHED_ACCT_TEXT = "Account number must be a non zero 11 digit number";

    static final String SEARCHED_ACCT_ZEROES =
            PIC_X_CODEC.movePicX(SEARCHED_ACCT_TEXT, WS_RETURN_MSG_LENGTH);

    static final String SEARCHED_ACCT_NOT_NUMERIC = SEARCHED_ACCT_ZEROES;

    static final String DID_NOT_FIND_ACCT_IN_CARDXREF = PIC_X_CODEC.movePicX(
            "Did not find this account in account card xref file", WS_RETURN_MSG_LENGTH);

    static final String DID_NOT_FIND_ACCT_IN_ACCTDAT = PIC_X_CODEC.movePicX(
            "Did not find this account in account master file", WS_RETURN_MSG_LENGTH);

    static final String DID_NOT_FIND_CUST_IN_CUSTDAT = PIC_X_CODEC.movePicX(
            "Did not find associated customer in master file", WS_RETURN_MSG_LENGTH);

    static final String XREF_READ_ERROR =
            PIC_X_CODEC.movePicX("Error reading account card xref File", WS_RETURN_MSG_LENGTH);

    static final String CODING_TO_BE_DONE =
            PIC_X_CODEC.movePicX("Looks Good.... so far", WS_RETURN_MSG_LENGTH);

    static final String ACCOUNT_FILTER_NOT_NUMERIC_TEXT =
            "Account Filter must  be a non-zero 11 digit number";

    static final String ACCOUNT_FILTER_NOT_NUMERIC =
            PIC_X_CODEC.movePicX(ACCOUNT_FILTER_NOT_NUMERIC_TEXT, WS_RETURN_MSG_LENGTH);

    static final String UNEXPECTED_DATA_SCENARIO =
            PIC_X_CODEC.movePicX("UNEXPECTED DATA SCENARIO", WS_RETURN_MSG_LENGTH);

    static final String UNEXPECTED_DATA_ABEND_CODE = "0001";

    static final String UNEXPECTED_ABEND_OCCURRED = PIC_X_CODEC.movePicX(
            "UNEXPECTED ABEND OCCURRED.", SystemMessages.ABEND_MSG_LENGTH);

    static final String ABEND_ROUTINE_ABCODE = "9999";

    static final String BLANK_FIELD_MARKER = AccountViewResponse.BLANK_FIELD_MARKER;

    // Eight items summing to exactly 80 characters, transcribed part by part so a reviewer can add them up
    // against the copybook.

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

    static final String READ_OPERATION_NAME = "READ";

    // Each concatenation overflows WS-RETURN-MSG PIC X(75) and is therefore truncated, which is what
    // COBOL's STRING does when the receiver fills up.

    static final String STRING_ACCOUNT_PREFIX = "Account:";

    static final String STRING_NOT_FOUND_IN = " not found in";

    static final String STRING_CROSS_REF_FILE = " Cross ref file.  Resp:";

    static final String STRING_ACCT_MASTER_FILE = " Acct Master file.Resp:";

    static final String STRING_REAS = " Reas:";

    static final String STRING_CUSTID_PREFIX = "CustId:";

    static final String STRING_NOT_FOUND = " not found";

    static final String STRING_IN_CUSTOMER_MASTER = " in customer master.Resp: ";

    static final String STRING_REAS_UPPER = " REAS:";

    static final String EIBAID_PARAM = "eibaid";

    static final String EIBAID_PARAM_ALIAS = "eibAid";

    static final String EIBCALEN_PARAM = "eibcalen";

    static final String ACCTSID_MEMBER = "acctsid";
    static final String NO_CRITERION_IMAGE = "*";

    private static final int AID_MIN = 0;

    private static final int AID_MAX = 255;

    static {
        if (FILE_ERROR_MESSAGE_LENGTH != 80) {
            throw new AssertionError("WS-FILE-ERROR-MESSAGE is declared as eight items summing to 80 "
                    + "characters at app/cbl/COACTVWC.cbl:86-105, but the transcribed parts sum to "
                    + FILE_ERROR_MESSAGE_LENGTH + "; a filler has been mistranscribed");
        }
        if (PASSED_COMMAREA_LENGTH != 172) {
            throw new AssertionError("The area app/cbl/COACTVWC.cbl:288-292 splits must be exactly 172 "
                    + "characters - CARDDEMO-COMMAREA (160) then WS-THIS-PROGCOMMAREA (12) - but the "
                    + "parts sum to " + PASSED_COMMAREA_LENGTH);
        }
        if (WS_PROMPT_FOR_INPUT_TEXT.length() != WS_INFO_MSG_LENGTH) {
            throw new AssertionError("88 WS-PROMPT-FOR-INPUT fills WS-INFO-MSG PIC X(40) exactly at "
                    + "app/cbl/COACTVWC.cbl:113-114, but the transcribed text is "
                    + WS_PROMPT_FOR_INPUT_TEXT.length() + " characters");
        }
        requireLiteral(LIT_ACCTFILENAME, "ACCTDAT ", "LIT-ACCTFILENAME", 185);
        requireLiteral(LIT_CUSTFILENAME, "CUSTDAT ", "LIT-CUSTFILENAME", 189);
        requireLiteral(LIT_CARDXREFNAME_ACCT_PATH, "CXACAIX ", "LIT-CARDXREFNAME-ACCT-PATH", 193);
        requireLiteral(READ_OPERATION_NAME, "READ", "ERROR-OPNAME's only value", 762);
        requireLiteral(AccountViewResponse.THIS_PROGRAM, LIT_THISPGM, "LIT-THISPGM", 144);
        requireLiteral(AccountViewResponse.THIS_TRANID, LIT_THISTRANID, "LIT-THISTRANID", 146);
        requireLiteral(AccountViewResponse.THIS_MAPSET, LIT_THISMAPSET, "LIT-THISMAPSET", 148);
        requireLiteral(AccountViewResponse.MAP_NAME, LIT_THISMAP, "LIT-THISMAP", 150);
        requireLiteral(ScreenTitles.CCDA_TITLE01, "      AWS Mainframe Modernization       ",
                "CCDA-TITLE01", "app/cpy/COTTL01Y.cpy:18-19");
        requireLiteral(ScreenTitles.CCDA_TITLE02, "              CardDemo                  ",
                "CCDA-TITLE02", "app/cpy/COTTL01Y.cpy:20-22");
    }

    private static void requireLiteral(String actual, String expected, String cobolName,
            int cobolLine) {
        requireLiteral(actual, expected, cobolName, "app/cbl/COACTVWC.cbl:" + cobolLine);
    }

    private static void requireLiteral(String actual, String expected, String cobolName, String where) {
        if (!expected.equals(actual)) {
            throw new AssertionError(cobolName + " is declared as '" + expected + "' at " + where
                    + ", but this class resolved it to '" + actual + "'");
        }
    }

    private final AccountRepository accountRepository;

    private final CardXrefRepository cardXrefRepository;

    private final CustomerRepository customerRepository;

    private final Clock clock;

    private final FixedWidthCodec codec;

    public AccountViewController(AccountRepository accountRepository,
            CardXrefRepository cardXrefRepository,
            CustomerRepository customerRepository,
            Clock clock,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset) {
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "An AccountRepository is required: 9300-GETACCTDATA-BYACCT reads ACCTDAT at "
                        + "app/cbl/COACTVWC.cbl:776-784 and this controller reaches it no other way");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository,
                "A CardXrefRepository is required: 9200-GETCARDXREF-BYACCT reads the CXACAIX path at "
                        + "app/cbl/COACTVWC.cbl:727-735, and it is that read which supplies the customer "
                        + "id the third read needs");
        this.customerRepository = Objects.requireNonNull(customerRepository,
                "A CustomerRepository is required: 9400-GETCUSTDATA-BYCUST reads CUSTDAT at "
                        + "app/cbl/COACTVWC.cbl:826-834");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: 1100-SCREEN-INIT reads FUNCTION CURRENT-DATE twice, and reading a "
                        + "clock inline would make every parity case non-deterministic");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "The active dataset "
                + "code page is required: the ACCOUNT-RECORD work area and the DFHCOMMAREA images are "
                + "bytes, and bytes are only right in a stated page"));
    }

    FixedWidthCodec codec() {
        return codec;
    }

    // The response is always 200 with a painted screen, because that is what the program does: every branch
    // of COACTVWC converges on COMMON-RETURN and sends a map, and a record that could not be found is
    // reported in ERRMSGO rather than by refusing to answer.

    @GetMapping(path = "/api/accounts/{acctId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScreenResponse<AccountViewResponse>> viewAccount(
            @PathVariable("acctId") String acctId,
            @Valid @RequestBody(required = false) AccountViewRequest request,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid,
            @RequestParam(name = EIBCALEN_PARAM, required = false) Integer eibcalen,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid) {
        Objects.requireNonNull(acctId, "An account identifier is required in the path: it is the RIDFLD "
                + "of the reads at app/cbl/COACTVWC.cbl:729 and :778");
        AccountViewRequest received = bind(acctId, request);
        int commareaLength = resolveEibcalen(eibcalen, received);
        byte attentionIdentifier = resolveAttentionIdentifier(resolveAidParameter(eibaid, eibAid));
        AccountViewResponse painted = handle(received, commareaLength, attentionIdentifier);
        return ResponseEntity.ok(ScreenResponse.of(painted, screenMetadata(received, painted)));
    }

    ScreenMetadata screenMetadata(AccountViewRequest received, AccountViewResponse painted) {
        Objects.requireNonNull(received, "The terminal input area carries the xxxL cursor requests");
        Objects.requireNonNull(painted, "The painted map area carries the xxxC attribute bytes");
        Map<String, ScreenMetadata.FieldMetadata> fields = new LinkedHashMap<>();
        String cursorOn = null;
        for (AccountViewResponse.ScreenField field : AccountViewResponse.ScreenField.values()) {
            AccountViewResponse.FieldAttributes quad = painted.attributes(field);
            fields.put(field.label(), ScreenMetadata.FieldMetadata.of(quad.getColour(), quad.getPs(),
                    quad.getHilight(), quad.getValidn()));
            if (cursorOn == null && received.metadata(
                    AccountViewRequest.ScreenField.valueOf(field.name())).isCursorHere()) {
                cursorOn = field.label();
            }
        }
        return ScreenMetadata.of(cursorOn,
                painted.attributes(AccountViewResponse.ScreenField.ERRMSG).getColour(),
                false,
                fields);
    }

    static Integer resolveAidParameter(Integer canonical, Integer alternate) {
        if (canonical == null) {
            return alternate;
        }
        if (alternate != null && !canonical.equals(alternate)) {
            throw ScreenInputRejectedException.contradictorySpellings(EIBAID_PARAM,
                    EIBAID_PARAM_ALIAS, "one EIBAID");
        }
        return canonical;
    }

    AccountViewRequest bind(String acctId, AccountViewRequest request) {
        if (acctId.length() > AccountViewRequest.ACCTSID_LENGTH) {
            throw ScreenInputRejectedException.tooWide(ACCTSID_MEMBER,
                    "ACCTSIDI PIC 9(" + AccountViewRequest.ACCTSID_LENGTH
                            + ") - LENGTH=11 in app/bms/COACTVW.bms:84-90",
                    AccountViewRequest.ACCTSID_LENGTH, acctId.length());
        }
        AccountViewRequest received =
                request == null ? coldStartRequest() : new AccountViewRequest(request);
        ScreenInputRejectedException.requireKeyAgreement(ACCTSID_MEMBER, acctId,
                received.getAcctsid(), AccountViewRequest.ACCTSID_LENGTH, PIC_X_CODEC,
                NO_CRITERION_IMAGE);
        received.setAcctsid(PIC_X_CODEC.movePicX(acctId, AccountViewRequest.ACCTSID_LENGTH));
        return received;
    }

    /**
     * The state a terminal that has never been written to is in: every one of the 37 input items blank and
     * every length, flag and attribute item reset. This is the {@code EIBCALEN = 0} entry of {@code :282},
     * for which the program initialises both communication areas rather than reading either.
     *
     * @return a blank map area carrying no communication area
     */
    private static AccountViewRequest coldStartRequest() {
        AccountViewRequest cold = new AccountViewRequest();
        cold.initializeMapArea();
        return cold;
    }

    static int resolveEibcalen(Integer eibcalen, AccountViewRequest request) {
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
                    + "because app/cbl/COACTVWC.cbl:282 uses it to decide whether the conversation's "
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

    AccountViewResponse handle(AccountViewRequest request, int eibcalen, byte eibAid) {
        Objects.requireNonNull(request, "A request is required: COACTVWC is entered with a terminal input "
                + "area, and an absent one is spaces rather than nothing");
        Conversation task = new Conversation();
        try {
            main0000(request, task, eibcalen, eibAid);
        } catch (AbendException alreadyAbending) {
            throw alreadyAbending;
        } catch (RuntimeException abend) {
            throw abendRoutine(task, abend);
        }
        return task.cactvwao.build();
    }

    void main0000(AccountViewRequest request, Conversation task, int eibcalen, byte eibAid) {
        initializeStorage(request, task, eibcalen, eibAid);
        task.wsTranid = PIC_X_CODEC.movePicX(LIT_THISTRANID, WS_TRANID_LENGTH);
        task.wsReturnMsg = WS_RETURN_MSG_OFF;
        restoreCommarea(task);
        storePfKeyYYYY(task);
        coerceInvalidAid(task);
        dispatch0000(request, task);
        if (!task.returned) {
            trailingInputErrorGuard(request, task);
        }
        if (!task.returned) {
            commonReturn(task);
        }
    }

    void initializeStorage(AccountViewRequest request, Conversation task, int eibcalen, byte eibAid) {
        task.eibcalen = eibcalen;
        task.eibAid = eibAid;
        // The carried work area is copied in first, then INITIALIZE blanks it - which is why the AID the
        // caller sent does not survive: CCARD-AID is set from EIBAID a few statements later, never from the
        // payload.
        task.ccWorkArea = new CardScreenState(request.getCardScreenState());
        task.ccWorkArea.initializeWorkArea();
        task.wsRespCd = FileStatus.NORMAL;
        task.wsReasCd = FileStatus.NO_REASON_CODE;
        task.wsTranid = CardScreenState.spaces(WS_TRANID_LENGTH);
        task.wsInputFlag = INITIALIZED_FLAG;
        task.wsPfkFlag = INITIALIZED_FLAG;
        task.wsEditAcctFlag = INITIALIZED_FLAG;
        task.wsEditCustFlag = INITIALIZED_FLAG;
        task.wsCardRidCardnum = CardScreenState.spaces(WS_CARD_RID_CARDNUM_LENGTH);
        task.wsCardRidCustId = PIC_X_CODEC.movePic9(0L, WS_CARD_RID_CUST_ID_LENGTH);
        task.wsCardRidAcctId = PIC_X_CODEC.movePic9(0L, WS_CARD_RID_ACCT_ID_LENGTH);
        task.wsAccountMasterReadFlag = INITIALIZED_FLAG;
        task.wsCustMasterReadFlag = INITIALIZED_FLAG;
        task.errorOpname = CardScreenState.spaces(ERROR_OPNAME_LENGTH);
        task.errorFile = CardScreenState.spaces(ERROR_FILE_LENGTH);
        task.errorResp = CardScreenState.spaces(ERROR_RESP_LENGTH);
        task.errorResp2 = CardScreenState.spaces(ERROR_RESP_LENGTH);
        task.wsLongMsg = CardScreenState.spaces(WS_LONG_MSG_LENGTH);
        task.wsInfoMsg = WS_INFO_MSG_SPACES;
        task.wsReturnMsg = WS_RETURN_MSG_OFF;
        // ACCOUNT-RECORD (COPY CVACT01Y at :244) and CUSTOMER-RECORD (COPY CVCUS01Y at :254) are group
        // items in WORKING-STORAGE, so they exist whether or not a read has filled them - and an allocated
        // record holds exactly what COBOL leaves there: spaces in every PIC X field and zero in every
        // numeric one.
        task.accountRecord = new AccountRecord(codec.charset());
        task.customerRecord = new CustomerRecord();
        task.cardXrefRecord = Optional.empty();
        task.abendData = SystemMessages.AbendData.spaces();
        task.wsCommarea = CardScreenState.spaces(WS_COMMAREA_LENGTH);
        task.carddemoCommarea = request.hasNavigationContext()
                ? request.getNavigationContext()
                : NavigationContext.empty();
        task.thisProgCommarea = ThisProgCommarea.initialized();
        task.cactvwao = AccountViewResponse.builder();
    }

    void restoreCommarea(Conversation task) {
        boolean noCommareaPassed = task.eibcalen == NO_COMMAREA_LENGTH;
        boolean freshEntryFromMenu = LIT_MENUPGM.equals(PIC_X_CODEC.movePicX(
                task.carddemoCommarea.fromProgram(), NavigationContext.FROM_PROGRAM_LENGTH))
                && !task.carddemoCommarea.isReenter();
        if (noCommareaPassed || freshEntryFromMenu) {
            task.carddemoCommarea = NavigationContext.empty();
            task.thisProgCommarea = ThisProgCommarea.initialized();
            return;
        }
        // Both areas are rendered to their fixed-width images and read back, which is not a no-op: a group
        // MOVE imposes every field's declared width, so a context carrying a short program name comes back
        // space padded to PIC X(08), exactly as the receiving area would hold it.
        task.carddemoCommarea = NavigationContext.fromFixedWidth(
                codec, task.carddemoCommarea.toFixedWidth(codec));
        task.thisProgCommarea = ThisProgCommarea.fromImage(task.thisProgCommarea.toImage());
    }

    void storePfKeyYYYY(Conversation task) {
        Optional<PfKeyResolver.AidKey> stored =
                PfKeyResolver.storePfKey(task.eibAid, task.ccWorkArea.aidKey());
        stored.ifPresent(task.ccWorkArea::setCcardAidCondition);
    }

    void coerceInvalidAid(Conversation task) {
        task.wsPfkFlag = PFK_INVALID;
        if (task.ccWorkArea.isCcardAidEnter() || task.ccWorkArea.isCcardAidPfk03()) {
            task.wsPfkFlag = PFK_VALID;
        }
        if (task.pfkInvalid()) {
            task.ccWorkArea.setCcardAidCondition(PfKeyResolver.AidKey.ENTER);
        }
    }

    void dispatch0000(AccountViewRequest request, Conversation task) {
        if (task.ccWorkArea.isCcardAidPfk03()) {
            backNavigationPfk03(task);
            return;
        }
        if (task.carddemoCommarea.isEnter()) {
            sendMap1000(request, task);
            commonReturn(task);
            return;
        }
        if (task.carddemoCommarea.isReenter()) {
            reenterProcessInputs(request, task);
            return;
        }
        unexpectedDataScenario(task);
    }

    void backNavigationPfk03(Conversation task) {
        NavigationContext commarea = task.carddemoCommarea;
        String toTranid =
                isLowValuesOrSpaces(commarea.fromTranid(), NavigationContext.FROM_TRANID_LENGTH)
                        ? LIT_MENUTRANID
                        : commarea.fromTranid();
        String toProgram =
                isLowValuesOrSpaces(commarea.fromProgram(), NavigationContext.FROM_PROGRAM_LENGTH)
                        ? LIT_MENUPGM
                        : commarea.fromProgram();
        commarea = commarea
                .withToTranid(PIC_X_CODEC.movePicX(toTranid, NavigationContext.TO_TRANID_LENGTH))
                .withToProgram(PIC_X_CODEC.movePicX(toProgram, NavigationContext.TO_PROGRAM_LENGTH))
                .withFromTranid(
                        PIC_X_CODEC.movePicX(LIT_THISTRANID, NavigationContext.FROM_TRANID_LENGTH))
                .withFromProgram(
                        PIC_X_CODEC.movePicX(LIT_THISPGM, NavigationContext.FROM_PROGRAM_LENGTH))
                .withUserTypeUser()
                .withPgmEnter()
                .withLastMapset(
                        PIC_X_CODEC.movePicX(LIT_THISMAPSET, NavigationContext.LAST_MAPSET_LENGTH))
                .withLastMap(PIC_X_CODEC.movePicX(LIT_THISMAP, NavigationContext.LAST_MAP_LENGTH));
        task.carddemoCommarea = commarea;
        task.cactvwao
                .navigationContext(commarea)
                .cardScreenState(task.ccWorkArea)
                .nextProgram(commarea.toProgram());
        task.returned = true;
    }

    void reenterProcessInputs(AccountViewRequest request, Conversation task) {
        processInputs2000(request, task);
        if (task.inputError()) {
            sendMap1000(request, task);
            commonReturn(task);
            return;
        }
        readAcct9000(task);
        sendMap1000(request, task);
        commonReturn(task);
    }

    void unexpectedDataScenario(Conversation task) {
        task.abendData = task.abendData
                .withAbendCulprit(LIT_THISPGM)
                .withAbendCode(UNEXPECTED_DATA_ABEND_CODE)
                .withAbendReason(CardScreenState.spaces(SystemMessages.ABEND_REASON_LENGTH));
        task.wsReturnMsg = UNEXPECTED_DATA_SCENARIO;
        sendPlainText(task);
    }

    void trailingInputErrorGuard(AccountViewRequest request, Conversation task) {
        if (!task.inputError()) {
            return;
        }
        task.ccWorkArea.setCcardErrorMsg(
                PIC_X_CODEC.movePicX(task.wsReturnMsg, CardScreenState.CCARD_ERROR_MSG_LENGTH));
        sendMap1000(request, task);
        commonReturn(task);
    }

    void commonReturn(Conversation task) {
        task.ccWorkArea.setCcardErrorMsg(
                PIC_X_CODEC.movePicX(task.wsReturnMsg, CardScreenState.CCARD_ERROR_MSG_LENGTH));
        String commareaImage = codec.decodeImage(
                task.carddemoCommarea.toFixedWidth(codec), "CARDDEMO-COMMAREA");
        task.wsCommarea = PIC_X_CODEC.movePicX(
                commareaImage + task.thisProgCommarea.toImage(), WS_COMMAREA_LENGTH);
        task.cactvwao
                .navigationContext(task.carddemoCommarea)
                .cardScreenState(task.ccWorkArea);
        task.returned = true;
    }

    void sendMap1000(AccountViewRequest request, Conversation task) {
        screenInit1100(task);
        setupScreenVars1200(task);
        setupScreenAttrs1300(request, task);
        sendScreen1400(task);
    }

    void screenInit1100(Conversation task) {
        task.cactvwao = AccountViewResponse.builder();
        task.dateHeader = DateHeader.from(PIC_X_CODEC, clock);
        task.cactvwao.screenTitles();
        task.cactvwao.screenIdentity();
        task.dateHeader = DateHeader.from(PIC_X_CODEC, clock);
        task.cactvwao.dateHeader(task.dateHeader);
    }

    void setupScreenVars1200(Conversation task) {
        if (task.eibcalen == NO_COMMAREA_LENGTH) {
            task.wsInfoMsg = WS_PROMPT_FOR_INPUT;
        } else {
            // :465-469 - a blank filter paints LOW-VALUES rather than the eleven characters of a value that
            // was never accepted. Note that INITIALIZE leaves WS-EDIT-ACCT-FLAG a space, so
            // FLG-ACCTFILTER-BLANK is true on any path that did not edit the field.
            if (task.flgAcctfilterBlank()) {
                task.cactvwao.acctsid(CardScreenState.lowValues(AccountViewResponse.ACCTSID_LENGTH));
            } else {
                task.cactvwao.acctsid(PIC_X_CODEC.movePicX(task.ccWorkArea.getCcAcctId(),
                        AccountViewResponse.ACCTSID_LENGTH));
            }
            if (task.foundAcctInMaster() || task.foundCustInMaster()) {
                projectAccountRecord1200(task);
            }
            if (task.foundCustInMaster()) {
                projectCustomerRecord1200(task);
            }
        }
        if (task.noInfoMessage()) {
            task.wsInfoMsg = WS_PROMPT_FOR_INPUT;
        }
        task.cactvwao.errmsg(
                PIC_X_CODEC.movePicX(task.wsReturnMsg, AccountViewResponse.ERRMSG_LENGTH));
        task.cactvwao.infomsg(
                PIC_X_CODEC.movePicX(task.wsInfoMsg, AccountViewResponse.INFOMSG_LENGTH));
    }

    void projectAccountRecord1200(Conversation task) {
        AccountRecord account = task.accountRecord;
        task.cactvwao.acsttus(PIC_X_CODEC.movePicX(account.getAcctActiveStatus(),
                AccountViewResponse.ACSTTUS_LENGTH));
        task.cactvwao.acurbalAmount(account.getAcctCurrBal());
        task.cactvwao.acrdlimAmount(account.getAcctCreditLimit());
        task.cactvwao.acshlimAmount(account.getAcctCashCreditLimit());
        task.cactvwao.acrcycrAmount(account.getAcctCurrCycCredit());
        task.cactvwao.acrcydbAmount(account.getAcctCurrCycDebit());
        task.cactvwao.adtopen(PIC_X_CODEC.movePicX(account.getAcctOpenDate(),
                AccountViewResponse.ADTOPEN_LENGTH));
        // The copybook misspells "EXPIRATION" and the Java accessor keeps the misspelling, because a
        // corrected field name would stop matching the copybook the parity differ compares against.
        task.cactvwao.aexpdt(PIC_X_CODEC.movePicX(account.getAcctExpiraionDate(),
                AccountViewResponse.AEXPDT_LENGTH));
        task.cactvwao.areisdt(PIC_X_CODEC.movePicX(account.getAcctReissueDate(),
                AccountViewResponse.AREISDT_LENGTH));
        task.cactvwao.aaddgrp(PIC_X_CODEC.movePicX(account.getAcctGroupId(),
                AccountViewResponse.AADDGRP_LENGTH));
    }

    void projectCustomerRecord1200(Conversation task) {
        CustomerRecord customer = task.customerRecord;
        task.cactvwao.acstnum(PIC_X_CODEC.movePic9(customer.getCustId(),
                AccountViewResponse.ACSTNUM_LENGTH));
        task.cactvwao.acstssnFromSsn(customer.custSsnImage(PIC_X_CODEC));
        task.cactvwao.acstfco(PIC_X_CODEC.movePic9(customer.getCustFicoCreditScore(),
                AccountViewResponse.ACSTFCO_LENGTH));
        task.cactvwao.acstdob(PIC_X_CODEC.movePicX(customer.getCustDobYyyyMmDd(),
                AccountViewResponse.ACSTDOB_LENGTH));
        task.cactvwao.acsfnam(PIC_X_CODEC.movePicX(customer.getCustFirstName(),
                AccountViewResponse.ACSFNAM_LENGTH));
        task.cactvwao.acsmnam(PIC_X_CODEC.movePicX(customer.getCustMiddleName(),
                AccountViewResponse.ACSMNAM_LENGTH));
        task.cactvwao.acslnam(PIC_X_CODEC.movePicX(customer.getCustLastName(),
                AccountViewResponse.ACSLNAM_LENGTH));
        task.cactvwao.acsadl1(PIC_X_CODEC.movePicX(customer.getCustAddrLine1(),
                AccountViewResponse.ACSADL1_LENGTH));
        task.cactvwao.acsadl2(PIC_X_CODEC.movePicX(customer.getCustAddrLine2(),
                AccountViewResponse.ACSADL2_LENGTH));
        task.cactvwao.acscity(PIC_X_CODEC.movePicX(customer.getCustAddrLine3(),
                AccountViewResponse.ACSCITY_LENGTH));
        task.cactvwao.acsstte(PIC_X_CODEC.movePicX(customer.getCustAddrStateCd(),
                AccountViewResponse.ACSSTTE_LENGTH));
        task.cactvwao.acszipc(PIC_X_CODEC.movePicX(customer.getCustAddrZip(),
                AccountViewResponse.ACSZIPC_LENGTH));
        task.cactvwao.acsctry(PIC_X_CODEC.movePicX(customer.getCustAddrCountryCd(),
                AccountViewResponse.ACSCTRY_LENGTH));
        task.cactvwao.acsphn1(PIC_X_CODEC.movePicX(customer.getCustPhoneNum1(),
                AccountViewResponse.ACSPHN1_LENGTH));
        task.cactvwao.acsphn2(PIC_X_CODEC.movePicX(customer.getCustPhoneNum2(),
                AccountViewResponse.ACSPHN2_LENGTH));
        task.cactvwao.acsgovt(PIC_X_CODEC.movePicX(customer.getCustGovtIssuedId(),
                AccountViewResponse.ACSGOVT_LENGTH));
        task.cactvwao.acseftc(PIC_X_CODEC.movePicX(customer.getCustEftAccountId(),
                AccountViewResponse.ACSEFTC_LENGTH));
        task.cactvwao.acspflg(PIC_X_CODEC.movePicX(customer.getCustPriCardHolderInd(),
                AccountViewResponse.ACSPFLG_LENGTH));
    }

    void setupScreenAttrs1300(AccountViewRequest request, Conversation task) {
        // :543 - MOVE DFHBMFSE TO ACCTSIDA OF CACTVWAI: unprotected, and with the modified-data tag set so
        // the field is always returned even when the operator retypes the same value.
        request.metadata(AccountViewRequest.ScreenField.ACCTSID).setAttribute(BmsAttributes.DFHBMFSE);
        if (task.flgAcctfilterNotOk() || task.flgAcctfilterBlank()) {
            request.metadata(AccountViewRequest.ScreenField.ACCTSID).positionCursorHere();
        } else {
            request.metadata(AccountViewRequest.ScreenField.ACCTSID).positionCursorHere();
        }
        task.cactvwao.attributes(AccountViewResponse.ScreenField.ACCTSID)
                .setColour(BmsAttributes.DFHDFCOL);
        if (task.flgAcctfilterNotOk()) {
            task.cactvwao.attributes(AccountViewResponse.ScreenField.ACCTSID)
                    .setColour(BmsAttributes.DFHRED);
        }
        if (task.flgAcctfilterBlank() && task.carddemoCommarea.isReenter()) {
            task.cactvwao.value(AccountViewResponse.ScreenField.ACCTSID, PIC_X_CODEC.movePicX(
                    BLANK_FIELD_MARKER, AccountViewResponse.ACCTSID_LENGTH));
            task.cactvwao.attributes(AccountViewResponse.ScreenField.ACCTSID)
                    .setColour(BmsAttributes.DFHRED);
        }
        task.cactvwao.attributes(AccountViewResponse.ScreenField.INFOMSG)
                .setColour(task.noInfoMessage() ? BmsAttributes.DFHBMDAR : BmsAttributes.DFHNEUTR);
    }

    void sendScreen1400(Conversation task) {
        task.ccWorkArea.setCcardNextMapset(
                PIC_X_CODEC.movePicX(LIT_THISMAPSET, CardScreenState.CCARD_NEXT_MAPSET_LENGTH));
        task.ccWorkArea.setCcardNextMap(
                PIC_X_CODEC.movePicX(LIT_THISMAP, CardScreenState.CCARD_NEXT_MAP_LENGTH));
        task.carddemoCommarea = task.carddemoCommarea.withPgmReenter();
        task.cactvwao
                .nextProgram(PIC_X_CODEC.movePicX(task.ccWorkArea.getCcardNextProg(),
                        AccountViewResponse.NEXT_PROGRAM_LENGTH))
                .nextMapset(task.ccWorkArea.getCcardNextMapset())
                .nextMap(task.ccWorkArea.getCcardNextMap())
                .navigationContext(task.carddemoCommarea)
                .cardScreenState(task.ccWorkArea);
        task.wsRespCd = FileStatus.NORMAL;
    }

    void processInputs2000(AccountViewRequest request, Conversation task) {
        receiveMap2100(task);
        editMapInputs2200(request, task);
        task.ccWorkArea.setCcardErrorMsg(
                PIC_X_CODEC.movePicX(task.wsReturnMsg, CardScreenState.CCARD_ERROR_MSG_LENGTH));
        task.ccWorkArea.setCcardNextProg(
                PIC_X_CODEC.movePicX(LIT_THISPGM, CardScreenState.CCARD_NEXT_PROG_LENGTH));
        task.ccWorkArea.setCcardNextMapset(
                PIC_X_CODEC.movePicX(LIT_THISMAPSET, CardScreenState.CCARD_NEXT_MAPSET_LENGTH));
        task.ccWorkArea.setCcardNextMap(
                PIC_X_CODEC.movePicX(LIT_THISMAP, CardScreenState.CCARD_NEXT_MAP_LENGTH));
    }

    void receiveMap2100(Conversation task) {
        task.wsRespCd = FileStatus.NORMAL;
        task.wsReasCd = FileStatus.NO_REASON_CODE;
    }

    void editMapInputs2200(AccountViewRequest request, Conversation task) {
        task.wsInputFlag = INPUT_OK;
        task.wsEditAcctFlag = FLG_FILTER_ISVALID;
        String acctsidI = PIC_X_CODEC.movePicX(request.getAcctsid(), AccountViewRequest.ACCTSID_LENGTH);
        if (isAsteriskOrSpaces(acctsidI, AccountViewRequest.ACCTSID_LENGTH)) {
            task.ccWorkArea.setCcAcctIdToLowValues();
        } else {
            task.ccWorkArea.setCcAcctId(
                    PIC_X_CODEC.movePicX(acctsidI, CardScreenState.CC_ACCT_ID_LENGTH));
        }
        editAccount2210(task);
        if (task.flgAcctfilterBlank()) {
            task.wsReturnMsg = NO_SEARCH_CRITERIA_RECEIVED;
        }
    }

    void editAccount2210(Conversation task) {
        task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
        if (task.ccWorkArea.isCcAcctIdLowValues() || task.ccWorkArea.isCcAcctIdSpaces()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_BLANK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = WS_PROMPT_FOR_ACCT;
            }
            task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L);
            return;
        }
        if (!task.ccWorkArea.isCcAcctIdNumeric() || task.ccWorkArea.isCcAcctIdNZeros()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = ACCOUNT_FILTER_NOT_NUMERIC;
            }
            task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L);
            return;
        }
        task.carddemoCommarea = task.carddemoCommarea
                .withAcctId(PIC_X_CODEC.decodePic9(task.ccWorkArea.getCcAcctId()));
        task.wsEditAcctFlag = FLG_FILTER_ISVALID;
    }

    void readAcct9000(Conversation task) {
        task.wsInfoMsg = WS_INFO_MSG_SPACES;
        task.wsCardRidAcctId = PIC_X_CODEC.movePic9(
                task.carddemoCommarea.acctId(), WS_CARD_RID_ACCT_ID_LENGTH);
        getCardXrefByAcct9200(task);
        if (task.flgAcctfilterNotOk()) {
            return;
        }
        getAcctDataByAcct9300(task);
        if (task.didNotFindAcctInAcctdat()) {
            return;
        }
        task.wsCardRidCustId = PIC_X_CODEC.movePic9(
                task.carddemoCommarea.custId(), WS_CARD_RID_CUST_ID_LENGTH);
        getCustDataByCust9400(task);
        if (task.didNotFindCustInCustdat()) {
            return;
        }
    }

    void getCardXrefByAcct9200(Conversation task) {
        CardXrefRepository.ReadResult result =
                cardXrefRepository.readByAccountIdViaAltIndex(task.wsCardRidAcctId);
        task.wsRespCd = result.cicsResp();
        task.wsReasCd = result.cicsResp2();
        task.cardXrefRecord = result.record();
        if (result.isFound()) {
            CardXrefRecord xref = result.record().orElseThrow(() -> new IllegalStateException(
                    "CardXrefRepository reported a successful read of the " + LIT_CARDXREFNAME_ACCT_PATH
                            + " path without a record. app/cbl/COACTVWC.cbl:739-740 moves XREF-CUST-ID and "
                            + "XREF-CARD-NUM out of the record area straight after DFHRESP(NORMAL), so a "
                            + "successful read without a record is a broken contract rather than a "
                            + "NOTFND."));
            task.carddemoCommarea = task.carddemoCommarea.withCustId(xref.xrefCustId());
            task.carddemoCommarea =
                    task.carddemoCommarea.withCardNum(carriedCardNumber(xref.xrefCardNum()));
            return;
        }
        if (result.isNotFound()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                task.errorResp = responseCodeImage(task.wsRespCd);
                task.errorResp2 = responseCodeImage(task.wsReasCd);
                task.wsReturnMsg = PIC_X_CODEC.movePicX(STRING_ACCOUNT_PREFIX
                        + task.wsCardRidAcctId
                        + STRING_NOT_FOUND_IN
                        + STRING_CROSS_REF_FILE
                        + task.errorResp
                        + STRING_REAS
                        + task.errorResp2, WS_RETURN_MSG_LENGTH);
            }
            return;
        }
        task.wsInputFlag = INPUT_ERROR;
        task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
        recordFileError(task, LIT_CARDXREFNAME_ACCT_PATH);
    }

    void getAcctDataByAcct9300(Conversation task) {
        AccountRepository.ReadResult result =
                accountRepository.readByKey(PIC_X_CODEC.decodePic9(task.wsCardRidAcctId));
        task.wsRespCd = cicsResp(result.cicsResp(), result.status());
        task.wsReasCd = result.cicsResp2();
        if (result.isFound()) {
            task.accountRecord = result.account().orElseThrow(() -> new IllegalStateException(
                    "AccountRepository reported a successful read of " + LIT_ACCTFILENAME
                            + " without a record. app/cbl/COACTVWC.cbl:780 reads INTO(ACCOUNT-RECORD), so "
                            + "a successful read always leaves 300 bytes behind."));
            task.wsAccountMasterReadFlag = FOUND_IN_MASTER;
            return;
        }
        if (result.isNotFound()) {
            // The SET DID-NOT-FIND-ACCT-IN-ACCTDAT at :792 is commented out, so the composed message below
            // is the only thing that records the miss - and the guard at :704 that would have stopped the
            // sequence therefore never fires.
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                task.errorResp = responseCodeImage(task.wsRespCd);
                task.errorResp2 = responseCodeImage(task.wsReasCd);
                task.wsReturnMsg = PIC_X_CODEC.movePicX(STRING_ACCOUNT_PREFIX
                        + task.wsCardRidAcctId
                        + STRING_NOT_FOUND_IN
                        + STRING_ACCT_MASTER_FILE
                        + task.errorResp
                        + STRING_REAS
                        + task.errorResp2, WS_RETURN_MSG_LENGTH);
            }
            return;
        }
        task.wsInputFlag = INPUT_ERROR;
        task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
        recordFileError(task, LIT_ACCTFILENAME);
    }

    void getCustDataByCust9400(Conversation task) {
        CustomerRepository.ReadResult result = customerRepository.readByKey(task.wsCardRidCustId);
        task.wsRespCd = cicsResp(result.cicsResp(), result.status());
        task.wsReasCd = result.cicsResp2();
        if (result.isFound()) {
            task.customerRecord = result.customer().orElseThrow(() -> new IllegalStateException(
                    "CustomerRepository reported a successful read of " + LIT_CUSTFILENAME
                            + " without a record. app/cbl/COACTVWC.cbl:830 reads INTO(CUSTOMER-RECORD), so "
                            + "a successful read always leaves 500 bytes behind."));
            task.wsCustMasterReadFlag = FOUND_IN_MASTER;
            return;
        }
        if (result.isNotFound()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditCustFlag = FLG_FILTER_NOT_OK;
            task.errorResp = responseCodeImage(task.wsRespCd);
            task.errorResp2 = responseCodeImage(task.wsReasCd);
            if (task.returnMessageOff()) {
                task.wsReturnMsg = PIC_X_CODEC.movePicX(STRING_CUSTID_PREFIX
                        + task.wsCardRidCustId
                        + STRING_NOT_FOUND
                        + STRING_IN_CUSTOMER_MASTER
                        + task.errorResp
                        + STRING_REAS_UPPER
                        + task.errorResp2, WS_RETURN_MSG_LENGTH);
            }
            return;
        }
        task.wsInputFlag = INPUT_ERROR;
        task.wsEditCustFlag = FLG_FILTER_NOT_OK;
        recordFileError(task, LIT_CUSTFILENAME);
    }

    void recordFileError(Conversation task, String fileName) {
        task.errorOpname = PIC_X_CODEC.movePicX(READ_OPERATION_NAME, ERROR_OPNAME_LENGTH);
        task.errorFile = PIC_X_CODEC.movePicX(fileName, ERROR_FILE_LENGTH);
        task.errorResp = responseCodeImage(task.wsRespCd);
        task.errorResp2 = responseCodeImage(task.wsReasCd);
        task.wsReturnMsg = PIC_X_CODEC.movePicX(fileErrorMessage(task), WS_RETURN_MSG_LENGTH);
    }

    String fileErrorMessage(Conversation task) {
        String message = FILE_ERROR_PREFIX
                + PIC_X_CODEC.movePicX(task.errorOpname, ERROR_OPNAME_LENGTH)
                + FILE_ERROR_ON
                + PIC_X_CODEC.movePicX(task.errorFile, ERROR_FILE_LENGTH)
                + FILE_ERROR_RETURNED_RESP
                + PIC_X_CODEC.movePicX(task.errorResp, ERROR_RESP_LENGTH)
                + FILE_ERROR_RESP2
                + PIC_X_CODEC.movePicX(task.errorResp2, ERROR_RESP_LENGTH)
                + FILE_ERROR_TRAILER;
        if (message.length() != FILE_ERROR_MESSAGE_LENGTH) {
            throw new IllegalStateException("WS-FILE-ERROR-MESSAGE must be exactly "
                    + FILE_ERROR_MESSAGE_LENGTH + " characters, as app/cbl/COACTVWC.cbl:86-105 declares "
                    + "it, but the composition came to " + message.length());
        }
        return message;
    }

    static String responseCodeImage(int responseCode) {
        if (!FileStatus.respReported(responseCode)) {
            return PIC_X_CODEC.movePicX(
                    FileStatus.respNotReportedImage(RESP_CODE_DIGITS), ERROR_RESP_LENGTH);
        }
        String digits = PIC_X_CODEC.movePic9(Math.abs((long) responseCode), RESP_CODE_DIGITS);
        return PIC_X_CODEC.movePicX(digits, ERROR_RESP_LENGTH);
    }

    static int cicsResp(OptionalInt reported, String status) {
        if (reported.isPresent()) {
            return reported.getAsInt();
        }
        return FileStatus.cicsRespOfBatchStatus(status).orElse(FileStatus.RESP_NOT_REPORTED);
    }

    void sendPlainText(Conversation task) {
        String transmitted = PIC_X_CODEC.movePicX(task.wsReturnMsg, WS_RETURN_MSG_LENGTH);
        task.cactvwao
                .errmsg(PIC_X_CODEC.movePicX(transmitted, AccountViewResponse.ERRMSG_LENGTH))
                .navigationContext(task.carddemoCommarea)
                .cardScreenState(task.ccWorkArea);
        if (LOG.isWarnEnabled()) {
            LOG.warn(LIT_THISPGM + " sent plain text (abend code " + task.abendData.abendCode().trim()
                    + "): " + transmitted.trim());
        }
        task.returned = true;
    }

    void sendLongText(Conversation task) {
        String transmitted = PIC_X_CODEC.movePicX(task.wsLongMsg, WS_LONG_MSG_LENGTH);
        task.cactvwao
                .errmsg(PIC_X_CODEC.movePicX(transmitted, AccountViewResponse.ERRMSG_LENGTH))
                .navigationContext(task.carddemoCommarea)
                .cardScreenState(task.ccWorkArea);
        task.returned = true;
    }

    AbendException abendRoutine(Conversation task, RuntimeException cause) {
        SystemMessages.AbendData abendData =
                task.abendData == null ? SystemMessages.AbendData.spaces() : task.abendData;
        String abendMsg =
                PIC_X_CODEC.movePicX(abendData.abendMsg(), SystemMessages.ABEND_MSG_LENGTH);
        if (CardScreenState.lowValues(SystemMessages.ABEND_MSG_LENGTH).equals(abendMsg)) {
            abendData = abendData.withAbendMsg(UNEXPECTED_ABEND_OCCURRED);
        }
        abendData = abendData.withAbendCulprit(LIT_THISPGM);
        task.abendData = abendData;
        LOG.error(LIT_THISPGM + " abending with ABCODE " + ABEND_ROUTINE_ABCODE + ": "
                + AbendException.ABEND_DISPLAY_TEXT + " " + abendDataImage(abendData));
        if (task.cactvwao != null) {
            task.cactvwao.cardScreenState(
                    task.ccWorkArea == null ? new CardScreenState() : task.ccWorkArea);
        }
        task.returned = true;
        return AbendException.withoutAbendParameters(LIT_THISPGM,
                        AbendException.RETURN_CODE_IO_ERROR,
                        "ABCODE " + ABEND_ROUTINE_ABCODE + ": " + abendData.abendMsg().trim(), cause)
                .withSourceDiagnostic(abendDataImage(abendData));
    }

    static String abendDataImage(SystemMessages.AbendData abendData) {
        SystemMessages.AbendData atWidth = abendData.toDeclaredWidths();
        return atWidth.abendCode() + atWidth.abendCulprit() + atWidth.abendReason()
                + atWidth.abendMsg();
    }

    static boolean isLowValuesOrSpaces(String value, int length) {
        String image = PIC_X_CODEC.movePicX(value, length);
        return CardScreenState.spaces(length).equals(image)
                || CardScreenState.lowValues(length).equals(image);
    }

    static boolean isAsteriskOrSpaces(String value, int length) {
        String image = PIC_X_CODEC.movePicX(value, length);
        return PIC_X_CODEC.movePicX(BLANK_FIELD_MARKER, length).equals(image)
                || CardScreenState.spaces(length).equals(image);
    }

    static long carriedCardNumber(String cardNumber) {
        String trimmed = cardNumber.trim();
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

    /**
     * Everything {@code COACTVWC} declares in {@code WORKING-STORAGE}, for the duration of one interaction.
     */
    static final class Conversation {
        int eibcalen;

        byte eibAid;

        int wsRespCd;

        int wsReasCd;

        String wsTranid;

        String wsInputFlag;

        String wsPfkFlag;

        String wsEditAcctFlag;

        String wsEditCustFlag;

        String wsCardRidCardnum;

        String wsCardRidCustId;

        String wsCardRidAcctId;

        String wsAccountMasterReadFlag;

        String wsCustMasterReadFlag;

        String errorOpname;

        String errorFile;

        String errorResp;

        String errorResp2;

        String wsLongMsg;

        String wsInfoMsg;

        String wsReturnMsg;

        CardScreenState ccWorkArea;

        NavigationContext carddemoCommarea;

        ThisProgCommarea thisProgCommarea;

        String wsCommarea;

        AccountRecord accountRecord;

        CustomerRecord customerRecord;

        Optional<CardXrefRecord> cardXrefRecord = Optional.empty();

        SystemMessages.AbendData abendData;

        DateHeader dateHeader;

        AccountViewResponse.Builder cactvwao;

        boolean returned;

        long wsCardRidCustIdN() {
            return PIC_X_CODEC.decodePic9(wsCardRidCustId);
        }

        long wsCardRidAcctIdN() {
            return PIC_X_CODEC.decodePic9(wsCardRidAcctId);
        }

        boolean inputOk() {
            return INPUT_OK.equals(wsInputFlag);
        }

        boolean inputError() {
            return INPUT_ERROR.equals(wsInputFlag);
        }

        /**
         * {@code 88 INPUT-PENDING VALUE LOW-VALUES} on {@code WS-INPUT-FLAG} - {@code :53}; never tested.
         */
        boolean inputPending() {
            return INPUT_PENDING.equals(wsInputFlag);
        }

        boolean pfkValid() {
            return PFK_VALID.equals(wsPfkFlag);
        }

        boolean pfkInvalid() {
            return PFK_INVALID.equals(wsPfkFlag);
        }

        boolean pfkeyInputPending() {
            return INPUT_PENDING.equals(wsPfkFlag);
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

        boolean flgCustfilterNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditCustFlag);
        }

        /**
         * {@code 88 FLG-CUSTFILTER-ISVALID VALUE '1'} - {@code :64}; declared, never set (B5).
         */
        boolean flgCustfilterIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditCustFlag);
        }

        /**
         * {@code 88 FLG-CUSTFILTER-BLANK VALUE ' '} - {@code :65}; declared, never tested (B5).
         */
        boolean flgCustfilterBlank() {
            return FLG_FILTER_BLANK.equals(wsEditCustFlag);
        }

        boolean foundAcctInMaster() {
            return FOUND_IN_MASTER.equals(wsAccountMasterReadFlag);
        }

        boolean foundCustInMaster() {
            return FOUND_IN_MASTER.equals(wsCustMasterReadFlag);
        }

        boolean noInfoMessage() {
            return WS_INFO_MSG_SPACES.equals(wsInfoMsg) || WS_INFO_MSG_LOW_VALUES.equals(wsInfoMsg);
        }

        boolean promptForInput() {
            return WS_PROMPT_FOR_INPUT.equals(wsInfoMsg);
        }

        /**
         * {@code 88 WS-INFORM-OUTPUT} - {@code :115-116}; declared, never set (B5).
         */
        boolean informOutput() {
            return WS_INFORM_OUTPUT.equals(wsInfoMsg);
        }

        boolean returnMessageOff() {
            return WS_RETURN_MSG_OFF.equals(wsReturnMsg);
        }

        /**
         * {@code 88 WS-EXIT-MESSAGE} - {@code :119-120}; declared, never set (B5).
         */
        boolean exitMessage() {
            return WS_EXIT_MESSAGE.equals(wsReturnMsg);
        }

        boolean promptForAcct() {
            return WS_PROMPT_FOR_ACCT.equals(wsReturnMsg);
        }

        boolean noSearchCriteriaReceived() {
            return NO_SEARCH_CRITERIA_RECEIVED.equals(wsReturnMsg);
        }

        /**
         * {@code 88 SEARCHED-ACCT-ZEROES} - {@code :125-126}; declared, never set.
         */
        boolean searchedAcctZeroes() {
            return SEARCHED_ACCT_ZEROES.equals(wsReturnMsg);
        }

        /**
         * {@code 88 SEARCHED-ACCT-NOT-NUMERIC} - {@code :127-128}; declared, never set, and declared over
         * the same literal as {@link #searchedAcctZeroes()}.
         */
        boolean searchedAcctNotNumeric() {
            return SEARCHED_ACCT_NOT_NUMERIC.equals(wsReturnMsg);
        }

        /**
         * {@code 88 DID-NOT-FIND-ACCT-IN-CARDXREF} - {@code :129-130}; declared, never set.
         */
        boolean didNotFindAcctInCardxref() {
            return DID_NOT_FIND_ACCT_IN_CARDXREF.equals(wsReturnMsg);
        }

        /**
         * {@code 88 DID-NOT-FIND-ACCT-IN-ACCTDAT} - {@code :131-132}; tested at {@code :704} and never set,
         * because the {@code SET} at {@code :792} is commented out.
         */
        boolean didNotFindAcctInAcctdat() {
            return DID_NOT_FIND_ACCT_IN_ACCTDAT.equals(wsReturnMsg);
        }

        /**
         * {@code 88 DID-NOT-FIND-CUST-IN-CUSTDAT} - {@code :133-134}; tested at {@code :713}, and never set
         * because the {@code SET} at {@code :842} is commented out.
         */
        boolean didNotFindCustInCustdat() {
            return DID_NOT_FIND_CUST_IN_CUSTDAT.equals(wsReturnMsg);
        }

        /**
         * {@code 88 XREF-READ-ERROR} - {@code :135-136}; declared, never set (B5).
         */
        boolean xrefReadError() {
            return XREF_READ_ERROR.equals(wsReturnMsg);
        }

        /**
         * {@code 88 CODING-TO-BE-DONE} - {@code :137-138}; declared, never set (B5).
         */
        boolean codingToBeDone() {
            return CODING_TO_BE_DONE.equals(wsReturnMsg);
        }
    }

    /**
     * {@code 01 WS-THIS-PROGCOMMAREA} - the twelve-character trailer this program appends to the
     * communication area: {@code CA-CALL-CONTEXT} holding {@code CA-FROM-PROGRAM PIC X(08)} and
     * {@code CA-FROM-TRANID PIC X(04)}.
     *
     * <p>{@code COACTVWC} never reads either field - it splits the trailer out of {@code DFHCOMMAREA} at
     * {@code :290-292} and packs it back at {@code :398-400} without looking inside - so modelling it as
     * two named fields with a width-exact image is the whole of its behaviour.
     *
     * @param caFromProgram {@code CA-FROM-PROGRAM PIC X(08)}
     * @param caFromTranid {@code CA-FROM-TRANID PIC X(04)}
     */
    record ThisProgCommarea(String caFromProgram, String caFromTranid) {
        static final int CA_FROM_PROGRAM_LENGTH = 8;

        static final int CA_FROM_TRANID_LENGTH = 4;

        ThisProgCommarea {
            Objects.requireNonNull(caFromProgram, "CA-FROM-PROGRAM is PIC X(08) and has no absent state; "
                    + "use ThisProgCommarea.initialized() for the state INITIALIZE leaves");
            Objects.requireNonNull(caFromTranid, "CA-FROM-TRANID is PIC X(04) and has no absent state; "
                    + "use ThisProgCommarea.initialized() for the state INITIALIZE leaves");
            if (caFromProgram.length() != CA_FROM_PROGRAM_LENGTH) {
                throw new IllegalArgumentException("CA-FROM-PROGRAM is PIC X(0"
                        + CA_FROM_PROGRAM_LENGTH + ") at app/cbl/COACTVWC.cbl:215, but was given "
                        + caFromProgram.length() + " characters");
            }
            if (caFromTranid.length() != CA_FROM_TRANID_LENGTH) {
                throw new IllegalArgumentException("CA-FROM-TRANID is PIC X(0"
                        + CA_FROM_TRANID_LENGTH + ") at app/cbl/COACTVWC.cbl:216, but was given "
                        + caFromTranid.length() + " characters");
            }
        }

        static ThisProgCommarea initialized() {
            return new ThisProgCommarea(CardScreenState.spaces(CA_FROM_PROGRAM_LENGTH),
                    CardScreenState.spaces(CA_FROM_TRANID_LENGTH));
        }

        static ThisProgCommarea fromImage(String image) {
            Objects.requireNonNull(image, "A twelve-character image is required to read "
                    + "WS-THIS-PROGCOMMAREA");
            int declared = CA_FROM_PROGRAM_LENGTH + CA_FROM_TRANID_LENGTH;
            if (image.length() != declared) {
                throw new IllegalArgumentException("WS-THIS-PROGCOMMAREA is " + declared
                        + " characters at app/cbl/COACTVWC.cbl:213-216, but the image is "
                        + image.length());
            }
            return new ThisProgCommarea(image.substring(0, CA_FROM_PROGRAM_LENGTH),
                    image.substring(CA_FROM_PROGRAM_LENGTH, declared));
        }

        String toImage() {
            return caFromProgram + caFromTranid;
        }
    }
}
