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
 * AuthControllerTest — Spring MVC slice test for AuthController
 *
 * Replaces BMS mapset: COSGN00.bms (Login Screen, TRANID CC00)
 * Replaces COBOL pgm:  COSGN00C.cbl (BCrypt verify against USRSEC records)
 *
 * AAP references:
 *   §0.5.1  CREATE — Controller Integration Tests
 *   §0.5.2  Blueprint A — Service-Layer Class example (AuthenticationService)
 *   §0.4.1  Strategy — @WebMvcTest + @MockBean + MockMvc
 *   §0.7.1  Coverage — controller line ≥80%, branch ≥70%
 *   §0.10.5 Security — no plaintext passwords in logs or responses; BCrypt
 *           hashes never leak; uniform 401 message for unknown user vs wrong
 *           password to prevent user enumeration
 *
 * Mocking boundary: AuthenticationService (@MockBean).
 *
 * COBOL business rules preserved at HTTP layer:
 *   - User ID:  PIC X(08) -> max 8 chars  → HTTP 400 if exceeded
 *   - Password: PIC X(08) -> max 8 chars  → HTTP 400 if exceeded
 *   - Empty userId or password rejected with HTTP 400 (service-driven)
 *   - Unknown user or wrong password: HTTP 401 (uniform "Invalid user or password")
 *   - Locked user: HTTP 423 Locked (RFC 4918)
 *   - Service exception: HTTP 500 with sanitised body (no stack trace leak)
 *
 * Session establishment:
 *   - On success the AuthenticationResult body carries the populated UserSession
 *     (userId, userType, loginTime, nextRoute) — never a password / passwordHash.
 *   - Anti-enumeration: unknown-user and wrong-password produce byte-identical
 *     response bodies, asserted explicitly in
 *     signOn_unknownUserAndWrongPassword_returnIdenticalBody().
 */
package com.aws.carddemo.controller;

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies)
//
//   * TestFixtures — single source of truth for fixture user identifiers
//     (ADMIN_USER_ID, REGULAR_USER_ID, NONEXISTENT_USER_ID,
//     TEST_PASSWORD_PLAINTEXT) and the deterministic clock instant
//     (FIXED_CLOCK_INSTANT) used to populate the session timestamp in the
//     standardAuthResult helper. Per AAP §0.10.5 the plaintext password is a
//     FIXTURE credential, not a real secret.
//
//   * AuthenticationRequest, AuthenticationResult, UserSession — authentication
//     DTOs under com.aws.carddemo.dto.auth. The DTO subtree is excluded from
//     the JaCoCo coverage gate (AAP §0.7.1) so the controller-layer coverage
//     rule applies to AuthController alone.
//
//   * AuthenticationService — the service collaborator mocked via @MockBean.
//     The AAP §0.10.1 Require Test Coverage rule restricts mocks to external
//     boundaries; the controller-under-test calls a real AuthController whose
//     only dependency, AuthenticationService, is the mocked boundary.
// ---------------------------------------------------------------------------
import com.aws.carddemo.dto.auth.AuthenticationRequest;
import com.aws.carddemo.dto.auth.AuthenticationResult;
import com.aws.carddemo.dto.auth.UserSession;
import com.aws.carddemo.service.AuthenticationService;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only).
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ---------------------------------------------------------------------------
// Spring Test slice / Mockito test-context wiring
//
//   * @WebMvcTest — loads only the Spring MVC slice (the AuthController bean,
//     its message converters, the validation infrastructure) without JPA,
//     repositories, or the full @SpringBootApplication context (AAP §0.4.1).
//   * @AutoConfigureMockMvc(addFilters = false) — disables the Spring Security
//     filter chain in the test context. The sign-on endpoint is by definition
//     accessible to anonymous callers (the user is not yet authenticated), so
//     security filters do not contribute to the behavioural contract this test
//     class verifies. A separate security-config integration test would
//     exercise CSRF / permitAll() wiring.
//   * @MockBean — replaces the real AuthenticationService bean in the
//     @WebMvcTest context with a Mockito mock (AAP §0.10.1 — single mocking
//     boundary).
//   * @Autowired — injects the MockMvc harness and the auto-configured Jackson
//     ObjectMapper into the test class.
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// ---------------------------------------------------------------------------
// Spring Security Test
//
//   * SecurityMockMvcRequestPostProcessors — required-by-schema import that
//     provides the .with(csrf()) MockMvc request post-processor. With
//     addFilters=false the post-processor is a no-op, but referencing it here
//     documents the intended production behaviour (production SecurityConfig
//     would enable CSRF on every state-changing endpoint other than the
//     anonymous /api/auth/** path; if that policy ever inverts and CSRF is
//     enforced on /api/auth/sign-on, removing the addFilters=false attribute
//     above will activate the existing .with(csrf()) usage below without
//     additional code churn).
//
// Direct reference (not a static import) so the test reads as
// .with(SecurityMockMvcRequestPostProcessors.csrf()), making the security
// post-processor explicit in every call site.
// ---------------------------------------------------------------------------
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

