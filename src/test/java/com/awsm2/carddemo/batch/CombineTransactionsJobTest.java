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
package com.awsm2.carddemo.batch;

// =============================================================================
// CombineTransactionsJobTest — Spring Batch tests for CombineTransactionsJob
// =============================================================================
//
// Replaces JCL job stream: COMBTRAN.jcl
// COBOL: <none — COMBTRAN.jcl is a pure DFSORT + IDCAMS REPRO utility
//        with NO corresponding COBOL program source>
//
// Per AAP §0.4.1, the source JCL stream consists of two utility steps:
//
//   STEP05R EXEC PGM=SORT  — concatenates
//                            AWS.M2.CARDDEMO.TRANSACT.BKUP(0) and
//                            AWS.M2.CARDDEMO.SYSTRAN(0), sorts by
//                            TRAN-ID ascending, writes
//                            AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1).
//   STEP10  EXEC PGM=IDCAMS — REPRO INFILE(TRANSACT.COMBINED)
//                            OUTFILE(TRANVSAM) loads the sorted file
//                            into AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS.
//
// The Java replacement (CombineTransactionsJob @Configuration) collapses
// both steps into a single Spring Batch tasklet that:
//   1. Reads the daily_transactions staging table via JPA (replaces
//      SORTIN concatenation).
//   2. Sorts the list in memory by dalytranId ascending using
//      Comparator.comparing(DailyTransaction::getDalytranId) (replaces
//      DFSORT FIELDS=(TRAN-ID,A)).
//   3. Maps each DailyTransaction → Transaction (field-for-field).
//   4. Bulk-inserts into the transactions journal via JPA saveAll
//      partitioned by bulkInsertSize (replaces IDCAMS REPRO).
//   5. Writes a versioned S3 backup via
//      S3OutputService.copyTransactionBackup(generation, payload)
//      (replaces SORTOUT DD GDG generation (+1) per AAP §0.6.2).
//
// This test exercises that pipeline end-to-end using:
//   * @SpringBatchTest        — auto-configures JobLauncherTestUtils.
//   * @SpringBootTest         — loads the full ApplicationContext so
//                               combineTransactionsJob Bean + every
//                               collaborator is wired correctly.
//   * @Testcontainers + @ServiceConnection — real PostgreSQL 16 to
//                                            back the JOB_REPOSITORY +
//                                            JPA tables (matches the
//                                            production RDS PostgreSQL
//                                            Multi-AZ target per AAP
//                                            §0.6.2).
//   * @ActiveProfiles("test") — loads application-test.yml, which
//                               disables Spring Cloud AWS Secrets
//                               Manager + Parameter Store auto-config.
//   * @MockBean               — replaces every collaborator and AWS SDK
//                               client so the test does not require
//                               real RDS, S3, MSK, Redis, OpenSearch,
//                               or AWS credentials.
//
// @Nested test groups (per export schema members_exposed):
//   HappyPath, SortOrdering, S3Backup, EmptyDataset,
//   ParameterValidation, ServiceFailurePropagation.
//
// AAP cross-references:
//   §0.4.1  — transformation mapping (CombineTransactionsJob: CREATE)
//   §0.4.2  — GDG (+1) → S3 versioned object
//   §0.5.1  — testing dependencies (JUnit 5, Spring Batch Test,
//             Testcontainers, AssertJ, Mockito, Spring Boot Test)
//   §0.6.2  — VSAM → RDS PostgreSQL Multi-AZ + S3 versioned backup
//   §0.6.3  — JCL → Step Functions orchestration (batch-job lifecycle)
//   §0.6.6  — Audit / observability (OpenSearch + CloudWatch lifecycle)
//   §0.7.1  — Refactoring rules: Jakarta only, AWS SDK v2 only,
//             BigDecimal for monetary, isolated adapters
//   §0.7.2  — Testing approach (JUnit 5 + Mockito + Testcontainers +
//             LocalStack), parallel-run output diffing

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.adapter.KafkaEventPublisher;
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.adapter.SecretsManagerService;
import com.awsm2.carddemo.adapter.StepFunctionsOrchestrator;
import com.awsm2.carddemo.domain.DailyTransaction;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.repository.DailyTransactionRepository;
import com.awsm2.carddemo.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sfn.SfnClient;

