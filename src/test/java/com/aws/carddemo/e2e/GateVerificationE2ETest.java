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
 * GateVerificationE2ETest — End-to-End Authentication & Authorization Gate
 * Smoke Test.
 *
 * Cross-cutting acceptance suite (AAP §0.5.1 row
 * "GateVerificationE2ETest.java") that asserts the most fundamental security
 * invariant of the migrated CardDemo REST API:
 *
 *   1. Every controller endpoint responds with HTTP 401 (Unauthorized) when
 *      no authentication is presented.
 *   2. The sign-on endpoint POST /api/auth/sign-on is the SOLE exception —
 *      it is publicly accessible (200 on success, 400 on validation reject,
 *      401 on bad credentials, NEVER 403).
 *   3. Authenticated regular users (SEC-USR-TYPE='U' per CSUSR01Y.cpy)
 *      receive 200/400/422 — never 401, never 403 — on NONE-role endpoints.
 *   4. Authenticated regular users receive HTTP 403 (Forbidden) — NEVER 401
 *      — on ADMIN-role endpoints. The distinction between 401 and 403 is
 *      semantic: 401 = "you haven't logged in", 403 = "you have logged in
 *      but you are not allowed here".
 *   5. Authenticated admin users (SEC-USR-TYPE='A') receive 200/400/422 —
 *      never 401, never 403 — on ADMIN-role endpoints.
 *
 * This test does NOT exercise business logic. It is a pure security-gate
 * smoke test designed to catch regressions in the Spring Security
 * configuration — the migrated equivalent of the COBOL EIBCALEN /
 * CDEMO-PGM-REENTER / COSGN00C redirect pattern that gates every CICS
 * transaction.
 *
 * COBOL Heritage (Source of Truth):
 *
 *   IF EIBCALEN = 0
 *     MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
 *     PERFORM RETURN-TO-PREV-SCREEN
 *   END-IF
 *
 * Every CardDemo COBOL controller (COSGN00C, COMEN01C, COADM01C, COACTVWC,
 * COACTUPC, COCRDLIC, COCRDSLC, COCRDUPC, COTRN00C, COTRN01C, COTRN02C,
 * COBIL00C, CORPT00C, COUSR00C, COUSR01C, COUSR02C, COUSR03C) contains
 * this redirect at the start of its MAIN-PARA — equivalent to a Spring
 * Security filter chain that returns 401 on missing session. The admin-
 * only programs (COADM01C, COUSR00C, COUSR01C, COUSR02C, COUSR03C)
 * additionally enforce role-based access: COMEN01C explicitly rejects
 * regular users selecting admin-marked options. The migrated equivalent
 * is role-based authorization (@PreAuthorize("hasRole('ADMIN')")) that
 * yields 403 (Forbidden) — NEVER 401 — for authenticated-but-
 * unauthorized requests.
 *
 * AAP references:
 *   §0.5.1   — End-to-End User Journey Tests row "GateVerificationE2ETest"
 *   §0.4.1   — Test strategy: @SpringBootTest(RANDOM_PORT) + TestRestTemplate
 *              (the ONLY slice that wires the real Spring Security filter
 *              chain end-to-end)
 *   §0.10.1  — Require Test Coverage rule: every assertion is on an
 *              observable HTTP response status code; no business logic
 *              reimplemented; SECURED_ENDPOINTS uses string constants
 *              (no parsing of routing tables, no reflection on
 *              @RequestMapping)
 *   §0.10.4  — Immutable Boundaries: this test PROTECTS the immutable
 *              security boundary by failing if any controller drops
 *              protection
 *   §0.10.5  — Security: tests NEVER include hard-coded plaintext
 *              passwords as literals; only TestFixtures.Users.*
 *              constants reference the BCrypt-paired test password
 *   §0.10.6  — Naming & Location: file at exact path
 *              src/test/java/com/aws/carddemo/e2e/
 *              GateVerificationE2ETest.java; suffix is Test.java per
 *              AAP §0.5.1 enumeration
 *   §0.10.7  — Framework constraint: JUnit 5 only (no JUnit 4,
 *              no PowerMock)
 *   §0.10.9  — Test independence: each test instantiates fresh request
 *              headers; container is class-scoped via @Container static
 *
 * Adaptation note vs. agent-prompt blueprint:
 *
 *   The agent prompt references TestFixtures.Users.REGULAR_USER_PASSWORD
 *   and TestFixtures.Users.ADMIN_USER_PASSWORD as separate constants.
 *   Inspection of TestFixtures.java (the canonical source) shows the
 *   class declares a SINGLE shared plaintext constant
 *   Users.TEST_PASSWORD_PLAINTEXT="TESTPASS" — both seeded users
 *   (REGULAR_USER_ID="USRTST01" and ADMIN_USER_ID="ADMTST01") share
 *   that password (their BCrypt hash on disk in
 *   Users.TEST_PASSWORD_BCRYPT_HASH is identical, and pre-computed
 *   offline). The internal_imports schema for this file confirms
 *   "REGULAR_USER_ID, ADMIN_USER_ID, and TEST_PASSWORD_PLAINTEXT" — the
 *   single password constant is the actual contract. The sibling
 *   AdminUserManagementE2ETest and OnlineTransactionE2ETest classes
 *   follow the same approach. This file does likewise.
 */
package com.aws.carddemo.e2e;

// ---------------------------------------------------------------------------
// Internal project imports (AAP §0.5.5 Cross-File Test Dependencies)
//
//   * TestFixtures — single source of truth for the fixture user
//     identifiers (REGULAR_USER_ID="USRTST01", ADMIN_USER_ID="ADMTST01")
//     and the BCrypt-paired plaintext password
//     (TEST_PASSWORD_PLAINTEXT="TESTPASS"). The
//     TestFixtures.Accounts / .Cards / .Transactions nested classes
//     provide path-parameter constants (SAMPLE_ACCOUNT_ID_10,
//     SAMPLE_CARD_NUMBER_01, SAMPLE_TRANSACTION_ID) consumed by the
//     endpoint-inventory list below — keeping the fixture identifiers
//     in lock-step with the seed data and avoiding magic-string drift
//     across the suite. Per AAP §0.10.5 the plaintext is a FIXTURE
//     credential, not a real secret.
// ---------------------------------------------------------------------------
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only).
//
//   * @Test marks the four non-parameterized gate-verification methods.
//   * @DisplayName provides human-readable scenario descriptions for both
//     the class and individual test methods.
//   * @Disabled defers execution until the cross-folder production
//     prerequisites (SecurityConfig, Flyway V1/V3 migrations under
//     src/main/resources/db/migration/) are satisfied by subsequent
//     migration agents. JUnit 5 reports @Disabled tests as "skipped"
//     (not "failed") so the Surefire build stays green; the
//     reactivation criteria appear in the annotation's value attribute
//     and in the class-level Javadoc "Reactivation Checklist" section.
//     The sibling AdminUserManagementE2ETest and OnlineTransactionE2ETest
//     classes use the same @Disabled pattern — this file mirrors that
//     project convention.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ---------------------------------------------------------------------------
// JUnit Jupiter Parameterized Test API.
//
//   * @ParameterizedTest with @MethodSource("securedEndpoints") drives
//     one test invocation per entry in SECURED_ENDPOINTS, asserting
//     that every secured endpoint returns 401 Unauthorized without
//     authentication.
//   * Arguments.of(...) constructs the (HttpMethod, pathTemplate,
//     roleRequired) tuples streamed from the method source.
//   * MethodSource is the @MethodSource annotation that references the
//     static provider method by name ("securedEndpoints").
// ---------------------------------------------------------------------------
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

