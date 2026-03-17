/*
 * MainMenuServiceTest.java — Unit Tests for Main Menu Routing Metadata Service
 *
 * Validates MainMenuService behavior against the COMEN02Y.cpy 10-option VALUE table
 * and the COMEN01C.cbl menu processing logic. Each test verifies exact behavioral
 * parity with the COBOL source:
 *
 *   - Option count = 10 (CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10)
 *   - Option names match CDEMO-MENU-OPT-NAME PIC X(35) VALUES, trimmed
 *   - COBOL program mappings match CDEMO-MENU-OPT-PGMNAME PIC X(08) VALUES
 *   - REST endpoint mappings follow AAP controller routes
 *   - Range validation matches PROCESS-ENTER-KEY lines 127-134
 *   - User type filtering matches PROCESS-ENTER-KEY lines 136-143
 *   - List immutability enforces service-layer encapsulation
 *
 * Testing framework: JUnit 5 + AssertJ (no mocks, no Spring context)
 * Source repository commit SHA: 27d6c6f
 */
package com.cardemo.unit.service;

import com.cardemo.model.enums.UserType;
import com.cardemo.service.menu.MainMenuService;
import com.cardemo.service.menu.MainMenuService.MenuOption;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MainMenuService} — the main menu routing metadata service
 * migrated from COBOL program COMEN01C.cbl and the COMEN02Y.cpy option table.
 *
 * <p>These are pure unit tests with no mocking or Spring context. The service under
 * test is a stateless data holder that returns menu option metadata from a static
 * compile-time table (mirroring the COBOL COMEN02Y.cpy VALUE table).</p>
 *
 * <p>All 10 option names, COBOL program names, REST endpoints, and user type codes
 * are verified against the exact values from the COMEN02Y.cpy copybook source.</p>
 */
class MainMenuServiceTest {

    private MainMenuService mainMenuService;

    /**
     * Instantiates a fresh {@link MainMenuService} before each test method.
     * No mocks or Spring context needed — this is a pure static data service.
     */
    @BeforeEach
    void setUp() {
        mainMenuService = new MainMenuService();
    }

