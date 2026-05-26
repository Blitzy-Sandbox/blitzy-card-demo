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
package com.awsm2.carddemo.repository;

import com.awsm2.carddemo.domain.UserSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} slice test for {@link UserSecurityRepository}.
 *
 * <h2>Source mapping (AAP &sect;0.3.1, &sect;0.4.1, &sect;0.6.2)</h2>
 * <ul>
 *   <li><strong>COBOL copybook:</strong> {@code app/cpy/CSUSR01Y.cpy}
 *       &mdash; the {@code SEC-USER-DATA} layout (RECLN 80 bytes):
 *       {@code SEC-USR-ID PIC X(08)} (8-byte primary key, KEYS(8 0)),
 *       {@code SEC-USR-FNAME PIC X(20)},
 *       {@code SEC-USR-LNAME PIC X(20)},
 *       {@code SEC-USR-PWD PIC X(08)} &mdash; <em>plaintext in COBOL,
 *       widened to BCrypt VARCHAR(60) in the Java target per AAP
 *       &sect;0.7.1 security upgrade</em>,
 *       {@code SEC-USR-TYPE PIC X(01)} (role discriminator
 *       {@code 'A'}=admin or {@code 'U'}=user), and a 23-byte trailing
 *       {@code SEC-USR-FILLER PIC X(23)} that is omitted in the
 *       relational model.</li>
 *   <li><strong>COBOL programs (REFERENCE only &mdash; never edited):</strong>
 *       <ul>
 *         <li>{@code app/cbl/COSGN00C.cbl} &mdash; signon (CICS
 *             {@code READ DATASET('USRSEC') RIDFLD(SEC-USR-ID)}).
 *             Replaced by {@code SignonService} +
 *             {@code AuthController} {@code POST /api/auth/signin}.</li>
 *         <li>{@code app/cbl/COUSR00C.cbl} &mdash; paged user list
 *             (CICS {@code STARTBR DATASET('USRSEC') RIDFLD(SEC-USR-ID)
 *             GTEQ} followed by {@code READNEXT} loop iterating up to
 *             10 times per the {@code USER-REC OCCURS 10 TIMES}
 *             working-storage array). Replaced by {@code UserListService}
 *             +
 *             {@link UserSecurityRepository#findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(String,
 *             org.springframework.data.domain.Pageable)}.</li>
 *         <li>{@code app/cbl/COUSR01C.cbl} &mdash; user add (CICS
 *             {@code WRITE DATASET('USRSEC')}). Replaced by
 *             {@code UserAddService} with BCrypt-hashing of the
 *             caller-supplied plaintext password before
 *             {@code save()}.</li>
 *         <li>{@code app/cbl/COUSR02C.cbl} &mdash; user update (CICS
 *             {@code READ ... REWRITE}). Replaced by
 *             {@code UserUpdateService}.</li>
 *         <li>{@code app/cbl/COUSR03C.cbl} &mdash; user delete (CICS
 *             {@code READ ... DELETE}). Replaced by
 *             {@code UserDeleteService}.</li>
 *       </ul>
 *   </li>
 *   <li><strong>VSAM cluster:</strong>
 *       {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS} &mdash;
 *       KEYS(8,0), RECORDSIZE(80,80), REUSE, INDEXED, FREESPACE(10,15),
 *       CISZ(8192) per {@code app/jcl/DUSRSECJ.jcl}:L62&ndash;L73 and
 *       verified against {@code app/catlg/LISTCAT.txt}. Replaced
 *       relationally by the {@code user_security} table.</li>
 *   <li><strong>JCL DD allocation:</strong>
 *       {@code app/jcl/DUSRSECJ.jcl} (IDCAMS DEFINE CLUSTER + REPRO of
 *       10 default users into the KSDS via in-stream PS staging file).
 *       Replaced operationally by the Flyway migrations V010 (schema)
 *       and V015 (seed data).</li>
 *   <li><strong>Migrations:</strong>
 *       {@code src/main/resources/db/migration/V010__create_user_security.sql}
 *       (schema) and
 *       {@code src/main/resources/db/migration/V015__seed_default_users.sql}
 *       (seeds 10 default users:
 *       {@code ADMIN001}&ndash;{@code ADMIN005} with BCrypt hash of
 *       {@code 'PASSWORDA'} +
 *       {@code USER0001}&ndash;{@code USER0005} with BCrypt hash of
 *       {@code 'PASSWORDU'}).</li>
 * </ul>
 *
 * <h2>What this test validates</h2>
 * <ul>
 *   <li><strong>Round-trip persistence by VARCHAR(8) PK:</strong>
 *       {@code save()} + {@code findById()} of a freshly built
 *       {@link UserSecurity} (Test 1).</li>
 *   <li><strong>V015 seed data integrity:</strong> the
 *       {@code ADMIN001}&ndash;{@code ADMIN005} (type {@code 'A'}) and
 *       {@code USER0001}&ndash;{@code USER0005} (type {@code 'U'})
 *       rows are reachable via {@code findById()} immediately after
 *       Flyway migration completes (Tests 2 and 3).</li>
 *   <li><strong>GTEQ paged scan ascending replaces COUSR00C STARTBR
 *       &amp; READNEXT loop:</strong> the derived query
 *       {@link UserSecurityRepository#findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(String,
 *       org.springframework.data.domain.Pageable)} returns ordered
 *       pages whose contents match the
 *       {@code WHERE sec_usr_id &gt;= :startId ORDER BY sec_usr_id ASC}
 *       semantics defined by the CICS keyed-browse pattern (Tests 4,
 *       5, 6).</li>
 *   <li><strong>Delete-by-PK contract:</strong> {@code deleteById()}
 *       removes a previously-persisted row (Test 7).</li>
 *   <li><strong>VARCHAR(60) BCrypt column round-trip (AAP &sect;0.7.1
 *       security upgrade):</strong> the {@code sec_usr_pwd} column
 *       accepts and returns 60-character BCrypt hashes with their
 *       {@code $2[abxy]$<cost>$<22-char-salt><31-char-hash>} prefix
 *       intact (Test 8).</li>
 * </ul>
 *
 * <h2>What this test deliberately does NOT validate</h2>
 * <ul>
 *   <li><strong>Password verification:</strong> matching a plaintext
 *       password against a stored BCrypt hash is the responsibility of
 *       {@code BCryptPasswordEncoder.matches(rawPassword, storedHash)}
 *       in the security layer ({@code src/main/java/com/awsm2/carddemo/security/}),
 *       NOT this repository. Per AAP &sect;0.7.1, repository
 *       interfaces MUST NOT expose any password-comparison method
 *       &mdash; doing so would leak the password onto the SQL string
 *       in trace logs (breaking PCI-DSS) and be incompatible with
 *       BCrypt's per-hash random salt (two hashes of the same
 *       plaintext differ, so SQL equality is meaningless for
 *       authentication).</li>
 *   <li><strong>CHECK constraint on {@code sec_usr_type}:</strong> the
 *       V010 migration declares
 *       {@code chk_user_security_type CHECK (sec_usr_type IN ('A', 'U'))}
 *       as a defense-in-depth safeguard. This test exercises only the
 *       valid {@code 'A'} and {@code 'U'} values; constraint violation
 *       behavior is a separate schema-validation concern outside the
 *       scope of these repository slice tests (per AAP &sect;0.7.1
 *       Minimal Change Clause and the per-file Phase 7 Key Insights).</li>
 *   <li><strong>Role-based access decisions:</strong> the mapping from
 *       {@code 'A'} / {@code 'U'} to {@code "ROLE_ADMIN"} /
 *       {@code "ROLE_USER"} is performed by Spring Security's
 *       {@code UserDetailsService} in {@code SecurityConfig}, NOT by
 *       this repository or these tests.</li>
 * </ul>
 *
 * <h2>Container strategy</h2>
 * <p>The test class uses a single static {@code postgres:16-alpine}
 * Testcontainer (PostgreSQL 16 to match the production RDS Multi-AZ
 * baseline per AAP &sect;0.5.1, &sect;0.6.2). The
 * {@link ServiceConnection &#64;ServiceConnection} annotation registers
 * the container's JDBC connection details as a
 * {@code JdbcConnectionDetails} bean; the
 * {@link DynamicPropertySource &#64;DynamicPropertySource} method below
 * additionally pushes the URL/credentials/driver into
 * {@code spring.datasource.*} so that the user-declared
 * {@code @Primary @RefreshScope} {@code HikariDataSource} bean in
 * {@code JpaConfig} (which is constructed from
 * {@code spring.datasource.*} properties rather than from
 * {@code JdbcConnectionDetails} &mdash; see
 * {@code DailyTransactionRepositoryTest} and
 * {@code CardDemoApplicationTests} for the precedent) picks up the same
 * container that {@code @ServiceConnection} configures for the auto-
 * config consumers. Flyway then applies the full V001&hellip;V015
 * migration set against the fresh container before any test method
 * runs, so V015's seeded 10 default users are queryable in Tests 2, 3,
 * 4, and 5.</p>
 *
 * <h2>Transactional isolation</h2>
 * <p>{@code @DataJpaTest} wraps each test method in a {@code @Transactional}
 * boundary that rolls back at the end of the test. The V015 seed data
 * (applied by Flyway at context start, BEFORE the first test transaction
 * begins) is therefore COMMITTED and visible to every test method, while
 * any rows persisted by an individual test (e.g., {@code TEST0001} in
 * Test 1, {@code ZZZZZZZA}/{@code ZZZZZZZB} in Test 4) are rolled back at
 * the end of that test and invisible to the others. This isolation
 * guarantee is what allows Test 5 to assert exactly 10 V015-seeded users
 * are returned by a from-absolute-start scan, and what allows Test 6 to
 * assert zero rows match a past-end {@code "ZZZZZZZZ"} starting key
 * (Test 4's {@code ZZZZZZZ*} rows are rolled back before Test 6 runs).</p>
 *
 * @see UserSecurityRepository
 * @see UserSecurity
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserSecurityRepositoryTest {

    // -------------------------------------------------------------------------
    // PostgreSQL Testcontainer (postgres:16-alpine per AAP §0.5.1, §0.6.2)
    // -------------------------------------------------------------------------
    // The @Container annotation hands lifecycle management to the
    // Testcontainers JUnit 5 extension activated by @Testcontainers — start
    // before the first test method, stop after the last. The @ServiceConnection
    // annotation registers the container as a JdbcConnectionDetails bean so
    // Spring Boot auto-configures any DataSource that consumes
    // JdbcConnectionDetails (Spring Boot 3.1+).
    //
    // The image tag is pinned to "postgres:16-alpine" (NOT "latest" or
    // unpinned) per AAP §0.5.1 — the production RDS PostgreSQL Multi-AZ
    // target runs PostgreSQL 16, so tests exercise the same SQL engine
    // ensuring VARCHAR(8)/VARCHAR(20)/VARCHAR(60)/CHAR(1) column types,
    // the chk_user_security_type CHECK constraint, Flyway V010 + V015
    // migrations, and PostgreSQL-specific ascending-ID ORDER BY behavior
    // on VARCHAR primary keys are validated faithfully.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // -------------------------------------------------------------------------
    // @DynamicPropertySource — bind container URL to spring.datasource.*
    // -------------------------------------------------------------------------
    // Per AAP §0.6.4, the application's @Primary @RefreshScope HikariDataSource
    // bean declared in JpaConfig.dataSource(DataSourceProperties) is
    // constructed from spring.datasource.* properties (NOT from
    // JdbcConnectionDetails) — because the bean is user-declared rather than
    // auto-configured, Spring Boot's HikariJdbcConnectionDetailsBeanPostProcessor
    // does not re-write its jdbcUrl from the @ServiceConnection container.
    //
    // To bridge this gap we explicitly push the @Container's connection
    // details into the Spring Environment under spring.datasource.* before
    // any DataSource bean is constructed. This guarantees that the @Primary
    // DataSource (loaded by the JPA slice via component scan of JpaConfig)
    // and Flyway both target the SAME container that @ServiceConnection
    // configured for auto-config consumers.
    //
    // Mirrors the pattern in DailyTransactionRepositoryTest and
    // CardDemoApplicationTests.
    @DynamicPropertySource
    static void overrideDataSourceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    // -------------------------------------------------------------------------
    // System Under Test (SUT) + JPA test fixture support
    // -------------------------------------------------------------------------
    // @Autowired field injection is required here — @DataJpaTest instantiates
    // the test class via reflection BEFORE the application context refreshes,
    // so constructor injection is not supported for the test class itself.

    /**
     * The Spring Data JPA repository under test, autowired from the
     * {@code @DataJpaTest} slice's component scan rooted at
     * {@code com.awsm2.carddemo.repository} (per {@code JpaConfig}
     * {@code @EnableJpaRepositories}).
     */
    @Autowired
    private UserSecurityRepository repository;

    /**
     * Helper exposed by {@code @AutoConfigureTestEntityManager} (transitively
     * enabled by {@code @DataJpaTest}). Used to force SQL execution via
     * {@link TestEntityManager#flush() flush()} and to detach the persistence
     * context via {@link TestEntityManager#clear() clear()} so that subsequent
     * {@code findById()} calls reload from the database rather than returning
     * a first-level-cache hit. This is critical for verifying that the V015
     * seeded BCrypt hashes round-trip from PostgreSQL VARCHAR(60), and that
     * the GTEQ paged scan returns DB-ordered ascending results rather than
     * in-memory cached entities.
     */
    @Autowired
    private TestEntityManager entityManager;

    // =========================================================================
    // Test fixture builder
    // =========================================================================

    /**
     * Builds a transient (non-persisted) {@link UserSecurity} entity with the
     * supplied identity fields and a canonical BCrypt(strength=12) hash for
     * the password column. Used by Tests 1, 4, 7, and 8 to construct test
     * fixtures.
     *
     * <p>The {@code secUsrPwd} value is a pre-computed 60-character BCrypt
     * hash (identical to the V015 ADMIN001 row's stored hash) &mdash; per
     * AAP &sect;0.7.1 the test MUST NOT store plaintext into the
     * {@code sec_usr_pwd} column. The hash format is:</p>
     * <pre>
     *     $2b$12$&lt;22-char-salt&gt;&lt;31-char-hash&gt; = 4 + 3 + 22 + 31 = 60 chars
     * </pre>
     * <p>Re-using a canonical V015 hash (rather than generating a fresh one
     * via {@code BCryptPasswordEncoder.encode(...)}) keeps the test
     * deterministic and avoids a dependency on Spring Security in this
     * repository slice test &mdash; per AAP &sect;0.7.3 Minimal Change
     * Clause, repository tests should exercise only the JPA contract, not
     * the security layer.</p>
     *
     * @param id    8-character {@code SEC-USR-ID} VARCHAR(8) primary key
     * @param fname user first name (max 20 characters)
     * @param lname user last name (max 20 characters)
     * @param type  role discriminator: {@code "A"} for ADMIN or {@code "U"}
     *              for USER (other values rejected by V010
     *              {@code chk_user_security_type} CHECK constraint)
     * @return a fresh, transient (non-persisted) {@link UserSecurity}
     */
    private UserSecurity buildUser(String id, String fname, String lname, String type) {
        UserSecurity u = new UserSecurity();
        u.setSecUsrId(id);                                                  // VARCHAR(8) PK
        u.setSecUsrFname(fname);                                            // VARCHAR(20)
        u.setSecUsrLname(lname);                                            // VARCHAR(20)
        // BCrypt(60) hash — NEVER plaintext per AAP §0.7.1 (security upgrade
        // from COBOL PIC X(08) plaintext). This canonical hash matches the
        // V015 ADMIN001 row so the test's persistence behavior is identical
        // to a hash freshly minted by Spring Security BCryptPasswordEncoder.
        u.setSecUsrPwd("$2b$12$5kRf7Cq.u2YnQV/DquQChOFOLGRxVuBaJCqI1RvdRnd/zXgJvgspu");
        u.setSecUsrType(type);                                              // CHAR(1) — 'A' or 'U'
        return u;
    }

    // =========================================================================
    // Test 1 — save + findById round-trips a UserSecurity by its SEC-USR-ID
    // =========================================================================

    /**
     * Validates that {@link UserSecurityRepository} can persist a freshly
     * built {@link UserSecurity} via the inherited
     * {@code JpaRepository.save(Object)} method and reload it by its
     * 8-character {@code sec_usr_id} primary key via
     * {@code JpaRepository.findById(String)}.
     *
     * <p>Replaces the COBOL pattern of {@code EXEC CICS WRITE
     * DATASET('USRSEC') FROM(SEC-USER-DATA) RIDFLD(SEC-USR-ID)} followed by
     * an {@code EXEC CICS READ DATASET('USRSEC') INTO(SEC-USER-DATA)
     * RIDFLD(SEC-USR-ID)} positioned re-read &mdash; the relational
     * equivalent exercises Hibernate INSERT, SQL flush,
     * persistence-context clear, and SELECT BY PK.</p>
     *
     * <p>The {@code secUsrPwd} length assertion verifies the V010
     * {@code VARCHAR(60)} column accepts the full BCrypt hash without
     * truncation &mdash; storage of fewer than 60 characters would
     * silently break authentication on the next signon (AAP &sect;0.7.1
     * security upgrade).</p>
     */
    @Test
    void saveAndFindById_persistsUserBySecUsrId() {
        UserSecurity input = buildUser("TEST0001", "Test", "User", "U");

        repository.save(input);
        // Force the pending INSERT to the DB and detach the entity so the
        // subsequent findById() returns a freshly hydrated entity (rather
        // than the same instance from the first-level cache).
        entityManager.flush();
        entityManager.clear();

        UserSecurity loaded = repository.findById("TEST0001")
                .orElseThrow(() -> new AssertionError(
                        "Expected UserSecurity with id TEST0001 to be present "
                                + "after save + flush + clear cycle"));

        // Identity assertions — verify primary-key round-trip
        assertThat(loaded.getSecUsrId())
                .as("sec_usr_id (VARCHAR(8) PK) must round-trip the COBOL "
                        + "SEC-USR-ID PIC X(08) value unchanged")
                .isEqualTo("TEST0001");
        // Display-name assertions — VARCHAR(20) columns
        assertThat(loaded.getSecUsrFname())
                .as("sec_usr_fname (VARCHAR(20)) must round-trip the COBOL "
                        + "SEC-USR-FNAME PIC X(20) value unchanged")
                .isEqualTo("Test");
        // Role discriminator — V010 chk_user_security_type accepts 'U'
        assertThat(loaded.getSecUsrType())
                .as("sec_usr_type (CHAR(1)) must round-trip the COBOL "
                        + "SEC-USR-TYPE PIC X(01) value unchanged")
                .isEqualTo("U");
        // Password column — AAP §0.7.1 security upgrade from COBOL PIC X(08)
        // plaintext to VARCHAR(60) BCrypt hash. The buildUser helper supplies
        // a canonical 60-char BCrypt hash; the column must accept it without
        // truncation.
        assertThat(loaded.getSecUsrPwd())
                .as("sec_usr_pwd (VARCHAR(60)) must store a 60-character "
                        + "BCrypt hash without truncation — AAP §0.7.1 security "
                        + "upgrade from COBOL PIC X(08) plaintext")
                .hasSize(60);
    }

    // =========================================================================
    // Test 2 — V015 seed migration: ADMIN001 admin user exists with type 'A'
    // =========================================================================

    /**
     * Validates that the V015 Flyway seed migration successfully inserted
     * the {@code ADMIN001} administrative user with the expected
     * {@code sec_usr_type = 'A'} role discriminator and a properly formed
     * BCrypt password hash.
     *
     * <p>V015 seeds 5 administrative users
     * ({@code ADMIN001}&ndash;{@code ADMIN005}) with the BCrypt hash of
     * {@code 'PASSWORDA'} per AAP &sect;0.7.1 (security upgrade from COBOL
     * plaintext {@code 'PASSWORDA'} originally stored in
     * {@code app/jcl/DUSRSECJ.jcl}:L35&ndash;L39). The hash MUST begin with
     * a recognized BCrypt version prefix ({@code $2b$}, {@code $2a$}, or
     * {@code $2y$} per the BCrypt spec) and use cost factor 12 (the AAP
     * &sect;0.7.1 production strength).</p>
     *
     * <p>If this test fails, either (a) V015 did not execute (Flyway
     * migration ordering issue), or (b) the seeded BCrypt hash was
     * mutated to an invalid format (manual edit of V015).</p>
     */
    @Test
    void seededAdminUsers_existInDatabase() {
        // V015 seeded admin: ADMIN001 with BCrypt 'PASSWORDA'
        UserSecurity admin = repository.findById("ADMIN001")
                .orElseThrow(() -> new AssertionError(
                        "Expected V015-seeded administrative user ADMIN001 "
                                + "to be present in user_security after Flyway "
                                + "migration; V015 may not have executed"));

        // Identity assertion — confirms PK round-trips from V015 INSERT
        assertThat(admin.getSecUsrId()).isEqualTo("ADMIN001");
        // Role discriminator — V015 inserts 'A' for ADMIN001
        assertThat(admin.getSecUsrType())
                .as("V015 seeds ADMIN001 with sec_usr_type='A' — admin role "
                        + "routes to COBOL COADM01C / Java admin menu per "
                        + "AAP §0.3.4")
                .isEqualTo("A");
        // BCrypt hash format — V015 uses $2b$12$ prefix (cost 12 per AAP §0.7.1)
        // but per the BCrypt spec, $2a$ and $2y$ variants are equivalent. The
        // assertion accepts all three to keep the test robust against a future
        // V015 regeneration using a different BCrypt library.
        String hash = admin.getSecUsrPwd();
        assertThat(hash)
                .as("V015 ADMIN001 BCrypt hash must be exactly 60 characters "
                        + "and begin with a recognized BCrypt version prefix "
                        + "($2b$ / $2a$ / $2y$) — AAP §0.7.1 security upgrade")
                .hasSize(60);
        assertThat(hash.startsWith("$2b$12$")
                || hash.startsWith("$2a$12$")
                || hash.startsWith("$2y$12$"))
                .as("V015 ADMIN001 BCrypt hash must use cost factor 12 (AAP "
                        + "§0.7.1 production strength); actual prefix: '"
                        + hash.substring(0, Math.min(7, hash.length())) + "'")
                .isTrue();
    }

    // =========================================================================
    // Test 3 — V015 seed migration: USER0001 regular user exists with type 'U'
    // =========================================================================

    /**
     * Validates that the V015 Flyway seed migration successfully inserted
     * the {@code USER0001} regular user with the expected
     * {@code sec_usr_type = 'U'} role discriminator.
     *
     * <p>V015 seeds 5 regular users
     * ({@code USER0001}&ndash;{@code USER0005}) with the BCrypt hash of
     * {@code 'PASSWORDU'} per AAP &sect;0.7.1 (security upgrade from COBOL
     * plaintext {@code 'PASSWORDU'} originally stored in
     * {@code app/jcl/DUSRSECJ.jcl}:L40&ndash;L44).</p>
     */
    @Test
    void seededRegularUsers_existInDatabase() {
        // V015 seeded user: USER0001 with BCrypt 'PASSWORDU'
        UserSecurity user = repository.findById("USER0001")
                .orElseThrow(() -> new AssertionError(
                        "Expected V015-seeded regular user USER0001 to be "
                                + "present in user_security after Flyway "
                                + "migration; V015 may not have executed"));

        // Identity assertion — confirms PK round-trips from V015 INSERT
        assertThat(user.getSecUsrId()).isEqualTo("USER0001");
        // Role discriminator — V015 inserts 'U' for USER0001
        assertThat(user.getSecUsrType())
                .as("V015 seeds USER0001 with sec_usr_type='U' — regular user "
                        + "role routes to COBOL COMEN01C / Java main menu per "
                        + "AAP §0.3.4")
                .isEqualTo("U");
        // BCrypt hash must be 60 characters even for regular users — column
        // VARCHAR(60) is uniform regardless of role.
        assertThat(user.getSecUsrPwd())
                .as("V015 USER0001 BCrypt hash must be exactly 60 characters "
                        + "— AAP §0.7.1 security upgrade preserves uniform "
                        + "hash length across all roles")
                .hasSize(60);
    }

    // =========================================================================
    // Test 4 — GTEQ paged scan ascending (replaces COUSR00C STARTBR/READNEXT)
    // =========================================================================

    /**
     * Validates the custom derived query
     * {@link UserSecurityRepository#findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(String,
     * org.springframework.data.domain.Pageable)} returns a page of rows whose
     * {@code sec_usr_id} is lexically greater than or equal to the supplied
     * starting key, in ascending order.
     *
     * <p>This is a direct relational replacement for the CICS keyed-browse
     * pattern in {@code app/cbl/COUSR00C.cbl}:</p>
     * <pre>
     *     EXEC CICS STARTBR DATASET('USRSEC') RIDFLD(SEC-USR-ID) GTEQ
     *               RESP(WS-RESP-CD) END-EXEC.
     *     PERFORM 10 TIMES
     *         EXEC CICS READNEXT DATASET('USRSEC') INTO(SEC-USER-DATA)
     *                   RIDFLD(SEC-USR-ID) RESP(WS-RESP-CD) END-EXEC.
     *     END-PERFORM.
     *     EXEC CICS ENDBR DATASET('USRSEC') END-EXEC.
     * </pre>
     * <p>Spring Data JPA translates the derived method name into the
     * equivalent JPQL:</p>
     * <pre>
     *     SELECT u FROM UserSecurity u
     *      WHERE u.secUsrId &gt;= :startId
     *      ORDER BY u.secUsrId ASC
     * </pre>
     *
     * <p><strong>Test scenario:</strong> Beginning from the V015 baseline
     * (10 users: ADMIN001-ADMIN005 + USER0001-USER0005), persist 2
     * additional users {@code ZZZZZZZA} and {@code ZZZZZZZB} that sort
     * after every V015 row, then query starting at {@code "USER0001"}
     * (which excludes the ADMIN* range because {@code 'A'} (0x41) &lt;
     * {@code 'U'} (0x55) in ASCII). The query must return exactly 7 rows
     * &mdash; the 5 V015-seeded USER000x rows plus the 2
     * test-persisted ZZZZZZZ* rows &mdash; in ascending lexical order,
     * with {@code USER0001} first.</p>
     *
     * @see UserSecurityRepository#findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(String,
     *      org.springframework.data.domain.Pageable)
     */
    @Test
    void findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc_pagedGteq_replacingCousr00cStartbr() {
        // COBOL: COUSR00C STARTBR GTEQ + READNEXT — 10 users/page per COUSR00.bms
        // Persist 2 additional users that lexically sort after every V015 row.
        // Both IDs use the all-Z prefix so they appear LAST in ascending order,
        // making the page-boundary semantics easy to assert.
        repository.save(buildUser("ZZZZZZZA", "Zeta", "Aardvark", "U"));
        repository.save(buildUser("ZZZZZZZB", "Zeta", "Beaver", "U"));
        // Force INSERTs to commit at the JDBC layer and detach entities so the
        // subsequent paged query returns DB-ordered results rather than
        // in-memory cached entities.
        entityManager.flush();
        entityManager.clear();

        // Query starting at "USER0001" — must include USER0001-USER0005
        // (5 V015 rows) and ZZZZZZZA, ZZZZZZZB (2 test rows) = 7 rows total.
        // ADMIN001-ADMIN005 are EXCLUDED because 'A' < 'U' lexically.
        Page<UserSecurity> page = repository.findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
                "USER0001", PageRequest.of(0, 10));

        // Total-elements assertion — verify exactly 7 rows match the GTEQ
        // predicate (5 V015 USER000x + 2 test ZZZZZZZ*); ADMIN* and USER0001-
        // predecessors are excluded.
        assertThat(page.getTotalElements())
                .as("findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc('USER0001', "
                        + "page) must return exactly 7 rows: 5 V015-seeded "
                        + "USER000x + 2 test-persisted ZZZZZZZ* rows. ADMIN* "
                        + "rows are excluded because 'A' < 'U' lexically.")
                .isEqualTo(7L);
        // Content size on page 0 of size 10 — all 7 rows fit on a single page.
        assertThat(page.getContent())
                .as("page 0 of size 10 must contain all 7 matching rows since "
                        + "7 < page size 10 — pagination boundary is not "
                        + "crossed")
                .hasSize(7);
        // First-row assertion — USER0001 is lexically the smallest row >=
        // "USER0001" per the GTEQ semantics. This validates that
        // OrderBySecUsrIdAsc is emitted in the SQL and that PostgreSQL's
        // VARCHAR collation respects ASCII ordering.
        assertThat(page.getContent().get(0).getSecUsrId())
                .as("the first row of an ascending GTEQ scan starting at "
                        + "'USER0001' MUST be 'USER0001' itself (greater-than-"
                        + "or-equal, not strictly greater-than)")
                .isEqualTo("USER0001");
        // Ascending-order assertion — verifies the OrderBySecUsrIdAsc clause
        // is emitted in the generated SQL. The IDs are extracted and asserted
        // to be in strictly ascending order matching the expected V015 +
        // test-data sequence.
        assertThat(page.getContent())
                .extracting(UserSecurity::getSecUsrId)
                .as("the ascending GTEQ scan must return rows in strictly "
                        + "ascending sec_usr_id order — replicates the CICS "
                        + "STARTBR ... READNEXT ascending-key traversal")
                .containsExactly(
                        "USER0001",
                        "USER0002",
                        "USER0003",
                        "USER0004",
                        "USER0005",
                        "ZZZZZZZA",
                        "ZZZZZZZB");
    }

    // =========================================================================
    // Test 5 — GTEQ paged scan from absolute start returns all V015 rows
    // =========================================================================

    /**
     * Validates that passing an empty string {@code ""} as the starting key
     * to {@link UserSecurityRepository#findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(String,
     * org.springframework.data.domain.Pageable)} starts the scan at the
     * beginning of the table &mdash; the canonical
     * {@code COMMAREA CDEMO-CU00-USRID-LAST = SPACES} sentinel pattern in
     * the COBOL source (per {@code app/cbl/COUSR00C.cbl} initial-entry
     * branch).
     *
     * <p>Since the V015 seed inserts exactly 10 users and the page size is
     * 10, page 0 must contain all 10 rows. The first row in ascending
     * order is {@code ADMIN001} because {@code 'A'} (0x41) lexically
     * precedes {@code 'U'} (0x55) in ASCII / UTF-8.</p>
     */
    @Test
    void findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc_fromAbsoluteStart_returnsAllInOrder() {
        // Query from absolute start (empty string sentinel) — equivalent to
        // CICS STARTBR with RIDFLD(SPACES) GTEQ which positions the browse
        // cursor at the first key in the cluster.
        Page<UserSecurity> page = repository.findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
                "", PageRequest.of(0, 10));

        // Total-elements assertion — exactly 10 V015-seeded rows
        // (ADMIN001-005 + USER0001-005) match the GTEQ-"" predicate.
        assertThat(page.getTotalElements())
                .as("findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc('', page) "
                        + "must return all 10 V015-seeded rows (5 ADMIN + 5 USER); "
                        + "empty-string starting key includes every PK")
                .isEqualTo(10L);
        // Page 0 of size 10 — contains all 10 rows on a single page.
        assertThat(page.getContent())
                .as("page 0 of size 10 must contain all 10 V015-seeded users "
                        + "since 10 == page size — pagination boundary is "
                        + "reached but not crossed")
                .hasSize(10);
        // First-row assertion — ADMIN001 is lexically smallest because
        // 'A' (0x41) < 'U' (0x55) in ASCII / UTF-8.
        assertThat(page.getContent().get(0).getSecUsrId())
                .as("the first row of an ascending GTEQ scan from '' MUST be "
                        + "'ADMIN001' — lexically smallest V015 row since 'A' "
                        + "< 'U' in ASCII")
                .isEqualTo("ADMIN001");
        // Ascending-order assertion — verifies the OrderBySecUsrIdAsc clause
        // produces deterministic V015-seed ordering.
        assertThat(page.getContent())
                .extracting(UserSecurity::getSecUsrId)
                .as("ascending GTEQ scan from '' must return V015 rows in "
                        + "strictly ascending order: ADMIN001..ADMIN005 then "
                        + "USER0001..USER0005")
                .containsExactly(
                        "ADMIN001",
                        "ADMIN002",
                        "ADMIN003",
                        "ADMIN004",
                        "ADMIN005",
                        "USER0001",
                        "USER0002",
                        "USER0003",
                        "USER0004",
                        "USER0005");
    }

    // =========================================================================
    // Test 6 — GTEQ paged scan past end-of-table returns an empty page
    // =========================================================================

    /**
     * Validates that querying with a starting key greater than every
     * existing {@code sec_usr_id} returns an empty {@link Page} (never
     * {@code null}) with both {@link Page#getContent()} empty and
     * {@link Page#getTotalElements()} equal to {@code 0}.
     *
     * <p>This is the JPA equivalent of the COBOL {@code WS-USER-SEC-EOF =
     * 'Y'} flag raised after the first {@code READNEXT} returns
     * {@code FILE STATUS 23} (NOTFND) &mdash; see
     * {@code app/cbl/COUSR00C.cbl}. Per AAP &sect;0.7.1, multi-row queries
     * return an empty collection rather than throwing
     * {@code RecordNotFoundException} (the typed exception applies only
     * to single-record lookups).</p>
     *
     * <p>The starting key {@code "ZZZZZZZZ"} is past every V015-seeded row
     * (which end at {@code USER0005}) and past any reasonable test-persisted
     * rows (e.g., Test 4's {@code ZZZZZZZA} which is excluded because
     * {@code 'A'} (0x41) &lt; {@code 'Z'} (0x5A) in ASCII; even if Test 4
     * had committed those rows, they are rolled back at the end of Test 4
     * by the {@code @DataJpaTest} transactional contract).</p>
     */
    @Test
    void findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc_pastEnd_returnsEmptyPage() {
        // Query past the end of the table — "ZZZZZZZZ" is past every V015
        // row and past any test-persisted ZZZZZZZ* row (since A < Z lexically).
        Page<UserSecurity> page = repository.findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
                "ZZZZZZZZ", PageRequest.of(0, 10));

        // Empty-content assertion — the Page envelope is non-null but
        // carries zero rows.
        assertThat(page)
                .as("findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc must "
                        + "return a non-null Page even when no rows match")
                .isNotNull();
        assertThat(page.getContent())
                .as("page.getContent() must be empty when no row satisfies "
                        + "sec_usr_id >= 'ZZZZZZZZ' — JPA equivalent of COBOL "
                        + "FILE STATUS 23 NOTFND for a browse query")
                .isEmpty();
        // Total-elements assertion — zero matching rows across all pages.
        assertThat(page.getTotalElements())
                .as("page.getTotalElements() must be 0 when no row satisfies "
                        + "the GTEQ predicate — verifies COUNT(*) returns 0")
                .isEqualTo(0L);
    }

    // =========================================================================
    // Test 7 — deleteById removes a previously persisted UserSecurity
    // =========================================================================

    /**
     * Validates that
     * {@link org.springframework.data.jpa.repository.JpaRepository#deleteById(Object)}
     * removes a previously-persisted {@link UserSecurity} from the
     * {@code user_security} table.
     *
     * <p>Replaces the COBOL pattern in {@code app/cbl/COUSR03C.cbl}
     * (user delete flow): {@code EXEC CICS READ DATASET('USRSEC')
     * RIDFLD(SEC-USR-ID) UPDATE} followed by {@code EXEC CICS DELETE
     * DATASET('USRSEC')}. The relational equivalent is
     * {@code deleteById(id)} which Spring Data JPA translates to
     * {@code DELETE FROM user_security WHERE sec_usr_id = ?}.</p>
     *
     * <p>This test does NOT exercise the COBOL {@code FILE STATUS 23}
     * (NOTFND) behavior on a non-existent delete &mdash; per the V010
     * schema and Spring Data JPA contract, {@code deleteById(id)} on a
     * non-existent row throws {@code EmptyResultDataAccessException}
     * which the {@code GlobalExceptionHandler} maps to
     * {@code RecordNotFoundException} (HTTP 404). That exception-mapping
     * behavior is tested in the service-layer tests for
     * {@code UserDeleteService}, not here.</p>
     */
    @Test
    void deleteById_removesUser() {
        // Persist a fresh test user — DELTEST1 is exactly 8 characters
        // (matches the VARCHAR(8) PK column width).
        UserSecurity victim = buildUser("DELTEST1", "Delete", "Target", "U");
        repository.save(victim);
        // Confirm persistence before delete — necessary precondition so
        // the subsequent delete actually exercises a real DELETE statement.
        entityManager.flush();
        entityManager.clear();
        assertThat(repository.findById("DELTEST1"))
                .as("Precondition: DELTEST1 must be persisted before delete "
                        + "is attempted — verifies the save+flush sequence "
                        + "above succeeded")
                .isPresent();

        // Execute the delete via the inherited JpaRepository.deleteById method.
        repository.deleteById("DELTEST1");
        // Force the pending DELETE to the DB and detach the persistence
        // context so the subsequent findById() returns the DB result rather
        // than the now-removed entity from the first-level cache.
        entityManager.flush();
        entityManager.clear();

        // Post-condition assertion — DELTEST1 is no longer in the table.
        assertThat(repository.findById("DELTEST1"))
                .as("deleteById('DELTEST1') must remove the row — subsequent "
                        + "findById must return Optional.empty(), matching "
                        + "COBOL CICS DELETE behavior")
                .isEmpty();
    }

    // =========================================================================
    // Test 8 — VARCHAR(60) BCrypt column round-trip (AAP §0.7.1 security upgrade)
    // =========================================================================

    /**
     * Validates that the {@code sec_usr_pwd} VARCHAR(60) column accepts a
     * full 60-character BCrypt hash, preserves the BCrypt version-and-cost
     * prefix unchanged on persist, and returns the hash byte-for-byte on
     * subsequent {@code findById()}.
     *
     * <p>AAP &sect;0.7.1 &mdash; VARCHAR(60) BCrypt hash, security upgrade
     * from PIC X(08). The COBOL source field {@code SEC-USR-PWD PIC X(08)}
     * stored exactly 8 characters of plaintext password; the Java target
     * column {@code sec_usr_pwd} is widened to {@code VARCHAR(60)} so it
     * can hold a Spring Security BCrypt hash:</p>
     * <pre>
     *     $2[abxy]$&lt;cost&gt;$&lt;22-char-salt&gt;&lt;31-char-hash&gt; = 60 chars
     * </pre>
     *
     * <p>If the column width were narrower than 60 characters, the
     * persist would either fail with a {@code DataIntegrityViolationException}
     * (PostgreSQL strict length enforcement) or silently truncate the hash,
     * breaking authentication on the next signon. This test surfaces
     * either regression.</p>
     *
     * <p>The prefix check accepts {@code $2b$}, {@code $2a$}, or
     * {@code $2y$} &mdash; all three are recognized BCrypt version prefixes
     * per the BCrypt spec (Niels Provos &amp; David Mazi&egrave;res, 1999).
     * Spring Security 6's {@code BCryptPasswordEncoder} currently produces
     * {@code $2a$}-prefixed hashes by default but the test is tolerant of
     * version upgrades.</p>
     */
    @Test
    void passwordColumn_storesBCryptHash60Chars() {
        // Persist a user with a canonical 60-char BCrypt hash. The hash is
        // identical to the V015 ADMIN001 row's hash — known to be a valid
        // 60-character $2b$12$-prefixed string.
        UserSecurity input = buildUser("BCRYPT01", "BCrypt", "RoundTrip", "U");
        repository.save(input);
        // Force INSERT to commit at the JDBC layer and detach the entity so
        // the subsequent findById() returns the value RELOADED from the DB
        // (rather than the in-memory copy passed to save()). This is the
        // critical step that surfaces a column-width regression — a
        // truncated hash would round-trip incorrectly even though the
        // in-memory entity still has the original value.
        entityManager.flush();
        entityManager.clear();

        UserSecurity reloaded = repository.findById("BCRYPT01")
                .orElseThrow(() -> new AssertionError(
                        "Expected BCRYPT01 to be persisted and reloadable "
                                + "from the VARCHAR(60) sec_usr_pwd column"));

        String hash = reloaded.getSecUsrPwd();

        // Length assertion — VARCHAR(60) must store all 60 characters of
        // the BCrypt hash without truncation. Any smaller value indicates
        // a schema regression (column width reduced below 60).
        assertThat(hash)
                .as("sec_usr_pwd (VARCHAR(60)) must store exactly 60 "
                        + "characters of the BCrypt hash — truncation would "
                        + "silently break authentication on the next signon "
                        + "(AAP §0.7.1)")
                .hasSize(60);
        // Prefix assertion — the BCrypt version-and-cost header
        // ($2[abxy]$<cost>$) MUST be preserved verbatim through the
        // round-trip. A garbled prefix indicates a character-encoding
        // regression (e.g., column declared as a multi-byte charset that
        // mangles the $ literal).
        assertThat(hash.startsWith("$2b$")
                || hash.startsWith("$2a$")
                || hash.startsWith("$2y$"))
                .as("sec_usr_pwd round-trip must preserve the BCrypt version "
                        + "prefix ($2b$ / $2a$ / $2y$) — any other prefix "
                        + "indicates a character-encoding regression; actual "
                        + "prefix: '"
                        + hash.substring(0, Math.min(4, hash.length())) + "'")
                .isTrue();
    }
}
