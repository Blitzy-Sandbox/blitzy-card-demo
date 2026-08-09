package com.vsergeychik.carddemo.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vsergeychik.carddemo.card.CardRepository.CardReadResult;
import com.vsergeychik.carddemo.card.CardSelectController.Conversation;
import com.vsergeychik.carddemo.card.CardSelectController.ThisProgCommarea;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest;
import com.vsergeychik.carddemo.card.dto.CardSelectResponse;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.WebConfig;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@link CardSelectController} - the {@code COCRDSLC} / {@code CCDL} credit-card detail screen.
 *
 * <p>Every test that asserts a <em>decision</em> instantiates the controller <strong>directly</strong>
 * with a mocked {@link CardRepository} and a fixed {@link Clock}. There is no Spring context, no
 * {@code MockMvc} and no {@code JobLauncher} in the decision path, which is gate <strong>G51</strong>: the
 * arithmetic and the guard chains are asserted where they live, and a failure names the paragraph rather
 * than an HTTP status.
 *
 * <p>The single exception is {@link HttpWiring}, and it is an exception only in mechanism, not in
 * principle. It uses {@code MockMvcBuilders.standaloneSetup} to assert the <em>transport</em> contract and
 * nothing else - that the mapping routes, that the path variable and the optional query parameters bind,
 * that the status codes are right, and that the body serialises to the shape the symbolic map defines. Not
 * one branch of the program is asserted through it, so no behaviour has a slower or less attributable
 * second home.
 *
 * <p>The clock is fixed for the same reason the controller takes one: {@code 1100-SCREEN-INIT} reads
 * {@code FUNCTION CURRENT-DATE} twice, and a screen that carried a live clock could not be compared
 * byte-for-byte against anything.
 *
 * <p>Expectations here are <strong>statically derived</strong> from {@code app/cbl/COCRDSLC.cbl},
 * {@code app/cpy-bms/COCRDSL.CPY}, {@code app/bms/COCRDSL.bms}, {@code app/cpy/CVCRD01Y.cpy} and
 * {@code app/cpy/CVACT02Y.cpy}. The legacy COBOL cannot be executed in this environment (risk R-A), so no
 * captured baseline exists and none is claimed - each assertion cites the line it was read from so it can
 * be checked against the source by eye.
 */
@DisplayName("CardSelectController - COCRDSLC, transaction CCDL, the credit-card detail screen")
class CardSelectControllerTest {

    /** A fixed instant, so both {@code FUNCTION CURRENT-DATE} reads see the same second. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:33Z"), ZoneOffset.UTC);

    /** US-ASCII: the fixtures' code page, and the one the parity harness seeds from. */
    private static final Charset CHARSET = StandardCharsets.US_ASCII;

    /** A sixteen-digit card number, at its declared {@code PIC X(16)} width. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** An eleven-digit account id, at its declared {@code PIC X(11)} width. */
    private static final String ACCOUNT_ID = "00000000011";

    /**
     * The map the <em>card list</em> program declares as its own - {@code LIT-THISMAP PIC X(7)} at
     * {@code app/cbl/COCRDLIC.cbl:185}.
     *
     * <p>Declared here for one reason only: it is the truthful inbound value of
     * {@code CDEMO-LAST-MAP}, because {@code COCRDLIC} moves its own {@code LIT-THISMAP} into that
     * commarea field at its :322, :391, :426, :525, :590 and :608 before transferring control here.
     * Fixtures that model "arrived from the card list" therefore carry it.
     *
     * <p><strong>This is emphatically not a correction of
     * {@link CardSelectController#LIT_CCLISTMAP}.</strong> That literal is wrong in the source and
     * stays wrong (practice B5); {@code cclistmapKeepsItsDefect()} pins it. The two are kept apart by
     * name so no future edit can confuse "the value COCRDLIC declares" with "the value COCRDSLC
     * declares for COCRDLIC". {@code COCRDSLC} never reads {@code CDEMO-LAST-MAP} at all - it reads
     * only {@code CDEMO-LAST-MAPSET}, at :505 and :527 - so this value is behaviourally inert and is
     * present purely so the commarea fixtures describe the real system.
     */
    private static final String CARD_LIST_OWN_MAP = "CCRDLIA";

    private CardRepository repository;
    private CardSelectController controller;

    @BeforeEach
    void setUp() {
        repository = mock(CardRepository.class);
        controller = new CardSelectController(repository, FIXED_CLOCK, CHARSET);
    }

    // =================================================================================================
    // Helpers
    // =================================================================================================

    /** A card record whose expiry is the {@code YYYY-MM-DD} form {@code CVACT02Y} declares. */
    private static CardRecord card() {
        return CardRecord.moving(CARD_NUMBER, 11L, 123, "JOHN Q PUBLIC", "2026-04-30", "Y",
                new FixedWidthCodec(CHARSET));
    }

    private static CardSelectRequest request(String cardsid, String acctsid,
            NavigationContext commarea) {
        CardSelectRequest request = new CardSelectRequest();
        request.initializeMapArea();
        request.setCardsid(cardsid);
        request.setAcctsid(acctsid);
        request.setNavigationContext(commarea);
        return request;
    }

    /** A commarea in the {@code CDEMO-PGM-ENTER} state, arriving from the card list. */
    private static NavigationContext fromCardList() {
        return NavigationContext.empty()
                .withFromProgram(CardSelectControllerAccess.CCLIST_PGM)
                .withFromTranid("CCLI")
                .withPgmEnter()
                .withAcctId(11L)
                .withCardNum(Long.parseLong(CARD_NUMBER));
    }

    /** The literals under test, named once so a typo fails in one place. */
    private static final class CardSelectControllerAccess {
        static final String THIS_PGM = "COCRDSLC";
        static final String THIS_TRANID = "CCDL";
        static final String CCLIST_PGM = "COCRDLIC";
        static final String CCLIST_MAPSET = "COCRDLI";
        static final String MENU_PGM = "COMEN01C";
        static final String MENU_TRANID = "CM00";

        private CardSelectControllerAccess() {
        }
    }

    private Conversation runMain(CardSelectRequest request, int eibcalen, byte eibAid,
            CardSelectResponse response) {
        Conversation task = new Conversation();
        controller.main0000(request, response, task, eibcalen, eibAid);
        return task;
    }

    /** A task with storage initialised, as {@code :254-256} leaves it. */
    private Conversation initialisedTask(NavigationContext commarea) {
        Conversation task = new Conversation();
        controller.initializeStorage(request("", "", commarea), task);
        task.carddemoCommarea = commarea;
        return task;
    }

    // =================================================================================================

    @Nested
    @DisplayName("The literals, at the widths app/cbl/COCRDSLC.cbl:162-190 declares them")
    class Literals {

        @Test
        @DisplayName("LIT-THISMAPSET is EIGHT characters with a trailing space, and is not normalised")
        void thisMapsetKeepsItsEighthByte() {
            // :167-168 - PIC X(8) VALUE 'COCRDSL '. COCRDLIC declares its own as X(7); this one does not.
            assertThat(CardSelectController.LIT_THISMAPSET).isEqualTo("COCRDSL ").hasSize(8);
            assertThat(CardSelectController.LIT_THISMAP).isEqualTo("CCRDSLA").hasSize(7);
        }

        @Test
        @DisplayName("LIT-CCLISTMAP is the source's wrong value 'CCRDSLA' and is NOT corrected")
        void cclistmapKeepsItsDefect() {
            // :177-178 - the card list's map is really the one COCRDLIC declares at its :185, not this
            // one. Correcting the literal would be a behaviour change, so the defect is preserved and
            // pinned here so nobody "fixes" it later. Equality to the source's own value is the strongest
            // form of that guard: it rules out the corrected spelling and every other spelling at once,
            // which is why no separate isNotEqualTo is needed.
            assertThat(CardSelectController.LIT_CCLISTMAP).isEqualTo("CCRDSLA").hasSize(7);
            assertThat(CardSelectController.LIT_CCLISTMAP).isNotEqualTo(CARD_LIST_OWN_MAP);
            assertThat(CardSelectController.LIT_CCLISTMAPSET).isEqualTo("COCRDLI");
        }

        @Test
        @DisplayName("the four dead literals are still declared, because deleting one is a change")
        void deadLiteralsSurvive() {
            assertThat(CardSelectController.LIT_CCLISTTRANID).isEqualTo("CCLI");
            assertThat(CardSelectController.LIT_MENUMAPSET).isEqualTo("COMEN01");
            assertThat(CardSelectController.LIT_MENUMAP).isEqualTo("COMEN1A");
            assertThat(CardSelectController.WS_EXIT_MESSAGE.strip())
                    .isEqualTo("PF03 pressed.Exiting");
        }

        @Test
        @DisplayName("the CICS file names are the base cluster and its alternate-index path")
        void fileNames() {
            // :187-190. Eight characters each, and a logical key rather than a dataset name (gate G46).
            assertThat(CardSelectController.LIT_CARDFILENAME).isEqualTo("CARDDAT ").hasSize(8);
            assertThat(CardSelectController.LIT_CARDFILENAME_ACCT_PATH).isEqualTo("CARDAIX ")
                    .hasSize(8);
        }

        @Test
        @DisplayName("WS-RETURN-MSG-OFF is 75 SPACES, not LOW-VALUES")
        void returnMessageOffIsSpaces() {
            // :135 - 88 WS-RETURN-MSG-OFF VALUE SPACES. The similarly named CCARD-RETURN-MSG-OFF in
            // CVCRD01Y:30 is LOW-VALUES and belongs to a field this program never touches.
            assertThat(CardSelectController.WS_RETURN_MSG_OFF).hasSize(75).isEqualTo(" ".repeat(75));
            assertThat(CardSelectController.WS_RETURN_MSG_OFF)
                    .isNotEqualTo(CardScreenState.lowValues(75));
        }

        @Test
        @DisplayName("every message constant is padded to its receiver's declared width")
        void messagesArePaddedToWidth() {
            assertThat(CardSelectController.FOUND_CARDS_FOR_ACCOUNT).hasSize(40)
                    .startsWith("   Displaying requested details");
            assertThat(CardSelectController.WS_PROMPT_FOR_INPUT).hasSize(40)
                    .startsWith("Please enter Account and Card Number");
            assertThat(CardSelectController.WS_PROMPT_FOR_ACCT).hasSize(75)
                    .startsWith("Account number not provided");
            assertThat(CardSelectController.WS_PROMPT_FOR_CARD).hasSize(75)
                    .startsWith("Card number not provided");
            assertThat(CardSelectController.NO_SEARCH_CRITERIA_RECEIVED).hasSize(75)
                    .startsWith("No input received");
            assertThat(CardSelectController.DID_NOT_FIND_ACCTCARD_COMBO).hasSize(75)
                    .startsWith("Did not find cards for this search condition");
            assertThat(CardSelectController.DID_NOT_FIND_ACCT_IN_CARDXREF).hasSize(75)
                    .startsWith("Did not find this account in cards database");
            assertThat(CardSelectController.UNEXPECTED_DATA_SCENARIO).hasSize(75)
                    .startsWith("UNEXPECTED DATA SCENARIO");
            // Dead, and still exact.
            assertThat(CardSelectController.SEARCHED_ACCT_MESSAGE).hasSize(75)
                    .startsWith("Account number must be a non zero 11 digit number");
            assertThat(CardSelectController.SEARCHED_CARD_NOT_NUMERIC).hasSize(75)
                    .startsWith("Card number if supplied must be a 16 digit number");
            assertThat(CardSelectController.XREF_READ_ERROR).hasSize(75)
                    .startsWith("Error reading Card Data File");
            assertThat(CardSelectController.CODING_TO_BE_DONE).hasSize(75)
                    .startsWith("Looks Good.... so far");
        }

        @Test
        @DisplayName("the file-error message geometry sums to exactly 80, and the commarea to 172")
        void geometry() {
            assertThat(CardSelectController.FILE_ERROR_MESSAGE_LENGTH).isEqualTo(80);
            assertThat(CardSelectController.PASSED_COMMAREA_LENGTH).isEqualTo(172);
            assertThat(CardSelectController.THIS_PROGCOMMAREA_LENGTH).isEqualTo(12);
            assertThat(CardSelectController.WS_COMMAREA_LENGTH).isEqualTo(2000);
            assertThat(CardSelectController.FILE_ERROR_TRAILER).isEqualTo("     ");
        }
    }

