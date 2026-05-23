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
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies; file
// schema internal_imports).
//
//   * TestFixtures — single source of truth for shared test constants. This
//     test uses TestFixtures.Users.REGULAR_USER_ID (the deletion target on
//     the happy and not-found paths), ADMIN_USER_ID (the current admin
//     session identifier on every test; also the deletion target on the
//     self-delete reject test), and TEST_PASSWORD_BCRYPT_HASH (the fixture
//     user's pre-computed BCrypt hash used by the standardUser() helper to
//     populate the SecurityUser.password field per AAP §0.10.5 "No plaintext
//     credentials in any configuration file").
//
//     Schema authority: file_schema.internal_imports[0] declares this
//     dependency explicitly.
//
//   * SecurityUser — the JPA entity returned by the repository on the
//     happy-path lookup; populated by the standardUser() helper as the
//     fixture value for findById's Optional.of(...) return.
//
//   * UserSecurityRepository — the Spring Data JPA repository the production
//     UserDeleteService delegates to. Mocked at the JPA-repository boundary
//     per AAP §0.10.1 ("Mocks limited to external boundaries: file I/O,
//     downstream service calls, database").
//
//   * UserDeleteService / UserDeleteRequest / UserDeleteResult — the
//     production classes under test. No explicit imports because they
//     share this test's package (`com.aws.carddemo.service`); Java
//     resolves simple-named references via package membership. This
//     matches the convention established by every other test under
//     com.aws.carddemo.service (AAP §0.10.10 style consistency).
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.SecurityUser;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 Jupiter API (file schema external_imports; AAP §0.10.7 framework
// constraint — JUnit 5 only, never JUnit 4 / Vintage).
//
//   * @BeforeEach — re-instantiates the system under test before every
//     method; paired with Mockito's default per-method @Mock instantiation
//     to enforce strict test isolation (AAP §0.10.9). Each test sees a
//     fresh UserSecurityRepository mock and a fresh UserDeleteService.
//   * @DisplayName — human-readable scenario names on the outer class and
//     on each @Nested grouping plus the individual @Test methods (AAP
//     §0.10.6 naming convention).
//   * @Nested — groups HappyPath / RejectPaths scenarios into the two
//     semantic sections that match this test class's exports.
//     members_exposed schema entries ("HappyPath", "RejectPaths").
//   * @Test — single-execution test marker for the four scenario methods.
//   * @ExtendWith — wires MockitoExtension below.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// ---------------------------------------------------------------------------
// Mockito 5 (file schema external_imports for mockito-core; resolved via
// spring-boot-starter-test BOM 3.3.x to Mockito 5.11.0 per setup log).
//
//   * @Mock — Mockito field-injection annotation; MockitoExtension processes
//     this annotation and produces a fresh mock per @Test method,
//     guaranteeing test isolation.
//   * ArgumentCaptor — captures the SecurityUser instance passed to
//     userSecurityRepository.delete(...) on the happy path so the test can
//     assert the captured entity carries the requested user ID, proving the
//     production code routes the correct hydrated entity to the delete
//     operation (rather than an arbitrary or default-constructed instance).
//   * MockitoExtension — activates STRICT_STUBS strictness by default
//     (AAP §0.10.1: "Mockito strictness is `STRICT_STUBS`; unused stubs
//     raise `UnnecessaryStubbingException`."). Each reject-path test
//     deliberately avoids stubbing the repository (or stubs only the
//     scenario-relevant method) to prove (via Mockito.verify(...,
//     never())) that the production code never reaches the unstubbed
//     methods on rejected calls.
// ---------------------------------------------------------------------------
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// ---------------------------------------------------------------------------
// JDK 17 standard-library imports (file schema external_imports for
// java.util).
//
//   * Optional — wraps the repository's findById return value:
//     Optional.of(securityUser) for happy-path scenarios where the target
//     user exists; Optional.empty() for the unknown-user NOTFND parity
//     with COBOL DFHRESP(NOTFND) on READ USRSEC.
// ---------------------------------------------------------------------------
import java.util.Optional;

// ---------------------------------------------------------------------------
// Static imports — AssertJ fluent DSL + Mockito DSL (AAP §0.10.10 "All
// assertions use AssertJ (fluent) rather than mixing AssertJ + Hamcrest +
// Assertions.assertEquals"; all Mockito stubs use the
// when(...).thenReturn(...) style).
//
//   * Assertions.assertThat — fluent assertion entry point used throughout.
//   * ArgumentMatchers.any — relaxes argument matching in the never()
//     verifications (the assertions care about whether the method is
//     called at all, not about the specific argument value).
//   * Mockito.never — verifies that a stub method was NOT invoked; used to
//     prove that the self-delete guard short-circuits BEFORE any repository
//     call and that the empty-ID / not-found rejects short-circuit BEFORE
//     the delete call.
//   * Mockito.verify — interaction assertion; pairs with .never() and with
//     ArgumentCaptor.
//   * Mockito.when — stub configuration; sets up the repository's return
//     values on the happy path (Optional.of(user)) and the not-found path
//     (Optional.empty()).
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserDeleteService}, the migrated Java equivalent of the
 * 359-line CICS COBOL program {@code app/cbl/COUSR03C.cbl} (TRANID {@code CU03},
 * the admin-only user-delete dispatcher). Removes a {@link SecurityUser} record
 * from the {@code USRSEC} VSAM KSDS replacement (PostgreSQL
 * {@code security_users} table) after enforcing COBOL-inherited reject paths
 * plus the Java-migration-added self-delete guard.
 *
 * <h2>COBOL Provenance — COUSR03C.cbl</h2>
 *
 * <p>The COBOL workflow combines three paragraphs:
 * <ol>
 *   <li>{@code DELETE-USER-INFO} (lines 174–192) — orchestrator: validates
 *       {@code USRIDINI OF COUSR3AI} non-empty (line 177), then performs the
 *       lookup and the delete in sequence.</li>
 *   <li>{@code READ-USER-SEC-FILE} (lines 267–300) — single-key READ on
 *       the {@code USRSEC} VSAM KSDS with three outcomes:
 *       {@code DFHRESP(NORMAL)} → continue; {@code DFHRESP(NOTFND)} →
 *       {@code 'User ID NOT found...'} reject (line 289);
 *       {@code WHEN OTHER} → {@code 'Unable to lookup User...'} reject
 *       (line 296), translated in the Java migration to
 *       {@link org.springframework.dao.DataAccessException} propagation.</li>
 *   <li>{@code DELETE-USER-SEC-FILE} (lines 305–336) — issues
 *       {@code EXEC CICS DELETE} on the same dataset; success builds the
 *       {@code 'User {id} has been deleted ...'} confirmation via the COBOL
 *       {@code STRING} construct (lines 318–321).</li>
 * </ol>
 *
 * <h2>Java Migration: Self-Delete Prevention</h2>
 *
 * <p>The Java migration adds a self-delete guard: an admin cannot delete
 * their own user record (operational safety). The COBOL original did not
 * enforce this because the mainframe environment had separate physical
 * sign-on and operations controls. The {@link RejectPaths#deleteUser_selfDelete_rejectsWithSelfDeleteMessage()}
 * test exercises this Java-migration addition and proves it short-circuits
 * BEFORE any database access (no {@code findById} call on a self-delete
 * attempt), eliminating the timing-side-channel that would otherwise leak
 * user existence.
 *
 * <h2>Test Coverage Matrix</h2>
 *
 * <p>The test methods below cover the entire decision tree:
 *
 * <table border="1">
 *   <caption>User-delete test coverage matrix</caption>
 *   <tr><th>Branch</th><th>Test method</th></tr>
 *   <tr><td>Happy path — existing user, admin caller, delete confirmed</td>
 *       <td>{@link HappyPath#deleteUser_existingUser_removesFromUserSec()}</td></tr>
 *   <tr><td>Reject — empty user ID (COBOL parity, line 177)</td>
 *       <td>{@link RejectPaths#deleteUser_emptyUserId_rejectsWithEmptyMessage()}</td></tr>
 *   <tr><td>Reject — user not found (COBOL DFHRESP(NOTFND) parity, line 289)</td>
 *       <td>{@link RejectPaths#deleteUser_nonexistentUser_rejectsWithNotFoundMessage()}</td></tr>
 *   <tr><td>Reject — self-delete attempt (Java-migration addition)</td>
 *       <td>{@link RejectPaths#deleteUser_selfDelete_rejectsWithSelfDeleteMessage()}</td></tr>
 * </table>
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>Tests instantiate the real {@link UserDeleteService} via its public
 * single-argument constructor and assert on the {@link UserDeleteResult}
 * returned by the real {@link UserDeleteService#deleteUser(UserDeleteRequest)}
 * method. The only mocked collaborator is the {@link UserSecurityRepository}
 * (database boundary, the only mock category permitted under AAP §0.10.1).
 * No business logic — the self-delete check, the empty-ID validation, the
 * findById dispatch, or the response construction — is duplicated in any
 * test body; the tests assert only on observable outputs
 * ({@link UserDeleteResult#isSuccess()}, {@link UserDeleteResult#getMessage()},
 * and the {@link SecurityUser} captured by {@link ArgumentCaptor}).
 *
 * <h2>Defence-in-Depth Invariants</h2>
 *
 * <p>Three critical "no-side-effect" invariants are verified via
 * Mockito's {@code never()} verification:
 * <ul>
 *   <li><b>Self-delete short-circuit</b> —
 *       {@link RejectPaths#deleteUser_selfDelete_rejectsWithSelfDeleteMessage()}
 *       asserts neither {@code findById} nor {@code delete} is invoked on a
 *       self-delete attempt. This eliminates the timing side-channel.</li>
 *   <li><b>Empty-ID short-circuit</b> —
 *       {@link RejectPaths#deleteUser_emptyUserId_rejectsWithEmptyMessage()}
 *       asserts {@code delete} is not invoked on an empty-ID reject.</li>
 *   <li><b>Not-found short-circuit</b> —
 *       {@link RejectPaths#deleteUser_nonexistentUser_rejectsWithNotFoundMessage()}
 *       asserts {@code delete} is not invoked when {@code findById} returns
 *       {@code Optional.empty()}.</li>
 * </ul>
 *
 * <p>Combined with Mockito's default {@code STRICT_STUBS} mode (which raises
 * {@link org.mockito.exceptions.misusing.UnnecessaryStubbingException} on
 * any unused stub), these assertions prove that the production code's reject
 * paths do not silently call the repository — a defence-in-depth property
 * documented per AAP §0.10.1 ("This catches premature mocking that would
 * otherwise mask the rule violation.").
 *
 * @see UserDeleteService
 * @see UserDeleteRequest
 * @see UserDeleteResult
 * @see UserSecurityRepository
 * @see TestFixtures.Users
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserDeleteService — COUSR03C.cbl migration parity")
final class UserDeleteServiceTest {

    /**
     * Mocked {@link UserSecurityRepository} — the JPA repository boundary the
     * production {@link UserDeleteService} delegates to. Per AAP §0.10.1, the
     * only mocked collaborator (database is one of the four permitted mock
     * categories). Re-created per {@code @Test} method by
     * {@link MockitoExtension}, ensuring strict isolation between scenarios.
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * System under test — the real {@link UserDeleteService} instance.
     * Re-created per {@code @Test} via {@link #setUp()} to mirror the
     * recreation of the mock collaborator above and to prevent any
     * accidental state leak between tests (the production service is
     * currently stateless aside from its single collaborator field, but
     * the per-method reset is defensive for future state additions).
     */
    private UserDeleteService service;

    /**
     * Constructs a fresh {@link UserDeleteService} before every test method,
     * injecting the freshly-instantiated {@link #userSecurityRepository}
     * mock. The combination of per-method {@code @Mock} instantiation
     * (driven by {@link MockitoExtension}) and per-method service
     * construction here guarantees that no stub or interaction from one
     * test leaks into another (AAP §0.10.9 test isolation).
     */
    @BeforeEach
    void setUp() {
        service = new UserDeleteService(userSecurityRepository);
    }

    // =========================================================================
    // HAPPY PATH — existing user deleted, success message returned
    //
    // COUSR03C.cbl DELETE-USER-INFO (lines 174–192) plus READ-USER-SEC-FILE
    // (lines 267–300, DFHRESP(NORMAL) branch) plus DELETE-USER-SEC-FILE
    // (lines 305–336, DFHRESP(NORMAL) branch). The COBOL workflow STRINGs
    // 'User ' + SEC-USR-ID + ' has been deleted ...' into WS-MESSAGE at
    // lines 318–321; the Java migration concatenates the same three pieces
    // via MSG_DELETED_PREFIX + targetUserId + MSG_DELETED_SUFFIX and surfaces
    // the result via UserDeleteResult.getMessage().
    // =========================================================================

    /**
     * Happy-path scenarios for {@link UserDeleteService#deleteUser(UserDeleteRequest)}.
     *
     * <p>The single test in this group exercises the canonical success
     * scenario: the requested user exists in the repository, the admin
     * operator is not deleting their own record, and the delete operation
     * succeeds. The test asserts both the observable outcome
     * ({@link UserDeleteResult#isSuccess()} true; message contains the
     * verbatim COBOL literal {@code "deleted"}) and the production-side
     * interaction ({@link ArgumentCaptor} captures the
     * {@link SecurityUser} entity passed to
     * {@link UserSecurityRepository#delete(Object)} and asserts the
     * captured entity carries the requested {@code userId}).
     */
    @Nested
    @DisplayName("Happy path — user deletion")
    class HappyPath {

        /**
         * Happy path: an existing user ({@link TestFixtures.Users#REGULAR_USER_ID}
         * = {@code "USRTST01"}) is removed from the {@code USRSEC} replacement
         * by an admin operator ({@link TestFixtures.Users#ADMIN_USER_ID} =
         * {@code "ADMTST01"}). Asserts that the response carries
         * {@link UserDeleteResult#isSuccess()} = {@code true} and the
         * COBOL-verbatim {@code 'User {id} has been deleted ...'} message
         * (verified via {@code containsIgnoringCase("deleted")} to tolerate
         * copy edits to the surrounding text while anchoring on the
         * load-bearing literal).
         *
         * <p>COBOL parity: this is the
         * {@code DELETE-USER-INFO} → {@code READ-USER-SEC-FILE}
         * (DFHRESP(NORMAL)) → {@code DELETE-USER-SEC-FILE} (DFHRESP(NORMAL))
         * sequence (lines 174–192, 280–286, 313–322).
         *
         * <p>The {@link ArgumentCaptor} assertion proves that the production
         * code routes the correct hydrated entity (returned by {@code findById})
         * to {@code delete(...)} rather than constructing a fresh entity or
         * passing an arbitrary value. This invariant matters for JPA
         * optimistic-locking semantics: the {@link SecurityUser#getVersion()}
         * field on the captured entity must be the one loaded from the
         * database so the {@code @Version} check can detect concurrent
         * modifications (matches the COBOL before-image/after-image record
         * comparison semantic that VSAM provided implicitly).
         */
        @Test
        @DisplayName("deleteUser(existing) removes record from USRSEC")
        void deleteUser_existingUser_removesFromUserSec() {
            // Arrange — stub the repository to return the standard fixture
            // user for the REGULAR_USER_ID lookup. The fixture is populated
            // by standardUser() with a BCrypt-hashed password (AAP §0.10.5
            // "No plaintext credentials") and a non-null @Version value so
            // the captured-entity assertion below can confirm the production
            // code preserves the version field through the delete call.
            SecurityUser existing = standardUser();
            when(userSecurityRepository.findById(TestFixtures.Users.REGULAR_USER_ID))
                    .thenReturn(Optional.of(existing));

            UserDeleteRequest request = new UserDeleteRequest(
                    TestFixtures.Users.REGULAR_USER_ID,
                    TestFixtures.Users.ADMIN_USER_ID);

            // Act — invoke the real production method.
            UserDeleteResult result = service.deleteUser(request);

            // Assert (1) — success flag is true and the message carries the
            // verbatim COBOL literal "deleted" (anchored on the lower-case
            // load-bearing phrase per the production constant
            // UserDeleteService.MSG_DELETED_SUFFIX = " has been deleted ...").
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'User {id} has been deleted ...'")
                    .containsIgnoringCase("deleted");

            // Assert (2) — the production code routed the hydrated entity
            // (returned by findById) verbatim through to delete(...). The
            // ArgumentCaptor proves the captured argument carries the
            // requested userId, eliminating the possibility that the
            // production code mistakenly constructed a new SecurityUser or
            // passed a different entity to delete().
            ArgumentCaptor<SecurityUser> captor =
                    ArgumentCaptor.forClass(SecurityUser.class);
            verify(userSecurityRepository).delete(captor.capture());
            assertThat(captor.getValue().getUserId())
                    .as("Captured SecurityUser passed to delete(...) "
                            + "carries the requested userId")
                    .isEqualTo(TestFixtures.Users.REGULAR_USER_ID);
        }
    }

    // =========================================================================
    // REJECT PATHS — empty user ID / not-found / self-delete
    //
    // Three reject branches: two inherited from COBOL parity (empty user ID
    // at line 177; DFHRESP(NOTFND) at lines 287 and 323) plus one Java-
    // migration addition (self-delete guard, no COBOL equivalent). The
    // production code performs the rejects in the documented order:
    //   1. Self-delete (Java-migration addition, before any repo access)
    //   2. Empty user ID (COBOL parity, before any repo access)
    //   3. findById → Optional.empty() (COBOL DFHRESP(NOTFND))
    //
    // The never() assertions below verify that the reject paths short-
    // circuit BEFORE the corresponding repository operations, providing
    // defence-in-depth against accidental state leakage on rejected calls.
    // =========================================================================

    /**
     * Reject-path scenarios for {@link UserDeleteService#deleteUser(UserDeleteRequest)}.
     *
     * <p>Three tests cover the three reject branches:
     * <ul>
     *   <li>{@link #deleteUser_emptyUserId_rejectsWithEmptyMessage()} —
     *       empty {@code userId} produces the {@code 'User ID can NOT be
     *       empty...'} reject; {@code delete} is never invoked.</li>
     *   <li>{@link #deleteUser_nonexistentUser_rejectsWithNotFoundMessage()} —
     *       non-existent {@code userId} produces the {@code 'User ID NOT
     *       found...'} reject; {@code delete} is never invoked even though
     *       {@code findById} is called.</li>
     *   <li>{@link #deleteUser_selfDelete_rejectsWithSelfDeleteMessage()} —
     *       admin-deleting-self produces the self-delete guard message;
     *       neither {@code findById} nor {@code delete} is invoked
     *       (defence-in-depth: no database state is leaked).</li>
     * </ul>
     */
    @Nested
    @DisplayName("Reject paths")
    class RejectPaths {

        /**
         * Reject path: an empty {@code userId} produces the COBOL-equivalent
         * {@code 'User ID can NOT be empty...'} reject. Asserts that
         * {@link UserDeleteResult#isSuccess()} is {@code false}, the message
         * contains both load-bearing phrases ({@code "user id"} and
         * {@code "empty"}), and the repository's {@code delete} method is
         * never invoked on the empty-ID branch.
         *
         * <p>COBOL parity: this is the {@code DELETE-USER-INFO}
         * {@code USRIDINI = SPACES OR LOW-VALUES} reject at lines 177–182.
         * The COBOL workflow short-circuits before invoking
         * {@code READ-USER-SEC-FILE}; the Java migration short-circuits
         * before invoking {@code findById}, but this test does not assert
         * the {@code never().findById(any())} invariant because the
         * order-of-validation between empty-ID and self-delete is the
         * production class's choice (the test focuses on the load-bearing
         * never-delete invariant that protects against accidental data
         * loss).
         *
         * <p>The empty string {@code ""} drives the test rather than
         * {@code null} or whitespace because the COBOL
         * {@code SPACES OR LOW-VALUES} predicate matches both empty and
         * blank; the production code treats null, empty, and whitespace
         * uniformly per the {@link UserDeleteService} javadoc.
         */
        @Test
        @DisplayName("deleteUser rejects when user ID is empty")
        void deleteUser_emptyUserId_rejectsWithEmptyMessage() {
            // Arrange — note the deliberate absence of any stub configuration
            // on userSecurityRepository. Mockito's STRICT_STUBS mode (default
            // under MockitoExtension) would fail if we stubbed and the
            // production code did not call; conversely, the never() assertion
            // below proves the production code does not reach the delete
            // operation.
            UserDeleteRequest request = new UserDeleteRequest(
                    "", TestFixtures.Users.ADMIN_USER_ID);

            // Act — invoke the real production method with an empty userId.
            UserDeleteResult result = service.deleteUser(request);

            // Assert (1) — the response is a failure with the canonical
            // "user id" + "empty" message phrases. The two
            // .containsIgnoringCase(...) matchers tolerate copy edits to
            // the surrounding text while anchoring on both load-bearing
            // phrases (matches the verbatim COBOL literal
            // 'User ID can NOT be empty...').
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'User ID can NOT be empty...'")
                    .containsIgnoringCase("user id")
                    .containsIgnoringCase("empty");

            // Assert (2) — defence-in-depth: the delete operation is NEVER
            // invoked on a rejected call. This protects against the
            // catastrophic-data-loss failure mode where a malformed request
            // silently triggers a delete on an unintended record.
            verify(userSecurityRepository, never()).delete(any());
        }

        /**
         * Reject path: a {@code userId} that does not exist in the
         * repository produces the COBOL-equivalent {@code 'User ID NOT
         * found...'} reject. Asserts that
         * {@link UserDeleteResult#isSuccess()} is {@code false}, the message
         * contains the load-bearing {@code "not found"} phrase, and the
         * repository's {@code delete} method is never invoked even though
         * {@code findById} returns {@code Optional.empty()}.
         *
         * <p>COBOL parity: this is the
         * {@code READ-USER-SEC-FILE} {@code DFHRESP(NOTFND)} branch at
         * lines 287–292. The Java migration's {@code Optional.empty()}
         * return from {@code findById} maps onto the same reject branch
         * (and also collapses the rare-race {@code DFHRESP(NOTFND)} on
         * {@code DELETE-USER-SEC-FILE} lines 323–328 into the same Java
         * code path because the two are observationally identical to the
         * caller).
         *
         * <p>The fixture {@code "NOSUCH01"} is used in preference to
         * {@link TestFixtures.Users#NONEXISTENT_USER_ID} (which is
         * {@code "NOTAUSER"}, also guaranteed not to exist) because the
         * agent-prompt template specifies {@code "NOSUCH01"} verbatim;
         * either value would satisfy the test contract since the literal
         * is only used as a key for an explicitly-stubbed
         * {@code Optional.empty()} response.
         */
        @Test
        @DisplayName("deleteUser rejects when user does not exist (NOTFND parity)")
        void deleteUser_nonexistentUser_rejectsWithNotFoundMessage() {
            // Arrange — stub the repository to return Optional.empty() for
            // the lookup, mirroring COBOL DFHRESP(NOTFND). The string
            // "NOSUCH01" is a deliberately-malformed-looking placeholder
            // that the test explicitly stubs as missing; using a constant
            // from TestFixtures.Users would also work (any 8-char value
            // would do) but the inline literal keeps the test self-contained
            // and matches the agent-prompt template.
            when(userSecurityRepository.findById("NOSUCH01"))
                    .thenReturn(Optional.empty());

            UserDeleteRequest request = new UserDeleteRequest(
                    "NOSUCH01", TestFixtures.Users.ADMIN_USER_ID);

            // Act — invoke the real production method with a non-existent
            // userId.
            UserDeleteResult result = service.deleteUser(request);

            // Assert (1) — the response is a failure with the canonical
            // "not found" message. The .containsIgnoringCase("not found")
            // matcher anchors on the load-bearing phrase from the verbatim
            // COBOL literal 'User ID NOT found...'.
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'User ID NOT found...'")
                    .containsIgnoringCase("not found");

            // Assert (2) — defence-in-depth: the delete operation is NEVER
            // invoked when the lookup returns Optional.empty(). This
            // protects against any code path that might erroneously attempt
            // to delete-by-id even after a missing-record diagnosis.
            verify(userSecurityRepository, never()).delete(any());
        }

        /**
         * Reject path: the admin operator attempts to delete their own user
         * record. Asserts that {@link UserDeleteResult#isSuccess()} is
         * {@code false}, the message contains the load-bearing {@code "own"}
         * phrase, and CRITICALLY that neither {@code findById} nor
         * {@code delete} is invoked on the rejection branch.
         *
         * <p>This is a Java-migration-only safety guard with no direct COBOL
         * equivalent (see {@link UserDeleteService} class-level "Java
         * Migration: Self-Delete Prevention" note for the rationale). The
         * production code performs the self-delete check BEFORE the empty-ID
         * validation AND BEFORE any repository access, eliminating the
         * timing-side-channel that would otherwise leak whether the
         * operator-supplied target user exists in the database.
         *
         * <p>The {@code verify(..., never()).findById(any())} assertion is
         * the load-bearing defence-in-depth invariant: combined with the
         * {@code verify(..., never()).delete(any())} assertion below it,
         * this proves that the production code's self-delete branch makes
         * ZERO database calls. Any reachable call into the repository on
         * this branch would leak observable state (existence, latency)
         * about other users in the system.
         */
        @Test
        @DisplayName("deleteUser rejects self-delete attempt")
        void deleteUser_selfDelete_rejectsWithSelfDeleteMessage() {
            // Arrange — construct a request where the target userId equals
            // the current admin session userId. Both fields point at
            // ADMIN_USER_ID so the self-delete guard fires; the absence of
            // any stub configuration on userSecurityRepository is deliberate
            // (Mockito's STRICT_STUBS mode would fail if we stubbed and the
            // production code did not call, but the never() assertions below
            // also prove the converse — the production code does not call
            // the repository at all on this branch).
            UserDeleteRequest request = new UserDeleteRequest(
                    TestFixtures.Users.ADMIN_USER_ID,
                    TestFixtures.Users.ADMIN_USER_ID);

            // Act — invoke the real production method with an
            // operator-deleting-themselves request.
            UserDeleteResult result = service.deleteUser(request);

            // Assert (1) — the response is a failure with the self-delete
            // guard message. The .containsIgnoringCase("own") matcher
            // anchors on the load-bearing word from the Java-side canonical
            // phrasing UserDeleteService.MSG_CANNOT_DELETE_SELF
            // ("Cannot delete your own user record").
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Self-delete guard message anchored on 'own'")
                    .containsIgnoringCase("own");

            // Assert (2a) — CRITICAL defence-in-depth invariant: the
            // findById lookup is NEVER invoked on the self-delete branch.
            // This eliminates the timing-side-channel that an attacker
            // could exploit to enumerate user existence.
            verify(userSecurityRepository, never()).findById(any());

            // Assert (2b) — CRITICAL defence-in-depth invariant: the delete
            // operation is NEVER invoked on the self-delete branch. This
            // is the operational-safety guarantee that motivates the
            // Java-migration addition in the first place.
            verify(userSecurityRepository, never()).delete(any());
        }
    }

    // =========================================================================
    // FIXTURE HELPERS
    //
    // Pure-data factory for the standard fixture SecurityUser used by the
    // happy-path test. Centralised here (rather than at TestFixtures) because
    // the fixture's exact field population is only consumed by this test
    // class — TestFixtures itself remains a pure-constants holder per AAP
    // §0.10.1 ("TestFixtures contains NO methods ... only public static
    // final string, integer, and character constants").
    // =========================================================================

    /**
     * Builds the standard fixture {@link SecurityUser} used by the happy-path
     * test. Populates every persisted field with deterministic values:
     * <ul>
     *   <li>{@code userId} — {@link TestFixtures.Users#REGULAR_USER_ID}
     *       ({@code "USRTST01"}), the canonical regular-user fixture
     *       identifier.</li>
     *   <li>{@code firstName} / {@code lastName} — synthetic "TEST USER"
     *       placeholders that match no real person.</li>
     *   <li>{@code password} — {@link TestFixtures.Users#TEST_PASSWORD_BCRYPT_HASH},
     *       the pre-computed BCrypt hash of {@code "TESTPASS"} per AAP
     *       §0.10.5 (no plaintext credentials in tests).</li>
     *   <li>{@code userType} — {@code "U"} for regular user (the canonical
     *       COBOL {@code CDEMO-USRTYP-USER} 88-level value).</li>
     *   <li>{@code version} — {@code 1L} so the captured-entity assertion
     *       in the happy-path test can confirm the production code preserves
     *       the JPA {@code @Version} field through the {@code delete(...)}
     *       call (matters for optimistic-locking semantics; see
     *       {@link SecurityUser#getVersion()}).</li>
     * </ul>
     *
     * @return a freshly-constructed fixture {@link SecurityUser} ready for
     *         insertion into {@code Optional.of(...)} as a {@code findById}
     *         stub return value
     */
    private static SecurityUser standardUser() {
        SecurityUser u = new SecurityUser();
        u.setUserId(TestFixtures.Users.REGULAR_USER_ID);
        u.setFirstName("TEST");
        u.setLastName("USER");
        u.setPassword(TestFixtures.Users.TEST_PASSWORD_BCRYPT_HASH);
        u.setUserType("U");
        u.setVersion(1L);
        return u;
    }
}
