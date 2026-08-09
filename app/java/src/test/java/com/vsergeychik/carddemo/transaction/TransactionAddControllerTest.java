package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.TransactionAddController.ProgramState;
import com.vsergeychik.carddemo.transaction.TransactionRepository.ReadResult;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest.Ct01Info;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddResponse;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddResponse.AttributeQuad;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddResponse.ScreenField;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Proves {@link TransactionAddController} against {@code app/cbl/COTRN01C.cbl}, the 330-line CICS
 * program behind transaction {@code CT01}.
 *
 * <p>Three properties of that program shape every test here.
 *
 * <ul>
 *   <li><strong>It views; it does not add.</strong> The class name is the build prompt's and the
 *       behaviour is the source's - risk R-B. One test in {@link RiskRB} reads the controller's own
 *       source and asserts the divergence is still recorded there, and another asserts that no read
 *       path ever calls a write. Those two are the guard rail that stops a future reader "fixing" the
 *       name or the behaviour.</li>
 *   <li><strong>{@code SEND-TRNVIEW-SCREEN} is not terminal.</strong> It ends with no {@code GO TO},
 *       so control returns to the statement after the {@code PERFORM}. That is why the first-entry arm
 *       with a selection sends the screen <em>twice</em> and why {@code PROCESS-ENTER-KEY} tests
 *       {@code IF NOT ERR-FLG-ON} a second time rather than using an {@code ELSE}.</li>
 *   <li><strong>{@code COTRN1AO REDEFINES COTRN1AI}.</strong> Each field's {@code xxxI} and
 *       {@code xxxO} items are the same bytes, so a value the program moves "into the input field" is
 *       read back off the response.</li>
 * </ul>
 *
 * <p>Every expectation is derived statically from the source, the copybooks and the BMS mapset - the
 * legacy programs cannot be executed in this environment (risk R-A) - so each assertion names the line
 * it came from.
 */
@DisplayName("TransactionAddController - COTRN01C, transaction CT01, and it VIEWS")
class TransactionAddControllerTest {

    /** A pinned instant, so the two header images can be compared byte for byte. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:34Z");

    /** The {@code MM/DD/YY} image {@link #FIXED_INSTANT} produces at UTC. */
    private static final String EXPECTED_CURDATE = "07/19/22";

    /** The {@code HH:MM:SS} image {@link #FIXED_INSTANT} produces at UTC. */
    private static final String EXPECTED_CURTIME = "23:12:34";

    /** A transaction id that exists in the stubbed dataset. */
    private static final String KNOWN_TRAN_ID = "0000000000000001";

    /** The dataset name a stubbed outcome reports; only its presence matters here. */
    private static final String DD_NAME = TransactionRepository.INPUT_DD_NAME;

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private static Clock pinnedClock() {
        return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    }

    private static TransactionAddController controllerOver(TransactionRepository repository) {
        return new TransactionAddController(repository, pinnedClock(), realUnitOfWork());
    }

