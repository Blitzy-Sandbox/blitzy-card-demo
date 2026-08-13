package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.card.CardListController;
import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardRepository.BrowseDirection;
import com.vsergeychik.carddemo.card.CardRepository.CardBrowse;
import com.vsergeychik.carddemo.card.CardRepository.CardReadResult;
import com.vsergeychik.carddemo.card.dto.CardListRequest;
import com.vsergeychik.carddemo.card.dto.CardListRequest.FirstListRow;
import com.vsergeychik.carddemo.card.dto.CardListRequest.ListRow;
import com.vsergeychik.carddemo.card.dto.CardListRequest.PageCursor;
import com.vsergeychik.carddemo.card.dto.CardListRequest.StopperListRow;
import com.vsergeychik.carddemo.card.dto.CardListResponse;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest;
import com.vsergeychik.carddemo.card.dto.CardSelectResponse;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.RepositoryOperation;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Parity gate for {@code COCRDLIC}, the CICS online card-list transaction {@code CCLI}
 * [{@code app/csd/CARDDEMO.CSD}], projected onto {@code GET /api/cards}.
 */
class COCRDLICParityTest {
    private static final String PROGRAM = "COCRDLIC";

    private static final String CARDDAT = CardRepository.BASE_DD_NAME;

    private static final int FIRST_ROW = 1;

    private static final int MAX_SCREEN_LINES = 7;

    private static final String THIS_PROGRAM = "COCRDLIC";

    private static final String THIS_TRANID = "CCLI";

    private static final String MENU_PROGRAM = "COMEN01C";

    private static final String CARD_DETAIL_PROGRAM = "COCRDSLC";

    private static final String CARD_UPDATE_PROGRAM = "COCRDUPC";

    private static final char SPACE = ' ';

    private static final char LOW_VALUE = '\u0000';

    private static final String CURSOR_FIELD_PREFIX = "WS-";

    private static final Set<String> READABLE_MAP_ITEMS = buildReadableMapItems();

    private static final Map<String, Byte> AID_BY_MNEMONIC = buildAidByMnemonic();

    private static final String CARD_FIXTURE = "fixtures/carddata.txt";

    private static final String PINNED_CLOCK = "2022-07-19T23:12:34";

    private ParityHarness harness;

    @BeforeEach
    void buildHarness() {
        harness = ParityHarness.usAscii();
    }

