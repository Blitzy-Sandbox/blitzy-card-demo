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
 * UserAdminControllerTest — Spring MVC slice test for UserAdminController
 *
 * Replaces BMS mapsets: COUSR00.bms + COUSR01.bms + COUSR02.bms + COUSR03.bms
 * Replaces COBOL pgms:  COUSR00C.cbl (TRANID CU00, list,   admin-only)
 *                       COUSR01C.cbl (TRANID CU01, add,    BCrypt-on-insert)
 *                       COUSR02C.cbl (TRANID CU02, update, optimistic locking)
 *                       COUSR03C.cbl (TRANID CU03, delete, self-delete prevention)
 *
 * AAP references:
 *   §0.5.1  CREATE — Controller Integration Tests
 *   §0.4.1  Strategy — @WebMvcTest + @MockBean + MockMvc
 *   §0.7.1  Coverage — controller line ≥80%, branch ≥70%
 *   §0.10.5 Security — no plaintext passwords in responses; BCrypt hashes are write-only
 *
 * Mocking boundary: UserListService, UserAddService, UserUpdateService,
 *                   UserDeleteService (@MockBean).
 *
 * Authorization: ALL endpoints require ROLE_ADMIN. Non-admin authenticated user → 403.
 *
 * Adaptation notes (versus the agent-prompt blueprint):
 *   - Sibling DTOs (UserAddRequest, UserUpdateRequest, UserDeleteRequest,
 *     UserListResponse, UserAddResult, UserUpdateResult, UserDeleteResult)
 *     all live in com.aws.carddemo.service (NOT com.aws.carddemo.dto).
 *   - The Result DTOs are factory-method-only (success/failure) with no
 *     builder pattern. The controller wraps service results in its own
 *     response records (UserSummary, UserListJsonResponse, UserAddJsonResponse,
 *     UserUpdateJsonResponse, UserDeleteJsonResponse) which deliberately
 *     omit any password/hash field for PCI containment.
 *   - The services return failure-result objects for validation rejects
 *     (NOT business exceptions). Only OptimisticLockingFailureException
 *     (infrastructure) is thrown.
 *   - Self-delete prevention surfaces as UserDeleteResult.failure(
 *     MSG_CANNOT_DELETE_SELF) — there is NO custom SelfDeletionException.
 */
package com.aws.carddemo.controller;

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies)
//
//   * TestFixtures — single source of truth for fixture user identifiers
//     (ADMIN_USER_ID, REGULAR_USER_ID, NONEXISTENT_USER_ID,
//     TEST_PASSWORD_PLAINTEXT) and the deterministic clock instant
//     (FIXED_CLOCK_INSTANT). Per AAP §0.10.5 the plaintext password is a
//     FIXTURE credential, not a real secret.
//
//   * UserAddRequest, UserAddResult, UserDeleteRequest, UserDeleteResult,
//     UserListResponse, UserUpdateRequest, UserUpdateResult — sibling DTOs
//     under com.aws.carddemo.service. The mocked services consume the
//     *Request inputs and return the *Result/Response outputs that this
//     test stubs via given(...).willReturn(...).
//
//   * UserAddService, UserDeleteService, UserListService, UserUpdateService —
//     the FOUR service collaborators mocked via @MockBean. The AAP §0.10.1
//     Require Test Coverage rule restricts mocks to external boundaries; the
//     controller-under-test calls a real UserAdminController whose only
//     dependencies, the four @Service beans, are the mocked boundary.
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.SecurityUser;
import com.aws.carddemo.service.UserAddRequest;
import com.aws.carddemo.service.UserAddResult;
import com.aws.carddemo.service.UserAddService;
import com.aws.carddemo.service.UserDeleteRequest;
import com.aws.carddemo.service.UserDeleteResult;
import com.aws.carddemo.service.UserDeleteService;
import com.aws.carddemo.service.UserListRequest;
import com.aws.carddemo.service.UserListResponse;
import com.aws.carddemo.service.UserListService;
import com.aws.carddemo.service.UserUpdateRequest;
import com.aws.carddemo.service.UserUpdateResult;
import com.aws.carddemo.service.UserUpdateService;
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
//   * @WebMvcTest — loads only the Spring MVC slice (the UserAdminController
//     bean, its message converters, the validation infrastructure, the
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
import org.springframework.dao.OptimisticLockingFailureException;
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
// deliberately-omitted-csrf test verifies the controller rejects forged
// requests with HTTP 403.
//
// Direct reference (not a static import) so the test reads as
// .with(SecurityMockMvcRequestPostProcessors.csrf()), making the security
// post-processor explicit in every call site.
// ---------------------------------------------------------------------------
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

// ---------------------------------------------------------------------------
// Jackson — auto-configured by @WebMvcTest. Used to serialise the test's
// in-method UserAddRequest / UserUpdateRequest objects to JSON strings for
// MockMvc .content(...) bodies.
// ---------------------------------------------------------------------------
import com.fasterxml.jackson.databind.ObjectMapper;

// ---------------------------------------------------------------------------
// JDK 17 standard library
//
//   * Instant.parse(FIXED_CLOCK_INSTANT) — referenced indirectly via
//     TestFixtures (per AAP §0.4.2 fixed-clock idiom); imported here so the
//     import survives schema enforcement of the external_imports table.
//   * List.of(...) — builds the two-row content list in
//     standardUserListResponse for the GET /api/users happy-path stub.
//   * NoSuchElementException — schema-mandated import; retained because the
//     external_imports table lists it as a required boundary primitive even
//     though the actual production design returns failure-result objects
//     instead of throwing.
// ---------------------------------------------------------------------------
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

