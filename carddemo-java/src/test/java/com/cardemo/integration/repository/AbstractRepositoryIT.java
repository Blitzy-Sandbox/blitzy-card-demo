package com.cardemo.integration.repository;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Abstract Testcontainers base class for every repository integration test in
 * {@code com.cardemo.integration.repository}.
 *
 * <p>This is the keystone of the repository-layer <em>parity-validation</em> suite for the AWS
 * CardDemo COBOL &rarr; Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x migration. It wires a real
 * PostgreSQL&nbsp;16 database into a full Spring application context so the 11 production
 * {@link org.springframework.data.jpa.repository.JpaRepository JpaRepository} interfaces &mdash; the
 * relational replacement for the legacy z/OS VSAM KSDS data layer &mdash; can be exercised against a
 * schema and seed data that are provisioned exactly as in production. Concrete {@code *IT} subclasses
 * (for example {@code AccountRepositoryIT}, {@code CardRepositoryIT}, &hellip;) extend this class and
 * add their own {@code @Test} methods; this base class itself contains <strong>no</strong> tests.</p>
 *
 * <h2>Why a SINGLETON container started in a {@code static} block (not {@code @Container})</h2>
 * <p>All 11 concrete subclasses declare an <em>identical</em> Spring context configuration
 * ({@code @SpringBootTest} + {@code @ActiveProfiles("test")} + the {@code @DynamicPropertySource}
 * below). Spring's {@code TestContext} framework caches and reuses a single {@code ApplicationContext}
 * across test classes <em>only when the resolved context configuration &mdash; including every property
 * registered here &mdash; is identical</em>. The datasource JDBC URL is part of that configuration, so
 * it must stay <strong>stable for the whole suite</strong>.</p>
 * <p>This is why the container uses the Testcontainers <strong>singleton pattern</strong>: a single
 * {@code static} {@link PostgreSQLContainer} is started once in a {@code static} initializer and is
 * shared by every subclass. The field is deliberately <strong>not</strong> annotated with
 * {@code @Container} and the class is deliberately <strong>not</strong> driven by the
 * {@code @Testcontainers} JUnit extension: {@code @Container} ties a container's start/stop lifecycle to
 * a single test class, so the JUnit extension would stop the container after the first subclass and
 * start a fresh one (with a fresh, different mapped port and therefore a different JDBC URL) for the
 * next subclass &mdash; which would invalidate the cached context's datasource and force a slow context
 * reload (and a fresh Flyway run) for every class. The singleton is intentionally <strong>never
 * explicitly stopped</strong>; the Testcontainers <em>Ryuk</em> sidecar reaps it when the JVM exits.
 * Sharing one started container + one stable URL is what lets all 11 IT classes share <strong>one</strong>
 * cached context and one Flyway migration &mdash; the single most important performance and correctness
 * decision in this suite. Do not regress it to a per-class {@code @Container}.</p>
 * <p>The end-to-end ({@code e2e}) tests intentionally differ: each {@code e2e} class is a distinct
 * context (it additionally wires LocalStack for AWS), so those classes legitimately use a per-class
 * {@code @Container} field. That difference is by design and does not apply to the repository tier,
 * which needs only PostgreSQL.</p>
 *
 * <h2>Why {@code @SpringBootTest} (not {@code @DataJpaTest})</h2>
 * <p>{@code @SpringBootTest} loads the <strong>full</strong> application context rooted at
 * {@link com.cardemo.CardDemoApplication}, so the real Spring Data JPA repositories, entity mappings,
 * {@code JpaConfig}, and Flyway run exactly as in production. {@code @DataJpaTest} is deliberately
 * avoided: it slices the context, swaps in an embedded database by default, and would skip parts of the
 * real configuration &mdash; defeating the goal of validating the production data layer against a real
 * PostgreSQL instance.</p>
 *
 * <h2>Why all FOUR datasource properties are overridden (including {@code driver-class-name})</h2>
 * <p>The {@code test} profile ({@code application-test.yml}) configures the datasource using the
 * Testcontainers JDBC URL scheme &mdash; {@code jdbc:tc:postgresql:16-alpine:///carddemo} with
 * driver {@code org.testcontainers.jdbc.ContainerDatabaseDriver}. {@link DynamicPropertySource} has the
 * highest property precedence, so the {@link #datasourceProps(DynamicPropertyRegistry)} registrar below
 * overrides {@code spring.datasource.url} to the plain {@code jdbc:postgresql://&hellip;} URL of this
 * singleton container. It therefore <strong>must</strong> also override
 * {@code spring.datasource.driver-class-name} to this container's driver
 * ({@link PostgreSQLContainer#getDriverClassName()} = {@code org.postgresql.Driver}); otherwise the
 * profile's {@code ContainerDatabaseDriver} would be paired with a plain {@code jdbc:postgresql} URL it
 * does not understand, causing a URL/driver mismatch. Registering all four properties (url, username,
 * password, driver-class-name) makes this base robust regardless of which datasource strategy the YAML
 * happens to choose.</p>
 *
 * <h2>Flyway is the sole schema authority</h2>
 * <p>The {@code test} profile inherits {@code spring.jpa.hibernate.ddl-auto=validate} and keeps Flyway
 * enabled, so on context startup Flyway runs the production migrations <em>inside</em> this throwaway
 * container, in strict order: {@code V1__create_schema} (all 11 tables) &rarr; {@code V2__create_indexes}
 * (the {@code CXACAIX} and {@code TRANSACT} alternate indexes and supporting FK indexes) &rarr;
 * {@code V3__seed_data} (the 9 canonical ASCII fixtures). Hibernate then only <em>validates</em> that the
 * JPA entities match the Flyway-built schema. The 9 seeded fixtures are the behavioral-parity ground
 * truth that every subclass asserts against (for example, the {@code account} table is seeded with 50
 * rows). A mapping/DDL mismatch fails context startup &mdash; itself a useful parity check.</p>
 *
 * <h2>Testcontainers 2.x note</h2>
 * <p>This module pins {@code testcontainers-bom:2.0.3}. In Testcontainers&nbsp;2.x the legacy
 * {@code org.testcontainers.containers.PostgreSQLContainer} is {@code @Deprecated}; this class therefore
 * uses the current, non-deprecated {@link org.testcontainers.postgresql.PostgreSQLContainer} (which is
 * non-generic and is constructed from a {@link DockerImageName}). Using it keeps the build warning-free
 * and confines suppressed warnings to the single, documented {@code @SuppressWarnings("resource")} site
 * below.</p>
 *
 * <h2>Scope</h2>
 * <p>Repository integration tests need <strong>only</strong> PostgreSQL; this base intentionally does
 * not wire {@code LocalStack} or any AWS client &mdash; that belongs to {@code integration/aws} and
 * {@code e2e}. The concrete subclasses are run by the {@code maven-failsafe-plugin} under the
 * {@code integration} Maven profile ({@code mvn verify -Pintegration}); {@code maven-surefire-plugin}
 * excludes {@code *IT} / {@code **}{@code /integration/**}. Because this class is {@code abstract} it is
 * never instantiated or executed directly by JUnit/Failsafe.</p>
 *
 * <h2>Provenance &amp; traceability</h2>
 * <p>Net-new greenfield test infrastructure with no COBOL source equivalent. Base package
 * {@code com.cardemo} (decision D-006). Behavior under test is translated from the frozen AWS CardDemo
 * COBOL baseline at commit SHA {@code 27d6c6f}; the COBOL/JCL sources (for example
 * {@code app/jcl/ACCTFILE.jcl} and the {@code app/data/ASCII/*.txt} fixtures) are read-only reference
 * and are never copied into this repository.</p>
 *
 * @see com.cardemo.CardDemoApplication
 * @see PostgreSQLContainer
 * @see DynamicPropertySource
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractRepositoryIT {

    /**
     * Shared, singleton PostgreSQL&nbsp;16 container backing every repository integration test.
     *
     * <p>Started once in the {@code static} initializer below and never explicitly stopped (the
     * Testcontainers <em>Ryuk</em> sidecar reaps it at JVM exit), so its mapped port &mdash; and hence the
     * JDBC URL registered by {@link #datasourceProps(DynamicPropertyRegistry)} &mdash; stays stable for
     * the whole suite, allowing all subclasses to share one cached Spring context. The database name
     * {@code carddemo} mirrors the production / {@code docker-compose} database. The
     * {@code @SuppressWarnings("resource")} is the single, deliberate suppression in this class: the
     * container is an {@link AutoCloseable} that is intentionally <em>not</em> closed (closing it would
     * defeat the singleton-sharing strategy), so the resource warning is expected and justified.</p>
     */
    @SuppressWarnings("resource") // Singleton container is intentionally never closed; reaped by Ryuk at JVM exit.
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("carddemo");

    static {
        // Manual, one-time start of the singleton (NOT @Container-managed); see the class Javadoc.
        POSTGRES.start();
    }

    /**
     * Registers the singleton container's connection coordinates as Spring datasource properties,
     * overriding the {@code application-test.yml} defaults.
     *
     * <p>All four properties are registered: {@code spring.datasource.url}, {@code username},
     * {@code password}, and (critically) {@code driver-class-name}. The driver override is mandatory
     * because the {@code test} profile may select the {@code jdbc:tc:} URL scheme paired with
     * {@code org.testcontainers.jdbc.ContainerDatabaseDriver}; since this registrar overrides the URL to a
     * plain {@code jdbc:postgresql://&hellip;} value, the driver must also be overridden to this
     * container's {@code org.postgresql.Driver} to avoid a URL/driver mismatch. Suppliers are passed by
     * method reference so the values are resolved lazily, after the container has started.</p>
     *
     * @param registry the Spring-provided registry into which the datasource properties are added;
     *                  never {@code null}.
     */
    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    /**
     * Raw JDBC access for subclasses, bypassing the JPA persistence context.
     *
     * <p>Used primarily by the optimistic-locking tests (for example {@code AccountRepositoryIT} and
     * {@code CardRepositoryIT}) to bump an entity's {@code @Version} column out-of-band &mdash; e.g.
     * {@code jdbcTemplate.update("UPDATE account SET version = version + 1 WHERE account_id = ?", id)}
     * &mdash; to simulate a concurrent modification, and for raw {@code COUNT(*)} cross-checks against the
     * Flyway-seeded data.</p>
     */
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * JPA entity manager for subclasses that need a genuine database round-trip after a {@code save}.
     *
     * <p>Subclasses typically call {@code entityManager.flush()} followed by {@code entityManager.clear()}
     * after a {@code repository.save(&hellip;)} and then re-{@code findById(&hellip;)} so the first-level
     * persistence cache cannot mask a mapping or round-trip defect (the re-read comes from the database,
     * not from the managed-entity cache).</p>
     */
    @Autowired
    protected EntityManager entityManager;

    /**
     * Asserts that two monetary / decimal values are numerically equal using
     * {@link BigDecimal#compareTo(BigDecimal)} rather than {@link BigDecimal#equals(Object)}.
     *
     * <p>This centralizes the decimal-fidelity rule (AAP &sect;0.7.3): {@code BigDecimal.equals} is
     * scale-sensitive (so {@code 194.00} is <em>not</em> {@code equals} to {@code 194.0}), whereas
     * {@code compareTo} compares numeric value irrespective of scale, which is the correct semantics for
     * comparing COBOL {@code COMP-3} / {@code PIC S9(n)V99} amounts migrated to {@code NUMERIC(p,s)}.
     * Two {@code null} values are treated as equal; a {@code null} paired with a non-{@code null} value
     * fails.</p>
     *
     * @param expected the expected monetary value; may be {@code null}.
     * @param actual   the actual monetary value; may be {@code null}.
     */
    protected void assertAmountEquals(BigDecimal expected, BigDecimal actual) {
        if (expected == null || actual == null) {
            Assertions.assertTrue(expected == null && actual == null,
                    () -> "Monetary value nullity mismatch: expected <" + expected
                            + "> but was <" + actual + ">");
            return;
        }
        Assertions.assertTrue(expected.compareTo(actual) == 0,
                () -> "Monetary value mismatch (compared via BigDecimal.compareTo, scale-insensitive "
                        + "per AAP \u00a70.7.3): expected <" + expected + "> but was <" + actual + ">");
    }
}