    static List<ParityCase> cases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);
        if (loaded.size() != ParityHarness.CASES_PER_PROGRAM) {
            throw new IllegalStateException("Program " + PROGRAM + " must have exactly "
                    + ParityHarness.CASES_PER_PROGRAM + " parity cases but "
                    + ParityHarness.CASE_RESOURCE_ROOT + PROGRAM + "/ yielded " + loaded.size()
                    + ". The gate is stated as 20 cases per program and a module is not complete until "
                    + "its diff count is zero across all 20 of them, so a short list is a smaller gate "
                    + "wearing the same name.");
        }
        return loaded;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("COCRDLIC parity: every field of every case matches, diff count zero")
    void parityCaseHasZeroDiffs(ParityCase parityCase) {
        DiffResult result = harness.judge(parityCase, UnitKind.CONTROLLER_POJO,
                this::invokeCardList);

        assertThat(result.count())
                .as("%s/%s produced %d difference(s):%n%s", PROGRAM, parityCase.caseId(),
                        result.count(), result.render())
                .isZero();
    }

    private UnitOutcome invokeCardList(Invocation invocation) {
        SeededDataset carddat = invocation.dataset(CARDDAT);
        FixedWidthCodec codec = invocation.codec();

        CardListController controller = new CardListController(
                stubbedCardRepository(invocation, carddat), codec, invocation.clock());

        CardListResponse painted = controller.getCards(
                requestFrom(invocation, codec),
                Integer.valueOf(Byte.toUnsignedInt(aidByteOf(invocation))),
                null).screen();

        return invocation.recorder()
                .finalStateUnchanged(carddat, CardRecord.LAYOUT)
                .response(project(painted, codec))
                .returnCode(FileStatus.APPL_AOK)
                .build();
    }

    private CardRepository stubbedCardRepository(Invocation invocation, SeededDataset carddat) {
        List<String> rows = new ArrayList<>(carddat.rows());
        rows.sort(Comparator.comparing(COCRDLICParityTest::primaryKeyOf));

        CardRepository repository = Mockito.mock(CardRepository.class);
        Mockito.when(repository.startBrowse(Mockito.anyString(), Mockito.any(BrowseDirection.class)))
                .thenAnswer(call -> {
                    Optional<ParityCase.ForcedOutcome> declared = declaredReadOutcome(invocation);
                    return browseOver(rows, call.getArgument(0), call.getArgument(1),
                            forcedFailure(declared), forcesDuplicateKey(declared));
                });
        return repository;
    }

    private static Optional<ParityCase.ForcedOutcome> declaredReadOutcome(Invocation invocation) {
        if (!invocation.hasForcedOutcome(RepositoryOperation.READ_NEXT)) {
            return Optional.empty();
        }
        ParityCase.ForcedOutcome forced = invocation.forcedOutcome(RepositoryOperation.READ_NEXT);
        if (forced.outcome() != FileStatus.Outcome.OTHER
                && forced.outcome() != FileStatus.Outcome.DUPLICATE) {
            throw new IllegalArgumentException("Case " + invocation.program() + '/'
                    + invocation.caseId() + " forces read outcome " + forced.outcome()
                    + ", but the only read outcomes COCRDLIC cannot be driven to by seeding rows are "
                    + FileStatus.Outcome.OTHER + " and " + FileStatus.Outcome.DUPLICATE
                    + ". OK arrives by seeding a row, END_OF_FILE by seeding fewer rows than the "
                    + "browse asks for, and NOT_FOUND is never returned by a browse at all - forcing "
                    + "any of those would hide the seed that should have produced it.");
        }
        return Optional.of(forced);
    }

    private static Optional<CardReadResult> forcedFailure(
            Optional<ParityCase.ForcedOutcome> declared) {
        if (declared.isEmpty() || declared.get().outcome() != FileStatus.Outcome.OTHER) {
            return Optional.empty();
        }
        ParityCase.ForcedOutcome forced = declared.get();
        int resp = forced.resp() == null ? FileStatus.INVREQ : forced.resp();
        int resp2 = forced.resp2() == null ? FileStatus.NO_REASON_CODE : forced.resp2();
        return Optional.of(CardReadResult.reportedFailure(resp, resp2));
    }

    private static boolean forcesDuplicateKey(Optional<ParityCase.ForcedOutcome> declared) {
        return declared.isPresent()
                && declared.get().outcome() == FileStatus.Outcome.DUPLICATE;
    }

    private static CardBrowse browseOver(List<String> rows, String anchor,
            BrowseDirection direction, Optional<CardReadResult> forced) {
        return browseOver(rows, anchor, direction, forced, false);
    }

    private static CardBrowse browseOver(List<String> rows, String anchor,
            BrowseDirection direction, Optional<CardReadResult> forced,
            boolean reportDuplicateKey) {
        CardBrowse browse = Mockito.mock(CardBrowse.class);
        int[] position = new int[1];
        boolean[] positioned = new boolean[1];

        Mockito.when(browse.readNext()).thenAnswer(call -> forced.orElseGet(() ->
                asDeclared(read(rows, anchor, direction, position, positioned),
                        reportDuplicateKey)));
        Mockito.when(browse.readPrev()).thenAnswer(call -> forced.orElseGet(() ->
                asDeclared(read(rows, anchor, direction, position, positioned),
                        reportDuplicateKey)));
        return browse;
    }

    private static CardReadResult asDeclared(CardReadResult served, boolean reportDuplicateKey) {
        if (!reportDuplicateKey || !served.isRecordReturned()) {
            return served;
        }
        return CardReadResult.duplicateKey(served.requireRecord(), served.requireStoredImage());
    }

    private static CardReadResult read(List<String> rows, String anchor, BrowseDirection direction,
            int[] position, boolean[] positioned) {
        if (!positioned[0]) {
            positioned[0] = true;
            int anchored = anchorIndex(rows, anchor, direction);
            if (anchored < 0) {
                return CardReadResult.endOfFile();
            }
            position[0] = anchored;
        } else {
            position[0] += direction == BrowseDirection.FORWARD ? 1 : -1;
        }
        if (position[0] < 0 || position[0] >= rows.size()) {
            return CardReadResult.endOfFile();
        }
        String image = rows.get(position[0]);
        return CardReadResult.normal(CardRecord.decodeImage(image, ParityHarness.FIXTURE_CHARSET),
                image);
    }

    private static int anchorIndex(List<String> rows, String anchor, BrowseDirection direction) {
        String key = anchor.length() > CardRecord.CARD_NUM_LENGTH
                ? anchor.substring(0, CardRecord.CARD_NUM_LENGTH)
                : anchor;
        if (direction == BrowseDirection.FORWARD) {
            for (int index = 0; index < rows.size(); index++) {
                if (primaryKeyOf(rows.get(index)).compareTo(key) >= 0) {
                    return index;
                }
            }
            return -1;
        }
        for (int index = rows.size() - 1; index >= 0; index--) {
            if (primaryKeyOf(rows.get(index)).compareTo(key) <= 0) {
                return index;
            }
        }
        return -1;
    }

    private static String primaryKeyOf(String row) {
        return row.substring(0, CardRecord.CARD_NUM_LENGTH);
    }

    private static CardListRequest requestFrom(Invocation invocation, FixedWidthCodec codec) {
        if (invocation.eibcalen() == 0) {
            return null;
        }
        CardListRequest request = new CardListRequest();
        request.setNavigationContext(navigationFrom(invocation.commarea(), codec));
        request.setPageCursor(pageCursorFrom(invocation.commarea(), codec, invocation));

        Map<String, String> map = invocation.mapFields();
        request.setAcctsid(codec.movePicX(mapFieldOf(map, "ACCTSIDI", invocation),
                CardListRequest.ACCTSID_LENGTH));
        request.setCardsid(codec.movePicX(mapFieldOf(map, "CARDSIDI", invocation),
                CardListRequest.CARDSID_LENGTH));
        request.setRows(rowsFrom(map, invocation, codec));
        requireOnlyReadableMapItems(map, invocation);
        return request;
    }

    private static List<ListRow> rowsFrom(Map<String, String> map, Invocation invocation,
            FixedWidthCodec codec) {
        List<ListRow> rows = new ArrayList<>(MAX_SCREEN_LINES);
        for (int row = FIRST_ROW; row <= MAX_SCREEN_LINES; row++) {
            String select = codec.movePicX(mapFieldOf(map, "CRDSEL" + row + "I", invocation),
                    CardListRequest.CRDSEL_LENGTH);
            String acctNo = codec.movePicX(mapFieldOf(map, "ACCTNO" + row + "I", invocation),
                    CardListRequest.ACCTNO_LENGTH);
            String cardNum = codec.movePicX(mapFieldOf(map, "CRDNUM" + row + "I", invocation),
                    CardListRequest.CRDNUM_LENGTH);
            String status = codec.movePicX(mapFieldOf(map, "CRDSTS" + row + "I", invocation),
                    CardListRequest.CRDSTS_LENGTH);
            rows.add(row == FIRST_ROW
                    ? new FirstListRow(select, acctNo, cardNum, status)
                    : new StopperListRow(select, String.valueOf(LOW_VALUE), acctNo, cardNum, status));
        }
        return rows;
    }

    private static String mapFieldOf(Map<String, String> map, String item, Invocation invocation) {
        if (!READABLE_MAP_ITEMS.contains(item)) {
            throw new IllegalStateException("Case " + invocation.program() + '/'
                    + invocation.caseId() + " asked for map item " + item
                    + ", which this adapter does not read. The readable items are "
                    + READABLE_MAP_ITEMS + '.');
        }
        String value = map.get(item);
        return value == null ? String.valueOf(LOW_VALUE) : value;
    }

    private static void requireOnlyReadableMapItems(Map<String, String> map, Invocation invocation) {
        List<String> ignored = new ArrayList<>();
        for (String item : map.keySet()) {
            if (!READABLE_MAP_ITEMS.contains(item)) {
                ignored.add(item);
            }
        }
        if (!ignored.isEmpty()) {
            throw new IllegalStateException("Case " + invocation.program() + '/'
                    + invocation.caseId() + " states map item(s) " + ignored
                    + " that COCRDLIC never reads - 2100-RECEIVE-SCREEN at app/cbl/COCRDLIC.cbl:962-979 "
                    + "moves nothing out of the header items, INFOMSGI or ERRMSGI. Setting one drives "
                    + "nothing, so the case would assert less than it appears to. The items the program "
                    + "reads are " + READABLE_MAP_ITEMS + '.');
        }
    }

    private static NavigationContext navigationFrom(Map<String, String> commarea,
            FixedWidthCodec codec) {
        NavigationContext context = NavigationContext.empty();
        for (Map.Entry<String, String> entry : commarea.entrySet()) {
            String field = entry.getKey();
            if (field.startsWith(CURSOR_FIELD_PREFIX)) {
                continue;
            }
            String value = entry.getValue();
            context = switch (field) {
                case NavigationContext.FROM_TRANID_FIELD -> context.withFromTranid(
                        codec.movePicX(value, NavigationContext.FROM_TRANID_LENGTH));
                case NavigationContext.FROM_PROGRAM_FIELD -> context.withFromProgram(
                        codec.movePicX(value, NavigationContext.FROM_PROGRAM_LENGTH));
                case NavigationContext.TO_TRANID_FIELD -> context.withToTranid(
                        codec.movePicX(value, NavigationContext.TO_TRANID_LENGTH));
                case NavigationContext.TO_PROGRAM_FIELD -> context.withToProgram(
                        codec.movePicX(value, NavigationContext.TO_PROGRAM_LENGTH));
                case NavigationContext.USER_ID_FIELD -> context.withUserId(
                        codec.movePicX(value, NavigationContext.USER_ID_LENGTH));
                case NavigationContext.USER_TYPE_FIELD -> context.withUserType(
                        codec.movePicX(value, NavigationContext.USER_TYPE_LENGTH));
                case NavigationContext.PGM_CONTEXT_FIELD -> context.withPgmContext(
                        codec.decodePic9AsInt(value));
                case NavigationContext.CUST_ID_FIELD -> context.withCustId(
                        codec.decodePic9AsInt(value));
                case NavigationContext.CUST_FNAME_FIELD -> context.withCustFname(
                        codec.movePicX(value, NavigationContext.CUST_FNAME_LENGTH));
                case NavigationContext.CUST_MNAME_FIELD -> context.withCustMname(
                        codec.movePicX(value, NavigationContext.CUST_MNAME_LENGTH));
                case NavigationContext.CUST_LNAME_FIELD -> context.withCustLname(
                        codec.movePicX(value, NavigationContext.CUST_LNAME_LENGTH));
                case NavigationContext.ACCT_ID_FIELD -> context.withAcctId(
                        codec.decodePic9(value));
                case NavigationContext.ACCT_STATUS_FIELD -> context.withAcctStatus(
                        codec.movePicX(value, NavigationContext.ACCT_STATUS_LENGTH));
                case NavigationContext.CARD_NUM_FIELD -> context.withCardNum(
                        codec.decodePic9(value));
                case NavigationContext.LAST_MAP_FIELD -> context.withLastMap(
                        codec.movePicX(value, NavigationContext.LAST_MAP_LENGTH));
                case NavigationContext.LAST_MAPSET_FIELD -> context.withLastMapset(
                        codec.movePicX(value, NavigationContext.LAST_MAPSET_LENGTH));
                default -> throw new IllegalStateException("Commarea field " + field
                        + " is not one of the sixteen CARDDEMO-COMMAREA items app/cpy/COCOM01Y.cpy "
                        + "defines. COCRDLIC's own extension is WS-THIS-PROGCOMMAREA at "
                        + "app/cbl/COCRDLIC.cbl:229-248, whose fields are named WS-CA-... and "
                        + "WS-RETURN-FLAG; those are read by pageCursorFrom, not here.");
            };
        }
        return context;
    }

    private static PageCursor pageCursorFrom(Map<String, String> commarea, FixedWidthCodec codec,
            Invocation invocation) {
        PageCursor declared = PageCursor.firstPage();
        String lastCardNum = declared.lastCardKey().cardNum();
        long lastAcctId = declared.lastCardKey().acctId();
        String firstCardNum = declared.firstCardKey().cardNum();
        long firstAcctId = declared.firstCardKey().acctId();
        int screenNum = declared.screenNum();
        int lastPageDisplayed = declared.lastPageDisplayed();
        String nextPageInd = declared.nextPageInd();
        String returnFlag = declared.returnFlag();

        for (Map.Entry<String, String> entry : commarea.entrySet()) {
            String field = entry.getKey();
            if (!field.startsWith(CURSOR_FIELD_PREFIX)) {
                continue;
            }
            String value = entry.getValue();
            switch (field) {
                case "WS-CA-LAST-CARD-NUM" -> lastCardNum =
                        codec.movePicX(value, CardListRequest.CURSOR_CARD_NUM_LENGTH);
                case "WS-CA-LAST-CARD-ACCT-ID" -> lastAcctId = codec.decodePic9(value);
                case "WS-CA-FIRST-CARD-NUM" -> firstCardNum =
                        codec.movePicX(value, CardListRequest.CURSOR_CARD_NUM_LENGTH);
                case "WS-CA-FIRST-CARD-ACCT-ID" -> firstAcctId = codec.decodePic9(value);
                case "WS-CA-SCREEN-NUM" -> screenNum = codec.decodePic9AsInt(value);
                case "WS-CA-LAST-PAGE-DISPLAYED" -> lastPageDisplayed =
                        codec.decodePic9AsInt(value);
                case "WS-CA-NEXT-PAGE-IND" -> nextPageInd =
                        codec.movePicX(value, CardListRequest.NEXT_PAGE_IND_LENGTH);
                case "WS-RETURN-FLAG" -> returnFlag =
                        codec.movePicX(value, CardListRequest.RETURN_FLAG_LENGTH);
                default -> throw new IllegalStateException("Case " + invocation.program() + '/'
                        + invocation.caseId() + " states commarea field " + field
                        + ", which is not one of the eight WS-THIS-PROGCOMMAREA cursor items "
                        + "app/cbl/COCRDLIC.cbl:229-248 declares. The eight are "
                        + "WS-CA-LAST-CARD-NUM, WS-CA-LAST-CARD-ACCT-ID, WS-CA-FIRST-CARD-NUM, "
                        + "WS-CA-FIRST-CARD-ACCT-ID, WS-CA-SCREEN-NUM, WS-CA-LAST-PAGE-DISPLAYED, "
                        + "WS-CA-NEXT-PAGE-IND and WS-RETURN-FLAG. The 196-byte row table that "
                        + "follows them travels as the ACCTNOnI, CRDNUMnI and CRDSTSnI map items.");
            }
        }
        return new PageCursor(new CardListRequest.CardKey(lastCardNum, lastAcctId),
                new CardListRequest.CardKey(firstCardNum, firstAcctId),
                screenNum, lastPageDisplayed, nextPageInd, returnFlag);
    }

    private static ObservedResponse project(CardListResponse painted, FixedWidthCodec codec) {
        boolean transferred = !isBlank(painted.getNextProgram());
        return new ObservedResponse(
                trimmedOrNull(painted.getNextProgram()),
                trimmedOrNull(painted.getNextMapset()),
                trimmedOrNull(painted.getNextMap()),
                navigationImageOf(painted.getNavigationContext(), codec),
                transferred ? List.of() : List.of(sendOf(painted)),
                cursorItemOf(painted.screenMetadata()),
                transferred ? Termination.XCTL : Termination.RETURN_TRANSID);
    }

    private static ObservedSend sendOf(CardListResponse painted) {
        return new ObservedSend(painted.fieldImages(), attributesOf(painted));
    }

    private static Map<String, String> attributesOf(CardListResponse painted) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, ScreenMetadata.FieldMetadata> quad
                : painted.screenMetadata().fields().entrySet()) {
            int colour = quad.getValue().colour();
            if (colour == 0) {
                continue;
            }
            attributes.put(quad.getKey() + 'C', attributeMnemonicOf((byte) colour));
        }
        return attributes;
    }

    private static String attributeMnemonicOf(byte attribute) {
        Byte key = Byte.valueOf(attribute);
        String colour = BmsAttributes.COLOUR_MNEMONICS.get(key);
        if (colour != null) {
            return colour;
        }
        String field = BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.get(key);
        if (field != null) {
            return field;
        }
        String highlight = BmsAttributes.HIGHLIGHT_MNEMONICS.get(key);
        return highlight != null ? highlight : BmsAttributes.toHex(attribute);
    }

    private static String cursorItemOf(ScreenMetadata metadata) {
        String label = metadata.cursorField();
        return label == null ? null : label + 'L';
    }

    private static Map<String, String> navigationImageOf(NavigationContext context,
            FixedWidthCodec codec) {
        Map<String, String> images = new LinkedHashMap<>();
        images.put(NavigationContext.FROM_TRANID_FIELD,
                codec.movePicX(context.fromTranid(), NavigationContext.FROM_TRANID_LENGTH));
        images.put(NavigationContext.FROM_PROGRAM_FIELD,
                codec.movePicX(context.fromProgram(), NavigationContext.FROM_PROGRAM_LENGTH));
        images.put(NavigationContext.TO_TRANID_FIELD,
                codec.movePicX(context.toTranid(), NavigationContext.TO_TRANID_LENGTH));
        images.put(NavigationContext.TO_PROGRAM_FIELD,
                codec.movePicX(context.toProgram(), NavigationContext.TO_PROGRAM_LENGTH));
        images.put(NavigationContext.USER_ID_FIELD,
                codec.movePicX(context.userId(), NavigationContext.USER_ID_LENGTH));
        images.put(NavigationContext.USER_TYPE_FIELD,
                codec.movePicX(context.userType(), NavigationContext.USER_TYPE_LENGTH));
        images.put(NavigationContext.PGM_CONTEXT_FIELD,
                codec.movePic9(context.pgmContext(), NavigationContext.PGM_CONTEXT_LENGTH));
        images.put(NavigationContext.CUST_ID_FIELD,
                codec.movePic9(context.custId(), NavigationContext.CUST_ID_LENGTH));
        images.put(NavigationContext.CUST_FNAME_FIELD,
                codec.movePicX(context.custFname(), NavigationContext.CUST_FNAME_LENGTH));
        images.put(NavigationContext.CUST_MNAME_FIELD,
                codec.movePicX(context.custMname(), NavigationContext.CUST_MNAME_LENGTH));
        images.put(NavigationContext.CUST_LNAME_FIELD,
                codec.movePicX(context.custLname(), NavigationContext.CUST_LNAME_LENGTH));
        images.put(NavigationContext.ACCT_ID_FIELD,
                codec.movePic9(context.acctId(), NavigationContext.ACCT_ID_LENGTH));
        images.put(NavigationContext.ACCT_STATUS_FIELD,
                codec.movePicX(context.acctStatus(), NavigationContext.ACCT_STATUS_LENGTH));
        images.put(NavigationContext.CARD_NUM_FIELD,
                codec.movePic9(context.cardNum(), NavigationContext.CARD_NUM_LENGTH));
        images.put(NavigationContext.LAST_MAP_FIELD,
                codec.movePicX(context.lastMap(), NavigationContext.LAST_MAP_LENGTH));
        images.put(NavigationContext.LAST_MAPSET_FIELD,
                codec.movePicX(context.lastMapset(), NavigationContext.LAST_MAPSET_LENGTH));
        return images;
    }

    private static byte aidByteOf(Invocation invocation) {
        String mnemonic = invocation.aid();
        if (mnemonic == null) {
            return CicsAid.DFHENTER;
        }
        Byte aid = AID_BY_MNEMONIC.get(mnemonic);
        if (aid == null) {
            throw new IllegalStateException("Case " + invocation.program() + '/'
                    + invocation.caseId() + " names AID " + mnemonic
                    + ", which common.CicsAid does not publish. The permitted set is "
                    + new TreeSet<>(AID_BY_MNEMONIC.keySet()) + '.');
        }
        return aid.byteValue();
    }

    private static boolean isBlank(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != SPACE && character != LOW_VALUE) {
                return false;
            }
        }
        return true;
    }

    private static String trimmedOrNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static Set<String> buildReadableMapItems() {
        Set<String> items = new TreeSet<>();
        items.add("ACCTSIDI");
        items.add("CARDSIDI");
        for (int row = FIRST_ROW; row <= MAX_SCREEN_LINES; row++) {
            items.add("CRDSEL" + row + "I");
            items.add("ACCTNO" + row + "I");
            items.add("CRDNUM" + row + "I");
            items.add("CRDSTS" + row + "I");
        }
        return Set.copyOf(items);
    }

    private static Map<String, Byte> buildAidByMnemonic() {
        Map<String, Byte> byMnemonic = new LinkedHashMap<>();
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            byMnemonic.put(entry.getValue(), entry.getKey());
        }
        return Map.copyOf(byMnemonic);
    }

    @Test
    @DisplayName("G39: page size 7 is behaviour - seven rows placed, an eighth row refused outright")
    void pageSizeIsBehaviourNotConfiguration() {
        CardListResponse painted = listFrom(fixtureRows(0, 50), CicsAid.DFHENTER);

        assertThat(painted.screenRows())
                .as("WS-SCREEN-DATA is OCCURS 7 TIMES [app/cbl/COCRDLIC.cbl:76]")
                .hasSize(MAX_SCREEN_LINES);
        for (int row = FIRST_ROW; row <= MAX_SCREEN_LINES; row++) {
            assertThat(painted.screenRow(row).rowCardNum().trim())
                    .as("row %d of the first page is placed", row)
                    .isNotEmpty();
        }

        assertThatExceptionOfType(IndexOutOfBoundsException.class)
                .as("an eighth row is refused, not accommodated")
                .isThrownBy(() -> painted.screenRow(MAX_SCREEN_LINES + 1))
                .withMessageContaining("OCCURS 7 TIMES");
        assertThatExceptionOfType(IndexOutOfBoundsException.class)
                .isThrownBy(() -> CardListResponse.acctnoItem(MAX_SCREEN_LINES + 1));
        assertThatExceptionOfType(IndexOutOfBoundsException.class)
                .as("and neither does row zero, because COBOL subscripts start at one")
                .isThrownBy(() -> painted.screenRow(0));
        assertThat(CardListRequest.PAGE_SIZE)
                .as("WS-MAX-SCREEN-LINES VALUE 7 [app/cbl/COCRDLIC.cbl:177-178]")
                .isEqualTo(MAX_SCREEN_LINES)
                .isEqualTo(CardListResponse.PAGE_SIZE)
                .isEqualTo(CardListRequest.SELECT_FLAGS_LENGTH);
    }

    @Test
    @DisplayName("G28: page-up computes WS-SCRN-COUNTER = 7 + 1, spends one read, fills seven rows")
    void pageUpComputesScrnCounterAsMaxScreenLinesPlusOne() {
        List<String> rows = fixtureRows(0, 50);
        CardListRequest request = continuingRequest(cursorAt(rows, 7, 7, 2));

        CardListResponse painted = listFrom(rows, CicsAid.DFHPF7, request);

        for (int row = FIRST_ROW; row <= MAX_SCREEN_LINES; row++) {
            assertThat(painted.screenRow(row).rowCardNum())
                    .as("page-up placed fixture row %d into screen row %d", row, row)
                    .isEqualTo(primaryKeyOf(rows.get(row - 1)));
        }
        assertThat(painted.getPageCursor().screenNum()).isEqualTo(1);
        assertThat(painted.getPageCursor().firstCardNum()).isEqualTo(primaryKeyOf(rows.get(0)));
    }

    @Test
    @DisplayName("G37: PF8 reaches page two only through the cursor in the payload")
    void pageDownAdvancesOnlyByPayloadCursor() {
        List<String> rows = fixtureRows(0, 50);
        CardListController controller = controllerOver(rows);

        CardListResponse withCursor = controller
                .getCards(continuingRequest(cursorAt(rows, 7, 7, 1)), aid(CicsAid.DFHPF8), null)
                .screen();
        assertThat(withCursor.screenRow(FIRST_ROW).rowCardNum())
                .as(":488-489 MOVE WS-CA-LAST-CARD-NUM TO WS-CARD-RID-CARDNUM")
                .isEqualTo(primaryKeyOf(rows.get(7)));
        assertThat(withCursor.getPageCursor().screenNum())
                .as(":492 ADD +1 TO WS-CA-SCREEN-NUM")
                .isEqualTo(2);

        CardListResponse withoutCursor = controller
                .getCards(continuingRequest(PageCursor.firstPage()), aid(CicsAid.DFHPF8), null)
                .screen();
        assertThat(withoutCursor.screenRow(FIRST_ROW).rowCardNum())
                .isEqualTo(primaryKeyOf(rows.get(0)));
        assertThat(withoutCursor.getPageCursor().screenNum()).isEqualTo(1);
    }

    @Test
    @DisplayName("G37/G53: one controller, two calls, no state carried between them")
    void sequentialInvocationsShareNothingButThePayload() {
        List<String> rows = fixtureRows(0, 50);
        CardListController controller = controllerOver(rows);

        CardListRequest filtered = continuingRequest(PageCursor.firstPage());
        filtered.setAcctsid("NOTANUMBER!");
        filtered.setRows(selectionRows("S", rows, 1));
        CardListResponse first = controller.getCards(filtered, aid(CicsAid.DFHENTER), null).screen();
        assertThat(first.getErrmsgo().trim())
                .as(":1021-1023 the account-filter edit message")
                .startsWith("ACCOUNT FILTER");

        CardListResponse second = controller
                .getCards(continuingRequest(PageCursor.firstPage()), aid(CicsAid.DFHENTER), null)
                .screen();
        assertThat(second.getErrmsgo().trim())
                .as("the second call carries no filter, so no filter message survives")
                .isEmpty();
        assertThat(second.getAcctsido().trim())
                .as(":847 moves CC-ACCT-ID, which the second call never supplied")
                .isEmpty();
        assertThat(second.screenRow(FIRST_ROW).rowCardNum())
                .as("the second call lists from the top, unaffected by the first")
                .isEqualTo(primaryKeyOf(rows.get(0)));
    }

    @Test
    @DisplayName("G34: CC-ACCT-ID and CC-ACCT-ID-N are one span seen two ways, round trip both ways")
    void redefinesPairRoundTripsThroughBothAccessors() {
        CardScreenState state = new CardScreenState();

        state.setCcAcctId("00000000050");
        assertThat(state.getCcAcctIdN()).isEqualTo(50L);
        assertThat(state.isCcAcctIdNumeric()).isTrue();

        state.setCcAcctIdN(50L);
        assertThat(state.getCcAcctId())
                .hasSize(CardScreenState.CC_ACCT_ID_LENGTH)
                .isEqualTo("00000000050");

        state.setCcAcctIdToLowValues();
        assertThat(state.isCcAcctIdLowValues()).isTrue();
        assertThat(state.isCcAcctIdSpaces()).isFalse();

        state.setCcAcctId(CardScreenState.spaces(CardScreenState.CC_ACCT_ID_LENGTH));
        assertThat(state.isCcAcctIdSpaces()).isTrue();
        assertThat(state.isCcAcctIdLowValues()).isFalse();

        state.setCcAcctIdN(0L);
        assertThat(state.isCcAcctIdNZeros())
                .as(":1009 OR CC-ACCT-ID-N EQUAL ZEROS - the third not-supplied form")
                .isTrue();

        state.setCcAcctId("1234ABCD567");
        assertThat(state.getCcAcctId()).isEqualTo("1234ABCD567");
        assertThat(state.isCcAcctIdNumeric()).isFalse();
    }

    @Test
    @DisplayName("G19/G21: CVACT02Y is 150 bytes with its FILLER emitted, and round-trips exactly")
    void cardRecordIsOneHundredAndFiftyBytesIncludingFiller() {
        String image = fixtureRows(0, 1).get(0);
        assertThat(image)
                .as("app/data/ASCII/carddata.txt rows are exactly the copybook width")
                .hasSize(CardRecord.RECORD_LENGTH)
                .hasSize(150);

        CardRecord decoded = CardRecord.decodeImage(image, ParityHarness.FIXTURE_CHARSET);
        String reEncoded = decoded.encodeToImage(ParityHarness.FIXTURE_CHARSET);
        assertThat(reEncoded)
                .as("re-encoding restores every span, FILLER included")
                .hasSize(CardRecord.RECORD_LENGTH)
                .isEqualTo(image);
        assertThat(CardRecord.LAYOUT.recordLength()).isEqualTo(CardRecord.RECORD_LENGTH);
        assertThat(CardRepository.RECORD_LENGTH).isEqualTo(CardRecord.RECORD_LENGTH);
    }

    @Test
    @DisplayName("G45: CARDAIX is a second access path on one repository, never a second table")
    void cardAixIsAPathOverCarddatNotASecondTable() {
        assertThat(CardRepository.BASE_DD_NAME).isEqualTo("CARDDAT");
        assertThat(CardRepository.ALTERNATE_INDEX_DD_NAME).isEqualTo("CARDAIX");
        assertThat(CardRepository.BASE_CICS_FILE_NAME)
                .as(":213-214 LIT-CARD-FILE PIC X(8) VALUE 'CARDDAT '")
                .hasSize(CardRepository.CICS_FILE_NAME_LENGTH)
                .isEqualTo("CARDDAT ");
        assertThat(CardRepository.ALTERNATE_INDEX_CICS_FILE_NAME)
                .as(":215-217 LIT-CARD-FILE-ACCT-PATH PIC X(8) VALUE 'CARDAIX '")
                .hasSize(CardRepository.CICS_FILE_NAME_LENGTH)
                .isEqualTo("CARDAIX ");

        assertThat(CardRecord.cardDatPrimaryKeySpan().length())
                .as("CARD-NUM is the base key")
                .isEqualTo(CardRecord.CARD_NUM_LENGTH);
        assertThat(CardRecord.cardAixAlternateKeySpan().length())
                .as("CARD-ACCT-ID is the alternate key, over the same record")
                .isEqualTo(CardRecord.CARD_ACCT_ID_LENGTH);

        List<String> rows = fixtureRows(0, 50);
        CardRepository repository = stubbedRepositoryOver(rows);
        CardListRequest request = continuingRequest(PageCursor.firstPage());
        request.setAcctsid(CardScreenState.lowValues(CardListRequest.ACCTSID_LENGTH));

        controllerOver(repository).getCards(request, aid(CicsAid.DFHENTER), null);

        Mockito.verify(repository, Mockito.atLeastOnce())
                .startBrowse(Mockito.anyString(), Mockito.eq(BrowseDirection.FORWARD));
        Mockito.verify(repository, Mockito.never()).readByAccountIdViaAltIndex(Mockito.anyLong());
        Mockito.verify(repository, Mockito.never()).readByCardNumber(Mockito.anyString());
    }

    @Test
    @DisplayName("G40: the three XCTL sites become nextProgram, with no server-side forward")
    void threeTransferSitesBecomeNextProgramNames() {
        List<String> rows = fixtureRows(0, 50);

        CardListResponse toMenu = listFrom(rows, CicsAid.DFHPF3);
        assertThat(toMenu.getNextProgram().trim()).isEqualTo(MENU_PROGRAM);
        assertThat(toMenu.getNavigationContext().toProgram().trim())
                .as(":392 MOVE LIT-MENUPGM TO CDEMO-TO-PROGRAM")
                .isEqualTo(MENU_PROGRAM);
        assertThat(toMenu.getNextMapset().trim())
                .as(":394 MOVE LIT-MENUMAPSET TO CCARD-NEXT-MAPSET")
                .isEqualTo("COMEN01");
        assertThat(toMenu.getNextMap().trim())
                .as(":395 moves LIT-THISMAP, not LIT-MENUMAP - a source quirk, preserved")
                .isEqualTo("CCRDLIA");

        CardListResponse toDetail = listFrom(rows, CicsAid.DFHENTER,
                selectedRequest("S", rows, 1));
        assertThat(toDetail.getNextProgram().trim()).isEqualTo(CARD_DETAIL_PROGRAM);
        assertThat(toDetail.getNextMapset().trim()).isEqualTo("COCRDSL");
        assertThat(toDetail.getNextMap().trim()).isEqualTo("CCRDSLA");
        assertThat(toDetail.getNavigationContext().cardNum())
                .as(":533-534 MOVE WS-ROW-CARD-NUM(I-SELECTED) TO CDEMO-CARD-NUM")
                .isEqualTo(Long.parseLong(primaryKeyOf(rows.get(0))));

        CardListResponse toUpdate = listFrom(rows, CicsAid.DFHENTER,
                selectedRequest("U", rows, 1));
        assertThat(toUpdate.getNextProgram().trim()).isEqualTo(CARD_UPDATE_PROGRAM);
        assertThat(toUpdate.getNextMapset().trim()).isEqualTo("COCRDUP");
        assertThat(toUpdate.getNextMap().trim()).isEqualTo("CCRDUPA");

        assertThat(toDetail.getTrnnameo().trim())
                .as("1000-SEND-MAP is skipped by a transfer, so TRNNAMEO was never moved")
                .isEmpty();
    }

    @Test
    @DisplayName("Two mapsets: COCRDLI is sent with 45 items, COCRDSL is named through the shared DTO")
    void cardSelectProjectionIsSharedNotDuplicated() {
        CardListResponse painted = listFrom(fixtureRows(0, 50), CicsAid.DFHENTER);

        assertThat(painted.fieldImages())
                .as("app/cpy-bms/COCRDLI.CPY declares 45 xxxI/xxxO payload items")
                .hasSize(45);
        assertThat(painted.payloadFieldCount()).isEqualTo(painted.fieldImages().size());
        assertThat(painted.fieldImages().keySet())
                .as("the header band, in copybook order, PAGENOO seventh")
                .startsWith("TRNNAMEO", "TITLE01O", "CURDATEO", "PGMNAMEO", "TITLE02O", "CURTIMEO",
                        "PAGENOO", "ACCTSIDO", "CARDSIDO")
                .endsWith("INFOMSGO", "ERRMSGO");

        assertThat(CardSelectResponse.THIS_PROGRAM).isEqualTo(CARD_DETAIL_PROGRAM);
        assertThat(CardSelectResponse.THIS_MAPSET).isEqualTo("COCRDSL");
        assertThat(CardSelectResponse.MAP_NAME).isEqualTo("CCRDSLA");
        assertThat(CardSelectResponse.THIS_TRANID).isEqualTo("CCDL");
        assertThat(CardSelectRequest.ACCTSID_LENGTH)
                .as("CC-ACCT-ID hands over 11 characters, and the receiving screen declares 11")
                .isEqualTo(NavigationContext.ACCT_ID_LENGTH);
        assertThat(CardSelectRequest.CARDSID_LENGTH)
                .as("CDEMO-CARD-NUM hands over 16, and the receiving screen declares 16")
                .isEqualTo(NavigationContext.CARD_NUM_LENGTH);
    }

    @Test
    @DisplayName("CSSTRPFY: PF13 folds to PFK01, an unnamed AID resolves to nothing, both become ENTER")
    void pfKeyLadderFoldsAndUnnamedAidsBecomeEnter() {
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF13))
                .as("app/cpy/CSSTRPFY.cpy:54-55  WHEN EIBAID = DFHPF13 -> SET CCARD-AID-PFK01")
                .contains(AidKey.PFK01);
        assertThat(CicsAid.DFHPF13).isNotEqualTo(CicsAid.DFHPF1);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF1)).contains(AidKey.PFK01);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15)).contains(AidKey.PFK03);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF24)).contains(AidKey.PFK12);

        assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER)).contains(AidKey.ENTER);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHCLEAR)).contains(AidKey.CLEAR);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1)).contains(AidKey.PA1);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPA2)).contains(AidKey.PA2);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3))
                .as("CSSTRPFY has no DFHPA3 arm, so nothing is set")
                .isEmpty();
        assertThat(PfKeyResolver.resolve(CicsAid.DFHCLRP)).isEmpty();

        assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3, Optional.of(AidKey.PFK08)))
                .as("no WHEN OTHER and no reset, so the incoming value survives")
                .contains(AidKey.PFK08);

        List<String> rows = fixtureRows(0, 50);
        for (byte forced : new byte[] {CicsAid.DFHPF13, CicsAid.DFHPA3, CicsAid.DFHCLEAR,
                CicsAid.DFHPF5}) {
            CardListResponse painted = listFrom(rows, forced);
            assertThat(painted.getCardScreenState().isCcardAidEnter())
                    .as(":378-380 IF PFK-INVALID SET CCARD-AID-ENTER TO TRUE, AID 0x%02X",
                            Byte.toUnsignedInt(forced))
                    .isTrue();
            assertThat(painted.screenRow(FIRST_ROW).rowCardNum())
                    .as("forced to ENTER, so the WHEN OTHER arm at :572 lists page one")
                    .isEqualTo(primaryKeyOf(rows.get(0)));
        }
    }

    @Test
    @DisplayName("G38: arriving from elsewhere paints without validating; a re-entry reddens only the offending rows")
    void reenterHighlightsOnlyTheOffendingRow() {
        List<String> rows = fixtureRows(0, 50);

        CardListRequest fromMenu = continuingRequest(PageCursor.firstPage());
        fromMenu.setNavigationContext(NavigationContext.empty()
                .withFromTranid("CM00")
                .withFromProgram(MENU_PROGRAM)
                .withPgmEnter());
        fromMenu.setRows(selectionRows("X", rows, 1));
        CardListResponse painted = listFrom(rows, CicsAid.DFHENTER, fromMenu);
        assertThat(painted.getErrmsgo().trim())
                .as("nothing was received, so nothing failed edit")
                .isEmpty();
        assertThat(painted.fieldAttributes("CRDSEL1").colour())
                .as("no row is red when no row was validated")
                .isEqualTo((byte) 0);

        CardListRequest fromSelf = selectedRequest("S", rows, 1);
        fromSelf.setRows(twoSelectionRows(rows));
        CardListResponse rejected = listFrom(rows, CicsAid.DFHENTER, fromSelf);
        assertThat(rejected.getErrmsgo().trim())
                .as(":123-124 88 WS-MORE-THAN-1-ACTION")
                .isEqualTo("PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE");
        assertThat(rejected.fieldAttributes("CRDSEL1").colour())
                .as(":756 MOVE DFHRED TO CRDSEL1C for the flagged row")
                .isEqualTo(BmsAttributes.DFHRED);
        assertThat(rejected.fieldAttributes("CRDSEL3").colour())
                .as(":781 MOVE DFHRED TO CRDSEL3C for the second flagged row")
                .isEqualTo(BmsAttributes.DFHRED);
        for (int untouched : new int[] {2, 4, 5, 6, 7}) {
            assertThat(rejected.fieldAttributes("CRDSEL" + untouched).colour())
                    .as("row %d typed nothing, so it is not reddened", untouched)
                    .isEqualTo((byte) 0);
        }
        assertThat(rejected.getCardScreenState().getCcardNextProg().trim())
                .as(":428 the input-error arm names this program, so the operator corrects and resends")
                .isEqualTo(THIS_PROGRAM);
        assertThat(rejected.getNextProgram().trim())
                .as("no XCTL happened, so the response names no next program")
                .isEmpty();
    }

    @Test
    @DisplayName("B5: the row-one asterisk at :757-759 is preserved and does what the source says")
    void rowOneAsteriskIsPreservedThoughUnreachable() {
        CardListResponse response = new CardListResponse();
        response.setScreenRow(FIRST_ROW,
                new CardListResponse.ScreenRow("00000000050", "0500024453765740", "Y"));
        response.setWsRowCrdselectError(FIRST_ROW, String.valueOf(CardListRequest.ROW_SELECT_ERROR));
        response.setEditSelect(FIRST_ROW, String.valueOf(LOW_VALUE));

        boolean highlighted = response.applyRowSelectHighlight(FIRST_ROW, false);

        assertThat(highlighted).isTrue();
        assertThat(response.fieldAttributes("CRDSEL1").colour())
                .as(":756 MOVE DFHRED TO CRDSEL1C")
                .isEqualTo(BmsAttributes.DFHRED);
        assertThat(response.field("CRDSEL1O"))
                .as(":758 MOVE '*' TO CRDSEL1O, reachable only by calling the paragraph directly")
                .isEqualTo(FieldAttributeSetter.ASTERISK);

        CardListResponse protectedRows = new CardListResponse();
        protectedRows.setScreenRow(FIRST_ROW,
                new CardListResponse.ScreenRow("00000000050", "0500024453765740", "Y"));
        protectedRows.setWsRowCrdselectError(FIRST_ROW,
                String.valueOf(CardListRequest.ROW_SELECT_ERROR));
        assertThat(protectedRows.applyRowSelectHighlight(FIRST_ROW, true)).isFalse();
        assertThat(protectedRows.fieldAttributes("CRDSEL1").colour()).isEqualTo((byte) 0);
    }

    @Test
    @DisplayName("G47: the response ladder - a full page, a short page, an empty browse, and a failure")
    void fileStatusLadderIsDrivenAtEveryCallSite() {
        CardListResponse exactPage = listFrom(fixtureRows(0, MAX_SCREEN_LINES), CicsAid.DFHENTER);
        assertThat(exactPage.getPageCursor().isNextPageNotExists())
                .as(":1215-1216 the look-ahead reached ENDFILE")
                .isTrue();
        assertThat(exactPage.getErrmsgo().trim())
                .as(":1218-1221 IF WS-ERROR-MSG-OFF MOVE 'NO MORE RECORDS TO SHOW'")
                .isEqualTo("NO MORE RECORDS TO SHOW");

        CardListResponse shortPage = listFrom(fixtureRows(0, 4), CicsAid.DFHENTER);
        assertThat(shortPage.getErrmsgo().trim()).isEqualTo("NO MORE RECORDS TO SHOW");
        assertThat(shortPage.screenRow(4).rowCardNum().trim()).isNotEmpty();
        assertThat(shortPage.screenRow(5).rowCardNum().trim())
                .as("MOVE LOW-VALUES TO WS-ALL-ROWS left row 5 empty")
                .isEmpty();

        CardListResponse empty = listFrom(List.of(), CicsAid.DFHENTER);
        assertThat(empty.getErrmsgo().trim())
                .as(":1241-1244 WS-CA-SCREEN-NUM = 1 AND WS-SCRN-COUNTER = 0")
                .isEqualTo("NO RECORDS FOUND FOR THIS SEARCH CONDITION.");
        assertThat(empty.getPageCursor().lastCardNum().trim())
                .as(":1236-1237 store an untouched CARD-RECORD, which is LOW-VALUES")
                .isEmpty();

        CardListResponse failed = listFromFailedBrowse(fixtureRows(0, 50));
        String composed = "File Error:".concat(" ")
                + "READ    "
                + " on "
                + "CARDDAT  "
                + " returned RESP "
                + "000000016 "
                + ",RESP2 "
                + "000000000 "
                + "     ";
        assertThat(composed)
                .as(":153-171 WS-FILE-ERROR-MESSAGE sums to exactly eighty characters")
                .hasSize(80);
        assertThat(failed.getErrmsgo())
                .as(":1254 truncates the eighty into WS-ERROR-MSG PIC X(75), and :924 pads that into "
                        + "ERRMSGO PIC X(78)")
                .hasSize(CardListResponse.ERRMSGO_LENGTH)
                .isEqualTo(composed.substring(0, 75) + "   ");
        assertThat(failed.getErrmsgo())
                .as("both RESP and RESP2 are reported, each as a nine-digit PIC X(10) item")
                .contains(String.valueOf(FileStatus.INVREQ))
                .contains(",RESP2");
    }

    private static CardListController controllerOver(List<String> rows) {
        return controllerOver(stubbedRepositoryOver(rows));
    }

    private static CardListController controllerOver(CardRepository repository) {
        return new CardListController(repository,
                new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET),
                ParityHarness.fixedClockAt(LocalDateTime.parse(PINNED_CLOCK)));
    }

    private static CardRepository stubbedRepositoryOver(List<String> rows) {
        return stubbedRepositoryOver(rows, Optional.empty());
    }

    private static CardRepository stubbedRepositoryOver(List<String> rows,
            Optional<CardReadResult> forced) {
        List<String> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(COCRDLICParityTest::primaryKeyOf));
        CardRepository repository = Mockito.mock(CardRepository.class);
        Mockito.when(repository.startBrowse(Mockito.anyString(), Mockito.any(BrowseDirection.class)))
                .thenAnswer(call -> browseOver(ordered, call.getArgument(0), call.getArgument(1),
                        forced));
        return repository;
    }

    private static CardListResponse listFrom(List<String> rows, byte eibAid) {
        return listFrom(rows, eibAid, null);
    }

    private static CardListResponse listFrom(List<String> rows, byte eibAid,
            CardListRequest request) {
        return controllerOver(rows).getCards(request, aid(eibAid), null).screen();
    }

    private static CardListResponse listFromFailedBrowse(List<String> rows) {
        CardRepository failing = stubbedRepositoryOver(rows,
                Optional.of(CardReadResult.reportedFailure(FileStatus.INVREQ,
                        FileStatus.NO_REASON_CODE)));
        return controllerOver(failing).getCards(null, aid(CicsAid.DFHENTER), null).screen();
    }

    private static CardListRequest continuingRequest(PageCursor cursor) {
        CardListRequest request = new CardListRequest();
        request.setNavigationContext(NavigationContext.empty()
                .withFromTranid(THIS_TRANID)
                .withFromProgram(THIS_PROGRAM)
                .withUserTypeUser()
                .withPgmEnter()
                .withLastMap("CCRDLIA")
                .withLastMapset("COCRDLI"));
        request.setPageCursor(cursor);
        return request;
    }

    private static PageCursor cursorAt(List<String> rows, int lastIndex, int firstIndex,
            int screenNum) {
        return new PageCursor(keyOf(rows.get(lastIndex)), keyOf(rows.get(firstIndex)), screenNum,
                CardListRequest.LAST_PAGE_NOT_SHOWN,
                String.valueOf(CardListRequest.NEXT_PAGE_EXISTS),
                String.valueOf(SPACE));
    }

    private static CardListRequest.CardKey keyOf(String row) {
        CardRecord card = CardRecord.decodeImage(row, ParityHarness.FIXTURE_CHARSET);
        return new CardListRequest.CardKey(card.cardNum(), card.cardAcctId());
    }

    private static CardListRequest selectedRequest(String action, List<String> rows, int cobolRow) {
        CardListRequest request = continuingRequest(PageCursor.firstPage());
        request.setRows(selectionRows(action, rows, cobolRow));
        return request;
    }

    private static List<ListRow> selectionRows(String action, List<String> rows, int cobolRow) {
        return mapRows(rows, Map.of(Integer.valueOf(cobolRow), action));
    }

    private static List<ListRow> twoSelectionRows(List<String> rows) {
        return mapRows(rows, Map.of(Integer.valueOf(1), "S", Integer.valueOf(3), "U"));
    }

    private static List<ListRow> mapRows(List<String> rows, Map<Integer, String> actions) {
        FixedWidthCodec codec = new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET);
        List<ListRow> mapped = new ArrayList<>(MAX_SCREEN_LINES);
        for (int row = FIRST_ROW; row <= MAX_SCREEN_LINES; row++) {
            boolean seeded = row <= rows.size();
            CardRecord card = seeded
                    ? CardRecord.decodeImage(rows.get(row - 1), ParityHarness.FIXTURE_CHARSET)
                    : null;
            String select = actions.getOrDefault(Integer.valueOf(row), String.valueOf(LOW_VALUE));
            String acctNo = seeded ? card.cardAcctIdImage(codec) : String.valueOf(LOW_VALUE);
            String cardNum = seeded ? card.cardNum() : String.valueOf(LOW_VALUE);
            String status = seeded ? card.cardActiveStatus() : String.valueOf(LOW_VALUE);
            mapped.add(row == FIRST_ROW
                    ? new FirstListRow(select, acctNo, cardNum, status)
                    : new StopperListRow(select, String.valueOf(LOW_VALUE), acctNo, cardNum, status));
        }
        return mapped;
    }

    private static Integer aid(byte eibAid) {
        return Integer.valueOf(Byte.toUnsignedInt(eibAid));
    }

    private static List<String> fixtureRows(int fromRow, int count) {
        List<String> rows = readCardFixture();
        if (fromRow < 0 || count < 0 || fromRow + count > rows.size()) {
            throw new IllegalArgumentException(CARD_FIXTURE + " holds " + rows.size()
                    + " rows, so rows " + fromRow + " through " + (fromRow + count)
                    + " do not exist. Seeding fewer rows than a test describes is the silent-pass "
                    + "failure the harness exists to prevent.");
        }
        return List.copyOf(rows.subList(fromRow, fromRow + count));
    }

    private static List<String> readCardFixture() {
        byte[] content;
        try (InputStream stream = COCRDLICParityTest.class.getClassLoader()
                .getResourceAsStream(CARD_FIXTURE)) {
            if (stream == null) {
                throw new IllegalStateException("Fixture " + CARD_FIXTURE + " is not on the test "
                        + "classpath. It is a byte-for-byte copy of app/data/ASCII/carddata.txt and is "
                        + "the authoritative input every COCRDLIC case is seeded from.");
            }
            content = stream.readAllBytes();
        } catch (IOException unreadable) {
            throw new IllegalStateException("Fixture " + CARD_FIXTURE + " could not be read, so no "
                    + "COCRDLIC assertion has an input to stand on.", unreadable);
        }
        String text = new String(content, ParityHarness.FIXTURE_CHARSET);
        List<String> rows = new ArrayList<>();
        for (String row : text.split("\n", -1)) {
            if (row.isEmpty()) {
                continue;
            }
            if (row.indexOf('\r') >= 0) {
                throw new IllegalStateException("Fixture " + CARD_FIXTURE + " contains a carriage "
                        + "return, so the working tree translated its line endings and every row is one "
                        + "byte wider than app/cpy/CVACT02Y.cpy declares. Restore the fixture rather "
                        + "than stripping the byte here.");
            }
            if (row.length() != CardRecord.RECORD_LENGTH) {
                throw new IllegalStateException("Fixture " + CARD_FIXTURE + " has a row of "
                        + row.length() + " characters; app/cpy/CVACT02Y.cpy declares exactly "
                        + CardRecord.RECORD_LENGTH + ", FILLER X(59) included.");
            }
            rows.add(row);
        }
        return rows;
    }
}
