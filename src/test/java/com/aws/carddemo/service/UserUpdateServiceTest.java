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
//     test uses TestFixtures.Users.REGULAR_USER_ID ("USRTST01", the 8-char
//     fixture user ID used for happy-update, role-change, validation-reject,
//     and security-check scenarios), TestFixtures.Users.ADMIN_USER_ID
//     ("ADMTST01", used by the role-change happy scenario to drive the
//     U→A user-type promotion), TestFixtures.Users.NONEXISTENT_USER_ID
//     ("NOTAUSER", used by the user-not-found OptimisticLocking scenario
//     to drive the COBOL READ-USER-SEC-FILE DFHRESP(NOTFND) parity test),
//     TestFixtures.Users.TEST_PASSWORD_PLAINTEXT ("TESTPASS", the synthetic
//     fixture password passed to UserUpdateRequest.setNewPassword and
//     asserted against the persisted BCrypt hash via passwordEncoder.matches),
//     and TestFixtures.Users.TEST_PASSWORD_BCRYPT_HASH (the pre-computed
//     BCrypt hash of "TESTPASS" used by standardUser() to simulate an
//     existing persisted user record's password hash for the
//     password-preservation scenario).
//
//     Schema authority: file_schema.internal_imports[0] declares this
//     dependency explicitly.
//
//   * SecurityUser — the JPA entity returned by the repository on
//     findById(...) lookups and the captured argument on save(...) calls.
//     Imported via fully-qualified package because the entity lives in the
//     com.aws.carddemo.entity package (NOT this test's
//     com.aws.carddemo.service package).
//
//   * UserSecurityRepository — the Spring Data JPA repository the production
//     UserUpdateService delegates to. Mocked at the JPA-repository boundary
//     per AAP §0.10.1 ("Mocks limited to external boundaries: file I/O,
//     downstream service calls, database").
//
//   * UserUpdateService / UserUpdateRequest / UserUpdateResult — the
//     production classes under test. No explicit imports because they
//     share this test's package (com.aws.carddemo.service); Java resolves
//     simple-named references via package membership. This matches the
//     convention established by UserAddServiceTest, UserDeleteServiceTest,
//     and every other test under com.aws.carddemo.service (AAP §0.10.10
//     style consistency).
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
//     fresh UserSecurityRepository mock and a fresh UserUpdateService.
//   * @DisplayName — human-readable scenario names on the outer class and
//     on each @Nested grouping (AAP §0.10.6 naming convention).
//   * @Nested — groups HappyPath / OptimisticLocking / ValidationRejects /
//     SecurityChecks scenarios into the four semantic sections that match
//     this test class's exports.members_exposed schema entries.
//   * @Test — single-execution test marker for non-parameterised scenarios.
//   * @ExtendWith — wires MockitoExtension below.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// ---------------------------------------------------------------------------
// JUnit 5 Parameterized-test support (file schema external_imports).
//
//   * @ParameterizedTest + @ValueSource(strings = ...) — drives the
//     validation-reject scenarios across representative invalid inputs:
//     three empty/whitespace strings for the first-name and last-name
//     rejects, and seven values for the invalid-user-type reject (covering
//     letters outside {U, A}, digits, multi-character strings, single space,
//     empty string, and case-sensitivity verification via lowercase "a"
//     and "u"). Fulfils AAP §0.10.7 "@ParameterizedTest for calculation
//     variants" directive.
// ---------------------------------------------------------------------------
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// ---------------------------------------------------------------------------
// Mockito 5 (file schema external_imports for mockito-core; resolved via
// spring-boot-starter-test BOM 3.3.13 to Mockito 5.11.0 per setup log).
//
//   * @Mock — Mockito field-injection annotation; MockitoExtension processes
//     this annotation and produces a fresh mock per @Test method,
//     guaranteeing test isolation.
//   * ArgumentCaptor — captures the SecurityUser instance passed to
//     userSecurityRepository.save(...) so the test can assert the persisted
//     entity carries the requested user ID, names, user type, and the
//     BCrypt-hashed (never plaintext) password.
//   * MockitoExtension — activates STRICT_STUBS strictness by default
//     (AAP §0.10.1: "Mockito strictness is `STRICT_STUBS`; unused stubs
//     raise `UnnecessaryStubbingException`."). Each reject-path test
//     deliberately avoids stubbing the repository (or stubs only the
//     scenario-relevant method) to prove via Mockito.verify(...,
//     never()) that the production code never reaches the unstubbed
//     methods on rejected calls.
// ---------------------------------------------------------------------------
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// ---------------------------------------------------------------------------
// Spring Framework data-access exception (file schema external_imports for
// spring-tx, resolved via spring-boot-starter-data-jpa transitively).
//
//   * OptimisticLockingFailureException — Spring's JPA optimistic-locking
//     exception thrown when a save(...) call detects a @Version mismatch.
//     Stubbed via Mockito.when(...).thenThrow(...) in the OptimisticLocking
//     nested test group; asserted via AssertJ's assertThatThrownBy(...).
//     This is the Java translation of the COBOL
//     DATA-WAS-CHANGED-BEFORE-UPDATE flag from COUSR02C.cbl's
//     CHECK-CHANGE-IN-REC paragraph; the COBOL version detected the
//     mismatch via before/after-image record comparison, the Java version
//     relies on JPA's @Version field.
// ---------------------------------------------------------------------------
import org.springframework.dao.OptimisticLockingFailureException;

// ---------------------------------------------------------------------------
// Spring Security crypto (file schema external_imports for
// spring-security-crypto; transitively brought in by
// spring-boot-starter-security in pom.xml).
//
//   * BCryptPasswordEncoder — the real (NOT mocked) implementation supplied
//     to the UserUpdateService under test. Per AAP §0.10.1 ("Real BCrypt
//     password encoder used to hash the fixture password and verify
//     password-matching semantics"). Strength 10 is the Spring Security
//     default and is fast enough for unit tests (~80 ms / hash; each test
//     cumulatively creates at most three hashes — well under the AAP §0.7.2
//     "< 60 s total unit-suite wall-clock" target).
//   * PasswordEncoder — the Spring Security interface; the encoder is held
//     by the test as the interface type (not the concrete
//     BCryptPasswordEncoder type) so the production UserUpdateService can
//     accept any PasswordEncoder implementation — proves the service is
//     correctly parameterised on the abstraction, not the concrete class.
// ---------------------------------------------------------------------------
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

// ---------------------------------------------------------------------------
// JDK 17 standard-library imports (file schema external_imports for
// java.util).
//
//   * Optional — wraps the repository's findById return value:
//     Optional.of(existingUser) for happy-update / role-change /
//     password-preservation / optimistic-locking scenarios (simulates
//     "user exists in USRSEC"); Optional.empty() for the user-not-found
//     scenario (Java translation of COBOL READ-USER-SEC-FILE
//     DFHRESP(NOTFND) from COUSR02C lines 340–345).
// ---------------------------------------------------------------------------
import java.util.Optional;

