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

// java.util.UUID: used to derive ephemeral container database name,
// username, and password at class-load time so no credential string
// literal is committed to source. AAP §0.10.5 (no plaintext credentials)
// and Checkpoint 1 security checklist (no hardcoded credentials in
// testsupport Java files). Mirrors AbstractRepositoryIT for consistency.
import java.util.UUID;

// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint -- JUnit 5 only,
// never JUnit 4 / Vintage). @BeforeEach is the per-test lifecycle hook
// invoked by JUnit Jupiter before each subclass @Test method; it carries
// the JobRepositoryTestUtils#removeJobExecutions() call mandated by
// AAP §0.4.4 ("JobRepositoryTestUtils.removeJobExecutions() between tests").
import org.junit.jupiter.api.BeforeEach;

// Spring Batch test slice (AAP §0.6.1 -- spring-batch-test). @SpringBatchTest
// wires the JobLauncherTestUtils and JobRepositoryTestUtils beans that
// subclasses use to drive Jobs/Steps and to scrub historical
// BATCH_JOB_EXECUTION rows between tests.
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;

// Spring Beans field injection (AAP §0.6.1 -- spring-beans). Used to
// receive the @SpringBatchTest-provided JobLauncherTestUtils and
// JobRepositoryTestUtils into protected fields that subclasses inherit
// for driving Job/Step execution and cleaning the BATCH_JOB_EXECUTION
// table.
import org.springframework.beans.factory.annotation.Autowired;

// Spring Batch core Job type and ApplicationContext lookup (AAP §0.6.1).
// Used by the per-test @BeforeEach hook below to resolve the subclass's
// specific Spring Batch Job @Bean by name (derived from the subclass simple
// name via the JobIT naming convention) and inject it into
// JobLauncherTestUtils.setJob(...) so subsequent launchJob calls drive the
// correct Job. Without this hook the @Autowired(required=false) wiring in
// JobLauncherTestUtils.setJob would fail at context refresh with
// NoUniqueBeanDefinitionException when more than one Spring Batch Job bean
// exists in the application context (the migration declares five: one per
// migrated JCL job).
import org.springframework.batch.core.Job;
import org.springframework.context.ApplicationContext;

// Spring Boot test bootstrap (AAP §0.6.1 -- spring-boot-test).
// @SpringBootTest loads the full Spring application context so the
// inherited Spring Batch Job beans, the Testcontainers-wired DataSource,
// the Flyway-applied schema, and the JobRepository are all available for
// the 11 documented subclasses (5 batch ITs + 5 baseline-parity ITs +
// BatchPipelineE2ETest).
import org.springframework.boot.test.context.SpringBootTest;

