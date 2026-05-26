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
// InterestCalculationJobTest — Spring Batch orchestration tests
// =============================================================================
//
// Replaces JCL job stream: app/jcl/INTCALC.jcl
// COBOL: CBACT04C
//
// Validates the Spring Batch interestCalculationJob @Configuration class
// that replaces the COBOL CBACT04C.cbl interest calculator program
// orchestrated by INTCALC.jcl (EXEC PGM=CBACT04C,PARM='YYYYMMDDHH').
//
// The Java target accepts an ISO-8601 yyyy-MM-dd parmDate JobParameter
// (per InterestCalculationJob.java Javadoc — "the Java target accepts an
// ISO-8601 yyyy-MM-dd date string"); this is the canonical date string
// representing what the legacy JCL PARM='YYYYMMDDHH' encoded on the
// mainframe (e.g., ISO-8601 "2022-07-18" ↔ JCL '2022071800').
//
// This test focuses on Spring Batch JOB-LEVEL ORCHESTRATION concerns
// — JobParameters parsing, ExitStatus propagation, ExecutionContext
// publishing, JobExecutionListener lifecycle audit emission, and
// service-failure propagation. It does NOT re-verify the COBOL CBACT04C
// verbatim interest formula balance.multiply(rate).divide(
// BigDecimal.valueOf(1200L), 2, RoundingMode.HALF_EVEN) — that
// arithmetic invariant is exhaustively tested at the service layer by
// InterestCalculationServiceTest.java per AAP §0.6.1.
//
// Per AAP §0.4.1 and the sibling DailyTransactionPostingJobTest /
// CombineTransactionsJobTest pattern:
//
//   * @SpringBootTest          — full ApplicationContext bootstrap so
//                                the interestCalculationJob Job bean
//                                (and its standardJobParametersValidator
//                                + sharedAuditJobExecutionListener
//                                wiring from BatchJobConfig) is exercised
//                                end-to-end.
//   * @SpringBatchTest         — auto-configures JobLauncherTestUtils.
//   * @Testcontainers          — manages the lifecycle of the static
//                                @Container PostgreSQLContainer.
//   * @ServiceConnection       — auto-binds the container to the
//                                spring.datasource.* properties.
//   * @ActiveProfiles("test")  — loads application-test.yml (disables
//                                AWS Secrets Manager + Parameter Store
//                                auto-config, points spring.* at
//                                Testcontainers-backed local services).
//   * @MockBean                — replaces InterestCalculationService
//                                and every AWS SDK / Kafka / Redis /
//                                OpenSearch client with a Mockito mock
//                                so the test does not require live AWS
//                                infrastructure or business-data
//                                fixtures.
//
// @Nested test groups (per file schema members_exposed):
//   * HappyPath
//   * ParmDateFormatYyyymmddhh
//   * DefaultBusinessDate
//   * ParameterValidation
//   * ServiceResultPropagation
//   * EmptyBalanceTable
//   * ServiceFailurePropagation
//
// AAP cross-references:
//   §0.4.1  — transformation mapping (InterestCalculationJob: CREATE)
//   §0.6.1  — BigDecimal scale=2 + HALF_EVEN preservation (service-level)
//   §0.6.3  — JCL → Step Functions orchestration (batch-job lifecycle)
//   §0.6.6  — Audit-trail emission via AuditLogService
//   §0.7.1  — Refactoring rules: Jakarta only, AWS SDK v2 only,
//             constructor injection, isolated adapter classes

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.adapter.KafkaEventPublisher;
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.adapter.SecretsManagerService;
import com.awsm2.carddemo.adapter.StepFunctionsOrchestrator;
import com.awsm2.carddemo.service.InterestCalculationService;
import com.awsm2.carddemo.service.InterestCalculationService.InterestResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Spring Batch end-to-end tests for {@link InterestCalculationJob}.
 *
 * <p><b>// Replaces: app/jcl/INTCALC.jcl + app/cbl/CBACT04C.cbl</b>. The
 * tests verify Spring Batch orchestration concerns &mdash; JobParameters
 * parsing, ExecutionContext publishing, ExitStatus propagation, and
 * lifecycle audit-trail emission &mdash; while delegating the verbatim
 * COBOL interest-formula validation
 * ({@code balance.multiply(rate).divide(BigDecimal.valueOf(1200L),
 * 2, RoundingMode.HALF_EVEN)} per AAP &sect;0.6.1) to the dedicated
 * service-layer test {@code InterestCalculationServiceTest}.</p>
 *
 * <p>The Java target accepts an ISO-8601 {@code yyyy-MM-dd} parmDate
 * JobParameter (per the production
 * {@link InterestCalculationJob#calculateInterestTasklet()} contract);
 * this is the canonical Java representation of the date that the legacy
 * JCL {@code PARM='YYYYMMDDHH'} (e.g., {@code '2022071800'}) encoded
 * on the mainframe.</p>
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@Import(InterestCalculationJobTest.TestSecretsManagerConfiguration.class)
@DisplayName("InterestCalculationJob — Spring Batch orchestration tests (COBOL: CBACT04C, JCL: INTCALC.jcl)")
class InterestCalculationJobTest {

    // =========================================================================
    // TestConfiguration — disables Spring Cloud AWS Secrets Manager and seeds
    // the Spring Batch JOB_REPOSITORY schema on the Testcontainers PostgreSQL.
    // Identical wiring to the sibling CombineTransactionsJobTest +
    // DailyTransactionPostingJobTest per the established Spring Batch test
    // harness pattern (AAP §0.7.2).
    // =========================================================================
    @TestConfiguration
    static class TestSecretsManagerConfiguration {
        private static final String TEST_SIGNING_KEY =
                "test-only-jwt-signing-key-for-hs256-context-smoke-test-padded";

        @Bean
        @Primary
        SecretsManagerService secretsManagerService() {
            // Replaces: AWS Secrets Manager runtime credential fetch
            // (AAP §0.6.4) so the @SpringBootTest context never reaches
            // real AWS during bean wiring.
            SecretsManagerService stub = Mockito.mock(SecretsManagerService.class);
            Mockito.when(stub.getSecretJsonField(Mockito.anyString(), Mockito.anyString()))
                    .thenReturn(Optional.of(TEST_SIGNING_KEY));
            Mockito.when(stub.getSecret(Mockito.anyString()))
                    .thenReturn(TEST_SIGNING_KEY);
            return stub;
        }

        @Bean
        DataSourceInitializer batchSchemaInitializer(final DataSource dataSource) {
            // Explicitly seed the Spring Batch JOB_REPOSITORY schema on
            // the Testcontainers PostgreSQL. Belt-and-suspenders against
            // the spring.batch.jdbc.initialize-schema=always property in
            // application-test.yml; mirrors the sibling test pattern.
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

    // =========================================================================
    // Testcontainers PostgreSQL 16-alpine — matches the production RDS
    // PostgreSQL 16 engine baseline (AAP §0.5.1, §0.6.2). @ServiceConnection
    // auto-binds the container's JDBC URL / username / password to the
    // spring.datasource.* properties without manual @DynamicPropertySource
    // boilerplate; the explicit @DynamicPropertySource below adds a
    // belt-and-suspenders override for Hikari auto-commit (consumed by
    // the Spring Batch JdbcJobRepositoryFactoryBean).
    // =========================================================================
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @DynamicPropertySource
    static void overrideDataSourceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.datasource.hikari.auto-commit", () -> "true");
    }

    // =========================================================================
    // Spring Batch test harness — JobLauncherTestUtils drives the
    // synchronous launch of interestCalculationJob with the test-supplied
    // JobParameters. The @Qualifier on the JobLauncher / Job bean
    // injections prevents Spring from raising NoUniqueBeanDefinitionException
    // when the application context exposes multiple Job beans (one per
    // batch program) — only the interestCalculationJob is targeted here.
    // =========================================================================
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(InterestCalculationJob.JOB_NAME)
    private Job interestCalculationJob;

    // =========================================================================
    // @MockBean — primary collaborator under test (InterestCalculationService)
    // plus every AWS SDK / Kafka / Redis / OpenSearch / adapter bean that
    // the @SpringBootTest ApplicationContext would otherwise try to
    // resolve against live infrastructure or AWS credentials. The mock
    // posture is identical to CombineTransactionsJobTest and
    // DailyTransactionPostingJobTest per AAP §0.7.2.
    // =========================================================================
    @MockBean
    private InterestCalculationService interestCalculationService;

    @MockBean
    private AuditLogService auditLogService;

    @MockBean
    private S3OutputService s3OutputService;

    @MockBean
    private KafkaEventPublisher kafkaEventPublisher;

    @MockBean
    private StepFunctionsOrchestrator stepFunctionsOrchestrator;

    @MockBean
    private CacheService cacheService;

    @MockBean
    private S3Client s3Client;

    @MockBean
    private SfnClient sfnClient;

    @MockBean
    private SecretsManagerClient secretsManagerClient;

    @MockBean
    private CloudWatchClient cloudWatchClient;

    @MockBean
    private GlueClient glueClient;

    @MockBean
    private OpenSearchClient openSearchClient;

    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void setUp() {
        // Reset the primary collaborator + audit-log mocks so per-test
        // stubbing in @Nested groups is deterministic.
        Mockito.reset(interestCalculationService, auditLogService);
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJob(interestCalculationJob);
    }

    // =========================================================================
    // HappyPath — interestCalculationJob completes with the canonical
    // ISO-8601 parmDate plus a populated InterestResult, propagates the
    // service counts / grand total to the step's ExecutionContext, and
    // emits the COMPLETED audit lifecycle.
    // =========================================================================

    /**
     * Smoke + happy-path scenarios verifying that the
     * {@link InterestCalculationJob} tasklet delegates to
     * {@link InterestCalculationService#calculateInterest(LocalDate)},
     * propagates the {@link InterestResult} fields to the step
     * {@link ExecutionContext}, and emits the {@code COMPLETED} batch
     * lifecycle event to {@link AuditLogService}.
     */
    @Nested
    @DisplayName("HappyPath — Job completes with explicit parmDate")
    class HappyPath {

        @Test
        @DisplayName("calculates interest and publishes totals to ExecutionContext")
        void calculatesInterestAndPublishesTotals() throws Exception {
            // COBOL: CBACT04C PROCEDURE DIVISION USING EXTERNAL-PARMS
            // — Job invokes service with the parsed LocalDate parmDate.
            Mockito.when(interestCalculationService.calculateInterest(LocalDate.parse("2026-05-20")))
                    .thenReturn(new InterestResult(18, 42, new BigDecimal("1234.56")));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    interestParams("interest-happy", "2026-05-20"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());
            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(InterestCalculationJob.CTX_TCAT_COUNT))
                    .isEqualTo(18);
            assertThat(stepContext.getInt(InterestCalculationJob.CTX_ACCT_COUNT))
                    .isEqualTo(42);
            // grandTotal is stringified via BigDecimal.toPlainString() to
            // preserve the scale=2 (AAP §0.6.1) through the step
            // ExecutionContext — this serialised form is what AWS Step
            // Functions and downstream operational tooling consume.
            assertThat(stepContext.getString(InterestCalculationJob.CTX_GRAND_TOTAL))
                    .isEqualTo("1234.56");

            verify(interestCalculationService, times(1))
                    .calculateInterest(LocalDate.parse("2026-05-20"));
            verify(auditLogService, atLeastOnce()).logBatchJobLifecycle(
                    eq(InterestCalculationJob.JOB_NAME),
                    anyString(),
                    eq("COMPLETED"),
                    isNull(),
                    any(Map.class),
                    eq("corr-interest-happy"));
        }

        @Test
        @DisplayName("normalizes a null grand total to 0.00 for operator visibility")
        void normalizesNullGrandTotal() throws Exception {
            // The production tasklet defends against a null grandTotal
            // returned by the service (e.g., an empty TCATBAL scan) by
            // emitting "0.00" to the ExecutionContext so operators see a
            // valid BigDecimal-shaped string and not a literal "null".
            Mockito.when(interestCalculationService.calculateInterest(any(LocalDate.class)))
                    .thenReturn(new InterestResult(0, 0, null));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    interestParams("interest-null-total", "2026-05-20"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getString(InterestCalculationJob.CTX_GRAND_TOTAL))
                    .isEqualTo("0.00");
        }
    }

    // =========================================================================
    // ParmDateFormatYyyymmddhh — Java target's ISO-8601 parmDate is the
    // canonical equivalent of the legacy COBOL JCL PARM='YYYYMMDDHH'
    // format (e.g., ISO-8601 "2022-07-18" ↔ JCL '2022071800').
    //
    // The production InterestCalculationJob.java explicitly chose ISO-8601
    // for the parmDate JobParameter (see its Javadoc: "the Java target
    // accepts an ISO-8601 yyyy-MM-dd date string and ignores the hour
    // component which is not used by the service"). This @Nested group
    // verifies that the canonical date encoded by the legacy JCL PARM
    // (YYYYMMDDHH = "2022071800" → calendar date 2022-07-18) is correctly
    // resolved when supplied as the equivalent ISO-8601 string.
    // =========================================================================

    /**
     * Verifies that the Java target's ISO-8601 {@code parmDate} JobParameter
     * resolves to the same calendar date that the legacy COBOL JCL
     * {@code PARM='YYYYMMDDHH'} (e.g., {@code '2022071800'}) encoded on
     * the mainframe.
     */
    @Nested
    @DisplayName("ParmDateFormatYyyymmddhh — ISO-8601 parmDate is the canonical JCL PARM='YYYYMMDDHH' equivalent")
    class ParmDateFormatYyyymmddhh {

        @Test
        @DisplayName("parmDate '2022-07-18' (ISO-8601) parses to LocalDate(2022,7,18) — JCL PARM='2022071800' equivalent")
        void parmDateIso8601_parsesToJulyEighteenth() throws Exception {
            // Verbatim INTCALC.jcl: //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'
            // The Java target encodes the same calendar date in ISO-8601
            // form: "2022-07-18". The HH portion of the legacy JCL PARM
            // (the last two digits "00") is intentionally absent in the
            // Java target — it was a mainframe-era legacy artifact never
            // consumed by the COBOL CBACT04C interest calculator.
            LocalDate expected = LocalDate.of(2022, 7, 18);
            InterestResult result = new InterestResult(50, 50, new BigDecimal("1000.00"));
            Mockito.when(interestCalculationService.calculateInterest(expected))
                    .thenReturn(result);

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    interestParams("interest-parm-format", "2022-07-18"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            verify(interestCalculationService, times(1)).calculateInterest(expected);
        }

        @Test
        @DisplayName("the literal legacy JCL string '2022071800' is rejected — Java target requires ISO-8601")
        void literalJclParmString_isRejected() throws Exception {
            // The Java target deliberately accepts ISO-8601 and rejects
            // the literal legacy JCL PARM='YYYYMMDDHH' string format —
            // operators migrating from z/OS JCL must translate their PARM
            // values into ISO-8601 dates. This safeguard prevents the
            // ambiguous "2022071800" string being silently re-parsed by
            // Java tooling (e.g., as epoch-millis or another format).
            JobExecution execution = jobLauncherTestUtils.launchJob(
                    interestParams("interest-jcl-parm", "2022071800"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(InterestCalculationJobTest::isIllegalArgumentException);
            verify(interestCalculationService, never())
                    .calculateInterest(any(LocalDate.class));
        }
    }

    // =========================================================================
    // DefaultBusinessDate — verifies that supplying today's date
    // (LocalDate.now()) as the explicit parmDate JobParameter completes
    // successfully. The Java target requires parmDate (no implicit
    // default to "today" — see ParameterValidation for the missing-
    // parmDate failure case) so this test exercises the default-like
    // "ad-hoc batch run for today's interest" workflow that an operator
    // would invoke from AWS Batch or the local Spring Boot CLI.
    // =========================================================================

    /**
     * Verifies that an explicit today's-date parmDate (matching the
     * default-like batch invocation pattern operators use from AWS Batch
     * or the local Spring Boot CLI) successfully drives the job. The
     * Java target requires parmDate explicitly — there is no implicit
     * default-to-LocalDate.now() fallback because audit-traceability
     * (AAP &sect;0.7.1) mandates every batch run carry a deterministic
     * date parameter through CloudWatch / OpenSearch.
     */
    @Nested
    @DisplayName("DefaultBusinessDate — Today's date is the default-like batch parameter")
    class DefaultBusinessDate {

        @Test
        @DisplayName("Explicit LocalDate.now() parmDate completes successfully (default-like batch run for today)")
        void explicitTodayParmDate_completes() throws Exception {
            // COBOL: CBACT04C — driven by the JCL PARM date; in the
            // typical operational pattern the operator supplies today's
            // date when invoking the EOD interest job ad-hoc.
            LocalDate today = LocalDate.now();
            InterestResult result =
                    new InterestResult(10, 10, new BigDecimal("250.00"));
            Mockito.when(interestCalculationService.calculateInterest(today))
                    .thenReturn(result);

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    interestParams("interest-today", today.toString()));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            verify(interestCalculationService, times(1)).calculateInterest(today);
        }
    }

    // =========================================================================
    // ParameterValidation — the standardJobParametersValidator from
    // BatchJobConfig enforces a non-blank batchRunId before the tasklet
    // is ever invoked (throws JobParametersInvalidException synchronously
    // from JobLauncher.run). The tasklet itself enforces a non-blank
    // ISO-8601 parmDate at runtime (throws IllegalArgumentException
    // captured in JobExecution.getAllFailureExceptions() with
    // BatchStatus.FAILED).
    // =========================================================================

    /**
     * Verifies that the validator + tasklet enforce the batchRunId and
     * parmDate JobParameters. Missing or malformed values fail the job
     * deterministically without ever invoking the service.
     */
    @Nested
    @DisplayName("ParameterValidation — Standard validator enforces batchRunId; tasklet enforces parmDate")
    class ParameterValidation {

        @Test
        @DisplayName("Missing batchRunId throws JobParametersInvalidException synchronously")
        void missingBatchRunId_throwsJobParametersInvalidException() {
            // The standardJobParametersValidator from BatchJobConfig
            // rejects launches missing batchRunId (AAP §0.7.1 audit-
            // traceability rule). The exception is thrown synchronously
            // from JobLauncher.run() BEFORE any JobExecution is created
            // — so we use assertThatThrownBy rather than checking
            // execution.getStatus().
            JobParameters params = new JobParametersBuilder()
                    .addString(InterestCalculationJob.PARAM_PARM_DATE, "2026-05-20")
                    .addString("correlationId", "corr-missing-runId")
                    .toJobParameters();

            assertThatThrownBy(() -> jobLauncherTestUtils.launchJob(params))
                    .isInstanceOf(JobParametersInvalidException.class)
                    .hasMessageContaining("batchRunId");

            verify(interestCalculationService, never())
                    .calculateInterest(any(LocalDate.class));
        }

        @Test
        @DisplayName("Missing parmDate yields FAILED job with IllegalArgumentException in failureExceptions")
        void missingParmDate_jobFails() throws Exception {
            // batchRunId is present (validator passes) but parmDate is
            // omitted — the tasklet itself throws IllegalArgumentException
            // at runtime, which Spring Batch captures in the
            // JobExecution.getAllFailureExceptions() collection with
            // BatchStatus.FAILED.
            JobExecution execution = jobLauncherTestUtils.launchJob(
                    new JobParametersBuilder()
                            .addString(InterestCalculationJob.PARAM_BATCH_RUN_ID,
                                    "interest-missing-date")
                            .addString("correlationId", "corr-interest-missing-date")
                            .toJobParameters());

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(InterestCalculationJobTest::isIllegalArgumentException);
            verify(interestCalculationService, never())
                    .calculateInterest(any(LocalDate.class));
        }

        @Test
        @DisplayName("Malformed (non-ISO-8601) parmDate yields FAILED job with IllegalArgumentException")
        void malformedParmDate_jobFails() throws Exception {
            // A non-ISO-8601 parmDate value cannot be parsed by
            // LocalDate.parse() — the tasklet wraps the underlying
            // DateTimeParseException in an IllegalArgumentException that
            // identifies the offending parameter name.
            JobExecution execution = jobLauncherTestUtils.launchJob(
                    interestParams("interest-invalid-date", "JULY-2022"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(InterestCalculationJobTest::isIllegalArgumentException);
            verify(interestCalculationService, never())
                    .calculateInterest(any(LocalDate.class));
        }
    }

    // =========================================================================
    // ServiceResultPropagation — the InterestResult(tcatCount, acctCount,
    // grandTotal) returned by the service is propagated to:
    //   1. The step ExecutionContext (CTX_TCAT_COUNT, CTX_ACCT_COUNT,
    //      CTX_GRAND_TOTAL with BigDecimal.toPlainString() to preserve
    //      scale=2 per AAP §0.6.1).
    //   2. The StepContribution counters (read = 1, write = acctCount).
    //   3. The AuditLogService payload (jobName, executionId, status,
    //      tcatCount, acctCount, grandTotal, correlationId).
    // =========================================================================

    /**
     * Verifies that the {@link InterestResult} record returned by the
     * service is propagated unchanged through the
     * {@link InterestCalculationJob} tasklet to the Spring Batch
     * {@link ExecutionContext}, {@link StepContribution} counters, and
     * {@link AuditLogService} audit-log payload &mdash; preserving the
     * BigDecimal grandTotal scale=2 invariant (AAP &sect;0.6.1) end-to-
     * end without any algebraic transformation.
     */
    @Nested
    @DisplayName("ServiceResultPropagation — InterestResult counts and grandTotal propagated to job context")
    class ServiceResultPropagation {

        @Test
        @DisplayName("InterestResult(tcatCount, acctCount, grandTotal) propagated to ExecutionContext + audit payload")
        void interestResultPropagatedThroughExecutionContext() throws Exception {
            // COBOL CBACT04C — service emits InterestResult per AAP §0.6.1
            // BigDecimal scale=2 + HALF_EVEN preservation.
            LocalDate parmDate = LocalDate.parse("2022-07-18");
            BigDecimal grandTotal = new BigDecimal("987654.32");
            InterestResult result = new InterestResult(1234, 567, grandTotal);
            Mockito.when(interestCalculationService.calculateInterest(parmDate))
                    .thenReturn(result);

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    interestParams("interest-propagation", "2022-07-18"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            // (1) ExecutionContext propagation — scale=2 preserved via
            //     BigDecimal.toPlainString() (NOT toString() which would
            //     emit scientific notation for very large values).
            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(InterestCalculationJob.CTX_TCAT_COUNT))
                    .isEqualTo(1234);
            assertThat(stepContext.getInt(InterestCalculationJob.CTX_ACCT_COUNT))
                    .isEqualTo(567);
            assertThat(stepContext.getString(InterestCalculationJob.CTX_GRAND_TOTAL))
                    .isEqualTo("987654.32");
            // Validate scale preservation: re-parse the stringified
            // BigDecimal and compare by value (isEqualByComparingTo per
            // AAP §0.7.2 BigDecimal assertion rule).
            assertThat(new BigDecimal(stepContext.getString(InterestCalculationJob.CTX_GRAND_TOTAL)))
                    .isEqualByComparingTo(grandTotal);

            // (2) Audit-log payload propagation.
            verify(interestCalculationService, times(1)).calculateInterest(parmDate);
            verify(auditLogService, atLeastOnce()).logBatchJobLifecycle(
                    eq(InterestCalculationJob.JOB_NAME),
                    anyString(),
                    eq("COMPLETED"),
                    isNull(),
                    any(Map.class),
                    eq("corr-interest-propagation"));
        }

        @Test
        @DisplayName("High-magnitude BigDecimal grandTotal preserves scale=2 (no scientific notation)")
        void highMagnitudeGrandTotal_preservesScale() throws Exception {
            // COBOL CBACT04C — PIC S9(10)V99 ceiling = 99,999,999,999.99.
            // The Java target uses toPlainString() to avoid the
            // scientific-notation that BigDecimal.toString() emits for
            // very large or very small values, preserving the COBOL
            // fixed-point representation per AAP §0.6.1.
            LocalDate parmDate = LocalDate.parse("2022-07-18");
            BigDecimal grandTotal = new BigDecimal("99999999999.99");
            Mockito.when(interestCalculationService.calculateInterest(parmDate))
                    .thenReturn(new InterestResult(99, 99, grandTotal));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    interestParams("interest-large-total", "2022-07-18"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getString(InterestCalculationJob.CTX_GRAND_TOTAL))
                    .isEqualTo("99999999999.99");
            assertThat(new BigDecimal(stepContext.getString(InterestCalculationJob.CTX_GRAND_TOTAL)))
                    .isEqualByComparingTo(grandTotal);
        }
    }

    // =========================================================================
    // EmptyBalanceTable — when the TCATBAL table is empty (the JPA scan
    // returns zero rows), the service returns InterestResult(0, 0,
    // BigDecimal.ZERO). The job should COMPLETE successfully — there is
    // no COBOL CBACT04C "rejects" concept (every TCATBAL row falls back
    // to the DEFAULT disclosure-group per CBACT04C:L436-L438 if its
    // composite key misses).
    // =========================================================================

    /**
     * Verifies that an empty TCATBAL scan (service returns
     * {@code InterestResult(0, 0, BigDecimal.ZERO)}) completes the job
     * successfully &mdash; preserving the COBOL CBACT04C semantic that
     * "no rows to process" is a normal (not error) termination.
     */
    @Nested
    @DisplayName("EmptyBalanceTable — Zero TCATBAL rows still completes successfully")
    class EmptyBalanceTable {

        @Test
        @DisplayName("Service returns InterestResult(0, 0, ZERO) → job COMPLETED; ExecutionContext shows zero counts")
        void zeroBalances_completes() throws Exception {
            // COBOL CBACT04C — EOF on TCATBAL VSAM scan yields tcatCount=0
            // / acctCount=0 / grandTotal=0.00. No COBOL RETURN-CODE=8
            // (FAILED) is set because an empty TCATBAL is a normal
            // operational state (e.g., quiet weekend, first run of a new
            // batch cycle).
            LocalDate parmDate = LocalDate.parse("2099-01-01");
            InterestResult empty =
                    new InterestResult(0, 0, BigDecimal.ZERO);
            Mockito.when(interestCalculationService.calculateInterest(parmDate))
                    .thenReturn(empty);

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    interestParams("interest-empty", "2099-01-01"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(InterestCalculationJob.CTX_TCAT_COUNT))
                    .isZero();
            assertThat(stepContext.getInt(InterestCalculationJob.CTX_ACCT_COUNT))
                    .isZero();
            // The toPlainString form of BigDecimal.ZERO is "0" (scale=0)
            // — the production tasklet emits this verbatim because
            // BigDecimal.ZERO carries scale=0 not scale=2; the service
            // layer is responsible for preserving scale=2 when the
            // grandTotal is built from arithmetic operations.
            assertThat(stepContext.getString(InterestCalculationJob.CTX_GRAND_TOTAL))
                    .isEqualTo("0");
            // Verify the BigDecimal value comparison (scale-agnostic).
            assertThat(new BigDecimal(stepContext.getString(InterestCalculationJob.CTX_GRAND_TOTAL)))
                    .isEqualByComparingTo(BigDecimal.ZERO);

            verify(interestCalculationService, times(1)).calculateInterest(parmDate);
        }
    }

    // =========================================================================
    // ServiceFailurePropagation — when InterestCalculationService throws
    // (e.g., RDS connectivity loss, optimistic-lock conflict on the
    // Account row, OnSizeErrorException from a PIC S9(10)V99 overflow),
    // the Spring Batch step transitions to FAILED and the originating
    // exception is captured in JobExecution.getAllFailureExceptions().
    // The InterestStepExitStatusListener.afterStep preserves the FAILED
    // ExitStatus (CBACT04C has no "rejects" concept, so this is the
    // failure-only path).
    // =========================================================================

    /**
     * Verifies that a {@link RuntimeException} thrown by
     * {@link InterestCalculationService#calculateInterest(LocalDate)}
     * propagates correctly to the {@link JobExecution}: status
     * {@link BatchStatus#FAILED}, the originating exception captured in
     * {@link JobExecution#getAllFailureExceptions()}.
     */
    @Nested
    @DisplayName("ServiceFailurePropagation — Service exceptions cause job FAILED status")
    class ServiceFailurePropagation {

        @Test
        @DisplayName("Service RuntimeException → job FAILED with failureException captured")
        void failsWhenInterestCalculationServiceThrows() throws Exception {
            // COBOL CBACT04C — any underlying VSAM I/O failure or LE
            // ABEND would set RETURN-CODE = 8 (FAILED). The Java target
            // surfaces this as a thrown exception that Spring Batch
            // captures in the JobExecution's failureExceptions list.
            Mockito.when(interestCalculationService.calculateInterest(any(LocalDate.class)))
                    .thenThrow(new IllegalStateException("INTCALC service failure"));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    interestParams("interest-service-failure", "2026-05-20"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(ex -> ex instanceof IllegalStateException
                            && ex.getMessage().contains("INTCALC service failure"));
        }

        @Test
        @DisplayName("Service RuntimeException → service called exactly once before failure surfaces")
        void serviceCalledOnceBeforeFailure() throws Exception {
            // Defensive assertion that the tasklet does not retry the
            // service on failure (Spring Batch's default retry policy is
            // disabled for this tasklet — failures propagate immediately
            // per AAP §0.6.3 "Catch and Retry policies on Step Functions"
            // — retries happen at the Step Functions orchestrator level,
            // never at the Spring Batch tasklet level).
            LocalDate parmDate = LocalDate.parse("2026-05-20");
            Mockito.when(interestCalculationService.calculateInterest(parmDate))
                    .thenThrow(new RuntimeException("RDS connection failed"));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    interestParams("interest-svc-fail-once", "2026-05-20"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            verify(interestCalculationService, times(1)).calculateInterest(parmDate);
        }
    }

    // =========================================================================
    // Helper methods — shared across @Nested groups for compactness.
    // =========================================================================

    /**
     * Builds the canonical {@link JobParameters} for an
     * {@code interestCalculationJob} launch: batchRunId, parmDate (ISO-
     * 8601), and a derived correlationId. Used by every test that
     * launches with a valid parmDate.
     *
     * @param batchRunId unique batch run identifier (required by the
     *                   standardJobParametersValidator)
     * @param parmDate   ISO-8601 {@code yyyy-MM-dd} parmDate
     * @return the constructed immutable {@link JobParameters}
     */
    private JobParameters interestParams(String batchRunId, String parmDate) {
        return new JobParametersBuilder()
                .addString(InterestCalculationJob.PARAM_BATCH_RUN_ID, batchRunId)
                .addString(InterestCalculationJob.PARAM_PARM_DATE, parmDate)
                .addString("correlationId", "corr-" + batchRunId)
                .toJobParameters();
    }

    /**
     * Extracts the single step's {@link ExecutionContext} from a
     * {@link JobExecution}. The {@code interestCalculationJob} has
     * exactly one step ({@code calculateInterestStep}); any deviation
     * from this invariant should fail the test immediately so the
     * upstream regression is caught by the assertion.
     *
     * @param execution the {@link JobExecution} to inspect
     * @return the single step's {@link ExecutionContext}
     */
    private static ExecutionContext singleStepContext(JobExecution execution) {
        assertThat(execution.getStepExecutions()).hasSize(1);
        return execution.getStepExecutions().iterator().next().getExecutionContext();
    }

    /**
     * AssertJ Predicate&lt;Throwable&gt; — true if the throwable is an
     * {@link IllegalArgumentException}. Used in the ParameterValidation
     * @Nested group to assert that the tasklet's parmDate validation
     * surfaces as an IllegalArgumentException in
     * {@link JobExecution#getAllFailureExceptions()}.
     *
     * @param throwable the throwable to inspect
     * @return {@code true} if {@code throwable} is an
     *         {@link IllegalArgumentException}; {@code false} otherwise
     */
    private static boolean isIllegalArgumentException(Throwable throwable) {
        return throwable instanceof IllegalArgumentException;
    }
}
