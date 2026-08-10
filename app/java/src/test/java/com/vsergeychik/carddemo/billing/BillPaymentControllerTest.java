package com.vsergeychik.carddemo.billing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.billing.BillPaymentController.Invocation;
import com.vsergeychik.carddemo.billing.BillPaymentService.PaymentState;
import com.vsergeychik.carddemo.billing.dto.BillPaymentRequest;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse.CursorField;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.TransactionRepository.BrowseDirection;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Proves {@link BillPaymentController} against {@code app/cbl/COBIL00C.cbl}, the 572-line CICS program
 * behind transaction {@code CB00}.
 *
 * <p>The subject is a thin adapter, so this suite asserts exactly what the adapter owns:
 * {@code MAIN-PARA}'s ordered dispatch ({@code :99-149}), {@code RETURN-TO-PREV-SCREEN}
 * ({@code :273-284}), {@code RECEIVE-BILLPAY-SCREEN} ({@code :306-314}), {@code POPULATE-HEADER-INFO}
 * ({@code :319-338}), and the projection of the ten {@code DFHMDF} fields onto the payload. The payment
 * arithmetic itself is {@code BillPaymentServiceTest}'s subject and is not re-asserted here.
 *
 * <p><strong>No user rules were provided for this project</strong> - {@code review_rules} returns the
 * single line "No user rules provided." - so the enterprise-standard practices recorded in the
 * migration plan govern instead. The ones this suite exists to evidence are: preserved defects and dead
 * paths (the untested {@code RESP}/{@code RESP2}, the two sends on the selected-transaction path, the
 * {@code WHEN OTHER} arm exactly as coded), statelessness with no server-side conversation state,
 * payload provenance - every member traces to a {@code DFHMDF} field and no attribute or length item
 * reaches the wire - and a controller that holds no business logic.
 *
 * @see BillPaymentController
 * @see BillPaymentService
 */
@DisplayName("BillPaymentController - COBIL00C, the CB00 bill-payment screen")
class BillPaymentControllerTest {

    /** The dataset code page every fixed-width image in this suite is rendered in. */
    private static final Charset CHARSET = StandardCharsets.US_ASCII;

    /**
     * The instant every clock in this suite reports: the source's own version footer,
     * {@code Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:32 CDT}.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:32Z");

    /** {@code WS-CURDATE-MM-DD-YY} for {@link #FIXED_INSTANT} at Greenwich: {@code MM/DD/YY}. */
    private static final String EXPECTED_CURDATE = "07/19/22";

    /** {@code WS-CURTIME-HH-MM-SS} for {@link #FIXED_INSTANT} at Greenwich: {@code HH:MM:SS}. */
    private static final String EXPECTED_CURTIME = "23:12:32";

    /** The clock the controller and the service both read. */
    private static final Clock CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    /** An eleven-character account identifier, the width {@code ACTIDIN} declares. */
    private static final String ACCT_KEY = "00000000011";

    /** The same identifier in the sixteen-character {@code CDEMO-CB00-TRN-SELECTED} carrier. */
    private static final String ACCT_KEY_IN_CARRIER = "00000000011     ";

    /** A card number for the cross-reference read at {@code :410-418}. */
    private static final String CARD_NUMBER = "4111111111111111";

    /**
     * Every member {@link BillPaymentResponse} publishes on the wire: the ten {@code DFHMDF} field
     * projections, the two collapsed metadata members, the communication area, the navigation triple and
     * the six communication-area extension members.
     *
     * <p>Named exhaustively rather than derived, because the point of the assertion is that nothing
     * <em>else</em> appears - in particular no {@code xxxL} length item, no {@code xxxF} flag byte, no
     * {@code xxxA} attribute view and none of the output side's {@code xxxC}, {@code xxxP}, {@code xxxH}
     * or {@code xxxV} items.
     */
    private static final Set<String> EXPECTED_WIRE_MEMBERS = Set.of(
            "trnName", "title01", "curDate", "pgmName", "title02", "curTime",
            "actIdIn", "curBal", "confirm", "errMsg",
            "cursorField", "messageHighlight",
            "navigationContext", "nextProgram", "nextMapset", "nextMap",
            "trnIdFirst", "trnIdLast", "pageNum", "nextPageFlg", "trnSelFlg", "trnSelected");

    private AccountRepository accountRepository;
    private CardXrefRepository cardXrefRepository;
    private TransactionRepository transactionRepository;
    private TransactionRepository.Browse browse;
    private BillPaymentService service;
    private BillPaymentController controller;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        cardXrefRepository = mock(CardXrefRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        browse = mock(TransactionRepository.Browse.class);
        when(accountRepository.datasetCharset()).thenReturn(CHARSET);
        when(transactionRepository.startBrowse(BrowseDirection.BACKWARD)).thenReturn(browse);
        service = new BillPaymentService(accountRepository, cardXrefRepository, transactionRepository,
                CLOCK, newUnitOfWork());
        controller = new BillPaymentController(service, CLOCK);
    }

    // =================================================================================================
    // Fixtures.
    // =================================================================================================

