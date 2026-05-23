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
//     test uses TestFixtures.Users.REGULAR_USER_ID ("USRTST01", the
//     canonical 8-character regular-user identifier used by the happy /
//     duplicate-detection / rehash scenarios), TestFixtures.Users.
//     ADMIN_USER_ID ("ADMTST01", the canonical 8-character admin
//     identifier used by the admin-user-type happy scenario), TestFixtures.
//     Users.TEST_PASSWORD_PLAINTEXT ("TESTPASS", the synthetic 8-character
//     fixture password supplied as input to UserAddService and asserted
//     against the persisted BCrypt hash via passwordEncoder.matches(...) in
//     happy-path scenarios), and TestFixtures.Users.TEST_PASSWORD_BCRYPT_HASH
//     (the pre-computed BCrypt hash used by the standardUser() helper to
//     simulate an existing duplicate user record on the duplicate-detection
//     reject path).
//
//     Schema authority: file_schema.internal_imports[0] declares this
//     dependency explicitly.
//
//   * SecurityUser — the JPA entity returned by the repository on the
//     duplicate-detection lookup (Optional.of(standardUser())) and the
//     captured argument on the happy-path save(...) call. The entity is a
//     plain data carrier; no setters are inverted into the test, the helper
//     standardUser() populates the fields once.
//
//   * UserSecurityRepository — the Spring Data JPA repository the
//     production UserAddService delegates to. Mocked at the JPA-repository
//     boundary per AAP §0.10.1 ("Mocks limited to external boundaries:
//     file I/O, downstream service calls, database").
//
//   * UserAddService / UserAddRequest / UserAddResult — the production
//     classes under test. No explicit imports because they share this
//     test's package (`com.aws.carddemo.service`); Java resolves simple-
//     named references via package membership. This matches the convention
//     established by every other test under com.aws.carddemo.service
//     (AAP §0.10.10 style consistency).
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
//     fresh UserSecurityRepository mock and a fresh UserAddService.
//   * @DisplayName — human-readable scenario names on the outer class and
//     on each @Nested grouping (AAP §0.10.6 naming convention).
//   * @Nested — groups HappyPath / DuplicateDetection / ValidationRejects /
//     SecurityChecks scenarios into the four semantic sections that match
//     this test class's exports.members_exposed schema entries.
//   * @Test — single-execution test marker for non-parameterised
//     scenarios.
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
//   * @ParameterizedTest + @ValueSource(strings = ...) — drives the invalid
//     user-type rejection scenario across four representative invalid
//     values ("X", "B", "1", " "). The single space (" ") proves the
//     empty-check + domain-check ordering invariant: a single space passes
//     isBlank() only if the production code uses a "trim then empty" check;
//     since our production isBlank() does exactly that, " " is treated as
//     EMPTY (not INVALID_USER_TYPE). The "X", "B", and "1" rows are the
//     load-bearing invalid-domain cases.
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
// Spring Security crypto (file schema external_imports for
// spring-security-crypto; transitively brought in by
// spring-boot-starter-security in pom.xml).
//
//   * BCryptPasswordEncoder — the real (NOT mocked) implementation supplied
//     to the UserAddService under test. Per AAP §0.10.1 ("Real BCrypt
//     password encoder used to hash the fixture password and verify
//     password-matching semantics"). Strength 10 is the Spring Security
//     default and is fast enough for unit tests (~80 ms / hash; each
//     test cumulatively creates at most three hashes — well under the
//     AAP §0.7.2 "< 60 s total unit-suite wall-clock" target).
//   * PasswordEncoder — the Spring Security interface; the encoder is
//     held by the test as the interface type (not the concrete
//     BCryptPasswordEncoder type) so the production UserAddService can
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
//     Optional.empty() for the canonical "user does not exist yet" happy
//     and validation-reject scenarios (per Spring Data JPA CrudRepository
//     contract), and Optional.of(existingUser) for the duplicate-detection
//     scenario (parity with the COBOL DFHRESP(DUPKEY) / DFHRESP(DUPREC)
//     condition).
//   * List — return type of ArgumentCaptor.getAllValues() in the
//     SecurityChecks salt-uniqueness scenario, which captures two
//     SecurityUser instances saved during the same test method.
// ---------------------------------------------------------------------------
import java.util.List;
import java.util.Optional;