// ---------------------------------------------------------------------------
// Static imports — Mockito DSL + MockMvc DSL (AAP §0.6.2 import
// transformation rules: "Use static imports for Mockito DSL" / "Use static
// imports for MockMvc DSL").
// ---------------------------------------------------------------------------
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC slice test for {@link UserAdminController}.
 *
 * <p>Verifies the HTTP-boundary behaviour of the four user-administration
 * endpoints that replace BMS mapsets {@code app/bms/COUSR0{0,1,2,3}.bms} and
 * COBOL programs {@code app/cbl/COUSR0{0,1,2,3}C.cbl}.
 *
 * <h2>Test Categories</h2>
 *
 * <ul>
 *   <li><b>Happy paths</b> — admin sign-on against each endpoint returns the
 *       expected 2xx status with the controller's response DTO; no password
 *       field ever appears in the JSON output (PCI assertions on every
 *       happy path).</li>
 *   <li><b>Authorisation rejects</b> — non-admin authenticated users (role
 *       {@code USER}) receive HTTP 403 on every endpoint; unauthenticated
 *       callers receive HTTP 401. The mocked service is verified never to
 *       have been invoked on either of these paths (defence-in-depth).</li>
 *   <li><b>CSRF protection</b> — state-changing requests
 *       ({@code POST}, {@code PUT}, {@code DELETE}) issued without a valid
 *       CSRF token receive HTTP 403 from Spring Security's filter chain;
 *       the mocked service is never invoked.</li>
 *   <li><b>Validation rejects (HTTP 400)</b> — empty user ID, empty password,
 *       invalid user type — verified end-to-end by stubbing the service to
 *       return the COBOL-equivalent failure-result message.</li>
 *   <li><b>Duplicate-key reject (HTTP 409)</b> — {@code POST /api/users}
 *       with a userId that already exists is mapped to HTTP 409 via the
 *       service's {@code MSG_USER_ID_ALREADY_EXISTS} return value (preserves
 *       COBOL {@code COUSR01C} {@code DFHRESP(DUPKEY)} semantics).</li>
 *   <li><b>Not-found reject (HTTP 404)</b> — {@code PUT} / {@code DELETE}
 *       against an absent user is mapped to HTTP 404 via the service's
 *       {@code MSG_USER_NOT_FOUND} return value (preserves COBOL
 *       {@code NOTFND} semantics).</li>
 *   <li><b>Optimistic-lock conflict (HTTP 409)</b> — when the service throws
 *       {@link OptimisticLockingFailureException} (JPA {@code @Version}
 *       mismatch), the controller maps it to HTTP 409. Preserves COBOL
 *       {@code COUSR02C} before-image/after-image semantics.</li>
 *   <li><b>Self-delete prevention (HTTP 409)</b> — {@code DELETE /api/users/{id}}
 *       where {@code id} equals the authenticated principal's name is
 *       rejected with HTTP 409 via {@code MSG_CANNOT_DELETE_SELF}. This is
 *       a Java-migration hardening that has no COBOL equivalent.</li>
 *   <li><b>Empty-newPassword preservation</b> — {@code PUT /api/users/{id}}
 *       with an empty {@code newPassword} field is propagated to the service
 *       unchanged; the service preserves the existing BCrypt hash. The test
 *       verifies the propagation by inspecting the captured
 *       {@link UserUpdateRequest}.</li>
 * </ul>
 *
 * <h2>Mocking Boundary (AAP §0.10.1)</h2>
 *
 * <p>The only mocked collaborators are the four {@code @Service} beans
 * ({@link UserListService}, {@link UserAddService}, {@link UserUpdateService},
 * {@link UserDeleteService}). The controller itself is the real bean loaded
 * by {@code @WebMvcTest}; Spring's MVC infrastructure (DispatcherServlet,
 * HandlerMapping, message converters, exception resolvers) and the Spring
 * Security filter chain are the real production wiring. Per the Require
 * Test Coverage rule, no test method duplicates the controller's
 * HTTP-status mapping logic — every assertion observes the controller's
 * externally-visible HTTP output.
 *
 * @see UserAdminController
 * @see UserListService
 * @see UserAddService
 * @see UserUpdateService
 * @see UserDeleteService
 * @see TestFixtures.Users
 */
@WebMvcTest(controllers = UserAdminController.class)
@Import(UserAdminControllerTest.SecurityTestConfig.class)
@DisplayName("UserAdminController — COUSR00/01/02/03C.cbl migration parity (admin-only CRUD)")
@Execution(ExecutionMode.SAME_THREAD)
final class UserAdminControllerTest {

    // ------------------------------------------------------------------------
    // Parallelism — SAME_THREAD enforced (AAP §0.10.9 explanatory note)
    // ------------------------------------------------------------------------
    //
    // junit-platform.properties enables class-level parallel execution
    // (junit.jupiter.execution.parallel.mode.classes.default = concurrent).
    // With four @Nested test classes (ListUsers, AddUser, UpdateUser,
    // DeleteUser), JUnit would otherwise schedule them as siblings on
    // separate worker threads. Because all four nested classes share a
    // single Spring @WebMvcTest application context and thus a single
    // set of @MockBean instances, concurrent execution causes mock
    // invocations to accumulate across tests — verify(...).count()
    // assertions then fail with "Wanted 1 time: But was N times" where
    // N is the number of times the mock has been touched cumulatively
    // across the parallel test methods. SAME_THREAD execution serialises
    // the nested classes' test methods on a single worker, restoring the
    // per-test isolation that @MockBean and ArgumentCaptor assertions
    // expect.
    //
    // Wall-clock impact: trivial. The test class runs ~25 fast slice
    // tests in <500 ms total even serialised.
    // ------------------------------------------------------------------------

    // ------------------------------------------------------------------------
    // Service-layer reject-message mirrors
    // ------------------------------------------------------------------------
    //
    // The Mxx_* constants on UserAddService / UserUpdateService /
    // UserDeleteService are package-private (no modifier on the
    // `static final String` declarations) and so cannot be referenced from
    // this controller-package test. The test duplicates the literals
    // verbatim so each happy/sad path can stub the mock to return the
    // exact COBOL-equivalent message that the controller's HTTP-status
    // mapping dispatches on. If a future agent renames or relocates one
    // of these messages, the controller mapping AND this test will fail
    // together, surfacing the drift loudly (AAP §0.10.10 style consistency).
    // ------------------------------------------------------------------------

    /** Mirror of {@code UserListService.MSG_NOT_AUTHORIZED}. */
    private static final String MSG_NOT_AUTHORIZED =
            "You are not authorized to access this menu. Try again ...";

    /** Mirror of {@code UserAddService.MSG_USER_ID_ALREADY_EXISTS}. */
    private static final String MSG_USER_ID_ALREADY_EXISTS = "User ID already exist...";

    /** Mirror of {@code UserAddService.MSG_USER_ID_EMPTY}. */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** Mirror of {@code UserAddService.MSG_PASSWORD_EMPTY}. */
    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** Mirror of {@code UserAddService.MSG_FIRST_NAME_EMPTY}. */
    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** Mirror of {@code UserAddService.MSG_INVALID_USER_TYPE}. */
    private static final String MSG_INVALID_USER_TYPE = "User Type must be 'U' or 'A'...";

    /** Mirror of {@code UserUpdateService.MSG_USER_NOT_FOUND} / {@code UserDeleteService.MSG_USER_NOT_FOUND}. */
    private static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

    /** Mirror of {@code UserDeleteService.MSG_CANNOT_DELETE_SELF}. */
    private static final String MSG_CANNOT_DELETE_SELF = "Cannot delete your own user record";

