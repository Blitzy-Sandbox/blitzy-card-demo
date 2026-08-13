package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.TransactionViewController;
import com.vsergeychik.carddemo.transaction.TransactionViewController.ProgramState;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewRequest;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse.ScreenField;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The parity gate for {@code app/cbl/COTRN02C.cbl} - twenty declarative cases, judged field by field, with
 * a required diff count of zero.
 */
@DisplayName("COTRN02C parity - transaction CT02, a 350-byte INSERT, and the class name says View")
final class COTRN02CParityTest {
    private static final String PROGRAM = "COTRN02C";

    private static final ParityCase.UnitKind UNIT_KIND = ParityCase.UnitKind.CONTROLLER_POJO;

    private static final String TRANSACT_DATASET = TransactionRepository.CICS_FILE_NAME;

    private static final String CCXREF_DATASET = CardXrefRepository.BASE_DD_NAME;

    private static final String NO_AID_TOKEN = null;

    private static final String UNEXPECTED_STATUS = FileStatus.RECORD_LENGTH_CONFLICT;

    static List<ParityCase> cases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);

        assertThat(loaded)
                .as("the gate is stated as twenty declarative cases per program, and 'diff count is "
                        + "zero across all twenty' is satisfied vacuously by a shorter set - so the "
                        + "count is asserted before a single case runs")
                .hasSize(ParityHarness.CASES_PER_PROGRAM);

        List<String> ids = new ArrayList<>(loaded.size());
        for (ParityCase parityCase : loaded) {
            ids.add(parityCase.caseId());
            assertThat(parityCase.program())
                    .as("every case in parity/%s/ must name that program, or it is judging a different "
                            + "one", PROGRAM)
                    .isEqualTo(PROGRAM);
            assertThat(parityCase.unitKind())
                    .as("%s is a CICS online program reached as a plain controller object; a case "
                            + "declaring any other unit kind would be run by a different adapter",
                            PROGRAM)
                    .isEqualTo(UNIT_KIND);
            assertThat(parityCase.jobParameters())
                    .as("COTRN02C is an online transaction: it has no PARM and no job parameter")
                    .isEmpty();
        }

        List<String> expected = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            expected.add(ParityHarness.caseId(ordinal));
        }
        assertThat(ids)
                .as("the twenty cases are case01 through case20 in ascending order, so a gap or a "
                        + "duplicate is visible here rather than as a quietly smaller suite")
                .containsExactlyElementsOf(expected);
        return loaded;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("field-for-field identical to app/cbl/COTRN02C.cbl")
    void isFieldForFieldIdenticalToTheCobol(ParityCase parityCase) {
        FieldDiffer.DiffResult diff =
                ParityHarness.usAscii().judge(parityCase, UNIT_KIND, COTRN02CParityTest::execute);

        assertThat(diff.count())
                .as("%s/%s must produce a diff count of zero. A module is not complete until the count "
                        + "is zero across all twenty of its cases, so a single difference here is a "
                        + "failed gate rather than a tolerance.%n%s",
                        parityCase.program(), parityCase.caseId(), diff.render())
                .isZero();
        assertThat(diff.isClean())
                .as("the differ reported a clean result and a non-zero count, or the reverse - the two "
                        + "must agree.%n%s", diff.render())
                .isTrue();
    }

    private static ParityHarness.UnitOutcome execute(ParityHarness.Invocation invocation) {
        TransactMaster master = masterOf(invocation);
        CrossReference crossReference = crossReferenceOf(invocation);

        TransactionRepository transactions = transactionRepositoryFor(invocation, master);
        CardXrefRepository crossReferenceRepository =
                cardXrefRepositoryFor(invocation, crossReference);

        TransactionViewController controller = new TransactionViewController(
                transactions,
                crossReferenceRepository,
                new DateUtilityJob(invocation.charset()),
                invocation.clock(),
                invocation.charset());

        ProgramState state = controller.mainPara(requestOf(invocation));

        requireOnlyTheSourcesOwnAccessPaths(transactions, crossReferenceRepository);
        return fingerprintOf(invocation, master, state);
    }

    private static void requireOnlyTheSourcesOwnAccessPaths(TransactionRepository transactions,
                                                            CardXrefRepository crossReference) {
        verify(transactions, never()).readByTranId(anyString());
        verify(transactions, never()).readForUpdateByTranId(anyString());
        verify(transactions, never())
                .startBrowse(TransactionRepository.BrowseDirection.FORWARD);
        verify(transactions, never()).openInput();
        verify(transactions, never()).openOutput();
        verify(crossReference, never()).openBrowse();
    }

    private static final class TransactMaster {
        private final List<String> rows;

        private final Charset charset;

        private final FixedWidthCodec codec;

        private int backwardCursor;

        TransactMaster(List<String> seededRows, Charset charset) {
            this.charset = Objects.requireNonNull(charset, "A code page is required");
            this.codec = new FixedWidthCodec(charset);
            List<String> ordered = new ArrayList<>(seededRows.size());
            for (String row : seededRows) {
                ordered.add(codec.padToDeclaredWidth(row, TranRecord.RECORD_LENGTH));
            }
            ordered.sort(String::compareTo);
            this.rows = ordered;
            this.backwardCursor = ordered.size();
        }

        List<String> rows() {
            return Collections.unmodifiableList(new ArrayList<>(rows));
        }

        void startBackwardBrowse() {
            backwardCursor = rows.size();
        }

        TransactionRepository.ReadResult readPrev() {
            if (backwardCursor <= 0) {
                return TransactionRepository.ReadResult
                        .endOfFile(TransactionRepository.CICS_FILE_NAME);
            }
            backwardCursor--;
            return TransactionRepository.ReadResult.found(TransactionRepository.CICS_FILE_NAME,
                    TranRecord.decode(rows.get(backwardCursor), charset));
        }

        TransactionRepository.WriteResult insert(TranRecord record) {
            String image = new String(record.encode(charset), charset);
            String key = keyOf(image);
            for (String row : rows) {
                if (keyOf(row).equals(key)) {
                    return TransactionRepository.WriteResult
                            .duplicate(TransactionRepository.CICS_FILE_NAME);
                }
            }
            rows.add(image);
            rows.sort(String::compareTo);
            return TransactionRepository.WriteResult
                    .written(TransactionRepository.CICS_FILE_NAME);
        }

        private static String keyOf(String image) {
            return image.substring(TranRecord.TRAN_ID_OFFSET,
                    TranRecord.TRAN_ID_OFFSET + TranRecord.TRAN_ID_LENGTH);
        }
    }

    private static TransactMaster masterOf(ParityHarness.Invocation invocation) {
        List<String> rows = invocation.hasDataset(TRANSACT_DATASET)
                ? invocation.dataset(TRANSACT_DATASET).rows()
                : List.of();
        return new TransactMaster(rows, invocation.charset());
    }

    private static TransactionRepository transactionRepositoryFor(ParityHarness.Invocation invocation,
                                                                  TransactMaster master) {
        TransactionRepository repository = mock(TransactionRepository.class);
        when(repository.datasetCharset()).thenReturn(invocation.charset());
        when(repository.recordLength()).thenReturn(TranRecord.RECORD_LENGTH);
        when(repository.startBrowse(TransactionRepository.BrowseDirection.BACKWARD))
                .thenAnswer(positioning -> backwardBrowseOver(invocation, master));
        when(repository.write(any()))
                .thenAnswer(writing -> writeOutcomeOf(invocation, master,
                        writing.getArgument(0, TranRecord.class)));
        return repository;
    }

    private static TransactionRepository.Browse backwardBrowseOver(ParityHarness.Invocation invocation,
                                                                   TransactMaster master) {
        master.startBackwardBrowse();
        TransactionRepository.Browse browse = positionedBrowse();
        when(browse.direction()).thenReturn(TransactionRepository.BrowseDirection.BACKWARD);
        when(browse.readPrev()).thenAnswer(reading -> browseReadOf(invocation, master));
        when(browse.readNext()).thenThrow(new IllegalStateException(
                "COTRN02C issues EXEC CICS READPREV at app/cbl/COTRN02C.cbl:675 and never READNEXT. A "
                        + "forward read of a backward browse would return the lowest key rather than the "
                        + "highest, and every identifier this program generated afterwards would be "
                        + "wrong."));
        return browse;
    }

    private static TransactionRepository.ReadResult browseReadOf(ParityHarness.Invocation invocation,
                                                                 TransactMaster master) {
        if (!invocation.hasForcedOutcome(ParityCase.RepositoryOperation.READ_NEXT)) {
            return master.readPrev();
        }
        ParityCase.ForcedOutcome forced =
                invocation.forcedOutcome(ParityCase.RepositoryOperation.READ_NEXT);
        return switch (forced.outcome()) {
            case END_OF_FILE -> TransactionRepository.ReadResult
                    .endOfFile(TransactionRepository.CICS_FILE_NAME);
            case NOT_FOUND -> TransactionRepository.ReadResult
                    .notFound(TransactionRepository.CICS_FILE_NAME);
            case OTHER -> TransactionRepository.ReadResult
                    .other(TransactionRepository.CICS_FILE_NAME, UNEXPECTED_STATUS);
            case OK, DUPLICATE -> throw new IllegalArgumentException("A COTRN02C case forced the "
                    + forced.outcome() + " outcome for the TRANSACT browse read. That outcome carries a "
                    + "record, and forcing it would require inventing 350 bytes no copybook, fixture or "
                    + "case declared - so the identifier generated from it would be compared against an "
                    + "expectation derived from nothing. Seed a TRANSACT row instead: the row-backed "
                    + "browse then reports " + FileStatus.Outcome.OK + " for a file that holds one and "
                    + FileStatus.Outcome.END_OF_FILE + " for a file that does not.");
        };
    }

    private static TransactionRepository.WriteResult writeOutcomeOf(ParityHarness.Invocation invocation,
                                                                    TransactMaster master,
                                                                    TranRecord record) {
        if (!invocation.hasForcedOutcome(ParityCase.RepositoryOperation.WRITE)) {
            return master.insert(record);
        }
        ParityCase.ForcedOutcome forced =
                invocation.forcedOutcome(ParityCase.RepositoryOperation.WRITE);
        return switch (forced.outcome()) {
            case DUPLICATE -> TransactionRepository.WriteResult
                    .duplicate(TransactionRepository.CICS_FILE_NAME);
            case OTHER -> TransactionRepository.WriteResult
                    .other(TransactionRepository.CICS_FILE_NAME, UNEXPECTED_STATUS);
            case OK -> throw new IllegalArgumentException("A COTRN02C case forced the "
                    + forced.outcome() + " outcome for the TRANSACT write. A successful write is what "
                    + "the seeded master already reports, and forcing it would suppress the duplicate "
                    + "check while leaving the case's final-state expectation describing a row that was "
                    + "never inserted.");
            case NOT_FOUND, END_OF_FILE -> throw new IllegalArgumentException("A COTRN02C case forced "
                    + "the " + forced.outcome() + " outcome for the TRANSACT write. EXEC CICS WRITE "
                    + "raises neither condition, so no arm of WRITE-TRANSACT-FILE at "
                    + "app/cbl/COTRN02C.cbl:723-749 is written for it; use " + FileStatus.Outcome.OTHER
                    + " to reach the WHEN OTHER arm at :742.");
        };
    }

    private static final class CrossReference {
        private final List<CardXrefRecord> records;

        private final List<String> images;

        private final FixedWidthCodec codec;

        CrossReference(List<String> seededRows, Charset charset) {
            this.codec = new FixedWidthCodec(Objects.requireNonNull(charset,
                    "A code page is required"));
            List<CardXrefRecord> decoded = new ArrayList<>(seededRows.size());
            List<String> raw = new ArrayList<>(seededRows.size());
            for (String row : seededRows) {
                String image = codec.padToDeclaredWidth(row, CardXrefRecord.RECORD_LENGTH);
                raw.add(image);
                decoded.add(CardXrefRecord.decode(image.getBytes(charset), codec));
            }
            this.records = decoded;
            this.images = raw;
        }

        CardXrefRepository.ReadResult byCardNumber(String key) {
            String wanted = codec.movePicX(key == null ? "" : key,
                    CardXrefRecord.XREF_CARD_NUM_LENGTH);
            for (int index = 0; index < records.size(); index++) {
                if (records.get(index).xrefCardNum().equals(wanted)) {
                    return CardXrefRepository.ReadResult.found(CardXrefRepository.BASE_DD_NAME,
                            records.get(index), images.get(index));
                }
            }
            return CardXrefRepository.ReadResult.notFound(CardXrefRepository.BASE_DD_NAME);
        }

        CardXrefRepository.ReadResult byAccountId(String key) {
            String wanted = codec.movePicX(key == null ? "" : key,
                    CardXrefRecord.XREF_ACCT_ID_LENGTH);
            for (int index = 0; index < records.size(); index++) {
                String held = codec.movePic9(records.get(index).xrefAcctId(),
                        CardXrefRecord.XREF_ACCT_ID_LENGTH);
                if (held.equals(wanted)) {
                    return CardXrefRepository.ReadResult.found(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                            records.get(index), images.get(index));
                }
            }
            return CardXrefRepository.ReadResult
                    .notFound(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
        }
    }

    private static CrossReference crossReferenceOf(ParityHarness.Invocation invocation) {
        List<String> rows = invocation.hasDataset(CCXREF_DATASET)
                ? invocation.dataset(CCXREF_DATASET).rows()
                : List.of();
        return new CrossReference(rows, invocation.charset());
    }

    private static CardXrefRepository cardXrefRepositoryFor(ParityHarness.Invocation invocation,
                                                            CrossReference crossReference) {
        CardXrefRepository repository = mock(CardXrefRepository.class);
        when(repository.readByCardNumber(anyString()))
                .thenAnswer(reading -> invocation
                        .hasForcedOutcome(ParityCase.RepositoryOperation.READ)
                                ? forcedXrefResult(invocation, CardXrefRepository.BASE_DD_NAME)
                                : crossReference.byCardNumber(reading.getArgument(0, String.class)));
        when(repository.readByAccountIdViaAltIndex(anyString()))
                .thenAnswer(reading -> invocation
                        .hasForcedOutcome(ParityCase.RepositoryOperation.READ)
                                ? forcedXrefResult(invocation,
                                        CardXrefRepository.ALTERNATE_INDEX_DD_NAME)
                                : crossReference.byAccountId(reading.getArgument(0, String.class)));
        return repository;
    }

    private static CardXrefRepository.ReadResult forcedXrefResult(ParityHarness.Invocation invocation,
                                                                  String ddName) {
        ParityCase.ForcedOutcome forced =
                invocation.forcedOutcome(ParityCase.RepositoryOperation.READ);
        return switch (forced.outcome()) {
            case NOT_FOUND -> CardXrefRepository.ReadResult.notFound(ddName);
            case END_OF_FILE -> CardXrefRepository.ReadResult.endOfFile(ddName);
            case OTHER -> CardXrefRepository.ReadResult.other(ddName, UNEXPECTED_STATUS);
            case OK, DUPLICATE -> throw new IllegalArgumentException("A COTRN02C case forced the "
                    + forced.outcome() + " outcome for the cross-reference read. That outcome carries a "
                    + "record, and forcing it would require inventing the 50 bytes of a CARD-XREF-RECORD "
                    + "that no copybook, fixture or case declared - and the card number taken from it "
                    + "would reach TRAN-CARD-NUM. Seed a " + CCXREF_DATASET + " row instead: the "
                    + "row-backed read then reports " + FileStatus.Outcome.OK + " for a key that matches "
                    + "and " + FileStatus.Outcome.NOT_FOUND + " for one that does not.");
        };
    }

    private static TransactionViewRequest requestOf(ParityHarness.Invocation invocation) {
        TransactionViewRequest request = new TransactionViewRequest();
        FixedWidthCodec codec = invocation.codec();

        if (invocation.eibcalen() > 0) {
            request.setNavigationContext(NavigationContext.fromFixedWidth(codec,
                    codec.serialise(NavigationContext.LAYOUT,
                            subsetOf(invocation.commarea(), baseCommareaFieldNames()))));
            request.setCt02Info(TransactionViewRequest.Ct02Info.fromFixedWidth(
                    codec.serialise(TransactionViewRequest.Ct02Info.LAYOUT,
                            subsetOf(invocation.commarea(), extensionFieldNames())),
                    invocation.charset()));
        }

        String token = aidTokenOf(invocation.aid());
        if (token != NO_AID_TOKEN) {
            request.setAid(token);
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
        NavigationContext.LAYOUT.spans().forEach(span -> names.add(span.name()));
        return names;
    }

    private static List<String> extensionFieldNames() {
        List<String> names = new ArrayList<>();
        TransactionViewRequest.Ct02Info.LAYOUT.spans().forEach(span -> names.add(span.name()));
        return names;
    }

    private static String aidTokenOf(String mnemonic) {
        if (mnemonic == null) {
            return NO_AID_TOKEN;
        }
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return PfKeyResolver.aidImage(entry.getKey());
            }
        }
        throw new IllegalArgumentException('"' + mnemonic + "\" is not a DFHAID mnemonic that "
                + CicsAid.class.getName() + " reproduces, yet ParityCase accepted it. The two read the "
                + "same map, so this means they have drifted apart.");
    }

    private static void applyReceivedField(TransactionViewRequest request, String name, String value) {
        for (TransactionViewRequest.ScreenField field : TransactionViewRequest.ScreenField.values()) {
            if (field.inputItem().equals(name)) {
                request.setPayloadValue(field, value);
                return;
            }
        }
        List<String> declared = new ArrayList<>();
        for (TransactionViewRequest.ScreenField field : TransactionViewRequest.ScreenField.values()) {
            declared.add(field.inputItem());
        }
        throw new IllegalArgumentException('"' + name + "\" is not one of the "
                + TransactionViewResponse.FIELD_COUNT + " xxxI items app/cpy-bms/COTRN02.CPY declares. "
                + "The declared set is " + declared + ". The xxxL, xxxF and xxxA items are length, flag "
                + "and attribute metadata and are not payload fields, so they are not settable from a "
                + "case.");
    }

    private static ParityHarness.UnitOutcome fingerprintOf(ParityHarness.Invocation invocation,
                                                           TransactMaster master,
                                                           ProgramState state) {
        FixedWidthCodec codec = invocation.codec();
        ParityHarness.UnitOutcome.Builder recorder = invocation.recorder();

        for (String written : state.writtenRecords()) {
            recorder.wrote(TRANSACT_DATASET, TranRecord.LAYOUT, written);
        }
        recorder.finalState(TRANSACT_DATASET, TranRecord.LAYOUT, master.rows());
        recorder.response(observedResponseOf(codec, state));

        for (String line : state.displayLines()) {
            recorder.display(line);
        }
        if (state.screenSent()) {
            recorder.message(new ParityCase.EmittedMessage(
                    ParityCase.MessageChannel.WS_MESSAGE_80, state.message()));
            recorder.message(new ParityCase.EmittedMessage(
                    ParityCase.MessageChannel.SCREEN_ERRMSG_78, state.response().getErrmsgo()));
        }

        recorder.returnCode(0);
        return recorder.build();
    }

    private static FieldDiffer.ObservedResponse observedResponseOf(FixedWidthCodec codec,
                                                                  ProgramState state) {
        TransactionViewResponse response = state.response();

        Map<String, String> navigation = new LinkedHashMap<>();
        navigation.putAll(codec.deserialise(NavigationContext.LAYOUT,
                response.getNavigationContext().toFixedWidth(codec)));
        navigation.putAll(extensionImagesOf(codec, response.getCt02Info()));

        return new FieldDiffer.ObservedResponse(
                response.getNextProgram(),
                namedOrNone(response.getNextMapset()),
                namedOrNone(response.getNextMap()),
                navigation,
                sendsOf(state),
                cursorLengthItemOf(state),
                terminationOf(state));
    }

    private static Map<String, String> extensionImagesOf(FixedWidthCodec codec,
                                                         TransactionViewResponse.Ct02Info info) {
        TransactionViewRequest.Ct02Info projection = new TransactionViewRequest.Ct02Info();
        projection.setTrnidFirst(info.getTrnidFirst());
        projection.setTrnidLast(info.getTrnidLast());
        projection.setPageNum(info.getPageNum());
        projection.setNextPageFlg(info.getNextPageFlg());
        projection.setTrnSelFlg(info.getTrnSelFlg());
        projection.setTrnSelected(info.getTrnSelected());
        return codec.deserialise(TransactionViewRequest.Ct02Info.LAYOUT,
                projection.toFixedWidth(codec.charset()));
    }

    private static List<FieldDiffer.ObservedSend> sendsOf(ProgramState state) {
        if (!state.screenSent()) {
            return List.of();
        }

        Map<String, String> painted = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            painted.put(field.outputItemName(), state.response().getOutputItem(field));
        }
        Map<String, String> attributes = Map.of(
                ScreenField.ERRMSG.colourItemName(),
                BmsAttributes.colourMnemonic(
                        state.response().getMetadata(ScreenField.ERRMSG).getColour()));
        return List.of(new FieldDiffer.ObservedSend(painted, attributes));
    }

    private static String namedOrNone(String reference) {
        return reference == null || reference.isBlank() ? null : reference;
    }

    private static String cursorLengthItemOf(ProgramState state) {
        String label = state.screenMetadata().cursorField();
        return label == null ? null : label + LENGTH_ITEM_SUFFIX;
    }

    private static ParityCase.Termination terminationOf(ProgramState state) {
        if (state.transferred() == state.returned()) {
            throw new IllegalStateException("The run reports transferred=" + state.transferred()
                    + " and returned=" + state.returned() + ". Exactly one must hold: every arm of "
                    + "app/cbl/COTRN02C.cbl:107-159 ends either at the XCTL of :508-511 or at the "
                    + "EXEC CICS RETURN of :530-534, and an XCTL never reaches that RETURN.");
        }
        return state.transferred() ? ParityCase.Termination.XCTL
                : ParityCase.Termination.RETURN_TRANSID;
    }

    private static final String LENGTH_ITEM_SUFFIX = "L";

    private static final Instant PINNED_INSTANT = Instant.parse("2022-07-19T23:12:34Z");

    private static final String ACCOUNT_ID = "00000000011";

    private static final String CARD_NUMBER = "4111111111111111";

    private static final String VALID_DATE = "2022-07-18";

    private static final String TOLERATED_DATE = "1500-01-01";

    private static final String REJECTED_DATE = "2022-13-01";

    private static <T> T withController(TransactionRepository transactions,
                                        CardXrefRepository crossReference,
                                        Function<TransactionViewController, T> work) {
        return work.apply(new TransactionViewController(
                transactions,
                crossReference,
                new DateUtilityJob(ParityHarness.FIXTURE_CHARSET),
                Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC),
                ParityHarness.FIXTURE_CHARSET));
    }

    private static TransactionRepository masterHoldingOneRecord() {
        TransactionRepository repository = mock(TransactionRepository.class);
        TransactionRepository.Browse browse = positionedBrowse();
        when(browse.readPrev()).thenReturn(TransactionRepository.ReadResult.found(
                TransactionRepository.CICS_FILE_NAME, recordKeyed("0000000000000050")));
        when(repository.startBrowse(TransactionRepository.BrowseDirection.BACKWARD))
                .thenReturn(browse);
        when(repository.write(any())).thenReturn(TransactionRepository.WriteResult
                .written(TransactionRepository.CICS_FILE_NAME));
        return repository;
    }

    private static CardXrefRepository crossReferenceResolvingTheAccount() {
        CardXrefRepository repository = mock(CardXrefRepository.class);
        CardXrefRecord record = new CardXrefRecord(CARD_NUMBER, 123_456_789, 11L);
        when(repository.readByAccountIdViaAltIndex(anyString()))
                .thenReturn(CardXrefRepository.ReadResult.found(
                        CardXrefRepository.ALTERNATE_INDEX_DD_NAME, record,
                        new String(record.encode(ParityHarness.FIXTURE_CHARSET),
                                ParityHarness.FIXTURE_CHARSET)));
        return repository;
    }

    private static TranRecord recordKeyed(String tranId) {
        TranRecord record = new TranRecord(ParityHarness.FIXTURE_CHARSET);
        record.moveTranId(tranId);
        record.moveTranTypeCd("01");
        record.moveTranCatCd(1);
        record.moveTranSource("POS TERM  ");
        record.moveTranDesc("PARITY DESCRIPTION");
        record.moveTranAmt(new BigDecimal("504.77"));
        record.moveTranMerchantId(123_456_789L);
        record.moveTranMerchantName("PARITY MERCHANT");
        record.moveTranMerchantCity("PARITY CITY");
        record.moveTranMerchantZip("0000012345");
        record.moveTranCardNum(CARD_NUMBER);
        record.moveTranOrigTs(VALID_DATE);
        record.moveTranProcTs(VALID_DATE);
        return record;
    }

    private static TransactionViewRequest reentryWith(String aidMnemonic) {
        TransactionViewRequest request = new TransactionViewRequest();
        request.setNavigationContext(NavigationContext.empty().withPgmReenter());
        request.setAid(aidTokenOf(aidMnemonic));
        return request;
    }

    private static TransactionViewRequest confirmedAdd(String origDate, String procDate) {
        TransactionViewRequest request = reentryWith("DFHENTER");
        request.setActidin(ACCOUNT_ID);
        request.setTtypcd("01");
        request.setTcatcd("0001");
        request.setTrnsrc("POS TERM  ");
        request.setTdesc("Coffee and a newspaper");
        request.setTrnamt("+00000012.34");
        request.setTorigdt(origDate);
        request.setTprocdt(procDate);
        request.setMid("123456789");
        request.setMname("Kwik-E-Mart");
        request.setMcity("Springfield");
        request.setMzip("0000012345");
        request.setConfirm("Y");
        return request;
    }

    @Nested
    @DisplayName("Risk R-B - the class name says view and the program adds")
    class RiskRb {
        @Test
        @DisplayName("the source's own header says Add, and it browses, adds one and writes")
        void theCobolSourceAdds() throws IOException {
            String cobol = Files.readString(repositoryFile("app/cbl/COTRN02C.cbl"),
                    StandardCharsets.UTF_8);

            assertThat(cobol)
                    .as("the Function header is the primary evidence for risk R-B and the reason every "
                            + "expectation in this file describes an insert")
                    .contains("Function    : Add a new Transaction to TRANSACT file");
            assertThat(cobol)
                    .as("the four statements that make it an insert: position past the end, read the "
                            + "highest key, end the browse, write the new record")
                    .contains("EXEC CICS STARTBR")
                    .contains("EXEC CICS READPREV")
                    .contains("EXEC CICS ENDBR")
                    .contains("EXEC CICS WRITE");
            assertThat(cobol)
                    .as("the identifier is a high-water mark plus one, which is the whole reason the "
                            + "browse is backward")
                    .contains("MOVE HIGH-VALUES TO TRAN-ID")
                    .contains("ADD 1 TO WS-TRAN-ID-N");
            assertThat(cobol)
                    .as("this program neither rewrites nor deletes, so an expectation naming either "
                            + "would be describing a different program")
                    .doesNotContain("EXEC CICS REWRITE")
                    .doesNotContain("EXEC CICS DELETE");
        }

        @Test
        @DisplayName("README.md's own inventory records CT02 as Transaction Add, not View")
        void theProjectInventoryContradictsThePromptsMapping() throws IOException {
            String readme = Files.readString(repositoryFile("README.md"), StandardCharsets.UTF_8);

            assertThat(readme)
                    .as("README.md:213-231 is the independent corroboration of risk R-B; if this row ever "
                            + "changes, the resolution recorded in this class must be revisited")
                    .contains("| CT02 | COTRN02 | COTRN02C | Transaction Add     |");
            assertThat(readme)
                    .as("and the sibling really is the one that views")
                    .contains("| CT01 | COTRN01 | COTRN01C | Transaction View    |");
        }

        @Test
        @DisplayName("the confirmed-add path writes exactly one 350-byte record")
        void theConfirmedPathWrites() {
            TransactionRepository transactions = masterHoldingOneRecord();

            ProgramState state = withController(transactions, crossReferenceResolvingTheAccount(),
                    controller -> controller.mainPara(confirmedAdd(VALID_DATE, VALID_DATE)));

            assertThat(state.writtenRecords())
                    .as("app/cbl/COTRN02C.cbl:713 - one EXEC CICS WRITE, and the class name that says "
                            + "otherwise is honoured for its spelling only (rule R1)")
                    .hasSize(1)
                    .allSatisfy(image -> assertThat(image).hasSize(TranRecord.RECORD_LENGTH));
            verify(transactions).write(any());
            assertThat(state.tranRecord().tranId())
                    .as(":448-449 - the highest existing key was ...50, so the new record is ...51")
                    .isEqualTo("0000000000000051");
        }

        @Test
        @DisplayName("no rejected path writes anything at all")
        void aRejectedPathNeverWrites() {
            TransactionRepository transactions = masterHoldingOneRecord();

            withController(transactions, crossReferenceResolvingTheAccount(), controller -> {
                controller.mainPara(reentryWith("DFHENTER"));
                controller.mainPara(reentryWith("DFHPF3"));
                controller.mainPara(reentryWith("DFHPF4"));
                controller.mainPara(reentryWith("DFHPF12"));
                controller.mainPara(new TransactionViewRequest());

                TransactionViewRequest unconfirmed = confirmedAdd(VALID_DATE, VALID_DATE);
                unconfirmed.setConfirm(" ");
                assertThat(controller.mainPara(unconfirmed).message())
                        .as(":178-182 - a blank CONFIRM asks again rather than adding")
                        .startsWith(TransactionViewController.MSG_CONFIRM_TO_ADD);

                TransactionViewRequest badDate = confirmedAdd(REJECTED_DATE, VALID_DATE);
                assertThat(controller.mainPara(badDate).message())
                        .as(":404-409 - a date CSUTLDTC rejects stops the add")
                        .startsWith(TransactionViewController.MSG_ORIG_DATE_INVALID);
                return null;
            });

            verify(transactions, never()).write(any());
        }
    }

    @Nested
    @DisplayName("Practice B5 - ACCTDAT and CVACT01Y are declared, never used, and stay that way")
    class DeadDeclarations {
        @Test
        @DisplayName("the COBOL names ACCTDAT and copies CVACT01Y and references neither")
        void theSourceDeclaresThemAndUsesNeither() throws IOException {
            String cobol = Files.readString(repositoryFile("app/cbl/COTRN02C.cbl"),
                    StandardCharsets.UTF_8);

            assertThat(cobol)
                    .as("L40 declares the literal and L89 copies the record layout")
                    .contains("WS-ACCTDAT-FILE            PIC X(08) VALUE 'ACCTDAT '")
                    .contains("COPY CVACT01Y");
            assertThat(cobol)
                    .as("and no EXEC CICS command names WS-ACCTDAT-FILE, which is what makes the "
                            + "declaration dead rather than merely quiet")
                    .doesNotContain("DATASET   (WS-ACCTDAT-FILE)");
            assertThat(cobol)
                    .as("nor does any statement name the 01 group the copybook brings in, or any of the "
                            + "eleven fields of it that carry a distinctive name - including the "
                            + "misspelled ACCT-EXPIRAION-DATE, which is preserved as the copybook spells "
                            + "it wherever it IS modelled (implicit requirement I1)")
                    .doesNotContain("ACCOUNT-RECORD")
                    .doesNotContain("ACCT-ACTIVE-STATUS")
                    .doesNotContain("ACCT-CURR-BAL")
                    .doesNotContain("ACCT-CREDIT-LIMIT")
                    .doesNotContain("ACCT-EXPIRAION-DATE")
                    .doesNotContain("ACCT-GROUP-ID")
                    .doesNotContain("ACCT-OPEN-DATE");

            assertThat(occurrencesOf(cobol, "ACCT-ID"))
                    .as("every ACCT-ID in the source is part of XREF-ACCT-ID or WS-ACCT-ID-N; a bare one "
                            + "would be a reference to the dead COPY's key field")
                    .isEqualTo(occurrencesOf(cobol, "XREF-ACCT-ID")
                            + occurrencesOf(cobol, "WS-ACCT-ID"));
        }

        private int occurrencesOf(String text, String token) {
            int count = 0;
            int from = text.indexOf(token);
            while (from >= 0) {
                count++;
                from = text.indexOf(token, from + token.length());
            }
            return count;
        }

        @Test
        @DisplayName("the translation preserves both as constants and wires no account access")
        void theTranslationKeepsThemVisibleAndUnused() {
            assertThat(TransactionViewController.WS_ACCTDAT_FILE)
                    .as("the dead literal is sourced from the repository's own constant, so this file's "
                            + "only mention of the dataset is compile-checked and no name is retyped")
                    .isEqualTo(AccountRepository.CICS_FILE_NAME);
            assertThat(TransactionViewController.COPIED_ACCOUNT_RECORD_LENGTH)
                    .as("CVACT01Y declares a 300-byte ACCOUNT-RECORD, and recording its width keeps the "
                            + "dead COPY visible without modelling an account here")
                    .isEqualTo(AccountRecord.RECORD_LENGTH)
                    .isEqualTo(300);

            List<Class<?>> wired = List.of(TransactionRepository.class, CardXrefRepository.class,
                    DateUtilityJob.class, Clock.class, Charset.class);
            assertThat(TransactionViewController.class.getConstructors())
                    .as("no constructor takes an AccountRepository: the program performs no account "
                            + "access, so there is nowhere for one to be injected and nowhere for a "
                            + "well-meaning 'completion' of the migration to add a read (practice B5)")
                    .allSatisfy(constructor -> assertThat(List.of(constructor.getParameterTypes()))
                            .doesNotContain(AccountRepository.class)
                            .isSubsetOf(wired));
        }
    }

    @Nested
    @DisplayName("The widths every case is written against")
    class DeclaredWidths {
        @Test
        @DisplayName("TRAN-RECORD is 350 bytes and its FILLER is present and space-filled")
        void tranRecordIsThreeHundredAndFiftyBytesIncludingFiller() {
            TranRecord record = recordKeyed("0000000000000051");

            assertThat(TranRecord.RECORD_LENGTH)
                    .as("app/cpy/CVTRA05Y.cpy declares a 350-byte TRAN-RECORD, and app/cbl/COTRN02C.cbl:718 "
                            + "writes LENGTH OF TRAN-RECORD - so 350 is the width on the wire (gate G19)")
                    .isEqualTo(350);
            assertThat(record.displayImage())
                    .as("a serialised record is exactly its declared width, which is the check that fails "
                            + "immediately if a FILLER span were omitted (gate G21)")
                    .hasSize(TranRecord.RECORD_LENGTH);
            assertThat(TranRecord.sumOfDeclaredSpanLengths())
                    .as("every byte of the record belongs to a declared span; a gap would leave the sum "
                            + "short of the record length")
                    .isEqualTo(TranRecord.RECORD_LENGTH);
            assertThat(record.rawSpan(TranRecord.FILLER))
                    .as("CVTRA05Y's trailing FILLER PIC X(20) is emitted as spaces; omit it and the record "
                            + "is 330 bytes while every offset before it still looks right")
                    .isEqualTo(" ".repeat(TranRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("the commarea is 160 bytes and the CT02 extension is a separate 58")
        void theExtensionIsNotFoldedIntoTheCommarea() {
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("COCOM01Y's CARDDEMO-COMMAREA is 160 bytes and is shared by all seventeen online "
                            + "programs, so a per-program extension must not be folded into it")
                    .isEqualTo(160);
            assertThat(TransactionViewRequest.Ct02Info.CT02_INFO_LENGTH)
                    .as("CDEMO-CT02-INFO is 16 + 16 + 8 + 1 + 1 + 16 = 58 bytes "
                            + "(app/cbl/COTRN02C.cbl:72-80)")
                    .isEqualTo(58)
                    .isEqualTo(TransactionViewResponse.Ct02Info.LENGTH);
            assertThat(ProgramState.PASSED_COMMAREA_LENGTH)
                    .as("the area the XCTL at :509 and the RETURN at :530 pass is the two together")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH
                            + TransactionViewRequest.Ct02Info.CT02_INFO_LENGTH)
                    .isEqualTo(218);

            assertThat(baseCommareaFieldNames())
                    .as("the sixteen COCOM01Y fields and the six extension fields are disjoint, which is "
                            + "what lets a case declare them in one flat map")
                    .hasSize(16)
                    .doesNotContainAnyElementsOf(extensionFieldNames());
            assertThat(extensionFieldNames())
                    .containsExactly(TransactionViewRequest.Ct02Info.TRNID_FIRST_FIELD,
                            TransactionViewRequest.Ct02Info.TRNID_LAST_FIELD,
                            TransactionViewRequest.Ct02Info.PAGE_NUM_FIELD,
                            TransactionViewRequest.Ct02Info.NEXT_PAGE_FLG_FIELD,
                            TransactionViewRequest.Ct02Info.TRN_SEL_FLG_FIELD,
                            TransactionViewRequest.Ct02Info.TRN_SELECTED_FIELD);
        }

        @Test
        @DisplayName("WS-MESSAGE is 80 and ERRMSGO is 78, so :520 loses the last two bytes")
        void theMessageIsTruncatedByTwoOnItsWayToTheScreen() {
            assertThat(ParityCase.MessageChannel.WS_MESSAGE_80.fixedWidth())
                    .as("app/cbl/COTRN02C.cbl:37 declares WS-MESSAGE PIC X(80)")
                    .isEqualTo(TransactionViewController.WS_MESSAGE_LENGTH)
                    .isEqualTo(80);
            assertThat(ParityCase.MessageChannel.SCREEN_ERRMSG_78.fixedWidth())
                    .as("the symbolic map declares ERRMSGO PIC X(78)")
                    .isEqualTo(TransactionViewResponse.ERRMSGO_LENGTH)
                    .isEqualTo(78);

            TransactionViewResponse response = new TransactionViewResponse();
            response.setErrmsgo("X".repeat(TransactionViewController.WS_MESSAGE_LENGTH));
            assertThat(response.getErrmsgo())
                    .as("MOVE PIC X(80) TO PIC X(78) keeps the leading 78 characters and discards the rest "
                            + "on the right - a PIC X receiver truncates on the right, a PIC 9 receiver on "
                            + "the left, and a plain Java assignment does neither")
                    .hasSize(TransactionViewResponse.ERRMSGO_LENGTH)
                    .isEqualTo("X".repeat(78));
        }

        @Test
        @DisplayName("the symbolic map projects exactly 21 payload fields and its cursor is metadata")
        void theScreenHasTwentyOnePayloadFields() {
            assertThat(ScreenField.values())
                    .as("app/bms/COTRN02.bms declares 21 DFHMDF fields and app/cpy-bms/COTRN02.CPY declares "
                            + "21 xxxI items; the payload is those and nothing else")
                    .hasSize(TransactionViewResponse.FIELD_COUNT)
                    .hasSize(21);
            assertThat(TransactionViewRequest.ScreenField.values())
                    .as("the two projections of one copybook must agree about how many fields it has")
                    .hasSameSizeAs(ScreenField.values());
            assertThat(TransactionViewRequest.ScreenField.ACTIDIN.lengthItem())
                    .as("the cursor is requested by moving -1 into a length item, and the length item is "
                            + "the field's label plus L - which is metadata, not payload (gate G9)")
                    .isEqualTo(ScreenField.ACTIDIN.label() + LENGTH_ITEM_SUFFIX)
                    .isEqualTo("ACTIDINL");
        }

        @Test
        @DisplayName("the cross-reference record is 50 bytes, of which the fixture supplies only 36")
        void theCardXrefFixtureIsShortByItsFiller() {
            assertThat(CardXrefRecord.RECORD_LENGTH)
                    .as("app/cpy/CVACT03Y.cpy declares 16 + 9 + 11 + FILLER X(14) = 50")
                    .isEqualTo(50);
            assertThat(ParityCase.Normalisation.CARDXREF_FILLER_PAD_36_TO_50.sourceWidth())
                    .as("app/data/ASCII/cardxref.txt measures 36 bytes a row, with the FILLER absent")
                    .isEqualTo(36);
            assertThat(ParityCase.Normalisation.CARDXREF_FILLER_PAD_36_TO_50.targetWidth())
                    .as("so a seeded row is right-padded to the copybook's width before anything reads it "
                            + "(gate G16)")
                    .isEqualTo(CardXrefRecord.RECORD_LENGTH);
            assertThat(ParityCase.Normalisation.CARDXREF_FILLER_PAD_36_TO_50.describes(CCXREF_DATASET))
                    .as("and the normalisation is declared for the binding key this program's cases seed")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Gate G29 - FUNCTION NUMVAL and NUMVAL-C accept and reject exactly as COBOL does")
    class NumericIntrinsicParity {
        @Test
        @DisplayName("NUMVAL accepts a signed integer with surrounding spaces and a trailing CR")
        void numvalAcceptsWhatTheIntrinsicAccepts() {
            assertThat(TransactionViewController.testNumval(ACCOUNT_ID))
                    .as("L204 converts ACTIDINI, which the IS NUMERIC guard at L197 has already forced to "
                            + "eleven digits")
                    .isEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(TransactionViewController.numval(ACCOUNT_ID))
                    .isEqualByComparingTo(new BigDecimal("11"));

            assertThat(TransactionViewController.testNumval("  12345    "))
                    .as("spaces are permitted between the elements of the argument")
                    .isEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(TransactionViewController.numval("  12345    "))
                    .isEqualByComparingTo(new BigDecimal("12345"));

            assertThat(TransactionViewController.numval("-000123"))
                    .as("a leading sign is part of the argument format")
                    .isEqualByComparingTo(new BigDecimal("-123"));
            assertThat(TransactionViewController.numval("12CR"))
                    .as("CR belongs to both intrinsics and means the value is negative")
                    .isEqualByComparingTo(new BigDecimal("-12"));
        }

        @Test
        @DisplayName("NUMVAL rejects a comma, a currency sign, an embedded space and a second point")
        void numvalRejectsWhatTheIntrinsicRejects() {
            assertThat(TransactionViewController.testNumval("1,234"))
                    .as("a digit-grouping comma is a NUMVAL-C extension and is an error here, at the comma")
                    .isEqualTo(2);
            assertThat(TransactionViewController.testNumval("$12"))
                    .as("a currency sign is likewise NUMVAL-C only")
                    .isNotEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(TransactionViewController.testNumval("12 34"))
                    .as("an embedded space ends the digits, so the digit after it is in error")
                    .isEqualTo(4);
            assertThat(TransactionViewController.testNumval("1.2.3"))
                    .as("a second decimal point is in error at the point")
                    .isEqualTo(4);
            assertThat(TransactionViewController.testNumval("(12)"))
                    .as("a parenthesised negative is not an IBM NUMVAL argument at all")
                    .isNotEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(TransactionViewController.testNumval(""))
                    .as("an empty argument holds no digit, so the error is one past its end")
                    .isEqualTo(1);
            assertThat(TransactionViewController.testNumval(" ".repeat(11)))
                    .as("an all-spaces argument likewise holds no digit; this is the state the guard at "
                            + "L196 excludes before the conversion is ever reached")
                    .isEqualTo(12);

            for (String malformed : List.of("1,234", "$12", "12 34", "1.2.3", "(12)", "",
                    " ".repeat(11))) {
                assertThat(TransactionViewController.numval(malformed))
                        .as("a non-conforming argument converts to zero, and never to a guess")
                        .isEqualByComparingTo(BigDecimal.ZERO);
            }
        }

        @Test
        @DisplayName("NUMVAL-C additionally accepts a currency sign and grouping commas, at scale 2")
        void numvalCAcceptsTheCurrencyExtensions() {
            assertThat(TransactionViewController.testNumvalC("+00000012.34"))
                    .as("L383 and L456 convert TRNAMTI, which the positional guard at L340-343 has already "
                            + "forced into the shape [-+]dddddddd.dd")
                    .isEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(scaled(TransactionViewController.numvalC("+00000012.34")))
                    .as("stored into WS-TRAN-AMT-N PIC S9(9)V99 - scale exactly two, truncating, because "
                            + "ROUNDED appears zero times in all twenty-eight programs (rules R2 and R3)")
                    .isEqualTo(new BigDecimal("12.34"));
            assertThat(scaled(TransactionViewController.numvalC("-00000012.34")))
                    .isEqualTo(new BigDecimal("-12.34"));
            assertThat(scaled(TransactionViewController.numvalC("$1,234.56")))
                    .as("the currency sign and the grouping comma are the two things NUMVAL-C adds")
                    .isEqualTo(new BigDecimal("1234.56"));
            assertThat(scaled(TransactionViewController.numvalC("12.34DB")))
                    .as("DB, like CR, marks the value negative")
                    .isEqualTo(new BigDecimal("-12.34"));
            assertThat(scaled(TransactionViewController.numvalC("+99999999.99")))
                    .as("the widest amount the eight-digit mask at L385 can re-display")
                    .isEqualTo(new BigDecimal("99999999.99"));
        }

        @Test
        @DisplayName("NUMVAL-C rejects a parenthesised negative, an embedded space and a second point")
        void numvalCRejectsWhatTheIntrinsicRejects() {
            assertThat(TransactionViewController.testNumvalC("(12.34)"))
                    .as("parentheses are not part of the IBM NUMVAL-C argument format either")
                    .isNotEqualTo(TransactionViewController.NUMVAL_CONFORMS);
            assertThat(TransactionViewController.testNumvalC("12 .34"))
                    .isEqualTo(4);
            assertThat(TransactionViewController.testNumvalC("1.2.3"))
                    .isEqualTo(4);
            assertThat(TransactionViewController.testNumvalC(""))
                    .isEqualTo(1);
            assertThat(TransactionViewController.testNumvalC(" ".repeat(12)))
                    .isEqualTo(13);
        }

        @Test
        @DisplayName("the positional amount guard, not the conversion, is what the operator sees")
        void theShapeGuardRunsBeforeTheConversion() {
            assertThat(TransactionViewController.isMalformedAmount("+00000012.34")).isFalse();
            assertThat(TransactionViewController.isMalformedAmount("-00000012.34")).isFalse();
            assertThat(TransactionViewController.isMalformedAmount("100000012.34"))
                    .as("L340 - (1:1) must be '-' or '+', so a digit in the sign position is rejected")
                    .isTrue();
            assertThat(TransactionViewController.isMalformedAmount("+0000001234"))
                    .as("L342 - (10:1) must be '.', so an amount with no point is rejected")
                    .isTrue();
            assertThat(TransactionViewController.isMalformedDate(VALID_DATE)).isFalse();
            assertThat(TransactionViewController.isMalformedDate("2022/07/18"))
                    .as("L357 and L359 - the two separators must be hyphens")
                    .isTrue();
            assertThat(TransactionViewController.isMalformedDate(REJECTED_DATE))
                    .as("month 13 is numerically well formed, which is exactly why CSUTLDTC is called at "
                            + "all - the positional guard cannot see it")
                    .isFalse();
        }

        private BigDecimal scaled(BigDecimal value) {
            assertThat(CobolDecimal.COBOL_ROUNDING)
                    .as("the only faithful mode, because ROUNDED never appears in the source (gate G24)")
                    .isEqualTo(RoundingMode.DOWN);
            assertThat(CobolDecimal.MONETARY_SCALE)
                    .as("CVTRA05Y declares TRAN-AMT PIC S9(09)V99 and COTRN02C declares WS-TRAN-AMT-N "
                            + "PIC S9(9)V99 - the same scale twice")
                    .isEqualTo(TranRecord.TRAN_AMT_SCALE)
                    .isEqualTo(2);
            return CobolDecimal.storeMonetary(value);
        }
    }

    @Nested
    @DisplayName("The CSUTLDTC acceptance rule - severity '0000', or message 2513 tolerated")
    class CsutldtcAcceptanceRule {
        private final DateUtilityJob dateUtility = new DateUtilityJob(ParityHarness.FIXTURE_CHARSET);

        @Test
        @DisplayName("a valid date reports severity '0000' and 'Date is valid'")
        void aValidDateIsAcceptedOutright() {
            DateUtilityJob.DateValidationResult result =
                    dateUtility.validateDate(VALID_DATE, TransactionViewController.WS_DATE_FORMAT);

            assertThat(result.severityCode())
                    .as("app/cbl/COTRN02C.cbl:397 and :417 - IF CSUTLDTC-RESULT-SEV-CD = '0000' CONTINUE")
                    .isEqualTo(TransactionViewController.CSUTLDTC_SEVERITY_OK);
            assertThat(result.result())
                    .as("FC-INVALID-DATE is the token that means valid, and its 15-character text is "
                            + "pinned in full by CSUTLDTCParityTest")
                    .isEqualTo("Date is valid  ");
            assertThat(result.returnCode())
                    .as("MOVE WS-SEVERITY-N TO RETURN-CODE - zero for a valid date")
                    .isZero();
            assertThat(result.message())
                    .as("the 80-byte WS-MESSAGE image: 4 + 11 + 4 + 1 + 15 + 1 + 9 + 10 + 1 + 10 + 1 + 3")
                    .hasSize(DateUtilityJob.LS_RESULT_LENGTH)
                    .hasSize(80);
        }

        @Test
        @DisplayName("message 2513 is an error and both call sites tolerate it anyway")
        void theUnsupportedRangeErrorIsTolerated() {
            DateUtilityJob.DateValidationResult result =
                    dateUtility.validateDate(TOLERATED_DATE, TransactionViewController.WS_DATE_FORMAT);

            assertThat(result.severityCode())
                    .as("severity 3, so the '0000' arm is not taken")
                    .isNotEqualTo(TransactionViewController.CSUTLDTC_SEVERITY_OK);
            assertThat(result.messageNumber())
                    .as("app/cbl/CSUTLDTC.cbl:66 declares 88 FC-UNSUPP-RANGE VALUE X'000309D1...', whose "
                            + "second halfword X'09D1' is decimal 2513")
                    .isEqualTo(TransactionViewController.CSUTLDTC_TOLERATED_MESSAGE_NUMBER);
            assertThat(result.result()).isEqualTo("Unsupp. Range  ");
            assertThat(result.returnCode()).isEqualTo(3);

            ProgramState state = withController(masterHoldingOneRecord(),
                    crossReferenceResolvingTheAccount(),
                    controller -> controller.mainPara(confirmedAdd(TOLERATED_DATE, TOLERATED_DATE)));

            assertThat(state.errFlagOn())
                    .as("a tolerated date raises no error flag")
                    .isFalse();
            assertThat(state.writtenRecords())
                    .as("and the add completes, which is the whole content of the tolerance")
                    .hasSize(1);
        }

        @Test
        @DisplayName("any other feedback token rejects, on each of the two call sites separately")
        void anyOtherTokenRejects() {
            DateUtilityJob.DateValidationResult result =
                    dateUtility.validateDate(REJECTED_DATE, TransactionViewController.WS_DATE_FORMAT);

            assertThat(result.messageNumber())
                    .as("FC-INVALID-MONTH is X'000309D5', whose second halfword is decimal 2517")
                    .isEqualTo("2517");
            assertThat(result.result()).isEqualTo("Invalid month  ");

            withController(masterHoldingOneRecord(), crossReferenceResolvingTheAccount(), controller -> {
                ProgramState origin = controller.mainPara(confirmedAdd(REJECTED_DATE, VALID_DATE));
                assertThat(origin.message())
                        .as(":405-406 - the first call site's own literal")
                        .startsWith(TransactionViewController.MSG_ORIG_DATE_INVALID);
                assertThat(origin.cursorRequestedOn(ScreenField.TORIGDT))
                        .as(":408 - MOVE -1 TO TORIGDTL")
                        .isTrue();

                ProgramState processing = controller.mainPara(confirmedAdd(VALID_DATE, REJECTED_DATE));
                assertThat(processing.message())
                        .as(":425-426 - the second call site's own literal, which is a different string")
                        .startsWith(TransactionViewController.MSG_PROC_DATE_INVALID);
                assertThat(processing.cursorRequestedOn(ScreenField.TPROCDT))
                        .as(":428 - MOVE -1 TO TPROCDTL")
                        .isTrue();
                return null;
            });
        }
    }

    @Nested
    @DisplayName("Gate G30 - EVALUATE EIBAID keeps the source's order with WHEN OTHER last")
    class AidDispatch {
        @Test
        @DisplayName("the four named arms are the four the source names, and nothing else matches")
        void onlyTheFourNamedKeysMatchAnArm() {
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHENTER)).isTrue();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3)).isTrue();
            assertThat(PfKeyResolver.isPf4(CicsAid.DFHPF4)).isTrue();
            assertThat(PfKeyResolver.isPf5(CicsAid.DFHPF5)).isTrue();

            for (byte aid : new byte[] {CicsAid.DFHNULL, CicsAid.DFHCLEAR, CicsAid.DFHPA1,
                    CicsAid.DFHPF1, CicsAid.DFHPF7, CicsAid.DFHPF12}) {
                assertThat(PfKeyResolver.isEnter(aid) || PfKeyResolver.isPf3(aid)
                        || PfKeyResolver.isPf4(aid) || PfKeyResolver.isPf5(aid))
                        .as("EIBAID 0x%02X must fall to WHEN OTHER at :148. COTRN02C tests EIBAID inline "
                                + "rather than through CSSTRPFY, and the shared resolver has to reproduce "
                                + "those inline tests as identical boolean outcomes", aid)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("PF3 with no caller falls back to the main menu, and with one echoes it")
        void pf3ResolvesItsTargetFromTheCommarea() {
            withController(masterHoldingOneRecord(), crossReferenceResolvingTheAccount(), controller -> {
                ProgramState blankCaller = controller.mainPara(reentryWith("DFHPF3"));
                assertThat(blankCaller.response().getNextProgram())
                        .as(":137-138 - a blank CDEMO-FROM-PROGRAM defaults the target to COMEN01C")
                        .isEqualTo(TransactionViewController.MAIN_MENU_PROGRAM);
                assertThat(blankCaller.transferred())
                        .as("the PF3 arm reaches the XCTL at :508-511 and never the RETURN at :530")
                        .isTrue();
                assertThat(blankCaller.screenSent())
                        .as("no map is sent before transferring - the target paints its own")
                        .isFalse();
                assertThat(namedOrNone(blankCaller.response().getNextMapset()))
                        .as("and the mapset is blanked rather than left naming the screen being left")
                        .isNull();

                TransactionViewRequest fromMenu = reentryWith("DFHPF3");
                fromMenu.setNavigationContext(NavigationContext.empty()
                        .withPgmReenter()
                        .withFromProgram(TransactionViewController.MAIN_MENU_PROGRAM));
                assertThat(controller.mainPara(fromMenu).response().getNextProgram())
                        .as(":140-141 - a named caller is echoed back as the target")
                        .isEqualTo(TransactionViewController.MAIN_MENU_PROGRAM);

                ProgramState coldStart = controller.mainPara(new TransactionViewRequest());
                assertThat(coldStart.response().getNextProgram())
                        .as(":115-116 - no commarea, so the program abandons the transaction for signon")
                        .isEqualTo(TransactionViewController.SIGN_ON_PROGRAM);
                assertThat(coldStart.transferred()).isTrue();
                return null;
            });
        }

        @Test
        @DisplayName("an unnamed key takes WHEN OTHER and reports the invalid-key message")
        void whenOtherReportsTheInvalidKeyMessage() {
            withController(masterHoldingOneRecord(), crossReferenceResolvingTheAccount(), controller -> {
                ProgramState state = controller.mainPara(reentryWith("DFHPF12"));

                assertThat(state.errFlagOn())
                        .as(":149 - MOVE 'Y' TO WS-ERR-FLG")
                        .isTrue();
                assertThat(state.message())
                        .as(":150 - CCDA-MSG-INVALID-KEY moved into PIC X(80)")
                        .hasSize(TransactionViewController.WS_MESSAGE_LENGTH)
                        .startsWith("Invalid key pressed. Please see below...");
                assertThat(state.returned())
                        .as(":151 sends the screen, and the send is terminal at :530")
                        .isTrue();
                assertThat(state.response().getNextProgram())
                        .as("EXEC CICS RETURN TRANSID('CT02'), and the CSD binds CT02 to this program")
                        .isEqualTo(TransactionViewController.PROGRAM_NAME);
                assertThat(state.response().getNextMapset())
                        .as("the screen is named on a path that sends one")
                        .isEqualTo(TransactionViewResponse.MAPSET_NAME);
                return null;
            });
        }

        @Test
        @DisplayName("PF4 clears every input field and puts the cursor back on the account field")
        void pf4ClearsTheForm() {
            withController(masterHoldingOneRecord(), crossReferenceResolvingTheAccount(), controller -> {
                TransactionViewRequest filled = confirmedAdd(VALID_DATE, VALID_DATE);
                filled.setAid(aidTokenOf("DFHPF4"));

                ProgramState state = controller.mainPara(filled);

                assertThat(state.errFlagOn())
                        .as(":754-757 - CLEAR-CURRENT-SCREEN raises no error flag; it is not a rejection")
                        .isFalse();
                assertThat(state.message())
                        .as(":779 - WS-MESSAGE is one of the fifteen receivers INITIALIZE-ALL-FIELDS blanks")
                        .isEqualTo(" ".repeat(TransactionViewController.WS_MESSAGE_LENGTH));
                assertThat(state.actidinI()).isBlank();
                assertThat(state.confirmI()).isBlank();
                assertThat(state.cursorRequestedOn(ScreenField.ACTIDIN))
                        .as(":763 - MOVE -1 TO ACTIDINL")
                        .isTrue();
                assertThat(state.writtenRecords())
                        .as("clearing the form is not adding a transaction")
                        .isEmpty();
                return null;
            });
        }
    }

    @Nested
    @DisplayName("Gate G38 - this screen highlights in neither state; gate G37 - it keeps no session")
    class HighlightingAndStatelessness {
        @Test
        @DisplayName("the CSSETATY decision is 'touch nothing' on both ENTER and REENTER")
        void neverHighlightsInEitherState() {
            for (boolean reenter : new boolean[] {false, true}) {
                FieldHighlight decision = FieldAttributeSetter.resolveFromFlags(false, false, reenter);

                assertThat(decision.untouched())
                        .as("COTRN02C does not copy CSSETATY - its copybook list at :71-93 does not name "
                                + "it - and declares no FLG-<field>-NOT-OK pair, so with reenter=%s the "
                                + "resolver writes no byte. Gate G38 asks that highlighting apply only in "
                                + "REENTER; this program satisfies it by applying none in either state",
                                reenter)
                        .isTrue();
                assertThat(decision.colourItemAssigned()).isFalse();
                assertThat(decision.outputItemAssigned()).isFalse();
            }
        }

        @Test
        @DisplayName("no rejecting path ever colours a field, and the one colour it sets is DFHGREEN")
        void theOnlyAttributeAssignmentIsTheSuccessLine() {
            withController(masterHoldingOneRecord(), crossReferenceResolvingTheAccount(), controller -> {
                for (TransactionViewRequest rejected : List.of(reentryWith("DFHENTER"),
                        reentryWith("DFHPF12"), confirmedAddWithout(ScreenField.TDESC))) {
                    ProgramState state = controller.mainPara(rejected);
                    assertThat(state.response().getMetadata(ScreenField.ERRMSG).getColour())
                            .as("no rejecting arm of this program moves an attribute byte, so ERRMSGC "
                                    + "holds the map's default colour 0x%02X", BmsAttributes.DFHDFCOL)
                            .isEqualTo(BmsAttributes.DFHDFCOL);
                    assertThat(state.response().getMetadata(ScreenField.ACTIDIN).getColour())
                            .as("and DFHRED (0x%02X) never reaches a field, because CSSETATY would write "
                                    + "its asterisk into storage the operator's own value occupies",
                                    BmsAttributes.DFHRED)
                            .isNotEqualTo(BmsAttributes.DFHRED);
                }

                ProgramState added = controller.mainPara(confirmedAdd(VALID_DATE, VALID_DATE));
                assertThat(added.response().getMetadata(ScreenField.ERRMSG).getColour())
                        .as(":727 - MOVE DFHGREEN TO ERRMSGC OF COTRN2AO, the program's only attribute "
                                + "assignment anywhere, reproduced from IBM CICS documentation because "
                                + "DFHBMSCA is absent from this repository (risk R-D)")
                        .isEqualTo(BmsAttributes.DFHGREEN);
                assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHGREEN))
                        .as("and a case pins it by mnemonic rather than by byte")
                        .isEqualTo("DFHGREEN");
                return null;
            });
        }

        @Test
        @DisplayName("two runs of one controller cannot see each other - WORKING-STORAGE is per task")
        void theControllerKeepsNoStateBetweenRuns() {
            withController(masterHoldingOneRecord(), crossReferenceResolvingTheAccount(), controller -> {
                ProgramState first = controller.mainPara(confirmedAdd(VALID_DATE, VALID_DATE));
                ProgramState second = controller.mainPara(reentryWith("DFHENTER"));

                assertThat(first.writtenRecords()).hasSize(1);
                assertThat(second.writtenRecords())
                        .as("the second run was rejected at :224-229 and never reached the write")
                        .isEmpty();
                assertThat(second.message())
                        .as(":225-227 - Account or Card Number must be entered...")
                        .startsWith(TransactionViewController.MSG_KEY_REQUIRED);
                assertThat(second.actidinI())
                        .as("a shared field would show the first run's normalised account id here")
                        .isNotEqualTo(ACCOUNT_ID);
                assertThat(second.response())
                        .as("each run builds its own response, so the two are distinct objects")
                        .isNotSameAs(first.response());
                return null;
            });
        }

        private TransactionViewRequest confirmedAddWithout(ScreenField blanked) {
            TransactionViewRequest request = confirmedAdd(VALID_DATE, VALID_DATE);
            request.setPayloadValue(TransactionViewRequest.ScreenField.valueOf(blanked.name()),
                    TransactionViewRequest.spaces(blanked.width()));
            return request;
        }
    }

    @Nested
    @DisplayName("Gate G47 - every browse and write outcome the source names is reachable")
    class BrowseAndWriteOutcomes {
        @Test
        @DisplayName("an empty master reports ENDFILE, which zeros TRAN-ID and yields identifier 1")
        void anEmptyMasterIsLoadable() {
            TransactionRepository transactions = mock(TransactionRepository.class);
            TransactionRepository.Browse browse = positionedBrowse();
            when(browse.readPrev()).thenReturn(TransactionRepository.ReadResult
                    .endOfFile(TransactionRepository.CICS_FILE_NAME));
            when(transactions.startBrowse(TransactionRepository.BrowseDirection.BACKWARD))
                    .thenReturn(browse);
            when(transactions.write(any())).thenReturn(TransactionRepository.WriteResult
                    .written(TransactionRepository.CICS_FILE_NAME));

            ProgramState state = withController(transactions, crossReferenceResolvingTheAccount(),
                    controller -> controller.mainPara(confirmedAdd(VALID_DATE, VALID_DATE)));

            assertThat(state.errFlagOn())
                    .as(":688-689 - WHEN DFHRESP(ENDFILE) MOVE ZEROS TO TRAN-ID raises no error flag, and "
                            + "getting that wrong is the difference between an empty master being loadable "
                            + "and being permanently unloadable")
                    .isFalse();
            assertThat(state.tranRecord().tranId())
                    .as(":448-449 - zero plus one, at the sixteen-digit width of TRAN-ID")
                    .isEqualTo("0000000000000001");
            assertThat(state.writtenRecords()).hasSize(1);
        }

        @Test
        @DisplayName("a refused READPREV displays RESP and REAS, rejects, and never reaches the write")
        void theReadprevWhenOtherArmDisplaysAndRejects() {
            TransactionRepository transactions = mock(TransactionRepository.class);
            TransactionRepository.Browse browse = positionedBrowse();
            when(browse.readPrev()).thenReturn(TransactionRepository.ReadResult
                    .other(TransactionRepository.CICS_FILE_NAME, UNEXPECTED_STATUS));
            when(transactions.startBrowse(TransactionRepository.BrowseDirection.BACKWARD))
                    .thenReturn(browse);

            ProgramState state = withController(transactions, crossReferenceResolvingTheAccount(),
                    controller -> controller.mainPara(confirmedAdd(VALID_DATE, VALID_DATE)));

            assertThat(state.displayLines())
                    .as(":691 - DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD, the same nine-character "
                            + "rendering the write's own WHEN OTHER arm uses")
                    .hasSize(1);
            assertThat(state.message())
                    .as(":692-695 - the literal is the one STARTBR's WHEN OTHER arm also carries. READPREV "
                            + "declares no DFHRESP(NOTFND) arm of its own, so a not-found lands here too")
                    .startsWith(TransactionViewController.MSG_TRANSACTION_LOOKUP_FAILED);
            assertThat(state.cursorRequestedOn(ScreenField.ACTIDIN))
                    .as(":696 - MOVE -1 TO ACTIDINL OF COTRN2AI")
                    .isTrue();
            assertThat(state.errFlagOn())
                    .as(":693 - MOVE 'Y' TO WS-ERR-FLG, unlike the ENDFILE arm above which raises none")
                    .isTrue();
            assertThat(state.writtenRecords())
                    .as(":697 PERFORM SEND-TRNADD-SCREEN, whose EXEC CICS RETURN at :530 ends the task, so "
                            + "ADD-TRANSACTION never reaches its WRITE at :713")
                    .isEmpty();
            verify(transactions, never()).write(any());
        }

        @Test
        @DisplayName("the two unreachable STARTBR arms are still rendered, with their own literals")
        void bothRejectingPositioningArmsExist() {
            withController(masterHoldingOneRecord(), crossReferenceResolvingTheAccount(), controller -> {
                for (Map.Entry<FileStatus.Outcome, String> arm : Map.of(
                        FileStatus.Outcome.NOT_FOUND,
                        TransactionViewController.MSG_TRANSACTION_ID_NOT_FOUND,
                        FileStatus.Outcome.OTHER,
                        TransactionViewController.MSG_TRANSACTION_LOOKUP_FAILED).entrySet()) {
                    ProgramState state = new ProgramState(new FixedWidthCodec(
                            ParityHarness.FIXTURE_CHARSET));

                    controller.startbrOutcome(state, arm.getKey());

                    assertThat(state.message())
                            .as(":655-667 - positioning reports NORMAL through the repository, so these two "
                                    + "arms are unreachable from a parity case and are exercised here "
                                    + "instead. Deleting either would lose a literal the source carries")
                            .startsWith(arm.getValue());
                    assertThat(state.cursorRequestedOn(ScreenField.ACTIDIN))
                            .as("both place the cursor on ACTIDINL - the account field, because the map has "
                                    + "no transaction field to place it on")
                            .isTrue();
                }
                return null;
            });
        }

        @Test
        @DisplayName("a refused write displays RESP and REAS and reports 'Unable to Add Transaction...'")
        void theWriteWhenOtherArmDisplaysAndRejects() {
            TransactionRepository transactions = masterHoldingOneRecord();
            when(transactions.write(any())).thenReturn(TransactionRepository.WriteResult
                    .other(TransactionRepository.CICS_FILE_NAME, UNEXPECTED_STATUS));

            ProgramState state = withController(transactions, crossReferenceResolvingTheAccount(),
                    controller -> controller.mainPara(confirmedAdd(VALID_DATE, VALID_DATE)));

            assertThat(state.displayLines())
                    .as(":743 - DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD, both PIC S9(09) COMP so "
                            + "both render as nine characters")
                    .hasSize(1);
            assertThat(state.displayLines().get(0))
                    .startsWith(TransactionViewController.DISPLAY_RESP_PREFIX)
                    .contains(TransactionViewController.DISPLAY_REAS_PREFIX)
                    .hasSize(TransactionViewController.DISPLAY_RESP_PREFIX.length()
                            + TransactionViewController.DISPLAY_REAS_PREFIX.length()
                            + 2 * TransactionViewController.WS_RESP_CD_DIGITS);
            assertThat(state.message())
                    .as(":744-748")
                    .startsWith(TransactionViewController.MSG_UNABLE_TO_ADD);
            assertThat(state.writtenRecords())
                    .as("the record was handed over and REFUSED, so TRANSACT gained nothing and the "
                            + "WRITES channel this list feeds carries nothing. The record the program "
                            + "composed is still asserted - on the argument of the call, by "
                            + "transaction.TransactionViewControllerTest - because that is an observation "
                            + "about the program rather than about the dataset")
                    .isEmpty();
        }

        @Test
        @DisplayName("a duplicate write reports 'Tran ID already exist...' and displays nothing")
        void theWriteDuplicateArmRejectsWithoutDisplaying() {
            TransactionRepository transactions = masterHoldingOneRecord();
            when(transactions.write(any())).thenReturn(TransactionRepository.WriteResult
                    .duplicate(TransactionRepository.CICS_FILE_NAME));

            ProgramState state = withController(transactions, crossReferenceResolvingTheAccount(),
                    controller -> controller.mainPara(confirmedAdd(VALID_DATE, VALID_DATE)));

            assertThat(state.message())
                    .as(":735-741 - DUPKEY and DUPREC are two WHEN clauses over one action")
                    .startsWith(TransactionViewController.MSG_TRAN_ID_ALREADY_EXISTS);
            assertThat(state.displayLines())
                    .as("that arm carries no DISPLAY, unlike the WHEN OTHER arm below it")
                    .isEmpty();
            assertThat(state.cursorRequestedOn(ScreenField.ACTIDIN)).isTrue();
        }

        @Test
        @DisplayName("an incoherent forced outcome is refused rather than quietly mapped")
        void anIncoherentForcedOutcomeIsRefused() {
            assertThatThrownBy(() -> ParityHarness.usAscii().run(
                    syntheticConfirmedAdd(ParityCase.RepositoryOperation.WRITE,
                            FileStatus.Outcome.OK),
                    UNIT_KIND, COTRN02CParityTest::execute))
                    .as("a case that forced a successful write would suppress the duplicate check and "
                            + "leave its own final-state expectation describing a row that was never "
                            + "inserted - a false pass with a plausible-looking case file behind it")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("forced the OK outcome for the TRANSACT write");

            assertThatThrownBy(() -> ParityHarness.usAscii().run(
                    syntheticConfirmedAdd(ParityCase.RepositoryOperation.READ,
                            FileStatus.Outcome.DUPLICATE),
                    UNIT_KIND, COTRN02CParityTest::execute))
                    .as("and a case that forced a cross-reference read to carry a record would have to "
                            + "invent the 50 bytes of it, whose card number would then reach "
                            + "TRAN-CARD-NUM")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("forced the DUPLICATE outcome for the cross-reference read");
        }

        @Test
        @DisplayName("a forced outcome nothing asks for fails the run rather than passing quietly")
        void anUnconsumedForcedOutcomeFailsTheRun() {
            assertThatThrownBy(() -> ParityHarness.usAscii().run(
                    syntheticConfirmedAdd(ParityCase.RepositoryOperation.REWRITE,
                            FileStatus.Outcome.OTHER),
                    UNIT_KIND, COTRN02CParityTest::execute))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("that nothing asked for during the run");
        }

        private ParityCase syntheticConfirmedAdd(ParityCase.RepositoryOperation operation,
                                                 FileStatus.Outcome outcome) {
            Map<String, ParityCase.DatasetInput> inputs = Map.of(CCXREF_DATASET,
                    ParityCase.DatasetInput.ofRows(List.of(
                            CARD_NUMBER + "123456789" + ACCOUNT_ID)));
            return new ParityCase(PROGRAM, ParityHarness.caseId(1),
                    "A synthetic case used only to reach the adapter's refusal of an incoherent or "
                            + "unconsumed forced outcome; it is never loaded from a resource and never "
                            + "judged, because a case that provokes a refusal can never report zero.",
                    UNIT_KIND, inputs, Map.of(),
                    new ParityCase.ScreenRequest(ProgramState.PASSED_COMMAREA_LENGTH, "DFHENTER",
                            PINNED_INSTANT.atZone(ZoneOffset.UTC).toLocalDateTime().toString(),
                            ParityHarness.FIXTURE_CHARSET.name(),
                            Map.of(NavigationContext.PGM_CONTEXT_FIELD,
                                    String.valueOf(NavigationContext.PGM_CONTEXT_REENTER)),
                            receivedFields(),
                            Map.of(operation, new ParityCase.ForcedOutcome(outcome, null, null))),
                    new ParityCase.ExpectedResponse(TransactionViewController.PROGRAM_NAME,
                            TransactionViewResponse.MAPSET_NAME, TransactionViewResponse.MAP_NAME,
                            Map.of(), List.of(), null, ParityCase.Termination.RETURN_TRANSID),
                    List.of(), List.of(), 0, List.of(),
                    List.of(new ParityCase.DatasetNormalisation(CCXREF_DATASET,
                            ParityCase.Normalisation.CARDXREF_FILLER_PAD_36_TO_50)));
        }

        private Map<String, String> receivedFields() {
            Map<String, String> received = new LinkedHashMap<>();
            received.put(TransactionViewRequest.ScreenField.ACTIDIN.inputItem(), ACCOUNT_ID);
            received.put(TransactionViewRequest.ScreenField.TTYPCD.inputItem(), "01");
            received.put(TransactionViewRequest.ScreenField.TCATCD.inputItem(), "0001");
            received.put(TransactionViewRequest.ScreenField.TRNSRC.inputItem(), "POS TERM  ");
            received.put(TransactionViewRequest.ScreenField.TDESC.inputItem(),
                    "Coffee and a newspaper");
            received.put(TransactionViewRequest.ScreenField.TRNAMT.inputItem(), "+00000012.34");
            received.put(TransactionViewRequest.ScreenField.TORIGDT.inputItem(), VALID_DATE);
            received.put(TransactionViewRequest.ScreenField.TPROCDT.inputItem(), VALID_DATE);
            received.put(TransactionViewRequest.ScreenField.MID.inputItem(), "123456789");
            received.put(TransactionViewRequest.ScreenField.MNAME.inputItem(), "Kwik-E-Mart");
            received.put(TransactionViewRequest.ScreenField.MCITY.inputItem(), "Springfield");
            received.put(TransactionViewRequest.ScreenField.MZIP.inputItem(), "0000012345");
            received.put(TransactionViewRequest.ScreenField.CONFIRM.inputItem(), "Y");
            return received;
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
