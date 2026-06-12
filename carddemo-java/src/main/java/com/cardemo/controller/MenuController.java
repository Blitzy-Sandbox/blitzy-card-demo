package com.cardemo.controller;

import java.util.List;
import java.util.Locale;

import com.cardemo.exception.ValidationException;
import com.cardemo.model.enums.UserType;
import com.cardemo.service.menu.AdminMenuService;
import com.cardemo.service.menu.MainMenuService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST replacement for the two AWS CardDemo CICS BMS 3270 <strong>menu screens</strong>:
 * the regular-user <strong>Main Menu</strong> and the administrator <strong>Admin
 * Menu</strong>. It exposes the single read endpoint <strong>{@code GET
 * /api/menu/{type}}</strong> and is a thin adapter over {@link MainMenuService} and
 * {@link AdminMenuService}.
 *
 * <p>This controller is the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x realization of
 * AAP&nbsp;&sect;0.4.1 (tech-spec&nbsp;L653: <em>{@code controller/MenuController.java}
 * CREATE &larr; {@code app/bms/COMEN01.bms}, {@code app/bms/COADM01.bms} &mdash;
 * "GET /api/menu/{type}"</em>) and of AAP&nbsp;&sect;0.3.4 (BMS&nbsp;&rarr;&nbsp;REST
 * contract translation). It preserves features <strong>F-002</strong> (Main Menu
 * navigation) and <strong>F-003</strong> (Admin Menu navigation) without expansion.</p>
 *
 * <h2>Authoritative source artifacts (read-only reference, never copied)</h2>
 * <ul>
 *   <li><strong>{@code app/bms/COMEN01.bms}</strong> &mdash; the Main Menu mapset
 *       ({@code COMEN1A}), driven by CICS program {@code COMEN01C}, transaction
 *       <strong>{@code CM00}</strong>.</li>
 *   <li><strong>{@code app/bms/COADM01.bms}</strong> &mdash; the Admin Menu mapset
 *       ({@code COADM1A}), driven by CICS program {@code COADM01C}, transaction
 *       <strong>{@code CA00}</strong>.</li>
 * </ul>
 * <p>The fixed option tables themselves are migrated from the copybooks
 * {@code app/cpy/COMEN02Y.cpy} (10 options) and {@code app/cpy/COADM02Y.cpy}
 * (4 options) and are owned <em>verbatim</em> by the two services; this controller
 * never re-declares them.</p>
 *
 * <h2>COBOL &rarr; REST substitutions (documented per the Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>{@code SEND MAP('COMEN1A'/'COADM1A')} &rarr; JSON option table.</strong>
 *       The BMS map paint that filled the {@code OPTN001..OPTN0nn} display lines from
 *       the option copybook is replaced by projecting each service option record into a
 *       {@link MenuResponse.MenuOptionView}; option order, number, name and target
 *       program are preserved exactly.</li>
 *   <li><strong>{@code RETURN TRANSID('CM00'/'CA00') COMMAREA} &rarr; stateless GET.</strong>
 *       The pseudo-conversational hand-off is replaced by a stateless HTTP&nbsp;GET; this
 *       controller holds no conversational state (AAP&nbsp;&sect;0.1.2).</li>
 *   <li><strong>CICS transaction-id route table &rarr; {@code @RequestMapping}.</strong>
 *       The {@code CM00} / {@code CA00} transaction identifiers are realized as the
 *       {@code {type}} &rarr; service selection performed by {@link #getMenu(String)}
 *       (AAP&nbsp;&sect;0.6.3).</li>
 * </ul>
 *
 * <h2>Admin-menu access control</h2>
 * <p>The Admin Menu is <strong>ADMIN-only</strong>. That restriction is enforced
 * <em>upstream</em> at the security boundary and at sign-on, not duplicated as business
 * logic in this thin adapter (AAP&nbsp;&sect;0.3.3): sign-on ({@code COSGN00C} &rarr;
 * {@code AuthenticationService}) already routes administrators versus regular users, and
 * the Spring Security configuration ({@code config/SecurityConfig}) governs access to the
 * protected endpoints. {@link AdminMenuService#isAccessibleBy(UserType)} exposes the
 * ADMIN-only predicate for callers that wish to assert eligibility. No new security
 * infrastructure is introduced here.</p>
 *
 * <h2>Option-selection routing is client-side</h2>
 * <p>The services additionally expose {@code selectOption(String, CommArea)} (the COBOL
 * {@code PROCESS-ENTER-KEY} dispatch). It is intentionally <strong>not</strong> exposed by
 * this controller: in the REST model the client reads the option table and then calls the
 * chosen domain endpoint directly (for example {@code /api/accounts/{id}}), so menu-option
 * routing is realized client-side &mdash; faithful to the rule that CICS AID/PF keys map to
 * distinct REST endpoints (AAP&nbsp;&sect;0.1.2). This file therefore neither imports
 * {@code CommArea} nor adds a dispatch endpoint.</p>
 *
 * <h2>Error handling</h2>
 * <p>An unrecognized {@code {type}} yields a {@link ValidationException}, which the
 * centralized {@code @RestControllerAdvice} in {@code config/WebConfig} translates to
 * HTTP&nbsp;<strong>400 Bad Request</strong>. This controller defines no
 * {@code @ExceptionHandler} / {@code @RestControllerAdvice} and catches no domain
 * exception &mdash; it lets the exception propagate.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL/BMS baseline at commit
 * SHA {@code 27d6c6f}. The COBOL and BMS sources are read-only reference material and are
 * never copied into this repository.</p>
 *
 * @see MainMenuService
 * @see AdminMenuService
 * @see com.cardemo.model.enums.UserType
 */
@RestController
@RequestMapping("/api/menu")
public class MenuController {

    /**
     * Canonical {@code {type}} token selecting the regular-user Main Menu
     * ({@code COMEN01C} / {@code CM00}). Also the {@link MenuResponse#menuType()} value
     * returned for that menu.
     */
    private static final String MENU_TYPE_MAIN = "main";

    /**
     * Accepted alias of {@link #MENU_TYPE_MAIN}: a request for the {@code "user"} menu
     * resolves to the same regular-user Main Menu. The canonical {@code menuType} echoed
     * back in the response is always {@link #MENU_TYPE_MAIN}, regardless of which token
     * was supplied.
     */
    private static final String MENU_TYPE_USER_ALIAS = "user";

    /**
     * Canonical {@code {type}} token selecting the administrator Admin Menu
     * ({@code COADM01C} / {@code CA00}). Also the {@link MenuResponse#menuType()} value
     * returned for that menu.
     */
    private static final String MENU_TYPE_ADMIN = "admin";

    /** Service owning the 10-option regular-user Main Menu metadata ({@code COMEN01C}). */
    private final MainMenuService mainMenuService;

    /** Service owning the 4-option administrator Admin Menu metadata ({@code COADM01C}). */
    private final AdminMenuService adminMenuService;

    /**
     * Constructs the controller with the two menu services injected by Spring
     * (constructor injection; both fields are {@code final}).
     *
     * @param mainMenuService  the regular-user Main Menu service ({@code COMEN01C})
     * @param adminMenuService the administrator Admin Menu service ({@code COADM01C})
     */
    public MenuController(final MainMenuService mainMenuService, final AdminMenuService adminMenuService) {
        this.mainMenuService = mainMenuService;
        this.adminMenuService = adminMenuService;
    }

    /**
     * Returns the option table for the requested menu.
     *
     * <p><strong>Endpoint:</strong> {@code GET /api/menu/{type}}.</p>
     *
     * <p>The {@code type} path token is matched case-insensitively:</p>
     * <ul>
     *   <li>{@code "main"} (canonical) or {@code "user"} (alias) &rarr; the regular-user
     *       Main Menu &mdash; transaction {@code CM00}, program {@code COMEN01C}, 10
     *       options &mdash; with HTTP&nbsp;<strong>200 OK</strong>.</li>
     *   <li>{@code "admin"} &rarr; the administrator Admin Menu &mdash; transaction
     *       {@code CA00}, program {@code COADM01C}, 4 options &mdash; with
     *       HTTP&nbsp;<strong>200 OK</strong>. The Admin Menu is ADMIN-only; that
     *       restriction is enforced at the security boundary / sign-on (see the class
     *       documentation), not in this method.</li>
     *   <li>any other value &rarr; a {@link ValidationException} that the centralized
     *       advice translates to HTTP&nbsp;<strong>400 Bad Request</strong>.</li>
     * </ul>
     *
     * @param type the menu selector path token ({@code main}, {@code user} or {@code admin},
     *             case-insensitive)
     * @return {@code 200 OK} carrying the populated {@link MenuResponse}
     * @throws ValidationException if {@code type} is not one of the accepted values
     *                             (rendered as HTTP&nbsp;400 by {@code config/WebConfig})
     */
    @GetMapping("/{type}")
    public ResponseEntity<MenuResponse> getMenu(@PathVariable("type") final String type) {
        // Normalize the path token case-insensitively. Locale.ROOT avoids locale-specific
        // case folding (e.g. the Turkish dotless-i) so the contract is locale-independent.
        // COBOL substitution: the CICS transaction-id route table (CM00 vs CA00) is realized
        // here as a {type} -> service selection (AAP §0.6.3).
        final String normalized = (type == null) ? "" : type.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            // "main" (canonical) and "user" (alias) -> regular-user Main Menu (COMEN01C / CM00).
            case MENU_TYPE_MAIN, MENU_TYPE_USER_ALIAS -> ResponseEntity.ok(buildMainMenu());
            // "admin" -> administrator Admin Menu (COADM01C / CA00); ADMIN-only (enforced upstream).
            case MENU_TYPE_ADMIN -> ResponseEntity.ok(buildAdminMenu());
            // Any other token is an invalid route. Reproduce the COBOL invalid-selection
            // rejection as a ValidationException; the central @RestControllerAdvice in
            // config/WebConfig maps it to HTTP 400. No try/catch of domain exceptions here.
            default -> throw new ValidationException("Invalid menu type: " + type);
        };
    }

    /**
     * Builds the {@link MenuResponse} for the regular-user Main Menu from
     * {@link MainMenuService}.
     *
     * @return the populated Main Menu response (10 options, in order 1..10)
     */
    private MenuResponse buildMainMenu() {
        // COBOL substitution: COMEN01C SEND MAP('COMEN1A') painted the 10 option lines
        // (OPTN001..OPTN010) from the COMEN02Y table; here that table -- owned verbatim by
        // MainMenuService -- is projected into the JSON option list, preserving order 1..10.
        final List<MenuResponse.MenuOptionView> options = mainMenuService.getMenuOptions().stream()
                .map(option -> new MenuResponse.MenuOptionView(
                        option.number(),
                        option.name(),
                        option.targetProgram(),
                        formatUserType(option.allowedUserType())))
                .toList();
        return new MenuResponse(
                MENU_TYPE_MAIN,
                MainMenuService.TRANSACTION_ID,
                MainMenuService.PROGRAM_NAME,
                mainMenuService.getOptionCount(),
                options);
    }

    /**
     * Builds the {@link MenuResponse} for the administrator Admin Menu from
     * {@link AdminMenuService}.
     *
     * @return the populated Admin Menu response (4 options, in order 1..4)
     */
    private MenuResponse buildAdminMenu() {
        // COBOL substitution: COADM01C SEND MAP('COADM1A') painted the 4 option lines
        // (OPTN001..OPTN004) from the COADM02Y table; here that table -- owned verbatim by
        // AdminMenuService -- is projected into the JSON option list, preserving order 1..4.
        //
        // Design choice: AdminMenuOption carries no per-option user-type field (COADM02Y has
        // no usrtype byte); the whole admin menu is implicitly ADMIN-only, so each view's
        // allowedUserType is set to the ADMIN code 'A', sourced from UserType.ADMIN (the
        // single source of truth) rather than hard-coded as a literal.
        final String adminUserType = String.valueOf(UserType.ADMIN.getCode());
        final List<MenuResponse.MenuOptionView> options = adminMenuService.getMenuOptions().stream()
                .map(option -> new MenuResponse.MenuOptionView(
                        option.number(),
                        option.name(),
                        option.targetProgram(),
                        adminUserType))
                .toList();
        return new MenuResponse(
                MENU_TYPE_ADMIN,
                AdminMenuService.TRANSACTION_ID,
                AdminMenuService.PROGRAM_NAME,
                adminMenuService.getOptionCount(),
                options);
    }

    /**
     * Formats a {@link UserType} as its single-character COBOL storage code.
     *
     * <p>Maps {@link UserType#USER} &rarr; {@code "U"} and {@link UserType#ADMIN} &rarr;
     * {@code "A"}, preserving the 1-byte {@code CDEMO-MENU-OPT-USRTYPE} ({@code PIC X(01)})
     * external-interface field contract. A {@code null} user type is rendered as
     * {@code null}.</p>
     *
     * @param userType the option's permitted user type (may be {@code null})
     * @return {@code "U"}/{@code "A"} for a non-null user type, or {@code null}
     */
    private static String formatUserType(final UserType userType) {
        return (userType == null) ? null : String.valueOf(userType.getCode());
    }

    /**
     * Self-describing JSON contract for a menu, local to this controller.
     *
     * <p>The CardDemo migration intentionally has <strong>no menu DTO</strong> under
     * {@code model/dto}: the option shape is supplied by the services as nested records
     * ({@code MainMenuService.MenuOption} and {@code AdminMenuService.AdminMenuOption}).
     * To give both menu types one typed, self-describing contract carrying the
     * {@code transactionId} / {@code optionCount} metadata &mdash; without introducing a new
     * top-level DTO file (Minimal Change Clause, AAP&nbsp;&sect;0.7.1) &mdash; that contract
     * is declared here as an inline nested record.</p>
     *
     * @param menuType      the canonical menu type ({@code "main"} or {@code "admin"})
     * @param transactionId the CICS transaction identifier ({@code CM00} for the Main Menu,
     *                      {@code CA00} for the Admin Menu)
     * @param programName   the originating COBOL program name ({@code COMEN01C} /
     *                      {@code COADM01C})
     * @param optionCount   the number of options ({@code 10} for the Main Menu, {@code 4}
     *                      for the Admin Menu)
     * @param options       the ordered option views
     */
    public record MenuResponse(
            String menuType,
            String transactionId,
            String programName,
            int optionCount,
            List<MenuOptionView> options) {

        /**
         * A single menu option, mapped from a service option record.
         *
         * @param number          the 1-based option number
         * @param name            the human-readable option name (for example
         *                        {@code "Account View"} or {@code "User List (Security)"})
         * @param targetProgram   the COBOL program the option routes to (for example
         *                        {@code COACTVWC} or {@code COUSR00C})
         * @param allowedUserType the single-character user-type code permitted to select the
         *                        option ({@code "U"}/{@code "A"}); {@code "A"} for every Admin
         *                        Menu option, as that menu is implicitly ADMIN-only
         */
        public record MenuOptionView(
                int number,
                String name,
                String targetProgram,
                String allowedUserType) {
        }
    }
}
