package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
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
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
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
import java.math.RoundingMode;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
 * The mandated class name and the program it was migrated from disagree, deliberately.
 */
@DisplayName("TransactionViewController - COTRN02C / CT02, which adds a transaction (risk R-B)")
class TransactionViewControllerTest {
    private static final Charset TEST_PROFILE_CHARSET = StandardCharsets.US_ASCII;

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:33Z"), ZoneOffset.UTC);

    private static final Charset CHARSET = StandardCharsets.US_ASCII;

    private static final String ACCOUNT_ID = "00000000011";

    private static final String CARD_NUMBER = "4111111111111111";

    private static final String VALID_DATE = "2022-07-18";

    private static final String TOLERATED_DATE = "1500-01-01";

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

    private static NavigationContext reenterCommarea() {
        return NavigationContext.empty().withPgmReenter();
    }

    private static TransactionViewRequest enterRequest() {
        TransactionViewRequest request = new TransactionViewRequest();
        request.setNavigationContext(reenterCommarea());
        request.setAid(PfKeyResolver.aidImage(CicsAid.DFHENTER));
        return request;
    }

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

    private void xrefByAccountFound() {
        when(cardXrefRepository.readByAccountIdViaAltIndex(anyString()))
                .thenReturn(xrefFound(
                        CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                        new CardXrefRecord(CARD_NUMBER, 123_456_789, 11L)));
    }

    private void xrefByCardFound() {
        when(cardXrefRepository.readByCardNumber(anyString()))
                .thenReturn(xrefFound(CardXrefRepository.BASE_DD_NAME,
                        new CardXrefRecord(CARD_NUMBER, 123_456_789, 11L)));
    }

    private TransactionRepository.Browse browseYielding(TransactionRepository.ReadResult result) {
        TransactionRepository.Browse browse = positionedBrowse();
        when(browse.readPrev()).thenReturn(result);
        when(transactionRepository.startBrowse(TransactionRepository.BrowseDirection.BACKWARD))
                .thenReturn(browse);
        return browse;
    }

    private static TranRecord recordWithId(String tranId) {
        TranRecord record = new TranRecord(CHARSET);
        record.moveTranId(tranId);
        return record;
    }

    private TransactionRepository.Browse browseWithLastId() {
        return browseYielding(TransactionRepository.ReadResult.found(
                TransactionRepository.CICS_FILE_NAME, recordWithId("0000000000000050")));
    }

    private void writeSucceeds() {
        when(transactionRepository.write(any()))
                .thenReturn(TransactionRepository.WriteResult.written(
                        TransactionRepository.CICS_FILE_NAME));
    }

    @Nested
    @DisplayName("Risk R-B - the class name says view and the program adds")
    class RiskRb {
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
                    .as("the conflict has to be surfaced in the code, not only in a plan document")
                    .contains("Function    : Add a new Transaction to TRANSACT file")
                    .contains("the source is the authority");
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
        @DisplayName("it states the resolution, and resolves the swap in neither direction")
        void documentationNamesTheResolvingRule() throws IOException {
            assertThat(source())
                    .as("the mandated name is honoured as given and the behaviour comes from the source")
                    .contains("kept verbatim rather than corrected towards the behaviour");
            assertThat(controller.getClass().getSimpleName())
                    .as("the name is not corrected towards the behaviour")
                    .isEqualTo("TransactionViewController")
                    .isNotEqualTo("TransactionAddController");
            verifyNoInteractions(transactionRepository, cardXrefRepository);
        }
    }

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
            request.setAid(PfKeyResolver.aidImage(CicsAid.DFHPF3));

            ProgramState state = controller.mainPara(request);

