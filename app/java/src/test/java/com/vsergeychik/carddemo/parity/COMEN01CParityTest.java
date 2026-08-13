package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.vsergeychik.carddemo.admin.MainMenuController;
import com.vsergeychik.carddemo.admin.MainMenuService;
import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuInput;
import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuOptionTable;
import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuOutcome;
import com.vsergeychik.carddemo.admin.dto.MainMenuRequest;
import com.vsergeychik.carddemo.admin.dto.MainMenuResponse;
import com.vsergeychik.carddemo.admin.model.MenuOptions;
import com.vsergeychik.carddemo.admin.model.MenuOptions.MenuOption;
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
import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The twenty-case parity gate for {@code app/cbl/COMEN01C.cbl} - the CardDemo main menu for regular users,
 * CSD transaction {@code CM00}, mapset {@code COMEN01}, map {@code COMEN1A}, projected as
 * {@code GET /api/menu}.
 */
@DisplayName("COMEN01C parity gate - main menu, CSD transaction CM00, GET /api/menu")
class COMEN01CParityTest {
    private static final String PROGRAM = "COMEN01C";

    private static final String TRANSACTION_ID = "CM00";

    private static final String MAPSET_NAME = "COMEN01";

    private static final String MAP_NAME = "COMEN1A";

    private static final String SIGNON_PROGRAM = "COSGN00C";

    private static final int ACTIVE_OPTION_COUNT = 10;

    private static final int OCCURS_TABLE_SIZE = 12;

    private static final int MENU_LINE_COUNT = 12;

    private static final int MENU_LINE_LENGTH = 40;