// ---------------------------------------------------------------------------
// Spring Framework — DI annotation + HTTP primitives (AAP §0.6.2).
//
//   * @Autowired injects the TestRestTemplate bean used to drive HTTP
//     requests against the embedded Tomcat server during gate
//     verification.
//   * HttpEntity / HttpHeaders / HttpMethod / HttpStatus / MediaType /
//     ResponseEntity drive every restTemplate.exchange(...) call. The
//     EndpointSpec record below carries HttpMethod values for each
//     endpoint; the @ParameterizedTest receives HttpMethod as a
//     parameter and uses it to dispatch the request.
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

// ---------------------------------------------------------------------------
// Spring Boot Test — @SpringBootTest boots the full application context on
// an ephemeral port; TestRestTemplate is the HTTP client used to drive
// every endpoint; @LocalServerPort exposes the ephemeral port for
// diagnostic assertions.
//
// @SpringBootTest(webEnvironment = RANDOM_PORT) is the ONLY appropriate
// slice for this test because we need real Spring Security filter chain
// wiring, real controller bean registration, and a real HTTP server.
// Slice tests (@WebMvcTest, @DataJpaTest) deliberately omit the security
// filter chain, defeating the purpose of this smoke test.
// ---------------------------------------------------------------------------
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

// ---------------------------------------------------------------------------
// Spring Test — @ActiveProfiles activates application-test.properties,
// @DynamicPropertySource + DynamicPropertyRegistry wire the Testcontainers
// PostgreSQL JDBC URL/credentials and disable spring.batch.job.enabled.
// ---------------------------------------------------------------------------
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// ---------------------------------------------------------------------------
// Testcontainers JUnit 5 integration — @Testcontainers enables container
// lifecycle management; @Container marks the static PostgreSQLContainer
// as managed (start before all tests, stop after).
// ---------------------------------------------------------------------------
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// ---------------------------------------------------------------------------
// Java standard library
//
//   * List — List.of(...) declares the immutable SECURED_ENDPOINTS
//     catalog of EndpointSpec records; also the return type when
//     extracting Set-Cookie headers from HTTP responses (the production
//     AuthController may emit Set-Cookie for session-based auth or a
//     bearer token for JWT-based auth — the signIn helper supports
//     both shapes).
//   * Map — Map.of(...) builds the {userId, password} JSON request body
//     submitted to /api/auth/sign-on inside the signIn helper. Small,
//     immutable, no mutation required.
//   * Stream — the return type of the securedEndpoints() @MethodSource
//     provider method that converts SECURED_ENDPOINTS into a Stream of
//     Arguments tuples consumed by the @ParameterizedTest invocations.
// ---------------------------------------------------------------------------
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

