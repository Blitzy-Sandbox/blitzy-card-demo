package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardUpdateController;
import com.vsergeychik.carddemo.card.CardUpdateService;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardDetails;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.card.dto.CardUpdateResponse;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetChannel;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetInput;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedDataset;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedRecord;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedResponse;
import com.vsergeychik.carddemo.parity.ParityCase.ForcedOutcome;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.RepositoryOperation;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenRequest;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenSend;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import com.vsergeychik.carddemo.testsupport.ConversationStateSealFixture;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * The behavioural-parity gate for {@code COCRDUPC} - the credit-card update screen, CSD transaction
 * {@code CCUP}, projected as {@code PUT /api/cards/&#123;cardNum&#125;}.
 */
@DisplayName("COCRDUPC parity - the credit-card update screen, transaction CCUP")
class COCRDUPCParityTest {
    private static final String PROGRAM = "COCRDUPC";

    private static final String CARDDAT = CardRepository.BASE_DD_NAME;

    private static final String PINNED_CLOCK = "2022-07-19T23:12:33";

    private static final Charset FIXTURE_CHARSET = StandardCharsets.US_ASCII;

    private static final String CHARSET_NAME = "US-ASCII";

    private static final int EIBCALEN_NONE = 0;

    private static final int EIBCALEN_FULL =
            NavigationContext.COMMAREA_LENGTH + CardUpdateRequest.CommArea.RECORD_LENGTH;

    private static final String THIS_PGM = "COCRDUPC";

    private static final String THIS_TRANID = "CCUP";

    private static final String THIS_MAPSET = "COCRDUP";

    private static final String THIS_MAP = "CCRDUPA";

    private static final String CARD_LIST_PGM = "COCRDLIC";

    private static final String CARD_LIST_TRANID = "CCLI";

    private static final String MENU_PGM = "COMEN01C";

    private static final String MENU_TRANID = "CM00";

    private static final String ROW_01 = row("0500024453765740", "00000000050", "747", "Aniya Von",
            "2023-03-09", "Y");

    private static final String ROW_02 = row("0683586198171516", "00000000027", "567", "Ward Jones",
            "2025-07-13", "Y");

    private static final String ROW_03 = row("0923877193247330", "00000000002", "028",
            "Enrico Rosenbaum", "2024-08-11", "Y");

    private static final List<String> SEED_ROWS = List.of(ROW_01, ROW_02, ROW_03);

    private static final String KEY_01 = "0500024453765740";

    private static final String ACCT_01 = "00000000050";

    private static final String KEY_03 = "0923877193247330";

    private static final String ACCT_03 = "00000000002";

    private static final Map<String, Integer> MAP_FIELD_WIDTHS = Map.ofEntries(
            Map.entry("TRNNAME", 4), Map.entry("TITLE01", 40), Map.entry("CURDATE", 8),
            Map.entry("PGMNAME", 8), Map.entry("TITLE02", 40), Map.entry("CURTIME", 8),
            Map.entry("ACCTSID", 11), Map.entry("CARDSID", 16), Map.entry("CRDNAME", 50),
            Map.entry("CRDSTCD", 1), Map.entry("EXPMON", 2), Map.entry("EXPYEAR", 4),
            Map.entry("EXPDAY", 2), Map.entry("INFOMSG", 40), Map.entry("ERRMSG", 80),
            Map.entry("FKEYS", 21), Map.entry("FKEYSC", 18));

    private static final List<String> MAP_FIELDS = List.of("TRNNAME", "TITLE01", "CURDATE", "PGMNAME",
            "TITLE02", "CURTIME", "ACCTSID", "CARDSID", "CRDNAME", "CRDSTCD", "EXPMON", "EXPYEAR",
            "EXPDAY", "INFOMSG", "ERRMSG", "FKEYS", "FKEYSC");

    private static final String MSG_ACCT_NOT_PROVIDED = "Account number not provided";

    private static final String MSG_NO_SEARCH_CRITERIA = "No input received";

    private static final String MSG_ACCT_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    private static final String MSG_EXPIRY_MONTH_NOT_VALID =
            "Card expiry month must be between 1 and 12";

    private static final String INFO_PROMPT_FOR_KEYS = "Please enter Account and Card Number";

    private static final String LINKAGE_RETURN_MSG = "WS-RETURN-MSG";

    private static final String PROGRAM_AREA_KEY = "WS-THIS-PROGCOMMAREA";

    private static List<ParityCase> cases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);
        if (loaded.size() != ParityHarness.CASES_PER_PROGRAM) {
            throw new IllegalStateException("The parity gate for " + PROGRAM + " requires exactly "
                    + ParityHarness.CASES_PER_PROGRAM + " cases under src/test/resources/parity/"
                    + PROGRAM + "/, named case01 through case" + ParityHarness.CASES_PER_PROGRAM
                    + ", but " + loaded.size() + " loaded. A short set is not a smaller gate, it is a "
                    + "missing one: an assertion nobody runs cannot fail.");
        }
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            String required = ParityHarness.caseId(ordinal);
            String declared = loaded.get(ordinal - 1).caseId();
            if (!required.equals(declared)) {
                throw new IllegalStateException("Parity case " + ordinal + " for " + PROGRAM
                        + " is \"" + declared + "\" where the gate requires \"" + required
                        + "\". The identifiers are the file-name stems and they are ordered, so a "
                        + "mis-numbered file would silently take another case's place.");
            }
        }
        return loaded;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("COCRDUPC: every case diffs to zero against the COBOL-derived baseline")
    void theTranslationMatchesTheCobolFieldForField(ParityCase parityCase) {
        DiffResult result = ParityHarness.usAscii()
                .judge(parityCase, parityCase.unitKind(), COCRDUPCParityTest::unit);

        assertThat(result.count())
                .describedAs("%s/%s (%s) must diff to zero. %s", PROGRAM, parityCase.caseId(),
                        parityCase.unitKind(), result.render())
                .isZero();
    }

    private static UnitOutcome unit(Invocation invocation) {
        return switch (invocation.unitKind()) {
            case CONTROLLER_POJO -> controllerUnit(invocation);
            case SERVICE -> serviceUnit(invocation);
            case BATCH_JOB, COMPONENT -> throw new IllegalStateException("Case " + PROGRAM + '/'
                    + invocation.caseId() + " declares unitKind " + invocation.unitKind()
                    + ", which COCRDUPC has no unit for: it is a CICS online program, so its units are "
                    + "CardUpdateController (CONTROLLER_POJO) and CardUpdateService (SERVICE).");
        };
    }

    @Test
    @DisplayName("the class stem, the program name and the parity/COCRDUPC convention agree")
    void theResourceConventionIsHonoured() {
        assertThat(COCRDUPCParityTest.class.getSimpleName()).isEqualTo(PROGRAM + "ParityTest");
        assertThat(ParityHarness.caseResourcePath(PROGRAM, ParityHarness.caseId(1)))
                .isEqualTo(ParityHarness.CASE_RESOURCE_ROOT + PROGRAM + "/case01"
                        + ParityHarness.CASE_RESOURCE_EXTENSION);
        assertThat(ParityHarness.caseResourcePath(PROGRAM, ParityHarness.caseId(20)))
                .isEqualTo("parity/COCRDUPC/case20.json");
        assertThat(ParityHarness.CASES_PER_PROGRAM).isEqualTo(20);
        assertThat(cases()).hasSize(ParityHarness.CASES_PER_PROGRAM);
    }

    @Test
    @DisplayName("the seventeen payload fields carry the widths COCRDUP.CPY declares")
    void theSeventeenPayloadFieldsAreTheSymbolicMapsOwn() {
        assertThat(MAP_FIELD_WIDTHS).hasSize(17);
        assertThat(MAP_FIELDS).hasSize(17).containsExactlyInAnyOrderElementsOf(
                MAP_FIELD_WIDTHS.keySet());
        assertThat(CardUpdateResponse.MAPSET_NAME).isEqualTo(THIS_MAPSET);
        assertThat(CardUpdateResponse.MAP_NAME).isEqualTo(THIS_MAP);
        assertThat(CardUpdateResponse.TRANSACTION_ID).isEqualTo(THIS_TRANID);
        assertThat(CardUpdateResponse.PROGRAM_NAME).isEqualTo(THIS_PGM);
        assertThat(CardUpdateResponse.INPUT_GROUP_NAME).isEqualTo("CCRDUPAI");
        assertThat(CardUpdateResponse.OUTPUT_GROUP_NAME).isEqualTo("CCRDUPAO");

        CardUpdateResponse painted = new CardUpdateResponse();
        assertThat(painted.fieldImages().keySet())
                .as("every payload field is exposed as its xxxO output item, and only those")
                .containsExactlyInAnyOrderElementsOf(
                        MAP_FIELDS.stream().map(stem -> stem + "O").toList());
    }

    @Test
    @DisplayName("CARD-RECORD is 150 bytes, FILLER X(59) included and space-filled")
    void theCardRecordIsOneHundredAndFiftyBytesIncludingItsFiller() {
        assertThat(CardRecord.RECORD_LENGTH).isEqualTo(150);
        assertThat(CardRecord.CARD_NUM_OFFSET).isZero();
        assertThat(CardRecord.CARD_NUM_LENGTH).isEqualTo(16);
        assertThat(CardRecord.CARD_ACCT_ID_OFFSET).isEqualTo(16);
        assertThat(CardRecord.CARD_ACCT_ID_LENGTH).isEqualTo(11);
        assertThat(CardRecord.CARD_CVV_CD_OFFSET).isEqualTo(27);
        assertThat(CardRecord.CARD_CVV_CD_LENGTH).isEqualTo(3);
        assertThat(CardRecord.CARD_EMBOSSED_NAME_OFFSET).isEqualTo(30);
        assertThat(CardRecord.CARD_EMBOSSED_NAME_LENGTH).isEqualTo(50);
        assertThat(CardRecord.CARD_EXPIRAION_DATE_OFFSET).isEqualTo(80);
        assertThat(CardRecord.CARD_EXPIRAION_DATE_LENGTH).isEqualTo(10);
        assertThat(CardRecord.CARD_ACTIVE_STATUS_OFFSET).isEqualTo(90);
        assertThat(CardRecord.CARD_ACTIVE_STATUS_LENGTH).isEqualTo(1);
        assertThat(CardRecord.FILLER_OFFSET).isEqualTo(91);
        assertThat(CardRecord.FILLER_LENGTH).isEqualTo(59);
        assertThat(CardRecord.FILLER_OFFSET + CardRecord.FILLER_LENGTH)
                .isEqualTo(CardRecord.RECORD_LENGTH);
        assertThat(CardRecord.LAYOUT.recordLength()).isEqualTo(150);

        assertThat(SEED_ROWS).allSatisfy(image -> assertThat(image).hasSize(150));
        assertThat(ROW_01.substring(CardRecord.FILLER_OFFSET))
                .as("the fixture's reserved span is 59 spaces, and the codec must emit it as such")
                .isEqualTo(" ".repeat(59));
        assertThat(CardRecord.decodeImage(ROW_01, FIXTURE_CHARSET).encodeToImage(FIXTURE_CHARSET))
                .as("a decode-then-encode round trip is byte-identical, FILLER included")
                .isEqualTo(ROW_01);
        assertThat(CardUpdateService.CARD_UPDATE_RECORD_LENGTH)
                .as("CARD-UPDATE-RECORD at :314-321 restates the same 150 bytes as CVACT02Y")
                .isEqualTo(CardRecord.RECORD_LENGTH);
    }

    @Test
    @DisplayName("CARDDEMO-COMMAREA is 160 bytes and the returned area is 160 + 329")
    void theCommareaIsExactlyOneHundredAndSixtyBytes() {
        assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
        assertThat(4 + 8 + 4 + 8 + 8 + 1 + 1 + 9 + 25 + 25 + 25 + 11 + 1 + 16 + 7 + 7)
                .isEqualTo(NavigationContext.COMMAREA_LENGTH);
        assertThat(CardUpdateRequest.CommArea.RECORD_LENGTH)
                .as("WS-THIS-PROGCOMMAREA at :274-321 is 1 + 89 + 89 + 150")
                .isEqualTo(1 + CardDetails.RECORD_LENGTH + CardDetails.RECORD_LENGTH
                        + CardUpdateRequest.CardUpdateRecord.RECORD_LENGTH);
        assertThat(EIBCALEN_FULL).isEqualTo(489);
        assertThat(NavigationContext.empty().toFixedWidth(new FixedWidthCodec(FIXTURE_CHARSET)))
                .hasSize(NavigationContext.COMMAREA_LENGTH);
    }

    @Test
    @DisplayName("CC-ACCT-ID and CC-ACCT-ID-N are one span read two ways")
    void theRedefinesPairIsTwoTypedViewsOfOneSpan() {
        assertThat(CardScreenState.CC_ACCT_ID_LENGTH).isEqualTo(11);
        assertThat(CardScreenState.CC_ACCT_ID_N_SPAN.offset())
                .as("the numeric view starts where the character view starts - it is the same storage")
                .isEqualTo(CardScreenState.CC_ACCT_ID_SPAN.offset());
        assertThat(CardScreenState.CC_ACCT_ID_N_SPAN.length())
                .isEqualTo(CardScreenState.CC_ACCT_ID_SPAN.length());
        assertThat(CardScreenState.CC_ACCT_ID_N_SPAN.redefinition()).isTrue();
        assertThat(CardScreenState.CC_ACCT_ID_SPAN.redefinition()).isFalse();

        CardScreenState work = new CardScreenState();
        work.initializeWorkArea();

        work.setCcAcctId(ACCT_01);
        assertThat(work.getCcAcctId()).isEqualTo(ACCT_01);
        assertThat(work.getCcAcctIdN()).isEqualTo(50L);

        work.setCcAcctIdN(27L);
        assertThat(work.getCcAcctIdN()).isEqualTo(27L);
        assertThat(work.getCcAcctId()).isEqualTo("00000000027");

        work.setCcCardNum(KEY_01);
        assertThat(work.getCcCardNumN()).isEqualTo(500_024_453_765_740L);
        work.setCcCardNumN(683_586_198_171_516L);
        assertThat(work.getCcCardNum()).isEqualTo("0683586198171516");
        work.setCcCustIdN(9L);
        assertThat(work.getCcCustId()).isEqualTo("000000009");
    }

    @Test
    @DisplayName("CARDAIX is an alternate-index finder on the same repository as CARDDAT")
    void theAlternateIndexIsASecondFinderOnTheSameRepository() {
        assertThat(CardRepository.BASE_DD_NAME).isEqualTo("CARDDAT");
        assertThat(CardRepository.ALTERNATE_INDEX_DD_NAME).isEqualTo("CARDAIX");
        assertThat(CardRepository.BASE_CICS_FILE_NAME.trim()).isEqualTo("CARDDAT");
        assertThat(CardRepository.ALTERNATE_INDEX_CICS_FILE_NAME.trim()).isEqualTo("CARDAIX");
        assertThat(CardRepository.RECORD_LENGTH).isEqualTo(CardRecord.RECORD_LENGTH);
        assertThat(CardUpdateService.CICS_FILE_NAME)
                .as("9200-WRITE-PROCESSING addresses the base cluster, not the path - :1428 and :1478")
                .isEqualTo(CardRepository.BASE_CICS_FILE_NAME);

        List<CardRepository> served = new ArrayList<>(1);
        CardRepository repository = fixtureRepository(SEED_ROWS, Map.of(), served);

        assertThat(repository.readByCardNumber(KEY_01).requireRecord().cardAcctId()).isEqualTo(50L);
        assertThat(repository.readByAccountIdViaAltIndex(ACCT_03).requireRecord().cardNum())
                .isEqualTo(KEY_03);
        assertThat(served)
                .as("both access paths were answered by one repository instance")
                .containsExactly(repository, repository);
    }

    @Test
    @DisplayName("9300 detects a change in any one of its six compared fields")
    void theConcurrencyCheckComparesAllSixMutableFields() {
        FixedWidthCodec codec = new FixedWidthCodec(FIXTURE_CHARSET);
        CardUpdateService service = serviceOver(fixtureRepository(SEED_ROWS, Map.of()));
        CardRecord stored = CardRecord.decodeImage(ROW_01, FIXTURE_CHARSET);
        CardDetails clean = snapshotOf(stored, codec);

        assertThat(service.checkChangeInRec(stored, clean, codec).dataWasChanged())
                .as("the unperturbed snapshot matches, so the rewrite is allowed to proceed")
                .isFalse();

        Map<String, CardDetails> perturbations = new LinkedHashMap<>();
        perturbations.put("CARD-CVV-CD", clean.withCvvCd("999"));
        perturbations.put("CARD-EMBOSSED-NAME", clean.withCrdname(picX("SOMEONE ELSE", 50)));
        perturbations.put("CARD-EXPIRAION-DATE(1:4)", clean.withExpyear("2099"));
        perturbations.put("CARD-EXPIRAION-DATE(6:2)", clean.withExpmon("12"));
        perturbations.put("CARD-EXPIRAION-DATE(9:2)", clean.withExpday("31"));
        perturbations.put("CARD-ACTIVE-STATUS", clean.withCrdstcd("N"));

        assertThat(perturbations)
                .as("the six operands of the single AND chain at app/cbl/COCRDUPC.cbl:1503-1508")
                .hasSize(6);
        perturbations.forEach((field, snapshot) -> {
            CardUpdateService.ChangeCheck check = service.checkChangeInRec(stored, snapshot, codec);
            assertThat(check.dataWasChanged())
                    .as("a change to %s alone must be detected, or a concurrent update to it would be "
                            + "silently overwritten", field)
                    .isTrue();
            assertThat(check.describeDifferences())
                    .as("the report names the field that differed, for %s", field)
                    .isNotBlank();
            assertThat(check.oldDetails())
                    .as("the ELSE arm at :1512-1517 repaints the snapshot from the record it read, so "
                            + "the screen the user sees next shows what is actually stored")
                    .isEqualTo(snapshotOf(stored, codec));
        });
    }

    @Test
    @DisplayName("9300 compares neither CARD-NUM nor CARD-ACCT-ID, as the source does not")
    void theConcurrencyCheckIgnoresBothKeyFields() {
        FixedWidthCodec codec = new FixedWidthCodec(FIXTURE_CHARSET);
        CardUpdateService service = serviceOver(fixtureRepository(SEED_ROWS, Map.of()));
        CardRecord stored = CardRecord.decodeImage(ROW_01, FIXTURE_CHARSET);
        CardDetails clean = snapshotOf(stored, codec);

        assertThat(service.checkChangeInRec(stored, clean.withCardid("9999999999999999"), codec)
                .dataWasChanged())
                .as("CARD-NUM is absent from the comparison, so a differing snapshot key changes nothing")
                .isFalse();
        assertThat(service.checkChangeInRec(stored, clean.withAcctid("99999999999"), codec)
                .dataWasChanged())
                .as("CARD-ACCT-ID is absent from the comparison too")
                .isFalse();
    }

    @Test
    @DisplayName("no version column and no ORM annotation is introduced for concurrency control")
    void noVersionColumnOrSchemaArtefactIsIntroduced() {
        for (Class<?> type : List.of(CardRecord.class, CardUpdateRequest.CardUpdateRecord.class)) {
            assertThat(type.getRecordComponents())
                    .as("%s carries only the copybook's own items - no version, no timestamp, no "
                            + "surrogate key", type.getSimpleName())
                    .noneSatisfy(component -> assertThat(component.getName().toLowerCase())
                            .containsAnyOf("version", "revision", "rowversion", "etag", "optlock"));
            assertThat(type.getAnnotations())
                    .as("%s carries no persistence annotation, so no table is declared anywhere",
                            type.getSimpleName())
                    .noneSatisfy(annotation -> assertThat(
                            annotation.annotationType().getName().toLowerCase())
                            .containsAnyOf("jakarta.persistence", "javax.persistence",
                                    "hibernate"));
        }
        assertThat(CardRecord.LAYOUT.recordLength())
                .as("the record is exactly the copybook's 150 bytes, with nothing appended to it")
                .isEqualTo(150);
    }

    @Test
    @DisplayName("the rewrite zeroes CARD-CVV-CD because CCUP-NEW-CVV-CD is never assigned")
    void theRewriteAlwaysZeroesTheCvvBecauseCcupNewCvvCdIsNeverAssigned() {
        FixedWidthCodec codec = new FixedWidthCodec(FIXTURE_CHARSET);

        String initialised = CardUpdateService.redefinedCvvImage("   ", codec);
        assertThat(initialised).isEqualTo("   ");
        assertThat(CardUpdateService.zonedDigitsValue(initialised, codec))
                .as("spaces read through PIC 9(03) are zero, so the rewrite stores 000")
                .isZero();

        assertThat(CardUpdateService.zonedDigitsValue(
                CardUpdateService.redefinedCvvImage("747", codec), codec))
                .as("a supplied three-digit value survives the redefinition unchanged")
                .isEqualTo(747);
        assertThat(CardUpdateService.redefinedCvvImage("7", codec))
                .as("MOVE to PIC X(3) pads on the RIGHT, so one digit lands in the hundreds column")
                .isEqualTo("7  ");
        assertThat(CardUpdateService.zonedDigitsValue(
                CardUpdateService.redefinedCvvImage("7", codec), codec))
                .isEqualTo(700);
    }

    @Test
    @DisplayName("MOVE truncates PIC X on the right and PIC 9 on the left")
    void crossWidthMovesTruncateTheWayCobolDoes() {
        FixedWidthCodec codec = new FixedWidthCodec(FIXTURE_CHARSET);

        assertThat(codec.movePicX("ABCDEFGHIJKL", 10))
                .as("PIC X keeps the leftmost bytes and drops the tail")
                .isEqualTo("ABCDEFGHIJ");
        assertThat(codec.movePicX("ABC", 10))
                .as("PIC X pads on the right with spaces")
                .isEqualTo("ABC       ");
        assertThat(codec.movePic9("123456789012345", 11))
                .as("PIC 9 keeps the rightmost digits and drops the high-order ones")
                .isEqualTo("56789012345");
        assertThat(codec.movePic9("27", 11))
                .as("PIC 9 pads on the left with zeros")
                .isEqualTo("00000000027");
        assertThat(codec.concatenateDelimitedBySize("2028", "-", "11", "-", "30"))
                .as("STRING ... DELIMITED BY SIZE at :1467-1474 takes every operand at its full width")
                .isEqualTo("2028-11-30")
                .hasSize(CardRecord.CARD_EXPIRAION_DATE_LENGTH);

        assertThatThrownBy(() -> snapshotOf(CardRecord.decodeImage(ROW_01, FIXTURE_CHARSET), codec)
                .withCrdname("X".repeat(51)))
                .as("a commarea item is never silently truncated - 9300 compares these bytes")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PIC X(50)");
    }

    @Test
    @DisplayName("CSSTRPFY resolves every AID, folds PF13-PF24 and defaults nothing")
    void pfKeysResolveExactlyAsCsstrpfyMapsThem() {
        Map<Byte, AidKey> direct = new LinkedHashMap<>();
        direct.put(CicsAid.DFHENTER, AidKey.ENTER);
        direct.put(CicsAid.DFHCLEAR, AidKey.CLEAR);
        direct.put(CicsAid.DFHPA1, AidKey.PA1);
        direct.put(CicsAid.DFHPA2, AidKey.PA2);
        direct.put(CicsAid.DFHPF1, AidKey.PFK01);
        direct.put(CicsAid.DFHPF2, AidKey.PFK02);
        direct.put(CicsAid.DFHPF3, AidKey.PFK03);
        direct.put(CicsAid.DFHPF4, AidKey.PFK04);
        direct.put(CicsAid.DFHPF5, AidKey.PFK05);
        direct.put(CicsAid.DFHPF6, AidKey.PFK06);
        direct.put(CicsAid.DFHPF7, AidKey.PFK07);
        direct.put(CicsAid.DFHPF8, AidKey.PFK08);
        direct.put(CicsAid.DFHPF9, AidKey.PFK09);
        direct.put(CicsAid.DFHPF10, AidKey.PFK10);
        direct.put(CicsAid.DFHPF11, AidKey.PFK11);
        direct.put(CicsAid.DFHPF12, AidKey.PFK12);
        assertThat(direct)
                .as("the sixteen 88-levels of CVCRD01Y:3-19 - the plan's count of fifteen is one short "
                        + "of what app/cpy/CVCRD01Y.cpy actually declares, and the source wins")
                .hasSize(16);
        direct.forEach((aid, expected) -> assertThat(PfKeyResolver.resolve(aid))
                .as("EIBAID 0x%02X resolves to %s", aid, expected)
                .contains(expected));

        Map<Byte, AidKey> folded = new LinkedHashMap<>();
        folded.put(CicsAid.DFHPF13, AidKey.PFK01);
        folded.put(CicsAid.DFHPF14, AidKey.PFK02);
        folded.put(CicsAid.DFHPF15, AidKey.PFK03);
        folded.put(CicsAid.DFHPF16, AidKey.PFK04);
        folded.put(CicsAid.DFHPF17, AidKey.PFK05);
        folded.put(CicsAid.DFHPF18, AidKey.PFK06);
        folded.put(CicsAid.DFHPF19, AidKey.PFK07);
        folded.put(CicsAid.DFHPF20, AidKey.PFK08);
        folded.put(CicsAid.DFHPF21, AidKey.PFK09);
        folded.put(CicsAid.DFHPF22, AidKey.PFK10);
        folded.put(CicsAid.DFHPF23, AidKey.PFK11);
        folded.put(CicsAid.DFHPF24, AidKey.PFK12);
        assertThat(folded).hasSize(12);
        folded.forEach((aid, expected) -> assertThat(PfKeyResolver.resolve(aid))
                .as("EIBAID 0x%02X is the upper bank and folds onto %s", aid, expected)
                .contains(expected));

        for (byte unlisted : new byte[] {CicsAid.DFHPA3, CicsAid.DFHPEN, CicsAid.DFHCLRP,
                CicsAid.DFHOPID}) {
            assertThat(PfKeyResolver.resolve(unlisted))
                    .as("EIBAID 0x%02X is not one of the EVALUATE's WHEN clauses and there is no "
                            + "WHEN OTHER, so nothing is resolved", unlisted)
                    .isEmpty();
            assertThat(PfKeyResolver.storePfKey(unlisted, Optional.of(AidKey.PFK05)))
                    .as("an unlisted identifier leaves CCARD-AID exactly as it was")
                    .contains(AidKey.PFK05);
        }
        assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPF13, Optional.of(AidKey.PFK05)))
                .as("a listed identifier overwrites CCARD-AID, folded")
                .contains(AidKey.PFK01);
    }

    @Test
    @DisplayName("0000-MAIN's EVALUATE is first-match-wins, and arm one outranks arm four")
    void theEvaluateArmsArePickedInSourceOrder() {
        CardUpdateRequest overlapping = requestWith(navigationFromCardList()
                .withLastMapset("COCRDLI").withLastMap("CCRDSLA"));
        overlapping.setCommArea(new CardUpdateRequest.CommArea(
                CardUpdateRequest.ChangeAction.changesOkayedAndDone(),
                snapshotOf(CardRecord.decodeImage(ROW_01, FIXTURE_CHARSET),
                        new FixedWidthCodec(FIXTURE_CHARSET)),
                newDetailsOf(ACCT_01, KEY_01, picX("ANIYA VON", 50), "N", "11", "2028", "30"),
                CardUpdateRequest.CardUpdateRecord.initialised()));

        CardUpdateResponse painted = paint(overlapping, EIBCALEN_FULL, CicsAid.DFHENTER);

        assertThat(token(painted.getNextProgram()))
                .as("arm one wins: control transfers, so a next program is named")
                .isEqualTo(CARD_LIST_PGM);
        assertThat(token(painted.getNextMap()))
                .as("arm four would have repainted CCRDUPA; arm one sends no map at all")
                .isNull();
        assertThat(token(painted.getNextMapset())).isNull();
    }

    @Test
    @DisplayName("the highlight matrix reddens a field only in REENTER, and asterisks only a blank one")
    void theHighlightMatrixAppliesOnlyOnReentry() {
        FieldAttributeSetter.FieldHighlight okOnEntry =
                FieldAttributeSetter.resolveFromFlags(false, false, false, "ACCTSID", "CCRDUPAO");
        assertThat(okOnEntry.untouched())
                .as("a valid field is never highlighted, on either pass")
                .isTrue();

        FieldAttributeSetter.FieldHighlight notOkOnEntry =
                FieldAttributeSetter.resolveFromFlags(true, false, false, "ACCTSID", "CCRDUPAO");
        assertThat(notOkOnEntry.untouched())
                .as("first entry paints the screen; it does not judge what has not been typed yet")
                .isTrue();

        FieldAttributeSetter.FieldHighlight notOkOnReentry =
                FieldAttributeSetter.resolveFromFlags(true, false, true, "ACCTSID", "CCRDUPAO");
        assertThat(notOkOnReentry.colourItemAssigned()).isTrue();
        assertThat(notOkOnReentry.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
        assertThat(notOkOnReentry.colourItemName()).isEqualTo("ACCTSIDC");
        assertThat(notOkOnReentry.outputItemAssigned())
                .as("an invalid value is reddened but kept, so the user can see what was rejected")
                .isFalse();

        FieldAttributeSetter.FieldHighlight blankOnReentry =
                FieldAttributeSetter.resolveFromFlags(false, true, true, "ACCTSID", "CCRDUPAO");
        assertThat(blankOnReentry.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
        assertThat(blankOnReentry.outputItemAssigned())
                .as("a blank field is reddened AND marked, because there is nothing to show otherwise")
                .isTrue();
        assertThat(blankOnReentry.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);
        assertThat(blankOnReentry.outputItemName()).isEqualTo("ACCTSIDO");

        assertThat(FieldAttributeSetter.FieldValidationState.of(true, false).notOk()).isTrue();
        assertThat(FieldAttributeSetter.FieldValidationState.of(false, true).blank()).isTrue();
    }

    @Test
    @DisplayName("EXPDAYC is DFHBMDAR on every send - the expiry day is deliberately dark")
    void theExpiryDayIsAlwaysDark() {
        assertThat(BmsAttributes.DFHBMDAR).isEqualTo((byte) 0x4C);
        assertThat(BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.get(BmsAttributes.DFHBMDAR))
                .as("the mnemonic must be nameable, because a case declares attributes by mnemonic")
                .isEqualTo("DFHBMDAR");
        assertThat(BmsAttributes.COLOUR_MNEMONICS.get(BmsAttributes.DFHRED)).isEqualTo("DFHRED");

        CardUpdateResponse painted = paint(null, EIBCALEN_NONE, CicsAid.DFHENTER);
        assertThat(painted.attributesOf("EXPDAY").getColour()).isEqualTo(BmsAttributes.DFHBMDAR);
        for (String stem : MAP_FIELDS) {
            if ("EXPDAY".equals(stem)) {
                continue;
            }
            assertThat(painted.attributesOf(stem).getColour())
                    .as("%sC is left at its default on a first, valid entry", stem)
                    .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
        }
    }

    @Test
    @DisplayName("the xxxP and xxxV attribute planes stay untouched on every case")
    void theProgrammedSymbolAndValidationPlanesAreNeverWritten() {
        for (ParityCase parityCase : cases()) {
            if (parityCase.unitKind() != UnitKind.CONTROLLER_POJO) {
                continue;
            }
            ScreenRequest request = parityCase.screenRequest();
            CardUpdateResponse painted = paint(requestFrom(request), request.eibcalen(),
                    aidByte(request.aid()), pathCardNumberOf(request));
            for (String stem : MAP_FIELDS) {
                CardUpdateResponse.FieldAttributes attributes = painted.attributesOf(stem);
                assertThat(attributes.getPs())
                        .as("%s/%sP - no program in this application writes a programmed-symbol byte",
                                parityCase.caseId(), stem)
                        .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
                assertThat(attributes.getValidn())
                        .as("%s/%sV - nor a validation byte", parityCase.caseId(), stem)
                        .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
            }
        }
    }

    private static CardUpdateRequest.CommArea programAreaOf(Invocation invocation) {
        String image = invocation.commarea().get(PROGRAM_AREA_KEY);
        if (image == null) {
            return CardUpdateRequest.CommArea.initialised();
        }
        if (image.length() != CardUpdateRequest.CommArea.RECORD_LENGTH) {
            throw new IllegalStateException("Case " + invocation.program() + '/' + invocation.caseId()
                    + " declares a " + PROGRAM_AREA_KEY + " image of " + image.length()
                    + " characters where WS-THIS-PROGCOMMAREA is "
                    + CardUpdateRequest.CommArea.RECORD_LENGTH + " bytes - "
                    + "CARD-UPDATE-SCREEN-DATA, CCUP-OLD-DETAILS, CCUP-NEW-DETAILS and "
                    + "CARD-UPDATE-RECORD, at app/cbl/COCRDUPC.cbl:206-241. A short image would decode "
                    + "into a state the program cannot be in.");
        }
        return CardUpdateRequest.CommArea.decode(image.getBytes(invocation.charset()),
                invocation.codec());
    }

    private static String pathCardNumberOf(Invocation invocation) {
        return pathCardNumber(invocation.mapFields(), invocation.commarea());
    }

    private static String pathCardNumberOf(ScreenRequest declared) {
        return pathCardNumber(declared.mapFields(), declared.commarea());
    }

    private static String pathCardNumber(Map<String, String> mapFields,
                                         Map<String, String> commarea) {
        String typed = mapFields.get("CARDSIDI");
        if (typed != null) {
            return picX(typed.trim(), CardDetails.CARDID_LENGTH);
        }
        String carried = commarea.get("CDEMO-CARD-NUM");
        return carried == null || carried.isBlank()
                ? picX("", CardDetails.CARDID_LENGTH)
                : picX(carried.trim(), CardDetails.CARDID_LENGTH);
    }

    private static Map<RepositoryOperation, ForcedOutcome> forcedOutcomesOf(Invocation invocation,
                                                                           RepositoryOperation...
                                                                                   operations) {
        Map<RepositoryOperation, ForcedOutcome> forced = new LinkedHashMap<>();
        for (RepositoryOperation operation : operations) {
            if (invocation.hasForcedOutcome(operation)) {
                forced.put(operation, invocation.forcedOutcome(operation));
            }
        }
        return forced;
    }

    private static void requireReceivedFieldsMatch(Invocation invocation, CardDetails newDetails) {
        Map<String, String> received = invocation.mapFields();
        Map<String, String> fromArea = Map.of(
                "ACCTSIDI", newDetails.acctid(),
                "CARDSIDI", newDetails.cardid(),
                "CRDNAMEI", newDetails.crdname(),
                "CRDSTCDI", newDetails.crdstcd(),
                "EXPMONI", newDetails.expmon(),
                "EXPYEARI", newDetails.expyear(),
                "EXPDAYI", newDetails.expday());
        for (Map.Entry<String, String> entry : fromArea.entrySet()) {
            String typed = received.get(entry.getKey());
            if (typed == null) {
                continue;
            }
            if (!picX(typed.trim(), entry.getValue().length()).equals(entry.getValue())) {
                throw new IllegalStateException("Case " + invocation.program() + '/'
                        + invocation.caseId() + " declares " + entry.getKey() + " as '" + typed
                        + "' but its WS-THIS-PROGCOMMAREA holds '" + entry.getValue() + "' in the "
                        + "matching CCUP-NEW- item. 2000-PROCESS-INPUTS copies one into the other, so "
                        + "the two cannot disagree in a run the program could have produced.");
            }
        }
    }

    private static UnitOutcome serviceUnit(Invocation invocation) {
        CardUpdateRequest.CommArea area = programAreaOf(invocation);
        String cardKey = picX(invocation.commarea().getOrDefault("CDEMO-CARD-NUM", "").trim(),
                CardDetails.CARDID_LENGTH);
        CardDetails oldDetails = area.oldDetails();
        CardDetails newDetails = area.newDetails();
        requireReceivedFieldsMatch(invocation, newDetails);
        String returnMessage = invocation.stimulus().linkageValue(LINKAGE_RETURN_MSG)
                .orElseThrow(() -> new IllegalStateException("Case " + PROGRAM + '/'
                        + invocation.caseId() + " declares unitKind SERVICE but no "
                        + LINKAGE_RETURN_MSG + " linkage value. 9200-WRITE-PROCESSING is handed "
                        + "WS-RETURN-MSG as it stands on entry - SPACES on every path that reaches it "
                        + "through 0000-MAIN, which clears it at :384 - and a case that left it "
                        + "unstated would be running with a value nobody declared."));
        return serviceUnit(invocation, cardKey, oldDetails, newDetails, returnMessage);
    }

    private static UnitOutcome serviceUnit(Invocation invocation,
                                           String cardKey,
                                           CardDetails oldDetails,
                                           CardDetails newDetails,
                                           String returnMessage) {
        SeededDataset seeded = invocation.dataset(CARDDAT);
        Map<RepositoryOperation, ForcedOutcome> forced = new LinkedHashMap<>();
        for (RepositoryOperation operation : List.of(RepositoryOperation.READ_FOR_UPDATE,
                RepositoryOperation.REWRITE)) {
            if (invocation.hasForcedOutcome(operation)) {
                forced.put(operation, invocation.forcedOutcome(operation));
            }
        }

        List<String> rows = new ArrayList<>(seeded.rows());
        CardRepository repository = fixtureRepository(rows, forced);
        CardUpdateService service = serviceOver(repository);

        CardScreenState workArea = new CardScreenState();
        workArea.initializeWorkArea();
        workArea.setCcCardNum(cardKey);
        workArea.setCcAcctIdN(Long.parseLong(newDetails.acctid().trim()));

        CardUpdateService.WriteResult result = service.writeProcessing(workArea, oldDetails,
                newDetails, returnMessage, invocation.codec());

        UnitOutcome.Builder recorder = invocation.recorder();
        Optional<String> rewritten = result.isRewritten()
                ? result.cardUpdateRecord()
                        .map(CardUpdateService::asCardRecord)
                        .map(record -> record.encodeToImage(invocation.charset()))
                : Optional.empty();
        if (rewritten.isPresent()) {
            recorder.wrote(CARDDAT, CardRecord.LAYOUT, rewritten.get());
            recorder.finalState(CARDDAT, CardRecord.LAYOUT, rows);
        } else {
            recorder.openedWithoutWriting(CARDDAT, CardRecord.LAYOUT);
            recorder.finalStateUnchanged(seeded, CardRecord.LAYOUT);
        }
        recorder.display(result.returnMessage());
        recorder.returnCode(0);
        return recorder.build();
    }

    private static UnitOutcome controllerUnit(Invocation invocation) {
        SeededDataset seeded = invocation.dataset(CARDDAT);
        List<String> rows = new ArrayList<>(seeded.rows());
        List<String> rewritten = new ArrayList<>();
        CardRepository repository = fixtureRepository(rows,
                forcedOutcomesOf(invocation, RepositoryOperation.READ,
                        RepositoryOperation.READ_FOR_UPDATE, RepositoryOperation.REWRITE),
                new ArrayList<>(), rewritten::add);

        CardUpdateController controller = new CardUpdateController(repository,
                serviceOver(repository), invocation.clock(), ConversationStateSealFixture.seal(),
                invocation.charset());

        CardUpdateRequest request = requestFrom(invocation.commarea(), invocation.mapFields(),
                programAreaOf(invocation));
        ScreenResponse<CardUpdateResponse> body = controller.updateCardDetail(
                pathCardNumberOf(invocation), request, null,
                invocation.eibcalen(), Byte.toUnsignedInt(aidByte(invocation.aid()))).getBody();
        CardUpdateResponse painted = java.util.Objects.requireNonNull(body,
                "COCRDUPC ends in EXEC CICS XCTL or EXEC CICS RETURN on every path, so the handler "
                        + "always answers with a body").screen();

        UnitOutcome.Builder recorder = invocation.recorder();
        recorder.response(observed(painted, body));
        if (rewritten.isEmpty()) {
            recorder.openedWithoutWriting(CARDDAT, CardRecord.LAYOUT);
        } else {
            rewritten.forEach(image -> recorder.wrote(CARDDAT, CardRecord.LAYOUT, image));
        }
        recorder.finalState(CARDDAT, CardRecord.LAYOUT, rows);
        recorder.returnCode(0);
        return recorder.build();
    }

    private static ObservedResponse observed(CardUpdateResponse painted,
                                             ScreenResponse<CardUpdateResponse> body) {
        String nextProgram = token(painted.getNextProgram());
        String nextMapset = token(painted.getNextMapset());
        String nextMap = token(painted.getNextMap());

        List<ObservedSend> sends = new ArrayList<>(1);
        if (nextMap != null) {
            sends.add(new ObservedSend(painted.fieldImages(), colourMnemonics(painted)));
        }

        String cursorStem = body.screenMetadata() == null ? null
                : body.screenMetadata().cursorField();
        String cursorField = cursorStem == null || cursorStem.isBlank() ? null : cursorStem + "L";

        Termination termination = nextProgram != null && sends.isEmpty()
                ? Termination.XCTL
                : Termination.RETURN_TRANSID;

        return new ObservedResponse(nextProgram, nextMapset, nextMap,
                navigationImage(painted.getNavigationContext()), sends, cursorField, termination);
    }

    private static Map<String, String> colourMnemonics(CardUpdateResponse painted) {
        Map<String, String> items = new LinkedHashMap<>();
        for (String stem : MAP_FIELDS) {
            byte colour = painted.attributesOf(stem).getColour();
            String mnemonic = BmsAttributes.COLOUR_MNEMONICS.get(colour);
            if (mnemonic == null) {
                mnemonic = BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.get(colour);
            }
            items.put(stem + "C", java.util.Objects.requireNonNull(mnemonic,
                    () -> "No DFHBMSCA or DFHATTR mnemonic names the byte 0x"
                            + String.format("%02X", colour) + " that " + stem + "C carries. An "
                            + "attribute is declared by mnemonic rather than by raw byte, so a value "
                            + "with no name cannot be expressed in a case and is a defect rather than "
                            + "something to render numerically."));
        }
        return items;
    }

    private static CardRepository fixtureRepository(List<String> rows,
                                                   Map<RepositoryOperation, ForcedOutcome> forced) {
        return fixtureRepository(rows, forced, new ArrayList<>());
    }

    private static CardRepository fixtureRepository(List<String> rows,
                                                    Map<RepositoryOperation, ForcedOutcome> forced,
                                                    List<CardRepository> served) {
        return fixtureRepository(rows, forced, served, image -> { });
    }

    private static CardRepository fixtureRepository(List<String> rows,
                                                    Map<RepositoryOperation, ForcedOutcome> forced,
                                                    List<CardRepository> served,
                                                    java.util.function.Consumer<String> onRewrite) {
        CardRepository repository = Mockito.mock(CardRepository.class, unstubbed -> {
            throw new UnsupportedOperationException("COCRDUPC called CardRepository."
                    + unstubbed.getMethod().getName() + ", for which it has no statement. Its file "
                    + "verbs are the EXEC CICS READ at app/cbl/COCRDUPC.cbl:1355-1365, the READ ... "
                    + "UPDATE at :1427-1436 and the REWRITE at :1477-1483 - there is no WRITE, no "
                    + "DELETE and no browse anywhere in the program - so a call to anything else is a "
                    + "translation reaching for a verb the source does not contain.");
        });
        Mockito.doAnswer(read -> {
            served.add(repository);
            return readByKey(rows, read.getArgument(0), forced.get(RepositoryOperation.READ));
        }).when(repository).readByCardNumber(ArgumentMatchers.anyString());
        Mockito.doAnswer(read -> {
            served.add(repository);
            return readByKey(rows, read.getArgument(0),
                    forced.get(RepositoryOperation.READ_FOR_UPDATE));
        }).when(repository).readForUpdateByCardNumber(ArgumentMatchers.anyString());
        Mockito.doAnswer(read -> {
            served.add(repository);
            return readByAccountKey(rows, read.getArgument(0));
        }).when(repository).readByAccountIdViaAltIndex(ArgumentMatchers.anyString());
        Mockito.doAnswer(rewrite -> {
            served.add(repository);
            CardRepository.CardWriteResult result = rewriteRow(rows, rewrite.getArgument(0),
                    forced.get(RepositoryOperation.REWRITE));
            if (result.outcome() == FileStatus.Outcome.OK) {
                onRewrite.accept(((CardRecord) rewrite.getArgument(0))
                        .encodeToImage(StandardCharsets.US_ASCII));
            }
            return result;
        }).when(repository).rewrite(ArgumentMatchers.any());
        return repository;
    }

    private static CardRepository.CardReadResult readByKey(List<String> rows, String cardNumber,
                                                           ForcedOutcome forced) {
        if (forced != null) {
            return switch (forced.outcome()) {
                case OK -> rowKeyed(rows, cardNumber)
                        .map(image -> CardRepository.CardReadResult.normal(
                                CardRecord.decodeImage(image, FIXTURE_CHARSET), image))
                        .orElseGet(CardRepository.CardReadResult::notFound);
                case NOT_FOUND -> CardRepository.CardReadResult.notFound();
                case END_OF_FILE -> CardRepository.CardReadResult.endOfFile();
                case DUPLICATE -> rowKeyed(rows, cardNumber)
                        .map(image -> CardRepository.CardReadResult.duplicateKey(
                                CardRecord.decodeImage(image, FIXTURE_CHARSET), image))
                        .orElseGet(CardRepository.CardReadResult::notFound);
                case OTHER -> CardRepository.CardReadResult.reportedFailure(
                        forced.resp() == null ? 16 : forced.resp(),
                        forced.resp2() == null ? 0 : forced.resp2());
            };
        }
        return rowKeyed(rows, cardNumber)
                .map(image -> CardRepository.CardReadResult.normal(
                        CardRecord.decodeImage(image, FIXTURE_CHARSET), image))
                .orElseGet(CardRepository.CardReadResult::notFound);
    }

    private static CardRepository.CardReadResult readByAccountKey(List<String> rows,
                                                                  String accountIdDigits) {
        for (String image : rows) {
            String account = image.substring(CardRecord.CARD_ACCT_ID_OFFSET,
                    CardRecord.CARD_ACCT_ID_OFFSET + CardRecord.CARD_ACCT_ID_LENGTH);
            if (account.equals(accountIdDigits)) {
                return CardRepository.CardReadResult.normal(
                        CardRecord.decodeImage(image, FIXTURE_CHARSET), image);
            }
        }
        return CardRepository.CardReadResult.notFound();
    }

    private static CardRepository.CardWriteResult rewriteRow(List<String> rows, CardRecord record,
                                                             ForcedOutcome forced) {
        if (forced != null && forced.outcome() != FileStatus.Outcome.OK) {
            return CardRepository.CardWriteResult.reportedFailure(
                    forced.resp() == null ? 16 : forced.resp(),
                    forced.resp2() == null ? 0 : forced.resp2());
        }
        String image = record.encodeToImage(FIXTURE_CHARSET);
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).startsWith(record.cardNum())) {
                rows.set(index, image);
                return CardRepository.CardWriteResult.normal();
            }
        }
        return CardRepository.CardWriteResult.reportedFailure(13, 0);
    }

    private static Optional<String> rowKeyed(List<String> rows, String cardNumber) {
        if (cardNumber == null || cardNumber.length() != CardRecord.CARD_NUM_LENGTH) {
            return Optional.empty();
        }
        for (String image : rows) {
            if (image.startsWith(cardNumber)) {
                return Optional.of(image);
            }
        }
        return Optional.empty();
    }

    private static CardUpdateService serviceOver(CardRepository repository) {
        return new CardUpdateService(repository, unitOfWork());
    }

    private static DatasetUnitOfWork unitOfWork() {
        SingleConnectionDataSource source = new SingleConnectionDataSource(
                "jdbc:h2:mem:cocrdupc-parity-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "", true);
        source.setSuppressClose(true);
        DataSource dataSource = source;
        return new DatasetUnitOfWork(new JdbcTransactionManager(dataSource));
    }

    private static String row(String cardNum, String acctId, String cvv, String embossedName,
                              String expiry, String status) {
        String image = picX(cardNum, CardRecord.CARD_NUM_LENGTH)
                + picX(acctId, CardRecord.CARD_ACCT_ID_LENGTH)
                + picX(cvv, CardRecord.CARD_CVV_CD_LENGTH)
                + picX(embossedName, CardRecord.CARD_EMBOSSED_NAME_LENGTH)
                + picX(expiry, CardRecord.CARD_EXPIRAION_DATE_LENGTH)
                + picX(status, CardRecord.CARD_ACTIVE_STATUS_LENGTH)
                + " ".repeat(CardRecord.FILLER_LENGTH);
        if (image.length() != CardRecord.RECORD_LENGTH) {
            throw new IllegalStateException("A CARD-RECORD image composed from CVACT01Y's spans came "
                    + "out " + image.length() + " characters wide where the copybook declares "
                    + CardRecord.RECORD_LENGTH + ". The composition above is the copybook read out "
                    + "loud, so a mismatch here is a transcription error in this class.");
        }
        return image;
    }

    private static String stagedImage(String cardNum, String acctId, String embossedName,
                                      String expiry, String status) {
        return row(cardNum, acctId, "000", embossedName, expiry, status);
    }

    private static String picX(String value, int length) {
        String source = value == null ? "" : value;
        return source.length() >= length ? source.substring(0, length)
                : source + " ".repeat(length - source.length());
    }

    private static CardDetails foldedSnapshotOf(String storedImage) {
        CardRecord stored = CardRecord.decodeImage(storedImage, FIXTURE_CHARSET);
        return snapshotOf(stored, new FixedWidthCodec(FIXTURE_CHARSET));
    }

    private static CardDetails snapshotOf(CardRecord stored, FixedWidthCodec codec) {
        CardRecord folded = CardUpdateService.foldEmbossedName(stored);
        return new CardDetails(DetailGroup.OLD,
                codec.movePic9(stored.cardAcctId(), CardDetails.ACCTID_LENGTH),
                picX(stored.cardNum(), CardDetails.CARDID_LENGTH),
                codec.movePic9(stored.cardCvvCd(), CardDetails.CVV_CD_LENGTH),
                picX(folded.cardEmbossedName(), CardDetails.CRDNAME_LENGTH),
                folded.cardExpiraionDateYear(),
                folded.cardExpiraionDateMonth(),
                folded.cardExpiraionDateDay(),
                picX(folded.cardActiveStatus(), CardDetails.CRDSTCD_LENGTH));
    }

    private static CardDetails newDetailsOf(String acctid, String cardid, String crdname,
                                            String crdstcd, String expmon, String expyear,
                                            String expday) {
        return new CardDetails(DetailGroup.NEW, picX(acctid, CardDetails.ACCTID_LENGTH),
                picX(cardid, CardDetails.CARDID_LENGTH), "   ",
                picX(crdname, CardDetails.CRDNAME_LENGTH), expyear, expmon, expday, crdstcd);
    }

    private static List<ExpectedRecord> rewriteAt(int writeIndex, Map<String, String> fields,
                                                  String image) {
        return List.of(new ExpectedRecord(CARDDAT, writeIndex, fields, image));
    }

    private static List<ExpectedRecord> noRewrite() {
        return List.of();
    }

    private static List<String> replaceRow(int rowIndex, String image) {
        List<String> rows = new ArrayList<>(SEED_ROWS);
        rows.set(rowIndex, image);
        return List.copyOf(rows);
    }

    private static List<ExpectedRecord> finalStateOf(List<String> rows) {
        List<ExpectedRecord> expectations = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            expectations.add(new ExpectedRecord(CARDDAT, index, Map.of(), rows.get(index)));
        }
        return List.copyOf(expectations);
    }

    private static List<EmittedMessage> blankReturnMessage() {
        return List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE,
                CardUpdateService.RETURN_MESSAGE_OFF));
    }

    private static List<EmittedMessage> returnMessage(String text) {
        return List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE,
                picX(text, CardUpdateService.RETURN_MESSAGE_LENGTH)));
    }

    private static Map<String, String> unreceivedPromptScreen() {
        Map<String, String> fields = new LinkedHashMap<>(screenHeader());
        fields.put("ACCTSIDO", lowValues("ACCTSID"));
        fields.put("CARDSIDO", lowValues("CARDSID"));
        fields.put("CRDNAMEO", lowValues("CRDNAME"));
        fields.put("CRDSTCDO", lowValues("CRDSTCD"));
        fields.put("EXPMONO", lowValues("EXPMON"));
        fields.put("EXPYEARO", lowValues("EXPYEAR"));
        fields.put("EXPDAYO", lowValues("EXPDAY"));
        fields.put("INFOMSGO", picX(INFO_PROMPT_FOR_KEYS, MAP_FIELD_WIDTHS.get("INFOMSG")));
        fields.put("ERRMSGO", picX("", MAP_FIELD_WIDTHS.get("ERRMSG")));
        fields.put("FKEYSO", lowValues("FKEYS"));
        fields.put("FKEYSCO", lowValues("FKEYSC"));
        return Map.copyOf(fields);
    }

    private static Map<String, String> receivedPromptScreen(String acctsid, String cardsid,
                                                            String errorText) {
        Map<String, String> fields = new LinkedHashMap<>(screenHeader());
        fields.put("ACCTSIDO", acctsid);
        fields.put("CARDSIDO", cardsid);
        fields.put("CRDNAMEO", lowValues("CRDNAME"));
        fields.put("CRDSTCDO", lowValues("CRDSTCD"));
        fields.put("EXPMONO", lowValues("EXPMON"));
        fields.put("EXPYEARO", lowValues("EXPYEAR"));
        fields.put("EXPDAYO", lowValues("EXPDAY"));
        fields.put("INFOMSGO", picX(INFO_PROMPT_FOR_KEYS, MAP_FIELD_WIDTHS.get("INFOMSG")));
        fields.put("ERRMSGO", picX(errorText, MAP_FIELD_WIDTHS.get("ERRMSG")));
        fields.put("FKEYSO", lowValues("FKEYS"));
        fields.put("FKEYSCO", lowValues("FKEYSC"));
        return Map.copyOf(fields);
    }

    private static Map<String, String> screenHeader() {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("TRNNAMEO", THIS_TRANID);
        header.put("TITLE01O", picX("      AWS Mainframe Modernization", 40));
        header.put("CURDATEO", "07/19/22");
        header.put("PGMNAMEO", THIS_PGM);
        header.put("TITLE02O", picX("              CardDemo", 40));
        header.put("CURTIMEO", "23:12:33");
        return header;
    }

    private static String lowValues(String stem) {
        return "\u0000".repeat(MAP_FIELD_WIDTHS.get(stem));
    }

    private static Map<String, String> defaultColours() {
        Map<String, String> colours = new LinkedHashMap<>();
        for (String stem : MAP_FIELDS) {
            colours.put(stem + "C", "EXPDAY".equals(stem) ? "DFHBMDAR" : "DFHDFCOL");
        }
        return Map.copyOf(colours);
    }

    private static Map<String, String> erroredColours(String... erroredStems) {
        Map<String, String> colours = new LinkedHashMap<>(defaultColours());
        for (String erroredStem : erroredStems) {
            colours.put(erroredStem + "C", "DFHRED");
        }
        return Map.copyOf(colours);
    }

    private static Map<String, String> navigationImage(NavigationContext context) {
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
        return Map.copyOf(image);
    }

    private static NavigationContext navigationFrom(Map<String, String> declared) {
        NavigationContext initial = NavigationContext.empty();
        if (declared.isEmpty()) {
            return initial;
        }
        return new NavigationContext(
                text(declared, NavigationContext.FROM_TRANID_FIELD, initial.fromTranid()),
                text(declared, NavigationContext.FROM_PROGRAM_FIELD, initial.fromProgram()),
                text(declared, NavigationContext.TO_TRANID_FIELD, initial.toTranid()),
                text(declared, NavigationContext.TO_PROGRAM_FIELD, initial.toProgram()),
                text(declared, NavigationContext.USER_ID_FIELD, initial.userId()),
                text(declared, NavigationContext.USER_TYPE_FIELD, initial.userType()),
                (int) number(declared, NavigationContext.PGM_CONTEXT_FIELD, initial.pgmContext()),
                (int) number(declared, NavigationContext.CUST_ID_FIELD, initial.custId()),
                text(declared, NavigationContext.CUST_FNAME_FIELD, initial.custFname()),
                text(declared, NavigationContext.CUST_MNAME_FIELD, initial.custMname()),
                text(declared, NavigationContext.CUST_LNAME_FIELD, initial.custLname()),
                number(declared, NavigationContext.ACCT_ID_FIELD, initial.acctId()),
                text(declared, NavigationContext.ACCT_STATUS_FIELD, initial.acctStatus()),
                number(declared, NavigationContext.CARD_NUM_FIELD, initial.cardNum()),
                text(declared, NavigationContext.LAST_MAP_FIELD, initial.lastMap()),
                text(declared, NavigationContext.LAST_MAPSET_FIELD, initial.lastMapset()));
    }

    private static String text(Map<String, String> declared, String field, String fallback) {
        String value = declared.get(field);
        return value == null ? fallback : value;
    }

    private static long number(Map<String, String> declared, String field, long fallback) {
        String value = declared.get(field);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Long.parseLong(value.trim());
    }

    private static String digits(long value, int length) {
        return new FixedWidthCodec(FIXTURE_CHARSET).movePic9(value, length);
    }

    private static NavigationContext navigationFromCardList() {
        return NavigationContext.empty()
                .withFromTranid(CARD_LIST_TRANID)
                .withFromProgram(CARD_LIST_PGM)
                .withUserId("USER0001")
                .withUserTypeUser()
                .withPgmReenter()
                .withAcctId(50L)
                .withCardNum(500_024_453_765_740L)
                .withLastMapset(THIS_MAPSET)
                .withLastMap(THIS_MAP);
    }

    private static CardUpdateRequest requestWith(NavigationContext context) {
        CardUpdateRequest request = new CardUpdateRequest();
        request.setNavigationContext(context);
        return request;
    }

    private static CardUpdateRequest requestFrom(ScreenRequest declared) {
        return declared.eibcalen() == EIBCALEN_NONE && declared.commarea().isEmpty()
                && declared.mapFields().isEmpty()
                ? null
                : requestFrom(declared.commarea(), declared.mapFields(),
                        programAreaFrom(declared));
    }

    private static CardUpdateRequest.CommArea programAreaFrom(ScreenRequest declared) {
        String image = declared.commarea().get(PROGRAM_AREA_KEY);
        return image == null
                ? CardUpdateRequest.CommArea.initialised()
                : CardUpdateRequest.CommArea.decode(image.getBytes(FIXTURE_CHARSET),
                        new FixedWidthCodec(FIXTURE_CHARSET));
    }

    private static CardUpdateRequest requestFrom(Map<String, String> commarea,
                                                 Map<String, String> mapFields,
                                                 CardUpdateRequest.CommArea programArea) {
        if (commarea.isEmpty() && mapFields.isEmpty()) {
            return null;
        }
        CardUpdateRequest request = new CardUpdateRequest();
        request.setNavigationContext(navigationFrom(commarea));
        request.setCommArea(programArea);
        request.setAcctsid(mapFields.get("ACCTSIDI"));
        request.setCardsid(mapFields.get("CARDSIDI"));
        request.setCrdname(mapFields.get("CRDNAMEI"));
        request.setCrdstcd(mapFields.get("CRDSTCDI"));
        request.setExpmon(mapFields.get("EXPMONI"));
        request.setExpyear(mapFields.get("EXPYEARI"));
        request.setExpday(mapFields.get("EXPDAYI"));
        return request;
    }

    private static CardUpdateResponse paint(CardUpdateRequest request, int eibcalen, byte aid) {
        return paint(request, eibcalen, aid, KEY_01);
    }

    private static CardUpdateResponse paint(CardUpdateRequest request, int eibcalen, byte aid,
                                            String pathCardNumber) {
        CardRepository repository = fixtureRepository(new ArrayList<>(SEED_ROWS), Map.of());
        CardUpdateController controller = new CardUpdateController(repository,
                serviceOver(repository), ParityHarness.usAscii().clock(),
                ConversationStateSealFixture.seal(), FIXTURE_CHARSET);
        ScreenResponse<CardUpdateResponse> body = controller.updateCardDetail(pathCardNumber, request,
                null, eibcalen, Byte.toUnsignedInt(aid)).getBody();
        return java.util.Objects.requireNonNull(body, "every path answers with a body").screen();
    }

    private static byte aidByte(String mnemonic) {
        if (mnemonic == null || mnemonic.isBlank()) {
            return CicsAid.DFHENTER;
        }
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException("\"" + mnemonic + "\" is not a DFHAID mnemonic. The "
                + "copybook is IBM-supplied and absent from this repository, so common.CicsAid is the "
                + "single reproduction of it and the permitted names come from there.");
    }

    private static String token(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
