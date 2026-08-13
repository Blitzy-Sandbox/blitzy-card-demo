package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetNormalisation;
import com.vsergeychik.carddemo.parity.ParityCase.ForcedOutcome;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.Normalisation;
import com.vsergeychik.carddemo.parity.ParityCase.RepositoryOperation;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenSend;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import com.vsergeychik.carddemo.user.SecUserRepository;
import com.vsergeychik.carddemo.user.UserMenuController;
import com.vsergeychik.carddemo.user.dto.UserListRequest;
import com.vsergeychik.carddemo.user.dto.UserListResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import com.vsergeychik.carddemo.testdataset.RecordImageDataSource;
import com.vsergeychik.carddemo.testdataset.RecordImageStore.ColumnForm;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The twenty-case behavioural-parity gate for {@code app/cbl/COUSR00C.cbl}, the {@code CU00} user-list
 * screen, run against {@link UserMenuController}.
 */
@DisplayName("COUSR00C parity - the CU00 user-list screen, twenty statically derived cases")
final class COUSR00CParityTest {
    private static final String PROGRAM = "COUSR00C";

    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final FixedWidthCodec CODEC = new FixedWidthCodec(ASCII);

    private static final String USRSEC = SecUserRepository.CICS_FILE_NAME;

    private static final String TEST_DSNAME = "TEST.USRSEC.PARITY.RELATION";

    private static final String RECORD_IMAGE_COLUMN = "RECORD_IMAGE";

    private static final int RECORD_LENGTH = SecUserRecord.RECORD_LENGTH;

    private static final int KEY_LENGTH = SecUserRecord.KEY_LENGTH;

    private static final int PAGE_SIZE = 10;

    private static final List<String> ROW_STEMS = List.of("SEL", "USRID", "FNAME", "LNAME", "UTYPE");

    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("the field-for-field diff count is zero (gate G18)")
    void everyCaseDiffersInNothing(final ParityCase parityCase) {
        DiffResult diff = ParityHarness.usAscii()
                .judge(parityCase, UnitKind.CONTROLLER_POJO, invocation -> invoke(invocation));

        assertThat(diff.count())
                .withFailMessage("%s", diff.render())
                .isZero();
    }