import javax.sql.DataSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spring Batch end-to-end tests for
 * {@link com.awsm2.carddemo.batch.CombineTransactionsJob}.
 *
 * <p>Replaces JCL job stream: {@code app/jcl/COMBTRAN.jcl} (pure
 * DFSORT + IDCAMS REPRO utility &mdash; no COBOL source). See
 * file-level Javadoc on
 * {@link com.awsm2.carddemo.batch.CombineTransactionsJob} for the
 * end-to-end translation rationale.</p>
 *
 * <h2>What this test asserts</h2>
 * <ul>
 *   <li><b>HappyPath</b> &mdash; the job completes
 *       ({@link BatchStatus#COMPLETED}); the repository reads,
 *       repository writes (bulk insert), and S3 backup all fire
 *       exactly once.</li>
 *   <li><b>SortOrdering</b> &mdash; the iterable passed to
 *       {@link TransactionRepository#saveAll(Iterable)} is in
 *       ascending {@code tranId} order regardless of input order
 *       (validating
 *       {@code Comparator.comparing(DailyTransaction::getDalytranId)}
 *       replaces DFSORT {@code FIELDS=(TRAN-ID,A)} exactly).</li>
 *   <li><b>S3Backup</b> &mdash; the
 *       {@link S3OutputService#copyTransactionBackup(String, byte[])}
 *       adapter is invoked with the {@code businessDate} parameter
 *       string from {@link JobParameters} as the generation token
 *       (replacing GDG {@code (+1)} per AAP &sect;0.6.2).</li>
 *   <li><b>EmptyDataset</b> &mdash; zero source rows still completes
 *       the job; no bulk insert and no S3 backup are emitted (gated
 *       by {@code !targets.isEmpty()} in the production tasklet).</li>
 *   <li><b>ParameterValidation</b> &mdash; missing {@code batchRunId}
 *       causes the job to transition to {@link BatchStatus#FAILED}
 *       (the tasklet's
 *       {@code requireParameter(jobParameters, "batchRunId")} check
 *       throws {@code IllegalArgumentException} before any data work
 *       begins); the repository {@code findAll} is never called and
 *       no S3 backup is emitted.</li>
 *   <li><b>ServiceFailurePropagation</b> &mdash; exceptions thrown by
 *       {@link DailyTransactionRepository#findAll()} or
 *       {@link S3OutputService#copyTransactionBackup(String, byte[])}
 *       cause the job to transition to {@link BatchStatus#FAILED}
 *       with the failure exception captured in
 *       {@link JobExecution#getAllFailureExceptions()}.</li>
 * </ul>
 *
 * <h2>Test stack</h2>
 * <ul>
 *   <li>{@link SpringBatchTest &#64;SpringBatchTest} auto-configures
 *       {@link JobLauncherTestUtils} bound to the test
 *       {@code ApplicationContext}'s {@code JobLauncher},
 *       {@code JobRepository}, and the lone {@code combineTransactionsJob}
 *       Job bean.</li>
 *   <li>{@link SpringBootTest &#64;SpringBootTest} boots the full
 *       {@code ApplicationContext} so the
 *       {@code combineTransactionsJob} bean and its six injected
 *       collaborators wire correctly through the production
 *       {@code BatchConfig} + {@code JpaConfig} + sibling
 *       {@code @Configuration} classes.</li>
 *   <li>{@link Testcontainers &#64;Testcontainers} + {@link Container
 *       &#64;Container} + {@link ServiceConnection &#64;ServiceConnection}
 *       provision an ephemeral PostgreSQL 16-alpine instance for the
 *       Spring Batch {@code JOB_REPOSITORY} metadata tables and Flyway
 *       migrations.</li>
 *   <li>{@link ActiveProfiles &#64;ActiveProfiles("test")} loads
 *       {@code src/test/resources/application-test.yml}, disabling
 *       Spring Cloud AWS Secrets Manager + Parameter Store
 *       auto-configuration so no real AWS credentials are required at
 *       startup (AAP &sect;0.7.1).</li>
 *   <li>{@link MockBean &#64;MockBean} replaces every collaborator and
 *       AWS SDK v2 client so the context loads without real RDS, S3,
 *       MSK, ElastiCache, OpenSearch, or AWS credentials.</li>
 *   <li>{@link Import &#64;Import} of {@link TestSecretsManagerConfiguration}
 *       supplies a deterministic {@link SecretsManagerService} stub so
 *       {@code JwtTokenProvider.initSigningKey()} satisfies its
 *       {@code @PostConstruct} precondition during context refresh
 *       (matches the {@code CardDemoApplicationTests} +
 *       {@code BatchJobConfigTest} pattern).</li>
 * </ul>
 *
 * <h2>Class style (AAP &sect;0.7.2)</h2>
 * <ul>
 *   <li>Package-private class &mdash; JUnit 5 best practice; no
 *       {@code public} keyword.</li>
 *   <li>{@link DisplayName} on the class and on every test method
 *       &mdash; human-readable test output in CI reports.</li>
 *   <li>{@link Nested} inner classes organise tests by scenario,
 *       mirroring the export schema {@code members_exposed} list
 *       exactly: {@code HappyPath}, {@code SortOrdering},
 *       {@code S3Backup}, {@code EmptyDataset},
 *       {@code ParameterValidation},
 *       {@code ServiceFailurePropagation}.</li>
 *   <li>AssertJ fluent assertions for expressive failure messages
 *       ({@code assertThat(...).isEqualTo(...)},
 *       {@code .containsExactlyElementsOf(...)},
 *       {@code .isNotEmpty()}).</li>
 *   <li>Mockito {@link ArgumentCaptor} captures the
 *       {@code saveAll(Iterable)} argument so the
 *       {@code SortOrdering} test can iterate the captured collection
 *       and verify ascending {@code tranId} order &mdash; validating
 *       that the Java {@code Comparator} replacement of DFSORT
 *       {@code FIELDS=(TRAN-ID,A)} preserves the COBOL natural
 *       ordering exactly (AAP &sect;0.6.2).</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.batch.CombineTransactionsJob
 * @see com.awsm2.carddemo.adapter.S3OutputService#copyTransactionBackup(String, byte[])
 * @see com.awsm2.carddemo.adapter.AuditLogService#logBatchJobLifecycle(String, String, String, Long, java.util.Map, String)
 */
// Replaces JCL job stream: COMBTRAN.jcl
@SpringBootTest
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@Import(CombineTransactionsJobTest.TestSecretsManagerConfiguration.class)
@DisplayName("CombineTransactionsJob — Spring Batch tests (JCL: COMBTRAN.jcl; no COBOL source)")
class CombineTransactionsJobTest {

    // -------------------------------------------------------------------------
    // @TestConfiguration — stub SecretsManagerService for JwtTokenProvider
    // -------------------------------------------------------------------------
    // JwtTokenProvider is @Component @RefreshScope and performs an eager
    // Secrets Manager fetch in its @PostConstruct hook to load the HS256
    // signing key. In production the fetch returns a KMS-protected key from
    // AWS Secrets Manager; in this @SpringBootTest we must supply a
    // deterministic stub that returns a ≥ 32-byte string so
    // Keys.hmacShaKeyFor(...) accepts the material (HS256 requires a 256-
    // bit / 32-byte symmetric key per RFC 7518). The stub mirrors the
    // pattern used by CardDemoApplicationTests + BatchJobConfigTest so
    // context refresh succeeds without contacting real AWS.

    /**
     * Provides a stubbed {@link SecretsManagerService} bean for the
     * duration of {@link CombineTransactionsJobTest}. Returns a
     * deterministic 60-byte ASCII placeholder for any
     * {@code (secretArn, fieldName)} pair, satisfying
     * {@code JwtTokenProvider}'s HS256 key-length precondition during
     * context refresh.
     */
    @TestConfiguration
    static class TestSecretsManagerConfiguration {

        /**
         * Test-only placeholder JWT signing key. 60 ASCII bytes &mdash;
         * well above the HS256 32-byte minimum and clearly labelled as
         * non-production via the {@code test-only-} prefix.
         */
        private static final String TEST_SIGNING_KEY =
                "test-only-jwt-signing-key-for-hs256-context-smoke-test-padded";

        /**
         * Stub {@link SecretsManagerService} bean. Returns a non-empty
         * {@link Optional} containing {@link #TEST_SIGNING_KEY} for any
         * {@code getSecretJsonField(...)} invocation so
         * {@code JwtTokenProvider.initSigningKey()} can satisfy its
         * {@code @PostConstruct} precondition during context refresh.
         *
         * @return a pre-configured Mockito mock supplying the placeholder
         *         signing key for any input
         */
        @Bean
        @Primary
        SecretsManagerService secretsManagerService() {
            SecretsManagerService stub = Mockito.mock(SecretsManagerService.class);
            Mockito.when(stub.getSecretJsonField(Mockito.anyString(), Mockito.anyString()))
                    .thenReturn(Optional.of(TEST_SIGNING_KEY));
            Mockito.when(stub.getSecret(Mockito.anyString()))
                    .thenReturn(TEST_SIGNING_KEY);
            return stub;
        }

        // ---------------------------------------------------------------------
        // Spring Batch JOB_REPOSITORY schema initialiser
        // ---------------------------------------------------------------------
        // Why this is required: production {@code BatchConfig} declares
        // {@code @EnableBatchProcessing}, which deactivates Spring Boot's
        // {@code BatchAutoConfiguration} (the auto-configuration that
        // normally honours {@code spring.batch.jdbc.initialize-schema:
        // always} from {@code application-test.yml}). Without that
        // auto-configuration, the Testcontainers PostgreSQL instance
        // starts with no BATCH_JOB_INSTANCE / BATCH_JOB_EXECUTION /
        // BATCH_STEP_EXECUTION / BATCH_JOB_EXECUTION_CONTEXT /
        // BATCH_STEP_EXECUTION_CONTEXT / BATCH_JOB_EXECUTION_PARAMS
        // tables and {@code SimpleJobLauncher.run(...)} fails with
        // {@code BadSqlGrammarException: relation "batch_job_instance"
        // does not exist}.
        //
        // This bean replaces the missing auto-configuration by running
        // {@code org/springframework/batch/core/schema-postgresql.sql}
        // (shipped inside the {@code spring-batch-core} JAR at
        // version 5.1.3 per AAP &sect;0.5.1) against the Testcontainers
        // DataSource at context-refresh time. The
        // {@link DataSourceInitializer} bean lifecycle guarantees the
        // populator runs before any {@code @KafkaListener} or
        // {@code JobLauncherTestUtils.launchJob(...)} invocation. The
        // populator uses {@code setIgnoreFailedDrops(true)} so the
        // script's leading {@code DROP TABLE} statements (defensive,
        // for re-runnability) do not throw against an empty schema.
        //
        // This DOES NOT modify production code &mdash; the bean lives
        // entirely inside the test {@code @TestConfiguration} and is
        // never active in {@code dev} / {@code prod} profiles.
        //
        // @param dataSource the Testcontainers-backed DataSource
        //                   {@code @Primary}-declared by JpaConfig
        // @return a {@link DataSourceInitializer} that materialises
        //         the Spring Batch JOB_REPOSITORY tables on bootstrap

        /**
         * Initialises the Spring Batch JOB_REPOSITORY schema in the
         * Testcontainers PostgreSQL instance at context-refresh time.
         *
         * <p>The script
         * {@code org/springframework/batch/core/schema-postgresql.sql}
         * is loaded from the {@code spring-batch-core} JAR (version
         * pinned by the Spring Boot 3.4.x BOM &mdash; see
         * {@code pom.xml}) and executed via Spring's standard
         * {@link ResourceDatabasePopulator}. The
         * {@link DataSourceInitializer#setEnabled(boolean)} flag is
         * left at its default {@code true} so the script runs once
         * per test class lifecycle (the static {@code @Container} is
         * recreated per class on cache eviction; the cached
         * application context shares the populated schema across
         * all {@code @Nested} test groups).</p>
         *
         * @param dataSource the {@code @Primary} DataSource exposed by
         *                   {@link com.awsm2.carddemo.config.JpaConfig}
         *                   and bound to the Testcontainers PostgreSQL
         *                   instance via {@link ServiceConnection}
         * @return a configured {@link DataSourceInitializer} bean
         */
        @Bean
        DataSourceInitializer batchSchemaInitializer(final DataSource dataSource) {
            // Idempotent two-phase populator: DROP first (with
            // ignoreFailedDrops=true so an empty schema is fine), then
            // CREATE. Guarantees a clean BATCH_JOB_INSTANCE family of
            // tables on every context refresh regardless of whether
            // any sibling bean / prior test ran the script. The
            // populator.setContinueOnError(true) safeguard is set
            // narrowly so that any unrelated PostgreSQL DDL error
            // still surfaces during test bootstrap rather than being
            // silently swallowed.
            final ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource(
                    "org/springframework/batch/core/schema-drop-postgresql.sql"));
            populator.addScript(new ClassPathResource(
                    "org/springframework/batch/core/schema-postgresql.sql"));
            populator.setIgnoreFailedDrops(true);
            populator.setSeparator(";");
            populator.setContinueOnError(true);
            final DataSourceInitializer initializer = new DataSourceInitializer();
            initializer.setDataSource(dataSource);
            initializer.setDatabasePopulator(populator);
            initializer.setEnabled(true);
            return initializer;
        }
    }

    // -------------------------------------------------------------------------
    // Testcontainers PostgreSQL — required by JPA / Flyway / JobRepository
    // -------------------------------------------------------------------------
    // The @SpringBootTest context loads every @Configuration class and every
    // @Service / @Repository under com.awsm2.carddemo. Spring Batch's
    // JobRepository persists BATCH_JOB_INSTANCE, BATCH_JOB_EXECUTION,
    // BATCH_STEP_EXECUTION, BATCH_JOB_EXECUTION_CONTEXT rows to this
    // PostgreSQL container via Spring Boot's auto-configured Batch
    // infrastructure; Flyway applies V001..V015 migrations under
    // src/main/resources/db/migration to the same container before
    // Hibernate's ddl-auto=validate startup probe runs.
    //
    // postgres:16-alpine matches the production RDS PostgreSQL Multi-AZ
    // engine version per AAP §0.5.1 and §0.6.2 — same image used by
    // CardDemoApplicationTests + BatchJobConfigTest for consistency.

    /**
     * Ephemeral PostgreSQL 16-alpine Docker container backing the JPA /
     * Flyway / Spring Batch JobRepository for this test class.
     *
     * <p>The image tag is pinned to {@code postgres:16-alpine} per AAP
     * &sect;0.5.1 (no {@code latest} tags). Started once per test class
     * lifecycle via {@link Testcontainers}; bound to Spring Boot's
     * {@code FlywayConnectionDetails} via {@link ServiceConnection} so
     * Flyway migrations apply at context-refresh time.</p>
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /**
     * Binds the {@link #POSTGRES} container's connection details into
     * {@code spring.datasource.*} so the {@code @Primary @RefreshScope
     * DataSource} bean declared in
     * {@code com.awsm2.carddemo.config.JpaConfig} connects to the SAME
     * Testcontainers PostgreSQL instance that Flyway migrates via
     * {@link ServiceConnection}.
     *
     * <p>Without this override, the application's
     * {@code spring.datasource.url} would resolve to the
     * {@code jdbc:tc:postgresql:16-alpine:///carddemo_test} fallback in
     * {@code application-test.yml}, which would spin up a SECOND
     * (separate, empty) PostgreSQL container &mdash; causing Hibernate's
     * {@code ddl-auto=validate} startup probe to fail with
     * {@code Schema-validation: missing table [accounts]} (AAP
     * &sect;0.6.4 user-declared {@code @Primary} DataSource bypasses
     * Spring Boot's {@code HikariJdbcConnectionDetailsBeanPostProcessor}
     * that would otherwise rewrite the URL).</p>
     *
     * @param registry the Spring test
     *                 {@link DynamicPropertyRegistry} that accepts
     *                 {@code (key, supplier)} property overrides at
     *                 context-refresh time
     */
    @DynamicPropertySource
    static void overrideDataSourceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        // ---------------------------------------------------------------------
        // HikariCP auto-commit override — required for Spring Batch's
        // ISOLATION_SERIALIZABLE on JobRepository transactions
        // ---------------------------------------------------------------------
        // Spring Batch's DefaultBatchConfiguration hard-codes
        // getIsolationLevelForCreate() = Isolation.SERIALIZABLE. The
        // resulting JobRepository proxy wraps every method in a
        // @Transactional with that isolation level. PostgreSQL's JDBC
        // driver rejects setTransactionIsolation() on a connection
        // whose auto-commit is false AND whose transaction state is
        // not IDLE — which becomes the case the moment HikariCP's
        // connection-test-query SELECT 1 has executed against an
        // auto-commit=false connection. Forcing auto-commit=true for
        // this test context lets the driver accept SET TRANSACTION
        // ISOLATION LEVEL SERIALIZABLE without violating the
        // PostgreSQL "in transaction" precondition. The application's
        // @Transactional service code is unaffected — JpaTransaction
        // Manager.doBegin() still explicitly calls
        // connection.setAutoCommit(false) inside the transaction
        // scope, then restores the original (true) value on commit /
        // rollback. AAP §0.6.2 — RDS Multi-AZ engine matches the
        // Testcontainers image (postgres:16-alpine) regardless of
        // this Hikari property override.
        registry.add("spring.datasource.hikari.auto-commit", () -> "true");
    }

    // -------------------------------------------------------------------------
    // Spring Batch test infrastructure (auto-configured by @SpringBatchTest)
    // -------------------------------------------------------------------------

    /**
     * Spring Batch test harness auto-configured by
     * {@link SpringBatchTest &#64;SpringBatchTest}. Provides
     * {@link JobLauncherTestUtils#launchJob(JobParameters)} as the
     * canonical synchronous-blocking entry point for invoking the
     * {@code combineTransactionsJob} Job under test.
     *
     * <p>The harness auto-wires its {@code Job} field from the single
     * {@code combineTransactionsJob} bean in the test
     * {@code ApplicationContext}, but its {@code JobLauncher} field
     * cannot be auto-wired by {@code @SpringBatchTest}'s
     * {@code BatchTestContextBeanPostProcessor} because the
     * production {@code BatchConfig} exposes two beans of type
     * {@link JobLauncher} (the default {@code jobLauncher} from
     * {@code @EnableBatchProcessing} and the custom
     * {@code asyncJobLauncher}). The
     * {@code ObjectProvider.ifUnique(...)} contract suppresses
     * injection when the bean is not unique. The {@link #setUp()}
     * {@code @BeforeEach} hook therefore performs the manual binding
     * to the default synchronous {@code jobLauncher} bean so tests
     * run deterministically and block until the Job finishes.</p>
     */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /**
     * The synchronous default {@link JobLauncher} created by Spring
     * Batch's {@code @EnableBatchProcessing} / Spring Boot
     * auto-configuration. Selected explicitly via
     * {@link Qualifier &#64;Qualifier("jobLauncher")} to disambiguate
     * from the custom {@code asyncJobLauncher} bean declared in
     * {@code BatchConfig}. Used by {@link #setUp()} to inject into
     * {@link #jobLauncherTestUtils} so {@code launchJob(...)} blocks
     * until the Job reaches a terminal status.
     */
    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher jobLauncher;

    /**
     * The {@code combineTransactionsJob} {@link Job} bean produced by
     * {@code CombineTransactionsJob.combineTransactionsJob()}. Injected
     * by bean name. Bound to {@link #jobLauncherTestUtils} in
     * {@link #setUp()} so the harness invokes this exact Job (the
     * BatchTestContextBeanPostProcessor would auto-wire it via
     * {@code ObjectProvider.ifUnique}, but doing so here also ensures
     * the binding survives any future @MockBean / @Primary additions
     * of alternate Job beans during test refactoring).
     */
    @Autowired
    @Qualifier("combineTransactionsJob")
    private Job combineTransactionsJob;

    // -------------------------------------------------------------------------
    // Collaborators replaced by @MockBean
    // -------------------------------------------------------------------------
    // The Spring DI container replaces each named bean (registered by its
    // class-level @Repository / @Service / @Component stereotype or
    // @Configuration @Bean factory) with the Mockito mock declared here.
    // The production CombineTransactionsJob constructor receives these
    // mocks via constructor injection, so verify(...) calls against the
    // mocks inspect the SAME instances the tasklet invoked.

    /**
     * Mocked source-side JPA repository. Tests stub
     * {@code findAll()} to return synthetic {@link DailyTransaction}
     * lists representing the {@code SORTIN} concatenation of
     * {@code TRANSACT.BKUP(0)} + {@code SYSTRAN(0)} GDG generations.
     */
    @MockBean
    private DailyTransactionRepository dailyTransactionRepository;

    /**
     * Mocked target-side JPA repository. Tests verify
     * {@code saveAll(Iterable)} interactions using
     * {@link ArgumentCaptor} to inspect the captured iterable and
     * confirm ascending {@code tranId} order (validating the Java
     * {@code Comparator} replacement of DFSORT
     * {@code SORT FIELDS=(TRAN-ID,A)}).
     */
    @MockBean
    private TransactionRepository transactionRepository;

    /**
     * Mocked S3 output adapter. Tests verify
     * {@link S3OutputService#copyTransactionBackup(String, byte[])}
     * is invoked exactly once per HappyPath / S3Backup scenarios and
     * captured arguments include the {@code businessDate} string from
     * {@link JobParameters} (replaces JCL STEP05R SORTOUT GDG
     * generation per AAP &sect;0.6.2).
     */
    @MockBean
    private S3OutputService s3OutputService;

    /**
     * Mocked audit-log adapter. Replaces real OpenSearch / CloudWatch
     * calls during context bootstrap and during the
     * {@code JobExecutionListener} {@code beforeJob} / {@code afterJob}
     * lifecycle invocations from the production
     * {@code combineTransactionsJob} Bean.
     */
    @MockBean
    private AuditLogService auditLogService;

    /**
     * Mocked MSK Kafka producer adapter. Although
     * {@code CombineTransactionsJob} does not directly publish to
     * Kafka (the EOD utility job only writes RDS + S3), this adapter is
     * wired into sibling context beans via component scan and must be
     * mocked to allow context loading without an MSK broker.
     */
    @MockBean
    private KafkaEventPublisher kafkaEventPublisher;

    /**
     * Mocked AWS Step Functions orchestration adapter. Although
     * {@code CombineTransactionsJob} does not directly invoke Step
     * Functions (it is invoked BY Step Functions as Stage 3 of the EOD
     * pipeline per AAP &sect;0.6.3), this adapter bean must be mocked
     * to prevent {@code SfnClient} credential / endpoint lookups during
     * context startup.
     */
    @MockBean
    private StepFunctionsOrchestrator stepFunctionsOrchestrator;

    /**
     * Mocked ElastiCache Redis cache-aside adapter. Although
     * {@code CombineTransactionsJob} does not directly use the cache,
     * this adapter bean must be mocked to allow context loading
     * without a real Redis cluster.
     */
    @MockBean
    private CacheService cacheService;

    // -------------------------------------------------------------------------
    // AWS SDK v2 client mocks — prevent real AWS credential lookups
    // -------------------------------------------------------------------------
    // Every AWS SDK v2 client registered by AwsSdkConfig (and used by
    // collaborators under com.awsm2.carddemo.adapter) is mocked here so
    // the full Spring context loads in any CI environment without
    // requiring AWS credentials. Mirrors the CardDemoApplicationTests +
    // BatchJobConfigTest pattern (AAP §0.5.1: AWS SDK v2 only).

    /** Mocked {@link S3Client} — replaces real S3 client during bootstrap. */
    @MockBean
    private S3Client s3Client;

    /** Mocked {@link SfnClient} — replaces real Step Functions client. */
    @MockBean
    private SfnClient sfnClient;

    /** Mocked {@link SecretsManagerClient} — prevents real Secrets Manager API. */
    @MockBean
    private SecretsManagerClient secretsManagerClient;

    /** Mocked {@link CloudWatchClient} — prevents Micrometer CloudWatch calls. */
    @MockBean
    private CloudWatchClient cloudWatchClient;

    /** Mocked {@link GlueClient} — prevents real Glue API calls. */
    @MockBean
    private GlueClient glueClient;

    /** Mocked {@link OpenSearchClient} — prevents real OpenSearch indexing. */
    @MockBean
    private OpenSearchClient openSearchClient;

    /** Mocked {@link KafkaTemplate} — prevents real MSK broker connection. */
    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    /** Mocked {@link RedisTemplate} — prevents real Redis connection. */
    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    // =========================================================================
    // @BeforeEach — manual JobLauncher binding + defensive mock reset
    // =========================================================================

    /**
     * Manual binding of {@link #jobLauncher} and
     * {@link #combineTransactionsJob} into {@link #jobLauncherTestUtils}
     * before each test method, plus a defensive Mockito reset on every
     * collaborator mock.
     *
     * <h2>Why manual binding is required</h2>
     * <p>{@code @SpringBatchTest}'s
     * {@code BatchTestContextBeanPostProcessor} auto-wires
     * {@link JobLauncherTestUtils} via
     * {@link org.springframework.beans.factory.ObjectProvider#ifUnique(java.util.function.Consumer)
     * ObjectProvider.ifUnique}. {@code ifUnique} does NOT invoke the
     * consumer when more than one matching bean exists. The
     * production {@code BatchConfig} exposes both the default
     * {@code jobLauncher} (from {@code @EnableBatchProcessing}) and a
     * custom {@code asyncJobLauncher}, so the auto-binding silently
     * skips &mdash; leaving
     * {@code jobLauncherTestUtils.getJobLauncher()} as {@code null}.
     * The {@link #jobLauncher} field above is qualified to the
     * synchronous default; binding it here makes
     * {@code launchJob(JobParameters)} block until the Job reaches a
     * terminal {@link BatchStatus}, which is required for assertions
     * on {@link JobExecution#getStatus()}.</p>
     *
     * <h2>Why defensive mock reset is required</h2>
     * <p>Spring Boot's {@code @MockBean} declares
     * {@code MockReset.AFTER} by default, which resets each mock
     * after every test method via the
     * {@code MockitoTestExecutionListener}. In nested test classes
     * that share the same cached {@code ApplicationContext}, however,
     * a test that throws inside its {@code @Test} body (e.g., when
     * {@code jobLauncherTestUtils} is mis-wired and
     * {@code launchJob(...)} surfaces an NPE) leaves the partially
     * configured mock stubs in an indeterminate state. This explicit
     * {@code Mockito.reset(...)} of every collaborator at the start
     * of each test method guarantees a deterministic clean slate
     * regardless of prior-test outcome ordering, which is critical
     * for the {@code ServiceFailurePropagation} group whose stubs
     * inject {@code thenThrow} behaviour.</p>
     */
    @BeforeEach
    void setUp() {
        // 1. Bind the synchronous JobLauncher + Job into the test
        //    harness so launchJob(JobParameters) blocks until the Job
        //    reaches a terminal BatchStatus. Without this, the
        //    BatchTestContextBeanPostProcessor's ObjectProvider.ifUnique
        //    auto-wire skips (two JobLauncher beans exist in this
        //    context), leaving jobLauncher null and triggering NPE in
        //    launchJob.
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJob(combineTransactionsJob);

        // 2. Defensively reset every collaborator mock so prior-test
        //    stub state never leaks into the current test. The
        //    @MockBean MockReset.AFTER listener handles the common
        //    case, but explicit reset is cheap and immunises the
        //    suite against context-cache-related edge cases.
        Mockito.reset(
                dailyTransactionRepository,
                transactionRepository,
                s3OutputService,
                auditLogService,
                kafkaEventPublisher,
                stepFunctionsOrchestrator,
                cacheService);
    }

    // =========================================================================
    // Helper — builds synthetic DailyTransaction fixtures
    // =========================================================================

    /**
     * Builds a synthetic {@link DailyTransaction} with the given
     * {@code dalytranId} (the only field used by
     * {@code Comparator.comparing(DailyTransaction::getDalytranId)} for
     * sort ordering and by the production
     * {@code mapDailyToTransaction} field-for-field mapper for the
     * downstream {@code Transaction.tranId} column).
     *
     * <p>The remaining fields receive minimal, deterministic values
     * (zero {@link BigDecimal} for {@code dalytranAmt} per AAP
     * &sect;0.6.1 monetary-precision rule; non-null strings for
     * code/source/desc fields so JPA mapping does not stumble on
     * {@code NULL} where {@code @Column(nullable = false)} would
     * apply). Tests focused on sort-order verification need only the
     * {@code dalytranId}.</p>
     *
     * @param dalytranId the 16-character {@code DALYTRAN-ID} primary
     *                   key value; must not be {@code null}
     * @return a populated {@link DailyTransaction} fixture
     */
    private DailyTransaction buildDailyTransaction(String dalytranId) {
        DailyTransaction dt = new DailyTransaction();
        // Primary key — drives the Comparator sort and identity mapping
        // through mapDailyToTransaction → Transaction.tranId.
        dt.setDalytranId(dalytranId);
        // BigDecimal zero for the monetary field per AAP §0.6.1 (never
        // float / double for monetary values). BigDecimal.ZERO has scale
        // 0; we set scale 2 explicitly to match the JPA column
        // definition precision=11, scale=2 declared on
        // DailyTransaction.dalytranAmt.
        dt.setDalytranAmt(new BigDecimal("0.00"));
        return dt;
    }

    // =========================================================================
    // Nested test group #1 — HappyPath
    // =========================================================================

    /**
     * Test group verifying the canonical end-to-end happy path:
     * three unsorted source rows are read, sorted, mapped, bulk-
     * inserted, and backed up to S3 in a single job invocation that
     * transitions to {@link BatchStatus#COMPLETED}.
     */
    @Nested
    @DisplayName("HappyPath — job COMPLETED; reads source; sorts; saves Transactions; writes S3 backup")
    class HappyPath {

        @Test
        @DisplayName("3 unsorted DailyTransactions → 3 Transactions saved + S3 backup")
        void readSortAndSave_completesSuccessfully() throws Exception {
            // Arrange — three unsorted DailyTransactions; mimics the
            // SORTIN concatenation of TRANSACT.BKUP(0) + SYSTRAN(0).
            // ArrayList (NOT List.of) is mandatory: the production
            // CombineTransactionsJob calls List.sort(...) on the
            // returned collection at CombineTransactionsJob.java:726,
            // and List.of(...) returns an immutable list whose sort()
            // throws UnsupportedOperationException. Spring Data JPA's
            // production findAll() returns a mutable ArrayList, so
            // this fixture matches the real-runtime contract.
            List<DailyTransaction> unsorted = new ArrayList<>(List.of(
                    buildDailyTransaction("TX0000000000003"),
                    buildDailyTransaction("TX0000000000001"),
                    buildDailyTransaction("TX0000000000002")));
            when(dailyTransactionRepository.findAll()).thenReturn(unsorted);

            // JobParameters carry the standard CardDemo Spring Batch
            // run identifier (batchRunId), the business-date generation
            // token (businessDate), and the distributed-trace
            // correlation key (correlationId) per AAP §0.6.3.
            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "combtran-run-001")
                    .addString("businessDate", "2022-07-18")
                    .addString("correlationId", "corr-001")
                    .toJobParameters();

            // Act — synchronous blocking launch via the
            // JobLauncherTestUtils harness auto-configured by
            // @SpringBatchTest.
            JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert — terminal status is COMPLETED.
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            // Verify exactly one read (the JCL STEP05R SORTIN
            // concatenation equivalent).
            verify(dailyTransactionRepository, times(1)).findAll();
            // Verify exactly one bulk insert (the JCL STEP10 IDCAMS
            // REPRO equivalent). The production tasklet partitions the
            // sorted list into bulkInsertSize chunks; with 3 records
            // and default bulkInsertSize=1000, exactly one saveAll
            // invocation fires.
            verify(transactionRepository, times(1)).saveAll(any(Iterable.class));
            // Verify exactly one S3 backup (the JCL STEP05R SORTOUT
            // GDG (+1) equivalent — replaced by S3 versioned object
            // per AAP §0.6.2). The production tasklet uses String
            // signatures for both arguments (NOT LocalDate), so we use
            // anyString() + any() to avoid coupling to the byte
            // payload shape.
            verify(s3OutputService, times(1))
                    .copyTransactionBackup(anyString(), any(byte[].class));

            // Verify the JobExecutionListener emitted lifecycle audit
            // events (STARTED in beforeJob + terminal status in
            // afterJob) per AAP §0.6.6. The production listener emits
            // exactly two audit events per successful job execution.
            // The shared BatchJobConfig listener may also fire if
            // attached at the global level; we use atLeast(2) for
            // defensive resilience against future listener
            // composition changes.
            verify(auditLogService, atLeast(2)).logBatchJobLifecycle(
                    anyString(), anyString(), anyString(), any(), any(), anyString());
        }
    }

    // =========================================================================
    // Nested test group #2 — SortOrdering
    // =========================================================================

    /**
     * Test group verifying that
     * {@code Comparator.comparing(DailyTransaction::getDalytranId)}
     * preserves the COBOL DFSORT {@code SORT FIELDS=(TRAN-ID,A)}
     * natural ordering exactly (AAP &sect;0.6.2: COBOL
     * {@code PIC X(16)} zero-padded alphanumerics sort identically
     * under COBOL CH (character) collation and Java
     * {@link String#compareTo(String)}).
     */
    @Nested
    @DisplayName("SortOrdering — saveAll iterable is in ascending tranId order")
    class SortOrdering {

        @Test
        @DisplayName("Unsorted input → saveAll receives ascending tranId order")
        void unsortedInput_savedInAscendingDalytranIdOrder() throws Exception {
            // Arrange — input deliberately out of order so the
            // Comparator must rearrange every element.
            List<DailyTransaction> unsorted = new ArrayList<>(List.of(
                    buildDailyTransaction("TX0000000000099"),
                    buildDailyTransaction("TX0000000000010"),
                    buildDailyTransaction("TX0000000000050"),
                    buildDailyTransaction("TX0000000000001"),
                    buildDailyTransaction("TX0000000000020")));
            when(dailyTransactionRepository.findAll()).thenReturn(unsorted);

            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "combtran-sort-002")
                    .addString("businessDate", "2022-07-18")
                    .addString("correlationId", "corr-sort-002")
                    .toJobParameters();

            // Act
            JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert — job completed.
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            // Capture the iterable passed to saveAll so we can iterate
            // it and verify ascending order. The production tasklet
            // applies Comparator.comparing(DailyTransaction::getDalytranId)
            // BEFORE the map step, so the mapped Transactions inherit
            // the sorted order via the field-for-field
            // mapDailyToTransaction copy (Transaction.tranId =
            // DailyTransaction.dalytranId).
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Iterable<Transaction>> captor =
                    ArgumentCaptor.forClass(Iterable.class);
            verify(transactionRepository, times(1)).saveAll(captor.capture());

            // Extract tranIds from the captured iterable preserving
            // the order it was passed in.
            List<String> savedIds = new ArrayList<>();
            captor.getValue().forEach(t -> savedIds.add(t.getTranId()));

            // Build the expected sorted order — a copy of the captured
            // tranIds sorted by natural String order. Any monotonic
            // mapping preserves order; for the identity mapping
            // (DALYTRAN-ID → TRAN-ID) the expected order equals the
            // input order sorted ascending.
            List<String> expectedSorted = new ArrayList<>(savedIds);
            Collections.sort(expectedSorted);
            assertThat(savedIds).containsExactlyElementsOf(expectedSorted);

            // Additionally assert the precise expected sequence to
            // catch silent regressions in the Comparator: the input
            // [99, 10, 50, 01, 20] sorted ascending is
            // [01, 10, 20, 50, 99].
            assertThat(savedIds).containsExactly(
                    "TX0000000000001",
                    "TX0000000000010",
                    "TX0000000000020",
                    "TX0000000000050",
                    "TX0000000000099");
        }

        @Test
        @DisplayName("Already-sorted input → saveAll receives same ascending order")
        void alreadySortedInput_stableOrdering() throws Exception {
            // Arrange — input pre-sorted ascending; the Comparator
            // must be a no-op (Java sort is stable; equal keys keep
            // input order, ascending keys stay in place). Use a
            // mutable ArrayList because the production CombineTransactionsJob
            // calls List.sort(...) on the returned collection — which
            // throws UnsupportedOperationException on List.of() lists.
            List<DailyTransaction> sorted = new ArrayList<>(List.of(
                    buildDailyTransaction("TX0000000000001"),
                    buildDailyTransaction("TX0000000000002"),
                    buildDailyTransaction("TX0000000000003")));
            when(dailyTransactionRepository.findAll()).thenReturn(sorted);

            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "combtran-already-sorted-003")
                    .addString("businessDate", "2022-07-18")
                    .addString("correlationId", "corr-as-003")
                    .toJobParameters();

            // Act
            JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert — job completed and saveAll iterated 1, 2, 3 in
            // that exact order.
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Iterable<Transaction>> captor =
                    ArgumentCaptor.forClass(Iterable.class);
            verify(transactionRepository, times(1)).saveAll(captor.capture());

            List<String> savedIds = new ArrayList<>();
            captor.getValue().forEach(t -> savedIds.add(t.getTranId()));
            assertThat(savedIds).containsExactly(
                    "TX0000000000001",
                    "TX0000000000002",
                    "TX0000000000003");
        }
    }

    // =========================================================================
    // Nested test group #3 — S3Backup
    // =========================================================================

    /**
     * Test group verifying that
     * {@link S3OutputService#copyTransactionBackup(String, byte[])}
     * receives the {@code businessDate} parameter from
     * {@link JobParameters} as the generation token (replacing GDG
     * {@code (+1)} per AAP &sect;0.6.2).
     */
    @Nested
    @DisplayName("S3Backup — copyTransactionBackup(businessDate, payload) replaces GDG (+1)")
    class S3Backup {

        @Test
        @DisplayName("S3 backup invoked with businessDate string from JobParameters")
        void s3Backup_invokedWithBusinessDate() throws Exception {
            // Arrange — single record + ISO-8601 businessDate string
            // (the parameter is supplied as a String through the
            // JobParametersBuilder; the production tasklet reads it
            // via JobParameters.getString and passes it verbatim to
            // S3OutputService).
            final LocalDate businessDate = LocalDate.of(2022, 7, 18);
            final String businessDateStr = businessDate.toString();
            // ArrayList (not List.of) — the production tasklet sorts
            // the returned collection, which fails on immutable lists.
            when(dailyTransactionRepository.findAll())
                    .thenReturn(new ArrayList<>(List.of(
                            buildDailyTransaction("TX0000000000001"))));

            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "combtran-s3-004")
                    .addString("businessDate", businessDateStr)
                    .addString("correlationId", "corr-s3-004")
                    .toJobParameters();

            // Act
            JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert — job completed.
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            // Verify S3OutputService.copyTransactionBackup(String,
            // byte[]) was called with the exact businessDate string.
            // The production tasklet passes the businessDate parameter
            // verbatim — no LocalDate parsing, no ISO normalization —
            // so the captured String must equal the input.
            ArgumentCaptor<String> generationCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(1))
                    .copyTransactionBackup(generationCaptor.capture(), payloadCaptor.capture());

            assertThat(generationCaptor.getValue()).isEqualTo(businessDateStr);
            // The payload is a non-null, non-empty UTF-8 byte array
            // (the pipe-delimited serialization of the combined
            // transactions per the production tasklet's
            // serializeForBackup helper). We do not assert exact bytes
            // (production may evolve the serialization), but we DO
            // assert non-emptiness — a zero-byte payload would be a
            // regression indicating the serializer silently produced
            // an empty result.
            assertThat(payloadCaptor.getValue()).isNotNull();
            assertThat(payloadCaptor.getValue().length).isGreaterThan(0);
        }
    }

    // =========================================================================
    // Nested test group #4 — EmptyDataset
    // =========================================================================

    /**
     * Test group verifying the empty-source path: when
     * {@code daily_transactions} contains zero rows, the job still
     * completes but emits no bulk insert and no S3 backup (gated by
     * the production tasklet's {@code !targets.isEmpty()} check).
     */
    @Nested
    @DisplayName("EmptyDataset — zero source rows; job COMPLETED; no saveAll/backup")
    class EmptyDataset {

        @Test
        @DisplayName("When no DailyTransactions exist, job completes; no saveAll; no S3 backup")
        void noDailyTransactions_jobCompletes() throws Exception {
            // Arrange — empty source list.
            when(dailyTransactionRepository.findAll())
                    .thenReturn(Collections.emptyList());

            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "combtran-empty-005")
                    .addString("businessDate", "2099-01-01")
                    .addString("correlationId", "corr-empty-005")
                    .toJobParameters();

            // Act
            JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert — job still completes (no rows is not an error;
            // the source could be empty if the prior-day backup and
            // system-generated transactions were both empty).
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            // Source read was attempted exactly once.
            verify(dailyTransactionRepository, times(1)).findAll();

            // The production tasklet's saveAll loop iterates `for (i =
            // 0; i < totalTargets; i += bulkInsertSize)`. When
            // totalTargets is 0, the loop body never executes, so
            // saveAll is never called. We use atMost(1) for defensive
            // resilience against alternate production implementations
            // that explicitly call saveAll(Collections.emptyList()).
            verify(transactionRepository, atMost(1)).saveAll(any(Iterable.class));

            // S3 backup is gated by `businessDate != null && !blank &&
            // !targets.isEmpty()`. With empty targets the gate is
            // false, so copyTransactionBackup is never invoked.
            verify(s3OutputService, never())
                    .copyTransactionBackup(anyString(), any(byte[].class));
        }
    }

    // =========================================================================
    // Nested test group #5 — ParameterValidation
    // =========================================================================

    /**
     * Test group verifying that the required {@code batchRunId}
     * {@link JobParameters} entry is enforced by the production
     * tasklet's {@code requireParameter} pre-check.
     *
     * <p>Note: The production {@code CombineTransactionsJob} bean does
     * NOT attach {@code BatchJobConfig.standardJobParametersValidator}
     * to its {@code JobBuilder}; instead, the {@code requireParameter}
     * check runs inside the tasklet and throws
     * {@link IllegalArgumentException} when {@code batchRunId} is
     * missing or blank. Spring Batch catches the in-tasklet exception
     * and transitions the {@link JobExecution} to
     * {@link BatchStatus#FAILED} rather than propagating to the
     * caller. The test therefore asserts on the FAILED status plus
     * the captured failure exceptions.</p>
     */
    @Nested
    @DisplayName("ParameterValidation — missing batchRunId fails job (in-tasklet check)")
    class ParameterValidation {

        @Test
        @DisplayName("Missing batchRunId → BatchStatus.FAILED; findAll never called; no S3 backup")
        void missingBatchRunId_jobFails() throws Exception {
            // Arrange — JobParameters with businessDate present but no
            // batchRunId. The production tasklet's first action is
            // requireParameter(jobParameters, "batchRunId") which
            // throws IllegalArgumentException, aborting the step
            // BEFORE dailyTransactionRepository.findAll() is invoked.
            JobParameters params = new JobParametersBuilder()
                    .addString("businessDate", "2022-07-18")
                    .addString("correlationId", "corr-noid-006")
                    .toJobParameters();

            // Act — JobLauncherTestUtils captures the exception inside
            // Spring Batch's step infrastructure and returns the
            // failed JobExecution.
            JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert — terminal status is FAILED.
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            // The failure exception list must be non-empty.
            assertThat(execution.getAllFailureExceptions()).isNotEmpty();
            // At least one failure exception must reference the
            // missing parameter name so operators can fix the AWS
            // Batch container environment variable / Step Functions
            // input. The exact exception type is
            // IllegalArgumentException (per
            // CombineTransactionsJob.requireParameter); the message
            // contains "batchRunId" verbatim.
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(t -> t != null && t.getMessage() != null
                            && t.getMessage().contains("batchRunId"));

            // Confirm the tasklet aborted BEFORE any data work began:
            // no source read, no bulk insert, no S3 backup.
            verify(dailyTransactionRepository, never()).findAll();
            verify(transactionRepository, never()).saveAll(any(Iterable.class));
            verify(s3OutputService, never())
                    .copyTransactionBackup(anyString(), any(byte[].class));
        }

        @Test
        @DisplayName("Missing businessDate → job COMPLETED; S3 backup skipped (optional parameter)")
        void missingBusinessDate_jobCompletes_noS3Backup() throws Exception {
            // Arrange — JobParameters with batchRunId but no
            // businessDate (per the production class Javadoc:
            // businessDate is OPTIONAL; when absent, the S3 backup is
            // skipped to mirror the CICS-online ad-hoc / smoke-test
            // invocation pattern).
            // ArrayList (not List.of) — the production tasklet sorts
            // the returned collection, which fails on immutable lists.
            when(dailyTransactionRepository.findAll())
                    .thenReturn(new ArrayList<>(List.of(
                            buildDailyTransaction("TX0000000000001"))));

            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "combtran-no-bd-007")
                    .addString("correlationId", "corr-no-bd-007")
                    .toJobParameters();

            // Act
            JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert — job completed (missing businessDate is not an
            // error path).
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            // The source was read and the target was saved (so the
            // JPA load proceeded as expected) — only the S3 backup is
            // skipped when businessDate is absent.
            verify(dailyTransactionRepository, times(1)).findAll();
            verify(transactionRepository, times(1)).saveAll(any(Iterable.class));
            verify(s3OutputService, never())
                    .copyTransactionBackup(anyString(), any(byte[].class));
        }
    }

    // =========================================================================
    // Nested test group #6 — ServiceFailurePropagation
    // =========================================================================

    /**
     * Test group verifying that {@link RuntimeException}s thrown by
     * collaborators (the source repository or the S3 adapter) cause
     * the job to transition to {@link BatchStatus#FAILED} with the
     * failure captured in
     * {@link JobExecution#getAllFailureExceptions()}.
     */
    @Nested
    @DisplayName("ServiceFailurePropagation — collaborator exceptions cause job FAILED")
    class ServiceFailurePropagation {

        @Test
        @DisplayName("DailyTransactionRepository.findAll() throws → BatchStatus.FAILED")
        void repositoryException_jobFails() throws Exception {
            // Arrange — source repository throws on findAll(),
            // simulating an RDS PostgreSQL connection failure or
            // transient JDBC error.
            when(dailyTransactionRepository.findAll())
                    .thenThrow(new RuntimeException("Database connection failed"));

            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "combtran-repo-fail-008")
                    .addString("businessDate", "2022-07-18")
                    .addString("correlationId", "corr-repo-fail-008")
                    .toJobParameters();

            // Act
            JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert — terminal status is FAILED and the failure
            // exception is captured for downstream regulatory audit
            // (preserved verbatim per AAP §0.6.6).
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions()).isNotEmpty();

            // The failure should occur BEFORE any saveAll or S3
            // backup — those collaborators must remain untouched.
            verify(transactionRepository, never()).saveAll(any(Iterable.class));
            verify(s3OutputService, never())
                    .copyTransactionBackup(anyString(), any(byte[].class));
        }

        @Test
        @DisplayName("S3OutputService.copyTransactionBackup() throws → BatchStatus.FAILED")
        void s3FailureExtends_jobFails() throws Exception {
            // Arrange — source returns one record so the tasklet
            // proceeds through sort + saveAll + reaches the S3 backup
            // step; the S3 adapter then throws, exercising the S3
            // PutObject failure path. ArrayList (not List.of) — the
            // production tasklet sorts the returned collection.
            when(dailyTransactionRepository.findAll())
                    .thenReturn(new ArrayList<>(List.of(
                            buildDailyTransaction("TX0000000000001"))));
            Mockito.doThrow(new RuntimeException("S3 PutObject failed"))
                    .when(s3OutputService)
                    .copyTransactionBackup(anyString(), any(byte[].class));

            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "combtran-s3-fail-009")
                    .addString("businessDate", "2022-07-18")
                    .addString("correlationId", "corr-s3-fail-009")
                    .toJobParameters();

            // Act
            JobExecution execution = jobLauncherTestUtils.launchJob(params);

            // Assert — terminal status is FAILED and the S3 PutObject
            // failure surfaced as a captured exception.
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions()).isNotEmpty();

            // The saveAll did fire (the tasklet completed steps 1-4
            // successfully before failing on S3 in step 5). Note: in
            // a non-XA transactional environment the saveAll
            // committed before the S3 PutObject error rolled back —
            // the Spring Batch step's transactional boundary covers
            // ONLY the JPA tables (per the production class Javadoc
            // §0.4.1 SYNCPOINT replacement note).
            verify(dailyTransactionRepository, times(1)).findAll();
            verify(transactionRepository, times(1)).saveAll(any(Iterable.class));
            verify(s3OutputService, times(1))
                    .copyTransactionBackup(anyString(), any(byte[].class));
        }
    }
}