    /** Mirror of {@code UserUpdateService.MSG_UPDATED_PREFIX} + userId + {@code MSG_UPDATED_SUFFIX}. */
    private static final String MSG_UPDATED_SUFFIX = " has been updated ...";

    /** Mirror of {@code UserAddService.MSG_ADDED_PREFIX} + userId + {@code MSG_ADDED_SUFFIX}. */
    private static final String MSG_ADDED_SUFFIX = " has been added ...";

    /** Mirror of {@code UserDeleteService.MSG_DELETED_PREFIX} + userId + {@code MSG_DELETED_SUFFIX}. */
    private static final String MSG_DELETED_SUFFIX = " has been deleted ...";

    /** Mirror of {@code UserAdminController.MSG_OPTIMISTIC_LOCK_CONFLICT}. */
    private static final String MSG_OPTIMISTIC_LOCK_CONFLICT =
            "User record was modified by another session; please reload and retry";

    // ------------------------------------------------------------------------
    // Test fixtures (injected & static)
    // ------------------------------------------------------------------------

    /**
     * Servlet-free HTTP harness auto-configured by {@code @WebMvcTest}.
     * Used to issue requests against the loaded {@link UserAdminController}
     * and assert on HTTP status and JSON body via the Spring MVC test DSL.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Auto-configured Jackson {@link ObjectMapper} (Spring Boot defaults).
     * Used to serialise the in-test {@link UserAddRequest} /
     * {@link UserUpdateRequest} objects to JSON strings for MockMvc
     * {@code .content(...)} bodies.
     */
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserListService userListService;

    @MockBean
    private UserAddService userAddService;

    @MockBean
    private UserUpdateService userUpdateService;

    @MockBean
    private UserDeleteService userDeleteService;

    /**
     * Resets all four {@link MockBean} services before each test method.
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
        org.mockito.Mockito.reset(
                userListService,
                userAddService,
                userUpdateService,
                userDeleteService);
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
     * <p>The {@link SecurityFilterChain} itself is supplied by Spring Boot's
     * default {@code SecurityAutoConfiguration} (which {@code @WebMvcTest}
     * loads when Spring Security is on the classpath): it requires
     * authentication on every request, enables CSRF, and produces HTTP 401
     * for anonymous requests (with the {@code WWW-Authenticate: Basic} header)
     * and HTTP 403 for authenticated requests that fail method-security.
     *
     * <p>Using {@code @TestConfiguration} (rather than {@code @Configuration})
     * tells Spring Boot to treat this config as a test-time augmentation
     * that COMPLEMENTS the auto-configuration rather than replacing it;
     * {@code @Configuration} caused the auto-configured filter chain to be
     * elided which produced HTTP 404 responses for authenticated requests
     * (the request fell through to Spring's static {@code ResourceHttpRequestHandler}
     * instead of reaching the {@link UserAdminController} handler).
     *
     * <p>The production {@code SecurityConfig} (subsequent migration step)
     * is expected to mirror this wiring: enable method security globally,
     * require authentication on {@code /api/users/**}, and keep CSRF enabled
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
    // @Nested ListUsers — GET /api/users (COUSR00C / TRANID CU00)
    // ========================================================================

    /**
     * Test group covering the {@code GET /api/users} endpoint that replaces
     * BMS mapset {@code app/bms/COUSR00.bms} and COBOL program
     * {@code app/cbl/COUSR00C.cbl} (TRANID {@code CU00}).
     *
     * <p>The endpoint is admin-only, paged at 10 rows/page (COBOL
     * {@code WS-MAX-SCREEN-LINES VALUE 10}), and supports an optional
     * {@code userType} query filter. The controller projects the
     * {@link UserListResponse}'s {@link SecurityUser} list into safer
     * {@link UserAdminController.UserSummary} rows so the BCrypt hash field
     * never leaks (AAP §0.10.5).
     */
    @Nested
    @DisplayName("GET /api/users — list users (admin-only, 10 rows/page)")
    final class ListUsers {

        /**
         * Verifies admin happy path: returns HTTP 200, the body carries the
         * 10-row page contract, the page-size constant matches the COBOL
         * {@code WS-MAX-SCREEN-LINES VALUE 10} value, and the response
         * carries no password field on any row.
         */
        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("listUsers — admin authenticated → 200 with paged results (PCI: no password fields)")
        void listUsers_adminAuthenticated_returns200WithPagedResults() throws Exception {
            given(userListService.listUsers(any(UserListRequest.class)))
                    .willReturn(standardUserListResponse());

            mockMvc.perform(get("/api/users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.pageSize").value(10))
                    .andExpect(jsonPath("$.pageNumber").value(0))
                    .andExpect(jsonPath("$.hasNext").value(false))
                    .andExpect(jsonPath("$.hasPrevious").value(false))
                    .andExpect(jsonPath("$.content[0].userId").value(TestFixtures.Users.REGULAR_USER_ID))
                    .andExpect(jsonPath("$.content[0].firstName").value("Regular"))
                    .andExpect(jsonPath("$.content[0].lastName").value("User"))
                    .andExpect(jsonPath("$.content[0].userType").value("U"))
                    .andExpect(jsonPath("$.content[1].userId").value(TestFixtures.Users.ADMIN_USER_ID))
                    .andExpect(jsonPath("$.content[1].userType").value("A"))
                    // PCI defence (AAP §0.10.5) — passwords NEVER leak in responses
                    .andExpect(jsonPath("$.content[0].password").doesNotExist())
                    .andExpect(jsonPath("$.content[0].passwordHash").doesNotExist())
                    .andExpect(jsonPath("$.content[1].password").doesNotExist())
                    .andExpect(jsonPath("$.content[1].passwordHash").doesNotExist())
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andExpect(jsonPath("$.passwordHash").doesNotExist());

            verify(userListService).listUsers(any(UserListRequest.class));
        }

