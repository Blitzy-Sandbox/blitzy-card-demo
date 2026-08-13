package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardSelectController;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest;
import com.vsergeychik.carddemo.card.dto.CardSelectResponse;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.ForcedOutcome;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.RepositoryOperation;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * The behavioural-parity gate for {@code COCRDSLC} - the credit-card detail screen, CSD transaction
 * {@code CCDL}, projected as {@code GET /api/cards/&#123;cardNum&#125;} onto
 * {@link com.vsergeychik.carddemo.card.CardSelectController}.
 */
final class COCRDSLCParityTest {
    private static final String PROGRAM = "COCRDSLC";

    private static final String CARDDAT = CardRepository.BASE_DD_NAME;

    private static final int REQUIRED_CASES = ParityHarness.CASES_PER_PROGRAM;

    private static final Charset FIXTURE_CHARSET = StandardCharsets.US_ASCII;

    private static final String LENGTH_ITEM_SUFFIX = "L";

    private static final String COLOUR_ITEM_SUFFIX = "C";

    private static final String HIGHLIGHT_ITEM_SUFFIX = "H";

    private static final Map<Byte, String> AID_MNEMONICS = Map.copyOf(CicsAid.mnemonicsByAid());

    private static List<ParityCase> cases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);
        if (loaded.size() != REQUIRED_CASES) {
            throw new IllegalStateException("Parity gate for " + PROGRAM + " requires exactly "
                    + REQUIRED_CASES + " cases under src/test/resources/parity/" + PROGRAM
                    + "/, named case01 through case" + REQUIRED_CASES + ", but " + loaded.size()
                    + " loaded. A missing case does not weaken the gate - it removes it, because an "
                    + "assertion nobody runs cannot fail.");
        }
        for (int ordinal = 1; ordinal <= REQUIRED_CASES; ordinal++) {
            String expected = ParityHarness.caseId(ordinal);
            String actual = loaded.get(ordinal - 1).caseId();
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Parity case " + ordinal + " for " + PROGRAM
                        + " is \"" + actual + "\" where the gate requires \"" + expected
                        + "\". The identifiers are the file-name stems and they are ordered, so a "
                        + "mis-numbered file would silently take another case's place.");
            }
        }
        return loaded;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("COCRDSLC: every case diffs to zero against the COBOL-derived baseline")
    void theTranslationMatchesTheCobolFieldForField(ParityCase parityCase) {
        DiffResult result = ParityHarness.usAscii()
                .judge(parityCase, UnitKind.CONTROLLER_POJO, COCRDSLCParityTest::invoke);

        assertThat(result.count())
                .describedAs("%s/%s must diff to zero. %s", PROGRAM, parityCase.caseId(),
                        result.render())
                .isZero();
    }

    private static UnitOutcome invoke(Invocation invocation) {
        SeededDataset seeded = invocation.dataset(CARDDAT);
        CardSelectRequest request = requestOf(invocation);
        String pathCardNumber = pathCardNumberOf(invocation);

        CardSelectController controller = new CardSelectController(
                seededCardRepository(invocation, seeded), invocation.clock(), FIXTURE_CHARSET);

        ScreenResponse<CardSelectResponse> body = controller.viewCardDetail(
                pathCardNumber,
                request,
                null,
                invocation.eibcalen(),
                Byte.toUnsignedInt(aidByteOf(invocation))).getBody();
        CardSelectResponse painted = Objects.requireNonNull(body, "COCRDSLC always ends in EXEC CICS "
                + "RETURN, so viewCardDetail always answers 200 OK with a body").screen();

        UnitOutcome.Builder recorder = invocation.recorder();
        recorder.finalStateUnchanged(seeded, CardRecord.LAYOUT);
        recorder.response(observed(painted));
        emitPlainText(painted, recorder);
        recorder.returnCode(0);
        return recorder.build();
    }

    private static ObservedResponse observed(CardSelectResponse painted) {
        String nextProgram = tokenOrAbsent(painted.getNextProgram());
        String nextMapset = tokenOrAbsent(painted.getNextMapset());
        String nextMap = tokenOrAbsent(painted.getNextMap());

        List<ObservedSend> sends = new ArrayList<>(1);
        if (nextMap != null) {
            sends.add(new ObservedSend(painted.fieldImages(), attributeMnemonics(painted)));
        }

        String cursorField = painted.getCursorField() == null
                ? null
                : painted.getCursorField() + LENGTH_ITEM_SUFFIX;

        Termination termination;
        if (nextProgram != null && sends.isEmpty()) {
            termination = Termination.XCTL;
        } else if (sentPlainText(painted)) {
            termination = Termination.RETURN_NO_TRANSID;
        } else {
            termination = Termination.RETURN_TRANSID;
        }

        return new ObservedResponse(nextProgram, nextMapset, nextMap,
                navigation(painted), sends, cursorField, termination);
    }

    private static Map<String, String> navigation(CardSelectResponse painted) {
        return NavigationImage.of(painted.getNavigationContext());
    }

    private static Map<String, String> attributeMnemonics(CardSelectResponse painted) {
        Map<String, String> items = new LinkedHashMap<>();
        for (CardSelectResponse.ScreenField field : CardSelectResponse.ScreenField.values()) {
            CardSelectResponse.FieldAttributes quad = painted.attributes(field);
            items.put(field.dfhmdfLabel() + COLOUR_ITEM_SUFFIX, colourMnemonic(quad.getColour()));
            items.put(field.dfhmdfLabel() + HIGHLIGHT_ITEM_SUFFIX,
                    highlightMnemonic(quad.getHilight()));
        }
        return items;
    }

    private static String colourMnemonic(byte colour) {
        String mnemonic = BmsAttributes.COLOUR_MNEMONICS.get(colour);
        if (mnemonic == null) {
            throw new IllegalStateException(String.format(
                    "COCRDSLC moved 0x%02X into an extended-colour item, which is not one of the eight "
                            + "DFHBMSCA colours. A colour a case cannot name is a colour nobody can "
                            + "assert, so this is reported rather than rendered as a raw byte.",
                    Byte.toUnsignedInt(colour)));
        }
        return mnemonic;
    }

    private static String highlightMnemonic(byte highlight) {
        String mnemonic = BmsAttributes.HIGHLIGHT_MNEMONICS.get(highlight);
        if (mnemonic == null) {
            throw new IllegalStateException(String.format(
                    "COCRDSLC left 0x%02X in an extended-highlight item, which is not one of the four "
                            + "DFHBMSCA highlights. The program writes no highlight at all, so anything "
                            + "other than DFHDFHI is itself the finding.",
                    Byte.toUnsignedInt(highlight)));
        }
        return mnemonic;
    }

    private static String tokenOrAbsent(String token) {
        return token == null || token.isBlank() ? null : token.trim();
    }

    private static void emitPlainText(CardSelectResponse painted, UnitOutcome.Builder recorder) {
        if (!sentPlainText(painted)) {
            return;
        }
        recorder.message(new EmittedMessage(MessageChannel.DISPLAY_LINE,
                painted.getErrmsgo().substring(0, CardScreenState.CCARD_RETURN_MSG_LENGTH)));
    }

    private static boolean sentPlainText(CardSelectResponse painted) {
        boolean sentMap = tokenOrAbsent(painted.getNextMap()) != null;
        boolean transferred = tokenOrAbsent(painted.getNextProgram()) != null;
        boolean errorLineUntouched = painted.getErrmsgo().equals(
                CardScreenState.lowValues(CardSelectResponse.ERRMSGO_LENGTH));
        return !sentMap && !transferred && !errorLineUntouched;
    }

    private static CardSelectRequest requestOf(Invocation invocation) {
        Map<String, String> commarea = invocation.commarea();
        if (commarea.isEmpty()) {
            return null;
        }
        CardSelectRequest request = new CardSelectRequest();
        Map<String, String> mapFields = invocation.mapFields();
        String accountFilter = mapFields.get(
                CardSelectRequest.ScreenField.ACCTSID.symbolicItemName());
        if (accountFilter != null) {
            request.setAcctsid(accountFilter);
        }
        String cardFilter = mapFields.get(CardSelectRequest.ScreenField.CARDSID.symbolicItemName());
        if (cardFilter != null) {
            request.setCardsid(cardFilter);
        }
        request.setNavigationContext(NavigationImage.from(commarea));
        return request;
    }

    private static String pathCardNumberOf(Invocation invocation) {
        String typed = invocation.mapFields()
                .get(CardSelectRequest.ScreenField.CARDSID.symbolicItemName());
        return typed == null ? CardSelectRequest.spaces(CardSelectRequest.CARDSID_LENGTH) : typed;
    }

    private static byte aidByteOf(Invocation invocation) {
        String mnemonic = invocation.hasScreenRequest() ? invocation.aid() : null;
        if (mnemonic == null) {
            return CicsAid.DFHENTER;
        }
        for (Map.Entry<Byte, String> entry : AID_MNEMONICS.entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("Case " + invocation.program() + '/' + invocation.caseId()
                + " names the AID \"" + mnemonic + "\", which common.CicsAid does not define. DFHAID is "
                + "absent from this repository, so that class is the only reproduction of it and a "
                + "mnemonic it does not carry names no key.");
    }

    private static CardRepository seededCardRepository(Invocation invocation, SeededDataset seeded) {
        CardRepository repository = Mockito.mock(CardRepository.class, unstubbed -> {
            throw new UnsupportedOperationException("COCRDSLC called CardRepository."
                    + unstubbed.getMethod().getName() + ", which it has no statement for. Its only "
                    + "file verb is the EXEC CICS READ at app/cbl/COCRDSLC.cbl:742-750 - there is no "
                    + "WRITE, no REWRITE, no DELETE and no browse anywhere in the program - so a call "
                    + "to anything else is a translation reaching for a verb the source does not "
                    + "contain, and it fails here rather than receiving a default.");
        });
        Mockito.doAnswer(read -> readByKey(invocation, seeded, read.getArgument(0)))
                .when(repository).readByCardNumber(ArgumentMatchers.anyString());
        Mockito.doAnswer(read -> readByAccountKey(seeded, read.getArgument(0)))
                .when(repository).readByAccountIdViaAltIndex(ArgumentMatchers.anyString());
        return repository;
    }

    private static CardRepository.CardReadResult readByKey(Invocation invocation,
            SeededDataset seeded, String cardNumber) {
        if (invocation.hasForcedOutcome(RepositoryOperation.READ)) {
            return forcedRead(invocation, seeded,
                    invocation.forcedOutcome(RepositoryOperation.READ), cardNumber);
        }
        return rowKeyed(seeded, cardNumber)
                .map(image -> CardRepository.CardReadResult.normal(
                        CardRecord.decodeImage(image, FIXTURE_CHARSET), image))
                .orElseGet(CardRepository.CardReadResult::notFound);
    }

    private static CardRepository.CardReadResult readByAccountKey(SeededDataset seeded,
            String accountIdDigits) {
        for (String image : seeded.rows()) {
            String account = image.substring(CardRecord.CARD_ACCT_ID_OFFSET,
                    CardRecord.CARD_ACCT_ID_OFFSET + CardRecord.CARD_ACCT_ID_LENGTH);
            if (account.equals(accountIdDigits)) {
                return CardRepository.CardReadResult.normal(
                        CardRecord.decodeImage(image, FIXTURE_CHARSET), image);
            }
        }
        return CardRepository.CardReadResult.notFound();
    }

    private static Optional<String> rowKeyed(SeededDataset seeded, String cardNumber) {
        if (cardNumber.length() != CardRecord.CARD_NUM_LENGTH) {
            return Optional.empty();
        }
        for (String image : seeded.rows()) {
            if (image.startsWith(cardNumber)) {
                return Optional.of(image);
            }
        }
        return Optional.empty();
    }

    private static CardRepository.CardReadResult forcedRead(Invocation invocation,
            SeededDataset seeded, ForcedOutcome outcome, String cardNumber) {
        int reasonCode = outcome.resp2() == null ? 0 : outcome.resp2();
        return switch (outcome.outcome()) {
            case OK -> rowKeyed(seeded, cardNumber)
                    .map(image -> CardRepository.CardReadResult.normal(
                            CardRecord.decodeImage(image, FIXTURE_CHARSET), image))
                    .orElseThrow(() -> new IllegalStateException("Case " + invocation.caseId()
                            + " forces OK for the CARDDAT read but seeds no row keyed "
                            + cardNumber.trim() + ", so there is no record to return."));
            case NOT_FOUND -> CardRepository.CardReadResult.notFound();
            case END_OF_FILE -> CardRepository.CardReadResult.endOfFile();
            case DUPLICATE -> rowKeyed(seeded, cardNumber)
                    .map(image -> CardRepository.CardReadResult.duplicateKey(
                            CardRecord.decodeImage(image, FIXTURE_CHARSET), image))
                    .orElseThrow(() -> new IllegalStateException("Case " + invocation.caseId()
                            + " forces DUPLICATE but seeds no row keyed " + cardNumber.trim()));
            case OTHER -> CardRepository.CardReadResult.reportedFailure(
                    outcome.resp() == null ? FileStatus.LENGERR : outcome.resp(), reasonCode);
        };
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("every case names COCRDSLC and cites the source line it pins")
    void everyCaseNamesThisProgramAndPinsOneNamedBranch(ParityCase parityCase) {
        assertThat(parityCase.program()).isEqualTo(PROGRAM);
        assertThat(parityCase.unitKind())
                .describedAs("COCRDSLC keeps its decision logic in the controller - the card package "
                        + "lifts a service out for COCRDUPC alone - so every case reaches it as a plain "
                        + "Java object")
                .isEqualTo(UnitKind.CONTROLLER_POJO);
        assertThat(parityCase.description())
                .describedAs("%s must cite the COBOL line, paragraph or 88-level it pins, so parity "
                        + "coverage can be audited without re-reading the program", parityCase.caseId())
                .contains(":")
                .hasSizeGreaterThan(80);
        assertThat(parityCase.inputs())
                .describedAs("every case seeds the CARDDAT base cluster and nothing else: CARDAIX is a "
                        + "path over it, not a dataset of its own (gate G45)")
                .containsOnlyKeys(CARDDAT);
        assertThat(parityCase.expectedWrites())
                .describedAs("COCRDSLC issues no WRITE, REWRITE or DELETE, so every case asserts "
                        + "positively that nothing was written")
                .isEmpty();
        assertThat(parityCase.expectedReturnCode())
                .describedAs("every path ends in EXEC CICS RETURN rather than EXEC CICS ABEND")
                .isZero();
    }

    @Test
    @DisplayName("the fifteen COCRDSL widths are the symbolic map's own, on both projections")
    void theFifteenSharedFieldWidthsAreTheSymbolicMapsOwn() {
        assertThat(CardSelectRequest.FIELD_COUNT).isEqualTo(15);
        assertThat(CardSelectResponse.FIELD_COUNT).isEqualTo(CardSelectRequest.FIELD_COUNT);
        assertThat(CardSelectRequest.ScreenField.values())
                .hasSize(CardSelectRequest.FIELD_COUNT);
        assertThat(CardSelectResponse.ScreenField.values())
                .hasSize(CardSelectResponse.FIELD_COUNT);

        Map<String, Integer> declared = new LinkedHashMap<>();
        declared.put("TRNNAME", 4);
        declared.put("TITLE01", 40);
        declared.put("CURDATE", 8);
        declared.put("PGMNAME", 8);
        declared.put("TITLE02", 40);
        declared.put("CURTIME", 8);
        declared.put("ACCTSID", 11);
        declared.put("CARDSID", 16);
        declared.put("CRDNAME", 50);
        declared.put("CRDSTCD", 1);
        declared.put("EXPMON", 2);
        declared.put("EXPYEAR", 4);
        declared.put("INFOMSG", 40);
        declared.put("ERRMSG", 80);
        declared.put("FKEYS", 75);

        for (CardSelectRequest.ScreenField field : CardSelectRequest.ScreenField.values()) {
            assertThat(field.length())
                    .describedAs("%sI PIC X(n) in app/cpy-bms/COCRDSL.CPY", field.label())
                    .isEqualTo(declared.get(field.label()));
            assertThat(field.symbolicItemName()).isEqualTo(field.label() + "I");
        }
        for (CardSelectResponse.ScreenField field : CardSelectResponse.ScreenField.values()) {
            assertThat(field.length())
                    .describedAs("%sO PIC X(n) in app/cpy-bms/COCRDSL.CPY - the shared map's width, so "
                            + "changing it must fail here AND in COCRDLICParityTest", field.dfhmdfLabel())
                    .isEqualTo(declared.get(field.dfhmdfLabel()));
            assertThat(field.cobolName()).isEqualTo(field.dfhmdfLabel() + "O");
        }
    }

    @Test
    @DisplayName("CARDAIX is a second finder on the same repository, not a second table")
    void theAlternateIndexIsASecondFinderOnTheSameRepository() {
        assertThat(CardRepository.BASE_DD_NAME).isEqualTo("CARDDAT");
        assertThat(CardRepository.ALTERNATE_INDEX_DD_NAME).isEqualTo("CARDAIX");
        assertThat(CardRepository.BASE_CICS_FILE_NAME)
                .describedAs("LIT-CARDFILENAME PIC X(8) VALUE 'CARDDAT ' at app/cbl/COCRDSLC.cbl:187-188")
                .isEqualTo("CARDDAT ");
        assertThat(CardRepository.ALTERNATE_INDEX_CICS_FILE_NAME)
                .describedAs("LIT-CARDFILENAME-ACCT-PATH PIC X(8) VALUE 'CARDAIX ' at :189-190")
                .isEqualTo("CARDAIX ");
        assertThat(CardRepository.RECORD_LENGTH).isEqualTo(CardRecord.RECORD_LENGTH);

        ParityCase firstCase = cases().get(0);
        ParityHarness harness = ParityHarness.usAscii();
        SeededDataset seeded = harness.seed(firstCase).get(CARDDAT);
        String seededRow = seeded.row(0);
        String primaryKey = seededRow.substring(CardRecord.CARD_NUM_OFFSET,
                CardRecord.CARD_NUM_OFFSET + CardRecord.CARD_NUM_LENGTH);
        String alternateKey = seededRow.substring(CardRecord.CARD_ACCT_ID_OFFSET,
                CardRecord.CARD_ACCT_ID_OFFSET + CardRecord.CARD_ACCT_ID_LENGTH);

        List<CardRepository> theOneRepository = new ArrayList<>(1);
        harness.run(firstCase, UnitKind.CONTROLLER_POJO, invocation -> {
            theOneRepository.add(seededCardRepository(invocation, invocation.dataset(CARDDAT)));
            return invocation.recorder().returnCode(0).build();
        });
        CardRepository repository = theOneRepository.get(0);

        CardRepository.CardReadResult viaBaseKey = repository.readByCardNumber(primaryKey);
        CardRepository.CardReadResult viaAlternateKey =
                repository.readByAccountIdViaAltIndex(alternateKey);

        assertThat(viaBaseKey.isNormal()).isTrue();
        assertThat(viaAlternateKey.isNormal()).isTrue();
        assertThat(viaAlternateKey.requireStoredImage())
                .describedAs("CARDAIX is a path over CARDDAT's base cluster, so both keys reach the same "
                        + "150 bytes. Two tables could not satisfy this, and creating one would be the "
                        + "schema change the migration forbids (gate G45).")
                .isEqualTo(viaBaseKey.requireStoredImage());
        assertThat(viaAlternateKey.requireRecord()).isEqualTo(viaBaseKey.requireRecord());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("COCRDSLC writes no programmed-symbol or validation attribute on any case")
    void theProgrammedSymbolAndValidationPlanesAreNeverWritten(ParityCase parityCase) {
        CardSelectResponse painted = paint(parityCase);
        for (CardSelectResponse.ScreenField field : CardSelectResponse.ScreenField.values()) {
            CardSelectResponse.FieldAttributes quad = painted.attributes(field);
            assertThat(quad.getPs())
                    .describedAs("%s must stay 0x00: COCRDSLC issues no programmed-symbol move",
                            field.psItemName())
                    .isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(quad.getValidn())
                    .describedAs("%s must stay 0x00: COCRDSLC issues no validation move",
                            field.validnItemName())
                    .isEqualTo(BmsAttributes.DFHDFCOL);
        }
    }

    @Test
    @DisplayName("CARD-RECORD is 150 bytes and its FILLER X(59) is a declared span")
    void theCardRecordIsOneHundredAndFiftyBytesWithItsFillerPresent() {
        assertThat(CardRecord.RECORD_LENGTH).isEqualTo(150);
        assertThat(CardRecord.FILLER_OFFSET).isEqualTo(91);
        assertThat(CardRecord.FILLER_LENGTH).isEqualTo(59);
        assertThat(CardRecord.FILLER_OFFSET + CardRecord.FILLER_LENGTH)
                .describedAs("the FILLER closes the record, so the spans tile it exactly")
                .isEqualTo(CardRecord.RECORD_LENGTH);
        assertThat(CardRecord.LAYOUT.recordLength()).isEqualTo(CardRecord.RECORD_LENGTH);

        ParityCase firstCase = cases().get(0);
        SeededDataset seeded = ParityHarness.usAscii().seed(firstCase).get(CARDDAT);
        assertThat(seeded.recordLength()).isEqualTo(CardRecord.RECORD_LENGTH);
        for (String row : seeded.rows()) {
            assertThat(row).hasSize(CardRecord.RECORD_LENGTH);
            assertThat(CardRecord.decodeImage(row, FIXTURE_CHARSET).encodeToImage(FIXTURE_CHARSET))
                    .describedAs("a decode-then-encode round trip must return the same 150 bytes, which "
                            + "it cannot do if the FILLER is dropped on either leg")
                    .isEqualTo(row);
        }
    }

    @Test
    @DisplayName("the commarea is 160 bytes, travels in the payload, and no state is retained")
    void theConversationTravelsInThePayloadAndNothingIsRetained() {
        assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
        assertThat(NavigationContext.LAYOUT.recordLength()).isEqualTo(NavigationContext.COMMAREA_LENGTH);
        assertThat(CardSelectRequest.PASSED_COMMAREA_LENGTH)
                .describedAs("CARDDEMO-COMMAREA plus WS-THIS-PROGCOMMAREA, :397-400")
                .isEqualTo(NavigationContext.COMMAREA_LENGTH
                        + CardSelectRequest.ThisProgCommarea.RECORD_LENGTH);

        ParityCase reentry = cases().get(4);
        CardSelectResponse first = paint(reentry);
        CardSelectResponse second = paint(reentry);
        assertThat(NavigationImage.of(second.getNavigationContext()))
                .describedAs("the same request twice must yield the same commarea: a controller that "
                        + "retained conversation state between invocations could not")
                .isEqualTo(NavigationImage.of(first.getNavigationContext()));
        assertThat(second.getThisProgCommarea())
                .describedAs("the twelve-byte trailer :276-278 restores must come back, or the next turn "
                        + "receives 160 bytes where this program returned 172")
                .isEqualTo(first.getThisProgCommarea());
    }

    @Test
    @DisplayName("the three CVCRD01Y REDEFINES pairs are two views over one span each")
    void theRedefinesPairsRoundTripThroughBothAccessors() {
        CardScreenState work = new CardScreenState();
        work.initializeWorkArea();

        work.setCcAcctIdN(50L);
        assertThat(work.getCcAcctId()).isEqualTo("00000000050");
        assertThat(work.getCcAcctIdN()).isEqualTo(50L);
        assertThat(work.isCcAcctIdNumeric()).isTrue();

        work.setCcAcctId("00000000099");
        assertThat(work.getCcAcctIdN()).isEqualTo(99L);
        assertThat(work.getCcAcctId()).hasSize(CardScreenState.CC_ACCT_ID_LENGTH);

        work.setCcAcctIdToLowValues();
        assertThat(work.isCcAcctIdLowValues()).isTrue();
        work.setCcAcctId(CardScreenState.spaces(CardScreenState.CC_ACCT_ID_LENGTH));
        assertThat(work.isCcAcctIdSpaces()).isTrue();
        work.setCcAcctIdN(0L);
        assertThat(work.isCcAcctIdNZeros()).isTrue();
        assertThat(work.isCcAcctIdSpaces())
                .describedAs("eleven typed zeros are neither LOW-VALUES nor SPACES, which is exactly why "
                        + ":653 needs a third disjunct")
                .isFalse();

        work.setCcCardNumN(500024453765740L);
        assertThat(work.getCcCardNum()).isEqualTo("0500024453765740");
        assertThat(work.getCcCardNumN()).isEqualTo(500024453765740L);
        work.setCcCustIdN(9L);
        assertThat(work.getCcCustId()).isEqualTo("000000009");
        assertThat(work.getCcCustIdN()).isEqualTo(9L);
    }

    @Test
    @DisplayName("CSSTRPFY maps ENTER, CLEAR, PA1, PA2 and PF1-PF24, and leaves an unknown AID alone")
    void thePfKeyResolverReproducesTheCopybookEvaluate() {
        assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER)).contains(AidKey.ENTER);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHCLEAR)).contains(AidKey.CLEAR);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1)).contains(AidKey.PA1);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPA2)).contains(AidKey.PA2);
        assertThat(AidKey.PA1.token())
                .describedAs("CVCRD01Y declares VALUE 'PA1  ' - two trailing spaces in a PIC X(5) item")
                .isEqualTo("PA1  ");

        byte[] functionKeys = {CicsAid.DFHPF1, CicsAid.DFHPF2, CicsAid.DFHPF3, CicsAid.DFHPF4,
                CicsAid.DFHPF5, CicsAid.DFHPF6, CicsAid.DFHPF7, CicsAid.DFHPF8, CicsAid.DFHPF9,
                CicsAid.DFHPF10, CicsAid.DFHPF11, CicsAid.DFHPF12};
        byte[] foldedKeys = {CicsAid.DFHPF13, CicsAid.DFHPF14, CicsAid.DFHPF15, CicsAid.DFHPF16,
                CicsAid.DFHPF17, CicsAid.DFHPF18, CicsAid.DFHPF19, CicsAid.DFHPF20, CicsAid.DFHPF21,
                CicsAid.DFHPF22, CicsAid.DFHPF23, CicsAid.DFHPF24};
        for (int index = 0; index < functionKeys.length; index++) {
            AidKey direct = PfKeyResolver.resolve(functionKeys[index]).orElseThrow();
            AidKey folded = PfKeyResolver.resolve(foldedKeys[index]).orElseThrow();
            assertThat(direct.token()).isEqualTo(String.format("PFK%02d", index + 1));
            assertThat(folded)
                    .describedAs("CSSTRPFY L%d folds PF%d back onto %s rather than extending the range",
                            54 + index * 2, index + 13, direct)
                    .isSameAs(direct);
        }

        assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3))
                .describedAs("CSSTRPFY tests PA1 and PA2 but never PA3, and it has no WHEN OTHER")
                .isEmpty();
        assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3, Optional.of(AidKey.PFK03)))
                .describedAs("no match leaves CCARD-AID exactly as it was; it is never cleared")
                .contains(AidKey.PFK03);
        assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3, Optional.empty()))
                .describedAs("with nothing recorded before it, no match still records nothing - which is "
                        + "what lets :297-299 coerce five spaces to ENTER")
                .isEmpty();
        assertThatThrownBy(() -> PfKeyResolver.storePfKey(CicsAid.DFHENTER, null))
                .describedAs("an absent token is Optional.empty(), never null")
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest(name = "EIBAID 0x{0}")
    @ValueSource(ints = {0x7D, 0x6D, 0x6C, 0x6E, 0x6B, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7,
            0xF8, 0xF9, 0x7A, 0x7B, 0x7C, 0xC1, 0x4A, 0x40})
    @DisplayName("only ENTER and PF3 act; every other AID is coerced to ENTER and repaints")
    void everyOtherAidIsCoercedToEnterAndRepaints(int aid) {
        ParityCase coldStart = cases().get(0);
        CardSelectResponse painted = paintWithAid(coldStart, (byte) aid);

        boolean actsOnItsOwn = aid == Byte.toUnsignedInt(CicsAid.DFHENTER)
                || aid == Byte.toUnsignedInt(CicsAid.DFHPF3);
        if (actsOnItsOwn && aid == Byte.toUnsignedInt(CicsAid.DFHPF3)) {
            assertThat(tokenOrAbsent(painted.getNextMap())).isNull();
            assertThat(tokenOrAbsent(painted.getNextProgram())).isNotNull();
        } else {
            assertThat(tokenOrAbsent(painted.getNextMap()))
                    .describedAs("EIBAID 0x%02X must repaint CCRDSLA rather than be refused", aid)
                    .isEqualTo(CardSelectResponse.MAP_NAME);
            assertThat(painted.getPgmnameo()).isEqualTo(CardSelectResponse.THIS_PROGRAM);
            assertThat(painted.getTrnnameo()).isEqualTo(CardSelectResponse.THIS_TRANID);
        }
    }

    @Test
    @DisplayName("the CARDDAT read classifies NORMAL, NOTFND and WHEN OTHER as the source expects")
    void everyReadOutcomeOfTheOneCallSiteIsClassifiedCorrectly() {
        ParityCase firstCase = cases().get(0);
        SeededDataset seeded = ParityHarness.usAscii().seed(firstCase).get(CARDDAT);
        String seededRow = seeded.row(0);
        String primaryKey = seededRow.substring(CardRecord.CARD_NUM_OFFSET,
                CardRecord.CARD_NUM_OFFSET + CardRecord.CARD_NUM_LENGTH);
        String alternateKey = seededRow.substring(CardRecord.CARD_ACCT_ID_OFFSET,
                CardRecord.CARD_ACCT_ID_OFFSET + CardRecord.CARD_ACCT_ID_LENGTH);

        CardRepository.CardReadResult found = readByAccountKey(seeded, alternateKey);
        assertThat(found.isNormal()).isTrue();
        assertThat(found.resp()).isEqualTo(FileStatus.NORMAL);
        assertThat(found.requireRecord().cardNum()).isEqualTo(primaryKey);
        assertThat(found.requireStoredImage()).isEqualTo(seededRow);

        CardRepository.CardReadResult absent = readByAccountKey(seeded, "99999999999");
        assertThat(absent.isNotFound()).isTrue();
        assertThat(absent.resp()).isEqualTo(FileStatus.NOTFND);
        assertThat(absent.record()).isEmpty();

        CardRepository.CardReadResult failure =
                CardRepository.CardReadResult.reportedFailure(FileStatus.LENGERR, 3);
        assertThat(failure.isFailure()).isTrue();
        assertThat(failure.isNotFound())
                .describedAs("WHEN OTHER is a distinct arm from DFHRESP(NOTFND): :755-761 sets both "
                        + "filter flags NOT-OK while :762-771 guards only one of them")
                .isFalse();
        assertThat(failure.resp()).isEqualTo(FileStatus.LENGERR);
        assertThat(failure.resp2()).isEqualTo(3);
    }

    private static CardSelectResponse paint(ParityCase parityCase) {
        return paintWithAid(parityCase, null);
    }

    private static CardSelectResponse paintWithAid(ParityCase parityCase, Byte overrideAid) {
        ParityHarness harness = ParityHarness.usAscii();
        List<CardSelectResponse> captured = new ArrayList<>(1);
        harness.run(parityCase, UnitKind.CONTROLLER_POJO, invocation -> {
            SeededDataset seeded = invocation.dataset(CARDDAT);
            CardSelectController controller = new CardSelectController(
                    seededCardRepository(invocation, seeded), invocation.clock(), FIXTURE_CHARSET);
            byte aid = overrideAid == null ? aidByteOf(invocation) : overrideAid;
            ScreenResponse<CardSelectResponse> body = controller.viewCardDetail(
                    pathCardNumberOf(invocation), requestOf(invocation), null,
                    invocation.eibcalen(), Byte.toUnsignedInt(aid)).getBody();
            captured.add(Objects.requireNonNull(body, "viewCardDetail always answers with a body")
                    .screen());
            UnitOutcome.Builder recorder = invocation.recorder();
            recorder.finalStateUnchanged(seeded, CardRecord.LAYOUT);
            recorder.returnCode(0);
            return recorder.build();
        });
        return captured.get(0);
    }

    /**
     * The {@code CARDDEMO-COMMAREA} as sixteen named images, and back again.
     */
    private static final class NavigationImage {
        private NavigationImage() {
            throw new AssertionError("NavigationImage is a holder for two conversions");
        }

        private static Map<String, String> of(NavigationContext context) {
            Map<String, String> image = new LinkedHashMap<>();
            image.put(NavigationContext.FROM_TRANID_FIELD, context.fromTranid());
            image.put(NavigationContext.FROM_PROGRAM_FIELD, context.fromProgram());
            image.put(NavigationContext.TO_TRANID_FIELD, context.toTranid());
            image.put(NavigationContext.TO_PROGRAM_FIELD, context.toProgram());
            image.put(NavigationContext.USER_ID_FIELD, context.userId());
            image.put(NavigationContext.USER_TYPE_FIELD, context.userType());
            image.put(NavigationContext.PGM_CONTEXT_FIELD,
                    digits(context.pgmContext(), NavigationContext.PGM_CONTEXT_LENGTH));
            image.put(NavigationContext.CUST_ID_FIELD,
                    digits(context.custId(), NavigationContext.CUST_ID_LENGTH));
            image.put(NavigationContext.CUST_FNAME_FIELD, context.custFname());
            image.put(NavigationContext.CUST_MNAME_FIELD, context.custMname());
            image.put(NavigationContext.CUST_LNAME_FIELD, context.custLname());
            image.put(NavigationContext.ACCT_ID_FIELD,
                    digits(context.acctId(), NavigationContext.ACCT_ID_LENGTH));
            image.put(NavigationContext.ACCT_STATUS_FIELD, context.acctStatus());
            image.put(NavigationContext.CARD_NUM_FIELD,
                    digits(context.cardNum(), NavigationContext.CARD_NUM_LENGTH));
            image.put(NavigationContext.LAST_MAP_FIELD, context.lastMap());
            image.put(NavigationContext.LAST_MAPSET_FIELD, context.lastMapset());
            return image;
        }

        private static NavigationContext from(Map<String, String> declared) {
            NavigationContext empty = NavigationContext.empty();
            return new NavigationContext(
                    text(declared, NavigationContext.FROM_TRANID_FIELD, empty.fromTranid()),
                    text(declared, NavigationContext.FROM_PROGRAM_FIELD, empty.fromProgram()),
                    text(declared, NavigationContext.TO_TRANID_FIELD, empty.toTranid()),
                    text(declared, NavigationContext.TO_PROGRAM_FIELD, empty.toProgram()),
                    text(declared, NavigationContext.USER_ID_FIELD, empty.userId()),
                    text(declared, NavigationContext.USER_TYPE_FIELD, empty.userType()),
                    (int) number(declared, NavigationContext.PGM_CONTEXT_FIELD,
                            empty.pgmContext()),
                    (int) number(declared, NavigationContext.CUST_ID_FIELD, empty.custId()),
                    text(declared, NavigationContext.CUST_FNAME_FIELD, empty.custFname()),
                    text(declared, NavigationContext.CUST_MNAME_FIELD, empty.custMname()),
                    text(declared, NavigationContext.CUST_LNAME_FIELD, empty.custLname()),
                    number(declared, NavigationContext.ACCT_ID_FIELD, empty.acctId()),
                    text(declared, NavigationContext.ACCT_STATUS_FIELD, empty.acctStatus()),
                    number(declared, NavigationContext.CARD_NUM_FIELD, empty.cardNum()),
                    text(declared, NavigationContext.LAST_MAP_FIELD, empty.lastMap()),
                    text(declared, NavigationContext.LAST_MAPSET_FIELD, empty.lastMapset()));
        }

        private static String text(Map<String, String> declared, String field, String initialised) {
            String stated = declared.get(field);
            return stated == null ? initialised : stated;
        }

        private static long number(Map<String, String> declared, String field, long initialised) {
            String stated = declared.get(field);
            if (stated == null || stated.isBlank()) {
                return initialised;
            }
            return Long.parseLong(stated.trim());
        }

        private static String digits(long value, int width) {
            String rendered = Long.toString(value);
            if (rendered.length() >= width) {
                return rendered.substring(rendered.length() - width);
            }
            return "0".repeat(width - rendered.length()) + rendered;
        }
    }
}
