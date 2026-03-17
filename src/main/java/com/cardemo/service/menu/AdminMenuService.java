/*
 * AdminMenuService.java — Admin Menu Routing Metadata Service
 *
 * Migrated from COBOL source artifacts:
 *   - app/cbl/COADM01C.cbl (268 lines — Admin Menu CICS program)
 *   - app/cpy/COADM02Y.cpy (52 lines — Admin Menu Options compile-time VALUE table)
 *   - app/cpy/COCOM01Y.cpy (COMMAREA — CDEMO-USER-TYPE routing context)
 *
 * In the original COBOL application, the admin menu (COADM01C) displayed 4
 * user-management options exclusively to admin-type users. Users were routed
 * here from COSGN00C only when CDEMO-USRTYP-ADMIN ('A') was set in the
 * COMMAREA. Each option routed via CICS XCTL to COUSR00C–COUSR03C programs.
 *
 * In the Java migration, this stateless service provides admin menu option
 * metadata consumed by MenuController. Security enforcement (admin-only
 * access) is handled at the Spring Security / controller layer, mirroring
 * how COSGN00C restricted routing in the COBOL architecture.
 *
 * COBOL Paragraph → Java Method Traceability:
 *   BUILD-MENU-OPTIONS (lines 226-263)  → getAdminMenuOptions()
 *   PROCESS-ENTER-KEY  (lines 115-155)  → getAdminMenuOption(int)
 *   CDEMO-ADMIN-OPT-COUNT VALUE 4       → getOptionCount()
 *   Admin routing in COSGN00C           → isAdminOnly()
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
 * Provides admin menu routing metadata for the CardDemo application.
 *
 * <p>This service is the Java equivalent of the compile-time VALUE table defined in
 * {@code COADM02Y.cpy} and the menu-building/validation logic in {@code COADM01C.cbl}.
 * It holds exactly 4 admin menu options corresponding to the user management features
 * (User List, User Add, User Update, User Delete), each mapping to its original COBOL
 * program name for traceability and its REST API endpoint for runtime routing.</p>
 *
 * <p>Key differences from {@code MainMenuService}:</p>
 * <ul>
 *   <li>4 options (not 10) — mirrors {@code CDEMO-ADMIN-OPT-COUNT = 4}</li>
 *   <li>No per-option user type field — {@code COADM02Y.cpy} has only 3 fields
 *       (num, name, pgmname) vs. the main menu's 4-field structure</li>
 *   <li>No user type filtering — {@code COADM01C.cbl PROCESS-ENTER-KEY} does not
 *       check user type (admin-only access is enforced at the routing level)</li>
 *   <li>Admin-only access — enforced by Spring Security role-based access control,
 *       mirroring how {@code COSGN00C} routes only admin users to {@code COADM01C}</li>
 * </ul>
 *
 * <p>This service has no repository or external service dependencies — it is a pure
 * metadata provider with static, unmodifiable option data initialized at class load
 * time, exactly mirroring the COBOL compile-time VALUE table semantics.</p>
 *
 * @see UserType#ADMIN
 */
@Service
public class AdminMenuService {

    private static final Logger log = LoggerFactory.getLogger(AdminMenuService.class);

