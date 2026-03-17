/*
 * MainMenuService.java — Main Menu Routing Metadata Service
 *
 * Migrated from COBOL source artifacts:
 *   - app/cbl/COMEN01C.cbl (282 lines — Main Menu for Regular Users)
 *   - app/cpy/COMEN02Y.cpy (96 lines  — Menu Options VALUE table, 10 entries)
 *   - app/cpy/COCOM01Y.cpy (48 lines  — COMMAREA with CDEMO-USER-TYPE)
 *
 * In the original COBOL application, the main menu (transaction CM00) displayed
 * 10 options to regular users, routing them via CICS XCTL to specific programs.
 * In Java, this becomes a stateless service returning menu option metadata
 * (option number, label, target endpoint/program mapping, and required user type)
 * consumed by MenuController for the GET /api/menu/main endpoint.
 *
 * COBOL paragraph → Java method traceability:
 *   PROCESS-ENTER-KEY   → getMenuOption() + isOptionAccessible()
 *   BUILD-MENU-OPTIONS  → getMenuOptions()
 *   SEND-MENU-SCREEN    → getMenuOptions() response (JSON replaces BMS SEND MAP)
 *   RECEIVE-MENU-SCREEN → Controller request handling (REST replaces BMS RECEIVE MAP)
 *   POPULATE-HEADER-INFO→ N/A (date/time headers are REST response metadata)
 *   RETURN-TO-SIGNON    → N/A (handled by auth flow / PF3 signout is controller concern)
 *
 * Source repository commit SHA: 27d6c6f
 */
package com.cardemo.service.menu;

import com.cardemo.model.enums.UserType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Provides the main menu routing metadata migrated from COBOL program COMEN01C.cbl.
 *
 * <p>This service exposes the 10 menu options defined in the COMEN02Y.cpy compile-time
 * VALUE table as an unmodifiable list of {@link MenuOption} records. Each option maps
 * a COBOL program name to a REST API endpoint, preserving full traceability.</p>
 *
 * <p>The service is stateless — it has no repository dependencies. The menu option data
 * mirrors the COBOL compile-time VALUE table exactly (CDEMO-MENU-OPT-COUNT = 10,
 * OCCURS 12 TIMES capacity with 10 active entries).</p>
 *
 * <p>User type access filtering logic from COMEN01C.cbl lines 136-143 is preserved:
 * regular users (type 'U') are denied access to admin-only options (type 'A'),
 * while admin users see all options.</p>
 *
 * @see com.cardemo.model.enums.UserType
 */
@Service
public class MainMenuService {

    private static final Logger log = LoggerFactory.getLogger(MainMenuService.class);

    /**
     * Represents a single main menu option entry, migrated from the COMEN02Y.cpy
     * REDEFINES structure with OCCURS 12 TIMES.
     *
     * <p>Each field maps directly to a COBOL copybook field:</p>
     * <ul>
     *   <li>{@code optionNumber} — CDEMO-MENU-OPT-NUM PIC 9(02)</li>
     *   <li>{@code optionName} — CDEMO-MENU-OPT-NAME PIC X(35), trimmed</li>
     *   <li>{@code cobolProgram} — CDEMO-MENU-OPT-PGMNAME PIC X(08), preserved for traceability</li>
     *   <li>{@code apiEndpoint} — REST API path replacing CICS XCTL PROGRAM target</li>
     *   <li>{@code requiredUserType} — CDEMO-MENU-OPT-USRTYPE PIC X(01), "U" or "A"</li>
     * </ul>
     *
     * @param optionNumber  the 1-based menu option number (1–10)
     * @param optionName    the display label for this option, trimmed of trailing spaces
     * @param cobolProgram  the original COBOL program name for traceability (e.g., "COACTVWC")
     * @param apiEndpoint   the REST API endpoint path that replaces the COBOL XCTL target
     * @param requiredUserType the single-character user type code required ("U" or "A")
     */
    public record MenuOption(
            int optionNumber,
            String optionName,
            String cobolProgram,
            String apiEndpoint,
            String requiredUserType
    ) {
    }

