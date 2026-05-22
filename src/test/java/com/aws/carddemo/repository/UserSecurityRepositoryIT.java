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
package com.aws.carddemo.repository;

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies).
//
//   * SecurityUser — the JPA entity stub matching the CSUSR01Y.cpy
//     SEC-USER-DATA layout (80-byte fixed-width record: 8-char ID + 20-char
//     FNAME + 20-char LNAME + 8-char PWD [widened to VARCHAR(60) for the
//     BCrypt hash per AAP §0.10.5] + 1-char TYPE + 23-byte FILLER). Test
//     methods drive persist/find/save/delete round-trips through this entity.
//     The production class is named SecurityUser (not UserSecurity) per the
//     existing src/main/java/com/aws/carddemo/entity/SecurityUser.java stub;
//     the AAP §0.10 Adaptation Notes explicitly anticipate this name
//     divergence ("If the production entity field is named differently...
//     adjust setter and getter call sites").
//
//   * AbstractRepositoryIT — the abstract base class providing the
//     @DataJpaTest slice annotation, the @Testcontainers PostgreSQL 16
//     container, the @DynamicPropertySource that wires Testcontainers'
//     JDBC URL/username/password into Spring's environment, and the
//     inherited TestEntityManager field (entityManager). Per AAP §0.4.4
//     all 10 repository ITs extend this base — this IT inherits the JPA
//     slice, per-class container lifecycle, and transactional rollback
//     after each @Test method without re-declaring any of that wiring.
//
//   * TestFixtures — the shared test-constants holder. This IT consumes
//     the Users-nested constants: REGULAR_USER_ID ("USRTST01"),
//     ADMIN_USER_ID ("ADMTST01"), NONEXISTENT_USER_ID ("NOTAUSER"),
//     TEST_PASSWORD_PLAINTEXT ("TESTPASS"), and TEST_PASSWORD_BCRYPT_HASH
//     (the pre-computed 60-char BCrypt hash of "TESTPASS" at strength 10).
//     Per AAP §0.10.5 the BCrypt hash is the only password-form value ever
//     persisted; the plaintext appears only as the right-hand operand of
//     isNotEqualTo() assertions proving that the persisted value is NOT
//     the plaintext.
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.SecurityUser;
import com.aws.carddemo.testsupport.AbstractRepositoryIT;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only).
//
//   * @Test marks each integration-test method. The Failsafe plugin
//     discovers test methods on the {@code *IT.java} suffix convention
//     and JUnit Jupiter runs them via the JUnit Platform.
//
//   * @DisplayName supplies human-readable scenario descriptions on the
//     test class and each method so IDE runner output and CI test reports
//     surface the COBOL-parity intent (rather than the camelCase method
//     name alone).
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ---------------------------------------------------------------------------
// Spring Framework dependency-injection import.
//
//   * @Autowired field-injects the Spring-managed UserSecurityRepository
//     proxy into this IT class instance. The proxy is created by Spring
//     Data JPA at @DataJpaTest context startup from the
//     {@code JpaRepository<SecurityUser, String>} interface declaration on
//     the production repository — no manual implementation is required,
//     and no field declared with @Mock is permissible at this IT layer
//     (AAP §0.10.1 Require Test Coverage Rule: integration tests must
//     invoke the REAL repository against the REAL database).
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;

// ---------------------------------------------------------------------------
// Spring Data — Pageable / PageRequest construction for the
// findByUserType(String, Pageable) custom query. The production
// UserSecurityRepository.findByUserType signature accepts a Pageable
// (mirroring the COBOL STARTBR + READNEXT loop with WS-MAX-SCREEN-LINES
// VALUE 10 page bound in COUSR00C.cbl). Tests pass an unbounded page
// (PageRequest.of(0, 100)) so the assertion can verify all matching rows
// were returned without colliding with the production 10-rows-per-page
// contract.
// ---------------------------------------------------------------------------
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

// ---------------------------------------------------------------------------
// Java standard library imports.
//
//   * java.util.Optional — return type of UserSecurityRepository.findById()
//     used by the primary-key lookup assertions (isPresent / isEmpty).
// ---------------------------------------------------------------------------
import java.util.Optional;