    /**
     * Represents a single admin menu option, mirroring one entry in the
     * {@code CDEMO-ADMIN-OPT} OCCURS table from {@code COADM02Y.cpy}.
     *
     * <p>Field mapping from COBOL:</p>
     * <ul>
     *   <li>{@code optionNumber} ← {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)}</li>
     *   <li>{@code optionName}   ← {@code CDEMO-ADMIN-OPT-NAME PIC X(35)} (trimmed)</li>
     *   <li>{@code cobolProgram} ← {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)}</li>
     *   <li>{@code apiEndpoint}  — REST API route replacing CICS XCTL program dispatch</li>
     * </ul>
     *
     * <p>Unlike the main menu's option record, there is no {@code requiredUserType}
     * field because the admin menu options table ({@code COADM02Y.cpy}) does not
     * include a user type column — admin-only access is inherent to the menu itself.</p>
     *
     * @param optionNumber the 1-based option number (1–4), from {@code CDEMO-ADMIN-OPT-NUM}
     * @param optionName   the display name trimmed of trailing COBOL spaces, from
     *                     {@code CDEMO-ADMIN-OPT-NAME}
     * @param cobolProgram the original COBOL program identifier for traceability, from
     *                     {@code CDEMO-ADMIN-OPT-PGMNAME}
     * @param apiEndpoint  the REST API endpoint path that replaces CICS XCTL routing
     */
    public record AdminMenuOption(
            int optionNumber,
            String optionName,
            String cobolProgram,
            String apiEndpoint
    ) {
    }

    /**
     * The total number of active admin menu options, matching
     * {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} from {@code COADM02Y.cpy} line 20.
     *
     * <p>While the COBOL table has capacity for 9 entries ({@code OCCURS 9 TIMES}),
     * only 4 are populated with non-filler data. This constant reflects the active count.</p>
     */
    private static final int ADMIN_OPTION_COUNT = 4;

    /**
     * The required user type for accessing the admin menu, documenting that only
     * {@link UserType#ADMIN} users are routed to this menu.
     *
     * <p>In COBOL, {@code COSGN00C} checks {@code CDEMO-USRTYP-ADMIN VALUE 'A'}
     * before routing to {@code COADM01C}. In Java, this is enforced by Spring Security
     * role-based access control at the controller layer.</p>
     */
    @SuppressWarnings("unused") // Referenced for documentation and consistency with MainMenuService pattern
    private static final UserType REQUIRED_USER_TYPE = UserType.ADMIN;

    /**
     * Static unmodifiable list of all 4 admin menu options, exactly mirroring the
     * compile-time VALUE table from {@code COADM02Y.cpy} lines 22–42.
     *
     * <p>Each entry preserves the exact option names from the COBOL copybook (trimmed
     * of trailing spaces from the {@code PIC X(35)} fields) and maps the original COBOL
     * program names to their REST API endpoint equivalents.</p>
     *
     * <p>Option-to-endpoint mapping:</p>
     * <ul>
     *   <li>Option 1: "User List (Security)" — COUSR00C → GET /api/admin/users</li>
     *   <li>Option 2: "User Add (Security)" — COUSR01C → POST /api/admin/users</li>
     *   <li>Option 3: "User Update (Security)" — COUSR02C → PUT /api/admin/users/{id}</li>
     *   <li>Option 4: "User Delete (Security)" — COUSR03C → DELETE /api/admin/users/{id}</li>
     * </ul>
     */
    private static final List<AdminMenuOption> ADMIN_MENU_OPTIONS = List.of(
            new AdminMenuOption(1, "User List (Security)", "COUSR00C", "/api/admin/users"),
            new AdminMenuOption(2, "User Add (Security)", "COUSR01C", "/api/admin/users"),
            new AdminMenuOption(3, "User Update (Security)", "COUSR02C", "/api/admin/users/{id}"),
            new AdminMenuOption(4, "User Delete (Security)", "COUSR03C", "/api/admin/users/{id}")
    );

    /**
     * Constructs the {@code AdminMenuService}.
     *
     * <p>No dependencies are injected — this is a pure metadata service. The admin
     * menu options are statically defined at class load time, mirroring the COBOL
     * compile-time VALUE table semantics of {@code COADM02Y.cpy}.</p>
     */
    public AdminMenuService() {
        log.info("AdminMenuService initialized with {} admin menu options (COADM02Y.cpy migration)",
                ADMIN_OPTION_COUNT);
    }