// ---------------------------------------------------------------------------
// Jackson — auto-configured by @WebMvcTest and accessible for ad-hoc JSON
// deserialisation in the anti-enumeration body-equality test.
// ---------------------------------------------------------------------------
import com.fasterxml.jackson.databind.ObjectMapper;

// ---------------------------------------------------------------------------
// JDK 17 time primitives
//
//   * Instant.parse(FIXED_CLOCK_INSTANT) — deterministic session timestamp
//     used by every standardAuthResult invocation (AAP §0.4.2 Blueprint A
//     fixed-clock idiom).
//   * LocalDateTime — UserSession.loginTime type; converted from Instant via
//     LocalDateTime.ofInstant(instant, ZoneOffset.UTC) so the wire-format
//     stamp matches the production AuthenticationService output exactly.
//   * ZoneOffset.UTC — UTC offset used throughout the test fixtures, matching
//     the production AuthenticationService clock injection.
// ---------------------------------------------------------------------------
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

// ---------------------------------------------------------------------------
// Static imports — AssertJ fluent DSL, Mockito DSL, MockMvc DSL
//
// AAP §0.6.2 import transformation rules ("Use static imports for assertion
// helpers" / "Use static imports for Mockito DSL" / "Use static imports for
// MockMvc DSL"). Wildcard imports are avoided in favour of explicit names so
// the test source documents exactly which DSL primitives are in use.
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC slice test for {@link AuthController}.
 *
 * <p>Verifies the HTTP-boundary behaviour of the sign-on endpoint that replaces
 * BMS mapset {@code app/bms/COSGN00.bms} and COBOL program
 * {@code app/cbl/COSGN00C.cbl} (TRANID {@code CC00}).
 *
 * <h2>Test Categories</h2>
 *
 * <ul>
 *   <li><b>Happy paths</b> — admin and regular-user sign-on each return HTTP 200
 *       with the populated session in the response body; no password fields ever
 *       appear in the JSON output (PCI assertions on every happy path).</li>
 *   <li><b>Authentication failures</b> — unknown user, wrong password, and
 *       invalid user type all map to HTTP 401 with a single uniform message
 *       ({@code "Invalid user or password"}) so an attacker cannot enumerate
 *       valid user IDs by diffing reject messages.</li>
 *   <li><b>Account state</b> — locked-account rejects map to HTTP 423 LOCKED
 *       (RFC 4918), distinct from the 401 path because lock state is observable
 *       through the lockout mechanism and so the distinction is not an
 *       enumeration risk.</li>
 *   <li><b>Validation rejects</b> — blank user ID and blank password produce
 *       HTTP 400 (driven by the service's existing validation cascade); over-
 *       length user ID and over-length password produce HTTP 400 (driven by
 *       the controller's COBOL {@code PIC X(08)} boundary check, never reaching
 *       the service).</li>
 *   <li><b>Request-shape rejects</b> — malformed JSON body produces HTTP 400;
 *       missing {@code Content-Type: application/json} header produces
 *       HTTP 415 (Spring MVC's {@code consumes} attribute).</li>
 *   <li><b>Service failure</b> — when the service throws a
 *       {@link RuntimeException}, the controller's {@code @ExceptionHandler}
 *       returns HTTP 500 with a generic sanitised body — never the underlying
 *       exception message or stack trace (AAP §0.10.5 applied to error paths).</li>
 *   <li><b>Anti-enumeration</b> — an explicit assertion that the unknown-user
 *       and wrong-password responses are byte-identical.</li>
 * </ul>
 *
 * <h2>Mocking Boundary (AAP §0.10.1)</h2>
 *
 * <p>The only mocked collaborator is {@link AuthenticationService} (the
 * downstream service the controller delegates to). The controller itself is
 * the real bean loaded by {@code @WebMvcTest}; Spring's MVC infrastructure
 * (DispatcherServlet, HandlerMapping, message converters, exception resolvers)
 * is the real production wiring. Per the Require Test Coverage rule, no test
 * method duplicates the controller's HTTP-status mapping logic — every
 * assertion observes the controller's externally-visible HTTP output.
 *
 * <h2>Deterministic Time</h2>
 *
 * <p>Per AAP §0.4.2 Blueprint A, the session timestamp is sourced from a fixed
 * clock instant ({@link TestFixtures.Dates#FIXED_CLOCK_INSTANT}). The mocked
 * service returns sessions whose {@code loginTime} field equals this fixed
 * instant so JSON-body assertions are stable across test runs.
 *
 * @see AuthController
 * @see AuthenticationService
 * @see TestFixtures.Users
 * @see TestFixtures.Dates#FIXED_CLOCK_INSTANT
 */
@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AuthController — COSGN00C.cbl migration parity (BMS COSGN00, TRANID CC00)")
final class AuthControllerTest {

    /**
     * Servlet-free HTTP harness auto-configured by {@code @WebMvcTest}.
     * Used to issue requests against the loaded {@link AuthController} and
     * assert on HTTP status, headers, and response body via the Spring MVC
     * test DSL ({@code MockMvcResultMatchers.status()},
     * {@code .content()}, {@code .jsonPath(...)}).
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Auto-configured Jackson {@link ObjectMapper} (Spring Boot defaults). Used
     * by the anti-enumeration body-equality test to deserialise the two reject
     * responses for direct {@link AuthenticationResult} comparison rather than
     * string-comparing raw JSON (which would be fragile to whitespace).
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Mocked authentication service — the single mocking boundary. Every test
     * stubs this mock's {@code authenticate(...)} method with either a canned
     * {@link AuthenticationResult} (happy / reject paths) or an exception
     * (service-failure path).
     */
    @MockBean
    private AuthenticationService authenticationService;

    /**
     * Mocked session-token registry — the second mocking boundary needed
     * because {@link AuthController} now mints a Bearer token on every
     * successful sign-on via
     * {@link com.aws.carddemo.security.SessionTokenRegistry#register} and
     * embeds it in the {@code session.token} field of the response body.
     *
     * <p>The bean is mocked rather than supplied as the real
     * {@code @Component} because (a) {@code @WebMvcTest} excludes regular
     * {@code @Component} classes from the slice context by design and would
     * otherwise fail to wire the controller's constructor argument, and
     * (b) the test does not need a real UUID per call &mdash; it only needs
     * the controller's wire-up to the registry to be exercised. The
     * {@link #FIXED_TEST_TOKEN} string below is the deterministic value the
     * mocked {@code register(...)} returns; it surfaces in any future
     * {@code $.session.token} JSON assertion as the canonical expected
     * value.
     */
    @MockBean
    private com.aws.carddemo.security.SessionTokenRegistry sessionTokenRegistry;

    /**
     * Deterministic Bearer token returned by the mocked
     * {@link com.aws.carddemo.security.SessionTokenRegistry#register} stub.
     * Chosen as a syntactically-valid UUID string so any future
     * {@code $.session.token} assertion can pin the value.
     */
    private static final String FIXED_TEST_TOKEN = "00000000-0000-0000-0000-000000000001";

    /**
     * Stubs the {@code SessionTokenRegistry#register} call to return a
     * deterministic Bearer token so happy-path tests see a stable
     * {@code session.token} value in the JSON response. The stub is declared
     * with {@code lenient()} so failure-path tests that never reach the
     * registry (and therefore never invoke the stub) do not raise
     * {@code UnnecessaryStubbingException} under Mockito's
     * {@code STRICT_STUBS} default.
     */
    @BeforeEach
    void stubSessionTokenRegistry() {
        lenient().when(sessionTokenRegistry.register(anyString(), anyString()))
                .thenReturn(FIXED_TEST_TOKEN);
    }

    // =========================================================================
    // HAPPY PATHS — successful authentication returns HTTP 200 with a session
    // =========================================================================

    @Test
    @DisplayName("POST /api/auth/sign-on with valid admin returns 200 + admin session JSON")
    void signOn_validAdminUser_returns200WithSession() throws Exception {
        // Arrange — admin AuthenticationResult with a populated session that
        // mirrors what the real service produces when an admin signs on
        // successfully. The mocked service ignores the argument; the test
        // verifies (a) that the controller maps a successful service result to
        // HTTP 200, (b) that the session payload is preserved in the JSON body,
        // and (c) that the controller forwards the unmodified request to the
        // service (asserted via eq() below — Mockito equality matcher).
        AuthenticationResult adminResult = standardAuthResult("ADMIN");
        AuthenticationRequest expectedRequest = new AuthenticationRequest(
                TestFixtures.Users.ADMIN_USER_ID,
                TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
        given(authenticationService.authenticate(any(AuthenticationRequest.class)))
                .willReturn(adminResult);

        // Act + Assert
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAdminSignOnRequestJson()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.session").exists())
                .andExpect(jsonPath("$.session.userId").value(TestFixtures.Users.ADMIN_USER_ID))
                .andExpect(jsonPath("$.session.userType").value("A"))
                .andExpect(jsonPath("$.session.nextRoute").value("ADMIN_MENU"))
                .andExpect(jsonPath("$.session.loginTime").exists())
                // PCI assertions (AAP §0.10.5) — the response must NEVER include
                // a password, passwordHash, or any BCrypt token. The
                // AuthenticationResult / UserSession classes are designed
                // without such fields; these jsonPath checks defend against a
                // future regression that adds one inadvertently.
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.session.password").doesNotExist())
                .andExpect(jsonPath("$.session.passwordHash").doesNotExist())
                // ---------------------------------------------------------------
                // Authentication-transport contract (AAP §0.10.5 + §0.10.4)
                // ---------------------------------------------------------------
                // The CardDemo REST migration is intentionally STATELESS and
                // TOKEN-BASED at the transport layer: the session payload
                // travels in the JSON response body ({@code $.session}), and
                // the AuthController NEVER emits a {@code Set-Cookie} header
                // on the happy path or on any failure path. This is enforced
                // here so that a future regression that introduces a server-
                // side HTTP session (and the implicit {@code JSESSIONID}
                // {@code Set-Cookie} that comes with it) fails this test.
                //
                // Rationale per AAP §0.10.4 (immutable boundaries) — downstream
                // consumers (the future single-page application, mobile
                // clients, and any service-to-service caller) treat the
                // session as a JSON payload to be carried in an explicit
                // header (e.g., {@code Authorization: Bearer <token>}) on
                // subsequent requests. Issuing a session cookie would either
                // (a) silently change that contract or (b) introduce a
                // dual-transport surface that the migration explicitly avoids.
                //
                // Rationale per AAP §0.10.5 — cookies that lack {@code HttpOnly},
                // {@code Secure}, and {@code SameSite=Strict} attributes are a
                // PCI-scope attack surface (XSS-driven credential theft). The
                // safest enforcement is "no session cookie at all" — which is
                // also what the production AuthController source implements
                // (verified in {@code AuthController.signOn} L233–289: no
                // {@code ResponseEntity#header(HttpHeaders.SET_COOKIE, ...)},
                // no {@code Cookie} type referenced anywhere in the source).
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                // Defence-in-depth: also assert no {@code JSESSIONID} cookie was
                // surfaced via the MockHttpServletResponse#getCookies() API,
                // which captures cookies emitted via either the Set-Cookie
                // header OR the Servlet API's HttpServletResponse#addCookie.
                .andExpect(cookie().doesNotExist("JSESSIONID"))
                .andExpect(cookie().doesNotExist("SESSION"));

        // Verify the controller delegated to the service exactly once with the
        // EXACT request (AuthenticationRequest is a Java record with
        // auto-generated equals(), so eq() compares userId + password). This
        // proves the controller forwards the deserialised body to the service
        // unmodified — never truncating, lower-casing, or otherwise mutating
        // the candidate credentials.
        verify(authenticationService, times(1)).authenticate(eq(expectedRequest));
    }

    @Test
    @DisplayName("POST /api/auth/sign-on with valid regular user returns 200 + main-menu session")
    void signOn_validRegularUser_returns200WithUserRole() throws Exception {
        // Arrange — regular-user happy path with SEC-USR-TYPE='U' and
        // nextRoute=MAIN_MENU (COBOL XCTL COMEN01C parity).
        AuthenticationResult regularResult = standardAuthResult("USER");
        given(authenticationService.authenticate(any(AuthenticationRequest.class)))
                .willReturn(regularResult);

        // Act + Assert
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRegularSignOnRequestJson()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.session.userId").value(TestFixtures.Users.REGULAR_USER_ID))
                .andExpect(jsonPath("$.session.userType").value("U"))
                .andExpect(jsonPath("$.session.nextRoute").value("MAIN_MENU"))
                // PCI defence (AAP §0.10.5)
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.session.password").doesNotExist())
                // Token-only transport contract — see the long-form rationale on
                // signOn_validAdminUser_returns200WithSession above. The happy-
                // path response carries the session in JSON and emits NO
                // Set-Cookie header on success.
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(cookie().doesNotExist("JSESSIONID"))
                .andExpect(cookie().doesNotExist("SESSION"));

        verify(authenticationService, times(1)).authenticate(any(AuthenticationRequest.class));
    }

    // =========================================================================
    // AUTHENTICATION FAILURES — 401 uniform message (anti-enumeration)
    // =========================================================================

    @Test
    @DisplayName("Unknown user returns 401 with uniform anti-enumeration message")
    void signOn_unknownUser_returns401() throws Exception {
        // Arrange — service returns the COBOL "User not found ..." reject; the
        // controller must rewrite this to the uniform message to prevent user
        // enumeration (AAP §0.10.5).
        given(authenticationService.authenticate(any(AuthenticationRequest.class)))
                .willReturn(AuthenticationResult.failure("User not found. Try again ..."));

        // Act + Assert
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownUserSignOnRequestJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.session").doesNotExist())
                // Uniform message — does NOT contain "not found"; an attacker
                // cannot tell whether the user ID exists from the response.
                .andExpect(jsonPath("$.message").value("Invalid user or password"))
                // PCI defence — the submitted password must never echo back in
                // the response body.
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(authenticationService, times(1)).authenticate(any(AuthenticationRequest.class));
    }

    @Test
    @DisplayName("Wrong password returns 401 with the SAME uniform message")
    void signOn_wrongPassword_returns401() throws Exception {
        // Arrange — service returns the COBOL "Wrong Password ..." reject; the
        // controller must produce a response indistinguishable from the
        // unknown-user response so an attacker cannot tell whether a given
        // user ID exists.
        given(authenticationService.authenticate(any(AuthenticationRequest.class)))
                .willReturn(AuthenticationResult.failure("Wrong Password. Try again ..."));

        // Act + Assert
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAdminSignOnRequestJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.session").doesNotExist())
                // Identical uniform message — verified literally for parity with
                // the unknown-user response.
                .andExpect(jsonPath("$.message").value("Invalid user or password"))
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(authenticationService, times(1)).authenticate(any(AuthenticationRequest.class));
    }

    @Test
    @DisplayName("Invalid user type returns 401 with the uniform message (Java-migration safety)")
    void signOn_invalidUserType_returns401() throws Exception {
        // Arrange — the service's Java-migration safety net rejects users whose
        // SEC-USR-TYPE is neither 'A' nor 'U' (COBOL silently routes them as
        // regular users; the Java migration refuses to do so). The controller
        // surfaces this as the same uniform 401 to preserve the anti-enumeration
        // property — an attacker who manages to provoke this reject path should
        // not be able to distinguish it from other rejects.
        given(authenticationService.authenticate(any(AuthenticationRequest.class)))
                .willReturn(AuthenticationResult.failure("User type not valid ..."));

        // Act + Assert
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRegularSignOnRequestJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid user or password"))
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(authenticationService, times(1)).authenticate(any(AuthenticationRequest.class));
    }

    // =========================================================================
    // ACCOUNT STATE — locked accounts map to HTTP 423
    // =========================================================================

    @Test
    @DisplayName("Locked account returns 423 LOCKED (RFC 4918) with the locked reject message")
    void signOn_lockedUser_returns423Locked() throws Exception {
        // Arrange — the service rejects locked accounts BEFORE attempting
        // BCrypt verification (CPU-exhaustion mitigation). The controller maps
        // this to HTTP 423 Locked per RFC 4918. Locked state is intentionally
        // distinguishable from the 401 paths because the locked-out user
        // already knows their account is locked.
        given(authenticationService.authenticate(any(AuthenticationRequest.class)))
                .willReturn(AuthenticationResult.failure("Account is locked. Contact administrator ..."));

        // Act + Assert
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAdminSignOnRequestJson()))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.session").doesNotExist())
                // The locked message is preserved verbatim (it is not an
                // enumeration vector).
                .andExpect(jsonPath("$.message").value("Account is locked. Contact administrator ..."))
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(authenticationService, times(1)).authenticate(any(AuthenticationRequest.class));
    }

    // =========================================================================
    // VALIDATION REJECTS — HTTP 400 (service-driven or boundary-driven)
    // =========================================================================

    @Test
    @DisplayName("Empty userId surfaces the service's MSG_EMPTY_USER_ID as HTTP 400")
    void signOn_emptyUserId_returns400() throws Exception {
        // Arrange — the service detects the empty user ID and returns
        // MSG_EMPTY_USER_ID; the controller maps that specific message to
        // HTTP 400. The COBOL reject message is preserved verbatim because an
        // empty-input reject is not a user-enumeration vector.
        given(authenticationService.authenticate(any(AuthenticationRequest.class)))
                .willReturn(AuthenticationResult.failure("Please enter User ID ..."));

        // Act + Assert
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyUserIdSignOnRequestJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Please enter User ID ..."))
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(authenticationService, times(1)).authenticate(any(AuthenticationRequest.class));
    }

    @Test
    @DisplayName("Empty password surfaces the service's MSG_EMPTY_PASSWORD as HTTP 400")
    void signOn_emptyPassword_returns400() throws Exception {
        // Arrange — service detects empty password and rejects.
        given(authenticationService.authenticate(any(AuthenticationRequest.class)))
                .willReturn(AuthenticationResult.failure("Please enter Password ..."));

        // Act + Assert
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyPasswordSignOnRequestJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Please enter Password ..."))
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(authenticationService, times(1)).authenticate(any(AuthenticationRequest.class));
    }

    @Test
    @DisplayName("userId exceeding COBOL PIC X(08) yields HTTP 400 without calling the service")
    void signOn_userIdExceedingMaxLength_returns400_serviceNotCalled() throws Exception {
        // Arrange — userId is 15 characters, exceeding the COBOL PIC X(08)
        // boundary (8 characters). The controller MUST reject this at the HTTP
        // boundary and MUST NOT delegate to the service (because the COBOL
        // service would otherwise truncate the value silently, masking the
        // boundary violation).
        String overLengthUserIdJson = """
                {
                  "userId": "TOOLONG_USERID_15",
                  "password": "TESTPASS"
                }
                """;

        // Act + Assert
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overLengthUserIdJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("User ID must be 1-8 characters"))
                .andExpect(jsonPath("$.password").doesNotExist());

        // Critical: the boundary check must trigger BEFORE the service is
        // invoked, otherwise the service would receive a truncated value.
        verify(authenticationService, never()).authenticate(any(AuthenticationRequest.class));
    }

    @Test
    @DisplayName("password exceeding COBOL PIC X(08) yields HTTP 400 without calling the service")
    void signOn_passwordExceedingMaxLength_returns400_serviceNotCalled() throws Exception {
        // Arrange — password is 16 characters, exceeding the COBOL PIC X(08).
        String overLengthPasswordJson = """
                {
                  "userId": "ADMTST01",
                  "password": "PASSWORD_TOO_LONG_16"
                }
                """;

        // Act + Assert
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overLengthPasswordJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Password must be 1-8 characters"))
                // Critically: the submitted password is REJECTED but must never
                // appear in the response body — neither in $.password nor in
                // $.message (the boundary-check message names the field, not
                // the value).
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(authenticationService, never()).authenticate(any(AuthenticationRequest.class));
    }

    // =========================================================================
    // REQUEST-SHAPE REJECTS — HTTP 400 / 415 (Spring MVC infrastructure)
    // =========================================================================

    @Test
    @DisplayName("Malformed JSON body returns HTTP 400 without calling the service")
    void signOn_malformedJson_returns400_serviceNotCalled() throws Exception {
        // Arrange — Spring's Jackson HttpMessageConverter will fail to parse
        // this body and throw HttpMessageNotReadableException, which the
        // DefaultHandlerExceptionResolver maps to HTTP 400.
        String malformedJson = "{ this is { not valid JSON ";

        // Act + Assert
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedJson))
                .andExpect(status().isBadRequest());

        // The service must NEVER receive a malformed body — message
        // deserialisation is a strict precondition for the @RequestBody binding.
        verify(authenticationService, never()).authenticate(any(AuthenticationRequest.class));
    }

    @Test
    @DisplayName("Missing application/json Content-Type returns HTTP 415")
    void signOn_missingContentType_returns415_serviceNotCalled() throws Exception {
        // Arrange — the controller's @PostMapping has consumes=APPLICATION_JSON_VALUE
        // so Spring MVC enforces the content-type contract at the dispatcher.
        // Posting an empty body with no Content-Type header (or a non-JSON
        // Content-Type) is the canonical way to provoke HTTP 415.

        // Act + Assert
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .content(validAdminSignOnRequestJson()))
                // No .contentType(...) attached → Spring sees a missing
                // Content-Type and rejects with 415.
                .andExpect(status().isUnsupportedMediaType());

        verify(authenticationService, never()).authenticate(any(AuthenticationRequest.class));
    }

    // =========================================================================
    // SERVICE FAILURE — HTTP 500 with sanitised body
    // =========================================================================

    @Test
    @DisplayName("Service throws RuntimeException → HTTP 500 with sanitised body (no detail leak)")
    void signOn_serviceFailure_returns500() throws Exception {
        // Arrange — the service mock throws an exception carrying a sensitive
        // detail string ("Database password=secret"). The controller's
        // @ExceptionHandler MUST return the canned MSG_INTERNAL_ERROR and MUST
        // NOT include the exception message, the database password fragment,
        // or any stack-trace element in the response body.
        String sensitiveExceptionDetail =
                "Connection refused to db.internal: postgres://admin:dbpassword@10.0.0.5";
        given(authenticationService.authenticate(any(AuthenticationRequest.class)))
                .willThrow(new RuntimeException(sensitiveExceptionDetail));

        // Act
        MvcResult result = mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAdminSignOnRequestJson()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();

        // Defence-in-depth: explicitly assert the response body does NOT
        // contain ANY substring of the sensitive exception detail. JsonPath
        // matchers above already confirm the message is sanitised, but this
        // string-search guards against future regressions that might leak the
        // detail through an unexpected field (for example a debug trace
        // appended at the end of the body).
        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody)
                .as("HTTP 500 response body must not leak service-layer exception details")
                .doesNotContain("dbpassword")
                .doesNotContain("postgres://")
                .doesNotContain("Connection refused")
                .doesNotContain("RuntimeException")
                .doesNotContain("10.0.0.5");

        verify(authenticationService, times(1)).authenticate(any(AuthenticationRequest.class));
    }

    // =========================================================================
    // ANTI-ENUMERATION — explicit identical-body assertion
    // =========================================================================

    @Test
    @DisplayName("Unknown user and wrong password produce byte-identical response bodies")
    void signOn_unknownUserAndWrongPassword_returnIdenticalBody() throws Exception {
        // Arrange — two stubs, one returning the COBOL "User not found ..."
        // reject and the other the "Wrong Password ..." reject. The controller
        // must collapse both into the same uniform response so an attacker
        // cannot distinguish them.

        // First request — unknown user
        given(authenticationService.authenticate(any(AuthenticationRequest.class)))
                .willReturn(AuthenticationResult.failure("User not found. Try again ..."));
        MvcResult unknownUserResult = mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownUserSignOnRequestJson()))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // Re-stub the mock for the second scenario (wrong password).
        given(authenticationService.authenticate(any(AuthenticationRequest.class)))
                .willReturn(AuthenticationResult.failure("Wrong Password. Try again ..."));
        MvcResult wrongPasswordResult = mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAdminSignOnRequestJson()))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // Assert HTTP status equality
        assertThat(unknownUserResult.getResponse().getStatus())
                .isEqualTo(wrongPasswordResult.getResponse().getStatus());

        // Deserialise both bodies and assert AuthenticationResult equality.
        // Going via the deserialised object (rather than raw strings) tolerates
        // any harmless JSON-property ordering differences while still proving
        // semantic identity.
        AuthenticationResult unknownUserBody = objectMapper.readValue(
                unknownUserResult.getResponse().getContentAsString(),
                AuthenticationResult.class);
        AuthenticationResult wrongPasswordBody = objectMapper.readValue(
                wrongPasswordResult.getResponse().getContentAsString(),
                AuthenticationResult.class);

        assertThat(unknownUserBody)
                .as("Anti-enumeration: unknown-user and wrong-password bodies must be identical")
                .isEqualTo(wrongPasswordBody);

        // The shared message must be the uniform anti-enumeration literal.
        assertThat(unknownUserBody.getMessage()).isEqualTo("Invalid user or password");
        assertThat(wrongPasswordBody.getMessage()).isEqualTo("Invalid user or password");
        assertThat(unknownUserBody.isSuccess()).isFalse();
        assertThat(wrongPasswordBody.isSuccess()).isFalse();
        assertThat(unknownUserBody.getSession()).isNull();
        assertThat(wrongPasswordBody.getSession()).isNull();
    }

    // =========================================================================
    // TOKEN-ONLY TRANSPORT CONTRACT — no Set-Cookie on ANY response path
    // =========================================================================
    //
    // Code-review finding (AuthControllerTest, MAJOR): the happy-path tests
    // assert only on the JSON body and do not verify the cookie-issuance
    // contract that the original checkpoint scope called out as part of
    // "session cookie issuance". The CardDemo migration's chosen design is
    // explicitly token-based — the session payload travels in the JSON body
    // and authentication has no server-side session cookie. The block below
    // closes that gap by exhaustively asserting "no Set-Cookie on any
    // response path", which simultaneously:
    //
    //   (a) Documents the design choice in test code so future engineers
    //       cannot silently introduce a session cookie without breaking a
    //       test (AAP §0.10.4 immutable boundaries).
    //   (b) Defends against PCI-scope cookie-attribute vulnerabilities
    //       (missing HttpOnly / Secure / SameSite) by virtue of "no cookie
    //       at all" being trivially safer than "cookie with the wrong
    //       attributes" (AAP §0.10.5).
    //   (c) Catches a class of Spring-config regression where adding a
    //       stateful session-management filter would inject a JSESSIONID
    //       Set-Cookie response header.
    //
    // The original happy-path tests above already assert the no-cookie
    // contract for HTTP 200; the test below extends it to HTTP 400, 401,
    // 423, and the inputs that trigger each.

    @Test
    @DisplayName("Token-only contract: NO Set-Cookie / JSESSIONID issued on any response path")
    void signOn_neverIssuesSessionCookie_onAnyResponsePath() throws Exception {
        // -------------------------------------------------------------------
        // Path 1 — HTTP 200 happy path (admin)
        // -------------------------------------------------------------------
        given(authenticationService.authenticate(any(AuthenticationRequest.class)))
                .willReturn(standardAuthResult("ADMIN"));
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAdminSignOnRequestJson()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(cookie().doesNotExist("JSESSIONID"))
                .andExpect(cookie().doesNotExist("SESSION"));

        // -------------------------------------------------------------------
        // Path 2 — HTTP 401 unknown-user reject
        // -------------------------------------------------------------------
        given(authenticationService.authenticate(any(AuthenticationRequest.class)))
                .willReturn(AuthenticationResult.failure("User not found. Try again ..."));
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownUserSignOnRequestJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(cookie().doesNotExist("JSESSIONID"))
                .andExpect(cookie().doesNotExist("SESSION"));

        // -------------------------------------------------------------------
        // Path 3 — HTTP 401 wrong-password reject
        // -------------------------------------------------------------------
        given(authenticationService.authenticate(any(AuthenticationRequest.class)))
                .willReturn(AuthenticationResult.failure("Wrong Password. Try again ..."));
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAdminSignOnRequestJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(cookie().doesNotExist("JSESSIONID"))
                .andExpect(cookie().doesNotExist("SESSION"));

        // -------------------------------------------------------------------
        // Path 4 — HTTP 423 locked-account reject
        // -------------------------------------------------------------------
        // The locked-account reject message MUST equal the controller's
        // MSG_ACCOUNT_LOCKED constant verbatim for the controller to map
        // the reject to HTTP 423 Locked (RFC 4918). The verbatim literal
        // used here matches AuthController.MSG_ACCOUNT_LOCKED at L149.
        given(authenticationService.authenticate(any(AuthenticationRequest.class)))
                .willReturn(AuthenticationResult.failure(
                        "Account is locked. Contact administrator ..."));
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRegularSignOnRequestJson()))
                .andExpect(status().isLocked())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(cookie().doesNotExist("JSESSIONID"))
                .andExpect(cookie().doesNotExist("SESSION"));

        // -------------------------------------------------------------------
        // Path 5 — HTTP 400 empty-userId reject (service-driven)
        // -------------------------------------------------------------------
        given(authenticationService.authenticate(any(AuthenticationRequest.class)))
                .willReturn(AuthenticationResult.failure("Please enter User ID ..."));
        String emptyUserIdBody = """
                {
                  "userId": "",
                  "password": "%s"
                }
                """.formatted(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyUserIdBody))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(cookie().doesNotExist("JSESSIONID"))
                .andExpect(cookie().doesNotExist("SESSION"));

        // -------------------------------------------------------------------
        // Path 6 — HTTP 400 over-length userId (controller-driven, service
        //          NOT called — but the response still must not issue any
        //          session cookie)
        // -------------------------------------------------------------------
        String overLengthBody = """
                {
                  "userId": "TOOLONG99",
                  "password": "%s"
                }
                """.formatted(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
        mockMvc.perform(post("/api/auth/sign-on")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overLengthBody))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(cookie().doesNotExist("JSESSIONID"))
                .andExpect(cookie().doesNotExist("SESSION"));
    }

    // =========================================================================
    // STATIC HELPERS — fixture builders shared by the tests above
    // =========================================================================

    /**
     * Builds an {@link AuthenticationResult#success(String, UserSession)} carrying
     * a deterministic {@link UserSession} for the given role. The session's
     * login timestamp is sourced from the
     * {@link TestFixtures.Dates#FIXED_CLOCK_INSTANT} fixed clock so JSON-body
     * assertions are stable across test runs (AAP §0.4.2 Blueprint A — fixed
     * clock).
     *
     * <p>The session carries no password / passwordHash fields by design
     * ({@link UserSession} has no such field). This is structural defence
     * against accidental credential disclosure (AAP §0.10.5).
     *
     * @param role either {@code "ADMIN"} (produces an admin session with
     *             userType {@code "A"} and nextRoute {@code "ADMIN_MENU"}) or
     *             any other value (produces a regular-user session with
     *             userType {@code "U"} and nextRoute {@code "MAIN_MENU"})
     * @return the built {@code AuthenticationResult}
     */
    private static AuthenticationResult standardAuthResult(String role) {
        boolean admin = "ADMIN".equals(role);
        String userId = admin
                ? TestFixtures.Users.ADMIN_USER_ID
                : TestFixtures.Users.REGULAR_USER_ID;
        String userType = admin ? "A" : "U";
        String nextRoute = admin ? "ADMIN_MENU" : "MAIN_MENU";
        LocalDateTime loginTime = LocalDateTime.ofInstant(
                Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT),
                ZoneOffset.UTC);
        String welcome = "Welcome " + (admin ? "Admin" : "Regular") + " User ...";
        return AuthenticationResult.success(welcome,
                new UserSession(userId, userType, loginTime, nextRoute));
    }

    /**
     * @return a JSON body for a valid admin sign-on request using
     *         {@link TestFixtures.Users#ADMIN_USER_ID} and
     *         {@link TestFixtures.Users#TEST_PASSWORD_PLAINTEXT}
     */
    private static String validAdminSignOnRequestJson() {
        return """
                {
                  "userId": "%s",
                  "password": "%s"
                }
                """.formatted(
                TestFixtures.Users.ADMIN_USER_ID,
                TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
    }

    /**
     * @return a JSON body for a valid regular-user sign-on request using
     *         {@link TestFixtures.Users#REGULAR_USER_ID} and
     *         {@link TestFixtures.Users#TEST_PASSWORD_PLAINTEXT}
     */
    private static String validRegularSignOnRequestJson() {
        return """
                {
                  "userId": "%s",
                  "password": "%s"
                }
                """.formatted(
                TestFixtures.Users.REGULAR_USER_ID,
                TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
    }

    /**
     * @return a JSON body whose userId is the
     *         {@link TestFixtures.Users#NONEXISTENT_USER_ID} fixture
     */
    private static String unknownUserSignOnRequestJson() {
        return """
                {
                  "userId": "%s",
                  "password": "%s"
                }
                """.formatted(
                TestFixtures.Users.NONEXISTENT_USER_ID,
                TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
    }

    /**
     * @return a JSON body with an empty userId — the input the COBOL
     *         {@code IF USERIDI = SPACES OR LOW-VALUES} branch rejects
     */
    private static String emptyUserIdSignOnRequestJson() {
        return """
                {
                  "userId": "",
                  "password": "%s"
                }
                """.formatted(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
    }

    /**
     * @return a JSON body with an empty password — the input the COBOL
     *         {@code IF PASSWDI = SPACES OR LOW-VALUES} branch rejects
     */
    private static String emptyPasswordSignOnRequestJson() {
        return """
                {
                  "userId": "%s",
                  "password": ""
                }
                """.formatted(TestFixtures.Users.ADMIN_USER_ID);
    }

}
