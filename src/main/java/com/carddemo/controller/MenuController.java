package com.carddemo.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carddemo.dto.MenuOption;
import com.carddemo.dto.MenuResponse;
import com.carddemo.service.MenuService;

import jakarta.validation.constraints.Min;

/**
 * Menu / routing REST controller &mdash; the stateless replacement for the two legacy CICS menu
 * programs of the CardDemo application (legacy source referenced read-only by commit SHA
 * {@code 27d6c6f}).
 *
 * <p>This controller migrates:</p>
 * <ul>
 *   <li><b>Main menu</b> &mdash; transaction {@code CM00}, program {@code COMEN01C}
 *       ({@code app/cbl/COMEN01C.cbl}), whose ten options come from copybook {@code COMEN02Y}
 *       ({@code CDEMO-MENU-OPT-COUNT = 10});</li>
 *   <li><b>Admin menu</b> &mdash; transaction {@code CA00}, program {@code COADM01C}
 *       ({@code app/cbl/COADM01C.cbl}), whose four options come from copybook {@code COADM02Y}
 *       ({@code CDEMO-ADMIN-OPT-COUNT = 4}).</li>
 * </ul>
 *
 * <p><b>From 3270/BMS to headless JSON.</b> The legacy programs painted a BMS map, read the operator's
 * option keystroke, validated it in the {@code PROCESS-ENTER-KEY} paragraph, and, on success,
 * transferred control to the option's target program with {@code EXEC CICS XCTL} while carrying the
 * pseudo-conversational {@code COMMAREA}. In the migrated system there is no {@code COMMAREA} and no
 * server-side {@code XCTL}: the client fetches a role-appropriate, ordered list of options as JSON and
 * then calls the target endpoint directly. This controller is therefore a thin, stateless delegation
 * layer &mdash; all option-table data, the role-based visibility filter, and the option-selection edits
 * live in {@link MenuService}. The controller never re-implements those rules and never re-orders the
 * options the service returns (main menu 1..10, admin menu 1..4).</p>
 *
 * <p><b>Role model.</b> The legacy {@code SEC-USR-TYPE} indicator ({@code 'A'} administrator,
 * {@code 'U'} regular user) becomes a Spring Security authority ({@code ROLE_ADMIN} / {@code ROLE_USER})
 * carried on the JWT. {@link #getMenu(Authentication)} derives the caller's role from that authority and
 * asks the service for the matching menu, so administrator-only options are excluded for regular users
 * exactly as the {@code COMEN01C} filter did.</p>
 *
 * <p><b>Security.</b> {@code GET /api/menu} and {@code GET /api/menu/options/**} require only
 * authentication (any role) and are gated by the security filter chain in {@code config/SecurityConfig}.
 * {@code GET /api/admin/menu} is administrator-only via {@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")},
 * which is honored because {@code config/SecurityConfig} is annotated {@code @EnableMethodSecurity} (that
 * configuration is authored separately and is not created here).</p>
 *
 * <p><b>Error handling.</b> This controller deliberately catches nothing. Out-of-range or unauthorized
 * option selections raised by {@link MenuService} (a
 * {@link com.carddemo.exception.ValidationException}) and bean-validation failures on the path variable
 * (a {@code ConstraintViolationException} produced under {@link Validated @Validated}) are translated to
 * HTTP status codes by {@link GlobalExceptionHandler}.</p>
 *
 * @see MenuService
 * @see MenuResponse
 * @see MenuOption
 * @see GlobalExceptionHandler
 */
@RestController
@RequestMapping("/api")
@Validated
public class MenuController {

    /** Structured logger; every log line carries the MDC {@code correlationId} (Observability rule). */
    private static final Logger log = LoggerFactory.getLogger(MenuController.class);

    /**
     * Spring Security authority granted to administrators (the {@code ROLE_} prefix plus the JWT
     * {@code role} claim {@code 'A'} &rarr; {@code ADMIN}). Presence of this authority selects the
     * administrator menu.
     */
    private static final String ROLE_ADMIN_AUTHORITY = "ROLE_ADMIN";

    /**
     * Role token passed to {@link MenuService} for an administrator. Matches the service's
     * case-sensitive {@code "ADMIN"} discriminator (legacy {@code CDEMO-USRTYP-ADMIN}).
     */
    private static final String ROLE_ADMIN = "ADMIN";

    /**
     * Role token passed to {@link MenuService} for a regular (non-administrator) user. Any value other
     * than {@link #ROLE_ADMIN} resolves the main menu, mirroring the legacy default where only an
     * administrator reached {@code CA00}.
     */
    private static final String ROLE_USER = "USER";

    /** Role-scoped menu provider and option-selection validator (constructor-injected, never {@code null}). */
    private final MenuService menuService;

    /**
     * Creates the menu controller with its single collaborator.
     *
     * <p>Constructor injection is used exclusively so the dependency is explicit, the field can be
     * {@code final}, and the controller can be instantiated directly in tests.</p>
     *
     * @param menuService the menu service that owns the option tables, the role filter, and the
     *                    selection edits (never {@code null})
     */
    public MenuController(final MenuService menuService) {
        this.menuService = menuService;
    }

