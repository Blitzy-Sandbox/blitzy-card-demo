package com.carddemo.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.carddemo.dto.MenuOption;
import com.carddemo.dto.MenuResponse;
import com.carddemo.exception.ValidationException;

/**
 * Role-scoped menu provider and option-selection validator for the CardDemo
 * application.
 *
 * <p>This {@code @Service} migrates the two CICS menu programs and their option
 * tables into a single stateless component:</p>
 * <ul>
 *   <li><b>Main menu</b> &mdash; transaction {@code CM00}, program {@code COMEN01C}
 *       ({@code app/cbl/COMEN01C.cbl}). The ten selectable options come from the
 *       option table copybook {@code COMEN02Y}
 *       ({@code CARDDEMO-MAIN-MENU-OPTIONS} / {@code CDEMO-MENU-OPT},
 *       {@code CDEMO-MENU-OPT-COUNT = 10}). Every active option carries the
 *       user-type flag {@code 'U'} (regular user).</li>
 *   <li><b>Admin menu</b> &mdash; transaction {@code CA00}, program {@code COADM01C}
 *       ({@code app/cbl/COADM01C.cbl}). The four selectable options come from the
 *       option table copybook {@code COADM02Y}
 *       ({@code CARDDEMO-ADMIN-MENU-OPTIONS} / {@code CDEMO-ADMIN-OPT},
 *       {@code CDEMO-ADMIN-OPT-COUNT = 4}).</li>
 * </ul>
 *
 * <p><b>Static option tables.</b> The legacy option tables were declared as
 * {@code FILLER ... VALUE} groups in the copybooks and redefined as indexed
 * {@code OCCURS} tables. They are constant, compile-time data, so they are
 * reproduced here as immutable {@link java.util.List} constants built with
 * {@link java.util.List#of(Object...) List.of}. Because the data never changes at
 * runtime, this service injects no collaborators and holds no mutable state; it
 * is therefore inherently thread-safe.</p>
 *
 * <p><b>Admin option user type.</b> The admin option table {@code COADM02Y} has no
 * {@code USRTYPE} field (unlike {@code COMEN02Y}), because every admin option is
 * implicitly administrator-scoped &mdash; the whole {@code CA00} transaction is
 * reachable only by administrators. To preserve that visibility semantics
 * explicitly in the migrated model, each admin option is assigned the user-type
 * flag {@code 'A'}. This lets the shared {@link MenuOption} shape describe both
 * menus and keeps the role guard in {@link #resolveOption(String, int)} uniform.</p>
 *
 * <p><b>Selection logic parity.</b> {@link #resolveOption(String, int)} reproduces
 * the {@code PROCESS-ENTER-KEY} paragraph of {@code COMEN01C}
 * ({@code app/cbl/COMEN01C.cbl}, lines 115-165): the entered option must be a
 * number within {@code 1..count} and must not be zero, otherwise the verbatim
 * message {@code "Please enter a valid option number..."} is raised; a regular
 * user selecting an option flagged administrator-only ({@code USRTYPE 'A'})
 * receives the verbatim message {@code "No access - Admin Only option... "} (note
 * the trailing space). The corresponding {@code COADM01C} paragraph performs the
 * same range check against {@code CDEMO-ADMIN-OPT-COUNT} but omits the admin-only
 * guard, since the admin transaction is already role-gated.</p>
 *
 * <p><b>Stateless routing.</b> The COBOL programs finished a valid selection by
 * transferring control to the option's target program via
 * {@code EXEC CICS XCTL PROGRAM(...)} while carrying the pseudo-conversational
 * {@code COMMAREA}. In the migrated system there is no {@code COMMAREA} and no
 * transfer of control here: {@link #resolveOption(String, int)} simply returns the
 * resolved {@link MenuOption}, whose {@link MenuOption#targetProgram() targetProgram}
 * still names the original COBOL program (for example {@code COACTVWC}). The web
 * layer ({@code MenuController}) decides how to act on that resolution. Message
 * rationale and the paragraph&rarr;method mapping live in {@code docs/decision-log.md}
 * and {@code docs/traceability-matrix.md}, not in code comments.</p>
 *
 * <p>Frozen COBOL reference SHA {@code 27d6c6f}.</p>
 *
 * @see MenuOption
 * @see MenuResponse
 * @see com.carddemo.exception.ValidationException
 */
