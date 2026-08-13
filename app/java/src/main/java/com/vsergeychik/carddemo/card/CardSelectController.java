package com.vsergeychik.carddemo.card;

import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest.ThisProgCommarea;
import com.vsergeychik.carddemo.card.dto.CardSelectResponse;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.AidRequestParameter;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import jakarta.validation.Valid;

import java.nio.charset.Charset;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
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
 * {@code GET /api/cards/&#123;cardNum&#125;} - the credit-card detail screen, CSD transaction {@code CCDL},
 * migrated from {@code app/cbl/COCRDSLC.cbl} (887 lines) mapset {@code COCRDSL} / map {@code CCRDSLA}.
 *
 * <p>{@code app/cpy/CVCRD01Y.cpy:29-30} declares {@code 10 CCARD-RETURN-MSG PIC X(75).} with
 * {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES.} - a different field, whose off state is 75 bytes of
 * binary zero, and which {@code COCRDSLC} never references.
 *
 * <p>{@code 05 LIT-CCLISTMAP PIC X(7) VALUE 'CCRDSLA'} at {@code app/cbl/COCRDSLC.cbl:177} names this
 * screen's own map where it means the card list's {@code 'CCRDLIA'}, and {@link #LIT_CCLISTMAP} reproduces
 * it unchanged because the value is observable. Nothing here depends on it: the {@code PROCEDURE DIVISION}
 * references only {@code LIT-CCLISTMAPSET}, at {@code :505} and {@code :527}.
 */
@RestController
public class CardSelectController {
    private static final Log LOG = LogFactory.getLog(CardSelectController.class);

    static final String LIT_THISPGM = "COCRDSLC";

    static final String LIT_THISTRANID = "CCDL";

    static final String LIT_THISMAPSET = "COCRDSL ";

    static final String LIT_THISMAP = "CCRDSLA";

    static final String LIT_CCLISTPGM = "COCRDLIC";

    static final String LIT_CCLISTTRANID = "CCLI";

    static final String LIT_CCLISTMAPSET = "COCRDLI";

    static final String LIT_CCLISTMAP = "CCRDSLA";

    static final String LIT_MENUPGM = "COMEN01C";

    static final String LIT_MENUTRANID = "CM00";

    static final String LIT_MENUMAPSET = "COMEN01";

    static final String LIT_MENUMAP = "COMEN1A";

    static final String LIT_CARDFILENAME = CardRepository.BASE_CICS_FILE_NAME;

    static final String LIT_CARDFILENAME_ACCT_PATH = CardRepository.ALTERNATE_INDEX_CICS_FILE_NAME;

    static final int WS_TRANID_LENGTH = 4;

    static final int WS_LONG_MSG_LENGTH = 500;

    static final int WS_INFO_MSG_LENGTH = 40;

    static final int WS_RETURN_MSG_LENGTH = CardScreenState.CCARD_RETURN_MSG_LENGTH;

    static final int WS_CARD_RID_CARDNUM_LENGTH = CardScreenState.CC_CARD_NUM_LENGTH;

    static final int WS_CARD_RID_ACCT_ID_LENGTH = CardScreenState.CC_ACCT_ID_LENGTH;

    static final int WS_COMMAREA_LENGTH = 2000;

    static final int THIS_PROGCOMMAREA_LENGTH =
            ThisProgCommarea.CA_FROM_PROGRAM_LENGTH + ThisProgCommarea.CA_FROM_TRANID_LENGTH;

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

    private static final FixedWidthCodec PIC_X_CODEC =
            new FixedWidthCodec(java.nio.charset.StandardCharsets.US_ASCII);

    static final String WS_INFO_MSG_SPACES = CardScreenState.spaces(WS_INFO_MSG_LENGTH);

    static final String WS_INFO_MSG_LOW_VALUES = CardScreenState.lowValues(WS_INFO_MSG_LENGTH);

    static final String FOUND_CARDS_FOR_ACCOUNT =
            PIC_X_CODEC.movePicX("   Displaying requested details", WS_INFO_MSG_LENGTH);

    static final String WS_PROMPT_FOR_INPUT =
            PIC_X_CODEC.movePicX("Please enter Account and Card Number", WS_INFO_MSG_LENGTH);

    static final String WS_RETURN_MSG_OFF = CardScreenState.spaces(WS_RETURN_MSG_LENGTH);

    static final String WS_EXIT_MESSAGE =
            PIC_X_CODEC.movePicX("PF03 pressed.Exiting              ", WS_RETURN_MSG_LENGTH);

    static final String WS_PROMPT_FOR_ACCT =
            PIC_X_CODEC.movePicX("Account number not provided", WS_RETURN_MSG_LENGTH);

    static final String WS_PROMPT_FOR_CARD =
            PIC_X_CODEC.movePicX("Card number not provided", WS_RETURN_MSG_LENGTH);

    static final String NO_SEARCH_CRITERIA_RECEIVED =
            PIC_X_CODEC.movePicX("No input received", WS_RETURN_MSG_LENGTH);

    static final String SEARCHED_ACCT_MESSAGE = PIC_X_CODEC.movePicX(
            "Account number must be a non zero 11 digit number", WS_RETURN_MSG_LENGTH);

    static final String SEARCHED_CARD_NOT_NUMERIC = PIC_X_CODEC.movePicX(
            "Card number if supplied must be a 16 digit number", WS_RETURN_MSG_LENGTH);

    static final String DID_NOT_FIND_ACCT_IN_CARDXREF = PIC_X_CODEC.movePicX(
            "Did not find this account in cards database", WS_RETURN_MSG_LENGTH);

    static final String DID_NOT_FIND_ACCTCARD_COMBO = PIC_X_CODEC.movePicX(
            "Did not find cards for this search condition", WS_RETURN_MSG_LENGTH);

    static final String XREF_READ_ERROR =
            PIC_X_CODEC.movePicX("Error reading Card Data File", WS_RETURN_MSG_LENGTH);

    static final String CODING_TO_BE_DONE =
            PIC_X_CODEC.movePicX("Looks Good.... so far", WS_RETURN_MSG_LENGTH);

    static final String UNEXPECTED_DATA_SCENARIO =
            PIC_X_CODEC.movePicX("UNEXPECTED DATA SCENARIO", WS_RETURN_MSG_LENGTH);

    static final String UNEXPECTED_DATA_ABEND_CODE = "0001";

    static final String UNEXPECTED_ABEND_OCCURRED = PIC_X_CODEC.movePicX(
            "UNEXPECTED ABEND OCCURRED.", SystemMessages.ABEND_MSG_LENGTH);

    static final String ABEND_ROUTINE_ABCODE = "9999";

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
                    + "app/cbl/COCRDSLC.cbl:102-121 declares it, but the transcribed parts sum to "
                    + FILE_ERROR_MESSAGE_LENGTH + "; a filler has been mistranscribed");
        }
        if (PASSED_COMMAREA_LENGTH != 172) {
            throw new AssertionError("The passed commarea must be exactly 172 characters - "
                    + "CARDDEMO-COMMAREA (160) followed by WS-THIS-PROGCOMMAREA (12) - but the parts "
                    + "sum to " + PASSED_COMMAREA_LENGTH);
        }
        requireLiteral(LIT_CARDFILENAME, "CARDDAT ", "LIT-CARDFILENAME", 187);
        requireLiteral(LIT_CARDFILENAME_ACCT_PATH, "CARDAIX ", "LIT-CARDFILENAME-ACCT-PATH", 189);
        requireLiteral(READ_OPERATION_NAME, "READ", "ERROR-OPNAME's only value", 767);
        requireLiteral(ScreenTitles.CCDA_TITLE01, "      AWS Mainframe Modernization       ",
                "CCDA-TITLE01", "app/cpy/COTTL01Y.cpy:18-19");
        requireLiteral(ScreenTitles.CCDA_TITLE02, "              CardDemo                  ",
                "CCDA-TITLE02", "app/cpy/COTTL01Y.cpy:20+22");
    }

    /**
     * Verifies a literal this class borrows from another type against the value
     * {@code app/cbl/COCRDSLC.cbl} declares, so a change on either side cannot pass unnoticed.
     *
     * @param actual the borrowed value
     * @param expected the value the COBOL declares
     * @param cobolName the COBOL item's name, for the failure message
     * @param cobolLine the line of {@code app/cbl/COCRDSLC.cbl} that declares it
     * @throws AssertionError if the two disagree
     */
    private static void requireLiteral(String actual, String expected, String cobolName,
            int cobolLine) {
        requireLiteral(actual, expected, cobolName, "app/cbl/COCRDSLC.cbl:" + cobolLine);
    }

    private static void requireLiteral(String actual, String expected, String cobolName,
            String where) {
        if (!expected.equals(actual)) {
            throw new AssertionError(cobolName + " is declared as '" + expected + "' at " + where
                    + ", but this class resolved it to '" + actual + "'");
        }
    }

    private final CardRepository cardRepository;

    private final Clock clock;

    private final FixedWidthCodec codec;

    /**
     * Spring's constructor, wiring the repository, the clock and the active dataset code page.
     *
     * @param cardRepository the card file's repository; must not be {@code null}
     * @param clock the clock {@code FUNCTION CURRENT-DATE} reads; must not be {@code null}
     * @param datasetCharset the active dataset code page, from
     *     {@code @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)}; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CardSelectController(
            CardRepository cardRepository,
            Clock clock,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset) {
        this.cardRepository = Objects.requireNonNull(cardRepository,
                "A CardRepository is required: COCRDSLC reads CARDDAT and its CARDAIX path, and this "
                        + "controller reaches neither any other way");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: 1100-SCREEN-INIT reads FUNCTION CURRENT-DATE twice, and reading a "
                        + "clock inline would make every parity case non-deterministic");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset,
                "A dataset charset is required: a fixed-width mainframe field is bytes in a specific "
                        + "code page, so the code page is always stated and never taken from the "
                        + "platform"));
    }

    FixedWidthCodec codec() {
        return codec;
    }

    // The paired request owns it and the paired response imports it, which is exactly how the CT01 screen
    // carries CDEMO-CT01-INFO (TransactionAddRequest.Ct01Info).

    /**
     * One task's worth of {@code WORKING-STORAGE}: {@code WS-MISC-STORAGE}, {@code CC-WORK-AREA},
     * {@code CARDDEMO-COMMAREA}, {@code WS-THIS-PROGCOMMAREA} and {@code WS-COMMAREA}.
     *
     * <p>Deliberately mutable, because {@code WORKING-STORAGE} is: the program assigns into it throughout
     * processing.
     */
    static final class Conversation {
        int wsRespCd;

        int wsReasCd;

        String wsTranid;

        String wsInputFlag;

        String wsEditAcctFlag;

        String wsEditCardFlag;

        String wsReturnFlag;

        String wsPfkFlag;

        // ---- CICS-OUTPUT-EDIT-VARS, app/cbl/COCRDSLC.cbl:72-92 Of these ten items the program uses
        // exactly one: CARD-EXPIRAION-DATE-X, which receives CARD-EXPIRAION-DATE at :477-478 so that the
        // FILLER REDEFINES at :85-90 can split it into year, month and day.

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

        ThisProgCommarea thisProgCommarea;

        String wsCommarea;

        Optional<CardRecord> cardRecord = Optional.empty();

        SystemMessages.AbendData abendData;

        SecUserRecord secUserData;

        CustomerRecord customerRecord;

        DateHeader dateHeader;

        boolean returned;

        // Each is a comparison against the stored character, never a null check: after INITIALIZE the flags
        // hold a SPACE, which satisfies none of WS-INPUT-FLAG's three names and satisfies FLG-*-BLANK on
        // both edit flags.

        boolean inputOk() {
            return INPUT_OK.equals(wsInputFlag);
        }

        boolean inputError() {
            return INPUT_ERROR.equals(wsInputFlag);
        }

        /**
         * {@code 88 INPUT-PENDING VALUE LOW-VALUES}, {@code :54} - declared, never tested (B5).
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

        /**
         * {@code 88 WS-RETURN-FLAG-OFF VALUE LOW-VALUES}, {@code :64} - declared, never tested (B5).
         */
        boolean wsReturnFlagOff() {
            return WS_RETURN_FLAG_OFF.equals(wsReturnFlag);
        }

        /**
         * {@code 88 WS-RETURN-FLAG-ON VALUE '1'}, {@code :65} - declared, never tested (B5).
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

        boolean noInfoMessage() {
            return WS_INFO_MSG_SPACES.equals(wsInfoMsg) || WS_INFO_MSG_LOW_VALUES.equals(wsInfoMsg);
        }

        boolean foundCardsForAccount() {
            return FOUND_CARDS_FOR_ACCOUNT.equals(wsInfoMsg);
        }

        boolean promptForInput() {
            return WS_PROMPT_FOR_INPUT.equals(wsInfoMsg);
        }

        boolean returnMessageOff() {
            return WS_RETURN_MSG_OFF.equals(wsReturnMsg);
        }
    }

    // An over-long card number was silently truncated to PIC X(16) - which is one URI addressing a
    // DIFFERENT card - an EIBAID was narrowed straight to byte, and EIBCALEN was whatever the caller said
    // it was, including negative.

    private static final int AID_MIN = 0;

    private static final int AID_MAX = 255;

    static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    static final String EIBAID_PARAM_ALIAS = AidRequestParameter.ALTERNATE_NAME;

    static final String EIBCALEN_PARAM = "eibcalen";

    static final String CARDSID_MEMBER = "cardsid";
    static final String NO_CRITERION_IMAGE = "*";

    /**
     * Displays one credit card's detail screen: {@code GET /api/cards/&#123;cardNum&#125;}, transaction
     * {@code CCDL}, mapset {@code COCRDSL}, map {@code CCRDSLA}, fifteen fields.
     *
     * <p>Always answers {@code 200 OK}, because {@code COCRDSLC} always ends in {@code EXEC CICS RETURN}:
     * every path either paints the screen ({@code COMMON-RETURN}, {@code app/cbl/COCRDSLC.cbl:402}) or
     * sends plain text and returns ({@code SEND-PLAIN-TEXT}, {@code :846}).
     *
     * @param cardNum {@code CARDSIDI PIC X(16)} - the card number this URI addresses, and the
     *     {@code CARDDAT} key {@code 9100-GETCARD-BYACCTCARD} reads with
     * @param request the whole {@code 01 CCRDSLAI} symbolic-map request, validated against the declared
     *     widths
     * @param eibAid {@code EIBAID} - the raw attention identifier byte, {@code 0}-{@code 255}, under the
     *     alternate spelling this route originally declared
     * @param eibcalen {@code EIBCALEN} - the length of the area that arrived, which is
     *     {@value NavigationContext#COMMAREA_LENGTH} from the card list and {@value #WS_COMMAREA_LENGTH} from
     *     this program's own
     * @param eibaid the same value under {@link AidRequestParameter#CANONICAL_NAME}, the spelling every
     *     online route shares
     * @return the painted screen and its metadata: the fifteen fields, the next-screen triple, the
     *     commarea, the 12-byte trailer and the attribute quads, all in the body so nothing is retained
     *     server-side
     * @throws IllegalArgumentException if {@code cardNum} or a bound field is wider than its
     *     {@code PICTURE}, if the AID is outside {@code 0}-{@code 255}, if the two AID spellings disagree
     */
    @GetMapping(path = "/api/cards/{cardNum}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScreenResponse<CardSelectResponse>> viewCardDetail(
            @PathVariable("cardNum") String cardNum,
            @Valid @RequestBody(required = false) CardSelectRequest request,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid,
            @RequestParam(name = EIBCALEN_PARAM, required = false) Integer eibcalen,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid) {
        Objects.requireNonNull(cardNum, "A card number is required in the path: it is the RIDFLD of the "
                + "READ at app/cbl/COCRDSLC.cbl:806-813");

        CardSelectRequest received = bind(cardNum, request);
        int commareaLength = resolveEibcalen(eibcalen, received);
        byte attentionIdentifier =
                resolveAttentionIdentifier(AidRequestParameter.resolve(eibaid, eibAid));

        CardSelectResponse painted = handle(received, commareaLength, attentionIdentifier);
        return ResponseEntity.ok(ScreenResponse.of(painted, painted.screenMetadata(received)));
    }

    CardSelectRequest bind(String cardNum, CardSelectRequest request) {
        if (cardNum.length() > CardSelectRequest.CARDSID_LENGTH) {
            throw ScreenInputRejectedException.tooWide(CARDSID_MEMBER,
                    "CARDSIDI PIC X(" + CardSelectRequest.CARDSID_LENGTH + ")",
                    CardSelectRequest.CARDSID_LENGTH, cardNum.length());
        }

        CardSelectRequest received = request == null ? coldStartRequest() : new CardSelectRequest(request);
        ScreenInputRejectedException.requireKeyAgreement(CARDSID_MEMBER, cardNum,
                received.getCardsid(), CardSelectRequest.CARDSID_LENGTH, codec, NO_CRITERION_IMAGE);
        received.setCardsid(codec.movePicX(cardNum, CardSelectRequest.CARDSID_LENGTH));

        if (received.hasNavigationContext()) {
            received.setNavigationContext(
                    received.getNavigationContext().withCardNum(carriedCardNumber(cardNum)));
        }

        // A COBOL alphanumeric item has no absent state, and this one has none either, so no null guard is
        // written for a state that cannot occur.
        String acctsid = received.getAcctsid();
        if (acctsid.length() > CardSelectRequest.ACCTSID_LENGTH) {
            throw new IllegalArgumentException("ACCTSID is ACCTSIDI PIC X("
                    + CardSelectRequest.ACCTSID_LENGTH + ") and was given " + acctsid.length()
                    + " characters. Padding it would keep the leading "
                    + CardSelectRequest.ACCTSID_LENGTH + " and filter on a different account.");
        }
        received.setAcctsid(codec.movePicX(acctsid, CardSelectRequest.ACCTSID_LENGTH));
        return received;
    }


    /**
     * The URI's card number as {@code CDEMO-CARD-NUM PIC 9(16)} holds it.
     *
     * <p>{@code CARDSID} is {@code PIC X(16)} and the communication area's carried card number is
     * {@code PIC 9(16)} - alphanumeric on the screen, numeric in the commarea - so the projection has to
     * cross that boundary. A path value of sixteen digits or fewer is the number it spells, leading zeros
     * and all, since {@code :343} writes through the numeric {@code REDEFINES} view and zero-fills on the
     * left. Anything a {@code PIC 9(16)} item cannot hold - a blank segment, {@code '*'}, or any value
     * with a non-digit in it - yields zero, which is the area's own unset value and the one
     * {@code 1000-SEND-MAP} already tests for. Zero rather than a refusal, because zero is the only answer
     * that cannot name a card the URI does not: the arm reads it, finds nothing, and the source's
     * {@code NOTFND} handling paints its own message, exactly as it does for a card the operator typed
     * that does not exist.
     *
     * <p>Trailing and leading spaces are stripped before the digit test, because a client echoing a
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

    private static CardSelectRequest coldStartRequest() {
        CardSelectRequest cold = new CardSelectRequest();
        cold.initializeMapArea();
        return cold;
    }

    static int resolveEibcalen(Integer eibcalen, CardSelectRequest request) {
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
                    + "because app/cbl/COCRDSLC.cbl:268 uses it to decide whether the conversation's "
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

    CardSelectResponse handle(CardSelectRequest request, int eibcalen, byte eibAid) {
        Objects.requireNonNull(request, "A request is required: COCRDSLC is entered with a terminal "
                + "input area, and an absent one is spaces rather than nothing");

        CardSelectResponse response = new CardSelectResponse();
        Conversation task = new Conversation();

        // Every abend from here on is routed to the handler, exactly as the CICS declarative did, and the
        // handler has the last word.
        try {
            main0000(request, response, task, eibcalen, eibAid);
        } catch (AbendException alreadyAbending) {
            throw alreadyAbending;
        } catch (RuntimeException abend) {
            throw abendRoutine(task, response, abend);
        }
        return response;
    }

    void main0000(CardSelectRequest request, CardSelectResponse response, Conversation task,
            int eibcalen, byte eibAid) {
        initializeStorage(request, task);

        task.wsTranid = codec.movePicX(LIT_THISTRANID, WS_TRANID_LENGTH);

        task.wsReturnMsg = WS_RETURN_MSG_OFF;

        restoreCommarea(task, eibcalen);

        storePfKeyYYYY(task, eibAid);

        coerceInvalidAid(task);

        dispatch0000(request, response, task, eibcalen);

        if (!task.returned) {
            trailingInputErrorGuard(request, response, task, eibcalen);
        }
        if (!task.returned) {
            commonReturn(response, task);
        }
    }

    /**
     * The trailing guard - {@code app/cbl/COCRDSLC.cbl:386-391}: Unreachable from {@link #main0000},
     * because every {@code EVALUATE} arm terminates the task - see the note at the call site.
     *
     * @param request the terminal input area
     * @param response the map area being painted
     * @param task this task's storage
     * @param eibcalen {@code EIBCALEN}
     */
    void trailingInputErrorGuard(CardSelectRequest request, CardSelectResponse response,
            Conversation task, int eibcalen) {
        if (!task.inputError()) {
            return;
        }
        task.ccWorkArea.setCcardErrorMsg(
                codec.movePicX(task.wsReturnMsg, CardScreenState.CCARD_ERROR_MSG_LENGTH));
        sendMap1000(request, response, task, eibcalen);
        commonReturn(response, task);
    }

    void initializeStorage(CardSelectRequest request, Conversation task) {
        task.ccWorkArea = new CardScreenState();
        task.ccWorkArea.initializeWorkArea();

        task.wsRespCd = 0;
        task.wsReasCd = 0;
        task.wsTranid = CardScreenState.spaces(WS_TRANID_LENGTH);
        task.wsInputFlag = FLG_FILTER_BLANK;
        task.wsEditAcctFlag = FLG_FILTER_BLANK;
        task.wsEditCardFlag = FLG_FILTER_BLANK;
        task.wsReturnFlag = FLG_FILTER_BLANK;
        task.wsPfkFlag = FLG_FILTER_BLANK;
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

        task.wsCommarea = CardScreenState.spaces(WS_COMMAREA_LENGTH);

        task.abendData = SystemMessages.AbendData.spaces();

        task.secUserData = SecUserRecord.blank();
        task.customerRecord = new CustomerRecord();

        task.carddemoCommarea = request.hasNavigationContext()
                ? request.getNavigationContext()
                : NavigationContext.empty();
        task.thisProgCommarea = request.getThisProgCommarea();
    }

    void restoreCommarea(Conversation task, int eibcalen) {
        boolean noCommareaPassed = eibcalen == NO_COMMAREA_LENGTH;
        boolean freshEntryFromMenu = LIT_MENUPGM.equals(
                codec.movePicX(task.carddemoCommarea.fromProgram(),
                        NavigationContext.FROM_PROGRAM_LENGTH))
                && !task.carddemoCommarea.isReenter();

        if (noCommareaPassed || freshEntryFromMenu) {
            task.carddemoCommarea = NavigationContext.empty();
            task.thisProgCommarea = ThisProgCommarea.initialized();
            return;
        }

        byte[] dfhcommarea = new byte[PASSED_COMMAREA_LENGTH];
        byte[] passedCommarea = task.carddemoCommarea.toFixedWidth(codec);
        System.arraycopy(passedCommarea, 0, dfhcommarea, 0, NavigationContext.COMMAREA_LENGTH);
        byte[] passedTrailer =
                codec.encodeImage(task.thisProgCommarea.toImage(codec), "WS-THIS-PROGCOMMAREA");
        System.arraycopy(passedTrailer, 0, dfhcommarea, NavigationContext.COMMAREA_LENGTH,
                THIS_PROGCOMMAREA_LENGTH);

        task.carddemoCommarea = NavigationContext.fromFixedWidth(codec,
                Arrays.copyOfRange(dfhcommarea, 0, NavigationContext.COMMAREA_LENGTH));

        task.thisProgCommarea = ThisProgCommarea.fromImage(codec.decodeImage(
                Arrays.copyOfRange(dfhcommarea, NavigationContext.COMMAREA_LENGTH,
                        PASSED_COMMAREA_LENGTH),
                "WS-THIS-PROGCOMMAREA"));
    }

    void storePfKeyYYYY(Conversation task, byte eibAid) {
        Optional<PfKeyResolver.AidKey> stored =
                PfKeyResolver.storePfKey(eibAid, task.ccWorkArea.aidKey());
        // ifPresent, never orElse: on no match nothing is stored, which is exactly what the copybook's
        // missing WHEN OTHER does.
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

    void dispatch0000(CardSelectRequest request, CardSelectResponse response, Conversation task,
            int eibcalen) {
        if (task.ccWorkArea.isCcardAidPfk03()) {
            backNavigationPfk03(response, task);
            return;
        }

        if (task.carddemoCommarea.isEnter()
                && LIT_CCLISTPGM.equals(codec.movePicX(task.carddemoCommarea.fromProgram(),
                        NavigationContext.FROM_PROGRAM_LENGTH))) {
            enterFromCardList(request, response, task, eibcalen);
            return;
        }

        if (task.carddemoCommarea.isEnter()) {
            sendMap1000(request, response, task, eibcalen);
            commonReturn(response, task);
            return;
        }

        if (task.carddemoCommarea.isReenter()) {
            reenterProcessInputs(request, response, task, eibcalen);
            return;
        }

        unexpectedDataScenario(response, task);
    }

    void backNavigationPfk03(CardSelectResponse response, Conversation task) {
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
                .withFromProgram(codec.movePicX(LIT_THISPGM, NavigationContext.FROM_PROGRAM_LENGTH))
                .withUserTypeUser()
                .withPgmEnter()
                .withLastMapset(codec.movePicX(LIT_THISMAPSET, NavigationContext.LAST_MAPSET_LENGTH))
                .withLastMap(codec.movePicX(LIT_THISMAP, NavigationContext.LAST_MAP_LENGTH));

        task.carddemoCommarea = commarea;

        // :331-334 - EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA) The XCTL passes
        // CARDDEMO-COMMAREA and NOTHING ELSE - not WS-THIS-PROGCOMMAREA, which COMMON-RETURN appends at
        // :398-400 but this arm never touches.
        response.setNavigationContext(commarea);
        response.setCardScreenState(task.ccWorkArea);
        response.setNextProgram(commarea.toProgram());
        task.returned = true;
    }

    static boolean isLowValuesOrSpaces(String value, int length) {
        String image = PIC_X_CODEC.movePicX(value, length);
        return CardScreenState.spaces(length).equals(image)
                || CardScreenState.lowValues(length).equals(image);
    }

    void enterFromCardList(CardSelectRequest request, CardSelectResponse response, Conversation task,
            int eibcalen) {
        task.wsInputFlag = INPUT_OK;

        task.ccWorkArea.setCcAcctIdN(task.carddemoCommarea.acctId());

        task.ccWorkArea.setCcCardNumN(task.carddemoCommarea.cardNum());

        readData9000(task);

        sendMap1000(request, response, task, eibcalen);

        commonReturn(response, task);
    }

    void reenterProcessInputs(CardSelectRequest request, CardSelectResponse response,
            Conversation task, int eibcalen) {
        processInputs2000(request, task);

        if (task.inputError()) {
            sendMap1000(request, response, task, eibcalen);
            commonReturn(response, task);
            return;
        }
        readData9000(task);
        sendMap1000(request, response, task, eibcalen);
        commonReturn(response, task);
    }

    void unexpectedDataScenario(CardSelectResponse response, Conversation task) {
        // :374-376 - ABEND-DATA is filled, but ABEND-MSG is deliberately left as it was.
        task.abendData = task.abendData
                .withAbendCulprit(LIT_THISPGM)
                .withAbendCode(UNEXPECTED_DATA_ABEND_CODE)
                .withAbendReason(CardScreenState.spaces(SystemMessages.ABEND_REASON_LENGTH));

        task.wsReturnMsg = UNEXPECTED_DATA_SCENARIO;

        sendPlainText(response, task);
    }

    void commonReturn(CardSelectResponse response, Conversation task) {
        // :395 - MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG (redundant on one path; preserved - B5).
        task.ccWorkArea.setCcardErrorMsg(
                codec.movePicX(task.wsReturnMsg, CardScreenState.CCARD_ERROR_MSG_LENGTH));

        String commareaImage = codec.decodeImage(task.carddemoCommarea.toFixedWidth(codec),
                "CARDDEMO-COMMAREA");
        String trailerImage = task.thisProgCommarea.toImage(codec);
        task.wsCommarea = codec.movePicX(commareaImage + trailerImage, WS_COMMAREA_LENGTH);

        response.setNavigationContext(task.carddemoCommarea);
        response.setThisProgCommarea(task.thisProgCommarea);
        response.setCardScreenState(task.ccWorkArea);
        task.returned = true;
    }

    void sendMap1000(CardSelectRequest request, CardSelectResponse response, Conversation task,
            int eibcalen) {
        screenInit1100(response, task);
        setupScreenVars1200(response, task, eibcalen);
        setupScreenAttrs1300(request, response, task);
        sendScreen1400(response, task);
    }

    void screenInit1100(CardSelectResponse response, Conversation task) {
        response.initializeGroup();

        task.dateHeader = DateHeader.from(codec, clock);

        response.applyScreenTitles();

        response.applyScreenIdentity();

        // :437 - MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA, a second time (preserved - B5).
        task.dateHeader = DateHeader.from(codec, clock);

        response.applyDateHeader(task.dateHeader);
    }

    void setupScreenVars1200(CardSelectResponse response, Conversation task, int eibcalen) {
        if (eibcalen == NO_COMMAREA_LENGTH) {
            task.wsInfoMsg = WS_PROMPT_FOR_INPUT;
        } else {
            if (task.carddemoCommarea.acctId() == 0L) {
                response.setAcctsido(
                        CardScreenState.lowValues(CardSelectResponse.ACCTSIDO_LENGTH));
            } else {
                response.setAcctsido(codec.movePicX(task.ccWorkArea.getCcAcctId(),
                        CardSelectResponse.ACCTSIDO_LENGTH));
            }

            if (task.carddemoCommarea.cardNum() == 0L) {
                response.setCardsido(
                        CardScreenState.lowValues(CardSelectResponse.CARDSIDO_LENGTH));
            } else {
                response.setCardsido(codec.movePicX(task.ccWorkArea.getCcCardNum(),
                        CardSelectResponse.CARDSIDO_LENGTH));
            }

            if (task.foundCardsForAccount()) {
                projectCardRecord1200(response, task);
            }
        }

        if (task.noInfoMessage()) {
            task.wsInfoMsg = WS_PROMPT_FOR_INPUT;
        }

        response.setErrmsgo(codec.movePicX(task.wsReturnMsg, CardSelectResponse.ERRMSGO_LENGTH));

        response.setInfomsgo(codec.movePicX(task.wsInfoMsg, CardSelectResponse.INFOMSGO_LENGTH));
    }

    void projectCardRecord1200(CardSelectResponse response, Conversation task) {
        if (task.cardRecord.isEmpty()) {
            return;
        }
        CardRecord card = task.cardRecord.get();

        response.setCrdnameo(codec.movePicX(card.cardEmbossedName(),
                CardSelectResponse.CRDNAMEO_LENGTH));

        task.cardExpiraionDateX =
                codec.movePicX(card.cardExpiraionDate(), CardRecord.CARD_EXPIRAION_DATE_LENGTH);

        response.setExpmono(codec.movePicX(card.cardExpiraionDateMonth(),
                CardSelectResponse.EXPMONO_LENGTH));

        response.setExpyearo(codec.movePicX(card.cardExpiraionDateYear(),
                CardSelectResponse.EXPYEARO_LENGTH));

        response.setCrdstcdo(codec.movePicX(card.cardActiveStatus(),
                CardSelectResponse.CRDSTCDO_LENGTH));
    }

    void setupScreenAttrs1300(CardSelectRequest request, CardSelectResponse response,
            Conversation task) {
        boolean arrivedFromCardList = LIT_CCLISTMAPSET.equals(codec.movePicX(
                task.carddemoCommarea.lastMapset(), NavigationContext.LAST_MAPSET_LENGTH))
                && LIT_CCLISTPGM.equals(codec.movePicX(
                        task.carddemoCommarea.fromProgram(), NavigationContext.FROM_PROGRAM_LENGTH));

        // DFHBMPRF is protected + FSET, so a criterion the list already chose cannot be retyped; DFHBMFSE
        // is unprotected + FSET, so it can.
        byte fieldAttribute = arrivedFromCardList ? BmsAttributes.DFHBMPRF : BmsAttributes.DFHBMFSE;
        request.metadata(CardSelectRequest.ScreenField.ACCTSID).setAttribute(fieldAttribute);
        request.metadata(CardSelectRequest.ScreenField.CARDSID).setAttribute(fieldAttribute);

        if (task.flgAcctfilterNotOk() || task.flgAcctfilterBlank()) {
            request.metadata(CardSelectRequest.ScreenField.ACCTSID).positionCursorHere();
            response.setCursorField(CardSelectRequest.ScreenField.ACCTSID.label());
        } else if (task.flgCardfilterNotOk() || task.flgCardfilterBlank()) {
            request.metadata(CardSelectRequest.ScreenField.CARDSID).positionCursorHere();
            response.setCursorField(CardSelectRequest.ScreenField.CARDSID.label());
        } else {
            request.metadata(CardSelectRequest.ScreenField.ACCTSID).positionCursorHere();
            response.setCursorField(CardSelectRequest.ScreenField.ACCTSID.label());
        }

        if (arrivedFromCardList) {
            response.attributes(CardSelectResponse.ScreenField.ACCTSID)
                    .setColour(BmsAttributes.DFHDFCOL);
            response.attributes(CardSelectResponse.ScreenField.CARDSID)
                    .setColour(BmsAttributes.DFHDFCOL);
        }

        if (task.flgAcctfilterNotOk()) {
            response.attributes(CardSelectResponse.ScreenField.ACCTSID)
                    .setColour(BmsAttributes.DFHRED);
        }

        if (task.flgCardfilterNotOk()) {
            response.attributes(CardSelectResponse.ScreenField.CARDSID)
                    .setColour(BmsAttributes.DFHRED);
        }

        boolean reenter = task.carddemoCommarea.isReenter();

        response.applyHighlight(CardSelectResponse.ScreenField.ACCTSID,
                FieldAttributeSetter.resolveFromFlags(false, task.flgAcctfilterBlank(), reenter,
                        CardSelectResponse.ScreenField.ACCTSID.dfhmdfLabel(),
                        CardSelectResponse.MAP_NAME));

        response.applyHighlight(CardSelectResponse.ScreenField.CARDSID,
                FieldAttributeSetter.resolveFromFlags(false, task.flgCardfilterBlank(), reenter,
                        CardSelectResponse.ScreenField.CARDSID.dfhmdfLabel(),
                        CardSelectResponse.MAP_NAME));

        if (task.noInfoMessage()) {
            response.attributes(CardSelectResponse.ScreenField.INFOMSG)
                    .setColour(BmsAttributes.DFHBMDAR);
        } else {
            response.attributes(CardSelectResponse.ScreenField.INFOMSG)
                    .setColour(BmsAttributes.DFHNEUTR);
        }
    }

    void sendScreen1400(CardSelectResponse response, Conversation task) {
        task.ccWorkArea.setCcardNextMapset(
                codec.movePicX(LIT_THISMAPSET, CardScreenState.CCARD_NEXT_MAPSET_LENGTH));

        task.ccWorkArea.setCcardNextMap(
                codec.movePicX(LIT_THISMAP, CardScreenState.CCARD_NEXT_MAP_LENGTH));

        task.carddemoCommarea = task.carddemoCommarea.withPgmReenter();

        // On the arms that never reach 2000-PROCESS-INPUTS the work area still holds the spaces INITIALIZE
        // CC-WORK-AREA left, which is what the member already starts as, so nothing is invented for those
        // paths either.
        response.setNextProgram(task.ccWorkArea.getCcardNextProg());
        response.setNextMapset(task.ccWorkArea.getCcardNextMapset());
        response.setNextMap(task.ccWorkArea.getCcardNextMap());
        response.setNavigationContext(task.carddemoCommarea);
        response.setThisProgCommarea(task.thisProgCommarea);
        response.setCardScreenState(task.ccWorkArea);
        task.wsRespCd = FileStatus.NORMAL;
    }

    void processInputs2000(CardSelectRequest request, Conversation task) {
        receiveMap2100(task);

        editMapInputs2200(request, task);

        task.ccWorkArea.setCcardErrorMsg(
                codec.movePicX(task.wsReturnMsg, CardScreenState.CCARD_ERROR_MSG_LENGTH));

        task.ccWorkArea.setCcardNextProg(
                codec.movePicX(LIT_THISPGM, CardScreenState.CCARD_NEXT_PROG_LENGTH));
        task.ccWorkArea.setCcardNextMapset(
                codec.movePicX(LIT_THISMAPSET, CardScreenState.CCARD_NEXT_MAPSET_LENGTH));
        task.ccWorkArea.setCcardNextMap(
                codec.movePicX(LIT_THISMAP, CardScreenState.CCARD_NEXT_MAP_LENGTH));
    }

    void receiveMap2100(Conversation task) {
        task.wsRespCd = FileStatus.NORMAL;
        task.wsReasCd = CardRepository.NO_REASON_CODE;
    }

    void editMapInputs2200(CardSelectRequest request, Conversation task) {
        task.wsInputFlag = INPUT_OK;
        task.wsEditCardFlag = FLG_FILTER_ISVALID;
        task.wsEditAcctFlag = FLG_FILTER_ISVALID;

        String acctsidI = codec.movePicX(request.getAcctsid(), CardSelectRequest.ACCTSID_LENGTH);
        if (isAsteriskOrSpaces(acctsidI, CardSelectRequest.ACCTSID_LENGTH)) {
            task.ccWorkArea.setCcAcctIdToLowValues();
        } else {
            task.ccWorkArea.setCcAcctId(
                    codec.movePicX(acctsidI, CardScreenState.CC_ACCT_ID_LENGTH));
        }

        String cardsidI = codec.movePicX(request.getCardsid(), CardSelectRequest.CARDSID_LENGTH);
        if (isAsteriskOrSpaces(cardsidI, CardSelectRequest.CARDSID_LENGTH)) {
            task.ccWorkArea.setCcCardNumToLowValues();
        } else {
            task.ccWorkArea.setCcCardNum(
                    codec.movePicX(cardsidI, CardScreenState.CC_CARD_NUM_LENGTH));
        }

        editAccount2210(task);
        editCard2220(task);

        if (task.flgAcctfilterBlank() && task.flgCardfilterBlank()) {
            task.wsReturnMsg = NO_SEARCH_CRITERIA_RECEIVED;
        }
    }

    static boolean isAsteriskOrSpaces(String value, int length) {
        String image = PIC_X_CODEC.movePicX(value, length);
        return PIC_X_CODEC.movePicX(FieldAttributeSetter.ASTERISK, length).equals(image)
                || CardScreenState.spaces(length).equals(image);
    }

    void editAccount2210(Conversation task) {
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
            return;
        }

        if (!task.ccWorkArea.isCcAcctIdNumeric()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = PIC_X_CODEC.movePicX(
                        "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER", WS_RETURN_MSG_LENGTH);
            }
            task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L);
            return;
        }

        task.carddemoCommarea =
                task.carddemoCommarea.withAcctId(codec.decodePic9(task.ccWorkArea.getCcAcctId()));
        task.wsEditAcctFlag = FLG_FILTER_ISVALID;
    }

    void editCard2220(Conversation task) {
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
            return;
        }

        if (!task.ccWorkArea.isCcCardNumNumeric()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditCardFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = PIC_X_CODEC.movePicX(
                        "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER", WS_RETURN_MSG_LENGTH);
            }
            task.carddemoCommarea = task.carddemoCommarea.withCardNum(0L);
            return;
        }

        task.carddemoCommarea = task.carddemoCommarea.withCardNum(task.ccWorkArea.getCcCardNumN());
        task.wsEditCardFlag = FLG_FILTER_ISVALID;
    }

    void readData9000(Conversation task) {
        getCardByAcctCard9100(task);
    }

    void getCardByAcctCard9100(Conversation task) {
        task.wsCardRidCardnum =
                codec.movePicX(task.ccWorkArea.getCcCardNum(), WS_CARD_RID_CARDNUM_LENGTH);

        CardRepository.CardReadResult result = cardRepository.readByCardNumber(task.wsCardRidCardnum);
        task.wsRespCd = result.resp();
        task.wsReasCd = result.resp2();
        task.cardRecord = result.record();

        if (result.isNormal()) {
            task.wsInfoMsg = FOUND_CARDS_FOR_ACCOUNT;
        } else if (result.isNotFound()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            task.wsEditCardFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = DID_NOT_FIND_ACCTCARD_COMBO;
            }
        } else {
            task.wsInputFlag = INPUT_ERROR;
            if (task.returnMessageOff()) {
                task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            }
            recordFileError(task, LIT_CARDFILENAME);
        }
    }

    void getCardByAcct9150(Conversation task) {
        CardRepository.CardReadResult result =
                cardRepository.readByAccountIdViaAltIndex(task.wsCardRidAcctId);
        task.wsRespCd = result.resp();
        task.wsReasCd = result.resp2();
        task.cardRecord = result.record();

        if (result.isNormal()) {
            task.wsInfoMsg = FOUND_CARDS_FOR_ACCOUNT;
        } else if (result.isNotFound()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            task.wsReturnMsg = DID_NOT_FIND_ACCT_IN_CARDXREF;
        } else {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            recordFileError(task, LIT_CARDFILENAME_ACCT_PATH);
        }
    }

    void recordFileError(Conversation task, String fileName) {
        task.errorOpname = codec.movePicX(READ_OPERATION_NAME, ERROR_OPNAME_LENGTH);
        task.errorFile = codec.movePicX(fileName, ERROR_FILE_LENGTH);
        task.errorResp = responseCodeImage(task.wsRespCd);
        task.errorResp2 = responseCodeImage(task.wsReasCd);
        task.wsReturnMsg = codec.movePicX(fileErrorMessage(task), WS_RETURN_MSG_LENGTH);
    }

    static String responseCodeImage(int responseCode) {
        // A CICS response is never negative in practice; the guard is here because movePic9 rejects a
        // negative sender and a corrupted response code must still produce a message rather than an
        // exception.
        String digits = PIC_X_CODEC.movePic9(Math.abs((long) responseCode), RESP_CODE_DIGITS);
        return PIC_X_CODEC.movePicX(digits, ERROR_RESP_LENGTH);
    }

    /**
     * {@code WS-FILE-ERROR-MESSAGE} as one string - {@code app/cbl/COCRDSLC.cbl:102-121}, exactly
     * {@link #FILE_ERROR_MESSAGE_LENGTH} characters.
     *
     * @param task this task's storage, holding the four {@code ERROR-*} items
     * @return exactly {@link #FILE_ERROR_MESSAGE_LENGTH} characters
     * @throws IllegalStateException if the composition does not come to {@link #FILE_ERROR_MESSAGE_LENGTH}
     *     characters
     */
    String fileErrorMessage(Conversation task) {
        String message = FILE_ERROR_PREFIX
                + codec.movePicX(task.errorOpname, ERROR_OPNAME_LENGTH)
                + FILE_ERROR_ON
                + codec.movePicX(task.errorFile, ERROR_FILE_LENGTH)
                + FILE_ERROR_RETURNED_RESP
                + codec.movePicX(task.errorResp, ERROR_RESP_LENGTH)
                + FILE_ERROR_RESP2
                + codec.movePicX(task.errorResp2, ERROR_RESP_LENGTH)
                + FILE_ERROR_TRAILER;
        if (message.length() != FILE_ERROR_MESSAGE_LENGTH) {
            throw new IllegalStateException("WS-FILE-ERROR-MESSAGE must be exactly "
                    + FILE_ERROR_MESSAGE_LENGTH + " characters, as app/cbl/COCRDSLC.cbl:102-121 declares "
                    + "it, but the composition came to " + message.length());
        }
        return message;
    }

    void sendLongText(CardSelectResponse response, Conversation task) {
        String transmitted = codec.movePicX(task.wsLongMsg, WS_LONG_MSG_LENGTH);
        response.setErrmsgo(codec.movePicX(transmitted, CardSelectResponse.ERRMSGO_LENGTH));
        response.setNavigationContext(task.carddemoCommarea);
        response.setCardScreenState(task.ccWorkArea);

        task.returned = true;
    }

    void sendPlainText(CardSelectResponse response, Conversation task) {
        String transmitted = codec.movePicX(task.wsReturnMsg, WS_RETURN_MSG_LENGTH);
        response.setErrmsgo(codec.movePicX(transmitted, CardSelectResponse.ERRMSGO_LENGTH));
        response.setNavigationContext(task.carddemoCommarea);
        response.setCardScreenState(task.ccWorkArea);

        if (LOG.isWarnEnabled()) {
            LOG.warn(LIT_THISPGM + " sent plain text (abend code "
                    + task.abendData.abendCode().trim() + "): " + transmitted.trim());
        }

        task.returned = true;
    }

    AbendException abendRoutine(Conversation task, CardSelectResponse response, RuntimeException cause) {
        // ABEND-DATA is spaces before any arm writes to it, and INITIALIZE may not have run at all if the
        // failure came very early, so it is defaulted here rather than assumed present.
        SystemMessages.AbendData abendData =
                task.abendData == null ? SystemMessages.AbendData.spaces() : task.abendData;

        String abendMsg = PIC_X_CODEC.movePicX(abendData.abendMsg(), SystemMessages.ABEND_MSG_LENGTH);
        if (CardScreenState.lowValues(SystemMessages.ABEND_MSG_LENGTH).equals(abendMsg)) {
            abendData = abendData.withAbendMsg(UNEXPECTED_ABEND_OCCURRED);
        }

        abendData = abendData.withAbendCulprit(LIT_THISPGM);
        task.abendData = abendData;

        LOG.error(LIT_THISPGM + " abending with ABCODE " + ABEND_ROUTINE_ABCODE + ": "
                + AbendException.ABEND_DISPLAY_TEXT + " " + abendData.toDeclaredWidths()
                + " - " + BackendDiagnostic.of(cause).describe());

        // The response is left exactly as the failing path left it: EXEC CICS SEND sends ABEND-DATA, not
        // the map, so nothing further is painted. Reading it keeps the parameter honest rather than unused.
        response.setCardScreenState(task.ccWorkArea == null ? new CardScreenState() : task.ccWorkArea);
        task.returned = true;

        // :871-877 - HANDLE ABEND CANCEL, then EXEC CICS ABEND ABCODE('9999'). withoutAbendParameters,
        // deliberately, NOT standard(...). AbendException models CALL 'CEE3ABD', whose eight standard sites
        // move 999 into ABCODE and 0 into TIMING.
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
}