    /**
     * Static unmodifiable list of all 10 main menu options, mirroring the COMEN02Y.cpy
     * compile-time VALUE table exactly. The order, names, and program mappings are
     * identical to the COBOL source.
     *
     * <p>COBOL source: CDEMO-MENU-OPTIONS-DATA with 10 FILLER groups, each containing
     * PIC 9(02) option number, PIC X(35) name, PIC X(08) program, PIC X(01) user type.</p>
     *
     * <p>All 10 options have requiredUserType = "U" per COMEN02Y.cpy VALUES.</p>
     */
    private static final List<MenuOption> MENU_OPTIONS = List.of(
            // Option 1: Account View → COACTVWC → 'U' (COMEN02Y.cpy lines 25-29)
            new MenuOption(1, "Account View", "COACTVWC",
                    "/api/accounts/{id}", "U"),
            // Option 2: Account Update → COACTUPC → 'U' (COMEN02Y.cpy lines 31-35)
            new MenuOption(2, "Account Update", "COACTUPC",
                    "/api/accounts/{id}", "U"),
            // Option 3: Credit Card List → COCRDLIC → 'U' (COMEN02Y.cpy lines 37-41)
            new MenuOption(3, "Credit Card List", "COCRDLIC",
                    "/api/cards", "U"),
            // Option 4: Credit Card View → COCRDSLC → 'U' (COMEN02Y.cpy lines 43-47)
            new MenuOption(4, "Credit Card View", "COCRDSLC",
                    "/api/cards/{id}", "U"),
            // Option 5: Credit Card Update → COCRDUPC → 'U' (COMEN02Y.cpy lines 49-53)
            new MenuOption(5, "Credit Card Update", "COCRDUPC",
                    "/api/cards/{id}", "U"),
            // Option 6: Transaction List → COTRN00C → 'U' (COMEN02Y.cpy lines 55-59)
            new MenuOption(6, "Transaction List", "COTRN00C",
                    "/api/transactions", "U"),
            // Option 7: Transaction View → COTRN01C → 'U' (COMEN02Y.cpy lines 61-65)
            new MenuOption(7, "Transaction View", "COTRN01C",
                    "/api/transactions/{id}", "U"),
            // Option 8: Transaction Add → COTRN02C → 'U' (COMEN02Y.cpy lines 67-72)
            new MenuOption(8, "Transaction Add", "COTRN02C",
                    "/api/transactions", "U"),
            // Option 9: Transaction Reports → CORPT00C → 'U' (COMEN02Y.cpy lines 74-78)
            new MenuOption(9, "Transaction Reports", "CORPT00C",
                    "/api/reports/submit", "U"),
            // Option 10: Bill Payment → COBIL00C → 'U' (COMEN02Y.cpy lines 80-84)
            new MenuOption(10, "Bill Payment", "COBIL00C",
                    "/api/billing/pay", "U")
    );

    /**
     * Returns the complete unmodifiable list of all 10 main menu options.
     *
     * <p>Mirrors the BUILD-MENU-OPTIONS paragraph (COMEN01C.cbl lines 236-277)
     * which iterates from 1 to CDEMO-MENU-OPT-COUNT (10) building the display
     * text for each option. In the Java implementation, this static list replaces
     * the runtime string building with pre-defined metadata.</p>
     *
     * @return an unmodifiable list of all 10 {@link MenuOption} entries
     */
    public List<MenuOption> getMenuOptions() {
        log.info("Retrieving all main menu options, count={}", MENU_OPTIONS.size());
        return MENU_OPTIONS;
    }

    /**
     * Returns the menu options filtered by the specified user type.
     *
     * <p>Mirrors the user type access check in PROCESS-ENTER-KEY (COMEN01C.cbl
     * lines 136-143):</p>
     * <ul>
     *   <li>If {@code userType} is {@link UserType#ADMIN}: returns ALL options
     *       (admin users have unrestricted access)</li>
     *   <li>If {@code userType} is {@link UserType#USER}: returns only options
     *       where {@code requiredUserType} is "U" (filters out admin-only options)</li>
     * </ul>
     *
     * <p>Currently all 10 options have {@code requiredUserType = "U"}, so both user
     * types receive all 10 options. The filtering logic is preserved for behavioral
     * parity with COBOL — if an option were changed to admin-only ("A"), the
     * filtering would automatically apply.</p>
     *
     * @param userType the user type to filter by; must not be {@code null}
     * @return an unmodifiable filtered list of accessible {@link MenuOption} entries
     * @throws IllegalArgumentException if {@code userType} is {@code null}
     */
    public List<MenuOption> getMenuOptionsForUser(UserType userType) {
        if (userType == null) {
            log.warn("Null user type provided for menu option filtering");
            throw new IllegalArgumentException("User type must not be null");
        }

        log.info("Retrieving main menu options for userType={}", userType);

        // COMEN01C.cbl lines 136-143: Admin users see all options.
        // Regular users only see options where requiredUserType matches "U".
        if (userType == UserType.ADMIN) {
            // Admin sees everything — no filtering needed
            return MENU_OPTIONS;
        }

        // UserType.USER: filter out admin-only options (requiredUserType = "A")
        // Mirrors: IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A' → deny
        List<MenuOption> filtered = MENU_OPTIONS.stream()
                .filter(option -> "U".equals(option.requiredUserType()))
                .toList();

        log.info("Filtered menu options for userType={}, resultCount={}", userType, filtered.size());
        return filtered;
    }

