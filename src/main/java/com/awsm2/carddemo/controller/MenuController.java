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
package com.awsm2.carddemo.controller;

import com.awsm2.carddemo.dto.AdminMenuDto;
import com.awsm2.carddemo.dto.ApiResponse;
import com.awsm2.carddemo.dto.MainMenuDto;
import com.awsm2.carddemo.service.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Menu REST controller &mdash; returns main and admin menu listings.
 *
 * <p><b>COBOL Provenance &mdash; the only AAP-sanctioned dual-source
 * controller (AAP &sect;0.4.1):</b> this controller replaces TWO CICS COBOL
 * programs that share an identical control structure (read literal-storage
 * option table &rarr; render BMS map &rarr; on PF/AID dispatch &rarr;
 * validate option, gate by user-type, XCTL to target program) and differ
 * only in their working-storage option table:</p>
 * <ul>
 *   <li>{@code app/cbl/COMEN01C.cbl} (CICS transaction id {@code CM00})
 *       &mdash; Main Menu for regular users, reading
 *       {@code app/cpy/COMEN02Y.cpy} ({@code CARDDEMO-MAIN-MENU-OPTIONS},
 *       10 entries) and rendered through {@code app/bms/COMEN01.bms}
 *       (symbolic map {@code app/cpy-bms/COMEN01.CPY}).</li>
 *   <li>{@code app/cbl/COADM01C.cbl} (CICS transaction id {@code CA00})
 *       &mdash; Admin Menu, reading {@code app/cpy/COADM02Y.cpy}
 *       ({@code CARDDEMO-ADMIN-MENU-OPTIONS}, 4 entries) and rendered
 *       through {@code app/bms/COADM01.bms} (symbolic map
 *       {@code app/cpy-bms/COADM01.CPY}).</li>
 * </ul>
 *
 * <p><b>Endpoint inventory (AAP &sect;0.3.4):</b></p>
 * <ul>
 *   <li>{@code GET /api/menu/main} &mdash; serves the regular-user main
 *       menu (replaces CICS {@code COMEN01C} / {@code CM00}). Available
 *       to any authenticated principal carrying either {@code ROLE_USER}
 *       or {@code ROLE_ADMIN}.</li>
 *   <li>{@code GET /api/menu/admin} &mdash; serves the admin menu
 *       (replaces CICS {@code COADM01C} / {@code CA00}). Gated by
 *       {@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")} for
 *       defense in depth in addition to the URL-level
 *       {@code /api/admin/**} matcher in {@code SecurityConfig} (the
 *       admin-menu URL is under {@code /api/menu/admin}; the
 *       method-level annotation is the primary gate).</li>
 * </ul>
 *
 * <p><b>Scope discipline (CP5 review):</b> the previous
 * {@code POST /api/menu/resolve} endpoint was removed because it was not
 * declared in AAP &sect;0.3.4 (no scope creep per AAP &sect;0.7.1) and
 * because it trusted request-body role-discriminator fields rather than
 * deriving authority from the authenticated principal &mdash; a
 * security defect that allowed {@code ROLE_USER} callers to submit
 * {@code {"userType":"A","adminMenu":true}} and have the service resolve
 * admin-menu entries. Menu-option-to-target-program resolution remains
 * available to internal callers via
 * {@link MenuService#resolveMenuTarget(String, String, boolean)}, but is
 * no longer exposed as a REST endpoint. The COBOL
 * {@code EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME)} dispatch is
 * replaced by REST navigation where the client invokes the resolved
 * {@link com.awsm2.carddemo.dto.MenuOptionDto#targetEndpoint()} value
 * returned in the main/admin menu listings directly via HTTP.</p>
 *
 * <p><b>COBOL behaviors preserved:</b></p>
 * <ul>
 *   <li>Option-table selection (main vs admin) &mdash; preserved through
 *       the distinct REST endpoints {@code GET /api/menu/main} and
 *       {@code GET /api/menu/admin} (one per COBOL program).</li>
 *   <li>Original {@code EXEC CICS XCTL PROGRAM(...)} dispatch &mdash;
 *       replaced by REST navigation where the client invokes the
 *       resolved
 *       {@link com.awsm2.carddemo.dto.MenuOptionDto#targetEndpoint()}
 *       after receiving it embedded in the main/admin menu listing
 *       response. The original target program identifier (e.g.,
 *       {@code COACTVWC}) is preserved verbatim in
 *       {@link com.awsm2.carddemo.dto.MenuOptionDto#targetProgram()}
 *       for audit / parallel-run traceability per AAP &sect;0.7.3.</li>
 * </ul>
 *
 * <p><b>Security model (AAP &sect;0.3.4, &sect;0.6.6, &sect;0.7.1):</b>
 * both endpoints are authenticated (the global
 * {@code BearerAuth} security requirement declared in
 * {@code OpenApiConfig#cardDemoOpenAPI()} applies to every operation by
 * default). Method-level {@link PreAuthorize @PreAuthorize}
 * additionally narrows the authorization on each endpoint:</p>
 * <ul>
 *   <li>{@code getMainMenu} &mdash; {@code hasAnyRole('USER','ADMIN')}</li>
 *   <li>{@code getAdminMenu} &mdash; {@code hasRole('ADMIN')}
 *       (defense in depth alongside the {@code /api/admin/**} matcher
 *       in {@code SecurityConfig#securityFilterChain})</li>
 * </ul>
 *
 * <p><b>Layered architecture compliance (AAP &sect;0.3.3, &sect;0.7.1):</b>
 * this controller is a thin Spring MVC fa&ccedil;ade. It holds no business
 * state, performs no I/O, never accesses a repository or AWS adapter
 * directly, and delegates all menu rendering logic to
 * {@link MenuService}. Constructor injection of the single
 * {@code MenuService} dependency is the only collaborator linkage.</p>
 *
 * <p><b>Standardized response envelope (AAP &sect;0.3.4):</b> every endpoint
 * returns {@link ApiResponse}{@code <T>} via the
 * {@link ApiResponse#success(Object) static factory method}. Validation
 * failures from {@link MenuService} surface as
 * {@link com.awsm2.carddemo.exception.ValidationException} which is mapped
 * by the {@code GlobalExceptionHandler} to HTTP 400 with the same envelope
 * shape; authentication / authorization failures surface as Spring Security
 * exceptions and are likewise translated to HTTP 401 / 403.</p>
 *
 * <p><b>PCI-DSS logging discipline (AAP &sect;0.6.6, &sect;0.7.2):</b> the
 * class-level SLF4J logger emits the authenticated user identifier
 * ({@code userId}), which is non-sensitive. No passwords, JWT tokens,
 * card data, account balances, or other PCI material are ever logged.</p>
 *
 * <p><b>OpenAPI documentation:</b> the class-level {@link Tag} groups
 * both endpoints under a single Swagger UI section labelled
 * &ldquo;Menu&rdquo; (served at {@code /swagger-ui.html} and
 * {@code /v3/api-docs}); each endpoint carries a per-method
 * {@link Operation} summary, and per-status {@link ApiResponses}
 * entries. The OpenAPI {@code @ApiResponse} annotation is referenced via
 * its fully qualified class name
 * ({@code io.swagger.v3.oas.annotations.responses.ApiResponse}) to
 * disambiguate from the project's
 * {@link com.awsm2.carddemo.dto.ApiResponse} response envelope record
 * type, which is imported here as the unqualified {@code ApiResponse}
 * (per the controller convention enforced by the AAP).</p>
 *
 * @see MenuService
 * @see MainMenuDto
 * @see AdminMenuDto
 * @see com.awsm2.carddemo.dto.MenuOptionDto
 * @see com.awsm2.carddemo.dto.ApiResponse
 * @see <a href=
 *      "https://github.com/aws-samples/aws-mainframe-modernization-carddemo">
 *      AWS CardDemo (source COBOL)</a>
 */
@RestController
@RequestMapping("/api/menu")
@Tag(name = "Menu",
        description = "Main and Admin menu listings. "
                + "Replaces CICS COMEN01C (Tran-ID CM00, Main Menu) and "
                + "COADM01C (Tran-ID CA00, Admin Menu).")
public class MenuController {

    /**
     * SLF4J facade for structured JSON logging. Backed by Logback +
     * logstash-logback-encoder per AAP &sect;0.7.2 ("Operational
     * requirements: Structured JSON logging (Logback +
     * logstash-logback-encoder) shipped to CloudWatch Logs"). All log
     * lines emit only non-sensitive fields (user identifier, option
     * number, admin flag) per AAP &sect;0.6.6 PCI-DSS discipline.
     */
    private static final Logger LOG = LoggerFactory.getLogger(MenuController.class);

    /**
     * COBOL {@code CDEMO-USER-TYPE} discriminator value for regular users
     * &mdash; {@code 'U'}. Sourced from
     * {@code app/cpy/CSUSR01Y.cpy:SEC-USR-TYPE PIC X(01)}. Used as the
     * default value passed to {@link MenuService#getMainMenu(String)} so
     * the service's user-type filter behaves the same as the original
     * {@code COMEN01C.cbl:BUILD-MENU-OPTIONS} paragraph; the actual
     * authenticated principal's role is enforced by Spring Security at
     * the method-level {@link PreAuthorize} gate.
     */
    private static final String USER_TYPE_USER = "U";

    /**
     * COBOL {@code CDEMO-USER-TYPE} discriminator value for administrators
     * &mdash; {@code 'A'}. Sourced from
     * {@code app/cpy/CSUSR01Y.cpy:SEC-USR-TYPE PIC X(01)}. Passed to
     * {@link MenuService#getAdminMenu(String)} so the service's
     * defense-in-depth admin gate (which throws
     * {@link com.awsm2.carddemo.exception.ValidationException} for any
     * non-{@code 'A'} value) accepts the call.
     */
    private static final String USER_TYPE_ADMIN = "A";

    /**
     * Menu service collaborator &mdash; encapsulates the verbatim COBOL
     * literal-storage tables ({@code COMEN02Y.cpy} and {@code COADM02Y.cpy})
     * and the option-validation cascade originally implemented by the
     * {@code PROCESS-ENTER-KEY} paragraphs in {@code COMEN01C.cbl} and
     * {@code COADM01C.cbl}. The reference is {@code final} (immutable
     * post-construction) to align with AAP &sect;0.3.3 / &sect;0.7.1
     * constructor-injection / loose-coupling discipline.
     */
    private final MenuService menuService;

    /**
     * Constructor used by Spring's dependency injection container to wire
     * the {@link MenuService} collaborator into this controller bean.
     *
     * <p>Per AAP &sect;0.7.1 ("Dependency injection for loose coupling"),
     * this controller uses constructor injection exclusively &mdash; no
     * field injection ({@code @Autowired} on a private field) and no
     * setter injection. This style is also Spring's recommended approach
     * from version 4.3 onward because it makes the dependency contract
     * explicit, enables {@code final} fields (immutability), and supports
     * straightforward construction in tests without reflection.</p>
     *
     * @param menuService the {@link MenuService} collaborator; never
     *                    {@code null} (Spring fails fast at startup if the
     *                    bean cannot be found, which protects against
     *                    accidental component-scan misconfiguration)
     */
    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    /**
     * Returns the main menu listing for the authenticated caller.
     *
     * <p><b>COBOL provenance:</b> Replaces {@code app/cbl/COMEN01C.cbl}
     * (CICS transaction {@code CM00}) which read the
     * {@code app/cpy/COMEN02Y.cpy} literal-storage table
     * ({@code CARDDEMO-MAIN-MENU-OPTIONS}, 10 entries) and rendered it
     * through the BMS map {@code COMEN1A} in mapset {@code COMEN01}
     * (see {@code app/bms/COMEN01.bms}).</p>
     *
     * <p><b>Authorization:</b> {@link PreAuthorize @PreAuthorize("hasAnyRole('USER','ADMIN')")}
     * &mdash; available to any authenticated principal, whether
     * {@code ROLE_USER} or {@code ROLE_ADMIN}. The original COBOL
     * {@code COSGN00C} sign-on program routed admin users to
     * {@code COADM01C} by default, but did not prevent them from
     * subsequently returning to the main menu &mdash; this endpoint
     * preserves that behavior by allowing admin callers to inspect the
     * main menu (with the {@link MainMenuDto#userType()} field echoed
     * back so the client can render role-appropriate badging).</p>
     *
     * <p><b>Filtering behavior:</b> The {@code userType} value
     * {@value #USER_TYPE_USER} is passed to
     * {@link MenuService#getMainMenu(String)} for parity with the
     * original {@code BUILD-MENU-OPTIONS} paragraph's filter. Today the
     * source data ({@code COMEN02Y.cpy}) has every main-menu entry
     * tagged {@code USR-TYPE = 'U'}, so the filter does not exclude any
     * options regardless of caller role &mdash; but the logic is
     * preserved so a future menu-table change (adding admin-only
     * entries) automatically applies without code modification.</p>
     *
     * <p><b>Response envelope:</b> wrapped in {@link ApiResponse} via
     * {@link ApiResponse#success(Object)} per AAP &sect;0.3.4.</p>
     *
     * @param userId the authenticated principal name extracted from the
     *               JWT-populated security context by
     *               {@link AuthenticationPrincipal @AuthenticationPrincipal(expression
     *               = "name")}. Used for structured-JSON debug logging
     *               only &mdash; the service itself is stateless and does
     *               not personalise the menu by user identity (the
     *               COMMAREA-style identity propagation from
     *               {@code COCOM01Y.cpy} is replaced by JWT claims; the
     *               controller layer does not need to forward the
     *               identity into {@link MenuService} for menu rendering).
     *               Non-sensitive per AAP &sect;0.7.2 (PCI-DSS allows
     *               logging non-PII operator identifiers).
     * @return {@link ResponseEntity} with HTTP 200 and the
     *         {@link MainMenuDto} payload wrapped in {@link ApiResponse}.
     *         Never returns a non-2xx status from this method body;
     *         unauthenticated callers are rejected upstream by the JWT
     *         filter and rejected callers without {@code ROLE_USER} or
     *         {@code ROLE_ADMIN} are rejected by Spring Security's
     *         {@code @PreAuthorize} interceptor, with both translated to
     *         the standardized error envelope by
     *         {@code GlobalExceptionHandler}
     */
    @GetMapping("/main")
    @Operation(
            summary = "Get main menu options for the authenticated user",
            description = "Returns the option list applicable to the current user. "
                    + "Replaces CICS COMEN01C / Tran-ID CM00 (Main Menu for regular users). "
                    + "Available to any authenticated principal (USER or ADMIN role); "
                    + "admin callers receive the same option set as regular users."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Main menu retrieved successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Forbidden")
    })
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ResponseEntity<ApiResponse<MainMenuDto>> getMainMenu(
            @AuthenticationPrincipal(expression = "name") String userId) {
        // COBOL: COMEN01C / Tran-ID CM00 -- render main menu options
        //   (delegates to MenuService which holds the verbatim COMEN02Y.cpy
        //    literal-storage table per AAP §0.4.1)
        LOG.debug("Main menu requested for userId={}", userId);
        // Pass user-type "U" so MenuService.getMainMenu mirrors the COBOL
        // BUILD-MENU-OPTIONS filter ("CDEMO-MENU-OPT-USRTYPE = CDEMO-USRTYPE
        // OR LOW-VALUES"). The service echoes this value back into
        // MainMenuDto.userType() for client-side badging, and the option
        // list itself is identical for admins (all COMEN02Y.cpy entries
        // are USR-TYPE = 'U').
        MainMenuDto menu = menuService.getMainMenu(USER_TYPE_USER);
        return ResponseEntity.ok(ApiResponse.success(menu));
    }

    /**
     * Returns the admin menu listing for the authenticated admin caller.
     *
     * <p><b>COBOL provenance:</b> Replaces {@code app/cbl/COADM01C.cbl}
     * (CICS transaction {@code CA00}) which read the
     * {@code app/cpy/COADM02Y.cpy} literal-storage table
     * ({@code CARDDEMO-ADMIN-MENU-OPTIONS}, 4 entries) and rendered it
     * through the BMS map {@code COADM1A} in mapset {@code COADM01}
     * (see {@code app/bms/COADM01.bms}).</p>
     *
     * <p><b>Authorization (defense in depth):</b> three independent gates
     * enforce admin-only access for this endpoint:</p>
     * <ol>
     *   <li>{@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")} on this
     *       method &mdash; primary gate, throws
     *       {@code AccessDeniedException} (translated to HTTP 403 by
     *       {@code GlobalExceptionHandler}) for any caller lacking
     *       {@code ROLE_ADMIN}.</li>
     *   <li>{@code MenuService.getAdminMenu(userType)} &mdash; checks
     *       {@code userType.equals("A")} and throws
     *       {@code ValidationException} (HTTP 400) for any non-admin
     *       value. Defense in depth against future controller
     *       misconfiguration.</li>
     *   <li>{@code SecurityConfig#securityFilterChain} URL matcher
     *       {@code /api/admin/**} would normally trigger for admin paths;
     *       however the admin-menu URL is {@code /api/menu/admin} (under
     *       the {@code /api/menu} base), so the URL-level matcher
     *       {@code /api/**} would only enforce that the caller is
     *       authenticated (either role). The
     *       {@link PreAuthorize @PreAuthorize} above is therefore the
     *       authoritative role gate for this specific endpoint.</li>
     * </ol>
     *
     * <p><b>COBOL gate replaced:</b> In the source, admin-only access is
     * enforced indirectly by {@code COSGN00C} (the sign-on program),
     * which inspects {@code SEC-USR-TYPE} from
     * {@code app/cpy/CSUSR01Y.cpy} and routes admin users
     * ({@code SEC-USR-TYPE = 'A'}) to {@code COADM01C}, sending all other
     * users to {@code COMEN01C}. {@code COADM01C} itself therefore does
     * not check the user-type &mdash; the gate is upstream. The Java
     * target moves the gate into the controller and service layers
     * (defense in depth) so an explicit request to
     * {@code GET /api/menu/admin} by a regular user is rejected with
     * HTTP 403 rather than silently routed elsewhere.</p>
     *
     * <p><b>Response envelope:</b> wrapped in {@link ApiResponse} via
     * {@link ApiResponse#success(Object)} per AAP &sect;0.3.4.</p>
     *
     * @param userId the authenticated admin principal name extracted from
     *               the JWT-populated security context by
     *               {@link AuthenticationPrincipal @AuthenticationPrincipal(expression
     *               = "name")}. Used for structured-JSON debug logging
     *               only; the service does not personalise the menu by
     *               user identity. Non-sensitive per AAP &sect;0.7.2
     * @return {@link ResponseEntity} with HTTP 200 and the
     *         {@link AdminMenuDto} payload wrapped in {@link ApiResponse}.
     *         Unauthenticated callers receive HTTP 401 from the upstream
     *         JWT filter; non-admin callers receive HTTP 403 from the
     *         method-level {@link PreAuthorize} interceptor; both are
     *         translated to the standardized error envelope by
     *         {@code GlobalExceptionHandler}
     */
    @GetMapping("/admin")
    @Operation(
            summary = "Get admin menu options",
            description = "Returns the admin option list (User List, User Add, "
                    + "User Update, User Delete from app/cpy/COADM02Y.cpy). "
                    + "ADMIN role only. Replaces CICS COADM01C / Tran-ID CA00 (Admin Menu)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Admin menu retrieved successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "User is not an admin (ADMIN role required)")
    })
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AdminMenuDto>> getAdminMenu(
            @AuthenticationPrincipal(expression = "name") String userId) {
        // COBOL: COADM01C / Tran-ID CA00 -- render admin menu options
        //   (delegates to MenuService which enforces the defense-in-depth
        //    admin-only gate by throwing ValidationException for any
        //    non-'A' userType per AAP §0.4.1).
        LOG.debug("Admin menu requested for userId={}", userId);
        // Pass user-type "A" so MenuService.getAdminMenu's defense-in-depth
        // check (throws ValidationException if userType != 'A') accepts
        // the call. The @PreAuthorize gate above has already verified the
        // caller carries ROLE_ADMIN; this passes the matching COBOL
        // SEC-USR-TYPE discriminator value down to the service layer.
        AdminMenuDto menu = menuService.getAdminMenu(USER_TYPE_ADMIN);
        return ResponseEntity.ok(ApiResponse.success(menu));
    }

}
