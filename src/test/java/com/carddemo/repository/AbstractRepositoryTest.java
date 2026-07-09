package com.carddemo.repository;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base class for every Spring Data JPA repository slice test in the
 * {@code com.carddemo.repository} package. Each concrete {@code *RepositoryTest}
 * class {@code extends AbstractRepositoryTest} and therefore executes against a
 * <strong>real PostgreSQL&nbsp;16 database</strong> provisioned on demand by
 * Testcontainers — never an in-memory/H2 substitute and never a mock.
 *
 * <h2>Why a real PostgreSQL 16 container</h2>
 * <p>The CardDemo migration preserves COBOL decimal fidelity: every monetary
 * {@code PIC S9(n)V99}/{@code COMP-3} field maps to a {@code NUMERIC(p,2)}
 * column. An embedded H2 database does not reproduce PostgreSQL's exact
 * {@code NUMERIC} sign/scale semantics, so repository tests must run against the
 * same engine used in production. The container also lets Hibernate's
 * {@code ddl-auto: validate} check the {@code com.carddemo.entity.*} mappings
 * against the schema that Flyway actually builds.</p>
 *
 * <h2>What this base class configures</h2>
 * <ul>
 *   <li>{@link DataJpaTest @DataJpaTest} — loads only the JPA slice (entities,
 *       repositories and a {@code TestEntityManager}) and wraps <em>each test
 *       method in a transaction that is rolled back</em> at completion.</li>
 *   <li>{@link AutoConfigureTestDatabase @AutoConfigureTestDatabase}{@code (replace = NONE)}
 *       — mandatory: it stops {@code @DataJpaTest} from swapping in an embedded
 *       database, so the Testcontainers PostgreSQL datasource wired below is
 *       used instead (embedded H2 would break {@code NUMERIC} fidelity and the
 *       Flyway/{@code validate} startup).</li>
 *   <li>{@link ActiveProfiles @ActiveProfiles}{@code ("test")} — activates
 *       {@code application-test.yml} ({@code ddl-auto=validate},
 *       {@code flyway.enabled=true} over {@code classpath:db/migration},
 *       batch jobs disabled, tracing/OTLP export off). That profile deliberately
 *       omits the datasource URL/username/password; they are injected below.</li>
 *   <li>{@link Testcontainers @Testcontainers} — enables the JUnit&nbsp;5
 *       extension that manages the {@link Container @Container} lifecycle.</li>
 * </ul>
 *
 * <h2>Real Flyway migrations and seed data</h2>
 * <p>Because {@code @DataJpaTest} includes Flyway auto-configuration, the real
 * migrations run against the fresh container at context startup in order —
 * {@code V1__schema.sql} (schema) &rarr; {@code V2__indexes.sql} (indexes)
 * &rarr; {@code V3__seed_data.sql} (seed) — before Hibernate performs
 * {@code ddl-auto: validate}. The V3 seed rows are committed, so they are
 * visible to every test method.</p>
 *
 * <h2>Test isolation</h2>
 * <p>The committed Flyway seed is shared and read-only from a test's point of
 * view, while each test's own inserts/updates/deletes are rolled back at the end
 * of the wrapping transaction. Tests must therefore provision any state they
 * mutate (or rely on the committed seed) and must not depend on data written by
 * a previous test.</p>
 *
 * <h2>How to write a subclass</h2>
 * <p>Subclasses need <em>no</em> Spring or Testcontainers annotations of their
 * own — everything is inherited through extension. They simply autowire the
 * repository under test (and, optionally, a {@code TestEntityManager}) and add
 * {@code @Test} methods:</p>
 * <pre>{@code
 * class TransactionTypeRepositoryTest extends AbstractRepositoryTest {
 *
 *     @Autowired
 *     private TransactionTypeRepository repository;
 *
 *     @Autowired
 *     private TestEntityManager entityManager; // optional: setup/flush/clear
 *
 *     @Test
 *     void findsAllSeededTransactionTypes() {
 *         assertThat(repository.count()).isEqualTo(7);
 *     }
 * }
 * }</pre>
 *
 * <h2>Dynamic connection details</h2>
 * <p>The container binds a random host port, so the JDBC URL, username and
 * password are resolved from the running container at runtime via
 * {@link DynamicPropertySource} and are never hard-coded.</p>
 *
 * <p>This class is intentionally {@code abstract} and declares no {@code @Test}
 * methods, so JUnit&nbsp;5 never instantiates it and Surefire (which matches the
 * {@code *Test}/{@code *Tests} pattern) skips it as a runnable test.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractRepositoryTest {

    /**
     * The shared PostgreSQL&nbsp;16 container backing every repository slice
     * test. It is {@code static} so the {@link Testcontainers} extension starts
     * it once per test class and, when the developer has enabled reuse
     * ({@code testcontainers.reuse.enable=true} in {@code ~/.testcontainers.properties}),
     * keeps a single container alive across the concrete subclasses and across
     * runs — a pure performance optimization that never changes behaviour. If
     * reuse is not enabled the container degrades gracefully to the normal
     * per-class lifecycle.
     *
     * <p>The image tag is exactly {@code postgres:16}, matching the tag used by
     * the sibling root context test so the whole suite pulls a single image.</p>
     *
     * <p>This uses the non-generic {@code org.testcontainers.postgresql.PostgreSQLContainer}
     * introduced in Testcontainers&nbsp;2.x; the legacy generic
     * {@code org.testcontainers.containers.PostgreSQLContainer} is deprecated in
     * 2.x and referencing it would raise a {@code -Xlint:deprecation} warning.</p>
     */
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16"))
                    .withReuse(true);

    /**
     * Registers the running container's JDBC URL, username and password as
     * Spring environment properties so the auto-configured datasource points at
     * the throwaway PostgreSQL instance. Method references are used so the values
     * are read only after the container has started and its mapped port is known.
     *
     * @param registry the registry the Spring TestContext framework supplies for
     *                  contributing late-bound properties
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