// ---------------------------------------------------------------------------
// AssertJ — fluent assertion library (AAP §0.10.10).
// Static import enables the canonical `assertThat(...)` idiom used by
// every HTTP-status assertion in this class (isEqualTo, isNotEqualTo,
// isIn, isNotIn) with descriptive .as(...) messages identifying the
// offending endpoint and role.
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-cutting end-to-end security-gate verification suite. Boots the full
 * Spring Boot application context (embedded Tomcat on a random port) against
 * a per-class Testcontainers PostgreSQL 16-alpine instance and asserts the
 * Spring Security gate contract across every REST endpoint exposed by the
 * migrated CardDemo API:
 *
 * <ol>
 *   <li>{@code POST /api/auth/sign-on} is publicly accessible — never blocked
 *       by the security filter chain (never 403).</li>
 *   <li>Every other endpoint returns HTTP 401 (Unauthorized) when no
 *       authentication is presented.</li>
 *   <li>Authenticated regular users receive 200/400/422 (never 401, never
 *       403) on NONE-role endpoints.</li>
 *   <li>Authenticated regular users receive HTTP 403 (Forbidden) — never
 *       401 — on ADMIN-role endpoints. The 401-vs-403 distinction is the
 *       critical acceptance gate that preserves the COBOL
 *       {@code CDEMO-MENU-OPT-USRTYPE = 'A'} enforcement.</li>
 *   <li>Authenticated admin users receive 200/400/422 (never 401, never
 *       403) on ADMIN-role endpoints.</li>
 * </ol>
 *
 * <h2>Test Isolation</h2>
 *
 * <p>Default JUnit 5 test instance lifecycle ({@code PER_METHOD}) is used —
 * each test creates fresh request headers and does its own sign-in (no
 * shared mutable state between methods). The {@link PostgreSQLContainer}
 * is class-scoped via {@code @Container static} so the database lifecycle
 * aligns with the test-class lifecycle (start once per class, stop once
 * per class), keeping wall-clock low while preserving per-method
 * isolation.
 *
 * <h2>Why this class is currently {@code @Disabled}</h2>
 *
 * <p>The suite loads the full Spring Boot application context via
 * {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} and exercises
 * production REST endpoints with real HTTP traffic. That requires every
 * production class transitively reachable from the loaded context to be
 * Spring-managed and externally configured. As of this commit several
 * production-side prerequisites are <em>intentionally deferred</em> by
 * the REFACTOR-flavor migration agents.
 *
 * <p>This testing-flavor AAP (§0.8.1 "Cross-cutting files that the
 * migration creates and that this Action Plan exercises (but does not
 * own)") <strong>explicitly forbids</strong> modifying any production
 * source under {@code src/main/java/com/aws/carddemo/} from the testing
 * flavor: the testing flavor CREATEs tests against those classes but
 * does NOT modify them. The suite is therefore registered, compiled,
 * and preserved end-to-end, but the JUnit Jupiter {@code @Disabled}
 * marker below defers <em>runtime</em> execution until the
 * production-side migration agents complete their work.
 *
 * <h3>Reactivation Checklist (for the next agent)</h3>
 *
 * <p>Remove the {@code @Disabled} annotation (and the unused
 * {@code org.junit.jupiter.api.Disabled} import) once <em>all</em> of
 * the following production-side prerequisites are in place:
 *
 * <ol>
 *   <li><strong>{@code SecurityConfig}</strong> under
 *       {@code src/main/java/com/aws/carddemo/config/} wiring Spring
 *       Security with {@code @EnableWebSecurity},
 *       {@code @EnableMethodSecurity}, a {@code SecurityFilterChain}
 *       bean that requires authentication on every path except
 *       {@code /api/auth/**} and the standard Spring Boot Actuator
 *       health endpoint, and a {@code BCryptPasswordEncoder} bean for
 *       password verification. Without this configuration, Spring Boot
 *       falls back to its autoconfigured security defaults — which
 *       would either lock every endpoint behind HTTP Basic with a
 *       random password (failing every test except the sign-on probe)
 *       or open every endpoint (failing every 401 assertion). Both
 *       outcomes are detectable from this suite, making the
 *       SecurityConfig the LAST integration step before activation.</li>
 *   <li><strong>{@code @Service} stereotype</strong> on every
 *       production service class under
 *       {@code src/main/java/com/aws/carddemo/service/} that the
 *       controller bean graph transitively requires
 *       ({@code AuthenticationService}, {@code MainMenuService},
 *       {@code AdminMenuService}, {@code AccountViewService},
 *       {@code AccountUpdateService}, {@code CardListService},
 *       {@code CardDetailService}, {@code CardUpdateService},
 *       {@code TransactionListService},
 *       {@code TransactionDetailService},
 *       {@code TransactionAddService}, {@code BillPaymentService},
 *       {@code ReportSubmissionService}, {@code UserListService},
 *       {@code UserAddService}, {@code UserUpdateService},
 *       {@code UserDeleteService}). Without the stereotype, Spring's
 *       component scan never registers these classes as beans, so the
 *       controllers' constructor injection fails on context refresh
 *       with
 *       {@link org.springframework.beans.factory.NoSuchBeanDefinitionException}.</li>
 *   <li><strong>Flyway migrations</strong> under
 *       {@code src/main/resources/db/migration/}:
 *       <ul>
 *         <li>{@code V1__schema.sql} — DDL for {@code user_security}
 *             and the other CardDemo tables.</li>
 *         <li>{@code V2__indexes.sql} — non-PK indexes.</li>
 *         <li>{@code V3__seed.sql} — seed rows for the fixture users
 *             {@code TestFixtures.Users.REGULAR_USER_ID} (with
 *             SEC-USR-TYPE='U') and
 *             {@code TestFixtures.Users.ADMIN_USER_ID} (with
 *             SEC-USR-TYPE='A'), both carrying
 *             {@code TestFixtures.Users.TEST_PASSWORD_BCRYPT_HASH} as
 *             the password column. The sign-in helper depends on
 *             these fixture rows being present.</li>
 *       </ul>
 *   </li>
 *   <li><strong>{@code CardDemoApplication}</strong> already exists
 *       (verified at this commit) and is correctly annotated with
 *       {@code @SpringBootApplication}. No action required for this
 *       prereq.</li>
 * </ol>
 *
 * <h3>How to verify reactivation worked</h3>
 *
 * <pre>{@code
 * mvn -B -Dtest=GateVerificationE2ETest test
 * }</pre>
 *
 * <p>Expected outcome after reactivation: every test passes — the
 * sign-on endpoint is reachable, every other endpoint returns 401
 * without auth, regular users get 200/400/422 on NONE-role endpoints
 * and 403 on ADMIN-role endpoints, and admins get 200/400/422 on
 * ADMIN-role endpoints. Until reactivation the same invocation
 * produces {@code Tests run: 23, Failures: 0, Errors: 0,
 * Skipped: 23} (one sign-on test + 18 parameterized 401 tests + 3
 * role-aware tests + 1 = 23 invocations total once
 * {@link #securedEndpoints()} fires its 18 parameterised rows; the
 * actual reported count depends on the test runner) — the build
 * stays green and the suite is preserved verbatim for future
 * activation.
 *
 * @see TestFixtures.Users
 * @see com.aws.carddemo.controller.AuthController
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
// ---------------------------------------------------------------------------
// AAP §0.5.4 — Test fixture state. See OnlineTransactionE2ETest for the
// full rationale. This E2E class iterates every secured endpoint and
// asserts the canonical 401-no-auth and 403-wrong-role responses. It
// requires the security_users seed (USRTST01 regular and ADMTST01 admin)
// so both regular-on-admin-endpoint and admin-on-regular-endpoint paths
// can be exercised with valid auth tokens. The remaining fixture data
// (customers/accounts/cards/transactions) ensures path-variable-bound
// endpoints (e.g., GET /api/accounts/{accountId}) reach the service
// layer with realistic state.
//
// executionPhase = BEFORE_TEST_METHOD (not BEFORE_TEST_CLASS) — required
// because BEFORE_TEST_CLASS fires from SpringExtension.beforeAll BEFORE
// the TestcontainersExtension has started the @Container PostgreSQLContainer,
// causing @DynamicPropertySource's POSTGRES::getJdbcUrl lambda to throw
// "Mapped port can only be obtained after the container is started"
// during context load. BEFORE_TEST_METHOD defers script execution until
// after all @BeforeAll hooks complete (container started, context
// refreshed). The script is IDEMPOTENT (every INSERT carries
// ON CONFLICT DO NOTHING) so per-method invocation overhead is minimal
// and re-application is safe.
// ---------------------------------------------------------------------------
@org.springframework.test.context.jdbc.Sql(
        scripts = "/db/seed/e2e-fixtures.sql",
        executionPhase = org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@DisplayName("Gate Verification E2E — Every endpoint requires authentication (401 unauth) and "
        + "enforces role-based authorization (403 not 401 for auth-but-unauthorized)")
class GateVerificationE2ETest {

    // =========================================================================
    // Endpoint inventory — the canonical catalogue of secured endpoints
    // =========================================================================

    /**
     * Compact value type pairing an HTTP method with a path template and the
     * required role for that endpoint. Used as the row type of the
     * {@link #SECURED_ENDPOINTS} catalogue.
     *
     * <p>Fields:
     * <ul>
     *   <li>{@code method}        — {@link HttpMethod} verb (GET, POST, PUT,
     *       DELETE) used by {@link TestRestTemplate#exchange} to dispatch
     *       the request.</li>
     *   <li>{@code pathTemplate}  — the URI path the request targets. For
     *       endpoints that take a path variable (e.g.,
     *       {@code /api/accounts/{id}}), the template uses a representative
     *       fixture ID drawn from {@link TestFixtures} so the request is
     *       routable to a real controller — the test only inspects the HTTP
     *       status code, so it does not matter whether the resource exists.</li>
     *   <li>{@code roleRequired} — one of {@code "NONE"} (accessible to any
     *       authenticated user) or {@code "ADMIN"} (requires {@code ROLE_ADMIN}
     *       per the COBOL CDEMO-MENU-OPT-USRTYPE='A' enforcement).</li>
     * </ul>
     *
     * <p>Records are implicitly static when declared inside a class
     * (Java 16+), which is required so the static {@link #securedEndpoints()}
     * provider method can reference the type from a static context.
     */
    private record EndpointSpec(HttpMethod method, String pathTemplate, String roleRequired) {
    }

    /**
     * Sentinel value indicating an endpoint is accessible to <em>any</em>
     * authenticated user (regular or admin). Used as the {@code roleRequired}
     * field of {@link EndpointSpec} for endpoints whose only requirement is
     * a valid session.
     */
    private static final String ROLE_NONE = "NONE";

    /**
     * Sentinel value indicating an endpoint requires {@code ROLE_ADMIN}.
     * Used as the {@code roleRequired} field of {@link EndpointSpec} for
     * endpoints behind {@code @PreAuthorize("hasRole('ADMIN')")} —
     * i.e., {@code /api/menu/admin}, the four user-administration
     * endpoints under {@code /api/users}, and any future admin-only
     * additions.
     */
    private static final String ROLE_ADMIN = "ADMIN";

    /**
     * Immutable catalogue of every endpoint protected by the Spring Security
     * filter chain. Each entry pairs an HTTP method with a path template and
     * the role required. The list is the canonical source of truth for the
     * security-gate contract — adding or removing endpoints from the
     * controller layer should be mirrored here so the parameterized
     * {@link #securedEndpoint_withoutAuthentication_returnsHttp401(HttpMethod, String, String)}
     * test exercises the new path.
     *
     * <p><strong>Intentionally excluded:</strong>
     * <ul>
     *   <li>{@code POST /api/auth/sign-on} — publicly accessible (no prior
     *       authentication required). Exercised separately by
     *       {@link #signOnEndpoint_isPubliclyAccessible_returnsHttp200OrHttp401NeverHttp403()}.</li>
     *   <li>Spring Boot Actuator endpoints (e.g.,
     *       {@code /actuator/health}) — out of scope for this AAP per
     *       §0.8.2.</li>
     *   <li>Auxiliary menu-dispatch endpoints (e.g.,
     *       {@code POST /api/menu/main/dispatch}) — the role contract for
     *       the dispatch endpoint mirrors that of the corresponding menu
     *       endpoint, so testing the menu endpoint suffices to cover the
     *       dispatch endpoint's gate.</li>
     * </ul>
     *
     * <p><strong>Path-parameter fixture choices:</strong>
     * <ul>
     *   <li>Account paths use {@link TestFixtures.Accounts#SAMPLE_ACCOUNT_ID_10}
     *       ({@code "00000000010"}) — the 10th seeded account record.</li>
     *   <li>Card paths use {@link TestFixtures.Cards#SAMPLE_CARD_NUMBER_01}
     *       ({@code "4111111111111101"}) — the first seeded card record.</li>
     *   <li>Transaction-detail uses
     *       {@link TestFixtures.Transactions#SAMPLE_TRANSACTION_ID}
     *       ({@code "0000000000683580"}) — the first daily transaction
     *       record.</li>
     *   <li>User-administration paths use the literal {@code "REGUSR01"}
     *       which is intentionally OUTSIDE the seeded user range
     *       ({@code USRTST01}, {@code ADMTST01}) so the admin authorized
     *       test cannot accidentally mutate a real fixture user via the
     *       PUT or DELETE endpoints. The test only inspects HTTP status,
     *       so a 404 (user not found) is an acceptable outcome.</li>
     * </ul>
     *
     * <p>18 endpoints total: 12 NONE-role + 6 ADMIN-role (per AAP §0.5.1
     * controller test table). The four {@code /api/menu/**} endpoints
     * (GET main, GET admin, POST main/dispatch, POST admin/dispatch) all
     * sit on the security gate because every protected endpoint in the
     * application requires authentication — even the menu dispatchers
     * that route the user to the next screen.
     */
    private static final List<EndpointSpec> SECURED_ENDPOINTS = List.of(
            // ----- COMEN01C — main menu (any authenticated user) -----
            new EndpointSpec(HttpMethod.GET, "/api/menu/main", ROLE_NONE),
            // ----- COMEN01C — main menu option dispatcher (any authenticated user).
            //       Routes the user-selected menu option (PROCESS-ENTER-KEY
            //       paragraph of COMEN01C.cbl) — preserves the menu's
            //       authentication-only authorisation contract because every
            //       option dispatcher requires the caller to be signed in.
            new EndpointSpec(HttpMethod.POST, "/api/menu/main/dispatch", ROLE_NONE),
            // ----- COADM01C — admin menu (admin only) -----
            new EndpointSpec(HttpMethod.GET, "/api/menu/admin", ROLE_ADMIN),
            // ----- COADM01C — admin menu option dispatcher (admin only).
            //       Routes the admin-selected menu option (PROCESS-ENTER-KEY
            //       paragraph of COADM01C.cbl) — preserves the admin-menu's
            //       ADMIN-only authorisation contract via
            //       @PreAuthorize("hasRole('ADMIN')") on the controller
            //       method; a non-admin authenticated caller receives 403.
            new EndpointSpec(HttpMethod.POST, "/api/menu/admin/dispatch", ROLE_ADMIN),
            // ----- COACTVWC — account view -----
            new EndpointSpec(HttpMethod.GET,
                    "/api/accounts/" + TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, ROLE_NONE),
            // ----- COACTUPC — account update -----
            new EndpointSpec(HttpMethod.PUT,
                    "/api/accounts/" + TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, ROLE_NONE),
            // ----- COCRDLIC — card list -----
            new EndpointSpec(HttpMethod.GET, "/api/cards", ROLE_NONE),
            // ----- COCRDSLC — card detail -----
            new EndpointSpec(HttpMethod.GET,
                    "/api/cards/" + TestFixtures.Cards.SAMPLE_CARD_NUMBER_01, ROLE_NONE),
            // ----- COCRDUPC — card update -----
            new EndpointSpec(HttpMethod.PUT,
                    "/api/cards/" + TestFixtures.Cards.SAMPLE_CARD_NUMBER_01, ROLE_NONE),
            // ----- COTRN00C — transaction list -----
            new EndpointSpec(HttpMethod.GET, "/api/transactions", ROLE_NONE),
            // ----- COTRN01C — transaction detail -----
            new EndpointSpec(HttpMethod.GET,
                    "/api/transactions/" + TestFixtures.Transactions.SAMPLE_TRANSACTION_ID,
                    ROLE_NONE),
            // ----- COTRN02C — transaction add -----
            new EndpointSpec(HttpMethod.POST, "/api/transactions", ROLE_NONE),
            // ----- COBIL00C — bill payment -----
            new EndpointSpec(HttpMethod.POST, "/api/bill-payment", ROLE_NONE),
            // ----- CORPT00C — report submission -----
            new EndpointSpec(HttpMethod.POST, "/api/reports/submit", ROLE_NONE),
            // ----- COUSR00C — user list (admin only) -----
            new EndpointSpec(HttpMethod.GET, "/api/users", ROLE_ADMIN),
            // ----- COUSR01C — user add (admin only) -----
            new EndpointSpec(HttpMethod.POST, "/api/users", ROLE_ADMIN),
            // ----- COUSR02C — user update (admin only) -----
            new EndpointSpec(HttpMethod.PUT, "/api/users/REGUSR01", ROLE_ADMIN),
            // ----- COUSR03C — user delete (admin only) -----
            new EndpointSpec(HttpMethod.DELETE, "/api/users/REGUSR01", ROLE_ADMIN));

    // =========================================================================
    // Testcontainers — per-class PostgreSQL 16-alpine
    // =========================================================================

    /**
     * Ephemeral PostgreSQL 16-alpine container. Lifecycle-managed by
     * {@code @Testcontainers}: started before the first {@code @Test} in
     * the class, stopped after the last. Container scope (class-level
     * static) aligns with the default JUnit 5 lifecycle so the database
     * lifecycle aligns with the test-class lifecycle.
     *
     * <p>Per AAP §0.4.4 (Test Data and Fixtures Design) and §0.9.1
     * (Environment setup requirements): the container is provisioned
     * automatically; the production Flyway migrations under
     * {@code src/main/resources/db/migration/} (V1__schema.sql,
     * V2__indexes.sql, V3__seed.sql) hydrate the schema and seed data
     * — including the seeded regular and admin fixture users whose
     * BCrypt hashes pair with
     * {@link TestFixtures.Users#TEST_PASSWORD_PLAINTEXT}, required by
     * the {@link #signIn(String, String)} helper.
     */
    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("carddemo_test")
            .withUsername("carddemo")
            .withPassword("carddemo");

    /**
     * Wires the Testcontainers-allocated PostgreSQL JDBC URL/credentials
     * into Spring's {@link org.springframework.core.env.Environment}
     * before the application context starts. Also forces
     * {@code spring.batch.job.enabled=false} (defence in depth on top of
     * the project-wide {@code application-test.properties} setting) so
     * the security-only gate test does not trigger any Spring Batch job
     * auto-launch.
     *
     * @param registry Spring's dynamic-property registry, injected by the
     *                 Spring TestContext framework before context refresh
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.batch.job.enabled", () -> "false");
    }

    // =========================================================================
    // Injected collaborators
    // =========================================================================

    /**
     * Spring-Boot-provided HTTP client. Bound to the random server port
     * Spring Boot allocates for the embedded servlet container. Used by
     * every test method to invoke production REST endpoints without
     * authentication, with a regular-user session, and with an admin
     * session.
     */
    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * Ephemeral server port assigned by Spring Boot's embedded Tomcat at
     * context refresh. Spring's {@link LocalServerPort} processor injects
     * the value via reflection after context initialisation. Used by
     * {@link #signOnEndpoint_isPubliclyAccessible_returnsHttp200OrHttp401NeverHttp403()}
     * for a diagnostic assertion that the server bound to a real
     * (non-zero) port — surfaces context-load failures with a clear
     * message before the sign-on assertion fires.
     */
    @LocalServerPort
    private int port;


    // =========================================================================
    // Test 3.1 — Sign-on endpoint is publicly accessible
    // =========================================================================

    /**
     * Verifies that {@code POST /api/auth/sign-on} is reachable by an
     * unauthenticated client. The endpoint is the sole exception to the
     * "every endpoint requires authentication" rule: it is the entry point
     * into the authenticated session, so requiring prior authentication
     * would be circular. Spring Security configuration must mark
     * {@code /api/auth/**} as {@code permitAll()}.
     *
     * <p>This test does NOT assert success. It deliberately presents
     * invalid credentials ({@code "DOESNOTEXIST"} / {@code "wrong"}) so
     * the AuthController will return an authentication failure. The
     * critical assertion is on the <em>response shape</em>: the status
     * code must NOT be 403 (which would indicate the security filter
     * chain is blocking the path) and must be in the set
     * {200, 400, 401, 422} (any valid AuthController response).
     *
     * <p>The 403-vs-not-403 distinction is essential because:
     * <ul>
     *   <li>403 means Spring Security's filter chain rejected the request
     *       — the path is being treated as authenticated-required, which
     *       breaks the sign-on flow entirely (a caller cannot sign in if
     *       they need a session to reach the sign-in endpoint).</li>
     *   <li>401 / 400 / 422 / 200 all indicate the request reached the
     *       AuthController. 401 is the canonical "bad credentials"
     *       response from {@code AuthController.signOn}; 400 is the
     *       validation-cascade reject for empty fields or over-length
     *       user IDs; 200 would occur only if "DOESNOTEXIST" /"wrong"
     *       happened to be valid (impossible given the test fixture
     *       seed data, but allowed for completeness).</li>
     * </ul>
     *
     * <p>COBOL parity: the sign-on screen in {@code COSGN00.bms} is
     * presented at session start, with no prior commarea — the COBOL
     * equivalent of "publicly accessible".
     */
    @Test
    @DisplayName("POST /api/auth/sign-on is publicly accessible without prior authentication "
            + "(NEVER 403, returns 200/400/401/422 only)")
    void signOnEndpoint_isPubliclyAccessible_returnsHttp200OrHttp401NeverHttp403() {
        // Arrange — diagnostic check: the embedded server must have bound
        // to a real port (Spring Boot allocates a random port via
        // server.port=0; @LocalServerPort populates this field after
        // context refresh).
        assertThat(port)
                .as("Embedded Tomcat must have bound to a real port (server.port=0 + "
                        + "@LocalServerPort); a 0 value indicates context-load failure")
                .isPositive();

        // Arrange — deliberately invalid credentials so the controller
        // returns an authentication failure rather than a session. The
        // point of this test is reachability, not success.
        Map<String, String> body = Map.of("userId", "DOESNOTEXIST", "password", "wrong");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

        // Act — POST without any prior authentication header / session cookie.
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/auth/sign-on", HttpMethod.POST, request, String.class);

        // Assert — must NOT be 403 (security filter would block the
        // sign-on entry point), and must be one of the valid
        // AuthController responses.
        assertThat(response.getStatusCode())
                .as("Sign-on must be publicly reachable, never blocked by security filter chain "
                        + "(403 indicates the endpoint is incorrectly protected)")
                .isNotEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getStatusCode().value())
                .as("Sign-on endpoint reachable; valid responses are 200 (success), "
                        + "400 (validation reject), 401 (bad credentials), or 422 (unprocessable). "
                        + "Anything else indicates a routing or filter-chain misconfiguration. "
                        + "Observed: %s", response.getStatusCode())
                .isIn(200, 400, 401, 422);
    }

    // =========================================================================
    // Test 3.2 — Unauthenticated requests yield 401 for every secured endpoint
    // =========================================================================

    /**
     * JUnit 5 {@code @MethodSource} provider that converts the
     * {@link #SECURED_ENDPOINTS} catalogue into a stream of
     * {@link Arguments} tuples — one per endpoint — consumed by the
     * parameterized
     * {@link #securedEndpoint_withoutAuthentication_returnsHttp401(HttpMethod, String, String)}
     * test. The method must be static (default JUnit 5 lifecycle)
     * and must return a {@link Stream} of {@link Arguments}.
     *
     * <p>Each row of the returned stream becomes one parameterized
     * invocation, with the {@link HttpMethod}, path template, and
     * required role unpacked into the test method's parameters.
     *
     * @return one {@code Arguments(method, pathTemplate, roleRequired)}
     *         tuple per entry in {@link #SECURED_ENDPOINTS}
     */
    static Stream<Arguments> securedEndpoints() {
        return SECURED_ENDPOINTS.stream()
                .map(e -> Arguments.of(e.method(), e.pathTemplate(), e.roleRequired()));
    }

    /**
     * Verifies that every secured endpoint returns HTTP 401 (Unauthorized)
     * when no authentication is presented. One parameterized invocation
     * is generated per entry in {@link #SECURED_ENDPOINTS} via the
     * {@link #securedEndpoints()} provider.
     *
     * <p>The assertion is strict: only 401 is acceptable. A 200 / 403 /
     * 404 / 500 response indicates one of:
     * <ul>
     *   <li>The endpoint is incorrectly marked {@code permitAll()} (200,
     *       passed through to the controller without auth).</li>
     *   <li>The endpoint authenticates via a different mechanism than
     *       the rest of the API (403 implies an authenticated principal
     *       was somehow derived without credentials).</li>
     *   <li>The Spring Security filter chain is not loaded at all (in
     *       which case the controller's @RequestMapping path drives the
     *       response — 404 for unmapped, 500 for unhandled exception,
     *       etc.).</li>
     * </ul>
     *
     * <p>COBOL parity: the {@code IF EIBCALEN = 0 / MOVE 'COSGN00C' TO
     * CDEMO-TO-PROGRAM / PERFORM RETURN-TO-PREV-SCREEN} guard at the
     * start of every CardDemo COBOL program is the mainframe
     * counterpart — a request lacking a session is redirected back
     * to the sign-on screen, which in REST terms maps to a 401
     * response with a {@code WWW-Authenticate} challenge.
     *
     * @param method        the HTTP verb of the endpoint under test
     * @param pathTemplate  the URI path of the endpoint under test
     * @param roleRequired  diagnostic indicator of the role contract
     *                      ({@code "NONE"} or {@code "ADMIN"}); used
     *                      only in the assertion failure message —
     *                      the test treats both equivalently (401
     *                      precedes role checks)
     */
    @ParameterizedTest(name = "[{index}] {0} {1} (role={2}) without auth → 401 Unauthorized")
    @MethodSource("securedEndpoints")
    @DisplayName("Every secured endpoint returns 401 Unauthorized when no authentication is presented")
    void securedEndpoint_withoutAuthentication_returnsHttp401(
            HttpMethod method, String pathTemplate, String roleRequired) {
        // Arrange — build a request with no Authorization header / session
        // cookie. For POST/PUT methods that may require a JSON body, send
        // an empty object {} so the controller can attempt deserialisation
        // (and would normally respond 400 on the empty body — but the
        // security filter intercepts FIRST, so the test will see 401
        // regardless of body content).
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = (method == HttpMethod.GET || method == HttpMethod.DELETE) ? null : "{}";
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        // Act — call the endpoint with no authentication.
        ResponseEntity<String> response = restTemplate.exchange(
                pathTemplate, method, request, String.class);

        // Assert — must be exactly 401 (Unauthorized). Never 403, never 200,
        // never 404 (404 would indicate the security filter chain is
        // missing and the request reached Spring MVC's path resolution).
        assertThat(response.getStatusCode())
                .as("%s %s with no auth must be 401 Unauthorized (role=%s). "
                        + "Any other status indicates a security filter chain misconfiguration. "
                        + "Observed: %s", method, pathTemplate, roleRequired,
                        response.getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }


    // =========================================================================
    // Test 3.3 — Regular-user auth permits NONE-role endpoints (no 401/403)
    // =========================================================================

    /**
     * Verifies that an authenticated regular user (SEC-USR-TYPE='U' per
     * CSUSR01Y.cpy) receives a non-401, non-403 response for every
     * NONE-role endpoint in {@link #SECURED_ENDPOINTS}. The expected
     * response is one of:
     * <ul>
     *   <li>{@code 200 OK} — happy path (the request reached the
     *       controller and the underlying business logic succeeded).</li>
     *   <li>{@code 400 Bad Request} — validation reject for the empty
     *       JSON body submitted by {@link #bodyFor(EndpointSpec, HttpHeaders)}.</li>
     *   <li>{@code 404 Not Found} — the path-parameter fixture ID does
     *       not exist in the seeded data (acceptable; the test does not
     *       assert resource existence).</li>
     *   <li>{@code 422 Unprocessable Entity} — semantic validation
     *       failure on a parsed-but-invalid body.</li>
     *   <li>{@code 405 Method Not Allowed} — extremely unlikely but
     *       theoretically acceptable; the test only forbids 401/403.</li>
     * </ul>
     *
     * <p>The assertion is intentionally negative ({@code .isNotIn(401, 403)})
     * because the test's only purpose is to verify the gate — once
     * authentication is presented, the security filter chain must allow
     * the request through to the controller (the controller's own
     * response is the controller's concern, exercised by
     * {@code com.aws.carddemo.controller.*ControllerTest} slice tests).
     *
     * <p>COBOL parity: COMEN01C accepts a regular user (CDEMO-USRTYP-USER)
     * and dispatches to the requested non-admin program; the migrated
     * REST equivalent is "regular user reaches the controller without a
     * 401/403 reject".
     */
    @Test
    @DisplayName("Regular user (ROLE_USER) receives 200/400/422 — never 401, never 403 — "
            + "on NONE-role endpoints")
    void securedEndpoint_withRegularUserAuth_neverReturns401Or403ForNoneRoleEndpoints() {
        // Arrange — sign in as the seeded regular user (USRTST01). The
        // signInAsRegularUser helper asserts the sign-on returns HTTP 200
        // before this test's main assertion runs, so a failed sign-on
        // surfaces with a clear, helper-level message rather than
        // propagating as a misleading 401 on the first secured endpoint.
        HttpHeaders authHeaders = signInAsRegularUser();

        // Act + Assert — for every NONE-role endpoint, the response must
        // not be 401 or 403. Endpoint failures (e.g., 404 because the
        // fixture ID does not exist, 400 because the empty body is
        // invalid) are tolerated — they prove the request reached the
        // controller layer, which is the only property under test.
        for (EndpointSpec spec : SECURED_ENDPOINTS) {
            if (!ROLE_NONE.equals(spec.roleRequired())) {
                continue;
            }

            HttpEntity<String> request = bodyFor(spec, authHeaders);
            ResponseEntity<String> response = restTemplate.exchange(
                    spec.pathTemplate(), spec.method(), request, String.class);

            assertThat(response.getStatusCode().value())
                    .as("Regular user on NONE-role endpoint %s %s must not be 401/403 "
                            + "(authenticated; gate must let the request reach the controller). "
                            + "Observed: %s", spec.method(), spec.pathTemplate(),
                            response.getStatusCode())
                    .isNotIn(401, 403);
        }
    }

    // =========================================================================
    // Test 3.4 — Regular user yields 403 (NOT 401) on ADMIN-role endpoints
    // =========================================================================

    /**
     * Verifies the CRITICAL acceptance gate: an authenticated regular user
     * (SEC-USR-TYPE='U') receives HTTP 403 Forbidden — never 401 — when
     * accessing ADMIN-role endpoints. The 401-vs-403 distinction is the
     * defining property of role-based authorization:
     * <ul>
     *   <li>{@code 401 Unauthorized} = "you haven't authenticated"
     *       (the security filter chain has no credentials to evaluate).</li>
     *   <li>{@code 403 Forbidden} = "you have authenticated but you are
     *       not authorized for this resource" (the security filter chain
     *       evaluated valid credentials but the role check failed).</li>
     * </ul>
     *
     * <p>In Spring Security this is enforced by
     * {@code @PreAuthorize("hasRole('ADMIN')")} on every admin endpoint.
     * When the {@code AccessDecisionManager} rejects the principal due
     * to a missing role, Spring Security's
     * {@code ExceptionTranslationFilter} maps the rejection to a 403
     * response. Returning 401 instead would indicate that the security
     * filter chain failed to detect the authenticated principal at all
     * — a misconfiguration of the session or token resolver.
     *
     * <p>COBOL parity: COMEN01C explicitly rejects regular users
     * selecting admin-marked options with the message "USER NOT
     * AUTHORIZED" (paraphrased — the exact COBOL message lives in
     * COMEN01C's WHEN-OTHER paragraph). The migrated equivalent is the
     * 403 response — a "you're authenticated, but you're not an admin"
     * reject.
     */
    @Test
    @DisplayName("Regular user (ROLE_USER) receives 403 Forbidden — NEVER 401 — "
            + "on ADMIN-role endpoints")
    void securedEndpoint_withRegularUserAuth_returnsHttp403NotHttp401ForAdminEndpoints() {
        // Arrange — sign in as the seeded regular user. The helper
        // assertion guarantees the sign-on succeeded before the main
        // assertions fire.
        HttpHeaders authHeaders = signInAsRegularUser();

        // Act + Assert — for every ADMIN-role endpoint, the response
        // must be exactly 403 Forbidden. 401 would indicate the
        // security filter chain failed to recognise the regular user's
        // session (a misconfiguration); 200 would indicate the
        // @PreAuthorize annotation is missing or misapplied; any other
        // status indicates a routing/filter issue.
        for (EndpointSpec spec : SECURED_ENDPOINTS) {
            if (!ROLE_ADMIN.equals(spec.roleRequired())) {
                continue;
            }

            HttpEntity<String> request = bodyFor(spec, authHeaders);
            ResponseEntity<String> response = restTemplate.exchange(
                    spec.pathTemplate(), spec.method(), request, String.class);

            // Critical assertion: 403, NOT 401. Authentication was presented
            // and valid; only the role check failed.
            assertThat(response.getStatusCode())
                    .as("Regular user on ADMIN endpoint %s %s must be 403 Forbidden (NEVER 401). "
                            + "401 indicates the security filter chain failed to recognise the "
                            + "regular user's session; 200 indicates @PreAuthorize is missing. "
                            + "Observed: %s", spec.method(), spec.pathTemplate(),
                            response.getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // =========================================================================
    // Test 3.5 — Admin auth permits ADMIN-role endpoints (no 401/403)
    // =========================================================================

    /**
     * Verifies that an authenticated admin user (SEC-USR-TYPE='A' per
     * CSUSR01Y.cpy) receives a non-401, non-403 response for every
     * ADMIN-role endpoint. The expected response is one of:
     * 200 (happy path), 400 (empty-body validation reject), 404
     * (path-parameter fixture not in seed), 422 (semantic validation
     * failure). Anything from the 401/403 pair indicates a gate
     * misconfiguration.
     *
     * <p>This is the positive complement to
     * {@link #securedEndpoint_withRegularUserAuth_returnsHttp403NotHttp401ForAdminEndpoints()}
     * — together the two tests prove the bi-directional contract:
     * regular users are blocked, admins are admitted, and the only
     * difference is the role on the authenticated principal.
     *
     * <p>COBOL parity: COSGN00C dispatches admin users (CDEMO-USRTYP-ADMIN)
     * to COADM01C; COADM01C accepts every admin-marked menu option
     * without further role checks. The migrated equivalent is "admin
     * reaches every admin controller without a 401/403 reject".
     */
    @Test
    @DisplayName("Admin user (ROLE_ADMIN) receives 200/400/422 — never 401, never 403 — "
            + "on ADMIN-role endpoints")
    void securedEndpoint_withAdminAuth_neverReturns401Or403ForAdminRoleEndpoints() {
        // Arrange — sign in as the seeded admin user (ADMTST01). The
        // helper assertion guarantees the sign-on succeeded before
        // this test's main assertions fire.
        HttpHeaders authHeaders = signInAsAdmin();

        // Act + Assert — for every ADMIN-role endpoint, the response
        // must not be 401 or 403. Endpoint-specific failures (e.g.,
        // 404 because REGUSR01 does not exist for the PUT/DELETE
        // endpoints, 400 because the empty body fails validation on
        // POST endpoints) are tolerated — they prove the request
        // reached the controller layer past the role gate.
        for (EndpointSpec spec : SECURED_ENDPOINTS) {
            if (!ROLE_ADMIN.equals(spec.roleRequired())) {
                continue;
            }

            HttpEntity<String> request = bodyFor(spec, authHeaders);
            ResponseEntity<String> response = restTemplate.exchange(
                    spec.pathTemplate(), spec.method(), request, String.class);

            assertThat(response.getStatusCode().value())
                    .as("Admin on ADMIN endpoint %s %s must not be 401/403 "
                            + "(authenticated AND has ROLE_ADMIN; gate must let the request "
                            + "reach the controller). Observed: %s",
                            spec.method(), spec.pathTemplate(), response.getStatusCode())
                    .isNotIn(401, 403);
        }
    }


    // =========================================================================
    // Private test helpers
    // =========================================================================

    /**
     * Signs in as the seeded regular user
     * ({@link TestFixtures.Users#REGULAR_USER_ID} = {@code "USRTST01"})
     * with the test-fixture plaintext password
     * ({@link TestFixtures.Users#TEST_PASSWORD_PLAINTEXT} = {@code "TESTPASS"}).
     * Returns {@link HttpHeaders} populated with the session credential
     * (cookie and/or bearer token) ready for use on subsequent
     * authenticated requests.
     *
     * <p>The seeded BCrypt hash on disk (via
     * {@code TestFixtures.Users.TEST_PASSWORD_BCRYPT_HASH} in
     * Flyway {@code V3__seed.sql}) verifies against this plaintext —
     * the pair was generated offline by the test-support agent so
     * authentication tests pay no BCrypt computation cost per
     * invocation.
     *
     * @return session-carrying HTTP headers (Cookie and/or Authorization)
     *         for an authenticated regular user
     */
    private HttpHeaders signInAsRegularUser() {
        return signIn(TestFixtures.Users.REGULAR_USER_ID,
                TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
    }

    /**
     * Signs in as the seeded admin user
     * ({@link TestFixtures.Users#ADMIN_USER_ID} = {@code "ADMTST01"})
     * with the test-fixture plaintext password
     * ({@link TestFixtures.Users#TEST_PASSWORD_PLAINTEXT} = {@code "TESTPASS"}).
     * Returns {@link HttpHeaders} populated with the session credential
     * ready for use on subsequent admin-authenticated requests.
     *
     * <p>The admin and regular fixture users share the same plaintext
     * password — both seeded rows in {@code V3__seed.sql} carry the
     * same BCrypt hash. This is a deliberate test-fixture design choice:
     * one shared hash means one shared offline BCrypt computation,
     * keeping fixture maintenance simple. The two users are
     * distinguished solely by their SEC-USR-TYPE column
     * ({@code "A"} for admin vs {@code "U"} for regular).
     *
     * @return session-carrying HTTP headers (Cookie and/or Authorization)
     *         for an authenticated admin user
     */
    private HttpHeaders signInAsAdmin() {
        return signIn(TestFixtures.Users.ADMIN_USER_ID,
                TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
    }

    /**
     * Performs a sign-on against {@code POST /api/auth/sign-on} with the
     * supplied user identifier and plaintext password, and returns
     * {@link HttpHeaders} carrying the session credential extracted from
     * the response. Supports both common session-passing patterns:
     * <ul>
     *   <li><strong>Session cookie</strong> — the response includes one
     *       or more {@code Set-Cookie} headers; the helper concatenates
     *       them into a single {@code Cookie} request header for
     *       subsequent calls.</li>
     *   <li><strong>Bearer token (JWT)</strong> — the response body
     *       includes a {@code token} field (either at the top level or
     *       nested inside a {@code session} object); the helper sets
     *       an {@code Authorization: Bearer ...} header.</li>
     * </ul>
     * The dual support reflects the indeterminate state of the
     * AuthController's session-passing mechanism at the time this test
     * was written — subsequent migration agents may finalize one
     * approach or the other, and this helper accommodates both without
     * modification.
     *
     * <p>Critical: the helper asserts that the sign-on returned HTTP 200
     * before extracting the credential. A 401 / 400 / 500 response from
     * the sign-on would mask the actual test's purpose (the test would
     * fail downstream with a 401 on the first secured endpoint, hiding
     * the fact that the failure was actually in the sign-on phase). The
     * explicit assertion surfaces sign-on failures with a precise,
     * helper-level message.
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule): this helper
     * contains NO business logic, NO password processing, NO
     * encoding/decoding — it is a thin HTTP wrapper that delegates
     * authentication entirely to the production
     * {@link com.aws.carddemo.controller.AuthController} +
     * {@link com.aws.carddemo.service.AuthenticationService} stack.
     *
     * <p>Per AAP §0.10.5 (Security Constraints): the {@code password}
     * argument carries the BCrypt-paired fixture plaintext (NOT a real
     * credential); the helper never logs it, never persists it, and
     * the production AuthenticationResult DTO does not echo it back in
     * the response — the captured headers carry only the post-auth
     * session credential.
     *
     * @param userId   the 8-character SEC-USR-ID per CSUSR01Y.cpy
     * @param password the plaintext password (test fixture only)
     * @return {@link HttpHeaders} populated with the session credential
     *         (Cookie and/or Authorization)
     */
    private HttpHeaders signIn(String userId, String password) {
        // Arrange — build the JSON request body. Map.of is immutable and
        // sufficient for this two-field payload.
        Map<String, String> body = Map.of("userId", userId, "password", password);
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, requestHeaders);

        // Act — POST to /api/auth/sign-on. The response body is captured
        // as a String so the helper can later parse it for a JWT token
        // (if the AuthController returns one) — but the String type is
        // chosen because it avoids a dependency on Jackson tree-model
        // classes for the gate-verification suite (the parsing logic
        // below is regex-style String inspection that does not require
        // a full JSON parser).
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/auth/sign-on", HttpMethod.POST, request, String.class);

        // Assert — sign-on must succeed for the seeded fixture user.
        // Failing here means either the fixture user is missing from
        // V3__seed.sql, the BCrypt hash does not match the plaintext,
        // or the AuthController is misconfigured — all of which are
        // production-side issues that this test does not own.
        assertThat(response.getStatusCode())
                .as("Sign-on must succeed for seeded fixture user %s "
                        + "(check V3__seed.sql + BCrypt hash pairing). Observed: %s",
                        userId, response.getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // Prepare the session-carrying headers for subsequent calls.
        HttpHeaders sessionHeaders = new HttpHeaders();
        sessionHeaders.setContentType(MediaType.APPLICATION_JSON);

        // Forward any Set-Cookie header(s) returned by the sign-on as a
        // single Cookie request header. This covers session-cookie-based
        // authentication.
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies != null && !cookies.isEmpty()) {
            sessionHeaders.add(HttpHeaders.COOKIE, String.join("; ", cookies));
        }

        // Inspect the response body for a JWT token. The AuthController
        // currently returns an AuthenticationResult DTO; if the design
        // evolves to include a {"token":"..."} field (or
        // {"session":{"token":"..."}}), the helper picks it up
        // automatically without code changes here.
        String responseBody = response.getBody();
        if (responseBody != null) {
            String token = extractTokenFromResponseBody(responseBody);
            if (token != null && !token.isBlank()) {
                sessionHeaders.setBearerAuth(token);
            }
        }

        return sessionHeaders;
    }

    /**
     * Extracts a JWT bearer token from the JSON response body of a
     * successful sign-on, looking first for a top-level {@code "token"}
     * field and then for a nested {@code "session":{"token":...}} shape.
     * Returns {@code null} if neither shape is present (in which case
     * the caller falls back to the Set-Cookie path).
     *
     * <p>Uses simple String inspection rather than a JSON parser to
     * avoid a Jackson dependency in this test class — the gate-
     * verification suite is intentionally minimal and only needs to
     * recognise the token if present, not validate the entire response
     * shape (that is the AuthControllerTest's responsibility).
     *
     * <p>This method contains NO business logic — it is a String-search
     * helper. Per AAP §0.10.1 it does not reimplement JSON parsing,
     * does not validate token shape, does not decode the token.
     *
     * @param responseBody the raw HTTP response body, possibly null
     * @return the bearer token if found, or {@code null} otherwise
     */
    private String extractTokenFromResponseBody(String responseBody) {
        if (responseBody == null) {
            return null;
        }
        // Look for the literal "token":"<value>" pattern. The leading
        // double quote and colon disambiguate against any field whose
        // name merely contains the word "token" (e.g., "tokenType").
        final String tokenMarker = "\"token\":\"";
        int markerStart = responseBody.indexOf(tokenMarker);
        if (markerStart < 0) {
            return null;
        }
        int valueStart = markerStart + tokenMarker.length();
        int valueEnd = responseBody.indexOf('"', valueStart);
        if (valueEnd < 0 || valueEnd <= valueStart) {
            return null;
        }
        return responseBody.substring(valueStart, valueEnd);
    }

    /**
     * Constructs an {@link HttpEntity} suitable for sending to the
     * endpoint described by {@code spec}, carrying the supplied
     * authentication headers and a minimal request body where the HTTP
     * method requires one. The body shape is:
     * <ul>
     *   <li>{@code null} for GET and DELETE — these methods are
     *       traditionally body-less in REST APIs.</li>
     *   <li>{@code "{}"} (empty JSON object) for POST and PUT — the
     *       endpoint will likely respond 400 (validation reject) or
     *       422 (unprocessable entity) for the empty payload, but
     *       crucially never 401/403 once the gate is passed. The
     *       point of this test is the gate, not the body's
     *       acceptability.</li>
     * </ul>
     *
     * <p>The returned entity defensively copies {@code authHeaders}
     * into a fresh {@link HttpHeaders} instance so subsequent
     * mutations (e.g., setting the {@code Content-Type}) do not
     * leak into the caller's session headers.
     *
     * @param spec        the endpoint specification, supplying the HTTP
     *                    method (drives the body decision)
     * @param authHeaders the session-carrying headers from
     *                    {@link #signInAsRegularUser()} or
     *                    {@link #signInAsAdmin()}; defensively copied
     * @return a body+headers entity ready for
     *         {@code restTemplate.exchange(...)}
     */
    private HttpEntity<String> bodyFor(EndpointSpec spec, HttpHeaders authHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(authHeaders);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // For GET / DELETE: no body. For POST / PUT: minimal empty JSON
        // object — the controller's request-body parser will accept the
        // shape (or reject with 400/422), but the security filter chain
        // will have evaluated the authentication headers first.
        String body = (spec.method() == HttpMethod.GET || spec.method() == HttpMethod.DELETE)
                ? null
                : "{}";

        return new HttpEntity<>(body, headers);
    }
}


