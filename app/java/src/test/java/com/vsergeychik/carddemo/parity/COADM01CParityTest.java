package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.vsergeychik.carddemo.admin.AdminMenuController;
import com.vsergeychik.carddemo.admin.AdminMenuService;
import com.vsergeychik.carddemo.admin.AdminMenuService.AdminMenuInput;
import com.vsergeychik.carddemo.admin.AdminMenuService.AdminMenuOptionTable;
import com.vsergeychik.carddemo.admin.AdminMenuService.AdminMenuOutcome;
import com.vsergeychik.carddemo.admin.dto.AdminMenuRequest;
import com.vsergeychik.carddemo.admin.dto.AdminMenuResponse;
import com.vsergeychik.carddemo.admin.model.AdminMenuOptions;
import com.vsergeychik.carddemo.admin.model.AdminMenuOptions.AdminMenuOption;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.DecodedFingerprint;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.ParityUnit;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The twenty-case parity gate for {@code app/cbl/COADM01C.cbl} - the CardDemo administrator menu, CSD
 * transaction {@code CA00}, mapset {@code COADM01}, map {@code COADM1A}, projected as
 * {@code GET /api/admin/menu}.
 */
@DisplayName("COADM01C parity gate - admin menu, CSD transaction CA00, GET /api/admin/menu")
class COADM01CParityTest {
    private static final String PROGRAM = "COADM01C";

    private static final String TRANSACTION_ID = "CA00";

    private static final String MAPSET_NAME = "COADM01";

    private static final String MAP_NAME = "COADM1A";

    private static final String SIGNON_PROGRAM = "COSGN00C";

    private static final int ACTIVE_OPTION_COUNT = 4;

    private static final int OCCURS_TABLE_SIZE = 9;

    private static final int MENU_LINE_COUNT = 12;

    private static final int MENU_LINE_LENGTH = 40;

    private static final int TRN_NAME_LENGTH = 4;

    private static final int TITLE_LENGTH = 40;

    private static final int CURDATE_LENGTH = 8;

    private static final int PGM_NAME_LENGTH = 8;

    private static final int CURTIME_LENGTH = 8;

    private static final int OPTION_LENGTH = 2;

    private static final int WS_MESSAGE_LENGTH = 80;

    private static final int ERRMSG_LENGTH = 78;

    private static final int USRSEC_FILE_NAME_LENGTH = 8;

    private static final String USRSEC_FILE_NAME = "USRSEC  ";

    private static final String USRSEC_DATASET_KEY = "USRSEC";

    private static final String USRSEC_TEST_DSNAME = "CARDDEMO.TEST.USRSEC.VSAM.KSDS";

    private static final String USRSEC_COPYBOOK = "CSUSR01Y";

    private static final String USRSEC_RECORD_FORMAT = "FB";

    private static final List<String> OPTION_NAMES = List.of(
            "User List (Security)               ",
            "User Add (Security)                ",
            "User Update (Security)             ",
            "User Delete (Security)             ");

    private static final List<String> OPTION_PROGRAMS = List.of(
            "COUSR00C",
            "COUSR01C",
            "COUSR02C",
            "COUSR03C");

    private static final String MENU_TABLE_VARIANT_KEY = "MENU_TABLE_VARIANT";

    private static final String DUMMY_PREFIX_TABLE_VARIANT = "DUMMY_PREFIX_FIRST_ENTRY";

    private static final String DUMMY_OPTION_PROGRAM = "DUMMY001";

    private static final String CCDA_TITLE01 = "      AWS Mainframe Modernization       ";

    private static final String CCDA_TITLE02 = "              CardDemo                  ";

    private static final String CCDA_MSG_INVALID_KEY =
            "Invalid key pressed. Please see below..." + "          ";

    private static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    private static final String COMING_SOON_MESSAGE = "This option " + "is coming soon ...";

    private static final String GREEN_MNEMONIC = "DFHGREEN";

    private static final String RED_MNEMONIC = "DFHRED";

    private static final int COMMAREA_LENGTH = 160;

    private static final String TRNNAME_FIELD = "TRNNAMEO";

    private static final String TITLE01_FIELD = "TITLE01O";

    private static final String CURDATE_FIELD = "CURDATEO";

    private static final String PGMNAME_FIELD = "PGMNAMEO";

    private static final String TITLE02_FIELD = "TITLE02O";

    private static final String CURTIME_FIELD = "CURTIMEO";

    private static final String OPTION_FIELD = "OPTIONO";

    private static final String ERRMSG_FIELD = "ERRMSGO";

    private static final String ERRMSG_COLOUR_FIELD = "ERRMSGC";

    private static final String OPTION_INPUT_FIELD = "OPTIONI";

    private static final String BLANK_MENU_LINE = ScreenFieldImage.unpainted(MENU_LINE_LENGTH);

    private static final String BLANK_WS_MESSAGE = spaces(WS_MESSAGE_LENGTH);

    private static final String BLANK_ERRMSG = spaces(ERRMSG_LENGTH);

    private static final String BLANK_OPTION = ScreenFieldImage.unpainted(OPTION_LENGTH);

    private static final List<String> PAINTED_MENU_LINES = paintedMenuLines();

    private static final List<String> PRODUCIBLE_WS_MESSAGES = List.of(
            BLANK_WS_MESSAGE,
            picX(INVALID_OPTION_MESSAGE, WS_MESSAGE_LENGTH),
            picX(CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH),
            picX(COMING_SOON_MESSAGE, WS_MESSAGE_LENGTH));

    private static final List<String> DATA_ACCESS_TYPE_MARKERS =
            List.of("repository", "jdbctemplate", "datasource", "connection", "entitymanager");

    private static final Map<String, Byte> AID_BY_MNEMONIC = aidByMnemonic();

    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("produces zero differences against its statically derived expectation")
    void theCaseProducesZeroDifferences(ParityCase parityCase) {
        ParityHarness harness = ParityHarness.usAscii();

        DecodedFingerprint fingerprint =
                harness.run(parityCase, parityCase.unitKind(), unitFor(parityCase));
        DiffResult diff = harness.judge(parityCase, fingerprint);

        assertThat(diff.count())
                .describedAs("%s: the gate requires a diff count of 0 across all twenty cases.%n%s",
                        parityCase.caseId(), diff.render())
                .isZero();

        assertNoDatasetActivity(parityCase, fingerprint);
        assertReturnCodeIsNormal(parityCase, fingerprint);
    }

    @Test
    @DisplayName("declares exactly twenty cases, case01 to case20, all naming COADM01C")
    void theCaseSetIsExactlyTwentyCasesForThisProgram() {
        List<ParityCase> loaded = cases();

        assertThat(loaded).hasSize(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            ParityCase parityCase = loaded.get(ordinal - 1);
            assertThat(parityCase.caseId())
                    .describedAs("case %d must be %s", ordinal, ParityHarness.caseId(ordinal))
                    .isEqualTo(ParityHarness.caseId(ordinal));
            assertThat(parityCase.program())
                    .describedAs("%s names the wrong program, so it was copied from another "
                            + "program's directory", parityCase.caseId())
                    .isEqualTo(PROGRAM);
            assertThat(parityCase.description().length())
                    .describedAs("%s must say what it exercises, citing the COBOL it derives from",
                            parityCase.caseId())
                    .isGreaterThan(120);
        }
    }