    @Nested
    @DisplayName("INITIALIZE - spaces and zeros, never null (app/cbl/COCRDSLC.cbl:254-256)")
    class Initialize {

        @Test
        @DisplayName("the two edit flags become SPACES, which makes both filters read as BLANK")
        void editFlagsStartBlank() {
            Conversation task = initialisedTask(NavigationContext.empty());

            assertThat(task.flgAcctfilterBlank()).isTrue();
            assertThat(task.flgCardfilterBlank()).isTrue();
            assertThat(task.flgAcctfilterNotOk()).isFalse();
            assertThat(task.flgAcctfilterIsvalid()).isFalse();
            assertThat(task.flgCardfilterNotOk()).isFalse();
            assertThat(task.flgCardfilterIsvalid()).isFalse();
        }

        @Test
        @DisplayName("WS-INPUT-FLAG becomes a SPACE, which satisfies NONE of its three condition names")
        void inputFlagSatisfiesNoConditionName() {
            Conversation task = initialisedTask(NavigationContext.empty());

            // :52-54 declare '0', '1' and LOW-VALUES. A space is none of them, and that is the state
            // INITIALIZE actually produces.
            assertThat(task.inputOk()).isFalse();
            assertThat(task.inputError()).isFalse();
            assertThat(task.inputPending()).isFalse();
        }

        @Test
        @DisplayName("WS-CARD-RID-ACCT-ID is PIC 9(11), so it initialises to ZEROS not spaces")
        void numericKeyInitialisesToZeros() {
            Conversation task = initialisedTask(NavigationContext.empty());

            assertThat(task.wsCardRidAcctId).isEqualTo("00000000000").hasSize(11);
            assertThat(task.wsCardRidCardnum).isEqualTo(" ".repeat(16));
        }

        @Test
        @DisplayName("a request carrying NO commarea starts from the empty one, not from null")
        void anAbsentCommareaBecomesTheEmptyOne() {
            CardSelectRequest bare = new CardSelectRequest();
            bare.initializeMapArea();
            assertThat(bare.hasNavigationContext()).isFalse();
            Conversation task = new Conversation();

            controller.initializeStorage(bare, task);

            assertThat(task.carddemoCommarea).isEqualTo(NavigationContext.empty());
            assertThat(task.thisProgCommarea).isEqualTo(ThisProgCommarea.initialized());
        }

        @Test
        @DisplayName("WS-COMMAREA is 2000 spaces and the messages are off")
        void areasAndMessages() {
            Conversation task = initialisedTask(NavigationContext.empty());

            assertThat(task.wsCommarea).hasSize(2000).isBlank();
            assertThat(task.returnMessageOff()).isTrue();
            assertThat(task.noInfoMessage()).isTrue();
            assertThat(task.cardRecord).isEmpty();
            assertThat(task.wsLongMsg).hasSize(500);
            assertThat(task.abendData).isNotNull();
        }

        @Test
        @DisplayName("the two declared-only copybook areas exist, and no field of either is read")
        void theDeclaredOnlyAreasAreAllocated() {
            Conversation task = initialisedTask(NavigationContext.empty());

            // COPY CSUSR01Y at :227 and COPY CVCUS01Y at :240 are LIVE, unlike CVACT01Y at :231 and
            // CVACT03Y at :237. A live COPY allocates storage, so the areas exist (practice B5)...
            assertThat(task.secUserData).isEqualTo(SecUserRecord.blank());
            assertThat(task.customerRecord).isNotNull();
            // ...and nothing in this program reads them. In particular no password is compared here:
            // COCRDSLC performs no authentication, and none is added (practice B6).
            assertThat(task.secUserData.secUsrPwd()).isBlank();
            assertThat(task.secUserData.secUsrType()).isBlank();
        }

        @Test
        @DisplayName("the never-tested flags are still declared and still answerable")
        void deadFlagsAreStillModelled() {
            Conversation task = initialisedTask(NavigationContext.empty());

            // :63-65 WS-RETURN-FLAG. Declared by the source, tested by nothing (practice B5).
            assertThat(task.wsReturnFlagOff()).isFalse();
            assertThat(task.wsReturnFlagOn()).isFalse();
            task.wsReturnFlag = CardSelectController.WS_RETURN_FLAG_ON;
            assertThat(task.wsReturnFlagOn()).isTrue();
            task.wsReturnFlag = CardSelectController.WS_RETURN_FLAG_OFF;
            assertThat(task.wsReturnFlagOff()).isTrue();
            task.wsInputFlag = CardSelectController.INPUT_PENDING;
            assertThat(task.inputPending()).isTrue();
        }
    }

    @Nested
    @DisplayName("The commarea restore (app/cbl/COCRDSLC.cbl:268-279)")
    class CommareaRestore {

        @Test
        @DisplayName("EIBCALEN = 0 discards the commarea entirely")
        void noCommareaResets() {
            Conversation task = initialisedTask(
                    NavigationContext.empty().withFromProgram(CardSelectControllerAccess.CCLIST_PGM));

            controller.restoreCommarea(task, CardSelectController.NO_COMMAREA_LENGTH);

            assertThat(task.carddemoCommarea).isEqualTo(NavigationContext.empty());
            assertThat(task.thisProgCommarea).isEqualTo(ThisProgCommarea.initialized());
        }

        @Test
        @DisplayName("a FRESH entry from the menu discards it: FROM-PROGRAM = COMEN01C and NOT REENTER")
        void freshEntryFromMenuResets() {
            Conversation task = initialisedTask(NavigationContext.empty()
                    .withFromProgram(CardSelectControllerAccess.MENU_PGM)
                    .withAcctId(99L)
                    .withPgmEnter());

            controller.restoreCommarea(task, CardSelectController.PASSED_COMMAREA_LENGTH);

            assertThat(task.carddemoCommarea.acctId()).isZero();
        }

        @Test
        @DisplayName("a RE-ENTRY from the menu KEEPS it - the NOT in the condition is load-bearing")
        void reentryFromMenuKeepsIt() {
            Conversation task = initialisedTask(NavigationContext.empty()
                    .withFromProgram(CardSelectControllerAccess.MENU_PGM)
                    .withAcctId(99L)
                    .withPgmReenter());

            controller.restoreCommarea(task, CardSelectController.PASSED_COMMAREA_LENGTH);

            assertThat(task.carddemoCommarea.acctId()).isEqualTo(99L);
            assertThat(task.carddemoCommarea.isReenter()).isTrue();
        }

        @Test
        @DisplayName("the else arm splits at the 1-based offsets (1:160) and (161:12), losing nothing")
        void theSplitRoundTripsEveryField() {
            // Every member is supplied AT ITS DECLARED WIDTH, because that is the state a commarea is
            // actually in when it crosses an EXEC CICS RETURN: it is a fixed-width area, so a short
            // value has already been padded by the MOVE that put it there. Supplying the padded form is
            // what makes the round trip an identity, and what makes a shifted offset visible.
            NavigationContext passed = NavigationContext.empty()
                    .withFromProgram(CardSelectControllerAccess.CCLIST_PGM)
                    .withFromTranid("CCLI")
                    .withToProgram("COCRDSLC")
                    .withToTranid("CCDL")
                    .withUserId("USER0001")
                    .withUserTypeAdmin()
                    .withPgmReenter()
                    .withCustId(123456789)
                    .withCustFname(padded("JOHN", NavigationContext.CUST_FNAME_LENGTH))
                    .withCustMname(padded("Q", NavigationContext.CUST_MNAME_LENGTH))
                    .withCustLname(padded("PUBLIC", NavigationContext.CUST_LNAME_LENGTH))
                    .withAcctId(11L)
                    .withAcctStatus("Y")
                    .withCardNum(Long.parseLong(CARD_NUMBER))
                    .withLastMapset(CardSelectControllerAccess.CCLIST_MAPSET)
                    .withLastMap(CARD_LIST_OWN_MAP);
            Conversation task = initialisedTask(passed);
            task.thisProgCommarea = new ThisProgCommarea("COCRDLIC", "CCLI");

            controller.restoreCommarea(task, CardSelectController.PASSED_COMMAREA_LENGTH);

            // Every one of the sixteen members survives the byte split, which is what proves the two
            // offsets: a wrong one would shift every field after it.
            assertThat(task.carddemoCommarea).isEqualTo(passed);
            assertThat(task.thisProgCommarea.caFromProgram()).isEqualTo("COCRDLIC");
            assertThat(task.thisProgCommarea.caFromTranid()).isEqualTo("CCLI");
        }

        @Test
        @DisplayName("a SHORT value comes back space-padded, because the area it crossed is fixed-width")
        void theSplitPadsAShortValueToItsDeclaredWidth() {
            NavigationContext passed = NavigationContext.empty()
                    .withFromProgram(CardSelectControllerAccess.CCLIST_PGM)
                    .withCustFname("JOHN")
                    .withPgmReenter();
            Conversation task = initialisedTask(passed);

            controller.restoreCommarea(task, CardSelectController.PASSED_COMMAREA_LENGTH);

            // The value is intact and the field is at its width: that IS the COBOL MOVE, not a defect.
            assertThat(task.carddemoCommarea.custFname())
                    .hasSize(NavigationContext.CUST_FNAME_LENGTH)
                    .startsWith("JOHN");
            assertThat(task.carddemoCommarea.custFname().strip()).isEqualTo("JOHN");
        }

        private String padded(String value, int length) {
            return value + " ".repeat(length - value.length());
        }

        @Test
        @DisplayName("WS-THIS-PROGCOMMAREA renders and reads back as exactly twelve characters")
        void trailerGeometry() {
            ThisProgCommarea trailer = new ThisProgCommarea("AB", "C");

            String image = trailer.toImage(controller.codec());

            assertThat(image).hasSize(12).isEqualTo("AB      C   ");
            assertThat(ThisProgCommarea.fromImage(image))
                    .isEqualTo(new ThisProgCommarea("AB      ", "C   "));
        }

