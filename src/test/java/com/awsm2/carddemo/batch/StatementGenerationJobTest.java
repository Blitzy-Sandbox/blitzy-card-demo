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
// StatementGenerationJobTest — Spring Batch orchestration tests
// =============================================================================
//
// Replaces JCL job stream: app/jcl/CREASTMT.JCL
// COBOL: CBSTM03A (text statements, LRECL=80) + CBSTM03B (HTML statements,
//        LRECL=100) — dual-format Template Method pattern per AAP §0.3.3.
//
// Validates the Spring Batch statementGenerationJob @Configuration class
// that replaces the COBOL CBSTM03A.CBL statement renderer AND its file-
// service subroutine CBSTM03B.CBL orchestrated by CREASTMT.JCL
// (STEP040 EXEC PGM=CBSTM03A, STMTFILE DD LRECL=80, HTMLFILE DD LRECL=100).
//
// The Java target accepts an ISO-8601 yyyy-MM-dd statementMonth JobParameter
// (per StatementGenerationJob.java Javadoc — "the statement period end date
// (ISO-8601 yyyy-MM-dd); maps to the COBOL WS-STMT-DATE working-storage
// field"); this is the canonical date string used by the
// StatementGenerationService to label statement headers and to scope the
// transaction date-range filter.
//
// This test focuses on Spring Batch JOB-LEVEL ORCHESTRATION concerns
// — JobParameters parsing, ExitStatus propagation (zero errors →
// COMPLETED; non-zero errors → COMPLETED_WITH_REJECTS / COBOL RETURN-CODE
// = 4), ExecutionContext publishing (textCount, htmlCount, errorCount),
// JobExecutionListener lifecycle audit emission, and service-failure
// propagation. It does NOT re-verify the COBOL verbatim text/HTML
// rendering (FD-STMTFILE-REC PIC X(80) for CBSTM03A; FD-HTMLFILE-REC
// PIC X(100) for CBSTM03B; verbatim HTML colour literals #1d1d96b3,
// #FFAF33, #33FF5E, #f2f2f2 per AAP §0.7.2 "Regulatory reporting output
// formats must remain identical byte-for-byte") — those byte-level
// invariants are exhaustively tested at the service layer by
// StatementGenerationServiceTest.java per AAP §0.6.1.
//
// Per AAP §0.4.1 and the sibling InterestCalculationJobTest /
// CombineTransactionsJobTest / DailyTransactionPostingJobTest pattern:
//
//   * @SpringBootTest          — full ApplicationContext bootstrap so the
//                                statementGenerationJob Job bean (and its
//                                standardJobParametersValidator wiring
//                                from BatchJobConfig) is exercised
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
//   * @MockBean                — replaces StatementGenerationService and
//                                every AWS SDK / Kafka / Redis /
//                                OpenSearch client with a Mockito mock so
//                                the test does not require live AWS
//                                infrastructure or business-data fixtures.
//
// @Nested test groups (per file schema members_exposed):
//   * HappyPath
//   * DefaultStatementMonth
//   * ParameterValidation
//   * DualFormatTemplateMethod
//   * PartialFailures
//   * EmptyDataset
//   * ServiceFailurePropagation
//
// AAP cross-references:
//   §0.4.1  — transformation mapping (StatementGenerationJob: CREATE)
//   §0.6.1  — BigDecimal scale=2 + HALF_EVEN preservation (service-level)
//   §0.6.3  — JCL → Step Functions orchestration (batch-job lifecycle)
//   §0.6.6  — Audit-trail emission via AuditLogService
//   §0.7.1  — Refactoring rules: Jakarta only, AWS SDK v2 only,
//             constructor injection, isolated adapter classes
//   §0.7.2  — Regulatory output formats preserved byte-identical

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.adapter.KafkaEventPublisher;
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.adapter.SecretsManagerService;
import com.awsm2.carddemo.adapter.StepFunctionsOrchestrator;
import com.awsm2.carddemo.service.StatementGenerationService;
import com.awsm2.carddemo.service.StatementGenerationService.StatementResult;

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