    /**
     * A real unit of work over a database of this test's own, so no two tests share one.
     *
     * @return a boundary whose {@link DatasetUnitOfWork#active()} reports the truth
     */
    private static DatasetUnitOfWork newUnitOfWork() {
        PlatformTransactionManager manager = new DataSourceTransactionManager(
                new SimpleDriverDataSource(new org.h2.Driver(),
                        "jdbc:h2:mem:billpayctl-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        return new DatasetUnitOfWork(manager);
    }

    /**
     * A stored account carrying the given balance.
     *
     * @param balance {@code ACCT-CURR-BAL}
     * @return a fresh 300-byte record
     */
    private static AccountRecord account(String balance) {
        AccountRecord record = new AccountRecord(CHARSET);
        record.setAcctId(11L);
        record.setAcctActiveStatus("Y");
        record.setAcctCurrBal(new BigDecimal(balance));
        record.setAcctCreditLimit(new BigDecimal("5000.00"));
        return record;
    }

    /**
     * A stored cross-reference row for {@link #CARD_NUMBER}.
     *
     * @return the outcome a successful alternate-index read reports
     */
    private static CardXrefRepository.ReadResult xrefFound() {
        CardXrefRecord record = new CardXrefRecord(CARD_NUMBER, 1, 11L);
        return CardXrefRepository.ReadResult.found(CardXrefRepository.ALTERNATE_INDEX_DD_NAME, record,
                new String(record.encode(CHARSET), CHARSET));
    }

    /**
     * Stubs the account read alone: enough for the enter-key path to display a balance.
     *
     * @param balance the stored balance
     */
    private void stubAccountRead(String balance) {
        when(accountRepository.readForUpdate(anyString()))
                .thenReturn(AccountRepository.ReadResult.found(account(balance)));
    }

    /**
     * Stubs the whole confirmed-payment path: the account reads and rewrites, the cross-reference is
     * found, the browse reports an empty master, and the write succeeds.
     *
     * @param balance the stored balance
     */
    private void stubConfirmedPayment(String balance) {
        stubAccountRead(balance);
        when(accountRepository.rewrite(any())).thenReturn(AccountRepository.WriteResult.written());
        when(cardXrefRepository.readByAccountIdViaAltIndex(anyString())).thenReturn(xrefFound());
        when(browse.readPrev()).thenReturn(
                TransactionRepository.ReadResult.endOfFile(TransactionRepository.CICS_FILE_NAME));
        when(transactionRepository.write(any())).thenReturn(
                TransactionRepository.WriteResult.written(TransactionRepository.CICS_FILE_NAME));
    }

    /**
     * A stored transaction carrying the given identifier.
     *
     * @param tranId {@code TRAN-ID}, sixteen characters
     * @return a fresh 350-byte record
     */
    private static TranRecord tran(String tranId) {
        TranRecord record = new TranRecord(CHARSET);
        record.moveTranId(tranId);
        return record;
    }

    /**
     * A first-entry payload: a communication area at {@code CDEMO-PGM-ENTER} and nothing typed.
     *
     * @return the screen a transaction transferred into for the first time receives
     */
    private static BillPaymentRequest firstEntry() {
        BillPaymentRequest request = new BillPaymentRequest();
        request.setNavigationContext(NavigationContext.empty());
        return request;
    }

    /**
     * A re-entry payload carrying an attention identifier and the two typed fields.
     *
     * @param aid     the {@code CCARD-AID} token, or {@code null} for none
     * @param actIdIn {@code ACTIDINI}
     * @param confirm {@code CONFIRMI}
     * @return the screen an operator's keystroke sends back
     */
    private static BillPaymentRequest reentry(String aid, String actIdIn, String confirm) {
        BillPaymentRequest request = new BillPaymentRequest();
        request.setNavigationContext(NavigationContext.empty().withPgmReenter());
        request.setAid(aid);
        request.setActIdIn(actIdIn);
        request.setConfirm(confirm);
        return request;
    }

    /**
     * {@code MockMvc} over the controller alone, with a mapper of this suite's own.
     *
     * @return a standalone setup carrying no filter, no session support and no security
     */
    private MockMvc mockMvc(ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    /**
     * A run of the {@code LOW-VALUES} figurative constant.
     *
     * @param width the field's declared width
     * @return exactly {@code width} {@code X'00'} characters
     */
    private static String lowValues(int width) {
        return "\u0000".repeat(width);
    }

    // =================================================================================================
    // Construction.
    // =================================================================================================

    @Nested
    @DisplayName("construction - two collaborators, constructor injection only")
    class Construction {

        @Test
        @DisplayName("the service is required")
        void serviceIsRequired() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new BillPaymentController(null, CLOCK))
                    .withMessageContaining("BillPaymentService");
        }

        @Test
        @DisplayName("the clock is required, because POPULATE-HEADER-INFO must be assertable")
        void clockIsRequired() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new BillPaymentController(service, null))
                    .withMessageContaining("Clock");
        }

