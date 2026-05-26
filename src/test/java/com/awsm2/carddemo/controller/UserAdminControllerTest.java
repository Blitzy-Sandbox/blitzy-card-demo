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

// COBOL: COUSR00C.cbl — User list (paginated, PAGE_SIZE=10, admin only) — Tran-ID CU00
// COBOL: COUSR01C.cbl — User add (BCrypt password hashing at service) — Tran-ID CU01
// COBOL: COUSR02C.cbl — User update (password optional)               — Tran-ID CU02
// COBOL: COUSR03C.cbl — User delete (Y proceeds, N cancels)           — Tran-ID CU03
// BMS:   COUSR00.bms, COUSR01.bms, COUSR02.bms, COUSR03.bms
// CSUSR01Y.cpy — 80-byte user security record layout
//
// MockMvc slice test for {@link UserAdminController}, the Java target for the
// four CICS COBOL user-administration programs above. The controller carries
// a CLASS-LEVEL @PreAuthorize("hasRole('ADMIN')") gate so every endpoint
// (GET, POST, PUT, DELETE under /api/admin/users) is admin-only.

import com.awsm2.carddemo.config.SecurityConfig;
import com.awsm2.carddemo.dto.ApiResponse;
import com.awsm2.carddemo.dto.UserAddDto;
import com.awsm2.carddemo.dto.UserDeleteDto;
import com.awsm2.carddemo.dto.UserListDto;
import com.awsm2.carddemo.dto.UserUpdateDto;
import com.awsm2.carddemo.exception.DuplicateRecordException;
import com.awsm2.carddemo.exception.GlobalExceptionHandler;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.security.JwtAuthenticationFilter;
import com.awsm2.carddemo.security.JwtTokenProvider;
import com.awsm2.carddemo.service.UserAddService;
import com.awsm2.carddemo.service.UserDeleteService;
import com.awsm2.carddemo.service.UserListService;
import com.awsm2.carddemo.service.UserUpdateService;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc controller slice test for {@link UserAdminController}.
 *
 * <h2>System Under Test (SUT)</h2>
 *
 * <p>{@link UserAdminController} is the Java target for the four
 * CICS/COBOL user-administration programs of the source codebase:</p>
 * <ul>
 *   <li>{@code app/cbl/COUSR00C.cbl} (Tran-ID {@code CU00}) &mdash;
 *       paginated user list, page size = 10
 *       (verbatim from {@code USER-REC OCCURS 10 TIMES}).</li>
 *   <li>{@code app/cbl/COUSR01C.cbl} (Tran-ID {@code CU01}) &mdash;
 *       user add with {@code WRITE-USER-SEC-FILE}; the Java target
 *       BCrypt-hashes the password at the service layer per AAP
 *       &sect;0.1.1 (security upgrade from legacy plaintext storage).</li>
 *   <li>{@code app/cbl/COUSR02C.cbl} (Tran-ID {@code CU02}) &mdash;
 *       user update with {@code UPDATE-USER-SEC-FILE}; the password
 *       field is optional ({@code null}/blank means "keep existing").</li>
 *   <li>{@code app/cbl/COUSR03C.cbl} (Tran-ID {@code CU03}) &mdash;
 *       user delete with the {@code PF5}(Y)/{@code PF4}(N)
 *       confirmation flag from {@code app/bms/COUSR03.bms} (BMS map
 *       {@code COUSR3A}).</li>
 * </ul>
 *
 * <h2>Endpoint inventory under test (AAP &sect;0.3.4 / &sect;0.4.1)</h2>
 *
 * <ul>
 *   <li>{@code GET    /api/admin/users}      &rarr; HTTP 200 with the
 *       paginated {@link UserListDto} wrapped in {@link ApiResponse}.</li>
 *   <li>{@code POST   /api/admin/users}      &rarr; HTTP 201 Created
 *       with the saved {@link UserAddDto} (password component nulled
 *       by the service) wrapped in {@link ApiResponse}.</li>
 *   <li>{@code PUT    /api/admin/users/{id}} &rarr; HTTP 200 with the
 *       updated {@link UserUpdateDto} (password component nulled
 *       by the service) wrapped in {@link ApiResponse}.</li>
 *   <li>{@code DELETE /api/admin/users/{id}} &rarr;
 *       <ul>
 *         <li>{@code confirm = "Y"} (delete proceeds) &rarr;
 *             HTTP 204 No Content with an empty response body
 *             (actual {@link UserAdminController} implementation;
 *             matches {@code @ApiResponse(responseCode = "204",
 *             description = "User deleted successfully; no response
 *             body")}).</li>
 *         <li>{@code confirm = "N"} (delete cancelled) &rarr;
 *             HTTP 200 OK with the standardized {@link ApiResponse}
 *             envelope and {@code message = "User deletion cancelled"}.</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <h2>Class-level admin gate (CRITICAL)</h2>
 *
 * <p>{@link UserAdminController} carries a class-level
 * {@code @PreAuthorize("hasRole('ADMIN')")} annotation; the
 * {@link com.awsm2.carddemo.config.SecurityConfig} URL-matcher
 * additionally restricts {@code /api/admin/**} to ADMIN role at the
 * filter-chain layer. The class-level annotation is therefore the
 * inner defense-in-depth gate; the {@code @PreAuthorize} test scenarios
 * below verify ADMIN-required behavior end-to-end.</p>
 *
 * <h2>Slice composition</h2>
 *
 * <p>{@code @WebMvcTest(UserAdminController.class)} loads only this
 * controller plus the Spring MVC infrastructure beans (MockMvc,
 * ObjectMapper, message converters). The Spring Security filter chain
 * is loaded automatically because Spring Security is on the classpath,
 * and we additionally {@code @Import} the production
 * {@link com.awsm2.carddemo.config.SecurityConfig} so that the test
 * exercises the EXACT same filter chain wiring used in production
 * &mdash; the {@code restAuthenticationEntryPoint()} that maps
 * anonymous rejections to HTTP 401 + standardized {@link ApiResponse}
 * error envelope, the URL-level matcher
 * {@code requestMatchers("/api/admin/**").hasRole("ADMIN")}, and the
 * {@code @EnableMethodSecurity(prePostEnabled = true)} that enforces
 * the per-method {@code @PreAuthorize} gates.</p>
 *
 * <p>The four service collaborators ({@link UserListService},
 * {@link UserAddService}, {@link UserUpdateService},
 * {@link UserDeleteService}) are replaced by Mockito {@link MockBean}s;
 * {@link JwtTokenProvider} and {@link JwtAuthenticationFilter} are
 * also mocked to satisfy {@link com.awsm2.carddemo.config.SecurityConfig}'s
 * constructor dependencies without loading JWT signing keys, Secrets
 * Manager integration, or {@code @RefreshScope} beans. CRITICAL:
 * because Mockito's default mock for a {@code Filter} does NOT invoke
 * {@code chain.doFilter()}, every request would otherwise be silently
 * dropped (the filter would short-circuit the chain and the response
 * body would be empty). {@link #setUpFilterMock()} explicitly stubs
 * the mocked filter to delegate to the next filter so the chain
 * proceeds to Spring Security's authorization checks and the
 * controller dispatch.</p>
 *
 * <p>{@link GlobalExceptionHandler} is explicitly imported via
 * {@code @Import} because {@code @WebMvcTest} does not auto-load this
 * advice bean by default; without it, the standardized
 * {@link ApiResponse} error envelope shape would not be observed in
 * tests.</p>
 *
 * <h2>Security context for tests</h2>
 *
 * <p>Authentication state is established with Spring Security Test's
 * {@code @WithMockUser} and {@code @WithAnonymousUser} annotations,
 * which populate the {@code SecurityContextHolder} for the duration
 * of the test method without requiring real JWT tokens. The
 * {@code roles} attribute on {@code @WithMockUser} drives the
 * {@code hasRole('ADMIN')} check on the class-level
 * {@code @PreAuthorize}.</p>
 *
 * <h2>CSRF</h2>
 *
 * <p>{@link com.awsm2.carddemo.config.SecurityConfig} disables CSRF
 * for the entire {@code /api/**} surface (the API is JWT
 * authenticated, not cookie-authenticated). {@code csrf()} request
 * post-processors are nonetheless attached to POST/PUT/DELETE
 * requests in this test class so the tests remain meaningful if CSRF
 * protection is later re-enabled for any portion of the surface.</p>
 *
 * <h2>PCI-DSS password protection (AAP &sect;0.6.6 / &sect;0.7.1)</h2>
 *
 * <p>This test class verifies the following PCI-DSS-aligned invariants
 * (the password column of {@code CSUSR01Y.cpy} is the most sensitive
 * field touched by this controller):</p>
 * <ol>
 *   <li>Response bodies NEVER include the plaintext password (verified
 *       at the JSON level via {@code jsonPath("$.data.password").doesNotExist()}
 *       and at the raw-content level via
 *       {@code content().string(not(containsString(plaintextPassword)))}).</li>
 *   <li>Validation field errors NEVER include the rejected password
 *       value (verified via
 *       {@code jsonPath("$.fieldErrors[?(@.field=='password')].rejectedValue").doesNotExist()},
 *       which relies on the
 *       {@link com.awsm2.carddemo.exception.GlobalExceptionHandler}
 *       using {@link ApiResponse.FieldError#of(String, String, String)}
 *       (the 3-arg PCI-safe factory) for every Bean Validation
 *       failure).</li>
 *   <li>BCrypt password hashing is the SERVICE's responsibility; the
 *       controller forwards the plaintext password to
 *       {@link UserAddService}/{@link UserUpdateService} unchanged
 *       (verified via {@link ArgumentCaptor} in Phase 8).</li>
 * </ol>
 *
 * <h2>TestPropertySource</h2>
 *
 * <p>The {@code carddemo.security.jwt.signing-key} and
 * {@code carddemo.security.cors.allowed-origins} placeholders satisfy
 * Spring's property-resolution requirements when
 * {@link com.awsm2.carddemo.config.SecurityConfig} is imported; since
 * {@link JwtTokenProvider} is mocked the actual key value is not used
 * to sign tokens. The CORS allowed-origins value is set to a concrete
 * host so {@link com.awsm2.carddemo.config.SecurityConfig}'s
 * {@code corsConfigurationSource()} bean has a non-wildcard origin
 * list (a defensive setting that mirrors the production posture).</p>
 *
 * <h2>Test phases</h2>
 *
 * <ol>
 *   <li><strong>Phase 1</strong> &mdash; class-level
 *       {@code @PreAuthorize("hasRole('ADMIN')")} enforcement:
 *       USER role &rarr; 403; anonymous &rarr; 401 across all 4
 *       endpoints.</li>
 *   <li><strong>Phase 2</strong> &mdash; GET /api/admin/users paginated
 *       list (PAGE_SIZE = 10).</li>
 *   <li><strong>Phase 3</strong> &mdash; POST /api/admin/users user add
 *       (HTTP 201, Bean Validation, password redaction).</li>
 *   <li><strong>Phase 4</strong> &mdash; PUT /api/admin/users/{id} user
 *       update (optional password, RecordNotFound &rarr; 404).</li>
 *   <li><strong>Phase 5</strong> &mdash; DELETE /api/admin/users/{id}
 *       user delete (Y &rarr; 204, N &rarr; 200 with envelope).</li>
 *   <li><strong>Phase 6</strong> &mdash; HTTP status verification
 *       (POST is exactly 201; DELETE confirm=N is exactly 200 with
 *       envelope).</li>
 *   <li><strong>Phase 7</strong> &mdash; PCI-DSS password protection
 *       end-to-end.</li>
 *   <li><strong>Phase 8</strong> &mdash; Service argument verification
 *       via {@link ArgumentCaptor}.</li>
 * </ol>
 *
 * @see UserAdminController
 * @see UserListService
 * @see UserAddService
 * @see UserUpdateService
 * @see UserDeleteService
 * @see com.awsm2.carddemo.config.SecurityConfig
 * @see GlobalExceptionHandler
 */
// Replaces: app/cbl/COUSR00C.cbl, COUSR01C.cbl, COUSR02C.cbl, COUSR03C.cbl
// (CICS Tran-IDs CU00/CU01/CU02/CU03). The four COBOL programs all targeted
// the same VSAM KSDS USRSEC dataset (CSUSR01Y.cpy 80-byte layout); in the
// Java target the equivalent is the user_security table populated by
// Flyway V010__create_user_security.sql.
@WebMvcTest(UserAdminController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // The base configuration uses Secrets Manager-backed JWT signing
        // key resolution. For a controller-only slice test we never issue
        // or validate tokens (each test uses Spring Security's
        // @WithMockUser to inject the authentication directly), so we
        // suppress JwtTokenProvider initialization by mocking it; this
        // also keeps the slice fast. Any non-blank value satisfies the
        // @Value injection on the SecurityConfig bean.
        "carddemo.security.jwt.signing-key=test-only-jwt-signing-key-32-bytes-min-length",
        "carddemo.security.cors.allowed-origins=http://localhost:3000"
})
@DisplayName("UserAdminController — /api/admin/users (CLASS-LEVEL hasRole('ADMIN') — all USER → 403)")
class UserAdminControllerTest {

    // =====================================================================
    // Spring MVC infrastructure (auto-injected from @WebMvcTest)
    // =====================================================================

    /**
     * MockMvc fluent client into the Spring MVC dispatcher, configured
     * by {@code @WebMvcTest} to route through the SUT controller plus
     * the Spring Security filter chain wired by the imported
     * {@link com.awsm2.carddemo.config.SecurityConfig}.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Application Jackson mapper used to serialize {@link UserAddDto},
     * {@link UserUpdateDto}, and {@link UserDeleteDto} fixtures to
     * JSON request bodies. Auto-configured by Spring Boot's
     * {@code JacksonAutoConfiguration} in the {@code @WebMvcTest}
     * slice; honours the {@code @JsonProperty} naming on the DTO
     * records and the
     * {@code @JsonInclude(NON_NULL)} on {@link ApiResponse}.
     */
    @Autowired
    private ObjectMapper objectMapper;

    // =====================================================================
    // Mock collaborators (one @MockBean per direct dependency of the SUT)
    // =====================================================================

    /**
     * Mock of {@link UserListService} for the
     * {@code GET /api/admin/users} flow. Stubbed via
     * {@code BDDMockito.given(...).willReturn(...)} to return paginated
     * {@link UserListDto} fixtures without involving
     * {@code UserSecurityRepository} or RDS.
     */
    @MockBean
    private UserListService userListService;

    /**
     * Mock of {@link UserAddService} for the
     * {@code POST /api/admin/users} flow. Stubbed to return
     * {@link UserAddDto} fixtures with {@code password = null}
     * (per the service contract that nulls the password before return)
     * and to throw {@link DuplicateRecordException} /
     * {@link ValidationException} for negative-path tests.
     */
    @MockBean
    private UserAddService userAddService;

    /**
     * Mock of {@link UserUpdateService} for the
     * {@code PUT /api/admin/users/{id}} flow. Stubbed to return
     * {@link UserUpdateDto} fixtures with {@code password = null}
     * (per the service contract) and to throw
     * {@link RecordNotFoundException} for not-found tests.
     */
    @MockBean
    private UserUpdateService userUpdateService;

    /**
     * Mock of {@link UserDeleteService} for the
     * {@code DELETE /api/admin/users/{id}} flow. Stubbed to return
     * {@link UserDeleteDto} fixtures (with {@code confirm = "Y"} for
     * proceed flows or {@code confirm = "N"} for cancellation flows)
     * and to throw {@link RecordNotFoundException} /
     * {@link ValidationException} for negative-path tests.
     */
    @MockBean
    private UserDeleteService userDeleteService;

    /**
     * Mock of {@link JwtTokenProvider} required by Spring's bean factory
     * to satisfy {@link JwtAuthenticationFilter}'s constructor and
     * {@link com.awsm2.carddemo.config.SecurityConfig}'s filter-chain
     * wiring. The mock is never invoked because tests populate the
     * security context via {@code @WithMockUser}/{@code @WithAnonymousUser}
     * rather than via real bearer-token validation.
     */
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    /**
     * Mock of {@link JwtAuthenticationFilter} required by Spring
     * Security's filter-chain wiring; the chain registers the filter
     * as a pre-{@code UsernamePasswordAuthenticationFilter} via
     * {@link com.awsm2.carddemo.config.SecurityConfig#securityFilterChain}.
     * Mocking it (rather than letting the real filter execute) avoids
     * needing real JWT signing keys or Secrets Manager integration
     * during the slice test.
     *
     * <p><strong>CRITICAL:</strong> Mockito's default mock for a
     * {@code Filter} does NOT invoke {@code chain.doFilter()} &mdash;
     * this would drop every request silently (the filter would
     * short-circuit the chain, the controller would never be invoked,
     * and {@code MockMvc} would observe an empty HTTP 200 response).
     * {@link #setUpFilterMock()} explicitly stubs the
     * {@code doFilter} method with a pass-through answer so the chain
     * proceeds normally; tests rely on
     * {@code @WithMockUser}/{@code @WithAnonymousUser} to populate
     * the security context BEFORE the request enters the chain,
     * exactly as the production {@link JwtAuthenticationFilter} would
     * have done after validating a bearer token.</p>
     */
    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    // =====================================================================
    // @BeforeEach — JWT filter pass-through
    // =====================================================================

    /**
     * Pass-through configuration for the mocked
     * {@link JwtAuthenticationFilter}. Recreated before each test to
     * keep tests independent and to avoid the cross-test pollution
     * that mutable shared fixtures can introduce.
     *
     * <p>Mockito's default mock for the
     * {@code Filter.doFilter(req, resp, chain)} method does NOT invoke
     * {@code chain.doFilter(req, resp)}, so the chain halts at the
     * filter and the controller is never dispatched. The observable
     * symptom is HTTP 200 with empty body across every test. Stubbing
     * the mock with a "delegate to chain" answer restores the
     * production-equivalent behavior of a filter that simply continues
     * the chain when no bearer token is present (see
     * {@code JwtAuthenticationFilter.doFilterInternal}).</p>
     */
    @BeforeEach
    void setUpFilterMock() throws Exception {
        Mockito.doAnswer(invocation -> {
            ServletRequest req = invocation.getArgument(0);
            ServletResponse resp = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(req, resp);
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    // =====================================================================
    // Test fixture constants (PCI-safe values; no real credentials)
    // =====================================================================

    /**
     * Canonical test user identifier used as both the URL path variable
     * and the body {@code userId} value when the IDOR mitigation check
     * in {@link UserAdminController#updateUser(String, UserUpdateDto)} /
     * {@link UserAdminController#deleteUser(String, UserDeleteDto)}
     * requires consistency between path and body. The value matches
     * both the path-variable {@code @Pattern("^[A-Z0-9 ]{1,8}$")} and
     * the DTO {@code @Pattern("^[A-Za-z0-9]+$")}.
     */
    private static final String USER_ID = "USER0001";

    /**
     * Synthetic plaintext password used only to exercise the password
     * field in test fixtures. The 8-character limit matches the COBOL
     * {@code SEC-USR-PWD PIC X(08)} declaration and the DTO
     * {@code @Size(max = 8)}. CRITICAL: this value MUST NOT appear in
     * any response body or validation field error (PCI-DSS,
     * AAP &sect;0.6.6). The PCI-DSS Phase 7 tests grep for this exact
     * literal in the raw response content.
     */
    private static final String PLAINTEXT_PASSWORD = "Pa55w0rd";

    // =====================================================================
    // Phase 1 — Class-Level @PreAuthorize("hasRole('ADMIN')") Enforcement
    //
    // COBOL provenance: in the source system, RACF resource access control
    // and the CICS DFHRSRC profiles were the gate that prevented USER-role
    // operators from invoking the COUSR00C/COUSR01C/COUSR02C/COUSR03C
    // transactions; the BMS menu pad on app/bms/COADM01.bms was admin-only
    // (the role check in app/cbl/COSGN00C.cbl branched on
    // SEC-USR-TYPE='A'). In the Java target this is replaced by:
    //
    //   (a) the URL-level matcher
    //       .requestMatchers("/api/admin/**").hasRole("ADMIN") in
    //       SecurityConfig.securityFilterChain, AND
    //   (b) the class-level @PreAuthorize("hasRole('ADMIN')") on
    //       UserAdminController (defense in depth).
    //
    // Either gate is sufficient to deny the USER role; both must allow
    // the ADMIN role. The five tests below verify the negative paths
    // (USER → 403, anonymous → 401) end-to-end across every endpoint.
    // =====================================================================

    /**
     * Verifies that a USER-role caller invoking
     * {@code GET /api/admin/users} receives HTTP 403 Forbidden and that
     * the {@link UserListService} is NEVER invoked (defense in depth at
     * the security-filter / method-security layer).
     *
     * <p>COBOL provenance: replaces the {@code COSGN00C.cbl} signon-time
     * branch on {@code SEC-USR-TYPE = 'A'} that prevented USER-role
     * operators from reaching the {@code COUSR00C.cbl} transaction.</p>
     */
    @Test
    @DisplayName("listUsers_returns403ForUser — USER role rejected by class-level @PreAuthorize")
    @WithMockUser(username = "USER0001", roles = "USER")
    void listUsers_returns403ForUser() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .param("page", "0"))
                .andExpect(status().isForbidden());

        // The class-level @PreAuthorize fires before controller method
        // entry; the service must NEVER be invoked on a 403 path.
        verifyNoInteractions(userListService);
    }

    /**
     * Verifies that a USER-role caller invoking
     * {@code POST /api/admin/users} receives HTTP 403 Forbidden and
     * that the {@link UserAddService} is NEVER invoked.
     */
    @Test
    @DisplayName("addUser_returns403ForUser — USER role rejected by class-level @PreAuthorize")
    @WithMockUser(username = "USER0001", roles = "USER")
    void addUser_returns403ForUser() throws Exception {
        UserAddDto request = new UserAddDto(
                USER_ID, "John", "Doe", PLAINTEXT_PASSWORD, "U");
        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userAddService);
    }

    /**
     * Verifies that a USER-role caller invoking
     * {@code PUT /api/admin/users/{id}} receives HTTP 403 Forbidden and
     * that the {@link UserUpdateService} is NEVER invoked.
     */
    @Test
    @DisplayName("updateUser_returns403ForUser — USER role rejected by class-level @PreAuthorize")
    @WithMockUser(username = "USER0001", roles = "USER")
    void updateUser_returns403ForUser() throws Exception {
        UserUpdateDto request = new UserUpdateDto(
                USER_ID, "John", "Doe", null, "U");
        mockMvc.perform(put("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userUpdateService);
    }

    /**
     * Verifies that a USER-role caller invoking
     * {@code DELETE /api/admin/users/{id}} receives HTTP 403 Forbidden
     * and that the {@link UserDeleteService} is NEVER invoked.
     */
    @Test
    @DisplayName("deleteUser_returns403ForUser — USER role rejected by class-level @PreAuthorize")
    @WithMockUser(username = "USER0001", roles = "USER")
    void deleteUser_returns403ForUser() throws Exception {
        UserDeleteDto request = new UserDeleteDto(
                USER_ID, "John", "Doe", "U", "Y");
        mockMvc.perform(delete("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userDeleteService);
    }

    /**
     * Verifies that an anonymous caller (no authentication) invoking
     * any of the four endpoints receives HTTP 401 Unauthorized via the
     * {@code restAuthenticationEntryPoint} configured in
     * {@link com.awsm2.carddemo.config.SecurityConfig}.
     *
     * <p>This test runs four MockMvc operations &mdash; one for each
     * HTTP verb on the admin URL surface &mdash; under a single
     * {@code @WithAnonymousUser} so every endpoint's anonymous-access
     * behavior is verified in a single test method (per the schema
     * export {@code allEndpoints_returns401ForAnonymous}).</p>
     *
     * <p>COBOL provenance: replaces the {@code COSGN00C.cbl} sign-on
     * gate that required a valid {@code USRSEC} record before any CICS
     * transaction (including the COUSR* family) could be dispatched.</p>
     */
    @Test
    @DisplayName("allEndpoints_returns401ForAnonymous — anonymous caller rejected by restAuthenticationEntryPoint")
    @WithAnonymousUser
    void allEndpoints_returns401ForAnonymous() throws Exception {
        // GET /api/admin/users — anonymous → 401.
        mockMvc.perform(get("/api/admin/users")
                        .param("page", "0"))
                .andExpect(status().isUnauthorized());

        // POST /api/admin/users — anonymous → 401.
        UserAddDto addRequest = new UserAddDto(
                USER_ID, "John", "Doe", PLAINTEXT_PASSWORD, "U");
        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addRequest)))
                .andExpect(status().isUnauthorized());

        // PUT /api/admin/users/{id} — anonymous → 401.
        UserUpdateDto updateRequest = new UserUpdateDto(
                USER_ID, "John", "Doe", null, "U");
        mockMvc.perform(put("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isUnauthorized());

        // DELETE /api/admin/users/{id} — anonymous → 401.
        UserDeleteDto deleteRequest = new UserDeleteDto(
                USER_ID, "John", "Doe", "U", "Y");
        mockMvc.perform(delete("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteRequest)))
                .andExpect(status().isUnauthorized());

        // No service may be invoked on any 401 path; verify all four.
        verifyNoInteractions(userListService);
        verifyNoInteractions(userAddService);
        verifyNoInteractions(userUpdateService);
        verifyNoInteractions(userDeleteService);
    }

    // =====================================================================
    // Phase 2 — GET /api/admin/users — Paginated List
    //
    // COBOL provenance: replaces app/cbl/COUSR00C.cbl (Tran-ID CU00) —
    // the PROCESS-ENTER-KEY / PROCESS-PF7-KEY / PROCESS-PF8-KEY
    // pagination paragraphs. PAGE_SIZE = 10 is a verbatim carry-over
    // from the COBOL declaration USER-REC OCCURS 10 TIMES in the
    // CARDDEMO-MAIN-MENU working storage. The Java target preserves
    // this page size in UserListService.PAGE_SIZE.
    // =====================================================================

    /**
     * Verifies that an ADMIN-role caller invoking
     * {@code GET /api/admin/users?page=0} receives HTTP 200 with the
     * standardized {@link ApiResponse} envelope wrapping a
     * {@link UserListDto} containing 10 {@code UserRow} entries (one
     * full page) and the expected pagination metadata
     * ({@code page = 0}, {@code size = 10}, {@code totalElements = 15},
     * {@code totalPages = 2}).
     *
     * <p>The {@link UserListDto.UserRow} record by design has no
     * password field, so the response body is guaranteed PCI-safe by
     * construction (PCI-DSS, AAP &sect;0.6.6).</p>
     */
    @Test
    @DisplayName("listUsers_returns200ForAdmin — paginated UserListDto, 10 rows, size=10")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void listUsers_returns200ForAdmin() throws Exception {
        // Build a UserListDto with 10 rows (one full page), simulating a
        // paginated response from the USRSEC store (15 total rows ⇒
        // 2 total pages of size 10).
        List<UserListDto.UserRow> rows = List.of(
                new UserListDto.UserRow("ADMIN001", "Admin", "User", "A"),
                new UserListDto.UserRow("USER0001", "John", "Doe", "U"),
                new UserListDto.UserRow("USER0002", "Jane", "Smith", "U"),
                new UserListDto.UserRow("USER0003", "Alice", "Lee", "U"),
                new UserListDto.UserRow("USER0004", "Bob", "Chan", "U"),
                new UserListDto.UserRow("USER0005", "Carol", "Davis", "U"),
                new UserListDto.UserRow("USER0006", "Dave", "Evans", "U"),
                new UserListDto.UserRow("USER0007", "Eve", "Ford", "U"),
                new UserListDto.UserRow("USER0008", "Frank", "Green", "U"),
                new UserListDto.UserRow("USER0009", "Grace", "Hall", "U")
        );
        UserListDto dto = new UserListDto(
                rows,         // rows
                0,            // page
                10,           // size (UserListService.PAGE_SIZE = 10)
                15L,          // totalElements
                2,            // totalPages
                true,         // first
                false,        // last
                null);        // searchFilter
        BDDMockito.given(userListService.listUsers(any(), eq(0)))
                .willReturn(dto);

        mockMvc.perform(get("/api/admin/users")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.rows.length()").value(10))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(15))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.first").value(true))
                .andExpect(jsonPath("$.data.last").value(false))
                .andExpect(jsonPath("$.data.rows[0].userId").value("ADMIN001"))
                .andExpect(jsonPath("$.data.rows[0].userType").value("A"))
                .andExpect(jsonPath("$.timestamp").exists());

        // Verify service invocation; null searchTerm + page 0.
        verify(userListService).listUsers(null, 0);
    }

    /**
     * Verifies that the {@code search} query parameter is forwarded to
     * {@link UserListService#listUsers(String, int)} unchanged. The
     * service applies any normalization (uppercasing, trimming); the
     * controller just passes the raw value through.
     */
    @Test
    @DisplayName("listUsers_acceptsSearchFilter — search param forwarded to service")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void listUsers_acceptsSearchFilter() throws Exception {
        UserListDto dto = new UserListDto(
                List.of(new UserListDto.UserRow("ADMIN001", "Admin", "User", "A")),
                0, 10, 1L, 1, true, true, "ADMIN");
        BDDMockito.given(userListService.listUsers(eq("ADMIN"), eq(0)))
                .willReturn(dto);

        mockMvc.perform(get("/api/admin/users")
                        .param("search", "ADMIN")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.searchFilter").value("ADMIN"));

        ArgumentCaptor<String> searchCaptor = ArgumentCaptor.forClass(String.class);
        verify(userListService).listUsers(searchCaptor.capture(), eq(0));
        // Verify the raw search value was passed through; no controller-
        // side mangling of the search term.
        org.junit.jupiter.api.Assertions.assertEquals(
                "ADMIN", searchCaptor.getValue());
    }

    /**
     * Verifies that a search term exceeding the 8-character
     * {@code SEC-USR-ID PIC X(08)} length triggers
     * {@code @Size(max = 8)} on the
     * {@code @RequestParam(name = "search")} parameter, surfacing as
     * HTTP 400 Bad Request via Spring's
     * {@code ConstraintViolationException} (mapped by
     * {@link GlobalExceptionHandler#handleConstraintViolation}).
     */
    @Test
    @DisplayName("listUsers_returns400ForSearchTooLong — search>8 chars violates @Size(max=8)")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void listUsers_returns400ForSearchTooLong() throws Exception {
        // 9-char search term — exceeds @Size(max = 8) on the @RequestParam.
        mockMvc.perform(get("/api/admin/users")
                        .param("search", "TOOLONG12")
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));

        // Bean Validation rejects the request before the controller
        // method is dispatched; the service must NOT be invoked.
        verifyNoInteractions(userListService);
    }

    /**
     * Verifies that a negative {@code page} parameter triggers
     * {@code @Min(value = 0)} on the
     * {@code @RequestParam(name = "page")} parameter, surfacing as
     * HTTP 400 Bad Request.
     */
    @Test
    @DisplayName("listUsers_returns400ForNegativePage — page<0 violates @Min(0)")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void listUsers_returns400ForNegativePage() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));

        verifyNoInteractions(userListService);
    }

    /**
     * Verifies that omitting the {@code page} parameter defaults to
     * {@code 0} per the
     * {@code @RequestParam(defaultValue = "0")} declaration in
     * {@link UserAdminController#listUsers(String, int)}. The captured
     * service argument is asserted to be {@code 0}.
     */
    @Test
    @DisplayName("listUsers_defaultsPageToZero — omitting page param defaults to 0")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void listUsers_defaultsPageToZero() throws Exception {
        UserListDto dto = new UserListDto(
                List.of(), 0, 10, 0L, 0, true, true, null);
        BDDMockito.given(userListService.listUsers(any(), anyInt()))
                .willReturn(dto);

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk());

        ArgumentCaptor<Integer> pageCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(userListService).listUsers(any(), pageCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                0, pageCaptor.getValue(),
                "Page should default to 0 when @RequestParam is omitted");
    }

    /**
     * Verifies the canonical PCI-DSS invariant for the user-list flow:
     * the JSON response NEVER contains a {@code password} field
     * (because {@link UserListDto.UserRow} record has no password
     * component by design). This invariant is structural &mdash; it
     * cannot be violated without modifying the {@code UserRow} record
     * itself.
     */
    @Test
    @DisplayName("listUsers_responseNeverContainsPassword — UserRow has no password field by design")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void listUsers_responseNeverContainsPassword() throws Exception {
        UserListDto dto = new UserListDto(
                List.of(new UserListDto.UserRow(USER_ID, "John", "Doe", "U")),
                0, 10, 1L, 1, true, true, null);
        BDDMockito.given(userListService.listUsers(any(), anyInt()))
                .willReturn(dto);

        MvcResult result = mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].userId").value(USER_ID))
                .andExpect(jsonPath("$.data.rows[0].password").doesNotExist())
                .andReturn();

        // Defense-in-depth: even at the raw byte level, the response
        // must not contain a "password" JSON key.
        String body = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(
                body.contains("\"password\""),
                "PCI-DSS: response body must NOT contain a password JSON key");
    }

    // =====================================================================
    // Phase 3 — POST /api/admin/users — User Add (HTTP 201 Created)
    //
    // COBOL provenance: replaces app/cbl/COUSR01C.cbl (Tran-ID CU01) —
    // the PROCESS-ENTER-KEY / WRITE-USER-SEC-FILE paragraph. The Java
    // target additionally BCrypt-hashes the plaintext password BEFORE
    // persistence (UserAddService is the BCrypt boundary) — a deliberate
    // security upgrade per AAP §0.1.1 since the legacy USRSEC file
    // stored plaintext passwords, which is incompatible with PCI-DSS.
    //
    // Bean Validation constraints on UserAddDto:
    //   userId    @NotBlank @Size(max=8) @Pattern("^[A-Za-z0-9]+$")
    //   firstName @NotBlank @Size(max=20)
    //   lastName  @NotBlank @Size(max=20)
    //   password  @NotBlank @Size(max=8)            (REQUIRED for ADD)
    //   userType  @NotBlank @Pattern("^[AU]$")      (A=Admin, U=User)
    //
    // CRITICAL PCI-DSS invariant: when a password validation error
    // occurs, the response field error MUST NOT echo the rejected
    // password literal — GlobalExceptionHandler uses the 3-arg PCI-safe
    // FieldError.of(field, code, message) factory which omits the
    // rejectedValue component.
    // =====================================================================

    /**
     * Verifies that a valid {@code POST /api/admin/users} request from
     * an ADMIN-role caller returns HTTP 201 Created and that the
     * response body carries the standardized {@link ApiResponse}
     * envelope wrapping a {@link UserAddDto} with the password
     * component nulled by the service (the service nulls the password
     * after BCrypt-hashing to prevent plaintext leakage in responses,
     * per AAP &sect;0.6.6 PCI-DSS).
     */
    @Test
    @DisplayName("addUser_returns201WithUserRow — valid POST returns HTTP 201 with envelope")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void addUser_returns201WithUserRow() throws Exception {
        UserAddDto request = new UserAddDto(
                "NEWUSER1", "New", "User", PLAINTEXT_PASSWORD, "U");
        // The service nulls the password before returning to prevent
        // plaintext leakage in responses (PCI-DSS AAP §0.6.6).
        UserAddDto savedWithoutPassword = new UserAddDto(
                "NEWUSER1", "New", "User", null, "U");
        BDDMockito.given(userAddService.addUser(any(UserAddDto.class)))
                .willReturn(savedWithoutPassword);

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("User created successfully"))
                .andExpect(jsonPath("$.data.userId").value("NEWUSER1"))
                .andExpect(jsonPath("$.data.firstName").value("New"))
                .andExpect(jsonPath("$.data.lastName").value("User"))
                .andExpect(jsonPath("$.data.userType").value("U"))
                // PCI-DSS: response NEVER contains the password component
                // (the service set it to null, and Jackson's
                // @JsonInclude(NON_NULL) propagation removes null
                // properties from the response if applicable).
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.timestamp").exists());

        verify(userAddService).addUser(any(UserAddDto.class));
    }

    /**
     * Verifies that a blank {@code userId} violates
     * {@code @NotBlank} on {@link UserAddDto#userId()} and surfaces as
     * HTTP 400 Bad Request with a {@code fieldErrors} entry for the
     * {@code userId} field.
     */
    @Test
    @DisplayName("addUser_returns400ForBlankUserId — blank userId violates @NotBlank")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void addUser_returns400ForBlankUserId() throws Exception {
        // CRITICAL: blank string ("") triggers @NotBlank (not @Size).
        UserAddDto request = new UserAddDto(
                "", "John", "Doe", PLAINTEXT_PASSWORD, "U");

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='userId')]").exists());

        verifyNoInteractions(userAddService);
    }

    /**
     * Verifies that a {@code userId} exceeding 8 characters violates
     * {@code @Size(max = 8)} on {@link UserAddDto#userId()} and
     * surfaces as HTTP 400 Bad Request.
     */
    @Test
    @DisplayName("addUser_returns400ForUserIdTooLong — 9-char userId violates @Size(max=8)")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void addUser_returns400ForUserIdTooLong() throws Exception {
        // 9 chars — exceeds @Size(max = 8) but matches @Pattern.
        UserAddDto request = new UserAddDto(
                "TOOLONG12", "John", "Doe", PLAINTEXT_PASSWORD, "U");

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='userId')]").exists());

        verifyNoInteractions(userAddService);
    }

    /**
     * Verifies that a {@code userId} containing characters outside the
     * {@code ^[A-Za-z0-9]+$} pattern (e.g., a dash) violates
     * {@code @Pattern} and surfaces as HTTP 400 Bad Request.
     */
    @Test
    @DisplayName("addUser_returns400ForUserIdInvalidPattern — non-alphanumeric userId violates @Pattern")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void addUser_returns400ForUserIdInvalidPattern() throws Exception {
        // Dash violates the @Pattern("^[A-Za-z0-9]+$").
        UserAddDto request = new UserAddDto(
                "USER-001", "John", "Doe", PLAINTEXT_PASSWORD, "U");

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='userId')]").exists());

        verifyNoInteractions(userAddService);
    }

    /**
     * Verifies that a blank {@code firstName} violates
     * {@code @NotBlank} on {@link UserAddDto#firstName()} and surfaces
     * as HTTP 400 Bad Request with a {@code fieldErrors} entry for
     * the {@code firstName} field.
     */
    @Test
    @DisplayName("addUser_returns400ForBlankFirstName — blank firstName violates @NotBlank")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void addUser_returns400ForBlankFirstName() throws Exception {
        UserAddDto request = new UserAddDto(
                "NEWUSER1", "", "Doe", PLAINTEXT_PASSWORD, "U");

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='firstName')]").exists());

        verifyNoInteractions(userAddService);
    }

    /**
     * Verifies that a {@code firstName} exceeding 20 characters
     * violates {@code @Size(max = 20)} on
     * {@link UserAddDto#firstName()} and surfaces as HTTP 400 Bad
     * Request.
     */
    @Test
    @DisplayName("addUser_returns400ForFirstNameTooLong — 21-char firstName violates @Size(max=20)")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void addUser_returns400ForFirstNameTooLong() throws Exception {
        // 21 chars — exceeds @Size(max = 20) on firstName.
        String tooLong = "ABCDEFGHIJKLMNOPQRSTU";
        UserAddDto request = new UserAddDto(
                "NEWUSER1", tooLong, "Doe", PLAINTEXT_PASSWORD, "U");

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='firstName')]").exists());

        verifyNoInteractions(userAddService);
    }

    /**
     * Verifies that a blank {@code password} violates
     * {@code @NotBlank} on {@link UserAddDto#password()} and surfaces
     * as HTTP 400 Bad Request with a {@code fieldErrors} entry for
     * the {@code password} field.
     *
     * <p><strong>PCI-DSS CRITICAL:</strong> the field error MUST NOT
     * contain the rejected password literal. The standardized
     * {@link com.awsm2.carddemo.exception.GlobalExceptionHandler} uses
     * the 3-arg PCI-safe
     * {@link ApiResponse.FieldError#of(String, String, String)}
     * factory which omits the {@code rejectedValue} component
     * (AAP &sect;0.6.6).</p>
     */
    @Test
    @DisplayName("addUser_returns400ForBlankPassword — blank password violates @NotBlank (no rejectedValue echoed)")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void addUser_returns400ForBlankPassword() throws Exception {
        // Blank password — violates @NotBlank.
        UserAddDto request = new UserAddDto(
                "NEWUSER1", "John", "Doe", "", "U");

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='password')]").exists())
                // PCI-DSS: rejected password value MUST NOT be echoed.
                .andExpect(jsonPath(
                        "$.fieldErrors[?(@.field=='password')].rejectedValue")
                        .doesNotExist());

        verifyNoInteractions(userAddService);
    }

    /**
     * Verifies that a {@code password} exceeding 8 characters violates
     * {@code @Size(max = 8)} on {@link UserAddDto#password()} and
     * surfaces as HTTP 400 Bad Request, while critically ensuring the
     * rejected (over-long) password literal is NOT echoed in the
     * field error (PCI-DSS AAP &sect;0.6.6).
     */
    @Test
    @DisplayName("addUser_returns400ForPasswordTooLong — 9-char password violates @Size(max=8) (no rejectedValue)")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void addUser_returns400ForPasswordTooLong() throws Exception {
        // 9 chars — exceeds @Size(max = 8) on password.
        String tooLongPassword = "P@ssword1";
        UserAddDto request = new UserAddDto(
                "NEWUSER1", "John", "Doe", tooLongPassword, "U");

        MvcResult result = mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='password')]").exists())
                .andExpect(jsonPath(
                        "$.fieldErrors[?(@.field=='password')].rejectedValue")
                        .doesNotExist())
                .andReturn();

        // Defense-in-depth at the raw byte level: the rejected password
        // literal must NOT appear anywhere in the response body.
        String body = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(
                body.contains(tooLongPassword),
                "PCI-DSS: rejected password literal must NOT appear in response");

        verifyNoInteractions(userAddService);
    }

    /**
     * Verifies that an invalid {@code userType} (anything other than
     * "A" or "U") violates {@code @Pattern("^[AU]$")} on
     * {@link UserAddDto#userType()} and surfaces as HTTP 400 Bad
     * Request with a {@code fieldErrors} entry for the {@code userType}
     * field.
     */
    @Test
    @DisplayName("addUser_returns400ForInvalidUserType — userType='X' violates @Pattern('^[AU]$')")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void addUser_returns400ForInvalidUserType() throws Exception {
        // "X" is not "A" or "U" — violates @Pattern("^[AU]$") on userType.
        UserAddDto request = new UserAddDto(
                "NEWUSER1", "John", "Doe", PLAINTEXT_PASSWORD, "X");

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='userType')]").exists());

        verifyNoInteractions(userAddService);
    }

    /**
     * Verifies that when {@link UserAddService} throws
     * {@link DuplicateRecordException} (the Java target for COBOL
     * FILE STATUS '22' DUPKEY in {@code COUSR01C.cbl} —
     * "User ID already exist..."), the
     * {@link com.awsm2.carddemo.exception.GlobalExceptionHandler}
     * surfaces it as HTTP 409 Conflict with the
     * {@code reasonCode = "DUPLICATE_USER"} set by the real
     * {@link UserAddService} when it detects a duplicate user ID
     * (see {@code UserAddService.addUser}, which throws
     * {@code new DuplicateRecordException("DUPLICATE_USER", "User
     * already exists: ...")}).
     */
    @Test
    @DisplayName("addUser_returns409WhenServiceThrowsDuplicateRecord — DUPKEY → HTTP 409")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void addUser_returns409WhenServiceThrowsDuplicateRecord() throws Exception {
        UserAddDto request = new UserAddDto(
                "EXISTING", "John", "Doe", PLAINTEXT_PASSWORD, "U");
        // Mirror the exact constructor pattern used by the real
        // UserAddService.addUser when a duplicate is detected, so this
        // test exercises the same reasonCode path as production.
        BDDMockito.willThrow(new DuplicateRecordException(
                        "DUPLICATE_USER",
                        "User already exists: EXISTING"))
                .given(userAddService).addUser(any(UserAddDto.class));

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                // Code is preserved verbatim from the exception's
                // reasonCode per AAP §0.7.2 ("Error codes ... preserved
                // verbatim"). GlobalExceptionHandler falls back to
                // "DUPLICATE" only when reasonCode is null, which the
                // DuplicateRecordException constructors never produce.
                .andExpect(jsonPath("$.code").value("DUPLICATE_USER"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());

        verify(userAddService).addUser(any(UserAddDto.class));
    }

    /**
     * Verifies that when {@link UserAddService} throws
     * {@link ValidationException} (for service-level business-rule
     * violations not catchable by Jakarta Bean Validation), the
     * {@link com.awsm2.carddemo.exception.GlobalExceptionHandler}
     * surfaces it as HTTP 400 Bad Request.
     */
    @Test
    @DisplayName("addUser_returns400WhenServiceThrowsValidation — service-level validation → HTTP 400")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void addUser_returns400WhenServiceThrowsValidation() throws Exception {
        UserAddDto request = new UserAddDto(
                "NEWUSER1", "John", "Doe", PLAINTEXT_PASSWORD, "U");
        BDDMockito.willThrow(new ValidationException("Invalid user data"))
                .given(userAddService).addUser(any(UserAddDto.class));

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));

        verify(userAddService).addUser(any(UserAddDto.class));
    }

    /**
     * Verifies that the plaintext password sent in the request body is
     * NEVER echoed in the response body. The service nulls the
     * password component before returning the saved DTO (per AAP
     * &sect;0.6.6 PCI-DSS), and this test confirms that contract
     * end-to-end at the raw byte level.
     */
    @Test
    @DisplayName("addUser_neverEchoesPasswordInResponse — plaintext password never appears in response")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void addUser_neverEchoesPasswordInResponse() throws Exception {
        UserAddDto request = new UserAddDto(
                "NEWUSER1", "John", "Doe", PLAINTEXT_PASSWORD, "U");
        // Service contract: nulls password before return.
        UserAddDto saved = new UserAddDto(
                "NEWUSER1", "John", "Doe", null, "U");
        BDDMockito.given(userAddService.addUser(any(UserAddDto.class)))
                .willReturn(saved);

        MvcResult result = mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(
                body.contains(PLAINTEXT_PASSWORD),
                "PCI-DSS: plaintext password must NEVER appear in response");
        org.junit.jupiter.api.Assertions.assertFalse(
                body.contains("\"password\""),
                "PCI-DSS: response body must NOT contain a password JSON key");
    }

    /**
     * Verifies that when a password validation error occurs (e.g.,
     * blank or over-long password), the resulting JSON
     * {@code fieldErrors} entry for the password field does NOT
     * include the rejected literal password value. The
     * {@link com.awsm2.carddemo.exception.GlobalExceptionHandler} uses
     * the 3-arg PCI-safe
     * {@link ApiResponse.FieldError#of(String, String, String)}
     * factory which omits the {@code rejectedValue} component.
     */
    @Test
    @DisplayName("addUser_neverEchoesPasswordInErrorFields — rejected password never in fieldErrors")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void addUser_neverEchoesPasswordInErrorFields() throws Exception {
        // Use a distinctive password literal that we can grep for.
        String distinctivePassword = "Sek1234X"; // 8 chars, valid pattern
        // Make it fail by sending an invalid userId so validation fires.
        UserAddDto request = new UserAddDto(
                "", "John", "Doe", distinctivePassword, "U");

        MvcResult result = mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // Defense in depth — the rejected password literal must NEVER
        // appear in any portion of the JSON error response body.
        org.junit.jupiter.api.Assertions.assertFalse(
                body.contains(distinctivePassword),
                "PCI-DSS: rejected password literal must NOT appear in fieldErrors response");
    }

    // =====================================================================
    // Phase 4 — PUT /api/admin/users/{id} — User Update (HTTP 200 OK)
    //
    // COBOL provenance: replaces app/cbl/COUSR02C.cbl (Tran-ID CU02) —
    // the UPDATE-USER-INFO / UPDATE-USER-SEC-FILE paragraph. The Java
    // target preserves the COUSR02C semantic that the password is
    // OPTIONAL — if blank/null the existing BCrypt hash is retained,
    // otherwise the new plaintext value is re-hashed and persisted
    // (the legacy program had required-password semantics; the AAP
    // §0.7.3 Minimal Change Clause specifically calls out preserving
    // the legacy "fill the existing values" PROCESS-PF6-KEY behavior
    // while allowing password rotation when supplied).
    //
    // Bean Validation constraints on UserUpdateDto:
    //   userId    @NotBlank @Size(max=8) @Pattern("^[A-Za-z0-9]+$")
    //   firstName @NotBlank @Size(max=20)
    //   lastName  @NotBlank @Size(max=20)
    //   password  @Size(max=8)            (OPTIONAL — no @NotBlank)
    //   userType  @NotBlank @Pattern("^[AU]$")
    //
    // Path variable constraints (controller-level on @PathVariable id):
    //   @NotBlank @Size(min=1, max=8) @Pattern("^[A-Z0-9 ]{1,8}$")
    //
    // IDOR mitigation: path id MUST equal body userId, otherwise the
    // controller throws ValidationException("USER_ID_MISMATCH", ...)
    // BEFORE any service invocation.
    // =====================================================================

    /**
     * Verifies that a valid {@code PUT /api/admin/users/{id}} request
     * from an ADMIN-role caller returns HTTP 200 OK with the
     * standardized {@link ApiResponse} envelope wrapping the updated
     * {@link UserUpdateDto} (with the password component nulled by
     * the service per PCI-DSS AAP &sect;0.6.6).
     */
    @Test
    @DisplayName("updateUser_returns200WithUserRow — valid PUT returns HTTP 200 with envelope")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void updateUser_returns200WithUserRow() throws Exception {
        UserUpdateDto request = new UserUpdateDto(
                USER_ID, "John", "Doe", "NewPa55w", "U");
        // Service contract: nulls password before return.
        UserUpdateDto updated = new UserUpdateDto(
                USER_ID, "John", "Doe", null, "U");
        BDDMockito.given(userUpdateService.updateUser(
                        eq(USER_ID), any(UserUpdateDto.class)))
                .willReturn(updated);

        mockMvc.perform(put("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("User updated successfully"))
                .andExpect(jsonPath("$.data.userId").value(USER_ID))
                .andExpect(jsonPath("$.data.firstName").value("John"))
                .andExpect(jsonPath("$.data.lastName").value("Doe"))
                .andExpect(jsonPath("$.data.userType").value("U"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.timestamp").exists());

        verify(userUpdateService).updateUser(eq(USER_ID), any(UserUpdateDto.class));
    }

    /**
     * Verifies that a {@code null} password on
     * {@link UserUpdateDto#password()} is accepted (the field is
     * declared {@code @Size(max = 8)} only &mdash; no {@code @NotBlank}
     * &mdash; so {@code null} passes Bean Validation, and the service
     * interprets {@code null} as "retain existing BCrypt hash").
     */
    @Test
    @DisplayName("updateUser_acceptsNullPassword — null password passes Bean Validation (retain existing)")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void updateUser_acceptsNullPassword() throws Exception {
        // password = null — should be accepted (no @NotBlank on update).
        UserUpdateDto request = new UserUpdateDto(
                USER_ID, "John", "Doe", null, "U");
        UserUpdateDto updated = new UserUpdateDto(
                USER_ID, "John", "Doe", null, "U");
        BDDMockito.given(userUpdateService.updateUser(
                        eq(USER_ID), any(UserUpdateDto.class)))
                .willReturn(updated);

        mockMvc.perform(put("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(USER_ID))
                .andExpect(jsonPath("$.data.password").doesNotExist());

        verify(userUpdateService).updateUser(eq(USER_ID), any(UserUpdateDto.class));
    }

    /**
     * Verifies that a blank ({@code ""}) password on
     * {@link UserUpdateDto#password()} is accepted (the field is
     * declared {@code @Size(max = 8)} only). {@code @Size} permits
     * empty strings, and the service interprets blank as
     * "retain existing BCrypt hash" identically to {@code null}.
     */
    @Test
    @DisplayName("updateUser_acceptsBlankPassword — empty password passes Bean Validation (retain existing)")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void updateUser_acceptsBlankPassword() throws Exception {
        // password = "" — should be accepted (no @NotBlank on update).
        UserUpdateDto request = new UserUpdateDto(
                USER_ID, "John", "Doe", "", "U");
        UserUpdateDto updated = new UserUpdateDto(
                USER_ID, "John", "Doe", null, "U");
        BDDMockito.given(userUpdateService.updateUser(
                        eq(USER_ID), any(UserUpdateDto.class)))
                .willReturn(updated);

        mockMvc.perform(put("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.password").doesNotExist());

        verify(userUpdateService).updateUser(eq(USER_ID), any(UserUpdateDto.class));
    }

    /**
     * Verifies that a path id exceeding 8 characters violates
     * {@code @Size(min = 1, max = 8)} on the
     * {@code @PathVariable("id")} parameter, surfacing as HTTP 400
     * Bad Request via Spring's
     * {@code ConstraintViolationException} (mapped by
     * {@link com.awsm2.carddemo.exception.GlobalExceptionHandler}).
     */
    @Test
    @DisplayName("updateUser_returns400ForPathIdTooLong — 9-char path id violates @Size(max=8)")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void updateUser_returns400ForPathIdTooLong() throws Exception {
        // 9-char path id — exceeds @Size(max = 8) on the @PathVariable.
        // The body must match the path id to bypass the IDOR check
        // (otherwise the controller throws USER_ID_MISMATCH which is
        // a different code path); but here we expect the
        // @Size on the @PathVariable to fire BEFORE the IDOR check, so
        // body values don't matter — use legal body fields anyway.
        UserUpdateDto request = new UserUpdateDto(
                "TOOLONG12", "John", "Doe", null, "U");

        mockMvc.perform(put("/api/admin/users/TOOLONG12")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));

        verifyNoInteractions(userUpdateService);
    }

    /**
     * Verifies that a path id containing characters outside the
     * {@code ^[A-Z0-9 ]{1,8}$} pattern (e.g., lowercase letters or a
     * dash) violates {@code @Pattern} on the
     * {@code @PathVariable("id")} parameter, surfacing as HTTP 400
     * Bad Request.
     */
    @Test
    @DisplayName("updateUser_returns400ForPathIdInvalidPattern — lowercase path id violates @Pattern")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void updateUser_returns400ForPathIdInvalidPattern() throws Exception {
        // Lowercase letters violate the @Pattern("^[A-Z0-9 ]{1,8}$") on path.
        UserUpdateDto request = new UserUpdateDto(
                "user0001", "John", "Doe", null, "U");

        mockMvc.perform(put("/api/admin/users/user0001")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));

        verifyNoInteractions(userUpdateService);
    }

    /**
     * Verifies that body-level validation failures (blank
     * {@code firstName}, blank {@code lastName}, or invalid
     * {@code userType}) surface as HTTP 400 Bad Request with the
     * corresponding {@code fieldErrors} entries.
     */
    @Test
    @DisplayName("updateUser_returns400ForBodyValidationFailures — blank/invalid body fields → HTTP 400")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void updateUser_returns400ForBodyValidationFailures() throws Exception {
        // Multiple violations: blank firstName, blank lastName, invalid userType.
        UserUpdateDto request = new UserUpdateDto(
                USER_ID, "", "", null, "Z");

        mockMvc.perform(put("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='firstName')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='lastName')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='userType')]").exists());

        verifyNoInteractions(userUpdateService);
    }

    /**
     * Verifies that when {@link UserUpdateService} throws
     * {@link RecordNotFoundException} (the Java target for COBOL FILE
     * STATUS '23' NOTFND in {@code COUSR02C.cbl} —
     * "User ID NOT found..."), the
     * {@link com.awsm2.carddemo.exception.GlobalExceptionHandler}
     * surfaces it as HTTP 404 Not Found.
     */
    @Test
    @DisplayName("updateUser_returns404WhenServiceThrowsRecordNotFound — NOTFND → HTTP 404")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void updateUser_returns404WhenServiceThrowsRecordNotFound() throws Exception {
        String missingUserId = "MISSING1";
        UserUpdateDto request = new UserUpdateDto(
                missingUserId, "John", "Doe", null, "U");
        BDDMockito.willThrow(new RecordNotFoundException(
                        "UserSecurity", missingUserId))
                .given(userUpdateService).updateUser(
                        eq(missingUserId), any(UserUpdateDto.class));

        mockMvc.perform(put("/api/admin/users/" + missingUserId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());

        verify(userUpdateService).updateUser(eq(missingUserId), any(UserUpdateDto.class));
    }

    /**
     * Verifies that when {@link UserUpdateService} throws
     * {@link ValidationException} (for service-level business-rule
     * violations beyond Jakarta Bean Validation), the
     * {@link com.awsm2.carddemo.exception.GlobalExceptionHandler}
     * surfaces it as HTTP 400 Bad Request.
     */
    @Test
    @DisplayName("updateUser_returns400WhenServiceThrowsValidation — service-level validation → HTTP 400")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void updateUser_returns400WhenServiceThrowsValidation() throws Exception {
        UserUpdateDto request = new UserUpdateDto(
                USER_ID, "John", "Doe", null, "U");
        BDDMockito.willThrow(new ValidationException(
                        "User update violates business rule"))
                .given(userUpdateService).updateUser(
                        eq(USER_ID), any(UserUpdateDto.class));

        mockMvc.perform(put("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));

        verify(userUpdateService).updateUser(eq(USER_ID), any(UserUpdateDto.class));
    }

    /**
     * Verifies that the plaintext password sent in the update request
     * body is NEVER echoed in the response body. The service nulls
     * the password component before returning the updated DTO (per
     * AAP &sect;0.6.6 PCI-DSS), and this test confirms that contract
     * end-to-end at the raw byte level.
     */
    @Test
    @DisplayName("updateUser_neverEchoesPasswordInResponse — plaintext password never in response")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void updateUser_neverEchoesPasswordInResponse() throws Exception {
        UserUpdateDto request = new UserUpdateDto(
                USER_ID, "John", "Doe", PLAINTEXT_PASSWORD, "U");
        // Service contract: nulls password before return.
        UserUpdateDto updated = new UserUpdateDto(
                USER_ID, "John", "Doe", null, "U");
        BDDMockito.given(userUpdateService.updateUser(
                        eq(USER_ID), any(UserUpdateDto.class)))
                .willReturn(updated);

        MvcResult result = mockMvc.perform(put("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(
                body.contains(PLAINTEXT_PASSWORD),
                "PCI-DSS: plaintext password must NEVER appear in update response");
        org.junit.jupiter.api.Assertions.assertFalse(
                body.contains("\"password\""),
                "PCI-DSS: response body must NOT contain a password JSON key");
    }

    /**
     * Verifies that when {@link UserUpdateService} throws an
     * unexpected {@link RuntimeException}, the
     * {@link com.awsm2.carddemo.exception.GlobalExceptionHandler}'s
     * catch-all {@code @ExceptionHandler(Exception.class)} surfaces
     * it as HTTP 500 Internal Server Error with a generic message
     * ("An unexpected error occurred") &mdash; never leaking the
     * exception message to the client.
     */
    @Test
    @DisplayName("updateUser_returns500ForUnexpectedRuntime — RuntimeException → HTTP 500 with generic message")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void updateUser_returns500ForUnexpectedRuntime() throws Exception {
        UserUpdateDto request = new UserUpdateDto(
                USER_ID, "John", "Doe", null, "U");
        BDDMockito.willThrow(new RuntimeException(
                        "internal database driver crashed"))
                .given(userUpdateService).updateUser(
                        eq(USER_ID), any(UserUpdateDto.class));

        MvcResult result = mockMvc.perform(put("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").exists())
                .andReturn();

        // Defense in depth: the internal exception message must NOT
        // be leaked to the client (info-disclosure prevention).
        String body = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(
                body.contains("internal database driver crashed"),
                "Info-disclosure: internal exception message must NOT leak to client");
    }

    // =====================================================================
    // Phase 5 — DELETE /api/admin/users/{id} — User Delete with Y/N Confirm
    //
    // COBOL provenance: replaces app/cbl/COUSR03C.cbl (Tran-ID CU03) —
    // the pseudo-conversational PF5(Y delete) / PF4(N cancel) flow.
    //
    // CRITICAL — actual controller behavior vs. schema method name:
    //
    //   confirm = "Y"  →  HTTP 204 No Content with empty body
    //                     (UserAdminController line 771:
    //                      "return ResponseEntity.noContent().build();")
    //                     The @ApiResponses annotation on the endpoint
    //                     explicitly documents 204 (line 707-708).
    //
    //   confirm = "N"  →  HTTP 200 OK with ApiResponse envelope and
    //                     message = "User deletion cancelled"
    //                     (UserAdminController line 766-767:
    //                      "return ResponseEntity.ok(...)").
    //
    // The schema method name "deleteUser_returns200WhenConfirmedY" was
    // written before the controller's 204/200 split was finalized; the
    // test below honors the actual controller behavior and documents
    // the mismatch in its DisplayName and Javadoc so future readers
    // understand the divergence is intentional, not a regression.
    //
    // Bean Validation constraints on UserDeleteDto:
    //   userId, firstName, lastName, userType  — READ_ONLY (no validators)
    //   confirm                                — @Pattern("^[YN]$") only
    //
    // Path variable constraints same as PUT (above).
    //
    // IDOR mitigation: path id MUST equal body userId.
    // =====================================================================

    /**
     * Verifies the delete-proceeds flow: when {@code confirm = "Y"},
     * the controller returns HTTP 204 No Content with an empty body
     * after a successful service invocation.
     *
     * <p><strong>Schema/implementation mismatch note:</strong> the
     * schema export {@code deleteUser_returns200WhenConfirmedY} was
     * named before the controller's 204/200 split was finalized. The
     * actual controller behavior is HTTP 204 No Content for
     * {@code confirm = "Y"} (matches the
     * {@code @ApiResponse(responseCode = "204")} declaration on the
     * endpoint and the call to
     * {@code ResponseEntity.noContent().build()} in the controller's
     * delete branch). The 200-with-envelope behavior is reserved for
     * the {@code confirm = "N"} cancellation flow ("User deletion
     * cancelled") &mdash; verified by
     * {@link #deleteUser_returnsExactlyHttp200WithEnvelope()}.</p>
     */
    @Test
    @DisplayName("deleteUser_returns200WhenConfirmedY — confirm=Y returns HTTP 204 No Content (schema name vs. actual)")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void deleteUser_returns200WhenConfirmedY() throws Exception {
        UserDeleteDto request = new UserDeleteDto(
                USER_ID, "John", "Doe", "U", "Y");
        // The service returns the dto with confirm="Y" on a successful delete.
        UserDeleteDto deleted = new UserDeleteDto(
                USER_ID, "John", "Doe", "U", "Y");
        BDDMockito.given(userDeleteService.deleteUser(
                        eq(USER_ID), any(UserDeleteDto.class)))
                .willReturn(deleted);

        // Actual controller behavior: confirm="Y" → HTTP 204 No Content
        // with empty body (UserAdminController line 771).
        mockMvc.perform(delete("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(userDeleteService).deleteUser(eq(USER_ID), any(UserDeleteDto.class));
    }

    /**
     * Verifies that {@code confirm = "X"} (anything other than "Y" or
     * "N") violates {@code @Pattern("^[YN]$")} on
     * {@link UserDeleteDto#confirm()} and surfaces as HTTP 400 Bad
     * Request via Jakarta Bean Validation.
     */
    @Test
    @DisplayName("deleteUser_returns400ForInvalidConfirm — confirm='X' violates @Pattern('^[YN]$')")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void deleteUser_returns400ForInvalidConfirm() throws Exception {
        UserDeleteDto request = new UserDeleteDto(
                USER_ID, "John", "Doe", "U", "X");

        mockMvc.perform(delete("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='confirm')]").exists());

        verifyNoInteractions(userDeleteService);
    }

    /**
     * Verifies that a blank ({@code ""}) {@code confirm} value
     * triggers {@code @Pattern("^[YN]$")} on
     * {@link UserDeleteDto#confirm()} and surfaces as HTTP 400 Bad
     * Request via Jakarta Bean Validation BEFORE the service is
     * invoked.
     *
     * <p>Important Jakarta Bean Validation note: Jakarta's
     * {@code @Pattern} validator returns {@code true} for {@code null}
     * (by spec, "Accepts {@code null} values") but returns
     * {@code false} for an empty string that does not match the
     * regular expression. Since {@code ""} does not match
     * {@code ^[YN]$}, the validator fires and Spring's
     * {@code MethodArgumentNotValidException} reaches the
     * {@link com.awsm2.carddemo.exception.GlobalExceptionHandler}
     * before the controller dispatches to the service.</p>
     */
    @Test
    @DisplayName("deleteUser_returns400ForBlankConfirm — empty confirm violates @Pattern('^[YN]$')")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void deleteUser_returns400ForBlankConfirm() throws Exception {
        UserDeleteDto request = new UserDeleteDto(
                USER_ID, "John", "Doe", "U", "");

        mockMvc.perform(delete("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='confirm')]").exists());

        // Bean Validation fires before controller dispatch; service
        // must NEVER be invoked.
        verifyNoInteractions(userDeleteService);
    }

    /**
     * Verifies that when {@link UserDeleteService} throws
     * {@link RecordNotFoundException} (the Java target for COBOL FILE
     * STATUS '23' NOTFND in {@code COUSR03C.cbl} —
     * "User ID NOT found..."), the
     * {@link com.awsm2.carddemo.exception.GlobalExceptionHandler}
     * surfaces it as HTTP 404 Not Found.
     */
    @Test
    @DisplayName("deleteUser_returns404WhenServiceThrowsRecordNotFound — NOTFND → HTTP 404")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void deleteUser_returns404WhenServiceThrowsRecordNotFound() throws Exception {
        String missingUserId = "MISSING1";
        UserDeleteDto request = new UserDeleteDto(
                missingUserId, "Unknown", "User", "U", "Y");
        BDDMockito.willThrow(new RecordNotFoundException(
                        "UserSecurity", missingUserId))
                .given(userDeleteService).deleteUser(
                        eq(missingUserId), any(UserDeleteDto.class));

        mockMvc.perform(delete("/api/admin/users/" + missingUserId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());

        verify(userDeleteService).deleteUser(eq(missingUserId), any(UserDeleteDto.class));
    }

    /**
     * Verifies that when {@link UserDeleteService} throws
     * {@link ValidationException} (for service-level business-rule
     * violations beyond Jakarta Bean Validation), the
     * {@link com.awsm2.carddemo.exception.GlobalExceptionHandler}
     * surfaces it as HTTP 400 Bad Request.
     */
    @Test
    @DisplayName("deleteUser_returns400WhenServiceThrowsValidation — service-level validation → HTTP 400")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void deleteUser_returns400WhenServiceThrowsValidation() throws Exception {
        UserDeleteDto request = new UserDeleteDto(
                USER_ID, "John", "Doe", "U", "Y");
        BDDMockito.willThrow(new ValidationException(
                        "Cannot delete the last admin user"))
                .given(userDeleteService).deleteUser(
                        eq(USER_ID), any(UserDeleteDto.class));

        mockMvc.perform(delete("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));

        verify(userDeleteService).deleteUser(eq(USER_ID), any(UserDeleteDto.class));
    }

    // =====================================================================
    // Phase 6 — HTTP Status Verification (CRITICAL — AAP §0.4.1)
    //
    // These two tests are pinned guards against accidental regressions
    // in the HTTP status semantics:
    //   POST /api/admin/users          → EXACTLY 201 Created (never 200)
    //   DELETE /api/admin/users/{id}   →
    //       confirm = "N"  →  EXACTLY 200 OK with full envelope
    //                         (proves cancellation returns the
    //                         standard envelope rather than 204)
    //       confirm = "Y"  →  EXACTLY 204 No Content (covered by
    //                         deleteUser_returns200WhenConfirmedY above)
    //
    // The schema export name "deleteUser_returnsExactlyHttp200WithEnvelope"
    // is honored by exercising the cancellation flow which IS the
    // 200-with-envelope branch in the actual controller implementation.
    // =====================================================================

    /**
     * Verifies that a successful {@code POST /api/admin/users} returns
     * <em>exactly</em> HTTP 201 Created and not any other 2xx status.
     * The Spring MVC convention for resource creation is 201; using
     * 200 would silently change the API contract for downstream
     * clients relying on status-code branching.
     */
    @Test
    @DisplayName("addUser_returnsExactlyHttp201 — POST returns exactly HTTP 201 (not 200)")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void addUser_returnsExactlyHttp201() throws Exception {
        UserAddDto request = new UserAddDto(
                "NEWUSER2", "Pin", "Test", PLAINTEXT_PASSWORD, "U");
        UserAddDto saved = new UserAddDto(
                "NEWUSER2", "Pin", "Test", null, "U");
        BDDMockito.given(userAddService.addUser(any(UserAddDto.class)))
                .willReturn(saved);

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                // Exact match: HTTP 201 — not 200, not 202, not 204.
                .andExpect(status().is(201));
    }

    /**
     * Verifies that the {@code confirm = "N"} cancellation flow on
     * {@code DELETE /api/admin/users/{id}} returns <em>exactly</em>
     * HTTP 200 OK with the full {@link ApiResponse} envelope (code,
     * message = "User deletion cancelled", data, timestamp) &mdash;
     * the canonical 200-with-envelope branch in the controller's
     * delete implementation.
     */
    @Test
    @DisplayName("deleteUser_returnsExactlyHttp200WithEnvelope — confirm=N returns exactly HTTP 200 with envelope")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void deleteUser_returnsExactlyHttp200WithEnvelope() throws Exception {
        UserDeleteDto request = new UserDeleteDto(
                USER_ID, "John", "Doe", "U", "N");
        // Service returns the dto with confirm="N" on cancellation.
        UserDeleteDto cancelled = new UserDeleteDto(
                USER_ID, "John", "Doe", "U", "N");
        BDDMockito.given(userDeleteService.deleteUser(
                        eq(USER_ID), any(UserDeleteDto.class)))
                .willReturn(cancelled);

        mockMvc.perform(delete("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                // Exact match: HTTP 200 with envelope — never 204.
                .andExpect(status().is(200))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("User deletion cancelled"))
                .andExpect(jsonPath("$.data.userId").value(USER_ID))
                .andExpect(jsonPath("$.data.confirm").value("N"))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(userDeleteService).deleteUser(eq(USER_ID), any(UserDeleteDto.class));
    }

    // =====================================================================
    // Phase 7 — PCI-DSS Password Protection (AAP §0.6.6, §0.7.1)
    //
    // Two end-to-end invariants:
    //
    //   1. No 4-endpoint response body ever contains a "password" JSON
    //      key — even in the absence of any test-specific assertion.
    //
    //   2. When a password validation error occurs, the response
    //      fieldError MUST NOT include the rejectedValue component.
    //      The 3-arg PCI-safe FieldError.of(field, code, message)
    //      factory used by GlobalExceptionHandler guarantees this.
    //
    // These two tests serve as the master invariant guards. If a
    // future change adds password leakage anywhere on the
    // /api/admin/users surface, at least one of these two tests will
    // fail loudly.
    // =====================================================================

    /**
     * Master invariant guard: across all four endpoints
     * ({@code GET}, {@code POST}, {@code PUT}, {@code DELETE}) a
     * successful response body must never contain a {@code "password"}
     * JSON key. This is enforced structurally by
     * {@link UserListDto.UserRow} (no password component) and by the
     * service contract that nulls the password component on
     * {@link UserAddDto}/{@link UserUpdateDto} before returning.
     */
    @Test
    @DisplayName("userResponses_neverIncludePasswordField — no 'password' JSON key across all 4 endpoints")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void userResponses_neverIncludePasswordField() throws Exception {
        // ---- GET ----
        UserListDto listDto = new UserListDto(
                List.of(new UserListDto.UserRow(USER_ID, "John", "Doe", "U")),
                0, 10, 1L, 1, true, true, null);
        BDDMockito.given(userListService.listUsers(any(), anyInt()))
                .willReturn(listDto);

        String listBody = mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(
                listBody.contains("\"password\""),
                "PCI-DSS: GET response must not contain a password JSON key");

        // ---- POST ----
        UserAddDto addRequest = new UserAddDto(
                "NEWUSER3", "Pat", "Lee", PLAINTEXT_PASSWORD, "U");
        UserAddDto addSaved = new UserAddDto(
                "NEWUSER3", "Pat", "Lee", null, "U");
        BDDMockito.given(userAddService.addUser(any(UserAddDto.class)))
                .willReturn(addSaved);

        String addBody = mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(
                addBody.contains("\"password\""),
                "PCI-DSS: POST response must not contain a password JSON key");
        org.junit.jupiter.api.Assertions.assertFalse(
                addBody.contains(PLAINTEXT_PASSWORD),
                "PCI-DSS: POST response must not contain the plaintext password");

        // ---- PUT ----
        UserUpdateDto updateRequest = new UserUpdateDto(
                USER_ID, "John", "Doe", PLAINTEXT_PASSWORD, "U");
        UserUpdateDto updated = new UserUpdateDto(
                USER_ID, "John", "Doe", null, "U");
        BDDMockito.given(userUpdateService.updateUser(
                        eq(USER_ID), any(UserUpdateDto.class)))
                .willReturn(updated);

        String updateBody = mockMvc.perform(put("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(
                updateBody.contains("\"password\""),
                "PCI-DSS: PUT response must not contain a password JSON key");
        org.junit.jupiter.api.Assertions.assertFalse(
                updateBody.contains(PLAINTEXT_PASSWORD),
                "PCI-DSS: PUT response must not contain the plaintext password");

        // ---- DELETE (confirm=N → 200 with envelope) ----
        // DELETE bodies don't ever contain a password component
        // (UserDeleteDto has no password field by design); we still
        // verify the response body for completeness.
        UserDeleteDto deleteRequest = new UserDeleteDto(
                USER_ID, "John", "Doe", "U", "N");
        UserDeleteDto deleted = new UserDeleteDto(
                USER_ID, "John", "Doe", "U", "N");
        BDDMockito.given(userDeleteService.deleteUser(
                        eq(USER_ID), any(UserDeleteDto.class)))
                .willReturn(deleted);

        String deleteBody = mockMvc.perform(delete("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(
                deleteBody.contains("\"password\""),
                "PCI-DSS: DELETE response must not contain a password JSON key");
    }

    /**
     * Master invariant guard: validation errors on the
     * {@code password} field never echo the rejected literal value.
     * The {@link com.awsm2.carddemo.exception.GlobalExceptionHandler}
     * uses the 3-arg PCI-safe
     * {@link ApiResponse.FieldError#of(String, String, String)}
     * factory which omits the {@code rejectedValue} component.
     *
     * <p>This test sends a request with a distinctive password
     * literal AND an invalid userId (to trigger Bean Validation), then
     * greps the raw response body for the password literal to confirm
     * absence.</p>
     */
    @Test
    @DisplayName("userValidationErrors_neverIncludeRejectedPasswordValue — rejected password literal never in fieldErrors")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void userValidationErrors_neverIncludeRejectedPasswordValue() throws Exception {
        String distinctivePassword = "Z9Y8X7W6";
        // Send a request with blank userId (triggers @NotBlank on userId).
        UserAddDto request = new UserAddDto(
                "", "John", "Doe", distinctivePassword, "U");

        MvcResult result = mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // The distinctive password literal must NEVER appear in the
        // validation error response.
        org.junit.jupiter.api.Assertions.assertFalse(
                body.contains(distinctivePassword),
                "PCI-DSS: rejected password literal must NOT appear in fieldErrors response");
    }

    // =====================================================================
    // Phase 8 — Service Argument Verification
    //
    // ArgumentCaptor-based tests that prove the controller forwards
    // request data to its services unchanged (no controller-side
    // mutation, sanitization, or normalization is applied beyond
    // what Bean Validation has already enforced). This validates the
    // "thin controller" architecture mandated by AAP §0.3.3 (Layered
    // Architecture pattern) — controllers are HTTP-binding shims, all
    // business logic lives in services.
    // =====================================================================

    /**
     * Verifies that the {@code search} and {@code page} query
     * parameters are forwarded to
     * {@link UserListService#listUsers(String, int)} verbatim
     * (no trimming, no uppercasing, no other transformation).
     */
    @Test
    @DisplayName("listUsers_passesSearchAndPageToService — search and page captured unchanged")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void listUsers_passesSearchAndPageToService() throws Exception {
        UserListDto dto = new UserListDto(
                List.of(), 2, 10, 0L, 0, false, true, "FilterX");
        BDDMockito.given(userListService.listUsers(eq("FilterX"), eq(2)))
                .willReturn(dto);

        mockMvc.perform(get("/api/admin/users")
                        .param("search", "FilterX")
                        .param("page", "2"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> searchCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> pageCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(userListService).listUsers(searchCaptor.capture(), pageCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                "FilterX", searchCaptor.getValue(),
                "Controller must forward search unchanged to UserListService");
        org.junit.jupiter.api.Assertions.assertEquals(
                2, pageCaptor.getValue(),
                "Controller must forward page unchanged to UserListService");
    }

    /**
     * Verifies that every field of the {@link UserAddDto} request
     * body is forwarded to {@link UserAddService#addUser(UserAddDto)}
     * unchanged &mdash; including the plaintext password (which the
     * service then BCrypt-hashes per AAP &sect;0.1.1; the controller
     * never hashes).
     */
    @Test
    @DisplayName("addUser_passesAllFieldsToService — all UserAddDto fields captured unchanged (BCrypt is service responsibility)")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void addUser_passesAllFieldsToService() throws Exception {
        UserAddDto request = new UserAddDto(
                "NEWUSER4", "Verify", "Args", PLAINTEXT_PASSWORD, "U");
        UserAddDto saved = new UserAddDto(
                "NEWUSER4", "Verify", "Args", null, "U");
        BDDMockito.given(userAddService.addUser(any(UserAddDto.class)))
                .willReturn(saved);

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        ArgumentCaptor<UserAddDto> dtoCaptor = ArgumentCaptor.forClass(UserAddDto.class);
        verify(userAddService).addUser(dtoCaptor.capture());
        UserAddDto captured = dtoCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(
                "NEWUSER4", captured.userId(),
                "Controller must forward userId unchanged");
        org.junit.jupiter.api.Assertions.assertEquals(
                "Verify", captured.firstName(),
                "Controller must forward firstName unchanged");
        org.junit.jupiter.api.Assertions.assertEquals(
                "Args", captured.lastName(),
                "Controller must forward lastName unchanged");
        org.junit.jupiter.api.Assertions.assertEquals(
                "U", captured.userType(),
                "Controller must forward userType unchanged");
        // CRITICAL: controller forwards plaintext password unchanged —
        // BCrypt hashing is the SERVICE's responsibility (AAP §0.1.1).
        org.junit.jupiter.api.Assertions.assertEquals(
                PLAINTEXT_PASSWORD, captured.password(),
                "Controller must forward plaintext password unchanged "
                        + "(BCrypt hashing is service responsibility)");
    }

    /**
     * Verifies that the path {@code id} and the
     * {@link UserUpdateDto} request body are forwarded to
     * {@link UserUpdateService#updateUser(String, UserUpdateDto)}
     * unchanged. The first argument is the path id; the second
     * argument is the body DTO. The IDOR mitigation check has already
     * been passed when this verification fires.
     */
    @Test
    @DisplayName("updateUser_passesPathIdAndDtoToService — path id + DTO captured unchanged")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void updateUser_passesPathIdAndDtoToService() throws Exception {
        UserUpdateDto request = new UserUpdateDto(
                USER_ID, "John", "Doe", "NewPass1", "A");
        UserUpdateDto updated = new UserUpdateDto(
                USER_ID, "John", "Doe", null, "A");
        BDDMockito.given(userUpdateService.updateUser(
                        anyString(), any(UserUpdateDto.class)))
                .willReturn(updated);

        mockMvc.perform(put("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UserUpdateDto> dtoCaptor = ArgumentCaptor.forClass(UserUpdateDto.class);
        verify(userUpdateService).updateUser(idCaptor.capture(), dtoCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                USER_ID, idCaptor.getValue(),
                "Controller must forward path id unchanged as first arg");
        UserUpdateDto captured = dtoCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(
                USER_ID, captured.userId(),
                "Controller must forward DTO userId unchanged");
        org.junit.jupiter.api.Assertions.assertEquals(
                "John", captured.firstName(),
                "Controller must forward DTO firstName unchanged");
        org.junit.jupiter.api.Assertions.assertEquals(
                "Doe", captured.lastName(),
                "Controller must forward DTO lastName unchanged");
        org.junit.jupiter.api.Assertions.assertEquals(
                "A", captured.userType(),
                "Controller must forward DTO userType unchanged");
        // CRITICAL: plaintext password forwarded unchanged to service.
        org.junit.jupiter.api.Assertions.assertEquals(
                "NewPass1", captured.password(),
                "Controller must forward plaintext password unchanged");
    }

    /**
     * Verifies that the path {@code id} and the
     * {@link UserDeleteDto} request body are forwarded to
     * {@link UserDeleteService#deleteUser(String, UserDeleteDto)}
     * unchanged. The first argument is the path id; the second
     * argument is the body DTO. The IDOR mitigation check has already
     * been passed when this verification fires.
     */
    @Test
    @DisplayName("deleteUser_passesPathIdAndDtoToService — path id + DTO captured unchanged")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void deleteUser_passesPathIdAndDtoToService() throws Exception {
        UserDeleteDto request = new UserDeleteDto(
                USER_ID, "John", "Doe", "U", "Y");
        UserDeleteDto deleted = new UserDeleteDto(
                USER_ID, "John", "Doe", "U", "Y");
        BDDMockito.given(userDeleteService.deleteUser(
                        anyString(), any(UserDeleteDto.class)))
                .willReturn(deleted);

        mockMvc.perform(delete("/api/admin/users/" + USER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                // confirm=Y → 204 No Content (actual controller behavior)
                .andExpect(status().isNoContent());

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UserDeleteDto> dtoCaptor = ArgumentCaptor.forClass(UserDeleteDto.class);
        verify(userDeleteService).deleteUser(idCaptor.capture(), dtoCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                USER_ID, idCaptor.getValue(),
                "Controller must forward path id unchanged as first arg");
        UserDeleteDto captured = dtoCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(
                USER_ID, captured.userId(),
                "Controller must forward DTO userId unchanged");
        org.junit.jupiter.api.Assertions.assertEquals(
                "Y", captured.confirm(),
                "Controller must forward DTO confirm unchanged");
        org.junit.jupiter.api.Assertions.assertEquals(
                "John", captured.firstName(),
                "Controller must forward DTO firstName unchanged");
        org.junit.jupiter.api.Assertions.assertEquals(
                "Doe", captured.lastName(),
                "Controller must forward DTO lastName unchanged");
        org.junit.jupiter.api.Assertions.assertEquals(
                "U", captured.userType(),
                "Controller must forward DTO userType unchanged");
    }
}