    @Test
    @DisplayName("resolves every case to the adapter its declared unit kind names")
    void everyCaseIsDispatchedByItsDeclaredUnitKind() {
        List<String> dispatched = new ArrayList<>();
        for (ParityCase parityCase : cases()) {
            assertThat(unitFor(parityCase))
                    .describedAs("%s resolves to no adapter", parityCase.caseId())
                    .isNotNull();
            assertThat(parityCase.unitKind())
                    .describedAs("%s declares %s, and %s is an online transaction reached as either a "
                            + "service or a controller; no other kind has an adapter here",
                            parityCase.caseId(), parityCase.unitKind(), PROGRAM)
                    .isIn(UnitKind.SERVICE, UnitKind.CONTROLLER_POJO);
            dispatched.add(parityCase.caseId());
        }

        assertThat(dispatched).hasSize(ParityHarness.CASES_PER_PROGRAM).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("declares SERVICE for seventeen cases and CONTROLLER_POJO for case01, case02, case15")
    void theDeclaredUnitKindsAreSeventeenServicesAndThreeControllers() {
        List<String> controllers = cases().stream()
                .filter(one -> one.unitKind() == UnitKind.CONTROLLER_POJO)
                .map(ParityCase::caseId)
                .toList();
        List<String> services = cases().stream()
                .filter(one -> one.unitKind() == UnitKind.SERVICE)
                .map(ParityCase::caseId)
                .toList();

        assertThat(controllers)
                .describedAs("the three cases that need the controller are the ones observing the six "
                        + "POPULATE-HEADER-INFO fields and the 78-byte ERRMSGO truncation")
                .containsExactly("case01", "case02", "case15");
        assertThat(services)
                .describedAs("every other case reaches AdminMenuService directly")
                .hasSize(ParityHarness.CASES_PER_PROGRAM - controllers.size());
        assertThat(services.size() + controllers.size())
                .isEqualTo(ParityHarness.CASES_PER_PROGRAM);
    }

    @Test
    @DisplayName("declares both the LOW-VALUES and the SPACES term of the line 162 relation")
    void theCaseSetDeclaresBothTermsOfTheSignonDefaultingRelation() {
        String unpainted = ScreenFieldImage.unpainted(NavigationContext.TO_PROGRAM_LENGTH);
        List<String> coldStart = new ArrayList<>();
        List<String> declaringLowValues = new ArrayList<>();
        List<String> declaringNoToProgram = new ArrayList<>();

        for (ParityCase parityCase : cases()) {
            if (parityCase.screenRequest().eibcalen() != 0) {
                continue;
            }
            coldStart.add(parityCase.caseId());
            String image = parityCase.screenRequest().commarea()
                    .get(NavigationContext.TO_PROGRAM_FIELD);
            if (image == null) {
                declaringNoToProgram.add(parityCase.caseId());
            } else {
                assertThat(image)
                        .describedAs("%s declares %s on the EIBCALEN = 0 path, and the only image "
                                + "worth declaring there is the LOW-VALUES term itself; anything else "
                                + "would be a third spelling of a two-term relation",
                                parityCase.caseId(), NavigationContext.TO_PROGRAM_FIELD)
                        .isEqualTo(unpainted);
                declaringLowValues.add(parityCase.caseId());
            }
        }

        assertThat(coldStart)
                .describedAs("lines 82-84 divert on EIBCALEN = 0, and that diversion is the only path "
                        + "on which a signon target is resolved at all")
                .isNotEmpty();
        assertThat(declaringLowValues)
                .describedAs("some case must declare CDEMO-TO-PROGRAM as X'00' at every one of its %d "
                        + "declared bytes, or the LOW-VALUES term of line 162 is never the term under "
                        + "test", NavigationContext.TO_PROGRAM_LENGTH)
                .isNotEmpty();
        assertThat(declaringNoToProgram)
                .describedAs("and some case must arrive with no area at all, which is the un-VALUEd "
                        + "working storage this projection presents as spaces - the other term")
                .isNotEmpty();
    }

    @Test
    @DisplayName("declares no write, no final state and no dataset expectation in any case")
    void noCaseDeclaresADatasetExpectation() {
        for (ParityCase parityCase : cases()) {
            assertThat(parityCase.expectedWrites())
                    .describedAs("%s expects a write, but COADM01C has no WRITE, REWRITE or DELETE "
                            + "anywhere in its 268 lines", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedFinalState())
                    .describedAs("%s expects a final dataset state, but COADM01C opens no dataset",
                            parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedDatasets())
                    .describedAs("%s expects a dataset, but COADM01C creates none",
                            parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.jobParameters())
                    .describedAs("%s declares a job parameter, but COADM01C is an online program "
                            + "reached by transaction CA00 and not by EXEC PGM=", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.screenRequest().forcedOutcomes())
                    .describedAs("%s forces a repository outcome, but COADM01C has no repository "
                            + "call site to force one at", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedResponse().cursorField())
                    .describedAs("%s expects a cursor, but COADM01C never moves -1 into an xxxL item",
                            parityCase.caseId())
                    .isNull();
        }
    }

    @Test
    @DisplayName("pins only real symbolic-map fields, each at exactly its declared width")
    void everyPinnedScreenFieldMatchesItsDeclaredWidth() {
        Map<String, Integer> screenWidths = declaredScreenWidths();
        Map<String, Integer> commareaWidths = declaredCommareaWidths();

        for (ParityCase parityCase : cases()) {
            for (Map.Entry<String, String> field : parityCase.expectedResponse().navigation()
                    .entrySet()) {
                assertThat(commareaWidths)
                        .describedAs("%s pins '%s', which app/cpy/COCOM01Y.cpy does not declare",
                                parityCase.caseId(), field.getKey())
                        .containsKey(field.getKey());
                assertThat(field.getValue().length())
                        .describedAs("%s pins %s at the wrong width", parityCase.caseId(),
                                field.getKey())
                        .isEqualTo(commareaWidths.get(field.getKey()));
            }
            if (parityCase.expectedResponse().navigation().isEmpty()) {
                assertThat(parityCase.expectedResponse().termination())
                        .describedAs("%s pins no COMMAREA field, which only the no-COMMAREA transfer at "
                                + "app/cbl/COADM01C.cbl:165-167 can produce; a RETURN always carries the area",
                                parityCase.caseId())
                        .isEqualTo(ParityCase.Termination.XCTL);
            } else {
                assertThat(parityCase.expectedResponse().navigation())
                        .describedAs("%s must pin all sixteen COMMAREA fields, because the differ "
                                + "compares the navigation context in both directions",
                                parityCase.caseId())
                        .hasSameSizeAs(commareaWidths);
            }

            for (var send : parityCase.expectedResponse().sends()) {
                for (Map.Entry<String, String> field : send.fields().entrySet()) {
                    assertThat(screenWidths)
                            .describedAs("%s pins '%s', which is not one of the twenty labelled "
                                    + "DFHMDF fields of mapset COADM01", parityCase.caseId(),
                                    field.getKey())
                            .containsKey(field.getKey());
                    assertThat(field.getValue().length())
                            .describedAs("%s pins %s at %d character(s)", parityCase.caseId(),
                                    field.getKey(), field.getValue().length())
                            .isEqualTo(screenWidths.get(field.getKey()));
                }
                assertThat(send.attributes())
                        .describedAs("%s: ERRMSGC is the only attribute item COADM01C ever writes, at "
                                + "line 148", parityCase.caseId())
                        .containsOnlyKeys(ERRMSG_COLOUR_FIELD);
                assertThat(send.attributes().get(ERRMSG_COLOUR_FIELD))
                        .describedAs("%s: the map declares COLOR=RED and only line 148 overrides it",
                                parityCase.caseId())
                        .isIn(RED_MNEMONIC, GREEN_MNEMONIC);
            }
        }
    }

    private static Map<String, Integer> declaredScreenWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put(TRNNAME_FIELD, TRN_NAME_LENGTH);
        widths.put(TITLE01_FIELD, TITLE_LENGTH);
        widths.put(CURDATE_FIELD, CURDATE_LENGTH);
        widths.put(PGMNAME_FIELD, PGM_NAME_LENGTH);
        widths.put(TITLE02_FIELD, TITLE_LENGTH);
        widths.put(CURTIME_FIELD, CURTIME_LENGTH);
        for (int subscript = 1; subscript <= MENU_LINE_COUNT; subscript++) {
            widths.put(menuLineField(subscript), MENU_LINE_LENGTH);
        }
        widths.put(OPTION_FIELD, OPTION_LENGTH);
        widths.put(ERRMSG_FIELD, ERRMSG_LENGTH);
        return Collections.unmodifiableMap(widths);
    }

