package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.TransactionAddController;
import com.vsergeychik.carddemo.transaction.TransactionAddController.ProgramState;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.TransactionRepository.ReadResult;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest.Ct01Info;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddResponse;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddResponse.ScreenField;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * The parity gate for {@code app/cbl/COTRN01C.cbl} - twenty declarative cases, judged field by field, with
 * a required diff count of zero.
 */
@DisplayName("COTRN01C parity - transaction CT01, a keyed READ, and the class name says Add")
final class COTRN01CParityTest {
    private static final String PROGRAM = "COTRN01C";

    private static final ParityCase.UnitKind UNIT_KIND = ParityCase.UnitKind.CONTROLLER_POJO;

    private static final String TRANSACT_DATASET = TransactionRepository.CICS_FILE_NAME;

    private static final String CURSOR_LENGTH_ITEM =
            ScreenField.TRNIDINO.baseName() + "L";

    private static final String UNEXPECTED_STATUS = FileStatus.RECORD_LENGTH_CONFLICT;

    static List<ParityCase> cases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);

        assertThat(loaded)
                .as("the gate is stated as twenty declarative cases per program, and "
                        + "'diff count is zero across all twenty' is satisfied vacuously by a shorter "
                        + "set - so the count is asserted before a single case runs")
                .hasSize(ParityHarness.CASES_PER_PROGRAM);

        List<String> ids = new ArrayList<>(loaded.size());
        for (ParityCase parityCase : loaded) {
            ids.add(parityCase.caseId());
            assertThat(parityCase.program())
                    .as("every case in parity/%s/ must name that program, or it is judging a "
                            + "different one", PROGRAM)
                    .isEqualTo(PROGRAM);
            assertThat(parityCase.unitKind())
                    .as("case %s declares unitKind %s; COTRN01C is an online program reached as a "
                            + "plain controller object, so every one of its cases is %s",
                            parityCase.caseId(), parityCase.unitKind(), UNIT_KIND)
                    .isEqualTo(UNIT_KIND);
        }

        List<String> expectedIds = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            expectedIds.add(ParityHarness.caseId(ordinal));
        }
        assertThat(ids)
                .as("the twenty cases must be case01 through case20 in order, so that a "
                        + "mis-numbered fixture cannot silently replace another")
                .containsExactlyElementsOf(expectedIds);

        return loaded;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("field-for-field identical to app/cbl/COTRN01C.cbl")
    void isFieldForFieldIdenticalToTheCobol(ParityCase parityCase) {
        FieldDiffer.DiffResult diff =
                ParityHarness.usAscii().judge(parityCase, UNIT_KIND, COTRN01CParityTest::execute);

        assertThat(diff.count())
                .as("%s/%s must produce a diff count of zero. A module is not complete until the "
                        + "count is zero across all twenty of its cases, so a single difference here "
                        + "is a failed gate rather than a tolerance.%n%s",
                        parityCase.program(), parityCase.caseId(), diff.render())
                .isZero();
        assertThat(diff.isClean())
                .as("the differ reported a clean result and a non-zero count, or the reverse - the "
                        + "two must agree.%n%s", diff.render())
                .isTrue();
    }

    private static ParityHarness.UnitOutcome execute(ParityHarness.Invocation invocation) {
        SingleConnectionDataSource dataSource = taskBoundaryDataSource(invocation);
        try {
            TransactionRepository repository = repositoryFor(invocation);
            TransactionAddController controller = new TransactionAddController(
                    repository,
                    invocation.clock(),
                    new DatasetUnitOfWork(new JdbcTransactionManager(dataSource)),
                    invocation.charset());

            ProgramState state = controller.mainPara(requestOf(invocation));

            requireNothingWasWritten(repository);
            return fingerprintOf(invocation, state);
        } finally {
            dataSource.destroy();
        }
    }

    private static SingleConnectionDataSource taskBoundaryDataSource(
            ParityHarness.Invocation invocation) {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:parity-" + invocation.program() + '-' + invocation.caseId()
                        + ";DB_CLOSE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE",
                "sa", "", true);
        dataSource.setSuppressClose(true);
        return dataSource;
    }

    private static TransactionRepository repositoryFor(ParityHarness.Invocation invocation) {
        TransactionRepository repository = mock(TransactionRepository.class);

        List<String> rows = invocation.hasDataset(TRANSACT_DATASET)
                ? invocation.dataset(TRANSACT_DATASET).rows()
                : List.of();
        Charset charset = invocation.charset();

        if (invocation.hasForcedOutcome(ParityCase.RepositoryOperation.READ_FOR_UPDATE)) {
            ParityCase.ForcedOutcome forced =
                    invocation.forcedOutcome(ParityCase.RepositoryOperation.READ_FOR_UPDATE);
            when(repository.readForUpdateByTranId(anyString()))
                    .thenAnswer(answer -> forcedReadResult(
                            forced, answer.getArgument(0, String.class), rows, charset));
            return repository;
        }

        when(repository.readForUpdateByTranId(anyString()))
                .thenAnswer(answer -> keyedRead(answer.getArgument(0, String.class), rows, charset));
        return repository;
    }

    private static ReadResult keyedRead(String key, List<String> rows, Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        String wanted = codec.movePicX(key == null ? "" : key, TranRecord.TRAN_ID_LENGTH);
        for (String row : rows) {
            TranRecord record = TranRecord.decode(row, charset);
            if (record.rawSpan(TranRecord.TRAN_ID).equals(wanted)) {
                return ReadResult.found(TransactionRepository.INPUT_DD_NAME, record);
            }
        }
        return ReadResult.notFound(TransactionRepository.INPUT_DD_NAME);
    }

    private static ReadResult forcedReadResult(ParityCase.ForcedOutcome forced, String key,
                                               List<String> seededRows, Charset charset) {
        return switch (forced.outcome()) {
            case NOT_FOUND -> ReadResult.notFound(TransactionRepository.INPUT_DD_NAME);
            case END_OF_FILE -> ReadResult.endOfFile(TransactionRepository.INPUT_DD_NAME);
            case OTHER -> forcedUnexpectedCondition(forced);
            case DUPLICATE -> forcedDuplicate(forced, key, seededRows, charset);
            case OK -> throw new IllegalArgumentException("A COTRN01C case forced the "
                    + forced.outcome() + " outcome for the keyed read. That outcome carries a record, "
                    + "and forcing it would require inventing 350 bytes no copybook, fixture or case "
                    + "declared - so every field of the resulting screen would be compared against an "
                    + "expectation derived from nothing. Seed a TRANSACT row instead: the "
                    + "fixture-backed read then reports " + FileStatus.Outcome.OK + " for a key that "
                    + "matches and " + FileStatus.Outcome.NOT_FOUND + " for one that does not.");
        };
    }

    private static ReadResult forcedDuplicate(ParityCase.ForcedOutcome forced, String key,
                                              List<String> seededRows, Charset charset) {
        TranRecord record = keyedRead(key, seededRows, charset).record()
                .orElseThrow(() -> new IllegalArgumentException("A COTRN01C case forced the "
                        + FileStatus.Outcome.DUPLICATE + " outcome for the keyed read of '" + key
                        + "', but no seeded TRANSACT row carries that key. A duplicate is reported "
                        + "alongside a record, so the record has to come from somewhere; taking it from "
                        + "the case's own seeded data is what keeps it from being invented. Seed a row "
                        + "whose TRAN-ID is the key the case asks for."));

        int resp = forced.resp() == null ? FileStatus.DUPKEY : forced.resp();
        int resp2 = forced.resp2() == null ? FileStatus.NO_REASON_CODE : forced.resp2();
        if (FileStatus.outcomeOfCicsResp(resp) != FileStatus.Outcome.DUPLICATE) {
            throw new IllegalArgumentException("A COTRN01C case forced the "
                    + FileStatus.Outcome.DUPLICATE + " outcome but declared RESP " + resp
                    + ", which common.FileStatus classifies as "
                    + FileStatus.outcomeOfCicsResp(resp) + ". The two must agree, or the DISPLAY at "
                    + ":290 would render a response code that contradicts the arm the case says it "
                    + "pins. The duplicate conditions are DFHRESP(DUPREC) = " + FileStatus.DUPREC
                    + " and DFHRESP(DUPKEY) = " + FileStatus.DUPKEY + ".");
        }
        return new ReadResult(TransactionRepository.INPUT_DD_NAME, FileStatus.DUPLICATE,
                FileStatus.Outcome.DUPLICATE, Optional.of(record),
                CicsResponse.reported(resp, resp2), Optional.empty(), Optional.empty());
    }

    private static ReadResult forcedUnexpectedCondition(ParityCase.ForcedOutcome forced) {
        int resp = forced.resp() == null ? FileStatus.NOTOPEN : forced.resp();
        int resp2 = forced.resp2() == null ? FileStatus.NO_REASON_CODE : forced.resp2();
        if (FileStatus.outcomeOfCicsResp(resp) != FileStatus.Outcome.OTHER) {
            throw new IllegalArgumentException("A COTRN01C case forced the "
                    + FileStatus.Outcome.OTHER + " outcome for the keyed read but declared RESP " + resp
                    + ", which common.FileStatus classifies as "
                    + FileStatus.outcomeOfCicsResp(resp) + ". The two must agree, or the DISPLAY at "
                    + ":290 would render a response code that contradicts the arm the case says it "
                    + "pins. EVALUATE WS-RESP-CD (:280) names DFHRESP(NORMAL) = " + FileStatus.NORMAL
                    + " at :281 and DFHRESP(NOTFND) = " + FileStatus.NOTFND + " at :283, so neither "
                    + "reaches WHEN OTHER at :289; end-of-file and duplicate have their own forced "
                    + "outcomes. Declare a residual condition instead - DFHRESP(NOTOPEN) = "
                    + FileStatus.NOTOPEN + " is the default, and DFHRESP(INVREQ) = " + FileStatus.INVREQ
                    + " is another.");
        }
        return ReadResult.other(TransactionRepository.INPUT_DD_NAME, UNEXPECTED_STATUS,
                CicsResponse.reported(resp, resp2));
    }

    private static void requireNothingWasWritten(TransactionRepository repository) {
        verify(repository, never()).write(any());
        verify(repository, never()).openOutput();
        verify(repository, never()).readByTranId(anyString());
    }

    private static TransactionAddRequest requestOf(ParityHarness.Invocation invocation) {
        TransactionAddRequest request = new TransactionAddRequest();
        FixedWidthCodec codec = new FixedWidthCodec(invocation.charset());

        if (invocation.eibcalen() > 0) {
            request.setNavigationContext(NavigationContext.fromFixedWidth(codec,
                    codec.serialise(NavigationContext.LAYOUT,
                            subsetOf(invocation.commarea(), baseCommareaFieldNames()))));
            request.setCt01Info(Ct01Info.fromFixedWidth(codec,
                    codec.serialise(Ct01Info.LAYOUT,
                            subsetOf(invocation.commarea(), extensionFieldNames()))));
        }

        String aid = rawAidOf(invocation.aid());
        if (aid != null) {
            request.setAid(aid);
        }

        for (Map.Entry<String, String> field : invocation.mapFields().entrySet()) {
            applyReceivedField(request, field.getKey(), field.getValue());
        }
        return request;
    }

    private static Map<String, String> subsetOf(Map<String, String> values, List<String> names) {
        Map<String, String> owned = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (names.contains(entry.getKey())) {
                owned.put(entry.getKey(), entry.getValue());
            }
        }
        return owned;
    }

    private static List<String> baseCommareaFieldNames() {
        List<String> names = new ArrayList<>();
        for (var span : NavigationContext.LAYOUT.spans()) {
            names.add(span.name());
        }
        return names;
    }

    private static List<String> extensionFieldNames() {
        List<String> names = new ArrayList<>();
        for (var span : Ct01Info.LAYOUT.spans()) {
            names.add(span.name());
        }
        return names;
    }

    private static String rawAidOf(String mnemonic) {
        if (mnemonic == null) {
            return null;
        }
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return String.valueOf((char) (entry.getKey() & 0xFF));
            }
        }
        throw new IllegalArgumentException('"' + mnemonic + "\" is not a DFHAID mnemonic that "
                + CicsAid.class.getName() + " reproduces, yet ParityCase accepted it. The two read "
                + "the same map, so this means they have drifted apart.");
    }

    private static void applyReceivedField(TransactionAddRequest request, String name, String value) {
        switch (name) {
            case TransactionAddRequest.TRNNAME_FIELD -> request.setTrnname(value);
            case TransactionAddRequest.TITLE01_FIELD -> request.setTitle01(value);
            case TransactionAddRequest.CURDATE_FIELD -> request.setCurdate(value);
            case TransactionAddRequest.PGMNAME_FIELD -> request.setPgmname(value);
            case TransactionAddRequest.TITLE02_FIELD -> request.setTitle02(value);
            case TransactionAddRequest.CURTIME_FIELD -> request.setCurtime(value);
            case TransactionAddRequest.TRNIDIN_FIELD -> request.setTrnidin(value);
            case TransactionAddRequest.TRNID_FIELD -> request.setTrnid(value);
            case TransactionAddRequest.CARDNUM_FIELD -> request.setCardnum(value);
            case TransactionAddRequest.TTYPCD_FIELD -> request.setTtypcd(value);
            case TransactionAddRequest.TCATCD_FIELD -> request.setTcatcd(value);
            case TransactionAddRequest.TRNSRC_FIELD -> request.setTrnsrc(value);
            case TransactionAddRequest.TDESC_FIELD -> request.setTdesc(value);
            case TransactionAddRequest.TRNAMT_FIELD -> request.setTrnamt(value);
            case TransactionAddRequest.TORIGDT_FIELD -> request.setTorigdt(value);
            case TransactionAddRequest.TPROCDT_FIELD -> request.setTprocdt(value);
            case TransactionAddRequest.MID_FIELD -> request.setMid(value);
            case TransactionAddRequest.MNAME_FIELD -> request.setMname(value);
            case TransactionAddRequest.MCITY_FIELD -> request.setMcity(value);
            case TransactionAddRequest.MZIP_FIELD -> request.setMzip(value);
            case TransactionAddRequest.ERRMSG_FIELD -> request.setErrmsg(value);
            default -> throw new IllegalArgumentException('"' + name + "\" is not one of the "
                    + TransactionAddRequest.PAYLOAD_FIELD_COUNT + " xxxI items "
                    + "app/cpy-bms/COTRN01.CPY declares. The declared set is "
                    + TransactionAddRequest.PAYLOAD_FIELD_NAMES + ". The xxxL, xxxF and xxxA items are "
                    + "length, flag and attribute metadata and are not payload fields, so they are not "
                    + "settable from a case.");
        }
    }

    private static ParityHarness.UnitOutcome fingerprintOf(ParityHarness.Invocation invocation,
                                                           ProgramState state) {
        FixedWidthCodec codec = new FixedWidthCodec(invocation.charset());
        ParityHarness.UnitOutcome.Builder recorder = invocation.recorder();

        recorder.response(observedResponseOf(codec, state));

        if (invocation.hasDataset(TRANSACT_DATASET)) {
            recorder.finalStateUnchanged(invocation.dataset(TRANSACT_DATASET), TranRecord.LAYOUT);
        }

        recorder.message(new ParityCase.EmittedMessage(
                ParityCase.MessageChannel.WS_MESSAGE_80, state.message()));
        recorder.message(new ParityCase.EmittedMessage(
                ParityCase.MessageChannel.SCREEN_ERRMSG_78, state.response().getErrmsgo()));
        for (String line : state.displays()) {
            recorder.display(line);
        }

        recorder.returnCode(0);
        return recorder.build();
    }

    private static FieldDiffer.ObservedResponse observedResponseOf(FixedWidthCodec codec,
                                                                  ProgramState state) {
        TransactionAddResponse response = state.response();

        Map<String, String> navigation = new LinkedHashMap<>();
        navigation.putAll(codec.deserialise(NavigationContext.LAYOUT,
                response.getNavigationContext().toFixedWidth(codec)));
        navigation.putAll(codec.deserialise(Ct01Info.LAYOUT,
                response.getCt01Info().toFixedWidth(codec)));

        Map<String, String> painted = new LinkedHashMap<>();
        for (Map.Entry<ScreenField, String> item : response.payloadItems().entrySet()) {
            painted.put(item.getKey().outputItemName(), item.getValue());
        }

        List<FieldDiffer.ObservedSend> sends = new ArrayList<>(state.screensSent());
        for (int send = 0; send < state.screensSent(); send++) {
            sends.add(FieldDiffer.ObservedSend.ofFields(painted));
        }

        return new FieldDiffer.ObservedResponse(
                response.getNextProgram(),
                namedOrNone(response.getNextMapset()),
                namedOrNone(response.getNextMap()),
                navigation,
                sends,
                cursorLengthItemOf(state),
                terminationOf(state));
    }

    private static String namedOrNone(String reference) {
        return reference == null || reference.isBlank() ? null : reference;
    }

    private static String cursorLengthItemOf(ProgramState state) {
        return state.cursorRequested() ? state.cursorField().baseName() + "L" : null;
    }

    private static ParityCase.Termination terminationOf(ProgramState state) {
        if (state.transferred() == state.returned()) {
            throw new IllegalStateException("The run reports transferred=" + state.transferred()
                    + " and returned=" + state.returned() + ". Exactly one must hold: every arm of "
                    + "app/cbl/COTRN01C.cbl:86-139 ends either at the XCTL of :205-208 or at the "
                    + "EXEC CICS RETURN of :136-139, and an XCTL never reaches that RETURN.");
        }
        return state.transferred() ? ParityCase.Termination.XCTL
                : ParityCase.Termination.RETURN_TRANSID;
    }

    private static final Instant PINNED_INSTANT = Instant.parse("2022-07-19T23:12:34Z");

    private static final String KNOWN_TRAN_ID = "0000000000000001";

    private static <T> T withController(TransactionRepository repository,
                                        Function<TransactionAddController, T> work) {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:parity-direct-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE",
                "sa", "", true);
        dataSource.setSuppressClose(true);
        try {
            return work.apply(new TransactionAddController(
                    repository,
                    Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC),
                    new DatasetUnitOfWork(new JdbcTransactionManager(dataSource)),
                    ParityHarness.FIXTURE_CHARSET));
        } finally {
            dataSource.destroy();
        }
    }

    private static TransactionRepository repositoryReporting(ReadResult outcome) {
        TransactionRepository repository = mock(TransactionRepository.class);
        when(repository.readForUpdateByTranId(anyString())).thenReturn(outcome);
        return repository;
    }

    private static TranRecord tranRecord(String tranId, BigDecimal amount) {
        TranRecord record = new TranRecord(ParityHarness.FIXTURE_CHARSET);
        record.moveTranId(tranId);
        record.moveTranTypeCd("01");
        record.moveTranCatCd(5);
        record.moveTranSource("POS TERM");
        record.moveTranDesc("PARITY DESCRIPTION");
        record.moveTranAmt(amount);
        record.moveTranMerchantId(800000001L);
        record.moveTranMerchantName("PARITY MERCHANT");
        record.moveTranMerchantCity("PARITY CITY");
        record.moveTranMerchantZip("12345-6789");
        record.moveTranCardNum("4111111111111111");
        record.moveTranOrigTs("2022-07-19 23:12:34.123456");
        record.moveTranProcTs("2022-07-20 01:02:03.654321");
        return record;
    }

    private static TransactionAddRequest coldStart() {
        return new TransactionAddRequest();
    }

    private static TransactionAddRequest reentryWith(byte eibAid) {
        TransactionAddRequest request = new TransactionAddRequest();
        request.setNavigationContext(NavigationContext.empty().withPgmReenter());
        request.setAid(String.valueOf((char) (eibAid & 0xFF)));
        return request;
    }

    @Nested
    @DisplayName("Risk R-B - no path writes, because the program views")
    class NoRecordIsEverWritten {
        @Test
        @DisplayName("the source contains no WRITE, REWRITE or DELETE and says so in its header")
        void theCobolSourceNeverWrites() throws IOException {
            String cobol = Files.readString(repositoryFile("app/cbl/COTRN01C.cbl"),
                    StandardCharsets.UTF_8);

            assertThat(cobol)
                    .as("the Function header is the primary evidence for risk R-B and the reason "
                            + "every expectation in this file describes a read")
                    .contains("Function    : View a Transaction from TRANSACT file");
            assertThat(cobol)
                    .as("a WRITE, REWRITE or DELETE anywhere in COTRN01C would mean this whole file "
                            + "is written against the wrong program")
                    .doesNotContain("WRITE")
                    .doesNotContain("DELETE");
            assertThat(cobol)
                    .as("the only file operation is the keyed READ at :269-278, with the UPDATE "
                            + "option at :275")
                    .contains("EXEC CICS READ")
                    .contains("UPDATE");
        }

        @Test
        @DisplayName("README.md's own inventory records CT01 as Transaction View, not Add")
        void theProjectInventoryContradictsThePromptsMapping() throws IOException {
            String readme = Files.readString(repositoryFile("README.md"), StandardCharsets.UTF_8);

            assertThat(readme)
                    .as("README.md:213-231 is the independent corroboration of risk R-B; if this row "
                            + "ever changes, the resolution recorded in this class must be revisited")
                    .contains("| CT01 | COTRN01 | COTRN01C | Transaction View    |");
            assertThat(readme)
                    .as("and the sibling really is the one that adds")
                    .contains("| CT02 | COTRN02 | COTRN02C | Transaction Add     |");
        }

        @Test
        @DisplayName("no arm of the program reaches a mutating repository method")
        void noArmWrites() {
            TransactionRepository repository = repositoryReporting(
                    ReadResult.found(TransactionRepository.INPUT_DD_NAME,
                            tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"))));

            withController(repository, controller -> {
                TransactionAddRequest enter = reentryWith(CicsAid.DFHENTER);
                enter.setTrnidin(KNOWN_TRAN_ID);

                controller.mainPara(enter);
                controller.mainPara(coldStart());
                controller.mainPara(firstEntrySelecting(KNOWN_TRAN_ID));
                controller.mainPara(reentryWith(CicsAid.DFHPF3));
                controller.mainPara(reentryWith(CicsAid.DFHPF4));
                controller.mainPara(reentryWith(CicsAid.DFHPF5));
                controller.mainPara(reentryWith(CicsAid.DFHPF12));
                return null;
            });

            requireNothingWasWritten(repository);
        }

        private TransactionAddRequest firstEntrySelecting(String tranId) {
            TransactionAddRequest request = new TransactionAddRequest();
            request.setNavigationContext(NavigationContext.empty());
            Ct01Info info = new Ct01Info();
            info.setTrnSelected(tranId);
            info.setTrnSelFlg("S");
            request.setCt01Info(info);
            return request;
        }
    }

    @Nested
    @DisplayName("SEND-TRNVIEW-SCREEN is repeatable, which is why every send carries one field map")
    class SendIsIdempotent {
        @Test
        @DisplayName("sending twice under a pinned clock paints byte-identical values")
        void twoSendsAreIdentical() {
            Map<ScreenField, String> after = withController(repositoryReporting(
                    ReadResult.notFound(TransactionRepository.INPUT_DD_NAME)), controller -> {
                        ProgramState state =
                                new ProgramState(new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET));
                        controller.sendTrnviewScreen(state);
                        Map<ScreenField, String> first =
                                new LinkedHashMap<>(state.response().payloadItems());
                        controller.sendTrnviewScreen(state);

                        assertThat(state.response().payloadItems())
                                .as("POPULATE-HEADER-INFO reads a pinned clock and MOVE WS-MESSAGE TO "
                                        + "ERRMSGO copies an unchanged value, so a second send writes "
                                        + "the same bytes. The whole reason observedResponseOf can give "
                                        + "every send one field map rests on this")
                                .isEqualTo(first);
                        assertThat(state.screensSent())
                                .as("both sends are counted: the send count is behaviour, because "
                                        + "SEND-TRNVIEW-SCREEN is not terminal in this program")
                                .isEqualTo(2);
                        return state.response().payloadItems();
                    });

            assertThat(after.get(ScreenField.CURDATEO))
                    .as("MM/DD/YY from the pinned instant - :252-256")
                    .isEqualTo("07/19/22");
            assertThat(after.get(ScreenField.CURTIMEO))
                    .as("HH:MM:SS from the pinned instant - :258-262")
                    .isEqualTo("23:12:34");
        }
    }

    @Nested
    @DisplayName("The widths every case is written against")
    class DeclaredWidths {
        @Test
        @DisplayName("TRAN-RECORD is 350 bytes and its FILLER is present and space-filled")
        void tranRecordIsThreeHundredAndFiftyBytesIncludingFiller() {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));

            assertThat(TranRecord.RECORD_LENGTH)
                    .as("CVTRA05Y declares a 350-byte TRAN-RECORD")
                    .isEqualTo(350);
            assertThat(record.displayImage())
                    .as("a serialised record is exactly its declared width, which is the check that "
                            + "fails immediately if a FILLER span were omitted")
                    .hasSize(TranRecord.RECORD_LENGTH);
            assertThat(TranRecord.sumOfDeclaredSpanLengths())
                    .as("every byte of the record belongs to a declared span; a gap would leave the "
                            + "sum short of the record length")
                    .isEqualTo(TranRecord.RECORD_LENGTH);
            assertThat(record.rawSpan(TranRecord.FILLER))
                    .as("CVTRA05Y's trailing FILLER PIC X(20) is emitted as spaces; omit it and every "
                            + "downstream offset and the total width are wrong")
                    .isEqualTo(" ".repeat(TranRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("the commarea is 160 bytes and the CT01 extension is a separate 58")
        void theExtensionIsNotFoldedIntoTheCommarea() {
            FixedWidthCodec codec = new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET);

            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("COCOM01Y's CARDDEMO-COMMAREA is 160 bytes and is shared by all 17 online "
                            + "programs, so the per-program extension must not be folded into it")
                    .isEqualTo(160);
            assertThat(Ct01Info.RECORD_LENGTH)
                    .as("CDEMO-CT01-INFO is 16 + 16 + 8 + 1 + 1 + 16 = 58 bytes "
                            + "(app/cbl/COTRN01C.cbl:53-61)")
                    .isEqualTo(58);
            assertThat(TransactionAddResponse.PASSED_COMMAREA_LENGTH)
                    .as("the area the XCTL at :207 and the RETURN at :138 pass is the two together")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH + Ct01Info.RECORD_LENGTH)
                    .isEqualTo(218);

            assertThat(NavigationContext.empty().toFixedWidth(codec))
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(new Ct01Info().toFixedWidth(codec))
                    .hasSize(Ct01Info.RECORD_LENGTH);
            assertThat(baseCommareaFieldNames())
                    .as("the sixteen COCOM01Y fields and the six extension fields are disjoint, which "
                            + "is what lets a case declare them in one flat map")
                    .doesNotContainAnyElementsOf(extensionFieldNames());
            assertThat(extensionFieldNames())
                    .containsExactly(Ct01Info.TRNID_FIRST_FIELD, Ct01Info.TRNID_LAST_FIELD,
                            Ct01Info.PAGE_NUM_FIELD, Ct01Info.NEXT_PAGE_FLG_FIELD,
                            Ct01Info.TRN_SEL_FLG_FIELD, Ct01Info.TRN_SELECTED_FIELD);
        }

        @Test
        @DisplayName("WS-MESSAGE is 80 and ERRMSGO is 78, so :217 loses the last two bytes")
        void theMessageIsTruncatedByTwoOnItsWayToTheScreen() {
            FixedWidthCodec codec = new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET);

            assertThat(ParityCase.MessageChannel.WS_MESSAGE_80.fixedWidth()).isEqualTo(80);
            assertThat(ParityCase.MessageChannel.SCREEN_ERRMSG_78.fixedWidth()).isEqualTo(78);
            assertThat(ScreenField.ERRMSGO.payloadLength())
                    .as("the symbolic map declares ERRMSGO PIC X(78)")
                    .isEqualTo(ParityCase.MessageChannel.SCREEN_ERRMSG_78.fixedWidth());

            String eighty = codec.movePicX("X".repeat(80), TransactionAddController.WS_MESSAGE_LENGTH);
            TransactionAddResponse response = new TransactionAddResponse();
            response.setErrmsgo(eighty);

            assertThat(eighty).hasSize(80);
            assertThat(response.getErrmsgo())
                    .as("MOVE PIC X(80) TO PIC X(78) keeps the leading 78 characters and discards the "
                            + "rest on the right")
                    .hasSize(78)
                    .isEqualTo("X".repeat(78));
        }

        @Test
        @DisplayName("the symbolic map projects exactly 21 payload fields")
        void theScreenHasTwentyOnePayloadFields() {
            assertThat(ScreenField.values())
                    .as("app/bms/COTRN01.bms declares 21 DFHMDF fields and app/cpy-bms/COTRN01.CPY "
                            + "declares 21 xxxI items; the payload is those and nothing else")
                    .hasSize(TransactionAddResponse.PAYLOAD_FIELD_COUNT)
                    .hasSize(21);
            assertThat(TransactionAddRequest.PAYLOAD_FIELD_NAMES)
                    .hasSize(TransactionAddResponse.PAYLOAD_FIELD_COUNT);
        }
    }

    @Nested
    @DisplayName("Numeric parity - this program computes nothing, and the one numeric move truncates")
    class NumericParity {
        @Test
        @DisplayName("the source contains no arithmetic verb and no ROUNDED at all")
        void theProgramPerformsNoArithmetic() throws IOException {
            String cobol = Files.readString(repositoryFile("app/cbl/COTRN01C.cbl"),
                    StandardCharsets.UTF_8);

            for (String verb : List.of("ADD", "SUBTRACT", "COMPUTE", "MULTIPLY", "DIVIDE",
                    "ROUNDED")) {
                assertThat(cobol)
                        .as("COTRN01C is 57 MOVEs and no arithmetic whatever. %s appearing here would "
                                + "mean this program acquired a calculation, and every rounding and "
                                + "scale question in the migration would then apply to it", verb)
                        .doesNotContain(verb);
            }
        }

        @Test
        @DisplayName("TRAN-AMT is scale 2 and the edited move discards high-order digits, not low")
        void theEditedMoveTruncatesOnTheLeft() {
            assertThat(TranRecord.TRAN_AMT_SCALE)
                    .as("CVTRA05Y declares TRAN-AMT PIC S9(09)V99, so the scale is fixed at two and "
                            + "there is no fractional excess for a rounding mode to decide")
                    .isEqualTo(2);

            assertThat(TransactionAddController.editedTranAmt(new BigDecimal("504.77")))
                    .isEqualTo("+00000504.77")
                    .hasSize(TransactionAddController.WS_TRAN_AMT_LENGTH);
            assertThat(TransactionAddController.editedTranAmt(new BigDecimal("-50.00")))
                    .as("the picture's sign insertion is fixed, so a negative prints its sign rather "
                            + "than carrying a zoned overpunch")
                    .isEqualTo("-00000050.00");
            assertThat(TransactionAddController.editedTranAmt(new BigDecimal("0.00")))
                    .as("zero is not negative, so a fixed sign insertion prints '+'")
                    .isEqualTo("+00000000.00");
            assertThat(TransactionAddController.editedTranAmt(new BigDecimal("123456789.12")))
                    .as("the leading 1 is discarded - truncation on the left for a numeric receiver, "
                            + "which is the opposite direction from a PIC X move")
                    .isEqualTo("+23456789.12");
        }
    }

    @Nested
    @DisplayName("EVALUATE EIBAID - four named arms in source order, then WHEN OTHER")
    class AidDispatch {
        @Test
        @DisplayName("the four named arms are the four the source names, and nothing else matches")
        void onlyTheFourNamedKeysMatchAnArm() {
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHENTER)).isTrue();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3)).isTrue();
            assertThat(PfKeyResolver.isPf4(CicsAid.DFHPF4)).isTrue();
            assertThat(PfKeyResolver.isPf5(CicsAid.DFHPF5)).isTrue();

            for (byte aid : new byte[] {CicsAid.DFHNULL, CicsAid.DFHCLEAR, CicsAid.DFHPA1,
                    CicsAid.DFHPF1, CicsAid.DFHPF12, CicsAid.DFHPF15}) {
                assertThat(PfKeyResolver.isEnter(aid) || PfKeyResolver.isPf3(aid)
                        || PfKeyResolver.isPf4(aid) || PfKeyResolver.isPf5(aid))
                        .as("EIBAID 0x%02X must fall to WHEN OTHER at :128. COTRN01C tests EIBAID "
                                + "inline rather than through CSSTRPFY, and the resolver has to "
                                + "reproduce those inline tests as identical boolean outcomes",
                                aid)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("PF3 with no caller falls back to the main menu, and with one echoes it")
        void pf3ResolvesItsTargetFromTheCommarea() {
            TransactionRepository repository = repositoryReporting(
                    ReadResult.notFound(TransactionRepository.INPUT_DD_NAME));

            withController(repository, controller -> {
                ProgramState blankCaller = controller.mainPara(reentryWith(CicsAid.DFHPF3));
                assertThat(blankCaller.response().getNextProgram())
                        .as(":116-117 - a blank CDEMO-FROM-PROGRAM defaults the target to COMEN01C")
                        .isEqualTo(TransactionAddController.MAIN_MENU_PROGRAM);
                assertThat(blankCaller.transferred())
                        .as("the PF3 arm reaches the XCTL at :205-208 and never the RETURN at :136")
                        .isTrue();
                assertThat(blankCaller.screensSent())
                        .as("no map is sent before transferring - the target paints its own")
                        .isZero();

                TransactionAddRequest fromList = reentryWith(CicsAid.DFHPF3);
                fromList.setNavigationContext(NavigationContext.empty()
                        .withPgmReenter()
                        .withFromProgram(TransactionAddController.TRANSACTION_LIST_PROGRAM));
                assertThat(controller.mainPara(fromList).response().getNextProgram())
                        .as(":119-120 - a named caller is echoed back as the target")
                        .isEqualTo(TransactionAddController.TRANSACTION_LIST_PROGRAM);

                assertThat(controller.mainPara(reentryWith(CicsAid.DFHPF5))
                        .response().getNextProgram())
                        .as(":126 - PF5 always goes to the transaction list")
                        .isEqualTo(TransactionAddController.TRANSACTION_LIST_PROGRAM);
                assertThat(controller.mainPara(coldStart()).response().getNextProgram())
                        .as(":95 - no commarea, so the program abandons the transaction for signon")
                        .isEqualTo(TransactionAddController.SIGN_ON_PROGRAM);
                return null;
            });
        }

        @Test
        @DisplayName("an unnamed key takes WHEN OTHER and reports the invalid-key message")
        void whenOtherReportsTheInvalidKeyMessage() {
            TransactionRepository repository = repositoryReporting(
                    ReadResult.notFound(TransactionRepository.INPUT_DD_NAME));

            withController(repository, controller -> {
                ProgramState state = controller.mainPara(reentryWith(CicsAid.DFHPF12));

                assertThat(state.errFlagOn())
                        .as(":129 - MOVE 'Y' TO WS-ERR-FLG")
                        .isTrue();
                assertThat(state.message())
                        .as(":130 - CCDA-MSG-INVALID-KEY moved into PIC X(80)")
                        .hasSize(TransactionAddController.WS_MESSAGE_LENGTH)
                        .startsWith("Invalid key pressed. Please see below...");
                assertThat(state.response().getErrmsgo())
                        .as(":217 - and then into PIC X(78)")
                        .hasSize(ScreenField.ERRMSGO.payloadLength());
                assertThat(state.returned())
                        .as(":131 sends the screen and control falls through to the RETURN at :136")
                        .isTrue();
                assertThat(state.response().getNextMapset())
                        .as("the screen is named on a path that sends one")
                        .isEqualTo(TransactionAddController.MAPSET_NAME);
                return null;
            });
        }
    }

    @Nested
    @DisplayName("Gate G38 - this screen highlights in neither state, and gate G37 - no session")
    class HighlightingAndStatelessness {
        @Test
        @DisplayName("the CSSETATY decision is 'touch nothing' on both ENTER and REENTER")
        void neverHighlightsInEitherState() {
            for (boolean reenter : new boolean[] {false, true}) {
                FieldHighlight decision = TransactionAddController.lookupFieldHighlight(reenter);

                assertThat(decision.untouched())
                        .as("COTRN01C does not copy CSSETATY and declares no FLG-<field>-NOT-OK pair, "
                                + "so with reenter=%s the resolver writes no byte. Gate G38 asks that "
                                + "highlighting apply only in REENTER; this program satisfies it by "
                                + "applying none in either state", reenter)
                        .isTrue();
                assertThat(decision.colourItemAssigned()).isFalse();
                assertThat(decision.outputItemAssigned()).isFalse();
            }
        }

        @Test
        @DisplayName("no rejecting path ever colours the lookup field red")
        void theLookupFieldIsNeverRecoloured() {
            withController(repositoryReporting(
                    ReadResult.notFound(TransactionRepository.INPUT_DD_NAME)), controller -> {
                        TransactionAddRequest blankKey = reentryWith(CicsAid.DFHENTER);
                        TransactionAddRequest missingKey = reentryWith(CicsAid.DFHENTER);
                        missingKey.setTrnidin(KNOWN_TRAN_ID);

                        for (TransactionAddRequest request
                                : List.of(blankKey, missingKey, reentryWith(CicsAid.DFHPF4))) {
                            ProgramState state = controller.mainPara(request);
                            assertThat(TransactionAddController.isErrorColoured(state.response()))
                                    .as("TRNIDINC must never hold DFHRED (0x%02X): this program moves "
                                            + "no attribute byte at all", BmsAttributes.DFHRED)
                                    .isFalse();
                        }
                        return null;
                    });
        }

        @Test
        @DisplayName("two runs of one controller cannot see each other - WORKING-STORAGE is per task")
        void theControllerKeepsNoStateBetweenRuns() {
            TransactionRepository repository = repositoryReporting(
                    ReadResult.found(TransactionRepository.INPUT_DD_NAME,
                            tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"))));

            withController(repository, controller -> {
                TransactionAddRequest found = reentryWith(CicsAid.DFHENTER);
                found.setTrnidin(KNOWN_TRAN_ID);
                ProgramState first = controller.mainPara(found);

                ProgramState second = controller.mainPara(reentryWith(CicsAid.DFHENTER));

                assertThat(first.response().getTrnido())
                        .as(":178 - the first run painted the record it read")
                        .startsWith(KNOWN_TRAN_ID);
                assertThat(second.response().getTrnido())
                        .as("the second run rejected a blank key at :147-152 and reached neither the "
                                + "read nor the paint, so the detail field is blank. A shared field "
                                + "would show the first run's value here")
                        .isBlank();
                assertThat(second.message())
                        .as(":149 - Tran ID can NOT be empty...")
                        .startsWith("Tran ID can NOT be empty...");
                assertThat(second.response())
                        .as("each run builds its own response, so the two are distinct objects")
                        .isNotSameAs(first.response());
                return null;
            });
        }
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
                + Path.of("").toAbsolutePath() + ". The parity assertions read the COBOL source and "
                + "README.md as evidence for risk R-B, so the suite must run inside the repository.");
    }
}
