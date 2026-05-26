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
// DailyTransactionPostingJobTest — Spring Batch orchestration tests
// =============================================================================
//
// Replaces JCL job stream: app/jcl/POSTTRAN.jcl
// COBOL: CBTRN01C + CBTRN02C + CBTRN03C (4-stage validation cascade)
//
// Validates the Spring Batch dailyTransactionPostingJob @Configuration class
// that replaces the COBOL CBTRN02C.cbl daily-transaction-posting program
// (with supporting CBTRN01C dump-utility semantics subsumed inside the
// service and the CBTRN03C report variant intentionally split out to
// TransactionReportJob per AAP §0.4.1). This job is Stage 1 of the EOD
// pipeline POSTTRAN → INTCALC → COMBTRAN → CREASTMT/TRANREPT per AAP
// §0.6.3.
//
// Verbatim CBTRN02C RETURN-CODE semantics under test (CBTRN02C.cbl L229-L230
// "IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE"):
//   * rejectCount == 0 → ExitStatus.COMPLETED   (RETURN-CODE = 0)
//   * rejectCount  > 0 → COMPLETED_WITH_REJECTS  (RETURN-CODE = 4)
//   * service throws  → BatchStatus.FAILED       (RETURN-CODE = 8 equivalent)
//
// The detailed reject-code strings (100=INVALID CARD NUMBER FOUND,
// 101=ACCOUNT RECORD NOT FOUND, 102=OVERLIMIT TRANSACTION,
// 103=TRANSACTION RECEIVED AFTER ACCT EXPIRATION, 109=REWRITE FAILED) are
// exhaustively tested at the SERVICE layer by TransactionPostingServiceTest;
// this job test only verifies the AGGREGATE exit-status propagation through
// the Spring Batch step's tasklet + step-execution-listener.
//
// Per AAP §0.4.1 and the sibling InterestCalculationJobTest /
// CombineTransactionsJobTest pattern:
//   * @SpringBootTest         — full ApplicationContext bootstrap so the
//                               dailyTransactionPostingJob Job bean (and
//                               its standardJobParametersValidator +
//                               sharedAuditJobExecutionListener wiring
//                               from BatchJobConfig) is exercised
//                               end-to-end.
//   * @SpringBatchTest        — auto-configures JobLauncherTestUtils.
//   * @Testcontainers         — manages the lifecycle of the static
//                               @Container PostgreSQLContainer.
//   * @ServiceConnection      — auto-binds the container to the
//                               spring.datasource.* properties.
//   * @ActiveProfiles("test") — loads application-test.yml (disables
//                               AWS Secrets Manager + Parameter Store
//                               auto-config; points spring.* at
//                               Testcontainers-backed local services).
//   * @MockBean               — replaces TransactionPostingService and
//                               every AWS SDK / Kafka / Redis /
//                               OpenSearch client with a Mockito mock
//                               so the test does not require live AWS
//                               infrastructure or business-data
//                               fixtures.
//
// @Nested test groups (per file schema members_exposed):
//   * HappyPath_Exit0          — rejectCount == 0 → ExitStatus.COMPLETED
//   * Rejects_Exit4            — rejectCount  > 0 → COMPLETED_WITH_REJECTS
//   * ParameterValidation      — batchRunId required + businessDate ISO-8601
//   * BusinessDateDefault      — Today's date as the default-like batch run
//   * RejectCodeSemantics      — Documentation: reject codes live in service
//   * ServiceFailurePropagation— Service exceptions → BatchStatus.FAILED
//
// AAP cross-references:
//   §0.4.1  — transformation mapping (DailyTransactionPostingJob: CREATE)
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
import com.awsm2.carddemo.service.TransactionPostingService;
import com.awsm2.carddemo.service.TransactionPostingService.PostingResult;

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
 * Spring Batch end-to-end tests for {@link DailyTransactionPostingJob}.
 *
 * <p><b>// Replaces: app/jcl/POSTTRAN.jcl + app/cbl/CBTRN02C.cbl</b> (with
 * CBTRN01C preliminary-read semantics subsumed and CBTRN03C report variant
 * intentionally split out per AAP &sect;0.4.1). The tests verify Spring
 * Batch orchestration concerns &mdash; JobParameters parsing,
 * ExecutionContext publishing, ExitStatus propagation, and lifecycle
 * audit-trail emission &mdash; while delegating the verbatim COBOL
 * 4-stage validation cascade + per-record reject-code emission (100, 101,
 * 102, 103, 109) to the dedicated service-layer test
 * {@code TransactionPostingServiceTest}.</p>
 *
 * <p>The verbatim COBOL RETURN-CODE-to-ExitStatus mapping verified end-
 * to-end here mirrors {@code CBTRN02C.cbl} L229-L230:
 * {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}. The Java target
 * surfaces this verbatim via {@link CardDemoExitStatus} so AWS Batch
 * containers exit with the matching exit code and Step Functions Choice
 * states branch deterministically per AAP &sect;0.6.3.</p>
 *
 * @see DailyTransactionPostingJob
 * @see TransactionPostingService
 * @see CardDemoExitStatus
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@Import(DailyTransactionPostingJobTest.TestSecretsManagerConfiguration.class)
@DisplayName("DailyTransactionPostingJob — Spring Batch orchestration tests (COBOL: CBTRN02C, JCL: POSTTRAN.jcl)")
class DailyTransactionPostingJobTest {