// ---------------------------------------------------------------------------
// Static imports — AssertJ fluent DSL + Mockito DSL (AAP §0.10.10 "All
// assertions use AssertJ (fluent) rather than mixing AssertJ + Hamcrest +
// Assertions.assertEquals"; all Mockito stubs use the
// when(...).thenReturn(...) style).
//
//   * Assertions.assertThat — fluent assertion entry point used throughout.
//   * ArgumentMatchers.any — relaxes argument matching in the never()
//     verifications and in stubs that don't care about the specific argument
//     value.
//   * Mockito.never — verifies that a stub method was NOT invoked; used to
//     prove that validation rejects short-circuit BEFORE the save call.
//   * Mockito.times — verifies the exact number of invocations; used in the
//     SecurityChecks salt-uniqueness scenario to assert two save calls when
//     adding two users with the same plaintext password.
//   * Mockito.verify — interaction assertion; pairs with .never(), .times(),
//     and ArgumentCaptor.
//   * Mockito.when — stub configuration; sets up the repository's return
//     values on the happy path (Optional.empty() from findById, the saved
//     entity from save) and the duplicate-detection path (Optional.of(...)
//     from findById).
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserAddService}, the migrated Java equivalent of the
 * 299-line CICS COBOL program {@code app/cbl/COUSR01C.cbl} (TRANID
 * {@code CU01}, the admin-only user-add dispatcher). The service creates a new
 * {@link SecurityUser} record in the {@code USRSEC} VSAM KSDS replacement
 * (PostgreSQL {@code security_users} table) with a BCrypt-hashed password
 * (instead of the original plaintext {@code PIC X(08)} password field).
 *
 * <h2>COBOL Provenance — COUSR01C.cbl Validation Order</h2>
 *
 * <ol>
 *   <li>First name empty (line 118) → {@code 'First Name can NOT be empty...'}</li>
 *   <li>Last name empty (line 124) → {@code 'Last Name can NOT be empty...'}</li>
 *   <li>User ID empty (line 130) → {@code 'User ID can NOT be empty...'}</li>
 *   <li>Password empty (line 136) → {@code 'Password can NOT be empty...'}</li>
 *   <li>User type empty (line 142) → {@code 'User Type can NOT be empty...'}</li>
 *   <li>Duplicate user ID (DUPKEY on WRITE USRSEC, line 263) →
 *       {@code 'User ID already exist...'}</li>
 * </ol>
 *
 * <h2>BCrypt Migration (AAP §0.10.5)</h2>
 *
 * <p>COBOL stored {@code SEC-USR-PWD PIC X(08)} as plaintext (line 157:
 * {@code MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD}). The Java migration uses
 * {@link BCryptPasswordEncoder} at strength 10 (Spring Security default).
 * Tests verify the following invariants:
 * <ul>
 *   <li>Persisted password field matches BCrypt format: {@code ^\$2[abxy]\$\d{2}\$.{53}$}.</li>
 *   <li>{@code passwordEncoder.matches(plaintext, persistedHash)} returns
 *       {@code true} — proves real BCrypt encoding, not a deterministic or
 *       reversible transform.</li>
 *   <li>Plaintext password does NOT appear anywhere in the persisted entity
 *       (defence-in-depth assertion via {@code doesNotContain(plaintext)}).</li>
 *   <li>Adding the same plaintext password twice produces TWO DIFFERENT
 *       BCrypt hashes — proves the production code calls real BCrypt
 *       (which randomises the salt on each {@code encode()}), not a
 *       deterministic transform.</li>
 * </ul>
 *
 * <h2>Test Coverage Matrix</h2>
 *
 * <table border="1">
 *   <caption>User-add test coverage matrix</caption>
 *   <tr><th>Branch</th><th>Test method</th></tr>
 *   <tr><td>Happy path — regular user with BCrypt-hashed password</td>
 *       <td>{@link HappyPath#addUser_validRequest_persistsWithBcryptHash()}</td></tr>
 *   <tr><td>Happy path — all 5 fields populated correctly</td>
 *       <td>{@link HappyPath#addUser_validRequest_persistsAllFields()}</td></tr>
 *   <tr><td>Happy path — admin user type</td>
 *       <td>{@link HappyPath#addUser_adminUserType_persistsAsAdmin()}</td></tr>
 *   <tr><td>Happy path — success message format</td>
 *       <td>{@link HappyPath#addUser_validRequest_returnsAddedMessage()}</td></tr>
 *   <tr><td>Reject — duplicate user ID (DUPKEY parity)</td>
 *       <td>{@link DuplicateDetection#addUser_duplicateUserId_rejectsWithAlreadyExistsMessage()}</td></tr>
 *   <tr><td>Reject — empty first name (COBOL line 118)</td>
 *       <td>{@link ValidationRejects#addUser_emptyFirstName_rejectsWithFirstNameEmptyMessage()}</td></tr>
 *   <tr><td>Reject — empty last name (COBOL line 124)</td>
 *       <td>{@link ValidationRejects#addUser_emptyLastName_rejectsWithLastNameEmptyMessage()}</td></tr>
 *   <tr><td>Reject — empty user ID (COBOL line 130)</td>
 *       <td>{@link ValidationRejects#addUser_emptyUserId_rejectsWithUserIdEmptyMessage()}</td></tr>
 *   <tr><td>Reject — empty password (COBOL line 136)</td>
 *       <td>{@link ValidationRejects#addUser_emptyPassword_rejectsWithPasswordEmptyMessage()}</td></tr>
 *   <tr><td>Reject — empty user type (COBOL line 142)</td>
 *       <td>{@link ValidationRejects#addUser_emptyUserType_rejectsWithUserTypeEmptyMessage()}</td></tr>
 *   <tr><td>Reject — invalid user type (Java-migration addition)</td>
 *       <td>{@link ValidationRejects#addUser_invalidUserType_rejected(String)}</td></tr>
 *   <tr><td>Security — BCrypt salt uniqueness</td>
 *       <td>{@link SecurityChecks#addUser_samePasswordTwice_producesDifferentHashes()}</td></tr>
 * </table>
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>Tests instantiate the real {@link UserAddService} via its public
 * two-argument constructor and assert on the {@link UserAddResult} returned
 * by the real {@link UserAddService#addUser(UserAddRequest)} method. The
 * only mocked collaborator is the {@link UserSecurityRepository} (database
 * boundary, the only mock category permitted under AAP §0.10.1). The
 * {@link BCryptPasswordEncoder} is REAL — never mocked — because verifying
 * real BCrypt semantics is the entire point of the security tests in this
 * class (a mocked encoder could trivially be configured to return any
 * value, defeating the assurance that the production code calls real
 * BCrypt encoding).
 *
 * <p>No business logic — empty checks, user-type domain check, BCrypt
 * encoding, duplicate detection, response construction — is duplicated in
 * any test body; the tests assert only on observable outputs
 * ({@link UserAddResult#isSuccess()}, {@link UserAddResult#getMessage()},
 * and the {@link SecurityUser} captured by {@link ArgumentCaptor}).
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
 * @see UserAddService
 * @see UserAddRequest
 * @see UserAddResult
 * @see SecurityUser
 * @see UserSecurityRepository
 * @see TestFixtures.Users
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserAddService — COUSR01C.cbl migration parity")
final class UserAddServiceTest {

    /**
     * Mocked {@link UserSecurityRepository} — the JPA repository boundary the
     * production {@link UserAddService} delegates to. Per AAP §0.10.1, the
     * only mocked collaborator (database is one of the four permitted mock
     * categories). Re-created per {@code @Test} method by
     * {@link MockitoExtension}, ensuring strict isolation between scenarios.
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Real {@link BCryptPasswordEncoder} at strength 10 (Spring Security default).
     *
     * <p>NEVER mocked. The whole point of the security tests in this class is
     * to prove that the production {@link UserAddService} calls
     * {@code encode(plaintext)} on a real {@link PasswordEncoder} rather than
     * storing plaintext or using a deterministic transform. Mocking the
     * encoder would defeat the test.
     *
     * <p>Strength 10 produces hashes in roughly 80 ms on commodity hardware;
     * this class's tests cumulatively create well under one second of BCrypt
     * work (at most three encode() calls in the salt-uniqueness scenario),
     * comfortably under the AAP §0.7.2 wall-clock budget.
     */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * System under test — the real {@link UserAddService} instance.
     * Re-created per {@code @Test} via {@link #setUp()} to mirror the
     * recreation of the mock collaborator above and to prevent any accidental
     * state leak between tests (the production service is currently stateless
     * aside from its two collaborator fields, but the per-method reset is
     * defensive for future state additions).
     */
    private UserAddService service;

    /**
     * Constructs a fresh {@link UserAddService} before every test method,
     * injecting the freshly-instantiated {@link #userSecurityRepository}
     * mock plus the {@link #passwordEncoder} real BCrypt instance. The
     * combination of per-method {@code @Mock} instantiation (driven by
     * {@link MockitoExtension}) and per-method service construction here
     * guarantees that no stub or interaction from one test leaks into another
     * (AAP §0.10.9 test isolation).
     */
    @BeforeEach
    void setUp() {
        service = new UserAddService(userSecurityRepository, passwordEncoder);
    }

    // =========================================================================
    // HAPPY PATH — valid request persists user with BCrypt-hashed password
    //
    // COUSR01C.cbl PROCESS-ENTER-KEY (lines 115–160) passes all five empty-
    // field validations and PERFORMs WRITE-USER-SEC-FILE (line 159), which
    // returns DFHRESP(NORMAL) (line 251), triggering the STRING construct
    // 'User ' SEC-USR-ID ' has been added ...' (lines 255–258). The Java
    // migration adds BCrypt encoding of the plaintext password before the
    // save (AAP §0.10.5).
    // =========================================================================

    /**
     * Happy-path scenarios for {@link UserAddService#addUser(UserAddRequest)}.
     *
     * <p>Each test in this group asserts a different facet of the successful
     * add flow:
     * <ul>
     *   <li>BCrypt hash is persisted (never plaintext) and validates against
     *       the input plaintext</li>
     *   <li>All five COBOL input fields (user ID, first name, last name,
     *       user type, password) are populated on the persisted entity</li>
     *   <li>Admin user type ({@code "A"}) is preserved through to the
     *       persisted entity</li>
     *   <li>Success message format matches the COBOL STRING construct
     *       output</li>
     * </ul>
     */
    @Nested
    @DisplayName("Happy path — user creation")
    class HappyPath {

        /**
         * Happy path: a valid request results in a persisted {@link SecurityUser}
         * whose {@code password} field is a BCrypt hash of the request's
         * plaintext password. Asserts the four invariants documented in the
         * class-level <em>BCrypt Migration</em> note:
         * <ol>
         *   <li>Persisted password matches BCrypt format regex.</li>
         *   <li>Persisted password does NOT contain the plaintext.</li>
         *   <li>{@code passwordEncoder.matches(plaintext, persistedHash)}
         *       returns {@code true} — proves real BCrypt encoding.</li>
         *   <li>Result message contains the load-bearing token "added".</li>
         * </ol>
         *
         * <p>COBOL parity: this is the
         * {@code PROCESS-ENTER-KEY} → all-validations-pass →
         * {@code WRITE-USER-SEC-FILE} (DFHRESP(NORMAL)) →
         * {@code 'User {id} has been added ...'} sequence.
         */
        @Test
        @DisplayName("addUser(valid) persists with BCrypt-hashed password")
        void addUser_validRequest_persistsWithBcryptHash() {
            // Arrange — stub findById to return Optional.empty() so the
            // duplicate-key pre-check passes; stub save to return its
            // argument (typical JPA save semantics) so the production
            // service's save(...).getUserId() chain (if any) works.
            when(userSecurityRepository.findById(TestFixtures.Users.REGULAR_USER_ID))
                    .thenReturn(Optional.empty());
            when(userSecurityRepository.save(any(SecurityUser.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UserAddRequest request = buildValidRequest();

            // Act — invoke the real production method.
            UserAddResult result = service.addUser(request);

            // Assert (1) — outcome is success with the COBOL "added" token.
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'User {id} has been added ...'")
                    .containsIgnoringCase("added");

            // Assert (2) — capture the persisted entity for field-level
            // assertions. The captor proves the production code routes the
            // entity it constructed (not some default-constructed value) to
            // the save(...) call.
            ArgumentCaptor<SecurityUser> captor =
                    ArgumentCaptor.forClass(SecurityUser.class);
            verify(userSecurityRepository).save(captor.capture());
            SecurityUser persisted = captor.getValue();

            // Assert (3) — BCrypt hash format on the persisted password field.
            // The regex matches the BCrypt v2a/2b/2x/2y prefix, a 2-digit
            // cost factor, and the 53-character salt+hash tail (total 60
            // characters). If the production code stored plaintext or used a
            // different hashing algorithm, the assertion fails immediately.
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
            // against the persisted hash. This is the load-bearing security
            // assertion: it proves the production code used the SAME encoder
            // class the test injected (since BCrypt verifies are
            // cross-instance-compatible, but verifying against a non-BCrypt
            // hash would always fail).
            assertThat(passwordEncoder.matches(
                    TestFixtures.Users.TEST_PASSWORD_PLAINTEXT,
                    persisted.getPassword()))
                    .as("BCrypt encoder verifies plaintext against persisted hash")
                    .isTrue();
        }

        /**
         * Happy path: a valid request results in a persisted
         * {@link SecurityUser} carrying all five COBOL input fields
         * ({@code SEC-USR-ID}, {@code SEC-USR-FNAME}, {@code SEC-USR-LNAME},
         * {@code SEC-USR-TYPE}, plus the BCrypt-hashed password). Asserts on
         * the four observable string fields; the password field is asserted
         * separately by {@link #addUser_validRequest_persistsWithBcryptHash()}.
         *
         * <p>COBOL parity: lines 154–158 perform five MOVE statements that
         * copy the input fields verbatim onto the SEC-USER-DATA record before
         * the WRITE. The Java migration performs the same five field
         * assignments inside {@code addUser(...)} immediately before the
         * {@code repository.save(...)} call.
         */
        @Test
        @DisplayName("addUser persists user ID, first name, last name, user type")
        void addUser_validRequest_persistsAllFields() {
            // Arrange — same stub setup as the BCrypt scenario.
            when(userSecurityRepository.findById(any())).thenReturn(Optional.empty());
            when(userSecurityRepository.save(any(SecurityUser.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UserAddRequest request = buildValidRequest();

            // Act
            service.addUser(request);

            // Assert — capture the persisted entity and verify each field
            // matches the corresponding request field. The "TEST" / "USER"
            // literals are the canonical fixture names used by
            // buildValidRequest(); they have no security significance and
            // are documented in TestFixtures.
            ArgumentCaptor<SecurityUser> captor =
                    ArgumentCaptor.forClass(SecurityUser.class);
            verify(userSecurityRepository).save(captor.capture());
            SecurityUser persisted = captor.getValue();

            assertThat(persisted.getUserId())
                    .as("Persisted SEC-USR-ID matches request (line 154 MOVE)")
                    .isEqualTo(TestFixtures.Users.REGULAR_USER_ID);
            assertThat(persisted.getFirstName())
                    .as("Persisted SEC-USR-FNAME matches request (line 155 MOVE)")
                    .isEqualTo("TEST");
            assertThat(persisted.getLastName())
                    .as("Persisted SEC-USR-LNAME matches request (line 156 MOVE)")
                    .isEqualTo("USER");
            assertThat(persisted.getUserType())
                    .as("Persisted SEC-USR-TYPE matches request (line 158 MOVE)")
                    .isEqualTo("U");
        }

        /**
         * Happy path: an admin-user request (userType = {@code "A"}) is
         * persisted with the admin user-type code preserved verbatim. The
         * COBOL {@code CDEMO-USRTYP-ADMIN} 88-level value is the literal
         * {@code "A"}; the migrated service must preserve that exact value
         * (the {@link com.aws.carddemo.service.AdminMenuService} role check
         * later in the request lifecycle relies on the literal string match).
         *
         * <p>The {@link TestFixtures.Users#ADMIN_USER_ID} constant is used
         * for the user identifier so the request is internally consistent
         * (the fixture admin ID maps to the fixture admin user type), not
         * because the service distinguishes the IDs in any other way.
         */
        @Test
        @DisplayName("addUser admin (userType=A) persists with admin role")
        void addUser_adminUserType_persistsAsAdmin() {
            // Arrange — stub findById to return Optional.empty() for the
            // admin user ID (so the duplicate-key pre-check passes). The
            // save stub returns the entity (typical JPA save).
            when(userSecurityRepository.findById(any())).thenReturn(Optional.empty());
            when(userSecurityRepository.save(any(SecurityUser.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Build a valid request, then override the userId and userType
            // to drive the admin scenario. Reusing buildValidRequest() and
            // overriding two fields keeps the test compact and reuses the
            // five-field fixture pattern.
            UserAddRequest request = buildValidRequest();
            request.setUserId(TestFixtures.Users.ADMIN_USER_ID);
            request.setUserType("A");

            // Act
            service.addUser(request);

            // Assert — only the user type matters for this test; the other
            // four field assertions are covered by
            // addUser_validRequest_persistsAllFields().
            ArgumentCaptor<SecurityUser> captor =
                    ArgumentCaptor.forClass(SecurityUser.class);
            verify(userSecurityRepository).save(captor.capture());
            assertThat(captor.getValue().getUserType())
                    .as("Persisted SEC-USR-TYPE 'A' matches CDEMO-USRTYP-ADMIN")
                    .isEqualTo("A");
        }

        /**
         * Happy path: the success message format includes the new user's
         * identifier between the COBOL prefix and suffix literals. Asserts
         * on the verbatim COBOL message tokens to lock the user-visible
         * UX against accidental copy edits (per AAP §0.10.4 immutable
         * downstream boundaries).
         *
         * <p>COBOL parity: lines 255–258 build the message via STRING
         * construct: {@code STRING 'User ' DELIMITED BY SIZE SEC-USR-ID
         * DELIMITED BY SPACE ' has been added ...' DELIMITED BY SIZE INTO
         * WS-MESSAGE}. The Java equivalent concatenates the three
         * load-bearing tokens. The test asserts on lowercase substrings so
         * the production service can capitalise as needed.
         */
        @Test
        @DisplayName("addUser returns 'User {id} has been added ...' message")
        void addUser_validRequest_returnsAddedMessage() {
            // Arrange
            when(userSecurityRepository.findById(any())).thenReturn(Optional.empty());
            when(userSecurityRepository.save(any(SecurityUser.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UserAddRequest request = buildValidRequest();

            // Act
            UserAddResult result = service.addUser(request);

            // Assert — message carries both load-bearing tokens from the
            // COBOL STRING construct: the literal "user" (from 'User ' prefix)
            // and the literal "added" (from ' has been added ...' suffix),
            // plus the new user's identifier between them.
            assertThat(result.getMessage())
                    .as("COBOL STRING construct: 'User <id> has been added ...'")
                    .containsIgnoringCase("user")
                    .containsIgnoringCase("added")
                    .contains(TestFixtures.Users.REGULAR_USER_ID);
        }
    }

    // =========================================================================
    // DUPLICATE DETECTION — DFHRESP(DUPKEY) parity via findById pre-check
    //
    // COUSR01C.cbl WRITE-USER-SEC-FILE (lines 238–274) handles the
    // duplicate-key condition at lines 260–266 by inspecting WS-RESP-CD for
    // DFHRESP(DUPKEY) or DFHRESP(DUPREC) and emitting 'User ID already
    // exist...'. The Java migration short-circuits this via a findById
    // pre-check (see UserAddService class-level "Java Migration: Duplicate
    // Detection via findById Pre-Check" note); behaviour is observationally
    // equivalent to the COBOL DUPKEY branch.
    // =========================================================================

    /**
     * Duplicate-detection scenarios for
     * {@link UserAddService#addUser(UserAddRequest)}.
     *
     * <p>The single test in this group exercises the canonical duplicate-key
     * scenario: the requested user already exists in the repository. The
     * production code's findById pre-check returns
     * {@code Optional.of(existing)} → the service rejects without calling
     * save(...). The never()-save assertion is the load-bearing
     * defence-in-depth invariant: a regression that re-attempted the save
     * after the pre-check would corrupt the existing user's data.
     */
    @Nested
    @DisplayName("Duplicate detection — DUPKEY parity")
    class DuplicateDetection {

        /**
         * Reject path: a request for a user ID that already exists yields
         * {@link UserAddResult#isSuccess()} = {@code false} with the COBOL
         * {@code 'User ID already exist...'} message; the save operation is
         * never invoked (the persistence layer never sees the duplicate
         * request).
         *
         * <p>COBOL parity: COUSR01C.cbl lines 260–266 emit the
         * {@code 'User ID already exist...'} message when
         * {@code WS-RESP-CD = DFHRESP(DUPKEY)} or {@code DFHRESP(DUPREC)}
         * after the {@code EXEC CICS WRITE}. The Java migration short-
         * circuits this via the findById pre-check (see UserAddService
         * class-level note for the rationale).
         *
         * <p>The {@code containsIgnoringCase("already exist")} matcher
         * anchors on the load-bearing two-token phrase, tolerating the
         * verbatim COBOL "exist" (singular, missing the trailing "s")
         * without forcing a particular tense — the production code may
         * preserve the verbatim COBOL phrasing or modernise to "exists".
         */
        @Test
        @DisplayName("addUser rejects when user ID already exists")
        void addUser_duplicateUserId_rejectsWithAlreadyExistsMessage() {
            // Arrange — the existing user is built by standardUser() and
            // carries TEST_PASSWORD_BCRYPT_HASH per the test schema (the
            // helper simulates a previously-persisted user record). The
            // findById stub returns Optional.of(existing) to trigger the
            // duplicate-key branch in the production service.
            SecurityUser existing = standardUser();
            when(userSecurityRepository.findById(TestFixtures.Users.REGULAR_USER_ID))
                    .thenReturn(Optional.of(existing));

            UserAddRequest request = buildValidRequest();

            // Act
            UserAddResult result = service.addUser(request);

            // Assert (1) — outcome is failure with the COBOL message tokens.
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'User ID already exist...'")
                    .containsIgnoringCase("already exist");

            // Assert (2) — load-bearing defence-in-depth invariant: the
            // save operation is NEVER invoked on the duplicate-detection
            // branch. A regression that re-attempted the save after the
            // pre-check would either corrupt the existing user's data
            // (via JPA merge semantics) or throw
            // DataIntegrityViolationException at the database layer; the
            // never() assertion catches the former at the unit-test layer.
            verify(userSecurityRepository, never()).save(any());
        }
    }

    // =========================================================================
    // VALIDATION REJECTS — empty-field checks per COUSR01C cascade
    //
    // COUSR01C.cbl PROCESS-ENTER-KEY (lines 115–160) performs five empty-
    // field checks in the order: first name → last name → user ID →
    // password → user type. Each EVALUATE WHEN clause emits a distinct
    // reject message and skips the WRITE-USER-SEC-FILE invocation. The
    // Java migration preserves the cascade order verbatim.
    // =========================================================================

    /**
     * Validation-reject scenarios for
     * {@link UserAddService#addUser(UserAddRequest)}.
     *
     * <p>Six tests cover the five empty-field rejects (one per COBOL
     * EVALUATE WHEN clause) plus the Java-migration-added invalid-user-type
     * reject:
     * <ul>
     *   <li>{@link #addUser_emptyFirstName_rejectsWithFirstNameEmptyMessage()}
     *       — COBOL line 118 / 120.</li>
     *   <li>{@link #addUser_emptyLastName_rejectsWithLastNameEmptyMessage()}
     *       — COBOL line 124 / 126.</li>
     *   <li>{@link #addUser_emptyUserId_rejectsWithUserIdEmptyMessage()}
     *       — COBOL line 130 / 132.</li>
     *   <li>{@link #addUser_emptyPassword_rejectsWithPasswordEmptyMessage()}
     *       — COBOL line 136 / 138.</li>
     *   <li>{@link #addUser_emptyUserType_rejectsWithUserTypeEmptyMessage()}
     *       — COBOL line 142 / 144.</li>
     *   <li>{@link #addUser_invalidUserType_rejected(String)} —
     *       Java-migration addition (no COBOL equivalent).</li>
     * </ul>
     *
     * <p>Each reject-path test asserts {@code never()} on
     * {@code save(...)} to prove the production code short-circuits BEFORE
     * any persistence operation. This is the load-bearing defence-in-depth
     * invariant: malformed requests must not reach the database layer.
     */
    @Nested
    @DisplayName("Validation rejects — empty field checks (COUSR01C order)")
    class ValidationRejects {

        /**
         * Reject path: an empty {@code firstName} produces the verbatim COBOL
         * {@code 'First Name can NOT be empty...'} reject. Asserts that
         * {@link UserAddResult#isSuccess()} is {@code false}, the message
         * contains both load-bearing phrases ({@code "first name"} and
         * {@code "empty"}), and the repository's {@code save} method is never
         * invoked on the empty-name branch.
         *
         * <p>COBOL parity: this is the {@code PROCESS-ENTER-KEY}
         * {@code FNAMEI = SPACES OR LOW-VALUES} reject at lines 118–123.
         * The empty string {@code ""} drives the test rather than
         * {@code null} or whitespace because COBOL's
         * {@code SPACES OR LOW-VALUES} predicate matches both empty and
         * blank; the production code treats null, empty, and whitespace
         * uniformly per the {@link UserAddService} isBlank() helper.
         */
        @Test
        @DisplayName("addUser rejects empty first name")
        void addUser_emptyFirstName_rejectsWithFirstNameEmptyMessage() {
            // Arrange — note the deliberate absence of any stub
            // configuration on userSecurityRepository. Mockito's STRICT_STUBS
            // mode (default under MockitoExtension) would fail if we stubbed
            // and the production code did not call; conversely, the
            // never()-save assertion below proves the production code does
            // not reach the persistence operation.
            UserAddRequest request = buildValidRequest();
            request.setFirstName("");

            // Act
            UserAddResult result = service.addUser(request);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'First Name can NOT be empty...'")
                    .containsIgnoringCase("first name")
                    .containsIgnoringCase("empty");
            verify(userSecurityRepository, never()).save(any());
        }

        /**
         * Reject path: an empty {@code lastName} produces the verbatim COBOL
         * {@code 'Last Name can NOT be empty...'} reject. Same defence-in-
         * depth invariants as the empty-first-name test.
         *
         * <p>COBOL parity: lines 124–129.
         */
        @Test
        @DisplayName("addUser rejects empty last name")
        void addUser_emptyLastName_rejectsWithLastNameEmptyMessage() {
            UserAddRequest request = buildValidRequest();
            request.setLastName("");

            UserAddResult result = service.addUser(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'Last Name can NOT be empty...'")
                    .containsIgnoringCase("last name")
                    .containsIgnoringCase("empty");
            verify(userSecurityRepository, never()).save(any());
        }

        /**
         * Reject path: an empty {@code userId} produces the verbatim COBOL
         * {@code 'User ID can NOT be empty...'} reject. Same defence-in-
         * depth invariants as the other empty-field tests.
         *
         * <p>COBOL parity: lines 130–135.
         */
        @Test
        @DisplayName("addUser rejects empty user ID")
        void addUser_emptyUserId_rejectsWithUserIdEmptyMessage() {
            UserAddRequest request = buildValidRequest();
            request.setUserId("");

            UserAddResult result = service.addUser(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'User ID can NOT be empty...'")
                    .containsIgnoringCase("user id")
                    .containsIgnoringCase("empty");
            verify(userSecurityRepository, never()).save(any());
        }

        /**
         * Reject path: an empty {@code password} produces the verbatim COBOL
         * {@code 'Password can NOT be empty...'} reject. The defence-in-depth
         * invariant is doubly important here because the production code
         * would otherwise attempt to BCrypt-encode an empty string (which
         * succeeds and produces a valid hash for the empty plaintext, opening
         * a backdoor where any empty-password attempt would succeed) —
         * verify(...).never().save() catches that regression.
         *
         * <p>COBOL parity: lines 136–141.
         */
        @Test
        @DisplayName("addUser rejects empty password")
        void addUser_emptyPassword_rejectsWithPasswordEmptyMessage() {
            UserAddRequest request = buildValidRequest();
            request.setPassword("");

            UserAddResult result = service.addUser(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'Password can NOT be empty...'")
                    .containsIgnoringCase("password")
                    .containsIgnoringCase("empty");
            verify(userSecurityRepository, never()).save(any());
        }

        /**
         * Reject path: an empty {@code userType} produces the verbatim COBOL
         * {@code 'User Type can NOT be empty...'} reject. Same defence-in-
         * depth invariants as the other empty-field tests.
         *
         * <p>COBOL parity: lines 142–147.
         */
        @Test
        @DisplayName("addUser rejects empty user type")
        void addUser_emptyUserType_rejectsWithUserTypeEmptyMessage() {
            UserAddRequest request = buildValidRequest();
            request.setUserType("");

            UserAddResult result = service.addUser(request);

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
         * reject. Asserts {@link UserAddResult#isSuccess()} is {@code false}
         * and the repository's {@code save} method is never invoked.
         *
         * <p>This is a Java-migration-added scenario with no direct COBOL
         * equivalent (see {@link UserAddService} class-level
         * <em>Java Migration: Invalid User-Type Reject</em> note for the
         * rationale). The four parameter values cover representative
         * invalid-domain cases:
         * <ul>
         *   <li>{@code "X"} — an arbitrary letter that is neither U nor A</li>
         *   <li>{@code "B"} — a letter alphabetically adjacent to A
         *       (catches off-by-one bugs in a hypothetical range check)</li>
         *   <li>{@code "1"} — a digit (catches char-class confusion)</li>
         *   <li>{@code " "} — a single space (treated as empty by the
         *       production code's isBlank() check; this row asserts the
         *       empty-check + domain-check ordering invariant: a single
         *       space passes the cascade BEFORE the domain check fires)</li>
         * </ul>
         *
         * <p>For the first three values the production code reaches the
         * domain check and emits {@link UserAddService#MSG_INVALID_USER_TYPE};
         * for the fourth value the production code emits
         * {@link UserAddService#MSG_USER_TYPE_EMPTY} via isBlank(). Both
         * paths agree on {@code isSuccess() == false} and the
         * never()-save invariant, so this test asserts those two
         * load-bearing properties rather than the specific message text.
         */
        @ParameterizedTest(name = "[{index}] invalid user type ''{0}''")
        @ValueSource(strings = {"X", "B", "1", " "})
        @DisplayName("addUser rejects user type outside U/A domain")
        void addUser_invalidUserType_rejected(String invalidType) {
            // Arrange — note the deliberate absence of findById/save stubs.
            // The production code MUST short-circuit BEFORE reaching the
            // duplicate-key pre-check (which would call findById) or the
            // save call. Mockito's STRICT_STUBS mode would surface an
            // UnnecessaryStubbingException if we stubbed findById and the
            // production code didn't call it; the absence of the stub
            // confirms the production code's empty-check + domain-check
            // short-circuit by negative evidence.
            UserAddRequest request = buildValidRequest();
            request.setUserType(invalidType);

            // Act
            UserAddResult result = service.addUser(request);

            // Assert — load-bearing invariants for both code paths
            // (MSG_INVALID_USER_TYPE for "X"/"B"/"1", MSG_USER_TYPE_EMPTY
            // for " "): outcome is failure and save is never invoked.
            assertThat(result.isSuccess())
                    .as("Invalid user type '%s' must reject", invalidType)
                    .isFalse();
            verify(userSecurityRepository, never()).save(any());
        }
    }

    // =========================================================================
    // SECURITY CHECKS — proves BCrypt salt produces different hashes
    //
    // BCrypt randomises the salt on each encode() call, so two encodes of
    // the same plaintext produce two distinct 60-character hash strings.
    // Both hashes still verify against the same plaintext via matches().
    // This is the property that prevents rainbow-table attacks against
    // the stored hashes.
    // =========================================================================

    /**
     * Security tests that prove the production
     * {@link UserAddService#addUser(UserAddRequest)} method uses REAL BCrypt
     * encoding (which randomises the salt on each call) rather than a
     * deterministic transform.
     *
     * <p>This test is the practical enforcement of AAP §0.10.5 ("No plaintext
     * credentials in any configuration file") combined with AAP §0.10.1
     * ("Tests MUST NOT reimplement any business or calculation logic inside
     * test bodies"). It cannot be replaced by mocks: a mocked
     * {@link PasswordEncoder} could trivially be configured to return a
     * fixed hash for both calls, defeating the assurance that the production
     * code calls real BCrypt encoding.
     */
    @Nested
    @DisplayName("Security checks — BCrypt hashing")
    class SecurityChecks {

        /**
         * Security: adding the same plaintext password for two different
         * users produces TWO DIFFERENT BCrypt hashes (because BCrypt
         * randomises the salt on each {@code encode()} call). Both hashes
         * still verify against the original plaintext via {@code matches}.
         *
         * <p>This test would fail if the production code:
         * <ul>
         *   <li>Stored plaintext (both saved entities would carry the same
         *       plaintext string).</li>
         *   <li>Used a deterministic hash (e.g., SHA-256 without a salt;
         *       both saved entities would carry the same hash).</li>
         *   <li>Re-used a single BCrypt-encoded result across saves (both
         *       saved entities would carry the same hash).</li>
         * </ul>
         *
         * <p>The test does NOT use a second TestFixtures user identifier
         * because the inline literal {@code "USRTST02"} is sufficient and
         * keeps the test self-contained; the agent-prompt template
         * specifies the inline literal verbatim.
         */
        @Test
        @DisplayName("addUser produces different BCrypt hashes for same password (salt)")
        void addUser_samePasswordTwice_producesDifferentHashes() {
            // Arrange — stub findById to return Optional.empty() for ANY
            // argument (both REGULAR_USER_ID and "USRTST02") so the
            // duplicate-key pre-check passes for both saves. Stub save to
            // return its argument so the production code's chain (if any)
            // works.
            when(userSecurityRepository.findById(any())).thenReturn(Optional.empty());
            when(userSecurityRepository.save(any(SecurityUser.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act — add two different users with the SAME plaintext
            // password. The plaintext is held by TEST_PASSWORD_PLAINTEXT;
            // both requests carry that identical value.
            UserAddRequest req1 = buildValidRequest();
            service.addUser(req1);

            UserAddRequest req2 = buildValidRequest();
            req2.setUserId("USRTST02");
            service.addUser(req2);

            // Assert — capture both persisted entities and assert their
            // password fields differ. Capturing twice via times(2) yields
            // a List of length 2; AssertJ's isNotEqualTo asserts the
            // two strings differ.
            ArgumentCaptor<SecurityUser> captor =
                    ArgumentCaptor.forClass(SecurityUser.class);
            verify(userSecurityRepository, times(2)).save(captor.capture());
            List<SecurityUser> persisted = captor.getAllValues();

            // Defence in depth (1) — the two BCrypt hashes are distinct.
            // A production code that stored plaintext or used a
            // deterministic transform would fail here.
            assertThat(persisted.get(0).getPassword())
                    .as("BCrypt salt produces different hash even for identical plaintext")
                    .isNotEqualTo(persisted.get(1).getPassword());

            // Defence in depth (2) — both hashes still verify against the
            // same plaintext via real BCrypt matches(). A production code
            // that stored garbled or non-BCrypt strings would fail one or
            // both of these assertions.
            assertThat(passwordEncoder.matches(
                    TestFixtures.Users.TEST_PASSWORD_PLAINTEXT,
                    persisted.get(0).getPassword()))
                    .as("First hash verifies against the plaintext")
                    .isTrue();
            assertThat(passwordEncoder.matches(
                    TestFixtures.Users.TEST_PASSWORD_PLAINTEXT,
                    persisted.get(1).getPassword()))
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
     * duplicate-detection test to simulate an existing persisted user
     * record. Populates every field with deterministic values:
     * <ul>
     *   <li>{@code userId} — {@link TestFixtures.Users#REGULAR_USER_ID}
     *       ({@code "USRTST01"}), the canonical regular-user fixture
     *       identifier.</li>
     *   <li>{@code firstName} / {@code lastName} — synthetic
     *       {@code "TEST USER"} placeholders that match no real person.</li>
     *   <li>{@code password} —
     *       {@link TestFixtures.Users#TEST_PASSWORD_BCRYPT_HASH}, the
     *       pre-computed BCrypt hash of {@code "TESTPASS"} per AAP §0.10.5
     *       ("No plaintext credentials in tests"). The hash satisfies the
     *       BCrypt-format invariant a real existing user would carry.</li>
     *   <li>{@code userType} — {@code "U"} for regular user (the canonical
     *       COBOL {@code CDEMO-USRTYP-USER} 88-level value).</li>
     *   <li>{@code version} — {@code 1L} so the simulated existing user
     *       carries a non-{@code null} JPA {@code @Version} field.</li>
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

    /**
     * Builds the standard fixture {@link UserAddRequest} used as the
     * starting point for both happy-path and reject-path tests. Each
     * reject-path test calls this helper and then mutates exactly ONE field
     * to drive the specific reject scenario (for example,
     * {@code request.setFirstName("")} drives the first-name-empty reject;
     * {@code request.setUserType("X")} drives the invalid-user-type reject).
     *
     * <p>Field values:
     * <ul>
     *   <li>{@code userId} — {@link TestFixtures.Users#REGULAR_USER_ID}
     *       ({@code "USRTST01"}).</li>
     *   <li>{@code firstName} — {@code "TEST"} (synthetic).</li>
     *   <li>{@code lastName} — {@code "USER"} (synthetic).</li>
     *   <li>{@code password} —
     *       {@link TestFixtures.Users#TEST_PASSWORD_PLAINTEXT}
     *       ({@code "TESTPASS"}) — the 8-character fixture plaintext per
     *       COBOL {@code SEC-USR-PWD PIC X(08)} field width. The production
     *       code BCrypt-encodes this before persisting.</li>
     *   <li>{@code userType} — {@code "U"} (regular user, COBOL
     *       {@code CDEMO-USRTYP-USER}).</li>
     * </ul>
     *
     * @return a freshly-constructed fixture {@link UserAddRequest} ready to
     *         be passed to {@code service.addUser(...)} (with optional
     *         field overrides for reject-path scenarios)
     */
    private static UserAddRequest buildValidRequest() {
        UserAddRequest req = new UserAddRequest();
        req.setUserId(TestFixtures.Users.REGULAR_USER_ID);
        req.setFirstName("TEST");
        req.setLastName("USER");
        req.setPassword(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
        req.setUserType("U");
        return req;
    }
    // ============================================================
    // Nested test class — Authorization Contract
    // ============================================================

    /**
     * Documents and asserts the authorization-contract layer for
     * {@link UserAddService} — the service that replaces COBOL program
     * {@code COUSR01C} (which creates new users (admin-only operation in COBOL)).
     *
     * <h2>COBOL Authorization Model</h2>
     *
     * <p>In the original CICS/COBOL implementation, authorization was
     * gated by the CICS BMS sign-on flow: {@code COSGN00C} validated
     * the user's credentials and only after success could the user
     * navigate via the main menu (or admin menu for admin-only flows)
     * to this program's screen. The COBOL program itself performed no
     * caller-authorization check — it trusted the upstream CICS session.
     *
     * <h2>Java Migration — Layer of Responsibility</h2>
     *
     * <p>Per AAP §0.10.2 (Minimal Change Clause), the Java migration
     * preserves this contract. {@link UserAddService} does NOT perform a
     * service-level caller-authorization check; instead:
     * <ul>
     *   <li>The REST controller (e.g., the Spring MVC controller
     *       that fronts this service) MUST enforce Spring Security
     *       {@code @PreAuthorize} or {@code @PostAuthorize}
     *       annotations at the HTTP boundary (the modern equivalent
     *       of the CICS BMS sign-on gate).</li>
     *   <li>The service layer trusts that the caller has passed the
     *       upstream authentication check; this matches the COBOL
     *       contract precisely.</li>
     * </ul>
     *
     * <p>These tests assert that contract is preserved structurally.
     *
     * @see com.aws.carddemo.service.UserListService for the contrasting
     *      pattern where the COBOL program does perform an admin-only
     *      check and the Java migration mirrors it via {@code callerUserType}
     */
    @Nested
    @DisplayName("Authorization contract — controller-layer responsibility (AAP §0.10.2)")
    class AuthorizationContract {

        /**
         * Verify {@link UserAddService} method signatures carry NO
         * {@code callerUserType}-style parameter — proving the
         * authorization is the controller's responsibility per the
         * COBOL COUSR01C trust-upstream contract.
         */
        @Test
        @DisplayName("methodSignatures_carryNoCallerIdentity_perCobolContract")
        void methodSignatures_carryNoCallerIdentity_perCobolContract() {
            java.lang.reflect.Method[] methods = UserAddService.class.getDeclaredMethods();
            boolean hasCallerUserTypeParam = false;
            for (java.lang.reflect.Method m : methods) {
                if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                    continue;
                }
                for (java.lang.reflect.Parameter p : m.getParameters()) {
                    if (p.getName().toLowerCase().contains("callerusertype")
                            || p.getName().toLowerCase().contains("calleruser")) {
                        hasCallerUserTypeParam = true;
                    }
                }
            }
            assertThat(hasCallerUserTypeParam)
                    .as("UserAddService must NOT accept callerUserType — "
                            + "authorization is the controller's responsibility "
                            + "per the COBOL COUSR01C contract")
                    .isFalse();
        }

        /**
         * Verify the request DTO {@link UserAddRequest} carries no
         * {@code callerUserType} field — the structural assertion
         * of the layer-of-responsibility model.
         *
         * <p>Contrast with {@code AdminMenuRequest}, {@code UserListRequest},
         * {@code MainMenuRequest} which DO carry {@code callerUserType}
         * — those COBOL programs (COADM01C, COUSR00C, COMEN01C)
         * performed admin checks; this one (COUSR01C) did not.
         */
        @Test
        @DisplayName("requestDto_doesNotCarryCallerUserType_perCobolContract")
        void requestDto_doesNotCarryCallerUserType_perCobolContract() {
            java.lang.reflect.Field[] fields = UserAddRequest.class.getDeclaredFields();
            for (java.lang.reflect.Field f : fields) {
                assertThat(f.getName().toLowerCase())
                        .as("Field %s on UserAddRequest must not be a caller-identity field",
                                f.getName())
                        .doesNotContain("calleruser");
            }
        }
    }
}
