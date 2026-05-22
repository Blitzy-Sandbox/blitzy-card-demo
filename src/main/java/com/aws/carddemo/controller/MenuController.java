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
package com.aws.carddemo.controller;

import com.aws.carddemo.service.AdminMenuRequest;
import com.aws.carddemo.service.AdminMenuResponse;
import com.aws.carddemo.service.AdminMenuService;
import com.aws.carddemo.service.MainMenuRequest;
import com.aws.carddemo.service.MainMenuResponse;
import com.aws.carddemo.service.MainMenuService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposing the CardDemo menu endpoints — the Java migration of
 * the TWO CICS BMS screens and TWO COBOL programs that collectively own
 * role-based menu dispatch:
 *
 * <ul>
 *   <li>{@code app/bms/COMEN01.bms} + {@code app/cbl/COMEN01C.cbl}
 *       (TRANID {@code CM00}) — regular-user main menu (10 options)</li>
 *   <li>{@code app/bms/COADM01.bms} + {@code app/cbl/COADM01C.cbl}
 *       (TRANID {@code CA00}) — admin-only menu (4 user-management options)</li>
 * </ul>
 *
 * <h2>HTTP Contract</h2>
 *
 * <p>The controller exposes four endpoints — two GETs that expose the static
 * menu structure (equivalent to the COBOL {@code BUILD-MENU-OPTIONS}
 * paragraph) and two POSTs that dispatch a selected option to the
 * appropriate sub-program (equivalent to the COBOL {@code PROCESS-ENTER-KEY}
 * paragraph):
 *
 * <ul>
 *   <li>{@code GET /api/menu/main} — returns the {@link MainMenuView} carrying
 *       the 10 main-menu options preserved verbatim from
 *       {@code app/cpy/COMEN02Y.cpy}. Available to any authenticated caller;
 *       unauthenticated callers receive HTTP 401 from Spring Security's
 *       filter chain before the controller is invoked.</li>
 *   <li>{@code GET /api/menu/admin} — returns the {@link AdminMenuView}
 *       carrying the 4 admin-menu options preserved verbatim from
 *       {@code app/cpy/COADM02Y.cpy}. Admin-only via
 *       {@code @PreAuthorize("hasRole('ADMIN')")}; non-admin authenticated
 *       callers receive HTTP 403.</li>
 *   <li>{@code POST /api/menu/main/dispatch} — accepts a
 *       {@link MainMenuRequest} body carrying the user-typed option string
 *       and the caller's user-type, delegates to
 *       {@link MainMenuService#dispatch(MainMenuRequest)}, and maps the
 *       result to HTTP 200 (success) or HTTP 400 (reject).</li>
 *   <li>{@code POST /api/menu/admin/dispatch} — accepts an
 *       {@link AdminMenuRequest} body, delegates to
 *       {@link AdminMenuService#dispatch(AdminMenuRequest)}, and maps the
 *       result to HTTP 200 (success), HTTP 400 (reject), or HTTP 403 (not
 *       authorised). Admin-only via {@code @PreAuthorize("hasRole('ADMIN')")}.</li>
 * </ul>
 *
 * <h2>COBOL Provenance — Static Menu Tables</h2>
 *
 * <p>The {@link #MAIN_MENU_OPTIONS} and {@link #ADMIN_MENU_OPTIONS} constants
 * mirror the COBOL menu-option tables verbatim:
 *
 * <ul>
 *   <li>{@link #MAIN_MENU_OPTIONS} — 10 entries derived from
 *       {@code app/cpy/COMEN02Y.cpy} lines 25-84. Each entry pairs the
 *       option identifier with the human-readable name from the
 *       {@code CDEMO-MENU-OPT-NAME PIC X(35)} field and the next-route
 *       identifier the controller layer uses to dispatch the chosen
 *       sub-program (equivalent to the COBOL
 *       {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)} field).</li>
 *   <li>{@link #ADMIN_MENU_OPTIONS} — 4 entries derived from
 *       {@code app/cpy/COADM02Y.cpy} lines 24-42. Same shape as the main
 *       menu, but with {@code USER_*} routes for the four
 *       user-administration sub-programs.</li>
 * </ul>
 *
 * <p>The option-list order in both constants matches the COBOL
 * source-of-truth verbatim (AAP §0.10.2 Minimal Change Clause — "All
 * deviations from literal COBOL logic must be documented with the original
 * COBOL paragraph name and reason for divergence"). The order is
 * load-bearing because the COBOL dispatcher (the COBOL
 * {@code PROCESS-ENTER-KEY} paragraph) selects options by numeric position
 * (option 1, option 2, … option 10) and the corresponding Java service
 * dispatchers ({@link MainMenuService}, {@link AdminMenuService}) preserve
 * that numbering exactly.
 *
 * <h2>Authorisation</h2>
 *
 * <p>{@code GET /api/menu/main} and {@code POST /api/menu/main/dispatch} are
 * available to any authenticated caller. {@code GET /api/menu/admin} and
 * {@code POST /api/menu/admin/dispatch} are annotated with
 * {@code @PreAuthorize("hasRole('ADMIN')")}; the production
 * {@code SecurityConfig} (subsequent migration step) is expected to wire
 * {@code @EnableMethodSecurity} and a {@code SecurityFilterChain} that
 * requires authentication for all {@code /api/menu/**} paths. For tests
 * the same wiring is established by an inline {@code @TestConfiguration}
 * (see {@code MenuControllerTest.SecurityTestConfig}).
 *
 * <h2>Cross-Cutting Concerns</h2>
 *
 * <ul>
 *   <li><b>Static menu data is COBOL-derived.</b> The GET endpoints do not
 *       call the services — the menu structure is fixed by
 *       {@code app/cpy/COMEN02Y.cpy} and {@code app/cpy/COADM02Y.cpy} and is
 *       therefore embedded as immutable {@link MenuOption} lists. The
 *       dispatcher services own the option-to-route translation logic and
 *       are exercised by the POST endpoints.</li>
 *   <li><b>Service-layer reject-message handling.</b> The dispatch endpoints
 *       map the service's {@code MainMenuResponse.success}/{@code AdminMenuResponse.success}
 *       flag to HTTP 200 (success) or HTTP 400 (validation reject). The
 *       admin-menu authorisation reject ({@code "You are not authorized for
 *       Admin functions..."}) is mapped to HTTP 403 because the
 *       {@code @PreAuthorize} gate prevents non-admin callers from reaching
 *       the service in the first place — the 403 mapping is defence-in-depth.</li>
 *   <li><b>Internal-error handler.</b> Unexpected {@link RuntimeException}s
 *       from the service layer are caught by
 *       {@link #handleServiceFailure(RuntimeException)} which returns HTTP 500
 *       with a sanitised body so internal failure detail (database errors,
 *       stack traces) never leaks into the HTTP response (AAP §0.10.5).</li>
 *   <li><b>Read-only / non-state-changing endpoints.</b> Both GET endpoints
 *       are read-only — no CSRF concern. The POST dispatch endpoints are
 *       state-changing in the sense that they accept a request body, but the
 *       services they delegate to are themselves stateless and merely
 *       translate option numbers to route identifiers — there is no
 *       database write. CSRF protection still applies for defence in depth.</li>
 * </ul>
 *
 * @see MainMenuService
 * @see AdminMenuService
 * @see MainMenuRequest
 * @see AdminMenuRequest
 * @see MainMenuResponse
 * @see AdminMenuResponse
 */
@RestController
@RequestMapping("/api/menu")
public class MenuController {

    // ------------------------------------------------------------------------
    // Service-layer reject-message mirror
    // ------------------------------------------------------------------------
    //
    // AdminMenuService.MSG_NOT_AUTHORIZED is package-private (no modifier on
    // the `static final String` declaration in that class), so it cannot be
    // referenced directly from a class in a sibling package. The controller
    // duplicates the literal here so the HTTP-status mapping can dispatch on
    // it. The matching test (com.aws.carddemo.controller.MenuControllerTest)
    // asserts every mapped status against the same literal — drift will fail
    // both the controller mapping and the test together, surfacing the issue
    // loudly (AAP §0.10.10 style consistency).
    //
    // Source:
    //   com.aws.carddemo.service.AdminMenuService.MSG_NOT_AUTHORIZED
    //   = "You are not authorized for Admin functions..."
    // ------------------------------------------------------------------------

    /** Mirror of {@code AdminMenuService.MSG_NOT_AUTHORIZED}; HTTP 403. */
    static final String MSG_NOT_AUTHORIZED =
            "You are not authorized for Admin functions...";

    /** HTTP 500 message for unexpected service-layer exceptions; never echoes the underlying cause. */
    static final String MSG_INTERNAL_ERROR = "An unexpected error occurred";

    // ------------------------------------------------------------------------
    // COBOL parity constants — caller user-type codes per COCOM01Y.cpy
    // ------------------------------------------------------------------------

    /**
     * COBOL {@code CDEMO-USRTYP-USER} 88-level value from
     * {@code app/cpy/COCOM01Y.cpy} (regular user).
     */
    static final String CALLER_USER_TYPE_USER = "U";

    /**
     * COBOL {@code CDEMO-USRTYP-ADMIN} 88-level value from
     * {@code app/cpy/COCOM01Y.cpy} (admin user).
     */
    static final String CALLER_USER_TYPE_ADMIN = "A";

    // ------------------------------------------------------------------------
    // Static menu tables — verbatim from COMEN02Y.cpy and COADM02Y.cpy
    // ------------------------------------------------------------------------

    /**
     * The 10 main-menu options derived verbatim from
     * {@code app/cpy/COMEN02Y.cpy} lines 25-84. Each entry encodes the option
     * identifier (Java route token), the human-readable display name (from
     * the COBOL {@code CDEMO-MENU-OPT-NAME PIC X(35)} field, trimmed), and
     * the public REST target route the option will dispatch to. The order
     * matches the COBOL {@code CDEMO-MENU-OPTIONS-DATA} table exactly so
     * that the position-indexed dispatch in
     * {@link MainMenuService#dispatch(MainMenuRequest)} stays aligned.
     *
     * <p>Per COMEN02Y.cpy the {@code CDEMO-MENU-OPT-USRTYPE} column is
     * {@code 'U'} for every entry — there are no admin-only items in the
     * main menu in the current ruleset, so every option is visible to both
     * regular and admin users. (The COBOL dispatcher reserves an admin-only
     * branch for future entries marked {@code 'A'} — see {@link MainMenuService}
     * Step 4 comments.)
     */
    static final List<MenuOption> MAIN_MENU_OPTIONS = List.of(
            new MenuOption("ACCOUNT_VIEW",     "Account View",         "/api/accounts/{id}"),
            new MenuOption("ACCOUNT_UPDATE",   "Account Update",       "/api/accounts/{id}"),
            new MenuOption("CARD_LIST",        "Credit Card List",     "/api/cards"),
            new MenuOption("CARD_VIEW",        "Credit Card View",     "/api/cards/{cardNumber}"),
            new MenuOption("CARD_UPDATE",      "Credit Card Update",   "/api/cards/{cardNumber}"),
            new MenuOption("TRANSACTION_LIST", "Transaction List",     "/api/transactions"),
            new MenuOption("TRANSACTION_VIEW", "Transaction View",     "/api/transactions/{id}"),
            new MenuOption("TRANSACTION_ADD",  "Transaction Add",      "/api/transactions"),
            new MenuOption("REPORTS",          "Transaction Reports",  "/api/reports/submit"),
            new MenuOption("BILL_PAYMENT",     "Bill Payment",         "/api/bill-payment"));

    /**
     * The 4 admin-menu options derived verbatim from
     * {@code app/cpy/COADM02Y.cpy} lines 24-42. Each entry encodes the option
     * identifier (Java route token), the human-readable display name (from
     * the COBOL {@code CDEMO-ADMIN-OPT-NAME PIC X(35)} field, trimmed), and
     * the public REST target route. The order matches the COBOL
     * {@code CDEMO-ADMIN-OPTIONS-DATA} table exactly so the position-indexed
     * dispatch in {@link AdminMenuService#dispatch(AdminMenuRequest)} stays
     * aligned.
     */
    static final List<MenuOption> ADMIN_MENU_OPTIONS = List.of(
            new MenuOption("USER_LIST",   "User List (Security)",   "/api/users"),
            new MenuOption("USER_ADD",    "User Add (Security)",    "/api/users"),
            new MenuOption("USER_UPDATE", "User Update (Security)", "/api/users/{userId}"),
            new MenuOption("USER_DELETE", "User Delete (Security)", "/api/users/{userId}"));

    // ------------------------------------------------------------------------
    // Collaborators (constructor-injected)
    // ------------------------------------------------------------------------

    /**
     * The main-menu dispatcher service (Java migration of {@code COMEN01C.cbl}).
     * Used by {@link #dispatchMainMenuOption(MainMenuRequest)} to translate
     * the user-typed option into a next-route identifier.
     */
    private final MainMenuService mainMenuService;

    /**
     * The admin-menu dispatcher service (Java migration of {@code COADM01C.cbl}).
     * Used by {@link #dispatchAdminMenuOption(AdminMenuRequest)} to translate
     * the admin-user-typed option into a next-route identifier.
     */
    private final AdminMenuService adminMenuService;

    /**
     * Constructs the controller with constructor-injected service collaborators.
     *
     * @param mainMenuService  the main-menu dispatcher service (must not be {@code null})
     * @param adminMenuService the admin-menu dispatcher service (must not be {@code null})
     */
    public MenuController(MainMenuService mainMenuService, AdminMenuService adminMenuService) {
        this.mainMenuService = mainMenuService;
        this.adminMenuService = adminMenuService;
    }

    // ========================================================================
    // GET /api/menu/main — main menu view (COMEN01C / TRANID CM00)
    // ========================================================================

    /**
     * Returns the regular-user main menu — the Java equivalent of the COBOL
     * {@code BUILD-MENU-OPTIONS} paragraph of {@code app/cbl/COMEN01C.cbl}.
     *
     * <p>Available to any authenticated caller (regular or admin). The
     * returned {@link MainMenuView} carries the 10 fixed menu options from
     * {@link #MAIN_MENU_OPTIONS} and a {@code userId} echoed from the
     * authenticated principal so the client can decorate the response with
     * the operator's identity.
     *
     * <p>The {@code adminAccess} field is {@code true} when the caller holds
     * {@code ROLE_ADMIN} (which gates access to {@link #getAdminMenu(Authentication)}).
     *
     * @param authentication the Spring Security {@link Authentication}; never
     *                       {@code null} on a properly-secured request
     *                       because the auto-configured filter chain rejects
     *                       anonymous callers before the controller is invoked
     * @return HTTP 200 with a {@link MainMenuView} carrying the 10 main-menu
     *         options
     */
    @GetMapping(path = "/main", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MainMenuView> getMainMenu(Authentication authentication) {
        String userId = (authentication == null) ? "" : authentication.getName();
        boolean adminAccess = hasAdminRole(authentication);
        return ResponseEntity.ok(new MainMenuView(userId, MAIN_MENU_OPTIONS, adminAccess));
    }

    // ========================================================================
    // GET /api/menu/admin — admin menu view (COADM01C / TRANID CA00)
    // ========================================================================

    /**
     * Returns the admin-only menu — the Java equivalent of the COBOL
     * {@code BUILD-MENU-OPTIONS} paragraph of {@code app/cbl/COADM01C.cbl}.
     *
     * <p>Admin-only via {@code @PreAuthorize("hasRole('ADMIN')")}; non-admin
     * authenticated callers receive HTTP 403 from Spring Security's filter
     * chain before this method is invoked. Unauthenticated callers receive
     * HTTP 401 (also from the filter chain).
     *
     * @param authentication the Spring Security {@link Authentication}; never
     *                       {@code null} because {@code @PreAuthorize} fires
     *                       before this method
     * @return HTTP 200 with an {@link AdminMenuView} carrying the 4
     *         admin-menu options
     */
    @GetMapping(path = "/admin", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminMenuView> getAdminMenu(Authentication authentication) {
        String userId = (authentication == null) ? "" : authentication.getName();
        return ResponseEntity.ok(new AdminMenuView(userId, ADMIN_MENU_OPTIONS));
    }

    // ========================================================================
    // POST /api/menu/main/dispatch — dispatch main-menu option
    // ========================================================================

    /**
     * Dispatches a selected main-menu option to its next-route identifier —
     * the Java equivalent of the COBOL {@code PROCESS-ENTER-KEY} paragraph
     * of {@code app/cbl/COMEN01C.cbl}. Delegates the validation cascade
     * (null / blank / non-numeric / out-of-range / admin-only-by-regular-user)
     * to {@link MainMenuService#dispatch(MainMenuRequest)} and maps the
     * result to an HTTP status.
     *
     * @param request the JSON-serialised {@link MainMenuRequest}; the
     *                {@code option} field carries the user-typed option
     *                string (raw, untrimmed); the {@code callerUserType}
     *                carries {@code "U"} or {@code "A"}
     * @return HTTP 200 with the populated {@link MainMenuResponse} on
     *         success; HTTP 400 with the reject-bearing
     *         {@link MainMenuResponse} on a validation failure
     */
    @PostMapping(path = "/main/dispatch",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MainMenuResponse> dispatchMainMenuOption(
            @RequestBody MainMenuRequest request) {

        MainMenuResponse result = mainMenuService.dispatch(request);

        if (result.isSuccess()) {
            return ResponseEntity.ok(result);
        }
        // All main-menu rejects (empty option, invalid number, out of range,
        // non-numeric, admin-only-by-regular-user) map to HTTP 400 — there
        // is no "not found" or "conflict" semantics for a pure dispatcher.
        return ResponseEntity.badRequest().body(result);
    }

    // ========================================================================
    // POST /api/menu/admin/dispatch — dispatch admin-menu option
    // ========================================================================

    /**
     * Dispatches a selected admin-menu option to its next-route identifier —
     * the Java equivalent of the COBOL {@code PROCESS-ENTER-KEY} paragraph
     * of {@code app/cbl/COADM01C.cbl}. Delegates to
     * {@link AdminMenuService#dispatch(AdminMenuRequest)} and maps the result
     * to an HTTP status:
     *
     * <ul>
     *   <li>{@code success = true} → HTTP 200 with the populated response.</li>
     *   <li>{@code success = false} with message {@link #MSG_NOT_AUTHORIZED}
     *       → HTTP 403 Forbidden (the service-level authorisation reject;
     *       distinct from the {@code @PreAuthorize} 403 because the service
     *       performs defence-in-depth re-checking of the user type).</li>
     *   <li>Any other {@code success = false} → HTTP 400 Bad Request.</li>
     * </ul>
     *
     * <p>Admin-only via {@code @PreAuthorize("hasRole('ADMIN')")}; non-admin
     * authenticated callers receive HTTP 403 before this method is invoked.
     *
     * @param request the JSON-serialised {@link AdminMenuRequest}
     * @return HTTP 200 / 400 / 403 per the mapping above
     */
    @PostMapping(path = "/admin/dispatch",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminMenuResponse> dispatchAdminMenuOption(
            @RequestBody AdminMenuRequest request) {

        AdminMenuResponse result = adminMenuService.dispatch(request);

        if (result.isSuccess()) {
            return ResponseEntity.ok(result);
        }

        String message = result.getMessage();
        if (MSG_NOT_AUTHORIZED.equals(message)) {
            // Service-layer defence-in-depth authorisation reject — the
            // @PreAuthorize gate should have already prevented this, but if
            // it somehow didn't, surface as 403 with the service's reject
            // message rather than swallowing it into a 400.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
        }
        return ResponseEntity.badRequest().body(result);
    }

    // ========================================================================
    // @ExceptionHandler — unexpected service failures
    // ========================================================================

    /**
     * Handles unexpected {@link RuntimeException}s thrown by the service
     * layer. Returns HTTP 500 with a sanitised body — the underlying
     * exception detail must never leak into the HTTP response (AAP §0.10.5
     * applied to error paths).
     *
     * <p>{@link AccessDeniedException} (and its Spring Security 6.1+ subtype
     * {@code AuthorizationDeniedException} thrown by {@code @PreAuthorize})
     * is RE-THROWN so Spring Security's {@code ExceptionTranslationFilter}
     * can map it to HTTP 403 with the conventional access-denied semantics —
     * otherwise this handler would incorrectly swallow the security
     * exception and return HTTP 500.
     *
     * @param ex the caught exception; not echoed in the response. Production
     *           deployments would log this exception via an injected
     *           {@code Logger} for operations review.
     * @return HTTP 500 with a generic error message
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorJsonResponse> handleServiceFailure(RuntimeException ex) {
        // Re-throw security exceptions so Spring Security's filter chain can
        // produce the appropriate 403 response. AccessDeniedException covers
        // both the legacy class and the Spring Security 6.1+
        // AuthorizationDeniedException (which extends AccessDeniedException).
        if (ex instanceof AccessDeniedException accessDenied) {
            throw accessDenied;
        }
        // Defensive null-guard documents that this handler does NOT
        // dereference ex.getMessage() / ex.toString() — preventing accidental
        // disclosure of internal error strings in the HTTP response body.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorJsonResponse(false, MSG_INTERNAL_ERROR));
    }

    // ========================================================================
    // Private helpers
    // ========================================================================

    /**
     * Returns {@code true} when the supplied {@link Authentication} carries
     * the {@code ROLE_ADMIN} authority — used to populate the
     * {@link MainMenuView#adminAccess()} flag on the regular-user main menu
     * so the front-end can render an "Admin Menu" link conditionally.
     *
     * @param authentication the security context; may be {@code null}
     * @return {@code true} when the caller holds {@code ROLE_ADMIN};
     *         {@code false} otherwise
     */
    private static boolean hasAdminRole(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    // ========================================================================
    // Response DTOs — inner records
    // ========================================================================

    /**
     * A single menu option — the Java equivalent of one row in the COBOL
     * {@code CDEMO-MENU-OPTIONS} (or {@code CDEMO-ADMIN-OPTIONS}) table.
     *
     * @param optionId    the route identifier (e.g. {@code "ACCOUNT_VIEW"});
     *                    matches the {@link MainMenuService} /
     *                    {@link AdminMenuService} route constants
     * @param displayName the human-readable name shown to the user (COBOL
     *                    {@code CDEMO-MENU-OPT-NAME PIC X(35)}, trimmed)
     * @param targetRoute the REST URL the option will dispatch to
     */
    public static record MenuOption(String optionId, String displayName, String targetRoute) {
    }

    /**
     * Wire-format response for {@code GET /api/menu/main}.
     *
     * @param userId      the authenticated principal's name (echoed so the
     *                    client can render the operator's identity)
     * @param options     the 10 main-menu options
     * @param adminAccess {@code true} when the caller holds
     *                    {@code ROLE_ADMIN} (so the client can render an
     *                    optional "Admin Menu" link); {@code false} otherwise
     */
    public static record MainMenuView(String userId, List<MenuOption> options, boolean adminAccess) {
    }

    /**
     * Wire-format response for {@code GET /api/menu/admin}.
     *
     * @param userId  the authenticated admin's name
     * @param options the 4 admin-menu options
     */
    public static record AdminMenuView(String userId, List<MenuOption> options) {
    }

    /**
     * Wire-format response for unexpected service-layer failures. Returned
     * by the {@link #handleServiceFailure(RuntimeException)} exception
     * handler.
     *
     * @param success always {@code false}
     * @param message a sanitised generic message ({@link #MSG_INTERNAL_ERROR});
     *                NEVER includes the underlying exception's message or
     *                stack-trace fragment
     */
    public static record ErrorJsonResponse(boolean success, String message) {
    }
}
