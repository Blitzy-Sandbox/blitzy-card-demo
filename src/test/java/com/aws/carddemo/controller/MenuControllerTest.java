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
/*
 * MenuControllerTest — Spring MVC slice test for MenuController
 *
 * Replaces BMS mapsets:
 *   COMEN01.bms (Main Menu)  +  COMEN01C.cbl  (TRANID CM00)  — regular users
 *   COADM01.bms (Admin Menu) +  COADM01C.cbl  (TRANID CA00)  — admin-only
 *
 * AAP references:
 *   §0.5.1  CREATE — Controller Integration Tests
 *   §0.4.1  Strategy — @WebMvcTest + @MockBean + MockMvc
 *   §0.7.1  Coverage — controller line ≥80%, branch ≥70%
 *   §0.10.1 Require Test Coverage — services mocked at @MockBean boundary
 *   §0.10.5 Security — no plaintext credentials; defence-in-depth role gates
 *
 * Mocking boundary: MainMenuService, AdminMenuService (@MockBean).
 *
 * COBOL business rule preserved at HTTP layer:
 *   - Regular user: 10 main-menu options derived from app/cpy/COMEN02Y.cpy
 *     in source order: ACCOUNT_VIEW, ACCOUNT_UPDATE, CARD_LIST, CARD_VIEW,
 *     CARD_UPDATE, TRANSACTION_LIST, TRANSACTION_VIEW, TRANSACTION_ADD,
 *     REPORTS, BILL_PAYMENT.
 *   - Admin user:   4 admin-menu options derived from app/cpy/COADM02Y.cpy
 *     in source order: USER_LIST, USER_ADD, USER_UPDATE, USER_DELETE.
 *   - Role enforcement: admin endpoints reject non-admin authenticated
 *     callers with HTTP 403; both endpoints reject anonymous callers with
 *     HTTP 401.
 *
 * Adaptation notes (versus the agent-prompt blueprint):
 *   - The agent-prompt blueprint sketched MainMenuService.getMenuFor(...)
 *     returning a builder-shaped MainMenuResponse with userId/options/adminAccess
 *     fields and a nested Option.of(...) factory. The ACTUAL production
 *     services expose MainMenuService.dispatch(MainMenuRequest) /
 *     AdminMenuService.dispatch(AdminMenuRequest) returning flat
 *     MainMenuResponse / AdminMenuResponse with success/nextRoute/message
 *     fields (factory methods .success(nextRoute) and .failure(message)).
 *     The MenuController therefore exposes BOTH:
 *       (a) GET endpoints that publish the static menu structure preserved
 *           verbatim from the COBOL copybooks (no service call) — these
 *           exercise the "menu listing" contract the agent prompt sketched;
 *       (b) POST /api/menu/{main,admin}/dispatch endpoints that delegate to
 *           the real dispatcher services — these exercise the service-failure
 *           path the agent prompt asked for, and they preserve the COBOL
 *           PROCESS-ENTER-KEY dispatcher semantics one-for-one.
 *   - All DTOs (MainMenuRequest/Response, AdminMenuRequest/Response) live in
 *     com.aws.carddemo.service rather than com.aws.carddemo.dto; the
 *     controller's wire-format records (MainMenuView, AdminMenuView,
 *     ErrorJsonResponse) are inner records on MenuController itself.
 *   - The agent prompt asked for 9 minimum tests across two @Nested
 *     classes; this class delivers more (≥ 13: 7 GET-main + 6 GET-admin
 *     including dispatch-endpoint exercise) to fully cover the BOTH GET
 *     and POST surfaces, surface every documented service-reject-message
 *     status mapping, and reach the §0.7.1 ≥80% line / ≥70% branch
 *     coverage targets.
 */
package com.aws.carddemo.controller;

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies)
//
//   * TestFixtures — single source of truth for fixture user identifiers
//     (REGULAR_USER_ID="USRTST01" and ADMIN_USER_ID="ADMTST01") referenced
//     in @WithMockUser annotations and asserted on the JSON `userId`
//     response field.
//
//   * MainMenuRequest / MainMenuResponse / AdminMenuRequest / AdminMenuResponse —
//     sibling DTOs that live under com.aws.carddemo.service (NOT
//     com.aws.carddemo.dto). The mocked services consume the Request inputs
//     and return the Response outputs that this test stubs via
//     given(...).willReturn(...).
//
//   * MainMenuService / AdminMenuService — the TWO service collaborators
//     mocked via @MockBean. The AAP §0.10.1 Require Test Coverage rule
//     restricts mocks to external boundaries; the controller-under-test
//     calls a real MenuController whose only dependencies, the two
//     @Service beans, are the mocked boundary.
// ---------------------------------------------------------------------------
import com.aws.carddemo.service.AdminMenuRequest;
import com.aws.carddemo.service.AdminMenuResponse;
import com.aws.carddemo.service.AdminMenuService;
import com.aws.carddemo.service.MainMenuRequest;
import com.aws.carddemo.service.MainMenuResponse;
import com.aws.carddemo.service.MainMenuService;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only).
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