    /**
     * Retrieves a single menu option by its 1-based option number.
     *
     * <p>Mirrors the option validation in PROCESS-ENTER-KEY (COMEN01C.cbl
     * lines 127-134):</p>
     * <pre>
     *   IF WS-OPTION IS NOT NUMERIC OR
     *      WS-OPTION > CDEMO-MENU-OPT-COUNT OR
     *      WS-OPTION = ZEROS
     *       MOVE 'Please enter a valid option number...' TO WS-MESSAGE
     * </pre>
     *
     * @param optionNumber the 1-based option number (1 through 10)
     * @return the matching {@link MenuOption}
     * @throws IllegalArgumentException if {@code optionNumber} is less than 1
     *         or greater than the total option count (mirrors COBOL error message
     *         "Please enter a valid option number...")
     */
    public MenuOption getMenuOption(int optionNumber) {
        // Mirrors COMEN01C.cbl lines 127-134: validate option range
        if (optionNumber < 1 || optionNumber > MENU_OPTIONS.size()) {
            log.warn("Invalid menu option number requested: {}, valid range is 1-{}",
                    optionNumber, MENU_OPTIONS.size());
            throw new IllegalArgumentException(
                    "Please enter a valid option number (1-" + MENU_OPTIONS.size()
                            + "), received: " + optionNumber);
        }

        // List is 0-indexed; COBOL options are 1-indexed (CDEMO-MENU-OPT-NUM starts at 1)
        MenuOption option = MENU_OPTIONS.get(optionNumber - 1);
        log.info("Retrieved menu option {}: name='{}', program='{}', endpoint='{}'",
                option.optionNumber(), option.optionName(),
                option.cobolProgram(), option.apiEndpoint());
        return option;
    }

    /**
     * Returns the total count of active menu options.
     *
     * <p>Mirrors CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10 from COMEN02Y.cpy line 21.
     * The COBOL table has capacity for 12 entries (OCCURS 12 TIMES) but only 10
     * are active.</p>
     *
     * @return the number of active menu options (10)
     */
    public int getOptionCount() {
        return MENU_OPTIONS.size();
    }

    /**
     * Checks whether a specific menu option is accessible to the given user type.
     *
     * <p>Mirrors the exact user type access check from COMEN01C.cbl lines 136-143:</p>
     * <pre>
     *   IF CDEMO-USRTYP-USER AND
     *      CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
     *       SET ERR-FLG-ON TO TRUE
     *       MOVE 'No access - Admin Only option... ' TO WS-MESSAGE
     * </pre>
     *
     * <p>Access rules:</p>
     * <ul>
     *   <li>{@link UserType#ADMIN} users can access ALL options regardless of requiredUserType</li>
     *   <li>{@link UserType#USER} users are denied access to options with requiredUserType = "A"</li>
     *   <li>{@link UserType#USER} users can access options with requiredUserType = "U"</li>
     * </ul>
     *
     * @param optionNumber the 1-based option number to check (1 through 10)
     * @param userType     the user type requesting access; must not be {@code null}
     * @return {@code true} if the user can access the option, {@code false} otherwise
     * @throws IllegalArgumentException if {@code optionNumber} is out of range or
     *         {@code userType} is {@code null}
     */
    public boolean isOptionAccessible(int optionNumber, UserType userType) {
        if (userType == null) {
            log.warn("Null user type provided for access check on option {}", optionNumber);
            throw new IllegalArgumentException("User type must not be null");
        }

        // Validate option number range (mirrors COMEN01C lines 127-134)
        if (optionNumber < 1 || optionNumber > MENU_OPTIONS.size()) {
            log.warn("Invalid option number {} for access check, valid range 1-{}",
                    optionNumber, MENU_OPTIONS.size());
            throw new IllegalArgumentException(
                    "Please enter a valid option number (1-" + MENU_OPTIONS.size()
                            + "), received: " + optionNumber);
        }

        MenuOption option = MENU_OPTIONS.get(optionNumber - 1);

        // COMEN01C.cbl lines 136-143:
        // IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A' → deny
        // Admin users always have access; regular users are denied admin-only options
        if (userType == UserType.USER && "A".equals(option.requiredUserType())) {
            log.warn("Access denied: userType={} attempted to access admin-only option {} ('{}')",
                    userType, optionNumber, option.optionName());
            return false;
        }

        log.info("Access granted: userType={} for option {} ('{}')",
                userType, optionNumber, option.optionName());
        return true;
    }
}