    private static final int OPTION_NAME_LENGTH = 35;

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
            "Account View                       ",
            "Account Update                     ",
            "Credit Card List                   ",
            "Credit Card View                   ",
            "Credit Card Update                 ",
            "Transaction List                   ",
            "Transaction View                   ",
            "Transaction Add                    ",
            "Transaction Reports                ",
            "Bill Payment                       ");

    private static final List<String> OPTION_PROGRAMS = List.of(
            "COACTVWC",
            "COACTUPC",
            "COCRDLIC",
            "COCRDSLC",
            "COCRDUPC",
            "COTRN00C",
            "COTRN01C",
            "COTRN02C",
            "CORPT00C",
            "COBIL00C");

    private static final String REGULAR_USER_USRTYPE = "U";

    private static final String ADMIN_ONLY_USRTYPE = "A";

    private static final String CCDA_TITLE01 = "      AWS Mainframe Modernization       ";

    private static final String CCDA_TITLE02 = "              CardDemo                  ";

    private static final String CCDA_MSG_INVALID_KEY =
            "Invalid key pressed. Please see below..." + "          ";

    private static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    private static final String NO_ACCESS_MESSAGE = "No access - Admin Only option... ";

    private static final String COMING_SOON_MESSAGE = "This option " + "Account" + "is coming soon ...";

    private static final String GREEN_MNEMONIC = "DFHGREEN";

    private static final String RED_MNEMONIC = "DFHRED";

    private static final int COMMAREA_LENGTH = 160;

    private static final String OPTION_NUMBER_SEPARATOR = ". ";

    private static final String DUMMY_PROGRAM_PREFIX = "DUMMY";

    private static final String DUMMY_PROGRAM_NAME = "DUMMY001";

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

    private static final List<String> PAINTED_MENU_LINES = paintedMenuLines(ACTIVE_OPTION_COUNT);

    private static final List<String> STUB_MENU_LINES = paintedMenuLines(1);

    private static final List<String> PRODUCIBLE_WS_MESSAGES = List.of(
            BLANK_WS_MESSAGE,
            picX(INVALID_OPTION_MESSAGE, WS_MESSAGE_LENGTH),
            picX(NO_ACCESS_MESSAGE, WS_MESSAGE_LENGTH),
            picX(CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH),
            picX(COMING_SOON_MESSAGE, WS_MESSAGE_LENGTH));

    private static final List<String> DATA_ACCESS_TYPE_MARKERS =
            List.of("repository", "jdbctemplate", "datasource", "connection", "entitymanager");

    private static final Map<String, Byte> AID_BY_MNEMONIC = aidByMnemonic();

    private static final byte UNRESOLVABLE_AID = (byte) 0x07;

    private static final String MENU_TABLE_VARIANT_KEY = "MENU_TABLE_VARIANT";

    private static final String ADMIN_ONLY_FIRST_ENTRY_VARIANT = "ADMIN_ONLY_FIRST_ENTRY";

    private static final String ADMIN_ONLY_SLOT_THREE_VARIANT = "ADMIN_ONLY_SLOT_THREE";

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
    @DisplayName("declares exactly twenty cases, case01 to case20, all naming COMEN01C")
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
    @DisplayName("declares SERVICE for seventeen cases and CONTROLLER_POJO for case01, case16, case20")
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
                .containsExactly("case01", "case16", "case20");
        assertThat(services)
                .describedAs("the filter cases must be SERVICE cases, because the filter is the "
                        + "service's job and the controller cannot reach it")
                .hasSize(ParityHarness.CASES_PER_PROGRAM - controllers.size());
        assertThat(services.size() + controllers.size())
                .isEqualTo(ParityHarness.CASES_PER_PROGRAM);
    }

    @Test
    @DisplayName("declares both admin-only table variants, on cases whose inputs reach their arms")
    void theCaseSetDeclaresBothOptionTableVariants() {
        Map<String, List<String>> byVariant = new LinkedHashMap<>();
        for (ParityCase parityCase : cases()) {
            parityCase.unitStimulus().environmentValue(MENU_TABLE_VARIANT_KEY).ifPresent(variant -> {
                assertThat(variant)
                        .describedAs("%s declares a variant no adapter arm implements", parityCase.caseId())
                        .isIn(ADMIN_ONLY_FIRST_ENTRY_VARIANT, ADMIN_ONLY_SLOT_THREE_VARIANT);
                byVariant.computeIfAbsent(variant, key -> new ArrayList<>()).add(parityCase.caseId());
                assertThat(parityCase.unitKind())
                        .describedAs("%s declares an option table, and only a SERVICE run is handed one",
                                parityCase.caseId())
                        .isEqualTo(UnitKind.SERVICE);
                assertThat(parityCase.screenRequest().mapFields().get(OPTION_INPUT_FIELD))
                        .describedAs("%s declares an option table to drive the filter, and the filter "
                                + "needs an entered option to subscript with", parityCase.caseId())
                        .isNotNull();
            });
        }

        assertThat(byVariant.get(ADMIN_ONLY_FIRST_ENTRY_VARIANT))
                .describedAs("the refusal and the administrator isolation both run over this variant, "
                        + "and they are a controlled comparison: one byte of CDEMO-USER-TYPE apart")
                .isNotNull()
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(byVariant.get(ADMIN_ONLY_SLOT_THREE_VARIANT))
                .describedAs("and the ungated filter after a failed validation runs over this one")
                .isNotNull()
                .isNotEmpty();
    }

    @Test
    @DisplayName("declares a CDEMO-USER-TYPE satisfying neither 88, so the pass-through is not vacuous")
    void theCaseSetDeclaresAUserTypeSatisfyingNeitherCondition() {
        List<String> neither = new ArrayList<>();
        for (ParityCase parityCase : cases()) {
            String declared = parityCase.screenRequest().commarea().get(NavigationContext.USER_TYPE_FIELD);
            if (declared != null && !ADMIN_ONLY_USRTYPE.equals(declared)
                    && !REGULAR_USER_USRTYPE.equals(declared)) {
                neither.add(parityCase.caseId());
            }
        }

        assertThat(neither)
                .describedAs("no case declares a %s outside {'%s','%s'}, so nothing proves the field is "
                        + "inbound only: a translation that defaulted it from SEC-USR-TYPE would pass "
                        + "every case in the directory", NavigationContext.USER_TYPE_FIELD,
                        ADMIN_ONLY_USRTYPE, REGULAR_USER_USRTYPE)
                .isNotEmpty();
    }

    @Test
    @DisplayName("declares no write, no final state and no dataset expectation in any case")
    void noCaseDeclaresADatasetExpectation() {
        for (ParityCase parityCase : cases()) {
            assertThat(parityCase.expectedWrites())
                    .describedAs("%s expects a write, but COMEN01C has no WRITE, REWRITE or DELETE "
                            + "anywhere in its 282 lines", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedFinalState())
                    .describedAs("%s expects a final dataset state, but COMEN01C opens no dataset",
                            parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedDatasets())
                    .describedAs("%s expects a dataset, but COMEN01C creates none", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.jobParameters())
                    .describedAs("%s declares a job parameter, but COMEN01C is an online program "
                            + "reached by transaction CM00 and not by EXEC PGM=", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.screenRequest().forcedOutcomes())
                    .describedAs("%s forces a repository outcome, but COMEN01C has no repository call "
                            + "site to force one at", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedResponse().cursorField())
                    .describedAs("%s expects a cursor, but COMEN01C never moves -1 into an xxxL item",
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
                                + "app/cbl/COMEN01C.cbl:175-177 can produce; a RETURN always carries the area",
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
                            .describedAs("%s pins '%s', which is not one of the twenty labelled DFHMDF "
                                    + "fields of mapset COMEN01", parityCase.caseId(), field.getKey())
                            .containsKey(field.getKey());
                    assertThat(field.getValue().length())
                            .describedAs("%s pins %s at %d character(s)", parityCase.caseId(),
                                    field.getKey(), field.getValue().length())
                            .isEqualTo(screenWidths.get(field.getKey()));
                }
                assertThat(send.attributes())
                        .describedAs("%s: ERRMSGC is the only attribute item COMEN01C ever writes, at "
                                + "line 158", parityCase.caseId())
                        .containsOnlyKeys(ERRMSG_COLOUR_FIELD);
                assertThat(send.attributes().get(ERRMSG_COLOUR_FIELD))
                        .describedAs("%s: the map declares COLOR=RED and only line 158 overrides it",
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
        assertThat(MainMenuService.PROGRAM_NAME).isEqualTo(PROGRAM);
        assertThat(MainMenuService.TRANSACTION_ID).isEqualTo(TRANSACTION_ID);
        assertThat(MainMenuService.MAPSET_NAME).isEqualTo(MAPSET_NAME);
        assertThat(MainMenuService.MAP_NAME).isEqualTo(MAP_NAME);
        assertThat(MainMenuService.SIGNON_PROGRAM).isEqualTo(SIGNON_PROGRAM);
        assertThat(MainMenuService.MESSAGE_LENGTH).isEqualTo(WS_MESSAGE_LENGTH);
        assertThat(MainMenuService.OPTION_TEXT_LENGTH).isEqualTo(MENU_LINE_LENGTH);
        assertThat(MainMenuService.OPTION_LINE_COUNT).isEqualTo(MENU_LINE_COUNT);
        assertThat(MainMenuService.OPTION_LENGTH).isEqualTo(OPTION_LENGTH);
        assertThat(MainMenuService.USRSEC_FILE_NAME_LENGTH).isEqualTo(USRSEC_FILE_NAME_LENGTH);
        assertThat(MainMenuService.INVALID_OPTION_MESSAGE).isEqualTo(INVALID_OPTION_MESSAGE);
        assertThat(MainMenuService.NO_ACCESS_MESSAGE).isEqualTo(NO_ACCESS_MESSAGE);
        assertThat(MainMenuService.OPTION_NUMBER_SEPARATOR).isEqualTo(OPTION_NUMBER_SEPARATOR);
        assertThat(MainMenuService.DUMMY_PROGRAM_PREFIX).isEqualTo(DUMMY_PROGRAM_PREFIX);
        assertThat(MainMenuService.ADMIN_ONLY_USRTYPE).isEqualTo(ADMIN_ONLY_USRTYPE);
        assertThat(MainMenuService.COMING_SOON_PREFIX + "Account" + MainMenuService.COMING_SOON_SUFFIX)
                .describedAs("the two live STRING operands, joined the way lines 159 to 163 join them "
                        + "around the space-delimited first word of CDEMO-MENU-OPT-NAME(1)")
                .isEqualTo(COMING_SOON_MESSAGE);

        assertThat(MenuOptions.ACTIVE_OPTION_COUNT).isEqualTo(ACTIVE_OPTION_COUNT);
        assertThat(MenuOptions.TABLE_SIZE).isEqualTo(OCCURS_TABLE_SIZE);
        assertThat(MenuOptions.OPT_NAME_LENGTH).isEqualTo(OPTION_NAME_LENGTH);
        assertThat(MenuOptions.OPT_PGMNAME_LENGTH).isEqualTo(OPTION_PROGRAMS.get(0).length());
        assertThat(MenuOptions.OPT_USRTYPE_LENGTH).isEqualTo(ADMIN_ONLY_USRTYPE.length());
        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            MenuOption entry = MenuOptions.optionBySubscript(subscript).orElseThrow();
            assertThat(entry.menuOptName())
                    .describedAs("CDEMO-MENU-OPT-NAME(%d)", subscript)
                    .isEqualTo(OPTION_NAMES.get(subscript - 1));
            assertThat(entry.menuOptPgmName())
                    .describedAs("CDEMO-MENU-OPT-PGMNAME(%d)", subscript)
                    .isEqualTo(OPTION_PROGRAMS.get(subscript - 1));
            assertThat(entry.menuOptUsrType())
                    .describedAs("CDEMO-MENU-OPT-USRTYPE(%d) - all ten are 'U', which is why the "
                            + "filter's true arm needs a stub table", subscript)
                    .isEqualTo(REGULAR_USER_USRTYPE)
                    .isNotEqualTo(ADMIN_ONLY_USRTYPE);
        }
        assertThat(MenuOptions.options())
                .describedAs("OCCURS 12 with ten valued entries: slots 11 and 12 are "
                        + "present-but-unvalued and are never trimmed away")
                .hasSize(OCCURS_TABLE_SIZE);
        assertThat(MenuOptions.options().subList(ACTIVE_OPTION_COUNT, OCCURS_TABLE_SIZE))
                .allSatisfy(slot -> assertThat(slot).isEmpty());

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
        assertThat(MainMenuService.MAP_MESSAGE_COLOUR).isEqualTo(BmsAttributes.DFHRED);
        assertThat(MainMenuService.COMING_SOON_MESSAGE_COLOUR).isEqualTo(BmsAttributes.DFHGREEN);

        assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(COMMAREA_LENGTH);
        assertThat(NavigationContext.USER_TYPE_USER).isEqualTo(REGULAR_USER_USRTYPE);
        assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(80);
    }

    @Test
    @DisplayName("fails its own menu expectations when the OCCURS base is shifted by one")
    void shiftingTheOccursBaseBreaksTheMenuExpectations() {
        MainMenuService service = service(ParityHarness.usAscii().codec());

        List<String> correct = service.buildMenuOptions(MainMenuOptionTable.copybook());
        assertThat(correct)
                .describedAs("the copybook table must compose exactly the ten lines this class "
                        + "transcribed from app/cpy/COMEN02Y.cpy, followed by two blanks")
                .isEqualTo(PAINTED_MENU_LINES);
        assertThat(correct.get(0))
                .describedAs("subscript 1 is the FIRST entry, not the second")
                .startsWith("01" + OPTION_NUMBER_SEPARATOR + "Account View");
        assertThat(correct.get(ACTIVE_OPTION_COUNT - 1))
                .describedAs("subscript 10 is the LAST valued entry")
                .startsWith("10" + OPTION_NUMBER_SEPARATOR + "Bill Payment");
        assertThat(correct.subList(ACTIVE_OPTION_COUNT, MENU_LINE_COUNT))
                .describedAs("OPTN011O and OPTN012O are present but blank: the EVALUATE at 248-275 has "
                        + "arms for twelve subscripts and the loop bound reaches only ten")
                .containsExactly(BLANK_MENU_LINE, BLANK_MENU_LINE);

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
                        + "would leave Bill Payment off the menu - which is what this proves would be "
                        + "visible")
                .isEqualTo(BLANK_MENU_LINE);
        assertThat(correct.get(ACTIVE_OPTION_COUNT - 1)).isNotEqualTo(BLANK_MENU_LINE);

        assertThatExceptionOfType(IndexOutOfBoundsException.class)
                .describedAs("COBOL has no subscript 0, so addressing one on the dispatch path must "
                        + "fail rather than silently return the first entry")
                .isThrownBy(() -> MainMenuOptionTable.copybook().optionBySubscript(0));
        assertThatExceptionOfType(IndexOutOfBoundsException.class)
                .describedAs("nor may subscript 13 resolve on a table declared OCCURS 12")
                .isThrownBy(() -> MainMenuOptionTable.copybook()
                        .optionBySubscript(OCCURS_TABLE_SIZE + 1));
    }

    @Test
    @DisplayName("composes the coming-soon message with no space before 'is', for every option")
    void theComingSoonCompositionHasNoSeparatorBeforeIs() {
        assertThat(COMING_SOON_MESSAGE)
                .describedAs("the composed text, thirty-seven characters, with 'Account' and 'is' run "
                        + "together exactly as the source leaves them")
                .isEqualTo("This option Accountis coming soon ...")
                .hasSize(37)
                .doesNotContain("Account is");
        assertThat(COMING_SOON_MESSAGE)
                .describedAs("inserting the 'missing' space would be a behaviour change, so the two "
                        + "images must differ; if they ever compare equal this proof has no teeth")
                .isNotEqualTo("This option Account" + " " + "is coming soon ...");

        MainMenuService service = service(ParityHarness.usAscii().codec());
        record Worked(int subscript, String surviving, int length) {
        }
        List<Worked> worked = List.of(
                new Worked(1, "Account", 37),
                new Worked(3, "Credit", 36),
                new Worked(6, "Transaction", 41),
                new Worked(10, "Bill", 34));
        for (Worked row : worked) {
            String expected = MainMenuService.COMING_SOON_PREFIX + row.surviving()
                    + MainMenuService.COMING_SOON_SUFFIX;
            MainMenuOptionTable dummyTable = dummyPrefixTableFor(row.subscript());
            MainMenuOutcome outcome = service.handle(
                    new MainMenuInput(userReentered(), CicsAid.DFHENTER,
                            String.format(Locale.ROOT, "%02d", row.subscript())),
                    dummyTable);

            assertThat(outcome.message())
                    .describedAs("option %d composes '%s'", row.subscript(), expected)
                    .isEqualTo(picX(expected, WS_MESSAGE_LENGTH));
            assertThat(expected.length())
                    .describedAs("option %d's composed text length", row.subscript())
                    .isEqualTo(row.length());
            assertThat(expected)
                    .describedAs("no option may acquire a separator before 'is'")
                    .doesNotContain(row.surviving() + " is");
            assertThat(outcome.messageColourOverridden())
                    .describedAs("line 158 MOVE DFHGREEN TO ERRMSGC applies on this path and no other")
                    .isTrue();

            MenuOption stubbed = dummyTable.optionBySubscript(row.subscript()).orElseThrow();
            assertThat(stubbed.menuOptPgmName())
                    .describedAs("line 146 reference-modifies the first five characters, so the prefix "
                            + "is what matters and the remaining three bytes are ordinary name "
                            + "characters")
                    .startsWith(DUMMY_PROGRAM_PREFIX)
                    .hasSize(OPTION_PROGRAMS.get(row.subscript() - 1).length());
            assertThat(stubbed.menuOptName())
                    .describedAs("the name is the copybook's own, so the composed line is unchanged")
                    .isEqualTo(OPTION_NAMES.get(row.subscript() - 1));
            assertThat(outcome.hasNextProgram())
                    .describedAs("line 146 found 'DUMMY', so the XCTL at 152-155 is skipped entirely")
                    .isFalse();
            assertThat(outcome.screenPainted())
                    .describedAs("lines 157-164 are reachable only here, and line 164 sends the map")
                    .isTrue();
            assertThat(outcome.errFlgImage())
                    .describedAs("WS-ERR-FLG is untouched on this path: a skipped transfer is a "
                            + "successful outcome, not a rejection")
                    .isEqualTo(MainMenuService.ERR_FLG_OFF);
            assertThat(outcome.optionLine(row.subscript()))
                    .describedAs("the injected entry differs from the copybook's only in its program "
                            + "name, so the composed line must be byte-identical to the real one")
                    .isEqualTo(PAINTED_MENU_LINES.get(row.subscript() - 1));
        }
    }

    private static ParityUnit unitFor(ParityCase parityCase) {
        return parityCase.unitKind() == UnitKind.CONTROLLER_POJO
                ? COMEN01CParityTest::runController
                : COMEN01CParityTest::runService;
    }

    private static UnitOutcome runService(Invocation invocation) {
        MainMenuService service = service(invocation.codec());
        MainMenuInput input = inputOf(invocation);
        MainMenuOptionTable declaredTable = declaredOptionTable(invocation);
        MainMenuOptionTable table = declaredTable == null
                ? MainMenuOptionTable.copybook()
                : declaredTable;
        Map<String, List<String>> seededBefore = snapshotOfSeededRows(invocation);

        MainMenuOutcome outcome = handle(service, input, declaredTable);

        assertServiceInvariants(service, input, outcome);
        assertDeadWorkingStorageStaysDead(service, outcome);
        assertStatelessAcrossThreeCalls(invocation, input, declaredTable, outcome);
        assertSeededDatasetsAreUntouched(invocation, seededBefore, service);

        OptionalInt normalised = service.normaliseOption(input.option()).option();
        if (normalised.isPresent() && normalised.getAsInt() > OCCURS_TABLE_SIZE) {
            assertOutOfRangeSubscriptIsTolerated(service, normalised.getAsInt(), outcome);
        }
        assertUserTypePassesThroughUnaltered(input, outcome);
        if (declaredTable != null) {
            assertAuthorisationFilterArm(service, input, table, normalised, outcome);
        }

        recordServiceOutcome(invocation, outcome);
        return null;
    }

    private static MainMenuOutcome handle(MainMenuService service, MainMenuInput input,
                                          MainMenuOptionTable declaredTable) {
        return declaredTable == null ? service.handle(input) : service.handle(input, declaredTable);
    }

    private static MainMenuOptionTable declaredOptionTable(Invocation invocation) {
        String variant = invocation.stimulus()
                .environmentValue(MENU_TABLE_VARIANT_KEY)
                .orElse(null);
        if (variant == null) {
            return null;
        }
        return switch (variant) {
            case ADMIN_ONLY_FIRST_ENTRY_VARIANT -> adminOnlyFirstEntryTable();
            case ADMIN_ONLY_SLOT_THREE_VARIANT -> adminOnlySlotThreeTable();
            default -> throw new IllegalArgumentException(invocation.caseId()
                    + " declares MENU_TABLE_VARIANT '" + variant + "', and the only variants " + PROGRAM
                    + " has arms for are '" + ADMIN_ONLY_FIRST_ENTRY_VARIANT + "' and '"
                    + ADMIN_ONLY_SLOT_THREE_VARIANT + "'. Honouring an unknown variant by running the "
                    + "copybook table would make the declaration do nothing in silence.");
        };
    }

    private static Map<String, List<String>> snapshotOfSeededRows(Invocation invocation) {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        invocation.datasets()
                .forEach((key, dataset) -> snapshot.put(key, List.copyOf(dataset.rows())));
        return snapshot;
    }

    private static void assertSeededDatasetsAreUntouched(Invocation invocation,
                                                         Map<String, List<String>> before,
                                                         MainMenuService service) {
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

    private static void assertDeadWorkingStorageStaysDead(MainMenuService service,
                                                          MainMenuOutcome outcome) {
        assertThat(service.secUserData())
                .describedAs("implementing line 150 would need SEC-USR-TYPE, and the record COPY "
                        + "CSUSR01Y brings in is never read: it stays exactly as initialised")
                .isEqualTo(SecUserRecord.blank());
        assertThat(service.usrSecFileName())
                .describedAs("WS-USRSEC-FILE VALUE 'USRSEC  ' - the logical file name padded to its "
                        + "declared PIC X(08), with the source literal's two trailing spaces intact")
                .isEqualTo(USRSEC_FILE_NAME)
                .hasSize(USRSEC_FILE_NAME_LENGTH);
        assertThat(observableStrings(outcome))
                .describedAs("the dead declaration must not leak onto the screen: WS-USRSEC-FILE is "
                        + "never moved anywhere, so its value must appear in no field of the response")
                .noneMatch(image -> image.contains(USRSEC_DATASET_KEY));
    }

    private static void assertStatelessAcrossThreeCalls(Invocation invocation, MainMenuInput input,
                                                        MainMenuOptionTable declaredTable,
                                                        MainMenuOutcome first) {
        MainMenuService shared = service(invocation.codec());
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

    private static void assertOutOfRangeSubscriptIsTolerated(MainMenuService service, int wsOption,
                                                            MainMenuOutcome outcome) {
        MainMenuOptionTable copybook = MainMenuOptionTable.copybook();
        for (int subscript : new int[] {0, OCCURS_TABLE_SIZE + 1, wsOption, Integer.MAX_VALUE}) {
            assertThatNoException()
                    .describedAs("the ungated filter must tolerate subscript %d: COBOL without SSRANGE "
                            + "reads stray bytes rather than failing", subscript)
                    .isThrownBy(() -> service.isAdminOnlyOption(copybook, OptionalInt.of(subscript)));
            assertThat(service.isAdminOnlyOption(copybook, OptionalInt.of(subscript)))
                    .describedAs("no byte an out-of-range read could see makes line 137 true on a path "
                            + "this program reaches, so subscript %d must evaluate false", subscript)
                    .isFalse();
        }
        assertThat(outcome.message())
                .describedAs("line 133 painted the option rejection and the filter added nothing, so "
                        + "the No access text must NOT be on the screen")
                .isEqualTo(picX(INVALID_OPTION_MESSAGE, WS_MESSAGE_LENGTH))
                .isNotEqualTo(picX(NO_ACCESS_MESSAGE, WS_MESSAGE_LENGTH));
        assertThat(outcome.errorFlag())
                .describedAs("line 130 MOVE 'Y' TO WS-ERR-FLG, which then suppresses the dispatch")
                .isTrue();
    }

    private static void assertUserTypePassesThroughUnaltered(MainMenuInput input,
                                                             MainMenuOutcome outcome) {
        if (input.navigationContext() == null) {
            return;
        }
        assertThat(outcome.navigationContext().userType())
                .describedAs("line 150 is COMMENTED OUT, so CDEMO-USER-TYPE is inbound only and must "
                        + "cross the whole dispatch path unchanged")
                .isEqualTo(input.navigationContext().userType());
        assertThat(outcome.navigationContext().userId())
                .describedAs("line 149 is commented out too, so CDEMO-USER-ID is likewise untouched")
                .isEqualTo(input.navigationContext().userId());
    }

    private static void assertAuthorisationFilterArm(MainMenuService service, MainMenuInput input,
                                                     MainMenuOptionTable table,
                                                     OptionalInt normalised,
                                                     MainMenuOutcome outcome) {
        assertThat(normalised)
                .describedAs("a declared option table exists to drive the filter, and the filter needs "
                        + "a subscript; a blank option would never reach it")
                .isPresent();
        int wsOption = normalised.getAsInt();
        MenuOption injected = table.optionWithinTable(wsOption).orElseThrow();
        assertThat(injected.menuOptUsrType())
                .describedAs("slot %d must be a valued entry carrying 'A', so the filter at line 137 "
                        + "has something to find", wsOption)
                .isEqualTo(ADMIN_ONLY_USRTYPE);
        MenuOption copybook = MenuOptions.optionBySubscript(wsOption).orElseThrow();
        assertThat(injected.menuOptName())
                .describedAs("the authorisation column is the only thing the variant varies, which is "
                        + "what keeps the composed menu line byte-identical to the real one")
                .isEqualTo(copybook.menuOptName());
        assertThat(injected.menuOptPgmName()).isEqualTo(copybook.menuOptPgmName());
        assertThat(injected.menuOptUsrType()).isNotEqualTo(copybook.menuOptUsrType());
        assertThat(service.isAdminOnlyOption(table, OptionalInt.of(wsOption)))
                .describedAs("the filter's SECOND conjunct - the column at the entered subscript really "
                        + "is 'A' - holds for every variant run")
                .isTrue();

        if (wsOption > table.activeCount()) {
            assertThat(input.navigationContext().isUser())
                    .describedAs("both conjuncts must hold for line 142 to repaint, so the caller is a "
                            + "regular user")
                    .isTrue();
            assertThat(outcome.message())
                    .describedAs("the guard at line 128 rejected the option and painted at 133; "
                            + "PERFORM returned, line 136 found both conjuncts true, and line 142's "
                            + "paint overwrote it - so the No access text is what the terminal shows. "
                            + "Were the filter hoisted into the validation's ELSE, this would carry the "
                            + "option rejection instead")
                    .isEqualTo(picX(NO_ACCESS_MESSAGE, WS_MESSAGE_LENGTH))
                    .isNotEqualTo(picX(INVALID_OPTION_MESSAGE, WS_MESSAGE_LENGTH));
            assertThat(outcome.hasNextProgram())
                    .describedAs("line 145's IF NOT ERR-FLG-ON is false, so the dispatch is suppressed")
                    .isFalse();
            assertThat(outcome.optionLines())
                    .describedAs("BUILD-MENU-OPTIONS loops to the table's active count of %d, so only "
                            + "OPTN001O is composed and the other eleven lines are the spaces line 241 "
                            + "leaves", table.activeCount())
                    .isEqualTo(STUB_MENU_LINES);
            return;
        }

        if (input.navigationContext().isUser()) {
            assertThat(outcome.errorFlag())
                    .describedAs("line 138 SET ERR-FLG-ON TO TRUE - a SET here where line 130 used "
                            + "MOVE 'Y'")
                    .isTrue();
            assertThat(outcome.message())
                    .describedAs("line 140's literal carries a trailing space that padding to PIC X(80) "
                            + "makes invisible, and the emitted image is what is compared")
                    .isEqualTo(picX(NO_ACCESS_MESSAGE, WS_MESSAGE_LENGTH));
            assertThat(outcome.hasNextProgram())
                    .describedAs("the dispatch at 146-155 is suppressed by the flag")
                    .isFalse();
            assertThat(outcome.messageColourOverridden())
                    .describedAs("only the coming-soon path touches ERRMSGC, so a refusal stays red")
                    .isFalse();
            assertThat(outcome.optionLines())
                    .describedAs("the variant offers one option, so the paint composes OPTN001O alone - "
                            + "and that line is byte-identical to the copybook's first")
                    .isEqualTo(STUB_MENU_LINES);
            return;
        }

        assertThat(input.navigationContext().isAdmin())
                .describedAs("the remaining arm isolates the filter's first conjunct, so the caller "
                        + "must be an administrator")
                .isTrue();
        assertThat(outcome.errorFlag()).isFalse();
        assertThat(outcome.nextProgram())
                .describedAs("the dispatch at 152-155 transfers to the entry's own target program")
                .isEqualTo(picX(injected.menuOptPgmName(), NavigationContext.TO_PROGRAM_LENGTH));
        assertThat(outcome.nextProgramCarriesCommarea())
                .describedAs("line 154 specifies COMMAREA(CARDDEMO-COMMAREA), unlike the transfer at "
                        + "175-177")
                .isTrue();
    }

    private static UnitOutcome runController(Invocation invocation) {
        MainMenuService service = service(invocation.codec());
        MainMenuController controller = new MainMenuController(service, invocation.clock());

        var envelope =
                controller.getMainMenu(invocation.eibcalen() == 0 ? null : requestOf(invocation));
        MainMenuResponse screen = envelope.screen();
        var metadata = envelope.screenMetadata();

        assertThat(screen.trnName()).isEqualTo(TRANSACTION_ID);
        assertThat(screen.pgmName()).isEqualTo(PROGRAM);
        assertThat(screen.title01()).isEqualTo(CCDA_TITLE01);
        assertThat(screen.title02()).isEqualTo(CCDA_TITLE02);
        assertThat(screen.errMsg())
                .describedAs("MOVE WS-MESSAGE TO ERRMSGO at line 187 narrows PIC X(80) to PIC X(78)")
                .hasSize(ERRMSG_LENGTH);
        assertThat(metadata.resetAllOutputFields())
                .describedAs("MOVE LOW-VALUES TO COMEN1AO at line 89 runs on the first-entry path "
                        + "alone, and the flag must reach the client beside the screen rather than "
                        + "inside it, because it is not a DFHMDF field")
                .isEqualTo(screen.resetAllOutputFields());
        assertThat(metadata.messageColour())
                .describedAs("ERRMSGC travels as metadata, unsigned, because the attribute IBM "
                        + "documents as X'F2' is a negative Java byte")
                .isEqualTo(Byte.toUnsignedInt(screen.errMsgColor()));
        assertThat(metadata.cursorField())
                .describedAs("COMEN01C never moves -1 into an xxxL item, so no cursor is requested")
                .isNull();
        assertTruncationKeptTheLeadingBytes(screen.errMsg());

        boolean transferred = !isBlank(screen.nextProgram());
        List<ObservedSend> sends = transferred
                ? List.of()
                : List.of(new ObservedSend(controllerSendFields(screen), colour(screen.errMsgColor())));

        recordObservation(invocation,
                observedResponse(screen.nextProgram(), screen.nextMapset(), screen.nextMap(),
                        navigationImages(invocation.codec(), screen.navigationContext()), sends,
                        transferred),
                new EmittedMessage(MessageChannel.SCREEN_ERRMSG_78, screen.errMsg()));
        return null;
    }

    private static void recordServiceOutcome(Invocation invocation, MainMenuOutcome outcome) {
        boolean transferred = outcome.hasNextProgram();
        assertThat(transferred)
                .describedAs("exactly one of EXEC CICS XCTL and EXEC CICS SEND MAP happens on any one "
                        + "path through COMEN01C, so a run reporting both or neither has lost the shape "
                        + "of the program")
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

    private static Map<String, String> serviceSendFields(MainMenuOutcome outcome) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int subscript = 1; subscript <= MENU_LINE_COUNT; subscript++) {
            fields.put(menuLineField(subscript), outcome.optionLine(subscript));
        }
        fields.put(OPTION_FIELD, outcome.option());
        return fields;
    }

    private static Map<String, String> controllerSendFields(MainMenuResponse screen) {
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
                .describedAs("%s: COMEN01C has no WRITE, REWRITE or DELETE anywhere in its 282 lines, "
                        + "so nothing may appear on the writes channel", parityCase.caseId())
                .isEmpty();
        assertThat(fingerprint.finalState())
                .describedAs("%s: COMEN01C opens no dataset, so no dataset may appear on the final "
                        + "state channel either", parityCase.caseId())
                .isEmpty();
    }

    private static void assertReturnCodeIsNormal(ParityCase parityCase,
            DecodedFingerprint fingerprint) {
        assertThat(fingerprint.returnCode())
                .describedAs("%s: COMEN01C is an online program with no abend site and no RETURN-CODE "
                        + "of its own", parityCase.caseId())
                .isZero();
    }

    private static void assertServiceInvariants(MainMenuService service, MainMenuInput input,
            MainMenuOutcome outcome) {
        assertScreenIdentity(outcome);
        assertOptionEcho(service, input, outcome);
        assertEvaluateArm(input, outcome);
        assertMessageIsOneTheProgramCanProduce(outcome);
        assertGreenOverrideOnlyOnReenter(input, outcome);
    }

    private static void assertScreenIdentity(MainMenuOutcome outcome) {
        assertThat(outcome.transactionId())
                .describedAs("RETURN TRANSID(WS-TRANID) at line 108, and DEFINE TRANSACTION(CM00) "
                        + "PROGRAM(COMEN01C) in app/csd/CARDDEMO.CSD")
                .isEqualTo(TRANSACTION_ID);
        if (outcome.screenPainted()) {
            assertThat(outcome.mapsetName())
                    .describedAs("SEND MAP ... MAPSET('COMEN01') at line 191 ran, so the response names "
                            + "the mapset that was actually sent")
                    .isEqualTo(MAPSET_NAME);
            assertThat(outcome.mapName())
                    .describedAs("MAP('COMEN1A') at line 190 ran, so the response names the map that was "
                            + "actually sent")
                    .isEqualTo(MAP_NAME);
        } else {
            assertThat(outcome.mapsetName())
                    .describedAs("no SEND ran: neither EXEC CICS XCTL names a map - line 153 names a "
                            + "program and line 176 names a program - so a transfer must name no mapset "
                            + "either, and naming COMEN01 here would tell the client to paint the screen "
                            + "the program is leaving")
                    .isBlank();
            assertThat(outcome.mapName())
                    .describedAs("likewise no map on a transfer: which map the successor paints is the "
                            + "successor's decision, made after this program has ended")
                    .isBlank();
        }
        assertThat(outcome.navigationContext().lastMapset())
                .describedAs("CDEMO-LAST-MAPSET is a COMMAREA field and COMEN01C never writes it, so it "
                        + "stays exactly as it arrived - which is what distinguishes the program's own "
                        + "map identity from the conversation's record of it")
                .isBlank();
        assertThat(outcome.navigationContext().lastMap())
                .describedAs("CDEMO-LAST-MAP is likewise never written by this program")
                .isBlank();
    }

    private static void assertOptionEcho(MainMenuService service, MainMenuInput input,
            MainMenuOutcome outcome) {
        String asDelivered = picX(input.option(), OPTION_LENGTH);

        if (input.isCommareaPresent() && !input.isReenter()) {
            assertThat(outcome.option())
                    .describedAs("MOVE LOW-VALUES TO COMEN1AO at line 89 clears OPTIONO before the "
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
                .describedAs("line 125 MOVE WS-OPTION TO OPTIONO echoes the normalised option, so '%s' "
                        + "becomes '%s'", asDelivered, expectedEcho)
                .isEqualTo(expectedEcho);

        var normalisation = service.normaliseOption(input.option());
        assertThat(normalisation.optionEcho()).isEqualTo(expectedEcho);
        assertThat(normalisation.receivedOptionI()).isEqualTo(asDelivered);
        assertThat(normalisation.justifiedOptionX())
                .describedAs("WS-OPTION-X is PIC X(02) JUST RIGHT at line 45, so the sender lands at "
                        + "the rightmost position and the field is space-filled on the LEFT")
                .isEqualTo(justifiedOption(asDelivered));
    }

    private static void assertEvaluateArm(MainMenuInput input, MainMenuOutcome outcome) {
        if (!input.isCommareaPresent() || !input.isReenter()) {
            return;
        }
        byte eibAid = input.eibAid();
        if (PfKeyResolver.isAid(eibAid, CicsAid.DFHENTER)) {
            assertThat(outcome.message())
                    .describedAs("the DFHENTER arm performs PROCESS-ENTER-KEY, which never produces the "
                            + "invalid-key text")
                    .isNotEqualTo(picX(CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH));
            return;
        }
        if (PfKeyResolver.isAid(eibAid, CicsAid.DFHPF3)) {
            assertThat(outcome.nextProgram())
                    .describedAs("line 97 moves 'COSGN00C' into CDEMO-TO-PROGRAM and lines 175-177 "
                            + "transfer to it")
                    .isEqualTo(picX(SIGNON_PROGRAM, NavigationContext.TO_PROGRAM_LENGTH));
            assertThat(outcome.nextProgramCarriesCommarea())
                    .describedAs("the XCTL at 175-177 specifies NO COMMAREA, unlike the one at 152-155")
                    .isFalse();
            return;
        }
        assertThat(outcome.errFlgImage())
                .describedAs("line 100 MOVE 'Y' TO WS-ERR-FLG on the WHEN OTHER arm")
                .isEqualTo(MainMenuService.ERR_FLG_ON);
        assertThat(outcome.message())
                .describedAs("line 101 moves CCDA-MSG-INVALID-KEY, a PIC X(50) sender, into the "
                        + "PIC X(80) WS-MESSAGE - left-justified, space-padded to eighty")
                .isEqualTo(picX(CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH));
        assertThat(outcome.screenPainted())
                .describedAs("line 102 performs SEND-MENU-SCREEN")
                .isTrue();
    }

    private static void assertMessageIsOneTheProgramCanProduce(MainMenuOutcome outcome) {
        assertThat(outcome.message())
                .describedAs("WS-MESSAGE is written at lines 101, 131, 140 and 159-163 and nowhere "
                        + "else, so no other eighty-byte image is producible")
                .isIn(PRODUCIBLE_WS_MESSAGES)
                .hasSize(WS_MESSAGE_LENGTH);
    }

    @Test
    @DisplayName("reproduces the four-step option normalisation over its six worked inputs")
    void theOptionNormalisationReproducesAllFourSteps() {
        MainMenuService service = service(ParityHarness.usAscii().codec());

        record Worked(String received, int wsIdx, String justified, String optionX, boolean numeric) {
        }
        List<Worked> worked = List.of(
                new Worked("  ", 1, "  ", "00", true),
                new Worked(" 3", 2, " 3", "03", true),
                new Worked("3 ", 1, " 3", "03", true),
                new Worked("10", 2, "10", "10", true),
                new Worked("99", 2, "99", "99", true),
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
        MainMenuService service = service(ParityHarness.usAscii().codec());
        NavigationContext reentered = userReentered();
        String invalidKeyImage = picX(CCDA_MSG_INVALID_KEY, WS_MESSAGE_LENGTH);
        int otherArmCount = 0;

        for (Map.Entry<String, Byte> aid : AID_BY_MNEMONIC.entrySet()) {
            byte eibAid = aid.getValue();
            MainMenuOutcome outcome =
                    service.handle(new MainMenuInput(reentered, eibAid, BLANK_OPTION));

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
                assertThat(outcome.errFlgImage()).isEqualTo(MainMenuService.ERR_FLG_ON);
            }
        }

        assertThat(otherArmCount)
                .describedAs("WHEN OTHER must catch every AID but the two named ones; a count of zero "
                        + "would mean this walk drove nothing")
                .isEqualTo(AID_BY_MNEMONIC.size() - 2);

        assertThat(PfKeyResolver.resolve(UNRESOLVABLE_AID))
                .describedAs("the resolver must report an explicit no-match rather than substituting a "
                        + "default, which is what makes this byte a genuine unnamed AID")
                .isEmpty();
        assertThat(AID_BY_MNEMONIC.values())
                .describedAs("and the byte must genuinely be outside the DFHAID table")
                .doesNotContain(UNRESOLVABLE_AID);
        MainMenuOutcome unnamed =
                service.handle(new MainMenuInput(reentered, UNRESOLVABLE_AID, BLANK_OPTION));
        assertThat(unnamed.message())
                .describedAs("an AID with no mnemonic at all still lands on WHEN OTHER at lines 99-102")
                .isEqualTo(invalidKeyImage);
        assertThat(unnamed.errFlgImage()).isEqualTo(MainMenuService.ERR_FLG_ON);
        assertThat(unnamed.screenPainted()).isTrue();
    }

    private static void assertGreenOverrideOnlyOnReenter(MainMenuInput input,
            MainMenuOutcome outcome) {
        if (input.isReenter() && PfKeyResolver.isAid(input.eibAid(), CicsAid.DFHENTER)) {
            return;
        }
        assertThat(outcome.messageColourOverridden())
                .describedAs("line 158 is inside PROCESS-ENTER-KEY, which only the DFHENTER arm of a "
                        + "re-entry performs, so ERRMSGC must still hold the mapset's COLOR=RED here")
                .isFalse();
        assertThat(outcome.messageColour())
                .describedAs("and the byte itself must be the map's declared colour")
                .isEqualTo(MainMenuService.MAP_MESSAGE_COLOUR);
    }

    @Test
    @DisplayName("opens all ten copybook options to a regular user, refusing none")
    void everyCopybookOptionIsOpenToARegularUser() {
        MainMenuService service = service(ParityHarness.usAscii().codec());
        MainMenuOptionTable copybook = MainMenuOptionTable.copybook();

        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            String option = String.format(Locale.ROOT, "%02d", subscript);
            MainMenuOutcome outcome = service.handle(
                    new MainMenuInput(userReentered(), CicsAid.DFHENTER, option));

            assertThat(service.isAdminOnlyOption(copybook, OptionalInt.of(subscript)))
                    .describedAs("option %d's authorisation column is 'U', not 'A'", subscript)
                    .isFalse();
            assertThat(outcome.errorFlag())
                    .describedAs("lines 136-137 cannot fire for option %d", subscript)
                    .isFalse();
            assertThat(outcome.message())
                    .describedAs("no message at all is emitted on a successful transfer")
                    .isEqualTo(BLANK_WS_MESSAGE);
            assertThat(outcome.nextProgram())
                    .describedAs("option %d transfers to its own target program", subscript)
                    .isEqualTo(picX(OPTION_PROGRAMS.get(subscript - 1),
                            NavigationContext.TO_PROGRAM_LENGTH));
        }
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
                .describedAs("ERRMSGO must be the LEADING 78 characters of one of the five 80-byte "
                        + "images COMEN01C can put in WS-MESSAGE")
                .contains(errMsg);
        if (!errMsg.isBlank()) {
            assertThat(trailing)
                    .describedAs("a receiver filled from the right would hold the TRAILING 78 "
                            + "characters, shifting every character two places")
                    .doesNotContain(errMsg);
        }
    }

    private static void assertNoDataAccessCollaborator(MainMenuService service) {
        for (Field field : service.getClass().getDeclaredFields()) {
            String type = field.getType().getName();
            for (String forbidden : DATA_ACCESS_TYPE_MARKERS) {
                assertThat(type.toLowerCase(Locale.ROOT))
                        .describedAs("field '%s' is a %s, but COMEN01C performs no dataset access at "
                                + "all - it declares no SELECT, no FD and no file-handling EXEC CICS "
                                + "command", field.getName(), type)
                        .doesNotContain(forbidden);
            }
        }
    }

    private static void assertNoMutableState(MainMenuService service) {
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

    private static MainMenuService service(FixedWidthCodec codec) {
        return new MainMenuService(usrSecBindings(), codec);
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

    private static MainMenuInput inputOf(Invocation invocation) {
        byte eibAid = aidByte(invocation);
        String option = invocation.mapFields().getOrDefault(OPTION_INPUT_FIELD, BLANK_OPTION);
        NavigationContext context = commareaContext(invocation);
        return context == null
                ? MainMenuInput.withoutCommarea(eibAid, option)
                : new MainMenuInput(context, eibAid, option);
    }

    private static MainMenuRequest requestOf(Invocation invocation) {
        return new MainMenuRequest(spaces(TRN_NAME_LENGTH),
                spaces(TITLE_LENGTH),
                spaces(CURDATE_LENGTH),
                spaces(PGM_NAME_LENGTH),
                spaces(TITLE_LENGTH),
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
                    .describedAs("only the EIBCALEN = 0 path may omit the AID; every other path reaches "
                            + "the EVALUATE at line 93 and the byte it compares is behaviour")
                    .isZero();
            return CicsAid.DFHENTER;
        }
        Byte eibAid = AID_BY_MNEMONIC.get(mnemonic);
        assertThat(eibAid)
                .describedAs("'%s' is not a DFHAID mnemonic; common.CicsAid is the single reproduction "
                        + "of that IBM-supplied copybook, which is absent from this repository", mnemonic)
                .isNotNull();
        return eibAid;
    }

    private static NavigationContext userReentered() {
        return NavigationContext.empty().withPgmReenter().withUserTypeUser();
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

    private static List<String> observableStrings(MainMenuOutcome outcome) {
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

    private static List<String> paintedMenuLines(int activeCount) {
        List<String> lines = new ArrayList<>(MENU_LINE_COUNT);
        for (int subscript = 1; subscript <= activeCount; subscript++) {
            lines.add(picX(String.format(Locale.ROOT, "%02d", subscript) + OPTION_NUMBER_SEPARATOR
                    + OPTION_NAMES.get(subscript - 1), MENU_LINE_LENGTH));
        }
        while (lines.size() < MENU_LINE_COUNT) {
            lines.add(BLANK_MENU_LINE);
        }
        return List.copyOf(lines);
    }

    private static List<Optional<MenuOption>> copybookSlots() {
        List<Optional<MenuOption>> slots = new ArrayList<>(OCCURS_TABLE_SIZE);
        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            slots.add(Optional.of(MenuOption.of(subscript, OPTION_NAMES.get(subscript - 1),
                    OPTION_PROGRAMS.get(subscript - 1), REGULAR_USER_USRTYPE)));
        }
        while (slots.size() < OCCURS_TABLE_SIZE) {
            slots.add(Optional.empty());
        }
        return slots;
    }

    private static MainMenuOptionTable rotatedByOne() {
        List<Optional<MenuOption>> slots = new ArrayList<>(OCCURS_TABLE_SIZE);
        for (int subscript = 1; subscript <= ACTIVE_OPTION_COUNT; subscript++) {
            int rotated = subscript % ACTIVE_OPTION_COUNT + 1;
            slots.add(Optional.of(MenuOption.of(rotated, OPTION_NAMES.get(rotated - 1),
                    OPTION_PROGRAMS.get(rotated - 1), REGULAR_USER_USRTYPE)));
        }
        while (slots.size() < OCCURS_TABLE_SIZE) {
            slots.add(Optional.empty());
        }
        return new MainMenuOptionTable(slots, ACTIVE_OPTION_COUNT);
    }

    private static MainMenuOptionTable truncatedByOne() {
        return new MainMenuOptionTable(copybookSlots(), ACTIVE_OPTION_COUNT - 1);
    }

    private static MainMenuOptionTable adminOnlyFirstEntryTable() {
        List<Optional<MenuOption>> slots = new ArrayList<>(OCCURS_TABLE_SIZE);
        slots.add(Optional.of(MenuOption.of(1, OPTION_NAMES.get(0), OPTION_PROGRAMS.get(0),
                ADMIN_ONLY_USRTYPE)));
        while (slots.size() < OCCURS_TABLE_SIZE) {
            slots.add(Optional.empty());
        }
        return new MainMenuOptionTable(slots, 1);
    }

    private static MainMenuOptionTable adminOnlySlotThreeTable() {
        List<Optional<MenuOption>> slots = new ArrayList<>(OCCURS_TABLE_SIZE);
        for (int subscript = 1; subscript <= 4; subscript++) {
            String usrType = subscript == 3 ? ADMIN_ONLY_USRTYPE : REGULAR_USER_USRTYPE;
            slots.add(Optional.of(MenuOption.of(subscript, OPTION_NAMES.get(subscript - 1),
                    OPTION_PROGRAMS.get(subscript - 1), usrType)));
        }
        while (slots.size() < OCCURS_TABLE_SIZE) {
            slots.add(Optional.empty());
        }
        return new MainMenuOptionTable(slots, 1);
    }

    private static MainMenuOptionTable dummyPrefixTableFor(int activeCount) {
        List<Optional<MenuOption>> slots = new ArrayList<>(OCCURS_TABLE_SIZE);
        for (int subscript = 1; subscript <= activeCount; subscript++) {
            slots.add(Optional.of(MenuOption.of(subscript, OPTION_NAMES.get(subscript - 1),
                    DUMMY_PROGRAM_NAME, REGULAR_USER_USRTYPE)));
        }
        while (slots.size() < OCCURS_TABLE_SIZE) {
            slots.add(Optional.empty());
        }
        return new MainMenuOptionTable(slots, activeCount);
    }

    private static Map<String, Byte> aidByMnemonic() {
        Map<String, Byte> inverted = new LinkedHashMap<>();
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            inverted.put(entry.getValue(), entry.getKey());
        }
        return Collections.unmodifiableMap(inverted);
    }
}