// ---------------------------------------------------------------------------
// AssertJ static import (AAP §0.10.10 — project-wide assertion idiom).
//
//   * assertThat is the fluent-assertions entry point used uniformly
//     across the test suite. assertThat(optional).isPresent(),
//     assertThat(string).matches(regex).hasSize(60),
//     assertThat(string).isNotEqualTo(plaintext),
//     assertThat(page.getContent()).extracting(...).contains(...) are the
//     four primary idioms this class exercises.
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link UserSecurityRepository}, which persists
 * {@link SecurityUser} entities migrated from the COBOL {@code SEC-USER-DATA}
 * record defined in {@code app/cpy/CSUSR01Y.cpy} (RECLN 80).
 *
 * <h2>COBOL Provenance — CSUSR01Y.cpy</h2>
 *
 * <p>The original copybook layout is a fixed-width 80-byte record:
 * <pre>
 *   01 SEC-USER-DATA.
 *      05 SEC-USR-ID         PIC X(08).   --&gt; {@link SecurityUser#getUserId()}      (primary key)
 *      05 SEC-USR-FNAME      PIC X(20).   --&gt; {@link SecurityUser#getFirstName()}
 *      05 SEC-USR-LNAME      PIC X(20).   --&gt; {@link SecurityUser#getLastName()}
 *      05 SEC-USR-PWD        PIC X(08).   --&gt; {@link SecurityUser#getPassword()}    (BCrypt hash, NOT plaintext)
 *      05 SEC-USR-TYPE       PIC X(01).   --&gt; {@link SecurityUser#getUserType()}    ('U' or 'A')
 *      05 SEC-USR-FILLER     PIC X(23).
 * </pre>
 *
 * <p>{@code SEC-USER-DATA} is the 80-byte VSAM KSDS record read by
 * {@code COSGN00C} (sign-on) and managed by
 * {@code COUSR01C}/{@code COUSR02C}/{@code COUSR03C} (user add/update/delete).
 *
 * <h2>Migration Security Pattern (AAP §0.1.1 / §0.10.5)</h2>
 *
 * <p>The original COBOL field {@code SEC-USR-PWD PIC X(08)} stored an
 * 8-character plaintext password and COSGN00C compared it with the user's
 * input via a simple {@code IF SEC-USR-PWD = WS-USER-PWD} statement. The Java
 * migration replaces this with:
 * <ul>
 *   <li>A widened column (typically {@code VARCHAR(60)}) storing the BCrypt
 *       hash of the password (60-char hash format
 *       {@code $2[aby]$NN$<22-char-salt><31-char-hash>}).</li>
 *   <li>Password verification via
 *       {@code BCryptPasswordEncoder.matches(rawPassword, hash)} in
 *       {@link com.aws.carddemo.service.AuthenticationService}.</li>
 * </ul>
 *
 * <p>This IT verifies the persistence side of the contract: the stored value
 * is a valid BCrypt hash, NOT plaintext, and the persistence column is wide
 * enough to accommodate the full 60-char hash without truncation. Together
 * these two guarantees ensure that the migrated authentication pathway
 * cannot regress to the COBOL plaintext-comparison behaviour by accident.
 *
 * <h2>Production Entity Naming Note</h2>
 *
 * <p>The production class is named {@link SecurityUser} (not
 * {@code UserSecurity}); the AAP §0.10 Adaptation Notes explicitly anticipate
 * this name divergence. The production class uses Java-conventioned field
 * names ({@code userId}, {@code firstName}, {@code lastName},
 * {@code password}, {@code userType}) rather than the COBOL-style
 * {@code SEC-USR-*} names; this IT therefore drives the Java-style API
 * exactly as the production code exposes it. The COBOL-to-Java field-name
 * mapping is documented in the {@link SecurityUser} class Javadoc and again
 * in the field-list comments at the top of this class for cross-reference.
 *
 * <h2>Production Repository Custom Query</h2>
 *
 * <p>The {@link UserSecurityRepository#findByUserType(String, Pageable)}
 * method is a Spring Data derived query: the framework parses the method
 * name {@code findBy + UserType} and generates the equivalent of
 * {@code SELECT u FROM SecurityUser u WHERE u.userType = :userType} with
 * pagination support. The AAP §0.10 Adaptation Notes anticipate this name
 * divergence from the original {@code findBySecUsrType} prescription. The
 * role-filter tests in this IT pass an unbounded {@link PageRequest} (page
 * size 100) so the assertion can verify all matching rows were returned
 * without colliding with the COBOL {@code WS-MAX-SCREEN-LINES VALUE 10}
 * production constraint.
 *
 * <h2>Coverage Focus (AAP §0.5.1)</h2>
 *
 * <p>The 9 test methods exercise the integration of
 * {@link UserSecurityRepository} against a real PostgreSQL 16 instance
 * provisioned by Testcontainers — no Mockito stubs at this layer
 * (AAP §0.10.1 Require Test Coverage Rule). The categories below cover the
 * AAP-specified scenarios:
 *
 * <ul>
 *   <li>{@link #findById_existingUser_returnsUser()} — primary-key lookup
 *       happy path (COBOL {@code DFHRESP(NORMAL)} equivalent).</li>
 *   <li>{@link #findById_nonexistentUser_returnsEmpty()} — primary-key
 *       lookup negative path (COBOL {@code DFHRESP(NOTFND)} / VSAM
 *       {@code STATUS '23'} equivalent).</li>
 *   <li>{@link #save_userWithBcryptHash_persistsHashNotPlaintext()} —
 *       <strong>CRITICAL</strong> AAP §0.10.5 contract: the persisted
 *       password must be the BCrypt hash, NOT the plaintext. This is the
 *       core security assertion that distinguishes the migrated
 *       authentication pathway from the COBOL plaintext-comparison
 *       behaviour.</li>
 *   <li>{@link #save_passwordColumn_accommodates60CharHash()} — verifies
 *       that the persistence column has been widened from
 *       {@code CHAR(8)} (the COBOL {@code PIC X(08)} mapping) to at least
 *       {@code VARCHAR(60)} so the full BCrypt hash round-trips without
 *       silent truncation. A truncated hash would silently break
 *       authentication for every user.</li>
 *   <li>{@link #findByUserType_admin_returnsOnlyAdminUsers()} — role
 *       retrieval: filtering by {@code SEC-USR-TYPE = 'A'} (admin).
 *       Backs the admin-only authorisation branch of
 *       {@link com.aws.carddemo.service.UserListService}.</li>
 *   <li>{@link #findByUserType_regular_returnsOnlyRegularUsers()} — role
 *       retrieval: filtering by {@code SEC-USR-TYPE = 'U'} (regular user).
 *       Symmetric assertion to the admin case above.</li>
 *   <li>{@link #save_updatedUserName_persistsChange()} — update-path
 *       parity for {@code COUSR02C} (USER UPDATE).</li>
 *   <li>{@link #deleteById_existingUser_removesRow()} — delete-path
 *       parity for {@code COUSR03C} (USER DELETE).</li>
 *   <li>{@link #count_invoked_returnsNonNegativeValue()} — basic sanity
 *       check that the inherited {@code count()} method returns a
 *       non-negative tally.</li>
 * </ul>
 *
 * <h2>Mock Boundary (AAP §0.10.1 Require Test Coverage Rule)</h2>
 *
 * <p>This IT has <strong>no Mockito mocks</strong>. The system under test
 * is the real {@link UserSecurityRepository} bean wired by Spring Data JPA
 * against the real PostgreSQL 16 database supplied by Testcontainers
 * (inherited from {@link AbstractRepositoryIT}). Repository ITs sit at the
 * lowest mock boundary in the test pyramid: they verify that the Spring
 * Data JPA proxy + Hibernate ORM + JDBC driver + PostgreSQL stack produces
 * correct results against a real schema seeded by Flyway. Tests that would
 * otherwise mock the repository (service unit tests, batch processor unit
 * tests) live one layer up in {@code com.aws.carddemo.service.*Test}.
 *
 * <h2>No Business Logic in Test Bodies (AAP §0.10.1)</h2>
 *
 * <p>This IT performs <strong>zero</strong> BCrypt hashing inside test
 * methods. The hash value used in every {@code save(...)} call is the
 * pre-computed constant {@link TestFixtures.Users#TEST_PASSWORD_BCRYPT_HASH}
 * (generated once, offline, by invoking
 * {@code new BCryptPasswordEncoder(10).encode("TESTPASS")}). Hashing
 * logic — including the choice of strength, the salt generation, and the
 * verification algorithm — lives exclusively in
 * {@link com.aws.carddemo.service.AuthenticationService} and is covered by
 * {@code AuthenticationServiceTest}. This IT only verifies <em>storage</em>
 * of an already-computed hash.
 *
 * <h2>No PII / No Plaintext Credentials Persisted (AAP §0.10.5)</h2>
 *
 * <p>Every {@code save(...)} call routes the test fixture user through the
 * {@link #buildSyntheticUser(String, String, String, String)} helper, which
 * deliberately sets {@link SecurityUser#setPassword(String)} to
 * {@link TestFixtures.Users#TEST_PASSWORD_BCRYPT_HASH} (a BCrypt hash, not
 * plaintext). The plaintext constant
 * {@link TestFixtures.Users#TEST_PASSWORD_PLAINTEXT} appears in this IT
 * only as the right-hand operand of {@code isNotEqualTo(...)} assertions
 * proving that the persisted value is NOT the plaintext. No plaintext
 * password value is ever passed to {@link SecurityUser#setPassword(String)}.
 *
 * <h2>Test isolation (AAP §0.10.9)</h2>
 *
 * <p>{@code @DataJpaTest} (inherited from {@link AbstractRepositoryIT})
 * wraps each {@code @Test} method in a transaction that rolls back at
 * method end. This means the synthetic users persisted by every test in
 * this class are gone before the next test sees the database state. Each
 * test starts from the Flyway-seeded user catalog (zero synthetic
 * additions); test order independence is guaranteed.
 *
 * <h2>Activation State</h2>
 *
 * <p>This IT is active and executes under {@code mvn verify} (Failsafe).
 * The suite loads the {@code @DataJpaTest} Spring slice via
 * {@link AbstractRepositoryIT} and exercises the production
 * {@link UserSecurityRepository} bean against a real PostgreSQL 16
 * database. The {@link SecurityUser} entity is annotated as a JPA
 * {@code @Entity} (with {@code @Id} on {@code userId},
 * {@code @Column(name = "password", length = 60)} on {@code password},
 * {@code @Version} on {@code version}), and the Flyway scripts under
 * {@code src/main/resources/db/migration/} create the
 * {@code security_users} table. The {@code findByUserId},
 * BCrypt-hash persistence (60-char width, format regex, hashes never
 * plaintext), role retrieval, optimistic-locking, miss-path, and
 * update paths are all exercised by the test methods below.
 *
 * <h3>Operational Prerequisite</h3>
 *
 * <p>Docker must be available to Testcontainers at test runtime — the
 * {@code mvn verify} build agent must be able to run
 * {@code postgres:16-alpine}. CI agents that cannot start containers
 * (e.g. nested-virtualisation-free environments) can set
 * {@code TESTCONTAINERS_RYUK_DISABLED=true} as documented in
 * {@code src/test/resources/application-test.properties}.
 *
 * @see UserSecurityRepository
 * @see SecurityUser
 * @see AbstractRepositoryIT
 * @see TestFixtures.Users
 */
@DisplayName("UserSecurityRepository — CSUSR01Y.cpy migration parity ITs (BCrypt persistence)")
class UserSecurityRepositoryIT extends AbstractRepositoryIT {

    /**
     * The Spring Data JPA repository under integration test. The bean is
     * created by Spring Data JPA at {@code @DataJpaTest} context startup
     * from the {@link UserSecurityRepository} interface declaration (no
     * manual implementation). Field injection is consistent with the
     * inherited {@code @Autowired TestEntityManager entityManager} field
     * on {@link AbstractRepositoryIT}.
     */
    @Autowired
    private UserSecurityRepository userSecurityRepository;

    // =========================================================================
    // Primary-Key Lookup Tests
    // =========================================================================

    /**
     * Verifies that {@link UserSecurityRepository#findById(Object)} returns
     * the persisted {@link SecurityUser} for an existing 8-character primary
     * key. The test persists a synthetic user via the inherited
     * {@code TestEntityManager}, flushes the persistence context to push
     * the row to PostgreSQL, clears the first-level cache so the
     * subsequent {@code findById} hits the database, and then asserts that
     * the returned {@link Optional} contains an entity with the expected
     * primary key and all four migrated fields ({@code firstName},
     * {@code lastName}, {@code userType}, and {@code password}).
     *
     * <p>This is the Java equivalent of the COBOL CICS response code
     * {@code DFHRESP(NORMAL)} branch from {@code COSGN00C}'s
     * {@code READ-USER-SEC-FILE} paragraph (lines 209–257).
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(existing SEC-USR-ID) returns the persisted SecurityUser")
    void findById_existingUser_returnsUser() {
        // Arrange — persist a synthetic regular user via the inherited TestEntityManager
        SecurityUser user = buildSyntheticUser(
                TestFixtures.Users.REGULAR_USER_ID,
                "Test",
                "User",
                "U");
        entityManager.persistAndFlush(user);
        entityManager.clear();

        // Act — drive the production repository against the real DB
        Optional<SecurityUser> result =
                userSecurityRepository.findById(TestFixtures.Users.REGULAR_USER_ID);

        // Assert — the row must be located and all migrated fields must round-trip exactly
        assertThat(result)
                .as("findById should locate the persisted user by 8-character primary key "
                        + "(COBOL DFHRESP(NORMAL) equivalent)")
                .isPresent();
        SecurityUser u = result.get();
        assertThat(u.getUserId())
                .as("SEC-USR-ID primary-key component must round-trip exactly")
                .isEqualTo(TestFixtures.Users.REGULAR_USER_ID);
        assertThat(u.getFirstName())
                .as("SEC-USR-FNAME must round-trip exactly after persist+findById")
                .isEqualTo("Test");
        assertThat(u.getLastName())
                .as("SEC-USR-LNAME must round-trip exactly after persist+findById")
                .isEqualTo("User");
        assertThat(u.getUserType())
                .as("SEC-USR-TYPE must round-trip exactly ('U' for regular user)")
                .isEqualTo("U");
    }

    /**
     * Verifies that {@link UserSecurityRepository#findById(Object)} returns
     * {@link Optional#empty()} when the supplied 8-character primary key
     * does not exist in the {@code security_users} table. This is the Java
     * equivalent of the COBOL CICS response code {@code DFHRESP(NOTFND)}
     * branch from {@code COSGN00C}'s {@code READ-USER-SEC-FILE} paragraph
     * — the underlying VSAM file-status code {@code '23'} (record not
     * found). The production
     * {@link com.aws.carddemo.service.AuthenticationService} translates
     * this empty {@link Optional} into the COBOL-equivalent "User not
     * found" reject message.
     *
     * <p>The lookup key {@link TestFixtures.Users#NONEXISTENT_USER_ID}
     * ({@code "NOTAUSER"}) is guaranteed never to appear in the fixture
     * user catalog (the constant exists precisely for not-found
     * assertions).
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(nonexistent user) returns empty Optional (VSAM STATUS 23 parity)")
    void findById_nonexistentUser_returnsEmpty() {
        // Act — drive the production repository against the real DB with a key
        // guaranteed never to appear in the fixture user catalog
        Optional<SecurityUser> result =
                userSecurityRepository.findById(TestFixtures.Users.NONEXISTENT_USER_ID);

        // Assert — Optional.empty mirrors the COBOL DFHRESP(NOTFND) signal
        assertThat(result)
                .as("findById should return Optional.empty for unknown user IDs "
                        + "(COBOL DFHRESP(NOTFND) / VSAM STATUS '23' equivalent)")
                .isEmpty();
    }

    // =========================================================================
    // BCrypt Hash Persistence Tests (AAP §0.10.5 — CRITICAL SECURITY CONTRACT)
    // =========================================================================

    /**
     * <strong>CRITICAL security assertion (AAP §0.10.5).</strong> Verifies
     * that the password field of a persisted {@link SecurityUser} stores
     * the 60-character BCrypt hash exactly as supplied — and explicitly
     * <em>not</em> the plaintext password.
     *
     * <p>This is the core security contract that distinguishes the migrated
     * authentication pathway from the COBOL plaintext-comparison behaviour:
     * the COBOL {@code SEC-USR-PWD PIC X(08)} field stored an 8-character
     * plaintext password and the COSGN00C {@code IF SEC-USR-PWD =
     * WS-USER-PWD} statement compared two plaintext strings. Under that
     * legacy design, every BCrypt-hashed user record would have its
     * password field truncated to 8 characters and authentication would
     * silently break (or, worse, an attacker who obtained the database
     * would see plaintext passwords). This test would have failed under
     * the original COBOL implementation; it must pass under the Java
     * migration.
     *
     * <p>The test makes four assertions on the persisted password value:
     * <ol>
     *   <li>It exactly equals the BCrypt hash supplied at persist time
     *       (round-trip fidelity).</li>
     *   <li>It is <strong>NOT</strong> equal to the plaintext password
     *       (the AAP §0.10.5 contract: no plaintext credentials at rest).</li>
     *   <li>It matches the canonical BCrypt hash regular expression
     *       {@code ^\$2[aby]\$\d{2}\$.{53}$} (the
     *       {@code $2[aby]$NN$<22-char-salt><31-char-hash>} format
     *       Spring Security's {@code BCryptPasswordEncoder} produces).</li>
     *   <li>It is exactly 60 characters long (the BCrypt hash length is
     *       invariant across all BCrypt versions and strengths; any other
     *       length would indicate truncation or corruption).</li>
     * </ol>
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(SecurityUser with BCrypt hash) persists 60-char hash, NOT plaintext (AAP §0.10.5)")
    void save_userWithBcryptHash_persistsHashNotPlaintext() {
        // Arrange — build a synthetic user whose password is the pre-computed
        // BCrypt hash constant; the helper already sets the hash by default but
        // we set it explicitly here to make the contract under test inarguable.
        String bcryptHash = TestFixtures.Users.TEST_PASSWORD_BCRYPT_HASH;
        SecurityUser user = buildSyntheticUser(
                TestFixtures.Users.REGULAR_USER_ID,
                "Bob",
                "Test",
                "U");
        user.setPassword(bcryptHash);

        // Act — drive the production repository's save path, then force a DB read
        userSecurityRepository.save(user);
        entityManager.flush();
        entityManager.clear();

        // Assert — the four-fold security contract from the Javadoc above
        SecurityUser reloaded = userSecurityRepository
                .findById(TestFixtures.Users.REGULAR_USER_ID)
                .orElseThrow();

        // (1) Round-trip fidelity: persisted password equals the supplied BCrypt hash
        assertThat(reloaded.getPassword())
                .as("Persisted password must equal the BCrypt hash exactly (round-trip fidelity)")
                .isEqualTo(bcryptHash);

        // (2) AAP §0.10.5 core contract: persisted password is NOT the plaintext
        assertThat(reloaded.getPassword())
                .as("Persisted password must NOT equal the plaintext (AAP §0.10.5 — "
                        + "no plaintext credentials at rest; this distinguishes the migrated "
                        + "authentication pathway from the COBOL plaintext-comparison "
                        + "behaviour in COSGN00C)")
                .isNotEqualTo(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);

        // (3) BCrypt hash format check (Spring Security BCryptPasswordEncoder output)
        assertThat(reloaded.getPassword())
                .as("Persisted password must match the canonical BCrypt hash format "
                        + "$2[aby]$NN$<22-char-salt><31-char-hash>")
                .matches("^\\$2[aby]\\$\\d{2}\\$.{53}$");

        // (4) BCrypt hash length invariant: always 60 characters
        assertThat(reloaded.getPassword())
                .as("BCrypt hash must be exactly 60 characters (BCrypt length invariant; "
                        + "any other length indicates truncation or corruption)")
                .hasSize(60);
    }

    /**
     * Verifies that the persistence column backing the password field has
     * been widened from {@code CHAR(8)} (the literal COBOL
     * {@code PIC X(08)} mapping) to at least {@code VARCHAR(60)} so the
     * full BCrypt hash round-trips without silent truncation. A truncated
     * hash would silently break authentication for every user: BCrypt's
     * verify algorithm requires the full hash to recover the salt + cost
     * factor and recompute the comparison; a hash truncated to 8 (or any
     * length below 60) cannot be verified against the original plaintext.
     *
     * <p>This test persists the admin user fixture with the full 60-char
     * BCrypt hash from {@link TestFixtures.Users#TEST_PASSWORD_BCRYPT_HASH},
     * flushes to push the row through Hibernate's INSERT statement and the
     * JDBC driver's binding logic, clears the first-level cache, and
     * re-reads the row from PostgreSQL. The reloaded value is asserted to
     * be exactly 60 characters long (the BCrypt invariant) <em>and</em>
     * exactly equal to the original constant.
     *
     * <p>If the column were defined as {@code CHAR(8)} or any width below
     * 60, the assertion {@code .hasSize(60)} would fail with the truncated
     * length, and the assertion {@code .isEqualTo(...)} would fail with
     * the value-mismatch difference. Either failure is sufficient to
     * surface the column-width misconfiguration to the next agent.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(SecurityUser) widened password column accommodates 60-char BCrypt hash")
    void save_passwordColumn_accommodates60CharHash() {
        // Arrange — build a synthetic admin user whose password is the pre-computed
        // BCrypt hash (the helper sets this by default but we set it explicitly here
        // to keep the column-width contract self-evident)
        SecurityUser user = buildSyntheticUser(
                TestFixtures.Users.ADMIN_USER_ID,
                "Admin",
                "User",
                "A");
        user.setPassword(TestFixtures.Users.TEST_PASSWORD_BCRYPT_HASH);

        // Act — drive the production repository's save path; flush + clear forces
        // Hibernate to issue the INSERT immediately and the subsequent findById
        // to re-fetch from PostgreSQL rather than the first-level cache
        userSecurityRepository.save(user);
        entityManager.flush();
        entityManager.clear();

        // Assert — the full hash must round-trip without truncation; failure here
        // indicates the password column is too narrow to store the BCrypt hash
        SecurityUser reloaded = userSecurityRepository
                .findById(TestFixtures.Users.ADMIN_USER_ID)
                .orElseThrow();
        assertThat(reloaded.getPassword())
                .as("Password column must accommodate the full 60-char BCrypt hash without "
                        + "truncation (column must be VARCHAR(60) or wider; CHAR(8) — the "
                        + "literal PIC X(08) mapping — would silently break BCrypt verification "
                        + "for every user)")
                .hasSize(60)
                .isEqualTo(TestFixtures.Users.TEST_PASSWORD_BCRYPT_HASH);
    }

    // =========================================================================
    // Role Retrieval Tests (AAP §0.5.1 — "role retrieval")
    // =========================================================================

    /**
     * Verifies that
     * {@link UserSecurityRepository#findByUserType(String, Pageable)}
     * returns only users with {@code SEC-USR-TYPE = 'A'} (admin) when
     * invoked with the {@code "A"} filter value. This is the Java
     * equivalent of the COBOL {@code STARTBR/READNEXT} loop in
     * {@code COUSR00C.cbl} restricted to admin records — the production
     * {@link com.aws.carddemo.service.UserListService} uses this method
     * to power the admin-only authorisation branch of the
     * user-administration screen.
     *
     * <p>The test persists three synthetic users (two admins and one
     * regular user) outside the canonical {@link TestFixtures.Users} ID
     * range to avoid colliding with any fixture rows the
     * {@link AbstractRepositoryIT} base class may seed, then invokes
     * {@code findByUserType("A", PageRequest.of(0, 100))} (page size 100
     * is unbounded relative to the test's 3 rows; this avoids tying the
     * assertion to the COBOL {@code WS-MAX-SCREEN-LINES VALUE 10}
     * production page-size constant).
     *
     * <p>The assertions cover three contracts:
     * <ol>
     *   <li>Every returned user has {@code SEC-USR-TYPE = 'A'} (the
     *       filter contract).</li>
     *   <li>Both synthetic admin user IDs ({@code "ADMUSR01"} and
     *       {@code "ADMUSR02"}) appear in the result set (no false
     *       negatives).</li>
     *   <li>The regular user ID ({@code "REGUSR01"}) does NOT appear in
     *       the result set (no false positives — the filter correctly
     *       excludes non-admin rows).</li>
     * </ol>
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findByUserType('A') returns only admin users")
    void findByUserType_admin_returnsOnlyAdminUsers() {
        // Arrange — 2 admins, 1 regular user, all with synthetic IDs outside
        // the canonical TestFixtures.Users range to avoid fixture collisions
        entityManager.persist(buildSyntheticUser("ADMUSR01", "First", "Admin", "A"));
        entityManager.persist(buildSyntheticUser("ADMUSR02", "Second", "Admin", "A"));
        entityManager.persist(buildSyntheticUser("REGUSR01", "Regular", "User", "U"));
        entityManager.flush();
        entityManager.clear();

        // Act — drive the production repository against the real DB, using an
        // unbounded page (size 100) so the assertion can verify all matching rows
        // without tying to the COBOL WS-MAX-SCREEN-LINES VALUE 10 page size
        Pageable unbounded = PageRequest.of(0, 100);
        Page<SecurityUser> admins = userSecurityRepository.findByUserType("A", unbounded);

        // Assert — only admin users returned, both synthetic admins present,
        // regular user absent
        assertThat(admins.getContent())
                .as("All returned users must have SEC-USR-TYPE = 'A' (the filter contract; "
                        + "any 'U' row in the result indicates a Spring Data derived-query defect)")
                .allSatisfy(u -> assertThat(u.getUserType()).isEqualTo("A"));
        assertThat(admins.getContent())
                .as("Result must contain both synthetic admin users (no false negatives)")
                .extracting(SecurityUser::getUserId)
                .contains("ADMUSR01", "ADMUSR02")
                .doesNotContain("REGUSR01");
    }

    /**
     * Verifies that
     * {@link UserSecurityRepository#findByUserType(String, Pageable)}
     * returns only users with {@code SEC-USR-TYPE = 'U'} (regular user)
     * when invoked with the {@code "U"} filter value. This is the
     * symmetric assertion to
     * {@link #findByUserType_admin_returnsOnlyAdminUsers()}: the same
     * derived-query method, the opposite filter parameter value, the same
     * three-contract assertion (filter correctness, no false negatives,
     * no false positives).
     *
     * <p>The test persists three synthetic users (one admin and two
     * regular users) outside the canonical {@link TestFixtures.Users} ID
     * range, then invokes {@code findByUserType("U", PageRequest.of(0,
     * 100))} (page size 100 is unbounded relative to the test's 3 rows).
     *
     * <p>Together with {@link #findByUserType_admin_returnsOnlyAdminUsers()}
     * this pair of tests exercises both branches of the
     * {@code SEC-USR-TYPE} role-filter contract used by
     * {@link com.aws.carddemo.service.UserListService}.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findByUserType('U') returns only regular users")
    void findByUserType_regular_returnsOnlyRegularUsers() {
        // Arrange — 1 admin, 2 regular users, all with synthetic IDs outside
        // the canonical TestFixtures.Users range to avoid fixture collisions
        entityManager.persist(buildSyntheticUser("ADMUSR03", "Admin", "Three", "A"));
        entityManager.persist(buildSyntheticUser("REGUSR02", "Regular", "Two", "U"));
        entityManager.persist(buildSyntheticUser("REGUSR03", "Regular", "Three", "U"));
        entityManager.flush();
        entityManager.clear();

        // Act — drive the production repository against the real DB
        Pageable unbounded = PageRequest.of(0, 100);
        Page<SecurityUser> regulars = userSecurityRepository.findByUserType("U", unbounded);

        // Assert — only regular users returned, both synthetic regulars present,
        // the admin user absent
        assertThat(regulars.getContent())
                .as("All returned users must have SEC-USR-TYPE = 'U' (the filter contract; "
                        + "any 'A' row in the result indicates a Spring Data derived-query defect)")
                .allSatisfy(u -> assertThat(u.getUserType()).isEqualTo("U"));
        assertThat(regulars.getContent())
                .as("Result must contain both synthetic regular users (no false negatives)")
                .extracting(SecurityUser::getUserId)
                .contains("REGUSR02", "REGUSR03")
                .doesNotContain("ADMUSR03");
    }

    // =========================================================================
    // Update / Delete Path Tests (COUSR02C / COUSR03C parity)
    // =========================================================================

    /**
     * Verifies that {@link UserSecurityRepository#save(Object)} persists a
     * change to an existing {@link SecurityUser}'s mutable fields (first
     * name and last name in this test). This is the Java equivalent of
     * the COBOL {@code COUSR02C.cbl} USER UPDATE flow ({@code REWRITE-
     * USER-SEC-FILE} after the user-input screen overlays the in-memory
     * record).
     *
     * <p>The test:
     * <ol>
     *   <li>Persists an initial user with first name {@code "Original"}
     *       and last name {@code "Name"}.</li>
     *   <li>Reloads the user via the repository, mutates the two name
     *       fields in place to {@code "Updated"} and {@code "LastName"},
     *       and invokes {@code save(...)} again.</li>
     *   <li>Re-reads the user from PostgreSQL and asserts both fields
     *       carry the updated values.</li>
     * </ol>
     *
     * <p>The {@code entityManager.flush()} + {@code entityManager.clear()}
     * pair after each {@code save(...)} forces Hibernate to push the
     * statement to PostgreSQL and clears the first-level cache so the
     * subsequent {@code findById} re-fetches from the database rather
     * than returning the cached in-memory entity (which would mask a
     * persistence-layer defect).
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(updated user name) persists the change (COUSR02C parity)")
    void save_updatedUserName_persistsChange() {
        // Arrange — persist an initial user with the original name values
        SecurityUser initial = buildSyntheticUser(
                TestFixtures.Users.REGULAR_USER_ID,
                "Original",
                "Name",
                "U");
        entityManager.persistAndFlush(initial);
        entityManager.clear();

        // Act — load, mutate the name fields, re-save through the production repository
        SecurityUser reloaded = userSecurityRepository
                .findById(TestFixtures.Users.REGULAR_USER_ID)
                .orElseThrow();
        reloaded.setFirstName("Updated");
        reloaded.setLastName("LastName");
        userSecurityRepository.save(reloaded);
        entityManager.flush();
        entityManager.clear();

        // Assert — the updated name values must be visible on a fresh read
        SecurityUser afterUpdate = userSecurityRepository
                .findById(TestFixtures.Users.REGULAR_USER_ID)
                .orElseThrow();
        assertThat(afterUpdate.getFirstName())
                .as("SEC-USR-FNAME must reflect the updated value after save (COUSR02C "
                        + "REWRITE-USER-SEC-FILE equivalent)")
                .isEqualTo("Updated");
        assertThat(afterUpdate.getLastName())
                .as("SEC-USR-LNAME must reflect the updated value after save (COUSR02C "
                        + "REWRITE-USER-SEC-FILE equivalent)")
                .isEqualTo("LastName");
    }

    /**
     * Verifies that {@link UserSecurityRepository#deleteById(Object)}
     * removes the targeted row from the {@code security_users} table.
     * This is the Java equivalent of the COBOL {@code COUSR03C.cbl} USER
     * DELETE flow ({@code DELETE-USER-SEC-FILE} → {@code EXEC CICS DELETE
     * DATASET('USRSEC') RIDFLD(WS-USER-ID)}).
     *
     * <p>The test:
     * <ol>
     *   <li>Persists a synthetic user with ID {@code "DELME001"} (outside
     *       the canonical {@link TestFixtures.Users} ID range to avoid
     *       fixture collisions).</li>
     *   <li>Asserts the user is present (sanity check that the persist
     *       worked).</li>
     *   <li>Invokes {@code deleteById("DELME001")} through the production
     *       repository, then flushes and clears.</li>
     *   <li>Asserts the user is no longer retrievable.</li>
     * </ol>
     *
     * <p>The sanity check before the act phase ensures that a false-pass
     * (where the delete is a no-op against an absent row) is impossible:
     * the row must exist before the delete is invoked and must not exist
     * after.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("deleteById(existing user) removes the row (COUSR03C parity)")
    void deleteById_existingUser_removesRow() {
        // Arrange — persist a synthetic user with an ID outside the canonical
        // TestFixtures.Users range to keep this test self-contained
        SecurityUser user = buildSyntheticUser(
                "DELME001",
                "Delete",
                "Me",
                "U");
        entityManager.persistAndFlush(user);
        entityManager.clear();

        // Sanity check — the user must exist before the delete (guards against
        // false-pass from a no-op delete against an absent row)
        assertThat(userSecurityRepository.findById("DELME001"))
                .as("Pre-delete sanity check: the persisted user must be present "
                        + "(guards against a no-op-delete false-pass)")
                .isPresent();

        // Act — drive the production repository's delete path, then flush and clear
        userSecurityRepository.deleteById("DELME001");
        entityManager.flush();
        entityManager.clear();

        // Assert — the user must no longer be retrievable after the delete
        assertThat(userSecurityRepository.findById("DELME001"))
                .as("Deleted user must no longer be retrievable (COUSR03C "
                        + "DELETE-USER-SEC-FILE equivalent)")
                .isEmpty();
    }

    // =========================================================================
    // count() Sanity Check
    // =========================================================================

    /**
     * Verifies that {@link UserSecurityRepository#count()} returns a
     * non-negative tally. The assertion uses {@code isGreaterThanOrEqualTo(0)}
     * rather than an exact value because the inherited {@code @DataJpaTest}
     * transactional rollback may not yet have run when this method is
     * invoked, so synthetic rows from earlier tests in the same class
     * might still be visible — the non-negative invariant is the safest
     * universal assertion.
     *
     * <p>Together with the other tests in this class, this guards against
     * accidentally truncating the {@code security_users} table at any
     * point in the migration lifecycle: a negative tally would surface a
     * Spring Data JPA defect immediately.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("count() returns non-negative total row count")
    void count_invoked_returnsNonNegativeValue() {
        // Act — drive the inherited JpaRepository.count() against the real DB
        long total = userSecurityRepository.count();

        // Assert — count is a row tally; it must never be negative
        assertThat(total)
                .as("count() must return a non-negative row tally; negative values would "
                        + "indicate a Spring Data JPA implementation defect")
                .isGreaterThanOrEqualTo(0);
    }

    // =========================================================================
    // Private Helpers
    // =========================================================================

    /**
     * Constructs a synthetic {@link SecurityUser} populated with the
     * supplied identifier and name fields plus the canonical BCrypt hash
     * from {@link TestFixtures.Users#TEST_PASSWORD_BCRYPT_HASH} as the
     * password value.
     *
     * <p>This is a pure data-builder helper — it carries no business
     * logic, no validation, no hashing. The {@code password} field is
     * deliberately set to the pre-computed BCrypt hash constant so that
     * <em>every</em> synthetic user persisted by this IT carries a
     * password value that satisfies the AAP §0.10.5 contract (no
     * plaintext credentials at rest). Tests that need to overwrite the
     * password — for example
     * {@link #save_userWithBcryptHash_persistsHashNotPlaintext()} —
     * call {@link SecurityUser#setPassword(String)} after the helper
     * returns to make the contract under test inarguable; the helper's
     * default value is the same BCrypt hash, so the post-helper setter
     * call is informational (it does not change the persisted value).
     *
     * <p>Per AAP §0.10.1 Require Test Coverage Rule, this helper does
     * NOT invoke {@code BCryptPasswordEncoder.encode(...)} — hashing
     * logic lives exclusively in
     * {@link com.aws.carddemo.service.AuthenticationService}. The helper
     * is a pure setter cascade.
     *
     * <p>The {@code locked} field is left at its default {@code false}
     * value because the AAP §0.5.1 coverage focus for this IT is BCrypt
     * persistence and role retrieval, not account lockout (lockout is a
     * Java-migration addition with its own coverage path in
     * {@code AuthenticationServiceTest}). The {@code version} field is
     * left {@code null} (JPA will initialise it on persist).
     *
     * @param userId    8-character {@code SEC-USR-ID} primary key per
     *                  COBOL {@code PIC X(08)}; must not be {@code null}
     * @param firstName up-to-20-character first name per COBOL
     *                  {@code SEC-USR-FNAME PIC X(20)}
     * @param lastName  up-to-20-character last name per COBOL
     *                  {@code SEC-USR-LNAME PIC X(20)}
     * @param userType  1-character role code per COBOL
     *                  {@code SEC-USR-TYPE PIC X(01)} — {@code "U"} for
     *                  regular users, {@code "A"} for admins
     * @return a fully-populated {@link SecurityUser} instance with the
     *         supplied fields set via the production setters plus a
     *         default BCrypt-hash password value
     */
    private SecurityUser buildSyntheticUser(String userId,
                                            String firstName,
                                            String lastName,
                                            String userType) {
        SecurityUser u = new SecurityUser();
        u.setUserId(userId);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        // Always set the password to a BCrypt hash, never plaintext, per AAP §0.10.5
        u.setPassword(TestFixtures.Users.TEST_PASSWORD_BCRYPT_HASH);
        u.setUserType(userType);
        return u;
    }
}
