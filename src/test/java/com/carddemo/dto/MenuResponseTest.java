package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure, framework-free unit test for the composite menu payload {@link MenuResponse}.
 *
 * <p>{@code MenuResponse} is the JSON contract that replaces the legacy 3270/BMS menu screens of the
 * mainframe CardDemo application, migrated from the read-only COBOL source (referenced by source SHA
 * {@code 27d6c6f}, never copied into the target):</p>
 * <ul>
 *   <li><b>Main menu</b> &mdash; transaction {@code CM00}, program {@code COMEN01C}, screen shell
 *       {@code COMEN01} ({@code app/cpy-bms/COMEN01.CPY}, title field {@code TITLE01I}
 *       {@code PIC X(40)}). Its selectable options come from copybook {@code COMEN02Y}
 *       ({@code CARDDEMO-MAIN-MENU-OPTIONS}); the authoritative count is
 *       {@code CDEMO-MENU-OPT-COUNT = 10}, and every option carries a user-type flag of
 *       {@code 'U'}.</li>
 *   <li><b>Admin menu</b> &mdash; transaction {@code CA00}, program {@code COADM01C}, screen shell
 *       {@code COADM01} ({@code app/cpy-bms/COADM01.CPY}). Its options come from copybook
 *       {@code COADM02Y} ({@code CARDDEMO-ADMIN-MENU-OPTIONS}); the authoritative count is
 *       {@code CDEMO-ADMIN-OPT-COUNT = 4}, and the admin table has <em>no</em> user-type field so
 *       every admin option's {@code userType} is {@code null} on the Java side.</li>
 * </ul>
 *
 * <p>The production type is the plain carrier record
 * {@code MenuResponse(String menuType, String title, List<MenuOption> options)}; there is no
 * {@code mainMenu()}/{@code adminMenu()} factory on the record itself (role filtering and option
 * assembly are the service layer's job), so this test builds representative option lists that mirror
 * {@code COMEN02Y}/{@code COADM02Y} exactly and asserts the migration invariants against them.</p>
 *
 * <p>The suite is organised into four groups that mirror the migration acceptance criteria for this
 * DTO: the nested composite JSON round-trip and wire contract (Phase&nbsp;1), the critical
 * MAIN&nbsp;=&nbsp;10 / ADMIN&nbsp;=&nbsp;4 option-count and {@code menuType} discriminator invariant
 * (Phase&nbsp;2), the preservation of COBOL option-number ordering through a round-trip
 * (Phase&nbsp;3), and the record's construction, immutability and value semantics (Phase&nbsp;4). It
 * loads no Spring context and performs no database, file, or network I/O: every assertion is an
 * in-memory check driven by the shared, production-mirroring Jackson mapper exposed by
 * {@link DtoTestSupport}. That keeps the suite fast and deterministic while contributing to line
 * coverage (JaCoCo, Gate&nbsp;8). Design rationale lives in {@code docs/decision-log.md}, not in
 * verbose comments, and no COBOL source is reproduced here.</p>
 */
@DisplayName("MenuResponse DTO — composite menu payload (COMEN01/COMEN02Y, COADM01/COADM02Y)")
class MenuResponseTest {

    /** {@code menuType} discriminator for the main menu ({@code COMEN01} / {@code CM00}). */
    private static final String MENU_TYPE_MAIN = "MAIN";

    /** {@code menuType} discriminator for the admin menu ({@code COADM01} / {@code CA00}). */
    private static final String MENU_TYPE_ADMIN = "ADMIN";

    /**
     * Authoritative main-menu option count. Source of truth: copybook {@code COMEN02Y},
     * {@code 05 CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10}.
     */
    private static final int MAIN_OPTION_COUNT = 10;

    /**
     * Authoritative admin-menu option count. Source of truth: copybook {@code COADM02Y},
     * {@code 05 CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4}.
     */
    private static final int ADMIN_OPTION_COUNT = 4;

    /** Representative main-menu title (legacy BMS {@code TITLE01I}, {@code PIC X(40)}). */
    private static final String MAIN_TITLE = "Main Menu";

    /** Representative admin-menu title (legacy BMS {@code TITLE01I}, {@code PIC X(40)}). */
    private static final String ADMIN_TITLE = "Admin Menu";

    /** The three JSON property names that make up the {@code MenuResponse} wire contract. */
    private static final String[] RESPONSE_KEYS = {"menuType", "title", "options"};

    /** The four JSON property names that make up each nested {@code MenuOption} on the wire. */
    private static final String[] OPTION_KEYS = {"optionNumber", "optionName", "targetProgram", "userType"};

    /**
     * The ten main-menu options in the exact 1..10 order declared by {@code COMEN02Y}
     * ({@code CDEMO-MENU-OPTIONS-DATA}); every option carries the regular-user visibility flag
     * {@code "U"} ({@code CDEMO-MENU-OPT-USRTYPE}).
     */
    private static final List<MenuOption> MAIN_MENU_OPTIONS = List.of(
            new MenuOption(1, "Account View", "COACTVWC", "U"),
            new MenuOption(2, "Account Update", "COACTUPC", "U"),
            new MenuOption(3, "Credit Card List", "COCRDLIC", "U"),
            new MenuOption(4, "Credit Card View", "COCRDSLC", "U"),
            new MenuOption(5, "Credit Card Update", "COCRDUPC", "U"),
            new MenuOption(6, "Transaction List", "COTRN00C", "U"),
            new MenuOption(7, "Transaction View", "COTRN01C", "U"),
            new MenuOption(8, "Transaction Add", "COTRN02C", "U"),
            new MenuOption(9, "Transaction Reports", "CORPT00C", "U"),
            new MenuOption(10, "Bill Payment", "COBIL00C", "U"));

    /**
     * The four admin-menu options in the exact 1..4 order declared by {@code COADM02Y}
     * ({@code CDEMO-ADMIN-OPTIONS-DATA}); the admin table has no user-type field, so every
     * {@code userType} is {@code null}.
     */
    private static final List<MenuOption> ADMIN_MENU_OPTIONS = List.of(
            new MenuOption(1, "User List (Security)", "COUSR00C", null),
            new MenuOption(2, "User Add (Security)", "COUSR01C", null),
            new MenuOption(3, "User Update (Security)", "COUSR02C", null),
            new MenuOption(4, "User Delete (Security)", "COUSR03C", null));

    /**
     * Builds a canonical main-menu response ({@code menuType = "MAIN"}) carrying the ten
     * {@code COMEN02Y} options in menu order.
     *
     * @return a fresh main-menu {@link MenuResponse}
     */
    private static MenuResponse mainMenu() {
        return new MenuResponse(MENU_TYPE_MAIN, MAIN_TITLE, MAIN_MENU_OPTIONS);
    }

    /**
     * Builds a canonical admin-menu response ({@code menuType = "ADMIN"}) carrying the four
     * {@code COADM02Y} options in menu order.
     *
     * @return a fresh admin-menu {@link MenuResponse}
     */
    private static MenuResponse adminMenu() {
        return new MenuResponse(MENU_TYPE_ADMIN, ADMIN_TITLE, ADMIN_MENU_OPTIONS);
    }

    // =====================================================================
    // Phase 1 — nested composite JSON round-trip and wire contract
    // =====================================================================

    @Nested
    @DisplayName("Phase 1 — nested composite JSON round-trip and wire contract")
    class JsonRoundTrip {

        @Test
        @DisplayName("main-menu response round-trips through JSON preserving the whole nested tuple")
        void mainMenuRoundTripPreservesEverything() {
            MenuResponse original = mainMenu();

            MenuResponse restored = DtoTestSupport.roundTrip(original, MenuResponse.class);

            // Whole-value equality proves menuType, title, and every nested MenuOption survived the
            // serialize + deserialize cycle (record equality is recursive through the option list).
            assertThat(restored).isEqualTo(original);
            assertThat(restored.menuType()).isEqualTo(MENU_TYPE_MAIN);
            assertThat(restored.title()).isEqualTo(MAIN_TITLE);
            assertThat(restored.options()).containsExactlyElementsOf(MAIN_MENU_OPTIONS);
        }

        @Test
        @DisplayName("admin-menu response round-trips, keeping every option's userType null")
        void adminMenuRoundTripPreservesNullUserTypes() {
            MenuResponse original = adminMenu();

            MenuResponse restored = DtoTestSupport.roundTrip(original, MenuResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.menuType()).isEqualTo(MENU_TYPE_ADMIN);
            assertThat(restored.options()).containsExactlyElementsOf(ADMIN_MENU_OPTIONS);
            // COADM02Y has no user-type field: every admin option's userType stays null on the wire.
            assertThat(restored.options())
                    .allSatisfy(option -> assertThat(option.userType()).isNull());
        }

        @Test
        @DisplayName("serializes exactly the three contract keys menuType/title/options and no others")
        void topLevelJsonExposesExactlyTheThreeContractKeys() {
            String json = DtoTestSupport.toJson(mainMenu());

            // Re-read as an untyped map so the assertion inspects the actual wire keys Jackson emits
            // (the record component names), not the Java accessors.
            Map<String, Object> fields = DtoTestSupport.fromJson(json, new TypeReference<Map<String, Object>>() { });

            assertThat(fields).containsOnlyKeys(RESPONSE_KEYS);
            assertThat(fields.get("menuType")).isEqualTo(MENU_TYPE_MAIN);
            assertThat(fields.get("title")).isEqualTo(MAIN_TITLE);
            // options must be a JSON array (deserialised to a java.util.List under an Object binding).
            assertThat(fields.get("options")).isInstanceOf(List.class);
        }

        @Test
        @DisplayName("options serializes as a JSON array of objects, one per menu option")
        void optionsSerializeAsJsonArrayOfObjects() {
            String json = DtoTestSupport.toJson(mainMenu());

            JsonNode root = DtoTestSupport.fromJson(json, JsonNode.class);

            assertThat(root.get("menuType").asText()).isEqualTo(MENU_TYPE_MAIN);
            JsonNode options = root.get("options");
            assertThat(options.isArray()).as("options must serialize as a JSON array").isTrue();
            assertThat(options.size()).as("array element count").isEqualTo(MAIN_OPTION_COUNT);

            JsonNode first = options.get(0);
            assertThat(first.isObject()).as("each option element is a JSON object").isTrue();
            assertThat(first.has("optionNumber")).isTrue();
            assertThat(first.has("optionName")).isTrue();
            assertThat(first.has("targetProgram")).isTrue();
            assertThat(first.has("userType")).isTrue();

            // First element fidelity: option 1 of COMEN02Y is 01 'Account View' 'COACTVWC' 'U'.
            assertThat(first.get("optionNumber").isInt()).as("optionNumber is a JSON number").isTrue();
            assertThat(first.get("optionNumber").asInt()).isEqualTo(1);
            assertThat(first.get("optionName").asText()).isEqualTo("Account View");
            assertThat(first.get("targetProgram").asText()).isEqualTo("COACTVWC");
            assertThat(first.get("userType").asText()).isEqualTo("U");
        }

        @Test
        @DisplayName("every option object carries exactly optionNumber/optionName/targetProgram/userType")
        void everyOptionObjectExposesExactlyTheFourContractKeys() {
            // Deserialise the options array as a list of untyped maps so the key set is inspected on
            // the wire, warning-free (the TypeReference preserves the full generic type).
            List<Map<String, Object>> optionMaps = DtoTestSupport.fromJson(
                    DtoTestSupport.toJson(MAIN_MENU_OPTIONS),
                    new TypeReference<List<Map<String, Object>>>() { });

            assertThat(optionMaps).hasSize(MAIN_OPTION_COUNT);
            assertThat(optionMaps)
                    .allSatisfy(optionMap -> assertThat(optionMap).containsOnlyKeys(OPTION_KEYS));

            Map<String, Object> firstOption = optionMaps.get(0);
            assertThat(firstOption.get("optionNumber")).isEqualTo(1);
            assertThat(firstOption.get("optionName")).isEqualTo("Account View");
            assertThat(firstOption.get("targetProgram")).isEqualTo("COACTVWC");
            assertThat(firstOption.get("userType")).isEqualTo("U");
        }
    }

    // =====================================================================
    // Phase 2 — MAIN = 10 options / ADMIN = 4 options (CRITICAL)
    // =====================================================================

    @Nested
    @DisplayName("Phase 2 — MAIN = 10 options / ADMIN = 4 options (critical invariant)")
    class OptionCounts {

        @Test
        @DisplayName("MAIN menu carries exactly 10 options (COMEN02Y CDEMO-MENU-OPT-COUNT = 10)")
        void mainMenuHasTenOptions() {
            // Authoritative count: copybook COMEN02Y declares CDEMO-MENU-OPT-COUNT VALUE 10.
            MenuResponse main = mainMenu();

            assertThat(main.menuType()).isEqualTo(MENU_TYPE_MAIN);
            assertThat(main.options()).as("main-menu option count").hasSize(MAIN_OPTION_COUNT);
            assertThat(MAIN_OPTION_COUNT).as("authoritative COMEN02Y count").isEqualTo(10);
            // The count survives a JSON round-trip (no options dropped or duplicated on the wire).
            assertThat(DtoTestSupport.roundTrip(main, MenuResponse.class).options())
                    .as("main-menu option count after round-trip")
                    .hasSize(10);
        }

        @Test
        @DisplayName("ADMIN menu carries exactly 4 options (COADM02Y CDEMO-ADMIN-OPT-COUNT = 4)")
        void adminMenuHasFourOptions() {
            // Authoritative count: copybook COADM02Y declares CDEMO-ADMIN-OPT-COUNT VALUE 4.
            MenuResponse admin = adminMenu();

            assertThat(admin.menuType()).isEqualTo(MENU_TYPE_ADMIN);
            assertThat(admin.options()).as("admin-menu option count").hasSize(ADMIN_OPTION_COUNT);
            assertThat(ADMIN_OPTION_COUNT).as("authoritative COADM02Y count").isEqualTo(4);
            assertThat(DtoTestSupport.roundTrip(admin, MenuResponse.class).options())
                    .as("admin-menu option count after round-trip")
                    .hasSize(4);
        }

        @Test
        @DisplayName("menuType distinguishes MAIN from ADMIN using the exact production literals")
        void menuTypeDistinguishesMainFromAdmin() {
            assertThat(mainMenu().menuType()).isEqualTo("MAIN");
            assertThat(adminMenu().menuType()).isEqualTo("ADMIN");
            assertThat(mainMenu().menuType()).isNotEqualTo(adminMenu().menuType());
            // Different discriminator + different option set => distinct responses.
            assertThat(mainMenu()).isNotEqualTo(adminMenu());
        }

        @Test
        @DisplayName("admin options have null userType while every main option carries \"U\"")
        void userTypePresenceMatchesEachMenusCopybook() {
            // COADM02Y has no user-type field: admin options must expose a null userType.
            assertThat(adminMenu().options())
                    .allSatisfy(option -> assertThat(option.userType())
                            .as("admin option %d userType", option.optionNumber())
                            .isNull());
            // COMEN02Y marks all ten main options with CDEMO-MENU-OPT-USRTYPE = 'U'.
            assertThat(mainMenu().options())
                    .allSatisfy(option -> assertThat(option.userType())
                            .as("main option %d userType", option.optionNumber())
                            .isEqualTo("U"));
        }
    }

    // =====================================================================
    // Phase 3 — option ordering preserved (COBOL option-number order)
    // =====================================================================

    @Nested
    @DisplayName("Phase 3 — option ordering preserved through round-trip")
    class OptionOrdering {

        @Test
        @DisplayName("main-menu option order (1..10) survives the JSON round-trip element-by-element")
        void mainMenuOrderingPreservedThroughRoundTrip() {
            MenuResponse restored = DtoTestSupport.roundTrip(mainMenu(), MenuResponse.class);

            // Element-by-element order preserved (record value equality per element).
            assertThat(restored.options()).containsExactlyElementsOf(MAIN_MENU_OPTIONS);
            // The 1-based option numbers appear in strict ascending menu order.
            assertThat(restored.options().stream().map(MenuOption::optionNumber).toList())
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            // The dispatched COBOL program ids appear in the exact COMEN02Y sequence.
            assertThat(restored.options().stream().map(MenuOption::targetProgram).toList())
                    .containsExactly("COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC",
                            "COTRN00C", "COTRN01C", "COTRN02C", "CORPT00C", "COBIL00C");
        }

        @Test
        @DisplayName("admin-menu option order (1..4) survives the JSON round-trip element-by-element")
        void adminMenuOrderingPreservedThroughRoundTrip() {
            MenuResponse restored = DtoTestSupport.roundTrip(adminMenu(), MenuResponse.class);

            assertThat(restored.options()).containsExactlyElementsOf(ADMIN_MENU_OPTIONS);
            assertThat(restored.options().stream().map(MenuOption::optionNumber).toList())
                    .containsExactly(1, 2, 3, 4);
            assertThat(restored.options().stream().map(MenuOption::targetProgram).toList())
                    .containsExactly("COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C");
        }

        @Test
        @DisplayName("ordering is significant: a reordered option list yields a non-equal response")
        void orderingIsSignificant() {
            MenuOption first = MAIN_MENU_OPTIONS.get(0);
            MenuOption second = MAIN_MENU_OPTIONS.get(1);
            MenuResponse inOrder = new MenuResponse(MENU_TYPE_MAIN, MAIN_TITLE, List.of(first, second));
            MenuResponse swapped = new MenuResponse(MENU_TYPE_MAIN, MAIN_TITLE, List.of(second, first));

            // The record preserves list order, so swapping two options makes it a different value —
            // menu ordering can never be silently reordered without changing equality.
            assertThat(swapped).isNotEqualTo(inOrder);
            assertThat(swapped.options()).containsExactly(second, first);
        }
    }

    // =====================================================================
    // Phase 4 — construction, immutability and record value semantics
    // =====================================================================

    @Nested
    @DisplayName("Phase 4 — construction, immutability and record semantics")
    class ConstructionAndImmutability {

        @Test
        @DisplayName("null options are normalised to a non-null empty list and round-trip as an empty array")
        void nullOptionsNormalisedToEmptyList() {
            MenuResponse response = new MenuResponse(MENU_TYPE_MAIN, MAIN_TITLE, null);

            assertThat(response.options()).as("null options normalised").isNotNull().isEmpty();

            MenuResponse restored = DtoTestSupport.roundTrip(response, MenuResponse.class);
            assertThat(restored.options()).isEmpty();
            assertThat(restored).isEqualTo(response);
        }

        @Test
        @DisplayName("options() exposes an unmodifiable list")
        void optionsListIsUnmodifiable() {
            MenuResponse response = mainMenu();
            MenuOption extra = new MenuOption(11, "Extra", "COEXTRAC", "U");

            assertThatThrownBy(() -> response.options().add(extra))
                    .as("options() must expose an unmodifiable list")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("options are defensively copied so later mutation of the source list has no effect")
        void optionsAreDefensivelyCopiedFromSource() {
            List<MenuOption> mutableSource = new ArrayList<>();
            mutableSource.add(new MenuOption(1, "Account View", "COACTVWC", "U"));

            MenuResponse response = new MenuResponse(MENU_TYPE_MAIN, MAIN_TITLE, mutableSource);

            // Mutating the caller's list AFTER construction must not leak into the response.
            mutableSource.add(new MenuOption(2, "Account Update", "COACTUPC", "U"));

            assertThat(response.options()).as("defensive copy insulates the response").hasSize(1);
            assertThat(response.options().get(0).targetProgram()).isEqualTo("COACTVWC");
        }

        @Test
        @DisplayName("equal-arg responses are equals()/hashCode() consistent; different args are not equal")
        void recordEqualityAndHashCodeAreConsistent() {
            MenuResponse first = mainMenu();
            MenuResponse second = mainMenu();
            MenuResponse different = adminMenu();

            // Record component-wise value semantics: equal args => equal + same hashCode (symmetric).
            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(second).isEqualTo(first);
            assertThat(first).isNotEqualTo(different);
            // The generated toString surfaces the menuType discriminator value.
            assertThat(first.toString()).isNotNull().contains(MENU_TYPE_MAIN);
        }
    }
}
