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

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.adapter.KafkaEventPublisher;
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.adapter.SecretsManagerService;
import com.awsm2.carddemo.adapter.StepFunctionsOrchestrator;
import com.awsm2.carddemo.service.TransactionReportService;
import com.awsm2.carddemo.service.TransactionReportService.ReportResult;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Spring Batch tests for {@link TransactionReportJob}.
 *
 * <p><b>// Replaces: app/jcl/TRANREPT.jcl + app/cbl/CBTRN03C.cbl</b>.
 * The tests verify that the report tasklet forwards the date window to
 * the translated service, exposes report counts and S3 keys through the
 * batch {@link org.springframework.batch.item.ExecutionContext}, and
 * fails on invalid JCL-equivalent date parameters.</p>
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@Import(TransactionReportJobTest.TestSecretsManagerConfiguration.class)
@DisplayName("TransactionReportJob — tasklet orchestration tests (JCL: TRANREPT.jcl)")
class TransactionReportJobTest {

    @TestConfiguration
    static class TestSecretsManagerConfiguration {
        private static final String TEST_SIGNING_KEY =
                "test-only-jwt-signing-key-for-hs256-context-smoke-test-padded";

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

        @Bean
        DataSourceInitializer batchSchemaInitializer(final DataSource dataSource) {
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

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(TransactionReportJob.JOB_NAME)
    private Job transactionReportJob;

    @MockBean
    private TransactionReportService transactionReportService;

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
        Mockito.reset(transactionReportService, auditLogService);
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJob(transactionReportJob);
    }

    @Nested
    @DisplayName("happy-path execution")
    class HappyPath {

        @Test
        @DisplayName("generates the transaction report and publishes report metadata")
        void generatesReportAndPublishesMetadata() throws Exception {
            Mockito.when(transactionReportService.generateReport(
                            LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-31")))
                    .thenReturn(new ReportResult(
                            125, 7, new BigDecimal("9876.54"),
                            "reports/transaction/2026-05/report.txt"));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    reportParams("report-happy", "2026-05-01", "2026-05-31"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());
            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(TransactionReportJob.CTX_TRANSACTION_COUNT))
                    .isEqualTo(125);
            assertThat(stepContext.getInt(TransactionReportJob.CTX_PAGE_COUNT)).isEqualTo(7);
            assertThat(stepContext.getString(TransactionReportJob.CTX_GRAND_TOTAL))
                    .isEqualTo("9876.54");
            assertThat(stepContext.getString(TransactionReportJob.CTX_S3_KEY))
                    .isEqualTo("reports/transaction/2026-05/report.txt");

            verify(transactionReportService).generateReport(
                    LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-31"));
            verify(auditLogService, atLeastOnce()).logBatchJobLifecycle(
                    eq(TransactionReportJob.JOB_NAME),
                    anyString(),
                    eq("COMPLETED"),
                    isNull(),
                    any(Map.class),
                    eq("corr-report-happy"));
        }

        @Test
        @DisplayName("normalizes a null grand total to 0.00 while preserving the S3 key")
        void normalizesNullGrandTotal() throws Exception {
            Mockito.when(transactionReportService.generateReport(any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(new ReportResult(0, 0, null, "reports/transaction/empty.txt"));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    reportParams("report-null-total", "2026-05-01", "2026-05-31"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getString(TransactionReportJob.CTX_GRAND_TOTAL))
                    .isEqualTo("0.00");
            assertThat(stepContext.getString(TransactionReportJob.CTX_S3_KEY))
                    .isEqualTo("reports/transaction/empty.txt");
        }
    }

    @Nested
    @DisplayName("parameter validation")
    class ParameterValidation {

        @Test
        @DisplayName("fails when startDate is missing")
        void failsWhenStartDateIsMissing() throws Exception {
            JobExecution execution = jobLauncherTestUtils.launchJob(
                    new JobParametersBuilder()
                            .addString(TransactionReportJob.PARAM_BATCH_RUN_ID,
                                    "report-missing-start")
                            .addString(TransactionReportJob.PARAM_END_DATE, "2026-05-31")
                            .addString("correlationId", "corr-report-missing-start")
                            .toJobParameters());

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(TransactionReportJobTest::isIllegalArgumentException);
            verify(transactionReportService, never())
                    .generateReport(any(LocalDate.class), any(LocalDate.class));
        }

        @Test
        @DisplayName("fails when endDate is missing")
        void failsWhenEndDateIsMissing() throws Exception {
            JobExecution execution = jobLauncherTestUtils.launchJob(
                    new JobParametersBuilder()
                            .addString(TransactionReportJob.PARAM_BATCH_RUN_ID,
                                    "report-missing-end")
                            .addString(TransactionReportJob.PARAM_START_DATE, "2026-05-01")
                            .addString("correlationId", "corr-report-missing-end")
                            .toJobParameters());

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(TransactionReportJobTest::isIllegalArgumentException);
            verify(transactionReportService, never())
                    .generateReport(any(LocalDate.class), any(LocalDate.class));
        }

        @Test
        @DisplayName("fails when startDate is not ISO-8601")
        void failsWhenStartDateIsNotIso8601() throws Exception {
            JobExecution execution = jobLauncherTestUtils.launchJob(
                    reportParams("report-invalid-start", "05-01-2026", "2026-05-31"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(TransactionReportJobTest::isIllegalArgumentException);
            verify(transactionReportService, never())
                    .generateReport(any(LocalDate.class), any(LocalDate.class));
        }

        @Test
        @DisplayName("fails when endDate is not ISO-8601")
        void failsWhenEndDateIsNotIso8601() throws Exception {
            JobExecution execution = jobLauncherTestUtils.launchJob(
                    reportParams("report-invalid-end", "2026-05-01", "31-05-2026"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(TransactionReportJobTest::isIllegalArgumentException);
            verify(transactionReportService, never())
                    .generateReport(any(LocalDate.class), any(LocalDate.class));
        }
    }

    @Nested
    @DisplayName("failure propagation")
    class ServiceFailurePropagation {

        @Test
        @DisplayName("fails the job when TransactionReportService throws")
        void failsWhenTransactionReportServiceThrows() throws Exception {
            Mockito.when(transactionReportService.generateReport(any(LocalDate.class), any(LocalDate.class)))
                    .thenThrow(new IllegalStateException("TRANREPT service failure"));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    reportParams("report-service-failure", "2026-05-01", "2026-05-31"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(ex -> ex instanceof IllegalStateException
                            && ex.getMessage().contains("TRANREPT service failure"));
        }
    }

    private JobParameters reportParams(String batchRunId, String startDate, String endDate) {
        return new JobParametersBuilder()
                .addString(TransactionReportJob.PARAM_BATCH_RUN_ID, batchRunId)
                .addString(TransactionReportJob.PARAM_START_DATE, startDate)
                .addString(TransactionReportJob.PARAM_END_DATE, endDate)
                .addString("correlationId", "corr-" + batchRunId)
                .toJobParameters();
    }

    private static ExecutionContext singleStepContext(JobExecution execution) {
        assertThat(execution.getStepExecutions()).hasSize(1);
        return execution.getStepExecutions().iterator().next().getExecutionContext();
    }

    private static boolean isIllegalArgumentException(Throwable throwable) {
        return throwable instanceof IllegalArgumentException;
    }
}