    /**
     * A unit of work over a real transaction manager and a real single connection.
     *
     * <p>Deliberately not a stub. {@code :275} states the {@code UPDATE} option, so the read takes a
     * row lock and {@link TransactionRepository#readForUpdateByTranId(String)} refuses to issue one
     * with no transaction open; a double that merely ran the body would hide whether the boundary is
     * really there. An in-memory database is used because the subject is the boundary itself, which is
     * the framework's behaviour rather than the deployment driver's.
     *
     * @return a unit of work whose {@code execute} opens a genuine transaction
     */
    private static DatasetUnitOfWork realUnitOfWork() {
        SingleConnectionDataSource source = new SingleConnectionDataSource(
                "jdbc:h2:mem:cotrn01c-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "sa", "", true);
        source.setSuppressClose(true);
        DataSource dataSource = source;
        return new DatasetUnitOfWork(new JdbcTransactionManager(dataSource));
    }

    /** A repository that is never expected to be reached. */
    private static TransactionRepository unusedRepository() {
        return mock(TransactionRepository.class);
    }

    /**
     * A repository whose keyed read-for-update reports {@code outcome} for any key.
     *
     * <p>{@code readForUpdateByTranId}, not {@code readByTranId}: {@code :275} states the
     * {@code UPDATE} option and the translation issues the form the source states.
     *
     * @param outcome what the locking read reports
     * @return the stubbed repository
     */
    private static TransactionRepository repositoryReturning(ReadResult outcome) {
        TransactionRepository repository = mock(TransactionRepository.class);
        when(repository.readForUpdateByTranId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(outcome);
        return repository;
    }

    /** {@code EIBCALEN = 0}: a request carrying no communication area at all - {@code :94}. */
    private static TransactionAddRequest coldStart() {
        return new TransactionAddRequest();
    }

    /** A first entry: {@code CDEMO-PGM-CONTEXT} zero, so {@code :99} takes the paint branch. */
    private static TransactionAddRequest firstEntry() {
        TransactionAddRequest request = new TransactionAddRequest();
        request.setNavigationContext(NavigationContext.empty());
        return request;
    }

    /** A re-entry with the given raw {@code EIBAID} byte - {@code :110-112}. */
    private static TransactionAddRequest reentry(byte eibAid) {
        TransactionAddRequest request = new TransactionAddRequest();
        request.setNavigationContext(NavigationContext.empty().withPgmReenter());
        request.setAid(String.valueOf((char) (eibAid & 0xFF)));
        return request;
    }

    /** A 350-byte {@code TRAN-RECORD} with every field distinguishable. */
    private static TranRecord tranRecord(String tranId, BigDecimal amount) {
        TranRecord record = new TranRecord(StandardCharsets.US_ASCII);
        record.moveTranId(tranId);
        record.moveTranTypeCd("01");
        record.moveTranCatCd(5);
        record.moveTranSource("POS TERM");
        // 100 characters, so the move into TDESCO PIC X(60) has 40 to lose on the right.
        record.moveTranDesc("A".repeat(60) + "B".repeat(40));
        record.moveTranAmt(amount);
        record.moveTranMerchantId(800000001L);
        record.moveTranMerchantName("M".repeat(30) + "N".repeat(20));
        record.moveTranMerchantCity("C".repeat(25) + "D".repeat(25));
        record.moveTranMerchantZip("12345-6789");
        record.moveTranCardNum("4111111111111111");
        record.moveTranOrigTs("2022-07-19 23:12:34.123456");
        record.moveTranProcTs("2022-07-20 01:02:03.654321");
        return record;
    }

    /** Resolves a repository-relative path from wherever the suite is launched. */
    private static Path repositoryFile(String relativePath) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path resolved = candidate.resolve(relativePath);
            if (Files.exists(resolved)) {
                return resolved;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find " + relativePath + " at or above "
                + Path.of("").toAbsolutePath());
    }

    // =============================================================================================

    @Nested
    @DisplayName("Risk R-B - the name says Add, the program views, and both stay that way")
    class RiskRB {

        @Test
        @DisplayName("the class documentation records R-B, quotes the source header and cites README")
        void classDocumentationRecordsTheDivergence() throws IOException {
            String source = Files.readString(repositoryFile("app/java/src/main/java/com/vsergeychik/"
                    + "carddemo/transaction/TransactionAddController.java"), StandardCharsets.UTF_8);

            assertThat(source)
                    .as("the R-B risk identifier must be named, or a reader has no thread to pull")
                    .contains("R-B")
                    .as("the source Function header is the primary evidence and must be quoted")
                    .contains("View a Transaction from TRANSACT file")
                    .as("README.md's own inventory independently contradicts the prompt's mapping")
                    .contains("README.md:213-231")
                    .as("the mechanical grep result is the proof that nothing is written")
                    .contains("no matches")
                    .as("the sibling that actually adds must be named so the pair is findable")
                    .contains("TransactionViewController");
        }

        @Test
        @DisplayName("the source program contains no WRITE, REWRITE or DELETE - the R-B evidence")
        void theCobolProgramContainsNoWrite() throws IOException {
            String cobol = Files.readString(repositoryFile("app/cbl/COTRN01C.cbl"),
                    StandardCharsets.UTF_8);

            assertThat(cobol).doesNotContain("WRITE").doesNotContain("REWRITE")
                    .doesNotContain("DELETE");
            assertThat(cobol).contains("Function    : View a Transaction from TRANSACT file");
        }

        @Test
        @DisplayName("no path through the program reaches a write on the repository")
        void noPathWritesToTheDataset() {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));
            TransactionRepository repository = repositoryReturning(ReadResult.found(DD_NAME, record));
            TransactionAddController controller = controllerOver(repository);

            TransactionAddRequest request = reentry(CicsAid.DFHENTER);
            request.setTrnidin(KNOWN_TRAN_ID);
            controller.mainPara(request);
            controller.mainPara(coldStart());
            controller.mainPara(firstEntry());
            controller.mainPara(reentry(CicsAid.DFHPF3));
            controller.mainPara(reentry(CicsAid.DFHPF4));
            controller.mainPara(reentry(CicsAid.DFHPF5));
            controller.mainPara(reentry(CicsAid.DFHPF12));

            verify(repository, never()).write(org.mockito.ArgumentMatchers.any());
            verify(repository, never()).openOutput();
            // The plain read is never used either: :275 states UPDATE, so the locking form is the one
            // the translation issues - which is a read, not a write, and leaves this claim intact.
            verify(repository, never()).readByTranId(org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Nested
    @DisplayName("MAIN-PARA :86-139 - the three entry arms")
    class MainPara {

        @Test
        @DisplayName(":88-92 every execution starts with the flags off and the message blank")
        void everyExecutionStartsClean() {
            ProgramState state = controllerOver(unusedRepository()).mainPara(coldStart());

            assertThat(state.errFlagOff()).isTrue();
            assertThat(state.errFlagOn()).isFalse();
            assertThat(state.usrModifiedNo()).isTrue();
            assertThat(state.usrModifiedYes())
                    .as(":89 is the only write to WS-USR-MODIFIED, so YES is dead")
                    .isFalse();
            assertThat(state.errFlg()).isEqualTo(ProgramState.ERR_FLG_OFF);
            assertThat(state.usrModified()).isEqualTo(ProgramState.USR_MODIFIED_NO);
            assertThat(state.message()).hasSize(TransactionAddController.WS_MESSAGE_LENGTH).isBlank();
            assertThat(state.tranDate())
                    .as("WS-TRAN-DATE is declared with a VALUE and never referenced")
                    .isEqualTo(TransactionAddController.WS_TRAN_DATE_INITIAL);
        }

        @Test
        @DisplayName(":94-96 EIBCALEN = 0 transfers to COSGN00C and paints nothing")
        void coldStartGoesToSignOn() {
            ProgramState state = controllerOver(unusedRepository()).mainPara(coldStart());

            assertThat(state.response().getNextProgram())
                    .isEqualTo(TransactionAddController.SIGN_ON_PROGRAM);
            assertThat(state.transferred()).isTrue();
            assertThat(state.returned()).as("XCTL never comes back to :136").isFalse();
            assertThat(state.screenSent()).isFalse();
            assertThat(state.screensSent()).isZero();
            assertThat(state.cursorRequested()).isFalse();
            assertThat(state.cursorField()).isNull();
            assertThat(state.dateHeader()).isEmpty();
            assertThat(state.readResult()).isEmpty();
            assertThat(state.tranRecord()).isEmpty();
            assertThat(state.displays()).isEmpty();
            assertThat(state.response().getNextMapset()).isBlank();
            assertThat(state.response().getNextMap()).isBlank();
        }

        @Test
        @DisplayName(":99-109 a first entry with no selection paints an empty screen and asks for a key")
        void firstEntryWithoutSelectionPaintsAnEmptyScreen() {
            TransactionRepository repository = unusedRepository();
            ProgramState state = controllerOver(repository).mainPara(firstEntry());

            assertThat(state.commarea().isReenter())
                    .as(":100 sets CDEMO-PGM-REENTER before the screen is sent")
                    .isTrue();
            assertThat(state.cursorField()).isEqualTo(ScreenField.TRNIDINO);
            assertThat(state.screensSent()).as(":109 sends once").isEqualTo(1);
            assertThat(state.returned()).isTrue();
            assertThat(state.transferred()).isFalse();
            assertThat(state.response().getNextProgram())
                    .isEqualTo(TransactionAddController.PROGRAM_NAME);
            assertThat(state.response().getNextMapset())
                    .isEqualTo(TransactionAddResponse.MAPSET_NAME);
            assertThat(state.response().getNextMap()).isEqualTo(TransactionAddResponse.MAP_NAME);

            // :101 MOVE LOW-VALUES TO COTRN1AO left the detail fields as nulls; the header and the
            // message line were written over them afterwards.
            assertThat(state.response().getTrnido())
                    .isEqualTo(TransactionAddController.lowValues(ScreenField.TRNIDO.payloadLength()));
            assertThat(state.response().getMzipo())
                    .isEqualTo(TransactionAddController.lowValues(ScreenField.MZIPO.payloadLength()));
            assertThat(state.response().getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(state.response().getErrmsgo()).isBlank();

            verify(repository, never())
                    .readForUpdateByTranId(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName(":103-108 a first entry carrying a selection performs the lookup at once")
        void firstEntryWithSelectionLooksUpImmediately() {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));
            TransactionRepository repository = repositoryReturning(ReadResult.found(DD_NAME, record));

            TransactionAddRequest request = firstEntry();
            Ct01Info info = new Ct01Info();
            info.setTrnSelected(KNOWN_TRAN_ID);
            request.setCt01Info(info);

            ProgramState state = controllerOver(repository).mainPara(request);

            assertThat(state.response().getTrnidino())
                    .as(":105-106 the selection becomes the lookup key")
                    .isEqualTo(KNOWN_TRAN_ID);
            assertThat(state.tranId()).isEqualTo(KNOWN_TRAN_ID);
            assertThat(state.response().getTrnido()).isEqualTo(KNOWN_TRAN_ID);
            assertThat(state.screensSent())
                    .as(":191 sends, then :109 sends again - SEND-TRNVIEW-SCREEN is not terminal")
                    .isEqualTo(2);
            assertThat(state.errFlagOn()).isFalse();
            assertThat(state.returned()).isTrue();
            verify(repository).readForUpdateByTranId(KNOWN_TRAN_ID);
        }

        @Test
        @DisplayName(":103 the selection test is the COBOL relation, not a looser one")
        void aSelectionOfMixedSpacesAndNullsIsARealSelection() {
            TransactionRepository repository =
                    repositoryReturning(ReadResult.notFound(DD_NAME));
            TransactionAddRequest request = firstEntry();
            Ct01Info info = new Ct01Info();
            // Neither all spaces nor all low-values, so COBOL treats it as a value. Ct01Info's own
            // hasSelection() disagrees for exactly this shape, which is why it is not consulted.
            info.setTrnSelected("        " + "\u0000".repeat(8));
            request.setCt01Info(info);
            assertThat(info.hasSelection())
                    .as("the DTO helper answers a looser question; the divergence is documented")
                    .isFalse();

            ProgramState state = controllerOver(repository).mainPara(request);

            verify(repository).readForUpdateByTranId(org.mockito.ArgumentMatchers.anyString());
            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message().strip())
                    .isEqualTo(TransactionAddController.MSG_TRAN_ID_NOT_FOUND);
        }

        @Test
        @DisplayName(":113-114 re-entry with ENTER runs the enter-key processing")
        void reentryWithEnterProcessesTheKey() {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("1.05"));
            TransactionRepository repository = repositoryReturning(ReadResult.found(DD_NAME, record));
            TransactionAddRequest request = reentry(CicsAid.DFHENTER);
            request.setTrnidin(KNOWN_TRAN_ID);

            ProgramState state = controllerOver(repository).mainPara(request);

            assertThat(state.resolvedAid()).contains(AidKey.ENTER);
            assertThat(state.screensSent()).as(":191 only - :109 is on the other arm").isEqualTo(1);
            assertThat(state.response().getTrnamto()).isEqualTo("+00000001.05");
            assertThat(state.returned()).isTrue();
        }

        @Test
        @DisplayName(":115-122 PF3 with no caller recorded goes to COMEN01C")
        void pf3WithoutACallerGoesToTheMainMenu() {
            ProgramState state = controllerOver(unusedRepository())
                    .mainPara(reentry(CicsAid.DFHPF3));

            assertThat(state.response().getNextProgram())
                    .isEqualTo(TransactionAddController.MAIN_MENU_PROGRAM);
            assertThat(state.transferred()).isTrue();
            assertThat(state.returned()).isFalse();
            assertThat(state.commarea().fromProgram())
                    .isEqualTo(TransactionAddController.PROGRAM_NAME);
            assertThat(state.commarea().fromTranid())
                    .isEqualTo(TransactionAddController.TRANSACTION_ID);
            assertThat(state.commarea().isEnter())
                    .as(":204 MOVE ZEROS TO CDEMO-PGM-CONTEXT")
                    .isTrue();
            assertThat(state.resolvedAid()).contains(AidKey.PFK03);
        }

        @Test
        @DisplayName(":118-120 PF3 with a caller recorded returns to that caller")
        void pf3WithACallerReturnsToIt() {
            TransactionAddRequest request = new TransactionAddRequest();
            request.setNavigationContext(NavigationContext.empty()
                    .withPgmReenter()
                    .withFromProgram("COTRN00C"));
            request.setAid(String.valueOf((char) (CicsAid.DFHPF3 & 0xFF)));

            ProgramState state = controllerOver(unusedRepository()).mainPara(request);

            assertThat(state.response().getNextProgram()).isEqualTo("COTRN00C");
        }

        @Test
        @DisplayName(":123-124 PF4 clears every field and repaints")
        void pf4ClearsTheScreen() {
            TransactionAddRequest request = reentry(CicsAid.DFHPF4);
            request.setTrnidin(KNOWN_TRAN_ID);
            request.setTrnid(KNOWN_TRAN_ID);
            request.setMzip("12345-6789");

            ProgramState state = controllerOver(unusedRepository()).mainPara(request);

            assertThat(state.response().getTrnidino()).isBlank();
            assertThat(state.response().getTrnido()).isBlank();
            assertThat(state.response().getMzipo()).isBlank();
            assertThat(state.message()).isBlank();
            assertThat(state.response().getErrmsgo()).isBlank();
            assertThat(state.cursorField()).isEqualTo(ScreenField.TRNIDINO);
            assertThat(state.screensSent()).isEqualTo(1);
            assertThat(state.errFlagOn()).as(":301-304 does not touch the flag").isFalse();
            assertThat(state.returned()).isTrue();
        }

        @Test
        @DisplayName(":125-127 PF5 goes to the transaction list, COTRN00C")
        void pf5GoesToTheTransactionList() {
            ProgramState state = controllerOver(unusedRepository())
                    .mainPara(reentry(CicsAid.DFHPF5));

            assertThat(state.response().getNextProgram())
                    .isEqualTo(TransactionAddController.TRANSACTION_LIST_PROGRAM);
            assertThat(state.transferred()).isTrue();
            assertThat(state.screenSent()).isFalse();
        }

        @ParameterizedTest(name = "EIBAID 0x{0} takes WHEN OTHER")
        @CsvSource({"6D", "F1", "F2", "F6", "F7", "F8", "7C", "C3", "40", "20"})
        @DisplayName(":128-131 WHEN OTHER reports the standard invalid-key message")
        void everyOtherKeyIsRejected(String hex) {
            byte eibAid = (byte) Integer.parseInt(hex, 16);

            ProgramState state = controllerOver(unusedRepository()).mainPara(reentry(eibAid));

            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message())
                    .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY
                            + " ".repeat(TransactionAddController.WS_MESSAGE_LENGTH
                                    - SystemMessages.MESSAGE_LENGTH));
            assertThat(state.response().getErrmsgo().strip())
                    .isEqualTo("Invalid key pressed. Please see below...");
            assertThat(state.screensSent()).isEqualTo(1);
            assertThat(state.returned()).isTrue();
        }

        @Test
        @DisplayName(":115 PF15 is a distinct AID from PF3 and takes WHEN OTHER")
        void pf15IsNotFoldedOntoPf3() {
            ProgramState state = controllerOver(unusedRepository())
                    .mainPara(reentry(CicsAid.DFHPF15));

            assertThat(state.errFlagOn())
                    .as("the source's EVALUATE compares the raw byte, so no folding applies")
                    .isTrue();
            assertThat(state.resolvedAid())
                    .as("CSSTRPFY would nonetheless resolve it to PFK03; that is recorded, not acted on")
                    .contains(AidKey.PFK03);
        }