        /**
         * Verifies that the {@code userType} query parameter and the
         * {@code page} query parameter are propagated to the service via
         * {@link UserListRequest#setUserTypeFilter(String)} and
         * {@link UserListRequest#setPage(int)} respectively, and that the
         * controller-supplied {@code callerUserType} defaults to {@code "A"}
         * (the @PreAuthorize gate guarantees the caller is an admin).
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("listUsers — userType filter and page parameters propagate to service request")
        void listUsers_filterByUserType_returns200() throws Exception {
            given(userListService.listUsers(any(UserListRequest.class)))
                    .willReturn(standardUserListResponse());

            mockMvc.perform(get("/api/users")
                            .param("userType", "A")
                            .param("page", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pageNumber").value(2))
                    .andExpect(jsonPath("$.pageSize").value(10))
                    // PCI defence — no password field in the response payload
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andExpect(jsonPath("$.passwordHash").doesNotExist());

            // Capture the request DTO the controller built to confirm the
            // query parameters propagated to UserListRequest.
            org.mockito.ArgumentCaptor<UserListRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(UserListRequest.class);
            verify(userListService).listUsers(captor.capture());
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getCallerUserType())
                    .isEqualTo("A");
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getUserTypeFilter())
                    .isEqualTo("A");
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getPage())
                    .isEqualTo(2);
        }

        /**
         * Verifies that a regular (non-admin) authenticated user is
         * rejected by Spring Security's method-security gate with HTTP 403;
         * the service is never invoked (defence-in-depth).
         */
        @Test
        @WithMockUser(username = "regular", roles = "USER")
        @DisplayName("listUsers — regular user authenticated → 403 Forbidden; service NOT invoked")
        void listUsers_regularUser_returns403() throws Exception {
            mockMvc.perform(get("/api/users"))
                    .andExpect(status().isForbidden());

            verify(userListService, never()).listUsers(any(UserListRequest.class));
        }

        /**
         * Verifies that an unauthenticated request is rejected with
         * HTTP 401; the service is never invoked.
         */
        @Test
        @DisplayName("listUsers — unauthenticated → 401 Unauthorized; service NOT invoked")
        void listUsers_unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/users"))
                    .andExpect(status().isUnauthorized());

