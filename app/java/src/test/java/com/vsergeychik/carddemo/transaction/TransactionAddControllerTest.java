package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
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
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.config.WebConfig;
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
import org.junit.jupiter.params.provider.EnumSource;
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
 * Proves {@link TransactionAddController} against {@code app/cbl/COTRN01C.cbl}, the 330-line CICS program
 * behind transaction {@code CT01}.
 */
@DisplayName("TransactionAddController - COTRN01C, transaction CT01, and it VIEWS")
class TransactionAddControllerTest {
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:34Z");

    private static final String EXPECTED_CURDATE = "07/19/22";

    private static final String EXPECTED_CURTIME = "23:12:34";

    private static final String KNOWN_TRAN_ID = "0000000000000001";

    private static final String DD_NAME = TransactionRepository.INPUT_DD_NAME;

    private static Clock pinnedClock() {
        return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    }

    private static TransactionAddController controllerOver(TransactionRepository repository) {
        return new TransactionAddController(repository, pinnedClock(), realUnitOfWork());
    }

    private static DatasetUnitOfWork realUnitOfWork() {
        SingleConnectionDataSource source = new SingleConnectionDataSource(
                "jdbc:h2:mem:cotrn01c-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "sa", "", true);
        source.setSuppressClose(true);
        DataSource dataSource = source;
        return new DatasetUnitOfWork(new JdbcTransactionManager(dataSource));
    }

    private static TransactionRepository unusedRepository() {
        return mock(TransactionRepository.class);
    }

    private static TransactionRepository repositoryReturning(ReadResult outcome) {
        TransactionRepository repository = mock(TransactionRepository.class);
        when(repository.readForUpdateByTranId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(outcome);
        return repository;
    }

    private static TransactionAddRequest coldStart() {
        return new TransactionAddRequest();
    }

    private static TransactionAddRequest firstEntry() {
        TransactionAddRequest request = new TransactionAddRequest();
        request.setNavigationContext(NavigationContext.empty());
        return request;
    }

    private static TransactionAddRequest reentry(byte eibAid) {
        TransactionAddRequest request = new TransactionAddRequest();
        request.setNavigationContext(NavigationContext.empty().withPgmReenter());
        request.setAid(String.valueOf((char) (eibAid & 0xFF)));
        return request;
    }

    private static TranRecord tranRecord(String tranId, BigDecimal amount) {
        TranRecord record = new TranRecord(StandardCharsets.US_ASCII);
        record.moveTranId(tranId);
        record.moveTranTypeCd("01");
        record.moveTranCatCd(5);
        record.moveTranSource("POS TERM");
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

    private static List<String> onlyRepositoryCalls(TransactionRepository repository) {
        return org.mockito.Mockito.mockingDetails(repository).getInvocations().stream()
                .map(invocation -> invocation.getMethod().getName())
                .distinct()
                .toList();
    }

    private static byte aidConstant(String name) {
        try {
            return (byte) CicsAid.class.getField(name).get(null);
        } catch (ReflectiveOperationException absent) {
            throw new IllegalStateException("CicsAid declares no constant " + name
                    + "; DFHAID names PF1 through PF24 and this suite drives all of them", absent);
        }
    }

    private static List<String> namedFieldsOf(String mapsetSource) {
        return mapsetSource.lines()
                .map(line -> line.split("\\s+", 3))
                .filter(parts -> parts.length > 1 && "DFHMDF".equals(parts[1]))
                .map(parts -> parts[0])
                .filter(name -> !name.isEmpty())
                .toList();
    }

    private static String accessorStem(TransactionAddResponse.ScreenField field) {
        String lower = field.baseName().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static java.lang.reflect.Method inputSetterFor(
            TransactionAddResponse.ScreenField field) throws NoSuchMethodException {
        return TransactionAddRequest.class.getMethod("set" + accessorStem(field), String.class);
    }

    private static java.lang.reflect.Method outputGetterFor(
            TransactionAddResponse.ScreenField field) throws NoSuchMethodException {
        return TransactionAddResponse.class.getMethod("get" + accessorStem(field) + "o");
    }

    private static void fill(StringBuilder image, int offset, int width, char filler) {
        for (int position = offset; position < offset + width; position++) {
            image.setCharAt(position, filler);
        }
    }

    private static String span(String recordImage, int offset,
                               TransactionAddResponse.ScreenField receiver) {
        int width = receiver.payloadLength();
        String sending = recordImage.substring(offset, Math.min(offset + width, recordImage.length()));
        return sending.length() >= width
                ? sending.substring(0, width)
                : sending + " ".repeat(width - sending.length());
    }

    private static int occurrencesOf(String source, String token) {
        int count = 0;
        int at = source.indexOf(token);
        while (at >= 0) {
            count++;
            at = source.indexOf(token, at + token.length());
        }
        return count;
    }

    @Nested
    @DisplayName("The name says Add, the program views, and both stay that way")
    class NameVersusBehaviour {
        @Test
        @DisplayName("the class documentation quotes the source header and names the swapped sibling")
        void classDocumentationRecordsTheDivergence() throws IOException {
            String source = Files.readString(repositoryFile("app/java/src/main/java/com/vsergeychik/"
                    + "carddemo/transaction/TransactionAddController.java"), StandardCharsets.UTF_8);

            assertThat(source)
                    .as("the source Function header is the primary evidence and must be quoted")
                    .contains("View a Transaction from TRANSACT file")
                    .as("the reader must be told this controller writes nothing")
                    .contains("writes nothing")
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
            verify(repository, never()).readByTranId(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("a submitted-form request shape still only reads: the keyed read is the sole call")
        void aSubmittedFormShapeStillOnlyReads() {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));
            TransactionRepository repository = repositoryReturning(ReadResult.found(DD_NAME, record));

            TransactionAddRequest submitted = reentry(CicsAid.DFHENTER);
            submitted.setTrnname("CT01");
            submitted.setTitle01("T".repeat(40));
            submitted.setCurdate("01/02/03");
            submitted.setPgmname("COTRN01C");
            submitted.setTitle02("U".repeat(40));
            submitted.setCurtime("04:05:06");
            submitted.setTrnidin(KNOWN_TRAN_ID);
            submitted.setTrnid("0000000000000777");
            submitted.setCardnum("4444333322221111");
            submitted.setTtypcd("99");
            submitted.setTcatcd("8888");
            submitted.setTrnsrc("KEYED IN  ");
            submitted.setTdesc("S".repeat(60));
            submitted.setTrnamt("+00000009.99");
            submitted.setTorigdt("2099-12-31");
            submitted.setTprocdt("2099-12-31");
            submitted.setMid("999999999");
            submitted.setMname("Z".repeat(30));
            submitted.setMcity("Y".repeat(25));
            submitted.setMzip("99999-0000");
            submitted.setErrmsg("W".repeat(78));

            TransactionAddResponse painted =
                    controllerOver(repository).mainPara(submitted).response();

            assertThat(painted.getTrnido()).isEqualTo(KNOWN_TRAN_ID).isNotEqualTo("0000000000000777");
            assertThat(painted.getCardnumo()).isEqualTo("4111111111111111");
            assertThat(onlyRepositoryCalls(repository))
                    .as("the keyed read is the whole of this program's file access")
                    .containsExactly("readForUpdateByTranId");
        }

        @Test
        @DisplayName("the repository declares no rewrite and no delete for this program to reach")
        void theRepositoryDeclaresNoRewriteOrDelete() {
            List<String> mutators = java.util.Arrays.stream(TransactionRepository.class.getMethods())
                    .map(java.lang.reflect.Method::getName)
                    .filter(name -> name.startsWith("rewrite") || name.startsWith("delete")
                            || name.startsWith("update") || name.startsWith("add")
                            || name.startsWith("insert") || name.startsWith("save"))
                    .distinct()
                    .toList();

            assertThat(mutators).isEmpty();
        }

        @Test
        @DisplayName("the COTRN01 map is a lookup-then-display screen, not a data-entry form")
        void theMapProvesTheScreenIsALookup() throws IOException {
            String cotrn01 = Files.readString(repositoryFile("app/bms/COTRN01.bms"),
                    StandardCharsets.UTF_8);
            String cotrn02 = Files.readString(repositoryFile("app/bms/COTRN02.bms"),
                    StandardCharsets.UTF_8);

            assertThat(namedFieldsOf(cotrn01))
                    .as("21 DFHMDF fields carry a name, which is what the symbolic map projects")
                    .hasSize(TransactionAddResponse.PAYLOAD_FIELD_COUNT);
            assertThat(occurrencesOf(cotrn01, "UNPROT"))
                    .as("exactly one field can be typed into: the lookup key")
                    .isEqualTo(1);
            assertThat(cotrn01)
                    .as("app/bms/COTRN01.bms:85 - the one UNPROT field is TRNIDIN, and it is the "
                            + "insert-cursor field, which is why MOVE -1 TO TRNIDINL is consistent")
                    .contains("TRNIDIN DFHMDF ATTRB=(FSET,IC,NORM,UNPROT)");
            assertThat(occurrencesOf(cotrn01, "CONFIRM"))
                    .as("no confirmation field: there is nothing to confirm on a read")
                    .isZero();

            assertThat(namedFieldsOf(cotrn02))
                    .hasSize(TransactionAddResponse.PAYLOAD_FIELD_COUNT);
            assertThat(occurrencesOf(cotrn02, "UNPROT"))
                    .as("14 of COTRN02's 21 fields are typed into, and it has a CONFIRM field")
                    .isEqualTo(14);
            assertThat(cotrn02).contains("CONFIRM DFHMDF");
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

        @ParameterizedTest(name = "CDEMO-FROM-PROGRAM = {0} falls back to COMEN01C")
        @ValueSource(strings = {"SPACES", "LOW-VALUES"})
        @DisplayName(":116 the fallback test is `= SPACES OR LOW-VALUES`, so both are driven")
        void pf3FallsBackForEitherFigurativeConstant(String figurativeConstant) {
            String fromProgram = "SPACES".equals(figurativeConstant)
                    ? " ".repeat(NavigationContext.FROM_PROGRAM_LENGTH)
                    : "\u0000".repeat(NavigationContext.FROM_PROGRAM_LENGTH);

            TransactionAddRequest request = new TransactionAddRequest();
            request.setNavigationContext(NavigationContext.empty()
                    .withPgmReenter()
                    .withFromProgram(fromProgram));
            request.setAid(String.valueOf((char) (CicsAid.DFHPF3 & 0xFF)));

            ProgramState state = controllerOver(unusedRepository()).mainPara(request);

            assertThat(state.response().getNextProgram())
                    .isEqualTo(TransactionAddController.MAIN_MENU_PROGRAM);
            assertThat(state.transferred()).isTrue();
            assertThat(state.screensSent())
                    .as(":122 transfers without sending: the next program paints")
                    .isZero();
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
        @DisplayName(":112-132 PF7 and PF8 are COTRN00C's keys, not this program's - both are rejected")
        void thePagingKeysOfTheSiblingScreenAreRejectedHere() {
            for (byte pagingKey : new byte[] {CicsAid.DFHPF7, CicsAid.DFHPF8}) {
                ProgramState state = controllerOver(unusedRepository()).mainPara(reentry(pagingKey));

                assertThat(state.errFlagOn())
                        .as("0x%02X reaches WHEN OTHER at :128", pagingKey)
                        .isTrue();
                assertThat(state.message().strip())
                        .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY.strip());
                assertThat(state.transferred())
                        .as("no arm of this EVALUATE transfers on a paging key")
                        .isFalse();
                assertThat(state.screensSent()).isEqualTo(1);
            }

            assertThat(List.of(CicsAid.DFHENTER, CicsAid.DFHPF3, CicsAid.DFHPF4, CicsAid.DFHPF5))
                    .doesNotContain(CicsAid.DFHPF7, CicsAid.DFHPF8);
            assertThat(PfKeyResolver.isPf7(CicsAid.DFHPF7))
                    .as("the resolver knows the key; this program simply has no arm for it")
                    .isTrue();
            assertThat(PfKeyResolver.isPf8(CicsAid.DFHPF8)).isTrue();
        }

        @ParameterizedTest(name = "{0} in {1} state")
        @CsvSource({
            "7D, ENTER,   SEND",
            "F3, ENTER,   SEND",
            "F4, ENTER,   SEND",
            "F5, ENTER,   SEND",
            "F7, ENTER,   SEND",
            "7D, REENTER, SEND",
            "F3, REENTER, TRANSFER",
            "F4, REENTER, SEND",
            "F5, REENTER, TRANSFER",
            "F7, REENTER, REJECT"
        })
        @DisplayName(":99 crossed with :112-132 - the whole dispatch matrix (gates G30, G38)")
        void theDispatchMatrixAcrossBothContextLevels(String hex, String context, String outcome) {
            byte eibAid = (byte) Integer.parseInt(hex, 16);
            boolean reenter = "REENTER".equals(context);

            TransactionAddRequest request = new TransactionAddRequest();
            NavigationContext commarea = NavigationContext.empty();
            request.setNavigationContext(reenter ? commarea.withPgmReenter() : commarea);
            request.setAid(String.valueOf((char) (eibAid & 0xFF)));
            request.setTrnidin(KNOWN_TRAN_ID);

            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));
            ProgramState state = controllerOver(repositoryReturning(
                    ReadResult.found(DD_NAME, record))).mainPara(request);

            switch (outcome) {
                case "SEND" -> {
                    assertThat(state.transferred()).isFalse();
                    assertThat(state.returned()).isTrue();
                    assertThat(state.screensSent()).isEqualTo(1);
                    assertThat(state.errFlagOn())
                            .as("neither the paint branch nor PF4 raises the error flag")
                            .isFalse();
                }
                case "TRANSFER" -> {
                    assertThat(state.transferred()).isTrue();
                    assertThat(state.returned()).isFalse();
                    assertThat(state.screensSent()).isZero();
                }
                case "REJECT" -> {
                    assertThat(state.errFlagOn()).isTrue();
                    assertThat(state.message().strip())
                            .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY.strip());
                    assertThat(state.transferred()).isFalse();
                }
                default -> throw new IllegalStateException("Unknown expected outcome " + outcome);
            }

            assertThat(state.commarea().isEnter())
                    .as(":204 MOVE ZEROS TO CDEMO-PGM-CONTEXT runs only on a transferring arm")
                    .isEqualTo(state.transferred());
            assertThat(state.commarea().isReenter()).isEqualTo(!state.transferred());
        }

        @Test
        @DisplayName(":112-132 the five arms are tested in the source's order, WHEN OTHER last (G30)")
        void theArmsAreTestedInTheSourcesOrder() throws IOException {
            String controller = Files.readString(repositoryFile("app/java/src/main/java/com/"
                    + "vsergeychik/carddemo/transaction/TransactionAddController.java"),
                    StandardCharsets.UTF_8);
            int mainPara = controller.indexOf("private ProgramState mainParaUnderLock");
            assertThat(mainPara).as("the dispatch lives in the MAIN-PARA body").isNotNegative();
            String body = controller.substring(mainPara);

            int enter = body.indexOf("PfKeyResolver.isEnter(");
            int pf3 = body.indexOf("PfKeyResolver.isPf3(");
            int pf4 = body.indexOf("PfKeyResolver.isPf4(");
            int pf5 = body.indexOf("PfKeyResolver.isPf5(");

            assertThat(List.of(enter, pf3, pf4, pf5))
                    .as("every named arm of :112-132 is present in the chain")
                    .doesNotContain(-1);
            assertThat(enter).as(":113 WHEN DFHENTER is first").isLessThan(pf3);
            assertThat(pf3).as(":115 WHEN DFHPF3 is second").isLessThan(pf4);
            assertThat(pf4).as(":123 WHEN DFHPF4 is third").isLessThan(pf5);
            assertThat(body.indexOf("} else {", pf5))
                    .as(":128 WHEN OTHER is the default arm and comes after all four named ones")
                    .isGreaterThan(pf5);

            assertThat(body)
                    .doesNotContain("PfKeyResolver.isPf7(")
                    .doesNotContain("PfKeyResolver.isPf8(");
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

            assertThat(response.getTrnido()).isEqualTo(KNOWN_TRAN_ID);
            assertThat(response.getCardnumo()).isEqualTo("4111111111111111");
            assertThat(response.getTtypcdo()).isEqualTo("01");
            assertThat(response.getTcatcdo())
                    .as(":181 TRAN-CAT-CD is PIC 9(04): the stored digits move")
                    .isEqualTo("0005");
            assertThat(response.getTrnsrco()).isEqualTo("POS TERM  ");
            assertThat(response.getTrnamto()).isEqualTo("+00000504.77");
            assertThat(response.getTdesco())
                    .as(":184 PIC X(100) into PIC X(60): 40 characters lost on the RIGHT")
                    .isEqualTo("A".repeat(60));
            assertThat(response.getTorigdto())
                    .as(":185 PIC X(26) into PIC X(10): the date survives, the time is cut")
                    .isEqualTo("2022-07-19");
            assertThat(response.getTprocdto()).isEqualTo("2022-07-20");
            assertThat(response.getMido())
                    .as(":187 TRAN-MERCHANT-ID is PIC 9(09)")
                    .isEqualTo("800000001");
            assertThat(response.getMnameo()).isEqualTo("M".repeat(30));
            assertThat(response.getMcityo()).isEqualTo("C".repeat(25));
            assertThat(response.getMzipo()).isEqualTo("12345-6789");
            assertThat(response.getErrmsgo()).isBlank();
        }

        @Test
        @DisplayName(":178-190 every painted field is taken from its declared CVTRA05Y span")
        void everyPaintedFieldComesFromItsCopybookOffset() {
            StringBuilder image = new StringBuilder("?".repeat(TranRecord.RECORD_LENGTH));
            fill(image, TranRecord.TRAN_ID_OFFSET, TranRecord.TRAN_ID_LENGTH, 'A');
            fill(image, TranRecord.TRAN_TYPE_CD_OFFSET, TranRecord.TRAN_TYPE_CD_LENGTH, 'B');
            fill(image, TranRecord.TRAN_CAT_CD_OFFSET, TranRecord.TRAN_CAT_CD_LENGTH, '7');
            fill(image, TranRecord.TRAN_SOURCE_OFFSET, TranRecord.TRAN_SOURCE_LENGTH, 'D');
            fill(image, TranRecord.TRAN_DESC_OFFSET, TranRecord.TRAN_DESC_LENGTH, 'E');
            fill(image, TranRecord.TRAN_AMT_OFFSET, TranRecord.TRAN_AMT_LENGTH, '0');
            image.setCharAt(TranRecord.TRAN_AMT_OFFSET + TranRecord.TRAN_AMT_LENGTH - 3, '9');
            image.setCharAt(TranRecord.TRAN_AMT_OFFSET + TranRecord.TRAN_AMT_LENGTH - 2, '5');
            image.setCharAt(TranRecord.TRAN_AMT_OFFSET + TranRecord.TRAN_AMT_LENGTH - 1, '0');
            fill(image, TranRecord.TRAN_MERCHANT_ID_OFFSET, TranRecord.TRAN_MERCHANT_ID_LENGTH, '3');
            fill(image, TranRecord.TRAN_MERCHANT_NAME_OFFSET,
                    TranRecord.TRAN_MERCHANT_NAME_LENGTH, 'G');
            fill(image, TranRecord.TRAN_MERCHANT_CITY_OFFSET,
                    TranRecord.TRAN_MERCHANT_CITY_LENGTH, 'H');
            fill(image, TranRecord.TRAN_MERCHANT_ZIP_OFFSET, TranRecord.TRAN_MERCHANT_ZIP_LENGTH, 'J');
            fill(image, TranRecord.TRAN_CARD_NUM_OFFSET, TranRecord.TRAN_CARD_NUM_LENGTH, '4');
            fill(image, TranRecord.TRAN_ORIG_TS_OFFSET, TranRecord.TRAN_ORIG_TS_LENGTH, 'L');
            fill(image, TranRecord.TRAN_PROC_TS_OFFSET, TranRecord.TRAN_PROC_TS_LENGTH, 'M');
            fill(image, TranRecord.FILLER_OFFSET, TranRecord.FILLER_LENGTH, ' ');
            String raw = image.toString();
            assertThat(raw).hasSize(TranRecord.RECORD_LENGTH);

            TranRecord record = TranRecord.decode(raw, StandardCharsets.US_ASCII);
            TransactionRepository repository = repositoryReturning(ReadResult.found(DD_NAME, record));
            TransactionAddRequest request = reentry(CicsAid.DFHENTER);
            request.setTrnidin(KNOWN_TRAN_ID);

            TransactionAddResponse painted = controllerOver(repository).mainPara(request).response();

            assertThat(painted.getTrnido())
                    .isEqualTo(span(raw, TranRecord.TRAN_ID_OFFSET, ScreenField.TRNIDO));
            assertThat(painted.getTtypcdo())
                    .isEqualTo(span(raw, TranRecord.TRAN_TYPE_CD_OFFSET, ScreenField.TTYPCDO));
            assertThat(painted.getTcatcdo())
                    .isEqualTo(span(raw, TranRecord.TRAN_CAT_CD_OFFSET, ScreenField.TCATCDO));
            assertThat(painted.getTrnsrco())
                    .isEqualTo(span(raw, TranRecord.TRAN_SOURCE_OFFSET, ScreenField.TRNSRCO));
            assertThat(painted.getTdesco())
                    .isEqualTo(span(raw, TranRecord.TRAN_DESC_OFFSET, ScreenField.TDESCO));
            assertThat(painted.getTrnamto())
                    .as("@132, eleven zoned bytes read as S9(09)V99 and edited into +99999999.99")
                    .isEqualTo("+00000009.50");
            assertThat(painted.getMido())
                    .isEqualTo(span(raw, TranRecord.TRAN_MERCHANT_ID_OFFSET, ScreenField.MIDO));
            assertThat(painted.getMnameo())
                    .isEqualTo(span(raw, TranRecord.TRAN_MERCHANT_NAME_OFFSET,
                            ScreenField.MNAMEO));
            assertThat(painted.getMcityo())
                    .isEqualTo(span(raw, TranRecord.TRAN_MERCHANT_CITY_OFFSET,
                            ScreenField.MCITYO));
            assertThat(painted.getMzipo())
                    .isEqualTo(span(raw, TranRecord.TRAN_MERCHANT_ZIP_OFFSET, ScreenField.MZIPO));
            assertThat(painted.getCardnumo())
                    .isEqualTo(span(raw, TranRecord.TRAN_CARD_NUM_OFFSET, ScreenField.CARDNUMO));
            assertThat(painted.getTorigdto())
                    .isEqualTo(span(raw, TranRecord.TRAN_ORIG_TS_OFFSET, ScreenField.TORIGDTO));
            assertThat(painted.getTprocdto())
                    .isEqualTo(span(raw, TranRecord.TRAN_PROC_TS_OFFSET, ScreenField.TPROCDTO));

            assertThat(new int[] {TranRecord.TRAN_ID_OFFSET, TranRecord.TRAN_TYPE_CD_OFFSET,
                TranRecord.TRAN_CAT_CD_OFFSET, TranRecord.TRAN_SOURCE_OFFSET,
                TranRecord.TRAN_DESC_OFFSET, TranRecord.TRAN_AMT_OFFSET,
                TranRecord.TRAN_MERCHANT_ID_OFFSET, TranRecord.TRAN_MERCHANT_NAME_OFFSET,
                TranRecord.TRAN_MERCHANT_CITY_OFFSET, TranRecord.TRAN_MERCHANT_ZIP_OFFSET,
                TranRecord.TRAN_CARD_NUM_OFFSET, TranRecord.TRAN_ORIG_TS_OFFSET,
                TranRecord.TRAN_PROC_TS_OFFSET})
                    .containsExactly(0, 16, 18, 22, 32, 132, 143, 152, 202, 252, 262, 278, 304);
        }

        @Test
        @DisplayName(":179 and :187 the card number and merchant id are shown in full, unmasked")
        void theCardNumberAndMerchantIdAreNotMasked() {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));
            TransactionRepository repository = repositoryReturning(ReadResult.found(DD_NAME, record));
            TransactionAddRequest request = reentry(CicsAid.DFHENTER);
            request.setTrnidin(KNOWN_TRAN_ID);

            TransactionAddResponse painted = controllerOver(repository).mainPara(request).response();

            assertThat(painted.getCardnumo())
                    .isEqualTo(record.tranCardNum())
                    .hasSize(ScreenField.CARDNUMO.payloadLength())
                    .doesNotContain("*")
                    .doesNotContain("X")
                    .containsOnlyDigits();
            assertThat(painted.getMido())
                    .isEqualTo(record.tranMerchantIdImage())
                    .doesNotContain("*")
                    .containsOnlyDigits();
        }

        @Test
        @DisplayName(":147 a key shorter than sixteen characters is padded and read, never rejected")
        void aShortKeyIsPaddedAndReadBecauseTheSourceHasNoLengthTest() {
            TransactionRepository repository = repositoryReturning(ReadResult.notFound(DD_NAME));
            TransactionAddRequest request = reentry(CicsAid.DFHENTER);
            request.setTrnidin("0001");

            ProgramState state = controllerOver(repository).mainPara(request);

            assertThat(state.response().getTrnidino()).isEqualTo("0001" + " ".repeat(12));
            assertThat(state.tranId())
                    .as(":172 MOVE TRNIDINI TO TRAN-ID - a PIC X(16) key, space-padded")
                    .isEqualTo("0001" + " ".repeat(12));
            verify(repository).readForUpdateByTranId("0001" + " ".repeat(12));
            assertThat(state.message().strip())
                    .as("the outcome is NOT FOUND, not 'can NOT be empty' - the source has no "
                            + "length edit to produce the latter")
                    .isEqualTo(TransactionAddController.MSG_TRAN_ID_NOT_FOUND)
                    .isNotEqualTo(TransactionAddController.MSG_TRAN_ID_EMPTY);
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

            assertThat(state.respCd())
                    .as("a reported NORMAL and an unreported response must not share one value")
                    .isEqualTo(FileStatus.RESP_NOT_REPORTED)
                    .isNotEqualTo(FileStatus.NORMAL);
            assertThat(FileStatus.respReported(state.respCd())).isFalse();

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
                    .hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(response.getTrnnameo())
                    .isEqualTo(TransactionAddController.TRANSACTION_ID);
            assertThat(response.getPgmnameo())
                    .isEqualTo(TransactionAddController.PROGRAM_NAME);
            assertThat(response.getCurdateo()).isEqualTo(EXPECTED_CURDATE);
            assertThat(response.getCurtimeo()).isEqualTo(EXPECTED_CURTIME);
            assertThat(state.dateHeader()).isPresent();

            DateHeader captured = state.dateHeader().orElseThrow();
            assertThat(captured.wsCurdateMmDdYy())
                    .isEqualTo(response.getCurdateo())
                    .hasSize(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH);
            assertThat(captured.wsCurtimeHhMmSs())
                    .isEqualTo(response.getCurtimeo())
                    .hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH);
            assertThat(captured.wsCurdate())
                    .as("WS-CURDATE is CSDAT01Y's YYYY + MM + DD group, beside the edited form")
                    .isEqualTo("20220719")
                    .hasSize(DateHeader.WS_CURDATE_LENGTH);
            assertThat(captured.wsCurtime())
                    .as("WS-CURTIME is HH + MM + SS + hundredths, so only the first six are pinned")
                    .startsWith("231234")
                    .hasSize(DateHeader.WS_CURTIME_LENGTH);
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

            if (aid.length() > TransactionAddRequest.AID_LENGTH) {
                assertThat(TransactionAddController.eibAidOf(aid)).isEqualTo(CicsAid.DFHNULL);
                assertThat(PfKeyResolver.resolve(TransactionAddController.eibAidOf(aid))).isEmpty();
            } else {
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> TransactionAddController.eibAidOf(aid))
                        .withMessageContaining("one EIBAID byte");
            }
        }

        @Test
        @DisplayName("U+01F3 would have narrowed onto DFHPF3, the key this program transfers on")
        void theAliasThisGuardCloses() {
            assertThat((byte) '\u01F3').isEqualTo(CicsAid.DFHPF3);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionAddController.eibAidOf("\u01F3"));
            assertThat(TransactionAddController.eibAidOf("\u00F3")).isEqualTo(CicsAid.DFHPF3);
            assertThat(TransactionAddController.MAX_AID_CODE_POINT).isEqualTo((char) 0x00FF);
        }

        @Test
        @DisplayName("two to five characters is the CCARD-AID token form, which is not a byte and so "
                + "names no key: DFHNULL, and WHEN OTHER")
        void theTokenFormResolvesToItsByte() {
            assertThat(TransactionAddController.eibAidOf("ENTER")).isEqualTo(CicsAid.DFHNULL);
            assertThat(TransactionAddController.eibAidOf("PFK03")).isEqualTo(CicsAid.DFHNULL);
            assertThat(TransactionAddController.eibAidOf("PA1")).isEqualTo(CicsAid.DFHNULL);
            assertThat(TransactionAddController.eibAidOf("PA1  ")).isEqualTo(CicsAid.DFHNULL);
            assertThat(TransactionAddController.eibAidOf("PFK99")).isEqualTo(CicsAid.DFHNULL);
            assertThat(TransactionAddController.eibAidOf("     ")).isEqualTo(CicsAid.DFHNULL);
            assertThat(TransactionAddController.eibAidOf("\u0000".repeat(5)))
                    .isEqualTo(CicsAid.DFHNULL);
            assertThat(TransactionAddRequest.AID_LENGTH).isEqualTo(1);
            assertThat(TransactionAddRequest.AID_TOKEN_LENGTH)
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @ParameterizedTest(name = "the byte behind {0} is read back as itself")
        @EnumSource(PfKeyResolver.AidKey.class)
        @DisplayName("every key a response can name is readable back as its own byte, unfolded")
        void everyTokenRoundTripsThroughTheResolver(PfKeyResolver.AidKey key) {
            byte source = someByteResolvingTo(key);

            assertThat(TransactionAddController.eibAidOf(PfKeyResolver.aidImage(source)))
                    .isEqualTo(source);
            assertThat(PfKeyResolver.resolve(source)).contains(key);
        }

        private byte someByteResolvingTo(PfKeyResolver.AidKey key) {
            for (int unsigned = 0; unsigned <= 0xFF; unsigned++) {
                if (PfKeyResolver.resolve((byte) unsigned).filter(key::equals).isPresent()) {
                    return (byte) unsigned;
                }
            }
            throw new AssertionError("PfKeyResolver maps no byte at all onto " + key
                    + ", which would mean the sixteen condition names of app/cpy/CVCRD01Y.cpy and the "
                    + "EVALUATE of app/cpy/CSSTRPFY.cpy have drifted apart");
        }

        @Test
        @DisplayName("a value wider than the token itself is refused: neither spelling can carry it")
        void anAidWiderThanTheTokenIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionAddController.eibAidOf("ENTER!"))
                    .withMessageContaining("refused rather than truncated");
        }

        @Test
        @DisplayName("the whole one-byte AID space is accepted, boundaries included")
        void theWholeOneByteSpaceIsAccepted() {
            assertThat(TransactionAddController.eibAidOf("\u0000")).isEqualTo((byte) 0x00);
            assertThat(TransactionAddController.eibAidOf(String.valueOf(
                    TransactionAddController.MAX_AID_CODE_POINT))).isEqualTo((byte) 0xFF);
        }

        @ParameterizedTest(name = "DFHPF{0} folds onto PFK{1}")
        @CsvSource({"13, 01", "14, 02", "15, 03", "16, 04", "17, 05", "18, 06",
            "19, 07", "20, 08", "21, 09", "22, 10", "23, 11", "24, 12"})
        @DisplayName("CSSTRPFY folds DFHPF13-DFHPF24 back onto PFK01-PFK12, one for one")
        void theResolverFoldsTheUpperTwelveKeys(int pfNumber, String foldedOnto) {
            byte upper = aidConstant("DFHPF" + pfNumber);
            byte lower = aidConstant("DFHPF" + Integer.parseInt(foldedOnto));

            assertThat(PfKeyResolver.resolve(upper))
                    .contains(AidKey.valueOf("PFK" + foldedOnto));
            assertThat(PfKeyResolver.resolve(lower))
                    .as("the lower key resolves to the same token, which is what a fold means")
                    .isEqualTo(PfKeyResolver.resolve(upper));
            assertThat(upper)
                    .as("the fold is on the token, never on the byte: the two AIDs stay distinct")
                    .isNotEqualTo(lower);
        }

        @Test
        @DisplayName("the resolver has no WHEN OTHER and no DFHPA3 arm, so an unmapped AID is absent")
        void theResolverHasNoDefaultArm() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3))
                    .as("DFHPA3 is not one of the copybook's tested AIDs")
                    .isEmpty();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHNULL)).isEmpty();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPEN)).isEmpty();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1))
                    .as("the two PA keys the copybook does test are still resolved")
                    .contains(AidKey.PA1);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA2)).contains(AidKey.PA2);

            ProgramState state = controllerOver(unusedRepository()).mainPara(reentry(CicsAid.DFHPA3));

            assertThat(state.resolvedAid()).isEmpty();
            assertThat(state.errFlagOn()).isTrue();
            assertThat(state.message().strip())
                    .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY.strip());
        }

        @Test
        @DisplayName("a folded upper key still reaches WHEN OTHER, because :112 compares raw bytes")
        void aFoldedUpperKeyIsStillAnInvalidKeyHere() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF16)).contains(AidKey.PFK04);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF4)).contains(AidKey.PFK04);

            TransactionAddRequest request = reentry(CicsAid.DFHPF16);
            request.setTrnidin(KNOWN_TRAN_ID);
            ProgramState state = controllerOver(unusedRepository()).mainPara(request);

            assertThat(state.resolvedAid())
                    .as("the token is recorded, exactly as YYYY-STORE-PFKEY would store it")
                    .contains(AidKey.PFK04);
            assertThat(state.errFlagOn())
                    .as("but the dispatch is on the byte, so PF16 is not PF4")
                    .isTrue();
            assertThat(state.response().getTrnidino())
                    .as("CLEAR-CURRENT-SCREEN did not run, so the received key survives")
                    .isNotBlank();
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
        @DisplayName("CSSETATY's highlight is real and REENTER-only, and this screen still never uses it")
        void theCssetatyHighlightIsRealAndReenterOnly() {
            FieldAttributeSetter.FieldHighlight blankOnReentry =
                    FieldAttributeSetter.resolveFromFlags(true, true, true);
            assertThat(blankOnReentry.untouched()).isFalse();
            assertThat(blankOnReentry.colourItemAssigned()).isTrue();
            assertThat(blankOnReentry.colourItemValue())
                    .as("CSSETATY.cpy:21-22 MOVE DFHRED TO (SCRNVAR2)C")
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(blankOnReentry.outputItemValue())
                    .as("CSSETATY.cpy:24-25 MOVE '*' TO (SCRNVAR2)O - the inner, BLANK-only action")
                    .isEqualTo(FieldAttributeSetter.ASTERISK);

            FieldAttributeSetter.FieldHighlight wrongButNotBlankOnReentry =
                    FieldAttributeSetter.resolveFromFlags(true, false, true);
            assertThat(wrongButNotBlankOnReentry.colourItemAssigned())
                    .as("the outer test passes on NOT-OK alone, so the colour is still assigned")
                    .isTrue();
            assertThat(wrongButNotBlankOnReentry.outputItemAssigned())
                    .as("but :23's inner test does not, so no asterisk is moved")
                    .isFalse();

            FieldAttributeSetter.FieldHighlight blankOnFirstEntry =
                    FieldAttributeSetter.resolveFromFlags(true, true, false);
            assertThat(blankOnFirstEntry.untouched())
                    .as("the same blank field is left entirely alone on first entry - REENTER gates "
                            + "the whole outer IF at :18-20")
                    .isTrue();

            TransactionAddResponse probe = new TransactionAddResponse();
            probe.applyHighlight(ScreenField.TRNIDINO, blankOnReentry);
            assertThat(TransactionAddController.isErrorColoured(probe)).isTrue();
            assertThat(probe.getTrnidino())
                    .as("MOVE '*' TO a PIC X(16) item space-pads to the declared width, as any "
                            + "alphanumeric MOVE does - the asterisk is the first byte, not the field")
                    .isEqualTo(FieldAttributeSetter.ASTERISK
                            + " ".repeat(ScreenField.TRNIDINO.payloadLength()
                                    - FieldAttributeSetter.ASTERISK.length()))
                    .hasSize(ScreenField.TRNIDINO.payloadLength())
                    .startsWith(FieldAttributeSetter.ASTERISK);

            assertThat(TransactionAddController.lookupFieldHighlight(true).untouched()).isTrue();
            assertThat(TransactionAddController.lookupFieldHighlight(false).untouched()).isTrue();
            assertThat(TransactionAddController.lookupFieldHighlight(true))
                    .as("named for the field it would have highlighted, and still assigning nothing")
                    .isEqualTo(FieldAttributeSetter.FieldHighlight.none(
                            ScreenField.TRNIDINO.baseName(), TransactionAddController.MAP_NAME));

            ProgramState paintedOnFirstEntry =
                    controllerOver(unusedRepository()).mainPara(firstEntry());
            assertThat(paintedOnFirstEntry.response().getTrnidino())
                    .as("no asterisk was moved onto the empty lookup field")
                    .doesNotContain(FieldAttributeSetter.ASTERISK);
            assertThat(paintedOnFirstEntry.lookupFieldHighlight().untouched()).isTrue();
            assertThat(TransactionAddController.isErrorColoured(paintedOnFirstEntry.response()))
                    .isFalse();
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
                assertThat(json).contains("\"" + base + "\"");
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

        @Test
        @DisplayName("all 21 REDEFINES pairs round-trip through both views of one backing span")
        void everyRedefinedPairRoundTripsThroughBothViews() throws Exception {
            ProgramState state = new ProgramState(
                    new com.vsergeychik.carddemo.common.FixedWidthCodec(StandardCharsets.US_ASCII));
            TransactionAddRequest inbound = new TransactionAddRequest();

            ScreenField[] fields = ScreenField.values();
            List<String> written = new ArrayList<>();
            for (int index = 0; index < fields.length; index++) {
                char marker = (char) ('a' + index);
                String value = String.valueOf(marker).repeat(fields[index].payloadLength());
                written.add(value);
                inputSetterFor(fields[index]).invoke(inbound, value);
            }

            controllerOver(unusedRepository()).receiveTrnviewScreen(state, inbound);

            for (int index = 0; index < fields.length; index++) {
                ScreenField field = fields[index];
                assertThat(state.response().payload(field))
                        .as("%s written through %s must read back through %s",
                                field.baseName(), field.inputItemName(), field.outputItemName())
                        .isEqualTo(written.get(index));
                assertThat(outputGetterFor(field).invoke(state.response()))
                        .as("the generated accessor pair addresses the same bytes")
                        .isEqualTo(written.get(index));
                assertThat(TransactionAddRequest.declaredLengthOf(field.inputItemName()))
                        .as("%s and %s are one span, so their widths cannot differ",
                                field.inputItemName(), field.outputItemName())
                        .isEqualTo(field.payloadLength());
                assertThat(field.payloadSpan().offset())
                        .as("both views start at the same offset in the symbolic map")
                        .isEqualTo(field.payloadOffset());
            }
        }

        @Test
        @DisplayName("CDEMO-CT01-NEXT-PAGE-FLG travels in both of its 88-level states (gate G50)")
        void theNextPageFlagTravelsInBothStates() {
            for (boolean nextPage : new boolean[] {true, false}) {
                TransactionAddRequest request = firstEntry();
                Ct01Info arriving = new Ct01Info();
                if (nextPage) {
                    arriving.setNextPageYes();
                } else {
                    arriving.setNextPageNo();
                }
                request.setCt01Info(arriving);

                Ct01Info echoed = controllerOver(unusedRepository())
                        .mainPara(request).response().getCt01Info();

                assertThat(echoed.isNextPageYes())
                        .as("88 NEXT-PAGE-YES VALUE 'Y' - COTRN01C.cbl:58")
                        .isEqualTo(nextPage);
                assertThat(echoed.isNextPageNo())
                        .as("88 NEXT-PAGE-NO VALUE 'N' - COTRN01C.cbl:59")
                        .isEqualTo(!nextPage);
                assertThat(echoed.getNextPageFlg())
                        .isEqualTo(nextPage ? Ct01Info.NEXT_PAGE_YES : Ct01Info.NEXT_PAGE_NO)
                        .hasSize(Ct01Info.NEXT_PAGE_FLG_LENGTH);
            }

            Ct01Info neither = new Ct01Info();
            neither.setNextPageFlg("?");
            assertThat(neither.isNextPageYes()).isFalse();
            assertThat(neither.isNextPageNo()).isFalse();
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
            assertThat(insideAUnitOfWork).containsExactly(true);
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
                    .getMethod("viewTransaction", String.class, TransactionAddRequest.class,
                            Integer.class, Integer.class);
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
            request.setTrnidin(KNOWN_TRAN_ID);

            mockMvc.perform(get("/api/transactions/{tranId}", KNOWN_TRAN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnid").value(KNOWN_TRAN_ID))
                    .andExpect(jsonPath("$.trnamt").value("+00000504.77"))
                    .andExpect(jsonPath("$.nextProgram").value("COTRN01C"))
                    .andExpect(jsonPath("$.pgmname").value("COTRN01C"))
                    .andExpect(jsonPath("$.screenMetadata.cursorField").value("TRNIDIN"))
                    .andExpect(jsonPath("$.screenMetadata.fields.ERRMSG.colour").exists())
                    .andExpect(jsonPath("$.cursorField").doesNotExist());
        }

        @Test
        @DisplayName("over HTTP, an extension naming another transaction cannot make the URI read it")
        void theUriIsTheOnlyIdentityOverHttp() throws Exception {
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
                    .andExpect(jsonPath("$.trnidin").value(KNOWN_TRAN_ID));

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

        @ParameterizedTest(name = "EIBAID 0x{0} transfers to {1} without a redirect")
        @CsvSource({"F3, COMEN01C", "F5, COTRN00C"})
        @DisplayName("gate G40 - XCTL at :206 is a nextProgram field, never a redirect or a forward")
        void everyTransferIsAResponseFieldAndNeverARedirect(String hex, String target)
                throws Exception {
            byte eibAid = (byte) Integer.parseInt(hex, 16);
            ObjectMapper mapper = new ObjectMapper();
            MockMvc mockMvc = MockMvcBuilders
                    .standaloneSetup(controllerOver(unusedRepository()))
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                    .build();

            org.springframework.mock.web.MockHttpServletResponse answered = mockMvc
                    .perform(get("/api/transactions/{tranId}", KNOWN_TRAN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(reentry(eibAid))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value(target))
                    .andReturn()
                    .getResponse();

            assertThat(answered.getStatus()).isEqualTo(200);
            assertThat(answered.getHeader("Location")).isNull();
            assertThat(answered.getForwardedUrl()).isNull();
            assertThat(answered.getRedirectedUrl()).isNull();
        }

        @Test
        @DisplayName("gate G40 - a screen-sending arm names this mapset and map for the client to paint")
        void aSendingArmNamesTheMapsetAndMap() throws Exception {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));
            ObjectMapper mapper = new ObjectMapper();
            MockMvc mockMvc = MockMvcBuilders
                    .standaloneSetup(controllerOver(
                            repositoryReturning(ReadResult.found(DD_NAME, record))))
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                    .build();

            mockMvc.perform(get("/api/transactions/{tranId}", KNOWN_TRAN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(firstEntry())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value("COTRN01C"))
                    .andExpect(jsonPath("$.nextMapset").value("COTRN01"))
                    .andExpect(jsonPath("$.nextMap").value("COTRN1A"));
        }

        @Test
        @DisplayName("gate G37 - no HttpSession is ever created, on any arm")
        void noHttpSessionIsEverCreated() throws Exception {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));
            ObjectMapper mapper = new ObjectMapper();
            MockMvc mockMvc = MockMvcBuilders
                    .standaloneSetup(controllerOver(
                            repositoryReturning(ReadResult.found(DD_NAME, record))))
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                    .build();

            List<TransactionAddRequest> arms = new ArrayList<>();
            arms.add(firstEntry());
            arms.add(reentry(CicsAid.DFHENTER));
            arms.add(reentry(CicsAid.DFHPF3));
            arms.add(reentry(CicsAid.DFHPF4));
            arms.add(reentry(CicsAid.DFHPF5));
            arms.add(reentry(CicsAid.DFHPF12));

            for (TransactionAddRequest arm : arms) {
                var result = mockMvc.perform(get("/api/transactions/{tranId}", KNOWN_TRAN_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(arm)))
                        .andExpect(status().isOk())
                        .andReturn();

                assertThat(result.getRequest().getSession(false))
                        .as("the COMMAREA, the AID and the 58-byte cursor travel in the payload; a "
                                + "session would be a second, invisible copy of the conversation")
                        .isNull();
                assertThat(result.getResponse().getCookies())
                        .as("no session cookie is issued either")
                        .isEmpty();
            }

            var coldStart = mockMvc.perform(get("/api/transactions/{tranId}", KNOWN_TRAN_ID))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(coldStart.getRequest().getSession(false)).isNull();
        }

        @Test
        @DisplayName("gate G37 - two identical requests produce byte-identical responses")
        void twoIdenticalRequestsProduceIdenticalResponses() throws Exception {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));
            TransactionAddController controller = controllerOver(
                    repositoryReturning(ReadResult.found(DD_NAME, record)));
            ObjectMapper mapper = new ObjectMapper();

            TransactionAddRequest first = reentry(CicsAid.DFHENTER);
            first.setTrnidin(KNOWN_TRAN_ID);
            TransactionAddRequest second = reentry(CicsAid.DFHENTER);
            second.setTrnidin(KNOWN_TRAN_ID);
            assertThat(second).isEqualTo(first);

            String firstScreen = mapper.writeValueAsString(controller.mainPara(first).response());
            String secondScreen = mapper.writeValueAsString(controller.mainPara(second).response());

            assertThat(secondScreen).isEqualTo(firstScreen);
            assertThat(firstScreen)
                    .as("the pinned header is in there, so this is not vacuously true")
                    .contains(EXPECTED_CURDATE)
                    .contains(EXPECTED_CURTIME);
        }

        @Test
        @DisplayName("an over-width path id is answered 400 by WebConfig's handler, echoing no value")
        void anOverWidePathIdIsAnsweredBadRequest() throws Exception {
            String tooWide = "9".repeat(TransactionAddRequest.TRNIDIN_LENGTH + 1);
            MockMvc mockMvc = MockMvcBuilders
                    .standaloneSetup(controllerOver(unusedRepository()))
                    .setControllerAdvice(new WebConfig.CobolErrorHandler())
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                    .build();

            String body = mockMvc.perform(get("/api/transactions/{tranId}", tooWide))
                    .andExpect(status().isBadRequest())
                    .andReturn()
                    .getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);

            assertThat(body)
                    .as("the offending value is never echoed - practice B8's 'say what, not which'")
                    .doesNotContain(tooWide);
        }

        @Test
        @DisplayName("gate G44 - no DDL, no entity mapping and no version column anywhere in the graph")
        void noSchemaArtefactExistsInTheTranslation() throws IOException {
            List<String> graph = List.of(
                    "app/java/src/main/java/com/vsergeychik/carddemo/transaction/"
                            + "TransactionAddController.java",
                    "app/java/src/main/java/com/vsergeychik/carddemo/transaction/"
                            + "TransactionRepository.java",
                    "app/java/src/main/java/com/vsergeychik/carddemo/transaction/model/"
                            + "TranRecord.java",
                    "app/java/src/main/java/com/vsergeychik/carddemo/transaction/dto/"
                            + "TransactionAddRequest.java",
                    "app/java/src/main/java/com/vsergeychik/carddemo/transaction/dto/"
                            + "TransactionAddResponse.java");

            for (String relativePath : graph) {
                String source = Files.readString(repositoryFile(relativePath), StandardCharsets.UTF_8);
                assertThat(source)
                        .as("%s must declare no JPA entity mapping", relativePath)
                        .doesNotContain("jakarta.persistence")
                        .doesNotContain("javax.persistence")
                        .doesNotContain("@Entity")
                        .doesNotContain("@Table")
                        .doesNotContain("@Column")
                        .doesNotContain("@Version")
                        .doesNotContain("org.hibernate");
                assertThat(source.toUpperCase(java.util.Locale.ROOT))
                        .as("%s must emit no DDL: there is no schema to create or alter", relativePath)
                        .doesNotContain("CREATE TABLE")
                        .doesNotContain("ALTER TABLE")
                        .doesNotContain("DROP TABLE")
                        .doesNotContain("CREATE INDEX");
            }
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

    @Nested
    @DisplayName("bind - the URI names the transaction, in every carrier of that identity")
    class RouteBinding {
        private final TransactionAddController controller = controllerOver(unusedRepository());

        @Test
        @DisplayName("on a first entry the path variable is the identity, and it fills TRNIDIN")
        void thePathVariableIsTheIdentity() {
            TransactionAddRequest bound = controller.bind(KNOWN_TRAN_ID, firstEntry());

            assertThat(bound.getTrnidin()).isEqualTo(KNOWN_TRAN_ID)
                    .hasSize(TransactionAddRequest.TRNIDIN_LENGTH);
        }

        @Test
        @DisplayName("on a re-entry the URI is still the identity: :110-111 receives the map and :147 "
                + "and :217-224 read TRNIDIN, so TRNIDIN carries the key the URI names")
        void aReentryKeepsTheTypedIdentity() {
            TransactionAddRequest agreeing = reentry(CicsAid.DFHENTER);
            agreeing.setTrnidin(KNOWN_TRAN_ID);

            TransactionAddRequest bound = controller.bind(KNOWN_TRAN_ID, agreeing);

            assertThat(bound.getTrnidin()).isEqualTo(KNOWN_TRAN_ID);
            assertThat(bound.getCt01Info().getTrnSelected())
                    .as("both carriers of the identity are bound from the URI")
                    .isEqualTo(KNOWN_TRAN_ID);
        }

        @Test
        @DisplayName("it fills CDEMO-CT01-TRN-SELECTED too, which is what first entry reads")
        void thePathVariableAlsoFillsTheExtension() {
            TransactionAddRequest bound = controller.bind(KNOWN_TRAN_ID, firstEntry());

            assertThat(bound.getCt01Info().getTrnSelected()).isEqualTo(KNOWN_TRAN_ID)
                    .hasSize(TransactionAddRequest.Ct01Info.TRN_SELECTED_LENGTH);
        }

        @Test
        @DisplayName("a shorter path value is padded to the declared width, as a PIC X MOVE pads")
        void aShorterPathValueIsPadded() {
            TransactionAddRequest bound = controller.bind("1", firstEntry());

            assertThat(bound.getTrnidin())
                    .isEqualTo("1" + " ".repeat(TransactionAddRequest.TRNIDIN_LENGTH - 1));
        }

        @Test
        @DisplayName("an over-width path value is refused, never truncated onto another transaction")
        void anOverWidePathValueIsRefused() {
            String tooWide = "0".repeat(TransactionAddRequest.TRNIDIN_LENGTH + 1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> controller.bind(tooWide, firstEntry()))
                    .withMessageContaining("refused rather than truncated");
        }

        @Test
        @DisplayName("a body whose TRNIDIN names a different transaction is REFUSED before the "
                + "locking read, because the URI is the transaction this resource reads")
        void aDisagreeingScreenFieldIsRefused() {
            TransactionAddRequest stating = reentry(CicsAid.DFHENTER);
            stating.setTrnidin("0000000000000099");

            ScreenInputRejectedException refusal = catchThrowableOfType(
                    ScreenInputRejectedException.class, () -> controller.bind(KNOWN_TRAN_ID, stating));

            assertThat(refusal).isNotNull();
            assertThat(refusal.member()).contains("trnidin");
            assertThat(refusal.reason())
                    .isEqualTo(ScreenInputRejectedException.Reason.CONFLICTING_KEY);
            assertThat(refusal.getMessage()).as("neither identifier is ever echoed")
                    .doesNotContain("0000000000000099");
            assertThat(refusal.publicDetail()).doesNotContain("0000000000000099");
        }

        @Test
        @DisplayName("the extension is PROJECTED rather than judged, on every turn - its writer is the "
                + "list program, so a value there is not a second statement of the operator's key")
        void theExtensionIsStillProjected() {
            TransactionAddRequest arriving = firstEntry();
            arriving.getCt01Info().setTrnSelected("0000000000000098");

            TransactionAddRequest bound = controller.bind(KNOWN_TRAN_ID, arriving);

            assertThat(bound.getTrnidin()).isEqualTo(KNOWN_TRAN_ID);
            assertThat(bound.getCt01Info().getTrnSelected()).isEqualTo(KNOWN_TRAN_ID);

            TransactionAddRequest onReentry = reentry(CicsAid.DFHENTER);
            onReentry.setTrnidin(KNOWN_TRAN_ID);
            onReentry.getCt01Info().setTrnSelected("0000000000000098");

            assertThat(controller.bind(KNOWN_TRAN_ID, onReentry).getCt01Info().getTrnSelected())
                    .isEqualTo(KNOWN_TRAN_ID);
        }

        @ParameterizedTest(name = "a re-entry stating TRNIDIN as \"{0}\" agrees with the URI")
        @ValueSource(strings = {"", "   ", "0000000000000001", "0000000000000001    "})
        @DisplayName("the images that AGREE with the URI are accepted - absent, blank, the URI's key, "
                + "and the URI's key space-padded - and the field then carries the URI's key")
        void theStatesThatAgreeAreAccepted(String stated) {
            TransactionAddRequest stating = reentry(CicsAid.DFHENTER);
            stating.setTrnidin(stated);

            assertThat(controller.bind(KNOWN_TRAN_ID, stating).getTrnidin()).isEqualTo(KNOWN_TRAN_ID);
        }

        @Test
        @DisplayName("a LOW-VALUES key field agrees with any URI: that is what an unmodified BMS field "
                + "carries, so it states no key at all")
        void aLowValuesKeyFieldAgrees() {
            String lowValues = "\u0000".repeat(TransactionAddRequest.TRNIDIN_LENGTH);
            TransactionAddRequest stating = reentry(CicsAid.DFHENTER);
            stating.setTrnidin(lowValues);

            assertThat(controller.bind(KNOWN_TRAN_ID, stating).getTrnidin()).isEqualTo(KNOWN_TRAN_ID);

            assertThat(controller.bind(KNOWN_TRAN_ID, firstEntry()).getTrnidin())
                    .as("a first entry is bound from the URI in exactly the same way")
                    .isEqualTo(KNOWN_TRAN_ID);
        }

        @Test
        @DisplayName("an extension naming another transaction cannot make first entry read it")
        void aDisagreeingExtensionCannotBeRead() {
            TransactionRepository repository = repositoryReturning(ReadResult.notFound(DD_NAME));
            TransactionAddRequest arriving = firstEntry();
            arriving.getCt01Info().setTrnSelected("0000000000000099");

            controllerOver(repository).viewTransaction(KNOWN_TRAN_ID, arriving, null, null);

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

            controllerOver(repository).viewTransaction(KNOWN_TRAN_ID, arriving, null, null);

            verify(repository).readForUpdateByTranId(KNOWN_TRAN_ID);
        }

        @Test
        @DisplayName("a body that agrees, space-padded or not, is accepted, and the PIC X MOVE pads")
        void anAgreeingBodyIsAccepted() {
            TransactionAddRequest padded = reentry(CicsAid.DFHENTER);
            padded.setTrnidin(KNOWN_TRAN_ID);
            assertThat(controller.bind(KNOWN_TRAN_ID, padded).getTrnidin()).isEqualTo(KNOWN_TRAN_ID);

            String padTo16 = "1" + " ".repeat(TransactionAddRequest.TRNIDIN_LENGTH - 1);
            TransactionAddRequest shortForm = reentry(CicsAid.DFHENTER);
            shortForm.setTrnidin("1");
            assertThat(controller.bind("1", shortForm).getTrnidin()).isEqualTo(padTo16);

            assertThat(controller.bind("1", firstEntry()).getTrnidin()).isEqualTo(padTo16);
        }

        @Test
        @DisplayName("a blank or LOW-VALUES field states nothing, on a first entry and on a re-entry "
                + "alike, so the path is what fills it on both")
        void aBlankBodyFieldStatesNothing() {
            String spaces = " ".repeat(TransactionAddRequest.TRNIDIN_LENGTH);

            TransactionAddRequest coldSpaces = firstEntry();
            coldSpaces.setTrnidin(spaces);
            assertThat(controller.bind(KNOWN_TRAN_ID, coldSpaces).getTrnidin())
                    .isEqualTo(KNOWN_TRAN_ID);

            TransactionAddRequest warmSpaces = reentry(CicsAid.DFHENTER);
            warmSpaces.setTrnidin(spaces);
            assertThat(controller.bind(KNOWN_TRAN_ID, warmSpaces).getTrnidin())
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
                    .isThrownBy(() -> controller.viewTransaction(null, reentry(CicsAid.DFHENTER), null, null))
                    .withMessageContaining("transaction id");
        }

        @Test
        @DisplayName("the metadata reports the cursor request, the quads and the message colour")
        void theMetadataTravelsBesideTheScreen() {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));
            ScreenResponse<TransactionAddResponse> answer =
                    controllerOver(repositoryReturning(ReadResult.found(DD_NAME, record)))
                            .viewTransaction(KNOWN_TRAN_ID, reentry(CicsAid.DFHENTER), null, null);

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

            MockMvc both = MockMvcBuilders
                    .standaloneSetup(controllerOver(shared), list)
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                    .build();

            both.perform(get("/api/transactions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pagenum").exists())
                    .andExpect(jsonPath("$.trnamt").doesNotExist());
            both.perform(get("/api/transactions/{tranId}", KNOWN_TRAN_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnamt").exists())
                    .andExpect(jsonPath("$.pagenum").doesNotExist());
        }

        @Test
        @DisplayName("a transferring arm places no cursor, and the metadata names no field")
        void aTransferPlacesNoCursor() {
            ScreenResponse<TransactionAddResponse> answer = controllerOver(unusedRepository())
                    .viewTransaction(KNOWN_TRAN_ID, null, null, null);

            assertThat(answer.screen().getNextProgram().strip())
                    .isEqualTo(TransactionAddController.SIGN_ON_PROGRAM);
            assertThat(answer.screenMetadata().cursorField()).isNull();
        }
    }
}
