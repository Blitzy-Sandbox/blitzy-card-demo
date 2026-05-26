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
// PrintCategoryBalanceJobTest — Spring Batch orchestration tests
// =============================================================================
//
// Replaces JCL job stream: app/jcl/PRTCATBL.jcl
// COBOL: none (pure DFSORT utility — no COBOL program backs PRTCATBL.jcl)
//
// Validates the Spring Batch printCategoryBalanceJob @Configuration class
// that replaces the COBOL PRTCATBL.jcl DFSORT utility job. The original
// JCL had three steps: DELDEF (IEFBR14 file delete), STEP05R (IDCAMS
// REPRO unload), STEP10R (DFSORT sort + EDIT-mask emit). The Java target
// folds the read + sort + render + S3-write into a single tasklet.
//
// This test focuses on Spring Batch JOB-LEVEL ORCHESTRATION concerns
// — JobParameters validation, ExitStatus propagation, ExecutionContext
// publishing, S3OutputService invocation, AuditLogService lifecycle
// audit emission, and repository failure propagation.
//
// Per AAP §0.4.1 and the sibling InterestCalculationJobTest /
// CombineTransactionsJobTest / DailyTransactionPostingJobTest pattern:
//
//   * @SpringBootTest          — full ApplicationContext bootstrap so
//                                the printCategoryBalanceJob Job bean
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
//   * @MockBean                — replaces TransactionCategoryBalanceRepository,
//                                S3OutputService, and every AWS SDK /
//                                Kafka / Redis / OpenSearch client with
//                                a Mockito mock so the test does not
//                                require live AWS infrastructure or
//                                business-data fixtures.
//
// @Nested test groups (per file schema members_exposed):
//   * HappyPath
//   * ParameterValidation
//   * EmptyBalanceTable
//   * RepositoryFailurePropagation
//   * S3FailurePropagation
//   * OutputFormat
//   * SortOrder
//
// AAP cross-references:
//   §0.4.1  — transformation mapping (PrintCategoryBalanceJob: CREATE)
//   §0.6.1  — BigDecimal scale=2 + HALF_EVEN preservation
//   §0.6.3  — JCL → Step Functions orchestration (batch-job lifecycle)
//   §0.6.6  — Audit-trail emission via AuditLogService
//   §0.7.1  — Refactoring rules: Jakarta only, AWS SDK v2 only,
//             constructor injection, isolated adapter classes
//   §0.7.3  — Minimal Change Clause — sort order preserved as a Java
//             Comparator chain matching SORT FIELDS=(...,A,...,A,...,A)

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.adapter.KafkaEventPublisher;
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.adapter.SecretsManagerService;
import com.awsm2.carddemo.adapter.StepFunctionsOrchestrator;
import com.awsm2.carddemo.domain.TransactionCategoryBalance;
import com.awsm2.carddemo.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.awsm2.carddemo.repository.TransactionCategoryBalanceRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.data.domain.Sort;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
 * Spring Batch end-to-end tests for {@link PrintCategoryBalanceJob}.
 *
 * <p><b>// Replaces: app/jcl/PRTCATBL.jcl</b>. The tests verify Spring
 * Batch orchestration concerns &mdash; JobParameters parsing,
 * ExecutionContext publishing, ExitStatus propagation, S3 write
 * invocation, and lifecycle audit-trail emission &mdash; while also
 * unit-testing the package-private formatting helpers
 * ({@link PrintCategoryBalanceJob#formatTcatBalLine(TransactionCategoryBalance)}
 * and
 * {@link PrintCategoryBalanceJob#editTranCatBal(BigDecimal)}) for
 * byte-level format conformance to the COBOL DFSORT OUTREC layout.</p>
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@Import(PrintCategoryBalanceJobTest.TestSecretsManagerConfiguration.class)
@DisplayName("PrintCategoryBalanceJob — Spring Batch orchestration tests (JCL: PRTCATBL.jcl)")
class PrintCategoryBalanceJobTest {

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
            // the Testcontainers PostgreSQL. Mirrors the sibling test
            // pattern.
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
    // PostgreSQL 16 engine baseline (AAP §0.5.1, §0.6.2).
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
    // synchronous launch of printCategoryBalanceJob with the test-supplied
    // JobParameters.
    // =========================================================================
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(PrintCategoryBalanceJob.JOB_NAME)
    private Job printCategoryBalanceJob;

    // =========================================================================
    // @MockBean — primary collaborator under test (the repository and the
    // S3OutputService adapter) plus every AWS SDK / Kafka / Redis /
    // OpenSearch / adapter bean that the @SpringBootTest ApplicationContext
    // would otherwise try to resolve against live infrastructure or AWS
    // credentials. The mock posture mirrors the sibling test classes.
    // =========================================================================
    @MockBean
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    @MockBean
    private S3OutputService s3OutputService;

    @MockBean
    private AuditLogService auditLogService;

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
        Mockito.reset(transactionCategoryBalanceRepository, s3OutputService, auditLogService);
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJob(printCategoryBalanceJob);
    }

    // =========================================================================
    // HappyPath — printCategoryBalanceJob completes with a populated
    // TCATBAL set, sorts and renders the rows, writes the byte buffer to
    // S3, and emits the COMPLETED audit lifecycle.
    // =========================================================================

    @Nested
    @DisplayName("HappyPath — Job completes and writes the formatted report to S3")
    class HappyPath {

        @Test
        @DisplayName("renders all rows in sort order and writes to S3 via writeReport()")
        void rendersAllRowsAndWritesToS3() throws Exception {
            // Three rows, deliberately out of canonical order so the
            // Java Comparator chain must reorder them to match the
            // COBOL SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,
            // TRANCAT-CD,A) semantic.
            List<TransactionCategoryBalance> unsorted = Arrays.asList(
                    row(20000000001L, "02", 1000, "1500.00"),
                    row(10000000001L, "01", 1000, "200.50"),
                    row(10000000001L, "01", 2000, "0.50"));
            Mockito.when(transactionCategoryBalanceRepository.findAll(any(Sort.class)))
                    .thenReturn(unsorted);

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    params("happy-001", "2026-05-20"));

            // ----- Job-level assertions -----
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());

            // ----- ExecutionContext assertions -----
            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(PrintCategoryBalanceJob.CTX_ROW_COUNT))
                    .isEqualTo(3);
            // Each line is 40 bytes + 1 LF = 41 bytes; 3 rows → 123 bytes.
            assertThat(stepContext.getInt(PrintCategoryBalanceJob.CTX_REPORT_BYTES))
                    .isEqualTo(3 * 41);
            assertThat(stepContext.getString(PrintCategoryBalanceJob.CTX_REPORT_ID))
                    .isEqualTo("tcatbal-happy-001");

            // ----- S3 write invocation assertions -----
            ArgumentCaptor<String> reportIdCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
            verify(s3OutputService, times(1))
                    .writeReport(reportIdCaptor.capture(), bytesCaptor.capture());

            assertThat(reportIdCaptor.getValue()).isEqualTo("tcatbal-happy-001");
            String reportText = new String(bytesCaptor.getValue(),
                    StandardCharsets.US_ASCII);
            String[] lines = reportText.split("\n");
            assertThat(lines).hasSize(3);

            // Verify sort order: first line is acct 10000000001, type 01, cat 1000
            assertThat(lines[0])
                    .startsWith("10000000001 01 1000 ");
            assertThat(lines[1])
                    .startsWith("10000000001 01 2000 ");
            assertThat(lines[2])
                    .startsWith("20000000001 02 1000 ");

            // Each line is exactly 40 bytes
            for (String line : lines) {
                assertThat(line.length()).isEqualTo(40);
            }

            // ----- Audit lifecycle assertion -----
            verify(auditLogService, atLeastOnce()).logBatchJobLifecycle(
                    eq(PrintCategoryBalanceJob.JOB_NAME),
                    anyString(),
                    eq("COMPLETED"),
                    isNull(),
                    any(Map.class),
                    eq("corr-happy-001"));
        }

        @Test
        @DisplayName("Single row — completes and writes a single 40-byte + LF line to S3")
        void singleRow_completes() throws Exception {
            Mockito.when(transactionCategoryBalanceRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.singletonList(
                            row(99999999999L, "ZZ", 9999, "999999999.99")));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    params("happy-single", "2026-05-20"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(PrintCategoryBalanceJob.CTX_ROW_COUNT))
                    .isEqualTo(1);
            assertThat(stepContext.getInt(PrintCategoryBalanceJob.CTX_REPORT_BYTES))
                    .isEqualTo(41); // 40 + LF

            verify(s3OutputService, times(1))
                    .writeReport(eq("tcatbal-happy-single"), any(byte[].class));
        }

        @Test
        @DisplayName("parmDate is optional — null parmDate still completes")
        void optionalParmDate_completes() throws Exception {
            Mockito.when(transactionCategoryBalanceRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.singletonList(
                            row(10000000001L, "01", 1000, "100.00")));

            JobParameters params = new JobParametersBuilder()
                    .addString(PrintCategoryBalanceJob.PARAM_BATCH_RUN_ID, "happy-no-date")
                    .addString("correlationId", "corr-happy-no-date")
                    .toJobParameters();

            JobExecution execution = jobLauncherTestUtils.launchJob(params);

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            verify(s3OutputService, times(1))
                    .writeReport(eq("tcatbal-happy-no-date"), any(byte[].class));
        }
    }

    // =========================================================================
    // ParameterValidation — the standardJobParametersValidator from
    // BatchJobConfig enforces a non-blank batchRunId before the tasklet
    // is ever invoked. The tasklet itself parses an OPTIONAL parmDate at
    // runtime (throws IllegalArgumentException for malformed values).
    // =========================================================================

    @Nested
    @DisplayName("ParameterValidation — Standard validator enforces batchRunId; tasklet validates parmDate format")
    class ParameterValidation {

        @Test
        @DisplayName("Missing batchRunId throws JobParametersInvalidException synchronously")
        void missingBatchRunId_throwsJobParametersInvalidException() {
            JobParameters params = new JobParametersBuilder()
                    .addString(PrintCategoryBalanceJob.PARAM_PARM_DATE, "2026-05-20")
                    .addString("correlationId", "corr-missing-runId")
                    .toJobParameters();

            assertThatThrownBy(() -> jobLauncherTestUtils.launchJob(params))
                    .isInstanceOf(JobParametersInvalidException.class)
                    .hasMessageContaining("batchRunId");

            verify(transactionCategoryBalanceRepository, never())
                    .findAll(any(Sort.class));
            verify(s3OutputService, never())
                    .writeReport(anyString(), any(byte[].class));
        }

        @Test
        @DisplayName("Empty batchRunId throws JobParametersInvalidException synchronously")
        void emptyBatchRunId_throwsJobParametersInvalidException() {
            JobParameters params = new JobParametersBuilder()
                    .addString(PrintCategoryBalanceJob.PARAM_BATCH_RUN_ID, "   ")
                    .addString(PrintCategoryBalanceJob.PARAM_PARM_DATE, "2026-05-20")
                    .addString("correlationId", "corr-empty-runId")
                    .toJobParameters();

            assertThatThrownBy(() -> jobLauncherTestUtils.launchJob(params))
                    .isInstanceOf(JobParametersInvalidException.class)
                    .hasMessageContaining("batchRunId");
        }

        @Test
        @DisplayName("Malformed parmDate causes job to FAIL with IllegalArgumentException captured")
        void malformedParmDate_failsJob() throws Exception {
            JobParameters params = new JobParametersBuilder()
                    .addString(PrintCategoryBalanceJob.PARAM_BATCH_RUN_ID, "bad-date-001")
                    .addString(PrintCategoryBalanceJob.PARAM_PARM_DATE, "JULY-2022")
                    .addString("correlationId", "corr-bad-date")
                    .toJobParameters();

            JobExecution execution = jobLauncherTestUtils.launchJob(params);

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(PrintCategoryBalanceJobTest::isIllegalArgumentException);

            verify(s3OutputService, never())
                    .writeReport(anyString(), any(byte[].class));
        }
    }

    // =========================================================================
    // EmptyBalanceTable — when the TCATBAL table is empty, the job
    // completes with rowCount=0 and SKIPS the S3 write (per the tasklet
    // implementation: writeReport rejects empty byte arrays). This
    // matches the COBOL DFSORT semantic of producing an empty SORTOUT
    // dataset when SORTIN has zero records (DFSORT returns RC=0).
    // =========================================================================

    @Nested
    @DisplayName("EmptyBalanceTable — Empty TCATBAL completes COMPLETED with no S3 write")
    class EmptyBalanceTable {

        @Test
        @DisplayName("Zero TCATBAL rows → job COMPLETED; no S3 write")
        void emptyTable_completesNoS3Write() throws Exception {
            Mockito.when(transactionCategoryBalanceRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    params("empty-table", "2099-01-01"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(PrintCategoryBalanceJob.CTX_ROW_COUNT))
                    .isZero();
            assertThat(stepContext.getInt(PrintCategoryBalanceJob.CTX_REPORT_BYTES))
                    .isZero();

            // S3 PUT is SKIPPED when the report is empty per the
            // tasklet's defensive guard against S3OutputService's
            // IllegalArgumentException on empty byte[] inputs.
            verify(s3OutputService, never())
                    .writeReport(anyString(), any(byte[].class));

            verify(auditLogService, atLeastOnce()).logBatchJobLifecycle(
                    eq(PrintCategoryBalanceJob.JOB_NAME),
                    anyString(),
                    eq("COMPLETED"),
                    isNull(),
                    any(Map.class),
                    eq("corr-empty-table"));
        }
    }

    // =========================================================================
    // RepositoryFailurePropagation — when the JPA repository throws (e.g.,
    // RDS connectivity loss), the Spring Batch step transitions to FAILED
    // and the originating exception is captured in
    // JobExecution.getAllFailureExceptions().
    // =========================================================================

    @Nested
    @DisplayName("RepositoryFailurePropagation — Repository exceptions cause job FAILED status")
    class RepositoryFailurePropagation {

        @Test
        @DisplayName("Repository RuntimeException → job FAILED with failureException captured")
        void failsWhenRepositoryThrows() throws Exception {
            Mockito.when(transactionCategoryBalanceRepository.findAll(any(Sort.class)))
                    .thenThrow(new IllegalStateException("RDS connectivity lost"));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    params("repo-fail", "2026-05-20"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(t -> t instanceof IllegalStateException
                            && t.getMessage() != null
                            && t.getMessage().contains("RDS connectivity lost"));

            verify(s3OutputService, never())
                    .writeReport(anyString(), any(byte[].class));
        }
    }

    // =========================================================================
    // S3FailurePropagation — when S3OutputService.writeReport throws, the
    // job transitions to FAILED.
    // =========================================================================

    @Nested
    @DisplayName("S3FailurePropagation — S3 exceptions cause job FAILED status")
    class S3FailurePropagation {

        @Test
        @DisplayName("S3 RuntimeException → job FAILED with failureException captured")
        void failsWhenS3Throws() throws Exception {
            Mockito.when(transactionCategoryBalanceRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.singletonList(
                            row(10000000001L, "01", 1000, "100.00")));
            Mockito.doThrow(new IllegalStateException("S3 PUT failed"))
                    .when(s3OutputService)
                    .writeReport(anyString(), any(byte[].class));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    params("s3-fail", "2026-05-20"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(t -> t instanceof IllegalStateException
                            && t.getMessage() != null
                            && t.getMessage().contains("S3 PUT failed"));
        }
    }

    // =========================================================================
    // OutputFormat — direct unit testing of the package-private formatters
    // formatTcatBalLine() and editTranCatBal(). These tests do NOT need the
    // full Spring Batch context; they assert byte-level conformance to the
    // DFSORT OUTREC layout per AAP §0.7.3 (Minimal Change Clause).
    // =========================================================================

    @Nested
    @DisplayName("OutputFormat — formatTcatBalLine() produces 40-byte ASCII per DFSORT OUTREC")
    class OutputFormat {

        @Test
        @DisplayName("Formats a typical row to 40 bytes with correct field positions")
        void formatsTypicalRow_to40Bytes() {
            TransactionCategoryBalance bal = row(12345678901L, "01", 5000, "1234.56");
            String line = PrintCategoryBalanceJob.formatTcatBalLine(bal);

            // 40 bytes total
            assertThat(line.length()).isEqualTo(40);

            // Field-by-field assertions
            // pos  1-11: ACCT-ID zero-padded
            assertThat(line.substring(0, 11)).isEqualTo("12345678901");
            // pos    12: space
            assertThat(line.charAt(11)).isEqualTo(' ');
            // pos 13-14: TYPE-CD
            assertThat(line.substring(12, 14)).isEqualTo("01");
            // pos    15: space
            assertThat(line.charAt(14)).isEqualTo(' ');
            // pos 16-19: CAT-CD zero-padded
            assertThat(line.substring(15, 19)).isEqualTo("5000");
            // pos    20: space
            assertThat(line.charAt(19)).isEqualTo(' ');
            // pos 21-32: BAL with EDIT mask (right-justified in 12 chars)
            assertThat(line.substring(20, 32)).isEqualTo("     1234.56");
            // pos 33-40: 8 trailing spaces
            assertThat(line.substring(32, 40)).isEqualTo("        ");
        }

        @Test
        @DisplayName("Formats max-precision value PIC S9(09)V99 (999999999.99) without truncation")
        void formatsMaxPrecisionValue() {
            TransactionCategoryBalance bal = row(10000000001L, "AA", 1234, "999999999.99");
            String line = PrintCategoryBalanceJob.formatTcatBalLine(bal);

            assertThat(line.length()).isEqualTo(40);
            assertThat(line.substring(20, 32)).isEqualTo("999999999.99");
        }

        @Test
        @DisplayName("Formats zero balance with leading-space-padded scale=2 representation")
        void formatsZeroBalance() {
            TransactionCategoryBalance bal = row(10000000001L, "01", 1000,
                    BigDecimal.ZERO.toPlainString());
            String line = PrintCategoryBalanceJob.formatTcatBalLine(bal);

            assertThat(line.length()).isEqualTo(40);
            // Zero with scale=2 renders as "0.00" right-justified
            assertThat(line.substring(20, 32)).isEqualTo("        0.00");
        }

        @Test
        @DisplayName("Formats sub-dollar fractional balance correctly")
        void formatsSubDollarBalance() {
            TransactionCategoryBalance bal = row(10000000001L, "01", 1000, "0.50");
            String line = PrintCategoryBalanceJob.formatTcatBalLine(bal);

            assertThat(line.length()).isEqualTo(40);
            assertThat(line.substring(20, 32)).isEqualTo("        0.50");
        }

        @Test
        @DisplayName("editTranCatBal renders BigDecimal.ZERO as '        0.00'")
        void editTranCatBal_zero() {
            assertThat(PrintCategoryBalanceJob.editTranCatBal(BigDecimal.ZERO))
                    .isEqualTo("        0.00");
        }

        @Test
        @DisplayName("editTranCatBal renders null as '        0.00' (defensive normalization)")
        void editTranCatBal_null() {
            assertThat(PrintCategoryBalanceJob.editTranCatBal(null))
                    .isEqualTo("        0.00");
        }

        @Test
        @DisplayName("editTranCatBal applies HALF_EVEN banker's rounding (0.585 → 0.58)")
        void editTranCatBal_halfEven() {
            assertThat(PrintCategoryBalanceJob.editTranCatBal(new BigDecimal("0.585")))
                    .isEqualTo("        0.58");
            // 0.575 → 0.58 (half-even rounds 5 to even; previous digit 7 odd → rounds up to 8)
            assertThat(PrintCategoryBalanceJob.editTranCatBal(new BigDecimal("0.575")))
                    .isEqualTo("        0.58");
        }

        @Test
        @DisplayName("editTranCatBal throws IllegalArgumentException for value beyond PIC S9(09)V99 domain")
        void editTranCatBal_overflow_throws() {
            // PIC S9(09)V99 max = 999999999.99 — anything wider exceeds
            // the 12-char EDIT mask and is an arithmetic overflow.
            assertThatThrownBy(() ->
                    PrintCategoryBalanceJob.editTranCatBal(new BigDecimal("1000000000.00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds")
                    .hasMessageContaining("EDIT mask");
        }
    }

    // =========================================================================
    // SortOrder — verifies the canonical Comparator chain matches the
    // COBOL SORT FIELDS=(...,A,...,A,...,A) semantic. Direct unit testing
    // of the static CANONICAL_ORDER constant.
    // =========================================================================

    @Nested
    @DisplayName("SortOrder — CANONICAL_ORDER matches COBOL SORT FIELDS=(...,A,...,A,...,A)")
    class SortOrder {

        @Test
        @DisplayName("Sorts by acctId ascending, then typeCd ascending, then catCd ascending")
        void sortsByCompositeKeyAscending() {
            List<TransactionCategoryBalance> rows = new ArrayList<>(Arrays.asList(
                    row(20000000001L, "02", 1000, "100.00"),
                    row(10000000001L, "02", 1000, "100.00"),
                    row(10000000001L, "01", 2000, "100.00"),
                    row(10000000001L, "01", 1000, "100.00"),
                    row(20000000001L, "01", 1000, "100.00")));

            rows.sort(PrintCategoryBalanceJob.CANONICAL_ORDER);

            assertThat(rows.get(0).getId().getTrancatAcctId()).isEqualTo(10000000001L);
            assertThat(rows.get(0).getId().getTrancatTypeCd()).isEqualTo("01");
            assertThat(rows.get(0).getId().getTrancatCd()).isEqualTo(1000);

            assertThat(rows.get(1).getId().getTrancatAcctId()).isEqualTo(10000000001L);
            assertThat(rows.get(1).getId().getTrancatTypeCd()).isEqualTo("01");
            assertThat(rows.get(1).getId().getTrancatCd()).isEqualTo(2000);

            assertThat(rows.get(2).getId().getTrancatAcctId()).isEqualTo(10000000001L);
            assertThat(rows.get(2).getId().getTrancatTypeCd()).isEqualTo("02");
            assertThat(rows.get(2).getId().getTrancatCd()).isEqualTo(1000);

            assertThat(rows.get(3).getId().getTrancatAcctId()).isEqualTo(20000000001L);
            assertThat(rows.get(3).getId().getTrancatTypeCd()).isEqualTo("01");

            assertThat(rows.get(4).getId().getTrancatAcctId()).isEqualTo(20000000001L);
            assertThat(rows.get(4).getId().getTrancatTypeCd()).isEqualTo("02");
        }
    }

    // =========================================================================
    // Helper methods — shared across @Nested groups for compactness.
    // =========================================================================

    /**
     * Builds a {@link JobParameters} instance with the canonical
     * batchRunId + parmDate + correlationId trio.
     *
     * @param batchRunId unique batch run identifier (required by the
     *                   standardJobParametersValidator)
     * @param parmDate   ISO-8601 {@code yyyy-MM-dd} parmDate (optional;
     *                   recorded in audit trail)
     * @return the constructed immutable {@link JobParameters}
     */
    private JobParameters params(String batchRunId, String parmDate) {
        return new JobParametersBuilder()
                .addString(PrintCategoryBalanceJob.PARAM_BATCH_RUN_ID, batchRunId)
                .addString(PrintCategoryBalanceJob.PARAM_PARM_DATE, parmDate)
                .addString("correlationId", "corr-" + batchRunId)
                .toJobParameters();
    }

    /**
     * Builds a {@link TransactionCategoryBalance} fixture for testing.
     *
     * @param acctId   the 11-digit account identifier
     * @param typeCd   the 2-character transaction-type code
     * @param catCd    the 4-digit transaction-category code
     * @param balance  the running balance as a plain-string BigDecimal
     * @return the constructed {@link TransactionCategoryBalance}
     */
    private static TransactionCategoryBalance row(long acctId, String typeCd, int catCd,
                                                  String balance) {
        TransactionCategoryBalanceId id =
                new TransactionCategoryBalanceId(acctId, typeCd, catCd);
        return new TransactionCategoryBalance(id, new BigDecimal(balance));
    }

    /**
     * Extracts the single step's {@link ExecutionContext} from a
     * {@link JobExecution}. The {@code printCategoryBalanceJob} has
     * exactly one step ({@code printCategoryBalanceStep}); any deviation
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
     * {@link IllegalArgumentException}.
     *
     * @param throwable the throwable to inspect
     * @return {@code true} if {@code throwable} is an
     *         {@link IllegalArgumentException}; {@code false} otherwise
     */
    private static boolean isIllegalArgumentException(Throwable throwable) {
        return throwable instanceof IllegalArgumentException;
    }
}