@Service
public class MenuService {

    /**
     * Role token identifying an administrator. A caller whose role equals this
     * value (case-sensitive, matching the fixed-width {@code 'A'} semantics of the
     * legacy {@code CDEMO-USRTYP-ADMIN} condition) receives the admin menu and
     * bypasses the administrator-only option guard.
     */
    private static final String ROLE_ADMIN = "ADMIN";

    /**
     * User-type flag marking an option as administrator-only, mirroring the COBOL
     * {@code CDEMO-MENU-OPT-USRTYPE} value {@code 'A'}.
     */
    private static final String USRTYPE_ADMIN = "A";

    /**
     * Menu discriminator for the main (regular-user) menu, surfaced as
     * {@link MenuResponse#menuType()}.
     */
    private static final String MENU_TYPE_MAIN = "MAIN";

    /**
     * Menu discriminator for the administrator menu, surfaced as
     * {@link MenuResponse#menuType()}.
     */
    private static final String MENU_TYPE_ADMIN = "ADMIN";

    /** Display title for the main menu (legacy screen shell {@code COMEN01}). */
    private static final String TITLE_MAIN = "Main Menu";

    /** Display title for the admin menu (legacy screen shell {@code COADM01}). */
    private static final String TITLE_ADMIN = "Admin Menu";

    /**
     * Verbatim message raised when the selected option is not a number, is zero,
     * or exceeds the option count for the active menu.
     *
     * <p>COBOL origin: {@code COMEN01C}/{@code COADM01C} {@code PROCESS-ENTER-KEY}
     * ({@code 'Please enter a valid option number...'}). Preserved character-for-character
     * for behavioral parity (goal G1).</p>
     */
    private static final String MSG_INVALID_OPTION = "Please enter a valid option number...";

    /**
     * Verbatim message raised when a regular user selects an administrator-only
     * option.
     *
     * <p>COBOL origin: {@code COMEN01C} {@code PROCESS-ENTER-KEY}
     * ({@code 'No access - Admin Only option... '}). The <em>trailing space</em> is
     * intentional and preserved character-for-character for behavioral parity
     * (goal G1).</p>
     */
    private static final String MSG_ADMIN_ONLY = "No access - Admin Only option... ";

