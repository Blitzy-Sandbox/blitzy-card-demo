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
package com.aws.carddemo.testsupport;

// Spring Boot test-slice annotations and infrastructure (AAP §0.4.1, §0.4.4):
//   - @DataJpaTest: lightweight JPA slice (EntityManager + Spring Data
//     repositories + Hibernate + DataSource) -- NO services, NO controllers.
//   - @AutoConfigureTestDatabase(replace = NONE): disables Spring Boot's
//     default in-memory H2 substitution so the configured Testcontainers
//     PostgreSQL DataSource is used.
//   - TestEntityManager: Spring Boot's @DataJpaTest companion bean
//     supplying flush/clear semantics most repository tests expect.
// Spring TestContext support (AAP §0.10.5):
//   - @ActiveProfiles("test"): activates application-test.properties.
//   - @DynamicPropertySource + DynamicPropertyRegistry: bind the
//     Testcontainers JDBC URL and ephemeral credentials into Spring's
//     environment BEFORE context startup (NO plaintext credentials).
// Spring beans @Autowired: field injection for the inherited
// TestEntityManager handle.
// Testcontainers JUnit 5 extension (AAP §0.4.4):
//   - @Testcontainers + @Container static PostgreSQLContainer<?>:
//     per-class shared container lifecycle (started once before the
//     first subclass @Test, stopped after the last).
// java.util.UUID: used to derive ephemeral container database name,
// username, and password at class-load time so no credential string
// literal is committed to source. AAP §0.10.5 (no plaintext credentials)
// and Checkpoint 1 security checklist (no hardcoded credentials in
// testsupport Java files).
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Abstract base class for JPA repository integration tests in the CardDemo
 * migration.
 *
 * <p>This base class supplies the shared {@link Testcontainers} PostgreSQL 16
 * container, the {@link DataJpaTest} slice (JPA + EntityManager + Spring Data
 * repositories only -- no services, no controllers), and the
 * {@link DynamicPropertySource} that injects the container's JDBC URL and
 * ephemeral credentials into Spring's environment before context startup.
 *
 * <p>Subclasses (one per JPA repository, 10 total per AAP §0.5.1):
 * <ul>
 *   <li>{@code AccountRepositoryIT}</li>
 *   <li>{@code CardRepositoryIT}</li>
 *   <li>{@code CardXrefRepositoryIT}</li>
 *   <li>{@code CustomerRepositoryIT}</li>
 *   <li>{@code TransactionRepositoryIT}</li>
 *   <li>{@code TransactionCategoryBalanceRepositoryIT}</li>
 *   <li>{@code DiscountGroupRepositoryIT}</li>
 *   <li>{@code TransactionCategoryRepositoryIT}</li>
 *   <li>{@code TransactionTypeRepositoryIT}</li>
 *   <li>{@code UserSecurityRepositoryIT}</li>
 * </ul>
 *
 * <p>Subclasses need only:
 * <ul>
 *   <li>{@code @Autowired} the specific {@code XxxRepository} under test.</li>
 *   <li>Use the inherited {@link #entityManager} to seed entity state when the
 *       Flyway-applied seed data alone is insufficient, or to flush/clear the
 *       persistence context between assertions.</li>
 *   <li>Add {@code @Test} methods that exercise repository queries and assert
 *       on returned entities (using AssertJ -- AAP §0.10.10).</li>
 * </ul>
 *
 * <h2>Database state</h2>
 *
 * <p>The Flyway scripts {@code V1__schema.sql}, {@code V2__indexes.sql}, and
 * {@code V3__seed.sql} (owned by the migration's REFACTOR flavor, located
 * under {@code src/main/resources/db/migration/}) run automatically on
 * context startup, hydrating the container with the 50-record fixtures
 * derived from {@code app/data/ASCII/}.
 *
 * <h2>Transactional rollback</h2>
 *
 * <p>{@code @DataJpaTest} wraps each {@code @Test} in a transaction that
 * rolls back at method end, so the database state is restored between tests
 * without needing manual cleanup. This satisfies AAP §0.10.9 test isolation
 * ("tests must be independent and runnable in any order").
 *
 * <h2>Why {@code @AutoConfigureTestDatabase(replace = NONE)}</h2>
 *
 * <p>CRITICAL -- without this, Spring Boot would replace the configured
 * PostgreSQL DataSource with an in-memory H2 instance, which would not
 * exercise PostgreSQL-specific features (e.g., {@code RETURNING} clauses,
 * partial indexes, {@code TIMESTAMP WITH TIME ZONE}) used by the migrated
 * repositories. AAP §0.4.4 mandates Testcontainers PostgreSQL 16 for the
 * repository slice.
 *
 * <h2>Why {@code @DataJpaTest} (not {@code @SpringBootTest})</h2>
 *
 * <p>AAP §0.4.4 mandates the lightweight JPA slice for repository ITs.
 * {@code @DataJpaTest} skips loading services, controllers, security, and
 * batch -- materially faster startup than a full {@code @SpringBootTest}
 * and aligned with the AAP §0.7.2 wall-clock target.
 *
 * <h2>Why {@code @Container} is {@code static}</h2>
 *
 * <p>Required by Testcontainers' shared-container pattern -- one container
 * lifecycle per subclass test class (start before first {@code @Test}, stop
 * after last {@code @Test}). A non-static {@code @Container} field would
 * mean a fresh container per test method, which would balloon wall-clock
 * time (AAP §0.7.2 target {@code < 5 min} for {@code mvn verify}).
 *
 * <h2>Security (AAP §0.10.5)</h2>
 *
 * <p>No plaintext credentials are configured here. Testcontainers supplies
 * ephemeral username/password via {@link PostgreSQLContainer#getUsername()}
 * and {@link PostgreSQLContainer#getPassword()}. The container-local
 * credentials are scoped to the container's isolated network namespace and
 * cannot authenticate against any production system.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.4.4 (Test Database / State Management Approach), §0.5.1 (Test
 * Support Utilities table), §0.5.5 (Cross-File Test Dependencies -- Test
 * Utilities), §0.8.1 (Exhaustively In Scope).
 *
 * <h2>Minimal Change Clause (AAP §0.10.2)</h2>
 *
 * <p>This base class carries ONLY the wiring strictly required by repository
 * ITs: container, dynamic property registration, and the {@link TestEntityManager}
 * handle. There are no convenience methods, no shared test-data builders, no
 * fluent assertion helpers. Subclasses retain full responsibility for their
 * arrange-act-assert flows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
@ActiveProfiles("test")
// CRITICAL (CK7 remediation -- Phase 9 Spring TestContext caching fix):
//
// Without @DirtiesContext, Spring's TestContext framework caches the
// ApplicationContext (including its HikariCP DataSource bean) by
// MergedContextConfiguration key. When subclass IT classes share an
// identical configuration footprint (same @ActiveProfiles, same
// @DataJpaTest slice, same @AutoConfigureTestDatabase setting), Spring
// reuses the cached context across them.
//
// However, each IT subclass owns its own @Container static PostgreSQLContainer
// instance with a fresh random host port (e.g., 32793 for IT class A,
// 32816 for IT class B). When IT class A's @Container static field
// completes (Testcontainers JUnit 5 extension calls .stop() after the
// last @Test method on A), the container at port 32793 dies.
//
// If IT class B then reuses the cached ApplicationContext from A, B's
// HikariCP DataSource still points at the dead port 32793 -- even though
// B's @DynamicPropertySource registered port 32816. @DynamicPropertySource
// evaluates LAZILY (deferred supplier callback) but does NOT rebuild
// existing beans; the cached DataSource was constructed against A's
// supplier output and is never re-initialised.
//
// Empirical evidence (CK7 Phase 9 runtime testing):
//   - CustomerRepositoryIT (first to run, 8/8 PASS in 6.506s)
//   - Subsequent ITs fail with:
//       Connection to localhost:32812 refused
//       HikariPool-1 - Connection is not available, request timed out after 30000ms
//   - docker ps shows the new postgres container alive on port 32816
//   - Spring is dialing port 32812 -- the dead container's port from the
//     first IT
//
// Fix: @DirtiesContext(classMode = AFTER_CLASS) forces Spring to discard
// the cached ApplicationContext after the last @Test method on each IT
// subclass completes. The next IT subclass triggers a fresh context
// startup, which re-evaluates @DynamicPropertySource against its own
// fresh container's getJdbcUrl() and constructs a new HikariCP
// DataSource bean against the live port.
//
// Wall-clock cost is acceptable per AAP §0.7.2 ({@code < 5 min} for
// {@code mvn verify}): each IT class re-creates its context in ~2-3s
// alongside its own ~1-2s container startup; total for 10 ITs stays well
// under 1 minute on warm JVM.
//
// Alternative considered: a JVM-singleton PostgreSQLContainer shared
// across all ITs (one container, started once) would also work but
// requires refactoring away from @Container static (an annotation-driven
// lifecycle the AAP §0.10.2 Minimal Change Clause prefers). @DirtiesContext
// is the minimal, idiomatic Spring fix.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractRepositoryIT {

    /**
     * Ephemeral PostgreSQL database name generated at class-load time from
     * a fresh {@link UUID#randomUUID()}. The leading {@code db_} prefix
     * keeps the identifier within the PostgreSQL identifier grammar
     * (must start with a letter or underscore). The UUID hyphens are
     * stripped because they are not legal inside an unquoted SQL
     * identifier; truncating to 12 hex characters keeps the name well
     * under PostgreSQL's 63-byte {@code NAMEDATALEN} limit.
     *
     * <p>This value rotates on every JVM start, eliminating the
     * "hardcoded credential" finding from the Checkpoint 1 review while
     * preserving the AAP §0.4.4 contract that every test class owns its
     * own container.
     */
    private static final String EPHEMERAL_DB_NAME =
            "db_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    /**
     * Ephemeral PostgreSQL username generated at class-load time from a
     * fresh {@link UUID#randomUUID()}. Same identifier-grammar
     * constraints as {@link #EPHEMERAL_DB_NAME}; the leading {@code u_}
     * prefix guarantees the first character is a letter.
     *
     * <p>This value is bound into Spring's environment by
     * {@link #postgresProperties(DynamicPropertyRegistry)} only after the
     * container starts (Testcontainers reflects the configured username
     * back through {@link PostgreSQLContainer#getUsername()}).
     */
    private static final String EPHEMERAL_USERNAME =
            "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    /**
     * Ephemeral PostgreSQL password generated at class-load time from a
     * fresh {@link UUID#randomUUID()}. The full UUID is preserved (36
     * characters including hyphens) because PostgreSQL passwords have no
     * identifier-grammar restriction and a longer entropy footprint is
     * harmless. The value is never logged and is bound into Spring's
     * environment only at runtime.
     */
    private static final String EPHEMERAL_PASSWORD = UUID.randomUUID().toString();

    /**
     * Shared PostgreSQL 16 container, started once per subclass test run by
     * the Testcontainers JUnit 5 extension (activated by the class-level
     * {@code @Testcontainers} annotation).
     *
     * <p>Image tag {@code postgres:16-alpine} matches the companion
     * {@code AbstractBatchIT} so Spring's TestContext cache can reuse the
     * same container across test classes that share an identical
     * configuration footprint -- minimising total wall-clock time for
     * {@code mvn verify}.
     *
     * <p>Per AAP §0.4.4, PostgreSQL 16 is the target engine for repository
     * integration tests (real PostgreSQL semantics, not in-memory H2).
     *
     * <p><strong>Credential strategy (AAP §0.10.5, Checkpoint 1 security
     * checklist).</strong> The container's database name, username, and
     * password are generated at class-load time from {@link UUID#randomUUID()}
     * (see {@link #EPHEMERAL_DB_NAME}, {@link #EPHEMERAL_USERNAME}, and
     * {@link #EPHEMERAL_PASSWORD}). No credential string literal is committed
     * to source. The values are scoped to the container's isolated Docker
     * network namespace and cannot authenticate against any production
     * system; they are bound into Spring's environment by
     * {@link #postgresProperties(DynamicPropertyRegistry)} via
     * {@link PostgreSQLContainer#getUsername()} and
     * {@link PostgreSQLContainer#getPassword()}.
     *
     * <p>{@code withReuse(false)} keeps the container lifecycle explicit
     * per test-class instance. Testcontainers' "reuse" mode is an advanced
     * opt-in that requires {@code ~/.testcontainers.properties} configuration
     * and adds CI complexity (AAP §0.10.2 Minimal Change Clause -- avoid
     * unless strictly necessary).
     */
    @Container
    @SuppressWarnings("resource") // Lifecycle managed by @Container, not try-with-resources
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName(EPHEMERAL_DB_NAME)
            .withUsername(EPHEMERAL_USERNAME)
            .withPassword(EPHEMERAL_PASSWORD)
            .withReuse(false);

    /**
     * Wires the Testcontainers JDBC URL, username, and password into Spring's
     * environment BEFORE the application context starts.
     *
     * <p>The {@link DynamicPropertyRegistry#add(String, java.util.function.Supplier)}
     * callbacks defer evaluation until the property is read by Spring's
     * environment, so the container is guaranteed to have started by the time
     * {@link PostgreSQLContainer#getJdbcUrl()},
     * {@link PostgreSQLContainer#getUsername()}, and
     * {@link PostgreSQLContainer#getPassword()} are invoked.
     *
     * <p>The {@code spring.datasource.driver-class-name} property is pinned
     * to {@code org.postgresql.Driver} so Spring Boot does not attempt the
     * H2 driver auto-detection that {@code @DataJpaTest} would normally apply.
     *
     * <p>The Hibernate dialect is intentionally NOT pinned here: Spring Boot
     * 3.x's Hibernate 6 auto-detects the dialect from the JDBC connection
     * metadata, and pinning it would couple the test base class to a specific
     * Hibernate dialect class name -- a needless brittleness that violates
     * AAP §0.10.2 Minimal Change Clause.
     *
     * <p>Satisfies AAP §0.10.5: no plaintext credentials in
     * {@code application-test.properties}; the file declares the keys empty
     * and this callback supplies the live values at runtime.
     *
     * @param registry the Spring {@link DynamicPropertyRegistry} into which
     *                 the container-derived datasource properties are
     *                 registered.
     */
    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Hibernate dialect auto-detected by Spring Boot 3.x; do not pin here.
    }

    /**
     * The Spring Boot {@link TestEntityManager} companion bean supplied by
     * {@link DataJpaTest}.
     *
     * <p>Subclasses use this handle to seed entities that aren't already in
     * the Flyway-applied seed data, to flush/clear the persistence context
     * between assertions (forcing reads to hit the database rather than the
     * first-level cache), or to assert on entity state by id without going
     * through the repository under test.
     *
     * <p>Typical subclass usage:
     * <pre>{@code
     * @Test
     * void findById_existingAccount_returnsAccount() {
     *     // Arrange: persist a fresh Account via the test entity manager.
     *     Account seeded = entityManager.persistAndFlush(new Account(...));
     *
     *     // Act: invoke the repository under test.
     *     Optional<Account> result = accountRepository.findById(seeded.getId());
     *
     *     // Assert: verify on the production result.
     *     assertThat(result).isPresent();
     * }
     * }</pre>
     *
     * <p>Field injection (not constructor injection) is used here because
     * {@code @DataJpaTest} bootstraps the {@link TestEntityManager} as a
     * Spring bean and {@code AbstractRepositoryIT} is instantiated reflectively
     * by the JUnit Jupiter / Spring TestContext machinery -- a constructor
     * parameter would force every subclass to declare a redundant constructor.
     */
    @Autowired
    protected TestEntityManager entityManager;
}
