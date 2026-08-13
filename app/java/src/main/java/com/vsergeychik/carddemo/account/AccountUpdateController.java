package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest;
import com.vsergeychik.carddemo.account.dto.AccountUpdateResponse;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.ConversationStateSeal;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
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
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
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
 * {@code COACTUPC} - "Accept and process ACCOUNT UPDATE" - projected onto
 * {@code PUT /api/accounts/&#123;acctId&#125;}.
 *
 * <p>{@code 9000-READ-ACCT}'s second guard cannot fire.
 */
@RestController
public class AccountUpdateController {
    private static final Log LOG = LogFactory.getLog(AccountUpdateController.class);

    static final String LIT_THISPGM = "COACTUPC";

    static final String LIT_THISTRANID = "CAUP";

    static final String LIT_THISMAPSET = "COACTUP ";

    static final String LIT_THISMAP = "CACTUPA";

    static final String LIT_CARDUPDATE_PGM = "COCRDUPC";

    static final String LIT_CARDUPDATE_TRANID = "CCUP";

    static final String LIT_CARDUPDATE_MAPSET = "COCRDUP ";

    static final String LIT_CARDUPDATE_MAP = "CCRDUPA";

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

    static final String LIT_ACCTFILENAME = "ACCTDAT ";

    static final String LIT_CUSTFILENAME = "CUSTDAT ";

    static final String LIT_CARDFILENAME = "CARDDAT ";

    static final String LIT_CARDFILENAME_ACCT_PATH = "CARDAIX ";

    static final String LIT_CARDXREFNAME_ACCT_PATH = "CXACAIX ";

    static final String LIT_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    static final String LIT_LOWER = "abcdefghijklmnopqrstuvwxyz";

    static final String LIT_NUMBERS = "0123456789";

    static final String LIT_ALL_ALPHA_FROM_X = LIT_UPPER + LIT_LOWER;

    static final String LIT_ALL_ALPHANUM_FROM_X = LIT_ALL_ALPHA_FROM_X + LIT_NUMBERS;

    // Each names the PICTURE it comes from, so a reviewer can check a pad or a truncate against the source
    // without leaving this file.

    static final int WS_TRANID_LENGTH = 4;

    static final int WS_EDIT_VARIABLE_NAME_LENGTH = 25;

    static final int WS_EDIT_SIGNED_NUMBER_LENGTH = 15;

    static final int WS_EDIT_ALPHANUM_ONLY_LENGTH = 256;

    static final int WS_EDIT_US_PHONE_NUM_LENGTH = 15;

    static final int WS_CURR_DATE_LENGTH = 21;

    static final int WS_EDIT_CURRENCY_LENGTH = 15;

    // CUST-ACCT-ID-X, CUST-ACCT-ID-N and every field of the WS-EDIT-DATE-X group appear exactly once in
    // COACTUPC beyond their own declarations: nothing sets them and nothing reads them.

    static final int CUST_ACCT_ID_LENGTH = 11;

    static final int WS_EDIT_DATE_X_LENGTH = 10;

    static final int WS_EDIT_DATE_YEAR_OFFSET = 0;

    static final int WS_EDIT_DATE_YEAR_LENGTH = 4;

    static final int WS_EDIT_DATE_MONTH_OFFSET = 5;

    static final int WS_EDIT_DATE_MONTH_LENGTH = 2;

    static final int WS_EDIT_DATE_DAY_OFFSET = 8;

    static final int WS_EDIT_DATE_DAY_LENGTH = 2;

    static final int WS_LONG_MSG_LENGTH = 500;

    static final int WS_INFO_MSG_LENGTH = 40;

    static final int WS_RETURN_MSG_LENGTH = AccountUpdateService.RETURN_MESSAGE_LENGTH;

    static final int WS_CARD_RID_CARDNUM_LENGTH = CardScreenState.CC_CARD_NUM_LENGTH;

    static final int WS_CARD_RID_CUST_ID_LENGTH = CardScreenState.CC_CUST_ID_LENGTH;

    static final int WS_CARD_RID_ACCT_ID_LENGTH = CardScreenState.CC_ACCT_ID_LENGTH;

    static final int ERROR_OPNAME_LENGTH = 8;

    static final int ERROR_FILE_LENGTH = 9;

    static final int ERROR_RESP_LENGTH = 10;

    static final int RESP_CODE_DIGITS = 9;

    static final int WS_COMMAREA_LENGTH = AccountUpdateRequest.COMMAREA_CAPACITY;

    static final int THIS_PROGCOMMAREA_LENGTH = AccountUpdateRequest.CommArea.RECORD_LENGTH;

    static final int PASSED_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + THIS_PROGCOMMAREA_LENGTH;

    static final int NO_COMMAREA_LENGTH = 0;

    static final int AID_MIN = 0;

    static final int AID_MAX = 255;

    static final String INPUT_OK = "0";

    static final String INPUT_ERROR = "1";

    static final String INPUT_PENDING = "\u0000";

    static final String PFK_VALID = "0";

    static final String PFK_INVALID = "1";

    static final String NO_CHANGES_FOUND = "0";

    static final String CHANGE_HAS_OCCURRED = "1";

    static final String FLG_FILTER_ISVALID = "1";

    static final String FLG_FILTER_NOT_OK = "0";

    static final String FLG_FILTER_BLANK = " ";

    static final String FLG_ISVALID = "\u0000";

    static final String FLG_NOT_OK = "0";

    static final String FLG_BLANK = "B";

    static final String YES = "Y";

    static final String NO = "N";

    static final String FOUND_IN_MASTER = "1";

    static final String INITIALIZED_FLAG = " ";

    static final String INFO_FOUND_ACCOUNT_DATA = "Details of selected account shown above";

    static final String INFO_PROMPT_FOR_SEARCH_KEYS = "Enter or update id of account to update";

    static final String INFO_PROMPT_FOR_CHANGES = "Update account details presented above.";

    static final String INFO_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";

    static final String INFO_CONFIRM_UPDATE_SUCCESS = "Changes committed to database";

    static final String INFO_INFORM_FAILURE = "Changes unsuccessful. Please try again";

    static final String WS_RETURN_MSG_OFF = AccountUpdateService.RETURN_MESSAGE_OFF;

    static final String MSG_PROMPT_FOR_ACCT = "Account number not provided";

    static final String MSG_NO_SEARCH_CRITERIA_RECEIVED = "No input received";

    static final String MSG_NO_CHANGES_DETECTED =
            "No change detected with respect to values fetched.";

    static final String MSG_PROMPT_FOR_LASTNAME = "Last name not provided";

    static final String MSG_ACCT_STATUS_MUST_BE_YES_NO = "Account Active Status must be Y or N";

    static final String MSG_THIS_MONTH_NOT_VALID = "Card expiry month must be between 1 and 12";

    static final String MSG_THIS_YEAR_NOT_VALID = "Invalid card expiry year";

    static final String MSG_DID_NOT_FIND_ACCT_IN_ACCTDAT =
            "Did not find this account in account master file";

    static final String MSG_DID_NOT_FIND_CUST_IN_CUSTDAT =
            "Did not find associated customer in master file";

    static final String MSG_ACCT_NUMBER_11_DIGIT_A =
            "Account Number if supplied must be a 11 digit";

    static final String MSG_ACCT_NUMBER_11_DIGIT_B = " Non-Zero Number";

    static final String MSG_MUST_BE_SUPPLIED = " must be supplied.";

    static final String MSG_MUST_BE_Y_OR_N = " must be Y or N.";

    static final String MSG_ALPHABETS_ONLY = " can have alphabets only.";

    static final String MSG_NUMBERS_OR_ALPHABETS_ONLY = " can have numbers or alphabets only.";

    static final String MSG_MUST_BE_ALL_NUMERIC = " must be all numeric.";

    static final String MSG_MUST_NOT_BE_ZERO = " must not be zero.";

    static final String MSG_IS_NOT_VALID = " is not valid";

    static final String MSG_AREA_CODE_MUST_BE_SUPPLIED = ": Area code must be supplied.";

    static final String MSG_AREA_CODE_3_DIGITS = ": Area code must be A 3 digit number.";

    static final String MSG_AREA_CODE_CANNOT_BE_ZERO = ": Area code cannot be zero";

    static final String MSG_NOT_VALID_AREA_CODE =
            ": Not valid North America general purpose area code";

    static final String MSG_PREFIX_MUST_BE_SUPPLIED = ": Prefix code must be supplied.";

    static final String MSG_PREFIX_3_DIGITS = ": Prefix code must be A 3 digit number.";

    static final String MSG_PREFIX_CANNOT_BE_ZERO = ": Prefix code cannot be zero";

    static final String MSG_LINENUM_MUST_BE_SUPPLIED = ": Line number code must be supplied.";

    static final String MSG_LINENUM_4_DIGITS = ": Line number code must be A 4 digit number.";

    static final String MSG_LINENUM_CANNOT_BE_ZERO = ": Line number code cannot be zero";

    static final String MSG_SSN_PART1_RANGE = ": should not be 000, 666, or between 900 and 999";

    static final String MSG_NOT_A_VALID_STATE_CODE = ": is not a valid state code";

    static final String MSG_FICO_RANGE = ": should be between 300 and 850";

    static final String MSG_INVALID_ZIP_FOR_STATE = "Invalid zip code for state";

    static final String STRING_ACCOUNT_PREFIX = "Account:";

    static final String STRING_NOT_FOUND_IN = " not found in";

    static final String STRING_CROSS_REF_FILE = " Cross ref file.  Resp:";

    static final String STRING_ACCT_MASTER_FILE = " Acct Master file.Resp:";

    static final String STRING_REAS = " Reas:";

    static final String STRING_CUSTID_PREFIX = "CustId:";

    static final String STRING_NOT_FOUND = " not found";

    static final String STRING_IN_CUSTOMER_MASTER = " in customer master.Resp: ";

    static final String STRING_REAS_UPPER = " REAS:";

    // Eighty characters exactly, moved whole into the 75-character WS-RETURN-MSG by all three WHEN OTHER
    // arms, which truncates the trailing five.

    static final String FILE_ERROR_PREFIX = "File Error: ";

    static final String FILE_ERROR_ON = " on ";

    static final String FILE_ERROR_RETURNED_RESP = " returned RESP ";

    static final String FILE_ERROR_RESP2 = ",RESP2 ";

    static final String FILE_ERROR_TRAILER = "     ";

    static final String READ_OPERATION_NAME = "READ";

    static final int FILE_ERROR_MESSAGE_LENGTH = 80;

    static final String ABEND_CODE_UNEXPECTED_DATA = "0001";

    static final String ABEND_MSG_UNEXPECTED_DATA_SCENARIO = "UNEXPECTED DATA SCENARIO";

    static final String UNEXPECTED_ABEND_OCCURRED = "UNEXPECTED ABEND OCCURRED.";

    static final String ABEND_ROUTINE_ABCODE = "9999";

    static final String NO_TRIGGERING_FAILURE = "no triggering failure";

    static final String EIBAID_PARAM = "eibaid";

    static final String EIBAID_PARAM_ALIAS = "eibAid";

    static final String EIBCALEN_PARAM = "eibcalen";

    static final String ACCTSID_MEMBER = "acctsid";
    static final String NO_CRITERION_IMAGE = "*";

    /**
     * The payload member the sealed {@code WS-THIS-PROGCOMMAREA} travels in, spelled as the client sends
     * it.
     */
    static final String STATE_TOKEN_MEMBER = "stateToken";

    /**
     * What a state token issued by this screen is bound to, as GCM additional authenticated data.
     *
     * <p>Naming the program and the member means a token issued for the card-update conversation cannot be
     * presented here, and vice versa: the two carry different areas of different widths, and one accepted
     * as the other would be a different program's confirmation.
     */
    static final String STATE_PURPOSE = LIT_THISPGM + "/" + STATE_TOKEN_MEMBER;


    /** The route: {@code PUT /api/accounts/{acctId}}, CSD transaction {@code CAUP}. */
    static final String ACCOUNT_UPDATE_PATH = "/api/accounts/{acctId}";

    static final String NAME_ACCOUNT_STATUS = "Account Status";

    static final String NAME_OPEN_DATE = "Open Date";

    static final String NAME_CREDIT_LIMIT = "Credit Limit";

    static final String NAME_EXPIRY_DATE = "Expiry Date";

    static final String NAME_CASH_CREDIT_LIMIT = "Cash Credit Limit";

    static final String NAME_REISSUE_DATE = "Reissue Date";

    static final String NAME_CURRENT_BALANCE = "Current Balance";

    static final String NAME_CURRENT_CYCLE_CREDIT_LIMIT = "Current Cycle Credit Limit";

    static final String NAME_CURRENT_CYCLE_DEBIT_LIMIT = "Current Cycle Debit Limit";

    static final String NAME_SSN = "SSN";

    static final String NAME_DATE_OF_BIRTH = "Date of Birth";

    static final String NAME_FICO_SCORE = "FICO Score";

    static final String NAME_FIRST_NAME = "First Name";

    static final String NAME_MIDDLE_NAME = "Middle Name";

    static final String NAME_LAST_NAME = "Last Name";

    static final String NAME_ADDRESS_LINE_1 = "Address Line 1";

    static final String NAME_STATE = "State";

    static final String NAME_ZIP = "Zip";

    static final String NAME_CITY = "City";

    static final String NAME_COUNTRY = "Country";

    static final String NAME_PHONE_NUMBER_1 = "Phone Number 1";

    static final String NAME_PHONE_NUMBER_2 = "Phone Number 2";

    static final String NAME_EFT_ACCOUNT_ID = "EFT Account Id";

    static final String NAME_PRIMARY_CARD_HOLDER = "Primary Card Holder";

    static final String NAME_SSN_FIRST_3 = "SSN: First 3 chars";

    static final String NAME_SSN_4TH_5TH = "SSN 4th & 5th chars";

    static final String NAME_SSN_LAST_4 = "SSN Last 4 chars";

    static final int EDIT_LENGTH_3 = 3;

    static final int EDIT_LENGTH_2 = 2;

    static final int EDIT_LENGTH_4 = 4;

    static final int EDIT_LENGTH_5 = 5;

    static final int EDIT_LENGTH_10 = 10;

    static final int EDIT_LENGTH_25 = 25;

    static final int EDIT_LENGTH_50 = 50;

    static final int SSN_PART1_EXCLUDED_ZERO = 0;

    static final int SSN_PART1_EXCLUDED_666 = 666;

    static final int SSN_PART1_EXCLUDED_RANGE_LOW = 900;

    static final int SSN_PART1_EXCLUDED_RANGE_HIGH = 999;

    static final int ZIP_PREFIX_LENGTH = 2;

    static final int FICO_RANGE_LOW = 300;

    static final int FICO_RANGE_HIGH = 850;

    private static final FixedWidthCodec PIC_X_CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    private static final String CURRENCY_INTEGER_TEMPLATE = "ZZZ,ZZZ,ZZZ";

    private static final char SUPPRESSION_CHARACTER = 'Z';

    private static final int CURRENCY_INTEGER_DIGITS = 9;

    private static final int MONETARY_INTEGER_DIGITS = 10;

    private static final int MONETARY_FRACTION_DIGITS = 2;

    private static final char PLUS_SIGN = '+';

    private static final char MINUS_SIGN = '-';

    private static final String CURRENCY_DECIMAL_POINT = ".";

    private static final char SPACE = ' ';

    private static final char LOW_VALUE = '\u0000';

    private static final char ZERO_DIGIT = '0';

    private static final int ONE = 1;

    private static final int CURSOR_HERE = AccountUpdateRequest.FieldMetadata.CURSOR_HERE;

    static final List<AccountUpdateResponse.ScreenField> INITIAL_VALUE_FIELDS = List.of(
            AccountUpdateResponse.ScreenField.ACSTTUS,
            AccountUpdateResponse.ScreenField.ACRDLIM,
            AccountUpdateResponse.ScreenField.ACURBAL,
            AccountUpdateResponse.ScreenField.ACSHLIM,
            AccountUpdateResponse.ScreenField.ACRCYCR,
            AccountUpdateResponse.ScreenField.ACRCYDB,
            AccountUpdateResponse.ScreenField.OPNYEAR,
            AccountUpdateResponse.ScreenField.OPNMON,
            AccountUpdateResponse.ScreenField.OPNDAY,
            AccountUpdateResponse.ScreenField.EXPYEAR,
            AccountUpdateResponse.ScreenField.EXPMON,
            AccountUpdateResponse.ScreenField.EXPDAY,
            AccountUpdateResponse.ScreenField.RISYEAR,
            AccountUpdateResponse.ScreenField.RISMON,
            AccountUpdateResponse.ScreenField.RISDAY,
            AccountUpdateResponse.ScreenField.AADDGRP,
            AccountUpdateResponse.ScreenField.ACSTNUM,
            AccountUpdateResponse.ScreenField.ACTSSN1,
            AccountUpdateResponse.ScreenField.ACTSSN2,
            AccountUpdateResponse.ScreenField.ACTSSN3,
            AccountUpdateResponse.ScreenField.ACSTFCO,
            AccountUpdateResponse.ScreenField.DOBYEAR,
            AccountUpdateResponse.ScreenField.DOBMON,
            AccountUpdateResponse.ScreenField.DOBDAY,
            AccountUpdateResponse.ScreenField.ACSFNAM,
            AccountUpdateResponse.ScreenField.ACSMNAM,
            AccountUpdateResponse.ScreenField.ACSLNAM,
            AccountUpdateResponse.ScreenField.ACSADL1,
            AccountUpdateResponse.ScreenField.ACSADL2,
            AccountUpdateResponse.ScreenField.ACSCITY,
            AccountUpdateResponse.ScreenField.ACSSTTE,
            AccountUpdateResponse.ScreenField.ACSZIPC,
            AccountUpdateResponse.ScreenField.ACSCTRY,
            AccountUpdateResponse.ScreenField.ACSPH1A,
            AccountUpdateResponse.ScreenField.ACSPH1B,
            AccountUpdateResponse.ScreenField.ACSPH1C,
            AccountUpdateResponse.ScreenField.ACSPH2A,
            AccountUpdateResponse.ScreenField.ACSPH2B,
            AccountUpdateResponse.ScreenField.ACSPH2C,
            AccountUpdateResponse.ScreenField.ACSGOVT,
            AccountUpdateResponse.ScreenField.ACSEFTC,
            AccountUpdateResponse.ScreenField.ACSPFLG);

    static final int INITIAL_VALUE_FIELD_COUNT = 42;

    static final List<AccountUpdateResponse.ScreenField> PROTECTABLE_FIELDS = List.of(
            AccountUpdateResponse.ScreenField.ACCTSID,
            AccountUpdateResponse.ScreenField.ACSTTUS,
            AccountUpdateResponse.ScreenField.ACRDLIM,
            AccountUpdateResponse.ScreenField.ACSHLIM,
            AccountUpdateResponse.ScreenField.ACURBAL,
            AccountUpdateResponse.ScreenField.ACRCYCR,
            AccountUpdateResponse.ScreenField.ACRCYDB,
            AccountUpdateResponse.ScreenField.OPNYEAR,
            AccountUpdateResponse.ScreenField.OPNMON,
            AccountUpdateResponse.ScreenField.OPNDAY,
            AccountUpdateResponse.ScreenField.EXPYEAR,
            AccountUpdateResponse.ScreenField.EXPMON,
            AccountUpdateResponse.ScreenField.EXPDAY,
            AccountUpdateResponse.ScreenField.RISYEAR,
            AccountUpdateResponse.ScreenField.RISMON,
            AccountUpdateResponse.ScreenField.RISDAY,
            AccountUpdateResponse.ScreenField.AADDGRP,
            AccountUpdateResponse.ScreenField.ACSTNUM,
            AccountUpdateResponse.ScreenField.ACTSSN1,
            AccountUpdateResponse.ScreenField.ACTSSN2,
            AccountUpdateResponse.ScreenField.ACTSSN3,
            AccountUpdateResponse.ScreenField.ACSTFCO,
            AccountUpdateResponse.ScreenField.DOBYEAR,
            AccountUpdateResponse.ScreenField.DOBMON,
            AccountUpdateResponse.ScreenField.DOBDAY,
            AccountUpdateResponse.ScreenField.ACSFNAM,
            AccountUpdateResponse.ScreenField.ACSMNAM,
            AccountUpdateResponse.ScreenField.ACSLNAM,
            AccountUpdateResponse.ScreenField.ACSADL1,
            AccountUpdateResponse.ScreenField.ACSADL2,
            AccountUpdateResponse.ScreenField.ACSCITY,
            AccountUpdateResponse.ScreenField.ACSSTTE,
            AccountUpdateResponse.ScreenField.ACSZIPC,
            AccountUpdateResponse.ScreenField.ACSCTRY,
            AccountUpdateResponse.ScreenField.ACSPH1A,
            AccountUpdateResponse.ScreenField.ACSPH1B,
            AccountUpdateResponse.ScreenField.ACSPH1C,
            AccountUpdateResponse.ScreenField.ACSPH2A,
            AccountUpdateResponse.ScreenField.ACSPH2B,
            AccountUpdateResponse.ScreenField.ACSPH2C,
            AccountUpdateResponse.ScreenField.ACSGOVT,
            AccountUpdateResponse.ScreenField.ACSEFTC,
            AccountUpdateResponse.ScreenField.ACSPFLG,
            AccountUpdateResponse.ScreenField.INFOMSG);

    static final List<AccountUpdateResponse.ScreenField> UNPROTECTED_FIELDS = List.of(
            AccountUpdateResponse.ScreenField.ACSTTUS,
            AccountUpdateResponse.ScreenField.ACRDLIM,
            AccountUpdateResponse.ScreenField.ACSHLIM,
            AccountUpdateResponse.ScreenField.ACURBAL,
            AccountUpdateResponse.ScreenField.ACRCYCR,
            AccountUpdateResponse.ScreenField.ACRCYDB,
            AccountUpdateResponse.ScreenField.OPNYEAR,
            AccountUpdateResponse.ScreenField.OPNMON,
            AccountUpdateResponse.ScreenField.OPNDAY,
            AccountUpdateResponse.ScreenField.EXPYEAR,
            AccountUpdateResponse.ScreenField.EXPMON,
            AccountUpdateResponse.ScreenField.EXPDAY,
            AccountUpdateResponse.ScreenField.RISYEAR,
            AccountUpdateResponse.ScreenField.RISMON,
            AccountUpdateResponse.ScreenField.RISDAY,
            AccountUpdateResponse.ScreenField.DOBYEAR,
            AccountUpdateResponse.ScreenField.DOBMON,
            AccountUpdateResponse.ScreenField.DOBDAY,
            AccountUpdateResponse.ScreenField.AADDGRP,
            AccountUpdateResponse.ScreenField.ACTSSN1,
            AccountUpdateResponse.ScreenField.ACTSSN2,
            AccountUpdateResponse.ScreenField.ACTSSN3,
            AccountUpdateResponse.ScreenField.ACSTFCO,
            AccountUpdateResponse.ScreenField.ACSFNAM,
            AccountUpdateResponse.ScreenField.ACSMNAM,
            AccountUpdateResponse.ScreenField.ACSLNAM,
            AccountUpdateResponse.ScreenField.ACSADL1,
            AccountUpdateResponse.ScreenField.ACSADL2,
            AccountUpdateResponse.ScreenField.ACSCITY,
            AccountUpdateResponse.ScreenField.ACSSTTE,
            AccountUpdateResponse.ScreenField.ACSZIPC,
            AccountUpdateResponse.ScreenField.ACSPH1A,
            AccountUpdateResponse.ScreenField.ACSPH1B,
            AccountUpdateResponse.ScreenField.ACSPH1C,
            AccountUpdateResponse.ScreenField.ACSPH2A,
            AccountUpdateResponse.ScreenField.ACSPH2B,
            AccountUpdateResponse.ScreenField.ACSPH2C,
            AccountUpdateResponse.ScreenField.ACSGOVT,
            AccountUpdateResponse.ScreenField.ACSEFTC,
            AccountUpdateResponse.ScreenField.ACSPFLG);

    static final List<AccountUpdateResponse.ScreenField> CURSOR_ORDER = List.of(
            AccountUpdateResponse.ScreenField.ACSTTUS,
            AccountUpdateResponse.ScreenField.OPNYEAR,
            AccountUpdateResponse.ScreenField.OPNMON,
            AccountUpdateResponse.ScreenField.OPNDAY,
            AccountUpdateResponse.ScreenField.ACRDLIM,
            AccountUpdateResponse.ScreenField.EXPYEAR,
            AccountUpdateResponse.ScreenField.EXPMON,
            AccountUpdateResponse.ScreenField.EXPDAY,
            AccountUpdateResponse.ScreenField.ACSHLIM,
            AccountUpdateResponse.ScreenField.RISYEAR,
            AccountUpdateResponse.ScreenField.RISMON,
            AccountUpdateResponse.ScreenField.RISDAY,
            AccountUpdateResponse.ScreenField.ACURBAL,
            AccountUpdateResponse.ScreenField.ACRCYCR,
            AccountUpdateResponse.ScreenField.ACRCYDB,
            AccountUpdateResponse.ScreenField.ACTSSN1,
            AccountUpdateResponse.ScreenField.ACTSSN2,
            AccountUpdateResponse.ScreenField.ACTSSN3,
            AccountUpdateResponse.ScreenField.DOBYEAR,
            AccountUpdateResponse.ScreenField.DOBMON,
            AccountUpdateResponse.ScreenField.DOBDAY,
            AccountUpdateResponse.ScreenField.ACSTFCO,
            AccountUpdateResponse.ScreenField.ACSFNAM,
            AccountUpdateResponse.ScreenField.ACSMNAM,
            AccountUpdateResponse.ScreenField.ACSLNAM,
            AccountUpdateResponse.ScreenField.ACSADL1,
            AccountUpdateResponse.ScreenField.ACSSTTE,
            AccountUpdateResponse.ScreenField.ACSZIPC,
            AccountUpdateResponse.ScreenField.ACSCITY,
            AccountUpdateResponse.ScreenField.ACSCTRY,
            AccountUpdateResponse.ScreenField.ACSPH1A,
            AccountUpdateResponse.ScreenField.ACSPH1B,
            AccountUpdateResponse.ScreenField.ACSPH1C,
            AccountUpdateResponse.ScreenField.ACSPH2A,
            AccountUpdateResponse.ScreenField.ACSPH2B,
            AccountUpdateResponse.ScreenField.ACSPH2C,
            AccountUpdateResponse.ScreenField.ACSEFTC,
            AccountUpdateResponse.ScreenField.ACSPFLG);

    static final List<AccountUpdateResponse.ScreenField> HIGHLIGHT_ORDER = List.of(
            AccountUpdateResponse.ScreenField.ACSTTUS,
            AccountUpdateResponse.ScreenField.OPNYEAR,
            AccountUpdateResponse.ScreenField.OPNMON,
            AccountUpdateResponse.ScreenField.OPNDAY,
            AccountUpdateResponse.ScreenField.ACRDLIM,
            AccountUpdateResponse.ScreenField.EXPYEAR,
            AccountUpdateResponse.ScreenField.EXPMON,
            AccountUpdateResponse.ScreenField.EXPDAY,
            AccountUpdateResponse.ScreenField.ACSHLIM,
            AccountUpdateResponse.ScreenField.RISYEAR,
            AccountUpdateResponse.ScreenField.RISMON,
            AccountUpdateResponse.ScreenField.RISDAY,
            AccountUpdateResponse.ScreenField.ACURBAL,
            AccountUpdateResponse.ScreenField.ACRCYCR,
            AccountUpdateResponse.ScreenField.ACRCYDB,
            AccountUpdateResponse.ScreenField.ACTSSN1,
            AccountUpdateResponse.ScreenField.ACTSSN2,
            AccountUpdateResponse.ScreenField.ACTSSN3,
            AccountUpdateResponse.ScreenField.DOBYEAR,
            AccountUpdateResponse.ScreenField.DOBMON,
            AccountUpdateResponse.ScreenField.DOBDAY,
            AccountUpdateResponse.ScreenField.ACSTFCO,
            AccountUpdateResponse.ScreenField.ACSFNAM,
            AccountUpdateResponse.ScreenField.ACSMNAM,
            AccountUpdateResponse.ScreenField.ACSLNAM,
            AccountUpdateResponse.ScreenField.ACSADL1,
            AccountUpdateResponse.ScreenField.ACSSTTE,
            AccountUpdateResponse.ScreenField.ACSADL2,
            AccountUpdateResponse.ScreenField.ACSZIPC,
            AccountUpdateResponse.ScreenField.ACSCITY,
            AccountUpdateResponse.ScreenField.ACSCTRY,
            AccountUpdateResponse.ScreenField.ACSPH1A,
            AccountUpdateResponse.ScreenField.ACSPH1B,
            AccountUpdateResponse.ScreenField.ACSPH1C,
            AccountUpdateResponse.ScreenField.ACSPH2A,
            AccountUpdateResponse.ScreenField.ACSPH2B,
            AccountUpdateResponse.ScreenField.ACSPH2C,
            AccountUpdateResponse.ScreenField.ACSPFLG,
            AccountUpdateResponse.ScreenField.ACSEFTC);

    static final int HIGHLIGHT_SITE_COUNT = 39;

    static final int PROTECTABLE_FIELD_COUNT = 44;

    static final int UNPROTECTED_FIELD_COUNT = 40;

    static final int CURSOR_ARM_COUNT = 38;

    static final int RECORD_DATE_LENGTH = 10;

    static final int RECORD_DATE_YEAR_OFFSET = 0;

    static final int RECORD_DATE_MONTH_OFFSET = 5;

    static final int RECORD_DATE_DAY_OFFSET = 8;

    static {
        // Every one of them reaches the operator, is compared against a payload value, or names a CICS
        // resource, so a transcription slip is a parity failure - and one that a diff of a painted screen
        // reports as a mystery rather than as a typo.
        requireLiteral(LIT_THISPGM, "COACTUPC", "LIT-THISPGM", 533);
        requireLiteral(LIT_THISTRANID, "CAUP", "LIT-THISTRANID", 535);
        requireLiteral(LIT_THISMAPSET, "COACTUP ", "LIT-THISMAPSET", 537);
        requireLiteral(LIT_THISMAP, "CACTUPA", "LIT-THISMAP", 539);
        requireLiteral(LIT_CARDUPDATE_PGM, "COCRDUPC", "LIT-CARDUPDATE-PGM", 542);
        requireLiteral(LIT_CARDUPDATE_TRANID, "CCUP", "LIT-CARDUPDATE-TRANID", 544);
        requireLiteral(LIT_CARDUPDATE_MAPSET, "COCRDUP ", "LIT-CARDUPDATE-MAPSET", 546);
        requireLiteral(LIT_CARDUPDATE_MAP, "CCRDUPA", "LIT-CARDUPDATE-MAP", 548);
        requireLiteral(LIT_CCLISTPGM, "COCRDLIC", "LIT-CCLISTPGM", 550);
        requireLiteral(LIT_CCLISTTRANID, "CCLI", "LIT-CCLISTTRANID", 552);
        requireLiteral(LIT_CCLISTMAPSET, "COCRDLI", "LIT-CCLISTMAPSET", 554);
        requireLiteral(LIT_CCLISTMAP, "CCRDSLA", "LIT-CCLISTMAP", 556);
        requireLiteral(LIT_MENUPGM, "COMEN01C", "LIT-MENUPGM", 558);
        requireLiteral(LIT_MENUTRANID, "CM00", "LIT-MENUTRANID", 560);
        requireLiteral(LIT_MENUMAPSET, "COMEN01", "LIT-MENUMAPSET", 562);
        requireLiteral(LIT_MENUMAP, "COMEN1A", "LIT-MENUMAP", 564);
        requireLiteral(LIT_CARDDTLPGM, "COCRDSLC", "LIT-CARDDTLPGM", 566);
        requireLiteral(LIT_CARDDTLTRANID, "CCDL", "LIT-CARDDTLTRANID", 568);
        requireLiteral(LIT_CARDDTLMAPSET, "COCRDSL", "LIT-CARDDTLMAPSET", 570);
        requireLiteral(LIT_CARDDTLMAP, "CCRDSLA", "LIT-CARDDTLMAP", 572);
        requireLiteral(LIT_ACCTFILENAME, "ACCTDAT ", "LIT-ACCTFILENAME", 574);
        requireLiteral(LIT_CUSTFILENAME, "CUSTDAT ", "LIT-CUSTFILENAME", 576);
        requireLiteral(LIT_CARDFILENAME, "CARDDAT ", "LIT-CARDFILENAME", 578);
        requireLiteral(LIT_CARDFILENAME_ACCT_PATH, "CARDAIX ", "LIT-CARDFILENAME-ACCT-PATH", 580);
        requireLiteral(LIT_CARDXREFNAME_ACCT_PATH, "CXACAIX ", "LIT-CARDXREFNAME-ACCT-PATH", 582);
        requireLiteral(LIT_UPPER, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "LIT-UPPER", 588);
        requireLiteral(LIT_LOWER, "abcdefghijklmnopqrstuvwxyz", "LIT-LOWER", 590);
        requireLiteral(LIT_NUMBERS, "0123456789", "LIT-NUMBERS", 592);

        // The DTO pair must agree with this class about which program, transaction, mapset and map this is;
        // the payload carries all four and a disagreement would produce a screen that names the wrong
        // program.
        requireLiteral(AccountUpdateRequest.PROGRAM_NAME, LIT_THISPGM, "COACTUP.CPY's program", 533);
        requireLiteral(AccountUpdateRequest.TRANSACTION_ID, LIT_THISTRANID,
                "COACTUP.CPY's transaction", 535);
        requireLiteral(AccountUpdateRequest.MAP_NAME, LIT_THISMAP, "COACTUP.CPY's map", 539);
        requireLiteral(AccountUpdateResponse.THIS_PROGRAM, LIT_THISPGM, "LIT-THISPGM", 533);
        requireLiteral(AccountUpdateResponse.MAP_NAME, LIT_THISMAP, "LIT-THISMAP", 539);

        requireLiteral(ScreenTitles.CCDA_TITLE01, "      AWS Mainframe Modernization       ",
                "CCDA-TITLE01", "app/cpy/COTTL01Y.cpy:18-19");
        requireLiteral(ScreenTitles.CCDA_TITLE02, "              CardDemo                  ",
                "CCDA-TITLE02", "app/cpy/COTTL01Y.cpy:20-22");

        // The composed file-error message has to be exactly eighty characters wide before it is truncated
        // into WS-RETURN-MSG, and the eight FILLER literals are what make it so.
        int composed = FILE_ERROR_PREFIX.length() + ERROR_OPNAME_LENGTH + FILE_ERROR_ON.length()
                + ERROR_FILE_LENGTH + FILE_ERROR_RETURNED_RESP.length() + ERROR_RESP_LENGTH
                + FILE_ERROR_RESP2.length() + ERROR_RESP_LENGTH + FILE_ERROR_TRAILER.length();
        if (composed != FILE_ERROR_MESSAGE_LENGTH) {
            throw new AssertionError("WS-FILE-ERROR-MESSAGE is declared "
                    + FILE_ERROR_MESSAGE_LENGTH + " characters wide by app/cbl/COACTUPC.cbl:390-411, "
                    + "but this class's literals compose to " + composed);
        }

        // The five field lists are transcriptions of five COBOL statement groups, and a missing or
        // duplicated entry in any of them is a silent parity failure: a field that is never cleared, never
        // protected, never highlighted, or that steals the cursor from the field above it.
        requireListSize(INITIAL_VALUE_FIELDS.size(), INITIAL_VALUE_FIELD_COUNT,
                "3201-SHOW-INITIAL-VALUES' MOVE LOW-VALUES", "2732-2780");
        requireListSize(PROTECTABLE_FIELDS.size(), PROTECTABLE_FIELD_COUNT,
                "3310-PROTECT-ALL-ATTRS' MOVE DFHBMPRF", "3442-3492");
        requireListSize(UNPROTECTED_FIELDS.size(), UNPROTECTED_FIELD_COUNT,
                "3320-UNPROTECT-FEW-ATTRS' MOVE DFHBMFSE", "3500-3562");
        requireListSize(CURSOR_ORDER.size(), CURSOR_ARM_COUNT,
                "3300-SETUP-SCREEN-ATTRS' cursor EVALUATE", "3010-3164");
        requireListSize(HIGHLIGHT_ORDER.size(), HIGHLIGHT_SITE_COUNT,
                "3300-SETUP-SCREEN-ATTRS' COPY CSSETATY sites", "3208-3439");

        int mask = 1 + CURRENCY_INTEGER_TEMPLATE.length() + CURRENCY_DECIMAL_POINT.length()
                + MONETARY_FRACTION_DIGITS;
        if (mask != WS_EDIT_CURRENCY_LENGTH) {
            throw new AssertionError("WS-EDIT-CURRENCY-9-2-F is PIC +ZZZ,ZZZ,ZZZ.99 - "
                    + WS_EDIT_CURRENCY_LENGTH + " characters - at app/cbl/COACTUPC.cbl:373, but this "
                    + "class's template composes to " + mask);
        }
    }

    private static void requireListSize(int actual, int expected, String cobolWhat,
            String cobolLines) {
        if (actual != expected) {
            throw new AssertionError(cobolWhat + " has " + expected + " receivers at "
                    + "app/cbl/COACTUPC.cbl:" + cobolLines + ", but this class transcribes " + actual);
        }
    }

    private static void requireLiteral(String actual, String expected, String cobolName,
            int cobolLine) {
        requireLiteral(actual, expected, cobolName, "app/cbl/COACTUPC.cbl:" + cobolLine);
    }

    private static void requireLiteral(String actual, String expected, String cobolName,
            String where) {
        if (!expected.equals(actual)) {
            throw new AssertionError(cobolName + " is declared as '" + expected + "' at " + where
                    + ", but this class resolved it to '" + actual + "'");
        }
    }

    // COACTUPC's WORKING-STORAGE never becomes a field here - it becomes a Conversation, created per
    // request inside the request method. CardRepository is deliberately absent.

    private final AccountRepository accountRepository;

    private final CardXrefRepository cardXrefRepository;

    private final CustomerRepository customerRepository;

    private final AccountUpdateService accountUpdateService;

    private final AccountDateValidator accountDateValidator;

    private final AreaCodeLookup areaCodeLookup;

    private final Clock clock;

    private final FixedWidthCodec codec;

    /**
     * The seal that carries {@code WS-THIS-PROGCOMMAREA} across a stateless turn without letting a caller
     * write it.
     *
     * <p>See {@link #STATE_PURPOSE} for what the token is bound to, and {@link ConversationStateSeal} for
     * why the area cannot travel as structured JSON.
     */
    private final ConversationStateSeal conversationStateSeal;

    /**
     * Constructs the controller.
     *
     * @param accountRepository     the account master, {@code ACCTDAT}
     * @param cardXrefRepository    the card cross reference and its {@code CXACAIX} path
     * @param customerRepository    the customer master, {@code CUSTDAT}
     * @param accountUpdateService  the write path and the five {@code COMPUTE}s
     * @param accountDateValidator  {@code CSUTLDPY} plus {@code CSUTLDWY}
     * @param areaCodeLookup        {@code CSLKPCDY}
     * @param clock                 the clock behind {@code FUNCTION CURRENT-DATE}
     * @param conversationStateSeal what turns {@code WS-THIS-PROGCOMMAREA} into an opaque token and back,
     *                              so the byte that records "the twenty-four edits already passed" is
     *                              state this screen issues rather than state a caller composes
     * @param datasetCharset        the active dataset code page, from
     *                              {@code @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)}. It
     *                              is what the two record images this transaction rewrites are encoded
     *                              in, so it must be the page both repositories use - never the
     *                              platform default and never a hard-coded one
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public AccountUpdateController(AccountRepository accountRepository,
            CardXrefRepository cardXrefRepository,
            CustomerRepository customerRepository,
            AccountUpdateService accountUpdateService,
            AccountDateValidator accountDateValidator,
            AreaCodeLookup areaCodeLookup,
            Clock clock,
            ConversationStateSeal conversationStateSeal,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset) {
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "An AccountRepository is required: 9300-GETACCTDATA-BYACCT reads ACCTDAT at "
                        + "app/cbl/COACTUPC.cbl:3702-3711 and this controller reaches it no other way");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository,
                "A CardXrefRepository is required: 9200-GETCARDXREF-BYACCT reads the CXACAIX path at "
                        + "app/cbl/COACTUPC.cbl:3653-3663, and it is that read which supplies the "
                        + "customer id the third read needs");
        this.customerRepository = Objects.requireNonNull(customerRepository,
                "A CustomerRepository is required: 9400-GETCUSTDATA-BYCUST reads CUSTDAT at "
                        + "app/cbl/COACTUPC.cbl:3752-3761");
        this.accountUpdateService = Objects.requireNonNull(accountUpdateService,
                "An AccountUpdateService is required: 2000-DECIDE-ACTION performs "
                        + "9600-WRITE-PROCESSING at app/cbl/COACTUPC.cbl:2601-2602 and this controller "
                        + "must not re-implement it");
        this.accountDateValidator = Objects.requireNonNull(accountDateValidator,
                "An AccountDateValidator is required: 1200-EDIT-MAP-INPUTS performs "
                        + "EDIT-DATE-CCYYMMDD four times, at app/cbl/COACTUPC.cbl:1479, :1491, :1504 "
                        + "and :1534");
        this.areaCodeLookup = Objects.requireNonNull(areaCodeLookup,
                "An AreaCodeLookup is required: COPY CSLKPCDY at app/cbl/COACTUPC.cbl:602 supplies the "
                        + "three lookups the phone, state and zip edits test against");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: 3100-SCREEN-INIT reads FUNCTION CURRENT-DATE twice and "
                        + "EDIT-DATE-OF-BIRTH reads it again; reading a clock inline would make every "
                        + "parity case non-deterministic");
        this.conversationStateSeal = Objects.requireNonNull(conversationStateSeal,
                "A ConversationStateSeal is required: 1200-EDIT-MAP-INPUTS skips all twenty-four edits "
                        + "when the commarea reads ACUP-CHANGES-OK-NOT-CONFIRMED "
                        + "(app/cbl/COACTUPC.cbl:1463-1468), so that byte has to be unforgeable while it "
                        + "travels in the payload");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "The active dataset "
                + "code page is required: 9600-WRITE-PROCESSING rewrites a 300-byte ACCOUNT-RECORD and "
                + "a 500-byte CUSTOMER-RECORD at app/cbl/COACTUPC.cbl:4065-4091, and those bytes are "
                + "only right in the page the datasets are stored in; it is never the platform "
                + "default"));
    }

    FixedWidthCodec codec() {
        return codec;
    }

    // Always 200 with a painted screen, because that is what the program does: every arm of 0000-MAIN
    // converges on COMMON-RETURN and sends a map, and a record that could not be found is reported in
    // ERRMSGO rather than by refusing to answer.

    @PutMapping(path = ACCOUNT_UPDATE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScreenResponse<AccountUpdateResponse>> updateAccount(
            @PathVariable("acctId") String acctId,
            @Valid @RequestBody(required = false) AccountUpdateRequest request,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid,
            @RequestParam(name = EIBCALEN_PARAM, required = false) Integer eibcalen,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid) {
        Objects.requireNonNull(acctId, "An account identifier is required in the path: it is what "
                + "ACCTSIDI carries into 1100-RECEIVE-MAP at app/cbl/COACTUPC.cbl:1050 and the RIDFLD "
                + "of the reads at :3656 and :3705");

        AccountUpdateRequest received = bind(acctId, request);
        int commareaLength = resolveEibcalen(eibcalen, received);
        byte attentionIdentifier = resolveAttentionIdentifier(resolveAidParameter(eibaid, eibAid));

        PaintedScreen painted = handle(received, commareaLength, attentionIdentifier);
        AccountUpdateResponse sealed = sealState(painted.response(),
                received.value(AccountUpdateRequest.ScreenField.ACCTSID));
        return ResponseEntity.ok(ScreenResponse.of(sealed,
                screenMetadataOf(sealed, painted.request(), painted.cursorField())));
    }

    /**
     * What one interaction produced: the map area, the input area whose metadata carries the field
     * attributes and the cursor, and the name of the field the cursor landed on.
     *
     * <p>Three values rather than one because the symbolic map is one set of bytes with two views:
     * {@code CACTUPAO}'s values and colours are on the response, and {@code CACTUPAI}'s length and
     * attribute items are on the request, exactly as {@code 3300-SETUP-SCREEN-ATTRS} writes them.
     *
     * @param response the painted {@code CACTUPAO}
     * @param request the input area after {@code 3300}, whose metadata holds every {@code xxxA} and
     *     {@code xxxL}
     * @param cursorField the {@code DFHMDF} label of the field {@code MOVE -1 TO xxxL} selected, or
     *     {@code null} when {@code 3300} never ran
     */
    record PaintedScreen(AccountUpdateResponse response, AccountUpdateRequest request,
            String cursorField) {
        PaintedScreen {
            Objects.requireNonNull(response, "A painted screen carries the CACTUPAO map area");
            Objects.requireNonNull(request, "A painted screen carries the CACTUPAI input area, "
                    + "because 3310-PROTECT-ALL-ATTRS and the cursor EVALUATE write into it");
        }
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

    AccountUpdateRequest bind(String acctId, AccountUpdateRequest request) {
        if (acctId.length() > AccountUpdateRequest.ACCTSID_LENGTH) {
            throw ScreenInputRejectedException.tooWide(ACCTSID_MEMBER,
                    "ACCTSIDI PIC X(" + AccountUpdateRequest.ACCTSID_LENGTH
                            + ") in app/cpy-bms/COACTUP.CPY",
                    AccountUpdateRequest.ACCTSID_LENGTH, acctId.length());
        }
        AccountUpdateRequest received = request == null ? AccountUpdateRequest.initial() : request;
        ScreenInputRejectedException.requireKeyAgreement(ACCTSID_MEMBER, acctId,
                received.value(AccountUpdateRequest.ScreenField.ACCTSID),
                AccountUpdateRequest.ACCTSID_LENGTH, PIC_X_CODEC, NO_CRITERION_IMAGE);
        String identity = PIC_X_CODEC.movePicX(acctId, AccountUpdateRequest.ACCTSID_LENGTH);
        return unsealState(received.withValue(AccountUpdateRequest.ScreenField.ACCTSID, identity),
                identity);
    }

    /**
     * Restores {@code WS-THIS-PROGCOMMAREA} from the token the client sent back, and refuses one this
     * screen did not issue for this account.
     *
     * <p>An absent token is not a refusal: it is {@code EIBCALEN = 0}, the cold start the source branches
     * on at {@code app/cbl/COACTUPC.cbl:880-893}, and it leaves the request carrying
     * {@code CommArea.initialised()} - the state {@code INITIALIZE WS-THIS-PROGCOMMAREA} produces, from
     * which {@code ACUP-DETAILS-NOT-FETCHED} holds and no write arm is reachable. What is refused is a
     * token that is not one, or one issued for another record: accepting either would let a confirmation
     * proved somewhere else stand as proof here.
     *
     * <p>A request that already carries a structured commarea and no token keeps it. That is how the
     * program flow, a service and a parity case construct this request - in Java, where the area is the
     * program's own storage rather than something a caller supplies - and it is why the wire and the
     * in-process contract can differ without the parity corpus changing.
     *
     * @param received the request, already keyed from the URI
     * @param identity the canonical account identifier the token must have been issued for
     * @return the request carrying the restored area
     * @throws ScreenInputRejectedException if the token is not this screen's, or names another account
     */
    private AccountUpdateRequest unsealState(AccountUpdateRequest received, String identity) {
        String token = received.getStateToken();
        if (token.isEmpty()) {
            return received;
        }
        AccountUpdateRequest.CommArea restored = AccountUpdateRequest.CommArea.decode(
                conversationStateSeal.unseal(STATE_TOKEN_MEMBER, STATE_PURPOSE, identity, token),
                codec);
        return received.withCommArea(restored);
    }

    /**
     * Seals {@code WS-THIS-PROGCOMMAREA} into the token the client carries to its next turn.
     *
     * <p>Applied at the HTTP boundary rather than inside the program flow, so every arm of
     * {@code COMMON-RETURN} and the {@code XCTL} arm are covered by one statement and so a parity case,
     * which drives the flow directly and compares the structured area, sees exactly what it always saw.
     *
     * @param painted  the response the interaction produced
     * @param identity the canonical account identifier to bind the token to
     * @return the response carrying its sealed area
     */
    private AccountUpdateResponse sealState(AccountUpdateResponse painted, String identity) {
        return painted.withStateToken(conversationStateSeal.seal(STATE_PURPOSE, identity,
                painted.getCommArea().encode(codec)));
    }

    static int resolveEibcalen(Integer eibcalen, AccountUpdateRequest request) {
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
                    + "because app/cbl/COACTUPC.cbl:880 uses it to decide whether the conversation's "
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

    /**
     * The account half of a detail group: eleven characters items, five of which have a
     * {@code PIC S9(10)V99} redefinition and three of which have a year, month and day redefinition.
     */
    static final class AcctDataArea {
        String acctIdX;

        String activeStatus;

        String currBal;

        String creditLimit;

        String cashCreditLimit;

        String openDate;

        String expiraionDate;

        String reissueDate;

        String currCycCredit;

        String currCycDebit;

        String groupId;

        AcctDataArea() {
            initialize();
        }

        void initialize() {
            acctIdX = spaces(AccountUpdateRequest.AcctSnapshot.ACCT_ID_LENGTH);
            activeStatus = spaces(AccountUpdateRequest.AcctSnapshot.ACTIVE_STATUS_LENGTH);
            currBal = spaces(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            creditLimit = spaces(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            cashCreditLimit = spaces(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            openDate = spaces(AccountUpdateRequest.AcctSnapshot.DATE_LENGTH);
            expiraionDate = spaces(AccountUpdateRequest.AcctSnapshot.DATE_LENGTH);
            reissueDate = spaces(AccountUpdateRequest.AcctSnapshot.DATE_LENGTH);
            currCycCredit = spaces(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            currCycDebit = spaces(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            groupId = spaces(AccountUpdateRequest.AcctSnapshot.GROUP_ID_LENGTH);
        }

        void moveLowValues() {
            acctIdX = lowValues(AccountUpdateRequest.AcctSnapshot.ACCT_ID_LENGTH);
            activeStatus = lowValues(AccountUpdateRequest.AcctSnapshot.ACTIVE_STATUS_LENGTH);
            currBal = lowValues(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            creditLimit = lowValues(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            cashCreditLimit = lowValues(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            openDate = lowValues(AccountUpdateRequest.AcctSnapshot.DATE_LENGTH);
            expiraionDate = lowValues(AccountUpdateRequest.AcctSnapshot.DATE_LENGTH);
            reissueDate = lowValues(AccountUpdateRequest.AcctSnapshot.DATE_LENGTH);
            currCycCredit = lowValues(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            currCycDebit = lowValues(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            groupId = lowValues(AccountUpdateRequest.AcctSnapshot.GROUP_ID_LENGTH);
        }

        AccountUpdateRequest.AcctSnapshot toSnapshot() {
            return new AccountUpdateRequest.AcctSnapshot(acctIdX, activeStatus, currBal, creditLimit,
                    cashCreditLimit, openDate, expiraionDate, reissueDate, currCycCredit, currCycDebit,
                    groupId);
        }

        void fromSnapshot(AccountUpdateRequest.AcctSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "A carried account group is required");
            acctIdX = snapshot.acctIdX();
            activeStatus = snapshot.activeStatus();
            currBal = snapshot.currBal();
            creditLimit = snapshot.creditLimit();
            cashCreditLimit = snapshot.cashCreditLimit();
            openDate = snapshot.openDate();
            expiraionDate = snapshot.expiraionDate();
            reissueDate = snapshot.reissueDate();
            currCycCredit = snapshot.currCycCredit();
            currCycDebit = snapshot.currCycDebit();
            groupId = snapshot.groupId();
        }

        long acctIdN() {
            return toSnapshot().acctId();
        }

        AccountUpdateService.AccountData toAccountData() {
            AccountUpdateRequest.AcctSnapshot snapshot = toSnapshot();
            return new AccountUpdateService.AccountData(snapshot.acctId(),
                    activeStatus,
                    snapshot.currBalN(),
                    snapshot.creditLimitN(),
                    snapshot.cashCreditLimitN(),
                    openYear(), openMon(), openDay(),
                    expYear(), expMon(), expDay(),
                    reissueYear(), reissueMon(), reissueDay(),
                    snapshot.currCycCreditN(),
                    snapshot.currCycDebitN(),
                    groupId);
        }

        void setAcctIdN(long value) {
            acctIdX = PIC_X_CODEC.movePic9(Long.toString(value),
                    AccountUpdateRequest.AcctSnapshot.ACCT_ID_LENGTH);
        }

        BigDecimal currBalN() {
            return toSnapshot().currBalN();
        }

        BigDecimal creditLimitN() {
            return toSnapshot().creditLimitN();
        }

        BigDecimal cashCreditLimitN() {
            return toSnapshot().cashCreditLimitN();
        }

        BigDecimal currCycCreditN() {
            return toSnapshot().currCycCreditN();
        }

        BigDecimal currCycDebitN() {
            return toSnapshot().currCycDebitN();
        }

        void setCurrBalN(BigDecimal value) {
            currBal = monetaryImage(value);
        }

        void setCreditLimitN(BigDecimal value) {
            creditLimit = monetaryImage(value);
        }

        void setCashCreditLimitN(BigDecimal value) {
            cashCreditLimit = monetaryImage(value);
        }

        void setCurrCycCreditN(BigDecimal value) {
            currCycCredit = monetaryImage(value);
        }

        void setCurrCycDebitN(BigDecimal value) {
            currCycDebit = monetaryImage(value);
        }

        String openYear() {
            return yearOf(openDate);
        }

        String openMon() {
            return monthOf(openDate);
        }

        String openDay() {
            return dayOf(openDate);
        }

        String expYear() {
            return yearOf(expiraionDate);
        }

        String expMon() {
            return monthOf(expiraionDate);
        }

        String expDay() {
            return dayOf(expiraionDate);
        }

        String reissueYear() {
            return yearOf(reissueDate);
        }

        String reissueMon() {
            return monthOf(reissueDate);
        }

        String reissueDay() {
            return dayOf(reissueDate);
        }

        void setOpenYear(String value) {
            openDate = withYear(openDate, value);
        }

        void setOpenMon(String value) {
            openDate = withMonth(openDate, value);
        }

        void setOpenDay(String value) {
            openDate = withDay(openDate, value);
        }

        void setExpYear(String value) {
            expiraionDate = withYear(expiraionDate, value);
        }

        void setExpMon(String value) {
            expiraionDate = withMonth(expiraionDate, value);
        }

        void setExpDay(String value) {
            expiraionDate = withDay(expiraionDate, value);
        }

        void setReissueYear(String value) {
            reissueDate = withYear(reissueDate, value);
        }

        void setReissueMon(String value) {
            reissueDate = withMonth(reissueDate, value);
        }

        void setReissueDay(String value) {
            reissueDate = withDay(reissueDate, value);
        }
    }

    /**
     * The customer half of a detail group: eighteen character items over 330 bytes.
     */
    static final class CustDataArea {
        String custIdX;

        String firstName;

        String middleName;

        String lastName;

        String addrLine1;

        String addrLine2;

        String addrLine3;

        String addrStateCd;

        String addrCountryCd;

        String addrZip;

        String phoneNum1;

        String phoneNum2;

        String ssnX;

        String govtIssuedId;

        String dobYyyyMmDd;

        String eftAccountId;

        String priHolderInd;

        String ficoScoreX;

        CustDataArea() {
            initialize();
        }

        void initialize() {
            custIdX = spaces(AccountUpdateRequest.CustSnapshot.CUST_ID_LENGTH);
            firstName = spaces(AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
            middleName = spaces(AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
            lastName = spaces(AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
            addrLine1 = spaces(AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
            addrLine2 = spaces(AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
            addrLine3 = spaces(AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
            addrStateCd = spaces(AccountUpdateRequest.CustSnapshot.ADDR_STATE_CD_LENGTH);
            addrCountryCd = spaces(AccountUpdateRequest.CustSnapshot.ADDR_COUNTRY_CD_LENGTH);
            addrZip = spaces(AccountUpdateRequest.CustSnapshot.ADDR_ZIP_LENGTH);
            phoneNum1 = spaces(AccountUpdateRequest.CustSnapshot.PHONE_NUM_LENGTH);
            phoneNum2 = spaces(AccountUpdateRequest.CustSnapshot.PHONE_NUM_LENGTH);
            ssnX = spaces(AccountUpdateRequest.CustSnapshot.SSN_LENGTH);
            govtIssuedId = spaces(AccountUpdateRequest.CustSnapshot.GOVT_ISSUED_ID_LENGTH);
            dobYyyyMmDd = spaces(AccountUpdateRequest.CustSnapshot.DOB_LENGTH);
            eftAccountId = spaces(AccountUpdateRequest.CustSnapshot.EFT_ACCOUNT_ID_LENGTH);
            priHolderInd = spaces(AccountUpdateRequest.CustSnapshot.PRI_HOLDER_IND_LENGTH);
            ficoScoreX = spaces(AccountUpdateRequest.CustSnapshot.FICO_SCORE_LENGTH);
        }

        AccountUpdateRequest.CustSnapshot toSnapshot() {
            return new AccountUpdateRequest.CustSnapshot(custIdX, firstName, middleName, lastName,
                    addrLine1, addrLine2, addrLine3, addrStateCd, addrCountryCd, addrZip, phoneNum1,
                    phoneNum2, ssnX, govtIssuedId, dobYyyyMmDd, eftAccountId, priHolderInd, ficoScoreX);
        }

        void fromSnapshot(AccountUpdateRequest.CustSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "A carried customer group is required");
            custIdX = snapshot.custIdX();
            firstName = snapshot.firstName();
            middleName = snapshot.middleName();
            lastName = snapshot.lastName();
            addrLine1 = snapshot.addrLine1();
            addrLine2 = snapshot.addrLine2();
            addrLine3 = snapshot.addrLine3();
            addrStateCd = snapshot.addrStateCd();
            addrCountryCd = snapshot.addrCountryCd();
            addrZip = snapshot.addrZip();
            phoneNum1 = snapshot.phoneNum1();
            phoneNum2 = snapshot.phoneNum2();
            ssnX = snapshot.ssnX();
            govtIssuedId = snapshot.govtIssuedId();
            dobYyyyMmDd = snapshot.dobYyyyMmDd();
            eftAccountId = snapshot.eftAccountId();
            priHolderInd = snapshot.priHolderInd();
            ficoScoreX = snapshot.ficoScoreX();
        }

        int custIdN() {
            return (int) toSnapshot().custId();
        }

        String ssn1() {
            return slice(ssnX, 0, AccountUpdateRequest.CustSnapshot.SSN_PART_1_LENGTH);
        }

        String ssn2() {
            return slice(ssnX, AccountUpdateRequest.CustSnapshot.SSN_PART_1_LENGTH,
                    AccountUpdateRequest.CustSnapshot.SSN_PART_2_LENGTH);
        }

        String ssn3() {
            return slice(ssnX, AccountUpdateRequest.CustSnapshot.SSN_PART_1_LENGTH
                            + AccountUpdateRequest.CustSnapshot.SSN_PART_2_LENGTH,
                    AccountUpdateRequest.CustSnapshot.SSN_PART_3_LENGTH);
        }

        void setSsn1(String value) {
            ssnX = splice(ssnX, 0, AccountUpdateRequest.CustSnapshot.SSN_PART_1_LENGTH, value);
        }

        void setSsn2(String value) {
            ssnX = splice(ssnX, AccountUpdateRequest.CustSnapshot.SSN_PART_1_LENGTH,
                    AccountUpdateRequest.CustSnapshot.SSN_PART_2_LENGTH, value);
        }

        void setSsn3(String value) {
            ssnX = splice(ssnX, AccountUpdateRequest.CustSnapshot.SSN_PART_1_LENGTH
                            + AccountUpdateRequest.CustSnapshot.SSN_PART_2_LENGTH,
                    AccountUpdateRequest.CustSnapshot.SSN_PART_3_LENGTH, value);
        }

        String phoneNum1A() {
            return phoneArea(phoneNum1);
        }

        String phoneNum1B() {
            return phonePrefix(phoneNum1);
        }

        String phoneNum1C() {
            return phoneLine(phoneNum1);
        }

        String phoneNum2A() {
            return phoneArea(phoneNum2);
        }

        String phoneNum2B() {
            return phonePrefix(phoneNum2);
        }

        String phoneNum2C() {
            return phoneLine(phoneNum2);
        }

        void setPhoneNum1A(String value) {
            phoneNum1 = withPhoneArea(phoneNum1, value);
        }

        void setPhoneNum1B(String value) {
            phoneNum1 = withPhonePrefix(phoneNum1, value);
        }

        void setPhoneNum1C(String value) {
            phoneNum1 = withPhoneLine(phoneNum1, value);
        }

        void setPhoneNum2A(String value) {
            phoneNum2 = withPhoneArea(phoneNum2, value);
        }

        void setPhoneNum2B(String value) {
            phoneNum2 = withPhonePrefix(phoneNum2, value);
        }

        void setPhoneNum2C(String value) {
            phoneNum2 = withPhoneLine(phoneNum2, value);
        }

        AccountUpdateService.CustomerData toCustomerData() {
            AccountUpdateRequest.CustSnapshot snapshot = toSnapshot();
            return new AccountUpdateService.CustomerData((int) snapshot.custId(),
                    firstName, middleName, lastName,
                    addrLine1, addrLine2, addrLine3,
                    addrStateCd, addrCountryCd, addrZip,
                    phoneNum1, phoneNum2,
                    (int) snapshot.ssn(),
                    govtIssuedId,
                    dobYear(), dobMon(), dobDay(),
                    eftAccountId, priHolderInd,
                    snapshot.ficoScore());
        }

        int ficoScoreN() {
            return toSnapshot().ficoScore();
        }

        String dobYear() {
            return yearOf(dobYyyyMmDd);
        }

        String dobMon() {
            return monthOf(dobYyyyMmDd);
        }

        String dobDay() {
            return dayOf(dobYyyyMmDd);
        }

        void setDobYear(String value) {
            dobYyyyMmDd = withYear(dobYyyyMmDd, value);
        }

        void setDobMon(String value) {
            dobYyyyMmDd = withMonth(dobYyyyMmDd, value);
        }

        void setDobDay(String value) {
            dobYyyyMmDd = withDay(dobYyyyMmDd, value);
        }

        boolean ficoRangeIsValid() {
            return toSnapshot().ficoRangeIsValid();
        }
    }

    static String spaces(int length) {
        return AccountUpdateResponse.spaces(length);
    }

    static String lowValues(int length) {
        return AccountUpdateResponse.lowValues(length);
    }

    static String slice(String value, int zeroOffset, int partLength) {
        String image = PIC_X_CODEC.movePicX(value == null ? "" : value, zeroOffset + partLength);
        return image.substring(zeroOffset, zeroOffset + partLength);
    }

    static String splice(String span, int zeroOffset, int partLength, String value) {
        int spanWidth = Math.max(span == null ? 0 : span.length(), zeroOffset + partLength);
        String image = PIC_X_CODEC.movePicX(span == null ? "" : span, spanWidth);
        String part = PIC_X_CODEC.movePicX(value == null ? "" : value, partLength);
        return image.substring(0, zeroOffset) + part + image.substring(zeroOffset + partLength);
    }

    static String yearOf(String date) {
        return slice(date, 0, AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH);
    }

    static String monthOf(String date) {
        return slice(date, AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH,
                AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH);
    }

    static String dayOf(String date) {
        return slice(date, AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH
                        + AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH,
                AccountUpdateRequest.AcctSnapshot.DATE_DAY_LENGTH);
    }

    static String withYear(String date, String value) {
        return splice(date, 0, AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH, value);
    }

    static String withMonth(String date, String value) {
        return splice(date, AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH,
                AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH, value);
    }

    static String withDay(String date, String value) {
        return splice(date, AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH
                        + AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH,
                AccountUpdateRequest.AcctSnapshot.DATE_DAY_LENGTH, value);
    }

    static String phoneArea(String phone) {
        return slice(phone, AccountUpdateRequest.CustSnapshot.PHONE_AREA_CODE_RELATIVE_OFFSET,
                AccountUpdateRequest.CustSnapshot.PHONE_AREA_CODE_LENGTH);
    }

    static String phonePrefix(String phone) {
        return slice(phone, AccountUpdateRequest.CustSnapshot.PHONE_PREFIX_RELATIVE_OFFSET,
                AccountUpdateRequest.CustSnapshot.PHONE_PREFIX_LENGTH);
    }

    static String phoneLine(String phone) {
        return slice(phone, AccountUpdateRequest.CustSnapshot.PHONE_LINE_NUMBER_RELATIVE_OFFSET,
                AccountUpdateRequest.CustSnapshot.PHONE_LINE_NUMBER_LENGTH);
    }

    static String withPhoneArea(String phone, String value) {
        return splice(atPhoneWidth(phone),
                AccountUpdateRequest.CustSnapshot.PHONE_AREA_CODE_RELATIVE_OFFSET,
                AccountUpdateRequest.CustSnapshot.PHONE_AREA_CODE_LENGTH, value);
    }

    static String withPhonePrefix(String phone, String value) {
        return splice(atPhoneWidth(phone),
                AccountUpdateRequest.CustSnapshot.PHONE_PREFIX_RELATIVE_OFFSET,
                AccountUpdateRequest.CustSnapshot.PHONE_PREFIX_LENGTH, value);
    }

    static String withPhoneLine(String phone, String value) {
        return splice(atPhoneWidth(phone),
                AccountUpdateRequest.CustSnapshot.PHONE_LINE_NUMBER_RELATIVE_OFFSET,
                AccountUpdateRequest.CustSnapshot.PHONE_LINE_NUMBER_LENGTH, value);
    }

    private static String atPhoneWidth(String phone) {
        return PIC_X_CODEC.movePicX(phone == null ? "" : phone,
                AccountUpdateRequest.CustSnapshot.PHONE_NUM_LENGTH);
    }

    static String monetaryImage(BigDecimal value) {
        return PIC_X_CODEC.encodeSignedScaled(value == null ? BigDecimal.ZERO : value,
                MONETARY_INTEGER_DIGITS, MONETARY_FRACTION_DIGITS);
    }

    static String editCurrency92(BigDecimal value) {
        BigDecimal stored = storeMonetary(value);
        boolean negative = stored.signum() < 0;

        String allDigits = PIC_X_CODEC.movePic9(stored.abs().unscaledValue().toString(),
                MONETARY_INTEGER_DIGITS + MONETARY_FRACTION_DIGITS);
        String integerDigits = allDigits.substring(0, MONETARY_INTEGER_DIGITS);
        String fractionDigits = allDigits.substring(MONETARY_INTEGER_DIGITS);

        String shown = integerDigits.substring(MONETARY_INTEGER_DIGITS - CURRENCY_INTEGER_DIGITS);

        StringBuilder rendered = new StringBuilder(WS_EDIT_CURRENCY_LENGTH);
        rendered.append(negative ? MINUS_SIGN : PLUS_SIGN);
        boolean suppressing = true;
        int digitIndex = 0;
        for (int index = 0; index < CURRENCY_INTEGER_TEMPLATE.length(); index++) {
            char position = CURRENCY_INTEGER_TEMPLATE.charAt(index);
            if (position == SUPPRESSION_CHARACTER) {
                char digit = shown.charAt(digitIndex);
                digitIndex++;
                if (digit != ZERO_DIGIT) {
                    suppressing = false;
                }
                rendered.append(suppressing ? SPACE : digit);
            } else {
                rendered.append(suppressing ? SPACE : position);
            }
        }
        rendered.append(CURRENCY_DECIMAL_POINT).append(fractionDigits);
        return rendered.toString();
    }

    /**
     * A COBOL store into a {@code PIC S9(p)V99} receiver: scale exactly 2, excess fractional digits
     * truncated.
     *
     * @param value the value to store; {@code null} is the freshly initialised span, which is zero
     * @return the value at scale 2, never {@code null}
     */
    static BigDecimal storeMonetary(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(AccountUpdateResponse.MONETARY_SCALE,
                        AccountUpdateResponse.MONETARY_ROUNDING);
    }

    static final class Conversation {
        int eibcalen;

        byte eibAid;

        int wsRespCd;

        int wsReasCd;

        String wsTranid;

        String wsUctrans;

        String wsEditVariableName;

        String wsEditSignedNumber9v2X;

        String wsFlgSignedNumberEdit;

        String wsEditAlphanumOnly;

        int wsEditAlphanumLength;

        String wsEditAlphaOnlyFlags;

        String wsEditAlphanumOnlyFlags;

        String wsEditMandatoryFlags;

        String wsEditYesNo;

        String wsEditUsPhoneNum;

        String wsEditUsPhoneaFlg;

        String wsEditEditUsPhoneb;

        String wsEditEditPhonec;

        String wsEditUsSsnPart1Flgs;

        String wsEditUsSsnPart2Flgs;

        String wsEditUsSsnPart3Flgs;

        String wsCurrDate;

        String wsDatachangedFlag;

        String wsInputFlag;

        String wsReturnFlag;

        String wsPfkFlag;

        String wsEditAcctFlag;

        String wsEditCustFlag;

        final Map<AccountUpdateResponse.ScreenField, String> wsNonKeyFlags =
                new EnumMap<>(AccountUpdateResponse.ScreenField.class);

        String wsCardRidCardnum;

        String wsCardRidCustId;

        String wsCardRidAcctId;

        String wsAccountMasterReadFlag;

        String wsCustMasterReadFlag;

        String errorOpname;

        String errorFile;

        String errorResp;

        String errorResp2;

        String acupNewCreditLimitX;

        String acupNewCashCreditLimitX;

        String acupNewCurrBalX;

        String acupNewCurrCycCreditX;

        String acupNewCurrCycDebitX;

        String wsLongMsg;

        String wsInfoMsg;

        String wsReturnMsg;

        CardScreenState ccWorkArea;

        NavigationContext carddemoCommarea;

        AccountUpdateRequest.ChangeAction acupChangeAction;

        final AcctDataArea acupOldAcct = new AcctDataArea();

        final CustDataArea acupOldCust = new CustDataArea();

        final AcctDataArea acupNewAcct = new AcctDataArea();

        final CustDataArea acupNewCust = new CustDataArea();

        AccountRecord accountRecord;

        CustomerRecord customerRecord;

        Optional<CardXrefRecord> cardXrefRecord = Optional.empty();

        SystemMessages.AbendData abendData = SystemMessages.AbendData.spaces();

        DateHeader dateHeader;

        String wsCommarea;

        AccountUpdateResponse cactupao;

        AccountUpdateRequest cactupai;

        String cursorField;

        boolean returned;

        Conversation(Charset datasetCharset) {
            Objects.requireNonNull(datasetCharset,
                    "ACCOUNT-RECORD is 300 bytes of storage and needs the code page they are written in");
            this.accountRecord = new AccountRecord(datasetCharset);
            this.customerRecord = new CustomerRecord();
        }

        long wsCardRidAcctIdN() {
            return PIC_X_CODEC.decodePic9(wsCardRidAcctId);
        }

        long wsCardRidCustIdN() {
            return PIC_X_CODEC.decodePic9(wsCardRidCustId);
        }

        boolean inputOk() {
            return INPUT_OK.equals(wsInputFlag);
        }

        boolean inputError() {
            return INPUT_ERROR.equals(wsInputFlag);
        }

        boolean inputPending() {
            return INPUT_PENDING.equals(wsInputFlag);
        }

        boolean pfkValid() {
            return PFK_VALID.equals(wsPfkFlag);
        }

        boolean pfkInvalid() {
            return PFK_INVALID.equals(wsPfkFlag);
        }

        boolean noChangesFound() {
            return NO_CHANGES_FOUND.equals(wsDatachangedFlag);
        }

        boolean changeHasOccurred() {
            return CHANGE_HAS_OCCURRED.equals(wsDatachangedFlag);
        }

        boolean flgAcctfilterIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditAcctFlag);
        }

        boolean flgAcctfilterNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditAcctFlag);
        }

        boolean flgAcctfilterBlank() {
            return FLG_FILTER_BLANK.equals(wsEditAcctFlag);
        }

        boolean flgCustfilterIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditCustFlag);
        }

        boolean flgCustfilterNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditCustFlag);
        }

        boolean foundAcctInMaster() {
            return FOUND_IN_MASTER.equals(wsAccountMasterReadFlag);
        }

        boolean foundCustInMaster() {
            return FOUND_IN_MASTER.equals(wsCustMasterReadFlag);
        }

        boolean returnMsgOff() {
            return AccountUpdateService.isReturnMessageOff(wsReturnMsg);
        }

        boolean wsNoInfoMessage() {
            return spaces(WS_INFO_MSG_LENGTH).equals(wsInfoMsg)
                    || lowValues(WS_INFO_MSG_LENGTH).equals(wsInfoMsg);
        }

        boolean foundAccountData() {
            return atInfoWidth(INFO_FOUND_ACCOUNT_DATA).equals(wsInfoMsg);
        }

        boolean promptForConfirmation() {
            return atInfoWidth(INFO_PROMPT_FOR_CONFIRMATION).equals(wsInfoMsg);
        }

        boolean noChangesDetected() {
            return atReturnWidth(MSG_NO_CHANGES_DETECTED).equals(wsReturnMsg);
        }

        boolean didNotFindAcctInAcctdat() {
            return atReturnWidth(MSG_DID_NOT_FIND_ACCT_IN_ACCTDAT).equals(wsReturnMsg);
        }

        boolean didNotFindCustInCustdat() {
            return atReturnWidth(MSG_DID_NOT_FIND_CUST_IN_CUSTDAT).equals(wsReturnMsg);
        }

        String flag(AccountUpdateResponse.ScreenField field) {
            return wsNonKeyFlags.getOrDefault(field, FLG_ISVALID);
        }

        void setFlag(AccountUpdateResponse.ScreenField field, String value) {
            wsNonKeyFlags.put(field, value);
        }

        boolean flagNotOk(AccountUpdateResponse.ScreenField field) {
            return FLG_NOT_OK.equals(flag(field));
        }

        boolean flagBlank(AccountUpdateResponse.ScreenField field) {
            return FLG_BLANK.equals(flag(field));
        }

        boolean flagIsvalid(AccountUpdateResponse.ScreenField field) {
            return FLG_ISVALID.equals(flag(field));
        }

        void clearNonKeyFlags() {
            wsNonKeyFlags.clear();
        }

        AccountUpdateRequest.CommArea toCommArea() {
            return new AccountUpdateRequest.CommArea(acupChangeAction,
                    new AccountUpdateRequest.Details(AccountUpdateRequest.DetailGroup.OLD,
                            acupOldAcct.toSnapshot(), acupOldCust.toSnapshot()),
                    new AccountUpdateRequest.Details(AccountUpdateRequest.DetailGroup.NEW,
                            acupNewAcct.toSnapshot(), acupNewCust.toSnapshot()));
        }
    }

    static String atInfoWidth(String message) {
        return PIC_X_CODEC.movePicX(message, WS_INFO_MSG_LENGTH);
    }

    static String atReturnWidth(String message) {
        return PIC_X_CODEC.movePicX(message, WS_RETURN_MSG_LENGTH);
    }

    PaintedScreen handle(AccountUpdateRequest request, int eibcalen, byte eibAid) {
        Objects.requireNonNull(request, "A request is required: COACTUPC is entered with a terminal "
                + "input area, and an absent one is spaces rather than nothing");
        Conversation task = new Conversation(codec.charset());
        try {
            main0000(request, task, eibcalen, eibAid);
        } catch (AbendException alreadyAbending) {
            throw alreadyAbending;
        } catch (ScreenInputRejectedException callersInput) {
            throw callersInput;
        } catch (RuntimeException abend) {
            throw abendRoutine(task, abend);
        }
        return new PaintedScreen(task.cactupao, task.cactupai, task.cursorField);
    }

    void main0000(AccountUpdateRequest request, Conversation task, int eibcalen, byte eibAid) {
        initializeStorage(request, task, eibcalen, eibAid);
        task.wsTranid = PIC_X_CODEC.movePicX(LIT_THISTRANID, WS_TRANID_LENGTH);
        task.wsReturnMsg = WS_RETURN_MSG_OFF;
        restoreCommarea(task);
        storePfKeyYYYY(task);
        coerceInvalidAid(task);
        dispatch0000(task);
    }

    void initializeStorage(AccountUpdateRequest request, Conversation task, int eibcalen, byte eibAid) {
        task.eibcalen = eibcalen;
        task.eibAid = eibAid;

        // The carried work area is copied in first and then blanked, which is why the AID the caller sent
        // does not survive: CCARD-AID is set from EIBAID a few statements later, never from the payload.
        task.ccWorkArea = new CardScreenState(request.getCardScreenState());
        task.ccWorkArea.initializeWorkArea();

        task.wsRespCd = FileStatus.NORMAL;
        task.wsReasCd = FileStatus.NO_REASON_CODE;
        task.wsTranid = spaces(WS_TRANID_LENGTH);
        task.wsUctrans = spaces(WS_TRANID_LENGTH);
        task.wsEditVariableName = spaces(WS_EDIT_VARIABLE_NAME_LENGTH);
        task.wsEditSignedNumber9v2X = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.wsFlgSignedNumberEdit = INITIALIZED_FLAG;
        task.wsEditAlphanumOnly = spaces(WS_EDIT_ALPHANUM_ONLY_LENGTH);
        task.wsEditAlphanumLength = 0;
        task.wsEditAlphaOnlyFlags = INITIALIZED_FLAG;
        task.wsEditAlphanumOnlyFlags = INITIALIZED_FLAG;
        task.wsEditMandatoryFlags = INITIALIZED_FLAG;
        task.wsEditYesNo = INITIALIZED_FLAG;
        task.wsEditUsPhoneNum = spaces(WS_EDIT_US_PHONE_NUM_LENGTH);
        task.wsEditUsPhoneaFlg = INITIALIZED_FLAG;
        task.wsEditEditUsPhoneb = INITIALIZED_FLAG;
        task.wsEditEditPhonec = INITIALIZED_FLAG;
        task.wsEditUsSsnPart1Flgs = INITIALIZED_FLAG;
        task.wsEditUsSsnPart2Flgs = INITIALIZED_FLAG;
        task.wsEditUsSsnPart3Flgs = INITIALIZED_FLAG;
        task.wsCurrDate = spaces(WS_CURR_DATE_LENGTH);
        task.wsDatachangedFlag = INITIALIZED_FLAG;
        task.wsInputFlag = INITIALIZED_FLAG;
        task.wsReturnFlag = INITIALIZED_FLAG;
        task.wsPfkFlag = INITIALIZED_FLAG;
        task.wsEditAcctFlag = INITIALIZED_FLAG;
        task.wsEditCustFlag = INITIALIZED_FLAG;
        for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.FIELDS) {
            task.wsNonKeyFlags.put(field, INITIALIZED_FLAG);
        }
        task.wsCardRidCardnum = spaces(WS_CARD_RID_CARDNUM_LENGTH);
        task.wsCardRidCustId = PIC_X_CODEC.movePic9(0L, WS_CARD_RID_CUST_ID_LENGTH);
        task.wsCardRidAcctId = PIC_X_CODEC.movePic9(0L, WS_CARD_RID_ACCT_ID_LENGTH);
        task.wsAccountMasterReadFlag = INITIALIZED_FLAG;
        task.wsCustMasterReadFlag = INITIALIZED_FLAG;
        task.errorOpname = spaces(ERROR_OPNAME_LENGTH);
        task.errorFile = spaces(ERROR_FILE_LENGTH);
        task.errorResp = spaces(ERROR_RESP_LENGTH);
        task.errorResp2 = spaces(ERROR_RESP_LENGTH);
        task.acupNewCreditLimitX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCashCreditLimitX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCurrBalX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCurrCycCreditX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCurrCycDebitX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.wsLongMsg = spaces(WS_LONG_MSG_LENGTH);
        task.wsInfoMsg = spaces(WS_INFO_MSG_LENGTH);
        task.wsReturnMsg = WS_RETURN_MSG_OFF;

        task.wsCommarea = spaces(WS_COMMAREA_LENGTH);

        // ACCOUNT-RECORD and CUSTOMER-RECORD are deliberately NOT reset.
        task.cardXrefRecord = Optional.empty();
        task.abendData = SystemMessages.AbendData.spaces();

        // WS-THIS-PROGCOMMAREA - deliberately NOT initialised here. It is restored from the payload, or
        // blanked by restoreCommarea's first arm, and nowhere else.
        AccountUpdateRequest.CommArea carried = request.getCommArea();
        task.acupChangeAction = carried.changeAction();
        task.acupOldAcct.fromSnapshot(carried.oldDetails().acct());
        task.acupOldCust.fromSnapshot(carried.oldDetails().cust());
        task.acupNewAcct.fromSnapshot(carried.newDetails().acct());
        task.acupNewCust.fromSnapshot(carried.newDetails().cust());

        task.carddemoCommarea = request.hasNavigationContext()
                ? request.getNavigationContext()
                : NavigationContext.empty();

        task.cactupai = request;
        task.cactupao = AccountUpdateResponse.initial();
        task.cursorField = null;
        task.returned = false;
    }

    void restoreCommarea(Conversation task) {
        boolean noCommareaPassed = task.eibcalen == NO_COMMAREA_LENGTH;
        boolean freshEntryFromMenu = LIT_MENUPGM.equals(PIC_X_CODEC.movePicX(
                task.carddemoCommarea.fromProgram(), NavigationContext.FROM_PROGRAM_LENGTH))
                && !task.carddemoCommarea.isReenter();
        if (noCommareaPassed || freshEntryFromMenu) {
            task.carddemoCommarea = NavigationContext.empty();
            task.acupOldAcct.initialize();
            task.acupOldCust.initialize();
            task.acupNewAcct.initialize();
            task.acupNewCust.initialize();
            task.carddemoCommarea = task.carddemoCommarea.withPgmEnter();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.initial();
            return;
        }
        task.carddemoCommarea = NavigationContext.fromFixedWidth(
                codec, task.carddemoCommarea.toFixedWidth(codec));
        AccountUpdateRequest.CommArea atWidth =
                AccountUpdateRequest.CommArea.decode(task.toCommArea().encode(codec), codec);
        task.acupChangeAction = atWidth.changeAction();
        task.acupOldAcct.fromSnapshot(atWidth.oldDetails().acct());
        task.acupOldCust.fromSnapshot(atWidth.oldDetails().cust());
        task.acupNewAcct.fromSnapshot(atWidth.newDetails().acct());
        task.acupNewCust.fromSnapshot(atWidth.newDetails().cust());
    }

    void storePfKeyYYYY(Conversation task) {
        Optional<PfKeyResolver.AidKey> stored =
                PfKeyResolver.storePfKey(task.eibAid, task.ccWorkArea.aidKey());
        stored.ifPresent(task.ccWorkArea::setCcardAidCondition);
    }

    void coerceInvalidAid(Conversation task) {
        task.wsPfkFlag = PFK_INVALID;
        if (task.ccWorkArea.isCcardAidEnter()
                || task.ccWorkArea.isCcardAidPfk03()
                || (task.ccWorkArea.isCcardAidPfk05()
                        && task.acupChangeAction.isChangesOkNotConfirmed())
                || (task.ccWorkArea.isCcardAidPfk12()
                        && !task.acupChangeAction.isDetailsNotFetched())) {
            task.wsPfkFlag = PFK_VALID;
        }
        if (task.pfkInvalid()) {
            task.ccWorkArea.setCcardAidCondition(PfKeyResolver.AidKey.ENTER);
        }
    }

    void dispatch0000(Conversation task) {
        if (task.ccWorkArea.isCcardAidPfk03()) {
            transferControl0000(task);
            return;
        }
        boolean detailsNotFetchedOnEnter = task.acupChangeAction.isDetailsNotFetched()
                && task.carddemoCommarea.isEnter();
        boolean enteredFromMenu = LIT_MENUPGM.equals(PIC_X_CODEC.movePicX(
                task.carddemoCommarea.fromProgram(), NavigationContext.FROM_PROGRAM_LENGTH))
                && !task.carddemoCommarea.isReenter();
        if (detailsNotFetchedOnEnter || enteredFromMenu) {
            initializeThisProgCommarea(task);
            sendMap3000(task);
            task.carddemoCommarea = task.carddemoCommarea.withPgmReenter();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.initial();
            commonReturn(task);
            return;
        }
        if (task.acupChangeAction.isChangesOkayedAndDone() || task.acupChangeAction.isChangesFailed()) {
            initializeThisProgCommarea(task);
            initializeMiscStorage(task);
            task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L);
            task.carddemoCommarea = task.carddemoCommarea.withPgmEnter();
            sendMap3000(task);
            task.carddemoCommarea = task.carddemoCommarea.withPgmReenter();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.initial();
            commonReturn(task);
            return;
        }
        processInputs1000(task);
        decideAction2000(task);
        sendMap3000(task);
        commonReturn(task);
    }

    void initializeThisProgCommarea(Conversation task) {
        task.acupChangeAction = AccountUpdateRequest.ChangeAction.spacesState();
        task.acupOldAcct.initialize();
        task.acupOldCust.initialize();
        task.acupNewAcct.initialize();
        task.acupNewCust.initialize();
    }

    void initializeMiscStorage(Conversation task) {
        task.wsRespCd = FileStatus.NORMAL;
        task.wsReasCd = FileStatus.NO_REASON_CODE;
        task.wsTranid = spaces(WS_TRANID_LENGTH);
        task.wsUctrans = spaces(WS_TRANID_LENGTH);
        task.wsEditVariableName = spaces(WS_EDIT_VARIABLE_NAME_LENGTH);
        task.wsEditSignedNumber9v2X = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.wsFlgSignedNumberEdit = INITIALIZED_FLAG;
        task.wsEditAlphanumOnly = spaces(WS_EDIT_ALPHANUM_ONLY_LENGTH);
        task.wsEditAlphanumLength = 0;
        task.wsEditAlphaOnlyFlags = INITIALIZED_FLAG;
        task.wsEditAlphanumOnlyFlags = INITIALIZED_FLAG;
        task.wsEditMandatoryFlags = INITIALIZED_FLAG;
        task.wsEditYesNo = INITIALIZED_FLAG;
        task.wsEditUsPhoneNum = spaces(WS_EDIT_US_PHONE_NUM_LENGTH);
        task.wsEditUsPhoneaFlg = INITIALIZED_FLAG;
        task.wsEditEditUsPhoneb = INITIALIZED_FLAG;
        task.wsEditEditPhonec = INITIALIZED_FLAG;
        task.wsEditUsSsnPart1Flgs = INITIALIZED_FLAG;
        task.wsEditUsSsnPart2Flgs = INITIALIZED_FLAG;
        task.wsEditUsSsnPart3Flgs = INITIALIZED_FLAG;
        task.wsCurrDate = spaces(WS_CURR_DATE_LENGTH);
        task.wsDatachangedFlag = INITIALIZED_FLAG;
        task.wsInputFlag = INITIALIZED_FLAG;
        task.wsReturnFlag = INITIALIZED_FLAG;
        task.wsPfkFlag = INITIALIZED_FLAG;
        task.wsEditAcctFlag = INITIALIZED_FLAG;
        task.wsEditCustFlag = INITIALIZED_FLAG;
        for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.FIELDS) {
            task.wsNonKeyFlags.put(field, INITIALIZED_FLAG);
        }
        task.wsCardRidCardnum = spaces(WS_CARD_RID_CARDNUM_LENGTH);
        task.wsCardRidCustId = PIC_X_CODEC.movePic9(0L, WS_CARD_RID_CUST_ID_LENGTH);
        task.wsCardRidAcctId = PIC_X_CODEC.movePic9(0L, WS_CARD_RID_ACCT_ID_LENGTH);
        task.wsAccountMasterReadFlag = INITIALIZED_FLAG;
        task.wsCustMasterReadFlag = INITIALIZED_FLAG;
        task.errorOpname = spaces(ERROR_OPNAME_LENGTH);
        task.errorFile = spaces(ERROR_FILE_LENGTH);
        task.errorResp = spaces(ERROR_RESP_LENGTH);
        task.errorResp2 = spaces(ERROR_RESP_LENGTH);
        task.acupNewCreditLimitX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCashCreditLimitX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCurrBalX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCurrCycCreditX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCurrCycDebitX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.wsLongMsg = spaces(WS_LONG_MSG_LENGTH);
        task.wsInfoMsg = spaces(WS_INFO_MSG_LENGTH);
        task.wsReturnMsg = WS_RETURN_MSG_OFF;
        task.cardXrefRecord = Optional.empty();
        task.abendData = SystemMessages.AbendData.spaces();
    }

    void transferControl0000(Conversation task) {
        task.ccWorkArea.setCcardAidCondition(PfKeyResolver.AidKey.PFK03);

        String fromTranid = PIC_X_CODEC.movePicX(task.carddemoCommarea.fromTranid(),
                NavigationContext.FROM_TRANID_LENGTH);
        boolean fromTranidUnset = lowValues(NavigationContext.FROM_TRANID_LENGTH).equals(fromTranid)
                || spaces(NavigationContext.FROM_TRANID_LENGTH).equals(fromTranid);
        task.carddemoCommarea = task.carddemoCommarea
                .withToTranid(fromTranidUnset ? LIT_MENUTRANID : fromTranid);

        String fromProgram = PIC_X_CODEC.movePicX(task.carddemoCommarea.fromProgram(),
                NavigationContext.FROM_PROGRAM_LENGTH);
        boolean fromProgramUnset = lowValues(NavigationContext.FROM_PROGRAM_LENGTH).equals(fromProgram)
                || spaces(NavigationContext.FROM_PROGRAM_LENGTH).equals(fromProgram);
        task.carddemoCommarea = task.carddemoCommarea
                .withToProgram(fromProgramUnset ? LIT_MENUPGM : fromProgram);

        task.carddemoCommarea = task.carddemoCommarea
                .withFromTranid(LIT_THISTRANID)
                .withFromProgram(LIT_THISPGM);

        task.carddemoCommarea = task.carddemoCommarea.withUserTypeUser();
        task.carddemoCommarea = task.carddemoCommarea.withPgmEnter();
        // The mapset literal is PIC X(8) and CDEMO-LAST-MAPSET is PIC X(7), so the move drops the trailing
        // space - which is the right-hand truncation a PIC X move performs, and is why the carried value is
        // 'COACTUP' rather than 'COACTUP '.
        task.carddemoCommarea = task.carddemoCommarea
                .withLastMapset(PIC_X_CODEC.movePicX(LIT_THISMAPSET,
                        NavigationContext.LAST_MAPSET_LENGTH))
                .withLastMap(PIC_X_CODEC.movePicX(LIT_THISMAP, NavigationContext.LAST_MAP_LENGTH));

        task.cactupao = task.cactupao
                .withNextTarget(task.carddemoCommarea.toProgram(),
                        task.ccWorkArea.getCcardNextMapset(),
                        task.ccWorkArea.getCcardNextMap())
                .withNavigationContext(task.carddemoCommarea)
                .withCardScreenState(task.ccWorkArea)
                .withCommArea(task.toCommArea());
        task.returned = true;
    }

    void commonReturn(Conversation task) {
        if (task.returned) {
            return;
        }
        task.ccWorkArea.setCcardErrorMsg(PIC_X_CODEC.movePicX(task.wsReturnMsg,
                CardScreenState.CCARD_ERROR_MSG_LENGTH));

        // The composed image is built because its total width is what EXEC CICS RETURN LENGTH reports, and
        // a payload that cannot fit it is a payload the next turn could not restore.
        AccountUpdateRequest.CommArea thisProg = task.toCommArea();
        task.wsCommarea = codec.padToDeclaredWidth(
                codec.decodeImage(task.carddemoCommarea.toFixedWidth(codec),
                        "CARDDEMO-COMMAREA")
                        + codec.decodeImage(thisProg.encode(codec),
                                "WS-THIS-PROGCOMMAREA"),
                WS_COMMAREA_LENGTH);

        task.cactupao = task.cactupao
                .withNextTarget(task.ccWorkArea.getCcardNextProg(),
                        task.ccWorkArea.getCcardNextMapset(),
                        task.ccWorkArea.getCcardNextMap())
                .withNavigationContext(task.carddemoCommarea)
                .withCardScreenState(task.ccWorkArea)
                .withCommArea(thisProg);
        task.returned = true;
    }

    void processInputs1000(Conversation task) {
        receiveMap1100(task);
        editMapInputs1200(task);
        task.ccWorkArea.setCcardErrorMsg(PIC_X_CODEC.movePicX(task.wsReturnMsg,
                CardScreenState.CCARD_ERROR_MSG_LENGTH));
        // LIT-THISMAPSET is PIC X(8) and CCARD-NEXT-MAPSET is PIC X(7), so the trailing space is truncated
        // away.
        task.ccWorkArea.setCcardNextProg(PIC_X_CODEC.movePicX(LIT_THISPGM,
                CardScreenState.CCARD_NEXT_PROG_LENGTH));
        task.ccWorkArea.setCcardNextMapset(PIC_X_CODEC.movePicX(LIT_THISMAPSET,
                CardScreenState.CCARD_NEXT_MAPSET_LENGTH));
        task.ccWorkArea.setCcardNextMap(PIC_X_CODEC.movePicX(LIT_THISMAP,
                CardScreenState.CCARD_NEXT_MAP_LENGTH));
    }

    void receiveMap1100(Conversation task) {
        AccountUpdateRequest received = task.cactupai;

        task.acupNewAcct.initialize();
        task.acupNewCust.initialize();

        String acctsid = received.value(AccountUpdateRequest.ScreenField.ACCTSID);
        if (notSupplied(acctsid, AccountUpdateRequest.ACCTSID_LENGTH)) {
            task.ccWorkArea.setCcAcctId(lowValues(CardScreenState.CC_ACCT_ID_LENGTH));
            task.acupNewAcct.acctIdX = lowValues(AccountUpdateRequest.AcctSnapshot.ACCT_ID_LENGTH);
        } else {
            task.ccWorkArea.setCcAcctId(PIC_X_CODEC.movePicX(acctsid,
                    CardScreenState.CC_ACCT_ID_LENGTH));
            task.acupNewAcct.acctIdX = PIC_X_CODEC.movePicX(acctsid,
                    AccountUpdateRequest.AcctSnapshot.ACCT_ID_LENGTH);
        }

        if (task.acupChangeAction.isDetailsNotFetched()) {
            return;
        }

        task.acupNewAcct.activeStatus = receiveField(received,
                AccountUpdateRequest.ScreenField.ACSTTUS,
                AccountUpdateRequest.AcctSnapshot.ACTIVE_STATUS_LENGTH);

        AccountUpdateService.MonetaryEdit creditLimit = AccountUpdateService.computeCreditLimit(
                received.value(AccountUpdateRequest.ScreenField.ACRDLIM), null, PIC_X_CODEC);
        task.acupNewCreditLimitX = creditLimit.stagingImage();
        if (creditLimit.computed()) {
            task.acupNewAcct.setCreditLimitN(creditLimit.value());
        }

        AccountUpdateService.MonetaryEdit cashCreditLimit = AccountUpdateService.computeCashCreditLimit(
                received.value(AccountUpdateRequest.ScreenField.ACSHLIM), null, PIC_X_CODEC);
        task.acupNewCashCreditLimitX = cashCreditLimit.stagingImage();
        if (cashCreditLimit.computed()) {
            task.acupNewAcct.setCashCreditLimitN(cashCreditLimit.value());
        }

        AccountUpdateService.MonetaryEdit currBal = AccountUpdateService.computeCurrBal(
                received.value(AccountUpdateRequest.ScreenField.ACURBAL), null, PIC_X_CODEC);
        task.acupNewCurrBalX = currBal.stagingImage();
        if (currBal.computed()) {
            task.acupNewAcct.setCurrBalN(currBal.value());
        }

        AccountUpdateService.MonetaryEdit currCycCredit = AccountUpdateService.computeCurrCycCredit(
                received.value(AccountUpdateRequest.ScreenField.ACRCYCR), null, PIC_X_CODEC);
        task.acupNewCurrCycCreditX = currCycCredit.stagingImage();
        if (currCycCredit.computed()) {
            task.acupNewAcct.setCurrCycCreditN(currCycCredit.value());
        }

        AccountUpdateService.MonetaryEdit currCycDebit = AccountUpdateService.computeCurrCycDebit(
                received.value(AccountUpdateRequest.ScreenField.ACRCYDB), null, PIC_X_CODEC);
        task.acupNewCurrCycDebitX = currCycDebit.stagingImage();
        if (currCycDebit.computed()) {
            task.acupNewAcct.setCurrCycDebitN(currCycDebit.value());
        }

        task.acupNewAcct.setOpenYear(receiveField(received, AccountUpdateRequest.ScreenField.OPNYEAR,
                AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH));
        task.acupNewAcct.setOpenMon(receiveField(received, AccountUpdateRequest.ScreenField.OPNMON,
                AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH));
        task.acupNewAcct.setOpenDay(receiveField(received, AccountUpdateRequest.ScreenField.OPNDAY,
                AccountUpdateRequest.AcctSnapshot.DATE_DAY_LENGTH));

        task.acupNewAcct.setExpYear(receiveField(received, AccountUpdateRequest.ScreenField.EXPYEAR,
                AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH));
        task.acupNewAcct.setExpMon(receiveField(received, AccountUpdateRequest.ScreenField.EXPMON,
                AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH));
        task.acupNewAcct.setExpDay(receiveField(received, AccountUpdateRequest.ScreenField.EXPDAY,
                AccountUpdateRequest.AcctSnapshot.DATE_DAY_LENGTH));

        task.acupNewAcct.setReissueYear(receiveField(received,
                AccountUpdateRequest.ScreenField.RISYEAR,
                AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH));
        task.acupNewAcct.setReissueMon(receiveField(received, AccountUpdateRequest.ScreenField.RISMON,
                AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH));
        task.acupNewAcct.setReissueDay(receiveField(received, AccountUpdateRequest.ScreenField.RISDAY,
                AccountUpdateRequest.AcctSnapshot.DATE_DAY_LENGTH));

        task.acupNewAcct.groupId = receiveField(received, AccountUpdateRequest.ScreenField.AADDGRP,
                AccountUpdateRequest.AcctSnapshot.GROUP_ID_LENGTH);

        task.acupNewCust.custIdX = receiveField(received, AccountUpdateRequest.ScreenField.ACSTNUM,
                AccountUpdateRequest.CustSnapshot.CUST_ID_LENGTH);

        task.acupNewCust.setSsn1(receiveField(received, AccountUpdateRequest.ScreenField.ACTSSN1,
                AccountUpdateRequest.CustSnapshot.SSN_PART_1_LENGTH));
        task.acupNewCust.setSsn2(receiveField(received, AccountUpdateRequest.ScreenField.ACTSSN2,
                AccountUpdateRequest.CustSnapshot.SSN_PART_2_LENGTH));
        task.acupNewCust.setSsn3(receiveField(received, AccountUpdateRequest.ScreenField.ACTSSN3,
                AccountUpdateRequest.CustSnapshot.SSN_PART_3_LENGTH));

        task.acupNewCust.setDobYear(receiveField(received, AccountUpdateRequest.ScreenField.DOBYEAR,
                AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH));
        task.acupNewCust.setDobMon(receiveField(received, AccountUpdateRequest.ScreenField.DOBMON,
                AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH));
        task.acupNewCust.setDobDay(receiveField(received, AccountUpdateRequest.ScreenField.DOBDAY,
                AccountUpdateRequest.AcctSnapshot.DATE_DAY_LENGTH));

        task.acupNewCust.ficoScoreX = receiveField(received,
                AccountUpdateRequest.ScreenField.ACSTFCO,
                AccountUpdateRequest.CustSnapshot.FICO_SCORE_LENGTH);

        task.acupNewCust.firstName = receiveField(received, AccountUpdateRequest.ScreenField.ACSFNAM,
                AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
        task.acupNewCust.middleName = receiveField(received, AccountUpdateRequest.ScreenField.ACSMNAM,
                AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
        task.acupNewCust.lastName = receiveField(received, AccountUpdateRequest.ScreenField.ACSLNAM,
                AccountUpdateRequest.CustSnapshot.NAME_LENGTH);

        task.acupNewCust.addrLine1 = receiveField(received, AccountUpdateRequest.ScreenField.ACSADL1,
                AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
        task.acupNewCust.addrLine2 = receiveField(received, AccountUpdateRequest.ScreenField.ACSADL2,
                AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
        task.acupNewCust.addrLine3 = receiveField(received, AccountUpdateRequest.ScreenField.ACSCITY,
                AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
        task.acupNewCust.addrStateCd = receiveField(received, AccountUpdateRequest.ScreenField.ACSSTTE,
                AccountUpdateRequest.CustSnapshot.ADDR_STATE_CD_LENGTH);
        task.acupNewCust.addrCountryCd = receiveField(received,
                AccountUpdateRequest.ScreenField.ACSCTRY,
                AccountUpdateRequest.CustSnapshot.ADDR_COUNTRY_CD_LENGTH);
        task.acupNewCust.addrZip = receiveField(received, AccountUpdateRequest.ScreenField.ACSZIPC,
                AccountUpdateRequest.CustSnapshot.ADDR_ZIP_LENGTH);

        task.acupNewCust.setPhoneNum1A(receiveField(received,
                AccountUpdateRequest.ScreenField.ACSPH1A,
                AccountUpdateRequest.CustSnapshot.PHONE_AREA_CODE_LENGTH));
        task.acupNewCust.setPhoneNum1B(receiveField(received,
                AccountUpdateRequest.ScreenField.ACSPH1B,
                AccountUpdateRequest.CustSnapshot.PHONE_PREFIX_LENGTH));
        task.acupNewCust.setPhoneNum1C(receiveField(received,
                AccountUpdateRequest.ScreenField.ACSPH1C,
                AccountUpdateRequest.CustSnapshot.PHONE_LINE_NUMBER_LENGTH));
        task.acupNewCust.setPhoneNum2A(receiveField(received,
                AccountUpdateRequest.ScreenField.ACSPH2A,
                AccountUpdateRequest.CustSnapshot.PHONE_AREA_CODE_LENGTH));
        task.acupNewCust.setPhoneNum2B(receiveField(received,
                AccountUpdateRequest.ScreenField.ACSPH2B,
                AccountUpdateRequest.CustSnapshot.PHONE_PREFIX_LENGTH));
        task.acupNewCust.setPhoneNum2C(receiveField(received,
                AccountUpdateRequest.ScreenField.ACSPH2C,
                AccountUpdateRequest.CustSnapshot.PHONE_LINE_NUMBER_LENGTH));

        task.acupNewCust.govtIssuedId = receiveField(received,
                AccountUpdateRequest.ScreenField.ACSGOVT,
                AccountUpdateRequest.CustSnapshot.GOVT_ISSUED_ID_LENGTH);

        task.acupNewCust.eftAccountId = receiveField(received,
                AccountUpdateRequest.ScreenField.ACSEFTC,
                AccountUpdateRequest.CustSnapshot.EFT_ACCOUNT_ID_LENGTH);

        task.acupNewCust.priHolderInd = receiveField(received,
                AccountUpdateRequest.ScreenField.ACSPFLG,
                AccountUpdateRequest.CustSnapshot.PRI_HOLDER_IND_LENGTH);
    }

    String receiveField(AccountUpdateRequest received, AccountUpdateRequest.ScreenField field,
            int targetLength) {
        String value = received.value(field);
        if (notSupplied(value, field.length())) {
            return lowValues(targetLength);
        }
        return PIC_X_CODEC.movePicX(value, targetLength);
    }

    static boolean notSupplied(String value, int fieldLength) {
        String image = PIC_X_CODEC.movePicX(value == null ? "" : value, fieldLength);
        return image.equals(PIC_X_CODEC.movePicX(AccountUpdateService.NOT_SUPPLIED_MARKER, fieldLength))
                || image.equals(spaces(fieldLength));
    }

    void editMapInputs1200(Conversation task) {
        task.wsInputFlag = INPUT_OK;

        if (task.acupChangeAction.isDetailsNotFetched()) {
            editAccount1210(task);
            task.acupOldAcct.moveLowValues();
            if (task.flgAcctfilterBlank()) {
                task.wsReturnMsg = atReturnWidth(MSG_NO_SEARCH_CRITERIA_RECEIVED);
            }
            return;
        }

        task.wsInfoMsg = atInfoWidth(INFO_FOUND_ACCOUNT_DATA);
        task.wsAccountMasterReadFlag = FOUND_IN_MASTER;
        task.wsEditAcctFlag = FLG_FILTER_ISVALID;
        task.wsCustMasterReadFlag = FOUND_IN_MASTER;
        task.wsEditCustFlag = FLG_FILTER_ISVALID;

        compareOldNew1205(task);

        // NO-CHANGES-FOUND means nothing was typed; CHANGES-OK-NOT-CONFIRMED and CHANGES-OKAYED-AND-DONE
        // mean the edits already ran on a previous pass and their verdicts must not be recomputed.
        if (task.noChangesFound()
                || task.acupChangeAction.isChangesOkNotConfirmed()
                || task.acupChangeAction.isChangesOkayedAndDone()) {
            task.clearNonKeyFlags();
            return;
        }

        runFieldEdits1200(task);
    }

    /**
     * The edit pass of {@code 1200-EDIT-MAP-INPUTS} - {@code app/cbl/COACTUPC.cbl:1471-1668}.
     *
     * <p>The 24 field edits and the one cross-field edit, in source order, bracketed by the pessimistic
     * {@code SET ACUP-CHANGES-NOT-OK} at {@code :1471} and the {@code SET ACUP-CHANGES-OK-NOT-CONFIRMED}
     * at {@code :1668} that only executes when nothing failed. Order is behaviour, not style: the first
     * edit to fail owns {@code WS-RETURN-MSG}, so moving a call changes the message the operator reads.
     *
     * <p>It is a separate method from {@link #editMapInputs1200} for one reason. The source runs this pass
     * on the turn that produces the confirmation prompt and then skips it on the confirming turn, because
     * on a 3270 the confirmed values and the edited values are necessarily the same bytes. Over HTTP they
     * are two independent messages, so {@link #decideAction2000} re-runs this pass through
     * {@link #editsStillPassBeforeWrite} immediately before {@code 9600-WRITE-PROCESSING} to establish
     * that the values about to be written are the values that actually passed. The pass is idempotent and
     * ends in the state the confirming turn is already in, so for values that do edit clean the re-run
     * changes nothing at all.
     *
     * @param task this interaction's storage
     */
    void runFieldEdits1200(Conversation task) {
        // :1471 - SET ACUP-CHANGES-NOT-OK TO TRUE. Pessimistic: the state is "bad" until the last
        // statement of the paragraph proves otherwise.
        task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesNotOk();

        task.wsEditVariableName = editVariableName(NAME_ACCOUNT_STATUS);
        task.wsEditYesNo = PIC_X_CODEC.movePicX(task.acupNewAcct.activeStatus, 1);
        editYesno1220(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSTTUS, task.wsEditYesNo);

        editDate(task, NAME_OPEN_DATE, task.acupNewAcct.openDate,
                AccountUpdateResponse.ScreenField.OPNYEAR, AccountUpdateResponse.ScreenField.OPNMON,
                AccountUpdateResponse.ScreenField.OPNDAY, false);

        task.wsEditVariableName = editVariableName(NAME_CREDIT_LIMIT);
        task.wsEditSignedNumber9v2X = task.acupNewCreditLimitX;
        editSigned9v2At1250(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACRDLIM, task.wsFlgSignedNumberEdit);

        editDate(task, NAME_EXPIRY_DATE, task.acupNewAcct.expiraionDate,
                AccountUpdateResponse.ScreenField.EXPYEAR, AccountUpdateResponse.ScreenField.EXPMON,
                AccountUpdateResponse.ScreenField.EXPDAY, false);

        task.wsEditVariableName = editVariableName(NAME_CASH_CREDIT_LIMIT);
        task.wsEditSignedNumber9v2X = task.acupNewCashCreditLimitX;
        editSigned9v2At1250(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSHLIM, task.wsFlgSignedNumberEdit);

        editDate(task, NAME_REISSUE_DATE, task.acupNewAcct.reissueDate,
                AccountUpdateResponse.ScreenField.RISYEAR, AccountUpdateResponse.ScreenField.RISMON,
                AccountUpdateResponse.ScreenField.RISDAY, false);

        task.wsEditVariableName = editVariableName(NAME_CURRENT_BALANCE);
        task.wsEditSignedNumber9v2X = task.acupNewCurrBalX;
        editSigned9v2At1250(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACURBAL, task.wsFlgSignedNumberEdit);

        // Its name is twenty-six characters and WS-EDIT-VARIABLE-NAME is PIC X(25), so the MOVE truncates
        // the final 't': the operator reads "Current Cycle Credit Limi must be supplied." That is the
        // program's output and it is preserved.
        task.wsEditVariableName = editVariableName(NAME_CURRENT_CYCLE_CREDIT_LIMIT);
        task.wsEditSignedNumber9v2X = task.acupNewCurrCycCreditX;
        editSigned9v2At1250(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACRCYCR, task.wsFlgSignedNumberEdit);

        task.wsEditVariableName = editVariableName(NAME_CURRENT_CYCLE_DEBIT_LIMIT);
        task.wsEditSignedNumber9v2X = task.acupNewCurrCycDebitX;
        editSigned9v2At1250(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACRCYDB, task.wsFlgSignedNumberEdit);

        task.wsEditVariableName = editVariableName(NAME_SSN);
        editUsSsn1265(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACTSSN1, task.wsEditUsSsnPart1Flgs);
        task.setFlag(AccountUpdateResponse.ScreenField.ACTSSN2, task.wsEditUsSsnPart2Flgs);
        task.setFlag(AccountUpdateResponse.ScreenField.ACTSSN3, task.wsEditUsSsnPart3Flgs);

        editDate(task, NAME_DATE_OF_BIRTH, task.acupNewCust.dobYyyyMmDd,
                AccountUpdateResponse.ScreenField.DOBYEAR, AccountUpdateResponse.ScreenField.DOBMON,
                AccountUpdateResponse.ScreenField.DOBDAY, true);

        task.wsEditVariableName = editVariableName(NAME_FICO_SCORE);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.ficoScoreX);
        task.wsEditAlphanumLength = EDIT_LENGTH_3;
        editNumReqd1245(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSTFCO, task.wsEditAlphanumOnlyFlags);
        if (task.flagIsvalid(AccountUpdateResponse.ScreenField.ACSTFCO)) {
            editFicoScore1275(task);
        }

        task.wsEditVariableName = editVariableName(NAME_FIRST_NAME);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.firstName);
        task.wsEditAlphanumLength = EDIT_LENGTH_25;
        editAlphaReqd1225(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSFNAM, task.wsEditAlphaOnlyFlags);

        task.wsEditVariableName = editVariableName(NAME_MIDDLE_NAME);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.middleName);
        task.wsEditAlphanumLength = EDIT_LENGTH_25;
        editAlphaOpt1235(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSMNAM, task.wsEditAlphaOnlyFlags);

        task.wsEditVariableName = editVariableName(NAME_LAST_NAME);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.lastName);
        task.wsEditAlphanumLength = EDIT_LENGTH_25;
        editAlphaReqd1225(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSLNAM, task.wsEditAlphaOnlyFlags);

        task.wsEditVariableName = editVariableName(NAME_ADDRESS_LINE_1);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.addrLine1);
        task.wsEditAlphanumLength = EDIT_LENGTH_50;
        editMandatory1215(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSADL1, task.wsEditMandatoryFlags);

        task.wsEditVariableName = editVariableName(NAME_STATE);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.addrStateCd);
        task.wsEditAlphanumLength = EDIT_LENGTH_2;
        editAlphaReqd1225(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSSTTE, task.wsEditAlphaOnlyFlags);
        if (FLG_ISVALID.equals(task.wsEditAlphaOnlyFlags)) {
            editUsStateCd1270(task);
        }

        task.wsEditVariableName = editVariableName(NAME_ZIP);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.addrZip);
        task.wsEditAlphanumLength = EDIT_LENGTH_5;
        editNumReqd1245(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSZIPC, task.wsEditAlphanumOnlyFlags);

        task.wsEditVariableName = editVariableName(NAME_CITY);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.addrLine3);
        task.wsEditAlphanumLength = EDIT_LENGTH_50;
        editAlphaReqd1225(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSCITY, task.wsEditAlphaOnlyFlags);

        task.wsEditVariableName = editVariableName(NAME_COUNTRY);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.addrCountryCd);
        task.wsEditAlphanumLength = EDIT_LENGTH_3;
        editAlphaReqd1225(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSCTRY, task.wsEditAlphaOnlyFlags);

        task.wsEditVariableName = editVariableName(NAME_PHONE_NUMBER_1);
        task.wsEditUsPhoneNum = PIC_X_CODEC.movePicX(task.acupNewCust.phoneNum1,
                WS_EDIT_US_PHONE_NUM_LENGTH);
        editUsPhoneNum1260(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSPH1A, task.wsEditUsPhoneaFlg);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSPH1B, task.wsEditEditUsPhoneb);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSPH1C, task.wsEditEditPhonec);

        task.wsEditVariableName = editVariableName(NAME_PHONE_NUMBER_2);
        task.wsEditUsPhoneNum = PIC_X_CODEC.movePicX(task.acupNewCust.phoneNum2,
                WS_EDIT_US_PHONE_NUM_LENGTH);
        editUsPhoneNum1260(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSPH2A, task.wsEditUsPhoneaFlg);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSPH2B, task.wsEditEditUsPhoneb);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSPH2C, task.wsEditEditPhonec);

        task.wsEditVariableName = editVariableName(NAME_EFT_ACCOUNT_ID);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.eftAccountId);
        task.wsEditAlphanumLength = EDIT_LENGTH_10;
        editNumReqd1245(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSEFTC, task.wsEditAlphanumOnlyFlags);

        task.wsEditVariableName = editVariableName(NAME_PRIMARY_CARD_HOLDER);
        task.wsEditYesNo = PIC_X_CODEC.movePicX(task.acupNewCust.priHolderInd, 1);
        editYesno1220(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSPFLG, task.wsEditYesNo);

        if (task.flagIsvalid(AccountUpdateResponse.ScreenField.ACSSTTE)
                && task.flagIsvalid(AccountUpdateResponse.ScreenField.ACSZIPC)) {
            editUsStateZipCd1280(task);
        }

        if (!task.inputError()) {
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkNotConfirmed();
        }
    }

    void stringIntoReturnMsgIfOff(Conversation task, String message) {
        if (task.returnMsgOff()) {
            task.wsReturnMsg = atReturnWidth(message);
        }
    }

    static String editWindow(Conversation task) {
        return task.wsEditAlphanumOnly.substring(0, task.wsEditAlphanumLength);
    }

    static boolean windowNotSupplied(String window) {
        return isAll(window, LOW_VALUE) || isAll(window, SPACE) || trimmedLength(window) == 0;
    }

    static boolean isAll(String value, char character) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != character) {
                return false;
            }
        }
        return true;
    }

    static int trimmedLength(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == SPACE) {
            start++;
        }
        while (end > start && value.charAt(end - 1) == SPACE) {
            end--;
        }
        return end - start;
    }

    static String inspectConverting(String window, String from) {
        StringBuilder converted = new StringBuilder(window.length());
        for (int index = 0; index < window.length(); index++) {
            char character = window.charAt(index);
            converted.append(from.indexOf(character) >= 0 ? SPACE : character);
        }
        return converted.toString();
    }

    static boolean isNumericPicX(String value) {
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

    static boolean numvalIsZero(String digits) {
        return new BigDecimal(digits).signum() == 0;
    }

    void compareOldNew1205(Conversation task) {
        task.wsDatachangedFlag = NO_CHANGES_FOUND;

        AcctDataArea newAcct = task.acupNewAcct;
        AcctDataArea oldAcct = task.acupOldAcct;

        boolean acctSame = newAcct.acctIdX.equals(oldAcct.acctIdX)
                && upperCase(newAcct.activeStatus).equals(upperCase(oldAcct.activeStatus))
                && newAcct.currBal.equals(oldAcct.currBal)
                && newAcct.creditLimit.equals(oldAcct.creditLimit)
                && newAcct.cashCreditLimit.equals(oldAcct.cashCreditLimit)
                && newAcct.openDate.equals(oldAcct.openDate)
                && newAcct.expiraionDate.equals(oldAcct.expiraionDate)
                && newAcct.reissueDate.equals(oldAcct.reissueDate)
                && newAcct.currCycCredit.equals(oldAcct.currCycCredit)
                && newAcct.currCycDebit.equals(oldAcct.currCycDebit)
                && upperTrim(newAcct.groupId).equals(upperTrim(oldAcct.groupId));
        if (!acctSame) {
            task.wsDatachangedFlag = CHANGE_HAS_OCCURRED;
            return;
        }

        CustDataArea newCust = task.acupNewCust;
        CustDataArea oldCust = task.acupOldCust;

        boolean custSame = upperTrim(newCust.custIdX).equals(upperTrim(oldCust.custIdX))
                && upperTrim(newCust.firstName).equals(upperTrim(oldCust.firstName))
                && upperTrim(newCust.middleName).equals(upperTrim(oldCust.middleName))
                && upperTrim(newCust.lastName).equals(upperTrim(oldCust.lastName))
                && upperTrim(newCust.addrLine1).equals(upperTrim(oldCust.addrLine1))
                && upperTrim(newCust.addrLine2).equals(upperTrim(oldCust.addrLine2))
                && upperTrim(newCust.addrLine3).equals(upperTrim(oldCust.addrLine3))
                && upperTrim(newCust.addrStateCd).equals(upperTrim(oldCust.addrStateCd))
                && upperTrim(newCust.addrCountryCd).equals(upperTrim(oldCust.addrCountryCd))
                && upperTrim(newCust.addrZip).equals(upperTrim(oldCust.addrZip))
                && newCust.phoneNum1A().equals(oldCust.phoneNum1A())
                && newCust.phoneNum1B().equals(oldCust.phoneNum1B())
                && newCust.phoneNum1C().equals(oldCust.phoneNum1C())
                && newCust.phoneNum2A().equals(oldCust.phoneNum2A())
                && newCust.phoneNum2B().equals(oldCust.phoneNum2B())
                && newCust.phoneNum2C().equals(oldCust.phoneNum2C())
                && newCust.ssnX.equals(oldCust.ssnX)
                && upperTrim(newCust.govtIssuedId).equals(upperTrim(oldCust.govtIssuedId))
                && newCust.dobYyyyMmDd.equals(oldCust.dobYyyyMmDd)
                && newCust.eftAccountId.equals(oldCust.eftAccountId)
                && upperTrim(newCust.priHolderInd).equals(upperTrim(oldCust.priHolderInd))
                && newCust.ficoScoreX.equals(oldCust.ficoScoreX);
        if (custSame) {
            task.wsReturnMsg = atReturnWidth(MSG_NO_CHANGES_DETECTED);
        } else {
            task.wsDatachangedFlag = CHANGE_HAS_OCCURRED;
        }
    }

    static String upperCase(String value) {
        return value.toUpperCase(java.util.Locale.ROOT);
    }

    static String upperTrim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == SPACE) {
            start++;
        }
        while (end > start && value.charAt(end - 1) == SPACE) {
            end--;
        }
        return upperCase(value.substring(start, end));
    }

    void editAccount1210(Conversation task) {
        task.wsEditAcctFlag = FLG_FILTER_NOT_OK;

        if (task.ccWorkArea.isCcAcctIdLowValues() || task.ccWorkArea.isCcAcctIdSpaces()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_BLANK;
            stringIntoReturnMsgIfOff(task, MSG_PROMPT_FOR_ACCT);
            task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L);
            task.acupNewAcct.setAcctIdN(0L);
            return;
        }

        task.acupNewAcct.acctIdX = PIC_X_CODEC.movePicX(task.ccWorkArea.getCcAcctId(),
                AccountUpdateRequest.AcctSnapshot.ACCT_ID_LENGTH);

        if (!isNumericPicX(task.ccWorkArea.getCcAcctId()) || task.ccWorkArea.isCcAcctIdNZeros()) {
            task.wsInputFlag = INPUT_ERROR;
            stringIntoReturnMsgIfOff(task,
                    MSG_ACCT_NUMBER_11_DIGIT_A + MSG_ACCT_NUMBER_11_DIGIT_B);
            // ACUP-NEW-ACCT-ID is deliberately NOT reset.
            task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L);
            return;
        }

        task.carddemoCommarea = task.carddemoCommarea.withAcctId(task.ccWorkArea.getCcAcctIdN());
        task.wsEditAcctFlag = FLG_FILTER_ISVALID;
    }

    void editMandatory1215(Conversation task) {
        task.wsEditMandatoryFlags = FLG_NOT_OK;

        String window = editWindow(task);
        if (windowNotSupplied(window)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditMandatoryFlags = FLG_BLANK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_BE_SUPPLIED);
            return;
        }

        task.wsEditMandatoryFlags = FLG_ISVALID;
    }

    static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == SPACE) {
            start++;
        }
        while (end > start && value.charAt(end - 1) == SPACE) {
            end--;
        }
        return value.substring(start, end);
    }

    void editYesno1220(Conversation task) {
        if (isAll(task.wsEditYesNo, LOW_VALUE)
                || isAll(task.wsEditYesNo, SPACE)
                || isAll(task.wsEditYesNo, ZERO_DIGIT)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditYesNo = FLG_BLANK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_BE_SUPPLIED);
            return;
        }

        if (YES.equals(task.wsEditYesNo) || NO.equals(task.wsEditYesNo)) {
            return;
        }
        task.wsInputFlag = INPUT_ERROR;
        task.wsEditYesNo = FLG_NOT_OK;
        stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_BE_Y_OR_N);
    }

    void editAlphaReqd1225(Conversation task) {
        task.wsEditAlphaOnlyFlags = FLG_NOT_OK;

        String window = editWindow(task);
        if (windowNotSupplied(window)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphaOnlyFlags = FLG_BLANK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_BE_SUPPLIED);
            return;
        }

        String converted = inspectConverting(window, LIT_ALL_ALPHA_FROM_X);
        task.wsEditAlphanumOnly = splice(task.wsEditAlphanumOnly, 0, task.wsEditAlphanumLength,
                converted);

        if (trimmedLength(converted) != 0) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphaOnlyFlags = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_ALPHABETS_ONLY);
            return;
        }

        task.wsEditAlphaOnlyFlags = FLG_ISVALID;
    }

    void editAlphanumReqd1230(Conversation task) {
        task.wsEditAlphanumOnlyFlags = FLG_NOT_OK;

        String window = editWindow(task);
        if (windowNotSupplied(window)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphanumOnlyFlags = FLG_BLANK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_BE_SUPPLIED);
            return;
        }

        String converted = inspectConverting(window, LIT_ALL_ALPHANUM_FROM_X);
        task.wsEditAlphanumOnly = splice(task.wsEditAlphanumOnly, 0, task.wsEditAlphanumLength,
                converted);

        if (trimmedLength(converted) != 0) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphanumOnlyFlags = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task,
                    trim(task.wsEditVariableName) + MSG_NUMBERS_OR_ALPHABETS_ONLY);
            return;
        }

        task.wsEditAlphanumOnlyFlags = FLG_ISVALID;
    }

    void editAlphaOpt1235(Conversation task) {
        task.wsEditAlphaOnlyFlags = FLG_NOT_OK;

        String window = editWindow(task);
        if (windowNotSupplied(window)) {
            task.wsEditAlphaOnlyFlags = FLG_ISVALID;
            return;
        }

        String converted = inspectConverting(window, LIT_ALL_ALPHA_FROM_X);
        task.wsEditAlphanumOnly = splice(task.wsEditAlphanumOnly, 0, task.wsEditAlphanumLength,
                converted);

        if (trimmedLength(converted) != 0) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphaOnlyFlags = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_ALPHABETS_ONLY);
            return;
        }

        task.wsEditAlphaOnlyFlags = FLG_ISVALID;
    }

    void editAlphanumOpt1240(Conversation task) {
        task.wsEditAlphanumOnlyFlags = FLG_NOT_OK;

        String window = editWindow(task);
        if (windowNotSupplied(window)) {
            task.wsEditAlphanumOnlyFlags = FLG_ISVALID;
            return;
        }

        String converted = inspectConverting(window, LIT_ALL_ALPHANUM_FROM_X);
        task.wsEditAlphanumOnly = splice(task.wsEditAlphanumOnly, 0, task.wsEditAlphanumLength,
                converted);

        if (trimmedLength(converted) != 0) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphanumOnlyFlags = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task,
                    trim(task.wsEditVariableName) + MSG_NUMBERS_OR_ALPHABETS_ONLY);
            return;
        }

        task.wsEditAlphanumOnlyFlags = FLG_ISVALID;
    }

    void editNumReqd1245(Conversation task) {
        task.wsEditAlphanumOnlyFlags = FLG_NOT_OK;

        String window = editWindow(task);
        if (windowNotSupplied(window)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphanumOnlyFlags = FLG_BLANK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_BE_SUPPLIED);
            return;
        }

        if (!isNumericPicX(window)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphanumOnlyFlags = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_BE_ALL_NUMERIC);
            return;
        }

        if (numvalIsZero(window)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphanumOnlyFlags = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_NOT_BE_ZERO);
            return;
        }

        task.wsEditAlphanumOnlyFlags = FLG_ISVALID;
    }

    void editSigned9v2At1250(Conversation task) {
        task.wsFlgSignedNumberEdit = FLG_NOT_OK;

        if (isAll(task.wsEditSignedNumber9v2X, LOW_VALUE)
                || isAll(task.wsEditSignedNumber9v2X, SPACE)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsFlgSignedNumberEdit = FLG_BLANK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_BE_SUPPLIED);
            return;
        }

        if (AccountUpdateService.testNumvalC(task.wsEditSignedNumber9v2X)
                != AccountUpdateService.NUMVAL_CONFORMS) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsFlgSignedNumberEdit = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_IS_NOT_VALID);
            return;
        }

        task.wsFlgSignedNumberEdit = FLG_ISVALID;
    }

    void editUsPhoneNum1260(Conversation task) {
        task.wsEditUsPhoneaFlg = FLG_NOT_OK;
        task.wsEditEditUsPhoneb = FLG_NOT_OK;
        task.wsEditEditPhonec = FLG_NOT_OK;

        String numa = phoneArea(task.wsEditUsPhoneNum);
        String numb = phonePrefix(task.wsEditUsPhoneNum);
        String numc = phoneLine(task.wsEditUsPhoneNum);

        boolean firstBlank = isAll(numa, SPACE) || isAll(numa, LOW_VALUE);
        boolean secondBlank = isAll(numb, SPACE) || isAll(numb, LOW_VALUE);
        boolean thirdBlank = isAll(numa, SPACE) || isAll(numc, LOW_VALUE);
        if (firstBlank && secondBlank && thirdBlank) {
            task.wsEditUsPhoneaFlg = FLG_ISVALID;
            task.wsEditEditUsPhoneb = FLG_ISVALID;
            task.wsEditEditPhonec = FLG_ISVALID;
            return;
        }

        editAreaCode(task, numa);
        editUsPhonePrefix(task, numb);
        editUsPhoneLinenum(task, numc);
    }

    void editAreaCode(Conversation task, String numa) {
        if (isAll(numa, SPACE) || isAll(numa, LOW_VALUE)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditUsPhoneaFlg = FLG_BLANK;
            stringIntoReturnMsgIfOff(task,
                    trim(task.wsEditVariableName) + MSG_AREA_CODE_MUST_BE_SUPPLIED);
            return;
        }

        if (!isNumericPicX(numa)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditUsPhoneaFlg = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_AREA_CODE_3_DIGITS);
            return;
        }

        if (numvalIsZero(numa)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditUsPhoneaFlg = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task,
                    trim(task.wsEditVariableName) + MSG_AREA_CODE_CANNOT_BE_ZERO);
            return;
        }

        if (!areaCodeLookup.isValidGeneralPurposeCode(trim(numa))) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditUsPhoneaFlg = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_NOT_VALID_AREA_CODE);
            return;
        }

        task.wsEditUsPhoneaFlg = FLG_ISVALID;
    }

    void editUsPhonePrefix(Conversation task, String numb) {
        if (isAll(numb, SPACE) || isAll(numb, LOW_VALUE)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditEditUsPhoneb = FLG_BLANK;
            stringIntoReturnMsgIfOff(task,
                    trim(task.wsEditVariableName) + MSG_PREFIX_MUST_BE_SUPPLIED);
            return;
        }

        if (!isNumericPicX(numb)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditEditUsPhoneb = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_PREFIX_3_DIGITS);
            return;
        }

        if (numvalIsZero(numb)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditEditUsPhoneb = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_PREFIX_CANNOT_BE_ZERO);
            return;
        }

        task.wsEditEditUsPhoneb = FLG_ISVALID;
    }

    void editUsPhoneLinenum(Conversation task, String numc) {
        if (isAll(numc, SPACE) || isAll(numc, LOW_VALUE)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditEditPhonec = FLG_BLANK;
            stringIntoReturnMsgIfOff(task,
                    trim(task.wsEditVariableName) + MSG_LINENUM_MUST_BE_SUPPLIED);
            return;
        }

        if (!isNumericPicX(numc)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditEditPhonec = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_LINENUM_4_DIGITS);
            return;
        }

        if (numvalIsZero(numc)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditEditPhonec = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task,
                    trim(task.wsEditVariableName) + MSG_LINENUM_CANNOT_BE_ZERO);
            return;
        }

        task.wsEditEditPhonec = FLG_ISVALID;
    }

    void editUsSsn1265(Conversation task) {
        task.wsEditVariableName = editVariableName(NAME_SSN_FIRST_3);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.ssn1());
        task.wsEditAlphanumLength = EDIT_LENGTH_3;
        editNumReqd1245(task);
        task.wsEditUsSsnPart1Flgs = task.wsEditAlphanumOnlyFlags;

        if (FLG_ISVALID.equals(task.wsEditUsSsnPart1Flgs)) {
            int part1 = Integer.parseInt(PIC_X_CODEC.movePic9(task.acupNewCust.ssn1(),
                    AccountUpdateRequest.CustSnapshot.SSN_PART_1_LENGTH));
            if (part1 == SSN_PART1_EXCLUDED_ZERO
                    || part1 == SSN_PART1_EXCLUDED_666
                    || (part1 >= SSN_PART1_EXCLUDED_RANGE_LOW
                        && part1 <= SSN_PART1_EXCLUDED_RANGE_HIGH)) {
                task.wsInputFlag = INPUT_ERROR;
                task.wsEditUsSsnPart1Flgs = FLG_NOT_OK;
                stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_SSN_PART1_RANGE);
            }
        }

        task.wsEditVariableName = editVariableName(NAME_SSN_4TH_5TH);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.ssn2());
        task.wsEditAlphanumLength = EDIT_LENGTH_2;
        editNumReqd1245(task);
        task.wsEditUsSsnPart2Flgs = task.wsEditAlphanumOnlyFlags;

        task.wsEditVariableName = editVariableName(NAME_SSN_LAST_4);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.ssn3());
        task.wsEditAlphanumLength = EDIT_LENGTH_4;
        editNumReqd1245(task);
        task.wsEditUsSsnPart3Flgs = task.wsEditAlphanumOnlyFlags;
    }

    void editUsStateCd1270(Conversation task) {
        if (areaCodeLookup.isValidUsStateCode(task.acupNewCust.addrStateCd)) {
            return;
        }
        task.wsInputFlag = INPUT_ERROR;
        task.setFlag(AccountUpdateResponse.ScreenField.ACSSTTE, FLG_NOT_OK);
        stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_NOT_A_VALID_STATE_CODE);
    }

    void editFicoScore1275(Conversation task) {
        int score = task.acupNewCust.ficoScoreN();
        if (score >= FICO_RANGE_LOW && score <= FICO_RANGE_HIGH) {
            return;
        }
        task.wsInputFlag = INPUT_ERROR;
        task.setFlag(AccountUpdateResponse.ScreenField.ACSTFCO, FLG_NOT_OK);
        stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_FICO_RANGE);
    }

    void editUsStateZipCd1280(Conversation task) {
        String probe = areaCodeLookup.composeStateAndFirstZip2(task.acupNewCust.addrStateCd,
                task.acupNewCust.addrZip);

        if (areaCodeLookup.isValidStateZip2Combo(probe)) {
            return;
        }

        task.wsInputFlag = INPUT_ERROR;
        task.setFlag(AccountUpdateResponse.ScreenField.ACSSTTE, FLG_NOT_OK);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSZIPC, FLG_NOT_OK);
        stringIntoReturnMsgIfOff(task, MSG_INVALID_ZIP_FOR_STATE);
    }

    void editDate(Conversation task, String name, String date,
                  AccountUpdateResponse.ScreenField yearField,
                  AccountUpdateResponse.ScreenField monthField,
                  AccountUpdateResponse.ScreenField dayField,
                  boolean dateOfBirth) {
        task.wsEditVariableName = editVariableName(name);

        AccountDateValidator.EditDateState state = accountDateValidator.newState();
        state.setEditVariableName(task.wsEditVariableName);
        state.setReturnMsgOff();
        if (!task.returnMsgOff()) {
            state.stringIntoReturnMessage(task.wsReturnMsg);
        }
        if (INPUT_ERROR.equals(task.wsInputFlag)) {
            state.setInputError();
        } else if (INPUT_OK.equals(task.wsInputFlag)) {
            state.setInputOk();
        } else {
            state.setInputPending();
        }
        state.setEditDateCcyymmdd(PIC_X_CODEC.movePicX(date,
                AccountUpdateRequest.AcctSnapshot.DATE_LENGTH));

        accountDateValidator.editDateCcyymmddThruExit(state);
        copyDateFlags(task, state, yearField, monthField, dayField);

        if (dateOfBirth && state.wsEditDateIsValid()) {
            accountDateValidator.editDateOfBirth(state, clock);
            copyDateFlags(task, state, yearField, monthField, dayField);
        }

        if (task.returnMsgOff()) {
            task.wsReturnMsg = atReturnWidth(state.returnMessage());
        }
        if (state.inputError()) {
            task.wsInputFlag = INPUT_ERROR;
        }
    }

    void copyDateFlags(Conversation task, AccountDateValidator.EditDateState state,
                       AccountUpdateResponse.ScreenField yearField,
                       AccountUpdateResponse.ScreenField monthField,
                       AccountUpdateResponse.ScreenField dayField) {
        String flags = state.flagsImage();
        task.setFlag(yearField, flags.substring(0, ONE));
        task.setFlag(monthField, flags.substring(ONE, ONE + ONE));
        task.setFlag(dayField, flags.substring(ONE + ONE, ONE + ONE + ONE));
    }

    static String editVariableName(String name) {
        return PIC_X_CODEC.movePicX(name, WS_EDIT_VARIABLE_NAME_LENGTH);
    }

    static String stagingItem(String value) {
        return PIC_X_CODEC.movePicX(value == null ? "" : value, WS_EDIT_ALPHANUM_ONLY_LENGTH);
    }

    void decideAction2000(Conversation task) {
        if (task.acupChangeAction.isDetailsNotFetched() || task.ccWorkArea.isCcardAidPfk12()) {
            if (task.flgAcctfilterIsvalid()) {
                task.wsReturnMsg = WS_RETURN_MSG_OFF;
                readAcct9000(task);
                if (task.foundCustInMaster()) {
                    task.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
                }
            }
            return;
        }

        if (task.acupChangeAction.isShowDetails()) {
            if (task.inputError() || task.noChangesDetected()) {
                return;
            }
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkNotConfirmed();
            return;
        }

        if (task.acupChangeAction.isChangesNotOk()) {
            return;
        }

        if (task.acupChangeAction.isChangesOkNotConfirmed() && task.ccWorkArea.isCcardAidPfk05()) {
            if (!editsStillPassBeforeWrite(task)) {
                // The values on this turn do not edit clean, so there is nothing to confirm. The edits'
                // own flags and message stand and 3000-SEND-MAP repaints them, which is byte for byte the
                // screen the source paints when 1200 finds an error.
                return;
            }
            writeProcessing9600(task);
            return;
        }

        if (task.acupChangeAction.isChangesOkNotConfirmed()) {
            return;
        }

        if (task.acupChangeAction.isChangesOkayedAndDone()) {
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
            String fromTranid = task.carddemoCommarea.fromTranid();
            if (isAll(fromTranid, LOW_VALUE) || isAll(fromTranid, SPACE)) {
                task.carddemoCommarea = task.carddemoCommarea
                        .withAcctId(0L)
                        .withCardNum(0L)
                        .withAcctStatus(lowValues(NavigationContext.ACCT_STATUS_LENGTH));
            }
            return;
        }

        throw abendRoutine(task, ABEND_CODE_UNEXPECTED_DATA, ABEND_MSG_UNEXPECTED_DATA_SCENARIO);
    }

    /**
     * Re-runs the {@code 1200-EDIT-MAP-INPUTS} edit pass immediately before
     * {@code 9600-WRITE-PROCESSING}, so that what is written is what actually passed validation.
     *
     * <p>Why this exists. {@code ACUP-CHANGES-OK-NOT-CONFIRMED} is the source's record that the edits ran
     * and every one of them passed; on the confirming turn {@code :1464-1469} therefore skips them all and
     * {@code :2601} writes. On a 3270 that is sound, because the confirmed field values and the edited
     * field values are the same bytes of the same screen image. Over HTTP the confirmation and the values
     * arrive as two independent messages, so the state saying "these values passed" and the values
     * themselves must be re-associated before anything is persisted. {@link ConversationStateSeal} already
     * establishes that the state is one this route issued; this establishes that it describes the values in
     * hand.
     *
     * <p>Why it changes nothing for a well-behaved caller. {@link #runFieldEdits1200} is a pure function of
     * {@code ACUP-NEW-DETAILS} - it reads the staged fields and writes only {@code WS-INPUT-FLAG},
     * {@code WS-NON-KEY-FLAGS}, {@code WS-RETURN-MSG} and {@code ACUP-CHANGE-ACTION} - and it ends by
     * setting the very state the confirming turn is already in. When the pass is clean the four fields it
     * touched are restored to the image the turn had before the check, so the request proceeds through
     * {@code 9600} byte for byte as it did before. Only a caller presenting values that do not edit clean
     * sees any difference, and what it sees is the screen {@code 1200} paints for exactly that input.
     *
     * @param task this interaction's storage
     * @return whether every edit passed, and so whether the write may proceed
     */
    private boolean editsStillPassBeforeWrite(Conversation task) {
        String inputFlagBefore = task.wsInputFlag;
        Map<AccountUpdateResponse.ScreenField, String> flagsBefore = new EnumMap<>(task.wsNonKeyFlags);
        String returnMsgBefore = task.wsReturnMsg;
        AccountUpdateRequest.ChangeAction actionBefore = task.acupChangeAction;

        // :1431 - SET INPUT-OK TO TRUE, the same clean slate 1200 gives the pass on the turn that runs it.
        task.wsInputFlag = INPUT_OK;
        runFieldEdits1200(task);

        if (task.inputError()) {
            // Leave the pass's own verdicts in place: WS-NON-KEY-FLAGS carries the highlight, WS-RETURN-MSG
            // carries the message, and ACUP-CHANGES-NOT-OK is the state 1200 leaves on a failed pass.
            return false;
        }

        task.wsInputFlag = inputFlagBefore;
        task.wsNonKeyFlags.clear();
        task.wsNonKeyFlags.putAll(flagsBefore);
        task.wsReturnMsg = returnMsgBefore;
        task.acupChangeAction = actionBefore;
        return true;
    }


    /**
     * The {@code PERFORM 9600-WRITE-PROCESSING} arm of {@code 2000-DECIDE-ACTION}, and the inner
     * {@code EVALUATE TRUE} at {@code :2606-2617} that dispatches on its outcome.
     *
     * <p>Four arms in order: could not lock the account, locked but the update failed, the record changed
     * under us, and {@code WHEN OTHER} - success. {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} is
     * <strong>not</strong> one of them: the source tests only the account lock, so a customer-lock
     * failure falls to {@code WHEN OTHER} and is reported as a success. That is a defect in the original
     * and it is preserved (practice B5); the service still distinguishes the two outcomes, so the
     * mapping is visible here rather than hidden.
     *
     * @param task this interaction's storage
     */
    void writeProcessing9600(Conversation task) {
        AccountUpdateService.WriteResult result = accountUpdateService.writeProcessing(
                task.ccWorkArea.getCcAcctId(),
                task.carddemoCommarea,
                new AccountUpdateService.AccountUpdateDetails(
                        AccountUpdateService.DetailGroup.OLD,
                        task.acupOldAcct.toAccountData(),
                        task.acupOldCust.toCustomerData()),
                new AccountUpdateService.AccountUpdateDetails(
                        AccountUpdateService.DetailGroup.NEW,
                        task.acupNewAcct.toAccountData(),
                        task.acupNewCust.toCustomerData()),
                task.wsReturnMsg,
                codec);

        task.wsReturnMsg = atReturnWidth(result.returnMessage());
        if (result.inputError()) {
            task.wsInputFlag = INPUT_ERROR;
        }

        if (result.outcome() == AccountUpdateService.WriteOutcome.COULD_NOT_LOCK_ACCT_FOR_UPDATE) {
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkayedLockError();
        } else if (result.outcome() == AccountUpdateService.WriteOutcome.LOCKED_BUT_UPDATE_FAILED) {
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkayedButFailed();
        } else if (result.outcome()
                == AccountUpdateService.WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE) {
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
        } else {
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkayedAndDone();
        }
    }

    void sendMap3000(Conversation task) {
        screenInit3100(task);
        setupScreenVars3200(task);
        setupInfomsg3250(task);
        setupScreenAttrs3300(task);
        setupInfomsgAttrs3390(task);
        sendScreen3400(task);
    }

    void screenInit3100(Conversation task) {
        task.cactupao = AccountUpdateResponse.initial();
        task.cactupao.resetAttributeQuads();

        DateHeader.from(PIC_X_CODEC, clock);

        task.cactupao = task.cactupao.withScreenTitles().withFunctionKeyLegends();

        task.cactupao = task.cactupao.withDateTimeHeader(DateHeader.from(PIC_X_CODEC, clock));
    }

    void setupScreenVars3200(Conversation task) {
        if (task.carddemoCommarea.isEnter()) {
            return;
        }

        if (task.ccWorkArea.isCcAcctIdNZeros() && task.flgAcctfilterIsvalid()) {
            task.cactupao = task.cactupao.withValue(AccountUpdateResponse.ScreenField.ACCTSID,
                    lowValues(AccountUpdateResponse.declaredLength(
                            AccountUpdateResponse.ScreenField.ACCTSID)));
        } else {
            task.cactupao = task.cactupao.withValue(AccountUpdateResponse.ScreenField.ACCTSID,
                    PIC_X_CODEC.movePicX(task.ccWorkArea.getCcAcctId(),
                            AccountUpdateResponse.declaredLength(
                                    AccountUpdateResponse.ScreenField.ACCTSID)));
        }

        if (task.acupChangeAction.isDetailsNotFetched() || task.ccWorkArea.isCcAcctIdNZeros()) {
            showInitialValues3201(task);
        } else if (task.acupChangeAction.isShowDetails()) {
            showOriginalValues3202(task);
        } else if (task.acupChangeAction.isChangesMade()) {
            showUpdatedValues3203(task);
        } else {
            showOriginalValues3202(task);
        }
    }

    void showInitialValues3201(Conversation task) {
        for (AccountUpdateResponse.ScreenField field : INITIAL_VALUE_FIELDS) {
            task.cactupao = task.cactupao.withValue(field,
                    lowValues(AccountUpdateResponse.declaredLength(field)));
        }
    }

    void showOriginalValues3202(Conversation task) {
        task.clearNonKeyFlags();
        task.wsInfoMsg = atInfoWidth(INFO_PROMPT_FOR_CHANGES);

        AcctDataArea oldAcct = task.acupOldAcct;
        CustDataArea oldCust = task.acupOldCust;
        AccountUpdateRequest.AcctSnapshot acct = oldAcct.toSnapshot();

        if (task.foundAcctInMaster() || task.foundCustInMaster()) {
            paint(task, AccountUpdateResponse.ScreenField.ACSTTUS, oldAcct.activeStatus);
            paint(task, AccountUpdateResponse.ScreenField.ACURBAL,
                    editCurrency92(acct.currBalN()));
            paint(task, AccountUpdateResponse.ScreenField.ACRDLIM,
                    editCurrency92(acct.creditLimitN()));
            paint(task, AccountUpdateResponse.ScreenField.ACSHLIM,
                    editCurrency92(acct.cashCreditLimitN()));
            paint(task, AccountUpdateResponse.ScreenField.ACRCYCR,
                    editCurrency92(acct.currCycCreditN()));
            paint(task, AccountUpdateResponse.ScreenField.ACRCYDB,
                    editCurrency92(acct.currCycDebitN()));
            paint(task, AccountUpdateResponse.ScreenField.OPNYEAR, oldAcct.openYear());
            paint(task, AccountUpdateResponse.ScreenField.OPNMON, oldAcct.openMon());
            paint(task, AccountUpdateResponse.ScreenField.OPNDAY, oldAcct.openDay());
            paint(task, AccountUpdateResponse.ScreenField.EXPYEAR, oldAcct.expYear());
            paint(task, AccountUpdateResponse.ScreenField.EXPMON, oldAcct.expMon());
            paint(task, AccountUpdateResponse.ScreenField.EXPDAY, oldAcct.expDay());
            paint(task, AccountUpdateResponse.ScreenField.RISYEAR, oldAcct.reissueYear());
            paint(task, AccountUpdateResponse.ScreenField.RISMON, oldAcct.reissueMon());
            paint(task, AccountUpdateResponse.ScreenField.RISDAY, oldAcct.reissueDay());
            paint(task, AccountUpdateResponse.ScreenField.AADDGRP, oldAcct.groupId);
        }

        if (task.foundCustInMaster()) {
            paint(task, AccountUpdateResponse.ScreenField.ACSTNUM, oldCust.custIdX);
            paint(task, AccountUpdateResponse.ScreenField.ACTSSN1, oldCust.ssn1());
            paint(task, AccountUpdateResponse.ScreenField.ACTSSN2, oldCust.ssn2());
            paint(task, AccountUpdateResponse.ScreenField.ACTSSN3, oldCust.ssn3());
            paint(task, AccountUpdateResponse.ScreenField.ACSTFCO, oldCust.ficoScoreX);
            paint(task, AccountUpdateResponse.ScreenField.DOBYEAR, oldCust.dobYear());
            paint(task, AccountUpdateResponse.ScreenField.DOBMON, oldCust.dobMon());
            paint(task, AccountUpdateResponse.ScreenField.DOBDAY, oldCust.dobDay());
            paint(task, AccountUpdateResponse.ScreenField.ACSFNAM, oldCust.firstName);
            paint(task, AccountUpdateResponse.ScreenField.ACSMNAM, oldCust.middleName);
            paint(task, AccountUpdateResponse.ScreenField.ACSLNAM, oldCust.lastName);
            paint(task, AccountUpdateResponse.ScreenField.ACSADL1, oldCust.addrLine1);
            paint(task, AccountUpdateResponse.ScreenField.ACSADL2, oldCust.addrLine2);
            paint(task, AccountUpdateResponse.ScreenField.ACSCITY, oldCust.addrLine3);
            paint(task, AccountUpdateResponse.ScreenField.ACSSTTE, oldCust.addrStateCd);
            paint(task, AccountUpdateResponse.ScreenField.ACSZIPC, oldCust.addrZip);
            paint(task, AccountUpdateResponse.ScreenField.ACSCTRY, oldCust.addrCountryCd);
            paint(task, AccountUpdateResponse.ScreenField.ACSPH1A, oldCust.phoneNum1A());
            paint(task, AccountUpdateResponse.ScreenField.ACSPH1B, oldCust.phoneNum1B());
            paint(task, AccountUpdateResponse.ScreenField.ACSPH1C, oldCust.phoneNum1C());
            paint(task, AccountUpdateResponse.ScreenField.ACSPH2A, oldCust.phoneNum2A());
            paint(task, AccountUpdateResponse.ScreenField.ACSPH2B, oldCust.phoneNum2B());
            paint(task, AccountUpdateResponse.ScreenField.ACSPH2C, oldCust.phoneNum2C());
            paint(task, AccountUpdateResponse.ScreenField.ACSGOVT, oldCust.govtIssuedId);
            paint(task, AccountUpdateResponse.ScreenField.ACSEFTC, oldCust.eftAccountId);
            paint(task, AccountUpdateResponse.ScreenField.ACSPFLG, oldCust.priHolderInd);
        }
    }

    void showUpdatedValues3203(Conversation task) {
        AcctDataArea newAcct = task.acupNewAcct;
        CustDataArea newCust = task.acupNewCust;
        AccountUpdateRequest.AcctSnapshot acct = newAcct.toSnapshot();

        paint(task, AccountUpdateResponse.ScreenField.ACSTTUS, newAcct.activeStatus);

        paintMonetary(task, AccountUpdateResponse.ScreenField.ACRDLIM, acct.creditLimitN(),
                task.acupNewCreditLimitX);
        paintMonetary(task, AccountUpdateResponse.ScreenField.ACSHLIM, acct.cashCreditLimitN(),
                task.acupNewCashCreditLimitX);
        paintMonetary(task, AccountUpdateResponse.ScreenField.ACURBAL, acct.currBalN(),
                task.acupNewCurrBalX);
        paintMonetary(task, AccountUpdateResponse.ScreenField.ACRCYCR, acct.currCycCreditN(),
                task.acupNewCurrCycCreditX);
        paintMonetary(task, AccountUpdateResponse.ScreenField.ACRCYDB, acct.currCycDebitN(),
                task.acupNewCurrCycDebitX);

        paint(task, AccountUpdateResponse.ScreenField.OPNYEAR, newAcct.openYear());
        paint(task, AccountUpdateResponse.ScreenField.OPNMON, newAcct.openMon());
        paint(task, AccountUpdateResponse.ScreenField.OPNDAY, newAcct.openDay());
        paint(task, AccountUpdateResponse.ScreenField.EXPYEAR, newAcct.expYear());
        paint(task, AccountUpdateResponse.ScreenField.EXPMON, newAcct.expMon());
        paint(task, AccountUpdateResponse.ScreenField.EXPDAY, newAcct.expDay());
        paint(task, AccountUpdateResponse.ScreenField.RISYEAR, newAcct.reissueYear());
        paint(task, AccountUpdateResponse.ScreenField.RISMON, newAcct.reissueMon());
        paint(task, AccountUpdateResponse.ScreenField.RISDAY, newAcct.reissueDay());
        paint(task, AccountUpdateResponse.ScreenField.AADDGRP, newAcct.groupId);
        paint(task, AccountUpdateResponse.ScreenField.ACSTNUM, newCust.custIdX);
        paint(task, AccountUpdateResponse.ScreenField.ACTSSN1, newCust.ssn1());
        paint(task, AccountUpdateResponse.ScreenField.ACTSSN2, newCust.ssn2());
        paint(task, AccountUpdateResponse.ScreenField.ACTSSN3, newCust.ssn3());
        paint(task, AccountUpdateResponse.ScreenField.ACSTFCO, newCust.ficoScoreX);
        paint(task, AccountUpdateResponse.ScreenField.DOBYEAR, newCust.dobYear());
        paint(task, AccountUpdateResponse.ScreenField.DOBMON, newCust.dobMon());
        paint(task, AccountUpdateResponse.ScreenField.DOBDAY, newCust.dobDay());
        paint(task, AccountUpdateResponse.ScreenField.ACSFNAM, newCust.firstName);
        paint(task, AccountUpdateResponse.ScreenField.ACSMNAM, newCust.middleName);
        paint(task, AccountUpdateResponse.ScreenField.ACSLNAM, newCust.lastName);
        paint(task, AccountUpdateResponse.ScreenField.ACSADL1, newCust.addrLine1);
        paint(task, AccountUpdateResponse.ScreenField.ACSADL2, newCust.addrLine2);
        paint(task, AccountUpdateResponse.ScreenField.ACSCITY, newCust.addrLine3);
        paint(task, AccountUpdateResponse.ScreenField.ACSSTTE, newCust.addrStateCd);
        paint(task, AccountUpdateResponse.ScreenField.ACSZIPC, newCust.addrZip);
        paint(task, AccountUpdateResponse.ScreenField.ACSCTRY, newCust.addrCountryCd);
        paint(task, AccountUpdateResponse.ScreenField.ACSPH1A, newCust.phoneNum1A());
        paint(task, AccountUpdateResponse.ScreenField.ACSPH1B, newCust.phoneNum1B());
        paint(task, AccountUpdateResponse.ScreenField.ACSPH1C, newCust.phoneNum1C());
        paint(task, AccountUpdateResponse.ScreenField.ACSPH2A, newCust.phoneNum2A());
        paint(task, AccountUpdateResponse.ScreenField.ACSPH2B, newCust.phoneNum2B());
        paint(task, AccountUpdateResponse.ScreenField.ACSPH2C, newCust.phoneNum2C());
        paint(task, AccountUpdateResponse.ScreenField.ACSGOVT, newCust.govtIssuedId);
        paint(task, AccountUpdateResponse.ScreenField.ACSEFTC, newCust.eftAccountId);
        paint(task, AccountUpdateResponse.ScreenField.ACSPFLG, newCust.priHolderInd);
    }

    void paint(Conversation task, AccountUpdateResponse.ScreenField field, String value) {
        task.cactupao = task.cactupao.withValue(field,
                PIC_X_CODEC.movePicX(value == null ? "" : value,
                        AccountUpdateResponse.declaredLength(field)));
    }

    void paintMonetary(Conversation task, AccountUpdateResponse.ScreenField field,
                       BigDecimal value, String staging) {
        if (task.flagIsvalid(field)) {
            paint(task, field, editCurrency92(value));
        } else {
            paint(task, field, staging);
        }
    }

    void setupInfomsg3250(Conversation task) {
        if (task.carddemoCommarea.isEnter()) {
            task.wsInfoMsg = atInfoWidth(INFO_PROMPT_FOR_SEARCH_KEYS);
        } else if (task.acupChangeAction.isDetailsNotFetched()) {
            task.wsInfoMsg = atInfoWidth(INFO_PROMPT_FOR_SEARCH_KEYS);
        } else if (task.acupChangeAction.isShowDetails()) {
            task.wsInfoMsg = atInfoWidth(INFO_PROMPT_FOR_CHANGES);
        } else if (task.acupChangeAction.isChangesNotOk()) {
            task.wsInfoMsg = atInfoWidth(INFO_PROMPT_FOR_CHANGES);
        } else if (task.acupChangeAction.isChangesOkNotConfirmed()) {
            task.wsInfoMsg = atInfoWidth(INFO_PROMPT_FOR_CONFIRMATION);
        } else if (task.acupChangeAction.isChangesOkayedAndDone()) {
            task.wsInfoMsg = atInfoWidth(INFO_CONFIRM_UPDATE_SUCCESS);
        } else if (task.acupChangeAction.isChangesOkayedLockError()) {
            task.wsInfoMsg = atInfoWidth(INFO_INFORM_FAILURE);
        } else if (task.acupChangeAction.isChangesOkayedButFailed()) {
            task.wsInfoMsg = atInfoWidth(INFO_INFORM_FAILURE);
        } else if (task.wsNoInfoMessage()) {
            task.wsInfoMsg = atInfoWidth(INFO_PROMPT_FOR_SEARCH_KEYS);
        }

        paint(task, AccountUpdateResponse.ScreenField.INFOMSG, task.wsInfoMsg);
        paint(task, AccountUpdateResponse.ScreenField.ERRMSG, task.wsReturnMsg);
    }

    void setupScreenAttrs3300(Conversation task) {
        protectAllAttrs3310(task);

        unprotectByContext3300(task);

        positionCursor3300(task);

        if (task.carddemoCommarea.lastMapset()
                .equals(PIC_X_CODEC.movePicX(LIT_CCLISTMAPSET, NavigationContext.LAST_MAPSET_LENGTH))) {
            task.cactupao.attributes(AccountUpdateResponse.ScreenField.ACCTSID)
                    .setColour(BmsAttributes.DFHDFCOL);
        }
        if (task.flgAcctfilterNotOk()) {
            task.cactupao.attributes(AccountUpdateResponse.ScreenField.ACCTSID)
                    .setColour(BmsAttributes.DFHRED);
        }
        if (task.flgAcctfilterBlank() && task.carddemoCommarea.isReenter()) {
            task.cactupao = task.cactupao.withValue(AccountUpdateResponse.ScreenField.ACCTSID,
                    PIC_X_CODEC.movePicX(FieldAttributeSetter.ASTERISK,
                            AccountUpdateResponse.declaredLength(
                                    AccountUpdateResponse.ScreenField.ACCTSID)));
            task.cactupao.attributes(AccountUpdateResponse.ScreenField.ACCTSID)
                    .setColour(BmsAttributes.DFHRED);
        }

        if (task.acupChangeAction.isDetailsNotFetched()
                || task.flgAcctfilterBlank()
                || task.flgAcctfilterNotOk()) {
            return;
        }

        for (AccountUpdateResponse.ScreenField field : HIGHLIGHT_ORDER) {
            applyHighlight(task, field);
        }
    }

    void unprotectByContext3300(Conversation task) {
        if (task.acupChangeAction.isDetailsNotFetched()) {
            protect(task, AccountUpdateResponse.ScreenField.ACCTSID, BmsAttributes.DFHBMFSE);
            return;
        }
        if (task.acupChangeAction.isShowDetails() || task.acupChangeAction.isChangesNotOk()) {
            unprotectFewAttrs3320(task);
            return;
        }
        if (task.acupChangeAction.isChangesOkNotConfirmed()
                || task.acupChangeAction.isChangesOkayedAndDone()) {
            return;
        }
        protect(task, AccountUpdateResponse.ScreenField.ACCTSID, BmsAttributes.DFHBMFSE);
    }

    void applyHighlight(Conversation task, AccountUpdateResponse.ScreenField field) {
        FieldAttributeSetter.FieldValidationState state =
                FieldAttributeSetter.FieldValidationState.of(task.flagNotOk(field),
                        task.flagBlank(field));
        task.cactupao = task.cactupao.applyHighlight(field, state,
                task.carddemoCommarea.isReenter());
    }

    void protectAllAttrs3310(Conversation task) {
        for (AccountUpdateResponse.ScreenField field : PROTECTABLE_FIELDS) {
            protect(task, field, BmsAttributes.DFHBMPRF);
        }
    }

    void unprotectFewAttrs3320(Conversation task) {
        for (AccountUpdateResponse.ScreenField field : UNPROTECTED_FIELDS) {
            protect(task, field, BmsAttributes.DFHBMFSE);
        }
        protect(task, AccountUpdateResponse.ScreenField.ACSTNUM, BmsAttributes.DFHBMPRF);
        protect(task, AccountUpdateResponse.ScreenField.ACSCTRY, BmsAttributes.DFHBMPRF);
        protect(task, AccountUpdateResponse.ScreenField.INFOMSG, BmsAttributes.DFHBMPRF);
    }

    void protect(Conversation task, AccountUpdateResponse.ScreenField field, byte attribute) {
        task.cactupai = task.cactupai.withAttribute(
                AccountUpdateRequest.ScreenField.valueOf(field.name()), attribute);
    }

    void positionCursor3300(Conversation task) {
        if (task.foundAccountData() || task.noChangesDetected()) {
            placeCursor(task, AccountUpdateResponse.ScreenField.ACSTTUS);
            return;
        }
        if (task.flgAcctfilterNotOk() || task.flgAcctfilterBlank()) {
            placeCursor(task, AccountUpdateResponse.ScreenField.ACCTSID);
            return;
        }
        for (AccountUpdateResponse.ScreenField field : CURSOR_ORDER) {
            if (cursorArmMatches(task, field)) {
                placeCursor(task, field);
                return;
            }
        }
        placeCursor(task, AccountUpdateResponse.ScreenField.ACCTSID);
    }

    boolean cursorArmMatches(Conversation task, AccountUpdateResponse.ScreenField field) {
        if (field == AccountUpdateResponse.ScreenField.ACSMNAM) {
            return task.flagNotOk(field);
        }
        return task.flagNotOk(field) || task.flagBlank(field);
    }

    void placeCursor(Conversation task, AccountUpdateResponse.ScreenField field) {
        AccountUpdateRequest.ScreenField inputField =
                AccountUpdateRequest.ScreenField.valueOf(field.name());
        task.cactupai = task.cactupai.withMetadata(inputField,
                task.cactupai.metadata(inputField).withLengthItem(CURSOR_HERE));
        task.cursorField = field.label();
    }

    void setupInfomsgAttrs3390(Conversation task) {
        task.cactupao.applyInfoMessageVisibility(!task.wsNoInfoMessage());

        if (task.acupChangeAction.isChangesMade()
                && !task.acupChangeAction.isChangesOkayedAndDone()) {
            task.cactupao.revealCancelLegend();
        }

        if (task.promptForConfirmation()) {
            task.cactupao.revealSaveLegend();
            task.cactupao.revealCancelLegend();
        }
    }

    void sendScreen3400(Conversation task) {
        task.ccWorkArea.setCcardNextMapset(PIC_X_CODEC.movePicX(LIT_THISMAPSET,
                CardScreenState.CCARD_NEXT_MAPSET_LENGTH));
        task.ccWorkArea.setCcardNextMap(PIC_X_CODEC.movePicX(LIT_THISMAP,
                CardScreenState.CCARD_NEXT_MAP_LENGTH));
    }

    void readAcct9000(Conversation task) {
        task.acupOldAcct.initialize();
        task.acupOldCust.initialize();

        task.wsInfoMsg = atInfoWidth("");

        task.acupOldAcct.setAcctIdN(task.ccWorkArea.getCcAcctIdN());
        task.wsCardRidAcctId = PIC_X_CODEC.movePicX(task.ccWorkArea.getCcAcctId(),
                WS_CARD_RID_ACCT_ID_LENGTH);

        getCardXrefByAcct9200(task);
        if (task.flgAcctfilterNotOk()) {
            return;
        }

        getAcctDataByAcct9300(task);
        if (task.didNotFindAcctInAcctdat()) {
            return;
        }

        task.wsCardRidCustId = PIC_X_CODEC.movePic9(Long.toString(task.carddemoCommarea.custId()),
                WS_CARD_RID_CUST_ID_LENGTH);

        getCustDataByCust9400(task);
        if (task.didNotFindCustInCustdat()) {
            return;
        }

        storeFetchedData9500(task);
    }

    void getCardXrefByAcct9200(Conversation task) {
        CardXrefRepository.ReadResult result =
                cardXrefRepository.readByAccountIdViaAltIndex(task.wsCardRidAcctId);
        task.errorResp = respImage(result.cicsResp());
        task.errorResp2 = respImage(result.cicsResp2());

        if (result.isFound()) {
            CardXrefRecord xref = result.record().orElseThrow(() -> new IllegalStateException(
                    "9200-GETCARDXREF-BYACCT reached its DFHRESP(NORMAL) arm without a record; "
                    + "EXEC CICS READ with RESP(NORMAL) always fills INTO(CARD-XREF-RECORD)"));
            task.carddemoCommarea = task.carddemoCommarea
                    .withCustId(xref.xrefCustId())
                    .withCardNum(Long.parseLong(PIC_X_CODEC.movePic9(xref.xrefCardNum(),
                            NavigationContext.CARD_NUM_LENGTH)));
            return;
        }

        if (result.isNotFound()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            stringIntoReturnMsgIfOff(task, STRING_ACCOUNT_PREFIX
                    + task.wsCardRidAcctId
                    + STRING_NOT_FOUND_IN
                    + STRING_CROSS_REF_FILE
                    + task.errorResp
                    + STRING_REAS
                    + task.errorResp2);
            return;
        }

        task.wsInputFlag = INPUT_ERROR;
        task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
        task.errorOpname = PIC_X_CODEC.movePicX(READ_OPERATION_NAME, ERROR_OPNAME_LENGTH);
        task.errorFile = PIC_X_CODEC.movePicX(LIT_CARDXREFNAME_ACCT_PATH, ERROR_FILE_LENGTH);
        task.wsReturnMsg = atReturnWidth(fileErrorMessage(task));
    }

    void getAcctDataByAcct9300(Conversation task) {
        AccountRepository.ReadResult result =
                accountRepository.readByKey(task.wsCardRidAcctIdN());
        task.errorResp = respImage(result.cicsResp());
        task.errorResp2 = respImage(result.cicsResp2());

        if (result.isFound()) {
            task.accountRecord = result.account().orElseThrow(() -> new IllegalStateException(
                    "9300-GETACCTDATA-BYACCT reached its DFHRESP(NORMAL) arm without a record; "
                    + "EXEC CICS READ with RESP(NORMAL) always fills INTO(ACCOUNT-RECORD)"));
            task.wsAccountMasterReadFlag = FOUND_IN_MASTER;
            return;
        }

        if (result.isNotFound()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            stringIntoReturnMsgIfOff(task, STRING_ACCOUNT_PREFIX
                    + task.wsCardRidAcctId
                    + STRING_NOT_FOUND_IN
                    + STRING_ACCT_MASTER_FILE
                    + task.errorResp
                    + STRING_REAS
                    + task.errorResp2);
            return;
        }

        task.wsInputFlag = INPUT_ERROR;
        task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
        task.errorOpname = PIC_X_CODEC.movePicX(READ_OPERATION_NAME, ERROR_OPNAME_LENGTH);
        task.errorFile = PIC_X_CODEC.movePicX(LIT_ACCTFILENAME, ERROR_FILE_LENGTH);
        task.wsReturnMsg = atReturnWidth(fileErrorMessage(task));
    }

    void getCustDataByCust9400(Conversation task) {
        CustomerRepository.ReadResult result = customerRepository.readByKey(task.wsCardRidCustId);
        task.errorResp = respImage(result.cicsResp());
        task.errorResp2 = respImage(result.cicsResp2());

        if (result.isFound()) {
            task.customerRecord = result.customer().orElseThrow(() -> new IllegalStateException(
                    "9400-GETCUSTDATA-BYCUST reached its DFHRESP(NORMAL) arm without a record; "
                    + "EXEC CICS READ with RESP(NORMAL) always fills INTO(CUSTOMER-RECORD)"));
            task.wsCustMasterReadFlag = FOUND_IN_MASTER;
            return;
        }

        if (result.isNotFound()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditCustFlag = FLG_FILTER_NOT_OK;
            stringIntoReturnMsgIfOff(task, STRING_CUSTID_PREFIX
                    + task.wsCardRidCustId
                    + STRING_NOT_FOUND
                    + STRING_IN_CUSTOMER_MASTER
                    + task.errorResp
                    + STRING_REAS_UPPER
                    + task.errorResp2);
            return;
        }

        task.wsInputFlag = INPUT_ERROR;
        task.wsEditCustFlag = FLG_FILTER_NOT_OK;
        task.errorOpname = PIC_X_CODEC.movePicX(READ_OPERATION_NAME, ERROR_OPNAME_LENGTH);
        task.errorFile = PIC_X_CODEC.movePicX(LIT_CUSTFILENAME, ERROR_FILE_LENGTH);
        task.wsReturnMsg = atReturnWidth(fileErrorMessage(task));
    }

    static String respImage(OptionalInt resp) {
        return respImage(resp.orElse(0));
    }

    static String respImage(int resp) {
        return PIC_X_CODEC.movePicX(
                PIC_X_CODEC.movePic9(Integer.toString(resp), RESP_CODE_DIGITS), ERROR_RESP_LENGTH);
    }

    String fileErrorMessage(Conversation task) {
        return PIC_X_CODEC.movePicX(FILE_ERROR_PREFIX
                + task.errorOpname
                + FILE_ERROR_ON
                + task.errorFile
                + FILE_ERROR_RETURNED_RESP
                + task.errorResp
                + FILE_ERROR_RESP2
                + task.errorResp2
                + FILE_ERROR_TRAILER, FILE_ERROR_MESSAGE_LENGTH);
    }

    void storeFetchedData9500(Conversation task) {
        AccountRecord account = task.accountRecord;
        CustomerRecord customer = task.customerRecord;

        task.carddemoCommarea = task.carddemoCommarea
                .withAcctId(account.getAcctId())
                .withCustId(customer.getCustId())
                .withCustFname(PIC_X_CODEC.movePicX(customer.getCustFirstName(),
                        NavigationContext.CUST_FNAME_LENGTH))
                .withCustMname(PIC_X_CODEC.movePicX(customer.getCustMiddleName(),
                        NavigationContext.CUST_MNAME_LENGTH))
                .withCustLname(PIC_X_CODEC.movePicX(customer.getCustLastName(),
                        NavigationContext.CUST_LNAME_LENGTH))
                .withAcctStatus(PIC_X_CODEC.movePicX(account.getAcctActiveStatus(),
                        NavigationContext.ACCT_STATUS_LENGTH))
                .withCardNum(task.carddemoCommarea.cardNum());

        task.acupOldAcct.initialize();
        task.acupOldCust.initialize();

        AcctDataArea oldAcct = task.acupOldAcct;
        oldAcct.setAcctIdN(account.getAcctId());
        oldAcct.activeStatus = PIC_X_CODEC.movePicX(account.getAcctActiveStatus(),
                AccountUpdateRequest.AcctSnapshot.ACTIVE_STATUS_LENGTH);
        oldAcct.setCurrBalN(account.getAcctCurrBal());
        oldAcct.setCreditLimitN(account.getAcctCreditLimit());
        oldAcct.setCashCreditLimitN(account.getAcctCashCreditLimit());
        oldAcct.setCurrCycCreditN(account.getAcctCurrCycCredit());
        oldAcct.setCurrCycDebitN(account.getAcctCurrCycDebit());
        oldAcct.setOpenYear(datePartYear(account.getAcctOpenDate()));
        oldAcct.setOpenMon(datePartMonth(account.getAcctOpenDate()));
        oldAcct.setOpenDay(datePartDay(account.getAcctOpenDate()));
        oldAcct.setExpYear(datePartYear(account.getAcctExpiraionDate()));
        oldAcct.setExpMon(datePartMonth(account.getAcctExpiraionDate()));
        oldAcct.setExpDay(datePartDay(account.getAcctExpiraionDate()));
        oldAcct.setReissueYear(datePartYear(account.getAcctReissueDate()));
        oldAcct.setReissueMon(datePartMonth(account.getAcctReissueDate()));
        oldAcct.setReissueDay(datePartDay(account.getAcctReissueDate()));
        oldAcct.groupId = PIC_X_CODEC.movePicX(account.getAcctGroupId(),
                AccountUpdateRequest.AcctSnapshot.GROUP_ID_LENGTH);

        CustDataArea oldCust = task.acupOldCust;
        oldCust.custIdX = PIC_X_CODEC.movePic9(Integer.toString(customer.getCustId()),
                AccountUpdateRequest.CustSnapshot.CUST_ID_LENGTH);
        oldCust.ssnX = PIC_X_CODEC.movePic9(Integer.toString(customer.getCustSsn()),
                AccountUpdateRequest.CustSnapshot.SSN_LENGTH);
        oldCust.setDobYear(datePartYear(customer.getCustDobYyyyMmDd()));
        oldCust.setDobMon(datePartMonth(customer.getCustDobYyyyMmDd()));
        oldCust.setDobDay(datePartDay(customer.getCustDobYyyyMmDd()));
        oldCust.ficoScoreX = PIC_X_CODEC.movePic9(
                Integer.toString(customer.getCustFicoCreditScore()),
                AccountUpdateRequest.CustSnapshot.FICO_SCORE_LENGTH);
        oldCust.firstName = PIC_X_CODEC.movePicX(customer.getCustFirstName(),
                AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
        oldCust.middleName = PIC_X_CODEC.movePicX(customer.getCustMiddleName(),
                AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
        oldCust.lastName = PIC_X_CODEC.movePicX(customer.getCustLastName(),
                AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
        oldCust.addrLine1 = PIC_X_CODEC.movePicX(customer.getCustAddrLine1(),
                AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
        oldCust.addrLine2 = PIC_X_CODEC.movePicX(customer.getCustAddrLine2(),
                AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
        oldCust.addrLine3 = PIC_X_CODEC.movePicX(customer.getCustAddrLine3(),
                AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
        oldCust.addrStateCd = PIC_X_CODEC.movePicX(customer.getCustAddrStateCd(),
                AccountUpdateRequest.CustSnapshot.ADDR_STATE_CD_LENGTH);
        oldCust.addrCountryCd = PIC_X_CODEC.movePicX(customer.getCustAddrCountryCd(),
                AccountUpdateRequest.CustSnapshot.ADDR_COUNTRY_CD_LENGTH);
        oldCust.addrZip = PIC_X_CODEC.movePicX(customer.getCustAddrZip(),
                AccountUpdateRequest.CustSnapshot.ADDR_ZIP_LENGTH);
        oldCust.phoneNum1 = PIC_X_CODEC.movePicX(customer.getCustPhoneNum1(),
                AccountUpdateRequest.CustSnapshot.PHONE_NUM_LENGTH);
        oldCust.phoneNum2 = PIC_X_CODEC.movePicX(customer.getCustPhoneNum2(),
                AccountUpdateRequest.CustSnapshot.PHONE_NUM_LENGTH);
        oldCust.govtIssuedId = PIC_X_CODEC.movePicX(customer.getCustGovtIssuedId(),
                AccountUpdateRequest.CustSnapshot.GOVT_ISSUED_ID_LENGTH);
        oldCust.eftAccountId = PIC_X_CODEC.movePicX(customer.getCustEftAccountId(),
                AccountUpdateRequest.CustSnapshot.EFT_ACCOUNT_ID_LENGTH);
        oldCust.priHolderInd = PIC_X_CODEC.movePicX(customer.getCustPriCardHolderInd(),
                AccountUpdateRequest.CustSnapshot.PRI_HOLDER_IND_LENGTH);
    }

    static String datePartYear(String recordDate) {
        return slice(PIC_X_CODEC.movePicX(recordDate, RECORD_DATE_LENGTH),
                RECORD_DATE_YEAR_OFFSET, AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH);
    }

    static String datePartMonth(String recordDate) {
        return slice(PIC_X_CODEC.movePicX(recordDate, RECORD_DATE_LENGTH),
                RECORD_DATE_MONTH_OFFSET, AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH);
    }

    static String datePartDay(String recordDate) {
        return slice(PIC_X_CODEC.movePicX(recordDate, RECORD_DATE_LENGTH),
                RECORD_DATE_DAY_OFFSET, AccountUpdateRequest.AcctSnapshot.DATE_DAY_LENGTH);
    }

    AbendException abendRoutine(Conversation task, String abendCode, String abendMsg) {
        task.abendData = task.abendData
                .withAbendCode(abendCode)
                .withAbendReason(spaces(SystemMessages.ABEND_REASON_LENGTH))
                .withAbendMsg(abendMsg);
        return abendRoutine(task, (RuntimeException) null);
    }

    AbendException abendRoutine(Conversation task, RuntimeException cause) {
        SystemMessages.AbendData abendData = task.abendData;

        if (lowValues(SystemMessages.ABEND_MSG_LENGTH).equals(abendData.abendMsg())) {
            abendData = abendData.withAbendMsg(UNEXPECTED_ABEND_OCCURRED);
        }
        abendData = abendData
                .withAbendCulprit(PIC_X_CODEC.movePicX(LIT_THISPGM,
                        SystemMessages.ABEND_CULPRIT_LENGTH))
                .toDeclaredWidths();
        task.abendData = abendData;

        LOG.error("app/cbl/COACTUPC.cbl:4203 ABEND-ROUTINE: " + abendDataImage(abendData)
                + " ABCODE " + ABEND_ROUTINE_ABCODE + " ["
                + (cause == null ? NO_TRIGGERING_FAILURE : BackendDiagnostic.of(cause).describe())
                + ']');

        task.returned = true;

        return AbendException.withoutAbendParameters(LIT_THISPGM,
                        AbendException.RETURN_CODE_IO_ERROR,
                        "ABCODE " + ABEND_ROUTINE_ABCODE + ": " + trim(abendData.abendMsg()), cause)
                .withSourceDiagnostic(abendDataImage(abendData));
    }

    String abendDataImage(SystemMessages.AbendData abendData) {
        SystemMessages.AbendData atWidth = abendData.toDeclaredWidths();
        return atWidth.abendCode() + atWidth.abendCulprit() + atWidth.abendReason()
                + atWidth.abendMsg();
    }

    ScreenMetadata screenMetadataOf(AccountUpdateResponse response, AccountUpdateRequest request,
                                    String cursorField) {
        Map<String, ScreenMetadata.FieldMetadata> quads = new LinkedHashMap<>();
        for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.ScreenField.values()) {
            AccountUpdateResponse.FieldAttributes attributes = response.attributes(field);
            byte protection = request.metadata(AccountUpdateRequest.ScreenField.valueOf(field.name()))
                    .attribute();
            quads.put(field.label(), ScreenMetadata.FieldMetadata.of(attributes.getColour(),
                    protection, attributes.getHilight(), attributes.getValidn()));
        }
        return ScreenMetadata.of(cursorField,
                response.attributes(AccountUpdateResponse.ScreenField.ERRMSG).getColour(),
                true,
                quads);
    }
}