    // -----------------------------------------------------------------------
    // Test Case 1: getMenuOptions() returns exactly 10 options
    // Validates: CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10 (COMEN02Y.cpy line 21)
    //            getOptionCount() returns same value
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link MainMenuService#getMenuOptions()} returns exactly 10 items
     * and that {@link MainMenuService#getOptionCount()} returns 10, matching
     * CDEMO-MENU-OPT-COUNT VALUE 10 from COMEN02Y.cpy.
     */
    @Test
    void testGetMenuOptions_returnsAll10Options() {
        List<MenuOption> options = mainMenuService.getMenuOptions();

        assertThat(options).hasSize(10);
        assertThat(mainMenuService.getOptionCount()).isEqualTo(10);
    }

    // -----------------------------------------------------------------------
    // Test Case 2: Option names match COMEN02Y.cpy VALUES exactly (trimmed)
    // Validates: CDEMO-MENU-OPT-NAME PIC X(35) for each of the 10 entries
    // -----------------------------------------------------------------------

    /**
     * Verifies that all 10 option names match the exact trimmed values from the
     * COMEN02Y.cpy VALUE table entries (PIC X(35) fields, trailing spaces removed).
     *
     * <p>Expected names in order:</p>
     * <ol>
     *   <li>Account View (COMEN02Y.cpy lines 26-27)</li>
     *   <li>Account Update (lines 32-33)</li>
     *   <li>Credit Card List (lines 38-39)</li>
     *   <li>Credit Card View (lines 44-45)</li>
     *   <li>Credit Card Update (lines 50-51)</li>
     *   <li>Transaction List (lines 56-57)</li>
     *   <li>Transaction View (lines 62-63)</li>
     *   <li>Transaction Add (lines 68-70)</li>
     *   <li>Transaction Reports (lines 75-76)</li>
     *   <li>Bill Payment (lines 81-82)</li>
     * </ol>
     */
    @Test
    void testGetMenuOptions_correctOptionNames() {
        List<MenuOption> options = mainMenuService.getMenuOptions();

        assertThat(options.get(0).optionName()).isEqualTo("Account View");
        assertThat(options.get(1).optionName()).isEqualTo("Account Update");
        assertThat(options.get(2).optionName()).isEqualTo("Credit Card List");
        assertThat(options.get(3).optionName()).isEqualTo("Credit Card View");
        assertThat(options.get(4).optionName()).isEqualTo("Credit Card Update");
        assertThat(options.get(5).optionName()).isEqualTo("Transaction List");
        assertThat(options.get(6).optionName()).isEqualTo("Transaction View");
        assertThat(options.get(7).optionName()).isEqualTo("Transaction Add");
        assertThat(options.get(8).optionName()).isEqualTo("Transaction Reports");
        assertThat(options.get(9).optionName()).isEqualTo("Bill Payment");
    }

    // -----------------------------------------------------------------------
    // Test Case 3: COBOL program names match COMEN02Y.cpy VALUES exactly
    // Validates: CDEMO-MENU-OPT-PGMNAME PIC X(08) for each of the 10 entries
    // -----------------------------------------------------------------------

    /**
     * Verifies that all 10 COBOL program names match the exact values from the
     * COMEN02Y.cpy VALUE table entries (PIC X(08) fields, preserved for traceability).
     *
     * <p>These program names are preserved from the COBOL source as traceability
     * references — they map to the CICS XCTL targets in the original application.</p>
     */
    @Test
    void testGetMenuOptions_correctCobolPrograms() {
        List<MenuOption> options = mainMenuService.getMenuOptions();

        assertThat(options.get(0).cobolProgram()).isEqualTo("COACTVWC");
        assertThat(options.get(1).cobolProgram()).isEqualTo("COACTUPC");
        assertThat(options.get(2).cobolProgram()).isEqualTo("COCRDLIC");
        assertThat(options.get(3).cobolProgram()).isEqualTo("COCRDSLC");
        assertThat(options.get(4).cobolProgram()).isEqualTo("COCRDUPC");
        assertThat(options.get(5).cobolProgram()).isEqualTo("COTRN00C");
        assertThat(options.get(6).cobolProgram()).isEqualTo("COTRN01C");
        assertThat(options.get(7).cobolProgram()).isEqualTo("COTRN02C");
        assertThat(options.get(8).cobolProgram()).isEqualTo("CORPT00C");
        assertThat(options.get(9).cobolProgram()).isEqualTo("COBIL00C");
    }

    // -----------------------------------------------------------------------
    // Test Case 4: REST API endpoints match AAP controller route definitions
    // Validates: apiEndpoint field mapping from COBOL XCTL targets to REST paths
    // -----------------------------------------------------------------------

    /**
     * Verifies that all 10 REST API endpoint mappings match the AAP controller routes.
     *
     * <p>Endpoint mapping from COBOL XCTL targets to REST paths:</p>
     * <ul>
     *   <li>COACTVWC → GET /api/accounts/{id} (AccountController)</li>
     *   <li>COACTUPC → PUT /api/accounts/{id} (AccountController)</li>
     *   <li>COCRDLIC → GET /api/cards (CardController)</li>
     *   <li>COCRDSLC → GET /api/cards/{id} (CardController)</li>
     *   <li>COCRDUPC → PUT /api/cards/{id} (CardController)</li>
     *   <li>COTRN00C → GET /api/transactions (TransactionController)</li>
     *   <li>COTRN01C → GET /api/transactions/{id} (TransactionController)</li>
     *   <li>COTRN02C → POST /api/transactions (TransactionController)</li>
     *   <li>CORPT00C → POST /api/reports/submit (ReportController)</li>
     *   <li>COBIL00C → POST /api/billing/pay (BillingController)</li>
     * </ul>
     */
    @Test
    void testGetMenuOptions_correctApiEndpoints() {
        List<MenuOption> options = mainMenuService.getMenuOptions();

        assertThat(options.get(0).apiEndpoint()).isEqualTo("/api/accounts/{id}");
        assertThat(options.get(1).apiEndpoint()).isEqualTo("/api/accounts/{id}");
        assertThat(options.get(2).apiEndpoint()).isEqualTo("/api/cards");
        assertThat(options.get(3).apiEndpoint()).isEqualTo("/api/cards/{id}");
        assertThat(options.get(4).apiEndpoint()).isEqualTo("/api/cards/{id}");
        assertThat(options.get(5).apiEndpoint()).isEqualTo("/api/transactions");
        assertThat(options.get(6).apiEndpoint()).isEqualTo("/api/transactions/{id}");
        assertThat(options.get(7).apiEndpoint()).isEqualTo("/api/transactions");
        assertThat(options.get(8).apiEndpoint()).isEqualTo("/api/reports/submit");
        assertThat(options.get(9).apiEndpoint()).isEqualTo("/api/billing/pay");
    }

    // -----------------------------------------------------------------------
    // Test Case 5: getMenuOption(int) returns correct option for valid inputs
    // Validates: 1-based indexing, first and last option boundary
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link MainMenuService#getMenuOption(int)} returns the correct
     * option for boundary values 1 (first) and 10 (last).
     *
     * <p>Mirrors COMEN01C.cbl PROCESS-ENTER-KEY paragraph where WS-OPTION is used
     * as an index into CDEMO-MENU-OPT (1-based COBOL indexing).</p>
     */
    @Test
    void testGetMenuOption_validOption() {
        MenuOption firstOption = mainMenuService.getMenuOption(1);
        assertThat(firstOption.optionNumber()).isEqualTo(1);
        assertThat(firstOption.optionName()).isEqualTo("Account View");

        MenuOption lastOption = mainMenuService.getMenuOption(10);
        assertThat(lastOption.optionNumber()).isEqualTo(10);
        assertThat(lastOption.optionName()).isEqualTo("Bill Payment");
    }

    // -----------------------------------------------------------------------
    // Test Case 6: getMenuOption(0) throws IllegalArgumentException
    // Validates: COMEN01C.cbl lines 127-134: WS-OPTION = ZEROS → error
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link MainMenuService#getMenuOption(int)} throws
     * {@link IllegalArgumentException} when passed 0.
     *
     * <p>Mirrors COMEN01C.cbl PROCESS-ENTER-KEY validation:
     * {@code IF WS-OPTION = ZEROS → "Please enter a valid option number..."}</p>
     */
    @Test
    void testGetMenuOption_invalidOption_zero() {
        assertThatThrownBy(() -> mainMenuService.getMenuOption(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------
    // Test Case 7: getMenuOption(11) throws IllegalArgumentException
    // Validates: COMEN01C.cbl lines 127-134: WS-OPTION > CDEMO-MENU-OPT-COUNT → error
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link MainMenuService#getMenuOption(int)} throws
     * {@link IllegalArgumentException} when passed 11 (exceeds CDEMO-MENU-OPT-COUNT = 10).
     *
     * <p>Mirrors COMEN01C.cbl PROCESS-ENTER-KEY validation:
     * {@code IF WS-OPTION > CDEMO-MENU-OPT-COUNT → "Please enter a valid option number..."}</p>
     */
    @Test
    void testGetMenuOption_invalidOption_outOfRange() {
        assertThatThrownBy(() -> mainMenuService.getMenuOption(11))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------
    // Test Case 8: getMenuOptionsForUser(UserType.USER) returns user-accessible options
    // Validates: COMEN01C.cbl lines 136-143 user type filtering
    //            All 10 options have requiredUserType = "U" → regular user sees all
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link MainMenuService#getMenuOptionsForUser(UserType)} with
     * {@link UserType#USER} returns all options with {@code requiredUserType = "U"}.
     *
     * <p>Since all 10 options in COMEN02Y.cpy have user type "U", a regular user
     * should receive all 10 options. This mirrors the COBOL behavior where the
     * access check (lines 136-143) only denies when {@code CDEMO-MENU-OPT-USRTYPE = 'A'}.</p>
     */
    @Test
    void testGetMenuOptionsForUser_regularUser() {
        List<MenuOption> userOptions = mainMenuService.getMenuOptionsForUser(UserType.USER);

        assertThat(userOptions).hasSize(10);

        // Verify all returned options have requiredUserType "U"
        assertThat(userOptions)
                .extracting(MenuOption::requiredUserType)
                .containsOnly("U");
    }

    // -----------------------------------------------------------------------
    // Test Case 9: getMenuOptionsForUser(UserType.ADMIN) returns all options
    // Validates: Admin users see all options (no filtering applied)
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link MainMenuService#getMenuOptionsForUser(UserType)} with
     * {@link UserType#ADMIN} returns all 10 options without filtering.
     *
     * <p>Admin users have unrestricted menu access — the COBOL code only denies
     * access when the user is type 'U' and the option requires type 'A'. Admin
     * users bypass this check entirely.</p>
     */
    @Test
    void testGetMenuOptionsForUser_adminUser() {
        List<MenuOption> adminOptions = mainMenuService.getMenuOptionsForUser(UserType.ADMIN);

        assertThat(adminOptions).hasSize(10);

        // Verify option order is preserved (same as full list)
        assertThat(adminOptions.get(0).optionName()).isEqualTo("Account View");
        assertThat(adminOptions.get(9).optionName()).isEqualTo("Bill Payment");
    }

    // -----------------------------------------------------------------------
    // Test Case 10: isOptionAccessible(int, UserType) for regular user
    // Validates: COMEN01C.cbl lines 136-143 access check
    //            All 10 options accessible to regular users (all have type "U")
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link MainMenuService#isOptionAccessible(int, UserType)} returns
     * {@code true} for all 10 options when called with {@link UserType#USER}.
     *
     * <p>All 10 options in COMEN02Y.cpy have {@code CDEMO-MENU-OPT-USRTYPE = 'U'},
     * so the COBOL access check {@code IF CDEMO-USRTYP-USER AND
     * CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'} never triggers for any option.</p>
     */
    @Test
    void testIsOptionAccessible_regularUserAccessible() {
        for (int optionNumber = 1; optionNumber <= 10; optionNumber++) {
            assertThat(mainMenuService.isOptionAccessible(optionNumber, UserType.USER))
                    .as("Option %d should be accessible to regular user", optionNumber)
                    .isTrue();
        }
    }

    // -----------------------------------------------------------------------
    // Test Case 11: getMenuOptions() returns an immutable list
    // Validates: Service-layer data encapsulation — callers cannot mutate
    //            the static option table returned by the service
    // -----------------------------------------------------------------------

    /**
     * Verifies that the list returned by {@link MainMenuService#getMenuOptions()} is
     * unmodifiable — attempting to add an element throws {@link UnsupportedOperationException}.
     *
     * <p>This preserves the COBOL semantics where the COMEN02Y.cpy VALUE table is
     * compile-time constant and cannot be mutated at runtime.</p>
     */
    @Test
    void testGetMenuOptions_listIsImmutable() {
        List<MenuOption> options = mainMenuService.getMenuOptions();

        assertThatThrownBy(() -> options.add(
                new MenuOption(99, "Dummy", "DUMMY", "/api/dummy", "U")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
