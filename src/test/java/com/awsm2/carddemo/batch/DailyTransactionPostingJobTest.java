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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Spring Batch tests for {@link DailyTransactionPostingJob}.
 *
 * <p><b>// Replaces: app/jcl/POSTTRAN.jcl + app/cbl/CBTRN02C.cbl</b>.
 * The tests verify that the tasklet preserves the COBOL RETURN-CODE
 * contract (0, 4, 8), publishes per-run counters to the Spring Batch
 * {@link org.springframework.batch.item.ExecutionContext}, and fails
 * fast on invalid JCL-equivalent parameters.</p>
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@Import(DailyTransactionPostingJobTest.TestSecretsManagerConfiguration.class)
@DisplayName("DailyTransactionPostingJob — tasklet orchestration tests (JCL: POSTTRAN.jcl)")
class DailyTransactionPostingJobTest {

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
    @Qualifier(DailyTransactionPostingJob.JOB_NAME)
    private Job dailyTransactionPostingJob;

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
        Mockito.reset(transactionPostingService, auditLogService);
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJob(dailyTransactionPostingJob);
    }

    @Nested
    @DisplayName("happy-path execution")
    class HappyPath {

        @Test
        @DisplayName("posts daily transactions and publishes counts to ExecutionContext")
        void postsDailyTransactionsAndPublishesCounts() throws Exception {
            Mockito.when(transactionPostingService.postDailyTransactions(LocalDate.parse("2026-05-20")))
                    .thenReturn(new PostingResult(25, 0, CardDemoExitStatus.RETURN_CODE_COMPLETED));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("post-happy", "2026-05-20"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());
            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_TRANSACTION_COUNT))
                    .isEqualTo(25);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_REJECT_COUNT))
                    .isZero();
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_RETURN_CODE))
                    .isEqualTo(CardDemoExitStatus.RETURN_CODE_COMPLETED);

            verify(transactionPostingService)
                    .postDailyTransactions(LocalDate.parse("2026-05-20"));
            verify(auditLogService, atLeastOnce()).logBatchJobLifecycle(
                    eq(DailyTransactionPostingJob.JOB_NAME),
                    anyString(),
                    eq("COMPLETED"),
                    isNull(),
                    any(Map.class),
                    eq("corr-post-happy"));
        }
    }

    @Nested
    @DisplayName("COBOL RETURN-CODE to ExitStatus mapping")
    class ExitStatusMapping {

        @Test
        @DisplayName("maps RETURN-CODE 0 to COMPLETED")
        void mapsReturnCodeZeroToCompleted() throws Exception {
            Mockito.when(transactionPostingService.postDailyTransactions(any(LocalDate.class)))
                    .thenReturn(new PostingResult(8, 0, CardDemoExitStatus.RETURN_CODE_COMPLETED));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("post-rc0", "2026-05-20"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());
        }

        @Test
        @DisplayName("maps RETURN-CODE 4 to COMPLETED_WITH_REJECTS")
        void mapsReturnCodeFourToCompletedWithRejects() throws Exception {
            Mockito.when(transactionPostingService.postDailyTransactions(any(LocalDate.class)))
                    .thenReturn(new PostingResult(
                            10, 2, CardDemoExitStatus.RETURN_CODE_WITH_REJECTS));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("post-rc4", "2026-05-20"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(CardDemoExitStatus.COMPLETED_WITH_REJECTS.getExitCode());
            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_REJECT_COUNT))
                    .isEqualTo(2);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_RETURN_CODE))
                    .isEqualTo(CardDemoExitStatus.RETURN_CODE_WITH_REJECTS);
        }

        @Test
        @DisplayName("maps RETURN-CODE 8 to FAILED exit status")
        void mapsReturnCodeEightToFailedExitStatus() throws Exception {
            Mockito.when(transactionPostingService.postDailyTransactions(any(LocalDate.class)))
                    .thenReturn(new PostingResult(10, 0, CardDemoExitStatus.RETURN_CODE_FAILED));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("post-rc8", "2026-05-20"));

            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.FAILED.getExitCode());
            ExecutionContext stepContext = singleStepContext(execution);
            assertThat(stepContext.getInt(DailyTransactionPostingJob.CTX_RETURN_CODE))
                    .isEqualTo(CardDemoExitStatus.RETURN_CODE_FAILED);
        }
    }

    @Nested
    @DisplayName("parameter validation")
    class ParameterValidation {

        @Test
        @DisplayName("fails when businessDate is missing")
        void failsWhenBusinessDateIsMissing() throws Exception {
            JobExecution execution = jobLauncherTestUtils.launchJob(
                    new JobParametersBuilder()
                            .addString(DailyTransactionPostingJob.PARAM_BATCH_RUN_ID,
                                    "post-missing-date")
                            .addString("correlationId", "corr-post-missing-date")
                            .toJobParameters());

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(DailyTransactionPostingJobTest::isIllegalArgumentException);
            verify(transactionPostingService, never()).postDailyTransactions(any(LocalDate.class));
        }

        @Test
        @DisplayName("fails when businessDate is not ISO-8601")
        void failsWhenBusinessDateIsNotIso8601() throws Exception {
            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("post-invalid-date", "05/20/2026"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(DailyTransactionPostingJobTest::isIllegalArgumentException);
            verify(transactionPostingService, never()).postDailyTransactions(any(LocalDate.class));
        }
    }

    @Nested
    @DisplayName("failure propagation")
    class ServiceFailurePropagation {

        @Test
        @DisplayName("fails the job when TransactionPostingService throws")
        void failsWhenTransactionPostingServiceThrows() throws Exception {
            Mockito.when(transactionPostingService.postDailyTransactions(any(LocalDate.class)))
                    .thenThrow(new IllegalStateException("POSTTRAN service failure"));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    postingParams("post-service-failure", "2026-05-20"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(ex -> ex instanceof IllegalStateException
                            && ex.getMessage().contains("POSTTRAN service failure"));
        }
    }

    private JobParameters postingParams(String batchRunId, String businessDate) {
        return new JobParametersBuilder()
                .addString(DailyTransactionPostingJob.PARAM_BATCH_RUN_ID, batchRunId)
                .addString(DailyTransactionPostingJob.PARAM_BUSINESS_DATE, businessDate)
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