    /**
     * Returns the complete list of admin menu options.
     *
     * <p>Mirrors the {@code BUILD-MENU-OPTIONS} paragraph in {@code COADM01C.cbl}
     * (lines 226–263), which iterates from 1 to {@code CDEMO-ADMIN-OPT-COUNT} and
     * builds display strings for each option. In Java, the static list is returned
     * directly — no runtime string concatenation is needed since the REST API serves
     * structured JSON rather than BMS screen fields.</p>
     *
     * @return an unmodifiable list of all 4 admin menu options; never {@code null}
     */
    public List<AdminMenuOption> getAdminMenuOptions() {
        log.info("Retrieving all {} admin menu options", ADMIN_OPTION_COUNT);
        return ADMIN_MENU_OPTIONS;
    }

    /**
     * Returns a single admin menu option by its 1-based option number.
     *
     * <p>Mirrors the {@code PROCESS-ENTER-KEY} paragraph validation in
     * {@code COADM01C.cbl} (lines 127–134):</p>
     * <pre>
     *   IF WS-OPTION IS NOT NUMERIC OR
     *      WS-OPTION &gt; CDEMO-ADMIN-OPT-COUNT OR
     *      WS-OPTION = ZEROS
     *       MOVE 'Please enter a valid option number...' TO WS-MESSAGE
     * </pre>
     *
     * <p>In the COBOL program, invalid input triggers the error message
     * "Please enter a valid option number..." displayed on the BMS screen.
     * In Java, an {@link IllegalArgumentException} is thrown with the same
     * message text for behavioral parity.</p>
     *
     * @param optionNumber the 1-based option number (valid range: 1 to 4)
     * @return the matching {@code AdminMenuOption}
     * @throws IllegalArgumentException if {@code optionNumber} is less than 1 or
     *                                  greater than {@value #ADMIN_OPTION_COUNT}
     */
    public AdminMenuOption getAdminMenuOption(int optionNumber) {
        if (optionNumber < 1 || optionNumber > ADMIN_OPTION_COUNT) {
            log.warn("Invalid admin menu option requested: {} (valid range: 1-{})",
                    optionNumber, ADMIN_OPTION_COUNT);
            throw new IllegalArgumentException(
                    "Please enter a valid option number... (valid range: 1-" + ADMIN_OPTION_COUNT + ")");
        }
        AdminMenuOption option = ADMIN_MENU_OPTIONS.get(optionNumber - 1);
        log.info("Admin menu option {} selected: '{}' -> {} (COBOL: {})",
                optionNumber, option.optionName(), option.apiEndpoint(), option.cobolProgram());
        return option;
    }

    /**
     * Returns the total number of active admin menu options.
     *
     * <p>Mirrors {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} from
     * {@code COADM02Y.cpy} line 20. While the COBOL table has capacity for 9
     * entries ({@code OCCURS 9 TIMES}), only 4 are populated with real option data.</p>
     *
     * @return 4, the number of active admin menu options
     */
    public int getOptionCount() {
        return ADMIN_OPTION_COUNT;
    }

    /**
     * Indicates whether this menu is restricted to admin-type users only.
     *
     * <p>Always returns {@code true} for the admin menu. In the COBOL architecture,
     * only users with {@code CDEMO-USRTYP-ADMIN VALUE 'A'} (i.e., {@link UserType#ADMIN})
     * are routed to the admin menu program {@code COADM01C} by the sign-on program
     * {@code COSGN00C}. In the Java migration, this restriction is enforced by Spring
     * Security role-based access control at the controller/security configuration layer.</p>
     *
     * <p>Note that unlike the main menu ({@code COMEN01C}), the admin menu program
     * {@code COADM01C} does NOT perform an explicit user type check in its
     * {@code PROCESS-ENTER-KEY} paragraph — the admin-only restriction is inherent
     * to the routing architecture rather than the menu program itself.</p>
     *
     * @return {@code true} always — admin menu is exclusively for admin users
     */
    public boolean isAdminOnly() {
        return true;
    }
}
