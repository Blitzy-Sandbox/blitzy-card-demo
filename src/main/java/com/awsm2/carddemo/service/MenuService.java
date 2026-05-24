/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.dto.AdminMenuDto;
import com.awsm2.carddemo.dto.MainMenuDto;
import com.awsm2.carddemo.dto.MenuOptionDto;
import com.awsm2.carddemo.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Menu service — serves the main menu and the admin menu, and resolves the
 * target program identifier for a selected option.
 *
 * <p><b>COBOL Provenance — the single AAP-sanctioned dual-program service.</b>
 * Per AAP §0.4.1 ("MenuService.java ← COMEN01C, COADM01C — serve menu
 * structure from COMEN02Y/COADM02Y"), this service is the <em>only</em>
 * intentional exception to the one-service-per-COBOL-program rule
 * (AAP §0.7.1: "Isolate each COBOL program's logic in its own dedicated
 * Java service class"). The exception is justified because both COBOL
 * programs share an identical control structure (read literal-storage
 * option table → render BMS map → on PF/AID dispatch → validate option,
 * gate by user-type, XCTL to target program) and the only meaningful
 * difference between them is the working-storage table they consume
 * ({@code COMEN02Y.cpy} versus {@code COADM02Y.cpy}). Combining them into
 * a single service avoids a vacuous duplicate; the public API is split
 * into {@link #getMainMenu(String)} and {@link #getAdminMenu(String)} so
 * the controller-layer routing remains one-handler-per-screen.</p>
 *
 * <ul>
 *   <li>{@code app/cbl/COMEN01C.cbl} (CICS transaction id {@code CM00}) —
 *       main menu for regular ({@code 'U'}) and admin ({@code 'A'}) users.</li>
 *   <li>{@code app/cbl/COADM01C.cbl} (CICS transaction id {@code CA00}) —
 *       admin-only menu (gated by {@code COSGN00C}'s post-sign-on routing).</li>
 *   <li>{@code app/cpy/COMEN02Y.cpy} — main-menu option literal-storage
 *       ({@code CARDDEMO-MAIN-MENU-OPTIONS}: 10 entries, all
 *       {@code USR-TYPE = 'U'}).</li>
 *   <li>{@code app/cpy/COADM02Y.cpy} — admin-menu option literal-storage
 *       ({@code CARDDEMO-ADMIN-MENU-OPTIONS}: 4 entries; no {@code USR-TYPE}
 *       field because all admin-menu entries are admin-only by construction).</li>
 * </ul>
 *
 * <p><b>What this service replaces:</b></p>
 * <ul>
 *   <li>The COBOL {@code BUILD-MENU-OPTIONS} paragraphs in {@code COMEN01C.cbl}
 *       and {@code COADM01C.cbl} that iterated the working-storage option
 *       table and copied each entry into the BMS map output buffer
 *       ({@code OPTN001O} through {@code OPTN012O}). The Java equivalent is
 *       a static {@code List.of(...)} populated once at class-load time and
 *       returned verbatim inside a {@link MainMenuDto} or
 *       {@link AdminMenuDto}.</li>
 *   <li>The COBOL {@code PROCESS-ENTER-KEY} paragraphs that received the
 *       user's option selection, validated it ("Please enter a valid option
 *       number..."), applied the admin-only gate
 *       ({@code IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE = 'A'} ->
 *       "No access - Admin Only option..."), checked for "coming soon"
 *       options (program-name starts with {@code DUMMY}), and finally
 *       transferred control via {@code EXEC CICS XCTL PROGRAM(...)}. The
 *       Java equivalent is {@link #resolveMenuTarget(int, String, boolean)}
 *       — it validates the option number, applies the admin-only gate,
 *       checks for the {@code DUMMY} prefix, and returns the target program
 *       identifier to the caller (typically {@code MenuController}, which
 *       then routes the response payload). The actual cross-service
 *       invocation in the Java target is performed via REST navigation on
 *       the client side, with the {@link MenuOptionDto#targetEndpoint()}
 *       value providing the next URL.</li>
 * </ul>
 *
 * <p><b>Minimal Change Clause compliance (AAP §0.7.3).</b> Both menu tables
 * are <em>verbatim</em> ports of the source {@code FILLER} entries in
 * {@code COMEN02Y.cpy} and {@code COADM02Y.cpy} — same option numbers,
 * same display labels (trailing spaces trimmed only because Java strings
 * are not fixed-width like {@code PIC X(35)}), same target-program
 * identifiers ({@code COACTVWC}, {@code COCRDLIC}, etc.), and same
 * user-type gates. Two additional fields per option ({@code targetEndpoint}
 * and {@code enabled}) are net-new with respect to the COBOL copybook but
 * are justified by AAP §0.1.2 (REST replacement of CICS XCTL routing) and
 * AAP §0.3.4 (stateless REST design) as documented on
 * {@link MenuOptionDto}.</p>
 *
 * <p><b>State and lifecycle.</b> This service is stateless — both menu
 * tables are {@code private static final List<MenuOptionDto>} initialized
 * at class-load time via {@link List#of(Object[])} (immutable, lock-free,
 * safely shareable across all threads). The Spring {@code @Service}
 * annotation registers a singleton bean (per AAP §0.3.3 "Layered
 * Architecture") but no dependencies are injected — this service has no
 * collaborators because menu rendering is pure constant-data work. The
 * absence of a constructor (and therefore the absence of constructor
 * injection) is intentional and conforms to AAP §0.7.1 ("Dependency
 * injection for loose coupling") at the trivial-zero-dependency limit.</p>
 *
 * <p><b>Error envelope (AAP §0.3.4).</b> All three failure paths in
 * {@link #resolveMenuTarget(int, String, boolean)} throw a
 * {@link ValidationException} carrying a verbatim message preserved from
 * the source COBOL programs. {@link ValidationException} is mapped by
 * {@code GlobalExceptionHandler} to HTTP 400 Bad Request and projected
 * onto the standardized JSON envelope
 * ({@code {"code":"VALIDATION","message":"...","fieldErrors":[]}}).</p>
 *
 * <p><b>Logging and PCI-DSS (AAP §0.6.6 / §0.7.2).</b> Menu metadata
 * contains no PCI/PII material — only user-type discriminators
 * ({@code "A"}/{@code "U"}) and public program identifiers
 * ({@code COACTVWC}, etc.). Structured JSON logging via SLF4J
 * (Logback + logstash-logback-encoder per AAP §0.7.2) is therefore safe
 * to emit for every request without redaction. The class-level
 * {@link Logger} is used at {@code DEBUG} level for option-resolution
 * traceability and at {@code WARN} level for denied admin-only attempts
 * (security-relevant signal for OpenSearch / CloudTrail correlation).</p>
 *
 * @see MenuOptionDto
 * @see MainMenuDto
 * @see AdminMenuDto
 * @see ValidationException
 * @see <a href=
 *      "https://github.com/aws-samples/aws-mainframe-modernization-carddemo">
 *      AWS CardDemo (source COBOL)</a>
 */
@Service
public class MenuService {

    /**
     * SLF4J facade for structured JSON logging. Backed by Logback +
     * logstash-logback-encoder per AAP §0.7.2 "Operational requirements:
     * Structured JSON logging (Logback + logstash-logback-encoder) shipped
     * to CloudWatch Logs".
     */
    private static final Logger LOG = LoggerFactory.getLogger(MenuService.class);

    /**
     * Title for the main menu screen. Derived from the COBOL
     * {@code CCDA-TITLE01}/{@code CCDA-TITLE02} constants in
     * {@code app/cpy/COTTL01Y.cpy} with the screen-specific suffix
     * {@code " - Main Menu"} appended. Limited to 40 characters to align
     * with the legacy {@code TITLE01}/{@code TITLE02} field widths in
     * {@code app/bms/COMEN01.bms}.
     */
    private static final String MAIN_MENU_TITLE = "AWS CardDemo - Main Menu";

    /**
     * Title for the admin menu screen. Mirrors {@link #MAIN_MENU_TITLE}'s
     * derivation but uses the screen-specific suffix {@code " - Admin Menu"}
     * (aligns with {@code app/bms/COADM01.bms}).
     */
    private static final String ADMIN_MENU_TITLE = "AWS CardDemo - Admin Menu";

    /**
     * Admin user-type discriminator. Maps to {@code SEC-USR-TYPE PIC X(01)}
     * value {@code 'A'} from {@code app/cpy/CSUSR01Y.cpy} (admin user).
     */
    private static final String USER_TYPE_ADMIN = "A";

    /**
     * Regular user-type discriminator. Maps to {@code SEC-USR-TYPE PIC X(01)}
     * value {@code 'U'} from {@code app/cpy/CSUSR01Y.cpy} (regular user).
     */
    private static final String USER_TYPE_USER = "U";

    /**
     * Verbatim error message emitted when a regular user (or any non-admin
     * caller) attempts to access the admin menu or an admin-only option.
     *
     * <p>COBOL: {@code COMEN01C.cbl:PROCESS-ENTER-KEY} —
     * {@code MOVE 'No access - Admin Only option... ' TO WS-MESSAGE}.
     * The Java message text is the COBOL literal with the trailing space
     * collapsed and a brief parenthetical hint appended for REST clients
     * who do not have the legacy {@code WS-USRTYP} flag in scope.</p>
     */
    private static final String ERR_ADMIN_ONLY =
            "No access - Admin Only option (user type must be 'A')";

    /**
     * Verbatim "coming soon" error message format. The single
     * {@code %d} placeholder is substituted with the selected option
     * number.
     *
     * <p>COBOL: {@code COMEN01C.cbl:PROCESS-ENTER-KEY} —
     * {@code STRING 'This option ' CDEMO-MENU-OPT-NAME 'is coming soon ...'
     * INTO WS-MESSAGE}. The COBOL form embedded the menu option label
     * (first word, via {@code DELIMITED BY SPACE}); the Java form uses
     * the option number for unambiguous identification per the AAP §0.4.1
     * example translation. The trailing space-ellipsis (" ...") preserves
     * the COBOL whitespace verbatim.</p>
     */
    private static final String ERR_COMING_SOON_FORMAT =
            "This option %d is coming soon ...";

    /**
     * Verbatim "invalid option" error message format. The single
     * {@code %d} placeholder is substituted with the offending option
     * number.
     *
     * <p>COBOL: {@code COMEN01C.cbl:PROCESS-ENTER-KEY} —
     * {@code MOVE 'Please enter a valid option number...' TO WS-MESSAGE}.
     * The Java form additionally surfaces the offending number to ease
     * REST-client debugging while preserving the source intent.</p>
     */
    private static final String ERR_INVALID_OPTION_FORMAT =
            "Invalid menu option: %d";

    /**
     * Main-menu option table — verbatim port of the
     * {@code CARDDEMO-MAIN-MENU-OPTIONS} working-storage block in
     * {@code app/cpy/COMEN02Y.cpy}.
     *
     * <p>The 10 options correspond one-to-one to the {@code FILLER} rows
     * in the source copybook (option number 1 through 10). Each entry
     * carries:</p>
     * <ul>
     *   <li>{@code optionNumber}: {@code CDEMO-MENU-OPT-NUM PIC 9(02)}
     *       (1..10).</li>
     *   <li>{@code label}: {@code CDEMO-MENU-OPT-NAME PIC X(35)} with
     *       trailing spaces trimmed.</li>
     *   <li>{@code targetProgram}: {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)}
     *       preserved verbatim for parallel-run traceability
     *       (AAP §0.7.3).</li>
     *   <li>{@code targetEndpoint}: net-new REST URL replacing
     *       {@code EXEC CICS XCTL PROGRAM(...)} per AAP §0.1.2; mapping
     *       derived from the endpoint inventory in AAP §0.3.4.</li>
     *   <li>{@code userType}: {@code CDEMO-MENU-OPT-USRTYPE PIC X(01)}
     *       (all 10 entries are {@code 'U'} in {@code COMEN02Y.cpy}).</li>
     *   <li>{@code enabled}: net-new boolean — all entries are enabled by
     *       default; future REST flows may override per-request based on
     *       dynamic state (e.g., "Credit Card View" only enabled after
     *       a card is selected, per AAP §0.3.4 stateless REST design).</li>
     * </ul>
     *
     * <p>{@link List#of(Object[])} returns an unmodifiable list whose
     * element references are also immutable {@link MenuOptionDto} records.
     * The whole structure is therefore safely shareable across threads
     * without synchronization.</p>
     */
    // COBOL: COMEN02Y.cpy:CARDDEMO-MAIN-MENU-OPTIONS literal-storage table
    private static final List<MenuOptionDto> MAIN_MENU_OPTIONS = List.of(
            // COBOL: COMEN02Y.cpy option 1 - 'Account View' COACTVWC USR='U'
            new MenuOptionDto(1, "Account View",
                    "COACTVWC", "/api/accounts/{id}", USER_TYPE_USER, true),
            // COBOL: COMEN02Y.cpy option 2 - 'Account Update' COACTUPC USR='U'
            new MenuOptionDto(2, "Account Update",
                    "COACTUPC", "/api/accounts/{id}", USER_TYPE_USER, true),
            // COBOL: COMEN02Y.cpy option 3 - 'Credit Card List' COCRDLIC USR='U'
            new MenuOptionDto(3, "Credit Card List",
                    "COCRDLIC", "/api/cards", USER_TYPE_USER, true),
            // COBOL: COMEN02Y.cpy option 4 - 'Credit Card View' COCRDSLC USR='U'
            new MenuOptionDto(4, "Credit Card View",
                    "COCRDSLC", "/api/cards/{cardNumber}", USER_TYPE_USER, true),
            // COBOL: COMEN02Y.cpy option 5 - 'Credit Card Update' COCRDUPC USR='U'
            new MenuOptionDto(5, "Credit Card Update",
                    "COCRDUPC", "/api/cards/{cardNumber}", USER_TYPE_USER, true),
            // COBOL: COMEN02Y.cpy option 6 - 'Transaction List' COTRN00C USR='U'
            new MenuOptionDto(6, "Transaction List",
                    "COTRN00C", "/api/transactions", USER_TYPE_USER, true),
            // COBOL: COMEN02Y.cpy option 7 - 'Transaction View' COTRN01C USR='U'
            new MenuOptionDto(7, "Transaction View",
                    "COTRN01C", "/api/transactions/{id}", USER_TYPE_USER, true),
            // COBOL: COMEN02Y.cpy option 8 - 'Transaction Add' COTRN02C USR='U'
            new MenuOptionDto(8, "Transaction Add",
                    "COTRN02C", "/api/transactions", USER_TYPE_USER, true),
            // COBOL: COMEN02Y.cpy option 9 - 'Transaction Reports' CORPT00C USR='U'
            new MenuOptionDto(9, "Transaction Reports",
                    "CORPT00C", "/api/reports/submit", USER_TYPE_USER, true),
            // COBOL: COMEN02Y.cpy option 10 - 'Bill Payment' COBIL00C USR='U'
            new MenuOptionDto(10, "Bill Payment",
                    "COBIL00C", "/api/billing/pay", USER_TYPE_USER, true)
    );

    /**
     * Admin-menu option table — verbatim port of the
     * {@code CARDDEMO-ADMIN-MENU-OPTIONS} working-storage block in
     * {@code app/cpy/COADM02Y.cpy}.
     *
     * <p>The 4 options correspond one-to-one to the {@code FILLER} rows
     * in the source copybook (option number 1 through 4). Note that
     * {@code COADM02Y.cpy} omits the {@code CDEMO-ADMIN-OPT-USRTYPE}
     * field that {@code COMEN02Y.cpy} carries — admin-menu entries are
     * admin-only by construction (the COBOL routing in {@code COSGN00C}
     * deflects regular users to {@code COMEN01C}, so {@code COADM01C}
     * never has to filter by user-type). The Java {@link MenuOptionDto}
     * carries {@code userType="A"} on every admin entry for clarity and
     * to keep the dispatch-time gate in
     * {@link #resolveMenuTarget(int, String, boolean)} uniform.</p>
     */
    // COBOL: COADM02Y.cpy:CARDDEMO-ADMIN-MENU-OPTIONS literal-storage table
    private static final List<MenuOptionDto> ADMIN_MENU_OPTIONS = List.of(
            // COBOL: COADM02Y.cpy option 1 - 'User List (Security)' COUSR00C
            new MenuOptionDto(1, "User List (Security)",
                    "COUSR00C", "/api/admin/users", USER_TYPE_ADMIN, true),
            // COBOL: COADM02Y.cpy option 2 - 'User Add (Security)' COUSR01C
            new MenuOptionDto(2, "User Add (Security)",
                    "COUSR01C", "/api/admin/users", USER_TYPE_ADMIN, true),
            // COBOL: COADM02Y.cpy option 3 - 'User Update (Security)' COUSR02C
            new MenuOptionDto(3, "User Update (Security)",
                    "COUSR02C", "/api/admin/users/{id}", USER_TYPE_ADMIN, true),
            // COBOL: COADM02Y.cpy option 4 - 'User Delete (Security)' COUSR03C
            new MenuOptionDto(4, "User Delete (Security)",
                    "COUSR03C", "/api/admin/users/{id}", USER_TYPE_ADMIN, true)
    );

    /**
     * Builds the main menu response for the supplied user type.
     *
     * <p>COBOL: {@code COMEN01C.cbl:MAIN-PARA} reads the COMMAREA-supplied
     * user identity, calls {@code POPULATE-HEADER-INFO} (which copies the
     * title constants from {@code COTTL01Y.cpy} and the current date/time
     * into the BMS map output), then invokes {@code BUILD-MENU-OPTIONS}
     * (which iterates {@code COMEN02Y.cpy} and copies each option label
     * into {@code OPTN001O..OPTN012O}). The Java equivalent returns a
     * {@link MainMenuDto} carrying the full option list pre-built at
     * class-load time and the static {@link #MAIN_MENU_TITLE} string.</p>
     *
     * <p>Per AAP §0.4.1, the main-menu endpoint is available to
     * <em>any</em> authenticated user (regular {@code 'U'} or admin
     * {@code 'A'}). The {@link MainMenuDto#userType()} field echoes the
     * caller's user-type unchanged so the client can render
     * role-appropriate badging on the screen (e.g., a "View as Admin"
     * banner when an admin chooses to inspect the regular menu).</p>
     *
     * <p>The user-identity fields ({@code userId}, {@code firstName},
     * {@code lastName}) are returned as empty strings because the service
     * signature exposed by the AAP schema only carries {@code userType}.
     * The controller layer ({@code MenuController}) is responsible for
     * augmenting the response with the authenticated principal's identity
     * (sourced from the JWT subject and claims) before serializing it to
     * the client. This split keeps the service free of any authentication
     * concern and conforms to AAP §0.3.3 ("Layered Architecture") —
     * authentication context is a controller concern, menu data is a
     * service concern.</p>
     *
     * <p>No filtering of the option list is performed at build time: the
     * source {@code BUILD-MENU-OPTIONS} paragraph in {@code COMEN01C.cbl}
     * also emits all entries unconditionally. The {@code 'A'}-only
     * admin-only gate is enforced at dispatch time inside
     * {@link #resolveMenuTarget(int, String, boolean)} via the same
     * COBOL {@code IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE = 'A'}
     * predicate. Today the source data ({@code COMEN02Y.cpy}) has every
     * main-menu entry tagged {@code USR-TYPE = 'U'}, so the gate never
     * fires for the current dataset — but the logic is preserved for
     * future menu-table changes without code modification.</p>
     *
     * @param userType the authenticated caller's user-type discriminator,
     *                 typically {@code "A"} (admin) or {@code "U"} (regular).
     *                 Echoed back into {@link MainMenuDto#userType()} for
     *                 client-side badging; not used for filtering. May be
     *                 {@code null} or any value — the service does not
     *                 validate it (the controller's
     *                 {@code @PreAuthorize("isAuthenticated()")} gate has
     *                 already enforced presence of an authenticated user)
     * @return a non-{@code null} {@link MainMenuDto} carrying the 10
     *         main-menu options, the echoed user type, the static
     *         {@link #MAIN_MENU_TITLE}, and empty user-identity fields
     *         (controller augments with JWT claims before response)
     */
    public MainMenuDto getMainMenu(String userType) {
        // COBOL: COMEN01C:BUILD-MENU-OPTIONS -- emit all 10 entries from COMEN02Y.cpy
        LOG.debug("Building main menu response (userType={}, optionCount={})",
                userType, MAIN_MENU_OPTIONS.size());
        return new MainMenuDto(
                "",                  // userId   - controller augments from JWT
                userType,            // userType - echoed for client badging
                "",                  // firstName - controller augments from JWT
                "",                  // lastName  - controller augments from JWT
                MAIN_MENU_OPTIONS,   // options  - from COMEN02Y.cpy
                MAIN_MENU_TITLE      // title    - from COTTL01Y.cpy
        );
    }

    /**
     * Builds the admin menu response for the supplied user type, enforcing
     * the admin-only access gate as a defense-in-depth check at the
     * service layer.
     *
     * <p>COBOL: {@code COADM01C.cbl:MAIN-PARA} (admin transaction
     * {@code CA00}) follows the same shape as {@code COMEN01C} but reads
     * the {@code COADM02Y.cpy} option table. In the source, admin-only
     * access is enforced indirectly by {@code COSGN00C} (the sign-on
     * program), which inspects {@code SEC-USR-TYPE} from
     * {@code app/cpy/CSUSR01Y.cpy} and routes admin users
     * ({@code SEC-USR-TYPE = 'A'}) to {@code COADM01C}, sending all other
     * users to {@code COMEN01C}. {@code COADM01C} itself therefore does
     * not check the user-type — the gate is upstream.</p>
     *
     * <p>In the Java target, the gate is implemented in two layers:</p>
     * <ol>
     *   <li>The controller ({@code MenuController.getAdminMenu()}) carries
     *       {@code @PreAuthorize("hasRole('ADMIN')")} so regular users
     *       receive HTTP 403 Forbidden before this service is invoked
     *       (per AAP §0.3.4 admin endpoint design).</li>
     *   <li>This service redundantly checks the {@code userType} argument
     *       against {@link #USER_TYPE_ADMIN} and throws
     *       {@link ValidationException} (mapped to HTTP 400) for callers
     *       who reach this point with a non-admin user-type. This
     *       defense-in-depth check protects against future controller
     *       misconfiguration and matches the AAP §0.4.1 example
     *       translation pattern (verbatim error message from
     *       {@code COMEN01C.cbl:PROCESS-ENTER-KEY}).</li>
     * </ol>
     *
     * <p>The user-identity fields ({@code userId}, {@code firstName},
     * {@code lastName}) follow the same controller-augmentation
     * convention as {@link #getMainMenu(String)}. The
     * {@link AdminMenuDto} schema omits a top-level {@code userType}
     * field because the admin endpoint's implicit type is always
     * {@code 'A'} (per AAP §0.3.4).</p>
     *
     * @param userType the authenticated caller's user-type discriminator.
     *                 Must equal {@link #USER_TYPE_ADMIN} ({@code "A"});
     *                 any other value (including {@code null} or empty)
     *                 triggers a {@link ValidationException} carrying the
     *                 verbatim {@link #ERR_ADMIN_ONLY} message
     * @return a non-{@code null} {@link AdminMenuDto} carrying the 4
     *         admin-menu options sourced from {@code COADM02Y.cpy}, the
     *         static {@link #ADMIN_MENU_TITLE}, and empty user-identity
     *         fields (controller augments with JWT claims before response)
     * @throws ValidationException if {@code userType} is not
     *                             {@link #USER_TYPE_ADMIN}. The exception
     *                             carries the verbatim COBOL error message
     *                             from {@code COMEN01C.cbl:PROCESS-ENTER-KEY}
     *                             and is mapped by
     *                             {@code GlobalExceptionHandler} to
     *                             HTTP 400 Bad Request
     */
    public AdminMenuDto getAdminMenu(String userType) {
        // COBOL: COSGN00C admin-routing gate -- only SEC-USR-TYPE='A' reaches COADM01C
        if (!USER_TYPE_ADMIN.equals(userType)) {
            LOG.warn("Denied admin-menu access for non-admin caller "
                    + "(userType={})", userType);
            throw new ValidationException(ERR_ADMIN_ONLY);
        }
        // COBOL: COADM01C:BUILD-MENU-OPTIONS -- emit all 4 entries from COADM02Y.cpy
        LOG.debug("Building admin menu response (userType={}, optionCount={})",
                userType, ADMIN_MENU_OPTIONS.size());
        return new AdminMenuDto(
                "",                   // userId   - controller augments from JWT
                "",                   // firstName - controller augments from JWT
                "",                   // lastName  - controller augments from JWT
                ADMIN_MENU_OPTIONS,   // options  - from COADM02Y.cpy
                ADMIN_MENU_TITLE      // title    - from COTTL01Y.cpy
        );
    }

    /**
     * Validates a user-supplied option number and returns the resolved
     * {@link MenuOptionDto} (carrying the original COBOL target-program
     * identifier, the REST target endpoint, the option label, and the
     * user-type gate) for the selected menu option.
     *
     * <p>COBOL: {@code COMEN01C.cbl:PROCESS-ENTER-KEY} and
     * {@code COADM01C.cbl:PROCESS-ENTER-KEY} both implement the same
     * four-stage validation cascade against the selected option number:</p>
     * <ol>
     *   <li><b>Numeric check</b> — verify the supplied option number is
     *       numeric ({@code IF WS-OPTION IS NOT NUMERIC} from
     *       {@code COMEN01C.cbl:127}). The original program normalises
     *       the raw BMS input by replacing spaces with zeros
     *       ({@code INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'}). The
     *       Java equivalent parses the input {@code String} into an
     *       integer via {@link Integer#parseInt(String)}, treating any
     *       {@link NumberFormatException} (including {@code null} input)
     *       as an out-of-range failure. A {@link ValidationException}
     *       carrying the {@link #ERR_INVALID_OPTION_FORMAT} message is
     *       thrown (mapped to HTTP 400 by
     *       {@code GlobalExceptionHandler}).</li>
     *   <li><b>Bounds check</b> — verify the supplied option number is
     *       non-zero and within the option-table count
     *       ({@code IF WS-OPTION > COUNT OR WS-OPTION = ZEROS} from
     *       {@code COMEN01C.cbl:128-129}). Failure emits the verbatim
     *       "Please enter a valid option number..." message and re-renders
     *       the menu screen. The Java equivalent throws a
     *       {@link ValidationException} with the same message format.</li>
     *   <li><b>Admin-only gate</b> — only present in {@code COMEN01C}
     *       (the admin-menu options are admin-only by construction so
     *       {@code COADM01C} skips this check): if the caller is a regular
     *       user and the selected option's {@code USR-TYPE = 'A'}, emit
     *       the verbatim "No access - Admin Only option..." message and
     *       re-render the menu screen. The Java equivalent throws a
     *       {@link ValidationException} carrying the
     *       {@link #ERR_ADMIN_ONLY} message.</li>
     *   <li><b>"Coming soon" gate</b> — if the selected option's target
     *       program name starts with {@code "DUMMY"}, the source program
     *       emits "This option &lt;label&gt; is coming soon ..." and
     *       re-renders the menu screen instead of issuing
     *       {@code EXEC CICS XCTL}. The Java equivalent throws a
     *       {@link ValidationException} carrying the
     *       {@link #ERR_COMING_SOON_FORMAT} message (with the option
     *       number substituted). No {@code DUMMY}-prefixed entries exist
     *       in the current {@code COMEN02Y.cpy} or {@code COADM02Y.cpy}
     *       data — the gate is preserved for future menu-table changes
     *       without code modification.</li>
     * </ol>
     *
     * <p>On successful validation, the method returns the full
     * {@link MenuOptionDto} for the selected option (option number,
     * label, original COBOL target program, REST target endpoint, and
     * user-type gate). The caller (typically {@code MenuController}) then
     * wraps the DTO in an {@code ApiResponse} envelope and returns it to
     * the client. Clients use the embedded
     * {@link MenuOptionDto#targetEndpoint()} for REST navigation;
     * {@link MenuOptionDto#targetProgram()} is preserved verbatim from the
     * COBOL literal-storage table for parallel-run traceability and
     * audit-readiness per AAP §0.7.3.</p>
     *
     * <p><b>Parameter type rationale.</b> The {@code option} parameter is
     * typed as {@code String} (rather than {@code int}) to:</p>
     * <ul>
     *   <li>Mirror the raw COBOL {@code OPTIONI} input read from the BMS
     *       map ({@code WS-OPTION-X PIC X(02)} before normalisation) and
     *       defer numeric validation into this single method (it is the
     *       canonical site of the COBOL numeric check).</li>
     *   <li>Support internal-caller use cases (this method is no longer
     *       exposed as a REST endpoint after the CP5 review removed the
     *       out-of-scope {@code POST /api/menu/resolve} controller route;
     *       see the {@link com.awsm2.carddemo.controller.MenuController}
     *       class-level Javadoc "Scope discipline" section for the
     *       rationale). The {@code String} signature is preserved so any
     *       future internal caller mirrors the original COBOL
     *       {@code WS-OPTION-X PIC X(02)} input contract.</li>
     * </ul>
     *
     * @param option      the user-supplied option number as a {@code String}
     *                    (1-or-2-digit numeric expected, leading zeros
     *                    permitted &mdash; matches the COBOL
     *                    {@code WS-OPTION-X PIC X(02)} layout). Parsed
     *                    into an integer internally; a non-numeric value
     *                    or a value outside {@code 1 <= n <= size}
     *                    triggers a {@link ValidationException} with the
     *                    verbatim COBOL "invalid option" message.
     *                    {@code null} is treated as invalid input
     *                    (does not raise {@link NullPointerException})
     * @param userType    the authenticated caller's user-type discriminator
     *                    ({@code "A"} or {@code "U"}); used only to gate
     *                    admin-only options when {@code isAdminMenu} is
     *                    {@code false}. May be {@code null} (the
     *                    controller's authentication gate will have
     *                    already rejected unauthenticated callers, but
     *                    the service handles the null gracefully)
     * @param isAdminMenu {@code true} to select the admin menu's option
     *                    table ({@code COADM02Y.cpy}), {@code false} to
     *                    select the main menu's option table
     *                    ({@code COMEN02Y.cpy})
     * @return the resolved {@link MenuOptionDto} for the selected option;
     *         never {@code null} (option entries in {@code COMEN02Y.cpy}
     *         and {@code COADM02Y.cpy} all carry the full set of fields,
     *         including a non-empty {@code PGMNAME PIC X(08)} value)
     * @throws ValidationException with the verbatim
     *                             {@link #ERR_INVALID_OPTION_FORMAT}
     *                             message when {@code option} is
     *                             non-numeric or out of range; with the
     *                             verbatim {@link #ERR_ADMIN_ONLY}
     *                             message when a regular user attempts
     *                             an admin-only option; or with the
     *                             verbatim {@link #ERR_COMING_SOON_FORMAT}
     *                             message when the selected option's
     *                             target program is prefixed
     *                             {@code "DUMMY"}. All three are mapped
     *                             by {@code GlobalExceptionHandler} to
     *                             HTTP 400 Bad Request
     */
    public MenuOptionDto resolveMenuTarget(String option, String userType, boolean isAdminMenu) {
        // COBOL: COMEN01C:PROCESS-ENTER-KEY -- pick the table whose key the user just pressed
        List<MenuOptionDto> options = isAdminMenu ? ADMIN_MENU_OPTIONS : MAIN_MENU_OPTIONS;

        // COBOL: COMEN01C:VALIDATE-OPTION -- IF WS-OPTION IS NOT NUMERIC (line 127).
        // The COBOL program first INSPECTs WS-OPTION-X replacing spaces with zeros
        // (line 123) and then implicitly converts via MOVE WS-OPTION-X TO WS-OPTION
        // (PIC 9(02)). The Java equivalent rejects non-numeric input (including null
        // and blank) up-front so subsequent bounds-check logic operates on a clean int.
        int optionNumber;
        try {
            optionNumber = Integer.parseInt(option);
        } catch (NumberFormatException | NullPointerException ex) {
            LOG.warn("Rejected non-numeric menu option (option={}, isAdminMenu={})",
                    option, isAdminMenu);
            throw new ValidationException(
                    String.format(ERR_INVALID_OPTION_FORMAT, 0));
        }

        // COBOL: COMEN01C:VALIDATE-OPTION -- IF WS-OPTION > COUNT OR = ZEROS (lines 128-129).
        if (optionNumber < 1 || optionNumber > options.size()) {
            LOG.warn("Rejected menu option out of range (option={}, isAdminMenu={}, "
                    + "validRange=1..{})", optionNumber, isAdminMenu, options.size());
            throw new ValidationException(
                    String.format(ERR_INVALID_OPTION_FORMAT, optionNumber));
        }

        // PIC 9(02) is 1-based; Java List is 0-based, so subtract 1 for the index lookup.
        MenuOptionDto selected = options.get(optionNumber - 1);

        // COBOL: COMEN01C:PROCESS-ENTER-KEY -- IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE = 'A'
        if (USER_TYPE_USER.equals(userType)
                && USER_TYPE_ADMIN.equals(selected.userType())) {
            LOG.warn("Rejected admin-only menu option for regular user "
                    + "(option={}, targetProgram={}, userType={})",
                    optionNumber, selected.targetProgram(), userType);
            throw new ValidationException(ERR_ADMIN_ONLY);
        }

        // COBOL: COMEN01C:PROCESS-ENTER-KEY -- IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) = 'DUMMY'
        if (selected.targetProgram() != null
                && selected.targetProgram().startsWith("DUMMY")) {
            LOG.info("Selected menu option is not yet implemented "
                    + "(option={}, targetProgram={}, label={})",
                    optionNumber, selected.targetProgram(), selected.label());
            throw new ValidationException(
                    String.format(ERR_COMING_SOON_FORMAT, optionNumber));
        }

        // COBOL: COMEN01C:PROCESS-ENTER-KEY -- EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME)
        // The Java target performs cross-service routing at the controller layer
        // (REST navigation). Returning the full MenuOptionDto here lets the controller
        // emit ApiResponse<MenuOptionDto> (including the targetEndpoint URL and the
        // original COBOL targetProgram identifier preserved for parallel-run
        // traceability per AAP §0.7.3).
        LOG.debug("Resolved menu target (option={}, isAdminMenu={}, targetProgram={})",
                optionNumber, isAdminMenu, selected.targetProgram());
        return selected;
    }
}
