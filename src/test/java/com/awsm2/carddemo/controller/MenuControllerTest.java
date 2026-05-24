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

import com.awsm2.carddemo.config.SecurityConfig;
import com.awsm2.carddemo.dto.AdminMenuDto;
import com.awsm2.carddemo.dto.ApiResponse;
import com.awsm2.carddemo.dto.MainMenuDto;
import com.awsm2.carddemo.dto.MenuOptionDto;
import com.awsm2.carddemo.exception.GlobalExceptionHandler;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.security.JwtAuthenticationFilter;
import com.awsm2.carddemo.security.JwtTokenProvider;
import com.awsm2.carddemo.service.MenuService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.BDDMockito;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc slice test for {@link MenuController}.
 *
 * <p><b>System Under Test (SUT).</b> The {@code MenuController} replaces
 * the two CICS COBOL programs that share an identical control structure
 * but differ in their working-storage option table:
 * <ul>
 *   <li>{@code app/cbl/COMEN01C.cbl} (CICS transaction id {@code CM00})
 *       &mdash; Main Menu, reading {@code app/cpy/COMEN02Y.cpy} (10 entries)
 *       and rendered through {@code app/bms/COMEN01.bms}.</li>
 *   <li>{@code app/cbl/COADM01C.cbl} (CICS transaction id {@code CA00})
 *       &mdash; Admin Menu, reading {@code app/cpy/COADM02Y.cpy} (4 entries)
 *       and rendered through {@code app/bms/COADM01.bms}.</li>
 * </ul>
 * It is the only multi-COBOL-source controller in the project (AAP &sect;0.4.1).
 *
 * <p><b>Endpoint inventory under test (AAP &sect;0.3.4).</b></p>
 * <ul>
 *   <li>{@code GET /api/menu/main} &mdash; available to USER + ADMIN
 *       ({@code @PreAuthorize("hasAnyRole('USER','ADMIN')")}).</li>
 *   <li>{@code GET /api/menu/admin} &mdash; ADMIN only
 *       ({@code @PreAuthorize("hasRole('ADMIN')")}).</li>
 *   <li>{@code POST /api/menu/resolve} &mdash; intentionally NOT present;
 *       removed by the CP5 scope/security review (see
 *       {@link MenuController} class-level Javadoc "Scope discipline"
 *       section). The {@code resolveMenu_*} test methods below verify
 *       this removal by asserting HTTP 404 (or HTTP 401 for anonymous
 *       callers, because the surrounding {@code /api/**} matcher in
 *       {@link com.awsm2.carddemo.config.SecurityConfig} requires an
 *       authenticated principal) for every imagined input shape that
 *       the original endpoint would have accepted.</li>
 * </ul>
 *
 * <p><b>Slice composition.</b> {@code @WebMvcTest(MenuController.class)}
 * loads only this controller plus the Spring MVC infrastructure beans
 * (MockMvc, ObjectMapper, message converters). The Spring Security
 * filter chain is loaded automatically because Spring Security is on
 * the classpath, and we additionally {@code @Import} the production
 * {@link com.awsm2.carddemo.config.SecurityConfig} so that the test
 * exercises the EXACT same filter chain wiring used in production
 * &mdash; the {@code restAuthenticationEntryPoint()} that maps anonymous
 * rejections to HTTP 401 + standardized {@link ApiResponse} error
 * envelope, the URL-level matcher
 * {@code requestMatchers(HttpMethod.GET, "/api/menu/admin").hasRole("ADMIN")},
 * and the {@code @EnableMethodSecurity(prePostEnabled = true)} that
 * enforces the per-method {@code @PreAuthorize} gates.
 *
 * <p>The {@code MenuService} collaborator is replaced by a Mockito
 * {@link MockBean}; {@link JwtTokenProvider} and
 * {@link JwtAuthenticationFilter} are also mocked to satisfy
 * {@link com.awsm2.carddemo.config.SecurityConfig}'s constructor
 * dependencies without loading JWT signing keys, Secrets Manager
 * integration, or {@code @RefreshScope} beans. CRITICAL: because
 * Mockito's default mock for a {@code Filter} does NOT invoke
 * {@code chain.doFilter()}, every request would otherwise be silently
 * dropped (the filter would short-circuit the chain and the response
 * body would be empty). {@link #setUpFilterMock()} explicitly stubs
 * the mocked filter to delegate to the next filter so the chain
 * proceeds to Spring Security's authorization checks and the
 * controller dispatch.
 *
 * <p>{@link GlobalExceptionHandler} is explicitly imported via
 * {@code @Import} because {@code @WebMvcTest} does not auto-load this
 * advice bean by default; without it, the standardized
 * {@link ApiResponse} error envelope shape would not be observed in
 * tests (the
 * {@code GlobalExceptionHandler#handleNoResourceFound(NoResourceFoundException, HttpServletRequest)}
 * advice handler in particular is what surfaces HTTP 404 with
 * {@code code = "ENDPOINT_NOT_FOUND"} when the removed
 * {@code POST /api/menu/resolve} is invoked).
 *
 * <p><b>Security context for tests.</b> Authentication state is
 * established with Spring Security Test's {@code @WithMockUser} and
 * {@code @WithAnonymousUser} annotations, which populate the
 * {@code SecurityContextHolder} for the duration of the test method
 * without requiring real JWT tokens. The {@code username} attribute on
 * {@code @WithMockUser} becomes the value returned by
 * {@code Authentication.getName()}, which the controller receives via
 * {@code @AuthenticationPrincipal String userId} (note: the controller
 * uses the principal-typed parameter, not the SpEL
 * {@code expression="name"} form &mdash; see {@link MenuController} QA
 * finding CR-02).
 *
 * <p><b>CSRF.</b> {@link com.awsm2.carddemo.config.SecurityConfig}
 * disables CSRF for the entire {@code /api/**} surface (the API is JWT
 * authenticated, not cookie-authenticated). {@link #csrf()} request
 * post-processors are nonetheless attached to POST requests in this
 * test class so the tests remain meaningful if CSRF protection is
 * later re-enabled for any portion of the surface.
 *
 * <p><b>TestPropertySource.</b> The
 * {@code carddemo.security.jwt.signing-key} placeholder satisfies
 * Spring's property-resolution requirements for any
 * {@code @Value("${carddemo.security.jwt.signing-key}")} reference
 * activated when {@code SecurityConfig} is imported; since
 * {@code JwtTokenProvider} is mocked the actual value is not used to
 * sign tokens. The CORS allowed-origins value is set to a concrete
 * host so {@link com.awsm2.carddemo.config.SecurityConfig}'s
 * {@code corsConfigurationSource()} bean has a non-wildcard origin
 * list (a defensive setting that mirrors the production posture).
 *
 * @see MenuController
 * @see MenuService
 * @see com.awsm2.carddemo.config.SecurityConfig
 * @see GlobalExceptionHandler
 */
@WebMvcTest(MenuController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // The base configuration uses Secrets Manager-backed JWT signing
        // key resolution. For a controller-only slice test we never issue
        // or validate tokens (each test uses Spring Security's
        // @WithMockUser to inject the authentication directly), so we
        // suppress JwtTokenProvider initialization by mocking it; this
        // also keeps the slice fast.
        "carddemo.security.jwt.signing-key=test-only-jwt-signing-key-32-bytes-min-length",
        "carddemo.security.cors.allowed-origins=http://localhost:3000"
})
@DisplayName("MenuController — GET /api/menu/main, GET /api/menu/admin, POST /api/menu/resolve")
class MenuControllerTest {

    /**
     * MockMvc fluent client into the Spring MVC dispatcher, configured by
     * {@code @WebMvcTest} to route through the SUT controller plus the
     * Spring Security filter chain.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Application Jackson mapper used to serialize JSON request bodies
     * for {@code POST /api/menu/resolve} verification tests. Auto-configured
     * by Spring Boot's {@code JacksonAutoConfiguration} in the
     * {@code @WebMvcTest} slice. Also consumed by
     * {@link com.awsm2.carddemo.config.SecurityConfig} when writing
     * Spring-Security-layer error envelopes through
     * {@code restAuthenticationEntryPoint()} and the
     * {@code AccessDeniedHandler} configured in the filter chain.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Mock of the {@link MenuService} collaborator. The real service holds
     * the verbatim COBOL literal-storage menu tables from
     * {@code COMEN02Y.cpy} and {@code COADM02Y.cpy}; the mock returns
     * test-controlled fixtures so each test exercises only the controller's
     * routing, validation, security, and envelope behaviour. Used with
     * {@link BDDMockito#given} and {@link BDDMockito#willThrow} stubbing
     * plus {@link ArgumentCaptor} for verifying that the
     * {@code @AuthenticationPrincipal} value reaches the controller
     * (the value the controller forwards to the service is the COBOL
     * {@code SEC-USR-TYPE} discriminator, not the userId &mdash; see
     * {@link MenuController#getMainMenu(String)} Javadoc).
     */
    @MockBean
    private MenuService menuService;

    /**
     * Mock of {@link JwtTokenProvider} required by Spring's bean factory to
     * satisfy {@link JwtAuthenticationFilter}'s constructor and
     * {@link com.awsm2.carddemo.config.SecurityConfig}'s filter-chain
     * wiring. The mock is never invoked because tests populate the
     * security context via {@code @WithMockUser}/{@code @WithAnonymousUser}
     * rather than via real bearer-token validation.
     */
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    /**
     * Mock of {@link JwtAuthenticationFilter} required by Spring Security's
     * filter-chain wiring; the chain registers the filter as a
     * pre-{@code UsernamePasswordAuthenticationFilter} via
     * {@link com.awsm2.carddemo.config.SecurityConfig#securityFilterChain}.
     * Mocking it (rather than letting the real filter execute) avoids
     * needing real JWT signing keys or Secrets Manager integration during
     * the slice test.
     *
     * <p>CRITICAL: Mockito's default mock for a {@code Filter} does NOT
     * invoke {@code chain.doFilter()} &mdash; this would drop every
     * request silently (the filter would short-circuit the chain, the
     * controller would never be invoked, and {@code MockMvc} would
     * observe an empty HTTP 200 response). {@link #setUpFilterMock()}
     * explicitly stubs the {@code doFilter} method with a pass-through
     * answer so the chain proceeds normally; tests rely on
     * {@code @WithMockUser}/{@code @WithAnonymousUser} to populate the
     * security context BEFORE the request enters the chain, exactly as
     * the production {@link JwtAuthenticationFilter} would have done
     * after validating a bearer token.</p>
     */
    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Reusable main-menu fixture matching the verbatim
     * {@code CARDDEMO-MAIN-MENU-OPTIONS} entries from
     * {@code app/cpy/COMEN02Y.cpy}.  Carries every option's COBOL
     * {@code targetProgram} identifier verbatim for parallel-run
     * traceability per AAP &sect;0.7.3.
     */
    private MainMenuDto mainMenuFixture;

    /**
     * Reusable admin-menu fixture matching the verbatim
     * {@code CARDDEMO-ADMIN-MENU-OPTIONS} entries from
     * {@code app/cpy/COADM02Y.cpy}.
     */
    private AdminMenuDto adminMenuFixture;

    /**
     * Fixture builder and filter-mock pass-through configurator.
     * Recreated before each test to keep tests independent and to
     * avoid the cross-test pollution that mutable shared fixtures
     * can introduce.
     */
    @BeforeEach
    void setUpFilterMock() throws Exception {
        // ----------------------------------------------------------------
        // Configure the @MockBean JwtAuthenticationFilter to PASS THROUGH.
        //
        // Mockito's default mock for the Filter.doFilter(req, resp, chain)
        // method does NOT invoke chain.doFilter(req, resp), so the chain
        // halts at the filter and the controller is never dispatched. The
        // observable symptom is HTTP 200 with empty body across every
        // test. Stubbing the mock with a "delegate to chain" answer
        // restores the production-equivalent behavior of a filter that
        // simply continues the chain when no bearer token is present
        // (see JwtAuthenticationFilter.doFilterInternal at L246).
        //
        // The doAnswer style is used because doFilter is a void method
        // that cannot be stubbed with thenReturn(...). The lambda below
        // calls chain.doFilter on the same (request, response) so the
        // request reaches the controller, and Spring Security's
        // SecurityContextHolderFilter picks up the @WithMockUser /
        // @WithAnonymousUser authentication that was placed on the
        // SecurityContextHolder by WithSecurityContextTestExecutionListener
        // BEFORE the request entered the chain.
        // ----------------------------------------------------------------
        Mockito.doAnswer(invocation -> {
            ServletRequest req = invocation.getArgument(0);
            ServletResponse resp = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(req, resp);
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());

        // ----------------------------------------------------------------
        // Build the main-menu and admin-menu fixtures matching the
        // verbatim COBOL literal-storage tables from COMEN02Y.cpy and
        // COADM02Y.cpy. The DTOs are immutable records, so re-use across
        // tests would be safe; explicit per-test instantiation is
        // preserved for clarity.
        // ----------------------------------------------------------------

        // COBOL: COMEN02Y.cpy literal-storage table (10 entries, USR-TYPE='U').
        List<MenuOptionDto> mainOptions = List.of(
                new MenuOptionDto(1, "Account View",
                        "COACTVWC", "/api/accounts/{id}", "U", true),
                new MenuOptionDto(2, "Account Update",
                        "COACTUPC", "/api/accounts/{id}", "U", true),
                new MenuOptionDto(3, "Credit Card List",
                        "COCRDLIC", "/api/cards", "U", true),
                new MenuOptionDto(4, "Credit Card View",
                        "COCRDSLC", "/api/cards/{cardNumber}", "U", true),
                new MenuOptionDto(5, "Credit Card Update",
                        "COCRDUPC", "/api/cards/{cardNumber}", "U", true),
                new MenuOptionDto(6, "Transaction List",
                        "COTRN00C", "/api/transactions", "U", true),
                new MenuOptionDto(7, "Transaction View",
                        "COTRN01C", "/api/transactions/{id}", "U", true),
                new MenuOptionDto(8, "Transaction Add",
                        "COTRN02C", "/api/transactions", "U", true),
                new MenuOptionDto(9, "Transaction Reports",
                        "CORPT00C", "/api/reports/submit", "U", true),
                new MenuOptionDto(10, "Bill Payment",
                        "COBIL00C", "/api/billing/pay", "U", true)
        );
        mainMenuFixture = new MainMenuDto(
                "USER0001", "U", "Test", "User",
                mainOptions, "AWS CardDemo - Main Menu");

        // COBOL: COADM02Y.cpy literal-storage table (4 entries, admin-only).
        List<MenuOptionDto> adminOptions = List.of(
                new MenuOptionDto(1, "User List (Security)",
                        "COUSR00C", "/api/admin/users", "A", true),
                new MenuOptionDto(2, "User Add (Security)",
                        "COUSR01C", "/api/admin/users", "A", true),
                new MenuOptionDto(3, "User Update (Security)",
                        "COUSR02C", "/api/admin/users/{id}", "A", true),
                new MenuOptionDto(4, "User Delete (Security)",
                        "COUSR03C", "/api/admin/users/{id}", "A", true)
        );
        adminMenuFixture = new AdminMenuDto(
                "ADMIN001", "Admin", "User",
                adminOptions, "AWS CardDemo - Admin Menu");
    }

    // =====================================================================
    // Phase 1 — GET /api/menu/main (USER and ADMIN both allowed)
    // COBOL: COMEN01C / Tran-ID CM00 — Main Menu for regular users
    // =====================================================================

    /**
     * Verifies that a USER-role caller receives HTTP 200 from
     * {@code GET /api/menu/main} together with the expected
     * {@link MainMenuDto} payload wrapped in the standardized
     * {@link ApiResponse} envelope.
     *
     * <p>Stubs {@link MenuService#getMainMenu(String)} to return the
     * 10-option main-menu fixture (matching the verbatim
     * {@code CARDDEMO-MAIN-MENU-OPTIONS} entries from
     * {@code COMEN02Y.cpy}). Asserts on every component of the response
     * payload that downstream client code may consume:
     * {@code $.data.userId}, {@code $.data.userType},
     * {@code $.data.firstName}, {@code $.data.lastName},
     * {@code $.data.options[*]}, {@code $.data.title}.
     */
    @Test
    @DisplayName("getMainMenu returns 200 + envelope for USER role")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void getMainMenu_returns200ForUser() throws Exception {
        BDDMockito.given(menuService.getMainMenu(any())).willReturn(mainMenuFixture);

        mockMvc.perform(get("/api/menu/main"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.userId").value("USER0001"))
                .andExpect(jsonPath("$.data.userType").value("U"))
                .andExpect(jsonPath("$.data.firstName").value("Test"))
                .andExpect(jsonPath("$.data.lastName").value("User"))
                .andExpect(jsonPath("$.data.options").isArray())
                .andExpect(jsonPath("$.data.options.length()").value(10))
                .andExpect(jsonPath("$.data.title").value("AWS CardDemo - Main Menu"));
    }

    /**
     * Verifies that an ADMIN-role caller can also retrieve the main menu
     * (the {@code @PreAuthorize("hasAnyRole('USER','ADMIN')")} gate on
     * {@code getMainMenu} grants both roles).
     *
     * <p>The COBOL {@code COSGN00C} sign-on program routed admin users
     * to {@code COADM01C} by default but did not prevent them from
     * subsequently returning to the main menu &mdash; this endpoint
     * preserves that behaviour.
     */
    @Test
    @DisplayName("getMainMenu returns 200 for ADMIN role (hasAnyRole gate)")
    @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
    void getMainMenu_returns200ForAdmin() throws Exception {
        BDDMockito.given(menuService.getMainMenu(any())).willReturn(mainMenuFixture);

        mockMvc.perform(get("/api/menu/main"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.options").isArray());
    }

    /**
     * Verifies that an unauthenticated caller is rejected with HTTP 401
     * (REST-conventional "authentication required") for
     * {@code GET /api/menu/main}.
     *
     * <p>The rejection is enforced by Spring Security's
     * {@code authorizeHttpRequests} matcher
     * {@code .requestMatchers("/api/**").hasAnyRole("USER","ADMIN")}
     * combined with the
     * {@code com.awsm2.carddemo.config.SecurityConfig#restAuthenticationEntryPoint()}
     * which converts anonymous access into HTTP 401 + standardized
     * envelope. The mocked service must never be invoked for
     * anonymous callers.
     */
    @Test
    @DisplayName("getMainMenu returns 401 for anonymous caller")
    @WithAnonymousUser
    void getMainMenu_returns401ForAnonymous() throws Exception {
        mockMvc.perform(get("/api/menu/main"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(menuService);
    }

    /**
     * Verifies that the {@code @AuthenticationPrincipal} value (the
     * principal name from {@code Authentication.getName()}, which is
     * the {@code username} attribute of {@code @WithMockUser}) is
     * received by the controller. The controller does not forward
     * the userId into the service (see {@link MenuController}'s Javadoc
     * for {@code getMainMenu}, which documents that the service is
     * stateless and does not personalise the menu by user identity);
     * the test therefore captures the {@code userType} argument that
     * the controller passes to {@link MenuService#getMainMenu(String)}
     * and asserts it equals the hardcoded {@code "U"} discriminator
     * configured on {@link MenuController#USER_TYPE_USER}.
     *
     * <p>This test confirms that the controller code path that reads
     * {@code @AuthenticationPrincipal} executed (the call would have
     * failed with a {@code SpelEvaluationException} or
     * {@code NullPointerException} if the principal extraction were
     * misconfigured per QA finding CR-02). The principal type-binding
     * is to {@link String} so any SpEL expression mismatch would
     * surface as a context-startup failure.
     */
    @Test
    @DisplayName("getMainMenu passes userType derived from authenticated principal to MenuService")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void getMainMenu_passesUserIdFromAuthenticationPrincipal() throws Exception {
        BDDMockito.given(menuService.getMainMenu(any())).willReturn(mainMenuFixture);

        mockMvc.perform(get("/api/menu/main"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> userTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(menuService).getMainMenu(userTypeCaptor.capture());
        // COBOL: COMEN01C controller passes USER-TYPE-USER ("U") so the
        // service's BUILD-MENU-OPTIONS filter matches every COMEN02Y.cpy
        // entry tagged USR-TYPE = 'U'. The presence of the userId on the
        // SecurityContext (set by @WithMockUser(username="USER0001")) is
        // implicit — the request reached the controller body, which
        // requires successful @AuthenticationPrincipal binding.
        org.junit.jupiter.api.Assertions.assertEquals(
                "U", userTypeCaptor.getValue(),
                "Expected controller to invoke MenuService.getMainMenu(\"U\") "
                + "matching COMEN01C's BUILD-MENU-OPTIONS filter literal");
    }

    /**
     * Verifies the per-option components of the response payload are
     * serialized verbatim from the COBOL {@code COMEN02Y.cpy} literal
     * storage. Asserts on the first option ({@code Account View} -&gt;
     * {@code COACTVWC} -&gt; {@code /api/accounts/{id}}) and the last
     * option ({@code Bill Payment} -&gt; {@code COBIL00C} -&gt;
     * {@code /api/billing/pay}) for end-to-end traceability. Per AAP
     * &sect;0.7.3, the COBOL target program names are preserved verbatim
     * in {@link MenuOptionDto#targetProgram()} for parallel-run
     * validation.
     */
    @Test
    @DisplayName("getMainMenu returns all expected COMEN02Y.cpy options verbatim")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void getMainMenu_returnsAllExpectedOptions() throws Exception {
        BDDMockito.given(menuService.getMainMenu(any())).willReturn(mainMenuFixture);

        mockMvc.perform(get("/api/menu/main"))
                .andExpect(status().isOk())
                // First option — COBOL COMEN02Y.cpy entry #1 (Account View / COACTVWC)
                .andExpect(jsonPath("$.data.options[0].optionNumber").value(1))
                .andExpect(jsonPath("$.data.options[0].label").value("Account View"))
                .andExpect(jsonPath("$.data.options[0].targetProgram").value("COACTVWC"))
                .andExpect(jsonPath("$.data.options[0].targetEndpoint").value("/api/accounts/{id}"))
                .andExpect(jsonPath("$.data.options[0].userType").value("U"))
                .andExpect(jsonPath("$.data.options[0].enabled").value(true))
                // Last option — COBOL COMEN02Y.cpy entry #10 (Bill Payment / COBIL00C)
                .andExpect(jsonPath("$.data.options[9].optionNumber").value(10))
                .andExpect(jsonPath("$.data.options[9].label").value("Bill Payment"))
                .andExpect(jsonPath("$.data.options[9].targetProgram").value("COBIL00C"))
                .andExpect(jsonPath("$.data.options[9].targetEndpoint").value("/api/billing/pay"));
    }

    // =====================================================================
    // Phase 2 — GET /api/menu/admin (ADMIN only)
    // COBOL: COADM01C / Tran-ID CA00 — Admin Menu for SEC-USR-TYPE='A' users
    // =====================================================================

    /**
     * Verifies that an ADMIN-role caller receives HTTP 200 from
     * {@code GET /api/menu/admin} with the expected
     * {@link AdminMenuDto} payload wrapped in the standardized
     * {@link ApiResponse} envelope.
     *
     * <p>Stubs {@link MenuService#getAdminMenu(String)} to return the
     * 4-option admin-menu fixture (matching the verbatim
     * {@code CARDDEMO-ADMIN-MENU-OPTIONS} entries from
     * {@code COADM02Y.cpy}). Asserts on the title and option list to
     * confirm the admin-only payload is delivered.
     */
    @Test
    @DisplayName("getAdminMenu returns 200 for ADMIN role")
    @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
    void getAdminMenu_returns200ForAdmin() throws Exception {
        BDDMockito.given(menuService.getAdminMenu(any())).willReturn(adminMenuFixture);

        mockMvc.perform(get("/api/menu/admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.userId").value("ADMIN001"))
                .andExpect(jsonPath("$.data.title").value("AWS CardDemo - Admin Menu"))
                .andExpect(jsonPath("$.data.options").isArray())
                .andExpect(jsonPath("$.data.options.length()").value(4))
                // First option — COBOL COADM02Y.cpy entry #1 (User List / COUSR00C)
                .andExpect(jsonPath("$.data.options[0].optionNumber").value(1))
                .andExpect(jsonPath("$.data.options[0].label").value("User List (Security)"))
                .andExpect(jsonPath("$.data.options[0].targetProgram").value("COUSR00C"))
                // Last option — COBOL COADM02Y.cpy entry #4 (User Delete / COUSR03C)
                .andExpect(jsonPath("$.data.options[3].targetProgram").value("COUSR03C"));
    }

    /**
     * Verifies that a USER-role caller (i.e., {@code SEC-USR-TYPE='U'} in
     * COBOL terms) is rejected with HTTP 403 Forbidden when attempting
     * to access {@code GET /api/menu/admin}.
     *
     * <p>The rejection is enforced by TWO defence-in-depth gates per
     * AAP &sect;0.4.1:</p>
     * <ol>
     *   <li>Method-level
     *       {@code @PreAuthorize("hasRole('ADMIN')")} on
     *       {@link MenuController#getAdminMenu(String)}.</li>
     *   <li>URL-level
     *       {@code .requestMatchers(HttpMethod.GET, "/api/menu/admin").hasRole("ADMIN")}
     *       in {@code SecurityConfig#securityFilterChain}.</li>
     * </ol>
     * <p>Either gate is sufficient. The expected HTTP 403 (NOT 401) is
     * crucial: 401 means unauthenticated, 403 means
     * authenticated-but-unauthorised &mdash; reserving 403 for the
     * authenticated-wrong-role case is REST-conventional and explicitly
     * required by QA finding CR-03.</p>
     * <p>The mocked service must never be invoked because the
     * authorization filter rejects the request before Spring MVC
     * dispatches.</p>
     */
    @Test
    @DisplayName("getAdminMenu returns 403 for USER role (not 401)")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void getAdminMenu_returns403ForUser() throws Exception {
        mockMvc.perform(get("/api/menu/admin"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(menuService);
    }

    /**
     * Verifies that an unauthenticated caller is rejected with HTTP 401
     * for {@code GET /api/menu/admin}.
     *
     * <p>The rejection is enforced by Spring Security's
     * {@code com.awsm2.carddemo.config.SecurityConfig#restAuthenticationEntryPoint()}
     * which converts anonymous access into HTTP 401 + standardized
     * envelope. The mocked service must never be invoked.</p>
     */
    @Test
    @DisplayName("getAdminMenu returns 401 for anonymous caller")
    @WithAnonymousUser
    void getAdminMenu_returns401ForAnonymous() throws Exception {
        mockMvc.perform(get("/api/menu/admin"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(menuService);
    }

    // =====================================================================
    // Phase 3 — POST /api/menu/resolve (REMOVED by CP5 scope review)
    //
    // The original AAP draft included a POST /api/menu/resolve endpoint
    // mapped to MenuService.resolveMenuTarget(option, userType, isAdminMenu).
    // The CP5 scope/security review removed it because:
    //   (a) it was not declared in AAP §0.3.4 (no scope creep per
    //       AAP §0.7.1 / §0.7.3 Minimal Change Clause); and
    //   (b) it trusted client-supplied role discriminators
    //       (userType / adminMenu in the request body) rather than
    //       deriving authority from the authenticated principal — a
    //       security defect that allowed a ROLE_USER caller to submit
    //       {"userType":"A","adminMenu":true} and receive admin-menu
    //       targets.
    //
    // The MenuService.resolveMenuTarget(String, String, boolean) method
    // remains for internal callers (it is no longer reachable via REST).
    // See MenuController class-level Javadoc, "Scope discipline" section,
    // for the full rationale.
    //
    // The Phase 3 tests below preserve the schema-mandated test method
    // names but adapt their bodies to verify the REMOVAL: every POST
    // against /api/menu/resolve from an authenticated caller surfaces
    // HTTP 404 ENDPOINT_NOT_FOUND via GlobalExceptionHandler's
    // NoResourceFoundException handler; anonymous callers surface as
    // HTTP 401 because the surrounding /api/** matcher in SecurityConfig
    // requires authentication.
    // =====================================================================

    /**
     * Verifies that the {@code POST /api/menu/resolve} endpoint is no
     * longer reachable: a USER-role caller submitting a well-formed
     * request body that the original endpoint would have accepted
     * (option={@code "01"}, userType={@code "U"}, adminMenu={@code false})
     * receives HTTP 404 because no handler is registered on the URL.
     * The mocked service is never invoked.
     */
    @Test
    @DisplayName("resolveMenu — POST /api/menu/resolve no longer exists (would have returned 200)")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void resolveMenu_returns200WithMenuOption() throws Exception {
        String body = "{\"option\":\"01\",\"userType\":\"U\",\"adminMenu\":false}";

        mockMvc.perform(post("/api/menu/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));

        verifyNoInteractions(menuService);
    }

    /**
     * Same verification as {@link #resolveMenu_returns200WithMenuOption()}
     * but with an ADMIN-role caller. The endpoint removal applies to all
     * authenticated roles; both USER and ADMIN callers see HTTP 404.
     */
    @Test
    @DisplayName("resolveMenu — POST /api/menu/resolve no longer exists for ADMIN role either")
    @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
    void resolveMenu_returns200ForAdmin() throws Exception {
        String body = "{\"option\":\"01\",\"userType\":\"A\",\"adminMenu\":true}";

        mockMvc.perform(post("/api/menu/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));

        verifyNoInteractions(menuService);
    }

    /**
     * Verifies that even a syntactically invalid input (blank
     * {@code option}) results in HTTP 404 because the endpoint was
     * removed before Jakarta Bean Validation could be applied. The
     * client-supplied {@code option}="" would, if the endpoint existed,
     * have violated a {@code @NotBlank} constraint and surfaced as
     * HTTP 400; with the endpoint gone, the URL itself does not match
     * any handler and the response is HTTP 404.
     */
    @Test
    @DisplayName("resolveMenu — POST with blank option surfaces as 404 (endpoint removed)")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void resolveMenu_returns400ForBlankOption() throws Exception {
        String body = "{\"option\":\"\",\"userType\":\"U\",\"adminMenu\":false}";

        mockMvc.perform(post("/api/menu/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));

        verifyNoInteractions(menuService);
    }

    /**
     * Verifies the same removal contract for an {@code option} value that
     * would, if the endpoint existed, have violated the
     * {@code @Pattern("^[0-9]{1,2}$")} constraint. With the endpoint
     * removed, the response is HTTP 404 regardless of input content.
     */
    @Test
    @DisplayName("resolveMenu — POST with non-numeric option surfaces as 404 (endpoint removed)")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void resolveMenu_returns400ForInvalidOptionPattern() throws Exception {
        String body = "{\"option\":\"ABC\",\"userType\":\"U\",\"adminMenu\":false}";

        mockMvc.perform(post("/api/menu/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));

        verifyNoInteractions(menuService);
    }

    /**
     * Verifies the same removal contract for an {@code option} value that
     * would have violated the maximum-length component of the
     * {@code @Pattern("^[0-9]{1,2}$")} constraint (3 digits). With the
     * endpoint removed, the response is HTTP 404.
     */
    @Test
    @DisplayName("resolveMenu — POST with too-long option surfaces as 404 (endpoint removed)")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void resolveMenu_returns400ForTooLongOption() throws Exception {
        String body = "{\"option\":\"123\",\"userType\":\"U\",\"adminMenu\":false}";

        mockMvc.perform(post("/api/menu/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));

        verifyNoInteractions(menuService);
    }

    /**
     * Verifies the same removal contract for a blank {@code userType}
     * value (would have violated {@code @NotBlank} on the original
     * request DTO). With the endpoint removed, response is HTTP 404.
     */
    @Test
    @DisplayName("resolveMenu — POST with blank userType surfaces as 404 (endpoint removed)")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void resolveMenu_returns400ForBlankUserType() throws Exception {
        String body = "{\"option\":\"01\",\"userType\":\"\",\"adminMenu\":false}";

        mockMvc.perform(post("/api/menu/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));

        verifyNoInteractions(menuService);
    }

    /**
     * Verifies the same removal contract for a {@code userType} value
     * that would have violated {@code @Pattern("^[AU]$")} (only
     * 'A' or 'U' permitted on the original endpoint, matching COBOL
     * {@code SEC-USR-TYPE PIC X(01)} from {@code app/cpy/CSUSR01Y.cpy}).
     * With the endpoint removed, response is HTTP 404.
     */
    @Test
    @DisplayName("resolveMenu — POST with invalid userType ('X') surfaces as 404 (endpoint removed)")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void resolveMenu_returns400ForInvalidUserType() throws Exception {
        String body = "{\"option\":\"01\",\"userType\":\"X\",\"adminMenu\":false}";

        mockMvc.perform(post("/api/menu/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));

        verifyNoInteractions(menuService);
    }

    /**
     * Verifies the same removal contract for both legitimate user-type
     * discriminators ({@code "A"} and {@code "U"}). Both surface as
     * HTTP 404 because the endpoint was removed. This test demonstrates
     * that the removal is symmetric and does not depend on the
     * caller's role or the client-supplied {@code userType}.
     */
    @Test
    @DisplayName("resolveMenu — POST surfaces as 404 for both userType='A' and userType='U'")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void resolveMenu_acceptsBothAdminAndUserTypes() throws Exception {
        String userBody = "{\"option\":\"01\",\"userType\":\"U\",\"adminMenu\":false}";
        String adminBody = "{\"option\":\"01\",\"userType\":\"A\",\"adminMenu\":true}";

        mockMvc.perform(post("/api/menu/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userBody)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));

        mockMvc.perform(post("/api/menu/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adminBody)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));

        verifyNoInteractions(menuService);
    }

    /**
     * Verifies the same removal contract for a malformed JSON body. The
     * original endpoint would have surfaced
     * {@code HttpMessageNotReadableException} as HTTP 400 with code
     * {@code "MALFORMED_REQUEST"}; with the endpoint removed, the URL
     * itself does not match any handler and the response is HTTP 404.
     * Spring's request dispatch resolves the missing handler BEFORE
     * attempting message conversion, so the
     * {@code HttpMessageNotReadableException} path is never reached.
     */
    @Test
    @DisplayName("resolveMenu — POST with malformed JSON surfaces as 404 (endpoint removed)")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void resolveMenu_returns400ForMalformedJson() throws Exception {
        mockMvc.perform(post("/api/menu/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ broken")
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));

        verifyNoInteractions(menuService);
    }

    /**
     * Verifies that an anonymous caller submitting POST
     * {@code /api/menu/resolve} is rejected with HTTP 401 BEFORE the
     * "endpoint not found" check fires. The
     * {@code .requestMatchers("/api/**").hasAnyRole("USER","ADMIN")}
     * URL matcher in
     * {@link com.awsm2.carddemo.config.SecurityConfig#securityFilterChain}
     * runs before Spring MVC's dispatcher: anonymous callers cannot
     * even reach the "no handler" branch, so they see 401 rather than
     * 404.
     */
    @Test
    @DisplayName("resolveMenu — POST returns 401 for anonymous caller (auth required)")
    @WithAnonymousUser
    void resolveMenu_returns401ForAnonymous() throws Exception {
        String body = "{\"option\":\"01\",\"userType\":\"U\",\"adminMenu\":false}";

        mockMvc.perform(post("/api/menu/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(menuService);
    }

    /**
     * Verifies that the
     * {@link MenuService#resolveMenuTarget(String, String, boolean)}
     * service method is never invoked by the controller for any
     * {@code POST /api/menu/resolve} call &mdash; the original
     * "service-thrown validation" code path is unreachable because the
     * endpoint was removed. The test additionally configures the
     * mocked service to throw {@link ValidationException} via
     * {@link BDDMockito#willThrow} if ever invoked; that branch never
     * fires (the test asserts HTTP 404 ENDPOINT_NOT_FOUND from
     * {@link GlobalExceptionHandler#handleNoResourceFound}) and the
     * service is verified to have received zero interactions.
     */
    @Test
    @DisplayName("resolveMenu — service.resolveMenuTarget is never invoked (endpoint removed)")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void resolveMenu_returns400WhenServiceThrowsValidation() throws Exception {
        // Stub the service to throw — this is a contract assertion: even
        // if the service WERE invoked, the test confirms the controller
        // does not bridge that exception. Because the endpoint is gone,
        // the stub is never exercised.
        BDDMockito.willThrow(new ValidationException("Invalid menu option"))
                .given(menuService)
                .resolveMenuTarget(any(), any(), eq(false));

        String body = "{\"option\":\"99\",\"userType\":\"U\",\"adminMenu\":false}";

        mockMvc.perform(post("/api/menu/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));

        verifyNoInteractions(menuService);
    }

    // =====================================================================
    // Phase 4 — Method-level @PreAuthorize enforcement (defence-in-depth)
    // Confirms the per-endpoint authorization rules end-to-end:
    //   * GET /api/menu/main  — hasAnyRole('USER','ADMIN') (both pass)
    //   * GET /api/menu/admin — hasRole('ADMIN') (USER → 403; ADMIN → 200)
    //   * POST /api/menu/resolve — removed; authenticated → 404; anonymous → 401
    // =====================================================================

    /**
     * Omnibus assertion that the method-level {@code @PreAuthorize}
     * gates on {@link MenuController} are wired through
     * {@code @EnableMethodSecurity(prePostEnabled = true)} on
     * {@link com.awsm2.carddemo.config.SecurityConfig}.
     *
     * <p>Confirms the role-based access matrix end-to-end:</p>
     * <ul>
     *   <li>{@code GET /api/menu/main} is accessible to both USER and
     *       ADMIN roles per
     *       {@code @PreAuthorize("hasAnyRole('USER','ADMIN')")}.</li>
     *   <li>{@code GET /api/menu/admin} is accessible to ADMIN only per
     *       {@code @PreAuthorize("hasRole('ADMIN')")}; USER role
     *       receives HTTP 403.</li>
     *   <li>{@code POST /api/menu/resolve} is uniformly absent: any
     *       authenticated caller receives HTTP 404
     *       ENDPOINT_NOT_FOUND.</li>
     * </ul>
     */
    @Test
    @DisplayName("method-level @PreAuthorize is enforced for all three logical endpoints")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void methodLevelPreAuthorizeEnforced() throws Exception {
        BDDMockito.given(menuService.getMainMenu(any())).willReturn(mainMenuFixture);

        // GET /api/menu/main accepts USER (hasAnyRole('USER','ADMIN'))
        mockMvc.perform(get("/api/menu/main"))
                .andExpect(status().isOk());

        // GET /api/menu/admin rejects USER (hasRole('ADMIN')) → 403
        mockMvc.perform(get("/api/menu/admin"))
                .andExpect(status().isForbidden());

        // POST /api/menu/resolve removed → 404 (authenticated reaches MVC)
        mockMvc.perform(post("/api/menu/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"option\":\"01\",\"userType\":\"U\",\"adminMenu\":false}")
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    // =====================================================================
    // Phase 5 — Standardized ApiResponse envelope shape
    // (AAP §0.3.4 — every endpoint wraps payloads/errors in ApiResponse<T>)
    // =====================================================================

    /**
     * Verifies that {@code GET /api/menu/main} returns the standardized
     * {@link ApiResponse} envelope shape per AAP &sect;0.3.4:
     * {@code $.code = "OK"}, {@code $.timestamp} exists,
     * {@code $.data} exists. The {@code fieldErrors} component is
     * absent on success because {@code @JsonInclude(NON_NULL)} on the
     * record suppresses null components.
     */
    @Test
    @DisplayName("getMainMenu returns standardized ApiResponse envelope")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void getMainMenu_returnsApiResponseEnvelope() throws Exception {
        BDDMockito.given(menuService.getMainMenu(any())).willReturn(mainMenuFixture);

        mockMvc.perform(get("/api/menu/main"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("Success"))
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /**
     * Verifies that {@code GET /api/menu/admin} returns the same
     * standardized envelope shape as {@code GET /api/menu/main}. Both
     * controller methods wrap their payloads in
     * {@link ApiResponse#success(Object)} so the envelope contract is
     * uniform across the controller surface.
     */
    @Test
    @DisplayName("getAdminMenu returns standardized ApiResponse envelope")
    @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
    void getAdminMenu_returnsApiResponseEnvelope() throws Exception {
        BDDMockito.given(menuService.getAdminMenu(any())).willReturn(adminMenuFixture);

        mockMvc.perform(get("/api/menu/admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("Success"))
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /**
     * Verifies that {@code POST /api/menu/resolve} surfaces the
     * standardized error envelope (HTTP 404 with
     * {@code $.code = "ENDPOINT_NOT_FOUND"}, {@code $.message},
     * {@code $.timestamp}) via
     * {@link GlobalExceptionHandler#handleNoResourceFound}. The envelope
     * shape on the removed endpoint matches the envelope shape that the
     * surviving endpoints emit on success, so client code can rely on a
     * uniform JSON contract for both success and error responses
     * (AAP &sect;0.3.4 standardized envelope).
     */
    @Test
    @DisplayName("resolveMenu (removed) returns standardized ApiResponse error envelope")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void resolveMenu_returnsApiResponseEnvelope() throws Exception {
        mockMvc.perform(post("/api/menu/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"option\":\"01\",\"userType\":\"U\",\"adminMenu\":false}")
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