        @Test
        @DisplayName("a trailer image of the wrong width fails loudly rather than shifting a field")
        void trailerRejectsAWrongWidth() {
            assertThatThrownBy(() -> ThisProgCommarea.fromImage("too short"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("12");
            assertThatThrownBy(() -> new ThisProgCommarea(null, "CCDL"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ThisProgCommarea("COCRDSLC", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("an unstated EIBCALEN is inferred from whether any commarea field was named")
        void eibcalenInference() {
            assertThat(CardSelectController.anyCommareaFieldSupplied(null, null, null)).isFalse();
            assertThat(CardSelectController.anyCommareaFieldSupplied(null, "COCRDLIC", null)).isTrue();
            assertThat(CardSelectController.anyCommareaFieldSupplied()).isFalse();
        }
    }

    @Nested
    @DisplayName("YYYY-STORE-PFKEY and the invalid-AID coercion (app/cbl/COCRDSLC.cbl:284-299)")
    class AttentionIdentifier {

        @ParameterizedTest(name = "EIBAID 0x{0} resolves to {1}")
        @CsvSource({"7D,ENTER", "F3,PFK03", "6D,CLEAR", "6C,'PA1  '", "7C,PFK12", "C3,PFK03"})
        @DisplayName("a recognised AID byte is stored as its five-character token")
        void recognisedAidsAreStored(String hex, String token) {
            Conversation task = initialisedTask(NavigationContext.empty());

            controller.storePfKeyYYYY(task, (byte) Integer.parseInt(hex, 16));

            assertThat(task.ccWorkArea.getCcardAid()).isEqualTo(token);
        }

        @Test
        @DisplayName("an UNRECOGNISED byte stores NOTHING: the copybook has no WHEN OTHER")
        void unrecognisedAidStoresNothing() {
            Conversation task = initialisedTask(NavigationContext.empty());
            // DFHPA3 (0x6B) is a real AID that CSSTRPFY deliberately does not test.
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isEmpty();

            controller.storePfKeyYYYY(task, CicsAid.DFHPA3);

            // Left exactly as INITIALIZE left it - five spaces - because the EVALUATE neither defaults
            // nor pre-clears.
            assertThat(task.ccWorkArea.getCcardAid()).isEqualTo("     ");
            assertThat(task.ccWorkArea.aidKey()).isEmpty();
        }

        @Test
        @DisplayName("an unrecognised byte RETAINS a token an earlier turn stored")
        void unrecognisedAidRetainsThePrevious() {
            Conversation task = initialisedTask(NavigationContext.empty());
            task.ccWorkArea.setCcardAidCondition(PfKeyResolver.AidKey.PFK03);

            controller.storePfKeyYYYY(task, CicsAid.DFHPA3);

            assertThat(task.ccWorkArea.isCcardAidPfk03()).isTrue();
        }

        @ParameterizedTest(name = "AID {0} is left alone because it is valid here")
        @ValueSource(strings = {"ENTER", "PFK03"})
        @DisplayName("ENTER and PF3 are the only valid keys, and neither is rewritten")
        void validKeysSurvive(String token) {
            Conversation task = initialisedTask(NavigationContext.empty());
            task.ccWorkArea.setCcardAid(token);

            controller.coerceInvalidAid(task);

            assertThat(task.pfkValid()).isTrue();
            assertThat(task.pfkInvalid()).isFalse();
            assertThat(task.ccWorkArea.getCcardAid()).isEqualTo(token);
        }

        @ParameterizedTest(name = "AID {0} is silently COERCED to ENTER, never rejected")
        @ValueSource(strings = {"PFK12", "CLEAR", "PA1  ", "PA2  ", "PFK01", "     "})
        @DisplayName("every other key becomes ENTER - the least obvious behaviour in the program")
        void invalidKeysAreCoercedToEnter(String token) {
            Conversation task = initialisedTask(NavigationContext.empty());
            task.ccWorkArea.setCcardAid(token);

            controller.coerceInvalidAid(task);

            assertThat(task.ccWorkArea.isCcardAidEnter()).isTrue();
            // :297-299 rewrites the AID and NOT the flag, so the flag is left reading INVALID.
            assertThat(task.pfkInvalid()).isTrue();
        }
    }

    @Nested
    @DisplayName("EVALUATE TRUE - five arms, first match wins (app/cbl/COCRDSLC.cbl:304-381, gate G30)")
    class Dispatcher {

        @Test
        @DisplayName("PF3 with no caller transfers to the MAIN MENU and records this screen as caller")
        void pf3WithNoCallerGoesToTheMenu() {
            CardSelectResponse response = new CardSelectResponse();
            NavigationContext commarea = NavigationContext.empty().withPgmEnter();

            Conversation task = runMain(request(CARD_NUMBER, ACCOUNT_ID, commarea),
                    CardSelectController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF3, response);

            assertThat(task.carddemoCommarea.toProgram().strip())
                    .isEqualTo(CardSelectControllerAccess.MENU_PGM);
            assertThat(task.carddemoCommarea.toTranid().strip())
                    .isEqualTo(CardSelectControllerAccess.MENU_TRANID);
            assertThat(task.carddemoCommarea.fromProgram().strip())
                    .isEqualTo(CardSelectControllerAccess.THIS_PGM);
            assertThat(task.carddemoCommarea.fromTranid().strip())
                    .isEqualTo(CardSelectControllerAccess.THIS_TRANID);
            // :331-334 - XCTL becomes a response field (gate G40)
            assertThat(response.getNextProgram().strip())
                    .isEqualTo(CardSelectControllerAccess.MENU_PGM);
            // The map is NOT sent on this arm, so the fifteen items stay LOW-VALUES.
            assertThat(response.getTitle01o()).isEqualTo(CardScreenState.lowValues(40));
            verifyNoInteractions(repository);
        }

        @ParameterizedTest(name = "FROM-TRANID as {0} still routes to the menu")
        @CsvSource(value = {"LOW-VALUES", "SPACES"})
        @DisplayName("BOTH empty byte patterns route to the menu - LOW-VALUES and SPACES")
        void bothEmptyPatternsRouteToTheMenu(String pattern) {
            String empty = "LOW-VALUES".equals(pattern)
                    ? CardScreenState.lowValues(4)
                    : CardScreenState.spaces(4);
            String emptyProgram = "LOW-VALUES".equals(pattern)
                    ? CardScreenState.lowValues(8)
                    : CardScreenState.spaces(8);
            CardSelectResponse response = new CardSelectResponse();
            NavigationContext commarea = NavigationContext.empty()
                    .withFromTranid(empty).withFromProgram(emptyProgram).withPgmEnter();

            Conversation task = runMain(request(CARD_NUMBER, ACCOUNT_ID, commarea),
                    CardSelectController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF3, response);

            assertThat(task.carddemoCommarea.toTranid().strip())
                    .isEqualTo(CardSelectControllerAccess.MENU_TRANID);
            assertThat(task.carddemoCommarea.toProgram().strip())
                    .isEqualTo(CardSelectControllerAccess.MENU_PGM);
        }

        @Test
        @DisplayName("PF3 with a real caller transfers back to that caller, not to the menu")
        void pf3WithACallerGoesBackToIt() {
            CardSelectResponse response = new CardSelectResponse();
            NavigationContext commarea = NavigationContext.empty()
                    .withFromProgram(CardSelectControllerAccess.CCLIST_PGM)
                    .withFromTranid("CCLI")
                    .withPgmEnter();

            Conversation task = runMain(request(CARD_NUMBER, ACCOUNT_ID, commarea),
                    CardSelectController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF3, response);

            assertThat(task.carddemoCommarea.toProgram().strip())
                    .isEqualTo(CardSelectControllerAccess.CCLIST_PGM);
            assertThat(task.carddemoCommarea.toTranid().strip()).isEqualTo("CCLI");
        }

        @Test
        @DisplayName("PF3 forces USER-TYPE to 'U' unconditionally, even for an administrator")
        void pf3DowngradesTheUserTypeUnconditionally() {
            CardSelectResponse response = new CardSelectResponse();
            NavigationContext commarea = NavigationContext.empty()
                    .withFromProgram(CardSelectControllerAccess.CCLIST_PGM)
                    .withUserTypeAdmin()
                    .withPgmEnter();

            Conversation task = runMain(request(CARD_NUMBER, ACCOUNT_ID, commarea),
                    CardSelectController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF3, response);

            // :326 - SET CDEMO-USRTYP-USER TO TRUE, with no guard. Practice B6: reproduced, not
            // "hardened" and not corrected.
            assertThat(task.carddemoCommarea.isUser()).isTrue();
            assertThat(task.carddemoCommarea.isAdmin()).isFalse();
            // :327-329
            assertThat(task.carddemoCommarea.isEnter()).isTrue();
            assertThat(task.carddemoCommarea.lastMapset()).isEqualTo("COCRDSL");
            assertThat(task.carddemoCommarea.lastMap()).isEqualTo("CCRDSLA");
        }

        @Test
        @DisplayName("ENTER from the CARD LIST reads the record straight away - the qualified arm")
        void enterFromCardListReadsTheRecord() {
            when(repository.readByCardNumber(anyString()))
                    .thenReturn(CardReadResult.normal(card()));
            CardSelectResponse response = new CardSelectResponse();

            Conversation task = runMain(request("", "", fromCardList()),
                    CardSelectController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER, response);

            // :341-343 - the criteria come from the COMMAREA, through the NUMERIC redefinitions
            assertThat(task.inputOk()).isTrue();
            assertThat(task.ccWorkArea.getCcAcctId()).isEqualTo(ACCOUNT_ID);
            assertThat(task.ccWorkArea.getCcCardNum()).isEqualTo(CARD_NUMBER);
            assertThat(task.foundCardsForAccount()).isTrue();
            verify(repository).readByCardNumber(CARD_NUMBER);
            // The screen was painted from the record
            assertThat(response.getCrdnameo().strip()).isEqualTo("JOHN Q PUBLIC");
            assertThat(response.getExpyearo()).isEqualTo("2026");
            assertThat(response.getExpmono()).isEqualTo("04");
            assertThat(response.getCrdstcdo()).isEqualTo("Y");
        }

        @Test
        @DisplayName("the numeric REDEFINES zero-fills on the LEFT, so a short account id still keys")
        void theNumericRedefinesZeroFillsOnTheLeft() {
            when(repository.readByCardNumber(anyString()))
                    .thenReturn(CardReadResult.normal(card()));
            NavigationContext commarea = NavigationContext.empty()
                    .withFromProgram(CardSelectControllerAccess.CCLIST_PGM)
                    .withPgmEnter()
                    .withAcctId(42L)
                    .withCardNum(7L);

            Conversation task = runMain(request("", "", commarea),
                    CardSelectController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER,
                    new CardSelectResponse());

            // Writing through CC-ACCT-ID-N PIC 9(11), not CC-ACCT-ID PIC X(11): "00000000042", never
            // "42         ".
            assertThat(task.ccWorkArea.getCcAcctId()).isEqualTo("00000000042");
            assertThat(task.ccWorkArea.getCcCardNum()).isEqualTo("0000000000000007");
        }

        @Test
        @DisplayName("ENTER from ANYWHERE ELSE only paints the screen - the bare arm, reachable by order")
        void bareEnterOnlyPaintsTheScreen() {
            CardSelectResponse response = new CardSelectResponse();
            NavigationContext commarea = NavigationContext.empty()
                    .withFromProgram(CardSelectControllerAccess.MENU_PGM)
                    .withPgmReenter()   // so the commarea is kept, then ENTER is what dispatches
                    .withAcctId(11L);

            // Re-entry from the menu keeps the commarea; the AID decides the arm.
            Conversation task = runMain(request(CARD_NUMBER, ACCOUNT_ID,
                    commarea.withPgmEnter().withFromProgram("COBIL00C")),
                    CardSelectController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER, response);

            // No read at all: this arm gathers criteria rather than using them (gate G30 - the bare arm
            // is only reachable because the qualified arm was tested first).
            verifyNoInteractions(repository);
            assertThat(task.foundCardsForAccount()).isFalse();
            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01);
        }

        @Test
        @DisplayName("the ordering proof: the SAME state differs only by FROM-PROGRAM = COCRDLIC")
        void branchOrderingIsWhatDistinguishesTheTwoEnterArms() {
            when(repository.readByCardNumber(anyString()))
                    .thenReturn(CardReadResult.normal(card()));

            NavigationContext withList = NavigationContext.empty()
                    .withFromProgram(CardSelectControllerAccess.CCLIST_PGM)
                    .withPgmEnter().withAcctId(11L).withCardNum(Long.parseLong(CARD_NUMBER));
            NavigationContext withoutList = withList.withFromProgram("COBIL00C");

            runMain(request("", "", withList), CardSelectController.PASSED_COMMAREA_LENGTH,
                    CicsAid.DFHENTER, new CardSelectResponse());
            verify(repository).readByCardNumber(CARD_NUMBER);

            CardRepository second = mock(CardRepository.class);
            new CardSelectController(second, FIXED_CLOCK, CHARSET)
                    .main0000(request("", "", withoutList), new CardSelectResponse(),
                            new Conversation(), CardSelectController.PASSED_COMMAREA_LENGTH,
                            CicsAid.DFHENTER);
            verify(second, never()).readByCardNumber(anyString());
        }

        @Test
        @DisplayName("REENTER with valid input edits, then reads")
        void reenterWithValidInputReads() {
            when(repository.readByCardNumber(anyString()))
                    .thenReturn(CardReadResult.normal(card()));
            CardSelectResponse response = new CardSelectResponse();
            NavigationContext commarea = NavigationContext.empty()
                    .withFromProgram("COBIL00C").withPgmReenter();

            Conversation task = runMain(request(CARD_NUMBER, ACCOUNT_ID, commarea),
                    CardSelectController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER, response);

            assertThat(task.inputError()).isFalse();
            assertThat(task.flgAcctfilterIsvalid()).isTrue();
            assertThat(task.flgCardfilterIsvalid()).isTrue();
            verify(repository).readByCardNumber(CARD_NUMBER);
            assertThat(task.foundCardsForAccount()).isTrue();
        }

        @Test
        @DisplayName("REENTER with a rejected filter redisplays and NEVER touches the file")
        void reenterWithABadFilterDoesNotRead() {
            CardSelectResponse response = new CardSelectResponse();
            NavigationContext commarea = NavigationContext.empty()
                    .withFromProgram("COBIL00C").withPgmReenter();

            Conversation task = runMain(request("", "", commarea),
                    CardSelectController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER, response);

            assertThat(task.inputError()).isTrue();
            verifyNoInteractions(repository);
            // Both blank, so the unguarded cross-field rule wins.
            assertThat(task.wsReturnMsg).isEqualTo(CardSelectController.NO_SEARCH_CRITERIA_RECEIVED);
            assertThat(response.getErrmsgo().strip()).isEqualTo("No input received");
        }

        @Test
        @DisplayName("WHEN OTHER puts '0001' in ABEND-CODE and the text in the RETURN message")
        void whenOtherUsesTheReturnMessageNotTheAbendMessage() {
            CardSelectResponse response = new CardSelectResponse();
            // A context that is neither ENTER (0) nor REENTER (1).
            NavigationContext commarea = NavigationContext.empty()
                    .withFromProgram("COBIL00C").withPgmContext(7);

            Conversation task = runMain(request(CARD_NUMBER, ACCOUNT_ID, commarea),
                    CardSelectController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER, response);

            // :374-376
            assertThat(task.abendData.abendCulprit().strip())
                    .isEqualTo(CardSelectControllerAccess.THIS_PGM);
            assertThat(task.abendData.abendCode()).isEqualTo("0001");
            assertThat(task.abendData.abendReason()).isBlank();
            // :377-378 - the text goes to WS-RETURN-MSG, NOT to ABEND-MSG. COCRDUPC differs.
            assertThat(task.wsReturnMsg).isEqualTo(CardSelectController.UNEXPECTED_DATA_SCENARIO);
            assertThat(task.abendData.abendMsg()).isBlank();
            assertThat(response.getErrmsgo().strip()).isEqualTo("UNEXPECTED DATA SCENARIO");
            // SEND TEXT paints no map, and the RETURN carries no commarea.
            assertThat(response.getTitle01o()).isEqualTo(CardScreenState.lowValues(40));
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("an invalid key on an otherwise-unknown context still lands on WHEN OTHER")
        void coercionDoesNotRescueAnUnknownContext() {
            CardSelectResponse response = new CardSelectResponse();
            NavigationContext commarea = NavigationContext.empty().withPgmContext(9);

            Conversation task = runMain(request(CARD_NUMBER, ACCOUNT_ID, commarea),
                    CardSelectController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF12, response);

            // PF12 was coerced to ENTER, so the PF3 arm is not taken; the context matches no other arm.
            assertThat(task.ccWorkArea.isCcardAidEnter()).isTrue();
            assertThat(task.wsReturnMsg).isEqualTo(CardSelectController.UNEXPECTED_DATA_SCENARIO);
        }
    }

    @Nested
    @DisplayName("2200-EDIT-MAP-INPUTS and the two field edits (app/cbl/COCRDSLC.cbl:608-720)")
    class Edits {

        private Conversation editWith(String acctsid, String cardsid, NavigationContext commarea) {
            Conversation task = initialisedTask(commarea);
            task.wsReturnMsg = CardSelectController.WS_RETURN_MSG_OFF;
            controller.editMapInputs2200(request(cardsid, acctsid, commarea), task);
            return task;
        }

        @Test
        @DisplayName("a valid pair leaves both filters ISVALID and stores both in the commarea")
        void bothValid() {
            Conversation task = editWith(ACCOUNT_ID, CARD_NUMBER, NavigationContext.empty());

            assertThat(task.inputOk()).isTrue();
            assertThat(task.flgAcctfilterIsvalid()).isTrue();
            assertThat(task.flgCardfilterIsvalid()).isTrue();
            assertThat(task.carddemoCommarea.acctId()).isEqualTo(11L);
            assertThat(task.carddemoCommarea.cardNum()).isEqualTo(Long.parseLong(CARD_NUMBER));
            assertThat(task.returnMessageOff()).isTrue();
        }

        @ParameterizedTest(name = "an account filter of \"{0}\" reads as BLANK")
        @ValueSource(strings = {"", " ", "*", "00000000000"})
        @DisplayName("SPACES, an asterisk and eleven zeros all mean 'not supplied'")
        void allThreeEmptyFormsAreBlank(String acctsid) {
            Conversation task = editWith(acctsid, CARD_NUMBER, NavigationContext.empty());

            assertThat(task.inputError()).isTrue();
            assertThat(task.flgAcctfilterBlank()).isTrue();
            assertThat(task.wsReturnMsg).isEqualTo(CardSelectController.WS_PROMPT_FOR_ACCT);
            assertThat(task.carddemoCommarea.acctId()).isZero();
        }

        @Test
        @DisplayName("the asterisk test is against a PADDED asterisk, so \"*1\" is a real value")
        void theAsteriskTestIsPadded() {
            assertThat(CardSelectController.isAsteriskOrSpaces("*", 11)).isTrue();
            assertThat(CardSelectController.isAsteriskOrSpaces("*          ", 11)).isTrue();
            assertThat(CardSelectController.isAsteriskOrSpaces("           ", 11)).isTrue();
            assertThat(CardSelectController.isAsteriskOrSpaces("*1", 11)).isFalse();
            assertThat(CardSelectController.isAsteriskOrSpaces("00000000011", 11)).isFalse();
        }

        @Test
        @DisplayName("a non-numeric account filter reports its own 52-character message")
        void nonNumericAccount() {
            Conversation task = editWith("ABCDEFGHIJK", CARD_NUMBER, NavigationContext.empty());

            assertThat(task.inputError()).isTrue();
            assertThat(task.flgAcctfilterNotOk()).isTrue();
            assertThat(task.flgAcctfilterBlank()).isFalse();
            assertThat(task.wsReturnMsg.strip())
                    .isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
            assertThat(task.carddemoCommarea.acctId()).isZero();
        }

        @Test
        @DisplayName("a non-numeric card filter reports its own 52-character message")
        void nonNumericCard() {
            Conversation task = editWith(ACCOUNT_ID, "ABCDEFGHIJKLMNOP", NavigationContext.empty());

            assertThat(task.inputError()).isTrue();
            assertThat(task.flgCardfilterNotOk()).isTrue();
            assertThat(task.wsReturnMsg.strip())
                    .isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
            assertThat(task.carddemoCommarea.cardNum()).isZero();
        }

        @Test
        @DisplayName("a blank card alone reports the CARD prompt")
        void blankCardAlone() {
            Conversation task = editWith(ACCOUNT_ID, "", NavigationContext.empty());

            assertThat(task.flgCardfilterBlank()).isTrue();
            assertThat(task.flgAcctfilterIsvalid()).isTrue();
            assertThat(task.wsReturnMsg).isEqualTo(CardSelectController.WS_PROMPT_FOR_CARD);
        }

        @Test
        @DisplayName("the account edit runs FIRST, so its message survives the card edit's guard")
        void theFirstMessageWinsBecauseOfTheGuard() {
            Conversation task = initialisedTask(NavigationContext.empty());
            task.wsReturnMsg = CardSelectController.WS_RETURN_MSG_OFF;

            controller.editAccount2210(task);
            String afterAccount = task.wsReturnMsg;
            controller.editCard2220(task);

            // IF WS-RETURN-MSG-OFF at :696 sees a message already placed, so it leaves it.
            assertThat(afterAccount).isEqualTo(CardSelectController.WS_PROMPT_FOR_ACCT);
            assertThat(task.wsReturnMsg).isEqualTo(CardSelectController.WS_PROMPT_FOR_ACCT);
            // ...but INPUT-ERROR and the flag were still set unconditionally.
            assertThat(task.inputError()).isTrue();
            assertThat(task.flgCardfilterBlank()).isTrue();
        }

        @Test
        @DisplayName("the cross-field rule is UNGUARDED and overwrites whichever prompt was standing")
        void theCrossFieldRuleOverwrites() {
            Conversation task = editWith("", "", NavigationContext.empty());

            // :637-640 has no IF WS-RETURN-MSG-OFF, so it replaces the account prompt.
            assertThat(task.wsReturnMsg).isEqualTo(CardSelectController.NO_SEARCH_CRITERIA_RECEIVED);
        }

        @Test
        @DisplayName("SIXTEEN ZEROS is 'not supplied' for the card filter, through the PIC 9 view")
        void sixteenZerosIsBlank() {
            // :693 - the third arm of the not-supplied test, CC-CARD-NUM-N EQUAL ZEROS, reading the same
            // sixteen bytes as PIC 9(16). A supplied but meaningless card number.
            Conversation task = editWith(ACCOUNT_ID, "0000000000000000", NavigationContext.empty());

            assertThat(task.inputError()).isTrue();
            assertThat(task.flgCardfilterBlank()).isTrue();
            assertThat(task.wsReturnMsg).isEqualTo(CardSelectController.WS_PROMPT_FOR_CARD);
            assertThat(task.carddemoCommarea.cardNum()).isZero();
        }

        @Test
        @DisplayName("a NON-NUMERIC account with a message already standing keeps it, flag set regardless")
        void nonNumericAccountWithAMessageAlreadySet() {
            // :668-672 - the guard covers only the message. INPUT-ERROR and FLG-ACCTFILTER-NOT-OK are
            // set unconditionally, so the failure is recorded even though the text does not change.
            Conversation task = initialisedTask(NavigationContext.empty());
            task.ccWorkArea.setCcAcctId("ABCDEFGHIJK");
            task.wsReturnMsg = CardSelectController.NO_SEARCH_CRITERIA_RECEIVED;

            controller.editAccount2210(task);

            assertThat(task.wsReturnMsg).isEqualTo(CardSelectController.NO_SEARCH_CRITERIA_RECEIVED);
            assertThat(task.inputError()).isTrue();
            assertThat(task.flgAcctfilterNotOk()).isTrue();
        }

        @Test
        @DisplayName("a BLANK account with a message already standing keeps it, flag set regardless")
        void blankAccountWithAMessageAlreadySet() {
            // :656-658 - the same nesting on the other arm of 2210.
            Conversation task = initialisedTask(NavigationContext.empty());
            task.ccWorkArea.setCcAcctIdToLowValues();
            task.wsReturnMsg = CardSelectController.XREF_READ_ERROR;

            controller.editAccount2210(task);

            assertThat(task.wsReturnMsg).isEqualTo(CardSelectController.XREF_READ_ERROR);
            assertThat(task.inputError()).isTrue();
            assertThat(task.flgAcctfilterBlank()).isTrue();
        }

        @Test
        @DisplayName("a NON-NUMERIC card with a message already standing keeps it, flag set regardless")
        void nonNumericCardWithAMessageAlreadySet() {
            // :709-713 - 2220's equivalent guard.
            Conversation task = initialisedTask(NavigationContext.empty());
            task.ccWorkArea.setCcCardNum("ABCDEFGHIJKLMNOP");
            task.wsReturnMsg = CardSelectController.WS_PROMPT_FOR_ACCT;

            controller.editCard2220(task);

            assertThat(task.wsReturnMsg).isEqualTo(CardSelectController.WS_PROMPT_FOR_ACCT);
            assertThat(task.inputError()).isTrue();
            assertThat(task.flgCardfilterNotOk()).isTrue();
        }

        @Test
        @DisplayName("2210 moves the X view and 2220 moves the NUMERIC view - the source asymmetry")
        void theTwoEditsUseDifferentViews() {
            Conversation task = editWith("00000000042", "0000000000000007",
                    NavigationContext.empty());

            // Both reach a numeric receiver, by two different routes (:676 vs :717).
            assertThat(task.carddemoCommarea.acctId()).isEqualTo(42L);
            assertThat(task.carddemoCommarea.cardNum()).isEqualTo(7L);
        }

        @Test
        @DisplayName("2000-PROCESS-INPUTS nominates THIS screen as the next target, error or not")
        void processInputsAlwaysNominatesThisScreen() {
            Conversation task = initialisedTask(NavigationContext.empty());

            controller.processInputs2000(request("", "", NavigationContext.empty()), task);

            assertThat(task.ccWorkArea.getCcardNextProg()).isEqualTo("COCRDSLC");
            // :589 - X(8) into X(7) drops the trailing space.
            assertThat(task.ccWorkArea.getCcardNextMapset()).isEqualTo("COCRDSL").hasSize(7);
            assertThat(task.ccWorkArea.getCcardNextMap()).isEqualTo("CCRDSLA").hasSize(7);
            assertThat(task.ccWorkArea.getCcardErrorMsg()).hasSize(75);
            // 2100-RECEIVE-MAP records a NORMAL response and no reason code.
            assertThat(task.wsRespCd).isEqualTo(FileStatus.NORMAL);
            assertThat(task.wsReasCd).isEqualTo(CardRepository.NO_REASON_CODE);
        }
    }

    @Nested
    @DisplayName("9100-GETCARD-BYACCTCARD - the base read (app/cbl/COCRDSLC.cbl:736-773)")
    class BaseRead {

        private Conversation readWith(CardReadResult result, String priorMessage) {
            when(repository.readByCardNumber(anyString())).thenReturn(result);
            Conversation task = initialisedTask(NavigationContext.empty());
            task.ccWorkArea.setCcCardNum(CARD_NUMBER);
            task.wsReturnMsg = priorMessage;
            controller.readData9000(task);
            return task;
        }

        @Test
        @DisplayName("the key is the CARD NUMBER alone - the account MOVE is commented out at :739")
        void theKeyIsTheCardNumberOnly() {
            Conversation task = readWith(CardReadResult.normal(card()),
                    CardSelectController.WS_RETURN_MSG_OFF);

            assertThat(task.wsCardRidCardnum).isEqualTo(CARD_NUMBER);
            // The account key was never assigned, so it still holds INITIALIZE's eleven zeros.
            assertThat(task.wsCardRidAcctId).isEqualTo("00000000000");
            verify(repository).readByCardNumber(CARD_NUMBER);
        }

        @Test
        @DisplayName("NORMAL sets FOUND-CARDS-FOR-ACCOUNT, which is a message doubling as a flag")
        void normal() {
            Conversation task = readWith(CardReadResult.normal(card()),
                    CardSelectController.WS_RETURN_MSG_OFF);

            assertThat(task.foundCardsForAccount()).isTrue();
            assertThat(task.wsInfoMsg).isEqualTo(CardSelectController.FOUND_CARDS_FOR_ACCOUNT);
            assertThat(task.inputError()).isFalse();
            assertThat(task.cardRecord).contains(card());
        }

        @Test
        @DisplayName("NOTFND with NO earlier message sets the combination message and both flags")
        void notFoundWithNoEarlierMessage() {
            Conversation task = readWith(CardReadResult.notFound(),
                    CardSelectController.WS_RETURN_MSG_OFF);

            assertThat(task.inputError()).isTrue();
            assertThat(task.flgAcctfilterNotOk()).isTrue();
            assertThat(task.flgCardfilterNotOk()).isTrue();
            assertThat(task.wsReturnMsg).isEqualTo(CardSelectController.DID_NOT_FIND_ACCTCARD_COMBO);
            assertThat(task.cardRecord).isEmpty();
        }

        @Test
        @DisplayName("NOTFND with an earlier message KEEPS it, but still sets INPUT-ERROR and both flags")
        void notFoundWithAnEarlierMessage() {
            Conversation task = readWith(CardReadResult.notFound(),
                    CardSelectController.WS_PROMPT_FOR_ACCT);

            // The IF WS-RETURN-MSG-OFF nesting at :759-761: the message is guarded, the flags are not.
            assertThat(task.wsReturnMsg).isEqualTo(CardSelectController.WS_PROMPT_FOR_ACCT);
            assertThat(task.inputError()).isTrue();
            assertThat(task.flgAcctfilterNotOk()).isTrue();
            assertThat(task.flgCardfilterNotOk()).isTrue();
        }

        @Test
        @DisplayName("WHEN OTHER guards the FLAG and not the MESSAGE - the reverse of the NOTFND arm")
        void whenOtherGuardsTheFlagNotTheMessage() {
            Conversation withPrior = readWith(CardReadResult.failed(FileStatus.INVREQ),
                    CardSelectController.WS_PROMPT_FOR_ACCT);

            // :764-766 guards FLG-ACCTFILTER-NOT-OK, so with a message already set the flag is NOT set...
            assertThat(withPrior.flgAcctfilterNotOk()).isFalse();
            assertThat(withPrior.flgAcctfilterBlank()).isTrue();
            // ...while :767-771 is unguarded, so the file-error message DOES replace the prompt.
            assertThat(withPrior.wsReturnMsg).startsWith("File Error: READ");
            assertThat(withPrior.inputError()).isTrue();
        }

        @Test
        @DisplayName("WHEN OTHER with no earlier message sets the flag too")
        void whenOtherWithNoEarlierMessage() {
            Conversation task = readWith(CardReadResult.failed(FileStatus.INVREQ),
                    CardSelectController.WS_RETURN_MSG_OFF);

            assertThat(task.flgAcctfilterNotOk()).isTrue();
            assertThat(task.inputError()).isTrue();
        }

        @Test
        @DisplayName("END-OF-FILE and DUPLICATE both fall to WHEN OTHER, as the COBOL enumerates neither")
        void theOtherTwoStatusesFallThrough() {
            Conversation eof = readWith(CardReadResult.endOfFile(),
                    CardSelectController.WS_RETURN_MSG_OFF);
            assertThat(eof.wsReturnMsg).startsWith("File Error: READ");
            assertThat(FileStatus.outcomeOfCicsResp(eof.wsRespCd))
                    .isEqualTo(FileStatus.Outcome.END_OF_FILE);

            setUp();
            Conversation duplicate = readWith(CardReadResult.duplicateKey(card()),
                    CardSelectController.WS_RETURN_MSG_OFF);
            assertThat(duplicate.wsReturnMsg).startsWith("File Error: READ");
            assertThat(FileStatus.outcomeOfCicsResp(duplicate.wsRespCd))
                    .isEqualTo(FileStatus.Outcome.DUPLICATE);
        }

        @Test
        @DisplayName("the 80-character file-error message is composed character for character")
        void theFileErrorMessageIsExact() {
            Conversation task = initialisedTask(NavigationContext.empty());
            task.wsRespCd = FileStatus.NOTFND;
            task.wsReasCd = 0;

            controller.recordFileError(task, CardSelectController.LIT_CARDFILENAME);

            // 'File Error: '(12) + 'READ    '(8) + ' on '(4) + 'CARDDAT  '(9)
            //   + ' returned RESP '(15) + '000000013 '(10) + ',RESP2 '(7) + '000000000 '(10) + 5 spaces
            String expected80 = "File Error: " + "READ    " + " on " + "CARDDAT  "
                    + " returned RESP " + "000000013 " + ",RESP2 " + "000000000 " + "     ";
            assertThat(expected80).hasSize(80);
            assertThat(controller.fileErrorMessage(task)).isEqualTo(expected80);
            // The MOVE into WS-RETURN-MSG X(75) discards the trailing five.
            assertThat(task.wsReturnMsg).hasSize(75).isEqualTo(expected80.substring(0, 75));
            assertThat(task.errorOpname).isEqualTo("READ    ");
            assertThat(task.errorFile).isEqualTo("CARDDAT  ");
        }

        @ParameterizedTest(name = "RESP {0} renders as \"{1}\"")
        @CsvSource({"0,'000000000 '", "13,'000000013 '", "16,'000000016 '", "999999999,'999999999 '"})
        @DisplayName("a binary RESP renders as nine digits then ONE trailing space, never ten digits")
        void responseCodeImageGeometry(int resp, String expected) {
            assertThat(CardSelectController.responseCodeImage(resp)).isEqualTo(expected).hasSize(10);
        }

        @Test
        @DisplayName("a negative response code cannot make the message throw")
        void negativeResponseCodesStillRender() {
            assertThat(CardSelectController.responseCodeImage(-13)).isEqualTo("000000013 ");
            assertThat(CardSelectController.responseCodeImage(Integer.MIN_VALUE)).hasSize(10);
        }
    }

    @Nested
    @DisplayName("9150-GETCARD-BYACCT - dead code, translated and proven (COCRDSLC:779-809)")
    class AlternateIndexRead {

        @Test
        @DisplayName("9000-READ-DATA does NOT call it: the source performs 9100 only")
        void readDataDoesNotReachIt() {
            when(repository.readByCardNumber(anyString()))
                    .thenReturn(CardReadResult.normal(card()));
            Conversation task = initialisedTask(NavigationContext.empty());
            task.ccWorkArea.setCcCardNum(CARD_NUMBER);

            controller.readData9000(task);

            verify(repository).readByCardNumber(CARD_NUMBER);
            verify(repository, never()).readByAccountIdViaAltIndex(anyString());
        }

        @Test
        @DisplayName("NORMAL sets FOUND-CARDS-FOR-ACCOUNT, as in 9100")
        void normal() {
            when(repository.readByAccountIdViaAltIndex(anyString()))
                    .thenReturn(CardReadResult.normal(card()));
            Conversation task = initialisedTask(NavigationContext.empty());

            controller.getCardByAcct9150(task);

            assertThat(task.foundCardsForAccount()).isTrue();
            // The key is INITIALIZE's eleven zeros, because :739's MOVE is commented out.
            verify(repository).readByAccountIdViaAltIndex("00000000000");
        }

        @Test
        @DisplayName("NOTFND sets its message with NO WS-RETURN-MSG-OFF guard - unlike 9100")
        void notFoundIsUnguarded() {
            when(repository.readByAccountIdViaAltIndex(anyString()))
                    .thenReturn(CardReadResult.notFound());
            Conversation task = initialisedTask(NavigationContext.empty());
            // An earlier message is standing, which in 9100 would have been preserved.
            task.wsReturnMsg = CardSelectController.WS_PROMPT_FOR_ACCT;

            controller.getCardByAcct9150(task);

            assertThat(task.wsReturnMsg)
                    .isEqualTo(CardSelectController.DID_NOT_FIND_ACCT_IN_CARDXREF);
            assertThat(task.inputError()).isTrue();
            assertThat(task.flgAcctfilterNotOk()).isTrue();
        }

        @Test
        @DisplayName("WHEN OTHER names the CARDAIX path in ERROR-FILE, also unguarded")
        void whenOtherNamesTheAlternateIndexPath() {
            when(repository.readByAccountIdViaAltIndex(anyString()))
                    .thenReturn(CardReadResult.failed(FileStatus.NOTOPEN));
            Conversation task = initialisedTask(NavigationContext.empty());
            task.wsReturnMsg = CardSelectController.WS_PROMPT_FOR_ACCT;

            controller.getCardByAcct9150(task);

            assertThat(task.errorFile).isEqualTo("CARDAIX  ");
            assertThat(task.wsReturnMsg).startsWith("File Error: READ     on CARDAIX  ");
            assertThat(task.flgAcctfilterNotOk()).isTrue();
        }
    }

    @Nested
    @DisplayName("The painted screen (app/cbl/COCRDSLC.cbl:427-577)")
    class Screen {

        @Test
        @DisplayName("1100-SCREEN-INIT blanks the group with LOW-VALUES, then writes the header")
        void screenInit() {
            CardSelectResponse response = new CardSelectResponse();
            // A stale value at the item's declared width, so 1100's blanking is visibly what removed it.
            response.setCrdnameo("STALE VALUE FROM AN EARLIER TURN".repeat(2)
                    .substring(0, CardSelectResponse.CRDNAMEO_LENGTH));
            Conversation task = initialisedTask(NavigationContext.empty());

            controller.screenInit1100(response, task);

            assertThat(response.getCrdnameo()).isEqualTo(CardScreenState.lowValues(50));
            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(response.getTrnnameo()).isEqualTo("CCDL");
            assertThat(response.getPgmnameo()).isEqualTo("COCRDSLC");
            // The fixed clock, formatted mm/dd/yy and hh:mm:ss by CSDAT01Y's own compositions.
            assertThat(response.getCurdateo()).hasSize(8).isEqualTo("07/19/22");
            assertThat(response.getCurtimeo()).hasSize(8).isEqualTo("23:12:33");
            assertThat(task.dateHeader).isNotNull();
        }

        @Test
        @DisplayName("EIBCALEN = 0 shows the prompt and writes NEITHER criterion")
        void coldStartShowsOnlyThePrompt() {
            CardSelectResponse response = new CardSelectResponse();
            Conversation task = initialisedTask(NavigationContext.empty());
            controller.screenInit1100(response, task);

            controller.setupScreenVars1200(response, task, CardSelectController.NO_COMMAREA_LENGTH);

            assertThat(task.promptForInput()).isTrue();
            assertThat(response.getInfomsgo()).isEqualTo(CardSelectController.WS_PROMPT_FOR_INPUT);
            // Neither ACCTSIDO nor CARDSIDO was written, so both stay LOW-VALUES - not blank.
            assertThat(response.getAcctsido()).isEqualTo(CardScreenState.lowValues(11));
            assertThat(response.getCardsido()).isEqualTo(CardScreenState.lowValues(16));
        }

        @Test
        @DisplayName("a zero commarea id writes LOW-VALUES; a non-zero one writes the WORK AREA value")
        void theTestReadsTheCommareaAndTheValueComesFromTheWorkArea() {
            CardSelectResponse response = new CardSelectResponse();
            Conversation task = initialisedTask(NavigationContext.empty()
                    .withAcctId(11L).withCardNum(0L));
            task.ccWorkArea.setCcAcctId(ACCOUNT_ID);
            task.ccWorkArea.setCcCardNum(CARD_NUMBER);
            controller.screenInit1100(response, task);

            controller.setupScreenVars1200(response, task,
                    CardSelectController.PASSED_COMMAREA_LENGTH);

            // CDEMO-ACCT-ID is non-zero, so CC-ACCT-ID is shown.
            assertThat(response.getAcctsido()).isEqualTo(ACCOUNT_ID);
            // CDEMO-CARD-NUM is zero, so CARDSIDO is blanked - even though CC-CARD-NUM holds a value.
            assertThat(response.getCardsido()).isEqualTo(CardScreenState.lowValues(16));
        }

        @Test
        @DisplayName("the card projection is keyed on the MESSAGE flag, not on the record's presence")
        void theProjectionIsKeyedOnTheMessage() {
            CardSelectResponse response = new CardSelectResponse();
            Conversation task = initialisedTask(NavigationContext.empty().withAcctId(11L));
            task.cardRecord = Optional.of(card());
            controller.screenInit1100(response, task);

            // The record is present but FOUND-CARDS-FOR-ACCOUNT was never set, so nothing is projected.
            controller.setupScreenVars1200(response, task,
                    CardSelectController.PASSED_COMMAREA_LENGTH);
            assertThat(response.getCrdnameo()).isEqualTo(CardScreenState.lowValues(50));

            // Set the message and it is.
            task.wsInfoMsg = CardSelectController.FOUND_CARDS_FOR_ACCOUNT;
            controller.setupScreenVars1200(response, task,
                    CardSelectController.PASSED_COMMAREA_LENGTH);
            assertThat(response.getCrdnameo().strip()).isEqualTo("JOHN Q PUBLIC");
        }

        @Test
        @DisplayName("the flag set but no record present leaves the four fields as 1100 blanked them")
        void theFlagWithoutARecordProjectsNothing() {
            CardSelectResponse response = new CardSelectResponse();
            Conversation task = initialisedTask(NavigationContext.empty().withAcctId(11L));
            task.wsInfoMsg = CardSelectController.FOUND_CARDS_FOR_ACCOUNT;
            task.cardRecord = Optional.empty();
            controller.screenInit1100(response, task);

            controller.setupScreenVars1200(response, task,
                    CardSelectController.PASSED_COMMAREA_LENGTH);

            assertThat(response.getCrdstcdo()).isEqualTo(CardScreenState.lowValues(1));
            assertThat(response.getExpyearo()).isEqualTo(CardScreenState.lowValues(4));
        }

        @Test
        @DisplayName("the expiry splits YYYY-MM-DD into EXPYEAR [0,4) and EXPMON [5,7), with no EXPDAY")
        void theExpirySplit() {
            CardSelectResponse response = new CardSelectResponse();
            Conversation task = initialisedTask(NavigationContext.empty());
            task.cardRecord = Optional.of(card());

            controller.projectCardRecord1200(response, task);

            assertThat(response.getExpyearo()).isEqualTo("2026");
            assertThat(response.getExpmono()).isEqualTo("04");
            // The REDEFINES also names a day, which this map has no field for - and none is invented.
            assertThat(task.cardExpiraionDateX).isEqualTo("2026-04-30");
            assertThat(card().cardExpiraionDateDay()).isEqualTo("30");
        }

        @Test
        @DisplayName("WS-RETURN-MSG (75) reaches ERRMSGO (80) padded, and WS-INFO-MSG (40) unchanged")
        void theTwoMessageWidths() {
            CardSelectResponse response = new CardSelectResponse();
            Conversation task = initialisedTask(NavigationContext.empty());
            task.wsReturnMsg = CardSelectController.WS_PROMPT_FOR_ACCT;
            task.wsInfoMsg = CardSelectController.FOUND_CARDS_FOR_ACCOUNT;

            controller.setupScreenVars1200(response, task,
                    CardSelectController.PASSED_COMMAREA_LENGTH);

            assertThat(response.getErrmsgo()).hasSize(80)
                    .isEqualTo(CardSelectController.WS_PROMPT_FOR_ACCT + "     ");
            assertThat(response.getInfomsgo()).hasSize(40)
                    .isEqualTo(CardSelectController.FOUND_CARDS_FOR_ACCOUNT);
        }

        @Test
        @DisplayName("arriving from the card list PROTECTS both criteria and defaults their colour")
        void arrivingFromTheListProtectsTheCriteria() {
            CardSelectRequest request = request(CARD_NUMBER, ACCOUNT_ID, NavigationContext.empty());
            CardSelectResponse response = new CardSelectResponse();
            Conversation task = initialisedTask(NavigationContext.empty()
                    .withLastMapset(CardSelectControllerAccess.CCLIST_MAPSET)
                    .withFromProgram(CardSelectControllerAccess.CCLIST_PGM));
            task.wsEditAcctFlag = CardSelectController.FLG_FILTER_ISVALID;
            task.wsEditCardFlag = CardSelectController.FLG_FILTER_ISVALID;

            controller.setupScreenAttrs1300(request, response, task);

            assertThat(request.metadata(CardSelectRequest.ScreenField.ACCTSID).getAttribute())
                    .isEqualTo(BmsAttributes.DFHBMPRF);
            assertThat(request.metadata(CardSelectRequest.ScreenField.CARDSID).getAttribute())
                    .isEqualTo(BmsAttributes.DFHBMPRF);
            assertThat(response.attributes(CardSelectResponse.ScreenField.ACCTSID).getColour())
                    .isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(BmsAttributes.isProtected(BmsAttributes.DFHBMPRF)).isTrue();
        }

        @Test
        @DisplayName("arriving from anywhere else UNPROTECTS both and leaves their colour untouched")
        void arrivingFromElsewhereUnprotectsThem() {
            CardSelectRequest request = request(CARD_NUMBER, ACCOUNT_ID, NavigationContext.empty());
            CardSelectResponse response = new CardSelectResponse();
            Conversation task = initialisedTask(
                    NavigationContext.empty().withFromProgram("COBIL00C"));
            task.wsEditAcctFlag = CardSelectController.FLG_FILTER_ISVALID;
            task.wsEditCardFlag = CardSelectController.FLG_FILTER_ISVALID;

            controller.setupScreenAttrs1300(request, response, task);

            assertThat(request.metadata(CardSelectRequest.ScreenField.ACCTSID).getAttribute())
                    .isEqualTo(BmsAttributes.DFHBMFSE);
            // No ELSE on the colour block, so it stays as MOVE LOW-VALUES left it.
            assertThat(response.attributes(CardSelectResponse.ScreenField.ACCTSID).getColour())
                    .isEqualTo((byte) 0x00);
        }

        @Test
        @DisplayName("NOT-OK paints DFHRED with NO re-entry guard - where CSSETATY would paint nothing")
        void notOkIsColouredEvenOnFirstEntry() {
            CardSelectRequest request = request(CARD_NUMBER, ACCOUNT_ID, NavigationContext.empty());
            CardSelectResponse response = new CardSelectResponse();
            // ENTER, not REENTER: the guard CSSETATY applies is absent here (:533-539).
            Conversation task = initialisedTask(
                    NavigationContext.empty().withFromProgram("COBIL00C").withPgmEnter());
            task.wsEditAcctFlag = CardSelectController.FLG_FILTER_NOT_OK;
            task.wsEditCardFlag = CardSelectController.FLG_FILTER_NOT_OK;

            controller.setupScreenAttrs1300(request, response, task);

            assertThat(response.attributes(CardSelectResponse.ScreenField.ACCTSID).getColour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.attributes(CardSelectResponse.ScreenField.CARDSID).getColour())
                    .isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("BLANK marks the field with '*' ONLY on re-entry (gate G38)")
        void blankIsMarkedOnlyOnReentry() {
            CardSelectRequest request = request(CARD_NUMBER, ACCOUNT_ID, NavigationContext.empty());

            // ENTER: nothing is marked and nothing is coloured.
            CardSelectResponse onEnter = new CardSelectResponse();
            Conversation enterTask = initialisedTask(
                    NavigationContext.empty().withFromProgram("COBIL00C").withPgmEnter());
            enterTask.wsEditAcctFlag = CardSelectController.FLG_FILTER_BLANK;
            enterTask.wsEditCardFlag = CardSelectController.FLG_FILTER_BLANK;
            controller.setupScreenAttrs1300(request, onEnter, enterTask);
            assertThat(onEnter.getAcctsido()).isEqualTo(CardScreenState.lowValues(11));
            assertThat(onEnter.attributes(CardSelectResponse.ScreenField.ACCTSID).getColour())
                    .isEqualTo((byte) 0x00);

            // REENTER: both are marked and both go red.
            CardSelectResponse onReenter = new CardSelectResponse();
            Conversation reenterTask = initialisedTask(
                    NavigationContext.empty().withFromProgram("COBIL00C").withPgmReenter());
            reenterTask.wsEditAcctFlag = CardSelectController.FLG_FILTER_BLANK;
            reenterTask.wsEditCardFlag = CardSelectController.FLG_FILTER_BLANK;
            controller.setupScreenAttrs1300(request, onReenter, reenterTask);
            assertThat(onReenter.getAcctsido()).isEqualTo("*          ");
            assertThat(onReenter.getCardsido()).isEqualTo("*               ");
            assertThat(onReenter.attributes(CardSelectResponse.ScreenField.ACCTSID).getColour())
                    .isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("the cursor lands on ACCTSID for both of its states, on CARDSID next, and defaults")
        void cursorPositioning() {
            CardSelectResponse response = new CardSelectResponse();
            Conversation task = initialisedTask(
                    NavigationContext.empty().withFromProgram("COBIL00C"));

            // Arm 1 - the account filter is not ok
            CardSelectRequest onAcct = request(CARD_NUMBER, ACCOUNT_ID, NavigationContext.empty());
            task.wsEditAcctFlag = CardSelectController.FLG_FILTER_NOT_OK;
            task.wsEditCardFlag = CardSelectController.FLG_FILTER_ISVALID;
            controller.setupScreenAttrs1300(onAcct, response, task);
            assertThat(onAcct.metadata(CardSelectRequest.ScreenField.ACCTSID).isCursorHere()).isTrue();

            // Arm 1 again - the account filter is blank
            CardSelectRequest onBlankAcct =
                    request(CARD_NUMBER, ACCOUNT_ID, NavigationContext.empty());
            task.wsEditAcctFlag = CardSelectController.FLG_FILTER_BLANK;
            controller.setupScreenAttrs1300(onBlankAcct, response, task);
            assertThat(onBlankAcct.metadata(CardSelectRequest.ScreenField.ACCTSID).isCursorHere())
                    .isTrue();

            // Arm 2 - the account filter is fine, the card filter is not
            CardSelectRequest onCard = request(CARD_NUMBER, ACCOUNT_ID, NavigationContext.empty());
            task.wsEditAcctFlag = CardSelectController.FLG_FILTER_ISVALID;
            task.wsEditCardFlag = CardSelectController.FLG_FILTER_NOT_OK;
            controller.setupScreenAttrs1300(onCard, response, task);
            assertThat(onCard.metadata(CardSelectRequest.ScreenField.CARDSID).isCursorHere()).isTrue();
            assertThat(onCard.metadata(CardSelectRequest.ScreenField.ACCTSID).isCursorHere()).isFalse();

            // Arm 2 again - the card filter is blank
            CardSelectRequest onBlankCard =
                    request(CARD_NUMBER, ACCOUNT_ID, NavigationContext.empty());
            task.wsEditCardFlag = CardSelectController.FLG_FILTER_BLANK;
            controller.setupScreenAttrs1300(onBlankCard, response, task);
            assertThat(onBlankCard.metadata(CardSelectRequest.ScreenField.CARDSID).isCursorHere())
                    .isTrue();

            // WHEN OTHER - both fine, and the cursor still lands on the account filter
            CardSelectRequest onDefault = request(CARD_NUMBER, ACCOUNT_ID, NavigationContext.empty());
            task.wsEditCardFlag = CardSelectController.FLG_FILTER_ISVALID;
            controller.setupScreenAttrs1300(onDefault, response, task);
            assertThat(onDefault.metadata(CardSelectRequest.ScreenField.ACCTSID).isCursorHere())
                    .isTrue();
        }

        @Test
        @DisplayName("the information line is DARK when silent and NEUTRAL when it has something to say")
        void theInformationLineColour() {
            CardSelectRequest request = request(CARD_NUMBER, ACCOUNT_ID, NavigationContext.empty());
            Conversation task = initialisedTask(
                    NavigationContext.empty().withFromProgram("COBIL00C"));

            CardSelectResponse silent = new CardSelectResponse();
            controller.setupScreenAttrs1300(request, silent, task);
            assertThat(silent.attributes(CardSelectResponse.ScreenField.INFOMSG).getColour())
                    .isEqualTo(BmsAttributes.DFHBMDAR);

            task.wsInfoMsg = CardSelectController.FOUND_CARDS_FOR_ACCOUNT;
            CardSelectResponse speaking = new CardSelectResponse();
            controller.setupScreenAttrs1300(request, speaking, task);
            assertThat(speaking.attributes(CardSelectResponse.ScreenField.INFOMSG).getColour())
                    .isEqualTo(BmsAttributes.DFHNEUTR);
        }

        @Test
        @DisplayName("WS-NO-INFO-MESSAGE is a VALUES list: spaces AND low-values both satisfy it")
        void noInfoMessageAcceptsBothImages() {
            Conversation task = initialisedTask(NavigationContext.empty());
            assertThat(task.noInfoMessage()).isTrue();
            task.wsInfoMsg = CardSelectController.WS_INFO_MSG_LOW_VALUES;
            assertThat(task.noInfoMessage()).isTrue();
            task.wsInfoMsg = CardSelectController.FOUND_CARDS_FOR_ACCOUNT;
            assertThat(task.noInfoMessage()).isFalse();
        }

        @Test
        @DisplayName("1400-SEND-SCREEN flips the context to REENTER and names the map from the WORK AREA")
        void sendScreen() {
            CardSelectResponse response = new CardSelectResponse();
            Conversation task = initialisedTask(NavigationContext.empty().withPgmEnter());

            controller.sendScreen1400(response, task);

            assertThat(task.ccWorkArea.getCcardNextMapset()).isEqualTo("COCRDSL").hasSize(7);
            assertThat(task.ccWorkArea.getCcardNextMap()).isEqualTo("CCRDSLA");
            assertThat(task.carddemoCommarea.isReenter()).isTrue();
            assertThat(response.getNextMapset()).isEqualTo("COCRDSL");
            assertThat(response.getNextMap()).isEqualTo("CCRDSLA");
            assertThat(response.getNavigationContext().isReenter()).isTrue();
            assertThat(task.wsRespCd).isEqualTo(FileStatus.NORMAL);
        }
    }

    @Nested
    @DisplayName("COMMON-RETURN and the dead trailing guard (COCRDSLC:386-406)")
    class Terminals {

        @Test
        @DisplayName("COMMON-RETURN reassembles a 2000-byte commarea with the trailer at offset 161")
        void commonReturnAssemblesTheCommarea() {
            CardSelectResponse response = new CardSelectResponse();
            Conversation task = initialisedTask(NavigationContext.empty().withUserId("USER0001"));
            task.thisProgCommarea = new ThisProgCommarea("COCRDLIC", "CCLI");
            task.wsReturnMsg = CardSelectController.WS_PROMPT_FOR_ACCT;

            controller.commonReturn(response, task);

            assertThat(task.wsCommarea).hasSize(2000);
            assertThat(task.wsCommarea.substring(160, 172)).isEqualTo("COCRDLICCCLI");
            // The 1828 bytes beyond what was written stay as INITIALIZE left them.
            assertThat(task.wsCommarea.substring(172)).isBlank();
            assertThat(task.ccWorkArea.getCcardErrorMsg())
                    .isEqualTo(CardSelectController.WS_PROMPT_FOR_ACCT);
            assertThat(task.returned).isTrue();
        }

        @Test
        @DisplayName("the trailing guard sends the map when INPUT-ERROR, and does nothing otherwise")
        void theTrailingGuardBothWays() {
            CardSelectRequest request = request(CARD_NUMBER, ACCOUNT_ID, NavigationContext.empty());

            // Unreachable from main0000 - every EVALUATE arm terminates - so it is driven directly.
            CardSelectResponse painted = new CardSelectResponse();
            Conversation withError = initialisedTask(NavigationContext.empty());
            withError.wsInputFlag = CardSelectController.INPUT_ERROR;
            withError.wsReturnMsg = CardSelectController.WS_PROMPT_FOR_ACCT;
            controller.trailingInputErrorGuard(request, painted, withError,
                    CardSelectController.PASSED_COMMAREA_LENGTH);
            assertThat(painted.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(withError.returned).isTrue();

            CardSelectResponse untouched = new CardSelectResponse();
            Conversation withoutError = initialisedTask(NavigationContext.empty());
            withoutError.wsInputFlag = CardSelectController.INPUT_OK;
            controller.trailingInputErrorGuard(request, untouched, withoutError,
                    CardSelectController.PASSED_COMMAREA_LENGTH);
            assertThat(untouched.getTitle01o()).isEqualTo(CardScreenState.lowValues(40));
            assertThat(withoutError.returned).isFalse();
        }

        @Test
        @DisplayName("SEND-PLAIN-TEXT transmits WS-RETURN-MSG, paints no map, and ends the task")
        void sendPlainText() {
            CardSelectResponse response = new CardSelectResponse();
            Conversation task = initialisedTask(NavigationContext.empty());
            task.wsReturnMsg = CardSelectController.UNEXPECTED_DATA_SCENARIO;
            task.abendData = SystemMessages.AbendData.spaces().withAbendCode("0001");

            controller.sendPlainText(response, task);

            assertThat(response.getErrmsgo().strip()).isEqualTo("UNEXPECTED DATA SCENARIO");
            assertThat(response.getTitle01o()).isEqualTo(CardScreenState.lowValues(40));
            assertThat(task.returned).isTrue();
        }

        @Test
        @DisplayName("SEND-LONG-TEXT is dead code, and still transmits its 500-character buffer")
        void sendLongText() {
            CardSelectResponse response = new CardSelectResponse();
            Conversation task = initialisedTask(NavigationContext.empty());
            task.wsLongMsg = "DIAGNOSTIC";

            controller.sendLongText(response, task);

            // Truncated into the 80-character receiver, which is the only place a SEND TEXT can land.
            assertThat(response.getErrmsgo()).hasSize(80).startsWith("DIAGNOSTIC");
            assertThat(task.returned).isTrue();
        }
    }

    @Nested
    @DisplayName("HANDLE ABEND and ABEND-ROUTINE (COCRDSLC:250-252, :857-878)")
    class Abend {

        @Test
        @DisplayName("a repository failure is routed to the handler and surfaces as an AbendException")
        void aFailureBecomesAnAbend() {
            when(repository.readByCardNumber(anyString()))
                    .thenThrow(new IllegalStateException("the driver refused the request"));

            assertThatThrownBy(() -> controller.handle(request("", "", fromCardList()),
                    CardSelectController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER))
                    .isInstanceOf(AbendException.class)
                    .extracting(thrown -> ((AbendException) thrown).getProgram())
                    .isEqualTo("COCRDSLC");
        }

        @Test
        @DisplayName("the abend carries no CEE3ABD ABCODE, because COCRDSLC calls no CEE3ABD")
        void noFabricatedCeeAbendCode() {
            when(repository.readByCardNumber(anyString()))
                    .thenThrow(new IllegalStateException("refused"));

            AbendException abend = null;
            try {
                controller.handle(request("", "", fromCardList()),
                        CardSelectController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);
            } catch (AbendException thrown) {
                abend = thrown;
            }

            assertThat(abend).isNotNull();
            // standard(...) would have stamped 999. This program issues EXEC CICS ABEND ABCODE('9999').
            assertThat(abend.hasAbendCode()).isFalse();
            assertThat(abend.hasTiming()).isFalse();
            assertThat(abend.getReturnCode()).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
            assertThat(abend.getReason()).isPresent();
            assertThat(abend.getReason().orElseThrow()).contains("9999");
            assertThat(abend.getCause()).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("an abend already in flight is NOT re-handled - HANDLE ABEND CANCEL")
        void anAbendIsNotReHandled() {
            AbendException raised =
                    AbendException.withoutAbendParameters("COCRDSLC", 12, "already abending");
            when(repository.readByCardNumber(anyString())).thenThrow(raised);

            assertThatThrownBy(() -> controller.handle(request("", "", fromCardList()),
                    CardSelectController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER))
                    .isSameAs(raised);
        }

        @Test
        @DisplayName("the default text is supplied only when ABEND-MSG is LOW-VALUES, not when spaces")
        void theDefaultTextIsGuardedByLowValues() {
            Conversation onSpaces = initialisedTask(NavigationContext.empty());
            controller.abendRoutine(onSpaces, new CardSelectResponse(), new IllegalStateException());
            // CSMSG02Y declares VALUE SPACES, so the guard does NOT fire and the field stays blank.
            assertThat(onSpaces.abendData.abendMsg()).isBlank();
            assertThat(onSpaces.abendData.abendCulprit().strip()).isEqualTo("COCRDSLC");

            Conversation onLowValues = initialisedTask(NavigationContext.empty());
            onLowValues.abendData = SystemMessages.AbendData.spaces()
                    .withAbendMsg(CardScreenState.lowValues(SystemMessages.ABEND_MSG_LENGTH));
            controller.abendRoutine(onLowValues, new CardSelectResponse(),
                    new IllegalStateException());
            assertThat(onLowValues.abendData.abendMsg().strip())
                    .isEqualTo("UNEXPECTED ABEND OCCURRED.");
        }

        @Test
        @DisplayName("an existing ABEND-MSG survives, so a caller's diagnosis is not overwritten")
        void anExistingMessageSurvives() {
            Conversation task = initialisedTask(NavigationContext.empty());
            task.abendData = SystemMessages.AbendData.spaces().withAbendMsg("SPECIFIC DIAGNOSIS");

            controller.abendRoutine(task, new CardSelectResponse(), new IllegalStateException());

            assertThat(task.abendData.abendMsg().strip()).isEqualTo("SPECIFIC DIAGNOSIS");
        }

        @Test
        @DisplayName("the handler copes with storage the failure never got as far as initialising")
        void theHandlerCopesWithUninitialisedStorage() {
            Conversation bare = new Conversation();

            AbendException abend =
                    controller.abendRoutine(bare, new CardSelectResponse(), new RuntimeException());

            assertThat(abend).isNotNull();
            assertThat(bare.abendData).isNotNull();
            assertThat(bare.returned).isTrue();
        }
    }

    @Nested
    @DisplayName("The HTTP contract - GET /api/cards/{cardNum}")
    class HttpContract {

        @Test
        @DisplayName("a cold start with no parameters answers 200 and prompts for input")
        void coldStart() {
            ResponseEntity<CardSelectResponse> answer = controller.viewCardDetail(
                    CARD_NUMBER, null, null, null, null, null, null, null, null, null, null, null,
                    null);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            CardSelectResponse body = answer.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getInfomsgo()).isEqualTo(CardSelectController.WS_PROMPT_FOR_INPUT);
            assertThat(body.getTrnnameo()).isEqualTo("CCDL");
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("naming a commarea field infers a passed commarea, so the criteria are shown")
        void namingACommareaFieldInfersOne() {
            when(repository.readByCardNumber(anyString()))
                    .thenReturn(CardReadResult.normal(card()));

            ResponseEntity<CardSelectResponse> answer = controller.viewCardDetail(
                    CARD_NUMBER, ACCOUNT_ID, (int) CicsAid.DFHENTER, null, 0,
                    "CCLI", "COCRDLIC", "USER0001", "U", 11L, Long.parseLong(CARD_NUMBER),
                    "COCRDLI", CARD_LIST_OWN_MAP);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            CardSelectResponse body = answer.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getCardsido()).isEqualTo(CARD_NUMBER);
            assertThat(body.getCrdnameo().strip()).isEqualTo("JOHN Q PUBLIC");
            assertThat(body.getInfomsgo()).isEqualTo(CardSelectController.FOUND_CARDS_FOR_ACCOUNT);
        }

        @Test
        @DisplayName("an explicit eibcalen of 0 beats the inference")
        void anExplicitEibcalenWins() {
            ResponseEntity<CardSelectResponse> answer = controller.viewCardDetail(
                    CARD_NUMBER, ACCOUNT_ID, null, 0, 0, "CCLI", "COCRDLIC", null, null, 11L,
                    Long.parseLong(CARD_NUMBER), "COCRDLI", CARD_LIST_OWN_MAP);

            CardSelectResponse body = answer.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getInfomsgo()).isEqualTo(CardSelectController.WS_PROMPT_FOR_INPUT);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("PF3 over HTTP answers 200 with a nextProgram and never touches the file")
        void pf3OverHttp() {
            ResponseEntity<CardSelectResponse> answer = controller.viewCardDetail(
                    CARD_NUMBER, null, (int) CicsAid.DFHPF3, null, 0, null, null, null, null, null,
                    null, null, null);

            CardSelectResponse body = answer.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getNextProgram().strip()).isEqualTo("COMEN01C");
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("an over-wide card number is TRUNCATED by the PIC X move, never rejected")
        void anOverWideCardNumberIsTruncated() {
            when(repository.readByCardNumber(anyString()))
                    .thenReturn(CardReadResult.notFound());

            controller.viewCardDetail(CARD_NUMBER + "9999", ACCOUNT_ID, null, null, 1,
                    "COBI", "COBIL00C", null, null, null, null, null, null);

            // PIC X truncates on the right, keeping the leading sixteen.
            verify(repository).readByCardNumber(CARD_NUMBER);
        }

        @Test
        @DisplayName("every response member traces to an xxxO item of COCRDSL.CPY (gate G9)")
        void thePayloadIsTheSymbolicMap() {
            ResponseEntity<CardSelectResponse> answer = controller.viewCardDetail(
                    CARD_NUMBER, null, null, null, null, null, null, null, null, null, null, null,
                    null);
            CardSelectResponse body = answer.getBody();
            assertThat(body).isNotNull();

            assertThat(CardSelectResponse.ScreenField.values()).hasSize(15);
            assertThat(body.getTrnnameo()).hasSize(4);
            assertThat(body.getTitle01o()).hasSize(40);
            assertThat(body.getCurdateo()).hasSize(8);
            assertThat(body.getPgmnameo()).hasSize(8);
            assertThat(body.getTitle02o()).hasSize(40);
            assertThat(body.getCurtimeo()).hasSize(8);
            assertThat(body.getAcctsido()).hasSize(11);
            assertThat(body.getCardsido()).hasSize(16);
            assertThat(body.getCrdnameo()).hasSize(50);
            assertThat(body.getCrdstcdo()).hasSize(1);
            assertThat(body.getExpmono()).hasSize(2);
            assertThat(body.getExpyearo()).hasSize(4);
            assertThat(body.getInfomsgo()).hasSize(40);
            assertThat(body.getErrmsgo()).hasSize(80);
            assertThat(body.getFkeyso()).hasSize(75);
        }
    }

    @Nested
    @DisplayName("Construction - statelessness and explicit wiring")
    class Construction {

        @Test
        @DisplayName("every collaborator is required, and the failure says why")
        void everyCollaboratorIsRequired() {
            assertThatThrownBy(() -> new CardSelectController(null, FIXED_CLOCK, CHARSET))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("CardRepository");
            assertThatThrownBy(() -> new CardSelectController(repository, null, CHARSET))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Clock");
            assertThatThrownBy(() -> new CardSelectController(repository, FIXED_CLOCK, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("charset");
        }

        @Test
        @DisplayName("an absent request is rejected: COBOL is entered with an input area, not nothing")
        void anAbsentRequestIsRejected() {
            assertThatThrownBy(() -> controller.handle(null, 0, CicsAid.DFHENTER))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("two calls on one instance cannot see each other's state (gate G37)")
        void thereIsNoServerSideState() {
            when(repository.readByCardNumber(anyString()))
                    .thenReturn(CardReadResult.normal(card()));

            CardSelectResponse first = controller.handle(request("", "", fromCardList()),
                    CardSelectController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);
            // A second, unrelated cold start on the SAME controller instance.
            CardSelectResponse second = controller.handle(
                    request(CARD_NUMBER, ACCOUNT_ID, NavigationContext.empty()),
                    CardSelectController.NO_COMMAREA_LENGTH, CicsAid.DFHENTER);

            assertThat(first.getCrdnameo().strip()).isEqualTo("JOHN Q PUBLIC");
            // Nothing from the first call leaked into the second.
            assertThat(second.getCrdnameo()).isEqualTo(CardScreenState.lowValues(50));
            assertThat(second.getInfomsgo()).isEqualTo(CardSelectController.WS_PROMPT_FOR_INPUT);
        }

        @Test
        @DisplayName("the codec carries the CONFIGURED code page, never the platform default")
        void theCodecCarriesTheConfiguredCharset() {
            CardSelectController ebcdic = new CardSelectController(repository, FIXED_CLOCK,
                    Charset.forName("IBM037"));

            assertThat(ebcdic.codec().charset()).isEqualTo(Charset.forName("IBM037"));
            assertThat(controller.codec().charset()).isEqualTo(StandardCharsets.US_ASCII);
        }

        @Test
        @DisplayName("isLowValuesOrSpaces distinguishes the two empty patterns from a real value")
        void bothEmptyPatternsAreRecognised() {
            assertThat(CardSelectController.isLowValuesOrSpaces("    ", 4)).isTrue();
            assertThat(CardSelectController.isLowValuesOrSpaces(CardScreenState.lowValues(4), 4))
                    .isTrue();
            assertThat(CardSelectController.isLowValuesOrSpaces("", 4)).isTrue();
            assertThat(CardSelectController.isLowValuesOrSpaces("CCLI", 4)).isFalse();
        }
    }

    /**
     * The HTTP contract, and <strong>only</strong> the HTTP contract: that
     * {@code GET /api/cards/{cardNum}} routes, that the path variable and the optional query
     * parameters bind, that the status codes are what the migration plan specifies, and that the body
     * serialises to the JSON shape the symbolic map defines.
     *
     * <p>Deliberately thin. Every decision this controller makes is asserted by the groups above,
     * which instantiate the class directly with a mocked repository and no servlet layer in the path
     * (gate G51, practice B10). Re-asserting behaviour through {@code MockMvc} would only make the same
     * assertions slower and harder to attribute, so nothing here tests a branch - these tests fail only
     * if the wiring between HTTP and the controller is wrong.
     *
     * <p>{@code MockMvcBuilders.standaloneSetup} rather than a Spring context, matching the module's
     * established pattern in {@code WebConfigErrorContractTest}: it exercises the real
     * annotation-driven routing, the real parameter binding and the real Jackson serialisation without
     * paying for a context refresh. {@code CobolErrorHandler} is registered so the advice that maps an
     * {@link AbendException} is genuinely in the chain rather than assumed.
     */
    @Nested
    @DisplayName("The HTTP contract - routing, binding, status and JSON shape only")
    class HttpWiring {

        private MockMvc mockMvc() {
            return MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new WebConfig.CobolErrorHandler())
                    .build();
        }

        @Test
        @DisplayName("GET /api/cards/{cardNum} routes, binds the path variable and answers 200 JSON")
        void theMappingRoutesAndBindsThePathVariable() throws Exception {
            when(repository.readByCardNumber(anyString()))
                    .thenReturn(CardReadResult.normal(card()));

            mockMvc().perform(get("/api/cards/{cardNum}", CARD_NUMBER)
                            .param("acctId", ACCOUNT_ID)
                            .param("eibAid", String.valueOf((int) CicsAid.DFHENTER))
                            .param("fromTranid", "CCLI")
                            .param("fromProgram", CardSelectControllerAccess.CCLIST_PGM)
                            .param("cdemoAcctId", "11")
                            .param("cdemoCardNum", CARD_NUMBER))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    // The path variable reached CARDSIDI and came back on CARDSIDO at its X(16) width.
                    .andExpect(jsonPath("$.cardsido").value(CARD_NUMBER))
                    .andExpect(jsonPath("$.acctsido").value(ACCOUNT_ID));
        }

        @Test
        @DisplayName("the body carries all fifteen symbolic-map fields and no metadata item")
        void theJsonShapeIsTheSymbolicMap() throws Exception {
            when(repository.readByCardNumber(anyString()))
                    .thenReturn(CardReadResult.normal(card()));

            // The fifteen named DFHMDF fields of app/bms/COCRDSL.bms, each at the width its xxxO item
            // declares in app/cpy-bms/COCRDSL.CPY (gate G9).
            mockMvc().perform(get("/api/cards/{cardNum}", CARD_NUMBER).param("acctId", ACCOUNT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnnameo").value(CardSelectController.LIT_THISTRANID))
                    .andExpect(jsonPath("$.pgmnameo").value(CardSelectController.LIT_THISPGM))
                    .andExpect(jsonPath("$.title01o").value(ScreenTitles.CCDA_TITLE01))
                    .andExpect(jsonPath("$.title02o").value(ScreenTitles.CCDA_TITLE02))
                    .andExpect(jsonPath("$.curdateo").exists())
                    .andExpect(jsonPath("$.curtimeo").exists())
                    .andExpect(jsonPath("$.crdnameo").exists())
                    .andExpect(jsonPath("$.crdstcdo").exists())
                    .andExpect(jsonPath("$.expmono").exists())
                    .andExpect(jsonPath("$.expyearo").exists())
                    .andExpect(jsonPath("$.infomsgo").exists())
                    .andExpect(jsonPath("$.errmsgo").exists())
                    .andExpect(jsonPath("$.fkeyso").exists())
                    // xxxL, xxxF and xxxA are validation and highlight metadata, never payload
                    // members - AAP 0.6.3. None of them may appear.
                    .andExpect(jsonPath("$.acctsidl").doesNotExist())
                    .andExpect(jsonPath("$.cardsidl").doesNotExist())
                    .andExpect(jsonPath("$.acctsida").doesNotExist())
                    .andExpect(jsonPath("$.cardsida").doesNotExist())
                    .andExpect(jsonPath("$.acctsidf").doesNotExist())
                    // And the input side of the symbolic map is not a response member either.
                    .andExpect(jsonPath("$.cardsidi").doesNotExist())
                    .andExpect(jsonPath("$.acctsidi").doesNotExist());
        }

        @Test
        @DisplayName("PF3 answers 200 with the navigation fields, because XCTL is client-driven")
        void backNavigationSerialisesTheNextTarget() throws Exception {
            mockMvc().perform(get("/api/cards/{cardNum}", CARD_NUMBER)
                            .param("eibAid", String.valueOf((int) CicsAid.DFHPF3))
                            .param("eibcalen", String.valueOf(
                                    CardSelectController.PASSED_COMMAREA_LENGTH)))
                    .andExpect(status().isOk())
                    // Gate G40: the response names the next target and the client makes the call.
                    .andExpect(jsonPath("$.nextProgram").exists())
                    .andExpect(jsonPath("$.navigationContext").exists());
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("an unbindable numeric parameter is a 400 from the advice, not a 500")
        void anUnbindableParameterIsRejectedByTheAdvice() throws Exception {
            // Binding failure is a transport concern, so it is answered by CobolErrorHandler before the
            // controller runs. This is the one status code the controller itself never produces, which
            // is exactly why it is asserted here and nowhere else.
            mockMvc().perform(get("/api/cards/{cardNum}", CARD_NUMBER)
                            .param("cdemoAcctId", "not-a-number"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(repository);
        }
    }
}