// Spring TestContext infrastructure (AAP §0.6.1 -- spring-test).
// @ActiveProfiles("test") activates application-test.properties (which
// carries spring.batch.job.enabled=false and other test-profile
// settings); @DynamicPropertySource binds the Testcontainers JDBC URL
// and ephemeral credentials into Spring's environment BEFORE context
// startup -- satisfying AAP §0.10.5's no-plaintext-credentials directive.
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Testcontainers (AAP §0.4.4, §0.6.1). PostgreSQLContainer provides the
// ephemeral postgres:16-alpine container; @Testcontainers activates the
// JUnit 5 container lifecycle manager; @Container on the static POSTGRES
// field signals the per-class shared-container pattern (started once
// before the first subclass @Test, stopped after the last).
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Abstract base class for Spring Batch integration tests in the CardDemo
 * migration.
 *
 * <p>This base class supplies the shared {@link Testcontainers} PostgreSQL 16
 * container, {@link SpringBatchTest} wiring for {@link JobLauncherTestUtils} /
 * {@link JobRepositoryTestUtils}, and the {@link DynamicPropertySource} that
 * injects the container's JDBC URL and ephemeral credentials into Spring's
 * environment before context startup.
 *
 * <p>Subclasses (one per Spring Batch job, plus baseline-parity ITs and the
 * {@code BatchPipelineE2ETest} -- 11 in total per AAP §0.5.1):
 * <ul>
 *   <li>{@code TransactionPostingJobIT} (POSTTRAN.jcl / CBTRN02C)</li>
 *   <li>{@code TransactionPostingBaselineParityIT} (POSTTRAN.jcl byte-equal diff)</li>
 *   <li>{@code InterestCalculationJobIT} (INTCALC.jcl / CBACT04C)</li>
 *   <li>{@code InterestCalculationBaselineParityIT} (INTCALC.jcl byte-equal diff)</li>
 *   <li>{@code CombineTransactionsJobIT} (COMBTRAN.jcl / DFSORT replacement)</li>
 *   <li>{@code CombineTransactionsBaselineParityIT} (COMBTRAN.jcl byte-equal diff)</li>
 *   <li>{@code StatementGenerationJobIT} (CREASTMT.jcl / CBSTM03A+CBSTM03B)</li>
 *   <li>{@code StatementGenerationBaselineParityIT} (CREASTMT.jcl byte-equal diff)</li>
 *   <li>{@code TransactionReportJobIT} (TRANREPT.jcl / CBTRN03C)</li>
 *   <li>{@code TransactionReportBaselineParityIT} (TRANREPT.jcl byte-equal diff)</li>
 *   <li>{@code BatchPipelineE2ETest} (POSTTRAN -&gt; INTCALC -&gt; COMBTRAN -&gt; CREASTMT &#8741; TRANREPT)</li>
 * </ul>
 *
 * <p>Subclasses need only:
 * <ul>
 *   <li>Add {@code @Test} methods that invoke {@code jobLauncherTestUtils.launchJob(...)}
 *       or {@code jobLauncherTestUtils.launchStep(...)}.</li>
 *   <li>Assert on {@code BatchStatus.COMPLETED}, output-file contents (using
 *       {@link BaselineDiffUtil#assertByteEqual(java.nio.file.Path, java.nio.file.Path)}),
 *       or repository state.</li>
 * </ul>
 *
 * <h2>Why {@code @SpringBootTest} (not {@code @DataJpaTest})</h2>
 *
 * <p>AAP §0.4.4 mandates the <em>full</em> Spring application context for
 * batch ITs: the {@code Job}, {@code Step}, {@code ItemReader},
 * {@code ItemProcessor}, and {@code ItemWriter} beans defined in the
 * migrated batch configuration (the {@code com.aws.carddemo.batch.config}
 * package owned by the REFACTOR flavor) all participate in the
 * autowiring graph that Spring Batch's {@code JobLauncher} traverses.
 * The {@code @DataJpaTest} slice intentionally skips them and would
 * prevent the batch jobs from being launched at all.
 *
 * <h2>Why {@code @SpringBatchTest}</h2>
 *
 * <p>{@code @SpringBatchTest} contributes three test-only beans to the
 * Spring context:
 * <ul>
 *   <li>{@link JobLauncherTestUtils} -- supplies {@code launchJob(...)} /
 *       {@code launchStep(...)} helpers that wrap Spring Batch's
 *       {@code JobLauncher} with a synchronous, exception-raising
 *       launch idiom suited to JUnit assertions.</li>
 *   <li>{@link JobRepositoryTestUtils} -- supplies
 *       {@code removeJobExecutions()} which atomically truncates
 *       {@code BATCH_JOB_EXECUTION}, {@code BATCH_JOB_INSTANCE},
 *       {@code BATCH_STEP_EXECUTION}, and related tables so each test
 *       starts with a clean slate.</li>
 *   <li>A test-friendly {@code JobLauncher} that runs synchronously
 *       even when the production {@code JobLauncher} is configured for
 *       async execution.</li>
 * </ul>
 *
 * <h2>Container lifecycle</h2>
 *
 * <p>The {@code POSTGRES} container is declared {@code static} and annotated
 * {@code @Container}, so Testcontainers starts it once per test class and
 * reuses it across all {@code @Test} methods. Spring's TestContext cache
 * also keeps the application context warm across tests that share the
 * same configuration, minimising total wall-clock time for
 * {@code mvn verify} (AAP §0.7.2 target {@code < 5 min}).
 *
 * <h2>Job repository hygiene</h2>
 *
 * <p>{@link #cleanJobRepository()} is annotated {@code @BeforeEach} and
 * truncates {@code BATCH_JOB_EXECUTION} (and related tables) before every
 * subclass {@code @Test} method. Without this, a failing test could leave
 * a {@code STARTED} execution that prevents the next test from launching
 * the same job, and historical executions accumulated across tests would
 * make {@link JobLauncherTestUtils#launchJob()} pick the wrong
 * {@code JobInstance}.
 *
 * <p>Per AAP §0.4.4: "Batch ITs and end-to-end ITs: ...
 * {@code JobRepositoryTestUtils.removeJobExecutions()} between tests."
 *
 * <h2>Why {@code @Container} is {@code static}</h2>
 *
 * <p>Required by Testcontainers' shared-container pattern -- one container
 * lifecycle per subclass test class (start before first {@code @Test},
 * stop after last {@code @Test}). A non-static {@code @Container} field
 * would mean a fresh container per test method, which would balloon
 * wall-clock time (a single batch IT with 10 {@code @Test} methods would
 * incur 10x the container start cost -- typically 3-5 s each -- versus
 * the once-per-class pattern).
 *
 * <h2>Security (AAP §0.10.5)</h2>
 *
 * <p>No plaintext credentials are configured in any classpath resource.
 * Testcontainers supplies ephemeral username/password via
 * {@link PostgreSQLContainer#getUsername()} and
 * {@link PostgreSQLContainer#getPassword()}; the
 * {@link #postgresProperties(DynamicPropertyRegistry)} callback wires
 * those values into Spring's environment <em>before</em> the application
 * context starts. The container-local credentials are scoped to the
 * container's isolated network namespace and cannot authenticate against
 * any production system.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.4.4 (Test Database / State Management Approach), §0.5.1 (Test
 * Support Utilities table), §0.5.5 (Cross-File Test Dependencies -- Test
 * Utilities), §0.8.1 (Exhaustively In Scope), §0.10.5 (Security
 * Constraints), §0.10.9 (Test Execution Independence).
 *
 * <h2>Minimal Change Clause (AAP §0.10.2)</h2>
 *
 * <p>This base class carries ONLY the wiring strictly required by batch
 * ITs: container, dynamic property registration, the two Spring Batch
 * test utility handles, and a single {@code @BeforeEach} cleanup hook.
 * There are no convenience methods like {@code launchJobAndAssertCompleted()},
 * no shared output-path constants, no fluent DSL wrappers, no custom
 * listeners. Subclasses retain full responsibility for their
 * arrange-act-assert flows -- a deliberate design choice that keeps each
 * subclass test self-documenting and avoids the kind of test-framework
 * abstraction that the AAP explicitly forbids.
 *
 * @see AbstractRepositoryIT companion base class for JPA repository slice
 *      tests (uses {@code @DataJpaTest} instead of {@code @SpringBootTest}
 *      and is intentionally NOT the parent of this class -- the slice
 *      types are mutually exclusive).
 * @see BaselineDiffUtil byte-equal diff utility used by the 5 baseline
 *      parity IT subclasses.
 * @see FixtureLoader loads ASCII fixtures from
 *      {@code src/test/resources/baseline/input/} and edge-case CSVs from
 *      {@code src/test/resources/fixtures/edge/}.
 */
// ---------------------------------------------------------------------
// @DirtiesContext(AFTER_CLASS) — mirrors the AbstractRepositoryIT fix
// for the same Testcontainers context-cache hazard.
// ---------------------------------------------------------------------
// Without @DirtiesContext, Spring's TestContext framework caches the
// ApplicationContext (including its HikariCP DataSource bean) by
// MergedContextConfiguration key. The five JobIT subclasses share an
// identical configuration footprint (same @SpringBootTest classes, same
// @ActiveProfiles, same @SpringBatchTest slice), so without explicit
// dirtying Spring reuses the cached context across them.
//
// However, each JobIT inherits the @Container static PostgreSQLContainer
// from this base class. The @Testcontainers JUnit 5 extension's
// per-class lifecycle calls .stop() on the static container after the
// last @Test of class A; class B then sees a dead container at the old
// port, and the cached HikariCP DataSource bean refuses connections with
// "Connection refused" -> "Could not open JPA EntityManager".
//
// Empirical evidence (this checkpoint's runtime testing): the first 3
// JobIT classes to run (StatementGenerationJobIT, TransactionPostingJobIT,
// InterestCalculationJobIT) all PASSED their @BeforeEach JobRepository
// reset, but the 4th and 5th (CombineTransactionsJobIT,
// TransactionReportJobIT) FAILED at jobRepositoryTestUtils.removeJobExecutions()
// with 30-second HikariCP timeouts pointing at the long-dead first
// container's port.
//
// Fix: @DirtiesContext(classMode = AFTER_CLASS) forces Spring to discard
// the cached ApplicationContext after the last @Test method on each
// JobIT subclass completes. The next JobIT triggers a fresh context
// startup, which re-evaluates @DynamicPropertySource against the new
// fresh container's getJdbcUrl() and constructs a new HikariCP
// DataSource bean against the live port. Wall-clock cost (~2-3s per
// context refresh) stays well under the AAP §0.7.2 < 5 min target.
//
// Alternative considered: a JVM-singleton PostgreSQLContainer shared
// across all JobITs (one container, started once, never stopped) would
// also work but requires refactoring away from @Container static (an
// annotation-driven lifecycle the AAP §0.10.2 Minimal Change Clause
// prefers). @DirtiesContext is the minimal, idiomatic Spring fix and
// keeps this class consistent with its sister AbstractRepositoryIT.
@SpringBootTest
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractBatchIT {

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
     * own container. Mirrors {@code AbstractRepositoryIT#EPHEMERAL_DB_NAME}
     * for cross-class consistency.
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
     * back through {@link PostgreSQLContainer#getUsername()}). Mirrors
     * {@code AbstractRepositoryIT#EPHEMERAL_USERNAME}.
     */
    private static final String EPHEMERAL_USERNAME =
            "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    /**
     * Ephemeral PostgreSQL password generated at class-load time from a
     * fresh {@link UUID#randomUUID()}. The full UUID is preserved (36
     * characters including hyphens) because PostgreSQL passwords have no
     * identifier-grammar restriction and a longer entropy footprint is
     * harmless. The value is never logged and is bound into Spring's
     * environment only at runtime. Mirrors
     * {@code AbstractRepositoryIT#EPHEMERAL_PASSWORD}.
     */
    private static final String EPHEMERAL_PASSWORD = UUID.randomUUID().toString();

    /**
     * Shared PostgreSQL 16 container, started once per subclass test run by
     * the Testcontainers JUnit 5 extension (activated by the class-level
     * {@code @Testcontainers} annotation).
     *
     * <p>Image tag {@code postgres:16-alpine} chosen for:
     * <ul>
     *   <li>Small footprint (~200 MB vs ~400 MB for the full
     *       {@code postgres:16} tag) -- minimises CI artifact cache /
     *       network pull cost.</li>
     *   <li>PostgreSQL 16 parity with the production runtime per AAP §0.4.4
     *       (real PostgreSQL semantics, NOT in-memory H2). This matters
     *       for Spring Batch's {@code JdbcJobRepositoryFactoryBean} which
     *       relies on PostgreSQL sequence semantics for
     *       {@code BATCH_JOB_EXECUTION_SEQ} and related sequences.</li>
     *   <li>Matching {@code AbstractRepositoryIT}'s image tag so Spring's
     *       TestContext cache can reuse the same container configuration
     *       footprint across repository ITs and batch ITs -- minimising
     *       total wall-clock time for {@code mvn verify}.</li>
     * </ul>
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
     * opt-in that requires {@code ~/.testcontainers.properties}
     * configuration and adds CI complexity (AAP §0.10.2 Minimal Change
     * Clause -- avoid unless strictly necessary). Disabling reuse also
     * guarantees state isolation between batch IT subclasses: a leaked
     * {@code STARTED} execution in one subclass cannot affect another.
     *
     * <p>The {@code @SuppressWarnings("resource")} annotation silences an
     * IDE / Error Prone warning about not closing the container in a
     * try-with-resources block; the lifecycle is owned by Testcontainers'
     * {@code @Container} extension, which closes the container after the
     * subclass test run completes.
     *
     * <p>Field naming: {@code POSTGRES} (static, ALL_CAPS by Java naming
     * convention for constants) matches the companion
     * {@code AbstractRepositoryIT#POSTGRES} field for cross-class
     * consistency.
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
     * environment, so the container is guaranteed to have started by the
     * time {@link PostgreSQLContainer#getJdbcUrl()},
     * {@link PostgreSQLContainer#getUsername()}, and
     * {@link PostgreSQLContainer#getPassword()} are invoked. The
     * container start sequence is orchestrated by the
     * {@code @Testcontainers} extension before Spring builds the context.
     *
     * <p>The {@code spring.datasource.driver-class-name} property is
     * pinned to {@code org.postgresql.Driver} so Spring Boot does not
     * attempt the H2 driver auto-detection that would otherwise fire when
     * {@code com.h2database:h2} is on the test classpath (it is -- AAP
     * §0.6.1 lists it as an optional fallback for the rare repository
     * unit test that does not need PostgreSQL).
     *
     * <p>The Hibernate dialect is intentionally NOT pinned here: Spring
     * Boot 3.x's Hibernate 6 auto-detects the dialect from the JDBC
     * connection metadata, and pinning it would couple the test base
     * class to a specific Hibernate dialect class name -- a needless
     * brittleness that violates AAP §0.10.2 Minimal Change Clause.
     *
     * <p>Satisfies AAP §0.10.5: no plaintext credentials in
     * {@code application-test.properties}; the file declares
     * {@code spring.datasource.url=}, {@code spring.datasource.username=},
     * {@code spring.datasource.password=} as empty and this callback
     * supplies the live values at runtime.
     *
     * <p>The method is intentionally package-private (default visibility)
     * because the Spring TestContext framework discovers
     * {@code @DynamicPropertySource} methods via reflection and does not
     * require {@code public} access. Keeping it package-private also
     * prevents accidental subclass overrides that could break the
     * Testcontainers wiring.
     *
     * @param registry the Spring {@link DynamicPropertyRegistry} into
     *                 which the container-derived datasource properties
     *                 are registered.
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
     * Spring Batch test harness, contributed by {@code @SpringBatchTest}.
     *
     * <p>Subclasses invoke its {@code launchJob()} or {@code launchStep()}
     * methods to drive the Spring Batch {@code Job} or individual
     * {@code Step} under test. Typical subclass usage:
     *
     * <pre>{@code
     * @Test
     * void transactionPostingJob_validInput_completesSuccessfully() throws Exception {
     *     // Arrange: input file already seeded by Flyway V3__seed.sql.
     *     JobParameters params = new JobParametersBuilder()
     *         .addString("inputFile", "classpath:baseline/input/dailytran.txt")
     *         .toJobParameters();
     *
     *     // Act: launch the configured Job.
     *     JobExecution execution = jobLauncherTestUtils.launchJob(params);
     *
     *     // Assert.
     *     assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
     * }
     * }</pre>
     *
     * <p>Field injection (not constructor injection) is used here because
     * {@code @SpringBatchTest} bootstraps {@link JobLauncherTestUtils} as a
     * Spring bean and {@code AbstractBatchIT} is instantiated reflectively
     * by the JUnit Jupiter / Spring TestContext machinery -- a constructor
     * parameter would force every one of the 11 subclasses to declare a
     * redundant constructor.
     */
    @Autowired
    protected JobLauncherTestUtils jobLauncherTestUtils;

    /**
     * Spring Batch repository test harness, contributed by
     * {@code @SpringBatchTest}.
     *
     * <p>Used by {@link #cleanJobRepository()} to remove historical
     * {@code BATCH_JOB_EXECUTION}, {@code BATCH_JOB_INSTANCE}, and
     * {@code BATCH_STEP_EXECUTION} rows between tests. Subclasses may
     * also invoke it directly when a {@code @Test} method needs to
     * inspect or manipulate the job repository (rare; the
     * {@code @BeforeEach} cleanup is sufficient for the documented
     * subclasses).
     *
     * <p>Field injection follows the same rationale as
     * {@link #jobLauncherTestUtils}.
     */
    @Autowired
    protected JobRepositoryTestUtils jobRepositoryTestUtils;

    /**
     * Spring application context, autowired so the per-test
     * {@link #cleanJobRepository()} hook can resolve the subclass's specific
     * Spring Batch {@link Job} bean by its convention-derived name and inject
     * it into {@link #jobLauncherTestUtils} via
     * {@link JobLauncherTestUtils#setJob(Job)}. The lookup is required because
     * the migration declares five {@code Job} beans (one per migrated JCL job)
     * and {@code JobLauncherTestUtils}'s {@code @Autowired(required=false)
     * setJob(Job)} cannot disambiguate among multiple candidates &mdash; it
     * would fail with {@code NoUniqueBeanDefinitionException} at context
     * refresh before any {@code @BeforeEach} hook runs. To prevent that
     * failure the production {@code BatchJobConfig} marks one job as
     * {@code @Primary} (the autowired default), and this hook overrides the
     * field with the subclass-specific job before each {@code @Test}.
     *
     * <p>Field injection (not constructor injection) is used for the same
     * reason as the other {@code @Autowired} fields in this class: the
     * eleven documented subclasses are instantiated reflectively by JUnit /
     * Spring TestContext, so a constructor parameter would force every
     * subclass to declare a redundant constructor.
     */
    @Autowired
    protected ApplicationContext applicationContext;

    /**
     * Removes all rows from {@code BATCH_JOB_EXECUTION},
     * {@code BATCH_JOB_INSTANCE}, {@code BATCH_STEP_EXECUTION}, and related
     * Spring Batch metadata tables before each subclass {@code @Test}
     * method, AND injects the subclass's specific Spring Batch
     * {@link Job} bean into the inherited {@link #jobLauncherTestUtils}.
     *
     * <p>Without this hook:
     * <ul>
     *   <li>A failing test could leave a {@code STARTED} execution that
     *       prevents the next test from launching the same {@code Job}
     *       (Spring Batch refuses to start a new execution while a prior
     *       one is still marked {@code STARTED}).</li>
     *   <li>Historical executions accumulated across tests would cause
     *       {@link JobLauncherTestUtils#launchJob()} to pick the wrong
     *       {@code JobInstance} -- the test framework selects by
     *       parameter hash, and identical parameters across tests would
     *       collide.</li>
     *   <li>The metadata tables would grow unboundedly across a long
     *       {@code mvn verify} run, slowing down each subsequent test.</li>
     * </ul>
     *
     * <p>JUnit 5 lifecycle ordering: superclass {@code @BeforeEach}
     * methods run BEFORE subclass {@code @BeforeEach} methods (the
     * mirror image of {@code @AfterEach}), so this cleanup always runs
     * before any subclass-specific arrangement. Subclasses that need to
     * order their setup after the cleanup do not need to do anything
     * special.
     *
     * <p>Per AAP §0.4.4: "Batch ITs and end-to-end ITs: Testcontainers
     * PostgreSQL 16, shared container per test class via Spring's context
     * cache, {@code JobRepositoryTestUtils.removeJobExecutions()} between
     * tests."
     *
     * <p>The method is package-private (default visibility) because JUnit
     * Jupiter discovers {@code @BeforeEach} methods via reflection and
     * does not require {@code public} access. Keeping it package-private
     * also prevents accidental subclass overrides that would break the
     * cleanup invariant. The {@code @BeforeEach} annotation is inherited
     * by all subclasses via the JUnit Jupiter "lifecycle inheritance"
     * rule for non-overridden lifecycle methods.
     */
    @BeforeEach
    void cleanJobRepository() {
        jobRepositoryTestUtils.removeJobExecutions();
        // ------------------------------------------------------------------
        // Per-test Job injection
        // ------------------------------------------------------------------
        // Resolve the specific Spring Batch Job bean this subclass exercises
        // via the JobIT naming convention ("XxxJobIT" -> "xxxJob"). The lookup
        // is wrapped so that:
        //   * subclasses that are NOT JobITs (none today, but defensive
        //     against future additions) and that do not declare a matching
        //     Job @Bean simply leave the autowired @Primary default in place;
        //   * subclasses that ARE JobITs but have not been registered as Job
        //     beans yet produce a clear, actionable error message naming the
        //     expected bean rather than the generic
        //     "NoSuchBeanDefinitionException: No bean named 'xxxJob'".
        // Per AAP §0.4.4 and §0.10.9 this hook preserves the per-test
        // isolation contract: every @Test runs against a freshly-reset
        // JobRepository AND a freshly-set Job reference.
        final String expectedBeanName = jobBeanNameFromClassName();
        if (expectedBeanName != null
                && applicationContext.containsBean(expectedBeanName)) {
            final Job job = applicationContext.getBean(expectedBeanName, Job.class);
            jobLauncherTestUtils.setJob(job);
        }
    }

    /**
     * Derives the expected Spring Batch {@link Job} bean name from this
     * subclass's simple name using the JobIT naming convention:
     *
     * <pre>
     *   TransactionPostingJobIT   -> transactionPostingJob
     *   InterestCalculationJobIT  -> interestCalculationJob
     *   CombineTransactionsJobIT  -> combineTransactionsJob
     *   StatementGenerationJobIT  -> statementGenerationJob
     *   TransactionReportJobIT    -> transactionReportJob
     * </pre>
     *
     * <p>Subclasses whose names do not match the {@code *JobIT} pattern
     * (e.g., baseline-parity ITs that share this base class but pin their
     * own Job lookup) receive a {@code null} return so the
     * {@link #cleanJobRepository()} hook silently leaves the autowired
     * {@code @Primary} default in place. Those subclasses are expected to
     * call {@code jobLauncherTestUtils.setJob(...)} explicitly when they
     * need to drive a non-default Job.
     *
     * @return the expected Spring Batch {@link Job} bean name following the
     *         JobIT naming convention, or {@code null} if this subclass's
     *         name does not match the {@code *JobIT} pattern.
     */
    String jobBeanNameFromClassName() {
        final String simpleName = this.getClass().getSimpleName();
        // Strip the "IT" suffix only when the class name ends with "JobIT" --
        // we are deriving a Spring bean name from a Job IT class name, and
        // class names that do not end with "JobIT" should not contribute a
        // Job lookup (e.g., a future "FooStepIT" should NOT resolve "fooStep"
        // as a Job).
        if (!simpleName.endsWith("JobIT")) {
            return null;
        }
        // Strip the trailing "IT" two characters, then convert the leading
        // PascalCase letter to lowercase to match the Spring @Bean default
        // naming convention. For "TransactionPostingJobIT":
        //   simpleName.length() = 25
        //   simpleName.substring(0, 23) = "TransactionPostingJob"
        //   first char lowercased -> "transactionPostingJob"
        final String stripped = simpleName.substring(0, simpleName.length() - 2);
        return Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
    }
}