        @Test
        @DisplayName("a null request is refused rather than defaulted")
        void aNullRequestIsRefused() {
            TransactionAddController controller = controllerOver(unusedRepository());
            assertThatNullPointerException().isThrownBy(() -> controller.mainPara(null));
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY :144-192 and READ-TRANSACT-FILE :267-296")
    class EnterKey {

        @ParameterizedTest(name = "a lookup key of [{0}] is rejected as empty")
        @ValueSource(strings = {
            "",
            "                ",
            // Sixteen nulls: a whole field of LOW-VALUES. It has to be the whole field, because
            // RECEIVE reshapes a shorter value to the field's declared width by padding it with
            // SPACES - and a field of nulls followed by spaces equals neither figurative constant.
            "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
                + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"})
        @DisplayName(":147-152 a blank transaction id is rejected before any read")
        void aBlankKeyIsRejected(String key) {
            TransactionRepository repository = unusedRepository();
            TransactionAddRequest request = reentry(CicsAid.DFHENTER);
            request.setTrnidin(key);

            ProgramState state = controllerOver(repository).mainPara(request);

            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message().strip())
                    .isEqualTo(TransactionAddController.MSG_TRAN_ID_EMPTY);
            assertThat(state.response().getErrmsgo())
                    .hasSize(ScreenField.ERRMSGO.payloadLength())
                    .startsWith("Tran ID can NOT be empty...");
            assertThat(state.cursorField()).isEqualTo(ScreenField.TRNIDINO);
            assertThat(state.screensSent()).isEqualTo(1);
            assertThat(state.readResult()).isEmpty();
            assertThat(state.tranRecord()).isEmpty();
            verify(repository, never())
                    .readForUpdateByTranId(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName(":147 RECEIVE pads a short key with SPACES, so a partly-null field is not blank")
        void aPartlyNullKeyIsARealKeyBecauseReceivePadsWithSpaces() {
            TransactionRepository repository = repositoryReturning(ReadResult.notFound(DD_NAME));
            TransactionAddRequest request = reentry(CicsAid.DFHENTER);
            request.setTrnidin("\u0000\u0000\u0000\u0000");

            ProgramState state = controllerOver(repository).mainPara(request);

            assertThat(state.response().getTrnidino())
                    .as("four nulls reshaped to PIC X(16) become four nulls and twelve spaces")
                    .isEqualTo("\u0000\u0000\u0000\u0000" + " ".repeat(12));
            assertThat(state.message().strip())
                    .as("neither SPACES nor LOW-VALUES, so it is read and reported as not found")
                    .isEqualTo(TransactionAddController.MSG_TRAN_ID_NOT_FOUND);
            verify(repository)
                    .readForUpdateByTranId(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName(":158-173 the thirteen detail fields are blanked before the read, the key is not")
        void theDetailFieldsAreBlankedButNotTheKey() {
            TransactionRepository repository = repositoryReturning(ReadResult.notFound(DD_NAME));
            TransactionAddRequest request = reentry(CicsAid.DFHENTER);
            request.setTrnidin(KNOWN_TRAN_ID);
            request.setTrnid("STALE");
            request.setMname("STALE MERCHANT");

            ProgramState state = controllerOver(repository).mainPara(request);

            assertThat(state.response().getTrnidino())
                    .as(":159-171 does not blank TRNIDIN - the key is still needed")
                    .isEqualTo(KNOWN_TRAN_ID);
            assertThat(state.response().getTrnido()).isBlank();
            assertThat(state.response().getMnameo()).isBlank();
        }

        @Test
        @DisplayName(":281-282 a NORMAL read paints all fourteen fields from the 350-byte record")
        void aFoundRecordPaintsEveryField() {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));
            TransactionRepository repository = repositoryReturning(ReadResult.found(DD_NAME, record));
            TransactionAddRequest request = reentry(CicsAid.DFHENTER);
            request.setTrnidin(KNOWN_TRAN_ID);

            TransactionAddResponse response = controllerOver(repository).mainPara(request).response();

            assertThat(response.getTrnido()).isEqualTo(KNOWN_TRAN_ID);                    // :178
            assertThat(response.getCardnumo()).isEqualTo("4111111111111111");             // :179
            assertThat(response.getTtypcdo()).isEqualTo("01");                            // :180
            assertThat(response.getTcatcdo())
                    .as(":181 TRAN-CAT-CD is PIC 9(04): the stored digits move")
                    .isEqualTo("0005");
            assertThat(response.getTrnsrco()).isEqualTo("POS TERM  ");                    // :182
            assertThat(response.getTrnamto()).isEqualTo("+00000504.77");                  // :183
            assertThat(response.getTdesco())
                    .as(":184 PIC X(100) into PIC X(60): 40 characters lost on the RIGHT")
                    .isEqualTo("A".repeat(60));
            assertThat(response.getTorigdto())
                    .as(":185 PIC X(26) into PIC X(10): the date survives, the time is cut")
                    .isEqualTo("2022-07-19");
            assertThat(response.getTprocdto()).isEqualTo("2022-07-20");                    // :186
            assertThat(response.getMido())
                    .as(":187 TRAN-MERCHANT-ID is PIC 9(09)")
                    .isEqualTo("800000001");
            assertThat(response.getMnameo()).isEqualTo("M".repeat(30));                    // :188
            assertThat(response.getMcityo()).isEqualTo("C".repeat(25));                    // :189
            assertThat(response.getMzipo()).isEqualTo("12345-6789");                       // :190
            assertThat(response.getErrmsgo()).isBlank();
        }

        @Test
        @DisplayName(":283-288 a NOTFND read reports 'Transaction ID NOT found...'")
        void aMissingRecordIsReported() {
            TransactionRepository repository = repositoryReturning(ReadResult.notFound(DD_NAME));
            TransactionAddRequest request = reentry(CicsAid.DFHENTER);
            request.setTrnidin("9999999999999999");

            ProgramState state = controllerOver(repository).mainPara(request);

            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message()).isEqualTo(TransactionAddController.MSG_TRAN_ID_NOT_FOUND
                    + " ".repeat(TransactionAddController.WS_MESSAGE_LENGTH
                            - TransactionAddController.MSG_TRAN_ID_NOT_FOUND.length()));
            assertThat(state.respCd()).isEqualTo(FileStatus.NOTFND);
            assertThat(state.reasCd()).isEqualTo(FileStatus.NO_REASON_CODE);
            assertThat(state.cursorField()).isEqualTo(ScreenField.TRNIDINO);
            assertThat(state.displays()).as("the NOTFND arm displays nothing").isEmpty();
            assertThat(state.response().getTrnido())
                    .as("the detail stays blank - :176 does not run")
                    .isBlank();
            assertThat(state.returned()).isTrue();
        }

        @Test
        @DisplayName(":289-295 any other condition displays the two codes and reports the lookup failure")
        void anUnexpectedConditionIsDisplayedAndReported() {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, BigDecimal.ZERO);
            TransactionRepository repository =
                    repositoryReturning(ReadResult.duplicate(DD_NAME, record));
            TransactionAddRequest request = reentry(CicsAid.DFHENTER);
            request.setTrnidin(KNOWN_TRAN_ID);

            ProgramState state = controllerOver(repository).mainPara(request);

            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message().strip())
                    .isEqualTo(TransactionAddController.MSG_UNABLE_TO_LOOKUP);
            assertThat(state.displays()).containsExactly("RESP:000000014REAS:000000000");
            assertThat(state.respCd()).isEqualTo(FileStatus.DUPREC);
            assertThat(state.response().getTrnido())
                    .as("a duplicate carries a record, and :176 still must not paint it")
                    .isBlank();
        }

        @Test
        @DisplayName(":290 a condition with no CICS RESP never renders as RESP 0, which is NORMAL")
        void anOutcomeWithNoCicsResponseIsNotRenderedAsNormal() {
            TransactionRepository repository = repositoryReturning(
                    ReadResult.other(DD_NAME, TransactionRepository.PERMANENT_ERROR_STATUS));
            TransactionAddRequest request = reentry(CicsAid.DFHENTER);
            request.setTrnidin(KNOWN_TRAN_ID);

            ProgramState state = controllerOver(repository).mainPara(request);

            // WS-RESP-CD's VALUE ZEROS state is indistinguishable from a reported DFHRESP(NORMAL),
            // because zero IS NORMAL. Leaving the item there for an outcome that reported no response at
            // all made :290 emit RESP:000000000 - a line that says the read succeeded, on the arm reached
            // only because it did not. The sentinel cannot collide with any DFHRESP value.
            assertThat(state.respCd())
                    .as("a reported NORMAL and an unreported response must not share one value")
                    .isEqualTo(FileStatus.RESP_NOT_REPORTED)
                    .isNotEqualTo(FileStatus.NORMAL);
            assertThat(FileStatus.respReported(state.respCd())).isFalse();

            // The rendered line keeps its shape - DISPLAY concatenates its operands at their declared
            // widths - and the response operand is unmistakably not a response code.
            assertThat(state.displays()).containsExactly("RESP:*********REAS:000000000");
            assertThat(state.displays().get(0))
                    .doesNotContain("RESP:000000000")
                    .hasSameSizeAs("RESP:000000000REAS:000000000");
            assertThat(state.message().strip())
                    .isEqualTo(TransactionAddController.MSG_UNABLE_TO_LOOKUP);
        }

        @Test
        @DisplayName(":290 a condition that DOES report a RESP still renders that exact value")
        void aReportedResponseStillRendersAsItsNumber() {
            // The other side of the sentinel: it must not be applied to an outcome that reported a
            // response. ENDFILE is a real DFHRESP value and reaches WHEN OTHER on a keyed read.
            TransactionRepository repository = repositoryReturning(ReadResult.endOfFile(DD_NAME));
            TransactionAddRequest request = reentry(CicsAid.DFHENTER);
            request.setTrnidin(KNOWN_TRAN_ID);

            ProgramState state = controllerOver(repository).mainPara(request);

            assertThat(state.respCd()).isEqualTo(FileStatus.ENDFILE);
            assertThat(FileStatus.respReported(state.respCd())).isTrue();
            assertThat(state.displays()).containsExactly("RESP:000000020REAS:000000000");
            assertThat(state.displays().get(0))
                    .as("a reported response is a nine-digit number, never the asterisk image")
                    .matches("RESP:\\d{9}REAS:\\d{9}");
        }

        @Test
        @DisplayName(":289 an end-of-file on a keyed read also reaches WHEN OTHER")
        void anEndOfFileReachesWhenOther() {
            TransactionRepository repository = repositoryReturning(ReadResult.endOfFile(DD_NAME));
            TransactionAddRequest request = reentry(CicsAid.DFHENTER);
            request.setTrnidin(KNOWN_TRAN_ID);

            ProgramState state = controllerOver(repository).mainPara(request);

            assertThat(state.message().strip())
                    .isEqualTo(TransactionAddController.MSG_UNABLE_TO_LOOKUP);
            assertThat(state.respCd()).isEqualTo(FileStatus.ENDFILE);
        }

        @Test
        @DisplayName(":176 requireRecordRead names the contradiction it guards against")
        void requireRecordReadRefusesAnAbsentRecord() {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, BigDecimal.ONE);
            assertThat(TransactionAddController.requireRecordRead(Optional.of(record)))
                    .isSameAs(record);
            assertThatIllegalStateException()
                    .isThrownBy(() -> TransactionAddController.requireRecordRead(Optional.empty()))
                    .withMessageContaining("ERR-FLG-OFF");
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionAddController.requireRecordRead(null));
        }
    }

    @Nested
    @DisplayName("POPULATE-HEADER-INFO :243-262 and SEND-TRNVIEW-SCREEN :213-225")
    class HeaderAndSend {

        @Test
        @DisplayName(":247-262 the six header fields come from the copybooks and the injected Clock")
        void theHeaderIsBuiltFromTheCopybooksAndThePinnedClock() {
            ProgramState state = controllerOver(unusedRepository()).mainPara(firstEntry());
            TransactionAddResponse response = state.response();

            assertThat(response.getTitle01o())
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(ScreenTitles.TITLE_LENGTH);                                   // :247
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02);        // :248
            assertThat(response.getTrnnameo())
                    .isEqualTo(TransactionAddController.TRANSACTION_ID);                    // :249
            assertThat(response.getPgmnameo())
                    .isEqualTo(TransactionAddController.PROGRAM_NAME);                      // :250
            assertThat(response.getCurdateo()).isEqualTo(EXPECTED_CURDATE);                 // :256
            assertThat(response.getCurtimeo()).isEqualTo(EXPECTED_CURTIME);                 // :262
            assertThat(state.dateHeader()).isPresent();
        }

        @Test
        @DisplayName("the X(40) thank-you title and the X(50) thank-you message are different literals")
        void theTwoThankYouLiteralsAreNotInterchangeable() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(SystemMessages.MESSAGE_LENGTH);
            assertThat(ScreenTitles.CCDA_THANK_YOU.strip())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.strip());
        }

        @Test
        @DisplayName(":217 WS-MESSAGE PIC X(80) into ERRMSGO PIC X(78) loses the last two characters")
        void anOverLongMessageIsTruncatedOnTheRight() {
            ProgramState state = new ProgramState(
                    new com.vsergeychik.carddemo.common.FixedWidthCodec(StandardCharsets.US_ASCII));
            state.setMessage("X".repeat(TransactionAddController.WS_MESSAGE_LENGTH));
            controllerOver(unusedRepository()).sendTrnviewScreen(state);

            assertThat(state.message()).hasSize(TransactionAddController.WS_MESSAGE_LENGTH);
            assertThat(state.response().getErrmsgo())
                    .hasSize(ScreenField.ERRMSGO.payloadLength())
                    .isEqualTo("X".repeat(ScreenField.ERRMSGO.payloadLength()));
        }

        @Test
        @DisplayName(":199-200 RETURN-TO-PREV-SCREEN defaults a blank target to COSGN00C")
        void aBlankTransferTargetDefaultsToSignOn() {
            // Unreachable through MAIN-PARA - every arm that transfers has already named a target -
            // so the source's own defensive default is driven directly.
            ProgramState state = new ProgramState(
                    new com.vsergeychik.carddemo.common.FixedWidthCodec(StandardCharsets.US_ASCII));
            assertThat(state.commarea().toProgram()).isBlank();

            controllerOver(unusedRepository()).returnToPrevScreen(state);

            assertThat(state.response().getNextProgram())
                    .isEqualTo(TransactionAddController.SIGN_ON_PROGRAM);
            assertThat(state.transferred()).isTrue();
        }

        @Test
        @DisplayName(":138 and :207 both echo the 218-byte passed communication area")
        void thePassedCommareaIsEchoedInBothDirections() {
            TransactionAddRequest request = firstEntry();
            Ct01Info info = new Ct01Info();
            info.setTrnidFirst("0000000000000001");
            info.setTrnidLast("0000000000000010");
            info.setPageNum(3);
            info.setNextPageYes();
            info.setTrnSelFlg("S");
            request.setCt01Info(info);

            ProgramState state = controllerOver(unusedRepository()).mainPara(request);
            Ct01Info echoed = state.response().getCt01Info();

            assertThat(echoed.getTrnidFirst()).isEqualTo("0000000000000001");
            assertThat(echoed.getTrnidLast()).isEqualTo("0000000000000010");
            assertThat(echoed.getPageNum()).isEqualTo(3);
            assertThat(echoed.isNextPageYes()).isTrue();
            assertThat(echoed.getTrnSelFlg()).isEqualTo("S");
            assertThat(echoed)
                    .as("copied, so a client that keeps what it sent cannot see it change")
                    .isNotSameAs(info);
            assertThat(state.response().getNavigationContext().isReenter()).isTrue();
            assertThat(TransactionAddRequest.COMMAREA_TOTAL_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH + Ct01Info.RECORD_LENGTH)
                    .isEqualTo(218);
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("the shared communication area is never widened for this screen")
                    .isEqualTo(160);
        }
    }

    @Nested
    @DisplayName("The edited amount - WS-TRAN-AMT PIC +99999999.99 at :49, :177 and :183")
    class EditedAmount {

        @ParameterizedTest(name = "{0} renders {1}")
        @CsvSource({
            "504.77,        +00000504.77",
            "0.00,          +00000000.00",
            "0,             +00000000.00",
            "-50.00,        -00000050.00",
            "-0.01,         -00000000.01",
            "1.05,          +00000001.05",
            "99999999.99,   +99999999.99",
            "-99999999.99,  -99999999.99",
            "123456789.12,  +23456789.12",
            "-123456789.12, -23456789.12",
            "504.779,       +00000504.77",
            "-504.779,      -00000504.77"
        })
        @DisplayName("the twelve-character edit form, including the high-order digit COBOL discards")
        void theEditFormIsReproducedExactly(String amount, String expected) {
            assertThat(TransactionAddController.editedTranAmt(new BigDecimal(amount)))
                    .isEqualTo(expected)
                    .hasSize(TransactionAddController.WS_TRAN_AMT_LENGTH);
        }

        @Test
        @DisplayName("truncation is toward zero, because ROUNDED appears nowhere in the 28 programs")
        void excessFractionalDigitsAreTruncatedNotRounded() {
            assertThat(TransactionAddController.editedTranAmt(new BigDecimal("0.999")))
                    .isEqualTo("+00000000.99");
            assertThat(TransactionAddController.editedTranAmt(new BigDecimal("-0.999")))
                    .isEqualTo("-00000000.99");
        }

        @Test
        @DisplayName("an amount is required; a COBOL numeric item has no absent state")
        void aNullAmountIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionAddController.editedTranAmt(null));
        }

        @Test
        @DisplayName("requireEditedDigits fills to ten positions and refuses an eleventh")
        void theDigitFillIsBoundedAtTenPositions() {
            assertThat(TransactionAddController.requireEditedDigits("1"))
                    .isEqualTo("0000000001")
                    .hasSize(TransactionAddController.WS_TRAN_AMT_DIGIT_COUNT);
            assertThat(TransactionAddController.requireEditedDigits("1234567890"))
                    .isEqualTo("1234567890");
            assertThatIllegalStateException()
                    .isThrownBy(() -> TransactionAddController.requireEditedDigits("12345678901"))
                    .withMessageContaining("digit positions");
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionAddController.requireEditedDigits(null));
        }

        @Test
        @DisplayName("the geometry of the edit mask adds up to the field width")
        void theEditMaskGeometryIsConsistent() {
            assertThat(TransactionAddController.WS_TRAN_AMT_DIGIT_COUNT)
                    .isEqualTo(TransactionAddController.WS_TRAN_AMT_INTEGER_DIGITS
                            + TransactionAddController.WS_TRAN_AMT_SCALE);
            assertThat(TransactionAddController.WS_TRAN_AMT_LENGTH)
                    .as("one sign, eight digits, one point, two digits")
                    .isEqualTo(1 + TransactionAddController.WS_TRAN_AMT_INTEGER_DIGITS + 1
                            + TransactionAddController.WS_TRAN_AMT_SCALE);
            assertThat(TransactionAddController.WS_TRAN_AMT_INTEGER_DIGITS)
                    .as("one digit narrower than TRAN-AMT, which is what makes :177 lossy")
                    .isEqualTo(TranRecord.TRAN_AMT_INTEGER_DIGITS - 1);
        }
    }

    @Nested
    @DisplayName("Figurative constants, the AID cast and the CSSETATY decision")
    class Helpers {

        @Test
        @DisplayName(":116, :147, :199 and the negation at :103 - the exact COBOL relation")
        void theFigurativeConstantRelationIsWholeItemEquality() {
            // Equal to SPACES, or equal to LOW-VALUES: the two whole-item comparisons the source joins
            // with OR. Written out rather than parameterised, because a null byte cannot survive a CSV
            // source's own quoting and trimming rules intact.
            assertThat(TransactionAddController.isSpacesOrLowValues(null)).isTrue();
            assertThat(TransactionAddController.isSpacesOrLowValues("")).isTrue();
            assertThat(TransactionAddController.isSpacesOrLowValues(" ")).isTrue();
            assertThat(TransactionAddController.isSpacesOrLowValues("   ")).isTrue();
            assertThat(TransactionAddController.isSpacesOrLowValues("\u0000")).isTrue();
            assertThat(TransactionAddController.isSpacesOrLowValues("\u0000\u0000\u0000")).isTrue();

            assertThat(TransactionAddController.isSpacesOrLowValues("A")).isFalse();
            assertThat(TransactionAddController.isSpacesOrLowValues("A ")).isFalse();
            assertThat(TransactionAddController.isSpacesOrLowValues(" A")).isFalse();
            assertThat(TransactionAddController.isSpacesOrLowValues("\u0000A")).isFalse();
            assertThat(TransactionAddController.isSpacesOrLowValues("A\u0000")).isFalse();
            assertThat(TransactionAddController.isSpacesOrLowValues(" \u0000")).isFalse();
            assertThat(TransactionAddController.isSpacesOrLowValues("\u0000 ")).isFalse();
        }

        @Test
        @DisplayName("an item that mixes spaces and nulls equals neither figurative constant")
        void aMixedItemIsNeitherConstant() {
            assertThat(TransactionAddController.isSpacesOrLowValues("    \u0000\u0000\u0000\u0000"))
                    .isFalse();
            assertThat(TransactionAddController.isSpacesOrLowValues("\u0000\u0000    ")).isFalse();
            assertThat(TransactionAddController.isSpacesOrLowValues(null)).isTrue();
            assertThat(TransactionAddController.isSpacesOrLowValues("")).isTrue();
        }

        @Test
        @DisplayName("SPACES and LOW-VALUES are different bytes and both are sized to the field")
        void theTwoFigurativeConstantsAreDistinct() {
            assertThat(TransactionAddController.spaces(4)).isEqualTo("    ");
            assertThat(TransactionAddController.lowValues(4)).isEqualTo("\u0000\u0000\u0000\u0000");
            assertThat(TransactionAddController.spaces(0)).isEmpty();
            assertThat(TransactionAddController.lowValues(0)).isEmpty();
            assertThat(TransactionAddController.SPACE).isNotEqualTo(TransactionAddController.LOW_VALUE);
        }

        @ParameterizedTest(name = "EIBAID 0x{0}")
        @CsvSource({"7D", "F3", "F4", "F5", "6D", "C3"})
        @DisplayName("the one-character AID carries the raw EIBAID byte through unchanged")
        void theAidCharacterIsTheAidByte(String hex) {
            byte expected = (byte) Integer.parseInt(hex, 16);
            assertThat(TransactionAddController.eibAidOf(String.valueOf((char) (expected & 0xFF))))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("an absent or empty AID becomes DFHNULL and takes WHEN OTHER")
        void anAbsentAidBecomesDfhnull() {
            assertThat(TransactionAddController.eibAidOf(null)).isEqualTo(CicsAid.DFHNULL);
            assertThat(TransactionAddController.eibAidOf("")).isEqualTo(CicsAid.DFHNULL);
        }

        @ParameterizedTest(name = "U+{0} is refused")
        @CsvSource({"01F3", "00F3F3", "0100", "FFFF", "20AC"})
        @DisplayName("a character above U+00FF is refused, never narrowed onto a named key")
        void anAidAboveOneByteIsRefused(String hex) {
            String aid = hex.length() > 4
                    ? String.valueOf((char) Integer.parseInt(hex.substring(0, 4), 16))
                            + (char) Integer.parseInt(hex.substring(4), 16)
                    : String.valueOf((char) Integer.parseInt(hex, 16));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionAddController.eibAidOf(aid))
                    .withMessageContaining("EIBAID is one byte");
        }

        @Test
        @DisplayName("U+01F3 would have narrowed onto DFHPF3, the key this program transfers on")
        void theAliasThisGuardCloses() {
            // The whole point of the guard: (byte) '\u01F3' is 0xF3, which IS DFHPF3. Without it a
            // caller could reach the PF3 arm at :115 without ever presenting PF3.
            assertThat((byte) '\u01F3').isEqualTo(CicsAid.DFHPF3);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionAddController.eibAidOf("\u01F3"));
            // And the AID it aliases is still accepted on its own, so nothing legitimate was lost.
            assertThat(TransactionAddController.eibAidOf("\u00F3")).isEqualTo(CicsAid.DFHPF3);
            assertThat(TransactionAddController.MAX_AID_CODE_POINT).isEqualTo((char) 0x00FF);
        }

        @Test
        @DisplayName("an AID longer than one character is refused: EIBAID is one byte")
        void anAidLongerThanOneCharacterIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionAddController.eibAidOf("\u007D\u00F3"))
                    .withMessageContaining("characters");
            assertThat(TransactionAddRequest.AID_LENGTH).isEqualTo(1);
        }

        @Test
        @DisplayName("the whole one-byte AID space is accepted, boundaries included")
        void theWholeOneByteSpaceIsAccepted() {
            assertThat(TransactionAddController.eibAidOf("\u0000")).isEqualTo((byte) 0x00);
            assertThat(TransactionAddController.eibAidOf(String.valueOf(
                    TransactionAddController.MAX_AID_CODE_POINT))).isEqualTo((byte) 0xFF);
        }

        @Test
        @DisplayName("this screen never highlights a field - in either state (gate G38)")
        void theCssetatyDecisionIsAlwaysUntouched() {
            assertThat(TransactionAddController.LOOKUP_FIELD_NOT_OK).isFalse();
            assertThat(TransactionAddController.LOOKUP_FIELD_BLANK).isFalse();
            assertThat(TransactionAddController.lookupFieldHighlight(true).untouched()).isTrue();
            assertThat(TransactionAddController.lookupFieldHighlight(false).untouched()).isTrue();

            TransactionRepository repository = repositoryReturning(ReadResult.notFound(DD_NAME));
            TransactionAddRequest rejected = reentry(CicsAid.DFHENTER);
            rejected.setTrnidin("9999999999999999");

            for (ProgramState state : List.of(
                    controllerOver(unusedRepository()).mainPara(firstEntry()),
                    controllerOver(unusedRepository()).mainPara(reentry(CicsAid.DFHPF12)),
                    controllerOver(repository).mainPara(rejected))) {
                assertThat(state.lookupFieldHighlight().untouched()).isTrue();
                assertThat(TransactionAddController.isErrorColoured(state.response()))
                        .as("no DFHRED is ever written, because CSSETATY is not copied by COTRN01C")
                        .isFalse();
                assertThat(state.response().attributes(ScreenField.TRNIDINO))
                        .isEqualTo(AttributeQuad.defaults());
            }

            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionAddController.isErrorColoured(null));
        }

        @Test
        @DisplayName("a red colour item is detectable, so the negative assertions above mean something")
        void theColourProbeCanActuallySeeRed() {
            TransactionAddResponse response = new TransactionAddResponse();
            assertThat(TransactionAddController.isErrorColoured(response)).isFalse();
            response.setAttributes(ScreenField.TRNIDINO,
                    AttributeQuad.defaults().withColour(BmsAttributes.DFHRED));
            assertThat(TransactionAddController.isErrorColoured(response)).isTrue();
        }

        @Test
        @DisplayName("the two group moves cover the right fields and nothing else")
        void theGroupMovesTouchExactlyTheDeclaredFields() {
            assertThat(TransactionAddController.DETAIL_FIELDS)
                    .hasSize(13)
                    .doesNotContain(ScreenField.TRNIDINO, ScreenField.ERRMSGO,
                            ScreenField.TRNNAMEO, ScreenField.TITLE01O);

            TransactionAddResponse response = new TransactionAddResponse();
            TransactionAddController.moveLowValuesToOutputMap(response);
            for (ScreenField field : ScreenField.values()) {
                assertThat(response.payload(field))
                        .isEqualTo(TransactionAddController.lowValues(field.payloadLength()));
                assertThat(response.attributes(field)).isEqualTo(AttributeQuad.defaults());
            }

            TransactionAddController.blankDetailFields(response);
            for (ScreenField field : TransactionAddController.DETAIL_FIELDS) {
                assertThat(response.payload(field)).isBlank();
            }
            assertThat(response.getTrnidino())
                    .as("the lookup field is not a detail field")
                    .isEqualTo(TransactionAddController.lowValues(
                            ScreenField.TRNIDINO.payloadLength()));

            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionAddController.moveLowValuesToOutputMap(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionAddController.blankDetailFields(null));
        }

        @Test
        @DisplayName("the two projections of the symbolic map must agree, and are checked")
        void theProjectionsAreCrossChecked() {
            TransactionAddController.requireMatchingProjections(
                    TransactionAddRequest.PAYLOAD_FIELD_COUNT, ScreenField.values().length);
            assertThatIllegalStateException()
                    .isThrownBy(() -> TransactionAddController.requireMatchingProjections(21, 20))
                    .withMessageContaining("COTRN01.CPY");
        }
    }

    @Nested
    @DisplayName("The screen contract - 21 DFHMDF fields, and only those (gate G9)")
    class ScreenContract {

        @Test
        @DisplayName("both projections declare exactly the 21 verified names and widths")
        void theTwentyOneFieldsAndWidthsAreTheCopybookSown() {
            assertThat(TransactionAddRequest.PAYLOAD_FIELD_COUNT).isEqualTo(21);
            assertThat(ScreenField.values()).hasSize(21);
            assertThat(TransactionAddRequest.PAYLOAD_FIELD_NAMES).hasSize(21);

            int[] widths = {4, 40, 8, 8, 40, 8, 16, 16, 16, 2, 4, 10, 60, 12, 10, 10, 9, 30, 25, 10, 78};
            String[] bases = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
                "TRNIDIN", "TRNID", "CARDNUM", "TTYPCD", "TCATCD", "TRNSRC", "TDESC", "TRNAMT",
                "TORIGDT", "TPROCDT", "MID", "MNAME", "MCITY", "MZIP", "ERRMSG"};

            ScreenField[] fields = ScreenField.values();
            for (int index = 0; index < fields.length; index++) {
                assertThat(fields[index].baseName()).isEqualTo(bases[index]);
                assertThat(fields[index].payloadLength()).isEqualTo(widths[index]);
                assertThat(TransactionAddRequest.PAYLOAD_FIELD_NAMES.get(index))
                        .isEqualTo(bases[index] + "I");
                assertThat(TransactionAddRequest.declaredLengthOf(bases[index] + "I"))
                        .isEqualTo(widths[index]);
                assertThat(fields[index].payloadOffset())
                        .as("the xxxI item and the xxxO item are the same bytes")
                        .isEqualTo(fields[index].groupOffset() + 7);
            }
            assertThat(java.util.Arrays.stream(widths).sum()).isEqualTo(416);
        }

        @Test
        @DisplayName("no xxxL, xxxF, xxxA, xxxC, xxxP, xxxH or xxxV item appears as a JSON member")
        void onlyThePayloadItemsAreSerialised() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));
            TransactionRepository repository = repositoryReturning(ReadResult.found(DD_NAME, record));
            TransactionAddRequest request = reentry(CicsAid.DFHENTER);
            request.setTrnidin(KNOWN_TRAN_ID);

            String json = mapper.writeValueAsString(
                    controllerOver(repository).mainPara(request).response());

            for (ScreenField field : ScreenField.values()) {
                String base = field.baseName().toLowerCase(java.util.Locale.ROOT);
                assertThat(json).contains("\"" + base + "o\"");
                for (String suffix : List.of("l", "f", "a", "c", "p", "h", "v")) {
                    assertThat(json)
                            .as(field.baseName() + suffix + " is metadata, never a payload member")
                            .doesNotContain("\"" + base + suffix + "\"");
                }
            }
            assertThat(json).contains("\"navigationContext\"").contains("\"ct01Info\"")
                    .contains("\"nextProgram\"");
        }

        @Test
        @DisplayName("RECEIVE copies all 21 received values into the redefined output map")
        void receiveCopiesEveryField() {
            ProgramState state = new ProgramState(
                    new com.vsergeychik.carddemo.common.FixedWidthCodec(StandardCharsets.US_ASCII));
            TransactionAddRequest request = new TransactionAddRequest();
            request.setTrnname("CT01");
            request.setTrnidin(KNOWN_TRAN_ID);
            request.setTdesc("D".repeat(60));
            request.setMzip("99999-1111");
            request.setErrmsg("E".repeat(78));

            controllerOver(unusedRepository()).receiveTrnviewScreen(state, request);

            assertThat(state.response().getTrnnameo()).isEqualTo("CT01");
            assertThat(state.response().getTrnidino()).isEqualTo(KNOWN_TRAN_ID);
            assertThat(state.response().getTdesco()).isEqualTo("D".repeat(60));
            assertThat(state.response().getMzipo()).isEqualTo("99999-1111");
            assertThat(state.response().getErrmsgo()).isEqualTo("E".repeat(78));
            assertThat(state.respCd()).isEqualTo(FileStatus.NORMAL);
            assertThat(state.reasCd()).isEqualTo(FileStatus.NO_REASON_CODE);

            assertThatNullPointerException().isThrownBy(() -> controllerOver(unusedRepository())
                    .receiveTrnviewScreen(state, null));
        }
    }

    @Nested
    @DisplayName("ProgramState - the WORKING-STORAGE carrier")
    class WorkingStorage {

        private ProgramState freshState() {
            return new ProgramState(
                    new com.vsergeychik.carddemo.common.FixedWidthCodec(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("every item starts at the VALUE clause the copybook declares")
        void theInitialStateIsTheValueClauses() {
            ProgramState state = freshState();

            assertThat(state.message()).hasSize(80).isBlank();
            assertThat(state.errFlagOff()).isTrue();
            assertThat(state.usrModifiedNo()).isTrue();
            assertThat(state.tranAmtEdited()).hasSize(12).isBlank();
            assertThat(state.tranId()).hasSize(16).isBlank();
            assertThat(state.respCd()).isZero();
            assertThat(state.reasCd()).isZero();
            assertThat(state.commarea()).isEqualTo(NavigationContext.empty());
            assertThat(state.ct01Info().getPageNum()).isZero();
            assertThat(state.ct01Info().isNextPageNo()).isTrue();
            assertThat(state.cursorRequested()).isFalse();
            assertThat(state.resolvedAid()).isEmpty();
            assertThat(state.lookupFieldHighlight().untouched()).isTrue();
            assertThat(state.displays()).isEmpty();
            assertThat(state.screensSent()).isZero();
            assertThat(state.screenSent()).isFalse();
            assertThat(state.returned()).isFalse();
            assertThat(state.transferred()).isFalse();
            assertThat(state.readResult()).isEmpty();
            assertThat(state.tranRecord()).isEmpty();
            assertThat(state.dateHeader()).isEmpty();
        }

        @Test
        @DisplayName("each item is held at its declared width, and each mutator refuses null")
        void everyItemIsHeldAtItsDeclaredWidth() {
            ProgramState state = freshState();

            state.setMessage("short");
            assertThat(state.message()).hasSize(80).startsWith("short");
            state.setTranAmtEdited("+1.00");
            assertThat(state.tranAmtEdited()).hasSize(12);
            state.setTranId("1");
            assertThat(state.tranId()).hasSize(16);
            state.setErrFlagOn();
            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.errFlagOff()).isFalse();
            state.setErrFlagOff();
            assertThat(state.errFlagOff()).isTrue();
            state.setUsrModifiedNo();
            assertThat(state.usrModifiedNo()).isTrue();
            state.setRespCd(FileStatus.NOTFND);
            state.setReasCd(7);
            assertThat(state.respCd()).isEqualTo(FileStatus.NOTFND);
            assertThat(state.reasCd()).isEqualTo(7);
            state.moveMinusOneTo(ScreenField.TRNIDINO);
            assertThat(state.cursorRequested()).isTrue();
            assertThat(state.cursorField()).isEqualTo(ScreenField.TRNIDINO);
            state.recordDisplay("RESP:000000013REAS:000000000");
            assertThat(state.displays()).hasSize(1);
            state.recordScreenSent();
            assertThat(state.screenSent()).isTrue();
            assertThat(state.screensSent()).isEqualTo(1);
            state.markReturned();
            state.markTransferred();
            assertThat(state.returned()).isTrue();
            assertThat(state.transferred()).isTrue();
            state.setResolvedAid(Optional.of(AidKey.ENTER));
            assertThat(state.resolvedAid()).contains(AidKey.ENTER);
            state.setLookupFieldHighlight(
                    TransactionAddController.lookupFieldHighlight(true));
            assertThat(state.lookupFieldHighlight().untouched()).isTrue();

            TranRecord record = tranRecord(KNOWN_TRAN_ID, BigDecimal.ONE);
            state.setReadResult(ReadResult.found(DD_NAME, record));
            assertThat(state.readResult()).isPresent();
            assertThat(state.tranRecord()).isPresent();
            state.setReadResult(ReadResult.notFound(DD_NAME));
            assertThat(state.tranRecord()).isEmpty();

            assertThatNullPointerException().isThrownBy(() -> state.setMessage(null));
            assertThatNullPointerException().isThrownBy(() -> state.setTranAmtEdited(null));
            assertThatNullPointerException().isThrownBy(() -> state.setTranId(null));
            assertThatNullPointerException().isThrownBy(() -> state.setCommarea(null));
            assertThatNullPointerException().isThrownBy(() -> state.setCt01Info(null));
            assertThatNullPointerException().isThrownBy(() -> state.setReadResult(null));
            assertThatNullPointerException().isThrownBy(() -> state.setDateHeader(null));
            assertThatNullPointerException().isThrownBy(() -> state.moveMinusOneTo(null));
            assertThatNullPointerException().isThrownBy(() -> state.setResolvedAid(null));
            assertThatNullPointerException().isThrownBy(() -> state.setLookupFieldHighlight(null));
            assertThatNullPointerException().isThrownBy(() -> state.recordDisplay(null));
        }

        @Test
        @DisplayName("the displays view is unmodifiable, so a caller cannot forge console output")
        void theDisplayViewIsUnmodifiable() {
            ProgramState state = freshState();
            state.recordDisplay("one");
            List<String> view = state.displays();
            assertThat(view).containsExactly("one");
            org.assertj.core.api.Assertions
                    .assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> view.add("two"));
        }

        @Test
        @DisplayName("a null codec is refused: no image is ever rendered in the platform default")
        void aNullCodecIsRefused() {
            assertThatNullPointerException().isThrownBy(() -> new ProgramState(null));
        }

        @Test
        @DisplayName("the paragraph methods refuse a null state")
        void everyParagraphRefusesANullState() {
            TransactionAddController controller = controllerOver(unusedRepository());
            assertThatNullPointerException().isThrownBy(() -> controller.processEnterKey(null));
            assertThatNullPointerException().isThrownBy(() -> controller.readTransactFile(null));
            assertThatNullPointerException().isThrownBy(() -> controller.returnToPrevScreen(null));
            assertThatNullPointerException().isThrownBy(() -> controller.sendTrnviewScreen(null));
            assertThatNullPointerException().isThrownBy(() -> controller.returnToCics(null));
            assertThatNullPointerException().isThrownBy(() -> controller.echoPassedCommarea(null));
            assertThatNullPointerException().isThrownBy(() -> controller.populateHeaderInfo(null));
            assertThatNullPointerException().isThrownBy(() -> controller.clearCurrentScreen(null));
            assertThatNullPointerException().isThrownBy(() -> controller.initializeAllFields(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> controller.receiveTrnviewScreen(null, coldStart()));
            assertThatNullPointerException()
                    .isThrownBy(() -> controller.rejectAndSend(null, "x"));
            assertThatNullPointerException().isThrownBy(
                    () -> controller.rejectAndSend(new ProgramState(
                            new com.vsergeychik.carddemo.common.FixedWidthCodec(
                                    StandardCharsets.US_ASCII)), null));
        }
    }

    @Nested
    @DisplayName("Wiring and the HTTP adapter")
    class Wiring {

        @Test
        @DisplayName("all three collaborators are required, and the code page is never the default")
        void bothCollaboratorsAreRequired() {
            TransactionRepository repository = unusedRepository();
            DatasetUnitOfWork unitOfWork = realUnitOfWork();
            assertThatNullPointerException()
                    .isThrownBy(() -> new TransactionAddController(null, pinnedClock(), unitOfWork));
            assertThatNullPointerException()
                    .isThrownBy(() -> new TransactionAddController(repository, null, unitOfWork));
            assertThatNullPointerException()
                    .isThrownBy(() -> new TransactionAddController(repository, pinnedClock(), null))
                    .withMessageContaining("unit of work");
            assertThatNullPointerException().isThrownBy(
                    () -> new TransactionAddController(repository, pinnedClock(), unitOfWork, null));
            assertThat(TransactionAddController.DEFAULT_WORKING_STORAGE_CHARSET)
                    .isEqualTo(StandardCharsets.US_ASCII);
            assertThat(new TransactionAddController(repository, pinnedClock(), unitOfWork,
                    java.nio.charset.Charset.forName("IBM037"))).isNotNull();
        }

        @Test
        @DisplayName(":275 the UPDATE option's lock is taken inside an open unit of work")
        void theLockingReadRunsInsideAUnitOfWork() {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));
            TransactionRepository repository = mock(TransactionRepository.class);
            List<Boolean> insideAUnitOfWork = new ArrayList<>();
            List<Integer> completion = new ArrayList<>();
            when(repository.readForUpdateByTranId(org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(invocation -> {
                        insideAUnitOfWork.add(DatasetUnitOfWork.active());
                        TransactionSynchronizationManager.registerSynchronization(
                                new TransactionSynchronization() {
                                    @Override
                                    public void afterCompletion(int status) {
                                        completion.add(status);
                                    }
                                });
                        return ReadResult.found(DD_NAME, record);
                    });

            TransactionAddRequest request = reentry(CicsAid.DFHENTER);
            request.setTrnidin(KNOWN_TRAN_ID);
            ProgramState state = controllerOver(repository).mainPara(request);

            assertThat(state.tranRecord()).isPresent();
            // The real repository refuses FOR UPDATE with no transaction open, so this is what makes
            // the option the source states reproducible at all rather than only nominally issued.
            assertThat(insideAUnitOfWork).containsExactly(true);
            // The program writes nothing, so the boundary commits - which is the task's implicit
            // syncpoint at EXEC CICS RETURN, and there is no ROLLBACK anywhere in COTRN01C.
            assertThat(completion).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
            assertThat(DatasetUnitOfWork.active())
                    .as("the boundary closes when the task returns")
                    .isFalse();
        }

        @Test
        @DisplayName("a null request is refused before any boundary opens")
        void aNullRequestIsRefusedOutsideTheBoundary() {
            TransactionRepository repository = unusedRepository();

            assertThatNullPointerException()
                    .isThrownBy(() -> controllerOver(repository).mainPara(null));

            assertThat(DatasetUnitOfWork.active()).isFalse();
            verify(repository, never())
                    .readForUpdateByTranId(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("the identity constants agree with the CSD and the mapset")
        void theIdentityConstantsAgreeWithTheCsd() {
            assertThat(TransactionAddController.PROGRAM_NAME).isEqualTo("COTRN01C");
            assertThat(TransactionAddController.TRANSACTION_ID).isEqualTo("CT01");
            assertThat(TransactionAddController.MAPSET_NAME).isEqualTo("COTRN01");
            assertThat(TransactionAddController.MAP_NAME).isEqualTo("COTRN1A");
            assertThat(TransactionAddController.TRANSACT_FILE_NAME).isEqualTo("TRANSACT");
            assertThat(TransactionAddController.TRANSACTION_DETAIL_PATH)
                    .as("a read is expressed as a read on the record it reads, never as a command")
                    .isEqualTo("/api/transactions/{tranId}")
                    .doesNotContain("add")
                    .doesNotContain("view");
            assertThat(TransactionAddController.TRAN_ID_VARIABLE).isEqualTo("tranId");
            assertThat(TransactionAddController.PROGRAM_NAME)
                    .isEqualTo(TransactionAddResponse.PROGRAM_NAME);
            assertThat(TransactionAddController.MSG_TRAN_ID_EMPTY)
                    .isEqualTo("Tran ID can NOT be empty...");
            assertThat(TransactionAddController.MSG_TRAN_ID_NOT_FOUND)
                    .isEqualTo("Transaction ID NOT found...");
            assertThat(TransactionAddController.MSG_UNABLE_TO_LOOKUP)
                    .isEqualTo("Unable to lookup Transaction...");
        }

        @Test
        @DisplayName("the bean declaration Spring reads is exactly one @Autowired constructor")
        void theBeanDeclarationIsUnambiguous() throws Exception {
            assertThat(TransactionAddController.class
                    .isAnnotationPresent(org.springframework.web.bind.annotation.RestController.class))
                    .as("component scanning finds it as a controller, not as a plain bean")
                    .isTrue();

            long autowired = java.util.Arrays.stream(TransactionAddController.class.getConstructors())
                    .filter(constructor -> constructor.isAnnotationPresent(
                            org.springframework.beans.factory.annotation.Autowired.class))
                    .count();
            assertThat(autowired)
                    .as("two @Autowired constructors would make the bean definition ambiguous")
                    .isEqualTo(1);
            assertThat(TransactionAddController.class
                    .getConstructor(TransactionRepository.class, Clock.class,
                            DatasetUnitOfWork.class))
                    .isNotNull();

            java.lang.reflect.Method handler = TransactionAddController.class
                    .getMethod("viewTransaction", String.class, TransactionAddRequest.class);
            org.springframework.web.bind.annotation.GetMapping mapping = handler.getAnnotation(
                    org.springframework.web.bind.annotation.GetMapping.class);
            assertThat(mapping)
                    .as("COTRN01C reads and writes nothing, so the route is a GET")
                    .isNotNull();
            assertThat(handler.getAnnotation(
                    org.springframework.web.bind.annotation.PostMapping.class))
                    .as("no write-shaped mapping may remain on a read-only program")
                    .isNull();
            assertThat(mapping.path())
                    .containsExactly(TransactionAddController.TRANSACTION_DETAIL_PATH);
            assertThat(mapping.produces()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
            assertThat(handler.getReturnType())
                    .as("the screen travels in the shared envelope, beside its metadata")
                    .isEqualTo(ScreenResponse.class);
            assertThat(TransactionAddController.class.getPackageName())
                    .as("inside the scanned package, so no explicit registration is needed")
                    .startsWith("com.vsergeychik.carddemo");
        }

        @Test
        @DisplayName("no other main source claims the same path, so the mapping cannot be ambiguous")
        void thePathIsClaimedByThisControllerAlone() throws IOException {
            Path mainSources = repositoryFile("app/java/src/main/java/com/vsergeychik/carddemo");
            try (java.util.stream.Stream<Path> walk = Files.walk(mainSources)) {
                List<String> claimants = walk
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> {
                            try {
                                return Files.readString(path, StandardCharsets.UTF_8)
                                        .contains("\"" + TransactionAddController
                                                .TRANSACTION_DETAIL_PATH + "\"");
                            } catch (IOException failure) {
                                throw new IllegalStateException("Could not read " + path, failure);
                            }
                        })
                        .map(path -> path.getFileName().toString())
                        .toList();
                assertThat(claimants).containsExactly("TransactionAddController.java");
            }
        }

        @Test
        @DisplayName("GET /api/transactions/{tranId} binds the body, delegates and returns the screen")
        void theAdapterBindsDelegatesAndProjects() throws Exception {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));
            TransactionRepository repository = repositoryReturning(ReadResult.found(DD_NAME, record));
            ObjectMapper mapper = new ObjectMapper();

            MockMvc mockMvc = MockMvcBuilders
                    .standaloneSetup(controllerOver(repository))
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                    .build();

            TransactionAddRequest request = reentry(CicsAid.DFHENTER);

            mockMvc.perform(get("/api/transactions/{tranId}", KNOWN_TRAN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnido").value(KNOWN_TRAN_ID))
                    .andExpect(jsonPath("$.trnamto").value("+00000504.77"))
                    .andExpect(jsonPath("$.nextProgram").value("COTRN01C"))
                    .andExpect(jsonPath("$.pgmnameo").value("COTRN01C"))
                    // The 21 screen items stay flat at the top level; the metadata joins them as one
                    // sibling member rather than being dropped or mixed into the projection.
                    .andExpect(jsonPath("$.screenMetadata.cursorField").value("TRNIDIN"))
                    .andExpect(jsonPath("$.screenMetadata.fields.ERRMSG.colour").exists())
                    .andExpect(jsonPath("$.cursorField").doesNotExist());
        }

        @Test
        @DisplayName("over HTTP, an extension naming another transaction cannot make the URI read it")
        void theUriIsTheOnlyIdentityOverHttp() throws Exception {
            // Only reachable through the HTTP binder: /api/transactions/A with CDEMO-CT01-TRN-SELECTED
            // naming B used to read B on the first-entry arm.
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));
            TransactionRepository repository = repositoryReturning(ReadResult.found(DD_NAME, record));
            ObjectMapper mapper = new ObjectMapper();
            MockMvc mockMvc = MockMvcBuilders
                    .standaloneSetup(controllerOver(repository))
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                    .build();
            TransactionAddRequest arriving = firstEntry();
            arriving.getCt01Info().setTrnSelected("0000000000000099");

            mockMvc.perform(get("/api/transactions/{tranId}", KNOWN_TRAN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(arriving)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnidino").value(KNOWN_TRAN_ID));

            verify(repository).readForUpdateByTranId(KNOWN_TRAN_ID);
            verify(repository, never()).readForUpdateByTranId("0000000000000099");
        }

        @Test
        @DisplayName("an absent body is EIBCALEN = 0 and is answered with the sign-on target")
        void theAdapterAnswersAColdStart() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            MockMvc mockMvc = MockMvcBuilders
                    .standaloneSetup(controllerOver(unusedRepository()))
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                    .build();

            mockMvc.perform(get("/api/transactions/{tranId}", KNOWN_TRAN_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value("COSGN00C"));
        }

        @Test
        @DisplayName("the controller keeps no state between executions")
        void twoExecutionsShareNothing() {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));
            TransactionRepository repository = repositoryReturning(ReadResult.found(DD_NAME, record));
            TransactionAddController controller = controllerOver(repository);

            TransactionAddRequest found = reentry(CicsAid.DFHENTER);
            found.setTrnidin(KNOWN_TRAN_ID);
            ProgramState first = controller.mainPara(found);
            ProgramState second = controller.mainPara(reentry(CicsAid.DFHPF12));

            assertThat(first).isNotSameAs(second);
            assertThat(first.response()).isNotSameAs(second.response());
            assertThat(first.errFlagOn()).isFalse();
            assertThat(second.errFlagOn()).isTrue();
            assertThat(first.response().getTrnido()).isEqualTo(KNOWN_TRAN_ID);
            assertThat(second.response().getTrnido())
                    .as("the second execution cannot see the first one's record")
                    .isNotEqualTo(KNOWN_TRAN_ID);
        }
    }
    // =============================================================================================
    // The route binding: the URI's identity, and the metadata the envelope carries
    // =============================================================================================

    @Nested
    @DisplayName("bind - the URI names the transaction, in every carrier of that identity")
    class RouteBinding {

        private final TransactionAddController controller = controllerOver(unusedRepository());

        @Test
        @DisplayName("the path variable is the identity, and it fills TRNIDIN")
        void thePathVariableIsTheIdentity() {
            TransactionAddRequest bound = controller.bind(KNOWN_TRAN_ID, reentry(CicsAid.DFHENTER));

            assertThat(bound.getTrnidin()).isEqualTo(KNOWN_TRAN_ID)
                    .hasSize(TransactionAddRequest.TRNIDIN_LENGTH);
        }

        @Test
        @DisplayName("it fills CDEMO-CT01-TRN-SELECTED too, which is what first entry reads")
        void thePathVariableAlsoFillsTheExtension() {
            // app/cbl/COTRN01C.cbl:103-106 reads the extension, not the typed field, on first entry.
            TransactionAddRequest bound = controller.bind(KNOWN_TRAN_ID, reentry(CicsAid.DFHENTER));

            assertThat(bound.getCt01Info().getTrnSelected()).isEqualTo(KNOWN_TRAN_ID)
                    .hasSize(TransactionAddRequest.Ct01Info.TRN_SELECTED_LENGTH);
        }

        @Test
        @DisplayName("a shorter path value is padded to the declared width, as a PIC X MOVE pads")
        void aShorterPathValueIsPadded() {
            TransactionAddRequest bound = controller.bind("1", reentry(CicsAid.DFHENTER));

            assertThat(bound.getTrnidin())
                    .isEqualTo("1" + " ".repeat(TransactionAddRequest.TRNIDIN_LENGTH - 1));
        }

        @Test
        @DisplayName("an over-width path value is refused, never truncated onto another transaction")
        void anOverWidePathValueIsRefused() {
            String tooWide = "0".repeat(TransactionAddRequest.TRNIDIN_LENGTH + 1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> controller.bind(tooWide, reentry(CicsAid.DFHENTER)))
                    .withMessageContaining("Padding it would keep the leading");
        }

        @Test
        @DisplayName("a body naming a different transaction has both carriers replaced by the URI's")
        void aDisagreeingBodyIsProjectedOver() {
            TransactionAddRequest stating = reentry(CicsAid.DFHENTER);
            stating.setTrnidin("0000000000000099");
            stating.getCt01Info().setTrnSelected("0000000000000098");

            TransactionAddRequest bound = controller.bind(KNOWN_TRAN_ID, stating);

            assertThat(bound.getTrnidin()).isEqualTo(KNOWN_TRAN_ID);
            assertThat(bound.getCt01Info().getTrnSelected()).isEqualTo(KNOWN_TRAN_ID);
        }

        @Test
        @DisplayName("an extension naming another transaction cannot make first entry read it")
        void aDisagreeingExtensionCannotBeRead() {
            TransactionRepository repository = repositoryReturning(ReadResult.notFound(DD_NAME));
            TransactionAddRequest arriving = firstEntry();
            arriving.getCt01Info().setTrnSelected("0000000000000099");

            controllerOver(repository).viewTransaction(KNOWN_TRAN_ID, arriving);

            verify(repository).readForUpdateByTranId(KNOWN_TRAN_ID);
            verify(repository, never()).readForUpdateByTranId("0000000000000099");
        }

        @Test
        @DisplayName("a blank extension no longer skips the path-driven lookup first entry asks for")
        void aBlankExtensionStillReadsThePathsTransaction() {
            TransactionRepository repository = repositoryReturning(ReadResult.notFound(DD_NAME));
            TransactionAddRequest arriving = firstEntry();
            arriving.getCt01Info()
                    .setTrnSelected(" ".repeat(TransactionAddRequest.Ct01Info.TRN_SELECTED_LENGTH));

            controllerOver(repository).viewTransaction(KNOWN_TRAN_ID, arriving);

            verify(repository).readForUpdateByTranId(KNOWN_TRAN_ID);
        }

        @Test
        @DisplayName("a body that agrees, space-padded or not, is accepted")
        void anAgreeingBodyIsAccepted() {
            TransactionAddRequest padded = reentry(CicsAid.DFHENTER);
            padded.setTrnidin(KNOWN_TRAN_ID);
            assertThat(controller.bind(KNOWN_TRAN_ID, padded).getTrnidin()).isEqualTo(KNOWN_TRAN_ID);

            TransactionAddRequest shortForm = reentry(CicsAid.DFHENTER);
            shortForm.setTrnidin("1");
            assertThat(controller.bind("1", shortForm).getTrnidin())
                    .isEqualTo("1" + " ".repeat(TransactionAddRequest.TRNIDIN_LENGTH - 1));
        }

        @Test
        @DisplayName("a blank or LOW-VALUES body field states nothing, so the path supplies it")
        void aBlankBodyFieldStatesNothing() {
            TransactionAddRequest spaces = reentry(CicsAid.DFHENTER);
            spaces.setTrnidin(" ".repeat(TransactionAddRequest.TRNIDIN_LENGTH));
            assertThat(controller.bind(KNOWN_TRAN_ID, spaces).getTrnidin()).isEqualTo(KNOWN_TRAN_ID);

            TransactionAddRequest lowValues = reentry(CicsAid.DFHENTER);
            lowValues.setTrnidin("\u0000".repeat(TransactionAddRequest.TRNIDIN_LENGTH));
            assertThat(controller.bind(KNOWN_TRAN_ID, lowValues).getTrnidin())
                    .isEqualTo(KNOWN_TRAN_ID);
        }

        @Test
        @DisplayName("an absent body is EIBCALEN = 0: no communication area travelled")
        void anAbsentBodyIsTheColdStart() {
            TransactionAddRequest bound = controller.bind(KNOWN_TRAN_ID, null);

            assertThat(bound.hasNavigationContext()).isFalse();
            assertThat(bound.getTrnidin()).isEqualTo(KNOWN_TRAN_ID);
        }

        @Test
        @DisplayName("binding copies rather than mutating what arrived, so the caller's body is intact")
        void bindingCopies() {
            TransactionAddRequest arrived = reentry(CicsAid.DFHENTER);
            String before = arrived.getTrnidin();

            controller.bind(KNOWN_TRAN_ID, arrived);

            assertThat(arrived.getTrnidin()).isEqualTo(before);
        }

        @Test
        @DisplayName("the handler refuses an absent path variable: it is the RIDFLD of the read")
        void theHandlerRefusesAnAbsentPathVariable() {
            assertThatNullPointerException()
                    .isThrownBy(() -> controller.viewTransaction(null, reentry(CicsAid.DFHENTER)))
                    .withMessageContaining("transaction id");
        }

        @Test
        @DisplayName("the metadata reports the cursor request, the quads and the message colour")
        void theMetadataTravelsBesideTheScreen() {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));
            ScreenResponse<TransactionAddResponse> answer =
                    controllerOver(repositoryReturning(ReadResult.found(DD_NAME, record)))
                            .viewTransaction(KNOWN_TRAN_ID, reentry(CicsAid.DFHENTER));

            ScreenMetadata metadata = answer.screenMetadata();
            assertThat(metadata.cursorField())
                    .as("MOVE -1 TO TRNIDINL is the one cursor request this program makes")
                    .isEqualTo("TRNIDIN");
            assertThat(metadata.fields())
                    .hasSize(TransactionAddResponse.ScreenField.values().length);
            assertThat(metadata.fields().keySet())
                    .as("keyed by DFHMDF label, not by the xxxO item name")
                    .contains("ERRMSG", "TRNID")
                    .doesNotContain("ERRMSGO");
            assertThat(metadata.messageColour())
                    .isEqualTo(Byte.toUnsignedInt(
                            answer.screen().attributes(TransactionAddResponse.ScreenField.ERRMSGO)
                                    .colour()));
            assertThat(metadata.resetAllOutputFields())
                    .as("MOVE LOW-VALUES has already been applied to the screen being published")
                    .isFalse();
        }

        @Test
        @DisplayName("an unsigned quad is published, so DFHRED reads 242 and never -14")
        void quadsArePublishedUnsigned() {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("1.00"));
            ProgramState state = controllerOver(repositoryReturning(ReadResult.found(DD_NAME, record)))
                    .mainPara(reentry(CicsAid.DFHENTER));
            state.response().setAttributes(TransactionAddResponse.ScreenField.ERRMSGO,
                    new TransactionAddResponse.AttributeQuad(BmsAttributes.DFHRED,
                            BmsAttributes.DFHDFCOL, BmsAttributes.DFHDFCOL, BmsAttributes.DFHDFCOL));

            ScreenMetadata metadata = state.screenMetadata();

            assertThat(metadata.field("ERRMSG").colour()).isEqualTo(242);
            assertThat(metadata.messageColour()).isEqualTo(242);
        }

        @Test
        @DisplayName("the CT00 list and the CT01 detail read coexist: one path template, two handlers")
        void theListAndTheDetailReadCoexist() throws Exception {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));
            TransactionRepository shared = repositoryReturning(ReadResult.found(DD_NAME, record));
            TransactionMenuController list = new TransactionMenuController(shared,
                    new com.vsergeychik.carddemo.common.FixedWidthCodec(StandardCharsets.US_ASCII),
                    pinnedClock());

            // Both controllers in one dispatcher, which is what production has. If GET
            // /api/transactions/{tranId} could be read as the collection route, or the other way round,
            // Spring would refuse to start or would answer the wrong handler - and this would fail.
            MockMvc both = MockMvcBuilders
                    .standaloneSetup(controllerOver(shared), list)
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                    .build();

            // The two screens are told apart by a member only one of them declares: COTRN00's paging
            // field, and COTRN01's edited amount. Which handler answered is therefore unambiguous
            // without depending on what either one painted.
            both.perform(get("/api/transactions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pagenumO").exists())
                    .andExpect(jsonPath("$.trnamto").doesNotExist());
            both.perform(get("/api/transactions/{tranId}", KNOWN_TRAN_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnamto").exists())
                    .andExpect(jsonPath("$.pagenumO").doesNotExist());
        }

        @Test
        @DisplayName("a transferring arm places no cursor, and the metadata names no field")
        void aTransferPlacesNoCursor() {
            ScreenResponse<TransactionAddResponse> answer = controllerOver(unusedRepository())
                    .viewTransaction(KNOWN_TRAN_ID, null);

            assertThat(answer.screen().getNextProgram().strip())
                    .isEqualTo(TransactionAddController.SIGN_ON_PROGRAM);
            assertThat(answer.screenMetadata().cursorField()).isNull();
        }
    }
}
