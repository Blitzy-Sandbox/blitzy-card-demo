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
// BatchJobConfigTest — foundational Spring Batch infrastructure bean tests
// =============================================================================
//
// Tests the three shared @Bean definitions in
// `com.awsm2.carddemo.batch.BatchJobConfig`:
//
//   1. standardJobParametersValidator()    — JobParametersValidator
//   2. cardDemoJobParametersIncrementer()  — JobParametersIncrementer (RunIdIncrementer)
//   3. sharedAuditJobExecutionListener()   — JobExecutionListener
//
// These beans are foundational: every CardDemo Spring Batch job (Daily
// Transaction Posting, Interest Calculation, Combine Transactions, Statement
// Generation, Transaction Report) attaches them to its JobBuilder via
// `.validator(...)`, `.incrementer(...)`, `.listener(...)`. If any of these
// beans behaves incorrectly, EVERY downstream batch job is affected — hence
// the dedicated bean-level test class here.
//
// Per AAP §0.4.1 — BatchJobConfig has NO COBOL/JCL source-file counterpart;
// it is a pure Spring Configuration class introduced by the migration. The
// helper beans, however, encode behaviours that mirror JCL conventions:
//
//   * standardJobParametersValidator     ← replaces JCL PARM= length/format
//                                          checks (e.g., CBACT04C
//                                          0500-PARM-CHECK)
//   * cardDemoJobParametersIncrementer   ← replaces JES2 JCT monotonic
//                                          job-number assignment
//   * sharedAuditJobExecutionListener    ← replaces SYSPRINT DD SYSOUT=* +
//                                          RETURN-CODE inspection
//
// Test approach (AAP §0.7.2):
//   * Full @SpringBootTest context with the `test` Spring profile to
//     load BatchJobConfig and resolve its three beans via @Autowired.
//   * @ServiceConnection + @DynamicPropertySource bind a real
//     PostgreSQL 16-alpine Testcontainer so Flyway migrations + JPA bean
//     wiring succeed (matches CardDemoApplicationTests pattern).
//   * @Import TestSecretsManagerConfiguration supplies a stub
//     SecretsManagerService so JwtTokenProvider.initSigningKey() satisfies
//     its @PostConstruct precondition without contacting real AWS.
//   * AuditLogService is replaced with a Mockito @MockBean so that
//     verify(auditLogService, atLeastOnce()).logBatchJobLifecycle(...)
//     can confirm the sharedAuditJobExecutionListener actually emits
//     audit events on beforeJob / afterJob lifecycle callbacks.
//   * All AWS SDK v2 clients (S3Client, SfnClient, SecretsManagerClient,
//     CloudWatchClient, GlueClient, OpenSearchClient) plus KafkaTemplate
//     and RedisTemplate are @MockBean-replaced so no real AWS / Kafka /
//     Redis network calls occur during context refresh.
//
// AAP cross-references:
//   §0.3.1  — design pattern: Layered Architecture; one @Service per program
//   §0.4.1  — transformation mapping (BatchJobConfig.java listed as CREATE)
//   §0.5.1  — test stack: JUnit 5 + Mockito + Testcontainers + Spring Boot Test
//   §0.6.3  — JCL → Step Functions orchestration (batch-job lifecycle)
//   §0.6.6  — Audit / observability (OpenSearch indexing of batch events)
//   §0.7.1  — Refactoring rules (Jakarta only, AWS SDK v2 only, BigDecimal)
//   §0.7.2  — Testing approach (JUnit 5 + Mockito + Testcontainers + LocalStack)

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.adapter.KafkaEventPublisher;
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.adapter.StepFunctionsOrchestrator;
import com.awsm2.carddemo.adapter.SecretsManagerService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
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

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Bean-level unit tests for {@link com.awsm2.carddemo.batch.BatchJobConfig}.
 *
 * <p>This test class loads the full Spring Boot {@code ApplicationContext}
 * under the {@code test} profile so that the three foundational
 * {@code @Bean} definitions in {@code BatchJobConfig} are visible via
 * {@code @Autowired} and the production {@link
 * com.awsm2.carddemo.adapter.AuditLogService AuditLogService} dependency
 * can be replaced with a Mockito {@link MockBean} for invocation
 * verification.</p>
 *
 * <h2>What this test asserts</h2>
 * <ul>
 *   <li>{@link com.awsm2.carddemo.batch.BatchJobConfig#standardJobParametersValidator()
 *       standardJobParametersValidator} throws {@link JobParametersInvalidException}
 *       when the required {@code batchRunId} parameter is missing, blank, or
 *       when the entire {@link JobParameters} instance is {@code null}; and
 *       accepts a parameter set in which {@code batchRunId} is present
 *       alongside any combination of the seven standard CardDemo job
 *       parameters ({@code batchRunId}, {@code businessDate},
 *       {@code parmDate}, {@code statementMonth}, {@code startDate},
 *       {@code endDate}, {@code correlationId}) per AAP &sect;0.6.3.</li>
 *   <li>{@link com.awsm2.carddemo.batch.BatchJobConfig#cardDemoJobParametersIncrementer()
 *       cardDemoJobParametersIncrementer} is an instance of Spring Batch's
 *       built-in {@link RunIdIncrementer} (preserving the COBOL-era JES2
 *       JCT run-number semantics) and its {@code getNext} contract:
 *       (a) when no {@code run.id} parameter is present, the incrementer
 *       adds {@code run.id=1}; (b) when {@code run.id=N} is present, the
 *       incrementer returns {@code run.id=N+1}.</li>
 *   <li>{@link com.awsm2.carddemo.batch.BatchJobConfig#sharedAuditJobExecutionListener(AuditLogService)
 *       sharedAuditJobExecutionListener} invokes
 *       {@link AuditLogService#logBatchJobLifecycle(String, String, String, Long, Map, String)}
 *       on both {@code beforeJob} (status {@code "STARTED"}) and
 *       {@code afterJob} (status {@code "COMPLETED"} / {@code "FAILED"} /
 *       {@code "STOPPED"}) lifecycle callbacks, with the deterministic
 *       parameters extracted from the {@link JobExecution}: job name,
 *       execution ID, status name, duration millis, payload (always
 *       {@code null} from the listener), and correlation ID.</li>
 * </ul>
 *
 * <h2>Why a full {@code @SpringBootTest} (not a unit-style new BatchJobConfig())</h2>
 * <p>The {@code sharedAuditJobExecutionListener} bean method takes an
 * {@link AuditLogService} parameter that Spring's DI container supplies.
 * To exercise that dependency injection path AND replace the real
 * {@code AuditLogService} with a Mockito spy that {@code verify(...)} can
 * inspect, the test class must load the actual Spring context with a
 * {@link MockBean &#64;MockBean} {@code AuditLogService} declaration. A
 * pure unit test instantiating {@code new BatchJobConfig()} would bypass
 * the Spring lifecycle and not exercise the bean wiring being
 * validated.</p>
 *
 * <h2>Test class style</h2>
 * <ul>
 *   <li>Package-private class &mdash; JUnit 5 best practice; no
 *       {@code public} keyword.</li>
 *   <li>{@link DisplayName} on the class and on every test method &mdash;
 *       human-readable test output in CI reports.</li>
 *   <li>{@link Nested} inner classes group related tests by bean:
 *       {@link Validator}, {@link Incrementer}, {@link AuditListener}.</li>
 *   <li>AssertJ fluent assertions ({@code assertThat},
 *       {@code assertThatCode}, {@code assertThatThrownBy}) for
 *       expressive failure messages.</li>
 *   <li>Mockito {@code verify(mock, atLeastOnce()).method(args)} to
 *       confirm listener invocations without prescribing exact call
 *       counts (defensive against future double-emission for retries).</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.batch.BatchJobConfig
 * @see com.awsm2.carddemo.adapter.AuditLogService#logBatchJobLifecycle(String, String, String, Long, Map, String)
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Import(BatchJobConfigTest.TestSecretsManagerConfiguration.class)
@DisplayName("BatchJobConfig — Shared Spring Batch infrastructure tests")
class BatchJobConfigTest {

    // -------------------------------------------------------------------------
    // @TestConfiguration — stub SecretsManagerService for JwtTokenProvider
    // -------------------------------------------------------------------------
    // JwtTokenProvider (security/JwtTokenProvider.java) is @Component
    // @RefreshScope and performs an eager Secrets Manager fetch in its
    // @PostConstruct lifecycle hook to load the HS256 signing key. In
    // production that fetch returns a KMS-protected key from AWS Secrets
    // Manager; in this @SpringBootTest we must supply a deterministic
    // stub that returns a ≥ 32-byte string so Keys.hmacShaKeyFor(...)
    // accepts the material (HS256 requires a 256-bit / 32-byte symmetric
    // key per RFC 7518).
    //
    // Mirrors the pattern in CardDemoApplicationTests so context refresh
    // succeeds. @TestConfiguration registers the stub at bean-definition
    // time so JwtTokenProvider sees the stubbed value during
    // @PostConstruct, before any @MockBean stubbing would run in
    // @BeforeEach. @Primary ensures this stub takes precedence over the
    // real SecretsManagerService bean registered by component scan in
    // com.awsm2.carddemo.adapter.

    /**
     * Provides a stubbed {@link SecretsManagerService} bean for the
     * duration of {@link BatchJobConfigTest}. Returns a deterministic
     * 60-byte ASCII placeholder for any
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
         * {@code getSecretJsonField(...)} invocation so that
         * {@code JwtTokenProvider.initSigningKey()} can satisfy its
         * {@code @PostConstruct} precondition during context refresh.
         *
         * @return a pre-configured Mockito mock that supplies the
         *         placeholder signing key for any input
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
    }

    // -------------------------------------------------------------------------
    // Testcontainers PostgreSQL — required by JPA / Flyway bean wiring
    // -------------------------------------------------------------------------
    // BatchJobConfig itself does not depend on the DataSource; however the
    // full @SpringBootTest context loads every @Configuration class in
    // com.awsm2.carddemo.config and every @Service / @Repository under
    // com.awsm2.carddemo. Many of those beans require a working JDBC
    // DataSource, and Flyway migrations must apply to a real PostgreSQL
    // schema before Hibernate's `ddl-auto=validate` startup probe runs.
    //
    // postgres:16-alpine matches the production RDS PostgreSQL Multi-AZ
    // engine version per AAP §0.5.1 and §0.6.2 — same image used by
    // CardDemoApplicationTests for consistency.

    /**
     * Ephemeral PostgreSQL 16-alpine Docker container backing JPA /
     * Flyway bean wiring during context refresh.
     *
     * <p>Started once per test class via {@link Testcontainers}; bound
     * to Spring Boot's {@code FlywayConnectionDetails} via
     * {@link ServiceConnection} so Flyway migrations under
     * {@code src/main/resources/db/migration/V*.sql} apply at
     * context-refresh time. The image tag is pinned to
     * {@code postgres:16-alpine} per AAP &sect;0.5.1 (no
     * {@code latest} tags).</p>
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /**
     * Binds the {@link #POSTGRES} container's connection details into
     * {@code spring.datasource.*} so the {@code @Primary @RefreshScope
     * DataSource} bean declared in
     * {@code com.awsm2.carddemo.config.JpaConfig} resolves to the SAME
     * Testcontainers PostgreSQL instance that Flyway migrates via
     * {@link ServiceConnection}.
     *
     * <p>Mirrors the {@code overrideDataSourceUrl} pattern in
     * {@link com.awsm2.carddemo.CardDemoApplicationTests} &mdash;
     * required because the user-declared {@code @Primary} DataSource
     * bypasses Spring Boot's HikariJdbcConnectionDetails
     * BeanPostProcessor that would otherwise rewrite the URL from
     * {@link ServiceConnection} alone.</p>
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
    }

    // -------------------------------------------------------------------------
    // The three foundational beans under test (autowired)
    // -------------------------------------------------------------------------

    /**
     * The {@link JobParametersValidator} produced by
     * {@code BatchJobConfig.standardJobParametersValidator()}.
     * Verified to require the {@code batchRunId} JobParameter.
     */
    @Autowired
    private JobParametersValidator standardJobParametersValidator;

    /**
     * The {@link JobParametersIncrementer} produced by
     * {@code BatchJobConfig.cardDemoJobParametersIncrementer()}. Verified
     * to be a {@link RunIdIncrementer} and to monotonically increment
     * the {@code run.id} parameter (preserves COBOL JES2 JCT semantics).
     */
    @Autowired
    private JobParametersIncrementer cardDemoJobParametersIncrementer;

    /**
     * The {@link JobExecutionListener} produced by
     * {@code BatchJobConfig.sharedAuditJobExecutionListener(AuditLogService)}.
     * Verified to invoke
     * {@link AuditLogService#logBatchJobLifecycle(String, String, String, Long, Map, String)}
     * on both {@code beforeJob} and {@code afterJob} callbacks.
     */
    @Autowired
    private JobExecutionListener sharedAuditJobExecutionListener;

    // -------------------------------------------------------------------------
    // AuditLogService — replaced by @MockBean so verify(...) inspects calls
    // -------------------------------------------------------------------------
    /**
     * Mocked {@link AuditLogService}. Spring Boot's {@link MockBean}
     * registers this Mockito mock as the {@code AuditLogService} bean in
     * the {@code ApplicationContext}, replacing the real adapter
     * registered via {@code @Service}. The
     * {@code sharedAuditJobExecutionListener(AuditLogService)} bean
     * method receives THIS mock as its parameter during context
     * refresh, so the captured reference inside the anonymous
     * {@link JobExecutionListener} IS this mock &mdash; enabling
     * {@code verify(auditLogService, atLeastOnce())} assertions.
     *
     * <p>{@link MockBean} resets the mock automatically after each test
     * method (Spring Boot default {@code MockReset.AFTER}), so each
     * {@code @Test} starts with a fresh interaction recording.</p>
     */
    @MockBean
    private AuditLogService auditLogService;

    // -------------------------------------------------------------------------
    // Adapter mocks — symmetry with sibling tests; prevent real AWS calls
    // -------------------------------------------------------------------------
    // BatchJobConfig itself does NOT depend on these adapters. The full
    // @SpringBootTest context, however, loads sibling batch job beans
    // (CombineTransactionsJob, etc.) and @Service classes that DO depend
    // on these adapters. Mocking them at the adapter layer prevents the
    // real adapter constructors from running real AWS SDK initialisation
    // and matches the pattern documented in the file's internal_imports
    // schema (see §0 of AAP).

    /** Mocked {@link S3OutputService}. */
    @MockBean
    private S3OutputService s3OutputService;

    /** Mocked {@link KafkaEventPublisher}. */
    @MockBean
    private KafkaEventPublisher kafkaEventPublisher;

    /** Mocked {@link StepFunctionsOrchestrator}. */
    @MockBean
    private StepFunctionsOrchestrator stepFunctionsOrchestrator;

    /** Mocked {@link CacheService}. */
    @MockBean
    private CacheService cacheService;

    // -------------------------------------------------------------------------
    // AWS SDK v2 client mocks — prevent real credential resolution / API calls
    // -------------------------------------------------------------------------
    // Every AWS SDK v2 client registered by AwsSdkConfig (and used by
    // collaborators under com.awsm2.carddemo.adapter) is mocked here so
    // that the full Spring context loads in any CI environment without
    // requiring AWS credentials. Mirrors CardDemoApplicationTests pattern.

    /** Mocked {@link S3Client}. */
    @MockBean
    private S3Client s3Client;

    /** Mocked {@link SfnClient}. */
    @MockBean
    private SfnClient sfnClient;

    /** Mocked {@link SecretsManagerClient}. */
    @MockBean
    private SecretsManagerClient secretsManagerClient;

    /** Mocked {@link CloudWatchClient}. */
    @MockBean
    private CloudWatchClient cloudWatchClient;

    /** Mocked {@link GlueClient}. */
    @MockBean
    private GlueClient glueClient;

    /** Mocked {@link OpenSearchClient}. */
    @MockBean
    private OpenSearchClient openSearchClient;

    // -------------------------------------------------------------------------
    // Spring infrastructure template mocks (Kafka + Redis)
    // -------------------------------------------------------------------------
    // KafkaTemplate (KafkaConfig) and RedisTemplate (RedisConfig) require
    // real broker / cache connectivity at startup. Mocked so context
    // refresh does not stall on Kafka / Redis connection attempts.

    /** Mocked {@link KafkaTemplate}. */
    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    /** Mocked {@link RedisTemplate}. */
    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    // =========================================================================
    // Nested test group #1 — Validator
    // =========================================================================

    /**
     * Test group for {@code BatchJobConfig.standardJobParametersValidator}.
     * Confirms that the validator enforces the {@code batchRunId}
     * precondition required by AAP &sect;0.6.3 (every CardDemo batch
     * job, whether launched by AWS Step Functions, AWS Batch CLI, or a
     * local {@code mvn spring-boot:run}, must supply a non-blank
     * {@code batchRunId}).
     */
    @Nested
    @DisplayName("standardJobParametersValidator — Enforces batchRunId required")
    class Validator {

        @Test
        @DisplayName("throws JobParametersInvalidException when batchRunId is missing")
        void validatorRejectsMissingBatchRunId() {
            // Arrange — JobParameters with businessDate but no batchRunId
            JobParameters params = new JobParametersBuilder()
                    .addString("businessDate", "2022-07-18")
                    .toJobParameters();

            // Act & Assert — validator must throw and message must
            // identify the missing parameter so operators can fix the
            // AWS Batch environment variable / Step Functions input
            assertThatThrownBy(() -> standardJobParametersValidator.validate(params))
                    .isInstanceOf(JobParametersInvalidException.class)
                    .hasMessageContaining("batchRunId");
        }

        @Test
        @DisplayName("accepts JobParameters when batchRunId is supplied")
        void validatorAcceptsValidParameters() {
            // Arrange — minimal valid parameter set
            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "test-run-001")
                    .addString("businessDate", "2022-07-18")
                    .toJobParameters();

            // Act & Assert — no exception expected
            assertThatCode(() -> standardJobParametersValidator.validate(params))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects empty-string batchRunId as missing (String.isBlank semantics)")
        void validatorRejectsEmptyBatchRunId() {
            // Arrange — empty string for batchRunId
            // Per BatchJobConfig the check uses String.isBlank() which
            // returns true for the empty string, so an empty
            // batchRunId is treated identically to a missing one.
            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "")
                    .toJobParameters();

            // Act & Assert
            assertThatThrownBy(() -> standardJobParametersValidator.validate(params))
                    .isInstanceOf(JobParametersInvalidException.class)
                    .hasMessageContaining("batchRunId");
        }

        @Test
        @DisplayName("rejects whitespace-only batchRunId as missing (String.isBlank semantics)")
        void validatorRejectsWhitespaceOnlyBatchRunId() {
            // Arrange — whitespace-only string
            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "   ")
                    .toJobParameters();

            // Act & Assert — String.isBlank() returns true for
            // whitespace-only strings, so the validator must reject
            assertThatThrownBy(() -> standardJobParametersValidator.validate(params))
                    .isInstanceOf(JobParametersInvalidException.class)
                    .hasMessageContaining("batchRunId");
        }

        @Test
        @DisplayName("throws JobParametersInvalidException when entire JobParameters object is null")
        void validatorRejectsNullJobParameters() {
            // Arrange — null JobParameters (defensive guard in
            // BatchJobConfig). Spring normally supplies an empty
            // JobParameters but never null; the validator's null-guard
            // protects against a programmatic launch error.

            // Act & Assert
            assertThatThrownBy(() -> standardJobParametersValidator.validate(null))
                    .isInstanceOf(JobParametersInvalidException.class)
                    .hasMessageContaining("null");
        }

        @Test
        @DisplayName("accepts the full set of CardDemo standard JobParameters (7 keys)")
        void validatorAcceptsAllStandardParameters() {
            // Arrange — every standard CardDemo JobParameter per AAP §0.6.3
            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "comprehensive-run")
                    .addString("businessDate", "2022-07-18")
                    .addString("parmDate", "2022071800")
                    .addString("statementMonth", "2022-07")
                    .addString("startDate", "2022-07-01")
                    .addString("endDate", "2022-07-31")
                    .addString("correlationId", "corr-001")
                    .toJobParameters();

            // Act & Assert — no exception; the validator is lenient about
            // unknown optional parameters and accepts the full standard set
            assertThatCode(() -> standardJobParametersValidator.validate(params))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts minimal parameters when only batchRunId is supplied")
        void validatorAcceptsMinimalParameters() {
            // Arrange — only the required batchRunId; correlationId,
            // businessDate, etc. are intentionally absent. The
            // BatchJobConfig.extractCorrelationId helper synthesises a
            // fresh UUID for correlationId when absent (see §0.6.3), so
            // the validator must NOT require correlationId.
            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "minimal-run")
                    .toJobParameters();

            // Act & Assert
            assertThatCode(() -> standardJobParametersValidator.validate(params))
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // Nested test group #2 — Incrementer
    // =========================================================================

    /**
     * Test group for
     * {@code BatchJobConfig.cardDemoJobParametersIncrementer}. Confirms
     * the incrementer is a {@link RunIdIncrementer} (preserving the
     * COBOL JES2 JCT monotonic run-number semantics) and that its
     * {@code getNext} contract is honoured: first call adds
     * {@code run.id=1}; subsequent calls increment by 1.
     */
    @Nested
    @DisplayName("cardDemoJobParametersIncrementer — Preserves COBOL JES2 run-id semantics")
    class Incrementer {

        @Test
        @DisplayName("is an instance of RunIdIncrementer (preserves COBOL run-id semantics)")
        void incrementerIsRunIdIncrementer() {
            // Act & Assert — the bean must be a RunIdIncrementer so that
            // Spring Batch tests can rely on the run.id parameter being
            // named "run.id" and being of type Long (per Spring Batch
            // RunIdIncrementer documentation).
            assertThat(cardDemoJobParametersIncrementer)
                    .isInstanceOf(RunIdIncrementer.class);
        }

        @Test
        @DisplayName("getNext(empty) adds run.id=1 on first invocation")
        void incrementerAddsRunIdParameterOnFirstCall() {
            // Arrange — no prior JobParameters (mimics first launch
            // after a fresh BATCH_JOB_INSTANCE table)
            JobParameters empty = new JobParameters();

            // Act
            JobParameters incremented = cardDemoJobParametersIncrementer.getNext(empty);

            // Assert — RunIdIncrementer's documented contract: starts
            // at 1 when no prior run.id is present
            assertThat(incremented).isNotNull();
            assertThat(incremented.getLong("run.id"))
                    .as("First call to getNext() must add run.id=1")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("getNext(run.id=5) returns parameters with run.id=6")
        void incrementerIncrementsExistingRunId() {
            // Arrange — JobParameters with a prior run.id=5
            JobParameters previous = new JobParametersBuilder()
                    .addLong("run.id", 5L)
                    .toJobParameters();

            // Act
            JobParameters next = cardDemoJobParametersIncrementer.getNext(previous);

            // Assert — RunIdIncrementer increments by exactly 1
            assertThat(next).isNotNull();
            assertThat(next.getLong("run.id"))
                    .as("getNext(run.id=5) must return run.id=6")
                    .isEqualTo(6L);
        }

        @Test
        @DisplayName("getNext is idempotent in shape (every call returns a JobParameters with run.id)")
        void incrementerAlwaysProducesRunId() {
            // Arrange
            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "incrementer-test")
                    .toJobParameters();

            // Act — invoke getNext repeatedly to confirm the run.id
            // parameter is present after every invocation, regardless
            // of starting parameter shape
            JobParameters next1 = cardDemoJobParametersIncrementer.getNext(params);
            JobParameters next2 = cardDemoJobParametersIncrementer.getNext(next1);
            JobParameters next3 = cardDemoJobParametersIncrementer.getNext(next2);

            // Assert — run.id is monotonically increasing across calls
            assertThat(next1.getLong("run.id")).isEqualTo(1L);
            assertThat(next2.getLong("run.id")).isEqualTo(2L);
            assertThat(next3.getLong("run.id")).isEqualTo(3L);

            // Original batchRunId is preserved alongside the incremented run.id
            assertThat(next3.getString("batchRunId"))
                    .as("Original parameters must be preserved by RunIdIncrementer")
                    .isEqualTo("incrementer-test");
        }
    }

    // =========================================================================
    // Nested test group #3 — AuditListener
    // =========================================================================

    /**
     * Test group for
     * {@code BatchJobConfig.sharedAuditJobExecutionListener(AuditLogService)}.
     * Confirms that the listener invokes
     * {@link AuditLogService#logBatchJobLifecycle(String, String, String, Long, Map, String)}
     * on {@code beforeJob} (status {@code "STARTED"}) and
     * {@code afterJob} (status {@code "COMPLETED"} / {@code "FAILED"} /
     * etc., from {@link BatchStatus#name()}) lifecycle callbacks, with
     * the verbatim job name, execution ID, status, duration, and
     * correlation ID extracted from the {@link JobExecution} &mdash;
     * matching the contract documented at
     * {@link AuditLogService#logBatchJobLifecycle}.
     */
    @Nested
    @DisplayName("sharedAuditJobExecutionListener — Emits lifecycle events to AuditLogService")
    class AuditListener {

        @Test
        @DisplayName("the listener bean is non-null and wired into the Spring context")
        void listenerBeanExists() {
            // Act & Assert — Spring DI must produce a non-null listener
            // (the @Bean method is annotated and returns a non-null
            // anonymous inner class instance)
            assertThat(sharedAuditJobExecutionListener).isNotNull();
        }

        @Test
        @DisplayName("beforeJob invokes AuditLogService.logBatchJobLifecycle with status STARTED, durationMillis=0L")
        void beforeJob_invokesAuditLogServiceWithStartedStatus() {
            // Arrange — construct a minimal JobExecution with a known
            // correlationId so we can verify the correlation key is
            // propagated unchanged
            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "audit-test-before")
                    .addString("correlationId", "corr-before-001")
                    .toJobParameters();
            JobInstance jobInstance = new JobInstance(1L, "auditTestJobBefore");
            JobExecution execution = new JobExecution(jobInstance, 11L, params);
            execution.setStatus(BatchStatus.STARTING);

            // Act
            sharedAuditJobExecutionListener.beforeJob(execution);

            // Assert — the listener must call logBatchJobLifecycle with:
            //   jobName = "auditTestJobBefore"
            //   executionId = "11" (String.valueOf(11L))
            //   status = "STARTED" (literal, NOT BatchStatus.STARTING.name())
            //   durationMillis = 0L (no startTime/endTime set)
            //   payload = null
            //   correlationId = "corr-before-001" (verbatim from JobParameters)
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq("auditTestJobBefore"),
                    eq("11"),
                    eq("STARTED"),
                    eq(0L),
                    isNull(),
                    eq("corr-before-001"));
        }

        @Test
        @DisplayName("afterJob invokes AuditLogService.logBatchJobLifecycle with status COMPLETED")
        void afterJob_invokesAuditLogServiceWithCompletedStatus() {
            // Arrange — happy-path completed JobExecution
            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "audit-test-completed")
                    .addString("correlationId", "corr-completed-001")
                    .toJobParameters();
            JobInstance jobInstance = new JobInstance(2L, "auditTestJobCompleted");
            JobExecution execution = new JobExecution(jobInstance, 22L, params);
            execution.setStatus(BatchStatus.COMPLETED);
            execution.setExitStatus(ExitStatus.COMPLETED);

            // Act
            sharedAuditJobExecutionListener.afterJob(execution);

            // Assert — status is BatchStatus.COMPLETED.name() = "COMPLETED"
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq("auditTestJobCompleted"),
                    eq("22"),
                    eq("COMPLETED"),
                    eq(0L),
                    isNull(),
                    eq("corr-completed-001"));
        }

        @Test
        @DisplayName("afterJob invokes AuditLogService.logBatchJobLifecycle with status FAILED")
        void afterJob_invokesAuditLogServiceWithFailedStatus() {
            // Arrange — failed JobExecution
            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "audit-test-failed")
                    .addString("correlationId", "corr-failed-001")
                    .toJobParameters();
            JobInstance jobInstance = new JobInstance(3L, "auditTestJobFailed");
            JobExecution execution = new JobExecution(jobInstance, 33L, params);
            execution.setStatus(BatchStatus.FAILED);
            execution.setExitStatus(ExitStatus.FAILED);

            // Act
            sharedAuditJobExecutionListener.afterJob(execution);

            // Assert
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq("auditTestJobFailed"),
                    eq("33"),
                    eq("FAILED"),
                    eq(0L),
                    isNull(),
                    eq("corr-failed-001"));
        }

        @Test
        @DisplayName("afterJob invokes AuditLogService.logBatchJobLifecycle with status STOPPED")
        void afterJob_invokesAuditLogServiceWithStoppedStatus() {
            // Arrange — stopped JobExecution (e.g., operator-triggered
            // STOP via Spring Batch JobOperator)
            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "audit-test-stopped")
                    .addString("correlationId", "corr-stopped-001")
                    .toJobParameters();
            JobInstance jobInstance = new JobInstance(4L, "auditTestJobStopped");
            JobExecution execution = new JobExecution(jobInstance, 44L, params);
            execution.setStatus(BatchStatus.STOPPED);
            execution.setExitStatus(ExitStatus.STOPPED);

            // Act
            sharedAuditJobExecutionListener.afterJob(execution);

            // Assert — status name is BatchStatus.STOPPED.name() = "STOPPED"
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq("auditTestJobStopped"),
                    eq("44"),
                    eq("STOPPED"),
                    eq(0L),
                    isNull(),
                    eq("corr-stopped-001"));
        }

        @Test
        @DisplayName("afterJob computes non-zero duration when startTime and endTime are set")
        void afterJob_computesDurationFromStartEndTimes() {
            // Arrange — deterministic 5-second elapsed time
            LocalDateTime startTime = LocalDateTime.of(2023, 7, 1, 12, 0, 0);
            LocalDateTime endTime = LocalDateTime.of(2023, 7, 1, 12, 0, 5);

            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "audit-test-duration")
                    .addString("correlationId", "corr-duration-001")
                    .toJobParameters();
            JobInstance jobInstance = new JobInstance(5L, "auditTestJobDuration");
            JobExecution execution = new JobExecution(jobInstance, 55L, params);
            execution.setStatus(BatchStatus.COMPLETED);
            execution.setExitStatus(ExitStatus.COMPLETED);
            execution.setStartTime(startTime);
            execution.setEndTime(endTime);

            // Act
            sharedAuditJobExecutionListener.afterJob(execution);

            // Assert — duration must be exactly 5000 milliseconds
            // (Duration.between(start, end).toMillis() for a 5-second
            // gap = 5000L); the helper's Math.max(0L, millis) clamp
            // does not affect this positive value.
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq("auditTestJobDuration"),
                    eq("55"),
                    eq("COMPLETED"),
                    eq(5000L),
                    isNull(),
                    eq("corr-duration-001"));
        }

        @Test
        @DisplayName("beforeJob synthesises a non-blank correlationId when JobParameters omits it")
        void beforeJob_synthesisesCorrelationIdWhenAbsent() {
            // Arrange — NO correlationId parameter; extractCorrelationId
            // must synthesise a fresh UUID per BatchJobConfig javadoc
            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "audit-test-no-corr")
                    .toJobParameters();
            JobInstance jobInstance = new JobInstance(6L, "auditTestJobNoCorrelation");
            JobExecution execution = new JobExecution(jobInstance, 66L, params);
            execution.setStatus(BatchStatus.STARTING);

            // Act
            sharedAuditJobExecutionListener.beforeJob(execution);

            // Assert — capture the correlationId argument and assert it
            // is non-null, non-blank (the synthesised UUID)
            ArgumentCaptor<String> correlationIdCaptor = ArgumentCaptor.forClass(String.class);
            verify(auditLogService, atLeastOnce()).logBatchJobLifecycle(
                    eq("auditTestJobNoCorrelation"),
                    eq("66"),
                    eq("STARTED"),
                    eq(0L),
                    isNull(),
                    correlationIdCaptor.capture());

            String capturedCorrelationId = correlationIdCaptor.getValue();
            assertThat(capturedCorrelationId)
                    .as("Listener must synthesise a non-null correlationId when absent")
                    .isNotNull()
                    .isNotBlank();
        }

        @Test
        @DisplayName("beforeJob and afterJob can be invoked sequentially, producing exactly two audit emissions")
        void beforeJobAfterJob_invokeAuditLogServiceTwice() {
            // Arrange — single JobExecution exercised through full
            // lifecycle (start -> complete)
            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "audit-test-full-lifecycle")
                    .addString("correlationId", "corr-lifecycle-001")
                    .toJobParameters();
            JobInstance jobInstance = new JobInstance(7L, "auditTestJobLifecycle");
            JobExecution execution = new JobExecution(jobInstance, 77L, params);

            // Act — beforeJob then afterJob
            execution.setStatus(BatchStatus.STARTING);
            sharedAuditJobExecutionListener.beforeJob(execution);

            execution.setStatus(BatchStatus.COMPLETED);
            execution.setExitStatus(ExitStatus.COMPLETED);
            sharedAuditJobExecutionListener.afterJob(execution);

            // Assert — exactly TWO emissions: one STARTED, one COMPLETED.
            // Both must carry the same correlationId so the two audit
            // documents can be joined in OpenSearch (per AAP §0.6.6).
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq("auditTestJobLifecycle"),
                    eq("77"),
                    eq("STARTED"),
                    eq(0L),
                    isNull(),
                    eq("corr-lifecycle-001"));

            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq("auditTestJobLifecycle"),
                    eq("77"),
                    eq("COMPLETED"),
                    eq(0L),
                    isNull(),
                    eq("corr-lifecycle-001"));

            // Sanity: no other AuditLogService methods were called.
            // Note: logBatchJobLifecycle was called twice (once per
            // status); other public methods (logTransactionEvent,
            // logAuditEvent, logSecurityEvent) must not be invoked.
            verify(auditLogService, never()).logTransactionEvent(
                    anyString(), any(), anyString(), anyString(), any(), any(), any());
            verify(auditLogService, never()).logAuditEvent(
                    anyString(), any(), any(), anyString(), any(), any());
            verify(auditLogService, never()).logSecurityEvent(
                    anyString(), anyString(), anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("listener tolerates JobExecution whose endTime is before startTime (clamps duration to 0L)")
        void afterJob_clampsNegativeDurationToZero() {
            // Arrange — pathological case: endTime BEFORE startTime
            // (should never happen in production but the helper's
            // Math.max(0L, millis) clamp protects against it)
            LocalDateTime startTime = LocalDateTime.of(2023, 7, 1, 12, 0, 10);
            LocalDateTime endTime = LocalDateTime.of(2023, 7, 1, 12, 0, 5);

            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "audit-test-clamp")
                    .addString("correlationId", "corr-clamp-001")
                    .toJobParameters();
            JobInstance jobInstance = new JobInstance(8L, "auditTestJobClamp");
            JobExecution execution = new JobExecution(jobInstance, 88L, params);
            execution.setStatus(BatchStatus.COMPLETED);
            execution.setExitStatus(ExitStatus.COMPLETED);
            execution.setStartTime(startTime);
            execution.setEndTime(endTime);

            // Act
            sharedAuditJobExecutionListener.afterJob(execution);

            // Assert — duration is clamped to 0L (never negative)
            verify(auditLogService, atLeastOnce()).logBatchJobLifecycle(
                    eq("auditTestJobClamp"),
                    eq("88"),
                    eq("COMPLETED"),
                    eq(0L),
                    isNull(),
                    eq("corr-clamp-001"));
        }

        @Test
        @DisplayName("afterJob handles null exitStatus gracefully (no NPE; emits status from BatchStatus.name())")
        void afterJob_toleratesNullExitStatus() {
            // Arrange — exitStatus is not explicitly set so default
            // ExitStatus.UNKNOWN applies; status is UNKNOWN.
            JobParameters params = new JobParametersBuilder()
                    .addString("batchRunId", "audit-test-unknown")
                    .addString("correlationId", "corr-unknown-001")
                    .toJobParameters();
            JobInstance jobInstance = new JobInstance(9L, "auditTestJobUnknown");
            JobExecution execution = new JobExecution(jobInstance, 99L, params);
            execution.setStatus(BatchStatus.UNKNOWN);
            // intentionally do NOT call setExitStatus — exercise the
            // BatchJobConfig defensive null-guard path

            // Act & Assert — must not throw despite the UNKNOWN status
            assertThatCode(() -> sharedAuditJobExecutionListener.afterJob(execution))
                    .doesNotThrowAnyException();

            verify(auditLogService, atLeastOnce()).logBatchJobLifecycle(
                    eq("auditTestJobUnknown"),
                    eq("99"),
                    eq("UNKNOWN"),
                    anyLong(),
                    isNull(),
                    eq("corr-unknown-001"));
        }
    }
}
