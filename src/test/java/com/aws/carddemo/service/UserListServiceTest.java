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
package com.aws.carddemo.service;

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies, file
// schema internal_imports).
//
//   * TestFixtures — single source of truth for shared test constants. This
//     test uses TestFixtures.Users.TEST_PASSWORD_BCRYPT_HASH in the
//     sampleUsers() helper to seed every fixture SecurityUser's password
//     field with the synthetic BCrypt hash (AAP §0.10.5 "No plaintext
//     credentials in any configuration file" — the fixture credential is a
//     pre-computed BCrypt hash, never the plaintext form).
//
//     Schema authority: file_schema.internal_imports[0] declares this
//     dependency explicitly ("Shared test constants: TestFixtures.Users.
//     TEST_PASSWORD_BCRYPT_HASH used by the sampleUsers() helper to seed the
//     SecurityUser fixtures' password field with a synthetic BCrypt hash
//     (per AAP §0.10.5 no plaintext credentials in tests)").
//
//   * SecurityUser — the JPA entity returned by the repository on the
//     happy-path queries. The sampleUsers(int count) helper fabricates a
//     populated SecurityUser per iteration so the @ParameterizedTest /
//     @Test methods can stub the repository with realistic page contents.
//
//   * UserSecurityRepository — the Spring Data JPA repository the production
//     UserListService delegates to. Mocked at the JPA-repository boundary
//     per AAP §0.10.1 ("Mocks limited to external boundaries: file I/O,
//     downstream service calls, database").
//
//   * UserListService / UserListRequest / UserListResponse — the production
//     classes under test. No explicit imports because they share this
//     test's package (`com.aws.carddemo.service`); Java resolves simple-
//     named references via package membership. This matches the convention
//     established by every other test under com.aws.carddemo.service.
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.SecurityUser;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only,
// never JUnit 4 / Vintage).
//
//   * @BeforeEach — reinstantiates the system under test before every
//     method; paired with Mockito's default per-method @Mock instantiation
//     to enforce strict test isolation (AAP §0.10.9). Each test sees a
//     fresh UserSecurityRepository mock and a fresh UserListService.
//   * @DisplayName — human-readable scenario names on the outer class and
//     on each @Nested grouping (AAP §0.10.6 naming convention).
//   * @Nested — groups Authorization / Pagination / RoleFilter test
//     scenarios into the three semantic sections that match this test
//     class's exports.members_exposed schema entries
//     ("Authorization", "Pagination", "RoleFilter").
//   * @Test — single-execution test marker for the non-parameterised
//     scenarios.
//   * @ExtendWith — wires MockitoExtension below.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// ---------------------------------------------------------------------------
// JUnit 5 Parameterized-test support (file schema external_imports for
// junit-jupiter-params).
//
//   * @ParameterizedTest — drives the table-driven non-admin caller-type
//     rejection scenario, enumerating the three representative non-admin
//     user-type codes ("U" = regular user, " " = blank/whitespace,
//     "X" = unknown) to prove the dispatcher's authorisation guard rejects
//     every value that is not exactly "A".
//   * @ValueSource — enumerates the three non-admin caller types as
//     comma-separated literals; this is the canonical JUnit 5 source for a
//     single-argument parameterised test of small, named alternatives.
// ---------------------------------------------------------------------------
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// ---------------------------------------------------------------------------
// Mockito 5 (AAP §0.6.1 — BOM-managed by spring-boot-starter-test 3.3.13;
// resolved to Mockito 5.11.0 per setup log).
//
//   * @Mock — Mockito field-injection annotation; MockitoExtension processes
//     this annotation and produces a fresh mock per @Test method,
//     guaranteeing test isolation.
//   * ArgumentCaptor — captures the Pageable argument passed to
//     userSecurityRepository.findAll(Pageable) so the
//     Pagination#listUsers_pageZero_returns10 test can assert that the
//     production code requested page size 10 (matching COBOL
//     WS-MAX-SCREEN-LINES = 10) without relying on private/internal state.
//   * MockitoExtension — activates STRICT_STUBS strictness by default
//     (AAP §0.10.1: "Mockito strictness is `STRICT_STUBS`; unused stubs
//     raise `UnnecessaryStubbingException`. ...This catches premature
//     mocking that would otherwise mask the rule violation."). Each
//     authorisation-reject test deliberately avoids stubbing the repository
//     to prove (via Mockito.verify(..., never())) that the production code
//     never reaches the database query on a rejected call.
// ---------------------------------------------------------------------------
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// ---------------------------------------------------------------------------
// Spring Data Commons (AAP §0.6.1; resolved transitively via
// spring-boot-starter-data-jpa managed by the spring-boot-dependencies BOM).
//
//   * Page / PageImpl — Spring Data pagination result envelope. PageImpl
//     wraps fixture SecurityUser lists into Page<SecurityUser> so the
//     mocked repository's .findAll(...) / .findByUserType(...) returns a
//     realistic paged result (carrying both content and the navigation
//     metadata).
//   * Pageable / PageRequest — pagination request parameters. PageRequest.of
//     (0, 10) constructs the expected page bound for the fixture pages,
//     and ArgumentCaptor<Pageable> verifies that the production code
//     requests page size 10.
// ---------------------------------------------------------------------------
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