    @Test
    @DisplayName("exactly twenty cases, case01 through case20, each with a description (gate G15)")
    void theCaseSetIsExactlyTwenty() {
        List<ParityCase> loaded = cases();

        assertThat(loaded)
                .as("the gate is stated as twenty cases per program, and %s declares %d",
                        PROGRAM, loaded.size())
                .hasSize(ParityHarness.CASES_PER_PROGRAM);
        List<String> identifiers = new ArrayList<>(loaded.size());
        for (ParityCase parityCase : loaded) {
            identifiers.add(parityCase.caseId());
            assertThat(parityCase.program()).isEqualTo(PROGRAM);
            assertThat(parityCase.unitKind()).isEqualTo(UnitKind.CONTROLLER_POJO);
            assertThat(parityCase.description()).isNotBlank();
            assertThat(parityCase.expectedReturnCode())
                    .as("%s: an online transaction does not abend, so the RETURN-CODE is 0",
                            parityCase.caseId())
                    .isZero();
            assertThat(parityCase.expectedWrites())
                    .as("%s: COUSR00C opens USRSEC for browse only and writes nothing",
                            parityCase.caseId())
                    .isEmpty();
        }
        List<String> expected = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            expected.add(ParityHarness.caseId(ordinal));
        }
        assertThat(identifiers).containsExactlyElementsOf(expected);
    }

    private static UnitOutcome invoke(final Invocation invocation) {
        SeededDataset seeded = invocation.dataset(USRSEC);
        ScreenResponse<UserListResponse> response = callHandler(invocation, 1).get(0);

        UnitOutcome.Builder recorder = invocation.recorder();
        recorder.response(observedOf(response));
        recorder.finalStateUnchanged(seeded, SecUserRecord.LAYOUT);
        recorder.returnCode(0);
        return null;
    }

    private static List<ScreenResponse<UserListResponse>> callHandler(final Invocation invocation,
                                                                     final int times) {
        SeededDataset seeded = invocation.dataset(USRSEC);
        SecUserRepository repository = repositoryFor(invocation, seeded);
        UserMenuController controller =
                new UserMenuController(repository, CODEC, fixedClockOf(invocation));

        List<ScreenResponse<UserListResponse>> responses = new ArrayList<>(times);
        for (int call = 0; call < times; call++) {
            responses.add(controller.getUsers(requestOf(invocation), eibAidOf(invocation), null));
        }
        return responses;
    }

    private static SecUserRepository repositoryFor(final Invocation invocation,
                                                   final SeededDataset seeded) {
        boolean unreachable = false;
        if (invocation.hasForcedOutcome(RepositoryOperation.START_BROWSE)) {
            ForcedOutcome forced = invocation.forcedOutcome(RepositoryOperation.START_BROWSE);
            assertThat(forced.outcome())
                    .as("this screen browses and never reads by key, so the only outcome worth "
                            + "forcing on startBrowse is the one no data shape can produce")
                    .isEqualTo(FileStatus.Outcome.OTHER);
            unreachable = true;
        }
        JdbcTemplate template = unreachable
                ? relation(invocation, null)
                : relation(invocation, seeded.rows());
        return new SecUserRepository(template, bindings(), ASCII, RecordImageForm.CHARACTER);
    }

    private static JdbcTemplate relation(final Invocation invocation, final List<String> rows) {
        RecordImageDataSource backend = new RecordImageDataSource();
        if (rows != null) {
            backend.define(TEST_DSNAME, RECORD_IMAGE_COLUMN, ColumnForm.CHARACTER, RECORD_LENGTH);
            backend.store().seed(TEST_DSNAME, rows);
        }
        return new JdbcTemplate(backend);
    }

    private static DatasetBindings bindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(USRSEC, new DatasetBinding(TEST_DSNAME, DatasetBinding.KSDS, false, "FB", null,
                RECORD_LENGTH, "CSUSR01Y", KEY_LENGTH, null, null, null));
        return catalogue;
    }

    private static Clock fixedClockOf(final Invocation invocation) {
        Clock clock = invocation.clock();
        assertThat(clock)
                .as("the harness fixes every case's clock; a system clock would reach CURDATEO")
                .isEqualTo(Clock.fixed(clock.instant(), clock.getZone()));
        return clock;
    }

    private static UserListRequest requestOf(final Invocation invocation) {
        Map<String, String> received = invocation.mapFields();
        UserListRequest request = UserListRequest.empty()
                .withTrnName(picX(received, UserListRequest.TRNNAME_FIELD, UserListRequest.TRNNAME_LENGTH))
                .withTitle01(picX(received, UserListRequest.TITLE01_FIELD, UserListRequest.TITLE01_LENGTH))
                .withCurDate(picX(received, UserListRequest.CURDATE_FIELD, UserListRequest.CURDATE_LENGTH))
                .withPgmName(picX(received, UserListRequest.PGMNAME_FIELD, UserListRequest.PGMNAME_LENGTH))
                .withTitle02(picX(received, UserListRequest.TITLE02_FIELD, UserListRequest.TITLE02_LENGTH))
                .withCurTime(picX(received, UserListRequest.CURTIME_FIELD, UserListRequest.CURTIME_LENGTH))
                .withPageNum(picX(received, UserListRequest.PAGENUM_FIELD, UserListRequest.PAGENUM_LENGTH))
                .withUsrIdIn(picX(received, UserListRequest.USRIDIN_FIELD, UserListRequest.USRIDIN_LENGTH))
                .withErrMsg(picX(received, UserListRequest.ERRMSG_FIELD, UserListRequest.ERRMSG_LENGTH));

        for (int rowNumber = 1; rowNumber <= PAGE_SIZE; rowNumber++) {
            UserListRequest.UserListRow row = new UserListRequest.UserListRow(
                    picX(received, UserListRequest.selFieldName(rowNumber), UserListRequest.SEL_LENGTH),
                    picX(received, UserListRequest.usrIdFieldName(rowNumber), UserListRequest.USRID_LENGTH),
                    picX(received, UserListRequest.fnameFieldName(rowNumber), UserListRequest.FNAME_LENGTH),
                    picX(received, UserListRequest.lnameFieldName(rowNumber), UserListRequest.LNAME_LENGTH),
                    picX(received, UserListRequest.utypeFieldName(rowNumber), UserListRequest.UTYPE_LENGTH));
            request = request.withRow(rowNumber, row);
        }

        Map<String, String> commarea = invocation.commarea();
        request = request
                .withCdemoCu00UsrIdFirst(commareaPicX(commarea, UserListRequest.CU00_USRID_FIRST_FIELD,
                        UserListRequest.CU00_USRID_FIRST_LENGTH))
                .withCdemoCu00UsrIdLast(commareaPicX(commarea, UserListRequest.CU00_USRID_LAST_FIELD,
                        UserListRequest.CU00_USRID_LAST_LENGTH))
                .withCdemoCu00PageNum(commareaDigits(commarea, UserListRequest.CU00_PAGE_NUM_FIELD))
                .withCdemoCu00NextPageFlg(commarea.getOrDefault(
                        UserListRequest.CU00_NEXT_PAGE_FLG_FIELD, UserListRequest.NEXT_PAGE_NO))
                .withCdemoCu00UsrSelFlg(commareaPicX(commarea, UserListRequest.CU00_USR_SEL_FLG_FIELD,
                        UserListRequest.CU00_USR_SEL_FLG_LENGTH))
                .withCdemoCu00UsrSelected(commareaPicX(commarea, UserListRequest.CU00_USR_SELECTED_FIELD,
                        UserListRequest.CU00_USR_SELECTED_LENGTH));

        if (invocation.eibcalen() == 0) {
            return request.withoutNavigationContext();
        }
        return request.withNavigationContext(navigationOf(commarea));
    }

    private static NavigationContext navigationOf(final Map<String, String> commarea) {
        NavigationContext context = NavigationContext.empty();
        for (Map.Entry<String, String> entry : commarea.entrySet()) {
            String field = entry.getKey();
            String value = entry.getValue();
            switch (field) {
                case NavigationContext.FROM_TRANID_FIELD -> context = context.withFromTranid(value);
                case NavigationContext.FROM_PROGRAM_FIELD -> context = context.withFromProgram(value);
                case NavigationContext.TO_TRANID_FIELD -> context = context.withToTranid(value);
                case NavigationContext.TO_PROGRAM_FIELD -> context = context.withToProgram(value);
                case NavigationContext.USER_ID_FIELD -> context = context.withUserId(value);
                case NavigationContext.USER_TYPE_FIELD -> context = context.withUserType(value);
                case NavigationContext.PGM_CONTEXT_FIELD ->
                        context = context.withPgmContext(Integer.parseInt(value.strip()));
                case NavigationContext.CUST_ID_FIELD ->
                        context = context.withCustId(Integer.parseInt(value.strip()));
                case NavigationContext.CUST_FNAME_FIELD -> context = context.withCustFname(value);
                case NavigationContext.CUST_MNAME_FIELD -> context = context.withCustMname(value);
                case NavigationContext.CUST_LNAME_FIELD -> context = context.withCustLname(value);
                case NavigationContext.ACCT_ID_FIELD ->
                        context = context.withAcctId(Long.parseLong(value.strip()));
                case NavigationContext.ACCT_STATUS_FIELD -> context = context.withAcctStatus(value);
                case NavigationContext.CARD_NUM_FIELD ->
                        context = context.withCardNum(Long.parseLong(value.strip()));
                case NavigationContext.LAST_MAP_FIELD -> context = context.withLastMap(value);
                case NavigationContext.LAST_MAPSET_FIELD -> context = context.withLastMapset(value);
                default -> assertThat(field)
                        .as("a commarea field a case declares must be one this program carries: "
                                + "either a COCOM01Y field or one of the six of CDEMO-CU00-INFO")
                        .startsWith("CDEMO-CU00-");
            }
        }
        return context;
    }

    private static String picX(final Map<String, String> received, final String label, final int width) {
        String declared = received.get(label + "I");
        return CODEC.movePicX(declared == null ? "" : declared, width);
    }

    private static String commareaPicX(final Map<String, String> commarea, final String field,
                                       final int width) {
        String declared = commarea.get(field);
        return CODEC.movePicX(declared == null ? "" : declared, width);
    }

    private static int commareaDigits(final Map<String, String> commarea, final String field) {
        String declared = commarea.get(field);
        if (declared == null || declared.isBlank()) {
            return UserListRequest.CU00_PAGE_NUM_INITIAL;
        }
        return CODEC.decodePic9AsInt(declared);
    }

    private static Integer eibAidOf(final Invocation invocation) {
        String mnemonic = invocation.hasScreenRequest() ? invocation.aid() : null;
        if (mnemonic == null) {
            return Integer.valueOf(CicsAid.DFHENTER & 0xFF);
        }
        Optional<Byte> resolved = CicsAid.mnemonicsByAid().entrySet().stream()
                .filter(entry -> entry.getValue().equals(mnemonic))
                .map(Map.Entry::getKey)
                .findFirst();
        assertThat(resolved)
                .as("case %s declares AID %s, which is not a DFHAID mnemonic CicsAid reproduces",
                        invocation.caseId(), mnemonic)
                .isPresent();
        return Integer.valueOf(resolved.orElseThrow().byteValue() & 0xFF);
    }

    private static ObservedResponse observedOf(final ScreenResponse<UserListResponse> response) {
        UserListResponse screen = response.screen();
        ScreenMetadata metadata = response.screenMetadata();
        boolean transferred = !blank(screen.nextProgram());

        List<ObservedSend> sends = transferred
                ? List.of()
                : List.of(ObservedSend.ofFields(sentFieldsOf(screen)));

        return new ObservedResponse(
                absentIfBlank(screen.nextProgram()),
                absentIfBlank(screen.nextMapset()),
                absentIfBlank(screen.nextMap()),
                navigationImagesOf(screen),
                sends,
                cursorFieldOf(metadata),
                transferred ? Termination.XCTL : Termination.RETURN_TRANSID);
    }

    private static Map<String, String> navigationImagesOf(final UserListResponse screen) {
        NavigationContext context = screen.navigationContext();
        Map<String, String> images =
                new LinkedHashMap<>(CODEC.deserialise(NavigationContext.LAYOUT,
                        context.toFixedWidth(CODEC)));
        images.put(UserListResponse.CU00_USRID_FIRST_FIELD, screen.cdemoCu00UsrIdFirst());
        images.put(UserListResponse.CU00_USRID_LAST_FIELD, screen.cdemoCu00UsrIdLast());
        images.put(UserListResponse.CU00_PAGE_NUM_FIELD,
                CODEC.movePic9(screen.cdemoCu00PageNum(), UserListResponse.CU00_PAGE_NUM_DIGITS));
        images.put(UserListResponse.CU00_NEXT_PAGE_FLG_FIELD, screen.cdemoCu00NextPageFlg());
        images.put(UserListResponse.CU00_USR_SEL_FLG_FIELD, screen.cdemoCu00UsrSelFlg());
        images.put(UserListResponse.CU00_USR_SELECTED_FIELD, screen.cdemoCu00UsrSelected());
        return images;
    }

    private static Map<String, String> sentFieldsOf(final UserListResponse screen) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(outputItem(UserListResponse.TRNNAME_FIELD), screen.trnName());
        fields.put(outputItem(UserListResponse.TITLE01_FIELD), screen.title01());
        fields.put(outputItem(UserListResponse.CURDATE_FIELD), screen.curDate());
        fields.put(outputItem(UserListResponse.PGMNAME_FIELD), screen.pgmName());
        fields.put(outputItem(UserListResponse.TITLE02_FIELD), screen.title02());
        fields.put(outputItem(UserListResponse.CURTIME_FIELD), screen.curTime());
        fields.put(outputItem(UserListResponse.PAGENUM_FIELD), screen.pageNum());
        fields.put(outputItem(UserListResponse.USRIDIN_FIELD), screen.usrIdIn());
        for (int rowNumber = 1; rowNumber <= PAGE_SIZE; rowNumber++) {
            UserListResponse.Row row = screen.row(rowNumber);
            fields.put(outputItem(UserListRequest.selFieldName(rowNumber)), row.selection());
            fields.put(outputItem(UserListRequest.usrIdFieldName(rowNumber)), row.userId());
            fields.put(outputItem(UserListRequest.fnameFieldName(rowNumber)), row.firstName());
            fields.put(outputItem(UserListRequest.lnameFieldName(rowNumber)), row.lastName());
            fields.put(outputItem(UserListRequest.utypeFieldName(rowNumber)), row.userType());
        }
        fields.put(outputItem(UserListResponse.ERRMSG_FIELD), screen.errMsg());
        return fields;
    }

    private static String outputItem(final String label) {
        return label + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX;
    }

    private static String cursorFieldOf(final ScreenMetadata metadata) {
        String label = metadata.cursorField();
        return label == null ? null : label + "L";
    }

    private static String absentIfBlank(final String value) {
        return blank(value) ? null : value.strip();
    }

    private static boolean blank(final String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        return value.chars().allMatch(character -> character == ' ' || character == 0);
    }

    @Test
    @DisplayName("the page is ten rows, and seven would fail these fixtures (gate G39)")
    void pageSizeIsTenAndNotConfigurable() {
        assertThat(UserListResponse.ROW_COUNT).isEqualTo(PAGE_SIZE);
        assertThat(UserListRequest.ROW_COUNT).isEqualTo(PAGE_SIZE);
        assertThat(UserListResponse.MAP_FIELD_COUNT)
                .as("8 header and paging items + 10 rows of %d + the message line",
                        ROW_STEMS.size())
                .isEqualTo(UserListRequest.HEADER_FIELD_COUNT
                        + PAGE_SIZE * ROW_STEMS.size()
                        + UserListRequest.TRAILER_FIELD_COUNT);

        for (String caseId : List.of("case02", "case06", "case09")) {
            Map<String, String> page = onlySendOf(caseId);
            List<Integer> filled = new ArrayList<>();
            for (int rowNumber = 1; rowNumber <= PAGE_SIZE; rowNumber++) {
                if (!blank(page.get(outputItem(UserListRequest.usrIdFieldName(rowNumber))))) {
                    filled.add(rowNumber);
                }
            }
            assertThat(filled)
                    .as("%s fills the declared page to its last row, which a page of seven could not",
                            caseId)
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        }
    }

    @Test
    @DisplayName("the page-count and subscript arithmetic sites are each pinned (gate G28)")
    void theArithmeticSitesAreEachPinned() {
        assertThat(onlySendOf("case02").get(outputItem(UserListRequest.usrIdFieldName(1))))
                .isEqualTo("ADMIN001");
        assertThat(onlySendOf("case02").get(outputItem(UserListRequest.usrIdFieldName(PAGE_SIZE))))
                .isEqualTo("USER0005");

        assertThat(onlySendOf("case20").get(outputItem(UserListRequest.usrIdFieldName(1))))
                .isEqualTo("ADMIN001");
        assertThat(onlySendOf("case20").get(outputItem(UserListRequest.usrIdFieldName(PAGE_SIZE))))
                .isEqualTo("USER0005");
        assertThat(onlySendOf("case07").get(outputItem(UserListRequest.usrIdFieldName(1))))
                .isEqualTo("ZUSER001");

        assertThat(pageNumberOf("case02")).isEqualTo("00000001");
        assertThat(pageNumberOf("case06")).isEqualTo("00000002");
        assertThat(pageNumberOf("case09")).isEqualTo("00000002");

        assertThat(caseNamed("case09").screenRequest().commarea())
                .containsEntry(UserListRequest.CU00_USRID_LAST_FIELD, "USER0005");
        assertThat(onlySendOf("case09").get(outputItem(UserListRequest.usrIdFieldName(1))))
                .isEqualTo("USER0006");
        assertThat(onlySendOf("case09").get(outputItem(UserListRequest.usrIdFieldName(PAGE_SIZE))))
                .isEqualTo("USER0015");

        assertThat(pageNumberOf("case03")).isEqualTo("00000001");
        assertThat(pageNumberOf("case04")).isEqualTo("00000000");
        assertThat(pageNumberOf("case05")).isEqualTo("00000000");

        assertThat(pageNumberOf("case07")).isEqualTo("00000002");
        assertThat(pageNumberOf("case20")).isEqualTo("00000001");

        assertThat(pageNumberOf("case16")).isEqualTo("00000002");
        assertThat(caseNamed("case16").screenRequest().commarea())
                .as("and it is unchanged, not coincidentally equal - the payload carried the same 2")
                .containsEntry(UserListResponse.CU00_PAGE_NUM_FIELD, "00000002");

        assertThat(caseNamed("case16").expectedResponse().navigation())
                .containsEntry(UserListResponse.CU00_USRID_FIRST_FIELD, "ADMIN003")
                .containsEntry(UserListResponse.CU00_USRID_LAST_FIELD, "ADMIN002");
        assertThat(onlySendOf("case16").get(outputItem(UserListRequest.usrIdFieldName(1))))
                .as(":346-350 blanked row 1 and the fill never reached it")
                .isEqualTo("        ");
        assertThat(onlySendOf("case16").get(outputItem(UserListRequest.usrIdFieldName(PAGE_SIZE - 1))))
                .isEqualTo("ADMIN001");
        assertThat(onlySendOf("case16").get(outputItem(UserListRequest.usrIdFieldName(PAGE_SIZE))))
                .isEqualTo("ADMIN002");
        ParityCase transferred = caseNamed("case18");
        assertThat(transferred.expectedResponse().sends())
                .as("case18 transferred before painting anything, so there is no page to count")
                .isEmpty();
        assertThat(transferred.screenRequest().commarea())
                .containsEntry(UserListResponse.CU00_PAGE_NUM_FIELD, "00000001");
        assertThat(transferred.expectedResponse().navigation())
                .as("the count is carried through untouched, not recomputed")
                .containsEntry(UserListResponse.CU00_PAGE_NUM_FIELD, "00000001")
                .containsEntry(UserListResponse.CU00_USRID_FIRST_FIELD, "ADMIN001")
                .containsEntry(UserListResponse.CU00_USRID_LAST_FIELD, "USER0005")
                .containsEntry(UserListResponse.CU00_NEXT_PAGE_FLG_FIELD,
                        UserListRequest.NEXT_PAGE_YES);
    }

    @Test
    @DisplayName("CDEMO-CU00-INFO is 34 bytes over a 160-byte commarea, never 58")
    void theThirtyFourByteExtensionKeepsItsDeclaredWidths() {
        assertThat(UserListRequest.CU00_USRID_FIRST_LENGTH).isEqualTo(8);
        assertThat(UserListRequest.CU00_USRID_LAST_LENGTH).isEqualTo(8);
        assertThat(UserListRequest.CU00_PAGE_NUM_LENGTH).isEqualTo(8);
        assertThat(UserListRequest.CU00_NEXT_PAGE_FLG_LENGTH).isEqualTo(1);
        assertThat(UserListRequest.CU00_USR_SEL_FLG_LENGTH).isEqualTo(1);
        assertThat(UserListRequest.CU00_USR_SELECTED_LENGTH).isEqualTo(8);
        assertThat(UserListRequest.CU00_INFO_LENGTH)
                .as("8 + 8 + 8 + 1 + 1 + 8 = 34, and COTRN00C's extension is 58 - do not copy one to "
                        + "the other")
                .isEqualTo(34);
        assertThat(NavigationContext.COMMAREA_LENGTH)
                .as("nothing was added to the shared area to make an extension fit")
                .isEqualTo(160);
        assertThat(UserListRequest.CU00_COMMAREA_LENGTH)
                .isEqualTo(NavigationContext.COMMAREA_LENGTH + UserListRequest.CU00_INFO_LENGTH);

        for (ParityCase parityCase : cases()) {
            Map<String, String> navigation = parityCase.expectedResponse().navigation();
            assertThat(navigation)
                    .as("%s pins all 16 COCOM01Y fields and all 6 of the extension",
                            parityCase.caseId())
                    .hasSize(16 + 6);
            assertThat(navigation.get(UserListResponse.CU00_USRID_FIRST_FIELD)).hasSize(8);
            assertThat(navigation.get(UserListResponse.CU00_USRID_LAST_FIELD)).hasSize(8);
            assertThat(navigation.get(UserListResponse.CU00_PAGE_NUM_FIELD)).hasSize(8);
            assertThat(navigation.get(UserListResponse.CU00_NEXT_PAGE_FLG_FIELD)).hasSize(1);
            assertThat(navigation.get(UserListResponse.CU00_USR_SEL_FLG_FIELD)).hasSize(1);
            assertThat(navigation.get(UserListResponse.CU00_USR_SELECTED_FIELD)).hasSize(8);
        }
    }

    @Test
    @DisplayName("every record image is 80 bytes with a space-filled FILLER (gates G19, G21)")
    void theEightyByteRecordCarriesASpaceFilledFiller() {
        assertThat(SecUserRecord.SEC_USR_ID_LENGTH + SecUserRecord.SEC_USR_FNAME_LENGTH
                + SecUserRecord.SEC_USR_LNAME_LENGTH + SecUserRecord.SEC_USR_PWD_LENGTH
                + SecUserRecord.SEC_USR_TYPE_LENGTH + SecUserRecord.SEC_USR_FILLER_LENGTH)
                .isEqualTo(RECORD_LENGTH);
        assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET).isEqualTo(57);
        assertThat(SecUserRecord.LAYOUT.hasSpan(SecUserRecord.FIELD_SEC_USR_FILLER))
                .as("the copybook names this filler, so it is a resolvable span the differ can pin "
                        + "rather than an anonymous reserved one - and it must still be emitted, "
                        + "because omitting it makes every record 57 bytes")
                .isTrue();
        assertThat(SecUserRecord.SPAN_SEC_USR_FILLER.endOffsetExclusive()).isEqualTo(RECORD_LENGTH);

        int pinned = 0;
        for (ParityCase parityCase : cases()) {
            for (ParityCase.ExpectedRecord expectation : parityCase.expectedFinalState()) {
                assertThat(expectation.dataset()).isEqualTo(USRSEC);
                String image = expectation.expectedBytes();
                assertThat(image)
                        .as("%s row %d pins the whole image, which is what proves the total width",
                                parityCase.caseId(), expectation.rowIndex())
                        .isNotNull()
                        .hasSize(RECORD_LENGTH);
                assertThat(image.substring(SecUserRecord.SEC_USR_FILLER_OFFSET))
                        .as("SEC-USR-FILLER is emitted as spaces, never zeros")
                        .isEqualTo(" ".repeat(SecUserRecord.SEC_USR_FILLER_LENGTH));
                SecUserRecord decoded = SecUserRecord.decode(image.getBytes(ASCII), ASCII);
                assertThat(new String(SecUserRecord.encode(decoded, ASCII), ASCII)).isEqualTo(image);
                pinned++;
            }
        }
        assertThat(pinned)
                .as("eighteen cases seed the ten DUSRSECJ records or the twenty-five-record file, and "
                        + "two seed an empty dataset")
                .isPositive();
    }

    @Test
    @DisplayName("the 57-to-80 pad is declared wherever rows are seeded, and only there")
    void theFiftySevenToEightyPadIsDeclaredWhereverRowsAreSeeded() {
        assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.sourceWidth()).isEqualTo(57);
        assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.targetWidth()).isEqualTo(RECORD_LENGTH);
        assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.padWidth())
                .isEqualTo(SecUserRecord.SEC_USR_FILLER_LENGTH);
        assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.copybook()).isEqualTo("CSUSR01Y");
        assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.describes(USRSEC)).isTrue();

        for (ParityCase parityCase : cases()) {
            ParityCase.DatasetInput input = parityCase.inputs().get(USRSEC);
            assertThat(input)
                    .as("%s seeds USRSEC one way or another; a case that named no dataset would list "
                            + "from nothing", parityCase.caseId())
                    .isNotNull();
            if (input.declaredEmpty()) {
                assertThat(input.recordLength()).isEqualTo(RECORD_LENGTH);
                assertThat(parityCase.normalisations())
                        .as("%s seeds no row, so there is nothing to pad", parityCase.caseId())
                        .isEmpty();
                continue;
            }
            assertThat(input.inline())
                    .as("%s: USRSEC has no ASCII fixture, so its rows are always inline",
                            parityCase.caseId())
                    .isTrue();
            for (String row : input.rows()) {
                assertThat(row)
                        .as("%s seeds DUSRSECJ's records at their in-stream width",
                                parityCase.caseId())
                        .hasSize(Normalisation.USRSEC_FILLER_PAD_57_TO_80.sourceWidth());
            }
            assertThat(parityCase.normalisations())
                    .as("%s must declare the pad that carries its rows to 80", parityCase.caseId())
                    .containsExactly(new DatasetNormalisation(USRSEC,
                            Normalisation.USRSEC_FILLER_PAD_57_TO_80));
        }
    }

    @Test
    @DisplayName("MOVE WS-MESSAGE TO ERRMSGO drops the last two bytes, not the first two")
    void theEightyToSeventyEightNarrowingTruncatesOnTheRight() {
        String sender = "A".repeat(78) + "YZ";
        assertThat(sender).hasSize(80);

        String receiver = CODEC.movePicX(sender, UserListResponse.ERRMSG_LENGTH);

        assertThat(receiver).hasSize(UserListResponse.ERRMSG_LENGTH);
        assertThat(receiver)
                .as("the leading 78 characters survive")
                .isEqualTo(sender.substring(0, UserListResponse.ERRMSG_LENGTH));
        assertThat(receiver)
                .as("a left-truncating move would have kept the trailing bytes")
                .isNotEqualTo(sender.substring(sender.length() - UserListResponse.ERRMSG_LENGTH));

        for (ParityCase parityCase : cases()) {
            for (ScreenSend send : parityCase.expectedResponse().sends()) {
                String pinned = send.fields().get(outputItem(UserListResponse.ERRMSG_FIELD));
                assertThat(pinned)
                        .as("%s pins the message line at ERRMSGO's declared width",
                                parityCase.caseId())
                        .isNotNull()
                        .hasSize(UserListResponse.ERRMSG_LENGTH);
                String rebuilt = CODEC.movePicX(
                        CODEC.movePicX(pinned.strip(), MessageChannel.WS_MESSAGE_80.fixedWidth()),
                        UserListResponse.ERRMSG_LENGTH);
                assertThat(rebuilt)
                        .as("%s: the pinned line is its own text taken through both moves",
                                parityCase.caseId())
                        .isEqualTo(pinned);
            }
        }
    }

    @Test
    @DisplayName("STARTBR, READNEXT and READPREV each reach every arm (gate G47)")
    void everyBrowseOutcomeIsExercised() {
        assertThat(FileStatus.outcomeOfCicsResp(FileStatus.NORMAL)).isEqualTo(FileStatus.Outcome.OK);
        assertThat(FileStatus.outcomeOfCicsResp(FileStatus.NOTFND))
                .isEqualTo(FileStatus.Outcome.NOT_FOUND);
        assertThat(FileStatus.outcomeOfCicsResp(FileStatus.ENDFILE))
                .isEqualTo(FileStatus.Outcome.END_OF_FILE);
        assertThat(FileStatus.outcomeOfCicsResp(FileStatus.INVREQ)).isEqualTo(FileStatus.Outcome.OTHER);

        assertThat(messageOf("case02")).isEqualTo("You have reached the bottom of the page...");
        assertThat(messageOf("case04")).isEqualTo("You are at the top of the page...");
        assertThat(messageOf("case05")).isEqualTo("You are at the top of the page...");
        assertThat(messageOf("case17")).isEqualTo("Unable to lookup User...");
        assertThat(messageOf("case19")).isEqualTo("Unable to lookup User...");
        assertThat(caseNamed("case19").screenRequest().forcedOutcomes())
                .containsExactly(java.util.Map.entry(RepositoryOperation.START_BROWSE,
                        new ForcedOutcome(FileStatus.Outcome.OTHER, null, null)));

        assertThat(messageOf("case03")).isEqualTo("You have reached the bottom of the page...");
        assertThat(messageOf("case10")).isEqualTo("Unable to lookup User...");

        assertThat(messageOf("case07")).isEmpty();
        assertThat(messageOf("case20")).isEqualTo("You have reached the top of the page...");
        assertThat(messageOf("case16")).isEqualTo("You have reached the top of the page...");

        assertThat(messageOf("case08")).isEqualTo("You are already at the top of the page...");

        assertThat(messageOf("case06")).isEmpty();
        assertThat(messageOf("case09")).isEmpty();
    }

    @Test
    @DisplayName("ENTER and REENTER, and the ENTER / PF3 / PF7 / PF8 / no-match AID matrix (gate G38)")
    void bothProgramContextsAndEveryHandledAidAreDriven() {
        List<String> contexts = new ArrayList<>();
        List<String> handled = new ArrayList<>();
        boolean noMatchSeen = false;
        boolean noAreaSeen = false;

        for (ParityCase parityCase : cases()) {
            ParityCase.ScreenRequest request = parityCase.screenRequest();
            if (request.eibcalen() == 0) {
                noAreaSeen = true;
                continue;
            }
            contexts.add(request.commarea().getOrDefault(NavigationContext.PGM_CONTEXT_FIELD, "0"));
            if (request.aid() == null) {
                continue;
            }
            byte aid = aidByteOf(request.aid());
            if (PfKeyResolver.isEnter(aid)) {
                handled.add("ENTER");
            } else if (PfKeyResolver.isPf3(aid)) {
                handled.add("PF3");
            } else if (PfKeyResolver.isPf7(aid)) {
                handled.add("PF7");
            } else if (PfKeyResolver.isPf8(aid)) {
                handled.add("PF8");
            } else {
                noMatchSeen = true;
                assertThat(PfKeyResolver.resolve(aid))
                        .as("an AID the four inline tests all reject is still a key the resolver "
                                + "recognises; it simply is not one of them")
                        .isPresent();
            }
        }

        assertThat(noAreaSeen).as("the EIBCALEN = 0 guard at :110 is driven").isTrue();
        assertThat(contexts).as("both sides of :115 IF NOT CDEMO-PGM-REENTER").contains("0", "1");
        assertThat(handled).contains("ENTER", "PF3", "PF7", "PF8");
        assertThat(noMatchSeen).as("the WHEN OTHER arm at :132-136 is driven").isTrue();
        assertThat(messageOf("case12"))
                .as("and it paints CCDA-MSG-INVALID-KEY from app/cpy/CSMSG01Y.cpy")
                .isEqualTo("Invalid key pressed. Please see below...");
    }

    @Test
    @DisplayName("U routes to COUSR02C, D to COUSR03C, and the first ticked row wins (gates G30, G40)")
    void selectionRoutingIsAResponseFieldAndTheScanIsOrdered() {
        assertThat(nextProgramOf("case13")).isEqualTo(UserListResponse.NEXT_PROGRAM_USER_UPDATE);
        assertThat(nextProgramOf("case14")).isEqualTo(UserListResponse.NEXT_PROGRAM_USER_DELETE);
        assertThat(nextProgramOf("case11")).isEqualTo("COADM01C");
        assertThat(nextProgramOf("case01")).isEqualTo(UserListResponse.NEXT_PROGRAM_SIGNON);

        ParityCase firstMatch = caseNamed("case15");
        assertThat(firstMatch.screenRequest().mapFields())
                .containsEntry("SEL0003I", "u")
                .containsEntry("SEL0007I", "D");
        assertThat(nextProgramOf("case15"))
                .as("a scan that took the last tick would have named the delete program")
                .isEqualTo(UserListResponse.NEXT_PROGRAM_USER_UPDATE);
        assertThat(firstMatch.expectedResponse().navigation())
                .containsEntry(UserListResponse.CU00_USR_SEL_FLG_FIELD, "u")
                .containsEntry(UserListResponse.CU00_USR_SELECTED_FIELD, "ADMIN003");

        ParityCase lastArm = caseNamed("case18");
        for (int rowNumber = 1; rowNumber < PAGE_SIZE; rowNumber++) {
            assertThat(lastArm.screenRequest().mapFields())
                    .as("case18 leaves row %d untouched, so arm %d must not match",
                            rowNumber, rowNumber)
                    .containsEntry(UserListRequest.selFieldName(rowNumber) + "I", " ");
        }
        assertThat(lastArm.screenRequest().mapFields())
                .containsEntry(UserListRequest.selFieldName(PAGE_SIZE) + "I", "d")
                .containsEntry(UserListRequest.usrIdFieldName(PAGE_SIZE) + "I", "USER0005");
        assertThat(nextProgramOf("case18"))
                .as("the fourth clause of :189-215 is WHEN 'd', which names the delete program")
                .isEqualTo(UserListResponse.NEXT_PROGRAM_USER_DELETE);
        assertThat(lastArm.expectedResponse().navigation())
                .containsEntry(UserListResponse.CU00_USR_SEL_FLG_FIELD, "d")
                .containsEntry(UserListResponse.CU00_USR_SELECTED_FIELD, "USER0005");
        assertThat(lastArm.expectedResponse().navigation()
                        .get(UserListResponse.CU00_USR_SEL_FLG_FIELD))
                .as("stored as typed: an upper-cased flag would be a value the source never sets")
                .isNotEqualTo("D");

        assertThat(List.of(
                        caseNamed("case13").expectedResponse()
                                .navigation().get(UserListResponse.CU00_USR_SEL_FLG_FIELD),
                        caseNamed("case15").expectedResponse()
                                .navigation().get(UserListResponse.CU00_USR_SEL_FLG_FIELD),
                        caseNamed("case14").expectedResponse()
                                .navigation().get(UserListResponse.CU00_USR_SEL_FLG_FIELD),
                        caseNamed("case18").expectedResponse()
                                .navigation().get(UserListResponse.CU00_USR_SEL_FLG_FIELD)))
                .as(":190 'U', :191 'u', :200 'D' and :201 'd' are each driven")
                .containsExactly("U", "u", "D", "d");

        assertThat(nextProgramOf("case16"))
                .as("case16 presses PF7, and no PF-key path names a next program")
                .isNull();

        for (ParityCase parityCase : cases()) {
            ParityCase.ExpectedResponse response = parityCase.expectedResponse();
            if (response.termination() == Termination.XCTL) {
                assertThat(response.nextProgram())
                        .as("%s transfers, so it names where to", parityCase.caseId())
                        .isNotNull();
                assertThat(response.sends())
                        .as("%s transferred before painting anything", parityCase.caseId())
                        .isEmpty();
            } else {
                assertThat(response.nextProgram())
                        .as("%s returns to itself, so it names no next program", parityCase.caseId())
                        .isNull();
                assertThat(response.sends())
                        .as("%s pins the one transmission the published handler exposes",
                                parityCase.caseId())
                        .hasSize(1);
            }
            assertThat(response.nextMapset()).isNull();
            assertThat(response.nextMap()).isNull();
        }
    }

    @Test
    @DisplayName("two identical calls over one controller return identical responses (gate G37)")
    void theHandlerRetainsNothingBetweenCalls() {
        for (String caseId : List.of("case05", "case06", "case20")) {
            ParityCase parityCase = caseNamed(caseId);
            List<ObservedResponse> observed = new ArrayList<>(2);
            DiffResult diff = ParityHarness.usAscii().judge(parityCase, UnitKind.CONTROLLER_POJO,
                    invocation -> {
                        for (ScreenResponse<UserListResponse> response : callHandler(invocation, 2)) {
                            observed.add(observedOf(response));
                        }
                        UnitOutcome.Builder recorder = invocation.recorder();
                        recorder.response(observed.get(observed.size() - 1));
                        recorder.finalStateUnchanged(invocation.dataset(USRSEC),
                                SecUserRecord.LAYOUT);
                        recorder.returnCode(0);
                        return null;
                    });

            assertThat(observed).hasSize(2);
            assertThat(observed.get(1))
                    .as("%s: the second call over the same controller must answer identically",
                            caseId)
                    .isEqualTo(observed.get(0));
            assertThat(diff.count())
                    .withFailMessage("%s", diff.render())
                    .isZero();
        }
    }

    @Test
    @DisplayName("no attribute byte is moved, because no field is validated (practice B5)")
    void noAttributeIsMovedBecauseNoFieldIsValidated() {
        FieldAttributeSetter.FieldHighlight highlight =
                FieldAttributeSetter.resolve(FieldValidationState.of(false, false), true);
        assertThat(highlight.untouched())
                .as("a field in the OK state is not highlighted, even on a re-entry")
                .isTrue();

        for (ParityCase parityCase : cases()) {
            for (ScreenSend send : parityCase.expectedResponse().sends()) {
                assertThat(send.attributes())
                        .as("%s declares no attribute item, because the program moves none",
                                parityCase.caseId())
                        .isEmpty();
                assertThat(send.fields())
                        .as("%s pins all %d DFHMDF fields of the transmission",
                                parityCase.caseId(), UserListResponse.MAP_FIELD_COUNT)
                        .hasSize(UserListResponse.MAP_FIELD_COUNT);
            }
        }

        ParityCase listing = caseNamed("case05");
        List<Integer> colours = new ArrayList<>();
        ParityHarness.usAscii().run(listing, UnitKind.CONTROLLER_POJO, invocation -> {
            ScreenResponse<UserListResponse> response = callHandler(invocation, 1).get(0);
            colours.add(Integer.valueOf(response.screenMetadata().messageColour().intValue()));
            UnitOutcome.Builder recorder = invocation.recorder();
            recorder.response(observedOf(response));
            recorder.finalStateUnchanged(invocation.dataset(USRSEC), SecUserRecord.LAYOUT);
            recorder.returnCode(0);
            return null;
        });
        assertThat(colours).containsExactly(Integer.valueOf(BmsAttributes.DFHDFCOL & 0xFF));
    }

    @Test
    @DisplayName("SEC-USR-PWD stays plaintext in the record and never reaches the screen (practice B6)")
    void thePlaintextCredentialSpanNeverReachesTheScreen() {
        assertThat(SecUserRecord.SEC_USR_PWD_OFFSET).isEqualTo(48);
        assertThat(SecUserRecord.SEC_USR_PWD_LENGTH).isEqualTo(8);

        boolean anyRecordSeen = false;
        for (ParityCase parityCase : cases()) {
            for (ParityCase.ExpectedRecord expectation : parityCase.expectedFinalState()) {
                String image = expectation.expectedBytes();
                assertThat(image.substring(SecUserRecord.SEC_USR_PWD_OFFSET,
                        SecUserRecord.SEC_USR_PWD_OFFSET + SecUserRecord.SEC_USR_PWD_LENGTH))
                        .as("the span is carried as the legacy design stores it")
                        .isNotBlank();
                anyRecordSeen = true;
            }
            for (ScreenSend send : parityCase.expectedResponse().sends()) {
                for (Map.Entry<String, String> field : send.fields().entrySet()) {
                    assertThat(field.getValue())
                            .as("%s: %s must not carry the credential span - COUSR00.CPY declares no "
                                    + "field for it", parityCase.caseId(), field.getKey())
                            .doesNotContain(credentialLiteral());
                }
            }
        }
        assertThat(anyRecordSeen).isTrue();
    }

    private static ParityCase caseNamed(final String caseId) {
        for (ParityCase parityCase : cases()) {
            if (parityCase.caseId().equals(caseId)) {
                return parityCase;
            }
        }
        throw new IllegalArgumentException("No parity case " + caseId + " for " + PROGRAM
                + ". The set is case01 through case20, and an assertion naming anything else is "
                + "asserting against a case nobody wrote.");
    }

    private static Map<String, String> onlySendOf(final String caseId) {
        List<ScreenSend> sends = caseNamed(caseId).expectedResponse().sends();
        assertThat(sends)
                .as("%s is a returning case, so it pins exactly one transmission", caseId)
                .hasSize(1);
        return sends.get(0).fields();
    }

    private static String messageOf(final String caseId) {
        return onlySendOf(caseId).get(outputItem(UserListResponse.ERRMSG_FIELD)).strip();
    }

    private static String pageNumberOf(final String caseId) {
        return onlySendOf(caseId).get(outputItem(UserListResponse.PAGENUM_FIELD));
    }

    private static String nextProgramOf(final String caseId) {
        return caseNamed(caseId).expectedResponse().nextProgram();
    }

    private static byte aidByteOf(final String mnemonic) {
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return entry.getKey().byteValue();
            }
        }
        throw new IllegalArgumentException("DFHAID names no attention identifier " + mnemonic
                + ". CicsAid is the single reproduction of that IBM-supplied copybook, which is absent "
                + "from this repository, so a mnemonic it does not carry is a mnemonic no case may "
                + "declare.");
    }

    private static String credentialLiteral() {
        return "PASS" + "WORD";
    }
}