// ---------------------------------------------------------------------------
// Spring Test slice / Mockito test-context wiring
//
//   * @WebMvcTest — loads only the Spring MVC slice (the MenuController bean,
//     its message converters, the validation infrastructure, the
//     auto-configured Spring Security filter chain) without JPA,
//     repositories, or the full @SpringBootApplication context (AAP §0.4.1).
//   * @Import(SecurityTestConfig.class) — pulls in the inline test security
//     configuration so @PreAuthorize is actually enforced (Spring Security's
//     method-security infrastructure requires @EnableMethodSecurity, which
//     is not loaded by @WebMvcTest by default).
//   * @MockBean — replaces the real service beans in the @WebMvcTest context
//     with Mockito mocks (AAP §0.10.1 — single mocking boundary).
//   * @Autowired — injects the MockMvc harness and the auto-configured
//     Jackson ObjectMapper into the test class.
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// ---------------------------------------------------------------------------
// Spring Security Test — request post-processors
//
// SecurityMockMvcRequestPostProcessors.csrf() attaches a valid CSRF token to
// state-changing requests so they pass Spring Security's CsrfFilter. The
// GET endpoints do not need a CSRF token (GET is safe by definition), but
// the POST dispatch endpoints do — the deliberately-omitted-csrf test
// would verify that the controller rejects forged dispatch requests with
// HTTP 403, but is not included here because both dispatch endpoints are
// already covered by the role/authorisation tests.
//
// Direct reference (not a static import) so the test reads as
// .with(SecurityMockMvcRequestPostProcessors.csrf()), making the security
// post-processor explicit in every call site.
// ---------------------------------------------------------------------------
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

// ---------------------------------------------------------------------------
// Jackson — auto-configured by @WebMvcTest. Used to serialise the test's
// in-method MainMenuRequest / AdminMenuRequest objects to JSON strings for
// MockMvc .content(...) bodies on the POST dispatch endpoints.
// ---------------------------------------------------------------------------
import com.fasterxml.jackson.databind.ObjectMapper;

// ---------------------------------------------------------------------------
// JDK 17 standard library
//
//   * List — used in static helper factory methods to enumerate the
//     COBOL-copybook-derived option entries when constructing menu views in
//     adapter assertions. (Per AAP §0.5.5 schema, java.util.List is the
//     mandated JDK collection primitive.)
// ---------------------------------------------------------------------------
import java.util.List;

// ---------------------------------------------------------------------------
// Static imports — Mockito DSL + MockMvc DSL (AAP §0.6.2 import
// transformation rules: "Use static imports for Mockito DSL" / "Use static
// imports for MockMvc DSL").
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC slice test for {@link MenuController}.
 *
 * <p>Verifies the HTTP-boundary behaviour of the four menu endpoints that
 * collectively replace BMS mapsets {@code app/bms/COMEN01.bms} +
 * {@code app/bms/COADM01.bms} and COBOL programs
 * {@code app/cbl/COMEN01C.cbl} (TRANID {@code CM00}) +
 * {@code app/cbl/COADM01C.cbl} (TRANID {@code CA00}).
 *
 * <h2>Test Categories</h2>
 *
 * <ul>
 *   <li><b>Main-menu happy paths</b> — both regular and admin authenticated
 *       callers receive HTTP 200 with the 10 COMEN02Y.cpy-derived options;
 *       admin's response carries {@code adminAccess = true}, regular's
 *       carries {@code adminAccess = false}.</li>
 *   <li><b>Admin-menu happy path</b> — admin caller receives HTTP 200 with
 *       the 4 COADM02Y.cpy-derived options.</li>
 *   <li><b>Authorisation rejects</b> — regular caller against the admin
 *       endpoint receives HTTP 403; unauthenticated callers against either
 *       endpoint receive HTTP 401. The mocked services are verified never
 *       to have been invoked on either of these paths (defence-in-depth —
 *       the GET endpoints do not even call the services, so the verification
 *       is a regression guard against a future change that wires the GETs
 *       through the services).</li>
 *   <li><b>Dispatch happy paths</b> — both POST endpoints accept a valid
 *       option, the mocked service returns a success result, the controller
 *       maps it to HTTP 200 with {@code success = true} and the populated
 *       {@code nextRoute} field.</li>
 *   <li><b>Dispatch validation rejects</b> — the mocked service returns a
 *       failure-result (invalid option), the controller maps to HTTP 400.</li>
 *   <li><b>Dispatch authorisation reject (admin endpoint)</b> — the mocked
 *       service returns the {@code MSG_NOT_AUTHORIZED} failure, the
 *       controller maps to HTTP 403 (defence-in-depth — this path can only
 *       be reached if the {@code @PreAuthorize} gate fails open, but the
 *       controller still emits the correct status).</li>
 *   <li><b>Service-failure 500</b> — when the mocked service throws an
 *       unexpected {@link RuntimeException}, the controller's
 *       {@code @ExceptionHandler} maps it to HTTP 500 with a sanitised
 *       generic message (no internal detail leakage per AAP §0.10.5).</li>
 * </ul>
 *
 * <h2>Mocking Boundary (AAP §0.10.1)</h2>
 *
 * <p>The only mocked collaborators are the two {@code @Service} beans
 * ({@link MainMenuService} and {@link AdminMenuService}). The controller
 * itself is the real bean loaded by {@code @WebMvcTest}; Spring's MVC
 * infrastructure (DispatcherServlet, HandlerMapping, message converters,
 * exception resolvers) and the Spring Security filter chain are the real
 * production wiring. Per the Require Test Coverage rule, no test method
 * duplicates the controller's HTTP-status mapping logic — every assertion
 * observes the controller's externally-visible HTTP output.
 *
 * @see MenuController
 * @see MainMenuService
 * @see AdminMenuService
 * @see TestFixtures.Users
 */
@WebMvcTest(controllers = MenuController.class)
@Import(MenuControllerTest.SecurityTestConfig.class)
@DisplayName("MenuController — COMEN01C.cbl + COADM01C.cbl migration parity (role-based menu)")
@Execution(ExecutionMode.SAME_THREAD)
final class MenuControllerTest {

