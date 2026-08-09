package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.config.WebConfig;
import com.vsergeychik.carddemo.transaction.TransactionViewController.ProgramState;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewRequest;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse.ScreenField;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TransactionViewController} - the {@code COTRN02C} / {@code CT02} screen, which despite its
 * mandated name <strong>adds</strong> a transaction (risk R-B).
 *
 * <p>Every test that asserts a <em>decision</em> instantiates the controller <strong>directly</strong>
 * with mocked repositories, a real {@link DateUtilityJob} and a fixed {@link Clock}. There is no Spring
 * context and no {@code MockMvc} in the decision path, which is gate <strong>G51</strong>: the guard
 * chains are asserted where they live, so a failure names the paragraph rather than an HTTP status. One
 * nested class exercises the HTTP adapter, and only the adapter.
 *
 * <p>{@link DateUtilityJob} is used for real rather than stubbed, because it is deterministic and
 * because the three {@code CSUTLDTC} outcomes this screen distinguishes are reachable with three real
 * dates: {@code 2022-07-18} is valid, {@code 1500-01-01} precedes the Lillian epoch and so reports
 * severity 3 with message <strong>2513</strong> - the one error both call sites tolerate - and
 * {@code 2022-13-01} reports severity 3 with message 2517, which they reject. Stubbing would have
 * asserted the controller against a fiction.
 */
@DisplayName("TransactionViewController - COTRN02C / CT02, which adds a transaction (risk R-B)")
class TransactionViewControllerTest {

    /** A fixed instant, so every {@code FUNCTION CURRENT-DATE} read sees the same second. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:33Z"), ZoneOffset.UTC);

    /** US-ASCII: the fixtures' code page, and the one the parity harness seeds from. */
    private static final Charset CHARSET = StandardCharsets.US_ASCII;

    /** An eleven-digit account id at its declared {@code PIC X(11)} width. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A sixteen-digit card number at its declared {@code PIC X(16)} width. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** A valid ten-character date the real {@code CSUTLDTC} accepts with severity {@code '0000'}. */
    private static final String VALID_DATE = "2022-07-18";

    /** A date before the Lillian epoch, which {@code CSUTLDTC} reports as 2513 and both sites tolerate. */
    private static final String TOLERATED_DATE = "1500-01-01";

    /** A date with month 13, which {@code CSUTLDTC} reports as 2517 and both sites reject. */
    private static final String REJECTED_DATE = "2022-13-01";