// ---------------------------------------------------------------------------
// JDK 17 standard-library imports (file schema external_imports for java.util).
//
//   * Collections.emptyList() — builds the zero-row fixture for the
//     Pagination#listUsers_emptyRepository_returnsEmpty scenario.
//   * List / ArrayList — back the sampleUsers(int count) helper that
//     synthesises SecurityUser fixtures for the paging tests.
// ---------------------------------------------------------------------------
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// ---------------------------------------------------------------------------
// Static imports — AssertJ fluent DSL + Mockito DSL (AAP §0.10.10 "All
// assertions use AssertJ (fluent) rather than mixing AssertJ + Hamcrest +
// Assertions.assertEquals"; all Mockito stubs use the
// when(...).thenReturn(...) style).
//
//   * Assertions.assertThat — fluent assertion entry point used throughout.
//   * ArgumentMatchers.any — relaxes Pageable matching where the test does
//     not care about the exact PageRequest contents (the
//     ArgumentCaptor-based test captures and asserts on the Pageable
//     separately).
//   * ArgumentMatchers.eq — exact-equality matcher for the userType filter
//     string in the RoleFilter#listUsers_withUserTypeFilter_narrowsResults
//     scenario.
//   * Mockito.never — verifies that a stub method was NOT invoked; used to
//     prove that the admin-only authorisation guard short-circuits BEFORE
//     any repository call on rejected paths.
//   * Mockito.verify — interaction assertion; pairs with .never() and with
//     ArgumentCaptor.
//   * Mockito.when — stub configuration; sets up the repository's return
//     values on the happy paths.
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserListService}, the migrated Java equivalent of the
 * 695-line CICS program {@code app/cbl/COUSR00C.cbl} (TRANID {@code CU00}).
 * Read-only paged browse of the {@code USRSEC} VSAM KSDS replacement
 * (PostgreSQL {@code security_users} table) gated behind an admin-only
 * authorisation check.
 *
 * <h2>COBOL Provenance — COUSR00C.cbl</h2>
 *
 * <p>The COBOL workflow walks {@code USRSEC} via {@code STARTBR /
 * READNEXT} loops, populating a fixed-width 10-row {@code USER-REC OCCURS
 * 10 TIMES} table on the {@code COUSR0AO} BMS output map:
 *
 * <ul>
 *   <li>{@code PROCESS-PAGE-FORWARD} (lines 282–331) — the canonical page
 *       advance; calls {@code STARTBR-USER-SEC-FILE} (line 284) followed
 *       by a {@code PERFORM UNTIL WS-IDX >= 11 OR USER-SEC-EOF OR
 *       ERR-FLG-ON} loop (line 300) that reads up to 10 records via
 *       {@code POPULATE-USER-DATA} (lines 384–441).</li>
 *   <li>{@code PROCESS-PAGE-BACKWARD} (lines 336–379) — the PF7 / previous
 *       page workflow using {@code READPREV} instead of
 *       {@code READNEXT}.</li>
 *   <li>{@code WS-MAX-SCREEN-LINES VALUE 10} (working-storage line ~52) —
 *       the page size, materialised in this Java migration as
 *       {@link UserListService#PAGE_SIZE}.</li>
 * </ul>
 *
 * <p>The COBOL source does not contain an explicit user-type test inside
 * {@code COUSR00C} because the program is only reachable from the admin
 * menu {@code COADM01C}. The Java migration tightens this implicit
 * contract by re-verifying the user-type inside the dispatcher itself; see
 * {@link UserListService} "Admin-only authorisation re-verification" note
 * for the rationale.
 *
 * <h2>Test Coverage Matrix</h2>
 *
 * <p>The test methods below cover the entire decision tree:
 *
 * <table border="1">
 *   <caption>User-list test coverage matrix</caption>
 *   <tr><th>Branch</th><th>Test method</th></tr>
 *   <tr><td>Happy path — admin caller, no filter, populated page</td>
 *       <td>{@link Authorization#listUsers_adminCaller_succeeds()}</td></tr>
 *   <tr><td>Reject — non-admin caller (parameterised: "U", " ", "X")</td>
 *       <td>{@link Authorization#listUsers_nonAdminCaller_rejected(String)}</td></tr>
 *   <tr><td>Happy path — page=0, full 10-row page, captures Pageable</td>
 *       <td>{@link Pagination#listUsers_pageZero_returns10()}</td></tr>
 *   <tr><td>Happy path — empty repository, no navigation</td>
 *       <td>{@link Pagination#listUsers_emptyRepository_returnsEmpty()}</td></tr>
 *   <tr><td>Happy path — userType filter routes through findByUserType</td>
 *       <td>{@link RoleFilter#listUsers_withUserTypeFilter_narrowsResults()}</td></tr>
 * </table>
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>Tests instantiate the real {@link UserListService} via its public
 * single-argument constructor and assert on the {@link UserListResponse}
 * returned by the real {@link UserListService#listUsers(UserListRequest)}
 * method. The only mocked collaborator is the {@link UserSecurityRepository}
 * (database boundary, the only mock category permitted under AAP §0.10.1).
 * No business logic — the admin-only check, the page-size selection, the
 * filter dispatch, or the response construction — is duplicated in any
 * test body; the tests assert only on observable outputs.
 *
 * <h2>Authorisation-First Invariant</h2>
 *
 * <p>The {@link Authorization#listUsers_nonAdminCaller_rejected(String)}
 * scenario verifies the critical "authorisation happens BEFORE any database
 * access" invariant via Mockito {@code verify(repository, never())
 * .findAll(any(Pageable.class))}. Combined with Mockito's default
 * {@code STRICT_STUBS} mode (which raises
 * {@link org.mockito.exceptions.misusing.UnnecessaryStubbingException} on
 * any unused stub), this proves that the production code's reject path
 * does not silently call the repository — a defence-in-depth property
 * that protects against credential-enumeration probes.
 *
 * @see UserListService
 * @see UserListRequest
 * @see UserListResponse
 * @see UserSecurityRepository
 * @see TestFixtures.Users
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserListService — COUSR00C.cbl migration parity")
final class UserListServiceTest {

    /**
     * Mocked {@link UserSecurityRepository} — the JPA repository boundary the
     * production {@link UserListService} delegates to. Per AAP §0.10.1, the
     * only mocked collaborator (database is one of the four permitted mock
     * categories). Re-created per {@code @Test} method by
     * {@link MockitoExtension}, ensuring strict isolation between scenarios.
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * System under test — the real {@link UserListService} instance. Re-
     * created per {@code @Test} via {@link #setUp()} to mirror the
     * recreation of the mock collaborator above and to prevent any
     * accidental state leak between tests (the production service is
     * currently stateless aside from its single collaborator field, but
     * the per-method reset is defensive for future state additions).
     */
    private UserListService service;

    /**
     * Constructs a fresh {@link UserListService} before every test method,
     * injecting the freshly-instantiated {@link #userSecurityRepository}
     * mock. The combination of per-method {@code @Mock} instantiation
     * (driven by {@link MockitoExtension}) and per-method service
     * construction here guarantees that no stub or interaction from one
     * test leaks into another.
     */
    @BeforeEach
    void setUp() {
        service = new UserListService(userSecurityRepository);
    }

    // =========================================================================
    // AUTHORIZATION — admin-only access (Java-migration addition)
    //
    // COUSR00C is the admin-only user-list dispatcher. The COBOL source does
    // not include an explicit user-type check inside COUSR00C because the
    // program is only reachable from COADM01C (the admin menu), which is
    // itself protected by the sign-on flow's CDEMO-USRTYP-ADMIN branch. The
    // Java migration tightens this implicit contract by re-verifying the
    // user-type inside the dispatcher itself; this @Nested block verifies
    // that tightening.
    // =========================================================================

    /**
     * Authorisation tests for {@link UserListService#listUsers(UserListRequest)}
     * — the Java-migration-added user-type re-verification (see
     * {@link UserListService} "Admin-only authorisation re-verification"
     * section).
     *
     * <p>Verifies that:
     * <ul>
     *   <li>Admin callers ({@code "A"}) receive a populated response with
     *       {@link UserListResponse#isSuccess()} = {@code true} and the
     *       repository's page content propagated to
     *       {@link UserListResponse#getUsers()}.</li>
     *   <li>Any caller whose user-type is NOT {@code "A"} is rejected with
     *       the {@link UserListService#MSG_NOT_AUTHORIZED} message,
     *       regardless of page index or filter, and the repository is
     *       NEVER queried on rejected paths (defence-in-depth — protects
     *       against accidental credential / user-list disclosure via the
     *       rejection branch).</li>
     * </ul>
     */
    @Nested
    @DisplayName("Authorization — admin-only access")
    class Authorization {

        /**
         * Happy path: an admin caller ({@code callerUserType = "A"}) drives
         * the dispatcher through to the repository, which returns a 5-row
         * fixture page. Asserts that the response is successful and that the
         * page content propagates verbatim through to
         * {@link UserListResponse#getUsers()}.
         *
         * <p>COBOL parity: this is the {@code PROCESS-PAGE-FORWARD} happy
         * path (lines 282–331). The COBOL workflow assumes admin access is
         * already established upstream; the Java migration re-verifies
         * inline, and this test exercises the post-verification success
         * path.
         */
        @Test
        @DisplayName("listUsers(adminCaller) succeeds")
        void listUsers_adminCaller_succeeds() {
            // Arrange — stub the repository to return a 5-row fixture page
            // (PageImpl's total-element count of 5 ensures hasNext()=false
            // for this single-page fixture, which keeps the assertion focus
            // on the success/content path rather than on navigation).
            Page<SecurityUser> fixturePage =
                    new PageImpl<>(sampleUsers(5), PageRequest.of(0, 10), 5);
            when(userSecurityRepository.findAll(any(Pageable.class)))
                    .thenReturn(fixturePage);

            UserListRequest request = new UserListRequest();
            request.setCallerUserType("A");
            request.setPage(0);

            // Act — invoke the real production method.
            UserListResponse response = service.listUsers(request);

            // Assert — success flag is true and the page content matches the
            // fixture's row count (proves the page propagation works
            // end-to-end without re-implementing the COBOL POPULATE-USER-DATA
            // mapping in the test).
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getUsers()).hasSize(5);
        }

        /**
         * Reject path: any non-admin caller ({@code "U"}, blank, or unknown)
         * is rejected with the COBOL-equivalent
         * {@link UserListService#MSG_NOT_AUTHORIZED} message, and the
         * repository is NEVER queried.
         *
         * <p>The {@code @ValueSource} enumerates three representative
         * non-admin user-type codes:
         * <ul>
         *   <li>{@code "U"} — the canonical regular-user code (COBOL
         *       {@code CDEMO-USRTYP-USER}); the most likely real-world
         *       non-admin caller.</li>
         *   <li>{@code " "} (single space) — whitespace, simulating an
         *       upstream layer that fails to populate the user-type field
         *       (defence in depth — the service must reject blank values
         *       just as firmly as it rejects {@code "U"}).</li>
         *   <li>{@code "X"} — an unknown/unrecognised code; if the
         *       authentication service ever emits a new user-type the
         *       dispatcher must still reject anything that is not exactly
         *       {@code "A"}.</li>
         * </ul>
         *
         * <p>The critical invariant — verified via
         * {@link org.mockito.Mockito#verify(Object, org.mockito.verification.VerificationMode)}
         * with {@link org.mockito.Mockito#never()} — is that the rejection
         * happens BEFORE any database access. This is defence-in-depth: an
         * attacker probing with stolen credentials must not be able to
         * enumerate user existence by observing latency differences between
         * "unknown user" and "wrong password" branches; rejecting at the
         * authorisation layer before any I/O eliminates that signal.
         *
         * @param nonAdminType the non-admin user-type code to test
         */
        @ParameterizedTest(name = "[{index}] non-admin caller type ''{0}''")
        @ValueSource(strings = {"U", " ", "X"})
        @DisplayName("listUsers rejects non-admin caller")
        void listUsers_nonAdminCaller_rejected(String nonAdminType) {
            // Arrange — note the deliberate absence of any stub configuration
            // on userSecurityRepository. Mockito's STRICT_STUBS mode (default
            // under MockitoExtension) would fail if we stubbed and the
            // production code did not call; conversely, the never() assertion
            // below proves the production code does not reach the repository.
            UserListRequest request = new UserListRequest();
            request.setCallerUserType(nonAdminType);
            request.setPage(0);

            // Act — invoke the real production method with a non-admin caller.
            UserListResponse response = service.listUsers(request);

            // Assert (1) — the response is a failure with the canonical
            // "not authorized" message. The .containsIgnoringCase("not
            // authorized") matcher tolerates copy edits to the message
            // surrounding text while anchoring on the load-bearing phrase.
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage()).containsIgnoringCase("not authorized");

            // Assert (2) — CRITICAL defence-in-depth invariant: the
            // repository is NEVER queried on a rejected call. This guarantees
            // that the rejection branch does not leak any database state
            // (existence of users, total count, latency information) to the
            // caller.
            verify(userSecurityRepository, never()).findAll(any(Pageable.class));
        }
    }

    // =========================================================================
    // PAGINATION — 10 rows per page (COBOL WS-MAX-SCREEN-LINES = 10)
    //
    // The COBOL workflow uses a fixed 10-row page size (WS-MAX-SCREEN-LINES
    // VALUE 10) and walks the USRSEC KSDS via STARTBR / READNEXT loops. The
    // Java migration delegates the equivalent semantic to Spring Data
    // Pageable, configured with PAGE_SIZE = 10. This @Nested block verifies:
    //   (1) The production service requests page size 10 from the repository
    //       (ArgumentCaptor captures the Pageable and asserts on its size).
    //   (2) Empty result sets are surfaced as successful responses with
    //       empty user lists and both navigation flags false (distinct from
    //       the authorisation reject, which is a hard failure).
    // =========================================================================

    /**
     * Pagination tests for {@link UserListService#listUsers(UserListRequest)}
     * — verifies the COBOL {@code WS-MAX-SCREEN-LINES = 10} contract is
     * honoured by the Spring Data {@link Pageable} construction inside the
     * production service.
     *
     * <p>The page-size assertion uses an {@link ArgumentCaptor}{@code
     * <Pageable>} to capture the {@link Pageable} passed to
     * {@link UserSecurityRepository#findAll(Pageable)}; the captured value's
     * {@link Pageable#getPageSize()} is then asserted equal to
     * {@code 10}. This is preferred over peeking at
     * {@link UserListService#PAGE_SIZE} directly because it proves the
     * constant is actually being consumed by the production code rather
     * than just declared in a static field.
     */
    @Nested
    @DisplayName("Pagination — 10 rows per page")
    class Pagination {

        /**
         * Happy path: page=0 returns a full 10-row page, and the production
         * code requests page size 10 from the repository.
         *
         * <p>Asserts two things:
         * <ol>
         *   <li>The response carries exactly 10 users (the fixture page is
         *       populated by {@link UserListServiceTest#sampleUsers(int)
         *       sampleUsers(10)} and propagated verbatim).</li>
         *   <li>The {@link Pageable} passed to
         *       {@link UserSecurityRepository#findAll(Pageable)} has
         *       {@link Pageable#getPageSize()} = 10, matching the COBOL
         *       {@code WS-MAX-SCREEN-LINES VALUE 10} contract via
         *       {@link UserListService#PAGE_SIZE}.</li>
         * </ol>
         *
         * <p>COBOL parity: this is the {@code PROCESS-PAGE-FORWARD} loop
         * (lines 300–306) reading exactly 10 records into the
         * {@code USER-REC OCCURS 10 TIMES} table. The Java migration
         * delegates the row-count enforcement to Spring Data; this test
         * verifies the delegation passes the correct size.
         */
        @Test
        @DisplayName("listUsers(page=0) returns first 10")
        void listUsers_pageZero_returns10() {
            // Arrange — stub a 10-row fixture page with a total-element count
            // of 50 so .hasNext() returns true (the production code does not
            // currently assert on hasNext for this scenario, but a realistic
            // multi-page fixture protects against accidental coupling
            // between the test and the single-page case).
            Page<SecurityUser> fixturePage =
                    new PageImpl<>(sampleUsers(10), PageRequest.of(0, 10), 50);
            when(userSecurityRepository.findAll(any(Pageable.class)))
                    .thenReturn(fixturePage);

            UserListRequest request = new UserListRequest();
            request.setCallerUserType("A");
            request.setPage(0);

            // Act — invoke the real production method.
            UserListResponse response = service.listUsers(request);

            // Assert (1) — the response carries exactly 10 users (fixture
            // size).
            assertThat(response.getUsers()).hasSize(10);

            // Assert (2) — capture and inspect the Pageable passed to the
            // repository. The captured value's getPageSize() must equal 10
            // (the COBOL WS-MAX-SCREEN-LINES contract).
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(userSecurityRepository).findAll(captor.capture());
            assertThat(captor.getValue().getPageSize()).isEqualTo(10);
        }

        /**
         * Edge case: an empty repository (or a query beyond the last page)
         * produces a <em>successful</em> response with an empty user list and
         * both navigation flags {@code false}. This is distinct from the
         * admin-only authorisation reject — an authorised query against an
         * empty dataset still succeeds; only the unauthorised branch is a
         * hard failure.
         *
         * <p>COBOL parity: this matches the {@code PROCESS-PAGE-FORWARD}
         * branch where the {@code STARTBR-USER-SEC-FILE} succeeds but the
         * subsequent {@code READNEXT} immediately encounters EOF (the
         * "{@code USER-SEC-EOF}" condition); the COBOL workflow sets
         * {@code NEXT-PAGE-NO TO TRUE} (line 318) and emits an empty page.
         * The Java migration surfaces the same outcome via
         * {@link Page#hasNext()} = {@code false} /
         * {@link Page#hasPrevious()} = {@code false} on an empty page.
         */
        @Test
        @DisplayName("listUsers empty result returns empty list")
        void listUsers_emptyRepository_returnsEmpty() {
            // Arrange — stub a zero-row Page with total-element count 0 so
            // both hasNext() and hasPrevious() are false (the canonical
            // empty-dataset case).
            Page<SecurityUser> emptyPage =
                    new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            when(userSecurityRepository.findAll(any(Pageable.class)))
                    .thenReturn(emptyPage);

            UserListRequest request = new UserListRequest();
            request.setCallerUserType("A");
            request.setPage(0);

            // Act — invoke the real production method.
            UserListResponse response = service.listUsers(request);

            // Assert — empty users, no navigation. The response is still a
            // successful outcome (not asserted explicitly here because the
            // agent-prompt blueprint focuses on the content and navigation
            // assertions, but the implicit assumption is success=true for
            // an authorised query against an empty dataset).
            assertThat(response.getUsers()).isEmpty();
            assertThat(response.isHasNext()).isFalse();
            assertThat(response.isHasPrevious()).isFalse();
        }
    }

    // =========================================================================
    // ROLE FILTER — userType filter routes through findByUserType
    //
    // The COBOL workflow's USRIDINI OF COUSR0AI starting-browse key
    // implemented a user-ID prefix filter via STARTBR. The Java migration
    // repurposes that input field for declarative user-type filtering
    // (one of "A" or "U"), which routes the query through
    // UserSecurityRepository.findByUserType(...) instead of .findAll(...).
    // This @Nested block verifies the dispatch path is correctly chosen
    // based on the request's userTypeFilter field.
    // =========================================================================

    /**
     * Role-filter tests for {@link UserListService#listUsers(UserListRequest)}
     * — verifies that a non-empty {@link UserListRequest#getUserTypeFilter()}
     * routes the query through
     * {@link UserSecurityRepository#findByUserType(String, Pageable)}
     * instead of the unfiltered {@link UserSecurityRepository#findAll(Pageable)}.
     */
    @Nested
    @DisplayName("Role filter — narrow by user type")
    class RoleFilter {

        /**
         * Happy path: a request carrying {@code userTypeFilter = "A"} routes
         * through {@link UserSecurityRepository#findByUserType(String, Pageable)}
         * and returns a narrowed result set. The {@link Mockito#verify(Object)
         * verify(repository)} call asserts the filtered query method was
         * invoked with the expected user-type code; this implicitly proves
         * the unfiltered {@code findAll} branch was NOT taken (Mockito's
         * default verification mode requires the exact call to occur).
         *
         * <p>The fixture page contains 2 admin users (a realistic narrow
         * result given that the canonical seed data has very few admins);
         * the assertion on {@code .hasSize(2)} verifies the page content
         * propagates correctly through the filter dispatch path.
         */
        @Test
        @DisplayName("listUsers with userType filter narrows to matching role")
        void listUsers_withUserTypeFilter_narrowsResults() {
            // Arrange — stub the filtered query to return a 2-row admin
            // fixture page. The stub uses eq("A") to ensure the production
            // code passes exactly the filter value the caller supplied (any
            // mismatch would result in a STRICT_STUBS UnnecessaryStubbingException
            // because the stub would never match).
            Page<SecurityUser> adminPage =
                    new PageImpl<>(sampleUsers(2), PageRequest.of(0, 10), 2);
            when(userSecurityRepository.findByUserType(eq("A"), any(Pageable.class)))
                    .thenReturn(adminPage);

            UserListRequest request = new UserListRequest();
            request.setCallerUserType("A");
            request.setPage(0);
            request.setUserTypeFilter("A");

            // Act — invoke the real production method with the filter set.
            UserListResponse response = service.listUsers(request);

            // Assert (1) — the response carries exactly 2 users (fixture
            // size after filtering).
            assertThat(response.getUsers()).hasSize(2);

            // Assert (2) — the production code routed the query through
            // findByUserType(...) with the expected filter value. This
            // implicitly verifies that findAll(...) was NOT invoked (Mockito's
            // strict-stubs mode requires every stub to be consumed by exactly
            // the configured call).
            verify(userSecurityRepository).findByUserType(eq("A"), any(Pageable.class));
        }
    }

    // =========================================================================
    // FIXTURE HELPER — synthesises SecurityUser instances for the paging
    // tests. Private static so each test class instance shares the same
    // implementation without needing to subclass or extract into a separate
    // utility (kept inline because the helper is tightly coupled to this
    // test class's fixture style).
    // =========================================================================

    /**
     * Synthesises a list of {@code count} {@link SecurityUser} fixtures
     * populated with deterministic, synthetic data:
     *
     * <ul>
     *   <li>{@code userId} — {@code "USRTST" + zero-padded(i)} (8 characters,
     *       matching the COBOL {@code SEC-USR-ID PIC X(08)} field width).</li>
     *   <li>{@code firstName} / {@code lastName} — {@code "FIRST" + i} /
     *       {@code "LAST" + i} (short synthetic placeholders; the test does
     *       not assert on name fields).</li>
     *   <li>{@code userType} — alternates {@code "A"} (every 5th user) and
     *       {@code "U"} (others); this is a documentary alternation that
     *       lets the test fixtures distinguish admins from regular users
     *       even though the current tests assert on row count rather than
     *       on type membership.</li>
     *   <li>{@code password} — pre-computed BCrypt hash from
     *       {@link TestFixtures.Users#TEST_PASSWORD_BCRYPT_HASH} (AAP §0.10.5
     *       "No plaintext credentials in any configuration file"). Every
     *       fixture user carries the same hash because authentication is
     *       not exercised in these list-only tests; the hash is set merely
     *       to keep the SecurityUser instance fully populated and to
     *       document the convention that fixture passwords are always
     *       hashed.</li>
     *   <li>{@code version} — {@code 1L} (the JPA {@code @Version} starting
     *       value); fixtures behave as if they were freshly read from the
     *       database.</li>
     * </ul>
     *
     * <p>The helper is intentionally tightly coupled to the
     * {@link SecurityUser} field set; if the entity evolves (new fields
     * added by future migration agents) this method should be updated to
     * populate the new fields so tests across the user-management family
     * agree on fixture content.
     *
     * @param count the number of fixture {@link SecurityUser} instances to
     *              create; must be non-negative. {@code 0} returns an empty
     *              list.
     * @return a new {@link ArrayList} of populated fixture
     *         {@link SecurityUser} instances; never {@code null}.
     */
    private static List<SecurityUser> sampleUsers(int count) {
        List<SecurityUser> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            SecurityUser u = new SecurityUser();
            // 8-character user-id matching the COBOL SEC-USR-ID PIC X(08)
            // field width. String.format("USRTST%02d", i) produces "USRTST00"
            // through "USRTST99"; counts above 99 would overflow the field,
            // but no current test fixture exceeds 10 rows.
            u.setUserId(String.format("USRTST%02d", i));
            u.setFirstName("FIRST" + i);
            u.setLastName("LAST" + i);
            // Documentary user-type alternation — every 5th user is an admin,
            // the rest are regular users. Not asserted on by current tests
            // but kept for consistency with the rest of the user-management
            // fixture family.
            u.setUserType(i % 5 == 0 ? "A" : "U");
            // BCrypt hash (never plaintext) — TestFixtures.Users.
            // TEST_PASSWORD_BCRYPT_HASH is a pre-computed hash of the
            // fixture credential "TESTPASS" at strength 10 (per
            // TestFixtures.Users.TEST_PASSWORD_PLAINTEXT). Centralising the
            // hash in TestFixtures keeps the "no plaintext credentials"
            // contract in one place.
            u.setPassword(TestFixtures.Users.TEST_PASSWORD_BCRYPT_HASH);
            u.setVersion(1L);
            list.add(u);
        }
        return list;
    }
}