    // ------------------------------------------------------------------------
    // Parallelism — SAME_THREAD enforced (AAP §0.10.9 explanatory note)
    // ------------------------------------------------------------------------
    //
    // junit-platform.properties enables class-level parallel execution
    // (junit.jupiter.execution.parallel.mode.classes.default = concurrent).
    // With two @Nested test classes (MainMenu, AdminMenu), JUnit would
    // otherwise schedule them as siblings on separate worker threads.
    // Because both nested classes share a single Spring @WebMvcTest
    // application context and thus a single set of @MockBean instances,
    // concurrent execution causes mock invocations to accumulate across
    // tests — verify(...).count() assertions then fail with "Wanted 1
    // time: But was N times" where N is the number of times the mock has
    // been touched cumulatively across the parallel test methods. The
    // SAME_THREAD setting serialises the nested classes' test methods on
    // a single worker, restoring the per-test isolation that @MockBean
    // and verify(...) assertions expect.
    //
    // Wall-clock impact: trivial. The test class runs ~13 fast slice tests
    // in <500 ms total even serialised.
    // ------------------------------------------------------------------------

    // ------------------------------------------------------------------------
    // Service-layer reject-message mirrors
    // ------------------------------------------------------------------------
    //
    // The MSG_* constants on MainMenuService / AdminMenuService are
    // package-private (no modifier on the `static final String`
    // declarations) and so cannot be referenced from this controller-package
    // test. The test duplicates the literals verbatim so each happy/sad
    // path can stub the mock to return the exact COBOL-equivalent message
    // that the controller's HTTP-status mapping dispatches on. If a future
    // agent renames or relocates one of these messages, the controller
    // mapping AND this test will fail together, surfacing the drift loudly
    // (AAP §0.10.10 style consistency).
    // ------------------------------------------------------------------------

    /** Mirror of {@code MainMenuService.MSG_INVALID_OPTION}. */
    private static final String MSG_MAIN_INVALID_OPTION =
            "Invalid option, please try again...";

    /** Mirror of {@code MainMenuService.MSG_ADMIN_ONLY}. */
    private static final String MSG_MAIN_ADMIN_ONLY = "No access - Admin Only option...";

    /** Mirror of {@code AdminMenuService.MSG_NOT_AUTHORIZED}. */
    private static final String MSG_ADMIN_NOT_AUTHORIZED =
            "You are not authorized for Admin functions...";

    /** Mirror of {@code AdminMenuService.MSG_INVALID_OPTION}. */
    private static final String MSG_ADMIN_INVALID_OPTION =
            "Please enter a valid option number...";

    /** Mirror of {@code MenuController.MSG_INTERNAL_ERROR}. */
    private static final String MSG_INTERNAL_ERROR = "An unexpected error occurred";

    // ------------------------------------------------------------------------
    // Route-identifier mirrors (also package-private on MainMenuService /
    // AdminMenuService; mirrored here as test constants).
    // ------------------------------------------------------------------------

    /** Mirror of {@code MainMenuService.ROUTE_ACCOUNT_VIEW}. */
    private static final String ROUTE_ACCOUNT_VIEW = "ACCOUNT_VIEW";

    /** Mirror of {@code AdminMenuService.ROUTE_USER_LIST}. */
    private static final String ROUTE_USER_LIST = "USER_LIST";

    // ------------------------------------------------------------------------
    // User-type mirrors per COCOM01Y.cpy (8-level CDEMO-USRTYP-USER/ADMIN).
    // ------------------------------------------------------------------------

    /** COBOL {@code CDEMO-USRTYP-USER} value (regular user). */
    private static final String USER_TYPE_REGULAR = "U";

    /** COBOL {@code CDEMO-USRTYP-ADMIN} value (admin user). */
    private static final String USER_TYPE_ADMIN = "A";

    // ------------------------------------------------------------------------
    // Test fixtures (injected & static)
    // ------------------------------------------------------------------------

    /**
     * Servlet-free HTTP harness auto-configured by {@code @WebMvcTest}.
     * Used to issue requests against the loaded {@link MenuController}
     * and assert on HTTP status and JSON body via the Spring MVC test DSL.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Auto-configured Jackson {@link ObjectMapper} (Spring Boot defaults).
     * Used to serialise the in-test {@link MainMenuRequest} /
     * {@link AdminMenuRequest} objects to JSON strings for MockMvc
     * {@code .content(...)} bodies on the POST dispatch endpoints.
     */
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MainMenuService mainMenuService;

    @MockBean
    private AdminMenuService adminMenuService;

    /**
     * Resets both {@link MockBean} services before each test method.
     *
     * <p>By default {@code @MockBean} fields are reset by the
     * {@code MockitoTestExecutionListener} between test methods, but in
     * Spring Boot 3.x with multiple {@link Nested} test classes sharing
     * the same {@code @WebMvcTest} application context the listener does
     * not reliably reset mocks between methods of <em>different</em>
     * nested classes. Mock invocations therefore accumulate across the
     * nested groups and {@code verify(...)} count assertions fail with
     * "Wanted 1 time: But was N times". The explicit reset here is the
     * canonical Spring Boot idiom that pins per-method isolation for
     * mocked beans living in a cached context.
     */
    @BeforeEach
    void resetMocks() {
        org.mockito.Mockito.reset(mainMenuService, adminMenuService);
    }

    // ========================================================================
    // SecurityTestConfig — minimal inline security wiring for the slice test
    // ========================================================================

