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
 * Spring Batch tests for {@link InterestCalculationJob}.
 *
 * <p><b>// Replaces: app/jcl/INTCALC.jcl + app/cbl/CBACT04C.cbl</b>.
 * The job has no COBOL reject-return-code branch, so tests assert the
 * clean COMPLETED path and failure propagation from the translated
 * service.</p>
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@Import(InterestCalculationJobTest.TestSecretsManagerConfiguration.class)
@DisplayName("InterestCalculationJob — tasklet orchestration tests (JCL: INTCALC.jcl)")
class InterestCalculationJobTest {

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
    @Qualifier(InterestCalculationJob.JOB_NAME)
    private Job interestCalculationJob;

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
        Mockito.reset(interestCalculationService, auditLogService);
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJob(interestCalculationJob);
    }

    @Nested
    @DisplayName("happy-path execution")
    class HappyPath {

        @Test
        @DisplayName("calculates interest and publishes totals to ExecutionContext")
        void calculatesInterestAndPublishesTotals() throws Exception {
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
            assertThat(stepContext.getString(InterestCalculationJob.CTX_GRAND_TOTAL))
                    .isEqualTo("1234.56");

            verify(interestCalculationService)
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

    @Nested
    @DisplayName("parameter validation")
    class ParameterValidation {

        @Test
        @DisplayName("fails when parmDate is missing")
        void failsWhenParmDateIsMissing() throws Exception {
            JobExecution execution = jobLauncherTestUtils.launchJob(
                    new JobParametersBuilder()
                            .addString(InterestCalculationJob.PARAM_BATCH_RUN_ID,
                                    "interest-missing-date")
                            .addString("correlationId", "corr-interest-missing-date")
                            .toJobParameters());

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(InterestCalculationJobTest::isIllegalArgumentException);
            verify(interestCalculationService, never()).calculateInterest(any(LocalDate.class));
        }

        @Test
        @DisplayName("fails when parmDate is not ISO-8601")
        void failsWhenParmDateIsNotIso8601() throws Exception {
            JobExecution execution = jobLauncherTestUtils.launchJob(
                    interestParams("interest-invalid-date", "20260520"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(InterestCalculationJobTest::isIllegalArgumentException);
            verify(interestCalculationService, never()).calculateInterest(any(LocalDate.class));
        }
    }

    @Nested
    @DisplayName("failure propagation")
    class ServiceFailurePropagation {

        @Test
        @DisplayName("fails the job when InterestCalculationService throws")
        void failsWhenInterestCalculationServiceThrows() throws Exception {
            Mockito.when(interestCalculationService.calculateInterest(any(LocalDate.class)))
                    .thenThrow(new IllegalStateException("INTCALC service failure"));

            JobExecution execution = jobLauncherTestUtils.launchJob(
                    interestParams("interest-service-failure", "2026-05-20"));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(ex -> ex instanceof IllegalStateException
                            && ex.getMessage().contains("INTCALC service failure"));
        }
    }

    private JobParameters interestParams(String batchRunId, String parmDate) {
        return new JobParametersBuilder()
                .addString(InterestCalculationJob.PARAM_BATCH_RUN_ID, batchRunId)
                .addString(InterestCalculationJob.PARAM_PARM_DATE, parmDate)
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