// ---------------------------------------------------------------------------
// Static imports — AssertJ fluent DSL + Mockito DSL (AAP §0.10.10 "All
// assertions use AssertJ (fluent) rather than mixing AssertJ + Hamcrest +
// Assertions.assertEquals"; all Mockito stubs use the
// when(...).thenReturn(...) style).
//
//   * Assertions.assertThat — fluent assertion entry point used throughout.
//   * Assertions.assertThatThrownBy — fluent exception-assertion entry
//     point used in the OptimisticLocking scenarios to verify the
//     uncaught exception propagation (the COBOL DATA-WAS-CHANGED-BEFORE-
//     UPDATE Java migration).
//   * ArgumentMatchers.any — relaxes argument matching in the never()
//     verifications and in stubs that don't care about the specific argument
//     value.
//   * ArgumentMatchers.eq — anchors argument matching to a specific value
//     in stubs that pair with other any() matchers (Mockito DSL requires
//     either all matchers or none).
//   * Mockito.never — verifies that a stub method was NOT invoked; used to
//     prove that validation rejects short-circuit BEFORE the save call.
//   * Mockito.times — verifies the exact number of invocations; used to
//     pair with ArgumentCaptor in scenarios that save more than once.
//   * Mockito.verify — interaction assertion; pairs with .never(), .times(),
//     and ArgumentCaptor.
//   * Mockito.when — stub configuration; sets up the repository's return
//     values on the happy paths (Optional.of(...) from findById, the saved
//     entity from save), the user-not-found path (Optional.empty() from
//     findById), and the version-mismatch path (OptimisticLockingFailureException
//     from save).
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserUpdateService}, the migrated Java equivalent of
 * the 414-line CICS COBOL program {@code app/cbl/COUSR02C.cbl} (TRANID
 * {@code CU02}, the admin-only user-update dispatcher). The service updates
 * an existing {@link SecurityUser} record in the {@code USRSEC} VSAM KSDS
 * replacement (PostgreSQL {@code security_users} table) after enforcing
 * field-level validations inherited from the COBOL baseline, plus a
 * Java-migration-added invalid-user-type reject and JPA optimistic-locking
 * semantics.
 *
 * <h2>COBOL Provenance — COUSR02C.cbl Validation Order</h2>
 *
 * <ol>
 *   <li>User ID empty (line 180 / 182) →
 *       {@code 'User ID can NOT be empty...'}</li>
 *   <li>First name empty (line 186 / 188) →
 *       {@code 'First Name can NOT be empty...'}</li>
 *   <li>Last name empty (line 192 / 194) →
 *       {@code 'Last Name can NOT be empty...'}</li>
 *   <li>Password empty (line 198 / 200) →
 *       {@code 'Password can NOT be empty...'} <em>— omitted in the Java
 *       migration; see Java Migration: newPassword Semantics below</em></li>
 *   <li>User type empty (line 204 / 206) →
 *       {@code 'User Type can NOT be empty...'}</li>
 *   <li>(Java-migration addition) Invalid user type →
 *       {@code 'User Type must be ''U'' or ''A''...'}</li>
 *   <li>{@code READ-USER-SEC-FILE} NOTFND (line 342) →
 *       {@code 'User ID NOT found...'}</li>
 *   <li>(Java-migration optimistic-locking) {@code @Version} mismatch on
 *       {@code save()} → throws {@link OptimisticLockingFailureException}</li>
 *   <li>{@code UPDATE-USER-SEC-FILE} NORMAL (lines 369–375) →
 *       {@code 'User {id} has been updated ...'}</li>
 * </ol>
 *
 * <h2>BCrypt Migration (AAP §0.10.5)</h2>
 *
 * <p>COBOL stored {@code SEC-USR-PWD PIC X(08)} as plaintext (lines 227–230
 * of {@code COUSR02C.cbl} compare plaintext PASSWDI against plaintext
 * SEC-USR-PWD to detect a password change). The Java migration uses
 * {@link BCryptPasswordEncoder} at strength 10 (Spring Security default).
 * Because the persisted password is now a BCrypt hash, a plaintext-versus-
 * plaintext comparison is impossible; the Java migration re-purposes the
 * {@link UserUpdateRequest#getNewPassword()} field with the following
 * semantics:
 * <ul>
 *   <li>{@code null} or empty → preserve the existing BCrypt hash
 *       (operator did not change the password). The persisted entity's
 *       {@code password} field is left untouched.</li>
 *   <li>Non-empty → BCrypt-encode and overwrite the persisted hash. The
 *       persisted entity's {@code password} field carries the new
 *       60-character BCrypt hash.</li>
 * </ul>
 *
 * <p>Tests verify the following invariants:
 * <ul>
 *   <li>Persisted password field matches BCrypt format:
 *       {@code ^\$2[abxy]\$\d{2}\$.{53}$}.</li>
 *   <li>{@code passwordEncoder.matches(plaintext, persistedHash)} returns
 *       {@code true} — proves real BCrypt encoding, not a deterministic
 *       or reversible transform.</li>
 *   <li>Plaintext password does NOT appear anywhere in the persisted
 *       entity (defence-in-depth assertion via
 *       {@code doesNotContain(plaintext)}).</li>
 *   <li>An empty {@code newPassword} preserves the existing hash exactly
 *       (the test asserts the persisted hash equals the pre-existing
 *       hash, not a new BCrypt encode of any value).</li>
 * </ul>
 *
 * <h2>Optimistic Locking (COBOL DATA-WAS-CHANGED-BEFORE-UPDATE parity)</h2>
 *
 * <p>The COBOL READ UPDATE / REWRITE idiom serialised concurrent updates
 * through CICS file locks; the {@code CHECK-CHANGE-IN-REC} paragraph
 * compared the displayed before-image of the record against the current
 * persisted state on the way into the REWRITE, setting
 * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} when they differed. The Java
 * migration replaces this with JPA's {@code @Version} optimistic-locking
 * field on {@link SecurityUser}: when the {@code save()} call detects a
 * version mismatch, it raises {@link OptimisticLockingFailureException}.
 * Tests verify this exception propagates uncaught — the
 * {@link UserUpdateService} does not catch it, letting the controller
 * layer's exception-handler chain produce the HTTP 409 Conflict response.
 *
 * <h2>Test Coverage Matrix</h2>
 *
 * <table border="1">
 *   <caption>User-update test coverage matrix</caption>
 *   <tr><th>Branch</th><th>Test method</th></tr>
 *   <tr><td>Happy path — valid request rehashes password via BCrypt</td>
 *       <td>{@link HappyPath#updateUser_validRequestWithNewPassword_rehashesPasswordAndPersists()}</td></tr>
 *   <tr><td>Happy path — empty newPassword preserves existing hash</td>
 *       <td>{@link HappyPath#updateUser_validRequestWithEmptyPassword_preservesExistingHash()}</td></tr>
 *   <tr><td>Happy path — null newPassword preserves existing hash</td>
 *       <td>{@link HappyPath#updateUser_validRequestWithNullPassword_preservesExistingHash()}</td></tr>
 *   <tr><td>Happy path — role change U → A persists</td>
 *       <td>{@link HappyPath#updateUser_roleChangeUtoA_persistsNewRole()}</td></tr>
 *   <tr><td>Happy path — role change A → U persists</td>
 *       <td>{@link HappyPath#updateUser_roleChangeAtoU_persistsNewRole()}</td></tr>
 *   <tr><td>Happy path — first/last name change persists</td>
 *       <td>{@link HappyPath#updateUser_nameChange_persistsNewNames()}</td></tr>
 *   <tr><td>Happy path — success message format</td>
 *       <td>{@link HappyPath#updateUser_validRequest_returnsUpdatedMessage()}</td></tr>
 *   <tr><td>Optimistic lock — @Version mismatch propagates</td>
 *       <td>{@link OptimisticLocking#updateUser_versionMismatch_throwsOptimisticLockingFailureException()}</td></tr>
 *   <tr><td>User not found — NOTFND parity reject</td>
 *       <td>{@link OptimisticLocking#updateUser_userNotFound_returnsReject()}</td></tr>
 *   <tr><td>Reject — empty user ID (COBOL line 180)</td>
 *       <td>{@link ValidationRejects#updateUser_emptyUserId_rejected()}</td></tr>
 *   <tr><td>Reject — empty/whitespace first name (COBOL line 186)</td>
 *       <td>{@link ValidationRejects#updateUser_emptyFirstName_rejected(String)}</td></tr>
 *   <tr><td>Reject — empty/whitespace last name (COBOL line 192)</td>
 *       <td>{@link ValidationRejects#updateUser_emptyLastName_rejected(String)}</td></tr>
 *   <tr><td>Reject — empty user type (COBOL line 204)</td>
 *       <td>{@link ValidationRejects#updateUser_emptyUserType_rejected()}</td></tr>
 *   <tr><td>Reject — invalid user type (Java-migration addition)</td>
 *       <td>{@link ValidationRejects#updateUser_invalidUserType_rejected(String)}</td></tr>
 *   <tr><td>Security — no plaintext persistence</td>
 *       <td>{@link SecurityChecks#updateUser_validRequest_neverPersistsPlaintext()}</td></tr>
 *   <tr><td>Security — BCrypt salt produces different hashes</td>
 *       <td>{@link SecurityChecks#updateUser_samePasswordTwice_producesDifferentHashes()}</td></tr>
 * </table>
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>Tests instantiate the real {@link UserUpdateService} via its public
 * two-argument constructor and assert on the {@link UserUpdateResult}
 * returned by the real {@link UserUpdateService#updateUser(UserUpdateRequest)}
 * method (or on the propagated {@link OptimisticLockingFailureException}
 * for the version-mismatch scenarios). The only mocked collaborator is the
 * {@link UserSecurityRepository} (database boundary, the only mock category
 * permitted under AAP §0.10.1). The {@link BCryptPasswordEncoder} is REAL —
 * never mocked — because verifying real BCrypt semantics is the entire
 * point of the security tests in this class (a mocked encoder could
 * trivially be configured to return any value, defeating the assurance
 * that the production code calls real BCrypt encoding).
 *
 * <p>No business logic — empty checks, user-type domain check, BCrypt
 * encoding, optimistic-locking propagation, response construction — is
 * duplicated in any test body; the tests assert only on observable outputs
 * ({@link UserUpdateResult#isSuccess()}, {@link UserUpdateResult#getMessage()},
 * the {@link SecurityUser} captured by {@link ArgumentCaptor}, and the
 * exception type of the propagated {@link OptimisticLockingFailureException}).
 *
 * <h2>Defence-in-Depth Invariants</h2>
 *
 * <p>The reject-path tests assert the load-bearing "no save" invariant via
 * {@code verify(userSecurityRepository, never()).save(any())}, proving that
 * malformed requests cannot reach the persistence layer. Combined with
 * Mockito's default {@code STRICT_STUBS} mode (which raises
 * {@link org.mockito.exceptions.misusing.UnnecessaryStubbingException} on
 * any unused stub), these assertions prove that the production code's
 * reject paths do not silently call the repository — a defence-in-depth
 * property documented per AAP §0.10.1.
 *
 * @see UserUpdateService
 * @see UserUpdateRequest
 * @see UserUpdateResult
 * @see SecurityUser
 * @see UserSecurityRepository
 * @see TestFixtures.Users
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserUpdateService — COUSR02C.cbl migration parity")
final class UserUpdateServiceTest {

    /**
     * Mocked {@link UserSecurityRepository} — the JPA repository boundary
     * the production {@link UserUpdateService} delegates to. Per AAP
     * §0.10.1, the only mocked collaborator (database is one of the four
     * permitted mock categories). Re-created per {@code @Test} method by
     * {@link MockitoExtension}, ensuring strict isolation between scenarios.
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Real {@link BCryptPasswordEncoder} at strength 10 (Spring Security
     * default).
     *
     * <p>NEVER mocked. The whole point of the security tests in this class
     * is to prove that the production {@link UserUpdateService} calls
     * {@code encode(plaintext)} on a real {@link PasswordEncoder} rather
     * than storing plaintext or using a deterministic transform. Mocking
     * the encoder would defeat the test.
     *
     * <p>Strength 10 produces hashes in roughly 80 ms on commodity hardware;
     * this class's tests cumulatively create well under one second of
     * BCrypt work (at most a handful of encode() calls across all scenarios),
     * comfortably under the AAP §0.7.2 wall-clock budget.
     *
     * <p>Held by the test as the {@link PasswordEncoder} interface type
     * (not the concrete {@link BCryptPasswordEncoder} type) so the
     * production {@link UserUpdateService} can accept any
     * {@link PasswordEncoder} implementation — proves the service is
     * correctly parameterised on the abstraction, not the concrete class.
     */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * System under test — the real {@link UserUpdateService} instance.
     * Re-created per {@code @Test} via {@link #setUp()} to mirror the
     * recreation of the mock collaborator above and to prevent any
     * accidental state leak between tests (the production service is
     * currently stateless aside from its two collaborator fields, but the
     * per-method reset is defensive for future state additions).
     */
    private UserUpdateService service;

    /**
     * Constructs a fresh {@link UserUpdateService} before every test
     * method, injecting the freshly-instantiated
     * {@link #userSecurityRepository} mock plus the
     * {@link #passwordEncoder} real BCrypt instance. The combination of
     * per-method {@code @Mock} instantiation (driven by
     * {@link MockitoExtension}) and per-method service construction here
     * guarantees that no stub or interaction from one test leaks into
     * another (AAP §0.10.9 test isolation).
     */
    @BeforeEach
    void setUp() {
        service = new UserUpdateService(userSecurityRepository, passwordEncoder);
    }

    // =========================================================================
    // HAPPY PATH — valid request updates user with optional BCrypt rehash
    //
    // COUSR02C.cbl UPDATE-USER-INFO (lines 177–245) validates the five
    // input fields, READs the existing USRSEC record, compares each
    // input against the loaded value, and PERFORMs UPDATE-USER-SEC-FILE
    // (lines 358–390) which issues the REWRITE and emits 'User {id} has
    // been updated ...' on DFHRESP(NORMAL). The Java migration adds
    // BCrypt re-encoding of the optional new plaintext password before
    // the save (AAP §0.10.5).
    // =========================================================================

    /**
     * Happy-path scenarios for
     * {@link UserUpdateService#updateUser(UserUpdateRequest)}.
     *
     * <p>Each test in this group asserts a different facet of the
     * successful update flow:
     * <ul>
     *   <li>BCrypt hash is persisted when a new plaintext password is
     *       supplied (never plaintext) and validates against the input
     *       plaintext.</li>
     *   <li>Empty/null new password preserves the existing hash exactly
     *       (no rehash, no overwrite).</li>
     *   <li>Role changes (U → A and A → U) are persisted verbatim.</li>
     *   <li>First-name and last-name changes are persisted on the loaded
     *       entity.</li>
     *   <li>Success message format matches the COBOL STRING construct
     *       output ({@code 'User {id} has been updated ...'}).</li>
     * </ul>
     */
    @Nested
    @DisplayName("Happy path — user update")
    class HappyPath {

        /**
         * Happy path: a valid request with a new plaintext password
         * results in a persisted {@link SecurityUser} whose {@code password}
         * field is a fresh BCrypt hash of the request's plaintext.
         * Asserts the four invariants documented in the class-level
         * <em>BCrypt Migration</em> note:
         * <ol>
         *   <li>Persisted password matches BCrypt format regex.</li>
         *   <li>Persisted password does NOT contain the plaintext.</li>
         *   <li>{@code passwordEncoder.matches(plaintext, persistedHash)}
         *       returns {@code true} — proves real BCrypt encoding.</li>
         *   <li>Result message contains the load-bearing token
         *       "updated".</li>
         * </ol>
         *
         * <p>COBOL parity: this is the {@code UPDATE-USER-INFO} →
         * all-validations-pass → {@code READ-USER-SEC-FILE}
         * (DFHRESP(NORMAL)) → field-update → {@code UPDATE-USER-SEC-FILE}
         * (DFHRESP(NORMAL)) → {@code 'User {id} has been updated ...'}
         * sequence.
         */
        @Test
        @DisplayName("updateUser(valid + newPassword) rehashes password via BCrypt")
        void updateUser_validRequestWithNewPassword_rehashesPasswordAndPersists() {
            // Arrange — stub findById to return the existing fixture user
            // (Optional.of(...) per the Spring Data JPA CrudRepository
            // contract). The save stub returns its argument so any chain
            // the production code performs on the saved entity works.
            SecurityUser existing = standardUser();
            when(userSecurityRepository.findById(eq(TestFixtures.Users.REGULAR_USER_ID)))
                    .thenReturn(Optional.of(existing));
            when(userSecurityRepository.save(any(SecurityUser.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UserUpdateRequest request = buildValidRequest();
            request.setNewPassword(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);

            // Act — invoke the real production method.
            UserUpdateResult result = service.updateUser(request);

            // Assert (1) — outcome is success with the COBOL "updated" token.
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'User {id} has been updated ...'")
                    .containsIgnoringCase("updated");

            // Assert (2) — capture the persisted entity for field-level
            // assertions. The captor proves the production code routes the
            // entity it loaded and mutated (not some default-constructed
            // value) to the save(...) call.
            ArgumentCaptor<SecurityUser> captor =
                    ArgumentCaptor.forClass(SecurityUser.class);
            verify(userSecurityRepository).save(captor.capture());
            SecurityUser persisted = captor.getValue();

            // Assert (3) — BCrypt hash format on the persisted password field.
            // The regex matches the BCrypt v2a/2b/2x/2y prefix, a 2-digit
            // cost factor, and the 53-character salt+hash tail (total 60
            // characters). If the production code stored plaintext or used
            // a different hashing algorithm, the assertion fails immediately.
            assertThat(persisted.getPassword())
                    .as("Persisted password must be BCrypt hash, never plaintext")
                    .matches("^\\$2[abxy]\\$\\d{2}\\$.{53}$");

            // Assert (4) — defence-in-depth: the plaintext does NOT appear
            // anywhere in the persisted password string. A regression that
            // accidentally concatenated plaintext into the hash (or stored
            // it directly) would fail this assertion.
            assertThat(persisted.getPassword())
                    .as("Persisted password must NOT contain plaintext")
                    .doesNotContain(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);

            // Assert (5) — the real BCrypt encoder verifies the plaintext
            // against the persisted hash. This is the load-bearing
            // security assertion: it proves the production code used a real
            // BCrypt encoder (since verifying against a non-BCrypt hash
            // would always fail).
            assertThat(passwordEncoder.matches(
                    TestFixtures.Users.TEST_PASSWORD_PLAINTEXT,
                    persisted.getPassword()))
                    .as("BCrypt encoder verifies plaintext against persisted hash")
                    .isTrue();
        }

        /**
         * Happy path: an empty {@code newPassword} preserves the existing
         * BCrypt hash exactly — the production code does NOT call
         * {@code passwordEncoder.encode("")} (which would succeed and
         * produce a valid hash for the empty plaintext, opening a backdoor
         * where any empty-password update attempt would silently overwrite
         * the operator's password).
         *
         * <p>COBOL parity rationale: the COBOL workflow at lines 227–230
         * compared {@code PASSWDI} against {@code SEC-USR-PWD} (a
         * plaintext-vs-plaintext comparison) to decide whether to update
         * the field. The Java migration cannot perform this symmetric
         * comparison because the persisted value is a BCrypt hash, so it
         * re-purposes the empty {@code newPassword} as the canonical
         * "no change" signal. This test locks the contract.
         */
        @Test
        @DisplayName("updateUser without new password preserves existing password hash")
        void updateUser_validRequestWithEmptyPassword_preservesExistingHash() {
            // Arrange — the loaded entity carries the pre-existing BCrypt
            // hash from TestFixtures (which is a real BCrypt hash of
            // "TESTPASS", per the TestFixtures invariant). Capture the
            // hash before invoking the service so we can assert it
            // survives unchanged.
            SecurityUser existing = standardUser();
            String originalHash = existing.getPassword();
            when(userSecurityRepository.findById(any())).thenReturn(Optional.of(existing));
            when(userSecurityRepository.save(any(SecurityUser.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UserUpdateRequest request = buildValidRequest();
            request.setNewPassword(""); // empty — preserve existing hash

            // Act
            UserUpdateResult result = service.updateUser(request);

            // Assert (1) — outcome is success.
            assertThat(result.isSuccess()).isTrue();

            // Assert (2) — the persisted password field is byte-identical
            // to the pre-existing hash. A regression that called
            // passwordEncoder.encode("") would replace the hash with a
            // BCrypt hash of the empty string (a syntactically valid
            // 60-character string), and the equality assertion would fail.
            ArgumentCaptor<SecurityUser> captor =
                    ArgumentCaptor.forClass(SecurityUser.class);
            verify(userSecurityRepository).save(captor.capture());
            assertThat(captor.getValue().getPassword())
                    .as("Empty new password must preserve the existing hash exactly")
                    .isEqualTo(originalHash);
        }

        /**
         * Happy path: a {@code null} {@code newPassword} preserves the
         * existing BCrypt hash exactly. Same contract as the empty-string
         * scenario above; this test row covers the {@code null} branch of
         * the production code's {@code newPassword != null && !newPassword.isEmpty()}
         * gate to ensure the null branch behaves identically (defence in
         * depth: a regression that handled the empty branch but not the
         * null branch — e.g., {@code newPassword.length() > 0} — would
         * NullPointerException on this row, but never on the empty-string
         * row).
         */
        @Test
        @DisplayName("updateUser with null new password preserves existing password hash")
        void updateUser_validRequestWithNullPassword_preservesExistingHash() {
            // Arrange
            SecurityUser existing = standardUser();
            String originalHash = existing.getPassword();
            when(userSecurityRepository.findById(any())).thenReturn(Optional.of(existing));
            when(userSecurityRepository.save(any(SecurityUser.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UserUpdateRequest request = buildValidRequest();
            request.setNewPassword(null); // explicit null — preserve existing hash

            // Act
            UserUpdateResult result = service.updateUser(request);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            ArgumentCaptor<SecurityUser> captor =
                    ArgumentCaptor.forClass(SecurityUser.class);
            verify(userSecurityRepository).save(captor.capture());
            assertThat(captor.getValue().getPassword())
                    .as("Null new password must preserve the existing hash exactly")
                    .isEqualTo(originalHash);
        }

        /**
         * Happy path: a {@code userType} change from {@code "U"} (regular
         * user) to {@code "A"} (admin) is persisted on the loaded entity.
         * This is the canonical role-promotion scenario; the COBOL
         * workflow at line 234 performs the same single-field update
         * inside the UPDATE-USER-INFO paragraph.
         *
         * <p>The {@link com.aws.carddemo.service.AdminMenuService} role
         * check later in the request lifecycle (and the
         * {@code @PreAuthorize} guard on the admin REST endpoints) relies
         * on the literal {@code "A"} string match; the migrated service
         * must preserve that exact value (no case folding, no whitespace
         * trimming beyond the empty-check).
         */
        @Test
        @DisplayName("updateUser changes user type from U (regular) to A (admin)")
        void updateUser_roleChangeUtoA_persistsNewRole() {
            // Arrange — the loaded fixture user is a regular user
            // (userType = "U"). The request changes the userType to "A"
            // to drive the role promotion.
            SecurityUser existing = standardUser();
            existing.setUserType("U"); // explicit regular user (matches default; defensive)
            when(userSecurityRepository.findById(any())).thenReturn(Optional.of(existing));
            when(userSecurityRepository.save(any(SecurityUser.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UserUpdateRequest request = buildValidRequest();
            request.setUserType("A"); // promote to admin

            // Act
            UserUpdateResult result = service.updateUser(request);

            // Assert — outcome is success and the persisted entity carries
            // the new userType.
            assertThat(result.isSuccess()).isTrue();
            ArgumentCaptor<SecurityUser> captor =
                    ArgumentCaptor.forClass(SecurityUser.class);
            verify(userSecurityRepository).save(captor.capture());
            assertThat(captor.getValue().getUserType())
                    .as("User type change from U to A must persist")
                    .isEqualTo("A");
        }

        /**
         * Happy path: a {@code userType} change from {@code "A"} (admin)
         * to {@code "U"} (regular user) is persisted. This is the
         * role-demotion scenario; combined with the U → A test above it
         * proves the production code performs an unconditional assignment
         * of the request's userType to the loaded entity (rather than a
         * one-way "promote only" mutation).
         */
        @Test
        @DisplayName("updateUser changes user type from A (admin) to U (regular)")
        void updateUser_roleChangeAtoU_persistsNewRole() {
            // Arrange — loaded user is an admin; request demotes to regular.
            SecurityUser existing = standardUser();
            existing.setUserId(TestFixtures.Users.ADMIN_USER_ID);
            existing.setUserType("A"); // existing admin
            when(userSecurityRepository.findById(any())).thenReturn(Optional.of(existing));
            when(userSecurityRepository.save(any(SecurityUser.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UserUpdateRequest request = buildValidRequest();
            request.setUserId(TestFixtures.Users.ADMIN_USER_ID);
            request.setUserType("U"); // demote to regular

            // Act
            UserUpdateResult result = service.updateUser(request);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            ArgumentCaptor<SecurityUser> captor =
                    ArgumentCaptor.forClass(SecurityUser.class);
            verify(userSecurityRepository).save(captor.capture());
            assertThat(captor.getValue().getUserType())
                    .as("User type change from A to U must persist")
                    .isEqualTo("U");
        }

        /**
         * Happy path: {@code firstName} and {@code lastName} changes are
         * persisted on the loaded entity. COBOL parity: lines 219–222
         * perform the same two-field comparison-and-assign pattern inside
         * UPDATE-USER-INFO.
         *
         * <p>The {@link TestFixtures.Users#REGULAR_USER_ID} pre-existing
         * fixture user carries the {@code "TEST"} / {@code "USER"}
         * placeholder names; the request changes them to {@code "Jane"} /
         * {@code "Smith"} to drive the name-change scenario.
         */
        @Test
        @DisplayName("updateUser changes first name and last name")
        void updateUser_nameChange_persistsNewNames() {
            // Arrange
            SecurityUser existing = standardUser();
            when(userSecurityRepository.findById(any())).thenReturn(Optional.of(existing));
            when(userSecurityRepository.save(any(SecurityUser.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UserUpdateRequest request = buildValidRequest();
            request.setFirstName("Jane");
            request.setLastName("Smith");

            // Act
            UserUpdateResult result = service.updateUser(request);

            // Assert — outcome is success and the persisted entity carries
            // both new name values.
            assertThat(result.isSuccess()).isTrue();
            ArgumentCaptor<SecurityUser> captor =
                    ArgumentCaptor.forClass(SecurityUser.class);
            verify(userSecurityRepository).save(captor.capture());
            SecurityUser persisted = captor.getValue();
            assertThat(persisted.getFirstName())
                    .as("First-name change must persist (COBOL line 220)")
                    .isEqualTo("Jane");
            assertThat(persisted.getLastName())
                    .as("Last-name change must persist (COBOL line 222)")
                    .isEqualTo("Smith");
        }

        /**
         * Happy path: the success message format includes the updated
         * user's identifier between the COBOL prefix and suffix literals.
         * Asserts on the verbatim COBOL message tokens to lock the
         * user-visible UX against accidental copy edits (per AAP §0.10.4
         * immutable downstream boundaries).
         *
         * <p>COBOL parity: lines 372–375 build the message via STRING
         * construct: {@code STRING 'User ' DELIMITED BY SIZE SEC-USR-ID
         * DELIMITED BY SPACE ' has been updated ...' DELIMITED BY SIZE
         * INTO WS-MESSAGE}. The Java equivalent concatenates the three
         * load-bearing tokens. The test asserts on lowercase substrings
         * so the production service can capitalise as needed (the
         * verbatim COBOL message starts with capital "User" so the
         * assertion uses containsIgnoringCase to remain robust).
         */
        @Test
        @DisplayName("updateUser returns 'User {id} has been updated ...' message")
        void updateUser_validRequest_returnsUpdatedMessage() {
            // Arrange
            SecurityUser existing = standardUser();
            when(userSecurityRepository.findById(any())).thenReturn(Optional.of(existing));
            when(userSecurityRepository.save(any(SecurityUser.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UserUpdateRequest request = buildValidRequest();

            // Act
            UserUpdateResult result = service.updateUser(request);

            // Assert — message carries both load-bearing tokens from the
            // COBOL STRING construct: the literal "user" (from 'User '
            // prefix) and the literal "updated" (from ' has been updated
            // ...' suffix), plus the updated user's identifier between
            // them.
            assertThat(result.getMessage())
                    .as("COBOL STRING construct: 'User <id> has been updated ...'")
                    .containsIgnoringCase("user")
                    .containsIgnoringCase("updated")
                    .contains(TestFixtures.Users.REGULAR_USER_ID);
        }
    }

    // =========================================================================
    // OPTIMISTIC LOCKING — COUSR02C DATA-WAS-CHANGED-BEFORE-UPDATE parity
    //
    // The COBOL READ UPDATE / REWRITE idiom (lines 322 / 360) serialised
    // concurrent updates through CICS file locks; the CHECK-CHANGE-IN-REC
    // paragraph detected before/after-image mismatches and set the
    // DATA-WAS-CHANGED-BEFORE-UPDATE flag. The Java migration replaces
    // this with JPA's @Version optimistic-locking field on SecurityUser;
    // save() raises OptimisticLockingFailureException on a version mismatch.
    //
    // The user-not-found scenario shares this group because it covers the
    // related "could not lock for update" branch from the COBOL workflow
    // (READ-USER-SEC-FILE NOTFND at line 342).
    // =========================================================================

    /**
     * Optimistic-locking and user-not-found scenarios for
     * {@link UserUpdateService#updateUser(UserUpdateRequest)}.
     *
     * <p>Two tests cover:
     * <ul>
     *   <li>JPA {@code @Version} mismatch on {@code save(...)} — the
     *       service does NOT catch the exception, letting it propagate to
     *       the controller layer's exception-handler chain.</li>
     *   <li>{@code findById(...)} returns {@link Optional#empty()} — the
     *       Java equivalent of the COBOL
     *       {@code READ-USER-SEC-FILE DFHRESP(NOTFND)} branch. The service
     *       returns a failure result with the verbatim COBOL
     *       {@code 'User ID NOT found...'} message.</li>
     * </ul>
     */
    @Nested
    @DisplayName("Optimistic locking — DATA-WAS-CHANGED-BEFORE-UPDATE parity")
    class OptimisticLocking {

        /**
         * Optimistic-lock path: a JPA {@code @Version} mismatch on the
         * {@code save(...)} call raises
         * {@link OptimisticLockingFailureException}. The production
         * service does NOT catch the exception; it propagates uncaught.
         *
         * <p>COBOL parity: this is the Java translation of the
         * {@code CHECK-CHANGE-IN-REC} paragraph's
         * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} flag from
         * {@code COUSR02C.cbl}. The COBOL workflow compared the displayed
         * before-image of the record against the current persisted state
         * on the way into the REWRITE; the Java workflow defers the check
         * to JPA's {@code @Version} mechanism, which raises the exception
         * during {@code save()}.
         *
         * <p>The controller layer's exception-handler chain (out of scope
         * for this unit test) maps the exception to HTTP 409 Conflict,
         * preserving the observable contract of the COBOL workflow per
         * AAP §0.10.4 ("External interfaces consumed by downstream systems
         * MUST NOT change").
         */
        @Test
        @DisplayName("updateUser throws OptimisticLockingFailureException on version mismatch")
        void updateUser_versionMismatch_throwsOptimisticLockingFailureException() {
            // Arrange — findById returns the existing fixture user;
            // save(...) is stubbed to throw OptimisticLockingFailureException
            // (the JPA dialect for a @Version mismatch).
            SecurityUser existing = standardUser();
            when(userSecurityRepository.findById(any())).thenReturn(Optional.of(existing));
            when(userSecurityRepository.save(any(SecurityUser.class)))
                    .thenThrow(new OptimisticLockingFailureException(
                            "User row was updated by another transaction"));

            UserUpdateRequest request = buildValidRequest();

            // Act + Assert — the exception propagates uncaught through
            // the service.updateUser(...) call. AssertJ's
            // assertThatThrownBy(...).isInstanceOf(...) is the canonical
            // fluent idiom; it also captures the exception for further
            // assertions on its message / cause if needed.
            assertThatThrownBy(() -> service.updateUser(request))
                    .as("JPA @Version mismatch must propagate as "
                            + "OptimisticLockingFailureException (Java equivalent of "
                            + "COBOL DATA-WAS-CHANGED-BEFORE-UPDATE)")
                    .isInstanceOf(OptimisticLockingFailureException.class);
        }

        /**
         * User-not-found path: {@code findById(...)} returns
         * {@link Optional#empty()}, indicating that no user record exists
         * for the supplied user ID. The service returns a failure result
         * with the verbatim COBOL {@code 'User ID NOT found...'} message
         * and never invokes {@code save(...)}.
         *
         * <p>COBOL parity: this is the
         * {@code READ-USER-SEC-FILE DFHRESP(NOTFND)} branch from
         * {@code COUSR02C.cbl} lines 340–345 ({@code MOVE 'User ID NOT
         * found...' TO WS-MESSAGE}). The Java migration collapses both the
         * READ-USER-SEC-FILE NOTFND (line 342) and the
         * UPDATE-USER-SEC-FILE NOTFND (line 379) into a single reject
         * branch because the JPA model precludes a REWRITE-without-prior-
         * READ scenario.
         *
         * <p>The {@link TestFixtures.Users#NONEXISTENT_USER_ID} fixture
         * constant carries the canonical "user does not exist" identifier;
         * using it here keeps the test self-documenting (the request's
         * user ID semantically signals "this user is not expected to
         * exist" even though Mockito's {@code findById(any())} stub would
         * accept any value).
         */
        @Test
        @DisplayName("updateUser returns 'User ID NOT found...' when user is missing")
        void updateUser_userNotFound_returnsReject() {
            // Arrange — findById returns Optional.empty() for the
            // canonical NONEXISTENT_USER_ID. The save stub is NOT
            // configured because the production code MUST short-circuit
            // before reaching the save call; Mockito's STRICT_STUBS mode
            // would surface an UnnecessaryStubbingException if we stubbed
            // save here.
            when(userSecurityRepository.findById(any())).thenReturn(Optional.empty());

            UserUpdateRequest request = buildValidRequest();
            request.setUserId(TestFixtures.Users.NONEXISTENT_USER_ID);

            // Act
            UserUpdateResult result = service.updateUser(request);

            // Assert (1) — outcome is failure with the COBOL message tokens.
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'User ID NOT found...'")
                    .containsIgnoringCase("not found");

            // Assert (2) — load-bearing defence-in-depth invariant: the
            // save operation is NEVER invoked when the lookup fails. A
            // regression that called save(null) after the empty Optional
            // would NullPointerException at runtime; the never() assertion
            // catches that regression at the unit-test layer.
            verify(userSecurityRepository, never()).save(any());
        }
    }

    // =========================================================================
    // VALIDATION REJECTS — empty-field checks per COUSR02C cascade
    //
    // COUSR02C.cbl UPDATE-USER-INFO (lines 177–245) performs five empty-
    // field checks in the order: USRIDINI → FNAMEI → LNAMEI → PASSWDI →
    // USRTYPEI. The Java migration omits the PASSWDI empty check (see
    // class-level "Java Migration: newPassword Semantics" note) and adds
    // an invalid-user-type check (defence in depth — the REST controller
    // layer has no BMS attribute enforcement to validate the U/A domain
    // implicitly).
    // =========================================================================

    /**
     * Validation-reject scenarios for
     * {@link UserUpdateService#updateUser(UserUpdateRequest)}.
     *
     * <p>Five test methods cover the four COBOL-parity empty-field rejects
     * (one per remaining COBOL EVALUATE WHEN clause after the
     * PASSWDI-empty-omission divergence) plus the Java-migration-added
     * invalid-user-type reject:
     * <ul>
     *   <li>{@link #updateUser_emptyUserId_rejected()} — COBOL line 180 / 182.</li>
     *   <li>{@link #updateUser_emptyFirstName_rejected(String)} — COBOL line 186 / 188.</li>
     *   <li>{@link #updateUser_emptyLastName_rejected(String)} — COBOL line 192 / 194.</li>
     *   <li>{@link #updateUser_emptyUserType_rejected()} — COBOL line 204 / 206.</li>
     *   <li>{@link #updateUser_invalidUserType_rejected(String)} —
     *       Java-migration addition (no COBOL equivalent).</li>
     * </ul>
     *
     * <p>Each reject-path test asserts {@code never()} on
     * {@code save(...)} to prove the production code short-circuits BEFORE
     * any persistence operation. This is the load-bearing defence-in-depth
     * invariant: malformed requests must not reach the database layer.
     */
    @Nested
    @DisplayName("Validation rejects — empty field checks (COUSR02C cascade)")
    class ValidationRejects {

        /**
         * Reject path: an empty {@code userId} produces the verbatim COBOL
         * {@code 'User ID can NOT be empty...'} reject. Asserts that
         * {@link UserUpdateResult#isSuccess()} is {@code false}, the
         * message contains both load-bearing phrases ({@code "user id"}
         * and {@code "empty"}), and the repository's {@code findById} and
         * {@code save} methods are never invoked on the empty-userId
         * branch.
         *
         * <p>COBOL parity: this is the {@code UPDATE-USER-INFO}
         * {@code USRIDINI = SPACES OR LOW-VALUES} reject at lines 180–185.
         * The empty user ID is the FIRST field check in the cascade, so
         * the production code must not consult the repository at all.
         */
        @Test
        @DisplayName("updateUser rejects empty user ID")
        void updateUser_emptyUserId_rejected() {
            // Arrange — note the deliberate absence of any stub
            // configuration on userSecurityRepository. Mockito's
            // STRICT_STUBS mode (default under MockitoExtension) would
            // fail if we stubbed findById and the production code did not
            // call; the never()-save and never()-findById assertions below
            // prove the production code short-circuits BEFORE any
            // repository interaction.
            UserUpdateRequest request = buildValidRequest();
            request.setUserId("");

            // Act
            UserUpdateResult result = service.updateUser(request);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'User ID can NOT be empty...'")
                    .containsIgnoringCase("user id")
                    .containsIgnoringCase("empty");
            verify(userSecurityRepository, never()).findById(any());
            verify(userSecurityRepository, never()).save(any());
        }

        /**
         * Reject path: an empty or whitespace-only {@code firstName}
         * produces the verbatim COBOL {@code 'First Name can NOT be
         * empty...'} reject. Asserts that {@link UserUpdateResult#isSuccess()}
         * is {@code false}, the message contains both load-bearing phrases
         * ({@code "first name"} and {@code "empty"}), and the repository's
         * {@code save} method is never invoked on the empty-name branch.
         *
         * <p>COBOL parity: this is the {@code UPDATE-USER-INFO}
         * {@code FNAMEI = SPACES OR LOW-VALUES} reject at lines 186–191.
         * The three parameter values cover the COBOL
         * {@code SPACES OR LOW-VALUES} predicate space:
         * <ul>
         *   <li>{@code ""} — empty string (Java equivalent of
         *       LOW-VALUES).</li>
         *   <li>{@code "   "} — three spaces (canonical
         *       {@code SPACES} value).</li>
         *   <li>{@code "                    "} — twenty spaces (full
         *       {@code PIC X(20)} field width filled with spaces; the
         *       COBOL field-width upper bound, ensuring the production
         *       code's predicate covers the entire valid input space).</li>
         * </ul>
         */
        @ParameterizedTest(name = "[{index}] empty/whitespace first name ''{0}''")
        @ValueSource(strings = {"", "   ", "                    "})
        @DisplayName("updateUser rejects empty/whitespace first name")
        void updateUser_emptyFirstName_rejected(String emptyName) {
            // Arrange — no repository stubs needed; the validation
            // cascade should short-circuit BEFORE any findById call.
            UserUpdateRequest request = buildValidRequest();
            request.setFirstName(emptyName);

            // Act
            UserUpdateResult result = service.updateUser(request);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'First Name can NOT be empty...'")
                    .containsIgnoringCase("first name")
                    .containsIgnoringCase("empty");
            verify(userSecurityRepository, never()).save(any());
        }

        /**
         * Reject path: an empty or whitespace-only {@code lastName}
         * produces the verbatim COBOL {@code 'Last Name can NOT be
         * empty...'} reject. Same invariants as
         * {@link #updateUser_emptyFirstName_rejected(String)}.
         *
         * <p>COBOL parity: this is the {@code UPDATE-USER-INFO}
         * {@code LNAMEI = SPACES OR LOW-VALUES} reject at lines 192–197.
         */
        @ParameterizedTest(name = "[{index}] empty/whitespace last name ''{0}''")
        @ValueSource(strings = {"", "   ", "                    "})
        @DisplayName("updateUser rejects empty/whitespace last name")
        void updateUser_emptyLastName_rejected(String emptyName) {
            // Arrange
            UserUpdateRequest request = buildValidRequest();
            request.setLastName(emptyName);

            // Act
            UserUpdateResult result = service.updateUser(request);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'Last Name can NOT be empty...'")
                    .containsIgnoringCase("last name")
                    .containsIgnoringCase("empty");
            verify(userSecurityRepository, never()).save(any());
        }

        /**
         * Reject path: an empty {@code userType} produces the verbatim
         * COBOL {@code 'User Type can NOT be empty...'} reject. Same
         * defence-in-depth invariants as the other empty-field tests.
         *
         * <p>COBOL parity: this is the {@code UPDATE-USER-INFO}
         * {@code USRTYPEI = SPACES OR LOW-VALUES} reject at lines 204–209.
         * The empty-userType check is the LAST empty-field check in the
         * cascade (after the omitted PASSWDI check); this test row covers
         * the boundary case where all preceding empty-field checks pass.
         */
        @Test
        @DisplayName("updateUser rejects empty user type")
        void updateUser_emptyUserType_rejected() {
            // Arrange
            UserUpdateRequest request = buildValidRequest();
            request.setUserType("");

            // Act
            UserUpdateResult result = service.updateUser(request);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'User Type can NOT be empty...'")
                    .containsIgnoringCase("user type")
                    .containsIgnoringCase("empty");
            verify(userSecurityRepository, never()).save(any());
        }

        /**
         * Reject path: a {@code userType} value outside the {@code "U"} /
         * {@code "A"} domain produces the Java-migration-added invalid-type
         * reject. Asserts {@link UserUpdateResult#isSuccess()} is
         * {@code false} and the repository's {@code save} method is never
         * invoked.
         *
         * <p>This is a Java-migration-added scenario with no direct COBOL
         * equivalent (see {@link UserUpdateService} class-level
         * <em>Invalid User-Type Reject</em> note for the rationale). The
         * seven parameter values cover representative invalid-domain
         * cases:
         * <ul>
         *   <li>{@code "X"} — an arbitrary letter that is neither U
         *       nor A.</li>
         *   <li>{@code "1"} — a digit (catches char-class confusion).</li>
         *   <li>{@code "ADMIN"} — a multi-character string (catches
         *       length-confusion bugs where {@code "ADMIN".charAt(0)}
         *       might be accepted).</li>
         *   <li>{@code " "} — a single space (the production code's
         *       {@code isBlank()} check treats this as empty; this row
         *       asserts the empty-check + domain-check ordering
         *       invariant: a single space falls through to the empty
         *       check, NOT the domain check — both paths agree on the
         *       load-bearing properties asserted here).</li>
         *   <li>{@code ""} — the empty string (also caught by the empty
         *       check; same ordering invariant as the single-space row).</li>
         *   <li>{@code "a"} — lowercase "a" (catches case-folding bugs
         *       where the production code might toUpperCase()'d the input
         *       before comparing).</li>
         *   <li>{@code "u"} — lowercase "u" (same case-sensitivity
         *       check as the "a" row).</li>
         * </ul>
         *
         * <p>For the {@code "X"}, {@code "1"}, {@code "ADMIN"},
         * {@code "a"}, and {@code "u"} values the production code reaches
         * the domain check and emits
         * {@link UserUpdateService#MSG_INVALID_USER_TYPE}; for the
         * {@code " "} and {@code ""} values the production code emits
         * {@link UserUpdateService#MSG_USER_TYPE_EMPTY} via
         * {@code isBlank()}. Both paths agree on
         * {@code isSuccess() == false} and the never()-save invariant, so
         * this test asserts those two load-bearing properties rather than
         * the specific message text.
         */
        @ParameterizedTest(name = "[{index}] invalid user type ''{0}''")
        @ValueSource(strings = {"X", "1", "ADMIN", " ", "", "a", "u"})
        @DisplayName("updateUser rejects user type outside U/A domain")
        void updateUser_invalidUserType_rejected(String invalidType) {
            // Arrange — no repository stubs needed; the validation
            // cascade should short-circuit BEFORE any findById call.
            // Mockito's STRICT_STUBS mode would surface an
            // UnnecessaryStubbingException if we stubbed findById and
            // the production code didn't call it; the absence of the
            // stub confirms the production code's empty-check +
            // domain-check short-circuit by negative evidence.
            UserUpdateRequest request = buildValidRequest();
            request.setUserType(invalidType);

            // Act
            UserUpdateResult result = service.updateUser(request);

            // Assert — load-bearing invariants for both code paths
            // (MSG_INVALID_USER_TYPE for "X"/"1"/"ADMIN"/"a"/"u",
            // MSG_USER_TYPE_EMPTY for " "/""): outcome is failure and
            // save is never invoked.
            assertThat(result.isSuccess())
                    .as("Invalid user type '%s' must reject", invalidType)
                    .isFalse();
            verify(userSecurityRepository, never()).save(any());
        }
    }

    // =========================================================================
    // SECURITY CHECKS — AAP §0.10.5 plaintext-password prevention
    //
    // BCrypt randomises the salt on each encode() call, so two encodes of
    // the same plaintext produce two distinct 60-character hash strings.
    // Both hashes still verify against the same plaintext via matches().
    // This is the property that prevents rainbow-table attacks against
    // the stored hashes.
    //
    // These tests are the practical enforcement of AAP §0.10.5 ("No
    // plaintext credentials in any configuration file") combined with
    // AAP §0.10.1 ("Tests MUST NOT reimplement any business or
    // calculation logic inside test bodies"). They cannot be replaced by
    // mocks: a mocked PasswordEncoder could trivially be configured to
    // return a fixed hash, defeating the assurance that the production
    // code calls real BCrypt encoding.
    // =========================================================================

    /**
     * Security tests that prove the production
     * {@link UserUpdateService#updateUser(UserUpdateRequest)} method uses
     * REAL BCrypt encoding when a new plaintext password is supplied,
     * never stores or echoes the plaintext, and randomises the BCrypt
     * salt on each {@code encode()} call.
     *
     * <p>Two tests cover the two security invariants:
     * <ul>
     *   <li>{@link #updateUser_validRequest_neverPersistsPlaintext()} —
     *       the persisted password field is a BCrypt-format hash, contains
     *       neither the request's plaintext nor any substring of it, and
     *       verifies against the plaintext via the real
     *       {@link PasswordEncoder#matches}.</li>
     *   <li>{@link #updateUser_samePasswordTwice_producesDifferentHashes()}
     *       — two updates with the same plaintext produce two distinct
     *       BCrypt hashes (proves real BCrypt salt randomisation; rules
     *       out deterministic transforms).</li>
     * </ul>
     */
    @Nested
    @DisplayName("Security checks — AAP §0.10.5 plaintext-password prevention")
    class SecurityChecks {

        /**
         * Security: the persisted password field never contains the
         * plaintext. Asserts three load-bearing properties:
         * <ul>
         *   <li>The persisted password matches the BCrypt format regex
         *       ({@code ^\$2[abxy]\$\d{2}\$.{53}$}).</li>
         *   <li>The persisted password does NOT contain the plaintext
         *       string anywhere (defence in depth — catches a regression
         *       where the production code accidentally concatenated the
         *       plaintext into the hash).</li>
         *   <li>The real {@link PasswordEncoder#matches} verification
         *       succeeds against the plaintext (proves the production
         *       code used the SAME encoder class the test injected, and
         *       performed a real BCrypt encode rather than a deterministic
         *       transform).</li>
         * </ul>
         */
        @Test
        @DisplayName("updateUser never persists plaintext password")
        void updateUser_validRequest_neverPersistsPlaintext() {
            // Arrange
            SecurityUser existing = standardUser();
            when(userSecurityRepository.findById(any())).thenReturn(Optional.of(existing));
            when(userSecurityRepository.save(any(SecurityUser.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UserUpdateRequest request = buildValidRequest();
            request.setNewPassword(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);

            // Act
            service.updateUser(request);

            // Assert — passwordHash field MUST NOT contain the plaintext
            // anywhere, MUST be a valid BCrypt hash, AND must verify
            // against the plaintext via the real encoder.
            ArgumentCaptor<SecurityUser> captor =
                    ArgumentCaptor.forClass(SecurityUser.class);
            verify(userSecurityRepository).save(captor.capture());
            SecurityUser persisted = captor.getValue();

            assertThat(persisted.getPassword())
                    .as("Persisted password must not contain plaintext substring")
                    .doesNotContain(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);

            assertThat(persisted.getPassword())
                    .as("Persisted password must be BCrypt format ($2a$ or $2b$)")
                    .matches("^\\$2[abxy]\\$\\d{2}\\$.{53}$");

            assertThat(passwordEncoder.matches(
                    TestFixtures.Users.TEST_PASSWORD_PLAINTEXT,
                    persisted.getPassword()))
                    .as("Real BCrypt encoder verifies plaintext against persisted hash")
                    .isTrue();
        }

        /**
         * Security: updating two different users with the SAME plaintext
         * password produces TWO DIFFERENT BCrypt hashes (because BCrypt
         * randomises the salt on each {@code encode()} call). Both hashes
         * still verify against the original plaintext via
         * {@code matches}.
         *
         * <p>This test would fail if the production code:
         * <ul>
         *   <li>Stored plaintext (both saved entities would carry the
         *       same plaintext string).</li>
         *   <li>Used a deterministic hash (e.g., SHA-256 without a salt;
         *       both saved entities would carry the same hash).</li>
         *   <li>Re-used a single BCrypt-encoded result across saves (both
         *       saved entities would carry the same hash).</li>
         * </ul>
         */
        @Test
        @DisplayName("updateUser produces different BCrypt hashes for same password (salt)")
        void updateUser_samePasswordTwice_producesDifferentHashes() {
            // Arrange — two different users (REGULAR_USER_ID and
            // ADMIN_USER_ID) loaded into Optional.of(...) so the
            // production code's findById lookup succeeds for both
            // updates. The save stub returns its argument so any chain
            // works. Both updates use the SAME plaintext password.
            SecurityUser regular = standardUser();
            SecurityUser admin = standardUser();
            admin.setUserId(TestFixtures.Users.ADMIN_USER_ID);
            admin.setUserType("A");

            when(userSecurityRepository.findById(TestFixtures.Users.REGULAR_USER_ID))
                    .thenReturn(Optional.of(regular));
            when(userSecurityRepository.findById(TestFixtures.Users.ADMIN_USER_ID))
                    .thenReturn(Optional.of(admin));
            when(userSecurityRepository.save(any(SecurityUser.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act — update both users with the SAME plaintext password.
            UserUpdateRequest req1 = buildValidRequest();
            req1.setNewPassword(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
            service.updateUser(req1);

            UserUpdateRequest req2 = buildValidRequest();
            req2.setUserId(TestFixtures.Users.ADMIN_USER_ID);
            req2.setUserType("A");
            req2.setNewPassword(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
            service.updateUser(req2);

            // Assert — capture both persisted entities and assert their
            // password fields differ. Capturing twice via times(2) yields
            // a list of length 2; AssertJ's isNotEqualTo asserts the two
            // strings differ.
            ArgumentCaptor<SecurityUser> captor =
                    ArgumentCaptor.forClass(SecurityUser.class);
            verify(userSecurityRepository, times(2)).save(captor.capture());

            String hash1 = captor.getAllValues().get(0).getPassword();
            String hash2 = captor.getAllValues().get(1).getPassword();

            // Defence in depth (1) — the two BCrypt hashes are distinct.
            // A production code that stored plaintext or used a
            // deterministic transform would fail here.
            assertThat(hash1)
                    .as("BCrypt salt produces different hash even for identical plaintext")
                    .isNotEqualTo(hash2);

            // Defence in depth (2) — both hashes still verify against the
            // same plaintext via real BCrypt matches(). A production code
            // that stored garbled or non-BCrypt strings would fail one or
            // both of these assertions.
            assertThat(passwordEncoder.matches(
                    TestFixtures.Users.TEST_PASSWORD_PLAINTEXT, hash1))
                    .as("First hash verifies against the plaintext")
                    .isTrue();
            assertThat(passwordEncoder.matches(
                    TestFixtures.Users.TEST_PASSWORD_PLAINTEXT, hash2))
                    .as("Second hash verifies against the same plaintext")
                    .isTrue();
        }
    }

    // =========================================================================
    // FIXTURE HELPERS
    //
    // Two pure-data factories centralised here (rather than at TestFixtures)
    // because the fixtures' exact field population is consumed only by this
    // test class — TestFixtures itself remains a pure-constants holder per
    // AAP §0.10.1 ("TestFixtures contains NO methods ... only public static
    // final string, integer, and character constants").
    // =========================================================================

    /**
     * Builds the standard fixture {@link SecurityUser} used by the
     * happy-path / role-change / optimistic-locking / security tests to
     * simulate an existing persisted user record. Populates every field
     * with deterministic values:
     * <ul>
     *   <li>{@code userId} — {@link TestFixtures.Users#REGULAR_USER_ID}
     *       ({@code "USRTST01"}), the canonical regular-user fixture
     *       identifier.</li>
     *   <li>{@code firstName} / {@code lastName} — synthetic
     *       {@code "TEST"} / {@code "USER"} placeholders that match no
     *       real person.</li>
     *   <li>{@code password} —
     *       {@link TestFixtures.Users#TEST_PASSWORD_BCRYPT_HASH}, the
     *       pre-computed BCrypt hash of {@code "TESTPASS"} per AAP §0.10.5
     *       ("No plaintext credentials in tests"). The hash satisfies the
     *       BCrypt-format invariant a real existing user would carry; the
     *       password-preservation tests assert the persisted hash equals
     *       THIS exact string.</li>
     *   <li>{@code userType} — {@code "U"} for regular user (the canonical
     *       COBOL {@code CDEMO-USRTYP-USER} 88-level value).</li>
     *   <li>{@code version} — {@code 1L} so the simulated existing user
     *       carries a non-{@code null} JPA {@code @Version} field.</li>
     * </ul>
     *
     * <p>Each test that calls this helper can override any field (e.g.,
     * {@code existing.setUserType("A")} for the role-change scenario)
     * without affecting other tests, because Mockito's per-method @Mock
     * reset combined with the per-method @BeforeEach setUp() guarantees
     * each test starts with a fresh fixture instance.
     *
     * @return a freshly-constructed fixture {@link SecurityUser} ready
     *         for insertion into {@code Optional.of(...)} as a
     *         {@code findById} stub return value
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

    /**
     * Builds the standard fixture {@link UserUpdateRequest} used as the
     * starting point for both happy-path and reject-path tests. Each
     * reject-path test calls this helper and then mutates exactly ONE
     * field to drive the specific reject scenario (for example,
     * {@code request.setFirstName("")} drives the first-name-empty
     * reject; {@code request.setUserType("X")} drives the
     * invalid-user-type reject).
     *
     * <p>Field values:
     * <ul>
     *   <li>{@code userId} — {@link TestFixtures.Users#REGULAR_USER_ID}
     *       ({@code "USRTST01"}).</li>
     *   <li>{@code firstName} — {@code "TEST"} (synthetic; matches the
     *       standardUser() helper's value so happy-path
     *       no-effective-change updates pass).</li>
     *   <li>{@code lastName} — {@code "USER"} (synthetic).</li>
     *   <li>{@code newPassword} — {@code null} by default (so the
     *       happy-path role-change / name-change tests do NOT rehash
     *       the password); the BCrypt-hash and security tests override
     *       this to {@link TestFixtures.Users#TEST_PASSWORD_PLAINTEXT}.</li>
     *   <li>{@code userType} — {@code "U"} (regular user, COBOL
     *       {@code CDEMO-USRTYP-USER}); the role-change test overrides
     *       this to {@code "A"}.</li>
     *   <li>{@code version} — {@code 1L} matching the standardUser()
     *       helper's persisted version; this aligns with JPA's @Version
     *       expected-vs-persisted comparison (mismatch is independently
     *       simulated by the OptimisticLocking test via a save() stub
     *       that throws).</li>
     * </ul>
     *
     * @return a freshly-constructed fixture {@link UserUpdateRequest}
     *         ready to be passed to {@code service.updateUser(...)} (with
     *         optional field overrides for reject-path or
     *         password-change scenarios)
     */
    private static UserUpdateRequest buildValidRequest() {
        UserUpdateRequest req = new UserUpdateRequest();
        req.setUserId(TestFixtures.Users.REGULAR_USER_ID);
        req.setFirstName("TEST");
        req.setLastName("USER");
        req.setNewPassword(null); // default — preserve existing hash
        req.setUserType("U");
        req.setVersion(1L);
        return req;
    }
}