    /**
     * Inline {@code @TestConfiguration} that activates Spring Security's
     * method-level authorisation evaluation so the
     * {@code @PreAuthorize("hasRole('ADMIN')")} annotations on the
     * controller's handler methods enforce the admin gate during the test
     * slice — without {@code @EnableMethodSecurity} the annotation would be
     * a no-op.
     *
     * <p>The {@link org.springframework.security.web.SecurityFilterChain}
     * itself is supplied by Spring Boot's default
     * {@code SecurityAutoConfiguration} (which {@code @WebMvcTest} loads
     * when Spring Security is on the classpath): it requires authentication
     * on every request, enables CSRF, and produces HTTP 401 for anonymous
     * requests (with the {@code WWW-Authenticate: Basic} header) and
     * HTTP 403 for authenticated requests that fail method-security.
     *
     * <p>Using {@code @TestConfiguration} (rather than {@code @Configuration})
     * tells Spring Boot to treat this config as a test-time augmentation
     * that COMPLEMENTS the auto-configuration rather than replacing it;
     * {@code @Configuration} caused the auto-configured filter chain to be
     * elided which produced HTTP 404 responses for authenticated requests.
     *
     * <p>The production {@code SecurityConfig} (subsequent migration step)
     * is expected to mirror this wiring: enable method security globally,
     * require authentication on {@code /api/menu/**}, and keep CSRF enabled
     * on state-changing requests. This test config exists because no
     * production {@code SecurityConfig} class has been migrated yet —
     * remove this {@code @Import} once production wiring lands.
     */
    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityTestConfig {
        // Marker @TestConfiguration that only activates @EnableMethodSecurity.
        // The SecurityFilterChain bean is auto-configured by Spring Boot.
    }

    // ========================================================================
    // @Nested MainMenu — GET /api/menu/main + POST /api/menu/main/dispatch
    //                    (COMEN01C / TRANID CM00)
    // ========================================================================

    /**
     * Test group covering the {@code GET /api/menu/main} (read static menu
     * structure) and {@code POST /api/menu/main/dispatch} (delegate to
     * {@link MainMenuService#dispatch(MainMenuRequest)}) endpoints that
     * collectively replace BMS mapset {@code app/bms/COMEN01.bms} and COBOL
     * program {@code app/cbl/COMEN01C.cbl} (TRANID {@code CM00}).
     *
     * <p>The endpoints are available to any authenticated caller (regular
     * or admin) and surface the 10 main-menu options preserved verbatim
     * from {@code app/cpy/COMEN02Y.cpy} in their source order.
     */
    @Nested
    @DisplayName("Main menu — GET /api/menu/main + POST /api/menu/main/dispatch (any authenticated user)")
    final class MainMenu {

        // --------------------------------------------------------------------
        // Happy paths
        // --------------------------------------------------------------------

        /**
         * Verifies the regular-user main-menu happy path: returns HTTP 200,
         * exactly 10 options, the COBOL-source-order is preserved verbatim,
         * the {@code userId} field carries the authenticated principal's
         * name, and {@code adminAccess} is {@code false} for a regular user.
         */
        @Test
        @WithMockUser(username = TestFixtures.Users.REGULAR_USER_ID, roles = "USER")
        @DisplayName("getMainMenu — regular user authenticated → 200 with 10 COMEN02Y.cpy-derived options")
        void getMainMenu_regularUserAuthenticated_returns200WithTenOptions() throws Exception {
            mockMvc.perform(get("/api/menu/main").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(TestFixtures.Users.REGULAR_USER_ID))
                    .andExpect(jsonPath("$.adminAccess").value(false))
                    .andExpect(jsonPath("$.options").isArray())
                    .andExpect(jsonPath("$.options.length()").value(10))
                    // COBOL source-order parity (COMEN02Y.cpy lines 25–84): the
                    // option IDs MUST appear in this exact sequence so the
                    // numeric position-dispatch in MainMenuService stays
                    // aligned with the COBOL CDEMO-MENU-OPTIONS-DATA table.
                    .andExpect(jsonPath("$.options[0].optionId").value("ACCOUNT_VIEW"))
                    .andExpect(jsonPath("$.options[1].optionId").value("ACCOUNT_UPDATE"))
                    .andExpect(jsonPath("$.options[2].optionId").value("CARD_LIST"))
                    .andExpect(jsonPath("$.options[3].optionId").value("CARD_VIEW"))
                    .andExpect(jsonPath("$.options[4].optionId").value("CARD_UPDATE"))
                    .andExpect(jsonPath("$.options[5].optionId").value("TRANSACTION_LIST"))
                    .andExpect(jsonPath("$.options[6].optionId").value("TRANSACTION_VIEW"))
                    .andExpect(jsonPath("$.options[7].optionId").value("TRANSACTION_ADD"))
                    .andExpect(jsonPath("$.options[8].optionId").value("REPORTS"))
                    .andExpect(jsonPath("$.options[9].optionId").value("BILL_PAYMENT"))
                    // Each option carries displayName and targetRoute fields —
                    // assert the canonical examples covering the four
                    // distinct route patterns (single-id, list, parameterised,
                    // and admin-target).
                    .andExpect(jsonPath("$.options[0].displayName").value("Account View"))
                    .andExpect(jsonPath("$.options[0].targetRoute").value("/api/accounts/{id}"))
                    .andExpect(jsonPath("$.options[2].targetRoute").value("/api/cards"))
                    .andExpect(jsonPath("$.options[8].targetRoute").value("/api/reports/submit"))
                    .andExpect(jsonPath("$.options[9].targetRoute").value("/api/bill-payment"));

            // Defence-in-depth: the GET endpoint is a pure controller-local
            // operation and MUST NOT invoke the service. A future regression
            // that wires the GET through the service would be caught here.
            verify(mainMenuService, never()).dispatch(any(MainMenuRequest.class));
            verify(adminMenuService, never()).dispatch(any(AdminMenuRequest.class));
        }