            assertThat(state.transferred()).isTrue();
            assertThat(state.response().getNextProgram()).isEqualTo("COMEN01C");
        }

        @Test
        @DisplayName("PF3 with a recorded caller goes back to that caller")
        void pf3WithACaller() {
            TransactionViewRequest request = enterRequest();
            request.setAid(PfKeyResolver.aidImage(CicsAid.DFHPF3));
            request.setNavigationContext(reenterCommarea().withFromProgram("COTRN00C"));

            ProgramState state = controller.mainPara(request);

            assertThat(state.response().getNextProgram()).isEqualTo("COTRN00C");
        }

        @Test
        @DisplayName("PF4 blanks the form and repaints it")
        void pf4Clears() {
            TransactionViewRequest request = completeRequest();
            request.setAid(PfKeyResolver.aidImage(CicsAid.DFHPF4));

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
            request.setAid(PfKeyResolver.aidImage(CicsAid.DFHPF12));

            ProgramState state = controller.mainPara(request);

            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message()).startsWith(SystemMessages.CCDA_MSG_INVALID_KEY.strip());
            assertThat(state.screenSent()).isTrue();
            verifyNoInteractions(transactionRepository, cardXrefRepository);
        }

        @Test
        @DisplayName("a raw PF15 byte is an invalid key, where the folded token took the PF3 exit")
        void aRawUpperKeyIsNotItsFoldedPartner() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15)).contains(PfKeyResolver.AidKey.PFK03);

            TransactionViewRequest request = enterRequest();
            request.setAid(null);

            ProgramState state = controller.mainPara(request, Byte.toUnsignedInt(CicsAid.DFHPF15));

            assertThat(state.transferred())
                    .as("L136 tests WHEN DFHPF3, and PF15 is not that byte")
                    .isFalse();
            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message()).startsWith(SystemMessages.CCDA_MSG_INVALID_KEY.strip());
            verifyNoInteractions(transactionRepository, cardXrefRepository);
        }

        @Test
        @DisplayName("a raw PF3 byte still transfers, so the lower key is unaffected")
        void aRawLowerKeyStillTakesItsArm() {
            TransactionViewRequest request = enterRequest();
            request.setAid(null);

            ProgramState state = controller.mainPara(request, Byte.toUnsignedInt(CicsAid.DFHPF3));

            assertThat(state.transferred()).isTrue();
            assertThat(state.response().getNextProgram()).isEqualTo("COMEN01C");
        }

        @ParameterizedTest(name = "a raw DFHPF{0} byte is an invalid key here")
        @ValueSource(ints = {13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24})
        @DisplayName("all twelve upper function keys reach WHEN OTHER when stated as raw bytes")
        void everyUpperKeyIsInvalidHere(int pfNumber) {
            TransactionViewRequest request = enterRequest();
            request.setAid(null);

            ProgramState state =
                    controller.mainPara(request, Byte.toUnsignedInt(functionKeyByte(pfNumber)));

            assertThat(state.transferred()).isFalse();
            assertThat(state.errFlagOn()).isTrue();
            verifyNoInteractions(transactionRepository, cardXrefRepository);
        }

        @Test
        @DisplayName("the byte wins over the token, and a token restating it is accepted")
        void theByteWinsAndAConsistentTokenIsAccepted() {
            TransactionViewRequest request = enterRequest();
            request.setAid(PfKeyResolver.AidKey.PFK03.token());

            ProgramState state = controller.mainPara(request, Byte.toUnsignedInt(CicsAid.DFHPF15));

            assertThat(state.transferred()).isFalse();
            assertThat(state.errFlagOn()).isTrue();
        }

        @Test
        @DisplayName("a token naming a different key is refused rather than discarded")
        void aDisagreeingTokenIsRefused() {
            TransactionViewRequest request = enterRequest();
            request.setAid(PfKeyResolver.AidKey.PFK04.token());

            assertThatThrownBy(
                    () -> controller.mainPara(request, Byte.toUnsignedInt(CicsAid.DFHPF3)))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .hasMessageContaining("aid");

            verifyNoInteractions(transactionRepository, cardXrefRepository);
        }

        @ParameterizedTest(name = "a stated {0} is refused")
        @ValueSource(ints = {-1, 256, 4096})
        @DisplayName("a value that is not one byte is refused rather than narrowed")
        void anImpossibleByteIsRefused(int stated) {
            TransactionViewRequest request = enterRequest();
            request.setAid(null);

            assertThatThrownBy(() -> controller.mainPara(request, stated))
                    .isInstanceOf(ScreenInputRejectedException.class);

            verifyNoInteractions(transactionRepository, cardXrefRepository);
        }

        @Test
        @DisplayName("both spellings of the parameter reach the same byte through the route")
        void bothSpellingsAreHonoured() {
            TransactionViewRequest canonical = enterRequest();
            canonical.setAid(null);
            assertThat(controller.addTransaction(canonical,
                    Byte.toUnsignedInt(CicsAid.DFHPF15), null).screen().getErrmsgo())
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY.strip());

            TransactionViewRequest alternate = enterRequest();
            alternate.setAid(null);
            assertThat(controller.addTransaction(alternate, null,
                    Byte.toUnsignedInt(CicsAid.DFHPF15)).screen().getErrmsgo())
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY.strip());
        }

        private static byte functionKeyByte(int pfNumber) {
            try {
                return CicsAid.class.getDeclaredField("DFHPF" + pfNumber).getByte(null);
            } catch (ReflectiveOperationException absent) {
                throw new AssertionError("CicsAid does not declare DFHPF" + pfNumber, absent);
            }
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

    @Nested
    @DisplayName("VALIDATE-INPUT-DATA-FIELDS - every edit of L235-437, in the source's order")
    class DataFields {
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
            assertThatThrownBy(() -> controller.addTransaction((TransactionViewRequest) null, null, null))
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

    @Nested
    @DisplayName("PROCESS-ENTER-KEY - the ordered EVALUATE CONFIRMI of L169-188")
    class ConfirmEvaluation {
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

    @Nested
    @DisplayName("The next-identifier browse - L444-449 and L642-706")
    class NextIdentifier {
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
            TransactionRepository.Browse browse = positionedBrowse();
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
            TransactionRepository.Browse browse = positionedBrowse();
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
            ProgramState state = new ProgramState(controller.codec());
            state.setCommarea(reenterCommarea());
            state.setActidinI(ACCOUNT_ID);
            xrefByAccountFound();
            TransactionRepository.Browse browse = positionedBrowse();
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

    @Nested
    @DisplayName("ADD-TRANSACTION and WRITE-TRANSACT-FILE - the record and the three write arms")
    class TheWrite {
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
        @DisplayName("a refused write records no image, because the dataset gained no record")
        void aRefusedWriteRecordsNoImage() {
            // writtenRecords() is the account of what EXEC CICS WRITE inserted. DFHRESP(DUPKEY) and
            // DFHRESP(DUPREC) mean the key was already present and the existing record is untouched, so
            // nothing was inserted and nothing may be recorded. A parity case reads this list onto its
            // WRITES channel, so counting a refused hand-over there would pin a row TRANSACT does not
            // hold - and would make the channel mean "attempted" for one case and "inserted" for the
            // other nineteen.
            xrefByAccountFound();
            browseWithLastId();
            when(transactionRepository.write(any())).thenReturn(
                    TransactionRepository.WriteResult.duplicate(
                            TransactionRepository.CICS_FILE_NAME));

            ProgramState state = controller.mainPara(completeRequest());

            assertThat(state.writtenRecords())
                    .as("the duplicate arm inserted nothing")
                    .isEmpty();
            assertThat(state.message().trim())
                    .as("and the arm it took is the one that says so")
                    .isEqualTo(TransactionViewController.MSG_TRAN_ID_ALREADY_EXISTS);
        }

        @Test
        @DisplayName("an accepted write records the image at its full 350 bytes")
        void anAcceptedWriteRecordsTheImage() {
            xrefByAccountFound();
            browseWithLastId();
            when(transactionRepository.write(any())).thenReturn(
                    TransactionRepository.WriteResult.written(
                            TransactionRepository.CICS_FILE_NAME));

            ProgramState state = controller.mainPara(completeRequest());

            assertThat(state.writtenRecords()).hasSize(1);
            assertThat(state.writtenRecords().get(0))
                    .as("the whole record including the trailing FILLER - gates G19 and G21")
                    .hasSize(TranRecord.RECORD_LENGTH)
                    .hasSize(350);
        }

        @Test
        @DisplayName("a refused write still handed over a complete 350-byte record")
        void aRefusedWriteStillHandedOverACompleteRecord() {
            // The half of the old expectation that is still true, asserted where it belongs: on the
            // ARGUMENT of the call rather than on the contents of the dataset. COTRN02C composes the
            // whole record at :450-465 and hands it to EXEC CICS WRITE at :713 before the EVALUATE can
            // reject it, so a translation that composed a short or half-filled record would be wrong even
            // on the arm where the write fails. parity/COTRN02C case20 delegates this assertion here for
            // exactly that reason: its WRITES channel is now empty, as a refused write requires.
            xrefByAccountFound();
            browseWithLastId();
            when(transactionRepository.write(any())).thenReturn(
                    TransactionRepository.WriteResult.duplicate(
                            TransactionRepository.CICS_FILE_NAME));

            controller.mainPara(completeRequest());

            ArgumentCaptor<TranRecord> handedOver = ArgumentCaptor.forClass(TranRecord.class);
            verify(transactionRepository).write(handedOver.capture());
            String image = handedOver.getValue().displayImage();

            assertThat(image)
                    .as("350 bytes, FILLER included - G19 and G21 on the composed record")
                    .hasSize(TranRecord.RECORD_LENGTH)
                    .hasSize(350);
            assertThat(image.substring(330))
                    .as("bytes 331-350 are the trailing FILLER PIC X(20), space filled")
                    .isEqualTo(" ".repeat(20));
            assertThat(image.substring(132, 143))
                    .as("TRAN-AMT at 133-143 is the zoned S9(09)V99 image of +12.34, scale 2 truncating "
                            + "- G23 and G24")
                    .isEqualTo("0000000123D");
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

    @Nested
    @DisplayName("COPY-LAST-TRAN-DATA - the PF5 convenience of L471-495")
    class CopyLastTranData {
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
            request.setAid(PfKeyResolver.aidImage(CicsAid.DFHPF5));

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
            request.setAid(PfKeyResolver.aidImage(CicsAid.DFHPF5));

            ProgramState state = controller.mainPara(request);

            verify(transactionRepository).write(any());
            assertThat(state.errmsgO()).startsWith("Transaction added successfully.");
        }

        @Test
        @DisplayName("a key failure stops the copy before the browse")
        void aKeyFailureStopsTheCopy() {
            TransactionViewRequest request = enterRequest();
            request.setActidin("NOTNUMERIC ");
            request.setAid(PfKeyResolver.aidImage(CicsAid.DFHPF5));

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
            request.setAid(PfKeyResolver.aidImage(CicsAid.DFHPF5));

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
            assertThat(TransactionViewController.isSpacesOrLowValues(" \u0000 ")).isFalse();
            assertThat(TransactionViewController.isSpacesOrLowValues("\u0000 \u0000")).isFalse();
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
        @DisplayName("the one-character aid image IS the EIBAID byte, and nothing else is a byte")
        void theAidReconstruction() {
            assertThat(TransactionViewController.eibAidOf(PfKeyResolver.aidImage(CicsAid.DFHENTER)))
                    .isEqualTo(CicsAid.DFHENTER);
            assertThat(TransactionViewController.eibAidOf(PfKeyResolver.aidImage(CicsAid.DFHPF3)))
                    .isEqualTo(CicsAid.DFHPF3);
            assertThat(TransactionViewController.eibAidOf(PfKeyResolver.aidImage(CicsAid.DFHPF15)))
                    .as("PF15 is not PF3: COTRN02C compares EIBAID and takes WHEN OTHER at :132")
                    .isEqualTo(CicsAid.DFHPF15);
            assertThat(TransactionViewController.eibAidOf(PfKeyResolver.aidImage(CicsAid.DFHPA3)))
                    .as("a byte CSSTRPFY names in no branch is still a byte a terminal can send")
                    .isEqualTo(CicsAid.DFHPA3);

            assertThat(TransactionViewController.eibAidOf("ENTER")).isEqualTo(CicsAid.DFHNULL);
            assertThat(TransactionViewController.eibAidOf("PFK03")).isEqualTo(CicsAid.DFHNULL);
            assertThat(TransactionViewController.eibAidOf("")).isEqualTo(CicsAid.DFHNULL);
            assertThat(TransactionViewController.eibAidOf(null)).isEqualTo(CicsAid.DFHNULL);
            assertThat(TransactionViewController.eibAidOf(String.valueOf((char) 0x01F3)))
                    .as("a character above the one-byte AID space is never narrowed onto DFHPF3")
                    .isEqualTo(CicsAid.DFHNULL);
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

    @Nested
    @DisplayName("ProgramState - COTRN02C's WORKING-STORAGE, per request")
    class TheWorkingStorage {
        @Test
        @DisplayName("a fresh area is the storage image, zeros and flags off")
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
            assertThat(state.actidinI())
                    .as("01 COTRN2AO is declared at L82 with no VALUE clause, so a freshly opened area "
                            + "holds LOW-VALUES and not spaces - L122's MOVE LOW-VALUES is the source's "
                            + "own statement of what the area holds when nothing has been painted")
                    .isEqualTo(ScreenFieldImage.unpainted(TransactionViewResponse.ACTIDINO_LENGTH));
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
            TransactionRepository.Browse browse = positionedBrowse();

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

        private ObjectMapper carddemoMapper() {
            Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
            new WebConfig().carddemoJacksonCustomizer(TEST_PROFILE_CHARSET).customize(builder);
            return builder.build();
        }

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
                    .andExpect(jsonPath("$.trnname").value("CT02"))
                    .andExpect(jsonPath("$.pgmname").value("COTRN02C"))
                    .andExpect(jsonPath("$.nextProgram").value("COTRN02C"))
                    .andExpect(jsonPath("$.nextMapset").value("COTRN02"))
                    .andExpect(jsonPath("$.nextMap").value("COTRN2A"))
                    .andExpect(jsonPath("$.errmsg")
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
                    .contains("\"actidin\"")
                    .contains("\"errmsg\"");
        }

        @Test
        @DisplayName("the adapter is the same code path as mainPara, so it adds nothing of its own")
        void theAdapterIsThin() {
            xrefByAccountFound();
            browseWithLastId();
            writeSucceeds();

            ScreenResponse<TransactionViewResponse> answer =
                    controller.addTransaction(completeRequest(), null, null);

            TransactionViewResponse viaAdapter = answer.screen();
            assertThat(viaAdapter.getErrmsgo()).startsWith("Transaction added successfully.");
            assertThat(viaAdapter.getNextProgram()).isEqualTo("COTRN02C");
            assertThat(viaAdapter.getNextMapset()).isEqualTo("COTRN02");
            assertThat(viaAdapter.getNextMap()).isEqualTo("COTRN2A");
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

    @Nested
    @DisplayName("The transaction boundary - the TRANSACT insert must survive the connection's return")
    class TheTransactionBoundary {
        private static final String MASTER_DS = "CARDDEMO.TEST.TXBOUND.TRANSACT";

        private static final String SYSTRAN_DS = "CARDDEMO.TEST.TXBOUND.SYSTRAN";

        private static final String IMAGE_COLUMN = "RECORD_IMAGE";

        private static final PhysicalSequence ORDINAL = PhysicalSequence.of("_ROWID_");

        private static final String SEEDED_TRAN_ID = "0000000000000001";

        private static final String ADDED_TRAN_ID = "0000000000000002";

        private static final String SECOND_ADDED_TRAN_ID = "0000000000000003";

        @Test
        @DisplayName("the added transaction is still there after the request, on a pool that does not "
                + "auto-commit")
        void theInsertIsCommitted() {
            withTransactionalContext(true, (controller, verifier) -> {
                ScreenResponse<TransactionViewResponse> response =
                        controller.addTransaction(completeRequest(), null, null);

                assertThat(response.screen().getErrmsgo())
                        .as("the screen the operator is shown")
                        .startsWith("Transaction added successfully.  Your Tran ID is "
                                + ADDED_TRAN_ID + ".");
                assertThat(recordsIn(verifier))
                        .as("the seeded mark and the record the screen promised, read back on another "
                                + "connection")
                        .hasSize(2)
                        .allSatisfy(image -> assertThat(image)
                                .as("gate G19 - the copybook's 350 bytes")
                                .hasSize(TranRecord.RECORD_LENGTH))
                        .satisfiesExactlyInAnyOrder(
                                seeded -> assertThat(seeded).startsWith(SEEDED_TRAN_ID),
                                added -> assertThat(added).startsWith(ADDED_TRAN_ID));
            });
        }

        @Test
        @DisplayName("without the boundary the write is refused outright, so no identifier is ever "
                + "promised for a record that was not stored")
        void withoutTheBoundaryTheInsertIsRefused() {
            withTransactionalContext(false, (controller, verifier) -> {
                assertThatIllegalStateException()
                        .isThrownBy(() -> controller.addTransaction(completeRequest(), null, null))
                        .withMessageContaining("no transaction is open on this thread")
                        .withMessageContaining("changes stored records");

                assertThat(recordsIn(verifier))
                        .as("and nothing was written, so only the seeded mark remains")
                        .hasSize(1)
                        .allSatisfy(image -> assertThat(image).startsWith(SEEDED_TRAN_ID));
            });
        }

        @Test
        @DisplayName("the probe and the write are one unit, so the next identifier is the committed "
                + "high-water mark")
        void theProbeSeesWhatTheLastRequestCommitted() {
            withTransactionalContext(true, (controller, verifier) -> {
                controller.addTransaction(completeRequest(), null, null);

                ScreenResponse<TransactionViewResponse> second =
                        controller.addTransaction(completeRequest(), null, null);

                assertThat(second.screen().getErrmsgo())
                        .as("the high-water mark advanced, so this is an add and not a duplicate")
                        .startsWith("Transaction added successfully.  Your Tran ID is "
                                + SECOND_ADDED_TRAN_ID + ".");
                assertThat(recordsIn(verifier))
                        .as("both added records are durable, on top of the seeded mark")
                        .hasSize(3)
                        .satisfiesExactlyInAnyOrder(
                                seeded -> assertThat(seeded).startsWith(SEEDED_TRAN_ID),
                                first -> assertThat(first).startsWith(ADDED_TRAN_ID),
                                next -> assertThat(next).startsWith(SECOND_ADDED_TRAN_ID));
            });
        }

        @Test
        @DisplayName("the insert enlists in the unit of work, so a task that never commits leaves "
                + "nothing behind")
        void theInsertIsRolledBackWithTheUnitOfWork() {
            withTransactionalContext(true, (controller, verifier) -> {
                TransactionTemplate abandoned = new TransactionTemplate(new JdbcTransactionManager(
                        Objects.requireNonNull(verifier.getDataSource())));

                ScreenResponse<TransactionViewResponse> response = abandoned.execute(status -> {
                    ScreenResponse<TransactionViewResponse> answer =
                            controller.addTransaction(completeRequest(), null, null);
                    status.setRollbackOnly();
                    return answer;
                });

                assertThat(response).isNotNull();
                assertThat(response.screen().getErrmsgo())
                        .as("the program is unchanged - it still reports what it did")
                        .startsWith("Transaction added successfully.");
                assertThat(recordsIn(verifier))
                        .as("but the unit of work was abandoned, so the insert went with it and only "
                                + "the seeded mark is left")
                        .hasSize(1)
                        .allSatisfy(image -> assertThat(image).startsWith(SEEDED_TRAN_ID));
            });
        }

        @Test
        @DisplayName("the boundary is on the HTTP entry point, and mainPara stays free of it")
        void theBoundaryIsOnTheEntryPoint() throws Exception {
            assertThat(TransactionViewController.class
                    .getMethod("addTransaction", TransactionViewRequest.class, Integer.class,
                            Integer.class)
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
                    .getMethod("addTransaction", TransactionViewRequest.class, Integer.class,
                            Integer.class).getModifiers()))
                    .as("nor can a final method be overridden by the proxy")
                    .isFalse();
        }

        private void withTransactionalContext(boolean transactionManagementEnabled,
                java.util.function.BiConsumer<TransactionViewController, JdbcTemplate> body) {
            xrefByAccountFound();
            String url = "jdbc:h2:mem:tranview_tx" + UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
            HikariDataSource pool = new HikariDataSource();
            pool.setJdbcUrl(url);
            pool.setDriverClassName("org.h2.Driver");
            pool.setUsername("sa");
            pool.setPassword("");
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
                            schema.update("INSERT INTO \"" + MASTER_DS + "\" (\"" + IMAGE_COLUMN
                                    + "\") VALUES (?)", seededImage());
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

        private static String seededImage() {
            return new String(recordWithId(SEEDED_TRAN_ID).encode(CHARSET), CHARSET);
        }

        private List<String> recordsIn(JdbcTemplate template) {
            return template.queryForList("SELECT \"" + IMAGE_COLUMN + "\" FROM \"" + MASTER_DS + "\"",
                    String.class);
        }

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

        @Configuration
        @EnableTransactionManagement
        static class TransactionManagementEnabled {
        }
    }

    @Nested
    @DisplayName("CVTRA05Y offsets - the written 350 bytes cut at the copybook's own numbers")
    class CopybookOffsets {
        private static final String POSITIVE_AMOUNT_IMAGE = "0000000123D";

        private static final String NEGATIVE_AMOUNT_IMAGE = "0000000123M";

        private static final String ZERO_AMOUNT_IMAGE = "0000000000{";

        private String writtenImage(String amount) {
            xrefByAccountFound();
            browseWithLastId();
            writeSucceeds();
            TransactionViewRequest request = completeRequest();
            request.setTrnamt(amount);

            ProgramState state = controller.mainPara(request);

            assertThat(state.writtenRecords())
                    .as("one EXEC CICS WRITE, so one image")
                    .hasSize(1);
            return state.writtenRecords().get(0);
        }

        private static String span(String image, int offset, int length) {
            return image.substring(offset, offset + length);
        }

        @Test
        @DisplayName("the thirteen data spans each sit at their copybook offset, at their declared width")
        void everySpanSitsAtItsCopybookOffset() {
            String image = writtenImage("+00000012.34");

            assertThat(image).hasSize(350).hasSize(TranRecord.RECORD_LENGTH);
            assertThat(span(image, 0, 16))
                    .as("TRAN-ID PIC X(16) at offset 0 - the generated high-water-mark key")
                    .isEqualTo("0000000000000051");
            assertThat(span(image, 16, 2))
                    .as("TRAN-TYPE-CD PIC X(02) at offset 16")
                    .isEqualTo("01");
            assertThat(span(image, 18, 4))
                    .as("TRAN-CAT-CD PIC 9(04) at offset 18, zero-filled to its width")
                    .isEqualTo("0001");
            assertThat(span(image, 22, 10))
                    .as("TRAN-SOURCE PIC X(10) at offset 22")
                    .isEqualTo("POS TERM  ");
            assertThat(span(image, 32, 100))
                    .as("TRAN-DESC PIC X(100) at offset 32, TDESCI X(60) padded on the right")
                    .isEqualTo("Coffee and a newspaper" + " ".repeat(78));
            assertThat(span(image, 132, 11))
                    .as("TRAN-AMT PIC S9(09)V99 at offset 132 - eleven bytes, not twelve")
                    .isEqualTo(POSITIVE_AMOUNT_IMAGE);
            assertThat(span(image, 143, 9))
                    .as("TRAN-MERCHANT-ID PIC 9(09) at offset 143")
                    .isEqualTo("000123456");
            assertThat(span(image, 152, 50))
                    .as("TRAN-MERCHANT-NAME PIC X(50) at offset 152")
                    .isEqualTo("Kwik-E-Mart" + " ".repeat(39));
            assertThat(span(image, 202, 50))
                    .as("TRAN-MERCHANT-CITY PIC X(50) at offset 202")
                    .isEqualTo("Springfield" + " ".repeat(39));
            assertThat(span(image, 252, 10))
                    .as("TRAN-MERCHANT-ZIP PIC X(10) at offset 252")
                    .isEqualTo("0000012345");
            assertThat(span(image, 262, 16))
                    .as("TRAN-CARD-NUM PIC X(16) at offset 262 - the card the xref resolved")
                    .isEqualTo(CARD_NUMBER);
            assertThat(span(image, 278, 26))
                    .as("TRAN-ORIG-TS PIC X(26) at offset 278, TORIGDTI X(10) padded on the right")
                    .isEqualTo(VALID_DATE + " ".repeat(16));
            assertThat(span(image, 304, 26))
                    .as("TRAN-PROC-TS PIC X(26) at offset 304 - from TPROCDTI at L465, not from the clock")
                    .isEqualTo(VALID_DATE + " ".repeat(16));
        }

        @Test
        @DisplayName("gate G21: FILLER PIC X(20) at offset 330 is present and space-filled")
        void theTrailingFillerIsSpaceFilled() {
            String image = writtenImage("+00000012.34");

            assertThat(span(image, 330, 20))
                    .as("omit it and the record is 330 bytes with every consumer's offsets still right")
                    .isEqualTo(" ".repeat(20))
                    .isEqualTo(" ".repeat(TranRecord.FILLER_LENGTH));
            assertThat(image.substring(330))
                    .as("nothing follows FILLER: 330 + 20 is the whole record")
                    .hasSize(20);
        }

        @Test
        @DisplayName("the offset constants are the copybook's, and the fourteen spans tile 350 with no gap")
        void theSpansTileTheRecord() {
            int[][] layout = {
                {TranRecord.TRAN_ID_OFFSET, TranRecord.TRAN_ID_LENGTH},
                {TranRecord.TRAN_TYPE_CD_OFFSET, TranRecord.TRAN_TYPE_CD_LENGTH},
                {TranRecord.TRAN_CAT_CD_OFFSET, TranRecord.TRAN_CAT_CD_LENGTH},
                {TranRecord.TRAN_SOURCE_OFFSET, TranRecord.TRAN_SOURCE_LENGTH},
                {TranRecord.TRAN_DESC_OFFSET, TranRecord.TRAN_DESC_LENGTH},
                {TranRecord.TRAN_AMT_OFFSET, TranRecord.TRAN_AMT_LENGTH},
                {TranRecord.TRAN_MERCHANT_ID_OFFSET, TranRecord.TRAN_MERCHANT_ID_LENGTH},
                {TranRecord.TRAN_MERCHANT_NAME_OFFSET, TranRecord.TRAN_MERCHANT_NAME_LENGTH},
                {TranRecord.TRAN_MERCHANT_CITY_OFFSET, TranRecord.TRAN_MERCHANT_CITY_LENGTH},
                {TranRecord.TRAN_MERCHANT_ZIP_OFFSET, TranRecord.TRAN_MERCHANT_ZIP_LENGTH},
                {TranRecord.TRAN_CARD_NUM_OFFSET, TranRecord.TRAN_CARD_NUM_LENGTH},
                {TranRecord.TRAN_ORIG_TS_OFFSET, TranRecord.TRAN_ORIG_TS_LENGTH},
                {TranRecord.TRAN_PROC_TS_OFFSET, TranRecord.TRAN_PROC_TS_LENGTH},
                {TranRecord.FILLER_OFFSET, TranRecord.FILLER_LENGTH},
            };
            int[][] copybook = {
                {0, 16}, {16, 2}, {18, 4}, {22, 10}, {32, 100}, {132, 11}, {143, 9}, {152, 50},
                {202, 50}, {252, 10}, {262, 16}, {278, 26}, {304, 26}, {330, 20},
            };

            assertThat(layout)
                    .as("the model's constants against the copybook read out by hand")
                    .isDeepEqualTo(copybook);

            int cursor = 0;
            for (int[] field : layout) {
                assertThat(field[0])
                        .as("span at offset %d follows the previous one with no gap and no overlap",
                                field[0])
                        .isEqualTo(cursor);
                cursor += field[1];
            }
            assertThat(cursor).isEqualTo(TranRecord.RECORD_LENGTH).isEqualTo(350);
            assertThat(TranRecord.sumOfDeclaredSpanLengths()).isEqualTo(350);
        }

        @Test
        @DisplayName("the amount's sign is overpunched into the trailing byte, for both signs and zero")
        void theAmountSignIsOverpunched() {
            assertThat(span(writtenImage("+00000012.34"), 132, 11))
                    .isEqualTo(POSITIVE_AMOUNT_IMAGE)
                    .hasSize(TranRecord.TRAN_AMT_LENGTH)
                    .endsWith("D");
            assertThat(span(writtenImage("-00000012.34"), 132, 11))
                    .as("the same ten digits: only the zone of the trailing byte changes")
                    .isEqualTo(NEGATIVE_AMOUNT_IMAGE)
                    .startsWith(POSITIVE_AMOUNT_IMAGE.substring(0, 10))
                    .endsWith("M");
            assertThat(span(writtenImage("+00000000.00"), 132, 11))
                    .as("positive zero is a brace, not a digit - a zero test cannot stand in for bytes")
                    .isEqualTo(ZERO_AMOUNT_IMAGE);
        }

        @Test
        @DisplayName("the amount span is stored truncated at scale 2, never rounded")
        void theAmountIsTruncatedNotRounded() {
            TranRecord record = new TranRecord(CHARSET);

            record.moveTranAmt(CobolDecimal.storeAtPicture(new BigDecimal("12.349"),
                    TranRecord.TRAN_AMT_INTEGER_DIGITS, TranRecord.TRAN_AMT_SCALE));

            assertThat(CobolDecimal.COBOL_ROUNDING)
                    .as("one policy, in one place: never HALF_UP, HALF_EVEN, CEILING or FLOOR")
                    .isEqualTo(RoundingMode.DOWN);
            assertThat(record.tranAmt())
                    .isEqualByComparingTo(new BigDecimal("12.34"))
                    .isNotEqualByComparingTo(new BigDecimal("12.35"));
            assertThat(record.tranAmt().scale()).isEqualTo(2).isEqualTo(TranRecord.TRAN_AMT_SCALE);
            assertThat(span(record.displayImage(), 132, 11)).isEqualTo(POSITIVE_AMOUNT_IMAGE);
        }
    }

    @Nested
    @DisplayName("STARTBR then READPREV then ENDBR - the ordering of L445-447, not merely the calls")
    class TheBrowseSequence {
        @Test
        @DisplayName("the add issues STARTBR, READPREV, ENDBR and only then WRITE")
        void theFourCommandsIssueInTheSourcesOrder() {
            xrefByAccountFound();
            TransactionRepository.Browse browse = browseWithLastId();
            writeSucceeds();

            controller.mainPara(completeRequest());

            InOrder ordered = inOrder(transactionRepository, browse);
            ordered.verify(transactionRepository)
                    .startBrowse(TransactionRepository.BrowseDirection.BACKWARD);
            ordered.verify(browse).readPrev();
            ordered.verify(browse).endBrowse();
            ordered.verify(transactionRepository).write(any());
        }

        @Test
        @DisplayName("an empty master keeps the same order: the ENDFILE arm does not skip the ENDBR")
        void anEmptyMasterKeepsTheOrder() {
            xrefByAccountFound();
            TransactionRepository.Browse browse = browseYielding(
                    TransactionRepository.ReadResult.endOfFile(
                            TransactionRepository.CICS_FILE_NAME));
            writeSucceeds();

            ProgramState state = controller.mainPara(completeRequest());

            InOrder ordered = inOrder(transactionRepository, browse);
            ordered.verify(transactionRepository)
                    .startBrowse(TransactionRepository.BrowseDirection.BACKWARD);
            ordered.verify(browse).readPrev();
            ordered.verify(browse).endBrowse();
            ordered.verify(transactionRepository).write(any());
            assertThat(state.tranRecord().tranId())
                    .as("ENDFILE moved zeros into TRAN-ID at L689, so the first key ever issued is 1")
                    .isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("a rejected READPREV still releases the browse, and issues no write")
        void aRejectedReadStillReleasesTheBrowse() {
            xrefByAccountFound();
            TransactionRepository.Browse browse = browseYielding(
                    TransactionRepository.ReadResult.other(TransactionRepository.CICS_FILE_NAME,
                            TransactionRepository.PERMANENT_ERROR_STATUS));

            ProgramState state = controller.mainPara(completeRequest());

            InOrder ordered = inOrder(transactionRepository, browse);
            ordered.verify(transactionRepository)
                    .startBrowse(TransactionRepository.BrowseDirection.BACKWARD);
            ordered.verify(browse).readPrev();
            ordered.verify(browse).endBrowse();
            verify(transactionRepository, never()).write(any());
            assertThat(state.browseOpen()).isFalse();
            assertThat(state.message()).startsWith("Unable to lookup Transaction...");
        }

        @Test
        @DisplayName("PF5's copy walks the same three commands in the same order, and writes nothing")
        void theCopyWalksTheSameSequence() {
            TransactionViewRequest request = enterRequest();
            request.setActidin(ACCOUNT_ID);
            request.setAid(PfKeyResolver.aidImage(CicsAid.DFHPF5));
            xrefByAccountFound();
            TransactionRepository.Browse browse = browseWithLastId();

            controller.mainPara(request);

            InOrder ordered = inOrder(transactionRepository, browse);
            ordered.verify(transactionRepository)
                    .startBrowse(TransactionRepository.BrowseDirection.BACKWARD);
            ordered.verify(browse).readPrev();
            ordered.verify(browse).endBrowse();
            verify(transactionRepository, never())
                    .write(any());
        }

        @Test
        @DisplayName("the identifier comes from the browse and from no field of the request")
        void theIdentifierIsNeverTyped() {
            xrefByAccountFound();
            browseYielding(TransactionRepository.ReadResult.found(
                    TransactionRepository.CICS_FILE_NAME, recordWithId("0000000000000999")));
            writeSucceeds();

            ProgramState state = controller.mainPara(completeRequest());

            assertThat(state.tranRecord().tranId()).isEqualTo("0000000000001000");
            assertThat(state.wsTranIdN()).isEqualTo(1_000L);
            assertThat(TransactionViewResponse.ScreenField.values())
                    .as("no screen field could have carried it")
                    .noneMatch(field -> field.label().contains("TRNID"));
        }
    }

    @Nested
    @DisplayName("CSUTLDTC's eighty bytes - the caller's split, and the offsets the 2513 rule reads")
    class TheCsutldtcProjection {
        private static final int SEVERITY_OFFSET = 0;

        private static final int SEVERITY_LENGTH = 4;

        private static final int FILLER_OFFSET = 4;

        private static final int FILLER_LENGTH = 11;

        private static final int MESSAGE_NUMBER_OFFSET = 15;

        private static final int MESSAGE_NUMBER_LENGTH = 4;

        private static final int MESSAGE_OFFSET = 19;

        private static final int MESSAGE_LENGTH = 61;

        private ProgramState callWith(String date) {
            ProgramState state = new ProgramState(controller.codec());
            controller.callCsutldtc(state, date);
            return state;
        }

        @Test
        @DisplayName("the four spans are 4 + 11 + 4 + 61 and they close exactly eighty")
        void theFourSpansCloseEighty() {
            String eighty = callWith(VALID_DATE).csutldtcResult().message();

            assertThat(eighty)
                    .hasSize(80)
                    .hasSize(DateUtilityJob.LS_RESULT_LENGTH);
            assertThat(SEVERITY_LENGTH + FILLER_LENGTH + MESSAGE_NUMBER_LENGTH + MESSAGE_LENGTH)
                    .as("COTRN02C:66-69 sums to the LS-RESULT PIC X(80) it redefines")
                    .isEqualTo(DateUtilityJob.LS_RESULT_LENGTH);
            assertThat(SEVERITY_OFFSET + SEVERITY_LENGTH).isEqualTo(FILLER_OFFSET);
            assertThat(FILLER_OFFSET + FILLER_LENGTH).isEqualTo(MESSAGE_NUMBER_OFFSET);
            assertThat(MESSAGE_NUMBER_OFFSET + MESSAGE_NUMBER_LENGTH).isEqualTo(MESSAGE_OFFSET);
            assertThat(eighty.substring(MESSAGE_OFFSET)).hasSize(MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("severity reads at 0-3 and the message number at 15-18, with 'Mesg Code:' between")
        void theTypedViewAgreesWithTheBytes() {
            DateUtilityJob.DateValidationResult result = callWith(VALID_DATE).csutldtcResult();
            String eighty = result.message();

            assertThat(eighty.substring(SEVERITY_OFFSET, SEVERITY_OFFSET + SEVERITY_LENGTH))
                    .as("CSUTLDTC-RESULT-SEV-CD, which is also WS-SEVERITY at CSUTLDTC.cbl:43")
                    .isEqualTo(result.severityCode())
                    .isEqualTo("0000");
            assertThat(eighty.substring(FILLER_OFFSET, FILLER_OFFSET + FILLER_LENGTH))
                    .as("the caller calls it FILLER; the producer fills it with a literal at :45")
                    .isEqualTo("Mesg Code: ");
            assertThat(eighty.substring(MESSAGE_NUMBER_OFFSET,
                    MESSAGE_NUMBER_OFFSET + MESSAGE_NUMBER_LENGTH))
                    .as("CSUTLDTC-RESULT-MSG-NUM, which is WS-MSG-NO at CSUTLDTC.cbl:46")
                    .isEqualTo(result.messageNumber())
                    .isEqualTo("0000");
            assertThat(eighty.substring(MESSAGE_OFFSET))
                    .as("the caller's X(61) starts at the producer's space before WS-RESULT")
                    .startsWith(" Date is valid");
        }

        @Test
        @DisplayName("2513 appears exactly once, at offset 15, and not at 14 or 16")
        void theToleratedNumberSitsAtTheDeclaredOffset() {
            DateUtilityJob.DateValidationResult result = callWith(TOLERATED_DATE).csutldtcResult();
            String eighty = result.message();

            assertThat(result.messageNumber())
                    .isEqualTo(TransactionViewController.CSUTLDTC_TOLERATED_MESSAGE_NUMBER)
                    .isEqualTo("2513");
            assertThat(eighty.indexOf("2513"))
                    .as("the only occurrence, and it is where COTRN02C:68 says it is")
                    .isEqualTo(MESSAGE_NUMBER_OFFSET);
            assertThat(eighty.lastIndexOf("2513")).isEqualTo(MESSAGE_NUMBER_OFFSET);
            assertThat(eighty.substring(MESSAGE_NUMBER_OFFSET - 1,
                    MESSAGE_NUMBER_OFFSET - 1 + MESSAGE_NUMBER_LENGTH))
                    .as("one byte early reads the FILLER's trailing space and a digit")
                    .isEqualTo(" 251")
                    .isNotEqualTo("2513");
            assertThat(eighty.substring(MESSAGE_NUMBER_OFFSET + 1,
                    MESSAGE_NUMBER_OFFSET + 1 + MESSAGE_NUMBER_LENGTH))
                    .as("one byte late runs into CSUTLDTC-RESULT-MSG")
                    .isEqualTo("513 ")
                    .isNotEqualTo("2513");
        }

        @Test
        @DisplayName("both non-zero results carry severity 0003, so the verdict cannot be the severity")
        void theVerdictIsTheMessageNumberAndNotTheSeverity() {
            ProgramState tolerated = callWith(TOLERATED_DATE);
            ProgramState rejected = callWith(REJECTED_DATE);

            assertThat(tolerated.csutldtcResult().severityCode())
                    .isEqualTo(rejected.csutldtcResult().severityCode())
                    .isEqualTo("0003")
                    .isNotEqualTo(TransactionViewController.CSUTLDTC_SEVERITY_OK);
            assertThat(tolerated.csutldtcResult().messageNumber()).isEqualTo("2513");
            assertThat(rejected.csutldtcResult().messageNumber()).isEqualTo("2517");
            assertThat(controller.callCsutldtc(new ProgramState(controller.codec()), TOLERATED_DATE))
                    .as("L400: NOT = '2513' fails, so nothing is reported")
                    .isTrue();
            assertThat(controller.callCsutldtc(new ProgramState(controller.codec()), REJECTED_DATE))
                    .as("the same severity, four different bytes at offset 15, and it rejects")
                    .isFalse();
            assertThat(controller.callCsutldtc(new ProgramState(controller.codec()), VALID_DATE))
                    .as("L397: severity '0000' is accepted before the message number is even read")
                    .isTrue();
        }

        @Test
        @DisplayName("the call passes LS-DATE X(10) and LS-DATE-FORMAT X(10) and takes back X(80)")
        void theThreeParameterContractIsHonoured() {
            ProgramState state = callWith(VALID_DATE);

            assertThat(state.csutldtcDate())
                    .hasSize(DateUtilityJob.LS_DATE_LENGTH)
                    .hasSize(10)
                    .isEqualTo(VALID_DATE);
            assertThat(state.csutldtcDateFormat())
                    .as("WS-DATE-FORMAT at COTRN02C:60, moved in at L390 and L410")
                    .hasSize(DateUtilityJob.LS_DATE_FORMAT_LENGTH)
                    .isEqualTo(TransactionViewController.WS_DATE_FORMAT)
                    .isEqualTo("YYYY-MM-DD");
            assertThat(state.csutldtcResult().message())
                    .hasSize(DateUtilityJob.LS_RESULT_LENGTH);
        }

        @Test
        @DisplayName("a short date is padded to ten before the call, because the parameter is X(10)")
        void aShortDateIsPaddedToTheParameterWidth() {
            ProgramState shortDate = callWith("2022-7-1");
            ProgramState empty = callWith("");

            assertThat(shortDate.csutldtcDate())
                    .hasSize(DateUtilityJob.LS_DATE_LENGTH)
                    .isEqualTo("2022-7-1  ");
            assertThat(empty.csutldtcDate())
                    .as("an absent date arrives as ten spaces, not as a zero-length argument")
                    .hasSize(DateUtilityJob.LS_DATE_LENGTH)
                    .isEqualTo(" ".repeat(DateUtilityJob.LS_DATE_LENGTH));
            assertThat(empty.csutldtcResult().severityCode())
                    .as("ten spaces are non-numeric data, which CEEDAYS reports at severity 3")
                    .isEqualTo("0003")
                    .isNotEqualTo(TransactionViewController.CSUTLDTC_SEVERITY_OK);
            assertThat(empty.csutldtcResult().messageNumber())
                    .as("2520, which is not the tolerated 2513, so this site rejects")
                    .isEqualTo("2520")
                    .isNotEqualTo(TransactionViewController.CSUTLDTC_TOLERATED_MESSAGE_NUMBER);
            assertThat(controller.callCsutldtc(new ProgramState(controller.codec()), ""))
                    .isFalse();
        }

        @Test
        @DisplayName("the two call sites report their own literal, each ending in three dots")
        void eachSiteCarriesItsOwnLiteral() {
            assertThat(TransactionViewController.MSG_ORIG_DATE_INVALID)
                    .isEqualTo("Orig Date - Not a valid date...")
                    .endsWith("...");
            assertThat(TransactionViewController.MSG_PROC_DATE_INVALID)
                    .isEqualTo("Proc Date - Not a valid date...")
                    .endsWith("...");
            assertThat(TransactionViewController.MSG_ORIG_DATE_INVALID)
                    .isNotEqualTo(TransactionViewController.MSG_PROC_DATE_INVALID);
        }
    }

    @Nested
    @DisplayName("The highlight policy (gate G38) and the width of what travels (gate G37)")
    class TheHighlightPolicy {
        @Test
        @DisplayName("CSSETATY would order DFHRED and an asterisk for a blank field under re-entry")
        void whatCssetatyWouldOrder() {
            FieldHighlight underReenter = FieldAttributeSetter.resolveFromFlags(false, true, true);
            FieldHighlight underFirstEntry = FieldAttributeSetter.resolveFromFlags(false, true, false);

            assertThat(underReenter.colourItemAssigned()).isTrue();
            assertThat(underReenter.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(underReenter.outputItemAssigned()).isTrue();
            assertThat(underReenter.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);
            assertThat(underFirstEntry.colourItemAssigned())
                    .as("CSSETATY's outer test requires CDEMO-PGM-REENTER, so first entry orders nothing")
                    .isFalse();
        }

        @Test
        @DisplayName("a re-enter rejection recolours nothing and writes no asterisk into any field")
        void aReenterRejectionAppliesNoHighlight() {
            xrefByAccountFound();
            TransactionViewRequest request = enterRequest();
            request.setActidin(ACCOUNT_ID);
            request.setTtypcd("  ");

            ProgramState state = controller.mainPara(request);

            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message()).startsWith("Type CD can NOT be empty...");
            assertThat(state.commarea().isReenter())
                    .as("this is the re-enter arm, the only state CSSETATY could fire in")
                    .isTrue();
            assertThat(state.cursorRequestedOn(TransactionViewResponse.ScreenField.TTYPCD))
                    .as("MOVE -1 TO TTYPCDL at L256 is the whole of this program's field emphasis")
                    .isTrue();
            for (TransactionViewResponse.ScreenField field
                    : TransactionViewResponse.ScreenField.values()) {
                assertThat(state.response().getMetadata(field).isDefault())
                        .as("%s keeps the default attribute quad", field.label())
                        .isTrue();
                assertThat(state.response().getMetadata(field).getColour())
                        .as("%s is not recoloured DFHRED", field.label())
                        .isNotEqualTo(BmsAttributes.DFHRED);
                assertThat(state.response().getOutputItem(field))
                        .as("%s keeps its payload; no asterisk is moved over it", field.label())
                        .doesNotContain(FieldAttributeSetter.ASTERISK);
            }
        }

        @Test
        @DisplayName("first entry paints the screen with no attribute stated for any field either")
        void firstEntryAppliesNoHighlight() {
            TransactionViewRequest request = new TransactionViewRequest();
            request.setNavigationContext(NavigationContext.empty());

            ProgramState state = controller.mainPara(request);

            assertThat(state.commarea().isReenter())
                    .as("MAIN-PARA's not-re-enter arm at L120-130 sets it for the NEXT call")
                    .isTrue();
            assertThat(state.errFlagOff()).isTrue();
            assertThat(state.response().getMetadata(TransactionViewResponse.ScreenField.ERRMSG)
                    .isDefault())
                    .as("the DFHGREEN of L727 is on the success path, which first entry never reaches")
                    .isTrue();
            assertThat(java.util.Arrays.stream(TransactionViewResponse.ScreenField.values())
                    .allMatch(field -> state.response().getMetadata(field).isDefault()))
                    .as("nothing is recoloured on the paint, so nothing to unpaint on the next call")
                    .isTrue();
        }

        @Test
        @DisplayName("the one attribute this program does state is DFHGREEN, on success, and only there")
        void theOnlyAttributeIsGreenOnSuccess() {
            xrefByAccountFound();
            browseWithLastId();
            writeSucceeds();

            ProgramState added = controller.mainPara(completeRequest());

            assertThat(added.response().getMetadata(TransactionViewResponse.ScreenField.ERRMSG)
                    .getColour())
                    .as("MOVE DFHGREEN TO ERRMSGC OF COTRN2AO at L727")
                    .isEqualTo(BmsAttributes.DFHGREEN)
                    .isNotEqualTo(BmsAttributes.DFHRED);
            assertThat(java.util.Arrays.stream(TransactionViewResponse.ScreenField.values())
                    .filter(field -> !added.response().getMetadata(field).isDefault())
                    .toList())
                    .as("exactly one field carries an attribute, and it is the error line")
                    .containsExactly(TransactionViewResponse.ScreenField.ERRMSG);
        }

        @Test
        @DisplayName("fourteen of the twenty-one fields are UNPROT, and only those could be highlighted")
        void fourteenFieldsAreInputCapable() {
            assertThat(java.util.Arrays.stream(TransactionViewResponse.ScreenField.values())
                    .filter(TransactionViewResponse.ScreenField::input)
                    .count())
                    .as("the map's UNPROT count - the set CSSETATY could act on if it were copied")
                    .isEqualTo(14L);
            assertThat(TransactionViewResponse.ScreenField.ERRMSG.input())
                    .as("ASKIP, so the error line is never a highlight target")
                    .isFalse();
        }

        @Test
        @DisplayName("the commarea is 160 shared bytes plus this screen's 58, and stays 218")
        void theCommareaWidthsAreTheCopybooks() {
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("COCOM01Y, which is 160 and is never widened by a screen extension")
                    .isEqualTo(160);
            assertThat(TransactionViewRequest.Ct02Info.CT02_INFO_LENGTH)
                    .as("CDEMO-CT02-INFO at COTRN02C:72-80: 16 + 16 + 8 + 1 + 1 + 16")
                    .isEqualTo(58);
            assertThat(NavigationContext.COMMAREA_LENGTH
                    + TransactionViewRequest.Ct02Info.CT02_INFO_LENGTH)
                    .isEqualTo(TransactionViewRequest.Ct02Info.COMMAREA_TOTAL_LENGTH)
                    .isEqualTo(ProgramState.PASSED_COMMAREA_LENGTH)
                    .isEqualTo(218);
            assertThat(NavigationContext.empty().toFixedWidth(controller.codec()))
                    .as("the shared area renders at its declared width, extension excluded")
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
        }
    }

    @Nested
    @DisplayName("The twenty-one-field payload projection of COTRN02.CPY")
    class ThePayloadProjection {
        @ParameterizedTest
        @CsvSource({
            "TRNNAME, 4", "TITLE01, 40", "CURDATE, 8", "PGMNAME, 8", "TITLE02, 40", "CURTIME, 8",
            "ACTIDIN, 11", "CARDNIN, 16", "TTYPCD, 2", "TCATCD, 4", "TRNSRC, 10", "TDESC, 60",
            "TRNAMT, 12", "TORIGDT, 10", "TPROCDT, 10", "MID, 9", "MNAME, 30", "MCITY, 25",
            "MZIP, 10", "CONFIRM, 1", "ERRMSG, 78",
        })
        @DisplayName("each field is at the width its xxxI item declares")
        void everyFieldIsAtItsDeclaredWidth(String label, int width) {
            TransactionViewResponse.ScreenField field =
                    TransactionViewResponse.ScreenField.ofLabel(label);

            assertThat(field.width())
                    .as("%sI PIC X(%d) in app/cpy-bms/COTRN02.CPY", label, width)
                    .isEqualTo(width);
            assertThat(field.label()).isEqualTo(label);
        }

        @Test
        @DisplayName("there are exactly twenty-one fields, and they are all text")
        void thereAreExactlyTwentyOneFields() {
            assertThat(TransactionViewResponse.ScreenField.values()).hasSize(21);
            assertThat(java.util.Arrays.stream(TransactionViewResponse.ScreenField.values())
                    .mapToInt(TransactionViewResponse.ScreenField::width)
                    .sum())
                    .as("4+40+8+8+40+8+11+16+2+4+10+60+12+10+10+9+30+25+10+1+78")
                    .isEqualTo(396);
            for (TransactionViewResponse.ScreenField field
                    : TransactionViewResponse.ScreenField.values()) {
                assertThat(TransactionViewResponse.spaces(field.width()))
                        .as("every payload item is PIC X, so its empty value is spaces of its width")
                        .hasSize(field.width());
            }
        }

        @Test
        @DisplayName("there is deliberately no TRNIDIN and no TRNID field on either projection")
        void thereIsNoTransactionIdentifierField() {
            assertThat(java.util.Arrays.stream(TransactionViewRequest.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).contains("trnid"))
                    .toList())
                    .as("COTRN01 has TRNIDIN; COTRN02 has none, because the key is generated")
                    .isEmpty();
            assertThat(java.util.Arrays.stream(TransactionViewResponse.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).contains("trnid"))
                    .toList())
                    .isEmpty();
            assertThatThrownBy(() -> TransactionViewResponse.ScreenField.ofLabel("TRNIDIN"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("no xxxL, xxxF or xxxA item is a payload member on either projection")
        void theMetadataItemsAreNotPayloadMembers() {
            List<String> accessors =
                    java.util.stream.Stream.concat(
                            java.util.Arrays.stream(
                                    TransactionViewRequest.class.getDeclaredMethods()),
                            java.util.Arrays.stream(
                                    TransactionViewResponse.class.getDeclaredMethods()))
                    .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                    .map(java.lang.reflect.Method::getName)
                    .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                    .toList();

            for (TransactionViewResponse.ScreenField field
                    : TransactionViewResponse.ScreenField.values()) {
                String stem = field.label().toLowerCase(java.util.Locale.ROOT);
                assertThat(accessors)
                        .as("%sL is a COMP PIC S9(4) halfword, not a JSON member", field.label())
                        .doesNotContain("get" + stem + "l", "set" + stem + "l");
                assertThat(accessors)
                        .as("%sF is the flag byte, not a JSON member", field.label())
                        .doesNotContain("get" + stem + "f", "set" + stem + "f");
                assertThat(accessors)
                        .as("%sA redefines the flag byte, not a JSON member", field.label())
                        .doesNotContain("get" + stem + "a", "set" + stem + "a");
            }
        }

        @Test
        @DisplayName("the cursor halfword is behaviour even though it is not a payload member")
        void theCursorHalfwordStillCarriesBehaviour() {
            ProgramState state = new ProgramState(controller.codec());

            state.moveMinusOneTo(TransactionViewResponse.ScreenField.CONFIRM);

            assertThat(state.cursorRequestedOn(TransactionViewResponse.ScreenField.CONFIRM))
                    .as("MOVE -1 TO CONFIRML at L180 and L186")
                    .isTrue();
            assertThat(state.cursorRequestedOn(TransactionViewResponse.ScreenField.ACTIDIN)).isFalse();
            assertThat(state.symbolicMap().isCursorRequested(
                    TransactionViewRequest.ScreenField.CONFIRM))
                    .as("the AI projection reads the same halfword the AO projection wrote")
                    .isTrue();
            assertThat(TransactionViewRequest.ScreenField.CONFIRM.name())
                    .isEqualTo(TransactionViewResponse.ScreenField.CONFIRM.name());
        }

        @Test
        @DisplayName("the account, card and merchant identifiers are echoed whole and unmasked")
        void theIdentifiersAreNeverMasked() {
            xrefByAccountFound();
            TransactionViewRequest request = completeRequest();
            request.setConfirm("N");

            ProgramState state = controller.mainPara(request);

            assertThat(state.message()).startsWith("Confirm to add this transaction...");
            assertThat(state.response().getActidino())
                    .as("all eleven digits: COTRN02C masks nothing")
                    .isEqualTo(ACCOUNT_ID)
                    .doesNotContain("*")
                    .doesNotContain("X");
            assertThat(state.response().getCardnino())
                    .as("all sixteen digits, as READ-CXACAIX-FILE resolved them at L209")
                    .isEqualTo(CARD_NUMBER)
                    .doesNotContain("*");
            assertThat(state.response().getMido())
                    .isEqualTo("000123456")
                    .doesNotContain("*");
        }
    }

    private static CardXrefRepository.ReadResult xrefFound(String ddName, CardXrefRecord record) {
        return CardXrefRepository.ReadResult.found(ddName, record, xrefImageOf(record));
    }

    private static CardXrefRepository.ReadResult xrefDuplicate(String ddName, CardXrefRecord first,
            int cicsResp) {
        return CardXrefRepository.ReadResult.duplicate(ddName, first, xrefImageOf(first), cicsResp);
    }

    private static String xrefImageOf(CardXrefRecord record) {
        return new String(record.encode(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
    }

    private static TransactionRepository.Browse positionedBrowse() {
        TransactionRepository.Browse handle = mock(TransactionRepository.Browse.class);
        when(handle.positioningResult()).thenReturn(
                TransactionRepository.ReadResult.found(TransactionRepository.CICS_FILE_NAME,
                        new com.vsergeychik.carddemo.transaction.model.TranRecord(
                                java.nio.charset.StandardCharsets.US_ASCII)));
        when(handle.positioningOutcome()).thenReturn(FileStatus.Outcome.OK);
        when(handle.isStarted()).thenReturn(true);
        return handle;
    }

}