    /**
     * The immutable main-menu option table, transcribed from {@code COMEN02Y}
     * ({@code CDEMO-MENU-OPTIONS-DATA}). Order is the 1-based menu order; every
     * option is user-type {@code 'U'}. Option 8's alternate label
     * {@code 'Transaction Add (Admin Only)'} is commented out in the source, so the
     * active label {@code 'Transaction Add'} is used.
     */
    private static final List<MenuOption> MAIN_MENU = List.of(
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
     * The immutable admin-menu option table, transcribed from {@code COADM02Y}
     * ({@code CDEMO-ADMIN-OPTIONS-DATA}). Order is the 1-based menu order. The
     * source table has no user-type field; each option is assigned {@code 'A'} to
     * make its administrator-only visibility explicit (see class documentation).
     */
    private static final List<MenuOption> ADMIN_MENU = List.of(
            new MenuOption(1, "User List (Security)", "COUSR00C", "A"),
            new MenuOption(2, "User Add (Security)", "COUSR01C", "A"),
            new MenuOption(3, "User Update (Security)", "COUSR02C", "A"),
            new MenuOption(4, "User Delete (Security)", "COUSR03C", "A"));

    /**
     * Creates the stateless menu service. No initialization is required because the
     * menu tables are immutable compile-time constants and no collaborators are
     * injected.
     */
    public MenuService() {
        // Intentionally empty: this service holds no mutable state.
    }

    /**
     * Returns the regular-user main menu ({@code CM00} / {@code COMEN01C}).
     *
     * @return a {@link MenuResponse} of type {@code "MAIN"} carrying the ten main-menu
     *         options in their declared order; never {@code null}
     */
    public MenuResponse getMainMenu() {
        return new MenuResponse(MENU_TYPE_MAIN, TITLE_MAIN, MAIN_MENU);
    }

    /**
     * Returns the administrator menu ({@code CA00} / {@code COADM01C}).
     *
     * @return a {@link MenuResponse} of type {@code "ADMIN"} carrying the four admin-menu
     *         options in their declared order; never {@code null}
     */
    public MenuResponse getAdminMenu() {
        return new MenuResponse(MENU_TYPE_ADMIN, TITLE_ADMIN, ADMIN_MENU);
    }

    /**
     * Returns the menu appropriate to the supplied role: the admin menu for an
     * administrator, otherwise the main menu.
     *
     * <p>This convenience method backs role-based routing in the web layer. The role
     * comparison is null-safe and case-sensitive: any value other than the exact
     * token {@code "ADMIN"} (including {@code null}) yields the main menu, mirroring
     * the legacy default where only an administrator role reaches {@code CA00}.</p>
     *
     * @param role the caller's role token (for example {@code "ADMIN"} or
     *             {@code "USER"}); may be {@code null}
     * @return the {@link MenuResponse} for the role; never {@code null}
     */
    public MenuResponse getMenuForRole(String role) {
        return ROLE_ADMIN.equals(role) ? getAdminMenu() : getMainMenu();
    }

    /**
     * Resolves and authorizes a menu selection, reproducing the
     * {@code PROCESS-ENTER-KEY} logic of {@code COMEN01C} (and the range check of
     * {@code COADM01C}).
     *
     * <p>The option is resolved against the menu appropriate to {@code role} (the
     * admin menu for an administrator, otherwise the main menu), matching the legacy
     * behavior where each menu program validated the entry against its own option
     * count ({@code CDEMO-MENU-OPT-COUNT} / {@code CDEMO-ADMIN-OPT-COUNT}). The steps
     * are:</p>
     * <ol>
     *   <li><b>Range check</b> &mdash; {@code optionNumber} must fall within
     *       {@code 1..size} of the active menu (and thus be non-zero); otherwise a
     *       {@link ValidationException} carrying {@code "Please enter a valid option number..."}
     *       is thrown. This maps the COBOL guard
     *       {@code WS-OPTION IS NOT NUMERIC OR WS-OPTION > count OR WS-OPTION = ZEROS}.</li>
     *   <li><b>Administrator-only guard</b> &mdash; if the resolved option is flagged
     *       {@code 'A'} and the caller is not an administrator, a
     *       {@link ValidationException} carrying {@code "No access - Admin Only option... "}
     *       (trailing space preserved) is thrown. This maps the COBOL guard
     *       {@code CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'}.</li>
     *   <li><b>Success</b> &mdash; the resolved {@link MenuOption} is returned; its
     *       {@link MenuOption#targetProgram() targetProgram} is the destination the
     *       legacy program would {@code XCTL} to.</li>
     * </ol>
     *
     * <p>With the current data the administrator-only guard never fires through this
     * method (the main menu holds no {@code 'A'} options, and an administrator role
     * resolves the admin menu), exactly as in the frozen COBOL where option 8 is a
     * regular-user option. The guard is nonetheless preserved so the migrated
     * mechanism stays faithful should an administrator-only entry be reintroduced.</p>
     *
     * @param role         the caller's role token (for example {@code "ADMIN"} or
     *                     {@code "USER"}); may be {@code null}, treated as a
     *                     non-administrator
     * @param optionNumber the 1-based option number the caller selected
     * @return the resolved {@link MenuOption}; never {@code null}
     * @throws ValidationException if {@code optionNumber} is outside {@code 1..size}
     *                             of the active menu, or if a non-administrator
     *                             selects an administrator-only option
     */
    public MenuOption resolveOption(String role, int optionNumber) {
        List<MenuOption> menu = ROLE_ADMIN.equals(role) ? ADMIN_MENU : MAIN_MENU;

        // COBOL: WS-OPTION > count OR WS-OPTION = ZEROS (and non-numeric, which a
        // parsed int cannot represent) -> invalid option number.
        if (optionNumber < 1 || optionNumber > menu.size()) {
            throw new ValidationException(MSG_INVALID_OPTION);
        }

        MenuOption option = menu.get(optionNumber - 1);

        // COBOL: CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'.
        if (USRTYPE_ADMIN.equals(option.userType()) && !ROLE_ADMIN.equals(role)) {
            throw new ValidationException(MSG_ADMIN_ONLY);
        }

        return option;
    }
}