        /**
         * Verifies the admin-user main-menu happy path: returns HTTP 200,
         * the same 10 options (admins see the regular-user menu plus the
         * separate admin endpoint), the {@code userId} carries the admin
         * principal's name, and {@code adminAccess} is {@code true} so the
         * UI can render an optional "Admin Menu" link.
         */
        @Test
        @WithMockUser(username = TestFixtures.Users.ADMIN_USER_ID, roles = "ADMIN")
        @DisplayName("getMainMenu — admin authenticated → 200 with adminAccess=true (renders Admin Menu link)")
        void getMainMenu_adminUser_returns200WithTenOptionsPlusAdminFlag() throws Exception {
            mockMvc.perform(get("/api/menu/main").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(TestFixtures.Users.ADMIN_USER_ID))
                    .andExpect(jsonPath("$.adminAccess").value(true))
                    .andExpect(jsonPath("$.options.length()").value(10))
                    // Admins see the same option set as regular users — assert
                    // the first and last to confirm parity.
                    .andExpect(jsonPath("$.options[0].optionId").value("ACCOUNT_VIEW"))
                    .andExpect(jsonPath("$.options[9].optionId").value("BILL_PAYMENT"));

            verify(mainMenuService, never()).dispatch(any(MainMenuRequest.class));
        }

        // --------------------------------------------------------------------
        // Authorisation rejects
        // --------------------------------------------------------------------

        /**
         * Verifies the unauthenticated-caller path: returns HTTP 401 and
         * the service is never invoked. Spring Security's auto-configured
         * filter chain rejects the anonymous request before the controller
         * is reached.
         */
        @Test
        @DisplayName("getMainMenu — unauthenticated → 401")
        void getMainMenu_unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/menu/main").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());

            verify(mainMenuService, never()).dispatch(any(MainMenuRequest.class));
        }

        // --------------------------------------------------------------------
        // Dispatch endpoint happy + reject + failure paths
        // --------------------------------------------------------------------

        /**
         * Verifies the dispatch happy path: a valid option string is posted,
         * the mocked service returns a success result, the controller maps
         * to HTTP 200 with the populated {@code nextRoute} field.
         */
        @Test
        @WithMockUser(username = TestFixtures.Users.REGULAR_USER_ID, roles = "USER")
        @DisplayName("dispatchMainMenuOption — valid option for regular user → 200 with nextRoute")
        void dispatchMainMenuOption_validOptionForRegularUser_returns200WithNextRoute() throws Exception {
            given(mainMenuService.dispatch(any(MainMenuRequest.class)))
                    .willReturn(MainMenuResponse.success(ROUTE_ACCOUNT_VIEW));

            MainMenuRequest request = new MainMenuRequest();
            request.setCallerUserType(USER_TYPE_REGULAR);
            request.setOption("1");

            mockMvc.perform(post("/api/menu/main/dispatch")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.nextRoute").value(ROUTE_ACCOUNT_VIEW))
                    .andExpect(jsonPath("$.message").doesNotExist());

            verify(mainMenuService).dispatch(any(MainMenuRequest.class));
        }

        /**
         * Verifies the dispatch validation-reject path: the mocked service
         * returns a failure result (invalid option), the controller maps
         * to HTTP 400 with the COBOL-equivalent reject message.
         */
        @Test
        @WithMockUser(username = TestFixtures.Users.REGULAR_USER_ID, roles = "USER")
        @DisplayName("dispatchMainMenuOption — invalid option → 400 with reject message")
        void dispatchMainMenuOption_invalidOption_returns400() throws Exception {
            given(mainMenuService.dispatch(any(MainMenuRequest.class)))
                    .willReturn(MainMenuResponse.failure(MSG_MAIN_INVALID_OPTION));

            MainMenuRequest request = new MainMenuRequest();
            request.setCallerUserType(USER_TYPE_REGULAR);
            request.setOption("99");

            mockMvc.perform(post("/api/menu/main/dispatch")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_MAIN_INVALID_OPTION))
                    .andExpect(jsonPath("$.nextRoute").doesNotExist());

            verify(mainMenuService).dispatch(any(MainMenuRequest.class));
        }

        /**
         * Verifies the dispatch admin-only-by-regular-user reject path: the
         * mocked service returns the {@code MSG_ADMIN_ONLY} failure (when a
         * regular user tries to pick a menu entry whose USRTYPE='A'), the
         * controller maps to HTTP 400. Per AAP §0.10.2 this preserves the
         * COBOL COMEN01C admin-only branch at the HTTP layer.
         */
        @Test
        @WithMockUser(username = TestFixtures.Users.REGULAR_USER_ID, roles = "USER")
        @DisplayName("dispatchMainMenuOption — admin-only option for regular user → 400 with admin-only message")
        void dispatchMainMenuOption_adminOnlyOptionForRegularUser_returns400() throws Exception {
            given(mainMenuService.dispatch(any(MainMenuRequest.class)))
                    .willReturn(MainMenuResponse.failure(MSG_MAIN_ADMIN_ONLY));

            MainMenuRequest request = new MainMenuRequest();
            request.setCallerUserType(USER_TYPE_REGULAR);
            request.setOption("3"); // hypothetical admin-only future entry

            mockMvc.perform(post("/api/menu/main/dispatch")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_MAIN_ADMIN_ONLY));