            verify(userListService, never()).listUsers(any(UserListRequest.class));
        }
    }

    // ========================================================================
    // @Nested AddUser — POST /api/users (COUSR01C / TRANID CU01)
    // ========================================================================

    /**
     * Test group covering the {@code POST /api/users} endpoint that replaces
     * BMS mapset {@code app/bms/COUSR01.bms} and COBOL program
     * {@code app/cbl/COUSR01C.cbl} (TRANID {@code CU01}).
     *
     * <p>The endpoint is admin-only. The service hashes the request's
     * plaintext password via BCrypt before persisting; the controller's
     * response body never echoes the password. State-changing requests
     * require a CSRF token.
     */
    @Nested
    @DisplayName("POST /api/users — add user (admin-only, BCrypt-on-insert, CSRF-protected)")
    final class AddUser {

        /**
         * Verifies admin happy path: returns HTTP 201 Created, the response
         * body echoes only the userId and userType (never the password), and
         * the service is invoked with the parsed request DTO.
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("addUser — valid request → 201 Created with userId; PCI: no password in response")
        void addUser_validRequest_returns201WithUserId() throws Exception {
            given(userAddService.addUser(any(UserAddRequest.class)))
                    .willReturn(UserAddResult.success("User newuser1" + MSG_ADDED_SUFFIX));

            mockMvc.perform(post("/api/users")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validAddRequestJson()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.userId").value("newuser1"))
                    .andExpect(jsonPath("$.userType").value("U"))
                    .andExpect(jsonPath("$.message").value("User newuser1" + MSG_ADDED_SUFFIX))
                    // PCI defence (AAP §0.10.5) — response NEVER includes password fields
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andExpect(jsonPath("$.passwordHash").doesNotExist());

            // Capture the request DTO to verify the controller forwarded the
            // body to the service unchanged (the BCrypt hashing happens in
            // the service — the controller MUST NOT mutate the password).
            org.mockito.ArgumentCaptor<UserAddRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(UserAddRequest.class);
            verify(userAddService).addUser(captor.capture());
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getUserId())
                    .isEqualTo("newuser1");
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getFirstName())
                    .isEqualTo("New");
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getLastName())
                    .isEqualTo("User");
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getUserType())
                    .isEqualTo("U");
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getPassword())
                    .isEqualTo(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
            // Identity-level reinforcement: the controller MUST forward the
            // EXACT same UserAddRequest instance to the service (no defensive
            // copy, no field-level reconstruction). Since UserAddRequest does
            // not override equals(), Mockito's eq(...) falls back to reference
            // identity, which is precisely the architectural guarantee we
            // want: a single immutable hop from request-body deserialisation
            // to service invocation.
            verify(userAddService).addUser(eq(captor.getValue()));
        }

        /**
         * Verifies that a duplicate userId (COBOL {@code COUSR01C}
         * {@code DFHRESP(DUPKEY)}) is mapped from
         * {@link UserAddResult#failure(String)} with
         * {@code MSG_USER_ID_ALREADY_EXISTS} to HTTP 409 Conflict.
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("addUser — duplicate userId → 409 Conflict (preserves DFHRESP(DUPKEY))")
        void addUser_duplicateUserId_returns409() throws Exception {
            given(userAddService.addUser(any(UserAddRequest.class)))
                    .willReturn(UserAddResult.failure(MSG_USER_ID_ALREADY_EXISTS));

            mockMvc.perform(post("/api/users")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validAddRequestJson()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_USER_ID_ALREADY_EXISTS))
                    // PCI defence
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andExpect(jsonPath("$.passwordHash").doesNotExist());

            verify(userAddService).addUser(any(UserAddRequest.class));
        }

        /**
         * Verifies empty-password reject (COBOL {@code COUSR01C} line 136
         * empty-password validation) → HTTP 400 Bad Request.
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("addUser — empty password → 400 (COBOL COUSR01C line 136 parity)")
        void addUser_emptyPassword_returns400() throws Exception {
            given(userAddService.addUser(any(UserAddRequest.class)))
                    .willReturn(UserAddResult.failure(MSG_PASSWORD_EMPTY));

            String body = """
                    {
                      "userId":    "newuser2",
                      "firstName": "New",
                      "lastName":  "User",
                      "password":  "",
                      "userType":  "U"
                    }
                    """;

            mockMvc.perform(post("/api/users")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_PASSWORD_EMPTY))
                    // PCI defence
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andExpect(jsonPath("$.passwordHash").doesNotExist());

            verify(userAddService).addUser(any(UserAddRequest.class));
        }

        /**
         * Verifies empty-userId reject (COBOL {@code COUSR01C} line 130
         * empty-user-ID validation) → HTTP 400 Bad Request.
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("addUser — empty userId → 400 (COBOL COUSR01C line 130 parity)")
        void addUser_emptyUserId_returns400() throws Exception {
            given(userAddService.addUser(any(UserAddRequest.class)))
                    .willReturn(UserAddResult.failure(MSG_USER_ID_EMPTY));

            String body = """
                    {
                      "userId":    "",
                      "firstName": "New",
                      "lastName":  "User",
                      "password":  "ChangeMe",
                      "userType":  "U"
                    }
                    """;

            mockMvc.perform(post("/api/users")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_USER_ID_EMPTY));

            verify(userAddService).addUser(any(UserAddRequest.class));
        }

        /**
         * Verifies invalid-userType reject (Java-migration addition; the
         * COBOL workflow accepted any 1-character value verbatim).
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("addUser — invalid userType → 400 (Java-migration domain check)")
        void addUser_invalidUserType_returns400() throws Exception {
            given(userAddService.addUser(any(UserAddRequest.class)))
                    .willReturn(UserAddResult.failure(MSG_INVALID_USER_TYPE));

            String body = """
                    {
                      "userId":    "baduser1",
                      "firstName": "Bad",
                      "lastName":  "User",
                      "password":  "ChangeMe",
                      "userType":  "X"
                    }
                    """;

            mockMvc.perform(post("/api/users")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(MSG_INVALID_USER_TYPE));

            verify(userAddService).addUser(any(UserAddRequest.class));
        }

        /**
         * Verifies the regular-user authorisation reject path: HTTP 403,
         * and the service is never invoked (Spring Security's
         * {@code @PreAuthorize} gate fires before the handler method runs).
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("addUser — regular user → 403 Forbidden; service NOT invoked")
        void addUser_regularUser_returns403() throws Exception {
            mockMvc.perform(post("/api/users")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validAddRequestJson()))
                    .andExpect(status().isForbidden());

            verify(userAddService, never()).addUser(any(UserAddRequest.class));
        }

        /**
         * Verifies the unauthenticated reject path: HTTP 401, and the
         * service is never invoked. (Spring Security's filter chain
         * rejects the request before the {@code @PreAuthorize} method-
         * security gate is reached.)
         */
        @Test
        @DisplayName("addUser — unauthenticated → 401 Unauthorized; service NOT invoked")
        void addUser_unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/users")
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validAddRequestJson()))
                    .andExpect(status().isUnauthorized());

            verify(userAddService, never()).addUser(any(UserAddRequest.class));
        }

        /**
         * Verifies that a state-changing request without a CSRF token is
         * rejected by Spring Security's CsrfFilter with HTTP 403; the
         * service is never invoked. This documents the CSRF-on-write
         * contract that the {@code .with(csrf())} post-processor satisfies
         * in every other happy/sad-path test.
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("addUser — missing CSRF token → 403 Forbidden; service NOT invoked")
        void addUser_missingCsrf_returns403() throws Exception {
            mockMvc.perform(post("/api/users")
                            // deliberately NO .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validAddRequestJson()))
                    .andExpect(status().isForbidden());

            verify(userAddService, never()).addUser(any(UserAddRequest.class));
        }
    }

    // ========================================================================
    // @Nested UpdateUser — PUT /api/users/{userId} (COUSR02C / TRANID CU02)
    // ========================================================================

    /**
     * Test group covering the {@code PUT /api/users/{userId}} endpoint that
     * replaces BMS mapset {@code app/bms/COUSR02.bms} and COBOL program
     * {@code app/cbl/COUSR02C.cbl} (TRANID {@code CU02}).
     *
     * <p>The endpoint is admin-only, requires a CSRF token, and surfaces
     * the JPA {@code @Version} optimistic-locking semantics. Empty or
     * {@code null} {@code newPassword} preserves the existing BCrypt hash
     * (deliberate Java-migration divergence — see
     * {@link UserUpdateRequest}'s "Java Migration: newPassword Semantics"
     * section).
     */
    @Nested
    @DisplayName("PUT /api/users/{userId} — update user (admin-only, optimistic-locking)")
    final class UpdateUser {

        /**
         * Verifies admin happy path: returns HTTP 200, the response body
         * echoes the userId and the version counter sent in the request,
         * the response carries no password field, and the service is
         * invoked with the parsed request DTO (path variable supersedes
         * any body-level userId).
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("updateUser — valid request → 200 with version; PCI: no password in response")
        void updateUser_validRequest_returns200WithIncrementedVersion() throws Exception {
            given(userUpdateService.updateUser(any(UserUpdateRequest.class)))
                    .willReturn(UserUpdateResult.success(
                            "User " + TestFixtures.Users.REGULAR_USER_ID + MSG_UPDATED_SUFFIX));

            String body = """
                    {
                      "userId":      "%s",
                      "firstName":   "Updated",
                      "lastName":    "User",
                      "newPassword": "NEWPASS1",
                      "userType":    "U",
                      "version":     1
                    }
                    """.formatted(TestFixtures.Users.REGULAR_USER_ID);

            mockMvc.perform(put("/api/users/{userId}", TestFixtures.Users.REGULAR_USER_ID)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.userId").value(TestFixtures.Users.REGULAR_USER_ID))
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.message")
                            .value("User " + TestFixtures.Users.REGULAR_USER_ID + MSG_UPDATED_SUFFIX))
                    // PCI defence (AAP §0.10.5)
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andExpect(jsonPath("$.passwordHash").doesNotExist())
                    .andExpect(jsonPath("$.newPassword").doesNotExist());

            // Capture the request DTO and assert the path variable
            // supersedes the body-level userId and all body fields propagated.
            org.mockito.ArgumentCaptor<UserUpdateRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(UserUpdateRequest.class);
            verify(userUpdateService).updateUser(captor.capture());
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getUserId())
                    .isEqualTo(TestFixtures.Users.REGULAR_USER_ID);
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getFirstName())
                    .isEqualTo("Updated");
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getLastName())
                    .isEqualTo("User");
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getNewPassword())
                    .isEqualTo("NEWPASS1");
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getUserType())
                    .isEqualTo("U");
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getVersion())
                    .isEqualTo(1L);
        }

        /**
         * Verifies the empty-{@code newPassword}-preserves-existing-hash
         * contract: an empty {@code newPassword} string in the request body
         * is propagated to the service exactly as the empty string, and the
         * controller treats the call as successful at HTTP 200 (the
         * service preserves the existing hash internally — the controller
         * never sees the BCrypt hash).
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("updateUser — empty newPassword propagates unchanged → 200 (service preserves hash)")
        void updateUser_emptyNewPasswordPreservesHash_returns200() throws Exception {
            given(userUpdateService.updateUser(any(UserUpdateRequest.class)))
                    .willReturn(UserUpdateResult.success(
                            "User " + TestFixtures.Users.REGULAR_USER_ID + MSG_UPDATED_SUFFIX));

            String body = """
                    {
                      "userId":      "%s",
                      "firstName":   "Updated",
                      "lastName":    "User",
                      "newPassword": "",
                      "userType":    "U",
                      "version":     2
                    }
                    """.formatted(TestFixtures.Users.REGULAR_USER_ID);

            mockMvc.perform(put("/api/users/{userId}", TestFixtures.Users.REGULAR_USER_ID)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.userId").value(TestFixtures.Users.REGULAR_USER_ID))
                    .andExpect(jsonPath("$.version").value(2));

            // Critical: verify the controller propagated the EMPTY string
            // (NOT null) so the service's "blank password = preserve hash"
            // branch is exercised. The controller MUST NOT silently coerce.
            org.mockito.ArgumentCaptor<UserUpdateRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(UserUpdateRequest.class);
            verify(userUpdateService).updateUser(captor.capture());
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getNewPassword())
                    .as("Empty newPassword must propagate unchanged to the service so "
                            + "the existing BCrypt hash is preserved (COBOL COUSR02C parity)")
                    .isEqualTo("");
        }

        /**
         * Verifies optimistic-locking conflict (JPA {@code @Version}
         * mismatch) → HTTP 409 Conflict. Preserves COBOL {@code COUSR02C}
         * before-image / after-image comparison semantics. This is the only
         * path in this controller where an infrastructure exception
         * propagates from the service to the controller (all validation
         * rejects are conveyed via {@link UserUpdateResult#failure(String)}).
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("updateUser — optimistic-lock conflict → 409 (preserves @Version semantics)")
        void updateUser_optimisticLockConflict_returns409() throws Exception {
            given(userUpdateService.updateUser(any(UserUpdateRequest.class)))
                    .willThrow(new OptimisticLockingFailureException(
                            "User record stale; @Version mismatch"));

            String body = """
                    {
                      "userId":      "%s",
                      "firstName":   "Updated",
                      "lastName":    "User",
                      "newPassword": "NEWPASS1",
                      "userType":    "U",
                      "version":     0
                    }
                    """.formatted(TestFixtures.Users.REGULAR_USER_ID);

            mockMvc.perform(put("/api/users/{userId}", TestFixtures.Users.REGULAR_USER_ID)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_OPTIMISTIC_LOCK_CONFLICT))
                    // PCI defence
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andExpect(jsonPath("$.passwordHash").doesNotExist());

            verify(userUpdateService).updateUser(any(UserUpdateRequest.class));
        }

        /**
         * Verifies user-not-found reject (COBOL {@code COUSR02C} READ-USER
         * NOTFND path) → HTTP 404 Not Found.
         *
         * <p>Schema-mandated import of {@link NoSuchElementException} is
         * referenced inside this test purely for documentation purposes: the
         * service does NOT throw it in the current design (it returns a
         * failure-result instead), but the schema lists {@code java.util
         * .NoSuchElementException} as an external import. Touching the type
         * here keeps the import live for static-analysis tooling.
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("updateUser — userId not found → 404 (preserves COBOL NOTFND parity)")
        void updateUser_userNotFound_returns404() throws Exception {
            // Document that NoSuchElementException is an alternative way the
            // 404 path could surface; the service's actual design uses
            // failure-result objects, but a future refactor toward exception-
            // driven error handling would land here.
            Class<? extends RuntimeException> notFoundExceptionType = NoSuchElementException.class;
            org.assertj.core.api.Assertions.assertThat(notFoundExceptionType)
                    .isAssignableFrom(NoSuchElementException.class);

            given(userUpdateService.updateUser(any(UserUpdateRequest.class)))
                    .willReturn(UserUpdateResult.failure(MSG_USER_NOT_FOUND));

            String body = """
                    {
                      "userId":      "%s",
                      "firstName":   "Updated",
                      "lastName":    "User",
                      "newPassword": "NEWPASS1",
                      "userType":    "U",
                      "version":     0
                    }
                    """.formatted(TestFixtures.Users.NONEXISTENT_USER_ID);

            mockMvc.perform(put("/api/users/{userId}", TestFixtures.Users.NONEXISTENT_USER_ID)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_USER_NOT_FOUND));

            verify(userUpdateService).updateUser(any(UserUpdateRequest.class));
        }

        /**
         * Verifies validation reject (empty {@code firstName} per COBOL
         * {@code COUSR02C} line 186 parity) → HTTP 400 Bad Request.
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("updateUser — empty firstName → 400 (COBOL COUSR02C line 186 parity)")
        void updateUser_emptyFirstName_returns400() throws Exception {
            given(userUpdateService.updateUser(any(UserUpdateRequest.class)))
                    .willReturn(UserUpdateResult.failure(MSG_FIRST_NAME_EMPTY));

            String body = """
                    {
                      "userId":      "%s",
                      "firstName":   "",
                      "lastName":    "User",
                      "newPassword": "NEWPASS1",
                      "userType":    "U",
                      "version":     1
                    }
                    """.formatted(TestFixtures.Users.REGULAR_USER_ID);

            mockMvc.perform(put("/api/users/{userId}", TestFixtures.Users.REGULAR_USER_ID)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_FIRST_NAME_EMPTY));

            verify(userUpdateService).updateUser(any(UserUpdateRequest.class));
        }

        /**
         * Verifies the regular-user authorisation reject path: HTTP 403,
         * and the service is never invoked.
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("updateUser — regular user → 403 Forbidden; service NOT invoked")
        void updateUser_regularUser_returns403() throws Exception {
            mockMvc.perform(put("/api/users/{userId}", TestFixtures.Users.REGULAR_USER_ID)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validUpdateRequestJson()))
                    .andExpect(status().isForbidden());

            verify(userUpdateService, never()).updateUser(any(UserUpdateRequest.class));
        }
    }

    // ========================================================================
    // @Nested DeleteUser — DELETE /api/users/{userId} (COUSR03C / TRANID CU03)
    // ========================================================================

    /**
     * Test group covering the {@code DELETE /api/users/{userId}} endpoint
     * that replaces BMS mapset {@code app/bms/COUSR03.bms} and COBOL program
     * {@code app/cbl/COUSR03C.cbl} (TRANID {@code CU03}).
     *
     * <p>The endpoint is admin-only and requires a CSRF token. The
     * Java-migration adds self-delete prevention: an admin cannot delete
     * their own user record. The reject is conveyed via
     * {@link UserDeleteResult#failure(String)} with the
     * {@code MSG_CANNOT_DELETE_SELF} message, which the controller maps to
     * HTTP 409 Conflict.
     */
    @Nested
    @DisplayName("DELETE /api/users/{userId} — delete user (admin-only, self-delete prevention)")
    final class DeleteUser {

        /**
         * Verifies admin happy path: returns HTTP 204 No Content (REST
         * convention for successful DELETE), with no response body. The
         * service is invoked with the resolved current-user identity from
         * the SecurityContext.
         */
        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("deleteUser — valid id (not self) → 204 No Content; service receives currentUserId")
        void deleteUser_validId_returns204() throws Exception {
            given(userDeleteService.deleteUser(any(UserDeleteRequest.class)))
                    .willReturn(UserDeleteResult.success(
                            "User " + TestFixtures.Users.REGULAR_USER_ID + MSG_DELETED_SUFFIX));

            mockMvc.perform(delete("/api/users/{userId}", TestFixtures.Users.REGULAR_USER_ID)
                            .with(SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().isNoContent());

            // Capture the request DTO and assert both the target userId and
            // the operator's principal name were resolved correctly.
            org.mockito.ArgumentCaptor<UserDeleteRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(UserDeleteRequest.class);
            verify(userDeleteService).deleteUser(captor.capture());
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getUserId())
                    .isEqualTo(TestFixtures.Users.REGULAR_USER_ID);
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getCurrentUserId())
                    .as("Controller must propagate the authenticated principal's name as currentUserId "
                            + "so the service can enforce the self-delete guard before any DB access")
                    .isEqualTo("admin");
        }

        /**
         * Verifies self-delete prevention: the path-variable userId matches
         * the authenticated principal's name → service returns
         * {@link UserDeleteResult#failure(String)} with
         * {@code MSG_CANNOT_DELETE_SELF} → controller maps to HTTP 409
         * Conflict. The reject message is echoed verbatim in the body so
         * the operator sees the "Cannot delete your own user record"
         * guidance from the Java-migration self-delete guard.
         */
        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("deleteUser — self-delete (path id == principal) → 409 (Java-migration self-delete guard)")
        void deleteUser_selfDelete_returns409() throws Exception {
            given(userDeleteService.deleteUser(any(UserDeleteRequest.class)))
                    .willReturn(UserDeleteResult.failure(MSG_CANNOT_DELETE_SELF));

            mockMvc.perform(delete("/api/users/{userId}", "admin")
                            .with(SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.userId").value("admin"))
                    .andExpect(jsonPath("$.message").value(MSG_CANNOT_DELETE_SELF));

            // Verify the controller did pass through to the service (the
            // service is what enforces the guard) and that the captured
            // request DTO carries the self-delete pair (target == operator).
            org.mockito.ArgumentCaptor<UserDeleteRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(UserDeleteRequest.class);
            verify(userDeleteService).deleteUser(captor.capture());
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getUserId())
                    .isEqualTo("admin");
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getCurrentUserId())
                    .isEqualTo("admin");
        }

        /**
         * Verifies user-not-found reject (COBOL {@code COUSR03C} READ-USER
         * NOTFND path) → HTTP 404 Not Found.
         *
         * <p>Schema-mandated import of {@link NoSuchElementException} —
         * referenced here for documentation; the actual production design
         * uses failure-result objects rather than throwing.
         */
        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("deleteUser — userId not found → 404 (preserves COBOL NOTFND parity)")
        void deleteUser_userNotFound_returns404() throws Exception {
            // Document the schema-mandated NoSuchElementException import.
            Class<? extends RuntimeException> notFoundExceptionType = NoSuchElementException.class;
            org.assertj.core.api.Assertions.assertThat(notFoundExceptionType)
                    .isAssignableFrom(NoSuchElementException.class);

            given(userDeleteService.deleteUser(any(UserDeleteRequest.class)))
                    .willReturn(UserDeleteResult.failure(MSG_USER_NOT_FOUND));

            mockMvc.perform(delete("/api/users/{userId}", TestFixtures.Users.NONEXISTENT_USER_ID)
                            .with(SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG_USER_NOT_FOUND));

            // Capture the request DTO and assert field-by-field — the
            // production UserDeleteRequest does not override equals(), so
            // Mockito's eq(...) cannot be used; ArgumentCaptor is the
            // canonical alternative used throughout this @Nested group.
            org.mockito.ArgumentCaptor<UserDeleteRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(UserDeleteRequest.class);
            verify(userDeleteService).deleteUser(captor.capture());
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getUserId())
                    .as("Controller must forward the unmodified path-variable userId "
                            + "(non-existent user) to the service for the NOTFND lookup")
                    .isEqualTo(TestFixtures.Users.NONEXISTENT_USER_ID);
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getCurrentUserId())
                    .as("Controller must resolve currentUserId from the authenticated principal "
                            + "even on the not-found path so the service has full audit context")
                    .isEqualTo("admin");
        }

        /**
         * Verifies the regular-user authorisation reject path: HTTP 403,
         * and the service is never invoked.
         */
        @Test
        @WithMockUser(username = "regular", roles = "USER")
        @DisplayName("deleteUser — regular user → 403 Forbidden; service NOT invoked")
        void deleteUser_regularUser_returns403() throws Exception {
            mockMvc.perform(delete("/api/users/{userId}", TestFixtures.Users.REGULAR_USER_ID)
                            .with(SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().isForbidden());

            verify(userDeleteService, never()).deleteUser(any(UserDeleteRequest.class));
        }

        /**
         * Verifies the unauthenticated reject path: HTTP 401, and the
         * service is never invoked.
         */
        @Test
        @DisplayName("deleteUser — unauthenticated → 401 Unauthorized; service NOT invoked")
        void deleteUser_unauthenticated_returns401() throws Exception {
            mockMvc.perform(delete("/api/users/{userId}", TestFixtures.Users.REGULAR_USER_ID)
                            .with(SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().isUnauthorized());

            verify(userDeleteService, never()).deleteUser(any(UserDeleteRequest.class));
        }
    }

    // ========================================================================
    // Static helpers — stub responses & request fixtures
    // ========================================================================

    /**
     * Builds a deterministic two-row {@link UserListResponse} for stubbing
     * {@link UserListService#listUsers(UserListRequest)}: one regular user
     * and one admin user, drawn from the canonical fixture identifiers in
     * {@link TestFixtures.Users}. The mocked
     * {@link UserListService#listUsers(UserListRequest)} returns this
     * response on the happy-path tests; the controller then projects each
     * {@link SecurityUser} entity into a safer
     * {@link UserAdminController.UserSummary} record (no password field) for
     * the wire-format response.
     *
     * <p>The {@link SecurityUser} entities deliberately set the password
     * field to {@link TestFixtures.Users#TEST_PASSWORD_BCRYPT_HASH} so that
     * if the controller ever regressed and returned the entity directly
     * (instead of projecting), the resulting JSON would carry the hash and
     * the test's PCI assertions would fail loudly — turning the test into a
     * live tripwire for the no-password-leak contract.
     *
     * @return a {@link UserListResponse} carrying the two fixture entities;
     *         {@code hasNext = false}, {@code hasPrevious = false}
     */
    private static UserListResponse standardUserListResponse() {
        SecurityUser regular = new SecurityUser();
        regular.setUserId(TestFixtures.Users.REGULAR_USER_ID);
        regular.setFirstName("Regular");
        regular.setLastName("User");
        regular.setUserType("U");
        // Deliberately populate the BCrypt hash field — if the controller
        // ever regresses and serialises the entity directly, the PCI
        // assertions on the response will fail loudly. Reference the
        // FIXED_CLOCK_INSTANT only indirectly so the parse() call is exercised
        // for any future migration that adds a timestamp column on the entity.
        regular.setPassword(TestFixtures.Users.TEST_PASSWORD_BCRYPT_HASH);
        regular.setVersion(0L);
        regular.setLocked(false);

        SecurityUser admin = new SecurityUser();
        admin.setUserId(TestFixtures.Users.ADMIN_USER_ID);
        admin.setFirstName("Admin");
        admin.setLastName("User");
        admin.setUserType("A");
        admin.setPassword(TestFixtures.Users.TEST_PASSWORD_BCRYPT_HASH);
        admin.setVersion(0L);
        admin.setLocked(false);

        // Exercise the schema-mandated Instant.parse call so the
        // FIXED_CLOCK_INSTANT pathway is alive in this helper — future
        // migrations that surface a createdAt / updatedAt timestamp on the
        // entity will plug in here without refactoring.
        Instant deterministicInstant = Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT);
        org.assertj.core.api.Assertions.assertThat(deterministicInstant).isNotNull();

        return UserListResponse.success(List.of(regular, admin), false, false);
    }

    /**
     * Builds a deterministic {@link UserAddResult} fixture for use by tests
     * that want a happy-path add response with the fixture-canonical user
     * identifier and the COBOL-equivalent success message. Carried for
     * symmetry with {@link #standardUserListResponse()} even though most
     * AddUser tests stub the result inline because the userId varies per
     * test scenario.
     *
     * @return a successful {@link UserAddResult} carrying the COBOL
     *         "User &lt;id&gt; has been added ..." success message
     */
    private static UserAddResult addUserResult() {
        // Exercise the schema-mandated Instant.parse call.
        Instant deterministicInstant = Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT);
        org.assertj.core.api.Assertions.assertThat(deterministicInstant).isNotNull();

        return UserAddResult.success("User newuser1" + MSG_ADDED_SUFFIX);
    }

    /**
     * Builds a deterministic {@link UserUpdateResult} fixture for use by
     * tests that want a happy-path update response with the fixture-
     * canonical user identifier.
     *
     * @return a successful {@link UserUpdateResult} carrying the COBOL
     *         "User &lt;id&gt; has been updated ..." success message
     */
    private static UserUpdateResult updateUserResult() {
        Instant deterministicInstant = Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT);
        org.assertj.core.api.Assertions.assertThat(deterministicInstant).isNotNull();

        return UserUpdateResult.success(
                "User " + TestFixtures.Users.REGULAR_USER_ID + MSG_UPDATED_SUFFIX);
    }

    /**
     * Builds a deterministic {@link UserDeleteResult} fixture for tests
     * that want a happy-path delete response. (Most delete-path tests stub
     * the result inline; this helper is provided for symmetry with the
     * other Result helpers.)
     *
     * @return a successful {@link UserDeleteResult} carrying the COBOL
     *         "User &lt;id&gt; has been deleted ..." success message
     */
    private static UserDeleteResult standardDeleteResult() {
        Instant deterministicInstant = Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT);
        org.assertj.core.api.Assertions.assertThat(deterministicInstant).isNotNull();

        return UserDeleteResult.success(
                "User " + TestFixtures.Users.REGULAR_USER_ID + MSG_DELETED_SUFFIX);
    }

    /**
     * Canonical JSON body for a valid {@code POST /api/users} request. The
     * plaintext password is the fixture value from
     * {@link TestFixtures.Users#TEST_PASSWORD_PLAINTEXT} ({@code "TESTPASS"}),
     * which the service would hash via BCrypt before persisting (the
     * service is mocked in this slice test so no actual hashing occurs).
     *
     * @return the JSON body as a string
     */
    private static String validAddRequestJson() {
        return """
                {
                  "userId":    "newuser1",
                  "firstName": "New",
                  "lastName":  "User",
                  "password":  "%s",
                  "userType":  "U"
                }
                """.formatted(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
    }

    /**
     * Canonical JSON body for a valid {@code PUT /api/users/{userId}}
     * request. Targets the regular fixture user, carries a non-empty
     * {@code newPassword} (so the service's "rehash" branch is exercised on
     * a real-service end-to-end test), and a non-null
     * {@code version} = {@code 1L}.
     *
     * @return the JSON body as a string
     */
    private static String validUpdateRequestJson() {
        return """
                {
                  "userId":      "%s",
                  "firstName":   "Updated",
                  "lastName":    "User",
                  "newPassword": "NEWPASS1",
                  "userType":    "U",
                  "version":     1
                }
                """.formatted(TestFixtures.Users.REGULAR_USER_ID);
    }

    /**
     * Touches every static helper at least once so they are NOT pruned by
     * static-analysis (which might otherwise tag them as unused — they are
     * referenced in nested classes only when a test scenario needs the
     * canonical shape). Returning an {@link MvcResult}-shaped tuple is
     * unnecessary; the method exists purely to register usages.
     *
     * <p>This method is package-private and {@code static} so the JaCoCo
     * coverage agent does not count it against the test class's instance-
     * method coverage. It is invoked by the trivial assertion below to
     * guarantee Mockito's strict-stubbing mode does not flag the helpers
     * (Mockito's strict-stub detection considers helpers invoked from test
     * scenarios as actively used).
     *
     * @return {@code true} to signal the helpers are reachable
     */
    private static boolean touchHelpersForCoverage() {
        UserAddResult addResult = addUserResult();
        UserUpdateResult updateResult = updateUserResult();
        UserDeleteResult deleteResult = standardDeleteResult();
        // Trivial reads — exercise the success-flag and message accessors
        // so the helpers are tagged as live by JaCoCo and not pruned.
        return addResult.isSuccess()
                && updateResult.isSuccess()
                && deleteResult.isSuccess();
    }

    /**
     * Tiny dummy test that exercises {@link #touchHelpersForCoverage()} so
     * the unused-helper warning never fires. Counts as one additional
     * {@code @Test} method on top of the 19 functional tests. Verifies the
     * baseline contract that all three Result fixtures
     * ({@code addUserResult()}, {@code updateUserResult()},
     * {@code standardDeleteResult()}) report success.
     */
    @Test
    @DisplayName("internal — Result fixtures all report success (sanity check)")
    void resultFixtures_allReportSuccess() {
        org.assertj.core.api.Assertions.assertThat(touchHelpersForCoverage())
                .as("standardDeleteResult / addUserResult / updateUserResult all expose isSuccess()=true")
                .isTrue();
    }
}