        @Test
        @DisplayName("a service reporting no codec is refused at construction")
        void codecIsRequired() {
            BillPaymentService codeless = mock(BillPaymentService.class);
            when(codeless.codec()).thenReturn(null);
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new BillPaymentController(codeless, CLOCK))
                    .withMessageContaining("codec");
        }

        @Test
        @DisplayName("the identity constants come from the source, not from retyped literals")
        void identityConstants() {
            Assertions.assertThat(BillPaymentController.PROGRAM_NAME).isEqualTo("COBIL00C");
            Assertions.assertThat(BillPaymentController.TRANSACTION_ID).isEqualTo("CB00");
            Assertions.assertThat(BillPaymentController.MAPSET_NAME).isEqualTo("COBIL00");
            Assertions.assertThat(BillPaymentController.MAP_NAME).isEqualTo("COBIL0A");
            Assertions.assertThat(BillPaymentController.SIGN_ON_PROGRAM).isEqualTo("COSGN00C");
            Assertions.assertThat(BillPaymentController.MAIN_MENU_PROGRAM).isEqualTo("COMEN01C");
            Assertions.assertThat(BillPaymentController.BILL_PAY_PATH).isEqualTo("/api/billpay");
        }

        @Test
        @DisplayName("a null payload is refused rather than treated as a cold start")
        void nullPayloadIsRefused() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> controller.mainPara(null))
                    .withMessageContaining("payload is required");
        }
    }

    // =================================================================================================
    // :107-:109  IF EIBCALEN = 0.
    // =================================================================================================

    @Nested
    @DisplayName("EIBCALEN = 0 - app/cbl/COBIL00C.cbl:107-109, the cold start")
    class ColdStart {

        @Test
        @DisplayName("no communication area transfers to COSGN00C and stamps this program's identity")
        void transfersToSignOn() {
            Invocation outcome = controller.mainPara(new BillPaymentRequest());

            BillPaymentResponse response = outcome.response();
            Assertions.assertThat(response.getNextProgram()).isEqualTo("COSGN00C");
            Assertions.assertThat(response.getNavigationContext().toProgram()).isEqualTo("COSGN00C");
            Assertions.assertThat(response.getNavigationContext().fromTranid()).isEqualTo("CB00");
            Assertions.assertThat(response.getNavigationContext().fromProgram()).isEqualTo("COBIL00C");
            Assertions.assertThat(response.getNavigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            Assertions.assertThat(response.getNavigationContext().isEnter()).isTrue();
        }

        @Test
        @DisplayName("the screen is never sent, so only :105 has written to the map")
        void neverSendsTheScreen() {
            Invocation outcome = controller.mainPara(new BillPaymentRequest());

            Assertions.assertThat(outcome.state().screensSent()).isZero();
            Assertions.assertThat(outcome.state().sentScreens()).isEmpty();
            // :105 MOVE SPACES TO ERRMSGO OF COBIL0AO - the one map field written on this arm.
            Assertions.assertThat(outcome.response().getErrMsg())
                    .isEqualTo(" ".repeat(BillPaymentResponse.ERR_MSG_LENGTH));
        }

        @Test
        @DisplayName("POPULATE-HEADER-INFO never runs, so the header stays unpainted")
        void headerStaysUnpainted() {
            BillPaymentResponse response = controller.mainPara(new BillPaymentRequest()).response();

            Assertions.assertThat(response.getTrnName()).isNull();
            Assertions.assertThat(response.getPgmName()).isNull();
            Assertions.assertThat(response.getTitle01()).isNull();
            Assertions.assertThat(response.getTitle02()).isNull();
            Assertions.assertThat(response.getCurDate()).isNull();
            Assertions.assertThat(response.getCurTime()).isNull();
            // Nothing was sent, so the SEND's MAP and MAPSET operands report nothing either.
            Assertions.assertThat(response.getNextMapset()).isNull();
            Assertions.assertThat(response.getNextMap()).isNull();
        }

        @Test
        @DisplayName("the extension is not echoed, because :111 never loaded it")
        void extensionKeepsItsDeclaredInitialValues() {
            BillPaymentRequest request = new BillPaymentRequest();
            request.setTrnSelected(ACCT_KEY_IN_CARRIER);
            request.setTrnIdFirst("0000000000000001");
            request.setPageNum(7);

            BillPaymentResponse response = controller.mainPara(request).response();

            Assertions.assertThat(response.getTrnSelected()).isNull();
            Assertions.assertThat(response.getTrnIdFirst()).isNull();
            Assertions.assertThat(response.getPageNum()).isZero();
            Assertions.assertThat(response.getNextPageFlg()).isEqualTo(BillPaymentResponse.NEXT_PAGE_NO);
        }

        @Test
        @DisplayName("no dataset is touched on this arm")
        void touchesNoDataset() {
            controller.mainPara(new BillPaymentRequest());

            verify(accountRepository, never()).readForUpdate(anyString());
            verify(transactionRepository, never()).write(any());
        }
    }

    // =================================================================================================
    // :113-:122  the first-entry arm.
    // =================================================================================================

    @Nested
    @DisplayName("CDEMO-PGM-CONTEXT = ENTER - app/cbl/COBIL00C.cbl:113-122, first entry")
    class FirstEntry {

        @Test
        @DisplayName(":113 advances the context, so the next keystroke takes the other branch")
        void advancesTheContext() {
            BillPaymentResponse response = controller.mainPara(firstEntry()).response();

            Assertions.assertThat(response.getNavigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
            Assertions.assertThat(response.getNavigationContext().isReenter()).isTrue();
        }

        @Test
        @DisplayName(":114 clears the whole 294-byte overlay, and :115 parks the cursor in ACTIDIN")
        void clearsTheOverlayAndPlacesTheCursor() {
            BillPaymentResponse response = controller.mainPara(firstEntry()).response();

            Assertions.assertThat(response.getActIdIn())
                    .isEqualTo(lowValues(BillPaymentResponse.ACT_ID_IN_LENGTH));
            Assertions.assertThat(response.getCurBal())
                    .isEqualTo(lowValues(BillPaymentResponse.CUR_BAL_LENGTH));
            Assertions.assertThat(response.getConfirm())
                    .isEqualTo(lowValues(BillPaymentResponse.CONFIRM_LENGTH));
            Assertions.assertThat(response.getCursorField()).isEqualTo(CursorField.ACTIDIN);
        }

        @Test
        @DisplayName(":122 sends the screen once when nothing was selected")
        void sendsOnceWithNoSelection() {
            Invocation outcome = controller.mainPara(firstEntry());

            Assertions.assertThat(outcome.state().screensSent()).isEqualTo(1);
            Assertions.assertThat(outcome.response().getNextMapset()).isEqualTo("COBIL00");
            Assertions.assertThat(outcome.response().getNextMap()).isEqualTo("COBIL0A");
            // Every SEND-BILLPAY-SCREEN path re-displays this screen, so no transfer is requested.
            Assertions.assertThat(outcome.response().getNextProgram()).isNull();
            // :293 MOVE WS-MESSAGE TO ERRMSGO - WS-MESSAGE is blank at this point.
            Assertions.assertThat(outcome.response().getErrMsg())
                    .isEqualTo(" ".repeat(BillPaymentResponse.ERR_MSG_LENGTH));
            verify(accountRepository, never()).readForUpdate(anyString());
        }

        @ParameterizedTest(name = "a selection of {0} performs no lookup")
        @DisplayName(":116-117 a blank or LOW-VALUES selection is equal to a figurative constant")
        @ValueSource(ints = {0, 16})
        void blankSelectionPerformsNoLookup(int blankWidth) {
            // Width 0 exercises the empty-member case, which the codec's PIC X rule pads to sixteen
            // blanks; width 16 exercises the field as MOVE LOW-VALUES leaves it.
            BillPaymentRequest request = firstEntry();
            request.setTrnSelected(blankWidth == 0 ? "" : lowValues(blankWidth));

            Invocation outcome = controller.mainPara(request);

            Assertions.assertThat(outcome.state().screensSent()).isEqualTo(1);
            verify(accountRepository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName(":116-117 a selection of sixteen blanks performs no lookup")
        void allBlankSelectionPerformsNoLookup() {
            BillPaymentRequest request = firstEntry();
            request.setTrnSelected(" ".repeat(BillPaymentResponse.TRN_SELECTED_LENGTH));

            Invocation outcome = controller.mainPara(request);

            Assertions.assertThat(outcome.state().screensSent()).isEqualTo(1);
            verify(accountRepository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName(":116-117 an absent selection performs no lookup either")
        void absentSelectionPerformsNoLookup() {
            Invocation outcome = controller.mainPara(firstEntry());

            Assertions.assertThat(outcome.state().screensSent()).isEqualTo(1);
            verify(accountRepository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName(":120 a selected transaction is processed at once - and :122 sends a SECOND time")
        void selectedTransactionIsProcessedAndTheScreenIsSentTwice() {
            stubAccountRead("1234.56");
            BillPaymentRequest request = firstEntry();
            request.setTrnSelected(ACCT_KEY_IN_CARRIER);

            Invocation outcome = controller.mainPara(request);

            // PROCESS-ENTER-KEY sent once from :242; MAIN-PARA sends again at :122. Two sends, not one:
            // collapsing them would change the observable byte stream (practice B5).
            Assertions.assertThat(outcome.state().screensSent()).isEqualTo(2);
            verify(accountRepository).readForUpdate(ACCT_KEY);
            // :193-194 MOVE ACCT-CURR-BAL TO WS-CURR-BAL then to CURBALI - PIC +9999999999.99.
            Assertions.assertThat(outcome.response().getCurBal()).isEqualTo("+0000001234.56");
            // :237-239 no confirmation yet, so the prompt and the cursor in CONFIRM.
            Assertions.assertThat(outcome.response().getErrMsg())
                    .startsWith("Confirm to make a bill payment...");
            Assertions.assertThat(outcome.response().getCursorField()).isEqualTo(CursorField.CONFIRM);
        }

        @Test
        @DisplayName(":118-119 X(16) into X(11) keeps the leading eleven characters")
        void selectionIsTruncatedOnTheRight() {
            stubAccountRead("10.00");
            BillPaymentRequest request = firstEntry();
            request.setTrnSelected("0000000001199999");

            Invocation outcome = controller.mainPara(request);

            verify(accountRepository).readForUpdate("00000000011");
            Assertions.assertThat(outcome.response().getActIdIn()).isEqualTo("00000000011");
        }

        @Test
        @DisplayName(":114 leaves CONFIRMI at LOW-VALUES, so the confirmation switch reads, not pays")
        void confirmIsLowValuesSoNothingIsPaid() {
            stubAccountRead("1234.56");
            BillPaymentRequest request = firstEntry();
            request.setTrnSelected(ACCT_KEY_IN_CARRIER);

            controller.mainPara(request);

            verify(accountRepository).readForUpdate(ACCT_KEY);
            verify(transactionRepository, never()).write(any());
            verify(accountRepository, never()).rewrite(any());
        }

        @Test
        @DisplayName("the extension is echoed in full, including the five members nothing reads")
        void extensionIsEchoedInFull() {
            BillPaymentRequest request = firstEntry();
            request.setTrnIdFirst("0000000000000001");
            request.setTrnIdLast("0000000000000009");
            request.setPageNum(3);
            request.setNextPageFlg("Y");
            request.setTrnSelFlg("S");
            request.setTrnSelected("                ");

            BillPaymentResponse response = controller.mainPara(request).response();

            Assertions.assertThat(response.getTrnIdFirst()).isEqualTo("0000000000000001");
            Assertions.assertThat(response.getTrnIdLast()).isEqualTo("0000000000000009");
            Assertions.assertThat(response.getPageNum()).isEqualTo(3);
            Assertions.assertThat(response.getNextPageFlg()).isEqualTo("Y");
            Assertions.assertThat(response.getTrnSelFlg()).isEqualTo("S");
            Assertions.assertThat(response.getTrnSelected()).isEqualTo("                ");
        }

        @Test
        @DisplayName("CDEMO-LAST-MAP and CDEMO-LAST-MAPSET are echoed untouched - X(7), and never written")
        void lastMapMembersAreEchoedUntouched() {
            BillPaymentRequest request = new BillPaymentRequest();
            request.setNavigationContext(NavigationContext.empty()
                    .withLastMap("COTRN1A")
                    .withLastMapset("COTRN01"));

            BillPaymentResponse response = controller.mainPara(request).response();

            Assertions.assertThat(response.getNavigationContext().lastMap()).isEqualTo("COTRN1A");
            Assertions.assertThat(response.getNavigationContext().lastMapset()).isEqualTo("COTRN01");
        }
    }

    // =================================================================================================
    // :124-:142  the re-entry arm and the ordered EVALUATE EIBAID.
    // =================================================================================================

    @Nested
    @DisplayName("EVALUATE EIBAID - app/cbl/COBIL00C.cbl:125-142, four arms in the source's order")
    class KeyDispatch {

        @Test
        @DisplayName("DFHENTER at :126 processes the screen")
        void enterProcessesTheScreen() {
            stubAccountRead("1234.56");

            Invocation outcome = controller.mainPara(
                    reentry(AidKey.ENTER.token(), ACCT_KEY, " "));

            verify(accountRepository).readForUpdate(ACCT_KEY);
            Assertions.assertThat(outcome.response().getCurBal()).isEqualTo("+0000001234.56");
            Assertions.assertThat(outcome.response().getCursorField()).isEqualTo(CursorField.CONFIRM);
            Assertions.assertThat(outcome.state().screensSent()).isEqualTo(1);
            Assertions.assertThat(outcome.response().getNextProgram()).isNull();
        }

        @Test
        @DisplayName("an absent attention identifier defaults to DFHENTER, the only honest default")
        void absentAidDefaultsToEnter() {
            stubAccountRead("1234.56");

            Invocation outcome = controller.mainPara(reentry(null, ACCT_KEY, " "));

            verify(accountRepository).readForUpdate(ACCT_KEY);
            Assertions.assertThat(outcome.response().getCurBal()).isEqualTo("+0000001234.56");
        }

        @Test
        @DisplayName("an empty attention identifier defaults to DFHENTER too")
        void emptyAidDefaultsToEnter() {
            stubAccountRead("1234.56");

            Invocation outcome = controller.mainPara(reentry("", ACCT_KEY, " "));

            verify(accountRepository).readForUpdate(ACCT_KEY);
            Assertions.assertThat(outcome.response().getCurBal()).isEqualTo("+0000001234.56");
        }

        @Test
        @DisplayName("DFHENTER reads the map as RECEIVE left it, at the declared widths")
        void enterReadsTheReceivedMap() {
            stubAccountRead("1234.56");
            // A nine-character value in an eleven-character field: RECEIVE pads it, and the two-receiver
            // MOVE at :170-171 then carries the padded image into the record key unchanged.
            Invocation outcome = controller.mainPara(reentry(AidKey.ENTER.token(), "000000011", " "));

            verify(accountRepository).readForUpdate("000000011  ");
            Assertions.assertThat(outcome.response().getActIdIn()).isEqualTo("000000011  ");
        }

        @Test
        @DisplayName("DFHPF3 at :130 falls back to COMEN01C when CDEMO-FROM-PROGRAM is blank")
        void pf3FallsBackToTheMainMenu() {
            Invocation outcome = controller.mainPara(reentry(AidKey.PFK03.token(), ACCT_KEY, " "));

            BillPaymentResponse response = outcome.response();
            Assertions.assertThat(response.getNextProgram()).isEqualTo("COMEN01C");
            Assertions.assertThat(response.getNavigationContext().toProgram()).isEqualTo("COMEN01C");
            Assertions.assertThat(response.getNavigationContext().fromTranid()).isEqualTo("CB00");
            Assertions.assertThat(response.getNavigationContext().fromProgram()).isEqualTo("COBIL00C");
            Assertions.assertThat(response.getNavigationContext().isEnter()).isTrue();
            // :128-135 does not send, so the SEND's operands and the header report nothing.
            Assertions.assertThat(outcome.state().screensSent()).isZero();
            Assertions.assertThat(response.getNextMapset()).isNull();
            Assertions.assertThat(response.getNextMap()).isNull();
            // The header field holds what RECEIVE put there - LOW-VALUES for an untransmitted field -
            // and not what POPULATE-HEADER-INFO would have written, because this arm never sends.
            Assertions.assertThat(response.getTrnName())
                    .isEqualTo(lowValues(BillPaymentResponse.TRN_NAME_LENGTH));
            verify(accountRepository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("DFHPF3 at :132-133 goes back to CDEMO-FROM-PROGRAM when one is named")
        void pf3EchoesTheCaller() {
            BillPaymentRequest request = reentry(AidKey.PFK03.token(), ACCT_KEY, " ");
            request.setNavigationContext(request.getNavigationContext().withFromProgram("COMEN01C"));

            BillPaymentResponse response = controller.mainPara(request).response();

            Assertions.assertThat(response.getNextProgram()).isEqualTo("COMEN01C");
            // RETURN-TO-PREV-SCREEN then overwrites CDEMO-FROM-PROGRAM with this program's own name.
            Assertions.assertThat(response.getNavigationContext().fromProgram()).isEqualTo("COBIL00C");
        }

        @Test
        @DisplayName("DFHPF3 with a LOW-VALUES caller also falls back to COMEN01C")
        void pf3WithLowValuesCallerFallsBack() {
            BillPaymentRequest request = reentry(AidKey.PFK03.token(), ACCT_KEY, " ");
            request.setNavigationContext(request.getNavigationContext()
                    .withFromProgram(lowValues(NavigationContext.FROM_PROGRAM_LENGTH)));

            BillPaymentResponse response = controller.mainPara(request).response();

            Assertions.assertThat(response.getNextProgram()).isEqualTo("COMEN01C");
        }

        @Test
        @DisplayName("DFHPF3 echoes the map exactly as RECEIVE left it, because no send follows")
        void pf3EchoesTheReceivedMap() {
            BillPaymentRequest request = reentry(AidKey.PFK03.token(), "0000000002", "Y");
            request.setCurBal("+0000000099.00");

            BillPaymentResponse response = controller.mainPara(request).response();

            Assertions.assertThat(response.getActIdIn()).isEqualTo("0000000002 ");
            Assertions.assertThat(response.getCurBal()).isEqualTo("+0000000099.00");
            Assertions.assertThat(response.getConfirm()).isEqualTo("Y");
        }

        @Test
        @DisplayName("DFHPF4 at :137 clears every field and sends")
        void pf4ClearsTheScreen() {
            Invocation outcome = controller.mainPara(
                    reentry(AidKey.PFK04.token(), ACCT_KEY, "Y"));

            BillPaymentResponse response = outcome.response();
            // :560-566 INITIALIZE-ALL-FIELDS blanks the three data fields and WS-MESSAGE, and parks the
            // cursor in ACTIDIN.
            Assertions.assertThat(response.getActIdIn())
                    .isEqualTo(" ".repeat(BillPaymentResponse.ACT_ID_IN_LENGTH));
            Assertions.assertThat(response.getCurBal())
                    .isEqualTo(" ".repeat(BillPaymentResponse.CUR_BAL_LENGTH));
            Assertions.assertThat(response.getConfirm())
                    .isEqualTo(" ".repeat(BillPaymentResponse.CONFIRM_LENGTH));
            Assertions.assertThat(response.getErrMsg())
                    .isEqualTo(" ".repeat(BillPaymentResponse.ERR_MSG_LENGTH));
            Assertions.assertThat(response.getCursorField()).isEqualTo(CursorField.ACTIDIN);
            Assertions.assertThat(outcome.state().screensSent()).isEqualTo(1);
            Assertions.assertThat(response.getNextProgram()).isNull();
            verify(accountRepository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("WHEN OTHER at :138-141 raises the flag and sends the standard invalid-key text")
        void whenOtherReportsAnInvalidKey() {
            Invocation outcome = controller.mainPara(
                    reentry(AidKey.PFK12.token(), ACCT_KEY, " "));

            Assertions.assertThat(outcome.state().isErrFlagOn()).isTrue();
            Assertions.assertThat(outcome.response().getErrMsg())
                    .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY
                            + " ".repeat(BillPaymentResponse.ERR_MSG_LENGTH
                                    - SystemMessages.MESSAGE_LENGTH));
            // No cursor statement appears on this arm, so the terminal applies the map's own IC field.
            Assertions.assertThat(outcome.response().getCursorField()).isEqualTo(CursorField.NONE);
            Assertions.assertThat(outcome.state().screensSent()).isEqualTo(1);
            verify(accountRepository, never()).readForUpdate(anyString());
        }

        @ParameterizedTest(name = "{0} reaches WHEN OTHER")
        @DisplayName("every key the program does not name falls into WHEN OTHER - no arm is invented")
        @CsvSource({"CLEAR", "PA1  ", "PA2  ", "PFK01", "PFK02", "PFK05", "PFK06", "PFK07", "PFK08",
                    "PFK09", "PFK10", "PFK11", "PFK12"})
        void unnamedKeysReachWhenOther(String token) {
            Invocation outcome = controller.mainPara(reentry(token, ACCT_KEY, " "));

            Assertions.assertThat(outcome.state().isErrFlagOn()).isTrue();
            Assertions.assertThat(outcome.response().getErrMsg())
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY.trim());
            verify(accountRepository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("a token naming no key at all also reaches WHEN OTHER")
        void unknownTokenReachesWhenOther() {
            Invocation outcome = controller.mainPara(reentry("ZZZZZ", ACCT_KEY, " "));

            Assertions.assertThat(outcome.state().isErrFlagOn()).isTrue();
            Assertions.assertThat(outcome.response().getErrMsg())
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY.trim());
        }
    }

    // =================================================================================================
    // :306-:314  RECEIVE-BILLPAY-SCREEN.
    // =================================================================================================

    @Nested
    @DisplayName("RECEIVE-BILLPAY-SCREEN - app/cbl/COBIL00C.cbl:306-314")
    class Receive {

        @Test
        @DisplayName("all ten xxxI items are filled, because every field on the mapset carries FSET")
        void fillsAllTenFields() {
            BillPaymentRequest request = reentry(AidKey.PFK03.token(), ACCT_KEY, "N");
            request.setTrnName("CB00");
            request.setTitle01(ScreenTitles.CCDA_TITLE01);
            request.setCurDate("01/02/03");
            request.setPgmName("COBIL00C");
            request.setTitle02(ScreenTitles.CCDA_TITLE02);
            request.setCurTime("04:05:06");
            request.setCurBal("+0000000042.00");
            request.setErrMsg("previously displayed message");

            BillPaymentResponse response = controller.mainPara(request).response();

            Assertions.assertThat(response.getTrnName()).isEqualTo("CB00");
            Assertions.assertThat(response.getTitle01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            Assertions.assertThat(response.getCurDate()).isEqualTo("01/02/03");
            Assertions.assertThat(response.getPgmName()).isEqualTo("COBIL00C");
            Assertions.assertThat(response.getTitle02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            Assertions.assertThat(response.getCurTime()).isEqualTo("04:05:06");
            Assertions.assertThat(response.getActIdIn()).isEqualTo(ACCT_KEY);
            Assertions.assertThat(response.getCurBal()).isEqualTo("+0000000042.00");
            Assertions.assertThat(response.getConfirm()).isEqualTo("N");
            Assertions.assertThat(response.getErrMsg())
                    .isEqualTo("previously displayed message"
                            + " ".repeat(BillPaymentResponse.ERR_MSG_LENGTH
                                    - "previously displayed message".length()));
        }

        @Test
        @DisplayName("an untransmitted field arrives as LOW-VALUES, not as blanks and not as absent")
        void untransmittedFieldsAreLowValues() {
            BillPaymentResponse response =
                    controller.mainPara(reentry(AidKey.PFK03.token(), null, null)).response();

            Assertions.assertThat(response.getActIdIn())
                    .isEqualTo(lowValues(BillPaymentResponse.ACT_ID_IN_LENGTH));
            Assertions.assertThat(response.getCurBal())
                    .isEqualTo(lowValues(BillPaymentResponse.CUR_BAL_LENGTH));
            Assertions.assertThat(response.getConfirm())
                    .isEqualTo(lowValues(BillPaymentResponse.CONFIRM_LENGTH));
            Assertions.assertThat(response.getTrnName())
                    .isEqualTo(lowValues(BillPaymentResponse.TRN_NAME_LENGTH));
        }

        @Test
        @DisplayName(":312-313 RESP and RESP2 are captured, and the program tests neither")
        void responseCodesAreCapturedAndNeverTested() {
            Invocation outcome = controller.mainPara(reentry(AidKey.PFK03.token(), ACCT_KEY, " "));

            Assertions.assertThat(outcome.state().respCd()).isEqualTo(FileStatus.NORMAL);
            Assertions.assertThat(outcome.state().reasCd()).isEqualTo(FileStatus.NO_REASON_CODE);
            // The proof that nothing tests them: the arm ran to completion and no message was raised.
            Assertions.assertThat(outcome.state().isErrFlagOn()).isFalse();
        }

        @Test
        @DisplayName("a value wider than its field is truncated on the right, never on the left")
        void overWideValuesTruncateOnTheRight() {
            BillPaymentRequest request = new BillPaymentRequest();
            request.setNavigationContext(NavigationContext.empty().withPgmReenter());
            request.setAid(AidKey.PFK03.token());
            request.setActIdIn("0000000001199999");

            BillPaymentResponse response = controller.mainPara(request).response();

            Assertions.assertThat(response.getActIdIn()).isEqualTo("00000000011");
        }
    }

    // =================================================================================================
    // :319-:338  POPULATE-HEADER-INFO, and :526 the one attribute write.
    // =================================================================================================

    @Nested
    @DisplayName("POPULATE-HEADER-INFO - app/cbl/COBIL00C.cbl:319-338")
    class Header {

        @Test
        @DisplayName("the six header fields come from the literals and from the injected clock")
        void paintsTheSixHeaderFields() {
            BillPaymentResponse response = controller.mainPara(firstEntry()).response();

            Assertions.assertThat(response.getTrnName()).isEqualTo("CB00");
            Assertions.assertThat(response.getPgmName()).isEqualTo("COBIL00C");
            Assertions.assertThat(response.getTitle01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            Assertions.assertThat(response.getTitle02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            Assertions.assertThat(response.getCurDate()).isEqualTo(EXPECTED_CURDATE);
            Assertions.assertThat(response.getCurTime()).isEqualTo(EXPECTED_CURTIME);
        }

        @Test
        @DisplayName("the two titles are byte-exact and exactly PIC X(40) - an exact fit, nothing padded")
        void titlesAreByteExact() {
            BillPaymentResponse response = controller.mainPara(firstEntry()).response();

            Assertions.assertThat(response.getTitle01())
                    .hasSize(BillPaymentResponse.TITLE01_LENGTH)
                    .isEqualTo("      AWS Mainframe Modernization       ");
            Assertions.assertThat(response.getTitle02())
                    .hasSize(BillPaymentResponse.TITLE02_LENGTH)
                    .isEqualTo("              CardDemo                  ");
            // The third COTTL01Y literal belongs to no field of this screen and must not appear.
            Assertions.assertThat(response.getTitle01()).isNotEqualTo(ScreenTitles.CCDA_THANK_YOU);
            Assertions.assertThat(response.getTitle02()).isNotEqualTo(ScreenTitles.CCDA_THANK_YOU);
        }

        @Test
        @DisplayName("the header is rendered from the clock, so a different clock renders differently")
        void headerFollowsTheClock() {
            BillPaymentController other = new BillPaymentController(service,
                    Clock.fixed(Instant.parse("1999-12-31T00:00:01Z"), ZoneOffset.UTC));

            BillPaymentResponse response = other.mainPara(firstEntry()).response();

            Assertions.assertThat(response.getCurDate()).isEqualTo("12/31/99");
            Assertions.assertThat(response.getCurTime()).isEqualTo("00:00:01");
        }

        @Test
        @DisplayName(":526 a completed payment is the program's ONLY attribute write - DFHGREEN")
        void successfulPaymentColoursTheMessageGreen() {
            stubConfirmedPayment("1234.56");

            Invocation outcome = controller.mainPara(reentry(AidKey.ENTER.token(), ACCT_KEY, "Y"));

            BillPaymentResponse response = outcome.response();
            Assertions.assertThat(response.getErrMsg()).startsWith("Payment successful.");
            Assertions.assertThat(response.getMessageHighlight())
                    .isEqualTo(BillPaymentService.MESSAGE_HIGHLIGHT_GREEN)
                    .hasSize(BillPaymentResponse.MESSAGE_HIGHLIGHT_LENGTH);
            Assertions.assertThat((byte) response.getMessageHighlight().charAt(0))
                    .isEqualTo(BmsAttributes.DFHGREEN);
            verify(transactionRepository).write(any());
            verify(accountRepository).rewrite(any());
        }

        @Test
        @DisplayName("no path writes DFHRED and no path writes an asterisk - CSSETATY is not copied")
        void noPathAppliesAnErrorHighlight() {
            stubAccountRead("1234.56");
            List<BillPaymentResponse> everyPath = List.of(
                    controller.mainPara(new BillPaymentRequest()).response(),
                    controller.mainPara(firstEntry()).response(),
                    controller.mainPara(reentry(AidKey.ENTER.token(), ACCT_KEY, " ")).response(),
                    controller.mainPara(reentry(AidKey.ENTER.token(), "           ", " ")).response(),
                    controller.mainPara(reentry(AidKey.PFK03.token(), ACCT_KEY, " ")).response(),
                    controller.mainPara(reentry(AidKey.PFK04.token(), ACCT_KEY, " ")).response(),
                    controller.mainPara(reentry(AidKey.PFK12.token(), ACCT_KEY, " ")).response());

            String red = Character.toString(BmsAttributes.unsigned(BmsAttributes.DFHRED));
            for (BillPaymentResponse response : everyPath) {
                Assertions.assertThat(response.getMessageHighlight()).isNotEqualTo(red);
                // An absent member is the LOW-VALUES state and carries no asterisk by definition, so the
                // three fields CSSETATY would have marked are joined with absences dropped.
                String marked = String.join("",
                        String.valueOf(response.getActIdIn()).replace("null", ""),
                        String.valueOf(response.getConfirm()).replace("null", ""),
                        String.valueOf(response.getErrMsg()).replace("null", ""));
                Assertions.assertThat(marked).doesNotContain("*");
            }
        }
    }

    // =================================================================================================
    // The wire contract - gate G9 - and statelessness - gate G37.
    // =================================================================================================

    @Nested
    @DisplayName("the wire contract and statelessness")
    class Wire {

        @Test
        @DisplayName("POST /api/billpay answers 200 with the ten field projections and the commarea")
        void postReturnsThePaintedScreen() throws Exception {
            stubAccountRead("1234.56");
            ObjectMapper mapper = new ObjectMapper();
            BillPaymentRequest request = reentry(AidKey.ENTER.token(), ACCT_KEY, " ");

            mockMvc(mapper).perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnName").value("CB00"))
                    .andExpect(jsonPath("$.pgmName").value("COBIL00C"))
                    .andExpect(jsonPath("$.curDate").value(EXPECTED_CURDATE))
                    .andExpect(jsonPath("$.curTime").value(EXPECTED_CURTIME))
                    .andExpect(jsonPath("$.actIdIn").value(ACCT_KEY))
                    .andExpect(jsonPath("$.curBal").value("+0000001234.56"))
                    .andExpect(jsonPath("$.cursorField").value("CONFIRM"))
                    .andExpect(jsonPath("$.nextMapset").value("COBIL00"))
                    .andExpect(jsonPath("$.nextMap").value("COBIL0A"))
                    .andExpect(jsonPath("$.navigationContext.pgmContext")
                            .value(NavigationContext.PGM_CONTEXT_REENTER))
                    // No length item, flag byte, attribute view or output-side attribute item.
                    .andExpect(jsonPath("$.actIdInL").doesNotExist())
                    .andExpect(jsonPath("$.actIdInA").doesNotExist())
                    .andExpect(jsonPath("$.curBalL").doesNotExist())
                    .andExpect(jsonPath("$.errMsgC").doesNotExist())
                    .andExpect(jsonPath("$.errMsgH").doesNotExist());
        }

        @Test
        @DisplayName("an absent body is the cold start, exactly as a body with no commarea is")
        void absentBodyIsTheColdStart() throws Exception {
            mockMvc(new ObjectMapper()).perform(post(BillPaymentController.BILL_PAY_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value("COSGN00C"))
                    .andExpect(jsonPath("$.navigationContext.fromProgram").value("COBIL00C"));
        }

        @Test
        @DisplayName("no conversation state is retained: no session is created and no cookie is set")
        void nothingIsRetainedBetweenCalls() throws Exception {
            stubAccountRead("1234.56");
            ObjectMapper mapper = new ObjectMapper();
            BillPaymentRequest request = reentry(AidKey.ENTER.token(), ACCT_KEY, " ");

            MvcResult result = mockMvc(mapper).perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn();

            Assertions.assertThat(result.getRequest().getSession(false)).isNull();
            Assertions.assertThat(result.getResponse().getCookies()).isEmpty();
            Assertions.assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        }

        @Test
        @DisplayName("two identical requests produce two identical responses")
        void identicalRequestsAreIdempotentlyProjected() throws Exception {
            stubAccountRead("1234.56");
            ObjectMapper mapper = new ObjectMapper();
            String body = mapper.writeValueAsString(reentry(AidKey.ENTER.token(), ACCT_KEY, " "));
            MockMvc mockMvc = mockMvc(mapper);

            String first = mockMvc.perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String second = mockMvc.perform(post(BillPaymentController.BILL_PAY_PATH)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

            Assertions.assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("the payload publishes exactly the members the screen contract declares")
        void publishesExactlyTheDeclaredMembers() throws Exception {
            stubAccountRead("1234.56");
            ObjectMapper mapper = new ObjectMapper();
            BillPaymentResponse response =
                    controller.mainPara(reentry(AidKey.ENTER.token(), ACCT_KEY, " ")).response();

            Map<String, Object> members =
                    mapper.convertValue(response, new TypeReference<Map<String, Object>>() { });

            Assertions.assertThat(members.keySet()).isEqualTo(EXPECTED_WIRE_MEMBERS);
        }

        @Test
        @DisplayName("every projected field is exactly its symbolic-map PICTURE width")
        void everyFieldIsAtItsDeclaredWidth() {
            stubAccountRead("1234.56");

            BillPaymentResponse response =
                    controller.mainPara(reentry(AidKey.ENTER.token(), ACCT_KEY, " ")).response();

            Assertions.assertThat(response.getTrnName()).hasSize(BillPaymentResponse.TRN_NAME_LENGTH);
            Assertions.assertThat(response.getTitle01()).hasSize(BillPaymentResponse.TITLE01_LENGTH);
            Assertions.assertThat(response.getCurDate()).hasSize(BillPaymentResponse.CUR_DATE_LENGTH);
            Assertions.assertThat(response.getPgmName()).hasSize(BillPaymentResponse.PGM_NAME_LENGTH);
            Assertions.assertThat(response.getTitle02()).hasSize(BillPaymentResponse.TITLE02_LENGTH);
            Assertions.assertThat(response.getCurTime()).hasSize(BillPaymentResponse.CUR_TIME_LENGTH);
            Assertions.assertThat(response.getActIdIn()).hasSize(BillPaymentResponse.ACT_ID_IN_LENGTH);
            Assertions.assertThat(response.getCurBal()).hasSize(BillPaymentResponse.CUR_BAL_LENGTH);
            Assertions.assertThat(response.getConfirm()).hasSize(BillPaymentResponse.CONFIRM_LENGTH);
            Assertions.assertThat(response.getErrMsg()).hasSize(BillPaymentResponse.ERR_MSG_LENGTH);
        }
    }

    // =================================================================================================
    // The COBOL semantics this class expresses once each.
    // =================================================================================================

    @Nested
    @DisplayName("the figurative-constant test, the widths and the EIBAID resolution")
    class Semantics {

        @ParameterizedTest(name = "[{0}] equals a figurative constant: {1}")
        @DisplayName("IF <field> = SPACES OR LOW-VALUES - both constants, tested separately")
        @CsvSource(value = {
            "'        ' | true",
            "''         | true",
            "'  A     ' | false",
            "'COMEN01C' | false"
        }, delimiter = '|')
        void spacesOrLowValues(String value, boolean expected) {
            Assertions.assertThat(BillPaymentController.isSpacesOrLowValues(value)).isEqualTo(expected);
        }

        @Test
        @DisplayName("an absent field equals a figurative constant, because an unfilled item is X'00'")
        void absentFieldEqualsAFigurativeConstant() {
            Assertions.assertThat(BillPaymentController.isSpacesOrLowValues(null)).isTrue();
        }

        @Test
        @DisplayName("a field of X'00' equals LOW-VALUES but not SPACES")
        void lowValuesFieldEqualsTheConstant() {
            Assertions.assertThat(BillPaymentController.isSpacesOrLowValues(lowValues(8))).isTrue();
        }

        @Test
        @DisplayName("a partially blank field equals neither constant")
        void partiallyBlankFieldEqualsNeither() {
            Assertions.assertThat(BillPaymentController.isSpacesOrLowValues("00000000011     "))
                    .isFalse();
            Assertions.assertThat(BillPaymentController.isSpacesOrLowValues("A\u0000\u0000")).isFalse();
        }

        @Test
        @DisplayName("SPACES and LOW-VALUES render at any declared width")
        void figurativeConstantsRenderAtWidth() {
            Assertions.assertThat(BillPaymentController.spaces(0)).isEmpty();
            Assertions.assertThat(BillPaymentController.spaces(78))
                    .hasSize(78)
                    .isEqualTo(String.valueOf(BillPaymentController.SPACE).repeat(78));
            Assertions.assertThat(BillPaymentController.lowValues(0)).isEmpty();
            Assertions.assertThat(BillPaymentController.lowValues(11))
                    .hasSize(11)
                    .isEqualTo(String.valueOf(BillPaymentController.LOW_VALUE).repeat(11));
        }

        @ParameterizedTest(name = "the token {0} resolves to EIBAID {1}")
        @DisplayName("the CCARD-AID token resolves to the raw EIBAID byte the EVALUATE compares")
        @CsvSource({
            "ENTER, 125",
            "CLEAR, 109",
            "PFK01, -15",
            "PFK03, -13",
            "PFK04, -12",
            "PFK12, 124"
        })
        void tokensResolveToTheirBytes(String token, byte expected) {
            Assertions.assertThat(controller.eibAidOf(token)).isEqualTo(expected);
        }

        @Test
        @DisplayName("an unpadded token resolves, because the field's PIC X rule pads it first")
        void unpaddedTokenResolves() {
            Assertions.assertThat(controller.eibAidOf("PA1")).isEqualTo(CicsAid.DFHPA1);
            Assertions.assertThat(controller.eibAidOf(AidKey.PA1.token())).isEqualTo(CicsAid.DFHPA1);
            Assertions.assertThat(AidKey.PA1.token()).hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @Test
        @DisplayName("an absent or empty token becomes DFHENTER, the only honest default")
        void absentTokenBecomesEnter() {
            Assertions.assertThat(controller.eibAidOf(null)).isEqualTo(CicsAid.DFHENTER);
            Assertions.assertThat(controller.eibAidOf("")).isEqualTo(CicsAid.DFHENTER);
        }

        @Test
        @DisplayName("a token naming no key becomes DFHNULL, which matches no arm of the EVALUATE")
        void unknownTokenBecomesNull() {
            byte resolved = controller.eibAidOf("ZZZZZ");

            Assertions.assertThat(resolved).isEqualTo(CicsAid.DFHNULL);
            Assertions.assertThat(PfKeyResolver.isEnter(resolved)).isFalse();
            Assertions.assertThat(PfKeyResolver.isPf3(resolved)).isFalse();
            Assertions.assertThat(PfKeyResolver.isPf4(resolved)).isFalse();
        }

        @Test
        @DisplayName("RETURN-TO-PREV-SCREEN substitutes COSGN00C for a blank CDEMO-TO-PROGRAM")
        void returnToPrevScreenSubstitutesSignOn() {
            BillPaymentResponse response = new BillPaymentResponse();

            NavigationContext stamped =
                    controller.returnToPrevScreen(response, NavigationContext.empty());

            Assertions.assertThat(stamped.toProgram()).isEqualTo("COSGN00C");
            Assertions.assertThat(response.getNextProgram()).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("RETURN-TO-PREV-SCREEN keeps a CDEMO-TO-PROGRAM that names a target")
        void returnToPrevScreenKeepsANamedTarget() {
            BillPaymentResponse response = new BillPaymentResponse();

            NavigationContext stamped = controller.returnToPrevScreen(response,
                    NavigationContext.empty().withToProgram("COMEN01C"));

            Assertions.assertThat(stamped.toProgram()).isEqualTo("COMEN01C");
            Assertions.assertThat(response.getNextProgram()).isEqualTo("COMEN01C");
        }

        @Test
        @DisplayName("the Invocation carrier refuses a half-built outcome")
        void invocationRefusesNulls() {
            PaymentState state = new PaymentState(service.codec(), NavigationContext.empty());

            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new Invocation(null, state));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new Invocation(new BillPaymentResponse(), null));
        }

        @Test
        @DisplayName("the highest existing TRAN-ID is read through the service, not through this class")
        void theTransactionIdentifierIsTheServicesConcern() {
            stubConfirmedPayment("1234.56");
            when(browse.readPrev()).thenReturn(TransactionRepository.ReadResult.found(
                    TransactionRepository.CICS_FILE_NAME, tran("0000000000000041")));

            Invocation outcome = controller.mainPara(reentry(AidKey.ENTER.token(), ACCT_KEY, "Y"));

            Assertions.assertThat(outcome.state().tranIdNum()).isEqualTo(42L);
            Assertions.assertThat(outcome.response().getErrMsg())
                    .contains("Your Transaction ID is 0000000000000042");
        }
    }
}