    private TransactionRepository transactionRepository;
    private CardXrefRepository cardXrefRepository;
    private DateUtilityJob dateUtilityJob;
    private TransactionViewController controller;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        cardXrefRepository = mock(CardXrefRepository.class);
        dateUtilityJob = new DateUtilityJob(CHARSET);
        controller = new TransactionViewController(transactionRepository, cardXrefRepository,
                dateUtilityJob, FIXED_CLOCK, CHARSET);
    }

    // =================================================================================================
    // Helpers
    // =================================================================================================

    /** @return a re-enter communication area, which is what every keyed interaction arrives with */
    private static NavigationContext reenterCommarea() {
        return NavigationContext.empty().withPgmReenter();
    }

    /** @return a request that has arrived with a communication area and the Enter key */
    private static TransactionViewRequest enterRequest() {
        TransactionViewRequest request = new TransactionViewRequest();
        request.setNavigationContext(reenterCommarea());
        request.setAid(PfKeyResolver.AidKey.ENTER.token());
        return request;
    }

    /** @return a fully and validly filled screen, confirmed, ready to be added */
    private static TransactionViewRequest completeRequest() {
        TransactionViewRequest request = enterRequest();
        request.setActidin(ACCOUNT_ID);
        request.setCardnin(TransactionViewRequest.spaces(TransactionViewRequest.CARDNIN_LENGTH));
        request.setTtypcd("01");
        request.setTcatcd("0001");
        request.setTrnsrc("POS TERM  ");
        request.setTdesc("Coffee and a newspaper");
        request.setTrnamt("+00000012.34");
        request.setTorigdt(VALID_DATE);
        request.setTprocdt(VALID_DATE);
        request.setMid("000123456");
        request.setMname("Kwik-E-Mart");
        request.setMcity("Springfield");
        request.setMzip("0000012345");
        request.setConfirm("Y");
        return request;
    }

    /** Makes the cross-reference read by account id succeed, returning {@link #CARD_NUMBER}. */
    private void xrefByAccountFound() {
        when(cardXrefRepository.readByAccountIdViaAltIndex(anyString()))
                .thenReturn(xrefFound(
                        CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                        new CardXrefRecord(CARD_NUMBER, 123_456_789, 11L)));
    }

    /** Makes the cross-reference read by card number succeed, returning account 11. */
    private void xrefByCardFound() {
        when(cardXrefRepository.readByCardNumber(anyString()))
                .thenReturn(xrefFound(CardXrefRepository.BASE_DD_NAME,
                        new CardXrefRecord(CARD_NUMBER, 123_456_789, 11L)));
    }

    /**
     * Positions a backward browse whose single {@code READPREV} yields the given outcome.
     *
     * @param result what the read reports
     * @return the browse handle, so a caller can verify {@code ENDBR}
     */
    private TransactionRepository.Browse browseYielding(TransactionRepository.ReadResult result) {
        TransactionRepository.Browse browse = mock(TransactionRepository.Browse.class);
        when(browse.readPrev()).thenReturn(result);
        when(transactionRepository.startBrowse(TransactionRepository.BrowseDirection.BACKWARD))
                .thenReturn(browse);
        return browse;
    }

    /** @return a 350-byte record whose {@code TRAN-ID} is the given key */
    private static TranRecord recordWithId(String tranId) {
        TranRecord record = new TranRecord(CHARSET);
        record.moveTranId(tranId);
        return record;
    }

    /** Positions a browse that reports one record with key {@code 0000000000000050}. */
    private TransactionRepository.Browse browseWithLastId() {
        return browseYielding(TransactionRepository.ReadResult.found(
                TransactionRepository.CICS_FILE_NAME, recordWithId("0000000000000050")));
    }

    /** Makes the write succeed. */
    private void writeSucceeds() {
        when(transactionRepository.write(any()))
                .thenReturn(TransactionRepository.WriteResult.written(
                        TransactionRepository.CICS_FILE_NAME));
    }

    // =================================================================================================
    // Risk R-B: the name contradicts the program, and that has to be recorded in the source itself.
    // =================================================================================================

    @Nested
    @DisplayName("Risk R-B - the class name says view and the program adds")
    class RiskRb {

        /** @return the controller's own source text */
        private String source() throws IOException {
            Path file = Path.of("src/main/java/com/vsergeychik/carddemo/transaction",
                    "TransactionViewController.java");
            assertThat(file).as("the controller's source, read relative to the module root").exists();
            return Files.readString(file, StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("the class documentation names risk R-B and quotes the source's Function header")
        void documentationRecordsTheSwap() throws IOException {
            String text = source();

            assertThat(text)
                    .as("practice B4 requires the conflict to be surfaced in the code, not only in a plan")
                    .contains("risk R-B")
                    .contains("Function    : Add a new Transaction to TRANSACT file")
                    .contains("README.md")
                    .contains("Transaction Add");
        }

        @Test
        @DisplayName("it states explicitly that this controller validates and inserts")
        void documentationStatesTheBehaviour() throws IOException {
            assertThat(source())
                    .as("a reader must not be able to infer read-only behaviour from the class name")
                    .contains("this controller validates and inserts a transaction record");
        }

        @Test
        @DisplayName("it names the sibling that carries the mirror-image swap")
        void documentationNamesTheSibling() throws IOException {
            assertThat(source()).contains("TransactionAddController");
        }

        @Test
        @DisplayName("the mandated name is honoured verbatim")
        void theMandatedNameIsHonoured() {
            assertThat(TransactionViewController.class.getSimpleName())
                    .isEqualTo("TransactionViewController");
        }

        @Test
        @DisplayName("it records that no user rules exist, so the bar is the twelve practices")
        void documentationRecordsTheAbsenceOfRules() throws IOException {
            assertThat(source()).contains("No user rules provided.");
        }
    }

    // =================================================================================================
    // Identity, wiring and the dead declarations.
    // =================================================================================================

    @Nested
    @DisplayName("Identity, wiring and the five dead declarations")
    class IdentityAndWiring {

        @Test
        @DisplayName("the program and transaction identifiers are the source's own")
        void identifiers() {
            assertThat(TransactionViewController.PROGRAM_NAME).isEqualTo("COTRN02C");
            assertThat(TransactionViewController.TRANSACTION_ID).isEqualTo("CT02");
            assertThat(TransactionViewController.TRANSACTIONS_PATH).isEqualTo("/api/transactions");
            assertThat(TransactionViewController.SIGN_ON_PROGRAM).isEqualTo("COSGN00C");
            assertThat(TransactionViewController.MAIN_MENU_PROGRAM).isEqualTo("COMEN01C");
        }

        @Test
        @DisplayName("the three live dataset literals come from the repositories, so they cannot drift")
        void datasetLiterals() {
            assertThat(TransactionViewController.WS_TRANSACT_FILE).isEqualTo("TRANSACT");
            assertThat(TransactionViewController.WS_CCXREF_FILE).isEqualTo("CCXREF");
            assertThat(TransactionViewController.WS_CXACAIX_FILE).isEqualTo("CXACAIX");
        }

        @Test
        @DisplayName("the two ACCTDAT declarations survive, and no account access is performed")
        void deadAccountDeclarationsSurvive() {
            assertThat(TransactionViewController.WS_ACCTDAT_FILE)
                    .as("WS-ACCTDAT-FILE at COTRN02C:40, declared and never referenced")
                    .isEqualTo(AccountRepository.CICS_FILE_NAME)
                    .isEqualTo("ACCTDAT");
            assertThat(TransactionViewController.COPIED_ACCOUNT_RECORD_LENGTH)
                    .as("COPY CVACT01Y at COTRN02C:89, copied and never referenced")
                    .isEqualTo(AccountRecord.RECORD_LENGTH)
                    .isEqualTo(300);
        }

        @Test
        @DisplayName("the two dead working-storage items survive with their declared values")
        void deadWorkingStorageSurvives() {
            assertThat(TransactionViewController.WS_TRAN_AMT_PICTURE).isEqualTo("+99999999.99");
            assertThat(TransactionViewController.WS_TRAN_DATE_VALUE).isEqualTo("00/00/00");
        }

        @Test
        @DisplayName("the edit mask is eight integer digits wide, one fewer than the record")
        void theEditMaskIsNarrowerThanTheRecord() {
            assertThat(TransactionViewController.WS_TRAN_AMT_E_INTEGER_DIGITS).isEqualTo(8);
            assertThat(TransactionViewController.WS_TRAN_AMT_N_INTEGER_DIGITS).isEqualTo(9);
            assertThat(TransactionViewController.WS_TRAN_AMT_E_LENGTH)
                    .isEqualTo(TransactionViewRequest.TRNAMT_LENGTH)
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("the CSUTLDTC acceptance rule is '0000' or message 2513")
        void csutldtcRule() {
            assertThat(TransactionViewController.CSUTLDTC_SEVERITY_OK).isEqualTo("0000");
            assertThat(TransactionViewController.CSUTLDTC_TOLERATED_MESSAGE_NUMBER).isEqualTo("2513");
        }

        @Test
        @DisplayName("the title width agrees with COTTL01Y, and is not CSMSG01Y's message width")
        void titleWidth() {
            assertThat(TransactionViewController.SCREEN_TITLE_LENGTH)
                    .isEqualTo(ScreenTitles.TITLE_LENGTH)
                    .isEqualTo(40)
                    .isNotEqualTo(SystemMessages.MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("the wiring constructor takes the code page from the repository it writes through")
        void wiringConstructorDerivesTheCharset() {
            when(transactionRepository.datasetCharset()).thenReturn(CHARSET);

            TransactionViewController wired = new TransactionViewController(transactionRepository,
                    cardXrefRepository, dateUtilityJob, FIXED_CLOCK);

            assertThat(wired.codec().charset()).isEqualTo(CHARSET);
        }

        @Test
        @DisplayName("a repository that reports no code page is refused rather than defaulted")
        void wiringConstructorRefusesAnAbsentCharset() {
            when(transactionRepository.datasetCharset()).thenReturn(null);

            assertThatThrownBy(() -> new TransactionViewController(transactionRepository,
                    cardXrefRepository, dateUtilityJob, FIXED_CLOCK))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("no dataset code page");
        }

        @Test
        @DisplayName("every collaborator is required, and each absence names what it was needed for")
        void everyCollaboratorIsRequired() {
            assertThatThrownBy(() -> new TransactionViewController(null, cardXrefRepository,
                    dateUtilityJob, FIXED_CLOCK, CHARSET))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("TransactionRepository");
            assertThatThrownBy(() -> new TransactionViewController(transactionRepository, null,
                    dateUtilityJob, FIXED_CLOCK, CHARSET))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("CardXrefRepository");
            assertThatThrownBy(() -> new TransactionViewController(transactionRepository,
                    cardXrefRepository, null, FIXED_CLOCK, CHARSET))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("DateUtilityJob");
            assertThatThrownBy(() -> new TransactionViewController(transactionRepository,
                    cardXrefRepository, dateUtilityJob, null, CHARSET))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Clock");
            assertThatThrownBy(() -> new TransactionViewController(transactionRepository,
                    cardXrefRepository, dateUtilityJob, FIXED_CLOCK, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("dataset charset");
            assertThatThrownBy(() -> new TransactionViewController(null, cardXrefRepository,
                    dateUtilityJob, FIXED_CLOCK))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("TransactionRepository");
        }

        @Test
        @DisplayName("mainPara refuses a null request rather than painting an empty screen")
        void mainParaRequiresARequest() {
            assertThatThrownBy(() -> controller.mainPara(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("communication area");
        }
    }

    // =================================================================================================
    // MAIN-PARA - COTRN02C:107-159.
    // =================================================================================================

    @Nested
    @DisplayName("MAIN-PARA - the six arms of L107-159")
    class MainPara {

        @Test
        @DisplayName("EIBCALEN = 0 hands control to the sign-on program and touches no dataset")
        void noCommareaGoesToSignOn() {
            ProgramState state = controller.mainPara(new TransactionViewRequest());

            assertThat(state.transferred()).isTrue();
            assertThat(state.returned()).isFalse();
            assertThat(state.taskEnded()).isTrue();
            assertThat(state.response().getNextProgram()).isEqualTo("COSGN00C");
            assertThat(state.commarea().fromProgram()).isEqualTo("COTRN02C");
            assertThat(state.commarea().fromTranid()).isEqualTo("CT02");
            assertThat(state.commarea().isEnter()).isTrue();
            verifyNoInteractions(transactionRepository, cardXrefRepository);
        }

        @Test
        @DisplayName("first entry paints the screen, sets the cursor and asks for nothing else")
        void firstEntryPaints() {
            TransactionViewRequest request = new TransactionViewRequest();
            request.setNavigationContext(NavigationContext.empty());

            ProgramState state = controller.mainPara(request);

            assertThat(state.returned()).isTrue();
            assertThat(state.transferred()).isFalse();
            assertThat(state.screenSent()).isTrue();
            assertThat(state.commarea().isReenter())
                    .as("L121 sets the re-enter context so the next call reads the map")
                    .isTrue();
            assertThat(state.cursorRequestedOn(ScreenField.ACTIDIN)).isTrue();
            assertThat(state.response().getNextProgram()).isEqualTo("COTRN02C");
            assertThat(state.response().getNextMapset()).isEqualTo("COTRN02");
            assertThat(state.response().getNextMap()).isEqualTo("COTRN2A");
            assertThat(state.errFlagOff()).isTrue();
            verifyNoInteractions(transactionRepository, cardXrefRepository);
        }

        @Test
        @DisplayName("first entry carrying a CT02 selection moves it into the CARD field and processes it")
        void firstEntryWithASelection() {
            TransactionViewRequest request = new TransactionViewRequest();
            request.setNavigationContext(NavigationContext.empty());
            request.getCt02Info().setTrnSelected(CARD_NUMBER);
            xrefByCardFound();

            ProgramState state = controller.mainPara(request);

            assertThat(state.cardninI())
                    .as("L126-127 moves the selection into CARDNINI, quirk and all")
                    .isEqualTo(CARD_NUMBER);
            assertThat(state.actidinI())
                    .as("the cross-reference then writes the account back at L223")
                    .isEqualTo(ACCOUNT_ID);
            assertThat(state.errmsgO())
                    .as("PROCESS-ENTER-KEY runs the detail edits at L166 BEFORE it evaluates CONFIRMI "
                            + "at L168, so an unfilled form reports the first empty field, not the "
                            + "confirmation prompt")
                    .startsWith("Type CD can NOT be empty...");
            assertThat(state.taskEnded()).isTrue();
        }

        @Test
        @DisplayName("PF3 with no recorded caller goes to the main menu")
        void pf3WithoutACaller() {
            TransactionViewRequest request = enterRequest();
            request.setAid(PfKeyResolver.AidKey.PFK03.token());

            ProgramState state = controller.mainPara(request);

            assertThat(state.transferred()).isTrue();
            assertThat(state.response().getNextProgram()).isEqualTo("COMEN01C");
        }

        @Test
        @DisplayName("PF3 with a recorded caller goes back to that caller")
        void pf3WithACaller() {
            TransactionViewRequest request = enterRequest();
            request.setAid(PfKeyResolver.AidKey.PFK03.token());
            request.setNavigationContext(reenterCommarea().withFromProgram("COTRN00C"));

            ProgramState state = controller.mainPara(request);

            assertThat(state.response().getNextProgram()).isEqualTo("COTRN00C");
        }

        @Test
        @DisplayName("PF4 blanks the form and repaints it")
        void pf4Clears() {
            TransactionViewRequest request = completeRequest();
            request.setAid(PfKeyResolver.AidKey.PFK04.token());

            ProgramState state = controller.mainPara(request);

            assertThat(state.actidinI()).isBlank();
            assertThat(state.cardninI()).isBlank();
            assertThat(state.confirmI()).isBlank();
            assertThat(state.mzipI()).isBlank();
            assertThat(state.message()).isBlank();
            assertThat(state.cursorRequestedOn(ScreenField.ACTIDIN)).isTrue();
            assertThat(state.screenSent()).isTrue();
            verifyNoInteractions(transactionRepository, cardXrefRepository);
        }

        @Test
        @DisplayName("an unrecognised key reports CSMSG01Y's invalid-key text")
        void anyOtherKeyIsInvalid() {
            TransactionViewRequest request = enterRequest();
            request.setAid(PfKeyResolver.AidKey.PFK12.token());

            ProgramState state = controller.mainPara(request);

            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message()).startsWith(SystemMessages.CCDA_MSG_INVALID_KEY.strip());
            assertThat(state.screenSent()).isTrue();
            verifyNoInteractions(transactionRepository, cardXrefRepository);
        }

        @Test
        @DisplayName("an absent AID token reaches the same invalid-key arm")
        void anAbsentAidIsInvalid() {
            TransactionViewRequest request = enterRequest();
            request.setAid(null);

            ProgramState state = controller.mainPara(request);

            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message()).startsWith(SystemMessages.CCDA_MSG_INVALID_KEY.strip());
        }

        @Test
        @DisplayName("the header is filled from the injected clock, never from a live one")
        void headerComesFromTheInjectedClock() {
            ProgramState state = controller.mainPara(enterRequest());

            assertThat(state.response().getTrnnameo()).isEqualTo("CT02");
            assertThat(state.response().getPgmnameo()).isEqualTo("COTRN02C");
            assertThat(state.response().getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(state.response().getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(state.response().getCurdateo()).isEqualTo("07/19/22");
            assertThat(state.response().getCurtimeo()).isEqualTo("23:12:33");
            assertThat(state.dateHeader()).isNotNull();
        }

        @Test
        @DisplayName("the 218-byte communication area returns whole, extension included")
        void theCommareaReturnsWhole() {
            TransactionViewRequest request = enterRequest();
            request.getCt02Info().setTrnidFirst("0000000000000001");
            request.getCt02Info().setTrnidLast("0000000000000010");
            request.getCt02Info().setPageNum(3);
            request.getCt02Info().setNextPageYes();
            request.getCt02Info().setTrnSelFlg("S");

            ProgramState state = controller.mainPara(request);

            assertThat(ProgramState.PASSED_COMMAREA_LENGTH).isEqualTo(218);
            assertThat(state.response().getCt02Info().getTrnidFirst()).isEqualTo("0000000000000001");
            assertThat(state.response().getCt02Info().getTrnidLast()).isEqualTo("0000000000000010");
            assertThat(state.response().getCt02Info().getPageNum()).isEqualTo(3);
            assertThat(state.response().getCt02Info().isNextPageYes()).isTrue();
            assertThat(state.response().getCt02Info().getTrnSelFlg()).isEqualTo("S");
        }
    }

    // =================================================================================================
    // VALIDATE-INPUT-KEY-FIELDS - COTRN02C:193-230.
    // =================================================================================================

    @Nested
    @DisplayName("VALIDATE-INPUT-KEY-FIELDS - the ordered EVALUATE of L193-230")
    class KeyFields {

        @Test
        @DisplayName("an account id resolves the card through the CXACAIX path and normalises in place")
        void accountIdResolvesTheCard() {
            TransactionViewRequest request = enterRequest();
            request.setActidin(ACCOUNT_ID);
            xrefByAccountFound();

            ProgramState state = controller.mainPara(request);

            assertThat(state.wsAcctIdN()).isEqualTo(11L);
            assertThat(state.actidinI())
                    .as("L206-207 writes the eleven-digit normalised form back over the screen field")
                    .isEqualTo(ACCOUNT_ID);
            assertThat(state.cardninI())
                    .as("L209 moves the cross-referenced card number into CARDNINI")
                    .isEqualTo(CARD_NUMBER);
            verify(cardXrefRepository).readByAccountIdViaAltIndex(ACCOUNT_ID);
            verify(cardXrefRepository, never()).readByCardNumber(anyString());
        }

        @Test
        @DisplayName("a card number resolves the account through the CCXREF base and normalises in place")
        void cardNumberResolvesTheAccount() {
            TransactionViewRequest request = enterRequest();
            request.setCardnin(CARD_NUMBER);
            xrefByCardFound();

            ProgramState state = controller.mainPara(request);

            assertThat(state.wsCardNumN()).isEqualTo(4_111_111_111_111_111L);
            assertThat(state.cardninI()).isEqualTo(CARD_NUMBER);
            assertThat(state.actidinI()).isEqualTo(ACCOUNT_ID);
            verify(cardXrefRepository).readByCardNumber(CARD_NUMBER);
            verify(cardXrefRepository, never()).readByAccountIdViaAltIndex(anyString());
        }

        @Test
        @DisplayName("the account id wins when both keys are entered, because EVALUATE is ordered")
        void theAccountIdWinsWhenBothAreEntered() {
            TransactionViewRequest request = enterRequest();
            request.setActidin(ACCOUNT_ID);
            request.setCardnin("9999999999999999");
            xrefByAccountFound();

            controller.mainPara(request);

            verify(cardXrefRepository).readByAccountIdViaAltIndex(ACCOUNT_ID);
            verify(cardXrefRepository, never()).readByCardNumber(anyString());
        }

        @Test
        @DisplayName("a part-filled account id is non-numeric, because the class test rejects spaces")
        void aPartFilledAccountIdIsNonNumeric() {
            TransactionViewRequest request = enterRequest();
            request.setActidin("11         ");

            ProgramState state = controller.mainPara(request);

            assertThat(TransactionViewController.isNumericClass("11         "))
                    .as("IS NUMERIC over PIC X(11) requires all eleven positions to be digits")
                    .isFalse();
            assertThat(state.message()).startsWith("Account ID must be Numeric...");
            verifyNoInteractions(cardXrefRepository);
        }

        @Test
        @DisplayName("a part-filled card number is non-numeric for the same reason")
        void aPartFilledCardNumberIsNonNumeric() {
            TransactionViewRequest request = enterRequest();
            request.setCardnin("4111            ");

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).startsWith("Card Number must be Numeric...");
            verifyNoInteractions(cardXrefRepository);
        }

        @Test
        @DisplayName("a non-numeric account id is rejected before any conversion")
        void nonNumericAccountId() {
            TransactionViewRequest request = enterRequest();
            request.setActidin("ABCDEFGHIJK");

            ProgramState state = controller.mainPara(request);

            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message()).startsWith("Account ID must be Numeric...");
            assertThat(state.cursorRequestedOn(ScreenField.ACTIDIN)).isTrue();
            assertThat(state.wsAcctIdN())
                    .as("the send at L202 ends the task, so L204's COMPUTE never runs")
                    .isZero();
            verifyNoInteractions(cardXrefRepository);
        }

        @Test
        @DisplayName("a non-numeric card number is rejected before any conversion")
        void nonNumericCardNumber() {
            TransactionViewRequest request = enterRequest();
            request.setCardnin("41111111111111X1");

            ProgramState state = controller.mainPara(request);

            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message()).startsWith("Card Number must be Numeric...");
            assertThat(state.cursorRequestedOn(ScreenField.CARDNIN)).isTrue();
            assertThat(state.wsCardNumN()).isZero();
            verifyNoInteractions(cardXrefRepository);
        }

        @Test
        @DisplayName("neither key entered reaches WHEN OTHER")
        void neitherKeyEntered() {
            ProgramState state = controller.mainPara(enterRequest());

            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message()).startsWith("Account or Card Number must be entered...");
            assertThat(state.cursorRequestedOn(ScreenField.ACTIDIN)).isTrue();
            verifyNoInteractions(cardXrefRepository);
        }

        @Test
        @DisplayName("an unknown account reports NOTFND with the cursor on the account field")
        void accountNotFound() {
            TransactionViewRequest request = enterRequest();
            request.setActidin(ACCOUNT_ID);
            when(cardXrefRepository.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.notFound(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME));

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).startsWith("Account ID NOT found...");
            assertThat(state.cursorRequestedOn(ScreenField.ACTIDIN)).isTrue();
            assertThat(state.displayLines())
                    .as("the NOTFND arm displays nothing; only WHEN OTHER does")
                    .isEmpty();
        }

        @Test
        @DisplayName("any other condition on the AIX path displays RESP and REAS and reports the failure")
        void accountLookupFails() {
            TransactionViewRequest request = enterRequest();
            request.setActidin(ACCOUNT_ID);
            when(cardXrefRepository.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.other(CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                            CardXrefRepository.PERMANENT_ERROR_STATUS));

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).startsWith("Unable to lookup Acct in XREF AIX file...");
            assertThat(state.displayLines()).hasSize(1);
            assertThat(state.displayLines().get(0)).startsWith("RESP:").contains("REAS:");
        }

        @Test
        @DisplayName("an end-of-file on the AIX path also reaches WHEN OTHER")
        void accountLookupEndOfFile() {
            TransactionViewRequest request = enterRequest();
            request.setActidin(ACCOUNT_ID);
            when(cardXrefRepository.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.endOfFile(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME));

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).startsWith("Unable to lookup Acct in XREF AIX file...");
        }

        @Test
        @DisplayName("an unknown card reports NOTFND with the cursor on the card field")
        void cardNotFound() {
            TransactionViewRequest request = enterRequest();
            request.setCardnin(CARD_NUMBER);
            when(cardXrefRepository.readByCardNumber(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.notFound(CardXrefRepository.BASE_DD_NAME));

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).startsWith("Card Number NOT found...");
            assertThat(state.cursorRequestedOn(ScreenField.CARDNIN)).isTrue();
        }

        @Test
        @DisplayName("any other condition on the base cluster displays RESP and REAS")
        void cardLookupFails() {
            TransactionViewRequest request = enterRequest();
            request.setCardnin(CARD_NUMBER);
            when(cardXrefRepository.readByCardNumber(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.other(CardXrefRepository.BASE_DD_NAME,
                            CardXrefRepository.PERMANENT_ERROR_STATUS));

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).startsWith("Unable to lookup Card # in XREF file...");
            assertThat(state.displayLines()).hasSize(1);
        }

        @Test
        @DisplayName("a read that claims to have found a record and carries none is diagnosed, not "
                + "silently treated as absent")
        void aSelfContradictoryReadIsDiagnosed() {
            CardXrefRepository.ReadResult contradictory = mock(CardXrefRepository.ReadResult.class);
            when(contradictory.isFound()).thenReturn(true);
            when(contradictory.record()).thenReturn(java.util.Optional.empty());
            when(contradictory.ddName()).thenReturn(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
            when(cardXrefRepository.readByAccountIdViaAltIndex(anyString()))
                    .thenReturn(contradictory);
            ProgramState state = new ProgramState(controller.codec());
            state.setXrefAcctId(ACCOUNT_ID);

            assertThatThrownBy(() -> controller.readCxacaixFile(state))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CXACAIX")
                    .hasMessageContaining("the outcome and the record disagree");
        }

        @Test
        @DisplayName("the base-cluster read diagnoses the same contradiction")
        void aSelfContradictoryBaseReadIsDiagnosed() {
            CardXrefRepository.ReadResult contradictory = mock(CardXrefRepository.ReadResult.class);
            when(contradictory.isFound()).thenReturn(true);
            when(contradictory.record()).thenReturn(java.util.Optional.empty());
            when(contradictory.ddName()).thenReturn(CardXrefRepository.BASE_DD_NAME);
            when(cardXrefRepository.readByCardNumber(anyString())).thenReturn(contradictory);
            ProgramState state = new ProgramState(controller.codec());
            state.setXrefCardNum(CARD_NUMBER);

            assertThatThrownBy(() -> controller.readCcxrefFile(state))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CCXREF");
        }

        @Test
        @DisplayName("a found record re-synchronises both RIDFLD images from the record itself")
        void aFoundRecordResynchronisesTheKeys() {
            TransactionViewRequest request = enterRequest();
            request.setActidin(ACCOUNT_ID);
            xrefByAccountFound();

            ProgramState state = controller.mainPara(request);

            assertThat(state.cardXrefRecord().xrefCardNum()).isEqualTo(CARD_NUMBER);
            assertThat(state.xrefCardNum()).isEqualTo(CARD_NUMBER);
            assertThat(state.xrefAcctId()).isEqualTo(ACCOUNT_ID);
        }
    }

    // =================================================================================================
    // VALIDATE-INPUT-DATA-FIELDS - COTRN02C:235-437.
    // =================================================================================================

    @Nested
    @DisplayName("VALIDATE-INPUT-DATA-FIELDS - every edit of L235-437, in the source's order")
    class DataFields {

        /** @return a screen whose keys resolve, so every test here reaches the detail edits */
        private TransactionViewRequest keyedRequest() {
            xrefByAccountFound();
            TransactionViewRequest request = completeRequest();
            request.setConfirm("Y");
            return request;
        }

        @ParameterizedTest(name = "{0} empty -> \"{1}\"")
        @CsvSource({
            "TTYPCD,  Type CD can NOT be empty...",
            "TCATCD,  Category CD can NOT be empty...",
            "TRNSRC,  Source can NOT be empty...",
            "TDESC,   Description can NOT be empty...",
            "TRNAMT,  Amount can NOT be empty...",
            "TORIGDT, Orig Date can NOT be empty...",
            "TPROCDT, Proc Date can NOT be empty...",
            "MID,     Merchant ID can NOT be empty...",
            "MNAME,   Merchant Name can NOT be empty...",
            "MCITY,   Merchant City can NOT be empty...",
            "MZIP,    Merchant Zip can NOT be empty...",
        })
        @DisplayName("the eleven-arm empty cascade, in order")
        void theEmptyCascade(ScreenField field, String message) {
            TransactionViewRequest request = keyedRequest();
            request.setPayloadValue(TransactionViewRequest.ScreenField.valueOf(field.name()),
                    TransactionViewRequest.spaces(field.width()));

            ProgramState state = controller.mainPara(request);

            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message()).startsWith(message);
            assertThat(state.cursorRequestedOn(field)).isTrue();
            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a non-numeric type code is rejected")
        void nonNumericTypeCode() {
            TransactionViewRequest request = keyedRequest();
            request.setTtypcd("AB");

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).startsWith("Type CD must be Numeric...");
            assertThat(state.cursorRequestedOn(ScreenField.TTYPCD)).isTrue();
        }

        @Test
        @DisplayName("a non-numeric category code is rejected")
        void nonNumericCategoryCode() {
            TransactionViewRequest request = keyedRequest();
            request.setTcatcd("00A1");

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).startsWith("Category CD must be Numeric...");
            assertThat(state.cursorRequestedOn(ScreenField.TCATCD)).isTrue();
        }

        @ParameterizedTest(name = "amount \"{0}\" is malformed")
        @ValueSource(strings = {
            "000000012.34",
            "+0000001A.34",
            "+00000012,34",
            "+00000012.3X",
            "*00000012.34",
        })
        @DisplayName("the positional amount check rejects each of its four clauses")
        void theAmountFormatCheck(String amount) {
            TransactionViewRequest request = keyedRequest();
            request.setTrnamt(amount);

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).startsWith("Amount should be in format -99999999.99");
            assertThat(state.cursorRequestedOn(ScreenField.TRNAMT)).isTrue();
        }

        @Test
        @DisplayName("a minus sign is accepted, because both signs are")
        void aNegativeAmountIsAccepted() {
            TransactionViewRequest request = keyedRequest();
            request.setTrnamt("-00000012.34");
            request.setConfirm("N");

            ProgramState state = controller.mainPara(request);

            assertThat(state.wsTranAmtN()).isEqualByComparingTo(new BigDecimal("-12.34"));
            assertThat(state.trnamtI())
                    .as("declining the confirmation leaves the normalised screen standing")
                    .isEqualTo("-00000012.34");
            verifyNoInteractions(transactionRepository);
        }

        @ParameterizedTest(name = "orig date \"{0}\" is malformed")
        @ValueSource(strings = {"20X2-07-18", "2022x07-18", "2022-0A-18", "2022-07x18", "2022-07-1A"})
        @DisplayName("the positional origination-date check rejects each of its five clauses")
        void theOrigDateFormatCheck(String date) {
            TransactionViewRequest request = keyedRequest();
            request.setTorigdt(date);

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).startsWith("Orig Date should be in format YYYY-MM-DD");
            assertThat(state.cursorRequestedOn(ScreenField.TORIGDT)).isTrue();
        }

        @ParameterizedTest(name = "proc date \"{0}\" is malformed")
        @ValueSource(strings = {"20X2-07-18", "2022x07-18", "2022-0A-18", "2022-07x18", "2022-07-1A"})
        @DisplayName("the positional processing-date check rejects each of its five clauses")
        void theProcDateFormatCheck(String date) {
            TransactionViewRequest request = keyedRequest();
            request.setTprocdt(date);

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).startsWith("Proc Date should be in format YYYY-MM-DD");
            assertThat(state.cursorRequestedOn(ScreenField.TPROCDT)).isTrue();
        }

        @Test
        @DisplayName("the amount is normalised in place through the edit mask")
        void theAmountIsNormalisedInPlace() {
            TransactionViewRequest request = keyedRequest();
            request.setTrnamt("+00000012.34");
            request.setConfirm("N");

            ProgramState state = controller.mainPara(request);

            assertThat(state.wsTranAmtE())
                    .as("L385 moves WS-TRAN-AMT-N through the edit mask")
                    .isEqualTo("+00000012.34");
            assertThat(state.trnamtI())
                    .as("L386 moves the edited form back over TRNAMTI")
                    .isEqualTo("+00000012.34");
        }

        @Test
        @DisplayName("a successful add blanks the normalised amount again, which is why the two "
                + "normalisation tests decline the confirmation")
        void aSuccessfulAddBlanksTheNormalisedAmount() {
            TransactionViewRequest request = keyedRequest();
            request.setTrnamt("+00000012.34");
            browseWithLastId();
            writeSucceeds();

            ProgramState state = controller.mainPara(request);

            assertThat(state.wsTranAmtE())
                    .as("the working-storage edit survives, because INITIALIZE-ALL-FIELDS is a map move")
                    .isEqualTo("+00000012.34");
            assertThat(state.trnamtI())
                    .as("but the screen field does not, because L745 blanks the form")
                    .isBlank();
        }

        @Test
        @DisplayName("CSUTLDTC severity 0000 accepts the origination date")
        void csutldtcAcceptsAValidOrigDate() {
            TransactionViewRequest request = keyedRequest();
            browseWithLastId();
            writeSucceeds();

            ProgramState state = controller.mainPara(request);

            assertThat(state.csutldtcResult()).isNotNull();
            assertThat(state.csutldtcResult().severityCode()).isEqualTo("0000");
            assertThat(state.csutldtcDateFormat()).isEqualTo("YYYY-MM-DD");
            assertThat(state.message()).doesNotContain("Not a valid date");
        }

        @Test
        @DisplayName("CSUTLDTC message 2513 is tolerated on the origination date")
        void csutldtc2513IsToleratedOnTheOrigDate() {
            TransactionViewRequest request = keyedRequest();
            request.setTorigdt(TOLERATED_DATE);
            browseWithLastId();
            writeSucceeds();

            ProgramState state = controller.mainPara(request);

            assertThat(dateUtilityJob.validateDate(TOLERATED_DATE, "YYYY-MM-DD").messageNumber())
                    .as("the tolerated date really does report 2513")
                    .isEqualTo("2513");
            assertThat(state.message())
                    .as("a tolerated date is not reported to the operator at all")
                    .doesNotContain("Orig Date - Not a valid date");
            verify(transactionRepository).write(any());
        }

        @Test
        @DisplayName("any other CSUTLDTC error rejects the origination date")
        void csutldtcRejectsAnInvalidOrigDate() {
            TransactionViewRequest request = keyedRequest();
            request.setTorigdt(REJECTED_DATE);

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).startsWith("Orig Date - Not a valid date...");
            assertThat(state.cursorRequestedOn(ScreenField.TORIGDT)).isTrue();
            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("CSUTLDTC message 2513 is tolerated on the processing date too")
        void csutldtc2513IsToleratedOnTheProcDate() {
            TransactionViewRequest request = keyedRequest();
            request.setTprocdt(TOLERATED_DATE);
            browseWithLastId();
            writeSucceeds();

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).doesNotContain("Proc Date - Not a valid date");
            verify(transactionRepository).write(any());
        }

        @Test
        @DisplayName("any other CSUTLDTC error rejects the processing date")
        void csutldtcRejectsAnInvalidProcDate() {
            TransactionViewRequest request = keyedRequest();
            request.setTprocdt(REJECTED_DATE);

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).startsWith("Proc Date - Not a valid date...");
            assertThat(state.cursorRequestedOn(ScreenField.TPROCDT)).isTrue();
        }

        @Test
        @DisplayName("a non-numeric merchant id is the last edit and is rejected")
        void nonNumericMerchantId() {
            TransactionViewRequest request = keyedRequest();
            request.setMid("00012345X");

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).startsWith("Merchant ID must be Numeric...");
            assertThat(state.cursorRequestedOn(ScreenField.MID)).isTrue();
            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("the unreachable IF ERR-FLG-ON block blanks the eleven detail fields, and only those")
        void theBlankingBlockIsPreserved() {
            ProgramState state = new ProgramState(controller.codec());
            state.setActidinI(ACCOUNT_ID);
            state.setCardninI(CARD_NUMBER);
            state.setConfirmI("Y");
            state.setTtypcdI("01");
            state.setMzipI("0000012345");
            state.setErrFlagOn();

            controller.blankDetailFields(state);

            assertThat(state.ttypcdI()).isBlank();
            assertThat(state.mzipI()).isBlank();
            assertThat(state.actidinI())
                    .as("L238-248 leaves the two key fields alone")
                    .isEqualTo(ACCOUNT_ID);
            assertThat(state.cardninI()).isEqualTo(CARD_NUMBER);
            assertThat(state.confirmI())
                    .as("and it leaves CONFIRMI alone, unlike INITIALIZE-ALL-FIELDS")
                    .isEqualTo("Y");
        }

        @Test
        @DisplayName("validateInputDataFields blanks the detail fields when it arrives with the flag set")
        void theBlankingBlockRunsFromTheParagraph() {
            ProgramState state = new ProgramState(controller.codec());
            state.setCommarea(reenterCommarea());
            state.setTtypcdI("01");
            state.setErrFlagOn();

            controller.validateInputDataFields(state);

            assertThat(state.ttypcdI()).isBlank();
            assertThat(state.message())
                    .as("with every detail field blank, the first empty arm reports the type code")
                    .startsWith("Type CD can NOT be empty...");
        }

        @Test
        @DisplayName("both paragraph methods refuse a null state")
        void nullStateIsRefused() {
            assertThatThrownBy(() -> controller.validateInputKeyFields(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ProgramState");
            assertThatThrownBy(() -> controller.validateInputDataFields(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.blankDetailFields(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.processEnterKey(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.addTransaction((ProgramState) null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.addTransaction((TransactionViewRequest) null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.copyLastTranData(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.returnToPrevScreen(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.sendTrnaddScreen(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.returnToCics(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.populateHeaderInfo(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.readCxacaixFile(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.readCcxrefFile(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.startbrTransactFile(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.readprevTransactFile(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.endbrTransactFile(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.writeTransactFile(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.clearCurrentScreen(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.initializeAllFields(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.displayRespAndReas(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("callCsutldtc and rejectAndSend refuse their own null arguments")
        void helperArgumentsAreRequired() {
            ProgramState state = new ProgramState(controller.codec());

            assertThatThrownBy(() -> controller.callCsutldtc(null, VALID_DATE))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.callCsutldtc(state, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("CSUTLDTC-DATE");
            assertThatThrownBy(() -> controller.rejectAndSend(state, null, ScreenField.MID))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("literal");
            assertThatThrownBy(() -> controller.rejectAndSend(state, "x", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("cursor");
            assertThatThrownBy(() -> controller.rejectAndSend(null, "x", ScreenField.MID))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.startbrOutcome(state, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("STARTBR outcome");
            assertThatThrownBy(() -> controller.startbrOutcome(null, FileStatus.Outcome.OK))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.receiveTrnaddScreen(state, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("received map");
            assertThatThrownBy(() -> controller.receiveTrnaddScreen(null, enterRequest()))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =================================================================================================
    // PROCESS-ENTER-KEY's CONFIRM evaluation - COTRN02C:169-188.
    // =================================================================================================

    @Nested
    @DisplayName("PROCESS-ENTER-KEY - the ordered EVALUATE CONFIRMI of L169-188")
    class ConfirmEvaluation {

        /** @return a valid, keyed screen carrying the given confirmation value */
        private TransactionViewRequest confirming(String value) {
            xrefByAccountFound();
            TransactionViewRequest request = completeRequest();
            request.setConfirm(value);
            return request;
        }

        @ParameterizedTest(name = "CONFIRM \"{0}\" adds the transaction")
        @ValueSource(strings = {"Y", "y"})
        @DisplayName("both cases of yes add the transaction")
        void yesAdds(String value) {
            browseWithLastId();
            writeSucceeds();

            ProgramState state = controller.mainPara(confirming(value));

            verify(transactionRepository).write(any());
            assertThat(state.errFlagOff()).isTrue();
            assertThat(state.errmsgO()).startsWith("Transaction added successfully.");
        }

        @ParameterizedTest(name = "CONFIRM \"{0}\" asks for confirmation")
        @ValueSource(strings = {"N", "n", " "})
        @DisplayName("no, and blank, ask the operator to confirm")
        void noAsksForConfirmation(String value) {
            ProgramState state = controller.mainPara(confirming(value));

            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message()).startsWith("Confirm to add this transaction...");
            assertThat(state.cursorRequestedOn(ScreenField.CONFIRM)).isTrue();
            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("low-values reaches the same arm as blank")
        void lowValuesAsksForConfirmation() {
            ProgramState state = controller.mainPara(
                    confirming(TransactionViewRequest.lowValues(1)));

            assertThat(state.message()).startsWith("Confirm to add this transaction...");
        }

        @Test
        @DisplayName("anything else reaches WHEN OTHER")
        void anythingElseIsInvalid() {
            ProgramState state = controller.mainPara(confirming("Q"));

            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message()).startsWith("Invalid value. Valid values are (Y/N)...");
            assertThat(state.cursorRequestedOn(ScreenField.CONFIRM)).isTrue();
            verifyNoInteractions(transactionRepository);
        }
    }

    // =================================================================================================
    // The TRANSACT browse and the next identifier - COTRN02C:442-449 and :642-706.
    // =================================================================================================

    @Nested
    @DisplayName("The next-identifier browse - L444-449 and L642-706")
    class NextIdentifier {

        /** @return a valid, keyed, confirmed screen */
        private TransactionViewRequest addRequest() {
            xrefByAccountFound();
            return completeRequest();
        }

        @Test
        @DisplayName("the highest existing key plus one becomes the new identifier")
        void theHighWaterMark() {
            TransactionRepository.Browse browse = browseWithLastId();
            writeSucceeds();

            ProgramState state = controller.mainPara(addRequest());

            assertThat(state.tranIdAtHighValues())
                    .as("L444 moves HIGH-VALUES into TRAN-ID before positioning")
                    .isTrue();
            assertThat(state.wsTranIdN()).isEqualTo(51L);
            assertThat(state.tranRecord().tranId()).isEqualTo("0000000000000051");
            verify(transactionRepository).startBrowse(
                    TransactionRepository.BrowseDirection.BACKWARD);
            verify(browse).endBrowse();
            assertThat(state.browseOpen()).isFalse();
        }

        @Test
        @DisplayName("an empty master yields identifier 1, because ENDFILE moves zeros into TRAN-ID")
        void anEmptyMasterYieldsOne() {
            browseYielding(TransactionRepository.ReadResult.endOfFile(
                    TransactionRepository.CICS_FILE_NAME));
            writeSucceeds();

            ProgramState state = controller.mainPara(addRequest());

            assertThat(state.wsTranIdN()).isEqualTo(1L);
            assertThat(state.tranRecord().tranId()).isEqualTo("0000000000000001");
            assertThat(state.errFlagOff()).isTrue();
        }

        @Test
        @DisplayName("any other condition on the read reports the lookup failure and displays RESP")
        void anyOtherConditionRejects() {
            browseYielding(TransactionRepository.ReadResult.other(
                    TransactionRepository.CICS_FILE_NAME,
                    TransactionRepository.PERMANENT_ERROR_STATUS));

            ProgramState state = controller.mainPara(addRequest());

            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message()).startsWith("Unable to lookup Transaction...");
            assertThat(state.cursorRequestedOn(ScreenField.ACTIDIN)).isTrue();
            assertThat(state.displayLines()).hasSize(1);
            verify(transactionRepository, never()).write(any());
        }

        @Test
        @DisplayName("a NOTFND on the read also reaches WHEN OTHER")
        void notFoundOnTheReadRejects() {
            browseYielding(TransactionRepository.ReadResult.notFound(
                    TransactionRepository.CICS_FILE_NAME));

            ProgramState state = controller.mainPara(addRequest());

            assertThat(state.message()).startsWith("Unable to lookup Transaction...");
        }

        @Test
        @DisplayName("the STARTBR EVALUATE has all three arms, and each carries its own literal")
        void startbrHasThreeArms() {
            ProgramState ok = new ProgramState(controller.codec());
            controller.startbrOutcome(ok, FileStatus.Outcome.OK);
            assertThat(ok.errFlagOff()).isTrue();
            assertThat(ok.taskEnded()).isFalse();

            ProgramState notFound = new ProgramState(controller.codec());
            controller.startbrOutcome(notFound, FileStatus.Outcome.NOT_FOUND);
            assertThat(notFound.message()).startsWith("Transaction ID NOT found...");
            assertThat(notFound.cursorRequestedOn(ScreenField.ACTIDIN)).isTrue();
            assertThat(notFound.displayLines()).isEmpty();

            ProgramState other = new ProgramState(controller.codec());
            controller.startbrOutcome(other, FileStatus.Outcome.OTHER);
            assertThat(other.message()).startsWith("Unable to lookup Transaction...");
            assertThat(other.displayLines()).hasSize(1);
        }

        @ParameterizedTest
        @EnumSource(FileStatus.Outcome.class)
        @DisplayName("every FileStatus outcome is classified by the STARTBR EVALUATE")
        void everyOutcomeIsClassified(FileStatus.Outcome outcome) {
            ProgramState state = new ProgramState(controller.codec());

            controller.startbrOutcome(state, outcome);

            assertThat(outcome == FileStatus.Outcome.OK ? state.errFlagOff() : state.errFlagOn())
                    .isTrue();
        }

        @Test
        @DisplayName("a READPREV with no positioned browse is refused with a diagnosis")
        void readWithoutABrowseIsRefused() {
            ProgramState state = new ProgramState(controller.codec());

            assertThatThrownBy(() -> controller.readprevTransactFile(state))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TRANSACT")
                    .hasMessageContaining("STARTBR");
        }

        @Test
        @DisplayName("a READPREV that fails outright still ends the browse ADD-TRANSACTION opened")
        void anUnmodelledReadFailureStillEndsTheBrowse() {
            // L446's EVALUATE classifies a CICS response; it does not wrap the command, so a
            // driver-level refusal propagates and bypasses L447's ENDBR. Under CICS the unended browse
            // costs nothing because task termination releases it, and there is no implicit release here.
            TransactionRepository.Browse browse = mock(TransactionRepository.Browse.class);
            when(browse.readPrev()).thenThrow(new IllegalStateException("the read was refused"));
            when(transactionRepository.startBrowse(TransactionRepository.BrowseDirection.BACKWARD))
                    .thenReturn(browse);
            ProgramState state = new ProgramState(controller.codec());

            assertThatThrownBy(() -> controller.addTransaction(state))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("the read was refused");

            verify(browse).endBrowse();
            assertThat(state.browseOpen())
                    .as("the request boundary released it, so no handle is left positioned")
                    .isFalse();
        }

        @Test
        @DisplayName("a rejecting STARTBR arm ends the task, and the browse is released anyway")
        void aRejectingStartbrStillReleasesTheBrowse() {
            // startbrOutcome's OTHER arm sends a map and ends the task, so L447's ENDBR is skipped by
            // the taskEnded guard rather than by an exception.
            TransactionRepository.Browse browse = mock(TransactionRepository.Browse.class);
            when(browse.readPrev()).thenReturn(TransactionRepository.ReadResult.endOfFile(
                    TransactionRepository.CICS_FILE_NAME));
            when(transactionRepository.startBrowse(TransactionRepository.BrowseDirection.BACKWARD))
                    .thenReturn(browse);
            ProgramState state = new ProgramState(controller.codec());
            state.openBrowse(browse);
            controller.startbrOutcome(state, FileStatus.Outcome.OTHER);

            assertThat(state.taskEnded()).isTrue();
            assertThat(state.browseOpen())
                    .as("startbrOutcome itself leaves the handle positioned")
                    .isTrue();
        }

        @Test
        @DisplayName("COPY-LAST-TRAN-DATA releases its browse on an outright read failure too")
        void copyLastTranDataAlsoReleasesTheBrowse() {
            // The same three-statement browse as ADD-TRANSACTION, at L476-L478, and its own try/finally.
            ProgramState state = new ProgramState(controller.codec());
            state.setCommarea(reenterCommarea());
            state.setActidinI(ACCOUNT_ID);
            xrefByAccountFound();
            TransactionRepository.Browse browse = mock(TransactionRepository.Browse.class);
            when(browse.readPrev()).thenThrow(new IllegalStateException("refused"));
            when(transactionRepository.startBrowse(TransactionRepository.BrowseDirection.BACKWARD))
                    .thenReturn(browse);

            assertThatThrownBy(() -> controller.copyLastTranData(state))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("refused");

            verify(browse).endBrowse();
            assertThat(state.browseOpen()).isFalse();
        }

        @Test
        @DisplayName("ending a browse that was never positioned does nothing")
        void endingAnUnopenedBrowseIsSafe() {
            ProgramState state = new ProgramState(controller.codec());

            controller.endbrTransactFile(state);

            assertThat(state.browseOpen()).isFalse();
        }

        @Test
        @DisplayName("openBrowse refuses a null handle")
        void openBrowseRefusesNull() {
            ProgramState state = new ProgramState(controller.codec());

            assertThatThrownBy(() -> state.openBrowse(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =================================================================================================
    // ADD-TRANSACTION and WRITE-TRANSACT-FILE - COTRN02C:442-466 and :711-749.
    // =================================================================================================

    @Nested
    @DisplayName("ADD-TRANSACTION and WRITE-TRANSACT-FILE - the record and the three write arms")
    class TheWrite {

        /** @return a valid, keyed, confirmed screen with a positioned browse and a successful write */
        private TransactionViewRequest readyToAdd() {
            xrefByAccountFound();
            browseWithLastId();
            writeSucceeds();
            return completeRequest();
        }

        @Test
        @DisplayName("the written record is exactly 350 characters with its FILLER space-filled")
        void theRecordIsThreeHundredAndFifty() {
            ProgramState state = controller.mainPara(readyToAdd());

            assertThat(state.writtenRecords()).hasSize(1);
            assertThat(state.writtenRecords().get(0))
                    .as("gates G19 and G21: the full copybook width, FILLER included")
                    .hasSize(TranRecord.RECORD_LENGTH)
                    .hasSize(350);
            assertThat(state.tranRecord().filler())
                    .isEqualTo(" ".repeat(TranRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("the thirteen moves land in the source's order and with the source's values")
        void theThirteenMoves() {
            ProgramState state = controller.mainPara(readyToAdd());
            TranRecord record = state.tranRecord();

            assertThat(record.tranId()).isEqualTo("0000000000000051");
            assertThat(record.tranTypeCd()).isEqualTo("01");
            assertThat(record.tranCatCd()).isEqualTo(1);
            assertThat(record.tranCatCdImage()).isEqualTo("0001");
            assertThat(record.tranSource()).isEqualTo("POS TERM  ");
            assertThat(record.tranAmt()).isEqualByComparingTo(new BigDecimal("12.34"));
            assertThat(record.tranCardNum()).isEqualTo(CARD_NUMBER);
            assertThat(record.tranMerchantId()).isEqualTo(123_456);
            assertThat(record.tranMerchantIdImage()).isEqualTo("000123456");
        }

        @Test
        @DisplayName("every cross-width move pads on the right, because the receivers are PIC X")
        void crossWidthMovesPadOnTheRight() {
            ProgramState state = controller.mainPara(readyToAdd());
            TranRecord record = state.tranRecord();

            assertThat(record.tranDesc())
                    .as("TDESCI X(60) into TRAN-DESC X(100)")
                    .hasSize(TranRecord.TRAN_DESC_LENGTH)
                    .startsWith("Coffee and a newspaper")
                    .endsWith(" ");
            assertThat(record.tranMerchantName())
                    .as("MNAMEI X(30) into X(50)")
                    .hasSize(TranRecord.TRAN_MERCHANT_NAME_LENGTH)
                    .startsWith("Kwik-E-Mart ");
            assertThat(record.tranMerchantCity())
                    .as("MCITYI X(25) into X(50)")
                    .hasSize(TranRecord.TRAN_MERCHANT_CITY_LENGTH)
                    .startsWith("Springfield ");
            assertThat(record.tranMerchantZip()).isEqualTo("0000012345");
            assertThat(record.tranOrigTs())
                    .as("TORIGDTI X(10) into TRAN-ORIG-TS X(26)")
                    .hasSize(TranRecord.TRAN_ORIG_TS_LENGTH)
                    .isEqualTo(VALID_DATE + " ".repeat(16));
            assertThat(record.tranProcTs())
                    .hasSize(TranRecord.TRAN_PROC_TS_LENGTH)
                    .isEqualTo(VALID_DATE + " ".repeat(16));
        }

        @Test
        @DisplayName("a successful write blanks the form, greens the error line and names the identifier")
        void aSuccessfulWrite() {
            ProgramState state = controller.mainPara(readyToAdd());

            assertThat(state.errFlagOff()).isTrue();
            assertThat(state.actidinI()).isBlank();
            assertThat(state.confirmI()).isBlank();
            assertThat(state.cursorRequestedOn(ScreenField.ACTIDIN)).isTrue();
            assertThat(state.response().getMetadata(ScreenField.ERRMSG).getColour())
                    .isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(state.message())
                    .as("two spaces, because both STRING literals carry one of their own")
                    .startsWith("Transaction added successfully.  Your Tran ID is 0000000000000051.")
                    .hasSize(TransactionViewController.WS_MESSAGE_LENGTH);
            assertThat(state.errmsgO())
                    .hasSize(TransactionViewResponse.ERRMSGO_LENGTH)
                    .startsWith("Transaction added successfully.  Your Tran ID is 0000000000000051.");
        }

        @Test
        @DisplayName("a duplicate key surfaces the source's message rather than an escaping exception")
        void aDuplicateKey() {
            xrefByAccountFound();
            browseWithLastId();
            when(transactionRepository.write(any())).thenReturn(
                    TransactionRepository.WriteResult.duplicate(
                            TransactionRepository.CICS_FILE_NAME));

            ProgramState state = controller.mainPara(completeRequest());

            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message()).startsWith("Tran ID already exist...");
            assertThat(state.cursorRequestedOn(ScreenField.ACTIDIN)).isTrue();
            assertThat(state.displayLines())
                    .as("the duplicate arm displays nothing; only WHEN OTHER does")
                    .isEmpty();
        }

        @Test
        @DisplayName("any other condition displays RESP and REAS and reports the failure")
        void anyOtherWriteCondition() {
            xrefByAccountFound();
            browseWithLastId();
            when(transactionRepository.write(any())).thenReturn(
                    TransactionRepository.WriteResult.other(TransactionRepository.CICS_FILE_NAME,
                            TransactionRepository.PERMANENT_ERROR_STATUS));

            ProgramState state = controller.mainPara(completeRequest());

            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message()).startsWith("Unable to Add Transaction...");
            assertThat(state.displayLines()).hasSize(1);
            // The write reported NO CICS response, so the response operand is not a number: rendering it
            // as nine zeros would say DFHRESP(NORMAL), on the arm reached only because the write failed.
            // The reason operand IS reported - zero means "no further reason" - so it stays numeric.
            assertThat(state.displayLines().get(0))
                    .matches("RESP:\\*{9}REAS:\\d{9}")
                    .doesNotContain("RESP:000000000");
            assertThat(state.respCd())
                    .isEqualTo(FileStatus.RESP_NOT_REPORTED)
                    .isNotEqualTo(FileStatus.NORMAL);
        }

        @Test
        @DisplayName("a write that DOES report a response renders that response as its number")
        void aReportedWriteResponseStaysNumeric() {
            // The other side of the sentinel: a reported DFHRESP must still render numerically, so the
            // asterisk image cannot be over-applied.
            xrefByAccountFound();
            browseWithLastId();
            when(transactionRepository.write(any())).thenReturn(
                    TransactionRepository.WriteResult.duplicate(
                            TransactionRepository.CICS_FILE_NAME));

            ProgramState state = controller.mainPara(completeRequest());

            assertThat(FileStatus.respReported(state.respCd())).isTrue();
            assertThat(state.displayLines())
                    .allSatisfy(line -> assertThat(line).matches("RESP:\\d{9}REAS:\\d{9}"));
        }

        @Test
        @DisplayName("the record image is recorded whatever the write reported")
        void theImageIsAlwaysRecorded() {
            xrefByAccountFound();
            browseWithLastId();
            when(transactionRepository.write(any())).thenReturn(
                    TransactionRepository.WriteResult.duplicate(
                            TransactionRepository.CICS_FILE_NAME));

            ProgramState state = controller.mainPara(completeRequest());

            assertThat(state.writtenRecords()).hasSize(1);
            assertThat(state.writtenRecords().get(0)).hasSize(350);
        }

        @Test
        @DisplayName("the written record list is unmodifiable, and refuses a null record")
        void theWrittenRecordListIsSafe() {
            ProgramState state = new ProgramState(controller.codec());

            assertThatThrownBy(() -> state.writtenRecords().add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> state.recordWritten(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.displayLines().add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> state.recordDisplay(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =================================================================================================
    // COPY-LAST-TRAN-DATA - COTRN02C:471-495.
    // =================================================================================================

    @Nested
    @DisplayName("COPY-LAST-TRAN-DATA - the PF5 convenience of L471-495")
    class CopyLastTranData {

        /** @return a fully populated 350-byte record to copy back onto the screen */
        private TranRecord populatedRecord() {
            TranRecord record = new TranRecord(CHARSET);
            record.moveTranId("0000000000000050");
            record.moveTranTypeCd("02");
            record.moveTranCatCd("0003");
            record.moveTranSource("ONLINE    ");
            record.moveTranDesc("Previous purchase");
            record.moveTranAmt(new BigDecimal("99.99"));
            record.moveTranCardNum(CARD_NUMBER);
            record.moveTranMerchantId("000987654");
            record.moveTranMerchantName("Moe's Tavern");
            record.moveTranMerchantCity("Shelbyville");
            record.moveTranMerchantZip("0000054321");
            record.moveTranOrigTs("2022-07-01");
            record.moveTranProcTs("2022-07-02");
            return record;
        }

        @Test
        @DisplayName("PF5 copies eleven fields back and then asks the operator to confirm")
        void pf5CopiesAndAsksForConfirmation() {
            xrefByAccountFound();
            browseYielding(TransactionRepository.ReadResult.found(
                    TransactionRepository.CICS_FILE_NAME, populatedRecord()));
            TransactionViewRequest request = enterRequest();
            request.setActidin(ACCOUNT_ID);
            request.setAid(PfKeyResolver.AidKey.PFK05.token());

            ProgramState state = controller.mainPara(request);

            assertThat(state.ttypcdI()).isEqualTo("02");
            assertThat(state.tcatcdI()).isEqualTo("0003");
            assertThat(state.trnsrcI()).isEqualTo("ONLINE    ");
            assertThat(state.trnamtI())
                    .as("the amount comes back through the eight-digit edit mask")
                    .isEqualTo("+00000099.99");
            assertThat(state.tdescI()).startsWith("Previous purchase");
            assertThat(state.torigdtI()).isEqualTo("2022-07-01");
            assertThat(state.tprocdtI()).isEqualTo("2022-07-02");
            assertThat(state.midI()).isEqualTo("000987654");
            assertThat(state.mnameI()).startsWith("Moe's Tavern");
            assertThat(state.mcityI()).startsWith("Shelbyville");
            assertThat(state.mzipI()).isEqualTo("0000054321");
            assertThat(state.message())
                    .as("CONFIRMI is untouched by this paragraph, so the enter processing asks")
                    .startsWith("Confirm to add this transaction...");
            verify(transactionRepository, never()).write(any());
        }

        @Test
        @DisplayName("PF5 on an already-confirmed screen copies and then adds")
        void pf5CopiesAndAdds() {
            xrefByAccountFound();
            browseYielding(TransactionRepository.ReadResult.found(
                    TransactionRepository.CICS_FILE_NAME, populatedRecord()));
            writeSucceeds();
            TransactionViewRequest request = enterRequest();
            request.setActidin(ACCOUNT_ID);
            request.setConfirm("Y");
            request.setAid(PfKeyResolver.AidKey.PFK05.token());

            ProgramState state = controller.mainPara(request);

            verify(transactionRepository).write(any());
            assertThat(state.errmsgO()).startsWith("Transaction added successfully.");
        }

        @Test
        @DisplayName("a key failure stops the copy before the browse")
        void aKeyFailureStopsTheCopy() {
            TransactionViewRequest request = enterRequest();
            request.setActidin("NOTNUMERIC ");
            request.setAid(PfKeyResolver.AidKey.PFK05.token());

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).startsWith("Account ID must be Numeric...");
            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a browse failure stops the copy before the copy-back")
        void aBrowseFailureStopsTheCopy() {
            xrefByAccountFound();
            browseYielding(TransactionRepository.ReadResult.other(
                    TransactionRepository.CICS_FILE_NAME,
                    TransactionRepository.PERMANENT_ERROR_STATUS));
            TransactionViewRequest request = enterRequest();
            request.setActidin(ACCOUNT_ID);
            request.setAid(PfKeyResolver.AidKey.PFK05.token());

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).startsWith("Unable to lookup Transaction...");
            assertThat(state.ttypcdI()).isBlank();
        }

        @Test
        @DisplayName("the IF NOT ERR-FLG-ON guard at L480 is preserved and skips the copy-back")
        void theErrFlagGuardIsPreserved() {
            ProgramState state = new ProgramState(controller.codec());
            state.setCommarea(reenterCommarea());
            state.setActidinI(ACCOUNT_ID);
            state.setErrFlagOn();
            xrefByAccountFound();
            browseYielding(TransactionRepository.ReadResult.found(
                    TransactionRepository.CICS_FILE_NAME, populatedRecord()));

            controller.copyLastTranData(state);

            assertThat(state.ttypcdI())
                    .as("with the flag already on, L480 skips the eleven moves")
                    .isBlank();
        }
    }

    // =================================================================================================
    // The intrinsic functions and the numeric receivers - gate G29 and gates G22 to G24.
    // =================================================================================================

    @Nested
    @DisplayName("FUNCTION NUMVAL and NUMVAL-C - accept and reject exactly as COBOL does (gate G29)")
    class Intrinsics {

        @ParameterizedTest(name = "NUMVAL(\"{0}\") = {1}")
        @CsvSource({
            "'1234',            1234",
            "'0000001234',      1234",
            "'  1234  ',        1234",
            "'+1234',           1234",
            "'-1234',          -1234",
            "'12.34',           12.34",
            "'12.',             12",
            "'1234-',          -1234",
            "'1234 -',         -1234",
            "'1234CR',         -1234",
            "'1234DB',         -1234",
            "'1234+',           1234",
        })
        @DisplayName("conforming arguments convert exactly")
        void numvalAccepts(String image, BigDecimal expected) {
            assertThat(TransactionViewController.numval(image)).isEqualByComparingTo(expected);
            assertThat(TransactionViewController.testNumval(image))
                    .isEqualTo(TransactionViewController.NUMVAL_CONFORMS);
        }

        @ParameterizedTest(name = "NUMVAL(\"{0}\") does not conform")
        @ValueSource(strings = {"", "   ", "+", "-", ".", "abc", "12ab", "$12", "1,,234", "12.3.4",
            "1234CRX", "12 34", "1,", "1,a", "1.2,3", ",1", "+1234-", "-1234+", ",", "1234C",
            "1234D", "1234CD", "1,234", "1,234,567", "-1,234", "1,234-"})
        @DisplayName("non-conforming arguments are reported and yield zero")
        void numvalRejects(String image) {
            assertThat(TransactionViewController.testNumval(image))
                    .isNotEqualTo(TransactionViewController.NUMVAL_CONFORMS)
                    .isPositive();
            assertThat(TransactionViewController.numval(image))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("the grouping comma belongs to NUMVAL-C, and only between integer digits")
        void theGroupingComma() {
            assertThat(TransactionViewController.numvalC("1,234,567"))
                    .isEqualByComparingTo(new BigDecimal("1234567"));
            assertThat(TransactionViewController.testNumvalC("1,"))
                    .as("a trailing comma has no following digit")
                    .isPositive();
            assertThat(TransactionViewController.testNumvalC("1,a"))
                    .as("a comma must be followed by a digit")
                    .isPositive();
            assertThat(TransactionViewController.testNumvalC("1.2,3"))
                    .as("a comma after the decimal point is not a grouping comma")
                    .isPositive();
            assertThat(TransactionViewController.testNumvalC(",1"))
                    .as("a comma with no preceding digit is not a grouping comma")
                    .isPositive();
        }

        @Test
        @DisplayName("NUMVAL accepts no comma at all, not even a well-placed grouping one")
        void numvalAcceptsNoComma() {
            // The grouping comma is a NUMVAL-C extension. Both of this screen's NUMVAL call sites -
            // COTRN02C lines 204 and 218 - are guarded by an IS NOT NUMERIC test, and a comma is not
            // numeric, so the guard errors before the conversion is reached; tightening the grammar
            // therefore changes no live path while making the intrinsic right about the language.
            assertThat(TransactionViewController.testNumval("1,234"))
                    .as("well-placed under NUMVAL-C, still not a NUMVAL argument")
                    .isPositive();
            assertThat(TransactionViewController.numval("1,234")).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(TransactionViewController.testNumvalC("1,234"))
                    .as("the same argument under the wider grammar")
                    .isEqualTo(TransactionViewController.NUMVAL_CONFORMS);
        }

        @Test
        @DisplayName("a sign may appear at one end or the other, never at both")
        void aSignAppearsOnce() {
            assertThat(TransactionViewController.testNumval("+1234"))
                    .isEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(TransactionViewController.testNumval("1234+"))
                    .isEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(TransactionViewController.testNumval("+1234-"))
                    .as("a leading sign closes the trailing-sign position")
                    .isPositive();
            assertThat(TransactionViewController.testNumval("-1234+")).isPositive();
        }

        @Test
        @DisplayName("the two intrinsics differ in two places: the currency sign and the comma")
        void theTwoDifferencesBetweenTheIntrinsics() {
            assertThat(TransactionViewController.testNumvalC("$1234"))
                    .isEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(TransactionViewController.numvalC("$1234"))
                    .isEqualByComparingTo(new BigDecimal("1234"));
            assertThat(TransactionViewController.testNumval("$1234"))
                    .as("NUMVAL has no currency sign")
                    .isNotEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(TransactionViewController.testNumval("1,234"))
                    .as("and no grouping comma either - the second difference")
                    .isNotEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(TransactionViewController.testNumvalC("- $ 1,234.56"))
                    .isEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(TransactionViewController.numvalC("- $ 1,234.56"))
                    .isEqualByComparingTo(new BigDecimal("-1234.56"));
        }

        @Test
        @DisplayName("CR and DB belong to both intrinsics, which is the tempting wrong simplification")
        void theCreditIndicatorsBelongToBoth() {
            // A credit indicator reads as a currency-ish notion, so it looks as though it might be a
            // NUMVAL-C extension like the '$' and the ','. It is not: both intrinsics accept CR and DB,
            // and removing them from NUMVAL would itself be a parity defect.
            for (String image : new String[] {"1234CR", "1234DB"}) {
                assertThat(TransactionViewController.testNumval(image))
                        .isEqualTo(TransactionViewController.NUMVAL_CONFORMS);
                assertThat(TransactionViewController.testNumvalC(image))
                        .isEqualTo(TransactionViewController.NUMVAL_CONFORMS);
                assertThat(TransactionViewController.numval(image))
                        .isEqualByComparingTo(TransactionViewController.numvalC(image))
                        .isEqualByComparingTo(new BigDecimal("-1234"));
            }
        }

        @Test
        @DisplayName("the screen's own edited amount round-trips through NUMVAL-C")
        void theEditedAmountRoundTrips() {
            assertThat(TransactionViewController.numvalC("+00000012.34"))
                    .isEqualByComparingTo(new BigDecimal("12.34"));
            assertThat(TransactionViewController.numvalC("-00000012.34"))
                    .isEqualByComparingTo(new BigDecimal("-12.34"));
        }

        @ParameterizedTest(name = "NUMVAL-C(\"{0}\") does not conform")
        @ValueSource(strings = {"", "$", "$ ", "12$34", "$12X"})
        @DisplayName("NUMVAL-C rejects a bare or misplaced currency sign")
        void numvalCRejects(String image) {
            assertThat(TransactionViewController.testNumvalC(image)).isPositive();
            assertThat(TransactionViewController.numvalC(image)).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("both intrinsics refuse a null argument rather than reading it as zero")
        void bothIntrinsicsRefuseNull() {
            assertThatThrownBy(() -> TransactionViewController.numval(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> TransactionViewController.numvalC(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> TransactionViewController.testNumval(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> TransactionViewController.testNumvalC(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @ParameterizedTest(name = "the mask renders {0} as \"{1}\"")
        @CsvSource({
            "0,             '+00000000.00'",
            "12.34,         '+00000012.34'",
            "-12.34,        '-00000012.34'",
            "99999999.99,   '+99999999.99'",
            "-99999999.99,  '-99999999.99'",
            "100000000.00,  '+00000000.00'",
            "123456789.99,  '+23456789.99'",
            "-123456789.99, '-23456789.99'",
            "12.349,        '+00000012.34'",
            "-12.349,       '-00000012.34'",
        })
        @DisplayName("PIC +99999999.99 truncates the ninth integer digit and the third fraction digit")
        void theEditMask(BigDecimal value, String expected) {
            assertThat(controller.wsTranAmtEdited(value))
                    .isEqualTo(expected)
                    .hasSize(TransactionViewController.WS_TRAN_AMT_E_LENGTH);
        }

        @Test
        @DisplayName("the mask refuses a null value")
        void theEditMaskRefusesNull() {
            assertThatThrownBy(() -> controller.wsTranAmtEdited(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("COMPUTE into PIC 9 truncates the fraction, wraps the overflow and drops the sign")
        void computeIntoPic9() {
            assertThat(TransactionViewController.computeIntoPic9(new BigDecimal("11.99"), 11))
                    .isEqualTo(11L);
            assertThat(TransactionViewController.computeIntoPic9(new BigDecimal("-11.99"), 11))
                    .isEqualTo(11L);
            assertThat(TransactionViewController.computeIntoPic9(new BigDecimal("123456789012"), 11))
                    .as("twelve digits into eleven keeps the low-order eleven")
                    .isEqualTo(23_456_789_012L);
            assertThatThrownBy(() -> TransactionViewController.computeIntoPic9(null, 11))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> TransactionViewController.computeIntoPic9(BigDecimal.ONE, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PIC 9");
            assertThatThrownBy(() -> TransactionViewController.computeIntoPic9(BigDecimal.ONE, 19))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("MOVE PIC X to PIC 9 keeps the LOW-order digits, the opposite of a PIC X receiver")
        void movePicXToPic9() {
            assertThat(TransactionViewController.movePicXToPic9("0000000000000050", 16))
                    .isEqualTo(50L);
            assertThat(TransactionViewController.movePicXToPic9("50", 16)).isEqualTo(50L);
            assertThat(TransactionViewController.movePicXToPic9("123456789012345678", 16))
                    .as("eighteen digits into sixteen keeps the low-order sixteen")
                    .isEqualTo(3_456_789_012_345_678L);
            assertThat(TransactionViewController.movePicXToPic9("                ", 16))
                    .as("a non-numeric sender is undefined in COBOL and is read as zero here")
                    .isZero();
            assertThat(TransactionViewController.movePicXToPic9Image("50", 16))
                    .isEqualTo("0000000000000050");
            assertThat(TransactionViewController.movePicXToPic9Image("0001", 4)).isEqualTo("0001");
            assertThatThrownBy(() -> TransactionViewController.movePicXToPic9(null, 4))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("ADD 1 wraps at the receiver's width rather than raising a condition")
        void addOneWraps() {
            assertThat(TransactionViewController.addOneToPic9(50L, 16)).isEqualTo(51L);
            assertThat(TransactionViewController.addOneToPic9(0L, 16)).isEqualTo(1L);
            assertThat(TransactionViewController.addOneToPic9(9_999_999_999_999_999L, 16))
                    .as("ON SIZE ERROR is absent, so the high-order digit is discarded")
                    .isZero();
            assertThatThrownBy(() -> TransactionViewController.addOneToPic9(-1L, 16))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no sign position");
            assertThatThrownBy(() -> TransactionViewController.addOneToPic9(1L, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("STRING DELIMITED BY SPACE stops at the first space")
        void stringDelimitedBySpace() {
            assertThat(TransactionViewController.stringDelimitedBySpace("0000000000000051"))
                    .isEqualTo("0000000000000051");
            assertThat(TransactionViewController.stringDelimitedBySpace("51              "))
                    .isEqualTo("51");
            assertThat(TransactionViewController.stringDelimitedBySpace(" 51")).isEmpty();
            assertThatThrownBy(() -> TransactionViewController.stringDelimitedBySpace(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("STRING INTO overlays from position one and leaves the remainder alone")
        void stringInto() {
            assertThat(TransactionViewController.stringInto("....................", "abc"))
                    .isEqualTo("abc.................");
            assertThat(TransactionViewController.stringInto("...", "abcdef"))
                    .as("a composition longer than the receiver truncates on the right")
                    .isEqualTo("abc");
            assertThat(TransactionViewController.stringInto("abc", "abc")).isEqualTo("abc");
            assertThatThrownBy(() -> TransactionViewController.stringInto(null, "a"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> TransactionViewController.stringInto("a", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the class tests treat spaces, nulls and an absent value as empty")
        void theClassTests() {
            assertThat(TransactionViewController.isSpacesOrLowValues(null)).isTrue();
            assertThat(TransactionViewController.isSpacesOrLowValues("")).isTrue();
            assertThat(TransactionViewController.isSpacesOrLowValues("   ")).isTrue();
            assertThat(TransactionViewController.isSpacesOrLowValues("\u0000\u0000")).isTrue();
            assertThat(TransactionViewController.isSpacesOrLowValues(" \u0000 ")).isTrue();
            assertThat(TransactionViewController.isSpacesOrLowValues(" x ")).isFalse();

            assertThat(TransactionViewController.isNumericClass(null)).isFalse();
            assertThat(TransactionViewController.isNumericClass("")).isFalse();
            assertThat(TransactionViewController.isNumericClass("  ")).isFalse();
            assertThat(TransactionViewController.isNumericClass("0123456789")).isTrue();
            assertThat(TransactionViewController.isNumericClass("+123")).isFalse();
            assertThat(TransactionViewController.isNumericClass("12.3")).isFalse();
            assertThat(TransactionViewController.isNumericClass("12a")).isFalse();
        }

        @Test
        @DisplayName("a reference modification beyond a short image reads spaces")
        void referenceModification() {
            assertThat(TransactionViewController.window("2022-07-18", 0, 4)).isEqualTo("2022");
            assertThat(TransactionViewController.window("20", 0, 4)).isEqualTo("20  ");
            assertThat(TransactionViewController.charAtOrSpace("abc", 1)).isEqualTo('b');
            assertThat(TransactionViewController.charAtOrSpace("abc", 9)).isEqualTo(' ');
            assertThat(TransactionViewController.charAtOrSpace("abc", -1)).isEqualTo(' ');
            assertThatThrownBy(() -> TransactionViewController.window(null, 0, 1))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> TransactionViewController.charAtOrSpace(null, 0))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the positional predicates are usable and refuse a null image")
        void thePositionalPredicates() {
            assertThat(TransactionViewController.isMalformedAmount("+00000012.34")).isFalse();
            assertThat(TransactionViewController.isMalformedAmount("+0000012.34")).isTrue();
            assertThat(TransactionViewController.isMalformedDate("2022-07-18")).isFalse();
            assertThat(TransactionViewController.isMalformedDate("2022-07-1")).isTrue();
            assertThatThrownBy(() -> TransactionViewController.isMalformedAmount(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> TransactionViewController.isMalformedDate(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("spaces refuses a negative width")
        void spacesRefusesANegativeWidth() {
            assertThat(TransactionViewController.spaces(0)).isEmpty();
            assertThat(TransactionViewController.spaces(3)).isEqualTo("   ");
            assertThatThrownBy(() -> TransactionViewController.spaces(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the AID reconstruction knows the four keys this program acts on, and nothing else")
        void theAidReconstruction() {
            assertThat(TransactionViewController.eibAidOf("ENTER")).isEqualTo(CicsAid.DFHENTER);
            assertThat(TransactionViewController.eibAidOf("PFK03")).isEqualTo(CicsAid.DFHPF3);
            assertThat(TransactionViewController.eibAidOf("PFK04")).isEqualTo(CicsAid.DFHPF4);
            assertThat(TransactionViewController.eibAidOf("PFK05")).isEqualTo(CicsAid.DFHPF5);
            assertThat(TransactionViewController.eibAidOf("PFK12")).isEqualTo(CicsAid.DFHNULL);
            assertThat(TransactionViewController.eibAidOf("CLEAR")).isEqualTo(CicsAid.DFHNULL);
            assertThat(TransactionViewController.eibAidOf("")).isEqualTo(CicsAid.DFHNULL);
            assertThat(TransactionViewController.eibAidOf(null)).isEqualTo(CicsAid.DFHNULL);
        }

        @Test
        @DisplayName("the two symbolic-map projections must agree about the field count")
        void theProjectionsMustAgree() {
            TransactionViewController.requireMatchingProjections(21, 21);

            assertThatThrownBy(() -> TransactionViewController.requireMatchingProjections(20, 21))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("COTRN02.CPY")
                    .hasMessageContaining("drifted");
        }
    }

    // =================================================================================================
    // ProgramState - the per-request working storage, including the items the program never uses.
    // =================================================================================================

    @Nested
    @DisplayName("ProgramState - COTRN02C's WORKING-STORAGE, per request")
    class TheWorkingStorage {

        @Test
        @DisplayName("a fresh area is spaces, zeros and flags off")
        void aFreshArea() {
            ProgramState state = new ProgramState(controller.codec());

            assertThat(state.message()).isEqualTo(" ".repeat(80));
            assertThat(state.errFlagOff()).isTrue();
            assertThat(state.usrModifiedNo()).isTrue();
            assertThat(state.wsAcctIdN()).isZero();
            assertThat(state.wsCardNumN()).isZero();
            assertThat(state.wsTranIdN()).isZero();
            assertThat(state.wsTranAmtN()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(state.wsTranAmtN().scale()).isEqualTo(2);
            assertThat(state.wsTranAmtE()).hasSize(12);
            assertThat(state.csutldtcResult()).isNull();
            assertThat(state.csutldtcDate()).hasSize(10);
            assertThat(state.csutldtcDateFormat()).hasSize(10);
            assertThat(state.tranRecord().filler()).isEqualTo(" ".repeat(20));
            assertThat(state.tranIdAtHighValues()).isFalse();
            assertThat(state.browseOpen()).isFalse();
            assertThat(state.respCd()).isZero();
            assertThat(state.reasCd()).isZero();
            assertThat(state.dateHeader()).isNull();
            assertThat(state.returned()).isFalse();
            assertThat(state.transferred()).isFalse();
            assertThat(state.screenSent()).isFalse();
            assertThat(state.taskEnded()).isFalse();
            assertThat(state.commarea()).isEqualTo(NavigationContext.empty());
            assertThat(state.ct02Info()).isNotNull();
            assertThat(state.symbolicMap()).isNotNull();
            assertThat(state.actidinI()).isBlank();
        }

        @Test
        @DisplayName("the area refuses a null codec, because a record area needs a code page")
        void aNullCodecIsRefused() {
            assertThatThrownBy(() -> new ProgramState(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("code page");
        }

        @Test
        @DisplayName("the dead WS-USR-MODIFIED flag is addressable in both its states")
        void theDeadFlagIsAddressable() {
            ProgramState state = new ProgramState(controller.codec());

            state.setUsrModifiedYes();
            assertThat(state.usrModifiedYes()).isTrue();
            assertThat(state.usrModifiedNo()).isFalse();

            state.setUsrModifiedNo();
            assertThat(state.usrModifiedNo()).isTrue();
            assertThat(state.usrModifiedYes()).isFalse();
        }

        @Test
        @DisplayName("a null communication area reads as the empty one, which is the EIBCALEN = 0 state")
        void aNullCommareaIsTheEmptyOne() {
            ProgramState state = new ProgramState(controller.codec());

            state.setCommarea(null);
            assertThat(state.commarea()).isEqualTo(NavigationContext.empty());

            state.setCt02Info(null);
            assertThat(state.ct02Info()).isNotNull();

            state.adoptCt02Info(null);
            assertThat(state.ct02Info().getPageNum()).isZero();
        }

        @Test
        @DisplayName("adopting the request's CT02 extension copies all six fields")
        void adoptingTheExtension() {
            ProgramState state = new ProgramState(controller.codec());
            TransactionViewRequest.Ct02Info source = new TransactionViewRequest.Ct02Info();
            source.setTrnidFirst("0000000000000001");
            source.setTrnidLast("0000000000000010");
            source.setPageNum(7);
            source.setNextPageYes();
            source.setTrnSelFlg("S");
            source.setTrnSelected("0000000000000005");

            state.adoptCt02Info(source);

            assertThat(state.ct02Info().getTrnidFirst()).isEqualTo("0000000000000001");
            assertThat(state.ct02Info().getTrnidLast()).isEqualTo("0000000000000010");
            assertThat(state.ct02Info().getPageNum()).isEqualTo(7);
            assertThat(state.ct02Info().isNextPageYes()).isTrue();
            assertThat(state.ct02Info().getTrnSelFlg()).isEqualTo("S");
            assertThat(state.ct02Info().getTrnSelected()).isEqualTo("0000000000000005");
        }

        @Test
        @DisplayName("every scaled setter refuses a value at the wrong scale or a null")
        void scaleIsEnforced() {
            ProgramState state = new ProgramState(controller.codec());

            assertThatThrownBy(() -> state.setWsTranAmtN(new BigDecimal("1.234")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scale");
            assertThatThrownBy(() -> state.setWsTranAmtN(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.setMessage(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.setWsTranAmtE(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.setCsutldtcDate(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.setCsutldtcDateFormat(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.setCardXrefRecord(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.setXrefAcctId(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.setXrefCardNum(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.setTranRecord(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.setDateHeader(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.moveMinusOneTo(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.cursorRequestedOn(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("every payload accessor reads and writes the one buffer")
        void everyPayloadAccessor() {
            ProgramState state = new ProgramState(controller.codec());

            state.setActidinI(ACCOUNT_ID);
            state.setCardninI(CARD_NUMBER);
            state.setTtypcdI("01");
            state.setTcatcdI("0001");
            state.setTrnsrcI("POS TERM  ");
            state.setTdescI("Coffee");
            state.setTrnamtI("+00000012.34");
            state.setTorigdtI(VALID_DATE);
            state.setTprocdtI(VALID_DATE);
            state.setMidI("000123456");
            state.setMnameI("Kwik-E-Mart");
            state.setMcityI("Springfield");
            state.setMzipI("0000012345");
            state.setConfirmI("Y");

            assertThat(state.actidinI()).isEqualTo(ACCOUNT_ID);
            assertThat(state.cardninI()).isEqualTo(CARD_NUMBER);
            assertThat(state.ttypcdI()).isEqualTo("01");
            assertThat(state.tcatcdI()).isEqualTo("0001");
            assertThat(state.trnsrcI()).isEqualTo("POS TERM  ");
            assertThat(state.tdescI()).startsWith("Coffee").hasSize(60);
            assertThat(state.trnamtI()).isEqualTo("+00000012.34");
            assertThat(state.torigdtI()).isEqualTo(VALID_DATE);
            assertThat(state.tprocdtI()).isEqualTo(VALID_DATE);
            assertThat(state.midI()).isEqualTo("000123456");
            assertThat(state.mnameI()).startsWith("Kwik-E-Mart").hasSize(30);
            assertThat(state.mcityI()).startsWith("Springfield").hasSize(25);
            assertThat(state.mzipI()).isEqualTo("0000012345");
            assertThat(state.confirmI()).isEqualTo("Y");
            assertThat(state.payload(ScreenField.ACTIDIN)).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("MOVE LOW-VALUES clears the payload and the length items together")
        void lowValuesClearsBothViews() {
            ProgramState state = new ProgramState(controller.codec());
            state.setActidinI(ACCOUNT_ID);
            state.moveMinusOneTo(ScreenField.MID);

            state.moveLowValuesToOutputMap();

            assertThat(state.actidinI()).isEqualTo("\u0000".repeat(11));
            assertThat(state.cursorRequestedOn(ScreenField.MID))
                    .as("the group move covers the xxxL halfwords too, which is why L123 follows L122")
                    .isFalse();
        }

        @Test
        @DisplayName("setCardXrefRecord re-synchronises both RIDFLD images")
        void settingTheRecordResynchronises() {
            ProgramState state = new ProgramState(controller.codec());

            state.setCardXrefRecord(new CardXrefRecord(CARD_NUMBER, 1, 22L));

            assertThat(state.xrefCardNum()).isEqualTo(CARD_NUMBER);
            assertThat(state.xrefAcctId()).isEqualTo("00000000022");
        }

        @Test
        @DisplayName("INITIALIZE replaces the record area and keeps its code page")
        void initializeReplacesTheArea() {
            ProgramState state = new ProgramState(controller.codec());
            state.tranRecord().moveTranDesc("stale");

            state.initializeTranRecord();

            assertThat(state.tranRecord().tranDesc()).isBlank();
            assertThat(state.tranRecord().charset()).isEqualTo(CHARSET);
        }

        @Test
        @DisplayName("the terminal flags are independent and taskEnded covers both")
        void theTerminalFlags() {
            ProgramState returnedState = new ProgramState(controller.codec());
            returnedState.markReturned();
            assertThat(returnedState.returned()).isTrue();
            assertThat(returnedState.transferred()).isFalse();
            assertThat(returnedState.taskEnded()).isTrue();

            ProgramState transferredState = new ProgramState(controller.codec());
            transferredState.markTransferred();
            assertThat(transferredState.transferred()).isTrue();
            assertThat(transferredState.returned()).isFalse();
            assertThat(transferredState.taskEnded()).isTrue();

            ProgramState sentState = new ProgramState(controller.codec());
            sentState.recordScreenSent();
            assertThat(sentState.screenSent()).isTrue();
        }

        @Test
        @DisplayName("the response codes are settable, because the source stores into both")
        void theResponseCodes() {
            ProgramState state = new ProgramState(controller.codec());

            state.setRespCd(FileStatus.NOTFND);
            state.setReasCd(13);

            assertThat(state.respCd()).isEqualTo(13);
            assertThat(state.reasCd()).isEqualTo(13);
        }

        @Test
        @DisplayName("moveHighValuesToTranId records that the boundary was set")
        void theBrowseBoundary() {
            ProgramState state = new ProgramState(controller.codec());

            state.moveHighValuesToTranId();

            assertThat(state.tranIdAtHighValues()).isTrue();
        }

        @Test
        @DisplayName("the error flag reads consistently through both of its condition names")
        void theErrorFlagReadsBothWays() {
            ProgramState state = new ProgramState(controller.codec());

            state.setErrFlagOn();
            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.errFlagOff())
                    .as("88 ERR-FLG-OFF is the complement of 88 ERR-FLG-ON, never an independent bit")
                    .isFalse();

            state.setErrFlagOff();
            assertThat(state.errFlagOff()).isTrue();
            assertThat(state.errFlagOn()).isFalse();
        }

        @Test
        @DisplayName("browseOpen reports a positioned browse as well as an absent one")
        void browseOpenReportsBothStates() {
            ProgramState state = new ProgramState(controller.codec());
            TransactionRepository.Browse browse = mock(TransactionRepository.Browse.class);

            assertThat(state.browseOpen()).isFalse();
            state.openBrowse(browse);
            assertThat(state.browseOpen()).isTrue();
            assertThat(state.requireBrowse()).isSameAs(browse);

            state.closeBrowse();
            assertThat(state.browseOpen()).isFalse();
            assertThatThrownBy(state::requireBrowse).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a supplied CT02 extension is adopted by reference, matching the group MOVE")
        void aSuppliedExtensionIsAdopted() {
            ProgramState state = new ProgramState(controller.codec());
            TransactionViewResponse.Ct02Info supplied = new TransactionViewResponse.Ct02Info();
            supplied.setPageNum(9);
            supplied.setTrnidFirst("0000000000000009");

            state.setCt02Info(supplied);

            assertThat(state.ct02Info()).isSameAs(supplied);
            assertThat(state.ct02Info().getPageNum()).isEqualTo(9);
            assertThat(state.ct02Info().getTrnidFirst()).isEqualTo("0000000000000009");
        }

        @Test
        @DisplayName("RETURN-TO-PREV-SCREEN falls back to the sign-on program when no target is set")
        void theTransferFallsBackToSignOn() {
            ProgramState state = new ProgramState(controller.codec());

            controller.returnToPrevScreen(state);

            assertThat(state.commarea().toProgram())
                    .as("L502-503: an unset CDEMO-TO-PROGRAM becomes COSGN00C")
                    .isEqualTo("COSGN00C");
            assertThat(state.response().getNextProgram()).isEqualTo("COSGN00C");
            // An XCTL states no map: which map COSGN00C paints is its decision, made after this program
            // has ended, and COTRN02C names none in the XCTL at L508-511. Publishing COTRN02 / COTRN2A
            // here would tell the client to repaint the screen it is leaving.
            assertThat(state.response().getNextMapset()).isBlank()
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(state.response().getNextMap()).isBlank()
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(state.commarea().fromTranid()).isEqualTo("CT02");
            assertThat(state.commarea().fromProgram()).isEqualTo("COTRN02C");
            assertThat(state.commarea().isEnter())
                    .as("L507 zeroes CDEMO-PGM-CONTEXT so the target paints rather than receives")
                    .isTrue();
            assertThat(state.transferred()).isTrue();
        }

        @Test
        @DisplayName("RETURN-TO-PREV-SCREEN keeps a target that is already set")
        void theTransferKeepsAnExplicitTarget() {
            ProgramState state = new ProgramState(controller.codec());
            state.setCommarea(NavigationContext.empty().withToProgram("COTRN00C"));

            controller.returnToPrevScreen(state);

            assertThat(state.response().getNextProgram()).isEqualTo("COTRN00C");
            assertThat(state.response().getNextMapset()).isBlank();
            assertThat(state.response().getNextMap()).isBlank();
            // And the caller's own two commarea items are untouched by the blanking: they are different
            // storage, and COTRN02C never writes them.
            assertThat(state.commarea().lastMapset()).isEqualTo(NavigationContext.empty().lastMapset());
            assertThat(state.commarea().lastMap()).isEqualTo(NavigationContext.empty().lastMap());
        }

        @Test
        @DisplayName("a SEND still names this screen's own mapset and map, which is the other case")
        void aSendStillNamesThisScreen() {
            ProgramState state = new ProgramState(controller.codec());

            controller.sendTrnaddScreen(state);

            assertThat(state.response().getNextMapset()).isEqualTo(TransactionViewResponse.MAPSET_NAME);
            assertThat(state.response().getNextMap()).isEqualTo(TransactionViewResponse.MAP_NAME);
        }
    }

    // =================================================================================================
    // Statelessness and the HTTP adapter.
    // =================================================================================================

    @Nested
    @DisplayName("Statelessness (gate G37) and the HTTP adapter (gate G51)")
    class StatelessnessAndAdapter {

        @Test
        @DisplayName("two requests through one controller cannot see each other's screen")
        void twoRequestsAreIsolated() {
            xrefByAccountFound();
            TransactionViewRequest first = enterRequest();
            first.setActidin(ACCOUNT_ID);
            TransactionViewRequest second = enterRequest();
            second.setCardnin(CARD_NUMBER);
            xrefByCardFound();

            ProgramState firstState = controller.mainPara(first);
            ProgramState secondState = controller.mainPara(second);

            assertThat(firstState).isNotSameAs(secondState);
            assertThat(firstState.response()).isNotSameAs(secondState.response());
            assertThat(firstState.symbolicMap()).isNotSameAs(secondState.symbolicMap());
            assertThat(firstState.tranRecord()).isNotSameAs(secondState.tranRecord());
            assertThat(firstState.wsAcctIdN())
                    .as("the first request resolved by account id and the second by card number")
                    .isEqualTo(11L);
            assertThat(firstState.wsCardNumN()).isZero();
            assertThat(secondState.wsAcctIdN()).isZero();
            assertThat(secondState.wsCardNumN()).isEqualTo(4_111_111_111_111_111L);
        }

        @Test
        @DisplayName("a request carrying no detail fields cannot leave a residue for the next one")
        void noResidueSurvivesBetweenRequests() {
            xrefByAccountFound();
            TransactionViewRequest populated = completeRequest();
            TransactionViewRequest bare = enterRequest();
            bare.setActidin(ACCOUNT_ID);
            browseWithLastId();
            writeSucceeds();

            controller.mainPara(populated);
            ProgramState secondState = controller.mainPara(bare);

            assertThat(secondState.ttypcdI()).isBlank();
            assertThat(secondState.mzipI()).isBlank();
            assertThat(secondState.message()).startsWith("Type CD can NOT be empty...");
        }

        @Test
        @DisplayName("the controller declares no mutable state of its own")
        void theControllerHasNoMutableState() {
            assertThat(java.util.Arrays.stream(TransactionViewController.class.getDeclaredFields())
                    .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    .allMatch(field -> java.lang.reflect.Modifier.isFinal(field.getModifiers())))
                    .as("practice B9: every instance field is final")
                    .isTrue();
            assertThat(java.util.Arrays.stream(TransactionViewController.class.getDeclaredFields())
                    .filter(field -> java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    .allMatch(field -> java.lang.reflect.Modifier.isFinal(field.getModifiers())))
                    .as("gate G53: every static field is final")
                    .isTrue();
        }

        /** @return the module's own JSON mapping, so the adapter is asserted through real converters */
        private ObjectMapper carddemoMapper() {
            Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
            new WebConfig().carddemoJacksonCustomizer().customize(builder);
            return builder.build();
        }

        /** @return a stand-alone {@code MockMvc} over the controller, using the module's converters */
        private MockMvc mockMvc(ObjectMapper mapper) {
            return MockMvcBuilders.standaloneSetup(controller)
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                    .build();
        }

        @Test
        @DisplayName("POST /api/transactions adds a transaction and returns the painted screen")
        void theAdapterAdds() throws Exception {
            xrefByAccountFound();
            browseWithLastId();
            writeSucceeds();
            ObjectMapper mapper = carddemoMapper();

            mockMvc(mapper).perform(post(TransactionViewController.TRANSACTIONS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(completeRequest())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnnameo").value("CT02"))
                    .andExpect(jsonPath("$.pgmnameo").value("COTRN02C"))
                    .andExpect(jsonPath("$.nextProgram").value("COTRN02C"))
                    .andExpect(jsonPath("$.nextMapset").value("COTRN02"))
                    .andExpect(jsonPath("$.nextMap").value("COTRN2A"))
                    .andExpect(jsonPath("$.errmsgo")
                            .value(startsWith("Transaction added successfully.")));

            verify(transactionRepository).write(any());
        }

        @Test
        @DisplayName("POST with no communication area routes the client to the sign-on program")
        void theAdapterHandlesNoCommarea() throws Exception {
            ObjectMapper mapper = carddemoMapper();

            mockMvc(mapper).perform(post(TransactionViewController.TRANSACTIONS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(new TransactionViewRequest())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value("COSGN00C"));
        }

        @Test
        @DisplayName("the response carries no session identifier and no metadata member")
        void theResponseCarriesNoMetadata() throws Exception {
            ObjectMapper mapper = carddemoMapper();
            xrefByAccountFound();

            String body = mockMvc(mapper)
                    .perform(post(TransactionViewController.TRANSACTIONS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(enterRequest())))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .as("gate G9: only the twenty-one xxxO members plus the navigation fields")
                    .doesNotContain("actidinl")
                    .doesNotContain("actidinf")
                    .doesNotContain("actidina")
                    .doesNotContain("metadata")
                    .doesNotContain("JSESSIONID")
                    .contains("\"actidino\"")
                    .contains("\"errmsgo\"");
        }

        @Test
        @DisplayName("the adapter is the same code path as mainPara, so it adds nothing of its own")
        void theAdapterIsThin() {
            xrefByAccountFound();
            browseWithLastId();
            writeSucceeds();

            ScreenResponse<TransactionViewResponse> answer =
                    controller.addTransaction(completeRequest());

            TransactionViewResponse viaAdapter = answer.screen();
            assertThat(viaAdapter.getErrmsgo()).startsWith("Transaction added successfully.");
            assertThat(viaAdapter.getNextProgram()).isEqualTo("COTRN02C");
            assertThat(viaAdapter.getNextMapset()).isEqualTo("COTRN02");
            assertThat(viaAdapter.getNextMap()).isEqualTo("COTRN2A");
            // The twenty-one attribute quads and the cursor request are metadata by declaration, so
            // they travel beside the screen rather than not travelling at all.
            assertThat(answer.screenMetadata().fields())
                    .hasSize(TransactionViewResponse.ScreenField.values().length);
        }

        @Test
        @DisplayName("RECEIVE copies all twenty-one fields into the one buffer")
        void receiveCopiesEveryField() {
            ProgramState state = new ProgramState(controller.codec());
            TransactionViewRequest request = completeRequest();

            controller.receiveTrnaddScreen(state, request);

            assertThat(state.actidinI()).isEqualTo(ACCOUNT_ID);
            assertThat(state.confirmI()).isEqualTo("Y");
            assertThat(state.tdescI()).startsWith("Coffee and a newspaper");
            assertThat(state.respCd()).isEqualTo(FileStatus.NORMAL);
            assertThat(state.reasCd()).isEqualTo(FileStatus.NO_REASON_CODE);
        }

        @Test
        @DisplayName("an absent JSON member arrives as low-values, which is a field CICS did not send")
        void anAbsentMemberIsLowValues() {
            ProgramState state = new ProgramState(controller.codec());
            TransactionViewRequest request = new TransactionViewRequest();
            request.setActidin(null);

            controller.receiveTrnaddScreen(state, request);

            assertThat(state.actidinI()).isEqualTo("\u0000".repeat(11));
            assertThat(TransactionViewController.isSpacesOrLowValues(state.actidinI())).isTrue();
        }
    }

    // =================================================================================================
    // The transaction boundary. COTRN02C's add path is two commands against one file - the L644-650
    // high-water-mark browse and the L713-721 write - and under CICS the task's syncpoint at RETURN is
    // what makes the second one durable. Every other test in this class stubs the repository, which is
    // right for parity and is exactly why none of them can see whether the record survives the
    // connection going back to the pool.
    // =================================================================================================

    @Nested
    @DisplayName("The transaction boundary - the TRANSACT insert must survive the connection's return")
    class TheTransactionBoundary {

        /** The master the binding names, quoted as a delimited identifier by the repository. */
        private static final String MASTER_DS = "CARDDEMO.TEST.TXBOUND.TRANSACT";

        /** The generated-transaction output the repository also resolves, unused by this screen. */
        private static final String SYSTRAN_DS = "CARDDEMO.TEST.TXBOUND.SYSTRAN";

        /** The record image column, as the repository addresses it. */
        private static final String IMAGE_COLUMN = "RECORD_IMAGE";

        /**
         * The physical-record ordinal for an in-memory relation: H2's own row-identifier
         * pseudo-column, exactly as {@code application-test.yml} states it.
         */
        private static final PhysicalSequence ORDINAL = PhysicalSequence.of("_ROWID_");

        /** The identifier an empty master yields: READPREV reports ENDFILE, zero, plus one. */
        private static final String FIRST_TRAN_ID = "0000000000000001";

        @Test
        @DisplayName("the added transaction is still there after the request, on a pool that does not "
                + "auto-commit")
        void theInsertIsCommitted() {
            // The pool is configured exactly as application.yml:146 configures production - auto-commit
            // false - because that setting is what makes this observable. Under it a statement issued
            // with no transaction open is rolled back when Hikari takes the connection back, and the
            // screen still names the identifier because the repository reported NORMAL and was telling
            // the truth about the statement it executed.
            withTransactionalContext(true, (controller, verifier) -> {
                ScreenResponse<TransactionViewResponse> response =
                        controller.addTransaction(completeRequest());

                assertThat(response.screen().getErrmsgo())
                        .as("the screen the operator is shown")
                        .startsWith("Transaction added successfully.  Your Tran ID is "
                                + FIRST_TRAN_ID + ".");
                assertThat(recordsIn(verifier))
                        .as("and the record the screen promised, read back on another connection")
                        .hasSize(1)
                        .allSatisfy(image -> {
                            assertThat(image).as("gate G19 - the copybook's 350 bytes")
                                    .hasSize(TranRecord.RECORD_LENGTH);
                            assertThat(image).startsWith(FIRST_TRAN_ID);
                        });
            });
        }

        @Test
        @DisplayName("without the boundary the same call names an identifier and leaves nothing behind, "
                + "which is the defect this closes")
        void withoutTheBoundaryTheInsertIsLost() {
            // The control, and it is what makes the case above evidence rather than assertion. The only
            // difference is that transaction management is not enabled, so @Transactional advises
            // nothing - the same runtime the annotation's absence produced. The screen is identical; the
            // master is empty.
            withTransactionalContext(false, (controller, verifier) -> {
                ScreenResponse<TransactionViewResponse> response =
                        controller.addTransaction(completeRequest());

                assertThat(response.screen().getErrmsgo())
                        .as("the operator is told the same thing either way")
                        .startsWith("Transaction added successfully.  Your Tran ID is "
                                + FIRST_TRAN_ID + ".");
                assertThat(recordsIn(verifier))
                        .as("but nothing was committed")
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("the probe and the write are one unit, so the next identifier is the committed "
                + "high-water mark")
        void theProbeSeesWhatTheLastRequestCommitted() {
            // Two requests through one controller over one pool. The second request's browse has to see
            // what the first one committed, or it computes the same key again and the write lands on the
            // DUPKEY arm instead of adding. That is the second half of why the boundary exists: the
            // probe and the write have to be on the same connection AND the first unit has to have
            // finished.
            withTransactionalContext(true, (controller, verifier) -> {
                controller.addTransaction(completeRequest());

                ScreenResponse<TransactionViewResponse> second =
                        controller.addTransaction(completeRequest());

                assertThat(second.screen().getErrmsgo())
                        .as("the high-water mark advanced, so this is an add and not a duplicate")
                        .startsWith("Transaction added successfully.  Your Tran ID is "
                                + "0000000000000002.");
                assertThat(recordsIn(verifier))
                        .as("both records are durable")
                        .hasSize(2)
                        .satisfiesExactlyInAnyOrder(
                                first -> assertThat(first).startsWith(FIRST_TRAN_ID),
                                next -> assertThat(next).startsWith("0000000000000002"));
            });
        }

        @Test
        @DisplayName("the insert enlists in the unit of work, so a task that never commits leaves "
                + "nothing behind")
        void theInsertIsRolledBackWithTheUnitOfWork() {
            // The other half of a boundary. Committing is only meaningful if not committing is equally
            // possible: this drives the same request inside a unit of work the test then abandons, which
            // is the CICS task that abends before its syncpoint. The insert has to be enlisted in that
            // unit rather than standing outside it, or the record would survive an abend the mainframe
            // would have backed out.
            withTransactionalContext(true, (controller, verifier) -> {
                TransactionTemplate abandoned = new TransactionTemplate(new JdbcTransactionManager(
                        Objects.requireNonNull(verifier.getDataSource())));

                ScreenResponse<TransactionViewResponse> response = abandoned.execute(status -> {
                    ScreenResponse<TransactionViewResponse> answer =
                            controller.addTransaction(completeRequest());
                    status.setRollbackOnly();
                    return answer;
                });

                assertThat(response).isNotNull();
                assertThat(response.screen().getErrmsgo())
                        .as("the program is unchanged - it still reports what it did")
                        .startsWith("Transaction added successfully.");
                assertThat(recordsIn(verifier))
                        .as("but the unit of work was abandoned, so the insert went with it")
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("the boundary is on the HTTP entry point, and mainPara stays free of it")
        void theBoundaryIsOnTheEntryPoint() throws Exception {
            // Placement is the whole of it. On the entry point the annotation is advised by the proxy
            // Spring creates; on mainPara it would be reached by self-invocation from within the same
            // instance and advise nothing at all - and it would also put a transaction in the path of
            // every parity test, which drive mainPara directly against a stubbed repository.
            assertThat(TransactionViewController.class
                    .getMethod("addTransaction", TransactionViewRequest.class)
                    .isAnnotationPresent(Transactional.class))
                    .isTrue();
            assertThat(TransactionViewController.class
                    .getMethod("mainPara", TransactionViewRequest.class)
                    .isAnnotationPresent(Transactional.class))
                    .isFalse();
            assertThat(TransactionViewController.class
                    .getMethod("addTransaction", ProgramState.class)
                    .isAnnotationPresent(Transactional.class))
                    .as("ADD-TRANSACTION is a COBOL paragraph, not the CICS task's boundary")
                    .isFalse();
            assertThat(Modifier.isFinal(TransactionViewController.class.getModifiers()))
                    .as("a final class cannot be proxied by CGLIB, so the annotation would be inert")
                    .isFalse();
            assertThat(Modifier.isFinal(TransactionViewController.class
                    .getMethod("addTransaction", TransactionViewRequest.class).getModifiers()))
                    .as("nor can a final method be overridden by the proxy")
                    .isFalse();
        }

        /**
         * Runs a body against a real controller over a real repository, a real non-auto-commit pool and a
         * private in-memory database. The cross-reference stays stubbed, because it is read-only and its
         * outcome is not what this class is about.
         *
         * @param transactionManagementEnabled whether {@code @Transactional} is advised at all
         * @param body                         given the controller as the context exposes it - proxied
         *                                     when management is enabled - and a template for reading the
         *                                     master back
         */
        private void withTransactionalContext(boolean transactionManagementEnabled,
                java.util.function.BiConsumer<TransactionViewController, JdbcTemplate> body) {
            xrefByAccountFound();
            // A fresh name per test rather than a shared counter, which is how TransactionRepositoryTest
            // isolates its relations and which keeps this class free of mutable static state (gate G53).
            String url = "jdbc:h2:mem:tranview_tx" + UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
            HikariDataSource pool = new HikariDataSource();
            pool.setJdbcUrl(url);
            pool.setDriverClassName("org.h2.Driver");
            pool.setUsername("sa");
            pool.setPassword("");
            // The two settings that matter, and both mirror production: no auto-commit, and a pool small
            // enough that the connection a request used is the one the next request gets back.
            pool.setAutoCommit(false);
            pool.setMaximumPoolSize(2);

            try (HikariDataSource opened = pool) {
                JdbcTemplate schema = new JdbcTemplate(opened);
                new TransactionTemplate(new JdbcTransactionManager(opened)).executeWithoutResult(
                        status -> {
                            for (String dataset : new String[] { MASTER_DS, SYSTRAN_DS }) {
                                schema.execute("CREATE TABLE \"" + dataset + "\" (\"" + IMAGE_COLUMN
                                        + "\" CHAR(" + TranRecord.RECORD_LENGTH + "))");
                            }
                        });

                AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
                context.registerBean(DataSource.class, () -> opened);
                context.registerBean(PlatformTransactionManager.class,
                        () -> new JdbcTransactionManager(opened));
                context.registerBean(TransactionViewController.class,
                        () -> new TransactionViewController(
                                new TransactionRepository(new JdbcTemplate(opened), bindings(), CHARSET,
                                        RecordImageForm.CHARACTER, ORDINAL),
                                cardXrefRepository, dateUtilityJob, FIXED_CLOCK, CHARSET));
                if (transactionManagementEnabled) {
                    context.register(TransactionManagementEnabled.class);
                }
                try (AnnotationConfigApplicationContext running = context) {
                    running.refresh();
                    body.accept(running.getBean(TransactionViewController.class),
                            new JdbcTemplate(opened));
                }
            }
        }

        /**
         * @param template a template over the master's database
         * @return every record image the master holds, read outside any transaction the body opened
         */
        private List<String> recordsIn(JdbcTemplate template) {
            return template.queryForList("SELECT \"" + IMAGE_COLUMN + "\" FROM \"" + MASTER_DS + "\"",
                    String.class);
        }

        /** The three bindings the repository resolves, at {@code CVTRA05Y}'s geometry. */
        private DatasetBindings bindings() {
            DatasetBindings catalogue = new DatasetBindings();
            DatasetBinding master = new DatasetBinding(MASTER_DS, "ksds", false, "FB", null,
                    TranRecord.RECORD_LENGTH, "CVTRA05Y", TranRecord.TRAN_ID_KEY_LENGTH, null, null,
                    null);
            catalogue.put(TransactionRepository.CICS_FILE_NAME, master);
            catalogue.put(TransactionRepository.INPUT_DD_NAME, master);
            catalogue.put(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME,
                    new DatasetBinding(SYSTRAN_DS, "sequential", false, "F", 0,
                            TranRecord.RECORD_LENGTH, "CVTRA05Y", null, null, null, null));
            return catalogue;
        }

        /** Turns on the proxying that makes {@code @Transactional} mean anything. */
        @Configuration
        @EnableTransactionManagement
        static class TransactionManagementEnabled {
        }
    }

    // =================================================================================================
    // Synthesised cross-reference read outcomes. A ReadResult carries the decoded record AND the bytes it
    // was decoded from, because DISPLAY CARD-XREF-RECORD (app/cbl/CBACT03C.cbl:78 and :96) writes the
    // record area and the area's FILLER X(14) holds whatever the row held. A test constructing an outcome
    // has no row, so the image it supplies is the one a row of exactly this record would carry - stated
    // once here rather than at every call site.
    // =================================================================================================

    /**
     * The found arm over a synthesised row of this record.
     *
     * @param ddName the access path
     * @param record the record the row would carry
     * @return the outcome, carrying the record and the image a row of it would hold
     */
    private static CardXrefRepository.ReadResult xrefFound(String ddName, CardXrefRecord record) {
        return CardXrefRepository.ReadResult.found(ddName, record, xrefImageOf(record));
    }

    /**
     * The duplicate arm over a synthesised row of this record.
     *
     * @param ddName   the access path
     * @param first    the first of the matching records
     * @param cicsResp DUPREC for the base key or DUPKEY for an alternate key
     * @return the outcome, carrying the record and the image a row of it would hold
     */
    private static CardXrefRepository.ReadResult xrefDuplicate(String ddName, CardXrefRecord first,
            int cicsResp) {
        return CardXrefRepository.ReadResult.duplicate(ddName, first, xrefImageOf(first), cicsResp);
    }

    /**
     * The 50-character image a row of this record would hold.
     *
     * @param record the record
     * @return its encoded image
     */
    private static String xrefImageOf(CardXrefRecord record) {
        return new String(record.encode(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
    }
}