    private static Map<String, Integer> declaredCommareaWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put(NavigationContext.FROM_TRANID_FIELD, NavigationContext.FROM_TRANID_LENGTH);
        widths.put(NavigationContext.FROM_PROGRAM_FIELD, NavigationContext.FROM_PROGRAM_LENGTH);
        widths.put(NavigationContext.TO_TRANID_FIELD, NavigationContext.TO_TRANID_LENGTH);
        widths.put(NavigationContext.TO_PROGRAM_FIELD, NavigationContext.TO_PROGRAM_LENGTH);
        widths.put(NavigationContext.USER_ID_FIELD, NavigationContext.USER_ID_LENGTH);
        widths.put(NavigationContext.USER_TYPE_FIELD, NavigationContext.USER_TYPE_LENGTH);
        widths.put(NavigationContext.PGM_CONTEXT_FIELD, NavigationContext.PGM_CONTEXT_LENGTH);
        widths.put(NavigationContext.CUST_ID_FIELD, NavigationContext.CUST_ID_LENGTH);
        widths.put(NavigationContext.CUST_FNAME_FIELD, NavigationContext.CUST_FNAME_LENGTH);
        widths.put(NavigationContext.CUST_MNAME_FIELD, NavigationContext.CUST_MNAME_LENGTH);
        widths.put(NavigationContext.CUST_LNAME_FIELD, NavigationContext.CUST_LNAME_LENGTH);
        widths.put(NavigationContext.ACCT_ID_FIELD, NavigationContext.ACCT_ID_LENGTH);
        widths.put(NavigationContext.ACCT_STATUS_FIELD, NavigationContext.ACCT_STATUS_LENGTH);
        widths.put(NavigationContext.CARD_NUM_FIELD, NavigationContext.CARD_NUM_LENGTH);
        widths.put(NavigationContext.LAST_MAP_FIELD, NavigationContext.LAST_MAP_LENGTH);
        widths.put(NavigationContext.LAST_MAPSET_FIELD, NavigationContext.LAST_MAPSET_LENGTH);
        return Collections.unmodifiableMap(widths);
    }

    @Test
    @DisplayName("agrees with the translation about every copybook literal it transcribes")
    void copybookLiteralsAgreeWithTheTranslation() {
        assertThat(AdminMenuService.PROGRAM_NAME).isEqualTo(PROGRAM);
        assertThat(AdminMenuService.TRANSACTION_ID).isEqualTo(TRANSACTION_ID);
        assertThat(AdminMenuService.MAPSET_NAME).isEqualTo(MAPSET_NAME);
        assertThat(AdminMenuService.MAP_NAME).isEqualTo(MAP_NAME);
        assertThat(AdminMenuService.SIGNON_PROGRAM).isEqualTo(SIGNON_PROGRAM);
        assertThat(AdminMenuService.MESSAGE_LENGTH).isEqualTo(WS_MESSAGE_LENGTH);
        assertThat(AdminMenuService.OPTION_TEXT_LENGTH).isEqualTo(MENU_LINE_LENGTH);
        assertThat(AdminMenuService.OPTION_LINE_COUNT).isEqualTo(MENU_LINE_COUNT);
        assertThat(AdminMenuService.OPTION_LENGTH).isEqualTo(OPTION_LENGTH);
        assertThat(AdminMenuService.INVALID_OPTION_MESSAGE).isEqualTo(INVALID_OPTION_MESSAGE);
        assertThat(AdminMenuService.COMING_SOON_MESSAGE).isEqualTo(COMING_SOON_MESSAGE);

        assertThat(AdminMenuOptions.ACTIVE_OPTION_COUNT).isEqualTo(ACTIVE_OPTION_COUNT);
        assertThat(AdminMenuOptions.TABLE_SIZE).isEqualTo(OCCURS_TABLE_SIZE);
        assertThat(AdminMenuOptions.OPT_NAME_LENGTH).isEqualTo(OPTION_NAMES.get(0).length());
        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            AdminMenuOption entry = AdminMenuOptions.optionBySubscript(subscript).orElseThrow();
            assertThat(entry.adminOptName())
                    .describedAs("CDEMO-ADMIN-OPT-NAME(%d)", subscript)
                    .isEqualTo(OPTION_NAMES.get(subscript - 1));
            assertThat(entry.adminOptPgmName())
                    .describedAs("CDEMO-ADMIN-OPT-PGMNAME(%d)", subscript)
                    .isEqualTo(OPTION_PROGRAMS.get(subscript - 1));
        }

        assertThat(ScreenTitles.CCDA_TITLE01).isEqualTo(CCDA_TITLE01);
        assertThat(ScreenTitles.CCDA_TITLE02).isEqualTo(CCDA_TITLE02);
        assertThat(SystemMessages.CCDA_MSG_INVALID_KEY).isEqualTo(CCDA_MSG_INVALID_KEY);
        assertThat(CCDA_MSG_INVALID_KEY)
                .describedAs("CCDA-MSG-INVALID-KEY is PIC X(50) in app/cpy/CSMSG01Y.cpy while "
                        + "CCDA-THANK-YOU is PIC X(40) in app/cpy/COTTL01Y.cpy; one is never the other")
                .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU)
                .hasSize(50);

        assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED)).isEqualTo(RED_MNEMONIC);
        assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHGREEN)).isEqualTo(GREEN_MNEMONIC);
        assertThat(AdminMenuService.MAP_MESSAGE_COLOUR).isEqualTo(BmsAttributes.DFHRED);
        assertThat(AdminMenuService.COMING_SOON_MESSAGE_COLOUR).isEqualTo(BmsAttributes.DFHGREEN);

        assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(COMMAREA_LENGTH);
        assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(80);
    }

    @Test
    @DisplayName("fails its own menu expectations when the OCCURS base is shifted by one")
    void shiftingTheOccursBaseBreaksTheMenuExpectations() {
        AdminMenuService service = service(ParityHarness.usAscii().codec());

        List<String> correct = service.buildMenuOptions(AdminMenuOptionTable.copybook());
        assertThat(correct)
                .describedAs("the copybook table must compose exactly the four lines this class "
                        + "transcribed from app/cpy/COADM02Y.cpy")
                .isEqualTo(PAINTED_MENU_LINES);

        List<String> shifted = service.buildMenuOptions(rotatedByOne());
        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            assertThat(shifted.get(subscript - 1))
                    .describedAs("a 0-based read of the 1-based subscript %d must NOT reproduce the "
                            + "copybook's line; if it does, the menu expectations have no teeth",
                            subscript)
                    .isNotEqualTo(PAINTED_MENU_LINES.get(subscript - 1));
        }

        List<String> shortByOne = service.buildMenuOptions(truncatedByOne());
        assertThat(shortByOne.get(ACTIVE_OPTION_COUNT - 1))
                .describedAs("UNTIL WS-IDX > count is inclusive of the count, so an exclusive bound "
                        + "would leave the last active line blank - which is what this proves would "
                        + "be visible")
                .isEqualTo(BLANK_MENU_LINE);
        assertThat(correct.get(ACTIVE_OPTION_COUNT - 1)).isNotEqualTo(BLANK_MENU_LINE);

        assertThatExceptionOfType(IndexOutOfBoundsException.class)
                .describedAs("COBOL has no subscript 0, so addressing one must fail rather than "
                        + "silently return the first entry")
                .isThrownBy(() -> AdminMenuOptionTable.copybook().optionBySubscript(0));
    }

    private static ParityUnit unitFor(ParityCase parityCase) {
        return parityCase.unitKind() == UnitKind.CONTROLLER_POJO
                ? COADM01CParityTest::runController
                : COADM01CParityTest::runService;
    }

    private static UnitOutcome runService(Invocation invocation) {
        AdminMenuService service = service(invocation.codec());
        AdminMenuInput input = inputOf(invocation);
        AdminMenuOptionTable declaredTable = declaredOptionTable(invocation);
        Map<String, List<String>> seededBefore = snapshotOfSeededRows(invocation);

        SecUserRecord deadStorageBefore = service.secUserData();
        assertThat(deadStorageBefore)
                .describedAs("SEC-USER-DATA has no VALUE clause, so an initialised copy is blank")
                .isEqualTo(SecUserRecord.blank());

        AdminMenuOutcome outcome = handle(service, input, declaredTable);

        assertServiceInvariants(service, input, outcome);
        assertDeadWorkingStorageStaysDead(service, deadStorageBefore, outcome);
        assertStatelessAcrossThreeCalls(invocation, input, declaredTable, outcome);
        assertSeededDatasetsAreUntouched(invocation, seededBefore, service);
        if (invocation.eibcalen() == 0) {
            assertSignonDefaultingRelation(invocation, service, outcome);
        }
        if (declaredTable != null) {
            assertDummyPrefixBranch(declaredTable, outcome);
        }

        recordServiceOutcome(invocation, outcome);
        return null;
    }

    private static AdminMenuOutcome handle(AdminMenuService service, AdminMenuInput input,
                                           AdminMenuOptionTable declaredTable) {
        return declaredTable == null ? service.handle(input) : service.handle(input, declaredTable);
    }

    private static AdminMenuOptionTable declaredOptionTable(Invocation invocation) {
        String variant = invocation.stimulus()
                .environmentValue(MENU_TABLE_VARIANT_KEY)
                .orElse(null);
        if (variant == null) {
            return null;
        }
        if (!DUMMY_PREFIX_TABLE_VARIANT.equals(variant)) {
            throw new IllegalArgumentException(invocation.caseId() + " declares MENU_TABLE_VARIANT '"
                    + variant + "', and the only variant " + PROGRAM + " has a branch for is '"
                    + DUMMY_PREFIX_TABLE_VARIANT + "'. Honouring an unknown variant by running the "
                    + "copybook table would make the declaration do nothing in silence.");
        }
        return singleEntryTable(AdminMenuOption.of(1, OPTION_NAMES.get(0), DUMMY_OPTION_PROGRAM));
    }

    private static Map<String, List<String>> snapshotOfSeededRows(Invocation invocation) {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        invocation.datasets()
                .forEach((key, dataset) -> snapshot.put(key, List.copyOf(dataset.rows())));
        return snapshot;
    }

    private static void assertSeededDatasetsAreUntouched(Invocation invocation,
                                                         Map<String, List<String>> before,
                                                         AdminMenuService service) {
        before.forEach((key, rows) -> {
            SeededDataset dataset = invocation.dataset(key);
            if (USRSEC_DATASET_KEY.equals(key)) {
                assertThat(dataset.recordLength())
                        .describedAs("app/cpy/CSUSR01Y.cpy declares SEC-USER-DATA at 80 bytes, so the "
                                + "seed must have been padded from the 57 characters "
                                + "app/jcl/DUSRSECJ.jcl carries")
                        .isEqualTo(SecUserRecord.RECORD_LENGTH);
            }
            assertThat(dataset.rows())
                    .describedAs("%s opens no dataset, so every row seeded into %s must be exactly as "
                            + "it was seeded", PROGRAM, key)
                    .isEqualTo(rows);
        });
        assertNoDataAccessCollaborator(service);
    }

    private static void assertDeadWorkingStorageStaysDead(AdminMenuService service,
                                                          SecUserRecord before,
                                                          AdminMenuOutcome outcome) {
        assertThat(service.secUserData())
                .describedAs("not one of SEC-USER-DATA's six fields is read or written by %s, so the "
                        + "record is unchanged by a whole invocation", PROGRAM)
                .isEqualTo(before)
                .isEqualTo(SecUserRecord.blank());
        assertThat(service.usrSecFileName())
                .describedAs("WS-USRSEC-FILE VALUE 'USRSEC  ' - the logical file name padded to its "
                        + "declared PIC X(08), with the source literal's two trailing spaces intact")
                .isEqualTo(USRSEC_FILE_NAME)
                .hasSize(USRSEC_FILE_NAME_LENGTH);
        assertThat(observableStrings(outcome))
                .describedAs("neither dead declaration may leak onto the screen: WS-USRSEC-FILE is "
                        + "never moved anywhere, so its value must appear in no field of the response")
                .noneMatch(image -> image.contains(USRSEC_DATASET_KEY));
    }

    private static void assertStatelessAcrossThreeCalls(Invocation invocation, AdminMenuInput input,
                                                        AdminMenuOptionTable declaredTable,
                                                        AdminMenuOutcome first) {
        AdminMenuService shared = service(invocation.codec());
        assertThat(handle(shared, input, declaredTable))
                .describedAs("a second service instance handed the identical input must produce the "
                        + "identical outcome; anything else means the outcome depended on something "
                        + "outside the request")
                .isEqualTo(first);
        assertThat(handle(shared, input, declaredTable))
                .describedAs("and a second call on that same instance must produce it again; anything "
                        + "else means per-request working storage became a field")
                .isEqualTo(first);
        assertThat(first.navigationContext())
                .describedAs("the communication area travels in the payload, which is the only reason "
                        + "it is comparable at all")
                .isNotNull();
        assertNoMutableState(shared);
    }

    private static void assertSignonDefaultingRelation(Invocation invocation,
                                                       AdminMenuService service,
                                                       AdminMenuOutcome outcome) {
        assertThat(outcome.nextProgramCarriesCommarea())
                .describedAs("the XCTL at 165-167 specifies NO COMMAREA, unlike the one at 142-145, "
                        + "and an absent commarea is the condition COSGN00C's own EIBCALEN = 0 test "
                        + "looks for")
                .isFalse();

        String declared = declaredToProgramImage(invocation);
        String target = picX(SIGNON_PROGRAM, NavigationContext.TO_PROGRAM_LENGTH);
        assertThat(service.resolveSignonTarget(ScreenFieldImage
                        .unpainted(NavigationContext.TO_PROGRAM_LENGTH)))
                .describedAs("term one: a CDEMO-TO-PROGRAM of binary zeros defaults to 'COSGN00C' at "
                        + "line 163")
                .isEqualTo(target);
        assertThat(service.resolveSignonTarget(spaces(NavigationContext.TO_PROGRAM_LENGTH)))
                .describedAs("term two, which is the term this projection's cold-start area takes; "
                        + "the two converge, and that convergence is why the cases that declare each "
                        + "of them carry the same expectation")
                .isEqualTo(target);
        assertThat(service.resolveSignonTarget(declared))
                .describedAs("and the image %s itself declares, whichever term it is, resolves to the "
                        + "same target", invocation.caseId())
                .isEqualTo(target);
        assertThat(picX(outcome.nextProgram(), NavigationContext.TO_PROGRAM_LENGTH))
                .describedAs("which is the program the run transferred to")
                .isEqualTo(target);

        String halfAndHalf = ScreenFieldImage.unpainted(NavigationContext.TO_PROGRAM_LENGTH / 2)
                + spaces(NavigationContext.TO_PROGRAM_LENGTH / 2);
        assertThat(service.resolveSignonTarget(halfAndHalf))
                .describedAs("= LOW-VALUES needs every byte zero and = SPACES every byte a space, so a "
                        + "field that is half of each satisfies neither term and is transferred to "
                        + "unchanged")
                .isEqualTo(halfAndHalf);
    }

    private static void assertDummyPrefixBranch(AdminMenuOptionTable declaredTable,
                                                AdminMenuOutcome outcome) {
        assertThat(declaredTable.optionBySubscript(1).orElseThrow().adminOptPgmName())
                .describedAs("line 138 reference-modifies the first five characters, so the prefix is "
                        + "what matters and the remaining three bytes are ordinary name characters")
                .startsWith(AdminMenuService.DUMMY_PROGRAM_PREFIX)
                .hasSize(OPTION_PROGRAMS.get(0).length());
        assertThat(outcome.hasNextProgram())
                .describedAs("line 138 found 'DUMMY', so the XCTL at 142-145 is skipped entirely")
                .isFalse();
        assertThat(outcome.screenPainted())
                .describedAs("lines 147-154 are reachable only here, and line 154 sends the map")
                .isTrue();
        assertThat(outcome.messageColourOverridden())
                .describedAs("line 148 MOVE DFHGREEN TO ERRMSGC overrides the mapset's COLOR=RED, and "
                        + "this is the only statement in the program that does")
                .isTrue();
        assertThat(outcome.errFlgImage())
                .describedAs("WS-ERR-FLG is untouched on this path: a skipped transfer is a "
                        + "successful outcome, not a rejection")
                .isEqualTo(AdminMenuService.ERR_FLG_OFF);
        assertThat(outcome.message())
                .describedAs("lines 149-153 STRING the two live operands only; the "
                        + "CDEMO-ADMIN-OPT-NAME operand at 150-151 is commented out and stays out")
                .isEqualTo(picX(COMING_SOON_MESSAGE, WS_MESSAGE_LENGTH));
        assertThat(outcome.optionLine(1))
                .describedAs("the injected entry differs from the copybook's only in its program "
                        + "name, so the composed line must be byte-identical to the real one")
                .isEqualTo(PAINTED_MENU_LINES.get(0));
    }

    private static UnitOutcome runController(Invocation invocation) {
        AdminMenuService service = service(invocation.codec());
        AdminMenuController controller = new AdminMenuController(service, invocation.clock());

        var screen = controller
                .getAdminMenu(invocation.eibcalen() == 0 ? null : requestOf(invocation))
                .screen();

        assertThat(screen.trnName()).isEqualTo(TRANSACTION_ID);
        assertThat(screen.pgmName()).isEqualTo(PROGRAM);
        assertThat(screen.title01()).isEqualTo(CCDA_TITLE01);
        assertThat(screen.title02()).isEqualTo(CCDA_TITLE02);
        assertThat(screen.errMsg())
                .describedAs("MOVE WS-MESSAGE TO ERRMSGO at line 177 narrows PIC X(80) to PIC X(78)")
                .hasSize(ERRMSG_LENGTH);
        assertThat(screen.screenMetadata().resetAllOutputFields())
                .describedAs("MOVE LOW-VALUES TO COADM1AO at line 89 runs on the first-entry path "
                        + "alone, and the flag must reach the client beside the screen rather than "
                        + "inside it")
                .isEqualTo(screen.resetAllOutputFields());
        assertThat(screen.screenMetadata().messageColour())
                .describedAs("ERRMSGC travels as metadata, unsigned, because the attribute IBM "
                        + "documents as X'F2' is a negative Java byte")
                .isEqualTo(Byte.toUnsignedInt(screen.messageColour()));
        assertThat(screen.screenMetadata().cursorField())
                .describedAs("COADM01C never moves -1 into an xxxL item, so no cursor is requested")
                .isNull();
        assertTruncationKeptTheLeadingBytes(screen.errMsg());

        boolean transferred = !isBlank(screen.nextProgram());
        List<ObservedSend> sends = transferred
                ? List.of()
                : List.of(new ObservedSend(controllerSendFields(screen), colour(screen.messageColour())));

        recordObservation(invocation,
                observedResponse(screen.nextProgram(), screen.nextMapset(), screen.nextMap(),
                        navigationImages(invocation.codec(), screen.navigationContext()), sends,
                        transferred),
                new EmittedMessage(MessageChannel.SCREEN_ERRMSG_78, screen.errMsg()));
        return null;
    }

    private static void recordServiceOutcome(Invocation invocation, AdminMenuOutcome outcome) {
        boolean transferred = outcome.hasNextProgram();
        assertThat(transferred)
                .describedAs("exactly one of EXEC CICS XCTL and EXEC CICS SEND MAP happens on any one "
                        + "path through COADM01C, so a run reporting both or neither has lost the "
                        + "shape of the program")
                .isNotEqualTo(outcome.screenPainted());

        List<ObservedSend> sends = outcome.screenPainted()
                ? List.of(new ObservedSend(serviceSendFields(outcome), colour(outcome.messageColour())))
                : List.of();

        recordObservation(invocation,
                observedResponse(outcome.nextProgram(), outcome.mapsetName(), outcome.mapName(),
                        navigationImages(invocation.codec(), outcome.navigationContext()), sends,
                        transferred),
                new EmittedMessage(MessageChannel.WS_MESSAGE_80, outcome.message()));
    }

    private static void recordObservation(Invocation invocation, ObservedResponse response,
            EmittedMessage message) {
        invocation.recorder()
                .response(response)
                .message(message)
                .returnCode(0);
    }

    private static ObservedResponse observedResponse(String nextProgram, String nextMapset,
            String nextMap, Map<String, String> navigation, List<ObservedSend> sends,
            boolean transferred) {
        return new ObservedResponse(named(nextProgram), named(nextMapset), named(nextMap), navigation,
                sends, null, transferred ? Termination.XCTL : Termination.RETURN_TRANSID);
    }

    private static Map<String, String> serviceSendFields(AdminMenuOutcome outcome) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int subscript = 1; subscript <= MENU_LINE_COUNT; subscript++) {
            fields.put(menuLineField(subscript), outcome.optionLine(subscript));
        }
        fields.put(OPTION_FIELD, outcome.option());
        return fields;
    }

    private static Map<String, String> controllerSendFields(AdminMenuResponse screen) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(TRNNAME_FIELD, screen.trnName());
        fields.put(TITLE01_FIELD, screen.title01());
        fields.put(CURDATE_FIELD, screen.curDate());
        fields.put(PGMNAME_FIELD, screen.pgmName());
        fields.put(TITLE02_FIELD, screen.title02());
        fields.put(CURTIME_FIELD, screen.curTime());
        List<String> lines = screen.optionLines();
        for (int subscript = 1; subscript <= MENU_LINE_COUNT; subscript++) {
            fields.put(menuLineField(subscript), lines.get(subscript - 1));
        }
        fields.put(OPTION_FIELD, screen.option());
        fields.put(ERRMSG_FIELD, screen.errMsg());
        return fields;
    }

    private static Map<String, String> colour(byte messageColour) {
        return Map.of(ERRMSG_COLOUR_FIELD, BmsAttributes.colourMnemonic(messageColour));
    }

    private static void assertNoDatasetActivity(ParityCase parityCase,
            DecodedFingerprint fingerprint) {
        assertThat(fingerprint.writes())
                .describedAs("%s: COADM01C has no WRITE, REWRITE or DELETE anywhere in its 268 lines, "
                        + "so nothing may appear on the writes channel", parityCase.caseId())
                .isEmpty();
        assertThat(fingerprint.finalState())
                .describedAs("%s: COADM01C opens no dataset, so no dataset may appear on the final "
                        + "state channel either", parityCase.caseId())
                .isEmpty();
    }

    private static void assertReturnCodeIsNormal(ParityCase parityCase,
            DecodedFingerprint fingerprint) {
        assertThat(fingerprint.returnCode())
                .describedAs("%s: COADM01C is an online program with no abend site and no RETURN-CODE "
                        + "of its own", parityCase.caseId())
                .isZero();
    }

    private static void assertServiceInvariants(AdminMenuService service, AdminMenuInput input,
            AdminMenuOutcome outcome) {
        assertScreenIdentity(outcome);
        assertOptionEcho(service, input, outcome);
        assertEvaluateArm(input, outcome);
    }

    private static void assertScreenIdentity(AdminMenuOutcome outcome) {
        assertThat(outcome.transactionId())
                .describedAs("RETURN TRANSID(WS-TRANID) at line 108, and DEFINE TRANSACTION(CA00) "
                        + "PROGRAM(COADM01C) at app/csd/CARDDEMO.CSD:327-328")
                .isEqualTo(TRANSACTION_ID);
        if (outcome.screenPainted()) {
            assertThat(outcome.mapsetName()).isEqualTo(MAPSET_NAME);
            assertThat(outcome.mapName()).isEqualTo(MAP_NAME);
        } else {
            assertThat(isBlank(outcome.mapsetName()) && isBlank(outcome.mapName()))
                    .describedAs("neither EXEC CICS XCTL names a map, so a transfer must name none "
                            + "either: which map the successor paints is the successor's decision")
                    .isTrue();
        }
    }

    private static void assertOptionEcho(AdminMenuService service, AdminMenuInput input,
            AdminMenuOutcome outcome) {
        String asDelivered = picX(input.option(), OPTION_LENGTH);

        if (input.isCommareaPresent() && !input.isReenter()) {
            assertThat(outcome.option())
                    .describedAs("MOVE LOW-VALUES TO COADM1AO at line 89 clears OPTIONO before the "
                            + "first-entry paint, and no option is read on that path")
                    .isEqualTo(BLANK_OPTION);
            return;
        }
        if (!input.isCommareaPresent()
                || !PfKeyResolver.isAid(input.eibAid(), CicsAid.DFHENTER)) {
            assertThat(outcome.option())
                    .describedAs("PROCESS-ENTER-KEY did not run on this path, so line 125 never wrote "
                            + "OPTIONO and the field must still hold the re-supplied image")
                    .isEqualTo(asDelivered);
            return;
        }

        String expectedEcho = normalisedOption(asDelivered);
        assertThat(outcome.option())
                .describedAs("line 125 MOVE WS-OPTION TO OPTIONO echoes the normalised option, so "
                        + "'%s' becomes '%s'", asDelivered, expectedEcho)
                .isEqualTo(expectedEcho);

        var normalisation = service.normaliseOption(input.option());
        assertThat(normalisation.optionEcho()).isEqualTo(expectedEcho);
        assertThat(normalisation.receivedOptionI()).isEqualTo(asDelivered);
        assertThat(normalisation.justifiedOptionX())
                .describedAs("WS-OPTION-X is PIC X(02) JUST RIGHT at line 45, so the sender lands at "
                        + "the rightmost position and the field is space-filled on the LEFT")
                .isEqualTo(justifiedOption(asDelivered));
    }

    private static void assertEvaluateArm(AdminMenuInput input, AdminMenuOutcome outcome) {
        if (!input.isCommareaPresent() || !input.isReenter()) {
            return;
        }
        byte eibAid = input.eibAid();
        if (PfKeyResolver.isAid(eibAid, CicsAid.DFHENTER)) {
            assertThat(outcome.message())
                    .describedAs("the DFHENTER arm performs PROCESS-ENTER-KEY, which never produces "
                            + "the invalid-key text")
                    .isNotEqualTo(picX(CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH));
            return;
        }
        if (PfKeyResolver.isAid(eibAid, CicsAid.DFHPF3)) {
            assertThat(outcome.nextProgram())
                    .describedAs("line 97 moves 'COSGN00C' into CDEMO-TO-PROGRAM and lines 165-167 "
                            + "transfer to it")
                    .isEqualTo(picX(SIGNON_PROGRAM, NavigationContext.TO_PROGRAM_LENGTH));
            assertThat(outcome.nextProgramCarriesCommarea())
                    .describedAs("the XCTL at 165-167 specifies NO COMMAREA, unlike the one at 142-145")
                    .isFalse();
            return;
        }
        assertThat(outcome.errFlgImage())
                .describedAs("line 100 MOVE 'Y' TO WS-ERR-FLG on the WHEN OTHER arm")
                .isEqualTo(AdminMenuService.ERR_FLG_ON);
        assertThat(outcome.message())
                .describedAs("line 101 moves CCDA-MSG-INVALID-KEY, a PIC X(50) sender, into the "
                        + "PIC X(80) WS-MESSAGE - left-justified, space-padded to eighty")
                .isEqualTo(picX(CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH));
        assertThat(outcome.screenPainted())
                .describedAs("line 102 performs SEND-MENU-SCREEN")
                .isTrue();
    }

    @Test
    @DisplayName("reproduces the four-step option normalisation over its five worked inputs")
    void theOptionNormalisationReproducesAllFourSteps() {
        AdminMenuService service = service(ParityHarness.usAscii().codec());

        record Worked(String received, int wsIdx, String justified, String optionX, boolean numeric) {
        }
        List<Worked> worked = List.of(
                new Worked("  ", 1, "  ", "00", true),
                new Worked(" 3", 2, " 3", "03", true),
                new Worked("3 ", 1, " 3", "03", true),
                new Worked("12", 2, "12", "12", true),
                new Worked("1x", 2, "1x", "1x", false));

        for (Worked row : worked) {
            var normalisation = service.normaliseOption(row.received());
            assertThat(normalisation.wsIdx())
                    .describedAs("WS-IDX after the descending scan over '%s'", row.received())
                    .isEqualTo(row.wsIdx());
            assertThat(normalisation.justifiedOptionX())
                    .describedAs("WS-OPTION-X after the JUST RIGHT move of '%s'", row.received())
                    .isEqualTo(row.justified())
                    .isEqualTo(justifiedOption(picX(row.received(), OPTION_LENGTH)));
            assertThat(normalisation.optionX())
                    .describedAs("WS-OPTION-X after INSPECT REPLACING ALL ' ' BY '0' over '%s'",
                            row.received())
                    .isEqualTo(row.optionX())
                    .isEqualTo(normalisedOption(picX(row.received(), OPTION_LENGTH)));
            assertThat(normalisation.isNumeric())
                    .describedAs("WS-OPTION IS NOT NUMERIC at line 127, for '%s'", row.received())
                    .isEqualTo(row.numeric());
        }
    }

    @Test
    @DisplayName("routes DFHENTER and DFHPF3 to their own arms and every other AID to WHEN OTHER")
    void theEvaluateEibaidArmsAreOrderedAndExhaustive() {
        AdminMenuService service = service(ParityHarness.usAscii().codec());
        NavigationContext reentered = NavigationContext.empty().withPgmReenter();
        String invalidKeyImage = picX(CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH);
        int otherArmCount = 0;

        for (Map.Entry<String, Byte> aid : AID_BY_MNEMONIC.entrySet()) {
            byte eibAid = aid.getValue();
            AdminMenuOutcome outcome =
                    service.handle(new AdminMenuInput(reentered, eibAid, BLANK_OPTION));

            if (eibAid == CicsAid.DFHENTER) {
                assertThat(outcome.message())
                        .describedAs("%s must reach PROCESS-ENTER-KEY, which rejects a blank option "
                                + "with its own message", aid.getKey())
                        .isEqualTo(picX(INVALID_OPTION_MESSAGE, WS_MESSAGE_LENGTH));
            } else if (eibAid == CicsAid.DFHPF3) {
                assertThat(outcome.nextProgram())
                        .describedAs("%s must exit to the sign-on screen", aid.getKey())
                        .isEqualTo(picX(SIGNON_PROGRAM, NavigationContext.TO_PROGRAM_LENGTH));
            } else {
                otherArmCount++;
                assertThat(outcome.message())
                        .describedAs("%s has no arm of its own, so WHEN OTHER must catch it - even "
                                + "though PfKeyResolver resolves it to %s", aid.getKey(),
                                PfKeyResolver.resolve(eibAid))
                        .isEqualTo(invalidKeyImage);
                assertThat(outcome.errFlgImage()).isEqualTo(AdminMenuService.ERR_FLG_ON);
            }
        }

        assertThat(otherArmCount)
                .describedAs("WHEN OTHER must catch every AID but the two named ones; a count of zero "
                        + "would mean this walk drove nothing")
                .isEqualTo(AID_BY_MNEMONIC.size() - 2);
    }

    private static void assertTruncationKeptTheLeadingBytes(String errMsg) {
        List<String> leading = new ArrayList<>();
        List<String> trailing = new ArrayList<>();
        for (String wsMessage : PRODUCIBLE_WS_MESSAGES) {
            leading.add(wsMessage.substring(0, ERRMSG_LENGTH));
            if (!wsMessage.isBlank()) {
                trailing.add(wsMessage.substring(WS_MESSAGE_LENGTH - ERRMSG_LENGTH));
            }
        }

        assertThat(leading)
                .describedAs("ERRMSGO must be the LEADING 78 characters of one of the four 80-byte "
                        + "images COADM01C can put in WS-MESSAGE")
                .contains(errMsg);
        if (!errMsg.isBlank()) {
            assertThat(trailing)
                    .describedAs("a receiver filled from the right would hold the TRAILING 78 "
                            + "characters, shifting every character two places")
                    .doesNotContain(errMsg);
        }
    }

    private static void assertNoDataAccessCollaborator(AdminMenuService service) {
        for (Field field : service.getClass().getDeclaredFields()) {
            String type = field.getType().getName();
            for (String forbidden : DATA_ACCESS_TYPE_MARKERS) {
                assertThat(type.toLowerCase(Locale.ROOT))
                        .describedAs("field '%s' is a %s, but COADM01C performs no dataset access at "
                                + "all - it declares no SELECT, no FD and no file-handling EXEC CICS "
                                + "command", field.getName(), type)
                        .doesNotContain(forbidden);
            }
        }
    }

    private static void assertNoMutableState(AdminMenuService service) {
        for (Field field : service.getClass().getDeclaredFields()) {
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .describedAs("field '%s' is not final; COBOL WORKING-STORAGE is per-task and must "
                            + "never become assignable state on a shared bean", field.getName())
                    .isTrue();
            assertThat(field.getType().isArray())
                    .describedAs("field '%s' is an array, whose elements stay writable however final "
                            + "the reference is", field.getName())
                    .isFalse();
        }
    }

    private static AdminMenuService service(FixedWidthCodec codec) {
        return new AdminMenuService(usrSecBindings(), codec);
    }

    private static DatasetBindings usrSecBindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(USRSEC_DATASET_KEY, new DatasetBinding(USRSEC_TEST_DSNAME,
                DatasetBinding.KSDS,
                false,
                USRSEC_RECORD_FORMAT,
                null,
                SecUserRecord.RECORD_LENGTH,
                USRSEC_COPYBOOK,
                SecUserRecord.KEY_LENGTH,
                null,
                null,
                null));
        return catalogue;
    }

    private static AdminMenuInput inputOf(Invocation invocation) {
        byte eibAid = aidByte(invocation);
        String option = invocation.mapFields().getOrDefault(OPTION_INPUT_FIELD, BLANK_OPTION);
        NavigationContext context = commareaContext(invocation);
        return context == null
                ? AdminMenuInput.withoutCommarea(eibAid, option)
                : new AdminMenuInput(context, eibAid, option);
    }

    private static AdminMenuRequest requestOf(Invocation invocation) {
        return new AdminMenuRequest(spaces(TRANSACTION_ID.length()),
                spaces(CCDA_TITLE01.length()),
                spaces(CURDATE_LENGTH),
                spaces(PROGRAM.length()),
                spaces(CCDA_TITLE02.length()),
                spaces(CURTIME_LENGTH),
                BLANK_MENU_LINE, BLANK_MENU_LINE, BLANK_MENU_LINE, BLANK_MENU_LINE,
                BLANK_MENU_LINE, BLANK_MENU_LINE, BLANK_MENU_LINE, BLANK_MENU_LINE,
                BLANK_MENU_LINE, BLANK_MENU_LINE, BLANK_MENU_LINE, BLANK_MENU_LINE,
                invocation.mapFields().getOrDefault(OPTION_INPUT_FIELD, BLANK_OPTION),
                BLANK_ERRMSG,
                commareaContext(invocation),
                aidByte(invocation));
    }

    private static NavigationContext commareaContext(Invocation invocation) {
        if (invocation.eibcalen() == 0) {
            return null;
        }
        FixedWidthCodec codec = invocation.codec();
        return NavigationContext.fromFixedWidth(codec,
                codec.serialise(NavigationContext.LAYOUT, invocation.commarea()));
    }

    private static String declaredToProgramImage(Invocation invocation) {
        String declared = invocation.commarea().get(NavigationContext.TO_PROGRAM_FIELD);
        return declared == null ? spaces(NavigationContext.TO_PROGRAM_LENGTH) : declared;
    }

    private static Map<String, String> navigationImages(FixedWidthCodec codec,
            NavigationContext context) {
        if (context == null) {
            return Map.of();
        }
        return codec.deserialise(NavigationContext.LAYOUT, context.toFixedWidth(codec));
    }

    private static byte aidByte(Invocation invocation) {
        String mnemonic = invocation.aid();
        if (mnemonic == null) {
            assertThat(invocation.eibcalen())
                    .describedAs("only the EIBCALEN = 0 path may omit the AID; every other path "
                            + "reaches the EVALUATE at line 93 and the byte it compares is behaviour")
                    .isZero();
            return CicsAid.DFHENTER;
        }
        Byte eibAid = AID_BY_MNEMONIC.get(mnemonic);
        assertThat(eibAid)
                .describedAs("'%s' is not a DFHAID mnemonic; common.CicsAid is the single reproduction "
                        + "of that IBM-supplied copybook, which is absent from this repository",
                        mnemonic)
                .isNotNull();
        return eibAid;
    }

    private static String named(String image) {
        return isBlank(image) ? null : image.trim();
    }

    private static boolean isBlank(String image) {
        return image == null || image.isBlank();
    }

    private static String menuLineField(int cobolSubscript) {
        return String.format(Locale.ROOT, "OPTN%03dO", cobolSubscript);
    }

    private static String picX(String source, int width) {
        return source.length() >= width
                ? source.substring(0, width)
                : source + spaces(width - source.length());
    }

    private static String justifiedOption(String optionI) {
        int wsIdx = OPTION_LENGTH;
        while (optionI.charAt(wsIdx - 1) == ' ' && wsIdx != 1) {
            wsIdx--;
        }
        String sent = optionI.substring(0, wsIdx);
        return spaces(OPTION_LENGTH - sent.length()) + sent;
    }

    private static String normalisedOption(String optionI) {
        return justifiedOption(optionI).replace(' ', '0');
    }

    private static String spaces(int width) {
        return " ".repeat(width);
    }

    private static List<String> observableStrings(AdminMenuOutcome outcome) {
        List<String> images = new ArrayList<>();
        for (int subscript = 1; subscript <= MENU_LINE_COUNT; subscript++) {
            images.add(outcome.optionLine(subscript));
        }
        images.add(outcome.message());
        images.add(outcome.option());
        images.add(outcome.nextProgram());
        images.add(outcome.transactionId());
        images.add(outcome.mapsetName());
        images.add(outcome.mapName());
        return List.copyOf(images);
    }

    private static List<String> paintedMenuLines() {
        List<String> lines = new ArrayList<>(MENU_LINE_COUNT);
        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            lines.add(picX(String.format(Locale.ROOT, "%02d", subscript) + ". "
                    + OPTION_NAMES.get(subscript - 1), MENU_LINE_LENGTH));
        }
        while (lines.size() < MENU_LINE_COUNT) {
            lines.add(BLANK_MENU_LINE);
        }
        return List.copyOf(lines);
    }

    private static List<Optional<AdminMenuOption>> copybookSlots() {
        List<Optional<AdminMenuOption>> slots = new ArrayList<>(OCCURS_TABLE_SIZE);
        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            slots.add(Optional.of(AdminMenuOption.of(subscript, OPTION_NAMES.get(subscript - 1),
                    OPTION_PROGRAMS.get(subscript - 1))));
        }
        while (slots.size() < OCCURS_TABLE_SIZE) {
            slots.add(Optional.empty());
        }
        return slots;
    }

    private static AdminMenuOptionTable rotatedByOne() {
        List<Optional<AdminMenuOption>> slots = new ArrayList<>(OCCURS_TABLE_SIZE);
        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            int rotated = subscript % ACTIVE_OPTION_COUNT + 1;
            slots.add(Optional.of(AdminMenuOption.of(rotated, OPTION_NAMES.get(rotated - 1),
                    OPTION_PROGRAMS.get(rotated - 1))));
        }
        while (slots.size() < OCCURS_TABLE_SIZE) {
            slots.add(Optional.empty());
        }
        return new AdminMenuOptionTable(slots, ACTIVE_OPTION_COUNT);
    }

    private static AdminMenuOptionTable truncatedByOne() {
        return new AdminMenuOptionTable(copybookSlots(), ACTIVE_OPTION_COUNT - 1);
    }

    private static AdminMenuOptionTable singleEntryTable(AdminMenuOption entry) {
        List<Optional<AdminMenuOption>> slots = new ArrayList<>(OCCURS_TABLE_SIZE);
        slots.add(Optional.of(entry));
        while (slots.size() < OCCURS_TABLE_SIZE) {
            slots.add(Optional.empty());
        }
        return new AdminMenuOptionTable(slots, 1);
    }

    private static Map<String, Byte> aidByMnemonic() {
        Map<String, Byte> inverted = new LinkedHashMap<>();
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            inverted.put(entry.getValue(), entry.getKey());
        }
        return Collections.unmodifiableMap(inverted);
    }
}