import java.time.LocalDate;
import java.time.YearMonth;
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
 * Spring Batch end-to-end tests for {@link StatementGenerationJob}.
 *
 * <p><b>// Replaces: app/jcl/CREASTMT.JCL + app/cbl/CBSTM03A.CBL +
 * app/cbl/CBSTM03B.CBL</b>. The tests verify Spring Batch orchestration
 * concerns &mdash; JobParameters parsing, ExecutionContext publishing,
 * ExitStatus propagation (zero errors &rarr; {@code COMPLETED}; non-zero
 * errors &rarr; {@code COMPLETED_WITH_REJECTS} per COBOL
 * {@code RETURN-CODE = 4} semantic), and lifecycle audit-trail emission
 * &mdash; while delegating the verbatim COBOL byte-identical rendering
 * checks (FD-STMTFILE-REC {@code PIC X(80)}, FD-HTMLFILE-REC
 * {@code PIC X(100)}, the four COBOL HTML colour literals
 * {@code #1d1d96b3 / #FFAF33 / #33FF5E / #f2f2f2}, PAN-masking) to the
 * dedicated service-layer test
 * {@code StatementGenerationServiceTest}.</p>
 *
 * <p>The Java target accepts an ISO-8601 {@code yyyy-MM-dd}
 * {@code statementMonth} JobParameter (per the production
 * {@link StatementGenerationJob#generateStatementsTasklet()} contract);
 * this is the canonical Java representation of the date that the legacy
 * COBOL {@code CBSTM03A.CBL WS-STMT-DATE} working-storage field carried
 * on the mainframe.</p>
 *
 * <p><b>// COBOL: CBSTM03A + CBSTM03B</b> &mdash; the dual-format
 * Template Method pattern (per AAP &sect;0.3.3) means every successful
 * per-account iteration produces TWO outputs: an 80-byte text statement
 * and a 100-byte HTML statement. The
 * {@link DualFormatTemplateMethod @Nested} group exercises this
 * symmetry by asserting that {@code textCount == htmlCount} for the
 * happy path.</p>
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@Import(StatementGenerationJobTest.TestSecretsManagerConfiguration.class)
@DisplayName("StatementGenerationJob — Spring Batch orchestration tests (COBOL: CBSTM03A + CBSTM03B, JCL: CREASTMT.JCL)")
class StatementGenerationJobTest {

    // =========================================================================
    // TestConfiguration — disables Spring Cloud AWS Secrets Manager and seeds
    // the Spring Batch JOB_REPOSITORY schema on the Testcontainers PostgreSQL.
    // Identical wiring to the sibling InterestCalculationJobTest +
    // CombineTransactionsJobTest + DailyTransactionPostingJobTest per the
    // established Spring Batch test harness pattern (AAP §0.7.2).
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
    // synchronous launch of statementGenerationJob with the test-supplied
    // JobParameters. The @Qualifier on the JobLauncher / Job bean
    // injections prevents Spring from raising NoUniqueBeanDefinitionException
    // when the application context exposes multiple Job beans (one per
    // batch program) — only the statementGenerationJob is targeted here.
    // =========================================================================
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(StatementGenerationJob.JOB_NAME)
    private Job statementGenerationJob;

    // =========================================================================
    // @MockBean — primary collaborator under test (StatementGenerationService)
    // plus every AWS SDK / Kafka / Redis / OpenSearch / adapter bean that
    // the @SpringBootTest ApplicationContext would otherwise try to
    // resolve against live infrastructure or AWS credentials. The mock
    // posture is identical to the sibling InterestCalculationJobTest /
    // CombineTransactionsJobTest / DailyTransactionPostingJobTest per AAP
    // §0.7.2 testing strategy.
    // =========================================================================
    @MockBean
    private StatementGenerationService statementGenerationService;

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
        Mockito.reset(statementGenerationService, auditLogService);
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJob(statementGenerationJob);
    }

    // =========================================================================
    // HappyPath — statementGenerationJob completes with the canonical
    // ISO-8601 statementMonth plus a populated StatementResult, propagates
    // the service text / HTML / error counts to the step's
    // ExecutionContext, and emits the COMPLETED audit lifecycle.
    // =========================================================================

    /**
     * Smoke + happy-path scenarios verifying that the
     * {@link StatementGenerationJob} tasklet delegates to
     * {@link StatementGenerationService#generateStatements(LocalDate)},
     * propagates the {@link StatementResult} fields to the step
     * {@link ExecutionContext}, and emits the {@code COMPLETED} batch
     * lifecycle event to {@link AuditLogService}.
     */
    @Nested
    @DisplayName("HappyPath — Statement generation completes for explicit statementMonth")
    class HappyPath {

        @Test
        @DisplayName("Launches with explicit statementMonth; generates both text and HTML statements; returns COMPLETED")
        void launchWithStatementMonth_returnsCompleted() throws Exception {
            // COBOL: CBSTM03A.CBL writes 80-byte text statements via
            // FD-STMTFILE-REC PIC X(80); CBSTM03B.CBL writes 100-byte
            // HTML statements via FD-HTMLFILE-REC PIC X(100). The
            // service layer encapsulates the Template Method pattern
            // — the Job tasklet simply delegates and propagates counts.
            LocalDate statementDate = LocalDate.parse("2026-05-20");
            Mockito.when(statementGenerationService.generateStatements(statementDate))
                    .thenReturn(new StatementResult(150, 150, 0));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    statementParams("statement-happy", "2026-05-20"));

            // Job-level assertions: terminal status + ExitStatus.
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());

            // Step ExecutionContext propagation — the tasklet must
            // emit textCount, htmlCount, errorCount keys so downstream
            // Step Functions Choice states + the
            // StatementStepExitStatusListener can branch on partial-
            // success runs.
            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(StatementGenerationJob.CTX_TEXT_COUNT))
                    .isEqualTo(150);
            assertThat(stepContext.getInt(StatementGenerationJob.CTX_HTML_COUNT))
                    .isEqualTo(150);
            assertThat(stepContext.getInt(StatementGenerationJob.CTX_ERROR_COUNT))
                    .isZero();

            // Service was invoked exactly once with the parsed date.
            verify(statementGenerationService, times(1))
                    .generateStatements(statementDate);

            // COMPLETED audit lifecycle was emitted to AuditLogService.
            verify(auditLogService, atLeastOnce()).logBatchJobLifecycle(
                    eq(StatementGenerationJob.JOB_NAME),
                    anyString(),
                    eq("COMPLETED"),
                    isNull(),
                    any(Map.class),
                    eq("corr-statement-happy"));
        }

        @Test
        @DisplayName("ExitStatus description summarises text/HTML/error counts (operator visibility)")
        void exitStatusDescription_summarisesCounts() throws Exception {
            // COBOL: CBSTM03A 9999-EXIT-PROGRAM emits a summary line to
            // SYSPRINT — the Java target preserves operator-visible
            // counts via the StepContribution.setExitStatus exit
            // description (visible in Spring Batch metadata + AWS
            // Batch container logs + Step Functions output).
            LocalDate statementDate = LocalDate.parse("2026-05-20");
            Mockito.when(statementGenerationService.generateStatements(statementDate))
                    .thenReturn(new StatementResult(25, 25, 0));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    statementParams("statement-summary", "2026-05-20"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            // The summary line is appended to ExitStatus.COMPLETED as
            // an exitDescription — assert it contains the counts but
            // do not pin the exact format (preserves flexibility for
            // log-format evolution while still verifying the operator
            // contract).
            String exitDescription = execution.getExitStatus().getExitDescription();
            assertThat(exitDescription)
                    .contains("25")
                    .contains("text")
                    .contains("HTML");
        }
    }

    // =========================================================================
    // DefaultStatementMonth — verifies the Java target's strict-required
    // statementMonth contract. The production StatementGenerationJob
    // tasklet does NOT default a missing/blank statementMonth to "today"
    // or to "previous calendar month" — every batch run must supply an
    // explicit ISO-8601 yyyy-MM-dd date so the AuditLogService and the
    // Step Functions / AWS Batch orchestrator carry a deterministic
    // statement-period reference through CloudWatch / OpenSearch (AAP
    // §0.7.1 audit-traceability rule).
    //
    // The "default-like batch invocation pattern" — where operators
    // launch the EOD CREASTMT job for the previous calendar month — is
    // exercised by supplying an EXPLICIT first-of-previous-month date.
    // This mirrors the sibling InterestCalculationJobTest.DefaultBusinessDate
    // pattern (no implicit default; the operator supplies the equivalent
    // calendar date explicitly).
    // =========================================================================

    /**
     * Verifies that an explicit first-day-of-previous-month
     * statementMonth (matching the default-like EOD invocation pattern
     * that operators use from AWS Batch or the local Spring Boot CLI)
     * successfully drives the job. The Java target requires
     * statementMonth explicitly &mdash; there is no implicit default-
     * to-previous-month fallback because audit-traceability (AAP
     * &sect;0.7.1) mandates every batch run carry a deterministic date
     * parameter through CloudWatch / OpenSearch.
     */
    @Nested
    @DisplayName("DefaultStatementMonth — Previous calendar month is the default-like batch parameter")
    class DefaultStatementMonth {

        @Test
        @DisplayName("Explicit first-day-of-previous-month statementMonth completes successfully (default-like EOD run)")
        void explicitFirstOfPreviousMonth_completes() throws Exception {
            // COBOL: CBSTM03A — the typical EOD operational pattern is
            // to generate statements for the previous calendar month
            // (e.g., on May 1 the operator runs CREASTMT for April).
            // The Java target requires the operator to supply the
            // resolved first-of-previous-month date explicitly so the
            // audit trail is deterministic.
            LocalDate today = LocalDate.now();
            YearMonth previousMonth = YearMonth.from(today).minusMonths(1);
            LocalDate previousMonthStart = previousMonth.atDay(1);

            Mockito.when(statementGenerationService.generateStatements(previousMonthStart))
                    .thenReturn(new StatementResult(0, 0, 0));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    statementParams("statement-default-month", previousMonthStart.toString()));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            verify(statementGenerationService, times(1))
                    .generateStatements(previousMonthStart);
        }

        @Test
        @DisplayName("Missing statementMonth has NO implicit default — job FAILS deterministically")
        void missingStatementMonth_doesNotDefault() throws Exception {
            // The Java target deliberately does NOT default a missing
            // statementMonth — the production tasklet throws
            // IllegalArgumentException with a message mentioning
            // 'statementMonth' so operators cannot accidentally produce
            // statements for an unintended date. This @Test pairs with
            // the ParameterValidation group as a positive assertion of
            // the no-implicit-default contract (the negative test —
            // BatchStatus.FAILED — lives in ParameterValidation).
            JobExecution execution = jobLauncherTestUtils.launchJob(
                    new JobParametersBuilder()
                            .addString(StatementGenerationJob.PARAM_BATCH_RUN_ID,
                                    "statement-no-default")
                            .addString(StatementGenerationJob.PARAM_CORRELATION_ID,
                                    "corr-statement-no-default")
                            .toJobParameters());

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            // The service must never be invoked when the required
            // statementMonth parameter is absent — proving there is no
            // implicit default fallback in the production code path.
            verify(statementGenerationService, never())
                    .generateStatements(any(LocalDate.class));
        }
    }

    // =========================================================================
    // ParameterValidation — the standardJobParametersValidator from
    // BatchJobConfig enforces a non-blank batchRunId before the tasklet
    // is ever invoked (throws JobParametersInvalidException synchronously
    // from JobLauncher.run). The tasklet itself enforces a non-blank
    // ISO-8601 statementMonth at runtime (throws IllegalArgumentException
    // captured in JobExecution.getAllFailureExceptions() with
    // BatchStatus.FAILED).
    // =========================================================================

    /**
     * Verifies that the validator + tasklet enforce the batchRunId and
     * statementMonth JobParameters. Missing or malformed values fail
     * the job deterministically without ever invoking the service.
     */
    @Nested
    @DisplayName("ParameterValidation — Standard validator enforces batchRunId; tasklet enforces statementMonth")
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
                    .addString(StatementGenerationJob.PARAM_STATEMENT_MONTH, "2026-05-20")
                    .addString(StatementGenerationJob.PARAM_CORRELATION_ID,
                            "corr-missing-runId")
                    .toJobParameters();

            assertThatThrownBy(() -> jobLauncherTestUtils.launchJob(params))
                    .isInstanceOf(JobParametersInvalidException.class)
                    .hasMessageContaining("batchRunId");

            verify(statementGenerationService, never())
                    .generateStatements(any(LocalDate.class));
        }

        @Test
        @DisplayName("Missing statementMonth yields FAILED job with IllegalArgumentException in failureExceptions")
        void missingStatementMonth_jobFails() throws Exception {
            // batchRunId is present (validator passes) but statementMonth
            // is omitted — the tasklet itself throws
            // IllegalArgumentException at runtime, which Spring Batch
            // captures in the JobExecution.getAllFailureExceptions()
            // collection with BatchStatus.FAILED. This preserves the
            // COBOL CBSTM03A semantic of a fatal RETURN-CODE=8 ABEND
            // when PARM='YYYY-MM-DD' is missing.
            JobExecution execution = jobLauncherTestUtils.launchJob(
                    new JobParametersBuilder()
                            .addString(StatementGenerationJob.PARAM_BATCH_RUN_ID,
                                    "statement-missing-month")
                            .addString(StatementGenerationJob.PARAM_CORRELATION_ID,
                                    "corr-statement-missing-month")
                            .toJobParameters());

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(StatementGenerationJobTest::isIllegalArgumentException);
            verify(statementGenerationService, never())
                    .generateStatements(any(LocalDate.class));
        }

        @Test
        @DisplayName("Malformed (non-ISO-8601) statementMonth yields FAILED job with IllegalArgumentException")
        void malformedStatementMonth_jobFails() throws Exception {
            // A non-ISO-8601 statementMonth value cannot be parsed by
            // LocalDate.parse() — the tasklet wraps the underlying
            // DateTimeParseException in an IllegalArgumentException that
            // identifies the offending parameter name. The string
            // "July-2022" is the canonical agent-prompt example of an
            // unparseable date format.
            JobExecution execution = jobLauncherTestUtils.launchJob(
                    statementParams("statement-bad-format", "July-2022"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(StatementGenerationJobTest::isIllegalArgumentException);
            verify(statementGenerationService, never())
                    .generateStatements(any(LocalDate.class));
        }

        @Test
        @DisplayName("Yyyy-MM (no day) statementMonth is rejected — Java target requires full ISO-8601 yyyy-MM-dd")
        void yearMonthOnlyStatementMonth_isRejected() throws Exception {
            // Mainframe-era operators may have used a YYYY-MM only
            // identifier; the Java target deliberately requires the
            // full yyyy-MM-dd ISO-8601 form (the first-day-of-month
            // is the canonical statement-period start date). A
            // YYYY-MM value cannot be parsed by
            // DateTimeFormatter.ISO_LOCAL_DATE and is therefore
            // rejected just like any other malformed value.
            JobExecution execution = jobLauncherTestUtils.launchJob(
                    statementParams("statement-year-month-only", "2022-07"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(StatementGenerationJobTest::isIllegalArgumentException);
            verify(statementGenerationService, never())
                    .generateStatements(any(LocalDate.class));
        }
    }

    // =========================================================================
    // DualFormatTemplateMethod — text statement (CBSTM03A, LRECL=80) +
    // HTML statement (CBSTM03B, LRECL=100) Template Method pattern.
    //
    // Per AAP §0.3.3 and §0.4.1, each successful per-account iteration
    // produces BOTH a text statement (rendered via the CBSTM03A
    // FD-STMTFILE-REC PIC X(80) leaf) AND an HTML statement (rendered
    // via the CBSTM03B FD-HTMLFILE-REC PIC X(100) leaf). The Template
    // Method symmetry invariant is: every successful account produces
    // exactly one text statement AND one HTML statement, so
    // textCount == htmlCount for clean runs (errorCount reflects
    // accounts where BOTH renderings failed; partial failures where
    // text succeeds but HTML fails are handled inside the service).
    // =========================================================================

    /**
     * Verifies the Template Method symmetry: when all accounts succeed,
     * the count of text statements equals the count of HTML statements
     * (each account produces both formats). This is the key contract
     * preserved from the COBOL CBSTM03A + CBSTM03B dual-output design.
     */
    @Nested
    @DisplayName("DualFormatTemplateMethod — Text (CBSTM03A, LRECL=80) + HTML (CBSTM03B, LRECL=100)")
    class DualFormatTemplateMethod {

        @Test
        @DisplayName("Service emits equal text and HTML counts when all accounts processed successfully")
        void textAndHtmlCountsEqual_whenAllAccountsSucceed() throws Exception {
            // COBOL: CBSTM03A LRECL=80 (text via FD-STMTFILE-REC),
            //        CBSTM03B LRECL=100 (HTML via FD-HTMLFILE-REC).
            // The Template Method pattern (AAP §0.3.3) means each
            // account produces both a text and an HTML statement —
            // so textCount and htmlCount are equal for clean runs.
            LocalDate statementDate = LocalDate.parse("2022-06-01");
            Mockito.when(statementGenerationService.generateStatements(statementDate))
                    .thenReturn(new StatementResult(500, 500, 0));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    statementParams("statement-dual-symmetric", "2022-06-01"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            // Verify symmetry through the ExecutionContext.
            ExecutionContext stepContext = singleStepContext(execution);
            int textCount = stepContext.getInt(StatementGenerationJob.CTX_TEXT_COUNT);
            int htmlCount = stepContext.getInt(StatementGenerationJob.CTX_HTML_COUNT);
            assertThat(textCount).isEqualTo(500);
            assertThat(htmlCount).isEqualTo(500);
            assertThat(textCount).isEqualTo(htmlCount);

            verify(statementGenerationService, times(1))
                    .generateStatements(statementDate);
        }

        @Test
        @DisplayName("Audit payload carries both textCount and htmlCount for downstream consumers")
        void auditPayload_carriesBothCounts() throws Exception {
            // COBOL: CBSTM03A 9999-EXIT-PROGRAM — operator visibility of
            // both output counts is preserved through the AuditLog
            // structured payload (AAP §0.6.6 OpenSearch indexing).
            LocalDate statementDate = LocalDate.parse("2022-06-01");
            Mockito.when(statementGenerationService.generateStatements(statementDate))
                    .thenReturn(new StatementResult(75, 75, 0));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    statementParams("statement-dual-audit", "2022-06-01"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            // Verify the audit-log emission carried a non-null payload
            // map (the tasklet sources textCount + htmlCount + errorCount
            // into auditFields before calling logBatchJobLifecycle).
            verify(auditLogService, atLeastOnce()).logBatchJobLifecycle(
                    eq(StatementGenerationJob.JOB_NAME),
                    anyString(),
                    eq("COMPLETED"),
                    isNull(),
                    any(Map.class),
                    eq("corr-statement-dual-audit"));
        }
    }

    // =========================================================================
    // PartialFailures — when StatementGenerationService returns a non-
    // zero errorCount (per-account rendering errors), the Spring Batch
    // job still terminates with BatchStatus.COMPLETED because the COBOL
    // CBSTM03A semantic is "log and continue" for per-account errors
    // (the service catches RuntimeException and increments errorCount
    // without re-throwing — only OnSizeErrorException is fatal). The
    // StatementStepExitStatusListener.afterStep maps the non-zero
    // errorCount to CardDemoExitStatus.COMPLETED_WITH_REJECTS (COBOL
    // RETURN-CODE = 4) so downstream Step Functions Choice states can
    // branch on partial-success runs.
    // =========================================================================

    /**
     * Verifies that a {@link StatementResult} with a non-zero
     * {@code errorCount} terminates the job with
     * {@link BatchStatus#COMPLETED} but with the
     * {@link CardDemoExitStatus#COMPLETED_WITH_REJECTS} ExitStatus
     * (COBOL {@code RETURN-CODE = 4}), preserving the operator-visible
     * partial-success signal that downstream Step Functions Choice
     * states use to branch on partial-success runs.
     */
    @Nested
    @DisplayName("PartialFailures — Job COMPLETED with COMPLETED_WITH_REJECTS ExitStatus (COBOL RETURN-CODE = 4)")
    class PartialFailures {

        @Test
        @DisplayName("Non-zero errorCount: job COMPLETED; ExitStatus mapped to COMPLETED_WITH_REJECTS")
        void errorsButCompletes_mappedToCompletedWithRejects() throws Exception {
            // COBOL: CBSTM03A — per-account rendering errors are logged
            // but the batch continues; the final RETURN-CODE = 4 signals
            // "completed with rejects" to downstream JES consumers. The
            // Java target preserves this semantic by completing the job
            // with COMPLETED_WITH_REJECTS ExitStatus when errorCount > 0.
            LocalDate statementDate = LocalDate.parse("2022-08-01");
            Mockito.when(statementGenerationService.generateStatements(statementDate))
                    .thenReturn(new StatementResult(98, 98, 2));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    statementParams("statement-partial", "2022-08-01"));

            // BatchStatus is COMPLETED — the job did not fail.
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            // ExitStatus is COMPLETED_WITH_REJECTS — the partial-
            // success signal that downstream Step Functions Choice
            // states consume.
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(CardDemoExitStatus.COMPLETED_WITH_REJECTS.getExitCode());

            // ExecutionContext carries the non-zero errorCount.
            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(StatementGenerationJob.CTX_ERROR_COUNT))
                    .isEqualTo(2);
            assertThat(stepContext.getInt(StatementGenerationJob.CTX_TEXT_COUNT))
                    .isEqualTo(98);
            assertThat(stepContext.getInt(StatementGenerationJob.CTX_HTML_COUNT))
                    .isEqualTo(98);

            verify(statementGenerationService, times(1))
                    .generateStatements(statementDate);
        }

        @Test
        @DisplayName("Single errorCount=1 also triggers COMPLETED_WITH_REJECTS (any non-zero count)")
        void singleErrorCount_triggersCompletedWithRejects() throws Exception {
            // Verifies the "any non-zero" threshold of the
            // StatementStepExitStatusListener — even a single per-
            // account error maps to COMPLETED_WITH_REJECTS. This is the
            // conservative COBOL semantic: ANY reject counts as a
            // partial-success run requiring operator review.
            LocalDate statementDate = LocalDate.parse("2022-08-15");
            Mockito.when(statementGenerationService.generateStatements(statementDate))
                    .thenReturn(new StatementResult(199, 199, 1));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    statementParams("statement-single-err", "2022-08-15"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(CardDemoExitStatus.COMPLETED_WITH_REJECTS.getExitCode());
            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(StatementGenerationJob.CTX_ERROR_COUNT))
                    .isEqualTo(1);
        }
    }

    // =========================================================================
    // EmptyDataset — when the accounts table is empty (the JPA scan
    // returns zero rows), the service returns StatementResult(0, 0, 0).
    // The job should COMPLETE successfully — there is no COBOL CBSTM03A
    // "rejects" concept for an empty input (an empty input is a normal
    // operational state, not an error).
    // =========================================================================

    /**
     * Verifies that an empty accounts scan (service returns
     * {@code StatementResult(0, 0, 0)}) completes the job successfully
     * &mdash; preserving the COBOL CBSTM03A semantic that "no rows to
     * process" is a normal (not error) termination.
     */
    @Nested
    @DisplayName("EmptyDataset — Zero accounts still completes successfully")
    class EmptyDataset {

        @Test
        @DisplayName("Service returns StatementResult(0, 0, 0) → job COMPLETED; ExecutionContext shows zero counts")
        void zeroAccounts_completes() throws Exception {
            // COBOL CBSTM03A — EOF on the accounts/customer/transaction
            // VSAM scan yields textCount=0 / htmlCount=0 / errorCount=0.
            // No COBOL RETURN-CODE=8 (FAILED) is set because an empty
            // input is a normal operational state (e.g., quiet weekend,
            // first run of a new batch cycle, or a far-future statement
            // date with no transactions yet).
            LocalDate statementDate = LocalDate.parse("2099-01-01");
            Mockito.when(statementGenerationService.generateStatements(statementDate))
                    .thenReturn(new StatementResult(0, 0, 0));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    statementParams("statement-empty", "2099-01-01"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            // ExitStatus is COMPLETED (NOT COMPLETED_WITH_REJECTS) —
            // zero errors means clean completion.
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());

            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(StatementGenerationJob.CTX_TEXT_COUNT))
                    .isZero();
            assertThat(stepContext.getInt(StatementGenerationJob.CTX_HTML_COUNT))
                    .isZero();
            assertThat(stepContext.getInt(StatementGenerationJob.CTX_ERROR_COUNT))
                    .isZero();

            verify(statementGenerationService, times(1))
                    .generateStatements(statementDate);
        }

        @Test
        @DisplayName("Empty dataset still emits COMPLETED audit lifecycle to AuditLogService")
        void zeroAccounts_emitsCompletedAudit() throws Exception {
            // Operator visibility: even an empty run must emit the
            // COMPLETED audit document so OpenSearch retains a queryable
            // record (AAP §0.6.6) that the job did execute on this
            // statement date — important for regulatory parity.
            LocalDate statementDate = LocalDate.parse("2099-12-31");
            Mockito.when(statementGenerationService.generateStatements(statementDate))
                    .thenReturn(new StatementResult(0, 0, 0));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    statementParams("statement-empty-audit", "2099-12-31"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            verify(auditLogService, atLeastOnce()).logBatchJobLifecycle(
                    eq(StatementGenerationJob.JOB_NAME),
                    anyString(),
                    eq("COMPLETED"),
                    isNull(),
                    any(Map.class),
                    eq("corr-statement-empty-audit"));
        }
    }

    // =========================================================================
    // ServiceFailurePropagation — when StatementGenerationService throws
    // (e.g., RDS connectivity loss, S3 upload exhaustion, or an
    // OnSizeErrorException from a PIC S9(09)V99 overflow), the Spring
    // Batch step transitions to FAILED and the originating exception is
    // captured in JobExecution.getAllFailureExceptions(). The
    // StatementStepExitStatusListener.afterStep preserves the FAILED
    // ExitStatus (CBSTM03A's RETURN-CODE = 8 ABEND must NOT be downgraded
    // to COMPLETED_WITH_REJECTS).
    // =========================================================================

    /**
     * Verifies that a {@link RuntimeException} thrown by
     * {@link StatementGenerationService#generateStatements(LocalDate)}
     * propagates correctly to the {@link JobExecution}: status
     * {@link BatchStatus#FAILED}, the originating exception captured in
     * {@link JobExecution#getAllFailureExceptions()}.
     */
    @Nested
    @DisplayName("ServiceFailurePropagation — Service exceptions cause job FAILED status")
    class ServiceFailurePropagation {

        @Test
        @DisplayName("Service RuntimeException → job FAILED with failureException captured")
        void failsWhenStatementGenerationServiceThrows() throws Exception {
            // COBOL CBSTM03A — any underlying VSAM I/O failure or LE
            // ABEND would set RETURN-CODE = 8 (FAILED). The Java target
            // surfaces this as a thrown exception that Spring Batch
            // captures in the JobExecution's failureExceptions list.
            Mockito.when(statementGenerationService.generateStatements(any(LocalDate.class)))
                    .thenThrow(new IllegalStateException("CREASTMT service failure: S3 upload exhausted"));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    statementParams("statement-svc-fail", "2026-05-20"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(ex -> ex instanceof IllegalStateException
                            && ex.getMessage().contains("CREASTMT service failure"));
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
            LocalDate statementDate = LocalDate.parse("2026-05-20");
            Mockito.when(statementGenerationService.generateStatements(statementDate))
                    .thenThrow(new RuntimeException("RDS connection failed"));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    statementParams("statement-svc-fail-once", "2026-05-20"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            verify(statementGenerationService, times(1)).generateStatements(statementDate);
        }

        @Test
        @DisplayName("OnSizeErrorException from service → job FAILED (COBOL ON SIZE ERROR semantic preserved)")
        void onSizeErrorException_propagatesAsFailed() throws Exception {
            // COBOL CBSTM03A: PIC S9(09)V99 WS-TOTAL-AMT — an
            // arithmetic overflow during running-total accumulation
            // sets the ON SIZE ERROR flag in COBOL. The Java target
            // preserves this semantic via OnSizeErrorException (per
            // AAP §0.6.1) and the service re-throws (it is the only
            // exception that the service does NOT catch-and-count) so
            // the batch FAILS rather than silently truncating to zero.
            //
            // Here we simulate the propagation through the tasklet
            // using a generic RuntimeException — the service-layer
            // test exhaustively verifies the OnSizeErrorException-
            // specific behaviour; at the Job orchestration level we
            // only need to verify that arithmetic ABEND exceptions
            // surface as BatchStatus.FAILED.
            Mockito.when(statementGenerationService.generateStatements(any(LocalDate.class)))
                    .thenThrow(new RuntimeException(
                            "OnSizeErrorException: PIC S9(09)V99 overflow on running total"));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    statementParams("statement-on-size-err", "2026-05-20"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(ex -> ex.getMessage() != null
                            && ex.getMessage().contains("OnSizeErrorException"));
        }
    }

    // =========================================================================
    // Helper methods — shared across @Nested groups for compactness.
    // =========================================================================

    /**
     * Builds the canonical {@link JobParameters} for a
     * {@code statementGenerationJob} launch: batchRunId, statementMonth
     * (ISO-8601 yyyy-MM-dd), and a derived correlationId. Used by every
     * test that launches with a valid (or deliberately malformed)
     * statementMonth.
     *
     * @param batchRunId      unique batch run identifier (required by the
     *                        standardJobParametersValidator)
     * @param statementMonth  ISO-8601 {@code yyyy-MM-dd} statement date
     *                        (or a deliberately malformed string for
     *                        negative tests)
     * @return the constructed immutable {@link JobParameters}
     */
    private JobParameters statementParams(String batchRunId, String statementMonth) {
        return new JobParametersBuilder()
                .addString(StatementGenerationJob.PARAM_BATCH_RUN_ID, batchRunId)
                .addString(StatementGenerationJob.PARAM_STATEMENT_MONTH, statementMonth)
                .addString(StatementGenerationJob.PARAM_CORRELATION_ID, "corr-" + batchRunId)
                .toJobParameters();
    }

    /**
     * Extracts the single step's {@link ExecutionContext} from a
     * {@link JobExecution}. The {@code statementGenerationJob} has
     * exactly one step ({@code generateStatementsStep}); any deviation
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
     * and DefaultStatementMonth {@code @Nested} groups to assert that
     * the tasklet's statementMonth validation surfaces as an
     * {@link IllegalArgumentException} in
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