    /**
     * Returns the menu appropriate to the authenticated caller's role &mdash; the main menu
     * ({@code CM00} / {@code COMEN01C}) for a regular user, with administrator-only options excluded,
     * and the administrator menu for an administrator.
     *
     * <p>This reproduces the {@code COMEN01C} role-visibility filter: the caller's role is derived from
     * the JWT authority and handed to {@link MenuService#getMenuForRole(String)}, which returns the
     * already-ordered, already-filtered option set. The controller performs no filtering or re-ordering
     * of its own. Any authenticated user (regular or administrator) may call this endpoint.</p>
     *
     * @param authentication the current Spring Security authentication (supplied by the framework; may
     *                       be {@code null} if no context is established, which resolves to the
     *                       regular-user menu)
     * @return {@code 200 OK} with the role-appropriate {@link MenuResponse}
     */
    @GetMapping("/menu")
    public ResponseEntity<MenuResponse> getMenu(final Authentication authentication) {
        final String role = resolveRole(authentication);
        log.debug("Serving role-scoped menu for role={}", role);
        return ResponseEntity.ok(menuService.getMenuForRole(role));
    }

    /**
     * Returns the administrator menu ({@code CA00} / {@code COADM01C}) with its four options in declared
     * order.
     *
     * <p>The endpoint is administrator-only: {@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")}
     * denies any caller lacking {@code ROLE_ADMIN} with an {@code AccessDeniedException}
     * (&rarr; {@code 403} via {@link GlobalExceptionHandler}), reproducing the legacy invariant that the
     * {@code CA00} transaction &mdash; and the {@code CU00}&ndash;{@code CU03} user-maintenance programs
     * that return to it &mdash; are reachable only by administrators. Method security is enabled by
     * {@code @EnableMethodSecurity} in {@code config/SecurityConfig}.</p>
     *
     * @return {@code 200 OK} with the administrator {@link MenuResponse} (four options)
     */
    @GetMapping("/admin/menu")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MenuResponse> getAdminMenu() {
        log.debug("Serving admin menu (role={})", ROLE_ADMIN);
        return ResponseEntity.ok(menuService.getAdminMenu());
    }

    /**
     * Resolves a single menu selection, mirroring the {@code PROCESS-ENTER-KEY} option handling of
     * {@code COMEN01C} / {@code COADM01C}.
     *
     * <p>The selection is delegated to {@link MenuService#resolveOption(String, int)}, which owns every
     * edit: an out-of-range number yields the verbatim legacy message
     * {@code "Please enter a valid option number..."} and a regular user choosing an administrator-only
     * option yields {@code "No access - Admin Only option... "} &mdash; both raised as a
     * {@link com.carddemo.exception.ValidationException} ({@code 400}) and mapped by
     * {@link GlobalExceptionHandler}. The lower-bound guard {@link Min @Min(1)} on {@code optionNumber}
     * is enforced by {@link Validated @Validated} and surfaces as a {@code ConstraintViolationException}
     * ({@code 400}) for a zero or negative value. On success the resolved {@link MenuOption} is returned;
     * its {@link MenuOption#targetProgram() targetProgram} still names the original COBOL program the
     * legacy code would {@code XCTL} to. The controller adds no validation of its own.</p>
     *
     * @param authentication the current Spring Security authentication (supplied by the framework; may
     *                       be {@code null}, treated as a regular user)
     * @param optionNumber   the 1-based option number the caller selected (must be &ge; 1)
     * @return {@code 200 OK} with the resolved {@link MenuOption}
     */
    @GetMapping("/menu/options/{optionNumber}")
    public ResponseEntity<MenuOption> resolveOption(final Authentication authentication,
                                                    @PathVariable @Min(1) final int optionNumber) {
        final String role = resolveRole(authentication);
        log.debug("Resolving menu option {} for role={}", optionNumber, role);
        return ResponseEntity.ok(menuService.resolveOption(role, optionNumber));
    }

    /**
     * Maps the Spring Security {@link Authentication} to the role token expected by {@link MenuService}.
     *
     * <p>The caller is treated as an administrator only when its granted authorities contain
     * {@link #ROLE_ADMIN_AUTHORITY} ({@code "ROLE_ADMIN"}); every other case &mdash; including a
     * {@code null} authentication, a {@code null} authority collection, or a {@code ROLE_USER} token
     * &mdash; resolves to {@link #ROLE_USER}. This null-safe default mirrors the legacy behavior where
     * only an explicit administrator role reached the admin transaction.</p>
     *
     * @param authentication the current authentication, or {@code null}
     * @return {@link #ROLE_ADMIN} when the caller holds the administrator authority, otherwise
     *         {@link #ROLE_USER}
     */
    private String resolveRole(final Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return ROLE_USER;
        }
        final boolean admin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ROLE_ADMIN_AUTHORITY::equals);
        return admin ? ROLE_ADMIN : ROLE_USER;
    }
}