    // =========================================================================
    // TestConfiguration — disables Spring Cloud AWS Secrets Manager and seeds
    // the Spring Batch JOB_REPOSITORY schema on the Testcontainers PostgreSQL.
    // Identical wiring to the sibling InterestCalculationJobTest +
    // CombineTransactionsJobTest per the established Spring Batch test
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
    // synchronous launch of dailyTransactionPostingJob with the test-
    // supplied JobParameters. The @Qualifier on the JobLauncher / Job
    // bean injections prevents Spring from raising
    // NoUniqueBeanDefinitionException when the application context
    // exposes multiple Job beans (one per batch program) — only the
    // dailyTransactionPostingJob is targeted here.
    // =========================================================================
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(DailyTransactionPostingJob.JOB_NAME)
    private Job dailyTransactionPostingJob;

    // =========================================================================
    // @MockBean — primary collaborator under test (TransactionPostingService)
    // plus every adapter and AWS SDK / Kafka / Redis / OpenSearch client
    // bean that the @SpringBootTest ApplicationContext would otherwise try
    // to resolve against live infrastructure or AWS credentials. The mock
    // posture is identical to InterestCalculationJobTest and
    // CombineTransactionsJobTest per AAP §0.7.2.
    // =========================================================================
    @MockBean
    private TransactionPostingService transactionPostingService;

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
        Mockito.reset(transactionPostingService, auditLogService);
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJob(dailyTransactionPostingJob);
    }

    // =========================================================================
    // HappyPath_Exit0 — verbatim CBTRN02C RETURN-CODE = 0 (rejectCount==0)
    // mapping to Spring Batch ExitStatus.COMPLETED (CBTRN02C.cbl L229-L230
    // contract). Verifies that the tasklet propagates the service's
    // PostingResult through the step's ExecutionContext, increments the
    // read/write counters correctly, and emits the COMPLETED audit
    // lifecycle.
    // =========================================================================

    /**
     * Verifies that a {@link PostingResult} with {@code rejectCount == 0}
     * (verbatim COBOL RETURN-CODE = 0 per {@code CBTRN02C.cbl} L229-L230)
     * maps to Spring Batch {@link ExitStatus#COMPLETED} via the
     * {@link CardDemoExitStatus} centralized exit-status mapping.
     */
    @Nested
    @DisplayName("HappyPath_Exit0 — Zero rejects → ExitStatus.COMPLETED (verbatim CBTRN02C RETURN-CODE=0)")
    class HappyPath_Exit0 {

        @Test
        @DisplayName("All 100 transactions accepted (rejectCount=0) → ExitStatus.COMPLETED")
        void allAccepted_noRejects_exitCompleted() throws Exception {
            // COBOL: CBTRN02C MAIN-PARA — when WS-REJECT-COUNT = 0 the
            // implicit RETURN-CODE = 0 is preserved through GOBACK.
            LocalDate businessDate = LocalDate.of(2022, 7, 18);
            PostingResult result = new PostingResult(100, 0,
                    CardDemoExitStatus.RETURN_CODE_COMPLETED);
            Mockito.when(transactionPostingService.postDailyTransactions(businessDate))
                    .thenReturn(result);

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("posttran-run-001", "2022-07-18"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());

            // ExecutionContext propagation — verifies the tasklet
            // published the PostingResult fields verbatim.
            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_TRANSACTION_COUNT))
                    .isEqualTo(100);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_REJECT_COUNT))
                    .isZero();
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_RETURN_CODE))
                    .isEqualTo(CardDemoExitStatus.RETURN_CODE_COMPLETED);

            verify(transactionPostingService, times(1))
                    .postDailyTransactions(businessDate);

            // Audit-log emission — the tasklet emits a COMPLETED lifecycle
            // payload carrying the counts; the shared
            // sharedAuditJobExecutionListener additionally emits STARTED
            // and terminal lifecycle events (atLeastOnce captures both).
            verify(auditLogService, atLeastOnce()).logBatchJobLifecycle(
                    eq(DailyTransactionPostingJob.JOB_NAME),
                    anyString(),
                    eq("COMPLETED"),
                    isNull(),
                    any(Map.class),
                    eq("corr-posttran-run-001"));
        }

        @Test
        @DisplayName("Zero-transaction run still ExitStatus.COMPLETED (empty DALYTRAN-FILE)")
        void zeroTransactions_exitCompleted() throws Exception {
            // COBOL: CBTRN02C — EOF on DALYTRAN-FILE without any records
            // is a normal operational state (quiet weekend / first run
            // of a new batch cycle). The Java target preserves this
            // semantic — empty inputs produce a COMPLETED exit, not a
            // FAILED exit.
            LocalDate businessDate = LocalDate.of(2099, 1, 1);
            PostingResult result = new PostingResult(0, 0,
                    CardDemoExitStatus.RETURN_CODE_COMPLETED);
            Mockito.when(transactionPostingService.postDailyTransactions(businessDate))
                    .thenReturn(result);

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("posttran-empty", "2099-01-01"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());

            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_TRANSACTION_COUNT))
                    .isZero();
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_REJECT_COUNT))
                    .isZero();
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_RETURN_CODE))
                    .isEqualTo(CardDemoExitStatus.RETURN_CODE_COMPLETED);

            verify(transactionPostingService, times(1))
                    .postDailyTransactions(businessDate);
        }
    }

    // =========================================================================
    // Rejects_Exit4 — verbatim CBTRN02C RETURN-CODE = 4 (rejectCount > 0)
    // mapping to CardDemoExitStatus.COMPLETED_WITH_REJECTS. This is the
    // "completed but with rejected records" semantic that COBOL CBTRN02C
    // sets via "MOVE 4 TO RETURN-CODE" at L229-L230 when any record fell
    // through the 4-stage validation cascade and was emitted to the
    // DALYREJS S3 prefix.
    // =========================================================================

    /**
     * Verifies that a {@link PostingResult} with {@code rejectCount > 0}
     * (verbatim COBOL RETURN-CODE = 4 per {@code CBTRN02C.cbl} L229-L230)
     * maps to {@link CardDemoExitStatus#COMPLETED_WITH_REJECTS} via the
     * {@link CardDemoExitStatus} centralized exit-status mapping.
     */
    @Nested
    @DisplayName("Rejects_Exit4 — rejectCount > 0 → COMPLETED_WITH_REJECTS (verbatim CBTRN02C RETURN-CODE=4)")
    class Rejects_Exit4 {

        @Test
        @DisplayName("5 rejects out of 100 → ExitStatus.exitCode='COMPLETED_WITH_REJECTS'")
        void someRejects_exitCompletedWithRejects() throws Exception {
            // COBOL: CBTRN02C L229-L230 — "IF WS-REJECT-COUNT > 0
            // MOVE 4 TO RETURN-CODE". The Java target surfaces this
            // verbatim via CardDemoExitStatus.COMPLETED_WITH_REJECTS.
            LocalDate businessDate = LocalDate.of(2022, 7, 18);
            PostingResult result = new PostingResult(100, 5,
                    CardDemoExitStatus.RETURN_CODE_WITH_REJECTS);
            Mockito.when(transactionPostingService.postDailyTransactions(businessDate))
                    .thenReturn(result);

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("posttran-rejects", "2022-07-18"));

            // BatchStatus is COMPLETED — the step finished without an
            // unhandled exception; the rejects are a "soft" partial
            // failure mode that downstream Step Functions Choice states
            // branch on via the ExitStatus, not BatchStatus.
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            // ExitStatus is the verbatim COBOL RETURN-CODE = 4 mapping.
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo("COMPLETED_WITH_REJECTS");
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(CardDemoExitStatus.COMPLETED_WITH_REJECTS.getExitCode());

            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_TRANSACTION_COUNT))
                    .isEqualTo(100);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_REJECT_COUNT))
                    .isEqualTo(5);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_RETURN_CODE))
                    .isEqualTo(CardDemoExitStatus.RETURN_CODE_WITH_REJECTS);

            verify(transactionPostingService, times(1))
                    .postDailyTransactions(businessDate);
        }

        @Test
        @DisplayName("Single reject (1 out of 1) → ExitStatus.exitCode='COMPLETED_WITH_REJECTS'")
        void singleReject_exitCompletedWithRejects() throws Exception {
            // COBOL: even a SINGLE rejected record flips RETURN-CODE to
            // 4; the COBOL "IF WS-REJECT-COUNT > 0" predicate is strictly
            // positive, not threshold-based.
            LocalDate businessDate = LocalDate.of(2022, 7, 18);
            PostingResult result = new PostingResult(1, 1,
                    CardDemoExitStatus.RETURN_CODE_WITH_REJECTS);
            Mockito.when(transactionPostingService.postDailyTransactions(businessDate))
                    .thenReturn(result);

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("posttran-single-reject", "2022-07-18"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo("COMPLETED_WITH_REJECTS");

            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_REJECT_COUNT))
                    .isEqualTo(1);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_RETURN_CODE))
                    .isEqualTo(CardDemoExitStatus.RETURN_CODE_WITH_REJECTS);
        }

        @Test
        @DisplayName("All rejected (50 out of 50) → ExitStatus.exitCode='COMPLETED_WITH_REJECTS'")
        void allRejected_exitCompletedWithRejects() throws Exception {
            // Degenerate case — 100% of input records failed the
            // 4-stage validation cascade. The step still reaches
            // BatchStatus.COMPLETED because no unhandled exception was
            // raised; the COBOL semantic is preserved (a fully-rejected
            // batch is still a successful step run, just with a
            // distinct exit code).
            LocalDate businessDate = LocalDate.of(2022, 7, 18);
            PostingResult result = new PostingResult(50, 50,
                    CardDemoExitStatus.RETURN_CODE_WITH_REJECTS);
            Mockito.when(transactionPostingService.postDailyTransactions(businessDate))
                    .thenReturn(result);

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("posttran-all-rejected", "2022-07-18"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo("COMPLETED_WITH_REJECTS");

            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_TRANSACTION_COUNT))
                    .isEqualTo(50);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_REJECT_COUNT))
                    .isEqualTo(50);
        }
    }

    // =========================================================================
    // ParameterValidation — the standardJobParametersValidator from
    // BatchJobConfig enforces a non-blank batchRunId before the tasklet
    // is ever invoked (throws JobParametersInvalidException synchronously
    // from JobLauncher.run). The tasklet itself enforces a non-blank
    // ISO-8601 businessDate at runtime (throws IllegalArgumentException
    // captured in JobExecution.getAllFailureExceptions() with
    // BatchStatus.FAILED). In both cases the service is NEVER invoked —
    // the validator / parameter parser short-circuits the launch.
    // =========================================================================

    /**
     * Verifies that the validator + tasklet enforce the batchRunId and
     * businessDate JobParameters. Missing or malformed values fail the
     * job deterministically without ever invoking the service.
     */
    @Nested
    @DisplayName("ParameterValidation — Standard validator enforces batchRunId; tasklet enforces businessDate")
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
                    .addString(DailyTransactionPostingJob.PARAM_BUSINESS_DATE, "2022-07-18")
                    .addString("correlationId", "corr-missing-runId")
                    .toJobParameters();

            assertThatThrownBy(() -> jobLauncherTestUtils.launchJob(params))
                    .isInstanceOf(JobParametersInvalidException.class)
                    .hasMessageContaining("batchRunId");

            verify(transactionPostingService, never())
                    .postDailyTransactions(any(LocalDate.class));
        }

        @Test
        @DisplayName("Missing businessDate yields FAILED job with IllegalArgumentException in failureExceptions")
        void missingBusinessDate_jobFails() throws Exception {
            // batchRunId is present (validator passes) but businessDate
            // is omitted — the tasklet itself throws
            // IllegalArgumentException at runtime, which Spring Batch
            // captures in the JobExecution.getAllFailureExceptions()
            // collection with BatchStatus.FAILED.
            JobExecution execution = jobLauncherTestUtils.launchJob(
                    new JobParametersBuilder()
                            .addString(DailyTransactionPostingJob.PARAM_BATCH_RUN_ID,
                                    "posttran-missing-date")
                            .addString("correlationId", "corr-posttran-missing-date")
                            .toJobParameters());

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(DailyTransactionPostingJobTest::isIllegalArgumentException);
            verify(transactionPostingService, never())
                    .postDailyTransactions(any(LocalDate.class));
        }

        @Test
        @DisplayName("Malformed (non-ISO-8601) businessDate yields FAILED job with IllegalArgumentException")
        void malformedBusinessDate_jobFails() throws Exception {
            // A non-ISO-8601 businessDate value cannot be parsed by
            // LocalDate.parse() — the tasklet wraps the underlying
            // DateTimeParseException in an IllegalArgumentException that
            // identifies the offending parameter name. The Java target
            // deliberately rejects ambiguous date formats (e.g., the
            // legacy COBOL "MM/DD/YYYY" presentation) to prevent silent
            // mis-parsing of operator input.
            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("posttran-bad-date", "18/07/2022"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(DailyTransactionPostingJobTest::isIllegalArgumentException);
            verify(transactionPostingService, never())
                    .postDailyTransactions(any(LocalDate.class));
        }
    }

    // =========================================================================
    // BusinessDateDefault — verifies that supplying today's date
    // (LocalDate.now()) as the explicit businessDate JobParameter completes
    // successfully. The Java target requires businessDate explicitly (no
    // implicit default-to-LocalDate.now() fallback — see ParameterValidation
    // for the missing-businessDate failure case) because audit-traceability
    // (AAP §0.7.1) mandates every batch run carry a deterministic date
    // parameter through CloudWatch / OpenSearch. This @Nested group
    // exercises the default-like "ad-hoc batch run for today's
    // transactions" workflow that an operator would invoke from AWS Batch
    // or the local Spring Boot CLI.
    // =========================================================================

    /**
     * Verifies that an explicit today's-date businessDate (matching the
     * default-like batch invocation pattern operators use from AWS Batch
     * or the local Spring Boot CLI) successfully drives the job. The
     * Java target requires businessDate explicitly &mdash; there is no
     * implicit default-to-{@link LocalDate#now()} fallback because
     * audit-traceability (AAP &sect;0.7.1) mandates every batch run
     * carry a deterministic date parameter through CloudWatch /
     * OpenSearch.
     */
    @Nested
    @DisplayName("BusinessDateDefault — Today's date is the default-like batch parameter")
    class BusinessDateDefault {

        @Test
        @DisplayName("Explicit LocalDate.now() businessDate completes successfully (default-like batch run for today)")
        void explicitTodayBusinessDate_completes() throws Exception {
            // COBOL: CBTRN02C — driven by the JCL/CICS batch-date
            // parameter; in the typical operational pattern the operator
            // supplies today's date when invoking the EOD posting job
            // ad-hoc (the Step Functions schedule provides today's date
            // automatically; manual operator runs pass it explicitly).
            LocalDate today = LocalDate.now();
            PostingResult result = new PostingResult(0, 0,
                    CardDemoExitStatus.RETURN_CODE_COMPLETED);
            Mockito.when(transactionPostingService.postDailyTransactions(today))
                    .thenReturn(result);

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("posttran-today", today.toString()));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());
            verify(transactionPostingService, times(1)).postDailyTransactions(today);
        }
    }

    // =========================================================================
    // RejectCodeSemantics — documentation-style group confirming that
    // the verbatim COBOL reject codes (100, 101, 102, 103, 109) and
    // their associated reject reason strings live in the SERVICE layer
    // (TransactionPostingServiceTest), NOT this job test. This group
    // exists to make the architectural decision explicit and traceable
    // for any future agent maintaining the test suite — the job test
    // only verifies AGGREGATE exit-status propagation, not the
    // per-record reject-code emission detail.
    // =========================================================================

    /**
     * Documents the architectural decision that verbatim COBOL reject-
     * code emission (codes 100&ndash;109 with their reason strings) is
     * tested at the SERVICE layer in
     * {@code TransactionPostingServiceTest}, not in this job-level
     * orchestration test. The job test only verifies that ANY non-zero
     * reject count surfaces as {@link CardDemoExitStatus#COMPLETED_WITH_REJECTS}
     * end-to-end.
     */
    @Nested
    @DisplayName("RejectCodeSemantics — Verbatim CBTRN02C reject codes (100/101/102/103/109) are service-level concerns")
    class RejectCodeSemantics {

        @Test
        @DisplayName("Job test verifies AGGREGATE exit-status propagation only — reject-code strings live in service test")
        void rejectCodeSemanticsTestedInServiceTest() throws Exception {
            // Architectural decision: detailed verbatim reject-code
            // verification (per CBTRN02C.cbl):
            //   COBOL: CBTRN02C:1500-A-LOOKUP-XREF        — reject 100
            //          "INVALID CARD NUMBER FOUND"
            //   COBOL: CBTRN02C:1500-B-LOOKUP-ACCT (L397) — reject 101
            //          "ACCOUNT RECORD NOT FOUND"
            //   COBOL: CBTRN02C:1500-B-LOOKUP-ACCT (L410) — reject 102
            //          "OVERLIMIT TRANSACTION"
            //   COBOL: CBTRN02C:1500-B-LOOKUP-ACCT (L417) — reject 103
            //          "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"
            //   COBOL: CBTRN02C:2800-UPDATE-ACCOUNT-REC   — reject 109
            //          "ACCOUNT RECORD NOT FOUND" (REWRITE INVALID KEY
            //          → mapped to OptimisticLockingFailureException)
            // lives in TransactionPostingServiceTest.java. The job test
            // only verifies the aggregate exit-status propagation:
            // any non-zero rejectCount → CardDemoExitStatus.COMPLETED_WITH_REJECTS.
            //
            // This test exercises a mix of 10 transactions / 5 rejects
            // (the specific reject codes are encapsulated inside the
            // service) to confirm the AGGREGATE propagation contract.
            LocalDate businessDate = LocalDate.of(2022, 7, 18);
            PostingResult result = new PostingResult(10, 5,
                    CardDemoExitStatus.RETURN_CODE_WITH_REJECTS);
            Mockito.when(transactionPostingService.postDailyTransactions(businessDate))
                    .thenReturn(result);

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("posttran-rejcode-aggregate", "2022-07-18"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo("COMPLETED_WITH_REJECTS");

            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_TRANSACTION_COUNT))
                    .isEqualTo(10);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_REJECT_COUNT))
                    .isEqualTo(5);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_RETURN_CODE))
                    .isEqualTo(CardDemoExitStatus.RETURN_CODE_WITH_REJECTS);

            // Reject-count > 0 is the AGGREGATE signal that surfaces the
            // COBOL RETURN-CODE = 4 semantic; reject-code-string detail
            // is the service test's responsibility.
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_REJECT_COUNT))
                    .isPositive();
        }

        @Test
        @DisplayName("Service returnCode=4 with non-zero rejectCount maps to COMPLETED_WITH_REJECTS regardless of individual reject codes")
        void anyRejectCount_mapsToCompletedWithRejects() throws Exception {
            // Even if the service returns a single reject of any kind
            // (e.g., reject code 100 for INVALID CARD NUMBER FOUND, or
            // reject code 102 for OVERLIMIT TRANSACTION, or reject code
            // 109 for REWRITE FAILED), the AGGREGATE exit status is
            // identical — COMPLETED_WITH_REJECTS. The individual reject
            // code only affects the DALYREJS S3 object content (verified
            // at the service test layer).
            LocalDate businessDate = LocalDate.of(2022, 7, 18);
            PostingResult result = new PostingResult(7, 3,
                    CardDemoExitStatus.RETURN_CODE_WITH_REJECTS);
            Mockito.when(transactionPostingService.postDailyTransactions(businessDate))
                    .thenReturn(result);

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("posttran-mixed-rejects", "2022-07-18"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo("COMPLETED_WITH_REJECTS");
            // The exit-code string MUST match the CardDemoExitStatus
            // centralized constant byte-for-byte so AWS Batch and Step
            // Functions Choice states branch deterministically per
            // AAP §0.6.3.
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(CardDemoExitStatus.COMPLETED_WITH_REJECTS.getExitCode());
        }
    }

    // =========================================================================
    // ServiceFailurePropagation — when TransactionPostingService throws
    // (e.g., RDS connectivity loss, optimistic-lock conflict, S3 write
    // failure to DALYREJS, OnSizeErrorException from a PIC S9(09)V99
    // overflow), the Spring Batch step transitions to FAILED and the
    // originating exception is captured in
    // JobExecution.getAllFailureExceptions(). The CardDemoStepExitStatusListener
    // afterStep preserves the FAILED ExitStatus per AAP §0.7.1 RETURN-CODE
    // parity (COBOL RETURN-CODE = 8 equivalent).
    // =========================================================================

    /**
     * Verifies that a {@link RuntimeException} thrown by
     * {@link TransactionPostingService#postDailyTransactions(LocalDate)}
     * propagates correctly to the {@link JobExecution}: status
     * {@link BatchStatus#FAILED}, the originating exception captured in
     * {@link JobExecution#getAllFailureExceptions()}.
     */
    @Nested
    @DisplayName("ServiceFailurePropagation — Service exceptions cause job BatchStatus.FAILED")
    class ServiceFailurePropagation {

        @Test
        @DisplayName("Service RuntimeException → job FAILED with failureException captured")
        void serviceException_jobFails() throws Exception {
            // COBOL: CBTRN02C — any underlying VSAM I/O failure or LE
            // ABEND would set RETURN-CODE = 8 (FAILED) via 9999-ABEND-
            // PROGRAM. The Java target surfaces this as a thrown
            // exception that Spring Batch captures in the JobExecution's
            // failureExceptions list with BatchStatus.FAILED.
            LocalDate businessDate = LocalDate.of(2022, 7, 18);
            Mockito.when(transactionPostingService.postDailyTransactions(businessDate))
                    .thenThrow(new RuntimeException("Database connection failed"));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("posttran-svc-fail", "2022-07-18"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions()).isNotEmpty();
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(ex -> ex instanceof RuntimeException
                            && ex.getMessage() != null
                            && ex.getMessage().contains("Database connection failed"));
            verify(transactionPostingService, times(1))
                    .postDailyTransactions(businessDate);
        }

        @Test
        @DisplayName("IllegalStateException from service → job FAILED with originating exception preserved")
        void serviceIllegalState_jobFails() throws Exception {
            // Alternate exception type — verifies that the tasklet
            // surfaces ANY RuntimeException subtype (not just literal
            // RuntimeException) so operational tooling sees the
            // originating exception class in the JobExecution.
            // failureExceptions stack.
            LocalDate businessDate = LocalDate.of(2022, 7, 18);
            Mockito.when(transactionPostingService.postDailyTransactions(businessDate))
                    .thenThrow(new IllegalStateException("POSTTRAN service failure"));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("posttran-illegal-state", "2022-07-18"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(ex -> ex instanceof IllegalStateException
                            && ex.getMessage() != null
                            && ex.getMessage().contains("POSTTRAN service failure"));
        }

        @Test
        @DisplayName("Service called exactly once before failure surfaces (no retry at tasklet level)")
        void serviceCalledOnceBeforeFailure() throws Exception {
            // Defensive assertion that the tasklet does not retry the
            // service on failure — Spring Batch's default retry policy
            // is disabled for this tasklet; failures propagate
            // immediately per AAP §0.6.3 ("Catch and Retry policies on
            // Step Functions" — retries happen at the Step Functions
            // orchestrator level, never at the Spring Batch tasklet
            // level).
            LocalDate businessDate = LocalDate.of(2022, 7, 18);
            Mockito.when(transactionPostingService.postDailyTransactions(businessDate))
                    .thenThrow(new RuntimeException("RDS connection failed"));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("posttran-svc-fail-once", "2022-07-18"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            verify(transactionPostingService, times(1))
                    .postDailyTransactions(businessDate);
        }
    }

    // =========================================================================
    // Helper methods — shared across @Nested groups for compactness.
    // =========================================================================

    /**
     * Builds the canonical {@link JobParameters} for a
     * {@code dailyTransactionPostingJob} launch: batchRunId, businessDate
     * (ISO-8601), and a derived correlationId. Used by every test that
     * launches with a valid batchRunId and businessDate.
     *
     * @param batchRunId   unique batch run identifier (required by the
     *                     {@code standardJobParametersValidator})
     * @param businessDate ISO-8601 {@code yyyy-MM-dd} businessDate
     * @return the constructed immutable {@link JobParameters}
     */
    private JobParameters postingParams(String batchRunId, String businessDate) {
        return new JobParametersBuilder()
                .addString(DailyTransactionPostingJob.PARAM_BATCH_RUN_ID, batchRunId)
                .addString(DailyTransactionPostingJob.PARAM_BUSINESS_DATE, businessDate)
                .addString("correlationId", "corr-" + batchRunId)
                .toJobParameters();
    }

    /**
     * Extracts the single step's {@link ExecutionContext} from a
     * {@link JobExecution}. The {@code dailyTransactionPostingJob} has
     * exactly one step ({@code postDailyTransactionsStep}); any
     * deviation from this invariant should fail the test immediately
     * so the upstream regression is caught by the assertion.
     *
     * @param execution the {@link JobExecution} to inspect
     * @return the single step's {@link ExecutionContext}
     */
    private static ExecutionContext singleStepContext(JobExecution execution) {
        assertThat(execution.getStepExecutions()).hasSize(1);
        return execution.getStepExecutions().iterator().next().getExecutionContext();
    }

    /**
     * AssertJ {@code Predicate<Throwable>} &mdash; {@code true} if the
     * throwable is an {@link IllegalArgumentException}. Used in the
     * ParameterValidation {@code @Nested} group to assert that the
     * tasklet's businessDate validation surfaces as an
     * IllegalArgumentException in
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