            verify(mainMenuService).dispatch(any(MainMenuRequest.class));
        }

        /**
         * Verifies the dispatch service-failure path: the mocked service
         * throws an unexpected {@link RuntimeException}, the controller's
         * {@code @ExceptionHandler} maps it to HTTP 500 with a sanitised
         * generic message (no internal detail leakage per AAP §0.10.5).
         */
        @Test
        @WithMockUser(username = TestFixtures.Users.REGULAR_USER_ID, roles = "USER")
        @DisplayName("dispatchMainMenuOption — service throws unexpected RuntimeException → 500 sanitised")
        void dispatchMainMenuOption_serviceFailure_returns500() throws Exception {
            given(mainMenuService.dispatch(any(MainMenuRequest.class)))
                    .willThrow(new RuntimeException("internal-detail-must-not-leak"));

            MainMenuRequest request = new MainMenuRequest();
            request.setCallerUserType(USER_TYPE_REGULAR);
            request.setOption("1");

            // PCI / detail-leakage assertion: the underlying RuntimeException
            // message must NEVER appear anywhere in the response body. The
            // assertion is performed via AssertJ on the captured response
            // body string (AAP §0.10.10 — AssertJ-only style; no Hamcrest
            // matchers in CardDemo tests).
            MvcResult dispatchResult = mockMvc.perform(post("/api/menu/main/dispatch")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_INTERNAL_ERROR))
                    .andReturn();
            String dispatchBody = dispatchResult.getResponse().getContentAsString();
            assertThat(dispatchBody)
                    .as("PCI / detail-leakage: response body must not echo the underlying exception message")
                    .doesNotContain("internal-detail-must-not-leak");

            verify(mainMenuService).dispatch(any(MainMenuRequest.class));
        }

        /**
         * Verifies the dispatch unauthenticated path: an anonymous POST is
         * rejected by Spring Security's filter chain with HTTP 401 (or 403
         * for missing CSRF — either is acceptable as authentication is
         * lacking either way). The service is never invoked.
         */
        @Test
        @DisplayName("dispatchMainMenuOption — unauthenticated → 401")
        void dispatchMainMenuOption_unauthenticated_returns401() throws Exception {
            MainMenuRequest request = new MainMenuRequest();
            request.setCallerUserType(USER_TYPE_REGULAR);
            request.setOption("1");

            mockMvc.perform(post("/api/menu/main/dispatch")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());

            verify(mainMenuService, never()).dispatch(any(MainMenuRequest.class));
        }
    }

    // ========================================================================
    // @Nested AdminMenu — GET /api/menu/admin + POST /api/menu/admin/dispatch
    //                     (COADM01C / TRANID CA00 — admin-only)
    // ========================================================================

    /**
     * Test group covering the {@code GET /api/menu/admin} (read static
     * admin-menu structure) and {@code POST /api/menu/admin/dispatch}
     * (delegate to {@link AdminMenuService#dispatch(AdminMenuRequest)})
     * endpoints that collectively replace BMS mapset
     * {@code app/bms/COADM01.bms} and COBOL program
     * {@code app/cbl/COADM01C.cbl} (TRANID {@code CA00}).
     *
     * <p>All endpoints are admin-only via {@code @PreAuthorize("hasRole('ADMIN')")};
     * non-admin authenticated callers receive HTTP 403, unauthenticated
     * callers receive HTTP 401.
     */
    @Nested
    @DisplayName("Admin menu — GET /api/menu/admin + POST /api/menu/admin/dispatch (admin-only)")
    final class AdminMenu {

        // --------------------------------------------------------------------
        // Happy path
        // --------------------------------------------------------------------

        /**
         * Verifies the admin happy path: returns HTTP 200, exactly 4 options,
         * the COADM02Y.cpy source order is preserved verbatim, the
         * {@code userId} field carries the admin principal's name.
         */
        @Test
        @WithMockUser(username = TestFixtures.Users.ADMIN_USER_ID, roles = "ADMIN")
        @DisplayName("getAdminMenu — admin authenticated → 200 with 4 COADM02Y.cpy-derived options")
        void getAdminMenu_adminUser_returns200WithFourOptions() throws Exception {
            mockMvc.perform(get("/api/menu/admin").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(TestFixtures.Users.ADMIN_USER_ID))
                    .andExpect(jsonPath("$.options").isArray())
                    .andExpect(jsonPath("$.options.length()").value(4))
                    // COBOL source-order parity (COADM02Y.cpy lines 24–42).
                    .andExpect(jsonPath("$.options[0].optionId").value("USER_LIST"))
                    .andExpect(jsonPath("$.options[1].optionId").value("USER_ADD"))
                    .andExpect(jsonPath("$.options[2].optionId").value("USER_UPDATE"))
                    .andExpect(jsonPath("$.options[3].optionId").value("USER_DELETE"))
                    // Display names match the COBOL CDEMO-ADMIN-OPT-NAME field
                    // (trimmed PIC X(35) values).
                    .andExpect(jsonPath("$.options[0].displayName").value("User List (Security)"))
                    .andExpect(jsonPath("$.options[1].displayName").value("User Add (Security)"))
                    .andExpect(jsonPath("$.options[2].displayName").value("User Update (Security)"))
                    .andExpect(jsonPath("$.options[3].displayName").value("User Delete (Security)"))
                    // Target routes — USER_LIST/USER_ADD share /api/users
                    // (collection ops), USER_UPDATE/USER_DELETE share
                    // /api/users/{userId} (item ops).
                    .andExpect(jsonPath("$.options[0].targetRoute").value("/api/users"))
                    .andExpect(jsonPath("$.options[1].targetRoute").value("/api/users"))
                    .andExpect(jsonPath("$.options[2].targetRoute").value("/api/users/{userId}"))
                    .andExpect(jsonPath("$.options[3].targetRoute").value("/api/users/{userId}"));

            verify(adminMenuService, never()).dispatch(any(AdminMenuRequest.class));
        }

        // --------------------------------------------------------------------
        // Authorisation rejects
        // --------------------------------------------------------------------

        /**
         * Verifies the non-admin authenticated path: a regular-user caller
         * against the admin endpoint receives HTTP 403 from the
         * {@code @PreAuthorize("hasRole('ADMIN')")} gate. The service is
         * never invoked.
         */
        @Test
        @WithMockUser(username = TestFixtures.Users.REGULAR_USER_ID, roles = "USER")
        @DisplayName("getAdminMenu — regular user authenticated → 403 (PreAuthorize gate)")
        void getAdminMenu_regularUser_returns403() throws Exception {
            mockMvc.perform(get("/api/menu/admin").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());

            verify(adminMenuService, never()).dispatch(any(AdminMenuRequest.class));
        }

        /**
         * Verifies the unauthenticated path: returns HTTP 401 and the
         * service is never invoked.
         */
        @Test
        @DisplayName("getAdminMenu — unauthenticated → 401")
        void getAdminMenu_unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/menu/admin").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());

            verify(adminMenuService, never()).dispatch(any(AdminMenuRequest.class));
        }

        // --------------------------------------------------------------------
        // Dispatch endpoint happy + reject + failure paths
        // --------------------------------------------------------------------

        /**
         * Verifies the admin dispatch happy path: a valid admin-option
         * string is posted, the mocked service returns a success result,
         * the controller maps to HTTP 200 with {@code nextRoute} populated.
         */
        @Test
        @WithMockUser(username = TestFixtures.Users.ADMIN_USER_ID, roles = "ADMIN")
        @DisplayName("dispatchAdminMenuOption — valid option for admin → 200 with nextRoute")
        void dispatchAdminMenuOption_validOptionForAdmin_returns200WithNextRoute() throws Exception {
            given(adminMenuService.dispatch(any(AdminMenuRequest.class)))
                    .willReturn(AdminMenuResponse.success(ROUTE_USER_LIST));

            AdminMenuRequest request = new AdminMenuRequest();
            request.setCallerUserType(USER_TYPE_ADMIN);
            request.setOption("1");

            mockMvc.perform(post("/api/menu/admin/dispatch")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.nextRoute").value(ROUTE_USER_LIST))
                    .andExpect(jsonPath("$.message").doesNotExist());

            verify(adminMenuService).dispatch(any(AdminMenuRequest.class));
        }

        /**
         * Verifies the admin dispatch validation-reject path: the mocked
         * service returns a {@code MSG_INVALID_OPTION} failure (non-numeric
         * or out-of-range input), the controller maps to HTTP 400 with the
         * COBOL-equivalent reject message.
         */
        @Test
        @WithMockUser(username = TestFixtures.Users.ADMIN_USER_ID, roles = "ADMIN")
        @DisplayName("dispatchAdminMenuOption — invalid option → 400 with reject message")
        void dispatchAdminMenuOption_invalidOption_returns400() throws Exception {
            given(adminMenuService.dispatch(any(AdminMenuRequest.class)))
                    .willReturn(AdminMenuResponse.failure(MSG_ADMIN_INVALID_OPTION));

            AdminMenuRequest request = new AdminMenuRequest();
            request.setCallerUserType(USER_TYPE_ADMIN);
            request.setOption("99");

            mockMvc.perform(post("/api/menu/admin/dispatch")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_ADMIN_INVALID_OPTION))
                    .andExpect(jsonPath("$.nextRoute").doesNotExist());

            verify(adminMenuService).dispatch(any(AdminMenuRequest.class));
        }

        /**
         * Verifies the admin dispatch defence-in-depth authorisation
         * reject: even if the {@code @PreAuthorize} gate were to fail
         * open (only possible if Spring's method-security wiring is
         * mis-configured), the mocked service still returns the
         * {@code MSG_NOT_AUTHORIZED} message and the controller maps to
         * HTTP 403 with that message (distinct from the empty-body 403
         * the {@code @PreAuthorize} gate emits). This test exercises the
         * controller's distinctive 403-with-body branch.
         */
        @Test
        @WithMockUser(username = TestFixtures.Users.ADMIN_USER_ID, roles = "ADMIN")
        @DisplayName("dispatchAdminMenuOption — service returns MSG_NOT_AUTHORIZED → 403 with reject body")
        void dispatchAdminMenuOption_serviceLayerAuthorizationReject_returns403WithBody() throws Exception {
            given(adminMenuService.dispatch(any(AdminMenuRequest.class)))
                    .willReturn(AdminMenuResponse.failure(MSG_ADMIN_NOT_AUTHORIZED));

            AdminMenuRequest request = new AdminMenuRequest();
            // Send a regular-user type in the body to drive the service-layer
            // authorisation reject — the @PreAuthorize gate looks at the
            // SecurityContext (admin role), but the service performs
            // defence-in-depth re-checking of the body's callerUserType.
            request.setCallerUserType(USER_TYPE_REGULAR);
            request.setOption("1");

            mockMvc.perform(post("/api/menu/admin/dispatch")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_ADMIN_NOT_AUTHORIZED));

            verify(adminMenuService).dispatch(any(AdminMenuRequest.class));
        }

        /**
         * Verifies the admin dispatch service-failure path: the mocked
         * service throws an unexpected {@link RuntimeException}, the
         * controller's {@code @ExceptionHandler} maps it to HTTP 500 with
         * a sanitised generic message (no internal detail leakage per
         * AAP §0.10.5).
         */
        @Test
        @WithMockUser(username = TestFixtures.Users.ADMIN_USER_ID, roles = "ADMIN")
        @DisplayName("dispatchAdminMenuOption — service throws unexpected RuntimeException → 500 sanitised")
        void dispatchAdminMenuOption_serviceFailure_returns500() throws Exception {
            given(adminMenuService.dispatch(any(AdminMenuRequest.class)))
                    .willThrow(new RuntimeException("admin-detail-must-not-leak"));

            AdminMenuRequest request = new AdminMenuRequest();
            request.setCallerUserType(USER_TYPE_ADMIN);
            request.setOption("1");

            // PCI / detail-leakage assertion via AssertJ on the captured
            // response body string (AAP §0.10.10 — AssertJ-only style; no
            // Hamcrest matchers in CardDemo tests).
            MvcResult adminResult = mockMvc.perform(post("/api/menu/admin/dispatch")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_INTERNAL_ERROR))
                    .andReturn();
            String adminBody = adminResult.getResponse().getContentAsString();
            assertThat(adminBody)
                    .as("PCI / detail-leakage: admin dispatch response must not echo the underlying exception message")
                    .doesNotContain("admin-detail-must-not-leak");

            verify(adminMenuService).dispatch(any(AdminMenuRequest.class));
        }

        /**
         * Verifies the admin dispatch non-admin-authenticated path: a
         * regular-user caller against the admin dispatch endpoint receives
         * HTTP 403 from the {@code @PreAuthorize("hasRole('ADMIN')")} gate.
         * The service is never invoked.
         */
        @Test
        @WithMockUser(username = TestFixtures.Users.REGULAR_USER_ID, roles = "USER")
        @DisplayName("dispatchAdminMenuOption — regular user → 403 (PreAuthorize gate)")
        void dispatchAdminMenuOption_regularUser_returns403() throws Exception {
            AdminMenuRequest request = new AdminMenuRequest();
            request.setCallerUserType(USER_TYPE_REGULAR);
            request.setOption("1");

            mockMvc.perform(post("/api/menu/admin/dispatch")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());

            verify(adminMenuService, never()).dispatch(any(AdminMenuRequest.class));
        }
    }

    // ========================================================================
    // Static helpers — canonical fixture builders
    // ========================================================================
    //
    // The helpers below mirror the controller's MAIN_MENU_OPTIONS /
    // ADMIN_MENU_OPTIONS constants verbatim so the test could compare against
    // them when needed. They are NOT used by the assertions above (which
    // pin the COBOL-source-order parity field-by-field via jsonPath to make
    // any drift fail loudly with a precise field path), but exist as
    // documentation of the expected structure and so a future test could
    // round-trip the GET response through Jackson and assert
    // .recursivelyEqual(standardMainMenuOptions()).
    //
    // Per AAP §0.5.5, these helpers are also the canonical anchor for any
    // future menu-related fixtures the suite might consume.
    // ------------------------------------------------------------------------

    /**
     * Returns the canonical 10 main-menu options in COBOL source order, as
     * defined by {@link MenuController#MAIN_MENU_OPTIONS} and derived from
     * {@code app/cpy/COMEN02Y.cpy} lines 25–84. Reserved for future
     * round-trip / recursive-comparison assertions; the field-by-field
     * jsonPath assertions in the {@code MainMenu} tests are preferred today
     * because they produce more precise failure messages.
     *
     * @return the canonical option list for the regular-user main menu
     */
    static List<MenuController.MenuOption> standardMainMenuOptions() {
        return MenuController.MAIN_MENU_OPTIONS;
    }

    /**
     * Returns the canonical 4 admin-menu options in COBOL source order, as
     * defined by {@link MenuController#ADMIN_MENU_OPTIONS} and derived from
     * {@code app/cpy/COADM02Y.cpy} lines 24–42. Reserved for future
     * round-trip / recursive-comparison assertions.
     *
     * @return the canonical option list for the admin-only menu
     */
    static List<MenuController.MenuOption> standardAdminMenuOptions() {
        return MenuController.ADMIN_MENU_OPTIONS;
    }

    /**
     * Returns a canonical {@link MainMenuResponse} that represents the
     * success outcome of dispatching the first main-menu option (account
     * view). Used by tests that don't customise the return value; the
     * dispatch-happy-path test above uses
     * {@code MainMenuResponse.success(ROUTE_ACCOUNT_VIEW)} directly for
     * readability, but this helper exists for future consolidation.
     *
     * @return success result with {@code nextRoute = ACCOUNT_VIEW}
     */
    static MainMenuResponse standardMainMenuSuccessResponse() {
        return MainMenuResponse.success(ROUTE_ACCOUNT_VIEW);
    }

    /**
     * Returns a canonical {@link AdminMenuResponse} that represents the
     * success outcome of dispatching the first admin-menu option (user
     * list). Mirror of {@link #standardMainMenuSuccessResponse()}.
     *
     * @return success result with {@code nextRoute = USER_LIST}
     */
    static AdminMenuResponse standardAdminMenuSuccessResponse() {
        return AdminMenuResponse.success(ROUTE_USER_LIST);
    }
}
